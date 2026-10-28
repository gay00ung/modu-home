# 모두 집으로

동승자의 귀가와 가는 길의 픽업·장보기를 한 번의 이동으로 묶는 Pleos Connect 차량용 앱

## 서비스 구성

| 기능 | 목적 |
| --- | --- |
| 함께 귀가 | 동승자의 목적지와 도보·환승 조건을 고려한 귀가 계획 |
| 가는김에 | 돌아가는 길에 픽업·장보기를 더하는 볼일 계획 |
| 함께 가는김에 | 동승자의 하차 순서와 볼일을 함께 정하는 이동 계획 |

- 함께 귀가는 운전자 1명과 동승자 최대 2명을 기준으로 설계
- 동승자와 이동할 때는 볼일 1건, 혼자 이동할 때는 최대 2건으로 범위 제한
- 추가시간은 우회 주행, 주차 후 보행, 매장 체류시간을 합산

자세한 사용 흐름과 계산 기준은 [제품 설계](docs/product.md) 참고

## 가는김에

- 차량 내비의 저장 장소·최근 목적지에서 집과 들를 곳 선택
- 현재 차량 위치와 도로 경로로 픽업·장보기 순서를 비교하고 집 도착 계획 표시
- 추가시간 한도·마감·수령 준비 여부와 보행·체류시간을 함께 계산
- 큰 글자와 프리셋 버튼으로 체류시간·추가시간 선택
- 선택한 순서를 NaviHelper로 전달하고 내비의 목적지·경유 순서·안내 상태 확인
- 볼일 완료와 집 도착을 따로 기록하고 재실행 시 진행 상태 복원
- QR·링크로 받은 볼일은 내용을 검토한 뒤 같은 종류의 기존 볼일에 반영

경로 비교는 OpenStreetMap 도로망을 사용하며 실시간 교통은 포함하지 않는다
개발 실행에는 [로컬 경로 서버](docs/routing.md)가 필요하다

## 개발 환경

| 항목 | 설정 |
| --- | --- |
| Application ID / namespace | `io.moduhome.app` |
| 최소 / 컴파일 / 대상 API | 28 / 37 / 36 |
| JDK | 17 또는 21 |
| Java 바이트코드 | 17 |
| Gradle | 9.4.1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin / Compose Compiler | 2.4.21 |
| Compose BOM | 2026.09.00 |
| NaviHelper SDK | 3.0.5 |
| SpeechToText SDK | 3.3.1 |
| 차량 실행 환경 | Pleos Connect Emulator 2.1.0 / API 34 |

## 실행

- Android Studio에서 저장소 루트 열기
- Gradle JDK와 Android SDK Platform 37 설정
- 로컬 SDK 경로는 `local.properties`에서 관리
- 차량 연동은 Pleos AVD에서 실행하고 RAM 4GB·Cold boot로 설정

```sh
./gradlew :app:assembleDebug
```

APK 경로: `app/build/outputs/apk/debug/app-debug.apk`

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
```

## 코드 구조

```text
app/src/main/kotlin/io/moduhome/app/
├── MainActivity.kt
├── ModuHomeApp.kt
├── core/
│   ├── planning/       # 주행·보행·체류 시간 계산
│   ├── shared/         # 동승자 하차와 볼일 순서 계산
│   ├── navigation/     # 목적지와 내비 요청 계약
│   ├── routing/        # 도로 경로 응답 검증
│   ├── transfer/       # 볼일 제안과 승인 규칙
│   ├── vehicle/        # 차량 화면 제한
│   └── voice/          # 음성 입력 계약과 명령 해석
├── data/               # SDK·도로 서버·QR·로컬 저장 어댑터
├── feature/
│   ├── home/           # 계획 상태와 상세 입력
│   ├── vehicle/        # 차량 전용 화면과 큰 선택창
│   └── transfer/       # 휴대폰 제안 화면
└── ui/theme/            # 차량·휴대폰 글자 크기와 색상
```

- `ModuHomeApp`에서 선택 상태를 관리하고 화면에 값과 콜백 전달
- 시간 계산은 Android와 분리해 `core/planning`에서 처리
- 내비 요청은 `NavigationGateway`를 통해 화면과 SDK 사이를 분리
- `data/navigation`에서 SDK 초기화·해제와 목적지·경유지 변환 처리

## 문서

- [제품 설계](docs/product.md)
- [차량 내비 설정과 실행](docs/vehicle-navigation.md)
- [차량 화면 기준](docs/vehicle-ux.md)
- [도로 경로 서버](docs/routing.md)
- [작업 규칙](AGENTS.md)
- [공모전 개발 가이드](https://appdevchallenge.auto-hmg.io/guide/development)
- [NaviHelper SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
