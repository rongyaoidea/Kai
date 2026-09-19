package com.inspiredandroid.kai.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenCodeFreeTierErrorTest {

    @Test
    fun `real gateway body is recognized`() {
        assertTrue(
            isOpenCodeFreeTierError(
                errorType = "FreeTierError",
                message = "Error from provider (Console): OpenCode's free tier can only be used from within OpenCode",
            ),
        )
    }

    @Test
    fun `type and message are matched independently`() {
        assertTrue(isOpenCodeFreeTierError("FreeTierError", null))
        assertTrue(isOpenCodeFreeTierError(null, "OpenCode's free tier can only be used from within OpenCode"))
    }

    @Test
    fun `content moderation bodies are not misread as free tier limits`() {
        assertFalse(isOpenCodeFreeTierError(null, "flagged for 'violence'"))
        assertFalse(isOpenCodeFreeTierError("content_policy_violation", "content policy"))
    }

    @Test
    fun `restriction has its own user-facing resource`() {
        assertIs<UiError.Resource>(OpenAICompatibleFreeTierRestrictedException().toUiError())
    }
}
