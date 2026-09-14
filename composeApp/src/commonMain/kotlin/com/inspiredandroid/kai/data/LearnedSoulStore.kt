package com.inspiredandroid.kai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A pattern the AI promoted out of an established memory (via `promote_learning`).
 * Kept as its own row rather than merged into the soul string so entries stay
 * deduplicable, removable, and bounded.
 */
@Serializable
data class LearnedSoulEntry(
    val key: String,
    val text: String,
    val promotedAtEpochMs: Long = 0L,
)

/**
 * Durable additions to the system prompt that the AI promoted from reinforced
 * memories. Stored separately from the user-authored soul so:
 * - resetting or editing the soul never drops promoted behaviour,
 * - promoted text never bloats the user's editable soul,
 * - the user's soul/prefix stays stable for provider prompt caching.
 *
 * Entries are newest-first and capped; duplicates (same memory key or same text)
 * are rejected.
 */
@OptIn(ExperimentalTime::class)
class LearnedSoulStore(private val appSettings: AppSettings) {

    private val entries = SettingsJsonList(
        read = appSettings::getLearnedSoulJson,
        write = appSettings::setLearnedSoulJson,
        itemSerializer = serializer<LearnedSoulEntry>(),
        label = "LearnedSoulStore.entries",
    )

    fun get(): List<LearnedSoulEntry> = entries.get()

    /** Returns false when this key or text was already promoted (nothing was written). */
    suspend fun append(key: String, text: String): Boolean {
        var added = false
        entries.update { existing ->
            if (existing.any { it.key == key || it.text == text }) {
                existing
            } else {
                added = true
                (listOf(LearnedSoulEntry(key = key, text = text, promotedAtEpochMs = Clock.System.now().toEpochMilliseconds())) + existing)
                    .take(MAX_ENTRIES)
            }
        }
        return added
    }

    suspend fun remove(key: String) {
        entries.update { existing -> existing.filterNot { it.key == key } }
    }

    companion object {
        /** Keeps the injected block small — the oldest promotions fall off. */
        const val MAX_ENTRIES = 20
    }
}
