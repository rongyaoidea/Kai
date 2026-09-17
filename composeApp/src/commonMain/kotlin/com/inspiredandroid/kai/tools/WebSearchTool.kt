@file:OptIn(ExperimentalTime::class)

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val MAX_RESULTS = 5

/** Hard cap for the `count` argument — more than this is noise for the model. */
private const val MAX_COUNT = 10

/** Per-source budget. All sources race in parallel, so the worst case stays near this. */
private const val SOURCE_TIMEOUT_MS = 10_000L

/**
 * The first [PRIMARY_GROUP_SIZE] sources in priority order form the primary group:
 * the reader waits up to [PRIMARY_BUDGET_MS] for them and returns the best of the
 * group as soon as it can't be beaten, instead of awaiting every source. The tail
 * sources keep running in the background and only matter when the group fails.
 */
private const val PRIMARY_GROUP_SIZE = 3
private const val PRIMARY_BUDGET_MS = 6_000L

/** After a primary result arrives, higher-priority primary sources get this long to beat it. */
private const val GRACE_AFTER_PRIMARY_MS = 1_500L

/** Whole-chain cap, measured from the start; the tail phase gets whatever is left. */
private const val CHAIN_BUDGET_MS = 12_000L

/** Bounded wait for the instant answer once a result list is already in hand. */
private const val ANSWER_BUDGET_MS = 4_000L

/** Source ids used in `sources` / `source_status` telemetry. */
private const val SRC_INSTANT = "ddg-instant"
private const val SRC_DDG_HTML = "ddg-html"
private const val SRC_DDG_LITE = "ddg-lite"
private const val SRC_BING = "bing"
private const val SRC_MARGINALIA = "marginalia"
private const val SRC_BAIDU = "baidu"
private const val SRC_SO360 = "so360"
private const val SRC_SOGOU = "sogou"
private const val SRC_BAIDU_NEWS = "baidu-news"
private const val SRC_YANDEX = "yandex"

/** `time` argument → DuckDuckGo `df` param. Null means unfiltered. */
internal fun timeFilterParam(time: String?): String? = when (time?.trim()?.lowercase()) {
    "day", "d", "past-day", "past_day" -> "d"
    "week", "w", "past-week", "past_week" -> "w"
    "month", "m", "past-month", "past_month" -> "m"
    else -> null
}

/** `count` argument → clamped result limit. */
internal fun clampCount(count: Int?): Int = (count ?: MAX_RESULTS).coerceIn(1, MAX_COUNT)

/**
 * True when [text] contains Han characters, which selects the China-first source
 * priority. Deliberately query-only: a Chinese user asking an English question
 * gets the global order (Bing first), which is both reachable and better for
 * English, so no locale plumbing is needed.
 */
internal fun containsCjk(text: String): Boolean = text.any {
    it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF || it.code in 0xF900..0xFAFF
}

/**
 * Source priority. China-first for Han queries: the engines reachable there lead
 * and DuckDuckGo (unreachable without a VPN) drops behind; the global order keeps
 * the long-standing DuckDuckGo-first behavior for everyone else. Sources that
 * turn out to be unreachable are benched by [SourceCircuit] and skipped wherever
 * they sit.
 */
internal val SOURCE_PRIORITY_GLOBAL = listOf(
    SRC_DDG_HTML,
    SRC_DDG_LITE,
    SRC_BING,
    SRC_MARGINALIA,
    SRC_BAIDU,
    SRC_SOGOU,
    SRC_SO360,
    SRC_YANDEX,
)
internal val SOURCE_PRIORITY_CJK = listOf(
    SRC_BAIDU,
    SRC_BING,
    SRC_SOGOU,
    SRC_SO360,
    SRC_DDG_HTML,
    SRC_DDG_LITE,
    SRC_MARGINALIA,
    SRC_YANDEX,
)

/**
 * Source order for a query. When [includeNews] is set (the caller passed a
 * `time` filter), the Baidu News RSS feed slots in right after the leading
 * source — it is the one source whose results are inherently recency-sorted.
 */
internal fun orderedSourceIds(query: String, includeNews: Boolean = false): List<String> {
    val base = if (containsCjk(query)) SOURCE_PRIORITY_CJK else SOURCE_PRIORITY_GLOBAL
    if (!includeNews || SRC_BAIDU_NEWS in base) return base
    return base.take(1) + SRC_BAIDU_NEWS + base.drop(1)
}

/** Why a source failed; decides how long it sits out. */
internal enum class SourceFailureKind { NETWORK, HTTP, CAPTCHA }

/** Non-2xx, carrying the status so telemetry can say `http 403` rather than a bare failure. */
internal class HttpStatusFailure(val status: Int) : Exception("http $status")

/** A verification/interstitial page instead of results. */
internal class CaptchaFailure : Exception("captcha")

internal fun classifyFailure(e: Exception): SourceFailureKind = when (e) {
    is CaptchaFailure -> SourceFailureKind.CAPTCHA
    is HttpStatusFailure -> SourceFailureKind.HTTP
    else -> SourceFailureKind.NETWORK
}

/** Markers that identify an anti-bot interstitial rather than a result page. */
internal val DEFAULT_CAPTCHA_MARKERS = listOf(
    "unusual traffic",
    "verify you are human",
    "our systems have detected",
)

/** True when [body] looks like an anti-bot page for the given engine. */
internal fun looksLikeCaptcha(body: String, markers: List<String>): Boolean {
    val head = body.take(20_000).lowercase()
    return markers.any { head.contains(it.lowercase()) }
}

/**
 * Process-lifetime per-source bench: a source that just proved unreachable is
 * skipped instead of costing every later search its full timeout. Kept in
 * memory on purpose — an app restart retries everything, so a network change
 * (VPN switched on, different Wi-Fi) recovers without user action.
 */
internal class SourceCircuit(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private data class Bench(val consecutiveFailures: Int, val lastFailureMs: Long, val untilMs: Long)

    private val benched = mutableMapOf<String, Bench>()
    private val mutex = Mutex()

    suspend fun isBenched(id: String): Boolean = mutex.withLock {
        val bench = benched[id] ?: return@withLock false
        // untilMs == 0 means "counting strikes, not benched" — keep the count.
        if (bench.untilMs == 0L) return@withLock false
        if (bench.untilMs <= nowMs()) {
            benched.remove(id)
            return@withLock false
        }
        true
    }

    suspend fun remainingMs(id: String): Long = mutex.withLock {
        (benched[id]?.untilMs ?: 0L).minus(nowMs()).coerceAtLeast(0L)
    }

    suspend fun recordSuccess(id: String): Unit = mutex.withLock {
        benched.remove(id)
    }

    suspend fun recordFailure(id: String, kind: SourceFailureKind) = mutex.withLock {
        val now = nowMs()
        val previous = benched[id]
        // A cooldown that already expired starts a fresh run instead of continuing
        // the old one — otherwise an old failure could bench on a single new strike.
        val expired = previous != null && previous.untilMs > 0 && previous.untilMs <= now
        val consecutive = if (previous == null || expired) 1 else previous.consecutiveFailures + 1
        val (threshold, benchMs) = when (kind) {
            SourceFailureKind.CAPTCHA -> 1 to CAPTCHA_BENCH_MS
            SourceFailureKind.HTTP -> 2 to HTTP_BENCH_MS
            SourceFailureKind.NETWORK -> 2 to NETWORK_BENCH_MS
        }
        benched[id] = if (consecutive >= threshold) {
            Bench(consecutive, now, now + benchMs)
        } else {
            Bench(consecutive, now, 0)
        }
    }

    companion object {
        private const val NETWORK_BENCH_MS = 10 * 60 * 1000L
        private const val HTTP_BENCH_MS = 30 * 60 * 1000L
        private const val CAPTCHA_BENCH_MS = 60 * 60 * 1000L
    }
}

/** One searchable engine behind [WebSearchTool]. */
internal class SearchSource(
    val id: String,
    val captchaMarkers: List<String> = DEFAULT_CAPTCHA_MARKERS,
    val search: suspend (encodedQuery: String, df: String?, count: Int) -> List<Map<String, String>>,
)

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

    // Baidu: organic blocks carry `class="result c-container"` (ads use
    // `result-op`, which the split string does not match); the title anchor sits
    // inside the block's <h3>, snippets in a `content-right*` span or
    // `c-abstract` div. Links are `/link?url=` redirects, returned as-is —
    // fetch_url follows redirects.
    private val baiduTitleRegex = Regex("""<h3[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
    private val baiduSnippetRegex = Regex("""<(?:span|div)[^>]*class="[^"]*(?:content-right|c-abstract)[^"]*"[^>]*>([\s\S]*?)</(?:span|div)>""")

    // Sogou: result wrappers are `vrwrap` blocks; the title is the `vr-title`
    // heading anchor and the snippet one of the text layout classes.
    private val sogouTitleRegex = Regex("""<h3[^>]*class="[^"]*vr-title[^"]*"[^>]*>[\s\S]*?<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
    private val sogouSnippetRegex = Regex("""<(?:div|p|span)[^>]*class="[^"]*(?:text-layout|str_info|space-txt|str-text)[^"]*"[^>]*>([\s\S]*?)</(?:div|p|span)>""")

    // 360 (so.com): `res-list` result items with an `res-title` anchor and an
    // `res-desc` paragraph.
    private val so360TitleRegex = Regex("""<h3[^>]*class="[^"]*res-title[^"]*"[^>]*>[\s\S]*?<a[^>]+href="([^"]+)"[^>]*>([\s\S]*?)</a>""")
    private val so360SnippetRegex = Regex("""<p[^>]*class="[^"]*res-desc[^"]*"[^>]*>([\s\S]*?)</p>""")

    // Yandex: OrganicTitle anchors, scanned as tags so the attribute order in
    // the anchor doesn't matter; the snippet is the next OrganicText span.
    private val yandexAnchorRegex = Regex("""<a\s[^>]*class="[^"]*(?:OrganicTitle-Link|organic__url)[^"]*"[^>]*>""")
    private val yandexSnippetRegex = Regex("""<span[^>]*class="[^"]*OrganicText[^"]*"[^>]*>([\s\S]*?)</span>""")
    private val httpHrefRegex = Regex("""href="(https?://[^"]+)""")

    // Baidu News RSS: plain XML `<item>` rows.
    private val rssItemRegex = Regex("""<item>([\s\S]*?)</item>""")
    private val rssTitleRegex = Regex("""<title>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?</title>""")
    private val rssLinkRegex = Regex("""<link>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?</link>""")
    private val rssDescriptionRegex = Regex("""<description>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?</description>""")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Captcha markers are engine-specific strings that only appear on the
     * engine's own interstitial pages, so a search *for* the word "captcha"
     * can't be mistaken for one.
     */
    private val DDG_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS + listOf("bots use duckduckgo")
    private val BING_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS
    private val BAIDU_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS + listOf("wappass.baidu.com", "百度安全验证", "请完成安全验证")
    private val SOGOU_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS + listOf("antispider", "请输入验证码", "访问过于频繁")
    private val SO360_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS + listOf("访问验证", "请输入验证码")
    private val YANDEX_CAPTCHA_MARKERS = DEFAULT_CAPTCHA_MARKERS + listOf("showcaptcha", "not a robot")

    override val schema = ToolSchema(
        name = "web_search",
        description = "Search the web for current information. Returns a direct answer when one is available, plus titles, URLs, and snippets. Before answering questions about recent events, news, current prices, weather, or anything time-sensitive, search first. Also use this when you're unsure about facts or the user asks you to look something up. Pass count (1-10) to control result volume and time (day/week/month) to restrict recency (covers DuckDuckGo, Bing and the Baidu news feed). Queries in Chinese search Baidu, Bing, Sogou and 360 first; DuckDuckGo needs a VPN in mainland China and is skipped when unreachable.",
        parameters = mapOf(
            "query" to ParameterSchema("string", "The search query", true),
            "count" to ParameterSchema("integer", "Max results to return, 1-10 (default 5)", false),
            "time" to ParameterSchema("string", "Recency filter: any, day, week, month (default any). Applies to DuckDuckGo, Bing and the Baidu news feed.", false),
        ),
    )

    private val client = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
        }
    }

    /** Process-lifetime bench list; shared by every search this process makes. */
    private val circuit = SourceCircuit()

    /** All engines this tool can query. Adding one means adding it here and to a priority list. */
    private val sources: List<SearchSource> = listOf(
        SearchSource(SRC_DDG_HTML) { q, df, count -> searchDdgHtml(q, df, count) },
        SearchSource(SRC_DDG_LITE) { q, df, count -> searchDdgLite(q, df, count) },
        SearchSource(SRC_BING) { q, df, count -> searchBingHtml(q, df, count) },
        SearchSource(SRC_MARGINALIA) { q, _, count -> searchMarginalia(q, count) },
        SearchSource(SRC_BAIDU, captchaMarkers = BAIDU_CAPTCHA_MARKERS) { q, _, count -> searchBaidu(q, count) },
        SearchSource(SRC_SOGOU, captchaMarkers = SOGOU_CAPTCHA_MARKERS) { q, _, count -> searchSogou(q, count) },
        SearchSource(SRC_SO360, captchaMarkers = SO360_CAPTCHA_MARKERS) { q, _, count -> searchSo360(q, count) },
        SearchSource(SRC_BAIDU_NEWS, captchaMarkers = BAIDU_CAPTCHA_MARKERS) { q, _, count -> searchBaiduNews(q, count) },
        SearchSource(SRC_YANDEX, captchaMarkers = YANDEX_CAPTCHA_MARKERS) { q, _, count -> searchYandex(q, count) },
    )

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
        val status: String get() = if (error != null) {
            "failed: $error"
        } else if (items.isEmpty()) {
            "empty"
        } else {
            "ok (${items.size})"
        }
    }

    /** Source order for this query, engine objects in priority order. */
    private fun orderedSources(query: String, includeNews: Boolean): List<SearchSource> {
        val byId = sources.associateBy { it.id }
        return orderedSourceIds(query, includeNews).mapNotNull { byId[it] }
    }

    /**
     * Races the sources in priority order without waiting for all of them.
     *
     * The primary group (the first [PRIMARY_GROUP_SIZE] sources) gets
     * [PRIMARY_BUDGET_MS]; the best result that can no longer be beaten is
     * returned, with higher-priority group members given [GRACE_AFTER_PRIMARY_MS]
     * to arrive. When the group yields nothing, the tail sources get whatever
     * remains of [CHAIN_BUDGET_MS]. Benched sources are skipped entirely, and
     * every straggler is cancelled on return — a dead engine never costs more
     * than the budget of the phase it was in.
     */
    private suspend fun searchChain(query: String, count: Int, time: String?): Map<String, Any> {
        val encoded = query.encodeURLQueryComponent()
        val df = timeFilterParam(time)
        val ordered = orderedSources(query, includeNews = time != null)

        val skipped = mutableListOf<Pair<SearchSource, Long>>()
        val active = mutableListOf<SearchSource>()
        for (source in ordered) {
            if (circuit.isBenched(source.id)) {
                skipped += source to circuit.remainingMs(source.id)
            } else {
                active += source
            }
        }

        return coroutineScope {
            val channel = Channel<Pair<Int, SourceLoad>>(Channel.UNLIMITED)
            val jobs = active.mapIndexed { index, source ->
                launch { channel.send(index to loadList(source, encoded, df, count)) }
            }
            val answerTask: Deferred<Pair<Map<String, String>?, String>>? = if (circuit.isBenched(SRC_INSTANT)) {
                null
            } else {
                async { loadAnswer(encoded) }
            }

            try {
                val completed = linkedMapOf<Int, SourceLoad>()
                val startedAt = Clock.System.now().toEpochMilliseconds()
                val primaryBudgetDeadline = startedAt + PRIMARY_BUDGET_MS
                var graceDeadline: Long? = null

                while (completed.size < active.size) {
                    val deadline = graceDeadline ?: primaryBudgetDeadline
                    val remaining = deadline - Clock.System.now().toEpochMilliseconds()
                    if (remaining <= 0) break
                    val item = withTimeoutOrNull(remaining) { channel.receive() } ?: break
                    completed[item.first] = item.second
                    val best = bestCompletedIndex(completed, active) ?: continue
                    val higherPriorityPending = (0 until best).any { it !in completed && it < PRIMARY_GROUP_SIZE }
                    if (!higherPriorityPending) break
                    if (graceDeadline == null) {
                        graceDeadline = Clock.System.now().toEpochMilliseconds() + GRACE_AFTER_PRIMARY_MS
                    }
                }

                // Nothing usable yet: wait out the tail sources within the chain budget.
                if (bestCompletedIndex(completed, active) == null && completed.size < active.size) {
                    val remaining = CHAIN_BUDGET_MS - (Clock.System.now().toEpochMilliseconds() - startedAt)
                    if (remaining > 0) {
                        withTimeoutOrNull(remaining) {
                            while (completed.size < active.size) {
                                val item = channel.receive()
                                completed[item.first] = item.second
                            }
                        }
                    }
                }

                val answerResult = answerTask?.let { withTimeoutOrNull(ANSWER_BUDGET_MS) { it.await() } }
                val answerStatus = when {
                    answerTask == null -> "skipped: benched"
                    answerResult != null -> answerResult.second
                    else -> "pending"
                }

                buildSearchResponse(active, completed, skipped, answerResult?.first, answerStatus)
            } finally {
                jobs.forEach { it.cancel() }
                answerTask?.cancel()
                channel.close()
            }
        }
    }

    /**
     * Index of the highest-priority completed source with results, or null when
     * none has results yet. Timer-only entries (empty/failed) don't win but do
     * count as settled for the higher-priority wait.
     */
    private fun bestCompletedIndex(completed: Map<Int, SourceLoad>, active: List<SearchSource>): Int? = active.indices.firstOrNull { index ->
        completed[index]?.items?.isNotEmpty() == true
    }

    private fun buildSearchResponse(
        active: List<SearchSource>,
        completed: Map<Int, SourceLoad>,
        skipped: List<Pair<SearchSource, Long>>,
        answer: Map<String, String>?,
        answerStatus: String,
    ): Map<String, Any> {
        val winnerIndex = bestCompletedIndex(completed, active)
        val winner = winnerIndex?.let { active[it] }
        val results = winnerIndex?.let { completed[it]?.items }.orEmpty()

        if (results.isNotEmpty() || answer != null) {
            val sources = buildList {
                if (answer != null) add(SRC_INSTANT)
                if (winner != null) add(winner.id)
            }
            return buildMap<String, Any> {
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
            for (load in completed.values) put(load.name, load.status)
            for ((source, remainingMs) in skipped) put(source.id, "skipped: benched (~${remainingMs / 1000}s)")
        }
        return mapOf(
            "success" to true,
            "results" to emptyList<Any>(),
            "message" to "No results found",
            "source_status" to status,
        )
    }

    private suspend fun loadAnswer(encodedQuery: String): Pair<Map<String, String>?, String> = try {
        // withTimeout (not OrNull): a hanging instant endpoint must bench like a
        // hanging list source instead of looking like an empty answer forever.
        val answer = withTimeout(SOURCE_TIMEOUT_MS) { fetchInstantAnswer(encodedQuery) }
        if (answer != null) {
            circuit.recordSuccess(SRC_INSTANT)
            answer to "ok"
        } else {
            null to "empty"
        }
    } catch (e: TimeoutCancellationException) {
        circuit.recordFailure(SRC_INSTANT, SourceFailureKind.NETWORK)
        null to "timeout after ${SOURCE_TIMEOUT_MS / 1000}s"
    } catch (e: CancellationException) {
        // Early return cancels stragglers; that is not a source failure.
        throw e
    } catch (e: Exception) {
        circuit.recordFailure(SRC_INSTANT, classifyFailure(e))
        null to "failed: ${shortError(e)}"
    }

    private suspend fun loadList(source: SearchSource, encoded: String, df: String?, count: Int): SourceLoad = try {
        val items = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source.search(encoded, df, count) }
        if (items == null) {
            circuit.recordFailure(source.id, SourceFailureKind.NETWORK)
            SourceLoad(source.id, emptyList(), "timeout after ${SOURCE_TIMEOUT_MS / 1000}s")
        } else {
            circuit.recordSuccess(source.id)
            SourceLoad(source.id, items, null)
        }
    } catch (e: CancellationException) {
        // Early return cancels stragglers; that is not a source failure.
        throw e
    } catch (e: Exception) {
        circuit.recordFailure(source.id, classifyFailure(e))
        SourceLoad(source.id, emptyList(), shortError(e))
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
        val html = getText(url, "Mozilla/5.0 (compatible; Kai/1.0)", DDG_CAPTCHA_MARKERS)
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
            DDG_CAPTCHA_MARKERS,
        )
        return parseInstantAnswer(body)
    }

    /**
     * GET that treats non-2xx as a failure with the status code, so a block
     * page / captcha / outage surfaces as `failed: http 403` in source_status
     * instead of silently parsing to zero results ("empty").
     */
    private suspend fun getText(
        url: String,
        userAgent: String,
        captchaMarkers: List<String>,
        cookie: String? = null,
    ): String {
        val response: HttpResponse = client.get(url) {
            header("User-Agent", userAgent)
            cookie?.let { header("Cookie", it) }
        }
        if (!response.status.isSuccess()) throw HttpStatusFailure(response.status.value)
        val body = response.bodyAsText()
        if (looksLikeCaptcha(body, captchaMarkers)) throw CaptchaFailure()
        return body
    }

    /** `name=value; name2=value2` from a response's Set-Cookie headers, or null. */
    private fun cookieHeader(response: HttpResponse): String? = response.headers.getAll("Set-Cookie")
        ?.mapNotNull { it.substringBefore(';').trim().takeIf { part -> part.isNotBlank() } }
        ?.joinToString("; ")
        ?.takeIf { it.isNotBlank() }

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
        val html = getText(url, "Mozilla/5.0 (compatible; Kai/1.0)", DDG_CAPTCHA_MARKERS)
        return parseResults(html, count)
    }

    private suspend fun searchBingHtml(encodedQuery: String, df: String?, count: Int): List<Map<String, String>> {
        // Bing's freshness filter: ez1 = past 24h, ez2 = past week, ez3 = past month.
        val freshness = when (df) {
            "d" -> "&filters=ex1%3A%22ez1%22"
            "w" -> "&filters=ex1%3A%22ez2%22"
            "m" -> "&filters=ex1%3A%22ez3%22"
            else -> ""
        }
        val html = getText("https://www.bing.com/search?q=$encodedQuery$freshness", DESKTOP_UA, BING_CAPTCHA_MARKERS)
        return parseBingResults(html, count)
    }

    private suspend fun searchMarginalia(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://marginalia-search.com/search?query=$encodedQuery", DESKTOP_UA, DEFAULT_CAPTCHA_MARKERS)
        return parseMarginaliaResults(html, count)
    }

    // region China-first engines

    internal fun parseBaiduResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (segment in html.split("class=\"result c-container").drop(1)) {
            if (results.size >= maxResults) break
            val titleMatch = baiduTitleRegex.find(segment) ?: continue
            val url = titleMatch.groupValues[1].replace("&amp;", "&").trim()
            val title = titleMatch.groupValues[2].stripHtml().trim()
            if (url.isEmpty() || title.isEmpty()) continue
            val snippet = baiduSnippetRegex.find(segment)?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    private suspend fun searchBaidu(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://www.baidu.com/s?wd=$encodedQuery&rn=$count", DESKTOP_UA, BAIDU_CAPTCHA_MARKERS)
        return parseBaiduResults(html, count)
    }

    internal fun parseSogouResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (segment in html.split("class=\"vrwrap").drop(1)) {
            if (results.size >= maxResults) break
            val titleMatch = sogouTitleRegex.find(segment) ?: continue
            val url = titleMatch.groupValues[1].replace("&amp;", "&").trim()
            val title = titleMatch.groupValues[2].stripHtml().trim()
            if (url.isEmpty() || title.isEmpty()) continue
            val snippet = sogouSnippetRegex.find(segment)?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    private suspend fun searchSogou(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://www.sogou.com/web?query=$encodedQuery", DESKTOP_UA, SOGOU_CAPTCHA_MARKERS, cookie = sogouCookie())
        return parseSogouResults(html, count)
    }

    /**
     * Sogou intermittently wants a session cookie from its homepage before it
     * serves results. Fetched once per process; the homepage response is also
     * where a redirected/verification response would surface first.
     */
    private var sogouCookieValue: String? = null
    private var sogouCookieFetched = false
    private val sogouCookieMutex = Mutex()

    private suspend fun sogouCookie(): String? {
        if (sogouCookieFetched) return sogouCookieValue
        // Serialize concurrent first-searches and only cache success: a failed
        // homepage fetch must not poison the cookie for the process lifetime.
        return sogouCookieMutex.withLock {
            if (sogouCookieFetched) return sogouCookieValue
            val fetched = try {
                val response = client.get("https://www.sogou.com/") { header("User-Agent", DESKTOP_UA) }
                if (!response.status.isSuccess()) {
                    null
                } else {
                    val body = response.bodyAsText()
                    if (looksLikeCaptcha(body, SOGOU_CAPTCHA_MARKERS)) null else cookieHeader(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (fetched != null) {
                sogouCookieValue = fetched
                sogouCookieFetched = true
            }
            fetched
        }
    }

    internal fun parseSo360Results(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (segment in html.split("class=\"res-list").drop(1)) {
            if (results.size >= maxResults) break
            val titleMatch = so360TitleRegex.find(segment) ?: continue
            val url = titleMatch.groupValues[1].replace("&amp;", "&").trim()
            val title = titleMatch.groupValues[2].stripHtml().trim()
            if (url.isEmpty() || title.isEmpty()) continue
            val snippet = so360SnippetRegex.find(segment)?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    private suspend fun searchSo360(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://www.so.com/s?q=$encodedQuery", DESKTOP_UA, SO360_CAPTCHA_MARKERS)
        return parseSo360Results(html, count)
    }

    internal fun parseYandexResults(html: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (match in yandexAnchorRegex.findAll(html)) {
            if (results.size >= maxResults) break
            val url = httpHrefRegex.find(match.value)?.groupValues?.get(1)?.trim() ?: continue
            val textStart = match.range.last + 1
            val textEnd = html.indexOf("</a>", textStart)
            if (textEnd < 0) continue
            val title = html.substring(textStart, textEnd).stripHtml().trim()
            if (url.isEmpty() || title.isEmpty()) continue
            // Snippet: an OrganicText span shortly after the title anchor.
            val snippet = html.substring(textEnd).take(2000).let { window ->
                yandexSnippetRegex.find(window)?.groupValues?.get(1)?.stripHtml()?.trim().orEmpty()
            }
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    private suspend fun searchYandex(encodedQuery: String, count: Int): List<Map<String, String>> {
        val html = getText("https://yandex.com/search/?text=$encodedQuery", DESKTOP_UA, YANDEX_CAPTCHA_MARKERS)
        return parseYandexResults(html, count)
    }

    /**
     * Baidu News RSS — inherently recency-sorted, so it only joins a search that
     * asked for a `time` filter. xml parsing is regex-based like everything else.
     */
    internal fun parseBaiduNewsRss(xml: String, maxResults: Int = MAX_RESULTS): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        for (item in rssItemRegex.findAll(xml)) {
            if (results.size >= maxResults) break
            val block = item.groupValues[1]
            val title = rssTitleRegex.find(block)?.groupValues?.get(1)?.decodeHtmlEntities()?.trim().orEmpty()
            val url = rssLinkRegex.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val snippet = rssDescriptionRegex.find(block)?.groupValues?.get(1)?.decodeHtmlEntities()?.trim().orEmpty()
            if (title.isEmpty() || url.isEmpty()) continue
            results.add(mapOf("title" to title, "url" to url, "snippet" to snippet))
        }
        return results
    }

    private suspend fun searchBaiduNews(encodedQuery: String, count: Int): List<Map<String, String>> {
        val url = "https://news.baidu.com/ns?word=$encodedQuery&tn=newsrss&sr=0&cl=2&rn=$count&ct=0"
        val xml = getText(url, "Mozilla/5.0 (compatible; Kai/1.0)", BAIDU_CAPTCHA_MARKERS)
        return parseBaiduNewsRss(xml, count)
    }

    // endregion

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

    // Percent-decoding must reassemble UTF-8 byte sequences before turning them
    // into chars: decoding byte-by-byte corrupts every non-ASCII result URL.
    private fun decodeURLComponent(encoded: String): String {
        val pending = mutableListOf<Byte>()
        val out = StringBuilder()
        fun flushBytes() {
            if (pending.isNotEmpty()) {
                out.append(pending.toByteArray().decodeToString())
                pending.clear()
            }
        }
        var i = 0
        while (i < encoded.length) {
            when {
                encoded[i] == '%' && i + 2 < encoded.length -> {
                    val byte = encoded.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        pending.add(byte.toByte())
                        i += 3
                    } else {
                        flushBytes()
                        out.append(encoded[i])
                        i++
                    }
                }

                encoded[i] == '+' -> {
                    flushBytes()
                    out.append(' ')
                    i++
                }

                else -> {
                    flushBytes()
                    out.append(encoded[i])
                    i++
                }
            }
        }
        flushBytes()
        return out.toString()
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
