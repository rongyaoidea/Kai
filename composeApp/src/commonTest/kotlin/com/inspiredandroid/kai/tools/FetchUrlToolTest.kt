package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the `fetch_url` SSRF blocklist: private/loopback addresses must be
 * rejected in every notation they can be written (dotted, octal, hex, single
 * number, IPv6 including zone-scoped forms), while public hosts stay reachable.
 */
class FetchUrlToolTest {

    @Test
    fun `url normalization trims wrappers, entities, and missing schemes`() {
        assertEquals("https://example.com/path", FetchUrlTool.normalizeUrl("  <https://example.com/path>  "))
        assertEquals("https://example.com/path", FetchUrlTool.normalizeUrl("\"https://example.com/path\""))
        assertEquals("https://example.com/path", FetchUrlTool.normalizeUrl("`https://example.com/path`"))
        assertEquals("https://example.com", FetchUrlTool.normalizeUrl("example.com"))
        assertEquals("https://example.com/x", FetchUrlTool.normalizeUrl("[link](https://example.com/x)"))
        assertEquals("https://example.com/?a=1&b=2", FetchUrlTool.normalizeUrl("https://example.com/?a=1&amp;b=2"))
    }

    @Test
    fun `url normalization rejects unusable input`() {
        assertNull(FetchUrlTool.normalizeUrl(""))
        assertNull(FetchUrlTool.normalizeUrl("   "))
        assertNull(FetchUrlTool.normalizeUrl("ftp://example.com/file"))
        assertNull(FetchUrlTool.normalizeUrl("not a url at all"))
        assertNull(FetchUrlTool.normalizeUrl("https://"))
    }

    @Test
    fun `private and loopback hosts are blocked`() {
        val blocked = listOf(
            "localhost",
            "internal.localhost",
            "127.0.0.1",
            "127.1.2.3",
            "10.0.0.1",
            "0.0.0.0",
            "169.254.1.1",
            "192.168.1.1",
            "172.16.0.1",
            "172.31.255.255",
            "::1",
            "[::1]",
            "::1%eth0",
            "0:0:0:0:0:0:0:1",
            "::ffff:127.0.0.1",
            "fe80::1",
            "fe80::1%wlan0",
            "fd00::1",
            "fc00::1",
            "2130706433",
            "0x7f000001",
            "0177.0.0.1",
            // inet_aton short forms resolve to the same addresses.
            "127.1",
            "10.1",
            "0x7f.1",
            "0x7f.0x0.0x0.0x1",
            "127.0.1",
            "10.0.1",
            "192.168.1",
            "169.254.1",
            "::",
            "::ffff:10.0.0.1",
            "::ffff:192.168.0.1",
        )
        for (host in blocked) {
            assertTrue(FetchUrlTool.isBlockedHost(host), "expected $host to be blocked")
        }
    }

    @Test
    fun `short public forms stay allowed`() {
        for (host in listOf("8.8", "1.1", "11.1", "172.32.1")) {
            assertFalse(FetchUrlTool.isBlockedHost(host), "expected $host to be allowed")
        }
    }

    @Test
    fun `shared host check fails closed on blank hosts`() {
        assertTrue(blockedUrlHostReason("https://")?.isNotBlank() == true)
        assertTrue(blockedUrlHostReason("http://127.0.0.1/")?.contains("blocked host") == true)
        assertNull(blockedUrlHostReason("https://example.com/"))
    }

    @Test
    fun `public hosts are allowed`() {
        val allowed = listOf(
            "example.com",
            "api.openai.com",
            "8.8.8.8",
            "1.1.1.1",
            "172.32.0.1",
            "172.15.0.1",
            "169.253.0.1",
            "11.0.0.1",
            "2607:f8b0::1",
            // Hostnames starting with fc/fd are not IPv6 literals and must not
            // be mistaken for unique-local addresses.
            "fcc.gov",
            "fdic.gov",
        )
        for (host in allowed) {
            assertFalse(FetchUrlTool.isBlockedHost(host), "expected $host to be allowed")
        }
    }
}
