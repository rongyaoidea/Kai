package com.inspiredandroid.kai.tools

import android.content.Context
import com.inspiredandroid.kai.data.ToolScreenshotPreview
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_browse_page_description
import kai.composeapp.generated.resources.tool_browse_page_name
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val BROWSER_DIR = "browser"
private const val SCREENSHOT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Renders a web page with the system WebView and returns readable text
 * (default) or a screenshot. This is the tool for JavaScript-rendered pages,
 * SPAs, and lazy-loaded content that `fetch_url` cannot see — it runs a real
 * browser engine on the device's own network stack instead of the sandbox's.
 *
 * Each call renders in a one-shot offscreen WebView that is destroyed
 * afterwards: stateless, nothing to install, no sandbox required.
 */
object BrowsePageTool : Tool {
    private val context: Context by inject(Context::class.java)

    override val timeout: Duration = 60.seconds

    override val schema: ToolSchema
        get() = ToolSchema(
            name = "browse_page",
            description = "Render a web page with the system browser and return its readable text (default) or a screenshot. Use this for JavaScript-rendered pages, single-page apps, and lazy-loaded content that fetch_url cannot see. Text mode returns the page as readable text and scrolls through the page first so lazy-loaded content is included (scroll=false disables). Screenshot mode saves a viewport PNG and shows it to the user automatically in the chat. Screenshots capture the viewport only, not the full page.",
            parameters = mapOf(
                "url" to ParameterSchema("string", "The absolute http(s) URL to render", true),
                "mode" to ParameterSchema("string", "text (default) or screenshot", false),
                "scroll" to ParameterSchema("boolean", "Text mode only: scroll through the page to trigger lazy-loaded content before extracting text (default true). Set false for a first-screen-only read.", false),
                "width" to ParameterSchema("integer", "Screenshot viewport width (default 1280)", false),
                "height" to ParameterSchema("integer", "Screenshot viewport height (default 1024)", false),
                "timeout" to ParameterSchema("integer", "Timeout in seconds (default 45, max 60)", false),
            ),
        )

    override suspend fun execute(args: Map<String, Any>): Any {
        val url = (args["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "error" to "url is required")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return mapOf("success" to false, "error" to "only http and https URLs are allowed")
        }
        // Same host policy as fetch_url — without it this tool is simply the way around the
        // SSRF guard that one tool has.
        blockedUrlHostReason(url)?.let { blocked ->
            return mapOf("success" to false, "error" to blocked)
        }
        if (!WebViewPageRenderer.isAvailable()) {
            return mapOf("success" to false, "error" to "System WebView is not available on this device, so pages cannot be rendered")
        }
        val mode = (args["mode"] as? String)?.lowercase() ?: "text"
        if (mode != "text" && mode != "screenshot") {
            return mapOf("success" to false, "error" to "mode must be text or screenshot")
        }
        val timeoutSeconds = ((args["timeout"] as? Number)?.toLong() ?: 45L).coerceIn(10, 60)
        val width = ((args["width"] as? Number)?.toInt() ?: 1280).coerceIn(320, 1920)
        val height = ((args["height"] as? Number)?.toInt() ?: 1024).coerceIn(240, 4000)
        val scrollToLoad = when (val raw = args["scroll"]) {
            null -> true
            is Boolean -> raw
            else -> raw.toString().equals("true", ignoreCase = true)
        }

        val renderer = WebViewPageRenderer(context.applicationContext)
        val rendered = try {
            withTimeout(timeoutSeconds * 1000) {
                renderer.render(
                    url = url,
                    width = width,
                    height = height,
                    timeoutMs = timeoutSeconds * 1000,
                    wantScreenshot = mode == "screenshot",
                    scrollToLoad = scrollToLoad,
                )
            }
        } catch (_: TimeoutCancellationException) {
            return mapOf("success" to false, "error" to "Timed out after ${timeoutSeconds}s waiting for $url")
        } catch (t: Throwable) {
            return mapOf("success" to false, "error" to "Page render failed: ${t.message}")
        }

        if (mode == "screenshot") {
            val bitmap = rendered.screenshot
                ?: return mapOf("success" to false, "error" to "Screenshot capture failed for $url")
            val file = try {
                saveScreenshot(bitmap)
            } catch (t: Throwable) {
                return mapOf("success" to false, "error" to "Could not save screenshot: ${t.message}")
            }
            // Preview in chat so seeing the capture never requires leaving Kai.
            val conversationId = currentConversationIdOrNull()
            runCatching { ToolScreenshotPreview.publish(conversationId, file.readBytes()) }
            return mapOf(
                "success" to true,
                "path" to file.name,
                "hint" to "The screenshot is shown to the user automatically in the chat. Call open_file only if the user explicitly asks to open it in another app. Viewport only, not the full page.",
            )
        }

        val text = extractReadableText(rendered.text.trim())
        if (text.isBlank()) {
            return mapOf("success" to false, "error" to "Page rendered no readable text (empty page, login wall, or bot check). Try screenshot mode to see what loaded.")
        }
        return mapOf("success" to true, "url" to url, "text" to text)
    }

    /**
     * Screenshots live under the app cache (`cache/browser/`), shared through
     * FileProvider and openable via open_file — no sandbox install needed.
     * Prunes week-old captures so repeated screenshots cannot fill storage.
     */
    private fun saveScreenshot(bitmap: android.graphics.Bitmap): File {
        val dir = File(context.cacheDir, BROWSER_DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > SCREENSHOT_RETENTION_MS) runCatching { file.delete() }
        }
        val out = File(dir, "browser-$now.png")
        out.outputStream().use { stream ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IllegalStateException("bitmap compression failed")
            }
        }
        return out
    }

    /** Screenshot folder, for open_file's fallback root. */
    internal fun browserDir(): File = File(context.cacheDir, BROWSER_DIR)

    val toolInfo = ToolInfo(
        id = "browse_page",
        name = "Browse Page",
        description = "Render pages with the system browser",
        nameRes = Res.string.tool_browse_page_name,
        descriptionRes = Res.string.tool_browse_page_description,
        isEnabled = false,
    )
}
