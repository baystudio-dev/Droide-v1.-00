#!/usr/bin/env python3
"""Verify a source-built debug APK without granting release or device certification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

from current_native_jni_guard import required_methods, verify_library
from verify_distribution_assets import verify_assets
from verify_distribution_native import verify_native

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(root, build, engine, sdk, fdroid_candidate=False, native_source=None):
    require(fdroid_candidate == (native_source is not None), "Native source build must match distribution mode")
    apk = build / "outputs/apk/debug/app-debug.apk"
    require(apk.is_file(), "Debug APK is missing")
    receipt = json.loads((engine / "receipt.json").read_text())
    require(receipt["status"] == "BUILD_ONLY_DEVICE_UNVALIDATED", "Wrong engine receipt")
    if fdroid_candidate:
        require(receipt.get("fullSourceRebuild") is True and receipt.get("binaryBaseRetained") is False,
                "F-Droid terminal must be compiled entirely from source")
        require(sha((engine / "terminal-view.aar").read_bytes()) == receipt["outputs"]["terminal-view.aar"],
                "Full-source terminal view differs from its receipt")
    require(sha((engine / "terminal-engine.aar").read_bytes()) == receipt["inspection"]["aarSha256"],
            "Engine AAR differs from its verified build")
    for path, digest in receipt["sourceInputs"].items():
        require(sha((root / path).read_bytes()) == digest, "Engine input changed: " + path)
    methods = required_methods((root / "app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt").read_text())
    c_source = root / "third_party/termux/native/termux.c"
    table = c_source.read_text().split("static const JNINativeMethod owned_methods[] = {", 1)[1].split("};", 1)[0]
    registered = [{"name": name, "descriptor": descriptor} for name, descriptor in
                  re.findall(r'\{\s*"([A-Za-z0-9_]+)"\s*,\s*"([^"\n]+)"\s*,', table)]
    recipe = json.loads((root / "tools/terminal-engine-v2.json").read_text())
    require(registered == recipe["jniReplacement"]["methods"], "Native/Java terminal registration differs")
    native = {}
    with zipfile.ZipFile(apk) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries")
        require(archive.testzip() is None, "Corrupt APK entry")
        asset_count = verify_assets(root, archive, fdroid_candidate, native_source)
        for abi in ABIS:
            for library in ("libtermux.so", "libdroide_files.so"):
                name = f"lib/{abi}/{library}"
                packaged = archive.read(name)
                compiled = [p for p in (build / "intermediates/cxx").rglob(library) if p.parent.name == abi]
                require(compiled and {sha(p.read_bytes()) for p in compiled} == {sha(packaged)},
                        "Packaged library differs from current CMake outputs: " + name)
                if library == "libdroide_files.so":
                    verify_library(packaged, abi, methods)
                native[name] = sha(packaged)
        native.update(verify_native(root, build, archive, native_source))
    tools = sdk / "build-tools/36.0.0"
    commands = [
        [str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)],
        [str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)],
    ]
    checks = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
        require(result.returncode == 0, "APK check failed: " + result.stderr + result.stdout)
        checks.append({"argv": command, "stdout": result.stdout, "stderr": result.stderr})
    result = {"status": "BUILT_DEVICE_UNVALIDATED", "apk": str(apk), "apkSha256": sha(apk.read_bytes()),
              "apkBytes": apk.stat().st_size, "engineAarSha256": receipt["inspection"]["aarSha256"],
              "nativeLibraries": native, "checks": checks,
              "nativeSources": {str(p.relative_to(root)): sha(p.read_bytes()) for p in
                                (c_source, root / "app/src/main/cpp/droide_files.c", root / "app/src/main/cpp/CMakeLists.txt")},
              "deviceValidated": False, "releaseCompatible": False,
              "fdroidCandidate": fdroid_candidate, "fdroidAccepted": False, "packagedAssetsVerified": asset_count}
    output = build / "outputs/source-debug-verification.json"
    output.write_text(json.dumps(result, indent=2) + "\n")
    print("SOURCE_DEBUG_APK_VERIFIED", result["apkSha256"], str(apk))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "build-dir", "engine", "sdk"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--fdroid-candidate", action="store_true")
    parser.add_argument("--native-source", type=Path)
    args = parser.parse_args()
    verify(args.root.resolve(), args.build_dir.resolve(), args.engine.resolve(), args.sdk.resolve(), args.fdroid_candidate, args.native_source)
