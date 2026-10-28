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

- 출발지·집과 픽업·장보기 장소 입력
- 구간별 예상 주행시간, 왕복 도보와 매장 체류를 합산해 방문 순서 비교
- 추가시간 한도·마감·픽업 준비 여부를 확인하고 계획 확정
- 볼일 완료와 집 도착을 따로 확인하고 진행 중인 계획은 앱 재실행 시 복원
- 넓은 차량 화면은 두 열, 작은 화면은 세로 배치
- 차량 내비의 저장 장소·최근 목적지에서 집과 들를 곳 선택
- 확정한 방문 순서를 NaviHelper로 전달하고 내비의 목적지·경유 순서·안내 상태 확인

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
├── data/              # 이동 계획의 로컬 저장
├── core/
│   ├── model/          # 귀가 상황과 볼일 종류
│   ├── planning/       # 주행·보행·체류 시간 계산
│   └── navigation/     # 목적지·경유지와 내비 요청 인터페이스
├── feature/home/       # 입력·비교·확정·진행·완료 화면
└── ui/theme/           # 색상과 명암 테마
```

- `ModuHomeApp`에서 선택 상태를 관리하고 화면에 값과 콜백 전달
- 시간 계산은 Android와 분리해 `core/planning`에서 처리
- 내비 요청은 `NavigationGateway`를 통해 화면과 SDK 사이를 분리
- `data/navigation`에서 SDK 초기화·해제와 목적지·경유지 변환 처리

## 문서

- [제품 설계](docs/product.md)
- [차량 내비 설정과 실행](docs/vehicle-navigation.md)
- [작업 규칙](AGENTS.md)
- [공모전 개발 가이드](https://appdevchallenge.auto-hmg.io/guide/development)
- [NaviHelper SDK](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro)
