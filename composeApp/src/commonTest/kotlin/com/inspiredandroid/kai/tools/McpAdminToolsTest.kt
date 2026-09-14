package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract tests for the small parsing helpers behind `add_mcp_server`:
 * URL de-duplication must ignore trailing slashes, and headers must survive
 * being serialized as a JSON string instead of an object.
 */
class McpAdminToolsTest {

    @Test
    fun `normalizeServerUrl ignores trailing slashes and case`() {
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp/"),
        )
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("HTTPS://MCP.EXAMPLE.COM/MCP///"),
        )
    }

    @Test
    fun `parseHeadersArg accepts a map`() {
        val parsed = McpAdminTools.parseHeadersArg(mapOf("Authorization" to "Bearer abc"))
        assertEquals(mapOf("Authorization" to "Bearer abc"), parsed)
    }

    @Test
    fun `parseHeadersArg accepts a JSON string`() {
        val parsed = McpAdminTools.parseHeadersArg("""{"Authorization": "Bearer abc"}""")
        assertEquals(mapOf("Authorization" to "Bearer abc"), parsed)
    }

    @Test
    fun `parseHeadersArg drops to empty on null or garbage`() {
        assertEquals(emptyMap(), McpAdminTools.parseHeadersArg(null))
        assertEquals(emptyMap(), McpAdminTools.parseHeadersArg(""))
        assertEquals(emptyMap(), McpAdminTools.parseHeadersArg("not json"))
        assertEquals(emptyMap(), McpAdminTools.parseHeadersArg(42))
    }
}
