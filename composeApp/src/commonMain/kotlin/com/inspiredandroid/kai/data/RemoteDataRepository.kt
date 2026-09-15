@file:OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class, ExperimentalUuidApi::class)

package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.SandboxController
import com.inspiredandroid.kai.compressImageBytes
import com.inspiredandroid.kai.currentPlatform
import com.inspiredandroid.kai.data.providers.buildAnthropicMessages
import com.inspiredandroid.kai.data.providers.buildOpenAIMessages
import com.inspiredandroid.kai.data.providers.toResponsesInput
import com.inspiredandroid.kai.email.EmailPoller
import com.inspiredandroid.kai.formatFileSize
import com.inspiredandroid.kai.getAvailableTools
import com.inspiredandroid.kai.getPlatformToolDefinitions
import com.inspiredandroid.kai.getShizukuDetails
import com.inspiredandroid.kai.getShizukuStatus
import com.inspiredandroid.kai.inference.DownloadError
import com.inspiredandroid.kai.inference.DownloadedModel
import com.inspiredandroid.kai.inference.EngineState
import com.inspiredandroid.kai.inference.InferenceMessage
import com.inspiredandroid.kai.inference.LocalInferenceEngine
import com.inspiredandroid.kai.inference.LocalModel
import com.inspiredandroid.kai.inference.LocalTool
import com.inspiredandroid.kai.inference.ModelImportError
import com.inspiredandroid.kai.inference.ModelImportResult
import com.inspiredandroid.kai.inference.NoModelDownloadedException
import com.inspiredandroid.kai.inference.getTotalMemoryBytes
import com.inspiredandroid.kai.isAutomationServiceEnabled
import com.inspiredandroid.kai.linux.LinuxDistro
import com.inspiredandroid.kai.mcp.McpAppTemplate
import com.inspiredandroid.kai.mcp.McpServerConfig
import com.inspiredandroid.kai.mcp.McpServerManager
import com.inspiredandroid.kai.network.AllServicesFailedException
import com.inspiredandroid.kai.network.AnthropicInsufficientCreditsException
import com.inspiredandroid.kai.network.ContextWindowExceededException
import com.inspiredandroid.kai.network.FileTooLargeException
import com.inspiredandroid.kai.network.OpenAICompatibleEmptyResponseException
import com.inspiredandroid.kai.network.OpenAICompatibleGenericException
import com.inspiredandroid.kai.network.OpenAICompatibleQuotaExhaustedException
import com.inspiredandroid.kai.network.Requests
import com.inspiredandroid.kai.network.ServiceCredentials
import com.inspiredandroid.kai.network.UnsupportedFileTypeException
import com.inspiredandroid.kai.network.dtos.anthropic.extractText
import com.inspiredandroid.kai.network.dtos.gemini.extractText
import com.inspiredandroid.kai.network.dtos.openaicompatible.extractInlineToolCalls
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesResponseDto
import com.inspiredandroid.kai.network.toUiError
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.openAccessibilitySettings
import com.inspiredandroid.kai.openAppByPackage
import com.inspiredandroid.kai.openUrl
import com.inspiredandroid.kai.returnToKaiAfterAutomation
import com.inspiredandroid.kai.skills.RegistrySkillEntry
import com.inspiredandroid.kai.skills.SkillManager
import com.inspiredandroid.kai.skills.SkillManifest
import com.inspiredandroid.kai.sms.SmsPoller
import com.inspiredandroid.kai.sms.SmsReader
import com.inspiredandroid.kai.sms.SmsSendResult
import com.inspiredandroid.kai.sms.SmsSender
import com.inspiredandroid.kai.tools.NotificationListenerController
import com.inspiredandroid.kai.tools.PermissionController
import com.inspiredandroid.kai.tools.ToolInteractionElement
import com.inspiredandroid.kai.tools.isInteractiveRun
import com.inspiredandroid.kai.ui.chat.History
import com.inspiredandroid.kai.ui.chat.ToolCallInfo
import com.inspiredandroid.kai.ui.chat.toGeminiMessageDto
import com.inspiredandroid.kai.ui.settings.SettingsModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.default_soul
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.compose.resources.getString
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MIN_TOOL_DISPLAY_MS = 2000L
private const val MAX_REPEATED_TOOL_CALLS = 3
private const val MAX_API_RETRIES = 2
private const val MAX_HEARTBEAT_MESSAGES = 50
private const val ESTIMATED_CHARS_PER_TOKEN = 4
private const val COMPACTION_THRESHOLD = 0.7 // Compact when history exceeds 70% of context window
private const val COMPACTION_KEEP_RECENT = 4 // Number of recent user exchanges to keep verbatim
private const val RUN_SAVE_THROTTLE_MS = 500L // Incremental persistence cadence for in-flight runs
private const val MAX_CONSECUTIVE_FAILING_TOOL_BATCHES = 2

/** Tool results are JSON-ish maps; `"success": false` marks a failed call. */
private val TOOL_FAILURE_MARKER = Regex("\"success\"\\s*:\\s*false")

private fun looksLikeToolFailure(content: String): Boolean = content.length < 4_000 && TOOL_FAILURE_MARKER.containsMatchIn(content)

/** Tools whose use means the agent drove another app's UI during the turn. */
private val AUTOMATION_TOOL_NAMES = setOf("ui_act", "app_launch", "privileged_input")

private fun automationWasUsed(messages: List<History>, fromIndex: Int): Boolean = messages.drop(fromIndex).any { it.role == History.Role.TOOL && it.toolName in AUTOMATION_TOOL_NAMES }

// Explicit allowlist of tools exposed to the on-device (LiteRT) model. We use a
// hardcoded name list rather than a structural filter because small Gemma models hit
// litert-lm's strict ANTLR function-call parser hard on anything more complex than
// a couple of string parameters. Excluded by design: memory_learn (4 params + enum),
// schedule_task / list_tasks / cancel_task (datetime + cron), the entire email family,
// the heartbeat config tools, and MCP tools.
//
// Included beyond the basics because their schemas stay string-simple:
// fetch_url (url + optional strings; POST misuse is bounded by the SSRF host guard),
// search_memories (one string), todo (action + two optional strings),
// search_conversations (one string). All read-only except todo/fetch-POST, which
// carry no privilege beyond what the app already grants the chat.
internal val LOCAL_TOOL_ALLOWLIST = setOf(
    "get_local_time",
    "get_location_from_ip",
    "web_search",
    "open_url",
    "fetch_url",
    "memory_store",
    "memory_forget",
    "memory_reinforce",
    "search_memories",
    "todo",
    "search_conversations",
    "execute_shell_command",
)

/**
 * The Responses API reports a failed turn inside a 200 body (`status: "failed"`), which would
 * otherwise read as an empty answer. Surface OpenAI's message instead of a generic empty-response
 * error.
 */
private fun OpenAIResponsesResponseDto.throwIfFailed(service: Service) {
    val message = error?.message ?: return
    throw OpenAICompatibleGenericException("${service.displayName}: $message")
}

private data class LoopChatResult(
    val textContent: String,
    val reasoningContent: String? = null,
    val isThinkingContent: Boolean = false,
    val toolCalls: List<ToolCallInfo>,
)

/** Final answer from a single assistant turn — text and (optionally) the reasoning trace
 * that produced it. Returned from [askWithService] so the caller can persist both. */
private data class AssistantTurn(
    val content: String,
    val reasoningContent: String? = null,
)

private enum class BailoutReason { LIMIT_REACHED, REPEATING }

private fun bailoutPrompt(reason: BailoutReason): String = when (reason) {
    BailoutReason.LIMIT_REACHED -> "You have reached the tool call limit. Please respond with the best answer you have so far based on the information gathered."
    BailoutReason.REPEATING -> "You are repeating the same tool calls. Please respond with the best answer you have so far."
}

private interface ToolLoopStrategy {
    suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult
    suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String

    /**
     * Context budget used to trim raw history between tool rounds. Providers that send the
     * history as-is (Gemini, Anthropic) declare their window here; the OpenAI-compatible
     * strategy trims the built message list inside [chat] instead and leaves this null.
     */
    val historyContextWindowTokens: Int? get() = null
}

class RemoteDataRepository(
    private val requests: Requests,
    private val appSettings: AppSettings,
    private val conversationStorage: ConversationStorage,
    private val toolExecutor: ToolExecutor,
    private val memoryStore: MemoryStore,
    private val taskStore: TaskStore,
    private val heartbeatManager: HeartbeatManager,
    private val emailStore: EmailStore,
    private val emailPoller: EmailPoller,
    private val smsStore: SmsStore,
    private val smsPoller: SmsPoller,
    private val smsReader: SmsReader,
    private val smsPermissionController: PermissionController,
    private val smsSendPermissionController: PermissionController,
    private val smsSender: SmsSender,
    private val smsDraftStore: SmsDraftStore,
    private val notificationStore: NotificationStore,
    private val notificationListenerController: NotificationListenerController,
    private val mcpServerManager: McpServerManager,
    private val skillManager: SkillManager,
    private val sandboxController: SandboxController,
    private val localInferenceEngine: LocalInferenceEngine? = null,
) : DataRepository {

    private val prettyJson = Json { prettyPrint = true }

    /**
     * Returns the tools exposed to the on-device (LiteRT) model. Filtered by name against
     * [LOCAL_TOOL_ALLOWLIST]. Tools the user has disabled in settings (e.g. shell command,
     * which is gated behind `isToolEnabled("execute_shell_command")`) won't appear in
     * `getAvailableTools()` in the first place, so they're naturally excluded.
     */
    private fun getLocalSafeTools(): List<Tool> = getAvailableTools()
        .filter { it.schema.name in LOCAL_TOOL_ALLOWLIST }

    /**
     * Whether the on-device model file for [modelId] carries a tool section in its chat
     * template. A model that doesn't isn't merely worse at tools — it answers *instead* of
     * calling them, inventing whatever the tool would have returned, which is how Qwen3
     * 0.6B reports a fictional time rather than calling `get_local_time`. Withholding the
     * tools makes it a plain chat model, which is what it actually is.
     *
     * The engine reports null when it cannot read the declaration (iOS, or a bundle whose
     * metadata predates it). That is *unknown*, not *no*: keep offering the allowlist there
     * so existing setups behave exactly as before.
     */
    private suspend fun localModelDeclaresTools(modelId: String): Boolean = localInferenceEngine?.modelCapabilities(modelId)?.supportsFunctionCalling != false

    // Per-instance model storage: instanceId -> models flow
    private val modelsByInstance: MutableMap<String, MutableStateFlow<List<SettingsModel>>> = mutableMapOf()

    /** Build credentials from per-instance settings */
    private fun instanceCredentials(instanceId: String, service: Service): ServiceCredentials = ServiceCredentials(
        apiKey = appSettings.getInstanceApiKey(instanceId),
        modelId = if (service == Service.Free) {
            appSettings.getFreeMode().modelId
        } else {
            appSettings.getInstanceEffectiveModelId(instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
        },
        baseUrl = getInstanceBaseUrl(instanceId, service),
    )

    override val chatHistory: MutableStateFlow<List<History>> = MutableStateFlow(emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    override val currentConversationId: StateFlow<String?> = _currentConversationId

    private val _runningConversationIds = MutableStateFlow<Set<String>>(emptySet())
    override val runningConversationIds: StateFlow<Set<String>> = _runningConversationIds

    /**
     * Message flows of in-flight runs, keyed by conversation id. A run reads/writes
     * its own flow; [chatHistory] is only a mirror of whichever one the user views.
     * Held in a StateFlow so UI-thread lookups stay safe against run-thread writes.
     */
    private val runHistoriesByConversation = MutableStateFlow<Map<String, MutableStateFlow<List<History>>>>(emptyMap())

    /** Process-lifetime scope for the mirror/incremental-save collectors of runs. */
    private val runScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ChatRuns"))

    private val _fallbackStatus = MutableStateFlow<FallbackStatus?>(null)
    override val fallbackStatus: StateFlow<FallbackStatus?> = _fallbackStatus

    override val savedConversations: StateFlow<List<Conversation>> = conversationStorage.conversations

    override fun getConfiguredServiceInstances(): List<ServiceInstance> = appSettings.getConfiguredServiceInstances().filter { Service.fromId(it.serviceId) != Service.Free }

    override fun addConfiguredService(serviceId: String): ServiceInstance {
        val instanceId = appSettings.generateInstanceId(serviceId)
        val instance = ServiceInstance(instanceId = instanceId, serviceId = serviceId)
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.add(instance)
        appSettings.setConfiguredServiceInstances(current)
        appSettings.setFreeServicePrimary(false)
        return instance
    }

    override fun removeConfiguredService(instanceId: String) {
        val current = appSettings.getConfiguredServiceInstances().toMutableList()
        current.removeAll { it.instanceId == instanceId }
        appSettings.setConfiguredServiceInstances(current)
        appSettings.removeInstanceSettings(instanceId)
        modelsByInstance.remove(instanceId)
    }

    override fun reorderConfiguredServices(orderedInstanceIds: List<String>) {
        val current = appSettings.getConfiguredServiceInstances()
        val byId = current.associateBy { it.instanceId }
        val reordered = orderedInstanceIds.mapNotNull { byId[it] }
        appSettings.setConfiguredServiceInstances(reordered)
    }

    override fun getServiceEntries(): List<ServiceEntry> = getConfiguredServiceInstances().map { instance ->
        val service = Service.fromId(instance.serviceId)
        val modelId = appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty {
            appSettings.getSelectedModelId(service)
        }
        ServiceEntry(
            instanceId = instance.instanceId,
            serviceId = service.id,
            serviceName = service.displayName,
            modelId = modelId,
            icon = service.icon,
        )
    }

    override fun isFreeFallbackEnabled(): Boolean = appSettings.isFreeFallbackEnabled()

    override fun getApiMaxRequestsPerMinute(): Int = appSettings.getApiMaxRequestsPerMinute()

    override fun setApiMaxRequestsPerMinute(value: Int) {
        appSettings.setApiMaxRequestsPerMinute(value)
    }

    override fun isMemoryMaintenanceEnabled(): Boolean = appSettings.isMemoryMaintenanceEnabled()

    override fun setMemoryMaintenanceEnabled(enabled: Boolean) {
        appSettings.setMemoryMaintenanceEnabled(enabled)
    }

    override fun getMemoryStaleDays(): Int = appSettings.getMemoryStaleDays()

    override fun setMemoryStaleDays(days: Int) {
        appSettings.setMemoryStaleDays(days)
    }

    override fun isMemoryAutoCleanupEnabled(): Boolean = appSettings.isMemoryAutoCleanupEnabled()

    override fun setMemoryAutoCleanupEnabled(enabled: Boolean) {
        appSettings.setMemoryAutoCleanupEnabled(enabled)
    }

    override fun setFreeFallbackEnabled(enabled: Boolean) {
        appSettings.setFreeFallbackEnabled(enabled)
    }

    override fun getFreeMode(): FreeMode = appSettings.getFreeMode()

    override fun setFreeMode(mode: FreeMode) {
        appSettings.setFreeMode(mode)
    }

    override fun isFreeServicePrimary(): Boolean = appSettings.isFreeServicePrimary()

    override fun setFreeServicePrimary(primary: Boolean) {
        appSettings.setFreeServicePrimary(primary)
    }

    // Per-instance settings
    override fun getInstanceApiKey(instanceId: String): String = appSettings.getInstanceApiKey(instanceId)

    override fun updateInstanceApiKey(instanceId: String, apiKey: String) {
        appSettings.setInstanceApiKey(instanceId, apiKey)
    }

    override fun getInstanceBaseUrl(instanceId: String, service: Service): String {
        val url = appSettings.getInstanceBaseUrl(instanceId)
        return url.ifBlank { if (service is Service.OpenAICompatible) Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL else "" }
    }

    override fun updateInstanceBaseUrl(instanceId: String, baseUrl: String) {
        appSettings.setInstanceBaseUrl(instanceId, baseUrl)
    }

    override fun getInstanceModels(instanceId: String, service: Service): StateFlow<List<SettingsModel>> = modelsByInstance.getOrPut(instanceId) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val defaultSettingsModels = service.defaultModels.map {
            SettingsModel(
                id = it.id,
                subtitle = it.subtitle,
                descriptionRes = it.descriptionRes,
                isSelected = it.id == selectedModelId,
            )
        }
        val models = if (selectedModelId.isNotEmpty() && defaultSettingsModels.none { it.id == selectedModelId }) {
            listOf(
                SettingsModel(
                    id = selectedModelId,
                    subtitle = "",
                    isSelected = true,
                    isManualEntry = true,
                ),
            ) + defaultSettingsModels
        } else {
            defaultSettingsModels
        }
        MutableStateFlow(models)
    }

    override fun updateInstanceSelectedModel(instanceId: String, service: Service, modelId: String) {
        appSettings.setInstanceModelId(instanceId, modelId)
        modelsByInstance[instanceId]?.update { models ->
            ensureSelectedModelPresent(models, modelId)
        }
        // Free the previously-loaded on-device model as soon as the user picks a new one.
        // Deferring until the next chat would briefly hold both models' GPU buffers resident
        // and the driver's lazy reclaim can push us past LMK thresholds on mid-range devices.
        if (service.isOnDevice && localInferenceEngine?.currentModelId?.let { it != modelId } == true) {
            localInferenceEngine.releaseInBackground()
        }
    }

    override fun getInstanceUseCustomModel(instanceId: String): Boolean = appSettings.getInstanceUseCustomModel(instanceId)

    override fun updateInstanceUseCustomModel(instanceId: String, useCustom: Boolean) {
        if (useCustom && appSettings.getInstanceCustomModelId(instanceId).isBlank()) {
            // Prefill from list selection so the field is not empty when the checkbox is first enabled.
            val listModelId = appSettings.getInstanceModelId(instanceId)
            if (listModelId.isNotBlank()) {
                appSettings.setInstanceCustomModelId(instanceId, listModelId)
            }
        }
        appSettings.setInstanceUseCustomModel(instanceId, useCustom)
    }

    override fun getInstanceCustomModelId(instanceId: String): String = appSettings.getInstanceCustomModelId(instanceId)

    override fun updateInstanceCustomModelId(instanceId: String, modelId: String) {
        appSettings.setInstanceCustomModelId(instanceId, modelId)
    }

    override fun clearInstanceModels(instanceId: String, service: Service) {
        modelsByInstance[instanceId]?.update { emptyList() }
    }

    override suspend fun validateConnection(service: Service, instanceId: String) {
        if (service.isOnDevice) {
            fetchInstanceModels(service, instanceId)
            return
        }
        val creds = instanceCredentials(instanceId, service)
        when (service) {
            Service.Free -> { /* Always valid */ }

            Service.OpenRouter -> {
                requests.validateOpenRouterApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            Service.Perplexity -> {
                // No Sonar `/models` list — validate the key, then load the curated defaults.
                requests.validatePerplexityApiKey(creds).getOrThrow()
                fetchInstanceModels(service, instanceId)
            }

            else -> fetchInstanceModels(service, instanceId)
        }
    }

    private suspend fun fetchInstanceModels(service: Service, instanceId: String) {
        when (service) {
            Service.Gemini -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapGeminiModels(requests.getGeminiModels(creds).getOrThrow().models, selectedModelId)
            }

            Service.Anthropic -> fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                mapAnthropicModels(requests.getAnthropicModels(creds).getOrThrow().data, selectedModelId)
            }

            Service.Free -> { /* No model listing */ }

            Service.LiteRT -> {
                val engine = localInferenceEngine ?: return
                val selectedModelId = appSettings.getInstanceModelId(instanceId)
                val downloaded = engine.getDownloadedModels()
                val models = downloaded.map {
                    SettingsModel(
                        id = it.id,
                        subtitle = "${it.displayName} (${formatFileSize(it.sizeBytes)})",
                        isSelected = it.id == selectedModelId,
                    )
                }
                updateModelsForInstance(instanceId, models, service)
            }

            else -> {
                if (service.modelsUrl != null) {
                    fetchModelsForInstance(service, instanceId) { creds, selectedModelId ->
                        mapOpenAICompatibleModels(
                            requests.getOpenAICompatibleModels(service, creds).getOrThrow().data,
                            service,
                            selectedModelId,
                        )
                    }
                } else if (service.defaultModels.isNotEmpty()) {
                    val selectedModelId = appSettings.getInstanceModelId(instanceId)
                    val models = service.defaultModels.map {
                        SettingsModel(
                            id = it.id,
                            subtitle = it.subtitle,
                            descriptionRes = it.descriptionRes,
                            isSelected = it.id == selectedModelId,
                        )
                    }
                    updateModelsForInstance(instanceId, models, service)
                }
            }
        }
    }

    /**
     * Fetches [service]'s model list for [instanceId] and publishes it. [fetchAndMap] is the only
     * per-provider part: it issues the request and maps the response to [SettingsModel]s, given
     * the instance credentials and the currently selected model id.
     */
    private suspend fun fetchModelsForInstance(
        service: Service,
        instanceId: String,
        fetchAndMap: suspend (ServiceCredentials, String) -> List<SettingsModel>,
    ) {
        val creds = instanceCredentials(instanceId, service)
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        updateModelsForInstance(instanceId, fetchAndMap(creds, selectedModelId))
    }

    private fun updateModelsForInstance(instanceId: String, models: List<SettingsModel>, service: Service? = null) {
        val selectedModelId = appSettings.getInstanceModelId(instanceId)
        val withSelected = ensureSelectedModelPresent(models, selectedModelId)
        val flow = modelsByInstance.getOrPut(instanceId) { MutableStateFlow(emptyList()) }
        flow.update { withSelected }
        // Only auto-pick when nothing is stored yet — never overwrite a custom / unlisted model id.
        if (withSelected.isNotEmpty() && withSelected.none { it.isSelected }) {
            val default = pickDefaultModel(withSelected, service)
            if (default != null) {
                appSettings.setInstanceModelId(instanceId, default.id)
                flow.update { m -> m.map { it.copy(isSelected = it.id == default.id) } }
            }
        }
    }

    private fun pickDefaultModel(models: List<SettingsModel>, service: Service? = null): SettingsModel? {
        val defaultModel = service?.defaultModel
        if (defaultModel != null) {
            models.firstOrNull { it.id == defaultModel }?.let { return it }
        }
        return models.firstOrNull { it.id.contains("kimi-k2.5", ignoreCase = true) }
            ?: models.firstOrNull()
    }

    private suspend fun askWithLocalEngine(
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): String {
        val engine = localInferenceEngine
            ?: throw IllegalStateException("On-device inference not available on this platform")

        val modelId = appSettings.getInstanceModelId(instanceId)
        val downloadedModels = engine.getDownloadedModels()
        val model = downloadedModels.find { it.id == modelId }
            ?: downloadedModels.firstOrNull()
            ?: throw NoModelDownloadedException()

        val catalogModel = engine.getAvailableModels().find { it.id == model.id }
        val storedContext = appSettings.getModelContextTokens(model.id)
        val contextTokens = if (storedContext > 0) storedContext else catalogModel?.defaultContextTokens ?: 0

        val needsInit = engine.engineState.value != EngineState.READY || engine.currentModelId != model.id
        if (needsInit) {
            val statusEntry = History(
                role = History.Role.TOOL_EXECUTING,
                content = "",
                toolName = "Initializing ${model.displayName}",
                isStatusMessage = true,
            )
            history.update { it + statusEntry }
            try {
                engine.initialize(model, contextTokens)
            } finally {
                history.update { h -> h.filter { it.id != statusEntry.id } }
            }
        } else {
            engine.initialize(model, contextTokens)
        }

        // Callers pass either a CHAT_LOCAL system prompt (chat + silent paths) or null
        // (Splinterlands via `askSilentlyWithInstance`, where the caller owns the full
        // prompt shape). We hand whichever one through to the engine unchanged.
        // Native litert-lm `automaticToolCalling` owns the tool loop — our allowlisted
        // tools are passed once via [localToolDescriptionJson] and the engine drives them.
        val localTools: List<LocalTool> = if (localModelDeclaresTools(model.id)) {
            getLocalSafeTools().map { tool ->
                LocalTool(
                    name = tool.schema.name,
                    descriptionJsonString = localToolDescriptionJson(tool),
                    execute = { jsonArgs -> runLocalToolWithUiFeedback(tool.schema.name, jsonArgs, history) },
                )
            }
        } else {
            emptyList()
        }

        val inferenceMessages = messages.mapNotNull { msg ->
            when (msg.role) {
                History.Role.USER -> InferenceMessage(role = "user", content = msg.content)
                History.Role.ASSISTANT -> InferenceMessage(role = "assistant", content = msg.content)
                else -> null
            }
        }

        return try {
            engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = localTools)
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // litert-lm's strict ANTLR function-call parser sometimes rejects malformed
            // tool-call output from small Gemma models, throwing INVALID_ARGUMENT from JNI.
            // Retry once without tools so the user gets *some* answer rather than a hard
            // error in the UI. With an empty tool list, LiteRTInferenceEngine sets
            // automaticToolCalling = false, so the parser is bypassed entirely on the retry.
            println("LiteRT: tool-call parser failed (${e.message?.take(200)}). Falling back to plain chat.")
            engine.chat(messages = inferenceMessages, systemPrompt = systemPrompt, tools = emptyList())
        }
    }

    /**
     * Cached OpenAPI/OpenAI-style JSON descriptions for local tools, keyed by tool name.
     * Schemas are static for allowlisted tools, so serializing them once per tool avoids
     * re-running the JSON builder on every message.
     */
    private val localToolDescriptionJsonCache = mutableMapOf<String, String>()

    /**
     * Returns the cached OpenAPI/OpenAI-style JSON description for [tool], building it on
     * first request. Shape mirrors `Tool.toRequestTool()` in `Requests.kt` without the
     * OpenAI `{type: "function", function: {…}}` wrapper, so litert-lm's `OpenApiTool`
     * adapter can forward it straight to the model. If a parameter has a `rawSchema`,
     * it's passed through verbatim — that preserves array/enum/nested-object shapes the
     * simple `{type, description}` form would lose.
     */
    private fun localToolDescriptionJson(tool: Tool): String = localToolDescriptionJsonCache.getOrPut(tool.schema.name) {
        buildJsonObject {
            put("name", tool.schema.name)
            put("description", tool.schema.description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    for ((paramName, param) in tool.schema.parameters) {
                        val raw = param.rawSchema
                        if (raw != null) {
                            put(paramName, raw)
                        } else {
                            putJsonObject(paramName) {
                                put("type", param.type)
                                put("description", param.description)
                            }
                        }
                    }
                }
                putJsonArray("required") {
                    tool.schema.parameters.filter { it.value.required }.keys.forEach { add(it) }
                }
            }
        }.toString()
    }

    /**
     * Runs a single tool invocation requested by the on-device engine, mirroring the UI
     * flow used by [executeToolCallsInParallel]: write the assistant tool-call row, show a
     * TOOL_EXECUTING indicator (with a 2 s minimum so it's visible), execute the tool, then
     * replace the indicator with a TOOL result row. Returns the raw result string for the
     * engine to feed back to the model.
     */
    private suspend fun runLocalToolWithUiFeedback(
        name: String,
        arguments: String,
        history: MutableStateFlow<List<History>>,
    ): String {
        val callId = "local-${Uuid.random()}"
        val executingId = Uuid.random().toString()
        val displayName = toolExecutor.getToolDisplayName(name)
        // Append the assistant tool-call row and the executing indicator in a single
        // StateFlow update so the UI doesn't flash twice before the tool even starts.
        history.update {
            it.toMutableList().apply {
                add(
                    History(
                        role = History.Role.ASSISTANT,
                        content = "",
                        toolCalls = persistentListOf(
                            ToolCallInfo(id = callId, name = name, arguments = arguments),
                        ),
                    ),
                )
                add(
                    History(
                        id = executingId,
                        role = History.Role.TOOL_EXECUTING,
                        content = name,
                        toolName = displayName,
                    ),
                )
            }
        }
        val startTime = Clock.System.now().toEpochMilliseconds()
        // Background runs must not leak shell commands into whatever chat is open.
        val conversationIdForTool = activeConversationId()
        try {
            val result = try {
                toolExecutor.executeTool(name, arguments, conversationIdForTool, interactive = isInteractiveRun())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                """{"success": false, "error": "${e.message ?: "Tool execution failed"}"}"""
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay(MIN_TOOL_DISPLAY_MS.milliseconds - elapsed.milliseconds)
            }
            history.update { h ->
                buildList(h.size) {
                    for (entry in h) {
                        if (entry.id != executingId) add(entry)
                    }
                    add(
                        History(
                            role = History.Role.TOOL,
                            content = result,
                            toolCallId = callId,
                            toolName = name,
                        ),
                    )
                }
            }
            return result
        } finally {
            // On cancellation the fused update above never ran — drop the stranded
            // indicator. On the success path this is a no-op (id already removed).
            history.update { h ->
                if (h.none { it.id == executingId }) h else h.filter { it.id != executingId }
            }
        }
    }

    private suspend fun askWithService(
        service: Service,
        messages: List<History>,
        systemPrompt: String?,
        instanceId: String,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        if (service.isOnDevice) {
            // No retry: local-inference failures are deterministic, and this path mutates
            // chat history through tool execution, so a replay could re-run tools.
            // Re-fetch the system prompt with the CHAT_LOCAL variant — the caller
            // (`ask()`/`askWithTools()`) pre-fetched a CHAT_REMOTE prompt, but on-device
            // needs the trimmed variant.
            val localPrompt = getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL)
            return AssistantTurn(askWithLocalEngine(messages, localPrompt, instanceId, history))
        }

        val creds = instanceCredentials(instanceId, service)
        val tools = if (supportsTools(creds.modelId)) getAvailableTools() else emptyList()

        if (tools.isEmpty()) {
            return plainChat(service, creds, messages, systemPrompt, strictEmptyResponse = true)
        }

        return when (service) {
            Service.Gemini -> handleGeminiChatWithTools(creds, messages, tools, systemPrompt, history)
            Service.Anthropic -> handleAnthropicChatWithTools(creds, messages, tools, systemPrompt, history)
            else -> handleOpenAICompatibleChatWithTools(service, creds, messages, tools, systemPrompt, history)
        }
    }

    /**
     * The conversation a request belongs to: an explicit coroutine-context id (set by
     * askWithTools for heartbeat / scheduled runs) wins over the globally active chat id, so
     * background runs aren't attributed to whatever chat the user is viewing. Null before any
     * conversation exists. Used as the upstream session id for providers that require one.
     */
    private suspend fun activeConversationId(): String? = currentConversationIdOrNull() ?: _currentConversationId.value

    /**
     * One assistant turn with no tools declared, dispatched to whichever API [service] speaks.
     * The three providers differ only in message shape and text extraction, so every caller
     * needing a plain completion — the no-tools path above, the silent asks, and the
     * Gemini/Anthropic tool-loop bailouts — funnels through here.
     *
     * [retry] wraps the call in [retryApiCall]; silent callers skip it because they run on a
     * caller-supplied deadline. [strictEmptyResponse] turns a missing OpenAI-compatible message
     * or content into [OpenAICompatibleEmptyResponseException] — the visible chat path wants that
     * error surfaced, silent callers prefer empty text.
     */
    private suspend fun plainChat(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<History>,
        systemPrompt: String?,
        requestTimeoutMs: Long? = null,
        retry: Boolean = true,
        strictEmptyResponse: Boolean = false,
    ): AssistantTurn {
        suspend fun <T> call(block: suspend () -> T): T = if (retry) retryApiCall(block) else block()

        return when (service) {
            Service.Gemini -> {
                val response = call {
                    requests.geminiChat(
                        credentials = credentials,
                        messages = messages.map { it.toGeminiMessageDto() },
                        systemInstruction = systemPrompt,
                        requestTimeoutMs = requestTimeoutMs,
                    ).getOrThrow()
                }
                AssistantTurn(response.extractText())
            }

            Service.Anthropic -> {
                val response = call {
                    requests.anthropicChat(
                        credentials = credentials,
                        messages = buildAnthropicMessages(messages),
                        systemInstruction = systemPrompt,
                        requestTimeoutMs = requestTimeoutMs,
                    ).getOrThrow()
                }
                AssistantTurn(response.extractText())
            }

            else -> {
                // No tools on this request — strip any historic tool_calls so Groq's strict
                // validator doesn't see calls to tools we no longer declare.
                val openAIMessages = buildOpenAIMessages(service, messages, systemPrompt, credentials.modelId, declaredToolNames = emptySet())
                if (requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)) {
                    val response = call {
                        requests.openAIResponses(service, credentials, toResponsesInput(openAIMessages), requestTimeoutMs = requestTimeoutMs, sessionId = activeConversationId()).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val content = response.outputText
                    if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                    return AssistantTurn(content.orEmpty(), response.reasoningSummary)
                }
                if (requiresMessagesApi(service, credentials.modelId, credentials.baseUrl)) {
                    val response = call {
                        requests.gatewayMessages(
                            service,
                            credentials,
                            messages = buildAnthropicMessages(messages),
                            systemInstruction = systemPrompt,
                            sessionId = activeConversationId(),
                            requestTimeoutMs = requestTimeoutMs,
                        ).getOrThrow()
                    }
                    return AssistantTurn(response.extractText())
                }
                val sessionId = activeConversationId()
                val response = call {
                    requests.openAICompatibleChat(service, credentials, openAIMessages, sessionId = sessionId, requestTimeoutMs = requestTimeoutMs).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message
                val content = message?.effectiveContent
                if (content == null && strictEmptyResponse) throw OpenAICompatibleEmptyResponseException()
                AssistantTurn(content.orEmpty(), message?.reasoningTraceFor(content))
            }
        }
    }

    private fun hasValidInstanceApiKey(instanceId: String, service: Service): Boolean {
        if (service == Service.Free) return true
        if (service.isOnDevice) return true
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return true
        if (service.requiresApiKey) return appSettings.getInstanceApiKey(instanceId).isNotBlank()
        return true // Optional API key services are always valid
    }

    private data class FallbackEntry(val instanceId: String, val service: Service)

    private fun getOrderedFallbackEntries(): List<FallbackEntry> {
        val instances = getConfiguredServiceInstances()
        val entries = instances.map { FallbackEntry(instanceId = it.instanceId, service = Service.fromId(it.serviceId)) }
            .filter { it.service != Service.Free }
            .filter { !it.service.isOnDevice || localInferenceEngine != null }
        val freeEntry = FallbackEntry(instanceId = "free", service = Service.Free)
        val ordered = if (entries.isEmpty()) {
            listOf(freeEntry)
        } else if (appSettings.isFreeServicePrimary()) {
            listOf(freeEntry) + entries
        } else if (appSettings.isFreeFallbackEnabled()) {
            entries + freeEntry
        } else {
            entries
        }
        // On-device models are only tried as the primary service, never as a fallback
        // target — falling into one would silently start a heavy model load the user
        // didn't ask for (mirrors the guard that keeps on-device errors from silently
        // falling back to cloud services).
        return ordered.filterIndexed { index, entry -> index == 0 || !entry.service.isOnDevice }
    }

    override suspend fun ask(
        question: String?,
        files: List<PlatformFile>,
        uiSubmission: UiSubmission?,
        activeSkillId: String?,
        conversationIdOverride: String?,
    ) {
        // The active skill (if any) is consumed for this single turn only — stored in a
        // field rather than a parameter on getActiveSystemPrompt so the existing internal
        // callers (heartbeat, askWithTools, etc.) don't all need a new parameter. The
        // skill's files already live in the sandbox at ~/skills/<id>/, so nothing is
        // materialized here.
        val resolvedSkillId = activeSkillId?.takeIf { skillManager.getSkill(it) != null }
        pendingActiveSkillId = resolvedSkillId
        try {
            runChat(question, files, uiSubmission, conversationIdOverride)
        } finally {
            pendingActiveSkillId = null
            // Any turn may have written ~/skills/<id>/SKILL.md via the shell
            // (create-skill, manual git clones, user edits through the agent);
            // rescan so new skills show up in the slash menu and Settings
            // without a restart. Cheap (one listDirectory) and idempotent.
            skillManager.load()
        }
    }

    private var pendingActiveSkillId: String? = null

    /**
     * Runs one user turn against its own conversation, detached from the view: the run
     * keeps its own message flow, mirrors it into [chatHistory] only while the user is
     * looking at that conversation, and persists incrementally so the conversation stays
     * reachable (and shows a running badge) even after the user navigates away.
     */
    private suspend fun runChat(
        question: String?,
        files: List<PlatformFile>,
        uiSubmission: UiSubmission?,
        conversationIdOverride: String?,
    ) {
        val conversationId = conversationIdOverride ?: ensureCurrentConversationId()
        val runHistory = MutableStateFlow(
            if (_currentConversationId.value == conversationId) chatHistory.value else historyForConversation(conversationId),
        )
        // One run per conversation. The UI blocks re-sends while running; this is the
        // repository-level guard for any other caller. compareAndSet keeps the
        // check-and-register atomic across run threads.
        while (true) {
            val current = runHistoriesByConversation.value
            if (current.containsKey(conversationId)) return
            if (runHistoriesByConversation.compareAndSet(current, current + (conversationId to runHistory))) break
        }
        _runningConversationIds.update { it + conversationId }

        val mirrorJob = runScope.launch {
            runHistory.collect { messages ->
                if (_currentConversationId.value == conversationId) chatHistory.value = messages
            }
        }
        var lastSavedAtMs = 0L
        val saveJob = runScope.launch {
            runHistory.collect { messages ->
                val now = Clock.System.now().toEpochMilliseconds()
                if (now - lastSavedAtMs >= RUN_SAVE_THROTTLE_MS) {
                    lastSavedAtMs = now
                    persistConversation(conversationId, messages)
                }
            }
        }
        val runStartIndex = runHistory.value.size
        var runFailure: Exception? = null
        try {
            // A chat run is interactive: risky tools may ask the user for approval through
            // the dialog the app root shows. Scheduled/heartbeat runs deliberately do not
            // carry this element (see `askWithTools`).
            withContext(
                ConversationIdElement(conversationId) + ChatRunHistoryElement(runHistory) + ToolInteractionElement(interactive = true),
            ) {
                askInternal(question, files, uiSubmission, conversationId)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            runFailure = e
        } finally {
            mirrorJob.cancel()
            saveJob.cancel()
            persistConversation(conversationId, runHistory.value)
            runHistoriesByConversation.update { it - conversationId }
            _runningConversationIds.update { it - conversationId }
        }
        // A turn that drove another app leaves the user looking at that app; bring
        // Kai forward so they see the summary (success or failure), not WhatsApp.
        if (automationWasUsed(runHistory.value, runStartIndex)) {
            runCatching { returnToKaiAfterAutomation() }
        }
        runFailure?.let { throw it }
    }

    /** Stored messages of a conversation, for a run that begins while the user is elsewhere. */
    private fun historyForConversation(id: String): List<History> = savedConversations.value.find { it.id == id }?.messages?.map { it.toHistory() }.orEmpty()

    private suspend fun askInternal(
        question: String?,
        files: List<PlatformFile>,
        uiSubmission: UiSubmission?,
        conversationId: String,
    ) {
        val history = currentRunHistoryOrNull() ?: chatHistory
        // Process every attached file: classify, compress/encode, and build an Attachment.
        // readBytes() is suspend, so this happens before the StateFlow.update block.
        val attachments = files.map { file ->
            val fileMimeType = file.mimeType()?.toString()
            val fileName = file.name

            val category = classifyFile(fileMimeType, fileName)
            if (category == FileCategory.UNSUPPORTED) throw UnsupportedFileTypeException()

            // Reject oversized files by stat size before readBytes(), which would otherwise
            // allocate a ByteArray large enough to OOM the process on multi-GB inputs.
            val rawSizeLimit = when (category) {
                FileCategory.TEXT -> MAX_TEXT_FILE_BYTES.toLong()
                FileCategory.PDF -> MAX_PDF_BYTES.toLong()
                FileCategory.IMAGE -> MAX_RAW_IMAGE_BYTES.toLong()
                FileCategory.UNSUPPORTED -> 0L
            }
            if (file.size() > rawSizeLimit) throw FileTooLargeException()

            val rawBytes = file.readBytes()

            when (category) {
                FileCategory.IMAGE -> {
                    val compressed = compressImageBytes(rawBytes, fileMimeType ?: "image/jpeg")
                    // compressImageBytes can fall back to the original bytes on failure or on
                    // platforms without compression — guard against Base64 OOM for oversized input.
                    if (compressed.size > MAX_IMAGE_BYTES) throw FileTooLargeException()
                    Attachment(
                        data = Base64.encode(compressed),
                        mimeType = "image/jpeg",
                        fileName = null,
                    )
                }

                FileCategory.TEXT -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = fileMimeType ?: "text/plain",
                    fileName = fileName,
                )

                FileCategory.PDF -> Attachment(
                    data = Base64.encode(rawBytes),
                    mimeType = "application/pdf",
                    fileName = fileName,
                )

                FileCategory.UNSUPPORTED -> throw UnsupportedFileTypeException()
            }
        }.toImmutableList()

        if (question != null) {
            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.USER,
                            content = question,
                            attachments = attachments,
                            uiSubmission = uiSubmission,
                        ),
                    )
                }
            }
            // Persist before the API call so the conversation (and the user's message)
            // exists in the history list while the run is still in flight.
            persistConversation(conversationId, history.value)
        }

        compactHistoryIfNeeded(history)

        val messages = history.value
        val systemPrompt = getActiveSystemPrompt()

        val fallbackEntries = getOrderedFallbackEntries().filter { hasValidInstanceApiKey(it.instanceId, it.service) }

        val historyChars = messages.sumOf { it.content.length } + (systemPrompt?.length ?: 0)

        var lastException: Exception? = null
        var fallbackServiceName: String? = null

        try {
            for ((index, entry) in fallbackEntries.withIndex()) {
                // Skip fallback services whose context window is too small for the current history
                // On-device models handle their own context limits, so skip this check for them
                if (!entry.service.isOnDevice) {
                    val creds = instanceCredentials(entry.instanceId, entry.service)
                    val entryWindowChars = ModelCatalog.estimateContextWindow(creds.modelId) * ESTIMATED_CHARS_PER_TOKEN
                    if (historyChars > entryWindowChars) {
                        lastException = ContextWindowExceededException()
                        _fallbackStatus.value = FallbackStatus(
                            serviceName = entry.service.displayName,
                            errorReason = ContextWindowExceededException().toUiError(),
                            nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                        )
                        continue
                    }
                }

                // No retry wrapper here: each network call retries inside askWithService.
                // Retrying the whole call would re-enter the tool loop against a chat
                // history already mutated by the failed attempt.
                val turn = try {
                    askWithService(entry.service, messages, systemPrompt, entry.instanceId, history)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    // On-device services should not silently fall back — surface the error
                    if (entry.service.isOnDevice) throw e
                    lastException = e
                    _fallbackStatus.value = FallbackStatus(
                        serviceName = entry.service.displayName,
                        errorReason = e.toUiError(),
                        nextServiceName = fallbackEntries.getOrNull(index + 1)?.service?.displayName,
                    )
                    continue
                }
                if (index > 0) {
                    fallbackServiceName = entry.service.displayName
                }
                history.update {
                    it.toMutableList().apply {
                        add(
                            History(
                                role = History.Role.ASSISTANT,
                                content = turn.content,
                                reasoningContent = turn.reasoningContent,
                                fallbackServiceName = fallbackServiceName,
                            ),
                        )
                    }
                }
                persistConversation(conversationId, history.value)
                return
            }

            throw if (fallbackEntries.size > 1 && lastException != null) {
                AllServicesFailedException()
            } else {
                lastException ?: OpenAICompatibleEmptyResponseException()
            }
        } finally {
            _fallbackStatus.value = null
        }
    }

    private suspend fun handleOpenAICompatibleChatWithTools(
        service: Service,
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val declaredToolNames = tools.map { it.schema.name }.toSet()
        // GPT-5.6 and friends reject function tools on chat completions; the same messages are
        // translated to Responses API items instead. Everything before the wire call — prompt
        // assembly, tool-call pairing, context trimming — is shared. The OpenCode gateway
        // additionally serves some families (Zen's Claude/Qwen, Go's Claude/Qwen/MiniMax) on an
        // Anthropic Messages endpoint; those take the same Anthropic loop shape instead.
        val useResponsesApi = requiresResponsesApi(service, credentials.modelId, credentials.baseUrl)
        val useMessagesApi = !useResponsesApi && requiresMessagesApi(service, credentials.modelId, credentials.baseUrl)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                if (useMessagesApi) {
                    val response = retryApiCall {
                        requests.gatewayMessages(
                            service,
                            credentials,
                            messages = buildAnthropicMessages(history),
                            tools = tools,
                            systemInstruction = systemPrompt,
                            sessionId = activeConversationId(),
                        ).getOrThrow()
                    }
                    val toolUseBlocks = response.content.filter { it.type == "tool_use" }
                    val toolCallInfos = toolUseBlocks.map { block ->
                        val argsJson = block.input?.toString() ?: "{}"
                        ToolCallInfo(
                            id = block.id ?: "gateway-${Uuid.random()}",
                            name = block.name ?: "unknown",
                            arguments = argsJson,
                        )
                    }
                    val textContent = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                    return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos)
                }
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames), contextWindowTokens)
                if (useResponsesApi) {
                    val response = retryApiCall {
                        requests.openAIResponses(service, credentials, toResponsesInput(msgs), tools, sessionId = activeConversationId()).getOrThrow()
                    }
                    response.throwIfFailed(service)
                    val text = response.outputText
                    val calls = response.functionCalls.map { fc ->
                        ToolCallInfo(
                            id = fc.callId ?: Uuid.random().toString(),
                            name = fc.name.orEmpty(),
                            arguments = fc.arguments ?: "{}",
                        )
                    }
                    if (text == null && calls.isEmpty()) throw OpenAICompatibleEmptyResponseException()
                    return LoopChatResult(
                        textContent = text.orEmpty(),
                        reasoningContent = response.reasoningSummary,
                        toolCalls = calls,
                    )
                }
                val sessionId = activeConversationId()
                val response = retryApiCall {
                    requests.openAICompatibleChat(service, credentials, msgs, tools, sessionId = sessionId).getOrThrow()
                }
                val message = response.choices.firstOrNull()?.message ?: throw OpenAICompatibleEmptyResponseException()
                var calls = message.toolCalls.orEmpty().map { tc ->
                    ToolCallInfo(id = tc.id, name = tc.function.name, arguments = tc.function.arguments)
                }
                var textContent = message.effectiveContent ?: ""
                if (calls.isEmpty() && textContent.contains("<tool_call>")) {
                    val extracted = extractInlineToolCalls(textContent, tools)
                    if (extracted.calls.isNotEmpty()) {
                        textContent = extracted.cleanedText
                        calls = extracted.calls.map {
                            ToolCallInfo(
                                id = "inline-${Uuid.random()}",
                                name = it.name,
                                arguments = it.arguments,
                            )
                        }
                    }
                }
                return LoopChatResult(
                    textContent = textContent,
                    reasoningContent = message.reasoningTraceFor(textContent),
                    isThinkingContent = message.isContentFromReasoning,
                    toolCalls = calls,
                )
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String {
                // The gateway Messages path has no OpenAI-shaped bailout call — plainChat routes
                // back onto it automatically for these models.
                if (useMessagesApi) return plainChat(service, credentials, history, "${bailoutPrompt(reason)} $systemPrompt").content
                // Bailout sends no tools — strip historic tool_calls to satisfy strict validators.
                val msgs = trimMessagesForContext(buildOpenAIMessages(service, history, systemPrompt, credentials.modelId, declaredToolNames = emptySet()), contextWindowTokens)
                return makeFinalCallWithoutTools(service, credentials, msgs, reason, useResponsesApi)
            }
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleGeminiChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                val geminiMessages = history.map { it.toGeminiMessageDto() }
                val response = retryApiCall {
                    requests.geminiChat(
                        credentials = credentials,
                        messages = geminiMessages,
                        tools = tools,
                        systemInstruction = systemPrompt,
                    ).getOrThrow()
                }
                val parts = response.candidates.firstOrNull()?.content?.parts.orEmpty()
                val partsWithFunctionCalls = parts.filter { it.functionCall != null }
                val toolCallInfos = partsWithFunctionCalls.map { part ->
                    val fc = part.functionCall!!
                    val argsJson = fc.args?.let { JsonObject(it).toString() } ?: "{}"
                    ToolCallInfo(
                        id = "gemini-${Uuid.random()}",
                        name = fc.name,
                        arguments = argsJson,
                        thoughtSignature = part.thoughtSignature,
                    )
                }
                val textContent = parts.filterNot { it.isThought }.mapNotNull { it.text }.joinToString("\n")
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos)
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String = plainChat(Service.Gemini, credentials, history, "${bailoutPrompt(reason)} $systemPrompt").content

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun handleAnthropicChatWithTools(
        credentials: ServiceCredentials,
        @Suppress("UNUSED_PARAMETER") messages: List<History>,
        tools: List<Tool>,
        systemPrompt: String? = null,
        history: MutableStateFlow<List<History>> = chatHistory,
    ): AssistantTurn {
        val contextWindowTokens = ModelCatalog.estimateContextWindow(credentials.modelId)
        val strategy = object : ToolLoopStrategy {
            override suspend fun chat(history: List<History>, systemPrompt: String?): LoopChatResult {
                val msgs = buildAnthropicMessages(history)
                val response = retryApiCall {
                    requests.anthropicChat(
                        credentials = credentials,
                        messages = msgs,
                        tools = tools,
                        systemInstruction = systemPrompt,
                    ).getOrThrow()
                }
                val toolUseBlocks = response.content.filter { it.type == "tool_use" }
                val toolCallInfos = toolUseBlocks.map { block ->
                    val argsJson = block.input?.toString() ?: "{}"
                    ToolCallInfo(
                        id = block.id ?: "anthropic-${Uuid.random()}",
                        name = block.name ?: "unknown",
                        arguments = argsJson,
                    )
                }
                val textContent = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n")
                return LoopChatResult(textContent = textContent, toolCalls = toolCallInfos)
            }

            override suspend fun bailout(history: List<History>, systemPrompt: String?, reason: BailoutReason): String = plainChat(Service.Anthropic, credentials, history, "${bailoutPrompt(reason)} $systemPrompt").content

            override val historyContextWindowTokens = contextWindowTokens
        }
        return runToolLoop(strategy, systemPrompt, history)
    }

    private suspend fun runToolLoop(
        strategy: ToolLoopStrategy,
        systemPrompt: String?,
        history: MutableStateFlow<List<History>>,
    ): AssistantTurn {
        var iteration = 0
        val recentSignatures = mutableListOf<String>()
        var consecutiveFailingBatches = 0
        val maxSteps = appSettings.getMaxToolSteps().coerceIn(AppSettings.MIN_TOOL_STEPS, AppSettings.MAX_TOOL_STEPS)
        while (true) {
            iteration++
            val visible = history.value.filter { it.role != History.Role.TOOL_EXECUTING }
            if (iteration > maxSteps) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.LIMIT_REACHED))
            }
            val result = strategy.chat(visible, systemPrompt)
            if (result.toolCalls.isEmpty()) {
                // For thinking-only turns, the reasoning text already became the content via
                // `isContentFromReasoning`, so don't surface it again as a reasoning trace.
                val reasoning = result.reasoningContent?.takeIf { !result.isThinkingContent }
                return AssistantTurn(result.textContent, reasoning)
            }

            val signatures = result.toolCalls.map { "${it.name}:${it.arguments.hashCode()}" }
            if (isRepeatingToolCalls(recentSignatures, signatures)) {
                return AssistantTurn(strategy.bailout(visible, systemPrompt, BailoutReason.REPEATING))
            }
            recentSignatures.addAll(signatures)

            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            role = History.Role.ASSISTANT,
                            content = result.textContent,
                            isThinking = result.isThinkingContent,
                            toolCalls = result.toolCalls.toImmutableList(),
                            reasoningContent = result.reasoningContent,
                        ),
                    )
                }
            }

            val toolResults = executeToolCallsInParallel(
                result.toolCalls.map { Triple(it.id, it.name, it.arguments) },
            )

            history.update { h ->
                val merged = buildList(h.size + toolResults.size) {
                    for (entry in h) {
                        if (entry.role != History.Role.TOOL_EXECUTING) add(entry)
                    }
                    for ((callId, name, content) in toolResults) {
                        add(
                            History(
                                role = History.Role.TOOL,
                                content = content,
                                toolCallId = callId,
                                toolName = name,
                            ),
                        )
                    }
                }
                strategy.historyContextWindowTokens
                    ?.let { trimHistoryForContext(merged, systemPrompt?.length ?: 0, it) }
                    ?: merged
            }

            // Reflection: when whole batches of tool calls keep failing, prompt the
            // model to step back and re-inspect rather than retrying blindly.
            val batchFailed = toolResults.isNotEmpty() && toolResults.all { looksLikeToolFailure(it.third) }
            consecutiveFailingBatches = if (batchFailed) consecutiveFailingBatches + 1 else 0
            if (consecutiveFailingBatches >= MAX_CONSECUTIVE_FAILING_TOOL_BATCHES) {
                consecutiveFailingBatches = 0
                history.update {
                    it + History(
                        role = History.Role.USER,
                        content = "[SYSTEM] The last tool batches all failed. Stop and reflect: re-read the error messages, " +
                            "re-inspect the screen (ui_dump / ui_screenshot), and choose a different approach. " +
                            "Do not repeat the same call.",
                    )
                }
            }
        }
    }

    /**
     * Detects if the current batch of tool calls is repeating a recent pattern.
     */
    private fun isRepeatingToolCalls(recentSignatures: List<String>, currentSignatures: List<String>): Boolean {
        if (currentSignatures.isEmpty()) return false
        // Count how many consecutive times the same signature set appeared at the tail
        val batchSize = currentSignatures.size
        var consecutiveCount = 0
        var i = recentSignatures.size - batchSize
        while (i >= 0) {
            val slice = recentSignatures.subList(i, i + batchSize)
            if (slice == currentSignatures) {
                consecutiveCount++
                i -= batchSize
            } else {
                break
            }
        }
        // +1 for the current batch that's about to be executed
        return consecutiveCount + 1 >= MAX_REPEATED_TOOL_CALLS
    }

    /**
     * Makes a final OpenAI-compatible API call without tools, asking the model to summarize.
     */
    private suspend fun makeFinalCallWithoutTools(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        reason: BailoutReason,
        useResponsesApi: Boolean = false,
    ): String {
        val bailoutMessages = messages.toMutableList().apply {
            add(
                com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message(
                    role = "user",
                    content = JsonPrimitive(bailoutPrompt(reason)),
                ),
            )
        }
        if (useResponsesApi) {
            val response = retryApiCall {
                requests.openAIResponses(service, credentials, toResponsesInput(bailoutMessages), sessionId = activeConversationId()).getOrThrow()
            }
            response.throwIfFailed(service)
            return response.outputText.orEmpty()
        }
        val sessionId = activeConversationId()
        val response = retryApiCall {
            requests.openAICompatibleChat(service, credentials, bailoutMessages, sessionId = sessionId).getOrThrow()
        }
        return response.choices.firstOrNull()?.message?.effectiveContent ?: ""
    }

    /**
     * Executes tool calls in parallel, showing TOOL_EXECUTING indicators in the UI.
     * Returns a list of (callId, toolName, result).
     */
    private suspend fun executeToolCallsInParallel(
        toolCalls: List<Triple<String, String, String>>,
    ): List<Triple<String, String, String>> {
        // Write to the run's own history flow (falls back to the view for callers
        // outside a run) so progress can't land in a conversation the user switched to.
        val history = currentRunHistoryOrNull() ?: chatHistory
        // Add all TOOL_EXECUTING indicators first
        val executingIds = toolCalls.map { Uuid.random().toString() }
        for ((index, toolCall) in toolCalls.withIndex()) {
            val (_, name, _) = toolCall
            val toolDisplayName = toolExecutor.getToolDisplayName(name)
            history.update {
                it.toMutableList().apply {
                    add(
                        History(
                            id = executingIds[index],
                            role = History.Role.TOOL_EXECUTING,
                            content = name,
                            toolName = toolDisplayName,
                        ),
                    )
                }
            }
        }

        // Execute all tools concurrently, ensuring indicators show for at least 2 seconds.
        // Snapshot the conversation id once so all parallel tool calls in this batch
        // see a stable value even if the user switches conversations mid-flight.
        val conversationIdSnapshot = activeConversationId()
        // Risky tools may only ask for approval inside a chat run; the run's coroutine
        // context is the only place that knows whether a user is watching.
        val interactive = isInteractiveRun()
        val startTime = Clock.System.now().toEpochMilliseconds()
        try {
            val results = coroutineScope {
                toolCalls.map { (callId, name, arguments) ->
                    async {
                        val result = toolExecutor.executeTool(name, arguments, conversationIdSnapshot, interactive = interactive)
                        Triple(callId, name, result)
                    }
                }.awaitAll()
            }
            val elapsed = Clock.System.now().toEpochMilliseconds() - startTime
            if (elapsed < MIN_TOOL_DISPLAY_MS) {
                delay((MIN_TOOL_DISPLAY_MS - elapsed).milliseconds)
            }
            return results
        } finally {
            // Remove all TOOL_EXECUTING indicators — also on cancellation, so stopping a
            // run doesn't strand spinner rows in the chat. Non-suspending, safe in finally.
            history.update { current ->
                current.filter { h -> h.id !in executingIds }
            }
        }
    }

    private fun isNonRetryableException(e: Exception): Boolean = e is AnthropicInsufficientCreditsException || e is OpenAICompatibleQuotaExhaustedException

    /**
     * Retries an API call with simple exponential backoff.
     */
    private suspend fun <T> retryApiCall(block: suspend () -> T): T {
        var lastException: Exception? = null
        for (attempt in 0..MAX_API_RETRIES) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isNonRetryableException(e)) throw e
                lastException = e
                if (attempt < MAX_API_RETRIES) {
                    delay((attempt + 1).seconds)
                }
            }
        }
        throw lastException!!
    }

    private fun estimateMessageChars(msg: com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message): Int {
        val contentChars = when (val content = msg.content) {
            is JsonArray -> {
                // Vision messages: only count text parts, not base64 image data
                content.sumOf { element ->
                    val obj = element as? JsonObject
                    val type = (obj?.get("type") as? JsonPrimitive)?.content
                    if (type == "text") {
                        (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                    } else {
                        100 // Fixed small cost for image references
                    }
                }
            }

            is JsonPrimitive -> content.content.length

            else -> content?.toString()?.length ?: 0
        }
        return contentChars + msg.role.length
    }

    /**
     * Trims messages to fit within the estimated context window by dropping oldest messages
     * (keeping the system prompt and most recent messages).
     */
    private fun trimMessagesForContext(
        messages: List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = messages.sumOf { estimateMessageChars(it) }
        if (totalChars <= maxChars) return messages

        // Keep system prompt (first message if role is "system") and trim from oldest non-system
        val systemMessages = messages.takeWhile { it.role == "system" }
        val nonSystemMessages = messages.drop(systemMessages.size)

        val systemChars = systemMessages.sumOf { estimateMessageChars(it) }
        val availableChars = maxChars - systemChars

        // Group each assistant tool-call turn together with the tool responses that follow it so
        // trimming never strands one without the other. Strict OpenAI-compatible providers (e.g.
        // DeepSeek via OpenCode Zen) reject an assistant `tool_calls` message that isn't followed
        // by its tool responses, and a `tool` message without a preceding `tool_calls`.
        val groups = mutableListOf<List<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>>()
        var index = 0
        while (index < nonSystemMessages.size) {
            val msg = nonSystemMessages[index]
            if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
                var end = index + 1
                while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                    end++
                }
                groups.add(nonSystemMessages.subList(index, end).toList())
                index = end
            } else {
                groups.add(listOf(msg))
                index++
            }
        }

        // Keep whole groups from the end until we exceed the budget.
        val kept = mutableListOf<com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto.Message>()
        var usedChars = 0
        for (group in groups.asReversed()) {
            val groupChars = group.sumOf { estimateMessageChars(it) }
            if (usedChars + groupChars > availableChars) break
            kept.addAll(0, group)
            usedChars += groupChars
        }

        return systemMessages + kept
    }

    /**
     * Trims History entries to fit within the estimated context window by dropping oldest messages
     * (keeping the most recent). Used by Gemini and Anthropic tool loops where the system prompt
     * is sent separately (not as a message).
     */
    private fun trimHistoryForContext(
        history: List<History>,
        systemPromptChars: Int = 0,
        contextWindowTokens: Int = ModelCatalog.DEFAULT_CONTEXT_WINDOW_TOKENS,
    ): List<History> {
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        val totalChars = history.sumOf { it.content.length } + systemPromptChars
        if (totalChars <= maxChars) return history

        val availableChars = maxChars - systemPromptChars

        // Keep messages from the end until we exceed the budget
        val kept = mutableListOf<History>()
        var usedChars = 0
        for (msg in history.reversed()) {
            val msgChars = msg.content.length
            if (usedChars + msgChars > availableChars) break
            kept.add(0, msg)
            usedChars += msgChars
        }

        return kept
    }

    /**
     * Compacts chat history by summarizing older messages via an LLM call when the history
     * exceeds a percentage of the context window. Keeps recent exchanges verbatim and replaces
     * older ones with a single summary. Falls back to simple drop-oldest trimming on failure.
     */
    private suspend fun compactHistoryIfNeeded(historyFlow: MutableStateFlow<List<History>>) {
        // Use primary service's context window for compaction decisions
        val firstInstance = getConfiguredServiceInstances().firstOrNull() ?: return
        val service = Service.fromId(firstInstance.serviceId)
        val modelId = appSettings.getSelectedModelId(service)
        val contextWindowTokens = ModelCatalog.estimateContextWindow(modelId)

        val history = historyFlow.value.filter { it.role != History.Role.TOOL_EXECUTING }
        val systemPromptChars = getActiveSystemPrompt()?.length ?: 0
        val totalChars = history.sumOf { it.content.length } + systemPromptChars
        val maxChars = contextWindowTokens * ESTIMATED_CHARS_PER_TOKEN
        if (totalChars <= (maxChars * COMPACTION_THRESHOLD).toInt()) return

        // Split history: older messages to summarize, recent to keep verbatim
        val userIndices = history.mapIndexedNotNull { index, h ->
            if (h.role == History.Role.USER) index else null
        }
        if (userIndices.size <= COMPACTION_KEEP_RECENT) return
        val cutoffIndex = userIndices[userIndices.size - COMPACTION_KEEP_RECENT]
        val olderMessages = history.subList(0, cutoffIndex)
        val recentMessages = history.subList(cutoffIndex, history.size)

        if (olderMessages.isEmpty()) return

        // Build a transcript of the older messages for summarization
        val transcript = buildString {
            for (msg in olderMessages) {
                if (msg.role == History.Role.USER || msg.role == History.Role.ASSISTANT) {
                    val role = if (msg.role == History.Role.USER) "User" else "Assistant"
                    appendLine("$role: ${msg.content}")
                }
            }
        }

        val summaryPrompt = "Summarize this conversation concisely, preserving key facts, decisions, and any information the assistant would need to continue helping. Be brief but complete:\n\n$transcript"

        val summary = try {
            askSilently(summaryPrompt)
        } catch (_: Exception) {
            // Summarization failed — fall back to dropping old messages
            historyFlow.value = recentMessages
            return
        }

        val summaryEntry = History(
            role = History.Role.ASSISTANT,
            content = "[Conversation summary: $summary]",
        )

        historyFlow.value = listOf(summaryEntry) + recentMessages
    }

    private fun trimToRecentExchanges(history: List<History>, maxExchanges: Int): List<History> {
        val userIndices = history.mapIndexedNotNull { index, h ->
            if (h.role == History.Role.USER) index else null
        }
        if (userIndices.size <= maxExchanges) return history
        val cutoffIndex = userIndices[userIndices.size - maxExchanges]
        return history.subList(cutoffIndex, history.size)
    }

    /**
     * Persists [rawHistory] as [conversationId]'s messages. Safe to call repeatedly while
     * a run is in flight (throttled by the caller) and from a cancelled run's `finally`
     * — it is non-suspending so cancellation can't interrupt the write.
     */
    private fun persistConversation(conversationId: String, rawHistory: List<History>) {
        val history = trimToRecentExchanges(rawHistory, 20)
        if (history.isEmpty()) return

        val now = Clock.System.now().toEpochMilliseconds()
        val existingConversation = savedConversations.value.find { it.id == conversationId }

        val title = existingConversation?.title?.ifEmpty { null }
            ?: deriveTitle(history)
        val conversation = Conversation(
            id = conversationId,
            messages = history
                .filter { it.role != History.Role.TOOL_EXECUTING }
                .map { h ->
                    Conversation.Message(
                        id = h.id,
                        role = when (h.role) {
                            History.Role.USER -> "user"
                            History.Role.ASSISTANT -> "assistant"
                            History.Role.TOOL -> "tool"
                            History.Role.TOOL_EXECUTING -> "tool" // Should not happen due to filter
                        },
                        content = h.content,
                        attachments = h.attachments,
                        uiSubmission = h.uiSubmission,
                        isThinking = h.isThinking,
                        reasoningContent = h.reasoningContent,
                    )
                },
            createdAt = existingConversation?.createdAt ?: now,
            updatedAt = now,
            title = title,
            type = existingConversation?.type ?: if (interactiveModeFlag) Conversation.TYPE_INTERACTIVE else Conversation.TYPE_CHAT,
        )

        conversationStorage.saveConversation(conversation)
    }

    override fun clearHistory() {
        chatHistory.update {
            emptyList()
        }
    }

    override fun isUsingSharedKey(): Boolean = currentService() == Service.Free

    override fun supportedFileExtensions(): List<String> {
        val service = currentService()
        if (service.isOnDevice) return emptyList()
        // Images are offered only when both the service and the active model accept them —
        // mixed services (e.g. Z.AI) pair text-only models with multimodal ones.
        val imagesSupported = service.supportsImages && modelSupportsImages(currentModelId())
        val base = if (imagesSupported) supportedFileExtensions else supportedFileExtensions - imageExtensions
        return if (service.supportsPdf) base + "pdf" else base
    }

    override fun currentService(): Service {
        if (appSettings.isFreeServicePrimary()) return Service.Free
        val instances = getConfiguredServiceInstances()
        return instances.firstOrNull()?.let { Service.fromId(it.serviceId) } ?: Service.Free
    }

    /** Model id of the active (first configured, non-Free) instance, or "" when none. */
    private fun currentModelId(): String {
        val instance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val service = Service.fromId(instance.serviceId)
        return appSettings.getInstanceEffectiveModelId(instance.instanceId).ifEmpty { appSettings.getSelectedModelId(service) }
    }

    private fun setCurrentConversationId(id: String?) {
        _currentConversationId.value = id
        appSettings.setCurrentConversationId(id)
    }

    override fun ensureCurrentConversationId(): String {
        _currentConversationId.value?.let { return it }
        val id = Uuid.random().toString()
        setCurrentConversationId(id)
        return id
    }

    override fun conversationHistory(id: String): List<History> {
        runHistoriesByConversation.value[id]?.let { return it.value }
        if (_currentConversationId.value == id) return chatHistory.value
        return historyForConversation(id)
    }

    // Conversation management
    override fun loadConversations() {
        conversationStorage.loadConversations()
    }

    override fun loadConversation(id: String) {
        // Prefer the live run flow when one exists, so reopening a running
        // conversation shows its progress rather than the last persisted snapshot.
        runHistoriesByConversation.value[id]?.let { running ->
            setCurrentConversationId(id)
            chatHistory.value = running.value
            return
        }

        val conversation = savedConversations.value.find { it.id == id } ?: return

        setCurrentConversationId(id)
        chatHistory.value = conversation.messages.map { it.toHistory() }
    }

    /**
     * Maps a persisted message to the in-memory [History]. Prefers the modern
     * `attachments` field; falls back to the legacy single-file fields for
     * conversations saved before multi-attachment support.
     */
    private fun Conversation.Message.toHistory(): History {
        val resolvedAttachments = when {
            attachments.isNotEmpty() -> attachments.toImmutableList()

            data != null && mimeType != null ->
                persistentListOf(Attachment(data = data, mimeType = mimeType, fileName = fileName))

            else -> persistentListOf()
        }
        return History(
            id = id,
            role = when (role) {
                "user" -> History.Role.USER
                "tool" -> History.Role.TOOL
                else -> History.Role.ASSISTANT
            },
            content = content,
            attachments = resolvedAttachments,
            uiSubmission = uiSubmission,
            isThinking = isThinking,
            reasoningContent = reasoningContent,
        )
    }

    override suspend fun deleteConversation(id: String) {
        if (_currentConversationId.value == id) {
            setCurrentConversationId(null)
            chatHistory.value = emptyList()
        }
        runHistoriesByConversation.update { it - id }
        conversationStorage.deleteConversation(id)
        // Drop the per-conversation shell session so a future conversation reusing
        // this id (very unlikely — random uuids) doesn't inherit stale state, and
        // memory is freed.
        sandboxController.closeSession(id)
    }

    override fun regenerate() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) {
                history.subList(0, lastUserIndex + 1)
            } else {
                history
            }
        }
    }

    override fun startNewChat() {
        setCurrentConversationId(null)
        chatHistory.value = emptyList()
    }

    override fun popLastExchange() {
        chatHistory.update { history ->
            val lastUserIndex = history.indexOfLast { it.role == History.Role.USER }
            if (lastUserIndex >= 0) history.take(lastUserIndex) else history
        }
    }

    override fun truncateFrom(messageId: String) {
        chatHistory.update { history ->
            val index = history.indexOfFirst { it.id == messageId }
            if (index >= 0) history.take(index) else history
        }
    }

    override fun restoreCurrentConversation() {
        // One-time migration for existing users: pin the latest conversation as the new
        // "current" pointer so the upgrade is non-disruptive.
        if (!appSettings.isCurrentConversationMigrated()) {
            val latest = savedConversations.value.maxByOrNull { it.updatedAt }
            if (latest != null) {
                loadConversation(latest.id)
            }
            appSettings.markCurrentConversationMigrated()
            return
        }

        // Already-loaded guard (covers re-entry from refreshSettings)
        val currentId = _currentConversationId.value
        if (currentId != null && chatHistory.value.isNotEmpty() &&
            savedConversations.value.any { it.id == currentId }
        ) {
            return
        }

        val persistedId = appSettings.getCurrentConversationId()
        if (persistedId != null && savedConversations.value.any { it.id == persistedId }) {
            loadConversation(persistedId)
        }
        // else: null id or stale id → leave history empty (this is the new-empty-chat state)
    }

    // Tool management
    override fun getToolDefinitions(): List<ToolInfo> = getPlatformToolDefinitions()
        .filter { it.userToggleable }
        .map { it.copy(isEnabled = appSettings.isToolEnabled(it.id, defaultEnabled = it.isEnabled)) }

    override fun setToolEnabled(toolId: String, enabled: Boolean) {
        appSettings.setToolEnabled(toolId, enabled)
    }

    override fun getMaxToolSteps(): Int = appSettings.getMaxToolSteps()

    override fun setMaxToolSteps(steps: Int) {
        appSettings.setMaxToolSteps(steps)
    }

    override fun isRiskyToolsAutoApprove(): Boolean = appSettings.isRiskyToolsAutoApprove()

    override fun setRiskyToolsAutoApprove(autoApprove: Boolean) {
        appSettings.setRiskyToolsAutoApprove(autoApprove)
    }

    // MCP servers
    override fun getMcpServers(): List<McpServerConfig> = mcpServerManager.getServers()

    override suspend fun addMcpServer(name: String, url: String, headers: Map<String, String>): McpServerConfig = mcpServerManager.addServer(name, url, headers)

    override fun removeMcpServer(serverId: String) {
        mcpServerManager.removeServer(serverId)
    }

    override fun setMcpServerEnabled(serverId: String, enabled: Boolean) {
        mcpServerManager.setServerEnabled(serverId, enabled)
    }

    override suspend fun connectMcpServer(serverId: String): Result<List<ToolInfo>> {
        val result = mcpServerManager.connectAndDiscoverTools(serverId)
        return result.map { mcpServerManager.getToolsForServer(serverId) }
    }

    override fun getMcpToolsForServer(serverId: String): List<ToolInfo> = mcpServerManager.getToolsForServer(serverId)

    override suspend fun fetchMcpAppTemplate(serverId: String, toolName: String): McpAppTemplate? = mcpServerManager.fetchAppTemplate(serverId, toolName)

    override suspend fun callMcpAppTool(serverId: String, toolName: String, argsJson: String): String = mcpServerManager.callAppTool(serverId, toolName, argsJson)

    override fun isMcpServerConnected(serverId: String): Boolean = mcpServerManager.isConnected(serverId)

    override suspend fun connectEnabledMcpServers() {
        mcpServerManager.connectEnabledServers()
    }

    // Skills
    override fun getInstalledSkills(): List<SkillManifest> = skillManager.getInstalled()

    override fun observeInstalledSkills(): StateFlow<List<SkillManifest>> = skillManager.skills

    override suspend fun reloadInstalledSkills() {
        skillManager.load()
    }

    override suspend fun uninstallSkill(id: String) {
        skillManager.uninstall(id)
    }

    override suspend fun browseSkillMarketplaces(): Result<List<RegistrySkillEntry>> = skillManager.browseMarketplaces()

    override suspend fun installBrowsedSkill(entry: RegistrySkillEntry): Result<SkillManifest> = skillManager.installFromRegistryEntry(entry)

    override suspend fun installGitHubSkill(owner: String, repo: String, ref: String, path: String): Result<SkillManifest> = skillManager.installFromGitHub(owner, repo, ref, path)

    override suspend fun installSkillFromUrl(url: String): Result<SkillManifest> = skillManager.installFromUrl(url)

    override suspend fun installSkillFromContent(content: String): Result<SkillManifest> = skillManager.installFromContent(content)

    // Soul (system prompt)
    override fun getSoulText(): String = appSettings.getSoulText()

    override fun setSoulText(text: String) {
        appSettings.setSoulText(text)
    }

    override suspend fun getActiveSystemPrompt(variant: SystemPromptVariant): String? {
        val soul = appSettings.getSoulText().ifEmpty { getString(Res.string.default_soul) }
        val memoryEnabled = appSettings.isMemoryEnabled()
        val schedulingEnabled = appSettings.isSchedulingEnabled()

        val memoryInstructions = if (memoryEnabled) {
            appSettings.getMemoryInstructions().ifEmpty { null }
        } else {
            null
        }

        val memories = if (memoryEnabled) memoryStore.getAllMemories() else emptyList()
        val byCategory = memories.groupBy { it.category }

        val tasksSplit = if (schedulingEnabled) taskStore.getPendingTasksPartitioned() else PendingTaskPartition(emptyList(), emptyList())
        val pendingTasks = tasksSplit.scheduled
        val heartbeatAdditions = tasksSplit.heartbeatAdditions

        // Surface connected email accounts so the AI knows they exist in regular chat,
        // not just during heartbeats. Only the remote variant uses this — email tools
        // aren't in the local allowlist. Gated on the email toggle: if the user has email
        // off, the AI shouldn't reference the accounts.
        val emailAccounts = if (variant == SystemPromptVariant.CHAT_REMOTE && appSettings.isEmailEnabled()) {
            emailStore.getAccounts().map { account ->
                val state = emailStore.getSyncState(account.id)
                EmailAccountSummary(
                    email = account.email,
                    unreadCount = state.unreadCount,
                    lastSyncEpochMs = state.lastSyncEpochMs,
                    lastError = state.lastError,
                )
            }
        } else {
            emptyList()
        }

        val service = currentService()
        // On-device services store the active model ID per-instance, not globally, so
        // `getSelectedModelId` comes back blank for LiteRT. Fall back to the first
        // configured on-device instance's model ID in that case.
        val modelId = appSettings.getSelectedModelId(service).ifBlank {
            if (service.isOnDevice) {
                getConfiguredServiceInstances()
                    .firstOrNull { Service.fromId(it.serviceId).isOnDevice }
                    ?.let { appSettings.getInstanceModelId(it.instanceId) }
                    .orEmpty()
            } else {
                ""
            }
        }
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val localDateTime = now.toLocalDateTime(timeZone)
        val offset = timeZone.offsetAt(now)
        val runtime = ChatPromptRuntimeContext(
            nowLocalIsoWithOffset = "$localDateTime$offset",
            timeZoneId = timeZone.id,
            nowUtcIsoString = now.toString(),
            platform = currentPlatform.displayName,
            modelId = modelId,
            providerName = service.displayName,
        )

        val isLimited = !supportsTools(modelId)
        val uiMode = when {
            interactiveModeFlag -> ChatPromptUiMode.INTERACTIVE_UI
            appSettings.isDynamicUiEnabled() && !isLimited -> ChatPromptUiMode.DYNAMIC_UI
            else -> ChatPromptUiMode.NONE
        }

        // Tool-use guidance is only worth sending when the model is actually given tools.
        // Mirror the tool list the request will carry: remote uses the full set (when the
        // model supports tools), local uses the allowlist-filtered set.
        val hasTools = when (variant) {
            SystemPromptVariant.CHAT_REMOTE -> !isLimited && getAvailableTools().isNotEmpty()
            SystemPromptVariant.CHAT_LOCAL -> getLocalSafeTools().isNotEmpty() && localModelDeclaresTools(modelId)
        }

        val activeSkill = pendingActiveSkillId?.let { skillManager.getSkill(it) }

        return buildChatSystemPrompt(
            variant = variant,
            soul = soul,
            hasTools = hasTools,
            memoryEnabled = memoryEnabled,
            schedulingEnabled = schedulingEnabled,
            memoryInstructions = memoryInstructions,
            generalMemories = byCategory[MemoryCategory.GENERAL].orEmpty(),
            preferenceMemories = byCategory[MemoryCategory.PREFERENCE].orEmpty(),
            learningMemories = byCategory[MemoryCategory.LEARNING].orEmpty(),
            errorMemories = byCategory[MemoryCategory.ERROR].orEmpty(),
            pendingTasks = pendingTasks,
            heartbeatAdditions = heartbeatAdditions,
            emailAccounts = emailAccounts,
            runtime = runtime,
            uiMode = uiMode,
            activeSkill = activeSkill,
            learnedSoul = learnedSoulStore.get().map { it.text },
        ).ifEmpty { null }
    }

    override fun getLearnedSoulEntries(): List<LearnedSoulEntry> = learnedSoulStore.get()

    override suspend fun deleteLearnedSoulEntry(key: String) {
        learnedSoulStore.remove(key)
    }

    override fun isDynamicUiEnabled(): Boolean = appSettings.isDynamicUiEnabled()

    override fun setDynamicUiEnabled(enabled: Boolean) {
        appSettings.setDynamicUiEnabled(enabled)
    }

    override fun getThemeMode(): ThemeMode = appSettings.getThemeMode()

    override fun setThemeMode(mode: ThemeMode) {
        appSettings.setThemeMode(mode)
    }

    private var interactiveModeFlag = appSettings.getCurrentInteractiveMode()

    override fun setInteractiveMode(enabled: Boolean) {
        interactiveModeFlag = enabled
        appSettings.setCurrentInteractiveMode(enabled)
    }

    override fun isInteractiveModeActive(): Boolean = interactiveModeFlag

    override fun isMemoryEnabled(): Boolean = appSettings.isMemoryEnabled()

    override fun setMemoryEnabled(enabled: Boolean) {
        appSettings.setMemoryEnabled(enabled)
    }

    override fun getMemories(): List<MemoryEntry> = memoryStore.getAllMemories()

    override suspend fun deleteMemory(key: String) {
        memoryStore.forget(key)
    }

    override suspend fun updateMemoryContent(key: String, content: String) {
        memoryStore.updateContent(key, content)
    }

    override fun isSchedulingEnabled(): Boolean = appSettings.isSchedulingEnabled()

    override fun setSchedulingEnabled(enabled: Boolean) {
        appSettings.setSchedulingEnabled(enabled)
    }

    override fun getScheduledTasks(): List<ScheduledTask> = taskStore.getAllTasks()

    override suspend fun cancelScheduledTask(id: String) {
        taskStore.removeTask(id)
    }

    override fun isDaemonEnabled(): Boolean = appSettings.isDaemonEnabled()

    override fun setDaemonEnabled(enabled: Boolean) {
        appSettings.setDaemonEnabled(enabled)
    }

    override fun isSandboxEnabled(): Boolean = appSettings.isSandboxEnabled()

    override fun setSandboxEnabled(enabled: Boolean) {
        appSettings.setSandboxEnabled(enabled)
    }

    override fun getSandboxDistro(): LinuxDistro = appSettings.getSandboxDistro()

    override fun setSandboxDistro(distro: LinuxDistro) {
        appSettings.setSandboxDistro(distro)
    }

    override fun getKaiBuildLaunchAgent(): String? = appSettings.getKaiBuildLaunchAgent()

    override fun setKaiBuildLaunchAgent(agentId: String?) {
        appSettings.setKaiBuildLaunchAgent(agentId)
    }

    override fun getHeartbeatConfig(): HeartbeatConfig = heartbeatManager.getConfig()

    override fun setHeartbeatEnabled(enabled: Boolean) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(enabled = enabled))
    }

    override fun setHeartbeatIntervalMinutes(minutes: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(intervalMinutes = minutes))
    }

    override fun setHeartbeatActiveHours(start: Int, end: Int) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(activeHoursStart = start, activeHoursEnd = end))
    }

    override fun getHeartbeatPrompt(): String = appSettings.getHeartbeatPrompt()

    override fun setHeartbeatPrompt(text: String) {
        appSettings.setHeartbeatPrompt(text)
    }

    override fun getHeartbeatLog(): List<HeartbeatLogEntry> = heartbeatManager.getHeartbeatLog()

    override fun getHeartbeatInstanceId(): String? = heartbeatManager.getConfig().heartbeatInstanceId

    override fun setHeartbeatInstanceId(instanceId: String?) {
        val config = heartbeatManager.getConfig()
        heartbeatManager.saveConfig(config.copy(heartbeatInstanceId = instanceId))
    }

    override fun isEmailEnabled(): Boolean = appSettings.isEmailEnabled()

    override fun setEmailEnabled(enabled: Boolean) {
        appSettings.setEmailEnabled(enabled)
    }

    override fun getEmailAccounts(): List<EmailAccount> = emailStore.getAccounts()

    override suspend fun removeEmailAccount(id: String) {
        emailStore.removeAccount(id)
    }

    override fun getEmailPollIntervalMinutes(): Int = appSettings.getEmailPollIntervalMinutes()

    override fun getPendingEmailCount(): Int = emailStore.getPending().size

    override fun getEmailSyncStates(): Map<String, EmailSyncState> = emailStore.getAllSyncStates()

    override suspend fun pollEmailAccount(accountId: String) {
        val account = emailStore.getAccount(accountId) ?: return
        emailPoller.poll(account)
    }

    override fun setEmailPollIntervalMinutes(minutes: Int) {
        appSettings.setEmailPollIntervalMinutes(minutes)
    }

    override fun isSmsEnabled(): Boolean = appSettings.isSmsEnabled()

    override fun setSmsEnabled(enabled: Boolean) {
        appSettings.setSmsEnabled(enabled)
    }

    override fun getSmsPollIntervalMinutes(): Int = appSettings.getSmsPollIntervalMinutes()

    override fun setSmsPollIntervalMinutes(minutes: Int) {
        appSettings.setSmsPollIntervalMinutes(minutes)
    }

    override fun getPendingSmsCount(): Int = smsStore.getPending().size

    override fun getSmsSyncState(): SmsSyncState = smsStore.getSyncState()

    override fun hasSmsPermission(): Boolean = smsReader.hasPermission()

    override suspend fun requestSmsPermission(): Boolean = smsPermissionController.requestPermission()

    override suspend fun pollSms() {
        smsPoller.poll()
    }

    override fun isSmsSendEnabled(): Boolean = appSettings.isSmsSendEnabled()

    override fun setSmsSendEnabled(enabled: Boolean) {
        appSettings.setSmsSendEnabled(enabled)
    }

    override fun hasSmsSendPermission(): Boolean = smsSender.hasPermission()

    override suspend fun requestSmsSendPermission(): Boolean = smsSendPermissionController.requestPermission()

    override val smsDrafts: StateFlow<List<SmsDraft>> = smsDraftStore.drafts

    // Flips the draft to SENDING, delegates to SmsSender, then updates to SENT/FAILED.
    // Explicit user-triggered (never AI-triggered) — the banner is the gate.
    override suspend fun sendSmsDraft(draftId: String): Boolean {
        val draft = smsDraftStore.getDraft(draftId) ?: return false
        if (draft.status != SmsDraftStatus.PENDING) return false
        smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENDING)
        return when (val result = smsSender.send(draft.address, draft.body)) {
            is SmsSendResult.Success -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.SENT)
                true
            }

            is SmsSendResult.Failure -> {
                smsDraftStore.updateStatus(draftId, SmsDraftStatus.FAILED, result.message)
                false
            }
        }
    }

    override suspend fun discardSmsDraft(draftId: String) {
        smsDraftStore.removeDraft(draftId)
    }

    override fun isAutomationEnabled(): Boolean = appSettings.isAutomationEnabled()

    override fun setAutomationEnabled(enabled: Boolean) {
        appSettings.setAutomationEnabled(enabled)
    }

    override fun isAutomationWriteEnabled(): Boolean = appSettings.isAutomationWriteEnabled()

    override fun setAutomationWriteEnabled(enabled: Boolean) {
        appSettings.setAutomationWriteEnabled(enabled)
    }

    override fun isShizukuEnabled(): Boolean = appSettings.isShizukuEnabled()

    override fun setShizukuEnabled(enabled: Boolean) {
        appSettings.setShizukuEnabled(enabled)
    }

    override fun getAutomationAllowedApps(): Set<String> = appSettings.getAutomationAllowedApps()

    override fun setAutomationAllowedApps(packages: Set<String>) {
        appSettings.setAutomationAllowedApps(packages)
    }

    override fun isAutomationServiceBound(): Boolean = isAutomationServiceEnabled()

    override fun getShizukuStatusText(): String = getShizukuStatus()

    override fun openAutomationSystemSettings() = openAccessibilitySettings()

    override fun openShizukuApp(): Boolean = openAppByPackage(SHIZUKU_MANAGER_PACKAGE)

    override fun openShizukuDownloadPage(): Boolean = openUrl(SHIZUKU_DOWNLOAD_URL)

    override suspend fun requestShizukuAuthorization(): Boolean = com.inspiredandroid.kai.requestShizukuAuthorization()

    override fun getShizukuDetailsText(): String = getShizukuDetails()

    companion object {
        const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/"
    }

    override fun isNotificationsEnabled(): Boolean = appSettings.isNotificationsEnabled()

    override fun setNotificationsEnabled(enabled: Boolean) {
        appSettings.setNotificationsEnabled(enabled)
    }

    override fun isNotificationListenerAccessGranted(): Boolean = notificationListenerController.isAccessGranted()

    override fun openNotificationListenerSettings() {
        notificationListenerController.openAccessSettings()
    }

    override fun getPendingNotificationCount(): Int = notificationStore.getPending().size

    override fun getNotificationSyncState(): NotificationSyncState = notificationStore.getSyncState()

    override suspend fun clearPendingNotifications() {
        notificationStore.clearPending()
    }

    override fun getUiScale(): Float = appSettings.getUiScale()

    override fun setUiScale(scale: Float) {
        appSettings.setUiScale(scale)
    }

    override fun exportSettingsToJson(sections: Set<ImportSection>): String {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, sections, conversationStorage.conversations.value)
        return prettyJson.encodeToString(JsonObject.serializer(), jsonObject)
    }

    override fun getExportPreview(): Map<ImportSection, String?> {
        val toolIds = getPlatformToolDefinitions().map { it.id }
        val jsonObject = appSettings.exportToJson(toolIds, conversations = conversationStorage.conversations.value)
        return detectExportableSections(jsonObject)
    }

    override fun importSettingsFromJson(json: String, sections: Set<ImportSection>, replace: Boolean): Int {
        val jsonObject = SharedJson.parseToJsonElement(json).jsonObject
        val toolIds = getPlatformToolDefinitions().map { it.id }
        return appSettings.importFromJson(jsonObject, toolIds, sections, replace)
    }

    override suspend fun askWithTools(prompt: String, instanceId: String?, conversationIdOverride: String?): String {
        // Selection: explicit instance > first remote > first on-device. The simple-tool
        // allowlist works at any context size, so on-device is always eligible for fallback.
        val instances = getConfiguredServiceInstances()
        val targetInstance = instanceId?.let { id -> instances.find { it.instanceId == id } }
            ?: instances.firstOrNull { !Service.fromId(it.serviceId).isOnDevice }
            ?: instances.firstOrNull { Service.fromId(it.serviceId).isOnDevice }
            ?: return ""
        val service = Service.fromId(targetInstance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = prompt))
        val systemPrompt = getActiveSystemPrompt()
        // Use a local history to avoid polluting the current conversation's chatHistory
        val localHistory = MutableStateFlow(messages)
        // When a conversation override is set (heartbeat / scheduled tasks), bind any
        // tool calls in this run to that conversation's sandbox session via the
        // coroutine context — otherwise tool dispatch would inherit `_currentConversationId`
        // (the chat the user is viewing), routing the heartbeat's shell commands into
        // that chat's persistent bash session.
        //
        // Background runs can write ~/skills too, so rescan afterwards exactly
        // like the foreground ask() path does.
        val response = try {
            // Background runs are never interactive: nobody is looking at the chat, so a
            // risky tool call has to fail in ToolExecutor instead of waiting for an
            // approval no one can give.
            val runContext = ToolInteractionElement(interactive = false)
            if (conversationIdOverride != null) {
                withContext(runContext + ConversationIdElement(conversationIdOverride)) {
                    askWithService(service, messages, systemPrompt, targetInstance.instanceId, localHistory).content
                }
            } else {
                withContext(runContext) {
                    askWithService(service, messages, systemPrompt, targetInstance.instanceId, localHistory).content
                }
            }
        } finally {
            skillManager.load()
        }
        return response
    }

    override suspend fun askSilently(question: String): String {
        val service = currentService()
        val firstInstance = getConfiguredServiceInstances().firstOrNull() ?: return ""
        val messages = listOf(History(role = History.Role.USER, content = question))

        if (service.isOnDevice) {
            // Throwaway history — we don't want tool-execution rows leaking into the
            // visible chatHistory for a "silent" call. LOCAL variant of the system
            // prompt so small on-device models get the right section set.
            val localPrompt = getActiveSystemPrompt(SystemPromptVariant.CHAT_LOCAL)
            return askWithLocalEngine(messages, localPrompt, firstInstance.instanceId, MutableStateFlow(messages))
        }

        val systemPrompt = getActiveSystemPrompt()
        val creds = instanceCredentials(firstInstance.instanceId, service)

        return plainChat(service, creds, messages, systemPrompt, retry = false).content
    }

    override suspend fun askSilentlyWithInstance(instanceId: String, prompt: String, timeoutMs: Long): String {
        val instance = getConfiguredServiceInstances().find { it.instanceId == instanceId }
            ?: return askSilently(prompt)
        val service = Service.fromId(instance.serviceId)
        val messages = listOf(History(role = History.Role.USER, content = prompt))

        if (service.isOnDevice) {
            return askWithLocalEngine(messages, null, instanceId, MutableStateFlow(messages))
        }

        val creds = instanceCredentials(instanceId, service)

        return plainChat(
            service = service,
            credentials = creds,
            messages = messages,
            systemPrompt = null,
            requestTimeoutMs = timeoutMs.takeIf { it > 0 },
            retry = false,
        ).content
    }

    private val _hasUnreadHeartbeat = MutableStateFlow(appSettings.isHeartbeatUnread())
    override val hasUnreadHeartbeat: StateFlow<Boolean> = _hasUnreadHeartbeat

    private val learnedSoulStore = LearnedSoulStore(appSettings)

    override fun heartbeatConversationId(): String? = newestHeartbeatConversation()?.id

    private fun newestHeartbeatConversation(): Conversation? = savedConversations.value
        .filter { it.type == Conversation.TYPE_HEARTBEAT }
        .maxByOrNull { it.updatedAt }

    override fun clearUnreadHeartbeat() {
        if (!_hasUnreadHeartbeat.value) return
        _hasUnreadHeartbeat.value = false
        appSettings.setHeartbeatUnread(false)
    }

    private val _openHeartbeatRequested = MutableStateFlow(false)
    override val openHeartbeatRequested: StateFlow<Boolean> = _openHeartbeatRequested

    override fun requestOpenHeartbeat() {
        _openHeartbeatRequested.value = true
    }

    override fun consumeOpenHeartbeatRequest() {
        _openHeartbeatRequested.value = false
    }

    private val _openAssistRequested = MutableStateFlow(false)
    override val openAssistRequested: StateFlow<Boolean> = _openAssistRequested

    override fun requestOpenAssist() {
        _openAssistRequested.value = true
    }

    override fun consumeOpenAssistRequest() {
        _openAssistRequested.value = false
    }

    private val _pendingShareText = MutableStateFlow<String?>(null)
    override val pendingShareText: StateFlow<String?> = _pendingShareText

    override fun requestOpenShare(text: String) {
        _pendingShareText.value = text
    }

    override fun consumeOpenShareRequest() {
        _pendingShareText.value = null
    }

    override suspend fun addAssistantMessage(content: String) {
        val now = Clock.System.now().toEpochMilliseconds()

        val existing = newestHeartbeatConversation()
        val heartbeatId = existing?.id ?: getOrCreateHeartbeatConversationId()

        val newMessage = Conversation.Message(
            id = Uuid.random().toString(),
            role = "assistant",
            content = content,
        )

        val messages = ((existing?.messages ?: emptyList()) + newMessage).takeLast(MAX_HEARTBEAT_MESSAGES)

        val conversation = Conversation(
            id = heartbeatId,
            messages = messages,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            type = Conversation.TYPE_HEARTBEAT,
        )

        conversationStorage.saveConversation(conversation)

        if (_currentConversationId.value == heartbeatId) {
            // The user is reading the heartbeat conversation right now: surface the
            // report in the open chat (storage alone never reaches `chatHistory`) and
            // leave it read. Appending here also keeps a later user turn in this
            // conversation from persisting a stale history over the just-written message.
            chatHistory.update { history ->
                history + History(id = newMessage.id, role = History.Role.ASSISTANT, content = content)
            }
        } else {
            _hasUnreadHeartbeat.value = true
            appSettings.setHeartbeatUnread(true)
        }
    }

    override suspend fun getOrCreateHeartbeatConversationId(): String {
        newestHeartbeatConversation()?.let { return it.id }
        val now = Clock.System.now().toEpochMilliseconds()
        val id = Uuid.random().toString()
        conversationStorage.saveConversation(
            Conversation(
                id = id,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
                type = Conversation.TYPE_HEARTBEAT,
            ),
        )
        return id
    }

    private fun deriveTitle(history: List<History>): String {
        val firstUserMessage = history.firstOrNull { it.role == History.Role.USER }?.content ?: return ""
        return if (firstUserMessage.length <= 50) {
            firstUserMessage
        } else {
            val truncated = firstUserMessage.take(50)
            val lastSpace = truncated.lastIndexOf(' ')
            if (lastSpace > 20) truncated.substring(0, lastSpace) + "..." else truncated + "..."
        }
    }

    // On-device inference (LiteRT)

    override fun isLocalInferenceAvailable(): Boolean = localInferenceEngine != null

    override fun getLocalEngineState(): StateFlow<EngineState>? = localInferenceEngine?.engineState

    override fun getLocalDownloadingModelId(): StateFlow<String?>? = localInferenceEngine?.downloadingModelId

    override fun getLocalDownloadProgress(): StateFlow<Float?>? = localInferenceEngine?.downloadProgress

    override fun getLocalDownloadError(): StateFlow<DownloadError?>? = localInferenceEngine?.downloadError

    override fun getLocalDownloadedModels(): List<DownloadedModel> = localInferenceEngine?.getDownloadedModels() ?: emptyList()

    override fun getLocalAvailableModels(): List<LocalModel> = localInferenceEngine?.getAvailableModels() ?: emptyList()

    override fun getLocalImportedModels(): List<LocalModel> = localInferenceEngine?.getImportedLocalModels() ?: emptyList()

    override fun getLocalFreeSpaceBytes(): Long = localInferenceEngine?.getFreeSpaceBytes() ?: 0L

    override fun getTotalDeviceMemoryBytes(): Long = getTotalMemoryBytes()

    override fun getModelContextTokens(modelId: String): Int = appSettings.getModelContextTokens(modelId)

    override fun setModelContextTokens(modelId: String, contextTokens: Int) {
        appSettings.setModelContextTokens(modelId, contextTokens)
    }

    override suspend fun releaseLocalEngine() {
        localInferenceEngine?.release()
    }

    override fun getLocalImportingFileName(): StateFlow<String?>? = localInferenceEngine?.importingFileName

    override fun getLocalImportProgress(): StateFlow<Float?>? = localInferenceEngine?.importProgress

    override fun getLocalImportError(): StateFlow<ModelImportError?>? = localInferenceEngine?.importError

    override fun startLocalModelDownload(model: LocalModel) {
        localInferenceEngine?.startDownload(model)
    }

    override fun cancelLocalModelDownload() {
        localInferenceEngine?.cancelDownload()
    }

    override suspend fun importLocalModel(source: PlatformFile): ModelImportResult {
        val engine = localInferenceEngine
            ?: return ModelImportResult.Failure(ModelImportError.COPY_FAILED, "On-device inference not available")
        return engine.importModel(source)
    }

    override fun cancelLocalModelImport() {
        localInferenceEngine?.cancelImport()
    }

    override suspend fun deleteLocalModel(modelId: String) {
        localInferenceEngine?.deleteModel(modelId)
    }
}
