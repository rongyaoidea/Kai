package com.inspiredandroid.kai.ui.chat

import com.inspiredandroid.kai.tools.UntrustedToolOutput
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolCallsUiModelTest {

    private fun call(id: String, name: String) = ToolCallInfo(id = id, name = name, arguments = "{}")

    private fun okResult(payload: String = """{"success":true}""") = UntrustedToolOutput.wrap(payload)

    private fun failResult() = UntrustedToolOutput.wrap("""{"success":false,"error":"boom"}""")

    @Test
    fun `trivial calls merge into counts`() {
        val model = buildToolCallsUiModel(
            calls = persistentListOf(
                call("1", "todo"),
                call("2", "get_local_time"),
                call("3", "get_local_time"),
                call("4", "get_location_from_ip"),
            ),
            results = mapOf("1" to okResult(), "2" to okResult(), "3" to okResult(), "4" to okResult()),
        )

        assertEquals(1, model.items.size)
        assertEquals(mapOf("get_local_time" to 2, "get_location_from_ip" to 1), model.trivialCounts)
        assertEquals(4, model.total)
        assertEquals(1, model.succeeded)
        assertEquals(0, model.failed)
    }

    @Test
    fun `failures are detected and pending calls stay uncounted`() {
        val model = buildToolCallsUiModel(
            calls = persistentListOf(
                call("1", "fetch_url"),
                call("2", "todo"),
                call("3", "open_url"),
            ),
            results = mapOf("1" to failResult(), "2" to okResult()),
        )

        assertTrue(model.items[0].failed)
        assertFalse(model.items[1].failed)
        assertEquals(1, model.succeeded)
        assertEquals(1, model.failed)
        // Call 3 has no result yet: rendered pending, counted in neither bucket.
        assertEquals(3, model.total)
    }

    @Test
    fun `free-form and unparseable results count as success`() {
        assertFalse(isFailureResult(UntrustedToolOutput.wrap("just some text")))
        assertFalse(isFailureResult("not json at all"))
        assertFalse(isFailureResult(okResult()))
        assertFalse(isFailureResult(UntrustedToolOutput.wrap("""{"ok":true}""")))
        assertTrue(isFailureResult(failResult()))
    }

    @Test
    fun `unwrap strips markers`() {
        assertEquals("body", unwrapResult(UntrustedToolOutput.wrap("body")))
        assertEquals("body", unwrapResult("body"))
    }

    @Test
    fun `preview collapses whitespace and truncates`() {
        assertEquals("a b c", trimPreview("a\n\n  b\tc"))
        assertEquals("short", trimPreview("short"))
        val long = trimPreview("x".repeat(400))
        assertEquals(301, long.length)
        assertTrue(long.endsWith("…"))
    }
}
