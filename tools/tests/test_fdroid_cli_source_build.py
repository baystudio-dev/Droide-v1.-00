#!/usr/bin/env python3
"""Host-only controls; synthetic ELF and mocked compiler, never ARM64 execution."""
from __future__ import annotations

import importlib.util
import inspect
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch


HELPER = Path(__file__).resolve().parents[1] / "fdroid_cli_source_build.py"
SPEC = importlib.util.spec_from_file_location("fdroid_cli_source_build", HELPER)
assert SPEC is not None and SPEC.loader is not None
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


def elf_fixture(extra_kind: int | None = None) -> bytes:
    data = bytearray(256)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQ", data, 16, 2, 183, 1, 0x1000C0, 64)
    struct.pack_into("<HHH", data, 52, 64, 56, 1 if extra_kind is None else 2)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0x100000, 0x100000, 256, 256, 16384)
    if extra_kind is not None:
        struct.pack_into("<IIQQQQQQ", data, 120, extra_kind, 0, 0, 0, 0, 0, 0, 0)
    return bytes(data)


def changed(data: bytes, format_: str, offset: int, *values: int) -> bytes:
    copy = bytearray(data)
    struct.pack_into(format_, copy, offset, *values)
    return bytes(copy)


class ElfValidationTests(unittest.TestCase):
    def test_public_output_identity_is_frozen_and_core_has_no_reference_override(self):
        self.assertEqual("libdroide_cli.so", builder.OUTPUT_NAME)
        self.assertEqual(4528, builder.OUTPUT_BYTES)
        self.assertEqual("913b313398b30115416acd20959b1c449eab0563c15c83230862b03e44e7cfee",
                         builder.OUTPUT_SHA256)
        self.assertEqual(["ndk_path", "output_dir", "root"],
                         list(inspect.signature(builder.build_sources).parameters))

    def test_valid_static_arm64_executable(self):
        result = builder.validate_elf(elf_fixture())
        self.assertEqual("ET_EXEC", result["type"])
        self.assertEqual(16384, result["minimumLoadAlignment"])
        self.assertTrue(result["static"])

    def test_invalid_elf_identity_and_size(self):
        valid = elf_fixture()
        invalid = (valid[:63], valid + b"x" * 65536, b"NOPE" + valid[4:],
                   changed(valid, "B", 4, 1), changed(valid, "B", 5, 2),
                   changed(valid, "H", 16, 3), changed(valid, "H", 18, 62),
                   changed(valid, "I", 20, 2))
        for data in invalid:
            with self.subTest(data=data[:24]), self.assertRaises(builder.SourceBuildError):
                builder.validate_elf(data)

    def test_bad_program_header_dimensions(self):
        valid = elf_fixture()
        invalid = (changed(valid, "Q", 32, 1), changed(valid, "Q", 32, 240),
                   changed(valid, "H", 52, 63), changed(valid, "H", 54, 55),
                   changed(valid, "H", 54, 57),
                   changed(valid, "H", 56, 0), changed(valid, "H", 56, 33))
        for data in invalid:
            with self.subTest(data=data[32:58]), self.assertRaises(builder.SourceBuildError):
                builder.validate_elf(data)

    def test_aarch64_entry_must_be_four_byte_aligned(self):
        with self.assertRaisesRegex(builder.SourceBuildError, "four-byte aligned"):
            builder.validate_elf(changed(elf_fixture(), "Q", 24, 0x1000C1))

    def test_interpreter_and_dynamic_segments_rejected(self):
        for kind in (2, 3):
            with self.subTest(kind=kind), self.assertRaisesRegex(builder.SourceBuildError, "PT_DYNAMIC/PT_INTERP"):
                builder.validate_elf(elf_fixture(extra_kind=kind))

    def test_bad_load_alignment_and_sizes(self):
        valid = elf_fixture()
        invalid = (changed(valid, "Q", 112, 4096), changed(valid, "Q", 112, 49152),
                   changed(valid, "Q", 80, 0x100001), changed(valid, "Q", 96, 257),
                   changed(valid, "Q", 104, 255))
        for data in invalid:
            with self.subTest(data=data[64:120]), self.assertRaises(builder.SourceBuildError):
                builder.validate_elf(data)

    def test_entry_must_be_file_backed_and_executable(self):
        valid = elf_fixture()
        invalid = (changed(valid, "I", 68, 4), changed(valid, "Q", 24, 0x200000),
                   changed(valid, "I", 64, 4))
        for data in invalid:
            with self.subTest(data=data[24:72]), self.assertRaises(builder.SourceBuildError):
                builder.validate_elf(data)


class SourceBuildTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="droide-cli-proof-controls-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "repo"
        self.root.mkdir()
        self.source = b"/* synthetic host control: compiler must be mocked */\n"
        self.bundled = elf_fixture()
        for relative, data in ((builder.SOURCE_RELATIVE, self.source),
                               (builder.BUNDLED_RELATIVE, self.bundled),
                               (builder.INSTALLER_RELATIVE,
                                f'const val SHA256 = "{builder.sha256(self.bundled)}"\n'.encode())):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.ndk = self.base / "ndk"
        self.ndk.mkdir()
        (self.ndk / "source.properties").write_text(f"Pkg.Revision = {builder.NDK_REVISION}\n", encoding="utf-8")
        self.bin_dir = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        self.bin_dir.mkdir(parents=True)
        for name in ("clang", "ld.lld"):
            tool = self.bin_dir / name
            tool.write_bytes(b"NOT AN EXECUTABLE: tests must mock run_tool\n")
            tool.chmod(0o755)
        self.output = self.base / "proof"
        self.calls = []
        self.binaries = [self.bundled, self.bundled]
        for patcher in (patch.object(builder, "SOURCE_SHA256", builder.sha256(self.source)),
                        patch.object(builder, "OUTPUT_SHA256", builder.sha256(self.bundled)),
                        patch.object(builder, "OUTPUT_BYTES", len(self.bundled)),
                        patch.object(builder.platform, "system", return_value="Linux"),
                        patch.object(builder.platform, "machine", return_value="x86_64")):
            patcher.start()
            self.addCleanup(patcher.stop)

    def fake_tool(self, command, cwd, environment, timeout=120):
        self.calls.append((command, cwd, environment))
        self.assertIn(Path(command[0]).name, ("clang", "ld.lld"))
        if command[1:] == ["--version"]:
            return "synthetic pinned-tool version"
        self.assertEqual("clang", Path(command[0]).name)
        self.assertEqual(list(builder.BUILD_FLAGS), command[1:1 + len(builder.BUILD_FLAGS)])
        destination = Path(command[command.index("-o") + 1])
        self.assertEqual(self.source, Path(command[-1]).read_bytes())
        destination.write_bytes(self.binaries.pop(0))
        return ""

    def run_proof(self):
        with patch.object(builder, "run_tool", side_effect=self.fake_tool):
            return builder.build_proof(self.ndk, self.output, self.root)

    def run_sources(self):
        with patch.object(builder, "run_tool", side_effect=self.fake_tool):
            return builder.build_sources(self.ndk, self.output, self.root)

    def test_two_isolated_builds_and_baseline_preserved(self):
        originals = {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        report = self.run_proof()
        self.assertEqual(4, len(self.calls))
        self.assertTrue(report["reproducibleAcrossTwoBuildDirectories"])
        self.assertTrue(report["matchesBundledBytes"])
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertTrue(report["baselineComparisonPerformed"])
        self.assertEqual([builder.BUNDLED_RELATIVE.as_posix()], report["retainedArtifactInputs"])
        self.assertFalse(report["packagedPayloadsChanged"])
        self.assertFalse(report["arm64OutputExecuted"])
        self.assertFalse(report["androidRuntimeCompatibilityVerified"])
        self.assertFalse(report["fdroidSourceBuildAcceptanceVerified"])
        self.assertEqual({"first", "second"}, {item["name"] for item in report["builds"]})
        self.assertEqual(report, json.loads((self.output / "build-manifest.json").read_text(encoding="utf-8")))
        core = json.loads((self.output / "source-build-manifest.json").read_text(encoding="utf-8"))
        self.assertFalse(core["baselineComparisonPerformed"])
        self.assertNotIn("matchesBundledBytes", core)
        self.assertEqual(originals, {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
        for build in report["builds"]:
            binary = Path(build["directory"]) / builder.BUNDLED_RELATIVE.name
            self.assertEqual(0, binary.stat().st_mode & 0o111)

    def test_deterministic_but_wrong_output_identity_fails_without_proof_manifest(self):
        other = self.bundled[:-1] + b"x"
        self.binaries = [other, other]
        with self.assertRaisesRegex(builder.SourceBuildError, "pinned output identity"):
            self.run_proof()
        self.assertFalse((self.output / "build-manifest.json").exists())
        self.assertFalse((self.output / "source-build-manifest.json").exists())
        self.assertEqual(self.bundled, (self.root / builder.BUNDLED_RELATIVE).read_bytes())

    def test_source_core_succeeds_with_reference_physically_absent(self):
        (self.root / builder.BUNDLED_RELATIVE).unlink()
        report = self.run_sources()
        self.assertEqual("isolated-source-build-only", report["status"])
        self.assertEqual(4, len(self.calls))
        self.assertEqual([], report["retainedArtifactInputs"])
        self.assertFalse(report["baselineComparisonPerformed"])
        self.assertNotIn("bundledBaseline", report)
        self.assertNotIn("matchesBundledBytes", report)
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertTrue(report["inputsAndToolsUnchanged"])
        self.assertTrue(report["outputFilesUnchanged"])
        self.assertFalse(report["publicationApproved"])
        self.assertEqual(report, json.loads((self.output / "source-build-manifest.json").read_text()))
        self.assertFalse((self.output / "build-manifest.json").exists())
        self.assertFalse((self.root / builder.BUNDLED_RELATIVE).exists())

    def test_source_core_never_opens_retained_native_tree(self):
        native_tree = self.root / "app/src/main/jniLibs"
        original_open = Path.open
        opened = []

        def guarded_open(path, *args, **kwargs):
            resolved = path.resolve()
            if resolved == native_tree or native_tree in resolved.parents:
                self.fail("Source-only core tried to open retained native input: " + str(path))
            opened.append(resolved)
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=guarded_open):
            report = self.run_sources()
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertIn(self.root / builder.SOURCE_RELATIVE, opened)
        self.assertIn(self.root / builder.INSTALLER_RELATIVE, opened)
        self.assertNotIn(self.root / builder.BUNDLED_RELATIVE, opened)

    def test_source_core_does_not_use_poisoned_retained_reference(self):
        retained = self.root / builder.BUNDLED_RELATIVE
        retained.write_bytes(b"not an ELF; must never be read or copied")
        report = self.run_sources()
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertEqual(b"not an ELF; must never be read or copied", retained.read_bytes())

    def test_proof_still_requires_retained_reference_before_compiler(self):
        (self.root / builder.BUNDLED_RELATIVE).unlink()
        with self.assertRaisesRegex(builder.SourceBuildError, "regular, non-symlink"):
            self.run_proof()
        self.assertFalse(self.calls)
        self.assertFalse(self.output.exists())

    def test_source_core_rejects_wrong_size_even_if_hash_gate_is_satisfied(self):
        with patch.object(builder, "OUTPUT_BYTES", len(self.bundled) + 1):
            with self.assertRaisesRegex(builder.SourceBuildError, "pinned output identity"):
                self.run_sources()
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_proof_independently_compares_both_generated_files(self):
        for mismatch in ("first", "second"):
            with self.subTest(build=mismatch):
                self.output = self.base / ("independent-proof-" + mismatch)

                def fake_core(ndk_path, output_dir, root):
                    output_dir.mkdir()
                    for name in ("first", "second"):
                        directory = output_dir / name
                        directory.mkdir()
                        raw = self.bundled[:-1] + b"x" if name == mismatch else self.bundled
                        (directory / builder.OUTPUT_NAME).write_bytes(raw)
                    # A success claim cannot substitute for independent byte reads.
                    return {"matchesPinnedOutputIdentity": True}

                with patch.object(builder, "build_sources", side_effect=fake_core):
                    with self.assertRaisesRegex(builder.SourceBuildError, "Retained CLI byte comparison failed"):
                        builder.build_proof(self.ndk, self.output, self.root)
                self.assertFalse((self.output / "build-manifest.json").exists())

    def test_nondeterminism_fails_without_success_manifest(self):
        self.binaries = [self.bundled, self.bundled[:-1] + b"x"]
        with self.assertRaisesRegex(builder.SourceBuildError, "not byte-identical"):
            self.run_proof()
        self.assertFalse((self.output / "build-manifest.json").exists())

    def test_invalid_compiler_output_fails(self):
        self.binaries = [b"not an ELF"]
        with self.assertRaisesRegex(builder.SourceBuildError, "ELF size"):
            self.run_proof()

    def test_source_pin_fails_before_compiler(self):
        (self.root / builder.SOURCE_RELATIVE).write_bytes(b"changed source")
        with self.assertRaisesRegex(builder.SourceBuildError, "source checksum"):
            self.run_proof()
        self.assertFalse(self.calls)
        self.assertFalse(self.output.exists())

    def test_existing_installer_pin_enforced(self):
        (self.root / builder.INSTALLER_RELATIVE).write_text('const val SHA256 = "' + "0" * 64 + '"\n', encoding="utf-8")
        with self.assertRaisesRegex(builder.SourceBuildError, "installer integrity pin"):
            self.run_proof()
        self.assertFalse(self.calls)

    def test_source_core_rejects_missing_or_duplicate_installer_pin(self):
        pin = f'const val SHA256 = "{builder.OUTPUT_SHA256}"\n'
        for content in ("// missing integrity pin\n", pin + pin):
            with self.subTest(content=content):
                (self.root / builder.INSTALLER_RELATIVE).write_text(content)
                with self.assertRaisesRegex(builder.SourceBuildError, "installer integrity pin"):
                    self.run_sources()
                self.assertFalse(self.calls)
                self.assertFalse(self.output.exists())

    def test_wrong_or_duplicate_ndk_revision_fails(self):
        for content in ("Pkg.Revision = 27.0.0\n", "no revision\n",
                        f"Pkg.Revision = {builder.NDK_REVISION}\nPkg.Revision = {builder.NDK_REVISION}\n"):
            with self.subTest(content=content):
                (self.ndk / "source.properties").write_text(content, encoding="utf-8")
                with self.assertRaisesRegex(builder.SourceBuildError, "pinned Android NDK"):
                    self.run_proof()
                self.assertFalse(self.calls)

    def test_missing_or_nonexecutable_tool_rejected(self):
        (self.bin_dir / "ld.lld").chmod(0o644)
        with self.assertRaisesRegex(builder.SourceBuildError, "executable tool"):
            self.run_proof()
        self.assertFalse(self.calls)

    def test_output_must_be_new_absolute_and_external(self):
        existing = self.base / "existing"
        existing.mkdir()
        for path in (Path("relative-output"), self.root / "build", self.ndk / "proof",
                     existing, self.base / "nonexistent-parent" / "proof"):
            with self.subTest(path=path), self.assertRaises(builder.SourceBuildError):
                builder.checked_output(path, self.root, self.ndk)

    def test_symlink_parent_into_repository_is_not_external(self):
        alias = self.base / "repo-alias"
        alias.symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(builder.SourceBuildError, "outside"):
            builder.checked_output(alias / "proof", self.root, self.ndk)

    def test_existing_output_symlink_never_overwritten(self):
        self.output.symlink_to(self.base / "absent", target_is_directory=True)
        with self.assertRaisesRegex(builder.SourceBuildError, "must be new"):
            builder.checked_output(self.output, self.root, self.ndk)

    def test_environment_does_not_inherit_credentials_or_compiler_overrides(self):
        with patch.dict(os.environ, {"TOKEN": "secret", "LD_PRELOAD": "/untrusted/lib.so",
                                     "CC": "/untrusted/cc", "CFLAGS": "-bad", "HOME": "/private"}, clear=True):
            environment = builder.build_environment(self.bin_dir, self.base)
        self.assertEqual({"PATH", "LC_ALL", "LANG", "TZ", "SOURCE_DATE_EPOCH", "TMPDIR"}, set(environment))
        self.assertNotIn("secret", repr(environment))
        self.assertTrue(environment["PATH"].startswith(str(self.bin_dir) + os.pathsep))

    def test_source_changes_during_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                (self.root / builder.SOURCE_RELATIVE).write_bytes(b"concurrent change")
            return result
        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "source/text input changed"):
                builder.build_proof(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "build-manifest.json").exists())

    def test_retained_reference_changes_during_build_rejected_by_wrapper(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                (self.root / builder.BUNDLED_RELATIVE).write_bytes(self.bundled[:-1] + b"x")
            return result

        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "Retained CLI baseline changed"):
                builder.build_proof(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "build-manifest.json").exists())
        # The separate core result is valid; it makes no retained-byte claim.
        core = json.loads((self.output / "source-build-manifest.json").read_text())
        self.assertFalse(core["baselineComparisonPerformed"])

    def test_installer_text_mutation_during_core_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                path = self.root / builder.INSTALLER_RELATIVE
                path.write_bytes(path.read_bytes() + b"// concurrent mutation\n")
            return result

        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "source/text input changed"):
                builder.build_sources(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_ndk_properties_mutation_during_core_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                (self.ndk / "source.properties").write_text("Pkg.Revision = changed\n")
            return result

        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "revision metadata changed"):
                builder.build_sources(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_builder_code_reread_rejects_change_without_editing_helper(self):
        original_read = builder.read_regular
        changed_builder = False

        def mutate(command, cwd, environment, timeout=120):
            nonlocal changed_builder
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                changed_builder = True
            return result

        def read(path, bound=builder.MAX_INPUT_BYTES):
            data = original_read(path, bound)
            return data + b"# changed\n" if path == HELPER.resolve() and changed_builder else data

        with patch.object(builder, "run_tool", side_effect=mutate), patch.object(builder, "read_regular", side_effect=read):
            with self.assertRaisesRegex(builder.SourceBuildError, "builder source changed"):
                builder.build_sources(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_first_output_mutation_during_second_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command and cwd.name == "second":
                (self.output / "first" / builder.OUTPUT_NAME).write_bytes(self.bundled[:-1] + b"x")
            return result

        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "Built CLI output changed"):
                builder.build_sources(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_manifest_failure_does_not_return_a_source_success(self):
        with patch.object(builder, "write_manifest", side_effect=OSError("injected receipt failure")):
            with self.assertRaisesRegex(OSError, "injected receipt failure"):
                self.run_sources()
        self.assertFalse((self.output / "source-build-manifest.json").exists())

    def test_manifest_write_flush_and_close_failures_never_publish_success(self):
        original_open = Path.open
        for stage in ("write", "flush", "close"):
            with self.subTest(stage=stage):
                path = self.base / (stage + "-manifest.json")

                class FailingStream:
                    def __init__(self, stream):
                        self.stream = stream

                    def __enter__(self):
                        self.stream.__enter__()
                        return self

                    def __exit__(self, *arguments):
                        result = self.stream.__exit__(*arguments)
                        if stage == "close":
                            raise OSError("injected close failure")
                        return result

                    def write(self, payload):
                        if stage == "write":
                            self.stream.write(payload[:5])
                            raise OSError("injected write failure")
                        return self.stream.write(payload)

                    def flush(self):
                        if stage == "flush":
                            raise OSError("injected flush failure")
                        self.stream.flush()

                    def fileno(self):
                        return self.stream.fileno()

                def controlled_open(open_path, *args, **kwargs):
                    stream = original_open(open_path, *args, **kwargs)
                    return FailingStream(stream) if open_path.name.endswith(".pending") else stream

                with patch.object(Path, "open", autospec=True, side_effect=controlled_open):
                    with self.assertRaisesRegex(OSError, "injected " + stage + " failure"):
                        builder.write_manifest(path, {"status": "success"})
                self.assertFalse(path.exists())
                self.assertFalse(path.with_name(path.name + ".pending").exists())

    def test_manifest_fsync_failure_never_publishes_success(self):
        path = self.base / "sync-manifest.json"
        with patch.object(builder.os, "fsync", side_effect=OSError("injected sync failure")):
            with self.assertRaisesRegex(OSError, "injected sync failure"):
                builder.write_manifest(path, {"status": "success"})
        self.assertFalse(path.exists())
        self.assertFalse(path.with_name(path.name + ".pending").exists())

    def test_manifest_publication_is_exclusive_and_retains_completed_diagnostic_link(self):
        path = self.base / "exclusive-manifest.json"
        builder.write_manifest(path, {"status": "original"})
        original = path.read_bytes()
        pending = path.with_name(path.name + ".pending")
        self.assertEqual(2, path.stat().st_nlink)
        self.assertEqual(path.stat().st_ino, pending.stat().st_ino)
        self.assertEqual(original, pending.read_bytes())
        with self.assertRaises(FileExistsError):
            builder.write_manifest(path, {"status": "replacement"})
        self.assertEqual(original, path.read_bytes())
        self.assertEqual(original, pending.read_bytes())

    def test_manifest_success_never_attempts_post_publication_unlink(self):
        path = self.base / "no-cleanup-manifest.json"
        with patch.object(Path, "unlink", side_effect=OSError("injected cleanup failure")) as unlink:
            builder.write_manifest(path, {"status": "success"})
            unlink.assert_not_called()
        self.assertEqual({"status": "success"}, json.loads(path.read_text()))
        self.assertEqual(path.read_bytes(), path.with_name(path.name + ".pending").read_bytes())

    def test_manifest_final_leaf_collision_never_replaces_existing_receipt(self):
        path = self.base / "existing-final-manifest.json"
        path.write_bytes(b"existing receipt")
        with self.assertRaises(FileExistsError):
            builder.write_manifest(path, {"status": "replacement"})
        self.assertEqual(b"existing receipt", path.read_bytes())
        pending = path.with_name(path.name + ".pending")
        self.assertEqual({"status": "replacement"}, json.loads(pending.read_text()))
        self.assertNotEqual(path.stat().st_ino, pending.stat().st_ino)

    def test_existing_pending_manifest_is_not_removed_or_reused(self):
        path = self.base / "pending-manifest.json"
        pending = path.with_name(path.name + ".pending")
        pending.write_bytes(b"pre-existing pending data")
        with self.assertRaises(FileExistsError):
            builder.write_manifest(path, {"status": "success"})
        self.assertEqual(b"pre-existing pending data", pending.read_bytes())
        self.assertFalse(path.exists())

    def test_oversized_manifest_is_rejected_before_publication(self):
        path = self.base / "large-manifest.json"
        with self.assertRaisesRegex(builder.SourceBuildError, "bounded diagnostic size"):
            builder.write_manifest(path, {"data": "x" * builder.MAX_MANIFEST_BYTES})
        self.assertFalse(path.exists())
        self.assertFalse(path.with_name(path.name + ".pending").exists())

    def test_core_compiler_failure_never_falls_back_to_retained_binary(self):
        with patch.object(builder, "run_tool", side_effect=builder.SourceBuildError("compiler unavailable")):
            with self.assertRaisesRegex(builder.SourceBuildError, "compiler unavailable"):
                builder.build_sources(self.ndk, self.output, self.root)
        self.assertEqual(self.bundled, (self.root / builder.BUNDLED_RELATIVE).read_bytes())
        self.assertFalse((self.output / "source-build-manifest.json").exists())
        self.assertFalse((self.output / "first" / builder.OUTPUT_NAME).exists())

    def test_regular_reads_are_bounded_and_symlinks_rejected(self):
        source = self.root / builder.SOURCE_RELATIVE
        with self.assertRaisesRegex(builder.SourceBuildError, "input bound"):
            builder.read_regular(source, len(self.source) - 1)
        alias = self.base / "alias.c"
        alias.symlink_to(source)
        with self.assertRaisesRegex(builder.SourceBuildError, "non-symlink"):
            builder.read_regular(alias)

    def test_no_compiler_called_if_output_already_exists(self):
        self.output.mkdir()
        with self.assertRaisesRegex(builder.SourceBuildError, "must be new"):
            self.run_proof()
        self.assertFalse(self.calls)

    def test_compiler_or_linker_mutation_during_build_rejected(self):
        for name in ("clang", "ld.lld"):
            with self.subTest(tool=name):
                original = (self.bin_dir / name).read_bytes()
                self.output = self.base / ("proof-mutated-" + name)
                self.binaries = [self.bundled, self.bundled]

                def mutate(command, cwd, environment, timeout=120):
                    result = self.fake_tool(command, cwd, environment, timeout)
                    if "-o" in command:
                        (self.bin_dir / name).write_bytes(b"changed compiler/linker")
                    return result

                with patch.object(builder, "run_tool", side_effect=mutate):
                    with self.assertRaisesRegex(builder.SourceBuildError, "compiler/linker changed"):
                        builder.build_proof(self.ndk, self.output, self.root)
                self.assertFalse((self.output / "build-manifest.json").exists())
                (self.bin_dir / name).write_bytes(original)

    def test_compiler_source_copy_mutation_during_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                Path(command[-1]).write_bytes(b"changed isolated source")
            return result

        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "source copy changed"):
                builder.build_proof(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "build-manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
