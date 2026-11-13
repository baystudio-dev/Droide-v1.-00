"""Read-only contracts and mocked compiler fixtures; never javac/target execution."""
from __future__ import annotations

import contextlib
import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import fdroid_termux_view_source_build as proof
from test_fdroid_termux_source_build import class_fixture


def view_classes():
    return {name: class_fixture(name=name[:-6]) for name in proof.EXPECTED_CLASSES}


def compiler_classes():
    classes = view_classes()
    for name in proof.R_CLASSES:
        fields = [{"name": field, "descriptor": "I", "access": 0x0009}
                  for field in sorted(proof.R_FIELDS.get(name[:-6], ()))]
        classes[name] = class_fixture(name=name[:-6], fields=fields)
    return classes


def resource_rows():
    return [{"method_name": "fixtureRead", "method_descriptor": "()V", "bytecode_offset": number * 3,
             "owner": owner, "field_name": field, "field_descriptor": "I"}
            for number, (owner, field) in enumerate((owner, field) for owner, fields in sorted(proof.R_FIELDS.items())
                                                   for field in sorted(fields))]


def fixture_resource_reads(raw):
    name = proof.java_archive.parse_class(raw)["name"]
    return resource_rows() if name == "com/termux/view/TerminalView" else []


class SourceInputTests(unittest.TestCase):
    root = TOOLS.parent

    def select_inputs(self):
        provenance = proof.resources.parse_json((self.root / proof.emulator.PROVENANCE).read_bytes())
        entries = proof.java_archive.archive_entries((self.root / proof.SOURCE_JAR).read_bytes())
        return entries, provenance

    def test_real_closure_is_seven_original_sources_and_two_generated(self):
        with patch.object(proof.subprocess, "run", side_effect=AssertionError("no compiler allowed")):
            inputs = proof.load_inputs(self.root)
        self.assertEqual(set(inputs["sources"]), set(proof.PINS) | {proof.PREFIX + "R.java", proof.PREFIX + "BuildConfig.java"})
        self.assertEqual(len(inputs["references"]), 19)
        self.assertEqual(len(inputs["emulatorExpected"]), 40)
        self.assertEqual(inputs["inputs"][proof.SOURCE_JAR], proof.SOURCE_SHA256)
        self.assertEqual(inputs["sources"][proof.PREFIX + "BuildConfig.java"], proof.BUILD_CONFIG_SOURCE)
        self.assertEqual(inputs["sources"][proof.PREFIX + "R.java"], proof.R_SOURCE)
        self.assertEqual(set(inputs["resources"]), set(proof.resources.PINS))

    def test_modified_source_fails_exact_sha256(self):
        entries, provenance = self.select_inputs()
        entries[proof.PREFIX + "TerminalView.java"] += b"\n// changed\n"
        with self.assertRaisesRegex(ValueError, "Java source SHA-256 mismatch"):
            proof.select_sources(entries, provenance)

    def test_git_blob_is_checked_independently_of_sha256(self):
        entries, provenance = self.select_inputs()
        with patch.object(proof.resources, "git_blob", return_value="0" * 40):
            with self.assertRaisesRegex(ValueError, "Java source Git blob mismatch"):
                proof.select_sources(entries, provenance)

    def test_source_provenance_mapping_and_upstream_are_pinned(self):
        entries, provenance = self.select_inputs()
        changed = copy.deepcopy(provenance)
        changed["upstream"]["commit"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "immutable Java source upstream"):
            proof.select_sources(entries, changed)
        changed = copy.deepcopy(provenance)
        changed["artifacts"]["terminalViewSources"]["gitBlobSha1ByPath"][proof.PREFIX + "TerminalView.java"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "provenance mapping/pins"):
            proof.select_sources(entries, changed)

    def test_extra_java_or_class_payload_is_rejected(self):
        entries, provenance = self.select_inputs()
        for extra in ("com/termux/view/Extra.java", "com/termux/view/Extra.class"):
            changed = dict(entries, **{extra: b"unexpected"})
            with self.subTest(extra=extra), self.assertRaisesRegex(ValueError, "archive inventory"):
                proof.select_sources(changed, provenance)

    def test_missing_original_source_is_rejected(self):
        entries, provenance = self.select_inputs()
        entries.pop(proof.PREFIX + "TerminalViewClient.java")
        with self.assertRaisesRegex(ValueError, "archive inventory"):
            proof.select_sources(entries, provenance)

    def test_archive_hash_is_not_inferred_from_provenance(self):
        original = proof.read
        def changed(path, bound=proof.MAX_TEXT):
            raw = original(path, bound)
            return raw + b"changed" if Path(path) == self.root / proof.SOURCE_JAR else raw
        with patch.object(proof, "read", side_effect=changed):
            with self.assertRaisesRegex(ValueError, "source archive SHA-256"):
                proof.load_inputs(self.root)

    def test_resource_provenance_byte_identity_is_pinned(self):
        resources, _ = proof.resources.source_inputs(self.root)
        with patch.object(proof.resources, "source_inputs", return_value=(resources, "0" * 64)):
            with self.assertRaisesRegex(ValueError, "resource provenance SHA-256"):
                proof.load_inputs(self.root)

    def test_current_annotation_coordinate_and_hash_remain_fixed(self):
        raw = (self.root / proof.VERIFICATION_METADATA).read_bytes()
        proof.validate_annotation_pin(raw)
        with self.assertRaisesRegex(ValueError, "annotation Gradle hash pin changed"):
            proof.validate_annotation_pin(raw.replace(proof.ANNOTATION_SHA256.encode(), b"0" * 64))
        with self.assertRaisesRegex(ValueError, "annotation coordinate"):
            proof.validate_annotation_pin(raw.replace(b'name="annotation-jvm" version="1.10.0"',
                                                     b'name="annotation-jvm" version="1.10.1"'))

    def test_protected_read_rejects_symlinks_and_oversized_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            source = base / "source"
            source.write_bytes(b"12345")
            link = base / "alias"
            link.symlink_to(source)
            with self.assertRaisesRegex(ValueError, "symlinked protected"):
                proof.read(link)
            with self.assertRaisesRegex(ValueError, "byte bound"):
                proof.read(source, 4)


class CompilerContractTests(unittest.TestCase):
    def test_classpath_contains_only_pinned_android_annotation_and_source_emulator(self):
        work = Path("/scratch/view/run-1")
        argv = proof.command(Path("/toolchain/jdk"), work, {**proof.PINS, proof.PREFIX + "R.java": b"", proof.PREFIX + "BuildConfig.java": b""})
        self.assertEqual(argv[argv.index("-classpath") + 1].split(os.pathsep),
                         [str(work / "inputs" / name) for name in ("android.jar", "annotation.jar", "emulator-from-source.jar")])
        self.assertEqual(sum(value.endswith(".java") for value in argv), 9)
        self.assertEqual(argv[argv.index("-sourcepath") + 1], str(work / "empty-sourcepath"))
        self.assertFalse(any(value.endswith(".aar") for value in argv))
        self.assertNotIn("app/libs", " ".join(argv))

    def test_no_annotation_processors_implicit_sources_or_unbounded_jvm(self):
        argv = proof.command(Path("/toolchain/jdk"), Path("/scratch/run"), [])
        for option in ("--release", "8", "-g", "-parameters", "-proc:none", "-implicit:none", "-J-Xmx512m", "-J-XX:ActiveProcessorCount=2"):
            self.assertIn(option, argv)
        self.assertNotIn("-g:none", argv)
        self.assertEqual(proof.COMPILER_TIMEOUT, 120)
        self.assertIn("-J-Duser.home=/scratch/run/user-home", argv)
        self.assertIn("-J-Djava.io.tmpdir=/scratch/run/tmp", argv)
        self.assertIn("-J-Djava.util.prefs.userRoot=/scratch/run/prefs", argv)

    def test_environment_does_not_inherit_secrets_or_loader_overrides(self):
        with patch.dict(os.environ, {"TEST_SECRET": "not-inherited", "JAVA_TOOL_OPTIONS": "-javaagent:bad",
                                     "JDK_JAVA_OPTIONS": "bad", "CLASSPATH": "bad.jar", "LD_PRELOAD": "bad.so",
                                     "HTTP_PROXY": "untrusted"}, clear=True):
            environment = proof.build_environment(Path("/scratch/run"))
        self.assertEqual(set(environment), {"PATH", "LANG", "LC_ALL", "TZ", "HOME", "TMPDIR",
                                            "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"})
        self.assertEqual(environment["HOME"], "/scratch/run/user-home")
        self.assertEqual(environment["TMPDIR"], "/scratch/run/tmp")
        self.assertTrue(all(value.startswith("/scratch/run/") for key, value in environment.items()
                            if key in {"HOME", "TMPDIR", "XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"}))

    def test_compile_mock_receives_bounded_offline_contract_and_packages_only_view(self):
        resources, _ = proof.resources.source_inputs(TOOLS.parent)
        sources = {proof.PREFIX + "R.java": proof.R_SOURCE, proof.PREFIX + "BuildConfig.java": proof.BUILD_CONFIG_SOURCE}
        compiled = compiler_classes()
        def fake_compiler(argv, **kwargs):
            self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)
            self.assertEqual(kwargs["timeout"], 120)
            self.assertFalse(kwargs["check"])
            self.assertEqual(kwargs["env"], proof.build_environment(kwargs["cwd"]))
            for name, raw in compiled.items():
                path = kwargs["cwd"] / "compiled" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
            return subprocess.CompletedProcess(argv, 0)
        with tempfile.TemporaryDirectory() as directory, patch.object(proof.subprocess, "run", side_effect=fake_compiler) as run:
            work = Path(directory) / "run"
            classes, jar, aar = proof.compile_once(work, Path("/toolchain/jdk"), b"android", b"annotation", b"source-emulator", sources, resources)
            run.assert_called_once()
            self.assertEqual(set(classes), proof.EXPECTED_CLASSES)
            self.assertEqual(set(proof.java_archive.archive_entries(jar)), proof.EXPECTED_CLASSES)
            self.assertEqual(proof.java_archive.archive_entries(aar)["classes.jar"], jar)
            invocation = json.loads((work / "command.json").read_text())
            self.assertEqual(invocation["exit"], 0)
            self.assertTrue(invocation["annotationProcessorsDisabled"])
            self.assertFalse(invocation["targetClassesExecuted"])

    def test_compiler_failure_writes_logs_but_no_candidate_archive(self):
        resources, _ = proof.resources.source_inputs(TOOLS.parent)
        def failed(argv, **kwargs):
            kwargs["stderr"].write(b"mock compiler failure\n")
            return subprocess.CompletedProcess(argv, 1)
        with tempfile.TemporaryDirectory() as directory, patch.object(proof.subprocess, "run", side_effect=failed):
            work = Path(directory) / "run"
            with self.assertRaisesRegex(ValueError, "View source compiler failed"):
                proof.compile_once(work, Path("/toolchain/jdk"), b"android", b"annotation", b"emulator", {}, resources)
            self.assertFalse((work / "terminal-view-source-proof.jar").exists())
            self.assertFalse((work / "terminal-view-source-proof.aar").exists())
            self.assertEqual((work / "javac.stderr").read_bytes(), b"mock compiler failure\n")

    def test_compiler_timeout_is_not_retried_or_accepted(self):
        resources, _ = proof.resources.source_inputs(TOOLS.parent)
        error = subprocess.TimeoutExpired("mock-javac", 120)
        with tempfile.TemporaryDirectory() as directory, patch.object(proof.subprocess, "run", side_effect=error) as run:
            work = Path(directory) / "run"
            with self.assertRaises(subprocess.TimeoutExpired):
                proof.compile_once(work, Path("/toolchain/jdk"), b"android", b"annotation", b"emulator", {}, resources)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(json.loads((work / "command.json").read_text())["failure"], "TimeoutExpired")


class CompileSymbolTests(unittest.TestCase):
    def test_exact_three_nonfinal_r_classes_are_excluded(self):
        selected = proof.select_packaged_classes(compiler_classes())
        self.assertEqual(set(selected), proof.EXPECTED_CLASSES)
        self.assertEqual(len(selected), 19)
        self.assertFalse(set(selected) & proof.R_CLASSES)

    def test_final_resource_field_is_rejected_even_without_constant_value(self):
        classes = compiler_classes()
        owner = "com/termux/view/R$string"
        fields = [{"name": field, "descriptor": "I", "access": 0x0019} for field in sorted(proof.R_FIELDS[owner])]
        classes[owner + ".class"] = class_fixture(name=owner, fields=fields)
        with self.assertRaisesRegex(ValueError, "non-final without ConstantValue"):
            proof.select_packaged_classes(classes)

    def test_constant_value_is_rejected_even_on_nonfinal_resource_field(self):
        classes = compiler_classes()
        owner = "com/termux/view/R$drawable"
        fields = [{"name": field, "descriptor": "I", "access": 0x0009, "constant": 0} for field in sorted(proof.R_FIELDS[owner])]
        classes[owner + ".class"] = class_fixture(name=owner, fields=fields)
        with self.assertRaisesRegex(ValueError, "non-final without ConstantValue"):
            proof.select_packaged_classes(classes)

    def test_missing_resource_field_fails_inventory(self):
        classes = compiler_classes()
        name = "com/termux/view/R$string.class"
        classes[name] = class_fixture(name=name[:-6])
        with self.assertRaisesRegex(ValueError, "R field inventory"):
            proof.select_packaged_classes(classes)

    def test_dependency_or_extra_symbol_class_cannot_be_silently_stripped(self):
        for name in ("com/termux/terminal/TerminalEmulator.class", "androidx/annotation/RequiresApi.class", "com/termux/view/R$id.class"):
            classes = compiler_classes()
            classes[name] = class_fixture(name=name[:-6])
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "View class inventory"):
                proof.select_packaged_classes(classes)

    def test_internal_name_and_java_version_cannot_disagree_with_inventory(self):
        classes = view_classes()
        name = proof.PREFIX + "BuildConfig.class"
        classes[name] = class_fixture(name="com/termux/view/Unexpected")
        with self.assertRaisesRegex(ValueError, "identity/Java 8"):
            proof.validate_inventory(classes)
        classes = view_classes()
        classes[name] = classes[name][:6] + b"\x00\x3d" + classes[name][8:]
        with self.assertRaisesRegex(ValueError, "identity/Java 8"):
            proof.validate_inventory(classes)


class ResourceComparisonTests(unittest.TestCase):
    def test_actual_pinned_reference_has_exact_five_instruction_reads(self):
        inputs = proof.load_inputs(TOOLS.parent)
        result = proof.resource_accesses(inputs["references"])
        self.assertEqual(result["getstaticCount"], 5)
        self.assertEqual(result["symbolCount"], 5)
        self.assertEqual(sum(len(method["reads"]) for method in result["methods"]), 5)

    def test_no_actual_getstatic_is_rejected_not_constant_pool_inferred(self):
        with patch.object(proof.metadata, "resource_field_references", return_value=[]):
            with self.assertRaisesRegex(ValueError, "actual resource reads missing"):
                proof.resource_accesses({"fixture.class": b"unused-pool-only"})

    def test_offsets_can_shift_but_per_method_order_and_multiplicity_are_retained(self):
        rows = resource_rows()
        moved = [dict(row, bytecode_offset=row["bytecode_offset"] + 100) for row in rows]
        def inspect(value):
            return rows if value == b"old" else moved
        with patch.object(proof.metadata, "resource_field_references", side_effect=inspect):
            self.assertEqual(proof.resource_accesses({"fixture.class": b"old"}), proof.resource_accesses({"fixture.class": b"new"}))
        with patch.object(proof.metadata, "resource_field_references", side_effect=[rows, rows + [rows[0]]]):
            old = proof.resource_accesses({"fixture.class": b"old"})
            new = proof.resource_accesses({"fixture.class": b"new"})
            self.assertNotEqual(old, new)
            self.assertEqual(new["getstaticCount"], 6)
        with patch.object(proof.metadata, "resource_field_references", side_effect=[rows, list(reversed(rows))]):
            self.assertNotEqual(proof.resource_accesses({"fixture.class": b"old"}), proof.resource_accesses({"fixture.class": b"new"}))

    def test_resource_use_moved_to_another_method_is_a_difference(self):
        rows = resource_rows()
        moved = [dict(row, method_name="differentMethod") for row in rows]
        with patch.object(proof.metadata, "resource_field_references", side_effect=[rows, moved]):
            self.assertNotEqual(proof.resource_accesses({"fixture.class": b"old"}), proof.resource_accesses({"fixture.class": b"new"}))

    def test_unknown_resource_name_or_descriptor_fails_closed(self):
        rows = resource_rows()
        for change in ({"field_name": "unknown"}, {"field_descriptor": "J"}, {"owner": "com/termux/view/R$id"}):
            changed = [dict(rows[0], **change), *rows[1:]]
            with self.subTest(change=change), patch.object(proof.metadata, "resource_field_references", return_value=changed):
                with self.assertRaisesRegex(ValueError, "unexpected View resource GETSTATIC"):
                    proof.resource_accesses({"fixture.class": b"fake"})


class ApiAndPackagingTests(unittest.TestCase):
    def test_reference_self_comparison_includes_annotations_debug_and_parameters(self):
        inputs = proof.load_inputs(TOOLS.parent)
        result = proof.compare_classes(inputs["references"], inputs["references"])
        self.assertTrue(result["allAccessibleApiMatched"])
        self.assertTrue(result["accessibleDeclarationAbiMatched"])
        self.assertTrue(result["allClassHashesMatched"])
        self.assertTrue(result["resourceGetstaticMatched"])
        self.assertFalse(result["methodBodyEquivalenceClaimed"])
        counts = result["referenceDebugMetadata"]["attributeCounts"]
        self.assertEqual(counts["MethodParameters"], 119)
        self.assertEqual(counts["LineNumberTable"], 153)
        self.assertEqual(counts["LocalVariableTable"], 151)
        self.assertEqual(counts["StackMapTable"], 73)

    def test_same_api_does_not_hide_full_byte_differences(self):
        before, after = view_classes(), view_classes()
        name = proof.PREFIX + "TerminalRenderer.class"
        after[name] = class_fixture(name=name[:-6], source_file="DifferentDebugName.java")
        with patch.object(proof.metadata, "resource_field_references", side_effect=fixture_resource_reads):
            result = proof.compare_classes(after, before)
        self.assertTrue(result["allAccessibleApiMatched"])
        self.assertFalse(result["allClassHashesMatched"])
        self.assertEqual(set(result["allClassHashDifferences"]), {name})
        self.assertFalse(result["methodBodyEquivalenceClaimed"])

    def test_package_private_field_loss_is_not_disguised_as_metadata_only(self):
        before, after = view_classes(), view_classes()
        name = proof.PREFIX + "TerminalRenderer.class"
        row = {"name": "mFontLineSpacing", "descriptor": "I", "access": 0x10}
        before[name] = class_fixture(name=name[:-6], fields=[row])
        after[name] = class_fixture(name=name[:-6], fields=[dict(row, access=0x12)])
        with patch.object(proof.metadata, "resource_field_references", side_effect=fixture_resource_reads):
            result = proof.compare_classes(after, before)
        self.assertFalse(result["allAccessibleApiMatched"])
        self.assertFalse(result["accessibleDeclarationAbiMatched"])
        self.assertIn(name, result["accessibleDeclarationAbiDifferenceClasses"])
        self.assertIn(name, result["accessibleApiDifferences"])

    def test_unsupported_annotation_metadata_blocks_comparison(self):
        with patch.object(proof.metadata, "api_difference", side_effect=ValueError("unsupported annotation metadata")):
            with self.assertRaisesRegex(ValueError, "unsupported annotation metadata"):
                proof.compare_classes(view_classes(), view_classes())

    def test_compact_metadata_diagnostic_preserves_parameter_names_flags_and_order(self):
        old = {"name": "<init>", "descriptor": "(ZLandroid/view/MotionEvent;)V", "access": 0,
               "constant": None, "metadata": {"parameters": [{"name": "val$e2", "access": 0x1010},
                                                            {"name": "val$mouseTracking", "access": 0x1010}]}}
        new = copy.deepcopy(old)
        new["metadata"]["parameters"].reverse()
        delta = {"com/termux/view/TerminalView$1$1.class": {"methods": {"reference": [old], "candidate": [new]}}}
        compact = proof.compact_api_differences(delta)
        self.assertEqual(compact["changedPathCount"], 1)
        self.assertEqual(compact["entries"][0]["path"], "methods.metadata.parameters")
        self.assertEqual(compact["entries"][0]["reference"], old["metadata"]["parameters"])
        self.assertEqual(compact["entries"][0]["candidate"], new["metadata"]["parameters"])
        self.assertFalse(compact["truncated"])

    def test_compact_diagnostic_is_bounded_without_claiming_omitted_deltas_equal(self):
        delta = {"class" + str(number): {"metadata": {"reference": {"signature": "old" * 500},
                                                     "candidate": {"signature": "new" * 500}}}
                 for number in range(20)}
        compact = proof.compact_api_differences(delta, budget=300)
        self.assertTrue(compact["truncated"])
        self.assertEqual(compact["changedPathCount"], 20)
        self.assertLessEqual(len(json.dumps(compact["entries"], ensure_ascii=True, separators=(",", ":")).encode("ascii")), 300)

    def test_proof_aar_is_deterministic_and_contains_original_resources_license(self):
        sources, _ = proof.resources.source_inputs(TOOLS.parent)
        jar = proof.java_archive.deterministic_zip(view_classes())
        first = proof.proof_aar(jar, sources)
        self.assertEqual(first, proof.proof_aar(jar, dict(reversed(list(sources.items())))))
        entries = proof.java_archive.archive_entries(first)
        self.assertEqual(set(entries), {"classes.jar", "AndroidManifest.xml", "R.txt", proof.AAR_METADATA_PATH,
                                        proof.LICENSE_PATH} | set(proof.resources.RESOURCE_PATHS))
        self.assertEqual(entries["classes.jar"], jar)
        self.assertEqual(entries[proof.LICENSE_PATH], sources["LICENSE.md"])
        for name in proof.resources.RESOURCE_PATHS:
            self.assertEqual(entries[name], sources[name])
        proof.resources.manifest_reference(entries["AndroidManifest.xml"])
        self.assertEqual(set(entries["R.txt"].decode().splitlines()), proof.resources.EXPECTED_R)
        self.assertFalse(any(name.startswith(("jni/", "assets/", "libs/")) for name in entries))
        self.assertNotIn("res/values/values.xml", entries)

    def test_proof_aar_cannot_embed_compiled_r_or_changed_license(self):
        sources, _ = proof.resources.source_inputs(TOOLS.parent)
        bad_jar = proof.java_archive.deterministic_zip(compiler_classes())
        with self.assertRaisesRegex(ValueError, "View class inventory"):
            proof.proof_aar(bad_jar, sources)
        jar = proof.java_archive.deterministic_zip(view_classes())
        with self.assertRaisesRegex(ValueError, "resource/license identity"):
            proof.proof_aar(jar, dict(sources, **{"LICENSE.md": b"license removed"}))


class ReceiptAndPathTests(unittest.TestCase):
    def test_output_must_be_fresh_outside_source_and_compiler(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root, home, tooling = base / "src", base / "jdk", base / "tooling"
            root.mkdir()
            home.mkdir()
            tooling.mkdir()
            protected = (root, home, tooling / "android.jar", tooling / "annotation.jar", tooling / "jdk.tar.gz")
            for path in (root / "new", home / "new", base, tooling / "android.jar", root):
                with self.subTest(path=path), self.assertRaisesRegex(ValueError, "overlaps"):
                    proof.output_path(*protected, path)
            fresh = base / "proof"
            self.assertEqual(proof.output_path(*protected, fresh), fresh)
            fresh.mkdir()
            sentinel = fresh / "sentinel"
            sentinel.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "fresh output"):
                proof.output_path(*protected, fresh)
            self.assertEqual(sentinel.read_bytes(), b"keep")

    def test_bad_annotation_hash_stops_before_jdk_verification_and_any_compiler(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root, home = base / "src", base / "jdk"
            root.mkdir()
            home.mkdir()
            android, annotation, vendor = (base / name for name in ("android.jar", "annotation.jar", "jdk.tar.gz"))
            android.write_bytes(b"android")
            annotation.write_bytes(b"wrong annotation")
            vendor.write_bytes(b"vendor")
            output = base / "proof"
            with patch.object(proof, "load_inputs", return_value={"inputs": {}}), \
                    patch.object(proof, "ANDROID_SHA256", proof.sha(b"android")), \
                    patch.object(proof.terminal_jdk_v2, "verify") as verify, \
                    patch.object(proof.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "pinned annotation-jvm"):
                    proof.build(root, home, android, annotation, vendor, output)
            verify.assert_not_called()
            run.assert_not_called()
            compact = json.loads((output / "SUMMARY.json").read_text())
            self.assertEqual(compact["status"], "VIEW_SOURCE_PROOF_FAILED")
            self.assertFalse(compact["fullViewJavaSourceRebuild"])
            self.assertFalse(compact["fullEmulatorJavaSourceRebuild"])
            self.assertFalse(compact["inputsUnchanged"])
            self.assertEqual(compact["outputs"], [])
            self.assertFalse(compact["fdroidAcceptanceVerified"])
            self.assertTrue((output / "receipt.json").is_file())

    def test_failure_diagnostics_read_only_fixed_scratch_stderr_and_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / "run-2").mkdir()
            (base / "run-1").mkdir()
            (base / "run-2/javac.stderr").write_bytes(b"x" * 5000)
            (base / "run-1/javac.stderr").write_bytes(b"older error")
            (base / "unrelated-private-data").write_bytes(b"never read")
            result = proof.failure_diagnostics(base)
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]["path"], "run-2/javac.stderr")
            self.assertEqual(len(result[0]["text"]), 4096)
            self.assertTrue(result[0]["truncated"])

    def test_emulator_default_annotation_guard_remains_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "annotation API metadata"):
            proof.emulator.accessible_api(class_fixture(annotation=True))


if __name__ == "__main__":
    unittest.main()
