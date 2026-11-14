#!/usr/bin/env python3
"""Verify lease scripts and bundled TAR compatibility on a Linux/JDK host."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
TEST = ROOT / "app/src/test/java/com/baystudio/droide/core"
ASSETS = ROOT / "app/src/main/assets/workstation"


def digest(stream):
    value = hashlib.sha256()
    for block in iter(lambda: stream.read(65536), b""):
        value.update(block)
    return value.digest()


def verify_tree(archive, root):
    count = 0
    with tarfile.open(archive, "r:*") as tar:
        for member in tar:
            destination = root / member.name
            if member.isreg() or member.islnk():
                assert destination.is_file() and not destination.is_symlink(), member.name
                with tar.extractfile(member) as expected, destination.open("rb") as actual:
                    assert digest(expected) == digest(actual), member.name
                assert destination.stat().st_mode & 0o777 == member.mode & 0o777, member.name
                count += 1
            elif member.issym():
                assert destination.is_symlink() and os.readlink(destination) == member.linkname, member.name
            elif member.isdir():
                assert destination.is_dir() and not destination.is_symlink(), member.name
            else:
                raise AssertionError(f"Unexpected bundled TAR member: {member.name}")
    return count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", action="append", required=True)
    args = parser.parse_args()
    for command in ["java", "gcc", "tar", "setsid"]:
        if not shutil.which(command):
            raise SystemExit(f"Missing host prerequisite: {command}")
    jars = sorted({path.resolve() for directory in args.jars for path in Path(directory).glob("*.jar")})
    jars += sorted((ROOT / "app/libs").glob("xz-*.jar"))
    classpath = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix="droide-workstation-check-") as directory:
        temp = Path(directory)
        classes = temp / "classes"
        sources = [CORE / (name + ".kt") for name in ["RemoteProcessLease", "RootfsTarCompatibility", "BundledRootfsExtractor", "PathSecurity"]]
        names = ["RemoteProcessLeaseTest", "RootfsTarCompatibilityTest", "BundledXzDecoderTest"]
        sources += [TEST / (name + ".kt") for name in names]
        sources += sorted((ROOT / "tools/workstation-regressions").glob("*.kt"))
        subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-jvm-target", "17", "-classpath", classpath, "-d", str(classes), *map(str, sources)], check=True)
        runtime = str(classes) + os.pathsep + classpath
        subprocess.run(["java", "-cp", runtime, "org.junit.runner.JUnitCore",
                        *["com.baystudio.droide.core." + name for name in names]], cwd=ROOT / "app", check=True)
        subprocess.run(["java", "-cp", runtime, "com.baystudio.droide.core.RootfsConversionHarnessKt", str(ASSETS), str(temp)], check=True)

        # Simulate Android's hard-link prohibition while running a real tar extractor.
        deny_source = temp / "deny_links.c"
        deny_source.write_text("#include <errno.h>\n#include <unistd.h>\n"
            "int link(const char *old, const char *new) { errno=EPERM; return -1; }\n"
            "int linkat(int oldfd, const char *old, int newfd, const char *new, int flags) { errno=EPERM; return -1; }\n")
        deny_library = temp / "deny_links.so"
        subprocess.run(["gcc", "-shared", "-fPIC", "-o", str(deny_library), str(deny_source)], check=True)
        environment = dict(os.environ, LD_PRELOAD=str(deny_library))
        ubuntu = ASSETS / "ubuntu-base-24.04.5-base-arm64.tar.xz"
        unadapted = temp / "original-ubuntu"
        unadapted.mkdir()
        before = subprocess.run(["tar", "-xJf", str(ubuntu), "-C", str(unadapted), "--no-same-owner", "--same-permissions"],
                                env=environment, text=True, capture_output=True, timeout=60)
        assert before.returncode != 0 and "usr/bin/perl5.38.2" in before.stderr and "usr/bin/uncompress" in before.stderr, before.stderr
        print("ORIGINAL_UBUNTU_HARDLINK_FAILURE_REPRODUCED")

        for source in [ubuntu]:
            adapted = temp / source.name.removesuffix(".xz")
            staging = temp / (source.stem + "-staging")
            staging.mkdir()
            subprocess.run(["tar", "-xf", str(adapted), "-C", str(staging), "--no-same-owner", "--same-permissions"],
                           env=environment, capture_output=True, check=True, timeout=60)
            activated = staging.with_name(staging.name.replace("-staging", "-active"))
            staging.rename(activated)
            count = verify_tree(source, activated)
            if source == ubuntu:
                for alias, target in [("perl5.38.2", "perl"), ("uncompress", "gunzip")]:
                    a, b = activated / "usr/bin" / alias, activated / "usr/bin" / target
                    assert a.stat().st_ino != b.stat().st_ino and a.stat().st_nlink == 1, alias
            print(f"ROOTFS_EXTRACTION_AND_ACTIVATION_OK image={source.name} regular_files={count}")
        print("WORKSTATION_STARTUP_HOST_VERIFICATION_OK")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        if failure.stderr:
            print(failure.stderr.decode() if isinstance(failure.stderr, bytes) else failure.stderr)
        raise SystemExit(f"Workstation verification failed (exit {failure.returncode})") from None
