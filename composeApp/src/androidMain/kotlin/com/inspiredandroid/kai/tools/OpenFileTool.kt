package com.inspiredandroid.kai.tools

import android.content.Context
import com.inspiredandroid.kai.data.HtmlPreview
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.isHtmlPreviewSupported
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.openFileWithIntent
import com.inspiredandroid.kai.sandbox.resolveSandboxFile
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_open_file_description
import kai.composeapp.generated.resources.tool_open_file_name
import org.koin.java.KoinJavaComponent.inject

private const val OPEN_FILE_DESCRIPTION = """Open a file from the agent's workspace in the user's default Android app — browser for HTML, image viewer for PNG/JPG, PDF viewer for PDF, markdown viewer for .md, etc. This is how you show finished work to the user.

Path is relative to the workspace home: with the Linux sandbox that is /root, without it the native workspace the shell starts in. What the shell tool calls /root/page.html, this tool takes as path="page.html". Browser screenshots saved by browse_page resolve here too.

For an HTML report you wrote, prefer preview=true: it shows the page in Kai's in-app preview (a card appears in chat; tap opens it full-screen) instead of switching the user to another app. preview is ignored for non-HTML files and on platforms without an in-app preview.

Write self-contained files — for HTML, inline all CSS and JavaScript in the same file (no external <link rel="stylesheet"> or <script src=...>), since the file is opened in isolation."""

object OpenFileTool : Tool {
    private val context: Context by inject(Context::class.java)
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    override val schema = ToolSchema(
        name = "open_file",
        description = OPEN_FILE_DESCRIPTION,
        parameters = mapOf(
            "path" to ParameterSchema(
                "string",
                "Path relative to the workspace home, e.g. site/index.html or notes.md",
                true,
            ),
            "preview" to ParameterSchema(
                "boolean",
                "true to show an HTML file in Kai's in-app preview instead of opening it externally",
                false,
            ),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val rawPath = (args["path"] as? String)?.trim()
            ?: return mapOf("success" to false, "error" to "path is required")
        // The shell calls it /root/page.html — accept that form too instead of
        // failing with a path error when the agent copies the shell path verbatim.
        // Other absolute paths stay invalid.
        if (rawPath == "/root" || rawPath == "/root/") {
            return mapOf("success" to false, "error" to "Invalid path: must be relative to the workspace home, no leading / or .. segments")
        }
        val path = (if (rawPath.startsWith("/root/")) rawPath.removePrefix("/root/") else rawPath)
            .takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "error" to "Invalid path: must be relative to the workspace home, no leading / or .. segments")

        // Tier order: the sandbox home when Ready, then the native workspace,
        // then browser screenshots (app cache, outside both, so rendering never
        // needs a sandbox install). First hit wins.
        val file = resolveSandboxFile(sandboxManager.homePath, path)
            ?: resolveSandboxFile(sandboxManager.nativeHome.absolutePath, path)
            ?: resolveSandboxFile(BrowsePageTool.browserDir().absolutePath, path)
            ?: return mapOf("success" to false, "error" to "Invalid path: must be relative to the workspace home, no leading / or .. segments")

        if (!file.exists()) {
            return mapOf("success" to false, "error" to "File not found: $path")
        }
        if (!file.isFile) {
            return mapOf("success" to false, "error" to "Not a file: $path")
        }

        // In-app HTML preview: publish the document for the chat card. Any
        // failure (unsupported platform, non-HTML, oversized, unreadable) falls
        // through to the external-open path below, so the file is never left
        // unshown.
        val wantsPreview = (args["preview"] as? Boolean)
            ?: (args["preview"]?.toString()?.equals("true", ignoreCase = true) == true)
        val isHtml = path.endsWith(".html", ignoreCase = true) || path.endsWith(".htm", ignoreCase = true)
        // A de-Googled ROM may have no WebView at all; fall back to the
        // external-open path instead of publishing a preview that can't render.
        if (wantsPreview && isHtmlPreviewSupported && isHtml && WebViewPageRenderer.isAvailable()) {
            val html = runCatching { file.readText() }.getOrNull()
            if (html != null && HtmlPreview.publish(currentConversationIdOrNull(), path, html)) {
                return mapOf(
                    "success" to true,
                    "path" to path,
                    "previewed" to true,
                    "hint" to "The preview card is visible in chat; the user taps it to view full-screen. Do not call open_file again for this file.",
                )
            }
        }

        val result = openFileWithIntent(context, file)
        return if (result.success) {
            mapOf(
                "success" to true,
                "path" to path,
                "mime_type" to result.mimeType,
                "content_uri" to (result.contentUri ?: ""),
            )
        } else {
            mapOf("success" to false, "error" to (result.error ?: "Failed to open file"))
        }
    }

    val toolInfo = ToolInfo(
        id = "open_file",
        name = "Open File",
        description = "Open workspace files in your default Android app",
        nameRes = Res.string.tool_open_file_name,
        descriptionRes = Res.string.tool_open_file_description,
    )
}
