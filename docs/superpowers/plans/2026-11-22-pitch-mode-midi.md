# 트랙 피치 방식·MIDI 내보내기

> 0.6.2. 트랙별 Pitch Shift/Varispeed 선택과 Standard MIDI File 내보내기.

**Goal:** Let a track choose time-preserving pitch shifting or varispeed from a right-click menu, add MIDI export with a file-explorer save location, and verify the MIDI is structurally valid.

## Changes
- `Beat.Track` gains `PitchMode` (`VARISPEED`, `PITCH_SHIFT`), schema v6; old projects default to Varispeed.
- Right-click a track header → **Pitch Shift (음높이만)** / **Varispeed (속도 변경)** radio menu. Header shows `PITCH` or `VAR`.
- Native mixer: Varispeed keeps the old step-ratio path. Pitch Shift uses a streaming granular overlap-add reader (triangular crossfade, adaptive grain size, tail clamp) so the output duration stays the source duration while pitch changes.
- MP3 offline renderer implements the same two modes.
- MIDI exporter: format 1, 480 PPQ, conductor tempo/time signature, one event track per app track, GM drum channel 10, base-note map (Kick 36, Snare 38, closed hat 42, open hat 46, clap 39, tom 45, percussion 49), note/velocity, selected bar range and repeated loop pattern.
- Export dialog: format combo (WAV/MP3/MIDI), name, range `전체`/`1-10`/`3`, and a **찾아보기…** button that opens the native folder explorer; WAV is the exact default.
- Previous hidden post-OK file chooser was replaced by a visible location control so the user can always confirm/change the folder.

## Verification
- MIDI files are parsed back with `javax.sound.midi.MidiSystem.getSequence`: resolution, track count, tempo 600000 at 100 BPM, note-on/off counts, requested-range length and pitch offset.
- Native tests: +12 Varispeed makes a 100-frame sample 50 frames; -12 makes it 200; Pitch Shift keeps at least 95 of 100 frames.
- Java integration tests cover mode persistence, native mode install/command, plus all prior MP3/dialog/UI tests.
- Packaged as `0.6.3`; screenshots regenerated.

## Known limits
- Pitch Shift is a granular time-domain shifter; it preserves duration but can create grain artifacts on long tonal samples. Varispeed remains the clean choice for classic sampler behavior.
- MIDI is drum-channel oriented; per-step pitch becomes a drum-note offset. A DAW drum instrument's mapping determines the audible result.
- MP3 still cannot force the receiving DAW's project tempo.
