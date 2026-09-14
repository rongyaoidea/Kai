package com.inspiredandroid.kai.network

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class RequestRateLimiterTest {

    /**
     * Timestamps come from the virtual scheduler, not the wall clock — the
     * production default is Clock.System, but a real clock cannot slide while
     * `delay()` skips ahead, which is exactly the mismatch this guards.
     */
    private fun TestScope.limiter(maxPerMinute: Int, windowMs: Long) = RequestRateLimiter(
        maxPerMinute = { maxPerMinute },
        windowMs = windowMs,
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun `zero limit never waits`() = runTest {
        val limiter = limiter(0, windowMs = 1_000)
        repeat(10) { limiter.acquire() }
        assertTrue(currentTime < 1_000)
    }

    @Test
    fun `acquires within budget proceed immediately`() = runTest {
        val limiter = limiter(3, windowMs = 60_000)
        repeat(3) { limiter.acquire() }
        assertTrue(currentTime < 60_000)
    }

    @Test
    fun `over-budget acquire waits for the oldest slot`() = runTest {
        val limiter = limiter(1, windowMs = 1_000)
        limiter.acquire()
        val before = currentTime
        limiter.acquire()
        assertTrue(currentTime - before >= 1_000)
    }

    @Test
    fun `slots free up as the window slides`() = runTest {
        val limiter = limiter(2, windowMs = 500)
        limiter.acquire()
        limiter.acquire()
        val before = currentTime
        limiter.acquire()
        limiter.acquire()
        // Third waited ~500ms for the first slot; fourth reused the second.
        assertTrue(currentTime - before < 1_000)
    }
}
