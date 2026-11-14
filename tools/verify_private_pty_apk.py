#!/usr/bin/env python3
"""Publish only one fully checked, isolated private R46 APK; no Android execution.

Historical native, signature, alignment, identity and JNI checks are reused intact.
Fresh candidate DEX body binding is mandatory. Every check and the published file
must identify the same snapshot bytes. No stale report/export survives failure.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import tempfile

from build_pty_dex_reference import regular_bytes
from verify_private_debug_native import verify_apk
from verify_pty_dex_binding import verify_apk_binding, BindingError, require
from verify_termux_pty_derivative import verify_derivative_inputs, verify_jdk, no_symlinks

EXPORT_NAME = 'Droide-private-pty-derivative-device-unvalidated.apk'
SDK_TOOL_PINS = {'aapt2': '1a6a396b9cd071f7040071fdd108718cb98c3c9f4960044f373b288993d19eb7', 'apksigner': 'b47549e373b895ce6ca620d0c7887e674d9615ffa837a86ac601dcfd04adb0f0', 'lib/apksigner.jar': '3716d9311e55d2b0918a2fd9d54ba9e406c5f6abeea700b287f11259bc163dec', 'zipalign': 'c5f559e946de5a9e7d58792181db20383b228877812136bc469d97ae00a43b0a', 'lib64/libc++.so': '96a19cd2154a4423a61f8444e5d3148a608fa3033bc1a22285d19855a950d05a'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_sdk_tools(build_tools):
    identity = {}
    for name, wanted in SDK_TOOL_PINS.items():
        actual = sha(regular_bytes(build_tools / name))
        require(actual == wanted, 'changed or unrecognized SDK inspection tool: ' + name)
        identity[name] = actual
    # The SDK launcher prefers this sibling over lib/apksigner.jar if it exists.
    require(not (build_tools / 'apksigner.jar').exists(), 'unreviewed apksigner shadow JAR')
    require(bool(identity), 'missing SDK inspection tool pins')
    return identity


@contextmanager
def clean_environment(java_home, home):
    saved = dict(os.environ)
    os.environ.clear()
    os.environ.update({'PATH': str(java_home / 'bin') + ':/usr/bin:/bin',
                       'JAVA_HOME': str(java_home), 'HOME': str(home),
                       'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'})
    try:
        yield
    finally:
        os.environ.clear()
        os.environ.update(saved)


def publish_verified_apk(root, apk, report, build_tools, java_home, keystore, r8_jar, android_jar):
    root, apk, report, build_tools, java_home, keystore, r8_jar, android_jar = [
        Path(p).absolute() for p in
        (root, apk, report, build_tools, java_home, keystore, r8_jar, android_jar)]
    no_symlinks(report)
    report.parent.mkdir(parents=True, exist_ok=True)
    export = report.parent / EXPORT_NAME
    no_symlinks(export)
    require(apk != export and report != export and apk != report, 'input/output paths overlap')
    report.unlink(missing_ok=True)
    export.unlink(missing_ok=True)
    succeeded = False
    try:
        for path in (root, apk, build_tools, java_home, keystore, r8_jar, android_jar):
            no_symlinks(path)
        original = regular_bytes(apk)
        expected = sha(original)
        jdk = verify_jdk(java_home)
        sdk = verify_sdk_tools(build_tools)
        with tempfile.TemporaryDirectory(prefix='.r46-private-verify-', dir=report.parent) as directory:
            work = Path(directory)
            snapshot = work / 'candidate.apk'
            snapshot.write_bytes(original)
            require(regular_bytes(apk) == original, 'input APK changed while snapshotting')
            with clean_environment(java_home, work):
                admission = verify_derivative_inputs(root)
                native = verify_apk(root, snapshot, admission['baselineReceipt'], build_tools, java_home, keystore)
                require(native['apkSha256'] == expected and sha(regular_bytes(snapshot)) == expected,
                        'native/signature checks identified different APK bytes')
                binding = verify_apk_binding(root, snapshot, d8_jar_path=r8_jar,
                                            android_jar_path=android_jar, java_home=java_home)
                require(binding['apkSha256'] == native['apkSha256'] == expected,
                        'native and DEX checks identified different APKs')
                require(verify_derivative_inputs(root) == admission, 'derivative changed before publication')
                require(verify_jdk(java_home) == jdk and verify_sdk_tools(build_tools) == sdk,
                        'inspection toolchain changed before publication')
            require(regular_bytes(apk) == original and sha(regular_bytes(snapshot)) == expected,
                    'APK changed before publication')
            result = dict(native, schemaVersion=1, kind='R46_PRIVATE_PTY_DERIVATIVE_APK',
                          derivativeAarSha256=admission['output']['sha256'],
                          dexBinding=binding, inspectionSdkSha256=sdk,
                          executionEvidence=None, runtimeEvidence=None,
                          releaseCompatible=False, deviceValidated=False,
                          verifiedApkFilename=EXPORT_NAME)
            # Export the already inspected snapshot itself. The success report is last.
            snapshot.replace(export)
            require(sha(regular_bytes(export)) == expected, 'published APK copy mismatch')
            report_tmp = work / 'report.json'
            report_tmp.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
            require(sha(regular_bytes(export)) == expected, 'published APK changed before report')
            report_tmp.replace(report)
            succeeded = True
            return result
    finally:
        if not succeeded:
            report.unlink(missing_ok=True)
            export.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'apk', 'report', 'build-tools', 'java-home', 'debug-keystore', 'r8-jar', 'android-jar'):
        parser.add_argument('--' + name, type=Path, required=True)
    a = parser.parse_args()
    try:
        result = publish_verified_apk(a.root, a.apk, a.report, a.build_tools, a.java_home,
                                      a.debug_keystore, a.r8_jar, a.android_jar)
    except (OSError, ValueError, KeyError, BindingError) as error:
        raise SystemExit('PRIVATE_PTY_APK_FAILED: ' + str(error)) from error
    print('PRIVATE_PTY_APK_VERIFIED_DEVICE_UNVALIDATED ' + result['apkSha256'])


if __name__ == '__main__':
    main()
