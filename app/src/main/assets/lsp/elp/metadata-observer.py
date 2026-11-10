# App-authored fixed metadata observer. Input: SHA-256 line, then exact TOML bytes.
# No project imports, writes, subprocesses, network, or glob expansion.
import hashlib
import json
import math
import os
import sys
import sysconfig
import tomllib


def checked_json(value, depth=0):
    if depth > 64:
        raise ValueError("TOML nesting exceeds metadata policy")
    if type(value) is dict:
        if any(type(key) is not str for key in value):
            raise ValueError("Non-string TOML key")
        return {key: checked_json(item, depth + 1) for key, item in value.items()}
    if type(value) is list:
        return [checked_json(item, depth + 1) for item in value]
    if type(value) is float:
        if not math.isfinite(value):
            raise ValueError("Non-finite TOML float is unsupported")
        return value
    if type(value) is int:
        if abs(value) > 9007199254740991:
            raise ValueError("TOML integer exceeds exact JSON range")
        return value
    if type(value) in (str, bool):
        return value
    raise ValueError("TOML date/time or other non-JSON value is unsupported")


def main():
    if (sys.implementation.name != "cpython" or sys.version_info[:2] != (3, 12)
            or sys.version_info.releaselevel != "final" or not sys.flags.isolated
            or not sys.flags.no_site):
        raise ValueError("Requires selected stable CPython 3.12 with -I -S")
    envelope = sys.stdin.buffer.read(1048576 + 66)
    if len(envelope) > 1048576 + 65 or len(envelope) < 65 or envelope[64:65] != b"\n":
        raise ValueError("Invalid or oversized exact TOML input")
    expected = envelope[:64].decode("ascii")
    raw = envelope[65:]
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("TOML digest changed")
    data = checked_json(tomllib.loads(raw.decode("utf-8", errors="strict")))
    response = {"contractVersion": 1, "inputSha256": expected,
                "python": {"executable": os.path.realpath(sys.executable),
                           "version": ".".join(map(str, sys.version_info[:3])),
                           "stdlibOrigin": os.path.realpath(sysconfig.get_path("stdlib")),
                           "tomllibOrigin": os.path.realpath(tomllib.__file__)}, "data": data}
    output = json.dumps(response, ensure_ascii=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    if len(output) > 4194304:
        raise ValueError("Metadata output exceeds policy")
    sys.stdout.buffer.write(output)
    sys.stdout.buffer.flush()


if __name__ == "__main__":
    main()
