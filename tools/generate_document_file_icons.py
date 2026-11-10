#!/usr/bin/env python3
"""Shape-preserving container conversion for two pinned single-path SVG file icons."""
from pathlib import Path
import argparse
import hashlib
import json
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/material-document-icons"


def vector_bytes(asset):
    source = SOURCE / asset["sourceFile"]
    data = source.read_bytes()
    if len(data) != asset["sourceBytes"] or hashlib.sha256(data).hexdigest() != asset["sourceSha256"]:
        raise ValueError(f"Changed upstream asset: {source.name}")
    svg = ET.fromstring(data)
    assert svg.tag == "{http://www.w3.org/2000/svg}svg"
    assert svg.attrib == {"viewBox": "0 0 24 24"}
    assert len(svg) == 1 and svg[0].tag == "{http://www.w3.org/2000/svg}path"
    path = svg[0]
    assert set(path.attrib) == {"d", "fill"} and len(path) == 0
    assert path.attrib["fill"] == asset["sourceFill"]
    return ("""<?xml version="1.0" encoding="utf-8"?>
<!-- Material Icon Theme, MIT. See third_party/material-document-icons/manifest.json.
     Upstream path data is unchanged. Word fill is adjusted for dark/light contrast. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor=""" + quoteattr(asset["androidFill"]) + """
        android:fillType="nonZero"
        android:pathData=""" + quoteattr(path.attrib["d"]) + """ />
</vector>
""").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    manifest = json.loads((SOURCE / "manifest.json").read_text())
    for asset in manifest["assets"]:
        target = ROOT / asset["drawable"]
        expected = vector_bytes(asset)
        if args.check:
            if not target.is_file() or target.read_bytes() != expected:
                raise SystemExit(f"DOCUMENT_FILE_ICON_FAILED: stale {target}")
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(expected)
    print("DOCUMENT_FILE_ICONS_OK assets=2 paths_unchanged=true word_fill_adjusted=true")


if __name__ == "__main__":
    main()
