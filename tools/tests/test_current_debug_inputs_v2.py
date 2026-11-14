import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import current_debug_inputs_v2 as m

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)/'app';self.origin=Path(self.tmp.name)/'origin';self.comp=Path(self.tmp.name)/'composite'
        for p in (self.root/'tools',self.origin,self.comp):p.mkdir(parents=True)
        self.receipt={'kind':'CURRENT_COMPOSITE_NATIVE_V2','sourceRoot':str(self.origin)}
        self.spec={'originRoot':str(self.origin),'sourceProjection':{'app/source':'abc'},'originProjection':{'app/source':'abc'},'additions':['tools/new'],'additionPins':{'tools/new':'def'}}
        self.spec.update(compositePython=str(Path(sys.executable).resolve()),compositePythonSha256=m.sha(m.regular(Path(sys.executable).resolve())))
        self.actual={'app/source':'abc','tools/new':'def'}
        self.sync()
        self.p=patch.object(m,'source_manifest',side_effect=lambda p:dict(self.spec['originProjection']) if p==self.origin else dict(self.actual));self.p.start();self.addCleanup(self.p.stop)
    def sync(self):
        (self.comp/'receipt.json').write_text(json.dumps(self.receipt));self.spec['receiptSha256']=m.sha((self.comp/'receipt.json').read_bytes())
        (self.root/m.CONTRACT).write_text(json.dumps(self.spec));m.CONTRACT_SHA=m.sha((self.root/m.CONTRACT).read_bytes())
    def test_exact_bridge(self):self.assertEqual(m.bridge(self.root,self.comp),self.origin)
    def test_runtime_change(self):
        self.actual['app/source']='mutated'
        with self.assertRaisesRegex(ValueError,'baseline'):m.bridge(self.root,self.comp)
    def test_extra_source(self):
        self.actual['extra']='x'
        with self.assertRaisesRegex(ValueError,'membership'):m.bridge(self.root,self.comp)
    def test_helper_change(self):
        self.actual['tools/new']='x'
        with self.assertRaisesRegex(ValueError,'helper'):m.bridge(self.root,self.comp)
    def test_interpreter_mutation(self):
        self.spec['compositePythonSha256']='0'*64;self.sync()
        with self.assertRaisesRegex(ValueError,'interpreter'):m.bridge(self.root,self.comp)
    def test_wrong_origin(self):
        self.receipt['sourceRoot']='/wrong';self.sync()
        with self.assertRaisesRegex(ValueError,'origin'):m.bridge(self.root,self.comp)
    def test_v1_refused(self):
        self.receipt['kind']='CURRENT_COMPOSITE_NATIVE_V1';self.sync()
        with self.assertRaisesRegex(ValueError,'Wrong'):m.bridge(self.root,self.comp)
    def test_changed_receipt(self):
        (self.comp/'receipt.json').write_text('{}')
        with self.assertRaisesRegex(ValueError,'receipt'):m.bridge(self.root,self.comp)
    def test_invalid_context_no_launch(self):
        ctx=self.root/'context';ctx.write_text('{}')
        with patch.object(m,'ROOT',self.root),patch.object(m,'supervise') as child:
            with self.assertRaisesRegex(ValueError,'context'):m.verify(self.root,self.comp,ctx)
            child.assert_not_called()
    def test_success_calls_origin_then_rechecks(self):
        ctx=self.root/'context';ctx.write_text(json.dumps({'schemaVersion':2,'sourceSha256':self.actual,'compositeReceiptSha256':self.spec['receiptSha256']}))
        with patch.object(m,'ROOT',self.root),patch.object(m,'supervise',return_value={'outcome':'SUCCESS'}) as child:
            m.verify(self.root,self.comp,ctx)
            self.assertIn(str(self.origin/'tools/current_composite_native_v2.py'),child.call_args.args[0])
            self.assertEqual(child.call_args.args[0][0],str(Path(sys.executable).resolve()))
    def test_source_mutation_during_consumer_refused(self):
        ctx=self.root/'context';ctx.write_text(json.dumps({'schemaVersion':2,'sourceSha256':self.actual,'compositeReceiptSha256':self.spec['receiptSha256']}))
        def changed(*a):self.actual['app/source']='changed';return {'outcome':'SUCCESS'}
        with patch.object(m,'ROOT',self.root),patch.object(m,'supervise',side_effect=changed):
            with self.assertRaisesRegex(ValueError,'during'):m.verify(self.root,self.comp,ctx)

if __name__=='__main__':unittest.main()
