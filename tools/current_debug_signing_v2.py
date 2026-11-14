"""Local test-key continuity only. Never copies or generates a private key."""
import copy
import hashlib
import os
from pathlib import Path
import stat

DELIVERED_CERTIFICATE_SHA256 = '4fa260f18e8f9f1d3e0842a4b541b16d5a2142f19bb7943e572308010edbecb1'
PASSWORD_ENV = 'DROIDE_LOCAL_DEBUG_STORE_PASSWORD'

def snapshot(path):
    path = Path(os.path.abspath(path))
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Linked signing input')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or not 0 < before.st_size <= 1024 * 1024:
            raise ValueError('Invalid bounded signing input')
        data = bytearray()
        while len(data) <= before.st_size:
            chunk = os.read(fd, min(65536, before.st_size + 1 - len(data)))
            if not chunk: break
            data.extend(chunk)
        after = os.fstat(fd)
        identity = lambda s: (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        if len(data) != before.st_size or identity(before) != identity(after) or identity(os.stat(path, follow_symlinks=False)) != identity(before):
            raise ValueError('Signing input changed during observation')
        return {'path': str(path), 'identity': identity(before), 'sha256': hashlib.sha256(data).hexdigest()}
    finally:
        os.close(fd)

def unchanged(expected):
    if snapshot(expected['path']) != expected:
        raise ValueError('Original signing input changed')

def require_certificate(certificate_bytes, requested):
    if requested != DELIVERED_CERTIFICATE_SHA256 or hashlib.sha256(certificate_bytes).hexdigest() != requested:
        raise ValueError('Certificate differs from delivered preliminary APK')
    return requested

def redacted_record(record):
    result = copy.deepcopy(record)
    arguments = result.get('argv', [])
    for i, argument in enumerate(arguments[:-1]):
        if argument in ('-storepass', '-keypass'):
            arguments[i + 1] = '[REDACTED]'
    env = result.get('environment', {})
    if PASSWORD_ENV in env:
        env[PASSWORD_ENV] = '[REDACTED]'
    return result
