package com.inspiredandroid.kai.network.dtos.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One `data:` frame of an OpenAI-compatible chat-completions SSE stream. Only the fields the
 * accumulator needs are modeled; `usage`, `id`, `model`, and friends ride along as unknown keys.
 *
 * Free Zen models must stream, so the non-streaming [OpenAICompatibleChatResponseDto] never sees
 * this shape — [com.inspiredandroid.kai.network.OpenAIChatSseAccumulator] folds these frames back
 * into that DTO.
 */
@Serializable
internal data class OpenAICompatibleChatChunkDto(
    val choices: List<Choice> = emptyList(),
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
        @SerialName("finish_reason")
        val finishReason: String? = null,
    )

    @Serializable
    data class Delta(
        @Serializable(with = FlexibleContentSerializer::class)
        val content: String? = null,
        /** DeepSeek-style reasoning; Zen's free models send `reasoning` instead (see below). */
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
        /** OpenRouter and Zen free models (e.g. nemotron) put the chain of thought here. */
        val reasoning: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCallDelta>? = null,
    )

    @Serializable
    data class ToolCallDelta(
        /** Tool calls are split across frames; fragments merge by this index. */
        val index: Int = 0,
        val id: String? = null,
        val function: FunctionDelta? = null,
    )

    @Serializable
    data class FunctionDelta(
        val name: String? = null,
        /** Argument JSON fragments; concatenate in arrival order. */
        val arguments: String? = null,
    )
}
