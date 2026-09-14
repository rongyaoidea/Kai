package com.inspiredandroid.kai.ui.mcp

import androidx.compose.runtime.Composable
import com.inspiredandroid.kai.ui.settings.McpAppDialogState

@Composable
actual fun McpAppDialog(
    state: McpAppDialogState,
    onDismiss: () -> Unit,
    onToolCall: suspend (serverId: String, toolName: String, argsJson: String) -> String,
) {
    // MCP App interactive UIs render in the Android WebView host only.
}
