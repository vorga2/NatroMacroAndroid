package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MacroAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private static final long SAFE_GESTURE_SLICE_MS = 120L;

    private static volatile MacroAccessibilityService instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String foregroundPackage = "";
    // Safety must only become strict after Roblox has actually been reached.
    // During the hand-off MainActivity -> Roblox Android may briefly report
    // launcher/SystemUI windows. Treating those as "left Roblox" caused the
    // v0.2 startup race where the macro silently stopped before it started.
    private volatile boolean robloxSessionActive = false;

    static MacroAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;

        String pkg = event.getPackageName().toString();
        // Our floating STOP/calibration overlay is not a real app switch.
        if (getPackageName().equals(pkg)) return;

        foregroundPackage = pkg;

        // Before the run has positively entered Roblox we are only ARMED.
        // Do not cancel on launcher/SystemUI transition windows.
        if (running.get() && robloxSessionActive && !ROBLOX_PACKAGE.equals(pkg)) {
            stopMacro();
        }
    }

    @Override
    public void onInterrupt() {
        stopMacro();
    }

    @Override
    public void onDestroy() {
        stopMacro();
        if (instance == this) instance = null;
        worker.shutdownNow();
        super.onDestroy();
    }

    void startMacroAfterDelay(long delayMs) {
        if (!running.compareAndSet(false, true)) {
            showToast("Natro Mobile is already starting/running.");
            return;
        }
        robloxSessionActive = false;

        worker.execute(() -> {
            try {
                if (!sleepWhileRunning(Math.max(0, delayMs))) return;

                // We now tolerate launcher/SystemUI during startup and wait for
                // an actual Roblox foreground event instead of cancelling early.
                if (!waitForRobloxForeground(15_000L)) {
                    showToast("Roblox foreground was not detected. Open Roblox and press START again.");
                    return;
                }

                robloxSessionActive = true;
                showToast("Natro Mobile: RUNNING");
                runConfiguredPatternLoop();
            } finally {
                running.set(false);
                robloxSessionActive = false;
                stopOverlay();
            }
        });
    }

    void stopMacro() {
        running.set(false);
        robloxSessionActive = false;
    }

    boolean isRunningMacro() {
        return running.get();
    }

    boolean isRobloxForeground() {
        return ROBLOX_PACKAGE.equals(foregroundPackage);
    }

    private boolean waitForRobloxForeground(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (running.get() && SystemClock.uptimeMillis() < deadline) {
            if (isRobloxForeground()) return true;
            if (!sleepWhileRunning(50)) return false;
        }
        return false;
    }

    private void runConfiguredPatternLoop() {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        int cx = p.getInt("joy_x", -1);
        int cy = p.getInt("joy_y", -1);
        int radius = p.getInt("joy_radius", 150);
        float speed = p.getFloat("movespeed", 28f);
        float size = p.getFloat("pattern_size", 1f);
        int reps = p.getInt("pattern_reps", 3);
        int runSeconds = p.getInt("run_seconds", 900);
        String pattern = p.getString("pattern", "Snake");

        if (cx < 0 || cy < 0) {
            showToast("Joystick calibration is missing.");
            return;
        }

        long deadline = System.currentTimeMillis() + Math.max(10, runSeconds) * 1000L;
        List<PatternStep> steps = PatternFactory.create(pattern, size, reps);

        while (canControlRoblox() && System.currentTimeMillis() < deadline) {
            if (steps.isEmpty()) {
                if (!sleepWhileRunning(100)) break;
                continue;
            }
            for (PatternStep step : steps) {
                if (!canControlRoblox() || System.currentTimeMillis() >= deadline) break;
                long ms = durationForTiles(step.tiles, speed);
                float len = (float) Math.sqrt(step.x * step.x + step.y * step.y);
                if (len <= 0.0001f) {
                    if (!sleepWhileRunning(Math.min(ms, 250))) break;
                    continue;
                }
                float dx = step.x / len * radius;
                float dy = step.y / len * radius;
                holdJoystickSafely(cx, cy, cx + dx, cy + dy, ms);
            }
        }
    }

    private boolean canControlRoblox() {
        return running.get() && robloxSessionActive && isRobloxForeground();
    }

    private long durationForTiles(double tiles, float moveSpeed) {
        double studs = tiles * 4.0;
        double seconds = studs / Math.max(1.0, moveSpeed);
        return Math.max(80L, Math.round(seconds * 1000.0));
    }

    private void holdJoystickSafely(float cx, float cy, float tx, float ty, long totalMs) {
        long remaining = totalMs;
        while (canControlRoblox() && remaining > 0) {
            long chunk = Math.min(SAFE_GESTURE_SLICE_MS, remaining);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    line(cx, cy, tx, ty), 0, Math.max(1L, chunk), false);
            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 120L)) return;
            remaining -= chunk;
        }
    }

    private boolean dispatchAndWaitRobloxOnly(GestureDescription.StrokeDescription stroke, long timeoutMs) {
        if (!canControlRoblox()) return false;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                ok.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                latch.countDown();
            }
        }, main);
        if (!accepted) return false;

        long deadline = SystemClock.uptimeMillis() + Math.max(50L, timeoutMs);
        while (canControlRoblox() && SystemClock.uptimeMillis() < deadline) {
            try {
                if (latch.await(25, TimeUnit.MILLISECONDS)) return ok.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private boolean sleepWhileRunning(long ms) {
        long deadline = SystemClock.uptimeMillis() + Math.max(0L, ms);
        while (running.get() && SystemClock.uptimeMillis() < deadline) {
            long left = deadline - SystemClock.uptimeMillis();
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return running.get();
    }

    private void showToast(String text) {
        main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show());
    }

    private void stopOverlay() {
        try {
            stopService(new Intent(this, ControlOverlayService.class));
        } catch (Exception ignored) {}
    }
}
