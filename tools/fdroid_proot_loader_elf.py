"""Bounded, data-only inspection of the two static PRoot loader ELF formats.

This is deliberately not a general ELF loader, disassembler, or execution
validator. It accepts the reviewed little-endian ARM64/ARM32 ET_EXEC layouts,
checks file/memory mappings, and optionally verifies the two ARM64 symbols
used by loader-info generation. Nothing in this module opens files or executes
an inspected payload. ARM attributes are retained as section hashes, not
interpreted as proof of instruction-set or runtime compatibility.
"""
from __future__ import annotations

import hashlib
import struct


MAX_BINARY_BYTES = 1024 * 1024
MAX_PROGRAM_HEADERS = 32
MAX_SECTION_HEADERS = 128
MAX_SYMBOLS = 4096
MAX_STRING_BYTES = 256 * 1024
MAX_NAME_BYTES = 512
POKEDATA_STUB = bytes.fromhex("410000f900a0f0f7")
LOAD_HASH_FORMAT = "sha256:fdroid-proot-load-v1:framed-mapping-and-file-bytes"
_CONFIG = {"arm64": (2, 183, 64, 56, 64, 0x2000000000, 0x4000, 0),
           "arm32": (1, 40, 52, 32, 40, 0x20000000, 0x1000, 0x05000200)}
_PROGRAM_NAMES = {1: "PT_LOAD", 4: "PT_NOTE", 6: "PT_PHDR",
                  0x6474E551: "PT_GNU_STACK", 0x6474E552: "PT_GNU_RELRO",
                  0x70000001: "PT_ARM_EXIDX"}
_SECTION_NAMES = {0: "SHT_NULL", 1: "SHT_PROGBITS", 2: "SHT_SYMTAB", 3: "SHT_STRTAB",
                  7: "SHT_NOTE", 8: "SHT_NOBITS", 0x70000001: "SHT_ARM_EXIDX",
                  0x70000003: "SHT_ARM_ATTRIBUTES"}


class LoaderElfError(ValueError):
    """An input is outside the reviewed loader ELF contract."""


def _require(condition, message):
    if not condition:
        raise LoaderElfError(message)


def _sha(raw):
    return hashlib.sha256(raw).hexdigest()


def _span(start, size, limit, label):
    # Subtraction also documents the fixed-width overflow boundary explicitly.
    _require(0 <= start <= limit and 0 <= size <= limit - start, "invalid " + label + " span")
    return start, start + size


def _power_of_two(value):
    return value > 0 and value & (value - 1) == 0


def _disjoint(spans, label):
    previous = None
    for start, end in sorted((a, b) for a, b in spans if a != b):
        _require(previous is None or start >= previous, "overlapping or duplicate " + label)
        previous = end


class _Elf:
    def __init__(self, raw, abi):
        self.raw, self.abi = raw, abi
        elf_class, machine, ehsize, phsize, shsize, entry, alignment, flags = _CONFIG[abi]
        self.wide = elf_class == 2
        self.address_limit = 1 << (64 if self.wide else 32)
        self.alignment = alignment
        _require(ehsize <= len(raw) <= MAX_BINARY_BYTES, "ELF size outside reviewed bound")
        _require(raw[:16] == b"\x7fELF" + bytes((elf_class, 1, 1)) + bytes(9),
                 "wrong ELF identity, byte order, ABI, or reserved bytes")
        header = self.unpack("HHIQQQIHHHHHH" if self.wide else "HHIIIIIHHHHHH", 16)
        kind, emitted_machine, version, self.entry, self.phoff, self.shoff, self.flags, emitted_ehsize, emitted_phsize, self.phnum, emitted_shsize, self.shnum, self.shstr = header
        _require((kind, emitted_machine, version) == (2, machine, 1), "wrong ET_EXEC machine or ELF version")
        _require(self.entry == entry and self.entry % 4 == 0, "wrong loader entry point")
        _require(self.flags == flags, "wrong architecture flags (ARM requires EABI5 soft-float)")
        _require((emitted_ehsize, emitted_phsize, emitted_shsize) == (ehsize, phsize, shsize),
                 "wrong ELF header or table entry size")
        _require(1 <= self.phnum <= MAX_PROGRAM_HEADERS and 2 <= self.shnum <= MAX_SECTION_HEADERS,
                 "missing, extended, or excessive ELF table count")
        _require(0 < self.shstr < self.shnum, "invalid section-name table index")
        unit = 8 if self.wide else 4
        _require(self.phoff >= ehsize and self.shoff >= ehsize and self.phoff % unit == 0 and self.shoff % unit == 0,
                 "misaligned or header-overlapping ELF tables")
        self.tables = [(0, ehsize), _span(self.phoff, phsize * self.phnum, len(raw), "program table"),
                       _span(self.shoff, shsize * self.shnum, len(raw), "section table")]
        _disjoint(self.tables, "ELF tables")
        self.header = {"bytes": ehsize, "programHeaderOffset": self.phoff, "programHeaderEntryBytes": phsize,
                       "programHeaderCount": self.phnum, "sectionHeaderOffset": self.shoff,
                       "sectionHeaderEntryBytes": shsize, "sectionHeaderCount": self.shnum,
                       "sectionNameTableIndex": self.shstr}
        self.programs = self.read_programs(phsize)
        self.loads = [p for p in self.programs if p["typeId"] == 1]
        self.validate_programs()
        self.sections = self.read_sections(shsize)
        self.validate_sections()
        self.symbols, self.symbol_count = self.read_symbols()

    def unpack(self, fmt, offset):
        _span(offset, struct.calcsize("<" + fmt), len(self.raw), "ELF record")
        return struct.unpack_from("<" + fmt, self.raw, offset)

    def content(self, section):
        _require(section["typeId"] != 8, "NOBITS section has no file content")
        begin, end = _span(section["offset"], section["size"], len(self.raw), "section data")
        return self.raw[begin:end]

    @staticmethod
    def string(strings, offset):
        _require(0 <= offset < len(strings), "string offset outside table")
        end = strings.find(b"\0", offset, min(len(strings), offset + MAX_NAME_BYTES + 1))
        _require(end >= offset, "unterminated or oversized ELF string")
        value = strings[offset:end]
        _require(all(32 <= byte < 127 for byte in value), "non-ASCII or control byte in ELF string")
        return value.decode("ascii")

    def read_programs(self, entry_bytes):
        programs, seen = [], set()
        for index in range(self.phnum):
            values = self.unpack("IIQQQQQQ" if self.wide else "IIIIIIII", self.phoff + index * entry_bytes)
            if self.wide:
                kind, flags, offset, address, physical, size, memory, alignment = values
            else:
                kind, offset, address, physical, size, memory, flags, alignment = values
            _require(kind not in (2, 3), "PT_DYNAMIC/PT_INTERP forbidden for static loaders")
            _require(kind in _PROGRAM_NAMES and (self.abi == "arm32" or kind != 0x70000001),
                     "unsupported loader program header")
            _require(values not in seen, "duplicate program header")
            seen.add(values)
            _require(flags & ~7 == 0 and size <= memory <= MAX_BINARY_BYTES, "invalid program flags or sizes")
            _span(offset, size, len(self.raw), "program file")
            _span(address, memory, self.address_limit, "program virtual memory")
            _span(physical, memory, self.address_limit, "program physical memory")
            _require(physical == address, "program physical/virtual addresses differ")
            _require(alignment in (0, 1) or _power_of_two(alignment), "invalid program alignment")
            programs.append({"index": index, "type": _PROGRAM_NAMES[kind], "typeId": kind, "flags": flags,
                             "offset": offset, "virtualAddress": address, "physicalAddress": physical,
                             "fileBytes": size, "memoryBytes": memory, "alignment": alignment})
        return programs

    def mapped(self, address, size, *, executable=False, memory=False):
        _span(address, size, self.address_limit, "mapped address")
        matches = [p for p in self.loads if p["virtualAddress"] <= address
                   and address + size <= p["virtualAddress"] + p["memoryBytes" if memory else "fileBytes"]
                   and (not executable or p["flags"] & 1)]
        _require(len(matches) == 1, "address is not uniquely file/memory-backed by an appropriate PT_LOAD")
        segment = matches[0]
        return segment, segment["offset"] + address - segment["virtualAddress"]

    def validate_programs(self):
        _require(self.loads, "no PT_LOAD segments")
        for p in self.loads:
            _require(p["memoryBytes"] > 0 and p["flags"] in (4, 5, 6), "empty, unreadable, or writable-executable PT_LOAD")
            _require(p["alignment"] == self.alignment, "wrong architecture PT_LOAD alignment")
            _require(p["virtualAddress"] % self.alignment == p["offset"] % self.alignment,
                     "PT_LOAD file/address alignment mismatch")
        _disjoint([(p["offset"], p["offset"] + p["fileBytes"]) for p in self.loads], "PT_LOAD file spans")
        _disjoint([(p["virtualAddress"], p["virtualAddress"] + p["memoryBytes"]) for p in self.loads],
                  "PT_LOAD virtual spans")
        self.mapped(self.entry, 4, executable=True)
        stacks = [p for p in self.programs if p["typeId"] == 0x6474E551]
        _require(len(stacks) == 1, "missing or duplicate GNU stack header")
        stack = stacks[0]
        _require(stack["flags"] == 6 and all(stack[k] == 0 for k in ("offset", "virtualAddress", "fileBytes", "memoryBytes")),
                 "GNU stack must be read/write, nonexecuting, and have no file/memory image")
        for kind in (6, 0x6474E552, 0x70000001):
            _require(sum(p["typeId"] == kind for p in self.programs) <= 1, "duplicate singleton program header")
        for p in self.programs:
            if p["typeId"] not in (4, 6, 0x6474E552, 0x70000001):
                continue
            _require(p["fileBytes"] > 0 and p["flags"] == 4, "metadata program header must be nonempty and read-only")
            load, offset = self.mapped(p["virtualAddress"], p["fileBytes"])
            _require(offset == p["offset"] and not load["flags"] & 1, "metadata program mapping mismatch")
            if p["typeId"] == 6:
                _require(p["offset"] == self.phoff and p["fileBytes"] == p["memoryBytes"] == self.header["programHeaderEntryBytes"] * self.phnum,
                         "PT_PHDR does not describe the exact program table")
            if p["typeId"] in (4, 0x70000001):
                _require(p["fileBytes"] == p["memoryBytes"], "metadata program has unbacked memory")

    def read_sections(self, entry_bytes):
        result = []
        for index in range(self.shnum):
            values = self.unpack("IIQQQQIIQQ" if self.wide else "IIIIIIIIII", self.shoff + index * entry_bytes)
            name, kind, flags, address, offset, size, link, info, alignment, entries = values
            if index == 0:
                _require(not any(values), "nonzero null section or unsupported extended table metadata")
            else:
                _require(kind in _SECTION_NAMES and kind != 0 and (self.abi == "arm32" or kind < 0x70000000),
                         "unsupported or dynamic loader section")
                _require(flags & ~(1 | 2 | 4 | 0x10 | 0x20 | 0x80) == 0, "unsupported section flags (TLS/compression forbidden)")
                _require(0 <= link < self.shnum, "section link outside table")
                _require(alignment == 0 or _power_of_two(alignment), "invalid section alignment")
                _require(alignment <= MAX_BINARY_BYTES and size <= MAX_BINARY_BYTES, "section exceeds reviewed bounds")
                _span(address, size, self.address_limit, "section virtual")
                _span(offset, 0 if kind == 8 else size, len(self.raw), "section file")
                _require(alignment <= 1 or offset % alignment == 0, "section file offset misaligned")
            result.append({"index": index, "nameOffset": name, "type": _SECTION_NAMES[kind], "typeId": kind,
                           "flags": flags, "virtualAddress": address, "offset": offset, "size": size,
                           "link": link, "info": info, "alignment": alignment, "entryBytes": entries})
        names = result[self.shstr]
        _require(names["typeId"] == 3 and 0 < names["size"] <= MAX_STRING_BYTES, "invalid section-name string table")
        strings = self.content(names)
        _require(strings[:1] == strings[-1:] == b"\0", "section-name table lacks null boundaries")
        seen = set()
        for s in result:
            s["name"] = self.string(strings, s.pop("nameOffset"))
            if s["index"]:
                _require(s["name"] and s["name"] not in seen, "empty or duplicate section name")
                seen.add(s["name"])
            s["sha256"] = None if s["typeId"] in (0, 8) else _sha(self.content(s))
        return result

    def validate_sections(self):
        file_spans = list(self.tables)
        allocated_spans = []
        for s in self.sections[1:]:
            kind, flags = s["typeId"], s["flags"]
            if kind != 8:
                file_spans.append((s["offset"], s["offset"] + s["size"]))
            if flags & 2:
                load, offset = self.mapped(s["virtualAddress"], s["size"], executable=bool(flags & 4), memory=kind == 8)
                _require(not flags & 1 or load["flags"] & 2, "writable section lacks writable PT_LOAD")
                _require(not flags & 4 or not load["flags"] & 2, "executable section maps writable memory")
                _require(s["alignment"] <= 1 or s["virtualAddress"] % s["alignment"] == 0, "allocated section address misaligned")
                if kind == 8:
                    _require(s["virtualAddress"] >= load["virtualAddress"] + load["fileBytes"], "NOBITS overlaps file-backed load bytes")
                else:
                    _require(offset == s["offset"], "allocated section file/address mapping mismatch")
                allocated_spans.append((s["virtualAddress"], s["virtualAddress"] + s["size"]))
            else:
                _require(s["virtualAddress"] == 0 and not flags & (1 | 4), "nonallocated section has runtime address/permissions")
            if kind == 3:
                _require(0 < s["size"] <= MAX_STRING_BYTES and not flags & 2 and s["entryBytes"] == 0,
                         "invalid string table shape")
                data = self.content(s)
                _require(data[:1] == data[-1:] == b"\0", "string table lacks null boundaries")
            if kind not in (2, 0x70000001):
                _require(s["link"] == s["info"] == 0, "unexpected section links/info")
            if kind == 0x70000001:
                _require(s["flags"] == 0x82 and s["size"] > 0 and s["size"] % 8 == 0 and s["info"] == 0,
                         "invalid ARM exception-index section")
                _require(self.sections[s["link"]]["name"] == ".text", "ARM exception index does not link .text")
        _disjoint(file_spans, "section/header file spans")
        _disjoint(allocated_spans, "allocated sections")
        texts = [s for s in self.sections if s["name"] == ".text"]
        _require(len(texts) == 1, "missing .text section")
        self.text = texts[0]
        _require(self.text["typeId"] == 1 and self.text["flags"] == 6 and self.text["size"] >= 4
                 and self.text["alignment"] >= 4 and self.text["entryBytes"] == 0,
                 "invalid executable .text section")
        _require(self.text["virtualAddress"] <= self.entry and self.entry + 4 <= self.text["virtualAddress"] + self.text["size"],
                 "entry point outside .text")
        exidx = [s for s in self.sections if s["typeId"] == 0x70000001]
        programs = [p for p in self.programs if p["typeId"] == 0x70000001]
        _require(len(exidx) == len(programs) and len(exidx) <= 1, "ARM exception-index program/section count mismatch")
        if exidx:
            s, p = exidx[0], programs[0]
            _require((s["offset"], s["virtualAddress"], s["size"]) == (p["offset"], p["virtualAddress"], p["fileBytes"]),
                     "ARM exception-index program/section span mismatch")

    def read_symbols(self):
        tables = [s for s in self.sections if s["typeId"] == 2]
        _require(len(tables) <= 1, "duplicate symbol tables")
        if not tables:
            return {}, 0
        table = tables[0]
        expected = 24 if self.wide else 16
        _require(table["name"] == ".symtab" and table["flags"] == 0 and table["entryBytes"] == expected
                 and table["size"] % expected == 0 and 1 <= table["size"] // expected <= MAX_SYMBOLS,
                 "invalid symbol-table shape")
        count = table["size"] // expected
        _require(1 <= table["info"] <= count, "invalid local-symbol boundary")
        string_section = self.sections[table["link"]]
        _require(string_section["typeId"] == 3 and string_section["name"] == ".strtab", "symbol table lacks .strtab link")
        strings, selected, globals_seen = self.content(string_section), {}, set()
        for index in range(count):
            values = self.unpack("IBBHQQ" if self.wide else "IIIBBH", table["offset"] + index * expected)
            if self.wide:
                name, info, visibility, section, address, size = values
            else:
                name, address, size, info, visibility, section = values
            if index == 0:
                _require(not any(values), "nonzero null symbol")
            binding, kind = info >> 4, info & 15
            _require(binding in (0, 1, 2) and kind in (0, 1, 2, 3, 4) and visibility <= 3, "unsupported symbol type/binding/visibility")
            _require((index < table["info"]) == (binding == 0), "symbol local/global ordering mismatch")
            label = self.string(strings, name)
            _span(address, size, self.address_limit, "symbol address")
            _require(section in (0, 0xFFF1) or 0 < section < len(self.sections), "invalid, common, or extended symbol section")
            if section == 0:
                _require(address == size == 0, "undefined symbol has address or size")
            elif section != 0xFFF1:
                owner = self.sections[section]
                symbol_address = address & ~1 if self.abi == "arm32" and kind == 2 else address
                _require(owner["virtualAddress"] <= symbol_address and symbol_address + size <= owner["virtualAddress"] + owner["size"],
                         "defined symbol outside owning section")
            if label and binding != 0:
                _require(label not in globals_seen, "duplicate global symbol name")
                globals_seen.add(label)
            if label in ("_start", "pokedata_workaround"):
                _require(label not in selected, "duplicate loader symbol name")
                _require(binding == 1 and kind in (0, 2) and visibility == 0 and section == self.text["index"],
                         "loader symbol must be a defined global/default function or label in .text")
                _require(address % 4 == 0, "loader symbol is not four-byte aligned")
                self.mapped(address, 8 if label == "pokedata_workaround" else 4, executable=True)
                selected[label] = {"value": address, "size": size, "binding": binding, "type": kind,
                                   "visibility": visibility, "sectionIndex": section}
        return selected, count

    def compiler_comments(self):
        comments = [s for s in self.sections if s["name"] == ".comment"]
        if not comments:
            return []
        section = comments[0]
        _require(section["typeId"] == 1 and section["flags"] == 0x30 and section["entryBytes"] == 1
                 and 0 < section["size"] <= 16 * 1024, "invalid compiler-comment section")
        raw = self.content(section)
        _require(raw.endswith(b"\0"), "unterminated compiler comment")
        parts = [part for part in raw.split(b"\0") if part]
        _require(len(parts) <= 32 and all(len(part) <= 2048 and all(32 <= byte < 127 for byte in part) for part in parts),
                 "oversized or nonprintable compiler comment")
        return [part.decode("ascii") for part in parts]


def inspect_loader(raw: bytes, abi: str, *, require_symbols: bool = False) -> dict:
    """Return JSON-safe structural evidence, never a runtime-equivalence claim.

    ``require_symbols`` is the ARM64 pre-strip loader-info gate. Stripped
    baselines use the same validation with it false. The aggregate PT_LOAD hash
    includes mapping fields and each segment's exact file bytes, framed in
    program-header order; it is not an unframed concatenation or a semantic hash.
    """
    _require(type(raw) is bytes and type(abi) is str and abi in _CONFIG, "expected bytes and ABI arm64/arm32")
    _require(type(require_symbols) is bool and (not require_symbols or abi == "arm64"), "symbol requirement is ARM64-only")
    elf = _Elf(raw, abi)
    if require_symbols:
        _require(set(elf.symbols) == {"_start", "pokedata_workaround"}, "missing exact ARM64 loader symbols")
    offset = None
    if "_start" in elf.symbols:
        _require(elf.symbols["_start"]["value"] == elf.entry, "_start does not equal ELF entry")
    if "pokedata_workaround" in elf.symbols:
        _require(abi == "arm64" and "_start" in elf.symbols, "pokedata symbol lacks ARM64 _start")
        address = elf.symbols["pokedata_workaround"]["value"]
        offset = address - elf.symbols["_start"]["value"]
        _require(offset >= 0 and offset % 4 == 0, "invalid pokedata offset from _start")
        segment, position = elf.mapped(address, len(POKEDATA_STUB), executable=True)
        start_segment, _ = elf.mapped(elf.entry, 4, executable=True)
        _require(segment is start_segment and address + len(POKEDATA_STUB) <= elf.text["virtualAddress"] + elf.text["size"],
                 "pokedata stub not in the entry's executable text mapping")
        _require(raw[position:position + len(POKEDATA_STUB)] == POKEDATA_STUB, "pokedata workaround instruction bytes changed")
    digest = hashlib.sha256(b"FDROID-PROOT-LOAD-V1\0")
    digest.update(struct.pack("<I", len(elf.loads)))
    load_segments = []
    for segment in elf.loads:
        fields = (segment["flags"], segment["offset"], segment["virtualAddress"], segment["physicalAddress"],
                  segment["fileBytes"], segment["memoryBytes"], segment["alignment"])
        digest.update(struct.pack("<7Q", *fields))
        data = raw[segment["offset"]:segment["offset"] + segment["fileBytes"]]
        digest.update(data)
        load_segments.append(dict(segment, sha256=_sha(data)))
    return {"abi": abi, "class": "ELF64" if elf.wide else "ELF32", "endianness": "little", "type": "ET_EXEC",
            "machine": "AArch64" if elf.wide else "ARM", "machineId": _CONFIG[abi][1], "flags": elf.flags,
            "entryPoint": elf.entry, "static": True, "nonExecutableStack": True, "minimumLoadAlignment": elf.alignment,
            "header": elf.header, "programHeaders": elf.programs, "loadSegments": load_segments, "sections": elf.sections,
            "loadableSegmentsSha256": digest.hexdigest(), "loadableSegmentsHashFormat": LOAD_HASH_FORMAT,
            "textSha256": _sha(elf.content(elf.text)), "compilerComments": elf.compiler_comments(),
            "symbolCount": elf.symbol_count, "symbols": elf.symbols, "pokedataOffset": offset,
            "pokedataStubVerified": offset is not None, "executed": False,
            "inspectionScope": "Bounded structural/static mapping and optional exact ARM64 symbols/stub; not method semantics, ARM attribute interpretation, or Android execution"}
