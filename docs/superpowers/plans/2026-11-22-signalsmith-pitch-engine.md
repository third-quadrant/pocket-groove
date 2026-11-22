# Signalsmith 기반 피치 악기 Implementation Plan

**Goal:** 모든 WAV/MP3/AIFF 샘플을 MIDI 노트 기반 피치 악기로 연주하고, Signalsmith Stretch 1.3.2로 고품질 피치를 사전 렌더링해 실시간 재생과 WAV 내보내기가 같은 PCM을 쓰게 한다. 기존 드럼·프로젝트·MP3 가져오기는 유지하고 MP3 내보내기는 제거한다.

**Architecture:** `Beat.Track`에 기본 MIDI 노트·피치 방식·formant 설정을 더하고, `Beat.Hit`의 반복으로 같은 스텝 화음을 표현한다. Java 백그라운드 `PitchCache`가 `(트랙, 반음, formant)`별 PCM을 네이티브 Signalsmith로 만들고, 네이티브 믹서와 WAV `OfflineMix`가 같은 캐시 PCM을 읽는다. 오디오 콜백은 파일 I/O·DSP·캐시 생성을 하지 않는다.

**Tech Stack:** Java 17, JavaFX 21, JUnit 5, C++17, JNI, PortAudio WASAPI, CMake/MSVC, Signalsmith Stretch 1.3.2 + Signalsmith Linear 0.3.1 (둘 다 MIT, header-only).

**Spec:** `docs/superpowers/specs/2026-11-22-signalsmith-pitch-design.md`

**Global rules**

- 기존 `.mdrum` v1–v6는 손실 없이 읽는다. 기존 드럼은 `VARISPEED`, GM 드럼 노트, 기존 피치를 유지한다.
- MP3 **가져오기**는 유지한다. MP3 **내보내기**만 제거한다.
- 오디오 콜백 안에서 할당·파일 I/O·Signalsmith 호출·캐시 생성을 하지 않는다.
- 실시간 재생과 WAV 렌더링은 같은 `PitchCache` PCM을 쓴다. BPM·이벤트 시작·프로젝트 길이는 바꾸지 않는다.
- 새 비트는 항상 테스트를 먼저/같이 추가하고, 각 단계 종료 시 `scripts/build.ps1`로 전체 검증한다.

## Stage 1 — MP3 내보내기 제거, Signalsmith 빌드·JNI DSP 기반

### Task 1.1: MP3 내보내기 제거 (가져오기 유지)

**Files**
- Delete: `src/main/java/io/pocketgroove/Mp3Exporter.java`, `Mp3ExportDialog.java`
- Delete: `src/test/java/io/pocketgroove/Mp3ExporterTest.java`, `Mp3ExportDialogTest.java`
- Create: `src/main/java/io/pocketgroove/ExportDialog.java` (WAV/MIDI only)
- Create: `src/test/java/io/pocketgroove/ExportDialogTest.java`
- Modify: `DrumApp.java`, `DrumAppSmokeTest.java`, `OfflineMix.java`, `pom.xml`, `README.md`, `PRODUCT.md`, `DESIGN.md`, `THIRD_PARTY_NOTICES.md`
- Delete: `licenses/sources/jump3r-1.0.6-sources.jar`

**Steps**
1. `Mp3ExportDialog`를 `ExportDialog`로 이름만 바꾸고 `Format`에서 `MP3`를 뺀다. 확장자는 `.wav`/`.mid`만 남긴다.
2. `DrumApp.exportMp3()` → `exportAudio()`. `case MP3`와 MP3 상태 문구를 지운다. 파일 메뉴 연결을 바꾼다.
3. `pom.xml`에서 `jump3r` 의존성만 지운다. `mp3spi`는 남긴다.
4. `Mp3ExporterTest`/`Mp3ExportDialogTest`를 지우고, `ExportDialogTest`는 범위·이름·폴더·WAV/MIDI 경로만 검사한다. `DrumAppSmokeTest`는 `ExportDialog.show(...)`를 호출한다.
5. `OfflineMix` 주석에서 MP3를 뺀다. README/PRODUCT/DESIGN/THIRD_PARTY_NOTICES에서 MP3 내보내기·jump3r·인코더 고지를 지우고 “MP3는 가져오기만”으로 바꾼다.
6. 검증: `scripts/build.ps1` (MP3 가져오기 테스트 `SoundsTest` 유지, MP3 내보내기 테스트 0).

### Task 1.2: Signalsmith 1.3.2 + Linear 0.3.1 vendoring

**Files**
- Create: `native/third_party/signalsmith-stretch/signalsmith-stretch.h` (v1.3.2, pin `57b93f4e9206a089a45387eaa39bdc9f310d3308`)
- Create: `native/third_party/signalsmith-linear/{fft.h,stft.h}`, `native/third_party/signalsmith-linear/include/signalsmith-linear/{stft.h,fft.h,linear.h}` (v0.3.1)
- Create: `licenses/SIGNALSMITH-STRETCH-LICENSE.txt`, `licenses/SIGNALSMITH-LINEAR-LICENSE.txt`
- Modify: `native/CMakeLists.txt`, `THIRD_PARTY_NOTICES.md`, `README.md`

**Steps**
1. SHA-256를 기록한 vendoring note를 남긴다 (`native/third_party/README.md`).
2. `third_party/signalsmith-stretch` 헤더와 Linear 0.3.1 헤더를 그대로 복사한다. 수정하지 않는다.
3. CMake에 include 경로를 추가한다.
4. MIT 고지를 배포물에 넣는다.
5. 검증: C++ 컴파일 + `SignalsmithStretch<float> version[3] == {1,3,2}` 정적 확인.

### Task 1.3: 네이티브 고정 길이 피치 렌더러 + JNI

**Files**
- Create: `native/pitch_render.h`, `native/pitch_render.cpp`, `native/pitch_render_test.cpp`, `native/pitch_jni.cpp`
- Create: `src/main/java/io/pocketgroove/PitchRender.java`
- Create: `src/test/java/io/pocketgroove/PitchRenderTest.java`
- Modify: `native/CMakeLists.txt`

**Contract**

```cpp
// interleaved stereo, same input/output frame count
std::vector<float> renderPitchExact(const std::vector<float>& stereo,
                                    int sampleRate, double semitones,
                                    bool preserveFormants);
```

- `SignalsmithStretch<float> stretch(1)` 고정 시드로 재현 가능하게 한다.
- `presetDefault(2, sampleRate)`, `setTransposeSemitones(semitones)`.
- `preserveFormants`면 `setFormantFactor(1, true)`.
- `outputSeekLength(1)`보다 짧은 입력은 무음 패딩 후 `exact()`하고 원래 프레임 수만 취한다.
- 반음 범위 ±48, sampleRate 8–192 kHz, 입력은 짝수 길이. NaN/Inf는 거부한다.
- JNI: `PitchRender.nRenderPitch(float[], int, double, boolean) -> float[]`.
- JNI는 캐시 생성 경로에서만 호출한다. 오디오 콜백에서는 호출하지 않는다.

**Tests (C++)**
- 48 kHz 1초 261.63 Hz(C4) 사인을 ±12반음: 출력 길이 동일, 지배 주파수 ≈ 130.8/523.25 Hz (오차 3%).
- 0/±1/±7/±12/±24반음 12단계: 길이 동일, 유한값, 0이 아닌 에너지, 주파수 비율 오차.
- 옥타브(±12)와 12반음 전체.
- 모노 입력을 스테레오로 확장한 L/R 동일, 스테레오 위상 반전 입력 보존.
- 100프레임 짧은 효과음과 5초 긴 음원: 길이 동일, 유한값.
- formant 보존 on/off: 길이 동일, 유한값, 둘 다 0이 아닌 에너지.
- 고정 시드: 같은 입력 두 번 → 완전히 같은 출력.
- 0반음: 입력과 상관계수 ≥ 0.9.

**Tests (Java)**
- 네이티브 DLL이 있을 때만 실행(`Assumptions.assumeTrue`).
- C4→C3/C5 길이 유지와 비어 있지 않은 PCM.
- 잘못된 배열/범위/샘플레이트/반음 거부.

7. 검증: `scripts/build.ps1 -AudioSmoke`.

### Stage 1 완료 보고

- 변경 파일, CTest/Java 테스트 결과, MP3 가져오기 회귀, 남은 위험(짧은 샘플 시작 아티팩트, 캐시 미구현)을 보고한다.

## Stage 2 — MIDI 노트 데이터 모델과 화음

1. `Beat.Track`에 `baseMidiNote`(0–127), `formantPreserve`를 추가한다. 기존 생성자는 드럼 GM 노트·formant false로 채운다.
2. `ProjectIO` v7: 새 필드를 저장하고 v1–v6는 기본값으로 읽는다. `hits`는 그대로 반복 저장해 화음을 표현한다.
3. `Pattern`: 한 스텝의 `List<Note>` 조회·추가·삭제 API를 추가하고 기존 단일 노트 API는 유지한다.
4. `StepSequencerView`: 스텝 선택 후 노트 추가/삭제, 노트별 피치·velocity 편집. 기존 단일 노트 클릭은 그대로 동작한다.
5. `MidiExporter`: 트랙별 `baseMidiNote`를 기준으로 실제 노트를 내보내고, 드럼 트랙은 GM 채널 10을 유지한다.
6. 테스트: v1–v6 로드, v7 왕복, 같은 스텝 3음 화음, 기존 드럼 회귀, MIDI 노트 번호.

## Stage 3 — Java 피치 캐시와 백그라운드 렌더링

1. `PitchCache`: 키 `(트랙 identity, tenthSemitone, formant)`, 값 48 kHz stereo PCM.
2. `Sounds.Pcm`을 48 kHz로 리샘플하는 순수 Java 함수와 테스트.
3. 백그라운드 단일 실행기로 필요한 variant를 렌더링하고, 캐시 준비 상태를 노출한다.
4. LRU 256 MB 상한, 제거 테스트, 동일 PCM 재사용 테스트.
5. 준비 중에는 오디오 콜백이 기다리지 않는다. 준비되지 않은 히트는 건너뛰고 상태를 표시한다.
6. 테스트: LRU 제거, 동시 요청 중복 제거, 모노/스테레오, 0.1반음 키.

## Stage 4 — 네이티브 재생을 캐시 PCM으로 교체

1. `AudioEngine.install`/`nInstall`에 트랙별 캐시 PCM 배열과 기준 음을 넘긴다.
2. `Mixer` Voice는 PITCH_SHIFT일 때 캐시 PCM을 1.0 속도로 재생한다. Varispeed는 기존 경로 유지.
3. `PitchMode` 전환·트랙 피치·스텝 피치 변경 시 필요한 캐시를 다시 설치한다. 스트림 정지 후 원자 교체.
4. 화음: 같은 시점 복수 Hit를 각각 Voice로 재생하고 Voice 상한을 지킨다.
5. 테스트: C++ 믹서 계약, Java JNI 계약, 기존 드럼 회귀, 32트랙/64보이스 상한.

## Stage 5 — WAV 렌더링을 같은 캐시로 통합

1. `OfflineMix`가 `PitchCache` PCM을 읽고 실시간과 같은 피치 규칙을 쓴다.
2. WAV는 계속 32-bit float 48 kHz, 정확한 프레임 수.
3. 테스트: 같은 프로젝트의 실시간 캐시 PCM과 WAV PCM 비교, BPM·이벤트 시작·길이 불변.

## Stage 6 — UI, 문서, 패키징

1. 악기 설정 UI: 기본 MIDI 노트, Pitch Shift/Varispeed, formant 보존.
2. 준비 상태·메모리 사용량·캐시 오류 표시.
3. README/PRODUCT/DESIGN/THIRD_PARTY_NOTICES 최종 정리.
4. 전체 회귀, CPU/메모리 측정, 0.7.0 패키징.
