#!/usr/bin/env python3
"""Provision verified Android test inputs and compile the current host filesystem JNI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen

from current_native_jni_guard import required_methods, verify_library

ROOT = Path(__file__).resolve().parents[1]


def digest(path, algorithm='sha512'):
    value = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def ensure_artifact(directory, artifact, offline=False):
    name = artifact['name']
    if Path(name).name != name or not name.endswith('.jar'):
        raise ValueError('Invalid host runtime artifact name')
    target = directory / name
    if target.is_file() and target.stat().st_size == artifact['bytes'] and digest(target) == artifact['sha512']:
        return target
    if offline:
        raise ValueError('Missing or corrupt offline host runtime: ' + name)
    directory.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=directory, suffix='.download', delete=False) as output:
            temporary = Path(output.name)
            size = 0
            with urlopen(artifact['url'], timeout=60) as response:
                for block in iter(lambda: response.read(1024 * 1024), b''):
                    size += len(block)
                    if size > artifact['bytes']:
                        raise ValueError('Host runtime exceeds its pinned size: ' + name)
                    output.write(block)
        if size != artifact['bytes'] or digest(temporary) != artifact['sha512']:
            raise ValueError('Host runtime checksum mismatch: ' + name)
        os.replace(temporary, target)
        return target
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare_runtime(root, output, offline=False):
    manifest = json.loads((root / 'tools/host-test-runtime.json').read_text())
    if manifest.get('schema') != 1 or not manifest.get('artifacts'):
        raise ValueError('Invalid host test runtime inventory')
    for artifact in manifest['artifacts']:
        ensure_artifact(output / 'robolectric', artifact, offline)
    java_home = Path(os.environ.get('JAVA_HOME', '')).resolve()
    if not (java_home / 'include/linux/jni_md.h').is_file():
        raise ValueError('JAVA_HOME must identify the supported Linux JDK')
    native = output / 'native'
    native.mkdir(parents=True, exist_ok=True)
    library = native / 'libdroide_files.so'
    source = root / 'app/src/main/cpp/droide_files.c'
    with tempfile.NamedTemporaryFile(dir=native, suffix='.so', delete=False) as temporary:
        staged = Path(temporary.name)
    try:
        subprocess.run([
            os.environ.get('CC', 'cc'), '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
            '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
            str(source), '-o', str(staged),
        ], check=True)
        declarations = (root / 'app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt').read_text()
        verify_library(staged.read_bytes(), 'x86_64', required_methods(declarations))
        os.replace(staged, library)
    finally:
        staged.unlink(missing_ok=True)
    (output / 'receipt.json').write_text(json.dumps({
        'nativeSourceSha256': digest(source, 'sha256'),
        'nativeLibrarySha256': digest(library, 'sha256'),
        'androidArtifacts': manifest['artifacts'],
        'deviceValidated': False,
    }, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'app/build-host-jvm-tests/host-runtime')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    prepare_runtime(ROOT, args.output.resolve(), args.offline)
    print('HOST_TEST_RUNTIME_VERIFIED')


if __name__ == '__main__':
    main()
