package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.defaultUiScale
import com.inspiredandroid.kai.linux.LinuxDistro
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class ImportSection {
    SERVICES,
    SOUL,
    MEMORY,
    SCHEDULING,
    HEARTBEAT,
    EMAIL,
    SMS,
    SPLINTERLANDS,
    TOOLS,
    MCP,
    CONVERSATIONS,
}

enum class ThemeMode {
    System,
    Light,
    Dark,
    OledBlack,
}

/**
 * Stricter than [detectImportSections]: only includes sections that contain actual user data,
 * skipping ones that exist purely because of default feature-toggle flags (e.g. `sms_enabled = false`,
 * `splinterlands_enabled = false`, `mcp_servers = []`). Used to drive the Export preview dialog.
 */
fun detectExportableSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()

    val configured = json["configured_services"]?.jsonArray
    if (configured != null && configured.isNotEmpty()) {
        sections[ImportSection.SERVICES] = "${configured.size}"
    }

    if (json["soul_text"] != null || json["learned_soul"] != null) {
        sections[ImportSection.SOUL] = null
    }

    val memories = json["agent_memories"]?.jsonArray
    if (memories != null && memories.isNotEmpty()) {
        sections[ImportSection.MEMORY] = "${memories.size}"
    }

    val tasks = json["scheduled_tasks"]?.jsonArray
    if (tasks != null && tasks.isNotEmpty()) {
        sections[ImportSection.SCHEDULING] = "${tasks.size}"
    }

    val heartbeatHasPrompt = json["heartbeat_prompt"] != null
    val heartbeatHasConfig = json["heartbeat_config"] != null
    val heartbeatHasLog = json["heartbeat_log"]?.jsonArray?.isNotEmpty() == true
    if (heartbeatHasPrompt || heartbeatHasConfig || heartbeatHasLog) {
        sections[ImportSection.HEARTBEAT] = null
    }

    val emails = json["email_accounts"]?.jsonArray
    if (emails != null && emails.isNotEmpty()) {
        sections[ImportSection.EMAIL] = "${emails.size}"
    }

    val smsEnabled = json["sms_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    val smsSendEnabled = json["sms_send_enabled"]?.jsonPrimitive?.content?.toBoolean() == true
    if (smsEnabled || smsSendEnabled) {
        sections[ImportSection.SMS] = null
    }

    if (json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }

    val toolOverrides = json["tool_overrides"]?.jsonObject
    if (toolOverrides != null && toolOverrides.isNotEmpty()) {
        val enabled = toolOverrides.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = "$enabled"
    }

    val mcp = json["mcp_servers"]?.jsonArray
    if (mcp != null && mcp.isNotEmpty()) {
        sections[ImportSection.MCP] = "${mcp.size}"
    }

    val conversations = json["conversations"]?.jsonArray
    if (conversations != null && conversations.isNotEmpty()) {
        sections[ImportSection.CONVERSATIONS] = "${conversations.size}"
    }

    return sections
}

fun detectImportSections(json: JsonObject): Map<ImportSection, String?> {
    val sections = mutableMapOf<ImportSection, String?>()
    if (json["configured_services"] != null || json["current_service_id"] != null || json["free_fallback_enabled"] != null || json["instance_settings"] != null) {
        val count = json["configured_services"]?.jsonArray?.size
        sections[ImportSection.SERVICES] = count?.let { "$it" }
    }
    if (json["soul_text"] != null || json["learned_soul"] != null) {
        sections[ImportSection.SOUL] = null
    }
    if (json["memory_enabled"] != null || json["agent_memories"] != null) {
        val count = json["agent_memories"]?.jsonArray?.size
        sections[ImportSection.MEMORY] = count?.let { "$it" }
    }
    if (json["scheduling_enabled"] != null || json["scheduled_tasks"] != null) {
        val count = json["scheduled_tasks"]?.jsonArray?.size
        sections[ImportSection.SCHEDULING] = count?.let { "$it" }
    }
    if (json["heartbeat_config"] != null || json["heartbeat_prompt"] != null || json["heartbeat_log"] != null) {
        sections[ImportSection.HEARTBEAT] = null
    }
    if (json["email_enabled"] != null || json["email_accounts"] != null) {
        val count = json["email_accounts"]?.jsonArray?.size
        sections[ImportSection.EMAIL] = count?.let { "$it" }
    }
    if (json["sms_enabled"] != null || json["sms_poll_interval"] != null || json["sms_send_enabled"] != null) {
        sections[ImportSection.SMS] = null
    }
    if (json["splinterlands_enabled"] != null || json["splinterlands_account"] != null) {
        sections[ImportSection.SPLINTERLANDS] = null
    }
    if (json["tool_overrides"] != null) {
        val enabled = json["tool_overrides"]?.jsonObject?.count { (_, v) ->
            try {
                v.jsonPrimitive.content.toBoolean()
            } catch (_: Exception) {
                false
            }
        }
        sections[ImportSection.TOOLS] = enabled?.let { "$it" }
    }
    if (json["mcp_servers"] != null) {
        val count = json["mcp_servers"]?.jsonArray?.size
        sections[ImportSection.MCP] = count?.let { "$it" }
    }
    if (json["conversations"] != null) {
        val count = try {
            json["conversations"]?.jsonArray?.size
        } catch (_: Exception) {
            null
        }
        sections[ImportSection.CONVERSATIONS] = count?.let { "$it" }
    }
    return sections
}

data class ServiceInstance(
    val instanceId: String,
    val serviceId: String,
)

class AppSettings(internal val settings: Settings) {

    // App open tracking
    fun trackAppOpen(): Int {
        val currentCount = settings.getInt(KEY_APP_OPENS, 0)
        val newCount = currentCount + 1
        settings.putInt(KEY_APP_OPENS, newCount)
        return newCount
    }

    // Tool enable/disable settings
    fun isToolEnabled(toolId: String, defaultEnabled: Boolean = true): Boolean = settings.getBoolean("$KEY_TOOL_PREFIX$toolId", defaultEnabled)

    fun setToolEnabled(toolId: String, enabled: Boolean) {
        settings.putBoolean("$KEY_TOOL_PREFIX$toolId", enabled)
    }

    fun getConversationsJson(): String? = settings.getStringOrNull(KEY_CONVERSATIONS)

    fun setConversationsJson(json: String) {
        settings.putString(KEY_CONVERSATIONS, json)
    }

    fun removeConversationsJson() {
        settings.remove(KEY_CONVERSATIONS)
    }

    fun getCurrentConversationId(): String? = settings.getStringOrNull(KEY_CURRENT_CONVERSATION_ID)

    fun setCurrentConversationId(id: String?) {
        if (id == null) {
            settings.remove(KEY_CURRENT_CONVERSATION_ID)
        } else {
            settings.putString(KEY_CURRENT_CONVERSATION_ID, id)
        }
    }

    fun getCurrentInteractiveMode(): Boolean = settings.getBoolean(KEY_CURRENT_INTERACTIVE_MODE, false)

    fun setCurrentInteractiveMode(enabled: Boolean) {
        settings.putBoolean(KEY_CURRENT_INTERACTIVE_MODE, enabled)
    }

    fun isCurrentConversationMigrated(): Boolean = settings.getBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, false)

    fun markCurrentConversationMigrated() {
        settings.putBoolean(KEY_CURRENT_CONVERSATION_MIGRATED, true)
    }

    fun getEncryptionKey(): ByteArray? {
        val encoded = settings.getStringOrNull(KEY_ENCRYPTION_KEY) ?: return null
        return try {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            kotlin.io.encoding.Base64.decode(encoded)
        } catch (_: Exception) {
            null
        }
    }

    // Free fallback
    fun isFreeFallbackEnabled(): Boolean = settings.getBoolean(KEY_FREE_FALLBACK_ENABLED, true)

    fun setFreeFallbackEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_FREE_FALLBACK_ENABLED, enabled)
    }

    fun getFreeMode(): FreeMode {
        val stored = settings.getStringOrNull(KEY_FREE_MODE) ?: return FreeMode.FAST
        return FreeMode.entries.find { it.name == stored } ?: FreeMode.FAST
    }

    fun setFreeMode(mode: FreeMode) {
        settings.putString(KEY_FREE_MODE, mode.name)
    }

    fun isFreeServicePrimary(): Boolean = settings.getBoolean(KEY_FREE_SERVICE_PRIMARY, false)

    fun setFreeServicePrimary(primary: Boolean) {
        settings.putBoolean(KEY_FREE_SERVICE_PRIMARY, primary)
    }

    // Soul (system prompt)
    fun getSoulText(): String = settings.getString(KEY_SOUL, "")

    fun setSoulText(text: String) {
        settings.putString(KEY_SOUL, text)
    }

    // Memory
    fun isMemoryEnabled(): Boolean = settings.getBoolean(KEY_MEMORY_ENABLED, true)

    fun setMemoryEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MEMORY_ENABLED, enabled)
    }

    fun getMemoryInstructions(): String = settings.getString(KEY_MEMORY_INSTRUCTIONS, DEFAULT_MEMORY_INSTRUCTIONS)

    // Agent memories
    fun getMemoriesJson(): String = settings.getString(KEY_AGENT_MEMORIES, "[]")

    fun setMemoriesJson(json: String) {
        settings.putString(KEY_AGENT_MEMORIES, json)
    }

    // Scheduling
    fun isSchedulingEnabled(): Boolean = settings.getBoolean(KEY_SCHEDULING_ENABLED, true)

    fun setSchedulingEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SCHEDULING_ENABLED, enabled)
    }

    // Dynamic UI
    fun isDynamicUiEnabled(): Boolean = settings.getBoolean(KEY_DYNAMIC_UI_ENABLED, true)

    fun setDynamicUiEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DYNAMIC_UI_ENABLED, enabled)
    }

    private val _themeModeFlow = MutableStateFlow(loadInitialThemeMode())
    val themeModeFlow: StateFlow<ThemeMode> = _themeModeFlow

    fun getThemeMode(): ThemeMode = _themeModeFlow.value

    fun setThemeMode(mode: ThemeMode) {
        settings.putString(KEY_THEME_MODE, mode.name)
        _themeModeFlow.value = mode
    }

    private fun loadInitialThemeMode(): ThemeMode {
        val raw = settings.getString(KEY_THEME_MODE, "")
        if (raw.isNotEmpty()) {
            return try {
                ThemeMode.valueOf(raw)
            } catch (_: IllegalArgumentException) {
                ThemeMode.System
            }
        }
        // Migrate the legacy boolean OLED toggle: true → OledBlack, false → System.
        return if (settings.getBoolean(KEY_OLED_MODE_ENABLED, false)) ThemeMode.OledBlack else ThemeMode.System
    }

    // Daemon mode
    fun isDaemonEnabled(): Boolean = settings.getBoolean(KEY_DAEMON_ENABLED, false)

    fun setDaemonEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_DAEMON_ENABLED, enabled)
    }

    // Linux Sandbox
    fun isSandboxEnabled(): Boolean = settings.getBoolean(KEY_SANDBOX_ENABLED, true)

    fun setSandboxEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SANDBOX_ENABLED, enabled)
    }

    /**
     * Which distribution the shell integration runs in. Each keeps its own
     * install, so changing this points the sandbox at the other one rather than
     * replacing anything — and an install always records its own distro, which
     * is what everything downstream reads.
     */
    fun getSandboxDistro(): LinuxDistro = LinuxDistro.fromId(settings.getStringOrNull(KEY_SANDBOX_DISTRO))

    /**
     * The distribution the user actually picked, or null if they never have.
     * Sandboxes predating the picker never recorded one, and the default must
     * not be mistaken for a choice — it would point them away from the Linux
     * they have been using all along.
     */
    fun getSandboxDistroOrNull(): LinuxDistro? = settings.getStringOrNull(KEY_SANDBOX_DISTRO)
        ?.let { id -> LinuxDistro.entries.firstOrNull { it.id == id } }

    fun setSandboxDistro(distro: LinuxDistro) {
        settings.putString(KEY_SANDBOX_DISTRO, distro.id)
    }

    /**
     * Kai Build's "Open with" choice — the agent a freshly opened project starts,
     * or null for a plain shell. Stored as an empty string so "never picked" and
     * "picked the shell" both come back as null.
     */
    fun getKaiBuildLaunchAgent(): String? = settings.getStringOrNull(KEY_KAI_BUILD_LAUNCH_AGENT)?.takeIf { it.isNotEmpty() }

    fun setKaiBuildLaunchAgent(agentId: String?) {
        settings.putString(KEY_KAI_BUILD_LAUNCH_AGENT, agentId.orEmpty())
    }

    fun getScheduledTasksJson(): String = settings.getString(KEY_SCHEDULED_TASKS, "[]")

    fun setScheduledTasksJson(json: String) {
        settings.putString(KEY_SCHEDULED_TASKS, json)
    }

    // Heartbeat config
    fun getHeartbeatConfigJson(): String = settings.getString(KEY_HEARTBEAT_CONFIG, "")

    fun setHeartbeatConfigJson(json: String) {
        settings.putString(KEY_HEARTBEAT_CONFIG, json)
    }

    // Heartbeat log
    fun getHeartbeatLogJson(): String = settings.getString(KEY_HEARTBEAT_LOG, "")

    fun setHeartbeatLogJson(json: String) {
        settings.putString(KEY_HEARTBEAT_LOG, json)
    }

    // Heartbeat prompt
    fun getHeartbeatPrompt(): String = settings.getString(KEY_HEARTBEAT_PROMPT, "")

    fun setHeartbeatPrompt(text: String) {
        settings.putString(KEY_HEARTBEAT_PROMPT, text)
    }

    // Persisted unread badge. A heartbeat report can land while the process is
    // dead, so the dot has to survive a restart to be useful.
    fun isHeartbeatUnread(): Boolean = settings.getBoolean(KEY_HEARTBEAT_UNREAD, false)

    fun setHeartbeatUnread(unread: Boolean) {
        settings.putBoolean(KEY_HEARTBEAT_UNREAD, unread)
    }

    // Learned soul — AI-promoted additions kept apart from the user-authored soul
    // so resetting the soul doesn't erase them. Managed by [LearnedSoulStore].
    fun getLearnedSoulJson(): String = settings.getString(KEY_LEARNED_SOUL, "")

    fun setLearnedSoulJson(json: String) {
        settings.putString(KEY_LEARNED_SOUL, json)
    }

    // Per-app operating notes surfaced with ui_dump. Managed by [AppCardStore].
    fun getAppCardsJson(): String = settings.getString(KEY_APP_CARDS, "")

    fun setAppCardsJson(json: String) {
        settings.putString(KEY_APP_CARDS, json)
    }

    // MCP Servers
    fun getMcpServersJson(): String = settings.getString(KEY_MCP_SERVERS, "")

    fun setMcpServersJson(json: String) {
        settings.putString(KEY_MCP_SERVERS, json)
    }

    // UI Scale
    private val _uiScaleFlow = MutableStateFlow(settings.getFloat(KEY_UI_SCALE, defaultUiScale))
    val uiScaleFlow: StateFlow<Float> = _uiScaleFlow

    fun getUiScale(): Float = _uiScaleFlow.value

    fun setUiScale(scale: Float) {
        settings.putFloat(KEY_UI_SCALE, scale)
        _uiScaleFlow.value = scale
    }

    // Email
    fun isEmailEnabled(): Boolean = settings.getBoolean(KEY_EMAIL_ENABLED, true)

    fun setEmailEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_EMAIL_ENABLED, enabled)
    }

    fun getEmailAccountsJson(): String = settings.getString(KEY_EMAIL_ACCOUNTS, "")

    fun setEmailAccountsJson(json: String) {
        settings.putString(KEY_EMAIL_ACCOUNTS, json)
    }

    fun getEmailPassword(accountId: String): String = settings.getString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", "")

    fun setEmailPassword(accountId: String, password: String) {
        settings.putString("${KEY_EMAIL_PASSWORD_PREFIX}$accountId", password)
    }

    fun removeEmailPassword(accountId: String) {
        settings.remove("${KEY_EMAIL_PASSWORD_PREFIX}$accountId")
    }

    fun getEmailSyncStateJson(accountId: String): String = settings.getString("${KEY_EMAIL_SYNC_PREFIX}$accountId", "")

    fun setEmailSyncStateJson(accountId: String, json: String) {
        settings.putString("${KEY_EMAIL_SYNC_PREFIX}$accountId", json)
    }

    fun getEmailPollIntervalMinutes(): Int = settings.getInt(KEY_EMAIL_POLL_INTERVAL, 15)

    fun setEmailPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_EMAIL_POLL_INTERVAL, minutes)
    }

    fun getEmailPendingJson(): String = settings.getString(KEY_EMAIL_PENDING, "")

    fun setEmailPendingJson(json: String) {
        settings.putString(KEY_EMAIL_PENDING, json)
    }

    // SMS (FOSS-only, Android-only — settings layer is platform-agnostic, feature gate
    // is enforced by the READ_SMS permission being declared only in foss/AndroidManifest.xml)
    fun isSmsEnabled(): Boolean = settings.getBoolean(KEY_SMS_ENABLED, true)

    fun setSmsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_ENABLED, enabled)
    }

    fun getSmsPollIntervalMinutes(): Int = settings.getInt(KEY_SMS_POLL_INTERVAL, 15)

    fun setSmsPollIntervalMinutes(minutes: Int) {
        settings.putInt(KEY_SMS_POLL_INTERVAL, minutes)
    }

    fun getSmsPendingJson(): String = settings.getString(KEY_SMS_PENDING, "")

    fun setSmsPendingJson(json: String) {
        settings.putString(KEY_SMS_PENDING, json)
    }

    fun getSmsSyncStateJson(): String = settings.getString(KEY_SMS_SYNC_STATE, "")

    fun setSmsSyncStateJson(json: String) {
        settings.putString(KEY_SMS_SYNC_STATE, json)
    }

    fun isSmsSendEnabled(): Boolean = settings.getBoolean(KEY_SMS_SEND_ENABLED, false)

    fun setSmsSendEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SMS_SEND_ENABLED, enabled)
    }

    fun getSmsDraftsJson(): String = settings.getString(KEY_SMS_DRAFTS, "")

    fun setSmsDraftsJson(json: String) {
        settings.putString(KEY_SMS_DRAFTS, json)
    }

    // Notifications (FOSS-only, Android-only — settings layer is platform-agnostic, feature
    // gate is enforced by the listener service being declared only in foss/AndroidManifest.xml)
    fun isNotificationsEnabled(): Boolean = settings.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setNotificationsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)
    }

    fun getNotificationsPendingJson(): String = settings.getString(KEY_NOTIFICATIONS_PENDING, "")

    fun setNotificationsPendingJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_PENDING, json)
    }

    fun getNotificationsStoreJson(): String = settings.getString(KEY_NOTIFICATIONS_STORE, "")

    fun setNotificationsStoreJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_STORE, json)
    }

    fun getNotificationsSyncStateJson(): String = settings.getString(KEY_NOTIFICATIONS_SYNC_STATE, "")

    fun setNotificationsSyncStateJson(json: String) {
        settings.putString(KEY_NOTIFICATIONS_SYNC_STATE, json)
    }

    // Local model context size
    fun getModelContextTokens(modelId: String): Int = settings.getInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", 0)

    fun setModelContextTokens(modelId: String, contextTokens: Int) {
        settings.putInt("$KEY_MODEL_CONTEXT_PREFIX$modelId", contextTokens)
    }

    // Cross-app automation (Android-only; no-ops elsewhere). Master switch gates
    // every automation tool; the write switch additionally gates tap/input/scroll
    // and app launches. The allowlist is a comma-separated set of package names —
    // empty means "any app except the sensitive blocklist".
    fun isAutomationEnabled(): Boolean = settings.getBoolean(KEY_AUTOMATION_ENABLED, true)

    fun setAutomationEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTOMATION_ENABLED, enabled)
    }

    fun isAutomationWriteEnabled(): Boolean = settings.getBoolean(KEY_AUTOMATION_WRITE_ENABLED, true)

    fun setAutomationWriteEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_AUTOMATION_WRITE_ENABLED, enabled)
    }

    fun getAutomationAllowedApps(): Set<String> = settings.getString(KEY_AUTOMATION_ALLOWED_APPS, "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun setAutomationAllowedApps(packages: Set<String>) {
        settings.putString(KEY_AUTOMATION_ALLOWED_APPS, packages.joinToString(","))
    }

    fun isShizukuEnabled(): Boolean = settings.getBoolean(KEY_SHIZUKU_ENABLED, true)

    fun setShizukuEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SHIZUKU_ENABLED, enabled)
    }

    // Per-conversation todo checklists, persisted as JSON so progress survives
    // process death. Keyed by conversation id inside the blob.
    fun getTodoJson(): String = settings.getString(KEY_TODO_LISTS, "")

    fun setTodoJson(json: String) {
        settings.putString(KEY_TODO_LISTS, json)
    }

    // Event-conditioned notification intents (see IntentTools): intents plus
    // fired-notification bookkeeping in one blob.
    fun getNotificationIntentsJson(): String = settings.getString(KEY_NOTIFICATION_INTENTS, "")

    fun setNotificationIntentsJson(json: String) {
        settings.putString(KEY_NOTIFICATION_INTENTS, json)
    }

    // Cap on LLM chat requests per rolling minute across all services.
    // 0 (default) disables throttling; long tool loops then pace themselves
    // with cancellable waits instead of tripping provider rate limits.
    fun getApiMaxRequestsPerMinute(): Int = settings.getInt(KEY_API_MAX_RPM, 0)

    fun setApiMaxRequestsPerMinute(value: Int) {
        settings.putInt(KEY_API_MAX_RPM, value.coerceAtLeast(0))
    }

    // How many assistant tool-calling iterations one reply may run before the
    // loop bails out with a summary. Higher = longer autonomous tasks per turn,
    // at the cost of more API calls. Configurable in Settings → Tools.
    fun getMaxToolSteps(): Int = settings.getInt(KEY_MAX_TOOL_STEPS, DEFAULT_MAX_TOOL_STEPS)

    fun setMaxToolSteps(steps: Int) {
        settings.putInt(KEY_MAX_TOOL_STEPS, steps.coerceIn(MIN_TOOL_STEPS, MAX_TOOL_STEPS))
    }

    /**
     * Whether shell commands (`execute_shell_command`, `privileged_shell`) run without
     * asking. Default false (ask every time). Chosen in Settings → Tools; background
     * runs still fail closed regardless of this setting.
     */
    fun isShellAutoApprove(): Boolean = settings.getBoolean(KEY_SHELL_AUTO_APPROVE, false)

    fun setShellAutoApprove(autoApprove: Boolean) {
        settings.putBoolean(KEY_SHELL_AUTO_APPROVE, autoApprove)
    }

    // Heartbeat memory maintenance: review rotting/duplicate rows each run.
    // On by default; the section only renders when candidates exist, so quiet
    // histories cost nothing.
    fun isMemoryMaintenanceEnabled(): Boolean = settings.getBoolean(KEY_MEMORY_MAINTENANCE_ENABLED, true)

    fun setMemoryMaintenanceEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MEMORY_MAINTENANCE_ENABLED, enabled)
    }

    // A non-preference row untouched this many days (with ≤1 hit) counts as rotting.
    fun getMemoryStaleDays(): Int = settings.getInt(KEY_MEMORY_STALE_DAYS, 30)

    fun setMemoryStaleDays(days: Int) {
        settings.putInt(KEY_MEMORY_STALE_DAYS, days.coerceIn(7, 365))
    }

    // When the store exceeds its cap, heartbeat either suggests deletions or
    // performs them. Suggest-only by default — auto-delete is explicit opt-in.
    fun isMemoryAutoCleanupEnabled(): Boolean = settings.getBoolean(KEY_MEMORY_AUTO_CLEANUP, false)

    fun setMemoryAutoCleanupEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_MEMORY_AUTO_CLEANUP, enabled)
    }

    // Splinterlands
    fun isSplinterlandsEnabled(): Boolean = settings.getBoolean(KEY_SPLINTERLANDS_ENABLED, false)

    fun setSplinterlandsEnabled(enabled: Boolean) {
        settings.putBoolean(KEY_SPLINTERLANDS_ENABLED, enabled)
    }

    fun getSplinterlandsAccountJson(): String = settings.getString(KEY_SPLINTERLANDS_ACCOUNT, "")

    fun setSplinterlandsAccountJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_ACCOUNT, json)
    }

    fun getSplinterlandsPostingKey(): String = settings.getString(KEY_SPLINTERLANDS_POSTING_KEY, "")

    fun getSplinterlandsPostingKey(accountId: String): String = settings.getString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", "")
        .ifEmpty { getSplinterlandsPostingKey() } // fallback to legacy key

    fun setSplinterlandsPostingKey(accountId: String, key: String) {
        settings.putString("${KEY_SPLINTERLANDS_POSTING_KEY}_$accountId", key)
    }

    fun getSplinterlandsInstanceId(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_ID, "")

    fun setSplinterlandsInstanceId(instanceId: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_ID, instanceId)
    }

    fun getSplinterlandsInstanceIdsJson(): String = settings.getString(KEY_SPLINTERLANDS_INSTANCE_IDS, "")

    fun setSplinterlandsInstanceIdsJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_INSTANCE_IDS, json)
    }

    fun getSplinterlandsBattleLogJson(): String = settings.getString(KEY_SPLINTERLANDS_BATTLE_LOG, "")

    fun setSplinterlandsBattleLogJson(json: String) {
        settings.putString(KEY_SPLINTERLANDS_BATTLE_LOG, json)
    }

    companion object {
        const val KEY_CURRENT_SERVICE_ID = "current_service_id"
        const val KEY_APP_OPENS = "app_opens"

        const val KEY_CONVERSATIONS = "conversations_json"
        const val KEY_CURRENT_CONVERSATION_ID = "current_conversation_id"
        const val KEY_CURRENT_INTERACTIVE_MODE = "current_interactive_mode"
        const val KEY_CURRENT_CONVERSATION_MIGRATED = "current_conversation_migrated"
        const val KEY_ENCRYPTION_KEY = "encryption_key"
        const val KEY_MIGRATION_COMPLETE = "migration_complete_v1"
        const val KEY_TOOL_PREFIX = "tool_enabled_"
        const val KEY_SOUL = "soul_text"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_MEMORY_INSTRUCTIONS = "memory_instructions"
        const val KEY_AGENT_MEMORIES = "agent_memories"
        const val KEY_SCHEDULED_TASKS = "scheduled_tasks"
        const val KEY_SCHEDULING_ENABLED = "scheduling_enabled"
        const val KEY_DYNAMIC_UI_ENABLED = "dynamic_ui_enabled"
        const val KEY_OLED_MODE_ENABLED = "oled_mode_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DAEMON_ENABLED = "daemon_enabled"
        const val KEY_HEARTBEAT_CONFIG = "heartbeat_config"
        const val KEY_HEARTBEAT_PROMPT = "heartbeat_prompt"
        const val KEY_HEARTBEAT_LOG = "heartbeat_log"
        const val KEY_HEARTBEAT_UNREAD = "heartbeat_unread"
        const val KEY_LEARNED_SOUL = "learned_soul"
        const val KEY_APP_CARDS = "app_cards"

        const val KEY_EMAIL_ENABLED = "email_enabled"
        const val KEY_EMAIL_ACCOUNTS = "email_accounts"
        const val KEY_EMAIL_PASSWORD_PREFIX = "email_password_"
        const val KEY_EMAIL_SYNC_PREFIX = "email_sync_"
        const val KEY_EMAIL_POLL_INTERVAL = "email_poll_interval"
        const val KEY_EMAIL_PENDING = "email_pending"

        const val KEY_SMS_ENABLED = "sms_enabled"
        const val KEY_SMS_POLL_INTERVAL = "sms_poll_interval"
        const val KEY_SMS_PENDING = "sms_pending"
        const val KEY_SMS_SYNC_STATE = "sms_sync_state"
        const val KEY_SMS_SEND_ENABLED = "sms_send_enabled"
        const val KEY_SMS_DRAFTS = "sms_drafts"

        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_NOTIFICATIONS_PENDING = "notifications_pending"
        const val KEY_NOTIFICATIONS_STORE = "notifications_store"
        const val KEY_NOTIFICATIONS_SYNC_STATE = "notifications_sync_state"
        const val KEY_CONFIGURED_SERVICES = "configured_services"
        const val KEY_FREE_FALLBACK_ENABLED = "free_fallback_enabled"
        const val KEY_TODO_LISTS = "todo_lists"
        const val KEY_NOTIFICATION_INTENTS = "notification_intents"
        const val KEY_API_MAX_RPM = "api_max_requests_per_minute"
        const val KEY_MAX_TOOL_STEPS = "max_tool_steps"
        const val KEY_SHELL_AUTO_APPROVE = "shell_auto_approve"

        /** Slider bounds for [getMaxToolSteps] (Settings → Tools). */
        const val MIN_TOOL_STEPS = 20
        const val MAX_TOOL_STEPS = 100
        const val DEFAULT_MAX_TOOL_STEPS = 35
        const val KEY_MEMORY_MAINTENANCE_ENABLED = "memory_maintenance_enabled"
        const val KEY_MEMORY_STALE_DAYS = "memory_stale_days"
        const val KEY_MEMORY_AUTO_CLEANUP = "memory_auto_cleanup"
        const val KEY_FREE_MODE = "free_mode"
        const val KEY_FREE_SERVICE_PRIMARY = "free_service_primary"
        const val KEY_SERVICES_MIGRATION_COMPLETE = "services_migration_complete_v1"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_MCP_SERVERS = "mcp_servers"
        const val KEY_INSTANCE_MIGRATION_COMPLETE = "instance_migration_complete_v1"
        const val KEY_BASE_URL_V1_MIGRATION_COMPLETE = "base_url_v1_migration_complete"
        const val KEY_CUSTOM_MODEL_MIGRATION_COMPLETE = "custom_model_migration_complete_v1"

        const val KEY_SPLINTERLANDS_ENABLED = "splinterlands_enabled"
        const val KEY_SPLINTERLANDS_ACCOUNT = "splinterlands_account"
        const val KEY_SPLINTERLANDS_POSTING_KEY = "splinterlands_posting_key"
        const val KEY_SPLINTERLANDS_BATTLE_LOG = "splinterlands_battle_log"
        const val KEY_SPLINTERLANDS_INSTANCE_ID = "splinterlands_instance_id"
        const val KEY_SPLINTERLANDS_INSTANCE_IDS = "splinterlands_instance_ids"

        const val KEY_MODEL_CONTEXT_PREFIX = "model_context_"

        const val KEY_SANDBOX_ENABLED = "sandbox_enabled"
        const val KEY_SANDBOX_DISTRO = "sandbox_distro"
        const val KEY_KAI_BUILD_LAUNCH_AGENT = "kai_build_launch_agent"

        // Cross-app automation (Android-only): UI automation via AccessibilityService
        // plus ADB-level control via Shizuku. All default to off.
        const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        const val KEY_AUTOMATION_WRITE_ENABLED = "automation_write_enabled"
        const val KEY_AUTOMATION_ALLOWED_APPS = "automation_allowed_apps"
        const val KEY_SHIZUKU_ENABLED = "shizuku_enabled"

        // Basic memory guidance shared by every chat variant. The advanced `## Structured
        // Learning` block lives in `ChatSystemPromptBuilder.DEFAULT_STRUCTURED_LEARNING_SECTION`
        // and is composed in only for the remote variant.
        const val DEFAULT_MEMORY_INSTRUCTIONS =
            "You have persistent memory across conversations. " +
                "All your stored memories are listed in the system prompt grouped by category.\n\n" +
                "When you learn important information about the user (name, preferences, projects, goals, etc.), " +
                "proactively use the memory_store tool to save it.\n" +
                "Use the memory_forget tool to remove outdated or incorrect memories.\n" +
                "Do not store trivial or transient information."
    }
}
