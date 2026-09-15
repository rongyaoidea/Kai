package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.automation.AutomationBlockedException
import com.inspiredandroid.kai.automation.AutomationController
import com.inspiredandroid.kai.automation.AutomationFailedException
import com.inspiredandroid.kai.automation.AutomationNotEnabledException
import com.inspiredandroid.kai.automation.AutomationStaleNodeException
import com.inspiredandroid.kai.automation.KEYCODE_PASTE
import com.inspiredandroid.kai.automation.ShizukuController
import com.inspiredandroid.kai.automation.ShizukuDeniedException
import com.inspiredandroid.kai.automation.ShizukuUnavailableException
import com.inspiredandroid.kai.automation.systemBin
import com.inspiredandroid.kai.data.AppCardStore
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.ToolScreenshotPreview
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_app_launch_description
import kai.composeapp.generated.resources.tool_app_launch_name
import kai.composeapp.generated.resources.tool_privileged_input_description
import kai.composeapp.generated.resources.tool_privileged_input_name
import kai.composeapp.generated.resources.tool_privileged_shell_description
import kai.composeapp.generated.resources.tool_privileged_shell_name
import kai.composeapp.generated.resources.tool_ui_act_description
import kai.composeapp.generated.resources.tool_ui_act_name
import kai.composeapp.generated.resources.tool_ui_dump_description
import kai.composeapp.generated.resources.tool_ui_dump_name
import kai.composeapp.generated.resources.tool_ui_events_description
import kai.composeapp.generated.resources.tool_ui_events_name
import kai.composeapp.generated.resources.tool_ui_screenshot_description
import kai.composeapp.generated.resources.tool_ui_screenshot_name
import kotlinx.coroutines.delay
import org.koin.java.KoinJavaComponent.inject
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-app automation Tools (Android-only). Three tiers:
 *
 * Read (needs the automation master switch + the enabled accessibility service):
 * [uiDumpTool], [uiScreenshotTool], [uiEventsTool].
 *
 * Write (additionally needs the write switch and passes [AutomationPolicy]):
 * [uiActTool], [appLaunchTool].
 *
 * Privileged (needs the Shizuku switch + a running, authorized Shizuku
 * service): [privilegedShellTool], [privilegedInputTool].
 *
 * Every failure surfaces as `{"success": false, "error": ...}` — including the
 * "enable the service" hint — so the agent can relay it instead of stalling.
 */
object UiAutomationTools {
    private val controller: AutomationController by inject(AutomationController::class.java)
    private val shizuku: ShizukuController by inject(ShizukuController::class.java)
    private val appSettings: AppSettings by inject(AppSettings::class.java)

    // Lazy: constructing the store eagerly would resolve the Koin-injected
    // appSettings during object init, which crashes tool-list consumers that run
    // without Koin (e.g. Paparazzi screenshot tests).
    private val appCardStore: AppCardStore by lazy { AppCardStore(appSettings) }

    /** Raw text beyond this goes through ui_act instead of a binder-sized argv. */
    private const val MAX_PRIVILEGED_TEXT_CHARS = 2_000

    private fun requireRead(): Result<Unit> = if (!appSettings.isAutomationEnabled()) {
        Result.failure(IllegalStateException("Cross-app automation is off. Ask the user to enable it in Settings → Agent → Automation."))
    } else {
        Result.success(Unit)
    }

    private fun requireWrite(): Result<Set<String>> {
        val readFailure = requireRead().exceptionOrNull()
        if (readFailure != null) return Result.failure(readFailure)
        if (!appSettings.isAutomationWriteEnabled()) {
            return Result.failure(
                IllegalStateException("Automation write actions are off. Ask the user to enable them in Settings → Agent → Automation."),
            )
        }
        return Result.success(appSettings.getAutomationAllowedApps())
    }

    private suspend fun <T> runTool(block: suspend () -> T): Any = try {
        val result = block()
        when (result) {
            // A map that already carries its own success flag (e.g. a nested
            // tool-style error) must keep it — forcing true would mask failures.
            is Map<*, *> -> if ("success" in result) result else result + ("success" to true)

            else -> mapOf("success" to true, "result" to result)
        }
    } catch (e: AutomationNotEnabledException) {
        mapOf("success" to false, "error" to (e.message ?: "accessibility service not enabled"))
    } catch (e: AutomationBlockedException) {
        mapOf("success" to false, "error" to (e.message ?: "blocked by automation policy"))
    } catch (e: AutomationStaleNodeException) {
        mapOf("success" to false, "error" to (e.message ?: "stale node"))
    } catch (e: AutomationFailedException) {
        mapOf("success" to false, "error" to (e.message ?: "action failed"))
    } catch (e: ShizukuUnavailableException) {
        mapOf("success" to false, "error" to (e.message ?: "shizuku unavailable"))
    } catch (e: ShizukuDeniedException) {
        mapOf("success" to false, "error" to (e.message ?: "shizuku denied"))
    } catch (e: IllegalStateException) {
        mapOf("success" to false, "error" to (e.message ?: "automation disabled"))
    } catch (e: Exception) {
        mapOf("success" to false, "error" to "automation error: ${e.message}")
    }

    val uiDumpTool = object : Tool {
        override val schema = ToolSchema(
            name = "ui_dump",
            description = "Read the current on-screen UI of any app as a structured element list. " +
                "Each element has an id, text, content description, resource id, center x/y, and flags " +
                "(clickable/editable/scrollable). Call this FIRST before any ui_act call, then act on the ids. " +
                "Ids stay usable across screen changes — actions re-locate the element by resource id/text — and expire after ~5 minutes. " +
                "If an action still reports a stale node, re-run ui_dump. " +
                "Use query to pre-filter by visible text (case-insensitive substring). Read-only and safe. " +
                "Served by the accessibility service when enabled, otherwise by Shizuku (same output).",
            parameters = mapOf(
                "query" to ParameterSchema("string", "Only return elements whose text/description matches (substring)", false),
                "max_nodes" to ParameterSchema("integer", "Cap on returned elements (default 200, max 500)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireRead().getOrThrow()
            val query = (args["query"] as? String)?.takeIf { it.isNotBlank() }
            val maxNodes = ((args["max_nodes"] as? Number)?.toInt() ?: 200).coerceIn(1, 500)
            val result = controller.dumpUi(maxNodes = maxNodes, query = query)
            val packageName = (result["package"] as? String)?.takeIf { it.isNotBlank() }
            val card = packageName?.let { appCardStore.forPackage(it) }
            when {
                card != null -> result + ("app_card" to card)

                packageName != null && appSettings.isToolEnabled(appCardTool.schema.name, true) ->
                    result + (
                        "app_card_hint" to
                            "No notes for $packageName yet — after a successful task here, save durable hints " +
                            "(search box id, popups to skip, working flow) with the app_card tool."
                        )

                else -> result
            }
        }
    }

    val uiScreenshotTool = object : Tool {
        override val schema = ToolSchema(
            name = "ui_screenshot",
            description = "Capture a system-wide screenshot of whatever app is on screen and save it to a file. " +
                "Returns the file path plus dimensions; the screenshot is shown to the user automatically in the chat — " +
                "call open_file only if the user explicitly asks to open it in another app. " +
                "Prefer ui_dump for acting: coordinates from the tree are exact, screenshots are for the user. " +
                "Needs Android 11+.",
            parameters = mapOf(
                "scale" to ParameterSchema("number", "Downscale factor 0.1–1.0 (default 0.5) to keep the file small", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireRead().getOrThrow()
            val scale = ((args["scale"] as? Number)?.toFloat() ?: 0.5f).coerceIn(0.1f, 1f)
            val result = controller.screenshot(scale)
            // Preview in chat so seeing the capture never requires leaving Kai.
            if (result["success"] == true) {
                (result["path"] as? String)?.let { path ->
                    val conversationId = currentConversationIdOrNull()
                    runCatching { ToolScreenshotPreview.publish(conversationId, File(path).readBytes()) }
                }
            }
            result
        }
    }

    val uiEventsTool = object : Tool {
        override val schema = ToolSchema(
            name = "ui_events",
            description = "Report the foreground app package and recent UI events (window changes, notifications). " +
                "Use it to confirm an app_launch landed somewhere, or to see what changed after an action. Read-only.",
            parameters = mapOf(
                "package" to ParameterSchema("string", "Only return events from this package", false),
                "limit" to ParameterSchema("integer", "Max events to return (default 20, max 200)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireRead().getOrThrow()
            val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 200)
            val events = controller.recentEvents(limit, (args["package"] as? String)?.takeIf { it.isNotBlank() })
            controller.foregroundApp() + events
        }
    }

    val uiActTool = object : Tool {
        override val schema = ToolSchema(
            name = "ui_act",
            description = "Act on the on-screen UI of the foreground app: tap an element, type into a field, " +
                "scroll, or press a system key. Always ui_dump first and use fresh element ids. " +
                "Input supports Unicode/CJK; when a field rejects set-text the tool pastes from the clipboard automatically. " +
                "Runs over the accessibility service when enabled, otherwise over Shizuku raw input. " +
                "Banking/payment, password-manager, authenticator, and installer apps are ALWAYS blocked — " +
                "refuse those with an explanation instead of retrying. For irreversible actions " +
                "(payments, deletions, sending messages) confirm with the user first and say what you are about to do.",
            parameters = mapOf(
                "action" to ParameterSchema("string", "One of: tap_node, tap_xy, tap_text, input, scroll, key, wait_for (required)", true),
                "node_id" to ParameterSchema("string", "Element id from ui_dump (tap_node/input/scroll)", false),
                "x" to ParameterSchema("integer", "Screen x for tap_xy", false),
                "y" to ParameterSchema("integer", "Screen y for tap_xy", false),
                "text" to ParameterSchema("string", "Visible text for tap_text, text to type for input, or text to wait for", false),
                "clear" to ParameterSchema("boolean", "Replace field contents instead of appending (input, default false)", false),
                "direction" to ParameterSchema("string", "up|down|left|right for scroll (default down)", false),
                "key" to ParameterSchema("string", "BACK|HOME|RECENTS|NOTIFICATIONS for key", false),
                "gone" to ParameterSchema("boolean", "wait_for: wait for the text to disappear instead of appear", false),
                "timeout_ms" to ParameterSchema("integer", "wait_for / expect_text timeout in ms (default 5000, max 30000)", false),
                "expect_text" to ParameterSchema("string", "After tap_node/tap_xy/tap_text/input, wait until this text appears — best-effort verification reported as `verified`", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            val allowedApps = requireWrite().getOrThrow()
            val action = (args["action"] as? String)?.lowercase().orEmpty()
            val timeoutMs = ((args["timeout_ms"] as? Number)?.toLong() ?: 5_000L).coerceIn(500L, 30_000L)
            val result: Map<String, Any?> = when (action) {
                "tap_node" -> {
                    val id = (args["node_id"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return@runTool mapOf("success" to false, "error" to "node_id is required for tap_node")
                    controller.tapNode(allowedApps, id)
                }

                "tap_xy" -> {
                    val x = (args["x"] as? Number)?.toInt() ?: args["x"]?.toString()?.toIntOrNull()
                    val y = (args["y"] as? Number)?.toInt() ?: args["y"]?.toString()?.toIntOrNull()
                    if (x == null || y == null) {
                        return@runTool mapOf("success" to false, "error" to "x and y are required for tap_xy")
                    }
                    controller.tapAt(allowedApps, x, y)
                }

                "tap_text" -> {
                    val text = (args["text"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: return@runTool mapOf("success" to false, "error" to "text is required for tap_text")
                    controller.tapText(allowedApps, text)
                }

                "input" -> {
                    val text = args["text"] as? String
                        ?: return@runTool mapOf("success" to false, "error" to "text is required for input")
                    controller.inputText(
                        allowedApps,
                        text,
                        (args["node_id"] as? String)?.takeIf { it.isNotBlank() },
                        (args["clear"] as? Boolean) ?: (args["clear"]?.toString()?.equals("true", ignoreCase = true) == true),
                    )
                }

                "scroll" -> {
                    val direction = ((args["direction"] as? String) ?: "down").lowercase()
                    if (direction != "up" && direction != "down" && direction != "left" && direction != "right") {
                        return@runTool mapOf("success" to false, "error" to "direction must be up|down|left|right")
                    }
                    controller.scroll(
                        allowedApps,
                        direction,
                        (args["node_id"] as? String)?.takeIf { it.isNotBlank() },
                    )
                }

                "key" -> {
                    val key = (args["key"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return@runTool mapOf("success" to false, "error" to "key is required for key")
                    controller.pressKey(allowedApps, key)
                }

                "wait_for" -> {
                    val text = (args["text"] as? String)?.takeIf { it.isNotEmpty() }
                        ?: return@runTool mapOf("success" to false, "error" to "text is required for wait_for")
                    val gone = (args["gone"] as? Boolean) ?: (args["gone"]?.toString()?.equals("true", ignoreCase = true) == true)
                    val ok = controller.waitForText(text, gone = gone, timeoutMs = timeoutMs)
                    if (ok) {
                        mapOf("success" to true, "text" to text, "gone" to gone)
                    } else {
                        mapOf(
                            "success" to false,
                            "error" to "timed out after ${timeoutMs}ms waiting for \"$text\"${if (gone) " to disappear" else " to appear"}" +
                                " — re-run ui_dump and check the screen",
                        )
                    }
                }

                else -> return@runTool mapOf("success" to false, "error" to "action must be tap_node|tap_xy|tap_text|input|scroll|key|wait_for")
            }

            // Best-effort post-action verification: wait until the expected text shows up.
            val expectText = (args["expect_text"] as? String)?.takeIf { it.isNotBlank() }
            val verifiable = action == "tap_node" || action == "tap_xy" || action == "tap_text" || action == "input"
            if (expectText != null && verifiable && result["success"] == true) {
                val verified = controller.waitForText(expectText, gone = false, timeoutMs = timeoutMs)
                return@runTool if (verified) {
                    result + ("verified" to true)
                } else {
                    result + mapOf(
                        "verified" to false,
                        "verification" to "expected text \"$expectText\" did not appear within ${timeoutMs}ms — re-run ui_dump and check the screen",
                    )
                }
            }
            result
        }
    }

    val appCardTool = object : Tool {
        override val schema = ToolSchema(
            name = "app_card",
            description = "Read or store a per-app operating note (\"app card\"): where the search box is, which " +
                "onboarding or update prompts to dismiss, which flow worked. ui_dump automatically includes the card " +
                "for the foreground app, so save notes after a successful task instead of re-discovering the app next time. " +
                "Actions: set (package + note), remove (package), list.",
            parameters = mapOf(
                "action" to ParameterSchema("string", "set | remove | list (required)", true),
                "package" to ParameterSchema("string", "App package name, e.g. com.xingin.xhs", false),
                "note" to ParameterSchema("string", "Operating hints to store (set; max ${AppCardStore.MAX_NOTE_CHARS} chars)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireRead().getOrThrow()
            when ((args["action"] as? String)?.lowercase()) {
                "list" -> mapOf(
                    "success" to true,
                    "cards" to appCardStore.get().map { mapOf("package" to it.packageName, "note" to it.note) },
                )

                "set" -> {
                    val packageName = (args["package"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return@runTool mapOf("success" to false, "error" to "package is required for set")
                    val note = (args["note"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return@runTool mapOf("success" to false, "error" to "note is required for set")
                    if (appCardStore.set(packageName, note)) {
                        mapOf("success" to true, "package" to packageName.trim(), "stored" to true)
                    } else {
                        mapOf("success" to false, "error" to "could not store the card (store full or blank input)")
                    }
                }

                "remove" -> {
                    val packageName = (args["package"] as? String)?.takeIf { it.isNotBlank() }
                        ?: return@runTool mapOf("success" to false, "error" to "package is required for remove")
                    appCardStore.remove(packageName)
                    mapOf("success" to true, "package" to packageName.trim(), "removed" to true)
                }

                else -> mapOf("success" to false, "error" to "action must be set|remove|list")
            }
        }
    }

    val appCardToolInfo = ToolInfo(
        id = "app_card",
        name = "App Card",
        description = "Store per-app operating notes that ui_dump surfaces",
        // On by default: it only writes local notes, and the feature is useless
        // unless the agent can actually call it (the availability gate must agree
        // with this default).
        isEnabled = true,
        userToggleable = true,
    )

    val appLaunchTool = object : Tool {
        override val schema = ToolSchema(
            name = "app_launch",
            description = "Open another app by package name (optionally a specific activity or a deep-link URI). " +
                "Follow with ui_dump to see where you landed. Sensitive apps (banking/payment, password " +
                "managers, installers) are blocked; apps outside the user's allowlist are refused when one is set. " +
                "Pass package=\"self\" (or \"kai\") to bring Kai back to the front — do this as the final step after " +
                "finishing work in another app, so the user sees your summary instead of the foreign app. " +
                "If a launch reports the package missing, do NOT silently fall back to a web search: Android " +
                "package visibility can limit this check, so verify with privileged_shell " +
                "(`pm list packages | grep <name>`) and report the outcome.",
            parameters = mapOf(
                "package" to ParameterSchema("string", "Target app package name (e.g. com.example.app), or \"self\"/\"kai\" to return to Kai (required)", true),
                "activity" to ParameterSchema("string", "Fully-qualified activity class to open directly", false),
                "uri" to ParameterSchema("string", "Deep-link URI to open instead of the launcher entry", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            val allowedApps = requireWrite().getOrThrow()
            val pkg = args["package"] as? String
                ?: return@runTool mapOf("success" to false, "error" to "package is required")
            controller.launchApp(
                allowedApps,
                pkg,
                (args["activity"] as? String)?.takeIf { it.isNotBlank() },
                (args["uri"] as? String)?.takeIf { it.isNotBlank() },
            )
        }
    }

    val privilegedShellTool = object : Tool {
        override val timeout = 60.seconds

        override val schema = ToolSchema(
            name = "privileged_shell",
            description = "Run a shell command with ADB/shell privileges via Shizuku (uid 2000, or root when " +
                "Shizuku runs rooted) — OUTSIDE the Linux sandbox, directly on Android. This IS the adb/Shizuku path: " +
                "never look for a `shizuku`/`adb` binary or run `find /` inside execute_shell_command, those fail by design. " +
                "Has pm, am, cmd, settings, dumpsys, input, wm, appops, screencap. Use it for what accessibility cannot do: " +
                "hardware keyevents, querying package/system state, toggling system settings. " +
                "Still CANNOT read other apps' /data/data unless Shizuku runs rooted — report that blocker instead of retrying. " +
                "NEVER install/uninstall apps, grant permissions, wipe data, or touch other apps' private " +
                "files without explicit user approval for that exact command. Prefer ui_dump/ui_act for UI work.",
            parameters = mapOf(
                "command" to ParameterSchema("string", "argv as a JSON-ish string list is not supported — pass the full shell line; it runs under sh -c (required)", true),
                "timeout_ms" to ParameterSchema("integer", "Timeout in ms (default 30000, max 600000)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireShizuku().getOrThrow()
            val command = (args["command"] as? String)?.takeIf { it.isNotBlank() }
                ?: return@runTool mapOf("success" to false, "error" to "command is required")
            val timeoutMs = ((args["timeout_ms"] as? Number)?.toLong() ?: 30_000L).coerceIn(1_000L, 600_000L)
            val result = shizuku.exec(arrayOf(systemBin("sh"), "-c", command), timeoutMs)
            mapOf("exit_code" to result.exitCode, "stdout" to result.stdout, "stderr" to result.stderr)
        }
    }

    val privilegedInputTool = object : Tool {
        override val schema = ToolSchema(
            name = "privileged_input",
            description = "Send raw input events through Shizuku (the Android `input` command): hardware " +
                "keyevents (ENTER, DPAD_*, VOLUME_*, CAMERA…), taps and swipes by coordinate, and raw text. " +
                "This is the fallback when ui_act cannot do it — e.g. ENTER to submit a form. " +
                "Text supports Unicode: ASCII goes through `input text`, non-ASCII/CJK is written to the " +
                "clipboard and pasted with KEYCODE_PASTE (focus the field first). " +
                "Same app policy as ui_act applies to the foreground app.",
            parameters = mapOf(
                "action" to ParameterSchema("string", "One of: key, tap, swipe, text (required)", true),
                "key" to ParameterSchema("string", "Keyevent name/number for key, e.g. ENTER, DPAD_DOWN, 3", false),
                "x" to ParameterSchema("integer", "x for tap/swipe", false),
                "y" to ParameterSchema("integer", "y for tap/swipe", false),
                "x2" to ParameterSchema("integer", "end x for swipe", false),
                "y2" to ParameterSchema("integer", "end y for swipe", false),
                "duration_ms" to ParameterSchema("integer", "swipe duration in ms (default 300)", false),
                "text" to ParameterSchema("string", "text for text (Unicode supported; non-ASCII is delivered via the clipboard + KEYCODE_PASTE, so focus the field first)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any = runTool {
            requireShizuku().getOrThrow()
            val allowedApps = appSettings.getAutomationAllowedApps()
            val foreground = controller.foregroundApp()["package"] as? String
            when (val verdict = AutomationPolicy.checkInteract(foreground, allowedApps)) {
                is AutomationPolicy.Verdict.Allow -> Unit
                is AutomationPolicy.Verdict.Deny -> throw AutomationBlockedException(verdict.reason)
            }
            val argv = when ((args["action"] as? String)?.lowercase()) {
                "key" -> {
                    val key = args["key"] as? String
                        ?: return@runTool mapOf("success" to false, "error" to "key is required for key")
                    arrayOf(systemBin("input"), "keyevent", key)
                }

                "tap" -> {
                    val x = (args["x"] as? Number)?.toInt() ?: args["x"]?.toString()?.toIntOrNull()
                    val y = (args["y"] as? Number)?.toInt() ?: args["y"]?.toString()?.toIntOrNull()
                    if (x == null || y == null) {
                        return@runTool mapOf("success" to false, "error" to "x and y are required for tap")
                    }
                    arrayOf(systemBin("input"), "tap", x.toString(), y.toString())
                }

                "swipe" -> {
                    val x = (args["x"] as? Number)?.toInt() ?: args["x"]?.toString()?.toIntOrNull()
                    val y = (args["y"] as? Number)?.toInt() ?: args["y"]?.toString()?.toIntOrNull()
                    val x2 = (args["x2"] as? Number)?.toInt() ?: args["x2"]?.toString()?.toIntOrNull()
                    val y2 = (args["y2"] as? Number)?.toInt() ?: args["y2"]?.toString()?.toIntOrNull()
                    if (x == null || y == null || x2 == null || y2 == null) {
                        return@runTool mapOf("success" to false, "error" to "x, y, x2, y2 are required for swipe")
                    }
                    val duration = ((args["duration_ms"] as? Number)?.toInt() ?: args["duration_ms"]?.toString()?.toIntOrNull() ?: 300)
                        .coerceIn(50, 10_000).toString()
                    arrayOf(systemBin("input"), "swipe", x.toString(), y.toString(), x2.toString(), y2.toString(), duration)
                }

                "text" -> {
                    val text = args["text"] as? String
                        ?: return@runTool mapOf("success" to false, "error" to "text is required for text")
                    if (text.isEmpty()) {
                        return@runTool mapOf("success" to false, "error" to "text is empty")
                    }
                    if (text.length > MAX_PRIVILEGED_TEXT_CHARS) {
                        return@runTool mapOf("success" to false, "error" to "text is ${text.length} chars (max $MAX_PRIVILEGED_TEXT_CHARS) — keep privileged_input short and use ui_act for long text.")
                    }
                    if (text.any { it.code > 127 }) {
                        // `input text` mangles CJK: write the clipboard (Kai process)
                        // and paste it into the focused field.
                        controller.setClipboard(text)
                        delay(150)
                        arrayOf(systemBin("input"), "keyevent", KEYCODE_PASTE)
                    } else {
                        // The platform decodes %s as space and leaves other % runs
                        // alone; argv goes directly to exec (no shell), so quotes
                        // must be kept verbatim instead of stripped.
                        val encoded = text.replace(" ", "%s")
                        arrayOf(systemBin("input"), "text", encoded)
                    }
                }

                else -> return@runTool mapOf("success" to false, "error" to "action must be key|tap|swipe|text")
            }
            val result = shizuku.exec(argv)
            if (result.exitCode != 0) {
                mapOf("success" to false, "error" to result.stderr.ifBlank { "input command failed" })
            } else {
                mapOf("action" to (args["action"] as? String).orEmpty())
            }
        }
    }

    private fun requireShizuku(): Result<Unit> {
        val readFailure = requireRead().exceptionOrNull()
        if (readFailure != null) return Result.failure(readFailure)
        if (!appSettings.isAutomationWriteEnabled()) {
            return Result.failure(
                IllegalStateException("Automation write actions are off. Ask the user to enable them in Settings → Agent → Automation."),
            )
        }
        if (!appSettings.isShizukuEnabled()) {
            return Result.failure(
                IllegalStateException("Shizuku control is off. Ask the user to enable it in Settings → Agent → Automation."),
            )
        }
        return Result.success(Unit)
    }

    val uiDumpToolInfo = ToolInfo(
        id = "ui_dump",
        name = "Read App UI",
        description = "Read the on-screen UI of any app",
        nameRes = Res.string.tool_ui_dump_name,
        descriptionRes = Res.string.tool_ui_dump_description,
        isEnabled = true,
    )

    val uiScreenshotToolInfo = ToolInfo(
        id = "ui_screenshot",
        name = "Screenshot App",
        description = "Capture what's on screen to a file",
        nameRes = Res.string.tool_ui_screenshot_name,
        descriptionRes = Res.string.tool_ui_screenshot_description,
        isEnabled = true,
    )

    val uiEventsToolInfo = ToolInfo(
        id = "ui_events",
        name = "Watch UI Events",
        description = "Foreground app and recent UI events",
        nameRes = Res.string.tool_ui_events_name,
        descriptionRes = Res.string.tool_ui_events_description,
        isEnabled = true,
    )

    val uiActToolInfo = ToolInfo(
        id = "ui_act",
        name = "Control App UI",
        description = "Tap, type, scroll, or press keys in apps",
        nameRes = Res.string.tool_ui_act_name,
        descriptionRes = Res.string.tool_ui_act_description,
        isEnabled = true,
    )

    val appLaunchToolInfo = ToolInfo(
        id = "app_launch",
        name = "Launch Apps",
        description = "Open other apps by package name",
        nameRes = Res.string.tool_app_launch_name,
        descriptionRes = Res.string.tool_app_launch_description,
        isEnabled = true,
    )

    val privilegedShellToolInfo = ToolInfo(
        id = "privileged_shell",
        name = "Privileged Shell",
        description = "ADB-level shell via Shizuku",
        nameRes = Res.string.tool_privileged_shell_name,
        descriptionRes = Res.string.tool_privileged_shell_description,
        isEnabled = true,
    )

    val privilegedInputToolInfo = ToolInfo(
        id = "privileged_input",
        name = "Privileged Input",
        description = "Raw key/touch events via Shizuku",
        nameRes = Res.string.tool_privileged_input_name,
        descriptionRes = Res.string.tool_privileged_input_description,
        isEnabled = true,
    )
}
