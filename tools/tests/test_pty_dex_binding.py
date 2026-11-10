#!/usr/bin/env python3
"""Parser mutations plus optional pinned REAL D8 integration (no downloads)."""
from __future__ import annotations
import hashlib
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile
import zlib

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import verify_pty_dex_binding as binding


def uleb(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def fix_checksums(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def fixture(*, context=False, words=None, flags=9, constant=16, catch_type=None,
            catchall=False, duplicate_class=False, literal='hello', debug=False, annotation_value=None, class_access=0x10):
    """Tiny standard DEX; reference operands supplied by a pool-aware callback."""
    target = binding.TARGET
    types = sorted([target, 'Ljava/lang/Object;', 'Ljava/lang/Throwable;',
                    'Ljava/lang/IllegalStateException;', 'I', 'V'] + (['Laaa/Context;'] if context else []))
    strings = sorted(set(types + ['run', 'CONST', 'other', '<init>', 'V', 'hello', 'world', 'Fixture.java'] +
                         (['AAA', 'Unrelated.java'] if context else [])))
    si, ti = {s: i for i, s in enumerate(strings)}, {s: i for i, s in enumerate(types)}
    methods = sorted([(ti['Ljava/lang/Object;'], 0, si['<init>']), (ti[target], 0, si['run'])])
    method_index = methods.index((ti[target], 0, si['run']))
    pools = {'strings': si, 'types': ti, 'methods': methods, 'run': method_index, 'field': 0}
    words = words(pools) if callable(words) else (words or [0x001a, si[literal], 0x000e])
    defs = 2 if duplicate_class else 1
    ns, nt, np, nf, nm = len(strings), len(types), 1, 2, len(methods)
    positions = {}
    pos = 112
    for kind, count, width in ((1, ns, 4), (2, nt, 4), (3, np, 12), (4, nf, 8), (5, nm, 8), (6, defs, 32)):
        positions[kind] = pos
        pos += count * width
    data_off = (pos + 3) & ~3
    data = bytearray(data_off)
    maps = [(0, 1, 0)] + [(k, c, positions[k]) for k, c in ((1, ns), (2, nt), (3, np), (4, nf), (5, nm), (6, defs))]
    string_off = len(data)
    string_offsets = []
    for s in strings:
        string_offsets.append(len(data))
        data += uleb(len(s)) + s.encode() + b'\0'
    maps.append((0x2002, ns, string_off))
    debug_off = 0
    if debug:
        debug_off = len(data)
        data += b'\x01\x00\x07\x00'
        maps.append((0x2003, 1, debug_off))
    while len(data) % 4:
        data.append(0)
    code_off = len(data)
    tries = 1 if catch_type or catchall else 0
    data += struct.pack('<4H2I', 4, 0, 2, tries, debug_off, len(words))
    data += struct.pack('<' + str(len(words)) + 'H', *words)
    if tries:
        if len(words) % 2:
            data += b'\0\0'
        data += struct.pack('<IHH', 0, 2, 1)
        handler = b'\x00' + uleb(len(words) - 1) if catchall else b'\x01' + uleb(ti[catch_type]) + uleb(len(words) - 1)
        data += b'\x01' + handler
    maps.append((0x2001, 1, code_off))
    class_data_off = len(data)
    data += b'\x01\x00\x01\x00' + b'\x00\x18' + uleb(method_index) + uleb(flags) + uleb(code_off)
    maps.append((0x2000, 1, class_data_off))
    static_off = len(data)
    data += b'\x01\x04' + bytes([constant])
    maps.append((0x2005, 1, static_off))
    annotations_off = 0
    if annotation_value:
        annotation_off = len(data)
        data += b'\x01' + uleb(ti['Ljava/lang/Object;']) + b'\x01' + uleb(si['hello']) + b'\x17' + bytes([si[annotation_value]])
        maps.append((0x2004, 1, annotation_off))
        while len(data) % 4:
            data.append(0)
        set_off = len(data)
        data += struct.pack('<II', 1, annotation_off)
        maps.append((0x1003, 1, set_off))
        annotations_off = len(data)
        data += struct.pack('<4I', set_off, 0, 0, 0)
        maps.append((0x2006, 1, annotations_off))
    while len(data) % 4:
        data.append(0)
    map_off = len(data)
    maps.append((0x1000, 1, map_off))
    data += struct.pack('<I', len(maps))
    for kind, count, off in maps:
        data += struct.pack('<HHII', kind, 0, count, off)
    data[:8] = b'dex\n039\0'
    struct.pack_into('<6I', data, 32, len(data), 112, 0x12345678, 0, 0, map_off)
    for kind, count in ((1, ns), (2, nt), (3, np), (4, nf), (5, nm), (6, defs)):
        struct.pack_into('<II', data, 56 + (kind - 1) * 8, count, positions[kind])
    struct.pack_into('<II', data, 104, len(data) - data_off, data_off)
    for i, off in enumerate(string_offsets):
        struct.pack_into('<I', data, positions[1] + i * 4, off)
    for i, typ in enumerate(types):
        struct.pack_into('<I', data, positions[2] + i * 4, si[typ])
    struct.pack_into('<III', data, positions[3], si['V'], ti['V'], 0)
    struct.pack_into('<HHI', data, positions[4], ti[target], ti['I'], si['CONST'])
    struct.pack_into('<HHI', data, positions[4] + 8, ti['Ljava/lang/Object;'], ti['I'], si['other'])
    for i, values in enumerate(methods):
        struct.pack_into('<HHI', data, positions[5] + i * 8, *values)
    for i in range(defs):
        struct.pack_into('<8I', data, positions[6] + i * 32, ti[target], class_access, ti['Ljava/lang/Object;'],
                         0, si['Fixture.java'], annotations_off, class_data_off, static_off)
    return fix_checksums(data)


def zip_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w') as archive:
        for name, content in entries:
            archive.writestr(name, content)
    return stream.getvalue()


class ParserTests(unittest.TestCase):
    def compare(self, a, b):
        return binding.compare_dex_entries({'classes.dex': a}, {'classes.dex': b})

    def test_full_class_equal_despite_pool_and_file_relocation(self):
        self.assertNotEqual(fixture(), fixture(context=True))
        self.assertTrue(self.compare(fixture(), fixture(context=True))['registerExactFullCodeMatch'])

    def test_stale_body_and_literal_register_mutations_rejected(self):
        baseline = fixture(words=[0x0012, 0x000e])
        for mutated in ([0x1012, 0x000e], [0x0112, 0x000e], [0x0012, 0x0000, 0x000e]):
            with self.subTest(mutated=mutated), self.assertRaises(binding.BindingError):
                self.compare(baseline, fixture(words=mutated))

    def test_reference_string_type_field_method_changes_rejected(self):
        examples = [
            (lambda p: [0x001a, p['strings']['hello'], 0x000e], lambda p: [0x001a, p['strings']['world'], 0x000e]),
            (lambda p: [0x001c, p['types']['Ljava/lang/Object;'], 0x000e], lambda p: [0x001c, p['types']['Ljava/lang/Throwable;'], 0x000e]),
            (lambda p: [0x0060, 0, 0x000e], lambda p: [0x0060, 1, 0x000e]),
            (lambda p: [0x0071, 0, 0, 0x000e], lambda p: [0x0071, 1, 0, 0x000e]),
        ]
        for one, two in examples:
            with self.subTest(one=one), self.assertRaises(binding.BindingError):
                self.compare(fixture(words=one), fixture(words=two))

        for one, _ in examples[:2]:
            self.compare(fixture(words=one), fixture(words=one, context=True))

    def test_access_flags_and_static_constants_bound(self):
        for changed in (fixture(flags=1), fixture(constant=17), fixture(class_access=0x11)):
            with self.assertRaises(binding.BindingError):
                self.compare(fixture(), changed)

    def test_annotations_bound_and_resolved(self):
        self.compare(fixture(annotation_value='hello'), fixture(annotation_value='hello', context=True))
        with self.assertRaises(binding.BindingError):
            self.compare(fixture(annotation_value='hello'), fixture(annotation_value='world'))
        with self.assertRaises(binding.BindingError):
            self.compare(fixture(annotation_value='hello'), fixture())

    def test_malformed_debug_not_silently_ignored(self):
        data = bytearray(fixture(debug=True))
        dex = binding.Dex(bytes(data))
        offset = dex.maps[0x2003][1]
        data[offset + 3] = 1
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fix_checksums(data))

    def test_class_uniqueness_within_and_across_dex(self):
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fixture(duplicate_class=True))
        with self.assertRaises(binding.BindingError):
            binding.target_from_dexes({'classes.dex': fixture(), 'classes2.dex': fixture()})

    def test_duplicate_type_ids_rejected(self):
        data = bytearray(fixture())
        off = struct.unpack_from('<I', data, 68)[0]
        data[off + 4:off + 8] = data[off:off + 4]
        with self.assertRaisesRegex(binding.BindingError, 'duplicate type'):
            binding.canonical_class(fix_checksums(data))

    def test_unsorted_id_tables_rejected(self):
        for header, width in ((60, 4), (68, 4), (84, 8), (92, 8)):
            data = bytearray(fixture())
            off = struct.unpack_from('<I', data, header)[0]
            first = bytes(data[off:off + width])
            second = bytes(data[off + width:off + 2 * width])
            data[off:off + width] = second
            data[off + width:off + 2 * width] = first
            with self.subTest(header=header), self.assertRaises(binding.BindingError):
                binding.canonical_class(fix_checksums(data))

    def test_typed_and_catchall_handler_content_bound(self):
        first = fixture(catch_type='Ljava/lang/Throwable;')
        self.compare(first, fixture(catch_type='Ljava/lang/Throwable;', context=True))
        for second in (fixture(catch_type='Ljava/lang/IllegalStateException;'), fixture(catchall=True), fixture()):
            with self.assertRaises(binding.BindingError):
                self.compare(first, second)

    def test_catch_target_and_nonboundary_handler_rejected(self):
        data = bytearray(fixture(catch_type='Ljava/lang/Throwable;'))
        dex = binding.Dex(bytes(data))
        code = dex.maps[0x2001][1]

        struct.pack_into('<H', data, code + 16 + 6 + 2 + 6, 2)
        with self.assertRaisesRegex(binding.BindingError, 'handler offset'):
            binding.canonical_class(fix_checksums(data))

    def test_branch_targets_and_encoding_bound(self):
        self.compare(fixture(words=[0x0228, 0, 0x000e]), fixture(words=[0x0228, 0, 0x000e]))
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fixture(words=[0x0228, 0x0013, 0, 0x000e]))
        with self.assertRaises(binding.BindingError):
            self.compare(fixture(words=[0x0228, 0, 0x000e]), fixture(words=[0x0128, 0, 0x000e]))

    def test_switch_payloads_and_array_payloads_bound(self):

        packed = [0x002b, 4, 0, 0x000e, 0x0100, 1, 7, 0, 3, 0]
        sparse = [0x002c, 4, 0, 0x000e, 0x0200, 1, 7, 0, 3, 0]
        array = [0x0026, 4, 0, 0x000e, 0x0300, 1, 3, 0, 0x0201, 0x0003]
        for words in (packed, sparse, array):
            self.compare(fixture(words=words), fixture(words=words, context=True))
            changed = words.copy()
            changed[-2] ^= 1
            with self.assertRaises(binding.BindingError):
                self.compare(fixture(words=words), fixture(words=changed))
            with self.assertRaises(binding.BindingError):
                binding.canonical_class(fixture(words=words[:-1]))
        changed = packed.copy(); changed[1] = 3
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fixture(words=changed))
        changed = sparse.copy(); changed[-2] = 1
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fixture(words=changed))

    def test_unsupported_opcodes_and_truncation_reject(self):
        for op in (0x3e, 0x73, 0x79, 0xe3, 0xfc, 0xfd, 0xfe):
            with self.subTest(op=op), self.assertRaisesRegex(binding.BindingError, 'unsupported opcode'):
                binding.canonical_class(fixture(words=[op, 0x000e]))
        for words in ([0x0018, 0], [0x0100], [0x0071], [0x00fa, 0, 0]):
            with self.subTest(words=words), self.assertRaises(binding.BindingError):
                binding.canonical_class(fixture(words=words))

    def test_dual_method_prototype_operand_is_resolved(self):
        words = lambda p: [0x00fa, p['run'], 0, 0, 0x000e]
        self.compare(fixture(words=words), fixture(words=words, context=True))
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(fixture(words=[0x00fa, 0, 0, 1, 0x000e]))

    def test_bad_checksums_headers_and_all_truncations(self):
        data = fixture()
        for length in range(len(data)):
            with self.subTest(length=length), self.assertRaises(binding.BindingError):
                binding.canonical_class(data[:length])
        for off, value in ((7, 1), (36, 0), (40, 0), (52, 1), (108, 0)):
            mutated = bytearray(data); mutated[off] = value
            with self.subTest(off=off), self.assertRaises(binding.BindingError):
                binding.canonical_class(fix_checksums(mutated))
        mutated = bytearray(data); mutated[-1] ^= 1
        with self.assertRaises(binding.BindingError):
            binding.canonical_class(bytes(mutated))

    def test_debug_layout_is_ignored(self):
        self.compare(fixture(), fixture(debug=True))

    def test_archive_ambiguity_and_hidden_dex_paths(self):
        for entries in ([('classes.dex', fixture()), ('classes.dex', fixture())],
                        [('../classes.dex', fixture())], [('/classes.dex', fixture())]):
            with self.assertRaises(binding.BindingError):
                binding.checked_archive(zip_bytes(entries))
        with self.assertRaises(binding.BindingError):
            binding.target_from_dexes({'classes.dex': fixture(), 'hidden/classes.dex': fixture()})

if __name__ == '__main__':
    unittest.main()
