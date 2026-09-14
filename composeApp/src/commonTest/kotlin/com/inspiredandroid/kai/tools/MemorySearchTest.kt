package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.data.AppSettings
import com.inspiredandroid.kai.data.MemoryCategory
import com.inspiredandroid.kai.data.MemoryEntry
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemorySearchTest {

    private fun entry(key: String, content: String) = MemoryEntry(
        key = key,
        content = content,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private val entries = listOf(
        entry("user_name", "The user's name is Ada"),
        entry("preferred_language", "User prefers Chinese for explanations"),
        entry("project_details", "Ada's Ada project tracks Ada runtime metrics"),
    )

    @Test
    fun `key hits outrank content-only hits`() {
        val ranked = CommonTools.rankMemories("project", entries)
        assertEquals("project_details", ranked.first().key)
    }

    @Test
    fun `multi-term queries accumulate and drop non-matches`() {
        val ranked = CommonTools.rankMemories("Ada project", entries)
        assertEquals(listOf("project_details", "user_name"), ranked.map { it.key })
    }

    @Test
    fun `cjk substring queries match without word boundaries`() {
        val ranked = CommonTools.rankMemories("中文", listOf(entry("lang", "用户偏好中文解释")))
        assertEquals(1, ranked.size)
    }

    @Test
    fun `no match and blank queries yield empty`() {
        assertTrue(CommonTools.rankMemories("zzz-nope", entries).isEmpty())
        assertTrue(CommonTools.rankMemories("   ", entries).isEmpty())
    }

    @Test
    fun `search tool is registered alongside the memory tools`() {
        val names = CommonTools.getMemoryTools(
            com.inspiredandroid.kai.data.MemoryStore(AppSettings(MapSettings())),
        ).map { it.schema.name }
        assertTrue("search_memories" in names)
    }
}
