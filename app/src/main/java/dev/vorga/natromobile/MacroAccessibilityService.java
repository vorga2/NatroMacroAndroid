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
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MacroAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private static final long HOLD_SLICE_MS = 300L;
    private static final long INITIAL_DRAG_MS = 70L;

    private static volatile MacroAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String foregroundPackage = "";
    private volatile boolean robloxSessionActive = false;
    private volatile int activeJoyX = -1;
    private volatile int activeJoyY = -1;

    static MacroAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        refreshForegroundPackage();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        String rootPackage = packageFromActiveRoot();
        String eventPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String pkg = !rootPackage.isEmpty() ? rootPackage : eventPackage;

        // Our own floating controls/calibration do not count as leaving Roblox.
        if (!pkg.isEmpty() && !getPackageName().equals(pkg)) {
            foregroundPackage = pkg;
        }

        // Only enforce Roblox-only mode after Roblox has actually been detected.
        // Before that the macro is merely ARMED and may safely pass through the
        // launcher/SystemUI while MainActivity opens Roblox.
        if (running.get() && robloxSessionActive && !isRobloxForeground()) {
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
            showToast("Natro Mobile is already armed/running.");
            return;
        }

        robloxSessionActive = false;
        worker.execute(() -> {
            try {
                showToast("Natro Mobile: ARMED — waiting for Roblox");

                // No timeout. On Samsung the foreground Accessibility event can
                // arrive late. The floating STOP remains available while armed.
                if (!waitForRobloxForeground()) return;

                robloxSessionActive = true;
                showToast("Roblox detected — macro starts in " + Math.max(0, delayMs / 1000) + "s");
                if (!sleepWhileRunning(Math.max(0, delayMs))) return;
                if (!isRobloxForeground()) return;

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
        boolean wasInRoblox = robloxSessionActive && ROBLOX_PACKAGE.equals(foregroundPackage);
        running.set(false);
        robloxSessionActive = false;

        // A continued Accessibility stroke intentionally keeps the virtual
        // finger down. Dispatching one tiny neutral gesture cancels it. This is
        // only done while Roblox is still the foreground app.
        if (wasInRoblox) cancelActiveJoystickGesture();
    }

    boolean isRunningMacro() {
        return running.get();
    }

    boolean isRobloxForeground() {
        refreshForegroundPackage();
        return ROBLOX_PACKAGE.equals(foregroundPackage);
    }

    private boolean waitForRobloxForeground() {
        while (running.get()) {
            if (isRobloxForeground()) return true;
            if (!sleepWhileRunning(100)) return false;
        }
        return false;
    }

    private void refreshForegroundPackage() {
        String pkg = packageFromActiveRoot();
        if (!pkg.isEmpty() && !getPackageName().equals(pkg)) {
            foregroundPackage = pkg;
        }
    }

    private String packageFromActiveRoot() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Exception ignored) {
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
        return "";
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

        activeJoyX = cx;
        activeJoyY = cy;

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
                if (!holdJoystickContinuously(cx, cy, cx + dx, cy + dy, ms)) break;
            }
        }
    }

    private boolean canControlRoblox() {
        return running.get() && robloxSessionActive && isRobloxForeground();
    }

    private long durationForTiles(double tiles, float moveSpeed) {
        double studs = tiles * 4.0;
        double seconds = studs / Math.max(1.0, moveSpeed);
        return Math.max(100L, Math.round(seconds * 1000.0));
    }

    /**
     * Mobile Roblox needs a real held thumbstick, not a stream of independent
     * 120 ms swipes. We drag from the calibrated joystick center to the target,
     * then continue the SAME Accessibility stroke in short checked slices.
     * Every slice re-checks that Roblox is foreground, so another app never
     * receives macro touches.
     */
    private boolean holdJoystickContinuously(float cx, float cy, float tx, float ty, long totalMs) {
        if (!canControlRoblox()) return false;

        long dragMs = Math.min(INITIAL_DRAG_MS, Math.max(1L, totalMs));
        long remaining = Math.max(0L, totalMs - dragMs);
        boolean continueAfterDrag = remaining > 0;

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                line(cx, cy, tx, ty), 0, dragMs, continueAfterDrag);
        if (!dispatchAndWaitRobloxOnly(stroke, dragMs + 250L)) return false;

        while (canControlRoblox() && remaining > 0) {
            long chunk = Math.min(HOLD_SLICE_MS, remaining);
            remaining -= chunk;
            boolean willContinue = remaining > 0;

            try {
                stroke = stroke.continueStroke(holdPath(tx, ty), 0, Math.max(1L, chunk), willContinue);
            } catch (IllegalStateException | IllegalArgumentException e) {
                // Fallback for vendor Android builds that reject continued
                // strokes: use a longer normal joystick drag so movement still
                // works, while remaining Roblox-only.
                return holdJoystickFallback(cx, cy, tx, ty, chunk + remaining);
            }

            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 250L)) return false;
        }
        return true;
    }

    private boolean holdJoystickFallback(float cx, float cy, float tx, float ty, long totalMs) {
        long remaining = totalMs;
        while (canControlRoblox() && remaining > 0) {
            long chunk = Math.min(600L, remaining);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    line(cx, cy, tx, ty), 0, Math.max(80L, chunk), false);
            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 250L)) return false;
            remaining -= chunk;
        }
        return true;
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

        if (!accepted) {
            showToast("Android rejected the joystick gesture.");
            return false;
        }

        long deadline = SystemClock.uptimeMillis() + Math.max(50L, timeoutMs);
        while (canControlRoblox() && SystemClock.uptimeMillis() < deadline) {
            try {
                if (latch.await(20, TimeUnit.MILLISECONDS)) return ok.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void cancelActiveJoystickGesture() {
        if (!ROBLOX_PACKAGE.equals(foregroundPackage)) return;
        final int cx = activeJoyX;
        final int cy = activeJoyY;
        if (cx < 0 || cy < 0) return;

        main.post(() -> {
            if (!ROBLOX_PACKAGE.equals(foregroundPackage)) return;
            Path p = new Path();
            p.moveTo(cx, cy);
            p.lineTo(cx + 1f, cy);
            GestureDescription.StrokeDescription neutral =
                    new GestureDescription.StrokeDescription(p, 0, 1, false);
            try {
                dispatchGesture(new GestureDescription.Builder().addStroke(neutral).build(), null, null);
            } catch (Exception ignored) {}
        });
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private static Path holdPath(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        // Tiny round trip gives Android a non-empty continuation path while
        // being visually indistinguishable from holding the stick still.
        p.lineTo(x + 0.25f, y);
        p.lineTo(x, y);
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
