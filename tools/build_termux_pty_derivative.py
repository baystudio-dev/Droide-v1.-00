#!/usr/bin/env python3
"""Build the fixed reviewed R46 Java-only PTY derivative twice, offline.

Requires explicit pinned JDK and API36 android.jar. Both javac invocations use new
separate roots, retained copied inputs, an empty sourcepath, and no processors or
implicit sources. Publication is atomic and never replaces any existing output.
This creates no APK, native build, release approval or device/runtime evidence.
"""
from __future__ import annotations

import argparse
import ctypes
from datetime import datetime, timezone
import errno
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time
import zlib

import verify_termux_pty_derivative as V


def write_json(path: Path, value):
    with path.open('xb') as stream:
        stream.write(V.json_bytes(value))


def copy_inputs(root: Path, work: Path, android_jar: Path) -> dict[str, str]:
    copied = {}
    original_jar = V.archive_entries(V.checked_file(root, V.ORIGINAL_AAR))['classes.jar']
    contents = {'inputs/android.jar': V.file_bytes(android_jar), 'inputs/original-classes.jar': original_jar}
    if V.digest(contents['inputs/android.jar']) != V.ANDROID_JAR_SHA256:
        V.fail('Android API36 input identity changed during copying')
    for class_name, name in zip(V.OVERLAYS, V.source_copy_paths()):
        original = V.SOURCE if class_name == 'TerminalPtyIo' else 'third_party/termux/patches/src/com/termux/terminal/' + class_name + '.java'
        contents[name] = V.checked_file(root, original)
    for name, data in contents.items():
        target = work / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream: stream.write(data)
        copied[name] = V.digest(data)
    return copied


def copied_inputs(work: Path) -> dict[str, str]:
    return {'inputs/' + name: sha for name, sha in V.regular_tree(work / 'inputs').items()}


def utc():
    return datetime.now(timezone.utc).isoformat()


def compile_one(root: Path, work: Path, java_home: Path, android_jar: Path,
                inputs: dict[str, str], tool_identity: dict) -> dict:
    work.mkdir()  # Exclusive creation: neither build can reuse an output directory.
    (work / 'compiled').mkdir()
    (work / 'empty-sourcepath').mkdir()
    copied = copy_inputs(root, work, android_jar)
    identity = V.verify_jdk(java_home, root)
    if identity != tool_identity: V.fail('JDK changed before compilation')
    if V.source_inputs(root) != inputs: V.fail('repository changed before compilation')
    expected = {'repository': inputs, 'copiedInputs': copied, 'jdkManifestSha256': V.JDK_MANIFEST_SHA256}
    write_json(work / 'inputs-before.json', expected)
    write_json(work / 'tool-identities.json', identity)
    version_argv = [str(java_home / 'bin/javac'), '-version']
    version = subprocess.run(version_argv, cwd=work, env=V.SAFE_ENVIRONMENT,
                             capture_output=True, timeout=30)
    (work / 'javac-version.stdout.log').write_bytes(version.stdout)
    (work / 'javac-version.stderr.log').write_bytes(version.stderr)
    write_json(work / 'version-command.json', {'argv': version_argv, 'cwd': str(work),
               'environment': V.SAFE_ENVIRONMENT, 'returnCode': version.returncode})
    if version.returncode != 0 or version.stdout != b'javac 17.0.20.1\n' or version.stderr:
        V.fail('pinned javac actual version output mismatch')
    argv = V.compiler_command(work, java_home)
    if list((work / 'compiled').iterdir()) or list((work / 'empty-sourcepath').iterdir()):
        V.fail('new compiler output/sourcepath was not empty')
    started = utc(); clock = time.monotonic_ns()
    result = subprocess.run(argv, cwd=work, env=V.SAFE_ENVIRONMENT, capture_output=True, timeout=180)
    duration = time.monotonic_ns() - clock; finished = utc()
    (work / 'javac.stdout.log').write_bytes(result.stdout)
    (work / 'javac.stderr.log').write_bytes(result.stderr)
    command = {'argv': argv, 'cwd': str(work), 'javaHome': str(java_home),
               'environment': V.SAFE_ENVIRONMENT, 'returnCode': result.returncode,
               'startedAtUtc': started, 'finishedAtUtc': finished, 'durationNanoseconds': duration,
               'outputDirectoryInitiallyEmpty': True, 'freshDirectoryCreated': True}
    write_json(work / 'command.json', command)
    after_inputs = V.source_inputs(root)
    after_jdk = V.verify_jdk(java_home, root)
    after = {'repository': after_inputs, 'copiedInputs': copied_inputs(work),
             'jdkManifestSha256': after_jdk['manifestSha256']}
    write_json(work / 'inputs-after.json', after)
    if after != expected or after_jdk != identity: V.fail('build input race: before/after identity differs')
    if result.returncode != 0:
        V.fail('actual javac failed; raw command/output retained in ' + str(work))
    replacement = {name: V.checked_file(work / 'compiled', name) for name in V.regular_tree(work / 'compiled')}
    wanted = set(V.read_json(root, V.JAVA_RECEIPT)['replacementClassSha256ByPath'])
    if set(replacement) != wanted: V.fail('compiled overlay family scope differs')
    original = V.archive_entries(V.checked_file(root, V.ORIGINAL_AAR))
    classes = V.archive_entries(original['classes.jar'])
    classes.update(replacement)
    original['classes.jar'] = V.deterministic_zip(classes)
    output = V.deterministic_zip(original)
    V.verify_aar_bytes(root, output)
    (work / 'terminal-emulator-r46-private.aar').write_bytes(output)
    return {'aarSha256': V.digest(output),
            'compiledSha256ByPath': {n: V.digest(d) for n, d in sorted(replacement.items())},
            'commandSha256': V.digest(V.file_bytes(work / 'command.json'))}


def publish_noreplace(source: Path, destination: Path):
    """Atomic directory publication on Linux, with kernel-enforced no replacement."""
    libc = ctypes.CDLL(None, use_errno=True)
    if not hasattr(libc, 'renameat2'): V.fail('atomic no-replace publication unavailable on this host')
    rename = libc.renameat2
    rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    rename.restype = ctypes.c_int
    if rename(-100, os.fsencode(source), -100, os.fsencode(destination), 1) != 0:
        code = ctypes.get_errno()
        if code == errno.EEXIST: V.fail('publication exists; existing artifacts are never replaced')
        raise OSError(code, os.strerror(code), str(destination))
    descriptor = os.open(destination.parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)


def build(root: Path, java_home: Path, android_jar: Path) -> dict:
    root = V.no_symlinks(root)
    java_home = V.no_symlinks(java_home)
    android_jar = V.no_symlinks(android_jar)
    destination = V.no_symlinks(root / V.PUBLICATION)
    if destination.exists(): V.fail('publication already exists; refusing to replace artifacts')
    if V.digest(V.file_bytes(android_jar)) != V.ANDROID_JAR_SHA256:
        V.fail('only the pinned API36 android.jar is admitted; no download is attempted')
    baseline = V.run_baseline_gate(root)
    inputs = V.source_inputs(root)
    tools = V.verify_jdk(java_home, root)
    parent = root / V.BASE
    # Failure evidence is preserved under this unique path, never renamed over old runs.
    staging = Path(tempfile.mkdtemp(prefix='.fresh-java-build-', dir=parent))
    print('R46_JAVA_BUILD_EVIDENCE=' + str(staging), flush=True)
    write_json(staging / 'baseline-gate.json', baseline)
    write_json(staging / 'python-identity.json', {
        'executable': sys.executable, 'executableSha256': V.digest(Path(sys.executable).read_bytes()),
        'version': sys.version, 'implementation': platform.python_implementation(),
        'zlibVersion': zlib.ZLIB_VERSION, 'zlibRuntimeVersion': zlib.ZLIB_RUNTIME_VERSION,
    })
    builds = {}
    for stage in ('primary', 'independent'):
        builds[stage] = compile_one(root, staging / stage, java_home, android_jar, inputs, tools)
    first = V.file_bytes(staging / 'primary/terminal-emulator-r46-private.aar')
    second = V.file_bytes(staging / 'independent/terminal-emulator-r46-private.aar')
    if first != second: V.fail('separate genuine Java builds are not byte-identical')
    (staging / 'terminal-emulator-r46-private.aar').write_bytes(first)
    inspection = V.verify_aar_bytes(root, first)
    if V.source_inputs(root) != inputs: V.fail('repository inputs changed before publication')
    receipt = {'schemaVersion': 1, 'artifactKind': V.KIND, 'status': V.STATUS,
               'releaseCompatible': False, 'deviceValidated': False, 'executionEvidence': None,
               'nativeBuildPerformed': False, 'sourceInputSha256ByPath': inputs,
               'baselineReceiptSha256': V.digest(V.checked_file(root, V.PRIVATE_RECEIPT)),
               'verifierSha256': inputs[V.VERIFIER], 'builderSha256': inputs[V.BUILDER],
               'output': {'path': V.AAR, 'sha256': V.digest(first)}, 'builds': builds,
               'evidenceSha256ByPath': V.regular_tree(staging), 'aarInspection': inspection}
    write_json(staging / 'receipt.json', receipt)
    V.verify_publication(root, staging, inputs)
    if V.source_inputs(root) != inputs: V.fail('source changed at publication boundary')
    for path in staging.rglob('*'):
        if path.is_file():
            with path.open('rb') as stream: os.fsync(stream.fileno())
    for path in [*sorted((p for p in staging.rglob('*') if p.is_dir()), reverse=True), staging]:
        descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    publish_noreplace(staging, destination)
    # A published result is not reported successful until the full public gate passes.
    return V.verify_derivative_inputs(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=V.ROOT)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build(args.root, args.java_home, args.android_jar)
        print('TERMUX_PTY_DERIVATIVE_BUILT sha256=' + result['output']['sha256'] +
              ' independent_builds=2 release_compatible=false device_validated=false')
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        V.fail(str(error))


if __name__ == '__main__':
    main()
