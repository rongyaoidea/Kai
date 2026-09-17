package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for the small parsing helpers behind `add_mcp_server`:
 * URL de-duplication ignores trailing slashes, host case, default ports and
 * fragments (but keeps path case and query); headers survive being serialized
 * as a JSON string instead of an object, and non-string values stringify
 * instead of vanishing.
 */
class McpAdminToolsTest {

    @Test
    fun `normalizeServerUrl ignores trailing slashes and host case`() {
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp/"),
        )
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("HTTPS://MCP.EXAMPLE.COM/mcp///"),
        )
    }

    @Test
    fun `normalizeServerUrl keeps path case query and unifies default ports`() {
        // Paths are case-sensitive: different case may be a different endpoint.
        assertTrue(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/MCP") !=
                McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
        )
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp?key=1"),
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp/?key=1"),
        )
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("https://mcp.example.com:443/mcp"),
        )
        assertEquals(
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp"),
            McpAdminTools.normalizeServerUrl("https://mcp.example.com/mcp#frag"),
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

    @Test
    fun `parseHeadersArg stringifies non-string values`() {
        assertEquals(
            mapOf("Authorization" to "{\"token\":\"abc\"}"),
            McpAdminTools.parseHeadersArg("""{"Authorization": {"token": "abc"}}"""),
        )
    }
}
