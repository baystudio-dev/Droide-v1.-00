#!/usr/bin/env python3
"""Verify production Run File plans; optional original Linux compilers execute those exact plans."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
TEST = ROOT / "app/src/test/java/com/baystudio/droide/core"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", action="append", required=True)
    parser.add_argument("--typescript-compiler", type=Path)
    parser.add_argument("--node", type=Path)
    parser.add_argument("--dotnet", type=Path)
    parser.add_argument("--proof-dir", type=Path)
    args = parser.parse_args()
    if bool(args.typescript_compiler) != bool(args.node):
        parser.error("Pass both --typescript-compiler and --node")
    if (args.typescript_compiler or args.dotnet) and not args.proof_dir:
        parser.error("Compiler execution requires --proof-dir outside the source checkout")
    jars = sorted({p.resolve() for d in args.jars for p in Path(d).glob("*.jar")})
    for name in ("kotlin-compiler", "kotlin-stdlib", "coroutines-core", "serialization-json", "junit", "hamcrest"):
        if not any(name in p.name for p in jars):
            raise SystemExit(f"Missing externally provisioned JVM dependency: {name}")
    cp = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix="droide-source-runners-") as directory:
        temp = Path(directory)
        terminal = (CORE / "TerminalSession.kt").read_text()
        interface = terminal.split("interface ITerminalSession {", 1)[1].split("\n}", 1)[0]
        result = next(line for line in terminal.splitlines() if line.startswith("data class ExecResult("))
        (temp / "TerminalBoundaries.kt").write_text("package com.baystudio.droide.core\nimport kotlinx.coroutines.flow.StateFlow\n" +
            "interface ITerminalSession {" + interface + "\n}\n" + result + "\n" +
            "interface DevicePtySessionHandle : ITerminalSession { suspend fun refreshWorkspace() }\n")
        (temp / "SourcePlanHarness.kt").write_text(r'''
package com.baystudio.droide.core
import java.io.File
import kotlinx.serialization.json.*
fun main(args: Array<String>) {
    val plan = LanguageRegistry.runPlanFor(args[1]) ?: error("No source run plan")
    plan.preparation?.let { SourceRunPreparation.prepare(File(args[0]), it) }
    println(buildJsonObject {
        put("steps", buildJsonArray { plan.steps.forEach { step -> add(buildJsonArray { step.forEach { add(JsonPrimitive(it)) } }) } })
        put("display", plan.display)
    })
}
''')
        sources = [CORE / (name + ".kt") for name in ("SourceRunPreparation", "LanguageRegistry", "PathSecurity", "DroideCli", "Runner", "RunnerExecutionResult")]
        sources += [TEST / "SourceRunPreparationTest.kt", TEST / "DroideCliTest.kt", *temp.glob("*.kt")]
        classes = temp / "classes"
        subprocess.run(["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", cp, "-d", str(classes), *map(str, sources)], check=True)
        runtime = str(classes) + os.pathsep + cp
        subprocess.run(["java", "-cp", runtime, "org.junit.runner.JUnitCore", "com.baystudio.droide.core.SourceRunPreparationTest",
            "com.baystudio.droide.core.DroideCliTest"], check=True)
        if args.proof_dir:
            proofs(args, runtime)


def proofs(args, runtime):
    proof_root = args.proof_dir.resolve()
    if proof_root == ROOT or ROOT in proof_root.parents:
        raise SystemExit("Compiler proofs must be outside the source checkout")
    proof_root.mkdir(parents=True, exist_ok=True)
    cases = []
    if args.typescript_compiler:
        cases += [
            ("typescript-commonjs", "src/main.ts", {"src/main.ts": 'const value: string = "TS_COMMONJS_OK"; console.log(value);'}, "TS_COMMONJS_OK", True),
            ("typescript-esm-import", "src/main.ts", {"package.json": '{"type":"module"}',
                "src/main.ts": 'import { value } from "./dependency.ts"; console.log(value);',
                "src/dependency.ts": 'export const value: string = "TS_ESM_IMPORT_OK";'}, "TS_ESM_IMPORT_OK", True),
            ("typescript-nested-package-scope", "src/main.ts", {"package.json": '{"type":"module"}',
                "src/package.json": '{"type":"commonjs"}', "src/main.ts": 'console.log("TS_NESTED_CJS_OK");'}, "TS_NESTED_CJS_OK", True),
            ("typescript-mts", "src/main.mts", {"src/main.mts": 'console.log("TS_MTS_OK");'}, "TS_MTS_OK", True),
            ("typescript-cts", "src/main.cts", {"package.json": '{"type":"module"}', "src/main.cts": 'console.log("TS_CTS_OK");'}, "TS_CTS_OK", True),
            ("typescript-node-modules", "src/main.ts", {"package.json": '{"type":"module"}',
                "src/main.ts": 'import { value } from "fixture-dependency"; console.log(value);',
                "node_modules/fixture-dependency/package.json": '{"name":"fixture-dependency","type":"module","exports":{"types":"./index.d.ts","default":"./index.js"}}',
                "node_modules/fixture-dependency/index.js": 'export const value = "TS_DEPENDENCY_OK";',
                "node_modules/fixture-dependency/index.d.ts": 'export const value: string;'}, "TS_DEPENDENCY_OK", True),
            ("typescript-compile-failure", "main.ts", {"main.ts": 'const broken: number = "bad"; console.log("MUST_NOT_RUN");'}, "MUST_NOT_RUN", False),
        ]
    if args.dotnet:
        cases += [
            ("csharp-sdk-source", "Main.cs", {"Main.cs": 'Console.WriteLine("CSHARP_SDK_OK");',
                "Directory.Build.props": 'deliberately invalid user props', "Directory.Build.targets": 'deliberately invalid user targets',
                "user.csproj": 'preserve user project'}, "CSHARP_SDK_OK", True),
            ("csharp-special-filename", "src/a&b;$(touch sentinel)'%.cs", {"src/a&b;$(touch sentinel)'%.cs": 'Console.WriteLine("CSHARP_FILENAME_OK");'}, "CSHARP_FILENAME_OK", True),
            ("csharp-compile-failure", "Main.cs", {"Main.cs": 'this is not C#; Console.WriteLine("MUST_NOT_RUN");'}, "MUST_NOT_RUN", False),
        ]
    results = []
    for name, source, files, expected, should_succeed in cases:
        workspace = proof_root / name
        if workspace.exists():
            raise SystemExit(f"Proof workspace already exists: {workspace}; use a new proof directory")
        workspace.mkdir()
        for relative, content in files.items():
            path = workspace / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        planned = subprocess.run(["java", "-cp", runtime, "com.baystudio.droide.core.SourcePlanHarnessKt", str(workspace), source], check=True, text=True, capture_output=True)
        plan = json.loads(planned.stdout)
        executions = []
        env = dict(os.environ, DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_SKIP_FIRST_TIME_EXPERIENCE="1", DOTNET_NOLOGO="1")
        for step in plan["steps"]:
            executable = {"tsc": args.typescript_compiler, "node": args.node, "dotnet": args.dotnet}[step[0]]
            command = [str(executable.resolve()), *step[1:]]
            process = subprocess.run(command, cwd=workspace, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
            executions.append({"production_argv": step, "actual_argv": command, "exit_code": process.returncode, "output": process.stdout})
            if process.returncode != 0:
                break
        success = len(executions) == len(plan["steps"]) and all(e["exit_code"] == 0 for e in executions)
        if success != should_succeed or (should_succeed and expected not in executions[-1]["output"]):
            (workspace / "failure.json").write_text(json.dumps(executions, indent=2))
            raise SystemExit(f"Compiler proof failed: {name}; see {workspace / 'failure.json'}")
        if not should_succeed and "MUST_NOT_RUN\n" in executions[-1]["output"]:
            raise SystemExit(f"Invalid source executed: {name}")
        for relative, content in files.items():
            if (workspace / relative).read_text() != content:
                raise SystemExit(f"User file modified: {name}/{relative}")
        if (workspace / "sentinel").exists():
            raise SystemExit(f"Filename was executed as shell input: {name}")
        results.append({"case": name, "expected_success": should_succeed, "verified_success": success, "executions": executions})
        print(f"REAL_LINUX_COMPILER_PROOF_OK {name}", flush=True)
    (proof_root / "results.json").write_text(json.dumps({"scope": "Original Linux x64 compilers; generated projects from production Run File plans. Android ARM64 and Device bridge execution are separate QA gates.", "cases": results}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(f"Source runner verification failed (exit {failure.returncode})") from None
