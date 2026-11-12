"""Pure-Python source-build guards; these tests never start Java or target code."""

import io
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import fdroid_spake2_source_build as proof


class Spake2SourceBuildTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="droide-spake2-proof-test-")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)

    def source_copy(self):
        root = self.directory / "repo"
        for name in proof.PINS:
            destination = root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT / name).read_bytes())
        return root

    def zip_bytes(self, members):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w") as archive:
                for name, data in members:
                    archive.writestr(name, data)
        return stream.getvalue()

    def test_reviewed_sources_and_source_jar_match_without_execution(self):
        with patch.object(proof.subprocess, "run") as execute:
            inputs = proof.verify_inputs(ROOT)
        self.assertEqual(set(proof.PINS), set(inputs))
        execute.assert_not_called()

    def test_changed_source_fails_closed(self):
        root = self.source_copy()
        source = root / proof.VENDOR / "src/main/java" / proof.SOURCES[0]
        source.write_bytes(source.read_bytes() + b"\n// changed\n")
        with self.assertRaisesRegex(ValueError, "input SHA-256 mismatch"):
            proof.verify_inputs(root)

    def test_unreviewed_additional_source_fails_closed(self):
        root = self.source_copy()
        (root / proof.VENDOR / "src/main/java/Unexpected.java").write_text("class Unexpected {}")
        with self.assertRaisesRegex(ValueError, "source inventory"):
            proof.verify_inputs(root)

    def test_source_tree_does_not_ignore_extra_symlink_directories(self):
        root = self.source_copy()
        (root / proof.VENDOR / "src/main/java/alias").symlink_to(self.directory, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlink in source/output tree"):
            proof.verify_inputs(root)

    def test_symlink_input_is_rejected(self):
        actual = self.directory / "actual"
        actual.write_bytes(b"retained")
        alias = self.directory / "alias"
        alias.symlink_to(actual)
        with self.assertRaisesRegex(ValueError, "Symlinked"):
            proof.read_regular(alias)

    def test_hardlinked_input_is_rejected(self):
        actual = self.directory / "actual"
        actual.write_bytes(b"retained")
        os.link(actual, self.directory / "alias")
        with self.assertRaisesRegex(ValueError, "regular input"):
            proof.read_regular(actual)

    def test_input_size_is_bounded(self):
        actual = self.directory / "actual"
        actual.write_bytes(b"1234")
        with self.assertRaisesRegex(ValueError, "byte bound"):
            proof.read_regular(actual, 3)

    def test_archive_duplicate_member_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            proof.zip_contents(self.zip_bytes([("a", b"one"), ("a", b"two")]))

    def test_archive_path_escape_and_directory_members_are_rejected(self):
        for name in ("/absolute", "../escape", "a/../escape", "a\\escape", "a//b", "a/"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unsafe"):
                proof.zip_contents(self.zip_bytes([(name, b"one")]))

    def test_archive_symlink_is_rejected(self):
        member = zipfile.ZipInfo("alias")
        member.create_system = 3
        member.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(ValueError, "Nonregular"):
            proof.zip_contents(self.zip_bytes([(member, b"target")]))

    def test_class_inventory_and_java_version_are_exact(self):
        classes = {name: b"\xca\xfe\xba\xbe\0\0\0\x34" for name in proof.CLASSES}
        proof.verify_classes(classes)
        classes[next(iter(classes))] = b"\xca\xfe\xba\xbe\0\0\0\x3d"
        with self.assertRaisesRegex(ValueError, "Java 8"):
            proof.verify_classes(classes)
        with self.assertRaisesRegex(ValueError, "inventory"):
            proof.verify_classes({})

    def test_archive_is_byte_reproducible_and_does_not_copy_bundled_zip_metadata(self):
        contents = {"b": b"second", "a": b"first"}
        first = proof.write_jar(self.directory / "first.jar", contents)
        second = proof.write_jar(self.directory / "second.jar", dict(reversed(list(contents.items()))))
        self.assertEqual(first, second)
        with zipfile.ZipFile(io.BytesIO(first)) as archive:
            self.assertEqual(["a", "b"], archive.namelist())
            for member in archive.infolist():
                self.assertEqual(zipfile.ZIP_STORED, member.compress_type)
                self.assertEqual((1980, 1, 1, 0, 0, 0), member.date_time)
                self.assertEqual(0o100644, member.external_attr >> 16)

    def test_existing_output_is_not_overwritten(self):
        root, jdk, output = (self.directory / name for name in ("source", "jdk", "output"))
        for path in (root, jdk, output):
            path.mkdir()
        marker = output / "retained"
        marker.write_bytes(b"keep")
        with self.assertRaisesRegex(ValueError, "already exists"):
            proof.fresh_output(output, root, jdk)
        self.assertEqual(b"keep", marker.read_bytes())

    def test_output_cannot_overlap_source_or_toolchain(self):
        root, jdk = self.directory / "source", self.directory / "jdk"
        root.mkdir()
        jdk.mkdir()
        for output in (root, root / "new", jdk / "new", self.directory):
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "outside source and toolchain"):
                proof.fresh_output(output, root, jdk)

    def test_output_parent_symlink_is_rejected(self):
        root, jdk = self.directory / "source", self.directory / "jdk"
        root.mkdir()
        jdk.mkdir()
        alias = self.directory / "alias"
        alias.symlink_to(self.directory, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "Symlinked"):
            proof.fresh_output(alias / "new", root, jdk)

    def test_fresh_output_is_private(self):
        root, jdk = self.directory / "source", self.directory / "jdk"
        root.mkdir()
        jdk.mkdir()
        output = proof.fresh_output(self.directory / "output", root, jdk)
        self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))

    def test_compiler_cannot_resolve_bundled_classes_or_annotation_processors(self):
        run, jdk = self.directory / "first", self.directory / "jdk"
        sources = [run / "source" / name for name in proof.SOURCES]
        command = proof.compiler_command(jdk, run, run / "classes", sources, run / "empty-classpath")
        self.assertEqual(str(jdk / "bin/javac"), command[0])
        self.assertIn("-proc:none", command)
        self.assertIn("-implicit:none", command)
        self.assertEqual("8", command[command.index("--release") + 1])
        self.assertEqual(str(run / "empty-classpath"), command[command.index("-classpath") + 1])
        self.assertEqual(str(run / "empty-sourcepath"), command[command.index("-sourcepath") + 1])
        self.assertFalse(any(proof.MAVEN in value for value in command))
        self.assertEqual([str(source) for source in sources], command[-2:])

    def test_ambient_jvm_injection_options_are_not_inherited(self):
        with patch.dict(os.environ, {"JAVA_TOOL_OPTIONS": "-agentlib:bad", "JDK_JAVA_OPTIONS": "bad",
                                     "CLASSPATH": "bundled.jar", "LD_PRELOAD": "bad.so"}):
            environment = proof.process_environment(self.directory, self.directory / "jdk")
        self.assertEqual({"PATH", "HOME", "TMPDIR", "XDG_CACHE_HOME", "JAVA_HOME", "LANG", "LC_ALL", "TZ"}, set(environment))
        self.assertEqual(str(self.directory / "home"), environment["HOME"])

    def test_api_equality_does_not_misrepresent_bytecode_equality(self):
        result = proof.comparison({"Example.class": b"new"}, {"Example.class": b"old"}, b"new jar", b"old jar", "same ABI", "same ABI")
        self.assertTrue(result["publicProtectedApiIdentical"])
        self.assertFalse(result["classBytesIdentical"])
        self.assertFalse(result["allEntryBytesIdentical"])
        self.assertFalse(result["archiveBytesIdentical"])
        self.assertEqual(["Example.class"], result["differentEntries"])

    def test_different_zip_wrapping_is_distinct_from_entry_byte_parity(self):
        contents = {"Example.class": b"same code", "META-INF/NOTICE": b"same notice"}
        result = proof.comparison(contents, contents, b"stored zip", b"deflated zip", "same ABI", "same ABI")
        self.assertTrue(result["classBytesIdentical"])
        self.assertTrue(result["allEntryBytesIdentical"])
        self.assertFalse(result["archiveBytesIdentical"])


if __name__ == "__main__":
    unittest.main()
