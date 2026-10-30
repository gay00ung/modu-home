# 기존 HTTPS 프록시에 연결

Caddy가 인증서 발급·갱신과 외부 HTTPS를 처리하고, API gateway는 호스트의 루프백 HTTP 포트에서 요청을 받는다
도로·제안 서버와 요청 제한·본문 상한·토큰 검증은 직접 TLS 구성과 공유한다

## 서버 설정

- Docker Engine·Compose 2.20.3 이상 사용
- 사용할 호스트 이름의 DNS A 레코드를 서버 공인 IPv4에 연결
- Caddy에서 TCP 80·443을 수신하고 해당 호스트의 인증서를 자동 관리
- 실행 환경 파일과 지도 데이터는 소스 디렉터리 밖에 보관
- 지도 데이터는 같은 OSRM 이미지·플랫폼으로 전처리한 파일 사용

`proxy.env.example`을 소스 밖에 복사하고 실제 HTTPS 주소·데이터 디렉터리를 지정한다

```sh
cp tools/service-stack/proxy.env.example /srv/modu-home/deployment.env
chmod 600 /srv/modu-home/deployment.env
docker compose -p modu-home \
  --env-file /srv/modu-home/deployment.env \
  -f tools/service-stack/compose.proxy.yaml config --quiet
docker compose -p modu-home \
  --env-file /srv/modu-home/deployment.env \
  -f tools/service-stack/compose.proxy.yaml up --build --detach
```

- gateway만 `127.0.0.1:48090`에 연결하고 두 백엔드는 호스트 포트를 열지 않음
- 메모리 상한은 gateway·중계 각 128MiB, 도로 서버 2GiB
- CPU 상한은 gateway 0.25개·중계 0.5개·도로 서버 1개
- 프로세스 종료·서버 재부팅 후 unless-stopped 정책으로 재시작
- 중계 재시작 후에는 새 연결 QR 사용

## Caddy 연결

`Caddyfile.example`의 예시 호스트를 실제 이름으로 바꿔 기존 Caddy 설정에 새 사이트로 추가한다
기존 설정을 백업하고 검증한 뒤 reload한다

```sh
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
systemctl reload caddy
```

새 사이트는 외부의 전달 IP 헤더를 실제 연결 IP로 바꿔 gateway에 보낸다
gateway는 Docker frontend 게이트웨이 `172.29.247.1`에서 받은 헤더만 신뢰해 IP별 요청 제한에 사용한다
frontend 대역을 바꾸면 Compose의 gateway와 nginx-proxy.conf의 신뢰 IP를 함께 변경한다
backend 대역 `172.29.248.0/28`은 기존 환경과의 중복을 확인한다

## 확인과 갱신

- 기본 CA 신뢰와 호스트 이름 검증으로 실제 HTTPS 접속 확인
- `/healthz`는 gateway 응답만 확인하며 두 백엔드는 아래 요청으로 각각 확인
- 경로 응답의 구간·연결점·지도 버전을 확인하고 실시간 교통과 구분
- 방 생성·제안 전송·수신·보관 확인·재시도·연결 종료 확인
- 요청 제한과 위조 전달 IP 헤더 차단 확인
- 인증서는 Caddy가 관리하며 앱 서명 키는 개발 PC에서 보관
- 앱 빌드의 두 서버 주소를 같은 공개 HTTPS 원점으로 지정

```sh
docker compose -p modu-home \
  --env-file /srv/modu-home/deployment.env \
  -f tools/service-stack/compose.proxy.yaml ps
```

구성 기준은 [Compose include](https://docs.docker.com/compose/how-tos/multiple-compose-files/include/), [Caddy 자동 HTTPS](https://caddyserver.com/docs/automatic-https), [전달 헤더](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults) 참고
