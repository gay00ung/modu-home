# 차량 내비 설정과 실행

가는김에에서 선택한 방문 순서를 Pleos 차량 내비로 전달한다
집은 최종 목적지로 두고 픽업·장보기 중 선택한 볼일을 최대 두 곳의 경유지로 연결한다

## 실행 환경

- Pleos Connect Emulator 2.1.0, Android API 34
- Mac Apple Silicon은 ARM64 시스템 이미지 사용
- Pleos Connect 기기 프로필, RAM 4GB, Cold boot
- 한국 기준 시각으로 계획하려면 AVD 시간대를 Asia/Seoul로 설정
- NaviHelper SDK 3.0.5와 공식 Maven 저장소 사용

[Pleos Emulator 설치 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)의 시스템 이미지 저장소를 SDK Update Sites에 추가한 뒤 AVD를 생성한다
SDK 라이선스에 동의한 환경에서 설치한다

차량 AVD는 화면을 사용하는 사용자와 시스템 사용자가 다를 수 있다
단말 목록과 현재 사용자 ID를 확인하고 같은 사용자에 앱을 설치·실행한다

```sh
adb devices -l
adb -s emulator-5554 shell am get-current-user
```

현재 사용자가 10인 경우

```sh
adb -s emulator-5554 install -r -t --user 10 app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start --user 10 -n io.moduhome.app/.MainActivity
```

## 장소 선택

- 차량 내비에서 필요한 장소를 검색하거나 즐겨찾기에 저장
- 모두 집으로의 차량 내비 영역에서 집·픽업·장보기 장소 선택
- 저장 장소와 최근 목적지는 NaviHelper 응답으로 불러오기
- 선택한 장소의 이름·주소·좌표·장소 ID를 계획과 함께 기기 안에 저장
- 장소명을 직접 바꾸면 기존 좌표 연결 해제
- 픽업 장소를 다시 선택하면 수령 준비 여부도 다시 확인

차량 내비의 출발지는 현재 차량 위치를 사용한다
계획 비교에 표시하는 이동·보행·체류시간은 입력 화면에서 설정한 예상값을 사용한다

## 경로 전달과 확인

- 계획 비교에서 선택한 순서를 그대로 경유지 목록으로 변환
- 첫 경유지는 `FIRST`, 두 번째 경유지는 `SECOND`로 지정하고 집을 최종 목적지로 전달
- `requestRouteWithWaypoints()` 호출 후 목적지·경유지·안내 상태를 다시 조회
- 차량에서 반환한 경유지 목록의 순서를 유지해 계획과 비교
- 두 번의 연속 조회에서 목적지·경유 순서가 일치하고 길안내 중일 때 진행 화면으로 전환
- 전송 오류나 응답 시간 초과는 확정 화면에 표시하고 재시도 가능
- 진행 화면의 차량 내비 다시 확인으로 현재 안내 경로 조회

장소 ID가 양쪽 응답에 있으면 제공자와 ID를 우선 비교한다
장소 ID가 없는 경우에는 좌표의 25m 이내 오차로 확인한다
AVD에서 경유지 좌표를 0,0으로 반환하는 경우는 좌표 없음으로 처리하며 요청 좌표로 채우지 않는다

진행 중인 계획을 복원할 때는 차량 내비를 다시 조회한다
계획 기록을 중단해도 차량 내비의 길안내는 유지한다
휴대폰에서는 입력·비교·볼일 기록 흐름을 사용할 수 있다

## SDK 권한과 수명

- `pleos.car.permission.NAVI_ROUTE`: 저장 장소·최근 목적지·안내 정보 조회
- `pleos.car.permission.NAVI_ROUTE_SEARCH`: 목적지와 경유지를 포함한 경로 요청
- 자동차 환경에서 SDK를 초기화하고 화면이 해제되면 리스너 제거 후 SDK 해제
- SDK 타입은 `data/navigation` 안에서 다루고 화면과 계산 코드에는 전달하지 않음

- [SDK 의존성 설정](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/importing-sdk)
- [경유지를 포함한 경로 요청](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/route-with-waypoints)
