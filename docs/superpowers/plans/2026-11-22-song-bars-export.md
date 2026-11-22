# 곡 마디·MP3 범위 내보내기 Implementation Plan

> 0.5.0 위에 얹은 0.6.0 변경. 루프 1회 내보내기를 프로젝트 마디 전체/선택 범위로 확장한다.

**Goal:** Make the project length explicit in bars, add bars by repeating the unchanged loop pattern, export MP3 through any requested bar range with the project BPM/pitch/velocity, and choose both the file name and bar range inside the app.
**Architecture:** `Beat.Project` gains `bars` and schema 5; the timeline is bounded by that count; `Mp3Exporter` repeats loop events across the requested absolute bars; `Mp3ExportDialog` collects a name and `전체`/`1-10`/`3` range; MP3 gets an ID3v2 title/BPM tag.

## Constraints
- Existing 0.1–0.5 projects load; missing bars default to `max(16, loop bars)`.
- Added bars are exact copies of the current loop; per-step pitch applies to every repeated bar.
- Export defaults to `전체` (all project bars), and range values never silently shrink unless invalid input is rejected.
- Export timing must follow BPM: 4 beats per bar, absolute bars from the project start.
- Name and range are entered in-app; no file explorer.
- MP3 players/DAWs do not inherit the app's project tempo automatically; the dialog shows BPM and the tag records it.

## Tasks
- [x] 1. Project schema 5 with `bars`, validation, backward defaults, serializer.
- [x] 2. Timeline bounded by `bars`, horizontal scroll range, clip clamping, BARS spinner and `+ 바`.
- [x] 3. MP3 renderer repeats pattern events across selected bars; exact duration test at 100 BPM.
- [x] 4. In-app export dialog with name, `전체` default, `1-10`/`3` parsing, folder display, no explorer.
- [x] 5. ID3 title/TBPM tag and decode-back tests.
- [x] 6. UI test: BARS + `+ 바`, default `전체`, dialog screenshot, previous features.
- [x] 7. Package 0.6.0, screenshots, docs.

## Ledger
- Ruling: bars are a project-level repeat count rather than independent per-bar patterns. This matches the user's “copy the unchanged repeated form” wording and keeps the existing one-loop editor. Cost if wrong: different melodies per bar require a future arranger.
- Ruling: old files without bars default to 16 (or the loop's own bar count if larger), so opening an old one-loop project immediately exports a finite song instead of one bar.
- Ruling: MP3 timing is exact and a TBPM tag is written, but the receiving DAW's project tempo still controls playback. Cost if wrong: users expecting MP3 to set the DAW tempo will still need to set BPM manually.
- Verification: 40 regular Java tests (incl. WAV exact frames, MP3 range/duration/sample-rate, MIDI SMF parsing, dialog range/folder parsing, pitch mode), 1 JavaFX UI test, 2 WASAPI tests, 1 native CTest. UI checks BARS and `+ 바`, scrollbar thumb/increment, default `전체`, trim preview, save overwrite, macro input, pitch mode and per-step pitch.
- Packaging: `dist/PocketGroove-0.6.3/`, `dist/PocketGroove-0.6.3-windows-x64.zip`; older distribution folders preserved.
