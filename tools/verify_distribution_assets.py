"""Check the exact APK asset difference admitted for the F-Droid candidate."""

ROOTFS = "assets/workstation/ubuntu-base-24.04.5-base-arm64.tar.xz"


def verify_assets(root, archive, fdroid_candidate=False, native_source=None):
    from generate_fdroid_assets import overrides
    replacements = overrides(root, native_source) if fdroid_candidate else {}
    names = set(archive.namelist())
    count = 0
    for source in (root / "app/src/main/assets").rglob("*"):
        if not source.is_file():
            continue
        name = "assets/" + source.relative_to(root / "app/src/main/assets").as_posix()
        if fdroid_candidate and name == ROOTFS:
            if name in names:
                raise ValueError("F-Droid candidate must not embed the Ubuntu executable archive")
            continue
        expected = replacements.get(name.removeprefix("assets/"), source.read_bytes())
        if name not in names or archive.read(name) != expected:
            raise ValueError("Packaged asset missing or changed: " + name)
        count += 1
    if fdroid_candidate:
        unexpected = [name for name in names if name.startswith("assets/workstation/") and name.endswith((".xz", ".gz", ".zip", ".so"))]
        if unexpected:
            raise ValueError("Unexpected embedded executable distribution: " + repr(unexpected))
    return count
