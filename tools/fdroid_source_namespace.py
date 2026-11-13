#!/usr/bin/env python3
"""Trusted outer namespace setup for the fixed source-proof sandbox.

Invoke with the system Python's -I -B -S options. This is not
a setuid program, sudo policy, general command runner, or replacement sandbox.
The operator must review this file and run_fdroid_source_proofs.sh before
granting them sudo. Their checkout and declared toolchain inputs are trusted
public CI inputs; concurrent hostile modification of those inputs is outside
this helper's trust model. Never use a checkout containing credentials.

Only the short mapping parent retains host privileges. Its child creates an
empty network namespace and a user namespace mapping exactly one non-root
host user/group to namespace 0. The child does not exec until the mapping
parent has dropped its own host privileges. Bubblewrap then performs the
existing read-only mounts, scratch-only writes, capability drop, target UID
65534, and nested-userns lockout. No host policy or subordinate-ID file changes.

The reviewed setup passed the fixed GitHub CI readiness probe in run
43367069641. That is evidence for that runner, not arbitrary host policies;
mock tests alone cannot establish that another runner's LSM permits this setup.
"""

from __future__ import annotations

import argparse
import ctypes
import errno
import os
from pathlib import Path
import select
import signal
import sys
import time


BWRAP = "/usr/bin/bwrap"
CLONE_NEWUSER = 0x10000000
CLONE_NEWNET = 0x40000000
PR_SET_PDEATHSIG = 1
PR_SET_KEEPCAPS = 8
PR_CAPBSET_DROP = 24
PR_SET_NO_NEW_PRIVS = 38
PR_CAP_AMBIENT = 47
PR_CAP_AMBIENT_CLEAR_ALL = 4
SETUP_SECONDS = 20.0
RUN_SECONDS = 300.0
REAP_SECONDS = 3.0
CLEAN_ENV = {"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "TZ": "UTC"}


class SetupError(RuntimeError):
    """A bounded, non-secret setup failure; never an unsandboxed fallback."""


def host_id(value: str) -> int:
    if not 1 <= len(value) <= 10 or not value.isascii() or not value.isdecimal() or str(int(value)) != value:
        raise argparse.ArgumentTypeError("host identity must be a canonical decimal integer")
    result = int(value)
    if not 0 < result < 4294967295:
        raise argparse.ArgumentTypeError("host identity must be nonzero and valid")
    return result


def _absolute(value: str) -> bool:
    return value.startswith("/") and os.path.normpath(value) == value and not any(c in value for c in "\x00\r\n")


def validate_bwrap(argv: list[str], source_root: str, git_kind: str | None) -> None:
    """Fail closed on unknown/overriding options; inspect strings, never run them."""
    if not argv or argv[0] != BWRAP:
        raise SetupError("only the fixed system bubblewrap binary is allowed")
    if len(argv) > 512 or sum(len(v.encode("utf-8")) for v in argv) > 131072:
        raise SetupError("sandbox argument budget exceeded")
    if any("\x00" in value for value in argv) or not _absolute(source_root):
        raise SetupError("invalid sandbox argument")
    required = {"--unshare-all", "--unshare-user", "--disable-userns", "--share-net", "--die-with-parent", "--new-session", "--clearenv"}
    singles = {"--uid": "65534", "--gid": "65534", "--cap-drop": "ALL", "--chdir": "/src"}
    expected_env = {
        **CLEAN_ENV, "HOME": "/scratch/home", "TMPDIR": "/scratch/tmp",
        "XDG_CACHE_HOME": "/scratch/cache", "PYTHONDONTWRITEBYTECODE": "1",
    }
    symlinks = {"/bin": "usr/bin", "/sbin": "usr/sbin", "/lib": "usr/lib", "/lib64": "usr/lib64"}
    fixed_binds = {"/usr": "/usr", "/etc/alternatives": "/etc/alternatives", "/etc/ld.so.cache": "/etc/ld.so.cache", "/src": source_root}
    tool_destinations = {"/toolchain/ndk", "/toolchain/jdk", "/toolchain/jdk.tar.gz", "/toolchain/android.jar", "/toolchain/annotation.jar"}
    seen: dict[str, int] = {}
    binds: dict[str, str] = {}
    bind_positions: dict[str, int] = {}
    links: dict[str, str] = {}
    directories: set[str] = set()
    environment: dict[str, str] = {}
    mounts: dict[str, int] = {}
    remounts: dict[str, int] = {}
    pending_size = pending_perms = None
    last_mount = 0
    i = 1
    while i < len(argv) and argv[i].startswith("--"):
        option = argv[i]
        start = i
        if (pending_size is not None or pending_perms is not None) and option not in {"--size", "--perms", "--tmpfs"}:
            raise SetupError("tmpfs modifiers must immediately precede their mount")
        if option in required:
            if option in seen:
                raise SetupError("duplicate sandbox isolation option")
            seen[option] = i
            i += 1
            continue
        arity = 2 if option in {"--ro-bind", "--symlink", "--setenv"} else 1
        allowed = set(singles) | {"--ro-bind", "--symlink", "--setenv", "--dir", "--proc", "--dev", "--size", "--perms", "--tmpfs", "--remount-ro"}
        if option not in allowed or i + arity >= len(argv):
            raise SetupError("unknown or incomplete sandbox option")
        values = argv[i + 1:i + 1 + arity]
        i += arity + 1
        if option in singles:
            if option in seen or values[0] != singles[option]:
                raise SetupError("target identity or capability contract changed")
            seen[option] = start
        elif option == "--setenv":
            name, value = values
            if name in environment or (name != "PROOF_DIAGNOSTICS" and expected_env.get(name) != value):
                raise SetupError("unexpected target environment")
            if name == "PROOF_DIAGNOSTICS" and len(value.encode("utf-8")) > 16384:
                raise SetupError("diagnostic program budget exceeded")
            if "--clearenv" not in seen:
                raise SetupError("target environment must be cleared first")
            environment[name] = value
        elif option == "--ro-bind":
            source, target = values
            if target in binds or not _absolute(source):
                raise SetupError("invalid or duplicate read-only input")
            if target in fixed_binds:
                if source != fixed_binds[target]:
                    raise SetupError("fixed read-only input changed")
            elif target == "/src/.git":
                if git_kind != "file" or source != "/dev/null":
                    raise SetupError("Git metadata mask changed")
            elif target in tool_destinations:
                if source == "/" or source == source_root or source.startswith(source_root + "/") or source_root.startswith(source + "/"):
                    raise SetupError("tool input overlaps repository or its ancestors")
            else:
                raise SetupError("undeclared read-only mount destination")
            binds[target] = source
            bind_positions[target] = start
            last_mount = start
        elif option == "--symlink":
            source, target = values
            if target in links or symlinks.get(target) != source:
                raise SetupError("system symlink contract changed")
            links[target] = source
            last_mount = start
        elif option == "--dir":
            target = values[0]
            if target not in {"/etc", "/toolchain"} or target in directories:
                raise SetupError("undeclared sandbox directory")
            directories.add(target)
            last_mount = start
        elif option in {"--proc", "--dev"}:
            target = "/proc" if option == "--proc" else "/dev"
            if values[0] != target or target in mounts:
                raise SetupError("pseudo-filesystem contract changed")
            mounts[target] = start
            last_mount = start
        elif option in {"--size", "--perms"}:
            if option == "--size":
                if pending_size is not None or values[0] not in {"4096", "536870912"}:
                    raise SetupError("invalid tmpfs size")
                pending_size = values[0]
            else:
                if pending_perms is not None or values[0] != "1777":
                    raise SetupError("invalid tmpfs permissions")
                pending_perms = values[0]
        elif option == "--tmpfs":
            target = values[0]
            expected = ("536870912", "1777") if target == "/scratch" else ("4096", None)
            if target not in {"/scratch", "/src/.git"} or target in mounts or (pending_size, pending_perms) != expected:
                raise SetupError("unbounded or undeclared writable mount")
            if target == "/src/.git" and git_kind != "directory":
                raise SetupError("unexpected Git metadata mask")
            pending_size = pending_perms = None
            mounts[target] = start
            last_mount = start
        elif option == "--remount-ro":
            target = values[0]
            if target not in {"/", "/proc", "/dev", "/src/.git"} or target in remounts:
                raise SetupError("unexpected read-only remount")
            remounts[target] = start
    if set(seen) != required | set(singles) or seen["--share-net"] < seen["--unshare-all"]:
        raise SetupError("required sandbox isolation is missing or overridden")
    if any(binds.get(k) != v for k, v in fixed_binds.items()) or links != symlinks or directories != {"/etc", "/toolchain"}:
        raise SetupError("required read-only filesystem layout is missing")
    if any(environment.get(k) != v for k, v in expected_env.items()):
        raise SetupError("fixed target environment is missing")
    if pending_size is not None or pending_perms is not None or "/scratch" not in mounts:
        raise SetupError("scratch size or permissions are incomplete")
    if remounts.get("/", -1) <= last_mount:
        raise SetupError("root must be made read-only after filesystem setup")
    for target in ("/proc", "/dev") + (("/src/.git",) if git_kind == "directory" else ()):
        if target not in mounts or remounts.get(target, -1) <= mounts[target]:
            raise SetupError("required read-only pseudo-filesystem mask is missing")
    if git_kind == "file" and binds.get("/src/.git") != "/dev/null":
        raise SetupError("Git file metadata must be masked")
    if git_kind in {"file", "directory"}:
        mask_position = mounts.get("/src/.git", -1) if git_kind == "directory" else bind_positions.get("/src/.git", -1)
        if mask_position <= bind_positions.get("/src", -1):
            raise SetupError("Git metadata must be masked after the source mount")
    if i == len(argv) or argv[i] not in {"/bin/bash", "/usr/bin/python3", "/usr/bin/true"}:
        raise SetupError("unexpected sandbox target entry point")


class _CapHeader(ctypes.Structure):
    _fields_ = [("version", ctypes.c_uint32), ("pid", ctypes.c_int)]


class _CapData(ctypes.Structure):
    _fields_ = [("effective", ctypes.c_uint32), ("permitted", ctypes.c_uint32), ("inheritable", ctypes.c_uint32)]


class Linux:
    def __init__(self) -> None:
        self.libc = ctypes.CDLL(None, use_errno=True)
        self.libc.unshare.argtypes = [ctypes.c_int]
        self.libc.unshare.restype = ctypes.c_int
        self.libc.prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
        self.libc.prctl.restype = ctypes.c_int
        self.libc.capset.argtypes = [ctypes.POINTER(_CapHeader), ctypes.POINTER(_CapData)]
        self.libc.capset.restype = ctypes.c_int

    def prctl(self, operation: int, value: int = 0) -> None:
        if self.libc.prctl(operation, value, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "namespace privilege operation failed")

    def unshare(self) -> None:
        if self.libc.unshare(CLONE_NEWUSER | CLONE_NEWNET) != 0:
            raise OSError(ctypes.get_errno(), "outer namespace creation failed")

    def clear_caps(self) -> None:
        header = _CapHeader(0x20080522, 0)
        data = (_CapData * 2)()
        if self.libc.capset(ctypes.byref(header), data) != 0:
            raise OSError(ctypes.get_errno(), "parent capability drop failed")


def _close(fd: int) -> None:
    try:
        os.close(fd)
    except OSError as exc:
        if exc.errno != errno.EBADF:
            raise


def _close_inherited_fds() -> None:
    # Enumerate descriptor numbers only, never their targets or contents. This
    # closes even a descriptor inherited above the new RLIMIT_NOFILE ceiling.
    names = os.listdir("/proc/self/fd")
    if len(names) > 4096:
        raise SetupError("inherited descriptor budget exceeded")
    for name in names:
        if name.isdecimal() and int(name) > 2:
            _close(int(name))


def _receive(fd: int, expected: bytes, deadline: float) -> None:
    remaining = deadline - time.monotonic()
    if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
        raise SetupError("namespace handshake deadline exceeded")
    if os.read(fd, 1) != expected:
        raise SetupError("namespace peer ended or invalid handshake")


def _send(fd: int, token: bytes) -> None:
    if os.write(fd, token) != 1:
        raise SetupError("short namespace handshake write")


def _write_maps(proc_fd: int, uid: int, gid: int) -> None:
    for name, content in (("uid_map", f"0 {uid} 1\n"), ("setgroups", "deny\n"), ("gid_map", f"0 {gid} 1\n")):
        fd = os.open(name, os.O_WRONLY | os.O_CLOEXEC | os.O_NOFOLLOW, dir_fd=proc_fd)
        try:
            data = content.encode("ascii")
            if os.write(fd, data) != len(data):
                raise SetupError("short namespace mapping write")
        finally:
            os.close(fd)


def _drop_parent(linux: Linux, uid: int, gid: int) -> None:
    linux.prctl(PR_SET_NO_NEW_PRIVS, 1)
    linux.prctl(PR_SET_KEEPCAPS, 0)
    linux.prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL)
    for capability in range(64):
        try:
            linux.prctl(PR_CAPBSET_DROP, capability)
        except OSError as exc:
            if exc.errno != errno.EINVAL:
                raise
    os.setgroups([])
    os.setresgid(gid, gid, gid)
    os.setresuid(uid, uid, uid)
    linux.clear_caps()
    if os.getresuid() != (uid, uid, uid) or os.getresgid() != (gid, gid, gid) or os.getgroups():
        raise SetupError("mapping parent identity drop was not complete")


def _bind_parent_death(linux: Linux, parent_pid: int) -> None:
    linux.prctl(PR_SET_PDEATHSIG, signal.SIGKILL)
    if os.getppid() != parent_pid:
        raise SetupError("namespace parent exited during setup")


def _child(linux: Linux, parent_pid: int, ready_fd: int, control_fd: int, argv: list[str]) -> None:
    deadline = time.monotonic() + SETUP_SECONDS
    _bind_parent_death(linux, parent_pid)
    linux.unshare()
    _send(ready_fd, b"U")
    _receive(control_fd, b"M", deadline)
    # Groups were cleared before fork; deny is already permanent in this userns.
    os.setresgid(0, 0, 0)
    os.setresuid(0, 0, 0)
    _bind_parent_death(linux, parent_pid)  # UID/GID transitions clear pdeathsig.
    if os.getresuid() != (0, 0, 0) or os.getresgid() != (0, 0, 0) or os.getgroups():
        raise SetupError("namespace child identity is unexpected")
    _send(ready_fd, b"C")
    _receive(control_fd, b"G", deadline)
    _close(ready_fd)
    _close(control_fd)
    # Keep only this new namespace's setup capabilities until bwrap's final
    # drop. Clearing them before exec would prevent construction of the sandbox.
    os.execve(BWRAP, argv, CLEAN_ENV.copy())
    raise SetupError("bubblewrap exec unexpectedly returned")


def _wait_child(pid: int, deadline: float) -> int:
    while True:
        found, status = os.waitpid(pid, os.WNOHANG)
        if found == pid:
            result = os.waitstatus_to_exitcode(status)
            return result if result >= 0 else 128 - result
        if time.monotonic() >= deadline:
            raise SetupError("source proof deadline exceeded")
        time.sleep(0.05)


def _terminate_child(pid: int) -> None:
    # First establish it is still our unreaped child. Never signal a reused PID.
    try:
        found, _ = os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        return
    if found == pid:
        return
    try:
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    _wait_child(pid, time.monotonic() + REAP_SECONDS)


def _parent(linux: Linux, pid: int, ready_fd: int, control_fd: int, uid: int, gid: int) -> int:
    setup_deadline = time.monotonic() + SETUP_SECONDS
    run_deadline = time.monotonic() + RUN_SECONDS
    completed = False
    try:
        _receive(ready_fd, b"U", setup_deadline)
        # The unreaped child cannot have its PID reused. O_NOFOLLOW also rejects
        # a substituted final path component; only these three proc files open.
        proc_fd = os.open(f"/proc/{pid}", os.O_PATH | os.O_DIRECTORY | os.O_CLOEXEC | os.O_NOFOLLOW)
        try:
            _write_maps(proc_fd, uid, gid)
        finally:
            os.close(proc_fd)
        _send(control_fd, b"M")
        # Child must already hold the caller's host identity so cleanup signals
        # remain possible after our own privilege drop.
        _receive(ready_fd, b"C", setup_deadline)
        _drop_parent(linux, uid, gid)
        _send(control_fd, b"G")
        _close(ready_fd)
        _close(control_fd)
        result = _wait_child(pid, run_deadline)
        completed = True
        return result
    finally:
        _close(ready_fd)
        _close(control_fd)
        if not completed:
            _terminate_child(pid)


def run(uid: int, gid: int, argv: list[str], source_root: str, git_kind: str | None) -> int:
    validate_bwrap(argv, source_root, git_kind)
    if type(uid) is not int or type(gid) is not int or not 0 < uid < 4294967295 or not 0 < gid < 4294967295:
        raise SetupError("non-root host user and group are required")
    if os.getresuid() != (0, 0, 0):
        raise SetupError("trusted namespace setup requires explicit root invocation")
    # Default SIGCHLD ensures exited children remain unreaped until our waitpid.
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    _close_inherited_fds()
    os.setgroups([])
    linux = Linux()
    ready_r, ready_w = os.pipe2(os.O_CLOEXEC)
    try:
        control_r, control_w = os.pipe2(os.O_CLOEXEC)
    except BaseException:
        _close(ready_r)
        _close(ready_w)
        raise
    parent_pid = os.getpid()
    try:
        pid = os.fork()
    except BaseException:
        for fd in (ready_r, ready_w, control_r, control_w):
            _close(fd)
        raise
    if pid == 0:
        _close(ready_r)
        _close(control_w)
        try:
            _child(linux, parent_pid, ready_w, control_r, argv)
        except BaseException as exc:
            _report(exc)
        finally:
            _close(ready_w)
            _close(control_r)
        os._exit(125)
    _close(ready_w)
    _close(control_r)
    return _parent(linux, pid, ready_r, control_w, uid, gid)


def _report(exc: BaseException) -> None:
    # No argv, environment, arbitrary paths, traceback, or credential material.
    detail = str(exc) if isinstance(exc, SetupError) else type(exc).__name__
    code = exc.errno if isinstance(exc, OSError) else 0
    print(f"SOURCE_PROOF_NAMESPACE_FAILED: {detail}; errno={code}", file=sys.stderr, flush=True)


def main(arguments: list[str] | None = None) -> int:
    if not sys.flags.isolated or not sys.flags.no_site or not sys.dont_write_bytecode:
        _report(SetupError("invoke the system Python with -I -B -S"))
        return 125
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--uid", required=True, type=host_id)
    parser.add_argument("--gid", required=True, type=host_id)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(arguments)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    source_root = Path(__file__).resolve().parent.parent
    metadata = source_root / ".git"
    git_kind = "directory" if metadata.is_dir() else "file" if metadata.exists() else None
    try:
        return run(args.uid, args.gid, command, str(source_root), git_kind)
    except BaseException as exc:
        _report(exc)
        return 125


if __name__ == "__main__":
    raise SystemExit(main())
