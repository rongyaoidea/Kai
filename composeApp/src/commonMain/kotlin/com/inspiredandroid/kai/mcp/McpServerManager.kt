package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.SettingsJsonList
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

private val serverIdRegex = Regex("[^a-z0-9]")

class McpServerManager(private val appSettings: AppSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val mutex = Mutex()
    private val clients = mutableMapOf<String, McpClient>()
    private val discoveredTools = mutableMapOf<String, List<McpToolMetadata>>()

    // NOTE: the mutators below are read-modify-write without a lock. Making them safe means making
    // them suspend, which ripples through DataRepository and the settings UI — tracked separately.
    private val servers = SettingsJsonList(
        read = appSettings::getMcpServersJson,
        write = appSettings::setMcpServersJson,
        itemSerializer = McpServerConfig.serializer(),
        label = "McpServerManager",
        json = json,
    )

    init {
        // One-shot: fill missing popular default headers (e.g. Jina Authorization) without
        // overwriting headers the user already configured.
        migratePopularDefaultHeaders()
    }

    fun getServers(): List<McpServerConfig> = servers.get()

    private fun saveServers(servers: List<McpServerConfig>) = this.servers.set(servers)

    private fun migratePopularDefaultHeaders() {
        val current = servers.get()
        val updated = applyPopularDefaultHeaders(current)
        if (updated !== current) {
            saveServers(updated)
        }
    }

    fun addServer(name: String, url: String, headers: Map<String, String>): McpServerConfig {
        val servers = getServers().toMutableList()
        val id = generateServerId(name, servers)
        // If the user adds a popular endpoint manually without headers, still apply defaults
        // for missing keys only (never clobber explicit headers they typed).
        val popularDefaults = popularMcpServers
            .firstOrNull { matchesPopularMcpUrl(url, it.url) }
            ?.headers
            .orEmpty()
        val mergedHeaders = mergeMissingHeaders(headers, popularDefaults)
        val config = McpServerConfig(id = id, name = name, url = url, headers = mergedHeaders)
        servers.add(config)
        saveServers(servers)
        return config
    }

    fun removeServer(serverId: String) {
        val servers = getServers().toMutableList()
        servers.removeAll { it.id == serverId }
        saveServers(servers)
        clients[serverId]?.close()
        clients.remove(serverId)
        discoveredTools.remove(serverId)
    }

    fun setServerEnabled(serverId: String, enabled: Boolean) {
        val servers = getServers().toMutableList()
        val index = servers.indexOfFirst { it.id == serverId }
        if (index >= 0) {
            servers[index] = servers[index].copy(isEnabled = enabled)
            saveServers(servers)
        }
        if (!enabled) {
            clients[serverId]?.close()
            clients.remove(serverId)
            discoveredTools.remove(serverId)
        }
    }

    suspend fun connectAndDiscoverTools(serverId: String): Result<List<McpToolMetadata>> {
        val server = getServers().find { it.id == serverId }
            ?: return Result.failure(McpException("Server not found: $serverId"))

        // Close existing client if any
        mutex.withLock { clients[serverId] }?.close()

        val client = McpClient(server.url, server.headers)
        return try {
            client.connect()
            val toolDefs = client.listTools()
            val metadata = toolDefs.map { def ->
                McpToolMetadata(
                    serverId = serverId,
                    name = def.name,
                    description = def.description ?: "",
                    inputSchema = def.inputSchema,
                    appUiUri = def.uiResourceUri(),
                )
            }
            mutex.withLock {
                clients[serverId] = client
                discoveredTools[serverId] = metadata
            }
            Result.success(metadata)
        } catch (e: Exception) {
            client.close()
            mutex.withLock {
                clients.remove(serverId)
                discoveredTools.remove(serverId)
            }
            Result.failure(e)
        }
    }

    fun getEnabledMcpTools(): List<Tool> {
        val enabledServers = getServers().filter { it.isEnabled }.map { it.id }.toSet()
        // Tool lookup is by bare name, so two servers exposing the same tool
        // name would shadow each other non-deterministically. First server wins;
        // the rest stay visible in Settings per server.
        val seenNames = mutableSetOf<String>()
        return buildList {
            for ((serverId, tools) in discoveredTools) {
                if (serverId !in enabledServers) continue
                val client = clients[serverId] ?: continue
                for (meta in tools) {
                    if (!seenNames.add(meta.name)) continue
                    val toolId = McpTool.toolId(serverId, meta.name)
                    if (appSettings.isToolEnabled(toolId)) {
                        add(McpTool(client, meta))
                    }
                }
            }
        }
    }

    fun getToolsForServer(serverId: String): List<ToolInfo> {
        val tools = discoveredTools[serverId] ?: return emptyList()
        return tools.map { meta ->
            val toolId = McpTool.toolId(serverId, meta.name)
            ToolInfo(
                id = toolId,
                name = meta.name,
                description = meta.description,
                isEnabled = appSettings.isToolEnabled(toolId),
                appUiUri = meta.appUiUri,
            )
        }
    }

    /**
     * Fetches the interactive UI template a tool advertises, for in-app
     * rendering. Null when the tool declares none or the fetch fails.
     */
    suspend fun fetchAppTemplate(serverId: String, toolName: String): McpAppTemplate? {
        val meta = discoveredTools[serverId]?.firstOrNull { it.name == toolName } ?: return null
        val uri = meta.appUiUri ?: return null
        val client = clients[serverId] ?: return null
        return try {
            client.readAppTemplate(uri)
        } catch (_: Exception) {
            null
        }
    }

    /** Proxies an MCP App UI-initiated tool call to its server. */
    suspend fun callAppTool(serverId: String, toolName: String, argsJson: String): String {
        val client = clients[serverId] ?: throw McpException("Server not connected: $serverId")
        val args = try {
            Json.parseToJsonElement(argsJson).jsonObject
        } catch (_: Exception) {
            throw McpException("Invalid tool arguments JSON")
        }
        return when (val outcome = client.callTool(toolName, args)) {
            is McpClient.CallOutcome.Text -> outcome.text
            is McpClient.CallOutcome.Task -> client.awaitTask(outcome.handle)
        }
    }

    suspend fun connectEnabledServers() {
        val enabledServers = getServers().filter { it.isEnabled }
        coroutineScope {
            enabledServers
                .filter { !clients.containsKey(it.id) }
                .map { server ->
                    async {
                        try {
                            connectAndDiscoverTools(server.id)
                        } catch (_: Exception) {
                            // Individual server failures shouldn't block others
                        }
                    }
                }
                .awaitAll()
        }
    }

    fun isConnected(serverId: String): Boolean = clients.containsKey(serverId)

    private fun generateServerId(name: String, existing: List<McpServerConfig>): String {
        val base = name.lowercase().replace(serverIdRegex, "_").take(30)
        val existingIds = existing.map { it.id }.toSet()
        if (base !in existingIds) return base
        var counter = 2
        while ("${base}_$counter" in existingIds) counter++
        return "${base}_$counter"
    }
}
