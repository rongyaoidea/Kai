package com.inspiredandroid.kai.tools

import android.content.Context
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import org.koin.java.KoinJavaComponent.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Kai-native web interaction: drive a persistent offscreen WebView through
 * the main agent tool loop — no browser-use framework, no extra model, no
 * keys. Each conversation gets its own browser session (page, history, and
 * cookies persist across calls); `close` drops it.
 *
 * Element ids are snapshot-scoped like ui_dump: always `snapshot` first and
 * use fresh ids. Prefer ui_dump/ui_act for native apps; this tool is for web
 * pages that need multi-step interaction (search, open, fill, submit) rather
 * than one-shot reading (which is browse_page).
 */
object WebActTools {
    private val context: Context by inject(Context::class.java)
    private val sessions: WebViewSessionManager by lazy {
        WebViewSessionManager(context.applicationContext)
    }

    val webActTool = object : Tool {
        override val timeout = 60.seconds

        override val schema = ToolSchema(
            name = "web_act",
            description = "Interact with a web page in the device's browser session: go to URLs, " +
                "list clickable and fillable elements, tap them, fill forms, scroll, and go back. " +
                "Workflow: goto a URL, then snapshot, then tap/input on fresh element ids (e0, e1, …). " +
                "Snapshot and scroll report the page position (scroll_y / max_scroll_y / at_bottom) so you know whether more content exists below. " +
                "Ids expire on navigation or page change — re-run snapshot when a tap reports the page changed. " +
                "For irreversible actions (purchases, payments, sending, deleting, submitting applications) " +
                "confirm with the user first and say what you are about to do. " +
                "Never enter passwords, OTP codes, or payment details unless the user provided them for this exact page. " +
                "Banking, payment, and login flows need explicit per-action user approval.",
            parameters = mapOf(
                "action" to ParameterSchema("string", "One of: goto, snapshot, tap, input, scroll, back, close (required)", true),
                "url" to ParameterSchema("string", "URL to open (goto)", false),
                "node_id" to ParameterSchema("string", "Element id from snapshot, e.g. e3 (tap/input)", false),
                "text" to ParameterSchema("string", "Text to type (input)", false),
                "submit" to ParameterSchema("boolean", "Press Enter after typing, e.g. to submit a search (input, default false)", false),
                "expect" to ParameterSchema("string", "Skip the tap unless the element still shows this text (tap)", false),
                "direction" to ParameterSchema("string", "up|down for scroll (default down)", false),
                "distance" to ParameterSchema("integer", "Scroll pixels (default 600)", false),
                "timeout" to ParameterSchema("integer", "Timeout in seconds for goto (default 30, max 60)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            if (!WebViewPageRenderer.isAvailable()) {
                return mapOf("success" to false, "error" to "System WebView is not available on this device")
            }
            val sessionId = currentConversationIdOrNull() ?: "default"
            return try {
                when ((args["action"] as? String)?.lowercase()) {
                    "goto" -> {
                        val url = (args["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: return mapOf("success" to false, "error" to "url is required for goto")
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            return mapOf("success" to false, "error" to "only http and https URLs are allowed")
                        }
                        // Same host policy as fetch_url, so browsing cannot reach the
                        // private network that tool refuses.
                        blockedUrlHostReason(url)?.let { blocked ->
                            return mapOf("success" to false, "error" to blocked)
                        }
                        val timeoutMs = ((args["timeout"] as? Number)?.toLong() ?: 30L)
                            .coerceIn(10, 60) * 1000
                        val landed = sessions.goto(sessionId, url, timeoutMs)
                        mapOf("success" to true, "url" to landed.ifBlank { url }, "hint" to "Run snapshot to see the page elements")
                    }

                    "snapshot" -> {
                        val elements = sessions.snapshot(sessionId)
                        val page = sessions.pageScroll(sessionId)
                        buildMap<String, Any> {
                            put("success", true)
                            put("count", elements.size)
                            put(
                                "elements",
                                elements.mapIndexed { index, element ->
                                    buildMap<String, String> {
                                        put("id", "e$index")
                                        put("tag", element.tag)
                                        if (element.text.isNotEmpty()) put("text", element.text)
                                        if (element.type.isNotEmpty()) put("type", element.type)
                                        if (element.href.isNotEmpty()) put("href", element.href)
                                    }
                                },
                            )
                            if (page != null) {
                                put("scroll_y", page.y)
                                put("max_scroll_y", page.maxY)
                                put("at_bottom", page.atBottom)
                            }
                        }
                    }

                    "tap" -> {
                        val index = nodeIndex(args["node_id"])
                            ?: return mapOf("success" to false, "error" to "node_id is required for tap (e.g. e3 from snapshot)")
                        val result = sessions.tap(sessionId, index, args["expect"]?.toString().orEmpty())
                        if (!result.ok) {
                            return if (result.text.isNotEmpty()) {
                                mapOf("success" to false, "error" to "Element changed (now showing: ${result.text}). Run snapshot again for fresh ids.")
                            } else {
                                mapOf("success" to false, "error" to "No element e$index. Run snapshot first and use a fresh id.")
                            }
                        }
                        mapOf("success" to true, "text" to result.text)
                    }

                    "input" -> {
                        val index = nodeIndex(args["node_id"])
                            ?: return mapOf("success" to false, "error" to "node_id is required for input (e.g. e3 from snapshot)")
                        val text = args["text"]?.toString()
                            ?: return mapOf("success" to false, "error" to "text is required for input")
                        val submit = (args["submit"] as? Boolean) ?: false
                        val result = sessions.input(sessionId, index, text, submit)
                        if (!result.ok) {
                            return mapOf("success" to false, "error" to "No fillable element e$index. Run snapshot first and use a fresh id.")
                        }
                        mapOf("success" to true, "value" to result.text)
                    }

                    "scroll" -> {
                        val direction = ((args["direction"] as? String) ?: "down").lowercase()
                        if (direction != "up" && direction != "down") {
                            return mapOf("success" to false, "error" to "direction must be up or down")
                        }
                        val distance = ((args["distance"] as? Number)?.toInt() ?: 600).coerceIn(100, 5000)
                        val result = sessions.scroll(sessionId, if (direction == "down") distance else -distance)
                        if (!result.ok) {
                            return mapOf("success" to false, "error" to "scroll failed — the page may have changed; run snapshot again")
                        }
                        buildMap<String, Any> {
                            put("success", true)
                            put("scroll_y", result.y)
                            put("max_scroll_y", result.maxY)
                            put("at_bottom", result.atBottom)
                            put("moved", result.moved)
                            when {
                                !result.moved -> put("hint", "Page did not move — already at the end in that direction.")
                                result.atBottom -> put("hint", "Reached the bottom of the page.")
                            }
                        }
                    }

                    "back" -> {
                        if (!sessions.back(sessionId)) {
                            return mapOf("success" to false, "error" to "No page history to go back to")
                        }
                        mapOf("success" to true)
                    }

                    "close" -> {
                        sessions.close(sessionId)
                        mapOf("success" to true)
                    }

                    else -> mapOf("success" to false, "error" to "action must be goto|snapshot|tap|input|scroll|back|close")
                }
            } catch (t: Throwable) {
                mapOf("success" to false, "error" to "web action failed: ${t.message}")
            }
        }

        private fun nodeIndex(raw: Any?): Int? {
            val text = raw?.toString()?.trim()?.lowercase().orEmpty().removePrefix("e")
            return text.toIntOrNull()?.takeIf { it >= 0 }
        }
    }

    val webActToolInfo = ToolInfo(
        id = "web_act",
        name = "Control Web Page",
        description = "Tap, fill, and scroll web pages in the device browser",
        nameRes = null,
        descriptionRes = null,
        isEnabled = false,
    )
}
