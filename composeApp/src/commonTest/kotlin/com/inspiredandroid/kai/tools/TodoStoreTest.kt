package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoStoreTest {

    private fun store(settings: MapSettings = MapSettings()) = TodoStore(AppSettings(settings))

    @Test
    fun `add and list round-trip within a bucket`() = runTest {
        val s = store()

        val first = s.add("chat-a", "Write the report").getOrThrow()
        s.add("chat-a", "Send the report").getOrThrow()
        s.setStatus("chat-a", first.id, TodoStatus.COMPLETED)

        val items = s.list("chat-a")
        assertEquals(2, items.size)
        assertEquals(TodoStatus.COMPLETED, items[0].effectiveStatus())
        assertEquals("Write the report", items[0].text)
        assertEquals(TodoStatus.PENDING, items[1].effectiveStatus())
    }

    @Test
    fun `start moves an item to in-progress and reopen returns it to pending`() = runTest {
        val s = store()
        val item = s.add("c", "Do the thing").getOrThrow()

        assertTrue(s.setStatus("c", item.id, TodoStatus.IN_PROGRESS))
        assertEquals(TodoStatus.IN_PROGRESS, s.list("c").single().effectiveStatus())
        assertTrue(s.setStatus("c", item.id, TodoStatus.PENDING))
        assertEquals(TodoStatus.PENDING, s.list("c").single().effectiveStatus())
        assertFalse(s.setStatus("c", item.id, "bogus"))
    }

    @Test
    fun `buckets are isolated per conversation`() = runTest {
        val s = store()
        s.add("chat-a", "A task").getOrThrow()

        assertEquals(1, s.list("chat-a").size)
        assertTrue(s.list("chat-b").isEmpty())
        assertTrue(s.list(TodoStore.DEFAULT_BUCKET).isEmpty())
    }

    @Test
    fun `remove and clearDone prune the list`() = runTest {
        val s = store()
        val keep = s.add("c", "keep me").getOrThrow()
        val drop = s.add("c", "drop me").getOrThrow()
        s.setStatus("c", drop.id, TodoStatus.COMPLETED)

        assertTrue(s.remove("c", drop.id))
        assertEquals(listOf(keep.id), s.list("c").map { it.id })
        assertEquals(0, s.clearDone("c"))

        s.setStatus("c", keep.id, TodoStatus.COMPLETED)
        assertEquals(1, s.clearDone("c"))
        assertTrue(s.list("c").isEmpty())
    }

    @Test
    fun `unknown ids report false`() = runTest {
        val s = store()
        assertFalse(s.setStatus("c", "nope", TodoStatus.COMPLETED))
        assertFalse(s.remove("c", "nope"))
    }

    @Test
    fun `lists persist across store instances on the same settings`() = runTest {
        val settings = MapSettings()
        store(settings).add("c", "survive restart").getOrThrow()

        val items = store(settings).list("c")
        assertEquals(1, items.size)
        assertEquals("survive restart", items[0].text)
    }

    @Test
    fun `corrupt blob degrades to empty rather than crashing`() = runTest {
        val settings = MapSettings()
        val appSettings = AppSettings(settings)
        appSettings.setTodoJson("{not-json")

        assertTrue(store(settings).list("c").isEmpty())
    }

    @Test
    fun `list caps at one hundred items`() = runTest {
        val s = store()
        repeat(100) { s.add("c", "item $it").getOrThrow() }

        val over = s.add("c", "one too many")
        assertTrue(over.isFailure)
        assertEquals(100, s.list("c").size)
    }
}
