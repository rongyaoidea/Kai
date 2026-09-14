package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.NotificationRecord
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntentStoreTest {

    private fun store(settings: MapSettings = MapSettings()) = IntentStore(AppSettings(settings))

    private fun record(
        id: String,
        packageName: String = "com.bank",
        title: String = "Alert",
        text: String = "Large transfer detected",
        postedAt: Long = 1L,
        isOngoing: Boolean = false,
    ) = NotificationRecord(
        id = id,
        packageName = packageName,
        appLabel = "Bank",
        title = title,
        text = text,
        postedAtEpochMs = postedAt,
        isOngoing = isOngoing,
    )

    private fun intent(packageName: String = "", keyword: String = "") = NotificationIntent(
        id = "x",
        packageName = packageName,
        keyword = keyword,
        prompt = "Summarize",
    )

    @Test
    fun `matching respects package keyword and ongoing filters`() {
        val rec = record("1")
        assertTrue(IntentStore.matches(intent(), rec))
        assertTrue(IntentStore.matches(intent(packageName = "com.bank"), rec))
        assertTrue(IntentStore.matches(intent(keyword = "transfer"), rec))
        assertFalse(IntentStore.matches(intent(packageName = "com.other"), rec))
        assertFalse(IntentStore.matches(intent(keyword = "lottery"), rec))
        assertFalse(IntentStore.matches(intent(), rec.copy(isOngoing = true)))
        assertTrue(IntentStore.matches(intent(packageName = "COM.BANK"), rec))
        assertTrue(IntentStore.matches(intent(keyword = "TRANSFER"), rec))
    }

    @Test
    fun `add list and remove round-trip`() = runTest {
        val s = store()
        val added = s.add(NotificationIntent(id = "", packageName = "com.bank", keyword = "", prompt = "Summarize"))

        assertTrue(added.id.isNotEmpty())
        assertEquals(1, s.list().size)
        assertTrue(s.remove(added.id))
        assertTrue(s.list().isEmpty())
        assertFalse(s.remove("nope"))
    }

    @Test
    fun `claim dedupes refires and advances the watermark`() = runTest {
        val s = store()

        assertEquals(listOf("a", "b"), s.claim(listOf("a", "b"), 10L))
        assertEquals(emptyList(), s.claim(listOf("a"), 10L))
        assertEquals(10L, s.watermark())
        assertEquals(listOf("c"), s.claim(listOf("b", "c"), 20L))
        assertEquals(20L, s.watermark())
    }

    @Test
    fun `state persists across store instances`() = runTest {
        val settings = MapSettings()
        store(settings).add(NotificationIntent(id = "", prompt = "Summarize"))

        assertEquals(1, store(settings).list().size)
    }
}
