package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

class MemoryStoreTest {

    private fun store() = MemoryStore(AppSettings(MapSettings()))

    private fun entry(
        key: String,
        content: String,
        category: MemoryCategory = MemoryCategory.GENERAL,
        hitCount: Int = 1,
        updatedAt: Long = 0L,
    ) = MemoryEntry(
        key = key,
        content = content,
        createdAt = 0L,
        updatedAt = updatedAt,
        category = category,
        hitCount = hitCount,
    )

    @Test
    fun `store folds same fact under a new key into the existing row`() = runTest {
        val s = store()
        s.store("wifi", "The office wifi password is hunter2-hunter2-hunter2")

        val folded = s.store("network", "The office wifi password is hunter2-hunter2-hunter2!!")

        assertEquals("wifi", folded.key)
        assertEquals(1, s.getAllMemories().size)
        assertEquals(1, folded.hitCount)
    }

    @Test
    fun `fold adopts the stronger category and the new source`() = runTest {
        val s = store()
        s.store("wifi", "The office wifi password is hunter2-hunter2-hunter2")

        val folded = s.store(
            "network",
            "The office wifi password is hunter2-hunter2-hunter2!!",
            category = MemoryCategory.PREFERENCE,
            source = "user_correction",
        )

        assertEquals(MemoryCategory.PREFERENCE, folded.category)
        assertEquals("user_correction", folded.source)
    }

    @Test
    fun `fold never demotes an explicit category`() = runTest {
        val s = store()
        s.store("tone", "User prefers concise answers always", category = MemoryCategory.PREFERENCE)

        val folded = s.store("style", "User prefers concise answers always!!")

        assertEquals("tone", folded.key)
        assertEquals(MemoryCategory.PREFERENCE, folded.category)
    }

    @Test
    fun `exact key upsert still wins over content matching`() = runTest {
        val s = store()
        s.store("a", "The office wifi password is hunter2-hunter2-hunter2")
        s.store("a", "Completely different short text")

        assertEquals(1, s.getAllMemories().size)
        assertEquals("Completely different short text", s.getAllMemories().single().content)
    }

    @Test
    fun `short fragments never count as duplicates`() = runTest {
        val s = store()
        s.store("a", " likes tea")
        s.store("b", "Tea is great today")

        assertEquals(2, s.getAllMemories().size)
    }

    @Test
    fun `stale entries are old low-hit non-preferences`() {
        val day = 86_400_000L
        val now = 200 * day
        val entries = listOf(
            entry("rotten", "Old unreinforced factoid number one here", updatedAt = now - 200 * day, hitCount = 0),
            entry("fresh", "Recent unreinforced factoid number two here", updatedAt = now - 2 * day, hitCount = 0),
            entry("beloved", "Old but reinforced factoid number three!", updatedAt = now - 200 * day, hitCount = 9),
            entry("pref", "User adores dark mode interfaces daily", category = MemoryCategory.PREFERENCE, updatedAt = now - 400 * day, hitCount = 0),
        )

        val stale = MemoryStore(AppSettings(MapSettings())).let { s ->
            s.staleEntries(entries, now)
        }
        assertEquals(listOf("rotten"), stale.map { it.key })
    }

    @Test
    fun `duplicate groups cluster restatements`() {
        val s = store()
        val groups = s.duplicateGroups(
            listOf(
                entry("a", "The office wifi password is hunter2-hunter2-hunter2"),
                entry("b", "Unrelated fact about lunch places downtown area"),
                entry("c", "Office wifi password is hunter2-hunter2!!"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(setOf("a", "c"), groups.single().map { it.key }.toSet())
    }

    @Test
    fun `normalize collapses case punctuation and spacing`() {
        val s = store()
        assertEquals("hello world", s.normalize("  Hello,\tWORLD! "))
    }

    @Test
    @OptIn(ExperimentalTime::class)
    fun `fresh writes are never stale`() = runTest {
        val s = store()
        s.store("x", "A freshly written factoid about today")
        assertTrue(s.getStaleCandidates(kotlin.time.Clock.System.now().toEpochMilliseconds()).isEmpty())
    }

    @Test
    fun `selectExcess drops oldest unreinforced rows past the cap`() {
        val s = store()
        val entries = (0..30).map { i ->
            entry("k$i", "Distinct fact number $i about unrelated topic zzz $i", hitCount = 0, updatedAt = i.toLong())
        }

        val excess = s.selectExcess(entries, limit = 10, maxDelete = 100)

        assertEquals(21, excess.size)
        assertEquals("k0", excess.first().key)
        assertEquals("k20", excess.last().key)
    }

    @Test
    fun `selectExcess spares preferences reinforced rows and caps per pass`() {
        val s = store()
        val entries = listOf(
            entry("old", "Ancient unreinforced factoid number one here", hitCount = 0, updatedAt = 0L),
            entry("pref", "Ancient preference factoid number two here!", category = MemoryCategory.PREFERENCE, hitCount = 0, updatedAt = 0L),
            entry("loved", "Ancient reinforced factoid number three ok", hitCount = 9, updatedAt = 0L),
            entry("new", "Newer unreinforced factoid number four here", hitCount = 0, updatedAt = 999L),
        )

        val excess = s.selectExcess(entries, limit = 2, maxDelete = 100)

        assertEquals(listOf("old", "new"), excess.map { it.key })
    }

    @Test
    fun `selectExcess is empty at or under the cap`() {
        val s = store()
        val entries = listOf(entry("a", "Some sufficiently long factoid here now"))

        assertTrue(s.selectExcess(entries, limit = 1).isEmpty())
        assertTrue(s.selectExcess(entries, limit = 5).isEmpty())
    }
}
