package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends ordinary Accessibility gestures to Roblox.
 *
 * Important for Roblox mobile thumbsticks: do NOT emulate a held stick by chaining
 * dozens of continueStroke() calls. On some Samsung/One UI builds that chain is
 * accepted only for the first tiny drag and Roblox sees just a short nudge.
 *
 * Instead each movement slice is one real long press directly at the calibrated
 * thumbstick deflection point, the same primitive used by macro record/replay apps.
 */
final class TouchEngine {
    interface Guard { boolean allowed(); }

    // Normal Natro movement is already split into short distance legs by MovementEngine.
    // This bound keeps STOP/focus-loss latency reasonable even for raw cannon/glider holds.
    private static final long MAX_HOLD_SLICE_MS = 700;

    private final AccessibilityService service;
    private final Guard guard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MacroConfig config;
    private final int width, height;

    TouchEngine(AccessibilityService s, Guard g, MacroConfig c, int w, int h) {
        service = s;
        guard = g;
        config = c;
        width = w;
        height = h;
    }

    private Path line(float x, float y, float tx, float ty) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(tx, ty);
        return p;
    }

    /** Android explicitly treats a zero-length path as a touch that does not move. */
    private Path holdPoint(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        return p;
    }

    void move(float x, float y, long ms, boolean gather) throws InterruptedException {
        move(x, y, ms, gather, new long[0]);
    }

    /**
     * Hold the calibrated joystick direction for the requested time.
     * jumpAt values are offsets from the beginning of this movement.
     */
    void move(float x, float y, long ms, boolean gather, long[] jumpAt) throws InterruptedException {
        check();
        if (ms <= 0) return;

        float[] unit = MovementMath.unit(x, y);
        float cx = config.x("joy", width), cy = config.y("joy", height);
        float r = config.number("radius_n", 0) * Math.min(width, height);
        float tx = cx + unit[0] * r;
        float ty = cy + unit[1] * r;
        boolean moving = x != 0 || y != 0;

        if (moving && ControlOverlayService.obscures(tx, ty))
            throw new IllegalStateException("Перетащи N • меню: оно перекрывает точку удержания джойстика");
        if (gather && ControlOverlayService.obscures(config.x("tool", width), config.y("tool", height)))
            throw new IllegalStateException("Меню перекрывает кнопку сбора");
        if (jumpAt.length > 0 && ControlOverlayService.obscures(config.x("jump", width), config.y("jump", height)))
            throw new IllegalStateException("Меню перекрывает кнопку прыжка");

        long elapsed = 0;
        while (elapsed < ms) {
            check();
            long chunk = Math.min(MAX_HOLD_SLICE_MS, ms - elapsed);

            // If a jump lands just beyond the default slice edge, extend this one so
            // movement + jump stay in the SAME GestureDescription and one finger does
            // not cancel the other through a second dispatchGesture() call.
            for (long at : jumpAt) {
                if (at >= elapsed && at < elapsed + chunk + 65)
                    chunk = Math.min(ms - elapsed, Math.max(chunk, at - elapsed + 65));
            }

            GestureDescription.Builder b = new GestureDescription.Builder();
            int strokes = 0;

            if (moving) {
                // For a fixed Roblox thumbstick, touching the calibrated edge point and
                // holding it is a stable full-deflection input. No continuation chain.
                b.addStroke(new GestureDescription.StrokeDescription(
                        holdPoint(tx, ty), 0, chunk, false));
                strokes++;
            }

            if (gather) {
                float toolX = config.x("tool", width);
                float toolY = config.y("tool", height);
                if (config.flag("tool_hold", true)) {
                    b.addStroke(new GestureDescription.StrokeDescription(
                            holdPoint(toolX, toolY), 0, chunk, false));
                    strokes++;
                } else {
                    int interval = Math.max(100, config.integer("tool_interval", 250));
                    long first = ((elapsed + interval - 1) / interval) * interval;
                    for (long at = first; at < elapsed + chunk; at += interval) {
                        long local = at - elapsed;
                        if (local + 45 > chunk) break;
                        b.addStroke(new GestureDescription.StrokeDescription(
                                holdPoint(toolX, toolY), local, 45, false));
                        strokes++;
                    }
                }
            }

            for (long at : jumpAt) {
                long local = at - elapsed;
                if (local >= 0 && local + 65 <= chunk) {
                    b.addStroke(new GestureDescription.StrokeDescription(
                            holdPoint(config.x("jump", width), config.y("jump", height)),
                            local, 65, false));
                    strokes++;
                }
            }

            if (strokes > 0) {
                send(b.build(), chunk);
            } else {
                waitFor(chunk);
            }
            elapsed += chunk;
        }
    }

    void tap(String key, long duration) throws InterruptedException {
        if (!config.point(key)) throw new IllegalStateException("Откалибруй кнопку: " + key);
        tap(config.x(key, width), config.y(key, height), duration);
    }

    void tap(float x, float y, long ms) throws InterruptedException {
        check();
        if (ControlOverlayService.obscures(x, y))
            throw new IllegalStateException("Перетащи N • меню: оно перекрывает кнопку");
        long duration = Math.max(1, ms);
        send(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(holdPoint(x, y), 0, duration, false))
                .build(), duration);
    }

    void swipe(float x, float y, float tx, float ty, long ms) throws InterruptedException {
        check();
        if (ControlOverlayService.obscuresPath(x, y, tx, ty))
            throw new IllegalStateException("Перетащи меню: оно перекрывает свайп камеры");
        send(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(line(x, y, tx, ty), 0, ms, false))
                .build(), ms);
    }

    void waitFor(long ms) throws InterruptedException {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            check();
            Thread.sleep(Math.min(40, Math.max(1, end - SystemClock.uptimeMillis())));
        }
    }

    private void check() throws InterruptedException {
        if (!guard.allowed()) throw new InterruptedException("Stopped or Roblox lost focus");
    }

    private void send(GestureDescription gesture, long duration) throws InterruptedException {
        check();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean complete = new AtomicBoolean();
        AtomicBoolean expired = new AtomicBoolean();

        main.post(() -> {
            if (expired.get() || !guard.allowed()) {
                done.countDown();
                return;
            }
            boolean accepted = false;
            try {
                accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        complete.set(true);
                        done.countDown();
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        done.countDown();
                    }
                }, main);
            } catch (RuntimeException ignored) { }
            if (!accepted) done.countDown();
        });

        boolean signaled = done.await(duration + 1800, TimeUnit.MILLISECONDS);
        expired.set(true);
        check();
        if (!signaled || !complete.get())
            throw new IllegalStateException(signaled
                    ? "Жест отменён Android. Проверь Accessibility и не касайся игры во время движения"
                    : "Android не подтвердил удержание джойстика");
    }

    /** All movement strokes are finite slices, so there is no continuation pointer to release. */
    void release(boolean robloxVisible) { }
}
