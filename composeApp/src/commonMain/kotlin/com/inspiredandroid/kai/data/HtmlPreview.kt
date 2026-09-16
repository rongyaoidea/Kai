package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Transient in-app HTML preview: the most recent `open_file(preview=true)` on an
 * HTML file. The chat renders a card that opens the document in a full-screen
 * WebView (Android), so the agent's reports can be seen without leaving Kai or
 * handing the file to another app.
 *
 * In-memory by design — the file itself stays in the workspace, and the external
 * `open_file` path still works after a restart. Cleared when the user sends a new
 * message, like the tool screenshot preview.
 */
object HtmlPreview {
    /** Documents larger than this are not previewed (the file still exists and opens externally). */
    const val MAX_BYTES = 512 * 1024

    @Immutable
    data class Preview(
        val id: String,
        val conversationId: String?,
        val path: String,
        val title: String,
        val html: String,
    )

    private val _latest = MutableStateFlow<Preview?>(null)
    val latest: StateFlow<Preview?> = _latest

    /**
     * Publishes [html] for [path], or does nothing when the document exceeds
     * [MAX_BYTES] or is blank — callers fall back to opening it externally.
     * Returns whether a preview is now available.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun publish(conversationId: String?, path: String, html: String): Boolean {
        if (html.isBlank() || html.toByteArray().size > MAX_BYTES) return false
        val title = path.substringAfterLast('/').ifBlank { path }
        _latest.value = Preview(
            id = Uuid.random().toString(),
            conversationId = conversationId,
            path = path,
            title = title,
            html = html,
        )
        return true
    }

    fun clear() {
        _latest.value = null
    }
}
