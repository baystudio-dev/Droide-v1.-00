#!/usr/bin/env python3
"""Two clean current-source debug builds; no upload, release signing or device claim.

All outputs are new. Reference compilation is performed here, never supplied by
caller or extracted from the primary APK. Failed/intermediate APKs are UNADMITTED.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time
import xml.etree.ElementTree as ET
import current_debug_inputs as inputs
import current_gradle_distribution as gradle_distribution
import current_apk_binding as apk_binding
import verify_pty_dex_binding as dex_binding
from current_apk_binding import compare
ROOT = Path(__file__).resolve().parents[1]

def write(path: Path, value: object) -> None:
    """Create a new evidence record without replacing an earlier result."""
    with path.open('x') as f:
        json.dump(value, f, indent=2)
        f.write('\n')

def safe(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    if any((p.is_symlink() for p in (path, *path.parents))):
        raise ValueError('Linked root: ' + str(path))
    return path

def tree(path: Path) -> dict:
    """Measure tool inputs; retain symbolic-link identity instead of following it."""
    result = {}
    for p in sorted(path.rglob('*')):
        if p.is_symlink():
            result[str(p.relative_to(path))] = {'link': os.readlink(p)}
        elif p.is_file():
            result[str(p.relative_to(path))] = inputs.sha(p.read_bytes())
    return result

def group_exists(group: int) -> bool:
    try:
        os.killpg(group, 0)
        return True
    except ProcessLookupError:
        return False


def stop_owned_group(process: subprocess.Popen, grace_seconds: float = 5) -> dict:
    """Keep our leader unreaped until all signals, preventing group-ID recycling."""
    group = process.pid
    actions = []
    try:
        owned = process.returncode is None and os.getpgid(group) == group and os.getsid(group) == group
    except ProcessLookupError:
        owned = False
    if not owned:
        return {
            'processGroup': group, 'signals': actions, 'leaderReaped': process.returncode is not None,
            'status': 'CLEANUP_UNCONFIRMED', 'ownershipEstablished': False,
            'escapedDescendantsTracked': False,
        }
    try:
        os.killpg(group, signal.SIGTERM)
        actions.append('SIGTERM')
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + grace_seconds
    while group_exists(group) and time.monotonic() < deadline:
        # Do not poll/wait here: retaining the child PID prevents its reuse.
        time.sleep(0.05)
    try:
        os.killpg(group, signal.SIGKILL)
        actions.append('SIGKILL')
    except ProcessLookupError:
        pass
    # No more group signals after this wait is permitted to reap the leader.
    try:
        process.wait(timeout=grace_seconds)
        leader_reaped = True
    except subprocess.TimeoutExpired:
        leader_reaped = False
    remaining = group_exists(group)
    return {
        'processGroup': group,
        'signals': actions,
        'leaderReaped': leader_reaped,
        'groupStillExists': remaining,
        'status': 'OWNED_GROUP_GONE' if leader_reaped and not remaining else 'CLEANUP_UNCONFIRMED',
        'ownershipEstablished': True,
        'escapedDescendantsTracked': False,
    }


def run(work: Path, label: str, cmd: list[str], env: dict[str, str], timeout: int = 3600) -> str:
    """Retain evidence and bound cleanup of this command's own new process group."""
    started = time.time()
    process = subprocess.Popen(
        cmd, cwd=work, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        start_new_session=True,
    )
    try:
        stdout, stderr = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired as error:
        cleanup = stop_owned_group(process)
        try:
            stdout, stderr = process.communicate(timeout=5)
        except subprocess.TimeoutExpired as trailing:
            stdout, stderr = trailing.stdout or error.stdout or b'', trailing.stderr or error.stderr or b''
            cleanup['status'] = 'CLEANUP_UNCONFIRMED'
            cleanup['inheritedPipesStillOpen'] = True
            process.stdout.close()
            process.stderr.close()
        (work / (label + '.stdout')).write_bytes(stdout)
        (work / (label + '.stderr')).write_bytes(stderr)
        write(work / (label + '.command.json'), {
            'argv': cmd, 'timedOut': True, 'started': started, 'finished': time.time(),
            'cleanup': cleanup, 'automaticRetryAllowed': False,
        })
        raise
    (work / (label + '.stdout')).write_bytes(stdout)
    (work / (label + '.stderr')).write_bytes(stderr)
    write(work / (label + '.command.json'), {
        'argv': cmd, 'returnCode': process.returncode, 'started': started,
        'finished': time.time(), 'ownedProcessGroup': process.pid,
    })
    if process.returncode:
        raise ValueError(label + ' failed; retained UNADMITTED evidence at ' + str(work))
    return stdout.decode(errors='strict')

EXPECTED_FRAMEWORK = {
    'android-all-instrumented-10-robolectric-5803371-i7.jar': '700fb3bb88ff346969b955241b86d2f55d395488bf5df1e8eddfbd003980c78a',
    'android-all-instrumented-15-robolectric-12650502-i7.jar': '33c2d7cc4186d4605269d5d411fa1818972c5b30a9f70bfbbd3d430b076af52f',
}

EXPECTED_RUNTIME_COUNTS = {
    'com.baystudio.droide.core.TerminalResizeAuthorityTest': 2,
    'com.baystudio.droide.ui.TerminalResizeRetryTest': 19,
    'com.baystudio.droide.ui.TerminalImeOwnerR61Test': 22,
    'com.baystudio.droide.ui.NativeTerminalGeometryR63Test': 16,
    'com.baystudio.droide.core.CurrentTerminalResizeBridgeContractTest': 12,
}


def verify_runtime_xml(source: Path, receipt: dict) -> dict:
    """Independently bind the exact executed cohort, not only its summary receipt."""
    if receipt.get('expectedCounts') != EXPECTED_RUNTIME_COUNTS or receipt.get('actualCounts') != EXPECTED_RUNTIME_COUNTS:
        raise ValueError('Runtime cohort selection/counts differ')
    directory = source / 'app/build-current-debug/test-results/testDebugUnitTest'
    expected_paths = {directory / ('TEST-' + name + '.xml'): name for name in EXPECTED_RUNTIME_COUNTS}
    if set(directory.glob('TEST-*.xml')) != set(expected_paths):
        raise ValueError('Runtime XML suite inventory differs')
    declared = receipt.get('xml', {})
    if set(declared) != {str(path) for path in expected_paths}:
        raise ValueError('Runtime receipt XML paths differ')
    if receipt.get('offlineFrameworks') != EXPECTED_FRAMEWORK or receipt.get('loadedFrameworks') != sorted(EXPECTED_FRAMEWORK):
        raise ValueError('Runtime framework identity summary differs')
    measured = {}
    loaded_frameworks = set()
    for path, name in expected_paths.items():
        data = inputs.regular(safe(path))
        digest = inputs.sha(data)
        if declared[str(path)] != digest:
            raise ValueError('Runtime XML hash differs')
        suite = ET.fromstring(data)
        cases = suite.findall('testcase')
        expected = EXPECTED_RUNTIME_COUNTS[name]
        if suite.tag != 'testsuite' or suite.get('name') != name or int(suite.get('tests', '-1')) != expected:
            raise ValueError('Runtime XML suite/count differs')
        if any(int(suite.get(key, '-1')) != 0 for key in ('failures', 'errors', 'skipped')):
            raise ValueError('Runtime XML has failures, errors or skips')
        if len(cases) != expected or any(case.find(tag) is not None for case in cases for tag in ('failure', 'error', 'skipped')):
            raise ValueError('Runtime XML case outcomes differ')
        identities = {(case.get('classname'), case.get('name')) for case in cases}
        if len(identities) != expected or any(owner != name or not method for owner, method in identities):
            raise ValueError('Runtime XML case identity differs')
        logged = suite.findtext('system-out', default='')
        for filename, expected_digest in EXPECTED_FRAMEWORK.items():
            if 'CURRENT_APP_LOADED_FRAMEWORK file=' + filename + ' sha256=' + expected_digest in logged:
                loaded_frameworks.add(filename)
        measured[str(path)] = digest
    if loaded_frameworks != set(EXPECTED_FRAMEWORK):
        raise ValueError('Actual XML lacks both selected framework observations')
    return measured

def build(a: argparse.Namespace) -> None:
    root = safe(a.root)
    out = safe(a.output)
    composite = safe(a.composite)
    jdk = safe(a.java_home)
    sdk = safe(a.sdk)
    gradle = safe(a.gradle)
    cache = safe(a.dependency_cache)
    gradle_archive = safe(a.gradle_archive)
    robolectric = safe(a.robolectric_dir)
    if root != ROOT:
        raise ValueError('Execute orchestrator from selected root')
    for module, name in (
        (inputs, 'current_debug_inputs.py'),
        (apk_binding, 'current_apk_binding.py'),
        (dex_binding, 'verify_pty_dex_binding.py'),
        (gradle_distribution, 'current_gradle_distribution.py'),
    ):
        if safe(Path(module.__file__)) != root / 'tools' / name:
            raise ValueError('Imported verifier differs from selected source: ' + name)

    for p in (root, composite, jdk, sdk, gradle.parent.parent, gradle_archive, cache, robolectric):
        if out == p or p in out.parents or out in p.parents:
            raise ValueError('Output overlaps an input')
    if out.exists():
        raise ValueError('Output exists; never reuse failed build roots')
    if not (cache / 'files-2.1').is_dir():
        raise ValueError('Dependency cache must be Gradle modules-2, not arbitrary user home')
    composite_receipt = json.loads(inputs.regular(composite / 'receipt.json'))
    if safe(Path(composite_receipt['inputs']['java_home'])) != jdk:
        raise ValueError('App JDK differs from verified composite Java toolchain')
    if safe(Path(composite_receipt['inputs']['sdk'])) != sdk:
        raise ValueError('App SDK differs from verified composite native toolchain')
    gradle_identity = gradle_distribution.verify(
        gradle_archive, gradle.parent.parent, root / 'gradle/wrapper/gradle-wrapper.properties',
    )
    if tree(robolectric) != EXPECTED_FRAMEWORK:
        raise ValueError('Offline SDK29/35 Robolectric framework identity differs')
    source = inputs.source_manifest(root)
    tool_roots = {'jdk': jdk, 'gradle': gradle.parent.parent, 'buildTools': sdk / 'build-tools/36.0.0', 'robolectric': robolectric}
    tool_before = {k: tree(v) for k, v in tool_roots.items()}
    android = inputs.sha(inputs.regular(sdk / 'platforms/android-36/android.jar'))
    if android != 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a':
        raise ValueError('Wrong API36 platform')
    context = {'schemaVersion': 1,
        'sourceSha256': source,
        'compositeReceiptSha256': inputs.sha(inputs.regular(composite / 'receipt.json'))}
    out.mkdir(parents=True)
    (out / 'home').mkdir()
    (out / 'tmp').mkdir()
    env = {'PATH': str(jdk / 'bin') + ':/usr/bin:/bin',
        'JAVA_HOME': str(jdk),
        'ANDROID_HOME': str(sdk),
        'ANDROID_SDK_ROOT': str(sdk),
        'HOME': str(out / 'home'),
        'TMPDIR': str(out / 'tmp'),
        'LANG': 'C.UTF-8',
        'LC_ALL': 'C.UTF-8',
        'TZ': 'UTC'}
    env['ANDROID_USER_HOME'] = str(out / 'home/android')
    env['GRADLE_USER_HOME'] = str(out / 'preflight-gradle-home')
    write(out / 'source-manifest.json', source)
    write(out / 'tool-identities.json', tool_before)
    write(out / 'gradle-vendor-identity.json', gradle_identity)
    write(out / 'environment.json', env)
    write(out / 'context.json', context)
    run(out,
        'composite-preflight',
        ['python3',
            '-B',
            '-E',
            '-s',
            str(root / 'tools/current_debug_inputs.py'),
            '--root',
            str(root),
            '--composite',
            str(composite),
            '--context',
            str(out / 'context.json')],
        env,
        inputs.COMPOSITE_TIMEOUT_SECONDS + 60)
    version = run(out, 'gradle-version', ['bash', str(gradle), '--version', '--no-daemon'], env, 120)
    if 'Gradle 8.13\n' not in version:
        raise ValueError('Expected Gradle8.13')
    run(out,
        'keytool-generate',
        [str(jdk / 'bin/keytool'),
            '-genkeypair',
            '-keystore',
            str(out / 'debug.keystore'),
            '-alias',
            'androiddebugkey',
            '-storepass',
            'android',
            '-keypass',
            'android',
            '-dname',
            'CN=Android Debug,O=Android,C=US',
            '-keyalg',
            'RSA',
            '-keysize',
            '2048',
            '-validity',
            '3650'],
        env,
        120)
    run(out,
        'keytool-certificate',
        [str(jdk / 'bin/keytool'),
            '-exportcert',
            '-keystore',
            str(out / 'debug.keystore'),
            '-alias',
            'androiddebugkey',
            '-storepass',
            'android',
            '-file',
            str(out / 'debug-cert.der')],
        env,
        120)
    signer = inputs.sha((out / 'debug-cert.der').read_bytes())
    results = {}
    dependencies = {}
    external_native = {}
    runtime_contracts = None
    runtime_evidence = None
    runtime_source = None
    cache_before = tree(cache)
    if any(isinstance(value, dict) for value in cache_before.values()):
        raise ValueError('Dependency cache contains symbolic links')
    for stage in ('primary', 'independent'):
        work = out / stage
        src = work / 'source'
        src.mkdir(parents=True)
        for name, wanted in source.items():
            data = inputs.regular(root / name)
            if inputs.sha(data) != wanted:
                raise ValueError('Source changed before copy')
            target = src / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        if inputs.source_manifest(src) != source:
            raise ValueError('Incomplete clean source copy')
        gh = work / 'gradle-home'
        (gh / 'caches').mkdir(parents=True)
        shutil.copytree(cache, gh / 'caches/modules-2', ignore=shutil.ignore_patterns('*.lock'))
        stageenv = dict(env, GRADLE_USER_HOME=str(gh))
        cmd = ['bash',
            str(gradle),
            '--offline',
            '--no-daemon',
            '--no-build-cache',
            '--no-configuration-cache',
            '--rerun-tasks',
            '--dependency-verification=strict',
            '--max-workers=2',
            '-Pandroid.builder.sdkDownload=false',
            '-PDROIDE_CURRENT_DEBUG_VERIFIED=true',
            '-PDROIDE_CURRENT_COMPOSITE=' + str(composite),
            '-PDROIDE_CURRENT_CONTEXT=' + str(out / 'context.json'),
            '-PDROIDE_CURRENT_DEBUG_KEYSTORE=' + str(out / 'debug.keystore'),
            '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=1024m -XX:ActiveProcessorCount=4 -Dfile.encoding=UTF-8 -Duser.home=' + str(out / 'home'),
            '-Pkotlin.compiler.execution.strategy=in-process',
            ':app:assembleCurrentDebugCandidate']
        if stage == 'primary':
            cmd[-1:] = [
                '-PDROIDE_CURRENT_APP_RUNTIME_CONTRACTS=true',
                '-PDROIDE_CURRENT_ROBOLECTRIC_DIR=' + str(robolectric),
                ':app:verifyCurrentAppRuntimeContracts',
            ]
        run(src, 'current-build', cmd, stageenv, inputs.COMPOSITE_TIMEOUT_SECONDS + 7200)
        apk = src / 'app/build-current-debug/outputs/apk/debug/app-debug.apk'
        results[stage] = inputs.regular(apk)
        if stage == 'primary':
            contract_dir = src / 'app/build-current-debug/current-app-runtime-contracts'
            runtime_contracts = json.loads(inputs.regular(contract_dir / 'receipt.json'))
            if (
                runtime_contracts.get('kind') != 'CURRENT_APP_ENGINE_RUNTIME_CONTRACTS'
                or runtime_contracts.get('status') != 'PASS'
                or runtime_contracts.get('tests') != 71
                or runtime_contracts.get('productionInputsUnchanged') is not True
                or runtime_contracts.get('fullSuiteReplacement') is not False
                or runtime_contracts.get('deviceValidated') is not False
            ):
                raise ValueError('Actual-engine app contracts are not admitted')
            runtime_source = src
            runtime_evidence = {
                'contractFiles': tree(contract_dir),
                'xml': verify_runtime_xml(src, runtime_contracts),
            }
            write(out / 'actual-app-runtime-evidence.json', runtime_evidence)
        dependencies[stage] = json.loads((src / 'app/build-current-debug/current-resolved-inputs-after.json').read_text())
        native = {}
        for artifact in dependencies[stage]['debugRuntimeClasspath']:
            path = Path(artifact['filePath'])
            data = inputs.regular(path)
            if inputs.sha(data) != artifact['sha256']:
                raise ValueError('Resolved dependency changed')
            if path.suffix == '.aar':
                from verify_pty_dex_binding import checked_archive
                for name, body in checked_archive(data).items():
                    if name.startswith('jni/') and name.endswith('.so'):
                        target = 'lib/' + name[4:]
                        if target in native:
                            raise ValueError('Duplicate dependency native provider')
                        native[target] = inputs.sha(body)
        expected_external = {'lib/' + abi + '/' + lib for abi in ('arm64-v8a',
                'armeabi-v7a',
                'x86',
                'x86_64') for lib in ('libdatastore_shared_counter.so',
                'libandroidx.graphics.path.so')}
        if set(native) != expected_external:
            raise ValueError('Unexpected dependency native inventory')
        external_native[stage] = native
        if inputs.source_manifest(src) != source or inputs.source_manifest(root) != source:
            raise ValueError('Source changed during build')
        bt = sdk / 'build-tools/36.0.0'
        signature = run(work,
            'signature',
            [str(bt / 'apksigner'),
                'verify',
                '--verbose',
                '--print-certs',
                '--min-sdk-version',
                '29',
                str(apk)],
            env,
            120)
        certs = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)', signature)
        if certs != [signer]:
            raise ValueError('Unexpected debug signer')
        run(work, 'zipalign', [str(bt / 'zipalign'), '-c', '-P', '16', '4', str(apk)], env, 120)
        badging = run(work, 'manifest-badging', [str(bt / 'aapt2'), 'dump', 'badging', str(apk)], env, 120)
        xml_tree = run(
            work, 'manifest-xml',
            [str(bt / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'],
            env, 120,
        )
        apk_binding.verify_manifest_policy(badging, xml_tree)
        if inputs.regular(apk) != results[stage]:
            raise ValueError('APK changed during verification')
    normalized = lambda d: {k: [{n: v for n,
                v in row.items() if n != 'filePath'} for row in rows] for k,
        rows in d.items()}
    if normalized(dependencies['primary']) != normalized(dependencies['independent']):
        raise ValueError('Resolved dependency graphs differ')
    if tree(cache) != cache_before:
        raise ValueError('Input dependency cache changed; evidence inconclusive')
    if {k: tree(v) for k, v in tool_roots.items()} != tool_before:
        raise ValueError('Build toolchain changed')
    run(out,
        'composite-postflight',
        ['python3',
            '-B',
            '-E',
            '-s',
            str(root / 'tools/current_debug_inputs.py'),
            '--root',
            str(root),
            '--composite',
            str(composite),
            '--context',
            str(out / 'context.json')],
        env,
        inputs.COMPOSITE_TIMEOUT_SECONDS + 60)
    if gradle_distribution.verify(
        gradle_archive, gradle.parent.parent, root / 'gradle/wrapper/gradle-wrapper.properties',
    ) != gradle_identity:
        raise ValueError('Gradle vendor identity changed during builds')
    if runtime_source is None or runtime_evidence != {
        'contractFiles': tree(runtime_source / 'app/build-current-debug/current-app-runtime-contracts'),
        'xml': verify_runtime_xml(runtime_source, runtime_contracts),
    }:
        raise ValueError('Runtime contract evidence changed before final admission')
    providers = {'lib/' + str(p.relative_to(composite / 'native')): inputs.sha(inputs.regular(p)) for p in (composite / 'native').rglob('*') if p.is_file()}
    if len(providers) != 12:
        raise ValueError('Composite does not contain exact12providers')
    if external_native['primary'] != external_native['independent']:
        raise ValueError('Dependency native bytes differ')
    if set(providers) & set(external_native['primary']):
        raise ValueError('Owned/dependency provider collision')
    providers.update(external_native['primary'])
    proof = compare(results['primary'], results['independent'], providers)
    proof.update(kind='CURRENT_DEBUG_APK_SOURCE_BOUND_DEVICE_UNVALIDATED',
        sourceManifestSha256=inputs.sha((out / 'source-manifest.json').read_bytes()),
        compositeReceiptSha256=context['compositeReceiptSha256'],
        debugSignerSha256=signer,
        resolvedDependencies=dependencies['primary'],
        actualAppRuntimeContracts=runtime_contracts,
        freshIndependentCompilation=True,
        apkStructureVerified=True,
        deviceValidated=False,
        releaseCompatible=False)
    (out / 'Droide-current-debug-device-unvalidated.apk').write_bytes(results['primary'])
    evidence_paths = [
        path for path in out.rglob('*')
        if path.is_file() and (
            path.name.endswith(('.command.json', '.stdout', '.stderr'))
            or path.parent == out and path.name in {
                'source-manifest.json', 'tool-identities.json', 'environment.json',
                'context.json', 'gradle-vendor-identity.json', 'debug-cert.der',
                'actual-app-runtime-evidence.json',
            }
        )
    ]
    proof['evidenceSha256'] = {
        str(path.relative_to(out)): inputs.sha(path.read_bytes()) for path in evidence_paths
    }
    # The dedicated debug private key stays local; only its public certificate is evidence.
    write(out / 'receipt.json', proof)
    print('CURRENT_DEBUG_APK_SOURCE_BOUND_DEVICE_UNVALIDATED ' + str(out / 'receipt.json'))
if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for n in ('root', 'output', 'composite', 'java-home', 'sdk', 'gradle', 'dependency-cache', 'gradle-archive', 'robolectric-dir'):
        p.add_argument('--' + n, required=True, type=Path)
    build(p.parse_args())
