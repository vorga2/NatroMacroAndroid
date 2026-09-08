package dev.vorga.natromobile;

import java.util.ArrayList;
import java.util.List;

/** Literal tile sequences from Natro patterns at 094f9c7 (GPL-3.0). */
final class PatternFactory {
    static final String[] NAMES = {"Snake", "Lines", "Squares", "Diamonds", "Slimline", "e_lol", "Stationary"};
    static List<PatternStep> create(String pattern, double size, int reps) {
        return create(pattern, size, reps, false, false, false);
    }
    static List<PatternStep> create(String pattern, double size, int reps, boolean invertLR, boolean invertFB, boolean corner) {
        if (!Double.isFinite(size) || size < .25 || size > 2 || reps < 1 || reps > 10)
            throw new IllegalArgumentException("Size .25–2; repetitions 1–10");
        List<PatternStep> a = new ArrayList<>();
        switch (pattern) {
            case "Snake": case "Lines":
                for (int direction : new int[]{-1, 1}) for (int i=0; i<reps; i++) {
                    if (pattern.equals("Snake")) {
                        add(a,-1,0,11*size); add(a,0,direction,1);
                        add(a,1,0,11*size); add(a,0,direction,1);
                    } else {
                        add(a,0,-1,11*size); add(a,direction,0,1);
                        add(a,0,1,11*size); add(a,direction,0,1);
                    }
                } break;
            case "Squares": case "Diamonds":
                for (int i=1;i<=reps;i++) {
                    double d=5*size+i;
                    if (pattern.equals("Squares")) {
                        add(a,0,-1,d); add(a,-1,0,d); add(a,0,1,d); add(a,1,0,d);
                    } else {
                        add(a,-1,-1,d); add(a,1,-1,d); add(a,1,1,d); add(a,-1,1,d);
                    }
                } break;
            case "Slimline":
                add(a,-1,0,4*size+reps*.1-.1); add(a,1,0,8*size); add(a,-1,0,4*size); break;
            case "e_lol":
                double spacing=274*9/2000.0, longLeg=(1094+(corner?25:0))*9/2000.0*size;
                add(a,-1,0,spacing*(reps*2+1)); add(a,0,1,5*size);
                for(int i=0;i<reps;i++) {
                    add(a,1,0,spacing); add(a,0,-1,5*size); add(a,1,0,spacing); add(a,0,1,longLeg);
                }
                add(a,-1,0,spacing*(reps*2+.5)); add(a,0,-1,5*size);
                for(int i=0;i<reps;i++) {
                    add(a,1,0,spacing); add(a,0,1,longLeg); add(a,1,0,spacing*1.5); add(a,0,-1,5*size);
                } break;
            case "Stationary": break;
            default: throw new IllegalArgumentException("Unknown pattern: "+pattern);
        }
        List<PatternStep> result=new ArrayList<>();
        for(PatternStep step:a) result.add(new PatternStep(step.x*(invertLR?-1:1), step.y*(invertFB?-1:1),step.tiles));
        return result;
    }
    private static void add(List<PatternStep> a,float x,float y,double tiles) { a.add(new PatternStep(x,y,tiles)); }
}
