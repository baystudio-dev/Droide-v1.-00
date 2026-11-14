#!/usr/bin/env python3
"""Private build-only admission and exact final APK checks. Never runs Android code."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import shutil
import struct
import subprocess
from pathlib import Path
import zipfile

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
LIBRARIES = ('libtermux.so', 'libdroide_files.so')
BASE = 'third_party/termux/native/private-debug'
CONTRACT = 'third_party/termux/native/OWNED_PROCESS_ABI.json'
AAR = 'app/libs/terminal-emulator-v0.118.0-droide-input.aar'
JAVA_RECEIPT = 'third_party/termux/patches/REBUILD.json'
STATUS = 'BUILT_DEVICE_UNVALIDATED'
R40_ARCHIVE = 'e767a89ace01569e1e0412b2bbd03de171194caa85c3989b770d4bfe9438d317'
EXPECTED_LIBS = {f'{abi}/{name}' for abi in ABIS for name in LIBRARIES}
EXPECTED_SOURCES = {
    CONTRACT, 'third_party/termux/native/REBUILD.json',
    'third_party/termux/native/termux.c', 'app/src/main/cpp/CMakeLists.txt',
    'app/src/main/cpp/droide_files.c', JAVA_RECEIPT,
    *('third_party/termux/patches/src/com/termux/terminal/' + name + '.java'
      for name in ('JNI', 'TerminalSession', 'TerminalProcessOwner', 'TerminalPtyIo', 'ByteQueue')),
}
EVIDENCE_FILES = {
    'primary/build_native.py', 'primary/build-plan.json', 'primary/commands.json',
    'primary/tool-identities.json', 'primary/ndk-cmake-inputs.json',
    'primary/static-source-inspection.json', 'primary/native-build-static-receipt.json',
    'primary/compiled-java-table-agreement.json',
    'independent/reproduce_native.py', 'independent/build-plan.json',
    'independent/commands.json', 'independent/tool-identities.json',
    'independent/reproduction-comparison.json', 'independent/independent-native-static-receipt.json',
    'independent/java-c-contract-table-comparison.json',
    'independent/command-equivalence-and-security.json', 'independent/final-input-verification.json',
    *(f'primary/{abi}-actual-commands.log' for abi in ABIS),
    *(f'independent/{abi}-actual-commands.stdout.log' for abi in ABIS),
    *(f'primary/{abi}-compile_commands.json' for abi in ABIS),
    *(f'independent/{abi}-compile_commands.json' for abi in ABIS),
}


def fail(message: str):
    raise SystemExit('PRIVATE_DEBUG_FAILED: ' + message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError) as error:
        fail(f'invalid JSON {path}: {error}')


def checked_file(base: Path, relative: str, wanted: str) -> bytes:
    path = base / relative
    if not re.fullmatch('[a-f0-9]{64}', str(wanted)):
        fail('invalid SHA-256 pin: ' + relative)
    if Path(relative).is_absolute() or '..' in Path(relative).parts:
        fail('non-local receipt path: ' + relative)
    if any(parent.is_symlink() for parent in [path, *path.parents] if parent != base.parent):
        fail('symlinked provenance input: ' + relative)
    if not path.is_file():
        fail('missing provenance input: ' + relative)
    data = path.read_bytes()
    if digest(data) != wanted:
        fail('SHA-256 mismatch: ' + relative)
    return data


def keyed_libraries(items: list, label: str) -> dict:
    result = {item['abi'] + '/' + item['filename']: item for item in items}
    if len(items) != 8 or set(result) != EXPECTED_LIBS:
        fail(label + ' library/ABI set changed')
    return result


def verify_private_inputs(root: Path) -> dict:
    root = root.resolve()
    base = root / BASE
    receipt = read_json(base / 'receipt.json')
    if (receipt.get('schemaVersion') != 1 or receipt.get('status') != STATUS or
            receipt.get('artifactKind') != 'PRIVATE_DEBUG_PREBUILT_NATIVE_ADMISSION' or
            receipt.get('releaseCompatible') is not False or receipt.get('executionEvidence', 'absent') is not None or
            receipt.get('deviceValidated') is not False or receipt.get('sourceArchiveSha256') != R40_ARCHIVE):
        fail('receipt is not explicitly private, build-only and device-unvalidated')
    if set(receipt.get('sourceSha256ByPath', {})) != EXPECTED_SOURCES:
        fail('native/JNI/source receipt coverage changed')
    for path, sha in receipt['sourceSha256ByPath'].items():
        checked_file(root, path, sha)
    contract = read_json(root / CONTRACT)
    if (contract.get('status') != 'UNBUILT' or contract.get('artifactKind') != 'SOURCE_ABI_CONTRACT_ONLY' or
            contract.get('releaseCompatible') is not False or contract.get('executionEvidence', 'absent') is not None):
        fail('private evidence must not promote the release source contract')
    if set(receipt.get('evidenceSha256ByPath', {})) != EVIDENCE_FILES:
        fail('primary/independent evidence coverage changed')
    evidence = base / 'evidence'
    for path, sha in receipt['evidenceSha256ByPath'].items():
        checked_file(evidence, path, sha)
    actual_evidence = {str(p.relative_to(evidence)) for p in evidence.rglob('*') if p.is_file()}
    if actual_evidence != EVIDENCE_FILES:
        fail('unexpected or missing evidence file')
    for stage in ('primary', 'independent'):
        if set(receipt.get('nativeSha256ByBuild', {}).get(stage, {})) != EXPECTED_LIBS:
            fail(stage + ' native receipt ABI set changed')
        actual = {str(p.relative_to(base / stage)) for p in (base / stage).rglob('*') if p.is_file()}
        if actual != EXPECTED_LIBS:
            fail(stage + ' native directory must contain exactly eight owned libraries')
        for name, sha in receipt['nativeSha256ByBuild'][stage].items():
            data = checked_file(base / stage, name, sha)
            if data[:4] != b'\x7fELF':
                fail('native input is not ELF: ' + name)
    if receipt['nativeSha256ByBuild']['primary'] != receipt['nativeSha256ByBuild']['independent']:
        fail('independent full native bytes do not match primary')
    primary = read_json(evidence / 'primary/native-build-static-receipt.json')
    independent = read_json(evidence / 'independent/independent-native-static-receipt.json')
    for report in (primary, independent):
        if (report.get('status') != 'BUILD_ONLY_DEVICE_UNVALIDATED' or report.get('releaseCompatible') is not False or
                report.get('executionEvidence', 'absent') is not None or report.get('sourceContractModified') is not False or
                report.get('sourceTreeUnchanged') is not True or report.get('sourceArchiveSha256') != R40_ARCHIVE or
                report.get('ndkVersion') != '28.2.13676358' or report.get('buildType') != 'Debug' or
                report.get('androidApi') != 29):
            fail('native build evidence status/recipe changed')
    if primary.get('cmakeVersion') != '3.22.1' or independent.get('cmakePackageVersion') != '3.22.1':
        fail('native CMake version changed')
    p_libs = keyed_libraries(primary['libraries'], 'primary')
    i_libs = keyed_libraries(independent['libraries'], 'independent')
    methods = contract['registeredNativeMethods']
    for name in sorted(EXPECTED_LIBS):
        for measured in (p_libs[name], i_libs[name]):
            if measured['sha256'] != receipt['nativeSha256ByBuild']['primary'][name]:
                fail('compiled static evidence artifact mismatch: ' + name)
            if measured.get('executionEvidence', 'absent') is not None:
                fail('unexpected runtime evidence: ' + name)
        if name.endswith('/libtermux.so'):
            for measured, key in ((p_libs[name], 'compiledNativeMethodTable'), (i_libs[name], 'compiledMethodTable')):
                if ([{'name': item['name'], 'descriptor': item['descriptor']} for item in measured[key]] != methods or
                        measured.get('definedDynamicExports') != ['JNI_OnLoad'] or measured.get('soname') != 'libtermux.so'):
                    fail('compiled owned JNI method/export table mismatch: ' + name)
    static = read_json(evidence / 'primary/static-source-inspection.json')
    plan_primary = read_json(evidence / 'primary/build-plan.json')
    plan_independent = read_json(evidence / 'independent/build-plan.json')
    if plan_primary['buildRoot'] == plan_independent['outputRoot']:
        fail('independent native build did not use a separate output root')
    independent_required = {CONTRACT, 'third_party/termux/native/REBUILD.json',
                            'third_party/termux/native/termux.c', 'app/src/main/cpp/CMakeLists.txt',
                            'app/src/main/cpp/droide_files.c',
                            'third_party/termux/patches/src/com/termux/terminal/JNI.java'}
    if not independent_required.issubset(plan_independent.get('sourcePins', {})):
        fail('independent native source/recipe coverage incomplete')
    for name, sha in receipt['sourceSha256ByPath'].items():
        if static['sourceInputs'].get(name, {}).get('sha256') != sha:
            fail('current source differs from the actually compiled native inputs: ' + name)
        if name in independent_required and plan_independent['sourcePins'][name] != sha:
            fail('current source differs from the independently compiled native inputs: ' + name)
    comparison = read_json(evidence / 'independent/reproduction-comparison.json')
    if (comparison.get('allEightFullUnstrippedFilesByteIdentical') is not True or
            comparison.get('executionEvidence', 'absent') is not None or comparison.get('releaseCompatible') is not False):
        fail('independent reproduction result is missing or no longer build-only')
    for name, item in keyed_libraries(comparison['libraries'], 'comparison').items():
        expected = receipt['nativeSha256ByBuild']['primary'][name]
        if item.get('primarySha256') != expected or item.get('independentSha256') != expected or item.get('fullUnstrippedBytesEqual') is not True:
            fail('real reproduction artifact hashes disagree: ' + name)
    for stage, plan in (('primary', plan_primary), ('independent', plan_independent)):
        commands = read_json(evidence / stage / 'commands.json')
        if not commands or any(command.get('returnCode') != 0 for command in commands):
            fail(stage + ' build command failed or missing')
        for abi in ABIS:
            expected_out = str(Path(plan['buildRoot'] if stage == 'primary' else plan['outputRoot']) / abi)
            configure = [c['argv'] for c in commands if '-B' in c['argv'] and expected_out in c['argv']]
            builds = [c['argv'] for c in commands if '--build' in c['argv'] and expected_out in c['argv']]
            if len(configure) != 1 or len(builds) != 1 or not {'-DCMAKE_BUILD_TYPE=Debug', '-DANDROID_PLATFORM=android-29', '-DANDROID_ABI=' + abi}.issubset(configure[0]):
                fail(stage + ' real configure/build command evidence missing: ' + abi)
            if not {'termux', 'droide_files'}.issubset(builds[0]):
                fail(stage + ' native build targets changed')
    agreement = read_json(evidence / 'primary/compiled-java-table-agreement.json')
    aar_bytes = checked_file(root, AAR, receipt.get('javaAarSha256', ''))
    with zipfile.ZipFile(io.BytesIO(aar_bytes)) as aar:
        class_jar = aar.read('classes.jar')
        with zipfile.ZipFile(io.BytesIO(class_jar)) as classes:
            jni_class = classes.read('com/termux/terminal/JNI.class')
    if (agreement.get('aarSha256') != digest(aar_bytes) or agreement.get('classesJarSha256') != digest(class_jar) or
            agreement.get('jniClassSha256') != digest(jni_class) or agreement['class']['nativeStaticMethods'] != methods or
            agreement.get('classInitializationOrJniRegistrationExecuted') is not False):
        fail('rebuilt AAR/compiled JNI class differs from measured native agreement')
    # Existing unrelated runtime executables are retained and compared after packaging too.
    actual_runtime = {str(p.relative_to(root)) for p in (root / 'app/src/main/jniLibs').rglob('*') if p.is_file()}
    if set(receipt.get('existingRuntimeSha256ByPath', {})) != actual_runtime or len(actual_runtime) != 4:
        fail('existing main runtime-library set changed')
    for name, sha in receipt['existingRuntimeSha256ByPath'].items():
        checked_file(root, name, sha)
    return receipt


def dex_jni_methods(data: bytes) -> list | None:
    """Read native method declarations from a standard DEX without loading code."""
    if len(data) < 112 or data[:4] != b'dex\n' or data[7:8] != b'\0':
        fail('invalid packaged DEX')
    def u32(offset):
        if offset < 0 or offset + 4 > len(data): fail('truncated DEX')
        return struct.unpack_from('<I', data, offset)[0]
    def uleb(offset):
        value = 0
        for shift in range(0, 35, 7):
            if offset >= len(data): fail('truncated DEX variable integer')
            byte = data[offset]; offset += 1; value |= (byte & 127) << shift
            if not byte & 128: return value, offset
        fail('oversized DEX variable integer')
    if u32(32) != len(data) or u32(40) != 0x12345678:
        fail('DEX header mismatch')
    strings = []
    for i in range(u32(56)):
        offset = u32(u32(60) + 4 * i)
        _, offset = uleb(offset)
        end = data.find(b'\0', offset)
        if end < 0: fail('unterminated DEX string')
        strings.append(data[offset:end])
    types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]
    target = b'Lcom/termux/terminal/JNI;'
    if target not in types: return None
    target_id = types.index(target)
    class_defs = [u32(100) + 32 * i for i in range(u32(96)) if u32(u32(100) + 32 * i) == target_id]
    if not class_defs: return None
    if len(class_defs) != 1: fail('duplicate packaged JNI class definition')
    offset = u32(class_defs[0] + 24)
    counts = []
    for _ in range(4):
        value, offset = uleb(offset); counts.append(value)
    for _ in range(counts[0] + counts[1]):
        _, offset = uleb(offset); _, offset = uleb(offset)
    result = []
    for count in counts[2:]:
        method_id = 0
        for _ in range(count):
            delta, offset = uleb(offset); method_id += delta
            access, offset = uleb(offset); code, offset = uleb(offset)
            if access & 0x100:
                if not access & 0x8 or code != 0: fail('JNI native method is not a static native declaration')
                entry = u32(92) + method_id * 8
                class_id, proto_id, name_id = struct.unpack_from('<HHI', data, entry)
                if class_id != target_id: fail('JNI method has the wrong declaring class')
                proto = u32(76) + proto_id * 12
                params = u32(proto + 8)
                args = b'' if params == 0 else b''.join(types[struct.unpack_from('<H', data, params + 4 + 2 * i)[0]] for i in range(u32(params)))
                result.append({'name': strings[name_id].decode('ascii'), 'descriptor': (b'(' + args + b')' + types[u32(proto + 4)]).decode('ascii')})
    return result


def verify_apk_payload(root: Path, apk: Path, receipt: dict) -> dict:
    expected = {'lib/' + path: sha for path, sha in receipt['nativeSha256ByBuild']['primary'].items()}
    expected_runtime = {'lib/' + path.removeprefix('app/src/main/jniLibs/'): sha for path, sha in receipt['existingRuntimeSha256ByPath'].items()}
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            fail('duplicate APK ZIP entry')
        owned = {name for name in names if name.startswith('lib/') and Path(name).name in LIBRARIES}
        if owned != set(expected):
            fail('APK owned native library/ABI set changed')
        for name, sha in (expected | expected_runtime).items():
            if name not in names or digest(archive.read(name)) != sha:
                fail('APK does not contain the exact verified native bytes: ' + name)
        # These two AndroidX providers come from the strictly verified Gradle AARs.
        # They are recorded separately; owned native byte identity does not cover them.
        external = {f'lib/{abi}/{lib}' for abi in ABIS for lib in
                    ('libdatastore_shared_counter.so', 'libandroidx.graphics.path.so')}
        if {name for name in names if name.startswith('lib/') and not name.endswith('/')} != set(expected) | set(expected_runtime) | external:
            fail('APK contains an unexpected native provider/library')
        found = []
        for name in names:
            if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
                methods = dex_jni_methods(archive.read(name))
                if methods is not None: found.append(methods)
        methods = read_json(root / CONTRACT)['registeredNativeMethods']
        key = lambda entry: (entry['name'], entry['descriptor'])
        if len(found) != 1 or sorted(found[0], key=key) != sorted(methods, key=key):
            fail('packaged DEX JNI declarations do not match all twelve compiled native methods')
        return {'nativeSha256ByApkPath': expected | expected_runtime,
                'externalDependencyNativeSha256ByApkPath': {name: digest(archive.read(name)) for name in sorted(external)},
                'jniNativeMethods': found[0],
                'compressionByNativePath': {name: archive.getinfo(name).compress_type for name in set(expected) | set(expected_runtime) | external}}


def verify_apk(root: Path, apk: Path, receipt: dict, build_tools: Path, java_home: Path, keystore: Path) -> dict:
    payload = verify_apk_payload(root, apk, receipt)
    def run(args: list, binary=False):
        completed = subprocess.run([str(arg) for arg in args], capture_output=True, check=False, timeout=120)
        if completed.returncode != 0:
            fail('APK inspection command failed: ' + str(args[0]) + '\n' + completed.stderr.decode(errors='replace'))
        return completed.stdout if binary else completed.stdout.decode('utf-8')
    badging = run([build_tools / 'aapt2', 'dump', 'badging', apk])
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != 'com.baystudio.droide' or not package[3].endswith('-private-device-unvalidated') or '\napplication-debuggable\n' not in '\n' + badging:
        fail('APK package/version/debuggable identity is not private debug')
    if "application-label:'Droide Debug (Unvalidated)'" not in badging:
        fail('APK does not have the explicit unvalidated application label')
    signing = run([build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk])
    signer_hashes = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]+)$', signing, re.M)
    # Android's generated debug keystore uses the documented public default password.
    debug_cert = run([java_home / 'bin/keytool', '-exportcert', '-keystore', keystore,
                      '-alias', 'androiddebugkey', '-storepass', 'android'], binary=True)
    if signer_hashes != [digest(debug_cert)]:
        fail('APK was not signed by the selected Android debug keystore')
    alignment = run([build_tools / 'zipalign', '-c', '-P', '16', '-v', '4', apk])
    return dict(payload, apkSha256=digest(apk.read_bytes()), packageName=package[1],
                versionCode=package[2], versionName=package[3], debuggable=True,
                signerCertificateSha256=signer_hashes[0], apkSignatureVerified=True,
                zipAlignmentVerified=True, zipalignOutput=alignment,
                runtimeEvidence=None, status=STATUS, releaseCompatible=False, deviceValidated=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--java-home', type=Path)
    parser.add_argument('--debug-keystore', type=Path)
    args = parser.parse_args()
    if args.report:
        args.report.unlink(missing_ok=True)  # Never retain a stale success after a failed rerun.
    try:
        receipt = verify_private_inputs(args.root)
        if args.apk:
            if not all((args.report, args.build_tools, args.java_home, args.debug_keystore)):
                fail('final APK verification requires report, build-tools, java-home and debug-keystore')
            # Re-run all original upstream and Java scope checks after APK packaging too.
            subprocess.run(['python3', '-B', str(args.root / 'tools/verify_termux_provenance.py'),
                            '--private-debug-device-unvalidated'], check=True, timeout=120)
            result = verify_apk(args.root, args.apk, receipt, args.build_tools, args.java_home, args.debug_keystore)
            result.update(schemaVersion=1, executionEvidence=None,
                          admissionReceiptSha256=digest((args.root / BASE / 'receipt.json').read_bytes()))
            args.report.parent.mkdir(parents=True, exist_ok=True)
            named_apk = args.report.parent / 'Droide-private-debug-device-unvalidated.apk'
            shutil.copyfile(args.apk, named_apk)
            if digest(named_apk.read_bytes()) != result['apkSha256']:
                fail('named private APK copy mismatch')
            result['verifiedApkFilename'] = named_apk.name
            args.report.write_text(json.dumps(result, indent=2) + '\n')
            print('PRIVATE_DEBUG_APK_VERIFIED_DEVICE_UNVALIDATED ' + str(named_apk))
        else:
            print('PRIVATE_DEBUG_NATIVE_INPUTS_OK device_validated=false release_compatible=false')
    except (OSError, ValueError, KeyError, IndexError, struct.error, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        fail(str(error))


if __name__ == '__main__':
    main()
