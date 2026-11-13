"""Bounded data-only inspection of Droide's Android/Bionic PRoot PIE engine.

The static loader inspector deliberately remains separate. This module checks
the reviewed ELF64/AArch64 dynamic-linking contract and, before stripping,
compares the engine's symbol-backed constant/embedded data with caller-provided
source-build outputs. It never opens files, loads a library, executes a payload,
or establishes that instructions use the inspected data correctly at runtime.
"""
from __future__ import annotations

import hashlib
import struct


MAX_BINARY_BYTES = 8 * 1024 * 1024
MAX_PROGRAMS = 32
MAX_SECTIONS = 128
MAX_SYMBOLS = 16384
MAX_RELOCATIONS = 16384
MAX_STRINGS = 1024 * 1024
MAX_NAME = 1024
LOAD_ALIGNMENT = 0x4000
EXPECTED_NEEDED = ["libdl.so", "libc.so"]
INTERPRETER = b"/system/bin/linker64\0"
WRAPPERS = {"arm64": "_binary_loader_exe_", "arm32": "_binary_loader_m32_exe_"}
CONSTANT = "offset_to_pokedata_workaround"
_PROGRAMS = {1: "PT_LOAD", 2: "PT_DYNAMIC", 3: "PT_INTERP", 4: "PT_NOTE", 6: "PT_PHDR", 7: "PT_TLS",
             0x6474E550: "PT_GNU_EH_FRAME", 0x6474E551: "PT_GNU_STACK", 0x6474E552: "PT_GNU_RELRO"}
_SECTIONS = {0: "NULL", 1: "PROGBITS", 2: "SYMTAB", 3: "STRTAB", 4: "RELA", 6: "DYNAMIC",
             7: "NOTE", 8: "NOBITS", 11: "DYNSYM", 14: "INIT_ARRAY", 15: "FINI_ARRAY",
             0x6FFFFFF6: "GNU_HASH", 0x6FFFFFFE: "VERNEED", 0x6FFFFFFF: "VERSYM"}
_TAGS = {0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 20, 21, 23, 25, 27, 30,
         0x6FFFFEF5, 0x6FFFFFF0, 0x6FFFFFF9, 0x6FFFFFFB, 0x6FFFFFFE, 0x6FFFFFFF}


class EngineElfError(ValueError):
    """A dynamic engine input or source-built coupling expectation failed."""


def _require(condition, message):
    if not condition:
        raise EngineElfError(message)


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _span(start, size, limit, label):
    _require(0 <= start <= limit and 0 <= size <= limit - start, "invalid " + label + " span")
    return start, start + size


def _power2(value):
    return value > 0 and value & (value - 1) == 0


def _disjoint(spans, label):
    end = None
    for start, stop in sorted((a, b) for a, b in spans if a != b):
        _require(end is None or start >= end, "overlapping/duplicate " + label)
        end = stop


class _Engine:
    def __init__(self, raw):
        self.raw = raw
        _require(64 <= len(raw) <= MAX_BINARY_BYTES, "engine ELF outside size bound")
        _require(raw[:16] == b"\x7fELF\x02\x01\x01" + bytes(9), "wrong ELF64/LE/System-V identity")
        kind, machine, version, self.entry, self.phoff, self.shoff, flags, ehsize, phsize, self.phnum, shsize, self.shnum, self.shstr = self.unpack("HHIQQQIHHHHHH", 16)
        _require((kind, machine, version, flags) == (3, 183, 1, 0), "engine must be AArch64 ET_DYN with standard flags")
        _require(self.entry > 0 and self.entry % 4 == 0, "invalid engine entry point")
        _require((ehsize, phsize, shsize) == (64, 56, 64), "wrong ELF record dimensions")
        _require(1 <= self.phnum <= MAX_PROGRAMS and 2 <= self.shnum <= MAX_SECTIONS and 0 < self.shstr < self.shnum,
                 "invalid/extended ELF table counts or names index")
        _require(self.phoff >= 64 and self.shoff >= 64 and self.phoff % 8 == self.shoff % 8 == 0,
                 "misaligned/header-overlapping ELF tables")
        self.tables = [(0, 64), _span(self.phoff, self.phnum * 56, len(raw), "program table"),
                       _span(self.shoff, self.shnum * 64, len(raw), "section table")]
        _disjoint(self.tables, "ELF tables")
        self.programs = self.read_programs()
        self.loads = [p for p in self.programs if p["typeId"] == 1]
        self.validate_programs()
        self.sections = self.read_sections()
        self.validate_sections()
        self.dynamic = self.one_section(".dynamic", 6)
        self.dynsym = self.one_section(".dynsym", 11)
        self.dynstr = self.one_section(".dynstr", 3)
        self.dynamic_symbols = self.read_symbols(self.dynsym)
        self.tags = self.read_dynamic()
        self.validate_dynamic_tables()
        self.relocations = self.read_relocations()
        self.android_identity, self.build_id = self.read_notes()

    def unpack(self, fmt, offset):
        _span(offset, struct.calcsize("<" + fmt), len(self.raw), "ELF record")
        return struct.unpack_from("<" + fmt, self.raw, offset)

    def content(self, section):
        _require(section["typeId"] != 8, "NOBITS has no file payload")
        begin, end = _span(section["offset"], section["size"], len(self.raw), "section data")
        return self.raw[begin:end]

    @staticmethod
    def string(data, offset):
        _require(0 <= offset < len(data), "ELF string offset outside table")
        end = data.find(b"\0", offset, min(len(data), offset + MAX_NAME + 1))
        _require(end >= offset, "unterminated/oversized ELF string")
        value = data[offset:end]
        _require(all(32 <= byte < 127 for byte in value), "nonprintable/non-ASCII ELF string")
        return value.decode("ascii")

    def one_section(self, name, kind):
        matches = [s for s in self.sections if s["name"] == name]
        _require(len(matches) == 1 and matches[0]["typeId"] == kind, "missing/wrong section " + name)
        return matches[0]

    def mapped(self, address, size, *, memory=False, executable=False, writable=False, nonexecuting=False):
        _span(address, size, 1 << 64, "mapped address")
        matches = [p for p in self.loads if p["virtualAddress"] <= address
                   and address + size <= p["virtualAddress"] + p["memoryBytes" if memory else "fileBytes"]
                   and (not executable or p["flags"] & 1) and (not writable or p["flags"] & 2)
                   and (not nonexecuting or not p["flags"] & 1)]
        _require(len(matches) == 1, "range is not uniquely backed by a suitable PT_LOAD")
        p = matches[0]
        return p, p["offset"] + address - p["virtualAddress"]

    def read_programs(self):
        programs, seen = [], set()
        for index in range(self.phnum):
            row = self.unpack("IIQQQQQQ", self.phoff + index * 56)
            kind, flags, offset, address, physical, size, memory, alignment = row
            _require(kind in _PROGRAMS and row not in seen, "unsupported/duplicate program header")
            seen.add(row)
            _require(flags & ~7 == 0 and size <= memory <= MAX_BINARY_BYTES, "invalid program flags/size")
            _span(offset, size, len(self.raw), "program file")
            _span(address, memory, 1 << 64, "program memory")
            _require(physical == address and (alignment in (0, 1) or _power2(alignment)), "invalid physical address/alignment")
            programs.append({"index": index, "typeId": kind, "type": _PROGRAMS[kind], "flags": flags,
                             "offset": offset, "virtualAddress": address, "physicalAddress": physical,
                             "fileBytes": size, "memoryBytes": memory, "alignment": alignment})
        return programs

    def validate_programs(self):
        _require(self.loads, "missing engine LOAD segments")
        for p in self.loads:
            _require(p["flags"] in (4, 5, 6) and p["memoryBytes"] > 0, "unreadable/empty/RWX LOAD segment")
            _require(p["alignment"] == LOAD_ALIGNMENT and p["virtualAddress"] % LOAD_ALIGNMENT == p["offset"] % LOAD_ALIGNMENT,
                     "LOAD is not correctly 16-KiB aligned")
        _disjoint([(p["offset"], p["offset"] + p["fileBytes"]) for p in self.loads], "LOAD file spans")
        _disjoint([(p["virtualAddress"], p["virtualAddress"] + p["memoryBytes"]) for p in self.loads], "LOAD memory spans")
        self.mapped(self.entry, 4, executable=True)
        for kind in (2, 3, 0x6474E551, 0x6474E552):
            _require(sum(p["typeId"] == kind for p in self.programs) == 1, "missing/duplicate required dynamic program header")
        for kind in (6, 7, 0x6474E550):
            _require(sum(p["typeId"] == kind for p in self.programs) <= 1, "duplicate singleton program header")
        self.relro = next(p for p in self.programs if p["typeId"] == 0x6474E552)
        for p in self.programs:
            kind = p["typeId"]
            if kind == 1:
                continue
            if kind == 0x6474E551:
                _require(p["flags"] == 6 and all(p[k] == 0 for k in ("offset", "virtualAddress", "fileBytes", "memoryBytes")),
                         "stack is not empty RW/NX")
                continue
            if kind == 7:
                _require(p["flags"] == 4 and p["fileBytes"] == p["memoryBytes"] == 0, "nonempty/invalid TLS image outside proof contract")
                self.mapped(p["virtualAddress"], 0, memory=True, writable=True)
                continue
            _require(p["fileBytes"] > 0 and p["flags"] == (6 if kind == 2 else 4), "invalid metadata program permissions/size")
            load, offset = self.mapped(p["virtualAddress"], p["fileBytes"], writable=kind in (2, 0x6474E552), nonexecuting=True)
            _require(offset == p["offset"], "program file/virtual mapping mismatch")
            if kind == 0x6474E552:
                self.mapped(p["virtualAddress"], p["memoryBytes"], memory=True, writable=True, nonexecuting=True)
                _require((p["virtualAddress"] + p["memoryBytes"]) % LOAD_ALIGNMENT == 0, "RELRO end not page aligned")
            else:
                _require(p["fileBytes"] == p["memoryBytes"], "metadata program has unbacked memory")
            if kind == 6:
                _require((p["offset"], p["fileBytes"]) == (self.phoff, 56 * self.phnum), "PHDR range does not describe program table")
            if kind == 3:
                _require(self.raw[p["offset"]:p["offset"] + p["fileBytes"]] == INTERPRETER, "engine interpreter must be /system/bin/linker64")

    def read_sections(self):
        sections = []
        for index in range(self.shnum):
            row = self.unpack("IIQQQQIIQQ", self.shoff + index * 64)
            name, kind, flags, address, offset, size, link, info, alignment, entries = row
            if index == 0:
                _require(not any(row), "nonzero NULL section/extended numbering")
            else:
                _require(kind in _SECTIONS and kind != 0 and flags & ~(1 | 2 | 4 | 0x10 | 0x20 | 0x40 | 0x80 | 0x400) == 0,
                         "unsupported section type/flags")
                _require(0 <= link < self.shnum and size <= MAX_BINARY_BYTES and alignment <= MAX_BINARY_BYTES
                         and (alignment == 0 or _power2(alignment)), "invalid section dimensions/link")
                _span(offset, 0 if kind == 8 else size, len(self.raw), "section file")
                _span(address, size, 1 << 64, "section address")
                _require(kind == 8 or alignment <= 1 or offset % alignment == 0, "misaligned file-backed section")
            sections.append({"index": index, "nameOffset": name, "typeId": kind, "type": _SECTIONS[kind],
                             "flags": flags, "virtualAddress": address, "offset": offset, "size": size,
                             "link": link, "info": info, "alignment": alignment, "entryBytes": entries})
        names = sections[self.shstr]
        _require(names["typeId"] == 3 and 0 < names["size"] <= MAX_STRINGS, "invalid section name table")
        data = self.content(names)
        _require(data[:1] == data[-1:] == b"\0", "section name table lacks NUL boundaries")
        seen = set()
        for s in sections:
            s["name"] = self.string(data, s.pop("nameOffset"))
            if s["index"]:
                _require(s["name"] and s["name"] not in seen, "empty/duplicate section name")
                seen.add(s["name"])
            s["sha256"] = None if s["typeId"] in (0, 8) else _sha(self.content(s))
        return sections

    def validate_sections(self):
        files, addresses = list(self.tables), []
        for s in self.sections[1:]:
            if s["typeId"] != 8:
                files.append((s["offset"], s["offset"] + s["size"]))
            if s["flags"] & 2:
                load, offset = self.mapped(s["virtualAddress"], s["size"], memory=s["typeId"] == 8,
                                           executable=bool(s["flags"] & 4), writable=bool(s["flags"] & 1))
                _require(s["alignment"] <= 1 or s["virtualAddress"] % s["alignment"] == 0, "misaligned allocated section")
                if s["typeId"] == 8:
                    _require(s["virtualAddress"] >= load["virtualAddress"] + load["fileBytes"], "NOBITS overlaps file-backed LOAD data")
                else:
                    _require(offset == s["offset"], "allocated section file/virtual mapping mismatch")
                addresses.append((s["virtualAddress"], s["virtualAddress"] + s["size"]))
            else:
                _require(s["virtualAddress"] == 0 and not s["flags"] & (1 | 4 | 0x400), "nonallocated runtime section")
            if s["flags"] & 0x400:
                _require(s["name"] in (".tdata", ".tbss") and s["size"] == 0 and s["flags"] == 0x403,
                         "TLS section is not an empty reviewed marker")
            if s["typeId"] == 3:
                _require(0 < s["size"] <= MAX_STRINGS and s["entryBytes"] == 0, "invalid string-table shape")
                content = self.content(s)
                _require(content[:1] == content[-1:] == b"\0", "string table lacks NUL boundaries")
            if s["typeId"] not in (2, 4, 6, 11, 0x6FFFFFF6, 0x6FFFFFFE, 0x6FFFFFFF):
                _require(s["link"] == s["info"] == 0, "unexpected section link/info")
        _disjoint(files, "section/header file spans")
        _disjoint(addresses, "allocated section spans")
        for kind in (6, 11, 14, 0x6FFFFFF6, 0x6FFFFFFE, 0x6FFFFFFF):
            _require(sum(s["typeId"] == kind for s in self.sections) == 1, "missing/duplicate dynamic singleton section")
        _require({s["name"] for s in self.sections if s["typeId"] == 4} == {".rela.dyn", ".rela.plt"},
                 "unexpected relocation section inventory")
        self.text = self.one_section(".text", 1)
        _require(self.text["flags"] == 6 and self.text["alignment"] >= 4 and self.text["entryBytes"] == 0
                 and self.text["virtualAddress"] <= self.entry < self.text["virtualAddress"] + self.text["size"], "invalid entry .text section")
        for kind, name, section_kind in ((3, ".interp", 1), (2, ".dynamic", 6), (0x6474E550, ".eh_frame_hdr", 1)):
            programs = [p for p in self.programs if p["typeId"] == kind]
            if not programs:
                continue
            s, p = self.one_section(name, section_kind), programs[0]
            _require((s["offset"], s["virtualAddress"], s["size"]) == (p["offset"], p["virtualAddress"], p["fileBytes"]),
                     "program/section span disagreement: " + name)
        tls = [p for p in self.programs if p["typeId"] == 7]
        markers = [s for s in self.sections if s["flags"] & 0x400]
        _require(len(tls) == len(markers), "TLS program/section marker count mismatch")
        if tls:
            _require((tls[0]["offset"], tls[0]["virtualAddress"]) == (markers[0]["offset"], markers[0]["virtualAddress"]), "TLS marker mapping mismatch")

    def read_symbols(self, section):
        _require(section["entryBytes"] == 24 and section["size"] % 24 == 0
                 and 1 <= section["size"] // 24 <= MAX_SYMBOLS, "invalid symbol-table dimensions")
        count = section["size"] // 24
        _require(1 <= section["info"] <= count, "invalid local symbol boundary")
        strings = self.sections[section["link"]]
        _require(strings["typeId"] == 3, "symbol table lacks string table link")
        data, rows, globals_seen = self.content(strings), [], set()
        for index in range(count):
            row = self.unpack("IBBHQQ", section["offset"] + index * 24)
            name, info, other, owner_index, value, size = row
            if index == 0:
                _require(not any(row), "nonzero NULL symbol")
            binding, kind = info >> 4, info & 15
            _require(binding in (0, 1, 2) and kind in (0, 1, 2, 3, 4, 6) and other <= 3,
                     "unsupported symbol type/binding/visibility")
            _require((index < section["info"]) == (binding == 0), "symbol local/global boundary mismatch")
            _require(owner_index in (0, 0xFFF1) or 0 < owner_index < self.shnum, "invalid/common/extended symbol section")
            _span(value, size, 1 << 64, "symbol address")
            _require(size <= MAX_BINARY_BYTES, "symbol exceeds bound")
            if owner_index == 0:
                _require(value == 0, "undefined symbol has nonzero value")
            elif owner_index != 0xFFF1:
                owner = self.sections[owner_index]
                if kind == 6:
                    _require(owner["flags"] & 0x400 and owner["size"] == value == size == 0, "nonempty TLS symbol outside contract")
                else:
                    _require(owner["virtualAddress"] <= value and value + size <= owner["virtualAddress"] + owner["size"],
                             "symbol outside owning section")
            label = self.string(data, name)
            if label and binding != 0:
                _require(label not in globals_seen, "duplicate global symbol")
                globals_seen.add(label)
            rows.append({"name": label, "value": value, "size": size, "binding": binding, "type": kind,
                         "visibility": other, "sectionIndex": owner_index})
        return rows

    def read_dynamic(self):
        d = self.dynamic
        _require(d["flags"] == 3 and d["entryBytes"] == 16 and d["size"] % 16 == 0 and 1 <= d["size"] // 16 <= 128,
                 "invalid dynamic table shape")
        _require(d["link"] == self.dynsym["link"] == self.dynstr["index"] and d["info"] == 0,
                 "dynamic/dynsym string-table linkage mismatch")
        _require(self.dynsym["flags"] == self.dynstr["flags"] == 2, "invalid loader symbol/string permissions")
        tags, terminated = {}, False
        for position in range(d["offset"], d["offset"] + d["size"], 16):
            tag, value = self.unpack("qQ", position)
            if terminated or tag == 0:
                _require(tag == value == 0, "nonzero data after dynamic terminator")
                terminated = True
                continue
            _require(tag in _TAGS, "unsupported/unsafe dynamic tag (paths, SONAME, TEXTREL and packed relocations forbidden)")
            _require(tag == 1 or tag not in tags, "duplicate singleton dynamic tag")
            tags.setdefault(tag, []).append(value)
        _require(terminated, "unterminated dynamic table")
        _require(set(tags) == _TAGS - {0}, "missing dynamic contract tag")
        for tag, value in ((5, self.dynstr["virtualAddress"]), (10, self.dynstr["size"]),
                           (6, self.dynsym["virtualAddress"]), (11, 24), (9, 24), (20, 7),
                           (21, 0), (30, 8), (0x6FFFFFFB, 0x08000001)):
            _require(tags[tag] == [value], "dynamic pointer/size/BIND_NOW/PIE mismatch")
        needed = [self.string(self.content(self.dynstr), offset) for offset in tags[1]]
        _require(needed == EXPECTED_NEEDED, "dynamic dependency list differs from libdl.so/libc.so")
        return tags

    def tag(self, number):
        return self.tags[number][0]

    def in_relro(self, address, size):
        p = self.relro
        _require(p["virtualAddress"] <= address and address + size <= p["virtualAddress"] + p["memoryBytes"], "critical dynamic data outside RELRO")

    def validate_dynamic_tables(self):
        for name, kind, address_tag, size_tag in ((".rela.dyn", 4, 7, 8), (".rela.plt", 4, 23, 2),
                                                  (".init_array", 14, 25, 27)):
            s = self.one_section(name, kind)
            _require((s["virtualAddress"], s["size"]) == (self.tag(address_tag), self.tag(size_tag)), "dynamic section pointer/size mismatch: " + name)
        init = self.one_section(".init_array", 14)
        # The retained LLD engine emits sh_entsize=0 here. DT_INIT_ARRAYSZ and
        # the ELF64 pointer width still fix the actual array element size.
        _require(init["flags"] == 3 and init["entryBytes"] in (0, 8) and init["alignment"] >= 8
                 and 0 < init["size"] <= 4096 and init["size"] % 8 == 0, "invalid init array")
        got = self.one_section(".got.plt", 1)
        _require(got["flags"] == 3 and got["virtualAddress"] == self.tag(3) and got["size"] >= 24, "invalid PLTGOT")
        for s in (self.dynamic, init, got):
            self.in_relro(s["virtualAddress"], s["size"])
        h = self.one_section(".gnu.hash", 0x6FFFFFF6)
        _require(h["flags"] == 2 and h["link"] == self.dynsym["index"] and h["info"] == 0
                 and h["virtualAddress"] == self.tag(0x6FFFFEF5) and h["size"] >= 28, "invalid GNU hash mapping")
        buckets, first, blooms, shift = self.unpack("IIII", h["offset"])
        count = len(self.dynamic_symbols)
        _require(1 <= buckets <= MAX_SYMBOLS and 0 <= first <= count and _power2(blooms) and blooms <= MAX_SYMBOLS and shift < 64,
                 "invalid GNU hash dimensions")
        _require(h["size"] == 16 + blooms * 8 + buckets * 4 + (count - first) * 4, "GNU hash section size mismatch")
        base = h["offset"] + 16 + blooms * 8
        for index in range(buckets):
            value, = self.unpack("I", base + index * 4)
            _require(value == 0 or first <= value < count, "GNU hash bucket outside symbols")
        if count > first:
            last, = self.unpack("I", h["offset"] + h["size"] - 4)
            _require(last & 1, "unterminated GNU hash chain")
        self.validate_versions()

    def validate_versions(self):
        versions = self.one_section(".gnu.version", 0x6FFFFFFF)
        needs = self.one_section(".gnu.version_r", 0x6FFFFFFE)
        _require(versions["flags"] == needs["flags"] == 2 and versions["link"] == self.dynsym["index"]
                 and versions["entryBytes"] == 2 and versions["size"] == len(self.dynamic_symbols) * 2
                 and versions["virtualAddress"] == self.tag(0x6FFFFFF0), "invalid version-symbol table")
        count = self.tag(0x6FFFFFFF)
        _require(1 <= count <= 16 and needs["info"] == count and needs["link"] == self.dynstr["index"]
                 and needs["virtualAddress"] == self.tag(0x6FFFFFFE), "invalid version-requirement table")
        payload, strings, cursor, identifiers, spans = self.content(needs), self.content(self.dynstr), 0, {0, 1}, []
        for index in range(count):
            _span(cursor, 16, len(payload), "version need")
            version, number, file_name, aux, following = struct.unpack_from("<HHIII", payload, cursor)
            _require(version == 1 and 1 <= number <= 64 and self.string(strings, file_name) in EXPECTED_NEEDED,
                     "invalid version need record")
            spans.append((cursor, cursor + 16))
            position = cursor + aux
            for item in range(number):
                _span(position, 16, len(payload), "version auxiliary")
                _, flags, identifier, name, next_aux = struct.unpack_from("<IHHII", payload, position)
                _require(flags in (0, 2) and 2 <= identifier < 0x8000 and identifier not in identifiers
                         and self.string(strings, name), "invalid/duplicate version auxiliary")
                identifiers.add(identifier)
                spans.append((position, position + 16))
                _require(next_aux >= 16 if item + 1 < number else next_aux == 0, "invalid version auxiliary link")
                position += next_aux
            _require(following >= 16 if index + 1 < count else following == 0, "invalid version need link")
            cursor += following
        _disjoint(spans, "version records")
        _require(sum(end - start for start, end in spans) == len(payload), "unaccounted version table bytes")
        for position in range(versions["offset"], versions["offset"] + versions["size"], 2):
            value, = self.unpack("H", position)
            _require(value & 0x7FFF in identifiers, "undefined symbol version identifier")

    def read_relocations(self):
        rows, seen, relative_count = [], set(), self.tag(0x6FFFFFF9)
        for name in (".rela.dyn", ".rela.plt"):
            s = self.one_section(name, 4)
            _require(s["entryBytes"] == 24 and s["size"] % 24 == 0 and 0 < s["size"] // 24 <= MAX_RELOCATIONS
                     and s["link"] == self.dynsym["index"] and s["flags"] == (2 if name == ".rela.dyn" else 0x42),
                     "invalid RELA table shape")
            _require(s["info"] == (0 if name == ".rela.dyn" else self.one_section(".got.plt", 1)["index"]), "invalid RELA section target")
            count = s["size"] // 24
            if name == ".rela.dyn":
                _require(relative_count <= count, "RELACOUNT exceeds table")
            for index in range(count):
                address, info, addend = self.unpack("QQq", s["offset"] + index * 24)
                kind, symbol = info & 0xFFFFFFFF, info >> 32
                _require(address % 8 == 0 and address not in seen, "misaligned/duplicate relocation target")
                seen.add(address)
                self.mapped(address, 8, memory=True, writable=True, nonexecuting=True)
                _require(symbol < len(self.dynamic_symbols), "relocation symbol outside dynamic table")
                if name == ".rela.plt":
                    _require(kind == 1026 and symbol > 0 and addend == 0, "unexpected PLT relocation")
                elif index < relative_count:
                    _require(kind == 1027 and symbol == 0, "RELACOUNT does not describe leading relative relocations")
                else:
                    _require(kind == 1025 and symbol > 0 and addend == 0, "unexpected nonrelative dynamic relocation")
                rows.append({"address": address, "type": kind, "symbolIndex": symbol, "addend": addend})
        return rows

    def read_notes(self):
        android, builds, note_spans = [], [], []
        for s in self.sections:
            if s["typeId"] != 7:
                continue
            _require(s["flags"] == 2 and 0 < s["size"] <= 16384, "invalid note section")
            matches = [p for p in self.programs if p["typeId"] == 4 and p["offset"] <= s["offset"]
                       and s["offset"] + s["size"] <= p["offset"] + p["fileBytes"]]
            _require(len(matches) == 1, "note section is not uniquely in a PT_NOTE")
            data, cursor, count = self.content(s), 0, 0
            while cursor < len(data):
                _span(cursor, 12, len(data), "note header")
                names, descriptors, kind = struct.unpack_from("<III", data, cursor)
                start = cursor + 12
                description = start + ((names + 3) & ~3)
                end = description + ((descriptors + 3) & ~3)
                _span(start, end - start, len(data), "note body")
                owner, value = data[start:start + names], data[description:description + descriptors]
                _require(not any(data[start + names:description]) and not any(data[description + descriptors:end]), "nonzero note padding")
                if owner == b"Android\0" and kind == 1:
                    _require(descriptors == 132, "wrong Android identity note shape")
                    api, = struct.unpack_from("<I", value)
                    strings = []
                    for field in (value[4:68], value[68:132]):
                        _require(b"\0" in field, "unterminated Android identity field")
                        item, padding = field.split(b"\0", 1)
                        _require(not any(padding), "nonzero Android identity padding")
                        strings.append(item)
                    _require((api, *strings) == (29, b"r28c", b"13676358"), "unexpected Android API/NDK identity")
                    android.append({"api": api, "ndk": strings[0].decode("ascii"), "build": strings[1].decode("ascii")})
                elif owner == b"GNU\0" and kind == 3:
                    _require(descriptors == 20, "GNU build ID is not SHA-1-sized")
                    builds.append(value.hex())
                else:
                    raise EngineElfError("unsupported engine note")
                cursor, count = end, count + 1
                _require(count <= 16, "excessive note records")
            note_spans.append((s["offset"], s["offset"] + s["size"]))
        _require(len(android) == len(builds) == 1, "missing/duplicate Android or GNU build-ID note")
        for p in [p for p in self.programs if p["typeId"] == 4]:
            covered = sum(end - begin for begin, end in note_spans if p["offset"] <= begin and end <= p["offset"] + p["fileBytes"])
            _require(covered == p["fileBytes"], "PT_NOTE has unaccounted bytes")
        return android[0], builds[0]

    def comments(self):
        matches = [s for s in self.sections if s["name"] == ".comment"]
        if not matches:
            return []
        s = matches[0]
        _require(s["typeId"] == 1 and not s["flags"] & 2 and s["size"] <= 16384, "invalid compiler comments")
        raw = self.content(s)
        _require(raw.endswith(b"\0"), "unterminated compiler comments")
        rows = [part for part in raw.split(b"\0") if part]
        _require(len(rows) <= 32 and all(len(part) <= 2048 and all(32 <= byte < 127 for byte in part) for part in rows), "invalid compiler comment strings")
        return [part.decode("ascii") for part in rows]

    def coupling(self, expected_loaders, expected_offset):
        table = self.one_section(".symtab", 2)
        _require(table["flags"] == 0 and self.sections[table["link"]]["name"] == ".strtab", "invalid proof symbol-table identity")
        _require(sum(s["typeId"] == 2 for s in self.sections) == 1, "duplicate proof symbol table")
        symbols = self.read_symbols(table)
        names = {CONSTANT} | {prefix + suffix for prefix in WRAPPERS.values() for suffix in ("start", "end", "size")}
        selected = {}
        for row in symbols:
            if row["name"] in names:
                _require(row["name"] not in selected and row["binding"] == 1 and row["visibility"] == 0, "duplicate/non-global/non-default coupling symbol")
                selected[row["name"]] = row
        _require(set(selected) == names, "missing exact engine coupling symbols")
        constant = selected[CONSTANT]
        _require(constant["type"] == 1 and constant["size"] == 8 and 0 < constant["sectionIndex"] < self.shnum, "offset constant must be defined ssize_t object")
        owner = self.sections[constant["sectionIndex"]]
        _require(owner["flags"] & 2 and not owner["flags"] & (1 | 4) and owner["typeId"] != 8, "offset constant is not allocated read-only data")
        load, position = self.mapped(constant["value"], 8, nonexecuting=True)
        _require(load["flags"] == 4, "offset constant must be in a read-only PT_LOAD")
        value, = self.unpack("q", position)
        _require(value == expected_offset, "compiled pokedata offset differs from source-built loader evidence")
        protected, loaders = [(constant["value"], constant["value"] + 8)], {}
        for abi, prefix in WRAPPERS.items():
            start, end, size = [selected[prefix + suffix] for suffix in ("start", "end", "size")]
            expected = expected_loaders[abi]
            _require(all(row["type"] == 0 and row["size"] == 0 for row in (start, end, size)), "wrapper symbols must be zero-sized linker labels")
            _require(size["sectionIndex"] == 0xFFF1 and size["value"] == len(expected), "wrapper absolute size differs from expected loader")
            _require(0 < start["sectionIndex"] == end["sectionIndex"] < self.shnum
                     and end["value"] > start["value"] and end["value"] - start["value"] == len(expected), "invalid wrapper start/end span")
            owner = self.sections[start["sectionIndex"]]
            _require(owner["flags"] & 2 and not owner["flags"] & 4 and owner["typeId"] != 8, "wrapper is not allocated nonexecuting file data")
            _, position = self.mapped(start["value"], len(expected), nonexecuting=True)
            actual = self.raw[position:position + len(expected)]
            _require(actual == expected, "embedded loader bytes differ: " + abi)
            protected.append((start["value"], end["value"]))
            loaders[abi] = {"bytes": len(actual), "sha256": _sha(actual), "start": start["value"], "end": end["value"],
                            "fileOffset": position, "symbols": {suffix: prefix + suffix for suffix in ("start", "end", "size")}}
        _disjoint(protected, "coupling data spans")
        for relocation in self.relocations:
            _require(all(relocation["address"] + 8 <= start or end <= relocation["address"] for start, end in protected), "dynamic relocation overlaps proved coupling data")
        return {"verified": True, "constantVerified": True, "embeddedLoadersVerified": True,
                "pokedataOffset": value, "constantFileOffset": self.mapped(constant["value"], 8)[1],
                "loaders": loaders, "relocationsDoNotOverlap": True,
                "scope": "Exact provided bytes/constant through unstripped symbols; not source authenticity or instruction-use equivalence"}, len(symbols)


def inspect_engine(raw: bytes, *, expected_loaders: dict[str, bytes] | None = None,
                   expected_pokedata_offset: int | None = None, require_symbols: bool = False) -> dict:
    """Inspect bytes only; expectations are mandatory only for the pre-strip gate."""
    _require(type(raw) is bytes and type(require_symbols) is bool, "expected bytes and Boolean symbol requirement")
    if require_symbols:
        _require(type(expected_loaders) is dict and set(expected_loaders) == set(WRAPPERS)
                 and all(type(value) is bytes and 0 < len(value) <= 1024 * 1024 for value in expected_loaders.values()), "expected exact ARM64/ARM32 loader byte inputs")
        _require(type(expected_pokedata_offset) is int and 0 < expected_pokedata_offset <= 1024 * 1024
                 and expected_pokedata_offset % 4 == 0, "expected bounded aligned pokedata offset")
    else:
        _require(expected_loaders is None and expected_pokedata_offset is None, "unchecked coupling expectations forbidden without symbol gate")
    elf = _Engine(raw)
    coupling = {"verified": False, "constantVerified": False, "embeddedLoadersVerified": False,
                "pokedataOffset": None, "loaders": {}, "reason": "pre-strip symbol gate not requested"}
    symbol_count = 0
    tables = [s for s in elf.sections if s["typeId"] == 2]
    _require(len(tables) <= 1, "duplicate proof symbol tables")
    if tables:
        symbol_count = len(elf.read_symbols(tables[0]))
    if require_symbols:
        coupling, symbol_count = elf.coupling(expected_loaders, expected_pokedata_offset)
    digest, loads = hashlib.sha256(b"FDROID-PROOT-ENGINE-LOAD-V1\0"), []
    digest.update(struct.pack("<I", len(elf.loads)))
    for p in elf.loads:
        fields = (p["flags"], p["offset"], p["virtualAddress"], p["physicalAddress"], p["fileBytes"], p["memoryBytes"], p["alignment"])
        digest.update(struct.pack("<7Q", *fields))
        data = raw[p["offset"]:p["offset"] + p["fileBytes"]]
        digest.update(data)
        loads.append(dict(p, sha256=_sha(data)))
    counts = {str(kind): sum(row["type"] == kind for row in elf.relocations) for kind in (1025, 1026, 1027)}
    return {"class": "ELF64", "type": "ET_DYN", "machine": "AArch64", "endianness": "little", "entryPoint": elf.entry,
            "interpreter": INTERPRETER[:-1].decode("ascii"), "dynamicDependencies": list(EXPECTED_NEEDED),
            "bindNow": True, "pie": True, "nonExecutableStack": True, "noWritableExecutableLoad": True,
            "minimumLoadAlignment": LOAD_ALIGNMENT, "androidIdentity": elf.android_identity, "gnuBuildId": elf.build_id,
            "programHeaders": elf.programs, "loadSegments": loads, "sections": elf.sections,
            "loadableSegmentsSha256": digest.hexdigest(), "loadableSegmentsHashFormat": "sha256:fdroid-proot-engine-load-v1:framed-mapping-and-file-bytes",
            "textSha256": _sha(elf.content(elf.text)), "compilerComments": elf.comments(), "symbolCount": symbol_count,
            "dynamicSymbolCount": len(elf.dynamic_symbols), "relocationCounts": counts, "coupling": coupling,
            "executed": False, "runtimeCompatibilityVerified": False,
            "inspectionScope": "Bounded Android PIE structure/dependencies and optional symbol-backed source-build data coupling, not instruction semantics or Android execution"}
