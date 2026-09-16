package com.inspiredandroid.kai.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlPreviewTest {

    @Test
    fun `publish exposes the document with a title from the path`() {
        HtmlPreview.clear()

        val published = HtmlPreview.publish("chat-1", "reports/quarterly.html", "<h1>Q3</h1>")

        assertTrue(published)
        val preview = HtmlPreview.latest.value ?: error("preview missing")
        assertEquals("quarterly.html", preview.title)
        assertEquals("<h1>Q3</h1>", preview.html)
        assertEquals("chat-1", preview.conversationId)
    }

    @Test
    fun `oversized and blank documents are not published`() {
        HtmlPreview.clear()

        assertFalse(HtmlPreview.publish(null, "big.html", "x".repeat(HtmlPreview.MAX_BYTES + 1)))
        assertFalse(HtmlPreview.publish(null, "empty.html", "  "))
        assertNull(HtmlPreview.latest.value)
    }

    @Test
    fun `clear drops the current preview`() {
        HtmlPreview.publish(null, "a.html", "<p>hi</p>")
        HtmlPreview.clear()

        assertNull(HtmlPreview.latest.value)
    }
}
