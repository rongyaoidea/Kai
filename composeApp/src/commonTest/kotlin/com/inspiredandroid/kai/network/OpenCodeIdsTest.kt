package com.inspiredandroid.kai.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OpenCodeIdsTest {

    private val sessionShape = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")
    private val requestShape = Regex("^msg_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    @Test
    fun `session for a uuid has the canonical shape`() {
        val session = OpenCodeIds.session("9f8c0d34-3b9a-4d63-8b02-1d2f3e4a5b6c")
        assertTrue(sessionShape.matches(session), "unexpected session shape: $session")
        assertTrue(OpenCodeIds.isCanonicalSession(session))
    }

    @Test
    fun `session is stable for the same conversation`() {
        assertEquals(OpenCodeIds.session("conv-42"), OpenCodeIds.session("conv-42"))
    }

    @Test
    fun `different conversations get different sessions`() {
        assertNotEquals(OpenCodeIds.session("conv-1"), OpenCodeIds.session("conv-2"))
    }

    @Test
    fun `already canonical session is returned unchanged`() {
        val official = "ses_f476ee77affe8OOHyuBpleHqgG"
        assertEquals(official, OpenCodeIds.session(official))
    }

    @Test
    fun `request ids are fresh and canonical`() {
        val first = OpenCodeIds.request()
        val second = OpenCodeIds.request()
        assertTrue(requestShape.matches(first), "unexpected request shape: $first")
        assertNotEquals(first, second)
    }

    @Test
    fun `project id is stable, canonical, and not a session`() {
        val project = OpenCodeIds.project()
        assertEquals(project, OpenCodeIds.project())
        assertTrue(project.startsWith("prj_"))
        assertFalse(OpenCodeIds.isCanonicalSession(project))
    }
}
