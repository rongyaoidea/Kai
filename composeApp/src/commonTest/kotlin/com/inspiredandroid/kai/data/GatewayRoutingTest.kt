package com.inspiredandroid.kai.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The OpenCode gateway serves different model families on different endpoints
 * (opencode.ai/docs/zen, /go): chat completions by default, Responses for
 * GPT/Grok/Muse Spark, Messages for Claude/Qwen (plus MiniMax on Go only).
 */
class GatewayRoutingTest {

    private val zenBase = "https://opencode.ai/zen/v1"
    private val goBase = "https://opencode.ai/zen/go/v1"

    @Test
    fun `zen responses families route to the responses api`() {
        assertTrue(requiresResponsesApi(Service.OpenCode, "gpt-5.1-codex"))
        assertTrue(requiresResponsesApi(Service.OpenCode, "gpt-5.6-luna"))
        assertTrue(requiresResponsesApi(Service.OpenCode, "grok-4.5"))
        assertTrue(requiresResponsesApi(Service.OpenCode, "muse-spark-1.3-contributor-free"))
        assertTrue(requiresResponsesApi(Service.OpenCode, "muse-spark-1.3"))
    }

    @Test
    fun `zen chat families stay on chat completions`() {
        assertFalse(requiresResponsesApi(Service.OpenCode, "kimi-k2.6"))
        assertFalse(requiresResponsesApi(Service.OpenCode, "deepseek-v4-pro"))
        assertFalse(requiresResponsesApi(Service.OpenCode, "mimo-v2.5-free"))
        assertFalse(requiresResponsesApi(Service.OpenCode, "big-pickle"))
        // Messages families are not Responses families.
        assertFalse(requiresResponsesApi(Service.OpenCode, "claude-sonnet-4-5"))
        assertFalse(requiresResponsesApi(Service.OpenCode, "qwen3.7-plus"))
        assertFalse(requiresResponsesApi(Service.OpenCode, "minimax-m2.7"))
    }

    @Test
    fun `go base url routes responses families too`() {
        assertTrue(requiresResponsesApi(Service.OpenAICompatible, "gpt-5.6-luna", goBase))
        assertTrue(requiresResponsesApi(Service.OpenAICompatible, "muse-spark-1.3-contributor", goBase))
        assertFalse(requiresResponsesApi(Service.OpenAICompatible, "kimi-k2.6", goBase))
        assertFalse(requiresResponsesApi(Service.OpenAICompatible, "gpt-5.6-luna", "http://localhost:11434/v1"))
    }

    @Test
    fun `zen messages families route to the messages api`() {
        assertTrue(requiresMessagesApi(Service.OpenCode, "claude-sonnet-4-5"))
        assertTrue(requiresMessagesApi(Service.OpenCode, "claude-opus-4-6"))
        assertTrue(requiresMessagesApi(Service.OpenCode, "qwen3.7-plus"))
        assertTrue(requiresMessagesApi(Service.OpenCode, "qwen3.5-plus"))
    }

    @Test
    fun `zen minimax stays on chat completions`() {
        assertFalse(requiresMessagesApi(Service.OpenCode, "minimax-m2.7"))
        assertFalse(requiresMessagesApi(Service.OpenCode, "minimax-m3"))
    }

    @Test
    fun `go messages families include minimax`() {
        assertTrue(requiresMessagesApi(Service.OpenAICompatible, "minimax-m2.7", goBase))
        assertTrue(requiresMessagesApi(Service.OpenAICompatible, "qwen3.8-flash", goBase))
        assertTrue(requiresMessagesApi(Service.OpenAICompatible, "claude-sonnet-4-5", goBase))
        assertFalse(requiresMessagesApi(Service.OpenAICompatible, "kimi-k2.6", goBase))
        assertFalse(requiresMessagesApi(Service.OpenAICompatible, "grok-4.6", goBase))
    }

    @Test
    fun `zen base url keeps zen lists`() {
        assertTrue(requiresMessagesApi(Service.OpenAICompatible, "claude-sonnet-4-5", zenBase))
        assertFalse(requiresMessagesApi(Service.OpenAICompatible, "minimax-m2.7", zenBase))
    }

    @Test
    fun `non gateway services never route to messages`() {
        assertFalse(requiresMessagesApi(Service.Anthropic, "claude-sonnet-4-5"))
        assertFalse(requiresMessagesApi(Service.OpenRouter, "anthropic/claude-sonnet-4-5"))
        assertFalse(requiresMessagesApi(Service.OpenAICompatible, "claude-sonnet-4-5", "http://localhost:11434/v1"))
    }

    @Test
    fun `go preset routes like the go base url`() {
        assertTrue(requiresResponsesApi(Service.OpenCodeGo, "gpt-5.6-luna"))
        assertTrue(requiresResponsesApi(Service.OpenCodeGo, "muse-spark-1.3-contributor"))
        assertFalse(requiresResponsesApi(Service.OpenCodeGo, "kimi-k2.6"))
        assertTrue(requiresMessagesApi(Service.OpenCodeGo, "minimax-m2.7"))
        assertTrue(requiresMessagesApi(Service.OpenCodeGo, "qwen3.8-flash"))
        assertTrue(requiresMessagesApi(Service.OpenCodeGo, "claude-sonnet-4-5"))
        assertFalse(requiresMessagesApi(Service.OpenCodeGo, "kimi-k2.6"))
        assertFalse(requiresMessagesApi(Service.OpenCodeGo, "grok-4.6"))
        @Test
    fun `zen strict free models stay silent`() {
        for (model in ZEN_FREE_NO_ECHO_MODELS) {
            assertEquals(
                ReasoningRequestMode.NONE,
                reasoningModeFor(Service.OpenCode, model),
                "$model must not echo reasoning",
            )
        }
        // Paid Zen routes keep the echo their upstreams require.
        assertEquals(
            ReasoningRequestMode.REASONING_CONTENT,
            reasoningModeFor(Service.OpenCode, "deepseek-v4-pro"),
        )
        assertEquals(
            ReasoningRequestMode.REASONING_CONTENT,
            reasoningModeFor(Service.OpenCode, "kimi-k2.6"),
        )
    }
}

    @Test
    fun `only zen echoes reasoning content back`() {
        // The Go gateway answers any non-standard message field (including
        // reasoning_content) with 400 Extra inputs are not permitted, so the Go
        // preset must not echo reasoning while Zen keeps requiring it.
        assertEquals(
            ReasoningRequestMode.REASONING_CONTENT,
            Service.OpenCode.reasoningRequestMode,
        )
        assertEquals(
            ReasoningRequestMode.NONE,
            Service.OpenCodeGo.reasoningRequestMode,
        )
    }
}
