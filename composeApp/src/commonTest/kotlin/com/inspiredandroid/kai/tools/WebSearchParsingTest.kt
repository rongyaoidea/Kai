package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSearchParsingTest {

    private val htmlFixture = """
        <div class="result">
          <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=abc">Example <b>Title</b></a>
          <a class="result__snippet" href="https://example.com/page">First <em>snippet</em> text here</a>
        </div>
        <div class="result">
          <a rel="nofollow" class="result__a" href="https://plain.example.org/">Plain Title</a>
        </div>
    """.trimIndent()

    @Test
    fun `parses html endpoint results with snippets`() {
        val results = WebSearchTool.parseDdgHtmlResults(htmlFixture)
        assertEquals(2, results.size)
        assertEquals("Example Title", results[0]["title"])
        assertEquals("https://example.com/page", results[0]["url"])
        assertEquals("First snippet text here", results[0]["snippet"])
        assertEquals("Plain Title", results[1]["title"])
        assertEquals("https://plain.example.org/", results[1]["url"])
        assertEquals("", results[1]["snippet"])
    }

    @Test
    fun `empty html yields no results`() {
        assertTrue(WebSearchTool.parseDdgHtmlResults("<html><body>nothing</body></html>").isEmpty())
    }

    @Test
    fun `instant answer prefers answer over abstract`() {
        val body = """{"Answer":"42","AnswerURL":"https://example.com/a","AbstractText":"longer text","AbstractURL":"https://example.com/b"}"""
        val parsed = WebSearchTool.parseInstantAnswer(body)
        assertEquals("42", parsed?.get("text"))
        assertEquals("https://example.com/a", parsed?.get("url"))
    }

    @Test
    fun `instant answer falls back to abstract`() {
        val body = """{"AbstractText":"A fact","AbstractURL":"https://example.com/f","Answer":""}"""
        val parsed = WebSearchTool.parseInstantAnswer(body)
        assertEquals("A fact", parsed?.get("text"))
    }

    @Test
    fun `instant answer returns null when empty or invalid`() {
        assertNull(WebSearchTool.parseInstantAnswer("""{"AbstractText":"","Answer":""}"""))
        assertNull(WebSearchTool.parseInstantAnswer("not json"))
    }

    @Test
    fun `redirect extraction unwraps uddg`() {
        assertEquals(
            "https://example.com/a b",
            WebSearchTool.extractUrlFromRedirect("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa+b"),
        )
        assertEquals("https://x.org/", WebSearchTool.extractUrlFromRedirect("//x.org/"))
    }

    @Test
    fun `base64url decoder round-trips bing redirect payload`() {
        assertEquals(
            "https://www.linux.org/pages/download/",
            WebSearchTool.decodeBase64Url("aHR0cHM6Ly93d3cubGludXgub3JnL3BhZ2VzL2Rvd25sb2FkLw"),
        )
        assertNull(WebSearchTool.decodeBase64Url("not valid!!"))
    }

    private val bingFixture = """
        <ol id="b_results">
        <li class="b_algo" data-id><h2 class=""><a target="_blank" href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1aHR0cHM6Ly93d3cubGludXgub3JnL3BhZ2VzL2Rvd25sb2FkLw&amp;ntb=1" h="ID=SERP"><strong>Download Linux</strong> | Linux.org</a></h2><div class="b_caption"><p class="b_lineclamp2">24 Popular Linux Distributions &amp; find the one that fits.</p></div></li>
        <li class="b_algo" data-id><h2><a href="https://plain.example.org/docs">Plain Docs</a></h2></li>
        <li class="b_ad">sponsored noise without b_algo marker</li>
        </ol>
    """.trimIndent()

    @Test
    fun `parses bing algo blocks with redirect decode`() {
        val results = WebSearchTool.parseBingResults(bingFixture)
        assertEquals(2, results.size)
        assertEquals("Download Linux | Linux.org", results[0]["title"])
        assertEquals("https://www.linux.org/pages/download/", results[0]["url"])
        assertEquals("24 Popular Linux Distributions & find the one that fits.", results[0]["snippet"])
        assertEquals("Plain Docs", results[1]["title"])
        assertEquals("https://plain.example.org/docs", results[1]["url"])
        assertEquals("", results[1]["snippet"])
    }

    @Test
    fun `bing parse ignores pages without algo blocks`() {
        assertTrue(WebSearchTool.parseBingResults("<html><body>no results</body></html>").isEmpty())
    }

    private val marginaliaFixture = """
        <h2 class="text-md font-serif">
          <a href="https://example.org/guide" rel="noopener noreferrer" dir="auto">Example<wbr> Guide</a>
        </h2>
        <div class="text-sm mt-1">
          <a class="underline break-all" href="https://example.org/guide" rel="noopener noreferrer" tabindex="-1">example.org/guide</a>
        </div>
        <p class="mt-2 text-sm leading-relaxed" dir="auto">A practical guide with real examples.</p>
    """.trimIndent()

    @Test
    fun `parses marginalia dir-auto anchors with following snippet`() {
        val results = WebSearchTool.parseMarginaliaResults(marginaliaFixture)
        assertEquals(1, results.size)
        assertEquals("Example Guide", results[0]["title"])
        assertEquals("https://example.org/guide", results[0]["url"])
        assertEquals("A practical guide with real examples.", results[0]["snippet"])
    }

    @Test
    fun `marginalia parse ignores pages without result anchors`() {
        assertTrue(WebSearchTool.parseMarginaliaResults("<html><body>nothing</body></html>").isEmpty())
    }
}
