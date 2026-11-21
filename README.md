# Pocket Groove

6키 매크로 키보드로 연주하는 작은 JavaFX 드럼 머신. 기존 키보드 설정을 유지하고, 앱 창에 포커스가 있을 때만 입력을 받습니다.

## 실행

Windows x64 배포 ZIP을 **전체 압축 해제**하고 `PocketGroove/PocketGroove.exe`를 실행하세요. Java 설치가 필요 없습니다. `app`과 `runtime` 폴더가 필요하므로 exe만 따로 옮기지 마세요.

## 사용법

1. 기본 키는 `1`–`6`입니다. 각 패드의 **키** 버튼을 클릭하고 현재 매크로 키보드의 키를 눌러 연결하세요. Ctrl/Alt/Shift 조합도 학습할 수 있습니다. Esc는 취소입니다.
2. 패드 클릭 또는 키 입력으로 연주합니다. 누르고 있는 동안의 자동 반복 입력은 무시합니다.
3. **소리 변경**에서 WAV(PCM 권장), MP3, AIFF를 선택합니다. 파일당 최대 16 MB이며, 전체 곡보다 짧은 샘플에 적합합니다. 지원 여부는 JavaFX의 실제 디코더에 따릅니다.
4. 녹음 길이를 1·2·4·8마디 중 선택하고 **녹음 시작**을 누릅니다. 즉시 녹음하며 끝나면 자동 반복합니다. **녹음 끝내기**를 누르면 다음 온전한 박까지 반복 길이를 맞춥니다. 새 녹음은 기존 연주를 대체합니다.
5. 템포는 40–240 BPM, 피치는 ±12반음입니다. 녹음 중 템포는 고정됩니다. 피치는 샘플 속도 방식이므로 길이도 함께 달라집니다.
6. **비트 저장**으로 `.mdrum` 파일을 저장합니다. 사용한 소리·키·연주·템포·피치를 함께 담으므로 다른 PC에서도 열 수 있습니다. 출력 음량은 프로젝트에 저장하지 않습니다.

녹음은 패드 이벤트 기록이며 마이크 녹음이 아닙니다. WAV/MP3 내보내기, 오버더빙, 메트로놈, 장치 전용 HID 입력은 이 버전에 포함하지 않습니다. 포커스가 없어도 시작한 루프는 계속 재생되지만 새 키 입력은 받지 않습니다.

## 매크로 키보드

기기 설정을 수정하거나 HID 출력 명령을 보내지 않습니다. 일반 키보드의 같은 키도 반응합니다. 하나의 키가 여러 문자를 보내는 매크로나 OS가 먼저 처리하는 단축키(예: Win 키, Alt+F4), 미디어 키는 앱이 안정적으로 패드 하나에 대응시키지 못할 수 있습니다. 실제 키 입력 형태는 장치에서 확인해야 합니다.

SDTech Options의 WebHID 조사 결과는 [docs/hid-investigation.md](docs/hid-investigation.md)를 참고하세요.

## 빌드

Windows, 재배포 가능한 JDK 17 이상(Temurin 권장), PowerShell이 필요합니다. Maven이 없으면 검증된 배포 파일을 프로젝트의 `.tools`에 내려받습니다.

```powershell
$env:JAVA_HOME = 'C:\path\to\temurin-jdk-17'
.\scripts\build.ps1              # 테스트 + JAR
.\scripts\build.ps1 -Package     # 테스트 + exe + 런타임 + ZIP
.\scripts\build.ps1 -UiTest      # 데스크톱 세션에서 JavaFX 통합 테스트 + 화면 캡처
```

개발 중 실행: `mvn javafx:run` (Maven 부트스트랩 시 `.\.tools\apache-maven-3.9.9\bin\mvn.cmd javafx:run`). 샘플 재생성: `python scripts/generate_samples.py`.

GitHub Actions는 Windows 빌드 ZIP을 artifact로 제공합니다. GitHub Release 게시 및 코드 서명은 자동으로 수행하지 않습니다. 릴리스에는 ZIP과 해당 소스 버전·라이선스를 함께 제공하세요. 아직 서명하지 않은 exe입니다.

## 구조

- `DrumApp`: JavaFX 화면, 입력, 녹음
- `LoopPlayer` / `Beat`: UI 렌더링과 분리된 반복 시계, 데이터와 검증
- `Sounds`: 다중 음원 재생 및 파일 로드
- `ProjectIO`: 크기 제한이 있는 ZIP 프로젝트, 임시 파일을 이용한 저장
- `src/main/resources/drum.css`: 화면 스타일

앱 및 기본 샘플은 MIT. 런타임·JavaFX·폰트 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
