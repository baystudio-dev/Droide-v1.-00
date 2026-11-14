#!/usr/bin/env python3
"""Fail-closed R63 final APK admission until exact app-side DEX binding exists.

The separately usable engine gate binds the admitted R63 AAR and fresh R8 output.
It does not prove the app callback bridge, receipt reducer, viewport and retry
wiring were packaged. A stale app with the current engine could otherwise pass.
Therefore this release has NO successful final-APK/export path, bypass flag,
caller-supplied app hash, receipt override or fallback to the R46 final gate.
"""
from __future__ import annotations
import argparse
from pathlib import Path

from verify_pty_dex_binding import BindingError, require
from verify_termux_pty_derivative import no_symlinks

EXPORT_NAME = 'Droide-private-r63-resize-derivative-device-unvalidated.apk'
APP_BINDING_BLOCKER = (
    'R63 final APK publication is blocked: exact app-side binding for the callback bridge, '
    'receipt state, viewport and retry wiring is unavailable. AAR/engine-only DEX binding '
    'cannot establish APK completeness. No APK has been admitted or exported.'
)


def publish_verified_apk(root, apk, report, build_tools, java_home, keystore, r8_jar, android_jar):
    # Keep the fixed Gradle entry point, but no success-capable execution path.
    # Clear legacy outputs before the explicit blocker so a previous file/report
    # cannot be mistaken for this run's admission. Never remove an input on alias.
    apk, report = Path(apk).absolute(), Path(report).absolute()
    no_symlinks(report)
    report.parent.mkdir(parents=True, exist_ok=True)
    export = report.parent / EXPORT_NAME
    no_symlinks(export)
    require(apk != export and report != export and apk != report, 'input/output paths overlap')
    try:
        report.unlink(missing_ok=True)
        export.unlink(missing_ok=True)
        raise BindingError(APP_BINDING_BLOCKER)
    finally:
        report.unlink(missing_ok=True)
        export.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'apk', 'report', 'build-tools', 'java-home', 'debug-keystore', 'r8-jar', 'android-jar'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        publish_verified_apk(args.root, args.apk, args.report, args.build_tools, args.java_home,
                             args.debug_keystore, args.r8_jar, args.android_jar)
    except (OSError, ValueError, KeyError, BindingError) as error:
        raise SystemExit('PRIVATE_R63_RESIZE_APK_BLOCKED: ' + str(error)) from error


if __name__ == '__main__':
    main()
