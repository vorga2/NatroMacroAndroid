# NatroMacroAndroid

Experimental Android port of the **field-pattern / movement concepts** used by Natro Macro for Bee Swarm Simulator.

## What works in v0.1

- Native Android app; no modified Roblox APK.
- Uses Android Accessibility `dispatchGesture()` for ordinary on-screen touch input.
- Full-screen joystick-center calibration overlay.
- Natro-style **Snake**, **Lines**, **Squares**, and **Stationary** pattern engine.
- Pattern timing uses the same core assumption as Natro `Walk()`: **1 pattern tile = 4 Roblox studs**, adjusted by configured move speed.
- Floating STOP control while the macro is active.
- GitHub Actions debug APK build.

## What is not implemented yet

- Automatic hive -> field routes.
- Backpack-full image detection.
- Automatic reset / hive conversion.
- Buff / haste computer-vision compensation.
- Reconnect, planters, quests, mobs, dispensers, boosters, etc.

So v0.1 is meant to be started **while your character is already standing in the field and facing the intended direction**.

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
