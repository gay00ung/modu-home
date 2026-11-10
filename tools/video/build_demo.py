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


PAPER = '#F8F5EF'
INK = '#222943'
MUTED = '#54617C'
ACCENT = '#3C4CB3'
TINT = '#E7E9FF'
CAR_FRAME = (536, 64, 1328, 956)
PHONE_FRAME = (1192, 60, 432, 960)
PAIR_CAR_FRAME = (680, 175, 1184, 854)
PAIR_PHONE_FRAME = (76, 220, 360, 800)


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


def panel(path, version, font_path, scene=None):
    def font(size, bold=False):
        index = 6 if bold and font_path.name == 'AppleSDGothicNeo.ttc' else 0
        return ImageFont.truetype(str(font_path), size, index=index)

    canvas = Image.new('RGB', (1920, 1080), PAPER)
    draw = ImageDraw.Draw(canvas)

    def frame(bounds, dark=False):
        x, y, width, height = bounds
        draw.rounded_rectangle((x - 10, y - 4, x + width + 10, y + height + 15), 16, fill='#DCDDEB')
        draw.rounded_rectangle((x - 6, y - 6, x + width + 6, y + height + 6), 12,
                               fill=INK if dark else '#D7D9EA')

    if scene is None:
        draw.rounded_rectangle((84, 105, 343, 170), 33, fill=TINT)
        draw.text((110, 119), '차량용 앱 시연', font=font(28, True), fill=ACCENT)
        draw.text((83, 274), '모두 집으로', font=font(122, True), fill=INK)
        draw.multiline_text((91, 487), '동승자가 보낸 하차지로\n귀가 순서를 비교합니다', font=font(50),
                            fill=INK, spacing=12)
        draw.text((91, 885), f'{version}  ·  Pleos 차량 AVD  ·  휴대폰 AVD', font=font(29), fill=MUTED)
        draw.rounded_rectangle((1022, 164, 1834, 916), 60, fill=TINT)
        draw.line([(1162 + 534 * t / 80, 635 - 182 * t / 80 + 72 * (t / 80) ** 3)
                   for t in range(81)], fill='#7885D8', width=10)
        for x, y, color in ((1162, 635, '#F2CCB7'), (1415, 556, '#C6CDF9'), (1696, 525, ACCENT)):
            draw.ellipse((x - 38, y - 38, x + 38, y + 38), fill=color)
        draw.polygon([(1675, 523), (1696, 505), (1717, 523), (1714, 523), (1714, 544), (1678, 544), (1678, 523)],
                     fill='white')
        draw.rectangle((1691, 531, 1701, 544), fill=ACCENT)
        draw.text((1118, 735), '하차지', font=font(33, True), fill=INK)
        draw.text((1668, 635), '집', font=font(33, True), fill=INK)
    elif scene.get('phoneStill'):
        draw.text((67, 28), scene['chapter'], font=font(29, True), fill=ACCENT)
        draw.text((65, 61), scene['title'].replace('\n', ' '), font=font(57, True), fill=INK)
        for i, detail in enumerate(scene['bullets']):
            draw.text((1190, 36 + i * 35), detail, font=font(28), fill=MUTED)
        draw.text((76, 176), '동승자 · 휴대폰', font=font(29, True), fill=ACCENT)
        draw.text((680, 130), '운전자 · 차량', font=font(29, True), fill=ACCENT)
        frame(PAIR_PHONE_FRAME, dark=True)
        frame(PAIR_CAR_FRAME)
        draw.rounded_rectangle((474, 530, 644, 606), 38, fill=TINT)
        draw.line((511, 568, 605, 568), fill=ACCENT, width=5)
        draw.line((586, 550, 605, 568, 586, 586), fill=ACCENT, width=5, joint='curve')
    else:
        phone = scene['kind'] == 'phone'
        if phone:
            draw.rounded_rectangle((1002, 130, 1812, 950), 55, fill=TINT)
        frame(PHONE_FRAME if phone else CAR_FRAME, dark=phone)
        x, width = (92, 820) if phone else (54, 442)
        draw.text((x, 103), '모두 집으로', font=font(36, True), fill=INK)
        draw.rounded_rectangle((x, 170, x + 66, 179), 4, fill=ACCENT)
        draw.text((x, 231), scene['chapter'], font=font(29, True), fill=ACCENT)
        title_font = font(77 if phone else 56, True)
        y = 308
        for line in wrap(draw, scene['title'], title_font, width):
            draw.text((x, y), line, font=title_font, fill=INK)
            y += 88 if phone else 67
        y += 32
        body_font = font(40 if phone else 34)
        for detail in scene['bullets']:
            for line in wrap(draw, detail, body_font, width):
                draw.text((x, y), line, font=body_font, fill=MUTED)
                y += 48 if phone else 44
            y += 14
        if y > 940:
            raise ValueError('장면 자막이 길어요 제목이나 설명을 줄여주세요')
        device = '휴대폰 AVD' if phone else 'Pleos 차량 AVD'
        draw.text((x, 977), f'{device}  ·  {version}', font=font(27), fill=MUTED)
    canvas.save(path)


def source_file(directory, name):
    path = (directory / name).resolve()
    if not path.is_relative_to(directory.resolve()) or not path.is_file():
        raise ValueError('촬영 원본을 찾지 못했어요: ' + name)
    return path


def write_chapters(path, parts, labels):
    entries = []
    elapsed = 0
    for part, label in zip(parts, labels, strict=True):
        length = round(float(probe(part)['format']['duration']) * 1000)
        if entries and entries[-1][0] == label:
            entries[-1][2] += length
        else:
            entries.append([label, elapsed, elapsed + length])
        elapsed += length
    with path.open('w') as output:
        output.write(';FFMETADATA1\ntitle=모두 집으로\n')
        for label, start, end in entries:
            escaped = label.replace('\\', '\\\\')
            for character in ('=', ';', '#', '\n'):
                escaped = escaped.replace(character, '\\' + character)
            output.write(f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={start}\nEND={end}\ntitle={escaped}\n')


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
        try:
            source = source_file(args.source_dir, scene['source'])
            if scene.get('phoneStill'):
                with Image.open(source_file(args.source_dir, scene['phoneStill'])) as still:
                    if scene['kind'] != 'car' or still.width * 20 != still.height * 9:
                        parser.error('phoneStill은 차량 장면의 세로 휴대폰 캡처여야 해요')
        except (OSError, ValueError) as error:
            parser.error(str(error))
        if source not in footage:
            info = probe(source)
            footage[source] = info
        duration = float(footage[source]['format']['duration'])
        if scene['start'] < 0 or scene['duration'] <= 0 or scene['start'] + scene['duration'] > duration + 0.05:
            parser.error('녹화 길이를 벗어난 편집 구간이에요: ' + scene['source'])
        if min(scene.get('holdFirst', 0), scene.get('holdLast', 0)) < 0 or scene.get('speed', 1) <= 0:
            parser.error('화면을 유지하는 시간은 0초 이상, 재생 배속은 0보다 커야 해요')
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
        panel(intro, release['versionName'], args.font)
        parts = [directory / '00.mp4']
        encode(['-loop', '1', '-framerate', '30', '-i', str(intro), '-t', '3.6', *common, str(parts[0])])
        for i, scene in enumerate(plan['scenes'], 1):
            backdrop = directory / f'{i:02}.png'
            panel(backdrop, release['versionName'], args.font, scene)
            source = args.source_dir / scene['source']
            speed = scene.get('speed', 1)
            transform = f'setpts=(PTS-STARTPTS)/{speed},fps=30'
            # Redactions apply to the recorded pixels before sizing and do not alter app behavior.
            for box in scene.get('redactions', []):
                transform += f",drawbox=x={box['x']}:y={box['y']}:w={box['w']}:h={box['h']}:color=0x101B30:t=fill"
            paired = scene.get('phoneStill')
            inputs = ['-loop', '1', '-framerate', '30', '-i', str(backdrop),
                      '-ss', str(scene['start']), '-t', str(scene['duration']), '-i', str(source)]
            background = '[0:v]'
            graph = ''
            if paired:
                inputs += ['-loop', '1', '-framerate', '30', '-i', str(args.source_dir / paired)]
                phone_x, phone_y, phone_width, phone_height = PAIR_PHONE_FRAME
                graph = f'[2:v]scale={phone_width}:{phone_height}[phone];[0:v][phone]overlay={phone_x}:{phone_y}[pair];'
                background = '[pair]'
            if scene['kind'] == 'car':
                transform += ',crop=1260:908:640:67'
                x, y, width, height = PAIR_CAR_FRAME if paired else CAR_FRAME
            else:
                x, y, width, height = PHONE_FRAME
            transform += f',scale={width}:{height},setsar=1'
            first, last = scene.get('holdFirst', 0), scene.get('holdLast', 0)
            padding = f',tpad=start_mode=clone:start_duration={first}:stop_mode=clone:stop_duration={last}'
            graph += f'[1:v]{transform}[screen];{background}[screen]overlay={x}:{y}:shortest=1,format=yuv420p{padding}[out]'
            target = directory / f'{i:02}.mp4'
            encode([*inputs, '-filter_complex', graph,
                    '-filter_complex_threads', '1', '-map', '[out]', '-an', '-t', str(scene['duration'] / speed + first + last),
                    *common, str(target)])
            parts.append(target)
            print(f'장면 {i}/{len(plan["scenes"])} 완료', flush=True)
        concat = directory / 'concat.txt'
        concat.write_text(''.join(f"file '{part}'\n" for part in parts))
        chapters = directory / 'chapters.txt'
        write_chapters(chapters, parts, ['모두 집으로'] + [scene['chapter'] for scene in plan['scenes']])
        encode(['-f', 'concat', '-safe', '0', '-i', str(concat), '-f', 'ffmetadata', '-i', str(chapters),
                '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000',
                '-map', '0:v:0', '-map', '2:a:0', '-map_metadata', '1', '-map_chapters', '1',
                '-c:v', 'copy', '-c:a', 'aac', '-b:a', '64k', '-shortest', '-movflags', '+faststart', str(args.output)])
    media = probe(args.output)
    media['format'].pop('filename', None)
    summary = {'recordedRelease': recorded, 'source': 'actual ADB screenrecord', 'scenes': plan['scenes'],
               'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(), 'media': media}
    args.output.with_suffix('.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    print('영상 생성 완료', flush=True)


if __name__ == '__main__':
    main()
