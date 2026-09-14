package com.inspiredandroid.kai.mcp

import com.inspiredandroid.kai.httpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * MCP client over Streamable HTTP, preferring protocol `2026-07-28` with
 * automatic fallback to older versions (`2025-11-25` … `2024-10-07`) for
 * servers that reject the newest version (e.g. Firecrawl).
 *
 * Two connection modes, negotiated automatically per protocol version:
 * - **Stateless** (new servers): requests go out directly with the
 *   `MCP-Protocol-Version` / `Mcp-Method` headers (SEP-2243); no handshake,
 *   no session tracking. Any request can land on any instance behind a
 *   load balancer.
 * - **Legacy** (2024-11-05 / 2025-06-18-era servers): falls back to
 *   `initialize` + `notifications/initialized` with `Mcp-Session-Id`
 *   tracking when the direct attempt fails.
 *
 * Also implements the client side of the `io.modelcontextprotocol/tasks`
 * extension: `tools/call` results with `resultType: "task"` are polled via
 * `tasks/get` until terminal, and `ui://` app templates are fetchable via
 * `resources/read` for the MCP Apps protocol half.
 */
class McpClient(
    private val url: String,
    private val headers: Map<String, String>,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }
    private var sessionId: String? = null
    private var requestId = 0

    /** Protocol version the server accepted during [connect]; used for every later request. */
    var negotiatedVersion: String = McpProtocol.PROTOCOL_VERSION
        private set

    private fun nextId(): Int = ++requestId

    private suspend fun sendRequest(
        method: String,
        params: kotlinx.serialization.json.JsonElement? = null,
        taskId: String? = null,
        protocolVersion: String = negotiatedVersion,
    ): JsonRpcResponse {
        val request = JsonRpcRequest(
            id = nextId(),
            method = method,
            params = params,
        )
        val requestBody = json.encodeToString(JsonRpcRequest.serializer(), request)

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            for ((key, value) in McpProtocol.headers(method, taskId, protocolVersion)) {
                header(key, value)
            }
            sessionId?.let { header("Mcp-Session-Id", it) }
            this@McpClient.headers.keys.forEach { key ->
                header(key, this@McpClient.headers[key]!!)
            }
            setBody(requestBody)
        }

        // Legacy servers still mint sessions; track them when offered.
        response.headers["Mcp-Session-Id"]?.let { sessionId = it }

        val responseText = response.bodyAsText()

        // Handle SSE response
        if (response.headers["Content-Type"]?.contains("text/event-stream") == true) {
            return parseSseResponse(responseText)
        }

        return try {
            json.decodeFromString(JsonRpcResponse.serializer(), responseText)
        } catch (_: Exception) {
            // Some servers (e.g. version-gated ones) answer HTTP 400 with a
            // plain-text body instead of JSON-RPC. Surface the body so callers
            // — especially the version-negotiation loop — can react to it.
            throw McpException(responseText.take(500).ifBlank { "Empty response from server" })
        }
    }

    private fun parseSseResponse(sseText: String): JsonRpcResponse {
        // Parse SSE format: look for "data: " lines and find the JSON-RPC response
        val lines = sseText.lines()
        val dataLines = lines.filter { it.startsWith("data: ") }
        for (line in dataLines) {
            val data = line.removePrefix("data: ").trim()
            if (data.isEmpty()) continue
            try {
                return json.decodeFromString(JsonRpcResponse.serializer(), data)
            } catch (_: Exception) {
                // Not a valid JSON-RPC response, continue
            }
        }
        throw McpException("No valid JSON-RPC response found in SSE stream")
    }

    /**
     * Connect newest-first: for each protocol version, try the stateless bare
     * `tools/list`, then the legacy `initialize` handshake. The first version
     * that yields a usable `tools/list` wins and becomes [negotiatedVersion].
     * A version rejection (`Unsupported protocol version`) moves on to the
     * next older version; any other failure still tries the legacy handshake
     * on the same version before giving up on it.
     */
    suspend fun connect() {
        var lastError: Exception? = null
        for (version in McpProtocol.SUPPORTED_VERSIONS) {
            negotiatedVersion = version
            val probe = try {
                sendRequest("tools/list", protocolVersion = version)
            } catch (e: Exception) {
                if (McpProtocol.isVersionMismatch(e.message)) {
                    lastError = e
                    continue
                }
                lastError = e
                null
            }
            if (probe?.error == null && probe?.result != null) {
                return
            }
            val probeError = probe?.error?.message
            if (McpProtocol.isVersionMismatch(probeError)) {
                lastError = McpException(probeError!!)
                continue
            }
            try {
                initialize(version)
            } catch (e: Exception) {
                lastError = e
                if (McpProtocol.isVersionMismatch(e.message)) continue
                // Non-version failure: still try older versions, since some
                // servers only speak an older dialect end to end.
                continue
            }
            val afterHandshake = try {
                sendRequest("tools/list", protocolVersion = negotiatedVersion)
            } catch (e: Exception) {
                lastError = e
                if (McpProtocol.isVersionMismatch(e.message)) continue
                throw e
            }
            if (afterHandshake.error == null && afterHandshake.result != null) {
                return
            }
            val handshakeError = afterHandshake.error?.message ?: "tools/list returned no result"
            val failure = McpException(handshakeError)
            lastError = failure
            if (McpProtocol.isVersionMismatch(handshakeError)) continue
            throw failure
        }
        throw lastError ?: McpException("Unable to connect: no protocol version accepted")
    }

    suspend fun initialize(version: String = negotiatedVersion) {
        val params = buildJsonObject {
            put("protocolVersion", JsonPrimitive(version))
            put(
                "capabilities",
                buildJsonObject {
                    put(
                        "extensions",
                        buildJsonObject {
                            put(McpProtocol.TASKS_EXTENSION, buildJsonObject { })
                        },
                    )
                },
            )
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", JsonPrimitive("Kai 9000"))
                    put("version", JsonPrimitive("1.0"))
                },
            )
        }
        val response = sendRequest("initialize", params, protocolVersion = version)
        if (response.error != null) {
            throw McpException("Initialize failed: ${response.error.message}")
        }
        // Adopt the server's version when it names one we speak — the spec
        // lets the server pick, and middleboxes may pin sessions to it.
        try {
            val serverVersion = response.result?.jsonObject?.get("protocolVersion")
                ?.jsonPrimitive?.content
            if (serverVersion != null && serverVersion in McpProtocol.SUPPORTED_VERSIONS) {
                negotiatedVersion = serverVersion
            } else {
                negotiatedVersion = version
            }
        } catch (_: Exception) {
            negotiatedVersion = version
        }

        // Send initialized notification (no id, no response expected)
        sendNotification("notifications/initialized")
    }

    private suspend fun sendNotification(method: String) {
        val body = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("method", JsonPrimitive(method))
        }
        val requestBody = json.encodeToString(JsonObject.serializer(), body)

        client.post(url) {
            contentType(ContentType.Application.Json)
            for ((key, value) in McpProtocol.headers(method, protocolVersion = negotiatedVersion)) {
                header(key, value)
            }
            sessionId?.let { header("Mcp-Session-Id", it) }
            this@McpClient.headers.keys.forEach { key ->
                header(key, this@McpClient.headers[key]!!)
            }
            setBody(requestBody)
        }
    }

    suspend fun listTools(): List<McpToolDefinition> {
        val response = sendRequest("tools/list")
        if (response.error != null) {
            throw McpException("tools/list failed: ${response.error.message}")
        }
        val result = response.result ?: return emptyList()
        val toolsResult = json.decodeFromJsonElement<McpToolsResult>(result)
        return toolsResult.tools
    }

    /** Outcome of `tools/call`: final text, or a durable task handle to poll. */
    sealed interface CallOutcome {
        data class Text(val text: String) : CallOutcome
        data class Task(val handle: McpTaskHandle) : CallOutcome
    }

    suspend fun callTool(name: String, arguments: JsonObject): CallOutcome {
        val params = McpProtocol.withTasksCapability(
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("arguments", arguments)
            },
        )
        val response = sendRequest("tools/call", params)
        if (response.error != null) {
            throw McpException("tools/call failed: ${response.error.message}")
        }
        val result = response.result ?: return CallOutcome.Text("")
        val callResult = json.decodeFromJsonElement<McpCallToolResult>(result)
        callResult.taskHandle()?.let { return CallOutcome.Task(it) }
        if (callResult.isError) {
            val errorText = callResult.content.mapNotNull { it.text }.joinToString("\n")
            throw McpException("Tool error: $errorText")
        }
        return CallOutcome.Text(callResult.content.mapNotNull { it.text }.joinToString("\n"))
    }

    /**
     * Drive a task handle to a terminal state. Honors the server's suggested
     * poll interval, gives up after [timeoutMs] (the tool's own timeout owns
     * the outer deadline), and cancels cooperatively on timeout.
     *
     * @throws McpTaskInputRequired when the server pauses for user/model input;
     * the summary describes what was asked so the agent can relay it.
     */
    suspend fun awaitTask(handle: McpTaskHandle, timeoutMs: Long = 50_000L): String {
        val startedAt = currentTimeMs()
        var intervalMs = McpProtocol.pollDelayMs(handle.pollIntervalMs)
        while (true) {
            if (currentTimeMs() - startedAt > timeoutMs) {
                cancelTask(handle.taskId)
                throw McpException("MCP task ${handle.taskId} did not finish within ${timeoutMs / 1000}s")
            }
            delay(intervalMs)
            val state = getTask(handle.taskId)
            intervalMs = McpProtocol.pollDelayMs(state.pollIntervalMs ?: handle.pollIntervalMs)
            when (state.status) {
                "completed" -> {
                    val callResult = state.result?.let {
                        try {
                            json.decodeFromJsonElement<McpCallToolResult>(it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (callResult != null) {
                        if (callResult.isError) {
                            throw McpException(
                                "Tool error: ${callResult.content.mapNotNull { c -> c.text }.joinToString("\n")}",
                            )
                        }
                        return callResult.content.mapNotNull { c -> c.text }.joinToString("\n")
                    }
                    return state.statusMessage ?: ""
                }

                "failed" -> throw McpException(
                    "MCP task failed: ${state.error?.message ?: state.statusMessage ?: handle.taskId}",
                )

                "cancelled" -> throw McpException("MCP task was cancelled: ${handle.taskId}")

                "input_required" -> {
                    val summary = McpProtocol.summarizeInputRequests(state.inputRequests)
                    cancelTask(handle.taskId)
                    throw McpTaskInputRequired(summary)
                }

                else -> { /* still working — keep polling */ }
            }
        }
    }

    private suspend fun getTask(taskId: String): McpTaskState {
        val params = buildJsonObject { put("taskId", JsonPrimitive(taskId)) }
        val response = sendRequest("tasks/get", params, taskId = taskId)
        if (response.error != null) {
            throw McpException("tasks/get failed: ${response.error.message}")
        }
        return response.result?.let {
            json.decodeFromJsonElement<McpTaskState>(it)
        } ?: throw McpException("tasks/get returned no task state")
    }

    private suspend fun cancelTask(taskId: String) {
        try {
            val params = buildJsonObject { put("taskId", JsonPrimitive(taskId)) }
            sendRequest("tasks/cancel", params, taskId = taskId)
        } catch (_: Exception) {
            // Best-effort: cancellation is cooperative and never fatal.
        }
    }

    suspend fun readResource(uri: String): McpReadResourceResult {
        val params = buildJsonObject { put("uri", JsonPrimitive(uri)) }
        val response = sendRequest("resources/read", params)
        if (response.error != null) {
            throw McpException("resources/read failed: ${response.error.message}")
        }
        return response.result?.let {
            json.decodeFromJsonElement<McpReadResourceResult>(it)
        } ?: McpReadResourceResult()
    }

    /**
     * MCP Apps protocol half: fetch the interactive UI template a tool
     * advertises via `_meta.ui.resourceUri`. Returns the HTML document, or
     * null when the resource has no readable text. In-chat rendering of the
     * template is the documented next step — the agent uses the tool itself
     * through the normal path either way.
     */
    suspend fun readAppTemplate(uri: String): McpAppTemplate? {
        val result = try {
            readResource(uri)
        } catch (_: Exception) {
            return null
        }
        val html = result.contents.firstOrNull { it.text != null }?.text
            ?: return null
        return McpAppTemplate(uri = uri, html = html)
    }

    fun close() {
        client.close()
    }
}

class McpException(message: String) : Exception(message)

/** A polled MCP task paused for input the agent must relay, not answer. */
class McpTaskInputRequired(val requestSummary: String?) :
    Exception(
        if (requestSummary != null) {
            "MCP task needs input before continuing:\n$requestSummary"
        } else {
            "MCP task needs input before continuing."
        },
    )

private fun currentTimeMs(): Long = Clock.System.now().toEpochMilliseconds()
