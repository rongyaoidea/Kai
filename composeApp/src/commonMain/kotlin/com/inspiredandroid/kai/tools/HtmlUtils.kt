package com.inspiredandroid.kai.tools

/**
 * Shared word splitter for keyword matching (memory search, conversation
 * search, duplicate detection). Explicit punctuation class: \p{Punct} is
 * JVM-only and throws on JS/Native. Compiled once — callers used to rebuild
 * it per row per write.
 */
internal val WORD_SPLIT_REGEX = Regex("[\\s!\"#\$%&'()*+,./:;=?@\\[\\]^_`{|}~-]+")

internal fun String.decodeHtmlEntities(): String {
    // &amp; decodes last: decoding it first would double-decode "&amp;lt;"
    // into "<" instead of the literal "&lt;" the page shows.
    var out = this
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")
    out = Regex("&#(\\d+);").replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull()
        if (code != null && code in 1..0x10FFFF) code.toCodePointString() else match.value
    }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
        val code = match.groupValues[1].toIntOrNull(16)
        if (code != null && code in 1..0x10FFFF) code.toCodePointString() else match.value
    }
    return out
}

/** BMP chars directly, supplementary-plane chars (emoji) as a surrogate pair. */
internal fun Int.toCodePointString(): String = if (this <= 0xFFFF) {
    toChar().toString()
} else {
    val v = this - 0x10000
    "${((v shr 10) + 0xD800).toChar()}${((v and 0x3FF) + 0xDC00).toChar()}"
}

private val BLOCKED_BLOCK_REGEX = Regex(
    "(?is)<!--.*?-->|<script\\b[^>]*>.*?</script>|<style\\b[^>]*>.*?</style>|" +
        "<noscript\\b[^>]*>.*?</noscript>|<template\\b[^>]*>.*?</template>|" +
        "<svg\\b[^>]*>.*?</svg>|<(header|nav|footer|aside|form)\\b[^>]*>.*?</\\1>",
)
private val MAIN_ARTICLE_REGEX = Regex("(?is)<(main|article)\\b[^>]*>(.*?)</\\1>")
private val BLOCK_TAG_REGEX = Regex(
    "(?i)</?(p|h[1-6]|li|tr|div|section|blockquote|pre|br|hr|table|ul|ol)\\b[^>]*>",
)
private val ANY_TAG_REGEX = Regex("<[^>]*>")
private val HREF_REGEX = Regex("""(?i)<a\b[^>]*href=['"](https?://[^'"]+)['"][^>]*>([\s\S]*?)</a>""")
private const val MAX_LINKS = 20

/**
 * Boilerplate-stripping HTML→text for agent consumption (search snippets are
 * handled separately; this is for full pages via `fetch_url`).
 *
 * Drops comments, scripts, styles, and chrome blocks (header/nav/footer/aside/
 * forms). Prefers `<main>`/`<article>` when one carries substantial text.
 * Returns body text plus a bounded "Links:" section of outbound URLs so the
 * agent can follow up without re-scraping.
 */
internal fun extractReadableText(html: String, maxChars: Int = 15_000): String {
    var working = BLOCKED_BLOCK_REGEX.replace(html, " ")
    val links = HREF_REGEX.findAll(working).mapNotNull { match ->
        val url = match.groupValues[1].trim()
        val label = match.groupValues[2].replace(ANY_TAG_REGEX, "").decodeHtmlEntities().trim()
        if (url.isNotBlank()) url to label else null
    }.distinctBy { it.first }.take(MAX_LINKS).toList()

    MAIN_ARTICLE_REGEX.findAll(working)
        .map { it.groupValues[2] }
        .firstOrNull { candidate ->
            candidate.replace(ANY_TAG_REGEX, "").trim().length >= 500
        }?.let { working = it }

    val text = BLOCK_TAG_REGEX.replace(working, "\n")
        .replace(ANY_TAG_REGEX, " ")
        .decodeHtmlEntities()
        .split("\n")
        .map { line -> line.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    val builder = StringBuilder()
    builder.append(text.take(maxChars))
    if (links.isNotEmpty()) {
        builder.append("\n\nLinks:\n")
        for ((url, label) in links) {
            val entry = if (label.isNotBlank()) "$label — $url" else url
            if (builder.length + entry.length + 1 > maxChars + 2_000) break
            builder.append(entry).append('\n')
        }
    }
    val result = builder.toString().trim()
    return if (result.length > maxChars + 2_000) result.take(maxChars + 2_000) else result
}

/**
 * Decodes the JSON-encoded string WebView.evaluateJavascript hands back
 * (`"..."`, or the literal `null` for missing content) without org.json.
 */
internal fun decodeJsString(value: String?): String {
    if (value.isNullOrBlank() || value == "null") return ""
    val trimmed = value.trim()
    if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return ""
    val out = StringBuilder()
    var i = 1
    while (i < trimmed.length - 1) {
        val c = trimmed[i]
        if (c != '\\' || i + 1 >= trimmed.length - 1) {
            out.append(c)
            i++
            continue
        }
        when (val e = trimmed[i + 1]) {
            'n' -> {
                out.append('\n')
                i += 2
            }

            't' -> {
                out.append('\t')
                i += 2
            }

            'r' -> {
                out.append('\r')
                i += 2
            }

            'b' -> {
                out.append('\b')
                i += 2
            }

            'f' -> {
                out.append('\u000C')
                i += 2
            }

            'u' -> {
                val end = i + 6
                val code = if (end <= trimmed.length - 1) trimmed.substring(i + 2, end).toIntOrNull(16) else null
                if (code != null) {
                    out.append(code.toCodePointString())
                    i += 6
                } else {
                    // Truncated or non-hex \u: keep the backslash literally
                    // instead of swallowing the next 4 chars.
                    out.append('\\')
                    i++
                }
            }

            else -> {
                out.append(e)
                i += 2
            }
        }
    }
    return out.toString()
}
