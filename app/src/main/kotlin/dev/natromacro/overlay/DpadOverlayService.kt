package dev.natromacro.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.natromacro.NatroRuntime
import dev.natromacro.input.NatroKey

/** On-screen pad. Every button calls [dev.natromacro.input.InputBus], the same object the routes use. */
class DpadOverlayService : Service() {
    private var root: View? = null
    private lateinit var windowManager: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root != null) return START_STICKY
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = buildPad()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        windowManager.addView(view, params)
        root = view
        return START_STICKY
    }

    override fun onDestroy() {
        root?.let { windowManager.removeView(it) }
        root = null
        super.onDestroy()
    }

    private fun buildPad(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66111111)
            setPadding(12, 12, 12, 12)
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "speed —"
        }
        NatroRuntime.statusView = status
        column.addView(status)
        column.addView(row(
            move("FL", NatroKey.Forward, NatroKey.Left),
            move("F", NatroKey.Forward),
            move("FR", NatroKey.Forward, NatroKey.Right),
            action("Yaw+", NatroKey.RotRight),
            action("Pitch+", NatroKey.RotUp),
        ))
        column.addView(row(
            move("L", NatroKey.Left),
            move("B", NatroKey.Back),
            move("R", NatroKey.Right),
            action("Yaw-", NatroKey.RotLeft),
            action("Pitch-", NatroKey.RotDown),
        ))
        column.addView(row(
            move("BL", NatroKey.Back, NatroKey.Left),
            move("BR", NatroKey.Back, NatroKey.Right),
            action("Jump", NatroKey.Space),
            action("E", NatroKey.E),
            action("Shift", NatroKey.Shift),
        ))
        column.addView(row(
            action("Zoom+", NatroKey.ZoomIn),
            action("Zoom-", NatroKey.ZoomOut),
            action("1", NatroKey.Hotbar1),
            action("Menu", NatroKey.Esc),
            action("Click", NatroKey.Click),
        ))
        return column
    }

    private fun row(vararg buttons: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        }
    }

    private fun move(label: String, vararg keys: NatroKey): Button {
        return Button(this).apply {
            text = label
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> keys.forEach { NatroRuntime.bus?.keyDown(it) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> keys.forEach { NatroRuntime.bus?.keyUp(it) }
                }
                true
            }
        }
    }

    private fun action(label: String, key: NatroKey): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { NatroRuntime.bus?.tap(key, 1) }
        }
    }
}
