# Distribution notices

- Application source and the six procedurally generated WAV files: MIT, see LICENSE. The samples were synthesized by `scripts/generate_samples.py`, not downloaded or copied from recordings.
- OpenJFX 21.0.9+1: GPL v2 with Classpath Exception, with additional component notices. Complete applicable notices are included in `licenses/javafx/` (distribution: `app/licenses/javafx/`), copied from the matching source tag. Source: https://github.com/openjdk/jfx21u/tree/21.0.9%2B1 . Maven source JARs: https://repo.maven.apache.org/maven2/org/openjfx/ . Retain these notices when distributing; the Maven binary JARs do not contain the full legal directory.
- Bundled Java runtime: Eclipse Temurin / OpenJDK, GPL v2 with Classpath Exception and third-party notices. Complete applicable module notices are in `runtime/legal/`. The runtime version and source revision are recorded in `runtime/release`. This local build uses Temurin 17.0.16+8; corresponding source: https://github.com/adoptium/jdk17u/tree/jdk-17.0.16%2B8 . CI may use a newer patched Temurin 17; consult that build's `runtime/release` and https://github.com/adoptium/temurin17-binaries/releases for its source bundle.
- Barlow SemiBold by Jeremy Tribby: SIL Open Font License 1.1. `src/main/resources/fonts/Barlow-OFL.txt` (also included in the application JAR and distribution app folder). https://github.com/google/fonts/tree/main/ofl/barlow
- Nanum Gothic by NHN Corporation: SIL Open Font License 1.1. `src/main/resources/fonts/NanumGothic-OFL.txt` (also included in the application JAR and distribution app folder). https://github.com/google/fonts/tree/main/ofl/nanumgothic
- Maven and JUnit are build/test tools and are not included in the application bundle.
- SDTech Options is not part of this repository or distribution. Its installation was inspected read-only to investigate WebHID support. No proprietary code or assets are included.

User-imported audio belongs to its respective rights holders. A saved `.mdrum` contains those audio files; only share audio you have permission to distribute.
