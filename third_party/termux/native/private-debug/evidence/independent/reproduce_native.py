#!/usr/bin/env python3
"""Independent clean-root compile; no produced Android or Java code is executed."""
from pathlib import Path
import datetime
import hashlib
import json
import re
import shlex
import stat
import subprocess
import sys

E = Path(__file__).resolve().parent
R = E.parents[3]
B = R / 'build/R40/source/Droide-reconstructed-source'
O = R / 'build/R40/native-independent'
P = R / 'build/R40/native-primary'
PE = R / 'build/R40/evidence/native-primary'
SDK = R / 'toolchain/android-sdk'
NDK = SDK / 'ndk/28.2.13676358'
CM = SDK / 'cmake/3.22.1/bin'
LLVM = NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
JDK = R / 'toolchain/jdk-17.0.20.1+1'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
NAMES = ('libtermux.so', 'libdroide_files.so')
ARCHIVE = 'e767a89ace01569e1e0412b2bbd03de171194caa85c3989b770d4bfe9438d317'
REVIEW = '27d73550110628e5df54f8b188a254675528f2c8d3c8df45bd706075d3f173be'
PINS = {
    'app/src/main/cpp/CMakeLists.txt': '5822f744df7b90c4473ad690a8d068e82094a3e5a4be730c750e9aa80fd8982c',
    'app/src/main/cpp/droide_files.c': '8161f13dcca6e7df65c2ffe8be3298465c2593ace41f56114340475885fb5ea3',
    'third_party/termux/native/termux.c': 'e6e4e563865857af85fd6e101021fb3e247ecfc93f9261e1242d0ef637edc000',
    'third_party/termux/native/OWNED_PROCESS_ABI.json': '0d04d08422bae6c2cf88fec18cb77465b7e3234c69c294607a2f388397b9fe63',
    'third_party/termux/native/REBUILD.json': 'f2c9660006eefb353655994c92c6253c35533e038638e005b0aab11ab5732105',
    'third_party/termux/patches/src/com/termux/terminal/JNI.java': '7042e26081a991522110a730d1a7a1f59089dcd0388e91d93005755af5bd4ba3',
    'tools/verify_release_source.sh': 'bb1c4f1d3c95720bc44dff2f152317c3a7d2f83f65cc2de1d8021a72c3655f3b',
    'tools/verify_termux_provenance.py': '9a2b277a99bf0fffd8be1751f846cc91237c2e11ad13e2f07081fa3857792637',
}

def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for chunk in iter(lambda: f.read(1048576), b''):
            h.update(chunk)
    return h.hexdigest()

def put(name, value):
    (E / name).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def snapshot():
    result = {}
    for f in sorted(B.rglob('*')):
        if f.is_symlink():
            raise RuntimeError('Unexpected source symlink: ' + str(f))
        if f.is_file():
            result[f.relative_to(B).as_posix()] = {'sha256': sha(f), 'sizeBytes': f.stat().st_size,
                                                  'mode': oct(stat.S_IMODE(f.stat().st_mode))}
    return result

def main():
    if sys.argv[1:] != ['--primary-completed-authorized']:
        raise SystemExit('Wait for coordinator primary-completed notice before invocation.')
    assert not O.exists(), 'Refusing to reuse a native build root'
    assert not (E / 'commands.json').exists(), 'Refusing to overwrite prior run evidence'
    assert sha(E.parent / 'native-recipe-review.md') == REVIEW
    extraction = json.loads((E.parent / 'source-extraction-receipt.json').read_text())
    assert extraction['source_archive_sha256'] == ARCHIVE and extraction['all_manifest_entries_verified']
    primary_receipt_sha = '9f2cc87e3ae9751907412a4f93777a068586a89c78273ad7375601de642ce99a'
    primary_manifest_sha = 'c1d92eb41720b32c4f869417b08986dbe4d19c3f5ace2265646e0b89dfb78c32'
    assert sha(PE / 'native-build-static-receipt.json') == primary_receipt_sha
    assert sha(PE / 'evidence-manifest.json') == primary_manifest_sha
    primary_manifest = json.loads((PE / 'evidence-manifest.json').read_text())
    for f, entry in primary_manifest.items():
        path = R / f
        assert path.resolve().is_relative_to(R.resolve())
        assert sha(path) == entry['sha256'] and path.stat().st_size == entry['sizeBytes'], f
    put('primary-evidence-verification.json', {'primaryReceiptSha256': primary_receipt_sha,
        'primaryEvidenceManifestSha256': primary_manifest_sha, 'allManifestEntriesVerified': True,
        'entryCount': len(primary_manifest), 'coordinatorPrimaryCompletedNoticeReceived': True})
    prior = json.loads((PE / 'compilation-result.json').read_text())
    assert prior['status'] == 'BUILD_ONLY_DEVICE_UNVALIDATED' and prior['sourceTreeUnchanged']
    assert prior['sourceArchiveSha256'] == ARCHIVE
    for f, expected in PINS.items():
        assert sha(B / f) == expected, f
    contract = json.loads((B / 'third_party/termux/native/OWNED_PROCESS_ABI.json').read_text())
    assert contract['status'] == 'UNBUILT' and contract['executionEvidence'] is None and not contract['releaseCompatible']
    for f, expected in contract['sourceSha256ByPath'].items():
        assert sha(B / f) == expected, f
    assert sha(NDK / 'build/cmake/android.toolchain.cmake') == 'dbad92d9dcfea0d32b7c5e5f82f5072d878ded5d46a5d3f1f581ea108ca7fe89'
    assert sha(NDK / 'build/cmake/android-legacy.toolchain.cmake') == 'a225790c91d565ee556731748d3ce1f54f9162432820067a05d2d86259c2a9a7'
    assert re.search(r'^Pkg.Revision\s*=\s*28\.2\.13676358\s*$', (NDK / 'source.properties').read_text(), re.M)
    before = snapshot()
    assert before == json.loads((PE / 'source-tree-after.json').read_text()), 'Inputs changed after primary build'
    put('source-tree-before.json', before)
    O.mkdir(parents=True)
    (O / 'tmp').mkdir()
    (E / 'home').mkdir()
    env = {'PATH': f'{CM}:{LLVM}:/usr/bin:/bin', 'HOME': str(E / 'home'), 'TMPDIR': str(O / 'tmp'),
           'LANG': 'C', 'LC_ALL': 'C', 'TZ': 'UTC'}
    mappings = [(B, '/droide/source'), (O, '/droide/native'), (NDK, '/droide/toolchain/ndk/28.2.13676358')]
    flags = ' '.join(f'-{kind}={old}={new}' for old, new in mappings for kind in ('ffile-prefix-map', 'fdebug-prefix-map'))
    put('build-plan.json', {'startedUtc': now(), 'status': 'BUILD_ONLY_DEVICE_UNVALIDATED',
        'sourceArchiveSha256': ARCHIVE, 'sourceRoot': str(B), 'outputRoot': str(O), 'primaryRoot': str(P),
        'recipeReviewSha256': REVIEW, 'additionalCFlags': flags, 'environment': env, 'sourcePins': PINS,
        'sourceTreeMatchesPrimaryPostBuild': True, 'androidApi': 29, 'buildType': 'Debug',
        'abis': ABIS, 'targets': ['termux', 'droide_files'], 'androidOrJavaOutputExecution': False,
        'knownPriorDerivedChanges': ['Java-only AAR and patches/REBUILD.json were updated by another owner before both native runs.']})
    commands = []
    def run(label, argv):
        argv = list(map(str, argv))
        entry = {'label': label, 'argv': argv, 'cwd': str(O), 'environment': env, 'startedUtc': now()}
        result = subprocess.run(argv, cwd=O, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        (E / (label + '.stdout.log')).write_bytes(result.stdout)
        (E / (label + '.stderr.log')).write_bytes(result.stderr)
        entry.update(returnCode=result.returncode, finishedUtc=now(),
                     stdoutSha256=sha(E / (label + '.stdout.log')), stderrSha256=sha(E / (label + '.stderr.log')))
        commands.append(entry)
        put('commands.json', commands)
        if result.returncode:
            put('failure.json', {'status': 'BUILD_FAILED_NO_SOURCE_EDIT', 'failedCommand': entry})
            raise RuntimeError(label + ': ' + result.stderr.decode(errors='replace'))
        return result.stdout.decode(errors='replace')
    tools = {'cmake': CM / 'cmake', 'ninja': CM / 'ninja', 'clang': LLVM / 'clang',
             'ld.lld': LLVM / 'ld.lld', 'llvm-readelf': LLVM / 'llvm-readelf',
             'llvm-nm': LLVM / 'llvm-nm', 'llvm-dwarfdump': LLVM / 'llvm-dwarfdump',
             'javac': JDK / 'bin/javac', 'javap': JDK / 'bin/javap'}
    identities = {}
    hashed = {}
    for name, path in tools.items():
        resolved = path.resolve(strict=True)
        if resolved not in hashed:
            hashed[resolved] = sha(resolved)
        version = run('tool-' + name.replace('.', '-'), [path, '-version' if name in ('javac', 'javap') else '--version'])
        identities[name] = {'path': str(path), 'resolvedPath': str(resolved), 'sha256': hashed[resolved],
                            'sizeBytes': resolved.stat().st_size, 'versionStdout': version}
    put('tool-identities.json', identities)
    assert identities['cmake']['versionStdout'].startswith('cmake version 3.22.1-g37088a8\n')
    assert re.search(r'^Pkg.Revision\s*=\s*3\.22\.1\s*$', (SDK / 'cmake/3.22.1/source.properties').read_text(), re.M)
    assert identities['ninja']['versionStdout'].strip() == '1.10.2'
    for abi in ABIS:
        output = O / abi
        run(abi + '-configure', [CM / 'cmake', '-S', B / 'app/src/main/cpp', '-B', output, '-G', 'Ninja',
            f'-DCMAKE_MAKE_PROGRAM={CM / "ninja"}', f'-DCMAKE_TOOLCHAIN_FILE={NDK / "build/cmake/android.toolchain.cmake"}',
            f'-DANDROID_ABI={abi}', '-DANDROID_PLATFORM=android-29', '-DCMAKE_BUILD_TYPE=Debug',
            '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', f'-DCMAKE_C_FLAGS={flags}'])
        run(abi + '-build', [CM / 'cmake', '--build', output, '--target', 'termux', 'droide_files', '--parallel', '2', '--verbose'])
        run(abi + '-actual-commands', [CM / 'ninja', '-C', output, '-t', 'commands', 'termux', 'droide_files'])
        print(f'{abi}: two independently compiled Android libraries; neither executed', flush=True)
    (O / 'java-signatures').mkdir()
    run('compile-java-declarations', [JDK / 'bin/javac', '--release', '8', '-proc:none', '-d', O / 'java-signatures',
        B / 'third_party/termux/patches/src/com/termux/terminal/JNI.java'])
    run('inspect-java-declarations', [JDK / 'bin/javap', '-p', '-s', '-classpath', O / 'java-signatures', 'com.termux.terminal.JNI'])
    after = snapshot()
    put('source-tree-after.json', after)
    assert before == after, 'Source tree changed during independent build'
    comparisons = []
    for abi in ABIS:
        for name in NAMES:
            independent, primary = O / abi / name, P / abi / name
            expected = prior['libraries'][abi + '/' + name]['sha256']
            item = {'abi': abi, 'filename': name, 'independentSha256': sha(independent), 'primarySha256': sha(primary),
                    'independentBytes': independent.stat().st_size, 'primaryBytes': primary.stat().st_size,
                    'fullUnstrippedBytesEqual': independent.read_bytes() == primary.read_bytes()}
            assert item['primarySha256'] == expected, 'Primary output changed since receipt'
            comparisons.append(item)
    assert sha(PE / 'native-build-static-receipt.json') == primary_receipt_sha
    assert sha(PE / 'evidence-manifest.json') == primary_manifest_sha
    put('reproduction-comparison.json', {'status': 'BUILD_ONLY_DEVICE_UNVALIDATED',
        'allEightFullUnstrippedFilesByteIdentical': all(x['fullUnstrippedBytesEqual'] for x in comparisons),
        'sourceTreeUnchanged': True, 'sourceTreeMatchesPrimary': True, 'libraries': comparisons,
        'sourceArchiveSha256': ARCHIVE, 'executionEvidence': None, 'releaseCompatible': False,
        'scope': 'Two clean-root builds in the same toolchain and host environment only.'})
    if not all(x['fullUnstrippedBytesEqual'] for x in comparisons):
        raise RuntimeError('Byte mismatch preserved for diagnosis; no stripping or source/flag changes permitted')
    print('PASS: all eight full unstripped library files byte-identical to primary; source unchanged.', flush=True)

if __name__ == '__main__':
    main()
