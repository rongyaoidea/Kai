package com.inspiredandroid.kai.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Persistent offscreen WebViews, one per caller session (usually a chat
 * conversation), all driven on the main thread while tools run elsewhere.
 * Unlike the one-shot [WebViewPageRenderer], a session keeps its page,
 * history, and cookies across calls so multi-step flows (search → open →
 * fill → submit) work without reloading.
 *
 * Element ids are snapshot-scoped: tap/input re-query the live DOM and refuse
 * when the on-screen text drifted from what the snapshot showed.
 *
 * Threading rule: only synchronous view calls hop to the main thread
 * ([runOnMain]); every suspension (JS callbacks, settle delays, load gates)
 * happens off-main so the UI thread is never blocked.
 */
class WebViewSessionManager(private val appContext: Context) {

    private data class Session(
        val view: WebView,
        var lastUsedMs: Long,
        var pendingLoad: CompletableDeferred<Unit>? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>()

    /** Sessions are touched from tool threads and mutated on the main thread. */
    private inline fun <T> locked(block: () -> T): T = synchronized(sessions) { block() }

    private suspend fun <T> runOnMain(timeoutMs: Long, block: () -> T): T = withTimeout(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    if (cont.isActive) cont.resume(block())
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWith(Result.failure(t))
                }
            }
        }
    }

    private suspend fun WebView.evalJs(script: String, timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            main.post {
                try {
                    evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWith(Result.failure(t))
                }
            }
        }
    }

    private fun touch(id: String) {
        locked { sessions[id]?.lastUsedMs = System.currentTimeMillis() }
    }

    private fun createLockedDownWebView(sessionId: String): WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mediaPlaybackRequiresUserGesture = true
        settings.loadsImagesAutomatically = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                locked {
                    if (sessions[sessionId]?.view === this@apply) {
                        sessions[sessionId]?.pendingLoad?.complete(Unit)
                        sessions[sessionId]?.pendingLoad = null
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                locked {
                    if ((request == null || request.isForMainFrame) && sessions[sessionId]?.view === this@apply) {
                        sessions[sessionId]?.pendingLoad
                            ?.completeExceptionally(PageRenderException("Failed to load: ${error?.description ?: "unknown error"}"))
                        sessions[sessionId]?.pendingLoad = null
                    }
                }
            }
        }
    }

    private suspend fun session(id: String): WebView {
        runOnMain(SESSION_TIMEOUT_MS) {
            locked {
                sessions[id]?.view?.let { return@runOnMain it }
                while (sessions.size >= MAX_SESSIONS) {
                    val oldest = sessions.minByOrNull { it.value.lastUsedMs }?.key ?: break
                    if (oldest == id) break
                    runCatching { sessions.remove(oldest)?.view?.destroy() }
                }
                val view = createLockedDownWebView(id)
                sessions[id] = Session(view, System.currentTimeMillis())
                view
            }
        }.also { touch(id) }
        return locked { sessions[id]?.view } ?: throw PageRenderException("Web session is unavailable")
    }

    suspend fun goto(id: String, url: String, timeoutMs: Long): String {
        val view = session(id)
        touch(id)
        val gate = CompletableDeferred<Unit>()
        runOnMain(timeoutMs) {
            locked { sessions[id]?.pendingLoad = gate }
            view.loadUrl(url)
        }
        try {
            withTimeout(timeoutMs) { gate.await() }
        } finally {
            runOnMain(SESSION_TIMEOUT_MS) {
                locked { if (sessions[id]?.pendingLoad === gate) sessions[id]?.pendingLoad = null }
            }
        }
        delay(SETTLE_MS)
        return runOnMain(SESSION_TIMEOUT_MS) { view.url.orEmpty() }
    }

    suspend fun snapshot(id: String): List<WebActCommand.WebElement> {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.snapshotJs(), ACTION_TIMEOUT_MS) ?: return emptyList()
        return WebActCommand.parseSnapshot(raw)
    }

    suspend fun tap(id: String, index: Int, expect: String): WebActCommand.WebActionResult {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.tapJs(index), ACTION_TIMEOUT_MS)
            ?: return WebActCommand.WebActionResult(false, "")
        val result = WebActCommand.parseActionResult(raw)
            ?: return WebActCommand.WebActionResult(false, "")
        if (!result.ok) return result
        val hint = expect.trim().take(24)
        if (hint.isNotEmpty() && hint !in result.text) {
            return WebActCommand.WebActionResult(false, result.text)
        }
        delay(SETTLE_MS)
        return result
    }

    suspend fun input(id: String, index: Int, text: String, submit: Boolean): WebActCommand.WebActionResult {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.inputJs(index, text, submit), ACTION_TIMEOUT_MS)
            ?: return WebActCommand.WebActionResult(false, "")
        val result = WebActCommand.parseActionResult(raw)
            ?: return WebActCommand.WebActionResult(false, "")
        if (result.ok && !submit) {
            // inputJs fills then reports the live value: a page change between
            // snapshot and fill lands on the wrong element, so verify the filled
            // text like tap verifies its hint instead of reporting ok blindly.
            // (Skipped for submit: Enter may navigate or clear the field.)
            val want = text.trim().replace(Regex("\\s+"), " ").take(24)
            if (want.isNotEmpty() && want !in result.text) {
                return WebActCommand.WebActionResult(false, result.text)
            }
        }
        if (result.ok && submit) delay(SETTLE_MS)
        return result
    }

    suspend fun scroll(id: String, dy: Int): WebActCommand.WebScrollResult {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.scrollJs(dy), ACTION_TIMEOUT_MS)
            ?: return FAILED_SCROLL
        return WebActCommand.parseScrollResult(raw) ?: FAILED_SCROLL
    }

    /** Current scroll position of the real container, without moving it. */
    suspend fun pageScroll(id: String): WebActCommand.WebScrollResult? {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.scrollInfoJs(), ACTION_TIMEOUT_MS) ?: return null
        return WebActCommand.parseScrollResult(raw)
    }

    suspend fun back(id: String): Boolean {
        val view = session(id)
        touch(id)
        val raw = view.evalJs(WebActCommand.backJs(), ACTION_TIMEOUT_MS) ?: return false
        val ok = WebActCommand.parseActionResult(raw)?.ok == true
        if (ok) delay(SETTLE_MS)
        return ok
    }

    suspend fun close(id: String) {
        runOnMain(SESSION_TIMEOUT_MS) {
            locked { runCatching { sessions.remove(id)?.view?.destroy() } }
        }
    }

    companion object {
        private const val MAX_SESSIONS = 8
        private const val SESSION_TIMEOUT_MS = 10_000L
        private const val ACTION_TIMEOUT_MS = 15_000L
        private const val SETTLE_MS = 2_000L

        private val FAILED_SCROLL = WebActCommand.WebScrollResult(
            ok = false,
            y = 0,
            maxY = 0,
            atBottom = false,
            moved = false,
        )
    }
}
