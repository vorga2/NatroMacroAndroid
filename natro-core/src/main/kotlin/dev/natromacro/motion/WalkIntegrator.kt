package dev.natromacro.motion

import kotlin.math.abs

data class WalkResult(
    val studs: Double,
    val elapsedSeconds: Double,
    val resetRequested: Boolean,
    val paused: Boolean,
)

/**
 * Tile distance from Natro's NewWalk. One tile is 4 studs.
 * Time is the integral of live speed, so a haste spike shortens the step
 * and never requests a character reset.
 *
 * The last sample is cut short so a constant speed of v covers exactly
 * `tiles * 4 / v` seconds. That keeps a 20 FPS sample from walking past the corner.
 */
class WalkIntegrator(
    private val samplePeriodNanos: Long = CaptureTiming.FRAME_NANOS,
) {
    fun walk(tiles: Double, speed: (studsSoFar: Double, elapsedNanos: Long) -> Double): WalkResult {
        val target = tiles * 4.0
        if (target <= 0.0) {
            return WalkResult(0.0, 0.0, resetRequested = false, paused = false)
        }
        var studs = 0.0
        var elapsed = 0L
        var v = speed(0.0, 0L)
        if (v <= 0.0) {
            return WalkResult(0.0, 0.0, resetRequested = false, paused = true)
        }
        while (studs < target - 1e-9) {
            val nextElapsed = elapsed + samplePeriodNanos
            val v2 = speed(studs, nextElapsed)
            if (v2 <= 0.0) {
                return WalkResult(studs, elapsed / 1e9, resetRequested = false, paused = true)
            }
            val dt = samplePeriodNanos / 1e9
            val segment = (v + v2) / 2.0 * dt
            if (studs + segment >= target) {
                val remain = target - studs
                val partial = partialSeconds(v, v2, dt, remain)
                elapsed += (partial * 1e9).toLong()
                studs = target
                break
            }
            studs += segment
            elapsed = nextElapsed
            v = v2
        }
        return WalkResult(studs, elapsed / 1e9, resetRequested = false, paused = false)
    }

    private fun partialSeconds(v: Double, v2: Double, dt: Double, remain: Double): Double {
        if (abs(v2 - v) < 1e-12) {
            return remain / v
        }
        val a = (v2 - v) / 2.0 * dt
        val b = v * dt
        val disc = b * b + 4.0 * a * remain
        val f = ((-b + kotlin.math.sqrt(disc)) / (2.0 * a)).coerceIn(0.0, 1.0)
        return f * dt
    }
}

/** Capture budget shared by the phone grabber and the walk tests. */
object CaptureTiming {
    const val FPS = 20
    const val FRAME_NANOS = 1_000_000_000L / FPS
    const val FRAME_MS = 50L
    const val NAVIGATION_HZ = 4
}

/**
 * A missed buff read keeps the previous speed for 500 ms.
 * It never asks for a reset. After the hold expires the stick is released
 * and the caller retries the strip.
 */
class HeldSpeed(
    private val holdNanos: Long = 500_000_000L,
) {
    var resetRequested: Boolean = false
        private set
    var stickReleased: Boolean = false
        private set

    private var last: Double? = null
    private var lastAt: Long = Long.MIN_VALUE

    fun observe(nowNanos: Long, velocity: Double?): Double? {
        resetRequested = false
        if (velocity != null && velocity > 0.0) {
            last = velocity
            lastAt = nowNanos
            stickReleased = false
            return velocity
        }
        val kept = last
        if (kept != null && nowNanos - lastAt <= holdNanos) {
            stickReleased = false
            return kept
        }
        stickReleased = true
        return null
    }
}
