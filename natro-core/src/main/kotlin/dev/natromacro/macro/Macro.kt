package dev.natromacro.macro

import dev.natromacro.input.InputBus
import dev.natromacro.input.NatroKey
import dev.natromacro.motion.BuffReading
import dev.natromacro.motion.HeldSpeed
import dev.natromacro.motion.MoveSpeedProfile
import dev.natromacro.motion.MovespeedFormula
import dev.natromacro.script.Actuator
import dev.natromacro.script.RouteCatalog
import dev.natromacro.script.RouteRunner
import dev.natromacro.script.ScriptEnv
import dev.natromacro.script.Value
import dev.natromacro.vision.VisionPort

fun patternScale(size: String): Double = when (size.uppercase()) {
    "XS" -> 0.25
    "S" -> 0.5
    "L" -> 1.5
    "XL" -> 2.0
    else -> 1.0
}

/** `nm_gotoRamp`: 5 tiles forward, then `9.2 * hiveSlot - 4` tiles right. */
object Ramp {
    const val FORWARD_TILES = 5.0
    fun rightTiles(hiveSlot: Int): Double = 9.2 * hiveSlot - 4.0
}

class ResetGate {
    private val reasons = ArrayList<String>()
    val count: Int get() = reasons.size
    fun reasons(): List<String> = reasons.toList()
    fun reset(reason: String) { reasons += reason }
}

/**
 * Sprinkler recentering. The window matches `nm_fieldDriftCompensation`.
 * At 20 FPS the search runs once per frame and stops after 6 seconds.
 */
object FieldDrift {
    fun keysFor(x: Int, y: Int, width: Int, height: Int): Set<NatroKey> {
        val winLeft = kotlin.math.floor(width / 2.14).toInt()
        val winRight = kotlin.math.floor(width / 1.88).toInt()
        val winUp = kotlin.math.floor(height / 2.14).toInt()
        val winDown = kotlin.math.floor(height / 1.88).toInt()
        val keys = LinkedHashSet<NatroKey>()
        if (x < winLeft) keys += NatroKey.Left
        else if (x > winRight) keys += NatroKey.Right
        if (y < winUp) keys += NatroKey.Forward
        else if (y > winDown) keys += NatroKey.Back
        return keys
    }

    fun correct(
        vision: VisionPort,
        hold: (Set<NatroKey>) -> Unit,
        release: () -> Unit,
        nowNanos: () -> Long,
        sleepNanos: (Long) -> Unit,
    ): Boolean {
        val start = nowNanos()
        val limit = 6_000_000_000L
        while (nowNanos() - start < limit) {
            val point = vision.find("sprinkler") ?: run {
                release()
                return false
            }
            val keys = keysFor(point.x, point.y, vision.width, vision.height)
            if (keys.isEmpty()) {
                release()
                return true
            }
            hold(keys)
            sleepNanos(50_000_000L)
        }
        release()
        return false
    }
}

class ShiftLock(private val actuator: Actuator, private val vision: VisionPort) {
    fun set(on: Boolean) {
        if (vision.shiftLockOn() != on) actuator.tap(NatroKey.Shift, 1)
    }

    fun off() = set(false)
}

/**
 * Red cannon from `nm_gotoCannon`. A miss resets and walks the ramp again.
 * Ten misses is the frozen-game stop. Haste is not a reason to reset.
 */
class CannonApproach(
    private val actuator: Actuator,
    private val vision: VisionPort,
    private val resets: ResetGate,
    private val attempts: Int = 10,
) {
    fun run(): Boolean {
        actuator.tap(NatroKey.Shift, 1)
        repeat(attempts) {
            actuator.keyDown(NatroKey.Space)
            actuator.keyDown(NatroKey.Right)
            actuator.sleep(100)
            actuator.keyUp(NatroKey.Space)
            actuator.walk(2.0, 0)
            actuator.keyDown(NatroKey.Forward)
            actuator.walk(1.5, 0)
            actuator.keyUp(NatroKey.Forward)
            actuator.keyUp(NatroKey.Right)
            if (vision.find("redcannon") != null && confirmCannon()) return true
            resets.reset("cannon")
            actuator.gotoRamp()
        }
        return false
    }

    private fun confirmCannon(): Boolean {
        for (tryIndex in 0 until 10) {
            actuator.sleep(500)
            if (vision.find("redcannon") != null) return true
            actuator.keyDown(NatroKey.Left)
            actuator.walk(1.5, 0)
            actuator.keyUp(NatroKey.Left)
            if (tryIndex == 9) return false
        }
        return false
    }
}

/** `nm_SetHiveCameraDirection`: spin until the hive is visible, then face it. */
class HiveCamera(private val actuator: Actuator, private val vision: VisionPort) {
    fun setDirection(rotations: Int, keyDelayMs: Long = 0): Boolean {
        var pitchedDown = false
        val maxIndex = 8 / rotations * 2
        for (index in 1..maxIndex) {
            actuator.sleep(250 + keyDelayMs)
            if (vision.find("hive") != null) {
                actuator.tap(NatroKey.RotRight, 4)
                if (pitchedDown) actuator.tap(NatroKey.RotUp, 1)
                actuator.tap(NatroKey.ZoomOut, 5)
                return true
            }
            actuator.tap(NatroKey.RotRight, rotations)
            if (index == maxIndex / 2) {
                pitchedDown = !pitchedDown
                actuator.tap(if (pitchedDown) NatroKey.RotDown else NatroKey.RotUp, 1)
            }
        }
        return false
    }
}

class FieldTravel(
    private val catalog: RouteCatalog,
    private val runner: RouteRunner,
    private val shift: ShiftLock,
) {
    fun gotoField(field: String) {
        shift.off()
        runner.run(catalog.require("gtf-${normalize(field)}"))
    }

    fun walkFrom(field: String) {
        shift.off()
        runner.run(catalog.require("wf-${normalize(field)}"))
    }

    fun gotoPlanter(field: String) {
        shift.off()
        runner.run(catalog.require("gtp-${normalize(field)}"))
    }

    fun gotoCollect(name: String) {
        shift.off()
        runner.run(catalog.require("gtc-${normalize(name)}"))
    }

    fun gotoBooster(name: String) {
        shift.off()
        runner.run(catalog.require("gtb-${normalize(name)}"))
    }

    /**
     * `nm_gotoQuestgiver` resets, runs `gtq`, then presses E when the prompt is up.
     * The reset is the same one Natro does before the walk, not a reaction to haste.
     */
    fun gotoQuestgiver(giver: String, vision: VisionPort, resets: ResetGate): Boolean {
        val actuator = runnerActuator
        repeat(2) {
            resets.reset("quest")
            shift.off()
            runner.run(catalog.require("gtq-${normalize(giver)}"))
            repeat(2) {
                actuator.sleep(500)
                if (vision.find("e_button") != null) {
                    actuator.keyDown(NatroKey.E)
                    actuator.sleep(100)
                    actuator.keyUp(NatroKey.E)
                    for (attempt in 1..500) {
                        val dialog = vision.find("dialog") ?: break
                        actuator.clickAt(dialog.x, dialog.y)
                    }
                    return true
                }
            }
        }
        return false
    }

    private val runnerActuator: Actuator get() = runner.actuator

    companion object {
        fun normalize(name: String) = name.lowercase().replace(" ", "")
    }
}

class GatherLoop(
    private val catalog: RouteCatalog,
    private val runner: RouteRunner,
    private val shift: ShiftLock,
    private val vision: VisionPort,
) {
    fun run(field: String, pattern: String, patternSize: String, reps: Int, facingCorner: Int) {
        val travel = FieldTravel(catalog, runner, shift)
        travel.gotoField(field)
        runner.env.set("size", Value.Num(patternScale(patternSize)))
        runner.env.set("reps", Value.Num(reps.toDouble()))
        runner.env.set("facingcorner", Value.Num(facingCorner.toDouble()))
        runner.run(catalog.require(pattern.lowercase()), unwindCamera = true)
        val held = linkedSetOf<NatroKey>()
        var clock = 0L
        FieldDrift.correct(
            vision = vision,
            hold = { keys ->
                held.forEach { runner.actuator.keyUp(it) }
                held.clear()
                keys.forEach {
                    runner.actuator.keyDown(it)
                    held += it
                }
            },
            release = {
                held.forEach { runner.actuator.keyUp(it) }
                held.clear()
            },
            nowNanos = { clock },
            sleepNanos = { delta ->
                clock += delta
                runner.actuator.sleep(delta / 1_000_000)
            },
        )
        travel.walkFrom(field)
    }
}

/**
 * Bug run opens with an explicit reset, then the field path.
 * A speed change while attacking does not add another reset.
 */
class BossController(
    private val travel: FieldTravel,
    private val actuator: Actuator,
    private val vision: VisionPort,
    private val resets: ResetGate,
) {
    fun spiderAttempt(interrupted: () -> Boolean): Boolean {
        if (interrupted()) return false
        resets.reset("bugrun")
        travel.gotoField("spider")
        if (vision.find("health") == null) return false
        actuator.tap(NatroKey.RotUp, 4)
        actuator.keyDown(NatroKey.Click)
        return true
    }

    fun mondo(interrupted: () -> Boolean): Boolean {
        if (interrupted()) return false
        travel.gotoField("mountain top")
        return vision.find("health") != null
    }

    fun vicious(interrupted: () -> Boolean): Boolean {
        if (interrupted() || vision.disconnected()) return false
        actuator.tap(NatroKey.Hotbar1, 1)
        return true
    }
}

class DisconnectWatch(private val vision: VisionPort, private val resets: ResetGate) {
    fun poll(): Boolean {
        if (!vision.disconnected()) return false
        resets.reset("disconnect")
        return true
    }
}

/**
 * Phone-side actuator. Walk time comes from [WalkIntegrator] and [HeldSpeed],
 * so a missed buff frame keeps the last speed and does not reset.
 */
class LiveActuator(
    private val bus: InputBus,
    private val profile: MoveSpeedProfile,
    private val heldSpeed: HeldSpeed,
    private val reading: () -> BuffReading?,
    private val nowNanos: () -> Long,
    private val hiveSlot: () -> Int,
) : Actuator {
    override fun keyDown(key: NatroKey) = bus.keyDown(key)
    override fun keyUp(key: NatroKey) = bus.keyUp(key)
    override fun tap(key: NatroKey, count: Int) = bus.tap(key, count)
    override fun unwindCamera() = bus.unwindCamera()
    override fun clickAt(x: Int, y: Int) = bus.click(x.toFloat(), y.toFloat())
    override fun sleep(ms: Long) {
        if (ms > 0) Thread.sleep(ms)
    }

    override fun walk(tiles: Double, hasteCap: Int): Long {
        val target = tiles * 4.0
        if (target <= 0.0) return 0
        var studs = 0.0
        val start = nowNanos()
        var lastTick = start
        var velocity = sample(hasteCap) ?: 0.0
        while (studs < target - 1e-6) {
            Thread.sleep(dev.natromacro.motion.CaptureTiming.FRAME_MS)
            val now = nowNanos()
            val next = sample(hasteCap)
            if (next == null) {
                bus.releaseStick()
                lastTick = now
                continue
            }
            val dt = (now - lastTick) / 1e9
            lastTick = now
            val segment = (velocity + next) / 2.0 * dt.coerceAtLeast(0.0)
            studs += segment
            velocity = next
            if (studs >= target) break
        }
        return ((nowNanos() - start) / 1_000_000.0).toLong()
    }

    private fun sample(hasteCap: Int): Double? {
        val buffs = reading()
        val velocity = buffs?.let { MovespeedFormula.velocity(profile, it, hasteCap) }
        return heldSpeed.observe(nowNanos(), velocity)
    }

    override fun gotoRamp() {
        keyDown(NatroKey.Forward)
        walk(Ramp.FORWARD_TILES, 0)
        keyUp(NatroKey.Forward)
        keyDown(NatroKey.Right)
        walk(Ramp.rightTiles(hiveSlot()), 0)
        keyUp(NatroKey.Right)
    }

    override fun gotoCannon() = Unit
}

class MacroSession(
    val catalog: RouteCatalog,
    val actuator: Actuator,
    val env: ScriptEnv,
    val vision: VisionPort,
    val resets: ResetGate = ResetGate(),
) {
    val runner = RouteRunner(actuator, env)
    val shift = ShiftLock(actuator, vision)
    val travel = FieldTravel(catalog, runner, shift)
    val gather = GatherLoop(catalog, runner, shift, vision)
    val hive = HiveCamera(actuator, vision)
    val cannon = CannonApproach(actuator, vision, resets)
    val bosses = BossController(travel, actuator, vision, resets)
    val disconnect = DisconnectWatch(vision, resets)

    fun attachCannon() {
        if (actuator is LiveActuator) return
    }
}
