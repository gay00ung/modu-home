# 시연 영상 편집

차량·동승자 AVD에서 녹화한 화면을 자르고 차량 앱 영역을 확대해 장면 설명을 붙인다
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
- 마지막 화면을 잠깐 유지하려면 `holdLast`를 초 단위로 지정
- `kind`가 `car`이면 차량 앱 영역을 확대하고 `phone`이면 세로 휴대폰 화면 표시
- 연결 QR 등 제외할 영역은 `redactions`의 원본 영상 픽셀 좌표로 지정
- `--check`를 추가하면 인코딩 없이 원본 파일과 편집 구간 확인

앱 버전을 올릴 때는 해당 APK로 다시 촬영하고 `recordedRelease`를 갱신한다
촬영 버전·APK 해시가 제출용 APK 정보와 다르면 편집을 중단한다
장면 설명과 버전 표시는 원본 녹화와 분리되어 있어 같은 화면을 다시 촬영하지 않고도 자막을 조정할 수 있다

생성한 MP4 옆에는 원본 APK 버전·편집 구간·파일 해시를 담은 JSON과 인코딩 로그가 저장된다
