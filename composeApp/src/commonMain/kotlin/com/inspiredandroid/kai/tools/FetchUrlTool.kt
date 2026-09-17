package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kai.composeapp.generated.resources.Res
import kai.composeapp.generated.resources.tool_fetch_url_description
import kai.composeapp.generated.resources.tool_fetch_url_name

private val ALLOWED_METHODS = setOf("GET", "POST", "HEAD")
private const val MAX_BODY_CHARS = 15_000
private val MARKDOWN_LINK = Regex("""\[[^\]]*]\(\s*([^)\s]+)\s*\)""")
object FetchUrlTool : Tool {
    override val schema = ToolSchema(
        name = "fetch_url",
        description = "Fetch the contents of an https/http URL and return the status and response body. " +
            "Use this to read web pages, hit API endpoints, or act on links from emails (e.g. RFC 8058 " +
            "one-click unsubscribe: POST to the https list-unsubscribe URL with body `List-Unsubscribe=One-Click`). " +
            "Redirects are followed automatically. Private/loopback addresses are blocked. " +
            "HTML responses are stripped of tags; large responses are truncated. " +
            "If the page needs JavaScript (blank/empty text, SPA, lazy-loaded content), use browse_page instead when it is available.",
        parameters = mapOf(
            "url" to ParameterSchema(
                type = "string",
                description = "The absolute http(s) URL to fetch",
                required = true,
            ),
            "method" to ParameterSchema(
                type = "string",
                description = "HTTP method: GET (default), POST, or HEAD",
                required = false,
            ),
            "body" to ParameterSchema(
                type = "string",
                description = "Request body (POST only). For RFC 8058 one-click unsubscribe use `List-Unsubscribe=One-Click`.",
                required = false,
            ),
            "content_type" to ParameterSchema(
                type = "string",
                description = "Content-Type header for the request body. Defaults to application/x-www-form-urlencoded when a body is present.",
                required = false,
            ),
        ),
    )

    private val client = httpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }

    override suspend fun execute(args: Map<String, Any>): Any {
        val rawUrl = (args["url"] ?: args["link"] ?: args["href"])?.toString()
            ?: return mapOf("success" to false, "error" to "url is required — pass the absolute http(s) URL in the `url` field")
        val urlArg = normalizeUrl(rawUrl)
            ?: return mapOf(
                "success" to false,
                "error" to "invalid URL \"${rawUrl.take(120)}\" — pass an absolute http(s) URL like " +
                    "https://example.com/path, without surrounding quotes, markdown, or spaces",
            )
        val methodArg = (args["method"]?.toString() ?: "GET").uppercase()
        val bodyArg = args["body"]?.toString()
        val contentTypeArg = args["content_type"]?.toString()

        if (methodArg !in ALLOWED_METHODS) {
            return mapOf("success" to false, "error" to "method must be one of $ALLOWED_METHODS")
        }
        if (bodyArg != null && methodArg != "POST") {
            return mapOf("success" to false, "error" to "body is only supported with POST — retry without it or switch method to POST")
        }

        val parsed = runCatching { Url(urlArg) }.getOrNull()
            ?: return mapOf("success" to false, "error" to "invalid URL \"${urlArg.take(120)}\"")

        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") {
            return mapOf("success" to false, "error" to "only http and https schemes are allowed")
        }
        if (isBlockedHost(parsed.host)) {
            return mapOf("success" to false, "error" to "blocked host: ${parsed.host}")
        }

        return try {
            val response = client.request(urlArg) {
                method = HttpMethod.parse(methodArg)
                header("User-Agent", "Mozilla/5.0 (compatible; Kai/1.0)")
                if (bodyArg != null && methodArg == "POST") {
                    contentType(
                        contentTypeArg?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                            ?: ContentType.Application.FormUrlEncoded,
                    )
                    setBody(bodyArg)
                }
            }

            val responseCt = response.headers["Content-Type"].orEmpty()
            // Engines (OkHttp, Darwin, Js) follow redirects below Ktor's plugins, so the
            // initial-host check above cannot see them: re-check the final URL and refuse
            // to hand the body to the model when a redirect landed on a blocked host.
            val finalHost = response.call.request.url.host
            if (isBlockedHost(finalHost)) {
                return mapOf("success" to false, "error" to "blocked host after redirect: $finalHost — private, loopback and link-local addresses are not reachable from tools")
            }
            val rawBody = if (methodArg == "HEAD") "" else response.bodyAsText()
            val body = if (responseCt.startsWith("text/html", ignoreCase = true)) {
                extractReadableText(rawBody)
            } else {
                // Bound non-HTML the same way extractReadableText bounds HTML so a
                // huge API dump cannot blow past the tool-result budget in memory.
                rawBody.take(MAX_BODY_CHARS)
            }

            mapOf(
                "success" to response.status.isSuccess(),
                "status" to response.status.value,
                "final_url" to response.call.request.url.toString(),
                "content_type" to responseCt,
                "body" to body,
            )
        } catch (e: Exception) {
            mapOf("success" to false, "error" to "fetch failed: ${e.message}")
        }
    }

    /**
     * Cleans up the shapes a URL arrives in from models and copied web/email text:
     * surrounding quotes/angle brackets/backticks, markdown `[label](url)` wrapping,
     * HTML entities (`&amp;`), and a missing scheme. Returns null when what's left
     * can't be an absolute http(s) URL. Keep the output usable verbatim in the
     * request so logs and `final_url` reflect exactly what was fetched.
     */
    internal fun normalizeUrl(raw: String): String? {
        var value = raw.trim()
        MARKDOWN_LINK.find(value)?.let { value = it.groupValues[1] }
        value = value.trim().trim('`', '"', '\'', '<', '>').trim()
        value = value.replace("&amp;", "&").replace("&#38;", "&")
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        if (!value.contains("://")) value = "https://$value"
        // "https://" (or "https:///path") parses in Ktor with an empty authority —
        // require a real host before trusting the URL.
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank() || authority.startsWith(":")) return null
        val parsed = runCatching { Url(value) }.getOrNull() ?: return null
        val scheme = parsed.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (parsed.host.isBlank()) return null
        return value
    }

    internal fun isBlockedHost(host: String): Boolean {
        // Strip an IPv6 zone id ("fe80::1%wlan0") before matching: the suffix is
        // link metadata, so "::1%eth0" must still count as loopback.
        val h = host.lowercase().trim('[', ']').substringBefore('%')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost")) return true
        // IPv6 — including embedded-IPv4 forms like ::ffff:10.0.0.1, judged by
        // their trailing quad, and the unspecified address "::".
        if (h.contains(':')) return isBlockedIpv6(h)
        // Dotted numeric forms with 2-4 parts (inet_aton semantics: 127.1 and
        // 0x7f.1 resolve to loopback too, not just full quads).
        if (h.contains('.')) {
            expandShortIpv4(h.split("."))?.let { return isBlockedIpv4(it) }
        }
        // Single-number forms (decimal 2130706433, hex 0x7f000001) decode to IPv4 too.
        parseSingleNumberIp(h)?.let { return isBlockedIpv4(it) }
        return false
    }

    /** One dotted part in decimal, octal (leading 0), or hex (0x) notation, up to [max]. Null when not numeric. */
    internal fun parseIpv4Part(part: String, max: Int = 255): Int? {
        if (part.isEmpty()) return null
        return when {
            part.startsWith("0x", ignoreCase = true) -> part.substring(2).toIntOrNull(16)
            part.length > 1 && part.startsWith("0") && part.all { it.isDigit() } -> part.toIntOrNull(8)
            part.all { it.isDigit() } -> part.toIntOrNull()
            else -> null
        }?.takeIf { it in 0..max }
    }

    /**
     * inet_aton short forms: the last part absorbs the remaining bytes ("127.1"
     * is 127.0.0.1, "10.1" is 10.0.0.1). Null when any part isn't numeric.
     */
    internal fun expandShortIpv4(parts: List<String>): List<Int>? = when (parts.size) {
        4 -> parts.map { parseIpv4Part(it) ?: return null }
        3 -> {
            val a = parseIpv4Part(parts[0]) ?: return null
            val b = parseIpv4Part(parts[1]) ?: return null
            val c = parseIpv4Part(parts[2], 0xFFFF) ?: return null
            listOf(a, b, (c shr 8) and 0xFF, c and 0xFF)
        }
        2 -> {
            val a = parseIpv4Part(parts[0]) ?: return null
            val b = parseIpv4Part(parts[1], 0xFFFFFF) ?: return null
            listOf(a, (b shr 16) and 0xFF, (b shr 8) and 0xFF, b and 0xFF)
        }
        else -> null
    }

    private fun isBlockedIpv6(h: String): Boolean {
        if (h == "::1" || h == "0:0:0:0:0:0:0:1" || h == "::") return true
        if (h.startsWith("fe80:")) return true
        // Unique-local IPv6 (fc00::/7) — the colon keeps hostnames
        // like "fcc.gov" or "fdic.gov" from being treated as addresses.
        if (h.startsWith("fc") || h.startsWith("fd")) return true
        // Embedded IPv4 (::ffff:10.0.0.1): judge by the trailing quad.
        if (h.contains('.')) {
            val tail = h.substringAfterLast(':').split(".")
            if (tail.size == 4) {
                val octets = tail.map { parseIpv4Part(it) ?: return false }
                return isBlockedIpv4(octets)
            }
        }
        return false
    }

    internal fun parseSingleNumberIp(host: String): List<Int>? {
        val num = when {
            host.startsWith("0x", ignoreCase = true) -> host.substring(2).toLongOrNull(16)
            host.all { it.isDigit() } && host.length in 4..10 -> host.toLongOrNull()
            else -> null
        } ?: return null
        if (num !in 0..0xFFFFFFFFL) return null
        return listOf((num shr 24).toInt(), ((num shr 16) and 0xFF).toInt(), ((num shr 8) and 0xFF).toInt(), (num and 0xFF).toInt())
    }

    private fun isBlockedIpv4(octets: List<Int>): Boolean {
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 0 -> true
            a == 169 && b == 254 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            else -> false
        }
    }

    /**
     * Numeric IPv4 octets for [host] in any notation resolvers accept: full
     * quad, inet_aton short forms, octal/hex parts, a single 32-bit number, or
     * the trailing quad of an embedded-IPv4 IPv6 literal. Null when not numeric.
     */
    internal fun numericIpv4Octets(host: String): List<Int>? {
        val h = host.lowercase().trim('[', ']').substringBefore('%')
        if (h.contains(':')) {
            if (!h.contains('.')) return null
            val tail = h.substringAfterLast(':').split(".")
            if (tail.size != 4) return null
            return tail.map { parseIpv4Part(it) ?: return null }
        }
        if (h.contains('.')) return expandShortIpv4(h.split("."))
        return parseSingleNumberIp(h)
    }

    val toolInfo = ToolInfo(
        id = "fetch_url",
        name = "Fetch URL",
        description = "Fetch the contents of a URL and return the response body to the agent",
        nameRes = Res.string.tool_fetch_url_name,
        descriptionRes = Res.string.tool_fetch_url_description,
    )
}

/**
 * The host policy [FetchUrlTool] applies, exposed so every tool that accepts a URL from the
 * model — `browse_page`, `web_act` — refuses private and loopback addresses as well. Sharing
 * the check is the point: an SSRF guard is only as strong as the one tool that remembers to
 * call it.
 *
 * Returns the error to report, or null when the URL may be requested.
 */
internal fun blockedUrlHostReason(rawUrl: String): String? {
    val host = runCatching { Url(rawUrl).host }.getOrNull()
    if (host.isNullOrBlank()) return "invalid URL — pass an absolute http(s) URL with a host"
    if (!FetchUrlTool.isBlockedHost(host)) return null
    return "blocked host: $host — private, loopback and link-local addresses are not reachable from tools"
}
