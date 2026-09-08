package dev.vorga.natromobile;

/** Distance/buff model ported from Natro lib/Walk.ahk (GPL-3.0). */
final class MovementMath {
    static double speed(double base, boolean guard, boolean gifted, int haste,
                        boolean coconut, boolean bear, boolean plus, boolean oil, boolean smoothie) {
        return (base + (coconut ? 10 : 0) + (bear ? 4 : 0))
                * (guard ? 1.1 : 1) * (gifted ? 1.15 : 1)
                * (1 + Math.max(0, Math.min(10, haste)) * .1)
                * (plus ? 2 : 1) * (oil ? 1.2 : 1) * (smoothie ? 1.25 : 1);
    }
    static long duration(double tiles, double speed, double scale) {
        if (!Double.isFinite(tiles) || !Double.isFinite(speed) || !Double.isFinite(scale)
                || tiles < 0 || speed <= 0 || scale <= 0) throw new IllegalArgumentException("Invalid movement");
        // Walk() starts its integrated distance at freq / 8 = 0.125 studs.
        return Math.max(1, Math.round(Math.max(0, tiles * 4 - .125) / speed * 1000 * scale));
    }
    static float[] unit(float x, float y) {
        double len = Math.hypot(x, y);
        return len == 0 ? new float[]{0, 0} : new float[]{(float)(x / len), (float)(y / len)};
    }
}
