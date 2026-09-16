package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.TextFileResult
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import com.inspiredandroid.kai.sandbox.readFileAsText
import com.inspiredandroid.kai.sandbox.resolveSandboxFile
import org.koin.java.KoinJavaComponent.inject
import java.io.IOException

private const val READ_MAX_BYTES = 256 * 1024
private const val WRITE_MAX_BYTES = 1024 * 1024

/**
 * Direct file reads and writes for the agent's workspace, Android-only.
 *
 * The shell tool can already move bytes with cat/heredoc/sed, but precise work
 * through a shell mangles quoting and truncates silently. These two tools are
 * the exact path: paginated reads that refuse binaries loudly, and writes with
 * create/overwrite/append modes that never touch anything outside the home.
 *
 * Tiered like the shell: with the Linux sandbox Ready the home is `/root`
 * inside the rootfs; without it the home is the app-private native workspace
 * the native shell starts in. Relative paths mean the same file for the shell
 * and the file tools in either tier.
 */
object SandboxFileTools {
    private val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)

    private val isProotReady: Boolean get() = sandboxManager.state.value is SandboxState.Ready

    /** Home directory of the active tier. */
    private fun activeHome(): String = if (isProotReady) sandboxManager.homePath else sandboxManager.nativeHome.absolutePath

    val readFileTool = object : Tool {
        override val schema = ToolSchema(
            name = "read_file",
            description = "Read a text file from the workspace and return its lines with pagination. " +
                "Prefer this over cat/head/sed for reading: it pages large files, caps output, and refuses " +
                "binaries with a clear error instead of dumping garbage. " +
                "Path is relative to the workspace home (with the Linux sandbox that is /root; without it, " +
                "the native workspace the shell starts in). " +
                "For binary or media files use open_file instead.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Path relative to the workspace home", true),
                "offset" to ParameterSchema("integer", "First line to return, 0-based (default 0)", false),
                "limit" to ParameterSchema("integer", "Max lines to return (default 200, max 1000)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val file = resolveSandboxFile(activeHome(), path)
                ?: return mapOf("success" to false, "error" to "Invalid path: must be relative to the workspace home, no leading / or .. segments")
            if (!file.exists()) {
                return mapOf("success" to false, "error" to "File not found: $path")
            }
            if (!file.isFile) {
                return mapOf("success" to false, "error" to "Not a file: $path")
            }
            val offset = ((args["offset"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            val limit = ((args["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 1000)
            return when (val result = readFileAsText(file, READ_MAX_BYTES, force = false)) {
                is TextFileResult.Text -> {
                    val lines = result.content.lines()
                    val slice = lines.drop(offset).take(limit)
                    mapOf(
                        "success" to true,
                        "path" to path,
                        "offset" to offset,
                        "total_lines" to lines.size,
                        "truncated" to (offset + slice.size < lines.size),
                        "lines" to slice,
                    )
                }

                is TextFileResult.TooLarge -> mapOf(
                    "success" to false,
                    "error" to "File is ${result.sizeBytes} bytes (over the $READ_MAX_BYTES-byte read cap). " +
                        "Page it through the shell (head/tail/sed) or view it with open_file.",
                )

                TextFileResult.Binary -> mapOf(
                    "success" to false,
                    "error" to "Not a text file. Use open_file to view it.",
                )

                TextFileResult.Unreadable -> mapOf("success" to false, "error" to "Could not read file: $path")
            }
        }
    }

    val writeFileTool = object : Tool {
        override val schema = ToolSchema(
            name = "write_file",
            description = "Write exact text to a file in the workspace. " +
                "Prefer this over heredoc/echo/sed for precise writes: no shell-quoting mangling, " +
                "no accidental truncation. Path is relative to the workspace home (with the Linux sandbox " +
                "that is /root; without it, the native workspace the shell starts in); parent directories " +
                "are created. Modes: overwrite (default), append, create (fails if the file already exists). " +
                "Read the file first with read_file when editing existing content. " +
                "Do not write under skills/ by hand — use install_skill so frontmatter validation, " +
                "size caps, and the skill cache stay consistent.",
            parameters = mapOf(
                "path" to ParameterSchema("string", "Path relative to the workspace home", true),
                "content" to ParameterSchema("string", "Exact text to write", true),
                "mode" to ParameterSchema("string", "One of: overwrite (default), append, create", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val path = (args["path"] as? String)?.trim()
                ?: return mapOf("success" to false, "error" to "path is required")
            val content = args["content"]?.toString()
                ?: return mapOf("success" to false, "error" to "content is required")
            if (content.toByteArray().size > WRITE_MAX_BYTES) {
                return mapOf("success" to false, "error" to "Content exceeds the $WRITE_MAX_BYTES-byte write cap")
            }
            val file = resolveSandboxFile(activeHome(), path)
                ?: return mapOf("success" to false, "error" to "Invalid path: must be relative to the workspace home, no leading / or .. segments")
            val mode = ((args["mode"] as? String)?.lowercase() ?: "overwrite")
            if (mode != "overwrite" && mode != "append" && mode != "create") {
                return mapOf("success" to false, "error" to "mode must be overwrite|append|create")
            }
            if (mode == "create" && file.exists()) {
                return mapOf("success" to false, "error" to "File already exists: $path")
            }
            return try {
                file.parentFile?.mkdirs()
                if (mode == "append" && file.exists()) {
                    file.appendText(content)
                } else {
                    file.writeText(content)
                }
                mapOf(
                    "success" to true,
                    "path" to path,
                    "mode" to mode,
                    "bytes_written" to content.toByteArray().size,
                )
            } catch (e: IOException) {
                mapOf("success" to false, "error" to "Failed to write file: ${e.message}")
            }
        }
    }

    val readFileToolInfo = ToolInfo(
        id = "read_file",
        name = "Read File",
        description = "Read a text file from the workspace with pagination",
        nameRes = null,
        descriptionRes = null,
        isEnabled = false,
        // Availability follows the workspace tier (sandbox or native), same as
        // the shell tool, so a per-tool switch would read back a setting
        // nothing consults.
        userToggleable = false,
    )

    val writeFileToolInfo = ToolInfo(
        id = "write_file",
        name = "Write File",
        description = "Write exact text to a file in the workspace",
        nameRes = null,
        descriptionRes = null,
        isEnabled = false,
        userToggleable = false,
    )
}
