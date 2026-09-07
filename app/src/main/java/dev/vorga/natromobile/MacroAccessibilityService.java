package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MacroAccessibilityService extends AccessibilityService {
    private static volatile MacroAccessibilityService instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    static MacroAccessibilityService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

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
        if (!running.compareAndSet(false, true)) return;
        worker.execute(() -> {
            try {
                Thread.sleep(Math.max(0, delayMs));
                runConfiguredPatternLoop();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                running.set(false);
                stopOverlay();
            }
        });
    }

    void stopMacro() {
        running.set(false);
    }

    boolean isRunningMacro() {
        return running.get();
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

        if (cx < 0 || cy < 0) return;
        long deadline = System.currentTimeMillis() + Math.max(10, runSeconds) * 1000L;
        List<PatternStep> steps = PatternFactory.create(pattern, size, reps);

        while (running.get() && System.currentTimeMillis() < deadline) {
            if (steps.isEmpty()) {
                sleepChecked(500);
                continue;
            }
            for (PatternStep step : steps) {
                if (!running.get() || System.currentTimeMillis() >= deadline) break;
                long ms = durationForTiles(step.tiles, speed);
                float len = (float) Math.sqrt(step.x * step.x + step.y * step.y);
                float dx = step.x / len * radius;
                float dy = step.y / len * radius;
                holdJoystick(cx, cy, cx + dx, cy + dy, ms);
            }
        }
    }

    private long durationForTiles(double tiles, float moveSpeed) {
        // Natro Walk() treats one pattern tile as 4 Roblox studs.
        double studs = tiles * 4.0;
        double seconds = studs / Math.max(1.0, moveSpeed);
        return Math.max(80L, Math.round(seconds * 1000.0));
    }

    private void holdJoystick(float cx, float cy, float tx, float ty, long totalMs) {
        if (!running.get()) return;
        long settleMs = Math.min(120L, Math.max(70L, totalMs / 4));
        boolean needsContinuation = totalMs > settleMs;
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                line(cx, cy, tx, ty), 0, settleMs, needsContinuation);
        if (!dispatchAndWait(stroke)) return;

        long remaining = Math.max(0, totalMs - settleMs);
        while (running.get() && remaining > 0) {
            long chunk = Math.min(3000L, remaining);
            boolean continues = remaining > chunk;
            stroke = stroke.continueStroke(point(tx, ty), 0, chunk, continues);
            if (!dispatchAndWait(stroke)) return;
            remaining -= chunk;
        }

        // If the last segment was marked continue because stop arrived mid-loop, release quickly.
        if (!running.get() && remaining > 0) {
            try {
                GestureDescription.StrokeDescription release = stroke.continueStroke(point(tx, ty), 0, 1, false);
                dispatchAndWait(release);
            } catch (IllegalStateException ignored) {}
        }
    }

    private boolean dispatchAndWait(GestureDescription.StrokeDescription stroke) {
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
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok.get();
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private static Path point(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 0.01f, y + 0.01f);
        return p;
    }

    private void sleepChecked(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopOverlay() {
        try {
            stopService(new Intent(this, ControlOverlayService.class));
        } catch (Exception ignored) {}
    }
}
