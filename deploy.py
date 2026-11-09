"""Install the offline client: full install (base + home-assets split) when the split changed, otherwise a partial base-only update.
Usage: python3 deploy.py [--serial SERIAL] [--full] [--launch]"""
from pathlib import Path
import argparse, subprocess, sys

PROJECT = Path(__file__).resolve().parent
ADB = PROJECT.parents[2] / 'work/deep-tools/adb36/platform-tools/adb'
BASE = PROJECT / 'offline-lab.apk'
SPLIT = PROJECT / 'offline-split-homeassets.apk'
STAMP = PROJECT / 'offline-split-homeassets.stamp'
INSTALLED = PROJECT / 'offline-split-homeassets.installed'

p = argparse.ArgumentParser()
p.add_argument('--serial', default='3B665C005UA00000')
p.add_argument('--full', action='store_true')
p.add_argument('--launch', action='store_true')
a = p.parse_args()
# The phone over Wi-Fi debugging shows up as adb-<serial>-<id>._adb-tls-connect._tcp: use the attached device carrying this serial.
attached = [l.split()[0] for l in subprocess.run([str(ADB), 'devices'], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip().endswith('device')]
a.serial = next((d for d in attached if d == a.serial), next((d for d in attached if a.serial in d), a.serial))

def adb(*args, timeout=900):
    return subprocess.run([str(ADB), '-s', a.serial, *args], capture_output=True, text=True, timeout=timeout)

state = adb('get-state', timeout=15)
if state.returncode != 0 or state.stdout.strip() != 'device':
    sys.exit(state.stderr.strip() or 'ADB device is not connected/authorized; no installation attempted')
paths = adb('shell', 'pm', 'path', 'lab.coc.offline').stdout
split_on_device = 'split_homeassets' in paths or 'homeassets' in paths
stamp = STAMP.read_text() if STAMP.exists() else ''
need_full = a.full or not split_on_device or not INSTALLED.exists() or INSTALLED.read_text() != stamp

if need_full:
    if not SPLIT.exists(): sys.exit('Split APK missing: build with python3 build.py --home-assets')
    print('Full install (base + home assets split)...', flush=True)
    r = adb('install-multiple', '-r', '--no-incremental', str(BASE), str(SPLIT))
else:
    print('Partial install (base only, split kept)...', flush=True)
    r = adb('install-multiple', '-r', '--no-incremental', '-p', 'lab.coc.offline', str(BASE))
print(r.stdout.strip() or r.stderr.strip())
if r.returncode != 0 or 'Success' not in r.stdout: sys.exit(1)
if need_full: INSTALLED.write_text(stamp)
if a.launch:
    adb('shell', 'am', 'start', '-S', '-n', 'lab.coc.offline/.RenderActivity', '--ez', 'localHome', 'true')
    print('Launched', flush=True)
