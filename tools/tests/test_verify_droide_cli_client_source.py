#!/usr/bin/env python3
"""Bounded source-only controls for verify_droide_cli_client.py."""
from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import hashlib
import io
from pathlib import Path
import runpy
import struct
import unittest
from unittest.mock import patch

VERIFIER = Path(__file__).resolve().parents[1] / "verify_droide_cli_client.py"
VIRTUAL_ROOT = VERIFIER.parents[1]
CORE = "app/src/main/java/com/baystudio/droide/core/"
BINARY = "app/src/main/jniLibs/arm64-v8a/libdroide_cli.so"
INSTALLER = CORE + "DroideCliClientInstaller.kt"
SERVER = CORE + "DroideCliServer.kt"
ENDPOINT = CORE + "CliEndpointPublication.kt"
WIRE = CORE + "DroideCliWireProtocol.kt"
SUBSTRATE = CORE + "LocalExecutionSubstrate.kt"
CLIENT = "app/src/main/cpp/droide_cli_client.c"
BUILD = "app/build.gradle.kts"
TEXT_INPUTS = (INSTALLER, SERVER, ENDPOINT, WIRE, SUBSTRATE, CLIENT, BUILD)
SOURCES: dict[str, str | bytes] = {}


def replace_once(inputs: dict[str, str | bytes], path: str, old: str, new: str) -> None:
    source = inputs[path]
    if not isinstance(source, str) or source.count(old) != 1:
        raise AssertionError(f"fixture anchor must occur exactly once: {path}: {old!r}")
    inputs[path] = source.replace(old, new, 1)


def run_checker(inputs: dict[str, str | bytes]) -> tuple[str | None, str]:
    def key(path: Path) -> str:
        return path.relative_to(VIRTUAL_ROOT).as_posix()

    def is_file(path: Path) -> bool:
        return key(path) in inputs

    def read_text(path: Path, encoding: str | None = None) -> str:
        if encoding != "utf-8":
            raise AssertionError("unexpected text encoding")
        value = inputs[key(path)]
        if not isinstance(value, str):
            raise AssertionError("unexpected text read")
        return value

    def read_bytes(path: Path) -> bytes:
        value = inputs[key(path)]
        if key(path) != BINARY or not isinstance(value, bytes):
            raise AssertionError("unexpected bytes read")
        return value

    output = io.StringIO()
    failure = None
    with patch.object(Path, "is_file", is_file), \
            patch.object(Path, "read_text", read_text), \
            patch.object(Path, "read_bytes", read_bytes), redirect_stdout(output):
        try:
            runpy.run_path(str(VERIFIER), run_name="__main__")
        except SystemExit as error:
            failure = str(error.code)
    return failure, output.getvalue()


CASES = [
    ("renamed_endpoint", ENDPOINT, 'val endpointFile = File(discoveryDir, "endpoint-v1")',
     'val endpointFile = File(discoveryDir, "endpoint-v2")', "CLI endpoint owner path"),
    ("comment_only_endpoint", ENDPOINT, 'val endpointFile = File(discoveryDir, "endpoint-v1")',
     '// val endpointFile = File(discoveryDir, "endpoint-v1")', "CLI endpoint owner path"),
    ("nested_comment_only_endpoint", ENDPOINT, 'val endpointFile = File(discoveryDir, "endpoint-v1")',
     '/* outer /* nested */\nval endpointFile = File(discoveryDir, "endpoint-v1")\n*/', "CLI endpoint owner path"),
    ("unrelated_endpoint_decoy", ENDPOINT, 'val endpointFile = File(discoveryDir, "endpoint-v1")',
     'val endpointFile = File(discoveryDir, "endpoint-v2")\n'
     '    val unrelated = File(discoveryDir, "endpoint-v1")', "CLI endpoint owner path"),
    ("wrong_helper_root", ENDPOINT, 'private val root = filesDir.canonicalFile',
     'private val root = File("/tmp").canonicalFile', "CLI endpoint owner path"),
    ("wrong_helper_workspace", ENDPOINT, 'private val discoveryDir = File(cliDir, workspaceId)',
     'private val discoveryDir = File(cliDir, "other")', "CLI endpoint owner path"),
    ("wrong_endpoint_payload", ENDPOINT, 'DROIDE_CLI_ENDPOINT_V1',
     'DROIDE_CLI_ENDPOINT_V2', "CLI endpoint payload"),
    ("missing_payload_workspace", ENDPOINT, r'$socketName\n$workspaceId\n',
     r'$socketName\n', "CLI endpoint payload"),
    ("disconnected_atomic_target", ENDPOINT,
     'Files.move(stagingFile.toPath(), endpointFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)',
     'Files.move(stagingFile.toPath(), otherFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)',
     "CLI endpoint owner no longer atomically"),
    ("missing_publication_replace", ENDPOINT, '        replace(owner)',
     '        // replace(owner)', "CLI endpoint publication no longer invokes"),
    ("wrong_publication_replace_owner", ENDPOINT, '        replace(owner)',
     '        replace(otherOwner)', "CLI endpoint publication no longer invokes"),
    ("disconnected_current_owner", SERVER, 'isCurrent = { active === it && !it.isClosed && ApplicationWorkAdmission.isOpen },',
     'isCurrent = { true },', "CLI server publication is disconnected"),
    ("disconnected_publication_receiver", SERVER, 'replace = { it.endpoint.publishPrepared() },',
     'replace = { it.otherEndpoint.publishPrepared() },', "CLI server publication is disconnected"),
    ("disconnected_publication_callback", SERVER, 'replace = { it.endpoint.publishPrepared() },',
     'replace = { /* it.endpoint.publishPrepared() */ },', "CLI server publication is disconnected"),
    ("wrong_constructor_root", SERVER, 'val endpoint = CliEndpointFile(appContext.filesDir, workspaceId)',
     'val endpoint = CliEndpointFile(otherRoot, workspaceId)', "CLI server endpoint is disconnected"),
    ("wrong_constructor_workspace", SERVER, 'val endpoint = CliEndpointFile(appContext.filesDir, workspaceId)',
     'val endpoint = CliEndpointFile(appContext.filesDir, "other")', "CLI server endpoint is disconnected"),
    ("comment_only_constructor", SERVER, 'val endpoint = CliEndpointFile(appContext.filesDir, workspaceId)',
     '/* val endpoint = CliEndpointFile(appContext.filesDir, workspaceId) */', "CLI server endpoint is disconnected"),
    ("missing_prepare", SERVER, '                    endpoint.prepare(socketName)',
     '                    // endpoint.prepare(socketName)', "CLI server no longer prepares and publishes"),
    ("wrong_prepare_receiver", SERVER, 'endpoint.prepare(socketName)',
     'otherEndpoint.prepare(socketName)', "CLI server no longer prepares and publishes"),
    ("wrong_prepare_socket", SERVER, 'endpoint.prepare(socketName)',
     'endpoint.prepare(otherSocketName)', "CLI server no longer prepares and publishes"),
    ("missing_publish", SERVER, 'if (!endpointPublication.publish(this@Session)) return@launch',
     '// if (!endpointPublication.publish(this@Session)) return@launch', "CLI server no longer prepares and publishes"),
    ("wrong_publish_receiver", SERVER, 'if (!endpointPublication.publish(this@Session)) return@launch',
     'if (!otherPublication.publish(this@Session)) return@launch', "CLI server no longer prepares and publishes"),
    ("wrong_publish_owner", SERVER, 'if (!endpointPublication.publish(this@Session)) return@launch',
     'if (!endpointPublication.publish(otherSession)) return@launch', "CLI server no longer prepares and publishes"),
    ("preserved_peer_uid", SERVER, 'client.peerCredentials.uid == Process.myUid()',
     'true', "CLI server no longer authenticates"),
    ("preserved_guest_bind", SUBSTRATE, ':/opt/droide/cli',
     ':/opt/droide/other-cli', "PRoot does not bind"),
    ("preserved_native_magic", CLIENT, "static const u8 request_magic[4] = {'D','R','Q','2'};",
     "static const u8 request_magic[4] = {'D','R','Q','3'};", "native wire contract is missing"),
    ("preserved_native_endpoint", CLIENT, 'static const char endpoint_path[] = "/opt/droide/cli/endpoint-v1";',
     'static const char endpoint_path[] = "/opt/droide/cli/endpoint-v2";', "native wire contract is missing"),
    ("preserved_kotlin_magic", WIRE, "'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '2'.code.toByte()",
     "'D'.code.toByte(), 'R'.code.toByte(), 'Q'.code.toByte(), '3'.code.toByte()", "Kotlin wire magic drifted"),
    ("preserved_gradle_packaging", BUILD, '"**/libdroide_cli.so"',
     '"**/other-cli.so"', "Gradle native packaging contract"),
]


class SourceContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SOURCES:
            SOURCES.update({path: (VIRTUAL_ROOT / path).read_text(encoding="utf-8") for path in TEXT_INPUTS})
            SOURCES[BINARY] = (VIRTUAL_ROOT / BINARY).read_bytes()
        if len(SOURCES[BINARY]) > 64 * 1024:
            raise ValueError("CLI fixture exceeds the bounded input size")

    def test_valid_current_split_owner_sources(self) -> None:
        failure, output = run_checker(dict(SOURCES))
        self.assertIsNone(failure)
        self.assertIn("DROIDE_CLI_CLIENT_OK", output)
        self.assertIn("DROIDE_CLI_CONTRACT_OK", output)

    def test_valid_comments_preserve_linkage(self) -> None:
        inputs = dict(SOURCES)
        replace_once(inputs, SERVER, 'endpoint.prepare(socketName)',
                     'endpoint.prepare(socketName) /* outer /* nested */ valid comment */')
        failure, output = run_checker(inputs)
        self.assertIsNone(failure)
        self.assertIn("DROIDE_CLI_CONTRACT_OK", output)

    def test_missing_endpoint_helper_source(self) -> None:
        inputs = dict(SOURCES)
        del inputs[ENDPOINT]
        failure, _ = run_checker(inputs)
        self.assertEqual("DROIDE_CLI_CLIENT_FAILED: missing CLI endpoint publication source", failure)

    def test_preserved_native_hash(self) -> None:
        inputs = dict(SOURCES)
        blob = bytearray(inputs[BINARY])
        blob[-1] ^= 1
        inputs[BINARY] = bytes(blob)
        failure, _ = run_checker(inputs)
        self.assertIsNotNone(failure)
        self.assertIn("SHA-256 mismatch", failure)

    def test_preserved_native_elf_architecture(self) -> None:
        inputs = dict(SOURCES)
        blob = bytearray(inputs[BINARY])
        old_hash = hashlib.sha256(blob).hexdigest()
        struct.pack_into("<H", blob, 18, 62)  # Inert x86-64 ELF header, never loaded.
        inputs[BINARY] = bytes(blob)
        replace_once(inputs, INSTALLER, old_hash, hashlib.sha256(blob).hexdigest())
        failure, _ = run_checker(inputs)
        self.assertEqual("DROIDE_CLI_CLIENT_FAILED: client must target AArch64", failure)

    def test_preserved_guest_path(self) -> None:
        inputs = dict(SOURCES)


        for old in [
            'val managedBins = listOf("/opt/droide/core-bin") + managedGuestPaths',
            'else listOf("/opt/droide/core-bin") + workspaceBins',
        ]:
            replace_once(inputs, SUBSTRATE, old, old.replace("core-bin", "other-bin"))
        failure, _ = run_checker(inputs)
        self.assertEqual("DROIDE_CLI_CLIENT_FAILED: bundled CLI directory is not projected ahead of the guest PATH", failure)

    def test_preserved_wire_limit(self) -> None:
        inputs = dict(SOURCES)
        replace_once(inputs, CLIENT, '#define MAX_ARGS 128', '#define MAX_ARGS 127')
        failure, _ = run_checker(inputs)
        self.assertIsNotNone(failure)
        self.assertIn("wire limit drift: native MAX_ARGS=127", failure)


def make_negative(path: str, old: str, new: str, diagnostic: str):
    def test(self: SourceContractTests) -> None:
        inputs = dict(SOURCES)
        replace_once(inputs, path, old, new)
        failure, output = run_checker(inputs)
        self.assertIsNotNone(failure)
        self.assertIn("DROIDE_CLI_CLIENT_FAILED: " + diagnostic, failure)
        self.assertNotIn("DROIDE_CLI_CONTRACT_OK", output)
    return test


for name, path, old, new, diagnostic in CASES:
    setattr(SourceContractTests, "test_" + name, make_negative(path, old, new, diagnostic))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=VIRTUAL_ROOT)
    args = parser.parse_args()
    source_root = args.source_root.resolve(strict=True)
    SOURCES.update({path: (source_root / path).read_text(encoding="utf-8") for path in TEXT_INPUTS})
    SOURCES[BINARY] = (source_root / BINARY).read_bytes()
    if len(SOURCES[BINARY]) > 64 * 1024:
        raise SystemExit("fixture native bytes exceed the reviewed read-only bound")
    unittest.main(argv=[str(Path(__file__))], verbosity=2)
