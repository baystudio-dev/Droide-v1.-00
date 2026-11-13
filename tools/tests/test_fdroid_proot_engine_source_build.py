#!/usr/bin/env python3
"""Pure source data and mocked build controls; never executes native outputs."""
from __future__ import annotations

import io
import inspect
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
import fdroid_proot_engine_source_build as builder


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


class EngineInputDataTests(unittest.TestCase):
    def test_core_interface_and_output_identity_have_no_reference_override(self):
        self.assertEqual(["ndk_path", "loader_ndk_path", "output_dir", "root"],
                         list(inspect.signature(builder.build_sources).parameters))
        self.assertEqual(265648, builder.ENGINE_BYTES)
        self.assertEqual("6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b", builder.ENGINE_SHA256)

    def test_recipe_paths_reject_whitespace_shell_and_make_metacharacters(self):
        builder.recipe_path(Path("/scratch/proof-path_1.2"))
        for name in ("space x", "dollar$x", "quote'x", 'quote"x', "semi;x", "new\nline", "star*x", "tick`x", "back\\x"):
            with self.subTest(name=name), self.assertRaisesRegex(builder.SourceBuildError, "Recipe paths"):
                builder.recipe_path(Path("/scratch") / name)
        for path in (Path("relative"), Path("/scratch/../proof")):
            with self.subTest(path=path), self.assertRaisesRegex(builder.SourceBuildError, "Recipe paths"):
                builder.recipe_path(path)

    def test_retained_source_and_baseline_data_have_exact_pins(self):
        sources, talloc, shim, baseline, inputs = builder.read_inputs(TOOLS.parent)
        self.assertEqual(builder.EVENT_BEFORE_SHA256, builder.sha(sources[builder.EVENT]))
        self.assertEqual({"talloc.c", "talloc.h"}, set(talloc))
        self.assertEqual(builder.ENGINE_SHA256, builder.sha(baseline))
        self.assertEqual(builder.ENGINE_BYTES, len(baseline))
        self.assertEqual(shim, builder.stable_shim(inputs[builder.STABLE_BUILDER]))
        self.assertIn(b"TALLOC_BUILD_VERSION_RELEASE 2", shim)
        objects = builder.engine_objects(sources["src/GNUmakefile"])
        self.assertIn("extension/native_offload/native_offload.o", objects)
        self.assertEqual(["loader/loader-wrapped.o", "loader/loader-m32-wrapped.o", "loader/loader-info.o"], objects[-3:])

    def test_real_source_input_reader_never_stats_or_opens_native_references(self):
        native = TOOLS.parent / "app/src/main/jniLibs"
        original_open, original_lstat = Path.open, Path.lstat

        def check(path):
            if path == native or native in path.parents:
                self.fail("Source input reader touched retained native path: " + str(path))

        def guarded_open(path, *args, **kwargs):
            check(path)
            return original_open(path, *args, **kwargs)

        def guarded_lstat(path, *args, **kwargs):
            check(path)
            return original_lstat(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=guarded_open), \
             patch.object(Path, "lstat", autospec=True, side_effect=guarded_lstat):
            sources, talloc, shim, inputs = builder.read_source_inputs(TOOLS.parent)
        self.assertEqual(builder.EVENT_BEFORE_SHA256, builder.sha(sources[builder.EVENT]))
        self.assertEqual({"talloc.c", "talloc.h"}, set(talloc))
        self.assertTrue(shim)
        self.assertFalse(any(name.startswith("app/src/main/jniLibs/") for name in inputs))

    def test_talloc_stages_only_the_two_reviewed_source_files(self):
        root = builder.TALLOC_ROOT
        raw = tar_fixture([(root + "/talloc.c", b"C", tarfile.REGTYPE),
                           (root + "/talloc.h", b"H", tarfile.REGTYPE),
                           (root + "/waf", b"never execute", tarfile.REGTYPE)])
        self.assertEqual({"talloc.c": b"C", "talloc.h": b"H"}, builder.talloc_sources(raw))

    def test_talloc_rejects_links_duplicates_paths_missing_and_bounds(self):
        root = builder.TALLOC_ROOT
        valid = [(root + "/talloc.c", b"C", tarfile.REGTYPE), (root + "/talloc.h", b"H", tarfile.REGTYPE)]
        for name in ("/outside", root + "/../evil", root + "/./x", root + "//x", root + "/x\\y", "other/x"):
            with self.subTest(name=name), self.assertRaises(builder.SourceBuildError):
                builder.talloc_sources(tar_fixture(valid + [(name, b"x", tarfile.REGTYPE)]))
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE, tarfile.CHRTYPE):
            with self.subTest(kind=kind), self.assertRaises(builder.SourceBuildError):
                builder.talloc_sources(tar_fixture(valid + [(root + "/x", b"", kind)]))
        for entries in (valid + valid[:1], valid[:1], []):
            with self.subTest(entries=entries), self.assertRaises(builder.SourceBuildError):
                builder.talloc_sources(tar_fixture(entries))
        with self.assertRaises(builder.SourceBuildError):
            builder.talloc_sources(b"malformed")
        with patch.object(builder.loader, "MAX_FILE", 0), self.assertRaises(builder.SourceBuildError):
            builder.talloc_sources(tar_fixture(valid))
        with patch.object(builder.loader, "MAX_EXPANDED", 1), self.assertRaises(builder.SourceBuildError):
            builder.talloc_sources(tar_fixture(valid))
        with patch.object(builder.loader, "MAX_MEMBERS", 1), self.assertRaises(builder.SourceBuildError):
            builder.talloc_sources(tar_fixture(valid))

    def test_shim_is_literal_not_imported_and_production_hash_is_not_optional(self):
        with self.assertRaisesRegex(builder.SourceBuildError, "checksum"):
            builder.stable_shim(b"raise RuntimeError('must never run')\n")
        for raw in (b"SHIM = function()\n", b"SHIM = b'bytes'\n", b"SHIM='a'\nSHIM='b'\n"):
            with patch.object(builder, "STABLE_BUILDER_SHA256", builder.sha(raw)), \
                 self.assertRaisesRegex(builder.SourceBuildError, "literal"):
                builder.stable_shim(raw)

    def test_engine_object_inventory_refuses_offload_removal_and_traversal(self):
        for raw in (b"OBJECTS += \\\n cli/proot.o\n", b"OBJECTS += \\\n ../evil.o \\\n extension/native_offload/native_offload.o\n"):
            with self.subTest(raw=raw), self.assertRaises(builder.SourceBuildError):
                builder.engine_objects(raw)


class EngineBuildControlTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="engine-source-controls-")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root, self.ndk, self.loader_ndk = self.base / "repo", self.base / "ndk28", self.base / "ndk29"
        self.output = self.base / "proof"
        self.root.mkdir()
        self.ndk.mkdir()
        self.loader_ndk.mkdir()
        self.properties, self.loader_properties = b"Pkg.Revision = 28.2.13676358\n", b"Pkg.Revision = 29.0.14206865\n"
        (self.ndk / "source.properties").write_bytes(self.properties)
        (self.loader_ndk / "source.properties").write_bytes(self.loader_properties)
        self.header = self.ndk / "header.h"
        self.header.write_bytes(b"/* trusted fixture SDK header */\n")
        self.loader_header = self.loader_ndk / "header.h"
        self.loader_header.write_bytes(b"/* trusted fixture loader SDK header */\n")
        host = self.base / "host-tools"
        host.mkdir()
        directory = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        directory.mkdir(parents=True)
        self.tools = {"cc": directory / "aarch64-linux-android29-clang", "clang": directory / "clang",
                      "linker": directory / "ld.lld", "strip": directory / "llvm-strip",
                      "ar": directory / "llvm-ar", "ranlib": directory / "llvm-ranlib",
                      "objcopy": directory / "llvm-objcopy", "objdump": directory / "llvm-objdump",
                      **{name: host / name for name in builder.HOST_TOOLS}}
        loader_directory = self.loader_ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        loader_directory.mkdir(parents=True)
        self.loader_tools = {"cc": loader_directory / "aarch64-linux-android24-clang",
                             "clang": loader_directory / "clang", "linker": loader_directory / "ld.lld",
                             "strip": loader_directory / "llvm-strip",
                             **{name: self.tools[name] for name in builder.loader.HOST_TOOLS}}
        for path in set(self.tools.values()) | set(self.loader_tools.values()):
            path.write_bytes(b"NOT EXECUTABLE CODE; all tool calls are mocked\n")
            path.chmod(0o700)
        self.identities = {name: builder.tool_identity(path, self.ndk if name not in builder.HOST_TOOLS else None)
                           for name, path in self.tools.items()}
        self.loader_identities = {name: builder.tool_identity(path, self.loader_ndk if name not in builder.loader.HOST_TOOLS else None)
                                  for name, path in self.loader_tools.items()}
        self.sources = {
            "src/GNUmakefile": b"OBJECTS += \\\n tracee/event.o \\\n extension/native_offload/native_offload.o\n",
            builder.EVENT: b"event before",
            "src/extension/native_offload/native_offload.c": b"offload remains enabled",
            "src/loader/loader.c": b"loader source",
            "src/.check_process_vm.c": b"compile-only feature input",
            "src/.check_seccomp_filter.c": b"compile-only feature input",
        }
        self.after_event = b"event after"
        self.talloc = {"talloc.c": b"talloc source", "talloc.h": b"talloc header"}
        self.shim = b"shim\n"
        self.baseline = b"RETAINED_ENGINE"
        self.inputs = {builder.PATCH: b"reviewed synthetic patch", builder.ENGINE: self.baseline,
                       builder.STABLE_BUILDER: b"not executed"}
        self.inputs.update({"tools/" + name: b"mock proof code, not imported from fixture" for name in (
            "fdroid_proot_loader_source_build.py", "fdroid_proot_loader_elf.py",
            "fdroid_proot_engine_source_build.py", "fdroid_proot_engine_elf.py")})
        for relative, data in self.inputs.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.loaders = {"arm64": b"SOURCE64-COMMENT", "arm32": b"SOURCE32-COMMENT"}
        self.loader_pins = {abi: ("libproot-loader.so" if abi == "arm64" else "libproot-loader32.so", builder.sha(data))
                            for abi, data in self.loaders.items()}
        self.retained_paths = [self.root / builder.ENGINE]
        for abi, (name, _) in self.loader_pins.items():
            relative = builder.loader.NATIVE + "/" + name
            self.inputs[relative] = self.loaders[abi]
            path = self.root / relative
            path.write_bytes(self.loaders[abi])
            self.retained_paths.append(path)
        unused_cli = self.root / builder.loader.NATIVE / "libdroide_cli.so"
        unused_cli.write_bytes(b"UNUSED_RETAINED_CLI")
        self.retained_paths.append(unused_cli)
        self.source_inputs = {name: data for name, data in self.inputs.items()
                              if not name.startswith("app/src/main/jniLibs/")}
        self.wrapper_pairs = [{"arm64": b"SOURCE64", "arm32": b"SOURCE32"} for _ in range(2)]
        self.wrapper_pins = {abi: (len(data), builder.sha(data)) for abi, data in self.wrapper_pairs[0].items()}
        self.wrapper_calls, self.certificate_mutator = [], None
        self.calls, self.make_count, self.loader_calls = [], 0, 0
        self.raw_pairs = [b"RAW_ENGINE", b"RAW_ENGINE"]
        self.stripped_pairs = [self.baseline, self.baseline]
        self.after_make, self.external_header = None, None
        self.bad_patch, self.bad_coupling, self.changed_text, self.changed_wrapper = False, False, False, False
        for patcher in (
            patch.object(builder, "read_inputs", return_value=(self.sources, self.talloc, self.shim, self.baseline, self.inputs)),
            patch.object(builder, "read_source_inputs", return_value=(self.sources, self.talloc, self.shim, self.source_inputs)),
            patch.object(builder, "ndk_tools", return_value=(self.ndk, self.properties, self.tools, self.identities)),
            patch.object(builder.loader, "ndk_tools", return_value=(self.loader_ndk, self.loader_properties, self.loader_tools, self.loader_identities)),
            patch.object(builder.loader, "build_sources", side_effect=self.fake_loader_source),
            patch.object(builder.loader, "build_proof", side_effect=AssertionError("Engine must not call the retained loader proof")),
            patch.object(builder.loader, "inspect_loader", side_effect=self.fake_loader_inspect),
            patch.object(builder.loader, "LOADERS", self.loader_pins),
            patch.object(builder.loader, "OUTPUT_BYTES", {abi: len(data) for abi, data in self.loaders.items()}),
            patch.object(builder, "ENGINE_SHA256", builder.sha(self.baseline)),
            patch.object(builder, "ENGINE_BYTES", len(self.baseline)),
            patch.object(builder, "WRAPPED_LOADERS", self.wrapper_pins),
            patch.object(builder, "EVENT_AFTER_SHA256", builder.sha(self.after_event)),
            patch.object(builder, "run_tool", side_effect=self.fake_tool),
            patch.object(builder, "inspect_engine", side_effect=self.fake_inspect),
            patch.object(builder, "certify_loader_wrapper", side_effect=self.fake_certificate),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def fake_loader_source(self, ndk, output, root):
        self.loader_calls += 1
        self.assertEqual(self.loader_ndk, ndk)
        self.assertEqual(self.root, root)
        output.mkdir()
        for label in ("first", "second"):
            for source in ("loader.c", "assembly.S"):
                builder.write_new(output / label / "src/loader" / source, b"unchanged nested source\n")
            for abi, (name, _) in self.loader_pins.items():
                builder.write_new(output / label / ("post-strip-" + name), self.loaders[abi])
                builder.write_new(output / label / "src/loader" / ("loader" if abi == "arm64" else "loader-m32"), b"raw-" + self.loaders[abi])
                suffix = "" if abi == "arm64" else "-m32"
                for stem, source in (("loader", "loader.c"), ("assembly", "assembly.S")):
                    builder.write_new(output / label / "src/loader" / (stem + suffix + ".d"),
                                      ("loader/" + stem + suffix + ".o: loader/" + source + " " +
                                       str(self.loader_header) + "\n").encode())
            builder.write_new(output / label / "generated-loader-info.c",
                              b"#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=4;\n")
        report = {key: True for key in ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible",
                                       "matchesPinnedOutputIdentity", "inputsAndToolsUnchanged", "gawkOffsetMatchesIndependentSymbols")}
        report.update(status="source-built-both-loader-pinned-identities-reproduced", targetOutputExecuted=False,
                      packagedPayloadsChanged=False, arm64PokedataOffset=4, publicationApproved=False,
                      matchesBundledBytes=False, baselineComparisonPerformed=False, retainedArtifactInputs=[])
        builder.loader.publish_result(output, report, {"summary": report},
                                      summary_name="source-build-summary.json", receipt_name="source-build-receipt.json")
        return report

    def fake_loader_inspect(self, raw, abi, *, require_symbols=False):
        self.assertIn(abi, self.loaders)
        self.assertIn(raw, (self.loaders[abi], b"raw-" + self.loaders[abi]))
        return {"pokedataOffset": 4 if require_symbols else None}

    def fake_inspect(self, raw, *, require_symbols=False, expected_loaders=None, expected_pokedata_offset=None):
        if require_symbols:
            self.assertEqual(self.wrapper_pairs[self.make_count - 1], expected_loaders)
            self.assertEqual(4, expected_pokedata_offset)
        else:
            self.assertIsNone(expected_loaders)
            self.assertIsNone(expected_pokedata_offset)
        verified = require_symbols and not self.bad_coupling
        coupling = {"verified": verified, "constantVerified": verified, "embeddedLoadersVerified": verified,
                    "pokedataOffset": 4 if verified else None, "loaders": {}}
        if verified:
            coupling.update(constantFileOffset=0x40000, relocationsDoNotOverlap=True,
                            scope="Exact provided bytes/constant through unstripped symbols; not source authenticity or instruction-use equivalence")
            for abi, data in expected_loaders.items():
                prefix = "_binary_loader_exe_" if abi == "arm64" else "_binary_loader_m32_exe_"
                coupling["loaders"][abi] = {"sha256": builder.sha(data), "bytes": len(data),
                                             "start": 0x7FFFFFF000, "end": 0x7FFFFFF000 + len(data), "fileOffset": 0x48000,
                                             "symbols": {suffix: prefix + suffix for suffix in ("start", "end", "size")}}
        else:
            coupling["reason"] = "pre-strip symbol gate not requested"
        return {"class": "ELF64", "type": "ET_DYN", "machine": "AArch64", "entryPoint": 0x1234,
                "interpreter": "/system/bin/linker64", "dynamicDependencies": ["libdl.so", "libc.so"],
                "textSha256": "changed" if self.changed_text and raw == self.baseline else "stable",
                "loadableSegmentsSha256": builder.sha(raw),
                "coupling": coupling}

    def fake_certificate(self, source, wrapped, abi, expected_pokedata_offset=None):
        self.wrapper_calls.append((source, wrapped, abi, expected_pokedata_offset))
        self.assertEqual(self.loaders[abi], source)
        self.assertEqual(4 if abi == "arm64" else None, expected_pokedata_offset)
        if self.changed_wrapper:
            raise builder.EngineElfError("wrapper transform rejected")
        self.assertEqual(self.wrapper_pairs[self.make_count - 1][abi], wrapped)
        result = {"sourceSha256": builder.sha(source), "wrappedSha256": builder.sha(wrapped),
                  "sourceBytes": len(source), "wrappedBytes": len(wrapped),
                  "metadataOnlyRewriteVerified": True, "programHeadersUnchanged": True,
                  "allocatedSectionsUnchanged": True, "loadableBytesPreservedExceptSectionTableFields": True,
                  "retainedNonallocatedPayloadsUnchanged": True,
                  "changedSectionHeaderFields": [
                      {"name": "e_shoff", "fileOffset": 40 if abi == "arm64" else 32,
                       "bytes": 8 if abi == "arm64" else 4, "sourceValue": 1048560, "wrappedValue": 1048240},
                      {"name": "e_shnum", "fileOffset": 60 if abi == "arm64" else 48,
                       "bytes": 2, "sourceValue": 6, "wrappedValue": 5},
                      {"name": "e_shstrndx", "fileOffset": 62 if abi == "arm64" else 50,
                       "bytes": 2, "sourceValue": 5, "wrappedValue": 4}],
                  "removedSections": [".comment"], "pokedataOffset": expected_pokedata_offset}
        result.update(abi=abi, allocatedSections=[".text"] + ([".ARM.exidx"] if abi == "arm32" else []),
                      loadableBytesIdentical=False, sourceLoadableSegmentsSha256=builder.sha(b"before mapped bytes"),
                      wrappedLoadableSegmentsSha256=builder.sha(b"after mapped bytes"),
                      retainedNonallocatedSections=[".ARM.attributes"] if abi == "arm32" else [],
                      changedMappedHeaderBytes=[{"fileOffset": 40 if abi == "arm64" else 32, "source": 240, "wrapped": 176}],
                      removedCommentBytes=256, removedCommentSha256=builder.sha(b"compiler identity comment"),
                      pokedataFileOffset=0x40000 if abi == "arm64" else None,
                      executed=False, runtimeCompatibilityVerified=False,
                      scope="Exact observed .comment removal and bounded section-table reserialization; caller must pin source/wrapper/final-engine bytes; not Android execution or general semantic equivalence")
        if self.certificate_mutator:
            self.certificate_mutator(result)
        return result

    def fake_tool(self, command, cwd, environment, log, *, input_bytes=None, timeout=60):
        command = [str(item) for item in command]
        cwd = Path(cwd)
        self.calls.append((command, cwd, dict(environment), input_bytes))
        self.assertIn(command[0], {str(path) for path in self.tools.values()})
        name = Path(command[0]).name
        data = b"fixture diagnostics\n"
        if command[1:] == ["--version"]:
            if name == "aarch64-linux-android29-clang":
                # Exercise the helper's entire allowed generation-string budget.
                data = b"Android clang version " + b"x" * 600 + b"\n"
            elif name == "ld.lld":
                data = b"LLD " + b"x" * 300 + b"\n"
        elif name == "patch":
            self.assertEqual(["-p1", "--fuzz=0", "--batch"], command[1:])
            self.assertEqual(self.inputs[builder.PATCH], input_bytes)
            (cwd / builder.EVENT).write_bytes(b"wrong patch" if self.bad_patch else self.after_event)
        elif name == "aarch64-linux-android29-clang":
            if "-###" in command:
                pass
            elif "-M" in command:
                source = Path(command[4])
                data = ("talloc.o: " + str(source) + " " + str(source.parent / "replace.h") + " " + str(self.header) + "\n").encode()
            else:
                self.assertIn("-c", command)
                self.assertNotIn("-MD", command)
                Path(command[command.index("-o") + 1]).write_bytes(b"TALLOC_OBJECT")
        elif name == "llvm-ar":
            self.assertEqual("rcs", command[1])
            Path(command[2]).write_bytes(b"TALLOC_LIBRARY")
        elif name == "llvm-ranlib":
            self.assertEqual(b"TALLOC_LIBRARY", Path(command[-1]).read_bytes())
        elif name == "make":
            work = cwd.parent.parent
            self.assertEqual([str(item) for item in builder.make_command(self.tools, work)], command)
            (cwd / "proot").write_bytes(self.raw_pairs[self.make_count])
            (cwd / "proot").chmod(0o700)
            for abi, stem in (("arm64", "loader"), ("arm32", "loader-m32")):
                fresh = (cwd / "loader" / stem).read_bytes()
                self.assertEqual(self.loaders[abi], fresh)
                wrapped = b"changed" if self.changed_wrapper else self.wrapper_pairs[self.make_count][abi]
                (cwd / (stem + ".exe")).write_bytes(wrapped)
                (cwd / "loader" / (stem + "-wrapped.o")).write_bytes(b"WRAPPED-" + wrapped)
            header = self.external_header or self.header
            for obj in builder.engine_objects(self.sources["src/GNUmakefile"]):
                if obj.endswith("-wrapped.o"):
                    continue
                source = obj[:-2] + ".c"
                dep = cwd / (obj[:-2] + ".d")
                dep.write_text(obj + ": " + source + " " + str(header) + "\n")
            (cwd / "build.h").write_bytes(b"/* generated feature evidence */\n")
            for feature in ("process_vm", "seccomp_filter"):
                (cwd / (".check_" + feature + ".res")).write_text("#define HAVE_" + feature.upper() + "\n")
                (cwd / (".check_" + feature + ".d")).write_text(
                    ".check_" + feature + ".o: .check_" + feature + ".c " + str(header) + "\n")
            self.make_count += 1
            if self.after_make:
                self.after_make(cwd)
        elif name == "llvm-strip":
            self.assertEqual(2, len(command))
            output = Path(command[-1])
            self.assertEqual(0, stat.S_IMODE(output.stat().st_mode) & 0o111)
            output.write_bytes(self.stripped_pairs[self.make_count - 1])
        else:
            self.fail("Unexpected tool command: " + str(command))
        log.write_bytes(data)
        return data

    def build(self):
        return builder.build_proof(self.ndk, self.loader_ndk, self.output, self.root)

    def build_sources(self):
        return builder.build_sources(self.ndk, self.loader_ndk, self.output, self.root)

    def summary(self):
        return json.loads((self.output / "SUMMARY.json").read_text())

    def source_summary(self):
        return json.loads((self.output / "source-build-summary.json").read_text())

    def test_source_core_succeeds_with_all_four_retained_native_files_absent(self):
        for path in self.retained_paths:
            path.unlink()
        report = self.build_sources()
        self.assertEqual(builder.SOURCE_SUCCESS, report["status"])
        self.assertEqual([], report["retainedArtifactInputs"])
        self.assertFalse(report["baselineComparisonPerformed"])
        self.assertFalse(report["matchesBundledBytes"])
        self.assertFalse(report["publicationApproved"])
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertEqual(1, self.loader_calls)
        self.assertEqual(2, self.make_count)
        self.assertFalse(any(path.exists() for path in self.retained_paths))
        self.assertFalse((self.output / "SUMMARY.json").exists())
        self.assertFalse((self.output / "receipt.json").exists())
        self.assertEqual(report, self.source_summary())
        for item in report["builds"]:
            self.assertTrue(item["matchesPinnedOutputIdentity"])
            self.assertFalse(item["matchesBundledBytes"])
        receipt = json.loads((self.output / "source-build-receipt.json").read_text())
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertEqual({**report, "status": "verified-pending-summary-publication"}, receipt["summary"])
        self.assertFalse(receipt["loaderSourceBuildSummary"]["matchesBundledBytes"])

    def test_source_core_does_not_read_or_repair_poisoned_native_references(self):
        for index, path in enumerate(self.retained_paths):
            path.write_bytes(("poisoned retained file " + str(index)).encode())
        before = {path: path.read_bytes() for path in self.retained_paths}
        report = self.build_sources()
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertEqual(before, {path: path.read_bytes() for path in self.retained_paths})

    def test_source_core_transitively_forbids_native_reference_path_access(self):
        native = self.root / "app/src/main/jniLibs"
        original_open, original_stat, original_lstat = Path.open, Path.stat, Path.lstat

        def check(path):
            if path == native or native in path.parents:
                self.fail("Source-only engine touched retained native input: " + str(path))

        def guarded_open(path, *args, **kwargs):
            check(path)
            return original_open(path, *args, **kwargs)

        def guarded_stat(path, *args, **kwargs):
            check(path)
            return original_stat(path, *args, **kwargs)

        def guarded_lstat(path, *args, **kwargs):
            check(path)
            return original_lstat(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=guarded_open), \
             patch.object(Path, "stat", autospec=True, side_effect=guarded_stat), \
             patch.object(Path, "lstat", autospec=True, side_effect=guarded_lstat), \
             patch.object(builder, "read_inputs", side_effect=AssertionError("Proof reader called by core")), \
             patch.object(builder.loader, "read_inputs", side_effect=AssertionError("Loader proof reader called by core")):
            report = self.build_sources()
        self.assertTrue(report["matchesPinnedOutputIdentity"])

    def test_source_core_rejects_wrong_engine_size_despite_matching_hash(self):
        with patch.object(builder, "ENGINE_BYTES", len(self.baseline) + 1), \
             self.assertRaisesRegex(builder.SourceBuildError, "fixed output checksum/size"):
            self.build_sources()
        self.assertFalse(self.source_summary()["matchesPinnedOutputIdentity"])
        self.assertEqual("failed", self.source_summary()["status"])
        self.assertFalse((self.output / "source-build-receipt.json").exists())

    def test_source_core_rechecks_fresh_loader_sizes_before_embedding(self):
        with patch.object(builder.loader, "OUTPUT_BYTES", {"arm64": len(self.loaders["arm64"]) + 1,
                                                          "arm32": len(self.loaders["arm32"])}), \
             self.assertRaisesRegex(builder.SourceBuildError, "pinned output identity"):
            self.build_sources()
        self.assertEqual(0, self.make_count)
        self.assertFalse(self.source_summary()["sourceBuildSucceeded"])

    def test_source_core_rejects_a_reference_dependent_nested_result(self):
        def dishonest(ndk, output, root):
            report = self.fake_loader_source(ndk, output, root)
            report["baselineComparisonPerformed"] = True
            report["retainedArtifactInputs"] = [builder.loader.NATIVE + "/libproot-loader.so"]
            return report

        with patch.object(builder.loader, "build_sources", side_effect=dishonest), \
             self.assertRaisesRegex(builder.SourceBuildError, "status/limits"):
            self.build_sources()
        self.assertEqual(0, self.make_count)

    def test_source_core_failure_never_falls_back_to_retained_payloads(self):
        with patch.object(builder, "run_tool", side_effect=builder.SourceBuildError("compiler failed")), \
             self.assertRaisesRegex(builder.SourceBuildError, "compiler failed"):
            self.build_sources()
        self.assertEqual(0, self.make_count)
        self.assertFalse(self.source_summary()["sourceBuildSucceeded"])
        self.assertFalse((self.output / "first/post-strip-libdroide_proot.so").exists())
        self.assertEqual(self.baseline, (self.root / builder.ENGINE).read_bytes())

    def test_proof_rejects_comparison_input_failure_before_source_build(self):
        with patch.object(builder, "read_inputs", side_effect=builder.SourceBuildError("retained input missing")), \
             self.assertRaisesRegex(builder.SourceBuildError, "retained input missing"):
            self.build()
        self.assertFalse(self.output.exists())
        self.assertEqual(0, self.loader_calls)
        self.assertEqual(0, self.make_count)

    def test_proof_independently_compares_each_engine_output(self):
        original_core = builder.build_sources
        for label in ("first", "second"):
            with self.subTest(build=label):
                self.output = self.base / ("engine-proof-mismatch-" + label)
                self.make_count = self.loader_calls = 0

                def modified(ndk, loader_ndk, output, root):
                    report = original_core(ndk, loader_ndk, output, root)
                    (output / label / "post-strip-libdroide_proot.so").write_bytes(b"changed independently")
                    return report

                with patch.object(builder, "build_sources", side_effect=modified), \
                     self.assertRaisesRegex(builder.SourceBuildError, "Retained engine byte comparison failed for " + label):
                    self.build()
                self.assertFalse(self.summary()["matchesBundledBytes"])
                self.assertEqual("failed", self.summary()["status"])
                self.assertEqual(builder.SOURCE_SUCCESS, self.source_summary()["status"])
                self.assertFalse((self.output / "receipt.json").exists())

    def test_proof_independently_compares_all_four_loader_outputs(self):
        original_core = builder.build_sources
        for label in ("first", "second"):
            for abi in ("arm64", "arm32"):
                with self.subTest(build=label, abi=abi):
                    self.output = self.base / ("loader-proof-mismatch-" + label + "-" + abi)
                    self.make_count = self.loader_calls = 0

                    def modified(ndk, loader_ndk, output, root):
                        report = original_core(ndk, loader_ndk, output, root)
                        path = output / "loader-source-proof" / label / ("post-strip-" + self.loader_pins[abi][0])
                        path.write_bytes(b"changed independently")
                        return report

                    with patch.object(builder, "build_sources", side_effect=modified), \
                         patch.object(builder.loader, "inspect_loader", return_value={"pokedataOffset": 4}), \
                         self.assertRaisesRegex(builder.SourceBuildError, "Retained " + abi + " loader byte comparison failed for " + label):
                        self.build()
                    self.assertFalse(self.summary()["loaderMatchesBundledBytes"])
                    self.assertFalse(self.summary()["matchesBundledBytes"])
                    self.assertEqual("failed", self.summary()["status"])
                    self.assertFalse((self.output / "receipt.json").exists())

    def test_retained_mutation_does_not_affect_core_but_invalidates_wrapper(self):
        self.after_make = lambda _: (self.root / builder.ENGINE).write_bytes(b"concurrent retained mutation")
        with self.assertRaisesRegex(builder.SourceBuildError, "Retained-byte proof input changed"):
            self.build()
        self.assertEqual(builder.SOURCE_SUCCESS, self.source_summary()["status"])
        self.assertFalse(self.source_summary()["baselineComparisonPerformed"])
        self.assertEqual("failed", self.summary()["status"])
        self.assertFalse((self.output / "receipt.json").exists())

    def test_source_summary_mutation_is_rejected_before_wrapper_publication(self):
        original_core = builder.build_sources

        def modified(ndk, loader_ndk, output, root):
            report = original_core(ndk, loader_ndk, output, root)
            (output / "source-build-summary.json").write_bytes(b"{}\n")
            return report

        with patch.object(builder, "build_sources", side_effect=modified), \
             self.assertRaisesRegex(builder.SourceBuildError, "Source-only engine summary changed"):
            self.build()
        self.assertEqual("failed", self.summary()["status"])
        self.assertFalse((self.output / "receipt.json").exists())

    def test_source_receipt_binding_mutation_is_rejected_before_wrapper_publication(self):
        original_core = builder.build_sources

        def modified(ndk, loader_ndk, output, root):
            report = original_core(ndk, loader_ndk, output, root)
            path = output / "source-build-receipt.json"
            receipt = json.loads(path.read_text())
            receipt["authoritativeSummary"]["sha256"] = "0" * 64
            path.write_text(json.dumps(receipt))
            return report

        with patch.object(builder, "build_sources", side_effect=modified), \
             self.assertRaisesRegex(builder.SourceBuildError, "not bound to its completed summary"):
            self.build()
        self.assertEqual("failed", self.summary()["status"])

    def test_two_engine_builds_use_two_fresh_loader_pairs_without_production_mutation(self):
        report = self.build()
        self.assertEqual(1, self.loader_calls)
        self.assertEqual(2, self.make_count)
        self.assertEqual(4, len(self.wrapper_calls))
        self.assertTrue(all(source != wrapped for source, wrapped, *_ in self.wrapper_calls))
        self.assertEqual(builder.SUCCESS, report["status"])
        self.assertEqual(report, self.summary())
        for key in ("loaderSourceProofSucceeded", "sourceBuildSucceeded", "rawReproducible",
                    "postStripReproducible", "matchesBundledBytes", "loaderCouplingVerified",
                    "loaderWrapperTransformsVerified", "inputsAndToolsUnchanged"):
            self.assertTrue(report[key], key)
        for key in ("packagedPayloadsChanged", "targetOutputExecuted", "androidRuntimeCompatibilityVerified",
                    "fdroidAcceptanceVerified", "ndkArchivesAuthenticated"):
            self.assertFalse(report[key], key)
        for name, data in self.inputs.items():
            self.assertEqual(data, (self.root / name).read_bytes())
        for label in ("first", "second"):
            self.assertEqual(0, (self.output / label / "post-strip-libdroide_proot.so").stat().st_mode & 0o111)
        receipt = json.loads((self.output / "receipt.json").read_text())
        self.assertEqual({**report, "status": "verified-pending-summary-publication"}, receipt["summary"])
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertEqual(builder.sha((self.output / "SUMMARY.json").read_bytes()), receipt["authoritativeSummary"]["sha256"])
        self.assertEqual(4, len(receipt["loaderBaselineComparisons"]))
        self.assertTrue(report["loaderMatchesBundledBytes"])
        self.assertTrue(report["loaderBaselineComparisonPerformed"])
        self.assertFalse(receipt["loaderSourceBuildSummary"]["baselineComparisonPerformed"])
        self.assertFalse(receipt["loaderSourceBuildSummary"]["matchesBundledBytes"])
        self.assertLessEqual((self.output / "SUMMARY.json").stat().st_size, 12 * 1024)

    def test_original_engine_assignments_and_talloc_flags_are_preserved(self):
        with patch.dict(os.environ, {"SECRET_TEST": "no", "MAKEFLAGS": "evil", "LD_PRELOAD": "evil",
                                     "PROOT_UNBUNDLE_LOADER": "wrong", "CFLAGS": "wrong"}):
            self.build()
        makes = []
        for command, cwd, environment, _ in self.calls:
            for key in ("SECRET_TEST", "MAKEFLAGS", "LD_PRELOAD", "PROOT_UNBUNDLE_LOADER", "CFLAGS", "CPPFLAGS", "LDFLAGS"):
                self.assertNotIn(key, environment)
            if Path(command[0]).name == "make" and command[1:] != ["--version"]:
                makes.append(command)
                assignments = {item.split("=", 1)[0]: item.split("=", 1)[1] for item in command if "=" in item}
                flags = builder.engine_flags(cwd.parent.parent)
                self.assertEqual(flags, tuple(assignments[key] for key in ("CPPFLAGS", "CFLAGS", "LDFLAGS")))
                self.assertEqual(["proot", "loader.exe", "loader-m32.exe"], command[-3:])
                self.assertEqual(3, command.count("-o"))
                self.assertEqual("false", assignments["GIT"])
        self.assertEqual(2, len(makes))
        self.assertEqual(6, sum("-###" in command for command, *_ in self.calls))

    def test_realistic_full_summary_evidence_fits_the_diagnostic_budget(self):
        report = self.build()
        source_report = self.source_summary()
        self.assertEqual(4, len(source_report["proofCodeSha256"]))
        self.assertEqual(8, len(source_report["loaderInstalledToolSha256"]))
        self.assertEqual(13, len(source_report["engineInstalledToolSha256"]))
        provenance = report["sourceBuildProvenanceRef"]
        self.assertTrue(provenance["verified"])
        self.assertEqual("source-build-summary.json", provenance["file"])
        self.assertEqual(list(builder.PROVENANCE_FIELDS), provenance["fields"])
        self.assertEqual(builder.sha((self.output / provenance["file"]).read_bytes()), provenance["sha256"])
        for field in builder.PROVENANCE_FIELDS:
            self.assertNotIn(field, report)
            self.assertIn(field, source_report)
        self.assertEqual(256, len(report["compilerGenerationPreview"]))
        self.assertEqual(128, len(report["linkerGenerationPreview"]))
        self.assertEqual({"class": "ELF64", "type": "ET_DYN", "machine": "AArch64",
                          "interpreter": "/system/bin/linker64", "dynamicDependencies": ["libdl.so", "libc.so"]},
                         report["engineElfContract"])
        for item in report["builds"]:
            for stage in item["loaderWrapping"].values():
                self.assertEqual([1048560, 1048240], stage["sectionTableChanges"]["e_shoff"])
                self.assertEqual([6, 5], stage["sectionTableChanges"]["e_shnum"])
                self.assertEqual([5, 4], stage["sectionTableChanges"]["e_shstrndx"])
                self.assertNotIn("changedMappedHeaderBytes", stage)
            coupling = item["rawElf"]["coupling"]
            self.assertTrue(item["rawElf"]["sharedContractVerified"])
            self.assertTrue(item["postStripElf"]["sharedContractVerified"])
            self.assertEqual({"verified": False, "symbolsRequested": False}, item["postStripElf"]["coupling"])
            self.assertTrue(coupling["relocationsDoNotOverlap"])
            self.assertEqual("loaderWrapping", coupling["loaderIdentityRef"])
            self.assertNotIn("scope", coupling)
            self.assertIn("constantFileOffset", coupling)
            for evidence in coupling["loaders"].values():
                self.assertIn("fileOffset", evidence)
                self.assertNotIn("symbols", evidence)
        receipt = json.loads((self.output / "receipt.json").read_text())
        self.assertGreater(len(receipt["engineToolVersions"]["cc"].splitlines()[0]), 256)
        self.assertGreater(len(receipt["engineToolVersions"]["linker"].splitlines()[0]), 128)
        for item in receipt["builds"]:
            self.assertEqual({"arm64", "arm32"}, set(item["loaderWrapperCertificates"]))
            for cert in item["loaderWrapperCertificates"].values():
                self.assertTrue(cert["programHeadersUnchanged"])
                self.assertEqual([".comment"], cert["removedSections"])
                self.assertEqual(3, len(cert["changedSectionHeaderFields"]))
                self.assertTrue(all(set(field) == {"name", "fileOffset", "bytes", "sourceValue", "wrappedValue"}
                                    for field in cert["changedSectionHeaderFields"]))
                self.assertIn("changedMappedHeaderBytes", cert)
            coupling = item["rawElf"]["coupling"]
            self.assertIn("scope", coupling)
            for evidence in coupling["loaders"].values():
                self.assertEqual({"start", "end", "size"}, set(evidence["symbols"]))
        self.assertLessEqual((self.output / "SUMMARY.json").stat().st_size, 12 * 1024)
        self.assertLessEqual((self.output / "source-build-summary.json").stat().st_size, 12 * 1024)

    def test_core_full_late_failure_keeps_all_provenance_and_2000_character_error(self):
        original = builder.loader.publish_result

        def fail(output, summary, receipt, **names):
            if output == self.output and names["summary_name"] == "source-build-summary.json":
                raise OSError("x" * 2500 + " core receipt persistence failure")
            return original(output, summary, receipt, **names)

        with patch.object(builder.loader, "publish_result", side_effect=fail), \
             self.assertRaisesRegex(builder.SourceBuildError, "core receipt persistence failure"):
            self.build_sources()
        report = self.source_summary()
        self.assertEqual("failed", report["status"])
        self.assertEqual(2000, len(report["error"]))
        self.assertEqual(2, len(report["builds"]))
        self.assertTrue(report["matchesPinnedOutputIdentity"])
        self.assertFalse(report["matchesBundledBytes"])
        for field in builder.PROVENANCE_FIELDS:
            self.assertIn(field, report)
        self.assertLessEqual((self.output / "source-build-summary.json").stat().st_size, 12 * 1024)
        self.assertFalse((self.output / "source-build-receipt.json").exists())

    def test_core_final_summary_link_failure_leaves_only_pending_success_evidence(self):
        original = builder.loader.os.link
        blocked = []

        def fail_summary(source, destination, **kwargs):
            belongs_to_engine = os.fstat(kwargs["dst_dir_fd"]).st_ino == self.output.stat().st_ino
            if belongs_to_engine and destination == "source-build-summary.json" and source.endswith(".json.pending"):
                blocked.append(source)
                raise OSError("core final summary publication failed")
            return original(source, destination, **kwargs)

        with patch.object(builder.loader.os, "link", side_effect=fail_summary), \
             self.assertRaisesRegex(builder.SourceBuildError, "core final summary publication failed"):
            self.build_sources()
        self.assertEqual(["source-build-summary.json.pending"], blocked)
        report = self.source_summary()
        self.assertEqual("failed", report["status"])
        receipt = json.loads((self.output / "source-build-receipt.json").read_text())
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertEqual("verified-pending-summary-publication", receipt["summary"]["status"])
        self.assertNotEqual(receipt["authoritativeSummary"]["sha256"],
                            builder.sha((self.output / "source-build-summary.json").read_bytes()))

    def test_full_failure_summary_with_maximum_error_still_fits_budget(self):
        original = builder.loader.publish_result
        def fail(output, summary, receipt, **names):
            if output == self.output and names["receipt_name"] == "receipt.json":
                raise OSError("x" * 2500 + " receipt persistence failure")
            return original(output, summary, receipt, **names)
        with patch.object(builder.loader, "publish_result", side_effect=fail), \
             self.assertRaisesRegex(builder.SourceBuildError, "receipt persistence failure"):
            self.build()
        report = self.summary()
        self.assertEqual("failed", report["status"])
        self.assertEqual(2000, len(report["error"]))
        self.assertEqual(2, len(report["builds"]))
        self.assertLessEqual((self.output / "SUMMARY.json").stat().st_size, 12 * 1024)

    def test_loader_subproof_failure_prevents_any_engine_compilation(self):
        with patch.object(builder.loader, "build_sources", side_effect=builder.SourceBuildError("loader failed")), \
             self.assertRaisesRegex(builder.SourceBuildError, "loader failed"):
            self.build()
        self.assertEqual(0, self.make_count)
        self.assertFalse(self.summary()["loaderSourceProofSucceeded"])
        self.assertFalse(self.summary()["sourceBuildSucceeded"])

    def test_raw_nondeterminism_is_not_hidden_by_identical_poststrip(self):
        self.raw_pairs[1] = b"DIFFERENT_RAW"
        with self.assertRaisesRegex(builder.SourceBuildError, "not byte-identical"):
            self.build()
        self.assertFalse(self.summary()["rawReproducible"])
        self.assertTrue(self.summary()["postStripReproducible"])

    def test_second_poststrip_mismatch_cannot_report_any_baseline_success(self):
        self.stripped_pairs[1] = b"DIFFERENT_ENGINE"
        with self.assertRaisesRegex(builder.SourceBuildError, "not byte-identical"):
            self.build()
        self.assertTrue(self.summary()["sourceBuildSucceeded"])
        self.assertFalse(self.summary()["postStripReproducible"])
        self.assertFalse(self.summary()["matchesBundledBytes"])

    def test_repeatable_wrong_engine_fails_strict_output_pin_gate(self):
        self.stripped_pairs = [b"WRONG", b"WRONG"]
        with self.assertRaisesRegex(builder.SourceBuildError, "fixed output checksum/size"):
            self.build()
        self.assertTrue(self.summary()["rawReproducible"])
        self.assertTrue(self.summary()["postStripReproducible"])
        self.assertFalse(self.summary()["matchesBundledBytes"])

    def test_patch_wrong_result_is_rejected_before_compile(self):
        self.bad_patch = True
        with self.assertRaisesRegex(builder.SourceBuildError, "Patched event source"):
            self.build()
        self.assertEqual(0, self.make_count)

    def test_uncertified_wrapper_change_is_rejected_with_stage_fingerprints(self):
        self.changed_wrapper = True
        with self.assertRaisesRegex(builder.SourceBuildError, "wrapper transform rejected"):
            self.build()
        report = self.summary()
        self.assertFalse(report["sourceBuildSucceeded"])
        self.assertFalse(report["loaderWrapperTransformsVerified"])
        evidence = report["currentBuild"]["loaderWrapping"]["arm64"]
        self.assertEqual(builder.sha(self.loaders["arm64"]), evidence["sourceSha256"])
        self.assertEqual(builder.sha(b"changed"), evidence["wrappedSha256"])
        self.assertFalse(evidence["metadataOnlyRewriteVerified"])

    def test_certificate_cannot_claim_different_input_or_output_bytes(self):
        self.certificate_mutator = lambda certificate: certificate.update(wrappedSha256="0" * 64)
        with self.assertRaisesRegex(builder.SourceBuildError, "does not identify the provided bytes"):
            self.build()
        self.assertFalse(self.summary()["sourceBuildSucceeded"])

    def test_all_independent_certificate_properties_must_be_true(self):
        self.certificate_mutator = lambda certificate: certificate.update(allocatedSectionsUnchanged=False)
        with self.assertRaisesRegex(builder.SourceBuildError, "transformation was not certified"):
            self.build()
        self.assertFalse(self.summary()["loaderWrapperTransformsVerified"])

    def test_shared_summary_contract_must_match_raw_and_stripped_inspections(self):
        original = self.fake_inspect
        def disagree(raw, **kwargs):
            report = original(raw, **kwargs)
            if kwargs.get("require_symbols"):
                report["dynamicDependencies"] = ["unexpected.so"]
            return report
        with patch.object(builder, "inspect_engine", side_effect=disagree), \
             self.assertRaisesRegex(builder.SourceBuildError, "shared validated contract"):
            self.build()

    def test_compact_coupling_reference_must_match_exact_wrapper_identity(self):
        original = self.fake_inspect
        def disagree(raw, **kwargs):
            report = original(raw, **kwargs)
            if kwargs.get("require_symbols"):
                report["coupling"]["loaders"]["arm64"]["sha256"] = "0" * 64
            return report
        with patch.object(builder, "inspect_engine", side_effect=disagree), \
             self.assertRaisesRegex(builder.SourceBuildError, "certified wrapper identities"):
            self.build()

    def test_structural_certificate_alone_cannot_replace_recorded_wrapper_identity(self):
        self.wrapper_pairs[0]["arm64"] = b"OTHER_METADATA_LAYOUT"
        with self.assertRaisesRegex(builder.SourceBuildError, "independently recorded embedded baseline"):
            self.build()
        evidence = self.summary()["currentBuild"]["loaderWrapping"]["arm64"]
        self.assertTrue(evidence["metadataOnlyRewriteVerified"])
        self.assertFalse(evidence["matchesRecordedWrapperBytes"])
        self.assertEqual(builder.sha(b"OTHER_METADATA_LAYOUT"), evidence["wrappedSha256"])

    def test_raw_engine_must_certify_symbol_backed_coupling(self):
        self.bad_coupling = True
        with self.assertRaisesRegex(builder.SourceBuildError, "coupling"):
            self.build()

    def test_strip_cannot_silently_change_text(self):
        self.changed_text = True
        with self.assertRaisesRegex(builder.SourceBuildError, "strip changed executable"):
            self.build()

    def test_staged_offload_source_and_patch_mutation_are_detected(self):
        self.after_make = lambda cwd: (cwd / "extension/native_offload/native_offload.c").write_bytes(b"removed")
        with self.assertRaisesRegex(builder.SourceBuildError, "Staged source changed"):
            self.build()

    def test_repository_input_mutation_is_detected(self):
        self.after_make = lambda _: (self.root / builder.PATCH).write_bytes(b"mutated")
        with self.assertRaisesRegex(builder.SourceBuildError, "Pinned repository input changed"):
            self.build()

    def test_engine_tool_mutation_is_detected(self):
        self.after_make = lambda _: self.tools["clang"].write_bytes(b"mutated")
        with self.assertRaisesRegex(builder.SourceBuildError, "Tool identity changed"):
            self.build()

    def test_loader_tool_mutation_after_subproof_is_detected(self):
        self.after_make = lambda _: self.loader_tools["cc"].write_bytes(b"mutated")
        with self.assertRaisesRegex(builder.SourceBuildError, "Tool identity changed"):
            self.build()

    def test_loader_header_mutation_after_subproof_is_detected(self):
        self.after_make = lambda _: self.loader_header.write_bytes(b"mutated loader SDK header")
        with self.assertRaisesRegex(builder.SourceBuildError, "Compiler header input changed"):
            self.build()

    def test_nested_loader_proof_mutation_is_detected(self):
        self.after_make = lambda _: (self.output / "loader-source-proof/first/post-strip-libproot-loader.so").write_bytes(b"mutated")
        with self.assertRaisesRegex(builder.SourceBuildError, "Nested source-loader proof changed"):
            self.build()

    def test_previous_raw_engine_mutation_is_detected(self):
        def mutate(_):
            if self.make_count == 2:
                (self.output / "first" / builder.loader.ARCHIVE_ROOT / "src/proot").write_bytes(b"mutated")
        self.after_make = mutate
        with self.assertRaisesRegex(builder.SourceBuildError, "Proof output changed"):
            self.build()

    def test_undeclared_header_fails_closed(self):
        self.external_header = self.base / "undeclared.h"
        self.external_header.write_bytes(b"not a source or SDK input")
        with self.assertRaisesRegex(builder.SourceBuildError, "undeclared"):
            self.build()

    def test_receipt_failure_forces_failed_status_without_erasing_factual_gates(self):
        original = builder.loader.publish_result
        def fail(output, summary, receipt, **names):
            if output == self.output and names["receipt_name"] == "receipt.json":
                raise OSError("receipt failed")
            return original(output, summary, receipt, **names)
        with patch.object(builder.loader, "publish_result", side_effect=fail), self.assertRaisesRegex(builder.SourceBuildError, "receipt failed"):
            self.build()
        self.assertEqual("failed", self.summary()["status"])
        self.assertTrue(self.summary()["matchesBundledBytes"])
        self.assertTrue(self.summary()["sourceBuildSucceeded"])

    def test_summary_write_failure_does_not_leave_success_status(self):
        original = builder.loader.os.link
        calls = []
        def fail_once(source, destination, **kwargs):
            if destination == "SUMMARY.json":
                calls.append(source)
            if destination == "SUMMARY.json" and source == "SUMMARY.json.pending":
                raise OSError("summary persistence failed")
            return original(source, destination, **kwargs)
        with patch.object(builder.loader.os, "link", side_effect=fail_once), \
             self.assertRaisesRegex(builder.SourceBuildError, "summary persistence failed"):
            self.build()
        self.assertEqual(["SUMMARY.json.pending", "SUMMARY.json.failed.pending"], calls)
        self.assertEqual("failed", self.summary()["status"])
        self.assertTrue(self.summary()["matchesBundledBytes"])
        receipt = json.loads((self.output / "receipt.json").read_text())
        self.assertEqual("verified-pending-summary-publication", receipt["summary"]["status"])
        self.assertEqual("pending-summary", receipt["publicationState"])
        self.assertNotEqual(receipt["authoritativeSummary"]["sha256"],
                            builder.sha((self.output / "SUMMARY.json").read_bytes()))

    def test_input_ndk_ancestry_overlaps_are_rejected_directly(self):
        for directory in (self.base, self.root, self.ndk, self.ndk / "child"):
            with self.subTest(directory=directory), \
                 patch.object(builder.loader, "ndk_tools", return_value=(directory, self.loader_properties,
                                                                           self.loader_tools, self.loader_identities)), \
                 self.assertRaisesRegex(builder.SourceBuildError, "must not overlap"):
                self.build()
        self.assertEqual(0, self.loader_calls)

    def test_output_must_be_new_and_outside_both_ndks_and_repository(self):
        for candidate in (self.root / "output", self.ndk / "output", self.loader_ndk / "output", self.root, self.base):
            with self.subTest(candidate=candidate), self.assertRaises(builder.SourceBuildError):
                builder.build_proof(self.ndk, self.loader_ndk, candidate, self.root)
        self.assertEqual(0, self.loader_calls)

    def test_nested_tree_rejects_symlink_and_bounded_inventory(self):
        folder = self.base / "tree"
        folder.mkdir()
        (folder / "data").write_bytes(b"data")
        self.assertEqual({"data": b"data"}, builder.snapshot_tree(folder))
        with patch.object(builder, "MAX_TREE_FILES", 0), self.assertRaises(builder.SourceBuildError):
            builder.snapshot_tree(folder)
        with patch.object(builder, "MAX_TREE_BYTES", 1), self.assertRaises(builder.SourceBuildError):
            builder.snapshot_tree(folder)
        (folder / "link").symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(builder.SourceBuildError, "symlink"):
            builder.snapshot_tree(folder)


if __name__ == "__main__":
    unittest.main()
