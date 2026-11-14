"""Synthetic supervisor tests; never load or execute an application/toolchain."""

import importlib.util
import hashlib
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import current_owned_command as supervisor
MODULE_TOOLS=Path(__file__).resolve().parents[1]


@unittest.skipUnless(os.name == "posix", "Supervisor process groups require POSIX")
class SyntheticSupervisorTests(unittest.TestCase):
    def run_python(self, code, *, deadline_seconds=5, grace_seconds=0.1, on_started=None):
        """Keep every subprocess and log confined to a fresh temporary directory."""
        with tempfile.TemporaryDirectory(prefix="supervisor-test-") as directory:
            root = Path(directory)
            environment = {"PATH": os.defpath, "SUPERVISOR_TEST_MARKER": "synthetic"}
            with (root / "stdout.log").open("xb") as stdout:
                with (root / "stderr.log").open("xb") as stderr:
                    result = supervisor.supervise(
                        [sys.executable, "-I", "-c", code],
                        cwd=directory,
                        env=environment,
                        stdout_fd=stdout.fileno(),
                        stderr_fd=stderr.fileno(),
                        deadline_seconds=deadline_seconds,
                        grace_seconds=grace_seconds,
                        on_started=on_started,
                    )
            return result, (root / "stdout.log").read_text(), (root / "stderr.log").read_text(), directory

    def assert_process_finished(self, result):
        self.assertIsInstance(result["pid"], int)
        self.assertEqual(result["pgid"], result["pid"])
        self.assertGreaterEqual(result["elapsedSeconds"], 0)
        self.assertIn("cleanup", result)
        with self.assertRaises(ProcessLookupError):
            os.kill(result["pid"], 0)

    def test_success_captures_logs_and_uses_requested_cwd_and_environment(self):
        result, stdout, stderr, directory = self.run_python(
            "import os,sys; "
            "print('hello stdout'); "
            "print(os.getcwd()); "
            "print(os.environ['SUPERVISOR_TEST_MARKER']); "
            "print('hello stderr', file=sys.stderr)"
        )
        self.assertEqual(result["outcome"], "SUCCESS")
        self.assertEqual(result["returncode"], 0)
        self.assertEqual(stdout.splitlines(), ["hello stdout", directory, "synthetic"])
        self.assertEqual(stderr, "hello stderr\n")
        self.assert_process_finished(result)

    def test_interruption_callback_cleans_child_and_restores_signal_handlers(self):
        previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}

        def interrupt(_):
            raise supervisor.SupervisorInterrupted("synthetic interruption")

        result, _, _, _ = self.run_python("import time; time.sleep(30)", on_started=interrupt)
        self.assertEqual(result["outcome"], "INTERRUPTED")
        self.assertEqual(result["error"], "synthetic interruption")
        self.assertTrue(result["cleanup"]["leaderReaped"])
        self.assertEqual(result["cleanup"]["scope"], "OWNED_NEW_SESSION_PROCESS_GROUP_ONLY")
        self.assert_process_finished(result)
        for sig, handler in previous.items():
            self.assertEqual(signal.getsignal(sig), handler)

    def test_nonzero_preserves_exit_status_and_logs(self):
        result, stdout, stderr, _ = self.run_python(
            "import sys; print('before exit'); "
            "print('failure detail', file=sys.stderr); sys.exit(7)"
        )
        self.assertEqual(result["outcome"], "NONZERO_EXIT")
        self.assertEqual(result["returncode"], 7)
        self.assertEqual(stdout, "before exit\n")
        self.assertEqual(stderr, "failure detail\n")
        self.assert_process_finished(result)

    def test_short_deadline_terminates_and_reaps_sleeping_child(self):
        result, _, _, _ = self.run_python(
            "import time; time.sleep(30)", deadline_seconds=0.15, grace_seconds=0.1
        )
        self.assertEqual(result["outcome"], "TIMEOUT")
        self.assertIsNotNone(result["returncode"])
        self.assertNotEqual(result["returncode"], 0)
        self.assertLess(result["elapsedSeconds"], 10)
        self.assert_process_finished(result)

    def assert_leader_exit_drains_descendant(self, exit_status):
        # The pipe handshake proves the descendant started before its leader
        # exits. It inherits the owned session/group; no detached session is used.
        descendant_code = "import os,time; print(os.getpid(), flush=True); time.sleep(30)"
        code = (
            "import subprocess,sys; "
            f"child=subprocess.Popen([sys.executable,'-I','-c',{descendant_code!r}], "
            "stdout=subprocess.PIPE,text=True); "
            "print(child.stdout.readline().strip(),flush=True); "
            f"sys.exit({exit_status})"
        )
        result, stdout, stderr, _ = self.run_python(code)
        self.assertEqual(result["returncode"], exit_status)
        self.assertEqual(result["outcome"], "SUCCESS" if exit_status == 0 else "NONZERO_EXIT")
        self.assertEqual(stderr, "")
        self.assertEqual(result["observedExit"]["status"], exit_status)
        self.assertTrue(result["cleanup"]["leaderReaped"])
        self.assertIn("SIGTERM", result["cleanup"]["signals"])
        self.assert_process_finished(result)
        descendant_pid = int(stdout.strip())
        self.assertNotEqual(descendant_pid, result["pid"])
        stat_path = Path(f"/proc/{descendant_pid}/stat")
        deadline = time.monotonic() + 1
        while True:
            try:
                with stat_path.open() as stream:
                    # Only inspect this synthetic descendant, with bounded read.
                    state = stream.read(4096).rsplit(")", 1)[1].split()[0]
            except FileNotFoundError:
                return
            if state in ("Z", "X"):
                return  # An orphan zombie belongs to its adopter, not this test.
            if time.monotonic() >= deadline:
                self.fail(f"Synthetic descendant {descendant_pid} still active: {state}")
            time.sleep(0.01)

    @unittest.skipUnless(sys.platform.startswith("linux"), "Uses bounded known-PID /proc check")
    def test_successful_leader_drains_sleeping_descendant(self):
        self.assert_leader_exit_drains_descendant(0)

    @unittest.skipUnless(sys.platform.startswith("linux"), "Uses bounded known-PID /proc check")
    def test_nonzero_leader_drains_sleeping_descendant(self):
        self.assert_leader_exit_drains_descendant(7)



    def test_nested_interrupt_records_inner_cleanup_before_outer_reap(self):
        import json
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); marker=root/'started'; receipt=root/'inner.json'
            script=root/'inner.py'
            script.write_text("import sys,json; from pathlib import Path; sys.path.insert(0,"+repr(str(MODULE_TOOLS))+ "); import current_owned_command as s\n"+
                "p=Path("+repr(str(root))+ ")\nwith (p/'out').open('xb') as o,(p/'err').open('xb') as e:\n r=s.supervise([sys.executable,'-c','import time;time.sleep(30)'],p,{},o.fileno(),e.fileno(),30,0.05,on_started=lambda _: (p/'started').write_text('yes'))\n(p/'inner.json').write_text(json.dumps(r))\n")
            def interrupt_after_inner(_):
                deadline=time.monotonic()+5
                while not marker.exists():
                    if time.monotonic()>deadline:raise RuntimeError('inner start missing')
                    time.sleep(0.01)
                raise supervisor.SupervisorInterrupted('nested test')
            with (root/'outer.out').open('xb') as out,(root/'outer.err').open('xb') as err:
                result=supervisor.supervise([sys.executable,'-B',str(script)],root,{},out.fileno(),err.fileno(),15,2,on_started=interrupt_after_inner)
            inner=json.loads(receipt.read_text())
            self.assertEqual(result['outcome'],'INTERRUPTED')
            self.assertEqual(inner['outcome'],'INTERRUPTED')
            self.assertTrue(inner['cleanup']['leaderReaped'])
            with self.assertRaises(ProcessLookupError):os.kill(inner['pid'],0)

if __name__ == "__main__": unittest.main()
