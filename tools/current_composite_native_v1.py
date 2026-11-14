#!/usr/bin/env python3
"""Compose current build evidence and exact providers; never admits an APK."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys

import verify_termux_pty_derivative as classfiles

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = 'tools/current-composite-native-v1.json'
CONTRACT_SHA = '0f4c31e5549adfe103296473d3cb78336c58346f08be4f627de6c2ba7b599751'
KIND = 'CURRENT_COMPOSITE_NATIVE_V1'
STATUS = 'BUILD_ONLY_DEVICE_UNVALIDATED'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
INPUT_NAMES = ('sdk', 'java_home', 'android_jar', 'vendor_archive', 'filesystem_output',
               'termux_output', 'engine_output', 'host_output', 'junit', 'hamcrest')


def require(value, message):
    if not value:
        raise ValueError(message)


def checked(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlinked protected path: ' + str(path))
    return path


def raw(path):
    path = checked(path)
    require(path.is_file(), 'missing regular file: ' + str(path))
    return path.read_bytes()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_hash(path):
    return digest(raw(path))


def load(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(raw(path), object_pairs_hook=unique)


def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')


def inventory(directory):
    directory = checked(directory)
    require(directory.is_dir(), 'missing evidence directory')
    result = {}
    for path in sorted(directory.rglob('*')):
        checked(path)
        if path.is_file():
            result[str(path.relative_to(directory))] = file_hash(path)
        else:
            require(path.is_dir(), 'special evidence entry')
    return result


def disjoint(a, b):
    return a != b and a not in b.parents and b not in a.parents


def recipe(root, require_running=True):
    if require_running:
        require(checked(__file__) == root / 'tools/current_composite_native_v1.py', 'running compositor outside selected source')
        require(checked(classfiles.__file__) == root / 'tools/verify_termux_pty_derivative.py', 'class parser outside selected source')
    require(file_hash(root / CONTRACT) == CONTRACT_SHA, 'versioned composite recipe changed')
    value = load(root / CONTRACT)
    require(value['schema'] == 1 and value['kind'] == KIND, 'wrong composite contract')
    for relative, expected in value['sourceAndToolSha256'].items():
        require(file_hash(root / relative) == expected, 'pinned source/tool changed: ' + relative)
    actual_aux = inventory(root / 'app/src/main/jniLibs')
    require(actual_aux == value['auxiliarySha256'], 'auxiliary provider set or bytes changed')
    return value


def paths(values):
    require(set(values) == set(INPUT_NAMES), 'component input set changed')
    return {key: checked(value) for key, value in values.items()}


def commands(root, locations):
    p = locations
    python = str(Path(sys.executable).resolve(strict=True))
    source = ['--root', str(root)]
    java = ['--java-home', str(p['java_home']), '--android-jar', str(p['android_jar']),
            '--vendor-archive', str(p['vendor_archive'])]
    return {
        'filesystem': [python, '-B', '-E', '-s', str(root / 'tools/filesystem_native_v1.py'), 'verify', *source,
                       '--sdk', str(p['sdk']), '--output', str(p['filesystem_output'])],
        'termux': [python, '-B', '-E', '-s', str(root / 'tools/termux_native_v1.py'), 'verify', *source,
                   '--sdk', str(p['sdk']), '--output', str(p['termux_output'])],
        'java': [python, '-B', '-E', '-s', str(root / 'tools/terminal_engine_v2.py'), 'verify', *source, *java,
                 '--output', str(p['engine_output'])],
        'host': [python, '-B', '-E', '-s', str(root / 'tools/run_current_engine_host_v1.py'), 'verify', *source, *java,
                 '--engine-output', str(p['engine_output']), '--junit', str(p['junit']),
                 '--hamcrest', str(p['hamcrest']), '--output', str(p['host_output'])],
        'auxiliary-cli': [python, '-B', '-E', '-s', str(root / 'tools/verify_current_droide_cli_v1.py')],
    }


def verify_children(root, locations, output, record):
    rows = {}
    environment = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
    for name, argv in commands(root, locations).items():
        try:
            completed = subprocess.run(argv, cwd=root, env=environment, capture_output=True, timeout=1800)
        except subprocess.TimeoutExpired as error:
            if record:
                (output / 'verification' / (name + '.stdout.log')).write_bytes(error.stdout or b'')
                (output / 'verification' / (name + '.stderr.log')).write_bytes(error.stderr or b'')
                write_json(output / 'verification' / (name + '.command.json'),
                           {'argv': argv, 'exit': None, 'timedOut': True})
            raise

        row = {'argv': argv, 'exit': completed.returncode}
        if record:
            (output / 'verification' / (name + '.stdout.log')).write_bytes(completed.stdout)
            (output / 'verification' / (name + '.stderr.log')).write_bytes(completed.stderr)
            write_json(output / 'verification' / (name + '.command.json'), row)
        require(completed.returncode == 0, 'child consumer verification failed: ' + name)
        if not record:
            require(load(output / 'verification' / (name + '.command.json')) == row,
                    'recorded child verification command differs: ' + name)
        rows[name] = row
    return rows


def native_rows(filesystem, termux, methods):
    expected = {stage + '/' + abi for stage in ('primary', 'independent') for abi in ABIS}
    require(set(filesystem['artifacts']) == expected and set(termux['artifacts']) == expected,
            'native child ABI/stage coverage changed')
    for key in expected:
        rows = termux['artifacts'][key]['staticInspection']['registrationTable']
        actual = [{'name': row['name'], 'descriptor': row['descriptor']} for row in rows]
        require(actual == methods, 'compiled Java/native table descriptor mismatch: ' + key)
        require(termux['artifacts'][key]['staticInspection']['registrationPathVerified'] is False,
                'child scope unexpectedly promoted')
    for abi in ABIS:
        for child in (filesystem, termux):
            require(child['artifacts']['primary/' + abi]['sha256'] == child['artifacts']['independent/' + abi]['sha256'],
                    'native independent pair differs')


def scope_flags(receipt, status):
    require(receipt['status'] == status and receipt['deviceValidated'] is False and
            receipt['apkAdmitted'] is False and receipt['releaseCompatible'] is False,
            'child receipt scope invalid')


def read_components(root, locations, contract):
    p = locations
    fs = load(p['filesystem_output'] / 'receipt.json')
    termux = load(p['termux_output'] / 'receipt.json')
    java = load(p['engine_output'] / 'receipt.json')
    host = load(p['host_output'] / 'receipt.json')
    scope_flags(fs, STATUS)
    scope_flags(termux, 'BUILD_ONLY_REGISTRATION_BINDING_PENDING')
    scope_flags(java, STATUS)
    scope_flags(host, 'PASS')
    require(termux['componentAdmitted'] is False and termux['registrationPathVerified'] is False and
            termux['finalJavaBindingVerified'] is False, 'immutable Termux child scope changed')
    require(host['kind'] == 'CURRENT_ENGINE_HOST_BINDING_V1', 'missing fresh-engine host lineage')
    require(java['kind'] == 'CURRENT_TERMINAL_ENGINE_OVERLAY_V2' and java['binaryBaseRetained'] is True and
            java['fullSourceRebuild'] is False, 'wrong fresh Java build identity')
    for child in (fs, termux):
        require(child['sourceArchiveSha256'] == contract['sourceArchiveSha256'], 'mixed native source lineage')
    fs_inputs = load(p['filesystem_output'] / 'inputs-before.json')['source']
    termux_inputs = load(p['termux_output'] / 'inputs-before.json')['source']
    for relative in ('app/src/main/cpp/CMakeLists.txt', 'app/src/main/cpp/droide_files.c'):
        require(fs_inputs[relative] == termux_inputs[relative] == file_hash(root / relative),
                'component source compatibility failed: ' + relative)
    jni_source = 'third_party/termux/patches/src/com/termux/terminal/JNI.java'
    require(java['sourceInputs'][jni_source] == termux_inputs[jni_source] == file_hash(root / jni_source),
            'native and Java JNI source selection differs')
    engine = raw(p['engine_output'] / 'terminal-engine.aar')
    outer = classfiles.archive_entries(engine)
    require('classes.jar' in outer and not any(name.startswith(('jni/', 'lib/')) for name in outer),
            'engine is not a native-free AAR')
    classes = classfiles.archive_entries(outer['classes.jar'])
    jni_bytes = classes['com/termux/terminal/JNI.class']
    jni = classfiles.parse_class(jni_bytes)
    require(jni['name'] == 'com/termux/terminal/JNI' and jni['major'] == 52, 'wrong compiled JNI class')
    methods = [{'name': item['name'], 'descriptor': item['descriptor']} for item in jni['methods'] if item['access'] & 0x100]
    require(all(item['access'] == 0x108 for item in jni['methods'] if item['access'] & 0x100), 'JNI access flags changed')
    require(methods == contract['jniMethods'], 'actual JNI descriptor set differs')
    require(java['inspection']['aarSha256'] == digest(engine) and
            java['inspection']['classesJarSha256'] == digest(outer['classes.jar']) and
            java['inspection']['jniClassSha256'] == digest(jni_bytes), 'actual Java output differs from verified child')
    native_rows(fs, termux, methods)
    # Host wrapper already re-verifies its entire fresh invocation and child inventories.
    pre = load(p['host_output'] / 'pre-run.json')
    owned = load(p['host_output'] / 'owned-jni' / 'receipt.json')
    validate_host_lineage(pre, owned, p['engine_output'], file_hash(p['engine_output'] / 'receipt.json'),
                          digest(engine), digest(outer['classes.jar']), digest(jni_bytes),
                          file_hash(root / 'third_party/termux/native/termux.c'))
    require(host['ownedReceiptSha256'] == file_hash(p['host_output'] / 'owned-jni' / 'receipt.json'),
            'host child receipt linkage differs')
    return {'filesystem': fs, 'termux': termux, 'java': java, 'host': host}, engine


def validate_host_lineage(pre, owned, engine_output, java_receipt_sha, aar_sha, jar_sha, jni_sha, c_sha):
    require(pre['javaBuild']['root'] == str(engine_output) and
            pre['javaBuild']['receiptSha256'] == java_receipt_sha and
            pre['engine']['aarPath'] == str(engine_output / 'terminal-engine.aar') and
            pre['engine']['aarSha256'] == aar_sha, 'fresh Java invocation lineage differs')
    require(owned['status'] == 'PASS' and owned['childCleanupStatus'] == 'OWNED_TEST_CHILDREN_REAPED',
            'owned JNI lifecycle did not finish cleanly')
    require(owned['sourceBefore'] == owned['sourceAfter'] and
            owned['sourceBefore']['source'] == c_sha and owned['sourceBefore']['engine'] == aar_sha and
            owned['engineJarSha256'] == jar_sha and owned['jniClassSha256'] == jni_sha,
            'fresh host source/engine linkage differs')


def selected_providers(root, locations, contract, children):
    providers = {}
    for component, filename, location in (
        ('filesystem', 'libdroide_files.so', 'filesystem_output'),
        ('termux', 'libtermux.so', 'termux_output'),
    ):
        for abi in ABIS:
            key = abi + '/' + filename
            source = checked(locations[location] / 'primary' / abi / filename)
            sha = file_hash(source)
            require(sha == children[component]['artifacts']['primary/' + abi]['sha256'], 'selected native provider changed')
            providers[key] = {'source': str(source), 'sha256': sha, 'origin': 'CURRENT_REPRODUCED_COMPONENT', 'component': component}
    for key, expected in contract['auxiliarySha256'].items():
        require(key not in providers, 'native provider collision')
        source = checked(root / 'app/src/main/jniLibs' / key)
        require(file_hash(source) == expected, 'historical auxiliary drift')
        providers[key] = {'source': str(source), 'sha256': expected, 'origin': 'UNCHANGED_AUXILIARY_INPUT', 'component': 'auxiliary'}
    require(len(providers) == 12, 'native provider count changed')
    for key, row in providers.items():
        blob = raw(row['source'])
        require(blob[:4] == b'\x7fELF' and blob[5] == 1, 'provider is not little-endian ELF')
        row.update(abiLocation=key.split('/')[0], elfClass=blob[4], elfMachine=struct.unpack_from('<H', blob, 18)[0])
    return providers


def lineage(root, locations, contract, providers):
    return {
        'pinnedSourcesAndTools': {name: file_hash(root / name) for name in contract['sourceAndToolSha256']},
        'childReceipts': {name: file_hash(locations[location] / 'receipt.json') for name, location in (
            ('filesystem', 'filesystem_output'), ('termux', 'termux_output'), ('java', 'engine_output'), ('host', 'host_output'))},
        'providerSources': {name: file_hash(row['source']) for name, row in providers.items()},
        'javaAar': file_hash(locations['engine_output'] / 'terminal-engine.aar'),
    }


def output_evidence(output, providers):
    actual = inventory(output)
    expected = {'engine/terminal-engine.aar'} | {'native/' + name for name in providers}
    for name in ('filesystem', 'termux', 'java', 'host', 'auxiliary-cli'):
        expected.update('verification/' + name + suffix for suffix in ('.stdout.log', '.stderr.log', '.command.json'))
    evidence = {name: sha for name, sha in actual.items() if name != 'receipt.json'}
    require(set(evidence) == expected, 'unexpected or missing composite evidence files')
    return evidence


def measured_receipt(root, locations, contract, providers, children, before, output):
    require(inventory(output / 'native') == {name: row['sha256'] for name, row in providers.items()},
            'staged native inventory differs')
    require(file_hash(output / 'engine/terminal-engine.aar') == before['javaAar'], 'staged AAR differs')
    return {
        'schema': 1, 'kind': KIND, 'status': STATUS, 'deviceValidated': False,
        'apkAdmitted': False, 'releaseCompatible': False, 'androidRegistrationVerified': False,
        'componentBytesVerified': True, 'compiledJavaAbiMatched': True, 'linuxOwnedJniVerified': True,
        'sourceRoot': str(root), 'inputs': {name: str(path) for name, path in locations.items()},
        'contractSha256': CONTRACT_SHA, 'compositorSha256': file_hash(Path(__file__)),
        'verificationInterpreter': {'path': str(Path(sys.executable).resolve(strict=True)),
                                    'sha256': file_hash(Path(sys.executable).resolve(strict=True))},
        'lineage': before, 'providers': providers,
        'engine': {'path': 'engine/terminal-engine.aar', 'sha256': before['javaAar'],
                   'jniClassSha256': children['java']['inspection']['jniClassSha256'],
                   'binaryBaseRetained': True, 'fullSourceRebuild': False},
        'hostCounts': {'ownedChecks': children['host']['ownedChecks'], 'regressionTests': children['host']['regressionTests']},
        'limitations': ['No Android runtime registration or device validation',
                       'Four ARM64 auxiliary providers retained, not freshly rebuilt',
                       'ABI packaging location is not always ELF architecture (loader32)',
                       'Old child receipts and historical gates remain unchanged',
                       'APK DEX/provider/signing and release gates remain separate'],
        'evidence': output_evidence(output, providers),
    }


def assemble(root, locations, output):
    root, output = checked(root), checked(output)
    locations = paths(locations)
    require(not output.exists(), 'output exists; no overwrite')
    require(disjoint(output, root) and all(disjoint(output, path) for path in locations.values()), 'output overlaps protected input')
    contract = recipe(root)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'verification').mkdir()
    verify_children(root, locations, output, record=True)
    children, engine = read_components(root, locations, contract)
    providers = selected_providers(root, locations, contract, children)
    before = lineage(root, locations, contract, providers)
    (output / 'engine').mkdir()
    (output / 'engine/terminal-engine.aar').write_bytes(engine)
    for name, row in providers.items():
        target = output / 'native' / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(raw(row['source']))
    require(lineage(root, locations, contract, providers) == before, 'source/artifact changed during copy')
    recipe(root)
    result = measured_receipt(root, locations, contract, providers, children, before, output)
    write_json(output / 'receipt.json', result)
    return result


def verify(root, output):
    requested_root, output = checked(root), checked(output)
    requested_contract = recipe(requested_root)
    saved = load(output / 'receipt.json')
    origin = checked(saved['sourceRoot'])
    # Relocation is a new consumer check, not a rewrite of original child commands.
    # Child verifiers still execute their pinned originals against original inputs.
    contract = recipe(origin, require_running=False)
    require(contract == requested_contract and
            file_hash(origin / 'tools/current_composite_native_v1.py') == file_hash(Path(__file__)),
            'relocated consumer source/tool identity differs')
    locations = paths(saved['inputs'])
    require(disjoint(output, requested_root) and disjoint(output, origin) and
            all(disjoint(output, path) for path in locations.values()), 'composite aliases inputs')
    verify_children(origin, locations, output, record=False)
    children, _ = read_components(origin, locations, contract)
    providers = selected_providers(origin, locations, contract, children)
    before = lineage(origin, locations, contract, providers)
    result = measured_receipt(origin, locations, contract, providers, children, before, output)
    require(saved == result, 'composite receipt differs from current consumer verification')
    require(lineage(origin, locations, contract, providers) == before, 'input changed during verification')
    recipe(requested_root)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='action', required=True)
    for action in ('assemble', 'verify'):
        sub = subparsers.add_parser(action)
        sub.add_argument('--root', type=Path, default=ROOT)
        sub.add_argument('--output', type=Path, required=True)
        if action == 'assemble':
            for name in INPUT_NAMES:
                sub.add_argument('--' + name.replace('_', '-'), type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.action == 'assemble':
            result = assemble(args.root, {name: getattr(args, name) for name in INPUT_NAMES}, args.output)
        else:
            result = verify(args.root, args.output)
        print(json.dumps({'status': result['status'], 'providers': len(result['providers']), 'apkAdmitted': False}))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, struct.error) as error:
        raise SystemExit('CURRENT_COMPOSITE_NATIVE_V1_REJECTED: ' + str(error)) from error


if __name__ == '__main__':
    main()
