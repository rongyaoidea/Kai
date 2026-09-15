package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import org.koin.java.KoinJavaComponent.inject

object SandboxStatusTool {
    const val ID = "get_sandbox_status"

    val toolInfo = ToolInfo(
        id = ID,
        name = "Get Sandbox Status",
        description = "Check whether the Linux sandbox and native shell are available and which shell tier is active",
        isEnabled = true,
        userToggleable = true,
    )

    val tool: Tool = object : Tool {
        override val schema = ToolSchema(
            name = ID,
            description = "Check sandbox availability without running a command. Returns whether the Linux sandbox (proot) is installed and Ready, whether the host native shell (mksh/toybox) is available, which tier execute_shell_command will use, and the home directory. Use this to decide whether to use apt/python/ssh (proot only) or stick to toybox applets and WebView tools (native/no-sandbox). No parameters.",
            parameters = emptyMap(),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
            val appSettings: AppSettings by inject(AppSettings::class.java)
            val state = sandboxManager.state.value
            val isReady = state is SandboxState.Ready
            val nativeAvailable = try {
                sandboxManager.isNativeShellAvailable()
            } catch (_: Throwable) {
                false
            }
            val sandboxEnabled = try {
                appSettings.isSandboxEnabled()
            } catch (_: Throwable) {
                true
            }

            val (stateName, stateDetail) = when (state) {
                is SandboxState.NotInstalled -> "NotInstalled" to null
                is SandboxState.Downloading -> "Downloading" to "progress=${state.progress}"
                is SandboxState.Extracting -> "Extracting" to null
                is SandboxState.Installing -> "Installing" to state.label.toString()
                is SandboxState.Ready -> "Ready" to null
                is SandboxState.Error -> "Error" to state.label.toString()
            }

            val tier = when {
                isReady -> "proot"
                nativeAvailable -> "native"
                else -> "none"
            }

            val canExecuteShell = isReady || nativeAvailable
            val home = if (isReady) {
                "/root"
            } else {
                try {
                    sandboxManager.nativeHome.absolutePath
                } catch (_: Throwable) {
                    ""
                }
            }
            val distroName = try {
                sandboxManager.distro.displayName
            } catch (_: Throwable) {
                null
            }
            val distroId = try {
                sandboxManager.distro.id
            } catch (_: Throwable) {
                null
            }
            val webViewAvailable = try {
                WebViewPageRenderer.isAvailable()
            } catch (_: Throwable) {
                false
            }

            // Hint for the agent: what it can actually do right now
            val hint = when {
                isReady -> "Full proot Linux available: apt/apk, python, ssh, read_file/write_file, skills. Native tier also available."
                nativeAvailable -> "No proot sandbox — execute_shell_command runs host mksh/toybox only (ls, cat, grep, sed, awk, find, tar). No apt/python/ssh. Use WebView (browse_page/web_act) and fetch_url for web."
                else -> "No shell at all: /system/bin/sh missing. Install Linux sandbox in Settings > Tools."
            }

            return mapOf(
                "success" to true,
                "sandbox_enabled" to sandboxEnabled,
                "state" to stateName,
                "state_detail" to stateDetail,
                "is_ready" to isReady,
                "native_available" to nativeAvailable,
                "can_execute_shell" to canExecuteShell,
                "active_tier" to tier,
                "home" to home,
                "native_home" to try {
                    sandboxManager.nativeHome.absolutePath
                } catch (_: Throwable) {
                    ""
                },
                "distro_name" to distroName,
                "distro_id" to distroId,
                "webview_available" to webViewAvailable,
                "hint" to hint,
            )
        }
    }
}
