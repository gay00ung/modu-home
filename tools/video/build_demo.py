#!/usr/bin/env python3
"""Edit recorded emulator footage using the release metadata and scene plan."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFont


def probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_format', '-show_streams', '-of', 'json', str(path),
    ]))


def wrap(draw, text, font, width):
    lines = []
    for paragraph in text.split('\n'):
        line = ''
        for character in paragraph:
            if line and draw.textlength(line + character, font=font) > width:
                lines.append(line)
                line = character
            else:
                line += character
        lines.append(line)
    return lines


def panel(path, version, chapter=None, title=None, bullets=None, kind=None, font_path=None):
    def font(size, bold=False):
        index = 6 if bold and font_path.name == 'AppleSDGothicNeo.ttc' else 0
        return ImageFont.truetype(str(font_path), size, index=index)

    canvas = Image.new('RGB', (1920, 1080), '#101B30')
    draw = ImageDraw.Draw(canvas)
    if chapter is None:
        draw.text((124, 126), 'MODU HOME', font=font(34, True), fill='#8BAAFF')
        draw.text((124, 250), '모두 집으로', font=font(124, True), fill='white')
        draw.text((132, 465), '친구가 정한 하차지부터, 가는 길의 볼일까지', font=font(49), fill='#DCE4F5')
        draw.text((132, 566), f'{version} · 배포 서명 앱 실제 시연', font=font(43), fill='#DCE4F5')
        draw.text((132, 860), 'Pleos 차량 AVD + 동승자 휴대폰 AVD', font=font(32), fill='#ADBBD5')
        draw.text((132, 919), '예시 장소 사용 · 정차 상태에서 안내와 완료 입력', font=font(31), fill='#ADBBD5')
    else:
        draw.text((44, 52), '모두 집으로', font=font(38, True), fill='white')
        draw.rounded_rectangle((44, 133, 122, 142), 4, fill='#638DFF')
        draw.text((44, 201), chapter, font=font(28, True), fill='#8BAAFF')
        y = 270
        for line in wrap(draw, title, font(55, True), 330):
            draw.text((44, y), line, font=font(55, True), fill='white')
            y += 73
        y += 42
        for bullet in bullets:
            for line in wrap(draw, bullet, font(32), 330):
                draw.text((44, y), line, font=font(32), fill='#DCE4F5')
                y += 46
            y += 20
        if y > 940:
            raise ValueError('장면 자막이 길어요 제목이나 설명을 줄여주세요')
        draw.text((44, 960), '휴대폰 AVD 실제 화면' if kind == 'phone' else '차량 AVD 실제 화면',
                  font=font(25), fill='#ADBBD5')
        draw.text((44, 1003), f'{version} · 배포 서명 앱', font=font(25), fill='#ADBBD5')
    canvas.save(path)


def main():
    parser = argparse.ArgumentParser(description='모두 집으로 실제 녹화 영상 편집')
    parser.add_argument('--plan', type=Path, default=Path(__file__).with_name('demo.json'))
    parser.add_argument('--release', type=Path, default=Path(__file__).resolve().parents[2] / 'apk/release.json')
    parser.add_argument('--source-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--font', type=Path, default=Path('/System/Library/Fonts/AppleSDGothicNeo.ttc'))
    parser.add_argument('--check', action='store_true', help='파일·버전·편집 구간만 확인')
    args = parser.parse_args()
    for binary in ['ffmpeg', 'ffprobe']:
        if not shutil.which(binary):
            parser.error(binary + ' 설치가 필요해요')
    if not args.font.is_file():
        parser.error('--font로 한글을 표시할 수 있는 글꼴을 지정해주세요')
    plan = json.loads(args.plan.read_text())
    release = json.loads(args.release.read_text())
    recorded = plan['recordedRelease']
    for key in ['versionName', 'versionCode', 'sha256']:
        if recorded[key] != release[key]:
            parser.error('촬영한 APK와 제출 APK가 달라요 새 APK로 촬영한 뒤 recordedRelease를 갱신해주세요')
    footage = {}
    for scene in plan['scenes']:
        source = (args.source_dir / scene['source']).resolve()
        if not source.is_relative_to(args.source_dir.resolve()) or not source.is_file():
            parser.error('촬영 원본을 찾지 못했어요: ' + scene['source'])
        if source not in footage:
            info = probe(source)
            footage[source] = info
        duration = float(footage[source]['format']['duration'])
        if scene['start'] < 0 or scene['duration'] <= 0 or scene['start'] + scene['duration'] > duration + 0.05:
            parser.error('녹화 길이를 벗어난 편집 구간이에요: ' + scene['source'])
        if scene.get('holdLast', 0) < 0:
            parser.error('마지막 화면을 유지하는 시간은 0초 이상이어야 해요')
        if scene['kind'] not in ['car', 'phone']:
            parser.error('장면 kind는 car 또는 phone으로 지정해주세요')
        video = next(stream for stream in footage[source]['streams'] if stream['codec_type'] == 'video')
        dimensions = (1920, 1080) if scene['kind'] == 'car' else (540, 1200)
        if (video['width'], video['height']) != dimensions:
            parser.error(f'녹화 크기는 {dimensions[0]}×{dimensions[1]}이어야 해요: {scene["source"]}')
    if args.check:
        print(json.dumps({'versionName': release['versionName'], 'sources': len(footage),
                          'scenes': len(plan['scenes']), 'validated': True}, ensure_ascii=False))
        return
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='modu-home-video-') as temp:
        directory = Path(temp)
        log_path = args.output.with_suffix('.encode.log')

        def encode(arguments):
            with log_path.open('ab') as log:
                subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', *arguments],
                               stdout=log, stderr=log, check=True)

        common = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-threads', '2', '-pix_fmt', 'yuv420p']
        intro = directory / 'intro.png'
        panel(intro, release['versionName'], font_path=args.font)
        parts = [directory / '00.mp4']
        encode(['-loop', '1', '-framerate', '30', '-i', str(intro), '-t', '5', *common, str(parts[0])])
        for i, scene in enumerate(plan['scenes'], 1):
            backdrop = directory / f'{i:02}.png'
            panel(backdrop, release['versionName'], scene['chapter'], scene['title'], scene['bullets'], scene['kind'], args.font)
            source = args.source_dir / scene['source']
            transform = 'fps=30'
            # Redactions apply to the recorded pixels before sizing and do not alter app behavior.
            for box in scene.get('redactions', []):
                transform += f",drawbox=x={box['x']}:y={box['y']}:w={box['w']}:h={box['h']}:color=0x101B30:t=fill"
            if scene['kind'] == 'car':
                transform += ',crop=1260:908:640:67,scale=1480:1066,setsar=1'
                x, y = 430, 7
            else:
                transform += ',scale=464:1032,setsar=1'
                x, y = 960, 24
            hold = scene.get('holdLast', 0)
            tail = f',tpad=stop_mode=clone:stop_duration={hold}' if hold else ''
            graph = f'[1:v]{transform}[screen];[0:v][screen]overlay={x}:{y}:shortest=1,format=yuv420p{tail}[out]'
            target = directory / f'{i:02}.mp4'
            encode(['-loop', '1', '-framerate', '30', '-i', str(backdrop), '-ss', str(scene['start']),
                    '-t', str(scene['duration']), '-i', str(source), '-filter_complex', graph,
                    '-filter_complex_threads', '1', '-map', '[out]', '-an', '-t', str(scene['duration'] + hold),
                    *common, str(target)])
            parts.append(target)
            print(f'장면 {i}/{len(plan["scenes"])} 완료', flush=True)
        concat = directory / 'concat.txt'
        concat.write_text(''.join(f"file '{part}'\n" for part in parts))
        encode(['-f', 'concat', '-safe', '0', '-i', str(concat), '-f', 'lavfi', '-i',
                'anullsrc=channel_layout=stereo:sample_rate=48000', '-map', '0:v:0', '-map', '1:a:0',
                '-c:v', 'copy', '-c:a', 'aac', '-b:a', '64k', '-shortest', '-movflags', '+faststart', str(args.output)])
    media = probe(args.output)
    media['format'].pop('filename', None)
    summary = {'recordedRelease': recorded, 'source': 'actual ADB screenrecord', 'scenes': plan['scenes'],
               'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(), 'media': media}
    args.output.with_suffix('.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print('영상 생성 완료', flush=True)


if __name__ == '__main__':
    main()
