package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.SettingsJsonValue
import com.inspiredandroid.kai.data.currentConversationIdOrNull
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val createdAtEpochMs: Long = 0L,
    /**
     * Lifecycle state: pending, in_progress, or completed. Kept alongside the
     * older [done] flag so blobs written before statuses existed still read
     * correctly — [effectiveStatus] treats done as completed.
     */
    val status: String = TodoStatus.PENDING,
) {
    fun effectiveStatus(): String = if (done) TodoStatus.COMPLETED else status
}

object TodoStatus {
    const val PENDING = "pending"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
}

@Serializable
private data class TodoList(
    val items: List<TodoItem> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
)

/**
 * Per-conversation checklists, persisted in app settings so progress survives
 * process death. The tool resolves the bucket from the calling conversation
 * (falling back to a shared default outside one), so lists never leak across
 * chats — the same isolation the sandbox shells use.
 */
class TodoStore(appSettings: AppSettings) {
    private val backing = SettingsJsonValue(
        read = appSettings::getTodoJson,
        write = appSettings::setTodoJson,
        serializer = MapSerializer(String.serializer(), TodoList.serializer()),
        label = "todo",
        default = { emptyMap() },
    )

    fun list(bucket: String): List<TodoItem> = backing.get()[bucket]?.items.orEmpty()

    suspend fun add(bucket: String, text: String): Result<TodoItem> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Todo text is required"))
        var added: TodoItem? = null
        var full = false
        backing.update { all ->
            val current = all[bucket]?.items.orEmpty()
            if (current.size >= MAX_ITEMS_PER_LIST) {
                full = true
                return@update all
            }
            added = TodoItem(
                id = newId(),
                text = trimmed.take(MAX_TEXT_CHARS),
                createdAtEpochMs = nowMs(),
            )
            touch(all, bucket, current + added!!)
        }
        return if (full) {
            Result.failure(IllegalStateException("Todo list is full ($MAX_ITEMS_PER_LIST). Remove or clear finished items first."))
        } else {
            Result.success(added!!)
        }
    }

    suspend fun setStatus(bucket: String, id: String, status: String): Boolean {
        if (status != TodoStatus.PENDING && status != TodoStatus.IN_PROGRESS && status != TodoStatus.COMPLETED) {
            return false
        }
        var found = false
        backing.update { all ->
            val current = all[bucket]?.items.orEmpty()
            val updated = current.map {
                if (it.id == id) {
                    found = true
                    it.copy(done = status == TodoStatus.COMPLETED, status = status)
                } else {
                    it
                }
            }
            if (!found) return@update all
            touch(all, bucket, updated)
        }
        return found
    }

    suspend fun remove(bucket: String, id: String): Boolean {
        var found = false
        backing.update { all ->
            val current = all[bucket]?.items.orEmpty()
            val updated = current.filter {
                if (it.id == id) {
                    found = true
                    false
                } else {
                    true
                }
            }
            if (!found) return@update all
            touch(all, bucket, updated)
        }
        return found
    }

    suspend fun clearDone(bucket: String): Int {
        var removed = 0
        backing.update { all ->
            val current = all[bucket]?.items.orEmpty()
            // effectiveStatus covers blobs written before statuses existed
            // (done flag) as well as the newer status field.
            val remaining = current.filter {
                if (it.effectiveStatus() == TodoStatus.COMPLETED) {
                    removed++
                    false
                } else {
                    true
                }
            }
            if (removed == 0) return@update all
            touch(all, bucket, remaining)
        }
        return removed
    }

    private fun touch(all: Map<String, TodoList>, bucket: String, items: List<TodoItem>): Map<String, TodoList> {
        val withBucket = all + (bucket to TodoList(items, nowMs()))
        if (withBucket.size <= MAX_BUCKETS) return withBucket
        // Drop the least-recently-touched conversations first; never the one just written.
        return withBucket.entries
            .sortedByDescending { (key, list) -> if (key == bucket) Long.MAX_VALUE else list.updatedAtEpochMs }
            .take(MAX_BUCKETS)
            .associate { it.key to it.value }
    }

    companion object {
        const val DEFAULT_BUCKET = "default"
        private const val MAX_ITEMS_PER_LIST = 100
        private const val MAX_BUCKETS = 50
        private const val MAX_TEXT_CHARS = 500

        @OptIn(ExperimentalTime::class)
        private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

        @OptIn(ExperimentalUuidApi::class)
        private fun newId(): String = Uuid.random().toString().substring(0, 8)
    }
}

object TodoTools {
    fun todoTool(store: TodoStore) = object : Tool {
        override val schema = ToolSchema(
            name = "todo",
            description = "Track multi-step work as a checklist scoped to THIS conversation. " +
                "Use it when a task has 3+ steps so progress survives long tool loops and app restarts — " +
                "every call returns the full list with ids and statuses. Keep item text short; " +
                "mark the item you are working on started, complete it when done, and prefer updating " +
                "the list over narrating progress in chat. " +
                "Actions: add (params: text), list, start (params: id), done (params: id), " +
                "reopen (params: id), remove (params: id), clear (drops finished items).",
            parameters = mapOf(
                "action" to ParameterSchema("string", "One of: add, list, start, done, reopen, remove, clear (required)", true),
                "text" to ParameterSchema("string", "Item text for add", false),
                "id" to ParameterSchema("string", "Item id from list (for start, done, reopen, remove)", false),
            ),
        )

        override suspend fun execute(args: Map<String, Any>): Any {
            val bucket = currentConversationIdOrNull() ?: TodoStore.DEFAULT_BUCKET
            fun snapshot(): Map<String, Any> = mapOf(
                "success" to true,
                "items" to store.list(bucket).map { item ->
                    mapOf("id" to item.id, "text" to item.text, "status" to item.effectiveStatus())
                },
            )
            suspend fun transition(id: String, status: String, verb: String): Map<String, Any> {
                if (id.isEmpty()) return mapOf("success" to false, "error" to "id is required for $verb")
                if (!store.setStatus(bucket, id, status)) {
                    return mapOf("success" to false, "error" to "No todo with id $id — call list first")
                }
                return snapshot()
            }
            return when ((args["action"] as? String)?.lowercase()) {
                "list" -> snapshot()

                "add" -> {
                    val text = args["text"]?.toString().orEmpty()
                    store.add(bucket, text).fold(
                        onSuccess = { snapshot() + mapOf("added" to it.id) },
                        onFailure = { mapOf("success" to false, "error" to (it.message ?: "could not add todo")) },
                    )
                }

                "start" -> transition(args["id"]?.toString()?.trim().orEmpty(), TodoStatus.IN_PROGRESS, "start")

                "done" -> transition(args["id"]?.toString()?.trim().orEmpty(), TodoStatus.COMPLETED, "done")

                "reopen" -> transition(args["id"]?.toString()?.trim().orEmpty(), TodoStatus.PENDING, "reopen")

                "remove" -> {
                    val id = args["id"]?.toString()?.trim().orEmpty()
                    if (id.isEmpty()) return mapOf("success" to false, "error" to "id is required for remove")
                    if (!store.remove(bucket, id)) {
                        return mapOf("success" to false, "error" to "No todo with id $id — call list first")
                    }
                    snapshot()
                }

                "clear" -> {
                    val removed = store.clearDone(bucket)
                    snapshot() + mapOf("cleared" to removed)
                }

                else -> mapOf("success" to false, "error" to "action must be add|list|start|done|reopen|remove|clear")
            }
        }
    }

    val todoToolInfo = ToolInfo(
        id = "todo",
        name = "Task List",
        description = "Track multi-step work as a per-conversation checklist",
    )
}
