#!/usr/bin/env python3
"""Current Java terminal overlay rebuild, never historical or APK admission.

Retains a pinned Java-only binary base. Executes two fresh 9-source then 3-source
compilations. Only pure archive/classfile/full-JDK inspection helpers are reused;
no historical admission function is called or treated as passed.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import verify_termux_pty_derivative as inspect

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = 'tools/terminal-engine-v1.json'
CONTRACT_SHA = '1962697874f16d5f28865f717684b4b89366ffaaa6c181f21762c7c8cd5412ab'
SCRIPT = 'tools/terminal_engine_v1.py'
STATUS = 'BUILD_ONLY_DEVICE_UNVALIDATED'
FLAGS = ['--release', '8', '-encoding', 'UTF-8', '-g:none', '-proc:none', '-implicit:none']
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
JNI = 'com/termux/terminal/JNI.class'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlinked input/output: ' + str(path))
    return path


def data(path):
    path = checked(path)
    require(path.is_file(), 'missing regular file: ' + str(path))
    return path.read_bytes()


def write_json(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write('\n')


def files(root):
    require(root.is_dir(), 'missing tree: ' + str(root))
    return {str(p.relative_to(root)): sha(data(p)) for p in sorted(root.rglob('*')) if p.is_file() or p.is_symlink()}


def execution_identity(root):
    require(checked(__file__) == checked(root / SCRIPT), 'executing tool does not belong to selected source')
    require(checked(inspect.__file__) == checked(root / 'tools/verify_termux_pty_derivative.py'),
            'inspection helper does not belong to selected source')


def contract(root):
    execution_identity(root)
    raw = data(root / CONTRACT)
    require(sha(raw) == CONTRACT_SHA, 'versioned Java recipe changed')
    return json.loads(raw)


def source_inputs(root, recipe):
    result = {p: sha(data(root / p)) for p in recipe['sourceSha256']}
    require(result == recipe['sourceSha256'], 'current Java recipe input pin mismatch')
    result[CONTRACT] = sha(data(root / CONTRACT))
    result[SCRIPT] = sha(data(root / SCRIPT))
    return result


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(data(path), object_pairs_hook=unique)


def archive(raw):
    return inspect.archive_entries(raw)


def compatible(before, after):
    """Preserve public/protected API; synchronized may be added to methods."""
    old, new = inspect.parse_class(before), inspect.parse_class(after)
    for key in ('minor', 'major', 'access', 'name', 'super', 'interfaces'):
        require(old[key] == new[key], 'class declaration changed: ' + str(old['name']))
    for kind in ('fields', 'methods'):
        current = {(m['name'], m['descriptor']): m for m in new[kind]}
        for member in old[kind]:
            if not member['access'] & 5:
                continue
            found = current.get((member['name'], member['descriptor']))
            allowed = {member['access']}
            if kind == 'methods':
                allowed.add(member['access'] | 0x20)
            require(found is not None and found['access'] in allowed,
                    'public/protected member changed: ' + member['name'])
            if kind == 'fields':
                require(found['constant'] == member['constant'], 'public constant changed')
    if old['name'] == 'com/termux/terminal/TerminalSessionClient':
        previous = {(m['name'], m['descriptor']) for m in old['methods']}
        for member in new['methods']:
            if (member['name'], member['descriptor']) not in previous:
                require(not member['access'] & 0x400 and 'Code' in member['attributes'],
                        'new client callback must be an additive default method')


def in_family(name, family):
    prefix = 'com/termux/terminal/' + family
    return name == prefix + '.class' or name.startswith(prefix + '$') and name.endswith('.class')


def merge(base_aar, replacement, stage):
    outer = archive(base_aar)
    require('classes.jar' in outer, 'base lacks classes.jar')
    require(not any(n.startswith(('jni/', 'lib/')) for n in outer), 'native payload in Java AAR')
    before = archive(outer['classes.jar'])
    require(set(replacement) == set(stage['compiledClasses']), 'compiled class inventory changed')
    require({n: sha(v) for n, v in replacement.items()} == stage['expectedCompiledSha256'],
            'compiled class body differs from reviewed current recipe expectation')
    require(all(any(in_family(n, f) for f in stage['sources']) for n in replacement), 'class escaped overlay families')
    for name, value in replacement.items():
        parsed = inspect.parse_class(value)
        require(parsed['major'] == 52 and parsed['name'] + '.class' == name, 'compiled Java8 class identity mismatch')
        if name in before and name != JNI:
            compatible(before[name], value)
    removed = {n for n in before if any(in_family(n, f) for f in stage['sources'])}
    # Existing classes cannot disappear accidentally while replacing a source family.
    require(removed <= set(replacement), 'previous overlay class omitted')
    combined = {n: v for n, v in before.items() if n not in removed}
    require(not (combined.keys() & replacement.keys()), 'unexpected overlay collision')
    combined.update(replacement)
    require(all(combined[n] == before[n] for n in before.keys() - removed), 'base class/resource drift')
    outer['classes.jar'] = inspect.deterministic_zip(combined)
    return inspect.deterministic_zip(outer)


def inspect_final(raw, recipe):
    outer = archive(raw)
    require(not any(n.startswith(('jni/', 'lib/')) for n in outer), 'final AAR contains native payload')
    classes = archive(outer['classes.jar'])
    jni = inspect.parse_class(classes[JNI])
    require(jni['name'] == 'com/termux/terminal/JNI' and jni['major'] == 52 and
            jni['minor'] == 0 and jni['access'] == 0x30 and jni['super'] == 'java/lang/Object' and
            jni['interfaces'] == [], 'final JNI class identity/version changed')
    methods = [{'name': m['name'], 'descriptor': m['descriptor']}
               for m in jni['methods'] if m['access'] & 0x100]
    wanted = recipe['jniReplacement']['methods']
    require(methods == wanted, 'compiled JNI native inventory/descriptors mismatch')
    require(all(m['access'] == 0x108 for m in jni['methods'] if m['access'] & 0x100),
            'JNI methods must remain package-private static native')
    version = [m for m in jni['fields'] if m['name'] == 'API_VERSION']
    require(len(version) == 1 and version[0]['descriptor'] == 'I' and
            version[0]['constant'] == (3, (2).to_bytes(4, 'big')), 'JNI API version mismatch')
    return {'aarSha256': sha(raw), 'classesJarSha256': sha(outer['classes.jar']),
            'jniClassSha256': sha(classes[JNI]), 'jniMethods': methods,
            'classSha256': {n: sha(v) for n, v in sorted(classes.items())},
            'binaryBaseRetained': True, 'fullSourceRebuild': False}


def command(work, java_home, stage):
    return [str(java_home / 'bin/javac'), *FLAGS, '-sourcepath', str(work / 'empty-sourcepath'),
            '-classpath', str(work / 'inputs/android.jar') + os.pathsep + str(work / 'inputs/base-classes.jar'),
            '-d', str(work / 'compiled'),
            *(str(work / 'inputs/src/com/termux/terminal' / (name + '.java')) for name in stage['sources'])]


def expected_copies(root, android, base, stage):
    return {'inputs/android.jar': data(android), 'inputs/base-classes.jar': archive(base)['classes.jar'],
            **{'inputs/src/com/termux/terminal/' + name + '.java': data(root / path)
               for name, path in stage['sources'].items()}}


def compile_stage(root, work, java_home, android, base, stage):
    work.mkdir()
    (work / 'compiled').mkdir()
    (work / 'empty-sourcepath').mkdir()
    copies = expected_copies(root, android, base, stage)
    for name, raw in copies.items():
        destination = work / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open('xb') as stream:
            stream.write(raw)
    argv = command(work, java_home, stage)
    result = subprocess.run(argv, cwd=work, env=ENV, capture_output=True, timeout=180)
    (work / 'javac.stdout.log').write_bytes(result.stdout)
    (work / 'javac.stderr.log').write_bytes(result.stderr)
    write_json(work / 'command.json', {'argv': argv, 'cwd': str(work), 'environment': ENV, 'exit': result.returncode})
    require(result.returncode == 0, 'javac failed; see ' + str(work))
    require(files(work / 'inputs') == {n.removeprefix('inputs/'): sha(v) for n, v in copies.items()}, 'copied inputs changed')
    replacement = {n: data(work / 'compiled' / n) for n in files(work / 'compiled')}
    output = merge(base, replacement, stage)
    (work / 'terminal-engine.aar').write_bytes(output)
    return output


def disjoint_output(output, *roots):
    output = checked(output)
    for root in roots:
        root = checked(root)
        require(output != root and output not in root.parents and root not in output.parents,
                'output overlaps protected input root')
    return output


def tool_inputs(java_home, android, root, recipe):
    require(sha(data(android)) == recipe['androidJarSha256'], 'API36 android.jar mismatch')
    # Pure complete JDK inspection only; no historical gate/source baseline invocation.
    return inspect.verify_jdk(java_home, root)


def verify(root, java_home, android, output):
    recipe = contract(root)
    inputs = source_inputs(root, recipe)
    tools = tool_inputs(java_home, android, root, recipe)
    receipt = read_json(output / 'receipt.json')
    require(set(receipt) == {'schema', 'kind', 'status', 'deviceValidated', 'apkAdmitted',
            'releaseCompatible', 'binaryBaseRetained', 'fullSourceRebuild', 'sourceInputs',
            'jdk', 'inspection', 'evidence', 'builtAt'}, 'receipt schema fields changed')
    require(receipt['schema'] == 1 and receipt['binaryBaseRetained'] is True and
            receipt['fullSourceRebuild'] is False and receipt['kind'] == recipe['kind'] and receipt['status'] == STATUS and
            receipt['deviceValidated'] is False and receipt['apkAdmitted'] is False and
            receipt['releaseCompatible'] is False, 'unadmitted Java receipt scope')
    require(receipt['sourceInputs'] == inputs and receipt['jdk'] == tools, 'source/tool receipt mismatch')
    inventory = files(output)
    require({n: h for n, h in inventory.items() if n != 'receipt.json'} == receipt['evidence'], 'evidence inventory/hash mismatch')
    outputs = []
    for build in ('primary', 'independent'):
        base = data(root / recipe['baseAar'])
        for stage in recipe['stages']:
            work = output / build / stage['name']
            record = read_json(work / 'command.json')
            require(record == {'argv': command(work, java_home, stage), 'cwd': str(work), 'environment': ENV, 'exit': 0},
                    'compiler invocation mismatch')
            require(not list((work / 'empty-sourcepath').iterdir()), 'implicit sourcepath not empty')
            copies = expected_copies(root, android, base, stage)
            require(files(work / 'inputs') == {n.removeprefix('inputs/'): sha(v) for n, v in copies.items()}, 'copied input mismatch')
            replacement = {n: data(work / 'compiled' / n) for n in files(work / 'compiled')}
            expected = merge(base, replacement, stage)
            require(data(work / 'terminal-engine.aar') == expected, 'AAR differs from actual compiler outputs')
            base = expected
        require(data(output / build / 'terminal-engine.aar') == base, 'final build output mismatch')
        outputs.append(base)
    require(outputs[0] == outputs[1] == data(output / 'terminal-engine.aar'), 'independent full AAR bytes differ')
    measured = inspect_final(outputs[0], recipe)
    require(measured == receipt['inspection'], 'final Java/JNI inspection mismatch')
    require(source_inputs(root, recipe) == inputs and tool_inputs(java_home, android, root, recipe) == tools,
            'inputs changed during verification')
    return receipt


def build(root, java_home, android, output):
    output = disjoint_output(output, root, java_home, android.parent)
    require(not output.exists(), 'output exists; no overwrite permitted')
    recipe = contract(root)
    inputs = source_inputs(root, recipe)
    tools = tool_inputs(java_home, android, root, recipe)
    output.mkdir(parents=True, exist_ok=False)
    outputs = []
    for name in ('primary', 'independent'):
        work = output / name
        work.mkdir()
        base = data(root / recipe['baseAar'])
        for stage in recipe['stages']:
            base = compile_stage(root, work / stage['name'], java_home, android, base, stage)
        (work / 'terminal-engine.aar').write_bytes(base)
        outputs.append(base)
        require(source_inputs(root, recipe) == inputs and tool_inputs(java_home, android, root, recipe) == tools,
                'inputs changed during build')
    require(outputs[0] == outputs[1], 'independent builds differ')
    (output / 'terminal-engine.aar').write_bytes(outputs[0])
    receipt = {'schema': 1, 'kind': recipe['kind'], 'status': STATUS, 'deviceValidated': False,
               'apkAdmitted': False, 'releaseCompatible': False, 'binaryBaseRetained': True,
               'fullSourceRebuild': False, 'sourceInputs': inputs, 'jdk': tools,
               'inspection': inspect_final(outputs[0], recipe), 'evidence': files(output), 'builtAt': time.time()}
    write_json(output / 'receipt.json', receipt)
    return verify(root, java_home, android, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'verify'])
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--android-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        root, java_home, android, output = map(checked, (args.root, args.java_home, args.android_jar, args.output))
        result = (build if args.action == 'build' else verify)(root, java_home, android, output)
        print(json.dumps({'status': result['status'], 'aarSha256': result['inspection']['aarSha256'], 'apkAdmitted': False}))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit('TERMINAL_ENGINE_V1_FAILED: ' + str(error)) from error


if __name__ == '__main__':
    main()
