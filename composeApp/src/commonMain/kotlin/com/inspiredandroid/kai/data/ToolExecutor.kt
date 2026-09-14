package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.getAvailableTools
import com.inspiredandroid.kai.getPlatformToolDefinitions
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.smartTruncate
import com.inspiredandroid.kai.tools.ToolApprovalGate
import com.inspiredandroid.kai.tools.ToolApprovalPolicy
import com.inspiredandroid.kai.tools.UntrustedToolOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.jetbrains.compose.resources.getString

private const val MAX_TOOL_RESULT_LENGTH = 20_000
private const val MAX_APPROVAL_DETAIL_LENGTH = 600

class ToolExecutor(
    private val toolsProvider: () -> List<Tool> = { getAvailableTools() },
    /**
     * Asks the user before a risky tool runs. Null only in tests and on platforms that
     * build an executor without DI — production always injects one (see `AppModule`).
     */
    private val approvalGate: ToolApprovalGate? = null,
) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun executeTool(
        name: String,
        arguments: String,
        conversationId: String? = null,
        /**
         * Whether this call belongs to a chat run the user is watching. Non-interactive
         * runs cannot ask for approval, so risky tools fail there instead of executing.
         */
        interactive: Boolean = false,
    ): String {
        val tools = toolsProvider()
        val tool = tools.find { it.schema.name == name }
            ?: return toolResult("""{"success": false, "error": "Unknown tool: $name"}""")

        val args = try {
            parseJsonToMap(arguments)
        } catch (e: Exception) {
            return toolResult("""{"success": false, "error": "Failed to parse arguments: ${e.message}"}""")
        }

        if (!isApproved(name, arguments, interactive)) {
            return toolResult("""{"success": false, "error": "${denialMessage(name, interactive)}"}""")
        }

        return try {
            val result = withTimeout(tool.timeout) {
                if (conversationId != null) {
                    withContext(ConversationIdElement(conversationId)) { tool.execute(args) }
                } else {
                    tool.execute(args)
                }
            }
            val resultString = when (result) {
                is Map<*, *> -> {
                    val jsonObject = JsonObject(
                        result.entries.associate { (k, v) ->
                            k.toString() to anyToJsonElement(v)
                        },
                    )
                    jsonParser.encodeToString(JsonElement.serializer(), jsonObject)
                }

                is String -> result

                else -> """{"result": "$result"}"""
            }
            toolResult(truncateResult(resultString))
        } catch (e: TimeoutCancellationException) {
            toolResult("""{"success": false, "error": "Tool '$name' timed out after ${tool.timeout}"}""")
        } catch (e: CancellationException) {
            // Cooperative cancellation (user pressed stop) must propagate, not become a
            // fake tool result the loop would keep reasoning about.
            throw e
        } catch (e: Exception) {
            toolResult("""{"success": false, "error": "Tool execution failed: ${e.message}"}""")
        }
    }

    /**
     * Every tool result is marked as untrusted before it reaches the model: the content
     * usually comes from outside the conversation (a web page, a mail body, an MCP reply).
     * The matching system-prompt rule is built from the same markers, see
     * [UntrustedToolOutput].
     */
    private fun toolResult(value: String): String = UntrustedToolOutput.wrap(value)

    /**
     * Risky tools run only after the user says so. A non-interactive run (scheduled task,
     * heartbeat, silent call) can never ask, so it fails here rather than running a
     * privileged command unattended.
     */
    private suspend fun isApproved(
        name: String,
        arguments: String,
        interactive: Boolean,
    ): Boolean {
        val reason = ToolApprovalPolicy.approvalReason(name) ?: return true
        val gate = approvalGate ?: return true
        if (!interactive) return false
        return gate.awaitApproval(
            toolId = name,
            toolName = getToolDisplayName(name),
            reason = reason,
            detail = arguments.take(MAX_APPROVAL_DETAIL_LENGTH),
        )
    }

    private fun denialMessage(
        name: String,
        interactive: Boolean,
    ): String = if (interactive) {
        "The user denied '$name'. Do not retry it — ask what they want to change."
    } else {
        "'$name' needs the user's approval and no chat is open to ask. Tell the user what you tried to run so they can start it from a chat."
    }

    private fun truncateResult(result: String): String = result.smartTruncate(MAX_TOOL_RESULT_LENGTH)

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull

        is String -> JsonPrimitive(value)

        is Boolean -> JsonPrimitive(value)

        is Number -> JsonPrimitive(value)

        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) },
        )

        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })

        else -> JsonPrimitive(value.toString())
    }

    private fun parseJsonToMap(json: String): Map<String, Any> {
        // Some providers emit an empty string instead of "{}" for tools that take
        // no arguments; treat it as an empty object instead of a parse failure.
        if (json.isBlank()) return emptyMap()
        val jsonObject = jsonParser.parseToJsonElement(json).jsonObject
        return jsonObject.toMap()
    }

    private fun JsonObject.toMap(): Map<String, Any> = entries.mapNotNull { (key, value) ->
        // A JSON null means "not provided" — dropping it lets tools treat the
        // argument as omitted instead of seeing the literal string "null".
        if (value is JsonNull) null else key to jsonElementToAny(value)
    }.toMap()

    private fun jsonElementToAny(element: JsonElement): Any = when (element) {
        JsonNull -> "null"

        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.boolean
            element.intOrNull != null -> element.int
            element.longOrNull != null -> element.long
            element.doubleOrNull != null -> element.double
            else -> element.content
        }

        is JsonObject -> element.entries.mapNotNull { (k, v) ->
            if (v is JsonNull) null else k to jsonElementToAny(v)
        }.toMap()

        // Array elements follow the same "JSON null = not provided" rule as object
        // values, so a null inside an array is dropped instead of becoming "null".
        is JsonArray -> element.mapNotNull { if (it is JsonNull) null else jsonElementToAny(it) }
    }

    suspend fun getToolDisplayName(toolId: String): String {
        val toolInfo = getPlatformToolDefinitions().find { it.id == toolId } ?: return toolId
        return toolInfo.nameRes?.let { getString(it) } ?: toolInfo.name
    }
}
