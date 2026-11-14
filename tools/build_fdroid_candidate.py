#!/usr/bin/env python3
"""Build an unsigned source-only candidate using externally provisioned FLOSS toolchains."""
import argparse
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args()
    root, sdk, java = args.root.resolve(), args.sdk.resolve(), args.java_home.resolve()
    products = args.dependencies.resolve()
    environment = os.environ.copy()
    environment.update(JAVA_HOME=str(java), ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    # Distribution candidates never consume private upstream signing credentials.
    for name in ("DROIDE_KEYSTORE_PATH", "DROIDE_KEYSTORE_PASSWORD", "DROIDE_KEY_ALIAS", "DROIDE_KEY_PASSWORD"):
        if environment.get(name):
            raise ValueError("Remove upstream signing credentials before the F-Droid candidate build")
    subprocess.run(["python3", "-B", root / "tools/build_linux_engine_sources.py", "--root", root,
                    "--ndk", sdk / "ndk/28.2.13676358", "--output", products / "linux"],
                   cwd=root, env=environment, check=True)
    variant = "Debug" if args.debug else "Release"
    command = [args.gradle.resolve(), "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--max-workers=2",
        "--dependency-verification=strict", "-Dorg.gradle.jvmargs=-Xmx3200m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8",
        "-Pkotlin.compiler.execution.strategy=in-process", "-Pandroid.builder.sdkDownload=false",
        "-PDROIDE_FDROID_BUILD=true", "-PDROIDE_SOURCE_" + variant.upper() + "=true",
        "-PDROIDE_FDROID_NATIVE_DIR=" + str(products / "linux"),
        "-PDROIDE_FDROID_TERMINAL_DIR=" + str(products / "terminal"),
        "-PDROIDE_FDROID_JGIT_DIR=" + str(products / "jgit"), ":app:assembleSource" + variant]
    subprocess.run(command, cwd=root, env=environment, check=True)
    if not args.debug:
        build = root / "app/build-source-release-fdroid-candidate"
        subprocess.run(["python3", "-B", root / "tools/verify_fdroid_native_reproducibility.py",
                        "--root", root, "--sdk", sdk,
                        "--native", build / "intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib",
                        "--report", build / "outputs/native-reproducibility.json"],
                       cwd=root, env=environment, check=True)


if __name__ == "__main__":
    main()
