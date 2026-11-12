#!/usr/bin/env python3
"""Isolated full-emulator-Java proof; never a production AAR, APK or native build.

All compiler inputs are source plus the pinned Android SDK/JDK. Existing Termux
bytecode is inspected only as a compatibility reference, never on the compiler
classpath or copied into the output. A successful proof is NOT device validation
or F-Droid admission. The existing overlay/release recipe is deliberately untouched.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys

import java_archive
import terminal_jdk_v2


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = "tools/fdroid_termux_source_build.py"
CONTRACT = "tools/terminal-engine-v2.json"
CONTRACT_SHA256 = "4058f58f5709dfaed99e347ebeef79504fb6d4a68b2311986b5f589516cdce47"
PROVENANCE = "third_party/termux/UPSTREAM_PROVENANCE.json"
PREFIX = "com/termux/terminal/"
KIND = "FDROID_TERMUX_FULL_EMULATOR_JAVA_PROOF"
FLAGS = ["--release", "8", "-encoding", "UTF-8", "-g:none", "-proc:none", "-implicit:none"]
VM_FLAGS = ["-J-Xmx512m", "-J-XX:ActiveProcessorCount=2"]
ENV = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}
UPSTREAM_FAMILIES = frozenset((
    "ByteQueue", "JNI", "KeyHandler", "TerminalBuffer", "TerminalColorScheme",
    "TerminalColors", "TerminalEmulator", "TerminalOutput", "TerminalRow",
    "TerminalSession", "TerminalSessionClient", "TextStyle", "WcWidth",
))
ADDED_FAMILIES = frozenset((
    "TerminalMemoryBudget", "TerminalProcessOwner", "TerminalPtyIo", "TerminalResizeState",
))
RETAINED_FAMILIES = frozenset((
    "BuildConfig", "KeyHandler", "TerminalColorScheme", "TerminalColors",
    "TerminalOutput", "TextStyle", "WcWidth",
))
BUILD_CONFIG_SOURCE = b'''package com.termux.terminal;

/** Generated release constants; API and values are checked against the pinned baseline. */
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String LIBRARY_PACKAGE_NAME = "com.termux.terminal";
    public static final String BUILD_TYPE = "release";
}
'''


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), "symlinked protected path: " + str(path))
    return path


def read(path):
    path = checked(path)
    require(path.is_file() and stat.S_ISREG(path.stat().st_mode), "missing regular input: " + str(path))
    return path.read_bytes()


def json_read(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def json_write(path, value):
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")


def family_of(class_path):
    require(class_path.startswith(PREFIX) and class_path.endswith(".class"), "unexpected terminal class: " + class_path)
    name = class_path[len(PREFIX):-6]
    require("/" not in name, "unexpected terminal subpackage: " + class_path)
    return name.split("$", 1)[0]


def select_sources(upstream, provenance, recipe, root):
    """Source-only closure; the current recipe's LAST stage wins per Java family."""
    expected = provenance["artifacts"]["terminalEmulatorSources"]["gitBlobSha1ByPath"]
    require(set(expected) == {PREFIX + n + ".java" for n in UPSTREAM_FAMILIES}, "upstream source family inventory changed")
    require({n for n in upstream if n.endswith(".java")} == set(expected), "upstream Java inventory changed")
    sources, origins = {}, {}
    for name, blob in expected.items():
        content = upstream[name]
        actual = hashlib.sha1(b"blob " + str(len(content)).encode("ascii") + b"\0" + content).hexdigest()
        require(actual == blob, "upstream Git blob mismatch: " + name)
        sources[name], origins[name] = content, "pinned-upstream-sources-jar:" + name
    for stage in recipe["stages"]:
        for family, relative in stage["sources"].items():
            require(family in UPSTREAM_FAMILIES | ADDED_FAMILIES, "unexpected overlay family: " + family)
            require(relative in recipe["sourceSha256"], "unpinned overlay: " + relative)
            content = read(root / relative)
            require(sha(content) == recipe["sourceSha256"][relative], "overlay source hash mismatch: " + relative)
            name = PREFIX + family + ".java"
            sources[name], origins[name] = content, relative
    require(set(sources) == {PREFIX + n + ".java" for n in UPSTREAM_FAMILIES | ADDED_FAMILIES}, "incomplete final source closure")
    sources[PREFIX + "BuildConfig.java"] = BUILD_CONFIG_SOURCE
    origins[PREFIX + "BuildConfig.java"] = "generated-release-BuildConfig-checked-against-baseline"
    return sources, origins


def reference_inventory(base_classes, recipe):
    """Compute current expected classes/hashes without building or loading reference code."""
    require(len(base_classes) == 19 and all(n.endswith(".class") for n in base_classes), "unexpected upstream class inventory")
    expected = {name: sha(raw) for name, raw in base_classes.items()}
    overlay_names = set()
    for stage in recipe["stages"]:
        compiled = stage["expectedCompiledSha256"]
        require(set(compiled) == set(stage["compiledClasses"]), "recipe stage class/hash mismatch")
        require(all(family_of(n) in stage["sources"] for n in compiled), "compiled class escaped recipe family")
        replaced = {n for n in expected if family_of(n) in stage["sources"]}
        require(replaced <= set(compiled), "recipe drops an existing class")
        expected = {n: digest for n, digest in expected.items() if n not in replaced}
        expected.update(compiled)
        overlay_names.difference_update(replaced)
        overlay_names.update(compiled)
    require(len(expected) == 40, "expected current 40-class emulator inventory changed")
    retained = set(expected) - overlay_names
    require(retained == {PREFIX + n + ".class" for n in RETAINED_FAMILIES}, "retained reference families changed")
    return expected, overlay_names, {n: base_classes[n] for n in retained}


def _pool(raw):
    """Resolve class metadata indices without executing classes or invoking javap."""
    position, constants, index = 0, {}, 1
    def take(size):
        nonlocal position
        require(size >= 0 and position + size <= len(raw), "truncated class constant pool")
        value = raw[position:position + size]
        position += size
        return value
    def integer(size):
        return int.from_bytes(take(size), "big")
    while position < len(raw):
        tag = integer(1)
        if tag == 1:
            value = take(integer(2))
        elif tag in (3, 4):
            value = take(4)
        elif tag in (5, 6):
            value = take(8)
        elif tag in (7, 8, 16, 19, 20):
            value = integer(2)
        elif tag in (9, 10, 11, 12, 17, 18):
            value = (integer(2), integer(2))
        elif tag == 15:
            value = (integer(1), integer(2))
        else:
            raise ValueError("unsupported constant pool tag")
        constants[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1
    def value(number, tag):
        require(number in constants and constants[number][0] == tag, "invalid metadata constant reference")
        return constants[number][1]
    def utf(number):
        return value(number, 1).decode("utf-8")
    def cls(number):
        return utf(value(number, 7)) if number else None
    def name_type(number):
        if not number:
            return None
        name, descriptor = value(number, 12)
        return [utf(name), utf(descriptor)]
    return utf, cls, name_type


def accessible_api(raw):
    """All non-private declarations, including package-private/synthetic bridges.

    Descriptors, flags, constants, generic signatures and declared exceptions are
    compared. Unhandled annotation metadata fails closed instead of claiming a
    complete comparison. Method bodies are checked separately by pinned hashes.
    """
    parsed = java_archive.parse_class(raw)
    utf, cls, name_type = _pool(parsed["constantPool"])
    def short(value):
        require(len(value) == 2, "invalid metadata index")
        return int.from_bytes(value, "big")
    def metadata(attrs):
        require(not any("Annotation" in key for key in attrs), "annotation API metadata requires a reviewed comparator")
        result = {"signature": utf(short(attrs["Signature"])) if "Signature" in attrs else None,
                  "deprecated": "Deprecated" in attrs, "synthetic": "Synthetic" in attrs}
        if "Exceptions" in attrs:
            data = attrs["Exceptions"]
            require(len(data) >= 2 and len(data) == 2 + 2 * short(data[:2]), "invalid Exceptions attribute")
            result["exceptions"] = sorted(cls(short(data[i:i + 2])) for i in range(2, len(data), 2))
        else:
            result["exceptions"] = []
        return result
    result = {key: parsed[key] for key in ("minor", "major", "access", "name", "super", "interfaces")}
    result["metadata"] = metadata(parsed["attributes"])
    for kind in ("fields", "methods"):
        members = []
        for member in parsed[kind]:
            if member["access"] & 2 or member["name"] == "<clinit>":
                continue
            constant = member["constant"]
            members.append({"name": member["name"], "descriptor": member["descriptor"], "access": member["access"],
                            "constant": [constant[0], constant[1].hex()] if constant is not None else None,
                            "metadata": metadata(member["attributes"])})
        result[kind] = sorted(members, key=lambda m: (m["name"], m["descriptor"]))
    attrs = parsed["attributes"]
    result["innerSelf"] = []
    if "InnerClasses" in attrs:
        data = attrs["InnerClasses"]
        require(len(data) >= 2 and len(data) == 2 + 8 * short(data[:2]), "invalid InnerClasses attribute")
        for position in range(2, len(data), 8):
            inner, outer, name, flags = [short(data[position + i:position + i + 2]) for i in (0, 2, 4, 6)]
            if cls(inner) == parsed["name"]:
                result["innerSelf"].append([cls(inner), cls(outer), utf(name) if name else None, flags])
    result["enclosingMethod"] = None
    if "EnclosingMethod" in attrs:
        value = attrs["EnclosingMethod"]
        require(len(value) == 4, "invalid EnclosingMethod attribute")
        result["enclosingMethod"] = [cls(short(value[:2])), name_type(short(value[2:]))]
    return result


def api_difference(before, after):
    old, new = accessible_api(before), accessible_api(after)
    return {key: {"reference": old[key], "candidate": new[key]} for key in old if old[key] != new[key]}


def jni_inspection(classes, recipe):
    name = PREFIX + "JNI.class"
    parsed = java_archive.parse_class(classes[name])
    require(parsed["name"] == "com/termux/terminal/JNI" and parsed["access"] == 0x30 and
            parsed["major"] == 52 and parsed["minor"] == 0 and parsed["super"] == "java/lang/Object" and
            not parsed["interfaces"], "JNI class declaration changed")
    methods = [{"name": m["name"], "descriptor": m["descriptor"]} for m in parsed["methods"] if m["access"] & 0x100]
    require(methods == recipe["jniReplacement"]["methods"], "JNI method inventory/descriptors changed")
    require(all(m["access"] == 0x108 for m in parsed["methods"] if m["access"] & 0x100), "JNI native access flags changed")
    versions = [m for m in parsed["fields"] if m["name"] == "API_VERSION"]
    require(len(versions) == 1 and versions[0]["descriptor"] == "I" and
            versions[0]["constant"] == (3, (2).to_bytes(4, "big")), "JNI owned-process API version changed")
    return {"apiVersion": 2, "methods": methods, "compiledClassSha256": sha(classes[name]),
            "nativeRuntimeExecuted": False}


def compare_classes(classes, expected, overlay_names, references):
    require(set(classes) == set(expected), "compiled class inventory changed: missing=" + repr(sorted(set(expected) - set(classes))) +
            " extra=" + repr(sorted(set(classes) - set(expected))))
    differences, unavailable, evidence, hash_differences = {}, [], {}, {}
    all_hash_differences, retained_hash_differences = {}, {}
    for name in sorted(classes):
        parsed = java_archive.parse_class(classes[name])
        require(parsed["name"] + ".class" == name and parsed["major"] == 52 and parsed["minor"] == 0,
                "compiled class identity/Java 8 version changed: " + name)
        actual = sha(classes[name])
        if actual != expected[name]:
            all_hash_differences[name] = {"expected": expected[name], "actual": actual}
            if name not in overlay_names:
                retained_hash_differences[name] = all_hash_differences[name]
        if name in overlay_names and actual != expected[name]:
            hash_differences[name] = {"expected": expected[name], "actual": actual}
        if actual == expected[name]:
            evidence[name] = "exact pinned current class hash (all API and method bytes)"
        elif name in references:
            delta = api_difference(references[name], classes[name])
            evidence[name] = "parsed non-private API including package-private members"
            if delta:
                differences[name] = delta
        else:
            unavailable.append(name)
            evidence[name] = "current class hash differs; reference class bytes needed for API comparison"
    return {"classCount": len(classes), "classSha256": {n: sha(v) for n, v in sorted(classes.items())},
            "allClassHashesMatched": not all_hash_differences,
            "allClassHashDifferences": all_hash_differences,
            "retainedClassHashDifferences": retained_hash_differences,
            "retainedClassByteEquivalenceVerified": not retained_hash_differences,
            "overlayClassHashDifferences": hash_differences, "accessibleApiDifferences": differences,
            "accessibleApiComparisonUnavailable": unavailable, "apiComparisonEvidence": evidence,
            "allAccessibleApiMatched": not differences and not unavailable,
            "allCurrentOverlayClassHashesMatched": not hash_differences}


def load_inputs(root):
    require(checked(__file__) == checked(root / SCRIPT), "proof tool does not belong to selected source root")
    for module, relative in ((java_archive, "tools/java_archive.py"), (terminal_jdk_v2, "tools/terminal_jdk_v2.py")):
        require(checked(module.__file__) == checked(root / relative), "wrong imported helper: " + relative)
    raw = read(root / CONTRACT)
    require(sha(raw) == CONTRACT_SHA256, "current overlay recipe pin changed")
    recipe = json_read(raw)
    hashes = {p: sha(read(root / p)) for p in recipe["sourceSha256"]}
    require(hashes == recipe["sourceSha256"], "pinned current recipe input changed")
    provenance = json_read(read(root / PROVENANCE))
    artifact = provenance["artifacts"]["terminalEmulatorSources"]
    source_bytes = read(root / artifact["path"])
    require(sha(source_bytes) == artifact["sha256"], "upstream source archive hash changed")
    hashes[artifact["path"]] = sha(source_bytes)
    hashes[CONTRACT], hashes[SCRIPT] = sha(raw), sha(read(root / SCRIPT))
    sources, origins = select_sources(java_archive.archive_entries(source_bytes), provenance, recipe, root)
    base = java_archive.archive_entries(read(root / recipe["baseAar"]))
    require(not any(n.startswith(("jni/", "lib/")) for n in base), "reference Java AAR contains native payload")
    classes = java_archive.archive_entries(base["classes.jar"])
    expected, overlay_names, references = reference_inventory(classes, recipe)
    return recipe, hashes, sources, origins, expected, overlay_names, references


def add_reference_engine(path, expected, references):
    """Optional existing engine is READ ONLY and must match every current class pin."""
    raw = read(path)
    outer = java_archive.archive_entries(raw)
    classes = java_archive.archive_entries(outer["classes.jar"])
    require({n: sha(v) for n, v in classes.items()} == expected, "reference engine does not match current pinned classes")
    references.update(classes)
    return {"path": str(checked(path)), "sha256": sha(raw), "compilerInput": False}


def command(java_home, work, source_names):
    return [str(java_home / "bin/javac"), *VM_FLAGS,
            "-J-Duser.home=" + str(work / "user-home"),
            "-J-Djava.io.tmpdir=" + str(work / "tmp"),
            "-J-Djava.util.prefs.userRoot=" + str(work / "prefs"),
            *FLAGS, "-sourcepath", str(work / "empty-sourcepath"),
            "-classpath", str(work / "inputs/android.jar"), "-d", str(work / "compiled"),
            *(str(work / "inputs/src" / name) for name in sorted(source_names))]


def build_environment(work):
    # HOME and Java/loader overrides are not inherited. The JVM's home property
    # and preferences are explicitly redirected by command(), without changing
    # the user's shell environment or relying on an account-home fallback.
    return {**ENV, "TMPDIR": str(work / "tmp"), "XDG_CACHE_HOME": str(work / "cache"),
            "XDG_CONFIG_HOME": str(work / "config"), "XDG_DATA_HOME": str(work / "data")}


def compile_once(work, java_home, android_bytes, sources):
    work.mkdir(mode=0o700)
    for relative in ("inputs/src", "empty-sourcepath", "compiled", "user-home", "tmp", "prefs", "cache", "config", "data"):
        (work / relative).mkdir(parents=True)
    (work / "inputs/android.jar").write_bytes(android_bytes)
    for name, value in sources.items():
        path = work / "inputs/src" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    argv = command(java_home, work, sources)
    environment = build_environment(work)
    result = subprocess.run(argv, cwd=work, env=environment, stdin=subprocess.DEVNULL,
                            capture_output=True, timeout=180, check=False)
    (work / "javac.stdout").write_bytes(result.stdout)
    (work / "javac.stderr").write_bytes(result.stderr)
    json_write(work / "command.json", {"argv": argv, "cwd": str(work), "environment": environment, "exit": result.returncode})
    require(result.returncode == 0, "source compiler failed; inspect " + str(work / "javac.stderr"))
    require(not list((work / "empty-sourcepath").iterdir()), "compiler changed empty sourcepath")
    require(read(work / "inputs/android.jar") == android_bytes, "compiler Android input changed")
    require({p.relative_to(work / "inputs/src").as_posix(): read(p) for p in (work / "inputs/src").rglob("*") if p.is_file() or p.is_symlink()} == sources,
            "staged source inputs changed")
    classes = {p.relative_to(work / "compiled").as_posix(): read(p) for p in (work / "compiled").rglob("*") if p.is_file() or p.is_symlink()}
    require(all(n.endswith(".class") for n in classes), "unexpected compiler output")
    jar = java_archive.deterministic_zip(classes)
    (work / "terminal-emulator-source-proof.jar").write_bytes(jar)
    return classes, jar


def build(root, java_home, android_jar, vendor_archive, output, reference_engine=None):
    root, java_home, android_jar, vendor_archive, output = map(checked, (root, java_home, android_jar, vendor_archive, output))
    require(output != root and root not in output.parents and output not in root.parents, "proof output must be outside the source repository")
    require(java_home != output and java_home not in output.parents and output not in java_home.parents, "proof output overlaps the JDK")
    require(output.parent.is_dir() and not output.exists(), "provide a fresh output path with an existing parent")
    recipe, inputs, sources, origins, expected, overlay_names, references = load_inputs(root)
    android_bytes = read(android_jar)
    require(sha(android_bytes) == recipe["androidJarSha256"], "pinned API 36 Android jar changed")
    manifest = json_read(read(root / recipe["jdkManifest"]))
    jdk = terminal_jdk_v2.verify(java_home, vendor_archive, manifest)
    # The pinned manifest already records every verified JDK entry. Repeating
    # thousands of entries would obscure failures in bounded CI diagnostics.
    jdk_receipt = {key: value for key, value in jdk.items() if key != "entries"}
    jdk_receipt.update(manifestPath=recipe["jdkManifest"], manifestSha256=inputs[recipe["jdkManifest"]],
                       verifiedEntryCount=len(jdk["entries"]), fullArchiveAndInstalledTreeVerified=True)
    reference = add_reference_engine(reference_engine, expected, references) if reference_engine else None
    output.mkdir(mode=0o700)
    receipt = {"artifactKind": KIND, "releaseCompatible": False, "productionWiringChanged": False,
               "scope": "complete terminal-emulator Java only; no terminal-view, resources, JNI build or APK",
               "fullEmulatorJavaSourceRebuild": False, "binaryClassCopiedToOutput": False,
               "binaryTermuxCompilerClasspath": False, "deviceValidated": False, "publicationApproved": False,
               "compiler": {"arguments": FLAGS, "jvmResourceArguments": VM_FLAGS,
                            "androidJarSha256": sha(android_bytes), "jdk": jdk_receipt},
               "sourceInputs": inputs, "compileSourceSha256": {n: sha(v) for n, v in sorted(sources.items())},
               "compileSourceOrigins": origins, "sourceJavaFileCount": len(sources),
               "referenceOnlyInputs": [recipe["baseAar"]], "optionalReferenceEngine": reference,
               "expectedClassCount": len(expected), "outputs": []}
    try:
        results = []
        for number in (1, 2):
            require(load_inputs(root)[1] == inputs, "source inputs changed before compile")
            classes, jar = compile_once(output / ("run-" + str(number)), java_home, android_bytes, sources)
            results.append((classes, jar))
            receipt["outputs"].append({"run": number, "sha256": sha(jar), "classCount": len(classes)})
            require(load_inputs(root)[1] == inputs, "source inputs changed during compile")
        receipt["independentBuildsEqual"] = results[0][1] == results[1][1]
        receipt["comparison"] = compare_classes(results[0][0], expected, overlay_names, references)
        receipt["fullEmulatorJavaSourceRebuild"] = True
        receipt["jni"] = jni_inspection(results[0][0], recipe)
        require(read(android_jar) == android_bytes, "Android jar changed during proof")
        require(terminal_jdk_v2.verify(java_home, vendor_archive, manifest) == jdk, "compiler inputs changed during proof")
        if reference_engine:
            require(sha(read(reference_engine)) == reference["sha256"], "reference engine changed during proof")
        matched = (receipt["independentBuildsEqual"] and receipt["comparison"]["allAccessibleApiMatched"] and
                   receipt["comparison"]["allCurrentOverlayClassHashesMatched"])
        receipt["status"] = "SOURCE_PROOF_REPRODUCIBLE_API_MATCHED_DEVICE_UNVALIDATED" if matched else "SOURCE_PROOF_DIFFERENCES_DEVICE_UNVALIDATED"
        receipt["needsValidation"] = [
            "terminal-view full source and resource provenance are outside this proof",
            "native source builds and packaged APK/Dex JNI registration are outside this proof",
            "physical-device terminal rendering, IME/input, PTY resize, cancellation and lifecycle regression QA",
            "F-Droid scanner and reviewer acceptance remain separate",
        ]
        if not receipt["comparison"]["retainedClassByteEquivalenceVerified"]:
            receipt["needsValidation"].append(
                "retained upstream class bytes differ: matched accessible API does not establish method-body equivalence")
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        receipt["status"] = "SOURCE_PROOF_FAILED"
        receipt["failure"] = str(error)
        json_write(output / "receipt.json", receipt)
        raise
    json_write(output / "receipt.json", receipt)
    print(receipt["status"] + " " + str(output / "receipt.json"))
    return 0 if matched else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--vendor-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="fresh directory outside the source repository")
    parser.add_argument("--reference-engine", type=Path, help="optional existing current engine AAR, inspected only; never a compiler input")
    args = parser.parse_args()
    try:
        return build(args.root, args.java_home, args.android_jar, args.vendor_archive, args.output, args.reference_engine)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print("TERMUX_SOURCE_PROOF_FAILED: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
