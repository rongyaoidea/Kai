package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatChunkDto
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatResponseDto
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesResponseDto
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val sseJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/**
 * Reads an SSE body line by line and hands every complete `data:` payload to [onData].
 * Comment lines (`: keep-alive`, which the Zen gateway emits while thinking), `event:` lines,
 * and blank lines are skipped; `[DONE]` ends the stream (the gateway appends a stray
 * `data: {"choices":[],"cost":"0"}` frame after it that must not be parsed).
 */
internal suspend fun ByteReadChannel.readSseData(onData: (String) -> Unit) {
    while (true) {
        val line = readUTF8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.substring(5).trim()
        if (data.isEmpty()) continue
        if (data == "[DONE]") return
        onData(data)
    }
}

/**
 * Folds a chat-completions SSE stream back into the non-streaming [OpenAICompatibleChatResponseDto]
 * shape so the rest of Kai (tool loop, history, reasoning echo) is unaware streaming happened.
 * Text, `reasoning`/`reasoning_content`, and tool-call fragments (merged by index) are
 * accumulated; malformed frames are ignored rather than failing the whole turn.
 */
internal class OpenAIChatSseAccumulator {

    private val content = StringBuilder()
    private val reasoning = StringBuilder()
    private val toolCalls = mutableMapOf<Int, ToolCallBuilder>()

    fun accept(data: String) {
        val chunk = try {
            sseJson.decodeFromString(OpenAICompatibleChatChunkDto.serializer(), data)
        } catch (_: Exception) {
            return
        }
        val delta = chunk.choices.firstOrNull()?.delta ?: return
        delta.content?.let(content::append)
        delta.reasoningContent?.let(reasoning::append)
        delta.reasoning?.let(reasoning::append)
        delta.toolCalls.orEmpty().forEach { frame ->
            val builder = toolCalls.getOrPut(frame.index) { ToolCallBuilder() }
            frame.id?.let { builder.id = it }
            frame.function?.name?.let { builder.name = it }
            frame.function?.arguments?.let(builder.arguments::append)
        }
    }

    fun build(): OpenAICompatibleChatResponseDto {
        val calls = toolCalls.toSortedMap().entries.mapNotNull { (index, builder) ->
            val name = builder.name ?: return@mapNotNull null
            OpenAICompatibleChatResponseDto.ToolCall(
                id = builder.id ?: "call_$index",
                function = OpenAICompatibleChatResponseDto.FunctionCall(
                    name = name,
                    arguments = builder.arguments.toString().ifEmpty { "{}" },
                ),
            )
        }
        return OpenAICompatibleChatResponseDto(
            choices = listOf(
                OpenAICompatibleChatResponseDto.Choice(
                    message = OpenAICompatibleChatResponseDto.Choice.Message(
                        role = "assistant",
                        content = content.toString().takeIf { it.isNotEmpty() },
                        reasoningContent = reasoning.toString().takeIf { it.isNotEmpty() },
                        toolCalls = calls.takeIf { it.isNotEmpty() },
                    ),
                ),
            ),
        )
    }

    private class ToolCallBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }
}

/**
 * Folds a Responses-API SSE stream into [OpenAIResponsesResponseDto]. The `response.completed`
 * event carries the full response object, which is used verbatim; the item-based events feed a
 * fallback for truncated streams, and `response.function_call_arguments.*` repairs items whose
 * streamed form ends up with empty arguments (Zen's free Muse Spark does this).
 */
internal class OpenAIResponsesSseAccumulator {

    private val text = StringBuilder()
    private var sawTextDelta = false
    private val functionArguments = mutableMapOf<String, String>()
    private val functionNames = mutableMapOf<String, String>()
    private val items = LinkedHashMap<String, OpenAIResponsesResponseDto.OutputItem>()
    private var completed: OpenAIResponsesResponseDto? = null

    fun accept(data: String) {
        val event = try {
            sseJson.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            return
        }
        when (event.string("type")) {
            "response.output_text.delta" -> {
                event.string("delta")?.let {
                    sawTextDelta = true
                    text.append(it)
                }
            }

            "response.output_text.done" -> {
                val done = event.string("text")
                if (!sawTextDelta && done != null) text.append(done)
            }

            "response.function_call_arguments.delta" -> {
                val itemId = event.string("item_id") ?: return
                functionArguments[itemId] = functionArguments.getOrPut(itemId) { "" } + event.string("delta").orEmpty()
            }

            "response.function_call_arguments.done" -> {
                val itemId = event.string("item_id") ?: return
                event.string("arguments")?.let { functionArguments[itemId] = it }
                event.string("name")?.let { functionNames[itemId] = it }
            }

            "response.output_item.added", "response.output_item.done" -> {
                val item = event["item"] as? JsonObject ?: return
                val parsed = parseItem(item) ?: return
                val key = parsed.id ?: parsed.callId ?: return
                items[key] = parsed
            }

            "response.completed", "response.incomplete", "response.failed" -> {
                val response = event["response"] as? JsonObject ?: return
                completed = try {
                    sseJson.decodeFromJsonElement(OpenAIResponsesResponseDto.serializer(), response)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    fun build(): OpenAIResponsesResponseDto {
        val base = completed
        if (base != null) {
            return base.copy(output = base.output.map(::enrich))
        }
        val output = items.values.map(::enrich).toMutableList()
        if (text.isNotEmpty() && output.none { it.type == "message" }) {
            output.add(
                0,
                OpenAIResponsesResponseDto.OutputItem(
                    type = "message",
                    role = "assistant",
                    content = listOf(OpenAIResponsesResponseDto.ContentPart(type = "output_text", text = text.toString())),
                ),
            )
        }
        return OpenAIResponsesResponseDto(output = output)
    }

    /** Restores arguments/name that only arrived via delta events (completed items can be empty). */
    private fun enrich(item: OpenAIResponsesResponseDto.OutputItem): OpenAIResponsesResponseDto.OutputItem {
        if (item.type != "function_call") return item
        val key = item.id ?: item.callId
        val arguments = item.arguments?.takeIf { it.isNotEmpty() }
            ?: key?.let { functionArguments[it] }
            ?: item.arguments
        val name = item.name ?: key?.let { functionNames[it] }
        return item.copy(arguments = arguments, name = name)
    }

    private fun parseItem(item: JsonObject): OpenAIResponsesResponseDto.OutputItem? = try {
        sseJson.decodeFromJsonElement(OpenAIResponsesResponseDto.OutputItem.serializer(), item)
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
}
