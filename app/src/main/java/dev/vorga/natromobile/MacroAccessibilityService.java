package dev.vorga.natromobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class MacroAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private static final long HOLD_SLICE_MS = 1500L;
    private static final long INITIAL_DRAG_MS = 70L;
    private static volatile MacroAccessibilityService instance;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean robloxSessionActive;
    private volatile long lastRobloxSeenAt;
    private volatile int screenW;
    private volatile int screenH;
    private volatile int activeJoyX = -1;
    private volatile int activeJoyY = -1;

    private enum SpawnLocation { HIVE, SPAWN, UNKNOWN }

    private static final class ScanResult {
        final SpawnLocation location;
        final int hiveScore;
        final int spawnScore;
        ScanResult(SpawnLocation location, int hiveScore, int spawnScore) {
            this.location = location;
            this.hiveScore = hiveScore;
            this.spawnScore = spawnScore;
        }
    }

    static MacroAccessibilityService get() { return instance; }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        if (hasRobloxWindow()) lastRobloxSeenAt = SystemClock.uptimeMillis();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null
                && ROBLOX_PACKAGE.contentEquals(event.getPackageName())) {
            lastRobloxSeenAt = SystemClock.uptimeMillis();
        }
        // Never kill the route from one OneUI/SystemUI accessibility event.
        // Every actual touch is still gated by a visible Roblox window.
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
            toast("Natro Mobile is already running.");
            return;
        }
        robloxSessionActive = false;
        worker.execute(() -> {
            try {
                state("ARMED — WAIT ROBLOX");
                if (!waitForRoblox(30_000L)) {
                    fail("ROBLOX NOT FOUND");
                    return;
                }
                robloxSessionActive = true;
                if (!sleepRunning(Math.max(0L, delayMs))) return;
                if (!waitForRoblox(5000L)) {
                    fail("ROBLOX LOST BEFORE START");
                    return;
                }
                toast("Natro: reset → hive → Pine Tree");
                runPineTreeRoute();
            } finally {
                running.set(false);
                robloxSessionActive = false;
                state("STOPPED");
                stopOverlay();
            }
        });
    }

    void stopMacro() {
        boolean wasRunning = running.getAndSet(false);
        robloxSessionActive = false;
        if (wasRunning && hasRobloxWindow()) cancelActiveJoystickGesture();
        state("STOPPED");
    }

    boolean isRunningMacro() { return running.get(); }
    boolean isRobloxForeground() { return hasRobloxWindow(); }

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
            fail("CALIBRATE CONTROLS");
            return;
        }
        activeJoyX = cx;
        activeJoyY = cy;

        if (!resetUntilHive()) return;

        state("HIVE → RAMP");
        if (!moveVector(cx, cy, radius, 0f, -1f, 5.0, speed)) {
            fail("MOVE TO RAMP FAILED");
            return;
        }
        if (!moveVector(cx, cy, radius, 1f, 0f, 9.2 * hiveSlot - 4.0, speed)) {
            fail("RAMP ALIGN FAILED");
            return;
        }

        state("FIND CANNON");
        PointF cannon = gotoRedCannon(cx, cy, radius, jumpX, jumpY, speed);
        if (cannon == null) {
            fail("CANNON NOT FOUND");
            return;
        }

        state("USE CANNON");
        if (!tapCannonPrompt(cannon)) {
            fail("CANNON TAP FAILED");
            return;
        }

        // Original Natro Pine cannon timing translated to the mobile joystick.
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 925L)) return;
        if (!tapAt(jumpX, jumpY, 65L)) return;
        if (!sleepRunning(80L)) return;
        if (!tapAt(jumpX, jumpY, 65L)) return;

        state("GLIDER → PINE");
        if (!holdJoystickMs(cx, cy, radius, 1f, 1f, 4500L)) return;
        if (!holdJoystickMs(cx, cy, radius, 1f, 0f, 500L)) return;
        if (!tapAt(jumpX, jumpY, 70L)) return;
        if (!rotateCameraLeftSteps(4)) return;
        if (!sleepRunning(1700L)) return;

        state("PINE: GATHER");
        runConfiguredPatternLoop(cx, cy, radius, speed);
    }

    private boolean resetUntilHive() {
        for (int attempt = 1; attempt <= 3 && running.get(); attempt++) {
            state("RESET " + attempt + "/3");
            if (!waitForRoblox(5000L)) return fail("ROBLOX LOST BEFORE RESET");
            if (!respawnViaRobloxMenu()) return fail("RESET BUTTON FAILED");

            state("WAIT RESPAWN");
            // Respawn can temporarily recreate the Roblox window. Do not treat that as leaving Roblox.
            if (!sleepRunning(3500L)) return false;
            if (!waitForRoblox(10_000L)) return fail("ROBLOX DID NOT RETURN");
            if (!sleepRunning(1400L)) return false;

            state("CHECK HIVE / SPAWN");
            ScanResult first = scanRespawnLocation(true);
            if (first.location == SpawnLocation.HIVE) {
                state("HIVE RESPAWN OK");
                return sleepRunning(350L);
            }
            if (first.location == SpawnLocation.SPAWN) {
                state("SPAWN → RESET AGAIN");
                toast("Normal spawn detected — reset again.");
                if (!sleepRunning(700L)) return false;
                continue;
            }

            // Mobile rendering/lighting often prevents the exact desktop Natro
            // gold strip from matching. Rescan once at a second camera pitch.
            state("HIVE RESCAN");
            if (!cameraPitch(0.10f)) return false;
            if (!sleepRunning(250L)) return false;
            ScanResult second = scanRespawnLocation(false);
            if (second.location == SpawnLocation.SPAWN) {
                state("SPAWN → RESET AGAIN");
                if (!sleepRunning(700L)) return false;
                continue;
            }
            if (second.location == SpawnLocation.HIVE) {
                state("HIVE RESPAWN OK");
                return sleepRunning(350L);
            }

            // We KNOW a reset just succeeded. At this point the useful distinction
            // is hive vs normal spawn. If two scans did not see the normal spawn,
            // do not stop a real hive respawn merely because the mobile colors differ.
            if (first.spawnScore < 22 && second.spawnScore < 22) {
                state("HIVE RESPAWN — NO SPAWN DETECTED");
                toast("Hive respawn accepted; normal spawn was not detected.");
                return sleepRunning(450L);
            }

            state("UNCERTAIN → RESET AGAIN");
            if (!sleepRunning(650L)) return false;
        }
        return fail("NO HIVE AFTER 3 RESETS");
    }

    private ScanResult scanRespawnLocation(boolean alignWhenHive) {
        int bestHive = 0;
        int bestSpawn = 0;
        boolean sawHive = false;
        boolean sawSpawn = false;

        for (int yaw = 0; yaw < 6 && running.get(); yaw++) {
            if (!waitForRoblox(2500L)) return new ScanResult(SpawnLocation.UNKNOWN, bestHive, bestSpawn);
            Bitmap shot = screenshotSync(1800L);
            if (shot != null) {
                rememberScreenSize(shot);
                int hive = hiveScore(shot);
                int spawn = spawnScore(shot);
                bestHive = Math.max(bestHive, hive);
                bestSpawn = Math.max(bestSpawn, spawn);
                shot.recycle();

                if (hive >= 36 && hive >= spawn + 8) {
                    sawHive = true;
                    if (alignWhenHive) {
                        state("HIVE FOUND — ALIGN");
                        // Face away from the hive toward the ramp, same intent as desktop Natro.
                        if (!rotateCameraRightSteps(4)) return new ScanResult(SpawnLocation.UNKNOWN, bestHive, bestSpawn);
                    }
                    break;
                }
                if (spawn >= 38 && spawn >= hive + 10) sawSpawn = true;
            }
            if (!rotateCameraRightSteps(1)) break;
            if (!sleepRunning(120L)) break;
        }

        if (sawHive || (bestHive >= 32 && bestHive >= bestSpawn + 7))
            return new ScanResult(SpawnLocation.HIVE, bestHive, bestSpawn);
        if (sawSpawn || (bestSpawn >= 35 && bestSpawn >= bestHive + 8))
            return new ScanResult(SpawnLocation.SPAWN, bestHive, bestSpawn);
        return new ScanResult(SpawnLocation.UNKNOWN, bestHive, bestSpawn);
    }

    private int hiveScore(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = Math.max(0, (int)(w * 0.03f));
        int x1 = Math.min(w, (int)(w * 0.97f));
        int y0 = Math.max(0, (int)(h * 0.38f));
        int y1 = Math.min(h, (int)(h * 0.98f));
        int bestRun = 0, count = 0;
        for (int y = y0; y < y1; y += 3) {
            int run = 0;
            for (int x = x0; x < x1; x += 2) {
                int c = b.getPixel(x, y);
                if (isHiveGold(c)) {
                    run += 2; count++;
                    bestRun = Math.max(bestRun, run);
                } else run = 0;
            }
        }
        return bestRun + Math.min(30, count / Math.max(1, w / 80));
    }

    private int spawnScore(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = Math.max(0, (int)(w * 0.03f));
        int x1 = Math.min(w, (int)(w * 0.97f));
        int y0 = Math.max(0, (int)(h * 0.38f));
        int y1 = Math.min(h, (int)(h * 0.98f));
        int bestRun = 0, count = 0;
        for (int y = y0; y < y1; y += 3) {
            int run = 0;
            for (int x = x0; x < x1; x += 2) {
                int c = b.getPixel(x, y);
                if (isSpawnOlive(c)) {
                    run += 2; count++;
                    bestRun = Math.max(bestRun, run);
                } else run = 0;
            }
        }
        return bestRun + Math.min(30, count / Math.max(1, w / 80));
    }

    private boolean isHiveGold(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        // Covers original Natro hive colors plus mobile lighting/AA variants.
        return r >= 105 && g >= 65 && g <= 190 && b <= 105
                && r - g >= 18 && g - b >= 22;
    }

    private boolean isSpawnOlive(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        // Original Natro spawn palette is more olive/grey than hive orange.
        return r >= 90 && g >= 75 && b >= 15 && b <= 145
                && Math.abs(r - g) <= 55 && g - b >= 12;
    }

    private boolean respawnViaRobloxMenu() {
        if (!primeScreenSize()) return false;
        int w = screenW, h = screenH;

        state("OPEN ROBLOX MENU");
        boolean opened = clickRobloxNode("menu", "меню");
        if (!opened) {
            opened = tapAt(Math.max(36, Math.round(w * 0.055f)), Math.max(34, Math.round(h * 0.060f)), 80L);
        }
        if (!opened || !sleepRunning(700L)) return false;

        state("PRESS RESPAWN");
        boolean respawn = clickRobloxNode(
                "respawn", "respawn character", "reset character",
                "возродиться", "переродиться", "возрождение", "сбросить персонажа"
        );
        if (!respawn) {
            // Common current mobile Roblox menu layout.
            respawn = tapAt(Math.round(w * 0.50f), Math.round(h * 0.865f), 90L);
        }
        if (!respawn || !sleepRunning(650L)) return false;

        state("CONFIRM RESET");
        boolean confirm = clickRobloxNode(
                "reset", "respawn", "reset character",
                "сбросить", "возродиться", "переродиться"
        );
        if (!confirm) {
            // Try both common confirmation button positions, still strictly inside Roblox.
            confirm = tapAt(Math.round(w * 0.425f), Math.round(h * 0.665f), 90L);
            if (confirm) sleepRunning(160L);
        }
        return confirm;
    }

    private boolean clickRobloxNode(String... needles) {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows == null) return false;
            for (AccessibilityWindowInfo win : windows) {
                if (win == null) continue;
                AccessibilityNodeInfo root = null;
                try {
                    root = win.getRoot();
                    if (root == null || root.getPackageName() == null
                            || !ROBLOX_PACKAGE.equals(root.getPackageName().toString())) continue;
                    if (clickMatchingNode(root, needles)) return true;
                } finally {
                    if (root != null) try { root.recycle(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && ROBLOX_PACKAGE.equals(root.getPackageName().toString())) {
                return clickMatchingNode(root, needles);
            }
        } catch (Exception ignored) {
        } finally {
            if (root != null) try { root.recycle(); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean clickMatchingNode(AccessibilityNodeInfo root, String... needles) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo node = q.removeFirst();
            String text = node.getText() == null ? "" : node.getText().toString().toLowerCase(Locale.ROOT);
            String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString().toLowerCase(Locale.ROOT);
            boolean match = false;
            for (String n : needles) {
                String needle = n.toLowerCase(Locale.ROOT);
                if (text.contains(needle) || desc.contains(needle)) { match = true; break; }
            }
            if (match) {
                AccessibilityNodeInfo cur = node;
                for (int depth = 0; cur != null && depth < 6; depth++) {
                    if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                    cur = cur.getParent();
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) q.add(child);
            }
        }
        return false;
    }

    private PointF gotoRedCannon(int cx, int cy, int radius, int jumpX, int jumpY, float speed) {
        PointF last = null;
        for (int attempt = 0; attempt < 10 && running.get(); attempt++) {
            if (!waitForRoblox(2500L)) return null;
            if (!tapAt(jumpX, jumpY, 65L)) return null;
            if (!moveVector(cx, cy, radius, 1f, 0f, 2.0, speed)) return null;
            if (!moveVector(cx, cy, radius, 1f, -1f, 1.5, speed)) return null;
            for (int probe = 0; probe < 5 && running.get(); probe++) {
                Bitmap shot = screenshotSync(1500L);
                if (shot != null) {
                    rememberScreenSize(shot);
                    last = findRedCannon(shot);
                    shot.recycle();
                    if (last != null) return last;
                }
                if (!moveVector(cx, cy, radius, 1f, 0f, 0.65, speed)) return null;
            }
        }
        return last;
    }

    private PointF findRedCannon(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int x0 = Math.round(w * 0.10f), x1 = Math.round(w * 0.82f);
        int y0 = Math.round(h * 0.08f), y1 = Math.round(h * 0.72f);
        long sx = 0, sy = 0; int count = 0;
        for (int y = y0; y < y1; y += 4) {
            for (int x = x0; x < x1; x += 4) {
                int c = b.getPixel(x, y);
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                if (r >= 125 && r - g >= 45 && r - bl >= 35) {
                    sx += x; sy += y; count++;
                }
            }
        }
        int threshold = Math.max(18, (w * h) / 120000);
        return count < threshold ? null : new PointF((float)sx / count, (float)sy / count);
    }

    private boolean tapCannonPrompt(PointF cannon) {
        if (screenW <= 0 || screenH <= 0) return false;
        if (!tapAt(Math.round(cannon.x), Math.round(cannon.y), 85L)) return false;
        if (!sleepRunning(120L)) return false;
        if (!tapAt(Math.round(cannon.x), Math.round(Math.min(screenH - 1, cannon.y + screenH * 0.045f)), 85L)) return false;
        if (!sleepRunning(180L)) return false;
        return tapAt(Math.round(screenW * 0.50f), Math.round(screenH * 0.235f), 85L);
    }

    private void runConfiguredPatternLoop(int cx, int cy, int radius, float speed) {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        float size = p.getFloat("pattern_size", 1f);
        int reps = p.getInt("pattern_reps", 3);
        int runSeconds = p.getInt("run_seconds", 900);
        String pattern = p.getString("pattern", "Snake");
        List<PatternStep> steps = PatternFactory.create(pattern, size, reps);
        long end = System.currentTimeMillis() + Math.max(10, runSeconds) * 1000L;
        while (running.get() && System.currentTimeMillis() < end) {
            if (!waitForRoblox(2500L)) { fail("ROBLOX LOST WHILE GATHERING"); return; }
            if (steps.isEmpty()) { if (!sleepRunning(120L)) return; continue; }
            for (PatternStep step : steps) {
                if (!running.get() || System.currentTimeMillis() >= end) return;
                if (!moveVector(cx, cy, radius, step.x, step.y, step.tiles, speed)) return;
            }
        }
    }

    private boolean moveVector(int cx, int cy, int radius, float x, float y, double tiles, float speed) {
        if (tiles <= 0) return true;
        float len = (float)Math.sqrt(x * x + y * y);
        if (len < 0.001f) return sleepRunning(durationForTiles(tiles, speed));
        return holdJoystickMs(cx, cy, radius, x / len, y / len, durationForTiles(tiles, speed));
    }

    private long durationForTiles(double tiles, float speed) {
        return Math.max(100L, Math.round((tiles * 4.0 / Math.max(1.0, speed)) * 1000.0));
    }

    private boolean holdJoystickMs(int cx, int cy, int radius, float nx, float ny, long ms) {
        return holdJoystickContinuously(cx, cy, cx + nx * radius, cy + ny * radius, Math.max(90L, ms));
    }

    private boolean holdJoystickContinuously(float cx, float cy, float tx, float ty, long totalMs) {
        if (!waitForRoblox(2000L)) return false;
        long dragMs = Math.min(INITIAL_DRAG_MS, Math.max(1L, totalMs));
        long remaining = Math.max(0L, totalMs - dragMs);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                line(cx, cy, tx, ty), 0, dragMs, remaining > 0);
        if (!dispatchAndWait(stroke, dragMs + 500L)) return false;

        while (running.get() && remaining > 0) {
            if (!waitForRoblox(2000L)) return false;
            long chunk = Math.min(HOLD_SLICE_MS, remaining);
            remaining -= chunk;
            try {
                stroke = stroke.continueStroke(holdPath(tx, ty), 0, Math.max(1L, chunk), remaining > 0);
            } catch (Exception e) {
                return holdJoystickFallback(cx, cy, tx, ty, chunk + remaining);
            }
            if (!dispatchAndWait(stroke, chunk + 550L)) return false;
        }
        return running.get();
    }

    private boolean holdJoystickFallback(float cx, float cy, float tx, float ty, long totalMs) {
        long left = totalMs;
        while (running.get() && left > 0) {
            if (!waitForRoblox(2000L)) return false;
            long chunk = Math.min(1300L, left);
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                    fallbackPath(cx, cy, tx, ty), 0, Math.max(120L, chunk), false);
            if (!dispatchAndWait(stroke, chunk + 550L)) return false;
            left -= chunk;
        }
        return running.get();
    }

    private boolean tapAt(int x, int y, long ms) {
        if (!waitForRoblox(2000L)) return false;
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 0.2f, y);
        return dispatchAndWait(new GestureDescription.StrokeDescription(p, 0, Math.max(20L, ms), false), ms + 500L);
    }

    private boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        if (!waitForRoblox(2000L)) return false;
        return dispatchAndWait(new GestureDescription.StrokeDescription(line(x1, y1, x2, y2), 0, Math.max(60L, ms), false), ms + 550L);
    }

    private boolean rotateCameraRightSteps(int steps) {
        if (!primeScreenSize()) return false;
        for (int i = 0; i < steps; i++) {
            if (!swipe(screenW * 0.77f, screenH * 0.48f, screenW * 0.63f, screenH * 0.48f, 145L)) return false;
            if (!sleepRunning(75L)) return false;
        }
        return true;
    }

    private boolean rotateCameraLeftSteps(int steps) {
        if (!primeScreenSize()) return false;
        for (int i = 0; i < steps; i++) {
            if (!swipe(screenW * 0.63f, screenH * 0.48f, screenW * 0.77f, screenH * 0.48f, 145L)) return false;
            if (!sleepRunning(75L)) return false;
        }
        return true;
    }

    private boolean cameraPitch(float amount) {
        if (!primeScreenSize()) return false;
        float dy = screenH * amount;
        return swipe(screenW * 0.70f, screenH * 0.50f, screenW * 0.70f, screenH * 0.50f + dy, 160L);
    }

    private boolean dispatchAndWait(GestureDescription.StrokeDescription stroke, long timeoutMs) {
        if (!running.get() || !hasRobloxWindow()) return false;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { completed.set(true); latch.countDown(); }
            @Override public void onCancelled(GestureDescription d) { latch.countDown(); }
        }, main);
        if (!accepted) return false;
        try {
            boolean signalled = latch.await(Math.max(250L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!signalled) return running.get() && hasRobloxWindow();
            return completed.get() && running.get() && hasRobloxWindow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean hasRobloxWindow() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                for (AccessibilityWindowInfo win : windows) {
                    if (win == null) continue;
                    AccessibilityNodeInfo root = null;
                    try {
                        root = win.getRoot();
                        if (root != null && root.getPackageName() != null
                                && ROBLOX_PACKAGE.equals(root.getPackageName().toString())) {
                            lastRobloxSeenAt = SystemClock.uptimeMillis();
                            return true;
                        }
                    } finally {
                        if (root != null) try { root.recycle(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && ROBLOX_PACKAGE.equals(root.getPackageName().toString())) {
                lastRobloxSeenAt = SystemClock.uptimeMillis();
                return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (root != null) try { root.recycle(); } catch (Exception ignored) {}
        }
        return SystemClock.uptimeMillis() - lastRobloxSeenAt < 1800L;
    }

    private boolean waitForRoblox(long timeoutMs) {
        long end = SystemClock.uptimeMillis() + Math.max(100L, timeoutMs);
        while (running.get() && SystemClock.uptimeMillis() < end) {
            if (hasRobloxWindow()) return true;
            if (!sleepRunning(80L)) return false;
        }
        return false;
    }

    private boolean primeScreenSize() {
        if (screenW > 0 && screenH > 0) return true;
        Bitmap b = screenshotSync(1600L);
        if (b == null) return false;
        rememberScreenSize(b); b.recycle();
        return screenW > 0 && screenH > 0;
    }

    private void rememberScreenSize(Bitmap b) { screenW = b.getWidth(); screenH = b.getHeight(); }

    private Bitmap screenshotSync(long timeoutMs) {
        if (!running.get() || !hasRobloxWindow()) return null;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = null;
                    try {
                        buffer = result.getHardwareBuffer();
                        Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (wrapped != null) out.set(wrapped.copy(Bitmap.Config.ARGB_8888, false));
                    } catch (Exception ignored) {
                    } finally {
                        if (buffer != null) try { buffer.close(); } catch (Exception ignored) {}
                        latch.countDown();
                    }
                }
                @Override public void onFailure(int errorCode) { latch.countDown(); }
            });
            latch.await(Math.max(300L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
        return out.get();
    }

    private void cancelActiveJoystickGesture() {
        int x = activeJoyX, y = activeJoyY;
        if (x < 0 || y < 0 || !hasRobloxWindow()) return;
        main.post(() -> {
            Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 1, y);
            try {
                dispatchGesture(new GestureDescription.Builder().addStroke(
                        new GestureDescription.StrokeDescription(p, 0, 1, false)).build(), null, null);
            } catch (Exception ignored) {}
        });
    }

    private Path line(float x1, float y1, float x2, float y2) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2); return p;
    }

    private Path holdPath(float x, float y) {
        Path p = new Path(); p.moveTo(x, y); p.lineTo(x + 0.35f, y); p.lineTo(x, y); return p;
    }

    private Path fallbackPath(float cx, float cy, float tx, float ty) {
        Path p = new Path(); p.moveTo(cx, cy); p.lineTo(tx, ty);
        for (int i = 0; i < 180; i++) { p.lineTo(tx + 0.5f, ty); p.lineTo(tx, ty); }
        return p;
    }

    private boolean sleepRunning(long ms) {
        long end = SystemClock.uptimeMillis() + Math.max(0L, ms);
        while (running.get() && SystemClock.uptimeMillis() < end) {
            try { Thread.sleep(Math.min(50L, Math.max(1L, end - SystemClock.uptimeMillis()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return running.get();
    }

    private boolean fail(String message) {
        state("ERROR — " + message);
        toast(message);
        sleepRunning(3200L);
        return false;
    }

    private void state(String s) { ControlOverlayService.setState(s); }
    private void toast(String s) { main.post(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show()); }
    private void stopOverlay() {
        try { stopService(new Intent(this, ControlOverlayService.class)); } catch (Exception ignored) {}
    }
}
