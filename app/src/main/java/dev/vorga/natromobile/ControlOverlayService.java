package dev.vorga.natromobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;

public class ControlOverlayService extends Service {
    private static final String CHANNEL = "macro_control";
    private WindowManager wm;
    private Button stop;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Natro Mobile running")
                .setContentText("Use the floating STOP button to end the macro")
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setOngoing(true)
                .build();
        startForeground(42, notification);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        stop = new Button(this);
        stop.setText("STOP");
        stop.setTextColor(Color.WHITE);
        stop.setBackgroundColor(0xCCB00020);
        stop.setOnClickListener(v -> {
            MacroAccessibilityService svc = MacroAccessibilityService.get();
            if (svc != null) svc.stopMacro();
            stopSelf();
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                220, 120,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = 20;
        lp.y = 160;
        wm.addView(stop, lp);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Macro control", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        if (wm != null && stop != null) {
            try { wm.removeView(stop); } catch (Exception ignored) {}
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
