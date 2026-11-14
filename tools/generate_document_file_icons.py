#!/usr/bin/env python3
"""Shape-preserving container conversion for pinned SVG file icons."""
from pathlib import Path
import argparse
import hashlib
import json
import re
from decimal import Decimal
import xml.etree.ElementTree as ET
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "third_party/material-document-icons"


def number(value):
    parsed = Decimal(value)
    if not parsed.is_finite():
        raise ValueError("Non-finite SVG coordinate")
    return parsed


def decimal_text(value):
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def android_color(value):
    if value == "none":
        return "#00000000"
    if not re.fullmatch(r"#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}", value):
        raise ValueError(f"Unsupported SVG fill: {value}")
    return "#" + "".join(c * 2 for c in value[1:]) if len(value) == 4 else value


def circle_path(cx, cy, radius):
    x, y, r = map(number, (cx, cy, radius))
    if r <= 0:
        raise ValueError("Non-positive SVG circle radius")
    left, y, r, diameter = map(decimal_text, (x - r, y, r, r * 2))
    return f"M{left} {y}a{r} {r} 0 1 0 {diameter} 0a{r} {r} 0 1 0 -{diameter} 0z"


def vector_paths(svg, color_override=None):
    result = []

    def visit(node, inherited_fill, depth):
        tag = node.tag.removeprefix("{http://www.w3.org/2000/svg}")
        allowed = {
            "path": {"d", "fill", "data-mit-no-recolor", "aria-label"},
            "circle": {"cx", "cy", "r", "fill"},
            "g": {"fill", "transform", "stroke-width"},
        }.get(tag)
        if allowed is None or not set(node.attrib) <= allowed:
            raise ValueError(f"Unsupported SVG element/attributes: {tag} {set(node.attrib)}")
        fill = node.attrib.get("fill", inherited_fill)
        indent = "    " * depth
        if tag == "g":
            transform = node.attrib.get("transform")
            if transform:
                match = re.fullmatch(r"translate\(([^)]+)\)", transform)
                if match is None:
                    raise ValueError(f"Unsupported SVG transform: {transform}")
                values = [number(v) for v in match[1].replace(",", " ").split()]
                if len(values) not in (1, 2):
                    raise ValueError(f"Invalid SVG translation: {transform}")
                x, y = values[0], values[1] if len(values) == 2 else Decimal(0)
                result.append(f'{indent}<group android:translateX={quoteattr(decimal_text(x))} '
                              f'android:translateY={quoteattr(decimal_text(y))}>\n')
            # Stroke width has no visual effect: this subset rejects all stroke paint.
            for child in node:
                visit(child, fill, depth + 1 if transform else depth)
            if transform:
                result.append(f"{indent}</group>\n")
            return
        if len(node):
            raise ValueError(f"Unexpected children in SVG {tag}")
        path = node.attrib["d"] if tag == "path" else circle_path(
            node.attrib["cx"], node.attrib["cy"], node.attrib["r"])
        converted_fill = fill
        if color_override:
            if fill != color_override[0]:
                raise ValueError("SVG fill differs from the reviewed color override")
            converted_fill = color_override[1]
        if node.attrib.get("data-mit-no-recolor") == "true" and converted_fill != fill:
            raise ValueError("Upstream path disallows recoloring")
        result.append(f"{indent}<path\n"
                      f"{indent}    android:fillColor={quoteattr(android_color(converted_fill))}\n"
                      f'{indent}    android:fillType="nonZero"\n'
                      f"{indent}    android:pathData={quoteattr(path)} />\n")

    if svg.tag != "{http://www.w3.org/2000/svg}svg" or not set(svg.attrib) <= {"viewBox", "fill"}:
        raise ValueError("Unsupported SVG root")
    for child in svg:
        visit(child, svg.attrib.get("fill", "#000000"), 1)
    return "".join(result)


def vector_bytes(asset):
    source = SOURCE / asset["sourceFile"]
    data = source.read_bytes()
    if len(data) != asset["sourceBytes"] or hashlib.sha256(data).hexdigest() != asset["sourceSha256"]:
        raise ValueError(f"Changed upstream asset: {source.name}")
    svg = ET.fromstring(data)
    viewport = svg.attrib["viewBox"].split()
    if len(viewport) != 4 or any(number(v) != 0 for v in viewport[:2]) or any(number(v) <= 0 for v in viewport[2:]):
        raise ValueError("Unsupported SVG viewport")
    override = (asset["sourceFill"], asset["androidFill"]) if "androidFill" in asset else None
    paths = vector_paths(svg, override)
    return ("""<?xml version="1.0" encoding="utf-8"?>
<!-- Material Icon Theme, MIT. See third_party/material-document-icons/manifest.json.
     Upstream path data is unchanged. Word fill is adjusted for dark/light contrast. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth=""" + quoteattr(viewport[2]) + """
    android:viewportHeight=""" + quoteattr(viewport[3]) + ">\n" + paths + "</vector>\n").encode("utf-8")


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
    print(f"DOCUMENT_FILE_ICONS_OK assets={len(manifest['assets'])} paths_unchanged=true word_fill_adjusted=true")


if __name__ == "__main__":
    main()
