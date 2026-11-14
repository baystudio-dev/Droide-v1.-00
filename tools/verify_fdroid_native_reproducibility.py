#!/usr/bin/env python3
"""Rebuild the JNI libraries in different directories and compare actual release bytes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile


def verify(root, sdk, native):
    cmake = sdk / "cmake/3.22.1/bin/cmake"
    ninja = sdk / "cmake/3.22.1/bin/ninja"
    ndk = sdk / "ndk/28.2.13676358"
    products = []
    with tempfile.TemporaryDirectory(prefix="droide-native-repro-") as directory:
        for name in ("short", "second/checkout-with-a-different-length"):
            source = Path(directory) / name
            for relative in ("app/src/main/cpp", "third_party/termux/native"):
                shutil.copytree(root / relative, source / relative)
            flags = (f"-ffile-prefix-map={source}=. "
                     f"-ffile-prefix-map={sdk}=/android-sdk -fdebug-compilation-dir=.")
            output = {}
            for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
                build = source / "native-build" / abi
                commands = (
                    [cmake, "-S", source / "app/src/main/cpp", "-B", build, "-G", "Ninja",
                     f"-DCMAKE_MAKE_PROGRAM={ninja}",
                     f"-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake",
                     f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-29",
                     "-DCMAKE_BUILD_TYPE=RelWithDebInfo", f"-DCMAKE_C_FLAGS={flags}"],
                    [cmake, "--build", build, "--parallel", "1"],
                )
                for command in commands:
                    result = subprocess.run(list(map(str, command)), capture_output=True,
                                            text=True, timeout=180)
                    if result.returncode:
                        raise ValueError(result.stdout + result.stderr)
                for library in ("libtermux.so", "libdroide_files.so"):
                    output[f"{abi}/{library}"] = (build / library).read_bytes()
            products.append(output)
        hashes = {}
        for name, data in products[0].items():
            if data != products[1][name] or data != (native / name).read_bytes():
                raise ValueError("JNI library is not reproducible across build directories: " + name)
            hashes[name] = hashlib.sha256(data).hexdigest()
    return {"status": "VERIFIED", "independentBuilds": 2, "matchesReleaseLibraries": True,
            "nativeLibraries": hashes, "scope": "JNI libraries; full APK comparison is separate"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "sdk", "native", "report"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.root.resolve(), args.sdk.resolve(), args.native.resolve())
    args.report.write_text(json.dumps(result, indent=2) + "\n")
    print("FDROID_JNI_REPRODUCIBILITY_VERIFIED", len(result["nativeLibraries"]))
