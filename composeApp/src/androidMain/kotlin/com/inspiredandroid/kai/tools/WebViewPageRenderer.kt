package com.inspiredandroid.kai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless page rendering through the system WebView — no sandbox, no
 * downloaded browser engine, and the device's own network stack (Wi-Fi, DNS,
 * VPN) instead of the sandbox's.
 *
 * Everything WebView touches must happen on the main thread while tool
 * execution runs on an IO dispatcher, so each call hops to the main looper,
 * renders, captures, and destroys its own one-shot WebView. Screenshots are
 * viewport-only by contract: the view is measured and laid out at exactly the
 * requested size and drawn into a bitmap.
 */
class WebViewPageRenderer(private val appContext: Context) {

    data class RenderedPage(
        val text: String,
        val screenshot: Bitmap?,
    )

    suspend fun render(
        url: String,
        width: Int,
        height: Int,
        timeoutMs: Long,
        wantScreenshot: Boolean,
        scrollToLoad: Boolean = false,
    ): RenderedPage {
        val main = Handler(Looper.getMainLooper())
        return suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            var webView: WebView? = null
            // Nullable (not lateinit): local-variable references don't support
            // ::isInitialized, and both finish() and the cancellation handler
            // run strictly after these assignments.
            var timeoutRunnable: Runnable? = null
            var settleRunnable: Runnable? = null

            fun finish(value: Result<RenderedPage>) {
                if (!done.compareAndSet(false, true)) return
                main.post {
                    timeoutRunnable?.let(main::removeCallbacks)
                    settleRunnable?.let(main::removeCallbacks)
                    runCatching { webView?.stopLoading() }
                    runCatching { webView?.destroy() }
                    webView = null
                }
                if (cont.isActive) cont.resumeWith(value)
            }

            timeoutRunnable = Runnable {
                finish(Result.failure(PageRenderException("Timed out after ${timeoutMs}ms waiting for $url")))
            }

            // Runs on the main thread after the page settles: measure/layout at
            // the requested viewport, read text, optionally rasterize.
            settleRunnable = Runnable {
                val view = webView ?: run {
                    finish(Result.failure(PageRenderException("WebView was destroyed before $url settled")))
                    return@Runnable
                }
                try {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, width, height)

                    fun extractText() {
                        view.evaluateJavascript(TEXT_EXTRACT_JS) { value ->
                            finish(Result.success(RenderedPage(decodeJsString(value), null)))
                        }
                    }

                    fun captureScreenshot() {
                        view.evaluateJavascript(TEXT_EXTRACT_JS) { value ->
                            val text = decodeJsString(value)
                            try {
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                view.draw(Canvas(bitmap))
                                finish(Result.success(RenderedPage(text, bitmap)))
                            } catch (t: Throwable) {
                                finish(Result.failure(t))
                            }
                        }
                    }

                    // Text mode walks the real scroll container down the page so
                    // lazy-loaded content enters the DOM before extraction. Stops
                    // early at the bottom, when a step moves nothing, and after
                    // MAX_SCROLL_STEPS so infinite feeds stay bounded. Runs on the
                    // main thread (eval callbacks land there) with delayed steps.
                    fun scrollStep(remaining: Int) {
                        if (done.get()) return
                        if (remaining <= 0) {
                            extractText()
                            return
                        }
                        view.evaluateJavascript(WebActCommand.scrollJs(height * 9 / 10)) { value ->
                            if (done.get()) return@evaluateJavascript
                            val info = WebActCommand.parseScrollResult(value)
                            if (info == null || !info.ok || info.atBottom || !info.moved) {
                                extractText()
                            } else {
                                main.postDelayed({ scrollStep(remaining - 1) }, SCROLL_STEP_DELAY_MS)
                            }
                        }
                    }

                    when {
                        wantScreenshot -> captureScreenshot()
                        scrollToLoad -> scrollStep(MAX_SCROLL_STEPS)
                        else -> extractText()
                    }
                } catch (t: Throwable) {
                    finish(Result.failure(t))
                }
            }

            cont.invokeOnCancellation {
                main.post {
                    timeoutRunnable?.let(main::removeCallbacks)
                    settleRunnable?.let(main::removeCallbacks)
                    runCatching { webView?.stopLoading() }
                    runCatching { webView?.destroy() }
                    webView = null
                }
            }

            main.post {
                try {
                    main.postDelayed(timeoutRunnable, timeoutMs)
                    val view = WebView(appContext).apply {
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
                                // Debounced settle: SPAs keep firing finished as
                                // late scripts land; the last one wins.
                                main.removeCallbacks(settleRunnable)
                                main.postDelayed(settleRunnable, SETTLE_DELAY_MS)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request == null || request.isForMainFrame) {
                                    finish(Result.failure(PageRenderException("Failed to load $url: ${error?.description ?: "unknown error"}")))
                                }
                            }
                        }
                    }
                    webView = view
                    view.loadUrl(url)
                } catch (t: Throwable) {
                    finish(Result.failure(t))
                }
            }
        }
    }

    companion object {
        private const val SETTLE_DELAY_MS = 2_500L

        /** Pause between auto-scroll steps so lazy loaders can fetch and render. */
        private const val SCROLL_STEP_DELAY_MS = 1_200L
        private const val MAX_SCROLL_STEPS = 6
        private const val TEXT_EXTRACT_JS =
            "(function(){try{return document.documentElement.innerText||''}catch(e){return ''}})()"

        /** Null when no System WebView is installed (some de-Googled ROMs). */
        fun isAvailable(): Boolean = runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)
    }
}

class PageRenderException(message: String) : Exception(message)
