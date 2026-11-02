#!/usr/bin/env python3
"""Start only the dedicated exhibition emulators with persistent user data."""
import argparse
import json
import os
import socket
from pathlib import Path
import subprocess
import time

PROFILES = {
    'car': ('Modu_Home_Exhibition', 5554, 'system-images;android-34;pleos_car_emulator;arm64-v8a', 'Pleos_Connect_v2_0', 4096),
    'phone': ('Modu_Home_Companion', 5556, 'system-images;android-36;google_apis_playstore;arm64-v8a', 'pixel_7', 2048),
}


def sdk_directory():
    for value in [os.environ.get('ANDROID_SDK_ROOT'), os.environ.get('ANDROID_HOME')]:
        if value and (Path(value) / 'platform-tools/adb').is_file():
            return Path(value)
    local = Path(__file__).resolve().parents[2] / 'local.properties'
    if local.is_file():
        for line in local.read_text().splitlines():
            if line.startswith('sdk.dir='):
                return Path(line.split('=', 1)[1].replace('\\:', ':').replace('\\\\', '\\'))
    candidate = Path.home() / 'Library/Android/sdk'
    if (candidate / 'platform-tools/adb').is_file():
        return candidate
    raise RuntimeError('Android SDK 경로를 local.properties 또는 ANDROID_SDK_ROOT로 지정해주세요')


def avd_root():
    return Path(os.environ.get('ANDROID_AVD_HOME', str(Path.home() / '.android/avd')))


def create_profile(sdk, profile):
    name, _, image, device, memory = PROFILES[profile]
    root = avd_root()
    directory = root / (name + '.avd')
    if directory.exists() or (root / (name + '.ini')).exists():
        raise RuntimeError('같은 이름의 AVD가 있어 보존했어요 --create 없이 실행해주세요')
    package = sdk / 'system-images' / '/'.join(image.split(';')[1:])
    if not (package / 'source.properties').is_file():
        raise RuntimeError('설치된 시스템 이미지가 필요해요: ' + image)
    manager = sdk / 'cmdline-tools/latest/bin/avdmanager'
    result = subprocess.run([str(manager), 'create', 'avd', '-n', name, '-k', image, '-d', device],
                            input='no\n', text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('전시용 AVD 생성에 실패했어요 SDK 이미지와 기기 프로필을 확인해주세요')
    config = directory / 'config.ini'
    values = dict(line.split('=', 1) for line in config.read_text().splitlines() if '=' in line)
    values.update({'hw.ramSize': str(memory), 'hw.cpu.ncore': '4', 'hw.gpu.mode': 'host',
                   'fastboot.forceColdBoot': 'yes', 'fastboot.forceFastBoot': 'no',
                   'disk.dataPartition.size': '6442450944'})
    config.write_text(''.join(f'{k}={v.strip()}\n' for k, v in values.items()))
    return directory


def wait_for_boot(adb, name):
    end = time.monotonic() + 90
    while True:
        try:
            boot = subprocess.run(adb + ['shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True, timeout=4)
            if boot.stdout.strip() == '1':
                observed = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True, timeout=4)
                if observed.splitlines() and observed.splitlines()[0] == name:
                    break
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError):
            pass
        if time.monotonic() > end:
            raise RuntimeError('전시용 AVD 부팅을 확인하지 못했어요 전시용 로그를 확인해주세요')
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description='모두 집으로 전시용 AVD 실행')
    parser.add_argument('--profile', choices=PROFILES, default='car')
    parser.add_argument('--create', action='store_true', help='설치된 이미지로 새 전시용 AVD 생성')
    parser.add_argument('--restore-ready', action='store_true', help='전시 시작 스냅샷 복원')
    parser.add_argument('--stop', action='store_true', help='선택한 전시용 AVD만 종료')
    args = parser.parse_args()
    if args.create and (args.stop or args.restore_ready) or args.stop and args.restore_ready:
        parser.error('--create, --stop, --restore-ready는 각각 따로 사용해주세요')
    sdk = sdk_directory()
    name, port, _, _, _ = PROFILES[args.profile]
    directory = create_profile(sdk, args.profile) if args.create else avd_root() / (name + '.avd')
    if not directory.is_dir():
        parser.error('전시용 AVD가 없어요 --create로 먼저 생성해주세요')
    adb = [str(sdk / 'platform-tools/adb'), '-s', 'emulator-' + str(port)]
    started = False
    state = subprocess.run(adb + ['get-state'], text=True, capture_output=True)
    if state.returncode == 0:
        observed = subprocess.check_output(adb + ['emu', 'avd', 'name'], text=True)
        if not observed.splitlines():
            raise RuntimeError('AVD 연결이 정리 중이에요 잠시 후 다시 실행해주세요')
        if observed.splitlines()[0] != name:
            parser.error('포트를 다른 AVD가 사용하고 있어요 해당 AVD는 조작하지 않았어요')
    elif args.stop:
        print('전시용 AVD가 실행 중이 아니에요')
        return
    else:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                raise RuntimeError('실행 포트가 아직 사용 중이에요 잠시 후 다시 실행해주세요')
        log = directory / 'exhibition-emulator.log'
        with log.open('ab') as output:
            process = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', name, '-port', str(port),
                              '-data', str(directory / 'exhibition-userdata.img'),
                              '-gpu', 'host', '-feature', '-Vulkan', '-no-snapshot-load', '-no-snapshot-save'],
                             stdout=output, stderr=output, start_new_session=True)
        (directory / 'exhibition-emulator.pid').write_text(str(process.pid))
        started = True
    if args.stop:
        subprocess.run(adb + ['emu', 'kill'], check=True, capture_output=True)
        closing = time.monotonic() + 40
        while True:
            state = subprocess.run(adb + ['get-state'], capture_output=True, text=True, timeout=3)
            with socket.socket() as probe:
                console_closed = probe.connect_ex(('127.0.0.1', port)) != 0
            process_closed = True
            pid_file = directory / 'exhibition-emulator.pid'
            if pid_file.is_file() and pid_file.read_text().strip().isdigit():
                info = subprocess.run(['ps', '-p', pid_file.read_text().strip(), '-o', 'stat=', '-o', 'command='],
                                      capture_output=True, text=True, timeout=3)
                process_closed = info.returncode != 0 or name not in info.stdout or info.stdout.lstrip().startswith('Z')
            if state.returncode != 0 and console_closed and process_closed:
                break
            if time.monotonic() > closing:
                raise RuntimeError('AVD 종료 요청 뒤 연결 정리를 기다리고 있어요 잠시 후 다시 확인해주세요')
            time.sleep(0.25)
        print(name + ' 종료 완료')
        return
    wait_for_boot(adb, name)
    if args.restore_ready:
        snapshot = 'modu_home_exhibition_ready'
        saved = subprocess.run(adb + ['emu', 'avd', 'snapshot', 'list'], capture_output=True, text=True, timeout=10)
        if saved.returncode or snapshot not in saved.stdout:
            raise RuntimeError('준비 스냅샷이 없어요 현재 전시용 데이터를 유지했어요')
        result = subprocess.run(adb + ['emu', 'avd', 'snapshot', 'load', snapshot], capture_output=True, text=True, timeout=45)
        if result.returncode or 'KO:' in result.stdout or 'OK' not in result.stdout:
            raise RuntimeError('전시용 스냅샷 복원을 확인하지 못했어요')
        wait_for_boot(adb, name)
    user = subprocess.check_output(adb + ['shell', 'am', 'get-current-user'], text=True, timeout=5).strip()
    opened = False
    apk_installed = False
    shell_opened = False
    if args.profile == 'car' and not args.restore_ready and user.isdecimal():
        home = subprocess.run(adb + ['shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', user,
                                     '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'],
                              capture_output=True, text=True, timeout=10)
        component = home.stdout.strip().splitlines()[-1] if home.stdout.strip() else ''
        if home.returncode == 0 and component == 'ai.umos.homescreen/.HomeActivity':
            result = subprocess.run(adb + ['shell', 'am', 'start', '--user', user, '-n', component],
                                    capture_output=True, text=True, timeout=10)
            shell_opened = result.returncode == 0 and 'Error:' not in result.stdout + result.stderr
            deadline = time.monotonic() + 30
            while shell_opened:
                activities = subprocess.run(adb + ['shell', 'dumpsys', 'activity', 'activities'],
                                            capture_output=True, text=True, timeout=5)
                if 'ai.umos.drivingview/.DrivingViewActivity' in activities.stdout:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError('차량 홈의 앱 영역을 아직 확인하지 못했어요 잠시 후 다시 실행해주세요')
                time.sleep(1)
    navigation_opened = False
    if args.profile == 'car' and not args.restore_ready and user.isdecimal():
        native = subprocess.run(adb + ['shell', 'am', 'start', '-W', '--user', user,
                                       '-n', 'ai.umos.maps.android.navigation.app/.MainActivity'],
                                capture_output=True, text=True, timeout=30)
        navigation_opened = native.returncode == 0 and 'Error:' not in native.stdout + native.stderr
        if not navigation_opened:
            raise RuntimeError('기본 차량 내비를 준비하지 못했어요 차량 AVD 상태를 확인해주세요')
        if started:
            subprocess.run(adb + ['emu', 'geo', 'fix', '127.094681', '37.4127212'],
                           capture_output=True, check=True, timeout=5)
    if user.isdecimal():
        installed = subprocess.run(adb + ['shell', 'pm', 'path', '--user', user, 'io.moduhome.app'],
                                   capture_output=True, text=True, timeout=5)
        if installed.returncode == 0 and installed.stdout.startswith('package:'):
            apk_installed = True
            for _ in range(3):
                launched = subprocess.run(adb + ['shell', 'am', 'start', '-W', '--user', user,
                                                 '-n', 'io.moduhome.app/.MainActivity'],
                                          capture_output=True, text=True, timeout=15)
                if launched.returncode or 'Error:' in launched.stdout + launched.stderr:
                    break
                stable = 0
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    activities = subprocess.run(adb + ['shell', 'dumpsys', 'activity', 'activities'],
                                                capture_output=True, text=True, timeout=5)
                    resumed = any('io.moduhome.app/.MainActivity' in line and
                                  ('topResumedActivity=' in line or 'mResumedActivity:' in line)
                                  for line in activities.stdout.splitlines())
                    stable = stable + 1 if resumed else 0
                    if stable >= 5:
                        opened = True
                        break
                    time.sleep(1)
                if opened:
                    break
    if apk_installed and not opened:
        raise RuntimeError('설치된 모두 집으로 화면을 확인하지 못했어요 AVD 화면을 확인해주세요')
    if not opened:
        print('AVD는 준비됐어요 APK 설치와 모두 집으로 화면을 확인해주세요')
    print(json.dumps({'avd': name, 'serial': 'emulator-' + str(port),
                      'persistentUserData': str(directory / 'exhibition-userdata.img'),
                      'readySnapshotRestored': args.restore_ready, 'applicationOpened': opened, 'vehicleShellOpened': shell_opened,
                      'nativeNavigationOpened': navigation_opened,
                      'demoLocationApplied': started and args.profile == 'car' and not args.restore_ready}, ensure_ascii=False))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
