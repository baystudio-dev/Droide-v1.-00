"""Verify the complete-source distribution without requiring unused legacy binaries."""
import argparse
import hashlib
import json
from pathlib import Path

from verify_first_party_license import verify as verify_license
from verify_source_release import native_contract
import verify_third_party_integrity as third_party


def verify(root):
    verify_license(root)
    native_contract(root)
    expected = third_party.parse_manifest()
    actual = {p.relative_to(root).as_posix(): third_party.sha256(p) for p in third_party.tracked_files()}
    exported = root / "FDROID-SOURCE-MANIFEST.json"
    exclusions = json.loads(exported.read_text())["excluded"] if exported.exists() else {}
    for path, digest in expected.items():
        if path not in actual and exclusions.get(path, {}).get("sha256") == digest:
            continue
        if actual.get(path) != digest:
            raise ValueError("Third-party source identity missing or changed: " + path)
    if set(actual) - set(expected):
        raise ValueError("Unregistered third-party source inputs")
    # Java/native builders separately recompile and validate their own manifests.
    manifest = json.loads((root / "third_party/kadb/mdns-source.json").read_text())
    for path, expected in manifest["sourceSha256"].items():
        if hashlib.sha256((root / path).read_bytes()).hexdigest() != expected:
            raise ValueError("mDNS source changed: " + path)
    coordinates = [line.split("=", 1)[0] for line in (root / "app/gradle-fdroid.lockfile").read_text().splitlines()
                   if "=" in line and "releaseRuntimeClasspath" in line.split("=", 1)[1].split(",")]
    if any(c.startswith(("com.baystudio.compat:", "org.eclipse.jgit:org.eclipse.jgit:")) for c in coordinates):
        raise ValueError("Binary compatibility artifacts must not enter the F-Droid runtime")
    print("FDROID_SOURCE_INPUTS_VERIFIED (not F-Droid acceptance or device validation)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    verify(parser.parse_args().root.resolve())
