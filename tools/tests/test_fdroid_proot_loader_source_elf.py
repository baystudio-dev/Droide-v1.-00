"""Data-only ELF fixtures/mutations; neither ARM loader is ever executed."""
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
import fdroid_proot_loader_elf as inspector


def changed(raw, fmt, offset, *values):
    result = bytearray(raw)
    struct.pack_into("<" + fmt, result, offset, *values)
    return bytes(result)


def aligned(value, alignment):
    return (value + alignment - 1) & ~(alignment - 1)


def fixture(abi="arm64", *, symbols=True):
    """Emit small synthetic reviewed layouts, never compiler or target output."""
    wide = abi == "arm64"
    ehsize, phsize, shsize, unit = (64, 56, 64, 8) if wide else (52, 32, 40, 4)
    alignment, entry, header_address = (0x4000, 0x2000000000, 0x200000) if wide else (0x1000, 0x20000000, 0x10000)
    phnum = 4 if wide else 6
    image = bytearray(alignment)
    sections = [{"name": "", "type": 0, "flags": 0, "address": 0, "offset": 0, "size": 0,
                 "link": 0, "info": 0, "alignment": 0, "entries": 0}]
    def add(name, data, kind=1, flags=0, address=0, alignment=1, entries=0, link=0, info=0):
        offset = aligned(len(image), alignment)
        image.extend(bytes(offset - len(image)))
        image.extend(data)
        sections.append({"name": name, "type": kind, "flags": flags, "address": address,
                         "offset": offset, "size": len(data), "link": link, "info": info,
                         "alignment": alignment, "entries": entries})
        return len(sections) - 1
    text = (bytes.fromhex("1f2003d5") * 4 + inspector.POKEDATA_STUB + bytes.fromhex("1f2003d5") * 2
            if wide else bytes.fromhex("0000a0e1") * 8)
    add(".text", text, flags=6, address=entry, alignment=4)
    if not wide:
        add(".ARM.exidx", bytes(16), kind=0x70000001, flags=0x82,
            address=entry + 0x1000 + len(text), alignment=4, link=1)
    add(".comment", b"\0Android clang synthetic 21.0.0\0Linker: LLD synthetic 21.0.0\0", flags=0x30, entries=1)
    if not wide:
        # The inspector reports attribute-section bytes without interpreting ABI tags.
        add(".ARM.attributes", b"A\x0b\0\0\0aeabi\0\0", kind=0x70000003)
    symbol_locations = {}
    if symbols:
        strings = bytearray(b"\0")
        name_offsets = {"": 0}
        def name_offset(name):
            if name not in name_offsets:
                name_offsets[name] = len(strings)
                strings.extend(name.encode("ascii") + b"\0")
            return name_offsets[name]
        rows = [("", 0, 0, 0, 0, 0), ("loader.c", 4, 0, 0xFFF1, 0, 0),
                ("", 3, 0, 1, entry, 0), ("$x.0" if wide else "$a.0", 0, 0, 1, entry, 0),
                ("_start", 0x12, 0, 1, entry, 16)]
        if wide:
            rows.append(("pokedata_workaround", 0x10, 0, 1, entry + 16, 0))
        symbol_data = bytearray()
        for name, info, visibility, section, address, size in rows:
            if name:
                symbol_locations[name] = len(symbol_data)
            values = (name_offset(name), info, visibility, section, address, size) if wide else (
                name_offset(name), address, size, info, visibility, section)
            symbol_data.extend(struct.pack("<IBBHQQ" if wide else "<IIIBBH", *values))
        symtab_index = add(".symtab", symbol_data, kind=2, alignment=unit, entries=24 if wide else 16, info=4)
        strtab_index = add(".strtab", bytes(strings), kind=3)
        sections[symtab_index]["link"] = strtab_index
        symbol_locations = {name: sections[symtab_index]["offset"] + position for name, position in symbol_locations.items()}
    names = bytearray(b"\0")
    offsets = {"": 0}
    for section_name in [s["name"] for s in sections[1:]] + [".shstrtab"]:
        offsets[section_name] = len(names)
        names.extend(section_name.encode("ascii") + b"\0")
    shstr = add(".shstrtab", bytes(names), kind=3)
    shoff = aligned(len(image), unit)
    image.extend(bytes(shoff - len(image)))
    for section in sections:
        values = (offsets[section["name"]], section["type"], section["flags"], section["address"],
                  section["offset"], section["size"], section["link"], section["info"],
                  section["alignment"], section["entries"])
        image.extend(struct.pack("<IIQQQQIIQQ" if wide else "<IIIIIIIIII", *values))
    programs = [(6, 4, ehsize, header_address + ehsize, phnum * phsize, phnum * phsize, unit),
                (1, 4, 0, header_address, ehsize + phnum * phsize, ehsize + phnum * phsize, alignment),
                (1, 5, alignment, entry, len(text), len(text), alignment)]
    if not wide:
        exidx = sections[2]
        programs.append((1, 4, exidx["offset"], exidx["address"], exidx["size"], exidx["size"], alignment))
    programs.append((0x6474E551, 6, 0, 0, 0, 0, 0))
    if not wide:
        exidx = sections[2]
        programs.append((0x70000001, 4, exidx["offset"], exidx["address"], exidx["size"], exidx["size"], 4))
    image[:16] = b"\x7fELF" + bytes((2 if wide else 1, 1, 1)) + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH" if wide else "<HHIIIIIHHHHHH", image, 16,
                     2, 183 if wide else 40, 1, entry, ehsize, shoff, 0 if wide else 0x05000200,
                     ehsize, phsize, phnum, shsize, len(sections), shstr)
    for index, (kind, flags, offset, address, size, memory, align) in enumerate(programs):
        values = (kind, flags, offset, address, address, size, memory, align) if wide else (
            kind, offset, address, address, size, memory, flags, align)
        struct.pack_into("<IIQQQQQQ" if wide else "<IIIIIIII", image, ehsize + index * phsize, *values)
    section_map = {s["name"]: dict(s, header=shoff + index * shsize, index=index, name_offset=offsets[s["name"]])
                   for index, s in enumerate(sections)}
    return bytes(image), {"wide": wide, "entry": entry, "alignment": alignment, "ehsize": ehsize,
                          "phsize": phsize, "phnum": phnum, "shoff": shoff, "shsize": shsize,
                          "sections": section_map, "symbols": symbol_locations, "programs": programs}


def section_changed(raw, layout, name, field, value):
    offsets = ({"name": ("I", 0), "type": ("I", 4), "flags": ("Q", 8), "address": ("Q", 16),
                "offset": ("Q", 24), "size": ("Q", 32), "link": ("I", 40), "info": ("I", 44),
                "alignment": ("Q", 48), "entries": ("Q", 56)} if layout["wide"] else
               {"name": ("I", 0), "type": ("I", 4), "flags": ("I", 8), "address": ("I", 12),
                "offset": ("I", 16), "size": ("I", 20), "link": ("I", 24), "info": ("I", 28),
                "alignment": ("I", 32), "entries": ("I", 36)})
    fmt, offset = offsets[field]
    return changed(raw, fmt, layout["sections"][name]["header"] + offset, value)


def program_changed(raw, layout, index, field, value):
    offsets = ({"type": ("I", 0), "flags": ("I", 4), "offset": ("Q", 8), "address": ("Q", 16),
                "physical": ("Q", 24), "size": ("Q", 32), "memory": ("Q", 40), "alignment": ("Q", 48)}
               if layout["wide"] else
               {"type": ("I", 0), "offset": ("I", 4), "address": ("I", 8), "physical": ("I", 12),
                "size": ("I", 16), "memory": ("I", 20), "flags": ("I", 24), "alignment": ("I", 28)})
    fmt, offset = offsets[field]
    return changed(raw, fmt, layout["ehsize"] + index * layout["phsize"] + offset, value)


class LoaderElfStructureTests(unittest.TestCase):
    def test_valid_arm64_symbol_gate_and_json_safe_evidence(self):
        raw, layout = fixture()
        result = inspector.inspect_loader(raw, "arm64", require_symbols=True)
        self.assertEqual(result["entryPoint"], layout["entry"])
        self.assertEqual(result["pokedataOffset"], 16)
        self.assertTrue(result["pokedataStubVerified"])
        self.assertEqual(result["symbols"]["_start"]["value"], layout["entry"])
        self.assertEqual(result["symbols"]["pokedata_workaround"]["type"], 0)
        self.assertEqual(result["symbolCount"], 6)
        self.assertFalse(result["executed"])
        self.assertIn("not method semantics", result["inspectionScope"])
        self.assertEqual(json.loads(json.dumps(result)), result)

    def test_both_stripped_layouts_use_same_routine_without_symbol_requirement(self):
        for abi in ("arm64", "arm32"):
            with self.subTest(abi=abi):
                raw, layout = fixture(abi, symbols=False)
                result = inspector.inspect_loader(raw, abi)
                self.assertEqual(result["symbols"], {})
                self.assertIsNone(result["pokedataOffset"])
                self.assertEqual(result["minimumLoadAlignment"], layout["alignment"])
                self.assertTrue(result["static"] and result["nonExecutableStack"])
                self.assertEqual(len(result["compilerComments"]), 2)

    def test_arm32_symbol_table_is_valid_but_arm64_symbol_requirement_is_not_reused(self):
        raw, _ = fixture("arm32")
        self.assertIn("_start", inspector.inspect_loader(raw, "arm32")["symbols"])
        with self.assertRaisesRegex(inspector.LoaderElfError, "ARM64-only"):
            inspector.inspect_loader(raw, "arm32", require_symbols=True)

    def test_input_types_size_and_architecture_identity_fail_closed(self):
        raw, _ = fixture()
        for value in (None, bytearray(raw), "ELF", raw[:63], raw + bytes(inspector.MAX_BINARY_BYTES)):
            with self.subTest(kind=type(value).__name__), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(value, "arm64")
        for abi in (None, [], "aarch64", "arm", "arm32"):
            with self.subTest(abi=abi), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(raw, abi)
        for value in (1, None, "yes"):
            with self.subTest(require=value), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(raw, "arm64", require_symbols=value)

    def test_bad_identity_reserved_bytes_and_header_contract(self):
        raw, _ = fixture()
        cases = [("B", 0, 0), ("B", 4, 1), ("B", 5, 2), ("B", 6, 2), ("B", 7, 3),
                 ("B", 8, 1), ("B", 15, 1), ("H", 16, 3), ("H", 18, 62), ("I", 20, 2),
                 ("Q", 24, 0x2000000004), ("I", 48, 1), ("H", 52, 63), ("H", 54, 55),
                 ("H", 56, 0), ("H", 56, 33), ("H", 58, 63), ("H", 60, 0),
                 ("H", 60, 129), ("H", 62, 0), ("H", 62, 0xFFFF)]
        for fmt, offset, value in cases:
            with self.subTest(offset=offset, value=value), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(changed(raw, fmt, offset, value), "arm64")

    def test_arm32_requires_exact_eabi5_soft_float_flags(self):
        raw, _ = fixture("arm32", symbols=False)
        for value in (0, 0x04000200, 0x05000000, 0x05000400, 0x05000600, 0x85000200):
            with self.subTest(flags=value), self.assertRaisesRegex(inspector.LoaderElfError, "architecture flags"):
                inspector.inspect_loader(changed(raw, "I", 36, value), "arm32")

    def test_tables_cannot_overlap_or_escape_the_file(self):
        raw, layout = fixture()
        for field, value in ((32, 0), (32, 65), (32, layout["shoff"]), (32, len(raw) - 8),
                             (40, 0), (40, 65), (40, 64), (40, len(raw) - 8), (40, (1 << 64) - 8)):
            with self.subTest(field=field, value=value), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(changed(raw, "Q", field, value), "arm64")

    def test_null_section_cannot_encode_extended_tables(self):
        raw, layout = fixture()
        with self.assertRaisesRegex(inspector.LoaderElfError, "null section"):
            inspector.inspect_loader(changed(raw, "Q", layout["shoff"] + 32, 10), "arm64")

    def test_program_headers_reject_dynamic_interpreter_tls_and_unknown_types(self):
        raw, layout = fixture()
        for kind in (0, 2, 3, 7, 0x70000001, 0xFFFFFFFF):
            with self.subTest(kind=kind), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(program_changed(raw, layout, 0, "type", kind), "arm64")

    def test_load_alignment_permissions_sizes_and_address_overflow(self):
        for abi in ("arm64", "arm32"):
            raw, layout = fixture(abi)
            cases = [("alignment", 0), ("alignment", 3), ("alignment", layout["alignment"] * 2),
                     ("flags", 0), ("flags", 1), ("flags", 7), ("flags", 0x100),
                     ("size", len(raw) + 1), ("memory", 1), ("memory", inspector.MAX_BINARY_BYTES + 1),
                     ("offset", len(raw)), ("address", (1 << (64 if layout["wide"] else 32)) - 4)]
            for field, value in cases:
                altered = program_changed(raw, layout, 2, field, value)
                if field == "address":
                    altered = program_changed(altered, layout, 2, "physical", value)
                with self.subTest(abi=abi, field=field, value=value), self.assertRaises(inspector.LoaderElfError):
                    inspector.inspect_loader(altered, abi)

    def test_duplicate_load_and_overlapping_file_backing_fail(self):
        raw, layout = fixture()
        offset = layout["ehsize"] + layout["phsize"]
        duplicate = bytearray(raw)
        duplicate[offset + layout["phsize"]:offset + 2 * layout["phsize"]] = duplicate[offset:offset + layout["phsize"]]
        with self.assertRaisesRegex(inspector.LoaderElfError, "duplicate program"):
            inspector.inspect_loader(bytes(duplicate), "arm64")
        altered = program_changed(raw, layout, 2, "offset", 0)
        with self.assertRaisesRegex(inspector.LoaderElfError, "PT_LOAD file spans"):
            inspector.inspect_loader(altered, "arm64")

    def test_entry_is_not_accepted_in_nonexecuting_or_unbacked_load(self):
        raw, layout = fixture()
        for field, value in (("flags", 4), ("size", 0)):
            with self.subTest(field=field), self.assertRaisesRegex(inspector.LoaderElfError, "uniquely file/memory-backed"):
                inspector.inspect_loader(program_changed(raw, layout, 2, field, value), "arm64")

    def test_stack_must_be_unique_rw_and_nonexecuting(self):
        raw, layout = fixture()
        for field, value in (("flags", 7), ("flags", 4), ("offset", 1), ("memory", 1), ("type", 4)):
            with self.subTest(field=field), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(program_changed(raw, layout, 3, field, value), "arm64")
        start = layout["ehsize"]
        duplicate = bytearray(raw)
        duplicate[start:start + layout["phsize"]] = duplicate[start + 3 * layout["phsize"]:start + 4 * layout["phsize"]]
        with self.assertRaisesRegex(inspector.LoaderElfError, "duplicate"):
            inspector.inspect_loader(bytes(duplicate), "arm64")

    def test_phdr_range_and_metadata_mapping_must_be_exact(self):
        raw, layout = fixture()
        for field, value in (("offset", 72), ("size", layout["phsize"]), ("flags", 5), ("physical", 0)):
            with self.subTest(field=field), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(program_changed(raw, layout, 0, field, value), "arm64")

    def test_sections_reject_unsupported_types_flags_links_alignment_and_spans(self):
        raw, layout = fixture()
        cases = [("type", 0), ("type", 4), ("type", 6), ("type", 9), ("type", 11), ("type", 0x70000003),
                 ("flags", 0x806), ("flags", 0x406), ("link", 999), ("info", 1), ("alignment", 3),
                 ("offset", len(raw)), ("size", len(raw) + 1), ("address", (1 << 64) - 4)]
        for field, value in cases:
            with self.subTest(field=field, value=value), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(section_changed(raw, layout, ".text", field, value), "arm64")

    def test_duplicate_names_missing_text_and_table_aliases_are_rejected(self):
        raw, layout = fixture()
        cases = [section_changed(raw, layout, ".comment", "name", layout["sections"][".text"]["name_offset"]),
                 section_changed(raw, layout, ".text", "name", 0),
                 section_changed(raw, layout, ".comment", "offset", layout["sections"][".text"]["offset"]),
                 section_changed(raw, layout, ".comment", "offset", layout["shoff"]),
                 section_changed(raw, layout, ".text", "address", layout["entry"] + 4)]
        for value in cases:
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(value, "arm64")

    def test_string_tables_cannot_have_bad_indices_boundaries_or_control_bytes(self):
        raw, layout = fixture()
        names = layout["sections"][".shstrtab"]
        cases = [section_changed(raw, layout, ".text", "name", names["size"]),
                 changed(raw, "B", names["offset"], 1),
                 changed(raw, "B", names["offset"] + names["size"] - 1, 1),
                 changed(raw, "B", names["offset"] + names["name_offset"], 1),
                 section_changed(raw, layout, ".shstrtab", "type", 1),
                 section_changed(raw, layout, ".strtab", "entries", 1)]
        for value in cases:
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(value, "arm64")

    def test_arm_exception_index_requires_matching_program_and_text_link(self):
        raw, layout = fixture("arm32")
        for field, value in (("link", 0), ("size", 15), ("flags", 2), ("address", layout["entry"] + 0x1004)):
            with self.subTest(field=field), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(section_changed(raw, layout, ".ARM.exidx", field, value), "arm32")
        altered = program_changed(raw, layout, 5, "size", 8)
        altered = program_changed(altered, layout, 5, "memory", 8)
        with self.assertRaisesRegex(inspector.LoaderElfError, "span mismatch"):
            inspector.inspect_loader(altered, "arm32")

    def test_comment_is_bounded_printable_and_not_loaded(self):
        raw, layout = fixture()
        comments = layout["sections"][".comment"]
        for value in (changed(raw, "B", comments["offset"] + 1, 10),
                      changed(raw, "B", comments["offset"] + comments["size"] - 1, 65),
                      section_changed(raw, layout, ".comment", "flags", 0),
                      section_changed(raw, layout, ".comment", "entries", 0)):
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(value, "arm64")


class LoaderElfSymbolAndHashTests(unittest.TestCase):
    def test_symbol_gate_rejects_stripped_input(self):
        raw, _ = fixture(symbols=False)
        with self.assertRaisesRegex(inspector.LoaderElfError, "missing exact ARM64"):
            inspector.inspect_loader(raw, "arm64", require_symbols=True)

    def test_symbol_table_count_entry_size_link_and_local_boundary(self):
        raw, layout = fixture()
        for field, value in (("entries", 16), ("size", 1), ("flags", 2), ("link", 0), ("info", 0), ("info", 3), ("info", 999)):
            with self.subTest(field=field), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(section_changed(raw, layout, ".symtab", field, value), "arm64", require_symbols=True)
        table = layout["sections"][".symtab"]
        with self.assertRaisesRegex(inspector.LoaderElfError, "null symbol"):
            inspector.inspect_loader(changed(raw, "I", table["offset"], 1), "arm64")

    def test_symbol_names_must_be_unique_defined_global_and_default(self):
        raw, layout = fixture()
        start, pokedata = layout["symbols"]["_start"], layout["symbols"]["pokedata_workaround"]
        start_name = struct.unpack_from("<I", raw, start)[0]
        cases = [changed(raw, "I", pokedata, start_name), changed(raw, "H", start + 6, 0),
                 changed(raw, "H", start + 6, 0xFFF1), changed(raw, "H", start + 6, 0xFFFF),
                 changed(raw, "B", start + 4, 0x22), changed(raw, "B", start + 4, 0x11),
                 changed(raw, "B", start + 5, 2), changed(raw, "I", start, 0)]
        for value in cases:
            with self.subTest(prefix=value[:24]), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(value, "arm64", require_symbols=True)

    def test_symbol_values_cannot_fake_entry_or_escape_executable_text(self):
        raw, layout = fixture()
        for name, relative, value in (("_start", 8, layout["entry"] + 4),
                                      ("pokedata_workaround", 8, layout["entry"] + 2),
                                      ("pokedata_workaround", 8, layout["entry"] - 4),
                                      ("pokedata_workaround", 8, layout["entry"] + 28),
                                      ("pokedata_workaround", 8, (1 << 64) - 4),
                                      ("_start", 16, 0x1000)):
            with self.subTest(name=name, value=value), self.assertRaises(inspector.LoaderElfError):
                inspector.inspect_loader(changed(raw, "Q", layout["symbols"][name] + relative, value), "arm64", require_symbols=True)

    def test_every_pokedata_stub_byte_is_checked(self):
        raw, layout = fixture()
        offset = layout["sections"][".text"]["offset"] + 16
        for index in range(len(inspector.POKEDATA_STUB)):
            altered = changed(raw, "B", offset + index, raw[offset + index] ^ 1)
            with self.subTest(byte=index), self.assertRaisesRegex(inspector.LoaderElfError, "instruction bytes changed"):
                inspector.inspect_loader(altered, "arm64", require_symbols=True)

    def test_symbol_identity_is_checked_even_when_symbols_are_optional(self):
        raw, layout = fixture()
        changed_start = changed(raw, "Q", layout["symbols"]["_start"] + 8, layout["entry"] + 4)
        with self.assertRaisesRegex(inspector.LoaderElfError, "does not equal ELF entry"):
            inspector.inspect_loader(changed_start, "arm64")

    def test_load_digest_is_framed_and_text_digest_is_literal_bytes(self):
        raw, layout = fixture()
        result = inspector.inspect_loader(raw, "arm64", require_symbols=True)
        digest = hashlib.sha256(b"FDROID-PROOT-LOAD-V1\0")
        loads = [p for p in layout["programs"] if p[0] == 1]
        digest.update(struct.pack("<I", len(loads)))
        for _, flags, offset, address, size, memory, alignment in loads:
            digest.update(struct.pack("<7Q", flags, offset, address, address, size, memory, alignment))
            digest.update(raw[offset:offset + size])
        self.assertEqual(result["loadableSegmentsSha256"], digest.hexdigest())
        self.assertEqual(result["loadableSegmentsHashFormat"], inspector.LOAD_HASH_FORMAT)
        text = layout["sections"][".text"]
        self.assertEqual(result["textSha256"], hashlib.sha256(raw[text["offset"]:text["offset"] + text["size"]]).hexdigest())
        self.assertEqual(result, inspector.inspect_loader(raw, "arm64", require_symbols=True))

    def test_nonloaded_comment_change_is_separate_from_executable_byte_change(self):
        raw, layout = fixture()
        original = inspector.inspect_loader(raw, "arm64", require_symbols=True)
        comments = inspector.inspect_loader(changed(raw, "B", layout["sections"][".comment"]["offset"] + 1, 66),
                                            "arm64", require_symbols=True)
        self.assertNotEqual(original["compilerComments"], comments["compilerComments"])
        self.assertEqual(original["loadableSegmentsSha256"], comments["loadableSegmentsSha256"])
        self.assertEqual(original["textSha256"], comments["textSha256"])
        text = inspector.inspect_loader(changed(raw, "B", layout["sections"][".text"]["offset"], 0),
                                        "arm64", require_symbols=True)
        self.assertNotEqual(original["loadableSegmentsSha256"], text["loadableSegmentsSha256"])
        self.assertNotEqual(original["textSha256"], text["textSha256"])
        self.assertFalse(text["executed"])

    def test_text_size_is_reported_not_frozen_to_the_historical_payload(self):
        raw, layout = fixture(symbols=False)
        text = layout["sections"][".text"]
        self.assertNotEqual(text["size"], 0x468)
        result = inspector.inspect_loader(raw, "arm64")
        self.assertEqual(next(s for s in result["sections"] if s["name"] == ".text")["size"], text["size"])

    def test_real_stripped_baseline_pins_pass_the_same_structural_parser(self):
        root = TOOLS.parent / "app/src/main/jniLibs/arm64-v8a"
        cases = (("arm64", "libproot-loader.so", "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04", 0x468),
                 ("arm32", "libproot-loader32.so", "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34", 0x5A0))
        for abi, name, expected, text_bytes in cases:
            with self.subTest(abi=abi):
                raw = (root / name).read_bytes()
                self.assertEqual(hashlib.sha256(raw).hexdigest(), expected)
                result = inspector.inspect_loader(raw, abi)
                self.assertEqual(next(s for s in result["sections"] if s["name"] == ".text")["size"], text_bytes)
                self.assertEqual(result["symbols"], {})
                self.assertFalse(result["pokedataStubVerified"])
                self.assertTrue(any("13989888" in comment for comment in result["compilerComments"]))
                self.assertTrue(any("LLD 21.0.0" in comment for comment in result["compilerComments"]))


if __name__ == "__main__":
    unittest.main()
