#!/usr/bin/env python3
"""Build/check the offline legal browser from shipped dependency/runtime inventories."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
LEGAL = ASSETS / "legal"


def entry(identity, name, version, license_name, spdx, asset, source, notice):
    return dict(id=identity, name=name, version=version, licenseName=license_name,
                spdx=spdx, copyright="See the retained upstream copyright notices below.",
                licenseTextAsset=asset, source=source, notice=notice, dependencyKeys=[])


def build():
    direct = json.loads((LEGAL / "open_source_licenses.json").read_text())
    resolved = json.loads((LEGAL / "resolved_runtime_licenses.json").read_text())
    runtime = json.loads((LEGAL / "runtime_legal_inventory.json").read_text())
    entries, outputs = [], {}
    linked = {path for e in direct["entries"]
              for path in [e["licenseTextAsset"], *e.get("noticeTextAssets", [])]}
    notices_by_coordinate = {coordinate: e.get("noticeTextAssets", [])
                             for e in direct["entries"] for coordinate in e.get("dependencyKeys", [])}
    for item in resolved["components"]:
        coordinate = item["coordinate"]
        group, name, version = coordinate.split(":")
        e = entry("resolved:" + coordinate, name + " (" + group + ")", version,
                  item["spdx"], item["spdx"], item["licenseTextAsset"], item["source"],
                  "Resolved application dependency: " + coordinate)
        notices = list(notices_by_coordinate.get(coordinate, []))
        notice = e["licenseTextAsset"].replace("-LICENSE.txt", "-NOTICE.txt")
        if notice != e["licenseTextAsset"] and (ASSETS / notice).is_file():
            notices.append(notice)
        if notices:
            e["noticeTextAssets"] = list(dict.fromkeys(notices))
        entries.append(e)
        linked.update([e["licenseTextAsset"], *e.get("noticeTextAssets", [])])


    for path in sorted((LEGAL / "licenses").iterdir()):
        asset = path.relative_to(ASSETS).as_posix()
        if not path.is_file() or path.suffix not in {".txt", ".md", ".html"} or asset in linked:
            continue
        entries.append(entry("notice:" + path.name, path.stem, "Packaged notice",
                             "Upstream license / notice", "LicenseRef-Upstream-Notice", asset,
                             "Packaged source and attribution in Third-Party Notices",
                             "Retained license, attribution or source-availability notice. "
                             "Some notices concern optional downloadable components or historical versions; "
                             "their inclusion does not mean those components are installed."))

    rootfs = next(r for r in runtime["runtimeFiles"] if r["component"] == "Ubuntu Base aggregate")
    archive = ROOT / rootfs["path"]
    if hashlib.sha256(archive.read_bytes()).hexdigest() != rootfs["sha256"]:
        raise ValueError("Ubuntu runtime does not match its license inventory")
    with tarfile.open(archive, "r:*") as tf:
        members = {m.name.removeprefix("./"): m for m in tf.getmembers()}
        def copy_notice(name):
            member = members[name]
            if not member.isfile() or not 0 < member.size <= 4 * 1024 * 1024:
                raise ValueError("Invalid runtime notice: " + name)
            asset = "legal/licenses/ubuntu/" + name
            with tf.extractfile(member) as stream:
                outputs[ASSETS / asset] = stream.read()
            return asset
        for pkg in runtime["aggregates"]["ubuntuBase"]["packages"]:
            asset = copy_notice(pkg["copyrightNoticePath"])
            entries.append(entry("ubuntu:" + pkg["name"], "Ubuntu / " + pkg["name"],
                                 pkg["version"], "Debian copyright and licenses",
                                 "LicenseRef-Debian-Copyright", asset, pkg["sourcePage"],
                                 "Original notice from the bundled Ubuntu Base image. References to "
                                 "/usr/share/common-licenses are available in this catalog as Ubuntu common licenses."))
        for name, member in sorted(members.items()):
            if name.startswith("usr/share/common-licenses/") and member.isfile():
                asset = copy_notice(name)
                entries.append(entry("ubuntu-common:" + Path(name).name,
                                     "Ubuntu common license / " + Path(name).name,
                                     "Bundled Ubuntu Base", Path(name).name,
                                     "LicenseRef-Upstream-Text", asset,
                                     "https://www.debian.org/legal/licenses/",
                                     "Full license text referenced by bundled Ubuntu package notices."))
    ids = [e["id"] for e in direct["entries"] + entries]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate legal browser identity")
    for e in direct["entries"] + entries:
        for asset in [e["licenseTextAsset"], *e.get("noticeTextAssets", [])]:
            if not asset.startswith("legal/licenses/") or ".." in Path(asset).parts:
                raise ValueError("Invalid legal asset: " + asset)
            path = ASSETS / asset
            if not outputs.get(path) and not (path.is_file() and path.stat().st_size):
                raise ValueError("Missing legal text: " + asset)
    manifest = dict(schemaVersion=1, closureStatus="OFFLINE_BROWSER_INVENTORY",
                    closureNote="Resolved dependencies, retained notices and original bundled Ubuntu copyrights. "
                    "This catalog does not certify release readiness or source-offer fulfillment.", entries=entries)
    outputs[LEGAL / "supplemental_licenses.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    return outputs, len(direct["entries"]) + len(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs, count = build()
    for path, data in outputs.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                raise SystemExit("LEGAL_BROWSER_STALE: " + str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    actual = {p for p in (LEGAL / "licenses/ubuntu").rglob("*") if p.is_file()}
    expected = {p for p in outputs if p != LEGAL / "supplemental_licenses.json"}
    if actual != expected:
        raise SystemExit("LEGAL_BROWSER_STALE: obsolete Ubuntu notice files")
    print(f"LEGAL_BROWSER_OK entries={count} original_runtime_texts={len(expected)}")


if __name__ == "__main__":
    main()
