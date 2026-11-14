#!/usr/bin/env python3
"""Bind new host executions to a freshly verified Java-v2 build.

This additive lane never rewrites historical receipts or admits Android/APKs.
Its unsigned records establish local invocation lineage, not remote attestation.
"""
from __future__ import annotations
import argparse
import ast
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import sys

import terminal_engine_v2 as engine

KIND = 'CURRENT_ENGINE_HOST_BINDING_V1'
SCRIPT = 'tools/run_current_engine_host_v1.py'
ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
# Frozen reviewed harnesses/fixtures. Changed code requires explicit recipe review.
PINNED = {'third_party/termux/derivatives/r46/fixtures/AarFixtureRunner.java': '609896f2d5f092000f2d5c3e4960924ca5e887f35a2b72c8407244f4d5e66d7b',
 'third_party/termux/derivatives/r46/fixtures/ReaderHealthyOutputTiming.java': '9ce8a622834b22864dc2723df75b7214a92d91a09f0d227d7f6f2bedff21d79d',
 'third_party/termux/derivatives/r46/fixtures/TerminalOwnedProcessTest.java': '6169fa33f12fc537490ee08f1cf2302489622c79a8cb2f85c16aa0e3065df52c',
 'third_party/termux/derivatives/r46/fixtures/TerminalPtyReadProgressTest.java': 'c62c06cea093824dfd3977850350f6c2c56fa3452f5f255dbb91f8ec937be360',
 'third_party/termux/native/termux.c': 'e6e4e563865857af85fd6e101021fb3e247ecfc93f9261e1242d0ef637edc000',
 'tools/run_terminal_engine_regressions.py': 'a707ec7b5b19dae30ac786074ed4132975baf08b1f3184d93852bc3aecb45335',
 'tools/tests/terminal-engine-fixtures/TerminalResizeFixtureRunner.java': 'a1dd5b12f1e24a8848cc7e9f0ba28cce833b8c422af912c085fbf32dc2808c06',
 'tools/tests/terminal-engine-fixtures/TerminalResizeStateContractTest.java': '546e33937e1194bec5f62228146c3a35e6814948ac68f6635b1067966f3968aa',
 'tools/verify_owned_jni_host_contract.py': '09a461284ca2af4053e79f3b85222d10595d210f2d090b6b22bf1f155c1f0316',
 'tools/verify_termux_pty_derivative.py': '223d0f62da43fae3490819864dbfdc213934baae2fb6dc941805c77f9506b0e7'}


def sha(path):
    return hashlib.sha256(engine.data(path)).hexdigest()


def read_json(path):
    return json.loads(engine.data(path))


def write_json(path, value):
    with path.open('x') as stream:
        stream.write(json.dumps(value, indent=2, sort_keys=True) + '\n')


def inventory(root):
    result = {}
    for path in sorted(root.rglob('*')):
        engine.checked(path)
        if path.is_file(): result[path.relative_to(root).as_posix()] = sha(path)
        elif not path.is_dir(): raise ValueError('non-regular output entry')
    return result


def commands(root, java, aar, junit, hamcrest, output):
    python = str(Path(sys.executable).resolve(strict=True))
    common = ['--root', str(root), '--engine-aar', str(aar), '--java-home', str(java)]
    return {
        'owned-jni': [python, '-B', '-E', '-s', str(root / 'tools/verify_owned_jni_host_contract.py'),
                      *common, '--output', str(output / 'owned-jni')],
        'regressions': [python, '-B', '-E', '-s', str(root / 'tools/run_terminal_engine_regressions.py'),
                        *common, '--junit', str(junit), '--hamcrest', str(hamcrest),
                        '--output', str(output / 'regressions')],
    }


def prepare(root, java, android, archive, built, junit, hamcrest, output):
    engine.require(Path(__file__).resolve() == root / SCRIPT, 'selected root differs from executed wrapper')
    engine.require(Path(engine.__file__).resolve() == root / 'tools/terminal_engine_v2.py', 'unexpected Java verifier import')
    checked = {relative: sha(root / relative) for relative in PINNED}
    engine.require(checked == PINNED, 'reviewed host source/fixture hash mismatch')
    # Full child verification precedes recording or launching any host invocation.
    child = engine.verify(root, java, android, built, archive)
    aar = built / 'terminal-engine.aar'
    entries = engine.inspect.archive_entries(engine.data(aar))
    classes = engine.inspect.archive_entries(entries['classes.jar'])
    compiler = shutil.which('cc', path=ENV['PATH'])
    engine.require(compiler is not None, 'host C compiler unavailable')
    tool_paths = [Path(sys.executable).resolve(strict=True), Path(compiler).resolve(strict=True),
                  java / 'bin/java', java / 'bin/javac']
    return {
        'schema': 1, 'kind': KIND, 'deviceValidated': False, 'apkAdmitted': False,
        'wrapperSha256': sha(root / SCRIPT), 'sourceSha256': checked,
        'javaBuild': {'root': str(built), 'receiptSha256': sha(built / 'receipt.json'),
                      'kind': child['kind'], 'status': child['status']},
        'engine': {'aarPath': str(aar), 'aarSha256': sha(aar),
                   'classesJarSha256': engine.sha(entries['classes.jar']),
                   'jniClassSha256': engine.sha(classes[engine.JNI])},
        'dependencies': {str(junit): sha(junit), str(hamcrest): sha(hamcrest)},
        'tools': {str(p): sha(p) for p in tool_paths},
        'environment': ENV,
        'commands': commands(root, java, aar, junit, hamcrest, output),
    }


def verify_child_commands(output, pre, owned):
    owned_root = output / 'owned-jni'
    java = Path(pre['commands']['owned-jni'][pre['commands']['owned-jni'].index('--java-home') + 1])
    root = Path(pre['commands']['owned-jni'][pre['commands']['owned-jni'].index('--root') + 1])
    compiler = str(Path(shutil.which('cc', path=ENV['PATH'])).resolve(strict=True))
    jar = owned_root / 'engine.jar'
    expected = {
        'javac-version': [str(java / 'bin/javac'), '-version'],
        'cc-version': [compiler, '--version'],
        'javac': [str(java / 'bin/javac'), '-encoding', 'UTF-8', '-proc:none', '-implicit:none',
                  '-sourcepath', str(owned_root / 'empty-sourcepath'), '-cp', str(jar), '-d',
                  str(owned_root / 'classes'), str(owned_root / 'fixture/OwnedJniHostContract.java')],
        'cc': [compiler, '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror', '-pthread',
               '-I' + str(java / 'include'), '-I' + str(java / 'include/linux'),
               str(root / 'third_party/termux/native/termux.c'), '-o', str(owned_root / 'library/libtermux.so')],
        'java': [str(java / 'bin/java'), '-Xcheck:jni', '-Djava.library.path=' + str(owned_root / 'library'),
                 '-cp', str(owned_root / 'classes') + ':' + str(jar), 'com.termux.terminal.OwnedJniHostContract',
                 str(jar), pre['engine']['jniClassSha256'], str(owned_root / 'workspace')],
    }
    engine.require(len(owned['commands']) == len(expected), 'owned command count changed')
    for record, (name, argv) in zip(owned['commands'], expected.items()):
        engine.require(record.get('name') == name and record.get('argv') == argv and
                       record.get('returnCode') == 0 and 'timedOut' not in record,
                       'owned command does not bind fresh invocation: ' + name)
    expected_tools = {p: pre['tools'][p] for p in (compiler, str(java / 'bin/java'), str(java / 'bin/javac'))}
    engine.require(owned['toolHashes'] == expected_tools, 'owned exact compiler/runtime identities differ')
    regression_root = output / 'regressions'
    classpath = ':'.join(str(regression_root / name) for name in
                         ('actual-engine-classes.jar', 'junit.jar', 'hamcrest.jar'))
    sources = ['TerminalOwnedProcessTest.java', 'TerminalPtyReadProgressTest.java', 'ReaderHealthyOutputTiming.java',
               'AarFixtureRunner.java', 'TerminalResizeStateContractTest.java', 'TerminalResizeFixtureRunner.java']
    launch = [str(java / 'bin/java'), '-ea', '-cp', classpath + ':' + str(regression_root / 'classes')]
    expected_regression = {
        'compile-fixtures': [str(java / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-proc:none',
             '-implicit:none', '-sourcepath', str(regression_root / 'empty-sourcepath'), '-cp', classpath,
             '-d', str(regression_root / 'classes'), *[str(regression_root / 'sources' / n) for n in sources]],
        'pty-junit': launch + ['com.termux.terminal.AarFixtureRunner', 'junit', str(regression_root / 'actual-engine-classes.jar')],
        'pty-pipe': launch + ['com.termux.terminal.AarFixtureRunner', 'pipe', str(regression_root / 'actual-engine-classes.jar')],
        'resize-junit': launch + ['com.termux.terminal.TerminalResizeFixtureRunner', str(regression_root / 'actual-engine-classes.jar')],
    }
    recorded = read_json(regression_root / 'commands.json')
    engine.require(recorded == [{'label': name, 'argv': argv, 'returnCode': 0} for name, argv in expected_regression.items()],
                   'regression commands do not bind fresh invocation')


def verify_retained_bytes(output, pre, owned, regression):
    owned_root = output / 'owned-jni'
    regression_root = output / 'regressions'
    root = Path(pre['commands']['owned-jni'][pre['commands']['owned-jni'].index('--root') + 1])
    engine.require(sha(owned_root / 'engine.jar') == pre['engine']['classesJarSha256'], 'owned copied engine JAR differs')
    engine.require(sha(owned_root / 'library/libtermux.so') == owned['librarySha256'], 'owned host library differs')
    source_tree = ast.parse(engine.data(root / 'tools/verify_owned_jni_host_contract.py'))
    literals = [ast.literal_eval(node.value) for node in source_tree.body if isinstance(node, ast.Assign)
                and any(isinstance(target, ast.Name) and target.id == 'JAVA' for target in node.targets)]
    engine.require(len(literals) == 1 and isinstance(literals[0], str), 'unreviewed owned Java fixture declaration')
    fixture_bytes = literals[0].encode('utf-8')
    engine.require(engine.data(owned_root / 'fixture/OwnedJniHostContract.java') == fixture_bytes and
                   engine.sha(fixture_bytes) == owned['fixtureSha256'], 'owned generated fixture differs')
    fixture_classes = inventory(owned_root / 'classes')
    engine.require(set(fixture_classes) == {'com/termux/terminal/OwnedJniHostContract.class',
                    'com/termux/terminal/OwnedJniHostContract$Waiter.class'} and
                   fixture_classes == owned['fixtureClasses'], 'owned compiled fixture inventory differs')
    expected_stdout = 'OWNED_JNI_HOST_PASS checks=' + str(owned['checks']) + ' jni_sha256=' + pre['engine']['jniClassSha256'] + '\n'
    engine.require(engine.data(owned_root / 'java.stdout.log').decode() == expected_stdout and
                   engine.data(owned_root / 'java.stderr.log') == b'', 'owned runtime logs contradict success')
    engine.require(sha(regression_root / 'actual-engine-classes.jar') == pre['engine']['classesJarSha256'],
                   'regression copied engine differs')
    for label in ('junit', 'hamcrest'):
        argv = pre['commands']['regressions']
        original = argv[argv.index('--' + label) + 1]
        engine.require(sha(regression_root / (label + '.jar')) == pre['dependencies'][original],
                       'regression copied dependency differs: ' + label)
    fixture_sources = {Path(path).name: digest for path, digest in pre['sourceSha256'].items() if path.endswith('.java')}
    engine.require(inventory(regression_root / 'sources') == fixture_sources, 'regression copied fixtures differ')
    engine.require({p:h for p,h in inventory(regression_root).items() if p != 'receipt.json'} == regression['evidenceSha256'],
                   'regression child evidence inventory differs')
    classes = engine.inspect.archive_entries(engine.data(regression_root / 'actual-engine-classes.jar'))
    from urllib.parse import urlparse, unquote
    logs = {}
    for label, pattern, expected in (
        ('pty-junit', 'LOADED_AAR_CLASS', {'TerminalPtyIo', 'TerminalProcessOwner', 'ByteQueue'}),
        ('pty-pipe', 'LOADED_AAR_CLASS', {'TerminalPtyIo', 'TerminalProcessOwner', 'ByteQueue'}),
        ('resize-junit', 'LOADED_RESIZE_CLASS', {'TerminalResizeState', 'TerminalResizeState$Receipt',
                                               'TerminalResizeState$Backend', 'TerminalResizeState$Listener'})):
        text = engine.data(regression_root / (label + '.stdout.log')).decode('utf-8')
        logs[label] = text
        engine.require(engine.data(regression_root / (label + '.stderr.log')) == b'', 'regression runtime diagnostics emitted')
        rows = re.findall(r'^' + pattern + r' name=(\S+) codeSource=(\S+) resource=(\S+) sha256=([a-f0-9]{64})$', text, re.M)
        engine.require(len(rows) == len(expected) and {n.rsplit('.',1)[1] for n,*_ in rows} == expected,
                       'missing loaded regression class identities')
        for name, source, resource, digest in rows:
            entry = name.replace('.', '/') + '.class'
            engine.require(urlparse(source).scheme == 'file' and
                 Path(unquote(urlparse(source).path)) == regression_root / 'actual-engine-classes.jar' and
                 resource == 'jar:' + source + '!/' + entry and digest == engine.sha(classes[entry]),
                 'runtime regression class binding differs')
    engine.require('ACTUAL_AAR_JUNIT tests=25 failures=0' in logs['pty-junit'] and
                   'ACTUAL_ENGINE_RESIZE_JUNIT tests=12 failures=0 ignored=0 assumptions=0' in logs['resize-junit'],
                   'actual regression success logs differ')
    metrics = [dict(part.split('=',1) for part in row.split()) for row in re.findall(r'^HOST_PIPE_METRIC (.+)$',logs['pty-pipe'],re.M)]
    measured = [dict(part.split('=',1) for part in row.split()) for row in re.findall(r'^MEASURED_HOST_PIPE (.+)$',logs['pty-pipe'],re.M)]
    engine.require(metrics == regression['pipeMetrics'] and measured == [regression['measuredPipe']],
                   'actual pipe logs contradict child receipt')


def inspect_results(output, pre):
    owned = read_json(output / 'owned-jni/receipt.json')
    regression = read_json(output / 'regressions/receipt.json')
    engine.require(owned['status'] == 'PASS' and owned['deviceValidated'] is False and
                   owned['apkAdmitted'] is False and owned['childCleanupStatus'] == 'OWNED_TEST_CHILDREN_REAPED',
                   'owned JNI host did not finish/reap successfully')
    engine.require(owned['engineJarSha256'] == pre['engine']['classesJarSha256'] and
                   owned['jniClassSha256'] == pre['engine']['jniClassSha256'] and
                   owned['sourceBefore'] == owned['sourceAfter'] and
                   owned['sourceBefore']['engine'] == pre['engine']['aarSha256'] and
                   owned['sourceBefore']['source'] == pre['sourceSha256']['third_party/termux/native/termux.c'] and
                   all(pre['tools'].get(p) == h for p,h in owned['toolHashes'].items()) and
                   owned['harnessSha256'] == pre['sourceSha256']['tools/verify_owned_jni_host_contract.py'],
                   'owned JNI input binding mismatch')
    engine.require(regression['kind'] == 'CURRENT_ENGINE_HOST_FIXTURES' and
                   regression['tests'] == 37 and regression['failures'] == 0 and
                   regression['deviceValidated'] is False and regression['apkAdmitted'] is False and
                   regression['productionSourceCompiled'] is False and
                   regression['aarSha256'] == pre['engine']['aarSha256'] and
                   regression['classesJarSha256'] == pre['engine']['classesJarSha256'],
                   'regression receipt mismatch')
    verify_retained_bytes(output, pre, owned, regression)
    verify_child_commands(output, pre, owned)
    for name, argv in pre['commands'].items():
        record = read_json(output / (name + '.command.json'))
        engine.require(record == {'argv': argv, 'cwd': str(output), 'environment': ENV, 'returnCode': 0},
                       'host invocation record differs from fresh recipe')
    return {'ownedChecks': owned['checks'], 'regressionTests': 37,
            'ownedReceiptSha256': sha(output / 'owned-jni/receipt.json'),
            'regressionReceiptSha256': sha(output / 'regressions/receipt.json')}


def verify(root, java, android, archive, built, junit, hamcrest, output):
    pre = prepare(root, java, android, archive, built, junit, hamcrest, output)
    engine.require(read_json(output / 'pre-run.json') == pre, 'pre-run lineage differs from selected verified build')
    result = inspect_results(output, pre)
    receipt = read_json(output / 'receipt.json')
    expected = {'schema': 1, 'kind': KIND, 'status': 'PASS', 'deviceValidated': False,
                'apkAdmitted': False, 'releaseCompatible': False, 'preRunSha256': sha(output / 'pre-run.json'),
                **result, 'evidence': {p: h for p, h in inventory(output).items() if p != 'receipt.json'}}
    engine.require(receipt == expected, 'host binding receipt or immutable evidence differs')
    return receipt


def run(root, java, android, archive, built, junit, hamcrest, output):
    engine.require(not output.exists(), 'output exists; never overwrite a prior host run')
    for protected in (root, java, android, archive, built, junit, hamcrest):
        engine.require(output != protected and output not in protected.parents and protected not in output.parents,
                       'host output overlaps a protected input')
    pre = prepare(root, java, android, archive, built, junit, hamcrest, output)
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / 'pre-run.json', pre)
    for name, argv in pre['commands'].items():
        record = {'argv': argv, 'cwd': str(output), 'environment': ENV}
        try:
            result = subprocess.run(argv, cwd=output, env=ENV, capture_output=True, timeout=600)
        except subprocess.TimeoutExpired as error:
            (output / (name + '.stdout.log')).write_bytes(error.stdout or b'')
            (output / (name + '.stderr.log')).write_bytes(error.stderr or b'')
            write_json(output / (name + '.command.json'), {**record, 'timedOut': True, 'cleanupConfirmed': False})
            raise ValueError('host wrapper timed out; child cleanup unconfirmed, no automatic retry') from error
        (output / (name + '.stdout.log')).write_bytes(result.stdout)
        (output / (name + '.stderr.log')).write_bytes(result.stderr)
        write_json(output / (name + '.command.json'), {**record, 'returnCode': result.returncode})
        engine.require(result.returncode == 0, name + ' failed; retained logs, no automatic retry')
    engine.require(prepare(root, java, android, archive, built, junit, hamcrest, output) == pre,
                   'fresh build/source/tools changed during host execution')
    result = inspect_results(output, pre)
    write_json(output / 'receipt.json', {'schema': 1, 'kind': KIND, 'status': 'PASS',
                'deviceValidated': False, 'apkAdmitted': False, 'releaseCompatible': False,
                'preRunSha256': sha(output / 'pre-run.json'), **result, 'evidence': inventory(output)})
    return verify(root, java, android, archive, built, junit, hamcrest, output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('run', 'verify'))
    for name in ('root', 'java-home', 'android-jar', 'vendor-archive', 'engine-output', 'junit', 'hamcrest', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    paths = [engine.checked(getattr(args, name)) for name in
             ('root', 'java_home', 'android_jar', 'vendor_archive', 'engine_output', 'junit', 'hamcrest', 'output')]
    try:
        result = (run if args.action == 'run' else verify)(*paths)
        print(json.dumps({'status': result['status'], 'kind': KIND,
                          'ownedChecks': result['ownedChecks'], 'regressionTests': result['regressionTests']}))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        raise SystemExit('CURRENT_ENGINE_HOST_V1_FAILED: ' + str(error)) from error


if __name__ == '__main__':
    main()
