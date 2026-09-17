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

    /**
     * Substitute for an attacker-controlled [CLOSE] inside a result: tool output is
     * routinely third-party text (a web page, a mail body, an MCP reply), and a
     * literal close marker in it would end the envelope early, promoting whatever
     * follows to trusted instructions. An inner [OPEN] needs no escaping — only
     * [CLOSE] ends the envelope.
     */
    const val ESCAPED_CLOSE = "<<END_UNTRUSTED_TOOL_OUTPUT (escaped)>>"

    fun wrap(result: String): String = "$OPEN\n${result.replace(CLOSE, ESCAPED_CLOSE)}\n$CLOSE"
}
