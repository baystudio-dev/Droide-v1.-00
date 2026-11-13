"""Second-strip data fixtures only: no strip tool, loader or engine execution."""
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
from fdroid_proot_loader_elf import inspect_loader
from test_fdroid_proot_loader_source_elf import changed, fixture, program_changed


def second_strip(raw, abi, *, drop=(".comment",), extra_names=(), table_gap=0):
    """Synthetic metadata serializer, not LLVM or a production transform."""
    report = inspect_loader(raw, abi)
    wide = abi == "arm64"
    unit, shsize = (8, 64) if wide else (4, 40)
    image_end = max(p["offset"] + p["fileBytes"] for p in report["programHeaders"])
    image = bytearray(raw[:image_end])
    sections = [dict(s) for s in report["sections"] if s["name"] not in drop]
    indexes = {s["name"]: i for i, s in enumerate(sections)}
    for s in sections:
        if s["link"]:
            s["link"] = indexes[report["sections"][s["link"]]["name"]]
        if not s["index"] or s["flags"] & 2 or s["name"] == ".shstrtab":
            continue
        content = raw[s["offset"]:s["offset"] + s["size"]]
        alignment = max(1, s["alignment"])
        image.extend(bytes((-len(image)) % alignment))
        s["offset"] = len(image)
        image.extend(content)
    strings, name_offsets = bytearray(b"\0"), {"": 0}
    for name in [s["name"] for s in sections[1:]] + list(extra_names):
        name_offsets[name] = len(strings)
        strings.extend(name.encode("ascii") + b"\0")
    names = sections[indexes[".shstrtab"]]
    names["offset"], names["size"] = len(image), len(strings)
    image.extend(strings)
    image.extend(bytes((-len(image)) % unit + table_gap))
    shoff = len(image)
    for s in sections:
        image.extend(struct.pack("<IIQQQQIIQQ" if wide else "<IIIIIIIIII",
            name_offsets[s["name"]], s["typeId"], s["flags"], s["virtualAddress"], s["offset"],
            s["size"], s["link"], s["info"], s["alignment"], s["entryBytes"]))
    struct.pack_into("<Q" if wide else "<I", image, 40 if wide else 32, shoff)
    struct.pack_into("<HH", image, 60 if wide else 48, len(sections), indexes[".shstrtab"])
    assert len(image) == shoff + len(sections) * shsize
    return bytes(image)


def section_change(raw, abi, name, field, value):
    report = inspect_loader(raw, abi)
    selected = next(s for s in report["sections"] if s["name"] == name)
    offsets = ({"type": ("I", 4), "flags": ("Q", 8), "address": ("Q", 16), "offset": ("Q", 24),
                "size": ("Q", 32), "link": ("I", 40), "info": ("I", 44), "alignment": ("Q", 48), "entries": ("Q", 56)}
               if abi == "arm64" else
               {"type": ("I", 4), "flags": ("I", 8), "address": ("I", 12), "offset": ("I", 16),
                "size": ("I", 20), "link": ("I", 24), "info": ("I", 28), "alignment": ("I", 32), "entries": ("I", 36)})
    fmt, delta = offsets[field]
    position = report["header"]["sectionHeaderOffset"] + selected["index"] * report["header"]["sectionHeaderEntryBytes"]
    return changed(raw, fmt, position + delta, value)


def pair(abi="arm64"):
    source, layout = fixture(abi, symbols=False)
    return source, second_strip(source, abi), layout


def certify(source, wrapped, abi="arm64", offset=16):
    return inspector.certify_loader_wrapper(source, wrapped, abi,
        expected_pokedata_offset=offset if abi == "arm64" else None)


class LoaderWrapperCertificateTests(unittest.TestCase):
    def test_exact_observed_transform_for_both_abis_is_json_safe(self):
        for abi in ("arm64", "arm32"):
            source, wrapped, _ = pair(abi)
            with self.subTest(abi=abi):
                result = certify(source, wrapped, abi)
                self.assertEqual(json.loads(json.dumps(result)), result)
                for field in ("metadataOnlyRewriteVerified", "programHeadersUnchanged", "allocatedSectionsUnchanged",
                              "loadableBytesPreservedExceptSectionTableFields", "retainedNonallocatedPayloadsUnchanged"):
                    self.assertIs(result[field], True)
                self.assertEqual(result["sourceSha256"], hashlib.sha256(source).hexdigest())
                self.assertEqual(result["wrappedSha256"], hashlib.sha256(wrapped).hexdigest())
                self.assertEqual((result["sourceBytes"], result["wrappedBytes"]), (len(source), len(wrapped)))
                self.assertNotEqual(result["sourceSha256"], result["wrappedSha256"])
                self.assertFalse(result["loadableBytesIdentical"])
                self.assertEqual(result["removedSections"], [".comment"])
                self.assertEqual(result["retainedNonallocatedSections"], [] if abi == "arm64" else [".ARM.attributes"])
                self.assertEqual([v["name"] for v in result["changedSectionHeaderFields"]], ["e_shoff", "e_shnum", "e_shstrndx"])
                self.assertFalse(result["executed"] or result["runtimeCompatibilityVerified"])
                self.assertIn("caller must pin", result["scope"])

    def test_actual_pinned_retained_engine_slices_and_external_pair(self):
        root = TOOLS.parent / "app/src/main/jniLibs/arm64-v8a"
        with (root / "libdroide_proot.so").open("rb") as stream:
            engine = stream.read(1024 * 1024 + 1)
        self.assertEqual(len(engine), 265648)
        self.assertEqual(hashlib.sha256(engine).hexdigest(), "6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b")
        cases = [("arm64", "libproot-loader.so", 240140, 17728,
                  "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04",
                  "cb5e5b6900e198ca8160e9d355ea5b98d646333887a769411ff74132c1cec5df"),
                 ("arm32", "libproot-loader32.so", 257868, 5860,
                  "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34",
                  "02c898c049ed1b6af243e4bc8bc8243c772799b7b2d5c9785b790cedf28512b0")]
        for abi, name, position, size, source_hash, wrapped_hash in cases:
            with self.subTest(abi=abi), (root / name).open("rb") as stream:
                source = stream.read(1024 * 1024 + 1)
                self.assertEqual(hashlib.sha256(source).hexdigest(), source_hash)
                wrapped = engine[position:position + size]
                self.assertEqual(hashlib.sha256(wrapped).hexdigest(), wrapped_hash)
                result = certify(source, wrapped, abi, offset=1120)
                self.assertEqual(result["sourceSha256"], source_hash)
                self.assertEqual(result["wrappedSha256"], wrapped_hash)
                self.assertEqual(result["pokedataOffset"], 1120 if abi == "arm64" else None)
                self.assertEqual(result["pokedataFileOffset"], 17504 if abi == "arm64" else None)
                self.assertEqual([v["fileOffset"] for v in result["changedMappedHeaderBytes"]],
                                 [40, 41, 60, 62] if abi == "arm64" else [32, 33, 48, 50])

    def test_exact_bytes_and_abi_input_types_required(self):
        source, wrapped, _ = pair()
        for first, second, abi in ((bytearray(source), wrapped, "arm64"), (source, bytearray(wrapped), "arm64"),
                                   (None, wrapped, "arm64"), (source, wrapped, None), (source, wrapped, []),
                                   (source, wrapped, "aarch64"), (source, wrapped, "arm32"), (source[:10], wrapped, "arm64")):
            with self.subTest(abi=abi), self.assertRaises(inspector.EngineElfError):
                inspector.certify_loader_wrapper(first, second, abi, expected_pokedata_offset=16)

    def test_arm64_requires_nonzero_aligned_nonboolean_source_offset(self):
        source, wrapped, _ = pair()
        for offset in (None, True, False, 0, -4, 15, 1024 * 1024 + 4, "16"):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                inspector.certify_loader_wrapper(source, wrapped, "arm64", expected_pokedata_offset=offset)
        source, wrapped, _ = pair("arm32")
        with self.assertRaisesRegex(inspector.EngineElfError, "ARM32"):
            inspector.certify_loader_wrapper(source, wrapped, "arm32", expected_pokedata_offset=16)

    def test_symbol_removal_is_not_an_allowed_metadata_rewrite(self):
        for abi in ("arm64", "arm32"):
            source, _ = fixture(abi, symbols=True)
            wrapped = second_strip(source, abi, drop=(".comment", ".symtab", ".strtab"))
            self.assertGreater(inspect_loader(source, abi)["symbolCount"], 0)
            self.assertEqual(inspect_loader(wrapped, abi)["symbolCount"], 0)
            with self.subTest(abi=abi), self.assertRaisesRegex(inspector.EngineElfError, "symbols or symbol removal"):
                certify(source, wrapped, abi)

    def test_symbols_in_output_are_not_allowed_either(self):
        for abi in ("arm64", "arm32"):
            source, _, _ = pair(abi)
            symbol_source, _ = fixture(abi, symbols=True)
            wrapped = second_strip(symbol_source, abi)
            self.assertGreater(inspect_loader(wrapped, abi)["symbolCount"], 0)
            with self.subTest(abi=abi), self.assertRaisesRegex(inspector.EngineElfError, "symbols or symbol removal"):
                certify(source, wrapped, abi)

    def test_comment_must_be_removed_not_retained_or_replaced(self):
        source, _, layout = pair()
        comment = layout["sections"][".comment"]
        for wrapped in (source, changed(source, "B", comment["offset"] + 1, ord("Z"))):
            inspect_loader(wrapped, "arm64")
            with self.assertRaisesRegex(inspector.EngineElfError, "exactly the observed"):
                certify(source, wrapped)

    def test_unknown_section_replacement_or_extra_loss_fails(self):
        source, wrapped, _ = pair()
        unknown = source.replace(b".comment\0", b".unknown\0")
        inspect_loader(unknown, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "exactly the observed"):
            certify(unknown, wrapped)
        source, _, _ = pair("arm32")
        wrapped = second_strip(source, "arm32", drop=(".comment", ".ARM.attributes"))
        inspect_loader(wrapped, "arm32")
        with self.assertRaisesRegex(inspector.EngineElfError, "exactly the observed"):
            certify(source, wrapped, "arm32")

    def test_changed_text_bytes_fail_even_with_valid_elf_layout(self):
        for abi in ("arm64", "arm32"):
            source, wrapped, layout = pair(abi)
            altered = changed(wrapped, "B", layout["sections"][".text"]["offset"], 255)
            inspect_loader(altered, abi)
            with self.subTest(abi=abi), self.assertRaisesRegex(inspector.EngineElfError, "mapped payload"):
                certify(source, altered, abi)

    def test_changed_arm_exception_index_bytes_fail(self):
        source, wrapped, layout = pair("arm32")
        altered = changed(wrapped, "B", layout["sections"][".ARM.exidx"]["offset"], 1)
        inspect_loader(altered, "arm32")
        with self.assertRaisesRegex(inspector.EngineElfError, "mapped payload"):
            certify(source, altered, "arm32")

    def test_retained_nonallocated_attributes_payload_cannot_change(self):
        source, wrapped, _ = pair("arm32")
        attributes = next(s for s in inspect_loader(wrapped, "arm32")["sections"] if s["name"] == ".ARM.attributes")
        altered = changed(wrapped, "B", attributes["offset"], ord("B"))
        inspect_loader(altered, "arm32")
        with self.assertRaisesRegex(inspector.EngineElfError, "retained section shape or payload"):
            certify(source, altered, "arm32")

    def test_retained_nonallocated_attributes_shape_cannot_change(self):
        source, wrapped, _ = pair("arm32")
        for field, value in (("entries", 1), ("type", 1), ("alignment", 2)):
            altered = section_change(wrapped, "arm32", ".ARM.attributes", field, value)
            inspect_loader(altered, "arm32")
            with self.subTest(field=field), self.assertRaisesRegex(inspector.EngineElfError, "retained section shape or payload"):
                certify(source, altered, "arm32")

    def test_program_mappings_must_match_including_nonload_headers(self):
        for abi in ("arm64", "arm32"):
            source, wrapped, layout = pair(abi)
            stack_index = 3 if abi == "arm64" else 4
            altered = program_changed(wrapped, layout, stack_index, "alignment", 1)
            inspect_loader(altered, abi)
            with self.subTest(abi=abi), self.assertRaisesRegex(inspector.EngineElfError, "program headers"):
                certify(source, altered, abi)

    def test_unsupported_mapped_header_fields_are_never_ignored(self):
        source, wrapped, _ = pair()
        for fmt, offset, value in (("B", 7, 3), ("Q", 24, 0x2000000004), ("I", 48, 1),
                                   ("H", 52, 63), ("H", 54, 55), ("H", 58, 63)):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                certify(source, changed(wrapped, fmt, offset, value))

    def test_unallocated_gap_bytes_before_last_load_must_also_match(self):
        source, wrapped, _ = pair()
        altered = changed(wrapped, "B", 1024, 1)
        inspect_loader(altered, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "pre-metadata file bytes"):
            certify(source, altered)

    def test_allocated_section_descriptor_change_is_not_just_metadata(self):
        source, wrapped, _ = pair()
        altered = section_change(wrapped, "arm64", ".text", "size", 28)
        inspect_loader(altered, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "retained section shape or payload"):
            certify(source, altered)

    def test_trailing_bytes_are_rejected_on_either_side(self):
        source, wrapped, _ = pair()
        for first, second in ((source + b"\0", wrapped), (source, wrapped + b"\0"), (source, wrapped + b"hidden")):
            inspect_loader(first, "arm64")
            inspect_loader(second, "arm64")
            with self.assertRaisesRegex(inspector.EngineElfError, "trailing/unaccounted"):
                certify(first, second)

    def test_extra_zero_metadata_gap_is_rejected(self):
        source, _, _ = pair()
        wrapped = second_strip(source, "arm64", table_gap=8)
        inspect_loader(wrapped, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "unaccounted bytes before"):
            certify(source, wrapped)

    def test_nonzero_section_table_alignment_padding_is_rejected(self):
        source, wrapped, _ = pair()
        report = inspect_loader(wrapped, "arm64")
        strings = report["sections"][-1]
        position = strings["offset"] + strings["size"]
        self.assertLess(position, report["header"]["sectionHeaderOffset"])
        altered = changed(wrapped, "B", position, 1)
        inspect_loader(altered, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "unaccounted bytes before"):
            certify(source, altered)

    def test_unused_section_names_are_rejected(self):
        source, _, _ = pair()
        wrapped = second_strip(source, "arm64", extra_names=(".unused",))
        inspect_loader(wrapped, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "unused, duplicate, or missing"):
            certify(source, wrapped)

    def test_same_corrupted_stub_on_both_sides_still_fails(self):
        source, _, layout = pair()
        position = layout["sections"][".text"]["offset"] + 16
        source = changed(source, "B", position, 0)
        wrapped = second_strip(source, "arm64")
        with self.assertRaisesRegex(inspector.EngineElfError, "exact source-proved pokedata stub"):
            certify(source, wrapped)

    def test_wrong_or_outside_source_offset_fails_without_scanning_for_any_stub(self):
        source, wrapped, _ = pair()
        for offset in (4, 20, 32, 1024 * 1024):
            with self.subTest(offset=offset), self.assertRaises(inspector.EngineElfError):
                certify(source, wrapped, offset=offset)


if __name__ == "__main__":
    unittest.main()
