#!/usr/bin/env python3
"""Read-only verification of the exact retained Gradle 8.13 vendor distribution.

Never repairs permissions, extracts files, downloads, or changes shared tools.
"""
from pathlib import Path, PurePosixPath
import hashlib
import stat
import zipfile

ARCHIVE_SHA256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
PREFIX = 'gradle-8.13/'


def file_digest(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def verify(archive: Path, installed: Path, wrapper_properties: Path) -> dict:
    """Bind archive bytes and complete installed regular-file/directory identities."""
    for path in (archive, installed, wrapper_properties):
        if any(parent.is_symlink() for parent in (path, *path.parents)):
            raise ValueError('Linked Gradle input is not admitted')
    if file_digest(archive) != ARCHIVE_SHA256:
        raise ValueError('Gradle vendor archive digest mismatch')
    wrapper = wrapper_properties.read_text(encoding='utf-8')
    expected_line = 'distributionSha256Sum=' + ARCHIVE_SHA256
    if wrapper.splitlines().count(expected_line) != 1:
        raise ValueError('Wrapper does not bind exact Gradle vendor archive')

    expected = {}
    with zipfile.ZipFile(archive) as vendor:
        seen = set()
        for entry in vendor.infolist():
            name = entry.filename
            if name in seen or not name.startswith(PREFIX):
                raise ValueError('Duplicate or unexpected Gradle archive path')
            seen.add(name)
            relative = name[len(PREFIX):]
            if entry.is_dir() and relative.endswith('/'):
                relative = relative[:-1]
            if not relative:
                continue
            if any(part in ('', '.', '..') for part in relative.split('/')):
                raise ValueError('Non-canonical Gradle archive path')
            path = PurePosixPath(relative)
            if path.is_absolute() or '..' in path.parts or '\\' in relative:
                raise ValueError('Unsafe Gradle archive path')
            mode = entry.external_attr >> 16
            if stat.S_IFMT(mode) not in (stat.S_IFREG, stat.S_IFDIR):
                raise ValueError('Unsupported Gradle archive entry type')
            target = installed / relative
            if any(parent.is_symlink() for parent in (target, *target.parents)) or not target.exists():
                raise ValueError('Missing/linked installed Gradle entry: ' + relative)
            if stat.S_IMODE(target.stat().st_mode) != stat.S_IMODE(mode):
                raise ValueError('Installed Gradle mode differs: ' + relative)
            if entry.is_dir():
                if not target.is_dir():
                    raise ValueError('Installed Gradle directory type differs')
                expected[relative] = {'type': 'directory', 'mode': stat.S_IMODE(mode)}
            else:
                if not target.is_file():
                    raise ValueError('Installed Gradle regular-file type differs')
                digest = hashlib.sha256(vendor.read(entry)).hexdigest()
                if file_digest(target) != digest:
                    raise ValueError('Installed Gradle bytes differ: ' + relative)
                expected[relative] = {'type': 'file', 'mode': stat.S_IMODE(mode), 'sha256': digest}
    actual = {str(path.relative_to(installed)) for path in installed.rglob('*')}
    if actual != set(expected):
        raise ValueError('Installed Gradle complete entry set differs from vendor archive')
    return {'archiveSha256': ARCHIVE_SHA256, 'installedEntries': expected}
