"""Generate distribution-specific notices while retaining every upstream license text."""
import argparse
import json
from pathlib import Path

from generate_resolved_runtime_licenses import classify


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode()


def overrides(root, native):
    legal = root / "app/src/main/assets/legal"
    read = lambda name: json.loads((legal / name).read_text())
    resolved = read("resolved_runtime_licenses.json")
    coordinates = sorted(line.split("=", 1)[0] for line in (root / "app/gradle-fdroid.lockfile").read_text().splitlines()
                         if "=" in line and "releaseRuntimeClasspath" in line.split("=", 1)[1].split(","))
    xz = {"coordinate": "org.tukaani:xz:1.12", "spdx": "0BSD", "licenseTextAsset": "legal/licenses/0BSD.txt",
          "source": "https://tukaani.org/xz/java.html"}
    resolved["components"] = [xz if value == xz["coordinate"] else classify(value) for value in coordinates]
    resolved["scope"] = "F-Droid app releaseRuntimeClasspath from app/gradle-fdroid.lockfile; source components are in the direct catalog"
    direct = read("open_source_licenses.json")
    source_components = {
        "jgit": "third_party/jgit-complete/manifest.json",
        "kadb-compat": "third_party/kadb/mdns-source.json",
        "termux-terminal": "third_party/termux/source-build.json",
        "spake2-boringssl-compat": "third_party/kadb/spake2-boringssl-compat/",
        "proot-runtime": "third_party/native-engines/source-build/manifest.json",
        "talloc-runtime": "third_party/native-engines/source-build/manifest.json",
    }
    for entry in direct["entries"]:
        if entry["id"] in source_components:
            entry["source"] = source_components[entry["id"]]
            entry["notice"] = "Built from corresponding source in this distribution. Upstream licenses and all notices below remain applicable."
        entry["dependencyKeys"] = [c for c in entry.get("dependencyKeys", []) if not c.startswith(("com.baystudio.compat:", "local:terminal-"))]
        if entry["id"] == "xz-java":
            entry["dependencyKeys"] = [xz["coordinate"]]
        if "Ubuntu" in entry["name"]:
            entry["notice"] = "Optional Ubuntu Base download from Canonical after user consent; not embedded in this APK. Original package licenses remain available below."
    direct["closureNote"] = "Source-built components and locked F-Droid runtime dependencies; optional Ubuntu is downloaded only after consent."
    supplemental = read("supplemental_licenses.json")
    supplemental["entries"] = [e for e in supplemental["entries"] if not e["id"].startswith("resolved:com.baystudio.compat:")]
    for entry in supplemental["entries"]:
        if entry["id"].startswith(("ubuntu:", "ubuntu-common:")):
            entry["notice"] = "Original notice for the optional Canonical Ubuntu Base download. Inclusion of this license text does not mean Ubuntu is installed."
    supplemental["closureNote"] = "Locked F-Droid dependencies, source-built components, and retained notices for optional downloads."
    runtime = read("runtime_legal_inventory.json")
    receipt = json.loads((native / "receipt.json").read_text())
    runtime["runtimeFiles"] = [{"path": "lib/arm64-v8a/" + name, "sha256": digest,
        "bytes": (native / "jniLibs/arm64-v8a" / name).stat().st_size,
        "component": "Droide CLI" if name == "libdroide_cli.so" else "PRoot runtime / loader",
        "license": "LGPL-3.0-only" if name == "libdroide_cli.so" else "GPL-2.0-or-later"}
        for name, digest in sorted(receipt["nativeLibraries"].items())]
    runtime["optionalDownloads"] = runtime.pop("aggregates")
    runtime["aggregates"] = {}
    runtime["sourceArchives"] = []
    runtime["sourceBuildInputs"] = [{"path": path, "sha256": digest} for path, digest in sorted(receipt["sourceInputs"].items())]
    runtime["policy"] = "Linux providers are compiled from source; talloc retains LGPL-3.0-or-later. Ubuntu is an optional Canonical download, not an embedded executable asset."
    notices = """# Third-party notices — F-Droid distribution

All upstream copyright notices and full license texts remain available in About & Legal.
Droide-owned code remains LGPL-3.0-only; third-party code retains its original terms.

The terminal emulator and view are compiled entirely from Termux v0.118.0 source with
Droide's existing terminal patches. PRoot/OpenMinis, talloc, Termux PRoot r70 loaders,
and the Droide CLI are compiled from corresponding source with the Android NDK.
The exact build inputs and native hashes are recorded in runtime_legal_inventory.json.

The complete JGit Android derivative, Kadb mDNS and SPAKE2 compatibility code are built
from source. Existing Android adaptations and all original license texts are retained.
Other runtime modules use pinned Google Maven or Maven Central artifacts. The complete
locked module inventory is resolved_runtime_licenses.json.

Ubuntu Base is downloaded from Canonical only after the user chooses Install environment.
It is not embedded in this APK. Original Ubuntu package notices are retained for offline
inspection. Optional external tools and cloud services keep their own terms; their presence
in the catalog does not imply installation or review by F-Droid.

Source-build recipes and provenance are under third_party/ and tools/. All historical
source offers and previously granted license rights remain valid. See COPYLEFT_SOURCE_OFFER.md
and the complete upstream license texts. A candidate build is not F-Droid acceptance.
"""
    result = {"legal/" + name: encoded(value) for name, value in {
        "resolved_runtime_licenses.json": resolved, "open_source_licenses.json": direct,
        "supplemental_licenses.json": supplemental, "runtime_legal_inventory.json": runtime}.items()}
    result["legal/THIRD_PARTY_NOTICES.md"] = notices.encode()
    for entry in direct["entries"] + supplemental["entries"] + resolved["components"]:
        for asset in [entry["licenseTextAsset"], *entry.get("noticeTextAssets", [])]:
            if not asset.startswith("legal/licenses/") or ".." in Path(asset).parts or not (root / "app/src/main/assets" / asset).is_file():
                raise ValueError("Missing or unsafe license text: " + asset)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("root", "native", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    for name, data in overrides(args.root, args.native).items():
        path = args.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
