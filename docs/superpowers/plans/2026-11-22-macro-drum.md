# Pocket Groove Implementation Plan
> **For agentic workers:** Use executing-plans. Track completion here.

**Goal:** Ship a six-pad JavaFX instrument and a Windows runtime-bundled executable.
**Architecture:** Immutable project data, beat-clock recording/playback, JavaFX AudioClip polyphony, ZIP project files containing samples, one JavaFX screen.
**Tech Stack:** Java 17, JavaFX 21, Maven, JUnit 5, jpackage, PowerShell.
**Spec:** docs/superpowers/specs/2026-11-22-macro-drum-design.md

## Global constraints
No device-setting writes. Focused keyboard input only. Six pads. ZIP projects include sounds. Runtime must be redistributable.

## Review focus
1. Held keys and focus loss must not stick or retrigger.
2. Loop boundaries and tempo changes must not duplicate/drop scheduled hits.
3. Invalid/oversized project data must fail without replacing the current session.
4. Save failure must preserve the old file; quitting must protect unsaved work.
5. Unsupported audio must explain the problem without replacing a working pad.

## Tasks
- [x] 1. Core and persistence: `Beat.java`, `ProjectIO.java`, `BeatTest.java`, `ProjectIOTest.java`; write/run failing tests for loop wrap, tempo, validation and self-contained atomic save; implement and run full suite.
- [x] 2. Instrument: `DrumApp.java`, `Sounds.java`, `Launcher.java`, `drum.css`; create reproducible original samples, wire key learning, polyphony, loop recording, loading/saving, dirty-state handling; run JavaFX smoke tests and capture actual UI.
- [x] 3. Distribution: `scripts/build.ps1`, Maven config, README, notices, GitHub Actions; run test/package and jpackage app-image; launch bundled exe and inspect the loaded JVM path.
- [x] 4. Fresh review and fixes; final test run and artifact verification.

## Execution notes
- Ruling: work in the requested existing empty folder on `feat/pocket-groove`, rather than relocate the project. No previous implementation or baseline tests exist.
- Ruling: continue implementation after the user's explicit go-ahead rather than ask for a second implementation approval.
- Ruling: generate six original drum samples instead of downloading uncertain-license assets. Commit generator and WAVs; no external sample rights are needed.
- Ruling: use installed Eclipse Temurin 17 for packaging (not the Oracle JDK on PATH); include JavaFX and runtime notices. Portable exe folder/ZIP satisfies runtime bundling without an installer toolchain.
- Verification: initial core tests failed for missing implementation, then passed. LoopPlayer live-tempo test failed for missing implementation, then passed. JavaFX smoke verifies six pads, key learning, held-key suppression, recording/playback, rejection of malformed audio and minimum-width file actions.
- UI regression: compact-window action bounds test failed, then passed after fixing Canvas-driven minimum width. Actual JavaFX scene captures inspected at desktop and compact widths.
- Packaging verification: jpackage succeeds; Orca observed the packaged app's ready state. Process module inspection showed `dist/PocketGroove/runtime/bin/server/jvm.dll`, and the application closed normally. Initial parent-process-only smoke checks were invalid because the visible app is a child process; corrected verification targets the actual window process.
- Ruling: native JavaFX does not fit the web/iOS/Android-only design-tool platform enum; applied design principles to JavaFX, recorded native authority in DESIGN.md, and used actual JavaFX screenshots rather than a web mockup.
- Ruling: bundled fonts replace unavailable machine font families; same six-pad layout and palette. Below content height, vertical scrolling preserves controls rather than clipping.
- Final review: fresh reviewer assessed code, packaging and both screenshots. One important finding: ZIP directory entries unnecessarily invalidated otherwise complete projects. Reproduced by `standardZipDirectoryEntriesDoNotInvalidateProject` (RED), removed redundant total entry count, retained per-entry validation.
- Final: Ruling: reviewer suggested digit-label normalization; actual JavaFX 21.0.9 `new KeyCodeCombination(KeyCode.DIGIT1).getName()` returned `1` in JShell. No normalization added because the claimed discrepancy is absent.
- Final: Ruling: physical keypad timing and audio-driver latency require hands-on device testing. The app and bundled runtime were exercised, but no physical keypress or acoustic latency measurement is claimed.
- Final verification: `scripts/build.ps1 -Package -UiTest` passed 10 tests (0 failures/errors/skips), including the ZIP regression. Built `dist/PocketGroove/PocketGroove.exe` and a 35,560,774-byte portable ZIP. The rebuilt executable reached its ready state in native UI automation, with the window inside the screen bounds. JavaFX notices and runtime legal files are present. Development UI tests log JavaFX's unnamed-module warning; the packaged launcher supplies the JavaFX module path.
- Final integration: retain `feat/pocket-groove` locally. User plans GitHub publication later; no remote publication performed.
