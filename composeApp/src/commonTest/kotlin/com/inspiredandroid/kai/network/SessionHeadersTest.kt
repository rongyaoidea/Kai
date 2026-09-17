package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.data.Service
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenCode's gateway expects the full official client header set (`x-opencode-client`,
 * `x-opencode-session`, `x-opencode-project`, plus a fresh `x-opencode-request` per HTTP
 * request) and rejects free-model requests that arrive without it. The session id has to be
 * stable for the whole conversation, and no other provider may see these headers.
 *
 * Go requires the same headers, and is reached through an OpenAI-Compatible instance pointed
 * at `https://opencode.ai/zen/go/v1` — so the headers (and Zen's official-User-Agent gate)
 * also apply to OpenAI-Compatible requests whose URL targets opencode.ai. Go endpoints keep
 * Kai's own User-Agent per Go's client policy; only Zen spoofs the official one.
 */
class SessionHeadersTest {

    private val session = "x-opencode-session"
    private val client = "x-opencode-client"
    private val project = "x-opencode-project"
    private val zenChatUrl = "https://opencode.ai/zen/v1/chat/completions"
    private val goChatUrl = "https://opencode.ai/zen/go/v1/chat/completions"

    @Test
    fun openCodeRequestCarriesTheConversationIdAsSessionId() {
        val headers = sessionHeadersFor(Service.OpenCode, "conv-42")
        assertEquals("conv-42", headers[session])
        assertEquals(OPENCODE_CLIENT, headers[client])
        assertNotNull(headers[project])
        assertTrue(headers[project]!!.isNotBlank())
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
        val first = sessionHeadersFor(Service.OpenCode, null)
        val blank = sessionHeadersFor(Service.OpenCode, "  ")
        assertNotNull(first[session])
        assertTrue(first[session]!!.isNotBlank())
        assertEquals(first, blank)
    }

    @Test
    fun requestIdsAreFreshPerRequest() {
        val first = newOpenCodeRequestId()
        val second = newOpenCodeRequestId()
        assertTrue(first.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun otherProvidersGetNoSessionHeader() {
        for (service in Service.all.filter { it != Service.OpenCode && it != Service.OpenCodeGo }) {
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
        for (service in Service.all.filter { it != Service.OpenCode && it != Service.OpenCodeGo }) {
            assertEquals(null, userAgentFor(service), "unexpected UA override for ${service.id}")
        }
    }

    @Test
    fun openAICompatiblePointedAtGoCarriesTheSessionHeader() {
        val headers = sessionHeadersFor(Service.OpenAICompatible, "conv-42", goChatUrl)
        assertEquals("conv-42", headers[session])
        assertEquals(OPENCODE_CLIENT, headers[client])
        assertNotNull(headers[project])
    }

    @Test
    fun openAICompatiblePointedAtZenCarriesSessionAndOfficialUserAgent() {
        val headers = sessionHeadersFor(Service.OpenAICompatible, "conv-42", zenChatUrl)
        assertEquals("conv-42", headers[session])
        assertEquals(OPENCODE_CLIENT, headers[client])
        assertNotNull(headers[project])
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

    @Test
    fun openCodeGoPresetCarriesSessionHeaderAndKeepsKaiUserAgent() {
        val headers = sessionHeadersFor(Service.OpenCodeGo, "conv-42")
        assertEquals("conv-42", headers[session])
        assertEquals(OPENCODE_CLIENT, headers[client])
        assertNotNull(headers[project])
        assertEquals(null, userAgentFor(Service.OpenCodeGo))
        assertEquals(null, userAgentFor(Service.OpenCodeGo, goChatUrl))
    }
}
