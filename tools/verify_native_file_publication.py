#!/usr/bin/env python3
"""Exercise the production JNI publication primitive on a Linux host."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shlex
import shutil
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/cpp/droide_files.c"
NATIVE_CLASS = """package com.baystudio.droide.core;
public final class NativeFilePublication {
    static { System.load(System.getProperty("droide.files.library")); }
    public static native int renameNoReplace(byte[] source, byte[] target);
    public static native int moveNoReplace(byte[] source, byte[] target);
}
"""

HARNESS_CLASS = r"""package com.baystudio.droide.core;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;
import java.util.zip.*;

public final class NativeFilePublicationHarness {
    private static Path root;
    private static int completed;
    private static final byte[] ORIGINAL = new byte[] {0, 1, 2, -1, 80, 75};
    private static final byte[] EXISTING = new byte[] {99, 88, 77};

    private static byte[] path(Path value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static int publish(Path source, Path target) {
        return NativeFilePublication.renameNoReplace(path(source), path(target));
    }
    private static int move(Path source, Path target) {
        return NativeFilePublication.moveNoReplace(path(source), path(target));
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void same(Path value, byte[] expected) throws IOException {
        check(Arrays.equals(Files.readAllBytes(value), expected), "Bytes changed: " + value);
    }
    private static void passed(String name, int iterations) {
        completed++;
        System.out.println("{\"name\":\"" + name + "\",\"status\":\"passed\",\"iterations\":" + iterations + "}");
    }
    private static Path fixture(String name) throws IOException {
        return Files.createDirectory(root.resolve(name));
    }
    private static void exactZipAndUnicode() throws Exception {
        Path dir = fixture("zip-unicode");
        byte[] body = new byte[2 * 1024 * 1024];
        new Random(84932).nextBytes(body);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("src/halo-世界.txt"));
            zip.write(body);
            zip.closeEntry();
        }
        byte[] original = out.toByteArray();
        Path source = dir.resolve(".temporary-é-世界-😀");
        Path target = dir.resolve("Droide Website é 世界 😀 (1).zip");
        Files.write(source, original);
        check(publish(source, target) == 0, "ZIP publication failed");
        same(target, original);
        check(!Files.exists(source), "Published temporary file remained");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(target))) {
            check(zip.getNextEntry() != null, "Published ZIP became invalid");
            check(Arrays.equals(zip.readAllBytes(), body), "ZIP entry bytes changed");
        }
        passed("exact_zip_bytes_and_unicode_paths", 1);
    }
    private static void existingTarget() throws Exception {
        Path dir = fixture("target-exists");
        Path source = Files.write(dir.resolve("source"), ORIGINAL);
        Path target = Files.write(dir.resolve("target"), EXISTING);
        check(publish(source, target) == 17, "Expected EEXIST");
        same(source, ORIGINAL);
        same(target, EXISTING);
        check(publish(source, source) == 17, "Same-path call must not succeed");
        same(source, ORIGINAL);
        passed("existing_target_and_source_preserved", 2);
    }
    private static void targetSymlinks() throws Exception {
        Path dir = fixture("target-symlink");
        Path source = Files.write(dir.resolve("source"), ORIGINAL);
        Path referenced = Files.write(dir.resolve("referenced"), EXISTING);
        Path target = Files.createSymbolicLink(dir.resolve("target"), referenced.getFileName());
        check(publish(source, target) == 17, "Target symlink must count as existing");
        check(Files.isSymbolicLink(target), "Target symlink replaced");
        same(source, ORIGINAL);
        same(referenced, EXISTING);
        Path dangling = Files.createSymbolicLink(dir.resolve("dangling"), Path.of("absent"));
        check(publish(source, dangling) == 17, "Dangling target symlink must count as existing");
        check(Files.isSymbolicLink(dangling), "Dangling target symlink replaced");
        same(source, ORIGINAL);
        passed("target_symlinks_preserved", 2);
    }
    private static void invalidSourceTypes() throws Exception {
        Path dir = fixture("source-types");
        Path actual = Files.write(dir.resolve("actual"), ORIGINAL);
        Path link = Files.createSymbolicLink(dir.resolve("source-link"), actual.getFileName());
        Path target = dir.resolve("target");
        check(publish(link, target) == 22, "Source symlink must return EINVAL");
        check(publish(dir, target) == 22, "Source directory must return EINVAL");
        check(!Files.exists(target), "Invalid source published");
        check(Files.isSymbolicLink(link), "Source symlink removed");
        same(actual, ORIGINAL);
        passed("non_regular_source_rejected", 2);
    }
    private static void invalidPaths() throws Exception {
        Path dir = fixture("invalid-paths");
        Path source = Files.write(dir.resolve("source"), ORIGINAL);
        Path target = dir.resolve("target");
        check(NativeFilePublication.renameNoReplace(null, path(target)) == 22, "Null source");
        check(NativeFilePublication.renameNoReplace(path(source), null) == 22, "Null target");
        check(NativeFilePublication.renameNoReplace(new byte[0], path(target)) == 22, "Empty source");
        check(NativeFilePublication.renameNoReplace(path(source), new byte[0]) == 22, "Empty target");
        byte[] nul = new byte[] {47, 116, 109, 112, 0, 120};
        check(NativeFilePublication.renameNoReplace(nul, path(target)) == 22, "Embedded NUL source");
        check(NativeFilePublication.renameNoReplace(path(source), nul) == 22, "Embedded NUL target");
        byte[] huge = new byte[4096];
        Arrays.fill(huge, (byte) 'a');
        check(NativeFilePublication.renameNoReplace(huge, path(target)) == 36, "Oversize source");
        check(NativeFilePublication.renameNoReplace(path(source), huge) == 36, "Oversize target");
        same(source, ORIGINAL);
        check(!Files.exists(target), "Invalid path publication changed destination");
        passed("invalid_and_oversize_paths_rejected", 8);
    }
    private static void missingPaths() throws Exception {
        Path dir = fixture("missing-paths");
        Path source = Files.write(dir.resolve("source"), ORIGINAL);
        check(publish(source, dir.resolve("missing/target")) == 2, "Missing parent must return ENOENT");
        check(publish(dir.resolve("missing-source"), dir.resolve("target")) == 2, "Missing source must return ENOENT");
        same(source, ORIGINAL);
        check(!Files.exists(dir.resolve("target")), "Missing source created destination");
        passed("missing_paths_preserve_source", 2);
    }
    private static void concurrentCreation() throws Exception {
        Path dir = fixture("concurrent");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        int iterations = 400;
        try {
            for (int i = 0; i < iterations; i++) {
                Path source = Files.write(dir.resolve("source-" + i), ORIGINAL);
                Path target = dir.resolve("target-" + i);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> creator = executor.submit(() -> {
                    barrier.await();
                    try {
                        Files.write(target, EXISTING, StandardOpenOption.CREATE_NEW);
                        return true;
                    } catch (FileAlreadyExistsException expected) {
                        return false;
                    }
                });
                barrier.await();
                int result = publish(source, target);
                boolean created = creator.get(10, TimeUnit.SECONDS);
                if (created) {
                    check(result == 17, "Creator won but native publication did not return EEXIST");
                    same(target, EXISTING);
                    same(source, ORIGINAL);
                } else {
                    check(result == 0, "Native publication did not win");
                    same(target, ORIGINAL);
                    check(!Files.exists(source), "Native winner retained source");
                }
            }
        } finally {
            executor.shutdownNow();
            check(executor.awaitTermination(10, TimeUnit.SECONDS), "Creator thread remained active");
        }
        passed("concurrent_target_creation_never_overwrites", iterations);
    }
    private static void directoryMoves() throws Exception {
        Path dir = fixture("directory-moves");
        Path source = Files.createDirectory(dir.resolve("source-日本語-🚀"));
        Files.write(source.resolve("original"), ORIGINAL);
        Path target = dir.resolve("target");
        check(move(source, target) == 0, "Directory move failed");
        check(!Files.exists(source), "Moved directory still exists");
        same(target.resolve("original"), ORIGINAL);
        Path replacement = Files.createDirectory(source);
        Files.write(replacement.resolve("other"), EXISTING);
        check(move(source, target) == 17, "Directory collision did not return EEXIST");
        same(source.resolve("other"), EXISTING);
        same(target.resolve("original"), ORIGINAL);
        Path link = Files.createSymbolicLink(dir.resolve("link"), target.getFileName());
        check(move(link, dir.resolve("link-target")) == 22, "Source symlink moved");
        check(Files.isSymbolicLink(link), "Source symlink changed");
        Path dangling = Files.createSymbolicLink(dir.resolve("dangling"), Path.of("missing"));
        check(move(source, dangling) == 17, "Dangling destination overwritten");
        same(source.resolve("other"), EXISTING);
        passed("directory_moves_collisions_and_symlinks", 4);
    }
    private static void concurrentDirectoryCreation() throws Exception {
        Path dir = fixture("concurrent-directories");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        int iterations = 200;
        try {
            for (int i = 0; i < iterations; i++) {
                Path source = Files.createDirectory(dir.resolve("source-" + i));
                Files.write(source.resolve("original"), ORIGINAL);
                Path target = dir.resolve("target-" + i);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Boolean> writer = executor.submit(() -> {
                    barrier.await();
                    try {
                        Files.createDirectory(target);
                        Files.write(target.resolve("terminal"), EXISTING);
                        return true;
                    } catch (FileAlreadyExistsException expected) { return false; }
                });
                barrier.await();
                int result = move(source, target);
                boolean terminalWon = writer.get(10, TimeUnit.SECONDS);
                if (terminalWon) {
                    check(result == 17, "Concurrent directory was replaced");
                    same(source.resolve("original"), ORIGINAL);
                    same(target.resolve("terminal"), EXISTING);
                } else {
                    check(result == 0, "Atomic directory move did not win");
                    check(!Files.exists(source), "Successful move retained source");
                    same(target.resolve("original"), ORIGINAL);
                }
            }
        } finally { executor.shutdownNow(); }
        passed("concurrent_directory_creation_never_overwrites", iterations);
    }
    private static void injectedFailure(int expected) throws Exception {
        Path dir = fixture("fault");
        Path source = Files.write(dir.resolve("source"), ORIGINAL);
        Path target = dir.resolve("target");
        check(publish(source, target) == expected, "Fault errno was not returned");
        same(source, ORIGINAL);
        check(!Files.exists(target), "Fault created destination");
        Files.write(target, EXISTING);
        check(publish(source, target) == expected, "Fault with existing target changed errno");
        same(source, ORIGINAL);
        same(target, EXISTING);
        check(move(source, target) == expected, "Move fault errno was not returned");
        same(source, ORIGINAL);
        same(target, EXISTING);
        passed("fault_errno_" + expected + "_preserves_files", 3);
    }
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]);
        Files.createDirectories(root);
        if (args.length > 1) {
            injectedFailure(Integer.parseInt(args[1]));
        } else {
            exactZipAndUnicode();
            existingTarget();
            targetSymlinks();
            invalidSourceTypes();
            invalidPaths();
            missingPaths();
            concurrentCreation();
            directoryMoves();
            concurrentDirectoryCreation();
        }
        check(completed > 0, "No tests executed");
    }
}
"""


def run_command(command: list[str], log: Path) -> str:
    process = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, check=False, timeout=120)
    log.write_text("$ " + shlex.join(command) + "\n" + process.stdout, encoding="utf-8")
    if process.returncode != 0:
        raise RuntimeError(f"Command failed ({process.returncode}); see {log}:\n{process.stdout[-6000:]}")
    return process.stdout


def locate_jdk(explicit: str | None) -> Path:
    configured = explicit or os.environ.get("JAVA_HOME")
    if configured:
        return Path(configured).resolve()
    javac = shutil.which("javac")
    if not javac:
        raise RuntimeError("Set JAVA_HOME or --java-home to a JDK")
    return Path(javac).resolve().parents[1]


def verify(work: Path, logs: Path, java_home: Path, cc: str) -> list[dict[str, object]]:
    if platform.system() != "Linux":
        raise RuntimeError("This harness requires Linux renameat2 semantics")
    package = work / "java/com/baystudio/droide/core"
    package.mkdir(parents=True)
    native_java = package / "NativeFilePublication.java"
    native_java.write_text(NATIVE_CLASS, encoding="utf-8")
    harness_java = package / "NativeFilePublicationHarness.java"
    harness_java.write_text(HARNESS_CLASS, encoding="utf-8")
    classes = work / "classes"
    classes.mkdir()
    run_command([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(classes),
                 str(native_java), str(harness_java)], logs / "javac.log")
    variants = [
        ("production", [], None),
        ("unsupported", ["-DDROIDE_FILES_TEST_ERRNO=ENOSYS"], 38),
        ("denied", ["-DDROIDE_FILES_TEST_ERRNO=EACCES"], 13),
        ("interrupted", ["-DDROIDE_FILES_TEST_ERRNO=EINTR"], 4),
        ("unsupported_flag", ["-DDROIDE_FILES_TEST_ERRNO=EINVAL"], 22),
        ("unsupported_filesystem", ["-DDROIDE_FILES_TEST_ERRNO=EOPNOTSUPP"], 95),
        ("unavailable_syscall", ["-DDROIDE_FILES_TEST_NO_RENAMEAT2=1"], 38),
    ]
    results: list[dict[str, object]] = []
    for name, defines, expected in variants:
        library = work / f"libdroide_files_{name}.so"
        run_command([cc, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIC",
                     "-shared", "-I", str(java_home / "include"), "-I",
                     str(java_home / "include/linux"), *defines, str(SOURCE), "-o", str(library)],
                    logs / f"compile-{name}.log")
        command = [str(java_home / "bin/java"), f"-Ddroide.files.library={library}", "-cp",
                   str(classes), "com.baystudio.droide.core.NativeFilePublicationHarness",
                   str(work / f"fixtures-{name}")]
        if expected is not None:
            command.append(str(expected))
        output = run_command(command, logs / f"run-{name}.log")
        tests = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
        if not tests:
            raise RuntimeError(f"No JNI test results for {name}")
        for test in tests:
            test["variant"] = name
        results.extend(tests)
    return results


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", help="JDK used to compile and call JNI")
    parser.add_argument("--cc", default=os.environ.get("CC", "cc"), help="Host C compiler")
    parser.add_argument("--output", type=Path, help="Directory for JSON and logs (no generated binaries)")
    args = parser.parse_args()
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="droide-file-publication-") as temporary:
        work = Path(temporary)
        logs = args.output.resolve() if args.output else work / "evidence"
        logs.mkdir(parents=True, exist_ok=True)
        report: dict[str, object] = {
            "schema_version": 1,
            "scope": "Linux host JNI; Android/PRoot/physical-device execution not tested",
            "native_source_sha256": hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
            "platform": platform.platform(),
        }
        try:
            java_home = locate_jdk(args.java_home)
            report["java_home"] = str(java_home)
            tests = verify(work, logs, java_home, args.cc)
            report.update(status="passed", test_count=len(tests),
                          assertion_scenarios=sum(int(item["iterations"]) for item in tests), tests=tests)
            result = 0
        except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
            report.update(status="failed", error=str(error))
            result = 1
        report["elapsed_seconds"] = round(time.monotonic() - started, 3)
        encoded = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
        (logs / "native-file-publication.json").write_text(encoded, encoding="utf-8")
        print(encoded, end="")
        return result


if __name__ == "__main__":
    sys.exit(main())
