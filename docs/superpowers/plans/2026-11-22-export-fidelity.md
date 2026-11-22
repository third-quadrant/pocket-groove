# 내보내기 정합성·세밀 피치·스크롤 보강

> 0.6.0 후속. 사용자 DAW에서 앱과 같은 BPM/마디로 재생되는 것을 최우선으로 검증하고, 요청된 조작감을 다듬는다.

**Goal:** Keep export timing sample-consistent with the app's 48 kHz engine, make every bar range explicit, allow direct save-folder entry, make the per-step pitch snap (and Ctrl-fine), show the current pitch, and make horizontal navigation usable from the track-header area.

## Changes
- Export renders at **48 kHz**, the same rate as the native WASAPI mixer. MP3 duration tests use exact BPM timing (100 BPM, 1 bar 2.4s, 4 bars 9.6s) and assert the decoded sample rate.
- MP3 gets ID3 `TBPM` and title; the export dialog states the BPM that the receiving DAW project must use. MP3 does not carry/apply a DAW's project tempo, so this is explicit rather than silently wrong.
- Export dialog selects **MP3 or MIDI**, then a native file explorer chooses the final name/location. MIDI is a format-1 SMF with tempo, track names, velocity and GM drum channel 10.
- Timeline horizontal navigation: larger scrollbars, Shift+wheel over the whole workspace, and **right/middle-drag panning** even when starting over the track headers. A UI test increments the actual scrollbar and checks its value moves.
- Step pitch: integer snap by default, 0.1-semitone steps with Ctrl; display `현재 +N` in the pitch lane and `NOTE +N` in the header, plus a label next to the selected note. Fractional values persist and export.
- Pitch duration: native tests confirm +12 semitones halves the emitted sample length and -12 doubles it. This is varispeed behaviour, not a sign bug; the +/- display makes direction explicit.

## Verification
- Java: regular tests including MP3 range/duration/sample rate/ID3, MIDI SMF parsing, pitch snap/fraction, schema/mode compatibility and dialog range parsing.
- UI: 1 test covering BARS/`+ 바`, scrollbar increment, save path, per-step pitch, trim preview, export dialog defaults and responsive screenshots.
- WASAPI: 2 tests; native CTest: 1/1 including varispeed duration and pitch-shift duration assertions.
- Package: `0.6.2` rebuilt after the changes; previous folders preserved.

## Known limits
- MP3 cannot force a DAW project's tempo. The file duration is exact and the BPM tag is written, but the DAW project BPM must match (the dialog and status state this).
- A receiving DAW may apply its own MP3 decoder delay compensation; sample-exact DAW placement is best with a WAV, which is not offered in this pass.
- MIDI uses GM drum channel 10; pitched drum notes depend on the receiving drum instrument's mapping.
- Per-bar independent melodies remain out of scope; BARS repeats the one loop pattern.
