# 볼일 제안 로컬 중계

차량이 만든 방에서 휴대폰의 볼일 제안을 받아 차량 수신함으로 전달하는 개발용 서버다
Python 표준 라이브러리만 사용하고 요청·주소·토큰·본문을 로그에 남기지 않는다
데이터는 메모리에만 보관하며 프로세스가 끝나면 사라진다

## 실행

```sh
python3 tools/proposal-relay/relay.py
```

- 기본 수신 주소는 `127.0.0.1:48680`
- 기본 `publicBaseUrl`은 `http://127.0.0.1:48680`
- `--host`·`--port`로 수신 위치, `--public-url`로 클라이언트에 전달할 주소 설정
- 루프백 이외의 리스너는 `--allow-client`를 반복해 접속할 기기의 사설 IPv4 주소를 지정
- 허용하지 않은 소켓은 요청 헤더·본문 처리와 방 생성 전에 종료
- 허용값은 개별 IP만 사용하며 호스트 이름·대역·전체 허용 주소·공인 IP는 거절
- `publicBaseUrl`은 명시한 설정만 사용하고 요청의 `Host` 헤더에서 만들지 않음
- 공개 주소는 ASCII 최대 2048자, 경로 없는 HTTP(S) 주소와 1~65535 포트로 설정
- 자동 시작·외부 배포·파일 저장 기능 없이 개발 환경에서 직접 실행

## HTTPS 연결

인증서 체인과 개인키를 지정하면 서버가 TLS를 직접 처리한다
파일은 저장소 밖에 두고 공개 주소의 호스트 이름과 일치하는 인증서를 사용한다

```sh
python3 tools/proposal-relay/relay.py \
  --public-url https://relay.example:48680 \
  --tls-cert /run/secrets/relay-fullchain.pem \
  --tls-key /run/secrets/relay-private.key
```

- TLS 1.2 이상과 HTTP/1.1 사용
- 인증서와 암호화하지 않은 PEM 개인키를 함께 지정하며 잘못된 파일·키 불일치는 소켓을 열기 전에 거절
- TLS 리스너의 공개 주소는 HTTPS만 허용
- 접속 IP 검사를 통과한 요청만 TLS 처리하며 핸드셰이크는 요청 스레드에서 수행
- 핸드셰이크를 끝내지 않는 연결이 새 접속 처리를 막지 않도록 5초 소켓 제한 적용
- 앱은 기본 인증서 체인·호스트 이름 검증을 사용하며 테스트용 CA는 테스트 클라이언트에서만 신뢰
- LAN 접속은 기존 `--host`·`--allow-client` 규칙을 함께 적용

별도 프록시에서 HTTPS를 처리할 때는 이 서버를 루프백 HTTP로 실행하고 `--public-url`에 프록시의 HTTPS 주소를 지정한다
`--require-https-url`을 지정하면 HTTP 공개 주소를 리스너 생성 전에 거절한다
경로 서버까지 같은 HTTPS 주소로 연결하는 구성은 [HTTPS 서버 실행](../service-stack/README.md) 참고

## 프로토콜

요청 본문은 UTF-8 JSON이며 모든 응답 후 연결을 닫는다
각 방에는 발신·수신 역할별 토큰이 있고 `Authorization: Bearer <token>`으로 전달한다

| 메소드·경로 | 권한 | 본문 | 성공 응답 |
| --- | --- | --- | --- |
| `POST /v1/rooms` | 없음 | `{}` 또는 `{seedLink}` | 201 `{roomId,receiverToken,senderToken,expiresAtMillis,publicBaseUrl}` |
| `GET /v1/rooms/{roomId}` | 발신 토큰 | 없음 | 200 `{expiresAtMillis,seedLink}` |
| `POST /v1/rooms/{roomId}/messages` | 발신 토큰 | `{messageId,link}` | 202 `{status}` |
| `GET /v1/rooms/{roomId}/messages` | 수신 토큰 | 없음 | 200 `{messages:[{messageId,link}]}` |
| `GET /v1/rooms/{roomId}/messages/{messageId}` | 발신 토큰 | 없음 | 200 `{status}` |
| `POST /v1/rooms/{roomId}/messages/{messageId}/ack` | 수신 토큰 | `{status:"stored"}` 또는 `{status:"rejected"}` | 200 `{status}` |
| `DELETE /v1/rooms/{roomId}` | 수신 토큰 | 없음 | 204 |

- `seedLink`는 차량이 선택한 볼일 한 건의 제안 링크이며 생략하면 조회 응답에서 `null` 반환
- `queued`는 전달 대기, `stored`는 차량 수신함 저장 확인, `rejected`는 수신 거절
- `stored`는 귀가 계획 적용이나 사용자 승인과 구분
- 수신 조회는 대기 메시지만 반환하고 ack 전까지 같은 메시지를 반복 반환
- 발신 재시도는 같은 UUID와 같은 링크를 사용하며 현재 상태 반환
- 같은 UUID의 내용 변경과 서로 다른 최종 ack는 409로 거절
- 발신·수신 역할의 토큰은 서로 대신 사용할 수 없음

## 상한과 오류

- 방은 생성 후 15분 동안 유지하며 조회·재시도로 기한을 늘리지 않음
- 최대 32개 방, 방마다 최종 상태를 포함해 최대 20개 메시지 보관
- 방 생성·요청 처리 때와 서버 유휴 처리 주기에 만료된 방 정리
- 본문 최대 12KiB, 링크·seed는 출력 가능한 ASCII 1~8192자
- 역할 토큰은 각각 `secrets.token_urlsafe(32)`, 방·메시지 ID는 UUID 사용
- 중복 JSON 키·추가 필드·잘못된 형식은 400, 잘못된 권한은 401
- 다른 내용의 중복 ID는 409, 만료·없는 항목은 410, 상한 초과는 429
- `Content-Length` 중복·비정상 값, `Transfer-Encoding`, 경로의 query·추가 슬래시는 거절
- 매 응답은 `Connection: close`이며 같은 연결의 다음 요청은 처리하지 않음

서버는 링크를 전달하는 역할만 담당한다
제안 내용의 유효성, 수신함의 영속 저장, 기존 볼일 교체와 사용자 승인은 앱에서 처리한다
인터넷 운영 서비스에는 배포 환경의 접근 제어·인증서 관리·요청 제한을 별도로 구성한다

## 테스트

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/proposal-relay -p 'test_*.py' -v
```

테스트 서버는 `127.0.0.1`의 임시 포트에서만 실행하고 종료한다
시간 주입으로 만료 경계를 확인하며 인증·역할 분리·동시 중복·상한·상태·삭제·HTTP 프레이밍을 검증한다
HTTPS 테스트는 OpenSSL CLI로 임시 CA·서버 인증서를 생성하고 종료 시 지운다
암호화 전송·인증서 신뢰·호스트 이름·구형 TLS 거절·IP 제한·미완료 핸드셰이크를 실제 소켓으로 확인한다
