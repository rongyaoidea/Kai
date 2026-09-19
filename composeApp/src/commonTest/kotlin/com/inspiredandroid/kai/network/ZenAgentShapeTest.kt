package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesRequestDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZenAgentShapeTest {

    private val zenChatUrl = "https://opencode.ai/zen/v1/chat/completions"
    private val goChatUrl = "https://opencode.ai/zen/go/v1/chat/completions"

    @Test
    fun `free zen models are detected across presets and compatible instances`() {
        assertTrue(isZenFreeModel(Service.OpenCode, "nemotron-3.5-lightning-free", zenChatUrl))
        assertTrue(isZenFreeModel(Service.OpenCode, "big-pickle", zenChatUrl))
        assertTrue(isZenFreeModel(Service.OpenAICompatible, "mimo-v2.5-free", zenChatUrl))
        assertTrue(isZenFreeModel(Service.OpenCode, "mimo-v2.5-free", ""))
    }

    @Test
    fun `paid models, go, and unrelated providers are never shaped`() {
        assertFalse(isZenFreeModel(Service.OpenCode, "deepseek-v4-pro", zenChatUrl))
        assertFalse(isZenFreeModel(Service.OpenCodeGo, "deepseek-v4-flash-free", goChatUrl))
        assertFalse(isZenFreeModel(Service.OpenRouter, "nex-agi/nex-n2.5-pro:free", "https://openrouter.ai/api/v1/chat/completions"))
        assertFalse(isZenFreeModel(Service.OpenAICompatible, "nemotron-3.5-lightning-free", "http://localhost:11434/v1/chat/completions"))
    }

    @Test
    fun `missing core tools are appended in order`() {
        val shaped = zenShapedChatTools(emptyList())
        assertEquals(ZEN_CORE_TOOL_NAMES, shaped.map { it.function.name })
        assertTrue(shaped.all { it.function.parameters?.properties?.isEmpty() == true })
    }

    @Test
    fun `declared core tools are left alone`() {
        val declared = listOf(chatTool("read"), chatTool("bash"))
        val shaped = zenShapedChatTools(declared)
        assertEquals(listOf("read", "bash", "edit", "glob", "grep"), shaped.map { it.function.name })
    }

    @Test
    fun `aliases clone the real tool schema so the model can call them`() {
        val shaped = zenShapedChatTools(listOf(chatTool("execute_shell_command")))
        val bash = shaped.first { it.function.name == "bash" }
        val original = shaped.first { it.function.name == "execute_shell_command" }
        assertEquals(original.function.description, bash.function.description)
        assertEquals(original.function.parameters?.required, bash.function.parameters?.required)
        assertEquals(original.function.parameters?.properties?.keys, bash.function.parameters?.properties?.keys)
    }

    @Test
    fun `shaping is idempotent`() {
        val once = zenShapedChatTools(listOf(chatTool("read_file")))
        val twice = zenShapedChatTools(once)
        assertEquals(once, twice)
    }

    @Test
    fun `responses tools use the flattened shape and their own aliases`() {
        val declared = listOf(responsesTool("write_file"))
        val shaped = zenShapedResponsesTools(declared)
        assertEquals(listOf("write_file", "bash", "edit", "glob", "grep", "read"), shaped.map { it.name })
        val edit = shaped.first { it.name == "edit" }
        assertEquals("write_file", declared.first().name)
        assertEquals(declared.first().parameters?.required, edit.parameters?.required)
    }

    private fun chatTool(name: String) = OpenAICompatibleChatRequestDto.Tool(
        function = OpenAICompatibleChatRequestDto.Function(
            name = name,
            description = "$name tool",
            parameters = OpenAICompatibleChatRequestDto.Parameters(
                properties = mapOf("command" to OpenAICompatibleChatRequestDto.PropertySchema(type = "string")),
                required = listOf("command"),
            ),
        ),
    )

    private fun responsesTool(name: String) = OpenAIResponsesRequestDto.Tool(
        name = name,
        description = "$name tool",
        parameters = OpenAICompatibleChatRequestDto.Parameters(
            properties = mapOf("path" to OpenAICompatibleChatRequestDto.PropertySchema(type = "string")),
            required = listOf("path"),
        ),
    )
}
