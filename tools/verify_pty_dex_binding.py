#!/usr/bin/env python3
"""Fail-closed, register-exact PTY class binding. Never executes APK/PTY code."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import zipfile
import zlib

TARGET = 'Lcom/termux/terminal/TerminalPtyIo;'
MAX_FILE = 256 * 1024 * 1024
DEX_NAME = re.compile(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex')


class BindingError(ValueError):
    pass


def require(ok, message):
    if not ok:
        raise BindingError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked_archive(data: bytes) -> dict[str, bytes]:
    """In-memory ZIP, never extraction; reject ambiguous names and special files."""
    require(len(data) <= MAX_FILE, 'archive too large')
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            result = {}
            total = 0
            for item in archive.infolist():
                name = item.filename
                path = PurePosixPath(name)
                require(name and '\0' not in name and ':' not in name and '\\' not in name and not path.is_absolute() and
                        '..' not in path.parts and '.' not in name.split('/') and
                        '//' not in name and name not in result, 'unsafe/duplicate ZIP entry')
                mode = item.external_attr >> 16
                require(not stat.S_ISLNK(mode) and stat.S_IFMT(mode) in
                        (0, stat.S_IFREG, stat.S_IFDIR), 'special ZIP entry')
                require(not item.flag_bits & 1, 'encrypted ZIP entry')
                total += item.file_size
                require(total <= 4 * MAX_FILE and item.file_size <= MAX_FILE, 'ZIP data too large')
                result[name] = archive.read(item)
            return result
    except (zipfile.BadZipFile, RuntimeError, OSError) as exc:
        raise BindingError('invalid ZIP: ' + str(exc)) from exc


class Dex:
    """Bounds-checked standard little-endian DEX 035/037/038/039/040 reader."""
    def __init__(self, data: bytes):
        self.data = data
        require(112 <= len(data) <= MAX_FILE, 'truncated/oversized DEX')
        require(data[:8] in [b'dex\n' + v + b'\0' for v in
                            (b'035', b'037', b'038', b'039', b'040')], 'unsupported DEX version')
        self.version = int(data[4:7])
        require(self.u32(32) == len(data) and self.u32(36) == 112 and
                self.u32(40) == 0x12345678, 'invalid DEX header')
        require(self.u32(8) == zlib.adler32(data[12:]) & 0xffffffff and
                data[12:32] == hashlib.sha1(data[32:]).digest(), 'invalid DEX checksum/signature')
        require(self.u32(44) == 0 and self.u32(48) == 0, 'linked DEX unsupported')
        data_size, data_off = self.u32(104), self.u32(108)
        require(data_off >= 112 and data_off % 4 == 0 and
                data_off + data_size == len(data), 'invalid DEX data range')
        map_off = self.u32(52)
        require(map_off >= data_off and map_off % 4 == 0, 'invalid map offset')
        count = self.u32(map_off)
        self.span(map_off + 4, count, 12)
        entries = []
        known = set(range(9)) | {0x1000, 0x1001, 0x1002, 0x1003,
                               0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006}
        for i in range(count):
            kind, unused, size, off = self.unpack('<HHII', map_off + 4 + i * 12)
            require(kind in known and unused == 0 and size > 0 and off < len(data), 'invalid/unsupported map entry')
            if entries:
                require(entries[-1][2] < off, 'overlapping/unsorted DEX map')
            entries.append((kind, size, off))
        require(len({e[0] for e in entries}) == count, 'duplicate map type')
        self.maps = {kind: (size, off, entries[i + 1][2] if i + 1 < count else len(data))
                     for i, (kind, size, off) in enumerate(entries)}
        require(self.maps.get(0, ())[:2] == (1, 0) and
                self.maps.get(0x1000, ())[:2] == (1, map_off), 'missing DEX header/map entry')
        require(map_off + 4 + count * 12 <= self.maps[0x1000][2], 'map overlaps next section')
        for kind, size, off in entries:
            if kind >= 0x1000:
                require(off >= data_off, 'data section outside data area')
        widths = {0: 112, 1: 4, 2: 4, 3: 12, 4: 8, 5: 8, 6: 32, 7: 4, 8: 8}
        for kind, width in widths.items():
            if kind in self.maps:
                size, off, end = self.maps[kind]
                require(off % 4 == 0 and off + size * width <= end, 'overlapping ID table')
        tables = []
        for kind, pos in enumerate((56, 64, 72, 80, 88, 96), 1):
            size, off = self.u32(pos), self.u32(pos + 4)
            require((size == 0 and off == 0 and kind not in self.maps) or
                    (size > 0 and self.maps.get(kind, ())[:2] == (size, off)), 'header/map table mismatch')
            tables.append((size, off))
        self.strings = [self.string(self.u32(tables[0][1] + i * 4)) for i in range(tables[0][0])]
        self.unique(self.strings, 'string IDs')
        self.types = [self.ref(self.strings, self.u32(tables[1][1] + i * 4)) for i in range(tables[1][0])]
        self.unique(self.types, 'type IDs')
        self.protos = []
        for i in range(tables[2][0]):
            shorty, ret, args = self.unpack('<III', tables[2][1] + i * 12)
            ret = self.ref(self.types, ret)
            args = self.type_list(args)
            expected = ''.join('L' if t.startswith(('L', '[')) else t for t in (ret, *args))
            require(self.ref(self.strings, shorty) == expected, 'invalid prototype shorty')
            self.protos.append((ret, args))
        self.unique(self.protos, 'prototype IDs')
        self.fields = []
        for i in range(tables[3][0]):
            owner, typ, name = self.unpack('<HHI', tables[3][1] + i * 8)
            self.fields.append((self.ref(self.types, owner), self.ref(self.strings, name), self.ref(self.types, typ)))
        self.unique(self.fields, 'field IDs')
        self.methods = []
        for i in range(tables[4][0]):
            owner, proto, name = self.unpack('<HHI', tables[4][1] + i * 8)
            self.methods.append((self.ref(self.types, owner), self.ref(self.strings, name), self.ref(self.protos, proto)))
        self.unique(self.methods, 'method IDs')
        self.classes = {}
        for i in range(tables[5][0]):
            values = self.unpack('<8I', tables[5][1] + i * 32)
            name = self.ref(self.types, values[0])
            require(name not in self.classes, 'duplicate class definition')
            self.classes[name] = values

    @staticmethod
    def unique(values, label):
        require(len(values) == len(set(values)), 'duplicate ' + label)
        if label in ('string IDs', 'type IDs', 'prototype IDs', 'field IDs', 'method IDs'):
            require(values == sorted(values), 'unsorted ' + label)

    @staticmethod
    def ref(table, index):
        require(0 <= index < len(table), 'DEX pool index out of range')
        return table[index]

    def span(self, offset, count, width=1):
        require(offset >= 0 and count >= 0 and width > 0 and
                offset + count * width <= len(self.data), 'truncated DEX range')

    def unpack(self, fmt, offset):
        self.span(offset, struct.calcsize(fmt))
        return struct.unpack_from(fmt, self.data, offset)

    def u32(self, offset):
        return self.unpack('<I', offset)[0]

    def section(self, offset, kind, alignment=1):
        require(kind in self.maps, 'missing DEX section')
        _, start, end = self.maps[kind]
        require(start <= offset < end and offset % alignment == 0, 'invalid section offset')
        return end

    def leb(self, offset, signed=False, end=None):
        end = len(self.data) if end is None else end
        value = 0
        for i in range(5):
            require(offset < end, 'truncated LEB128')
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << (i * 7)
            if not byte & 128:
                if signed and byte & 64:
                    value -= 1 << ((i + 1) * 7)
                require(-(1 << 31) <= value < (1 << 31) if signed else 0 <= value <= 0xffffffff,
                        'overflowed LEB128')
                return value, offset
        raise BindingError('unterminated LEB128')

    def string(self, offset):
        end = self.section(offset, 0x2002)
        size, offset = self.leb(offset, end=end)
        units = []
        while True:
            require(offset < end, 'unterminated DEX string')
            first = self.data[offset]
            offset += 1
            if first == 0:
                break
            if first < 0x80:
                value = first
            elif first & 0xe0 == 0xc0:
                require(offset < end and self.data[offset] & 0xc0 == 0x80, 'invalid MUTF8')
                value = ((first & 31) << 6) | (self.data[offset] & 63)
                offset += 1
                require(value >= 0x80 or value == 0, 'overlong MUTF8')
            elif first & 0xf0 == 0xe0:
                require(offset + 2 <= end and all(b & 0xc0 == 0x80 for b in self.data[offset:offset + 2]), 'invalid MUTF8')
                value = ((first & 15) << 12) | ((self.data[offset] & 63) << 6) | (self.data[offset + 1] & 63)
                offset += 2
                require(value >= 0x800, 'overlong MUTF8')
            else:
                raise BindingError('invalid MUTF8 leading byte')
            units.append(value)
            require(len(units) <= size, 'DEX string length mismatch')
        require(len(units) == size, 'DEX string length mismatch')
        return ''.join(map(chr, units))

    def type_list(self, offset):
        if offset == 0:
            return ()
        end = self.section(offset, 0x1001, 4)
        size = self.u32(offset)
        require(offset + 4 + size * 2 <= end, 'truncated type list')
        return tuple(self.ref(self.types, self.unpack('<H', offset + 4 + 2 * i)[0]) for i in range(size))

    def value(self, offset, end, depth=0):
        require(depth < 64 and offset < end, 'invalid/too deep encoded value')
        tag = self.data[offset]
        offset += 1
        kind, arg = tag & 31, tag >> 5
        maxima = {0: 0, 2: 1, 3: 1, 4: 3, 6: 7, 16: 3, 17: 7,
                  21: 3, 23: 3, 24: 3, 25: 3, 26: 3, 27: 3}
        if kind == 21:
            require(self.version >= 38, 'encoded prototype requires DEX 038')
        if kind in maxima:
            require(arg <= maxima[kind] and offset + arg + 1 <= end, 'invalid encoded value width')
            raw = self.data[offset:offset + arg + 1]
            offset += arg + 1
            val = int.from_bytes(raw, 'little', signed=kind in (0, 2, 4, 6))
            if kind in (16, 17):
                val <<= 8 * (maxima[kind] - arg)
            tables = {21: self.protos, 23: self.strings, 24: self.types,
                      25: self.fields, 26: self.methods, 27: self.fields}
            if kind in tables:
                val = self.ref(tables[kind], val)
            return (kind, val), offset
        require(arg == 0 or kind == 31, 'invalid encoded value argument')
        if kind == 28:
            size, offset = self.leb(offset, end=end)
            require(size <= end - offset, 'truncated encoded array')
            values = []
            for _ in range(size):
                val, offset = self.value(offset, end, depth + 1)
                values.append(val)
            return (kind, tuple(values)), offset
        if kind == 29:
            value, offset = self.annotation(offset, end, depth + 1)
            return (kind, value), offset
        if kind == 30:
            return (kind, None), offset
        if kind == 31:
            require(arg <= 1, 'invalid boolean encoded value')
            return (kind, arg), offset
        raise BindingError('unsupported encoded value kind')

    def annotation(self, offset, end, depth=0):
        typ, offset = self.leb(offset, end=end)
        size, offset = self.leb(offset, end=end)
        require(size <= end - offset, 'truncated annotation')
        values = []
        last = -1
        for _ in range(size):
            name, offset = self.leb(offset, end=end)
            require(name > last, 'duplicate/unsorted annotation element')
            last = name
            value, offset = self.value(offset, end, depth + 1)
            values.append((self.ref(self.strings, name), value))
        return (self.ref(self.types, typ), tuple(values)), offset

    def annotation_set(self, offset):
        if offset == 0:
            return ()
        end = self.section(offset, 0x1003, 4)
        size = self.u32(offset)
        require(offset + 4 + size * 4 <= end, 'truncated annotation set')
        result = []
        for i in range(size):
            pos = self.u32(offset + 4 + i * 4)
            limit = self.section(pos, 0x2004)
            visibility = self.data[pos]
            require(visibility <= 2, 'invalid annotation visibility')
            annotation, _ = self.annotation(pos + 1, limit)
            result.append((visibility, annotation))
        annotation_types = [v[1][0] for v in result]
        self.unique(annotation_types, 'annotation types')
        require(annotation_types == sorted(annotation_types), 'unsorted annotation set')
        return tuple(sorted(result))

    def annotations(self, offset):
        if offset == 0:
            return ((), (), (), ())
        end = self.section(offset, 0x2006, 4)
        cls, nf, nm, np = self.unpack('<4I', offset)
        require(offset + 16 + 8 * (nf + nm + np) <= end, 'truncated annotations directory')
        pos = offset + 16
        result = [self.annotation_set(cls)]
        for category, count, pool in ((0, nf, self.fields), (1, nm, self.methods), (2, np, self.methods)):
            items = []
            last = -1
            for _ in range(count):
                index, off = self.unpack('<II', pos)
                pos += 8
                require(index > last, 'duplicate/unsorted annotated member')
                last = index
                if category != 2:
                    value = self.annotation_set(off)
                else:
                    limit = self.section(off, 0x1002, 4)
                    size = self.u32(off)
                    require(off + 4 + size * 4 <= limit, 'truncated parameter annotations')
                    value = tuple(self.annotation_set(self.u32(off + 4 + 4 * i)) for i in range(size))
                items.append((self.ref(pool, index), value))
            result.append(tuple(sorted(items)))
        return tuple(result)

    def canonical_class(self, target=TARGET):
        if target not in self.classes:
            return None
        _, access, superclass, interfaces, source, annotations, class_data, static_values = self.classes[target]
        if source != 0xffffffff:
            self.ref(self.strings, source)  # Validate, but ignore source filename/debug metadata.
        super_name = None if superclass == 0xffffffff else self.ref(self.types, superclass)
        groups = [[], [], [], []]
        if class_data:
            end = self.section(class_data, 0x2000)
            counts = []
            pos = class_data
            for _ in range(4):
                count, pos = self.leb(pos, end=end)
                counts.append(count)
            for group, count in enumerate(counts):
                pool = self.fields if group < 2 else self.methods
                require(count <= len(pool), 'invalid member count')
                index = 0
                for i in range(count):
                    delta, pos = self.leb(pos, end=end)
                    require(i == 0 or delta > 0, 'duplicate/unsorted encoded member')
                    index += delta
                    flags, pos = self.leb(pos, end=end)
                    member = self.ref(pool, index)
                    require(member[0] == target, 'member declares wrong class')
                    if group < 2:
                        require(bool(flags & 8) == (group == 0), 'field static classification mismatch')
                        groups[group].append((member, flags))
                    else:
                        code, pos = self.leb(pos, end=end)
                        require(bool(code) == (not bool(flags & (0x100 | 0x400))), 'method code/access mismatch')
                        groups[group].append((member, flags, self.code(code) if code else None))
        self.unique([v[0] for group in groups[:2] for v in group], 'class fields')
        self.unique([v[0] for group in groups[2:] for v in group], 'class methods')
        values = ()
        if static_values:
            end = self.section(static_values, 0x2005)
            size, pos = self.leb(static_values, end=end)
            require(size <= len(groups[0]), 'too many static values')
            vals = []
            for _ in range(size):
                value, pos = self.value(pos, end)
                vals.append(value)
            values = tuple(vals)
        ann = self.annotations(annotations)
        field_set = {v[0] for g in groups[:2] for v in g}
        method_set = {v[0] for g in groups[2:] for v in g}
        require(all(m in field_set for m, _ in ann[1]) and
                all(m in method_set for g in ann[2:] for m, _ in g), 'annotation targets nonexistent member')
        return (target, access, super_name, self.type_list(interfaces),
                tuple(tuple(g) for g in groups), values, ann)

    def validate_debug(self, offset, registers, code_size):
        """Validate ignored debug grammar; malformed tables do not get a free pass."""
        end = self.section(offset, 0x2003)
        _, pos = self.leb(offset, end=end)
        params, pos = self.leb(pos, end=end)
        require(params <= end - pos, 'truncated debug parameters')
        def reference(table):
            nonlocal pos
            index, pos = self.leb(pos, end=end)
            if index:
                self.ref(table, index - 1)
        def register():
            nonlocal pos
            index, pos = self.leb(pos, end=end)
            require(index < registers, 'debug register out of range')
        for _ in range(params):
            reference(self.strings)
        address = 0
        while True:
            require(pos < end, 'unterminated debug program')
            op = self.data[pos]
            pos += 1
            if op == 0:
                return
            if op == 1:
                advance, pos = self.leb(pos, end=end)
                address += advance
            elif op == 2:
                _, pos = self.leb(pos, signed=True, end=end)
            elif op in (3, 4):
                register()
                reference(self.strings)
                reference(self.types)
                if op == 4:
                    reference(self.strings)
            elif op in (5, 6):
                register()
            elif op == 9:
                reference(self.strings)
            elif op >= 10:
                address += (op - 10) // 15
            require(address <= code_size, 'debug address outside code')

    def code(self, offset):
        end = self.section(offset, 0x2001, 4)
        regs, ins, outs, tries, debug, size = self.unpack('<4H2I', offset)
        require(ins <= regs and size > 0 and offset + 16 + 2 * size <= end, 'truncated/invalid code item')
        if debug:
            self.validate_debug(debug, regs, size)  # Contents validated but not compared.
        words = self.unpack('<' + str(size) + 'H', offset + 16)
        instructions, starts, payloads = self.instructions(words)
        pos = offset + 16 + size * 2
        handlers = {}
        try_list = []
        if tries:
            if size & 1:
                require(self.unpack('<H', pos)[0] == 0, 'nonzero try padding')
                pos += 2
            require(pos + 8 * tries <= end, 'truncated tries')
            handler_base = pos + 8 * tries
            nhandlers, hp = self.leb(handler_base, end=end)
            require(0 < nhandlers <= end - hp, 'invalid catch handler count')
            for _ in range(nhandlers):
                relative = hp - handler_base
                count, hp = self.leb(hp, signed=True, end=end)
                require(abs(count) <= end - hp, 'invalid catch count')
                caught = []
                for _ in range(abs(count)):
                    typ, hp = self.leb(hp, end=end)
                    addr, hp = self.leb(hp, end=end)
                    require(addr in starts, 'catch target not instruction boundary')
                    caught.append((self.ref(self.types, typ), addr))
                self.unique([t for t, _ in caught], 'catch types')
                catchall = None
                if count <= 0:
                    catchall, hp = self.leb(hp, end=end)
                    require(catchall in starts, 'catchall target not instruction boundary')
                handlers[relative] = (tuple(caught), catchall)
            last_end = 0
            used = set()
            for i in range(tries):
                start, length, handler = self.unpack('<IHH', pos + i * 8)
                stop = start + length
                require(length > 0 and start >= last_end and start in starts and
                        (stop in starts or stop in payloads or stop == size) and stop <= size and
                        not any(start <= p < stop for p in payloads), 'invalid try range')
                require(handler in handlers, 'catch handler offset is not an item boundary')
                used.add(handler)
                try_list.append((start, length, handlers[handler]))
                last_end = stop
            require(used == set(handlers), 'unused catch handler unsupported')
        return (regs, ins, outs, size, instructions, tuple(try_list))

    def instructions(self, words):
        """Keep EVERY code unit except replace known index words with resolved refs."""
        widths = {0: 1, 1: 1, 2: 2, 3: 3, 4: 1, 5: 2, 6: 3, 7: 1, 8: 2, 9: 3,
                  **{i: 1 for i in range(0x0a, 0x13)}, 0x13: 2, 0x14: 3, 0x15: 2,
                  0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2, 0x1a: 2, 0x1b: 3, 0x1c: 2,
                  0x1d: 1, 0x1e: 1, 0x1f: 2, 0x20: 2, 0x21: 1, 0x22: 2, 0x23: 2,
                  0x24: 3, 0x25: 3, 0x26: 3, 0x27: 1, 0x28: 1, 0x29: 2, 0x2a: 3,
                  0x2b: 3, 0x2c: 3, **{i: 2 for i in range(0x2d, 0x3e)},
                  **{i: 2 for i in range(0x44, 0x6e)}, **{i: 3 for i in range(0x6e, 0x73)},
                  **{i: 3 for i in range(0x74, 0x79)}, **{i: 1 for i in range(0x7b, 0x90)},
                  **{i: 2 for i in range(0x90, 0xb0)}, **{i: 1 for i in range(0xb0, 0xd0)},
                  **{i: 2 for i in range(0xd0, 0xe3)}, 0xfa: 4, 0xfb: 4, 0xff: 2}
        def signed(val, bits):
            return val - (1 << bits) if val & (1 << (bits - 1)) else val
        def read32(p):
            require(p + 2 <= len(words), 'truncated instruction/payload')
            return words[p] | words[p + 1] << 16
        result, starts, payloads, branches, uses = [], set(), {}, [], []
        pc = 0
        while pc < len(words):
            word, op = words[pc], words[pc] & 255
            if op == 0 and word != 0:
                require(pc % 2 == 0 and word in (0x100, 0x200, 0x300), 'unsupported/misaligned payload')
                require(pc + 2 <= len(words), 'truncated payload')
                count = words[pc + 1]
                if word == 0x100:
                    width = 4 + count * 2
                elif word == 0x200:
                    width = 2 + count * 4
                else:
                    require(count in (1, 2, 4, 8), 'invalid array element width')
                    width = 4 + (count * read32(pc + 2) + 1) // 2
                require(pc + width <= len(words), 'truncated payload data')
                payloads[pc] = (word, tuple(words[pc:pc + width]))
                result.append((pc, tuple(words[pc:pc + width])))
                pc += width
                continue
            require(op in widths, f'unsupported opcode 0x{op:02x}')
            require(op < 0xfa or self.version >= (39 if op == 0xff else 38), 'opcode requires newer DEX')
            width = widths[op]
            require(pc + width <= len(words), 'truncated instruction')
            raw = list(words[pc:pc + width])
            starts.add(pc)
            if op in (0x1a, 0x1b):
                index = raw[1] if op == 0x1a else read32(pc + 1)
                raw[1] = ('string', self.ref(self.strings, index))
                if op == 0x1b:
                    raw[2] = 'resolved-index-high'
            elif op in (0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25):
                raw[1] = ('type', self.ref(self.types, raw[1]))
            elif 0x52 <= op <= 0x6d:
                raw[1] = ('field', self.ref(self.fields, raw[1]))
            elif 0x6e <= op <= 0x72 or 0x74 <= op <= 0x78 or op in (0xfa, 0xfb):
                raw[1] = ('method', self.ref(self.methods, raw[1]))
                if op in (0xfa, 0xfb):
                    raw[3] = ('proto', self.ref(self.protos, raw[3]))
            elif op == 0xff:
                raw[1] = ('proto', self.ref(self.protos, raw[1]))
            if op == 0x28:
                branches.append(pc + signed(word >> 8, 8))
            elif op == 0x29 or 0x32 <= op <= 0x3d:
                branches.append(pc + signed(words[pc + 1], 16))
            elif op == 0x2a:
                branches.append(pc + signed(read32(pc + 1), 32))
            elif op in (0x26, 0x2b, 0x2c):
                uses.append((pc, op, pc + signed(read32(pc + 1), 32)))
            result.append((pc, tuple(raw)))
            pc += width
        require(all(target in starts for target in branches), 'branch target not instruction boundary')
        used = set()
        for origin, op, target in uses:
            require(target in payloads and payloads[target][0] ==
                    {0x26: 0x300, 0x2b: 0x100, 0x2c: 0x200}[op], 'payload target/type mismatch')
            used.add(target)
            payload = payloads[target][1]
            if op in (0x2b, 0x2c):
                n = payload[1]
                begin = 4 if op == 0x2b else 2 + 2 * n
                if op == 0x2c:
                    keys = [signed(payload[2 + 2 * i] | payload[3 + 2 * i] << 16, 32) for i in range(n)]
                    require(all(a < b for a, b in zip(keys, keys[1:])), 'unsorted sparse switch keys')
                for i in range(n):
                    dest = origin + signed(payload[begin + i * 2] | payload[begin + i * 2 + 1] << 16, 32)
                    require(dest in starts, 'switch target not instruction boundary')
        require(used == set(payloads), 'unreferenced payload unsupported')
        return tuple(result), starts, payloads


def canonical_class(data: bytes, target=TARGET):
    return Dex(data).canonical_class(target)


def target_from_dexes(entries: dict[str, bytes], target=TARGET):
    names = sorted(name for name in entries if DEX_NAME.fullmatch(name))
    require(names and 'classes.dex' in names, 'missing primary APK DEX')
    require(all(not name.endswith('.dex') or DEX_NAME.fullmatch(name) for name in entries),
            'unexpected DEX path/name')
    found = []
    for name in names:
        cls = canonical_class(entries[name], target)
        if cls is not None:
            found.append((name, cls))
    require(len(found) == 1, 'target must have exactly one definition across all DEX files')
    return found[0]


def compare_dex_entries(actual, reference):
    """Low-level comparison only. A caller-supplied reference is NOT provenance."""
    name, cls = target_from_dexes(actual)
    _, expected = target_from_dexes(reference)
    require(cls == expected, 'packaged PTY class differs from fresh candidate D8 reference')
    encoded = json.dumps(cls, ensure_ascii=True, separators=(',', ':')).encode()
    return {'targetDescriptor': TARGET, 'dexEntry': name,
            'canonicalClassSha256': digest(encoded), 'registerExactFullCodeMatch': True}
