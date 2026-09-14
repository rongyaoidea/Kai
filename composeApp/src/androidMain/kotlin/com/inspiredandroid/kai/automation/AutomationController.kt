package com.inspiredandroid.kai.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import com.inspiredandroid.kai.tools.AutomationPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Facade every automation Tool talks to. Owns the policy gate: reads always run,
 * writes resolve the foreground package first and pass it through
 * [AutomationPolicy]. All blocking calls are shifted to [Dispatchers.IO] — Tool
 * execution never runs on the main thread.
 *
 * Two backends, one contract. While the Kai accessibility service is bound,
 * the tree and gestures go through it (rich actions: node click, direct
 * set-text, scroll actions). Otherwise [ShellUiBackend] serves the same calls
 * over Shizuku — `uiautomator dump` for the tree (UiAutomation as the shell
 * user, no accessibility service needed) and raw `input` injection for
 * actions, which is steadier on accessibility-flaky surfaces like Chrome
 * search boxes. Node IDs come from per-backend registries and expire after
 * [AutomationNodeRegistry.TTL_MS]; a detached or expired id is re-located by the
 * identity captured at dump time, and only then does the action fail with a
 * "re-run ui_dump" hint instead of acting on a recycled node.
 */
class AutomationController(
    private val context: Context,
    private val shell: ShellUiBackend,
) {

    fun isServiceEnabled(): Boolean = KaiAccessibilityService.getInstance() != null

    /** Which channel would serve a call right now: accessibility, shizuku, or none. */
    suspend fun activeBackend(): String = withContext(Dispatchers.IO) {
        when {
            isServiceEnabled() -> BACKEND_ACCESSIBILITY
            shell.isAvailable() -> ShellUiBackend.BACKEND_NAME
            else -> BACKEND_NONE
        }
    }

    fun openAccessibilitySettings() {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun serviceOrThrow(): KaiAccessibilityService = KaiAccessibilityService.getInstance()
        ?: throw AutomationNotEnabledException()

    private suspend fun gateWrite(allowedApps: Set<String>, explicitPackage: String? = null): String {
        val foreground = explicitPackage ?: if (isServiceEnabled()) {
            serviceOrThrow().foregroundPackage()
        } else {
            shell.foregroundPackage()
        }
        when (val verdict = AutomationPolicy.checkInteract(foreground, allowedApps)) {
            is AutomationPolicy.Verdict.Allow -> return foreground.orEmpty()
            is AutomationPolicy.Verdict.Deny -> throw AutomationBlockedException(verdict.reason)
        }
    }

    suspend fun foregroundApp(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val pkg = if (isServiceEnabled()) {
            serviceOrThrow().foregroundPackage()
        } else {
            shell.foregroundPackage()
        }
        mapOf("package" to (pkg ?: ""))
    }

    suspend fun dumpUi(
        // 20 rather than 10: real app trees nest layouts well past ten levels,
        // and a node that is cut from the dump is a node the agent cannot tap.
        maxDepth: Int = 20,
        maxNodes: Int = 200,
        query: String? = null,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            return@withContext shell.dumpUi(maxNodes, query)
        }
        val service = serviceOrThrow()
        val nodes = ArrayList<Map<String, Any?>>()
        for (root in service.rootNodes()) {
            walkNode(service, root, 0, maxDepth, maxNodes, query, nodes)
            if (nodes.size >= maxNodes) break
        }
        mapOf(
            "package" to (service.foregroundPackage() ?: ""),
            "count" to nodes.size,
            "truncated" to (nodes.size >= maxNodes),
            "nodes" to nodes,
            "backend" to BACKEND_ACCESSIBILITY,
        )
    }

    private fun walkNode(
        service: KaiAccessibilityService,
        node: AccessibilityNodeInfo?,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
        query: String?,
        out: MutableList<Map<String, Any?>>,
    ) {
        if (node == null || depth > maxDepth || out.size >= maxNodes) return
        if (query == null || nodeMatchesQuery(node, query)) {
            out.add(nodeToMap(service, node, depth))
        }
        for (i in 0 until node.childCount) {
            walkNode(service, node.getChild(i), depth + 1, maxDepth, maxNodes, query, out)
            if (out.size >= maxNodes) return
        }
    }

    private fun nodeMatchesQuery(node: AccessibilityNodeInfo, query: String): Boolean {
        val q = query.lowercase()
        return node.text?.toString()?.lowercase()?.contains(q) == true ||
            node.contentDescription?.toString()?.lowercase()?.contains(q) == true ||
            node.viewIdResourceName?.lowercase()?.contains(q) == true
    }

    private fun nodeToMap(
        service: KaiAccessibilityService,
        node: AccessibilityNodeInfo,
        depth: Int,
    ): Map<String, Any?> {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return mapOf(
            "id" to service.nodeRegistry.put(node),
            "text" to (node.text?.toString() ?: ""),
            "desc" to (node.contentDescription?.toString() ?: ""),
            "resId" to (node.viewIdResourceName ?: ""),
            "class" to (node.className?.toString() ?: ""),
            "package" to (node.packageName?.toString() ?: ""),
            "x" to rect.centerX(),
            "y" to rect.centerY(),
            "clickable" to node.isClickable,
            "editable" to node.isEditable,
            "scrollable" to node.isScrollable,
            "enabled" to node.isEnabled,
            "depth" to depth,
            "backend" to BACKEND_ACCESSIBILITY,
        )
    }

    /**
     * Resolves a dump id to a usable node. Handles detach after a screen change by
     * re-locating the element in the current tree via the identity captured at dump
     * time (resource id + text/description + class), so quick follow-up actions don't
     * force a full ui_dump. Throws [AutomationStaleNodeException] only when even the
     * signature is gone (id never existed or the element left the screen).
     */
    private fun resolveNode(service: KaiAccessibilityService, nodeId: String): AccessibilityNodeInfo {
        val cached = service.nodeRegistry.get(nodeId)
        if (cached != null && runCatching { cached.refresh() }.getOrDefault(false)) return cached
        val signature = service.nodeRegistry.signatureOf(nodeId)
        if (signature != null) {
            findNodeBySignature(service, signature)?.let { return it }
        }
        throw AutomationStaleNodeException(nodeId)
    }

    private fun findNodeBySignature(
        service: KaiAccessibilityService,
        signature: AutomationNodeRegistry.NodeSignature,
    ): AccessibilityNodeInfo? {
        // A signature with no unique anchor would match every node; refuse it.
        if (signature.resId == null && signature.text == null && signature.desc == null) return null
        for (root in service.rootNodes()) {
            findFirstNode(root, 0) { node ->
                if (signature.resId != null && node.viewIdResourceName != signature.resId) return@findFirstNode false
                if (signature.text != null && node.text?.toString() != signature.text) return@findFirstNode false
                if (signature.desc != null && node.contentDescription?.toString() != signature.desc) return@findFirstNode false
                if (signature.className != null && node.className?.toString() != signature.className) return@findFirstNode false
                true
            }?.let { return it }
        }
        return null
    }

    private fun findFirstNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            findFirstNode(node.getChild(i), depth + 1, predicate)?.let { return it }
        }
        return null
    }

    /** Waits until the accessibility event stream has been quiet for [quietMs] (bounded by [timeoutMs]). */
    private suspend fun awaitIdle(service: KaiAccessibilityService, quietMs: Long = 250, timeoutMs: Long = 1_200) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val last = service.lastEventAtMs()
            if (last > 0 && System.currentTimeMillis() - last >= quietMs) return
            delay(50)
        }
    }

    /**
     * Waits until [text] appears (or disappears with [gone]) anywhere on screen,
     * polling the live tree. Lets the agent synchronize instead of guessing sleeps.
     */
    suspend fun waitForText(text: String, gone: Boolean = false, timeoutMs: Long = 5_000L): Boolean = withContext(Dispatchers.IO) {
        val service = serviceOrThrow()
        val needle = text.lowercase()
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500L, 30_000L)
        while (System.currentTimeMillis() < deadline) {
            val present = findFirstContaining(service, needle) != null
            if (present != gone) return@withContext true
            delay(150)
        }
        false
    }

    private fun findFirstContaining(service: KaiAccessibilityService, needle: String): AccessibilityNodeInfo? {
        for (root in service.rootNodes()) {
            findFirstNode(root, 0) { node ->
                node.text?.toString()?.lowercase()?.contains(needle) == true ||
                    node.contentDescription?.toString()?.lowercase()?.contains(needle) == true
            }?.let { return it }
        }
        return null
    }

    /**
     * Dismisses obvious transient dialogs (onboarding "Skip", "Not now", update
     * prompts, ...) before an action, using a conservative exact-label allowlist.
     * Never presses allow/confirm-style buttons, so it can't grant permissions or
     * accept terms on the user's behalf.
     */
    private suspend fun dismissTransientDialogs(service: KaiAccessibilityService): List<String> {
        val dismissed = mutableListOf<String>()
        repeat(MAX_DISMISS_PER_ACTION) {
            val hit = service.rootNodes().firstNotNullOfOrNull { root -> findDismissButton(root) } ?: return dismissed
            val label = (hit.text?.toString() ?: hit.contentDescription?.toString()).orEmpty().trim()
            val clicked = try {
                hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Throwable) {
                false
            }
            if (!clicked) return dismissed
            dismissed.add(label)
        }
        if (dismissed.isNotEmpty()) awaitIdle(service)
        return dismissed
    }

    private fun findDismissButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? = findFirstNode(root, 0) { node ->
        node.isClickable && node.text?.toString()?.trim()?.lowercase()?.let { it in DISMISS_LABELS } == true
    }

    suspend fun tapNode(allowedApps: Set<String>, nodeId: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.tapNode(nodeId)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        // A shell id can linger while both backends are up (e.g. the dump
        // came from Shizuku, then the service bound). Resolve by registry,
        // accessibility first.
        if (shell.lookup(nodeId) != null && service.nodeRegistry.get(nodeId) == null) {
            return@withContext shell.tapNode(nodeId)
        }
        val node = resolveNode(service, nodeId)
        val clicked = try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                service.tapAt(rect.centerX(), rect.centerY())
            }
        } catch (_: Throwable) {
            false
        }
        if (!clicked) throw AutomationFailedException("tap on node $nodeId was rejected")
        awaitIdle(service)
        mapOf("success" to true, "node" to nodeId, "backend" to BACKEND_ACCESSIBILITY)
    }

    suspend fun tapAt(allowedApps: Set<String>, x: Int, y: Int): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.tapAt(x, y)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        if (!service.tapAt(x, y)) throw AutomationFailedException("tap at $x,$y was cancelled")
        awaitIdle(service)
        mapOf("success" to true, "x" to x, "y" to y, "backend" to BACKEND_ACCESSIBILITY)
    }

    suspend fun tapText(allowedApps: Set<String>, text: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.tapText(text)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        val match = findFirstByText(service, text)
            ?: throw AutomationFailedException("no on-screen element with text \"$text\"")
        val done = try {
            if (match.isClickable) {
                match.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val rect = Rect()
                match.getBoundsInScreen(rect)
                service.tapAt(rect.centerX(), rect.centerY())
            }
        } catch (_: Throwable) {
            false
        }
        if (!done) throw AutomationFailedException("tap on \"$text\" was rejected")
        awaitIdle(service)
        mapOf("success" to true, "text" to text, "backend" to BACKEND_ACCESSIBILITY)
    }

    private fun findFirstByText(service: KaiAccessibilityService, text: String): AccessibilityNodeInfo? {
        for (root in service.rootNodes()) {
            val hit = searchText(root, text, 0)
            if (hit != null) return hit
        }
        return null
    }

    private fun searchText(node: AccessibilityNodeInfo?, text: String, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 30) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) return node
        for (i in 0 until node.childCount) {
            searchText(node.getChild(i), text, depth + 1)?.let { return it }
        }
        return null
    }

    suspend fun inputText(
        allowedApps: Set<String>,
        text: String,
        nodeId: String?,
        clearFirst: Boolean,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.inputText(text, nodeId)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        if (nodeId != null && shell.lookup(nodeId) != null && service.nodeRegistry.get(nodeId) == null) {
            return@withContext shell.inputText(text, nodeId)
        }
        val target = if (nodeId != null) resolveNode(service, nodeId) else null
        val node = target?.let { editableTarget(service, it) }
            ?: findFocusedEditable(service)
            ?: throw AutomationFailedException(
                "no editable field found; ui_dump and pass the search/input node id, or tap the field first",
            )
        val finalText = if (clearFirst) text else (node.text?.toString().orEmpty() + text)
        // ACTION_SET_TEXT only lands on the focused input in many apps; request
        // focus first so typing into a field the agent just tapped actually works.
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Throwable) {
        }
        if (service.setNodeText(node, finalText)) {
            awaitIdle(service)
            return@withContext mapOf("success" to true, "text" to finalText, "backend" to BACKEND_ACCESSIBILITY)
        }
        // Custom search boxes / WebViews frequently reject SET_TEXT. The clipboard
        // carries any Unicode and ACTION_PASTE is accepted where SET_TEXT isn't.
        if (pasteInto(service, node, finalText)) {
            awaitIdle(service)
            return@withContext mapOf("success" to true, "text" to finalText, "backend" to BACKEND_ACCESSIBILITY, "input" to "paste")
        }
        throw AutomationFailedException("the field rejected both set-text and paste (not editable?)")
    }

    /**
     * Picks the node that actually accepts text. A dump can hand back the
     * clickable row that wraps the field; prefer an editable descendant, then
     * fall back to focusing the given node and letting the caller use whatever
     * ends up focused.
     */
    private fun editableTarget(service: KaiAccessibilityService, node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        findFirstNode(node, 0) { it.isEditable }?.let { return it }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Throwable) {
        }
        return null
    }

    private fun pasteInto(service: KaiAccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        setSystemClipboard(context, text)
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Throwable) {
        }
        val pasted = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            false
        }
        if (pasted) return true
        // Some fields only accept paste on the focused input node.
        val focused = findFocusedEditable(service) ?: return false
        return try {
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Throwable) {
            false
        }
    }

    /** Puts [text] on the system clipboard for the privileged/Shizuku input paths. */
    fun setClipboard(text: String) {
        setSystemClipboard(context, text)
    }

    private fun findFocusedEditable(service: KaiAccessibilityService): AccessibilityNodeInfo? {
        for (root in service.rootNodes()) {
            try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    suspend fun scroll(
        allowedApps: Set<String>,
        direction: String,
        nodeId: String?,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.scroll(direction, nodeId)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        if (nodeId != null && shell.lookup(nodeId) != null && service.nodeRegistry.get(nodeId) == null) {
            return@withContext shell.scroll(direction, nodeId)
        }
        val action = when (direction.lowercase()) {
            "down", "right" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> throw AutomationFailedException("direction must be up|down|left|right")
        }
        if (nodeId != null) {
            val node = resolveNode(service, nodeId)
            if (!node.performAction(action)) throw AutomationFailedException("node $nodeId refused to scroll")
            awaitIdle(service)
            return@withContext mapOf("success" to true, "direction" to direction, "backend" to BACKEND_ACCESSIBILITY)
        }
        // Fall back to a swipe gesture over the lower half of the screen.
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels / 2
        val distance = (metrics.heightPixels * 0.4).toInt()
        val centerY = metrics.heightPixels / 2
        val ok = if (direction.lowercase() == "down" || direction.lowercase() == "right") {
            service.swipe(x, centerY + distance / 2, x, centerY - distance / 2)
        } else {
            service.swipe(x, centerY - distance / 2, x, centerY + distance / 2)
        }
        if (!ok) throw AutomationFailedException("scroll gesture was cancelled")
        awaitIdle(service)
        mapOf("success" to true, "direction" to direction, "backend" to BACKEND_ACCESSIBILITY)
    }

    suspend fun pressKey(allowedApps: Set<String>, key: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            gateWrite(allowedApps)
            return@withContext shell.pressKey(key)
        }
        val service = serviceOrThrow()
        gateWrite(allowedApps)
        dismissTransientDialogs(service)
        val action = when (key.uppercase()) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK

            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME

            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS

            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS

            else -> throw AutomationFailedException(
                "key must be BACK|HOME|RECENTS|NOTIFICATIONS " +
                    "(anything else needs the Shizuku privileged_input tool)",
            )
        }
        if (!service.globalAction(action)) throw AutomationFailedException("system rejected $key")
        awaitIdle(service, quietMs = 150, timeoutMs = 600)
        mapOf("success" to true, "key" to key.uppercase(), "backend" to BACKEND_ACCESSIBILITY)
    }

    suspend fun launchApp(
        allowedApps: Set<String>,
        packageName: String,
        activity: String?,
        uri: String?,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        // Bringing Kai itself forward is always allowed — it's how the agent hands the
        // turn back to the user after working inside another app.
        if (packageName == context.packageName || packageName.equals("kai", true) || packageName.equals("self", true)) {
            return@withContext returnToKai()
        }
        // Pure intents: needs neither backend, only the policy verdict on the target.
        when (val verdict = AutomationPolicy.checkInteract(packageName, allowedApps)) {
            is AutomationPolicy.Verdict.Allow -> Unit
            is AutomationPolicy.Verdict.Deny -> throw AutomationBlockedException(verdict.reason)
        }
        val intent = when {
            uri != null -> Intent(Intent.ACTION_VIEW, uri.toUri())

            activity != null -> Intent().setClassName(packageName, activity)

            else -> context.packageManager.getLaunchIntentForPackage(packageName)
                ?: run {
                    // Android 11+ package visibility can hide the launcher entry even for an
                    // installed app. Resolve it as the shell user before concluding anything.
                    val resolved = if (shell.isAvailable()) shell.resolveLauncherActivity(packageName) else null
                    if (resolved != null) {
                        if (!shell.startActivity(resolved)) {
                            throw AutomationFailedException("package $packageName resolved to $resolved but am start failed")
                        }
                        return@withContext mapOf(
                            "success" to true,
                            "package" to packageName,
                            "component" to resolved,
                            "backend" to ShellUiBackend.BACKEND_NAME,
                        )
                    }
                    throw AutomationFailedException(
                        "package $packageName is not installed or has no launcher entry. Do NOT silently fall back " +
                            "to a web search — verify with privileged_shell (`pm list packages | grep $packageName`) " +
                            "and report the result to the user.",
                    )
                }
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            throw AutomationFailedException("could not launch $packageName: ${t.message}")
        }
        mapOf("success" to true, "package" to packageName)
    }

    /**
     * Returns to Kai's own UI. Prefers Shizuku's `am start`, which is exempt from
     * Android's background-activity-launch limits; the direct start is a best-effort
     * fallback when Shizuku is unavailable.
     */
    suspend fun returnToKai(): Map<String, Any?> {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: throw AutomationFailedException("could not resolve Kai's launcher activity")
        val component = launch.component?.flattenToShortString()
        if (component != null && shell.isAvailable() && shell.startActivity(component)) {
            return mapOf("success" to true, "package" to context.packageName, "backend" to ShellUiBackend.BACKEND_NAME)
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return if (runCatching { context.startActivity(launch) }.isSuccess) {
            mapOf("success" to true, "package" to context.packageName, "backend" to "direct")
        } else {
            throw AutomationFailedException("could not bring Kai to the foreground; ask the user to reopen Kai")
        }
    }

    suspend fun screenshot(scale: Float): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            return@withContext shell.screenshot(scale)
        }
        val service = serviceOrThrow()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw AutomationFailedException("screenshots need Android 11+; use ui_dump instead")
        }
        val shot = service.captureScreenshot()
        val raw = shot.bitmap ?: throw AutomationFailedException(shot.error ?: "screenshot failed")
        val clamped = scale.coerceIn(0.1f, 1f)
        val bitmap = if (clamped == 1f) {
            raw
        } else {
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * clamped).toInt().coerceAtLeast(1),
                (raw.height * clamped).toInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== raw) raw.recycle()
            scaled
        }
        val dir = File(context.cacheDir, "a11y_screenshots").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.png")
        val width = bitmap.width
        val height = bitmap.height
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        mapOf(
            "success" to true,
            "path" to file.absolutePath,
            "width" to width,
            "height" to height,
            "backend" to BACKEND_ACCESSIBILITY,
            "hint" to "the screenshot is shown to the user automatically in the chat; call open_file only if the user explicitly asks. The agent perceives apps through ui_dump",
        )
    }

    suspend fun recentEvents(limit: Int, packageFilter: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (!isServiceEnabled()) {
            return@withContext mapOf(
                "count" to 0,
                "events" to emptyList<Any>(),
                "backend" to ShellUiBackend.BACKEND_NAME,
                "note" to "live UI events need the accessibility service; use ui_dump to see the current screen",
            )
        }
        val service = serviceOrThrow()
        val events = service.recentEvents()
            .filter { packageFilter == null || it.packageName == packageFilter }
            .takeLast(limit.coerceIn(1, 200))
            .map {
                mapOf(
                    "type" to it.type,
                    "package" to (it.packageName ?: ""),
                    "text" to (it.text ?: ""),
                )
            }
        mapOf("count" to events.size, "events" to events, "backend" to BACKEND_ACCESSIBILITY)
    }

    companion object {
        const val BACKEND_ACCESSIBILITY = "accessibility"
        const val BACKEND_NONE = "none"

        /** At most this many transient dialogs are auto-dismissed per action. */
        private const val MAX_DISMISS_PER_ACTION = 2

        /**
         * Exact labels (lowercase) of clearly transient dialogs. Deliberately
         * excludes allow/confirm/agree-style buttons: dismissing a prompt is safe,
         * accepting one is a decision that belongs to the user.
         */
        private val DISMISS_LABELS = setOf(
            "skip",
            "not now",
            "later",
            "close",
            "dismiss",
            "got it",
            "no thanks",
            "no, thanks",
            "remind me later",
            "跳过",
            "以后再说",
            "稍后再说",
            "稍后",
            "暂不",
            "关闭",
            "我知道了",
            "知道了",
            "不用了",
            "下次再说",
        )
    }
}

class AutomationNotEnabledException :
    Exception(
        "Neither backend is available: enable the Kai accessibility service under " +
            "Settings → Accessibility → Kai, or start Shizuku and enable it in Kai's automation settings.",
    )

class AutomationBlockedException(reason: String) : Exception(reason)

class AutomationStaleNodeException(nodeId: String) : Exception("node $nodeId expired or never existed; re-run ui_dump and use a fresh id")

class AutomationFailedException(reason: String) : Exception(reason)
