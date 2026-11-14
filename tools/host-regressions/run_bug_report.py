#!/usr/bin/env python3
"""Compile/run the native report domain and real OkHttp adapter with in-process HTTP fixtures only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--toolchain', type=Path, required=True)
p.add_argument('--dependencies', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root, output, deps = a.root.resolve(), a.output.resolve(), a.dependencies.resolve()
output.mkdir(parents=True, exist_ok=False)
classes = output / 'classes'; classes.mkdir()
lib = a.toolchain.resolve() / 'gradle-8.13/lib'
java = a.toolchain.resolve() / 'jdk17/bin/java'
javac = java.with_name('javac')
compiler = [lib / name for name in ('kotlin-compiler-embeddable-2.0.21.jar', 'kotlin-stdlib-2.0.21.jar', 'kotlin-script-runtime-2.0.21.jar', 'kotlin-reflect-2.0.21.jar', 'trove4j-1.0.20200330.jar', 'kotlinx-coroutines-core-jvm-1.6.4.jar', 'annotations-24.0.1.jar')]
runtime = [lib / name for name in ('kotlin-stdlib-2.0.21.jar', 'junit-4.13.2.jar', 'hamcrest-core-1.3.jar', 'annotations-24.0.1.jar', 'kotlinx-coroutines-core-jvm-1.6.4.jar')]
runtime += [deps / name for name in ('okhttp-4.12.0.jar', 'kotlinx-serialization-json-jvm-1.7.2.jar', 'kotlinx-serialization-core-jvm-1.7.2.jar')]
runtime += [root / 'third_party/maven/com/squareup/okio/okio-jvm/3.17.0/okio-jvm-3.17.0.jar']
production = ['BugReportPolicy', 'BugReportCoordinator', 'OfficialBugReportTransport', 'OkHttpAwait']
tests = ['BugReportPolicyR75Test', 'BugReportCoordinatorR75Test', 'OfficialBugReportTransportR75Test']
sources = [root / f'app/src/main/java/com/baystudio/droide/core/{name}.kt' for name in production]
sources += [root / f'app/src/test/java/com/baystudio/droide/core/{name}.kt' for name in tests]
runner = root / 'tools/host-regressions/r75-report/FocusedJUnitRunner.java'
inputs = sources + [runner, Path(__file__).resolve()]
snapshot = output / 'input-snapshot'
for source in inputs:
    target = snapshot / source.relative_to(root); target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
compiled = [snapshot / v.relative_to(root) for v in sources]
def digest(path):
    return {'path': str(path), 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
cp = os.pathsep.join(map(str, [classes, *runtime]))
commands = [[str(javac), '-proc:none', '-implicit:none', '-cp', cp, '-d', str(classes), str(snapshot / runner.relative_to(root))],
    [str(java), '-Xmx512m', '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
     '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, compiled)],
    [str(java), '-Xmx512m', '-cp', cp, 'FocusedJUnitRunner', str(output), *['com.baystudio.droide.core.' + name for name in tests]]]
env = {'PATH': '/usr/bin:/bin', 'JAVA_HOME': str(java.parent.parent), 'HOME': str(output), 'TMPDIR': str(output), 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
plan = {'scope': 'Exact production domain/coordinator and OkHttp adapter with real HTTP request/body/parser classes, in-process application interceptor fixtures and fake transport. No socket, DNS, production request, Android/device, Compose, full Gradle or APK proof. Coroutines JVM fixture 1.6.4 and compiler 2.0.21 are host-only tools, not bundled or app dependency edits.',
    'inputs': [digest(v) for v in inputs], 'input_snapshot': [digest(snapshot / v.relative_to(root)) for v in inputs],
    'commands': commands, 'tools': [digest(v) for v in [java, javac, *compiler, *runtime]], 'environment': env}
(output / 'PLAN.json').write_text(json.dumps(plan, indent=2) + '\n')
steps = []
for index, command in enumerate(commands, 1):
    start = time.monotonic()
    result = subprocess.run(command, cwd=root, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    log = output / f'{index}.log'; log.write_text(result.stdout)
    print(result.stdout, flush=True)
    steps.append({'step': index, 'exit_code': result.returncode, 'seconds': time.monotonic() - start, 'log': digest(log)})
    if result.returncode: break
(output / 'RESULT.json').write_text(json.dumps({'steps': steps, 'input_hashes_unchanged': all(digest(Path(v['path'])) == v for v in plan['inputs']), 'android_runtime': False, 'real_network_requests': 0}, indent=2) + '\n')
raise SystemExit(steps[-1]['exit_code'])
