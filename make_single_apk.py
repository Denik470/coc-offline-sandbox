"""Combine the signed base and assets-only split into one normally installable APK.

The result is re-aligned and signed with the existing sandbox certificate.
Optional portable save assets seed only a fresh app installation.
"""
from pathlib import Path, PurePosixPath
import argparse
import copy
import hashlib
import json
import shutil
import subprocess
import tarfile
import time
import zipfile

PROJECT = Path(__file__).resolve().parent
WS = PROJECT.parents[2]
TOOLS = WS / 'work/android-sdk/build-tools/36.0.0'
TEMP = WS / 'work/single-apk-build'
JAVA_HOME = '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', required=True)
p.add_argument('--state-tar', type=Path)
p.add_argument('--exclude', action='append', default=[], help='leave out entries under this path prefix (repeatable)')
a = p.parse_args()
output = Path(a.output).resolve()
output.parent.mkdir(parents=True, exist_ok=True)
TEMP.mkdir(parents=True, exist_ok=True)
unsigned = TEMP / 'merged-unsigned.apk'
aligned = TEMP / 'merged-aligned.apk'
expected = {}
last_report = time.monotonic()


def signature(name):
    upper = name.upper()
    return upper.startswith('META-INF/') and (upper.endswith(('.SF', '.RSA', '.DSA', '.EC')) or upper == 'META-INF/MANIFEST.MF')


def run(args):
    import os
    subprocess.run([str(x) for x in args], check=True, env={**os.environ, 'JAVA_HOME': JAVA_HOME})


with zipfile.ZipFile(unsigned, 'w', allowZip64=False) as target:
    for apk, asset_only in [(PROJECT / 'offline-lab.apk', False), (PROJECT / 'offline-split-homeassets.apk', True)]:
        with zipfile.ZipFile(apk) as source:
            for original in source.infolist():
                if original.is_dir() or signature(original.filename) or (asset_only and not original.filename.startswith('assets/')):
                    continue
                if any(original.filename.startswith(x) for x in a.exclude):
                    continue
                if original.filename in expected:
                    digest = hashlib.sha256()
                    with source.open(original) as stream:
                        for block in iter(lambda: stream.read(1024 * 1024), b''):
                            digest.update(block)
                    if expected[original.filename] != {'size': original.file_size, 'sha256': digest.hexdigest()}:
                        raise RuntimeError('Conflicting package member: ' + original.filename)
                    continue
                info = copy.copy(original)
                info._compresslevel = 9  # level 1 re-deflated the sources' deflated members larger and pushed the APK past 2 GiB
                digest = hashlib.sha256()
                with source.open(original) as stream, target.open(info, 'w') as dest:
                    while True:
                        block = stream.read(1024 * 1024)
                        if not block:
                            break
                        digest.update(block)
                        dest.write(block)
                expected[original.filename] = {'size': original.file_size, 'sha256': digest.hexdigest()}
                if time.monotonic() - last_report > 20:
                    print('Merged', len(expected), 'entries', flush=True)
                    last_report = time.monotonic()
    if a.state_tar:
        with tarfile.open(a.state_tar) as state:
            total = 0
            for member in state:
                name = PurePosixPath(member.name)
                if not member.isfile():
                    continue
                if name.is_absolute() or '..' in name.parts or len(name.parts) < 2 or name.parts[0] != 'files':
                    raise RuntimeError('Unsafe save path: ' + member.name)
                relative = PurePosixPath(*name.parts[1:])
                if str(relative) not in ('save-home.json', 'save-avatar.bin', 'defense-cc.json', 'modifier.txt', 'scenery-choice.txt', 'profile.txt') and relative.parts[0] not in ('layout-bank', 'profiles', 'replays'):
                    raise RuntimeError('Unexpected portable save file: ' + str(relative))
                total += member.size
                if total > 64 * 1024 * 1024:
                    raise RuntimeError('Portable state exceeds size limit')
                content = state.extractfile(member).read()
                path = 'assets/portable-state/' + str(relative)
                if path in expected:
                    raise RuntimeError('Duplicate portable state member')
                target.writestr(path, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
                expected[path] = {'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()}
        for name in ['save-home.json', 'save-avatar.bin']:
            if 'assets/portable-state/' + name not in expected:
                raise RuntimeError('Portable state missing: ' + name)
if unsigned.stat().st_size >= 2 ** 31:
    raise RuntimeError('Merged APK exceeds conservative 2 GiB compatibility limit')
print('Aligning single APK...', flush=True)
run([TOOLS / 'zipalign', '-P', '16', '-f', '4', unsigned, aligned])
unsigned.unlink()
print('Signing with existing sandbox certificate...', flush=True)
run([TOOLS / 'apksigner', 'sign', '--ks', WS / 'work/android-offline-build/lab.keystore', '--ks-pass', 'pass:offline-lab', '--v4-signing-enabled', 'false', '--out', output, aligned])
run([TOOLS / 'apksigner', 'verify', output])
run([TOOLS / 'zipalign', '-c', '-P', '16', '4', output])
print('Verifying merged code, resources and save assets...', flush=True)
with zipfile.ZipFile(output) as result:
    actual = {i.filename for i in result.infolist() if not i.is_dir() and not signature(i.filename)}
    if actual != set(expected):
        raise RuntimeError('Final APK content set differs from inputs')
    for name, details in expected.items():
        digest = hashlib.sha256()
        with result.open(name) as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(block)
        if result.getinfo(name).file_size != details['size'] or digest.hexdigest() != details['sha256']:
            raise RuntimeError('Final APK content mismatch: ' + name)
aligned.unlink()
digest = hashlib.sha256()
with output.open('rb') as f:
    for block in iter(lambda: f.read(8 * 1024 * 1024), b''):
        digest.update(block)
report = {'apk': str(output), 'bytes': output.stat().st_size, 'sha256': digest.hexdigest(), 'verified_members': len(expected), 'portable_seed_included': bool(a.state_tar), 'original_package_still_required': True}
(output.parent / 'BUILD_CHECK.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report), flush=True)
