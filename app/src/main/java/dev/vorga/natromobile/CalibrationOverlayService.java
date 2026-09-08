package dev.vorga.natromobile;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

public class CalibrationOverlayService extends Service {
    private WindowManager wm;
    private View overlay;
    private TextView text;
    private int step = 0;
    private int centerX;
    private int centerY;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            text = new TextView(this);
            text.setTextColor(Color.WHITE);
            text.setTextSize(20);
            text.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            text.setPadding(30, 110, 30, 30);
            text.setBackgroundColor(0x55000000);
            updateText();
            text.setOnTouchListener(this::onTouch);
            overlay = text;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(overlay, lp);
        }, 1200);
    }

    private boolean onTouch(View view, MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return true;

        int x = Math.round(event.getRawX());
        int y = Math.round(event.getRawY());
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);

        if (step == 0) {
            centerX = x;
            centerY = y;
            p.edit().putInt("joy_x", x).putInt("joy_y", y).apply();
            step = 1;
            updateText();
            return true;
        }

        if (step == 1) {
            int dx = x - centerX;
            int dy = y - centerY;
            int radius = (int) Math.round(Math.sqrt(dx * dx + dy * dy));
            radius = Math.max(55, Math.min(280, radius));
            p.edit().putInt("joy_radius", radius).apply();
            step = 2;
            updateText();
            return true;
        }

        p.edit().putInt("jump_x", x).putInt("jump_y", y).apply();
        Toast.makeText(this, "Controls calibrated", Toast.LENGTH_LONG).show();
        stopSelf();
        return true;
    }

    private void updateText() {
        if (text == null) return;
        if (step == 0) {
            text.setText("Natro calibration 1/3\nTap the CENTER of the Roblox movement joystick");
        } else if (step == 1) {
            text.setText("Natro calibration 2/3\nTap the RIGHT EDGE of the joystick ring\n(this measures the real joystick radius)");
        } else {
            text.setText("Natro calibration 3/3\nTap the Roblox JUMP button");
        }
    }

    @Override
    public void onDestroy() {
        if (wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Exception ignored) {}
        }
        overlay = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
