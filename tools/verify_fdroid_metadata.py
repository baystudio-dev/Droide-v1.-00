#!/usr/bin/env python3
"""Validate upstream F-Droid/Fastlane listing files without Android tooling.

This is a metadata check, not an F-Droid scanner or a source-build attestation.
"""

from __future__ import annotations

import re
import struct
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
LOCALES = ("en-US", "id-ID")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def checked_text(path: Path, *, limit: int | None = None) -> str:
    require(path.is_file(), f"Missing F-Droid metadata: {path.relative_to(ROOT)}")
    content = path.read_text(encoding="utf-8")
    require(content.strip() == content.rstrip("\r\n") and bool(content.strip()),
            f"Empty or padded F-Droid metadata: {path.relative_to(ROOT)}")
    if limit is not None:
        require(len(content.strip()) <= limit,
                f"F-Droid metadata exceeds {limit} characters: {path.relative_to(ROOT)}")
    return content.strip()


def verify() -> tuple[int, int]:
    build_script = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r"(?m)^\s*versionCode\s*=\s*(\d+)\s*$", build_script)
    require(match is not None, "Unable to find a literal Android versionCode")
    version_code = int(match.group(1))

    metadata = ROOT / "fastlane/metadata/android"
    for locale in LOCALES:
        directory = metadata / locale
        checked_text(directory / "title.txt", limit=50)
        summary = checked_text(directory / "short_description.txt", limit=79)
        require("\n" not in summary, f"Short description must be one line: {locale}")
        checked_text(directory / "full_description.txt", limit=4000)
        checked_text(directory / f"changelogs/{version_code}.txt", limit=500)

    artwork = metadata / "en-US/images"
    icon = artwork / "icon.png"
    require(icon.is_file(), "Missing F-Droid app icon")
    data = icon.read_bytes()
    require(len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR",
            "F-Droid icon is not a valid PNG header")
    width, height = struct.unpack(">II", data[16:24])
    require(width == height and width >= 192, "F-Droid icon must be square and at least 192 pixels")

    directory = artwork / "phoneScreenshots"
    screenshots = sorted(directory.iterdir()) if directory.is_dir() else []
    require(len(screenshots) >= 2, "At least two F-Droid phone screenshots are required")
    require(all(p.name == f"{i}.jpg" for i, p in enumerate(screenshots, 1)),
            "Phone screenshots must be consecutively numbered JPEGs")
    for screenshot in screenshots:
        picture = screenshot.read_bytes()
        require(len(picture) > 1024 and picture.startswith(b"\xff\xd8\xff"),
                f"Invalid F-Droid screenshot: {screenshot.name}")

    return version_code, len(screenshots)


if __name__ == "__main__":
    version, count = verify()
    print(f"FDROID_METADATA_OK versionCode={version} locales={','.join(LOCALES)} screenshots={count}")
