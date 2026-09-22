package com.inspiredandroid.kai.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the auto-scroll policy: a reader must never be yanked to the bottom by background
 * appends (heartbeat runs mirroring into the viewed conversation), while explicit submissions
 * and the user's own streaming reply still follow.
 */
class ChatAutoScrollTest {

    @Test
    fun `first paint after opening a conversation follows`() {
        assertTrue(
            shouldFollowNewestItem(
                neverLaidOut = true,
                atBottom = false,
                userSubmitted = false,
                foregroundReply = false,
            ),
        )
    }

    @Test
    fun `sending a message follows even when scrolled up`() {
        assertTrue(
            shouldFollowNewestItem(
                neverLaidOut = false,
                atBottom = false,
                userSubmitted = true,
                foregroundReply = false,
            ),
        )
    }

    @Test
    fun `the user's streaming reply follows while at the bottom`() {
        assertTrue(
            shouldFollowNewestItem(
                neverLaidOut = false,
                atBottom = true,
                userSubmitted = false,
                foregroundReply = true,
            ),
        )
    }

    @Test
    fun `the user's streaming reply stops following once they scroll up`() {
        assertFalse(
            shouldFollowNewestItem(
                neverLaidOut = false,
                atBottom = false,
                userSubmitted = false,
                foregroundReply = true,
            ),
        )
    }

    @Test
    fun `a background append never follows, even at the bottom`() {
        assertFalse(
            shouldFollowNewestItem(
                neverLaidOut = false,
                atBottom = true,
                userSubmitted = false,
                foregroundReply = false,
            ),
        )
    }

    @Test
    fun `a background append while reading older content never follows`() {
        assertFalse(
            shouldFollowNewestItem(
                neverLaidOut = false,
                atBottom = false,
                userSubmitted = false,
                foregroundReply = false,
            ),
        )
    }
}
