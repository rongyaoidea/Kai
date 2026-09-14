package com.inspiredandroid.kai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.settings_automation_a11y_title
import kai.composeapp.generated.resources.settings_automation_allowlist_hint
import kai.composeapp.generated.resources.settings_automation_allowlist_label
import kai.composeapp.generated.resources.settings_automation_allowlist_save
import kai.composeapp.generated.resources.settings_automation_backend
import kai.composeapp.generated.resources.settings_automation_backend_accessibility
import kai.composeapp.generated.resources.settings_automation_backend_none
import kai.composeapp.generated.resources.settings_automation_backend_shizuku
import kai.composeapp.generated.resources.settings_automation_description
import kai.composeapp.generated.resources.settings_automation_label
import kai.composeapp.generated.resources.settings_automation_not_bound
import kai.composeapp.generated.resources.settings_automation_open_settings
import kai.composeapp.generated.resources.settings_automation_ready
import kai.composeapp.generated.resources.settings_automation_refresh
import kai.composeapp.generated.resources.settings_automation_shizuku_authorize
import kai.composeapp.generated.resources.settings_automation_shizuku_authorizing
import kai.composeapp.generated.resources.settings_automation_shizuku_description
import kai.composeapp.generated.resources.settings_automation_shizuku_install
import kai.composeapp.generated.resources.settings_automation_shizuku_label
import kai.composeapp.generated.resources.settings_automation_shizuku_open_app
import kai.composeapp.generated.resources.settings_automation_shizuku_start_hint
import kai.composeapp.generated.resources.settings_automation_shizuku_state_need_permission
import kai.composeapp.generated.resources.settings_automation_shizuku_state_not_installed
import kai.composeapp.generated.resources.settings_automation_shizuku_state_not_running
import kai.composeapp.generated.resources.settings_automation_shizuku_state_ready
import kai.composeapp.generated.resources.settings_automation_write_description
import kai.composeapp.generated.resources.settings_automation_write_label
import org.jetbrains.compose.resources.stringResource

/**
 * Cross-app automation section (Android-only): master switch, per-channel
 * authorization status cards (accessibility + Shizuku, OpenMinis-style: status
 * dot plus the action each state needs), write-actions switch, allowlist
 * editor, and Shizuku switch.
 */
@Composable
internal fun AutomationSection(
    isAutomationEnabled: Boolean,
    serviceBound: Boolean,
    backend: String,
    isWriteEnabled: Boolean,
    isShizukuEnabled: Boolean,
    shizukuStatus: String,
    shizukuDetails: String,
    isRequestingShizuku: Boolean,
    allowedApps: String,
    onToggleAutomation: (Boolean) -> Unit,
    onToggleWrite: (Boolean) -> Unit,
    onToggleShizuku: (Boolean) -> Unit,
    onSaveAllowedApps: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onRefreshStatus: () -> Unit,
    onRequestAuthorization: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onOpenShizukuDownloadPage: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ToggleableHeadline(
            title = stringResource(Res.string.settings_automation_label),
            description = stringResource(Res.string.settings_automation_description),
            checked = isAutomationEnabled,
            onCheckedChange = onToggleAutomation,
        )

        if (isAutomationEnabled) {
            Spacer(Modifier.height(12.dp))

            AuthorizationStatusRow(
                dotColor = if (serviceBound) StatusColorConnected else StatusColorError,
                title = stringResource(Res.string.settings_automation_a11y_title),
                subtitle = if (serviceBound) {
                    stringResource(Res.string.settings_automation_ready)
                } else {
                    stringResource(Res.string.settings_automation_not_bound)
                },
                actionLabel = if (serviceBound) {
                    stringResource(Res.string.settings_automation_refresh)
                } else {
                    stringResource(Res.string.settings_automation_open_settings)
                },
                onAction = { if (serviceBound) onRefreshStatus() else onOpenSystemSettings() },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    Res.string.settings_automation_backend,
                    when (backend) {
                        "accessibility" -> stringResource(Res.string.settings_automation_backend_accessibility)
                        "shizuku" -> stringResource(Res.string.settings_automation_backend_shizuku)
                        else -> stringResource(Res.string.settings_automation_backend_none)
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            ToggleableHeadline(
                title = stringResource(Res.string.settings_automation_write_label),
                description = stringResource(Res.string.settings_automation_write_description),
                checked = isWriteEnabled,
                onCheckedChange = onToggleWrite,
            )

            Spacer(Modifier.height(16.dp))

            var editedApps by remember(allowedApps) { mutableStateOf(allowedApps) }
            Text(
                text = stringResource(Res.string.settings_automation_allowlist_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = editedApps,
                onValueChange = { editedApps = it },
                placeholder = { Text(stringResource(Res.string.settings_automation_allowlist_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSaveAllowedApps(editedApps) },
                enabled = editedApps != allowedApps,
            ) {
                Text(stringResource(Res.string.settings_automation_allowlist_save))
            }

            Spacer(Modifier.height(16.dp))

            ToggleableHeadline(
                title = stringResource(Res.string.settings_automation_shizuku_label),
                description = stringResource(Res.string.settings_automation_shizuku_description),
                checked = isShizukuEnabled,
                onCheckedChange = onToggleShizuku,
            )

            Spacer(Modifier.height(12.dp))

            ShizukuStatusCard(
                status = shizukuStatus,
                details = shizukuDetails,
                isRequesting = isRequestingShizuku,
                onAuthorize = onRequestAuthorization,
                onOpenApp = onOpenShizukuApp,
                onDownload = onOpenShizukuDownloadPage,
                onRefresh = onRefreshStatus,
            )
        }
    }
}

/**
 * One authorization channel: status dot, title, subtitle, and the single
 * action the current state calls for. Same visual language as the MCP
 * server cards.
 */
@Composable
private fun AuthorizationStatusRow(
    dotColor: Color,
    title: String,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
    actionEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onAction, enabled = actionEnabled) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ShizukuStatusCard(
    status: String,
    details: String,
    isRequesting: Boolean,
    onAuthorize: () -> Unit,
    onOpenApp: () -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (status) {
            "READY" -> AuthorizationStatusRow(
                dotColor = StatusColorConnected,
                title = stringResource(Res.string.settings_automation_shizuku_label),
                subtitle = if (details.isNotBlank()) {
                    "${stringResource(Res.string.settings_automation_shizuku_state_ready)} · $details"
                } else {
                    stringResource(Res.string.settings_automation_shizuku_state_ready)
                },
                actionLabel = stringResource(Res.string.settings_automation_refresh),
                onAction = onRefresh,
            )

            "NEED_PERMISSION" -> AuthorizationStatusRow(
                dotColor = StatusColorChecking,
                title = stringResource(Res.string.settings_automation_shizuku_label),
                subtitle = stringResource(Res.string.settings_automation_shizuku_state_need_permission),
                actionLabel = if (isRequesting) {
                    stringResource(Res.string.settings_automation_shizuku_authorizing)
                } else {
                    stringResource(Res.string.settings_automation_shizuku_authorize)
                },
                onAction = onAuthorize,
                actionEnabled = !isRequesting,
            )

            "NOT_RUNNING" -> {
                AuthorizationStatusRow(
                    dotColor = StatusColorChecking,
                    title = stringResource(Res.string.settings_automation_shizuku_label),
                    subtitle = stringResource(Res.string.settings_automation_shizuku_state_not_running),
                    actionLabel = stringResource(Res.string.settings_automation_shizuku_open_app),
                    onAction = onOpenApp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.settings_automation_shizuku_start_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            "NOT_INSTALLED" -> AuthorizationStatusRow(
                dotColor = StatusColorUnknown,
                title = stringResource(Res.string.settings_automation_shizuku_label),
                subtitle = stringResource(Res.string.settings_automation_shizuku_state_not_installed),
                actionLabel = stringResource(Res.string.settings_automation_shizuku_install),
                onAction = onDownload,
            )

            else -> AuthorizationStatusRow(
                dotColor = StatusColorUnknown,
                title = stringResource(Res.string.settings_automation_shizuku_label),
                subtitle = status,
                actionLabel = stringResource(Res.string.settings_automation_refresh),
                onAction = onRefresh,
            )
        }
    }
}
