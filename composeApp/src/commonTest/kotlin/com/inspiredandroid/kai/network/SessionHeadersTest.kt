package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.data.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenCode Zen rejects requests that arrive without `x-opencode-session`. The id has to be
 * stable for the whole conversation, and no other provider may see the header.
 *
 * Go requires the same header, and is reached through an OpenAI-Compatible instance pointed
 * at `https://opencode.ai/zen/go/v1` — so the header (and Zen's official-User-Agent gate)
 * also apply to OpenAI-Compatible requests whose URL targets opencode.ai. Go endpoints keep
 * Kai's own User-Agent per Go's client policy; only Zen spoofs the official one.
 */
class SessionHeadersTest {

    private val header = "x-opencode-session"
    private val zenChatUrl = "https://opencode.ai/zen/v1/chat/completions"
    private val goChatUrl = "https://opencode.ai/zen/go/v1/chat/completions"

    @Test
    fun openCodeRequestCarriesTheConversationIdAsSessionId() {
        assertEquals(
            mapOf(header to "conv-42"),
            sessionHeadersFor(Service.OpenCode, "conv-42"),
        )
    }

    @Test
    fun sameConversationKeepsTheSameSessionId() {
        assertEquals(
            sessionHeadersFor(Service.OpenCode, "conv-42"),
            sessionHeadersFor(Service.OpenCode, "conv-42"),
        )
    }

    @Test
    fun requestsOutsideAnyConversationStillCarryAStableSessionId() {
        val first = sessionHeadersFor(Service.OpenCode, null)[header]
        val blank = sessionHeadersFor(Service.OpenCode, "  ")[header]
        assertNotNull(first)
        assertTrue(first.isNotBlank())
        assertEquals(first, blank)
    }

    @Test
    fun otherProvidersGetNoSessionHeader() {
        for (service in Service.all.filter { it != Service.OpenCode }) {
            assertEquals(emptyMap(), sessionHeadersFor(service, "conv-42"), "unexpected header for ${service.id}")
        }
    }

    @Test
    fun openCodeRequestsCarryTheOfficialUserAgent() {
        assertEquals(OPENCODE_USER_AGENT, userAgentFor(Service.OpenCode))
        assertTrue(OPENCODE_USER_AGENT.startsWith("opencode/"))
    }

    @Test
    fun otherProvidersKeepTheDefaultUserAgent() {
        for (service in Service.all.filter { it != Service.OpenCode }) {
            assertEquals(null, userAgentFor(service), "unexpected UA override for ${service.id}")
        }
    }

    @Test
    fun openAICompatiblePointedAtGoCarriesTheSessionHeader() {
        assertEquals(
            mapOf(header to "conv-42"),
            sessionHeadersFor(Service.OpenAICompatible, "conv-42", goChatUrl),
        )
    }

    @Test
    fun openAICompatiblePointedAtZenCarriesSessionAndOfficialUserAgent() {
        assertEquals(
            mapOf(header to "conv-42"),
            sessionHeadersFor(Service.OpenAICompatible, "conv-42", zenChatUrl),
        )
        assertEquals(OPENCODE_USER_AGENT, userAgentFor(Service.OpenAICompatible, zenChatUrl))
    }

    @Test
    fun goEndpointsKeepKaiUserAgent() {
        assertEquals(null, userAgentFor(Service.OpenAICompatible, goChatUrl))
        assertEquals(null, userAgentFor(Service.OpenAICompatible, "https://opencode.ai/zen/go/v1/models"))
    }

    @Test
    fun openAICompatibleWithUnrelatedBaseUrlIsUnaffected() {
        val url = "http://localhost:11434/v1/chat/completions"
        assertEquals(emptyMap(), sessionHeadersFor(Service.OpenAICompatible, "conv-42", url))
        assertEquals(null, userAgentFor(Service.OpenAICompatible, url))
    }
}
