"""Real owned-process-group cleanup fixtures; no Gradle or unrelated processes."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_current_debug_apk as recipe


class OwnedProcessCleanup(unittest.TestCase):
    def test_timed_out_child_and_grandchild_are_gone(self):
        code = '''
import os, signal, subprocess, sys, time
child = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(120)'])
def stop(sig, frame):
    child.wait(timeout=4)
    sys.exit(0)
signal.signal(signal.SIGTERM, stop)
print(os.getpid(), child.pid, flush=True)
time.sleep(120)
'''
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(subprocess.TimeoutExpired):
                recipe.run(root, 'timeout', [sys.executable, '-c', code], dict(os.environ), timeout=1)
            evidence = json.loads((root / 'timeout.command.json').read_text())
            self.assertEqual('OWNED_GROUP_GONE', evidence['cleanup']['status'])
            self.assertTrue(evidence['cleanup']['leaderReaped'])
            self.assertFalse(evidence['automaticRetryAllowed'])
            leader, child = map(int, (root / 'timeout.stdout').read_text().split())
            self.assertEqual(leader, evidence['cleanup']['processGroup'])
            for pid in (leader, child):
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)

    def test_already_reaped_leader_never_signals_group(self):
        process = subprocess.Popen([sys.executable, '-c', 'pass'], start_new_session=True)
        process.wait(timeout=5)
        with mock.patch.object(recipe.os, 'killpg') as signal_group:
            result = recipe.stop_owned_group(process)
        signal_group.assert_not_called()
        self.assertEqual('CLEANUP_UNCONFIRMED', result['status'])
        self.assertFalse(result['ownershipEstablished'])

    def test_term_resistant_owned_leader_is_killed(self):
        process = subprocess.Popen(
            [sys.executable, '-c', 'import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("ready",flush=True);time.sleep(120)'],
            start_new_session=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        try:
            self.assertEqual(b'ready\n', process.stdout.readline())
            result = recipe.stop_owned_group(process, grace_seconds=0.2)
            self.assertEqual(['SIGTERM', 'SIGKILL'], result['signals'])
            self.assertEqual('OWNED_GROUP_GONE', result['status'])
            self.assertEqual(-9, process.returncode)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, 9)
                process.wait(timeout=5)
            process.stdout.close()
            process.stderr.close()


if __name__ == '__main__':
    unittest.main()
