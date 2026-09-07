package dev.vorga.natromobile;

import java.util.ArrayList;
import java.util.List;

final class PatternFactory {
    private PatternFactory() {}

    static List<PatternStep> create(String pattern, double size, int reps) {
        ArrayList<PatternStep> out = new ArrayList<>();
        reps = Math.max(1, reps);
        size = Math.max(0.1, size);

        switch (pattern) {
            case "Lines":
                // Port of Natro Lines: W long, A short, S long, A short; then mirrored with D.
                for (int i = 0; i < reps; i++) {
                    add(out, 0, -1, 11 * size);
                    add(out, -1, 0, 1);
                    add(out, 0, 1, 11 * size);
                    add(out, -1, 0, 1);
                }
                for (int i = 0; i < reps; i++) {
                    add(out, 0, -1, 11 * size);
                    add(out, 1, 0, 1);
                    add(out, 0, 1, 11 * size);
                    add(out, 1, 0, 1);
                }
                break;

            case "Squares":
                // Natro Squares grows each loop by one tile-equivalent.
                for (int i = 1; i <= reps; i++) {
                    double d = 5 * size + i;
                    add(out, 0, -1, d);
                    add(out, -1, 0, d);
                    add(out, 0, 1, d);
                    add(out, 1, 0, d);
                }
                break;

            case "Stationary":
                break;

            case "Snake":
            default:
                // Port of Natro Snake: A long, W short, D long, W short; then mirrored backwards.
                for (int i = 0; i < reps; i++) {
                    add(out, -1, 0, 11 * size);
                    add(out, 0, -1, 1);
                    add(out, 1, 0, 11 * size);
                    add(out, 0, -1, 1);
                }
                for (int i = 0; i < reps; i++) {
                    add(out, -1, 0, 11 * size);
                    add(out, 0, 1, 1);
                    add(out, 1, 0, 11 * size);
                    add(out, 0, 1, 1);
                }
                break;
        }
        return out;
    }

    private static void add(List<PatternStep> list, float x, float y, double tiles) {
        list.add(new PatternStep(x, y, Math.max(0.05, tiles)));
    }
}
