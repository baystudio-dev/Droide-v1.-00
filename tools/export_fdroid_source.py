"""Create a separate source-only distribution without altering the working source."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def reason(path):
    if any(part in {".git", ".gradle", ".cxx", ".kotlin", "__pycache__", ".idea"} or
           part == "build" or part.startswith("build-") for part in path.parts[:-1]):
        return "generated local state"
    if path.name in {"local.properties", "keystore.properties", "signing.properties"} or path.suffix in {".jks", ".keystore", ".p12"}:
        return "machine-local configuration or signing material"
    if path.as_posix().startswith("third_party/maven/"):
        return "unused local Maven artifact; F-Droid uses source components or trusted repositories"
    if path.as_posix() in {
        "third_party/native-engines/proot/proot-openminis-8cf13e99-source.tar.gz",
        "third_party/native-engines/proot/talloc-2.4.2-source.tar.gz",
    }:
        return "corresponding source is verified plaintext under third_party/native-engines/source-build"
    if path.name in {"gradlew", "gradlew.bat"}:
        return "distribution uses the pinned buildserver Gradle, not a downloaded wrapper"
    if path.suffix in {".jar", ".aar", ".so", ".apk", ".aab", ".class", ".zip"}:
        return "legacy/reference binary or source archive replaced by verified plaintext corresponding source"
    if path.as_posix() == "app/src/main/assets/workstation/ubuntu-base-24.04.5-base-arm64.tar.xz":
        return "optional Ubuntu rootfs is downloaded from Canonical after consent"
    return None


def export(root, output):
    if output.exists() or output == root or output.is_relative_to(root):
        raise ValueError("Use a new output directory outside the original source")
    output.mkdir(parents=True)
    excluded, copied = {}, {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        why = reason(relative)
        if why == "generated local state":
            continue
        if path.is_symlink():
            raise ValueError("Review source symlink before export: " + str(relative))
        if not path.is_file():
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if why:
            excluded[relative.as_posix()] = {"sha256": digest, "reason": why}
            continue
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        copied[relative.as_posix()] = digest
    for source, destination in (
        ("tools/fdroid/settings.gradle.kts", "settings.gradle.kts"),
        ("tools/fdroid/android-ci.yml", ".github/workflows/android-ci.yml"),
        ("tools/fdroid/README.md", "README.md"),
    ):
        target = output / destination
        shutil.copy2(root / source, target)
        copied[destination] = hashlib.sha256(target.read_bytes()).hexdigest()
    receipt = {"schema": 1, "source": "current working source; source-only distribution", "excluded": excluded,
               "files": copied, "fdroidAccepted": False}
    (output / "FDROID-SOURCE-MANIFEST.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps({"copied": len(copied), "excluded": len(excluded), "output": str(output)}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    export(args.root.resolve(), args.output.resolve())
