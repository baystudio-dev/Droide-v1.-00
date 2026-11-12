"""Fixture-only controls for view source checks; no compiler, network or target code."""
from __future__ import annotations

import contextlib
import copy
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import fdroid_termux_view_sources as checker


def zip_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return stream.getvalue()


class ViewSourceFixture:
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="termux-view-source-controls-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root = self.base / "repo"
        self.sources = self.root / checker.SOURCE_RELATIVE
        original = TOOLS.parent / checker.SOURCE_RELATIVE
        for name in set(checker.PINS) | {"PROVENANCE.json"}:
            destination = self.sources / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((original / name).read_bytes())

    def manifest(self):
        return json.loads((self.sources / "PROVENANCE.json").read_text(encoding="utf-8"))

    def change_manifest(self, transform):
        manifest = self.manifest()
        transform(manifest)
        (self.sources / "PROVENANCE.json").write_text(json.dumps(manifest), encoding="utf-8")

    def snapshot(self):
        return {p.relative_to(self.root).as_posix(): p.read_bytes() for p in self.root.rglob("*") if p.is_file()}


class ViewSourceTests(ViewSourceFixture, unittest.TestCase):
    def test_vendored_sources_are_covered_by_shared_integrity_manifest(self):
        manifest = TOOLS.parent / "third_party/ARTIFACTS_SHA256.txt"
        pins = {}
        for line in manifest.read_text(encoding="utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith("#"):
                digest, relative = line.split(None, 1)
                self.assertNotIn(relative.strip(), pins)
                pins[relative.strip()] = digest
        for name in set(checker.PINS) | {"PROVENANCE.json"}:
            relative = (checker.SOURCE_RELATIVE / name).as_posix()
            with self.subTest(path=relative):
                self.assertIn(relative, pins)
                self.assertEqual(checker.sha256((TOOLS.parent / relative).read_bytes()), pins[relative])

    def test_source_only_default_never_reads_or_requires_an_aar(self):
        original_read = checker.read_regular
        reads = []
        def guard(path, *args):
            reads.append(Path(path))
            self.assertNotEqual(Path(path).suffix, ".aar")
            return original_read(path, *args)
        with patch.object(checker, "read_regular", side_effect=guard), patch.object(
                checker, "compare_reference", side_effect=AssertionError("reference must not be inferred")):
            report = checker.verify(self.root)
        self.assertEqual(len(reads), 6)
        self.assertFalse(report["sourceOnlyCheckRequiresReferenceBinary"])
        self.assertFalse(report["referenceComparisonPerformed"])
        self.assertNotIn("referenceComparison", report)
        self.assertEqual(report["sourceManifestAndXmlCount"], 4)
        self.assertEqual(report["resourceCount"], 3)

    def test_source_check_is_read_only_and_has_no_build_acceptance_claim(self):
        before = self.snapshot()
        report = checker.verify(self.root)
        self.assertEqual(before, self.snapshot())
        for name in ("productionDependenciesChanged", "compilationPerformed", "androidResourcesGenerated",
                     "deviceValidated", "fdroidAcceptanceVerified"):
            self.assertFalse(report[name])
        self.assertEqual(set(report["sourceSha256ByPath"]), set(checker.PINS))

    def test_original_license_text_is_required(self):
        (self.sources / "LICENSE.md").write_text("Apache-2.0", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: LICENSE.md"):
            checker.verify(self.root)

    def test_source_xml_hash_mutation_rejected(self):
        path = self.sources / "res/values/strings.xml"
        path.write_bytes(path.read_bytes().replace(b"Copy", b"Share"))
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            checker.verify(self.root)

    def test_missing_or_extra_file_rejected(self):
        extra = self.sources / "extra.class"
        extra.write_bytes(b"not used")
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            checker.verify(self.root)
        extra.unlink()
        (self.sources / "AndroidManifest.xml").unlink()
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            checker.verify(self.root)

    def test_upstream_commit_cannot_be_changed_to_a_branch(self):
        self.change_manifest(lambda manifest: manifest["upstream"].update(commit="master"))
        with self.assertRaisesRegex(ValueError, "immutable upstream identity"):
            checker.verify(self.root)

    def test_source_pin_and_url_cannot_be_changed_together(self):
        def mutate(manifest):
            manifest["files"]["AndroidManifest.xml"]["sha256"] = "0" * 64
            manifest["files"]["AndroidManifest.xml"]["url"] = "https://example.invalid/manifest.xml"
        self.change_manifest(mutate)
        with self.assertRaisesRegex(ValueError, "mappings/pins changed"):
            checker.verify(self.root)

    def test_sdk_metadata_change_rejected(self):
        self.change_manifest(lambda manifest: manifest["buildMetadata"].update(targetSdkVersion=36))
        with self.assertRaisesRegex(ValueError, "SDK metadata changed"):
            checker.verify(self.root)

    def test_sdk_metadata_source_identity_change_rejected(self):
        self.change_manifest(lambda manifest: manifest["buildMetadataSource"].update(gitBlobSha1="0" * 40))
        with self.assertRaisesRegex(ValueError, "SDK metadata provenance changed"):
            checker.verify(self.root)

    def test_resource_tree_provenance_change_rejected(self):
        self.change_manifest(lambda manifest: manifest["resourceTrees"]["values"]["paths"].append("unreviewed.xml"))
        with self.assertRaisesRegex(ValueError, "resource tree provenance changed"):
            checker.verify(self.root)

    def test_duplicate_manifest_key_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate provenance JSON key"):
            checker.parse_json(b'{"schemaVersion":1,"schemaVersion":2}')

    def test_manifest_schema_boolean_is_not_integer_version(self):
        self.change_manifest(lambda manifest: manifest.update(schemaVersion=True))
        with self.assertRaisesRegex(ValueError, "schema changed"):
            checker.verify(self.root)

    def test_symlink_source_leaf_or_ancestor_rejected(self):
        leaf = self.sources / "AndroidManifest.xml"
        copied = self.base / "manifest.xml"
        copied.write_bytes(leaf.read_bytes())
        leaf.unlink()
        leaf.symlink_to(copied)
        with self.assertRaisesRegex(ValueError, "symlinked protected path"):
            checker.verify(self.root)
        alias = self.base / "repo-alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlinked protected path"):
            checker.verify(alias)

    def test_bounded_reads_reject_oversized_text(self):
        large = self.base / "large.xml"
        large.write_bytes(b"x" * (checker.MAX_SOURCE_BYTES + 1))
        with self.assertRaisesRegex(ValueError, "byte bound"):
            checker.read_regular(large)

    def test_cli_default_outputs_only_source_verification(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = checker.main(["--root", str(self.root)])
        report = json.loads(output.getvalue())
        self.assertEqual(result, 0)
        self.assertFalse(report["referenceComparisonPerformed"])


class XmlSemanticsTests(unittest.TestCase):
    def test_string_resource_order_and_outer_whitespace_are_not_semantic(self):
        first = b'<resources><string name="a">One</string><string name="b">Two</string></resources>'
        second = b'<resources>\n  <string name="b">Two</string>\n  <string name="a">One</string>\n</resources>'
        self.assertEqual(checker.strings(first), checker.strings(second))

    def test_string_value_whitespace_is_not_silently_trimmed(self):
        first = b'<resources><string name="a">One</string></resources>'
        second = b'<resources><string name="a"> One </string></resources>'
        self.assertNotEqual(checker.strings(first), checker.strings(second))

    def test_duplicate_string_name_rejected(self):
        raw = b'<resources><string name="a">One</string><string name="a">Two</string></resources>'
        with self.assertRaisesRegex(ValueError, "duplicate string resource"):
            checker.strings(raw)

    def test_entity_declarations_rejected_without_expansion(self):
        raw = b'<!DOCTYPE resources [<!ENTITY a "One">]><resources><string name="a">&a;</string></resources>'
        with self.assertRaisesRegex(ValueError, "declarations/entities"):
            checker.xml(raw)

    def test_reference_manifest_may_add_only_pinned_sdk(self):
        valid = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.termux.view"><uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28"/></manifest>'
        checker.manifest_reference(valid)
        for invalid in (valid.replace(b'"28"', b'"36"'), valid.replace(b'</manifest>', b'<application/></manifest>')):
            with self.subTest(invalid=invalid), self.assertRaisesRegex(ValueError, "uses-sdk"):
                checker.manifest_reference(invalid)


class ReferenceComparisonTests(ViewSourceFixture, unittest.TestCase):
    """Synthetic reference archive; expected hash is patched only for these fixtures."""
    def reference_entries(self):
        entries = {name: (self.sources / name).read_bytes() for name in checker.RESOURCE_PATHS if "/drawable/" in name}
        entries["res/values/values.xml"] = (
            '<resources>\n<string name="copy_text">Copy</string>\n<string name="paste_text">Paste</string>\n'
            '<string name="text_selection_more">More…</string>\n</resources>\n').encode("utf-8")
        entries["AndroidManifest.xml"] = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.termux.view">'
            '<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28"/></manifest>').encode("utf-8")
        entries["R.txt"] = ("\n".join(sorted(checker.EXPECTED_R)) + "\n").encode("utf-8")
        entries["classes.jar"] = b"fixture bytes are never loaded, parsed as classes, or executed"
        return entries

    def compare_fixture(self, entries):
        raw = zip_bytes(entries)
        path = self.base / "reference.aar"
        path.write_bytes(raw)
        source_bytes = {name: (self.sources / name).read_bytes() for name in checker.PINS}
        with patch.object(checker, "AAR_SHA256", checker.sha256(raw)):
            return checker.compare_reference(path, source_bytes)

    def test_optional_reference_report_keeps_byte_and_xml_parity_separate(self):
        report = self.compare_fixture(self.reference_entries())
        self.assertTrue(report["drawablesByteIdentical"])
        self.assertTrue(report["stringsXmlEquivalent"])
        self.assertFalse(report["stringsByteIdentical"])
        self.assertFalse(report["manifestByteIdentical"])
        self.assertTrue(report["manifestAddsOnlyPinnedUsesSdk"])
        self.assertFalse(report["referenceUsedAsBuildInput"])
        self.assertFalse(report["classesLoadedOrExecuted"])

    def test_wrong_reference_hash_fails_before_zip_inspection(self):
        path = self.base / "wrong.aar"
        path.write_bytes(b"not a reference archive")
        with patch.object(checker, "aar_resource_entries", side_effect=AssertionError("untrusted ZIP must not be opened")):
            with self.assertRaisesRegex(ValueError, "AAR SHA-256 mismatch"):
                checker.compare_reference(path, {})

    def test_reference_drawable_mutation_rejected(self):
        entries = self.reference_entries()
        name = "res/drawable/text_select_handle_left_material.xml"
        entries[name] = entries[name].replace(b"#2196F3", b"#000000")
        with self.assertRaisesRegex(ValueError, "drawable is not byte-identical"):
            self.compare_fixture(entries)

    def test_added_alternative_resource_is_not_ignored(self):
        entries = self.reference_entries()
        entries["res/values-night/values.xml"] = entries["res/values/values.xml"]
        with self.assertRaisesRegex(ValueError, "resource inventory changed"):
            self.compare_fixture(entries)

    def test_string_translation_or_symbol_change_rejected(self):
        entries = self.reference_entries()
        changed = copy.deepcopy(entries)
        changed["res/values/values.xml"] = changed["res/values/values.xml"].replace(b"Copy", b"Share")
        with self.assertRaisesRegex(ValueError, "string values differ"):
            self.compare_fixture(changed)
        changed = copy.deepcopy(entries)
        changed["R.txt"] += b"int string unexpected 0x0\n"
        with self.assertRaisesRegex(ValueError, "resource symbols changed"):
            self.compare_fixture(changed)

    def test_unsafe_zip_path_rejected(self):
        entries = self.reference_entries()
        entries["../outside"] = b"not extracted"
        with self.assertRaisesRegex(ValueError, "unsafe/duplicate"):
            checker.aar_resource_entries(zip_bytes(entries))

    def test_duplicate_and_symlink_zip_entries_rejected(self):
        stream = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(stream, "w") as archive:
                archive.writestr("R.txt", b"one")
                archive.writestr("R.txt", b"two")
        with self.assertRaisesRegex(ValueError, "unsafe/duplicate"):
            checker.aar_resource_entries(stream.getvalue())
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            info = zipfile.ZipInfo("res/alias")
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            archive.writestr(info, b"../../outside")
        with self.assertRaisesRegex(ValueError, "nonregular"):
            checker.aar_resource_entries(stream.getvalue())


if __name__ == "__main__":
    unittest.main()
