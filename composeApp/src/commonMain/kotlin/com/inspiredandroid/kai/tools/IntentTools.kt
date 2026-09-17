package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.NotificationRecord
import com.inspiredandroid.kai.data.SettingsJsonValue
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * An event-conditioned standing instruction: when a matching Android
 * notification arrives, the scheduler runs [prompt] as a full agent turn.
 * Time-based work stays on `schedule_task`; intents cover "when X happens".
 */
@Serializable
data class NotificationIntent(
    val id: String,
    /** Package to watch, e.g. com.whatsapp. Blank matches any app. */
    val packageName: String = "",
    /** Substring matched against title + text, case-insensitive. Blank matches any text. */
    val keyword: String = "",
    val prompt: String,
    val createdAtEpochMs: Long = 0L,
)

@Serializable
private data class IntentState(
    val intents: List<NotificationIntent> = emptyList(),
    val firedNotificationIds: List<String> = emptyList(),
    val watermarkEpochMs: Long = 0L,
)

class IntentStore(appSettings: AppSettings) {
    private val backing = SettingsJsonValue(
        read = appSettings::getNotificationIntentsJson,
        write = appSettings::setNotificationIntentsJson,
        serializer = IntentState.serializer(),
        label = "intents",
        default = ::IntentState,
    )

    fun list(): List<NotificationIntent> = backing.get().intents

    fun watermark(): Long = backing.get().watermarkEpochMs

    suspend fun add(intent: NotificationIntent): NotificationIntent {
        var added = intent
        backing.update { state ->
            added = intent.copy(id = newId())
            state.copy(intents = (state.intents + added).takeLast(MAX_INTENTS))
        }
        return added
    }

    suspend fun remove(id: String): Boolean {
        var found = false
        backing.update { state ->
            val remaining = state.intents.filter {
                if (it.id == id) {
                    found = true
                    false
                } else {
                    true
                }
            }
            if (!found) return@update state
            state.copy(intents = remaining)
        }
        return found
    }

    /**
     * Records notification ids as fired (capped) and advances the scan
     * watermark. Returns the ids that had not fired before — each
     * notification triggers its intent at most once across ticks.
     */
    suspend fun claim(ids: List<String>, watermark: Long): List<String> {
        var fresh: List<String> = emptyList()
        backing.update { state ->
            fresh = ids.distinct().filter { it !in state.firedNotificationIds }
            if (fresh.isEmpty() && watermark <= state.watermarkEpochMs) return@update state
            state.copy(
                firedNotificationIds = (state.firedNotificationIds + fresh).takeLast(MAX_FIRED_IDS),
                watermarkEpochMs = maxOf(state.watermarkEpochMs, watermark),
            )
        }
        return fresh
    }

    companion object {
        private const val MAX_INTENTS = 50
        private const val MAX_FIRED_IDS = 500

        fun matches(intent: NotificationIntent, record: NotificationRecord): Boolean {
            if (record.isOngoing) return false
            if (intent.packageName.isNotBlank() && !intent.packageName.equals(record.packageName, ignoreCase = true)) {
                return false
            }
            if (intent.keyword.isNotBlank()) {
                val haystack = "${record.title}\n${record.text}".lowercase()
                if (intent.keyword.lowercase() !in haystack) return false
            }
            return true
        }

        @OptIn(ExperimentalUuidApi::class)
        private fun newId(): String = Uuid.random().toString().substring(0, 8)
    }
}

object IntentTools {
    private const val MAX_PROMPT_CHARS = 4000

    fun intentsTool(store: IntentStore) = object : Tool {
        override val schema = ToolSchema(
            name = "intents",
            description = "Manage event-triggered standing instructions: when a matching Android notification " +
                "arrives, the scheduler runs your prompt as a full agent turn. Use it for event-driven " +
                "behaviour (\"when my bank app notifies, summarize the alert\") — time-based work still goes " +
                "through schedule_task. Firing needs scheduling enabled and notification access granted; " +
                "each notification triggers at most once. " +
                "Actions: add (params: prompt, optional package_name, keyword), list, remove (params: id).",
            parameters = mapOf(
                "action" to ParameterSchema("string", "One of: add, list, remove (required)", true),
                "prompt" to ParameterSchema("string", "Instruction run when the intent fires (for add)", false),
                "package_name" to ParameterSchema("string", "Only fire for this app package, e.g. com.whatsapp (for add)", false),
                "keyword" to ParameterSchema("string", "Only fire when title/text contains this (for add)", false),
                "id" to ParameterSchema("string", "Intent id from list (for remove)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            fun snapshot(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = mapOf(
                "success" to true,
                "intents" to store.list().map { intent ->
                    mapOf(
                        "id" to intent.id,
                        "package_name" to intent.packageName.ifBlank { "(any app)" },
                        "keyword" to intent.keyword.ifBlank { "(any text)" },
                        "prompt" to intent.prompt,
                    )
                },
            ) + extra
            return when ((args["action"] as? String)?.lowercase()) {
                "list" -> snapshot()

                "add" -> {
                    val prompt = args["prompt"]?.toString()?.trim().orEmpty()
                    if (prompt.isEmpty()) return mapOf("success" to false, "error" to "prompt is required for add")
                    if (prompt.length > MAX_PROMPT_CHARS) {
                        return mapOf("success" to false, "error" to "prompt is ${prompt.length} chars (max $MAX_PROMPT_CHARS) — shorten it and retry")
                    }
                    val added = store.add(
                        NotificationIntent(
                            id = "",
                            packageName = args["package_name"]?.toString()?.trim().orEmpty(),
                            keyword = args["keyword"]?.toString()?.trim().orEmpty(),
                            prompt = prompt,
                        ),
                    )
                    snapshot(mapOf("added" to added.id))
                }

                "remove" -> {
                    val id = args["id"]?.toString()?.trim().orEmpty()
                    if (id.isEmpty()) return mapOf("success" to false, "error" to "id is required for remove")
                    if (!store.remove(id)) {
                        return mapOf("success" to false, "error" to "No intent with id $id — call list first")
                    }
                    snapshot()
                }

                else -> mapOf("success" to false, "error" to "action must be add|list|remove")
            }
        }
    }

    val intentToolInfo = ToolInfo(
        id = "intents",
        name = "Notification Intents",
        description = "Run a prompt when a matching notification arrives",
        nameRes = null,
        descriptionRes = null,
        userToggleable = false,
    )

    val intentToolDefinitions = listOf(intentToolInfo)

    fun getIntentTools(store: IntentStore): List<Tool> = listOf(intentsTool(store))
}
