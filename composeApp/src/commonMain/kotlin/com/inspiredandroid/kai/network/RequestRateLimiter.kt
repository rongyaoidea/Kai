package com.inspiredandroid.kai.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Sliding-window throttle for LLM chat traffic: at most [maxPerMinute] request
 * starts per rolling 60 seconds (0 or negative disables). Long tool loops and
 * retries queue behind it with cancellable waits instead of tripping
 * provider per-minute limits and failing the whole task.
 *
 * One instance is shared by every provider path, so parallel fallback
 * attempts also count against the same budget. [windowMs] and [nowMs] exist
 * for tests: the virtual-time scheduler must drive both the delays and the
 * timestamps or the window cannot slide.
 */
@OptIn(ExperimentalTime::class)
class RequestRateLimiter(
    private val maxPerMinute: () -> Int,
    private val windowMs: Long = 60_000L,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()

    suspend fun acquire() {
        val limit = maxPerMinute().coerceAtLeast(0)
        if (limit <= 0) return
        while (true) {
            val waitMs = mutex.withLock {
                val now = nowMs()
                while (starts.isNotEmpty() && now - starts.first() >= windowMs) {
                    starts.removeFirst()
                }
                if (starts.size < limit) {
                    starts.addLast(now)
                    -1L
                } else {
                    starts.first() + windowMs - now
                }
            }
            if (waitMs < 0) return
            delay(waitMs.coerceAtLeast(1L))
        }
    }
}
