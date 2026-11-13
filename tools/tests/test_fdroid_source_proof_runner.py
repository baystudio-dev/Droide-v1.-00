"""Contract tests for the proof launcher; never start a sandbox or a compiler."""
from pathlib import Path
import os
import importlib.util
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
        self.loader_ndk = self.scratch / "loader ndk"
        self.loader_ndk.mkdir()
        (self.loader_ndk / "source.properties").write_text("Pkg.Revision = 29.0.14206865\n")
        self.archive = self.scratch / "vendor.tar.gz"
        self.archive.write_bytes(b"not a real archive")
        self.android = self.scratch / "android.jar"
        self.android.write_bytes(b"not a real jar")
        self.annotation = self.scratch / "annotation.jar"
        self.annotation.write_bytes(b"not a real annotation jar")

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

    def test_sudo_only_runs_reviewed_mapping_helper_with_checkout_owner(self):
        arguments = self.args(self.invoke("tests", "--sudo"))
        self.assertEqual(["/usr/bin/sudo", "-n"], arguments[:2])
        self.assertIn("--unshare-all", arguments)
        python = arguments.index("/usr/bin/python3")
        bwrap = arguments.index("/usr/bin/bwrap")
        self.assertEqual(["-I", "-B", "-S", str(ROOT / "tools/fdroid_source_namespace.py"),
                          "--uid", str(os.getuid()), "--gid", str(os.getgid()), "--"],
                         arguments[python + 1:bwrap])
        self.assertNotIn("/usr/bin/setpriv", arguments)
        self.assertNotIn("/usr/bin/unshare", arguments)
        self.assertIn("--share-net", arguments[bwrap:])

    def test_actual_sudo_command_satisfies_namespace_allowlist(self):
        specification = importlib.util.spec_from_file_location(
            "proof_namespace_validation", ROOT / "tools/fdroid_source_namespace.py")
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
        for options in (("tests",), ("cli", "--ndk", self.ndk),
                        ("loaders", "--ndk", self.ndk),
                        ("proot", "--ndk", self.ndk, "--loader-ndk", self.loader_ndk),
                        ("spake2", "--jdk", self.jdk, "--jdk-archive", self.archive),
                        ("termux", "--jdk", self.jdk, "--jdk-archive", self.archive,
                         "--android-jar", self.android),
                        ("view", "--jdk", self.jdk, "--jdk-archive", self.archive,
                         "--android-jar", self.android, "--annotation-jar", self.annotation)):
            with self.subTest(mode=options[0]):
                arguments = self.args(self.invoke(*options, "--sudo"))
                metadata = ROOT / ".git"
                kind = "directory" if metadata.is_dir() else "file" if metadata.exists() else None
                module.validate_bwrap(arguments[arguments.index("/usr/bin/bwrap"):], str(ROOT), kind)

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
        arguments = arguments[arguments.index("/usr/bin/bwrap"):]
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

    def test_fixed_final_probe_must_pass_before_any_target_is_started(self):
        arguments = self.args(self.invoke("tests"))
        program = arguments[arguments.index("/bin/bash") + 2]
        probe = "/usr/bin/python3 -I -B -S /src/tools/fdroid_source_sandbox_probe.py"
        self.assertTrue(program.startswith("set -e;"))
        self.assertLess(program.index("/usr/bin/mkdir"), program.index(probe))
        self.assertLess(program.index(probe), program.index("set +e"))
        self.assertLess(program.index(probe), program.index('"$@"'))

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

    def test_loaders_reuses_the_exact_cli_isolation_and_readonly_ndk_mount(self):
        cli = self.args(self.invoke("cli", "--ndk", self.ndk))
        loaders = self.args(self.invoke("loaders", "--ndk", self.ndk))
        self.assertEqual(cli[:-5], loaders[:-5])
        self.assertEqual(["tools/fdroid_proot_loader_source_build.py", "--ndk",
                          "/toolchain/ndk", "--output-dir", "/scratch/proof"], loaders[-5:])
        self.assertNotIn("/toolchain/jdk", loaders)
        self.assertNotIn("/toolchain/android.jar", loaders)
        self.assertNotIn("/toolchain/annotation.jar", loaders)

    def test_loaders_rejects_missing_extra_and_protected_toolchain_inputs(self):
        self.invoke("loaders", success=False)
        self.invoke("loaders", "--ndk", self.ndk, "--ndk", self.ndk, success=False)
        for option, value in (("--jdk", self.jdk), ("--jdk-archive", self.archive),
                              ("--android-jar", self.android), ("--annotation-jar", self.annotation)):
            with self.subTest(option=option):
                self.invoke("loaders", "--ndk", self.ndk, option, value, success=False)
        for value in (ROOT, ROOT.parent, ROOT / "tools", "/", "relative/ndk"):
            with self.subTest(value=value):
                self.invoke("loaders", "--ndk", value, success=False)

    def test_proot_uses_two_fixed_readonly_ndks_without_changing_isolation(self):
        arguments = self.args(self.invoke("proot", "--ndk", self.ndk, "--loader-ndk", self.loader_ndk))
        for source, target in ((self.ndk, "/toolchain/ndk"), (self.loader_ndk, "/toolchain/loader-ndk")):
            at = arguments.index(str(source))
            self.assertEqual(["--ro-bind", str(source), target], arguments[at - 1:at + 2])
        self.assertEqual(["tools/fdroid_proot_engine_source_build.py", "--ndk", "/toolchain/ndk",
                          "--loader-ndk", "/toolchain/loader-ndk", "--output-dir", "/scratch/proof"], arguments[-7:])
        loader_at = arguments.index(str(self.loader_ndk)) - 1
        del arguments[loader_at:loader_at + 3]
        cli = self.args(self.invoke("cli", "--ndk", self.ndk))
        self.assertEqual(cli[:-5], arguments[:-7])

    def test_proot_requires_both_toolchains_and_rejects_other_mode_inputs(self):
        options = ("proot", "--ndk", self.ndk, "--loader-ndk", self.loader_ndk)
        self.invoke("proot", success=False)
        self.invoke("proot", "--ndk", self.ndk, success=False)
        self.invoke("proot", "--loader-ndk", self.loader_ndk, success=False)
        self.invoke(*options, "--loader-ndk", self.loader_ndk, success=False)
        self.invoke(*options, "--ndk", self.ndk, success=False)
        for option, value in (("--jdk", self.jdk), ("--jdk-archive", self.archive),
                              ("--android-jar", self.android), ("--annotation-jar", self.annotation)):
            with self.subTest(option=option):
                self.invoke(*options, option, value, success=False)
        for mode in ("tests", "cli", "loaders", "spake2", "termux", "view"):
            with self.subTest(mode=mode):
                self.invoke(mode, "--loader-ndk", self.loader_ndk, success=False)

    def test_proot_rejects_protected_overlapping_and_invalid_toolchains(self):
        for flag in ("--ndk", "--loader-ndk"):
            other_flag = "--loader-ndk" if flag == "--ndk" else "--ndk"
            other_path = self.loader_ndk if flag == "--ndk" else self.ndk
            for value in (ROOT, ROOT.parent, ROOT / "tools", "/", "relative/ndk", self.archive):
                with self.subTest(flag=flag, value=value):
                    self.invoke("proot", flag, value, other_flag, other_path, success=False)
        nested = self.ndk / "nested"
        nested.mkdir()
        (nested / "source.properties").write_text("Pkg.Revision = 29.0.14206865\n")
        alias = self.scratch / "ndk alias"
        alias.symlink_to(self.ndk, target_is_directory=True)
        for first, second in ((self.ndk, self.ndk), (self.ndk, alias),
                              (self.ndk, nested), (nested, self.ndk)):
            with self.subTest(first=first, second=second):
                self.invoke("proot", "--ndk", first, "--loader-ndk", second, success=False)
        missing = self.scratch / "unprovisioned"
        missing.mkdir()
        self.invoke("proot", "--ndk", self.ndk, "--loader-ndk", missing, success=False)

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

    def test_view_uses_only_fixed_readonly_tools_and_no_binary_termux_classpath(self):
        arguments = self.args(self.invoke("view", "--jdk", self.jdk, "--jdk-archive", self.archive,
                                          "--android-jar", self.android, "--annotation-jar", self.annotation))
        self.assertIn("tools/fdroid_termux_view_source_build.py", arguments)
        at = arguments.index(str(self.annotation))
        self.assertEqual(["--ro-bind", str(self.annotation), "/toolchain/annotation.jar"], arguments[at - 1:at + 2])
        self.assertEqual(["--annotation-jar", "/toolchain/annotation.jar", "--output", "/scratch/proof"], arguments[-4:])
        self.assertNotIn("--reference-engine", arguments)
        self.assertNotIn("--ndk", arguments)
        self.assertFalse(any("kotlin" in arg or "app/libs" in arg for arg in arguments))

    def test_annotation_is_required_only_for_view_and_cannot_be_repeated(self):
        options = ("--jdk", self.jdk, "--jdk-archive", self.archive, "--android-jar", self.android)
        self.invoke("view", *options, success=False)
        self.invoke("view", *options, "--annotation-jar", self.annotation, "--annotation-jar", self.annotation, success=False)
        for mode in ("tests", "cli", "spake2", "termux"):
            with self.subTest(mode=mode):
                self.invoke(mode, "--annotation-jar", self.annotation, success=False)

    def test_annotation_cannot_mount_repository_or_directory(self):
        options = ("view", "--jdk", self.jdk, "--jdk-archive", self.archive, "--android-jar", self.android)
        self.invoke(*options, "--annotation-jar", ROOT / "tools/java_archive.py", success=False)
        self.invoke(*options, "--annotation-jar", self.jdk, success=False)

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
        self.assertIn("proof: [tests, cli, loaders, proot, spake2, termux, view]", workflow)
        self.assertIn("needs: sandbox-readiness", workflow)
        self.assertIn("Require isolated source contracts before provisioning compilers", workflow)
        self.assertIn("Official registry byte parity (data only)", workflow)
        self.assertIn("--signal=TERM --kill-after=5s 540s", workflow)
        self.assertIn("tools/fdroid_maven_registry_parity.py", workflow)
        self.assertIn("jdk-17.0.20.1+1", workflow)
        self.assertIn("ndk;28.2.13676358", workflow)
        self.assertIn("ndk;29.0.14206865", workflow)
        self.assertNotIn("pull_request_target", workflow)
        self.assertNotIn("upload-artifact", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertNotIn("secrets.", workflow)
        for line in workflow.splitlines():
            if "uses:" in line:
                self.assertRegex(line, r"uses: [\w/-]+@[0-9a-f]{40}(?:\s|$)")

    def test_r8_registry_job_is_separate_supervised_data_only_after_sandbox_readiness(self):
        workflow = WORKFLOW.read_text()
        start = workflow.index("  r8-registry-parity:")
        end = workflow.index("  sandbox-readiness:", start)
        job = workflow[start:end]
        self.assertIn("needs: sandbox-readiness", job)
        self.assertIn("timeout-minutes: 4", job)
        self.assertIn("persist-credentials: false", job)
        self.assertIn("--signal=TERM --kill-after=5s 180s", job)
        self.assertIn("/usr/bin/python3 -I -B tools/fdroid_r8_registry_parity.py", job)
        self.assertIn("--download-and-compare", job)
        self.assertIn('"$RUNNER_TEMP/droide-r8-registry-parity-proof"', job)
        for forbidden in ("upload-artifact", "continue-on-error", "secrets.", "sdkmanager", "./gradlew", "java -jar"):
            self.assertNotIn(forbidden, job)

    def test_loader_toolchain_provisioning_is_scoped_and_separate_from_cli(self):
        workflow = WORKFLOW.read_text()
        start = workflow.index("- name: Provision the historical loader NDK and recipe utility")
        end = workflow.index("- name: Provision engine patch and source-wrapper utilities", start)
        step = workflow[start:end]
        self.assertIn("if: matrix.proof == 'loaders' || matrix.proof == 'proot'", step)
        self.assertIn("sdkmanager --install 'ndk;29.0.14206865'", step)
        self.assertIn("sudo apt-get install --no-install-recommends --yes gawk", step)
        self.assertNotIn("elf-cleaner", step)
        self.assertNotIn("28.2.13676358", step)
        self.assertIn('bash tools/run_fdroid_source_proofs.sh loaders --sudo --ndk "$ANDROID_HOME/ndk/29.0.14206865"', workflow[end:])
        self.assertIn('bash tools/run_fdroid_source_proofs.sh cli --sudo --ndk "$ANDROID_HOME/ndk/28.2.13676358"', workflow)
        self.assertEqual(1, workflow.count("sdkmanager --install 'ndk;29.0.14206865'"))
        self.assertEqual(1, workflow.count("sdkmanager --install 'ndk;28.2.13676358'"))

    def test_engine_provisioning_retains_both_toolchain_revisions_and_fixed_offline_target(self):
        workflow = WORKFLOW.read_text()
        start = workflow.index("- name: Provision the existing pinned NDK")
        end = workflow.index("- name: Provision the historical loader NDK", start)
        self.assertIn("if: matrix.proof == 'cli' || matrix.proof == 'proot'", workflow[start:end])
        self.assertIn("sdkmanager --install 'ndk;28.2.13676358'", workflow[start:end])
        start = workflow.index("- name: Provision engine patch and source-wrapper utilities")
        end = workflow.index("- name: Provision API 36", start)
        self.assertIn("if: matrix.proof == 'proot'", workflow[start:end])
        self.assertIn("sudo apt-get install --no-install-recommends --yes patch xxd", workflow[start:end])
        start = workflow.index("- name: Rebuild patched PRoot with both source-built loaders twice offline")
        end = workflow.index("- name: Compile SPAKE2", start)
        step = workflow[start:end]
        self.assertIn("if: matrix.proof == 'proot'", step)
        self.assertIn("bash tools/run_fdroid_source_proofs.sh proot --sudo", step)
        self.assertIn('--ndk "$ANDROID_HOME/ndk/28.2.13676358"', step)
        self.assertIn('--loader-ndk "$ANDROID_HOME/ndk/29.0.14206865"', step)
        self.assertNotIn("sdkmanager", step)
        self.assertNotIn("apt-get", step)

    def test_view_provisioning_is_one_pinned_bounded_download_before_offline_compile(self):
        workflow = WORKFLOW.read_text()
        start = workflow.index("- name: Provision only the existing pinned annotation compiler dependency")
        end = workflow.index("- name: Run source proof contract tests offline", start)
        step = workflow[start:end]
        self.assertIn("if: matrix.proof == 'view'", step)
        self.assertIn("--signal=TERM --kill-after=5s 90s", step)
        self.assertIn('test ! -e "$annotation_input"', step)
        self.assertIn('test ! -L "$annotation_input"', step)
        self.assertIn("/usr/bin/curl --disable --fail --silent --show-error --proto '=https'", step)
        self.assertIn("--max-time 60 --max-filesize 1048576", step)
        self.assertIn("https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.10.0/annotation-jvm-1.10.0.jar", step)
        self.assertIn("ddd072ddbb553178e205517ce777b2f05aa9e412982d9ecb4eedc74f1212f697", step)
        self.assertIn("/usr/bin/sha256sum --check --strict -", step)
        for forbidden in ("--location", "--insecure", "kotlin-stdlib", "--netrc", "--user", "Authorization"):
            self.assertNotIn(forbidden, step)
        self.assertIn("bash tools/run_fdroid_source_proofs.sh view --sudo", workflow[end:])


if __name__ == "__main__":
    unittest.main()
