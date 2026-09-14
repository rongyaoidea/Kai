package com.inspiredandroid.kai.data

import androidx.compose.ui.graphics.ImageBitmap
import com.inspiredandroid.kai.decodeToImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Transient preview of the most recent screenshot produced by a tool
 * (`browse_page` screenshot mode or `ui_screenshot`). The chat renders it
 * inline so the user sees the capture without leaving Kai.
 *
 * Tool results stay text-only, so nothing here reaches the model context; the
 * preview is replaced by the next capture and cleared when the user sends a
 * new message. In-memory by design — after a restart the file path in the tool
 * result is still usable with `open_file` if the user explicitly asks.
 */
object ToolScreenshotPreview {
    data class Preview(
        val id: String,
        val conversationId: String?,
        val image: ImageBitmap,
    )

    private val _latest = MutableStateFlow<Preview?>(null)
    val latest: StateFlow<Preview?> = _latest

    /** Decodes [bytes] on the caller's thread and publishes a new preview. */
    @OptIn(ExperimentalUuidApi::class)
    fun publish(conversationId: String?, bytes: ByteArray) {
        val image = decodeToImageBitmap(bytes) ?: return
        _latest.value = Preview(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            image = image,
        )
    }

    fun clear() {
        _latest.value = null
    }
}
