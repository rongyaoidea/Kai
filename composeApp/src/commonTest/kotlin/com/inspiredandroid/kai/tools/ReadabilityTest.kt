package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JsStringDecodeTest {

    @Test
    fun `decodes escapes and unicode`() {
        assertEquals("a\nb\tc", decodeJsString("\"a\\nb\\tc\""))
        assertEquals("中文", decodeJsString("\"\\u4e2d\\u6587\""))
        assertEquals("it's", decodeJsString("\"it\\'s\""))
    }

    @Test
    fun `null blank and bare values decode empty`() {
        assertEquals("", decodeJsString(null))
        assertEquals("", decodeJsString("null"))
        assertEquals("", decodeJsString(""))
        assertEquals("", decodeJsString("42"))
    }
}

class ReadabilityTest {

    private val page = """
        <html><head><title>Page</title><style>.x{color:red}</style>
        <script>var ads = 1;</script></head>
        <body>
        <header><nav><a href="https://example.com/home">Home</a></nav></header>
        <main>
        <h1>Real Article</h1>
        <p>First paragraph with <a href="https://example.com/deep">a link</a>.</p>
        <p>Second paragraph &amp; more.</p>
        </main>
        <footer>Copyright 2026 <a href="https://example.com/privacy">Privacy</a></footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `drops chrome scripts and footer`() {
        val text = extractReadableText(page)
        assertTrue(text.contains("Real Article"))
        assertTrue(text.contains("First paragraph"))
        assertTrue(text.contains("Second paragraph & more."))
        assertFalse(text.contains("var ads"))
        assertFalse(text.contains("Copyright"))
        assertFalse(text.contains(".x{color"))
    }

    @Test
    fun `collects outbound links`() {
        val text = extractReadableText(page)
        assertTrue(text.contains("https://example.com/deep"))
        // Nav/footer links live in dropped chrome blocks.
        assertFalse(text.contains("https://example.com/privacy"))
    }

    @Test
    fun `numeric entities decode`() {
        assertTrue("A&#65;".decodeHtmlEntities() == "AA")
        assertTrue("&#x41;".decodeHtmlEntities() == "A")
    }

    @Test
    fun `plain text passes through`() {
        assertTrue(extractReadableText("just words").contains("just words"))
    }
}
