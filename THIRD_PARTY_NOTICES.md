# Distribution notices

- Application source and the seven procedurally generated WAV files: MIT, see LICENSE. The samples were synthesized by `scripts/generate_samples.py`, not downloaded or copied from recordings.
- OpenJFX 21.0.9+1 controls/graphics: GPL v2 with Classpath Exception and component notices in `licenses/javafx/` (distribution: `app/licenses/javafx/`). This release no longer bundles JavaFX Media. Source: https://github.com/openjdk/jfx21u/tree/21.0.9%2B1 .
- Bundled Java runtime: Eclipse Temurin / OpenJDK, GPL v2 with Classpath Exception and third-party notices. Complete applicable module notices are in `runtime/legal/`. The runtime version and source revision are recorded in `runtime/release`. This local build uses Temurin 17.0.16+8; corresponding source: https://github.com/adoptium/jdk17u/tree/jdk-17.0.16%2B8 . CI may use a newer patched Temurin 17; consult that build's `runtime/release` and https://github.com/adoptium/temurin17-binaries/releases for its source bundle.
- PortAudio 19.7.0: permissive PortAudio license in `licenses/PORTAUDIO-LICENSE.txt`. The build fetches the tagged upstream source with a pinned SHA-256 and links it into the app's `pocketgroove_audio.dll`. Upstream source: https://github.com/PortAudio/portaudio/tree/v19.7.0 .
- MP3 decoding (MP3SPI 1.9.5.4, JLayer 1.0.1.4, Tritonus Share 0.3.7.4): LGPL 2.1. License text: `licenses/LGPL-2.1.txt`; corresponding version-matched source JARs: `licenses/sources/` (also in the distribution at `app/licenses/sources/`). The JARs remain separate in `app/lib`, so users may replace them with compatible builds. Upstream: https://github.com/pdudits/soundlibs .
- MP3 encoding (jump3r 1.0.6, a Java port of LAME 3.98.4): LGPL 2.1. License text: `licenses/LGPL-2.1.txt`; version-matched source JAR: `licenses/sources/jump3r-1.0.6-sources.jar` (also in the distribution). The JAR remains separate in `app/lib`, so users may replace it with a compatible build. Upstream: https://codeberg.org/RaphiMC/jump3r .
- Barlow SemiBold by Jeremy Tribby: SIL Open Font License 1.1. `src/main/resources/fonts/Barlow-OFL.txt` (also included in the application JAR and distribution app folder). https://github.com/google/fonts/tree/main/ofl/barlow
- Nanum Gothic by NHN Corporation: SIL Open Font License 1.1. `src/main/resources/fonts/NanumGothic-OFL.txt` (also included in the application JAR and distribution app folder). https://github.com/google/fonts/tree/main/ofl/nanumgothic
- Maven and JUnit are build/test tools and are not included in the application bundle.
- The CC0 MP3 used only by tests is documented in `docs/audio-fixtures.md`; it is not included in the EXE.
- SDTech Options is not part of this repository or distribution. Its installation was inspected read-only to investigate WebHID support. No proprietary code or assets are included.

User-imported audio belongs to its respective rights holders. A saved `.mdrum` contains those audio files; only share audio you have permission to distribute.
