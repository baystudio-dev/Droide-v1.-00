#!/usr/bin/env python3
"""Offline SPAKE2 source-rebuild proof; never changes application dependencies.

``--check-inputs`` only reads source/provenance. ``--build`` requires the already
provisioned, exact vendor JDK and archive, and a fresh output outside the repo.
It compiles the two Java sources twice with empty class/source paths, compares
both deterministic outputs, and compares their entries/API with the retained
binary *after* compilation. The retained binary is never a compiler input.

Build/optional vector execution belongs in a separately enforced no-network OS
sandbox with read-only source/toolchain, scratch-only writes and resource limits.
This script supplies a clean subprocess environment and timeouts, not a sandbox.
It does not download, install, publish, modify pins, or certify crypto/device use.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import types
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VENDOR = "third_party/kadb/spake2-boringssl-compat"
COORDINATE = "com.baystudio.compat:spake2-boringssl-compat:1.0.0"
MAVEN = "third_party/maven/com/baystudio/compat/spake2-boringssl-compat/1.0.0"
JAR_NAME = "spake2-boringssl-compat-1.0.0.jar"
SOURCE_JAR = MAVEN + "/spake2-boringssl-compat-1.0.0-sources.jar"
BUNDLED_JAR = MAVEN + "/" + JAR_NAME
JDK_MANIFEST = "tools/terminal-engine-v2-jdk.json"
JDK_HELPER = "tools/terminal_jdk_v2.py"
JAVA_PACKAGE = "com/flyfish233/crypto/spake2/"
SOURCES = (JAVA_PACKAGE + "Spake2Context.java", JAVA_PACKAGE + "Spake2Role.java")
CLASSES = frozenset(JAVA_PACKAGE + name + ".class" for name in (
    "Spake2Context", "Spake2Context$Point", "Spake2Context$State", "Spake2Role"))
TEST_SOURCE = "com/baystudio/droide/spake2/Spake2CompatibilitySelfTest.java"
TEST_CLASS = "com.baystudio.droide.spake2.Spake2CompatibilitySelfTest"
COMPILER_OPTIONS = ("--release", "8", "-g:none", "-proc:none", "-implicit:none", "-encoding", "UTF-8")
MANIFEST = ("Manifest-Version: 1.0\n"
            "Implementation-Title: Droide SPAKE2 BoringSSL compatibility\n"
            "Implementation-Version: 1.0.0\n\n").encode()
MAX_INPUT = 256 * 1024
MAX_OUTPUT = 1024 * 1024
TOOL_TIMEOUT = 60

# These are the reviewed existing inputs, not newly generated dependency pins.
# A source/toolchain change requires explicit review of this proof's input set.
PINS = {
    VENDOR + "/src/main/java/" + SOURCES[0]: "a91b6bf9f7c94077313eb3e74bd77c4111a7e271612d7f268ee65df963fdc381",
    VENDOR + "/src/main/java/" + SOURCES[1]: "45d793f15e087e0d3df73d3b424f278cef6e94374a974913c19de4f913eee003",
    VENDOR + "/src/test/java/" + TEST_SOURCE: "d3be2159989e420b6023dd1417225151c822a9f4674a492340a6c98b418d0396",
    VENDOR + "/LICENSE-BSD-3-CLAUSE.txt": "afa999f686cabc1fa1b8c070a0749b7916c83940fde0b07a2b1de5e455ac2a07",
    VENDOR + "/NOTICE.md": "6bf0a9b69aea97c808e798da241044133697217e44b01dbcc86345565274f3bc",
    VENDOR + "/UPSTREAM_REFERENCES.md": "42e7e20b3504b7a6c22bfc29b1f7268f7694cec72a90e46f70b40ec2aaac9ae4",
    VENDOR + "/UPSTREAM_LICENSES/Jellepepe-flutter_adb-BSD-3-Clause.txt": "f8c7d0ff12c55b9de74865ef3e6afe3704d952ce11df632c80db352c794192d6",
    VENDOR + "/TEST_VECTORS.json": "2ccaa5d20cc1c1db8228283c30c0abff1bb416afa714c8d1011990254cd5848f",
    SOURCE_JAR: "167223a441d5f3ae39f29b2be387b886d5db28573158b0e195fd18b8170bd9cb",
    BUNDLED_JAR: "e274cd0b690a7ac831a064024335f585e492a945eec1c52fb4c0a6963e366463",
    MAVEN + "/spake2-boringssl-compat-1.0.0.pom": "0787ef628f623dddaeeb332dcbadb6aab5490fe69a07e61f3740b09437015974",
    JDK_MANIFEST: "8438db3e628cf0860d2f5e0db29a3216bba0b4d3d1ce6d1a6e8d9cf17f29f2e5",
    JDK_HELPER: "0583a3dfeb92088eca19554abd4928d93c14e1f6bf7a02abc529fb663f2f2ddd",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_path(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    require(not any(item.is_symlink() for item in (path, *path.parents)), "Symlinked protected path")
    return path


def read_regular(path: Path, limit: int = MAX_INPUT) -> bytes:
    path = safe_path(path)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    with os.fdopen(os.open(path, flags), "rb") as stream:
        before = os.fstat(stream.fileno())
        require(stat.S_ISREG(before.st_mode) and before.st_nlink == 1, "Expected single-link regular input: " + str(path))
        require(0 <= before.st_size <= limit, "Input exceeds byte bound: " + str(path))
        data = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
        require(len(data) == before.st_size and len(data) <= limit, "Input size changed: " + str(path))
        require((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) ==
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns),
                "Input changed while reading: " + str(path))
        return data


def zip_contents(data: bytes) -> dict[str, bytes]:
    result = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.infolist()) <= 32, "Too many ZIP members")
        total = 0
        for member in archive.infolist():
            name = member.filename
            require(name and "\\" not in name and "\0" not in name and
                    all(part not in ("", ".", "..") for part in name.split("/")), "Unsafe ZIP member")
            require(name not in result, "Duplicate ZIP member")
            mode = member.external_attr >> 16
            require(stat.S_IFMT(mode) in (0, stat.S_IFREG), "Nonregular ZIP member")
            require(not member.flag_bits & 1 and member.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                    "Unsupported ZIP encoding")
            total += member.file_size
            require(0 <= member.file_size <= MAX_INPUT and total <= MAX_OUTPUT, "ZIP exceeds byte bound")
            result[name] = archive.read(member)
    return result


def verify_classes(classes: dict[str, bytes]) -> None:
    require(set(classes) == CLASSES, "Unexpected SPAKE2 class inventory")
    for name, data in classes.items():
        require(len(data) >= 8 and data[:8] == b"\xca\xfe\xba\xbe\0\0\0\x34", "Not Java 8 bytecode: " + name)


def tree_files(root: Path) -> list[Path]:
    files = []
    for directory, dirs, names in os.walk(safe_path(root), followlinks=False):
        for name in dirs + names:
            path = Path(directory) / name
            require(not path.is_symlink(), "Symlink in source/output tree")
            mode = path.lstat().st_mode
            require(stat.S_ISDIR(mode) or stat.S_ISREG(mode), "Nonregular source/output tree entry")
            if stat.S_ISREG(mode):
                files.append(path)
    return sorted(files)


def verify_inputs(root: Path) -> dict[str, bytes]:
    root = safe_path(root)
    inputs = {name: read_regular(root / name) for name in PINS}
    for name, expected in PINS.items():
        require(sha256(inputs[name]) == expected, "Reviewed input SHA-256 mismatch: " + name)
    source_root = root / VENDOR / "src/main/java"
    source_files = {path.relative_to(source_root).as_posix() for path in tree_files(source_root)}
    require(source_files == set(SOURCES), "Unexpected SPAKE2 source inventory")
    extras = {
        "META-INF/MANIFEST.MF": MANIFEST,
        "META-INF/LICENSE": inputs[VENDOR + "/LICENSE-BSD-3-CLAUSE.txt"],
        "META-INF/NOTICE": inputs[VENDOR + "/NOTICE.md"],
    }
    source_entries = zip_contents(inputs[SOURCE_JAR])
    wanted_sources = {name: inputs[VENDOR + "/src/main/java/" + name] for name in SOURCES}
    require(source_entries == {**wanted_sources, **extras}, "Source JAR differs from retained plain sources/notices")
    bundled_entries = zip_contents(inputs[BUNDLED_JAR])
    require(set(bundled_entries) == CLASSES | set(extras), "Unexpected bundled SPAKE2 JAR inventory")
    require(all(bundled_entries[name] == data for name, data in extras.items()), "Bundled notice/manifest differs from source")
    verify_classes({name: bundled_entries[name] for name in CLASSES})
    return inputs


def fresh_output(output: Path, root: Path, jdk: Path) -> Path:
    output, root, jdk = map(safe_path, (output, root, jdk))
    for protected in (root, jdk):
        require(output != protected and protected not in output.parents and output not in protected.parents,
                "Output must be outside source and toolchain trees")
    require(output.parent.is_dir(), "Output parent must already exist")
    require(not output.exists(), "Output already exists; choose a fresh path")
    output.mkdir(mode=0o700)
    return output


def verify_toolchain(root: Path, jdk: Path, archive: Path, inputs: dict[str, bytes]) -> dict:
    # Compile only the checksum-verified helper bytes, not a potentially stale
    # .pyc cache or another module found via PYTHONPATH. This helper only checks
    # full vendor archive/installed inventories; it never installs or executes.
    vendor = types.ModuleType("droide_spake2_verified_jdk_helper")
    exec(compile(inputs[JDK_HELPER], str(root / JDK_HELPER), "exec"), vendor.__dict__)
    manifest = json.loads(inputs[JDK_MANIFEST])
    require(manifest["version"] == "17.0.20.1+1", "Unexpected reviewed compiler version")
    require(manifest["archiveSha256"] == "3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e",
            "Unexpected reviewed compiler archive")
    vendor.verify(safe_path(jdk), safe_path(archive), manifest)
    return {"version": manifest["version"], "archiveSha256": manifest["archiveSha256"],
            "manifestSha256": PINS[JDK_MANIFEST], "helperSha256": PINS[JDK_HELPER],
            "fullVendorArchiveAndInstalledIdentityVerified": True}


def process_environment(run: Path, jdk: Path) -> dict[str, str]:
    return {"PATH": os.defpath, "HOME": str(run / "home"), "TMPDIR": str(run / "tmp"),
            "XDG_CACHE_HOME": str(run / "cache"), "JAVA_HOME": str(jdk),
            "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}


def jvm_options(run: Path, prefix: str = "-J") -> list[str]:
    return [prefix + option for option in (
        "-Xms16m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-XX:-UsePerfData",
        "-Djava.io.tmpdir=" + str(run / "tmp"), "-Duser.home=" + str(run / "home"),
        "-Duser.language=en", "-Duser.country=US")]


def compiler_command(jdk: Path, run: Path, output: Path, sources: list[Path], classpath: Path) -> list[str]:
    return [str(jdk / "bin/javac"), *jvm_options(run), *COMPILER_OPTIONS,
            "-classpath", str(classpath), "-sourcepath", str(run / "empty-sourcepath"),
            "-d", str(output), *(str(source) for source in sources)]


def run_tool(command: list[str], run: Path, jdk: Path, log_name: str) -> str:
    log = run / log_name
    with log.open("xb") as stream:
        result = subprocess.run(command, cwd=run, env=process_environment(run, jdk), stdin=subprocess.DEVNULL,
                                stdout=stream, stderr=subprocess.STDOUT, timeout=TOOL_TIMEOUT, check=False,
                                close_fds=True)
    text = read_regular(log, MAX_OUTPUT).decode("utf-8", errors="replace")
    require(result.returncode == 0, "Tool failed: " + Path(command[0]).name + "\n" + text[-4000:])
    return text


def write_jar(path: Path, contents: dict[str, bytes]) -> bytes:
    # STORED and fixed metadata avoid both zlib-version and timestamp variation.
    with path.open("xb") as stream, zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in sorted(contents):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, contents[name])
    return read_regular(path, MAX_OUTPUT)


def build_one(run: Path, jdk: Path, inputs: dict[str, bytes]) -> tuple[Path, dict[str, bytes]]:
    run.mkdir(mode=0o700)
    for name in ("home", "tmp", "cache", "empty-classpath", "empty-sourcepath", "classes"):
        (run / name).mkdir()
    sources = []
    for name in SOURCES:
        source = run / "source" / name
        source.parent.mkdir(parents=True, exist_ok=True)
        with source.open("xb") as stream:
            stream.write(inputs[VENDOR + "/src/main/java/" + name])
        sources.append(source)
    run_tool(compiler_command(jdk, run, run / "classes", sources, run / "empty-classpath"), run, jdk, "javac.log")
    classes = {path.relative_to(run / "classes").as_posix(): read_regular(path)
               for path in tree_files(run / "classes")}
    verify_classes(classes)
    contents = dict(classes)
    contents.update({"META-INF/MANIFEST.MF": MANIFEST,
                     "META-INF/LICENSE": inputs[VENDOR + "/LICENSE-BSD-3-CLAUSE.txt"],
                     "META-INF/NOTICE": inputs[VENDOR + "/NOTICE.md"]})
    artifact = run / JAR_NAME
    write_jar(artifact, contents)
    return artifact, contents


def api_listing(jar: Path, run: Path, jdk: Path, log_name: str) -> str:
    names = [name[:-6].replace("/", ".") for name in sorted(CLASSES)]
    command = [str(jdk / "bin/javap"), *jvm_options(run), "-protected", "-s", "-constants",
               "-classpath", str(jar), *names]
    listing = run_tool(command, run, jdk, log_name)
    # Source-file debug labels are not ABI; retain every other line verbatim.
    return "\n".join(line for line in listing.splitlines() if not line.startswith('Compiled from "')) + "\n"


def comparison(generated: dict[str, bytes], bundled: dict[str, bytes], generated_jar: bytes,
               bundled_jar: bytes, generated_api: str, bundled_api: str) -> dict:
    names = set(generated) | set(bundled)
    different = sorted(name for name in names if generated.get(name) != bundled.get(name))
    return {"classBytesIdentical": not any(name.endswith(".class") for name in different),
            "allEntryBytesIdentical": not different, "differentEntries": different,
            "publicProtectedApiIdentical": generated_api == bundled_api,
            "archiveBytesIdentical": generated_jar == bundled_jar,
            "archivePackaging": "Canonical STORED ZIP; bundled ZIP is compared, never copied"}


def vector_test(run: Path, jdk: Path, inputs: dict[str, bytes]) -> None:
    source = run / "test-source" / TEST_SOURCE
    source.parent.mkdir(parents=True)
    with source.open("xb") as stream:
        stream.write(inputs[VENDOR + "/src/test/java/" + TEST_SOURCE])
    output = run / "test-classes"
    output.mkdir()
    run_tool(compiler_command(jdk, run, output, [source], run / "classes"), run, jdk, "vector-javac.log")
    classpath = os.pathsep.join(str(path) for path in (run / "classes", output))
    command = [str(jdk / "bin/java"), *jvm_options(run, ""), "-cp", classpath, TEST_CLASS]
    result = run_tool(command, run, jdk, "vector-test.log")
    require(result.strip() == "SPAKE2_COMPATIBILITY_VECTOR_OK", "Unexpected SPAKE2 vector result")


def build(root: Path, jdk: Path, archive: Path, output: Path, run_vector: bool = False) -> dict:
    root, jdk = safe_path(root), safe_path(jdk)
    inputs = verify_inputs(root)
    toolchain = verify_toolchain(root, jdk, archive, inputs)
    output = fresh_output(output, root, jdk)
    first, first_entries = build_one(output / "first", jdk, inputs)
    second, second_entries = build_one(output / "second", jdk, inputs)
    first_bytes, second_bytes = read_regular(first, MAX_OUTPUT), read_regular(second, MAX_OUTPUT)
    require(first_entries == second_entries and first_bytes == second_bytes, "Two clean source builds are not byte-identical")
    source_api = api_listing(first, first.parent, jdk, "source-api.log")
    bundled_api = api_listing(root / BUNDLED_JAR, first.parent, jdk, "bundled-api.log")
    parity = comparison(first_entries, zip_contents(inputs[BUNDLED_JAR]), first_bytes, inputs[BUNDLED_JAR], source_api, bundled_api)
    if run_vector:
        vector_test(first.parent, jdk, inputs)
    receipt = {
        "schema": 1, "coordinate": COORDINATE, "status": "SOURCE_BUILD_PROOF_ONLY",
        "builderSha256": sha256(read_regular(Path(__file__))),
        "inputSha256": dict(PINS), "toolchain": toolchain, "compilerOptions": list(COMPILER_OPTIONS),
        "mainCompilerInputs": [VENDOR + "/src/main/java/" + name for name in SOURCES],
        "prebuiltDependencyCompilerInputs": [], "twoCleanBuildsByteIdentical": True,
        "generatedJarSha256": sha256(first_bytes), "generatedClasses": {name: sha256(first_entries[name]) for name in sorted(CLASSES)},
        "bundledComparison": parity, "vectorTest": "PASS" if run_vector else "NOT_RUN",
        "productionDependenciesChanged": False, "fullApplicationSourceBuild": False,
        "fdroidScannerFindingsResolved": False, "physicalDeviceInteroperabilityValidated": False,
        "independentCryptographicSecurityReviewPassed": False,
    }
    if not (parity["allEntryBytesIdentical"] and parity["publicProtectedApiIdentical"]):
        receipt["status"] = "SOURCE_BUILD_REPRODUCIBLE_BUT_BUNDLED_PARITY_NOT_PROVEN"
    with (output / "REBUILD.json").open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    require(receipt["status"] == "SOURCE_BUILD_PROOF_ONLY",
            "Source build is reproducible but bundled entry/API parity differs; inspect REBUILD.json; production is unchanged")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-inputs", action="store_true", help="Read-only source/notice checks; no JDK or code execution")
    mode.add_argument("--build", action="store_true", help="Build proof in a separately enforced sandbox; no production changes")
    parser.add_argument("--jdk", type=Path)
    parser.add_argument("--jdk-archive", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--run-vector-test", action="store_true", help="Explicitly execute only the freshly compiled deterministic self-test")
    args = parser.parse_args()
    if args.check_inputs:
        if any((args.jdk, args.jdk_archive, args.output, args.run_vector_test)):
            parser.error("--check-inputs does not accept build/execution arguments")
        verify_inputs(args.root)
        print("SPAKE2_INPUTS_ONLY_OK: source/archive correspondence checked; no build, runtime or crypto certification")
    else:
        if not all((args.jdk, args.jdk_archive, args.output)):
            parser.error("--build requires --jdk, --jdk-archive and --output")
        receipt = build(args.root, args.jdk, args.jdk_archive, args.output, args.run_vector_test)
        print("SPAKE2_SOURCE_BUILD_PROOF_OK " + json.dumps(receipt, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print("SPAKE2 source-build proof failed: " + str(error), file=sys.stderr)
        sys.exit(1)
