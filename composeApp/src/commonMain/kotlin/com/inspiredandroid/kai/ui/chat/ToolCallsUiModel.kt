package com.inspiredandroid.kai.ui.chat

import com.inspiredandroid.kai.tools.UntrustedToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Trivial context reads that would drown the tool-call card if listed one by one.
 * They are merged into a single "+ N trivial" line instead. Deliberately small:
 * anything that acts on the world stays visible.
 */
internal val TRIVIAL_TOOL_IDS = setOf(
    "get_local_time",
    "get_location_from_ip",
)

/** One rendered tool-call row: the call plus its (possibly still missing) result. */
internal data class ToolCallUiItem(
    val callId: String,
    val toolId: String,
    val failed: Boolean,
    val resultText: String?,
)

/** Everything [ToolCallsCard] needs: notable rows, merged trivial counts, and totals. */
internal data class ToolCallsUiModel(
    val items: List<ToolCallUiItem>,
    /** Trivial tool id -> call count, in first-seen order. */
    val trivialCounts: Map<String, Int>,
    val succeeded: Int,
    val failed: Int,
) {
    val total: Int get() = items.size + trivialCounts.values.sum()
}

/**
 * Pairs an assistant turn's [ToolCallInfo]s with their TOOL results ([toolCallId] -> raw
 * stored content). A call with no result yet (still executing, or pruned history) renders
 * as pending rather than disappearing.
 */
internal fun buildToolCallsUiModel(
    calls: List<ToolCallInfo>,
    results: Map<String, String>,
): ToolCallsUiModel {
    val items = mutableListOf<ToolCallUiItem>()
    val trivial = linkedMapOf<String, Int>()
    var succeeded = 0
    var failed = 0
    for (call in calls) {
        if (call.name in TRIVIAL_TOOL_IDS) {
            trivial[call.name] = (trivial[call.name] ?: 0) + 1
            continue
        }
        val raw = results[call.id]
        val failedCall = raw != null && isFailureResult(raw)
        if (raw != null) {
            if (failedCall) failed++ else succeeded++
        }
        items.add(
            ToolCallUiItem(
                callId = call.id,
                toolId = call.name,
                failed = failedCall,
                resultText = raw?.let(::unwrapResult),
            ),
        )
    }
    return ToolCallsUiModel(items, trivial, succeeded, failed)
}

/** Strips the untrusted-output markers [ToolExecutor] wraps every stored result in. */
internal fun unwrapResult(content: String): String {
    var text = content.trim()
    if (text.startsWith(UntrustedToolOutput.OPEN)) {
        text = text.removePrefix(UntrustedToolOutput.OPEN).trimStart()
    }
    if (text.endsWith(UntrustedToolOutput.CLOSE)) {
        text = text.removeSuffix(UntrustedToolOutput.CLOSE).trimEnd()
    }
    return text
}

/**
 * True when a stored result reports failure. Tool failures are JSON
 * `{"success": false, ...}` (see [ToolExecutor]); anything else — plain text,
 * success payloads, unparseable bodies — counts as success so the card never
 * cries wolf on tools with free-form output.
 */
internal fun isFailureResult(content: String): Boolean = try {
    val element = Json.parseToJsonElement(unwrapResult(content))
    element.jsonObject["success"]?.jsonPrimitive?.booleanOrNull == false
} catch (_: Exception) {
    false
}

/** Single-line preview: collapses whitespace runs and cuts at [maxChars] with an ellipsis. */
internal fun trimPreview(text: String, maxChars: Int = 300): String {
    val singleLine = text.replace(Regex("\\s+"), " ").trim()
    if (singleLine.length <= maxChars) return singleLine
    return singleLine.take(maxChars).trimEnd() + "…"
}
