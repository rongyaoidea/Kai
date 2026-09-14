package com.inspiredandroid.kai.network.dtos.anthropic

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicPromptCachingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun tool(name: String) = AnthropicChatRequestDto.Tool(
        name = name,
        description = "desc $name",
        input_schema = AnthropicChatRequestDto.InputSchema(
            properties = mapOf(
                "q" to AnthropicChatRequestDto.PropertySchema(type = "string"),
            ),
        ),
    )

    private fun withBreakpoint(tools: List<AnthropicChatRequestDto.Tool>?): List<AnthropicChatRequestDto.Tool>? {
        // Mirror Requests.withCacheBreakpoint() without reaching into privates.
        if (tools.isNullOrEmpty()) return null
        return tools.dropLast(1) + tools.last().copy(cache_control = AnthropicChatRequestDto.CacheControl())
    }

    private fun encode(
        system: String? = "soul + memories",
        tools: List<AnthropicChatRequestDto.Tool>? = listOf(tool("a"), tool("b")),
    ): String = json.encodeToString(
        AnthropicChatRequestDto(
            model = "claude-fable-5-1",
            messages = emptyList(),
            system = system?.let { AnthropicChatRequestDto.cachedSystemPrompt(it) },
            tools = withBreakpoint(tools),
        ),
    )

    @Test
    fun `system prompt carries ephemeral cache breakpoint`() {
        val root = json.parseToJsonElement(encode()).jsonObject
        val system = root["system"]!!.jsonArray
        assertEquals(1, system.size)
        val block = system[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("soul + memories", block["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only last tool carries cache breakpoint`() {
        val root = json.parseToJsonElement(encode()).jsonObject
        val tools = root["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertNull(tools[0].jsonObject["cache_control"])
        assertEquals("ephemeral", tools[1].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `absent system and tools are omitted from payload`() {
        val encoded = json.encodeToString(
            AnthropicChatRequestDto(model = "m", messages = emptyList()),
        )
        val root = json.parseToJsonElement(encoded).jsonObject
        assertTrue("system" !in root)
        assertTrue("tools" !in root)
    }
}
