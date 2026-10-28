# 도로 경로 서버

가는김에는 현재 차량 위치에서 집 직행과 방문 순서별 전체 경로를 조회한다
도로별 일방통행과 회전 조건이 반영된 각 순서의 구간 시간을 사용하며, 한 목적지의 도착시간을 다른 목적지에서 빼서 구간 시간을 만들지 않는다

## 로컬 실행

macOS에서는 Homebrew의 OSRM을 사용할 수 있다
지도 데이터와 전처리 결과는 저장소 밖에 둔다

```sh
brew install osrm-backend
ROUTING_DATA="$HOME/.cache/modu-home-routing"
mkdir -p "$ROUTING_DATA"
curl --fail --location --output "$ROUTING_DATA/south-korea-latest.osm.pbf" https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
```

[Geofabrik 대한민국 데이터](https://download.geofabrik.de/asia/south-korea.html)의 다운로드 체크섬과 파일을 비교한 뒤 같은 OSRM 버전으로 세 단계를 수행한다
자동차 프로필을 사용하므로 보행 경로로 해석하지 않는다

```sh
osrm-extract -p "$(brew --prefix)/share/osrm/profiles/car.lua" -t 2 -d osmosis "$ROUTING_DATA/south-korea-latest.osm.pbf"
osrm-partition -t 2 "$ROUTING_DATA/south-korea-latest.osrm"
osrm-customize -t 2 "$ROUTING_DATA/south-korea-latest.osrm"
osrm-routed --algorithm MLD --ip 127.0.0.1 --port 5001 --threads 2 --mmap --max-viaroute-size 8 --max-table-size 16 "$ROUTING_DATA/south-korea-latest.osrm"
```

- 서버는 실행한 터미널에서 유지하고 종료할 때 Ctrl+C 사용
- `debug` APK의 기본 주소는 AVD에서 호스트를 가리키는 `http://10.0.2.2:5001`
- 다른 서버는 Gradle의 `routingBaseUrl` 속성으로 지정
- `release`는 HTTPS 서버 주소를 지정해야 도로 경로 조회 사용 가능
- 서버에 전달되는 내용은 선택한 장소의 좌표이며 배포 서버의 보관·접근 정책을 개인정보 안내에 반영

```sh
./gradlew :app:assembleRelease -ProutingBaseUrl=https://routing.example.com
```

위 도메인은 설정 형식을 보여주는 예시다
서비스 서버의 실제 HTTPS 주소로 바꾼다

## 계산과 실패 처리

- 각 경로의 구간 시간·거리와 경유 순서가 요청에 대응하는지 검증
- 도로 연결점과 요청 장소의 거리가 250m를 넘으면 해당 응답 거부
- 경로 일부가 누락되거나 서로 다른 지도 버전이면 비교 결과를 만들지 않음
- 구간 이동시간은 1분 단위 올림하고 체류·왕복 보행시간 합산
- 모든 순서의 응답이 모인 뒤 비교 화면 표시
- 서버 연결 실패를 수동 예상시간으로 바꿔 표시하지 않음
- 응답 시각과 지도 버전을 함께 저장하고 5분이 지난 계획은 내비 전송 전 재조회

## 출처와 범위

OSRM의 자동차 프로필과 OpenStreetMap 도로망에 따른 예상시간이며 실시간 교통은 포함하지 않는다
NaviHelper의 현재 위치→목적지 예상시간 조회는 차량 내비가 제공하는 별도 결과다
두 결과를 같은 교통 조건의 시간처럼 섞지 않는다

지도 출처는 © OpenStreetMap contributors, ODbL이며 비교 화면에도 표시한다
운영 서버는 [OpenStreetMap 저작권과 라이선스](https://www.openstreetmap.org/copyright)를 따른다

- [OSRM 실행 안내](https://github.com/Project-OSRM/osrm-backend#quick-start)
- [Route API](https://project-osrm.org/docs/v5.24.0/api/#route-service)
- [Homebrew OSRM](https://formulae.brew.sh/formula/osrm-backend)
