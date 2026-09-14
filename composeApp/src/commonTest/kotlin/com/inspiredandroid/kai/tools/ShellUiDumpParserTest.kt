package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellUiDumpParserTest {

    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.chrome" content-desc="" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" bounds="[0,0][1080,2400]">
            <node index="0" text="" resource-id="com.chrome:id/url_bar" class="android.widget.EditText" package="com.chrome" content-desc="Search or type URL" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" bounds="[100,150][980,250]" />
            <node index="1" text="Search" resource-id="com.chrome:id/go" class="android.widget.Button" package="com.chrome" content-desc="" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" bounds="[990,150][1070,250]" />
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parsesNestedNodesWithDepth() {
        val nodes = ShellUiDumpParser.parseNodes(sample, maxNodes = 200, query = null)
        assertEquals(3, nodes.size)
        assertEquals(0, nodes[0].depth)
        assertEquals(1, nodes[1].depth)
        assertTrue(nodes[1].editable)
        assertEquals("com.chrome:id/url_bar", nodes[1].resId)
        assertEquals("Search or type URL", nodes[1].desc)
        assertEquals(540, nodes[1].centerX)
        assertEquals(200, nodes[1].centerY)
        assertEquals("com.chrome", nodes[2].pkg)
    }

    @Test
    fun queryFiltersByTextDescAndResId() {
        assertEquals(2, ShellUiDumpParser.parseNodes(sample, 200, "search").size)
        assertEquals(1, ShellUiDumpParser.parseNodes(sample, 200, "url_bar").size)
        assertEquals(0, ShellUiDumpParser.parseNodes(sample, 200, "no-such-thing").size)
    }

    @Test
    fun respectsMaxNodes() {
        assertEquals(2, ShellUiDumpParser.parseNodes(sample, 2, null).size)
    }

    @Test
    fun skipsNodesWithoutBounds() {
        val xml = "<hierarchy><node text=\"Ghost\" class=\"android.view.View\" package=\"x\" /></hierarchy>"
        assertTrue(ShellUiDumpParser.parseNodes(xml, 200, null).isEmpty())
    }

    @Test
    fun parsesForegroundPackage() {
        val dumpsys = "topResumedActivity=ActivityRecord{9d43613 u0 com.chrome/.Main} other stuff"
        assertEquals("com.chrome", ShellUiDumpParser.foregroundPackage(dumpsys))
        assertNull(ShellUiDumpParser.foregroundPackage("no activity info here"))
    }

    @Test
    fun parsesDisplaySize() {
        assertEquals(1080 to 2400, ShellUiDumpParser.displaySize("Physical size: 1080x2400\n"))
        assertNull(ShellUiDumpParser.displaySize("no size here"))
    }

    @Test
    fun escapesInputText() {
        assertEquals("hello%sworld", ShellUiDumpParser.escapeInputText("hello world"))
        assertEquals("ab", ShellUiDumpParser.escapeInputText("a'b\""))
        assertTrue(ShellUiDumpParser.hasNonAscii("搜索"))
    }
}
