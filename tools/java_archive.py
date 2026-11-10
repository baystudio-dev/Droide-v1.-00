"""Read class files and compose deterministic Java archives without loading code."""
import io
import stat
import zipfile

def fail(message):
    raise ValueError(message)


def archive_entries(raw: bytes) -> dict[str, bytes]:
    try:
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)): fail('duplicate archive entry')
            result = {}
            for entry in archive.infolist():
                name = entry.filename
                parts = name[:-1].split('/') if name.endswith('/') else name.split('/')
                if (not name or '\\' in name or ':' in name or name.startswith('/') or
                        any(p in ('', '.', '..') for p in parts) or '\0' in name):
                    fail('unsafe archive entry path: ' + name)
                kind = stat.S_IFMT(entry.external_attr >> 16)
                if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or entry.flag_bits & 1:
                    fail('nonregular/encrypted archive entry: ' + name)
                if entry.file_size > 256 * 1024 * 1024: fail('oversized archive entry')
                result[name] = archive.read(entry)
            return result
    except (zipfile.BadZipFile, RuntimeError, KeyError) as error:
        fail('invalid archive: ' + str(error))


def deterministic_zip(entries: dict[str, bytes]) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (0o40755 if name.endswith('/') else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data, compresslevel=9)
    return stream.getvalue()


def parse_class(data: bytes) -> dict:
    """Preserve descriptors, modifiers, and constants without loading classes."""
    pos = 0
    def take(n):
        nonlocal pos
        if n < 0 or pos + n > len(data): fail('truncated classfile')
        out = data[pos:pos + n]; pos += n
        return out
    def u1(): return int.from_bytes(take(1), 'big')
    def u2(): return int.from_bytes(take(2), 'big')
    def u4(): return int.from_bytes(take(4), 'big')
    if take(4) != b'\xca\xfe\xba\xbe': fail('invalid class magic')
    minor, major = u2(), u2()
    count = u2(); pool_start = pos; cp = [None] * count; index = 1
    while index < count:
        tag = u1()
        if tag == 1: value = take(u2())
        elif tag in (3, 4): value = take(4)
        elif tag in (5, 6): value = take(8)
        elif tag in (7, 8, 16, 19, 20): value = u2()
        elif tag in (9, 10, 11, 12, 17, 18): value = (u2(), u2())
        elif tag == 15: value = (u1(), u2())
        else: fail('unsupported class constant-pool tag')
        cp[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1
    pool_bytes = data[pool_start:pos]
    def constant(index, tag=None):
        if index < 1 or index >= len(cp) or cp[index] is None: fail('invalid class constant index')
        value = cp[index]
        if tag is not None and value[0] != tag: fail('class constant type mismatch')
        return value[1]
    def utf(index):
        try: return constant(index, 1).decode('utf-8')
        except UnicodeError: fail('invalid class identifier UTF-8')
    def cls(index): return utf(constant(index, 7)) if index else None
    def attributes():
        out = {}
        for _ in range(u2()):
            name = utf(u2()); value = take(u4())
            if name in out: fail('duplicate class attribute')
            out[name] = value
        return out
    access, name, super_name = u2(), cls(u2()), cls(u2())
    interfaces = [cls(u2()) for _ in range(u2())]
    def members():
        out = []
        for _ in range(u2()):
            flags, member_name, descriptor = u2(), utf(u2()), utf(u2())
            attrs = attributes(); value = None
            if 'ConstantValue' in attrs:
                if len(attrs['ConstantValue']) != 2: fail('invalid ConstantValue attribute')
                k = int.from_bytes(attrs['ConstantValue'], 'big')
                if k <= 0 or k >= len(cp) or cp[k] is None: fail('invalid ConstantValue index')
                value = cp[k]
                if value[0] == 8: value = (8, constant(value[1], 1))
                elif value[0] not in (3, 4, 5, 6): fail('invalid ConstantValue type')
            out.append({'access': flags, 'name': member_name, 'descriptor': descriptor,
                        'constant': value, 'attributes': attrs})
        if len({(m['name'], m['descriptor']) for m in out}) != len(out): fail('duplicate class member')
        return out
    fields, methods, attrs = members(), members(), attributes()
    if pos != len(data): fail('trailing class bytes')
    return {'minor': minor, 'major': major, 'constantPool': pool_bytes, 'access': access,
            'name': name, 'super': super_name, 'interfaces': interfaces,
            'fields': fields, 'methods': methods, 'attributes': attrs}
