package com.inspiredandroid.kai.ui.chat.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inspiredandroid.kai.getPlatformToolDefinitions
import com.inspiredandroid.kai.ui.chat.ToolCallUiItem
import com.inspiredandroid.kai.ui.chat.ToolCallsUiModel
import com.inspiredandroid.kai.ui.chat.trimPreview
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_calls_expand_content_description
import kai.composeapp.generated.resources.tool_calls_failed
import kai.composeapp.generated.resources.tool_calls_succeeded
import kai.composeapp.generated.resources.tool_calls_trivial
import kai.composeapp.generated.resources.tools_count
import org.jetbrains.compose.resources.stringResource

/**
 * One assistant turn's tool calls as a collapsed card. Only tool names show;
 * results open per row on tap. Failures render red but never auto-expand.
 * Trivial context reads ([TRIVIAL_TOOL_IDS]) merge into a single line.
 */
@Composable
internal fun ToolCallsCard(
    model: ToolCallsUiModel,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val definitions = remember { getPlatformToolDefinitions() }
    @Composable
    fun displayName(toolId: String): String {
        val info = definitions.find { it.id == toolId }
        return info?.nameRes?.let { stringResource(it) } ?: info?.name ?: toolId
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.tool_calls_expand_content_description),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(Res.string.tools_count, model.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " · " + stringResource(Res.string.tool_calls_succeeded, model.succeeded),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.failed > 0) {
                Text(
                    text = " · " + stringResource(Res.string.tool_calls_failed, model.failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.size(8.dp))
            for (item in model.items) {
                ToolCallRow(
                    name = displayName(item.toolId),
                    item = item,
                )
            }
            if (model.trivialCounts.isNotEmpty()) {
                val grouped = model.trivialCounts.entries.joinToString { (id, count) ->
                    val name = displayName(id)
                    if (count > 1) "$name ×$count" else name
                }
                Text(
                    text = stringResource(Res.string.tool_calls_trivial, model.trivialCounts.values.sum()) + ": $grouped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallRow(
    name: String,
    item: ToolCallUiItem,
) {
    var previewExpanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dotColor = when {
        item.failed -> scheme.error
        item.resultText != null -> scheme.tertiary
        else -> scheme.outline
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.resultText != null) { previewExpanded = !previewExpanded }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.failed) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (previewExpanded && item.resultText != null) {
            Text(
                text = trimPreview(item.resultText, 2000),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}
