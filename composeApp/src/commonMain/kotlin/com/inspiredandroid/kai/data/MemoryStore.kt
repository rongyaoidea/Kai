package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.tools.WORD_SPLIT_REGEX
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
enum class MemoryCategory {
    GENERAL,
    LEARNING,
    ERROR,
    PREFERENCE,
}

@Immutable
@Serializable
data class MemoryEntry(
    val key: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val category: MemoryCategory = MemoryCategory.GENERAL,
    val hitCount: Int = 1,
    val source: String? = null,
)

@OptIn(ExperimentalTime::class)
class MemoryStore(appSettings: AppSettings) {

    private val memories = SettingsJsonList(
        read = appSettings::getMemoriesJson,
        write = appSettings::setMemoriesJson,
        itemSerializer = serializer<MemoryEntry>(),
        label = "MemoryStore",
    )

    suspend fun store(
        key: String,
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        source: String? = null,
    ): MemoryEntry {
        val now = Clock.System.now().toEpochMilliseconds()
        lateinit var entry: MemoryEntry
        memories.update { current ->
            val existing = current.find { it.key == key }
            if (existing != null) {
                entry = existing.copy(content = content, updatedAt = now, category = category, source = source ?: existing.source)
                return@update current.map { if (it.key == key) entry else it }
            }
            // Same fact under a different key: fold into the existing row
            // instead of growing a duplicate. Timestamp refreshes, but the hit
            // count is left alone — a repeated write is not evidence the memory
            // produced a good outcome (only memory_reinforce says that). The
            // stronger category wins so an explicit learn() never silently
            // demotes a preference to general (nor a casual store() a
            // preference to anything weaker); an explicit new source wins too.
            val duplicate = current.find { isDuplicate(it, key, content) }
            if (duplicate != null) {
                entry = duplicate.copy(
                    content = content,
                    updatedAt = now,
                    category = strongerCategory(duplicate.category, category),
                    source = source ?: duplicate.source,
                )
                return@update current.map { if (it.key == duplicate.key) entry else it }
            }
            entry = MemoryEntry(key = key, content = content, createdAt = now, updatedAt = now, category = category, source = source)
            current + entry
        }
        return entry
    }

    /**
     * Rows whose content restates an existing row (normalized containment
     * either way). Short fragments are excluded — below [MIN_DUPLICATE_CHARS]
     * almost everything "contains" almost everything.
     */
    internal fun isDuplicate(existing: MemoryEntry, key: String, content: String): Boolean {
        if (existing.key == key) return true
        val a = normalize(existing.content)
        val b = normalize(content)
        if (a.length < MIN_DUPLICATE_CHARS || b.length < MIN_DUPLICATE_CHARS) return false
        return a in b || b in a
    }

    internal fun normalize(text: String): String = text.lowercase().replace(WORD_SPLIT_REGEX, " ").trim()

    /** Category strength for fold adoption: explicit intent never demotes. */
    internal fun strongerCategory(a: MemoryCategory, b: MemoryCategory): MemoryCategory {
        fun rank(category: MemoryCategory): Int = when (category) {
            MemoryCategory.PREFERENCE -> 3
            MemoryCategory.LEARNING, MemoryCategory.ERROR -> 2
            MemoryCategory.GENERAL -> 1
        }
        return if (rank(b) >= rank(a)) b else a
    }

    /** Near-identical rows grouped for heartbeat review (never auto-merged). */
    internal fun duplicateGroups(entries: List<MemoryEntry>): List<List<MemoryEntry>> {
        val groups = mutableListOf<MutableList<MemoryEntry>>()
        for (entry in entries) {
            val group = groups.firstOrNull { members ->
                members.any { isDuplicate(it, entry.key, entry.content) }
            }
            if (group != null) group.add(entry) else groups.add(mutableListOf(entry))
        }
        return groups.filter { it.size > 1 }
    }

    /**
     * Rows rotting in place: untouched for longer than [olderThanMs] with at
     * most [maxHits] reinforcements. Preferences are exempt — a standing
     * user preference is true until the user says otherwise, however old.
     */
    internal fun staleEntries(
        entries: List<MemoryEntry>,
        nowMs: Long,
        olderThanMs: Long = STALE_AFTER_MS,
        maxHits: Int = 1,
    ): List<MemoryEntry> = entries.filter {
        it.category != MemoryCategory.PREFERENCE &&
            it.hitCount <= maxHits &&
            nowMs - it.updatedAt >= olderThanMs
    }

    fun getStaleCandidates(nowMs: Long, olderThanMs: Long = STALE_AFTER_MS): List<MemoryEntry> = staleEntries(getAllMemories(), nowMs, olderThanMs)

    fun getDuplicateGroups(): List<List<MemoryEntry>> = duplicateGroups(getAllMemories())

    /**
     * Lowest-value rows to drop when the store exceeds [limit]: never
     * preferences, never reinforced rows, oldest first, capped at [maxDelete]
     * per pass. Pure selection — the caller deletes.
     */
    internal fun selectExcess(
        entries: List<MemoryEntry>,
        limit: Int = MAX_MEMORIES,
        maxDelete: Int = MAX_AUTO_DELETE_PER_RUN,
    ): List<MemoryEntry> {
        val over = entries.size - limit
        if (over <= 0) return emptyList()
        return entries
            .filter { it.category != MemoryCategory.PREFERENCE && it.hitCount == 0 }
            .sortedBy { it.updatedAt }
            .take(minOf(over, maxDelete))
    }

    fun selectExcessForCleanup(): List<MemoryEntry> = selectExcess(getAllMemories())

    suspend fun updateContent(key: String, content: String): MemoryEntry? = mutateEntry(key) { it.copy(content = content, updatedAt = Clock.System.now().toEpochMilliseconds()) }

    suspend fun reinforceMemory(key: String): MemoryEntry? = mutateEntry(key) { it.copy(hitCount = it.hitCount + 1, updatedAt = Clock.System.now().toEpochMilliseconds()) }

    /** Applies [transform] to the entry under [key], persisting only when it exists. */
    private suspend fun mutateEntry(key: String, transform: (MemoryEntry) -> MemoryEntry): MemoryEntry? {
        var updated: MemoryEntry? = null
        memories.update { current ->
            val existing = current.find { it.key == key } ?: return@update current
            val next = transform(existing)
            updated = next
            current.map { if (it.key == key) next else it }
        }
        return updated
    }

    fun getPromotionCandidates(minHits: Int = PROMOTION_THRESHOLD): List<MemoryEntry> = memories.get().filter { it.hitCount >= minHits }

    suspend fun forget(key: String): Boolean {
        var removed = false
        memories.update { current ->
            removed = current.any { it.key == key }
            if (removed) current.filterNot { it.key == key } else current
        }
        return removed
    }

    fun getAllMemories(): List<MemoryEntry> = memories.get()

    companion object {
        /** Normalized content shorter than this never counts as a duplicate. */
        internal const val MIN_DUPLICATE_CHARS = 20

        /** Untouched non-preference rows older than this rot (180 days). */
        internal const val STALE_AFTER_MS = 180L * 24 * 60 * 60 * 1000

        /** Soft cap on stored rows; excess is suggested or auto-removed by heartbeat. */
        const val MAX_MEMORIES = 500

        /** Hit count at which heartbeat surfaces a memory as a promotion candidate. */
        const val PROMOTION_THRESHOLD = 5

        /** Upper bound on rows auto-deleted in a single heartbeat pass. */
        internal const val MAX_AUTO_DELETE_PER_RUN = 20
    }
}
