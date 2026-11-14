#!/usr/bin/env python3
"""Produce an ephemeral D8 reference from the admitted candidate; never reuse one.

No download, SDK D8 fallback, caller hash override, persistent reference input,
or receipt-based reference authorization is provided. The manifest and exact
R8/android pins are reviewed trust roots. An output receipt records what actually
ran; it is diagnostic evidence, never a substitute for running this function.
"""
from __future__ import annotations

from contextlib import contextmanager
import json
from pathlib import Path
import re
import subprocess
import tempfile

from verify_pty_dex_binding import BindingError, checked_archive, digest, require, MAX_FILE, DEX_NAME

R8_VERSION = '9.1.29'
R8_SHA256 = '9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a'
ANDROID_SHA256 = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'
MIN_API = 29


def regular_bytes(path: Path):
    path = path.absolute()
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlinked binding input: ' + str(path))
    require(path.is_file() and path.stat().st_size <= MAX_FILE, 'missing/oversized binding input: ' + str(path))
    return path.read_bytes()


@contextmanager
def build_fresh_reference(candidate_aar: Path, d8_jar: Path, android_jar: Path, java_home: Path):
    from verify_termux_pty_derivative import verify_jdk
    jdk_before = verify_jdk(java_home)
    originals = {str(p.absolute()): regular_bytes(p) for p in (candidate_aar, d8_jar, android_jar)}
    aar = originals[str(candidate_aar.absolute())]
    r8 = originals[str(d8_jar.absolute())]
    android = originals[str(android_jar.absolute())]
    require(digest(r8) == R8_SHA256, 'requires exact project R8 9.1.29; SDK D8 is not a substitute')
    require(digest(android) == ANDROID_SHA256, 'requires pinned API 36 android.jar')
    aar_entries = checked_archive(aar)
    require('classes.jar' in aar_entries, 'candidate AAR has no classes.jar')
    classes = aar_entries['classes.jar']
    jar_entries = checked_archive(classes)
    require('com/termux/terminal/TerminalPtyIo.class' in jar_entries, 'candidate lacks PTY class')
    require(not any(name.endswith('.dex') for name in jar_entries), 'candidate classes.jar contains DEX')
    with tempfile.TemporaryDirectory(prefix='r46-fresh-pty-d8-') as directory:
        work = Path(directory)
        home, out = work / 'home', work / 'out'
        home.mkdir()
        out.mkdir()
        (work / 'candidate-classes.jar').write_bytes(classes)
        (work / 'r8.jar').write_bytes(r8)
        (work / 'android.jar').write_bytes(android)
        command = [str((java_home / 'bin/java').absolute()), '-Xshare:off',
                   '-Djava.io.tmpdir=' + str(work), '-Duser.home=' + str(home),
                   '-cp', str(work / 'r8.jar'), 'com.android.tools.r8.D8',
                   '--debug', '--min-api', str(MIN_API), '--lib', str(work / 'android.jar'),
                   '--output', str(out), str(work / 'candidate-classes.jar')]
        environment = {'PATH': str((java_home / 'bin').absolute()), 'HOME': str(home),
                       'TMPDIR': str(work), 'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}
        try:
            completed = subprocess.run(command, cwd=work, env=environment, capture_output=True, timeout=180)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise BindingError('fresh pinned D8 invocation failed: ' + str(exc)) from exc
        require(completed.returncode == 0, 'fresh D8 failed: ' + completed.stderr.decode('utf-8', errors='replace'))
        entries = {}
        for path in out.iterdir():
            require(DEX_NAME.fullmatch(path.name) and path.is_file() and not path.is_symlink(),
                    'unexpected D8 output')
            entries[path.name] = regular_bytes(path)
        require('classes.dex' in entries, 'fresh D8 produced no classes.dex')
        # Check both original inputs and the exact temporary compiler inputs after invocation.
        require(all(regular_bytes(Path(p)) == data for p, data in originals.items()), 'binding input changed during D8')
        require((work / 'candidate-classes.jar').read_bytes() == classes and
                (work / 'r8.jar').read_bytes() == r8 and (work / 'android.jar').read_bytes() == android,
                'temporary D8 input changed during invocation')
        require(verify_jdk(java_home) == jdk_before, 'JDK changed during D8')
        provenance = {
            'schemaVersion': 1, 'kind': 'FRESH_CANDIDATE_D8_REFERENCE',
            'candidateAarSha256': digest(aar), 'classesJarSha256': digest(classes),
            'ptyClassSha256': digest(jar_entries['com/termux/terminal/TerminalPtyIo.class']),
            'd8Version': R8_VERSION, 'd8JarSha256': digest(r8),
            'androidJarSha256': digest(android), 'javaIdentity': jdk_before,
            'minApi': MIN_API, 'mode': 'debug', 'command': command, 'environment': environment,
            'returnCode': completed.returncode,
            'stdout': completed.stdout.decode('utf-8', errors='replace'),
            'stderr': completed.stderr.decode('utf-8', errors='replace'),
            'dexSha256ByPath': {name: digest(data) for name, data in sorted(entries.items())},
            'builderSha256': digest(Path(__file__).read_bytes()),
            'verifierSha256': digest(Path(__file__).with_name('verify_pty_dex_binding.py').read_bytes()),
            'freshInvocation': True, 'runtimeEvidence': None, 'releaseCompatible': False,
            'deviceValidated': False,
        }
        yield {'dexEntries': entries, 'provenance': provenance}
        require(all(regular_bytes(Path(p)) == data for p, data in originals.items()),
                'binding input changed before verification completed')
        require(verify_jdk(java_home) == jdk_before, 'JDK changed before verification completed')
