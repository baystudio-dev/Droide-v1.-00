#!/usr/bin/env python3
"""Exercise real native PRoot under an additional kernel seccomp RET_TRAP filter.

This is a Linux x86_64 host check, not Android/ARM64 device certification.
Supply original and patched host builds made from the vendored PRoot archive.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-proot", type=Path, required=True)
    parser.add_argument("--patched-proot", type=Path, required=True)
    parser.add_argument("--talloc-library-dir", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    work = out / "workspace with spaces 日本語"
    work.mkdir(exist_ok=True)
    # The old x86 handler can reinterpret the positive getcwd byte count as
    # another syscall number. Bind to a fixed 58-byte guest path so the exact
    # ENOSYS reproduction is independent of the host output directory length.
    guest_work = "/workspace/droide regression with spaces 日本語"
    guest_work += "x" * (58 - len(guest_work.encode("utf-8")))
    assert len(guest_work.encode("utf-8")) == 58
    source = ROOT / "tools/proot-regressions"
    for name in ["seccomp-wrapper", "cwd-probe"]:
        subprocess.run(["gcc", "-O2", "-Wall", "-Wextra", str(source / (name + ".c")),
                        "-o", str(out / name)], check=True)
    environment = dict(os.environ, LD_LIBRARY_PATH=str(args.talloc_library_dir),
                       PROOT_NO_SECCOMP="1", PROOT_TMP_DIR=str(out))
    environment.pop("PROOT_ASSUME_NEW_SECCOMP", None)
    rows = []
    for kind, binary in [("original", args.original_proot.resolve()), ("patched", args.patched_proot.resolve())]:
        for mode in ["unfiltered", "outer-trap", "outer-trap-assume-order"]:
            for operation in ["getcwd", "getcwd-libc", "fchdir", "errors", "bash-startup", "directory-health"]:
                prefix = [str(binary), "-0", "--kill-on-exit", "-r", "/",
                          "-b", str(work) + ":" + guest_work, "-w", guest_work]
                if operation == "bash-startup":
                    command = ["/bin/bash", "--noprofile", "--norc", "-i", "-c", "/bin/pwd -P && cd . && /bin/pwd -P"]
                elif operation == "directory-health":
                    command = ["/bin/sh", "-c", "cd . && /bin/pwd -P >/dev/null"]
                else:
                    command = [str(out / "cwd-probe"), operation, guest_work]
                argv = prefix + command
                env = dict(environment)
                if mode != "unfiltered":
                    argv = [str(out / "seccomp-wrapper"), "-2"] + argv
                if mode == "outer-trap-assume-order":
                    env["PROOT_ASSUME_NEW_SECCOMP"] = "1"
                result = subprocess.run(argv, env=env, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, timeout=20)
                rows.append({"engine": kind, "mode": mode, "operation": operation,
                             "exitCode": result.returncode, "output": result.stdout})
    baseline = [row for row in rows if row["engine"] == "original" and row["mode"] == "outer-trap"]
    reproduction_cases = [row for row in baseline if row["operation"] in ["getcwd", "fchdir"]]
    reproduces = len(reproduction_cases) == 2 and all(
        row["exitCode"] != 0 and "Function not implemented" in row["output"]
        for row in reproduction_cases)
    controls = [row for row in rows if row["engine"] == "original" and row["mode"] == "unfiltered"]
    controls_pass = len(controls) == 6 and all(row["exitCode"] == 0 for row in controls)
    patched = [row for row in rows if row["engine"] == "patched"]
    passes = all(row["exitCode"] == 0 and "Function not implemented" not in row["output"] for row in patched)
    report = {"result": "PASS" if reproduces and controls_pass and passes else "FAIL",
              "platform": "Linux x86_64, real native PRoot and additional kernel RET_TRAP filter",
              "androidDeviceExecuted": False, "baselineReproduced": reproduces,
              "unfilteredControlsPassed": controls_pass,
              "getcwdProbe": "getcwd exercises the raw syscall; getcwd-libc also checks the libc fallback path",
              "guestWorkingDirectory": guest_work,
              "patchedCases": len(patched), "patchedCasesPassed": sum(row["exitCode"] == 0 for row in patched),
              "originalBinarySha256": sha(args.original_proot), "patchedBinarySha256": sha(args.patched_proot),
              "productionPatchSha256": sha(ROOT / "third_party/native-engines/proot/patches/0001-preserve-voided-syscall-results-under-outer-seccomp.patch"),
              "regressionSources": {path.relative_to(ROOT).as_posix(): sha(path) for path in sorted(source.glob("*.c"))},
              "cases": rows}
    (out / "seccomp-regressions.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "cases"}, indent=2))
    if not reproduces or not controls_pass or not passes:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
