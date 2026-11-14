#!/usr/bin/env python3
"""Fail-closed R46 Java-only derivative admission. No Android code is executed.

The frozen, original private provenance gate runs against the actual historical
paths first. Its success cannot substitute for the independent derivative gate.
There is no caller-selectable AAR, receipt, source, lock or baseline path.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import struct
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = 'third_party/termux/derivatives/r46'
PUBLICATION = BASE + '/build'
AAR = PUBLICATION + '/terminal-emulator-r46-private.aar'
RECEIPT = PUBLICATION + '/receipt.json'
SOURCE = BASE + '/src/com/termux/terminal/TerminalPtyIo.java'
PATCH = BASE + '/0001-reset-reader-budget-after-enqueue.patch'
HISTORICAL_AAR = 'app/libs/terminal-emulator-v0.118.0-droide-input.aar'
ORIGINAL_AAR = 'app/libs/terminal-emulator-v0.118.0-java.aar'
SOURCE_JAR = 'third_party/termux/terminal-emulator-v0.118.0-sources.jar'
JAVA_RECEIPT = 'third_party/termux/patches/REBUILD.json'
PRIVATE_RECEIPT = 'third_party/termux/native/private-debug/receipt.json'
CONTRACT = 'third_party/termux/native/OWNED_PROCESS_ABI.json'
BASELINE_SOURCE = 'third_party/termux/patches/src/com/termux/terminal/TerminalPtyIo.java'
BUILDER = 'tools/build_termux_pty_derivative.py'
VERIFIER = 'tools/verify_termux_pty_derivative.py'
BASELINE_SHA256 = '0951b735f98caf03acf0f77eb18946828d9276cb2d882ed79c81062f5b6a68b9'
JDK_MANIFEST_SHA256 = '262fc3ceaeecc4e9ef8efaefc74af78bb304957bad8bb448138ef8930e4cdc44'
SOURCE_SHA256 = '01c470e72e74106426d494849f61d42d9fdb456546b506b393895316148f48c4'
PATCH_SHA256 = 'fdc5b087de5192d7d8fa49909afe0564595547a9c93dbbf7b638d4135be07c8c'
BUILDER_SHA256 = '4d198635129caa87ea7f84ce1c1a80ed2dc57820a8ea1c29eb500a2e822f86b7'
# Set from inspected, genuinely compiled output before final double-build admission.
PTY_CLASS_SHA256 = 'a862712e743f276925bfdc480e00dabd9e31a4cc54c68aae0af38eb0e84a3e1f'
ANDROID_JAR_SHA256 = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'
PTY_CLASS = 'com/termux/terminal/TerminalPtyIo.class'
JNI_CLASS = 'com/termux/terminal/JNI.class'
KIND = 'R46_PRIVATE_JAVA_PTY_DERIVATIVE'
STATUS = 'BUILT_DEVICE_UNVALIDATED'
OVERLAYS = ('TerminalSession', 'TerminalRow', 'TerminalEmulator', 'TerminalBuffer',
            'ByteQueue', 'TerminalMemoryBudget', 'JNI', 'TerminalProcessOwner', 'TerminalPtyIo')
COMPILER_ARGUMENTS = ['--release', '8', '-encoding', 'UTF-8', '-g:none', '-proc:none', '-implicit:none']
SAFE_ENVIRONMENT = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}


def fail(message: str):
    raise SystemExit('TERMUX_PTY_DERIVATIVE_FAILED: ' + message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_bytes(value) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode('utf-8')


def no_symlinks(path: Path) -> Path:
    path = path.absolute()
    for current in [path, *path.parents]:
        if current.is_symlink():
            fail('symlinked input/output path: ' + str(current))
    return path


def file_bytes(path: Path) -> bytes:
    no_symlinks(path)
    if not path.is_file() or not stat.S_ISREG(path.stat().st_mode):
        fail('missing or nonregular file: ' + str(path))
    return path.read_bytes()


def checked_file(root: Path, relative: str, wanted: str | None = None) -> bytes:
    if not isinstance(relative, str) or '\\' in relative or ':' in relative:
        fail('invalid relative path')
    parts = relative.split('/')
    if not relative or any(part in ('', '.', '..') for part in parts) or Path(relative).is_absolute():
        fail('non-local path: ' + relative)
    data = file_bytes(root / relative)
    if wanted is not None and digest(data) != wanted:
        fail('SHA-256 mismatch: ' + relative)
    return data


def read_json(root: Path, relative: str, wanted: str | None = None):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result: fail('duplicate JSON key: ' + key)
            result[key] = value
        return result
    try:
        return json.loads(checked_file(root, relative, wanted), object_pairs_hook=pairs)
    except (ValueError, UnicodeError) as error:
        fail('invalid JSON ' + relative + ': ' + str(error))


def regular_tree(root: Path) -> dict[str, str]:
    no_symlinks(root)
    if not root.is_dir(): fail('missing directory: ' + str(root))
    result = {}
    for path in sorted(root.rglob('*')):
        no_symlinks(path)
        if path.is_dir(): continue
        result[path.relative_to(root).as_posix()] = digest(file_bytes(path))
    return result


def pinned_baseline(root: Path) -> dict[str, str]:
    manifest = read_json(root, BASE + '/BASELINE.json', BASELINE_SHA256)
    expected = manifest['sha256ByPath']
    for relative, wanted in expected.items(): checked_file(root, relative, wanted)
    actual = set()
    for prefix in manifest['protectedTrees']:
        for relative in regular_tree(root / prefix):
            name = prefix + '/' + relative
            if name.startswith('third_party/termux/derivatives/'):
                continue
            actual.add(name)
    expected_trees = {p for p in expected if any(p.startswith(t + '/') for t in manifest['protectedTrees'])}
    if actual != expected_trees: fail('historical protected input file set changed')
    return expected


def run_baseline_gate(root: Path) -> dict:
    """Pin the historical executables BEFORE invoking their original gate."""
    pinned_baseline(root)
    argv = [sys.executable, '-B', '-E', '-s', str(root / 'tools/verify_termux_provenance.py'),
            '--private-debug-device-unvalidated']
    result = subprocess.run(argv, cwd=root, env=SAFE_ENVIRONMENT, capture_output=True, timeout=120)
    if result.returncode != 0:
        fail('original untouched baseline gate rejected actual baseline:\n' +
             (result.stdout + result.stderr).decode('utf-8', errors='replace'))
    pinned_baseline(root)
    return {'argv': argv, 'cwd': str(root), 'environment': SAFE_ENVIRONMENT,
            'returnCode': result.returncode, 'stdout': result.stdout.decode('utf-8'),
            'stderr': result.stderr.decode('utf-8')}


def verify_jdk(java_home: Path, root: Path | None = None) -> dict:
    """Verify every recovered JDK file and mode; never trust a supplied java shim."""
    root = ROOT if root is None else no_symlinks(Path(root))
    java_home = no_symlinks(Path(java_home))
    manifest = read_json(root, BASE + '/JDK17.json', JDK_MANIFEST_SHA256)
    actual = regular_tree(java_home)
    if set(actual) != set(manifest['files']): fail('pinned JDK complete file set changed')
    for name, wanted in manifest['files'].items():
        if actual[name] != wanted['sha256'] or (java_home / name).stat().st_mode & 0o777 != wanted['mode']:
            fail('pinned JDK file identity/mode changed: ' + name)
    return {'javaHome': str(java_home), 'version': manifest['version'],
            'manifestSha256': JDK_MANIFEST_SHA256, 'files': manifest['files']}


def archive_entries(raw: bytes) -> dict[str, bytes]:
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)): fail('duplicate archive entry')
            result = {}
            for entry in archive.infolist():
                name = entry.filename
                parts = name[:-1].split('/') if name.endswith('/') else name.split('/')
                if (not name or '\\' in name or ':' in name or name.startswith('/') or
                        any(p in ('', '.', '..') for p in parts) or '\0' in name):
                    fail('unsafe archive entry path: ' + name)
                kind = stat.S_IFMT(entry.external_attr >> 16)
                if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or entry.flag_bits & 1:
                    fail('nonregular/encrypted archive entry: ' + name)
                if entry.file_size > 256 * 1024 * 1024: fail('oversized archive entry')
                result[name] = archive.read(entry)
            return result
    except (zipfile.BadZipFile, RuntimeError, KeyError) as error:
        fail('invalid archive: ' + str(error))


def deterministic_zip(entries: dict[str, bytes]) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o40755 if name.endswith('/') else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)
    return stream.getvalue()


def parse_class(data: bytes) -> dict:
    """Bounded classfile parsing, including modifiers, descriptors and constants.

    No class loading. Constant-pool raw identity is intentionally required by the
    derivative comparison, so attributes cannot conceal changed symbolic values.
    """
    pos = 0
    def take(n):
        nonlocal pos
        if n < 0 or pos + n > len(data): fail('truncated classfile')
        out = data[pos:pos + n]; pos += n
        return out
    def u1(): return int.from_bytes(take(1), 'big')
    def u2(): return int.from_bytes(take(2), 'big')
    def u4(): return int.from_bytes(take(4), 'big')
    if take(4) != b'\xca\xfe\xba\xbe': fail('invalid class magic')
    minor, major = u2(), u2()
    count = u2(); pool_start = pos; cp = [None] * count; index = 1
    while index < count:
        tag = u1()
        if tag == 1: value = take(u2())
        elif tag in (3, 4): value = take(4)
        elif tag in (5, 6): value = take(8)
        elif tag in (7, 8, 16, 19, 20): value = u2()
        elif tag in (9, 10, 11, 12, 17, 18): value = (u2(), u2())
        elif tag == 15: value = (u1(), u2())
        else: fail('unsupported class constant-pool tag')
        cp[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1
    pool_bytes = data[pool_start:pos]
    def constant(index, tag=None):
        if index < 1 or index >= len(cp) or cp[index] is None: fail('invalid class constant index')
        value = cp[index]
        if tag is not None and value[0] != tag: fail('class constant type mismatch')
        return value[1]
    def utf(index):
        try: return constant(index, 1).decode('utf-8')
        except UnicodeError: fail('invalid class identifier UTF-8')
    def cls(index): return utf(constant(index, 7)) if index else None
    def attributes():
        out = {}
        for _ in range(u2()):
            name = utf(u2()); value = take(u4())
            if name in out: fail('duplicate class attribute')
            out[name] = value
        return out
    access, name, super_name = u2(), cls(u2()), cls(u2())
    interfaces = [cls(u2()) for _ in range(u2())]
    def members():
        out = []
        for _ in range(u2()):
            flags, member_name, descriptor = u2(), utf(u2()), utf(u2())
            attrs = attributes(); value = None
            if 'ConstantValue' in attrs:
                if len(attrs['ConstantValue']) != 2: fail('invalid ConstantValue attribute')
                k = int.from_bytes(attrs['ConstantValue'], 'big')
                if k <= 0 or k >= len(cp) or cp[k] is None: fail('invalid ConstantValue index')
                value = cp[k]
                if value[0] == 8: value = (8, constant(value[1], 1))
                elif value[0] not in (3, 4, 5, 6): fail('invalid ConstantValue type')
            out.append({'access': flags, 'name': member_name, 'descriptor': descriptor,
                        'constant': value, 'attributes': attrs})
        if len({(m['name'], m['descriptor']) for m in out}) != len(out): fail('duplicate class member')
        return out
    fields, methods, attrs = members(), members(), attributes()
    if pos != len(data): fail('trailing class bytes')
    return {'minor': minor, 'major': major, 'constantPool': pool_bytes, 'access': access,
            'name': name, 'super': super_name, 'interfaces': interfaces,
            'fields': fields, 'methods': methods, 'attributes': attrs}


def compare_pty_structure(before: bytes, after: bytes):
    old, new = parse_class(before), parse_class(after)
    if old['major'] != 52 or new['major'] != 52: fail('PTY class is not Java 8 bytecode')
    for key in ('minor', 'major', 'constantPool', 'access', 'name', 'super', 'interfaces', 'fields', 'attributes'):
        if old[key] != new[key]: fail('PTY class interface/modifier/constant drift: ' + key)
    if len(old['methods']) != len(new['methods']): fail('PTY method count changed')
    changed = []
    for prior, current in zip(old['methods'], new['methods']):
        for key in ('access', 'name', 'descriptor', 'constant'):
            if prior[key] != current[key]: fail('PTY method interface/modifier drift: ' + key)
        left, right = dict(prior['attributes']), dict(current['attributes'])
        left_code, right_code = left.pop('Code', None), right.pop('Code', None)
        if left != right: fail('PTY method non-Code attributes changed')
        if left_code != right_code: changed.append(prior['name'])
    if changed != ['readUntilClosed']: fail('only readUntilClosed implementation may change')


def verify_aar_bytes(root: Path, candidate: bytes, require_reviewed_hash: bool = True) -> dict:
    original = archive_entries(checked_file(root, HISTORICAL_AAR))
    actual = archive_entries(candidate)
    if set(actual) != set(original): fail('derivative AAR entry set changed')
    if any(n.startswith(('jni/', 'lib/')) for n in actual): fail('derivative AAR contains native payload')
    for name in original:
        if name != 'classes.jar' and actual[name] != original[name]: fail('unrelated AAR entry changed: ' + name)
    baseline_classes = archive_entries(original['classes.jar'])
    classes = archive_entries(actual['classes.jar'])
    if set(classes) != set(baseline_classes): fail('derivative classes.jar entry set changed')
    changed = sorted(name for name in classes if classes[name] != baseline_classes[name])
    if changed != [PTY_CLASS]: fail('exactly TerminalPtyIo.class must differ: ' + repr(changed))
    compare_pty_structure(baseline_classes[PTY_CLASS], classes[PTY_CLASS])
    if require_reviewed_hash and digest(classes[PTY_CLASS]) != PTY_CLASS_SHA256:
        fail('PTY implementation differs from reviewed actual compiler output')
    for name, data in classes.items():
        if name.endswith('.class'):
            parsed = parse_class(data)
            if parsed['name'] + '.class' != name: fail('class path/declaration mismatch')
    jni = parse_class(classes[JNI_CLASS]); natives = []
    for method in jni['methods']:
        if method['access'] & 0x100:
            if not method['access'] & 0x8 or 'Code' in method['attributes']: fail('JNI declaration is not static native')
            natives.append({'name': method['name'], 'descriptor': method['descriptor']})
    methods = read_json(root, CONTRACT)['registeredNativeMethods']
    key = lambda m: (m['name'], m['descriptor'])
    if len(natives) != 12 or sorted(natives, key=key) != sorted(methods, key=key): fail('twelve JNI declarations differ')
    if actual['classes.jar'] != deterministic_zip(classes) or candidate != deterministic_zip(actual):
        fail('derivative ZIP is not the deterministic approved recipe')
    return {'aarSha256': digest(candidate), 'classesJarSha256': digest(actual['classes.jar']),
            'ptyClassSha256': digest(classes[PTY_CLASS]), 'jniClassSha256': digest(classes[JNI_CLASS]),
            'classSha256ByPath': {n: digest(d) for n, d in sorted(classes.items())},
            'jniNativeMethods': natives, 'onlyChangedClass': PTY_CLASS}


def source_inputs(root: Path) -> dict[str, str]:
    baseline = pinned_baseline(root)
    candidate = checked_file(root, SOURCE, SOURCE_SHA256)
    before = checked_file(root, BASELINE_SOURCE)
    needle = b'                backend.queued();\n'
    if before.count(needle) != 1 or candidate != before.replace(needle, needle + b'                immediate = 0;\n'):
        fail('candidate is not exact reviewed one-line source delta')
    expected = ''.join(difflib.unified_diff(before.decode().splitlines(True), candidate.decode().splitlines(True),
                        fromfile='a/com/termux/terminal/TerminalPtyIo.java',
                        tofile='b/com/termux/terminal/TerminalPtyIo.java')).encode()
    if checked_file(root, PATCH, PATCH_SHA256) != expected: fail('reviewed derivative patch mismatch')
    if set(regular_tree(root / BASE / 'src')) != {'com/termux/terminal/TerminalPtyIo.java'}:
        fail('derivative source file scope changed')
    checked_file(root, BUILDER, BUILDER_SHA256)
    read_json(root, BASE + '/JDK17.json', JDK_MANIFEST_SHA256)
    extra = (SOURCE, PATCH, BASE + '/BASELINE.json', BASE + '/JDK17.json', BUILDER, VERIFIER)
    return dict(sorted((baseline | {n: digest(checked_file(root, n)) for n in extra}).items()))


def source_copy_paths() -> list[str]:
    return ['inputs/src/com/termux/terminal/' + name + '.java' for name in OVERLAYS]


def compiler_command(work_root: Path, java_home: Path) -> list[str]:
    return [str(java_home / 'bin/javac'), *COMPILER_ARGUMENTS, '-sourcepath', str(work_root / 'empty-sourcepath'),
            '-classpath', str(work_root / 'inputs/android.jar') + os.pathsep + str(work_root / 'inputs/original-classes.jar'),
            '-d', str(work_root / 'compiled'), *(str(work_root / p) for p in source_copy_paths())]


def verify_publication(root: Path, publication: Path, inputs: dict[str, str]) -> dict:
    """Internal fixed-schema verification, also used before the builder renames staging."""
    receipt = read_json(publication, 'receipt.json')
    if (set(receipt) != {'schemaVersion', 'artifactKind', 'status', 'releaseCompatible', 'deviceValidated',
                        'executionEvidence', 'nativeBuildPerformed', 'sourceInputSha256ByPath', 'baselineReceiptSha256',
                        'verifierSha256', 'builderSha256', 'output', 'builds', 'evidenceSha256ByPath', 'aarInspection'} or
        receipt['schemaVersion'] != 1 or receipt['artifactKind'] != KIND or receipt['status'] != STATUS or
        receipt['releaseCompatible'] is not False or receipt['deviceValidated'] is not False or
        receipt['executionEvidence'] is not None or receipt['nativeBuildPerformed'] is not False):
        fail('derivative receipt schema/status is not strictly private build-only')
    if receipt['sourceInputSha256ByPath'] != inputs: fail('derivative source/build/verifier inputs changed')
    if receipt['baselineReceiptSha256'] != digest(checked_file(root, PRIVATE_RECEIPT)): fail('baseline receipt binding changed')
    if receipt['builderSha256'] != inputs[BUILDER] or receipt['verifierSha256'] != inputs[VERIFIER]: fail('producer/verifier identity changed')
    actual = regular_tree(publication); actual.pop('receipt.json', None)
    if receipt['evidenceSha256ByPath'] != actual: fail('raw evidence/output manifest differs')
    final = checked_file(publication, 'terminal-emulator-r46-private.aar')
    inspection = verify_aar_bytes(root, final)
    if receipt['output'] != {'path': AAR, 'sha256': digest(final)} or receipt['aarInspection'] != inspection:
        fail('fixed derivative AAR identity/inspection mismatch')
    if set(receipt['builds']) != {'primary', 'independent'}: fail('two independent Java builds required')
    work_roots = []
    expected_evidence = {'terminal-emulator-r46-private.aar', 'baseline-gate.json', 'python-identity.json'}
    baseline_gate = read_json(publication, 'baseline-gate.json')
    if baseline_gate.get('returnCode') != 0 or 'TERMUX_PROVENANCE_OK' not in baseline_gate.get('stdout', ''):
        fail('fresh original baseline gate evidence missing')
    identity = read_json(publication, 'python-identity.json')
    if (set(identity) != {'executable', 'executableSha256', 'version', 'implementation', 'zlibVersion', 'zlibRuntimeVersion'} or
        not identity.get('executableSha256') or not identity.get('version') or not identity.get('zlibVersion')):
        fail('Python/ZIP tool identity missing')
    producer_root = Path(baseline_gate.get('cwd', ''))
    if (not producer_root.is_absolute() or set(baseline_gate) != {'argv', 'cwd', 'environment', 'returnCode', 'stdout', 'stderr'} or
        baseline_gate.get('argv') != [identity['executable'], '-B', '-E', '-s',
            str(producer_root / 'tools/verify_termux_provenance.py'), '--private-debug-device-unvalidated'] or
        baseline_gate.get('environment') != SAFE_ENVIRONMENT or baseline_gate.get('stderr') != '' or
        baseline_gate.get('stdout') != 'TERMUX_PROVENANCE_OK upstream=v0.118.0@6e2689f java_sources=20 classes=38 native_abis=4 local_input_classes=26\n'):
        fail('fresh baseline gate command/environment/output identity changed')
    original_jar = archive_entries(checked_file(root, ORIGINAL_AAR))['classes.jar']
    replacement_set = set(read_json(root, JAVA_RECEIPT)['replacementClassSha256ByPath'])
    jdk_manifest = read_json(root, BASE + '/JDK17.json', JDK_MANIFEST_SHA256)
    for stage in ('primary', 'independent'):
        stage_root = publication / stage
        command = read_json(stage_root, 'command.json')
        stage_receipt = receipt['builds'][stage]
        work = Path(command['cwd']); java_home = Path(command['javaHome']); work_roots.append(str(work))
        if (not work.is_absolute() or work.name != stage or not java_home.is_absolute() or
            work.parent.parent != producer_root / BASE or not work.parent.name.startswith('.fresh-java-build-') or
            command.get('argv') != compiler_command(work, java_home) or command.get('returnCode') != 0 or
            command.get('environment') != SAFE_ENVIRONMENT or type(command.get('durationNanoseconds')) is not int or
            command.get('durationNanoseconds', 0) <= 0 or type(command.get('returnCode')) is not int or
            command.get('outputDirectoryInitiallyEmpty') is not True or command.get('freshDirectoryCreated') is not True):
            fail(stage + ' actual compiler command/environment evidence changed')
        if set(command) != {'argv', 'cwd', 'javaHome', 'returnCode', 'environment', 'durationNanoseconds',
                            'outputDirectoryInitiallyEmpty', 'freshDirectoryCreated', 'startedAtUtc', 'finishedAtUtc'}:
            fail('unexpected compiler command evidence')
        tool = read_json(stage_root, 'tool-identities.json')
        if tool != {'javaHome': str(java_home), 'version': jdk_manifest['version'],
                    'manifestSha256': JDK_MANIFEST_SHA256, 'files': jdk_manifest['files']}:
            fail('compiler tool identity differs from pinned full JDK')
        expected_inputs = {'inputs/android.jar': ANDROID_JAR_SHA256,
                           'inputs/original-classes.jar': digest(original_jar)}
        for class_name, copied in zip(OVERLAYS, source_copy_paths()):
            source = SOURCE if class_name == 'TerminalPtyIo' else 'third_party/termux/patches/src/com/termux/terminal/' + class_name + '.java'
            expected_inputs[copied] = inputs[source]
        for name in ('inputs-before.json', 'inputs-after.json'):
            record = read_json(stage_root, name)
            if record != {'repository': inputs, 'copiedInputs': expected_inputs, 'jdkManifestSha256': JDK_MANIFEST_SHA256}:
                fail(stage + ' inputs changed before/after actual compilation')
        for name, wanted in expected_inputs.items(): checked_file(stage_root, name, wanted)
        compiled = regular_tree(stage_root / 'compiled')
        if set(compiled) != replacement_set: fail('actual compiled overlay class set changed')
        stage_aar = checked_file(stage_root, 'terminal-emulator-r46-private.aar')
        if stage_aar != final: fail('fresh independent AAR outputs are not byte-identical')
        classes = archive_entries(archive_entries(stage_aar)['classes.jar'])
        for name, wanted in compiled.items():
            if digest(classes[name]) != wanted: fail('AAR class differs from actual compiler output: ' + name)
            if parse_class(checked_file(stage_root, 'compiled/' + name))['major'] != 52:
                fail('actual compiler output is not Java 8')
        if stage_receipt != {'aarSha256': digest(stage_aar), 'compiledSha256ByPath': compiled,
                             'commandSha256': digest(checked_file(stage_root, 'command.json'))}:
            fail('build output receipt differs from actual compiler artifacts')
        if checked_file(stage_root, 'javac-version.stdout.log') != b'javac 17.0.20.1\n' or checked_file(stage_root, 'javac-version.stderr.log'):
            fail('actual javac version output changed')
        version = read_json(stage_root, 'version-command.json')
        if version != {'argv': [str(java_home / 'bin/javac'), '-version'], 'cwd': str(work),
                       'environment': SAFE_ENVIRONMENT, 'returnCode': 0}:
            fail('actual javac version command missing')
        expected_stage = {'command.json', 'tool-identities.json', 'inputs-before.json', 'inputs-after.json',
                          'javac.stdout.log', 'javac.stderr.log', 'javac-version.stdout.log', 'javac-version.stderr.log',
                          'version-command.json', 'terminal-emulator-r46-private.aar', *expected_inputs,
                          *('compiled/' + name for name in compiled)}
        expected_evidence.update(stage + '/' + name for name in expected_stage)
        if not (stage_root / 'empty-sourcepath').is_dir() or list((stage_root / 'empty-sourcepath').iterdir()):
            fail('explicit empty sourcepath was changed')
    if work_roots[0] == work_roots[1] or Path(work_roots[0]).parent != Path(work_roots[1]).parent:
        fail('separate independent fresh build roots required')
    if set(actual) != expected_evidence: fail('unexpected or missing derivative evidence file')
    return receipt


def verify_derivative_inputs(root: Path) -> dict:
    """Public packaging gate: actual frozen baseline, then fixed R46 publication."""
    root = no_symlinks(Path(root))
    run_baseline_gate(root)
    inputs = source_inputs(root)
    result = verify_publication(root, root / PUBLICATION, inputs)
    # Recheck to reject input races across the entire gate.
    if source_inputs(root) != inputs: fail('repository inputs raced during derivative verification')
    private = read_json(root, PRIVATE_RECEIPT)
    return dict(result, baselineReceipt=private,
                nativeSha256ByBuild=private['nativeSha256ByBuild'],
                existingRuntimeSha256ByPath=private['existingRuntimeSha256ByPath'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--report', type=Path, help='New report path; existing reports are never overwritten')
    args = parser.parse_args()
    try:
        result = verify_derivative_inputs(args.root)
        if args.report:
            no_symlinks(args.report)
            with args.report.open('xb') as stream: stream.write(json_bytes(result))
        print('TERMUX_PTY_DERIVATIVE_OK sha256=' + result['output']['sha256'] + ' release_compatible=false device_validated=false')
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, struct.error) as error:
        fail(str(error))


if __name__ == '__main__':
    main()
