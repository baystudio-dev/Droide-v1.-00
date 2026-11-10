#!/usr/bin/env python3
"""Run critical host regressions and require a complete nonempty result for every class."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

from provision_test_runtime import prepare_runtime

ROOT = Path(__file__).resolve().parents[1]


def selections(root=ROOT):
    manifest = json.loads((root / 'tools/release-test-suites.json').read_text())
    classes = manifest['classes']
    if manifest.get('schema') != 1 or not classes or len(classes) != len(set(classes)):
        raise ValueError('Invalid or empty release test inventory')
    for name in classes:
        if not re.fullmatch(r'com\.baystudio\.droide\.(?:[A-Za-z_$][A-Za-z0-9_$]*\.)*[A-Za-z_$][A-Za-z0-9_$]*', name):
            raise ValueError('Release tests require exact fully qualified classes')
        base = root / 'app/src/test/java' / name.replace('.', '/')
        if not base.with_suffix('.kt').is_file() and not base.with_suffix('.java').is_file():
            raise ValueError('Release test class source is missing: ' + name)
    return classes


def verify_results(directory, classes):
    executed = {name: 0 for name in classes}
    for path in directory.glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for case in suite.findall('.//testcase'):
            name = case.get('classname')
            if name not in executed:
                raise ValueError('Unexpected test class in release report: ' + str(name))
            if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                raise ValueError('Release test failed or skipped: ' + name + '.' + str(case.get('name')))
            executed[name] += 1
    missing = [name for name, count in executed.items() if not count]
    if missing:
        raise ValueError('Missing or empty release test reports: ' + ', '.join(missing))
    return executed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--gradle', type=Path, help='Existing Gradle 8.13 executable; defaults to the verified wrapper')
    parser.add_argument('--runtime-dir', type=Path, default=ROOT / 'app/build-host-jvm-tests/host-runtime')
    args = parser.parse_args()
    classes = selections()
    runtime = args.runtime_dir.resolve()
    prepare_runtime(ROOT, runtime, args.offline)
    results = ROOT / 'app/build-host-jvm-tests/test-results/testDebugUnitTest'
    if results.exists():
        shutil.rmtree(results)
    command = ([str(args.gradle.resolve(strict=True))] if args.gradle else ['bash', str(ROOT / 'gradlew')])
    command.append(':app:testDebugUnitTest')
    for name in classes:
        command.extend(['--tests', name])
    command.extend([
        '-PDROIDE_HOST_JVM_TESTS_ONLY=true', '-Pandroid.builder.sdkDownload=false',
        '-PDROIDE_HOST_TEST_RUNTIME_DIR=' + str(runtime),
        '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
        '-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8',
        '--dependency-verification=strict', '--no-daemon', '--max-workers=1',
        '--no-configuration-cache', '--no-build-cache',
    ])
    if args.offline:
        command.append('--offline')
    subprocess.run(command, cwd=ROOT, check=True)
    print(json.dumps({'executedTests': verify_results(results, classes)}, indent=2))


if __name__ == '__main__':
    main()
