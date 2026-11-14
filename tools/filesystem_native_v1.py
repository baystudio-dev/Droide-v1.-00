#!/usr/bin/env python3
"""Build-only current-source filesystem payload. Never packages or admits an APK."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess

NDK = '28.2.13676358'
CMAKE = '3.22.1'
ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86': (1, 3), 'x86_64': (2, 62)}
PREFIX = 'Java_com_baystudio_droide_core_NativeFilePublication_'
CONTRACT = Path(__file__).with_name('filesystem-native-v1.json')
STATUS = 'BUILD_ONLY_DEVICE_UNVALIDATED'

class Rejected(ValueError):
    """A build input, artifact or receipt did not meet the fixed contract."""

def require(condition, message):
    if not condition:
        raise Rejected(message)

def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def load(path):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs)

def save(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')

def no_links(path):
    path = Path(os.path.abspath(path))
    require(not any(p.is_symlink() for p in [path, *path.parents]), 'symlinked path: ' + str(path))
    return path

def disjoint(left, right):
    return left != right and left not in right.parents and right not in left.parents

def snapshot(root):
    """Measure all installed inputs, including internal NDK symlink identities."""
    root = no_links(root)
    result = {}
    for path in sorted(root.rglob('*')):
        name = str(path.relative_to(root))
        if path.is_symlink():
            target = path.resolve(strict=True)
            require(target == root or root in target.parents, 'external toolchain symlink: ' + name)
            result[name] = {'link': os.readlink(path)}
        elif path.is_file():
            result[name] = {'sha256': sha(path), 'size': path.stat().st_size}
        elif not path.is_dir():
            raise Rejected('non-ordinary toolchain entry: ' + name)
    return result

def kotlin_contract(text):
    types = {'Int': 'I', 'Long': 'J', 'Boolean': 'Z', 'ByteArray': '[B', 'LongArray': '[J'}
    result = {}
    for name, parameters, returns in re.findall(r'@JvmStatic\s+external fun (\w+)\(([^)]*)\): (\w+)', text):
        require(name not in result, 'duplicate JNI declaration')
        try:
            args = ''.join(types[p.split(':')[1].strip()] for p in parameters.split(',') if p.strip())
            result[name] = '(' + args + ')' + types[returns]
        except (KeyError, IndexError) as error:
            raise Rejected('unsupported JNI declaration') from error
    return result

def header(methods):
    c = {'I': 'jint', 'J': 'jlong', 'Z': 'jboolean', '[B': 'jbyteArray', '[J': 'jlongArray'}
    lines = ['/* Generated from the frozen Kotlin descriptor contract. */', '#include <jni.h>']
    for name, descriptor in sorted(methods.items()):
        args, returns = descriptor[1:].split(')')
        parts = re.findall(r'\[[BJ]|[IJZ]', args)
        require(''.join(parts) == args and returns in c, 'unsupported JNI descriptor')
        signature = ', '.join(['JNIEnv *', 'jclass', *(c[p] for p in parts)])
        lines.append(f'JNIEXPORT {c[returns]} JNICALL {PREFIX}{name}({signature});')
    return '\n'.join(lines) + '\n'

# Parse ELF bytes independently of build-receipt assertions or readelf text formatting.
def inspect_elf(data, abi, methods):
    require(abi in ABIS and data[:4] == b'\x7fELF', 'invalid ELF/ABI')
    require(len(data) >= 64 and data[4:7] == bytes([ABIS[abi][0], 1, 1]), 'ELF class/endian/version mismatch')
    wide = data[4] == 2
    def unpack(fmt, offset):
        size = struct.calcsize('<' + fmt)
        require(0 <= offset <= len(data) - size, 'truncated ELF structure')
        return struct.unpack_from('<' + fmt, data, offset)
    h = unpack('HHIQQQIHHHHHH' if wide else 'HHIIIIIHHHHHH', 16)
    kind, machine, version, _, phoff, shoff, _, ehsize, phsize, phnum, shsize, shnum, _ = h
    require((kind, machine, version) == (3, ABIS[abi][1], 1), 'ELF machine/type mismatch')
    require(ehsize == (64 if wide else 52) and phnum > 0 and shnum > 0, 'invalid ELF tables')
    require(phsize == (56 if wide else 32) and shsize == (64 if wide else 40), 'invalid ELF entry size')
    loads, stack, relro, dynamic_segments = [], [], [], []
    for i in range(phnum):
        p = unpack('IIQQQQQQ' if wide else 'IIIIIIII', phoff + i * phsize)
        if wide: typ, flags, offset, address, _, size, memory, align = p
        else: typ, offset, address, _, size, memory, flags, align = p
        require(offset + size <= len(data) and size <= memory, 'invalid ELF segment bounds')
        if typ == 1:
            require(align >= 16384 and align & (align - 1) == 0 and (address - offset) % align == 0, 'PT_LOAD not 16KiB aligned')
            require(flags & 3 != 3, 'writable executable segment')
            loads.append({'offset': offset, 'address': address, 'alignment': align,
                          'fileSize': size, 'memorySize': memory, 'flags': flags})
        if typ == 2: dynamic_segments.append((offset, address, size, memory))
        if typ == 0x6474e551: stack.append(flags)
        if typ == 0x6474e552: relro.append(size)
    require(loads and len(stack) == 1 and not stack[0] & 1 and any(relro), 'missing NX stack/RELRO')
    require(len(dynamic_segments) == 1, 'missing/duplicate PT_DYNAMIC')
    def mapped_offset(address, size, executable=False):
        require(size > 0, 'empty loader range')
        matches = [p['offset'] + address - p['address'] for p in loads
                   if p['address'] <= address and address + size <= p['address'] + p['fileSize']
                   and (not executable or p['flags'] & 1)]
        require(len(matches) == 1, 'loader range outside unique file-backed PT_LOAD')
        return matches[0]
    sections = [unpack('IIQQQQIIQQ' if wide else 'IIIIIIIIII', shoff + i * shsize) for i in range(shnum)]
    def contents(section):
        offset, size = section[4:6]
        require(offset + size <= len(data), 'truncated ELF section')
        return data[offset:offset + size]
    android_notes = []
    for section in sections:
        if section[1] != 7:
            continue
        notes = contents(section)
        offset = 0
        while offset < len(notes):
            require(offset + 12 <= len(notes), 'truncated ELF note')
            name_size, descriptor_size, note_type = struct.unpack_from('<III', notes, offset)
            name_start = offset + 12
            descriptor_start = name_start + ((name_size + 3) & ~3)
            end = descriptor_start + ((descriptor_size + 3) & ~3)
            require(end <= len(notes), 'ELF note range invalid')
            name = notes[name_start:name_start + name_size]
            descriptor = notes[descriptor_start:descriptor_start + descriptor_size]
            if name == b'Android\0' and note_type == 1:
                require(mapped_offset(section[3], section[5]) == section[4], 'Android note not mapped')
                require(descriptor_size == 132, 'unexpected Android identity note shape')
                api = struct.unpack_from('<I', descriptor)[0]
                ndk_version = descriptor[4:68].split(b'\0', 1)[0]
                ndk_build = descriptor[68:132].split(b'\0', 1)[0]
                android_notes.append((api, ndk_version, ndk_build))
            offset = end
    require(android_notes == [(29, b'r28c', b'13676358')], 'emitted Android API/NDK identity mismatch')
    def string(table, offset):
        require(0 <= offset < len(table), 'bad ELF string offset')
        end = table.find(b'\0', offset)
        require(end >= 0, 'unterminated ELF string')
        return table[offset:end].decode('ascii')
    def strings(section):
        require(section[6] < len(sections) and sections[section[6]][1] == 3, 'bad ELF string table')
        return contents(sections[section[6]])
    symbols = [s for s in sections if s[1] == 11]
    dynamics = [s for s in sections if s[1] == 6]
    require(len(symbols) == len(dynamics) == 1, 'missing/duplicate ELF dynamic tables')
    exports, undefined = [], []
    s = symbols[0]; entry = 24 if wide else 16
    require(s[9] == entry and s[5] % entry == 0, 'bad ELF symbol size')
    require(mapped_offset(s[3], s[5]) == s[4], 'dynsym section not loader-mapped')
    table = strings(s)
    for offset in range(s[4], s[4] + s[5], entry):
        if wide: name, info, other, index, value, symbol_size = unpack('IBBHQQ', offset)
        else: name, value, symbol_size, info, other, index = unpack('IIIBBH', offset)
        name = string(table, name)
        if name and info >> 4 in (1, 2):
            if index == 0: undefined.append(name)
            elif other & 3 in (0, 3):
                require(info & 15 == 2 and index < len(sections), 'export is not a defined function')
                function_address = value & ~1 if machine == 40 else value
                mapped_offset(function_address, symbol_size, executable=True)
                owner = sections[index]
                require(owner[2] & 6 == 6 and owner[3] <= function_address and
                        function_address + symbol_size <= owner[3] + owner[5], 'export outside executable section')
                exports.append(name)
    require(sorted(exports) == sorted(PREFIX + n for n in methods), 'JNI dynamic export set mismatch')
    # API29 bionic FORTIFY open/openat wrappers, verified against all four NDK stubs.
    # Reviewed exportDescriptorLock uses the API29 bionic flock import; exact name only.
    allowed_imports = {'__open_2', '__openat_2', '__cxa_finalize', '__cxa_atexit', '__register_atfork', '__stack_chk_fail', '__stack_chk_guard', '__errno',
                       'fstatat', 'syscall', 'memchr', 'fsync', 'fstat', 'fcntl', 'flock', 'close', 'openat', 'open',
                       'mkdirat', 'read', 'write', 'fchmod', 'strchr', 'strlen', 'strcmp', 'memcpy', 'memset',
                       '__memcpy_chk', '__read_chk', '__write_chk'}
    require(set(undefined) <= allowed_imports, 'unexpected undefined dynamic symbol')
    d = dynamics[0]; entry = 16 if wide else 8
    require(dynamic_segments[0] == (d[4], d[3], d[5], d[5]), 'PT_DYNAMIC differs from inspected section')
    require(mapped_offset(d[3], d[5]) == d[4], 'dynamic section not loader-mapped')
    require(d[9] == entry and d[5] % entry == 0, 'bad ELF dynamic entry size')
    require(d[6] == s[6], 'dynamic and symbol strings differ')
    table = strings(d); tags = {}; terminated = False
    for offset in range(d[4], d[4] + d[5], entry):
        tag, value = unpack('qQ' if wide else 'iI', offset)
        if tag == 0:
            terminated = True
            break
        tags.setdefault(tag, []).append(value)
    require(terminated, 'unterminated loader dynamic table')
    string_section = sections[d[6]]
    for tag, expected in ((5, string_section[3]), (10, string_section[5]), (6, s[3]), (11, s[9])):
        require(tags.get(tag) == [expected], 'loader dynamic pointer/size mismatch')
    require(mapped_offset(string_section[3], string_section[5]) == string_section[4], 'dynstr not loader-mapped')
    require(not any(t in tags for t in (15, 22, 29)), 'RPATH/RUNPATH/TEXTREL forbidden')
    require(not any(v & 4 for v in tags.get(30, [])), 'dynamic text relocations forbidden')
    require(24 in tags or any(v & 8 for v in tags.get(30, [])) or any(v & 1 for v in tags.get(0x6ffffffb, [])), 'BIND_NOW missing')
    require([string(table, x) for x in tags.get(14, [])] == ['libdroide_files.so'], 'wrong SONAME')
    needed = [string(table, x) for x in tags.get(1, [])]
    require(len(needed) == len(set(needed)) and set(needed) == {'libc.so', 'libm.so', 'libdl.so'}, 'DT_NEEDED mismatch')
    return {'androidApi': 29, 'androidNdkVersion': 'r28c', 'androidNdkBuild': '13676358', 'exports': sorted(exports), 'undefined': sorted(undefined), 'needed': sorted(needed), 'loadSegments': loads}

def inputs(root, sdk, contract):
    for relative, expected in contract['sourceSha256'].items():
        path = no_links(root / relative)
        require(sha(path) == expected, 'source pin mismatch: ' + relative)
    declarations = kotlin_contract((root / contract['kotlin']).read_text())
    require(declarations == contract['methods'], 'Kotlin descriptor contract mismatch')
    ndk = sdk / 'ndk' / NDK; cmake = sdk / 'cmake' / CMAKE
    for directory, version in ((ndk, NDK), (cmake, CMAKE)):
        require(re.search(r'^Pkg.Revision\s*=\s*' + re.escape(version) + r'\s*$', (no_links(directory) / 'source.properties').read_text(), re.M), 'toolchain revision mismatch')
    tools = {n: (cmake / 'bin' / n if n in ('cmake', 'ninja') else ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin' / n) for n in contract['toolSha256']}
    for name, expected in contract['toolSha256'].items():
        require(sha(tools[name]) == expected, 'historical tool binary identity mismatch: ' + name)
    measured = {'ndk': snapshot(ndk), 'cmake': snapshot(cmake), 'source': {p: sha(root / p) for p in contract['sourceSha256']},
                'tooling': {str(p.relative_to(root)): sha(no_links(p)) for p in (Path(__file__), CONTRACT,
                    root / 'tools/tests/test_filesystem_native_v1.py', root / 'tools/filesystem-native-v1.md')}}
    return tools, measured

def commands(root, sdk, output, stage, abi, tools):
    work = output / stage / abi
    ndk = sdk / 'ndk' / NDK
    flags = ' '.join(f'-{kind}={old}={new}' for old, new in ((root, '/droide/source'), (output / stage, '/droide/native'), (ndk, '/droide/ndk')) for kind in ('ffile-prefix-map', 'fdebug-prefix-map'))
    flags += ' -include ' + str(output / stage / 'jni-contract.h')
    return [
        [str(tools['cmake']), '-S', str(root / 'app/src/main/cpp'), '-B', str(work), '-G', 'Ninja',
         '-DCMAKE_MAKE_PROGRAM=' + str(tools['ninja']), '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
         '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-29', '-DCMAKE_BUILD_TYPE=Debug', '-DCMAKE_EXPORT_COMPILE_COMMANDS=ON', '-DCMAKE_C_FLAGS=' + flags],
        [str(tools['cmake']), '--build', str(work), '--target', 'droide_files', '--parallel', '2', '--verbose'],
    ]

def evidence(output, root, sdk, contract, tools):
    result = {}
    for stage in ('primary', 'independent'):
        require(no_links(output / stage / 'jni-contract.h').read_text() == header(contract['methods']), 'generated header mismatch')
        for abi in ABIS:
            directory = output / stage / abi
            artifact = no_links(directory / 'libdroide_files.so')
            require(not (directory / 'libtermux.so').exists(), 'unexpected Termux build')
            record = load(no_links(directory / 'commands.json'))
            require(record == [{'argv': cmd, 'exit': 0} for cmd in commands(root, sdk, output, stage, abi, tools)], 'build recipe/exit record mismatch')
            result[stage + '/' + abi] = {'sha256': sha(artifact), 'size': artifact.stat().st_size, 'elf': inspect_elf(artifact.read_bytes(), abi, contract['methods']),
                'evidenceSha256': {name: sha(no_links(directory / name)) for name in ('commands.json', 'command-0.log', 'command-1.log', 'compile_commands.json')}}
    for abi in ABIS:
        require(all(result['primary/' + abi][key] == result['independent/' + abi][key] for key in ('sha256', 'size', 'elf')), 'independent output mismatch: ' + abi)
    return result

def execute(root, sdk, output, verify=False):
    root, sdk, output = map(no_links, (root, sdk, output))
    require(all(disjoint(a, b) for a, b in ((root, output), (sdk, output), (root, sdk))), 'source/toolchain/output overlap')
    require(all(re.fullmatch(r'[A-Za-z0-9_./-]+', str(p)) for p in (root, sdk, output)), 'unsupported recipe path characters')
    require(no_links(Path(__file__)) == root / 'tools' / Path(__file__).name, 'run the tooling from the selected source root')
    contract = load(no_links(CONTRACT))
    require(contract['schema'] == 'filesystem-native-v1', 'wrong contract schema')
    if not verify:
        require(not output.exists(), 'output already exists; no silent reuse')
    else:
        require(output.is_dir(), 'missing evidence directory')
    tools, before = inputs(root, sdk, contract)
    if not verify:
        output.mkdir(parents=True, exist_ok=False)
        save(output / 'inputs-before.json', before)
        for stage in ('primary', 'independent'):
            stage_root = output / stage; stage_root.mkdir()
            (stage_root / 'jni-contract.h').write_text(header(contract['methods']))
            home = stage_root / 'home'; home.mkdir()
            temp = stage_root / 'tmp'; temp.mkdir()
            env = {'PATH': str(tools['cmake'].parent) + ':' + str(tools['clang'].parent) + ':/usr/bin:/bin', 'HOME': str(home), 'TMPDIR': str(temp), 'LC_ALL': 'C', 'LANG': 'C', 'TZ': 'UTC', 'SOURCE_DATE_EPOCH': '0'}
            for abi in ABIS:
                directory = stage_root / abi; directory.mkdir()
                record = []
                for index, cmd in enumerate(commands(root, sdk, output, stage, abi, tools)):
                    with (directory / f'command-{index}.log').open('wb') as log:
                        completed = subprocess.run(cmd, env=env, cwd=stage_root, stdout=log, stderr=subprocess.STDOUT, check=False)
                    record.append({'argv': cmd, 'exit': completed.returncode})
                    save(directory / 'commands.json', record)
                    require(completed.returncode == 0, 'native command failed; inspect retained log')
    require(load(no_links(output / 'inputs-before.json')) == before, 'input manifest changed since build')
    artifacts = evidence(output, root, sdk, contract, tools)
    _, after = inputs(root, sdk, contract)
    require(before == after, 'inputs changed during build/verification')
    receipt = {'schema': 'filesystem-native-v1', 'status': STATUS, 'releaseCompatible': False, 'deviceValidated': False, 'apkAdmitted': False,
               'sourceArchiveSha256': contract['sourceArchiveSha256'], 'api': 29, 'ndk': NDK, 'cmake': CMAKE,
               'inputManifestSha256': sha(output / 'inputs-before.json'), 'contractSha256': sha(CONTRACT), 'artifacts': artifacts,
               'toolchainProvenance': 'Installed tree measured; named tool binaries match historical pins. No vendor archive provenance claimed.'}
    if verify:
        require(load(no_links(output / 'receipt.json')) == receipt, 'receipt differs from measured artifacts/inputs')
    else:
        save(output / 'receipt.json', receipt)
    return receipt

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('build', 'verify'))
    for name in ('root', 'sdk', 'output'):
        parser.add_argument('--' + name, required=True, type=Path)
    args = parser.parse_args()
    try:
        result = execute(args.root, args.sdk, args.output, args.action == 'verify')
        print(json.dumps({'status': result['status'], 'abis': list(ABIS), 'deviceValidated': False}))
    except (Rejected, OSError, KeyError, ValueError, struct.error) as error:
        raise SystemExit('FILESYSTEM_NATIVE_V1_REJECTED: ' + str(error)) from error

if __name__ == '__main__':
    main()
