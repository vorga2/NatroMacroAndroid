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

public class CalibrationOverlayService extends Service {
    private WindowManager wm;
    private View overlay;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            TextView v = new TextView(this);
            v.setText("Tap the CENTER of Roblox movement joystick\n\nThis transparent screen closes after one tap.");
            v.setTextColor(Color.WHITE);
            v.setTextSize(20);
            v.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            v.setPadding(30, 120, 30, 30);
            v.setBackgroundColor(0x66000000);
            v.setOnTouchListener((view, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
                    p.edit()
                            .putInt("joy_x", Math.round(event.getRawX()))
                            .putInt("joy_y", Math.round(event.getRawY()))
                            .putInt("joy_radius", 150)
                            .apply();
                    stopSelf();
                    return true;
                }
                return true;
            });
            overlay = v;

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
