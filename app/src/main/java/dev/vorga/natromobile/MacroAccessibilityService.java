package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MacroAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    // Long enough that Roblox sees a real held thumbstick instead of pulses,
    // short enough that the service still re-checks foreground regularly.
    private static final long HOLD_SLICE_MS = 1800L;
    private static final long INITIAL_DRAG_MS = 75L;

    private static volatile MacroAccessibilityService instance;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String foregroundPackage = "";
    private volatile boolean robloxSessionActive = false;
    private volatile int activeJoyX = -1;
    private volatile int activeJoyY = -1;
    private volatile int screenW = 0;
    private volatile int screenH = 0;

    static MacroAccessibilityService get() { return instance; }

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
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        String rootPackage = packageFromActiveRoot();
        String eventPackage = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String pkg = !rootPackage.isEmpty() ? rootPackage : eventPackage;
        if (!pkg.isEmpty() && !getPackageName().equals(pkg)) foregroundPackage = pkg;

        if (running.get() && robloxSessionActive && !isRobloxForeground()) stopMacro();
    }

    @Override public void onInterrupt() { stopMacro(); }

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
                setState("ARMED");
                showToast("Natro: waiting for Roblox");
                if (!waitForRobloxForeground()) return;

                robloxSessionActive = true;
                if (!sleepWhileRunning(Math.max(0, delayMs))) return;
                if (!canControlRoblox()) return;

                showToast("Natro: Pine Tree route started");
                runPineTreeRoute();
            } finally {
                running.set(false);
                robloxSessionActive = false;
                setState("STOPPED");
                stopOverlay();
            }
        });
    }

    void stopMacro() {
        boolean wasRunning = running.getAndSet(false);
        robloxSessionActive = false;
        if (wasRunning && ROBLOX_PACKAGE.equals(foregroundPackage)) cancelActiveJoystickGesture();
        setState("STOPPED");
    }

    private void runPineTreeRoute() {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        int cx = p.getInt("joy_x", -1);
        int cy = p.getInt("joy_y", -1);
        int radius = p.getInt("joy_radius", -1);
        int jumpX = p.getInt("jump_x", -1);
        int jumpY = p.getInt("jump_y", -1);
        int hiveSlot = Math.max(1, Math.min(6, p.getInt("hive_slot", 1)));
        float speed = Math.max(1f, p.getFloat("movespeed", 28f));

        if (cx < 0 || cy < 0 || radius < 0 || jumpX < 0 || jumpY < 0) {
            showToast("Run 3-step controls calibration first.");
            return;
        }
        activeJoyX = cx;
        activeJoyY = cy;

        setState("INSPECT HIVE");
        if (!inspectAndAlignHive()) {
            setState("RESPAWN");
            if (!respawnViaRobloxMenu()) return;
            if (!waitForRespawnHive(14_000L) || !inspectAndAlignHive()) {
                showToast("Hive was not recognized after respawn. Press STOP and calibrate/start again at your hive.");
                return;
            }
        }

        // Exact Natro nm_gotoRamp(): Walk(5, Fwd), Walk(9.2*HiveSlot-4, Right)
        setState("HIVE → RAMP");
        if (!moveVector(cx, cy, radius, 0f, -1f, 5.0, speed)) return;
        if (!moveVector(cx, cy, radius, 1f, 0f, 9.2 * hiveSlot - 4.0, speed)) return;

        setState("FIND CANNON");
        PointF cannon = gotoRedCannon(cx, cy, radius, jumpX, jumpY, speed);
        if (cannon == null) {
            showToast("Red cannon was not found. Natro stopped instead of wandering away.");
            return;
        }

        setState("USE CANNON");
        if (!tapCannonPrompt(cannon)) return;

        // Mobile translation of Natro gtf-pinetree Cannon block:
        // E, Right+Back 925ms, Space x2, 4500ms glide, Back up,
        // Right another 500ms, Space, RotLeft x4, settle.
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 925L)) return;
        if (!tapAt(jumpX, jumpY, 65)) return;
        if (!sleepWhileRunning(80)) return;
        if (!tapAt(jumpX, jumpY, 65)) return;

        setState("GLIDER → PINE");
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 4500L)) return;
        if (!holdJoystickMs(cx, cy, radius, 1f, 0f, 500L)) return;
        if (!tapAt(jumpX, jumpY, 70)) return;
        if (!rotateCameraLeftSteps(4)) return;
        if (!sleepWhileRunning(1800)) return;

        setState("PINE: GATHER");
        runConfiguredPatternLoop(cx, cy, radius, speed);
    }

    /** Natro's reset scans four camera directions for the gold hive strip. */
    private boolean inspectAndAlignHive() {
        for (int pass = 0; pass < 2 && canControlRoblox(); pass++) {
            for (int i = 0; i < 4 && canControlRoblox(); i++) {
                Bitmap shot = screenshotSync(1800);
                if (shot != null) {
                    rememberScreenSize(shot);
                    boolean hive = looksLikeHive(shot);
                    shot.recycle();
                    if (hive) {
                        setState("HIVE FOUND");
                        // Same intent as Natro RotRight x4 after hive recognition:
                        // face away from the hive toward the ramp.
                        if (!rotateCameraRightSteps(4)) return false;
                        sleepWhileRunning(300);
                        return true;
                    }
                }
                if (!rotateCameraRightSteps(4)) return false;
                sleepWhileRunning(180);
            }
            // Natro changes vertical camera angle halfway through its hive scan.
            if (!cameraPitch(pass == 0 ? 0.13f : -0.13f)) return false;
        }
        return false;
    }

    private boolean waitForRespawnHive(long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (canControlRoblox() && SystemClock.uptimeMillis() < end) {
            Bitmap shot = screenshotSync(1600);
            if (shot != null) {
                rememberScreenSize(shot);
                boolean found = looksLikeHive(shot);
                shot.recycle();
                if (found) return true;
            }
            if (!sleepWhileRunning(650)) return false;
        }
        return false;
    }

    /**
     * Roblox's current mobile menu has Respawn in the bottom-center. This is
     * only used when hive vision failed; if the layout is different, foreground
     * safety stops the macro rather than continuing blindly.
     */
    private boolean respawnViaRobloxMenu() {
        Bitmap shot = screenshotSync(1600);
        if (shot == null) return false;
        rememberScreenSize(shot);
        shot.recycle();
        int w = screenW, h = screenH;
        if (w <= 0 || h <= 0) return false;

        // Hamburger / Roblox in-experience menu.
        if (!tapAt(Math.round(w * 0.055f), Math.round(h * 0.060f), 80)) return false;
        if (!sleepWhileRunning(650)) return false;
        // Current mobile UI: Leave | Respawn | Resume along the bottom.
        if (!tapAt(Math.round(w * 0.535f), Math.round(h * 0.875f), 90)) return false;
        if (!sleepWhileRunning(550)) return false;
        // Confirmation card: Reset is the left action.
        if (!tapAt(Math.round(w * 0.425f), Math.round(h * 0.665f), 90)) return false;
        return sleepWhileRunning(4200);
    }

    /** Mobile port of Natro nm_gotoCannon with red-cannon visual correction. */
    private PointF gotoRedCannon(int cx, int cy, int radius, int jumpX, int jumpY, float speed) {
        PointF last = null;
        for (int attempt = 0; attempt < 10 && canControlRoblox(); attempt++) {
            // Original: Space+Right, Walk(2, Right), Walk(1.5, Fwd+Right)
            if (!tapAt(jumpX, jumpY, 65)) return null;
            if (!moveVector(cx, cy, radius, 1f, 0f, 2.0, speed)) return null;
            if (!moveVector(cx, cy, radius, 1f, -1f, 1.5, speed)) return null;

            for (int probe = 0; probe < 5 && canControlRoblox(); probe++) {
                Bitmap shot = screenshotSync(1300);
                if (shot != null) {
                    rememberScreenSize(shot);
                    last = findRedCannon(shot);
                    shot.recycle();
                    if (last != null) {
                        // Confirmation check like desktop Natro's repeated image search.
                        if (!sleepWhileRunning(220)) return null;
                        Bitmap confirm = screenshotSync(1300);
                        if (confirm != null) {
                            PointF c2 = findRedCannon(confirm);
                            confirm.recycle();
                            if (c2 != null) return c2;
                        }
                    }
                }
                if (!moveVector(cx, cy, radius, 1f, 0f, 0.65, speed)) return null;
            }
        }
        return last;
    }

    private boolean tapCannonPrompt(PointF cannon) {
        int w = screenW, h = screenH;
        // Default Roblox ProximityPrompt follows the object in screen space.
        // Tap around the detected red-cannon center, then a little below it.
        if (!tapAt(Math.round(cannon.x), Math.round(cannon.y), 90)) return false;
        if (!sleepWhileRunning(110)) return false;
        if (!tapAt(Math.round(cannon.x), Math.round(Math.min(h - 1, cannon.y + h * 0.045f)), 90)) return false;
        if (!sleepWhileRunning(180)) return false;
        // Fallback around the same top-center region Natro searches for cannon.
        return tapAt(Math.round(w * 0.50f), Math.round(h * 0.235f), 90);
    }

    private void runConfiguredPatternLoop(int cx, int cy, int radius, float speed) {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        float size = p.getFloat("pattern_size", 1f);
        int reps = p.getInt("pattern_reps", 3);
        int runSeconds = p.getInt("run_seconds", 900);
        String pattern = p.getString("pattern", "Snake");
        List<PatternStep> steps = PatternFactory.create(pattern, size, reps);
        long deadline = System.currentTimeMillis() + Math.max(10, runSeconds) * 1000L;

        while (canControlRoblox() && System.currentTimeMillis() < deadline) {
            if (steps.isEmpty()) {
                if (!sleepWhileRunning(100)) break;
                continue;
            }
            for (PatternStep step : steps) {
                if (!canControlRoblox() || System.currentTimeMillis() >= deadline) break;
                if (!moveVector(cx, cy, radius, step.x, step.y, step.tiles, speed)) return;
            }
        }
    }

    private boolean moveVector(int cx, int cy, int radius, float x, float y, double tiles, float speed) {
        if (tiles <= 0) return true;
        float len = (float) Math.sqrt(x * x + y * y);
        if (len < 0.001f) return sleepWhileRunning(durationForTiles(tiles, speed));
        return holdJoystickMs(cx, cy, radius, x / len, y / len, durationForTiles(tiles, speed));
    }

    private boolean holdJoystickMs(int cx, int cy, int radius, float nx, float ny, long ms) {
        float tx = cx + nx * radius;
        float ty = cy + ny * radius;
        return holdJoystickContinuously(cx, cy, tx, ty, Math.max(80L, ms));
    }

    private long durationForTiles(double tiles, float moveSpeed) {
        // Same physical model as Natro Walk(): one tile = four Roblox studs.
        return Math.max(90L, Math.round((tiles * 4.0 / Math.max(1.0, moveSpeed)) * 1000.0));
    }

    private boolean holdJoystickContinuously(float cx, float cy, float tx, float ty, long totalMs) {
        if (!canControlRoblox()) return false;
        long dragMs = Math.min(INITIAL_DRAG_MS, Math.max(1L, totalMs));
        long remaining = Math.max(0L, totalMs - dragMs);
        boolean continued = remaining > 0;

        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                line(cx, cy, tx, ty), 0, dragMs, continued);
        if (!dispatchAndWaitRobloxOnly(stroke, dragMs + 220L)) return false;

        while (canControlRoblox() && remaining > 0) {
            long chunk = Math.min(HOLD_SLICE_MS, remaining);
            remaining -= chunk;
            boolean willContinue = remaining > 0;
            try {
                stroke = stroke.continueStroke(holdPath(tx, ty), 0, Math.max(1L, chunk), willContinue);
            } catch (IllegalStateException | IllegalArgumentException e) {
                return holdJoystickFallback(cx, cy, tx, ty, chunk + remaining);
            }
            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 220L)) return false;
        }
        return true;
    }

    private boolean holdJoystickFallback(float cx, float cy, float tx, float ty, long totalMs) {
        long remaining = totalMs;
        while (canControlRoblox() && remaining > 0) {
            long chunk = Math.min(1200L, remaining);
            GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(
                    line(cx, cy, tx, ty), 0, Math.max(90L, chunk), false);
            if (!dispatchAndWaitRobloxOnly(s, chunk + 220L)) return false;
            remaining -= chunk;
        }
        return true;
    }

    private boolean rotateCameraRightSteps(int steps) {
        ensureScreenSize();
        if (screenW <= 0 || screenH <= 0) return false;
        float amount = screenW * 0.045f * Math.max(1, steps);
        float sx = screenW * 0.64f, sy = screenH * 0.43f;
        return swipe(sx, sy, Math.max(screenW * 0.12f, sx - amount), sy, 220);
    }

    private boolean rotateCameraLeftSteps(int steps) {
        ensureScreenSize();
        if (screenW <= 0 || screenH <= 0) return false;
        float amount = screenW * 0.045f * Math.max(1, steps);
        float sx = screenW * 0.54f, sy = screenH * 0.43f;
        return swipe(sx, sy, Math.min(screenW * 0.88f, sx + amount), sy, 220);
    }

    private boolean cameraPitch(float fraction) {
        ensureScreenSize();
        if (screenW <= 0 || screenH <= 0) return false;
        float sx = screenW * 0.62f, sy = screenH * 0.43f;
        return swipe(sx, sy, sx, sy + screenH * fraction, 190);
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                line(x1, y1, x2, y2), 0, Math.max(1L, ms), false);
        return dispatchAndWaitRobloxOnly(stroke, ms + 250L);
    }

    private boolean tapAt(int x, int y, long durationMs) {
        if (!canControlRoblox()) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                p, 0, Math.max(1L, durationMs), false);
        return dispatchAndWaitRobloxOnly(stroke, durationMs + 250L);
    }

    private boolean dispatchAndWaitRobloxOnly(GestureDescription.StrokeDescription stroke, long timeoutMs) {
        if (!canControlRoblox()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted;
        try {
            accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { ok.set(true); latch.countDown(); }
                @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
            }, main);
        } catch (Exception e) {
            return false;
        }
        if (!accepted) return false;

        long end = SystemClock.uptimeMillis() + Math.max(50L, timeoutMs);
        while (canControlRoblox() && SystemClock.uptimeMillis() < end) {
            try {
                if (latch.await(20, TimeUnit.MILLISECONDS)) return ok.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Bitmap screenshotSync(long timeoutMs) {
        if (!canControlRoblox()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        main.post(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, Runnable::run, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        HardwareBuffer hb = result.getHardwareBuffer();
                        try {
                            Bitmap wrapped = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
                            if (wrapped != null) out.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                        } finally {
                            try { hb.close(); } catch (Exception ignored) {}
                            latch.countDown();
                        }
                    }
                    @Override public void onFailure(int errorCode) { latch.countDown(); }
                });
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try { latch.await(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return out.get();
    }

    private boolean looksLikeHive(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = (int) (w * 0.06f), x1 = (int) (w * 0.94f);
        int y0 = (int) (h * 0.56f), y1 = (int) (h * 0.96f);
        int[][] colors = {
                {0xd2,0x8f,0x0c}, {0xc0,0x82,0x00}, {0x91,0x65,0x07},
                {0x84,0x5c,0x00}, {0xb9,0x7e,0x03}, {0xff,0xb3,0x25},
                {0xe3,0x9d,0x1f}, {0xa2,0x86,0x45}
        };
        for (int y = y0; y < y1; y += 3) {
            int run = 0;
            for (int x = x0; x < x1; x += 3) {
                int c = b.getPixel(x, y);
                if (nearAny(Color.red(c), Color.green(c), Color.blue(c), colors, 48)) {
                    if (++run >= 7) return true; // about the 22px strip Natro searches for
                } else run = 0;
            }
        }
        return false;
    }

    private PointF findRedCannon(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = (int) (w * 0.27f), x1 = (int) (w * 0.73f);
        int y0 = (int) (h * 0.045f), y1 = (int) (h * 0.38f);
        long sx = 0, sy = 0;
        int count = 0;
        int minX = x1, maxX = x0, minY = y1, maxY = y0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                if (r > 145 && r - g > 42 && r - bl > 35 && r > g * 1.25f && r > bl * 1.18f) {
                    count++; sx += x; sy += y;
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        if (count < 38 || maxX - minX < 18 || maxY - minY < 8) return null;
        return new PointF((float) sx / count, (float) sy / count);
    }

    private static boolean nearAny(int r, int g, int b, int[][] refs, int tolerance) {
        for (int[] c : refs) {
            if (Math.abs(r-c[0]) <= tolerance && Math.abs(g-c[1]) <= tolerance && Math.abs(b-c[2]) <= tolerance) return true;
        }
        return false;
    }

    private void rememberScreenSize(Bitmap b) { screenW = b.getWidth(); screenH = b.getHeight(); }

    private void ensureScreenSize() {
        if (screenW > 0 && screenH > 0) return;
        Bitmap b = screenshotSync(1300);
        if (b != null) { rememberScreenSize(b); b.recycle(); }
    }

    private boolean canControlRoblox() {
        return running.get() && robloxSessionActive && isRobloxForeground();
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
        if (!pkg.isEmpty() && !getPackageName().equals(pkg)) foregroundPackage = pkg;
    }

    private String packageFromActiveRoot() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) return root.getPackageName().toString();
        } catch (Exception ignored) {
        } finally {
            if (root != null) try { root.recycle(); } catch (Exception ignored) {}
        }
        return "";
    }

    private void cancelActiveJoystickGesture() {
        final int cx = activeJoyX, cy = activeJoyY;
        if (cx < 0 || cy < 0) return;
        main.post(() -> {
            if (!ROBLOX_PACKAGE.equals(foregroundPackage)) return;
            Path p = new Path(); p.moveTo(cx, cy); p.lineTo(cx + 1f, cy);
            try {
                dispatchGesture(new GestureDescription.Builder().addStroke(
                        new GestureDescription.StrokeDescription(p, 0, 1, false)).build(), null, null);
            } catch (Exception ignored) {}
        });
    }

    private static Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2); return p;
    }

    private static Path holdPath(float x, float y) {
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 0.35f, y); p.lineTo(x, y); return p;
    }

    private boolean sleepWhileRunning(long ms) {
        long end = SystemClock.uptimeMillis() + Math.max(0L, ms);
        while (running.get() && SystemClock.uptimeMillis() < end) {
            try { Thread.sleep(Math.min(50L, Math.max(1L, end - SystemClock.uptimeMillis()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return running.get();
    }

    private void setState(String s) { ControlOverlayService.setState(s); }
    private void showToast(String text) { main.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show()); }
    private void stopOverlay() { try { stopService(new Intent(this, ControlOverlayService.class)); } catch (Exception ignored) {} }
}
