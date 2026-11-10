"""Exact vendor archive and installed JDK identity; no extraction or normalization."""
import hashlib
import os
from pathlib import Path, PurePosixPath
import stat
import tarfile


def require(ok, message):
    if not ok:
        raise ValueError(message)


def digest(stream):
    return hashlib.file_digest(stream, 'sha256').hexdigest()


def safe_path(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlinked protected path')
    return path


def archive_inventory(archive, manifest):
    archive = safe_path(archive)
    with archive.open('rb') as stream:
        require(digest(stream) == manifest['archiveSha256'], 'vendor archive SHA mismatch')
    entries = {}
    prefix = 'jdk-' + manifest['version']
    root_seen = False
    with tarfile.open(archive, 'r:gz') as source:
        for member in source:
            require(member.name == prefix or member.name.startswith(prefix + '/'), 'archive outside vendor root')
            name = '' if member.name == prefix else member.name[len(prefix) + 1:]
            if not name:
                require(not root_seen and member.isdir() and member.mode == 0o755, 'invalid/duplicate archive root')
                root_seen = True
                continue
            require(all(p not in ('', '.', '..') for p in name.split('/')) and '\\' not in name,
                    'unsafe archive member')
            require(name not in entries, 'duplicate archive member')
            entry = {'mode': member.mode}
            if member.isfile():
                require(member.size <= 512 * 1024 * 1024, 'oversized vendor member')
                with source.extractfile(member) as stream:
                    entry.update(kind='file', sha256=digest(stream), size=member.size)
            elif member.isdir():
                entry.update(kind='directory')
            elif member.issym():
                expected = manifest['entries'].get(name, {})
                require(expected.get('kind') == 'symlink' and member.linkname == expected.get('target'),
                        'unexpected vendor alias')
                entry.update(kind='symlink', target=member.linkname, resolved=expected['resolved'])
            else:
                raise ValueError('nonregular vendor member: ' + name)
            entries[name] = entry
    require(root_seen and entries == manifest['entries'], 'vendor archive full manifest mismatch')
    return entries


def installed_inventory(home, manifest):
    home = safe_path(home)
    require(home.is_dir() and stat.S_IMODE(home.stat().st_mode) == 0o755, 'vendor JDK root changed')
    actual = {}
    for directory, dirs, names in os.walk(home, followlinks=False):
        for name in dirs + names:
            path = Path(directory) / name
            relative = path.relative_to(home).as_posix()
            info = path.lstat()
            entry = {'mode': stat.S_IMODE(info.st_mode)}
            if stat.S_ISLNK(info.st_mode):
                target = os.readlink(path)
                require(not PurePosixPath(target).is_absolute() and '\\' not in target, 'absolute vendor alias')
                resolved = path.resolve(strict=True)
                require(home in resolved.parents and resolved.is_file(), 'external/directory vendor alias')
                safe_path(resolved)
                entry.update(kind='symlink', target=target, resolved=resolved.relative_to(home).as_posix())
            elif stat.S_ISDIR(info.st_mode):
                entry.update(kind='directory')
            elif stat.S_ISREG(info.st_mode):
                with path.open('rb') as stream:
                    entry.update(kind='file', sha256=digest(stream), size=info.st_size)
            else:
                raise ValueError('special file in vendor JDK')
            actual[relative] = entry
    require(actual == manifest['entries'], 'installed vendor JDK full identity mismatch')
    return actual


def verify(home, archive, manifest):
    archive_inventory(archive, manifest)
    installed_inventory(home, manifest)
    return {'javaHome': str(home), 'archive': str(archive), 'archiveSha256': manifest['archiveSha256'],
            'version': manifest['version'], 'entries': manifest['entries'],
            'origin': manifest['archiveUrl'], 'checksumUrl': manifest['checksumUrl']}
