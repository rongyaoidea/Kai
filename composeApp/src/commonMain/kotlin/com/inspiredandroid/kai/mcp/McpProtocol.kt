package com.inspiredandroid.kai.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Pure protocol helpers for the MCP Streamable HTTP surface (stateless core +
 * `io.modelcontextprotocol/tasks` extension). No networking here so the wire
 * shapes stay unit-testable; [McpClient] owns the HTTP.
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2026-07-28"
    const val TASKS_EXTENSION = "io.modelcontextprotocol/tasks"

    /**
     * Protocol versions Kai can speak, newest first. [PROTOCOL_VERSION] is the
     * preferred one; older entries exist for servers that predate it (e.g.
     * Firecrawl, which currently caps at 2025-11-25 and rejects 2026-07-28
     * with `Bad Request: Unsupported protocol version`). [McpClient] walks
     * this list during connect until a version sticks.
     */
    val SUPPORTED_VERSIONS = listOf(
        PROTOCOL_VERSION,
        "2025-11-25",
        "2025-06-18",
        "2025-03-26",
        "2024-11-05",
        "2024-10-07",
    )

    /**
     * True when an error message looks like a protocol-version rejection, so
     * the client knows to retry with an older version instead of surfacing
     * the failure. Matches both JSON-RPC errors and plain-HTTP 400 bodies.
     */
    fun isVersionMismatch(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return "protocol version" in lower ||
            ("unsupported" in lower && "version" in lower)
    }

    /**
     * Standard headers for every Streamable HTTP request (SEP-2243): the
     * negotiated protocol version plus the JSON-RPC method for routing.
     * `Mcp-Name` carries the task id on `tasks/get|update|cancel` so load
     * balancers pin follow-ups to the instance holding the task state.
     */
    fun headers(method: String, taskId: String? = null, protocolVersion: String = PROTOCOL_VERSION): Map<String, String> = buildMap {
        put("MCP-Protocol-Version", protocolVersion)
        put("Mcp-Method", method)
        if (taskId != null) put("Mcp-Name", taskId)
    }

    /**
     * The Tasks capability block a client attaches to a request's `_meta`
     * (per the Tasks extension's client guide). Merges with — never replaces
     * — an existing `_meta` object.
     */
    fun withTasksCapability(params: JsonObject): JsonObject {
        val existing = try {
            params["_meta"]?.jsonObject
        } catch (_: Exception) {
            null
        }
        val capabilities = buildJsonObject {
            put(
                "extensions",
                buildJsonObject {
                    put(TASKS_EXTENSION, buildJsonObject { })
                },
            )
        }
        val meta = buildJsonObject {
            if (existing != null) {
                for ((key, value) in existing) put(key, value)
            }
            put("io.modelcontextprotocol/clientCapabilities", capabilities)
        }
        return buildJsonObject {
            for ((key, value) in params) {
                if (key != "_meta") put(key, value)
            }
            put("_meta", meta)
        }
    }

    /** Terminal task statuses: polling stops and the state is final. */
    fun isTerminalStatus(status: String): Boolean = status == "completed" || status == "failed" || status == "cancelled"

    /** Poll interval honoring the server hint, clamped against hammering. */
    fun pollDelayMs(pollIntervalMs: Long?): Long = (pollIntervalMs ?: 2_000L).coerceIn(500L, 10_000L)

    /**
     * One-line-per-key summary of outstanding `inputRequests` for the
     * `input_required` path, so the agent sees what the server asked for
     * instead of an opaque state name.
     */
    fun summarizeInputRequests(inputRequests: JsonElement?, maxChars: Int = 1_000): String? {
        val obj = try {
            inputRequests?.jsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        if (obj.isEmpty()) return null
        return obj.entries.joinToString("\n") { (key, value) ->
            val message = try {
                value.jsonObject["message"]?.jsonPrimitive?.content
                    ?: value.jsonObject["prompt"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
            "- $key${if (message != null) ": $message" else ""}"
        }.take(maxChars)
    }
}
