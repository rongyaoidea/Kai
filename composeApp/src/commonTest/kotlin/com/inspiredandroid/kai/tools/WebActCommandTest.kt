package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebActCommandTest {

    private val snapshotFixture = """
        [{"i":4,"tag":"a","text":"Download Linux","type":"","href":"/download"},
         {"i":9,"tag":"input","text":"Search","type":"search","href":""},
         {"i":"x","tag":"b","text":"skip"}]
    """.trimIndent()

    @Test
    fun `parses snapshot rows and drops malformed ones`() {
        val elements = WebActCommand.parseSnapshot(snapshotFixture)
        assertEquals(2, elements.size)
        assertEquals(WebActCommand.WebElement(4, "a", "Download Linux", "", "/download"), elements[0])
        assertEquals(WebActCommand.WebElement(9, "input", "Search", "search", ""), elements[1])
    }

    @Test
    fun `snapshot parse tolerates garbage`() {
        assertTrue(WebActCommand.parseSnapshot("not json").isEmpty())
        assertTrue(WebActCommand.parseSnapshot("{}").isEmpty())
        assertTrue(WebActCommand.parseSnapshot("[1,2]").isEmpty())
    }

    @Test
    fun `parses quoted and bare action results`() {
        assertEquals(WebActCommand.WebActionResult(true, "Go"), WebActCommand.parseActionResult("""{"ok":true,"text":"Go"}"""))
        assertEquals(WebActCommand.WebActionResult(false, ""), WebActCommand.parseActionResult("""{"ok":false,"text":""}"""))
        assertNull(WebActCommand.parseActionResult("nope"))
        assertNull(WebActCommand.parseActionResult("""{"text":"x"}"""))
    }

    @Test
    fun `input builder embeds text safely`() {
        val js = WebActCommand.inputJs(2, "it's \"quoted\"\nnewline", submit = true)
        assertTrue(js.contains("els[2]"))
        assertTrue(js.contains("keyCode: 13"))
        assertTrue(js.contains("it\\'s") || js.contains("it\\u0027s") || js.contains("it's"))
    }

    @Test
    fun `builders carry the shared selector and caps`() {
        assertTrue(WebActCommand.snapshotJs().contains("role=\"button\""))
        // input must query the same collection as snapshot, or the ids shift.
        assertTrue(WebActCommand.inputJs(0, "x", submit = false).contains("role=\"button\""))
        assertTrue(WebActCommand.inputJs(0, "x", submit = false).contains("not_fillable"))
        assertTrue(WebActCommand.tapJs(0).contains("role=\"button\""))
        assertTrue(WebActCommand.tapJs(0).contains("scrollIntoView"))
        assertTrue(WebActCommand.scrollJs(-600).contains("-600"))
        assertTrue(WebActCommand.backJs().contains("history.back"))
        assertFalse(WebActCommand.inputJs(0, "x", submit = false).contains("keyCode"))
    }

    @Test
    fun `scroll builders target the real container and report position`() {
        assertTrue(WebActCommand.scrollJs(600).contains("pickScroller"))
        assertTrue(WebActCommand.scrollJs(600).contains("atBottom"))
        assertTrue(WebActCommand.scrollInfoJs().contains("pickScroller"))
        assertTrue(WebActCommand.scrollInfoJs().contains("atBottom"))
    }

    @Test
    fun `parses scroll results`() {
        assertEquals(
            WebActCommand.WebScrollResult(true, 600, 2000, false, true),
            WebActCommand.parseScrollResult("""{"ok":true,"y":600,"maxY":2000,"atBottom":false,"moved":true}"""),
        )
        assertEquals(
            true,
            WebActCommand.parseScrollResult("""{"ok":true,"y":2000,"maxY":2000,"atBottom":true,"moved":true}""")?.atBottom,
        )
        assertNull(WebActCommand.parseScrollResult("garbage"))
        assertNull(WebActCommand.parseScrollResult("""{"y":1}"""))
        assertEquals(
            WebActCommand.WebScrollResult(false, 0, 0, false, false),
            WebActCommand.parseScrollResult("""{"ok":false,"y":0,"maxY":0,"atBottom":false,"moved":false}"""),
        )
    }
}
