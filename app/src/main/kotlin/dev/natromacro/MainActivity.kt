package dev.natromacro

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import dev.natromacro.input.Calibration
import dev.natromacro.input.GestureSink
import dev.natromacro.input.InputBus
import dev.natromacro.input.NatroKey
import dev.natromacro.input.TouchMapper
import dev.natromacro.macro.LiveActuator
import dev.natromacro.macro.MacroSession
import dev.natromacro.macro.ResetGate
import dev.natromacro.motion.BuffReading
import dev.natromacro.motion.HeldSpeed
import dev.natromacro.motion.MoveSpeedProfile
import dev.natromacro.overlay.DpadOverlayService
import dev.natromacro.script.RouteCatalog
import dev.natromacro.script.ScriptEnv
import dev.natromacro.vision.BuffScanner
import dev.natromacro.vision.RgbImage
import dev.natromacro.vision.VisionPort
import dev.natromacro.vision.Point

class MainActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NatroRuntime.ensure(this)

        findViewById<Button>(R.id.overlay).setOnClickListener {
            saveFields()
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                startService(Intent(this, DpadOverlayService::class.java))
            }
        }
        findViewById<Button>(R.id.start).setOnClickListener {
            saveFields()
            val field = findViewById<EditText>(R.id.fieldName).text.toString().ifBlank { "sunflower" }
            Thread({
                try {
                    NatroRuntime.session().gather.run(field, "e_lol", "M", 1, 0)
                    runOnUiThread { status("Gather finished without a haste reset") }
                } catch (error: Exception) {
                    runOnUiThread { status(error.message ?: "gather failed") }
                }
            }, "natro-gather").start()
        }
    }

    private fun saveFields() {
        val speed = findViewById<EditText>(R.id.moveSpeed).text.toString().toDoubleOrNull() ?: 28.0
        val slot = findViewById<EditText>(R.id.hiveSlot).text.toString().toIntOrNull() ?: 1
        val pixels = findViewById<EditText>(R.id.pixelsPer45).text.toString().toFloatOrNull() ?: 120f
        NatroRuntime.moveSpeed = speed
        NatroRuntime.hiveSlot = slot.coerceIn(1, 6)
        NatroRuntime.updatePixels(pixels)
        Toast.makeText(this, "Saved speed $speed, hive ${NatroRuntime.hiveSlot}", Toast.LENGTH_SHORT).show()
    }

    private fun status(text: String) {
        findViewById<TextView>(R.id.status).text = text
    }
}

object NatroRuntime {
    var moveSpeed: Double = 28.0
    var hiveSlot: Int = 1
    var bus: InputBus? = null
    var calibration: Calibration = Calibration(
        stickCenterX = 220f,
        stickCenterY = 900f,
        stickRadius = 140f,
        cameraX = 900f,
        cameraY = 500f,
        pixelsPer45Deg = 120f,
        swipeDurationMs = 200,
        keyDelayMs = 50,
        buffTop = 48,
        buffHeight = 80,
    )
    var statusView: TextView? = null
    @Volatile var latestReading: BuffReading? = null
    @Volatile var frameWidth: Int = 0
    @Volatile var frameHeight: Int = 0

    private val heldSpeed = HeldSpeed()
    private val scanner = BuffScanner()
    private var lastCaptureMs = 0L
    private var buttons: Map<NatroKey, Pair<Float, Float>> = emptyMap()

    fun ensure(activity: MainActivity) {
        val metrics = activity.resources.displayMetrics
        if (buttons.isEmpty()) {
            buttons = defaultButtons(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            calibration = calibration.copy(
                stickCenterX = metrics.widthPixels * 0.18f,
                stickCenterY = metrics.heightPixels * 0.72f,
                cameraX = metrics.widthPixels * 0.72f,
                cameraY = metrics.heightPixels * 0.45f,
            )
        }
    }

    fun attachSink(sink: GestureSink) {
        bus = InputBus(TouchMapper(calibration), sink)
    }

    fun updatePixels(pixels: Float) {
        calibration = calibration.copy(pixelsPer45Deg = pixels)
        val sink = dev.natromacro.input.GestureAccessibilityService.instance ?: return
        attachSink(sink)
    }

    fun button(key: NatroKey): Pair<Float, Float>? = buttons[key]

    /** At most one screenshot every 300 ms, matching VisionEngine in the public repo. */
    fun refreshBuffs(): BuffReading {
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureMs >= 300) {
            lastCaptureMs = now
            val shot = dev.natromacro.input.GestureAccessibilityService.instance?.screenshotBlocking()
            if (shot != null) {
                try {
                    frameWidth = shot.width
                    frameHeight = shot.height
                    latestReading = readStrip(shot)
                } finally {
                    shot.recycle()
                }
            }
        }
        return latestReading ?: BuffReading()
    }

    private fun readStrip(full: Bitmap): BuffReading? {
        val top = calibration.buffTop.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val height = calibration.buffHeight.coerceIn(1, full.height - top)
        val strip = Bitmap.createBitmap(full, 0, top, full.width, height)
        try {
            val pixels = IntArray(strip.width * strip.height)
            strip.getPixels(pixels, 0, strip.width, 0, 0, strip.width, strip.height)
            return scanner.scan(RgbImage(strip.width, strip.height, pixels))
        } finally {
            if (strip !== full) strip.recycle()
        }
    }

    fun session(): MacroSession {
        val actuator = LiveActuator(
            bus = bus ?: error("Enable the accessibility service so gestures can be injected"),
            profile = MoveSpeedProfile.fromConfigured(moveSpeed),
            heldSpeed = heldSpeed,
            reading = { refreshBuffs() },
            nowNanos = { System.nanoTime() },
            hiveSlot = { hiveSlot },
        )
        val env = ScriptEnv(hiveSlot = hiveSlot.toDouble(), moveMethod = "Walk")
        val catalog = RouteCatalog.load()
        statusView?.post {
            val reading = latestReading
            statusView?.text = if (reading == null) "no buff frame" else "haste ${reading.hasteStacks}"
        }
        return MacroSession(catalog, actuator, env, PhoneVision(), ResetGate())
    }

    private fun defaultButtons(width: Float, height: Float): Map<NatroKey, Pair<Float, Float>> = mapOf(
        NatroKey.Space to (width * 0.86f to height * 0.78f),
        NatroKey.E to (width * 0.72f to height * 0.62f),
        NatroKey.Shift to (width * 0.08f to height * 0.62f),
        NatroKey.ZoomIn to (width * 0.9f to height * 0.2f),
        NatroKey.ZoomOut to (width * 0.9f to height * 0.28f),
        NatroKey.Hotbar1 to (width * 0.2f to height * 0.92f),
        NatroKey.Esc to (width * 0.08f to height * 0.08f),
        NatroKey.Click to (width * 0.5f to height * 0.55f),
        NatroKey.Enter to (width * 0.5f to height * 0.58f),
        NatroKey.R to (width * 0.5f to height * 0.5f),
    )
}

private class PhoneVision : VisionPort {
    override val width: Int get() = NatroRuntime.frameWidth
    override val height: Int get() = NatroRuntime.frameHeight
    override fun shiftLockOn(): Boolean = false
    override fun disconnected(): Boolean = false
    override fun find(name: String): Point? = null
}
