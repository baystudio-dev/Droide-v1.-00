#!/usr/bin/env python3
"""Classify, but NEVER waive, the binary findings in Droide's F-Droid RFP.

This is a drift guard on upstream versionCode 44. It neither runs F-Droid's
scanner nor claims a clean or source-built APK. Any source-build remediation
must update this baseline deliberately and preserve user-facing functionality.
"""

from __future__ import annotations

from collections import Counter
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    "source_jar": 7,
    "original_jgit_jar": 1,
    "custom_compat_jar": 4,
    "upstream_mirror_jar": 5,
    "app_xz_jar": 1,
    "termux_aar": 4,
    "upstream_mirror_aar": 2,
    "native_runtime_so": 4,
    "proot_source_tar_gz": 2,
}
GRADLE_WRAPPER = "gradle/wrapper/gradle-wrapper.jar"


def classify(path: str) -> str:
    if path.endswith("-sources.jar"):
        return "source_jar"
    if path.startswith("third_party/jgit/upstream/") and path.endswith(".jar"):
        return "original_jgit_jar"
    if path.startswith("third_party/maven/com/baystudio/compat/") and path.endswith(".jar"):
        return "custom_compat_jar"
    if path.startswith((
        "third_party/maven/com/squareup/okio/",
        "third_party/maven/org/bouncycastle/",
        "third_party/maven/org/jetbrains/kotlinx/",
    )) and path.endswith(".jar"):
        return "upstream_mirror_jar"
    if path == "app/libs/xz-1.12.jar":
        return "app_xz_jar"
    if path.startswith("app/libs/terminal-") and path.endswith(".aar"):
        return "termux_aar"
    if path.startswith((
        "third_party/maven/androidx/documentfile/",
        "third_party/maven/org/lsposed/hiddenapibypass/",
    )) and path.endswith(".aar"):
        return "upstream_mirror_aar"
    if path.startswith("app/src/main/jniLibs/arm64-v8a/") and path.endswith(".so"):
        return "native_runtime_so"
    if path.startswith("third_party/native-engines/proot/") and path.endswith("-source.tar.gz"):
        return "proot_source_tar_gz"
    raise ValueError("Unclassified F-Droid scanner artifact: " + path)


def inventory(root: Path = ROOT) -> dict:
    result = subprocess.run(
        ["git", "ls-files", "-z", "--", "*.jar", "*.aar", "*.so", "*.tar.gz"],
        cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True,
    )
    tracked = [path.decode("utf-8") for path in result.stdout.split(b"\x00") if path]
    if tracked.count(GRADLE_WRAPPER) != 1:
        raise ValueError("Gradle wrapper's separately reported info entry is missing or duplicated")
    scanned = sorted(path for path in tracked if path != GRADLE_WRAPPER)
    if len(scanned) != len(set(scanned)):
        raise ValueError("Duplicate scanner artifact path")
    for item in scanned:
        path = root / item
        if not path.is_file() or path.is_symlink():
            raise ValueError("Scanner artifact missing or symlinked: " + item)
    categories = {path: classify(path) for path in scanned}
    counts = Counter(categories.values())
    if counts != EXPECTED:
        raise ValueError("Scanner binary inventory changed: " + str(dict(counts)))
    settings = (root / "settings.gradle.kts").read_text()
    for needle in (
        'name = "R8Releases"',
        'url = uri("https://storage.googleapis.com/r8-releases/raw")',
        'name = "DroideLocalCompat"',
        'name = "DroideLocalSupport"',
        'url = uri(rootDir.resolve("third_party/maven"))',
    ):
        if needle not in settings:
            raise ValueError("Maven scanner baseline changed: " + needle)
    lock = (root / "app/gradle.lockfile").read_text().splitlines()
    opencensus = [line for line in lock if line.startswith("io.opencensus:")]
    if len(opencensus) != 2 or any(
        not line.split("=", 1)[1].startswith("_internal-unified-test-platform-") or
        "releaseRuntimeClasspath" in line or "debugRuntimeClasspath" in line
        for line in opencensus
    ):
        raise ValueError("OpenCensus test-only dependency claim needs revalidation")
    return {
        "scannerReference": "fdroid/rfp#4776 / versionCode=44 / commit=a02d3c7e",
        "mavenDeclarations": 3,
        "binaryFindings": len(scanned),
        "totalFindings": len(scanned) + 3,
        "categoryCounts": dict(sorted(counts.items())),
        "paths": categories,
        "opencensusLockScope": "internal-test-platform-only (APK not assessed)",
        "status": "KNOWN_SCAN_ERRORS_UNRESOLVED",
    }


if __name__ == "__main__":
    value = inventory()
    print("FDROID_SCANNER_INVENTORY_UNRESOLVED", json.dumps(value, sort_keys=True))
