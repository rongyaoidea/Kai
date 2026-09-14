package com.inspiredandroid.kai.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Host-side [AccessibilityService] backing cross-app automation. The user must
 * enable it by hand under Settings → Accessibility; it is never started
 * programmatically. All automation Tools go through [AutomationController],
 * never through this singleton directly.
 */
class KaiAccessibilityService : AccessibilityService() {

    companion object {
        const val SERVICE_ID = "com.inspiredandroid.kai/.automation.KaiAccessibilityService"
        private const val EVENT_RING_CAP = 512

        @Volatile
        private var instance: KaiAccessibilityService? = null

        fun getInstance(): KaiAccessibilityService? = instance
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    val nodeRegistry = AutomationNodeRegistry()

    /** Last accessibility event time, used by [AutomationController] to wait for the screen to settle. */
    private val lastEventAtMsRef = AtomicLong(0L)

    fun lastEventAtMs(): Long = lastEventAtMsRef.get()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        lastEventAtMsRef.set(System.currentTimeMillis())
        eventRing.offer(
            RecordedEvent(
                type = AccessibilityEvent.eventTypeToString(event.eventType),
                packageName = event.packageName?.toString(),
                text = event.text?.joinToString(" ") { it?.toString().orEmpty() }
                    ?.takeIf { it.isNotBlank() },
                timestamp = System.currentTimeMillis(),
            ),
        )
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
    }

    override fun onInterrupt() {
        // Nothing to clean up; gestures in flight report cancellation themselves.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        eventRing.clear()
        nodeRegistry.clear()
    }

    fun recentEvents(): List<RecordedEvent> = eventRing.toList()

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in windows.orEmpty()) {
                window.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            try {
                rootInActiveWindow?.let { out.add(it) }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun foregroundPackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (_: Throwable) {
        null
    }

    fun globalAction(action: Int): Boolean = try {
        performGlobalAction(action)
    } catch (_: Throwable) {
        false
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Throwable) {
            false
        }
    }

    fun tapAt(x: Int, y: Int, durationMs: Long = 50L, timeoutMs: Long = 5_000L): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.1f, y.toFloat() + 0.1f)
        }
        return dispatchPath(path, 0L, durationMs, timeoutMs)
    }

    fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long = 300L,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchPath(path, 0L, durationMs, timeoutMs)
    }

    private fun dispatchPath(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startTime, durationMs))
            .build()
        val done = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                ok[0] = true
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                ok[0] = false
                done.countDown()
            }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && ok[0]
    }

    data class ShotResult(
        val bitmap: Bitmap?,
        val error: String? = null,
    )

    /**
     * System-wide screenshot via `takeScreenshot` (API 30+). Blocks the calling
     * thread up to [timeoutMs]. Caller owns the returned bitmap.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000L): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = AtomicReference(ShotResult(null, "takeScreenshot timed out"))
        val executor = Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(
                displayId,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hardwareBuffer: HardwareBuffer = screenshot.hardwareBuffer
                            val colorSpace: ColorSpace? = screenshot.colorSpace
                            val hardware = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            try {
                                hardwareBuffer.close()
                            } catch (_: Throwable) {
                            }
                            if (hardware == null) {
                                resultRef.set(ShotResult(null, "failed to decode screenshot"))
                            } else {
                                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                                hardware.recycle()
                                resultRef.set(ShotResult(software))
                            }
                        } catch (t: Throwable) {
                            resultRef.set(ShotResult(null, "${t.javaClass.simpleName}: ${t.message}"))
                        } finally {
                            done.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        resultRef.set(ShotResult(null, "takeScreenshot failed (code=$errorCode)"))
                        done.countDown()
                    }
                },
            )
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "${t.javaClass.simpleName}: ${t.message}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }
}
