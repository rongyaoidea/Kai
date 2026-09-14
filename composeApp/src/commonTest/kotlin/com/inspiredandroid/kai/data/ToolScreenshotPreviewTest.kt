package com.inspiredandroid.kai.data

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Contract tests for the transient screenshot preview the chat renders inline.
 * Publishing garbage must not blank an existing preview or crash; publishing a
 * real image must replace it with the new conversation scope.
 */
@OptIn(ExperimentalEncodingApi::class)
class ToolScreenshotPreviewTest {

    // Smallest valid PNG (1x1).
    private val onePixelPng = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @AfterTest
    fun tearDown() {
        ToolScreenshotPreview.clear()
    }

    @Test
    fun `valid image publishes with conversation scope`() {
        ToolScreenshotPreview.publish(conversationId = "conv-1", bytes = onePixelPng)

        val preview = assertNotNull(ToolScreenshotPreview.latest.value)
        assertEquals("conv-1", preview.conversationId)
        assertEquals(1, preview.image.width)
        assertEquals(1, preview.image.height)
    }

    @Test
    fun `undecodable bytes do not replace the current preview`() {
        ToolScreenshotPreview.publish(conversationId = "conv-1", bytes = onePixelPng)

        ToolScreenshotPreview.publish(conversationId = "conv-2", bytes = byteArrayOf(1, 2, 3))

        assertEquals("conv-1", ToolScreenshotPreview.latest.value?.conversationId)
    }

    @Test
    fun `clear drops the preview`() {
        ToolScreenshotPreview.publish(conversationId = null, bytes = onePixelPng)
        assertNotNull(ToolScreenshotPreview.latest.value)

        ToolScreenshotPreview.clear()

        assertNull(ToolScreenshotPreview.latest.value)
    }
}
