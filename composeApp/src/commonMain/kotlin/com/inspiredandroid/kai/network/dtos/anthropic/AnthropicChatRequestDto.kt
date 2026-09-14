package com.inspiredandroid.kai.network.dtos.anthropic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class AnthropicChatRequestDto(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int = 8192,
    /**
     * Plain string or a blocks array. Kai always sends the blocks form with a
     * prompt-caching breakpoint when a system prompt is present, so repeated
     * tool-loop turns re-read the static prefix at cache-read prices.
     */
    val system: JsonElement? = null,
    val tools: List<Tool>? = null,
) {
    @Serializable
    data class Message(
        val role: String,
        val content: JsonElement,
    )

    @Serializable
    data class CacheControl(
        val type: String = "ephemeral",
    )

    @Serializable
    data class Tool(
        val name: String,
        val description: String,
        val input_schema: InputSchema,
        val cache_control: CacheControl? = null,
    )

    @Serializable
    data class InputSchema(
        val type: String = "object",
        val properties: Map<String, PropertySchema>,
        val required: List<String> = emptyList(),
    )

    @Serializable
    data class PropertySchema(
        val type: String,
        val description: String? = null,
        val enum: List<String>? = null,
        val items: PropertySchema? = null,
        val properties: Map<String, PropertySchema>? = null,
        val required: List<String>? = null,
    )

    companion object {
        /**
         * System prompt in the blocks form with an ephemeral cache breakpoint.
         * The breakpoint must sit on the last block shared across turns; the
         * system prompt is Kai's static prefix (soul, memories, tool guidance),
         * so it is the ideal anchor. No beta header needed — GA since Dec 2024.
         */
        fun cachedSystemPrompt(text: String): JsonElement = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    put("cache_control", buildJsonObject { put("type", "ephemeral") })
                },
            )
        }
    }
}
