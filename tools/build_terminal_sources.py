#!/usr/bin/env python3
"""Build both terminal libraries entirely from verified source, twice independently."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

from terminal_engine_v2 import inspect_final


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as result:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            result.writestr(entry, data)
    return output.getvalue()


def build(root, java_home, android_jar, annotations, output):
    require(not output.exists(), "Use a new output directory; existing results are never overwritten")
    manifest_path = root / "third_party/termux/source-build.json"
    manifest = json.loads(manifest_path.read_text())
    inputs = {str(manifest_path.relative_to(root)): digest(manifest_path.read_bytes())}
    for path in ("tools/build_terminal_sources.py", "tools/terminal_engine_v2.py"):
        inputs[path] = digest((root / path).read_bytes())
    require(digest(android_jar.read_bytes()) == manifest["androidJarSha256"], "Android SDK input differs")
    require(digest(annotations.read_bytes()) == manifest["annotations"]["sha256"], "AndroidX annotation input differs")
    for path, row in manifest["files"].items():
        value = (root / path).read_bytes()
        require(digest(value) == row["sha256"], "Upstream source differs: " + path)
        inputs[path] = row["sha256"]
    for row in manifest["emulatorOverlays"].values():
        require(digest((root / row["path"]).read_bytes()) == row["sha256"], "Terminal overlay differs: " + row["path"])
        inputs[row["path"]] = row["sha256"]
    inputs[manifest["license"]] = digest((root / manifest["license"]).read_bytes())
    compiler = java_home / "bin/javac"
    version = subprocess.check_output([compiler, "-version"], text=True).strip()
    require(re.fullmatch(r"javac 17\.[0-9.]+", version), "Use JDK 17 for this source recipe")
    tools = {str(p): digest(p.read_bytes()) for p in (compiler, java_home / "lib/modules", android_jar, annotations)}
    products = []
    with tempfile.TemporaryDirectory(prefix="droide-terminal-source-") as temporary:
        for attempt in range(2):
            work = Path(temporary) / str(attempt)
            work.mkdir()
            results = {}
            for module, namespace in (("emulator", "com.termux.terminal"), ("view", "com.termux.view")):
                staged = work / module
                source_root = root / "third_party/termux/source-build" / module
                shutil.copytree(source_root / "java", staged / "src")
                if module == "emulator":
                    for name, row in manifest["emulatorOverlays"].items():
                        shutil.copyfile(root / row["path"], staged / "src/com/termux/terminal" / (name + ".java"))
                package = staged / "generated" / namespace.replace(".", "/")
                package.mkdir(parents=True)
                (package / "BuildConfig.java").write_text("package " + namespace + "; public final class BuildConfig { public static final boolean DEBUG = false; }\n")
                if module == "view":
                    # Non-final fields preserve resource references for AGP's actual R class.
                    (package / "R.java").write_text("package com.termux.view; public final class R { "
                        "public static final class string { public static int copy_text, paste_text, text_selection_more; } "
                        "public static final class drawable { public static int text_select_handle_left_material, text_select_handle_right_material; } }\n")
                classes = staged / "classes"
                classes.mkdir()
                classpath = [android_jar, annotations]
                if module == "view":
                    classpath.append(work / "emulator/classes.jar")
                sources = sorted(staged.rglob("*.java"))
                command = [compiler, "--release", "8", "-encoding", "UTF-8", "-g:none", "-proc:none", "-implicit:none",
                           "-classpath", os.pathsep.join(map(str, classpath)), "-d", classes, *sources]
                compiled = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=120)
                require(compiled.returncode == 0, compiled.stdout + compiled.stderr)
                compiled_files = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")
                                  if not re.fullmatch(r"com/termux/view/R(?:\$[^/]+)?\.class", p.relative_to(classes).as_posix())}
                require(compiled_files, "No terminal classes compiled")
                jar = archive(compiled_files)
                (staged / "classes.jar").write_bytes(jar)
                entries = {p.relative_to(source_root).as_posix(): p.read_bytes() for p in source_root.rglob("*")
                           if p.is_file() and not p.is_relative_to(source_root / "java")}
                entries["classes.jar"] = jar
                entries["META-INF/LICENSE.txt"] = (root / manifest["license"]).read_bytes()
                results[module] = archive(entries)
            products.append(results)
    require(products[0] == products[1], "Independent full-source terminal builds differ")
    recipe = json.loads((root / "tools/terminal-engine-v2.json").read_text())
    inspection = inspect_final(products[0]["emulator"], recipe)
    inspection.update(binaryBaseRetained=False, fullSourceRebuild=True)
    output.mkdir(parents=True)
    names = {"emulator": "terminal-engine.aar", "view": "terminal-view.aar"}
    for module, data in products[0].items():
        (output / names[module]).write_bytes(data)
    receipt = {"schema": 1, "kind": "complete-terminal-source", "status": "BUILD_ONLY_DEVICE_UNVALIDATED", "deviceValidated": False,
               "binaryBaseRetained": False, "fullSourceRebuild": True, "sourceInputs": inputs,
               "compilerVersion": version, "tools": tools, "independentBuilds": 2, "inspection": inspection,
               "outputs": {names[k]: digest(v) for k, v in products[0].items()}}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "java-home", "android-jar", "annotations", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    inputs = [getattr(args, name).resolve() for name in ("root", "java_home", "android_jar", "annotations")]
    output = args.output.resolve()
    if output.exists():
        recorded = json.loads((output / "receipt.json").read_text())
        with tempfile.TemporaryDirectory(prefix="droide-terminal-recheck-") as temporary:
            receipt = build(*inputs, Path(temporary) / "rebuilt")
            require(receipt == recorded, "Full-source terminal receipt is stale; use a new output directory")
            for name, expected in receipt["outputs"].items():
                require(digest((output / name).read_bytes()) == expected, "Terminal output differs: " + name)
    else:
        receipt = build(*inputs, output)
    print(json.dumps({"outputs": receipt["outputs"], "independentBuilds": receipt["independentBuilds"]}, indent=2))
