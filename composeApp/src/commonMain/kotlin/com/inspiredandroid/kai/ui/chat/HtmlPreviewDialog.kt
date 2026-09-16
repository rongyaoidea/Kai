package com.inspiredandroid.kai.ui.chat

import androidx.compose.runtime.Composable

/**
 * Full-screen in-app HTML preview used by the chat's report card.
 *
 * Platform hosts implement this with their native web surface (Android: system
 * WebView, locked down — JS on for self-contained reports, file/content access
 * off, navigation outside the document cancelled). Platforms without a web
 * surface report `isHtmlPreviewSupported = false` and the tool falls back to
 * opening the file externally, so this stub is unreachable there.
 */
@Composable
expect fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
)
