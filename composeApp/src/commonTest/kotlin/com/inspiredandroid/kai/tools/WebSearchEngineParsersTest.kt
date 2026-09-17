package com.inspiredandroid.kai.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsers for the engines that make web_search usable in mainland China
 * (Baidu, Sogou, 360, the Baidu News RSS feed) plus Yandex.
 */
class WebSearchEngineParsersTest {

    private val baiduFixture = """
        <div class="result c-container xpath-log" id="1">
          <h3 class="t"><a href="http://www.baidu.com/link?url=abc&amp;wd=1">示例 <em>标题</em></a></h3>
          <div class="c-abstract">第一段<em>摘要</em>。</div>
        </div>
        <div class="result-op c-container" id="ad"><h3 class="t"><a href="http://ad.example.com/">广告</a></h3></div>
        <div class="result c-container" id="2">
          <h3><a href="https://plain.example.org/x">Plain &amp; Title</a></h3>
          <span class="content-right_1a2b3">Second snippet</span>
        </div>
    """.trimIndent()

    @Test
    fun `parses baidu organic blocks and skips ads`() {
        val results = WebSearchTool.parseBaiduResults(baiduFixture)

        assertEquals(2, results.size)
        assertEquals("示例 标题", results[0]["title"])
        assertEquals("http://www.baidu.com/link?url=abc&wd=1", results[0]["url"])
        assertEquals("第一段摘要。", results[0]["snippet"])
        assertEquals("Plain & Title", results[1]["title"])
        assertEquals("https://plain.example.org/x", results[1]["url"])
        assertEquals("Second snippet", results[1]["snippet"])
    }

    private val sogouFixture = """
        <div class="vrwrap">
          <h3 class="vr-title"><a href="https://example.com/s1" target="_blank">搜狗 <em>结果</em></a></h3>
          <div class="text-layout">一段摘要</div>
        </div>
    """.trimIndent()

    @Test
    fun `parses sogou vrwrap blocks`() {
        val results = WebSearchTool.parseSogouResults(sogouFixture)

        assertEquals(1, results.size)
        assertEquals("搜狗 结果", results[0]["title"])
        assertEquals("https://example.com/s1", results[0]["url"])
        assertEquals("一段摘要", results[0]["snippet"])
    }

    private val so360Fixture = """
        <li class="res-list">
          <h3 class="res-title "><a href="https://www.so.com/link?m=abc" target="_blank">360 <em>标题</em></a></h3>
          <p class="res-desc">描述文本</p>
        </li>
    """.trimIndent()

    @Test
    fun `parses 360 res-list items`() {
        val results = WebSearchTool.parseSo360Results(so360Fixture)

        assertEquals(1, results.size)
        assertEquals("360 标题", results[0]["title"])
        assertEquals("https://www.so.com/link?m=abc", results[0]["url"])
        assertEquals("描述文本", results[0]["snippet"])
    }

    private val yandexFixture = """
        <li class="serp-item">
          <a class="OrganicTitle-Link" href="https://example.org/y1">Yandex <b>Title</b></a>
          <span class="OrganicTextContentSpan">Yandex snippet</span>
        </li>
    """.trimIndent()

    @Test
    fun `parses yandex organic title anchors`() {
        val results = WebSearchTool.parseYandexResults(yandexFixture)

        assertEquals(1, results.size)
        assertEquals("Yandex Title", results[0]["title"])
        assertEquals("https://example.org/y1", results[0]["url"])
        assertEquals("Yandex snippet", results[0]["snippet"])
    }

    private val newsRssFixture = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
        <item>
          <title><![CDATA[央行：7月政府债券净融资13176.4亿元]]></title>
          <link>https://example.com/news/1</link>
          <description><![CDATA[较上年同期增加694.6亿元 &amp; 更多]]></description>
        </item>
        <item>
          <title>Plain headline</title>
          <link>https://example.com/news/2</link>
          <description>no cdata here</description>
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    fun `parses baidu news rss with and without cdata`() {
        val results = WebSearchTool.parseBaiduNewsRss(newsRssFixture)

        assertEquals(2, results.size)
        assertEquals("央行：7月政府债券净融资13176.4亿元", results[0]["title"])
        assertEquals("https://example.com/news/1", results[0]["url"])
        assertEquals("较上年同期增加694.6亿元 & 更多", results[0]["snippet"])
        assertEquals("Plain headline", results[1]["title"])
    }

    @Test
    fun `empty fixtures yield no results`() {
        assertTrue(WebSearchTool.parseBaiduResults("<html>nothing</html>").isEmpty())
        assertTrue(WebSearchTool.parseSogouResults("<html>nothing</html>").isEmpty())
        assertTrue(WebSearchTool.parseSo360Results("<html>nothing</html>").isEmpty())
        assertTrue(WebSearchTool.parseYandexResults("<html>nothing</html>").isEmpty())
        assertTrue(WebSearchTool.parseBaiduNewsRss("<rss></rss>").isEmpty())
    }

    @Test
    fun `count caps every parser`() {
        assertEquals(1, WebSearchTool.parseBaiduResults(baiduFixture, maxResults = 1).size)
        assertEquals(1, WebSearchTool.parseBaiduNewsRss(newsRssFixture, maxResults = 1).size)
    }

    @Test
    fun `time filtered searches slot the news feed in after the lead source`() {
        val withNews = orderedSourceIds("上海天气", includeNews = true)
        val withoutNews = orderedSourceIds("上海天气", includeNews = false)

        assertEquals("baidu", withNews.first())
        assertEquals("baidu-news", withNews[1])
        assertEquals(withoutNews.size + 1, withNews.size)
        assertTrue("baidu-news" !in withoutNews)
        // The chain's tail keeps its order after the insertion.
        assertEquals(withoutNews.drop(1), withNews.drop(2))
    }

    @Test
    fun `english time filtered searches also use the news feed`() {
        val withNews = orderedSourceIds("interest rates", includeNews = true)

        assertEquals("baidu-news", withNews[1])
    }
}
