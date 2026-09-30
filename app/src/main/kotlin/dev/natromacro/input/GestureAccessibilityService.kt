package dev.natromacro.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import dev.natromacro.NatroRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns [InputBus] strokes into Android gestures. The D-pad and the route runner both end here.
 */
class GestureAccessibilityService : AccessibilityService(), GestureSink {
    private val main = Handler(Looper.getMainLooper())
    private var stick: GestureDescription.StrokeDescription? = null
    private var holding = false
    private var holdX = 0f
    private var holdY = 0f
    private val refresh = Runnable {
        if (holding) stick(holdX, holdY, true)
    }

    override fun onServiceConnected() {
        instance = this
        NatroRuntime.attachSink(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun stick(x: Float, y: Float, held: Boolean) {
        main.post {
            if (!held) {
                holding = false
                main.removeCallbacks(refresh)
                stick?.let { continuing ->
                    val path = Path().apply { moveTo(x, y) }
                    dispatchGesture(GestureDescription.Builder().addStroke(
                        continuing.continueStroke(path, 0, 1, false)
                    ).build(), null, null)
                }
                stick = null
                return@post
            }
            holdX = x
            holdY = y
            val path = Path().apply { moveTo(x, y) }
            val stroke = stick?.continueStroke(path, 0, 800, true)
                ?: GestureDescription.StrokeDescription(path, 0, 800, true)
            stick = stroke
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
            holding = true
            main.removeCallbacks(refresh)
            main.postDelayed(refresh, 700)
        }
    }

    override fun strokes(strokes: List<dev.natromacro.input.Stroke>, gapMs: Long) {
        main.post {
            var delay = 0L
            for (stroke in strokes) {
                val path = Path().apply {
                    moveTo(stroke.x0, stroke.y0)
                    lineTo(stroke.x1, stroke.y1)
                }
                val gesture = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, stroke.durationMs.coerceAtLeast(1))
                ).build()
                main.postDelayed({ dispatchGesture(gesture, null, null) }, delay)
                delay += stroke.durationMs + gapMs
            }
        }
    }

    override fun tap(key: NatroKey, count: Int) {
        val point = NatroRuntime.button(key) ?: return
        val strokes = List(count) {
            dev.natromacro.input.Stroke(point.first, point.second, point.first, point.second, 40)
        }
        strokes(strokes, NatroRuntime.calibration.keyDelayMs)
    }

    override fun click(x: Float, y: Float) {
        strokes(listOf(dev.natromacro.input.Stroke(x, y, x, y, 40)), 0)
    }

    /**
     * Same shot as vorga2/NatroMacroAndroid: one Accessibility screenshot, then the caller
     * keeps only the buff strip. Must be called off the main thread.
     */
    fun screenshotBlocking(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        val out = AtomicReference<Bitmap?>()
        main.post {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        out.set(copyScreenshot(result))
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                })
            } catch (_: RuntimeException) {
                latch.countDown()
            }
        }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return out.get()
    }

    private fun copyScreenshot(result: ScreenshotResult): Bitmap? {
        var wrapped: Bitmap? = null
        return try {
            val buffer: HardwareBuffer = result.hardwareBuffer
            try {
                wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                wrapped?.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                buffer.close()
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            wrapped?.recycle()
        }
    }

    companion object {
        var instance: GestureAccessibilityService? = null
    }
}
