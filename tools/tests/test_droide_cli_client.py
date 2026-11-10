#!/usr/bin/env python3
"""Run the native client's actual framing/wait loop on Linux with host syscall shims."""
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]

class NativeCliClientTest(unittest.TestCase):
    WAITING = (b"[Droide] Awaiting CLI response; no final result yet. Use droide pkg status in another "
               b"terminal to inspect the transaction. Do not repeat the command.\n")

    def progress_bytes(self, stderr):
        return stderr.replace(self.WAITING, b"")

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="droide-native-cli-")
        cls.endpoint = Path(cls.temp.name) / "endpoint-v1"
        cls.binary = Path(cls.temp.name) / "droide-test"
        subprocess.run([os.environ.get("CC", "cc"), "-Os", "-Wall", "-Wextra", "-Werror",
                        "-Wno-misleading-indentation", "-DDROIDE_CLI_HOST_TEST",
                        f'-DDROIDE_CLI_TEST_ENDPOINT="{cls.endpoint}"',
                        str(ROOT / "app/src/main/cpp/droide_cli_client.c"), "-o", str(cls.binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_fixture(self, handler):
        name = "droide-test-" + uuid.uuid4().hex
        self.endpoint.write_text(f"DROIDE_CLI_ENDPOINT_V1\n{name}\n")
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind("\0" + name)
        listener.listen(1)
        errors = []
        requests = []
        def read_exact(peer, count):
            result = b""
            while len(result) < count:
                data = peer.recv(count - len(result))
                if not data:
                    raise EOFError("client disconnected during request")
                result += data
            return result
        def serve():
            try:
                with listener.accept()[0] as peer:
                    peer.settimeout(2)
                    self.assertEqual(b"DRQ2", read_exact(peer, 4))
                    count = struct.unpack("!I", read_exact(peer, 4))[0]
                    args = [read_exact(peer, struct.unpack("!I", read_exact(peer, 4))[0]).decode() for _ in range(count)]
                    requests.append(args)
                    handler(peer)
            except BaseException as error:
                errors.append(error)
        thread = threading.Thread(target=serve, daemon=True)
        thread.start()
        try:
            result = subprocess.run([str(self.binary), "pkg", "install", "fixture"], capture_output=True, timeout=3)
            thread.join(2)
            self.assertFalse(thread.is_alive(), "server did not finish")
            if errors:
                raise errors[0]
            self.assertEqual([["pkg", "install", "fixture"]], requests, "request was duplicated")
            return result
        finally:
            listener.close()

    def test_quiet_wait_has_stderr_heartbeat_and_preserves_final_result(self):
        def serve(peer):
            time.sleep(.18)
            stdout = "installed 日本語\n".encode()
            stderr = b"fixture warning\n"
            peer.sendall(b"DRS1" + struct.pack("!III", 7, len(stdout), len(stderr)) + stdout + stderr)
        result = self.run_fixture(serve)
        self.assertEqual(7, result.returncode)
        self.assertEqual("installed 日本語\n".encode(), result.stdout)
        self.assertIn(b"no final result yet", result.stderr)
        self.assertTrue(result.stderr.endswith(b"fixture warning\n"))

    def test_fragmented_response_does_not_corrupt_framing(self):
        def serve(peer):
            frame = b"DRS1" + struct.pack("!III", 0, 2, 0) + b"ok"
            for offset in range(0, len(frame), 3):
                peer.sendall(frame[offset:offset+3])
                time.sleep(.06)
        result = self.run_fixture(serve)
        self.assertEqual(0, result.returncode)
        self.assertEqual(b"ok", result.stdout)
        self.assertIn(b"Awaiting", result.stderr)

    def test_disconnected_transaction_finishes_without_endless_wait_or_retry(self):
        result = self.run_fixture(lambda peer: None)
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertIn(b"final status is unknown", result.stderr)

    def test_invalid_frame_does_not_relay_untrusted_stdout(self):
        result = self.run_fixture(lambda peer: peer.sendall(b"BAD!" + struct.pack("!III", 0, 6, 0) + b"unsafe"))
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"", result.stdout)

    def test_streamed_progress_preserves_stdout_and_final_failure_code(self):
        def serve(peer):
            for text in [b"Preparing Ubuntu\n", "Downloading 日本語\n".encode()]:
                frame = b"DRP1" + struct.pack("!III", 0, 0, len(text)) + text
                for offset in range(0, len(frame), 5):
                    peer.sendall(frame[offset:offset+5])
            peer.sendall(b"DRS1" + struct.pack("!III", 4, 0, 12) + b"health fails")
        result = self.run_fixture(serve)
        self.assertEqual(4, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertEqual("Preparing Ubuntu\nDownloading 日本語\nhealth fails".encode(), self.progress_bytes(result.stderr))

    def test_disconnect_after_progress_never_claims_completion(self):
        def serve(peer):
            peer.sendall(b"DRP1" + struct.pack("!III", 0, 0, 4) + b"work")
        result = self.run_fixture(serve)
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertTrue(self.progress_bytes(result.stderr).startswith(b"work"))
        self.assertIn(b"final status is unknown", result.stderr)

    def test_progress_stdout_is_rejected_before_relay(self):
        result = self.run_fixture(lambda peer: peer.sendall(b"DRP1" + struct.pack("!III", 0, 6, 0) + b"unsafe"))
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"", result.stdout)

    def test_oversized_progress_frame_is_rejected_without_waiting_for_body(self):
        result = self.run_fixture(lambda peer: peer.sendall(b"DRP1" + struct.pack("!III", 0, 0, 16385)))
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"", result.stdout)

    def test_help_and_bare_command_work_without_an_endpoint(self):
        self.endpoint.unlink(missing_ok=True)
        for args in [[], ["--help"], ["-h"], ["help"]]:
            with self.subTest(args=args):
                result = subprocess.run([str(self.binary), *args], capture_output=True, timeout=1)
                self.assertEqual(0, result.returncode)
                self.assertIn(b"Usage:", result.stdout)
                self.assertIn(b"status", result.stdout)
                self.assertEqual(b"", result.stderr)

    def test_valid_server_failure_is_relayed_without_false_transport_warning(self):
        def serve(peer):
            stderr = b"Installer failed a health check\n"
            peer.sendall(b"DRS1" + struct.pack("!III", 70, 0, len(stderr)) + stderr)
        result = self.run_fixture(serve)
        self.assertEqual(70, result.returncode)
        self.assertEqual(b"Installer failed a health check\n", result.stderr)

if __name__ == "__main__":
    unittest.main(verbosity=2)
