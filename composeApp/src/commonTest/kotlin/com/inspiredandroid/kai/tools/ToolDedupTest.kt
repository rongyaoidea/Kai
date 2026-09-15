package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDedupTest {

    private fun named(name: String): Tool = object : Tool {
        override val schema = ToolSchema(name = name, description = "test", parameters = emptyMap())
        override suspend fun execute(args: Map<String, Any>): Any = "ok"
    }

    @Test
    fun `first occurrence wins on duplicate names`() {
        val tools = listOf(named("todo"), named("web_search"), named("todo"))

        val deduped = tools.distinctToolNames()

        assertEquals(listOf("todo", "web_search"), deduped.map { it.schema.name })
    }

    @Test
    fun `unique lists pass through untouched`() {
        val tools = listOf(named("a"), named("b"))

        assertEquals(tools, tools.distinctToolNames())
    }

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList(), emptyList<Tool>().distinctToolNames())
    }
}
