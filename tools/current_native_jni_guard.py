"""Required JNI ABI guard only; existing composite/Android admission remains mandatory."""
import struct
import re
from pathlib import Path
import os

ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}
PREFIX = 'Java_com_baystudio_droide_core_NativeFilePublication_'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def no_links(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in (path, *path.parents)), 'symlinked path: ' + str(path))
    return path

REQUIRED = PREFIX + 'exportDescriptorLock'
KOTLIN = 'app/src/main/java/com/baystudio/droide/core/ProjectFilePublication.kt'

def required_methods(text):

    declarations = re.findall(r'@JvmStatic\s+external fun ([A-Za-z][A-Za-z0-9]*)\(', text)
    require(declarations and len(declarations) == len(set(declarations)), 'Missing/duplicate JNI declarations')
    require(len(declarations) == len(re.findall(r'\bexternal\s+fun\b', text)), 'Unsupported JNI declaration naming')
    require('exportDescriptorLock' in declarations, 'Current Kotlin exportDescriptorLock declaration missing')
    return declarations

def defined_functions(data, abi):
    """Inspect loader-linked ELF dynsym, never infer an export from embedded text."""
    require(abi in ABIS and len(data) >= 64 and data[:4] == b'\x7fELF', 'Invalid/truncated JNI ELF')
    require(data[4:7] == bytes((ABIS[abi][0], 1, 1)), 'JNI ELF class/endian/version mismatch')
    wide = data[4] == 2
    def unpack(fmt, offset):
        size = struct.calcsize('<' + fmt)
        require(0 <= offset <= len(data) - size, 'Truncated JNI ELF structure')
        return struct.unpack_from('<' + fmt, data, offset)
    h = unpack('HHIQQQIHHHHHH' if wide else 'HHIIIIIHHHHHH', 16)
    kind, machine, version, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, _ = h
    require((kind, machine, version) == (3, ABIS[abi][1], 1), 'JNI ELF machine/type mismatch')
    require(ehsize == (64 if wide else 52) and phnum > 0 and shnum > 0, 'Missing JNI ELF tables')
    require(phsize == (56 if wide else 32) and shsize == (64 if wide else 40), 'Invalid JNI ELF entry size')
    loads, dynamics = [], []
    for i in range(phnum):
        p = unpack('IIQQQQQQ' if wide else 'IIIIIIII', phoff + i * phsize)
        if wide: typ, flags, offset, address, _, size, memory, _ = p
        else: typ, offset, address, _, size, memory, flags, _ = p
        require(offset + size <= len(data) and size <= memory, 'Invalid JNI ELF segment')
        if typ == 1: loads.append((address, size, offset, flags))
        if typ == 2: dynamics.append((offset, address, size))
    def mapped(address, size, execute=False):
        require(size > 0, 'Empty JNI ELF mapped range')
        matches = [o + address - a for a, n, o, flags in loads
                   if a <= address and address + size <= a + n and (not execute or flags & 1)]
        require(len(matches) == 1, 'JNI ELF range is not uniquely loader-mapped')
        return matches[0]
    sections = [unpack('IIQQQQIIQQ' if wide else 'IIIIIIIIII', shoff + i * shsize) for i in range(shnum)]
    def contents(s):
        require(s[4] + s[5] <= len(data), 'Truncated JNI ELF section')
        return data[s[4]:s[4]+s[5]]
    symbols = [s for s in sections if s[1] == 11]
    dynamic = [s for s in sections if s[1] == 6]
    require(len(symbols) == len(dynamic) == len(dynamics) == 1, 'Missing/duplicate JNI dynamic tables')
    s, d = symbols[0], dynamic[0]
    entry = 24 if wide else 16
    require(s[9] == entry and s[5] % entry == 0, 'Invalid JNI dynsym entries')
    require(mapped(s[3], s[5]) == s[4], 'JNI dynsym is not loader-mapped')
    require(s[6] < len(sections) and sections[s[6]][1] == 3 and d[6] == s[6], 'Invalid JNI dynstr linkage')
    strings = sections[s[6]]; table = contents(strings)
    require(mapped(strings[3], strings[5]) == strings[4], 'JNI dynstr is not loader-mapped')
    require(dynamics[0] == (d[4], d[3], d[5]) and mapped(d[3], d[5]) == d[4], 'PT_DYNAMIC mismatch')
    dynamic_entry = 16 if wide else 8
    require(d[9] == dynamic_entry and d[5] % dynamic_entry == 0, 'Invalid JNI dynamic entries')
    tags = {}; terminated = False
    for offset in range(d[4], d[4]+d[5], dynamic_entry):
        tag, value = unpack('qQ' if wide else 'iI', offset)
        if tag == 0: terminated = True; break
        tags.setdefault(tag, []).append(value)
    require(terminated, 'Unterminated JNI dynamic table')
    for tag, expected in ((5, strings[3]), (10, strings[5]), (6, s[3]), (11, s[9])):
        require(tags.get(tag) == [expected], 'JNI loader dynamic linkage mismatch')
    exports = set()
    for offset in range(s[4], s[4]+s[5], entry):
        if wide: name, info, other, index, address, size = unpack('IBBHQQ', offset)
        else: name, address, size, info, other, index = unpack('IIIBBH', offset)
        require(name < len(table), 'Invalid JNI symbol name offset')
        end = table.find(b'\0', name); require(end >= 0, 'Unterminated JNI symbol name')
        text = table[name:end].decode('ascii')
        if not text.startswith(PREFIX): continue
        # Undefined imports, data symbols, local or hidden functions cannot satisfy JNI.
        if not (info >> 4 in (1, 2) and info & 15 == 2 and other & 3 in (0, 3) and 0 < index < len(sections)):
            continue
        address = address & ~1 if machine == 40 else address
        mapped(address, size, execute=True)
        owner = sections[index]
        require(owner[2] & 6 == 6 and owner[3] <= address and address + size <= owner[3]+owner[5], 'JNI export outside executable section')
        require(text not in exports, 'Duplicate JNI export')
        exports.add(text)
    return exports

def verify_library(data, abi, methods):
    expected = {PREFIX + name for name in methods}
    require(REQUIRED in expected, 'Current Kotlin exportDescriptorLock declaration missing')
    actual = defined_functions(data, abi)
    require(actual == expected, 'JNI export contract mismatch for ' + abi + ': missing=' + ','.join(sorted(expected-actual)) + '; unexpected=' + ','.join(sorted(actual-expected)))
    return sorted(actual)

def verify_composite_jni(root, composite):
    methods = required_methods(no_links(Path(root)/KOTLIN).read_text())
    native = no_links(Path(composite)/'native')
    require(native.is_dir(), 'Composite native directory missing')
    require({p.name for p in native.iterdir()} == set(ABIS), 'Composite JNI ABI set mismatch')
    for abi in ABIS:
        library = no_links(native/abi/'libdroide_files.so')
        require(library.is_file() and 0 < library.stat().st_size <= 64*1024*1024, 'Invalid composite JNI file: '+abi)
        verify_library(library.read_bytes(), abi, methods)
