#!/usr/bin/env python3
"""Execute current production Run File / task plans using provisioned Linux tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
INPUTS = ["LanguageRegistry", "SourceRunPreparation", "PathSecurity", "TaskManager",
          "TaskDependencyGraph", "WorkspaceJsonConfig", "ProcessRunner", "ProcessIo",
          "ProcessSecurityPolicy", "LocalLinuxUserEnvironment", "ProjectDebugTaskGate"]

HARNESS = r'''
package com.baystudio.droide.core
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
private fun argv(value: List<String>) = buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
private fun task(value: IdeTask) = buildJsonObject {
    put("label", value.label); put("argv", argv(value.argv)); put("cwd", value.cwd.path)
    put("steps", buildJsonArray { value.steps.forEach { add(argv(it)) } })
    put("dependsOn", argv(value.dependsOn)); put("environment", buildJsonObject { value.environment.forEach { (k,v) -> put(k,v) } })
}
fun main(args: Array<String>) = runBlocking {
    val workspace = File(args[1])
    when (args[0]) {
        "matrix" -> println(buildJsonArray {
            LanguageRegistry.all.forEach { language ->
                val source = if (language.id == "java") "Main.java" else "main.${language.extensions.first()}"
                val plan = LanguageRegistry.runPlanFor(source)
                add(buildJsonObject {
                    put("id", language.id); put("name", language.name); put("extensions", argv(language.extensions))
                    put("source", source); put("template", language.template); put("hasRunner", language.runner != null)
                    put("steps", buildJsonArray { plan?.steps?.forEach { add(argv(it)) } })
                })
            }
        })
        "plan" -> {
            val plan = LanguageRegistry.runPlanFor(args[2]) ?: error("No source run plan")
            plan.preparation?.let { SourceRunPreparation.prepare(workspace, it) }
            println(buildJsonObject {
                put("steps", buildJsonArray { plan.steps.forEach { add(argv(it)) } }); put("display", plan.display)
            })
        }
        "tasks" -> println(buildJsonArray { TaskManager(workspace).list().forEach { add(task(it)) } })
        "task", "debug-task" -> {
            val executions = mutableListOf<JsonObject>()
            val manager = TaskManager(workspace, taskExecutor = { current, remaining ->
                val started = System.nanoTime()
                var result = ProcessRunResult(0, "", false, 0)
                for (step in current.steps) {
                    result = ProcessRunner.run(step, current.cwd, if (remaining == 0L) 120_000L else remaining,
                        96_000, environment = current.environment)
                    executions += buildJsonObject {
                        put("task", current.label); put("production_argv", argv(step)); put("exit_code", result.exitCode)
                        put("output", result.output); put("timed_out", result.timedOut)
                    }
                    if (result.exitCode != 0 || result.timedOut) break
                }
                TaskResult(current, result.exitCode, result.output, result.timedOut, (System.nanoTime() - started) / 1_000_000L)
            })
            var lastResult: TaskResult? = null
            var launchInvoked = false
            val failure = if (args[0] == "debug-task") runCatching {
                ProjectDebugTaskGate.start(args[2],
                    execute = { manager.runNamed(it, 120_000).also { lastResult = it } },
                    launch = { launchInvoked = true })
            }.exceptionOrNull() else { lastResult = manager.runNamed(args[2], 120_000); null }
            val result = requireNotNull(lastResult)
            println(buildJsonObject {
                put("exit_code", result.exitCode); put("output", result.output); put("timed_out", result.timedOut)
                put("executions", JsonArray(executions))
                put("debug_launch_callback_invoked", launchInvoked)
                failure?.let { put("debug_prerequisite_error", it.message) }
            })
        }
        else -> error("Unknown probe command")
    }
}
'''

# Only the Android-facing boundary is replaced. Every method throws; invoking one
# invalidates this probe. Task graph execution uses the production injected executor.
BOUNDARIES = r'''
package com.baystudio.droide.core
interface ITerminalSession { suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult }
interface DevicePtySessionHandle : ITerminalSession { suspend fun refreshWorkspace() }
data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean)
class TerminalManager {
    fun automatedExecutionSession(createIfMissing: Boolean): ITerminalSession = error("Android terminal boundary cannot be invoked in a host control")
    fun automatedExecutionUnavailableReason(): String = error("Android terminal boundary cannot be invoked in a host control")
    fun commandShellFor(session: ITerminalSession): String = error("Android terminal boundary cannot be invoked in a host control")
}
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, cwd=None, timeout=120, env=None):
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, start_new_session=True)
    timed_out = False
    try:
        output, _ = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        os.killpg(process.pid, signal.SIGKILL)
        output, _ = process.communicate()
    return {"actual_argv": command, "exit_code": process.returncode,
            "output": output, "timed_out": timed_out,
            "duration_ms": round((time.monotonic() - started) * 1000)}


def category(item):
    if item["hasRunner"]:
        return "EXECUTABLE_RUN_FILE"
    language = item["id"]
    if language in {"batch"}:
        return "WINDOWS_RUNTIME_REQUIRED"
    if language == "objectivec":
        return "PLATFORM_FRAMEWORK_REQUIRED"
    if language in {"html", "css", "scss", "less", "react", "vue", "svelte"}:
        return "BROWSER_OR_PROJECT_BUILD"
    if language in {"dockerfile", "makefile", "cmake", "latex", "terraform", "solidity", "prisma", "vim"}:
        return "DOMAIN_TOOL_OR_PROJECT_TASK"
    return "SCHEMA_DATA_OR_QUERY_LANGUAGE"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jars", action="append", type=Path, required=True,
                        help="Directory containing provisioned Kotlin compiler/runtime jars")
    parser.add_argument("--proof-dir", type=Path, required=True)
    parser.add_argument("--path", help="PATH with provisioned host compilers (for example SDK CMake)")
    parser.add_argument("--require-language", action="append", default=["c", "cpp"])
    args = parser.parse_args()
    proof = args.proof_dir.resolve()
    if proof == ROOT or ROOT in proof.parents:
        parser.error("Proofs, classes and fixtures must be outside the source checkout")
    if proof.exists():
        parser.error("Use a new proof directory; evidence is never overwritten")
    proof.mkdir(parents=True)
    env = dict(os.environ)
    if args.path:
        env["PATH"] = args.path
    env.update({"GOTOOLCHAIN": "local", "GOPROXY": "off", "GOSUMDB": "off",
                "DOTNET_CLI_TELEMETRY_OPTOUT": "1", "DOTNET_SKIP_FIRST_TIME_EXPERIENCE": "1"})
    jars = sorted({p.resolve() for directory in args.jars for p in directory.glob("*.jar")})
    for name in ["kotlin-compiler", "kotlin-stdlib", "coroutines-core", "serialization-core", "serialization-json"]:
        if not any(name in p.name for p in jars):
            raise SystemExit(f"Missing provisioned JVM dependency: {name}")
    classpath = os.pathsep.join(map(str, jars))
    sources = [CORE / f"{name}.kt" for name in INPUTS]
    result = {"scope": "Linux host control using current production plans; no Android device or ARM64 PRoot execution",
              "host": {"system": platform.system(), "release": platform.release(), "machine": platform.machine()},
              "source_sha256": {str(path.relative_to(ROOT)): digest(path) for path in sources},
              "boundary_sha256": hashlib.sha256(BOUNDARIES.encode()).hexdigest(),
              "jvm_jars_sha256": {path.name: digest(path) for path in jars}, "cases": [], "probe_diagnostics": []}
    with tempfile.TemporaryDirectory(prefix="droide-language-probe-", dir=proof) as directory:
        temp = Path(directory)
        harness, boundary, classes = temp / "LanguageExecutionHarness.kt", temp / "HostBoundary.kt", temp / "classes"
        harness.write_text(HARNESS); boundary.write_text(BOUNDARIES)
        compile_result = run(["java", "-Xmx384m", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", classpath, "-d", str(classes),
            *map(str, sources), str(harness), str(boundary)], timeout=120, env=env)
        (proof / "probe-compile.json").write_text(json.dumps(compile_result, indent=2))
        if compile_result["exit_code"] != 0:
            raise SystemExit("Production probe compilation failed; see probe-compile.json")
        runtime = str(classes) + os.pathsep + classpath

        def probe(mode, workspace, extra=None):
            command = ["java", "-Xmx256m", "-cp", runtime,
                       "com.baystudio.droide.core.LanguageExecutionHarnessKt", mode, str(workspace)]
            if extra is not None:
                command.append(extra)
            execution = run(command, timeout=180, env=env)
            if execution["exit_code"] != 0:
                raise RuntimeError(execution["output"])
            lines = execution["output"].splitlines()
            if len(lines) > 1:
                result["probe_diagnostics"].append({"mode": mode, "output": "\n".join(lines[:-1])})
            return json.loads(lines[-1])

        matrix = probe("matrix", proof)
        for item in matrix:
            item["classification"] = category(item)
            executable = item["steps"][0][0] if item["steps"] else None
            item["host_executable"] = shutil.which(executable, path=env["PATH"]) if executable else None
            item["host_execution_status"] = "NOT_APPLICABLE" if not executable else "NOT_INSTALLED"
            item["host_toolchain_valid"] = item["host_executable"] is not None
            if item["id"] == "go" and item["host_executable"]:
                identity = run([item["host_executable"], "version"], env=env)
                item["host_toolchain_identity"] = identity
                item["host_toolchain_valid"] = identity["exit_code"] == 0 and identity["output"].startswith("go version go")
                if not item["host_toolchain_valid"]:
                    item["host_execution_status"] = "PATH_NAME_COLLISION_NOT_A_GO_TOOLCHAIN"
            if item["id"] == "assembly" and platform.machine() not in {"aarch64", "arm64"}:
                item["host_execution_status"] = "AARCH64_TEMPLATE_REQUIRES_ARM64_TOOLCHAIN"
        result["matrix"] = matrix

        def source_case(name, relative, content, expected, succeed=True):
            workspace = proof / name
            source = workspace / relative
            source.parent.mkdir(parents=True)
            source.write_text(content)
            original = digest(source)
            plan = probe("plan", workspace, relative)
            executions = []
            for step in plan["steps"]:
                execution = run(step, workspace, env=env)
                execution["production_argv"] = step
                executions.append(execution)
                if execution["exit_code"] != 0:
                    break
            success = len(executions) == len(plan["steps"]) and all(e["exit_code"] == 0 for e in executions)
            preserved = original == digest(source)
            sentinel_absent = not (workspace / "sentinel").exists()
            passed = success == succeed and preserved and sentinel_absent
            if succeed:
                passed = passed and expected in executions[-1]["output"]
            else:
                passed = passed and len(executions) == 1 and "error" in executions[0]["output"].lower()
            record = {"case": name, "passed": passed, "expected_success": succeed, "source_unchanged": preserved,
                      "sentinel_absent": sentinel_absent, "plan": plan, "executions": executions}
            if succeed and plan["steps"][0][0] in {"gcc", "g++"}:
                binary = workspace / plan["steps"][-1][0]
                header = binary.read_bytes()[:20]
                record["output_elf_machine"] = int.from_bytes(header[18:20], "little")
                readelf = shutil.which("readelf", path=env["PATH"])
                if readelf:
                    symbols = run([readelf, "--sections", str(binary)], env=env)
                    record["debug_sections"] = symbols
                    record["passed"] = record["passed"] and symbols["exit_code"] == 0 and ".debug_info" in symbols["output"]
            result["cases"].append(record)
            return record["passed"]

        for item in matrix:
            if not item["host_toolchain_valid"] or item["id"] == "assembly":
                continue
            # Only trivial registry templates, no package installation/network access.
            if item["id"] not in {"c", "cpp", "python", "javascript", "java", "go", "shell", "perl", "ruby", "php", "lua", "r", "swift", "dart", "rust", "kotlin", "csharp", "typescript", "haskell", "elixir", "erlang", "clojure", "scala", "zig", "nim", "powershell"}:
                continue
            passed = source_case("run-" + item["id"], item["source"], item["template"], "Hello")
            item["host_execution_status"] = "PASS" if passed else "FAIL"
        for language, extension, content in [
            ("c", "c", '#include <stdio.h>\nint main(void) { puts("C_LITERAL_OK"); return 0; }\n'),
            ("cpp", "cpp", '#include <iostream>\nint main() { std::cout << "CPP_LITERAL_OK\\n"; }\n')]:
            source_case(language + "-literal-path", f"src/a $(touch sentinel); quote' space.{extension}", content,
                        "C_LITERAL_OK" if language == "c" else "CPP_LITERAL_OK")
            source_case(language + "-diagnostic-stop", "broken." + extension,
                        'int main(void) { this is invalid; }\n', "", succeed=False)

        for build_system in ["make", "cmake"]:
            workspace = proof / (build_system + "-multi-file")
            workspace.mkdir()
            files = {"main.cpp": '#include <iostream>\n#include "answer.h"\nextern "C" int c_answer(void);\nint main(){ std::cout << answer() + c_answer() << "\\n"; }\n',
                     "answer.cpp": '#include "answer.h"\nint answer(){ return 40; }\n',
                     "answer.c": 'int c_answer(void){ return 2; }\n', "answer.h": "int answer();\n"}
            if build_system == "make":
                files["Makefile"] = "all: program\nprogram: main.cpp answer.cpp answer.c answer.h\n\tgcc -g -O0 -c answer.c -o answer.o\n\tg++ -g -O0 main.cpp answer.cpp answer.o -o program\n"
            else:
                files["CMakeLists.txt"] = 'cmake_minimum_required(VERSION 3.16)\nproject(DroideProof LANGUAGES C CXX)\nadd_executable(program main.cpp answer.cpp answer.c)\nenable_testing()\nadd_test(NAME answer COMMAND program)\nset_tests_properties(answer PROPERTIES PASS_REGULAR_EXPRESSION "42")\n'
            for relative, content in files.items():
                (workspace / relative).write_text(content)
            originals = {relative: digest(workspace / relative) for relative in files}
            detected = probe("tasks", workspace)
            task_label = "Make" if build_system == "make" else "CMake: test"
            task_result = probe("task", workspace, task_label)
            binary = "./program" if build_system == "make" else "./.droide/build/cmake/program"
            execution = run([binary], workspace, env=env)
            passed = task_result["exit_code"] == 0 and execution["exit_code"] == 0 and execution["output"].strip() == "42"
            passed = passed and all(digest(workspace / relative) == sha for relative, sha in originals.items())
            if build_system == "cmake":
                passed = passed and [step["task"] for step in task_result["executions"]] == ["CMake: configure", "CMake: build", "CMake: test"]
                passed = passed and (workspace / ".droide/build/cmake/compile_commands.json").is_file()
            result["cases"].append({"case": build_system + "-multi-file", "passed": passed,
                                    "production_detected_tasks": detected, "task_result": task_result, "execution": execution})
        workspace = proof / "vscode-jsonc-shell-tasks"
        (workspace / ".vscode").mkdir(parents=True)
        literal = "$(touch sentinel); quote' space"
        config = {"version": "2.0.0", "tasks": [
            {"label": "literal shell", "type": "shell", "command": "printf '%s\\n'", "args": [literal],
             "options": {"cwd": "${workspaceFolder}", "env": {"DROIDE_PROOF": "${workspaceFolderBasename}"}}},
            {"label": "failed build", "type": "process", "command": "python3", "args": ["-c", "import sys; print('EXPECTED_BUILD_FAILURE'); sys.exit(7)"]},
            {"label": "must not run", "type": "process", "command": "python3", "args": ["-c", "open('unwanted-marker','w').write('bad')"],
             "dependsOn": ["failed build"], "dependsOrder": "sequence"},
        ]}
        config_file = workspace / ".vscode/tasks.json"
        config_file.write_text("// Production JSONC parsing\n" + json.dumps(config, indent=2)[:-1] + ",\n}\n")
        original = digest(config_file)
        detected = probe("tasks", workspace)
        literal_result = probe("debug-task", workspace, "literal shell")
        failed_result = probe("debug-task", workspace, "must not run")
        result["cases"].append({"case": "vscode-shell-literal-arguments-and-prelaunch-success",
                                "passed": literal_result["exit_code"] == 0 and literal in literal_result["output"]
                                and literal_result["debug_launch_callback_invoked"] and not (workspace / "sentinel").exists(),
                                "detected_tasks": detected, "task_result": literal_result})
        result["cases"].append({"case": "vscode-dependency-and-prelaunch-failure-stop",
                                "passed": failed_result["exit_code"] == 7 and not failed_result["debug_launch_callback_invoked"]
                                and len(failed_result["executions"]) == 1 and "failed" in failed_result["debug_prerequisite_error"]
                                and not (workspace / "unwanted-marker").exists() and digest(config_file) == original,
                                "task_result": failed_result})
        required = {item["id"]: item["host_execution_status"] for item in matrix if item["id"] in args.require_language}
        result["required_languages"] = required
        result["passed"] = all(case["passed"] for case in result["cases"]) and all(required.get(language) == "PASS" for language in args.require_language)
    result["source_still_matches"] = all(digest(ROOT / relative) == sha for relative, sha in result["source_sha256"].items())
    result["passed"] = result["passed"] and result["source_still_matches"]
    (proof / "results.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"passed": result["passed"], "cases": len(result["cases"]),
                      "matrix_languages": len(result["matrix"]), "host": result["host"], "results": str(proof / "results.json")}, indent=2))
    if not result["passed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
