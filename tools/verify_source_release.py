#!/usr/bin/env python3
"""Verify a current-source release candidate without claiming device certification."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile

from current_native_jni_guard import required_methods, verify_library
from termux_native_elf import inspect as inspect_termux
from verify_pty_dex_binding import DEX_NAME, Dex

ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
INPUT_CHECKS = (
    "verify_first_party_license.py", "verify_dependency_policy.py",
    "verify_resolved_runtime_licenses.py", "verify_runtime_licenses.py",
    "verify_third_party_integrity.py", "verify_bundled_runtime.py",
    "verify_artifact_transfer_manifest.py",
)
VERIFIER_SOURCES = (
    "verify_source_release.py", "current_native_jni_guard.py",
    "termux_native_elf.py", "verify_pty_dex_binding.py",
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verifier_sources(root):
    sources = {}
    for name in VERIFIER_SOURCES:
        path = root / "tools" / name
        require(path.is_file() and path.stat().st_size > 0,
                "Missing or empty release verifier source: " + name)
        sources[name] = sha(path.read_bytes())
    return sources


def run(command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=180)
    require(result.returncode == 0, "Check failed: " + str(command[0]) + "\n" + result.stdout + result.stderr)
    return result.stdout


def native_contract(root):
    contract = json.loads((root / "tools/termux-native-v1.json").read_text())


    owned = json.loads((root / "third_party/termux/native/OWNED_PROCESS_ABI.json").read_text())
    require(owned["ownedProcessApiVersion"] == contract["ownedProcessApiVersion"] == 2, "Terminal ABI changed")
    for path, digest in owned["sourceSha256ByPath"].items():
        require(sha((root / path).read_bytes()) == digest, "Owned terminal source contract changed: " + path)
    require(owned["registeredNativeMethods"] == [{k: row[k] for k in ("name", "descriptor")}
                                               for row in contract["methods"]], "Terminal method specification differs")
    table = re.findall(r'\{ "(\w+)", "([^"]+)", \(void\*\) (\w+) \}',
                       (root / "third_party/termux/native/termux.c").read_text())
    require(table == [(row["name"], row["descriptor"], row["function"]) for row in contract["methods"]],
            "Terminal C registration differs from its ABI")
    return contract


def verify_inputs(root):
    verifier_sources(root)
    native_contract(root)
    upstream = json.loads((root / "third_party/termux/UPSTREAM_PROVENANCE.json").read_text())
    for artifact in upstream["artifacts"].values():
        require(sha((root / artifact["path"]).read_bytes()) == artifact["sha256"],
                "Upstream terminal input changed: " + artifact["path"])
    license_text = (root / "app/src/main/assets/legal/licenses/Termux-upstream-LICENSE.md").read_bytes()
    blob = hashlib.sha1(f"blob {len(license_text)}\0".encode() + license_text).hexdigest()
    require(blob == upstream["upstream"]["rootLicenseGitBlobSha1"], "Original terminal license changed")
    for name in INPUT_CHECKS:
        print(run(["python3", "-B", root / "tools" / name]).strip())
    print(run(["python3", "-B", root / "tools/generate_legal_browser_catalog.py", "--check"]).strip())
    print("SOURCE_RELEASE_INPUTS_VERIFIED (device validation remains separate)")


def native_methods_from_dex(entries):
    """Read native declarations, independent of R8's optimized debug positions."""
    target = "Lcom/termux/terminal/JNI;"
    require("classes.dex" in entries and all(DEX_NAME.fullmatch(name) for name in entries),
            "Unexpected APK DEX names")
    matches = []
    for name, data in sorted(entries.items()):
        dex = Dex(data)
        if target not in dex.classes:
            continue
        class_data = dex.classes[target][6]
        require(class_data != 0, "JNI class has no declarations")
        end = dex.section(class_data, 0x2000)
        position, counts = class_data, []
        for _ in range(4):
            count, position = dex.leb(position, end=end)
            counts.append(count)
        seen_fields, seen_methods, methods = set(), set(), []
        for group, count in enumerate(counts):
            pool = dex.fields if group < 2 else dex.methods
            seen = seen_fields if group < 2 else seen_methods
            require(count <= len(pool), "Invalid JNI member count")
            index = 0
            for member_number in range(count):
                delta, position = dex.leb(position, end=end)
                require(member_number == 0 or delta > 0, "Duplicate/unsorted JNI declaration")
                index += delta
                flags, position = dex.leb(position, end=end)
                member = dex.ref(pool, index)
                require(member[0] == target and index not in seen, "Invalid/duplicate JNI member owner")
                seen.add(index)
                if group < 2:
                    require(bool(flags & 8) == (group == 0), "JNI field classification mismatch")
                    continue
                code, position = dex.leb(position, end=end)
                require(bool(code) == (not bool(flags & (0x100 | 0x400))), "JNI method code/access mismatch")
                if flags & 0x100:
                    require(group == 2 and flags & 8 and not flags & 0x400,
                            "JNI method must be static native")
                    returns, arguments = member[2]
                    methods.append({"name": member[1], "descriptor": "(" + "".join(arguments) + ")" + returns})
                elif code:
                    code_end = dex.section(code, 0x2001, 4)
                    require(code + 16 <= code_end and code + 16 + 2 * dex.u32(code + 12) <= code_end,
                            "Truncated JNI class code item")
        matches.append((name, sorted(methods, key=lambda method: method["name"])))
    require(len(matches) == 1 and matches[0][1], "JNI class must have one definition and native declarations")
    return matches[0]


FDROID_EXCLUDED_ASSET = "assets/workstation/ubuntu-base-24.04.5-base-arm64.tar.xz"


def verify_asset_payloads(root, archive, fdroid_build=False):
    """Allow exactly one omitted rootfs in F-Droid builds; verify all other assets byte-for-byte."""
    names = set(archive.namelist())
    if fdroid_build:
        require(FDROID_EXCLUDED_ASSET not in names, "F-Droid APK embeds the opt-in Ubuntu executable archive")
    asset_count = 0
    for source in (root / "app/src/main/assets").rglob("*"):
        if source.is_file():
            name = "assets/" + source.relative_to(root / "app/src/main/assets").as_posix()
            if fdroid_build and name == FDROID_EXCLUDED_ASSET:
                continue
            require(name in names and archive.read(name) == source.read_bytes(),
                    "Packaged asset missing/changed: " + name)
            asset_count += 1
    return asset_count


def verify_apk(root, build, engine, sdk, signing, fdroid_build=False):
    output = build / "outputs/apk/release"
    candidates = list(output.glob("*.apk"))
    require(len(candidates) == 1, "Expected exactly one release APK; remove stale build outputs")
    apk = candidates[0]
    receipt = json.loads((engine / "receipt.json").read_text())
    require(receipt["status"] == "BUILD_ONLY_DEVICE_UNVALIDATED" and receipt["deviceValidated"] is False,
            "Engine receipt must retain its measured build-only scope")
    require(sha((engine / "terminal-engine.aar").read_bytes()) == receipt["inspection"]["aarSha256"],
            "Engine AAR changed after its independent builds")
    for path, digest in receipt["sourceInputs"].items():
        require(sha((root / path).read_bytes()) == digest, "Engine source changed: " + path)
    contract = native_contract(root)
    expected_methods = sorted(receipt["inspection"]["jniMethods"], key=lambda method: method["name"])
    require(expected_methods == sorted(({k: row[k] for k in ("name", "descriptor")}
                                       for row in contract["methods"]), key=lambda method: method["name"]),
            "Java/native registration contracts differ")
    filesystem_methods = required_methods(
        (root / "app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt").read_text())
    libraries, registrations = {}, {}
    asset_count = 0
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and archive.testzip() is None, "Malformed APK archive")
        dex = {name: archive.read(name) for name in names if name.endswith(".dex")}
        dex_entry, packaged_methods = native_methods_from_dex(dex)
        require(packaged_methods == expected_methods, "R8 changed the packaged JNI class/native method binding")
        for abi in ABIS:
            for library in ("libtermux.so", "libdroide_files.so"):
                name = f"lib/{abi}/{library}"
                data = archive.read(name)
                compiled = [p for p in (build / "intermediates/cxx").rglob(library) if p.parent.name == abi]
                require(compiled and {sha(p.read_bytes()) for p in compiled} == {sha(data)},
                        "Packaged native library differs from current CMake output: " + name)
                if library == "libtermux.so":
                    registrations[abi] = inspect_termux(data, abi, contract)
                else:
                    verify_library(data, abi, filesystem_methods)
                libraries[name] = sha(data)
        for source in (root / "app/src/main/jniLibs").rglob("*.so"):
            name = "lib/" + source.relative_to(root / "app/src/main/jniLibs").as_posix()
            require(archive.read(name) == source.read_bytes(), "Bundled runtime library changed: " + name)
            libraries[name] = sha(source.read_bytes())
        asset_count = verify_asset_payloads(root, archive, fdroid_build=fdroid_build)
        if signing == "unsigned":
            require(not any(re.fullmatch(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)", name, re.I) for name in names),
                    "Unsigned output unexpectedly contains signing material")
            require(b"APK Sig Block 42" not in apk.read_bytes(), "Unsigned output unexpectedly has an APK signing block")
    tools = sdk / "build-tools/36.0.0"
    badging = run([tools / "aapt2", "dump", "badging", apk])
    require("application-debuggable" not in badging, "Release APK is debuggable")
    require("package: name='com.baystudio.droide'" in badging, "Unexpected application ID")
    require(re.search(r"^(?:minSdkVersion|sdkVersion):'29'$", badging, re.MULTILINE) and
            re.search(r"^targetSdkVersion:'36'$", badging, re.MULTILINE), "Unexpected Android SDK range")
    require((build / "outputs/mapping/release/mapping.txt").is_file(), "R8 mapping missing")
    checks = {"badging": badging, "alignment": run([tools / "zipalign", "-c", "-P", "16", "4", apk])}
    if signing == "signed":
        checks["signature"] = run([tools / "apksigner", "verify", "--verbose", "--print-certs", apk])
        require("CN=Android Debug" not in checks["signature"], "Debug certificate is not a release signer")
    result = {
        "status": "RELEASE_VARIANT_BUILD_VERIFIED", "apk": str(apk),
        "distribution": "fdroid" if fdroid_build else "regular",
        "omittedAssets": [FDROID_EXCLUDED_ASSET] if fdroid_build else [],
        "apkSha256": sha(apk.read_bytes()), "apkBytes": apk.stat().st_size,
        "signing": signing, "deviceValidated": False, "publicationApproved": False,
        "engineAarSha256": receipt["inspection"]["aarSha256"],
        "jniDexEntry": dex_entry, "jniMethods": packaged_methods,
        "nativeLibraries": libraries, "nativeRegistration": registrations,
        "packagedAssetsVerified": asset_count, "checks": checks,
        "nativeSources": {str(p.relative_to(root)): sha(p.read_bytes()) for p in (
            root / "third_party/termux/native/termux.c", root / "app/src/main/cpp/droide_files.c",
            root / "app/src/main/cpp/CMakeLists.txt")},
        "verifierSources": verifier_sources(root),
    }
    (build / "outputs/source-release-verification.json").write_text(json.dumps(result, indent=2) + "\n")
    print("SOURCE_RELEASE_APK_VERIFIED", signing, result["apkSha256"], apk)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("inputs", "apk"))
    parser.add_argument("--root", required=True, type=Path)
    for name in ("build-dir", "engine", "sdk"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--signing", choices=("unsigned", "signed"))
    parser.add_argument("--fdroid-build", action="store_true")
    args = parser.parse_args()
    if args.action == "inputs":
        verify_inputs(args.root.resolve())
    else:
        require(all((args.build_dir, args.engine, args.sdk, args.signing)), "APK verification arguments missing")
        verify_apk(args.root.resolve(), args.build_dir.resolve(), args.engine.resolve(), args.sdk.resolve(),
                   args.signing, fdroid_build=args.fdroid_build)
