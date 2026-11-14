#!/usr/bin/env python3
"""Local Termux patch integrity regressions; never modify the working AARs."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
METADATA = "third_party/termux/patches/REBUILD.json"
OUTPUT = "app/libs/terminal-emulator-v0.118.0-droide-input.aar"


def zip_bytes(entries: dict[str, bytes]) -> bytes:
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return data.getvalue()


def zip_entries(data: bytes) -> dict[str, bytes]:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


class TermuxInputProvenanceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="termux-provenance-test-")
        self.root = Path(self.temporary.name)
        paths = [
            "tools/verify_termux_provenance.py", "tools/build_termux_input_aar.py",
            "third_party/termux/UPSTREAM_PROVENANCE.json", "third_party/termux/SHA256SUMS",
            "third_party/termux/native/REBUILD.json", "third_party/termux/native/termux.c",
            "third_party/termux/native/OWNED_PROCESS_ABI.json", "app/src/main/cpp/CMakeLists.txt",
            "third_party/termux/native/termux-upstream.c",
            "third_party/termux/terminal-emulator-v0.118.0-sources.jar",
            "third_party/termux/terminal-view-v0.118.0-sources.jar",
            "app/libs/terminal-emulator-v0.118.0.aar",
            "app/libs/terminal-emulator-v0.118.0-java.aar",
            "app/libs/terminal-view-v0.118.0.aar", OUTPUT, METADATA,
            "third_party/termux/patches/src/com/termux/terminal/TerminalSession.java",
            "third_party/termux/patches/0001-agent-input-owner-hook.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalRow.java",
            "third_party/termux/patches/0002-bounded-combining-row.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalEmulator.java",
            "third_party/termux/patches/0003-bounded-parser-work.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalBuffer.java",
            "third_party/termux/patches/0004-retained-buffer-budget.patch",
            "third_party/termux/patches/src/com/termux/terminal/ByteQueue.java",
            "third_party/termux/patches/0005-drainable-output-queue.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalMemoryBudget.java",
            "third_party/termux/patches/0006-mobile-memory-budget.patch",
            "third_party/termux/patches/src/com/termux/terminal/JNI.java",
            "third_party/termux/patches/0007-owned-process-jni.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalProcessOwner.java",
            "third_party/termux/patches/0008-prestarted-process-owner.patch",
            "third_party/termux/patches/src/com/termux/terminal/TerminalPtyIo.java",
            "third_party/termux/patches/0009-bounded-pty-io.patch",
            "app/src/main/assets/legal/licenses/Termux-upstream-LICENSE.md",
        ]
        for relative in paths:
            destination = self.root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def verify(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["python3", str(self.root / "tools/verify_termux_provenance.py")],
                              capture_output=True, text=True, check=False)

    def replace_aar_and_pin(self, entries: dict[str, bytes]) -> None:
        """A newly pinned output must still obey exact class and unrelated-entry scope."""
        payload = zip_bytes(entries)
        (self.root / OUTPUT).write_bytes(payload)
        metadata = json.loads((self.root / METADATA).read_text())
        metadata["output"]["sha256"] = hashlib.sha256(payload).hexdigest()
        (self.root / METADATA).write_text(json.dumps(metadata))
        # Mutation fixture only: its deliberately changed archive pin still must fail scope checks.
        sums = self.root / "third_party/termux/SHA256SUMS"
        lines = sums.read_text().splitlines()
        lines = [metadata["output"]["sha256"] + "  ../../" + OUTPUT if line.endswith("../../" + OUTPUT) else line for line in lines]
        sums.write_text("\n".join(lines) + "\n")

    def test_current_source_rejects_stale_release_artifacts(self) -> None:
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("owned PTY ABI v2 native rebuild pending", result.stderr)

    def test_status_label_alone_cannot_promote_unbuilt_native_source(self) -> None:
        path = self.root / "third_party/termux/native/OWNED_PROCESS_ABI.json"
        contract = json.loads(path.read_text())
        contract["status"] = "BUILT_AND_INDEPENDENTLY_VERIFIED"
        path.write_text(json.dumps(contract))
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("native ABI v2 registration/rebuild receipt missing or stale", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_unrelated_class_change_rejected_even_with_new_output_pin(self) -> None:
        entries = zip_entries((self.root / OUTPUT).read_bytes())
        classes = zip_entries(entries["classes.jar"])
        classes["com/termux/terminal/TerminalOutput.class"] += b"unexpected change"
        entries["classes.jar"] = zip_bytes(classes)
        self.replace_aar_and_pin(entries)
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed an unrelated compiled entry", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_additional_class_rejected_even_with_new_output_pin(self) -> None:
        entries = zip_entries((self.root / OUTPUT).read_bytes())
        classes = zip_entries(entries["classes.jar"])
        classes["com/termux/terminal/Unexpected.class"] = b"unexpected"
        entries["classes.jar"] = zip_bytes(classes)
        self.replace_aar_and_pin(entries)
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("introduced unexpected/missing classes", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_unrelated_aar_entry_rejected_even_with_new_output_pin(self) -> None:
        entries = zip_entries((self.root / OUTPUT).read_bytes())
        entries["AndroidManifest.xml"] += b"unexpected change"
        self.replace_aar_and_pin(entries)
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("changed an unrelated entry", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_source_change_cannot_silently_retain_reviewed_diff(self) -> None:
        metadata = json.loads((self.root / METADATA).read_text())
        source = self.root / metadata["source"]["path"]
        source.write_bytes(source.read_bytes() + b"\n// unreviewed change\n")
        metadata["source"]["sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
        (self.root / METADATA).write_text(json.dumps(metadata))
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("diff does not exactly describe", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_schema_one_receipt_is_rejected_with_pending_rebuild_message(self) -> None:
        metadata = json.loads((self.root / METADATA).read_text())
        metadata["schemaVersion"] = 1
        (self.root / METADATA).write_text(json.dumps(metadata))
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("overlay rebuild pending", result.stderr)

    @unittest.skip("Requires a separately authorized ABI-v2 rebuilt release fixture; current source is UNBUILT")
    def test_local_checksum_file_must_follow_the_actual_rebuild(self) -> None:
        sums = self.root / "third_party/termux/SHA256SUMS"
        sums.write_text("\n".join(line for line in sums.read_text().splitlines() if not line.endswith("../../" + OUTPUT)) + "\n")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA256SUMS", result.stderr)

    def test_upstream_binary_tampering_still_rejected(self) -> None:
        original = self.root / "app/libs/terminal-emulator-v0.118.0.aar"
        original.write_bytes(original.read_bytes() + b"unexpected change")
        result = self.verify()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("terminal-emulator-v0.118.0.aar SHA-256 mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
