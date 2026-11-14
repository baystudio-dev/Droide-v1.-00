import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(TOOLS))
import run_current_engine_host_v1 as host

class HostBindingGuards(unittest.TestCase):
    def test_commands_bind_exact_fresh_paths(self):
        values=[Path('/root'),Path('/jdk'),Path('/fresh/terminal-engine.aar'),Path('/deps/junit'),Path('/deps/hamcrest'),Path('/out')]
        commands=host.commands(*values)
        for label,argv in commands.items():
            self.assertEqual(argv[argv.index('--engine-aar')+1],'/fresh/terminal-engine.aar')
            self.assertEqual(argv[argv.index('--output')+1],'/out/'+label)
        self.assertNotIn('verify_private_debug_native.py',str(commands))

    def test_exclusive_json_prevents_lineage_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'pre-run.json';host.write_json(path,{'first':True})
            with self.assertRaises(FileExistsError):host.write_json(path,{'first':False})
            self.assertEqual(json.loads(path.read_text()),{'first':True})

    def test_inventory_rejects_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'real').write_text('x');(root/'link').symlink_to(root/'real')
            with self.assertRaisesRegex(ValueError,'symlink'):host.inventory(root)

    def test_verifier_rejects_wrong_fresh_build_lineage(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);host.write_json(root/'pre-run.json',{'javaBuild':{'receiptSha256':'old'}})
            with patch.object(host,'prepare',return_value={'javaBuild':{'receiptSha256':'fresh'}}):
                with self.assertRaisesRegex(ValueError,'pre-run lineage'):
                    host.verify(root,root,root,root,root,root,root,root)

    def test_runner_rejects_existing_output_before_verifying_child(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with patch.object(host,'prepare') as prepare:
                with self.assertRaisesRegex(ValueError,'output exists'):
                    host.run(root,root,root,root,root,root,root,root)
                prepare.assert_not_called()

    def test_copied_engine_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);(output/'owned-jni').mkdir();(output/'owned-jni/engine.jar').write_bytes(b'wrong')
            pre={'commands':{'owned-jni':['--root',str(TOOLS.parent)]},'engine':{'classesJarSha256':'0'*64}}
            with self.assertRaisesRegex(ValueError,'copied engine'):
                host.verify_retained_bytes(output,pre,{}, {})

    def test_host_library_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);(output/'owned-jni/library').mkdir(parents=True)
            jar=output/'owned-jni/engine.jar';jar.write_bytes(b'jar')
            (output/'owned-jni/library/libtermux.so').write_bytes(b'wrong')
            pre={'commands':{'owned-jni':['--root',str(TOOLS.parent)]},'engine':{'classesJarSha256':host.sha(jar)}}
            with self.assertRaisesRegex(ValueError,'host library'):
                host.verify_retained_bytes(output,pre,{'librarySha256':'0'*64}, {})

    def test_generated_fixture_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            output=Path(directory);(output/'owned-jni/library').mkdir(parents=True);(output/'owned-jni/fixture').mkdir()
            jar=output/'owned-jni/engine.jar';jar.write_bytes(b'jar')
            library=output/'owned-jni/library/libtermux.so';library.write_bytes(b'lib')
            (output/'owned-jni/fixture/OwnedJniHostContract.java').write_text('fake')
            pre={'commands':{'owned-jni':['--root',str(TOOLS.parent)]},'engine':{'classesJarSha256':host.sha(jar)}}
            with self.assertRaisesRegex(ValueError,'generated fixture'):
                host.verify_retained_bytes(output,pre,{'librarySha256':host.sha(library)}, {})

    def test_pinned_host_inputs_still_match(self):
        root=TOOLS.parent
        self.assertEqual({p:host.sha(root/p) for p in host.PINNED},host.PINNED)

if __name__=='__main__': unittest.main()
