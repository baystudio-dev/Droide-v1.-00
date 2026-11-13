#!/usr/bin/env python3
"""Offline, proof-only full Termux View Java rebuild; never production wiring.

Run only inside the reviewed source-proof OS sandbox. Provisioning is separate.
The complete emulator compiler dependency is rebuilt from source first. Pinned
Termux reference bytecode is comparison data only, never a compiler input.
Java/API/resource-reference parity is not Android resource linking, device
validation, method-body equivalence, or F-Droid acceptance.
"""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

import fdroid_termux_source_build as emulator
import fdroid_termux_view_compat as compatibility
import fdroid_termux_view_metadata as metadata
import fdroid_termux_view_sources as resources
import java_archive
import terminal_jdk_v2


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = "tools/fdroid_termux_view_source_build.py"
KIND = "FDROID_TERMUX_FULL_VIEW_JAVA_PROOF"
PREFIX = "com/termux/view/"
SOURCE_JAR = "third_party/termux/terminal-view-v0.118.0-sources.jar"
SOURCE_SHA256 = "93449d6faf0fa0c0e1b89595959d35f4f84bb2f04f7a3d06dc78226677c7f09d"
RESOURCE_PROVENANCE_SHA256 = "70549552343f0eb228e6bc342ae95f080a1353b154910017b2369ea37a8de6d3"
REFERENCE_JAR_SHA256 = "9bceb16dc4d35f412aff579a11fcf4498bd5935dbbee85b9adb11c4ea3ec9068"
# Measured in both independent compilations in run 43369448300, not learned
# from the candidate under test. Any new compiler output needs a fresh review.
RAW_JAR_SHA256 = "b66ee025ea84c1314dee858bc0c711db8b959d8ce04e24b01d5f0616e1519b00"
RAW_AAR_SHA256 = "e61c71cc8e94d06d944e107404fedb07990224aa5cf49a48fa27e793a03b54e0"
ANDROID_SHA256 = "d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a"
ANNOTATION_SHA256 = "ddd072ddbb553178e205517ce777b2f05aa9e412982d9ecb4eedc74f1212f697"
ANNOTATION_COORDINATE = "androidx.annotation:annotation-jvm:1.10.0"
VERIFICATION_METADATA = "gradle/verification-metadata.xml"
JDK_MANIFEST = "tools/terminal-engine-v2-jdk.json"
FLAGS = ["--release", "8", "-encoding", "UTF-8", "-g", "-parameters", "-proc:none", "-implicit:none"]
VM_FLAGS = ["-J-Xmx512m", "-J-XX:ActiveProcessorCount=2"]
ENV = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}
MAX_TEXT = 1024 * 1024
MAX_ARCHIVE = 8 * 1024 * 1024
MAX_ANDROID = 64 * 1024 * 1024
MAX_CLASS = 1024 * 1024
COMPILER_TIMEOUT = 120
PINS = {
    "com/termux/view/GestureAndScaleRecognizer.java": (
        "0df6451d1187ffe2d4c9aa5496cdcc04c96f75839cc990e70b40afaf0bd6e071", "f7fc9d23700905f7e0cdffc13d9b55b63113e23c"),
    "com/termux/view/TerminalRenderer.java": (
        "52db93a02a856e3da3fb79d296d200f5dab2167c97101e46633ed558331f76b7", "307e4226946862eebab505c0d7946cf953a10bcb"),
    "com/termux/view/TerminalView.java": (
        "04ca7b03cd225bd6c7b54fe7377b239f6b6ce3724d6c5acc5491ec8dab28d59e", "2b56d66ea6312cc814afea11c800512b8ee79a2f"),
    "com/termux/view/TerminalViewClient.java": (
        "8f8e647e734e7a888104511979f00084e2e5fc0a887ebbd56c601fd4c314a795", "d6b49b892ca1ab179d20df44a2fdfc2864dc0f09"),
    "com/termux/view/textselection/CursorController.java": (
        "6a7331f375881be9b24d34467900ec84a232ca07feeff69575b3b642abdc3e60", "f0e1cc5e451cf80e6c30cbcfd784828fcfb9c86f"),
    "com/termux/view/textselection/TextSelectionCursorController.java": (
        "14c01f42c82ae891687139088b802442b0a8121b502e7ca21fddceeb51ad6f2a", "253e8deae686f6691b6a8b425ea84fe67b7c8654"),
    "com/termux/view/textselection/TextSelectionHandleView.java": (
        "1f7258ebbd5ab29f7b8ef9ce74c5191a86a6b04cd5806bcb05cf5065843e16fe", "4f39e723bae28fccafa0d6134b3f24f02e694861"),
}
EXPECTED_CLASSES = frozenset(PREFIX + name + ".class" for name in (
    "BuildConfig", "GestureAndScaleRecognizer", "GestureAndScaleRecognizer$1",
    "GestureAndScaleRecognizer$2", "GestureAndScaleRecognizer$3", "GestureAndScaleRecognizer$Listener",
    "TerminalRenderer", "TerminalView", "TerminalView$1", "TerminalView$1$1", "TerminalView$2",
    "TerminalView$3", "TerminalView$TerminalCursorBlinkerRunnable", "TerminalViewClient",
    "textselection/CursorController", "textselection/TextSelectionCursorController",
    "textselection/TextSelectionCursorController$1", "textselection/TextSelectionCursorController$2",
    "textselection/TextSelectionHandleView",
))
R_FIELDS = {
    "com/termux/view/R$drawable": frozenset(("text_select_handle_left_material", "text_select_handle_right_material")),
    "com/termux/view/R$string": frozenset(("copy_text", "paste_text", "text_selection_more")),
}
R_CLASSES = frozenset((PREFIX + "R.class", PREFIX + "R$drawable.class", PREFIX + "R$string.class"))
INSPECTED_CONSTRUCTORS = {
    PREFIX + "TerminalView$1$1.class": "(Lcom/termux/view/TerminalView$1;ZLandroid/view/MotionEvent;)V",
    PREFIX + "TerminalView$2.class": "(Lcom/termux/view/TerminalView;Landroid/view/View;Z)V",
}
METADATA_COMPATIBILITY = {
    PREFIX + "TerminalView$1$1.class": {
        "referenceParameters": [{"name": "this$1", "access": 32784},
                                {"name": "val$e2", "access": 4112},
                                {"name": "val$mouseTrackingAtStartOfFling", "access": 4112}],
        "candidateParameters": [{"name": "this$1", "access": 32784},
                                {"name": "val$mouseTrackingAtStartOfFling", "access": 4112},
                                {"name": "val$e2", "access": 4112}],
        "referenceSignature": None, "candidateSignature": "()V",
    },
    PREFIX + "TerminalView$2.class": {
        "referenceParameters": [{"name": "this$0", "access": 32784},
                                {"name": "x0", "access": 0}, {"name": "x1", "access": 0}],
        "candidateParameters": [{"name": "this$0", "access": 32784},
                                {"name": "targetView", "access": 0}, {"name": "fullEditor", "access": 0}],
        "referenceSignature": None, "candidateSignature": None,
    },
}
BUILD_CONFIG_SOURCE = b'''package com.termux.view;

/** Generated release constants, compared against the unchanged reference. */
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String LIBRARY_PACKAGE_NAME = "com.termux.view";
    public static final String BUILD_TYPE = "release";
}
'''
R_SOURCE = b'''package com.termux.view;

/** Compile-only library symbols. Non-final fields prevent zero-ID inlining. */
public final class R {
    public static final class drawable {
        public static int text_select_handle_left_material = 0;
        public static int text_select_handle_right_material = 0;
    }
    public static final class string {
        public static int copy_text = 0;
        public static int paste_text = 0;
        public static int text_selection_more = 0;
    }
}
'''
PROOF_MANIFEST = b'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.termux.view">
    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28" />
</manifest>
'''
R_TXT = ("\n".join(sorted(resources.EXPECTED_R)) + "\n").encode("utf-8")
AAR_METADATA_PATH = "META-INF/com/android/build/gradle/aar-metadata.properties"
AAR_METADATA = b"aarFormatVersion=1.0\naarMetadataVersion=1.0\n"
LICENSE_PATH = "META-INF/termux-LICENSE.md"


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read(path, bound=MAX_TEXT):
    return resources.read_regular(path, bound)


def write_new(path, raw):
    path = resources.checked(path)
    with path.open("xb") as stream:
        stream.write(raw)


def validate_annotation_pin(raw):
    root = resources.xml(raw)
    ns = {"v": "https://schema.gradle.org/dependency-verification"}
    nodes = [node for node in root.findall("v:components/v:component", ns)
             if node.attrib == {"group": "androidx.annotation", "name": "annotation-jvm", "version": "1.10.0"}]
    require(len(nodes) == 1, "current annotation coordinate is missing or duplicated")
    artifacts = [node for node in nodes[0].findall("v:artifact", ns) if node.get("name") == "annotation-jvm-1.10.0.jar"]
    require(len(artifacts) == 1, "current annotation JAR entry is missing or duplicated")
    digests = [node.get("value") for node in artifacts[0].findall("v:sha256", ns)]
    require(digests == [ANNOTATION_SHA256], "current annotation Gradle hash pin changed")


def select_sources(entries, provenance):
    expected_upstream = {"repository": resources.REPOSITORY, "tag": resources.TAG, "commit": resources.COMMIT}
    require(all(provenance.get("upstream", {}).get(key) == value for key, value in expected_upstream.items()),
            "immutable Java source upstream changed")
    expected_artifact = {"path": SOURCE_JAR, "sha256": SOURCE_SHA256,
                         "gitBlobSha1ByPath": {name: pin[1] for name, pin in PINS.items()}}
    require(provenance.get("artifacts", {}).get("terminalViewSources") == expected_artifact,
            "Java source provenance mapping/pins changed")
    regular = {name for name in entries if not name.endswith("/")}
    require(regular == set(PINS) | {"META-INF/MANIFEST.MF"}, "View source archive inventory changed")
    sources = {}
    for name, (digest, blob) in PINS.items():
        raw = entries[name]
        require(len(raw) <= MAX_TEXT and sha(raw) == digest, "View Java source SHA-256 mismatch: " + name)
        require(resources.git_blob(raw) == blob, "View Java source Git blob mismatch: " + name)
        sources[name] = raw
    sources[PREFIX + "BuildConfig.java"] = BUILD_CONFIG_SOURCE
    sources[PREFIX + "R.java"] = R_SOURCE
    return sources


def validate_inventory(classes, expected=EXPECTED_CLASSES):
    require(set(classes) == set(expected), "View class inventory changed: missing=" + repr(sorted(set(expected) - set(classes))) +
            " extra=" + repr(sorted(set(classes) - set(expected))))
    for name, raw in classes.items():
        require(len(raw) <= MAX_CLASS, "oversized View class: " + name)
        parsed = java_archive.parse_class(raw)
        require(parsed["name"] + ".class" == name and parsed["minor"] == 0 and parsed["major"] == 52,
                "View class identity/Java 8 version changed: " + name)


def load_inputs(root):
    root = resources.checked(root)
    require(resources.checked(__file__) == resources.checked(root / SCRIPT), "proof tool is outside selected source root")
    modules = ((emulator, "tools/fdroid_termux_source_build.py"), (metadata, "tools/fdroid_termux_view_metadata.py"),
               (compatibility, "tools/fdroid_termux_view_compat.py"),
               (resources, "tools/fdroid_termux_view_sources.py"), (java_archive, "tools/java_archive.py"),
               (terminal_jdk_v2, "tools/terminal_jdk_v2.py"))
    for module, relative in modules:
        require(resources.checked(module.__file__) == resources.checked(root / relative), "wrong imported helper: " + relative)
    recipe, emulator_hashes, _, _, emulator_expected, _, _ = emulator.load_inputs(root)
    require(recipe["androidJarSha256"] == ANDROID_SHA256 and recipe["jdkManifest"] == JDK_MANIFEST,
            "current emulator toolchain identity changed")
    source_raw = read(root / SOURCE_JAR, MAX_ARCHIVE)
    require(sha(source_raw) == SOURCE_SHA256, "View source archive SHA-256 changed")
    provenance = resources.parse_json(read(root / emulator.PROVENANCE))
    sources = select_sources(java_archive.archive_entries(source_raw), provenance)
    source_resources, resource_provenance = resources.source_inputs(root)
    require(resource_provenance == RESOURCE_PROVENANCE_SHA256, "View resource provenance SHA-256 changed")
    resource_comparison = resources.compare_reference(root / resources.AAR_RELATIVE, source_resources)
    reference_raw = read(root / resources.AAR_RELATIVE, MAX_ARCHIVE)
    require(sha(reference_raw) == resources.AAR_SHA256, "View reference AAR SHA-256 changed")
    outer = java_archive.archive_entries(reference_raw)
    expected_payload = {"classes.jar", "AndroidManifest.xml", "R.txt", AAR_METADATA_PATH} | set(resources.EXPECTED_AAR_RESOURCES)
    require({name for name in outer if not name.endswith("/")} == expected_payload, "View reference payload inventory changed")
    require(sha(outer["classes.jar"]) == REFERENCE_JAR_SHA256, "View reference classes.jar SHA-256 changed")
    references = java_archive.archive_entries(outer["classes.jar"])
    validate_inventory(references)
    # AGP's property ordering/line endings are wrapping metadata, not runtime resources.
    properties = dict(line.split("=", 1) for line in outer[AAR_METADATA_PATH].decode("ascii").splitlines() if line)
    require(properties == {"aarFormatVersion": "1.0", "aarMetadataVersion": "1.0"}, "reference AAR metadata changed")
    validate_annotation_pin(read(root / VERIFICATION_METADATA))
    tracked = {SCRIPT, SOURCE_JAR, emulator.PROVENANCE, resources.AAR_RELATIVE, VERIFICATION_METADATA,
               str(resources.SOURCE_RELATIVE / "PROVENANCE.json"), *(relative for _, relative in modules),
               *(str(resources.SOURCE_RELATIVE / name) for name in resources.PINS)}
    hashes = {**emulator_hashes, **{name: sha(read(root / name, MAX_ARCHIVE)) for name in tracked}}
    return {"sources": sources, "resources": source_resources, "inputs": hashes, "references": references,
            "referenceAarEntries": outer, "resourceComparison": resource_comparison,
            "emulatorExpected": emulator_expected, "recipe": recipe}


def command(java_home, work, source_names):
    classpath = os.pathsep.join(str(work / "inputs" / name)
                               for name in ("android.jar", "annotation.jar", "emulator-from-source.jar"))
    return [str(java_home / "bin/javac"), *VM_FLAGS,
            "-J-Duser.home=" + str(work / "user-home"), "-J-Djava.io.tmpdir=" + str(work / "tmp"),
            "-J-Djava.util.prefs.userRoot=" + str(work / "prefs"), *FLAGS,
            "-sourcepath", str(work / "empty-sourcepath"), "-classpath", classpath,
            "-d", str(work / "compiled"), *(str(work / "inputs/src" / name) for name in sorted(source_names))]


def build_environment(work):
    # No ambient shell, JVM, loader, proxy, credentials or classpath variables.
    return {**ENV, "HOME": str(work / "user-home"), "TMPDIR": str(work / "tmp"),
            "XDG_CACHE_HOME": str(work / "cache"), "XDG_CONFIG_HOME": str(work / "config"),
            "XDG_DATA_HOME": str(work / "data")}


def read_tree(base, bound):
    result, total = {}, 0
    for path in base.rglob("*"):
        path = resources.checked(path)
        if path.is_dir():
            continue
        require(len(result) < 64, "compiler tree file-count bound exceeded")
        raw = read(path, bound)
        total += len(raw)
        require(total <= 16 * 1024 * 1024, "compiler tree byte bound exceeded")
        result[path.relative_to(base).as_posix()] = raw
    return result


def select_packaged_classes(compiled):
    validate_inventory(compiled, EXPECTED_CLASSES | R_CLASSES)
    for name in sorted(R_CLASSES):
        parsed = java_archive.parse_class(compiled[name])
        expected = R_FIELDS.get(parsed["name"], frozenset())
        require({field["name"] for field in parsed["fields"]} == expected, "compile-only R field inventory changed")
        require(all(field["descriptor"] == "I" and field["access"] == 0x0009 and field["constant"] is None and
                    "ConstantValue" not in field["attributes"] for field in parsed["fields"]),
                "compile-only R fields must be public static non-final without ConstantValue")
    selected = {name: raw for name, raw in compiled.items() if name not in R_CLASSES}
    validate_inventory(selected)
    return selected


def proof_aar(jar, source_resources):
    validate_inventory(java_archive.archive_entries(jar))
    require(set(source_resources) == set(resources.PINS), "incomplete proof resource/license sources")
    for name, (digest, blob) in resources.PINS.items():
        require(sha(source_resources[name]) == digest and resources.git_blob(source_resources[name]) == blob,
                "proof source resource/license identity changed: " + name)
    resources.manifest_source(source_resources["AndroidManifest.xml"])
    resources.manifest_reference(PROOF_MANIFEST)
    require(resources.strings(source_resources["res/values/strings.xml"]) == resources.EXPECTED_STRINGS,
            "proof string resources changed")
    # The manifest SDK declaration is generated from reviewed upstream metadata;
    # every resource/license payload byte comes from the pinned text sources.
    entries = {"classes.jar": jar, "AndroidManifest.xml": PROOF_MANIFEST, "R.txt": R_TXT,
               AAR_METADATA_PATH: AAR_METADATA, LICENSE_PATH: source_resources["LICENSE.md"],
               **{name: source_resources[name] for name in sorted(resources.RESOURCE_PATHS)}}
    return java_archive.deterministic_zip(entries)


def compile_once(work, java_home, android_bytes, annotation_bytes, emulator_jar, sources, source_resources):
    work = resources.checked(work)
    work.mkdir(mode=0o700)
    for relative in ("inputs/src", "empty-sourcepath", "compiled", "user-home", "tmp", "prefs", "cache", "config", "data"):
        (work / relative).mkdir(parents=True)
    staged = {"android.jar": android_bytes, "annotation.jar": annotation_bytes, "emulator-from-source.jar": emulator_jar}
    for name, raw in staged.items():
        write_new(work / "inputs" / name, raw)
    for name, raw in sources.items():
        path = work / "inputs/src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        write_new(path, raw)
    argv, environment = command(java_home, work, sources), build_environment(work)
    invocation = {"argv": argv, "cwd": str(work), "environment": environment, "timeoutSeconds": COMPILER_TIMEOUT,
                  "annotationProcessorsDisabled": True, "targetClassesExecuted": False}
    try:
        # Disk-backed logs avoid collecting compiler output in unbounded RAM.
        with (work / "javac.stdout").open("xb") as out, (work / "javac.stderr").open("xb") as err:
            result = subprocess.run(argv, cwd=work, env=environment, stdin=subprocess.DEVNULL,
                                    stdout=out, stderr=err, timeout=COMPILER_TIMEOUT, check=False)
        invocation["exit"] = result.returncode
    except subprocess.SubprocessError as error:
        invocation["failure"] = type(error).__name__
        emulator.json_write(work / "command.json", invocation)
        raise
    emulator.json_write(work / "command.json", invocation)
    require(result.returncode == 0, "View source compiler failed; inspect " + str(work / "javac.stderr"))
    for name in ("javac.stdout", "javac.stderr"):
        read(work / name, MAX_TEXT)
    require(not list((work / "empty-sourcepath").iterdir()), "compiler changed empty sourcepath")
    require({name: read(work / "inputs" / name, MAX_ANDROID) for name in staged} == staged,
            "staged compiler dependency changed")
    require(read_tree(work / "inputs/src", MAX_TEXT) == sources, "staged View source inputs changed")
    classes = select_packaged_classes(read_tree(work / "compiled", MAX_CLASS))
    jar = java_archive.deterministic_zip(classes)
    aar = proof_aar(jar, source_resources)
    write_new(work / "terminal-view-source-proof.jar", jar)
    write_new(work / "terminal-view-source-proof.aar", aar)
    return classes, jar, aar


def resource_accesses(classes):
    """Per-method ordered actual GETSTATIC reads; offsets may differ by compiler."""
    grouped, symbols, count = {}, set(), 0
    for class_name, raw in sorted(classes.items()):
        for row in metadata.resource_field_references(raw):
            owner, field, descriptor = row["owner"], row["field_name"], row["field_descriptor"]
            require(owner in R_FIELDS and field in R_FIELDS[owner] and descriptor == "I", "unexpected View resource GETSTATIC")
            require(type(row["bytecode_offset"]) is int and row["bytecode_offset"] >= 0, "invalid resource instruction offset")
            key = (class_name, row["method_name"], row["method_descriptor"])
            grouped.setdefault(key, []).append([owner, field, descriptor])
            symbols.add((owner, field, descriptor))
            count += 1
    expected = {(owner, field, "I") for owner, fields in R_FIELDS.items() for field in fields}
    require(symbols == expected, "actual resource reads missing; zero-ID inlining or resource loss is not allowed")
    return {"getstaticCount": count, "symbolCount": len(symbols),
            "methods": [{"class": key[0], "method": key[1], "descriptor": key[2], "reads": grouped[key]}
                        for key in sorted(grouped)]}


def debug_inventory(classes):
    """Read debug attributes as data only, separately from API and full-byte gates."""
    counts = {name: 0 for name in ("SourceFile", "MethodParameters", "LineNumberTable", "LocalVariableTable",
                                  "LocalVariableTypeTable", "StackMapTable")}
    source_files = {}
    for name, raw in sorted(classes.items()):
        parsed = java_archive.parse_class(raw)
        utf, _, _ = emulator._pool(parsed["constantPool"])
        if "SourceFile" in parsed["attributes"]:
            value = parsed["attributes"]["SourceFile"]
            require(len(value) == 2, "invalid SourceFile attribute")
            source_files[name] = utf(int.from_bytes(value, "big"))
            counts["SourceFile"] += 1
        for method in parsed["methods"]:
            counts["MethodParameters"] += "MethodParameters" in method["attributes"]
            code = method["attributes"].get("Code")
            if code is None:
                continue
            position = 0
            def take(size):
                nonlocal position
                require(size >= 0 and position + size <= len(code), "truncated debug Code attribute")
                value = code[position:position + size]
                position += size
                return value
            def integer(size):
                return int.from_bytes(take(size), "big")
            take(4)
            take(integer(4))
            take(8 * integer(2))
            seen = set()
            for _ in range(integer(2)):
                attr_name, size = utf(integer(2)), integer(4)
                require(attr_name not in seen, "duplicate nested Code attribute")
                seen.add(attr_name)
                take(size)
                if attr_name in counts:
                    counts[attr_name] += 1
            require(position == len(code), "trailing Code attribute bytes")
    return {"attributeCounts": counts, "sourceFiles": source_files}


def declaration_abi(raw):
    """Narrow diagnostic only; annotations/generics/parameters keep a strict gate."""
    parsed = java_archive.parse_class(raw)
    result = {key: parsed[key] for key in ("minor", "major", "access", "name", "super", "interfaces")}
    for kind in ("fields", "methods"):
        result[kind] = sorted((member["name"], member["descriptor"], member["access"], member["constant"])
                              for member in parsed[kind] if not member["access"] & 2 and member["name"] != "<clinit>")
    return result


def compact_api_differences(differences, budget=4096):
    """Bound exact changed paths/values instead of logging entire method lists."""
    entries, count = [], 0
    def changed(old, new, path):
        if isinstance(old, dict) and isinstance(new, dict):
            for key in sorted(set(old) | set(new)):
                if key not in old or key not in new:
                    yield path + "." + key, old.get(key), new.get(key)
                elif old[key] != new[key]:
                    yield from changed(old[key], new[key], path + "." + key)
        elif old != new:
            yield path, old, new
    def rows():
        for class_name, delta in sorted(differences.items()):
            for category, values in sorted(delta.items()):
                old, new = values["reference"], values["candidate"]
                if category in ("fields", "methods"):
                    old_members = {(row["name"], row["descriptor"]): row for row in old}
                    new_members = {(row["name"], row["descriptor"]): row for row in new}
                    for member in sorted(set(old_members) | set(new_members)):
                        old_row, new_row = old_members.get(member), new_members.get(member)
                        for key, before, after in changed(old_row, new_row, category):
                            yield {"class": class_name, "member": member[0] + member[1], "path": key,
                                   "reference": before, "candidate": after}
                else:
                    for key, before, after in changed(old, new, category):
                        yield {"class": class_name, "path": key, "reference": before, "candidate": after}
    for row in rows():
        count += 1
        if len(json.dumps(entries + [row], ensure_ascii=True, separators=(",", ":")).encode("ascii")) <= budget:
            entries.append(row)
    return {"changedPathCount": count, "entries": entries, "truncated": count != len(entries)}


def compare_classes(classes, references):
    validate_inventory(classes)
    validate_inventory(references)
    differences, byte_differences, declaration_differences = {}, {}, []
    for name in sorted(EXPECTED_CLASSES):
        # Always parse API/annotations even when full bytes happen to match.
        delta = metadata.api_difference(references[name], classes[name])
        if delta:
            differences[name] = delta
        if declaration_abi(references[name]) != declaration_abi(classes[name]):
            declaration_differences.append(name)
        if classes[name] != references[name]:
            byte_differences[name] = {"reference": sha(references[name]), "candidate": sha(classes[name])}
    old_accesses, new_accesses = resource_accesses(references), resource_accesses(classes)
    return {"classCount": len(classes), "classSha256": {name: sha(raw) for name, raw in sorted(classes.items())},
            "allAccessibleApiMatched": not differences, "accessibleApiDifferences": differences,
            "accessibleDeclarationAbiMatched": not declaration_differences,
            "accessibleDeclarationAbiDifferenceClasses": declaration_differences,
            "declarationDiagnosticScope": "names, descriptors, access, constants and class hierarchy only; strict metadata still gates success",
            "compactApiDifferences": compact_api_differences(differences),
            "apiIncludesPackagePrivateAndAnnotationValues": True, "allClassHashesMatched": not byte_differences,
            "allClassHashDifferences": byte_differences, "resourceGetstaticMatched": old_accesses == new_accesses,
            "referenceResourceAccesses": old_accesses, "candidateResourceAccesses": new_accesses,
            "referenceDebugMetadata": debug_inventory(references), "candidateDebugMetadata": debug_inventory(classes),
            "methodBodyEquivalenceClaimed": False}


def constructor_binding_comparison(classes, references):
    """Two bounded straight-line constructor diagnostics, not an API waiver."""
    rows = []
    for name, descriptor in sorted(INSPECTED_CONSTRUCTORS.items()):
        before = metadata.constructor_binding_evidence(references[name], descriptor)
        after = metadata.constructor_binding_evidence(classes[name], descriptor)
        rows.append({"class": name, "descriptor": descriptor, "matched": before == after,
                     "referenceEvidence": before, "candidateEvidence": after})
    return {"allInspectedBindingsMatched": all(row["matched"] for row in rows),
            "constructorCount": len(rows), "constructors": rows,
            "scope": "ordered resolved instructions, local slots, field stores and super calls in only these two constructors",
            "reflectionMetadataEquivalenceClaimed": False, "methodBodyEquivalenceClaimed": False,
            "strictMetadataGateRelaxed": False}


def compact_constructor_bindings(comparison):
    """Keep one common instruction record when equal; preserve both on mismatch."""
    compact = json.loads(json.dumps(comparison))
    for row in compact.get("constructors", []):
        if row["matched"]:
            row["candidateEvidence"] = {"identicalToReferenceEvidence": True}
    return compact


def verify_raw_compatibility(classes, references, comparison, bindings):
    """Require exactly the reviewed metadata delta, using complete API records.

    This is a precondition for a separate artifact transformation, not an API
    comparator exception. The raw strict comparison remains a failing record.
    """
    require(comparison["allAccessibleApiMatched"] is False and
            set(comparison["accessibleApiDifferences"]) == set(METADATA_COMPATIBILITY),
            "raw candidate does not have exactly the two reviewed metadata targets")
    require(comparison["accessibleDeclarationAbiMatched"] is True and
            comparison["resourceGetstaticMatched"] is True,
            "raw declaration ABI or resource reads differ")
    require(bindings["allInspectedBindingsMatched"] is True and bindings["constructorCount"] == 2,
            "raw constructor instructions/bindings differ from reference")
    validate_inventory(classes)
    validate_inventory(references)
    for name in sorted(EXPECTED_CLASSES):
        expected = metadata.accessible_api(references[name])
        candidate = metadata.accessible_api(classes[name])
        if name in METADATA_COMPATIBILITY:
            rule = METADATA_COMPATIBILITY[name]
            constructors = [row for row in expected["methods"] if row["name"] == "<init>"]
            require(len(constructors) == 1 and constructors[0]["descriptor"] == INSPECTED_CONSTRUCTORS[name]
                    and constructors[0]["access"] == 0, "reviewed constructor declaration changed")
            details = constructors[0]["metadata"]
            require(details["parameters"] == rule["referenceParameters"] and
                    details["signature"] == rule["referenceSignature"], "historical metadata precondition changed")
            # Fixed source-controlled expectations only. This modifies a fresh
            # comparison model, never the raw class or its reported comparison.
            details["parameters"] = rule["candidateParameters"]
            details["signature"] = rule["candidateSignature"]
        require(candidate == expected, "unreviewed raw API/metadata difference: " + name)


def code_attribute_inventory(raw):
    """All methods, including private/initializers, with complete raw Code bytes."""
    return [(method["name"], method["descriptor"], method["access"], method["attributes"].get("Code"))
            for method in java_archive.parse_class(raw)["methods"]]


def adapt_once(work, raw_result, references, source_resources):
    """Create a distinct metadata-adapted proof after every exact guard passes."""
    classes, raw_jar, raw_aar = raw_result
    require(sha(raw_jar) == RAW_JAR_SHA256 and sha(raw_aar) == RAW_AAR_SHA256,
            "raw View archive differs from reviewed compiler output")
    require(java_archive.archive_entries(raw_jar) == classes and proof_aar(raw_jar, source_resources) == raw_aar,
            "raw View artifact payload disagrees with compiler result")
    raw_comparison = compare_classes(classes, references)
    raw_bindings = constructor_binding_comparison(classes, references)
    verify_raw_compatibility(classes, references, raw_comparison, raw_bindings)
    adapted, records = dict(classes), []
    for name in sorted(METADATA_COMPATIBILITY):
        adapted[name], evidence = compatibility.adapt_class(name, classes[name])
        require(evidence["existingConstantPoolPreserved"] is True and
                evidence["allCodeAttributesPreserved"] is True and
                evidence["unrelatedClassStructurePreserved"] is True,
                "metadata compatibility preservation certificate failed")
        require(evidence["rawSha256"] == sha(classes[name]) and evidence["adaptedSha256"] == sha(adapted[name]),
                "metadata compatibility certificate hash mismatch")
        require(evidence["class"] == name and evidence["descriptor"] == INSPECTED_CONSTRUCTORS[name],
                "metadata compatibility certificate identity mismatch")
        records.append(evidence)
    require({name for name in classes if classes[name] != adapted[name]} == set(METADATA_COMPATIBILITY),
            "metadata compatibility changed the wrong class set")
    for name in sorted(EXPECTED_CLASSES):
        require(code_attribute_inventory(classes[name]) == code_attribute_inventory(adapted[name]),
                "metadata compatibility changed a method or its Code bytes: " + name)
    comparison = compare_classes(adapted, references)
    bindings = constructor_binding_comparison(adapted, references)
    require(comparison["allAccessibleApiMatched"] is True and comparison["resourceGetstaticMatched"] is True and
            bindings["allInspectedBindingsMatched"] is True, "adapted candidate failed unchanged strict comparison")
    jar = java_archive.deterministic_zip(adapted)
    aar = proof_aar(jar, source_resources)
    work = resources.checked(work)
    work.mkdir(mode=0o700)
    write_new(work / "terminal-view-metadata-compatible-proof.jar", jar)
    write_new(work / "terminal-view-metadata-compatible-proof.aar", aar)
    evidence = {"applied": True, "recipe": "EXACT_TWO_CONSTRUCTOR_METADATA_COMPATIBILITY_V1",
                "classCount": len(records), "changedMetadataPathCount": 3, "classes": records,
                "allCodeAttributesPreservedFromRaw": True, "untouchedClassCount": len(classes) - len(records),
                "strictMetadataGateRelaxed": False, "referenceBinaryCopiedToOutput": False,
                "rawVsReferenceMethodBodyEquivalenceClaimed": False, "productionPromotionPerformed": False}
    return {"classes": adapted, "jar": jar, "aar": aar, "comparison": comparison,
            "constructorBindingComparison": bindings, "metadataCompatibility": evidence}


def proof_matches(receipt):
    comparison = receipt["comparison"]
    adaptation = receipt.get("metadataCompatibility", {})
    return bool(receipt.get("inputsUnchanged") is True and receipt.get("rawIndependentBuildsEqual") and receipt["independentBuildsEqual"] and
                receipt.get("comparisonArtifact") == "metadata-adapted" and
                adaptation.get("applied") is True and adaptation.get("allCodeAttributesPreservedFromRaw") is True and
                adaptation.get("strictMetadataGateRelaxed") is False and
                comparison["allAccessibleApiMatched"] and
                comparison["resourceGetstaticMatched"] and
                receipt["constructorBindingComparison"]["allInspectedBindingsMatched"])


def rebuild_emulator(root, java_home, android_jar, vendor_archive, output, expected):
    captured = io.StringIO()
    with contextlib.redirect_stdout(captured):
        result = emulator.build(root, java_home, android_jar, vendor_archive, output)
    write_new(output / "driver.stdout", captured.getvalue().encode("utf-8"))
    receipt = resources.parse_json(read(output / "receipt.json", MAX_ARCHIVE))
    require(result == 0 and receipt.get("fullEmulatorJavaSourceRebuild") is True and
            receipt.get("independentBuildsEqual") is True and receipt.get("binaryClassCopiedToOutput") is False and
            receipt.get("binaryTermuxCompilerClasspath") is False and
            receipt.get("comparison", {}).get("allAccessibleApiMatched") is True and
            receipt.get("comparison", {}).get("allCurrentOverlayClassHashesMatched") is True,
            "complete emulator source proof did not pass")
    jars = [read(output / ("run-" + str(number)) / "terminal-emulator-source-proof.jar", MAX_ARCHIVE) for number in (1, 2)]
    require(jars[0] == jars[1], "emulator source output is not reproducible")
    classes = java_archive.archive_entries(jars[0])
    require(set(classes) == set(expected), "emulator source dependency class inventory changed")
    require({name: sha(raw) for name, raw in sorted(classes.items())} == receipt["comparison"]["classSha256"],
            "emulator source dependency differs from proof receipt")
    for name, raw in classes.items():
        parsed = java_archive.parse_class(raw)
        require(parsed["name"] + ".class" == name and parsed["major"] == 52 and parsed["minor"] == 0,
                "invalid source-built emulator dependency")
    return jars[0], {"status": receipt["status"], "sourceJavaFileCount": receipt["sourceJavaFileCount"],
                     "classCount": len(classes), "jarSha256": sha(jars[0]), "receipt": "emulator/receipt.json",
                     "allClassHashesMatched": receipt["comparison"]["allClassHashesMatched"],
                     "allCurrentOverlayClassHashesMatched": True, "allAccessibleApiMatched": True,
                     "independentBuildsEqual": True, "prebuiltTermuxCompilerInput": False}


def output_path(root, java_home, android_jar, annotation_jar, vendor_archive, output):
    output = resources.checked(output)
    for protected in (root, java_home, android_jar, annotation_jar, vendor_archive):
        require(output != protected and protected not in output.parents and output not in protected.parents,
                "proof output overlaps source/toolchain input")
    require(output.parent.is_dir() and not output.exists(), "provide a fresh output path with an existing parent")
    return output


def failure_diagnostics(output):
    """Only fixed scratch compiler logs, at most 4096 bytes total; no host data."""
    logs, remaining = [], 4096
    for relative in ("run-2/javac.stderr", "run-1/javac.stderr", "emulator/run-2/javac.stderr", "emulator/run-1/javac.stderr"):
        path = resources.checked(output / relative)
        if not path.exists() or remaining == 0:
            continue
        require(stat.S_ISREG(path.stat().st_mode), "nonregular compiler diagnostic")
        with path.open("rb") as stream:
            raw = stream.read(remaining)
        if raw:
            logs.append({"path": relative, "text": raw.decode("utf-8", errors="replace"),
                         "truncated": path.stat().st_size > len(raw)})
            remaining -= len(raw)
    return logs


def summary(receipt):
    comparison = receipt.get("comparison", {})
    raw = receipt.get("rawComparison", {})
    result = {"status": receipt["status"], "artifactKind": KIND,
              "fullViewJavaSourceRebuild": receipt["fullViewJavaSourceRebuild"],
              "fullEmulatorJavaSourceRebuild": receipt["fullEmulatorJavaSourceRebuild"],
              "independentBuildsEqual": receipt.get("independentBuildsEqual", False),
              "classCount": comparison.get("classCount", 0),
              "allAccessibleApiMatched": comparison.get("allAccessibleApiMatched", False),
              "accessibleDeclarationAbiMatched": comparison.get("accessibleDeclarationAbiMatched", False),
              "accessibleApiDifferenceClassCount": len(comparison.get("accessibleApiDifferences", {})),
              "accessibleApiDifferenceClasses": sorted(comparison.get("accessibleApiDifferences", {})),
              "apiDifferences": json.loads(json.dumps(comparison.get("compactApiDifferences", {}))),
              "allClassHashesMatched": comparison.get("allClassHashesMatched", False),
              "classByteDifferenceCount": len(comparison.get("allClassHashDifferences", {})),
              "resourceGetstaticMatched": comparison.get("resourceGetstaticMatched", False),
              "resourceGetstaticCount": comparison.get("candidateResourceAccesses", {}).get("getstaticCount", 0),
              "inputsUnchanged": receipt.get("inputsUnchanged", False), "outputs": receipt["outputs"],
              "comparisonArtifact": receipt.get("comparisonArtifact", "raw"),
              "rawIndependentBuildsEqual": receipt.get("rawIndependentBuildsEqual", False),
              "rawAllAccessibleApiMatched": raw.get("allAccessibleApiMatched", False),
              "rawApiDifferences": json.loads(json.dumps(raw.get("compactApiDifferences", {}))),
              "rawClassByteDifferenceCount": len(raw.get("allClassHashDifferences", {})),
              "rawOutputs": receipt.get("rawOutputs", []),
              "productionWiringChanged": False, "binaryTermuxCompilerClasspath": False,
              "androidResourceLinkingVerified": False, "deviceValidated": False,
              "methodBodyEquivalenceClaimed": False, "fdroidAcceptanceVerified": False}
    if "metadataCompatibility" in receipt:
        adaptation = receipt["metadataCompatibility"]
        result["metadataCompatibility"] = {key: value for key, value in adaptation.items() if key != "classes"}
        result["metadataCompatibility"]["classes"] = [
            {key: row[key] for key in ("class", "descriptor", "rawSha256", "adaptedSha256", "changedAttributes",
                                      "existingConstantPoolPreserved", "allCodeAttributesPreserved", "unrelatedClassStructurePreserved")}
            for row in adaptation.get("classes", [])]
    if "constructorBindingComparison" in receipt:
        result["constructorBindingComparison"] = compact_constructor_bindings(receipt["constructorBindingComparison"])
    elif "rawConstructorBindingComparison" in receipt:
        result["constructorBindingComparison"] = compact_constructor_bindings(receipt["rawConstructorBindingComparison"])
    if "failure" in receipt:
        result["failure"] = receipt["failure"][:800]
        result["compilerDiagnostics"] = list(receipt.get("compilerDiagnostics", []))
    return result


def finish(output, receipt):
    compact = summary(receipt)
    # Leave space within the runner's 16 KiB log budget even for escaped text.
    while len(json.dumps(compact, indent=2, sort_keys=True).encode("utf-8")) > 12 * 1024:
        samples = compact.get("apiDifferences", {}).get("entries", [])
        raw_samples = compact.get("rawApiDifferences", {}).get("entries", [])
        diagnostics = compact.get("compilerDiagnostics", [])
        if samples:
            samples.pop()
            compact["apiDifferences"]["truncated"] = True
        elif raw_samples:
            raw_samples.pop()
            compact["rawApiDifferences"]["truncated"] = True
        elif diagnostics:
            diagnostics.pop()
            compact["compilerDiagnosticsTruncated"] = True
        elif compact.get("constructorBindingComparison", {}).get("constructors"):
            compact["constructorBindingComparison"]["constructors"].pop()
            compact["constructorBindingComparison"]["displayTruncated"] = True
        else:
            raise ValueError("compact proof summary unexpectedly exceeds byte budget")
    # Summary first: the bounded runner must not lose the verdict behind hashes.
    emulator.json_write(output / "SUMMARY.json", compact)
    print(json.dumps(compact, sort_keys=True))
    emulator.json_write(output / "receipt.json", receipt)


def build(root, java_home, android_jar, annotation_jar, vendor_archive, output):
    root, java_home, android_jar, annotation_jar, vendor_archive = map(
        resources.checked, (root, java_home, android_jar, annotation_jar, vendor_archive))
    output = output_path(root, java_home, android_jar, annotation_jar, vendor_archive, output)
    output.mkdir(mode=0o700)
    receipt = {"artifactKind": KIND, "status": "VIEW_SOURCE_PROOF_FAILED", "fullViewJavaSourceRebuild": False,
               "fullEmulatorJavaSourceRebuild": False, "productionWiringChanged": False, "releaseCompatible": False,
               "binaryTermuxCompilerClasspath": False, "binaryClassCopiedToOutput": False,
               "androidResourceLinkingVerified": False, "deviceValidated": False, "fdroidAcceptanceVerified": False,
               "methodBodyEquivalenceClaimed": False, "inputsUnchanged": False, "outputs": [], "rawOutputs": [],
               "comparisonArtifact": "raw", "metadataCompatibility": {"applied": False, "strictMetadataGateRelaxed": False}}
    try:
        inputs = load_inputs(root)
        android_bytes, annotation_bytes = read(android_jar, MAX_ANDROID), read(annotation_jar, MAX_ARCHIVE)
        require(sha(android_bytes) == ANDROID_SHA256, "pinned API 36 Android jar changed")
        require(sha(annotation_bytes) == ANNOTATION_SHA256, "pinned annotation-jvm 1.10.0 JAR changed")
        manifest = resources.parse_json(read(root / JDK_MANIFEST, MAX_ARCHIVE))
        jdk = terminal_jdk_v2.verify(java_home, vendor_archive, manifest)
        jdk_summary = {key: value for key, value in jdk.items() if key != "entries"}
        jdk_summary.update(manifestPath=JDK_MANIFEST, manifestSha256=inputs["inputs"][JDK_MANIFEST],
                           verifiedEntryCount=len(jdk["entries"]), fullArchiveAndInstalledTreeVerified=True)
        receipt.update(sourceInputs=inputs["inputs"], compileSourceSha256={name: sha(raw) for name, raw in sorted(inputs["sources"].items())},
                       upstreamJavaFileCount=7, generatedJavaFileCount=2, expectedPackagedClassCount=19,
                       excludedCompileSymbolClasses=sorted(R_CLASSES), resourceProvenanceSha256=RESOURCE_PROVENANCE_SHA256,
                       compiler={"arguments": FLAGS, "jvmResourceArguments": VM_FLAGS, "jdk": jdk_summary,
                                 "androidJarSha256": ANDROID_SHA256, "annotationCoordinate": ANNOTATION_COORDINATE,
                                 "annotationJarSha256": ANNOTATION_SHA256, "kotlinCompilerOrStdlibAdded": False,
                                 "debugAndParameterFlagsAreCurrentProofChoicesNotHistoricalToolchainClaims": True},
                       referenceOnlyInputs=[resources.AAR_RELATIVE, inputs["recipe"]["baseAar"]])
        emulator_jar, emulator_receipt = rebuild_emulator(root, java_home, android_jar, vendor_archive,
                                                         output / "emulator", inputs["emulatorExpected"])
        receipt.update(fullEmulatorJavaSourceRebuild=True, emulatorSourceProof=emulator_receipt)
        raw_results = []
        for number in (1, 2):
            require(load_inputs(root)["inputs"] == inputs["inputs"], "source/reference inputs changed before View compile")
            require(read(android_jar, MAX_ANDROID) == android_bytes and read(annotation_jar, MAX_ARCHIVE) == annotation_bytes,
                    "compiler dependency changed before View compile")
            result = compile_once(output / ("run-" + str(number)), java_home, android_bytes, annotation_bytes,
                                  emulator_jar, inputs["sources"], inputs["resources"])
            raw_results.append(result)
            receipt["rawOutputs"].append({"run": number, "artifactVariant": "raw", "directory": "run-" + str(number),
                                          "classCount": len(result[0]), "jarSha256": sha(result[1]), "aarSha256": sha(result[2])})
            require(load_inputs(root)["inputs"] == inputs["inputs"], "source/reference inputs changed during View compile")
            require(read(android_jar, MAX_ANDROID) == android_bytes and read(annotation_jar, MAX_ARCHIVE) == annotation_bytes,
                    "compiler dependency changed during View compile")
        receipt["fullViewJavaSourceRebuild"] = True
        receipt["rawIndependentBuildsEqual"] = raw_results[0] == raw_results[1]
        receipt["rawComparison"] = compare_classes(raw_results[0][0], inputs["references"])
        receipt["comparison"] = receipt["rawComparison"]
        receipt["rawConstructorBindingComparison"] = constructor_binding_comparison(raw_results[0][0], inputs["references"])
        require(receipt["rawIndependentBuildsEqual"], "independent raw compiler outputs differ")
        results = []
        for number, raw_result in enumerate(raw_results, start=1):
            result = adapt_once(output / ("compat-run-" + str(number)), raw_result, inputs["references"], inputs["resources"])
            results.append(result)
            receipt["outputs"].append({"run": number, "artifactVariant": "metadata-adapted", "directory": "compat-run-" + str(number),
                                       "classCount": len(result["classes"]), "jarSha256": sha(result["jar"]), "aarSha256": sha(result["aar"])})
        receipt["independentBuildsEqual"] = results[0] == results[1]
        receipt["comparisonArtifact"] = "metadata-adapted"
        receipt["comparison"] = results[0]["comparison"]
        receipt["constructorBindingComparison"] = results[0]["constructorBindingComparison"]
        receipt["metadataCompatibility"] = results[0]["metadataCompatibility"]
        receipt["resourceComparison"] = inputs["resourceComparison"]
        receipt["packaging"] = {"referenceClassesJarSha256": REFERENCE_JAR_SHA256,
                                "referenceAarSha256": resources.AAR_SHA256,
                                "artifactVariant": "metadata-adapted",
                                "classesJarByteIdenticalToReference": sha(results[0]["jar"]) == REFERENCE_JAR_SHA256,
                                "aarByteIdenticalToReference": sha(results[0]["aar"]) == resources.AAR_SHA256,
                                "sourceResourceBytesPreserved": True, "sourceLicenseBytesPreserved": True,
                                "licenseEntry": LICENSE_PATH, "resourceSymbolCount": 5,
                                "generatedManifestAddsOnlyPinnedUsesSdk": True,
                                "wrappingDifferences": ["deterministic proof-only ZIP timestamps/order",
                                                        "source res/values/strings.xml instead of generated values.xml",
                                                        "generated manifest, R.txt and AAR metadata formatting",
                                                        "exact upstream license additionally embedded at " + LICENSE_PATH],
                                "notInstallableApk": True, "resourceCompilationOrLinkingPerformed": False}
        require(load_inputs(root)["inputs"] == inputs["inputs"], "source/reference inputs changed after proof")
        require(terminal_jdk_v2.verify(java_home, vendor_archive, manifest) == jdk, "compiler inputs changed during proof")
        require(read(android_jar, MAX_ANDROID) == android_bytes and read(annotation_jar, MAX_ARCHIVE) == annotation_bytes,
                "compiler dependency changed after proof")
        require(all(read(output / "emulator" / ("run-" + str(number)) / "terminal-emulator-source-proof.jar", MAX_ARCHIVE) == emulator_jar
                    for number in (1, 2)), "source-built emulator dependency changed during View proof")
        for number, (_, jar, aar) in enumerate(raw_results, start=1):
            work = output / ("run-" + str(number))
            require(read(work / "terminal-view-source-proof.jar", MAX_ARCHIVE) == jar and
                    read(work / "terminal-view-source-proof.aar", MAX_ARCHIVE) == aar,
                    "raw View proof artifact changed after compilation")
        for number, result in enumerate(results, start=1):
            work = output / ("compat-run-" + str(number))
            require(read(work / "terminal-view-metadata-compatible-proof.jar", MAX_ARCHIVE) == result["jar"] and
                    read(work / "terminal-view-metadata-compatible-proof.aar", MAX_ARCHIVE) == result["aar"],
                    "adapted View proof artifact changed after comparison")
        receipt["inputsUnchanged"] = True
        matched = proof_matches(receipt)
        receipt["status"] = ("VIEW_SOURCE_PROOF_COMPAT_ADAPTED_REPRODUCIBLE_API_RESOURCES_MATCHED_DEVICE_UNVALIDATED" if matched else
                             "VIEW_SOURCE_PROOF_DIFFERENCES_DEVICE_UNVALIDATED")
        receipt["needsValidation"] = [
            "API, annotations and resource reads do not establish method-body equivalence when complete class bytes differ",
            "metadata compatibility preserves Code bytes from the raw rebuild, not equivalence of those bytes to the historical baseline",
            "Android resource compilation/linking, APK build and Dex/native integration have not been run",
            "physical-device selection handles, copy/paste/more, rendering/row metrics, gestures, IME and autofill QA",
            "production source-only wiring without reference binary requirements remains a separate reviewed change",
            "F-Droid scanner, build infrastructure and maintainer acceptance remain separate",
        ]
    except (ValueError, OSError, subprocess.SubprocessError, KeyError, TypeError) as error:
        receipt["failure"] = str(error)
        try:
            receipt["compilerDiagnostics"] = failure_diagnostics(output)
        except (ValueError, OSError):
            receipt["compilerDiagnostics"] = [{"failure": "scratch compiler diagnostics unavailable"}]
        finish(output, receipt)
        raise
    finish(output, receipt)
    return 0 if matched else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--vendor-archive", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--annotation-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="fresh proof directory outside source/toolchain inputs")
    args = parser.parse_args(argv)
    try:
        return build(args.root, args.java_home, args.android_jar, args.annotation_jar, args.vendor_archive, args.output)
    except (ValueError, OSError, subprocess.SubprocessError, KeyError, TypeError) as error:
        print("TERMUX_VIEW_SOURCE_PROOF_FAILED: " + str(error)[:800], file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
