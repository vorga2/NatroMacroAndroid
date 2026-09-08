package dev.vorga.natromobile;

/**
 * Natro-style movement facade. Public route distances are expressed in tiles
 * (1 tile = 4 Roblox studs), never arbitrary sleep durations.
 */
final class MovementEngine {
    interface SpeedProvider { double currentSpeed(); }

    private final TouchEngine touch;
    private final MacroConfig config;
    private final int width;
    private final int height;
    private final SpeedProvider speedProvider;

    MovementEngine(TouchEngine touch, MacroConfig config, int width, int height, SpeedProvider speedProvider) {
        this.touch = touch;
        this.config = config;
        this.width = width;
        this.height = height;
        this.speedProvider = speedProvider;
    }

    void moveTiles(float x, float y, double tiles) throws InterruptedException {
        moveTiles(x,y,tiles,false);
    }

    void moveTiles(float x, float y, double tiles, boolean gather) throws InterruptedException {
        if (!Double.isFinite(tiles) || tiles < 0) throw new IllegalArgumentException("Некорректная дистанция маршрута");
        if (tiles == 0) return;

        // Re-sample the speed on bounded legs. Long scripted paths therefore
        // compensate when the speed model changes instead of using one huge delay.
        double left = tiles;
        while (left > 0.0001) {
            double leg = Math.min(4.0, left);
            double speed = speedProvider.currentSpeed();
            long ms = MovementMath.duration(leg, speed, config.number("timing_scale",1));
            touch.move(x,y,ms,gather);
            left -= leg;
        }
    }

    void moveRaw(float x, float y, long ms, long... jumpAt) throws InterruptedException {
        touch.move(x,y,ms,false,jumpAt);
    }

    void moveRawGather(float x,float y,long ms) throws InterruptedException {
        touch.move(x,y,ms,true);
    }

    void waitFor(long ms) throws InterruptedException { touch.waitFor(ms); }

    void tap(String key,long ms) throws InterruptedException { touch.tap(key,ms); }

    void jump() throws InterruptedException { touch.tap("jump",65); }

    void jump(int count,long gapMs) throws InterruptedException {
        for(int i=0;i<count;i++) {
            jump();
            if(i+1<count) waitFor(gapMs);
        }
    }

    void rotate(int steps) throws InterruptedException {
        float distance=config.number("camera_swipe",.14f)*width;
        for(int i=0;i<Math.abs(steps);i++) {
            float from=width*.70f;
            float to=from+(steps>0?-distance:distance);
            touch.swipe(from,height*.48f,to,height*.48f,145);
            touch.waitFor(75);
        }
    }
}
