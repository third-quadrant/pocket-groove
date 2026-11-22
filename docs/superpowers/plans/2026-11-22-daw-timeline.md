# DAW 타임라인·가변 트랙 Implementation Plan

> Execute inline with executing-plans. Model/input tests first; native and UI adapt after the contracts settle.

**Goal:** Replace the fixed six-pad pastel screen with a dark DAW workspace, dynamic tracks (max 32), a real pattern timeline, a 16th-note velocity step sequencer, and macro-typed/numpad key support without losing any current feature.
**Architecture:** JavaFX controller + two Canvas views (`ArrangementView`, `StepSequencerView`) over a dynamic `Beat.Project`; the existing PortAudio/JNI mixer holds up to 32 samples and velocity-scaled hits.
**Tech Stack:** Java 17, JavaFX 21, Canvas/CSS, JUnit 5, C++17, JNI, PortAudio WASAPI.
**Spec:** `docs/superpowers/specs/2026-11-22-daw-timeline-design.md`

## Global constraints
Keep focus-only keyboard input, existing keyboard configuration untouched, WAV/AIFF/MP3, project save/load, recording, looping, tempo/pitch/volume. No fake seek, no decorative controls. Every interaction shown must change real audio or project data. Existing v1 projects must load.

## Review focus
1. V1 project files, zero/duplicate tracks, out-of-range hit track indices, velocity bounds.
2. Key Typed/Numpad aliases must not double-fire normal physical presses.
3. Step drag edits must not create duplicate hits or corrupt timing while playing.
4. Dynamic sample install and loop-only updates must remain callback-safe and stop/recover streams.
5. Compact layouts must keep transport, selected track, and step grid usable without clipping.

## Tasks

- [x] 1. Dynamic model + pattern math (`Beat.java`, `Pattern.java`, `KeyBindings.java`, `ProjectIO.java`, tests): write failing tests for 1–32 tracks, velocity, v1 compatibility, step toggle/velocity/track removal, key aliases. Implement. Run Java tests.
- [x] 2. Native 32-track/velocity (`native/mixer.*`, `native/audio_jni.cpp`, `AudioEngine.java`, native/Java tests): failing tests for 7+ tracks, velocity mix, loop-only update; implement; run CTest and WASAPI tests.
- [x] 3. DAW views (`DawStyle.java`, `ArrangementView.java`, `StepSequencerView.java`): build Canvas track/timeline/ruler/clips/playhead and step/velocity grid, real scrolling, zoom, selection, painting. Unit/model tests plus headless view smoke.
- [x] 4. Controller + theme (`DrumApp.java`, `drum.css`): transport bar, dynamic track controls, add/remove/sound/key workflows, step edits → loop update, input diagnostics, file compatibility. Update JavaFX smoke test and inspect 1440×900/compact screenshots.
- [x] 5. Distribution `0.3.0`, docs/notices/CI, run `build.ps1 -Package -UiTest -AudioSmoke`, launch EXE, verify runtime/native DLL and no old-folder overwrite, final review notes.

## Ledger

- Baseline: uncommitted 0.2.1 low-latency work on `feat/low-latency-audio`; user selected one-loop pattern repetition and confirmed macro output appears in Notepad.
- Ruling: implement one-loop clip repetition rather than multi-pattern song arrangement — preserves the verified engine model; cost if wrong: no song sections beyond repeated loop.
- Ruling: default project becomes 7 tracks (Kick, Snare, Closed Hi-Hat, Open Hi-Hat, Clap, Tom, Percussion), keys 1–7, 1 bar (16 steps) — matches the requested track list and default sequencer; cost if wrong: users preferring the old 4-bar six-pad default must change loop length/keys.
- Ruling: use a single-pass typed/numpad alias matcher and an on-screen last-input trace rather than changing SDTech Options or attempting global device-exclusive capture — cost if wrong: a macro that never reaches JavaFX as any key or text event still needs a separate device-level investigation.
- Ruling: add an audio-safe `replaceLoop` path that preserves playback position when a step is edited while playing; the first UI run showed the pending loop update resetting playback position to zero — cost if wrong: minor extra native complexity, but prevents an audible reset.
- Verification 1 (model/input): 6 `ProjectIOTest`, 6 `PatternTest`, 4 `KeyBindingsTest`, 3 `BeatTest`, 4 `SoundsTest` pass. Includes 32-track model, v1/v2 project load, velocity, step toggle/drag, Numpad/KEY_TYPED aliases.
- Verification 2 (native): CTest 1/1 pass; 7+ track mixer, velocity mixing, bounded command/event processing, live `replaceLoop` playback preservation, malformed track rejection.
- Verification 3 (integration): WASAPI `AudioEngineTest` 2/2 pass, including 32-track native install and a 7-track record/loop/reconfigure flow.
- Verification 4 (UI): JavaFX smoke test passes; checks 7 tracks, key learning, physical held-key suppression, a real macro `KEY_TYPED` hit, record/loop, step keyboard editing, live position/playhead, desktop/compact snapshots, adding an 8th track from a sound file, removing it, and auto key assignment.
- Screenshots: `target/screenshots/desktop.png` and `target/screenshots/compact.png`; final copies live in `docs/screenshots/`.
- Packaging: `scripts/build.ps1 -Package -UiTest -AudioSmoke` produced `dist/PocketGroove-0.3.0-windows-x64.zip` and `dist/PocketGroove-0.3.0/PocketGroove/PocketGroove.exe`; runtime, JNI DLL, JavaFX controls, MP3 decoder JARs and LGPL source JARs verified in the ZIP; test-only MP3 and JUnit excluded.
- Runtime: packaged 0.3.0 launched and reached its ready state; process modules loaded the bundled `runtime/bin/server/jvm.dll` and `app/pocketgroove_audio.dll`. Older `dist/PocketGroove` and `dist/PocketGroove-0.2.1` folders were not replaced.
- Known limits: the physical macro keyboard's exact event stream was not available in this session (the user confirmed text appears in Notepad; app diagnostics now expose the received event). No physical key-to-DAC latency measurement was made.
- Final review: fresh reviewer was not available within the session budget (earlier HTTP 402); this is an author review, weaker than an independent review. No commit or push has been made.
