#!/usr/bin/env python3
"""Bounded data fixtures and mocked tool calls; no native outputs are executed."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import stat
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch


TOOLS = Path(__file__).resolve().parents[1]
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import fdroid_proot_loader_source_build as builder


def tar_fixture(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w:gz") as archive:
        for name, data, kind in entries:
            info = tarfile.TarInfo(name)
            info.type = kind
            info.size = len(data) if kind == tarfile.REGTYPE else 0
            if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                info.linkname = "/outside"
            archive.addfile(info, io.BytesIO(data) if kind == tarfile.REGTYPE else None)
    return stream.getvalue()


class LoaderInputDataTests(unittest.TestCase):
    def test_exact_source_input_reader_has_no_retained_native_inputs(self):
        sources, inputs = builder.read_source_inputs(TOOLS.parent)
        self.assertEqual({builder.ARCHIVE, "tools/fdroid_proot_loader_source_build.py",
                          "tools/fdroid_proot_loader_elf.py"}, set(inputs))
        self.assertGreater(len(sources), len(builder.SOURCE_BLOBS))
        self.assertFalse(any(name.startswith(builder.NATIVE + "/") for name in inputs))

    def test_real_source_reader_succeeds_without_a_native_artifact_tree(self):
        with tempfile.TemporaryDirectory(prefix="loader-source-inputs-only-") as temporary:
            root = Path(temporary).resolve()
            for relative in (builder.ARCHIVE, "tools/fdroid_proot_loader_source_build.py",
                             "tools/fdroid_proot_loader_elf.py"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(builder.read(TOOLS.parent / relative, builder.MAX_ARCHIVE))
            native = root / builder.NATIVE
            original_stat, original_lstat = Path.stat, Path.lstat

            def guard(method):
                def checked(path, *args, **kwargs):
                    self.assertFalse(path == native or native in path.parents, "Native tree must never be inspected")
                    return method(path, *args, **kwargs)
                return checked

            with patch.object(Path, "stat", guard(original_stat)), patch.object(Path, "lstat", guard(original_lstat)):
                sources, inputs = builder.read_source_inputs(root)
            self.assertEqual(3, len(inputs))
            self.assertIn("src/loader/loader.c", sources)
            self.assertFalse(native.exists())

    def test_fixed_output_identity_requires_both_literal_sizes_and_sha256(self):
        self.assertEqual({"arm64": 18136, "arm32": 6244}, builder.OUTPUT_BYTES)
        self.assertEqual({
            "arm64": ("libproot-loader.so", "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"),
            "arm32": ("libproot-loader32.so", "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"),
        }, builder.LOADERS)
        for abi, (name, _) in builder.LOADERS.items():
            raw = builder.read(TOOLS.parent / builder.NATIVE / name)
            self.assertTrue(builder.matches_output_pin(raw, abi))
            self.assertFalse(builder.matches_output_pin(raw[:-1], abi))
            self.assertFalse(builder.matches_output_pin(bytes([raw[0] ^ 1]) + raw[1:], abi))

    def test_exact_retained_archive_blobs_and_binaries_as_data(self):
        sources, baselines, inputs = builder.read_inputs(TOOLS.parent)
        self.assertGreater(len(sources), len(builder.SOURCE_BLOBS))
        self.assertEqual(5, len(inputs))
        for name, digest in builder.SOURCE_BLOBS.items():
            self.assertEqual(digest, builder.git_blob(sources[name]))
        self.assertEqual(set(builder.LOADERS), set(baselines))
        self.assertIn("src/extension/native_offload/native_offload.c", sources)
        self.assertNotIn("COPYING", sources)

    def test_archive_only_stages_regular_src_files(self):
        root = builder.ARCHIVE_ROOT
        result = builder.archive_sources(tar_fixture([
            (root, b"", tarfile.DIRTYPE),
            (root + "/src", b"", tarfile.DIRTYPE),
            (root + "/src/loader/loader.c", b"data", tarfile.REGTYPE),
            (root + "/tests/not-executed.sh", b"exit 2", tarfile.REGTYPE),
        ]))
        self.assertEqual({"src/loader/loader.c": b"data"}, result)

    def test_archive_rejects_traversal_absolute_noncanonical_and_wrong_root(self):
        root = builder.ARCHIVE_ROOT
        for name in ("/src/evil", root + "/../evil", root + "/src/./evil",
                     root + "//src/evil", root + "/src/back\\slash", root + "/src/new\nline", "other/src/x"):
            with self.subTest(name=name), self.assertRaises(builder.SourceBuildError):
                builder.archive_sources(tar_fixture([(name, b"x", tarfile.REGTYPE)]))

    def test_archive_rejects_duplicates_links_and_devices(self):
        name = builder.ARCHIVE_ROOT + "/src/loader.c"
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE, tarfile.BLKTYPE):
            with self.subTest(kind=kind), self.assertRaises(builder.SourceBuildError):
                builder.archive_sources(tar_fixture([(name, b"", kind)]))
        with self.assertRaisesRegex(builder.SourceBuildError, "Duplicate"):
            builder.archive_sources(tar_fixture([(name, b"a", tarfile.REGTYPE), (name, b"b", tarfile.REGTYPE)]))

    def test_archive_bounds_and_malformed_payload_fail_closed(self):
        name = builder.ARCHIVE_ROOT + "/src/loader.c"
        with self.assertRaises(builder.SourceBuildError):
            builder.archive_sources(b"not a compressed archive")
        with self.assertRaisesRegex(builder.SourceBuildError, "compressed bound"):
            builder.archive_sources(b"x" * (builder.MAX_ARCHIVE + 1))
        with patch.object(builder, "MAX_FILE", 2), self.assertRaisesRegex(builder.SourceBuildError, "file bound"):
            builder.archive_sources(tar_fixture([(name, b"abc", tarfile.REGTYPE)]))
        with patch.object(builder, "MAX_EXPANDED", 2), self.assertRaisesRegex(builder.SourceBuildError, "expansion"):
            builder.archive_sources(tar_fixture([(name, b"abc", tarfile.REGTYPE)]))
        with patch.object(builder, "MAX_MEMBERS", 0), self.assertRaisesRegex(builder.SourceBuildError, "member bound"):
            builder.archive_sources(tar_fixture([(name, b"abc", tarfile.REGTYPE)]))

    def test_git_blob_mismatch_does_not_become_a_source_rebuild(self):
        wrong = {**builder.SOURCE_BLOBS, "src/loader/loader.c": "0" * 40}
        with patch.object(builder, "SOURCE_BLOBS", wrong), self.assertRaisesRegex(builder.SourceBuildError, "Git-blob"):
            builder.read_inputs(TOOLS.parent)

    def test_exact_architecture_macros_include_m32_and_addresses(self):
        arm64 = b"#define ARCH_ARM64 1\n#define HAS_LOADER_32BIT true\n#define HAS_POKEDATA_WORKAROUND true\n#define LOADER_ADDRESS 0x2000000000\n"
        arm32 = b"#define ARCH_ARM_EABI 1\n#define LOADER_ADDRESS 0x20000000\n"
        self.assertEqual("0x2000000000", builder.architecture_macros(arm64, "arm64")["LOADER_ADDRESS"])
        self.assertEqual("0x20000000", builder.architecture_macros(arm32, "arm32")["LOADER_ADDRESS"])
        for raw, abi in ((arm32, "arm64"), (arm64, "arm32"),
                         (arm64 + b"#define LOADER_ARCH_CFLAGS -mthumb\n", "arm64"),
                         (arm32 + b"#define ARCH_ARM_EABI 1\n", "arm32")):
            with self.subTest(raw=raw, abi=abi), self.assertRaises(builder.SourceBuildError):
                builder.architecture_macros(raw, abi)

    def test_gawk_offset_is_explicit_positive_aligned_and_bounded(self):
        prefix = b"#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround="
        self.assertEqual(1120, builder.parse_pokedata(prefix + b"1120;\n"))
        for raw in (prefix + b"0;\n", prefix + b"-4;\n", prefix + b"3;\n", prefix + b"999999999;\n",
                    prefix + b"4;\ntrailing", b"", b"different =4;\n"):
            with self.subTest(raw=raw), self.assertRaises(builder.SourceBuildError):
                builder.parse_pokedata(raw)


class LoaderBuildControlTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="loader-source-controls-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root, self.ndk, self.output = self.base / "repo", self.base / "ndk", self.base / "proof"
        self.root.mkdir()
        self.ndk.mkdir()
        self.properties = b"Pkg.Revision = 29.0.14206865\n"
        (self.ndk / "source.properties").write_bytes(self.properties)
        self.header = self.ndk / "stdbool.h"
        self.header.write_bytes(b"/* dummy compiler header */\n")
        directory = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        directory.mkdir(parents=True)
        host = self.base / "host-tools"
        host.mkdir()
        self.tools = {"cc": directory / "aarch64-linux-android24-clang", "clang": directory / "clang",
                      "linker": directory / "ld.lld", "strip": directory / "llvm-strip",
                      **{name: host / name for name in builder.HOST_TOOLS}}
        for path in self.tools.values():
            path.write_bytes(b"NOT A PROGRAM; tests must mock every invocation\n")
            path.chmod(0o700)
        self.identities = {name: builder.tool_identity(path, self.ndk if name not in builder.HOST_TOOLS else None)
                           for name, path in self.tools.items()}
        self.sources = {"src/GNUmakefile": b"# synthetic, never run\n",
                        "src/arch.h": b"/* synthetic */\n",
                        "src/loader/loader.c": b"/* synthetic */\n",
                        "src/loader/assembly.S": b"/* synthetic */\n",
                        "src/loader/loader-info.awk": b"# synthetic, never run\n"}
        # These immutable bytes are only fake compiler output data in control
        # tests, never compiler inputs. The production core must obtain the same
        # fixed identities by building source, without reading native artifacts.
        self.baselines = {abi: builder.read(TOOLS.parent / builder.NATIVE / name)
                          for abi, (name, _) in builder.LOADERS.items()}
        self.source_inputs = {builder.ARCHIVE: b"synthetic source archive, public pin not bypassed outside mock"}
        self.inputs = dict(self.source_inputs)
        for abi, (name, _) in builder.LOADERS.items():
            self.inputs[builder.NATIVE + "/" + name] = self.baselines[abi]
        for relative, data in self.inputs.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.calls = []
        self.make_count = 0
        self.raw_pairs = [{"arm64": b"RAW64", "arm32": b"RAW32"} for _ in range(2)]
        self.stripped_pairs = [dict(self.baselines) for _ in range(2)]
        self.after_make = None
        self.gawk_value = b"4"
        self.external_header = None
        self.text_changed = False
        for patcher in (
            patch.object(builder, "read_inputs", return_value=(self.sources, self.baselines, self.inputs)),
            patch.object(builder, "read_source_inputs", return_value=(self.sources, self.source_inputs)),
            patch.object(builder, "ndk_tools", return_value=(self.ndk, self.properties, self.tools, self.identities)),
            patch.object(builder, "run_tool", side_effect=self.fake_tool),
            patch.object(builder, "inspect_loader", side_effect=self.fake_inspect),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def fake_inspect(self, raw, abi, *, require_symbols=False):
        self.assertIn(abi, ("arm64", "arm32"))
        self.assertFalse(require_symbols and abi != "arm64")
        return {"abi": abi, "textSha256": "changed" if self.text_changed and raw in self.baselines.values() else "unchanged",
                "entryPoint": 0x2000000000 if abi == "arm64" else 0x20000000,
                "class": "ELF64" if abi == "arm64" else "ELF32", "type": "ET_EXEC",
                "machine": "AArch64" if abi == "arm64" else "ARM", "flags": 0, "static": True,
                "nonExecutableStack": True, "minimumLoadAlignment": 16384 if abi == "arm64" else 4096,
                "pokedataOffset": 4 if require_symbols else None, "pokedataStubVerified": require_symbols,
                "compilerComments": ["fixture"], "symbols": {},
                "loadableSegmentsSha256": builder.sha(raw)}

    def fake_tool(self, command, cwd, environment, log, *, input_bytes=None, timeout=60):
        command = [str(item) for item in command]
        self.calls.append((command, Path(cwd), dict(environment), input_bytes))
        name = Path(command[0]).name
        allowed = {path.name for path in self.tools.values()}
        self.assertIn(name, allowed)
        result = b"fixture tool diagnostics\n"
        if command[1:] == ["--version"]:
            if name == "aarch64-linux-android24-clang":
                result = b"Android clang version 21.0.0 (r563880c)\n"
            elif name == "gawk":
                result = b"GNU Awk fixture\n"
        elif name == "ld":
            self.assertEqual(["--build-id=none", "--version"], command[1:])
        elif name == "aarch64-linux-android24-clang" and "-dM" in command:
            if "-m32" in command:
                result = b"#define ARCH_ARM_EABI 1\n#define LOADER_ADDRESS 0x20000000\n"
            else:
                result = b"#define ARCH_ARM64 1\n#define HAS_LOADER_32BIT true\n#define HAS_POKEDATA_WORKAROUND true\n#define LOADER_ADDRESS 0x2000000000\n"
        elif name == "make":
            self.assertEqual(builder.make_command(self.tools), command)
            pair = self.raw_pairs[self.make_count]
            for abi in builder.LOADERS:
                suffix = "" if abi == "arm64" else "-m32"
                path = Path(cwd) / ("loader/loader" + suffix)
                path.write_bytes(pair[abi])
                path.chmod(0o700)
                header = self.external_header or self.header
                for stem, source in (("loader", "loader.c"), ("assembly", "assembly.S")):
                    (Path(cwd) / ("loader/" + stem + suffix + ".d")).write_text(
                        "loader/" + stem + suffix + ".o: loader/" + source + " " + str(header) + "\n")
            self.make_count += 1
            if self.after_make:
                self.after_make(Path(cwd))
        elif name == "aarch64-linux-android24-clang":
            self.assertIn("-###", command)
        elif name == "readelf":
            self.assertEqual(["--wide", "--syms"], command[1:3])
            result = b"fixture symbol table\n"
        elif name == "gawk":
            self.assertEqual(b"fixture symbol table\n", input_bytes)
            result = b"#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=" + self.gawk_value + b";\n"
        elif name == "llvm-strip":
            self.assertEqual(["--strip-unneeded", "--preserve-dates"], command[1:3])
            path = Path(command[-1])
            self.assertEqual(0, stat.S_IMODE(path.stat().st_mode) & 0o111)
            abi = "arm32" if "loader32" in path.name else "arm64"
            path.write_bytes(self.stripped_pairs[self.make_count - 1][abi])
        else:
            self.fail("Unexpected tool invocation: " + str(command))
        log.write_bytes(result)
        return result

    def build(self):
        return builder.build_proof(self.ndk, self.output, self.root)

    def build_core(self):
        return builder.build_sources(self.ndk, self.output, self.root)

    def summary(self):
        return json.loads((self.output / "SUMMARY.json").read_text())

    def core_summary(self):
        return json.loads((self.output / "source-build-summary.json").read_text())

    def test_two_builds_both_abis_exact_and_no_acceptance_or_cleaner_claim(self):
        report = self.build()
        self.assertEqual(report, self.summary())
        self.assertEqual(2, self.make_count)
        for key in ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible", "matchesBundledBytes",
                    "matchesPinnedOutputIdentity", "baselineComparisonPerformed", "sourceBuildOutputsUnchanged",
                    "inputsAndToolsUnchanged"):
            self.assertTrue(report[key], key)
        for key in ("packagedPayloadsChanged", "targetOutputExecuted", "androidRuntimeCompatibilityVerified",
                    "fdroidAcceptanceVerified", "ndkArchiveAuthenticated", "elfCleanerExecuted",
                    "historicalPostprocessingReplayed", "postprocessingIncomplete", "publicationPerformed",
                    "publicationApproved"):
            self.assertFalse(report[key], key)
        for relative, data in self.inputs.items():
            self.assertEqual(data, (self.root / relative).read_bytes())
        for label in ("first", "second"):
            for name, _ in builder.LOADERS.values():
                self.assertEqual(0, (self.output / label / ("post-strip-" + name)).stat().st_mode & 0o111)
        self.assertTrue((self.output / "receipt.json").is_file())
        receipt = json.loads((self.output / "receipt.json").read_text())
        self.assertEqual({**report, "status": "verified-pending-summary-publication"}, receipt["summary"])
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertEqual(builder.sha((self.output / "SUMMARY.json").read_bytes()),
                         receipt["authoritativeSummary"]["sha256"])
        self.assertEqual(4, len(receipt["baselineComparisons"]))
        self.assertTrue(all(row["matchesBundledBytes"] for row in receipt["baselineComparisons"]))
        self.assertLessEqual((self.output / "SUMMARY.json").stat().st_size, 12 * 1024)

    def test_source_core_ignores_absent_poisoned_and_forbidden_native_trees(self):
        native = self.root / builder.NATIVE
        original_read = builder.read
        original_stat, original_lstat = Path.stat, Path.lstat

        def guard(method):
            def checked(path, *args, **kwargs):
                path = Path(path)
                self.assertFalse(path == native or native in path.parents, "Core touched a retained-artifact path")
                return method(path, *args, **kwargs)
            return checked

        for index, state in enumerate(("absent", "poisoned", "forbidden")):
            if index == 0:
                for name, _ in builder.LOADERS.values():
                    (native / name).unlink()
                native.rmdir()
            elif state == "poisoned":
                native.mkdir()
                for name, _ in builder.LOADERS.values():
                    (native / name).write_bytes(b"poisoned retained artifact")
            else:
                for name, _ in builder.LOADERS.values():
                    (native / name).unlink()
                native.rmdir()
                native.symlink_to(self.base / "forbidden-nonexistent-tree", target_is_directory=True)
            self.output, self.make_count = self.base / ("source-" + state), 0
            with self.subTest(state=state), patch.object(builder, "read_inputs", side_effect=AssertionError("Proof-only reader")), \
                    patch.object(builder, "read", side_effect=guard(original_read)), \
                    patch.object(Path, "stat", guard(original_stat)), patch.object(Path, "lstat", guard(original_lstat)):
                report = self.build_core()
            self.assertEqual("source-built-both-loader-pinned-identities-reproduced", report["status"])
            self.assertTrue(report["matchesPinnedOutputIdentity"])
            self.assertEqual([], report["retainedArtifactInputs"])
            for flag in ("matchesBundledBytes", "baselineComparisonPerformed", "publicationPerformed",
                         "publicationApproved", "targetOutputExecuted", "androidRuntimeCompatibilityVerified",
                         "fdroidAcceptanceVerified", "packagedPayloadsChanged"):
                self.assertFalse(report[flag], flag)
            self.assertEqual(report, self.core_summary())
            self.assertFalse((self.output / "SUMMARY.json").exists())
            self.assertFalse((self.output / "receipt.json").exists())
            receipt = json.loads((self.output / "source-build-receipt.json").read_text())
            self.assertEqual("pending-summary", receipt["publicationState"])
            for row in report["builds"]:
                for record in row["loaders"].values():
                    self.assertTrue(record["matchesPinnedOutputIdentity"])
                    self.assertFalse(record["matchesBundledBytes"])
                    self.assertNotIn("baselineSha256", record)

    def test_source_core_hard_pin_cannot_be_replaced_by_reproducibility(self):
        for abi in builder.LOADERS:
            self.output, self.make_count = self.base / ("bad-pin-" + abi), 0
            self.stripped_pairs = [dict(self.baselines) for _ in range(2)]
            raw = self.baselines[abi]
            for pair in self.stripped_pairs:
                pair[abi] = bytes([raw[0] ^ 1]) + raw[1:]
            with self.subTest(abi=abi), self.assertRaisesRegex(builder.SourceBuildError, "fixed output size/SHA-256"):
                self.build_core()
            report = self.core_summary()
            self.assertTrue(report["sourceBuildSucceeded"])
            self.assertTrue(report["rawReproducible"])
            self.assertTrue(report["postStripReproducible"])
            self.assertFalse(report["matchesPinnedOutputIdentity"])
            self.assertFalse(report["baselineComparisonPerformed"])
            self.assertTrue(report["postprocessingIncomplete"])
            self.assertEqual("failed", report["status"])

    def test_proof_independently_rereads_both_outputs_in_both_builds(self):
        real_core = builder.build_sources
        for label in ("first", "second"):
            for abi, (name, _) in builder.LOADERS.items():
                self.output, self.make_count = self.base / ("mutated-" + label + "-" + abi), 0

                def mutate_after_core(*args, **kwargs):
                    core = real_core(*args, **kwargs)
                    path = self.output / label / ("post-strip-" + name)
                    raw = path.read_bytes()
                    path.write_bytes(bytes([raw[0] ^ 1]) + raw[1:])
                    return core

                with self.subTest(label=label, abi=abi), patch.object(builder, "build_sources", side_effect=mutate_after_core), \
                        self.assertRaisesRegex(builder.SourceBuildError, "changed before baseline comparison"):
                    self.build()
                report = self.summary()
                self.assertTrue(report["baselineComparisonPerformed"])
                self.assertFalse(report["matchesBundledBytes"])
                self.assertFalse(report["sourceBuildOutputsUnchanged"])
                compared = {(row["name"], variant): record["matchesBundledBytes"]
                            for row in report["builds"] for variant, record in row["loaders"].items()}
                self.assertEqual(4, len(compared))
                self.assertEqual({(label, abi)}, {key for key, match in compared.items() if not match})

    def test_proof_rejects_a_core_return_with_weakened_source_only_gates(self):
        good = {"status": "source-built-both-loader-pinned-identities-reproduced",
                "retainedArtifactInputs": []}
        good.update({key: True for key in ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible",
                     "matchesPinnedOutputIdentity", "inputsAndToolsUnchanged", "gawkOffsetMatchesIndependentSymbols")})
        good.update({key: False for key in ("baselineComparisonPerformed", "matchesBundledBytes",
                     "publicationApproved", "targetOutputExecuted", "packagedPayloadsChanged")})
        for field, value in (("rawReproducible", False), ("matchesPinnedOutputIdentity", False),
                             ("baselineComparisonPerformed", True), ("retainedArtifactInputs", ["forbidden.so"])):
            with self.subTest(field=field), patch.object(builder, "build_sources", return_value={**good, field: value}), \
                    self.assertRaisesRegex(builder.SourceBuildError, "baseline-free strict result"):
                self.build()
        self.assertEqual(0, self.make_count)
        self.assertFalse(self.output.exists())

    def test_common_flags_are_environment_not_make_command_assignments(self):
        with patch.dict(os.environ, {"SECRET_TEST": "never pass", "CFLAGS": "bad", "MAKEFLAGS": "evil", "LD_PRELOAD": "bad"}):
            self.build()
        make_builds = []
        make_version_probes = []
        for command, _, environment, _ in self.calls:
            self.assertNotIn("SECRET_TEST", environment)
            self.assertNotIn("MAKEFLAGS", environment)
            self.assertNotIn("LD_PRELOAD", environment)
            self.assertEqual(builder.COMMON_CFLAGS, environment["CFLAGS"])
            self.assertEqual(builder.COMMON_CPPFLAGS, environment["CPPFLAGS"])
            if Path(command[0]).name == "make":
                if command[1:] == ["--version"]:
                    make_version_probes.append(command)
                    continue
                make_builds.append(command)
                self.assertFalse(any(item.startswith(("CFLAGS=", "CPPFLAGS=", "LDFLAGS=")) for item in command))
                self.assertIn("loader/loader-m32", command)
        self.assertEqual(2, len(make_builds))
        self.assertEqual(1, len(make_version_probes))
        probes = [command for command, _, _, _ in self.calls if "-###" in command]
        self.assertEqual(8, len(probes))
        self.assertEqual(4, sum("-m32" in command for command in probes))

    def test_single_baseline_mismatch_is_strict_failure_with_truthful_summary(self):
        for pair in self.stripped_pairs:
            pair["arm32"] = b"DIFFERENT32"
        with self.assertRaisesRegex(builder.SourceBuildError, "historical postprocessing remains incomplete"):
            self.build()
        report = self.summary()
        self.assertTrue(report["sourceBuildSucceeded"])
        self.assertTrue(report["rawReproducible"])
        self.assertTrue(report["postStripReproducible"])
        self.assertFalse(report["matchesBundledBytes"])
        self.assertTrue(report["postprocessingIncomplete"])
        self.assertFalse(report["elfCleanerExecuted"])
        self.assertEqual("failed", report["status"])

    def test_raw_nondeterminism_not_hidden_by_identical_stripped_outputs(self):
        self.raw_pairs[1]["arm64"] = b"DIFFERENT_RAW64"
        with self.assertRaisesRegex(builder.SourceBuildError, "not byte-identical"):
            self.build()
        self.assertFalse(self.summary()["rawReproducible"])
        self.assertTrue(self.summary()["postStripReproducible"])

    def test_poststrip_nondeterminism_fails(self):
        self.stripped_pairs[1]["arm32"] = b"DIFFERENT32"
        with self.assertRaisesRegex(builder.SourceBuildError, "not byte-identical"):
            self.build()
        self.assertTrue(self.summary()["rawReproducible"])
        self.assertFalse(self.summary()["postStripReproducible"])
        self.assertFalse(self.summary()["matchesBundledBytes"])
        self.assertTrue(self.summary()["postprocessingIncomplete"])

    def test_receipt_write_failure_cannot_leave_success_status(self):
        original = builder._stage_receipt

        def fail_receipt(directory, name, raw):
            if name == "receipt.json.pending":
                raise OSError("synthetic receipt write failure")
            return original(directory, name, raw)

        with patch.object(builder, "_stage_receipt", side_effect=fail_receipt), \
             self.assertRaisesRegex(builder.SourceBuildError, "receipt write failure"):
            self.build()
        self.assertEqual("failed", self.summary()["status"])
        self.assertTrue(self.summary()["sourceBuildSucceeded"])
        self.assertTrue(self.summary()["matchesBundledBytes"])
        self.assertIn("receipt write failure", self.summary()["error"])
        self.assertFalse((self.output / "receipt.json").exists())

    def test_make_error_produces_failure_summary_and_no_false_source_success(self):
        def fail(_):
            raise builder.SourceBuildError("synthetic make failure")
        self.after_make = fail
        with self.assertRaisesRegex(builder.SourceBuildError, "synthetic make failure"):
            self.build()
        self.assertFalse(self.summary()["sourceBuildSucceeded"])
        self.assertEqual("failed", self.summary()["status"])

    def test_repository_input_mutation_detected(self):
        self.after_make = lambda _: (self.root / builder.ARCHIVE).write_bytes(b"changed")
        with self.assertRaisesRegex(builder.SourceBuildError, "Pinned repository input changed"):
            self.build()
        self.assertFalse(self.summary()["inputsAndToolsUnchanged"])

    def test_staged_source_mutation_detected(self):
        self.after_make = lambda cwd: (cwd / "loader/loader.c").write_bytes(b"changed")
        with self.assertRaisesRegex(builder.SourceBuildError, "Staged source changed"):
            self.build()

    def test_tool_mutation_detected(self):
        self.after_make = lambda _: self.tools["clang"].write_bytes(b"changed tool")
        with self.assertRaisesRegex(builder.SourceBuildError, "Tool identity changed"):
            self.build()

    def test_previous_raw_output_mutation_detected_after_second_build(self):
        def mutate(_):
            if self.make_count == 2:
                (self.output / "first/src/loader/loader").write_bytes(b"changed earlier output")
        self.after_make = mutate
        with self.assertRaisesRegex(builder.SourceBuildError, "Proof output changed"):
            self.build()

    def test_gawk_symbols_must_match_independent_parser(self):
        self.gawk_value = b"8"
        with self.assertRaisesRegex(builder.SourceBuildError, "independently resolved"):
            self.build()

    def test_missing_symbols_cannot_silently_become_zero_offset(self):
        self.gawk_value = b"0"
        with self.assertRaisesRegex(builder.SourceBuildError, "Invalid generated pokedata"):
            self.build()

    def test_strip_cannot_silently_change_text(self):
        self.text_changed = True
        with self.assertRaisesRegex(builder.SourceBuildError, "changed executable"):
            self.build()

    def test_dependency_on_host_header_fails_closed(self):
        self.external_header = self.base / "host-secret.h"
        self.external_header.write_bytes(b"dummy forbidden input")
        with self.assertRaisesRegex(builder.SourceBuildError, "undeclared header"):
            self.build()

    def test_existing_output_and_source_tool_overlap_rejected(self):
        existing = self.base / "existing"
        existing.mkdir()
        for path in (existing, Path("relative"), self.root / "out", self.ndk / "out"):
            with self.subTest(path=path), self.assertRaises(builder.SourceBuildError):
                builder.output_path(path, self.root, self.ndk)
        link = self.base / "alias"
        link.symlink_to(self.ndk, target_is_directory=True)
        with self.assertRaisesRegex(builder.SourceBuildError, "canonical"):
            builder.output_path(link / "out", self.root, self.ndk)

    def test_dependency_syntax_count_duplicate_and_escape_guards(self):
        source = self.base / "sources"
        source.mkdir()
        (source / "x.c").write_bytes(b"x")
        good = builder.dependency_closure(b"x.o: x.c\n", source, self.ndk)
        self.assertEqual({"source/x.c"}, set(good))
        for raw in (b"x.o:", b"x.o: x.c:more", b"x.o: x.c x.c", b"x.o: " + b"x.c " * 129):
            with self.subTest(raw=raw), self.assertRaises(builder.SourceBuildError):
                builder.dependency_closure(raw, source, self.ndk)

    def test_regular_file_reader_rejects_symlink_directory_and_oversize(self):
        path = self.base / "real"
        path.write_bytes(b"abc")
        link = self.base / "link"
        link.symlink_to(path)
        for candidate, bound in ((link, 3), (self.base, 3), (path, 2)):
            with self.subTest(path=candidate), self.assertRaises(builder.SourceBuildError):
                builder.read(candidate, bound)


class LoaderReceiptPublicationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="loader-source-receipts-")
        self.addCleanup(temporary.cleanup)
        self.output = Path(temporary.name).resolve()
        self.summary = {"status": "source-built-fixture", "sourceBuildSucceeded": True,
                        "publicationApproved": False}

    def publish(self):
        builder.publish_result(self.output, self.summary, {"builds": ["fixture"]},
                               summary_name="source-build-summary.json", receipt_name="source-build-receipt.json")

    def test_both_pending_files_close_before_any_final_link_and_summary_is_last(self):
        original_fdopen, original_link = builder.os.fdopen, builder.os.link
        streams, destinations = [], []

        def record_stream(*args, **kwargs):
            stream = original_fdopen(*args, **kwargs)
            streams.append(stream)
            return stream

        def record_link(source, destination, **kwargs):
            self.assertEqual(2, len(streams))
            self.assertTrue(all(stream.closed for stream in streams))
            self.assertEqual(kwargs["src_dir_fd"], kwargs["dst_dir_fd"])
            destinations.append(destination)
            return original_link(source, destination, **kwargs)

        with patch.object(builder.os, "fdopen", side_effect=record_stream), \
                patch.object(builder.os, "link", side_effect=record_link), \
                patch.object(builder.os, "unlink", side_effect=AssertionError("No post-publication cleanup")):
            self.publish()
        self.assertEqual(["source-build-receipt.json", "source-build-summary.json"], destinations)
        receipt = json.loads((self.output / destinations[0]).read_text())
        self.assertEqual("verified-pending-summary-publication", receipt["summary"]["status"])
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertEqual(builder.sha((self.output / destinations[1]).read_bytes()),
                         receipt["authoritativeSummary"]["sha256"])
        self.assertEqual(self.summary, json.loads((self.output / destinations[1]).read_text()))

    def test_large_repeated_header_evidence_is_losslessly_compact_and_published_last(self):
        # Actual engine CI reported 347 distinct headers per build. The 21
        # translation units here are synthetic, not a claim about that run's
        # unexported receipt; they exercise its repeated full-header-map shape.
        headers = {
            "ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/sys/header_%03d.h" % index:
            {"sha256": "%064x" % index,
             "path": "/toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/sys/header_%03d.h" % index}
            for index in range(347)
        }
        receipt = {
            "builds": [
                {"name": label, "compilerDependencies": {"unit_%02d.o" % unit: headers for unit in range(21)},
                 "objectInventory": ["unit_%02d.o" % unit for unit in range(21)]}
                for label in ("first", "second")
            ],
            "commands": [{"command": ["/toolchain/ndk/bin/clang", "-c", "unit_%02d.c" % index],
                          "log": "command-%03d.log" % index, "logSha256": "%064x" % index}
                         for index in range(48)],
            "trustLimits": ["Synthetic publication fixture; no compiler or target output is executed."],
        }
        original_receipt = json.dumps(receipt, separators=(",", ":"))
        summary_raw = builder.summary_bytes(self.summary)
        expected = {**receipt, "summary": {**self.summary, "status": "verified-pending-summary-publication"},
                    "publicationState": "pending-summary",
                    "authoritativeSummary": {"file": "source-build-summary.json",
                                             "sha256": builder.sha(summary_raw), "required": True}}
        compact = (json.dumps(expected, separators=(",", ":")) + "\n").encode("utf-8")
        self.assertGreater(len((json.dumps(expected, indent=2) + "\n").encode("utf-8")), builder.MAX_RECEIPT)
        self.assertLessEqual(len(compact), builder.MAX_RECEIPT)
        original_fdopen, original_link = builder.os.fdopen, builder.os.link
        streams, destinations = [], []

        def record_stream(*args, **kwargs):
            stream = original_fdopen(*args, **kwargs)
            streams.append(stream)
            return stream

        def record_link(source, destination, **kwargs):
            self.assertEqual(2, len(streams))
            self.assertTrue(all(stream.closed for stream in streams))
            destinations.append(destination)
            return original_link(source, destination, **kwargs)

        with patch.object(builder.os, "fdopen", side_effect=record_stream), \
                patch.object(builder.os, "link", side_effect=record_link), \
                patch.object(builder.os, "unlink", side_effect=AssertionError("No post-publication cleanup")):
            builder.publish_result(self.output, self.summary, receipt,
                                   summary_name="source-build-summary.json", receipt_name="source-build-receipt.json")
        self.assertEqual(["source-build-receipt.json", "source-build-summary.json"], destinations)
        actual = (self.output / "source-build-receipt.json").read_bytes()
        self.assertEqual(compact, actual)
        self.assertEqual(json.loads(compact), json.loads(actual))
        self.assertEqual(original_receipt, json.dumps(receipt, separators=(",", ":")))
        self.assertEqual(summary_raw, (self.output / "source-build-summary.json").read_bytes())
        self.assertEqual(builder.sha(summary_raw), json.loads(actual)["authoritativeSummary"]["sha256"])

    def test_compact_receipt_over_limit_fails_before_any_pending_or_final_file(self):
        receipt = {"payload": "x" * builder.MAX_RECEIPT}
        expected = {**receipt, "summary": {**self.summary, "status": "verified-pending-summary-publication"},
                    "publicationState": "pending-summary",
                    "authoritativeSummary": {"file": "source-build-summary.json",
                                             "sha256": builder.sha(builder.summary_bytes(self.summary)), "required": True}}
        expected_bytes = len((json.dumps(expected, separators=(",", ":")) + "\n").encode("utf-8"))
        self.assertGreater(expected_bytes, builder.MAX_RECEIPT)
        with patch.object(builder, "_stage_receipt") as stage, \
                patch.object(builder.os, "open", side_effect=AssertionError("No output descriptor before size validation")), \
                self.assertRaisesRegex(builder.SourceBuildError, "Detailed source receipt exceeds diagnostic bound") as caught:
            builder.publish_result(self.output, self.summary, receipt,
                                   summary_name="source-build-summary.json", receipt_name="source-build-receipt.json")
        stage.assert_not_called()
        self.assertIn("%d bytes; limit %d bytes" % (expected_bytes, builder.MAX_RECEIPT), str(caught.exception))
        self.assertEqual([], list(self.output.iterdir()))

    def test_completed_pending_receipt_then_write_failure_has_no_published_success(self):
        original = builder._stage_receipt

        def fail_after_complete(directory, name, raw):
            original(directory, name, raw)
            if name == "source-build-receipt.json.pending":
                raise OSError("synthetic post-write failure")

        with patch.object(builder, "_stage_receipt", side_effect=fail_after_complete), self.assertRaises(OSError):
            self.publish()
        self.assertFalse((self.output / "source-build-summary.json").exists())
        self.assertFalse((self.output / "source-build-receipt.json").exists())
        pending = json.loads((self.output / "source-build-receipt.json.pending").read_text())
        self.assertEqual("pending-summary", pending["publicationState"])
        failed = {**self.summary, "status": "failed", "error": "synthetic post-write failure"}
        builder.publish_failure_summary(self.output / "source-build-summary.json", failed)
        self.assertEqual(failed, json.loads((self.output / "source-build-summary.json").read_text()))

    def test_sync_and_close_failures_cannot_publish_final_files(self):
        original_fdopen = builder.os.fdopen

        class CloseFailure:
            def __init__(self, stream):
                self.stream = stream

            def __enter__(self):
                return self.stream

            def __exit__(self, *_args):
                self.stream.close()
                raise OSError("synthetic close failure")

        for phase in ("sync", "close"):
            output = self.output / phase
            output.mkdir()
            injected = (patch.object(builder.os, "fsync", side_effect=OSError("synthetic sync failure"))
                        if phase == "sync" else patch.object(builder.os, "fdopen",
                            side_effect=lambda *args, **kwargs: CloseFailure(original_fdopen(*args, **kwargs))))
            with self.subTest(phase=phase), injected, self.assertRaises(OSError):
                builder.publish_result(output, self.summary, {}, summary_name="source-build-summary.json",
                                       receipt_name="source-build-receipt.json")
            self.assertFalse((output / "source-build-summary.json").exists())
            self.assertFalse((output / "source-build-receipt.json").exists())
            self.assertEqual(self.summary, json.loads((output / "source-build-summary.json.pending").read_text()))

    def test_second_link_failure_leaves_only_explicitly_pending_evidence(self):
        original_link = builder.os.link

        def fail_final(source, destination, **kwargs):
            if destination == "source-build-summary.json":
                raise OSError("synthetic summary publication failure")
            return original_link(source, destination, **kwargs)

        with patch.object(builder.os, "link", side_effect=fail_final), self.assertRaises(OSError):
            self.publish()
        self.assertFalse((self.output / "source-build-summary.json").exists())
        evidence = json.loads((self.output / "source-build-receipt.json").read_text())
        self.assertEqual("verified-pending-summary-publication", evidence["summary"]["status"])
        self.assertTrue(evidence["authoritativeSummary"]["required"])
        failed = {**self.summary, "status": "failed"}
        builder.publish_failure_summary(self.output / "source-build-summary.json", failed)
        self.assertEqual("failed", json.loads((self.output / "source-build-summary.json").read_text())["status"])

    def test_pending_and_final_names_are_exclusive_and_never_follow_symlinks(self):
        for name in ("source-build-summary.json.pending", "source-build-receipt.json.pending",
                     "source-build-summary.json", "source-build-receipt.json"):
            for symlink in (False, True):
                output = self.output / (name + str(symlink))
                output.mkdir()
                marker = self.output / ("keep-" + name + str(symlink))
                marker.write_bytes(b"keep")
                blocked = output / name
                if symlink:
                    blocked.symlink_to(marker)
                else:
                    blocked.write_bytes(b"keep")
                with self.subTest(name=name, symlink=symlink), self.assertRaises(FileExistsError):
                    builder.publish_result(output, self.summary, {}, summary_name="source-build-summary.json",
                                           receipt_name="source-build-receipt.json")
                self.assertEqual(b"keep", marker.read_bytes())
                self.assertEqual(b"keep", blocked.read_bytes())

    def test_failed_summary_cannot_overwrite_a_final_summary(self):
        final = self.output / "SUMMARY.json"
        final.write_bytes(b"existing diagnostic")
        with self.assertRaises(FileExistsError):
            builder.publish_failure_summary(final, {"status": "failed"})
        self.assertEqual(b"existing diagnostic", final.read_bytes())

    def test_short_writes_and_oversized_receipts_fail_before_final_publication(self):
        original_fdopen = builder.os.fdopen

        class ShortWrite:
            def __init__(self, stream):
                self.stream = stream

            def __enter__(self):
                return self

            def write(self, raw):
                return self.stream.write(raw[:-1])

            def __exit__(self, *args):
                return self.stream.__exit__(*args)

        with patch.object(builder.os, "fdopen", side_effect=lambda *args, **kwargs: ShortWrite(original_fdopen(*args, **kwargs))), \
                self.assertRaisesRegex(builder.SourceBuildError, "Short receipt write"):
            self.publish()
        self.assertFalse((self.output / "source-build-summary.json").exists())
        self.assertFalse((self.output / "source-build-receipt.json").exists())
        for summary, receipt in (({"status": "fixture", "large": "x" * (12 * 1024)}, {}),
                                 (self.summary, {"large": "x" * builder.MAX_RECEIPT})):
            with self.subTest(receipt=bool(receipt)), patch.object(builder, "_stage_receipt") as stage, \
                    self.assertRaisesRegex(builder.SourceBuildError, "diagnostic bound"):
                builder.publish_result(self.output, summary, receipt, summary_name="source-build-summary.json",
                                       receipt_name="source-build-receipt.json")
            stage.assert_not_called()


class LoaderToolchainDataTests(unittest.TestCase):
    def test_revision_requires_exact_single_value_before_executable_access(self):
        with tempfile.TemporaryDirectory(prefix="loader-toolchain-data-") as temporary:
            base = Path(temporary).resolve()
            root, ndk = base / "repo", base / "ndk"
            root.mkdir()
            ndk.mkdir()
            for raw in (b"Pkg.Revision = 28.2.13676358\n", b"none\n",
                        b"Pkg.Revision = 29.0.14206865\nPkg.Revision = 29.0.14206865\n"):
                (ndk / "source.properties").write_bytes(raw)
                with self.subTest(raw=raw), patch.object(builder.platform, "system", return_value="Linux"), \
                     patch.object(builder.platform, "machine", return_value="x86_64"), \
                     self.assertRaisesRegex(builder.SourceBuildError, "exact pinned Android NDK"):
                    builder.ndk_tools(ndk, root)

    def test_tool_symlink_cannot_escape_ndk(self):
        with tempfile.TemporaryDirectory(prefix="loader-tool-identity-") as temporary:
            base = Path(temporary).resolve()
            ndk = base / "ndk"
            ndk.mkdir()
            outside = base / "outside"
            outside.write_bytes(b"not executable source")
            outside.chmod(0o700)
            link = ndk / "clang"
            link.symlink_to(outside)
            with self.assertRaisesRegex(builder.SourceBuildError, "outside the NDK"):
                builder.tool_identity(link, ndk)


if __name__ == "__main__":
    unittest.main()
