package com.inspiredandroid.kai.data

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppCardStoreTest {

    private fun store() = AppCardStore(AppSettings(MapSettings()))

    @Test
    fun `set upserts one card per package, newest first`() = runTest {
        val store = store()
        assertTrue(store.set("com.a", "first"))
        assertTrue(store.set("com.b", "b card"))
        assertTrue(store.set("com.a", "updated"))
        assertEquals(listOf("com.a", "com.b"), store.get().map { it.packageName })
        assertEquals("updated", store.forPackage("com.a"))
    }

    @Test
    fun `blank input is rejected and remove drops the card`() = runTest {
        val store = store()
        assertFalse(store.set("com.a", "   "))
        assertFalse(store.set("  ", "note"))
        store.set("com.a", "note")
        store.remove("com.a")
        assertNull(store.forPackage("com.a"))
    }

    @Test
    fun `caps stored cards`() = runTest {
        val store = store()
        repeat(AppCardStore.MAX_CARDS) { index -> store.set("com.pkg$index", "note $index") }
        assertFalse(store.set("com.extra", "nope"))
        assertEquals(AppCardStore.MAX_CARDS, store.get().size)
    }
}
