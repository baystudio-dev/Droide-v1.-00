import os
from pathlib import Path
import struct
import sys
import unittest
import tempfile
from unittest.mock import patch
import current_native_jni_guard as guard
import current_debug_inputs
import current_debug_inputs_v2
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from current_native_jni_guard import defined_functions, verify_library, verify_composite_jni, REQUIRED, KOTLIN, required_methods
from filesystem_native_v1 import PREFIX, Rejected, kotlin_contract, header

class BooleanDescriptorTests(unittest.TestCase):
    def test_exact_lock_descriptor_and_native_header(self):
        methods=kotlin_contract('@JvmStatic external fun exportDescriptorLock(fd: Int, acquire: Boolean): Int')
        self.assertEqual({'exportDescriptorLock':'(IZ)I'},methods)
        self.assertIn('JNIEnv *, jclass, jint, jboolean',header(methods))
    def test_previous_mappings_and_unknown_types_remain_strict(self):
        self.assertEqual({'probe':'(IJ[B[J)J'},kotlin_contract('@JvmStatic external fun probe(a: Int, b: Long, c: ByteArray, d: LongArray): Long'))
        for kind in ('String','Double','BooleanArray'):
            with self.subTest(kind=kind),self.assertRaises(Rejected):kotlin_contract('@JvmStatic external fun probe(a: '+kind+'): Int')
        with self.assertRaises(Rejected):header({'bad':'(D)I'})

class NativeJniGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        paths = [os.environ.get(k) for k in ('DROIDE_JNI_OLD_FIXTURE', 'DROIDE_JNI_NEW_FIXTURE', 'DROIDE_JNI_SOURCE_ROOT')]
        if not all(paths): raise unittest.SkipTest('Set explicit old/new host ELF fixtures and source root')
        cls.old, cls.new = [Path(p).read_bytes() for p in paths[:2]]
        cls.methods = required_methods((Path(paths[2])/KOTLIN).read_text())
    def test_both_current_validators_invoke_guard_before_external_verifiers(self):
        class GuardReached(Exception): pass
        root=Path('/unused-test-root')
        for module in (current_debug_inputs,current_debug_inputs_v2):
            with self.subTest(module=module.__name__),patch.object(module,'ROOT',root),patch.object(module,'verify_composite_jni',side_effect=GuardReached) as call:
                with self.assertRaises(GuardReached):module.verify(root,root/'composite',root/'context')
                call.assert_called_once_with(root,root/'composite')
    def test_composite_checks_every_abi_and_rejects_missing_abi(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/KOTLIN;source.parent.mkdir(parents=True)
            source.write_text('\n'.join('@JvmStatic external fun '+name+'(): Int' for name in self.methods))
            for abi in guard.ABIS:
                path=root/'composite/native'/abi/'libdroide_files.so';path.parent.mkdir(parents=True);path.write_bytes(b'fixture')
            with patch.object(guard,'verify_library',return_value=[]) as verify:
                verify_composite_jni(root,root/'composite')
                self.assertEqual([call.args[1] for call in verify.call_args_list],list(guard.ABIS))
            (root/'composite/native/x86/libdroide_files.so').unlink()
            (root/'composite/native/x86').rmdir()
            with self.assertRaisesRegex(Rejected,'ABI set'):verify_composite_jni(root,root/'composite')
    def test_old_host_library_rejected(self):
        with self.assertRaisesRegex(Rejected, 'exportDescriptorLock'): verify_library(self.old, 'x86_64', self.methods)
    def test_new_exact_host_library_accepted_and_previous_exports_preserved(self):
        actual = verify_library(self.new, 'x86_64', self.methods)
        previous = defined_functions(self.old, 'x86_64')
        self.assertEqual(set(actual)-previous, {REQUIRED})
        self.assertEqual(previous-set(actual), set())
        self.assertEqual(len(actual), 18)
    def test_appended_symbol_text_does_not_satisfy_guard(self):
        with self.assertRaises(Rejected): verify_library(self.old+REQUIRED.encode()+b'\0', 'x86_64', self.methods)
    def test_truncated_elf_refused(self):
        for size in (0, 4, 63, 100, len(self.new)//2):
            with self.subTest(size=size), self.assertRaises(Rejected): verify_library(self.new[:size], 'x86_64', self.methods)
    def symbol_offset(self, data):
        shoff=struct.unpack_from('<Q',data,40)[0]; entsize,count=struct.unpack_from('<HH',data,58)
        sections=[struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*entsize) for i in range(count)]
        section=next(s for s in sections if s[1]==11); strings=sections[section[6]]
        table=data[strings[4]:strings[4]+strings[5]]
        for off in range(section[4],section[4]+section[5],section[9]):
            name=struct.unpack_from('<I',data,off)[0]
            if table[name:table.find(b'\0',name)].decode()==REQUIRED:return off
        self.fail('Fixture export missing')
    def test_undefined_hidden_and_data_symbols_refused(self):
        offset=self.symbol_offset(self.new)
        for kind in ('undefined','hidden','object'):
            data=bytearray(self.new)
            if kind=='undefined':struct.pack_into('<H',data,offset+6,0)
            elif kind=='hidden':data[offset+5]=2
            else:data[offset+4]=(data[offset+4]&0xf0)|1
            with self.subTest(kind=kind),self.assertRaisesRegex(Rejected,'exportDescriptorLock'):
                verify_library(data,'x86_64',self.methods)
    def test_wrong_abi_refused(self):
        for abi in ('arm64-v8a','armeabi-v7a','x86'):
            with self.subTest(abi=abi),self.assertRaises(Rejected):verify_library(self.new,abi,self.methods)
    def test_loader_symbol_pointer_tampering_refused(self):
        data=bytearray(self.new);shoff=struct.unpack_from('<Q',data,40)[0];entsize,count=struct.unpack_from('<HH',data,58)
        sections=[struct.unpack_from('<IIQQQQIIQQ',data,shoff+i*entsize) for i in range(count)]
        dynamic=next(s for s in sections if s[1]==6)
        for off in range(dynamic[4],dynamic[4]+dynamic[5],dynamic[9]):
            if struct.unpack_from('<q',data,off)[0]==6:
                struct.pack_into('<Q',data,off+8,0);break
        with self.assertRaisesRegex(Rejected,'linkage'):verify_library(data,'x86_64',self.methods)

if __name__=='__main__':unittest.main()
