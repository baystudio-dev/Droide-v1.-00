#!/usr/bin/env python3
"""Host-only controls; synthetic ELF and mocked compiler, never ARM64 execution."""
from __future__ import annotations

import importlib.util
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

    def test_two_isolated_builds_and_baseline_preserved(self):
        originals = {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()}
        report = self.run_proof()
        self.assertEqual(4, len(self.calls))
        self.assertTrue(report["reproducibleAcrossTwoBuildDirectories"])
        self.assertTrue(report["matchesBundledBytes"])
        self.assertFalse(report["packagedPayloadsChanged"])
        self.assertFalse(report["arm64OutputExecuted"])
        self.assertFalse(report["androidRuntimeCompatibilityVerified"])
        self.assertFalse(report["fdroidSourceBuildAcceptanceVerified"])
        self.assertEqual({"first", "second"}, {item["name"] for item in report["builds"]})
        self.assertEqual(report, json.loads((self.output / "build-manifest.json").read_text(encoding="utf-8")))
        self.assertEqual(originals, {str(path): path.read_bytes() for path in self.root.rglob("*") if path.is_file()})
        for build in report["builds"]:
            binary = Path(build["directory"]) / builder.BUNDLED_RELATIVE.name
            self.assertEqual(0, binary.stat().st_mode & 0o111)

    def test_different_bundled_bytes_reported_without_compatibility_claim(self):
        other = self.bundled[:-1] + b"x"
        self.binaries = [other, other]
        report = self.run_proof()
        self.assertFalse(report["matchesBundledBytes"])
        self.assertFalse(report["androidRuntimeCompatibilityVerified"])
        self.assertEqual(self.bundled, (self.root / builder.BUNDLED_RELATIVE).read_bytes())

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

    def test_baseline_changes_during_build_rejected(self):
        def mutate(command, cwd, environment, timeout=120):
            result = self.fake_tool(command, cwd, environment, timeout)
            if "-o" in command:
                (self.root / builder.SOURCE_RELATIVE).write_bytes(b"concurrent change")
            return result
        with patch.object(builder, "run_tool", side_effect=mutate):
            with self.assertRaisesRegex(builder.SourceBuildError, "baseline changed"):
                builder.build_proof(self.ndk, self.output, self.root)
        self.assertFalse((self.output / "build-manifest.json").exists())

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
