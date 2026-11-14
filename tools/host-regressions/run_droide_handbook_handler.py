#!/usr/bin/env python3
"""Compile/run the exact handbook handler and policy against explicit external boundary doubles offline. Not Android/LLM proof."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import shutil
import time

p = argparse.ArgumentParser()
p.add_argument("--root", type=Path, required=True)
p.add_argument("--toolchain", type=Path, required=True)
p.add_argument("--output", type=Path, required=True)
a = p.parse_args()
root, output = a.root.resolve(), a.output.resolve()
output.mkdir(parents=True, exist_ok=False)
classes = output / "classes"
classes.mkdir()
lib = a.toolchain.resolve() / "gradle-8.13/lib"
java = a.toolchain.resolve() / "jdk17/bin/java"
if not java.is_file():
    java = a.toolchain.resolve() / "jdk-17.0.20.1+1/bin/java"
runtime = [lib / name for name in ("kotlin-stdlib-2.0.21.jar", "junit-4.13.2.jar", "hamcrest-core-1.3.jar", "annotations-24.0.1.jar", "kotlinx-coroutines-core-jvm-1.6.4.jar", "kotlinx-serialization-core-jvm-1.6.2.jar", "kotlinx-serialization-json-jvm-1.6.2.jar")]
sources = [root / "app/src/test/java/com/baystudio/droide/core/DroideHandbookPolicyTest.kt",
           root / "app/src/main/java/com/baystudio/droide/core/DroideHandbookPolicy.kt",
           root / "app/src/main/java/com/baystudio/droide/core/AgentDroideHelp.kt",
           *[root / "app/src/main/java/com/baystudio/droide/core" / name for name in (
               "AgentRuntimePrompt.kt", "AgentSystemPrompt.kt", "AgentRequestScope.kt", "AgentMode.kt", "AgentToolCatalog.kt",
               "AgentToolPermissionPolicy.kt", "McpToolIdentity.kt", "AgentIdeRequest.kt", "GradleTaskPath.kt")],
           *sorted((root / "tools/host-regressions/r71-handbook").glob("*.kt"))]
assets = sorted((root / "app/src/main/assets/agent-handbook").rglob("*"))
inputs = sources + [v for v in assets if v.is_file()] + [Path(__file__).resolve()]
snapshot = output / "input-snapshot"
for original in inputs:
    local = snapshot / original.relative_to(root)
    local.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(original, local)
compiled_sources = [snapshot / v.relative_to(root) for v in sources]
def digest(path):
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
cp = os.pathsep.join(map(str, runtime))
commands = [
    [str(java), "-Xmx512m", "-cp", str(lib / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
     "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp, "-d", str(classes), *map(str, compiled_sources)],
    [str(java), "-Xmx512m", f"-Ddroide.source={snapshot}", "-cp", str(classes) + os.pathsep + cp,
     "org.junit.runner.JUnitCore", "com.baystudio.droide.core.AgentDroideHelpHostTest", "com.baystudio.droide.core.DroideHandbookPolicyTest"],
]
plan = {"scope": "Exact whole AgentDroideHelp and DroideHandbookPolicy sources; explicit asset-context, BuildConfig, provider/vision, ChatMediaStore AgentTools.Ctx and service-final-step-text boundary doubles; real runtime prompt/catalog/permission/mode source. Tests execute real handler branches and bundled assets. Does not verify real Android/store/provider implementation, full app build, live model, APK, CLI or device.",
        "inputs": [digest(v) for v in inputs], "input_snapshot": [digest(snapshot / v.relative_to(root)) for v in inputs], "commands": commands, "runtime": [digest(v) for v in [java, *runtime]]}
(output / "PLAN.json").write_text(json.dumps(plan, indent=2) + "\n")
steps = []
for i, command in enumerate(commands, 1):
    start = time.monotonic()
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    log = output / f"{i}.log"
    log.write_text(result.stdout)
    print(result.stdout, flush=True)
    steps.append({"step": i, "exit_code": result.returncode, "seconds": time.monotonic() - start, "log": digest(log)})
    if result.returncode:
        break
(output / "RESULT.json").write_text(json.dumps({"steps": steps,
    "input_hashes_unchanged": all(digest(Path(v["path"])) == v for v in plan["inputs"]),
    "android_runtime": False}, indent=2) + "\n")
raise SystemExit(steps[-1]["exit_code"])
