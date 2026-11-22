# 편집 이력·트랙 녹음·MP3·파형 자르기 Implementation Plan

> Execute inline with executing-plans. Existing 0.3.0 work stays intact; this pass adds five user-requested editing features.

**Goal:** Add undo/redo, selected-track overdub recording, per-track pitch, one-loop MP3 export, and a waveform trim window while keeping old projects, the macro-key safety rules and the DAW layout.
**Architecture:** `Beat.Track` gains a pitch; `ProjectIO` schema 3 stores it and still reads 1–3; the native mixer owns selected-track recording and adds sample pitch; `DrumApp` keeps bounded project snapshots for history; `Mp3Exporter` reuses the same sample/velocity/pitch rules offline; `WaveformTrimDialog` returns a cropped `Sounds.Pcm` that is written as WAV.
**Tech Stack:** Java 17, JavaFX 21, JUnit 5, C++17/JNI/PortAudio WASAPI, jump3r 1.0.6 (LGPL 2.1) for MP3 encoding.

## Constraints
- Undo/redo covers project edits only (steps, tracks, keys, tempo, global/track pitch, loop length, recording, imported sound). Save does not clear history; NEW/OPEN resets it.
- File open/save/export dialog actions are not themselves undoable.
- Recording replaces only the selected track; other tracks keep playing and keep their hits.
- Existing keyboard configuration and focus-only macro-key handling remain untouched.
- MP3 export must match the audible loop: velocity, global pitch, per-track pitch and MASTER gain.
- Waveform selection is optional; cancelling leaves the project unchanged.
- Do not overwrite earlier distribution folders.

## Tasks

- [x] 1. Model and schema: add `Track.pitch`, schema v3, v1/v2 defaults, tests.
- [x] 2. Native: per-track sample pitch and selected-track overdub recording; keep backing hits sorted and playing; native/Java integration tests.
- [x] 3. Controller: bounded snapshot history with coalesced drag edits, Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y, toolbar buttons, track pitch spinner, selected-track REC, trim-aware add/change sound.
- [x] 4. Export: offline loop mix and jump3r encoding, MP3 toolbar action, progress/error status, LGPL source/notice.
- [x] 5. Waveform window: waveform drawing, draggable handles, automatic leading/trailing silence removal, WAV crop, test hook-free helper tests and a UI snapshot check.
- [x] 6. Full build: `scripts/build.ps1 -Package -UiTest -AudioSmoke`, package `0.4.0`, launch and inspect runtime/native DLL and ZIP contents.

## Ledger

- Ruling: schema becomes v3 rather than reusing v2 without a version bump; v1/v2 load with track pitch 0. Cost if wrong: older 0.3.0 readers cannot open new files, which is acceptable for a feature release and keeps old files readable here.
- Ruling: recording is one selected track per REC pass, matching the user's per-instrument workflow. Backing tracks loop during recording. Cost if wrong: recording multiple tracks at once now requires switching tracks and recording again.
- Ruling: history stores full immutable project snapshots (bounded to 64) but coalesces rapid same-action edits for 700 ms; step undos after a drag return to the state before the drag. Cost if wrong: many separate quick edits may undo together.
- Ruling: undo of step/tempo/pitch edits uses the fast `setLoop` path when track objects are identical; track/key/sound changes reinstall samples. Cost if wrong: audio reinstall takes slightly longer for those edits.
- Ruling: MP3 is encoded from a fresh offline mix rather than capturing the output device. This keeps export deterministic and independent of the selected WASAPI device. Cost if wrong: live MASTER changes after opening the save dialog are not included.
- Verification: 31 Java tests (including MP3 decode-back, trim/WAV, schema v2), 1 JavaFX flow test (overdub recording, macro KEY_TYPED, step undo/redo, 8th-track add/remove, per-track pitch, trim snapshot), 2 WASAPI tests and native CTest 1/1.
- Screenshots: `docs/screenshots/desktop.png`, `compact.png`, `trim.png`.
- Packaging: `dist/PocketGroove-0.4.0/` and `dist/PocketGroove-0.4.0-windows-x64.zip`; prior 0.1–0.3 folders preserved.
- Known limits: physical macro-keyboard taps and physical key-to-DAC latency were not measurable in this session; MP3 export is offline only; a second independent reviewer was unavailable (credit limit).
