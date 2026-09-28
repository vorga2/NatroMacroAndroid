# NatroMacroAndroid

Experimental Android port of the **field-pattern / movement concepts** used by Natro Macro for Bee Swarm Simulator.

## What works in v0.3.2

Full automated **Pine Tree cycle**: reset → hive respawn → red cannon → flight → gather.

- Native Android app; no modified Roblox APK, no injection.
- All input through Android Accessibility `dispatchGesture()`:
  - continuous joystick drags via `continueStroke` chains (1.5 s slices + fallback path);
  - taps and camera swipes.
- Reset through the Roblox in-game menu: UI-node text search (English **and** Russian labels) with coordinate tap fallbacks.
- Respawn vision via `takeScreenshot()` + color scoring:
  - hive-gold vs spawn-olive detection, camera-pitch rescan, up to 3 reset attempts;
  - hardened against mobile lighting/AA differences and false exits.
- Hive slot selection (1–6) with ramp alignment.
- Red cannon search by red-channel centroid with probe movement.
- Cannon flight: joystick timing + double jump + glider segment (Natro Pine Tree route timings translated to the touch joystick).
- Natro-style **Snake**, **Lines**, **Squares**, **Stationary** pattern engine.
  Pattern timing uses the same core assumption as Natro `Walk()`: **1 pattern tile = 4 Roblox studs**, scaled by configured move speed.
- Calibration overlays for joystick center/radius and jump button; floating STOP overlay (foreground service).
- Robustness fixes for Samsung / OneUI window noise and foreground detection.
- CI builds a **stably signed** debug APK (reproducible dev key) on every push.

## Not implemented yet

- Backpack-full detection and hive conversion.
- Other fields/routes, dispensers/collection tasks.
- Buff / haste move-speed compensation (Natro `DetectMovespeed`).
- Planters, quests, kills/bosses, boosts, reconnect logic.
- Reconnect after crashes/kicks.

The full 1:1 parity roadmap (phased, based on a full scan of `NatroTeam/NatroMacro` sources) lives in [`docs/PORT_PLAN.md`](docs/PORT_PLAN.md).

## Safety / account risk

This project deliberately does **not** inject into Roblox, read or patch Roblox memory, use script executors, modify the Roblox client, or attempt to bypass anti-cheat. It only automates normal touch gestures through Android accessibility APIs.

That does **not** mean a ban can be guaranteed impossible. Automated gameplay can still violate game/platform rules, and detection policies can change.

## Build

GitHub Actions builds on every push to `main` and on manual `workflow_dispatch`.

Locally, with Android SDK + JDK 17 + Gradle 8.9:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Attribution

Pattern behavior was studied from the open-source Natro Macro project by Natro Team:
https://github.com/NatroTeam/NatroMacro

Natro Macro is GPL-3.0 licensed. This port is intended to remain open-source and compatible with those obligations when derivative Natro material is incorporated.
