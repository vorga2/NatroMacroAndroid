package dev.vorga.natromobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Recovery-oriented coordinator: known anchor -> deterministic path -> anchor check.
 * Failures reset and retry instead of continuing from accumulated drift.
 */
final class RecoveryStateMachine {
    interface StatusSink { void set(String state); }

    private final MacroConfig config;
    private final MovementEngine movement;
    private final VisionEngine vision;
    private final PathEngine paths;
    private final StatusSink status;

    RecoveryStateMachine(
            MacroConfig config,
            MovementEngine movement,
            VisionEngine vision,
            PathEngine paths,
            StatusSink status
    ) {
        this.config=config;
        this.movement=movement;
        this.vision=vision;
        this.paths=paths;
        this.status=status;
    }

    void validateAutoRoute() {
        for(String point:new String[]{"menu","reset","confirm","interact","jump"})
            if(!config.point(point)) throw new IllegalStateException("Для маршрута нужна калибровка кнопки: "+point);
        for(String marker:new String[]{"hive","cannon","pine"})
            if(!vision.exists(marker)) throw new IllegalStateException("Обучи ориентир «"+marker+"»");
        if(!config.flag("camera_calibrated",false))
            throw new IllegalStateException("Откалибруй поворот камеры на 45°");
        if(config.flag("sprinkler",false)&&!config.point("sprinkler"))
            throw new IllegalStateException("Откалибруй слот спринклера");
    }

    void resetToHive() throws Exception {
        for(int attempt=1;attempt<=3;attempt++) {
            status.set("RESET → HIVE • "+attempt+"/3");
            movement.tap("menu",80);
            movement.waitFor(650);
            movement.tap("reset",80);
            movement.waitFor(650);
            movement.tap("confirm",80);
            movement.waitFor(config.integer("respawn_ms",5500));

            status.set("RESPAWN • SEARCH HIVE");
            if(findHiveByRotation()) return;

            // Desktop Natro has a distinct SpawnLocation recovery path. On mobile
            // we use the optional user-trained spawn anchor and the same slotMove table.
            if(vision.exists("spawn") && vision.seen("spawn",1)) {
                status.set("SPAWN → HIVE SLOT "+config.integer("hive_slot",1));
                runSpawnToHive(config.integer("hive_slot",1));
                movement.waitFor(450);
                if(findHiveByRotation()) return;
            }
        }
        throw new IllegalStateException("Не удалось подтвердить улей после 3 reset/recovery попыток");
    }

    private boolean findHiveByRotation() throws Exception {
        for(int yaw=0;yaw<8;yaw++) {
            if(vision.seen("hive",1)) {
                status.set("HIVE ANCHOR OK");
                return true;
            }
            movement.rotate(1);
            movement.waitFor(150);
        }
        return false;
    }

    private void runSpawnToHive(int slot) throws Exception {
        int s=Math.max(1,Math.min(6,slot));
        List<PathStep> p=new ArrayList<>();
        switch(s) {
            case 1:
                p.add(PathStep.tiles("SPAWN H1 • RIGHT",1,0,4));
                p.add(PathStep.tiles("SPAWN H1 • RIGHT+FWD",1,-1,20));
                break;
            case 2:
                p.add(PathStep.tiles("SPAWN H2 • FWD+RIGHT",1,-1,13));
                p.add(PathStep.tiles("SPAWN H2 • FWD",0,-1,6));
                break;
            case 3:
                p.add(PathStep.tiles("SPAWN H3 • FWD",0,-1,20));
                p.add(PathStep.tiles("SPAWN H3 • BACK",0,1,4));
                break;
            case 4:
                p.add(PathStep.tiles("SPAWN H4 • LEFT+FWD",-1,-1,13));
                p.add(PathStep.tiles("SPAWN H4 • FWD",0,-1,6));
                break;
            case 5:
                p.add(PathStep.tiles("SPAWN H5 • LEFT",-1,0,4));
                p.add(PathStep.tiles("SPAWN H5 • LEFT+FWD",-1,-1,20));
                break;
            default:
                p.add(PathStep.tiles("SPAWN H6 • LEFT+FWD",-1,-1,12));
                p.add(PathStep.tiles("SPAWN H6 • LEFT",-1,0,13));
                p.add(PathStep.tiles("SPAWN H6 • LEFT+FWD",-1,-1,10));
                break;
        }
        paths.run(p);
    }

    void gotoRamp() throws Exception {
        int slot=Math.max(1,Math.min(6,config.integer("hive_slot",1)));
        paths.run(List.of(
                PathStep.tiles("HIVE → RAMP • FWD 5",0,-1,5),
                PathStep.tiles("HIVE → RAMP • RIGHT",1,0,9.2*slot-4.0)
        ));
    }

    void gotoCannonWithRecovery() throws Exception {
        for(int attempt=1;attempt<=3;attempt++) {
            status.set("RAMP → CANNON • "+attempt+"/3");
            movement.jump();
            movement.moveTiles(1,0,2.0);
            movement.moveTiles(1,-1,1.5);

            for(int probe=0;probe<18;probe++) {
                if(vision.seen("cannon",1)) {
                    movement.waitFor(500);
                    if(vision.seen("cannon",1)) {
                        status.set("CANNON ANCHOR OK");
                        return;
                    }
                    // Natro correction: if the prompt disappears, assume we passed it.
                    movement.moveTiles(-1,0,1.5);
                    if(vision.waitFor("cannon",1600,2)) {
                        status.set("CANNON CORRECTED");
                        return;
                    }
                }
                movement.moveTiles(1,0,.65);
            }

            if(attempt<3) {
                status.set("CANNON MISSED → RESET");
                resetToHive();
                gotoRamp();
            }
        }
        throw new IllegalStateException("Red Cannon prompt не найден после recovery");
    }

    void flyCannonToPine() throws Exception {
        // Data-driven translation of paths/gtf-pinetree.ahk cannon branch:
        // E 100 ms; Right+Back 925 ms; double jump; glide 4500 ms;
        // keep Right 500 ms; jump; RotLeft x4; settle.
        paths.run(List.of(
                PathStep.tap("CANNON • INTERACT","interact",100),
                PathStep.raw("CANNON • LAUNCH+GLIDER",1,1,5425,925,1070),
                PathStep.raw("GLIDE • RIGHT 500",1,0,500),
                PathStep.tap("GLIDE • FINAL JUMP","jump",65),
                PathStep.rotate("CAMERA • LEFT ×4",-4),
                PathStep.waitMs("PINE • SETTLE",2000),
                PathStep.require("PINE • VERIFY","pine",2,1800)
        ));

        if(config.flag("sprinkler",false)) {
            status.set("PINE • SPRINKLER");
            movement.tap("sprinkler",80);
            movement.waitFor(500);
        }
    }

    void convertAtHive() throws Exception {
        if(!vision.exists("empty")) return;
        status.set("HIVE • CONVERT");
        if(vision.seen("empty",2)) return;
        movement.tap("interact",100);
        long timeout=config.integer("convert_seconds",120)*1000L;
        if(!vision.waitFor("empty",timeout,3))
            throw new IllegalStateException("Переработка не завершилась: empty не найден");
    }
}
