package com.inspiredandroid.kai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject? = null,
    /**
     * Tool-level `_meta`. Read for the MCP Apps extension: a tool that ships
     * an interactive UI declares `_meta.ui.resourceUri` pointing at a `ui://`
     * resource ([McpToolDefinition.uiResourceUri]). Unknown keys are ignored.
     */
    val _meta: JsonObject? = null,
) {
    /** `ui://` resource backing this tool's MCP App UI, or null. */
    fun uiResourceUri(): String? = try {
        _meta?.get("ui")?.jsonObject?.get("resourceUri")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
}

@Serializable
data class McpToolsResult(
    val tools: List<McpToolDefinition> = emptyList(),
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContent> = emptyList(),
    @SerialName("isError")
    val isError: Boolean = false,
    /**
     * Polymorphic discriminator (SEP-2322). `"task"` means this result is a
     * `CreateTaskResult` and the synchronous [content] is absent — the real
     * outcome arrives via `tasks/get` polling. Anything else (or absent)
     * means [content] is final.
     */
    val resultType: String? = null,
    val taskId: String? = null,
    val status: String? = null,
    val ttlMs: Long? = null,
    val pollIntervalMs: Long? = null,
)

fun McpCallToolResult.taskHandle(): McpTaskHandle? = if (resultType == "task" && taskId != null) {
    McpTaskHandle(taskId = taskId, pollIntervalMs = pollIntervalMs)
} else {
    null
}

/** Durable handle from a `CreateTaskResult` (`io.modelcontextprotocol/tasks`). */
data class McpTaskHandle(
    val taskId: String,
    val pollIntervalMs: Long?,
)

/**
 * `tasks/get` response body. On `completed`, [result] holds whatever the
 * original request would have returned synchronously (for `tools/call`, a
 * [McpCallToolResult]); on `failed`, [error] carries the JSON-RPC error; on
 * `input_required`, [inputRequests] carries the outstanding elicitations for
 * `tasks/update`.
 */
@Serializable
data class McpTaskState(
    val taskId: String = "",
    val status: String = "",
    val statusMessage: String? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    val inputRequests: JsonObject? = null,
    val pollIntervalMs: Long? = null,
)

@Serializable
data class McpResourceContent(
    val uri: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

@Serializable
data class McpReadResourceResult(
    val contents: List<McpResourceContent> = emptyList(),
)

/** Fetched MCP App UI template: an HTML document plus its source URI. */
data class McpAppTemplate(
    val uri: String,
    val html: String,
)

@Serializable
data class McpContent(
    val type: String,
    val text: String? = null,
)

data class McpToolMetadata(
    val serverId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject?,
    /**
     * `ui://` resource backing this tool's MCP App UI (from the tool's
     * `_meta.ui.resourceUri`), or null. Protocol half of MCP Apps: discovery
     * and fetch are implemented ([McpClient.readAppTemplate]); in-chat
     * rendering is the documented next step.
     */
    val appUiUri: String? = null,
)
