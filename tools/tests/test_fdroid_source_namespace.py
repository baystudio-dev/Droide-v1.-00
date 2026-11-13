"""Pure contract tests and mocked handoffs: never create a namespace or exec."""

import argparse
import errno
import importlib.util
import os
from pathlib import Path
import signal
import unittest
from unittest.mock import Mock, call, patch


SPEC = importlib.util.spec_from_file_location("fdroid_source_namespace", Path(__file__).resolve().parents[1] / "fdroid_source_namespace.py")
assert SPEC is not None and SPEC.loader is not None
ns = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ns)
SOURCE = "/workspace/public-checkout"


def argv(git_kind="directory"):
    result = [
        ns.BWRAP, "--unshare-all", "--unshare-user", "--disable-userns", "--share-net",
        "--die-with-parent", "--new-session", "--uid", "65534", "--gid", "65534",
        "--cap-drop", "ALL", "--clearenv", "--ro-bind", "/usr", "/usr",
        "--symlink", "usr/bin", "/bin", "--symlink", "usr/sbin", "/sbin",
        "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
        "--dir", "/etc", "--ro-bind", "/etc/alternatives", "/etc/alternatives",
        "--ro-bind", "/etc/ld.so.cache", "/etc/ld.so.cache", "--ro-bind", SOURCE, "/src",
    ]
    if git_kind == "directory":
        result += ["--size", "4096", "--tmpfs", "/src/.git", "--remount-ro", "/src/.git"]
    elif git_kind == "file":
        result += ["--ro-bind", "/dev/null", "/src/.git"]
    result += [
        "--dir", "/toolchain", "--proc", "/proc", "--remount-ro", "/proc",
        "--dev", "/dev", "--remount-ro", "/dev", "--size", "536870912",
        "--perms", "1777", "--tmpfs", "/scratch", "--remount-ro", "/",
    ]
    for key, value in {
        **ns.CLEAN_ENV, "HOME": "/scratch/home", "TMPDIR": "/scratch/tmp",
        "XDG_CACHE_HOME": "/scratch/cache", "PYTHONDONTWRITEBYTECODE": "1",
        "PROOF_DIAGNOSTICS": "print('fixed diagnostic')",
    }.items():
        result += ["--setenv", key, value]
    return result + ["--chdir", "/src", "/usr/bin/true"]


def native_argv(git_kind="directory"):
    result = argv(git_kind)
    at = result.index("--proc")
    result[at:at] = [
        "--size", "4096", "--tmpfs", ns.NATIVE_MASK, "--remount-ro", ns.NATIVE_MASK,
        "--ro-bind", "/opt/native-ndk28", "/toolchain/ndk",
        "--ro-bind", "/opt/native-ndk29", "/toolchain/loader-ndk",
    ]
    return result[:-1] + list(ns.NATIVE_COMMAND)


class ValidationTests(unittest.TestCase):
    def test_original_layout_and_fixed_smoke_are_allowed(self):
        for kind in ("directory", "file", None):
            with self.subTest(kind=kind):
                ns.validate_bwrap(argv(kind), SOURCE, kind)

    def test_proof_shell_and_python_entry_points_are_inside_sandbox(self):
        for target in (["/bin/bash", "-c", '"$@"', "proof-runner", "/usr/bin/python3", "-B", "tools/proof.py"], ["/usr/bin/python3", "-I", "-B", "-c", "pass"]):
            ns.validate_bwrap(argv()[:-1] + target, SOURCE, "directory")

    def test_external_declared_toolchain_is_read_only(self):
        for source, target in (("/opt/public-ndk", "/toolchain/ndk"),
                               ("/opt/public-annotation.jar", "/toolchain/annotation.jar")):
            with self.subTest(target=target):
                command = argv()
                at = command.index("--proc")
                command[at:at] = ["--ro-bind", source, target]
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_separate_engine_and_loader_ndk_readonly_mounts_are_allowed(self):
        command = argv()
        at = command.index("--proc")
        command[at:at] = ["--ro-bind", "/opt/ndk28", "/toolchain/ndk",
                         "--ro-bind", "/opt/ndk29", "/toolchain/loader-ndk"]
        ns.validate_bwrap(command, SOURCE, "directory")

    def test_loader_toolchain_cannot_overlap_or_override_mount_contract(self):
        for mount in (["--bind", "/opt/ndk29", "/toolchain/loader-ndk"],
                      ["--ro-bind", SOURCE, "/toolchain/loader-ndk"],
                      ["--ro-bind", SOURCE + "/tools", "/toolchain/loader-ndk"],
                      ["--ro-bind", "/workspace", "/toolchain/loader-ndk"],
                      ["--ro-bind", "/opt/ndk28", "/toolchain/loader-ndk"],
                      ["--ro-bind", "/opt/ndk28/nested", "/toolchain/loader-ndk"],
                      ["--ro-bind", "/opt", "/toolchain/loader-ndk"],
                      ["--ro-bind", "/opt/ndk29", "/toolchain/other-ndk"],
                      ["--ro-bind", "/opt/ndk29", "/toolchain/loader-ndk",
                       "--ro-bind", "/opt/ndk29", "/toolchain/loader-ndk"]):
            command = argv()
            at = command.index("--proc")
            command[at:at] = ["--ro-bind", "/opt/ndk28", "/toolchain/ndk", *mount]
            with self.subTest(mount=mount), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")
        command = argv()
        at = command.index("--proc")
        command[at:at] = ["--ro-bind", "/opt/ndk29", "/toolchain/loader-ndk"]
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_host_ids_are_strict_nonzero_numbers(self):
        self.assertEqual(ns.host_id("1001"), 1001)
        self.assertEqual(ns.host_id("4294967294"), 4294967294)
        for value in ("0", "-1", "+1", "01", "1 ", " 1", "1\n", "4294967295", "x", "١", "", "1" * 5000):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                ns.host_id(value)

    def test_rejects_missing_or_duplicate_isolation_guards(self):
        for flag in ("--unshare-all", "--unshare-user", "--disable-userns", "--share-net", "--die-with-parent", "--new-session", "--clearenv"):
            with self.subTest(flag=flag):
                command = argv()
                command.remove(flag)
                with self.assertRaises(ns.SetupError):
                    ns.validate_bwrap(command, SOURCE, "directory")
                with self.assertRaises(ns.SetupError):
                    ns.validate_bwrap(argv()[:1] + [flag] + argv()[1:], SOURCE, "directory")

    def test_network_flag_order_cannot_override_outer_isolation(self):
        command = argv()
        command.remove("--share-net")
        command.insert(1, "--share-net")
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_root_target_or_capability_addition_is_rejected(self):
        for flag, bad in (("--uid", "0"), ("--gid", "0"), ("--cap-drop", "CAP_SYS_ADMIN")):
            command = argv()
            command[command.index(flag) + 1] = bad
            with self.subTest(flag=flag), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")
        for options in (["--uid", "0"], ["--cap-add", "ALL"], ["--share-user"], ["--userns", "5"], ["--bind", "/", "/"]):
            with self.subTest(options=options), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(argv()[:-1] + options + argv()[-1:], SOURCE, "directory")

    def test_no_alternate_setup_binary(self):
        for binary in ("bwrap", "/bin/bwrap", "/usr/bin/python3", "/bin/sh"):
            with self.subTest(binary=binary), self.assertRaises(ns.SetupError):
                ns.validate_bwrap([binary] + argv()[1:], SOURCE, "directory")

    def test_undeclared_host_mounts_and_tool_ancestors_rejected(self):
        for mount in (["--ro-bind", "/home", "/home"], ["--ro-bind", "/", "/toolchain/ndk"], ["--ro-bind", "/workspace", "/toolchain/ndk"], ["--ro-bind", SOURCE + "/private", "/toolchain/ndk"]):
            command = argv()
            at = command.index("--proc")
            command[at:at] = mount
            with self.subTest(mount=mount), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_no_changed_system_or_source_bind(self):
        command = argv()
        command[command.index(SOURCE)] = "/some/other/source"
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")
        command = argv()
        command[command.index("usr/bin")] = "../../host/bin"
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_scratch_bounds_and_readonly_root_are_required(self):
        command = argv()
        command[command.index("536870912")] = "9999999999"
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")
        command = argv()
        at = command.index("536870912") - 1
        del command[at:at + 2]
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")
        command = argv()
        at = command.index("/", command.index("--tmpfs") + 1) - 1
        del command[at:at + 2]
        command[1:1] = ["--remount-ro", "/"]
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_tmpfs_modifiers_cannot_be_consumed_by_other_operations(self):
        command = argv()
        at = command.index("--perms") + 2
        command[at:at] = ["--dir", "/anything"]
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_git_metadata_must_be_masked(self):
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(argv(None), SOURCE, "directory")
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(argv(None), SOURCE, "file")

    def test_source_mount_cannot_cover_an_earlier_git_mask(self):
        for kind in ("directory", "file"):
            command = argv(kind)
            source = command.index(SOURCE) - 1
            source_mount = command[source:source + 3]
            del command[source:source + 3]
            position = command.index("--proc")
            command[position:position] = source_mount
            with self.subTest(kind=kind), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, kind)

    def test_environment_not_inherited_or_overridden(self):
        for addition in (["--setenv", "TOKEN", "private"], ["--setenv", "LD_PRELOAD", "/src/x.so"], ["--setenv", "HOME", "/root"]):
            with self.subTest(addition=addition), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(argv()[:-1] + addition + argv()[-1:], SOURCE, "directory")

    def test_argument_size_budget_and_nul_rejected(self):
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(argv() + ["x" * 131072], SOURCE, "directory")
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(argv() + ["\x00"], SOURCE, "directory")


class NativeMaskValidationTests(unittest.TestCase):
    def test_native_exact_target_allows_only_fixed_readonly_empty_mask(self):
        self.assertEqual("/src/app/src/main/jniLibs/arm64-v8a", ns.NATIVE_MASK)
        self.assertEqual(("tools/fdroid_native_source_build.py", "--ndk", "/toolchain/ndk",
                          "--loader-ndk", "/toolchain/loader-ndk", "--output-dir", "/scratch/proof"),
                         ns.NATIVE_COMMAND[-7:])
        for kind in ("directory", "file", None):
            with self.subTest(git=kind):
                ns.validate_bwrap(native_argv(kind), SOURCE, kind)

    def test_native_target_rejects_missing_mask(self):
        command = native_argv()
        at = command.index(ns.NATIVE_MASK) - 3
        del command[at:at + 6]
        with self.assertRaisesRegex(ns.SetupError, "masked after the source mount"):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_mask_cannot_be_writable_or_remounted_before_creation(self):
        for earlier in (False, True):
            command = native_argv()
            mount = command.index(ns.NATIVE_MASK) - 3
            remount = command[mount + 4:mount + 6]
            del command[mount + 4:mount + 6]
            if earlier:
                command[mount:mount] = remount
            with self.subTest(earlier=earlier), self.assertRaisesRegex(ns.SetupError, "explicitly read-only"):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_mask_cannot_precede_and_be_hidden_by_source_mount(self):
        command = native_argv()
        at = command.index(ns.NATIVE_MASK) - 3
        mask = command[at:at + 6]
        del command[at:at + 6]
        source = command.index(SOURCE) - 1
        command[source:source] = mask
        with self.assertRaisesRegex(ns.SetupError, "masked after the source mount"):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_mask_rejects_changed_size_permissions_or_duplicate_mount(self):
        mutations = ("size", "missing-size", "permissions", "duplicate")
        for mutation in mutations:
            command = native_argv()
            at = command.index(ns.NATIVE_MASK) - 3
            if mutation == "size":
                command[at + 1] = "536870912"
            elif mutation == "missing-size":
                del command[at:at + 2]
            elif mutation == "permissions":
                command[at + 2:at + 2] = ["--perms", "1777"]
            else:
                command[at:at] = command[at:at + 6]
            with self.subTest(mutation=mutation), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_mask_has_no_alternate_or_child_mount_destinations(self):
        for alternate in ("/src/app/src/main/jniLibs", "/src/app/src/main/jniLibs/armeabi-v7a",
                          ns.NATIVE_MASK + "/libdroide_cli.so", "/src/other"):
            command = [alternate if value == ns.NATIVE_MASK else value for value in native_argv()]
            with self.subTest(alternate=alternate), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")
        for mount in (["--ro-bind", "/opt/retained", ns.NATIVE_MASK],
                      ["--ro-bind", "/opt/retained/file.so", ns.NATIVE_MASK + "/libdroide_cli.so"],
                      ["--bind", "/opt/retained", ns.NATIVE_MASK],
                      ["--dir", ns.NATIVE_MASK + "/new"]):
            command = native_argv()
            at = command.index("--proc")
            command[at:at] = mount
            with self.subTest(mount=mount), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_mask_is_rejected_for_every_other_fixed_proof_target(self):
        for script in ("fdroid_cli_source_build.py", "fdroid_proot_loader_source_build.py",
                       "fdroid_proot_engine_source_build.py", "fdroid_spake2_source_build.py",
                       "fdroid_termux_source_build.py", "fdroid_termux_view_source_build.py"):
            command = native_argv()
            command[command.index(ns.NATIVE_SCRIPT)] = "tools/" + script
            with self.subTest(script=script), self.assertRaisesRegex(ns.SetupError, "exact fixed source-only target"):
                ns.validate_bwrap(command, SOURCE, "directory")
        command = native_argv()
        at = command.index("/bin/bash")
        for target in (["/usr/bin/true"], ["/usr/bin/python3", "-m", "unittest"]):
            with self.subTest(target=target), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command[:at] + target, SOURCE, "directory")

    def test_native_command_requires_exact_probe_wrapper_paths_and_arguments(self):
        mutations = ("shell", "entry", "absolute-script", "output", "ndk", "extra", "direct")
        for mutation in mutations:
            command = native_argv()
            at = command.index("/bin/bash")
            if mutation == "shell":
                command[at + 2] = '"$@"'
            elif mutation == "entry":
                command[at] = "/bin/sh"
            elif mutation == "absolute-script":
                command[command.index(ns.NATIVE_SCRIPT)] = "/src/" + ns.NATIVE_SCRIPT
            elif mutation == "output":
                command[-1] = "/scratch/elsewhere"
            elif mutation == "ndk":
                target_ndk = command.index("--ndk", at)
                command[target_ndk + 1] = "/toolchain/loader-ndk"
            elif mutation == "extra":
                command += ["--reference", "/src/retained.so"]
            else:
                command = command[:at] + ["/usr/bin/python3", "-B", ns.NATIVE_SCRIPT, *ns.NATIVE_COMMAND[-6:]]
            with self.subTest(mutation=mutation), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_target_cannot_avoid_required_mask_using_an_alternate_script_path(self):
        command = native_argv()
        at = command.index(ns.NATIVE_MASK) - 3
        del command[at:at + 6]
        command[command.index(ns.NATIVE_SCRIPT)] = "/src/" + ns.NATIVE_SCRIPT
        with self.assertRaises(ns.SetupError):
            ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_target_requires_both_readonly_ndks_and_no_other_tool_inputs(self):
        for missing in ("/toolchain/ndk", "/toolchain/loader-ndk"):
            command = native_argv()
            at = command.index(missing) - 2
            del command[at:at + 3]
            with self.subTest(missing=missing), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")
        for target in ("/toolchain/jdk", "/toolchain/jdk.tar.gz", "/toolchain/android.jar", "/toolchain/annotation.jar"):
            command = native_argv()
            at = command.index("--proc")
            command[at:at] = ["--ro-bind", "/opt/public-extra", target]
            with self.subTest(target=target), self.assertRaisesRegex(ns.SetupError, "exactly the two"):
                ns.validate_bwrap(command, SOURCE, "directory")

    def test_native_ndks_cannot_overlap_or_cover_source(self):
        for source in ("/opt/native-ndk28", "/opt/native-ndk28/nested", "/opt", SOURCE,
                       SOURCE + "/tools", "/workspace"):
            command = native_argv()
            command[command.index("/opt/native-ndk29")] = source
            with self.subTest(source=source), self.assertRaises(ns.SetupError):
                ns.validate_bwrap(command, SOURCE, "directory")


class MappingTests(unittest.TestCase):
    @patch.object(ns.os, "close")
    @patch.object(ns.os, "write", side_effect=lambda fd, value: len(value))
    @patch.object(ns.os, "open", side_effect=[20, 21, 22])
    def test_exact_single_caller_mapping_and_deny(self, opened, written, closed):
        ns._write_maps(10, 1001, 1002)
        flags = os.O_WRONLY | os.O_CLOEXEC | os.O_NOFOLLOW
        self.assertEqual(opened.call_args_list, [call("uid_map", flags, dir_fd=10), call("setgroups", flags, dir_fd=10), call("gid_map", flags, dir_fd=10)])
        self.assertEqual(written.call_args_list, [call(20, b"0 1001 1\n"), call(21, b"deny\n"), call(22, b"0 1002 1\n")])
        self.assertEqual(closed.call_args_list, [call(20), call(21), call(22)])

    @patch.object(ns.os, "close")
    @patch.object(ns.os, "write", return_value=1)
    @patch.object(ns.os, "open", return_value=20)
    def test_short_mapping_write_fails_closed_and_closes_fd(self, opened, written, closed):
        with self.assertRaises(ns.SetupError):
            ns._write_maps(10, 1001, 1002)
        self.assertEqual(opened.call_count, 1)
        closed.assert_called_once_with(20)

    @patch.object(ns.os, "close")
    @patch.object(ns.os, "listdir", return_value=["0", "1", "2", "3", "999"])
    def test_inherited_fds_above_rlimit_are_closed_without_reading_contents(self, listed, closed):
        ns._close_inherited_fds()
        listed.assert_called_once_with("/proc/self/fd")
        self.assertEqual(closed.call_args_list, [call(3), call(999)])


class HandshakeTests(unittest.TestCase):
    @patch.object(ns.time, "monotonic", return_value=10)
    @patch.object(ns.select, "select", return_value=([], [], []))
    @patch.object(ns.os, "read")
    def test_handshake_timeout_never_reads_or_executes(self, read, selected, clock):
        with self.assertRaises(ns.SetupError):
            ns._receive(7, b"U", 11)
        selected.assert_called_once_with([7], [], [], 1)
        read.assert_not_called()

    @patch.object(ns.time, "monotonic", return_value=10)
    @patch.object(ns.select, "select", return_value=([7], [], []))
    @patch.object(ns.os, "read", return_value=b"")
    def test_child_eof_fails_closed(self, read, selected, clock):
        with self.assertRaises(ns.SetupError):
            ns._receive(7, b"U", 11)

    @patch.object(ns, "_terminate_child")
    @patch.object(ns, "_wait_child", return_value=0)
    @patch.object(ns, "_drop_parent")
    @patch.object(ns, "_write_maps")
    @patch.object(ns, "_send")
    @patch.object(ns, "_receive")
    @patch.object(ns.os, "open", return_value=13)
    @patch.object(ns.os, "close")
    def test_parent_drop_precedes_go_token(self, closed, opened, receive, send, maps, drop, wait, terminate):
        order = Mock()
        for name, mocked in (("receive", receive), ("send", send), ("maps", maps), ("drop", drop), ("wait", wait)):
            order.attach_mock(mocked, name)
        linux = Mock()
        self.assertEqual(ns._parent(linux, 77, 7, 8, 1001, 1002), 0)
        names = [c[0] for c in order.mock_calls]
        self.assertEqual(names, ["receive", "maps", "send", "receive", "drop", "send", "wait"])
        self.assertEqual(send.call_args_list, [call(8, b"M"), call(8, b"G")])
        drop.assert_called_once_with(linux, 1001, 1002)
        terminate.assert_not_called()
        self.assertEqual(opened.call_args.args[0], "/proc/77")
        self.assertTrue(opened.call_args.args[1] & os.O_NOFOLLOW)

    @patch.object(ns, "_terminate_child")
    @patch.object(ns, "_drop_parent", side_effect=OSError(errno.EPERM, "denied"))
    @patch.object(ns, "_write_maps")
    @patch.object(ns, "_send")
    @patch.object(ns, "_receive")
    @patch.object(ns.os, "open", return_value=13)
    @patch.object(ns.os, "close")
    def test_drop_failure_never_releases_target_and_terminates_child(self, closed, opened, receive, send, maps, drop, terminate):
        with self.assertRaises(OSError):
            ns._parent(Mock(), 77, 7, 8, 1001, 1002)
        self.assertEqual(send.call_args_list, [call(8, b"M")])
        terminate.assert_called_once_with(77)

    @patch.object(ns, "_terminate_child")
    @patch.object(ns, "_write_maps", side_effect=OSError(errno.EACCES, "denied"))
    @patch.object(ns, "_send")
    @patch.object(ns, "_receive")
    @patch.object(ns.os, "open", return_value=13)
    @patch.object(ns.os, "close")
    def test_mapping_denial_never_releases_child(self, closed, opened, receive, send, maps, terminate):
        with self.assertRaises(OSError):
            ns._parent(Mock(), 77, 7, 8, 1001, 1002)
        send.assert_not_called()
        terminate.assert_called_once_with(77)

    @patch.object(ns, "_bind_parent_death")
    @patch.object(ns, "_receive")
    @patch.object(ns, "_send")
    @patch.object(ns.os, "setresgid")
    @patch.object(ns.os, "setresuid")
    @patch.object(ns.os, "getresgid", return_value=(0, 0, 0))
    @patch.object(ns.os, "getresuid", return_value=(0, 0, 0))
    @patch.object(ns.os, "getgroups", return_value=[])
    @patch.object(ns.os, "close")
    @patch.object(ns.os, "execve", side_effect=RuntimeError("mock handoff"))
    def test_child_exec_has_clean_environment_and_only_follows_two_gates(self, execute, closed, groups, uids, gids, setuid, setgid, send, receive, death):
        linux = Mock()
        order = Mock()
        for name, mocked in (("unshare", linux.unshare), ("receive", receive), ("send", send), ("setuid", setuid), ("setgid", setgid), ("exec", execute)):
            order.attach_mock(mocked, name)
        with self.assertRaisesRegex(RuntimeError, "mock handoff"):
            ns._child(linux, 42, 7, 8, argv())
        self.assertEqual([c[0] for c in order.mock_calls], ["unshare", "send", "receive", "setgid", "setuid", "send", "receive", "exec"])
        self.assertEqual([c.args[1] for c in receive.call_args_list], [b"M", b"G"])
        execute.assert_called_once_with(ns.BWRAP, argv(), ns.CLEAN_ENV)
        linux.clear_caps.assert_not_called()
        self.assertEqual(death.call_count, 2)

    @patch.object(ns.os, "getppid", return_value=1)
    def test_parent_death_race_is_rejected(self, parent):
        linux = Mock()
        with self.assertRaises(ns.SetupError):
            ns._bind_parent_death(linux, 42)
        linux.prctl.assert_called_once_with(ns.PR_SET_PDEATHSIG, signal.SIGKILL)

    @patch.object(ns.os, "waitpid", side_effect=ChildProcessError)
    @patch.object(ns.os, "kill")
    def test_reaped_pid_is_never_signalled(self, kill, waited):
        ns._terminate_child(77)
        kill.assert_not_called()

    @patch.object(ns.os, "waitpid", return_value=(77, 9))
    def test_signal_exit_status_is_preserved(self, waited):
        self.assertEqual(ns._wait_child(77, 100), 137)

    @patch.object(ns.time, "monotonic", return_value=101)
    @patch.object(ns.os, "waitpid", return_value=(0, 0))
    @patch.object(ns.time, "sleep")
    def test_final_wait_is_bounded(self, sleep, waited, clock):
        with self.assertRaises(ns.SetupError):
            ns._wait_child(77, 100)
        sleep.assert_not_called()


class PrivilegeTests(unittest.TestCase):
    @patch.object(ns, "_report")
    @patch.object(ns, "run")
    def test_main_requires_isolated_no_site_no_bytecode_startup(self, run, report):
        for isolated, no_site, no_bytecode in ((0, 1, True), (1, 0, True), (1, 1, False)):
            with self.subTest(isolated=isolated, no_site=no_site, no_bytecode=no_bytecode):
                with patch.object(ns.sys, "flags", Mock(isolated=isolated, no_site=no_site)), patch.object(ns.sys, "dont_write_bytecode", no_bytecode):
                    self.assertEqual(ns.main([]), 125)
        run.assert_not_called()
        self.assertEqual(report.call_count, 3)

    @patch.object(ns.os, "getgroups", return_value=[])
    @patch.object(ns.os, "getresuid", return_value=(1001, 1001, 1001))
    @patch.object(ns.os, "getresgid", return_value=(1002, 1002, 1002))
    @patch.object(ns.os, "setresuid")
    @patch.object(ns.os, "setresgid")
    @patch.object(ns.os, "setgroups")
    def test_parent_all_uid_gid_and_capability_sets_are_dropped(self, groups, gid, uid, getgid, getuid, getgroups):
        linux = Mock()
        ns._drop_parent(linux, 1001, 1002)
        groups.assert_called_once_with([])
        gid.assert_called_once_with(1002, 1002, 1002)
        uid.assert_called_once_with(1001, 1001, 1001)
        linux.clear_caps.assert_called_once_with()
        self.assertIn(call(ns.PR_SET_NO_NEW_PRIVS, 1), linux.prctl.call_args_list)
        self.assertIn(call(ns.PR_SET_KEEPCAPS, 0), linux.prctl.call_args_list)
        self.assertIn(call(ns.PR_CAP_AMBIENT, ns.PR_CAP_AMBIENT_CLEAR_ALL), linux.prctl.call_args_list)
        self.assertEqual([c.args[1] for c in linux.prctl.call_args_list if c.args[0] == ns.PR_CAPBSET_DROP], list(range(64)))

    @patch.object(ns.os, "getresuid", return_value=(1001, 1001, 1001))
    @patch.object(ns.os, "fork")
    def test_nonroot_launch_rejected_before_fork(self, fork, uids):
        with self.assertRaises(ns.SetupError):
            ns.run(1001, 1002, argv(), SOURCE, "directory")
        fork.assert_not_called()

    @patch.object(ns.os, "fork")
    def test_root_host_mapping_is_rejected_before_fork(self, fork):
        for uid, gid in ((0, 1002), (1001, 0), (-1, 1002), (1001, 4294967295), (True, 1002)):
            with self.subTest(uid=uid, gid=gid), self.assertRaises(ns.SetupError):
                ns.run(uid, gid, argv(), SOURCE, "directory")
        fork.assert_not_called()


if __name__ == "__main__":
    unittest.main()
