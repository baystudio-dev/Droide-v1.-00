"""Full-source checkpoint controls using only authored local Git fixtures."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HELPER = Path(__file__).resolve().parents[1] / "source_checkpoint.py"


class SourceCheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="source-checkpoint-test-")
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.archive = self.root / "full.zip"
        self.destination = self.root / "restored"
        self.git("init", "-q")
        self.git("config", "user.name", "Checkpoint fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        (self.source / "README.md").write_text("Authored project fixture\n")
        (self.source / ".gitignore").write_text("build/\n")
        (self.source / "build").mkdir()
        (self.source / "build" / "cache").write_text("not source")
        (self.source / "run.sh").write_text("not executed\n")
        (self.source / "run.sh").chmod(0o755)
        (self.source / "unicode λ.bin").write_bytes(bytes(range(256)))
        self.git("add", ".")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-qm", "Authored full-source fixture")
        self.modes = self.root / "directory-modes.json"
        self.modes.write_text(json.dumps({"empty": 0o700}))

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.source, stderr=subprocess.STDOUT)

    def run_helper(self, *args, succeeds=True):
        result = subprocess.run([sys.executable, str(HELPER), *map(str, args)], capture_output=True, text=True)
        if succeeds:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    def export(self, succeeds=True):
        return self.run_helper("export", self.source, "R103", self.archive, "--report", "README.md",
                               "--directory-modes", self.modes, succeeds=succeeds)

    def restore(self, checksum=None, succeeds=True):
        checksum = checksum or hashlib.sha256(self.archive.read_bytes()).hexdigest()
        return self.run_helper("restore", self.archive, checksum, self.destination, succeeds=succeeds)

    def test_full_restore_keeps_binary_unicode_executable_and_empty_directory(self):
        receipt = json.loads(self.export().stdout)
        result = json.loads(self.restore().stdout)
        root = self.destination / "Droide-R103-source"
        self.assertEqual(4, result["source_files"])
        self.assertEqual(receipt["commit"], result["commit"])
        self.assertEqual(bytes(range(256)), (root / "unicode λ.bin").read_bytes())
        self.assertEqual(0o755, (root / "run.sh").stat().st_mode & 0o777)
        self.assertEqual(0o700, (root / "empty").stat().st_mode & 0o777)
        self.assertFalse((root / "build").exists())
        self.assertFalse((root / ".git").exists())
        self.assertFalse(receipt["requires_prior_checkpoint"])
        self.assertEqual("NOT_UPLOADED", receipt["library_delivery"])

    def test_uncommitted_file_is_not_silently_omitted(self):
        (self.source / "new-code.kt").write_text("pending source")
        self.export(succeeds=False)
        self.assertFalse(self.archive.exists())

    def test_modified_source_is_not_mislabeled_with_old_commit(self):
        (self.source / "README.md").write_text("new changes")
        self.export(succeeds=False)
        self.assertFalse(self.archive.exists())

    def test_restore_rejects_wrong_digest_and_existing_destination(self):
        self.export()
        self.restore("0" * 64, succeeds=False)
        self.assertFalse(self.destination.exists())
        self.destination.mkdir()
        sentinel = self.destination / "personal.txt"
        sentinel.write_text("preserve")
        self.restore(succeeds=False)
        self.assertEqual("preserve", sentinel.read_text())
        self.assertEqual([sentinel], list(self.destination.iterdir()))


if __name__ == "__main__":
    unittest.main()
