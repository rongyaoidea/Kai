package com.inspiredandroid.kai.ui.chat

import androidx.compose.runtime.Composable

/** No web surface in the desktop JVM host; the tool opens HTML externally instead. */
@Composable
actual fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
) {
}
