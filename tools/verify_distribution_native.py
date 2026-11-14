"""Bind packaged Linux providers to their complete-source build receipts."""
import hashlib
import json
import re


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_native(root, build, archive, source_build=None):
    providers = root / "app/src/main/jniLibs"
    receipt = None
    if source_build is not None:
        receipt = json.loads((source_build / "receipt.json").read_text())
        require(receipt.get("fullSourceRebuild") is True and receipt.get("binaryBaseRetained") is False,
                "Linux providers must be built entirely from source")
        require(receipt.get("independentBuilds") == 2, "Independent Linux source builds are missing")
        for path, digest in receipt["sourceInputs"].items():
            require(sha((root / path).read_bytes()) == digest, "Linux source input changed: " + path)
        providers = source_build / "jniLibs"
        expected = receipt["nativeLibraries"]
        require(set(expected) == {"libdroide_proot.so", "libdroide_cli.so", "libproot-loader.so", "libproot-loader32.so"},
                "Unexpected Linux provider inventory")
        contracts = list((build / "generated/source/buildConfig").rglob("BuildConfig.java"))
        require(len(contracts) == 1, "Missing or ambiguous generated native identity contract")
        contract = contracts[0].read_text()
        require('FDROID_NATIVE_SOURCE = true;' in contract, "Source-built native admission was not enabled")
        for field, library in {"PROOT": "libdroide_proot.so", "CLI": "libdroide_cli.so",
                               "PROOT_LOADER": "libproot-loader.so", "PROOT_LOADER32": "libproot-loader32.so"}.items():
            require(re.search(r'SOURCE_' + field + r'_SHA256 = "' + expected[library] + r'";', contract),
                    "Generated runtime hash differs from compiled native library: " + library)
        require({p.relative_to(providers).as_posix() for p in providers.rglob("*.so")} ==
                {"arm64-v8a/" + name for name in expected}, "Unexpected compiled native ABI inventory")
    result = {}
    for source in sorted(providers.rglob("*.so")):
        name = "lib/" + source.relative_to(providers).as_posix()
        digest = sha(source.read_bytes())
        if receipt:
            require(digest == receipt["nativeLibraries"][source.name], "Compiled Linux provider changed: " + name)
        require(archive.read(name) == source.read_bytes(), "Packaged Linux provider differs from its build: " + name)
        result[name] = digest
    return result
