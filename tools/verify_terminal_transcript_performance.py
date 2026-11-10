#!/usr/bin/env python3
"""Benchmark production transcript coalescing with exact preserved Termux buffer source on a JVM."""
import argparse
import hashlib
import json
import os
import statistics
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    jars = sorted(args.jars.glob("*.jar"))
    for required in ("kotlin-compiler", "kotlin-stdlib"):
        if not any(required in jar.name for jar in jars):
            raise SystemExit("Missing JVM dependency: " + required)
    args.out.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(str(jar.resolve()) for jar in jars)
    archive = ROOT / "third_party/termux/terminal-emulator-v0.118.0-sources.jar"
    with tempfile.TemporaryDirectory(prefix="droide-transcript-benchmark-") as temp:
        work = Path(temp)
        sources = []
        provenance = []
        with zipfile.ZipFile(archive) as source_jar:
            for name in ("TerminalBuffer.java", "TerminalRow.java", "WcWidth.java", "TextStyle.java"):
                member = next(member for member in source_jar.namelist() if member.endswith("/" + name))
                body = source_jar.read(member)
                target = work / name
                target.write_bytes(body)
                sources.append(target)
                provenance.append({"member": member, "sha256": hashlib.sha256(body).hexdigest()})
        classes = work / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([
            "java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", str(classes) + os.pathsep + classpath,
            "-d", str(classes),
            str(ROOT / "app/src/main/java/com/baystudio/droide/core/TerminalTranscriptPublisher.kt"),
            str(ROOT / "tools/terminal-regressions/TerminalTranscriptHostBenchmark.kt"),
        ], check=True)
        measured = subprocess.run([
            "java", "-Xms128m", "-Xmx512m", "-cp", str(classes) + os.pathsep + classpath,
            "com.termux.terminal.TerminalTranscriptHostBenchmark",
        ], check=True, capture_output=True, text=True)
        (args.out / "terminal-transcript-benchmark.csv").write_text(measured.stdout)
    rows = []
    for line in measured.stdout.splitlines():
        run, mode, captures, scan_ns, total_ns, digest = line.split(",")
        rows.append({"run": int(run), "mode": mode, "captures": int(captures),
                     "scanNanoseconds": int(scan_ns), "totalNanoseconds": int(total_ns), "finalTextSha256": digest})
    baseline = [row for row in rows if row["mode"] == "baseline"]
    coalesced = [row for row in rows if row["mode"] == "coalesced"]
    report = {
        "kind": "host-jvm-pinned-termux-transcript-microbenchmark",
        "limitation": "JVM only: 6000 initial history rows, 200 output events at a deterministic virtual 10 ms cadence. "
                      "Uses real unchanged pinned Termux TerminalBuffer extraction and production publisher. "
                      "No Android Looper, JNI PTY, PRoot, actual package downloads or measured physical device speedup.",
        "sourceArchiveSha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
        "implementationSources": [
            {"path": str(path.relative_to(ROOT)), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in (
                ROOT / "app/src/main/java/com/baystudio/droide/core/TerminalTranscriptPublisher.kt",
                ROOT / "tools/terminal-regressions/TerminalTranscriptHostBenchmark.kt",
            )
        ],
        "upstreamSources": provenance,
        "runs": rows,
        "baselineMedianScanMilliseconds": statistics.median(row["scanNanoseconds"] for row in baseline) / 1e6,
        "coalescedMedianScanMilliseconds": statistics.median(row["scanNanoseconds"] for row in coalesced) / 1e6,
        "finalTextIdenticalAcrossAllRuns": len({row["finalTextSha256"] for row in rows}) == 1,
    }
    if not report["finalTextIdenticalAcrossAllRuns"]:
        raise SystemExit("Final transcript content changed")
    (args.out / "terminal-transcript-benchmark.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
