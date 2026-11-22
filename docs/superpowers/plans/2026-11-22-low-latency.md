# Low-latency audio implementation plan
> Execute inline using executing-plans. The user approved the recommended architecture and asked to proceed.

**Goal:** Replace per-hit AudioClip players with a persistent native sample mixer without losing current features.
**Architecture:** JavaFX and project I/O remain Java. A thin JNI control layer feeds a bounded command queue; a PortAudio WASAPI callback owns voices and the musical clock.
**Tech stack:** Java 17, JavaFX 21, C++17, JNI, PortAudio 19.7.0, CMake/MSVC, Java Sound + MP3 decoder.
**Spec:** docs/superpowers/specs/2026-11-22-low-latency-design.md

## Global constraints
No keyboard configuration writes. Preserve existing project files and features. Preserve the running old app with unsaved work. No allocation/locks/JVM calls in native render callback. Keep user explanations simple.

## Review focus
1. JNI bounds/invalid handles and malformed decoded audio must not crash the JVM.
2. Sample lifetime and closing streams must not race the callback.
3. Loop boundaries, stop, recording, and tempo changes must not duplicate or lose hits.
4. Queue saturation and voice stealing must remain bounded and visible.
5. Output failures must preserve the project; packaging must include its native dependencies.

## Tasks
- [x] 1. Native mixer + tests (`native/mixer.h`, `native/mixer.cpp`, `native/mixer_test.cpp`): write and run failing tests, implement mixing, commands, sample clock and recording, run CTest.
- [x] 2. Backend + decoder (`native/audio_jni.cpp`, `AudioEngine.java`, `Sounds.java`, `AudioEngineTest.java`, `SoundsTest.java`): validate PCM/JNI input, persistent WASAPI stream and device settings, actual MP3/WAV/AIFF decoding. Run native/Java tests.
- [x] 3. UI integration (`DrumApp.java`, `DrumAppSmokeTest.java`): preserve controls and project semantics, add plain-language audio settings, drive loops/recording through engine, batch status polling. Run smoke test and inspect screenshots.
- [x] 4. Build/distribution/docs (`scripts/build-native.ps1`, `scripts/build.ps1`, CI, README, notices): include pinned source dependency, native build/tests and DLL in runtime bundle; launch new version without disturbing old one.
- [x] 5. Final verification and separate self-review. The fresh reviewer request failed with HTTP 402 (credit limit); no independent reviewer verdict is claimed. Changes remain uncommitted and nothing was pushed.

## Ledger
- Baseline: clean `feat/pocket-groove`, core Java tests pass; new branch `feat/low-latency-audio`.
- Ruling: the user's go-ahead approves the previously presented architecture; continue inline without another implementation questionnaire — cost if wrong: implementation may need rework if “recommended direction” meant a Java-only engine.
- Ruling: use installed MSVC/CMake to build x64 native code; keep each new app in a versioned sibling folder — cost if wrong: developers without those tools need to install Build Tools, and a Windows-only app version can remain open beside older builds.
- Ruling: no physical key-to-speaker latency number without a reference signal and real key presses — cost if wrong: users still lack a quantified comparison, but claiming an unmeasured improvement would be misleading.
- Ruling: run the optional WASAPI smoke test in a separate Maven process from the JavaFX UI smoke test. Running both audio lifecycles in one JVM produced an intermittent WASAPI host error; isolated runs pass consistently — cost if wrong: this adds one Maven startup and might hide an interaction that only appears when both app lifecycles share a JVM.
- Verification: PortAudio core tests 1/1 passed, including mixed voices, pitch, loop boundaries, recording, bounded callback commands and queue/voice overload. Java tests: 10/10 pass in the packaging run; 2 WASAPI integration tests pass separately; UI integration passes with a real shared output, six pads, key learning, recording/loop, compact layout and output buffer reconfiguration. MP3 test uses a documented CC0 test-only fixture.
- Packaging: `scripts/build.ps1 -Package -UiTest -AudioSmoke` produced `dist/PocketGroove-0.2.1-windows-x64.zip` (about 35 MB) and the versioned app directory. Runtime, PortAudio JNI DLL, OpenJFX controls, MP3 decoder JARs, LGPL license/source JARs all verified in the ZIP. The test-only MP3 is not included.
- Runtime check: launched the final version and waited for its ready status. Process modules show both `dist/PocketGroove-0.2.1/PocketGroove/runtime/.../jvm.dll` and `app/pocketgroove_audio.dll`, with JavaFX UI active. Prior versioned folders were not replaced. No physical-key-to-speaker latency was measured.
- Final self-review: checked the changed UI/native/decoder/build/package paths against the plan focus list; bounded all callback work, verified invalid handles/PCM and rollback paths, confirmed output/load errors preserve project state, and checked the final ZIP/source notices. No further code changes indicated; this is the author's review, weaker than an independent review.
