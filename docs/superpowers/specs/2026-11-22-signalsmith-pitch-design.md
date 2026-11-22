# Signalsmith Stretch 기반 악기 피치 엔진 설계

## 목표

기존 드럼 중심 재생을 유지하면서 WAV/MP3/AIFF 샘플을 MIDI 노트 기반 악기로 사용한다. 고품질 피치는 Signalsmith Stretch 1.3.2로 사전 렌더링하고, 실시간 재생과 WAV 렌더링이 같은 캐시 PCM을 사용한다. 오디오 콜백에서는 파일 I/O와 DSP를 하지 않는다.

## 확정 사항

- 한 악기의 한 스텝에서 여러 음(화음)을 동시에 지원한다.
- 기존 드럼은 GM Drum Note를 기본값으로 유지한다.
- 새로 추가하는 악기의 기본 MIDI Note는 C4(60)이다.
- 기존 MP3 **가져오기**는 유지하고 MP3 **내보내기**는 제거한다.
- 기존 프로젝트는 손실 없이 읽고, 기존 Varispeed 드럼 동작은 기본으로 유지한다.

## 접근

**A. 사전 렌더링 캐시 + 네이티브 재생 (승인됨)**

1. Java가 원본 PCM을 디코딩한다.
2. 백그라운드 실행기가 `(악기, 반음×10, formant)` 조합을 JNI로 Signalsmith에 넘겨 고정 길이 PCM을 만든다.
3. 완성된 PCM을 Java LRU 캐시와 네이티브 악기 뱅크에 넣는다.
4. 오디오 콜백은 완성된 PCM만 일반 속도로 읽는다.
5. WAV 렌더러는 같은 Java 캐시 PCM을 읽는다.

실시간 스트리밍(B)은 오디오 콜백 CPU와 지연 관리 위험이 커서 채택하지 않는다. 기존 그레인(C)은 품질 목표를 만족하지 못한다.

## Signalsmith 처리

- `SignalsmithStretch<float>`, `presetDefault(channels, sampleRate)`.
- `setTransposeSemitones(semitones)`, formant 보존 시 `setFormantFactor(1, true)`.
- `outputSeekLength(1.0)`보다 짧은 샘플은 끝을 무음으로 패딩한 뒤 `exact()`로 처리하고 원래 길이만 취한다.
- 원본과 같은 프레임 수를 출력해 이벤트 시작 시점과 프로젝트 길이를 바꾸지 않는다.
- 모노는 스테레오로 확장한다.
- 짧은 샘플의 시작/끝은 길이를 유지한 채 무음 패딩과 트리밍으로 잔여 샘플을 제거한다.

## 데이터 모델

`Beat.Track` 추가 필드:

- `baseMidiNote` 0–127. 기존 드럼: GM note, 새 악기: 60.
- `pitchMode`: `VARISPEED` 또는 `PITCH_SHIFT`.
- `formantPreserve`: bool.
- `playback`: 이후 단계에서 확장할 설정 묶음. 1차에는 상수/기본값.

스텝 화음:

- 기존 `Hit(track, beat, velocity, pitch)`를 유지한다.
- 한 스텝 셀은 같은 beat에 여러 Hit를 가질 수 있다.
- `Pattern`의 셀 API는 `List<Note>`(pitch/velocity)를 다루도록 확장하되, 기존 단일 노트 프로젝트는 그대로 읽는다.
- 화음 편집 UI는 스텝 선택 후 노트 추가/삭제와 각 노트 피치·velocity를 제공한다.
- 파일 스키마는 hit 반복만으로 화음을 표현하므로 기존 `.mdrum`과 호환된다.

## 피치/캐시

- MIDI note = `baseMidiNote + project.pitch + track.pitch + step.pitch`.
- 캐시 키 = `(Track identity, tenthSemitone, formant)`.
- 정수·소수(0.1) 반음을 모두 지원한다.
- LRU 상한 기본 256 MB. 상한을 넘으면 오래된 variant를 제거한다.
- 루프/트랙 변경 시 필요한 variant를 백그라운드에서 준비한다.
- 준비 중에는 이전 캐시로 재생하거나, 새 재생은 “피치 준비 중” 상태로 대기한다. 오디오 콜백은 절대 기다리지 않는다.
- 동일 PCM/피치 조합은 재사용한다.

## JNI 경계

- `native/pitch_render.*`: Signalsmith를 감싼 고정 길이 렌더러.
- JNI `nRenderPitch(float[] interleavedStereo, int sampleRate, double semitones, boolean formant)` → `float[]`.
- 캐시 생성만 JNI를 호출한다. 오디오 콜백에서는 JNI/DSP/파일 I/O가 없다.
- 네이티브 악기 뱅크는 스트림이 정지된 상태에서 원자적으로 교체한다.

## 검증

- C4 기준 C3~C5, 12반음, 옥타브.
- 모노/스테레오, 짧은 효과음/긴 음원, 피아노·현악기·드럼.
- 화음(같은 트랙 같은 시점 복수 음).
- 실시간 재생과 WAV의 동일 캐시 PCM 비교.
- 기존 드럼/Varispeed/프로젝트 로드 회귀.
- CPU/메모리 상한과 캐시 제거.
- MP3 가져오기 회귀, MP3 내보내기 제거 확인.

## 비목표

- 오디오 콜백에서 실시간 Signalsmith 스트리밍.
- MP3 내보내기.
- 기존 프로젝트의 드럼을 자동으로 Pitch Shift로 전환.
