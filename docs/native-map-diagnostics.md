# 기본 내비 배경 지도 진단

경로선과 회전 안내가 표시돼도 도로·배경 지도가 정상이라는 뜻은 아니다
NaviHelper의 목적지·경유 순서·안내 상태와 기본 내비의 지도 이미지를 각각 확인한다

## 현재 확인 범위

2026년 10월 30일 Pleos Connect 2.1.0 ARM64·사용자 10에서 확인했다

- 영구 배포 키로 서명한 비디버그 앱에 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 허용
- 운영 HTTPS 비교와 선택한 방문 순서의 실제 내비 요청·완료 후 다음 구간 연결 확인
- 기본 내비 3.3.2-rc5-hotfix.3에서도 경로선·위치·회전 안내와 빈 배경이 함께 표시
- 기본 내비만 재실행한 새 관측에서 `Map onInitialized true` 1회, VNET 이미지 전송 로그 25회 확인
- 같은 관측에서 GL `0x501` 16회 확인
- 공개 ARM64 시스템 이미지 피드와 설치된 SDK Manager 메타데이터의 revision은 2

이미지 전송 로그는 실제 bitmap에 도로가 들어 있거나 수신측에서 디코드·합성을 마쳤다는 증거가 아니다
GL 오류 횟수만으로 지도 미표시 원인을 확정하지 않는다
배포 서명 앱의 SDK 동작은 확인했지만 배경 지도 정상 표시는 확인하지 못했다

## 추가 진단에 필요한 값

- 같은 요청의 bitmap 크기·형식·바이트 수·해시와 실제 이미지 내용
- 전송한 `ImageMapTileInfo`의 runtime `tileArea`
- VNET 수신 식별자·디코딩 결과와 화면 합성 영역
- 동일 요청을 연결할 수 있는 공급사 진단 빌드 또는 공식 진단 절차

현재 공개 NaviHelper API로 위 이미지 내부 값을 조회할 수 있는 경로는 확인하지 못했다
공급사에 재현 자료를 전달할 때는 공식 AVD·기본 내비 버전, 발생 시각, 화면과 비식별화한 오류·단계 요약을 사용한다
전체 네이티브 로그·인증값·타일 URL·사용자의 장소 데이터는 첨부하지 않는다

## 재검증 기준

- 입력을 바꾸지 않은 새 기본 내비 화면과 실제 안내 화면에서 도로·지명을 시각적으로 확인
- 앱 데이터·내비 캐시·주행 제한 설정을 보존하고 이전에 실패한 GPU 조합은 새 근거가 있을 때만 비교
- 지도 초기화·생성 이미지·전송·수신·디코드·합성을 같은 요청으로 대조
- 지도 정상 표시와 SDK 경로 반영을 각각 검증 결과에 기록

[공식 AVD 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml) · [에뮬레이터 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note) · [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
