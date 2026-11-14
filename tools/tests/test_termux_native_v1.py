"""Readonly native-byte parser controls; no compiler, SDK or native execution."""
import copy
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import filesystem_native_v1 as common
import termux_native_elf as elfmod
import termux_native_v1 as lane

ROOT = TOOLS.parent
CONTRACT = common.load(lane.CONTRACT)


def fixture(abi='arm64-v8a'):
    path = ROOT / 'third_party/termux/native/private-debug/primary' / abi / 'libtermux.so'
    common.require(common.sha(path) == CONTRACT['historicalParserFixtureSha256ByAbi'][abi], 'parser fixture changed')
    return path.read_bytes()


class SourceAndRecipeTests(unittest.TestCase):
    def test_current_source_and_twelve_prototypes(self):
        self.assertEqual(lane.CONTRACT_SHA, common.sha(lane.CONTRACT))
        lane.source_contract(ROOT, CONTRACT)
        output = lane.header(CONTRACT['methods'])
        self.assertEqual(12, output.count('static '))
        self.assertIn('jlong, jstring, jstring, jobjectArray, jobjectArray, jint, jint', output)
        self.assertIn('static void JNICALL cancel_owned_pty_io', output)
        self.assertIn('JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *);', output)

    def test_header_rejects_unknown_descriptors(self):
        for descriptor in ('(V)I', '(Q)I', '(I)Igarbage', 'I', '(I'):
            with self.subTest(descriptor=descriptor), self.assertRaises(common.Rejected):
                lane.header([dict(name='test', function='test', descriptor=descriptor)])

    def test_changed_source_pin_is_rejected(self):
        bad = copy.deepcopy(CONTRACT)
        bad['sourceSha256']['third_party/termux/native/termux.c'] = '0'*64
        with self.assertRaisesRegex(common.Rejected, 'source pin'):
            lane.source_contract(ROOT, bad)

    def test_helpers_are_fixed_and_executed_from_selected_root(self):
        lane.verify_helpers(ROOT, CONTRACT)
        bad = copy.deepcopy(CONTRACT)
        bad['helperSha256']['tools/termux_native_elf.py'] = '0' * 64
        with self.assertRaisesRegex(common.Rejected, 'fixed helper identity'):
            lane.verify_helpers(ROOT, bad)
        with mock.patch.object(elfmod, '__file__', '/unrelated/termux_native_elf.py'):
            with self.assertRaisesRegex(common.Rejected, 'outside selected'):
                lane.verify_helpers(ROOT, CONTRACT)

    def test_modified_versioned_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            changed = directory / 'contract.json'
            common.save(changed, {'schema': 'termux-native-v1'})
            with mock.patch.object(lane, 'CONTRACT', changed):
                with self.assertRaisesRegex(common.Rejected, 'versioned Termux contract'):
                    lane.execute(ROOT, directory / 'sdk', directory / 'new-output')

    def test_native_recipe_only_requests_termux(self):
        tools = {name: Path('/toolchain/bin') / name for name in ('cmake', 'ninja', 'clang', 'llvm-objdump')}
        commands = lane.commands(Path('/source'), Path('/sdk'), Path('/output'), 'primary', 'arm64-v8a', tools)
        self.assertEqual('termux', commands[1][commands[1].index('--target')+1])
        self.assertIn('-DANDROID_PLATFORM=android-29', commands[0])
        self.assertIn('--disassemble-symbols=JNI_OnLoad', commands[2])
        self.assertNotIn('droide_files', commands[1])

    def test_output_cannot_overwrite_or_use_metacharacters(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            existing = directory/'existing'; existing.mkdir()
            with self.assertRaises(common.Rejected): lane.execute(ROOT, directory/'sdk', existing)
            for suffix in ('$x', "'x", '`x', ';x', ' x'):
                with self.subTest(suffix=suffix), self.assertRaises(common.Rejected):
                    lane.execute(ROOT, directory/'sdk', directory/suffix)

    def test_receipt_cannot_claim_registration_or_apk_admission(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp); output = directory/'output'; output.mkdir()
            common.save(output/'inputs-before.json', {'frozen':True})
            common.save(output/'receipt.json', {'registrationPathVerified':True, 'apkAdmitted':True})
            with mock.patch.object(lane, 'inputs', return_value=({}, {'frozen':True})), mock.patch.object(lane, 'artifacts', return_value={}):
                with self.assertRaisesRegex(common.Rejected, 'receipt does not match'):
                    lane.execute(ROOT,directory/'sdk',output,verify=True)


class RegistrationTableTests(unittest.TestCase):
    def test_all_four_historical_parser_controls(self):
        # Readonly structure controls, not fresh compilation or new historical admission.
        for abi in common.ABIS:
            with self.subTest(abi=abi):
                report = elfmod.inspect(fixture(abi),abi,CONTRACT)
                self.assertEqual(12,len(report['registrationTable']))
                self.assertEqual(['JNI_OnLoad'],report['exports'])
                self.assertFalse(report['registrationPathVerified'])
                self.assertFalse(report['finalJavaBindingVerified'])

    def test_changed_function_pointer_is_rejected(self):
        data = bytearray(fixture()); elf = elfmod.Elf(data,'arm64-v8a')
        table = next(s for s in elf.symbols(elf.one_section(2)) if s['name']=='owned_methods')
        slot = table['address']+16
        start = elf.mapped(elf.tags[7][0],elf.tags[8][0])
        found = False
        for offset in range(start,start+elf.tags[8][0],24):
            target,_,addend = elf.unpack('QQq',offset)
            if target==slot:
                struct.pack_into('<q',data,offset+16,addend+4); found=True; break
        self.assertTrue(found)
        with self.assertRaisesRegex(common.Rejected,'function target'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)

    def test_nonrelative_table_relocation_is_rejected(self):
        data = bytearray(fixture()); elf = elfmod.Elf(data,'arm64-v8a')
        table = next(s for s in elf.symbols(elf.one_section(2)) if s['name']=='owned_methods')
        start = elf.mapped(elf.tags[7][0],elf.tags[8][0])
        for offset in range(start,start+elf.tags[8][0],24):
            if elf.unpack('Q',offset)[0]==table['address']:
                struct.pack_into('<Q',data,offset+8,1025); break
        with self.assertRaisesRegex(common.Rejected,'relative relocation'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)

    def test_changed_descriptor_and_decoy_count_rejected(self):
        original = fixture(); elf = elfmod.Elf(original,'arm64-v8a')
        table = next(s for s in elf.symbols(elf.one_section(2)) if s['name']=='owned_methods')
        descriptor = elf.relative_relocations()[table['address']+8]['addend']
        data = bytearray(original); data[elf.mapped(descriptor,3)+2]=ord('J')
        with self.assertRaisesRegex(common.Rejected,'descriptor'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)
        data = bytearray(original); symtab = elf.one_section(2)
        for offset in range(symtab[4],symtab[4]+symtab[5],symtab[9]):
            values=elf.unpack('IBBHQQ',offset)
            if values[4]==table['address'] and values[5]==table['size']:
                struct.pack_into('<Q',data,offset+16,table['size']-24); break
        with self.assertRaisesRegex(common.Rejected,'count/size'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)

    def test_jmprel_cannot_overwrite_registration_slot(self):
        data = bytearray(fixture()); elf = elfmod.Elf(data, 'arm64-v8a')
        table = next(s for s in elf.symbols(elf.one_section(2)) if s['name']=='owned_methods')
        plt = elf.mapped(elf.tags[23][0], elf.tags[2][0])
        struct.pack_into('<Q', data, plt, table['address'])
        with self.assertRaisesRegex(common.Rejected, 'duplicate relocation destination'):
            elfmod.inspect(bytes(data), 'arm64-v8a', CONTRACT)

    def test_partial_pointer_overlap_and_readonly_target_rejected(self):
        original = fixture(); elf = elfmod.Elf(original, 'arm64-v8a')
        table = next(s for s in elf.symbols(elf.one_section(2)) if s['name']=='owned_methods')
        plt = elf.mapped(elf.tags[23][0], elf.tags[2][0])
        data = bytearray(original); struct.pack_into('<Q', data, plt, table['address']+1)
        with self.assertRaisesRegex(common.Rejected, 'unaligned relocation'):
            elfmod.inspect(bytes(data), 'arm64-v8a', CONTRACT)
        readonly = next(segment['address'] for segment in elf.loads if not segment['flags'] & 2)
        data = bytearray(original); struct.pack_into('<Q', data, plt, readonly)
        with self.assertRaisesRegex(common.Rejected, 'not writable at load'):
            elfmod.inspect(bytes(data), 'arm64-v8a', CONTRACT)

    def test_unsupported_loader_relocation_encodings_rejected(self):
        original = fixture(); elf = elfmod.Elf(original, 'arm64-v8a')
        dynamic = elf.one_section(6)
        flags_entry = next(offset for offset in range(dynamic[4], dynamic[4]+dynamic[5], 16)
                           if elf.unpack('q', offset)[0] == 0x6ffffffb)
        for tag in (35, 36, 37, 0x6000000f, 0x60000011, 0x6fffe000):
            data = bytearray(original); struct.pack_into('<q', data, flags_entry, tag)
            with self.subTest(tag=tag), self.assertRaisesRegex(common.Rejected, 'unsupported packed/RELR'):
                elfmod.inspect(bytes(data), 'arm64-v8a', CONTRACT)

    def test_missing_loader_dynamic_segment_rejected(self):
        data=bytearray(fixture()); elf=elfmod.Elf(data,'arm64-v8a')
        h=elf.unpack('HHIQQQIHHHHHH',16)
        for offset in range(h[4],h[4]+h[9]*h[8],h[8]):
            if elf.unpack('I',offset)[0]==2:
                struct.pack_into('<I',data,offset,0); break
        with self.assertRaisesRegex(common.Rejected,'PT_DYNAMIC'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)

    def test_wrong_import_contract_and_legacy_export_rejected(self):
        bad=copy.deepcopy(CONTRACT); bad['importsByAbi']['arm64-v8a'].append('custom_application_symbol')
        with self.assertRaisesRegex(common.Rejected,'import set'):
            elfmod.inspect(fixture(),'arm64-v8a',bad)
        data=bytearray(fixture()); elf=elfmod.Elf(data,'arm64-v8a')
        sym=elf.dynamic_symbols; strings=elf.linked_strings(sym)
        offset=strings[4]+elf.content(strings).index(b'JNI_OnLoad\0')
        data[offset:offset+10]=b'JNI_OldPid'
        with self.assertRaisesRegex(common.Rejected,'export set'):
            elfmod.inspect(bytes(data),'arm64-v8a',CONTRACT)


if __name__=='__main__': unittest.main()
