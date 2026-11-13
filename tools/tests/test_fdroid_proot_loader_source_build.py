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
        self.baselines = {"arm64": b"BASE64", "arm32": b"BASE32"}
        self.inputs = {builder.ARCHIVE: b"synthetic source archive, public pin not bypassed outside mock"}
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
            patch.object(builder, "ndk_tools", return_value=(self.ndk, self.properties, self.tools, self.identities)),
            patch.object(builder, "run_tool", side_effect=self.fake_tool),
            patch.object(builder, "inspect_loader", side_effect=self.fake_inspect),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def fake_inspect(self, raw, abi, *, require_symbols=False):
        self.assertIn(abi, ("arm64", "arm32"))
        self.assertFalse(require_symbols and abi != "arm64")
        return {"abi": abi, "textSha256": "changed" if self.text_changed and raw.startswith(b"BASE") else "unchanged",
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

    def summary(self):
        return json.loads((self.output / "SUMMARY.json").read_text())

    def test_two_builds_both_abis_exact_and_no_acceptance_or_cleaner_claim(self):
        report = self.build()
        self.assertEqual(report, self.summary())
        self.assertEqual(2, self.make_count)
        for key in ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible", "matchesBundledBytes", "inputsAndToolsUnchanged"):
            self.assertTrue(report[key], key)
        for key in ("packagedPayloadsChanged", "targetOutputExecuted", "androidRuntimeCompatibilityVerified",
                    "fdroidAcceptanceVerified", "ndkArchiveAuthenticated", "elfCleanerExecuted",
                    "historicalPostprocessingReplayed", "postprocessingIncomplete"):
            self.assertFalse(report[key], key)
        for relative, data in self.inputs.items():
            self.assertEqual(data, (self.root / relative).read_bytes())
        for label in ("first", "second"):
            for name, _ in builder.LOADERS.values():
                self.assertEqual(0, (self.output / label / ("post-strip-" + name)).stat().st_mode & 0o111)
        self.assertTrue((self.output / "receipt.json").is_file())
        self.assertEqual(report, json.loads((self.output / "receipt.json").read_text())["summary"])
        self.assertLessEqual((self.output / "SUMMARY.json").stat().st_size, 12 * 1024)

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
        original = builder.save_json

        def fail_receipt(path, value):
            if path.name == "receipt.json":
                raise OSError("synthetic receipt write failure")
            return original(path, value)

        with patch.object(builder, "save_json", side_effect=fail_receipt), \
             self.assertRaisesRegex(builder.SourceBuildError, "receipt write failure"):
            self.build()
        self.assertEqual("failed", self.summary()["status"])
        self.assertTrue(self.summary()["sourceBuildSucceeded"])
        self.assertTrue(self.summary()["matchesBundledBytes"])
        self.assertIn("receipt write failure", self.summary()["error"])

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
