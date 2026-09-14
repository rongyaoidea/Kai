package com.inspiredandroid.kai.ui.mcp

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inspiredandroid.kai.ui.settings.McpAppDialogState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * Android host for MCP App interactive UIs: a fullscreen dialog with a
 * locked-down system WebView.
 *
 * Security posture: JavaScript is required by design, but file/content
 * access are off, navigation outside the bundled document is cancelled, and
 * the page can only talk to the host through [AppBridge]. The bridge speaks
 * a minimal Kai AppBridge subset — pages call
 * `window.KaiAppBridge.postMessage(string)` with `{id, method, params}` for
 * `ui/initialize` and `tools/call`, and receive replies through
 * `window.__kaiOnMessage(string)`. This is intentionally not the full
 * postMessage-iframe dialect (no `ui/message` push, no capability-gated
 * permissions yet); data-driven apps that call tools and render results
 * work, richer apps degrade with an explicit "unsupported method" reply.
 */
@Composable
actual fun McpAppDialog(
    state: McpAppDialogState,
    onDismiss: () -> Unit,
    onToolCall: suspend (serverId: String, toolName: String, argsJson: String) -> String,
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
                        text = state.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        // TODO(codegen): restore Res.string.settings_mcp_app_close (see McpSection.kt).
                        Text("Close")
                    }
                }
                val html = state.html
                when {
                    html != null -> AppWebView(
                        html = html,
                        serverId = state.serverId,
                        toolName = state.toolName,
                        onToolCall = onToolCall,
                        modifier = Modifier.weight(1f),
                    )

                    state.error != null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // TODO(codegen): restore Res.string.settings_mcp_app_load_failed (see McpSection.kt).
                        Text(
                            text = "Could not load the interactive UI",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private class AppBridge(
    private val scope: CoroutineScope,
    private val view: () -> WebView?,
    private val serverId: String,
    private val toolName: String,
    private val onToolCall: suspend (serverId: String, toolName: String, argsJson: String) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @JavascriptInterface
    fun postMessage(message: String) {
        scope.launch(Dispatchers.IO) {
            val reply = try {
                handleMessage(message)
            } catch (e: Exception) {
                "{\"error\":{\"message\":\"host failure: ${e.message}\"}}"
            }
            withContext(Dispatchers.Main) {
                view()?.evaluateJavascript("window.__kaiOnMessage(${JSONObject.quote(reply)})", null)
            }
        }
    }

    private suspend fun handleMessage(message: String): String {
        val envelope = try {
            json.parseToJsonElement(message).jsonObject
        } catch (_: Exception) {
            return ""
        }
        val id = try {
            envelope["id"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        } ?: return ""
        val method = try {
            envelope["method"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        } ?: return errorReply(id, "missing method")
        val params = try {
            envelope["params"]?.jsonObject
        } catch (_: Exception) {
            null
        }
        return when (method) {
            "ui/initialize" -> buildJsonObject {
                put("id", id)
                put(
                    "result",
                    buildJsonObject {
                        put("serverId", serverId)
                        put("toolName", toolName)
                        put("capabilities", buildJsonObject { })
                    },
                )
            }.toString()

            "tools/call" -> {
                val name = try {
                    params?.get("name")?.jsonPrimitive?.content ?: toolName
                } catch (_: Exception) {
                    toolName
                }
                val args = try {
                    params?.get("arguments")?.toString() ?: params?.get("params")?.toString() ?: "{}"
                } catch (_: Exception) {
                    "{}"
                }
                try {
                    val text = onToolCall(serverId, name, args)
                    buildJsonObject {
                        put("id", id)
                        put("result", buildJsonObject { put("text", text) })
                    }.toString()
                } catch (e: Exception) {
                    errorReply(id, e.message ?: "tool call failed")
                }
            }

            else -> errorReply(id, "unsupported method: $method")
        }
    }

    private fun errorReply(id: String, message: String): String = buildJsonObject {
        put("id", id)
        put("error", buildJsonObject { put("message", message) })
    }.toString()
}

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
@Composable
private fun AppWebView(
    html: String,
    serverId: String,
    toolName: String,
    onToolCall: suspend (serverId: String, toolName: String, argsJson: String) -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var webView: WebView? = remember { null }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Keep the app inside its bundled document.
                        return true
                    }
                }
                addJavascriptInterface(
                    AppBridge(
                        scope = scope,
                        view = { webView },
                        serverId = serverId,
                        toolName = toolName,
                        onToolCall = onToolCall,
                    ),
                    "KaiAppBridge",
                )
                loadDataWithBaseURL("https://mcp-app.local/", html, "text/html", "utf-8", null)
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
