package com.inspiredandroid.kai.tools

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Priority selection and the per-source bench ([SourceCircuit]).
 *
 * Han queries lead with the engines that are reachable in mainland China
 * (Bing) and demote DuckDuckGo, which needs a VPN there; every other query
 * keeps the long-standing DuckDuckGo-first order.
 */
class WebSearchSourcesTest {

    @Test
    fun `han queries use the china first order`() {
        assertTrue(containsCjk("上海天气"))
        assertTrue(containsCjk("iphone 价格对比"))
        assertFalse(containsCjk("shanghai weather"))
        assertFalse(containsCjk("python asyncio"))

        assertEquals("bing", orderedSourceIds("上海天气").first())
        assertEquals("ddg-html", orderedSourceIds("shanghai weather").first())
    }

    @Test
    fun `both orders contain every source exactly once`() {
        assertEquals(SOURCE_PRIORITY_GLOBAL.sorted(), orderedSourceIds("hello").sorted())
        assertEquals(SOURCE_PRIORITY_CJK.sorted(), orderedSourceIds("你好").sorted())
        assertEquals(SOURCE_PRIORITY_GLOBAL.size, SOURCE_PRIORITY_GLOBAL.toSet().size)
        assertEquals(SOURCE_PRIORITY_CJK.size, SOURCE_PRIORITY_CJK.toSet().size)
    }

    @Test
    fun `captcha detection matches markers anywhere in the head`() {
        assertTrue(looksLikeCaptcha("<html><body>Our systems have detected unusual traffic</body></html>", DEFAULT_CAPTCHA_MARKERS))
        assertTrue(looksLikeCaptcha("<html>VERIFY YOU ARE HUMAN</html>", DEFAULT_CAPTCHA_MARKERS))
        assertTrue(looksLikeCaptcha("<html>百度安全验证</html>", listOf("百度安全验证")))
        // A result page that merely mentions the word does not match a marker.
        assertFalse(looksLikeCaptcha("<html><a>how to solve a captcha</a></html>", DEFAULT_CAPTCHA_MARKERS))
        assertFalse(looksLikeCaptcha("<html>normal results</html>", DEFAULT_CAPTCHA_MARKERS))
    }

    @Test
    fun `failure classification routes to the right bench policy`() {
        assertEquals(SourceFailureKind.CAPTCHA, classifyFailure(CaptchaFailure()))
        assertEquals(SourceFailureKind.HTTP, classifyFailure(HttpStatusFailure(403)))
        assertEquals(SourceFailureKind.NETWORK, classifyFailure(Exception("connection reset")))
    }

    @Test
    fun `two network strikes bench a source and success clears it`() = runTest {
        val circuit = SourceCircuit()
        val id = "ddg-html"

        circuit.recordFailure(id, SourceFailureKind.NETWORK)
        assertFalse(circuit.isBenched(id), "one transient failure must not bench")

        circuit.recordFailure(id, SourceFailureKind.NETWORK)
        assertTrue(circuit.isBenched(id))
        assertTrue(circuit.remainingMs(id) > 0)

        circuit.recordSuccess(id)
        assertFalse(circuit.isBenched(id))
    }

    @Test
    fun `a captcha benches on the first strike`() = runTest {
        val circuit = SourceCircuit()

        circuit.recordFailure("baidu", SourceFailureKind.CAPTCHA)

        assertTrue(circuit.isBenched("baidu"))
    }

    @Test
    fun `bench expires with time and starts a fresh strike count`() = runTest {
        var now = 0L
        val circuit = SourceCircuit(nowMs = { now })

        circuit.recordFailure("bing", SourceFailureKind.NETWORK)
        circuit.recordFailure("bing", SourceFailureKind.NETWORK)
        assertTrue(circuit.isBenched("bing"))

        now += 11 * 60 * 1000L
        assertFalse(circuit.isBenched("bing"), "expired bench must retry")

        // A single new strike after expiry must not re-bench from the old count.
        circuit.recordFailure("bing", SourceFailureKind.NETWORK)
        assertFalse(circuit.isBenched("bing"))
    }

    @Test
    fun `http failures bench after two strikes`() = runTest {
        val circuit = SourceCircuit()

        circuit.recordFailure("marginalia", SourceFailureKind.HTTP)
        assertFalse(circuit.isBenched("marginalia"))
        circuit.recordFailure("marginalia", SourceFailureKind.HTTP)
        assertTrue(circuit.isBenched("marginalia"))
    }

    @Test
    fun `http status failure carries the code for telemetry`() {
        assertEquals("http 403", HttpStatusFailure(403).message)
        assertEquals("captcha", CaptchaFailure().message)
    }
}
