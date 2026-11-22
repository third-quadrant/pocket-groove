# 스텝 음높이·미리 듣기·파일 메뉴 Implementation Plan

> 0.4.0 위에 얹은 0.5.0 변경. 기존 녹음/저장/오디오 경로를 유지하면서 멜로디와 파일 동작을 추가한다.

**Goal:** Add audition for the waveform selection, per-step note pitch (monophonic melody per track), a Korean hamburger file menu with Ctrl+S overwrite/save-as behavior, fix the horizontal timeline scroll range, and associate `.mdrum` with the packaged EXE.
**Architecture:** `Beat.Hit` gains a note pitch stored in project schema 4; `Pattern` and the step sequencer expose a pitch lane; the native mixer applies global + track + step pitch; `Mp3Exporter` includes step pitch; `WaveformTrimDialog` uses a Java Sound `Clip` for selection preview; `FileAssociations` registers HKCU classes and resolves the startup argument.

## Constraints
- Existing `.mdrum` 0.1–0.4 files must load; missing step pitch becomes 0.
- A track stays monophonic per timestamp: one note per step cell, with its own pitch.
- Note pitch range is ±24 semitones, track pitch ±12 and global pitch ±12.
- Preview must not stop/restart the native loop; it is an independent temporary Java Sound clip.
- The hamburger menu must use Korean labels; `Ctrl+S` overwrites the current file, and falls back to Save As for a new project.
- File association is per-user (HKCU, no admin) and skipped in development runs.
- Prior distribution folders are not overwritten.

## Tasks
- [x] 1. Model/schema: `Hit.pitch`, schema v4, v1–v3 compatibility, `Pattern.setPitch`, tests.
- [x] 2. Native/audio: hit pitch in install/setLoop/recording/snapshot and playback; C++/JNI/Java integration tests; fix global-pitch double/omission behavior.
- [x] 3. Sequencer: NOTE PITCH lane, click/drag step pitch, Shift+↑/↓, header/summary display, timeline clip preview height, UI test.
- [x] 4. Timeline: extend loop cycles beyond the viewport so the horizontal scrollbar has range; UI assertion.
- [x] 5. Waveform: preview button using selected crop, stop on drag/selection change/dialog close; UI test verifies playback starts.
- [x] 6. File menu: ☰ with Korean labels, Ctrl+S / Ctrl+Shift+S, Save As current-file handling, UI test for overwrite path.
- [x] 7. Association: HKCU registry commands, packaged-EXE detection, Preferences guard, startup argument open; command/argument tests and packaged launch with `.mdrum` argument.
- [x] 8. Package 0.5.0, inspect screenshots, verify bundled runtime/native DLL/menu labels, update docs/notices.

## Ledger
- Ruling: per-step pitch is stored on the hit and applied together with global and track pitch. Cost if wrong: older readers reject schema 4, but 0.5.0 reads all prior files.
- Ruling: the pitch lane creates a note when an empty cell is clicked. This makes melody entry fast but means pitch editing also adds rhythm; users can delete with a normal step toggle.
- Ruling: the preview uses `javax.sound.sampled.Clip` instead of the native mixer. It keeps the loop untouched, but if the selected WASAPI device is exclusive the preview can fail with an on-screen warning.
- Ruling: the file association is registered at first packaged launch rather than by an installer, because distribution is a portable app-image. Registry policy can block this; the app still opens files passed on the command line.
- Verification: 31 regular Java tests + 1 JavaFX UI test + 2 WASAPI tests + 1 native CTest; the UI test covers menu labels, scrollbar max, per-step pitch, Save overwrite through the Ctrl+S path, waveform preview playback, dynamic tracks and macro input. A packaged launch with a `.mdrum` argument and a real `Ctrl+S` overwrite were also checked.
- Packaging: `dist/PocketGroove-0.5.0/`, `dist/PocketGroove-0.5.0-windows-x64.zip`; older distribution folders preserved.
- Known limit: this machine's administrator policy blocks registry editing, so the HKCU association commands could not be executed here. Command construction and startup-file opening were verified independently.
