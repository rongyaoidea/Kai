package com.inspiredandroid.kai.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.inspiredandroid.kai.tools.ShellUiDumpParser
import com.inspiredandroid.kai.tools.ShellUiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * UI automation without any accessibility service: the tree comes from the
 * platform `uiautomator dump` tool (which itself drives UiAutomation as the
 * shell user), and actions go through raw `input` injection — the same
 * channel a finger uses, so WebViews and other accessibility-flaky surfaces
 * (e.g. a Chrome search box) behave more stably than under `performAction`.
 *
 * Runs only when Shizuku is READY; the [AutomationController] prefers the
 * accessibility backend while it is bound and falls back here. Node ids are
 * `sh` + 4 base-36 chars, which can never collide with the accessibility
 * registry's always-4-char ids, so routing by id shape is unambiguous.
 */
class ShellUiBackend(
    private val context: Context,
    private val shizuku: ShizukuController,
) {
    private data class Signature(
        val resId: String?,
        val text: String?,
        val desc: String?,
        val className: String?,
    )

    private data class Entry(val node: ShellUiNode, val createdAt: Long)

    private val registry = ConcurrentHashMap<String, Entry>()

    // Kept after an entry expires so a stale id can still be re-located by identity.
    private val signatures = ConcurrentHashMap<String, Signature>()
    private val seq = AtomicLong(0)

    fun isAvailable(): Boolean = try {
        shizuku.state() == ShizukuController.State.READY
    } catch (_: Throwable) {
        false
    }

    private fun register(node: ShellUiNode): String {
        val now = System.currentTimeMillis()
        val iterator = registry.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > AutomationNodeRegistry.TTL_MS) iterator.remove()
        }
        val id = "sh" + java.lang.Long.toString(seq.incrementAndGet() and 0xFFFFFL, 36).padStart(4, '0')
        registry[id] = Entry(node, now)
        if (signatures.size > MAX_SIGNATURES) signatures.clear()
        signatures[id] = Signature(
            resId = node.resId.takeIf { it.isNotBlank() },
            text = node.text.takeIf { it.isNotBlank() },
            desc = node.desc.takeIf { it.isNotBlank() },
            className = node.className.takeIf { it.isNotBlank() },
        )
        return id
    }

    fun lookup(id: String): ShellUiNode? {
        val entry = registry[id] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > AutomationNodeRegistry.TTL_MS) {
            registry.remove(id)
            return null
        }
        return entry.node
    }

    private fun matches(node: ShellUiNode, signature: Signature): Boolean {
        if (signature.resId == null && signature.text == null && signature.desc == null) return false
        if (signature.resId != null && node.resId != signature.resId) return false
        if (signature.text != null && node.text != signature.text) return false
        if (signature.desc != null && node.desc != signature.desc) return false
        if (signature.className != null && node.className != signature.className) return false
        return true
    }

    /** Fresh dump + identity match when a cached id no longer resolves. */
    private suspend fun resolveNode(nodeId: String): ShellUiNode {
        lookup(nodeId)?.let { return it }
        val signature = signatures[nodeId] ?: throw AutomationStaleNodeException(nodeId)
        val node = dumpNodes(maxNodes = 500, query = null).firstOrNull { matches(it, signature) }
            ?: throw AutomationStaleNodeException(nodeId)
        register(node)
        return node
    }

    private fun tmpFile(prefix: String, suffix: String): String = "/data/local/tmp/${prefix}_${UUID.randomUUID()}$suffix"

    private suspend fun execOrThrow(
        argv: Array<String>,
        timeoutMs: Long,
        maxOutputBytes: Int = PrivilegedShellService.DEFAULT_MAX_OUTPUT_BYTES,
        what: String,
    ): String {
        val result = shizuku.exec(argv, timeoutMs, maxOutputBytes)
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().take(300)
            throw AutomationFailedException("$what failed: $detail")
        }
        return result.stdout
    }

    private suspend fun dumpNodes(maxNodes: Int, query: String?): List<ShellUiNode> {
        val tmp = tmpFile("kai_ui", ".xml")
        try {
            // `--compressed` only exists on newer releases; fall back to a
            // plain dump when the flag itself is rejected — but let Shizuku
            // connectivity errors propagate instead of masking them.
            val compressedOk = try {
                execOrThrow(
                    arrayOf(systemBin("uiautomator"), "dump", "--compressed", tmp),
                    15_000L,
                    what = "uiautomator dump",
                )
                true
            } catch (_: AutomationFailedException) {
                false
            }
            if (!compressedOk) {
                execOrThrow(arrayOf(systemBin("uiautomator"), "dump", tmp), 15_000L, what = "uiautomator dump")
            }
            val xml = execOrThrow(arrayOf(systemBin("cat"), tmp), 10_000L, what = "reading ui dump")
            if (xml.isBlank()) throw AutomationFailedException("uiautomator dump came back empty")
            return ShellUiDumpParser.parseNodes(xml, maxNodes, query)
        } finally {
            try {
                shizuku.exec(arrayOf(systemBin("rm"), "-f", tmp), 5_000L)
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun dumpUi(maxNodes: Int, query: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val nodes = dumpNodes(maxNodes, query).map { node ->
            mapOf(
                "id" to register(node),
                "text" to node.text,
                "desc" to node.desc,
                "resId" to node.resId,
                "class" to node.className,
                "package" to node.pkg,
                "x" to node.centerX,
                "y" to node.centerY,
                "clickable" to node.clickable,
                "editable" to node.editable,
                "scrollable" to node.scrollable,
                "enabled" to node.enabled,
                "depth" to node.depth,
            )
        }
        mapOf(
            "package" to (foregroundPackage() ?: ""),
            "count" to nodes.size,
            "truncated" to (nodes.size >= maxNodes),
            "nodes" to nodes,
            "backend" to BACKEND_NAME,
        )
    }

    suspend fun tapAt(x: Int, y: Int): Map<String, Any?> = withContext(Dispatchers.IO) {
        execOrThrow(arrayOf(systemBin("input"), "tap", x.toString(), y.toString()), 10_000L, what = "input tap")
        mapOf("success" to true, "x" to x, "y" to y, "backend" to BACKEND_NAME)
    }

    suspend fun tapNode(nodeId: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val node = resolveNode(nodeId)
        execOrThrow(
            arrayOf(systemBin("input"), "tap", node.centerX.toString(), node.centerY.toString()),
            10_000L,
            what = "input tap",
        )
        mapOf("success" to true, "node" to nodeId, "backend" to BACKEND_NAME)
    }

    suspend fun tapText(text: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        // Fresh dump per call: shell ids can't observe the live tree, so never
        // act on a cached one here.
        val dumped = dumpUi(maxNodes = 500, query = null)

        @Suppress("UNCHECKED_CAST")
        val nodes = (dumped["nodes"] as? List<Map<String, Any?>>).orEmpty()
        val hit = nodes.firstOrNull { it["text"] == text || it["desc"] == text }
            ?: throw AutomationFailedException("no on-screen element with text \"$text\"")
        val x = (hit["x"] as? Number)?.toInt()
            ?: throw AutomationFailedException("element \"$text\" has no coordinates")
        val y = (hit["y"] as? Number)?.toInt()
            ?: throw AutomationFailedException("element \"$text\" has no coordinates")
        execOrThrow(arrayOf(systemBin("input"), "tap", x.toString(), y.toString()), 10_000L, what = "input tap")
        mapOf("success" to true, "text" to text, "backend" to BACKEND_NAME)
    }

    suspend fun inputText(text: String, nodeId: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (text.isEmpty()) throw AutomationFailedException("text is empty")
        if (nodeId != null) {
            val node = resolveNode(nodeId)
            execOrThrow(
                arrayOf(systemBin("input"), "tap", node.centerX.toString(), node.centerY.toString()),
                10_000L,
                what = "focusing field",
            )
            delay(400L)
        }
        if (ShellUiDumpParser.hasNonAscii(text)) {
            // The platform `input text` mangles non-ASCII/CJK. Write the clipboard
            // (Kai process) and send KEYCODE_PASTE into the focused field instead.
            setSystemClipboard(context, text)
            delay(200L)
            execOrThrow(arrayOf(systemBin("input"), "keyevent", KEYCODE_PASTE), 10_000L, what = "input paste")
        } else {
            execOrThrow(
                arrayOf(systemBin("input"), "text", ShellUiDumpParser.escapeInputText(text)),
                10_000L,
                what = "input text",
            )
        }
        mapOf("success" to true, "text" to text, "backend" to BACKEND_NAME)
    }

    suspend fun scroll(direction: String, nodeId: String?): Map<String, Any?> = withContext(Dispatchers.IO) {
        val dir = direction.lowercase()
        if (dir != "down" && dir != "up" && dir != "left" && dir != "right") {
            throw AutomationFailedException("direction must be up|down|left|right")
        }
        val (left, top, right, bottom) = if (nodeId != null) {
            val node = resolveNode(nodeId)
            listOf(node.left, node.top, node.right, node.bottom)
        } else {
            val (width, height) = displaySize()
            listOf(0, 0, width, height)
        }
        val x = (left + right) / 2
        val cy = (top + bottom) / 2
        val distance = (((bottom - top) * 0.6).toInt()).coerceAtLeast(200)
        val (x1, y1, x2, y2) = if (dir == "down" || dir == "right") {
            listOf(x, cy + distance / 2, x, cy - distance / 2)
        } else {
            listOf(x, cy - distance / 2, x, cy + distance / 2)
        }
        // Horizontal scrolls reuse the vertical gesture axis on the node's
        // center line; the shell `input` command has no axis flag.
        val swipe = if (dir == "left" || dir == "right") {
            val cx = (left + right) / 2
            val half = (((right - left) * 0.3).toInt()).coerceAtLeast(200)
            if (dir == "right") {
                listOf(cx - half, cy, cx + half, cy)
            } else {
                listOf(cx + half, cy, cx - half, cy)
            }
        } else {
            listOf(x1, y1, x2, y2)
        }
        execOrThrow(
            arrayOf(
                "input",
                "swipe",
                swipe[0].toString(),
                swipe[1].toString(),
                swipe[2].toString(),
                swipe[3].toString(),
                "300",
            ),
            10_000L,
            what = "input swipe",
        )
        mapOf("success" to true, "direction" to direction, "backend" to BACKEND_NAME)
    }

    suspend fun pressKey(key: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val code = key.trim().uppercase()
        if (code.isEmpty() || code.any { !it.isLetterOrDigit() && it != '_' }) {
            throw AutomationFailedException("key must be a keyevent name like ENTER, DPAD_DOWN, or BACK")
        }
        execOrThrow(arrayOf(systemBin("input"), "keyevent", code), 10_000L, what = "input keyevent")
        mapOf("success" to true, "key" to code, "backend" to BACKEND_NAME)
    }

    /**
     * Resolves a package's launcher component as the shell user. This is the
     * fallback for Android 11+ package-visibility limits, where the app-side
     * `getLaunchIntentForPackage` can return null for an installed app.
     * Returns a `pkg/activity` component string, or null when there is none.
     */
    suspend fun resolveLauncherActivity(packageName: String): String? = withContext(Dispatchers.IO) {
        val out = runCatching {
            execOrThrow(
                arrayOf(
                    systemBin("cmd"),
                    "package",
                    "resolve-activity",
                    "--brief",
                    "-a",
                    "android.intent.action.MAIN",
                    "-c",
                    "android.intent.category.LAUNCHER",
                    packageName,
                ),
                8_000L,
                what = "resolve-activity",
            )
        }.getOrNull() ?: return@withContext null
        // Success prints the component on a line of its own; skip logs/prompts.
        out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && !it.contains(' ') && !it.startsWith("priority") }
    }

    /** Starts an activity as the shell user (`am start`), exempt from background-launch limits. */
    suspend fun startActivity(component: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            execOrThrow(arrayOf(systemBin("am"), "start", "-n", component), 10_000L, what = "am start")
        }.isSuccess
    }

    suspend fun foregroundPackage(): String? = withContext(Dispatchers.IO) {
        val out = try {
            shizuku.exec(arrayOf(systemBin("dumpsys"), "activity", "activities"), 8_000L).stdout
        } catch (_: Throwable) {
            return@withContext null
        }
        ShellUiDumpParser.foregroundPackage(out)
    }

    suspend fun displaySize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val out = try {
            shizuku.exec(arrayOf(systemBin("wm"), "size"), 5_000L).stdout
        } catch (t: Throwable) {
            throw AutomationFailedException("could not read display size: ${t.message}")
        }
        ShellUiDumpParser.displaySize(out)
            ?: throw AutomationFailedException("could not parse display size from: ${out.trim().take(120)}")
    }

    suspend fun screenshot(scale: Float): Map<String, Any?> = withContext(Dispatchers.IO) {
        val tmp = tmpFile("kai_shot", ".png")
        try {
            execOrThrow(arrayOf(systemBin("screencap"), "-p", tmp), 15_000L, what = "screencap")
            val raw = execOrThrow(
                arrayOf(systemBin("base64"), tmp),
                15_000L,
                maxOutputBytes = PrivilegedShellService.MAX_OUTPUT_BYTES_HARD_CAP,
                what = "reading screenshot",
            ).filterNot { it.isWhitespace() }
            if (raw.isEmpty()) throw AutomationFailedException("screenshot came back empty")
            val png = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (t: Throwable) {
                throw AutomationFailedException("could not decode screenshot: ${t.message}")
            }
            var bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: throw AutomationFailedException("could not decode screenshot")
            val clamped = scale.coerceIn(0.1f, 1f)
            if (clamped != 1f) {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * clamped).toInt().coerceAtLeast(1),
                    (bitmap.height * clamped).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
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
                "backend" to BACKEND_NAME,
                "hint" to "the screenshot is shown to the user automatically in the chat; call open_file only if the user explicitly asks. The agent perceives apps through ui_dump",
            )
        } finally {
            try {
                shizuku.exec(arrayOf(systemBin("rm"), "-f", tmp), 5_000L)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val BACKEND_NAME = "shizuku-uiautomator"

        /** Bound on retained signatures; ids are short-lived, so a reset is fine. */
        private const val MAX_SIGNATURES = 4_096
    }
}
