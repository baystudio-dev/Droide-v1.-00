"""Contract tests for the proof launcher; never start a sandbox or a compiler."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "tools/run_fdroid_source_proofs.sh"
WORKFLOW = ROOT / ".github/workflows/fdroid-source-proofs.yml"


class ProofRunnerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="fdroid-runner-test-")
        self.addCleanup(self.temporary.cleanup)
        self.scratch = Path(self.temporary.name)
        self.jdk = self.scratch / "jdk with spaces"
        (self.jdk / "bin").mkdir(parents=True)
        for name in ("javac", "java"):
            tool = self.jdk / "bin" / name
            tool.write_text("not executable compiler code\n")
            tool.chmod(0o700)
        self.ndk = self.scratch / "ndk"
        self.ndk.mkdir()
        (self.ndk / "source.properties").write_text("Pkg.Revision = 28.2.13676358\n")
        self.archive = self.scratch / "vendor.tar.gz"
        self.archive.write_bytes(b"not a real archive")
        self.android = self.scratch / "android.jar"
        self.android.write_bytes(b"not a real jar")

    def invoke(self, *arguments, success=True):
        environment = {"PATH": "/usr/bin:/bin", "HOME": str(self.scratch),
                       "TMPDIR": str(self.scratch), "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"}
        result = subprocess.run(["/bin/bash", str(RUNNER), *map(str, arguments), "--show-command"],
                                cwd=ROOT, env=environment, capture_output=True, text=True, timeout=5,
                                check=False)
        if success:
            self.assertEqual(0, result.returncode, result.stderr)
            # The shell %q format uses ANSI-C quoting for the multiline fixed
            # diagnostic program. That string is inspected separately below;
            # shlex is enough for all resource/mount/target argument contracts.
            return result.stdout
        self.assertNotEqual(0, result.returncode)
        self.assertIn("SOURCE_PROOF_SANDBOX_FAILED", result.stderr)
        return result

    def args(self, output):
        return shlex.split(output)

    def test_local_network_share_is_only_after_an_empty_network_namespace(self):
        arguments = self.args(self.invoke("tests"))
        unshare = arguments.index("/usr/bin/unshare")
        bwrap = arguments.index("/usr/bin/bwrap")
        self.assertEqual(["--user", "--map-root-user", "--net", "--"], arguments[unshare + 1:bwrap])
        self.assertIn("--share-net", arguments[bwrap:])
        self.assertNotIn("/usr/bin/sudo", arguments)

    def test_sudo_only_creates_outer_network_then_returns_to_checkout_owner(self):
        arguments = self.args(self.invoke("tests", "--sudo"))
        self.assertEqual(["/usr/bin/sudo", "-n"], arguments[:2])
        self.assertIn("--unshare-all", arguments)
        unshare = arguments.index("/usr/bin/unshare")
        identity = arguments.index("/usr/bin/setpriv")
        bwrap = arguments.index("/usr/bin/bwrap")
        self.assertEqual(["--net", "--"], arguments[unshare + 1:identity])
        self.assertEqual(["--reuid", str(os.getuid()), "--regid", str(os.getgid()), "--clear-groups",
                          "--inh-caps=-all", "--ambient-caps=-all", "--bounding-set=-all",
                          "--no-new-privs"], arguments[identity + 1:bwrap])
        self.assertIn("--share-net", arguments[bwrap:])

    def test_sandbox_setup_does_not_widen_host_file_permissions_or_copy_secrets(self):
        source = RUNNER.read_text()
        self.assertIn("'--sudo requires an unprivileged caller'", source)
        self.assertNotIn("/usr/bin/chmod", source)
        self.assertNotIn("/usr/bin/chown", source)
        self.assertNotIn("/usr/bin/cp", source)
        self.assertNotIn("--preserve-env", source)
        self.assertNotIn("--preserve-groups", source)

    def test_limits_are_fixed_and_not_operator_relaxable(self):
        arguments = self.args(self.invoke("tests"))
        for expected in ("300s", "--kill-after=3s", "--as=6442450944", "--cpu=120",
                         "--nproc=128", "--nofile=256", "--fsize=67108864", "--core=0"):
            self.assertIn(expected, arguments)
        self.invoke("tests", "--timeout", "999", success=False)

    def test_privileges_dropped_before_fixed_target_command(self):
        arguments = self.args(self.invoke("tests", "--sudo"))
        for flag, value in (("--uid", "65534"), ("--gid", "65534"), ("--cap-drop", "ALL")):
            self.assertEqual(value, arguments[arguments.index(flag) + 1])
        self.assertIn("--disable-userns", arguments)
        self.assertIn("--die-with-parent", arguments)
        self.assertIn("--new-session", arguments)
        self.assertLess(arguments.index("--clearenv"), arguments.index("/bin/bash"))

    def test_only_scratch_is_writable_and_disk_is_bounded(self):
        arguments = self.args(self.invoke("tests"))
        scratch = arguments.index("/scratch")
        self.assertEqual(["--size", "536870912", "--perms", "1777", "--tmpfs"], arguments[scratch - 5:scratch])
        self.assertNotIn("--bind", arguments)
        self.assertNotIn("--bind-try", arguments)
        self.assertIn("/src", arguments)
        positions = [i for i, value in enumerate(arguments) if value == "--remount-ro"]
        mounts = {arguments[i + 1] for i in positions}
        self.assertTrue({"/", "/proc", "/dev"}.issubset(mounts))
        self.assertTrue(mounts.issubset({"/", "/proc", "/dev", "/src/.git"}))

    def test_private_home_and_temp_are_created_after_privilege_drop(self):
        arguments = self.args(self.invoke("tests", "--sudo"))
        program = arguments[arguments.index("/bin/bash") + 2]
        self.assertIn("umask 077", program)
        self.assertIn("/usr/bin/mkdir -m 700 -- /scratch/home /scratch/tmp /scratch/cache", program)
        self.assertLess(program.index("/usr/bin/mkdir"), program.index('"$@"'))

    def test_environment_is_allowlisted_and_scratch_local(self):
        arguments = self.args(self.invoke("tests"))
        variables = {arguments[i + 1]: arguments[i + 2] for i, value in enumerate(arguments) if value == "--setenv"}
        self.assertEqual({"PATH", "HOME", "TMPDIR", "XDG_CACHE_HOME", "LANG", "LC_ALL", "TZ",
                          "PYTHONDONTWRITEBYTECODE", "PROOF_DIAGNOSTICS"}, set(variables))
        self.assertEqual("/scratch/home", variables["HOME"])
        self.assertEqual("/scratch/tmp", variables["TMPDIR"])
        self.assertEqual("/scratch/cache", variables["XDG_CACHE_HOME"])
        self.assertNotIn("GITHUB_TOKEN", arguments)
        self.assertNotIn("HTTPS_PROXY", arguments)

    def test_tests_mode_runs_only_fdroid_checks_not_socket_tests(self):
        arguments = self.args(self.invoke("tests"))
        self.assertEqual(["/usr/bin/python3", "-B", "-m", "unittest", "discover", "-s",
                          "tools/tests", "-p", "test_fdroid_*source*.py", "-v"], arguments[-10:])
        self.assertNotIn("test_droide_cli_client.py", arguments)
        self.invoke("tests", "--ndk", self.ndk, success=False)

    def test_cli_uses_readonly_ndk_and_fresh_scratch_output(self):
        arguments = self.args(self.invoke("cli", "--ndk", self.ndk))
        self.assertIn(str(self.ndk), arguments)
        self.assertIn("/toolchain/ndk", arguments)
        self.assertEqual(["tools/fdroid_cli_source_build.py", "--ndk", "/toolchain/ndk",
                          "--output-dir", "/scratch/proof"], arguments[-5:])
        self.assertNotIn("--run-vector-test", arguments)

    def test_spake2_has_only_jdk_and_explicit_vector_test(self):
        arguments = self.args(self.invoke("spake2", "--jdk", self.jdk, "--jdk-archive", self.archive))
        self.assertIn(str(self.jdk), arguments)
        self.assertIn("--run-vector-test", arguments)
        self.assertNotIn("/toolchain/android.jar", arguments)
        self.assertNotIn("/toolchain/ndk", arguments)

    def test_termux_uses_only_jdk_android_tools_and_no_prebuilt_engine(self):
        arguments = self.args(self.invoke("termux", "--jdk", self.jdk, "--jdk-archive", self.archive,
                                          "--android-jar", self.android))
        self.assertIn("tools/fdroid_termux_source_build.py", arguments)
        self.assertIn("/toolchain/android.jar", arguments)
        self.assertNotIn("--reference-engine", arguments)
        self.assertNotIn("--ndk", arguments)

    def test_missing_duplicate_and_unrecognized_inputs_fail_closed(self):
        self.invoke("unknown", success=False)
        self.invoke("cli", success=False)
        self.invoke("tests", "--sudo", "--sudo", success=False)
        self.invoke("cli", "--ndk", self.ndk, "--ndk", self.ndk, success=False)
        self.invoke("spake2", "--jdk", self.jdk, success=False)
        self.invoke("tests", "--command", "true", success=False)

    def test_repository_mount_cannot_be_supplied_as_toolchain(self):
        self.invoke("cli", "--ndk", ROOT, success=False)
        self.invoke("cli", "--ndk", ROOT / "tools", success=False)

    def test_no_parent_directory_or_whole_root_tool_mount(self):
        self.invoke("cli", "--ndk", "/", success=False)
        self.invoke("cli", "--ndk", ROOT.parent, success=False)
        self.invoke("cli", "--ndk", "relative/ndk", success=False)

    def test_diagnostics_are_bounded_exact_paths_and_proof_status_is_preserved(self):
        source = RUNNER.read_text()
        self.assertIn("remaining = 16384", source)
        self.assertIn('stream.read(remaining)', source)
        self.assertIn('exit "$result"', source)
        self.assertNotIn("rglob", source)
        self.assertNotIn("shutil.copy", source)
        self.assertNotIn("|| true", source)

    def test_ci_is_readonly_pinned_and_does_not_publish_or_use_host_fallback(self):
        workflow = WORKFLOW.read_text()
        self.assertIn("contents: read", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("proof: [tests, cli, spake2, termux]", workflow)
        self.assertIn("jdk-17.0.20.1+1", workflow)
        self.assertIn("ndk;28.2.13676358", workflow)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("upload-artifact", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("secrets.", workflow)
        for line in workflow.splitlines():
            if "uses:" in line:
                self.assertRegex(line, r"uses: [\w/-]+@[0-9a-f]{40}(?:\s|$)")


if __name__ == "__main__":
    unittest.main()
