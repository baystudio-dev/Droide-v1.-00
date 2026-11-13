"""Mocked orchestration and publication contracts; no compiler/ELF execution."""
from __future__ import annotations

import inspect
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import fdroid_native_source_build as builder


class NativeSourceTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="native-source-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root, self.ndk, self.loader_ndk = (self.base / name for name in ("source", "ndk", "loader-ndk"))
        for directory in (self.root, self.ndk, self.loader_ndk):
            directory.mkdir()
        self.native = self.root / builder.NATIVE_DIRECTORY
        self.native.mkdir(parents=True)
        self.marker = self.root / "source-input"
        self.marker.write_bytes(b"original source")
        self.output = self.base / "output"
        self.payloads = {name: ("synthetic " + name).encode() for name in builder.OUTPUTS}
        self.identities = {name: (len(raw), builder.loader.sha(raw)) for name, raw in self.payloads.items()}
        self.engine_after = lambda report: None
        self.cli_after = lambda report: None

    def patches(self):
        replacements = (
            patch.object(builder, "OUTPUTS", self.identities),
            patch.object(builder, "source_inputs", side_effect=lambda root: {"source-input": self.marker.read_bytes()}),
            patch.object(builder.cli, "build_sources", side_effect=self.cli_build),
            patch.object(builder.engine, "build_sources", side_effect=self.engine_build),
            patch.object(builder.cli, "validate_elf", return_value={}),
            patch.object(builder.engine, "inspect_engine", return_value={}),
            patch.object(builder.loader, "inspect_loader", return_value={}),
        )
        for replacement in replacements:
            replacement.start()
            self.addCleanup(replacement.stop)

    def report(self, cli=False):
        result = {"status": "isolated-source-build-only" if cli else builder.engine.SOURCE_SUCCESS,
                  "matchesPinnedOutputIdentity": True, "inputsAndToolsUnchanged": True,
                  "retainedArtifactInputs": [], "baselineComparisonPerformed": False,
                  "matchesBundledBytes": False, "packagedPayloadsChanged": False,
                  "publicationApproved": False, "androidRuntimeCompatibilityVerified": False}
        if cli:
            result.update(reproducibleAcrossTwoBuildDirectories=True, outputFilesUnchanged=True,
                          arm64OutputExecuted=False, fdroidSourceBuildAcceptanceVerified=False)
        else:
            result.update(sourceBuildSucceeded=True, rawReproducible=True, postStripReproducible=True,
                          loaderCouplingVerified=True, loaderWrapperTransformsVerified=True,
                          targetOutputExecuted=False, fdroidAcceptanceVerified=False, arm64PokedataOffset=1120)
        return result

    def write_outputs(self, names):
        for label in ("first", "second"):
            for name in names:
                path = builder.output_path(self.output, label, name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(self.payloads[name])
                path.chmod(0o600)

    def cli_build(self, ndk, output, root):
        self.assertEqual((self.ndk, self.output / "cli", self.root), (ndk, output, root))
        self.assertEqual([], list(self.native.iterdir()))
        self.write_outputs(["libdroide_cli.so"])
        report = self.report(cli=True)
        self.cli_after(report)
        (output / "source-build-manifest.json").write_text(json.dumps(report, indent=2) + "\n")
        return report

    def engine_build(self, ndk, loader_ndk, output, root):
        self.assertEqual((self.ndk, self.loader_ndk, self.output / "proot", self.root),
                         (ndk, loader_ndk, output, root))
        self.assertEqual([], list(self.native.iterdir()))
        self.write_outputs([name for name in self.payloads if name != "libdroide_cli.so"])
        report = self.report()
        self.engine_after(report)
        (output / "source-build-summary.json").write_text(json.dumps(report, separators=(",", ":")) + "\n")
        return report

    def run_build(self):
        return builder.build_sources(self.ndk, self.loader_ndk, self.output, self.root)

    def test_fixed_inventory_and_no_baseline_or_expected_pin_override(self):
        self.assertEqual(["ndk_path", "loader_ndk_path", "output_dir", "root"],
                         list(inspect.signature(builder.build_sources).parameters))
        self.assertEqual({"libdroide_cli.so": 4528, "libdroide_proot.so": 265648,
                          "libproot-loader.so": 18136, "libproot-loader32.so": 6244},
                         {name: values[0] for name, values in builder.OUTPUTS.items()})
        self.assertEqual(builder.cli.OUTPUT_SHA256, builder.OUTPUTS["libdroide_cli.so"][1])
        self.assertEqual(builder.engine.ENGINE_SHA256, builder.OUTPUTS["libdroide_proot.so"][1])
        for _, (name, digest) in builder.loader.LOADERS.items():
            self.assertEqual(digest, builder.OUTPUTS[name][1])

    def test_two_complete_builds_with_no_retained_inputs_and_no_promotion(self):
        self.patches()
        report = self.run_build()
        self.assertEqual(builder.SUCCESS, report["status"])
        self.assertTrue(report["sourceBuildSucceeded"])
        self.assertTrue(report["bothBuildsMatchPinnedBytes"])
        self.assertTrue(report["inputsAndOutputsUnchanged"])
        self.assertEqual([], report["retainedArtifactInputs"])
        self.assertEqual([], list(self.native.iterdir()))
        self.assertEqual(["first", "second"], [row["name"] for row in report["builds"]])
        for row in report["builds"]:
            self.assertEqual(set(self.payloads), set(row["outputs"]))
        for name in ("baselineComparisonPerformed", "packagedPayloadsChanged", "publicationApproved",
                     "targetOutputExecuted", "androidRuntimeCompatibilityVerified", "fdroidAcceptanceVerified",
                     "ndkArchivesAuthenticated"):
            self.assertIs(report[name], False)
        final = self.output / "SUMMARY.json"
        self.assertEqual(report, json.loads(final.read_text()))
        self.assertLessEqual(final.stat().st_size, builder.SUMMARY_LIMIT)

    def test_nonempty_native_reference_directory_fails_before_build(self):
        self.patches()
        (self.native / "libdroide_cli.so").write_bytes(b"must never read")
        with self.assertRaisesRegex(builder.SourceBuildError, "must be empty"):
            self.run_build()
        builder.cli.build_sources.assert_not_called()
        builder.engine.build_sources.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_symlinked_reference_directory_cannot_mask_real_input_access(self):
        self.patches()
        self.native.rmdir()
        self.native.symlink_to(self.ndk, target_is_directory=True)
        with self.assertRaisesRegex(builder.SourceBuildError, "real directory"):
            self.run_build()
        builder.cli.build_sources.assert_not_called()

    def test_both_ndks_and_source_must_be_disjoint(self):
        self.patches()
        with self.assertRaisesRegex(builder.SourceBuildError, "overlap"):
            builder.build_sources(self.ndk, self.ndk, self.output, self.root)
        builder.cli.build_sources.assert_not_called()

    def test_core_cannot_claim_retained_comparison_or_failed_status(self):
        for key, value in (("retainedArtifactInputs", ["hidden reference"]), ("baselineComparisonPerformed", True),
                           ("matchesBundledBytes", True), ("sourceBuildSucceeded", False),
                           ("publicationApproved", True), ("status", "failed"),
                           ("error", "failure after compilation")):
            with self.subTest(key=key), self.assertRaises(builder.SourceBuildError):
                report = self.report()
                report[key] = value
                builder.require_core_limits(report)

    def test_every_native_output_in_both_builds_is_independently_checked(self):
        self.patches()
        for index, (label, name) in enumerate((label, name) for label in ("first", "second") for name in self.payloads):
            self.output = self.base / ("output-" + str(index))
            def mutate(_report):
                path = builder.output_path(self.output, label, name)
                path.write_bytes(b"x" * len(self.payloads[name]))
            self.engine_after = mutate
            with self.subTest(label=label, name=name), self.assertRaisesRegex(builder.SourceBuildError, "fixed identity"):
                self.run_build()
            failed = json.loads((self.output / "SUMMARY.json").read_text())
            self.assertEqual("failed", failed["status"])
            self.assertFalse(failed["sourceBuildSucceeded"])

    def test_missing_output_is_not_substituted_from_any_reference(self):
        self.patches()
        self.engine_after = lambda _: builder.output_path(self.output, "second", "libdroide_cli.so").unlink()
        with self.assertRaises(builder.SourceBuildError):
            self.run_build()
        self.assertEqual("failed", json.loads((self.output / "SUMMARY.json").read_text())["status"])

    def test_source_drift_across_cores_is_rejected(self):
        self.patches()
        self.engine_after = lambda _: self.marker.write_bytes(b"changed source")
        with self.assertRaisesRegex(builder.SourceBuildError, "Source inputs changed"):
            self.run_build()

    def test_prior_core_completion_receipt_is_reread_after_other_build(self):
        self.patches()
        self.engine_after = lambda _: (self.output / "cli/source-build-manifest.json").write_text("{}")
        with self.assertRaisesRegex(builder.SourceBuildError, "receipt differs"):
            self.run_build()

    def test_source_derived_coupling_offset_is_not_optional(self):
        self.patches()
        self.engine_after = lambda report: report.update(arm64PokedataOffset=1124)
        with self.assertRaisesRegex(builder.SourceBuildError, "workaround offset"):
            self.run_build()

    def test_executable_symlink_or_hardlink_outputs_are_rejected(self):
        self.patches()
        name = "libdroide_cli.so"
        for index, kind in enumerate(("executable", "symlink", "hardlink")):
            self.output = self.base / ("shape-" + str(index))
            def mutate(_report):
                path = builder.output_path(self.output, "first", name)
                if kind == "executable":
                    path.chmod(0o700)
                else:
                    other = path.with_name("other")
                    path.rename(other)
                    if kind == "symlink":
                        path.symlink_to(other)
                    else:
                        os.link(other, path)
            self.engine_after = mutate
            with self.subTest(kind=kind), self.assertRaisesRegex(builder.SourceBuildError, "output shape"):
                self.run_build()

    def test_output_mutation_during_final_validation_is_detected(self):
        self.patches()
        original = builder.verify_output
        calls = 0
        def verify(path, name):
            nonlocal calls
            raw = original(path, name)
            calls += 1
            if calls == 8:
                builder.output_path(self.output, "first", "libdroide_cli.so").write_bytes(b"corrupted")
            return raw
        with patch.object(builder, "verify_output", side_effect=verify), self.assertRaises(builder.SourceBuildError):
            self.run_build()

    def test_second_build_directory_cannot_alias_first_via_a_late_symlink(self):
        self.patches()
        for component in ("cli", "proot"):
            self.output = self.base / ("alias-" + component)
            def mutate(_report):
                second = self.output / component / "second"
                second.rename(second.with_name("second-original"))
                second.symlink_to(second.with_name("first"), target_is_directory=True)
            self.engine_after = mutate
            with self.subTest(component=component), self.assertRaisesRegex(builder.SourceBuildError, "directory may not be symlinked"):
                self.run_build()

    def test_publication_cannot_leave_final_success_after_fsync_failure(self):
        self.output.mkdir()
        final = self.output / "SUMMARY.json"
        with patch.object(builder.os, "fsync", side_effect=OSError("synthetic sync failure")), self.assertRaises(OSError):
            builder.publish_summary(final, {"status": builder.SUCCESS})
        self.assertFalse(final.exists())
        self.assertEqual(builder.SUCCESS, json.loads(final.with_name("SUMMARY.json.pending").read_text())["status"])

    def test_final_publication_never_overwrites_and_never_cleans_up_after_success(self):
        self.output.mkdir()
        final = self.output / "SUMMARY.json"
        with patch.object(Path, "unlink", side_effect=AssertionError("no post-commit cleanup")):
            builder.publish_summary(final, {"status": builder.SUCCESS})
        before = final.read_bytes()
        final.with_name("SUMMARY.json.pending").unlink()
        with self.assertRaises(FileExistsError):
            builder.publish_summary(final, {"status": "other"})
        self.assertEqual(before, final.read_bytes())

    def test_summary_size_is_not_silently_truncated(self):
        self.output.mkdir()
        with self.assertRaisesRegex(builder.SourceBuildError, "diagnostic budget"):
            builder.publish_summary(self.output / "SUMMARY.json", {"error": "x" * builder.SUMMARY_LIMIT})
        self.assertFalse((self.output / "SUMMARY.json").exists())


if __name__ == "__main__":
    unittest.main()
