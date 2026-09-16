package com.inspiredandroid.kai.ui.chat

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Android host for the in-app HTML preview: a fullscreen dialog with a
 * locked-down system WebView. The document is injected as a string (never a
 * `file://` load), so the WebView needs no file access at all — JS is enabled
 * because reports are self-contained documents, and every navigation attempt
 * is cancelled so links can't escape the report.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun HtmlPreviewDialog(
    title: String,
    html: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
                PreviewWebView(
                    html = html,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PreviewWebView(
    html: String,
    modifier: Modifier = Modifier,
) {
    var webView: WebView? = remember { null }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Self-contained documents only; links stay inert.
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                webView = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }
}
