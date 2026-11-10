# 시연 영상 편집

차량·동승자 AVD 녹화에서 하차지 전송, 차량 수신, 순서 비교와 내비 안내를 이어 붙인다
동승자 전송 완료와 차량 수신 장면은 한 화면에서 함께 보여준다
버전 표시는 `apk/release.json`에서 읽는다

## 준비

- Python 3.10 이상과 Pillow
- `ffmpeg`와 `ffprobe`
- 한글 글꼴 기본값은 macOS의 Apple SD Gothic Neo이며 다른 환경에서는 `--font`로 지정
- `demo.json`에 기재한 녹화 원본을 저장소 밖의 한 폴더에 보관
- 녹화 크기는 차량 1920×1080, 휴대폰 540×1200

## 편집

```sh
python3 tools/video/build_demo.py \
  --source-dir /path/to/recordings \
  --output /path/to/output/modu-home-demo.mp4
```

- `demo.json`의 `start`·`duration`으로 원본에서 사용할 구간 선택
- `chapter`·`title`·`bullets`로 장면 설명 변경
- `speed`로 재생 배속을 지정하고 `holdFirst`와 `holdLast`로 첫 화면과 마지막 화면을 잠깐 유지
- `kind`가 `car`이면 차량 앱 영역을 확대하고 `phone`이면 세로 휴대폰 화면 표시
- 차량 장면에 `phoneStill`을 지정하면 원본 폴더에 있는 세로 휴대폰 캡처를 옆에 표시
- MP4의 탐색 구간은 `chapter`에서 읽고 같은 이름의 연속 장면은 한 구간으로 묶음
- 연결 QR 등 제외할 영역은 `redactions`의 원본 영상 픽셀 좌표로 지정
- `--check`를 추가하면 인코딩 없이 원본 파일과 편집 구간 확인

앱 버전을 올릴 때는 해당 APK로 다시 촬영하고 `recordedRelease`를 갱신한다
촬영 버전·APK 해시가 제출용 APK 정보와 다르면 편집을 중단한다
장면 설명과 버전 표시는 원본 녹화와 분리되어 있어 같은 화면을 다시 촬영하지 않고도 자막을 조정할 수 있다

생성한 MP4 옆에는 원본 APK 버전·편집 구간·파일 해시를 담은 JSON과 인코딩 로그가 저장된다
