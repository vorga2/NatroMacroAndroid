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
import android.view.accessibility.AccessibilityWindowInfo;
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
    private static final long HOLD_SLICE_MS = 1600L;
    private static final long INITIAL_DRAG_MS = 70L;

    private static final int[][] HIVE_COLORS = {
            {0xd2,0x8f,0x0c}, {0xc0,0x82,0x00}, {0x91,0x65,0x07},
            {0x84,0x5c,0x00}, {0xb9,0x7e,0x03}, {0xaa,0x74,0x00},
            {0xff,0xb3,0x25}, {0x69,0x4a,0x00}, {0xe3,0x9d,0x1f},
            {0xa2,0x86,0x45}
    };

    private static final int[][] SPAWN_COLORS = {
            {0x93,0x7b,0x1f}, {0x79,0x66,0x15}, {0xc1,0xae,0x55},
            {0x95,0x7a,0x70}, {0x98,0x8a,0x55}, {0xc3,0xbc,0xa5}
    };

    private enum SpawnLocation { HIVE, SPAWN, UNKNOWN }

    private static volatile MacroAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String foregroundPackage = "";
    private volatile boolean robloxSessionActive = false;
    private volatile long lastRobloxSeenAt = 0L;
    private volatile int activeJoyX = -1;
    private volatile int activeJoyY = -1;
    private volatile int screenW = 0;
    private volatile int screenH = 0;

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

        String interactive = packageFromInteractiveWindows();
        if (!interactive.isEmpty()) {
            rememberForeground(interactive);
            return;
        }

        String root = packageFromActiveRoot();
        if (!root.isEmpty() && !getPackageName().equals(root)) {
            rememberForeground(root);
            return;
        }

        if (event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if (!pkg.isEmpty() && !getPackageName().equals(pkg)) {
                rememberForeground(pkg);
            }
        }

        // Do not stop from one transient OneUI/SystemUI accessibility event.
        // Every actual gesture is still gated by canControlRoblox().
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
                setState("ARMED");
                showToast("Natro: waiting for Roblox");
                if (!waitForRobloxForeground()) return;

                robloxSessionActive = true;
                if (!sleepWhileRunning(Math.max(0L, delayMs))) return;
                if (!canControlRoblox()) return;

                showToast("Natro: reset → hive → Pine Tree");
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
        boolean robloxWasVisible = isRobloxInteractiveWindow();
        robloxSessionActive = false;

        if (wasRunning && robloxWasVisible) {
            cancelActiveJoystickGesture();
        }
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
            showToast("Run controls calibration first.");
            return;
        }

        activeJoyX = cx;
        activeJoyY = cy;

        // Always reset first. Continue only when the post-reset scan confirms
        // the hive center. A normal spawn result causes another reset.
        boolean centeredAtHive = false;
        for (int attempt = 1; attempt <= 3 && canControlRoblox(); attempt++) {
            setState("RESET " + attempt + "/3");
            if (!respawnViaRobloxMenu()) {
                showToast("Could not press Roblox Respawn/Reset.");
                return;
            }

            setState("WAIT RESPAWN");
            if (!sleepWhileRunning(4300L)) return;

            setState("CHECK HIVE / SPAWN");
            SpawnLocation location = scanRespawnLocationAndAlign();

            if (location == SpawnLocation.HIVE) {
                centeredAtHive = true;
                break;
            }

            if (location == SpawnLocation.SPAWN) {
                setState("SPAWN → RESET AGAIN");
                showToast("Spawn detected — resetting again for hive.");
                if (!sleepWhileRunning(650L)) return;
                continue;
            }

            setState("UNKNOWN → RESCAN");
            if (!sleepWhileRunning(650L)) return;
        }

        if (!centeredAtHive) {
            setState("NO HIVE — STOP");
            showToast("Natro did not confirm the hive center, so it will not walk blindly.");
            return;
        }

        setState("HIVE CENTER CONFIRMED");

        // Natro nm_gotoRamp(): Walk(5, Fwd), Walk(9.2*HiveSlot-4, Right)
        setState("HIVE → RAMP");
        if (!moveVector(cx, cy, radius, 0f, -1f, 5.0, speed)) return;
        if (!moveVector(cx, cy, radius, 1f, 0f, 9.2 * hiveSlot - 4.0, speed)) return;

        setState("FIND CANNON");
        PointF cannon = gotoRedCannon(cx, cy, radius, jumpX, jumpY, speed);
        if (cannon == null) {
            showToast("Red cannon was not found. Stopping instead of wandering.");
            return;
        }

        setState("USE CANNON");
        if (!tapCannonPrompt(cannon)) return;

        // Pine Tree cannon timing from original Natro path.
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 925L)) return;
        if (!tapAt(jumpX, jumpY, 65L)) return;
        if (!sleepWhileRunning(80L)) return;
        if (!tapAt(jumpX, jumpY, 65L)) return;

        setState("GLIDER → PINE");
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 4500L)) return;
        if (!holdJoystickMs(cx, cy, radius, 1f, 0f, 500L)) return;
        if (!tapAt(jumpX, jumpY, 70L)) return;
        if (!rotateCameraLeftSteps(4)) return;
        if (!sleepWhileRunning(1800L)) return;

        setState("PINE: GATHER");
        runConfiguredPatternLoop(cx, cy, radius, speed);
    }

    private SpawnLocation scanRespawnLocationAndAlign() {
        boolean sawSpawn = false;

        for (int pass = 0; pass < 2 && canControlRoblox(); pass++) {
            for (int yaw = 0; yaw < 4 && canControlRoblox(); yaw++) {
                Bitmap shot = screenshotSync(1800L);
                if (shot != null) {
                    rememberScreenSize(shot);
                    SpawnLocation location = classifyRespawnLocation(shot);
                    shot.recycle();

                    if (location == SpawnLocation.HIVE) {
                        setState("HIVE FOUND");
                        if (!rotateCameraRightSteps(4)) return SpawnLocation.UNKNOWN;
                        if (!sleepWhileRunning(280L)) return SpawnLocation.UNKNOWN;
                        return SpawnLocation.HIVE;
                    }

                    if (location == SpawnLocation.SPAWN) {
                        sawSpawn = true;
                    }
                }

                if (!rotateCameraRightSteps(1)) return SpawnLocation.UNKNOWN;
                if (!sleepWhileRunning(150L)) return SpawnLocation.UNKNOWN;
            }

            if (!cameraPitch(pass == 0 ? 0.12f : -0.12f)) {
                return SpawnLocation.UNKNOWN;
            }
            if (!sleepWhileRunning(180L)) return SpawnLocation.UNKNOWN;
        }

        return sawSpawn ? SpawnLocation.SPAWN : SpawnLocation.UNKNOWN;
    }

    private SpawnLocation classifyRespawnLocation(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x0 = Math.max(0, Math.round(w * 0.04f));
        int x1 = Math.min(w, Math.round(w * 0.96f));
        int y0 = Math.max(0, Math.round(h * 0.46f));
        int y1 = Math.min(h, Math.round(h * 0.96f));

        int hiveRun = maxPaletteHorizontalRun(bitmap, x0, y0, x1, y1, HIVE_COLORS, 24);
        int spawnRun = maxPaletteHorizontalRun(bitmap, x0, y0, x1, y1, SPAWN_COLORS, 22);

        int threshold = Math.max(14, w / 150);

        if (hiveRun >= threshold && hiveRun >= spawnRun + 5) {
            return SpawnLocation.HIVE;
        }
        if (spawnRun >= threshold && spawnRun >= hiveRun + 4) {
            return SpawnLocation.SPAWN;
        }
        return SpawnLocation.UNKNOWN;
    }

    private int maxPaletteHorizontalRun(
            Bitmap bitmap,
            int x0, int y0, int x1, int y1,
            int[][] palette,
            int tolerance
    ) {
        int max = 0;
        int stepX = 2;
        int stepY = 3;

        for (int y = y0; y < y1; y += stepY) {
            int run = 0;
            for (int x = x0; x < x1; x += stepX) {
                int c = bitmap.getPixel(x, y);
                if (matchesPalette(c, palette, tolerance)) {
                    run += stepX;
                    if (run > max) max = run;
                } else {
                    run = 0;
                }
            }
        }
        return max;
    }

    private boolean matchesPalette(int color, int[][] palette, int tolerance) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        int limit = tolerance * tolerance * 3;

        for (int[] p : palette) {
            int dr = r - p[0];
            int dg = g - p[1];
            int db = b - p[2];
            if (dr * dr + dg * dg + db * db <= limit) {
                return true;
            }
        }
        return false;
    }

    private boolean respawnViaRobloxMenu() {
        if (!primeScreenSize()) return false;

        int w = screenW;
        int h = screenH;

        setState("OPEN ROBLOX MENU");

        boolean menuOpened = clickNodeByAnyText("Menu");
        if (!menuOpened) {
            menuOpened = tapAt(
                    Math.max(30, Math.round(w * 0.024f)),
                    Math.max(28, Math.round(h * 0.050f)),
                    75L
            );
        }
        if (!menuOpened || !sleepWhileRunning(650L)) return false;

        setState("PRESS RESPAWN");
        boolean respawnPressed = clickNodeByAnyText(
                "Respawn",
                "Reset Character",
                "Reset character"
        );

        if (!respawnPressed) {
            respawnPressed = tapAt(
                    Math.round(w * 0.50f),
                    Math.round(h * 0.865f),
                    85L
            );
        }
        if (!respawnPressed || !sleepWhileRunning(520L)) return false;

        setState("CONFIRM RESET");
        boolean resetPressed = clickNodeByAnyText("Reset");

        if (!resetPressed) {
            resetPressed = tapAt(
                    Math.round(w * 0.425f),
                    Math.round(h * 0.665f),
                    85L
            );
        }

        return resetPressed;
    }

    private boolean clickNodeByAnyText(String... texts) {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return false;
            if (!ROBLOX_PACKAGE.equals(root.getPackageName().toString())) return false;

            for (String text : texts) {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                if (nodes == null) continue;

                for (AccessibilityNodeInfo node : nodes) {
                    AccessibilityNodeInfo current = node;
                    for (int depth = 0; current != null && depth < 5; depth++) {
                        if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                        current = current.getParent();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (root != null) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private PointF gotoRedCannon(
            int cx, int cy, int radius,
            int jumpX, int jumpY,
            float speed
    ) {
        PointF last = null;

        for (int attempt = 0; attempt < 10 && canControlRoblox(); attempt++) {
            if (!tapAt(jumpX, jumpY, 65L)) return null;
            if (!moveVector(cx, cy, radius, 1f, 0f, 2.0, speed)) return null;
            if (!moveVector(cx, cy, radius, 1f, -1f, 1.5, speed)) return null;

            for (int probe = 0; probe < 5 && canControlRoblox(); probe++) {
                Bitmap shot = screenshotSync(1400L);
                if (shot != null) {
                    rememberScreenSize(shot);
                    last = findRedCannon(shot);
                    shot.recycle();

                    if (last != null) {
                        if (!sleepWhileRunning(200L)) return null;

                        Bitmap confirm = screenshotSync(1400L);
                        if (confirm != null) {
                            PointF c2 = findRedCannon(confirm);
                            confirm.recycle();
                            if (c2 != null) return c2;
                        }
                    }
                }

                if (!moveVector(cx, cy, radius, 1f, 0f, 0.65, speed)) {
                    return null;
                }
            }
        }
        return last;
    }

    private PointF findRedCannon(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int x0 = Math.round(w * 0.14f);
        int x1 = Math.round(w * 0.78f);
        int y0 = Math.round(h * 0.10f);
        int y1 = Math.round(h * 0.72f);

        long sumX = 0L;
        long sumY = 0L;
        int count = 0;

        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                int c = bitmap.getPixel(x, y);
                int r = Color.red(c);
                int g = Color.green(c);
                int b = Color.blue(c);

                if (r >= 125 && r - g >= 45 && r - b >= 35) {
                    sumX += x;
                    sumY += y;
                    count++;
                }
            }
        }

        int threshold = Math.max(18, (w * h) / 120000);
        if (count < threshold) return null;

        return new PointF(
                (float) sumX / count,
                (float) sumY / count
        );
    }

    private boolean tapCannonPrompt(PointF cannon) {
        int w = screenW;
        int h = screenH;
        if (w <= 0 || h <= 0) return false;

        if (!tapAt(Math.round(cannon.x), Math.round(cannon.y), 85L)) return false;
        if (!sleepWhileRunning(110L)) return false;

        if (!tapAt(
                Math.round(cannon.x),
                Math.round(Math.min(h - 1, cannon.y + h * 0.045f)),
                85L
        )) return false;

        if (!sleepWhileRunning(170L)) return false;

        return tapAt(
                Math.round(w * 0.50f),
                Math.round(h * 0.235f),
                85L
        );
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
                if (!sleepWhileRunning(120L)) break;
                continue;
            }

            for (PatternStep step : steps) {
                if (!canControlRoblox() || System.currentTimeMillis() >= deadline) {
                    return;
                }
                if (!moveVector(cx, cy, radius, step.x, step.y, step.tiles, speed)) {
                    return;
                }
            }
        }
    }

    private boolean moveVector(
            int cx, int cy, int radius,
            float x, float y,
            double tiles,
            float speed
    ) {
        if (tiles <= 0) return true;

        float len = (float) Math.sqrt(x * x + y * y);
        if (len < 0.001f) {
            return sleepWhileRunning(durationForTiles(tiles, speed));
        }

        return holdJoystickMs(
                cx, cy, radius,
                x / len,
                y / len,
                durationForTiles(tiles, speed)
        );
    }

    private boolean holdJoystickMs(
            int cx, int cy, int radius,
            float nx, float ny,
            long ms
    ) {
        float tx = cx + nx * radius;
        float ty = cy + ny * radius;
        return holdJoystickContinuously(cx, cy, tx, ty, Math.max(90L, ms));
    }

    private long durationForTiles(double tiles, float moveSpeed) {
        return Math.max(
                100L,
                Math.round((tiles * 4.0 / Math.max(1.0, moveSpeed)) * 1000.0)
        );
    }

    private boolean holdJoystickContinuously(
            float cx, float cy,
            float tx, float ty,
            long totalMs
    ) {
        if (!canControlRoblox()) return false;

        long dragMs = Math.min(INITIAL_DRAG_MS, Math.max(1L, totalMs));
        long remaining = Math.max(0L, totalMs - dragMs);
        boolean continued = remaining > 0L;

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        line(cx, cy, tx, ty),
                        0L,
                        dragMs,
                        continued
                );

        if (!dispatchAndWaitRobloxOnly(stroke, dragMs + 450L)) {
            return false;
        }

        while (running.get() && remaining > 0L) {
            if (!canControlRoblox()) return false;

            long chunk = Math.min(HOLD_SLICE_MS, remaining);
            remaining -= chunk;
            boolean willContinue = remaining > 0L;

            try {
                stroke = stroke.continueStroke(
                        holdPath(tx, ty),
                        0L,
                        Math.max(1L, chunk),
                        willContinue
                );
            } catch (IllegalStateException | IllegalArgumentException e) {
                return holdJoystickFallback(cx, cy, tx, ty, chunk + remaining);
            }

            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 500L)) {
                return false;
            }
        }

        return running.get();
    }

    private boolean holdJoystickFallback(
            float cx, float cy,
            float tx, float ty,
            long totalMs
    ) {
        long remaining = totalMs;

        while (running.get() && remaining > 0L) {
            if (!canControlRoblox()) return false;

            long chunk = Math.min(1400L, remaining);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(
                            heldFallbackPath(cx, cy, tx, ty),
                            0L,
                            Math.max(120L, chunk),
                            false
                    );

            if (!dispatchAndWaitRobloxOnly(stroke, chunk + 500L)) {
                return false;
            }
            remaining -= chunk;
        }

        return running.get();
    }

    private Path heldFallbackPath(float cx, float cy, float tx, float ty) {
        Path p = new Path();
        p.moveTo(cx, cy);
        p.lineTo(tx, ty);

        for (int i = 0; i < 220; i++) {
            p.lineTo(tx + 0.6f, ty);
            p.lineTo(tx, ty);
        }
        return p;
    }

    private boolean tapAt(int x, int y, long durationMs) {
        if (!canControlRoblox()) return false;

        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 0.2f, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        p,
                        0L,
                        Math.max(20L, durationMs),
                        false
                );

        return dispatchAndWaitRobloxOnly(stroke, durationMs + 450L);
    }

    private boolean swipe(
            float x1, float y1,
            float x2, float y2,
            long durationMs
    ) {
        if (!canControlRoblox()) return false;

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        line(x1, y1, x2, y2),
                        0L,
                        Math.max(60L, durationMs),
                        false
                );

        return dispatchAndWaitRobloxOnly(stroke, durationMs + 500L);
    }

    private boolean rotateCameraRightSteps(int steps) {
        if (!primeScreenSize()) return false;

        for (int i = 0; i < steps; i++) {
            if (!swipe(
                    screenW * 0.77f, screenH * 0.48f,
                    screenW * 0.63f, screenH * 0.48f,
                    150L
            )) return false;
            if (!sleepWhileRunning(80L)) return false;
        }
        return true;
    }

    private boolean rotateCameraLeftSteps(int steps) {
        if (!primeScreenSize()) return false;

        for (int i = 0; i < steps; i++) {
            if (!swipe(
                    screenW * 0.63f, screenH * 0.48f,
                    screenW * 0.77f, screenH * 0.48f,
                    150L
            )) return false;
            if (!sleepWhileRunning(80L)) return false;
        }
        return true;
    }

    private boolean cameraPitch(float amount) {
        if (!primeScreenSize()) return false;

        float dy = screenH * amount;
        return swipe(
                screenW * 0.70f, screenH * 0.50f,
                screenW * 0.70f, screenH * 0.50f + dy,
                160L
        );
    }

    private boolean dispatchAndWaitRobloxOnly(
            GestureDescription.StrokeDescription stroke,
            long timeoutMs
    ) {
        if (!canControlRoblox()) return false;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        boolean accepted = dispatchGesture(
                gesture,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        completed.set(true);
                        latch.countDown();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        latch.countDown();
                    }
                },
                main
        );

        if (!accepted) {
            showToast("Android rejected a Natro gesture.");
            return false;
        }

        boolean signalled = false;
        try {
            signalled = latch.await(
                    Math.max(250L, timeoutMs),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        // Samsung can report continued-gesture completion late. Do not kill
        // the route only because the callback missed this timeout.
        if (!signalled) {
            return canControlRoblox();
        }

        if (!completed.get()) {
            return false;
        }

        return canControlRoblox();
    }

    private boolean canControlRoblox() {
        return running.get()
                && robloxSessionActive
                && isRobloxInteractiveWindow();
    }

    boolean isRobloxForeground() {
        return isRobloxInteractiveWindow();
    }

    private boolean waitForRobloxForeground() {
        while (running.get()) {
            if (isRobloxInteractiveWindow()) return true;
            if (!sleepWhileRunning(100L)) return false;
        }
        return false;
    }

    private boolean isRobloxInteractiveWindow() {
        String interactive = packageFromInteractiveWindows();
        if (!interactive.isEmpty()) {
            rememberForeground(interactive);
            return ROBLOX_PACKAGE.equals(interactive);
        }

        String root = packageFromActiveRoot();
        if (!root.isEmpty() && !getPackageName().equals(root)) {
            rememberForeground(root);
            return ROBLOX_PACKAGE.equals(root);
        }

        return ROBLOX_PACKAGE.equals(foregroundPackage)
                && SystemClock.uptimeMillis() - lastRobloxSeenAt < 1200L;
    }

    private void refreshForegroundPackage() {
        String interactive = packageFromInteractiveWindows();
        if (!interactive.isEmpty()) {
            rememberForeground(interactive);
            return;
        }

        String root = packageFromActiveRoot();
        if (!root.isEmpty() && !getPackageName().equals(root)) {
            rememberForeground(root);
        }
    }

    private void rememberForeground(String pkg) {
        if (pkg == null || pkg.isEmpty() || getPackageName().equals(pkg)) return;
        foregroundPackage = pkg;

        if (ROBLOX_PACKAGE.equals(pkg)) {
            lastRobloxSeenAt = SystemClock.uptimeMillis();
        }
    }

    private String packageFromInteractiveWindows() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null || windows.isEmpty()) return "";

            String fallback = "";

            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                if (!window.isActive() && !window.isFocused()) continue;

                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    if (root == null || root.getPackageName() == null) continue;

                    String pkg = root.getPackageName().toString();
                    if (getPackageName().equals(pkg)) continue;

                    if (ROBLOX_PACKAGE.equals(pkg)) {
                        return ROBLOX_PACKAGE;
                    }

                    if (fallback.isEmpty()) {
                        fallback = pkg;
                    }
                } catch (Exception ignored) {
                } finally {
                    if (root != null) {
                        try { root.recycle(); } catch (Exception ignored) {}
                    }
                }
            }

            return fallback;
        } catch (Exception ignored) {
            return "";
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

    private boolean primeScreenSize() {
        if (screenW > 0 && screenH > 0) return true;

        Bitmap bitmap = screenshotSync(1600L);
        if (bitmap == null) return false;

        rememberScreenSize(bitmap);
        bitmap.recycle();
        return screenW > 0 && screenH > 0;
    }

    private void rememberScreenSize(Bitmap bitmap) {
        screenW = bitmap.getWidth();
        screenH = bitmap.getHeight();
    }

    private Bitmap screenshotSync(long timeoutMs) {
        if (!canControlRoblox()) return null;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>(null);

        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = null;
                            try {
                                buffer = result.getHardwareBuffer();
                                Bitmap wrapped = Bitmap.wrapHardwareBuffer(
                                        buffer,
                                        result.getColorSpace()
                                );
                                if (wrapped != null) {
                                    out.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (buffer != null) {
                                    try { buffer.close(); } catch (Exception ignored) {}
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    }
            );
        } catch (Exception e) {
            return null;
        }

        try {
            latch.await(
                    Math.max(300L, timeoutMs),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        return out.get();
    }

    private void cancelActiveJoystickGesture() {
        if (!isRobloxInteractiveWindow()) return;

        int cx = activeJoyX;
        int cy = activeJoyY;
        if (cx < 0 || cy < 0) return;

        main.post(() -> {
            if (!isRobloxInteractiveWindow()) return;

            Path p = new Path();
            p.moveTo(cx, cy);
            p.lineTo(cx + 1f, cy);

            GestureDescription.StrokeDescription neutral =
                    new GestureDescription.StrokeDescription(
                            p,
                            0L,
                            1L,
                            false
                    );

            try {
                dispatchGesture(
                        new GestureDescription.Builder()
                                .addStroke(neutral)
                                .build(),
                        null,
                        null
                );
            } catch (Exception ignored) {
            }
        });
    }

    private Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return p;
    }

    private Path holdPath(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        p.lineTo(x + 0.35f, y);
        p.lineTo(x, y);
        return p;
    }

    private boolean sleepWhileRunning(long ms) {
        long end = SystemClock.uptimeMillis() + Math.max(0L, ms);

        while (running.get() && SystemClock.uptimeMillis() < end) {
            long left = end - SystemClock.uptimeMillis();
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return running.get();
    }

    private void setState(String state) {
        ControlOverlayService.setState(state);
    }

    private void showToast(String text) {
        main.post(() ->
                Toast.makeText(
                        getApplicationContext(),
                        text,
                        Toast.LENGTH_LONG
                ).show()
        );
    }

    private void stopOverlay() {
        try {
            stopService(new Intent(this, ControlOverlayService.class));
        } catch (Exception ignored) {
        }
    }
}
