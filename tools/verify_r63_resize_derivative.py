#!/usr/bin/env python3
"""Additive, fail-closed R63 Java resize derivative admission.

The actual untouched R46 gate must pass first. Only three fixed source overlays
are compiled against its exact AAR. Reviewed source and actual compiler output
pins are a code-reviewed trust root, never a caller-provided arbitrary allowlist.
No native/JNI/resource changes, release admission or device evidence are granted.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import subprocess
import sys

import verify_termux_pty_derivative as R46
from verify_termux_pty_derivative import (archive_entries, checked_file, deterministic_zip,
    digest, file_bytes, json_bytes, no_symlinks, parse_class, read_json, regular_tree, verify_jdk)

ROOT = Path(__file__).resolve().parents[1]
BASE = 'third_party/termux/derivatives/r63'
PUBLICATION = BASE + '/build'
AAR_NAME = 'terminal-emulator-r63-private.aar'
AAR = PUBLICATION + '/' + AAR_NAME
RECEIPT = PUBLICATION + '/receipt.json'
SOURCE_PREFIX = BASE + '/src/com/termux/terminal/'
OVERLAYS = ('TerminalSession', 'TerminalSessionClient', 'TerminalResizeState')
SOURCES = tuple(SOURCE_PREFIX + name + '.java' for name in OVERLAYS)
REVIEW = 'tools/r63-resize-reviewed.json'
# Filled only after reviewing the real pinned compiler's output.
REVIEW_SHA256 = '65a8deea49c62eca868f2ba9e88d723877b07a1d055190d3e7c1d9f1a5650e0c'
TRUSTED_TOOLS = {
    "tools/build_pty_dex_reference.py": "f191a28f891542c7efadbdaf3c4fa6906f2a5ff583684dc9c5c097e880b437a9",
    "tools/build_r63_resize_derivative.py": "0f3e0d871b5eb5e31ecd7c12f26739e6e69eb1fa291d25d0f3790ac8a1d6b998",
    "tools/build_r63_resize_dex_reference.py": "02bda699d25e9c6df0151f0969b05ca6ff2fbad4ca2ec9cf13baab59dd9b3c01",
    "tools/private-r63-resize-derivative.gradle": "705720cfb65d74036d14fba13c1677d55b01f076259cc978d7b7ab6993e151df",
    "tools/verify_private_r63_resize_apk.py": "b4832a0e5ad5b84b4929251c23e01139a495d6ecbd039980ca88787a93f3f5ae",
    "tools/verify_pty_dex_binding.py": "48ac74a01e37b374f454117c7befd3c648ff971eafd15bcb1af1e735d1945818",
    "tools/verify_r63_resize_dex_binding.py": "f0f177c4a59e092615d85df6eb7f80ba0843a7c395dddc9cf2395c1efac3e800",
    "tools/verify_termux_pty_derivative.py": "223d0f62da43fae3490819864dbfdc213934baae2fb6dc941805c77f9506b0e7"
}
BUILDER = 'tools/build_r63_resize_derivative.py'
VERIFIER = 'tools/verify_r63_resize_derivative.py'
ORIGINAL_AAR = R46.AAR
PRIVATE_RECEIPT = R46.PRIVATE_RECEIPT
KIND = 'R63_PRIVATE_JAVA_RESIZE_DERIVATIVE'
STATUS = 'BUILT_DEVICE_UNVALIDATED'
ANDROID_JAR_SHA256 = R46.ANDROID_JAR_SHA256
JDK_MANIFEST_SHA256 = R46.JDK_MANIFEST_SHA256
COMPILER_ARGUMENTS = R46.COMPILER_ARGUMENTS
SAFE_ENVIRONMENT = R46.SAFE_ENVIRONMENT


def fail(message):
    raise SystemExit('R63_RESIZE_DERIVATIVE_FAILED: ' + message)


def review(root):
    if len(REVIEW_SHA256) != 64 or not TRUSTED_TOOLS:
        fail('reviewed source, compiler output and tool pins are not finalized')
    value = read_json(root, REVIEW, REVIEW_SHA256)
    if (set(value) != {'schemaVersion', 'baselineAarSha256', 'sourceSha256ByPath',
                       'compiledSha256ByPath', 'changedClassPaths', 'newClassPaths'} or
            value['schemaVersion'] != 1 or set(value['sourceSha256ByPath']) != set(SOURCES)):
        fail('reviewed manifest schema/source scope changed')
    baseline_bytes = checked_file(root, ORIGINAL_AAR, value['baselineAarSha256'])
    classes = value['compiledSha256ByPath']
    if not classes or not all(name + '.class' in classes for name in
            ('com/termux/terminal/' + name for name in OVERLAYS)):
        fail('reviewed compiler output lacks required fixed overlay classes')
    for name, sha in classes.items():
        if not any(name == 'com/termux/terminal/' + top + '.class' or
                (name.startswith('com/termux/terminal/' + top + '$') and name.endswith('.class'))
                for top in OVERLAYS):
            fail('reviewed compiler scope escapes three fixed class families')
        if len(sha) != 64 or any(c not in '0123456789abcdef' for c in sha):
            fail('invalid reviewed class digest')
    baseline_classes = archive_entries(archive_entries(baseline_bytes)['classes.jar'])
    for name in baseline_classes:
        if any(name == 'com/termux/terminal/' + top + '.class' or
               name.startswith('com/termux/terminal/' + top + '$') for top in OVERLAYS):
            if name not in classes: fail('compiled overlay omits an existing family class: ' + name)
    for key in ('changedClassPaths', 'newClassPaths'):
        if value[key] != sorted(set(value[key])) or not set(value[key]) <= set(classes):
            fail('reviewed changed/new class scope is invalid')
    if set(value['changedClassPaths']) & set(value['newClassPaths']):
        fail('reviewed changed/new scopes overlap')
    if not value['changedClassPaths'] or not value['newClassPaths']:
        fail('reviewed resize delta is incomplete')
    return value


def run_baseline_gate(root):
    # Pin every historical input and the R46 receipt-bound verifier before executing it.
    R46.verify_derivative_inputs(root)
    argv = [sys.executable, '-B', '-E', '-s', str(root / R46.VERIFIER), '--root', str(root)]
    result = subprocess.run(argv, cwd=root, env=SAFE_ENVIRONMENT, capture_output=True, timeout=180)
    if result.returncode != 0:
        fail('untouched R46 admission failed: ' + (result.stdout + result.stderr).decode(errors='replace'))
    R46.verify_derivative_inputs(root)
    return {'argv': argv, 'cwd': str(root), 'environment': SAFE_ENVIRONMENT,
            'returnCode': result.returncode, 'stdout': result.stdout.decode(), 'stderr': result.stderr.decode()}


def source_inputs(root):
    reviewed = review(root)
    inputs = R46.source_inputs(root)
    for name, sha in regular_tree(root / R46.PUBLICATION).items():
        inputs[R46.PUBLICATION + '/' + name] = sha
    if set(regular_tree(root / BASE / 'src')) != {'com/termux/terminal/' + name + '.java' for name in OVERLAYS}:
        fail('R63 source file scope changed')
    for name, wanted in reviewed['sourceSha256ByPath'].items():
        inputs[name] = digest(checked_file(root, name, wanted))
    for name, wanted in TRUSTED_TOOLS.items():
        inputs[name] = digest(checked_file(root, name, wanted))
    for name in (REVIEW, VERIFIER):
        inputs[name] = digest(checked_file(root, name))
    return dict(sorted(inputs.items()))


def source_copy_paths():
    return ['inputs/src/com/termux/terminal/' + name + '.java' for name in OVERLAYS]


def compiler_command(work_root, java_home):
    return [str(java_home / 'bin/javac'), *COMPILER_ARGUMENTS, '-sourcepath', str(work_root / 'empty-sourcepath'),
            '-classpath', str(work_root / 'inputs/android.jar') + os.pathsep + str(work_root / 'inputs/original-classes.jar'),
            '-d', str(work_root / 'compiled'), *(str(work_root / name) for name in source_copy_paths())]


def check_compatible_surface(before, after):
    old, new = parse_class(before), parse_class(after)
    for key in ('minor', 'major', 'access', 'name', 'super', 'interfaces'):
        if old[key] != new[key]: fail('existing class declaration changed: ' + str(old['name']))
    for kind in ('fields', 'methods'):
        current = {(m['name'], m['descriptor']): m for m in new[kind]}
        for member in old[kind]:
            if member['access'] & (1 | 4):
                found = current.get((member['name'], member['descriptor']))
                # Adding synchronized serializes the resize/close boundary without changing JVM ABI.
                allowed_access = {member['access']} if kind == 'fields' else {member['access'], member['access'] | 0x20}
                if found is None or found['access'] not in allowed_access:
                    fail('existing public/protected member removed or changed: ' + member['name'])
    if old['name'] == 'com/termux/terminal/TerminalSessionClient':
        previous = {(m['name'], m['descriptor']) for m in old['methods']}
        for member in new['methods']:
            if (member['name'], member['descriptor']) not in previous:
                if member['access'] & 0x400 or 'Code' not in member['attributes']:
                    fail('new session client callback is not an additive default implementation')


def verify_aar_bytes(root, candidate):
    reviewed = review(root)
    original = archive_entries(checked_file(root, ORIGINAL_AAR, reviewed['baselineAarSha256']))
    actual = archive_entries(candidate)
    if set(actual) != set(original): fail('R63 AAR entry set changed')
    if any(n.startswith(('jni/', 'lib/')) for n in actual): fail('R63 AAR contains native payload')
    for name in original:
        if name != 'classes.jar' and actual[name] != original[name]: fail('unrelated AAR entry changed: ' + name)
    baseline = archive_entries(original['classes.jar'])
    classes = archive_entries(actual['classes.jar'])
    if set(classes) != set(baseline) | set(reviewed['newClassPaths']): fail('R63 classes.jar entry set changed')
    changed = sorted(n for n in baseline if classes[n] != baseline[n])
    added = sorted(set(classes) - set(baseline))
    if changed != reviewed['changedClassPaths'] or added != reviewed['newClassPaths']:
        fail('exact reviewed changed/new class set differs')
    for name, wanted in reviewed['compiledSha256ByPath'].items():
        if digest(classes[name]) != wanted: fail('class differs from reviewed actual compiler output: ' + name)
        parsed = parse_class(classes[name])
        if parsed['major'] != 52 or any(m['access'] & 0x100 for m in parsed['methods']):
            fail('overlay is not Java8-only or introduces native declarations')
        if name in baseline: check_compatible_surface(baseline[name], classes[name])
    for name, data in classes.items():
        if name.endswith('.class') and parse_class(data)['name'] + '.class' != name:
            fail('class path/declaration mismatch')
    # The byte-identical baseline outside the exact pinned scope includes JNI,
    # TerminalPtyIo, native declarations, all unrelated classes and all resources.
    if actual['classes.jar'] != deterministic_zip(classes) or candidate != deterministic_zip(actual):
        fail('R63 ZIP is not the deterministic approved recipe')
    return {'aarSha256': digest(candidate), 'classesJarSha256': digest(actual['classes.jar']),
            'baselineAarSha256': reviewed['baselineAarSha256'],
            'classSha256ByPath': {n: digest(d) for n, d in sorted(classes.items())},
            'changedClassPaths': changed, 'newClassPaths': added,
            'jniClassSha256': digest(classes[R46.JNI_CLASS]),
            'ptyClassSha256': digest(classes[R46.PTY_CLASS])}


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
    if receipt['baselineReceiptSha256'] != digest(checked_file(root, R46.RECEIPT)): fail('baseline receipt binding changed')
    if receipt['builderSha256'] != inputs[BUILDER] or receipt['verifierSha256'] != inputs[VERIFIER]: fail('producer/verifier identity changed')
    actual = regular_tree(publication); actual.pop('receipt.json', None)
    if receipt['evidenceSha256ByPath'] != actual: fail('raw evidence/output manifest differs')
    final = checked_file(publication, 'terminal-emulator-r63-private.aar')
    inspection = verify_aar_bytes(root, final)
    if receipt['output'] != {'path': AAR, 'sha256': digest(final)} or receipt['aarInspection'] != inspection:
        fail('fixed derivative AAR identity/inspection mismatch')
    if set(receipt['builds']) != {'primary', 'independent'}: fail('two independent Java builds required')
    work_roots = []
    expected_evidence = {'terminal-emulator-r63-private.aar', 'baseline-gate.json', 'python-identity.json'}
    baseline_gate = read_json(publication, 'baseline-gate.json')
    if baseline_gate.get('returnCode') != 0 or 'TERMUX_PTY_DERIVATIVE_OK' not in baseline_gate.get('stdout', ''):
        fail('fresh original baseline gate evidence missing')
    identity = read_json(publication, 'python-identity.json')
    if (set(identity) != {'executable', 'executableSha256', 'version', 'implementation', 'zlibVersion', 'zlibRuntimeVersion'} or
        not identity.get('executableSha256') or not identity.get('version') or not identity.get('zlibVersion')):
        fail('Python/ZIP tool identity missing')
    producer_root = Path(baseline_gate.get('cwd', ''))
    if (not producer_root.is_absolute() or set(baseline_gate) != {'argv', 'cwd', 'environment', 'returnCode', 'stdout', 'stderr'} or
        baseline_gate.get('argv') != [identity['executable'], '-B', '-E', '-s',
            str(producer_root / R46.VERIFIER), '--root', str(producer_root)] or
        baseline_gate.get('environment') != SAFE_ENVIRONMENT or baseline_gate.get('stderr') != '' or
        baseline_gate.get('stdout') != 'TERMUX_PTY_DERIVATIVE_OK sha256=' + review(root)['baselineAarSha256'] + ' release_compatible=false device_validated=false\n'):
        fail('fresh baseline gate command/environment/output identity changed')
    original_jar = archive_entries(checked_file(root, ORIGINAL_AAR))['classes.jar']
    replacement_set = set(review(root)['compiledSha256ByPath'])
    jdk_manifest = read_json(root, R46.BASE + '/JDK17.json', JDK_MANIFEST_SHA256)
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
            source = SOURCE_PREFIX + class_name + '.java'
            expected_inputs[copied] = inputs[source]
        for name in ('inputs-before.json', 'inputs-after.json'):
            record = read_json(stage_root, name)
            if record != {'repository': inputs, 'copiedInputs': expected_inputs, 'jdkManifestSha256': JDK_MANIFEST_SHA256}:
                fail(stage + ' inputs changed before/after actual compilation')
        for name, wanted in expected_inputs.items(): checked_file(stage_root, name, wanted)
        compiled = regular_tree(stage_root / 'compiled')
        if set(compiled) != replacement_set: fail('actual compiled overlay class set changed')
        stage_aar = checked_file(stage_root, 'terminal-emulator-r63-private.aar')
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
                          'version-command.json', 'terminal-emulator-r63-private.aar', *expected_inputs,
                          *('compiled/' + name for name in compiled)}
        expected_evidence.update(stage + '/' + name for name in expected_stage)
        if not (stage_root / 'empty-sourcepath').is_dir() or list((stage_root / 'empty-sourcepath').iterdir()):
            fail('explicit empty sourcepath was changed')
    if work_roots[0] == work_roots[1] or Path(work_roots[0]).parent != Path(work_roots[1]).parent:
        fail('separate independent fresh build roots required')
    if set(actual) != expected_evidence: fail('unexpected or missing derivative evidence file')
    return receipt



def verify_derivative_inputs(root):
    root = no_symlinks(Path(root))
    run_baseline_gate(root)
    inputs = source_inputs(root)
    result = verify_publication(root, root / PUBLICATION, inputs)
    if source_inputs(root) != inputs: fail('R63 source inputs raced during admission')
    baseline = R46.verify_derivative_inputs(root)
    return dict(result, baselineReceipt=baseline['baselineReceipt'],
                nativeSha256ByBuild=baseline['nativeSha256ByBuild'],
                existingRuntimeSha256ByPath=baseline['existingRuntimeSha256ByPath'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        result = verify_derivative_inputs(args.root)
        if args.report:
            no_symlinks(args.report)
            with args.report.open('xb') as stream: stream.write(json_bytes(result))
        print('R63_RESIZE_DERIVATIVE_OK sha256=' + result['output']['sha256'] +
              ' release_compatible=false device_validated=false')
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        fail(str(error))


if __name__ == '__main__':
    main()
