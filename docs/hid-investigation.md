# SDTech Options / WebHID 조사 (2026-11-22)

설치 경로 `C:\Program Files\SDTech Options`의 `resources/app.asar` 헤더와 일부 코드 문자열을 읽었다. 프로그램을 조작하거나 설정 파일을 수정하지 않았으며 장치에 보고서를 보내지 않았다.

## 확인한 사실
- Electron 프로그램의 HID 권한 처리와 `navigator.hid.requestDevice` 호출이 있다.
- 공급업체 정의 컬렉션 `usagePage 0xFF00`, `usage 0x0002`를 사용하는 기기 목록이 있다.
- 목록에는 `VID 0x36AE / PID 0x246F`가 있고 Windows의 현재 연결 장치 목록에도 같은 VID/PID가 있다. 이것만으로 해당 장치가 사용자가 말한 6키 장치라고 확정할 수는 없다.
- 코드에 `inputreport` 수신과 `sendReport(0, ...)` 전송이 있다. 이는 설정용 요청/응답일 수 있으며 독립적인 물리 키 누름 보고라고 확인한 것은 아니다.

## 결론
WebHID 설정 통신을 사용하는 것은 확인되었다. 다만 일반 키보드 컬렉션은 WebHID 보호 대상이고, JavaFX WebView가 Chromium WebHID를 제공한다고 가정할 수 없다. Java 전용 HID 라이브러리 또는 외부 Chromium 브리지로 공급업체 인터페이스를 여는 방식은 조사할 수 있지만, 장치가 물리 키 이벤트를 그 인터페이스로 제공하는지 먼저 확인해야 한다.

이번 구현은 승인된 일반 JavaFX 키 이벤트를 사용한다. 매크로 기기의 기존 키 설정을 유지하고, 앱 내부에만 패드-키 대응을 저장한다. 기기 전용 식별, OS 단축키 차단, 복합 매크로 분해는 하지 않는다.

참고: https://developer.chrome.com/docs/capabilities/hid#security-and-privacy
