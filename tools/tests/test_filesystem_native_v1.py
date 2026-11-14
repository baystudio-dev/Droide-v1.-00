"""Admission parser tests: no SDK, compiler, Android runtime or native execution."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

MODULE = Path(__file__).resolve().parents[1] / 'filesystem_native_v1.py'
spec = importlib.util.spec_from_file_location('filesystem_native_v1', MODULE)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def elf_fixture(exports=None, machine=183, alignment=16384, stack=6, relro=True, dynamic_extra=(), undefined=(), api=29):
    """Minimal ELF64 shared object with real binary tables (never executable)."""
    names = [m.PREFIX + 'probe'] if exports is None else exports
    strings = bytearray(b'\0')
    def add(name):
        offset = len(strings); strings.extend(name.encode() + b'\0'); return offset
    symbols = bytearray(24)
    for name in names:
        symbols.extend(struct.pack('<IBBHQQ', add(name), 0x12, 0, 4, 320, 1))
    for name in undefined:
        symbols.extend(struct.pack('<IBBHQQ', add(name), 0x12, 0, 0, 0, 0))
    tags = [(1, add(n)) for n in ('libc.so', 'libm.so', 'libdl.so')]
    tags += [(14, add('libdroide_files.so')), (30, 8)]
    tags += [(5, 1024), (10, len(strings)), (6, 1536), (11, 24), *dynamic_extra, (0, 0)]
    dynamics = b''.join(struct.pack('<qQ', *entry) for entry in tags)
    data = bytearray(4096)
    data[:16] = b'\x7fELF\x02\x01\x01' + bytes(9)
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 0, 64, 512, 0, 64, 56, 4, 64, 6, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    struct.pack_into('<IIQQQQQQ', data, 120, 0x6474e551, stack, 0, 0, 0, 0, 0, 16)
    struct.pack_into('<IIQQQQQQ', data, 176, 0x6474e552 if relro else 0, 4, 256, 256, 0, 64, 64, 1)
    struct.pack_into('<IIQQQQQQ', data, 232, 2, 4, 2048, 2048, 0, len(dynamics), len(dynamics), 8)
    struct.pack_into('<IIQQQQIIQQ', data, 512 + 4*64, 0, 1, 6, 320, 320, 64, 0, 0, 16, 0)
    for index, kind, offset, size, link, entry in [(1, 3, 1024, len(strings), 0, 0), (2, 11, 1536, len(symbols), 1, 24), (3, 6, 2048, len(dynamics), 1, 16)]:
        struct.pack_into('<IIQQQQIIQQ', data, 512 + index * 64, 0, kind, 2, offset, offset, size, link, 0, 8, entry)
    descriptor = struct.pack('<I', api) + b'r28c'.ljust(64, b'\0') + b'13676358'.ljust(64, b'\0')
    note = struct.pack('<III', 8, len(descriptor), 1) + b'Android\0' + descriptor
    struct.pack_into('<IIQQQQIIQQ', data, 512 + 5*64, 0, 7, 2, 2560, 2560, len(note), 0, 0, 4, 0)
    data[2560:2560+len(note)] = note
    data[1024:1024+len(strings)] = strings
    data[1536:1536+len(symbols)] = symbols
    data[2048:2048+len(dynamics)] = dynamics
    return bytes(data)


LEGACY_SEVENTEEN_METHODS = {'changedTimeDescriptor': '(I)[J', 'closeDescriptor': '(I)I', 'createDirectoryRelative': '(I[B)J', 'createOrdinaryRelative': '(I[B)J', 'duplicateDescriptor': '(I)I', 'forceDirectory': '(I)I', 'forceFileDescriptor': '(II)I', 'kindRelative': '(I[B)I', 'moveNoReplace': '([B[B)I', 'moveNoReplaceAnchored': '(I[BI[B)I', 'moveNoReplaceAnchoredDurable': '(I[BI[B)J', 'openDirectoryRelative': '(I[B)I', 'openDirectoryRoot': '([B)I', 'openOrdinaryRelative': '(I[B)I', 'readDescriptor': '(I[BII)I', 'renameNoReplace': '([B[B)I', 'writeDescriptor': '(I[BII)I'}


class ContractTests(unittest.TestCase):
    def test_current_kotlin_matches_eighteen_methods_preserving_legacy_abi(self):
        contract = m.load(m.CONTRACT)
        root = MODULE.parents[1]
        self.assertEqual(17, len(LEGACY_SEVENTEEN_METHODS))
        self.assertEqual({**LEGACY_SEVENTEEN_METHODS, 'exportDescriptorLock': '(IZ)I'}, contract['methods'])
        self.assertEqual(18, len(contract['methods']))
        self.assertEqual('989ce467c3daae57246377c0915cdab985ac3e6146ee4ec9809ed2045eab8504', contract['sourceArchiveSha256'])
        self.assertEqual(contract['methods'], m.kotlin_contract((root / contract['kotlin']).read_text()))
        for relative, expected in contract['sourceSha256'].items():
            self.assertEqual(expected, m.sha(root / relative))

    def test_header_checks_arrays_and_long_returns(self):
        value = m.header({'read': '(I[BII)I', 'stamp': '(I)[J', 'move': '(I[BI[B)J'})
        self.assertIn('jint, jbyteArray, jint, jint', value)
        self.assertIn('JNIEXPORT jlongArray', value)
        self.assertIn('JNIEXPORT jlong JNICALL', value)

    def test_duplicate_declaration_rejected(self):
        with self.assertRaises(m.Rejected):
            m.kotlin_contract('@JvmStatic external fun x(): Int\n@JvmStatic external fun x(): Int')

    def test_duplicate_json_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'x.json'; path.write_text('{"a":1,"a":2}')
            with self.assertRaises(m.Rejected): m.load(path)

    def test_symlink_and_overlapping_roots_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'real').mkdir(); (root / 'link').symlink_to(root / 'real')
            with self.assertRaises(m.Rejected): m.no_links(root / 'link' / 'child')
            self.assertFalse(m.disjoint(root, root / 'child'))
            self.assertFalse(m.disjoint(root, root))

    def test_toolchain_external_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'tree'; root.mkdir(); (root / 'escape').symlink_to('/etc/passwd')
            with self.assertRaises(m.Rejected): m.snapshot(root)

    def test_toolchain_internal_alias_is_measured(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root / 'tool').write_text('pinned'); (root / 'alias').symlink_to('tool')
            self.assertEqual({'link': 'tool'}, m.snapshot(root)['alias'])

    def test_changed_source_pin_is_rejected_before_toolchain_use(self):
        contract = m.load(m.CONTRACT)
        contract['sourceSha256'] = {'tools/filesystem_native_v1.py': '0' * 64}
        with self.assertRaisesRegex(m.Rejected, 'source pin mismatch'):
            m.inputs(MODULE.parents[1], Path('/unused-sdk'), contract)

    def test_tampered_receipt_status_is_not_admitted(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); output = directory / 'output'; output.mkdir()
            m.save(output / 'inputs-before.json', {'frozen': True})
            m.save(output / 'receipt.json', {'status': 'RELEASE_READY'})
            with mock.patch.object(m, 'inputs', return_value=({}, {'frozen': True})), mock.patch.object(m, 'evidence', return_value={}):
                with self.assertRaisesRegex(m.Rejected, 'receipt differs'):
                    m.execute(MODULE.parents[1], directory / 'sdk', output, verify=True)

    def test_shell_metacharacter_paths_are_rejected_before_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            for character in ("$", "`", "'", '"', ';', '&', '|', '(', ')', ' ', '\\'):
                output = directory / ('unsafe' + character + 'path')
                with self.subTest(character=character), self.assertRaisesRegex(m.Rejected, 'path characters'):
                    m.execute(MODULE.parents[1], directory / 'sdk', output)
                self.assertFalse(output.exists())

    def test_existing_build_directory_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); output = directory / 'output'; output.mkdir()
            (output / 'owned').write_text('preserve')
            with self.assertRaisesRegex(m.Rejected, 'already exists'):
                m.execute(MODULE.parents[1], directory / 'sdk', output)
            self.assertEqual('preserve', (output / 'owned').read_text())


class ElfTests(unittest.TestCase):
    def check(self, data):
        return m.inspect_elf(data, 'arm64-v8a', {'probe': '()I'})

    def test_valid_fixture(self):
        self.assertEqual([m.PREFIX + 'probe'], self.check(elf_fixture())['exports'])

    def test_only_reviewed_fortify_open_wrappers_are_accepted(self):
        result = self.check(elf_fixture(undefined=['__open_2', '__openat_2']))
        self.assertEqual(['__open_2', '__openat_2'], result['undefined'])
        for name in ('__open_3', '__openat_3', '__open_2_custom', 'custom_application_symbol'):
            with self.subTest(name=name), self.assertRaises(m.Rejected):
                self.check(elf_fixture(undefined=[name]))

    def test_only_exact_reviewed_flock_import_is_accepted(self):
        result = self.check(elf_fixture(undefined=['fcntl', 'flock', 'close']))
        self.assertEqual(['close', 'fcntl', 'flock'], result['undefined'])
        for name in ('flock64', 'flock_custom', '__flock', 'FLOCK',
                     'flock@LIBC', 'dangerous_application_symbol'):
            with self.subTest(name=name), self.assertRaisesRegex(m.Rejected, 'unexpected undefined dynamic symbol'):
                self.check(elf_fixture(undefined=['flock', name]))

    def test_rejects_invalid_variants(self):
        variants = {
            'missing export': elf_fixture(exports=[]),
            'extra export': elf_fixture(exports=[m.PREFIX+'probe', 'surprise']),
            'wrong ABI': elf_fixture(machine=62),
            'wrong Android API': elf_fixture(api=28),
            '4KiB alignment': elf_fixture(alignment=4096),
            'executable stack': elf_fixture(stack=7),
            'missing RELRO': elf_fixture(relro=False),
            'RPATH': elf_fixture(dynamic_extra=[(15, 0)]),
            'RUNPATH': elf_fixture(dynamic_extra=[(29, 0)]),
            'TEXTREL': elf_fixture(dynamic_extra=[(22, 0)]),
            'unexpected import': elf_fixture(undefined=['dangerous_application_symbol']),
            'truncated': elf_fixture()[:1700],
        }
        for label, data in variants.items():
            with self.subTest(label=label), self.assertRaises(m.Rejected): self.check(data)

    def test_no_bind_now_rejected(self):
        data = bytearray(elf_fixture())
        # 3 NEEDED + SONAME precede FLAGS.
        struct.pack_into('<qQ', data, 2048 + 4*16, 30, 0)
        with self.assertRaises(m.Rejected): self.check(data)

    def test_loader_decoys_are_rejected(self):
        mutations = {}
        missing = bytearray(elf_fixture()); struct.pack_into('<I', missing, 232, 0)
        mutations['missing PT_DYNAMIC'] = missing
        wrong_segment = bytearray(elf_fixture()); struct.pack_into('<Q', wrong_segment, 232+8, 2100)
        mutations['decoy dynamic section'] = wrong_segment
        wrong_strtab = bytearray(elf_fixture()); struct.pack_into('<Q', wrong_strtab, 2048+5*16+8, 1100)
        mutations['loader STRTAB differs'] = wrong_strtab
        wrong_symtab = bytearray(elf_fixture()); struct.pack_into('<Q', wrong_symtab, 2048+7*16+8, 1600)
        mutations['loader SYMTAB differs'] = wrong_symtab
        export_outside = bytearray(elf_fixture()); struct.pack_into('<Q', export_outside, 1536+24+8, 5000)
        mutations['export outside LOAD'] = export_outside
        export_data = bytearray(elf_fixture()); struct.pack_into('<I', export_data, 64+4, 4)
        mutations['export not executable'] = export_data
        section_unmapped = bytearray(elf_fixture()); struct.pack_into('<Q', section_unmapped, 512+2*64+16, 1700)
        mutations['dynsym section decoy'] = section_unmapped
        for label, data in mutations.items():
            with self.subTest(label=label), self.assertRaises(m.Rejected): self.check(bytes(data))

    def test_bad_string_bounds_rejected(self):
        data = bytearray(elf_fixture()); struct.pack_into('<I', data, 1536+24, 999999)
        with self.assertRaises(m.Rejected): self.check(data)


if __name__ == '__main__':
    unittest.main()
