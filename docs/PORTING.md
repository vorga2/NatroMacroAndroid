# Android Pine Tree port: implementation and verification boundary

Upstream: [NatroTeam/NatroMacro at 094f9c7](https://github.com/NatroTeam/NatroMacro/tree/094f9c7b97e27f05e23f04beebecb7396bff5fd9).

| Upstream source | Android implementation |
| --- | --- |
| `lib/Walk.ahk`, `Walk` | 4 studs/tile, initial integrated distance 0.125 studs; monotonic timing |
| `lib/Walk.ahk`, `DetectMovespeed` | Same additive/multiplicative formula, manually configured buff states |
| `patterns/Snake.ahk`, `Lines.ahk`, `Squares.ahk`, `Diamonds.ahk`, `Slimline.ahk`, `e_lol.ahk`, `Stationary.ahk` | Literal tile sequences in `PatternFactory`, size .25/.5/1/1.5/2, repetition and axis inversion |
| `submacros/natro_macro.ahk`, `nm_gotoRamp` | 5 tiles forward; `9.2 * HiveSlot - 4` tiles right |
| `nm_PathVars`, `nm_gotoCannon` | Right+jump 100 ms, 2 tiles right, 1.5 tiles right+forward; bounded probes against a taught mobile prompt |
| `paths/gtf-pinetree.ahk`, Cannon branch | Normalized right+back 925 ms, double jump, 4500 ms glide, right 500 ms, jump, four left rotations, 2000 ms settle |
| Desktop pixel checks / E / Space / camera keys | User-taught screen regions, calibrated interaction/jump buttons and 45-degree camera swipe |
| Tool mouse-down during gather | Second simultaneous stroke, configurable held tool or repeated taps |

Jump taps use two 65 ms touches separated by 80 ms. The diagonal remains held through both jumps; 925 + 65 + 80 + 65 + 4500 = 5635 ms. A 16 ms joystick ramp precedes full-deflection movement. Android scheduling and gesture callback latency remain physical sources of timing error; durations are not a claim of millisecond-identical in-game motion.

## Why the old behavior was replaced

- No collection-tool input was issued during gathering.
- A timed-out gesture could be reported as successful.
- Unnormalized cannon diagonal requested a radius of sqrt(2) times the calibrated joystick radius.
- Any red pixels could count as a cannon; failure to see normal spawn could count as confirmed hive.
- Main-thread STOP could race a queued/restarted worker. Running and stop-requested are now separate; a new run cannot start until cleanup finishes.
- Cached foreground grace periods could send touches after leaving Roblox.
- Coordinates survived display changes without validation.

## Operation

The overlay is a Material Components `Theme.Material3.Dark.NoActionBar` panel. Its compact window is draggable. Opening settings stops the worker and waits for input cleanup before accepting settings touches. Closing the panel does not silently restart the route. Start explicitly runs the configured entry mode.

The entry modes are gather from current Pine Tree position or reset/align at hive, convert, route and gather. A repeating session performs reset, hive landmark confirmation, conversion with empty-backpack confirmation, route with cannon/Pine confirmation, then gathering. A finite repeating session converts the final backpack before stopping. Return currently uses reset, not the upstream walking/glider return path.

All screen templates are taught on the user's own screen and stored privately by the app. They are resolution-specific, compare a fixed region with ±4 px position tolerance, and require consecutive matches. They should contain small, distinctive, stable game UI or scene features. False negatives stop the macro; they do not launch blind recovery gestures.

## Not yet parity with desktop Natro

This is a Pine Tree port candidate requiring device validation, **not a complete Natro Macro feature port**. No device gameplay validation has been performed by CI.

- Automatic haste/buff recognition is not implemented. Manual values become wrong when buffs change. The resulting movement drift limits unattended sessions.
- Desktop field-drift correction, sprinkler-grid positioning and automatic camera pitch/zoom normalization are not implemented. `Slimline` and `e_lol` retain upstream offsets; use of these without upstream drift correction can move away from the starting point over repeated patterns.
- The seven listed patterns are ported; other desktop patterns, other fields, walking return, quests, planters, mobs, reconnect, boosters and dispensers are not implemented.
- Full/empty recognition is taught-state recognition, not OCR or a percentage gauge. Do not include changing numeric amounts in the template.
- The cannon approach requires correct initial position and camera calibration. Hive marker must match the character's position at the claimed hive, facing the ramp; markers do not infer arbitrary 3D pose.
- Accessibility only injects ordinary touch events. The app does not inject Roblox code or read its memory.

## Meaningful verification

Host unit tests cover upstream distance/buff math, diagonal normalization, exact pattern coefficients, axis inversions, geometric closure where upstream promises it, stationary mode and invalid settings. CI runs Android compilation, these tests and lint, then signs an installable APK using the repository's existing development certificate.

Device acceptance remains mandatory: tool collection with motion, 45° camera turn, cannon flight, hive/field landmark consistency, full/empty detection, conversion, two complete cycles, stop during a continued hold, opening settings during flight, screen rotation, keyboard editing, leaving Roblox, and service shutdown. A passing build is not evidence of successful farming.
