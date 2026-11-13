#!/usr/bin/env python3
"""Offline proof-only PRoot rebuild with independently source-built loaders.

Run only through the enforced source-proof runner. The existing production
recipe and APK inputs are never modified, and no generated target is executed.
Installed tool identities are recorded, not presented as authenticated vendor
archives. Exact final-byte reproduction is not Android QA/F-Droid acceptance.
Direct calls require canonical ASCII recipe paths without whitespace or shell/
make metacharacters; the runner remaps external paths to fixed safe mounts.
"""
from __future__ import annotations

import argparse
import ast
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shlex
import stat
import tarfile

import fdroid_proot_loader_source_build as loader
from fdroid_proot_engine_elf import EngineElfError, inspect_engine


ROOT = Path(__file__).resolve().parents[1]
BASE = "third_party/native-engines/proot/"
TALLOC_ARCHIVE = BASE + "talloc-2.4.2-source.tar.gz"
TALLOC_SHA256 = "85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"
TALLOC_ROOT = "talloc-2.4.2"
PATCH = BASE + "patches/0001-preserve-voided-syscall-results-under-outer-seccomp.patch"
PATCH_SHA256 = "6a823fe58eee9110a69f411be3fd62ac657b299878a878cf51fd9b5df4d819f6"
STABLE_BUILDER = BASE + "build_droide_proot.py"
STABLE_BUILDER_SHA256 = "a61f1068a0b5e36a945d92136a60aa9cd73cad6c22fbcb8ee56a3bda309fe12f"
EVENT = "src/tracee/event.c"
EVENT_BEFORE_SHA256 = "5b6c52b10124c2652f92951b0fbbf98b7067dbfe3034a7079918ac329486653b"
EVENT_AFTER_SHA256 = "5b6748648b751ab084c4d4a566cd327a455ea5231fae1eaab2f46b962bd3ca9b"
ENGINE = loader.NATIVE + "/libdroide_proot.so"
ENGINE_SHA256 = "6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b"
ENGINE_BYTES = 265648
NDK_REVISION = "28.2.13676358"
HOST_TOOLS = {**loader.HOST_TOOLS, "patch": Path("/usr/bin/patch")}
MAX_OUTPUT = 4 * 1024 * 1024
MAX_TREE_BYTES = 64 * 1024 * 1024
MAX_TREE_FILES = 4096
SUCCESS = "source-built-engine-and-both-loaders-retained-bytes-reproduced"

SourceBuildError = loader.SourceBuildError
require, sha, read = loader.require, loader.sha, loader.read
write_new, save_json, save_summary = loader.write_new, loader.save_json, loader.save_summary
tool_identity, run_tool = loader.tool_identity, loader.run_tool


def recipe_path(path):
    require(path.is_absolute() and path == path.resolve() and
            re.fullmatch(r"/[A-Za-z0-9._/-]+", str(path)) is not None,
            "Recipe paths must be canonical ASCII paths without shell/make metacharacters")


def talloc_sources(raw):
    """Validate the complete archive envelope; stage only its two C inputs."""
    require(len(raw) <= loader.MAX_ARCHIVE, "Talloc archive compressed bound exceeded")
    result, seen, expanded = {}, set(), 0
    try:
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
            for index, member in enumerate(archive):
                require(index < loader.MAX_MEMBERS, "Talloc member bound exceeded")
                name = member.name.rstrip("/") if member.isdir() else member.name
                path = PurePosixPath(name)
                require(name and name == path.as_posix() and not path.is_absolute() and
                        ".." not in path.parts and "\\" not in name and
                        all(ord(c) >= 32 and ord(c) != 127 for c in name) and
                        path.parts[0] == TALLOC_ROOT, "Noncanonical talloc archive path")
                require(name not in seen, "Duplicate talloc archive member")
                seen.add(name)
                require(member.isdir() or member.isfile(), "Talloc links/special files forbidden")
                require(not member.issparse(), "Talloc sparse archive member forbidden")
                if member.isdir():
                    require(member.size == 0, "Talloc directory has data")
                    continue
                require(0 <= member.size <= loader.MAX_FILE, "Talloc file bound exceeded")
                expanded += member.size
                require(expanded <= loader.MAX_EXPANDED, "Talloc expansion bound exceeded")
                if name not in (TALLOC_ROOT + "/talloc.c", TALLOC_ROOT + "/talloc.h"):
                    continue
                stream = archive.extractfile(member)
                require(stream is not None, "Missing talloc payload")
                with stream:
                    data = stream.read(loader.MAX_FILE + 1)
                require(len(data) == member.size, "Truncated talloc payload")
                result[path.name] = data
    except (tarfile.TarError, EOFError, OSError) as error:
        raise SourceBuildError("Cannot read bounded talloc archive: " + str(error)) from error
    require(set(result) == {"talloc.c", "talloc.h"}, "Missing exact talloc source pair")
    return result


def stable_shim(raw):
    """Read a literal from the hash-pinned old recipe; never import/execute it."""
    require(sha(raw) == STABLE_BUILDER_SHA256, "Existing production builder checksum mismatch")
    tree = ast.parse(raw)
    values = [node.value for node in tree.body if isinstance(node, ast.Assign) and
              any(isinstance(target, ast.Name) and target.id == "SHIM" for target in node.targets)]
    require(len(values) == 1 and isinstance(values[0], ast.Constant) and
            isinstance(values[0].value, str), "Expected one literal production talloc shim")
    result = values[0].value.encode("utf-8")
    require(0 < len(result) <= 8192, "Unexpected talloc shim size")
    return result


def read_inputs(root):
    sources, _, inputs = loader.read_inputs(root)
    require(sha(sources.get(EVENT, b"")) == EVENT_BEFORE_SHA256, "Original event source checksum mismatch")
    for relative in (TALLOC_ARCHIVE, PATCH, STABLE_BUILDER,
                     "tools/fdroid_proot_engine_source_build.py", "tools/fdroid_proot_engine_elf.py", ENGINE):
        inputs[relative] = read(root / relative, loader.MAX_ARCHIVE)
    require(sha(inputs[TALLOC_ARCHIVE]) == TALLOC_SHA256, "Talloc source archive checksum mismatch")
    require(sha(inputs[PATCH]) == PATCH_SHA256, "Reviewed seccomp patch checksum mismatch")
    baseline = inputs[ENGINE]
    require(len(baseline) == ENGINE_BYTES and sha(baseline) == ENGINE_SHA256,
            "Retained engine baseline checksum/size mismatch")
    inspect_engine(baseline)
    return sources, talloc_sources(inputs[TALLOC_ARCHIVE]), stable_shim(inputs[STABLE_BUILDER]), baseline, inputs


def ndk_tools(path, root):
    require(platform.system() == "Linux" and platform.machine().lower() in ("amd64", "x86_64"),
            "Engine proof requires a Linux x86_64 host")
    ndk = path.resolve(strict=True)
    recipe_path(ndk)
    require(ndk != root and root not in ndk.parents and ndk not in root.parents,
            "Engine NDK must not overlap the source tree")
    properties = read(ndk / "source.properties", 65536)
    revisions = re.findall(rb"^\s*Pkg\.Revision\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    require(revisions == [NDK_REVISION.encode()], "Expected exact engine NDK " + NDK_REVISION)
    directory = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    tools = {"cc": directory / "aarch64-linux-android29-clang", "clang": directory / "clang",
             "linker": directory / "ld.lld", "strip": directory / "llvm-strip",
             "ar": directory / "llvm-ar", "ranlib": directory / "llvm-ranlib",
             "objcopy": directory / "llvm-objcopy", "objdump": directory / "llvm-objdump", **HOST_TOOLS}
    identities = {name: tool_identity(value, ndk if name not in HOST_TOOLS else None)
                  for name, value in tools.items()}
    return ndk, properties, tools, identities


def build_environment(tools, temporary):
    # No inherited compiler, make, loader, credential, or network environment.
    return {"PATH": str(tools["cc"].parent) + ":/usr/bin:/bin", "HOME": str(temporary),
            "TMPDIR": str(temporary), "XDG_CACHE_HOME": str(temporary), "LANG": "C", "LC_ALL": "C",
            "TZ": "UTC", "SOURCE_DATE_EPOCH": "0"}


def engine_flags(work):
    talloc = work / TALLOC_ROOT
    mapping = "-ffile-prefix-map=" + str(work) + "=."
    return ("-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -DARG_MAX=131072 -I" + str(talloc),
            "-O2 -Wall -Wextra -fPIE " + mapping,
            "-pie -L" + str(work) + " -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384,-z,common-page-size=16384,--build-id=sha1")


def talloc_command(tools, work):
    talloc = work / TALLOC_ROOT
    return [tools["cc"], "-c", talloc / "talloc.c", "-o", work / "talloc.o", "-I" + str(talloc),
            "-fPIC", "-O2", "-std=gnu99", "-ffile-prefix-map=" + str(work) + "=.",
            "-DHAVE_STDARG_H=1", "-DHAVE_VA_COPY=1", "-DHAVE_UNISTD_H=1", "-DHAVE_INTPTR_T=1"]


def make_command(tools, work):
    cppflags, cflags, ldflags = engine_flags(work)
    return [tools["make"], "--no-print-directory", "-j2", "V=1", "-o", "loader/loader",
            "-o", "loader/loader-m32", "-o", "loader/loader-info.c", "GIT=false",
            "CC=" + str(tools["cc"]), "STRIP=" + str(tools["strip"]),
            "OBJCOPY=" + str(tools["objcopy"]), "OBJDUMP=" + str(tools["objdump"]),
            "CPPFLAGS=" + cppflags, "CFLAGS=" + cflags, "LDFLAGS=" + ldflags,
            # Explicit intermediate goals preserve them for inspection. The
            # original Makefile/compile/link rules remain completely unchanged.
            "proot", "loader.exe", "loader-m32.exe"]


def engine_objects(makefile):
    match = re.search(rb"(?m)^OBJECTS \+= \\\n((?:[^\n]*\\\n)*[^\n]+)\n", makefile)
    require(match is not None, "Missing pinned engine object inventory")
    names = match[1].replace(b"\\\n", b" ").decode("ascii").split()
    require(1 <= len(names) <= 128 and len(names) == len(set(names)) and
            all(re.fullmatch(r"[a-zA-Z0-9_/-]+\.o", name) and ".." not in name for name in names),
            "Invalid engine object inventory")
    require("extension/native_offload/native_offload.o" in names, "Native offload object must remain enabled")
    return names + ["loader/loader-wrapped.o", "loader/loader-m32-wrapped.o", "loader/loader-info.o"]


def dependency_closure(raw, cwd, work, ndk):
    require(len(raw) <= 65536, "Engine dependency file exceeds bound")
    text = raw.decode("utf-8").replace("\\\n", " ")
    require(text.count(":") == 1, "Unexpected dependency target syntax")
    target, _, names = text.partition(":")
    items = shlex.split(names)
    require(target.strip() and 1 <= len(items) <= 512, "Empty/excessive dependency inventory")
    result = {}
    for name in items:
        path = Path(name)
        resolved = (path if path.is_absolute() else cwd / path).resolve(strict=True)
        if work in resolved.parents:
            identity = "source/" + resolved.relative_to(work).as_posix()
        elif ndk in resolved.parents:
            identity = "ndk/" + resolved.relative_to(ndk).as_posix()
        else:
            raise SourceBuildError("Compiler read undeclared source/header: " + name)
        require(identity not in result, "Duplicate compiler dependency")
        result[identity] = {"sha256": sha(read(resolved)), "path": str(resolved)}
    return result


def snapshot_tree(directory):
    """Bounded byte snapshots; no symlink traversal or executable evaluation."""
    result, total, count = {}, 0, 0
    for parent, directories, files in os.walk(directory, followlinks=False):
        for name in directories + files:
            count += 1
            require(count <= MAX_TREE_FILES, "Nested proof tree member bound exceeded")
            path = Path(parent) / name
            info = path.lstat()
            require(stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode),
                    "Nested proof contains symlink/special file")
            if stat.S_ISDIR(info.st_mode):
                continue
            data = read(path, MAX_OUTPUT)
            total += len(data)
            require(total <= MAX_TREE_BYTES, "Nested proof tree byte bound exceeded")
            result[path.relative_to(directory).as_posix()] = data
    return result


def compact_elf(report):
    # The full parsed ELF, exact symbol names and scope wording are preserved
    # independently in receipt.builds[].rawElf/postStripElf. The diagnostic
    # summary retains only proof-critical coupling values, avoiding repeated
    # fixed symbol-name dictionaries and scope strings across the two builds.
    result = {key: report[key] for key in ("class", "type", "machine", "entryPoint", "interpreter",
              "dynamicDependencies", "loadableSegmentsSha256", "textSha256")}
    coupling = report["coupling"]
    result["coupling"] = {key: coupling[key] for key in ("verified", "constantVerified", "embeddedLoadersVerified",
                          "pokedataOffset", "constantFileOffset", "relocationsDoNotOverlap") if key in coupling}
    result["coupling"]["loaders"] = {abi: {key: value[key] for key in ("sha256", "bytes", "start", "end", "fileOffset")}
                                      for abi, value in coupling["loaders"].items()}
    return result


def build_proof(ndk_path, loader_ndk_path, output_dir, root=ROOT):
    root = root.resolve()
    for path in (root, ndk_path.resolve(strict=True), loader_ndk_path.resolve(strict=True), output_dir):
        recipe_path(path)
    sources, talloc, shim, baseline, inputs = read_inputs(root)
    ndk, properties, tools, identities = ndk_tools(ndk_path, root)
    second_ndk, second_properties, second_tools, second_identities = loader.ndk_tools(loader_ndk_path, root)
    recipe_path(second_ndk)
    for left, right in ((root, ndk), (root, second_ndk), (ndk, second_ndk)):
        require(left != right and left not in right.parents and right not in left.parents,
                "Source and both NDK inputs must not overlap")
    output = loader.output_path(output_dir, root, ndk)
    loader.output_path(output_dir, root, second_ndk)
    output.mkdir(mode=0o700)
    temporary = output / "tmp"
    temporary.mkdir(mode=0o700)
    environment = build_environment(tools, temporary)
    summary = {"schemaVersion": 1, "status": "failed", "proofOnly": True,
               "loaderSourceProofSucceeded": False, "sourceBuildSucceeded": False,
               "rawReproducible": False, "postStripReproducible": False,
               "matchesBundledBytes": False, "loaderCouplingVerified": False,
               "inputsAndToolsUnchanged": False, "packagedPayloadsChanged": False,
               "targetOutputExecuted": False, "androidRuntimeCompatibilityVerified": False,
               "fdroidAcceptanceVerified": False, "ndkArchivesAuthenticated": False,
               "engineNdkRevision": NDK_REVISION, "loaderNdkRevision": loader.NDK_REVISION,
               "engineAndroidApi": 29, "loaderAndroidApi": 24,
               "sourceArchives": {loader.ARCHIVE: loader.ARCHIVE_SHA256, TALLOC_ARCHIVE: TALLOC_SHA256},
               "patchSha256": PATCH_SHA256, "patchedEventSha256": EVENT_AFTER_SHA256,
               "productionRecipeSha256": STABLE_BUILDER_SHA256, "tallocShimSha256": sha(shim),
               "baselineSha256": sha(baseline), "baselineBytes": len(baseline),
               "proofCodeSha256": {name: sha(raw) for name, raw in inputs.items() if name.startswith("tools/")},
               "engineInstalledToolSha256": {name: item["sha256"] for name, item in identities.items()},
               "engineNdkPropertiesSha256": sha(properties), "loaderNdkPropertiesSha256": sha(second_properties),
               "toolchainTrust": "Trusted separately provisioned SDK-manager installations; not vendor archive authentication.",
               "builds": []}
    snapshots, source_copies, dependency_inputs = [], [], {}
    nested_directory, nested_snapshot = output / "loader-source-proof", None
    commands, details, versions, pairs = [], [], {}, []

    def run(command, cwd=output, input_bytes=None):
        log = output / ("command-%03d.log" % (len(commands) + 1))
        data = run_tool(command, cwd, environment, log, input_bytes=input_bytes)
        commands.append({"command": [str(item) for item in command], "cwd": str(cwd),
                         "log": log.name, "logSha256": sha(data)})
        snapshots.append((log, data))
        return data

    def remember(path, bound=MAX_OUTPUT):
        data = read(path, bound)
        snapshots.append((path, data))
        return data

    def unchanged():
        for name, data in inputs.items():
            require(read(root / name, loader.MAX_ARCHIVE) == data, "Pinned repository input changed: " + name)
        for directory, metadata, configured, original, host in (
            (ndk, properties, tools, identities, HOST_TOOLS),
            (second_ndk, second_properties, second_tools, second_identities, loader.HOST_TOOLS),
        ):
            require(read(directory / "source.properties", 65536) == metadata, "NDK metadata changed")
            for name, value in configured.items():
                require(tool_identity(value, directory if name not in host else None) == original[name],
                        "Tool identity changed: " + name)
        for path, expected in source_copies:
            require(read(path) == expected, "Staged source changed: " + str(path))
        for path, digest in dependency_inputs.items():
            require(sha(read(Path(path))) == digest, "Compiler header input changed: " + path)
        for path, data in snapshots:
            require(read(path, MAX_OUTPUT) == data, "Proof output changed after inspection: " + str(path))
        if nested_snapshot is not None:
            require(snapshot_tree(nested_directory) == nested_snapshot, "Nested source-loader proof changed")

    try:
        nested = loader.build_proof(second_ndk, nested_directory, root)
        for key in ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible",
                    "matchesBundledBytes", "inputsAndToolsUnchanged", "gawkOffsetMatchesIndependentSymbols"):
            require(nested.get(key) is True, "Source-loader subproof did not establish " + key)
        require(nested.get("status") == "source-built-both-retained-loader-bytes-reproduced" and
                nested.get("targetOutputExecuted") is False and nested.get("packagedPayloadsChanged") is False,
                "Unexpected source-loader proof status/limits")
        nested_snapshot = snapshot_tree(nested_directory)
        nested_header_identities = {}
        for label in ("first", "second"):
            nested_source = nested_directory / label / "src"
            for suffix in ("", "-m32"):
                for stem in ("loader", "assembly"):
                    dep = read(nested_source / "loader" / (stem + suffix + ".d"), 65536)
                    closure = loader.dependency_closure(dep, nested_source, second_ndk)
                    for name, item in closure.items():
                        require(name not in nested_header_identities or nested_header_identities[name] == item["sha256"],
                                "Nested loader headers differ between independent builds")
                        nested_header_identities[name] = item["sha256"]
                        require(item["path"] not in dependency_inputs or dependency_inputs[item["path"]] == item["sha256"],
                                "Nested loader header changed between observations")
                        dependency_inputs[item["path"]] = item["sha256"]
        offset = loader.parse_pokedata(read(nested_directory / "first/generated-loader-info.c"))
        require(offset == nested.get("arm64PokedataOffset") and
                offset == loader.parse_pokedata(read(nested_directory / "second/generated-loader-info.c")),
                "Source-loader builds disagree on pokedata offset")
        summary["loaderSourceProofSucceeded"] = True
        summary["arm64PokedataOffset"] = offset
        summary["loaderSourceProofSummarySha256"] = sha(read(nested_directory / "SUMMARY.json"))
        summary["loaderInstalledToolSha256"] = {name: item["sha256"] for name, item in second_identities.items()}
        summary["loaderHeaderClosureCount"] = len(nested_header_identities)
        summary["loaderHeaderClosureSha256"] = sha(json.dumps(nested_header_identities, sort_keys=True,
                                                             separators=(",", ":")).encode())
        for name in ("cc", "linker", "strip", "ar", "ranlib", "objcopy", "objdump", "make", "patch"):
            versions[name] = run([tools[name], "--version"]).decode("utf-8", "replace")
        require("clang version" in versions["cc"], "Engine compiler did not identify itself as clang")
        summary["compilerGeneration"] = versions["cc"].splitlines()[0][:512]
        summary["linkerGeneration"] = versions["linker"].splitlines()[0][:256]
        objects = engine_objects(sources["src/GNUmakefile"])
        for label in ("first", "second"):
            unchanged()
            work = output / label
            work.mkdir(mode=0o700)
            proot = work / loader.ARCHIVE_ROOT
            source_dir = proot / "src"
            for relative, data in sources.items():
                write_new(proot / relative, data)
            for name, data in talloc.items():
                write_new(work / TALLOC_ROOT / name, data)
                source_copies.append((work / TALLOC_ROOT / name, data))
            run([tools["patch"], "-p1", "--fuzz=0", "--batch"], proot, input_bytes=inputs[PATCH])
            event = read(proot / EVENT)
            require(sha(event) == EVENT_AFTER_SHA256, "Patched event source checksum mismatch")
            for relative, data in sources.items():
                expected = event if relative == EVENT else data
                require(read(proot / relative) == expected, "Patch changed unrelated source: " + relative)
                source_copies.append((proot / relative, expected))
            write_new(work / TALLOC_ROOT / "replace.h", shim)
            source_copies.append((work / TALLOC_ROOT / "replace.h", shim))
            compile_talloc = talloc_command(tools, work)
            run(compile_talloc, work)
            run([tools["ar"], "rcs", work / "libtalloc.a", work / "talloc.o"], work)
            run([tools["ranlib"], work / "libtalloc.a"], work)
            talloc_object, talloc_library = remember(work / "talloc.o"), remember(work / "libtalloc.a")
            talloc_expansion = run([tools["cc"], "-###", *compile_talloc[1:]], work)
            talloc_dependencies = run([tools["cc"], "-M", "-MT", "talloc.o", compile_talloc[2],
                                      *compile_talloc[5:]], work)
            expected_loaders = {}
            for abi, (name, digest) in loader.LOADERS.items():
                raw_loader = read(nested_directory / label / "src/loader" /
                                  ("loader" if abi == "arm64" else "loader-m32"))
                loader_report = loader.inspect_loader(raw_loader, abi, require_symbols=abi == "arm64")
                if abi == "arm64":
                    require(loader_report["pokedataOffset"] == offset, "Raw source-loader offset changed")
                data = read(nested_directory / label / ("post-strip-" + name))
                require(sha(data) == digest, "Fresh source-built loader differs from proved retained identity")
                loader.inspect_loader(data, abi)
                expected_loaders[abi] = data
                destination = source_dir / ("loader/loader" if abi == "arm64" else "loader/loader-m32")
                write_new(destination, data)
                source_copies.append((destination, data))
            generated = ("#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=" + str(offset) + ";\n").encode()
            write_new(source_dir / "loader/loader-info.c", generated)
            source_copies.append((source_dir / "loader/loader-info.c", generated))
            run(make_command(tools, work), source_dir)
            wrapping = {}
            for abi, stem in (("arm64", "loader"), ("arm32", "loader-m32")):
                executable = source_dir / (stem + ".exe")
                executable.chmod(0o600)
                wrapped_input = remember(executable)
                require(wrapped_input == expected_loaders[abi], "Engine NDK strip changed source-built loader")
                wrapped_object = remember(source_dir / "loader" / (stem + "-wrapped.o"))
                wrapping[abi] = {"inputSha256": sha(wrapped_input), "inputBytes": len(wrapped_input),
                                 "wrappedObjectSha256": sha(wrapped_object)}
            binary = source_dir / "proot"
            binary.chmod(0o600)
            raw = remember(binary)
            raw_elf = inspect_engine(raw, require_symbols=True, expected_loaders=expected_loaders,
                                     expected_pokedata_offset=offset)
            require(all(raw_elf["coupling"].get(key) is True for key in
                        ("verified", "constantVerified", "embeddedLoadersVerified")),
                    "Engine/source-loader symbol-backed coupling was not proved")
            stripped_path = work / "post-strip-libdroide_proot.so"
            write_new(stripped_path, raw)
            run([tools["strip"], stripped_path], source_dir)
            stripped_path.chmod(0o600)
            stripped = remember(stripped_path)
            stripped_elf = inspect_engine(stripped)
            require(raw_elf["textSha256"] == stripped_elf["textSha256"], "Engine strip changed executable .text")
            dependencies = {"talloc": dependency_closure(talloc_dependencies, work, work, ndk)}
            for name in objects:
                if name.endswith("-wrapped.o"):
                    continue
                dep_path = source_dir / (name[:-2] + ".d")
                dependencies[name] = dependency_closure(remember(dep_path, 65536), source_dir, work, ndk)
            # Generated feature probes only compile/link; never execute targets.
            feature_results = {}
            remember(source_dir / "build.h")
            for name in ("process_vm", "seccomp_filter"):
                result = remember(source_dir / (".check_" + name + ".res"))
                require(result in (b"\n", ("#define HAVE_" + name.upper() + "\n").encode()),
                        "Unexpected compile-only feature probe result")
                feature_results[name] = bool(result.strip())
                dep_path = source_dir / (".check_" + name + ".d")
                dependencies["feature/" + name] = dependency_closure(remember(dep_path, 65536), source_dir, work, ndk)
            closure = {}
            for group in dependencies.values():
                for name, item in group.items():
                    require(name not in closure or closure[name] == item["sha256"], "Header changed between compiler units")
                    closure[name] = item["sha256"]
                    require(item["path"] not in dependency_inputs or dependency_inputs[item["path"]] == item["sha256"],
                            "Compiler input changed between dependency observations")
                    dependency_inputs[item["path"]] = item["sha256"]
            cppflags, cflags, ldflags = engine_flags(work)
            engine_expansion = run([tools["cc"], "-###", *shlex.split(cppflags), *shlex.split(cflags),
                                    "-MD", "-c", "./tracee/event.c", "-o", "tracee/event.o"], source_dir)
            link_expansion = run([tools["cc"], "-###", "-o", "proot", *objects, *shlex.split(ldflags)], source_dir)
            item = {"name": label, "rawSha256": sha(raw), "postStripSha256": sha(stripped),
                    "rawBytes": len(raw), "postStripBytes": len(stripped), "matchesBundledBytes": stripped == baseline,
                    "textUnchangedByStrip": True, "rawElf": compact_elf(raw_elf), "postStripElf": compact_elf(stripped_elf),
                    "tallocObjectSha256": sha(talloc_object), "tallocLibrarySha256": sha(talloc_library),
                    "loaderWrapping": wrapping, "headerClosureCount": len(closure),
                    "compileOnlyFeatureResults": feature_results,
                    "headerClosureSha256": sha(json.dumps(closure, sort_keys=True, separators=(",", ":")).encode()),
                    "tallocDriverExpansionSha256": sha(talloc_expansion),
                    "engineDriverExpansionSha256": sha(engine_expansion), "linkDriverExpansionSha256": sha(link_expansion)}
            summary["builds"].append(item)
            details.append({"name": label, "rawElf": raw_elf, "postStripElf": stripped_elf,
                            "compilerDependencies": dependencies, "objectInventory": objects})
            pairs.append({"raw": raw, "postStrip": stripped})
            unchanged()
        summary["sourceBuildSucceeded"] = True
        summary["loaderCouplingVerified"] = True
        summary["rawReproducible"] = pairs[0]["raw"] == pairs[1]["raw"]
        summary["postStripReproducible"] = pairs[0]["postStrip"] == pairs[1]["postStrip"]
        summary["matchesBundledBytes"] = all(item["postStrip"] == baseline for item in pairs)
        unchanged()
        summary["inputsAndToolsUnchanged"] = True
        if summary["rawReproducible"] and summary["postStripReproducible"] and summary["matchesBundledBytes"]:
            summary["status"] = SUCCESS
        receipt = {"summary": summary, "engineToolIdentities": identities, "engineToolVersions": versions,
                   "loaderToolIdentities": second_identities, "loaderProofSummary": nested, "builds": details,
                   "environment": environment, "commands": commands,
                   "sourceSha256": {name: sha(data) for name, data in sources.items()},
                   "tallocSourceSha256": {name: sha(data) for name, data in talloc.items()},
                   "trustLimits": ["The nested loader proof compiles both loaders twice from pinned source with NDK r29.",
                                   "Each engine embeds its corresponding source-built pair; retained binaries are comparison inputs only.",
                                   "The existing engine recipe, seccomp patch, talloc shim and native offload source remain unchanged.",
                                   "Installed SDK-manager tool hashes are not whole vendor ZIP authentication.",
                                   "No target output was executed, promoted into the APK, or proven accepted by F-Droid."]}
        save_json(output / "receipt.json", receipt)
        require(summary["rawReproducible"] and summary["postStripReproducible"], "Two source engine builds were not byte-identical")
        require(summary["matchesBundledBytes"], "Source-built engine does not match the retained baseline bytes")
        unchanged()
        save_summary(output / "SUMMARY.json", summary)
    except (SourceBuildError, loader.LoaderElfError, EngineElfError, OSError, ValueError) as error:
        summary["status"] = "failed"
        summary["error"] = str(error)[-2000:]
        save_summary(output / "SUMMARY.json", summary)
        raise SourceBuildError(str(error)) from error
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--loader-ndk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = build_proof(args.ndk, args.loader_ndk, args.output_dir)
    except (SourceBuildError, loader.LoaderElfError, EngineElfError, OSError, ValueError) as error:
        print("PROOT_ENGINE_SOURCE_PROOF_FAILED: " + str(error))
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
