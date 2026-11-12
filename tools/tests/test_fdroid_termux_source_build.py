"""Read-only contract/mutation tests; no javac, JNI, APK or target program executes."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
import fdroid_termux_source_build as proof


def class_fixture(name="com/termux/terminal/Fixture", access=0x31, fields=(), methods=(),
                  extra_constant=False, signature=None, annotation=False, source_file=None):
    """Construct parse-only classfile fixtures; they are never loaded into a JVM."""
    constants, keys = [], {}
    def entry(key, payload):
        if key not in keys:
            keys[key] = len(constants) + 1
            constants.append(payload)
        return keys[key]
    def utf(value):
        raw = value.encode("utf-8")
        return entry(("utf", value), b"\x01" + struct.pack(">H", len(raw)) + raw)
    def cls(value):
        index = utf(value)
        return entry(("class", value), b"\x07" + struct.pack(">H", index))
    def attr(name, raw):
        return struct.pack(">HI", utf(name), len(raw)) + raw
    def attrs(rows):
        return struct.pack(">H", len(rows)) + b"".join(rows)
    def member(row):
        values = []
        if "constant" in row:
            value = row["constant"]
            if isinstance(value, str):
                string = utf(value)
                constant = entry(("string", value), b"\x08" + struct.pack(">H", string))
            else:
                constant = entry(("int", value), b"\x03" + struct.pack(">i", value))
            values.append(attr("ConstantValue", struct.pack(">H", constant)))
        if "signature" in row:
            values.append(attr("Signature", struct.pack(">H", utf(row["signature"]))))
        if "exceptions" in row:
            values.append(attr("Exceptions", struct.pack(">H", len(row["exceptions"])) +
                               b"".join(struct.pack(">H", cls(e)) for e in row["exceptions"])))
        if "code" in row:
            values.append(attr("Code", row["code"]))
        return struct.pack(">HHH", row.get("access", 0), utf(row["name"]), utf(row["descriptor"])) + attrs(values)
    if extra_constant:
        utf("unused-padding-shifts-every-pool-index")
    this, parent = cls(name), cls("java/lang/Object")
    field_bytes = b"".join(member(row) for row in fields)
    method_bytes = b"".join(member(row) for row in methods)
    class_attrs = []
    if signature:
        class_attrs.append(attr("Signature", struct.pack(">H", utf(signature))))
    if annotation:
        class_attrs.append(attr("RuntimeVisibleAnnotations", b"\x00\x00"))
    if source_file:
        class_attrs.append(attr("SourceFile", struct.pack(">H", utf(source_file))))
    tail = (struct.pack(">HHHH", access, this, parent, 0) + struct.pack(">H", len(fields)) + field_bytes +
            struct.pack(">H", len(methods)) + method_bytes + attrs(class_attrs))
    return b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(constants) + 1) + b"".join(constants) + tail


class SourceClosureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = TOOLS.parent

    def test_current_source_closure_is_complete_without_compiler(self):
        with patch.object(proof.subprocess, "run", side_effect=AssertionError("compiler must not execute")):
            recipe, hashes, sources, origins, expected, overlays, references = proof.load_inputs(self.root)
        self.assertEqual(len(sources), 18)
        self.assertEqual(len(expected), 40)
        self.assertEqual(len(overlays), 33)
        self.assertEqual(len(references), 7)
        self.assertEqual(set(references), {proof.PREFIX + n + ".class" for n in proof.RETAINED_FAMILIES})
        self.assertEqual(hashes[proof.CONTRACT], proof.CONTRACT_SHA256)
        self.assertEqual(sources[proof.PREFIX + "BuildConfig.java"], proof.BUILD_CONFIG_SOURCE)
        self.assertEqual(origins[proof.PREFIX + "TerminalSession.java"], recipe["stages"][-1]["sources"]["TerminalSession"])

    def test_last_stage_sources_win_without_dropping_other_families(self):
        _, _, sources, origins, _, _, _ = proof.load_inputs(self.root)
        for family in ("TerminalSession", "TerminalSessionClient", "TerminalResizeState"):
            name = proof.PREFIX + family + ".java"
            expected = "third_party/termux/derivatives/resize/src/" + name
            self.assertEqual(origins[name], expected)
            self.assertEqual(sources[name], (self.root / expected).read_bytes())
        self.assertEqual(origins[proof.PREFIX + "TerminalPtyIo.java"],
                         "third_party/termux/derivatives/pty-io/src/com/termux/terminal/TerminalPtyIo.java")
        self.assertEqual(origins[proof.PREFIX + "KeyHandler.java"],
                         "pinned-upstream-sources-jar:com/termux/terminal/KeyHandler.java")

    def test_source_git_blob_mutation_is_rejected(self):
        recipe, _, _, _, _, _, _ = proof.load_inputs(self.root)
        provenance = proof.json_read((self.root / proof.PROVENANCE).read_bytes())
        archive_path = provenance["artifacts"]["terminalEmulatorSources"]["path"]
        upstream = proof.java_archive.archive_entries((self.root / archive_path).read_bytes())
        upstream[proof.PREFIX + "KeyHandler.java"] += b"\n// mutation\n"
        with self.assertRaisesRegex(ValueError, "Git blob mismatch"):
            proof.select_sources(upstream, provenance, recipe, self.root)

    def test_recipe_pin_mutation_is_rejected(self):
        original = proof.read
        def changed(path):
            if Path(path) == self.root / proof.CONTRACT:
                return original(path) + b" "
            return original(path)
        with patch.object(proof, "read", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "recipe pin changed"):
                proof.load_inputs(self.root)

    def test_compiler_has_no_binary_termux_classpath_or_implicit_sources(self):
        sources = {proof.PREFIX + name + ".java": b"" for name in proof.UPSTREAM_FAMILIES | proof.ADDED_FAMILIES | {"BuildConfig"}}
        argv = proof.command(Path("/pinned/jdk"), Path("/fresh/proof/run-1"), sources)
        self.assertEqual(argv[argv.index("-classpath") + 1], "/fresh/proof/run-1/inputs/android.jar")
        self.assertEqual(argv[argv.index("-sourcepath") + 1], "/fresh/proof/run-1/empty-sourcepath")
        self.assertEqual(sum(value.endswith(".java") for value in argv), 18)
        self.assertIn("-proc:none", argv)
        self.assertIn("-implicit:none", argv)
        self.assertNotIn("base-classes.jar", " ".join(argv))
        self.assertFalse(any(value.endswith(".aar") for value in argv))

    def test_jvm_resources_and_writable_properties_are_scoped_to_each_run(self):
        work = Path("/fresh/proof/run-2")
        argv = proof.command(Path("/pinned/jdk"), work, [])
        self.assertIn("-J-Xmx512m", argv)
        self.assertIn("-J-XX:ActiveProcessorCount=2", argv)
        self.assertIn("-J-Duser.home=/fresh/proof/run-2/user-home", argv)
        self.assertIn("-J-Djava.io.tmpdir=/fresh/proof/run-2/tmp", argv)
        self.assertIn("-J-Djava.util.prefs.userRoot=/fresh/proof/run-2/prefs", argv)

    def test_child_environment_does_not_inherit_secrets_or_jvm_overrides(self):
        with patch.dict(os.environ, {"TASK_TEST_TOKEN": "do-not-inherit", "JAVA_TOOL_OPTIONS": "-javaagent:untrusted",
                                     "CLASSPATH": "untrusted.jar", "LD_PRELOAD": "/untrusted.so"}, clear=True):
            environment = proof.build_environment(Path("/fresh/proof/run-1"))
        self.assertEqual(environment["TMPDIR"], "/fresh/proof/run-1/tmp")
        self.assertEqual(environment["XDG_CACHE_HOME"], "/fresh/proof/run-1/cache")
        self.assertFalse(set(environment) & {"TASK_TEST_TOKEN", "JAVA_TOOL_OPTIONS", "CLASSPATH", "LD_PRELOAD", "HOME"})

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            proof.json_read(b'{"status":"ok","status":"bad"}')


class AccessibleApiTests(unittest.TestCase):
    def test_all_nonprivate_visibilities_are_compared(self):
        fields = [{"name": name, "descriptor": "I", "access": flags}
                  for name, flags in (("publicField", 1), ("protectedField", 4), ("packageField", 0), ("privateField", 2))]
        methods = [{"name": name, "descriptor": "()V", "access": flags}
                   for name, flags in (("publicMethod", 1), ("protectedMethod", 4), ("packageMethod", 0), ("privateMethod", 2), ("<clinit>", 8))]
        api = proof.accessible_api(class_fixture(fields=fields, methods=methods))
        self.assertEqual({x["name"] for x in api["fields"]}, {"publicField", "protectedField", "packageField"})
        self.assertEqual({x["name"] for x in api["methods"]}, {"publicMethod", "protectedMethod", "packageMethod"})

    def test_package_private_field_loss_detected(self):
        old = class_fixture(fields=[{"name": "mFontLineSpacing", "descriptor": "I", "access": 0x10}])
        new = class_fixture(fields=[{"name": "mFontLineSpacing", "descriptor": "I", "access": 0x12}])
        self.assertIn("fields", proof.api_difference(old, new))

    def test_package_private_method_loss_detected(self):
        row = {"name": "bridge", "descriptor": "()Ljava/lang/Object;", "access": 0}
        old = class_fixture(methods=[row])
        new = class_fixture(methods=[dict(row, access=2)])
        self.assertIn("methods", proof.api_difference(old, new))

    def test_generic_signatures_and_exceptions_normalize_pool_indices(self):
        fields = [{"name": "values", "descriptor": "Ljava/util/List;", "signature": "Ljava/util/List<Ljava/lang/String;>;", "access": 1}]
        methods = [{"name": "read", "descriptor": "()V", "exceptions": ["java/io/IOException"], "access": 1}]
        old = class_fixture(fields=fields, methods=methods, signature="Ljava/lang/Object;", source_file="Original.java")
        new = class_fixture(fields=fields, methods=methods, signature="Ljava/lang/Object;", extra_constant=True)
        self.assertNotEqual(old, new)
        self.assertEqual(proof.api_difference(old, new), {})

    def test_declared_exception_change_detected(self):
        row = {"name": "read", "descriptor": "()V", "access": 1, "exceptions": ["java/io/IOException"]}
        self.assertIn("methods", proof.api_difference(class_fixture(methods=[row]), class_fixture(methods=[dict(row, exceptions=[])])))

    def test_public_constant_change_detected(self):
        row = {"name": "MODE", "descriptor": "I", "access": 0x19, "constant": 2}
        self.assertIn("fields", proof.api_difference(class_fixture(fields=[row]), class_fixture(fields=[dict(row, constant=3)])))

    def test_string_constant_normalizes_pool_indices(self):
        row = {"name": "BUILD_TYPE", "descriptor": "Ljava/lang/String;", "access": 0x19, "constant": "release"}
        self.assertEqual(proof.api_difference(class_fixture(fields=[row]), class_fixture(fields=[row], extra_constant=True)), {})

    def test_unknown_annotation_metadata_cannot_be_claimed_as_compared(self):
        with self.assertRaisesRegex(ValueError, "annotation API metadata"):
            proof.accessible_api(class_fixture(annotation=True))

    def test_method_body_is_separate_from_api(self):
        row = {"name": "run", "descriptor": "()V", "access": 1, "code": b"body-one"}
        self.assertEqual(proof.api_difference(class_fixture(methods=[row]), class_fixture(methods=[dict(row, code=b"body-two")])), {})


class CandidateComparisonTests(unittest.TestCase):
    name = proof.PREFIX + "Fixture.class"

    def test_exact_pinned_hash_proves_all_api_and_body(self):
        raw = class_fixture()
        result = proof.compare_classes({self.name: raw}, {self.name: proof.sha(raw)}, {self.name}, {})
        self.assertTrue(result["allAccessibleApiMatched"])
        self.assertTrue(result["allCurrentOverlayClassHashesMatched"])
        self.assertTrue(result["allClassHashesMatched"])

    def test_changed_overlay_hash_is_not_hidden_when_api_matches(self):
        old = class_fixture(source_file="Old.java")
        new = class_fixture()
        result = proof.compare_classes({self.name: new}, {self.name: proof.sha(old)}, {self.name}, {self.name: old})
        self.assertTrue(result["allAccessibleApiMatched"])
        self.assertFalse(result["allCurrentOverlayClassHashesMatched"])
        self.assertFalse(result["allClassHashesMatched"])
        self.assertIn(self.name, result["overlayClassHashDifferences"])

    def test_changed_overlay_without_reference_is_explicitly_unvalidated(self):
        old = class_fixture(source_file="Old.java")
        new = class_fixture()
        result = proof.compare_classes({self.name: new}, {self.name: proof.sha(old)}, {self.name}, {})
        self.assertFalse(result["allAccessibleApiMatched"])
        self.assertEqual(result["accessibleApiComparisonUnavailable"], [self.name])

    def test_retained_source_recompile_may_change_debug_bytes_but_not_api(self):
        old = class_fixture(source_file="Old.java")
        new = class_fixture()
        result = proof.compare_classes({self.name: new}, {self.name: proof.sha(old)}, set(), {self.name: old})
        self.assertTrue(result["allAccessibleApiMatched"])
        self.assertEqual(result["overlayClassHashDifferences"], {})
        self.assertFalse(result["allClassHashesMatched"])
        self.assertFalse(result["retainedClassByteEquivalenceVerified"])
        self.assertIn(self.name, result["retainedClassHashDifferences"])

    def test_missing_or_extra_class_rejected(self):
        raw = class_fixture()
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            proof.compare_classes({}, {self.name: proof.sha(raw)}, set(), {})
        with self.assertRaisesRegex(ValueError, "inventory changed"):
            proof.compare_classes({self.name: raw}, {}, set(), {})

    def test_java_class_internal_name_must_match_archive_entry(self):
        raw = class_fixture(name="com/termux/terminal/Wrong")
        with self.assertRaisesRegex(ValueError, "identity/Java 8"):
            proof.compare_classes({self.name: raw}, {self.name: proof.sha(raw)}, set(), {})

    def test_reference_engine_is_strictly_pinned_and_read_only(self):
        raw = class_fixture()
        jar = proof.java_archive.deterministic_zip({self.name: raw})
        aar = proof.java_archive.deterministic_zip({"classes.jar": jar})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "reference.aar"
            path.write_bytes(aar)
            references = {}
            info = proof.add_reference_engine(path, {self.name: proof.sha(raw)}, references)
            self.assertFalse(info["compilerInput"])
            self.assertEqual(path.read_bytes(), aar)
            self.assertEqual(references, {self.name: raw})
            with self.assertRaisesRegex(ValueError, "pinned classes"):
                proof.add_reference_engine(path, {self.name: "0" * 64}, {})

    def test_jni_owned_process_v2_contract_is_exact(self):
        recipe = proof.json_read((TOOLS.parent / proof.CONTRACT).read_bytes())
        methods = [dict(row, access=0x108) for row in recipe["jniReplacement"]["methods"]]
        raw = class_fixture(name="com/termux/terminal/JNI", access=0x30,
                            fields=[{"name": "API_VERSION", "descriptor": "I", "access": 0x18, "constant": 2}], methods=methods)
        result = proof.jni_inspection({proof.PREFIX + "JNI.class": raw}, recipe)
        self.assertEqual(len(result["methods"]), 12)
        self.assertFalse(result["nativeRuntimeExecuted"])
        mutated = copy.deepcopy(recipe)
        mutated["jniReplacement"]["methods"][0]["descriptor"] = "()J"
        with self.assertRaisesRegex(ValueError, "inventory/descriptors"):
            proof.jni_inspection({proof.PREFIX + "JNI.class": raw}, mutated)


class OutputIsolationTests(unittest.TestCase):
    def test_source_tree_output_rejected_before_any_compiler_or_input_load(self):
        root = TOOLS.parent
        with patch.object(proof, "load_inputs", side_effect=AssertionError("input load must not occur")):
            with self.assertRaisesRegex(ValueError, "outside the source"):
                proof.build(root, Path("/unused-jdk"), Path("/unused-android.jar"), Path("/unused-vendor.tgz"), root / "new-proof")

    def test_source_ancestor_output_rejected(self):
        root = TOOLS.parent
        with self.assertRaisesRegex(ValueError, "outside the source"):
            proof.build(root, Path("/unused-jdk"), Path("/unused-android.jar"), Path("/unused-vendor.tgz"), root.parent)

    def test_existing_directory_is_not_reused_or_deleted(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            marker = output / "preserve.txt"
            marker.write_text("user file", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "fresh output"):
                proof.build(TOOLS.parent, Path("/unused-jdk"), Path("/unused-android.jar"), Path("/unused-vendor.tgz"), output)
            self.assertEqual(marker.read_text(encoding="utf-8"), "user file")

    def test_symlink_ancestor_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "real").mkdir()
            (root / "alias").symlink_to(root / "real", target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlinked protected path"):
                proof.checked(root / "alias/new-output")


if __name__ == "__main__":
    unittest.main()
