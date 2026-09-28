package dev.vorga.natromobile;

import android.os.SystemClock;

/**
 * Safety watchdog for the macro worker loop.
 *
 * Every long-running route phase is already bounded by retries/timeouts,
 * but a pathological stall (dead callback queue, frozen worker, etc.) used
 * to leave the macro "running" forever with no gestures being sent. The
 * watchdog trips when no measurable progress happens for TRIP_MS and runs
 * the trip callback, which stops the macro.
 *
 * Progress sources (see MacroAccessibilityService.markProgress):
 *  - every successful gesture dispatch,
 *  - every successful screenshot,
 *  - every route state change.
 *
 * During normal gathering these fire many times per minute, so the
 * 10-minute trip window is far beyond any legitimate quiet period.
 */
final class MacroWatchdog {
    static final long TRIP_MS = 10 * 60 * 1000L;
    private static final long CHECK_MS = 15 * 1000L;

    private final Runnable onTrip;
    private volatile long lastProgressAt;
    private volatile boolean armed;
    private Thread thread;

    MacroWatchdog(Runnable onTrip) {
        this.onTrip = onTrip;
    }

    synchronized void start() {
        armed = true;
        progress();
        if (thread != null) return;
        thread = new Thread(this::loop, "macro-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    synchronized void stop() {
        armed = false;
    }

    void progress() {
        lastProgressAt = SystemClock.elapsedRealtime();
    }

    private void loop() {
        while (armed) {
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!armed) return;
            long stuckFor = SystemClock.elapsedRealtime() - lastProgressAt;
            if (stuckFor >= TRIP_MS) {
                armed = false;
                SessionLog.e("WATCHDOG trip: no progress for " + (stuckFor / 1000L) + "s, stopping macro");
                try {
                    onTrip.run();
                } catch (Exception ignored) {}
                return;
            }
        }
    }
}
