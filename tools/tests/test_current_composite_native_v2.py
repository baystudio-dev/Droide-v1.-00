"""Pure synthetic V2 boundary tests. No child/compiler/provider execution."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

TOOLS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOLS))
import current_composite_native_v2 as C

class ProjectionTests(unittest.TestCase):
    def test_exact_baseline_and_additions(self):
        C.exact_projection({'base':'a','new':'b'},{'base':'a'},{'new'})
    def test_missing_base_rejected(self):
        with self.assertRaises(ValueError):C.exact_projection({'new':'b'},{'base':'a'},{'new'})
    def test_changed_base_rejected(self):
        with self.assertRaises(ValueError):C.exact_projection({'base':'z','new':'b'},{'base':'a'},{'new'})
    def test_unapproved_extra_rejected(self):
        with self.assertRaises(ValueError):C.exact_projection({'base':'a','new':'b','extra':'c'},{'base':'a'},{'new'})
    def test_child_origin_has_no_additions(self):
        with self.assertRaises(ValueError):C.exact_projection({'base':'a','new':'b'},{'base':'a'},set())

class DeltaTests(unittest.TestCase):
    def setUp(self):
        self.old=b'before\nANCHOR\nafter\n';self.insert='x'*210
        self.new=self.old.replace(b'ANCHOR',b'ANCHOR'+self.insert.encode())
        self.proof={'beforeSha256':hashlib.sha256(self.old).hexdigest(),'afterSha256':hashlib.sha256(self.new).hexdigest(),'anchor':'ANCHOR','insertion':self.insert}
    def test_exact_210_byte_insertion(self):C.validate_delta(self.old,self.new,self.proof)
    def test_extra_change_rejected(self):
        with self.assertRaises(ValueError):C.validate_delta(self.old,self.new+b'x',self.proof)
    def test_wrong_original_rejected(self):
        with self.assertRaises(ValueError):C.validate_delta(b'wrong',self.new,self.proof)
    def test_changed_anchor_rejected_even_if_hashes_match(self):
        self.proof['anchor']='missing'
        with self.assertRaises(ValueError):C.validate_delta(self.old,self.new,self.proof)
    def test_wrong_insert_length_rejected(self):
        self.proof['insertion']='short'
        with self.assertRaises(ValueError):C.validate_delta(self.old,self.new,self.proof)

class CompiledBindingTests(unittest.TestCase):
    def test_wrong_jar_size_refuses_before_archive_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'main.jar';p.write_bytes(b'notzip')
            with self.assertRaisesRegex(ValueError,'size'):
                C.compiled_binding(p,{'compiledApplication':{'bytes':123}})
    def test_wrong_jar_hash_refuses_before_archive_decode(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'main.jar';p.write_bytes(b'notzip')
            with self.assertRaisesRegex(ValueError,'provenance'):
                C.compiled_binding(p,{'compiledApplication':{'bytes':6,'sha256':'0'*64}})

class OriginTests(unittest.TestCase):
    def test_child_locations_exclude_new_consumer_proof_paths(self):
        p={name:Path('/original')/name for name in (*C.common.INPUT_NAMES,*C.EXTRA_INPUTS)}
        actual=C.child_locations(p)
        self.assertEqual(set(actual),set(C.common.INPUT_NAMES))
        commands=C.common.commands(Path('/original/source'),actual)
        self.assertIn('/original/source/tools/filesystem_native_v1.py',commands['filesystem'])
        self.assertIn('verify',commands['filesystem']);self.assertNotIn('build',commands['filesystem'])
        self.assertNotIn('application_main_jar',actual)
    def test_v1_recipe_is_never_used_to_bypass_stale_pin(self):
        source=(TOOLS/'current_composite_native_v2.py').read_text()
        self.assertNotIn('common.recipe(',source)
        self.assertNotIn('common.CONTRACT_SHA =',source)
    def test_exact_five_additions_no_directory_exclusion(self):
        self.assertEqual(len(C.ADDITIONS),5)
        self.assertTrue(all('.' in Path(name).name for name in C.ADDITIONS))


class SyntheticCompiledClassTests(unittest.TestCase):
    def fixture(self, root):
        import io, struct, zipfile
        u2=lambda n:struct.pack('>H',n)
        u4=lambda n:struct.pack('>I',n)
        utf=lambda text:b'\x01'+u2(len(text))+text.encode()
        cp=[utf('example/T'),b'\x07'+u2(1),utf('java/lang/Object'),b'\x07'+u2(3),utf('helper'),utf('()V'),utf('Code'),utf('nativeCall'),utf('()I')]
        code=u2(1)+u2(1)+u4(1)+b'\xb1'+u2(0)+u2(0)
        value=b'\xca\xfe\xba\xbe'+u2(0)+u2(61)+u2(10)+b''.join(cp)+u2(0x21)+u2(2)+u2(4)+u2(0)+u2(0)+u2(2)
        value+=u2(17)+u2(5)+u2(6)+u2(1)+u2(7)+u4(len(code))+code
        value+=u2(0x109)+u2(8)+u2(9)+u2(0)+u2(0)
        path=root/'classes.jar'
        with zipfile.ZipFile(path,'w') as z:z.writestr('example/T.class',value)
        proof={'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
               'archiveMemberCount':1,'families':['example/T'],'classes':{'example/T.class':{'size':len(value),'sha256':hashlib.sha256(value).hexdigest()}},
               'methods':[{'class':'example/T.class','name':'helper','descriptor':'()V','access':17,'codeAttributeSha256':hashlib.sha256(code).hexdigest()}],
               'nativeMethods':{'class':'example/T.class','methods':[{'name':'nativeCall','descriptor':'()I','access':0x109}]},'externalRuntimeDependencies':['java/lang/Object']}
        return path,{'compiledApplication':proof}
    def test_exact_compiled_class_and_code_binding(self):
        with tempfile.TemporaryDirectory() as directory:
            path,contract=self.fixture(Path(directory));result=C.compiled_binding(path,contract,{'nativeCall':'()I'})
            self.assertEqual(result['classCount'],1);self.assertFalse(result['androidRuntimeVerified'])
    def test_substituted_code_descriptor_access_or_native_shape_refuses(self):
        for mutation in ('code','descriptor','access','class-hash','class-size','native','closure','count'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                path,contract=self.fixture(Path(directory));p=contract['compiledApplication']
                if mutation=='code':p['methods'][0]['codeAttributeSha256']='0'*64
                elif mutation=='descriptor':p['methods'][0]['descriptor']='()I'
                elif mutation=='access':p['methods'][0]['access']=1
                elif mutation=='class-hash':p['classes']['example/T.class']['sha256']='0'*64
                elif mutation=='class-size':p['classes']['example/T.class']['size']+=1
                elif mutation=='native':p['nativeMethods']['methods'][0]['descriptor']='()V'
                elif mutation=='closure':p['families']=['example/Other']
                else:p['archiveMemberCount']=2
                with self.assertRaises(ValueError):C.compiled_binding(path,contract,{'nativeCall':'()I'})

class AssemblyRefusalTests(unittest.TestCase):
    def test_existing_output_never_invokes_child_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);output=root/'existing';output.mkdir()
            with mock.patch.object(C,'recipe',return_value=({},{})),mock.patch.object(C,'validate_inputs',return_value=({},{})),mock.patch.object(C.common,'verify_children') as launch:
                with self.assertRaisesRegex(ValueError,'output exists'):C.assemble(root,{},output)
                launch.assert_not_called()
    def test_incorrect_input_set_refuses_before_archive_or_child(self):
        with self.assertRaisesRegex(ValueError,'input set'):
            C.validate_inputs(Path('/current'),{}, {},{})


class ActualSourceGateTests(unittest.TestCase):
    def test_v1_still_refuses_current_historical_substrate_mismatch(self):
        with self.assertRaisesRegex(ValueError,'pinned source/tool changed: .*LocalExecutionSubstrate'):
            C.common.recipe(TOOLS.parent)
    def test_v2_exact_current_baseline_and_additions_are_accepted(self):
        contract,baseline=C.recipe(TOOLS.parent)
        self.assertEqual(len(baseline),3999)
        self.assertEqual(contract['kind'],'CURRENT_COMPOSITE_NATIVE_V2')


class ActualMutationTests(unittest.TestCase):
    fixture = SyntheticCompiledClassTests.fixture
    def test_actual_jar_byte_corruption_with_stale_proof(self):
        with tempfile.TemporaryDirectory() as directory:
            path,contract=self.fixture(Path(directory));data=bytearray(path.read_bytes());data[-1]^=1;path.write_bytes(data)
            with self.assertRaisesRegex(ValueError,'provenance'):C.compiled_binding(path,contract,{'nativeCall':'()I'})
    def test_immutable_filesystem_recipe_descriptor_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            path,contract=self.fixture(Path(directory))
            with self.assertRaisesRegex(ValueError,'immutable native recipe'):C.compiled_binding(path,contract,{'nativeCall':'()V'})

class V2OrchestrationTests(unittest.TestCase):
    def test_wrong_origin_baseline_refuses_before_child_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);child=root/'child';child.mkdir();(child/'old').write_bytes(b'changed')
            locations={name:root/name for name in (*C.common.INPUT_NAMES,*C.EXTRA_INPUTS)};locations['child_source_root']=child
            with mock.patch.object(C,'recipe',return_value=({}, {'old':'0'*64})),mock.patch.object(C.common,'verify_children') as launch:
                with self.assertRaisesRegex(ValueError,'baseline source bytes'):C.assemble(root/'current',locations,root/'output')
                launch.assert_not_called()
    def test_output_alias_refuses_before_child_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);current=root/'current';current.mkdir();output=current/'output'
            with mock.patch.object(C,'recipe',return_value=({},{})),mock.patch.object(C,'validate_inputs',return_value=({},{})),mock.patch.object(C.common,'verify_children') as launch:
                with self.assertRaisesRegex(ValueError,'overlaps'):C.assemble(current,{},output)
                launch.assert_not_called()
    def test_promoted_child_flag_refuses_before_artifact_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);current=root/'current';current.mkdir();child=root/'child';child.mkdir()
            locations={name:root/name for name in (*C.common.INPUT_NAMES,*C.EXTRA_INPUTS)};locations['child_source_root']=child
            for name in ('filesystem_output','termux_output','engine_output','host_output'):
                locations[name].mkdir();(locations[name]/'receipt.json').write_text(json.dumps({'status':C.common.STATUS,'deviceValidated':True,'apkAdmitted':False,'releaseCompatible':False}))
            output=root/'output'
            with mock.patch.object(C,'recipe',return_value=({},{})),mock.patch.object(C,'validate_inputs',return_value=(locations,{})),mock.patch.object(C.common,'verify_children') as launch:
                with self.assertRaisesRegex(ValueError,'scope invalid'):C.assemble(current,locations,output)
                launch.assert_called_once();self.assertFalse((output/'native').exists());self.assertFalse((output/'receipt.json').exists())
    def test_rebased_recorded_command_refuses_through_v2_verify(self):
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);current=root/'current';current.mkdir();child=root/'child';child.mkdir();output=root/'output';(output/'verification').mkdir(parents=True)
            locations={name:root/name for name in (*C.common.INPUT_NAMES,*C.EXTRA_INPUTS)};locations['child_source_root']=child
            saved={'sourceRoot':str(current),'inputs':{k:str(v) for k,v in locations.items()}}
            (output/'receipt.json').write_text(json.dumps(saved))
            wrong=C.common.commands(current,C.child_locations(locations))['filesystem']
            (output/'verification/filesystem.command.json').write_text(json.dumps({'argv':wrong,'exit':0}))
            with mock.patch.object(C,'recipe',return_value=({},{})),mock.patch.object(C,'validate_inputs',return_value=(locations,{})),mock.patch.object(C,'file_hash',return_value='same'),mock.patch.object(C.common.subprocess,'run',return_value=subprocess.CompletedProcess([],0,b'',b'')) as launch:
                with self.assertRaisesRegex(ValueError,'recorded child verification command differs'):C.verify(current,output)
                self.assertIn(str(child/'tools/filesystem_native_v1.py'),launch.call_args.args[0])
    def test_changed_saved_receipt_refuses_after_exact_origin_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);current=root/'current';current.mkdir();output=root/'output';output.mkdir()
            locations={name:root/name for name in (*C.common.INPUT_NAMES,*C.EXTRA_INPUTS)}
            (output/'receipt.json').write_text(json.dumps({'sourceRoot':str(current),'inputs':{k:str(v) for k,v in locations.items()},'apkAdmitted':True}))
            with mock.patch.object(C,'recipe',return_value=({},{})),mock.patch.object(C,'validate_inputs',return_value=(locations,{})),mock.patch.object(C,'file_hash',return_value='same'),mock.patch.object(C.common,'verify_children') as launch,mock.patch.object(C.common,'read_components',return_value=({},b'')),mock.patch.object(C.common,'selected_providers',return_value={}),mock.patch.object(C,'observation',return_value={}),mock.patch.object(C,'measured',return_value={'apkAdmitted':False}):
                with self.assertRaisesRegex(ValueError,'receipt differs'):C.verify(current,output)
                self.assertEqual(launch.call_args.args[0],locations['child_source_root'])

if __name__=='__main__':unittest.main()
