#!/usr/bin/env python3
"""Compile the complete Android JGit derivative without using an upstream binary JGit JAR."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from build_terminal_sources import archive, require
from verify_jgit_android_compat import manifest as android_manifest


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(root, java_home, dependencies, output):
    require(not output.exists(), "Use a new output directory")
    manifest_path = root / "third_party/jgit-complete/manifest.json"
    manifest = json.loads(manifest_path.read_text())
    inputs = dict(manifest["sources"], **manifest["overlays"])
    for path, expected in inputs.items():
        require(digest(root / path) == expected, "JGit source changed: " + path)
    inputs[str(manifest_path.relative_to(root))] = digest(manifest_path)
    for path in ("tools/build_jgit_sources.py", "tools/build_terminal_sources.py", "tools/verify_jgit_android_compat.py"):
        inputs[path] = digest(root / path)
    supplied = {p.name: digest(p) for p in dependencies}
    require(supplied == manifest["compileClasspath"], "JGit's compile dependency identities differ")
    compiler = java_home / "bin/javac"
    outputs = []
    with tempfile.TemporaryDirectory(prefix="droide-jgit-source-") as temporary:
        for attempt in range(2):
            work = Path(temporary) / str(attempt)
            source = work / "src"
            shutil.copytree(root / "third_party/jgit-complete/src", source)
            for path in manifest["removedSources"]:
                (source / path).unlink()
            for path in manifest["overlays"]:
                relative = Path(path).relative_to("third_party/jgit/src")
                shutil.copyfile(root / path, source / relative)
            classes = work / "classes"
            classes.mkdir()
            command = [compiler, "--release", "11", "-proc:none", "-implicit:none", "-encoding", "UTF-8", "-g:source,lines",
                       "-classpath", os.pathsep.join(map(str, dependencies)), "-d", classes, *sorted(source.rglob("*.java"))]
            environment = {"PATH": os.defpath, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}
            result = subprocess.run(list(map(str, command)), env=environment, capture_output=True, text=True, timeout=180)
            require(result.returncode == 0, result.stdout + result.stderr)
            entries = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
            forbidden = (b"java/lang/ProcessHandle", b"java/lang/management/", b"javax/management/", b"org/ietf/jgss/")
            for name, data in entries.items():
                require(not any(value in data for value in forbidden), "Desktop-only JGit binding: " + name)
            entries.update({p.relative_to(source).as_posix(): p.read_bytes() for p in source.rglob("*") if p.is_file() and p.suffix != ".java"})
            for path in ("JGit-EDL-1.0.txt", "JGit-Android-Compatibility-NOTICE.txt"):
                license_file = root / "app/src/main/assets/legal/licenses" / path
                inputs[str(license_file.relative_to(root))] = digest(license_file)
                entries["META-INF/licenses/" + path] = license_file.read_bytes()
            entries["META-INF/MANIFEST.MF"] = android_manifest()
            outputs.append(archive(entries))
    require(outputs[0] == outputs[1], "Independent complete JGit builds differ")
    output.mkdir(parents=True)
    (output / "jgit-android-source.jar").write_bytes(outputs[0])
    receipt = {"schema": 1, "kind": "complete-jgit-source", "fullSourceRebuild": True, "binaryBaseRetained": False,
               "independentBuilds": 2, "sourceInputs": inputs, "compileClasspath": supplied,
               "compilerSha256": digest(compiler), "compilerModulesSha256": digest(java_home / "lib/modules"),
               "jarSha256": hashlib.sha256(outputs[0]).hexdigest()}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "java-home", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--dependency", type=Path, required=True, action="append")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists():
        recorded = json.loads((output / "receipt.json").read_text())
        with tempfile.TemporaryDirectory(prefix="droide-jgit-recheck-") as temporary:
            receipt = build(args.root.resolve(), args.java_home.resolve(), args.dependency, Path(temporary) / "rebuilt")
            require(receipt == recorded, "JGit receipt is stale; use a new output directory")
            require(digest(output / "jgit-android-source.jar") == receipt["jarSha256"], "JGit binary changed")
    else:
        receipt = build(args.root.resolve(), args.java_home.resolve(), args.dependency, output)
    print(json.dumps({"jarSha256": receipt["jarSha256"], "independentBuilds": 2}, indent=2))
