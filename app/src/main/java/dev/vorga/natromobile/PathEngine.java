package dev.vorga.natromobile;

import java.util.List;

/** Executes deterministic Natro-style path steps with visual checkpoints. */
final class PathEngine {
    interface StatusSink { void set(String state); }

    private final MovementEngine movement;
    private final VisionEngine vision;
    private final StatusSink status;

    PathEngine(MovementEngine movement, VisionEngine vision, StatusSink status) {
        this.movement=movement;
        this.vision=vision;
        this.status=status;
    }

    void run(List<PathStep> steps) throws Exception {
        for(PathStep step:steps) {
            if(step.label!=null && !step.label.isEmpty()) status.set(step.label);
            switch(step.kind) {
                case MOVE_TILES:
                    movement.moveTiles(step.x,step.y,step.tiles,step.gather);
                    break;
                case MOVE_MS:
                    movement.moveRaw(step.x,step.y,step.ms,step.jumpAt);
                    break;
                case TAP:
                    movement.tap(step.key,step.ms);
                    break;
                case WAIT:
                    movement.waitFor(step.ms);
                    break;
                case ROTATE:
                    movement.rotate(step.rotateSteps);
                    break;
                case REQUIRE_ANCHOR:
                    if(!vision.waitFor(step.anchor,step.timeoutMs,step.frames))
                        throw new IllegalStateException("Не найден ориентир: "+step.anchor);
                    break;
            }
        }
    }
}
