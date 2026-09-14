package com.inspiredandroid.kai.ui.mcp

import androidx.compose.runtime.Composable
import com.inspiredandroid.kai.ui.settings.McpAppDialogState

/**
 * Renders a fetched MCP App interactive UI ([McpAppDialogState.html]).
 *
 * Platform hosts implement this with their native web surface (Android:
 * system WebView). The host speaks a minimal Kai AppBridge subset over
 * JavaScript: pages call `window.KaiAppBridge.postMessage(string)` with
 * `{id, method, params}` for `ui/initialize` and `tools/call`, and receive
 * replies through `window.__kaiOnMessage(string)`. This is a deliberate
 * subset of the full postMessage-iframe dialect — enough for data-driven
 * apps that call tools and render results.
 *
 * @param onToolCall proxies an app-initiated tool call to its MCP server.
 */
@Composable
expect fun McpAppDialog(
    state: McpAppDialogState,
    onDismiss: () -> Unit,
    onToolCall: suspend (serverId: String, toolName: String, argsJson: String) -> String,
)
