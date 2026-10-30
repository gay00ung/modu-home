# 기본 내비 배경 지도 진단

경로선과 회전 안내가 표시돼도 도로·배경 지도가 정상이라는 뜻은 아니다
NaviHelper의 목적지·경유 순서·안내 상태와 기본 내비의 지도 이미지를 각각 확인한다

## 현재 확인 범위

2026년 10월 30~31일 Pleos Connect 2.1.0 ARM64·사용자 10에서 확인했다

- 영구 배포 키로 서명한 비디버그 앱에 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 허용
- 운영 HTTPS 비교와 선택한 방문 순서의 실제 내비 요청·완료 후 다음 구간 연결 확인
- 기본 내비 3.3.2-rc5-hotfix.3에서도 경로선·위치·회전 안내와 빈 배경이 함께 표시
- 기본 내비만 재실행한 새 관측에서 `Map onInitialized true` 1회, VNET 이미지 전송 로그 25회 확인
- 같은 관측에서 GL `0x501` 16회 확인
- 공개 ARM64 시스템 이미지 피드와 설치된 SDK Manager 메타데이터의 revision은 2

이미지 전송 로그는 실제 bitmap에 도로가 들어 있거나 수신측에서 디코드·합성을 마쳤다는 증거가 아니다
GL 오류 횟수만으로 지도 미표시 원인을 확정하지 않는다
배포 서명 앱의 SDK 동작은 확인했지만 배경 지도 정상 표시는 확인하지 못했다

## 실제 화면의 스타일 선택

10월 31일 원본과 분리한 읽기 전용 AVD에서 기본 내비를 새로 실행했다

- 기본 내비 프로세스에서 `Map StyleLoaded`·`Map MapLoaded`·`Map onInitialized` 각 1회 확인
- 같은 프로세스의 `[map-loading-error]`는 수집한 로그 안에서 0회였지만 화면의 도로·배경은 미표시
- 실행 로그의 `navi42`·`navi_engine` 환경 값은 `coral`, `NaviAgent`는 `CORAL`
- 실제 스타일 요청 주소는 `https://a.tile.static.maps-coral.umos.ai/styles/42dot/connect_empty`
- 같은 주소를 좌표·인증정보 없이 읽은 HTTP 200 응답은 coastline source 하나와 background·fill·slot·slot 레이어 네 개로 구성되며 도로 source와 style import는 없음
- 그래파이트·자동, 팝·자동, 팝·다크를 비교했으며 제어부 테마와 배경색은 바뀌었지만 도로는 표시되지 않음
- 팝·다크 전환 후에도 전체 스타일 로드 이벤트는 각각 1회로 유지됐으므로 이 조작을 전체 도로 스타일 재적용으로 해석하지 않음

설치 이미지의 `MapStyleRemoteDataSourceImpl.getMapStyles`도 읽기 전용으로 확인했다
입력 환경이 문자열 `coral`과 같으면 `dataCollection` 항목을 만들고 위 `connect_empty` 주소를 반환하는 분기가 있다
이 비교값은 바이트코드의 문자열 상수로 대조했다
항목의 이름은 `connect_v2`지만 실제 URL은 `connect_empty`이므로 이름만으로 도로 지도 여부를 판단할 수 없다

초기 스타일이 빈 지도인 이유를 환경별 선택 코드와 연결할 수 있게 됐다
다만 인증된 실제 스타일 응답의 바이트와 최종 runtime source 목록까지 읽은 것은 아니다
이후 전체 스타일 교체가 필요한 설계인지, 어떤 설정으로 다른 프로필을 사용하도록 의도했는지는 추가 확인 대상이다

## 화면 지도와 이미지 전송의 경계

중앙 지도는 `Navi42MapView` → `Map42View` → Mapbox `MapView`로 연결된다
확인한 초기 `NavigationLayer` 32개는 경로·교통·TBT·마커·lane-carpet을 위한 GeoJSON overlay다
이 목록과 적용 helper에서는 도로 벡터 타일이나 raster source를 추가하지 않는다

`VehicleUseCase.sendMapImage`는 WEBP 이미지와 위치 범위를 AD connector로 전달한다
확인한 메서드에서는 이 이미지가 중앙 Mapbox source로 되돌아오는 연결을 찾지 못했다
다른 프로세스·JNI·동적 호출까지 없다고 증명한 것은 아니며, VNET 전송 횟수를 중앙 도로 지도의 성공 근거로 사용하지 않는다

SDK 내부의 전체 스타일 setter는 현재 map manager가 null이면 스타일을 저장하거나 나중에 재적용하지 않고 반환한다
실제 초기 호출이 이 분기에서 유실됐다는 실행 증거는 없으므로 초기화 순서 오류로 확정하지 않는다

## 콘솔과 좌표 변환 확인

- FormFactorConsole의 `Host Display Bridge UNAVAILABLE`은 DISPLAY2 조회·생성·제거 기능의 상태이며 지도 전송 상태 표시가 아님
- guest 환경 조회나 host display 조회 실패가 같은 표시로 이어질 수 있어 이 문구만으로 host 프로세스 부재도 단정하지 않음
- 내부 common 좌표 타입의 생성자는 위도·경도, map 좌표 타입은 경도·위도 순서이며 확인한 카메라·위치 변환은 필드 의미에 맞게 변환
- 외부 NaviHelper 생성자 변경을 내부 map 타입에 그대로 적용해 좌표를 바꾸는 수정은 근거가 없음

[공식 Form Factor Test Guide](https://document.pleos.ai/en/docs/pleos-only/app-test-guide/form-factor-test-guide)도 Host Display Bridge를 가상 화면 제어 도구로 설명한다

## 보조 화면과 표시 모드 비교

- 안내 중인 기본 화면에서 가상 화면 슬롯 2를 1274×855·160dpi로 연결해도 도로·배경은 표시되지 않음
- 가상 화면 슬롯 1을 1280×720·160dpi로 연결한 비안내 화면에서도 빈 배경을 확인
- 보조 화면을 제거한 뒤 기본 내비의 2D·3D 모드를 각각 확인했으며 비안내 화면의 도로·배경은 표시되지 않음
- 초기화 중인 화면은 정상 지도 표시 여부의 판정에 사용하지 않음

이번 비교는 에뮬레이터 가상 화면의 연결 조건을 확인한 것이다
화면 연결과 3D 전환만으로 해결되지 않았으며 지도 생성·전달·합성 중 어느 단계가 원인인지는 확정하지 않는다

## 추가 진단에 필요한 값

- 기본 내비의 환경 값이 스타일 선택 함수에 전달되는 값과 최종 선택 URL
- 도로 source가 포함된 전체 스타일로 교체하는 공식 설정 또는 초기화 경로
- map-ready 전후 전체 스타일 교체 호출의 값·순서·실제 적용 결과
- 중앙 화면의 최종 runtime style/source 목록과 도로 source 데이터 수신 결과
- VNET과 중앙 화면의 관계를 추가 확인할 경우 같은 요청의 bitmap 크기·형식·내용과 수신·합성 결과

현재 확인한 공개 NaviHelper API에서는 중앙 지도의 전체 스타일 URL이나 내부 source 목록을 바꾸거나 조회하는 경로를 찾지 못했다
일부 Compose 메서드는 디컴파일 중 SSA·코드 생성 오류가 있어 해당 본문을 근거로 사용하지 않고 확인 가능한 메서드와 바이트코드 호출 관계를 구분했다
공급사에 재현 자료를 전달할 때는 공식 AVD·기본 내비 버전, 발생 시각, 화면과 비식별화한 오류·단계 요약을 사용한다
전체 네이티브 로그·인증값·타일 URL·사용자의 장소 데이터는 첨부하지 않는다

## 재검증 기준

- 입력을 바꾸지 않은 새 기본 내비 화면과 실제 안내 화면에서 도로·지명을 시각적으로 확인
- 앱 데이터·내비 캐시·주행 제한 설정을 보존하고 이전에 실패한 GPU 조합은 새 근거가 있을 때만 비교
- `connect_empty` 선택 경로와 실제 도로 source가 공급되는 경로를 먼저 대조
- 이미지 전송을 확인할 경우 중앙 화면과의 연결을 입증한 뒤 같은 요청의 생성·수신·디코드·합성을 대조
- 지도 정상 표시와 SDK 경로 반영을 각각 검증 결과에 기록

[공식 AVD 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml) · [에뮬레이터 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note) · [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
