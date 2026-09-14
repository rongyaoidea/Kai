package com.inspiredandroid.kai

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.inspiredandroid.kai.automation.AutomationController
import com.inspiredandroid.kai.automation.ShizukuController
import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.ConversationStorage
import com.inspiredandroid.kai.data.EmailStore
import com.inspiredandroid.kai.data.MemoryStore
import com.inspiredandroid.kai.data.NotificationStore
import com.inspiredandroid.kai.data.SmsDraftStore
import com.inspiredandroid.kai.data.SmsStore
import com.inspiredandroid.kai.data.TaskStore
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.notifications.NotificationReader
import com.inspiredandroid.kai.notifications.declaresNotificationListener
import com.inspiredandroid.kai.sandbox.LinuxSandboxManager
import com.inspiredandroid.kai.sandbox.SandboxState
import com.inspiredandroid.kai.sms.SmsReader
import com.inspiredandroid.kai.sms.SmsSender
import com.inspiredandroid.kai.sms.declaresReadSms
import com.inspiredandroid.kai.tools.AppPermission
import com.inspiredandroid.kai.tools.BrowsePageTool
import com.inspiredandroid.kai.tools.CalendarRepository
import com.inspiredandroid.kai.tools.CommonTools
import com.inspiredandroid.kai.tools.ConversationTools
import com.inspiredandroid.kai.tools.CreateCalendarEventTool
import com.inspiredandroid.kai.tools.NotificationHelper
import com.inspiredandroid.kai.tools.NotificationTools
import com.inspiredandroid.kai.tools.OpenFileTool
import com.inspiredandroid.kai.tools.PermissionController
import com.inspiredandroid.kai.tools.ProcessManagerTool
import com.inspiredandroid.kai.tools.SandboxFileTools
import com.inspiredandroid.kai.tools.SendNotificationTool
import com.inspiredandroid.kai.tools.SetAlarmTool
import com.inspiredandroid.kai.tools.ShellCommandTool
import com.inspiredandroid.kai.tools.SkillAdminTools
import com.inspiredandroid.kai.tools.SmsTools
import com.inspiredandroid.kai.tools.SshConfigureHostTool
import com.inspiredandroid.kai.tools.UiAutomationTools
import com.inspiredandroid.kai.tools.WebActTools
import com.inspiredandroid.kai.tools.WebViewPageRenderer
import com.inspiredandroid.kai.tools.buildAgentToolSet
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import dev.spght.encryptedprefs.EncryptedSharedPreferences
import dev.spght.encryptedprefs.MasterKey
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.write
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject
import kotlin.coroutines.CoroutineContext

actual fun httpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    config(this)
}

actual fun getBackgroundDispatcher(): CoroutineContext = Dispatchers.IO

actual fun onDragAndDropEventDropped(event: DragAndDropEvent): PlatformFile? = null

actual val BackIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack

actual val currentPlatform: Platform = Platform.Mobile.Android

actual val defaultUiScale: Float = 1.0f

actual val isEmailSupported: Boolean = true

// Evaluated lazily because we need the Koin-injected Context. Whether READ_SMS
// is declared in the merged manifest is a build-time property (foss flavor adds
// it, playStore does not), so caching the first result is safe for the process
// lifetime. The try/catch guards screenshot / unit-test environments that may
// call `getPlatformToolDefinitions()` before Koin has been started.
actual val isSmsSupported: Boolean by lazy {
    try {
        val context: Context by inject(Context::class.java)
        context.declaresReadSms()
    } catch (_: Throwable) {
        false
    }
}

// Same lazy pattern as `isSmsSupported`: probe the merged manifest for the listener
// service. Foss flavor declares it, playStore does not.
actual val isMcpAppUiSupported: Boolean = true

actual val isNotificationsSupported: Boolean by lazy {
    try {
        val context: Context by inject(Context::class.java)
        context.declaresNotificationListener()
    } catch (_: Throwable) {
        false
    }
}

// Cross-app automation lives in the main manifest (no Play-Store flavor concerns:
// maximum-permission build). The getters below are safe before Koin starts — the
// screenshot/unit-test path falls back to false/DISABLED instead of crashing.
actual val isAutomationSupported: Boolean = true

actual fun isAutomationServiceEnabled(): Boolean = try {
    val automation: AutomationController by inject(AutomationController::class.java)
    automation.isServiceEnabled()
} catch (_: Throwable) {
    false
}

actual fun getShizukuStatus(): String = try {
    val shizuku: ShizukuController by inject(ShizukuController::class.java)
    shizuku.state().name
} catch (_: Throwable) {
    ShizukuController.State.NOT_INSTALLED.name
}

actual fun openAccessibilitySettings() {
    try {
        val automation: AutomationController by inject(AutomationController::class.java)
        automation.openAccessibilitySettings()
    } catch (_: Throwable) {
        // Koin not ready (previews/tests) — nothing to open.
    }
}

actual fun openAppByPackage(packageName: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    true
} catch (_: Throwable) {
    false
}

actual suspend fun returnToKaiAfterAutomation() {
    try {
        val controller: AutomationController by inject(AutomationController::class.java)
        controller.returnToKai()
    } catch (_: Throwable) {
        // Best-effort convenience: a failed return must not fail the agent turn.
    }
}

actual suspend fun requestShizukuAuthorization(): Boolean = try {
    val shizuku: ShizukuController by inject(ShizukuController::class.java)
    shizuku.ensureReady()
    true
} catch (_: Throwable) {
    // Shizuku surfaces failures as several exception types (unavailable, denied,
    // IllegalStateException from a missing binder). Never let any of them escape
    // into the settings coroutine — a denied dialog is not a crash.
    false
}

actual fun getShizukuDetails(): String = try {
    val shizuku: ShizukuController by inject(ShizukuController::class.java)
    val version = shizuku.version()
    if (version < 0) "" else "v$version"
} catch (_: Throwable) {
    ""
}

actual val isSplinterlandsSupported: Boolean = true

actual suspend fun compressImageBytes(bytes: ByteArray, mimeType: String): ByteArray {
    if (!mimeType.startsWith("image/")) return bytes
    return try {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        val maxDim = 1024
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            bitmap.scale(newWidth, newHeight)
        } else {
            bitmap
        }
        val outputStream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        outputStream.toByteArray()
    } catch (_: Exception) {
        bytes
    }
}

actual fun getAppFilesDirectory(): String {
    val context: Context by inject(Context::class.java)
    return context.filesDir.absolutePath
}

// Uses dev.spght:encryptedprefs-ktx — a maintained community fork of the deprecated
// androidx.security:security-crypto. We keep application-level encryption because
// secure settings store API keys, email passwords, and conversation encryption keys.
actual fun createSecureSettings(): Settings {
    val context: Context by inject(Context::class.java)
    return try {
        SharedPreferencesSettings(createEncryptedPrefs(context))
    } catch (_: Exception) {
        // AEADBadTagException occurs when Android Auto Backup restores the encrypted
        // prefs file but the Keystore key is hardware-bound and doesn't transfer.
        // Delete the corrupted file and recreate fresh encrypted prefs.
        context.deleteSharedPreferences("kai_secure_prefs")
        SharedPreferencesSettings(createEncryptedPrefs(context))
    }
}

private fun createEncryptedPrefs(context: Context): android.content.SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        "kai_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

actual fun createLegacySettings(): Settings? {
    val context: Context by inject(Context::class.java)
    val prefs = context.getSharedPreferences("com.inspiredandroid.kai_preferences", Context.MODE_PRIVATE)
    return SharedPreferencesSettings(prefs)
}

// Tool definitions for Android platform. Every tool Android can execute is listed, including the
// ones no per-tool switch controls — chat resolves display names from here, and an omission shows
// the raw tool id instead. Tools driven by a master toggle carry `userToggleable = false`, which
// is what keeps them out of the Tools tab.
actual fun getPlatformToolDefinitions(): List<ToolInfo> = CommonTools.commonToolDefinitions +
    listOf(
        SendNotificationTool.toolInfo,
        CreateCalendarEventTool.toolInfo,
        SetAlarmTool.toolInfo,
        OpenFileTool.toolInfo,
        SandboxFileTools.readFileToolInfo,
        SandboxFileTools.writeFileToolInfo,
        ShellCommandTool.toolInfo,
        ProcessManagerTool.toolInfo,
        SshConfigureHostTool.toolInfo,
        BrowsePageTool.toolInfo,
        UiAutomationTools.uiDumpToolInfo,
        UiAutomationTools.uiScreenshotToolInfo,
        UiAutomationTools.uiEventsToolInfo,
        UiAutomationTools.uiActToolInfo,
        UiAutomationTools.appCardToolInfo,
        UiAutomationTools.appLaunchToolInfo,
        UiAutomationTools.privilegedShellToolInfo,
        UiAutomationTools.privilegedInputToolInfo,
        ConversationTools.searchConversationsToolInfo,
        WebActTools.webActToolInfo,
    ) + SkillAdminTools.toolInfos

actual fun getAvailableTools(): List<Tool> {
    val context: Context by inject(Context::class.java)
    val appSettings: AppSettings by inject(AppSettings::class.java)
    val memoryStore: MemoryStore by inject(MemoryStore::class.java)
    val taskStore: TaskStore by inject(TaskStore::class.java)
    val emailStore: EmailStore by inject(EmailStore::class.java)
    val mcpServerManager: McpServerManager by inject(McpServerManager::class.java)

    return buildAgentToolSet(
        appSettings = appSettings,
        memoryStore = memoryStore,
        taskStore = taskStore,
        mcpServerManager = mcpServerManager,
        emailStore = emailStore,
    ) {
        if (appSettings.isToolEnabled(SendNotificationTool.ID)) {
            val notificationPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.POST_NOTIFICATIONS))
            add(SendNotificationTool.create(NotificationHelper(context, notificationPermissionController)))
        }

        if (appSettings.isToolEnabled(CreateCalendarEventTool.ID)) {
            val calendarPermissionController: PermissionController =
                KoinJavaComponent.get(PermissionController::class.java, permissionQualifier(AppPermission.CALENDAR))
            add(CreateCalendarEventTool.create(CalendarRepository(context, calendarPermissionController)))
        }

        if (appSettings.isToolEnabled(SetAlarmTool.ID)) {
            add(SetAlarmTool.create(context))
        }

        if (appSettings.isToolEnabled(OpenFileTool.schema.name)) {
            add(OpenFileTool)
        }

        if (appSettings.isToolEnabled(ConversationTools.searchConversationsToolInfo.id)) {
            val conversationStorage: ConversationStorage by inject(ConversationStorage::class.java)
            add(ConversationTools.searchConversationsTool(conversationStorage))
        }

        if (appSettings.isSandboxEnabled()) {
            val sandboxManager: LinuxSandboxManager by inject(LinuxSandboxManager::class.java)
            if (sandboxManager.state.value is SandboxState.Ready) {
                add(ShellCommandTool)
                add(ProcessManagerTool)
                add(SshConfigureHostTool)
                add(SandboxFileTools.readFileTool)
                add(SandboxFileTools.writeFileTool)
            }
            // Skill management rides the sandbox being Ready (skills are folders in
            // ~/skills/); each tool still keeps its own Tools-tab switch.
            addAll(SkillAdminTools.getTools())
        }

        // Headless page rendering needs no sandbox — only a System WebView,
        // which de-Googled ROMs may lack. Re-checked per call too, so a
        // WebView update mid-chat fails with a hint instead of acting.
        if (WebViewPageRenderer.isAvailable() && appSettings.isToolEnabled(BrowsePageTool.schema.name, false)) {
            add(BrowsePageTool)
        }
        if (WebViewPageRenderer.isAvailable() && appSettings.isToolEnabled(WebActTools.webActTool.schema.name, false)) {
            add(WebActTools.webActTool)
        }

        // Cross-app automation: triple-gated. `isAutomationEnabled()` is the master
        // switch, one live backend is required — the bound accessibility service
        // or Shizuku (which serves the same tools through `uiautomator` + raw
        // `input`, no accessibility setup needed) — and each tool keeps its own
        // Tools-tab switch. Write tools additionally require the write switch;
        // privileged tools require the Shizuku switch plus a live Shizuku service
        // (Tools themselves re-check everything at execution time, so a grant
        // revoked mid-chat fails with a hint instead of acting).
        if (appSettings.isAutomationEnabled()) {
            val automation: AutomationController by inject(AutomationController::class.java)
            val shizuku: ShizukuController by inject(ShizukuController::class.java)
            val shizukuState = shizuku.state()
            val shizukuUsable = appSettings.isShizukuEnabled() &&
                shizukuState != ShizukuController.State.NOT_INSTALLED &&
                shizukuState != ShizukuController.State.NOT_RUNNING
            if (automation.isServiceEnabled() || shizukuUsable) {
                if (appSettings.isToolEnabled(UiAutomationTools.uiDumpTool.schema.name, false)) {
                    add(UiAutomationTools.uiDumpTool)
                }
                if (appSettings.isToolEnabled(UiAutomationTools.uiScreenshotTool.schema.name, false)) {
                    add(UiAutomationTools.uiScreenshotTool)
                }
                if (appSettings.isToolEnabled(UiAutomationTools.uiEventsTool.schema.name, false)) {
                    add(UiAutomationTools.uiEventsTool)
                }
                if (appSettings.isToolEnabled(UiAutomationTools.appCardTool.schema.name, true)) {
                    add(UiAutomationTools.appCardTool)
                }
                if (appSettings.isAutomationWriteEnabled()) {
                    if (appSettings.isToolEnabled(UiAutomationTools.uiActTool.schema.name, false)) {
                        add(UiAutomationTools.uiActTool)
                    }
                    if (appSettings.isToolEnabled(UiAutomationTools.appLaunchTool.schema.name, false)) {
                        add(UiAutomationTools.appLaunchTool)
                    }
                    if (shizukuUsable) {
                        if (appSettings.isToolEnabled(UiAutomationTools.privilegedShellTool.schema.name, false)) {
                            add(UiAutomationTools.privilegedShellTool)
                        }
                        if (appSettings.isToolEnabled(UiAutomationTools.privilegedInputTool.schema.name, false)) {
                            add(UiAutomationTools.privilegedInputTool)
                        }
                    }
                }
            }
        }

        // SMS read tools: triple-gated. `isSmsSupported` is only true on FOSS builds
        // (READ_SMS declared in merged manifest). `isSmsEnabled()` is the user toggle.
        // `hasPermission()` catches runtime revocation.
        val smsReaderForTools: SmsReader? = if (isSmsSupported) {
            val smsReader: SmsReader by inject(SmsReader::class.java)
            smsReader
        } else {
            null
        }
        if (smsReaderForTools != null && appSettings.isSmsEnabled() && smsReaderForTools.hasPermission()) {
            val smsStore: SmsStore by inject(SmsStore::class.java)
            addAll(SmsTools.getSmsReadTools(smsStore, smsReaderForTools))
        }

        // SMS send tools: independently gated on the Send toggle + SEND_SMS permission.
        // These only *stage* drafts — actual sending is user-triggered via the review banner.
        if (smsReaderForTools != null && appSettings.isSmsSendEnabled()) {
            val smsSender: SmsSender by inject(SmsSender::class.java)
            if (smsSender.hasPermission()) {
                val smsDraftStore: SmsDraftStore by inject(SmsDraftStore::class.java)
                addAll(SmsTools.getSmsSendTools(smsDraftStore, smsReaderForTools, smsSender))
            }
        }

        // Notification tools: triple-gated. `isNotificationsSupported` is FOSS-only
        // (listener service declared in merged manifest). `isNotificationsEnabled()`
        // is the user toggle. `hasAccess()` catches system-level revocation.
        if (isNotificationsSupported && appSettings.isNotificationsEnabled()) {
            val notificationReader: NotificationReader by inject(NotificationReader::class.java)
            if (notificationReader.hasAccess()) {
                val notificationStore: NotificationStore by inject(NotificationStore::class.java)
                addAll(NotificationTools.getNotificationTools(notificationStore, notificationReader))
            }
        }
    }
}

actual fun openUrl(url: String): Boolean = try {
    val context: Context by inject(Context::class.java)
    val parsedUri = url.toUri()
    val intent = if (parsedUri.scheme == "file") {
        val file = java.io.File(parsedUri.path!!)
        val contentUri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension) ?: "*/*"
        Intent(Intent.ACTION_VIEW, contentUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(contentUri, mimeType)
        }
    } else {
        Intent(Intent.ACTION_VIEW, parsedUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(intent)
    true
} catch (_: Exception) {
    false
}

actual fun decodeToImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) {
    null
}

@androidx.compose.runtime.Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

actual suspend fun saveFileToDevice(bytes: ByteArray, baseName: String, extension: String) {
    val file = FileKit.openFileSaver(suggestedName = baseName, defaultExtension = extension)
    file?.write(bytes)
}
