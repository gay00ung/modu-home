# 전시용 AVD

차량과 동승자 휴대폰을 별도 AVD로 실행한다
기존 AVD와 실물 기기는 조작하지 않는다

| 구분 | AVD 이름 | ADB 대상 | 시스템 이미지 |
| --- | --- | --- | --- |
| 차량 | Modu_Home_Exhibition | emulator-5554 | Pleos Connect API 34 ARM64 revision 2 |
| 휴대폰 | Modu_Home_Companion | emulator-5556 | Google Play API 36 ARM64 |

## 실행

프로젝트 루트에서 실행한다

```sh
python3 tools/exhibition/launch.py
python3 tools/exhibition/launch.py --profile phone
```

- Android SDK는 ANDROID_SDK_ROOT, ANDROID_HOME, local.properties 순으로 찾으며 macOS 기본 경로도 지원
- 차량은 공식 홈의 DrivingView와 기본 내비를 먼저 열어 앱 영역과 SDK 현재 위치 조회 준비
- 새로 부팅한 차량 AVD는 예시 위치 37.4127212, 127.094681 사용
- 실행 중인 AVD를 다시 열 때는 위치를 바꾸지 않음
- 설치된 모두 집으로 앱은 현재 Android 사용자에서 자동 실행
- 정해진 포트를 다른 AVD가 사용하면 해당 기기를 조작하지 않고 중단
- 각 AVD의 exhibition-userdata.img를 명시해 콜드 부팅 후에도 앱과 장소 설정 유지
- host GPU로 실행하고 Vulkan은 비활성화
- --stop은 선택한 전시용 AVD 이름이 일치할 때만 종료

준비 화면 스냅샷을 저장한 환경에서는 아래 명령으로 시연 시작 상태로 돌아간다
이 동작은 전시용 AVD의 스냅샷 저장 이후 변경만 되돌린다
일반 실행은 스냅샷을 복원하지 않고 마지막 앱 데이터를 사용한다

```sh
python3 tools/exhibition/launch.py --restore-ready
python3 tools/exhibition/launch.py --profile phone --restore-ready
```

## 새 환경

공식 SDK 안내에 따라 시스템 이미지를 설치한 뒤 새 전시용 AVD를 만든다
--create는 같은 이름의 AVD가 있으면 덮어쓰지 않는다
시스템 이미지나 서명 키는 소스에 포함하지 않는다

```sh
python3 tools/exhibition/launch.py --create
python3 tools/exhibition/launch.py --profile phone --create
```

APK는 각 대상의 이름을 확인한 뒤 업데이트 설치한다
기존 앱과 서명이 다르면 설치를 중단하고 데이터 보존 절차를 먼저 진행한다

```sh
adb -s emulator-5554 emu avd name
adb -s emulator-5554 install -r -g apk/modu-home_모두집으로_release.apk
adb -s emulator-5556 emu avd name
adb -s emulator-5556 install -r -g apk/modu-home_모두집으로_release.apk
```

앱이 차량·휴대폰 환경을 구분해 화면을 연다
전시 예시 장소는 사용자에게 속한 주소와 구분해 설정하고, 실제 경로 응답은 서버와 차량 SDK에서 조회한다
준비 화면에서 차량 연결을 종료한 뒤 각 AVD에 스냅샷을 저장한다

```sh
adb -s emulator-5554 emu avd snapshot save modu_home_exhibition_ready
adb -s emulator-5556 emu avd snapshot save modu_home_exhibition_ready
```

- 차량은 정차 상태에서 장소·조건 선택 → 비교 → 확정 → 차량 내비 시작 순서로 진행
- 두 볼일이 한도를 넘으면 픽업만·장보기만 대안을 조회하고 한도 유지 여부 확인
- 완료 버튼은 AVD의 정차 상태에서 직접 확인하며 실제 주행·수령·방문으로 기록하지 않음
- 도로 경로선과 안내 상태, 기본 내비의 도로·배경 지도 표시는 구분

[Android Emulator 실행 옵션](https://developer.android.com/studio/run/emulator-commandline) · [공식 개발 가이드](https://appdevchallenge.auto-hmg.io/guide/development)
