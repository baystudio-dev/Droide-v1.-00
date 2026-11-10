"""Input guards for the supplementary owned JNI host runner (no native execution)."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

SPEC=importlib.util.spec_from_file_location('owned_host',Path(__file__).resolve().parents[1]/'verify_owned_jni_host_contract.py')
MODULE=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

class InputGuards(unittest.TestCase):
    def archive(self, entries):
        buffer=io.BytesIO()
        with zipfile.ZipFile(buffer,'w') as archive:
            for name,data in entries: archive.writestr(name,data)
        return buffer.getvalue()

    def test_archive_preserves_class_bytes(self):
        data=self.archive([('com/termux/terminal/JNI.class',b'classbytes')])
        self.assertEqual(MODULE.archive_members(data),{'com/termux/terminal/JNI.class':b'classbytes'})

    def test_archive_rejects_duplicate_entries(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            data=self.archive([('same',b'one'),('same',b'two')])
        with self.assertRaisesRegex(ValueError,'duplicate'): MODULE.archive_members(data)

    def test_archive_rejects_unsafe_paths(self):
        for name in ('../outside','/absolute','a/../b','a\\b'):
            with self.subTest(name=name),self.assertRaisesRegex(ValueError,'unsafe'):
                MODULE.archive_members(self.archive([(name,b'bad')]))

    def test_existing_and_overlapping_output_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);protected=root/'source';protected.mkdir()
            for path in (root,protected,protected/'new'):
                with self.subTest(path=path),self.assertRaises(ValueError):
                    MODULE.validate_output(path,[protected])
            self.assertEqual(MODULE.validate_output(root/'new',[protected]),root/'new')

    def test_symlink_ancestor_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'real').mkdir();(root/'alias').symlink_to(root/'real')
            with self.assertRaisesRegex(ValueError,'symlink'):
                MODULE.validate_output(root/'alias/new',[])

if __name__=='__main__': unittest.main()
