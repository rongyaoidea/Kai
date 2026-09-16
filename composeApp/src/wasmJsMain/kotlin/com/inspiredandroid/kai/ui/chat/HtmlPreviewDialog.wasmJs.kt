package com.inspiredandroid.kai.ui.chat

import androidx.compose.runtime.Composable

/** The web build opens HTML in the browser instead of an in-app preview. */
@Composable
actual fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
) {
}
