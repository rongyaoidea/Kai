package com.inspiredandroid.kai.tools

/**
 * Pure-Kotlin parsing for the Shizuku UI backend: `uiautomator dump` XML,
 * `dumpsys activity` foreground detection, `wm size` display metrics, and
 * `input text` escaping. No platform APIs, so it stays unit-testable in
 * commonTest; the Android backend feeds it raw shell output.
 */
data class ShellUiNode(
    val text: String,
    val desc: String,
    val resId: String,
    val className: String,
    val pkg: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val depth: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

object ShellUiDumpParser {
    private val nodeTag = Regex("""<(/?)node\b([^>]*?)(/?)>""")
    private val attr = Regex("([\\w:.-]+)=\"([^\"]*)\"")
    private val bounds = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
    private val topResumed =
        Regex("""topResumedActivity=ActivityRecord\{[^}]*?\s([A-Za-z][\w$]+(?:\.[\w$]+)+)/""")
    private val sizePair = Regex("""(\d+)\s*x\s*(\d+)""")

    fun parseNodes(xml: String, maxNodes: Int, query: String?): List<ShellUiNode> {
        val out = ArrayList<ShellUiNode>()
        var depth = -1
        for (match in nodeTag.findAll(xml)) {
            val closing = match.groupValues[1] == "/"
            val selfClosing = match.groupValues[3] == "/"
            if (closing) {
                if (depth > -1) depth--
                continue
            }
            depth++
            if (out.size >= maxNodes) break
            val attrs = attr.findAll(match.groupValues[2])
                .associate { it.groupValues[1] to it.groupValues[2] }
            val boundsMatch = attrs["bounds"]?.let { bounds.find(it) } ?: continue
            val node = ShellUiNode(
                text = attrs["text"].orEmpty(),
                desc = attrs["content-desc"].orEmpty(),
                resId = attrs["resource-id"].orEmpty(),
                className = attrs["class"].orEmpty(),
                pkg = attrs["package"].orEmpty(),
                left = boundsMatch.groupValues[1].toInt(),
                top = boundsMatch.groupValues[2].toInt(),
                right = boundsMatch.groupValues[3].toInt(),
                bottom = boundsMatch.groupValues[4].toInt(),
                clickable = attrs["clickable"] == "true",
                editable = attrs["editable"] == "true" || attrs["class"] == "android.widget.EditText",
                scrollable = attrs["scrollable"] == "true",
                enabled = attrs["enabled"] != "false",
                depth = depth.coerceAtLeast(0),
            )
            if (query == null || nodeMatches(node, query)) out.add(node)
            if (selfClosing && depth > -1) depth--
        }
        return out
    }

    private fun nodeMatches(node: ShellUiNode, query: String): Boolean {
        val q = query.lowercase()
        return node.text.lowercase().contains(q) ||
            node.desc.lowercase().contains(q) ||
            node.resId.lowercase().contains(q)
    }

    /** Foreground package from `dumpsys activity activities`, or null. */
    fun foregroundPackage(dumpsysActivity: String): String? = topResumed.find(dumpsysActivity)?.groupValues?.get(1)

    /** Display size from `wm size` ("Physical size: 1080x2400"), or null. */
    fun displaySize(wmSizeOutput: String): Pair<Int, Int>? {
        val match = sizePair.find(wmSizeOutput) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    /**
     * Escape for `input text`: spaces become %s, quotes are dropped (the
     * command has no quoting mechanism). Non-ASCII (e.g. CJK) is left in
     * place but the platform command silently mangles it — callers detect
     * that case and steer toward the accessibility backend instead.
     */
    fun escapeInputText(text: String): String = text.replace(" ", "%s").filter { it != '\'' && it != '"' }

    fun hasNonAscii(text: String): Boolean = text.any { it.code > 127 }
}
