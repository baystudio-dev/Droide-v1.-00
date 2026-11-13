#!/usr/bin/env python3
"""Prove all four native pins with the retained-native directory unavailable.

This fixed orchestrator runs only inside the reviewed offline/read-only proof
sandbox. The native mode mounts an empty read-only directory over the four
retained APK inputs; no source-output producer can read those original bytes.
Outputs remain bounded disposable scratch data, never installed or executed.
The sole completion marker is SUMMARY.json, published after every check.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import stat

import fdroid_cli_source_build as cli
import fdroid_proot_engine_source_build as engine
import fdroid_proot_loader_source_build as loader


ROOT = Path(__file__).resolve().parents[1]
NATIVE_DIRECTORY = Path("app/src/main/jniLibs/arm64-v8a")
OUTPUTS = {
    "libdroide_cli.so": (4528, "913b313398b30115416acd20959b1c449eab0563c15c83230862b03e44e7cfee"),
    "libdroide_proot.so": (265648, "6a7babd8480adea03afba45a3cabd89a1387040a0cdc205a90f37185cb4f882b"),
    "libproot-loader.so": (18136, "44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04"),
    "libproot-loader32.so": (6244, "25f6bd90bc5a3d3088026289a0d3eaf3e502bd2b00e5cb74fadd9791132efa34"),
}
SUCCESS = "baseline-free-native-build-matches-all-four-pins"
SUMMARY_LIMIT = 12 * 1024
require = loader.require
SourceBuildError = loader.SourceBuildError


def require_empty_native_directory(root):
    """Inspect directory metadata only, never stat/open any retained ELF leaf."""
    current = root
    for part in NATIVE_DIRECTORY.parts:
        current = current / part
        info = current.lstat()
        require(stat.S_ISDIR(info.st_mode) and not current.is_symlink(),
                "Native reference directory must have only real directory ancestors")
    with os.scandir(current) as entries:
        require(next(entries, None) is None,
                "Native reference directory must be empty before source-only compilation")


def source_inputs(root):
    result = {str(name): raw for name, raw in cli.read_source_inputs(root).items()}
    _sources, _talloc, _shim, engine_inputs = engine.read_source_inputs(root)
    for name, raw in engine_inputs.items():
        require(name not in result or result[name] == raw, "Conflicting source-input identity")
        result[name] = raw
    for name in ("tools/fdroid_cli_source_build.py", "tools/fdroid_native_source_build.py"):
        result[name] = loader.read(root / name)
    return result


def require_core_limits(report, *, cli_core=False):
    expected_status = "isolated-source-build-only" if cli_core else engine.SOURCE_SUCCESS
    require(report.get("status") == expected_status and "error" not in report,
            "Source-only core did not publish a successful result")
    required = ("matchesPinnedOutputIdentity", "inputsAndToolsUnchanged")
    required += (("reproducibleAcrossTwoBuildDirectories", "outputFilesUnchanged") if cli_core else
                 ("sourceBuildSucceeded", "rawReproducible", "postStripReproducible",
                  "loaderCouplingVerified", "loaderWrapperTransformsVerified"))
    require(all(report.get(name) is True for name in required), "Incomplete source-only core gates")
    require(report.get("retainedArtifactInputs") == [] and report.get("baselineComparisonPerformed") is False,
            "A source-only core consumed or compared retained artifacts")
    require(report.get("matchesBundledBytes", False) is False and
            report.get("packagedPayloadsChanged") is False and
            report.get("publicationApproved") is False and
            report.get("androidRuntimeCompatibilityVerified") is False,
            "Unexpected source-only core promotion/runtime claim")
    if cli_core:
        require(report.get("publicationApproved") is False and report.get("arm64OutputExecuted") is False and
                report.get("fdroidSourceBuildAcceptanceVerified") is False, "Unexpected CLI execution/acceptance claim")
    else:
        require(report.get("targetOutputExecuted") is False and report.get("fdroidAcceptanceVerified") is False,
                "Unexpected engine execution/acceptance claim")


def check_core_receipt(path, report, *, compact):
    expected = (json.dumps(report, separators=(",", ":")) + "\n" if compact else
                json.dumps(report, indent=2) + "\n").encode("utf-8")
    raw = loader.read(path, cli.MAX_MANIFEST_BYTES)
    require(raw == expected, "Completed source-only receipt differs from its returned evidence")
    return raw


def output_path(directory, label, name):
    if name == "libdroide_cli.so":
        return directory / "cli" / label / name
    if name == "libdroide_proot.so":
        return directory / "proot" / label / ("post-strip-" + name)
    return directory / "proot/loader-source-proof" / label / ("post-strip-" + name)


def verify_output(path, name):
    # Inspect ancestors from the root down, before following any child path.
    # In particular, second/ must not alias first/ through a late symlink even
    # when both leaf files contain the same fixed output bytes.
    require(path.is_absolute(), "Native output path must be absolute")
    for parent in reversed(path.parents):
        parent_info = parent.lstat()
        require(stat.S_ISDIR(parent_info.st_mode) and not parent.is_symlink(),
                "Native output directory may not be symlinked")
    info = path.lstat()
    size, digest = OUTPUTS[name]
    require(stat.S_ISREG(info.st_mode) and not path.is_symlink() and not info.st_mode & 0o111 and
            info.st_nlink == 1 and info.st_size == size, "Unexpected native output shape: " + name)
    raw = loader.read(path, engine.MAX_OUTPUT)
    require(len(raw) == size and loader.sha(raw) == digest, "Native output does not match fixed identity: " + name)
    if name == "libdroide_cli.so":
        cli.validate_elf(raw)
    elif name == "libdroide_proot.so":
        engine.inspect_engine(raw)
    else:
        loader.inspect_loader(raw, "arm64" if name == "libproot-loader.so" else "arm32")
    return raw


def publish_summary(path, report):
    raw = (json.dumps(report, separators=(",", ":")) + "\n").encode("utf-8")
    require(len(raw) <= SUMMARY_LIMIT, "Native summary exceeds the fixed diagnostic budget")
    pending = path.with_name(path.name + ".pending")
    with pending.open("xb") as stream:
        require(stream.write(raw) == len(raw), "Short native summary write")
        stream.flush()
        os.fsync(stream.fileno())
    # Final completion publication is the last fallible filesystem operation.
    # The pending diagnostic link is retained, never treated as completion.
    os.link(pending, path, follow_symlinks=False)


def build_sources(ndk_path, loader_ndk_path, output_dir, root=ROOT):
    root = root.resolve(strict=True)
    for path in (root, ndk_path.resolve(strict=True), loader_ndk_path.resolve(strict=True), output_dir):
        engine.recipe_path(path)
    ndk, second_ndk = ndk_path.resolve(strict=True), loader_ndk_path.resolve(strict=True)
    for left, right in ((root, ndk), (root, second_ndk), (ndk, second_ndk)):
        require(left != right and left not in right.parents and right not in left.parents,
                "Source and both NDK inputs must not overlap")
    require_empty_native_directory(root)
    before = source_inputs(root)
    directory = loader.output_path(output_dir, root, ndk)
    loader.output_path(output_dir, root, second_ndk)
    directory.mkdir(mode=0o700)
    report = {"schemaVersion": 1, "status": "failed", "sourceBuildSucceeded": False,
              "bothBuildsMatchPinnedBytes": False, "retainedArtifactInputs": [],
              "baselineComparisonPerformed": False, "nativeReferenceDirectoryEmpty": True,
              "inputsAndOutputsUnchanged": False, "packagedPayloadsChanged": False,
              "publicationApproved": False, "targetOutputExecuted": False,
              "androidRuntimeCompatibilityVerified": False, "fdroidAcceptanceVerified": False,
              "ndkArchivesAuthenticated": False, "sourceInputSha256":
              {name: loader.sha(raw) for name, raw in before.items()}, "builds": []}
    try:
        cli_report = cli.build_sources(ndk, directory / "cli", root)
        require_core_limits(cli_report, cli_core=True)
        cli_path = directory / "cli/source-build-manifest.json"
        cli_receipt = check_core_receipt(cli_path, cli_report, compact=False)
        require_empty_native_directory(root)
        engine_report = engine.build_sources(ndk, second_ndk, directory / "proot", root)
        require_core_limits(engine_report)
        engine_path = directory / "proot/source-build-summary.json"
        engine_receipt = check_core_receipt(engine_path, engine_report, compact=True)
        snapshots = []
        first = {}
        for label in ("first", "second"):
            rows = {}
            for name in OUTPUTS:
                path = output_path(directory, label, name)
                raw = verify_output(path, name)
                if label == "first":
                    first[name] = raw
                else:
                    require(raw == first[name], "Independent native builds differ: " + name)
                snapshots.append((path, name, raw))
                rows[name] = {"bytes": len(raw), "sha256": loader.sha(raw)}
            report["builds"].append({"name": label, "outputs": rows})
        report["coreSummarySha256"] = {"cli": loader.sha(cli_receipt), "proot": loader.sha(engine_receipt)}
        report["arm64PokedataOffset"] = engine_report.get("arm64PokedataOffset")
        require(type(report["arm64PokedataOffset"]) is int and report["arm64PokedataOffset"] == 1120,
                "Source-derived PRoot/loader workaround offset differs")
        require_empty_native_directory(root)
        require(source_inputs(root) == before, "Source inputs changed across the combined build")
        require(check_core_receipt(cli_path, cli_report, compact=False) == cli_receipt and
                check_core_receipt(engine_path, engine_report, compact=True) == engine_receipt,
                "Core completion evidence changed across the combined build")
        for path, name, raw in snapshots:
            require(verify_output(path, name) == raw, "Native output changed before completion")
        report.update(status=SUCCESS, sourceBuildSucceeded=True, bothBuildsMatchPinnedBytes=True,
                      inputsAndOutputsUnchanged=True)
    except (SourceBuildError, cli.SourceBuildError, engine.EngineElfError, loader.LoaderElfError,
            OSError, ValueError) as error:
        report["status"] = "failed"
        report["error"] = str(error)[-2000:]
        publish_summary(directory / "SUMMARY.json", report)
        raise SourceBuildError(str(error)) from error
    publish_summary(directory / "SUMMARY.json", report)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--loader-ndk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = build_sources(args.ndk, args.loader_ndk, args.output_dir)
    except (SourceBuildError, cli.SourceBuildError, engine.EngineElfError, loader.LoaderElfError,
            OSError, ValueError) as error:
        print("NATIVE_SOURCE_BUILD_FAILED: " + str(error)[-2000:])
        return 1
    print(json.dumps(report, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
