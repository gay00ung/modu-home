# 기본 내비 배경 지도 진단

경로선과 회전 안내가 표시돼도 도로·배경 지도가 정상이라는 뜻은 아니다
NaviHelper의 목적지·경유 순서·안내 상태와 기본 내비의 지도 이미지를 각각 확인한다

## 현재 확인 범위

2026년 10월 30~31일 Pleos Connect 2.1.0 ARM64·사용자 10에서 확인했다

- 영구 배포 키로 서명한 비디버그 앱에 `NAVI_ROUTE`·`NAVI_ROUTE_SEARCH` 허용
- 운영 HTTPS 비교와 선택한 방문 순서의 실제 내비 요청·완료 후 다음 구간 연결 확인
- 기본 내비 3.3.2-rc5-hotfix.3에서도 경로선·위치·회전 안내와 빈 배경이 함께 표시
- 기본 내비만 재실행한 새 관측에서 `Map onInitialized true` 1회, VNET 이미지 전송 로그 25회 확인
- 같은 관측에서 GL `0x501` 반복 확인
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

## 초기 환경 값의 출처

기본 내비 APK의 `BuildConfig.APP_ENV`는 `PUBLIC STATIC FINAL` 문자열 `CORAL`이다
`MainApplication.onCreate`는 이 값을 `Locale.ROOT` 기준 소문자로 바꾸고 `InitUseCase.setAppInfo`에 전달한다
시작 코드의 실제 DEX에도 `CORAL` 문자열 상수와 소문자 변환 호출이 있다
이 경로는 설정 저장소나 실행 Intent에서 환경 값을 읽지 않는다

초기 스타일 선택은 다음 순서로 이어진다

- `setAppInfo`가 받은 환경 값을 `InitUseCase.buildFlavor`에 저장
- `MainViewModel.checkSSLCertification`의 coroutine이 `getBuildFlavor`와 국가 코드를 조회
- `InitUseCase.checkSSLCertification`이 같은 buildFlavor를 `MapStyleRepository.getMapStyle`에 전달
- repository의 coroutine이 이를 `MapStyleRemoteDataSource.getMapStyles` 첫 인수로 전달
- `coral` 분기에서 만든 목록의 `connect_v2` 항목을 이름으로 선택하고 그 항목의 `connect_empty` URL 반환
- 성공 여부와 URL을 `readySSLCertificationFlow`에 보관

coroutine의 호출·인수 전달도 DEX로 대조했다
함수 이름에 SSL이 들어 있지만 이 함수의 성공값만으로 실제 지도 타일의 TLS 연결이나 도로 표시가 검증됐다고 판단하지 않는다
이 시작 경로의 환경 상수는 모두 집으로의 장소·경유지 입력과 별개다
다른 네이티브 렌더링 경로가 도로를 추가하는지와 최종 runtime source 목록은 아직 확인하지 못했다

## 공개 SDK로 변경할 수 있는 범위

NaviHelper 3.0.5 source 17개와 navi-data 3.0.4 source 51개를 확인하고 공개 컴파일 인터페이스와 대조했다

- NaviHelper 생성자는 `Context`, 초기화는 무인수 또는 연결 콜백을 받으며 환경·전체 스타일 URL 입력은 없음
- `NavigationScreenRequest`의 13개 타입에 환경·스타일 설정 필드는 없음
- `NavigationScreenRequest.Settings`는 인수가 없는 일반 설정 화면 요청이며 `NAVI_CUSTOM_MAP` 권한 필요
- 서비스 연결 Intent도 고정 component·action을 사용하고 공개 설정 인수를 받지 않음
- 숨겨진 `InternalApi`의 `updateOccupiedRegion`은 화면 점유 영역 전달이며 지도 프로필 제어가 아님

generic String 요청을 받는 하위 AIDL이 있다는 이유로 임의의 미공개 설정 명령을 만들어 사용하지 않았다
확인한 기본 내비 manifest와 MainActivity 시작·새 Intent 처리에도 외부에서 환경·스타일 URL을 지정하는 계약은 없었다
이 결론은 조사한 공개 계약과 시작 경로의 범위이며 공급사 전용 설정이나 모든 동적 호출의 부재를 증명한 것은 아니다

10월 31일 공개 ARM64 피드는 설치된 것과 같은 Android 34·revision 2·Pleos Connect v2.1.0을 제공했다
같은 시점의 `raw-releases` 전체 목록은 시스템 이미지·피드와 Host Display Bridge의 다섯 항목이며 개별 내비 업데이트 APK는 없었다
기본 내비 manifest의 `debuggable`도 false이므로 디버거로 내부 스타일을 강제 교체하는 검증은 하지 않았다

[공식 NaviHelper 소개](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) · [권한 선언](https://document.pleos.ai/en/api-reference/connect-sdk-pleos/NaviHelper/declaring-permissions)

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

- `CORAL` 초기 프로필에서 도로를 공급하는 정식 경로와 `connect_empty` 사용 의도
- 도로 source가 포함된 전체 스타일로 교체하는 공식 설정 또는 초기화 경로
- map-ready 전후 전체 스타일 교체 호출의 값·순서·실제 적용 결과
- 중앙 화면의 최종 runtime style/source 목록과 도로 source 데이터 수신 결과
- VNET과 중앙 화면의 관계를 추가 확인할 경우 같은 요청의 bitmap 크기·형식·내용과 수신·합성 결과

현재 확인한 공개 NaviHelper API에서는 중앙 지도의 전체 스타일 URL이나 내부 source 목록을 바꾸거나 조회하는 경로를 찾지 못했다
일부 Compose 메서드는 디컴파일 중 SSA·코드 생성 오류가 있어 해당 본문을 근거로 사용하지 않고 확인 가능한 메서드와 바이트코드 호출 관계를 구분했다
재현 자료에는 공식 AVD·기본 내비 버전, 발생 시각, 화면과 비식별화한 오류·단계 요약을 사용한다
전체 네이티브 로그·인증값·타일 URL·사용자의 장소 데이터는 첨부하지 않는다

## 재검증 기준

- 입력을 바꾸지 않은 새 기본 내비 화면과 실제 안내 화면에서 도로·지명을 시각적으로 확인
- 앱 데이터·내비 캐시·주행 제한 설정을 보존하고 이전에 실패한 GPU 조합은 새 근거가 있을 때만 비교
- `connect_empty` 선택 경로와 실제 도로 source가 공급되는 경로를 먼저 대조
- 이미지 전송을 확인할 경우 중앙 화면과의 연결을 입증한 뒤 같은 요청의 생성·수신·디코드·합성을 대조
- 지도 정상 표시와 SDK 경로 반영을 각각 검증 결과에 기록

[공식 AVD 피드](https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml) · [에뮬레이터 릴리즈 노트](https://document.pleos.ai/docs/pleos-only/release-note/emulator-release-note) · [NaviHelper 권한](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/declaring-permissions)
