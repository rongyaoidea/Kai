package com.inspiredandroid.kai.tools

/**
 * Wraps every tool result in markers the system prompt calls out as untrusted input.
 *
 * Tool output is routinely third-party text — a fetched web page, an email body, an MCP
 * server's reply, a file's contents — and instructions inside it must not be read as coming
 * from the user. The markers give the model a stable boundary to reason about instead of
 * leaving provenance to guesswork.
 *
 * [OPEN] and [CLOSE] are also interpolated into the system prompt rule in
 * `buildChatSystemPrompt`, so the marker text and the rule can never drift apart.
 */
object UntrustedToolOutput {
    const val OPEN = "<<UNTRUSTED_TOOL_OUTPUT>>"
    const val CLOSE = "<<END_UNTRUSTED_TOOL_OUTPUT>>"

    fun wrap(result: String): String = "$OPEN\n$result\n$CLOSE"
}
