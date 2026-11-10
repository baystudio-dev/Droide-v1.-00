#!/usr/bin/env python3
"""Run preserved PTY and current resize fixtures against an explicit fresh engine AAR."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

import java_archive as archive_tools

ENV = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
PRESERVED = {
    'TerminalOwnedProcessTest.java': '6169fa33f12fc537490ee08f1cf2302489622c79a8cb2f85c16aa0e3065df52c',
    'TerminalPtyReadProgressTest.java': 'c62c06cea093824dfd3977850350f6c2c56fa3452f5f255dbb91f8ec937be360',
    'ReaderHealthyOutputTiming.java': '9ce8a622834b22864dc2723df75b7214a92d91a09f0d227d7f6f2bedff21d79d',
    'AarFixtureRunner.java': '609896f2d5f092000f2d5c3e4960924ca5e887f35a2b72c8407244f4d5e66d7b',
}
DEPENDENCIES = {
    'junit': '8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3',
    'hamcrest': '66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9',
}
RESIZE = ('TerminalResizeStateContractTest.java', 'TerminalResizeFixtureRunner.java')


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def clean_path(value: Path) -> Path:
    path = value.absolute()
    if '..' in path.parts or any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symlink or traversal in input/output path')
    return path


def fixture_entry(name: str, families: tuple[str, ...]) -> bool:
    return name.endswith('.class') and any(
        name == family + '.class' or name.startswith(family + '$') for family in families)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'engine-aar', 'java-home', 'junit', 'hamcrest', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    root, aar, java, output = map(clean_path, (args.root, args.engine_aar, args.java_home, args.output))
    if Path(__file__).resolve() != root / 'tools/run_terminal_engine_regressions.py' or Path(archive_tools.__file__).resolve() != root / 'tools/java_archive.py':
        raise ValueError('Selected root differs from the executing runner/helper')
    inputs = [root, aar, java, clean_path(args.junit), clean_path(args.hamcrest)]
    if output.exists() or any(output == p or output in p.parents or p in output.parents for p in inputs):
        raise ValueError('Output must be fresh and disjoint from all inputs')
    before = {str(p): sha(p.read_bytes()) for p in (aar, Path(__file__).resolve(), Path(archive_tools.__file__).resolve())}
    source_dir = root / 'third_party/termux/derivatives/pty-io/fixtures'
    sources = {}
    for name, wanted in PRESERVED.items():
        p = clean_path(source_dir / name); data = p.read_bytes()
        if sha(data) != wanted: raise ValueError('Preserved fixture changed: ' + name)
        sources[name] = data; before[str(p)] = sha(data)
    for name in RESIZE:
        p = clean_path(root / 'tools/tests/terminal-engine-fixtures' / name)
        sources[name] = p.read_bytes(); before[str(p)] = sha(sources[name])
    dependencies = {}
    for name, wanted in DEPENDENCIES.items():
        p = clean_path(getattr(args, name)); data = p.read_bytes()
        if sha(data) != wanted: raise ValueError('Test dependency identity mismatch: ' + name)
        dependencies[name] = data; before[str(p)] = sha(data)
    tools = {str(java / 'bin' / name): sha((java / 'bin' / name).read_bytes()) for name in ('javac', 'java')}
    before.update(tools)
    entries = archive_tools.archive_entries(aar.read_bytes())
    classes = archive_tools.archive_entries(entries['classes.jar'])
    families = tuple('com/termux/terminal/' + n.removesuffix('.java') for n in sources)
    if any(fixture_entry(name, families) or name.startswith(('org/junit/', 'org/hamcrest/')) for name in classes):
        raise ValueError('Selected engine contains fixture classes that could shadow tests')
    output.mkdir(parents=True)
    (output / 'sources').mkdir(); (output / 'classes').mkdir(); (output / 'empty-sourcepath').mkdir()
    jar = output / 'actual-engine-classes.jar'; jar.write_bytes(entries['classes.jar'])
    for name, data in sources.items(): (output / 'sources' / name).write_bytes(data)
    for name, data in dependencies.items(): (output / (name + '.jar')).write_bytes(data)
    classpath = os.pathsep.join(str(p) for p in (jar, output / 'junit.jar', output / 'hamcrest.jar'))
    commands = []
    def run(label: str, command: list[str]) -> str:
        try:
            result = subprocess.run(command, cwd=output, env=ENV, capture_output=True, timeout=180)
        except subprocess.TimeoutExpired as error:
            (output / (label + '.stdout.log')).write_bytes(error.stdout or b'')
            (output / (label + '.stderr.log')).write_bytes(error.stderr or b'')
            commands.append({'label': label, 'argv': command, 'timedOut': True, 'timeoutSeconds': 180})
            (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
            raise ValueError(label + ' timed out; partial logs retained') from error
        (output / (label + '.stdout.log')).write_bytes(result.stdout)
        (output / (label + '.stderr.log')).write_bytes(result.stderr)
        commands.append({'label': label, 'argv': command, 'returnCode': result.returncode})
        (output / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        if result.returncode: raise ValueError(label + ' failed; see retained logs')
        return result.stdout.decode('utf-8')
    run('compile-fixtures', [str(java / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-proc:none',
        '-implicit:none', '-sourcepath', str(output / 'empty-sourcepath'), '-cp', classpath,
        '-d', str(output / 'classes'), *[str(output / 'sources' / n) for n in sources]])
    for p in (output / 'classes').rglob('*'):
        if p.is_file() and not fixture_entry(str(p.relative_to(output / 'classes')), families):
            raise ValueError('Fixture compilation emitted unexpected production class: ' + str(p))
    launch = [str(java / 'bin/java'), '-ea', '-cp', classpath + os.pathsep + str(output / 'classes')]
    junit = run('pty-junit', launch + ['com.termux.terminal.AarFixtureRunner', 'junit', str(jar)])
    pipe = run('pty-pipe', launch + ['com.termux.terminal.AarFixtureRunner', 'pipe', str(jar)])
    resize = run('resize-junit', launch + ['com.termux.terminal.TerminalResizeFixtureRunner', str(jar)])
    if 'ACTUAL_AAR_JUNIT tests=25 failures=0' not in junit or 'ACTUAL_ENGINE_RESIZE_JUNIT tests=12 failures=0 ignored=0 assumptions=0' not in resize:
        raise ValueError('Unexpected executed fixture counts')
    from urllib.parse import urlparse, unquote
    for text, pattern, expected in (
        (junit, 'LOADED_AAR_CLASS', {'TerminalPtyIo', 'TerminalProcessOwner', 'ByteQueue'}),
        (pipe, 'LOADED_AAR_CLASS', {'TerminalPtyIo', 'TerminalProcessOwner', 'ByteQueue'}),
        (resize, 'LOADED_RESIZE_CLASS', {'TerminalResizeState', 'TerminalResizeState$Receipt', 'TerminalResizeState$Backend', 'TerminalResizeState$Listener'})):
        records = re.findall(r'^' + pattern + r' name=(\S+) codeSource=(\S+) resource=(\S+) sha256=([a-f0-9]{64})$', text, re.M)
        if len(records) != len(expected) or {n.rsplit('.', 1)[1] for n, *_ in records} != expected:
            raise ValueError('Missing loaded production class identities')
        for name, source, resource, digest in records:
            entry = name.replace('.', '/') + '.class'
            if urlparse(source).scheme != 'file' or Path(unquote(urlparse(source).path)) != jar or resource != 'jar:' + source + '!/' + entry or digest != sha(classes[entry]):
                raise ValueError('Loaded production class does not match selected AAR')
    metrics = [dict(x.split('=', 1) for x in row.split()) for row in re.findall(r'^HOST_PIPE_METRIC (.+)$', pipe, re.M)]
    if len(metrics) != 4: raise ValueError('Missing pipe trials')
    for i, m in enumerate(metrics):
        if any(m.get(k) != v for k, v in {'trial': str(i), 'bytes': str(262144 if i == 0 else 4194304),
            'integrity': 'exact', 'backoffs': '0', 'requestedBackoffMillis': '0'}.items()):
            raise ValueError('Pipe progress contract failed')
    measured = re.findall(r'^MEASURED_HOST_PIPE (.+)$', pipe, re.M)
    if len(measured) != 1: raise ValueError('Missing measured pipe record')
    measured = dict(x.split('=', 1) for x in measured[0].split())
    expected_hash = sha(bytes((i * 17 + (i >> 8)) & 255 for i in range(4194304)))
    if any(measured.get(k) != v for k, v in {'bytes': '4194304', 'sha256': expected_hash,
        'backoffs': '0', 'requestedBackoffMillis': '0', 'nativePtyExecuted': 'false'}.items()):
        raise ValueError('Measured payload/progress mismatch')
    for p, digest in before.items():
        if sha(Path(p).read_bytes()) != digest: raise ValueError('Input changed during run: ' + p)
    if jar.read_bytes() != entries['classes.jar']: raise ValueError('Selected JAR changed during run')
    receipt = {'schemaVersion': 1, 'kind': 'CURRENT_ENGINE_HOST_FIXTURES', 'tests': 37, 'failures': 0,
        'deviceValidated': False, 'apkAdmitted': False, 'productionSourceCompiled': False,
        'aarSha256': before[str(aar)], 'classesJarSha256': sha(entries['classes.jar']),
        'inputSha256': before, 'environment': ENV, 'pipeMetrics': metrics, 'measuredPipe': measured,
        'evidenceSha256': {str(p.relative_to(output)): sha(p.read_bytes()) for p in sorted(output.rglob('*')) if p.is_file()}}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('CURRENT_ENGINE_HOST_FIXTURES_OK tests=37 deviceValidated=false')


if __name__ == '__main__':
    main()
