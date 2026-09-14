package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_web_search_description
import kai.composeapp.generated.resources.tool_web_search_name
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MAX_RESULTS = 5

/** Hard cap for the `count` argument — more than this is noise for the model. */
private const val MAX_COUNT = 10

/** Per-source budget. All sources race in parallel, so the worst case stays near this. */
private const val SOURCE_TIMEOUT_MS = 12_000L

/** Source ids used in `sources` / `source_status` telemetry. */
private const val SRC_INSTANT = "ddg-instant"
private const val SRC_DDG_HTML = "ddg-html"
private const val SRC_DDG_LITE = "ddg-lite"
private const val SRC_BING = "bing"
private const val SRC_MARGINALIA = "marginalia"

/** `time` argument → DuckDuckGo `df` param. Null means unfiltered. */
internal fun timeFilterParam(time: String?): String? = when (time?.trim()?.lowercase()) {
    "day", "d", "past-day", "past_day" -> "d"
    "week", "w", "past-week", "past_week" -> "w"
    "month", "m", "past-month", "past_month" -> "m"
    else -> null
}

/** `count` argument → clamped result limit. */
internal fun clampCount(count: Int?): Int = (count ?: MAX_RESULTS).coerceIn(1, MAX_COUNT)

object WebSearchTool : Tool {
    private val liteLinkRegex = Regex("""<a[^>]+class=['"]result-link['"][^>]*>([\s\S]*?)</a>""")
    private val liteHrefRegex = Regex("""href=['"]([^'"]*?)['"]""")
    private val liteSnippetRegex = Regex("""<td[^>]+class=['"]result-snippet['"][^>]*>([\s\S]*?)</td>""")
    private val liteFullLinkRegex = Regex("""<a\s[^>]*class=['"]result-link['"][^>]*>""")
    private val htmlLinkRegex = Regex("""<a[^>]+class=['"]result__a['"][^>]*>([\s\S]*?)</a>""")
    private val htmlSnippetRegex = Regex("""<a[^>]+class=['"]result__snippet['"][^>]*>([\s\S]*?)</a>""")
    private val uddgRegex = Regex("""uddg=([^&]+)""")
    private val htmlTagRegex = Regex("<[^>]*>")
    private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    // Bing: result items are `<li class="b_algo">` segments; the title link
    // usually points at a /ck/a redirect carrying the target base64url-encoded
    // in `u=a1…`, and the snippet is the first <p> inside `.b_caption`.
    private val bingTitleRegex = Regex("""<h2[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
    private val bingRedirectRegex = Regex("""[?&]u=a1([^&]+)""")
    private val bingCaptionPRegex = Regex("""<div class="b_caption"[^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)</p>""")

    // Marginalia (marginalia-search.com): Tailwind markup with no semantic
    // result classes, so titles are the anchors carrying dir="auto" and the
    // snippet is the following `<p class="mt-2 …">` paragraph.
    private val margAnchorRegex = Regex("""<a\s[^>]*href="(https?://[^"]+)"[^>]*>""")

    private val json = Json { ignoreUnknownKeys = true }

    override val schema = ToolSchema(
        name = "web_search",
        description = "Search the web for current information. Returns a direct answer when one is available, plus titles, URLs, and snippets. Before answering questions about recent events, news, current prices, weather, or anything time-sensitive, search first. Also use this when you're unsure about facts or the user asks you to look something up. Pass count (1-10) to control result volume and time (day/week/month) to restrict recency — time applies to the DuckDuckGo sources; use it for news and other time-sensitive queries.",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "count" to ParameterSchema("integer", "Max results to return, 1-10 (default 5)", false),
            "time" to ParameterSchema("string", "Recency filter: any, day, week, month (default any). Applies to DuckDuckGo results.", false),
        ),
    )

    private val client = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
        }
    }

    override suspend fun execute(args: Map<String, Any>): Any {
        val query = args["query"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return mapOf("success" to false, "error" to "Query is required")
        val count = clampCount((args["count"] as? Number)?.toInt() ?: args["count"]?.toString()?.toIntOrNull())
        val time = (args["time"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            searchChain(query, count, time)
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "Search failed: ${e.message}")
        }
    }

    /** One source's outcome. [error] is null on clean runs (even empty ones) — it names the failure, not the absence. */
    private data class SourceLoad(
        val name: String,
        val items: List<Map<String, String>>,
        val error: String?,
    ) {
        val status: String get() = if (error != null) "failed: $error" else if (items.isEmpty()) "empty" else "ok (${items.size})"
    }

    /**
     * No-API-key chain, raced in parallel: DuckDuckGo (instant answer + html +
     * lite), then Bing HTML, then Marginalia. The old code walked the chain
     * sequentially (answer+html → lite → bing → marginalia), so a slow first
     * source could eat the whole tool timeout before the fallbacks were even
     * tried. Now every source gets the same 12s budget concurrently and the
     * first non-empty source in priority order wins, so worst case stays flat.
     */
    private suspend fun searchChain(query: String, count: Int, time: String?): Map<String, Any> = coroutineScope {
        val encoded = query.encodeURLQueryComponent()
        val df = timeFilterParam(time)
        val instant = async { loadAnswer(encoded) }
        val html = async { loadList(SRC_DDG_HTML) { searchDdgHtml(encoded, df, count) } }
        val lite = async { loadList(SRC_DDG_LITE) { searchDdgLite(encoded, df, count) } }
        val bing = async { loadList(SRC_BING) { searchBingHtml(encoded, count) } }
        val marginalia = async { loadList(SRC_MARGINALIA) { searchMarginalia(encoded, count) } }

        val (answer, answerStatus) = instant.await()
        val lists = listOf(html.await(), lite.await(), bing.await(), marginalia.await())
        val winner = lists.firstOrNull { it.items.isNotEmpty() }
        val results = winner?.items.orEmpty()

        if (results.isNotEmpty() || answer != null) {
            val sources = buildList {
                if (answer != null) add(SRC_INSTANT)
                if (winner != null) add(winner.name)
            }
            return@coroutineScope buildMap<String, Any> {
                if (answer != null) put("answer", answer)
                put("results", results)
                put("sources", sources)
            }.let { mapOf("success" to true) + it }
        }
        // Nothing anywhere: report per-source status instead of a bare "no
        // results", so parser rot (a source suddenly returning empty/failed
        // on every query) is distinguishable from a genuinely empty web.
        val status = buildMap<String, String> {
            put(SRC_INSTANT, answerStatus)
            for (load in lists) put(load.name, load.status)
        }
        mapOf(
            "success" to true,
            "results" to emptyList<Any>(),
            "message" to "No results found",
            "source_status" to status,
        )
    }

    private suspend fun loadAnswer(encodedQuery: String): Pair<Map<String, String>?, String> {
        return try {
            val answer = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { fetchInstantAnswer(encodedQuery) }
            if (answer != null) answer to "ok" else null to "empty"
        } catch (e: Exception) {
            null to "failed: ${shortError(e)}"
        }
    }

    private suspend fun loadList(name: String, block: suspend () -> List<Map<String, String>>): SourceLoad {
        return try {
            val items = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() }
                ?: return SourceLoad(name, emptyList(), "timeout after ${SOURCE_TIMEOUT_MS / 1000}s")
            SourceLoad(name, items, null)
        } catch (e: Exception) {
            SourceLoad(name, emptyList(), shortError(e))
        }
    }

    private fun shortError(e: Exception): String = (e.message ?: e::class.simpleName ?: "error").take(120)

    internal fun parseDdgHtmlResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val links = htmlLinkRegex.findAll(html).toList()
        val snippets = htmlSnippetRegex.findAll(html).toList()
        for (i in links.indices) {
            if (results.size >= maxResults) break
            val tag = links[i].value
            val href = liteHrefRegex.find(tag)?.groupValues?.get(1) ?: continue
            val title = links[i].groupValues[1].stripHtml().trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.stripHtml()?.trim() ?: ""
            val url = extractUrlFromRedirect(href)
            if (url.isNotBlank() && title.isNotBlank()) {
                results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
            }
        }
        return results
    }

    private suspend fun searchDdgHtml(encodedQuery: String, df: String?, count: Int): List<Map<String, String>> {
        val url = buildString {
            append("https://html.duckduckgo.com/html/?q=$encodedQuery")
            if (df != null) append("&df=$df")
        }
        val html = getText(url, "Mozilla/5.0 (compatible; Kai/1.0)")
        return parseDdgHtmlResults(html, count)
    }

    internal fun parseInstantAnswer(body: String): Map<String, String>? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return null
        }
        fun field(name: String): String = try {
            root[name]?.jsonPrimitive?.content?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
        // Prefer a computed answer, then definition, then abstract.
        val answer = field("Answer")
        if (answer.isNotBlank()) return mapOf("text" to answer, "url" to field("AnswerURL"))
        val definition = field("Definition")
        if (definition.isNotBlank()) {
            return mapOf("text" to definition, "url" to field("DefinitionURL"))
        }
        val abstract = field("AbstractText")
        if (abstract.isNotBlank()) {
            return mapOf("text" to abstract, "url" to field("AbstractURL"))
        }
        return null
    }

    private suspend fun fetchInstantAnswer(encodedQuery: String): Map<String, String>? {
        val body = getText(
            "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1",
            "Mozilla/5.0 (compatible; Kai/1.0)",
        )
        return parseInstantAnswer(body)
    }

    /**
     * GET that treats non-2xx as a failure with the status code, so a block
     * page / captcha / outage surfaces as `failed: http 403` in source_status
     * instead of silently parsing to zero results ("empty").
     */
    private suspend fun getText(url: String, userAgent: String): String {
        val response: HttpResponse = client.get(url) {
            header("User-Agent", userAgent)
        }
        if (!response.status.isSuccess()) throw Exception("http ${response.status.value}")
        return response.bodyAsText()
    }

    private fun parseResults(html: String, maxResults: Int): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()

        // DuckDuckGo Lite returns results in a table structure
        // Links: <a rel="nofollow" href="//duckduckgo.com/l/?uddg=URL" class='result-link'>Title</a>
        // Snippets: <td class='result-snippet'>...</td>
        val linkTags = liteFullLinkRegex.findAll(html).toList()
        val links = liteLinkRegex.findAll(html).toList()
        val snippets = liteSnippetRegex.findAll(html).toList()

        for (i in links.indices) {
            if (results.size >= maxResults) break
            val linkTag = linkTags.getOrNull(i)?.value ?: continue
            val href = liteHrefRegex.find(linkTag)?.groupValues?.get(1) ?: continue
            val title = links[i].groupValues[1].stripHtml().trim()
            val snippet = snippets.getOrNull(i)?.groupValues?.get(1)?.stripHtml()?.trim() ?: ""

            // Extract the actual URL from DDG redirect: //duckduckgo.com/l/?uddg=ENCODED_URL
            val url = extractUrlFromRedirect(href)

            if (url.isNotBlank() && title.isNotBlank()) {
                results.add(
                    mapOf(
                        "title" to title,
                        "url" to url,
                        "snippet" to snippet,
                    ),
                )
            }
        }

        return results
    }

    private suspend fun searchDdgLite(encodedQuery: String, df: String?, count: Int): List<Map<String, String>> {
        val url = buildString {
            append("https://lite.duckduckgo.com/lite/?q=$encodedQuery")
            if (df != null) append("&df=$df")
        }
        val html = getText(url, "Mozilla/5.0 (compatible; Kai/1.0)")
        return parseResults(html, count)
    }

    private suspend fun searchBingHtml(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://www.bing.com/search?q=$encodedQuery", DESKTOP_UA)
        return parseBingResults(html, count)
    }

    private suspend fun searchMarginalia(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://marginalia-search.com/search?query=$encodedQuery", DESKTOP_UA)
        return parseMarginaliaResults(html, count)
    }

    internal fun parseBingResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (segment in html.split("<li class=\"b_algo\"").drop(1)) {
            if (results.size >= maxResults) break
            val blockEnd = segment.indexOf("<li class=\"")
            val block = if (blockEnd >= 0) segment.substring(0, blockEnd) else segment
            val titleMatch = bingTitleRegex.find(block) ?: continue
            val url = resolveBingUrl(titleMatch.groupValues[1].replace("&amp;", "&")) ?: continue
            val title = titleMatch.groupValues[2].stripHtml().trim()
            if (title.isEmpty()) continue
            val snippet = bingCaptionPRegex.find(block)
                ?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    /**
     * Resolves a Bing title-link href: usually a `/ck/a` redirect with the
     * target base64url-encoded in `u=a1…`, occasionally a direct URL.
     */
    internal fun resolveBingUrl(href: String): String? {
        if (href.startsWith("http") && !href.contains("bing.com/ck/a")) return href
        val encoded = bingRedirectRegex.find(href)?.groupValues?.get(1) ?: return null
        return decodeBase64Url(encoded)?.takeIf { it.startsWith("http") }
    }

    internal fun decodeBase64Url(s: String): String? = try {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var bits = 0
        var nbits = 0
        val bytes = mutableListOf<Byte>()
        for (c in s.trim().trimEnd('=')) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            bits = (bits shl 6) or v
            nbits += 6
            if (nbits >= 8) {
                nbits -= 8
                bytes.add(((bits shr nbits) and 0xFF).toByte())
            }
        }
        bytes.toByteArray().decodeToString()
    } catch (_: Exception) {
        null
    }

    internal fun parseMarginaliaResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (match in margAnchorRegex.findAll(html)) {
            if (results.size >= maxResults) break
            if (!match.value.contains("dir=\"auto\"")) continue
            val url = match.groups[1]?.value?.trim().orEmpty()
            if (url.isEmpty()) continue
            val textStart = match.range.last + 1
            val textEnd = html.indexOf("</a>", textStart)
            if (textEnd < 0) continue
            val title = html.substring(textStart, textEnd).stripHtml().trim()
            if (title.isEmpty()) continue
            var snippet = ""
            val pStart = html.indexOf("<p class=\"mt-2", textEnd)
            if (pStart >= 0 && pStart - textEnd < 3000) {
                val pEnd = html.indexOf("</p>", pStart)
                val tagEnd = html.indexOf('>', pStart)
                if (pEnd >= 0 && tagEnd >= 0 && tagEnd < pEnd) {
                    snippet = html.substring(tagEnd + 1, pEnd).stripHtml().trim()
                }
            }
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    internal fun extractUrlFromRedirect(href: String): String {
        val uddgParam = uddgRegex.find(href)?.groupValues?.get(1)
        if (uddgParam != null) {
            return decodeURLComponent(uddgParam)
        }
        // Not a redirect, use as-is (add https: if protocol-relative)
        return if (href.startsWith("//")) "https:$href" else href
    }

    private fun decodeURLComponent(encoded: String): String = buildString {
        var i = 0
        while (i < encoded.length) {
            when {
                encoded[i] == '%' && i + 2 < encoded.length -> {
                    val hex = encoded.substring(i + 1, i + 3)
                    val byte = hex.toIntOrNull(16)
                    if (byte != null) {
                        append(byte.toChar())
                        i += 3
                    } else {
                        append(encoded[i])
                        i++
                    }
                }

                encoded[i] == '+' -> {
                    append(' ')
                    i++
                }

                else -> {
                    append(encoded[i])
                    i++
                }
            }
        }
    }

    private fun String.stripHtml(): String = replace(htmlTagRegex, "")
        .decodeHtmlEntities()

    internal fun String.encodeURLQueryComponent(): String = buildString {
        for (c in this@encodeURLQueryComponent) {
            when {
                c.isLetterOrDigit() || c in "-_.~" -> append(c)

                c == ' ' -> append('+')

                else -> {
                    val bytes = c.toString().encodeToByteArray()
                    for (b in bytes) {
                        append('%')
                        append(
                            b.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0'),
                        )
                    }
                }
            }
        }
    }

    val toolInfo = ToolInfo(
        id = "web_search",
        name = "Web Search",
        description = "Search the web for current information",
        nameRes = Res.string.tool_web_search_name,
        descriptionRes = Res.string.tool_web_search_description,
    )
}
