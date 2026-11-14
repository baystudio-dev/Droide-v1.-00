#!/usr/bin/env python3
"""Run preserved PTY fixtures against the baseline and BOTH actual generated AARs.

Compiles fixture sources only. Records CodeSource/resource/hash inside the same
JVM as each test run; no production source is compiled into fixture outputs.
Never executes Android or native PTY code, downloads, Gradle or APK builds.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import verify_termux_pty_derivative as V

FIXTURES = {
    'TerminalOwnedProcessTest.java': '6169fa33f12fc537490ee08f1cf2302489622c79a8cb2f85c16aa0e3065df52c',
    'TerminalPtyReadProgressTest.java': 'c62c06cea093824dfd3977850350f6c2c56fa3452f5f255dbb91f8ec937be360',
    'ReaderHealthyOutputTiming.java': '9ce8a622834b22864dc2723df75b7214a92d91a09f0d227d7f6f2bedff21d79d',
    'AarFixtureRunner.java': '609896f2d5f092000f2d5c3e4960924ca5e887f35a2b72c8407244f4d5e66d7b',
}
DEPENDENCIES = {
    'junit-4.13.2.jar': '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3',
    'hamcrest-core-1.3.jar': '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9',
}
EXPECTED_FAILURES = {
    'intermittentRetriesResetAfterEachSuccessfulDelivery(com.termux.terminal.TerminalPtyReadProgressTest)',
    'sustainedSuccessfulDeliveryDoesNotBackoff(com.termux.terminal.TerminalPtyReadProgressTest)',
}
CLASS_FAMILIES = tuple('com/termux/terminal/' + name.removesuffix('.java') for name in FIXTURES)


def write_json(path, value):
    with path.open('xb') as stream: stream.write(V.json_bytes(value))


def run(work: Path, label: str, argv: list[str], expected_exit: int, timeout=180):
    start = time.monotonic_ns(); wall = datetime.now(timezone.utc).isoformat()
    result = subprocess.run(argv, cwd=work, env=V.SAFE_ENVIRONMENT, capture_output=True, timeout=timeout)
    (work / (label + '.stdout.log')).write_bytes(result.stdout)
    (work / (label + '.stderr.log')).write_bytes(result.stderr)
    write_json(work / (label + '.command.json'), {
        'argv': argv, 'cwd': str(work), 'environment': V.SAFE_ENVIRONMENT,
        'startedAtUtc': wall, 'durationNanoseconds': time.monotonic_ns() - start,
        'returnCode': result.returncode, 'expectedExit': expected_exit,
    })
    if result.returncode != expected_exit:
        V.fail(label + ' unexpected exit; raw outputs retained at ' + str(work))
    return result.stdout.decode('utf-8')


def check_loaded(output: str, jar: Path, classes: dict[str, bytes]):
    rows = re.findall(r'^LOADED_AAR_CLASS name=(\S+) codeSource=(\S+) resource=(\S+) sha256=([a-f0-9]{64})$', output, re.M)
    expected_names = {'com.termux.terminal.TerminalPtyIo', 'com.termux.terminal.TerminalProcessOwner', 'com.termux.terminal.ByteQueue'}
    if len(rows) != 3 or {row[0] for row in rows} != expected_names:
        V.fail('loaded production class identity evidence is incomplete')
    measured = {}
    for name, source, resource, sha in rows:
        path = name.replace('.', '/') + '.class'
        # Java URL uses file:/ while Path.as_uri uses file:///. Compare URI paths.
        from urllib.parse import urlparse, unquote
        if urlparse(source).scheme != 'file' or Path(unquote(urlparse(source).path)) != jar:
            V.fail('test loaded a shadow production class from another CodeSource')
        if resource != 'jar:' + source + '!/' + path or sha != V.digest(classes[path]):
            V.fail('loaded production class resource/hash differs from actual AAR')
        measured[name] = {'codeSource': source, 'resource': resource, 'sha256': sha}
    return measured


def validate_fixture_outputs(output: Path) -> dict:
    compiled = V.regular_tree(output)
    for name in compiled:
        if not any(name == base + '.class' or name.startswith(base + '$') and name.endswith('.class') for base in CLASS_FAMILIES):
            V.fail('production or unexpected class compiled into fixture output: ' + name)
    if not all(base + '.class' in compiled for base in CLASS_FAMILIES): V.fail('missing compiled fixture class')
    return compiled


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--junit', type=Path, required=True)
    parser.add_argument('--hamcrest', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, help='New empty evidence root; default fixed r46/host-regressions')
    args = parser.parse_args()
    root = V.no_symlinks(args.root)
    target = V.no_symlinks(args.output_root or root / V.BASE / 'host-regressions')
    if target.exists(): V.fail('regression output root exists; refusing to overwrite evidence')
    admission = V.verify_derivative_inputs(root)
    tools = V.verify_jdk(args.java_home, root)
    java_home = Path(tools['javaHome'])
    fixtures = root / V.BASE / 'fixtures'
    fixture_bytes = {name: V.checked_file(fixtures, name, sha) for name, sha in FIXTURES.items()}
    inputs = V.source_inputs(root)
    dependencies = {}
    for name, path in (('junit-4.13.2.jar', args.junit), ('hamcrest-core-1.3.jar', args.hamcrest)):
        data = V.file_bytes(path)
        if V.digest(data) != DEPENDENCIES[name]: V.fail('test dependency pin mismatch: ' + name)
        dependencies[name] = data
    target.mkdir(parents=True)
    (target / 'dependencies').mkdir()
    for name, data in dependencies.items(): (target / 'dependencies' / name).write_bytes(data)
    write_json(target / 'tool-identities.json', tools)
    write_json(target / 'inputs-before.json', {'repository': inputs, 'fixtures': FIXTURES, 'dependencies': DEPENDENCIES})
    write_json(target / 'admitted-artifact.json', admission)
    expected_payload = V.digest(bytes((i * 17 + (i >> 8)) & 255 for i in range(4194304)))
    summary = {}
    baseline_class = V.read_json(root, V.JAVA_RECEIPT)['replacementClassSha256ByPath'][V.PTY_CLASS]
    for stage in ('baseline', 'primary', 'independent'):
        work = target / stage; work.mkdir()
        (work / 'fixture-sources').mkdir(); (work / 'fixture-classes').mkdir(); (work / 'empty-sourcepath').mkdir()
        for name, data in fixture_bytes.items(): (work / 'fixture-sources' / name).write_bytes(data)
        aar_path = root / (V.HISTORICAL_AAR if stage == 'baseline' else V.PUBLICATION + '/' + stage + '/terminal-emulator-r46-private.aar')
        aar = V.file_bytes(aar_path); jar_bytes = V.archive_entries(aar)['classes.jar']
        jar = work / 'actual-aar-classes.jar'; jar.write_bytes(jar_bytes)
        classes = V.archive_entries(jar_bytes)
        wanted = baseline_class if stage == 'baseline' else V.PTY_CLASS_SHA256
        if V.digest(classes[V.PTY_CLASS]) != wanted: V.fail('wrong actual-AAR PTY input')
        classpath = ':'.join(map(str, [jar, target / 'dependencies/junit-4.13.2.jar', target / 'dependencies/hamcrest-core-1.3.jar']))
        compile_argv = [str(java_home / 'bin/javac'), *V.COMPILER_ARGUMENTS,
                        '-sourcepath', str(work / 'empty-sourcepath'), '-classpath', classpath,
                        '-d', str(work / 'fixture-classes'), *(str(work / 'fixture-sources' / name) for name in FIXTURES)]
        run(work, 'compile-fixtures-only', compile_argv, 0)
        compiled = validate_fixture_outputs(work / 'fixture-classes')
        runtime_classpath = ':'.join(map(str, [jar, work / 'fixture-classes', target / 'dependencies/junit-4.13.2.jar', target / 'dependencies/hamcrest-core-1.3.jar']))
        launch = [str(java_home / 'bin/java'), '-cp', runtime_classpath, 'com.termux.terminal.AarFixtureRunner']
        junit = run(work, 'junit-actual-aar', launch + ['junit', str(jar)], 1 if stage == 'baseline' else 0)
        loaded = check_loaded(junit, jar, classes)
        expected_count = 2 if stage == 'baseline' else 0
        if 'ACTUAL_AAR_JUNIT tests=25 failures=' + str(expected_count) not in junit:
            V.fail('real-AAR JUnit count/result mismatch')
        failures = set(re.findall(r'^\d+\) ([^\n]+)$', junit, re.M))
        if failures != (EXPECTED_FAILURES if stage == 'baseline' else set()):
            V.fail('baseline/candidate failed a different set of actual-AAR controls')
        pipe = run(work, 'host-pipe-actual-aar', launch + ['pipe', str(jar)], 0, 30)
        pipe_loaded = check_loaded(pipe, jar, classes)
        if pipe_loaded != loaded: V.fail('host pipe changed loaded production class identity')
        rows = re.findall(r'^HOST_PIPE_METRIC (.+)$', pipe, re.M)
        if len(rows) != 4: V.fail('preserved four-trial host pipe fixture result missing')
        metrics = [dict(item.split('=', 1) for item in row.split()) for row in rows]
        for index, metric in enumerate(metrics):
            count = 262144 if index == 0 else 4194304
            backoffs = count // 4096 // 16 if stage == 'baseline' else 0
            if (metric['trial'] != str(index) or metric['bytes'] != str(count) or metric['integrity'] != 'exact' or
                metric['backoffs'] != str(backoffs) or metric['requestedBackoffMillis'] != str(backoffs * 10)):
                V.fail('actual-AAR host pipe payload/backoff control failed')
        measured = re.findall(r'^MEASURED_HOST_PIPE (.+)$', pipe, re.M)
        if len(measured) != 1: V.fail('measured delivered-payload SHA/CPU record missing')
        measured = dict(item.split('=', 1) for item in measured[0].split())
        expected_backoffs = 64 if stage == 'baseline' else 0
        if (measured['bytes'] != '4194304' or measured['sha256'] != expected_payload or
            measured['backoffs'] != str(expected_backoffs) or measured['requestedBackoffMillis'] != str(expected_backoffs * 10) or
            measured['nativePtyExecuted'] != 'false'):
            V.fail('actual delivered host-pipe bytes/hash/backoff control failed')
        if validate_fixture_outputs(work / 'fixture-classes') != compiled: V.fail('fixture classes changed during execution')
        if V.file_bytes(jar) != jar_bytes or V.file_bytes(aar_path) != aar: V.fail('actual AAR/JAR input changed during fixtures')
        if any(V.checked_file(work / 'fixture-sources', n) != d for n, d in fixture_bytes.items()):
            V.fail('fixture source changed during execution')
        summary[stage] = {'aarPath': str(aar_path), 'aarSha256': V.digest(aar), 'classesJarSha256': V.digest(jar_bytes),
                          'loadedClasses': loaded, 'fixtureCompiledSha256ByPath': compiled,
                          'tests': 25, 'failures': expected_count, 'expectedFailureNames': sorted(failures),
                          'preservedHostPipeTrials': metrics, 'measuredHostPipe': measured}
    if V.source_inputs(root) != inputs or V.verify_jdk(java_home, root) != tools:
        V.fail('source/tool input race across actual-AAR regressions')
    for name, wanted in FIXTURES.items(): V.checked_file(fixtures, name, wanted)
    if V.verify_derivative_inputs(root)['output'] != admission['output']: V.fail('admitted AAR changed during regression run')
    write_json(target / 'inputs-after.json', {'repository': V.source_inputs(root), 'fixtures': FIXTURES, 'dependencies': DEPENDENCIES})
    result = {'schemaVersion': 1, 'artifactKind': 'R46_ACTUAL_AAR_HOST_JVM_REGRESSIONS',
              'releaseCompatible': False, 'deviceValidated': False, 'nativeRuntimeEvidence': None,
              'productionSourceCompilationPerformed': False, 'scope': 'Host JVM fixtures and stdout pipe only',
              'runnerSha256': V.digest(Path(__file__).read_bytes()), 'results': summary,
              'rawEvidenceSha256ByPath': V.regular_tree(target)}
    write_json(target / 'results.json', result)
    print('ACTUAL_AAR_PTY_REGRESSIONS_OK baseline=25/2_expected primary=25/0 independent=25/0 payload_sha256=' + expected_payload)


if __name__ == '__main__':
    main()
