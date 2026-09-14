package com.inspiredandroid.kai.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * A short per-app operating note ("app card") the agent attaches to a package —
 * where the search box is, which onboarding popups to skip, which flow works.
 * Surfaced with `ui_dump` whenever that package is in the foreground, so the
 * guidance is present exactly when the agent is acting inside that app.
 */
@Serializable
data class AppCard(
    val packageName: String,
    val note: String,
    val updatedAtEpochMs: Long = 0L,
)

@OptIn(ExperimentalTime::class)
class AppCardStore(private val appSettings: AppSettings) {

    private val cards = SettingsJsonList(
        read = appSettings::getAppCardsJson,
        write = appSettings::setAppCardsJson,
        itemSerializer = serializer<AppCard>(),
        label = "AppCardStore.cards",
    )

    fun get(): List<AppCard> = cards.get()

    fun forPackage(packageName: String): String? = cards.get().firstOrNull { it.packageName == packageName }?.note?.takeIf { it.isNotBlank() }

    /** Upserts the note for [packageName] (newest first, capped). Returns false when blank. */
    suspend fun set(packageName: String, note: String): Boolean {
        val pkg = packageName.trim()
        val text = note.trim().take(MAX_NOTE_CHARS)
        if (pkg.isEmpty() || text.isEmpty()) return false
        var stored = false
        cards.update { existing ->
            if (existing.size >= MAX_CARDS && existing.none { it.packageName == pkg }) {
                existing
            } else {
                stored = true
                listOf(AppCard(pkg, text, Clock.System.now().toEpochMilliseconds())) +
                    existing.filterNot { it.packageName == pkg }
            }
        }
        return stored
    }

    suspend fun remove(packageName: String) {
        cards.update { existing -> existing.filterNot { it.packageName == packageName.trim() } }
    }

    companion object {
        const val MAX_CARDS = 100
        const val MAX_NOTE_CHARS = 2_000
    }
}
