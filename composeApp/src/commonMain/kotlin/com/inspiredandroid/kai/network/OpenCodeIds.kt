@file:OptIn(ExperimentalUuidApi::class)

package com.inspiredandroid.kai.network

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Canonical OpenCode gateway ids.
 *
 * Since 2026-09-16 Zen's free tier rejects requests whose `x-opencode-session` is not shaped
 * like a real OpenCode session (`ses_` + 12 lowercase hex + 14 Base62), answering
 * `403 FreeTierError` no matter which other headers are present. Kai's conversation ids are
 * UUIDs, so the header writer passes them through [session] to mint the canonical shape while
 * keeping the mapping deterministic: one conversation always produces the same session id, so
 * upstream prompt-cache affinity survives retries and app restarts.
 *
 * [request] and [project] follow the same shape for the sibling headers. The gateway only
 * enforces the session shape today, but a canonical request id costs nothing and matches what
 * the official client sends (`msg_…`, one fresh id per HTTP request).
 *
 * The hash is FNV-1a 64 with two different seeds, not a cryptographic digest: these ids need
 * shape, stability, and uniqueness, not secrecy.
 */
internal object OpenCodeIds {

    private const val FNV_OFFSET: ULong = 0xCBF29CE484222325uL
    private const val FNV_PRIME: ULong = 0x100000001B3uL
    private const val BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val SESSION_PREFIX = "ses_"
    private val canonicalSession = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")

    /** Canonical session id for a conversation; already-canonical input is returned unchanged. */
    fun session(raw: String): String {
        if (canonicalSession.matches(raw)) return raw
        return format(SESSION_PREFIX, digest("kai-session\u0000$raw"))
    }

    /** Fresh canonical request id, one per HTTP request. */
    fun request(): String = format("msg_", digest("kai-request\u0000${Uuid.random()}"))

    /** Canonical project id, stable for the lifetime of the process. */
    fun project(): String = format("prj_", digest("kai-project"))

    /** True when [value] already has the canonical session shape. */
    fun isCanonicalSession(value: String): Boolean = canonicalSession.matches(value)

    private fun format(prefix: String, bytes: ByteArray): String {
        val body = StringBuilder(prefix.length + 26)
        body.append(prefix)
        for (i in 0 until 6) {
            body.append(HEX[bytes[i].toInt() and 0x0F])
            body.append(HEX[(bytes[i].toInt() ushr 4) and 0x0F])
        }
        for (i in 0 until 14) {
            body.append(BASE62[(bytes[(6 + i) % bytes.size].toInt() and 0xFF) % BASE62.length])
        }
        return body.toString()
    }

    private fun digest(seed: String): ByteArray {
        val first = fnv1a64(seed, FNV_OFFSET)
        val second = fnv1a64(seed, FNV_OFFSET xor 0x9E3779B97F4A7C15uL)
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i] = ((first shr (8 * (7 - i))) and 0xFFu).toByte()
            bytes[8 + i] = ((second shr (8 * (7 - i))) and 0xFFu).toByte()
        }
        return bytes
    }

    private fun fnv1a64(text: String, seed: ULong): ULong {
        var hash = seed
        for (char in text) {
            hash = hash xor char.code.toULong()
            hash *= FNV_PRIME
        }
        return hash
    }

    private const val HEX = "0123456789abcdef"
}
