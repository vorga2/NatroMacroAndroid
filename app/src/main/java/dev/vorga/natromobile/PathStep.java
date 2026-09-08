package dev.vorga.natromobile;

/** Immutable step used by PathEngine. Distances are Natro tiles (4 studs). */
final class PathStep {
    enum Kind { MOVE_TILES, MOVE_MS, TAP, WAIT, ROTATE, REQUIRE_ANCHOR }

    final Kind kind;
    final String label;
    final float x;
    final float y;
    final double tiles;
    final long ms;
    final String key;
    final int rotateSteps;
    final boolean gather;
    final long[] jumpAt;
    final String anchor;
    final int frames;
    final long timeoutMs;

    private PathStep(
            Kind kind, String label,
            float x, float y, double tiles, long ms,
            String key, int rotateSteps, boolean gather, long[] jumpAt,
            String anchor, int frames, long timeoutMs
    ) {
        this.kind = kind;
        this.label = label;
        this.x = x;
        this.y = y;
        this.tiles = tiles;
        this.ms = ms;
        this.key = key;
        this.rotateSteps = rotateSteps;
        this.gather = gather;
        this.jumpAt = jumpAt == null ? new long[0] : jumpAt.clone();
        this.anchor = anchor;
        this.frames = frames;
        this.timeoutMs = timeoutMs;
    }

    static PathStep tiles(String label, float x, float y, double tiles) {
        return new PathStep(Kind.MOVE_TILES,label,x,y,tiles,0,null,0,false,null,null,0,0);
    }

    static PathStep tilesGather(String label, float x, float y, double tiles) {
        return new PathStep(Kind.MOVE_TILES,label,x,y,tiles,0,null,0,true,null,null,0,0);
    }

    static PathStep raw(String label, float x, float y, long ms, long... jumpAt) {
        return new PathStep(Kind.MOVE_MS,label,x,y,0,ms,null,0,false,jumpAt,null,0,0);
    }

    static PathStep tap(String label, String key, long ms) {
        return new PathStep(Kind.TAP,label,0,0,0,ms,key,0,false,null,null,0,0);
    }

    static PathStep waitMs(String label, long ms) {
        return new PathStep(Kind.WAIT,label,0,0,0,ms,null,0,false,null,null,0,0);
    }

    static PathStep rotate(String label, int steps) {
        return new PathStep(Kind.ROTATE,label,0,0,0,0,null,steps,false,null,null,0,0);
    }

    static PathStep require(String label, String anchor, int frames, long timeoutMs) {
        return new PathStep(Kind.REQUIRE_ANCHOR,label,0,0,0,0,null,0,false,null,anchor,frames,timeoutMs);
    }
}
