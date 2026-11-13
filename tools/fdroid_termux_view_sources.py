#!/usr/bin/env python3
"""Offline, read-only provenance check for Termux View manifest/resources/license.

The default check uses only vendored text sources. An explicitly supplied pinned
reference AAR may additionally be inspected, but is never required by source-only
validation. No downloads, compilation, resource generation, file writes or target
program execution occur. This is not an APK, runtime or F-Droid acceptance test.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE_RELATIVE = Path("third_party/termux/view-source-resources")
REPOSITORY = "https://github.com/termux/termux-app"
COMMIT = "6e2689f55295fa444be8ac8592c527c2c5ef3253"
TAG = "v0.118.0"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
AAR_SHA256 = "06b3e83cf34c40e82ba5348a88fc72657ebf35177e28f567e86b2389c34cd2d0"
AAR_RELATIVE = "app/libs/terminal-view-v0.118.0.aar"
MAX_SOURCE_BYTES = 1024 * 1024
MAX_AAR_BYTES = 8 * 1024 * 1024
PINS = {
    "AndroidManifest.xml": ("a595e81539a73bad6a0601d1b47b1cef0dd6ff236f2cc4e4158fffede55e57cb", "f2b0725df41305c9d557c16e80e10a890b24811d"),
    "res/drawable/text_select_handle_left_material.xml": ("3ce66a831646933bea6adc67ed8d4b8dfd3bb86b992b1ea789b0ba577add4551", "576ff4a968f6abd6da01db87847a312f7e8ae7c6"),
    "res/drawable/text_select_handle_right_material.xml": ("977579c11e9c28ef74af25ab61b8f39f812006e8ac5d2a9c5bf185a2456d522f", "d049d3a8a9b6f658ae51f372930d179179d76915"),
    "res/values/strings.xml": ("c2880e9b490ea42e0816dc2ad1b9bd6b1e75098508afd3681433ba07b14a1bfb", "cd03c618ef1b158511f5943673cf7a4ff54bbb8e"),
    "LICENSE.md": ("a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304", "36cd48f8710cc1e2552b5bfacff730d064415b92"),
}
BUILD_METADATA = {"minSdkVersion": 24, "targetSdkVersion": 28, "compileSdkVersion": 30}
BUILD_METADATA_SOURCE = {
    "upstreamPath": "gradle.properties",
    "gitBlobSha1": "c8f9ae0514310cf33a32ff0b2ce01ef55ea2461e",
    "sha256": "2b2ea08b383b5a9689d01d62a5997baab7e29f6bba63ffc9a2dede46a4bcd933",
    "url": REPOSITORY + "/blob/" + COMMIT + "/gradle.properties",
}
RESOURCE_TREES = {
    "drawable": {"gitTreeSha1": "4668147f88f807ed7a04b8cc41a322e7c2b05b11",
                 "paths": ["text_select_handle_left_material.xml", "text_select_handle_right_material.xml"]},
    "values": {"gitTreeSha1": "23a894c7c7912ee29b38d6a3d5de6f5c99ffbede", "paths": ["strings.xml"]},
}
EXPECTED_STRINGS = {"copy_text": "Copy", "paste_text": "Paste", "text_selection_more": "More…"}
RESOURCE_PATHS = frozenset(n for n in PINS if n.startswith("res/"))
EXPECTED_AAR_RESOURCES = (RESOURCE_PATHS - {"res/values/strings.xml"}) | {"res/values/values.xml"}
EXPECTED_R = frozenset((
    "int drawable text_select_handle_left_material 0x0",
    "int drawable text_select_handle_right_material 0x0",
    "int string copy_text 0x0", "int string paste_text 0x0", "int string text_selection_more 0x0",
))


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def git_blob(raw):
    return hashlib.sha1(b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()


def checked(path):
    path = Path(os.path.abspath(path))
    require(not any(item.is_symlink() for item in (path, *path.parents)), "symlinked protected path: " + str(path))
    return path


def read_regular(path, bound=MAX_SOURCE_BYTES):
    path = checked(path)
    info = path.stat()
    require(stat.S_ISREG(info.st_mode), "expected regular source file: " + str(path))
    require(info.st_size <= bound, "input exceeds byte bound: " + str(path))
    with path.open("rb") as stream:
        raw = stream.read(bound + 1)
    require(len(raw) <= bound, "input grew beyond byte bound: " + str(path))
    return raw


def parse_json(raw):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            require(name not in result, "duplicate provenance JSON key: " + name)
            result[name] = value
        return result
    result = json.loads(raw, object_pairs_hook=unique)
    require(isinstance(result, dict), "provenance must be a JSON object")
    return result


def upstream_path(local):
    return "LICENSE.md" if local == "LICENSE.md" else "terminal-view/src/main/" + local


def validate_provenance(manifest):
    allowed = {"schemaVersion", "upstream", "files", "buildMetadata", "referenceAar",
               "buildMetadataSource", "resourceTrees", "notes"}
    require(set(manifest) <= allowed, "unexpected provenance field")
    require(type(manifest.get("schemaVersion")) is int and manifest["schemaVersion"] == 1, "provenance schema changed")
    require(manifest.get("upstream") == {"repository": REPOSITORY, "commit": COMMIT, "tag": TAG}, "immutable upstream identity changed")
    expected = {}
    for local, (digest, blob) in PINS.items():
        remote = upstream_path(local)
        expected[local] = {"upstreamPath": remote, "gitBlobSha1": blob, "sha256": digest,
                           "url": REPOSITORY + "/blob/" + COMMIT + "/" + remote}
    require(manifest.get("files") == expected, "source provenance file mappings/pins changed")
    require(manifest.get("buildMetadata") == BUILD_METADATA, "upstream Android SDK metadata changed")
    require(manifest.get("buildMetadataSource") == BUILD_METADATA_SOURCE, "immutable SDK metadata provenance changed")
    require(manifest.get("resourceTrees") == RESOURCE_TREES, "immutable resource tree provenance changed")
    if "referenceAar" in manifest:
        require(manifest["referenceAar"] == {"path": AAR_RELATIVE, "sha256": AAR_SHA256}, "reference AAR metadata changed")


def xml(raw):
    require(len(raw) <= MAX_SOURCE_BYTES, "XML exceeds byte bound")
    upper = raw.upper()
    require(b"<!DOCTYPE" not in upper and b"<!ENTITY" not in upper, "XML declarations/entities are forbidden")
    try:
        return ET.fromstring(raw)
    except ET.ParseError as error:
        raise ValueError("invalid source/resource XML: " + str(error)) from error


def strings(raw):
    root = xml(raw)
    require(root.tag == "resources" and not root.attrib and not (root.text or "").strip(), "unexpected string resource root")
    result = {}
    for child in root:
        require(child.tag == "string" and set(child.attrib) == {"name"} and not list(child), "unexpected string resource declaration")
        name = child.attrib["name"]
        require(name not in result, "duplicate string resource: " + name)
        require(not (child.tail or "").strip(), "unexpected text outside string resource")
        result[name] = child.text or ""
    return result


def manifest_source(raw):
    root = xml(raw)
    require(root.tag == "manifest" and root.attrib == {"package": "com.termux.view"} and not list(root) and
            not (root.text or "").strip(), "source manifest differs from package-only upstream declaration")


def manifest_reference(raw):
    root = xml(raw)
    require(root.tag == "manifest" and root.attrib == {"package": "com.termux.view"} and
            not (root.text or "").strip(), "reference manifest identity/content changed")
    children = list(root)
    expected = {ANDROID_NS + "minSdkVersion": "24", ANDROID_NS + "targetSdkVersion": "28"}
    require(len(children) == 1 and children[0].tag == "uses-sdk" and children[0].attrib == expected and
            not list(children[0]) and not (children[0].text or "").strip() and not (children[0].tail or "").strip(),
            "reference manifest must add only the pinned upstream uses-sdk declaration")


def source_inputs(root):
    base = checked(checked(root) / SOURCE_RELATIVE)
    require(base.is_dir(), "vendored view source directory is missing")
    actual = set()
    for path in base.rglob("*"):
        checked(path)
        if path.is_dir():
            continue
        require(path.is_file(), "nonregular entry in vendored view sources")
        actual.add(path.relative_to(base).as_posix())
    require(actual == set(PINS) | {"PROVENANCE.json"}, "vendored view source inventory changed")
    manifest_raw = read_regular(base / "PROVENANCE.json")
    manifest = parse_json(manifest_raw)
    validate_provenance(manifest)
    sources = {}
    for name, (digest, blob) in PINS.items():
        raw = read_regular(base / name)
        require(sha256(raw) == digest, "vendored source SHA-256 mismatch: " + name)
        require(git_blob(raw) == blob, "vendored source Git blob mismatch: " + name)
        sources[name] = raw
    manifest_source(sources["AndroidManifest.xml"])
    require(strings(sources["res/values/strings.xml"]) == EXPECTED_STRINGS, "upstream string values changed")
    for name in RESOURCE_PATHS - {"res/values/strings.xml"}:
        require(xml(sources[name]).tag == "vector", "expected vector resource: " + name)
    return sources, sha256(manifest_raw)


def aar_resource_entries(raw):
    """Inspect bounded ZIP data without extracting or loading classes.jar."""
    require(len(raw) <= MAX_AAR_BYTES, "reference AAR exceeds byte bound")
    selected, resource_names, seen, total = {}, set(), set(), 0
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            for entry in archive.infolist():
                name = entry.filename
                parts = name[:-1].split("/") if name.endswith("/") else name.split("/")
                require(name and name not in seen and "\\" not in name and ":" not in name and "\0" not in name and
                        not PurePosixPath(name).is_absolute() and all(part not in ("", ".", "..") for part in parts),
                        "unsafe/duplicate reference ZIP entry")
                seen.add(name)
                require(not entry.flag_bits & 1 and stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG, stat.S_IFDIR),
                        "encrypted/nonregular reference ZIP entry")
                total += entry.file_size
                require(entry.file_size <= MAX_AAR_BYTES and total <= 2 * MAX_AAR_BYTES, "reference ZIP expansion exceeds bound")
                if entry.is_dir():
                    continue
                if name.startswith("res/"):
                    resource_names.add(name)
                if name.startswith("res/") or name in ("AndroidManifest.xml", "R.txt"):
                    selected[name] = archive.read(entry)
    except (zipfile.BadZipFile, RuntimeError) as error:
        raise ValueError("invalid reference AAR: " + str(error)) from error
    require(resource_names == EXPECTED_AAR_RESOURCES, "reference resource inventory changed")
    require({"AndroidManifest.xml", "R.txt"} <= set(selected), "reference manifest/resource symbols missing")
    return selected


def compare_reference(path, sources):
    raw = read_regular(path, MAX_AAR_BYTES)
    require(sha256(raw) == AAR_SHA256, "explicit reference AAR SHA-256 mismatch")
    entries = aar_resource_entries(raw)
    manifest_reference(entries["AndroidManifest.xml"])
    for name in RESOURCE_PATHS - {"res/values/strings.xml"}:
        require(entries[name] == sources[name], "reference drawable is not byte-identical to source: " + name)
    source_strings = strings(sources["res/values/strings.xml"])
    require(strings(entries["res/values/values.xml"]) == source_strings, "reference string values differ from source")
    symbols = entries["R.txt"].decode("utf-8").splitlines()
    require(len(symbols) == len(EXPECTED_R) and set(symbols) == EXPECTED_R, "reference resource symbols changed")
    return {"referenceAarSha256": sha256(raw), "drawablesByteIdentical": True, "stringsXmlEquivalent": True,
            "stringsByteIdentical": sources["res/values/strings.xml"] == entries["res/values/values.xml"],
            "manifestByteIdentical": sources["AndroidManifest.xml"] == entries["AndroidManifest.xml"],
            "manifestAddsOnlyPinnedUsesSdk": True, "resourceSymbolCount": len(symbols),
            "referenceUsedAsBuildInput": False, "classesLoadedOrExecuted": False}


def verify(root=ROOT, reference_aar=None):
    sources, provenance_sha256 = source_inputs(root)
    report = {"status": "TERMUX_VIEW_TEXT_SOURCES_VERIFIED", "scope": "manifest, three resources, and exact upstream license only",
              "upstreamCommit": COMMIT, "sourceManifestAndXmlCount": 4, "resourceCount": 3,
              "sourceSha256ByPath": {name: sha256(raw) for name, raw in sorted(sources.items())},
              "provenanceSha256": provenance_sha256, "sourceOnlyCheckRequiresReferenceBinary": False,
              "referenceComparisonPerformed": reference_aar is not None,
              "productionDependenciesChanged": False, "compilationPerformed": False,
              "androidResourcesGenerated": False, "deviceValidated": False, "fdroidAcceptanceVerified": False}
    if reference_aar is not None:
        report["referenceComparison"] = compare_reference(reference_aar, sources)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="source checkout containing vendored text sources")
    parser.add_argument("--compare-reference-aar", type=Path,
                        help="optional explicit pinned AAR to inspect; omitted for normal source-only validation")
    args = parser.parse_args(argv)
    try:
        result = verify(args.root, args.compare_reference_aar)
    except (ValueError, OSError, UnicodeError) as error:
        print("TERMUX_VIEW_TEXT_SOURCES_FAILED: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
