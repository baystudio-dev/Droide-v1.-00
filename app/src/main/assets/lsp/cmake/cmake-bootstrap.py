#!/usr/bin/python3.12
"""App-authored isolated entry guard. Package consistency is not a sandbox."""
import sys
# Deliberately establish the trusted standard-library search path before any optional import.
if not (sys.flags.isolated == 1 and sys.flags.no_site == 1 and sys.flags.dont_write_bytecode == 1):
    raise SystemExit(70)
if sys.implementation.name != 'cpython' or sys.version_info[:2] != (3, 12):
    raise SystemExit(70)
_TRUSTED_PATH = ['/usr/lib/python312.zip', '/usr/lib/python3.12', '/usr/lib/python3.12/lib-dynload']
if sys.path != _TRUSTED_PATH or sys.executable != '/usr/bin/python3.12':
    raise SystemExit(70)
import os
import stat
import json
import hashlib
import struct
import time
import unicodedata
import resource

POLICY = 'cmake-purelib-0.1.11-cpython312-v1'
PYTHON = '/usr/bin/python3.12'
CMAKE = '/usr/bin/cmake'
MAX_RUNTIME_BYTES = 536870912
START = time.monotonic()


def check(value, message='CMake managed identity rejected'):
    if not value:
        raise RuntimeError(message)


def budget():
    check(time.monotonic() - START < 30, 'CMake identity deadline')


def absolute(path):
    return isinstance(path, str) and path.startswith('/') and 1 < len(path) <= 4096 and all(32 <= ord(c) < 127 and c != '\\' for c in path) and all(x not in ('', '.', '..') for x in path.split('/')[1:])


def stamp(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns, s.st_nlink)


def regular(path, maximum):
    budget(); check(absolute(path))
    parent = os.path.dirname(path)
    while parent != '/':
        check(stat.S_ISDIR(os.lstat(parent).st_mode)); parent = os.path.dirname(parent)
    before = os.lstat(path); check(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and 0 <= before.st_size <= maximum)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        check(stamp(os.fstat(fd)) == stamp(before)); parts = []; total = 0
        while True:
            budget(); data = os.read(fd, min(65536, maximum + 1 - total))
            if not data:
                break
            total += len(data); check(total <= maximum and total <= before.st_size); parts.append(data)
        check(total == before.st_size and stamp(os.fstat(fd)) == stamp(before) and stamp(os.lstat(path)) == stamp(before))
        return b''.join(parts)
    finally:
        os.close(fd)


def strict(data):
    def pairs(items):
        result = {}
        for k, v in items:
            check(k not in result, 'Duplicate JSON field'); result[k] = v
        return result
    return json.loads(data.decode('utf-8', 'strict'), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite JSON')))


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def loader_cache(data):
    """Finite glibc 2.39 new cache format, without invoking ldconfig or an ELF loader."""
    check(48 <= len(data) <= 8388608 and data[:20] == b'glibc-ld.so.cache1.1')
    count, size = struct.unpack_from('<II', data, 20)
    check(count <= 16384 and data[28] in (0, 2) and data[29:32] == b'\0' * 3 and data[36:48] == b'\0' * 12)
    start = 48 + 24 * count; end = start + size
    check(start <= end <= len(data))
    extension = struct.unpack_from('<I', data, 32)[0]
    if extension:
        check(extension >= end and extension % 4 == 0 and extension + 8 <= len(data))
        magic, sections = struct.unpack_from('<II', data, extension)
        check(magic == ((-358342284) & 0xffffffff) and sections <= 2 and extension + 8 + 16 * sections <= len(data))
        seen = set()
        for i in range(sections):
            tag, flags, offset, length = struct.unpack_from('<IIII', data, extension + 8 + 16 * i)
            # Hardware-capability variants have unreviewed preference semantics on this route.
            check(tag == 0 and tag not in seen and flags == 0 and length <= 1024 and
                  offset >= extension + 8 + 16 * sections and offset + length <= len(data))
            seen.add(tag)
    else:
        check(end == len(data))
    def string(index):
        check(start <= index < end); stop = data.find(b'\0', index, end); check(index <= stop and stop - index <= 4096)
        return data[index:stop].decode('ascii', 'strict')
    result = {}
    for i in range(count):
        flags, key, value, unused, hwcap = struct.unpack_from('<iIIIQ', data, 48 + 24 * i)
        name = string(key); target = string(value)
        check(name and '/' not in name and all(c.isalnum() or c in '._+-' for c in name))
        check(absolute(target) and target.startswith(('/usr/lib/', '/lib/', '/usr/local/lib/')))
        # AArch64 libc6 cache entries only. No architecture guessing or fallback cache format.
        check(flags == 0x0a03 and unused == 0 and hwcap == 0)
        result.setdefault(name, []).append(target)
    return result


def runtime_snapshot():
    check(unicodedata.unidata_version == '15.0.0' and os.uname().machine == 'aarch64')
    files = {}; links = {}; directories = set(); total = 0; inspected_elf = set()
    def resolved(path, missing=False):
        check(absolute(path)); seen = set()
        for _ in range(24):
            check(path not in seen); seen.add(path)
            parts = path.split('/')[1:]; prefix = ''; changed = False
            for index, part in enumerate(parts):
                prefix += '/' + part
                try:
                    s = os.lstat(prefix)
                except FileNotFoundError:
                    check(missing); return path
                if stat.S_ISLNK(s.st_mode):
                    target = os.readlink(prefix); check(len(target) <= 4096 and '\x00' not in target)
                    links[prefix] = target
                    tail = '/'.join(parts[index + 1:])
                    path = os.path.normpath(os.path.join(os.path.dirname(prefix), target, tail))
                    check(absolute(path) and path.startswith(('/usr/', '/lib/'))); changed = True; break
                if index < len(parts) - 1:
                    check(stat.S_ISDIR(s.st_mode))
            if not changed:
                return path
        raise RuntimeError('Runtime link bound')
    def add(path):
        nonlocal total
        path = resolved(path)
        if path in files:
            return path
        data = regular(path, 67108864); total += len(data)
        check(total <= MAX_RUNTIME_BYTES and len(files) < 16384 and len(links) < 2048)
        files[path] = {'sha256': digest(data), 'bytes': len(data)}
        if data[:4] == b'\x7fELF':
            elf(path, data)
        return path
    def elf(path, data):
        if path in inspected_elf:
            return
        inspected_elf.add(path)
        check(len(data) >= 64 and data[4:7] == b'\x02\x01\x01' and struct.unpack_from('<H', data, 18)[0] == 183)
        phoff = struct.unpack_from('<Q', data, 32)[0]; phsize, count = struct.unpack_from('<HH', data, 54)
        check(phsize == 56 and count <= 256 and phoff + count * phsize <= len(data))
        loads = []; dynamic = None; interpreter = False
        for i in range(count):
            typ, flags, off, va, pa, fsz, msz, align = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phsize)
            check(off + fsz <= len(data))
            if typ == 1:
                loads.append((va, fsz, off))
            elif typ == 2:
                check(dynamic is None); dynamic = (off, fsz)
            elif typ == 3:
                check(not interpreter); interpreter = True
                name = data[off:off+fsz]; check(name == b'/lib/ld-linux-aarch64.so.1\0'); add(name[:-1].decode('ascii'))
        if dynamic is None:
            return
        off, length = dynamic; check(length <= 65536 and length % 16 == 0)
        values = []; terminated = False
        for pos in range(off, off + length, 16):
            tag, value = struct.unpack_from('<qQ', data, pos)
            if tag == 0:
                terminated = True; break
            values.append((tag, value))
        check(terminated)
        strtab = [v for t, v in values if t == 5]; strsz = [v for t, v in values if t == 10]
        check(len(strtab) == 1 and len(strsz) == 1 and strsz[0] <= 8388608)
        matches = [o + strtab[0] - va for va, n, o in loads if va <= strtab[0] and strtab[0] + strsz[0] <= va + n]
        check(len(matches) == 1); strings = data[matches[0]:matches[0]+strsz[0]]
        def string(index):
            check(index < len(strings)); end = strings.find(b'\0', index); check(end >= index)
            return strings[index:end].decode('ascii')
        # Search overrides, audit hooks, FILTER and AUXILIARY can load unmeasured code.
        check(not any(t in (15, 29, 0x6ffffefb, 0x6ffffefc, 0x7fffffff, 0x7ffffffd) for t, _ in values))
        for tag, index in values:
            if tag != 1:
                continue
            name = string(index); check(name and '/' not in name and all(c.isalnum() or c in '._+-' for c in name))
            check(all(os.path.lexists(p) for p in cache.get(name, [])))
            candidates = cache.get(name, []) + [directory + '/' + name for directory in search]
            existing = [p for p in candidates if os.path.lexists(p)]
            check(existing); targets = {resolved(p) for p in existing}; check(len(targets) == 1); add(existing[0])
    def walk(path, depth=0):
        budget(); check(depth <= 24 and len(files) + len(directories) < 20000)
        path = resolved(path); s = os.lstat(path)
        if stat.S_ISDIR(s.st_mode):
            check(path not in directories); directories.add(path)
            for name in sorted(os.listdir(path)):
                check(name not in ('.', '..') and '/' not in name); walk(path + '/' + name, depth + 1)
        else:
            add(path)
    # Loader control files and search choices are measured before resolving any DT_NEEDED.
    check(not os.path.lexists('/etc/ld.so.preload'), 'Global preload is not permitted')
    cache_data = regular('/etc/ld.so.cache', 8388608); cache = loader_cache(cache_data)
    files['/etc/ld.so.cache'] = {'sha256': digest(cache_data), 'bytes': len(cache_data)}
    config_root = '/etc/ld.so.conf.d'; check(stat.S_ISDIR(os.lstat(config_root).st_mode))
    config_names = sorted(os.listdir(config_root)); check(len(config_names) <= 128)
    check(all(n.endswith('.conf') and '/' not in n and all(c.isalnum() or c in '._+-' for c in n) for n in config_names))
    search = ['/lib/aarch64-linux-gnu', '/usr/lib/aarch64-linux-gnu', '/lib', '/usr/lib']
    for config in ['/etc/ld.so.conf'] + [config_root + '/' + n for n in config_names]:
        data = regular(config, 65536); files[config] = {'sha256': digest(data), 'bytes': len(data)}
        for line in data.decode('ascii', 'strict').splitlines():
            line = line.split('#', 1)[0].strip()
            if not line:
                continue
            if line == 'include /etc/ld.so.conf.d/*.conf':
                check(config == '/etc/ld.so.conf'); continue
            check(absolute(line) and (line in ('/lib', '/usr/lib', '/usr/local/lib') or
                  line.startswith(('/lib/', '/usr/lib/', '/usr/local/lib/'))))
            search.append(line)
    check(len(search) <= 128)
    inventories = {}; absent = set(); canonical_search = set()
    for directory in search:
        directory = resolved(directory, missing=True)
        if not os.path.lexists(directory):
            absent.add(directory); continue
        check(stat.S_ISDIR(os.lstat(directory).st_mode)); canonical_search.add(directory)
        names = sorted(os.listdir(directory)); check(len(names) <= 16384 and 'glibc-hwcaps' not in names)
        entries = {}
        for name in names:
            check(name and '/' not in name and all(32 <= ord(c) < 127 and c != '\\' for c in name))
            path = directory + '/' + name; mode = os.lstat(path).st_mode
            if stat.S_ISLNK(mode):
                target = os.readlink(path); check(len(target) <= 4096 and '\x00' not in target)
                entries[name] = {'type': 'link', 'target': target}
            elif stat.S_ISREG(mode):
                entries[name] = {'type': 'file'}
            else:
                check(stat.S_ISDIR(mode)); entries[name] = {'type': 'directory'}
        inventories[directory] = entries
    search = sorted(canonical_search); check(sum(len(v) for v in inventories.values()) <= 32768)
    add(PYTHON); add(CMAKE)
    walk('/usr/lib/python3.12'); walk('/usr/share/cmake-3.28')
    # A ZIP on sys.path changes the trusted import closure even if the usual directory is intact.
    if os.path.lexists('/usr/lib/python312.zip'):
        add('/usr/lib/python312.zip')
    status = regular('/var/lib/dpkg/status', 16777216); files['/var/lib/dpkg/status'] = {'sha256': digest(status), 'bytes': len(status)}
    blocks = status.decode('utf-8', 'strict').split('\n\n')
    versions = {}
    for package in ('python3.12', 'cmake'):
        matches = [dict(line.split(': ', 1) for line in block.splitlines() if ': ' in line and not line.startswith(' ')) for block in blocks if '\nPackage: ' + package + '\n' in '\n'+block+'\n']
        check(len(matches) == 1 and matches[0].get('Status') == 'install ok installed')
        versions[package] = matches[0]['Version']
    check(versions['python3.12'].split(':')[-1].startswith('3.12.') and versions['cmake'].split(':')[-1].startswith('3.28.'))
    return {'schema': 1, 'implementation': 'CPython', 'pythonVersion': sys.version, 'unicodeVersion': unicodedata.unidata_version,
            'cmakeMajorMinor': '3.28', 'packageVersions': versions, 'files': files, 'links': links, 'directories': sorted(directories),
            'pythonZipAbsent': not os.path.lexists('/usr/lib/python312.zip'),
            'loaderConfigNames': config_names, 'loaderInventories': inventories, 'loaderAbsentDirectories': sorted(absent)}


def inspect(health=False):
    global START
    START = time.monotonic()
    check(sys.argv == [__file__])
    check(absolute(__file__) and __file__.endswith('/payload/cmake-bootstrap.py'))
    root = __file__[:-len('/payload/cmake-bootstrap.py')]
    authority = strict(regular(root + '/CMAKE_LAUNCH_AUTHORITY.json', 4194304))
    check(set(authority) == {'schema','family','version','policy','activationId','stageGuestRoot','finalGuestRoot','recipeSha256','runtimeSha256','bootstrapSha256','supervisorSha256','healthSha256','purelibFiles'})
    check(authority['schema'] == 1 and authority['family'] == 'lsp.cmake-language-server' and authority['version'] == '0.1.11' and authority['policy'] == POLICY)
    check(root in (authority['stageGuestRoot'],authority['finalGuestRoot']) and authority['stageGuestRoot'] != authority['finalGuestRoot'])
    check(regular(root + '/DROIDE_ACTIVATION_ID',100).decode() == authority['activationId'])
    for filename, key in (('payload/cmake-bootstrap.py','bootstrapSha256'),('payload/cmake-supervisor.py','supervisorSha256'),('payload/cmake-lsp-health.py','healthSha256'),('CMAKE_RECIPE_LOCK.json','recipeSha256'),('CMAKE_RUNTIME.json','runtimeSha256')):
        check(digest(regular(root+'/'+filename,4194304)) == authority[key])
    expected = strict(regular(root + '/CMAKE_RUNTIME.json',4194304))
    check(runtime_snapshot() == expected, 'CMake runtime fingerprint changed')
    observed = {}; purelib = root + '/payload/purelib'; count = 0; total = 0
    def walk(directory, depth=0):
        nonlocal count,total
        check(depth <= 24 and stat.S_ISDIR(os.lstat(directory).st_mode))
        names = sorted(os.listdir(directory)); check(names or directory == purelib)
        for name in names:
            count += 1; check(count <= 8192); path = directory+'/'+name; s=os.lstat(path)
            if stat.S_ISDIR(s.st_mode):
                walk(path,depth+1)
            else:
                data=regular(path,16777216);total+=len(data);check(total<=67108864)
                observed[path[len(purelib)+1:]]=digest(data)
    walk(purelib);check(observed == authority['purelibFiles'])
    if root == authority['stageGuestRoot']:
        check(health and not os.path.lexists(root + '/CMAKE_TREE_SEAL.json'))
    else:
        seal = strict(regular(root + '/CMAKE_TREE_SEAL.json',4194304)); check(seal['schema']==1)
        actual = {}; directories = []
        def full(directory, depth=0):
            check(depth <= 26)
            for name in sorted(os.listdir(directory)):
                path=directory+'/'+name;relative=path[len(root)+1:]
                if relative=='CMAKE_TREE_SEAL.json':continue
                check(len(actual)<8256)
                s=os.lstat(path)
                if stat.S_ISDIR(s.st_mode):directories.append(relative);full(path,depth+1)
                else:actual[relative]=digest(regular(path,16777216))
        full(root);check(set(seal)=={'schema','files','directories'} and actual==seal['files'] and sorted(directories)==seal['directories'])
    return root, authority


def apply_limits(policy):
    check(policy in ('health','editor'))
    limits = [(resource.RLIMIT_AS,(805306368,805306368)),(resource.RLIMIT_CPU,(25,30) if policy=='health' else (120,130)),
              (resource.RLIMIT_FSIZE,(16777216,16777216) if policy=='health' else (67108864,67108864)),
              (resource.RLIMIT_NOFILE,(128,128) if policy=='health' else (256,256)),(resource.RLIMIT_CORE,(0,0))]
    for kind,value in limits:
        resource.setrlimit(kind,value);check(resource.getrlimit(kind)==value)


def main():
    if sys.argv[1:] == ['--runtime-fingerprint']:
        apply_limits('health'); sys.stdout.buffer.write(canonical(runtime_snapshot()) + b'\n'); return
    policy = os.environ.get('DROIDE_CMAKE_POLICY');apply_limits(policy)
    root,_ = inspect(policy=='health')
    check(sys.path == _TRUSTED_PATH and 'site' not in sys.modules)
    sys.path.append(root + '/payload/purelib')
    sys.argv = ['cmake-language-server']
    from cmake_language_server.server import main as server_main
    raise SystemExit(server_main())


if __name__ == '__main__':
    main()
