package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LearnedSoulStoreTest {

    private fun store() = LearnedSoulStore(AppSettings(MapSettings()))

    @Test
    fun `append stores entries newest first`() = runTest {
        val store = store()
        assertTrue(store.append("a", "First"))
        assertTrue(store.append("b", "Second"))
        assertEquals(listOf("b", "a"), store.get().map { it.key })
    }

    @Test
    fun `duplicate key or text is rejected`() = runTest {
        val store = store()
        assertTrue(store.append("a", "First"))
        assertFalse(store.append("a", "Other text"))
        assertFalse(store.append("b", "First"))
        assertEquals(1, store.get().size)
    }

    @Test
    fun `remove drops the entry`() = runTest {
        val store = store()
        store.append("a", "First")
        store.remove("a")
        assertTrue(store.get().isEmpty())
    }

    @Test
    fun `cap keeps the newest entries`() = runTest {
        val store = store()
        repeat(LearnedSoulStore.MAX_ENTRIES + 3) { index ->
            store.append("k$index", "text $index")
        }
        val entries = store.get()
        assertEquals(LearnedSoulStore.MAX_ENTRIES, entries.size)
        assertEquals("k${LearnedSoulStore.MAX_ENTRIES + 2}", entries.first().key)
    }
}
