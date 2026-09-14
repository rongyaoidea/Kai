package com.inspiredandroid.kai.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpProtocolTest {

    @Test
    fun `headers carry protocol version method and task name`() {
        val plain = McpProtocol.headers("tools/call")
        assertEquals("2026-07-28", plain["MCP-Protocol-Version"])
        assertEquals("tools/call", plain["Mcp-Method"])
        assertNull(plain["Mcp-Name"])
        assertEquals("t-1", McpProtocol.headers("tasks/get", "t-1")["Mcp-Name"])
    }

    @Test
    fun `supported versions prefer newest and include legacy versions`() {
        assertEquals("2026-07-28", McpProtocol.SUPPORTED_VERSIONS.first())
        assertEquals(McpProtocol.PROTOCOL_VERSION, McpProtocol.SUPPORTED_VERSIONS.first())
        assertTrue("2025-11-25" in McpProtocol.SUPPORTED_VERSIONS)
        assertTrue("2024-10-07" in McpProtocol.SUPPORTED_VERSIONS)
    }

    @Test
    fun `headers default to newest version but honor override`() {
        assertEquals("2026-07-28", McpProtocol.headers("tools/list")["MCP-Protocol-Version"])
        assertEquals(
            "2025-11-25",
            McpProtocol.headers("tools/list", protocolVersion = "2025-11-25")["MCP-Protocol-Version"],
        )
    }

    @Test
    fun `version mismatch detection covers json-rpc and http bodies`() {
        assertTrue(
            McpProtocol.isVersionMismatch(
                "Bad Request: Unsupported protocol version: 2026-07-28 (supported versions: 2025-11-25)",
            ),
        )
        assertTrue(McpProtocol.isVersionMismatch("Initialize failed: Unsupported protocol version"))
        assertFalse(McpProtocol.isVersionMismatch("Connection refused"))
        assertFalse(McpProtocol.isVersionMismatch(null))
    }

    @Test
    fun `tasks capability merges with existing meta`() {
        val params = buildJsonObject {
            put("name", "do_thing")
            put(
                "_meta",
                buildJsonObject { put("traceId", "abc") },
            )
        }
        val merged = McpProtocol.withTasksCapability(params)
        assertEquals("do_thing", merged["name"]!!.jsonPrimitive.content)
        val meta = merged["_meta"]!!.jsonObject
        assertEquals("abc", meta["traceId"]!!.jsonPrimitive.content)
        val extensions = meta["io.modelcontextprotocol/clientCapabilities"]!!
            .jsonObject["extensions"]!!.jsonObject
        assertTrue("io.modelcontextprotocol/tasks" in extensions)
    }

    @Test
    fun `terminal statuses stop polling`() {
        assertTrue(McpProtocol.isTerminalStatus("completed"))
        assertTrue(McpProtocol.isTerminalStatus("failed"))
        assertTrue(McpProtocol.isTerminalStatus("cancelled"))
        assertFalse(McpProtocol.isTerminalStatus("working"))
        assertFalse(McpProtocol.isTerminalStatus("input_required"))
    }

    @Test
    fun `poll delay honors server hint within bounds`() {
        assertEquals(2_000L, McpProtocol.pollDelayMs(null))
        assertEquals(500L, McpProtocol.pollDelayMs(50L))
        assertEquals(10_000L, McpProtocol.pollDelayMs(60_000L))
        assertEquals(3_000L, McpProtocol.pollDelayMs(3_000L))
    }

    @Test
    fun `input requests summarize to readable lines`() {
        assertNull(McpProtocol.summarizeInputRequests(null))
        val summary = McpProtocol.summarizeInputRequests(
            buildJsonObject {
                put(
                    "approval",
                    buildJsonObject { put("message", "Delete 4 rows?") },
                )
            },
        )
        assertTrue(summary!!.contains("approval"))
        assertTrue(summary.contains("Delete 4 rows?"))
    }
}
