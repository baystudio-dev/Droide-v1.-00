#!/usr/bin/env python3
"""Current CLI v1: deliberate versioned copy of the historical static gate.

All historical checks are retained. The only predicate change requires the
additional current ApplicationWorkAdmission.isOpen shutdown guard. Historical
gate bytes remain unchanged; this is not a runtime patch or relaxed allowlist.
"""
from __future__ import annotations

import hashlib
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BINARY = ROOT / "app/src/main/jniLibs/arm64-v8a/libdroide_cli.so"
INSTALLER = ROOT / "app/src/main/java/com/baystudio/droide/core/DroideCliClientInstaller.kt"
BUILD = ROOT / "app/build.gradle.kts"
CLIENT_SOURCE = ROOT / "app/src/main/cpp/droide_cli_client.c"
WIRE = ROOT / "app/src/main/java/com/baystudio/droide/core/DroideCliWireProtocol.kt"
SERVER = ROOT / "app/src/main/java/com/baystudio/droide/core/DroideCliServer.kt"
ENDPOINT = ROOT / "app/src/main/java/com/baystudio/droide/core/CliEndpointPublication.kt"
SUBSTRATE = ROOT / "app/src/main/java/com/baystudio/droide/core/LocalExecutionSubstrate.kt"


def fail(message: str) -> None:
    raise SystemExit(f"DROIDE_CLI_CLIENT_FAILED: {message}")


def u16(data: bytes, off: int) -> int:
    return struct.unpack_from("<H", data, off)[0]


def u32(data: bytes, off: int) -> int:
    return struct.unpack_from("<I", data, off)[0]


def u64(data: bytes, off: int) -> int:
    return struct.unpack_from("<Q", data, off)[0]


if not BINARY.is_file():
    fail("missing APK-packaged ARM64 CLI bridge")
blob = BINARY.read_bytes()
if not (0 < len(blob) <= 64 * 1024):
    fail("native client size is outside reviewed bound")
installer_text = INSTALLER.read_text(encoding="utf-8")
match = re.search(r'const val SHA256 = "([0-9a-f]{64})"', installer_text)
if not match:
    fail("could not find pinned Kotlin SHA-256")
actual_sha = hashlib.sha256(blob).hexdigest()
if actual_sha != match.group(1):
    fail(f"SHA-256 mismatch: kotlin={match.group(1)} binary={actual_sha}")

if len(blob) < 64 or blob[:4] != b"\x7fELF" or blob[4] != 2 or blob[5] != 1:
    fail("client is not ELF64 little-endian")
if u16(blob, 16) != 2:
    fail("client must be ET_EXEC")
if u16(blob, 18) != 183:
    fail("client must target AArch64")
phoff, phentsize, phnum = u64(blob, 32), u16(blob, 54), u16(blob, 56)
if phoff < 64 or phentsize < 56 or not (1 <= phnum <= 32):
    fail("invalid program header table")
if phoff + phentsize * phnum > len(blob):
    fail("truncated program header table")
loads = 0
exec_loads = 0
for i in range(phnum):
    off = phoff + i * phentsize
    p_type = u32(blob, off)
    if p_type == 3:
        fail("PT_INTERP is forbidden; CLI bridge must be static")
    if p_type == 1:
        loads += 1
        flags = u32(blob, off + 4)
        align = u64(blob, off + 48)
        if align < 16 * 1024 or align % (16 * 1024):
            fail(f"PT_LOAD alignment is not 16 KiB compatible: {align}")
        if flags & 1:
            exec_loads += 1
if loads == 0 or exec_loads == 0:
    fail("client has no executable PT_LOAD segment")

build_text = BUILD.read_text(encoding="utf-8")
if "**/libdroide_cli.so" not in build_text or "useLegacyPackaging = true" not in build_text:
    fail("Gradle native packaging contract for CLI bridge is missing")

print(f"DROIDE_CLI_CLIENT_OK sha256={actual_sha} bytes={len(blob)} load_segments={loads} exec_loads={exec_loads}")


def c_define_int(text: str, name: str) -> int:
    match = re.search(rf"^#define\s+{re.escape(name)}\s+(\d+)\s*$", text, re.MULTILINE)
    if not match:
        fail(f"missing native protocol constant {name}")
    return int(match.group(1))


def kotlin_const_int(text: str, name: str) -> int:
    match = re.search(rf"const val\s+{re.escape(name)}\s*=\s*([0-9_]+)", text)
    if not match:
        fail(f"missing Kotlin protocol constant {name}")
    return int(match.group(1).replace("_", ""))


def kotlin_code(text: str) -> str:
    """Mask comments for the bounded linkage checks; preserve strings and line breaks."""
    tokens = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*')
    result = []
    cursor = 0
    while match := tokens.search(text, cursor):
        result.append(text[cursor:match.start()])
        end = match.end()
        value = match.group()
        if value == "/*":
            depth = 1
            for delimiter in re.finditer(r"/\*|\*/", text[end:]):
                depth += 1 if delimiter.group() == "/*" else -1
                if depth == 0:
                    end += delimiter.end()
                    break
            if depth:
                fail("unterminated Kotlin comment in CLI endpoint linkage source")
            value = text[match.start():end]
        result.append(re.sub(r"[^\n]", " ", value) if value.startswith(("//", "/*")) else value)
        cursor = end
    result.append(text[cursor:])
    return "".join(result)


client_text = CLIENT_SOURCE.read_text(encoding="utf-8")
wire_text = WIRE.read_text(encoding="utf-8")
server_text = SERVER.read_text(encoding="utf-8")
substrate_text = SUBSTRATE.read_text(encoding="utf-8")
if not ENDPOINT.is_file():
    fail("missing CLI endpoint publication source")
endpoint_code = kotlin_code(ENDPOINT.read_text(encoding="utf-8"))
server_code = kotlin_code(server_text)

for native_name, kotlin_name in [
    ("MAX_ARGS", "MAX_ARGUMENTS"),
    ("MAX_ARG_BYTES", "MAX_ARGUMENT_BYTES"),
    ("MAX_REQUEST_BYTES", "MAX_REQUEST_BYTES"),
    ("MAX_STREAM_BYTES", "MAX_STREAM_BYTES"),
    ("MAX_PROGRESS_BYTES", "MAX_PROGRESS_BYTES"),
]:
    native_value = c_define_int(client_text, native_name)
    kotlin_value = kotlin_const_int(wire_text, kotlin_name)
    if native_value != kotlin_value:
        fail(f"wire limit drift: native {native_name}={native_value}, Kotlin {kotlin_name}={kotlin_value}")

if "#define SYS_sendto 206" not in client_text or "#define MSG_NOSIGNAL 0x4000" not in client_text or "SYS_sendto,fd" not in client_text:
    fail("native client socket writes must use sendto(MSG_NOSIGNAL) so a closed server yields a structured exit code instead of SIGPIPE")

for literal in [
    "static const u8 request_magic[4] = {'D','R','Q','2'};",
    "static const u8 progress_magic[4] = {'D','R','P','1'};",
    "static const u8 response_magic[4] = {'D','R','S','1'};",
    'static const char endpoint_path[] = "/opt/droide/cli/endpoint-v1";',
]:
    if literal not in client_text:
        fail(f"native wire contract is missing: {literal}")
for literal in ["'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '1'.code.toByte()",
                "'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '2'.code.toByte()",
                "'D'.code.toByte(), 'R'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()",
                "'D'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), '1'.code.toByte()"]:
    if literal not in wire_text:
        fail("Kotlin wire magic drifted from bundled native client")

# Follow the split owner explicitly. These are source-shape guards, not Kotlin execution.
if not re.search(
    r'(?m)^internal class CliEndpointFile\(filesDir: File, private val workspaceId: String\) \{\s*'
    r'private val root = filesDir\.canonicalFile\s*'
    r'private val cliDir = File\(root, "cli"\)\s*'
    r'private val discoveryDir = File\(cliDir, workspaceId\)\s*'
    r'val endpointFile = File\(discoveryDir, "endpoint-v1"\)\s*$', endpoint_code,
):
    fail("CLI endpoint owner path drifted from native client")
if r'val payload = "DROIDE_CLI_ENDPOINT_V1\n$socketName\n$workspaceId\n".toByteArray(Charsets.UTF_8)' not in endpoint_code:
    fail("CLI endpoint payload drifted from native client")
if not re.search(
    r'fun publishPrepared\(\) \{\s*requireDirectories\(\)\s*'
    r'check\(Files\.isRegularFile\(stagingFile\.toPath\(\), NOFOLLOW_LINKS\)\) '
    r'\{ "CLI staging file is not a regular file" \}\s*'
    r'Files\.move\(stagingFile\.toPath\(\), endpointFile\.toPath\(\), ATOMIC_MOVE, REPLACE_EXISTING\)\s*\}',
    endpoint_code,
):
    fail("CLI endpoint owner no longer atomically publishes its prepared file")
if not re.search(
    r'fun publish\(owner: Owner\): Boolean = synchronized\(monitor\) \{\s*'
    r'if \(!isCurrent\(owner\)\) return@synchronized false\s*'
    r'check\(publishedEndpointOwner == null\) \{ "Previous CLI endpoint has not been withdrawn" \}\s*'
    r'replace\(owner\)\s*publishedEndpointOwner = owner\s*true\s*\}', endpoint_code,
):
    fail("CLI endpoint publication no longer invokes its current owner's replacement")
if not re.search(
    r'(?m)^\s*private val endpointPublication = CliEndpointPublication<Session>\(\s*'
    r'isCurrent = \{ active === it && !it\.isClosed && ApplicationWorkAdmission\.isOpen \},\s*'
    r'replace = \{ it\.endpoint\.publishPrepared\(\) \},', server_code,
):
    fail("CLI server publication is disconnected from its session endpoint")
if not re.search(r'(?m)^\s*val endpoint = CliEndpointFile\(appContext\.filesDir, workspaceId\)\s*$', server_code):
    fail("CLI server endpoint is disconnected from app storage or workspace")
if not re.search(
    r'(?m)^\s*endpoint\.prepare\(socketName\)\s*'
    r'if \(!endpointPublication\.publish\(this@Session\)\) return@launch\s*$', server_code,
):
    fail("CLI server no longer prepares and publishes its own endpoint")
if "client.peerCredentials.uid == Process.myUid()" not in server_text:
    fail("CLI server no longer authenticates the connected peer UID")
if ':/opt/droide/core-bin/droide' not in substrate_text or ':/opt/droide/cli' not in substrate_text:
    fail("PRoot does not bind the APK client and workspace discovery into their reviewed paths")
if 'listOf("/opt/droide/core-bin")' not in substrate_text:
    fail("bundled CLI directory is not projected ahead of the guest PATH")

print("CURRENT_DROIDE_CLI_CONTRACT_V1_OK request_protocol=2 legacy_request=1 final=DRS1 progress=DRP1/16384 args=128 request=16384 streams=65536 peer_uid=bound workspace_discovery=bound")
