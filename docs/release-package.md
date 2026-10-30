# 출품 APK 생성

`exportRelease`는 HTTPS 서버 주소와 서명 설정을 확인하고 release APK를 생성한다
Android SDK의 apksigner로 서명을 검증한 뒤 APK·인증서 해시와 빌드 설정을 함께 내보낸다

## 서명 설정

새 앱의 배포 키는 다음 명령으로 생성한다
이미 배포한 앱은 기존 키와 서명 설정을 사용한다

```sh
python3 tools/release/create-signing.py --java-home "$JAVA_HOME"
```

- `JAVA_HOME`은 사용하는 JDK 위치로 지정
- macOS 기본 보관 위치: `~/Library/Application Support/modu-home/signing`
- Linux 기본 보관 위치: `~/.local/share/modu-home/signing`
- `--directory`로 저장소 밖의 다른 새 폴더 지정
- RSA 4096비트·유효기간 10000일의 PKCS12 키와 keystore.properties 생성
- 공개 인증서와 SHA-256·SHA-1 지문은 certificate.json·modu-home-release.cer에 보관
- 폴더는 0700·파일은 0600 권한으로 생성하며 기존 폴더와 키는 덮어쓰지 않음
- 생성된 서명 폴더는 별도 암호화 저장소에도 백업

키 저장소와 UTF-8 `keystore.properties` 파일을 저장소 밖에 둔다

```properties
storeFile=modu-home.jks
storePassword=키_저장소_비밀번호
keyAlias=키_별칭
keyPassword=키_비밀번호
storeType=JKS
```

- `storeFile`의 상대 경로는 서명 설정 파일이 있는 디렉터리에서 해석
- `storeType`은 키 저장소 형식에 맞추고 생략하면 JKS 사용
- 서명 값은 파일에서 읽으며 명령행이나 내보낸 메타데이터에 비밀번호를 넣지 않음
- 기존 앱을 업데이트할 때는 같은 서명 키 사용

## 생성

서버는 [HTTPS 실행 구성](../tools/service-stack/README.md)으로 준비하고 실제 주소를 지정한다
두 API를 같은 서버로 운영하면 두 주소 속성에 같은 HTTPS 주소를 넣는다
주소에는 인증정보·경로·쿼리·프래그먼트를 넣지 않는다

```sh
./gradlew :app:exportRelease \
  -PpackageRelease=true \
  -ProutingBaseUrl=https://routing.example \
  -PproposalRelayBaseUrl=https://relay.example \
  -PreleaseSigningFile=/srv/modu-home/keystore.properties
```

위 도메인과 파일 경로는 설정 예시이며 실제 값으로 바꾼다
서버 주소·서명 항목이 빠지거나 주소가 HTTPS 원점 형식이 아니면 생성을 중단한다
음성은 기본적으로 비활성이며 사용하는 차량의 SDK 권한을 확인한 환경에서 `-PenableVehicleVoice=true`로 지정한다

## 결과 확인

- APK: `app/build/outputs/distribution/modu-home-release.apk`
- 메타데이터: `app/build/outputs/distribution/release.json`
- 메타데이터에는 앱 ID·버전·APK SHA-256·서명 인증서 SHA-256·서명 검증 결과·서버 주소·음성 설정 기록
- 키 저장소 경로·별칭·비밀번호·인증서 소유자 이름은 메타데이터에서 제외

생성 후 실제 HTTPS 서버와 Pleos 기기에서 계획 비교·제안 수신·내비 안내·재실행을 확인한다
서버 접속과 차량 SDK 권한도 이 설치 검증에서 함께 확인한다
제출 파일명과 저장소의 APK 위치는 공식 제출 안내에 맞춰 정리한다

일반 개발용 `assembleDebug`와 빌드 확인용 `assembleRelease`는 기존 설정으로 실행한다

## 서명이 다른 APK의 AVD 검증

debug 키와 배포 키의 서명이 다르면 기존 앱 위에 업데이트할 수 없다
기존 데이터가 있는 AVD에서는 앱을 교체하기 전에 전체 스냅샷과 별도 앱 백업을 확보한다
이 절차는 Pleos AVD에서 사용하며 실물 기기의 설치 절차와 구분한다

- `adb devices -l`, AVD 이름과 현재 사용자 ID를 확인하고 모든 명령에 같은 serial 지정
- 실행 중인 앱을 종료한 뒤 현재 APK, 앱 데이터 전체와 패키지 권한을 저장소 밖에 보관
- debug 앱의 데이터 백업은 `run-as io.moduhome.app --user 10` 순서로 실행
- AVD 전체 스냅샷 저장이 끝나고 목록에 표시된 것을 확인한 뒤 서명 교체 진행
- 배포 APK의 인증서 지문과 비디버그 플래그를 확인하고 별도 시험 데이터로 실행

```sh
adb -s emulator-5554 emu avd snapshot save modu_home_before_release
adb -s emulator-5554 emu avd snapshot list
```

설치 후에는 `dumpsys package io.moduhome.app`의 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 허용 여부와 실제 SDK 조회·경로 요청을 함께 확인한다
최근 목적지 불러오기, 운영 HTTPS 비교, 방문 순서 선택, 내비 안내, 정차 완료 입력과 앱 재실행을 차례로 확인한다
AVD의 완료 버튼 시험과 실제 이동·방문 완료는 기록에서 구분한다

검증을 마치면 저장한 스냅샷을 불러오고 원래 APK 해시, 앱 데이터 파일별 해시와 권한을 백업과 대조한다
서명이 같은 배포 APK의 업데이트 설치 결과와 기존 debug 환경의 복원 결과도 따로 기록한다

```sh
adb -s emulator-5554 emu avd snapshot load modu_home_before_release
```

기본 내비의 배경 지도는 [지도 진단 기준](native-map-diagnostics.md)에 따라 별도로 확인한다

서명 설정은 [Android 앱 서명](https://developer.android.com/studio/publish/app-signing), 검증은 [apksigner](https://developer.android.com/tools/apksigner) 참고
