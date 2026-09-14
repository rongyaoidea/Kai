package com.inspiredandroid.kai.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inspiredandroid.kai.tools.ToolApprovalGate
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_approval_approve
import kai.composeapp.generated.resources.tool_approval_deny
import kai.composeapp.generated.resources.tool_approval_detail
import kai.composeapp.generated.resources.tool_approval_reason
import kai.composeapp.generated.resources.tool_approval_title
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the user to approve a single risky tool call (shell command, outgoing mail,
 * installing an MCP server or skill). Hoisted into the app root so the question is visible
 * whichever screen started the run.
 *
 * Dismissing the dialog denies the request: the tool fails closed and reports the denial
 * back to the model, which is why there is no "ask me later" option.
 */
@Composable
fun ToolApprovalDialog(gate: ToolApprovalGate) {
    val pending by gate.pending.collectAsStateWithLifecycle()
    val request = pending?.request ?: return

    AlertDialog(
        onDismissRequest = { gate.dismissPending() },
        title = { Text(stringResource(Res.string.tool_approval_title, request.toolName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(Res.string.tool_approval_reason, request.reason))
                if (request.detail.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(Res.string.tool_approval_detail), style = MaterialTheme.typography.labelLarge)
                    Text(request.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { gate.approve(request.id) }) {
                Text(stringResource(Res.string.tool_approval_approve))
            }
        },
        dismissButton = {
            TextButton(onClick = { gate.deny(request.id) }) {
                Text(stringResource(Res.string.tool_approval_deny))
            }
        },
    )
}
