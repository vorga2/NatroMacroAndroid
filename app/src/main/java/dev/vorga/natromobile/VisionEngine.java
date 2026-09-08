package dev.vorga.natromobile;

import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * Mobile visual-anchor backend. It intentionally works on user-taught Android
 * crops instead of desktop Natro bitmaps, so UI scale/aspect ratio differences
 * do not invalidate the route.
 */
final class VisionEngine {
    interface FrameSource { Bitmap capture() throws Exception; }

    private final ScreenAnchors anchors;
    private final FrameSource source;
    private final MovementEngine movement;
    private long lastCapture;

    VisionEngine(ScreenAnchors anchors, FrameSource source, MovementEngine movement) {
        this.anchors=anchors;
        this.source=source;
        this.movement=movement;
    }

    boolean exists(String name) { return anchors.exists(name); }

    boolean seen(String name,int frames) throws Exception {
        int need=Math.max(1,frames);
        for(int i=0;i<need;i++) {
            throttle();
            Bitmap bitmap=source.capture();
            if(bitmap==null) throw new IllegalStateException("Не удалось получить снимок Roblox");
            boolean ok;
            try { ok=anchors.matches(name,bitmap); }
            finally { bitmap.recycle(); }
            lastCapture=SystemClock.uptimeMillis();
            if(!ok) return false;
        }
        return true;
    }

    boolean waitFor(String name,long timeoutMs,int consecutive) throws Exception {
        long end=SystemClock.uptimeMillis()+Math.max(0,timeoutMs);
        int matched=0;
        while(SystemClock.uptimeMillis()<=end) {
            throttle();
            Bitmap bitmap=source.capture();
            if(bitmap==null) throw new IllegalStateException("Не удалось получить снимок Roblox");
            boolean ok;
            try { ok=anchors.matches(name,bitmap); }
            finally { bitmap.recycle(); }
            lastCapture=SystemClock.uptimeMillis();
            matched=ok?matched+1:0;
            if(matched>=Math.max(1,consecutive)) return true;
            if(SystemClock.uptimeMillis()>=end) break;
            movement.waitFor(120);
        }
        return false;
    }

    private void throttle() throws InterruptedException {
        long since=SystemClock.uptimeMillis()-lastCapture;
        if(since<300) movement.waitFor(300-since);
    }
}
