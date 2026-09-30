package dev.natromacro.input

import kotlin.math.hypot

enum class NatroKey {
    Forward, Left, Back, Right,
    RotLeft, RotRight, RotUp, RotDown,
    ZoomIn, ZoomOut,
    E, R, L, Esc, Enter, Shift, Space, Hotbar1, Slash, Click,
}

fun NatroKey.isMove(): Boolean = this == NatroKey.Forward || this == NatroKey.Left ||
    this == NatroKey.Back || this == NatroKey.Right

fun NatroKey.isYaw(): Boolean = this == NatroKey.RotLeft || this == NatroKey.RotRight

fun NatroKey.isPitch(): Boolean = this == NatroKey.RotUp || this == NatroKey.RotDown

/**
 * Same ledger as `nm_CameraRotation`: yaw is a count of 45° steps,
 * pitch is absolute. Eight yaw steps are a full turn.
 */
class CameraLedger {
    var yaw: Int = 0
        private set
    var pitch: Int = 0
        private set

    fun apply(key: NatroKey, count: Int) {
        when (key) {
            NatroKey.RotLeft -> yaw -= count
            NatroKey.RotRight -> yaw += count
            NatroKey.RotUp -> pitch -= count
            NatroKey.RotDown -> pitch += count
            else -> Unit
        }
    }

    /** Taps that put the camera back. A multiple of 8 yaw steps needs nothing. */
    fun unwind(): List<Pair<NatroKey, Int>> {
        val taps = mutableListOf<Pair<NatroKey, Int>>()
        val yawFix = Math.floorMod(kotlin.math.abs(yaw), 8)
        if (yawFix != 0) {
            val key = if (yaw > 0) NatroKey.RotLeft else NatroKey.RotRight
            taps += key to yawFix
        }
        if (pitch != 0) {
            val key = if (pitch > 0) NatroKey.RotUp else NatroKey.RotDown
            taps += key to kotlin.math.abs(pitch)
        }
        yaw = 0
        pitch = 0
        return taps
    }
}

data class Calibration(
    val stickCenterX: Float,
    val stickCenterY: Float,
    val stickRadius: Float,
    val cameraX: Float,
    val cameraY: Float,
    val pixelsPer45Deg: Float,
    val swipeDurationMs: Long = 200,
    val keyDelayMs: Long = 50,
    val buffLeft: Int = 0,
    val buffTop: Int = 48,
    val buffWidth: Int = 0,
    val buffHeight: Int = 30,
)

data class Stroke(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val durationMs: Long,
)

data class Vec(val x: Double, val y: Double)

class TouchMapper(val calibration: Calibration) {
    /** Full deflection on the rim. A diagonal is not shorter than a cardinal. */
    fun stick(keys: Set<NatroKey>): Vec {
        var x = 0.0
        var y = 0.0
        if (NatroKey.Right in keys) x += 1.0
        if (NatroKey.Left in keys) x -= 1.0
        if (NatroKey.Back in keys) y += 1.0
        if (NatroKey.Forward in keys) y -= 1.0
        if (x == 0.0 && y == 0.0) return Vec(0.0, 0.0)
        val len = hypot(x, y)
        val radius = calibration.stickRadius.toDouble()
        return Vec(x / len * radius, y / len * radius)
    }

    fun stickPoint(keys: Set<NatroKey>): Pair<Float, Float> {
        val v = stick(keys)
        return calibration.stickCenterX + v.x.toFloat() to calibration.stickCenterY + v.y.toFloat()
    }

    /** One swipe per 45° step. N taps are N gestures, not one long drag. */
    fun yawStrokes(key: NatroKey, count: Int): List<Stroke> {
        val dir = if (key == NatroKey.RotRight) 1f else -1f
        val dx = dir * calibration.pixelsPer45Deg
        return List(count) {
            Stroke(
                calibration.cameraX,
                calibration.cameraY,
                calibration.cameraX + dx,
                calibration.cameraY,
                calibration.swipeDurationMs,
            )
        }
    }

    fun pitchStrokes(key: NatroKey, count: Int): List<Stroke> {
        val dir = if (key == NatroKey.RotUp) -1f else 1f
        val dy = dir * calibration.pixelsPer45Deg
        return List(count) {
            Stroke(
                calibration.cameraX,
                calibration.cameraY,
                calibration.cameraX,
                calibration.cameraY + dy,
                calibration.swipeDurationMs,
            )
        }
    }
}

interface GestureSink {
    fun stick(x: Float, y: Float, held: Boolean)
    fun strokes(strokes: List<Stroke>, gapMs: Long)
    fun tap(key: NatroKey, count: Int)
    fun click(x: Float, y: Float)
}

/**
 * One bus for the macro and the D-pad. Buttons call this; they do not inject touches themselves.
 */
class InputBus(
    private val mapper: TouchMapper,
    private val sink: GestureSink,
    private val ledger: CameraLedger = CameraLedger(),
) {
    private val held = linkedSetOf<NatroKey>()

    fun heldKeys(): Set<NatroKey> = held.toSet()

    fun keyDown(key: NatroKey) {
        if (!held.add(key)) return
        if (key.isMove()) publishStick()
    }

    fun keyUp(key: NatroKey) {
        if (!held.remove(key)) return
        if (key.isMove()) publishStick()
    }

    fun tap(key: NatroKey, count: Int) {
        if (count <= 0) return
        when {
            key.isYaw() -> {
                ledger.apply(key, count)
                sink.strokes(mapper.yawStrokes(key, count), mapper.calibration.keyDelayMs)
            }
            key.isPitch() -> {
                ledger.apply(key, count)
                sink.strokes(mapper.pitchStrokes(key, count), mapper.calibration.keyDelayMs)
            }
            else -> sink.tap(key, count)
        }
    }

    fun click(x: Float, y: Float) {
        sink.click(x, y)
    }

    fun releaseStick() {
        val moves = held.filter { it.isMove() }
        moves.forEach { held.remove(it) }
        publishStick()
    }

    fun unwindCamera() {
        for ((key, count) in ledger.unwind()) {
            val strokes = if (key.isYaw()) mapper.yawStrokes(key, count) else mapper.pitchStrokes(key, count)
            sink.strokes(strokes, mapper.calibration.keyDelayMs)
        }
    }

    private fun publishStick() {
        val moves = held.filter { it.isMove() }.toSet()
        if (moves.isEmpty()) {
            sink.stick(mapper.calibration.stickCenterX, mapper.calibration.stickCenterY, held = false)
        } else {
            val (x, y) = mapper.stickPoint(moves)
            sink.stick(x, y, held = true)
        }
    }
}
