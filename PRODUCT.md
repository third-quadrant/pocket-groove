# Pocket Groove

<!-- impeccable:product-schema 1 -->

## Platform

Windows desktop (JavaFX; not a web surface).

## Stack

User-selected Java and JavaFX CSS, with a small C++/JNI mixer and PortAudio WASAPI engine. Self-contained Windows executable with a bundled redistributable runtime.

## Users and purpose

A macro-keyboard owner wants an immediate finger-drum surface that grows beyond six pads. They assign their own keys and sounds, arrange a looping pattern by track, edit it on a rhythmic grid, and save the whole kit. The app must work without changing the keyboard's own configuration.

## Constraints

Focus-only keyboard input. Ordinary JavaFX key events rather than device-exclusive HID. General keyboards with the same keys also work, but must not distinguish physical devices. Preserve six-track project compatibility. No publishing to GitHub automatically. Avoid decorative controls that do not change audio or project data.

## Brand commitments

Clean but distinctive, dark professional music-production interface. One-loop pattern representation for this version. Product name delegated to implementation.

## Scope approved

Default 7 tracks with up to 32 dynamic tracks. Add/remove tracks with sound and key assignment. DAW timeline showing repeated per-track pattern clips with bar/beat grid, playhead, horizontal zoom/scroll and vertical track scrolling. 16th-note step sequencer with 1/2/4/8-bar loop lengths and per-hit velocity. Audio replacement with a waveform trim window and selected-range audition, tempo, global, per-track and per-step pitch for melodies, selectable per-track Pitch Shift/Varispeed, selected-track overdub recording (not microphone), a repeatable song length in bars, looping, portable project save/load, project undo/redo, sample-exact 32-bit float WAV and Standard MIDI export with an in-app format/name/range dialog and file-explorer location, a Korean file menu and `.mdrum` file association. WAV/AIFF/MP3 import remains available; MP3 export is not part of the product. The shipped default samples are original. Multi-pattern song arrangement is deferred.
