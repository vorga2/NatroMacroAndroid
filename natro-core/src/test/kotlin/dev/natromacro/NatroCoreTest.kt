package dev.natromacro

import dev.natromacro.input.Calibration
import dev.natromacro.input.CameraLedger
import dev.natromacro.input.GestureSink
import dev.natromacro.input.InputBus
import dev.natromacro.input.NatroKey
import dev.natromacro.input.Stroke
import dev.natromacro.input.TouchMapper
import dev.natromacro.macro.BossController
import dev.natromacro.macro.CannonApproach
import dev.natromacro.macro.DisconnectWatch
import dev.natromacro.macro.FieldDrift
import dev.natromacro.macro.FieldTravel
import dev.natromacro.macro.GatherLoop
import dev.natromacro.macro.HiveCamera
import dev.natromacro.macro.Ramp
import dev.natromacro.macro.ResetGate
import dev.natromacro.macro.patternScale
import dev.natromacro.motion.BuffReading
import dev.natromacro.motion.HeldSpeed
import dev.natromacro.motion.MoveSpeedProfile
import dev.natromacro.motion.MovespeedFormula
import dev.natromacro.motion.WalkIntegrator
import dev.natromacro.script.LogActuator
import dev.natromacro.script.RouteCatalog
import dev.natromacro.script.RouteParser
import dev.natromacro.script.RouteRunner
import dev.natromacro.script.ScriptEnv
import dev.natromacro.script.UnknownCallException
import dev.natromacro.vision.BuffScanner
import dev.natromacro.vision.BuffTemplates
import dev.natromacro.vision.Point
import dev.natromacro.vision.RgbImage
import dev.natromacro.vision.VisionPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class WalkCoreTest {
    private val plain = MoveSpeedProfile.fromConfigured(28.0)

    @Test
    fun constantSpeedCoversExactlyFortyStuds() {
        val result = WalkIntegrator().walk(10.0) { _, _ -> 28.0 }
        assertEquals(40.0, result.studs, 1e-6)
        assertEquals(40.0 / 28.0, result.elapsedSeconds, 1e-6)
        assertFalse(result.resetRequested)
        assertFalse(result.paused)
    }

    @Test
    fun hasteJumpKeepsDistanceAndDoesNotReset() {
        val result = WalkIntegrator().walk(10.0) { studs, _ -> if (studs < 20.0) 28.0 else 56.0 }
        assertEquals(40.0, result.studs, 1e-6)
        assertTrue(result.elapsedSeconds < 40.0 / 28.0)
        assertTrue(result.elapsedSeconds > 40.0 / 56.0)
        assertFalse(result.resetRequested)
    }

    @Test
    fun hastePlusHalvesTheTime() {
        val buffs = BuffReading(hastePlus = true)
        val speed = MovespeedFormula.velocity(plain, buffs)
        assertEquals(56.0, speed, 1e-9)
        val result = WalkIntegrator().walk(10.0) { _, _ -> speed }
        assertEquals((40.0 / 28.0) / 2.0, result.elapsedSeconds, 1e-6)
        assertFalse(result.resetRequested)
    }

    @Test
    fun buffMultipliersMatchWalkAhk() {
        assertEquals(38.0, MovespeedFormula.velocity(plain, BuffReading(coconutHaste = true)), 1e-9)
        assertEquals(32.0, MovespeedFormula.velocity(plain, BuffReading(bear = true)), 1e-9)
        assertEquals(33.6, MovespeedFormula.velocity(plain, BuffReading(oil = true)), 1e-9)
        assertEquals(35.0, MovespeedFormula.velocity(plain, BuffReading(smoothie = true)), 1e-9)
        assertEquals(56.0, MovespeedFormula.velocity(plain, BuffReading(hasteStacks = 10)), 1e-9)
        val guard = MoveSpeedProfile.fromConfigured(30.8)
        assertEquals(28.0, guard.base, 1e-9)
        assertTrue(guard.hastyGuard)
        assertFalse(guard.giftedHasty)
        assertEquals(30.8, MovespeedFormula.velocity(guard, BuffReading()), 1e-6)
        val gifted = MoveSpeedProfile.fromConfigured(32.2)
        assertEquals(28.0, gifted.base, 1e-9)
        assertTrue(gifted.giftedHasty)
        assertEquals(32.2, MovespeedFormula.velocity(gifted, BuffReading()), 1e-6)
        val both = MoveSpeedProfile.fromConfigured(35.42)
        assertEquals(28.0, both.base, 1e-9)
        assertTrue(both.hastyGuard && both.giftedHasty)
        assertEquals(35.42, MovespeedFormula.velocity(both, BuffReading()), 1e-6)
    }

    @Test
    fun hasteCapCompensatesOnlyStacksAboveTheCap() {
        val buffs = BuffReading(hasteStacks = 12)
        assertEquals(28.0 * (1.0 + 2 * 0.1), MovespeedFormula.velocity(plain, buffs, hasteCap = 10), 1e-9)
        assertEquals(28.0, MovespeedFormula.velocity(plain, BuffReading(hasteStacks = 10), hasteCap = 10), 1e-9)
    }

    @Test
    fun missedFrameKeepsSpeedAndDoesNotReset() {
        val held = HeldSpeed()
        assertEquals(28.0, held.observe(0, 28.0))
        assertEquals(28.0, held.observe(50_000_000, null))
        assertFalse(held.stickReleased)
        assertFalse(held.resetRequested)
        assertEquals(null, held.observe(500_000_001, null))
        assertTrue(held.stickReleased)
        assertFalse(held.resetRequested)
    }
}

class BuffScannerTest {
    @Test
    fun emptyFrameIsAMissNotZeroHaste() {
        assertEquals(null, BuffScanner().scan(RgbImage.empty()))
    }

    @Test
    fun hasteIconWithoutADigitIsOneStack() {
        val image = RgbImage(120, 40)
        image.blit(BuffTemplates.haste, 40, 16)
        val reading = BuffScanner().scan(image)!!
        assertEquals(1, reading.hasteStacks)
        assertFalse(reading.coconutHaste)
    }

    @Test
    fun digitNineAndHastePlus() {
        val image = RgbImage(120, 40)
        image.blit(BuffTemplates.haste, 50, 20)
        image.blit(BuffTemplates.digits.getValue(9), 20, 8)
        image.blit(BuffTemplates.hastePlus, 10, 30)
        val reading = BuffScanner().scan(image)!!
        assertEquals(9, reading.hasteStacks)
        assertTrue(reading.hastePlus)
    }

    @Test
    fun melodyIsNotCountedAsHaste() {
        val image = RgbImage(120, 40)
        image.blit(BuffTemplates.haste, 40, 16)
        image.blit(BuffTemplates.melody, 44, 14)
        val reading = BuffScanner().scan(image)!!
        assertEquals(0, reading.hasteStacks)
    }
}

class ControlsTest {
    @Test
    fun eightRightsUnwindToNothingAndTenRightsUnwindTwoLeft() {
        val ledger = CameraLedger()
        repeat(8) { ledger.apply(NatroKey.RotRight, 1) }
        assertTrue(ledger.unwind().isEmpty())
        repeat(10) { ledger.apply(NatroKey.RotRight, 1) }
        assertEquals(listOf(NatroKey.RotLeft to 2), ledger.unwind())
    }

    @Test
    fun diagonalStickStaysOnTheRimAndYawIsOneSwipePerStep() {
        val calibration = Calibration(
            stickCenterX = 200f,
            stickCenterY = 800f,
            stickRadius = 100f,
            cameraX = 900f,
            cameraY = 400f,
            pixelsPer45Deg = 80f,
            swipeDurationMs = 200,
        )
        val mapper = TouchMapper(calibration)
        val diagonal = mapper.stick(setOf(NatroKey.Forward, NatroKey.Right))
        assertEquals(100.0, hypot(diagonal.x, diagonal.y), 1e-6)
        val strokes = mapper.yawStrokes(NatroKey.RotRight, 2)
        assertEquals(2, strokes.size)
        assertEquals(80f, strokes[0].x1 - strokes[0].x0)
        assertEquals(200L, strokes[0].durationMs)
        assertTrue(strokes[0].durationMs in 150..250)
    }

    @Test
    fun macroAndPadShareOneBus() {
        val sink = RecordingSink()
        val bus = InputBus(
            TouchMapper(
                Calibration(10f, 10f, 40f, 100f, 100f, 30f, swipeDurationMs = 180),
            ),
            sink,
        )
        bus.keyDown(NatroKey.Forward)
        bus.keyDown(NatroKey.Right)
        bus.tap(NatroKey.RotRight, 1)
        val last = sink.sticks.last()
        assertTrue(last.third)
        assertEquals(40.0, hypot((last.first - 10f).toDouble(), (last.second - 10f).toDouble()), 1e-3)
        assertEquals(1, sink.strokeBatches.size)
        assertEquals(30f, sink.strokeBatches[0][0].x1 - sink.strokeBatches[0][0].x0)
    }
}

class RouteTest {
    @Test
    fun everyShippedRouteParsesAndRuns() {
        val catalog = RouteCatalog.load()
        assertEquals(103, catalog.names().size)
        val log = LogActuator()
        val runner = RouteRunner(log, ScriptEnv(hiveSlot = 3.0, hiveBees = 50.0, moveMethod = "Walk"))
        for (name in catalog.names()) {
            runner.run(catalog.require(name))
        }
        assertFalse(log.log.isEmpty())
    }

    @Test
    fun unknownCallFails() {
        assertThrows(UnknownCallException::class.java) {
            RouteParser().parse("Foo(1)\n", "bad.ahk")
        }
    }
}

class RouteBehaviorTest {
    @Test
    fun sunflowerRoute() {
        val catalog = RouteCatalog.load()
        val log = LogActuator()
        RouteRunner(log, ScriptEnv(hiveSlot = 1.0)).run(catalog.require("gtf-sunflower"))
        assertEquals(
            listOf(
                "ramp",
                "down Back",
                "walk 9.0 cap=0 keys=Back",
                "up Back",
                "down Back",
                "down Right",
                "walk 6.75 cap=0 keys=Back+Right",
                "up Back",
                "up Right",
                "tap RotRight x2",
                "down Right",
                "walk 29.0 cap=0 keys=Right",
                "up Right",
            ),
            log.log,
        )
    }

    @Test
    fun patternExitUnwindsCamera() {
        val source = """
            send "{RotRight 10}"
        """.trimIndent()
        val program = RouteParser().parse(source, "turn.ahk")
        val log = LogActuator()
        RouteRunner(log).run(program, unwindCamera = true)
        assertEquals(listOf("tap RotRight x10", "tap RotLeft x2"), log.log)
    }

    @Test
    fun rampTilesFollowHiveSlot() {
        assertEquals(5.0, Ramp.FORWARD_TILES)
        assertEquals(9.2 * 6 - 4, Ramp.rightTiles(6), 1e-9)
    }
}

class MacroFlowTest {
    private val catalog = RouteCatalog.load()

    @Test
    fun cannonMissResetsAndHasteDoesNot() {
        val vision = FakeVision()
        val log = LogActuator()
        val resets = ResetGate()
        val approach = CannonApproach(log, vision, resets, attempts = 10)
        assertFalse(approach.run())
        assertEquals(10, resets.count)
        assertTrue(resets.reasons().all { it == "cannon" })
        val before = resets.count
        val held = HeldSpeed()
        held.observe(0, MovespeedFormula.velocity(MoveSpeedProfile.fromConfigured(28.0), BuffReading()))
        held.observe(50_000_000, MovespeedFormula.velocity(MoveSpeedProfile.fromConfigured(28.0), BuffReading(hasteStacks = 10)))
        assertEquals(before, resets.count)
        assertFalse(held.resetRequested)
    }

    @Test
    fun questTravelResetsThenPressesE() {
        val log = LogActuator()
        val env = ScriptEnv()
        val runner = RouteRunner(log, env)
        val vision = FakeVision(found = mutableMapOf("e_button" to Point(10, 10)))
        val resets = ResetGate()
        val travel = FieldTravel(catalog, runner, dev.natromacro.macro.ShiftLock(log, vision))
        assertTrue(travel.gotoQuestgiver("polar", vision, resets))
        assertTrue(resets.reasons().contains("quest"))
        assertTrue(log.log.any { it.startsWith("down E") })
    }

    @Test
    fun planterCollectAndBoostUseTheirPrefixes() {
        val log = LogActuator()
        val vision = FakeVision(shiftOn = false)
        val travel = FieldTravel(catalog, RouteRunner(log), dev.natromacro.macro.ShiftLock(log, vision))
        val beforePlanter = log.log.size
        travel.gotoPlanter("sunflower")
        assertTrue(log.log.size > beforePlanter)
        travel.gotoCollect("clock")
        travel.gotoBooster("blue")
        assertTrue(log.log.isNotEmpty())
    }

    @Test
    fun gatherDoesNotResetWhenTheSprinklerIsAlreadyCentered() {
        val log = LogActuator()
        val vision = FakeVision(width = 1000, height = 1000, found = mutableMapOf("sprinkler" to Point(500, 500)))
        val resets = ResetGate()
        val runner = RouteRunner(log)
        GatherLoop(catalog, runner, dev.natromacro.macro.ShiftLock(log, vision), vision)
            .run("sunflower", "stationary", "M", 1, 0)
        assertEquals(0, resets.count)
        assertTrue(log.log.any { it.startsWith("walk") })
    }

    @Test
    fun bossAndDisconnectResetOnlyForTheirOwnReasons() {
        val log = LogActuator()
        val vision = FakeVision(found = mutableMapOf("health" to Point(1, 1)))
        val resets = ResetGate()
        val travel = FieldTravel(catalog, RouteRunner(log), dev.natromacro.macro.ShiftLock(log, vision))
        val bosses = BossController(travel, log, vision, resets)
        assertTrue(bosses.spiderAttempt { false })
        assertEquals(listOf("bugrun"), resets.reasons())
        val speed = HeldSpeed()
        speed.observe(0, 28.0)
        speed.observe(10, 56.0)
        assertEquals(1, resets.count)
        assertFalse(DisconnectWatch(vision, resets).poll())
        vision.disconnected = true
        assertTrue(DisconnectWatch(vision, resets).poll())
        assertEquals("disconnect", resets.reasons().last())
    }

    @Test
    fun hiveCameraFacesTheHiveOnTheFirstLook() {
        val log = LogActuator()
        val vision = FakeVision(found = mutableMapOf("hive" to Point(4, 4)))
        assertTrue(HiveCamera(log, vision).setDirection(4))
        assertTrue(log.log.contains("tap RotRight x4"))
        assertTrue(log.log.contains("tap ZoomOut x5"))
    }

    @Test
    fun driftPushesLeftWhenTheSprinklerIsLeftOfCenter() {
        val keys = FieldDrift.keysFor(10, 500, 1000, 1000)
        assertEquals(setOf(NatroKey.Left), keys)
        assertTrue(FieldDrift.keysFor(500, 500, 1000, 1000).isEmpty())
    }

    @Test
    fun patternSizesMatchNatro() {
        assertEquals(0.25, patternScale("XS"))
        assertEquals(0.5, patternScale("S"))
        assertEquals(1.0, patternScale("M"))
        assertEquals(1.5, patternScale("L"))
        assertEquals(2.0, patternScale("XL"))
    }
}

private class FakeVision(
    override var width: Int = 1920,
    override var height: Int = 1080,
    val found: MutableMap<String, Point> = mutableMapOf(),
    var shiftOn: Boolean = true,
    var disconnected: Boolean = false,
) : VisionPort {
    override fun find(name: String): Point? = found[name]
    override fun shiftLockOn(): Boolean = shiftOn
    override fun disconnected(): Boolean = disconnected
}

private class RecordingSink : GestureSink {
    val sticks = ArrayList<Triple<Float, Float, Boolean>>()
    val strokeBatches = ArrayList<List<Stroke>>()
    override fun stick(x: Float, y: Float, held: Boolean) { sticks += Triple(x, y, held) }
    override fun strokes(strokes: List<Stroke>, gapMs: Long) { strokeBatches += strokes }
    override fun tap(key: NatroKey, count: Int) = Unit
    override fun click(x: Float, y: Float) = Unit
}
