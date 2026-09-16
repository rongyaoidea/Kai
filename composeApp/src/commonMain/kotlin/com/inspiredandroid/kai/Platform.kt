package com.inspiredandroid.kai

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.russhwolf.settings.Settings
import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import kotlin.coroutines.CoroutineContext

expect fun httpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

expect fun createSecureSettings(): Settings

expect fun createLegacySettings(): Settings?

expect fun getBackgroundDispatcher(): CoroutineContext

expect fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile?

expect val BackIcon: ImageVector

sealed class Platform(val displayName: String) {
    sealed class Mobile(displayName: String) : Platform(displayName) {
        data object Android : Mobile("Android")
        data object Ios : Mobile("iOS")
    }

    sealed class Desktop(displayName: String) : Platform(displayName) {
        data object Mac : Desktop("macOS")
        data object Windows : Desktop("Windows")
        data object Linux : Desktop("Linux")
    }

    data object Web : Platform("Web")
}

expect val currentPlatform: Platform

expect val defaultUiScale: Float

expect fun getAppFilesDirectory(): String

expect fun getAvailableTools(): List<Tool>

/**
 * Returns all raw tool definitions available on this platform.
 * The returned tools have no isEnabled state set - that's handled by RemoteDataRepository.
 * Unlike getAvailableTools(), this returns all tools regardless of enabled state.
 */
expect fun getPlatformToolDefinitions(): List<ToolInfo>

expect val isEmailSupported: Boolean

/**
 * True only on the FOSS Android build. Gated on `READ_SMS` being declared in the
 * merged manifest — the Play Store flavor doesn't declare it, so this returns
 * false there, and the SMS feature is invisible in that build.
 */
expect val isSmsSupported: Boolean

/**
 * True only on the FOSS Android build. Gated on `KaiNotificationListenerService`
 * being declared in the merged manifest — the Play Store flavor doesn't declare
 * it, so this returns false there, and the notification-reading feature is
 * invisible in that build.
 */
expect val isNotificationsSupported: Boolean

/**
 * True only on Android, where MCP App interactive UIs render in a system
 * WebView host. Other platforms declare the capability off and hide the
 * entry points.
 */
expect val isMcpAppUiSupported: Boolean

/**
 * True only on Android, where an HTML report written to the workspace can be
 * previewed in-app (full-screen WebView) instead of being handed to another
 * app. When false, `open_file` with a preview request falls back to opening
 * the file externally.
 */
expect val isHtmlPreviewSupported: Boolean

/**
 * Cross-app automation (AccessibilityService + Shizuku) is Android-only.
 * Everywhere else this is false and the automation settings section stays hidden.
 */
expect val isAutomationSupported: Boolean

/** True while the Kai accessibility service is bound. Android-only; false elsewhere. */
expect fun isAutomationServiceEnabled(): Boolean

/**
 * Shizuku service state for settings display: one of NOT_INSTALLED, NOT_RUNNING,
 * NEED_PERMISSION, READY, DISABLED. Android-only; DISABLED elsewhere.
 */
expect fun getShizukuStatus(): String

/** Opens the system Accessibility settings screen. No-op off Android. */
expect fun openAccessibilitySettings()

/**
 * Launches an installed app by package name (used for the Shizuku manager
 * app). Returns false off Android or when the package is not installed.
 */
expect fun openAppByPackage(packageName: String): Boolean

/**
 * Brings Kai back to the foreground after a run that drove other apps through
 * automation, so the user sees the result instead of the foreign app. Best-effort
 * and never throws; no-op off Android (or when the platform can't start activities).
 */
expect suspend fun returnToKaiAfterAutomation()

/**
 * Requests Shizuku authorization, suspending until the user answers the
 * system dialog. True when Kai is authorized afterwards. Always false off
 * Android. Never throws — denials and errors all read as false.
 */
expect suspend fun requestShizukuAuthorization(): Boolean

/**
 * Shizuku server identity for settings display (e.g. "v13"). Empty when the
 * server is unreachable or off Android.
 */
expect fun getShizukuDetails(): String

expect val isSplinterlandsSupported: Boolean

expect suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray

expect fun openUrl(url: String): Boolean

@androidx.compose.runtime.Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

expect fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap?

expect suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String)

/**
 * Fires a background push notification for a heartbeat that produced a non-trivial
 * response. Android additionally wires a tap-to-open-heartbeat deep link via its
 * PendingIntent; iOS/desktop just surface the message in the OS notification center
 * without deep-linking back to the conversation. No-op on web.
 */
expect fun sendHeartbeatNotification(title: String, body: String)
