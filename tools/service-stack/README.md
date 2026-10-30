# 경로·볼일 제안 HTTPS 서버

Nginx가 HTTPS 요청을 받아 OSRM 도로 경로 서버와 볼일 제안 중계로 전달한다
차량의 경로 조회와 휴대폰 연결에 같은 서버 주소를 사용할 수 있다

| 경로 | 처리 |
| --- | --- |
| `/route/v1/driving/…` | OSRM 자동차 경로 조회 |
| `/v1/rooms` | 연결 생성 |
| `/v1/rooms/…` | 제안 전송·수신·보관 확인·연결 종료 |

## 준비

- Docker Engine과 Compose 사용
- Linux용 OSRM 26.10.0으로 MLD 전처리한 지도 디렉터리 준비
- 접속할 도메인에 맞는 인증서 체인과 개인키를 저장소 밖에 보관
- Android 앱은 기본 인증서·호스트 이름 검증을 사용하므로 기기가 신뢰하는 CA의 인증서 사용

지도 전처리는 [도로 경로 서버](../../docs/routing.md), 중계 계약은 [볼일 제안 서버](../proposal-relay/README.md) 참고
지도 파일은 읽기 전용으로 연결한다
OSRM 데이터는 플랫폼 간에 옮겨 쓸 수 없으므로 macOS에서 만든 파일을 Linux 컨테이너에 사용하지 않는다

## 실행

저장소 루트에서 환경 파일을 복사하고 실제 값으로 바꾼다

```sh
cp tools/service-stack/.env.example tools/service-stack/.env
```

| 설정 | 값 |
| --- | --- |
| `PUBLIC_BASE_URL` | 휴대폰과 차량에서 접속할 HTTPS 원점 주소 |
| `TLS_CERT_FILE` | 인증서 체인 PEM 파일의 절대 경로 |
| `TLS_KEY_FILE` | 암호화하지 않은 개인키 PEM 파일의 절대 경로 |
| `ROUTING_DATA_DIRECTORY` | `.osrm.*` 전처리 파일을 모두 담은 디렉터리 |
| `ROUTING_DATASET` | 전처리 파일의 공통 기준 이름, 기본 `south-korea-latest.osrm` |
| `BIND_ADDRESS` | 호스트 수신 주소, 기본 `127.0.0.1` |
| `HTTPS_PORT` | 호스트 HTTPS 포트, 기본 `8443` |

`PUBLIC_BASE_URL`에는 경로·쿼리·인증정보를 넣지 않는다
인증서와 데이터 경로는 기존 파일·디렉터리를 사용하며 누락된 경로를 자동 생성하지 않는다

새 데이터 디렉터리에 Geofabrik의 `south-korea-latest.osm.pbf` 원본을 복사하고 Linux 이미지 안에서 전처리한다
추출·분할·가중치 계산을 순서대로 수행하며 기존 전처리 파일이 있으면 덮어쓰지 않고 종료한다
기준 이름을 바꾼 경우 같은 이름의 `.osm.pbf` 원본을 준비한다

```sh
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml run --rm prepare-routing
```

전처리에는 지도 크기에 맞는 디스크·메모리가 필요하며 스레드는 두 개 사용한다
Geofabrik 원본의 복제 시각을 OSRM `data_version`으로 보관한다

```sh
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml config --quiet
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml up --build --detach
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml ps
```

기본 수신 주소는 이 컴퓨터에서만 접근할 수 있다
별도 서버에 배치할 때는 해당 서버의 수신 주소와 방화벽·도메인·인증서 갱신을 함께 설정한다
프록시 뒤에서는 `PUBLIC_BASE_URL`을 클라이언트가 사용하는 최종 HTTPS 주소로 지정한다

앱 빌드 시 두 주소를 같은 원점으로 지정한다

```sh
./gradlew :app:assembleRelease \
  -ProutingBaseUrl=https://modu-home.example:8443 \
  -PproposalRelayBaseUrl=https://modu-home.example:8443
```

위 도메인은 설정 예시이며 실제 주소로 바꾼다
생성된 release APK에는 배포에 사용할 서명을 적용한다

## 동작과 종료

- 외부 수신 포트는 gateway 한 곳에만 연결하고 두 백엔드에는 호스트 포트를 열지 않음
- 중계는 gateway의 내부 IP에서 오는 요청만 허용하고 기존 발신·수신 토큰 검증 유지
- 연결 생성은 IP별 분당 6건·순간 여유 2건, 일반 중계는 초당 10건·순간 여유 20건으로 제한
- 경로 조회는 초당 10건·순간 여유 30건으로 두어 함께 귀가의 13개 경로 비교 수용
- 본문은 최대 12KiB로 제한하고 Nginx 접근 로그는 사용하지 않음
- OSRM은 WARNING 이상만 기록해 정상 경로 조회 좌표의 INFO 로그 제외
- 연결과 전달 대기 제안은 메모리에 보관하며 중계 재시작 후 새 QR로 연결
- 백엔드 네트워크 `172.29.248.0/28`이 기존 환경과 겹치면 서브넷·정적 IP·중계 허용 IP를 함께 변경

```sh
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml down
```

종료해도 호스트의 인증서·지도 데이터는 삭제하지 않는다
인증서를 갱신하면 gateway를 재생성해 새 파일을 읽는다

```sh
docker compose --env-file tools/service-stack/.env -f tools/service-stack/compose.yaml up --detach --force-recreate gateway
```

이미지 버전은 다이제스트로 고정하며 변경할 때 경로 응답·제안 상태·TLS 연결을 다시 확인한다

설정 기준은 [Nginx HTTPS](https://nginx.org/en/docs/http/configuring_https_servers.html), [요청 제한](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html), [Compose 네트워크](https://docs.docker.com/reference/compose-file/networks/), [OSRM 이미지](https://github.com/Project-OSRM/osrm-backend/pkgs/container/osrm-backend) 참고
