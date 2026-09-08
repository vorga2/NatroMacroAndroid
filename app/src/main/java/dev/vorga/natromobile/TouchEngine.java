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

/** A single worker owns every stroke; the main thread owns dispatch/callbacks. */
final class TouchEngine {
    interface Guard { boolean allowed(); }
    private final AccessibilityService service;
    private final Guard guard;
    private final Handler main=new Handler(Looper.getMainLooper());
    private GestureDescription.StrokeDescription joy,tool;
    private float toolX,toolY;
    private final MacroConfig config;
    private final int width,height;
    TouchEngine(AccessibilityService s,Guard g,MacroConfig c,int w,int h) { service=s;guard=g;config=c;width=w;height=h; }

    private Path line(float x,float y,float tx,float ty) {
        Path p=new Path();
        p.moveTo(x,y);
        p.lineTo(tx,ty);
        return p;
    }

    /**
     * GestureDescription rejects/ignores an empty path on some Android/Samsung builds.
     * A Path containing only moveTo() is effectively empty, so every tap gets a tiny
     * real segment while remaining visually/physically a tap.
     */
    private Path pressPath(float x,float y) {
        Path p=new Path();
        p.moveTo(x,y);
        float nx=(x+0.35f<width)?x+0.35f:Math.max(0f,x-0.35f);
        p.lineTo(nx,y);
        return p;
    }

    /**
     * Continuations must start exactly at the previous stroke endpoint. This tiny
     * out-and-back segment is non-empty and ends at the same coordinate, so the next
     * continueStroke() can safely begin at x,y again.
     */
    private Path holdPath(float x,float y) {
        Path p=new Path();
        p.moveTo(x,y);
        float nx=(x+0.35f<width)?x+0.35f:Math.max(0f,x-0.35f);
        p.lineTo(nx,y);
        p.lineTo(x,y);
        return p;
    }

    void move(float x,float y,long ms,boolean gather) throws InterruptedException {
        move(x,y,ms,gather,new long[0]);
    }

    // Jump offsets are relative to movement start. Both fingers share a GestureDescription.
    void move(float x,float y,long ms,boolean gather,long[] jumpAt) throws InterruptedException {
        check();
        float[] unit=MovementMath.unit(x,y);
        float cx=config.x("joy",width),cy=config.y("joy",height);
        float r=config.number("radius_n",0)*Math.min(width,height);
        float tx=cx+unit[0]*r,ty=cy+unit[1]*r;
        boolean moving=x!=0 || y!=0;
        if(moving && ControlOverlayService.obscuresPath(cx,cy,tx,ty))throw new IllegalStateException("Перетащи N • меню: оно перекрывает джойстик");
        if(gather && ControlOverlayService.obscures(config.x("tool",width),config.y("tool",height)))throw new IllegalStateException("Меню перекрывает кнопку сбора");
        if(jumpAt.length>0 && ControlOverlayService.obscures(config.x("jump",width),config.y("jump",height)))throw new IllegalStateException("Меню перекрывает кнопку прыжка");
        long elapsed=0;

        // Ramp is deliberately short and separate from the exact full-deflection hold.
        if(moving) {
            joy=new GestureDescription.StrokeDescription(line(cx,cy,tx,ty),0,16,true);
            send(new GestureDescription.Builder().addStroke(joy).build(),16);
        }

        while(elapsed<ms) {
            check();
            long chunk=Math.min(250,ms-elapsed);
            // Never cut a jump or tool tap across a chunk boundary.
            for(long at:jumpAt) if(at>=elapsed && at<elapsed+chunk) chunk=Math.max(chunk,at-elapsed+65);
            chunk=Math.min(chunk,ms-elapsed);
            boolean more=elapsed+chunk<ms;
            GestureDescription.Builder b=new GestureDescription.Builder();
            int strokeCount=0;

            if(moving) {
                joy=joy.continueStroke(holdPath(tx,ty),0,chunk,more);
                b.addStroke(joy);
                strokeCount++;
            }

            if(gather) {
                toolX=config.x("tool",width);
                toolY=config.y("tool",height);
                if(config.flag("tool_hold",true)) {
                    tool=tool==null
                            ? new GestureDescription.StrokeDescription(holdPath(toolX,toolY),0,chunk,more)
                            : tool.continueStroke(holdPath(toolX,toolY),0,chunk,more);
                    b.addStroke(tool);
                    strokeCount++;
                } else {
                    int interval=config.integer("tool_interval",250);
                    long first=((elapsed+interval-1)/interval)*interval;
                    for(long at=first;at<elapsed+chunk;at+=interval){
                        long tapDuration=Math.max(1,Math.min(45,elapsed+chunk-at));
                        b.addStroke(new GestureDescription.StrokeDescription(pressPath(toolX,toolY),at-elapsed,tapDuration,false));
                        strokeCount++;
                    }
                }
            }

            for(long at:jumpAt) if(at>=elapsed && at+65<=elapsed+chunk){
                b.addStroke(new GestureDescription.StrokeDescription(
                        pressPath(config.x("jump",width),config.y("jump",height)),at-elapsed,65,false));
                strokeCount++;
            }

            long chunkStart=SystemClock.uptimeMillis();
            if(strokeCount>0) send(b.build(),chunk);
            long rest=chunk-(SystemClock.uptimeMillis()-chunkStart);
            if(rest>0)waitFor(rest);
            elapsed+=chunk;
            if(!more) { joy=null;tool=null; }
        }
    }

    void tap(String key,long duration) throws InterruptedException {
        if(!config.point(key)) throw new IllegalStateException("Откалибруй кнопку: "+key);
        tap(config.x(key,width),config.y(key,height),duration);
    }

    void tap(float x,float y,long ms) throws InterruptedException {
        check();
        if(ControlOverlayService.obscures(x,y))throw new IllegalStateException("Перетащи N • меню: оно перекрывает кнопку");
        send(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(pressPath(x,y),0,Math.max(1,ms),false))
                .build(),Math.max(1,ms));
    }

    void swipe(float x,float y,float tx,float ty,long ms) throws InterruptedException {
        check();
        if(ControlOverlayService.obscuresPath(x,y,tx,ty))throw new IllegalStateException("Перетащи меню: оно перекрывает свайп камеры");
        send(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(line(x,y,tx,ty),0,ms,false))
                .build(),ms);
    }

    void waitFor(long ms) throws InterruptedException {
        long end=SystemClock.uptimeMillis()+ms;
        while(SystemClock.uptimeMillis()<end) {
            check();
            Thread.sleep(Math.min(40,Math.max(1,end-SystemClock.uptimeMillis())));
        }
    }

    private void check() throws InterruptedException {
        if(!guard.allowed()) throw new InterruptedException("Stopped or Roblox lost focus");
    }

    private void send(GestureDescription gesture,long duration) throws InterruptedException {
        check();
        CountDownLatch done=new CountDownLatch(1);
        AtomicBoolean complete=new AtomicBoolean();
        AtomicBoolean expired=new AtomicBoolean();

        main.post(()->{
            if(expired.get()||!guard.allowed()) {done.countDown();return;}
            boolean accepted=false;
            try {
                accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
                    @Override public void onCompleted(GestureDescription g) {complete.set(true);done.countDown();}
                    @Override public void onCancelled(GestureDescription g) {done.countDown();}
                },main);
            } catch(RuntimeException ignored) { }
            if(!accepted) done.countDown();
        });

        boolean signaled=done.await(duration+1800,TimeUnit.MILLISECONDS);
        expired.set(true);
        check();
        if(!signaled || !complete.get())
            throw new IllegalStateException(signaled
                    ? "Жест отменён Android. Не касайся игры во время выполнения и проверь службу специальных возможностей"
                    : "Android не подтвердил жест; макрос остановлен");
    }

    void release(boolean robloxVisible) {
        GestureDescription.StrokeDescription j=joy,t=tool;
        joy=null;
        tool=null;
        if(!robloxVisible || (j==null && t==null)) return;
        CountDownLatch done=new CountDownLatch(1);
        main.post(()->{
            try {
                GestureDescription.Builder b=new GestureDescription.Builder();
                // A new valid short gesture cancels any in-flight strokes. Only touch
                // the calibrated joystick, never the tool (which could trigger an action).
                b.addStroke(new GestureDescription.StrokeDescription(
                        pressPath(config.x("joy",width),config.y("joy",height)),0,1,false));
                service.dispatchGesture(b.build(),new AccessibilityService.GestureResultCallback(){
                    @Override public void onCompleted(GestureDescription g){done.countDown();}
                    @Override public void onCancelled(GestureDescription g){done.countDown();}
                },main);
            } catch(RuntimeException e) {
                done.countDown();
            }
        });
        try { done.await(800,TimeUnit.MILLISECONDS); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();}
    }
}
