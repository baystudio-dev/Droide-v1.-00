"""Bounded ELF data fixtures; no engine, loader, compiler, or library execution."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import struct
import sys
import unittest


TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import fdroid_proot_engine_elf as inspector


def changed(raw, fmt, offset, *values):
    data = bytearray(raw)
    struct.pack_into("<" + fmt, data, offset, *values)
    return bytes(data)


def fixture(*, symbols=True, init_entry_bytes=0):
    """Small synthetic Android PIE with ordinary RELA and explicit coupling."""
    image = bytearray(0x4300)
    sections = [{"name": "", "type": 0, "flags": 0, "address": 0, "offset": 0,
                 "size": 0, "link": 0, "info": 0, "alignment": 0, "entries": 0}]
    def add(name, offset, data, *, kind=1, flags=2, address=None, alignment=1, entries=0, link=0, info=0, size=None):
        if len(image) < offset + len(data):
            image.extend(bytes(offset + len(data) - len(image)))
        image[offset:offset + len(data)] = data
        sections.append({"name": name, "type": kind, "flags": flags, "address": offset if address is None else address,
                         "offset": offset, "size": len(data) if size is None else size, "link": link, "info": info,
                         "alignment": alignment, "entries": entries})
        return len(sections) - 1
    def note(owner, kind, description):
        return (struct.pack("<III", len(owner), len(description), kind) + owner + bytes((-len(owner)) % 4)
                + description + bytes((-len(description)) % 4))
    android_note = note(b"Android\0", 1, struct.pack("<I", 29) + b"r28c\0".ljust(64, b"\0") + b"13676358\0".ljust(64, b"\0"))
    build_note = note(b"GNU\0", 3, bytes(range(20)))
    add(".interp", 0x300, inspector.INTERPRETER)
    add(".note.android.ident", 0x320, android_note, kind=7, alignment=4)
    add(".note.gnu.build-id", 0x320 + len(android_note), build_note, kind=7, alignment=4)
    strings = bytearray(b"\0")
    offsets = {}
    for name in ("libdl.so", "libc.so", "puts", "stderr", "LIBC"):
        offsets[name] = len(strings)
        strings.extend(name.encode("ascii") + b"\0")
    dynsym = bytes(24) + struct.pack("<IBBHQQ", offsets["puts"], 0x12, 0, 0, 0, 0) + struct.pack("<IBBHQQ", offsets["stderr"], 0x11, 0, 0, 0, 0)
    dynamic_symbols = add(".dynsym", 0x400, dynsym, kind=11, alignment=8, entries=24, info=1)
    add(".gnu.version", 0x480, struct.pack("<HHH", 0, 2, 2), kind=0x6FFFFFFF, alignment=2, entries=2, link=dynamic_symbols)
    versions = struct.pack("<HHIII", 1, 1, offsets["libc.so"], 16, 0) + struct.pack("<IHHII", 0, 0, 2, offsets["LIBC"], 0)
    version_needs = add(".gnu.version_r", 0x490, versions, kind=0x6FFFFFFE, alignment=4, info=1)
    add(".gnu.hash", 0x4C0, struct.pack("<IIIIQI", 1, 3, 1, 0, 0, 0), kind=0x6FFFFFF6, alignment=8, link=dynamic_symbols)
    dynamic_strings = add(".dynstr", 0x500, bytes(strings), kind=3)
    sections[dynamic_symbols]["link"] = dynamic_strings
    sections[version_needs]["link"] = dynamic_strings
    dyn_relocations = struct.pack("<QQq", 0x8008, 1027, 0x6000) + struct.pack("<QQq", 0x8190, (2 << 32) | 1025, 0)
    add(".rela.dyn", 0x600, dyn_relocations, kind=4, alignment=8, entries=24, link=dynamic_symbols)
    plt_relocations = add(".rela.plt", 0x640, struct.pack("<QQq", 0x81B0, (1 << 32) | 1026, 0),
                          kind=4, flags=0x42, alignment=8, entries=24, link=dynamic_symbols)
    rodata = add(".rodata", 0x800, struct.pack("<q", 1120) + bytes(120), alignment=8)
    add(".eh_frame_hdr", 0x900, bytes((1, 0, 0, 0)), alignment=4)
    add(".eh_frame", 0x908, bytes(8), alignment=8)
    add(".text", 0x2000, bytes.fromhex("1f2003d5") * 16, flags=6, address=0x6000, alignment=4)
    add(".plt", 0x2040, bytes.fromhex("1f2003d5") * 4, flags=6, address=0x6040, alignment=16)
    add(".tdata", 0x4000, b"", flags=0x403, address=0x8000, alignment=64)
    add(".data.rel.ro", 0x4000, bytes(8), flags=3, address=0x8000, alignment=8)
    add(".init_array", 0x4008, bytes(8), kind=14, flags=3, address=0x8008, alignment=8, entries=init_entry_bytes)
    tags = [(1, offsets["libdl.so"]), (1, offsets["libc.so"]), (30, 8), (0x6FFFFFFB, 0x08000001), (21, 0),
            (7, 0x600), (8, len(dyn_relocations)), (9, 24), (0x6FFFFFF9, 1), (23, 0x640), (2, 24),
            (3, 0x81A0), (20, 7), (6, 0x400), (11, 24), (5, 0x500), (10, len(strings)), (0x6FFFFEF5, 0x4C0),
            (25, 0x8008), (27, 8), (0x6FFFFFF0, 0x480), (0x6FFFFFFE, 0x490), (0x6FFFFFFF, 1), (0, 0)]
    dynamic_data = b"".join(struct.pack("<qQ", *row) for row in tags)
    add(".dynamic", 0x4010, dynamic_data, kind=6, flags=3, address=0x8010, alignment=8, entries=16, link=dynamic_strings)
    add(".got", 0x4190, bytes(16), flags=3, address=0x8190, alignment=8)
    got_plt = add(".got.plt", 0x41A0, bytes(24), flags=3, address=0x81A0, alignment=8)
    sections[plt_relocations]["info"] = got_plt
    add(".relro_padding", 0x41B8, b"", kind=8, flags=3, address=0x81B8, size=0x3E48)
    expected = {"arm64": b"ELF64 source fixture bytes", "arm32": b"ELF32 source fixture bytes"}
    data = bytearray(0x100)
    data[:len(expected["arm64"])] = expected["arm64"]
    data[0x40:0x40 + len(expected["arm32"])] = expected["arm32"]
    data_index = add(".data", 0x4200, bytes(data), flags=3, address=0x10200, alignment=8)
    add(".bss", 0x4300, b"", kind=8, flags=3, address=0x10300, size=0x20, alignment=8)
    symbol_locations = {}
    if symbols:
        names = bytearray(b"\0")
        rows = [("", 0, 0, 0, 0, 0), (inspector.CONSTANT, 0x11, 0, rodata, 0x800, 8)]
        for abi, address in (("arm64", 0x10200), ("arm32", 0x10240)):
            prefix = inspector.WRAPPERS[abi]
            rows.extend([(prefix + "start", 0x10, 0, data_index, address, 0),
                         (prefix + "end", 0x10, 0, data_index, address + len(expected[abi]), 0),
                         (prefix + "size", 0x10, 0, 0xFFF1, len(expected[abi]), 0)])
        table = bytearray()
        for name, info, visibility, section, value, size in rows:
            offset = len(names) if name else 0
            if name:
                names.extend(name.encode("ascii") + b"\0")
                symbol_locations[name] = 0x4300 + len(table)
            table.extend(struct.pack("<IBBHQQ", offset, info, visibility, section, value, size))
        symbol_index = add(".symtab", 0x4300, bytes(table), kind=2, flags=0, address=0, alignment=8, entries=24, info=1)
        string_index = add(".strtab", len(image), bytes(names), kind=3, flags=0, address=0)
        sections[symbol_index]["link"] = string_index
    shstrings = bytearray(b"\0")
    shnames = {"": 0}
    for name in [s["name"] for s in sections[1:]] + [".shstrtab"]:
        shnames[name] = len(shstrings)
        shstrings.extend(name.encode("ascii") + b"\0")
    shstr = add(".shstrtab", len(image), bytes(shstrings), kind=3, flags=0, address=0)
    shoff = (len(image) + 7) & ~7
    image.extend(bytes(shoff - len(image)))
    for s in sections:
        image.extend(struct.pack("<IIQQQQIIQQ", shnames[s["name"]], s["type"], s["flags"], s["address"], s["offset"],
                                 s["size"], s["link"], s["info"], s["alignment"], s["entries"]))
    programs = [(6, 4, 64, 64, 12 * 56, 12 * 56, 8), (3, 4, 0x300, 0x300, len(inspector.INTERPRETER), len(inspector.INTERPRETER), 1),
                (1, 4, 0, 0, 0x910, 0x910, 0x4000), (1, 5, 0x2000, 0x6000, 0x50, 0x50, 0x4000),
                (1, 6, 0x4000, 0x8000, 0x1B8, 0x4000, 0x4000), (1, 6, 0x4200, 0x10200, 0x100, 0x120, 0x4000),
                (2, 6, 0x4010, 0x8010, len(dynamic_data), len(dynamic_data), 8), (7, 4, 0x4000, 0x8000, 0, 0, 64),
                (0x6474E552, 4, 0x4000, 0x8000, 0x1B8, 0x4000, 1), (0x6474E550, 4, 0x900, 0x900, 4, 4, 4),
                (0x6474E551, 6, 0, 0, 0, 0, 0), (4, 4, 0x320, 0x320, len(android_note) + len(build_note), len(android_note) + len(build_note), 4)]
    image[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", image, 16, 3, 183, 1, 0x6000, 64, shoff, 0, 64, 56, len(programs), 64, len(sections), shstr)
    for index, (kind, flags, offset, address, size, memory, alignment) in enumerate(programs):
        struct.pack_into("<IIQQQQQQ", image, 64 + index * 56, kind, flags, offset, address, address, size, memory, alignment)
    section_map = {s["name"]: dict(s, header=shoff + index * 64, index=index, name_offset=shnames[s["name"]]) for index, s in enumerate(sections)}
    return bytes(image), {"sections": section_map, "symbols": symbol_locations, "programs": programs, "shoff": shoff,
                          "tags": {tag: 0x4010 + index * 16 for index, (tag, _) in enumerate(tags) if tag != 1},
                          "expected": expected, "offset": 1120}


def section_changed(raw, layout, name, field, value):
    fmt, offset = {"name": ("I", 0), "type": ("I", 4), "flags": ("Q", 8), "address": ("Q", 16),
                   "offset": ("Q", 24), "size": ("Q", 32), "link": ("I", 40), "info": ("I", 44),
                   "alignment": ("Q", 48), "entries": ("Q", 56)}[field]
    return changed(raw, fmt, layout["sections"][name]["header"] + offset, value)


def program_changed(raw, index, field, value):
    fmt, offset = {"type": ("I", 0), "flags": ("I", 4), "offset": ("Q", 8), "address": ("Q", 16),
                   "physical": ("Q", 24), "size": ("Q", 32), "memory": ("Q", 40), "alignment": ("Q", 48)}[field]
    return changed(raw, fmt, 64 + index * 56 + offset, value)


def inspect_coupled(raw, layout, **kwargs):
    return inspector.inspect_engine(raw, expected_loaders=layout["expected"],
                                    expected_pokedata_offset=layout["offset"], require_symbols=True, **kwargs)


class EngineStructureTests(unittest.TestCase):
    def test_valid_dynamic_contract_is_json_safe_and_does_not_claim_execution(self):
        raw, _ = fixture(symbols=False)
        result = inspector.inspect_engine(raw)
        self.assertEqual(result["type"], "ET_DYN")
        self.assertEqual(result["interpreter"], "/system/bin/linker64")
        self.assertEqual(result["dynamicDependencies"], ["libdl.so", "libc.so"])
        self.assertEqual(result["androidIdentity"], {"api": 29, "ndk": "r28c", "build": "13676358"})
        self.assertEqual(result["relocationCounts"], {"1025": 1, "1026": 1, "1027": 1})
        self.assertTrue(result["nonExecutableStack"] and result["noWritableExecutableLoad"] and result["bindNow"] and result["pie"])
        self.assertFalse(result["executed"] or result["runtimeCompatibilityVerified"] or result["coupling"]["verified"])
        self.assertEqual(json.loads(json.dumps(result)), result)

    def test_init_array_accepts_observed_zero_entry_size_and_explicit_pointer_size(self):
        for entry_size in (0, 8):
            raw, _ = fixture(init_entry_bytes=entry_size)
            with self.subTest(entry_size=entry_size):
                inspector.inspect_engine(raw)
        raw, layout = fixture()
        for field, value in (("entries", 16), ("size", 7), ("size", 0), ("alignment", 4)):
            with self.subTest(field=field), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(section_changed(raw, layout, ".init_array", field, value))

    def test_wrong_types_sizes_identity_and_header_contract(self):
        raw, _ = fixture()
        for value in (None, bytearray(raw), "ELF", raw[:63], raw + bytes(inspector.MAX_BINARY_BYTES)):
            with self.subTest(kind=type(value).__name__), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(value)
        cases = [("B", 4, 1), ("B", 5, 2), ("B", 7, 3), ("B", 15, 1), ("H", 16, 2), ("H", 18, 40),
                 ("I", 20, 2), ("Q", 24, 0), ("Q", 24, 0x6001), ("I", 48, 1), ("H", 52, 63),
                 ("H", 54, 55), ("H", 56, 0), ("H", 56, 33), ("H", 58, 63), ("H", 60, 0),
                 ("H", 60, 129), ("H", 62, 0), ("H", 62, 0xFFFF)]
        for fmt, offset, value in cases:
            with self.subTest(offset=offset, value=value), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, fmt, offset, value))

    def test_tables_are_bounded_nonoverlapping_and_not_extended(self):
        raw, layout = fixture()
        for offset, value in ((32, 0), (32, 65), (32, layout["shoff"]), (40, 64), (40, len(raw) - 8), (40, (1 << 64) - 8)):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, "Q", offset, value))
        with self.assertRaisesRegex(inspector.EngineElfError, "NULL section"):
            inspector.inspect_engine(changed(raw, "Q", layout["shoff"] + 32, 1))

    def test_dynamic_engine_rejects_missing_or_wrong_interpreter(self):
        raw, _ = fixture()
        for value in (program_changed(raw, 1, "type", 7), changed(raw, "B", 0x301, ord("X")),
                      changed(raw, "B", 0x300 + len(inspector.INTERPRETER) - 1, 1)):
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(value)

    def test_program_alignment_rwx_sizes_duplicates_and_overflow(self):
        raw, _ = fixture()
        cases = [(3, "flags", 7), (3, "flags", 4), (3, "alignment", 0x1000), (3, "offset", 0),
                 (3, "size", len(raw)), (3, "memory", 1), (3, "physical", 1), (0, "type", 0),
                 (0, "size", 8), (10, "flags", 7), (7, "memory", 1)]
        for index, field, value in cases:
            with self.subTest(index=index, field=field), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(program_changed(raw, index, field, value))
        altered = program_changed(program_changed(raw, 3, "address", (1 << 64) - 4), 3, "physical", (1 << 64) - 4)
        with self.assertRaises(inspector.EngineElfError):
            inspector.inspect_engine(altered)
        data = bytearray(raw)
        data[64 + 3 * 56:64 + 4 * 56] = data[64 + 2 * 56:64 + 3 * 56]
        with self.assertRaisesRegex(inspector.EngineElfError, "duplicate program"):
            inspector.inspect_engine(bytes(data))

    def test_section_types_names_spans_mapping_and_links_fail_closed(self):
        raw, layout = fixture()
        cases = [(".text", "type", 9), (".text", "flags", 0x806), (".text", "address", (1 << 64) - 4),
                 (".text", "offset", len(raw)), (".text", "link", 999), (".text", "info", 1),
                 (".text", "name", layout["sections"][".rodata"]["name_offset"]),
                 (".dynamic", "offset", 0x4000), (".data", "offset", layout["shoff"]),
                 (".bss", "address", 0x10200), (".tdata", "size", 8), (".tdata", "address", 0x8008)]
        for name, field, value in cases:
            with self.subTest(name=name, field=field), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(section_changed(raw, layout, name, field, value))

    def test_strict_dynamic_dependencies_flags_and_all_singleton_pointers(self):
        raw, layout = fixture()
        for tag in (2, 3, 5, 6, 7, 8, 9, 10, 11, 20, 21, 23, 25, 27, 30, 0x6FFFFEF5, 0x6FFFFFF0, 0x6FFFFFF9, 0x6FFFFFFB, 0x6FFFFFFE, 0x6FFFFFFF):
            position = layout["tags"][tag] + 8
            old, = struct.unpack_from("<Q", raw, position)
            with self.subTest(tag=tag), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, "Q", position, old + 1))
        # Needed entries must remain exactly the ordered two existing names.
        for value in (0, 10, 0xFFFFFF):
            with self.subTest(needed=value), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, "Q", 0x4010 + 8, value))

    def test_unused_duplicate_dynamic_tables_and_extra_relocation_sections_are_rejected(self):
        raw, layout = fixture()
        for kind in (6, 11, 14, 0x6FFFFFF6, 0x6FFFFFFE, 0x6FFFFFFF, 4):
            with self.subTest(kind=kind), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(section_changed(raw, layout, ".rodata", "type", kind))

    def test_dynamic_unsafe_duplicate_missing_and_post_terminator_tags(self):
        raw, layout = fixture()
        for tag in (14, 15, 17, 22, 29, 0x60000011, 0xFFFFFFFF):
            with self.subTest(tag=tag), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, "q", layout["tags"][21], tag))
        with self.assertRaisesRegex(inspector.EngineElfError, "duplicate singleton"):
            inspector.inspect_engine(changed(raw, "q", layout["tags"][21], 30))
        with self.assertRaisesRegex(inspector.EngineElfError, "after dynamic terminator"):
            inspector.inspect_engine(changed(raw, "qQ", layout["tags"][21], 0, 0))
        with self.assertRaises(inspector.EngineElfError):
            inspector.inspect_engine(changed(raw, "qQ", layout["tags"][0], 21, 0))

    def test_dynsym_and_gnu_hash_table_dimensions_cannot_be_faked(self):
        raw, layout = fixture()
        for name, field, value in ((".dynsym", "entries", 16), (".dynsym", "link", 0),
                                   (".dynsym", "info", 0), (".dynsym", "info", 2),
                                   (".gnu.hash", "link", 0), (".dynstr", "flags", 0)):
            with self.subTest(name=name, field=field), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(section_changed(raw, layout, name, field, value))
        for offset, value in ((0x4C0, 0), (0x4C4, 4), (0x4C8, 3), (0x4CC, 64), (0x4D8, 4)):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, "I", offset, value))

    def test_version_tables_require_bounded_links_unique_identifiers_and_known_versions(self):
        raw, _ = fixture()
        for fmt, offset, value in (("H", 0x490, 2), ("H", 0x492, 0), ("I", 0x498, 0xFFFFFF),
                                   ("I", 0x49C, 16), ("H", 0x4A6, 1), ("I", 0x4AC, 16),
                                   ("H", 0x482, 3)):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, fmt, offset, value))

    def test_relocations_validate_types_symbols_ranges_and_no_duplicate_writes(self):
        raw, _ = fixture()
        cases = [("Q", 0x600, 0x8009), ("Q", 0x600, 0x6000), ("Q", 0x608, 1025),
                 ("Q", 0x620, (99 << 32) | 1025), ("Q", 0x648, (1 << 32) | 1027),
                 ("q", 0x650, 1), ("Q", 0x640, 0x8008)]
        for fmt, offset, value in cases:
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, fmt, offset, value))

    def test_android_and_build_id_notes_have_exact_bounded_identity(self):
        raw, layout = fixture()
        android = layout["sections"][".note.android.ident"]["offset"]
        gnu = layout["sections"][".note.gnu.build-id"]["offset"]
        for fmt, offset, value in (("I", android, 0xFFFFFFFF), ("I", android + 4, 0),
                                   ("I", android + 20, 28), ("B", android + 24, ord("X")),
                                   ("B", android + 29, 1), ("I", gnu + 4, 19), ("B", gnu + 12, ord("X"))):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(changed(raw, fmt, offset, value))

    def test_real_retained_engine_pin_passes_without_symbol_claim(self):
        path = TOOLS.parent / "app/src/main/jniLibs/arm64-v8a/libdroide_proot.so"
        raw = path.read_bytes()
        self.assertEqual(len(raw), 265648)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), "6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b")
        result = inspector.inspect_engine(raw)
        self.assertEqual(result["entryPoint"], 0x191DC)
        self.assertEqual(result["relocationCounts"], {"1025": 4, "1026": 125, "1027": 651})
        self.assertEqual(result["symbolCount"], 0)
        self.assertFalse(result["coupling"]["verified"] or result["runtimeCompatibilityVerified"])


class EngineCouplingTests(unittest.TestCase):
    def test_seven_unique_symbol_backed_values_match_exact_source_built_inputs(self):
        raw, layout = fixture()
        result = inspect_coupled(raw, layout)
        coupling = result["coupling"]
        self.assertTrue(coupling["verified"] and coupling["constantVerified"] and coupling["embeddedLoadersVerified"])
        self.assertTrue(coupling["relocationsDoNotOverlap"])
        self.assertEqual(coupling["pokedataOffset"], 1120)
        self.assertEqual(result["symbolCount"], 8)
        for abi, expected in layout["expected"].items():
            self.assertEqual(coupling["loaders"][abi]["sha256"], hashlib.sha256(expected).hexdigest())
            self.assertEqual(coupling["loaders"][abi]["end"] - coupling["loaders"][abi]["start"], len(expected))

    def test_symbol_gate_requires_explicit_valid_inputs_and_never_ignores_expectations(self):
        raw, layout = fixture()
        invalid = (None, {}, {"arm64": b"x"}, dict(layout["expected"], arm32="x"), dict(layout["expected"], arm32=b""),
                   dict(layout["expected"], arm32=bytes(1024 * 1024 + 1)))
        for value in invalid:
            with self.subTest(expected=value is None), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(raw, expected_loaders=value, expected_pokedata_offset=1120, require_symbols=True)
        for value in (None, False, 0, -4, 1, 1024 * 1024 + 4):
            with self.subTest(offset=value), self.assertRaises(inspector.EngineElfError):
                inspector.inspect_engine(raw, expected_loaders=layout["expected"], expected_pokedata_offset=value, require_symbols=True)
        with self.assertRaisesRegex(inspector.EngineElfError, "unchecked"):
            inspector.inspect_engine(raw, expected_loaders=layout["expected"])
        with self.assertRaises(inspector.EngineElfError):
            inspector.inspect_engine(raw, require_symbols=1)

    def test_stripped_input_cannot_pass_prestrip_symbol_gate(self):
        raw, layout = fixture(symbols=False)
        with self.assertRaisesRegex(inspector.EngineElfError, "symtab"):
            inspect_coupled(raw, layout)

    def test_duplicate_missing_undefined_local_weak_hidden_or_wrong_type_symbols_fail(self):
        raw, layout = fixture()
        constant = layout["symbols"][inspector.CONSTANT]
        first = layout["symbols"][inspector.WRAPPERS["arm64"] + "start"]
        name, = struct.unpack_from("<I", raw, constant)
        cases = [changed(raw, "I", first, name), changed(raw, "I", first, 0), changed(raw, "H", first + 6, 0),
                 changed(raw, "B", first + 4, 0), changed(raw, "B", first + 4, 0x20),
                 changed(raw, "B", first + 5, 2), changed(raw, "B", constant + 4, 0x12),
                 changed(raw, "Q", constant + 16, 4)]
        for value in cases:
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.EngineElfError):
                inspect_coupled(value, layout)

    def test_offset_value_and_both_loader_payloads_are_compared_literally(self):
        raw, layout = fixture()
        for fmt, offset, value in (("q", 0x800, 1116), ("q", 0x800, -1120),
                                   ("B", 0x4200, 0), ("B", 0x4240, 0)):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspect_coupled(changed(raw, fmt, offset, value), layout)
        swapped = dict(layout, expected={"arm64": layout["expected"]["arm32"], "arm32": layout["expected"]["arm64"]})
        with self.assertRaisesRegex(inspector.EngineElfError, "embedded loader bytes"):
            inspect_coupled(raw, swapped)

    def test_readonly_section_label_cannot_hide_writable_constant_mapping(self):
        raw, layout = fixture()
        altered = program_changed(raw, 2, "flags", 6)
        inspector.inspect_engine(altered)
        with self.assertRaisesRegex(inspector.EngineElfError, "read-only PT_LOAD"):
            inspect_coupled(altered, layout)

    def test_wrapper_absolute_sizes_end_bounds_and_sections_are_verified(self):
        raw, layout = fixture()
        prefix = inspector.WRAPPERS["arm64"]
        cases = [(prefix + "size", "Q", 8, len(layout["expected"]["arm64"]) + 1),
                 (prefix + "size", "H", 6, layout["sections"][".data"]["index"]),
                 (prefix + "end", "Q", 8, 0x10200), (prefix + "end", "Q", 8, 0x10400),
                 (prefix + "start", "H", 6, layout["sections"][".bss"]["index"]),
                 (prefix + "start", "Q", 16, 1)]
        for name, fmt, relative, value in cases:
            with self.subTest(name=name, relative=relative), self.assertRaises(inspector.EngineElfError):
                inspect_coupled(changed(raw, fmt, layout["symbols"][name] + relative, value), layout)

    def test_relations_cannot_claim_file_match_while_relocations_overwrite_payload(self):
        raw, layout = fixture()
        for target in (0x10200, 0x10240):
            value = changed(raw, "Q", 0x600, target)
            # Dynamic structure itself is valid; only coupling rejects the write.
            inspector.inspect_engine(value)
            with self.subTest(target=target), self.assertRaisesRegex(inspector.EngineElfError, "relocation overlaps"):
                inspect_coupled(value, layout)

    def test_raw_and_structural_inspections_report_distinct_claim_scope(self):
        raw, layout = fixture()
        structural, coupled = inspector.inspect_engine(raw), inspect_coupled(raw, layout)
        self.assertEqual(structural["textSha256"], coupled["textSha256"])
        self.assertEqual(structural["loadableSegmentsSha256"], coupled["loadableSegmentsSha256"])
        self.assertFalse(structural["coupling"]["verified"])
        self.assertTrue(coupled["coupling"]["verified"])
        self.assertFalse(coupled["runtimeCompatibilityVerified"])

    def test_load_digest_is_length_framed_and_text_hash_is_literal(self):
        raw, layout = fixture()
        result = inspector.inspect_engine(raw)
        digest = hashlib.sha256(b"FDROID-PROOT-ENGINE-LOAD-V1\0")
        loads = [row for row in layout["programs"] if row[0] == 1]
        digest.update(struct.pack("<I", len(loads)))
        for _, flags, offset, address, size, memory, alignment in loads:
            digest.update(struct.pack("<7Q", flags, offset, address, address, size, memory, alignment))
            digest.update(raw[offset:offset + size])
        self.assertEqual(result["loadableSegmentsSha256"], digest.hexdigest())
        self.assertEqual(result["textSha256"], hashlib.sha256(raw[0x2000:0x2040]).hexdigest())
        changed_result = inspector.inspect_engine(changed(raw, "B", 0x2000, 0))
        self.assertNotEqual(result["loadableSegmentsSha256"], changed_result["loadableSegmentsSha256"])
        self.assertNotEqual(result["textSha256"], changed_result["textSha256"])
        self.assertFalse(changed_result["runtimeCompatibilityVerified"])


if __name__ == "__main__":
    unittest.main()
