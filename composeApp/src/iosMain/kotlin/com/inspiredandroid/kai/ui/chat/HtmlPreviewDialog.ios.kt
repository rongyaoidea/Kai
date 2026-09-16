package com.inspiredandroid.kai.ui.chat

import androidx.compose.runtime.Composable

/** No in-app web surface on iOS; the tool opens HTML externally instead. */
@Composable
actual fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
) {
}
