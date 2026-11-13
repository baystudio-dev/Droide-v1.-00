#!/usr/bin/env python3
"""Fixed preflight inside the final proof sandbox; never executes a target.

Only bounded kernel observations, known safe environment values, fixed path
metadata, and one exclusive scratch marker are used. No sockets, subprocesses,
host configuration, credential values, or source writes. Success covers these
observed controls, not arbitrary sandbox security or application correctness.
"""
from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import json
import os
import re
import stat
import sys


MAX_SCRATCH_BYTES = 512 * 1024 * 1024
CAP_FIELDS = ("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb")
REQUIRED_ENV = {
    "PATH": "/usr/bin:/bin", "HOME": "/scratch/home", "TMPDIR": "/scratch/tmp",
    "XDG_CACHE_HOME": "/scratch/cache", "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8",
    "TZ": "UTC", "PYTHONDONTWRITEBYTECODE": "1",
}
SHELL_ENV = {"PWD": "/src", "SHLVL": "1", "_": "/usr/bin/python3"}
ALLOWED_ENV_NAMES = frozenset(REQUIRED_ENV) | frozenset(SHELL_ENV) | {"PROOF_DIAGNOSTICS"}
SAFE_DEVICES = {
    "/dev/null": (1, 3), "/dev/zero": (1, 5), "/dev/full": (1, 7),
    "/dev/random": (1, 8), "/dev/urandom": (1, 9), "/dev/tty": (5, 0),
    "/dev/ptmx": (5, 2),
}
SCRATCH_PATHS = ("/scratch", "/scratch/home", "/scratch/tmp", "/scratch/cache")
MARKER_NAME = ".fdroid-source-sandbox-probe"
MARKER_BYTES = b"source-proof-sandbox\n"
IPV4_HEADER = "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT".split()


class ProbeFailure(ValueError):
    """A fixed non-secret error code; observed host data is never interpolated."""


def require(ok, code):
    if not ok:
        raise ProbeFailure(code)


def check_identity(text):
    require(len(text) <= 65536, "STATUS_TOO_LARGE")
    wanted = {"Uid", "Gid", "NoNewPrivs", *CAP_FIELDS}
    fields = {}
    for line in text.splitlines():
        key, separator, value = line.partition(":")
        if separator and key in wanted:
            require(key not in fields, "DUPLICATE_STATUS_FIELD")
            fields[key] = value.strip()
    require(set(fields) == wanted, "MISSING_IDENTITY_CONTROL")
    for key in ("Uid", "Gid"):
        require(fields[key].split() == ["65534"] * 4, "SANDBOX_IDENTITY_MISMATCH")
    for key in CAP_FIELDS:
        require(re.fullmatch(r"[0-9A-Fa-f]{1,16}", fields[key]) is not None,
                "INVALID_CAPABILITY_FIELD")
        require(int(fields[key], 16) == 0, "CAPABILITY_PRESENT")
    require(fields["NoNewPrivs"] == "1", "NO_NEW_PRIVILEGES_NOT_SET")


def mount_path(value):
    decoded = re.sub(r"\\(040|011|012|134)", lambda m: chr(int(m[1], 8)), value)
    require("\\" not in decoded and "\0" not in decoded and decoded.startswith("/"),
            "INVALID_MOUNT_PATH")
    require(decoded == "/" or
            all(piece not in ("", ".", "..") for piece in decoded[1:].split("/")),
            "NONCANONICAL_MOUNT_PATH")
    return decoded


@dataclass(frozen=True)
class Mount:
    point: str
    root: str
    filesystem: str
    options: frozenset[str]
    super_options: tuple[str, ...]


def parse_mountinfo(text):
    require(len(text) <= 262144, "MOUNTINFO_TOO_LARGE")
    result, identities = {}, set()
    lines = text.splitlines()
    require(0 < len(lines) <= 256, "INVALID_MOUNT_COUNT")
    for line in lines:
        fields = line.split()
        require(fields.count("-") == 1, "MALFORMED_MOUNTINFO")
        separator = fields.index("-")
        require(separator >= 6 and len(fields) == separator + 4, "MALFORMED_MOUNTINFO")
        require(fields[0].isdigit() and fields[1].isdigit() and
                re.fullmatch(r"[0-9]+:[0-9]+", fields[2]) is not None, "INVALID_MOUNT_ID")
        require(fields[0] not in identities, "DUPLICATE_MOUNT_ID")
        identities.add(fields[0])
        root, point = mount_path(fields[3]), mount_path(fields[4])
        require(point not in result, "STACKED_MOUNTPOINT")
        options = fields[5].split(",")
        require(len(options) == len(set(options)) and
                len(set(options) & {"ro", "rw"}) == 1, "INVALID_MOUNT_FLAGS")
        result[point] = Mount(point, root, fields[separator + 1], frozenset(options),
                              tuple(fields[separator + 3].split(",")))
    return result


def required_readonly_mounts(mounts):
    for point in ("/", "/src", "/usr", "/proc", "/dev"):
        require(point in mounts and "ro" in mounts[point].options, "REQUIRED_MOUNT_NOT_READONLY")
    require(mounts["/proc"].filesystem == "proc", "PROC_MOUNT_MISMATCH")
    require(mounts["/dev"].filesystem == "tmpfs", "DEV_MOUNT_MISMATCH")


def tmpfs_size(options):
    values = [item[5:] for item in options if item.startswith("size=")]
    require(len(values) == 1, "SCRATCH_SIZE_NOT_EXPLICIT")
    match = re.fullmatch(r"([0-9]{1,12})([kKmMgG]?)", values[0])
    require(match is not None, "INVALID_SCRATCH_SIZE")
    size = int(match[1]) * {"": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[match[2].lower()]
    require(0 < size <= MAX_SCRATCH_BYTES, "SCRATCH_SIZE_EXCEEDS_LIMIT")
    return size


def check_mounts(mounts, scratch_bytes, devices, git):
    required_readonly_mounts(mounts)
    require("/scratch" in mounts, "SCRATCH_MOUNT_MISSING")
    scratch = mounts["/scratch"]
    require(scratch.filesystem == "tmpfs" and scratch.root == "/" and "rw" in scratch.options,
            "SCRATCH_NOT_PRIVATE_TMPFS")
    size = tmpfs_size(scratch.super_options)
    require(type(scratch_bytes) is int and 0 < scratch_bytes <= MAX_SCRATCH_BYTES and
            scratch_bytes <= size, "SCRATCH_FILESYSTEM_SIZE_MISMATCH")
    for point, mount in mounts.items():
        if "rw" not in mount.options or point == "/scratch":
            continue
        if point == "/dev/pts":
            require(mount.filesystem == "devpts" and mount.root == "/", "UNSAFE_DEVPTS_MOUNT")
        elif point in SAFE_DEVICES:
            require(devices.get(point) == SAFE_DEVICES[point], "UNSAFE_CHARACTER_DEVICE")
        else:
            raise ProbeFailure("UNEXPECTED_WRITABLE_MOUNT")
    mask = mounts.get("/src/.git")
    if git == {"kind": "absent"}:
        require(mask is None, "GIT_MASK_STATE_MISMATCH")
    elif git == {"kind": "empty-directory"}:
        require(mask is not None and mask.filesystem == "tmpfs" and mask.root == "/" and
                "ro" in mask.options, "GIT_DIRECTORY_NOT_MASKED")
    elif git == {"kind": "null-device"}:
        require(mask is not None and "ro" in mask.options, "GIT_FILE_NOT_MASKED")
    else:
        raise ProbeFailure("GIT_NOT_MASKED")
    return size


def check_environment(names, values):
    require(set(names) <= ALLOWED_ENV_NAMES, "UNEXPECTED_ENVIRONMENT_VARIABLE")
    require(set(REQUIRED_ENV) <= set(names), "REQUIRED_ENVIRONMENT_MISSING")
    require(set(values) <= set(REQUIRED_ENV) | set(SHELL_ENV), "UNEXPECTED_ENVIRONMENT_VALUE")
    for name, expected in {**REQUIRED_ENV, **SHELL_ENV}.items():
        if name in REQUIRED_ENV or name in names:
            require(values.get(name) == expected, "UNSAFE_ENVIRONMENT_VALUE")


def check_paths(paths):
    require(set(paths) == set(SCRATCH_PATHS), "SCRATCH_PATH_INVENTORY_MISMATCH")
    for path in SCRATCH_PATHS:
        value = paths[path]
        require(value.get("directory") is True, "SCRATCH_PATH_NOT_DIRECTORY")
        if path != "/scratch":
            require(value.get("uid") == 65534 and value.get("mode") == 0o700,
                    "SCRATCH_PRIVATE_DIRECTORY_MISMATCH")


def check_network(net_dev, ipv4_routes, ipv6_routes):
    require(max(map(len, (net_dev, ipv4_routes, ipv6_routes))) <= 32768, "NETWORK_TABLE_TOO_LARGE")
    rows = net_dev.splitlines()
    require(len(rows) >= 2 and "Inter-" in rows[0] and "Receive" in rows[0] and
            "face" in rows[1] and "bytes" in rows[1], "MALFORMED_INTERFACE_TABLE")
    interfaces = []
    for row in rows[2:]:
        if not row.strip():
            continue
        name, separator, counters = row.partition(":")
        require(separator and len(counters.split()) == 16 and
                all(value.isdigit() for value in counters.split()), "MALFORMED_INTERFACE_ROW")
        interfaces.append(name.strip())
    require(interfaces == ["lo"], "EXTERNAL_NETWORK_INTERFACE")
    rows = ipv4_routes.splitlines()
    # Some isolated kernels return a readable zero-byte table rather than a
    # header-only table when no IPv4 routes exist. Failed reads never become
    # empty strings: observe()/main() fail closed on the original read error.
    require(ipv4_routes == "" or (rows and rows[0].split() == IPV4_HEADER), "MALFORMED_IPV4_TABLE")
    for row in rows[1:]:
        if not row.strip():
            continue
        fields = row.split()
        require(len(fields) == 11 and fields[0] == "lo", "EXTERNAL_IPV4_ROUTE")
        require(all(re.fullmatch(r"[0-9A-Fa-f]{1,8}", fields[n]) for n in (1, 2, 3, 7)),
                "MALFORMED_IPV4_ROUTE")
        destination, gateway, flags, mask = (int(fields[n], 16) for n in (1, 2, 3, 7))
        require(gateway == 0 and not flags & 2, "EXTERNAL_IPV4_GATEWAY")
        if destination == mask == 0 and flags & 0x200:
            continue  # Kernel reject route is not a forwarding default route.
        try:
            network = ipaddress.IPv4Network(
                (str(ipaddress.IPv4Address(destination.to_bytes(4, "little"))),
                 str(ipaddress.IPv4Address(mask.to_bytes(4, "little")))), strict=False)
        except ValueError as error:
            raise ProbeFailure("MALFORMED_IPV4_ROUTE") from error
        require(network.prefixlen >= 8 and network.network_address.is_loopback, "EXTERNAL_IPV4_ROUTE")
    for row in ipv6_routes.splitlines():
        fields = row.split()
        require(len(fields) == 10 and fields[-1] == "lo", "EXTERNAL_IPV6_ROUTE")
        require(all(re.fullmatch(r"[0-9A-Fa-f]{32}", fields[n]) for n in (0, 2, 4)) and
                all(re.fullmatch(r"[0-9A-Fa-f]{1,8}", fields[n]) for n in (1, 3, 5, 6, 7, 8)),
                "MALFORMED_IPV6_ROUTE")
        destination, prefix, source, source_prefix, gateway = (int(fields[n], 16) for n in (0, 1, 2, 3, 4))
        flags = int(fields[8], 16)
        require(source == source_prefix == gateway == 0, "EXTERNAL_IPV6_ROUTE")
        loopback = destination == 1 and prefix == 128
        reject_default = destination == prefix == 0 and flags & 0x200
        require(loopback or reject_default, "EXTERNAL_IPV6_ROUTE")


def validate(snapshot):
    """Pure validation API for mocked fixtures; returns no raw observed data."""
    check_identity(snapshot["status"])
    mounts = parse_mountinfo(snapshot["mountinfo"])
    size = check_mounts(mounts, snapshot["scratch_bytes"], snapshot["devices"], snapshot["git"])
    check_environment(snapshot["env_names"], snapshot["env_values"])
    check_paths(snapshot["paths"])
    check_network(snapshot["net_dev"], snapshot["ipv4_routes"], snapshot["ipv6_routes"])
    return {"status": "SOURCE_PROOF_SANDBOX_READY", "uid": 65534, "gid": 65534,
            "capabilitiesEmpty": True, "noNewPrivileges": True, "requiredMountsReadOnly": True,
            "scratchLimitBytes": size, "network": "loopback-only-no-external-route",
            "environmentAllowlisted": True, "gitMaskedOrAbsent": True}


def read_kernel(path, bound):
    with open(path, "rb") as stream:
        raw = stream.read(bound + 1)
    require(len(raw) <= bound, "KERNEL_OBSERVATION_TOO_LARGE")
    return raw.decode("utf-8", errors="strict")


def observe():
    status_text = read_kernel("/proc/self/status", 65536)
    check_identity(status_text)  # Refuse a normal host invocation before other observations.
    mountinfo = read_kernel("/proc/self/mountinfo", 262144)
    mounts = parse_mountinfo(mountinfo)
    required_readonly_mounts(mounts)
    names = frozenset(os.environ)  # Names only; never copy an ambient environment.
    require(names <= ALLOWED_ENV_NAMES, "UNEXPECTED_ENVIRONMENT_VARIABLE")
    values = {name: os.environ[name] for name in REQUIRED_ENV.keys() | SHELL_ENV.keys() if name in names}
    check_environment(names, values)  # PROOF_DIAGNOSTICS contents are not read.
    paths = {}
    for path in SCRATCH_PATHS:
        info = os.lstat(path)
        paths[path] = {"directory": stat.S_ISDIR(info.st_mode), "uid": info.st_uid,
                       "mode": stat.S_IMODE(info.st_mode)}
    check_paths(paths)
    devices = {}
    for point in SAFE_DEVICES:
        if point in mounts and "rw" in mounts[point].options:
            info = os.lstat(point)
            if stat.S_ISCHR(info.st_mode):
                devices[point] = (os.major(info.st_rdev), os.minor(info.st_rdev))
    try:
        info = os.lstat("/src/.git")
    except FileNotFoundError:
        git = {"kind": "absent"}
    else:
        mask = mounts.get("/src/.git")
        if stat.S_ISDIR(info.st_mode):
            require(mask is not None and mask.filesystem == "tmpfs" and mask.root == "/" and
                    "ro" in mask.options, "GIT_DIRECTORY_NOT_MASKED")
            with os.scandir("/src/.git") as entries:
                require(next(entries, None) is None, "GIT_MASK_NOT_EMPTY")
            git = {"kind": "empty-directory"}
        elif stat.S_ISCHR(info.st_mode) and (os.major(info.st_rdev), os.minor(info.st_rdev)) == (1, 3):
            git = {"kind": "null-device"}
        else:
            raise ProbeFailure("GIT_NOT_MASKED")
    filesystem = os.statvfs("/scratch")
    return {"status": status_text, "mountinfo": mountinfo,
            "scratch_bytes": filesystem.f_frsize * filesystem.f_blocks,
            "devices": devices, "git": git, "paths": paths, "env_names": names, "env_values": values,
            "net_dev": read_kernel("/proc/self/net/dev", 32768),
            "ipv4_routes": read_kernel("/proc/self/net/route", 32768),
            "ipv6_routes": read_kernel("/proc/self/net/ipv6_route", 32768)}


def scratch_marker():
    """Create/unlink only our fixed exclusive regular file in verified scratch."""
    directory = os.open("/scratch", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    descriptor, identity = None, None
    try:
        descriptor = os.open(MARKER_NAME, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                             0o600, dir_fd=directory)
        info = os.fstat(descriptor)
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == 65534 and
                stat.S_IMODE(info.st_mode) == 0o600, "SCRATCH_MARKER_NOT_PRIVATE_REGULAR_FILE")
        identity = (info.st_dev, info.st_ino)
        require(os.write(descriptor, MARKER_BYTES) == len(MARKER_BYTES), "SCRATCH_MARKER_WRITE_FAILED")
        require(os.fstat(descriptor).st_size == len(MARKER_BYTES), "SCRATCH_MARKER_SIZE_MISMATCH")
    finally:
        try:
            if identity is not None:
                info = os.stat(MARKER_NAME, dir_fd=directory, follow_symlinks=False)
                require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and
                        (info.st_dev, info.st_ino) == identity, "SCRATCH_MARKER_IDENTITY_CHANGED")
                os.unlink(MARKER_NAME, dir_fd=directory)
        finally:
            if descriptor is not None:
                os.close(descriptor)
            os.close(directory)


def main(argv=None):
    try:
        require(not (sys.argv[1:] if argv is None else argv), "UNEXPECTED_PROBE_ARGUMENT")
        result = validate(observe())
        scratch_marker()
        result["scratchCreateUnlinkVerified"] = True
    except ProbeFailure as error:
        print(json.dumps({"status": "SOURCE_PROOF_SANDBOX_BLOCKED", "code": str(error)}, sort_keys=True))
        return 2
    except (OSError, ValueError, KeyError, TypeError):
        print(json.dumps({"status": "SOURCE_PROOF_SANDBOX_BLOCKED", "code": "OBSERVATION_UNAVAILABLE"}, sort_keys=True))
        return 2
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
