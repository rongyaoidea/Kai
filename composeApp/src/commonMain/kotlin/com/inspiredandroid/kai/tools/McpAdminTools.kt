package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the agent install, inspect, and remove MCP servers itself — the same
 * operations the Tools tab exposes, so anything it connects shows up in
 * Settings automatically (the UI reads the same store).
 *
 * Registration is deliberately two-step in the UI, but one step here: adding a
 * server connects it and returns the tools that came back, so the agent learns
 * immediately whether the endpoint worked instead of waiting for a later chat.
 */
object McpAdminTools {

    private fun describe(manager: McpServerManager): List<Map<String, Any?>> = manager.getServers().map { server ->
        mapOf(
            "id" to server.id,
            "name" to server.name,
            "url" to server.url,
            "enabled" to server.isEnabled,
            "connected" to manager.isConnected(server.id),
            "tools" to manager.getToolsForServer(server.id).map { it.name },
        )
    }

    val listServersTool = { manager: McpServerManager ->
        object : Tool {
            override val schema = ToolSchema(
                name = "list_mcp_servers",
                description = "List every configured MCP server with its id, URL, enabled/connected state, and the tools it exposes. Call this before add/remove so you reuse ids instead of creating duplicates.",
                parameters = emptyMap(),
            )

            override suspend fun execute(args: Map<String, Any>): Any = mapOf(
                "success" to true,
                "servers" to describe(manager),
            )
        }
    }

    val addServerTool = { manager: McpServerManager ->
        object : Tool {
            override val schema = ToolSchema(
                name = "add_mcp_server",
                description = "Install an MCP server and connect to it in one step. Give a short name and its Streamable HTTP URL; optional headers carry an API key (e.g. {\"Authorization\":\"Bearer …\"}). Returns the tools the server exposes. Tell the user what you added — it appears in Settings → Tools → MCP Servers automatically.",
                parameters = mapOf(
                    "name" to ParameterSchema("string", "Short display name, e.g. \"Context7\"", true),
                    "url" to ParameterSchema("string", "Streamable HTTP endpoint, e.g. https://mcp.example.com/mcp", true),
                    "headers" to ParameterSchema("object", "Optional request headers, e.g. {\"Authorization\": \"Bearer …\"}", false),
                ),
            )

            @Suppress("UNCHECKED_CAST")
            override suspend fun execute(args: Map<String, Any>): Any {
                val name = (args["name"] as? String)?.trim().orEmpty()
                val url = (args["url"] as? String)?.trim().orEmpty()
                if (name.isEmpty()) return mapOf("success" to false, "error" to "name is required")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return mapOf("success" to false, "error" to "url must start with http:// or https://")
                }
                if (manager.getServers().any { normalizeServerUrl(it.url) == normalizeServerUrl(url) }) {
                    return mapOf("success" to false, "error" to "An MCP server with this URL already exists — call list_mcp_servers to reuse it, or remove_mcp_server first and then add again")
                }
                val headers = parseHeadersArg(args["headers"])
                val server = manager.addServer(name, url, headers)
                val discovered = manager.connectAndDiscoverTools(server.id)
                return discovered.fold(
                    onSuccess = { tools ->
                        mapOf(
                            "success" to true,
                            "id" to server.id,
                            "name" to server.name,
                            "tools" to tools.map { it.name },
                        )
                    },
                    onFailure = { error ->
                        // Keep the server configured — a transient failure should not
                        // force a re-add, and the UI has a Refresh action for retries.
                        // The agent has no refresh tool, so point it at the
                        // remove-then-add retry it can actually perform.
                        mapOf(
                            "success" to false,
                            "id" to server.id,
                            "error" to "Server was added but the connection failed: ${error.message}. Retry from Settings → Tools → MCP Servers → Refresh, or run remove_mcp_server and then add_mcp_server again.",
                        )
                    },
                )
            }
        }
    }

    val removeServerTool = { manager: McpServerManager ->
        object : Tool {
            override val schema = ToolSchema(
                name = "remove_mcp_server",
                description = "Remove a configured MCP server by its id (from list_mcp_servers). Its tools stop being available.",
                parameters = mapOf(
                    "server_id" to ParameterSchema("string", "Server id from list_mcp_servers", true),
                ),
            )

            override suspend fun execute(args: Map<String, Any>): Any {
                val id = args["server_id"]?.toString()?.trim().orEmpty()
                if (id.isEmpty()) return mapOf("success" to false, "error" to "server_id is required")
                if (manager.getServers().none { it.id == id }) {
                    return mapOf("success" to false, "error" to "No MCP server with id=$id")
                }
                manager.removeServer(id)
                return mapOf("success" to true, "removed" to id)
            }
        }
    }

    /** Trailing slashes carry no meaning for server identity — ignore them when de-duplicating. */
    internal fun normalizeServerUrl(url: String): String = url.trim().trimEnd('/').lowercase()

    /**
     * Headers arrive as a JSON object from most models, but some serialize the
     * whole map as a string. Accept both instead of silently dropping auth.
     */
    internal fun parseHeadersArg(raw: Any?): Map<String, String> {
        (raw as? Map<*, *>)?.entries
            ?.associate { (k, v) -> k.toString() to v.toString() }
            ?.let { return it }
        val text = (raw as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.entries.mapNotNull { (k, v) ->
                runCatching { k to v.jsonPrimitive.content }.getOrNull()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    val toolInfos = listOf(
        ToolInfo(
            id = "list_mcp_servers",
            name = "List MCP Servers",
            description = "List installed MCP servers and their tools",
            isEnabled = true,
        ),
        // Installing a server hands the agent a new, attacker-controllable source of tool
        // descriptions and results, so it is opt-in: the user turns the switch on instead of
        // discovering that a fetched page already did.
        ToolInfo(
            id = "add_mcp_server",
            name = "Install MCP Server",
            description = "Add and connect an MCP server",
            isEnabled = false,
        ),
        ToolInfo(
            id = "remove_mcp_server",
            name = "Remove MCP Server",
            description = "Remove an installed MCP server",
            isEnabled = false,
        ),
    )
}
