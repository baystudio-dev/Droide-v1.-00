"""Pure/mock probe tests: no live /proc reads, sockets, marker writes or targets."""
import io
import json
import os
from pathlib import Path
import stat
import sys
from types import SimpleNamespace
import unittest
from unittest import mock


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import fdroid_source_sandbox_probe as probe


def fixture():
    status = "Name:\tpython3\nUid:\t65534\t65534\t65534\t65534\nGid:\t65534\t65534\t65534\t65534\n"
    status += "".join(name + ":\t0000000000000000\n" for name in probe.CAP_FIELDS)
    status += "NoNewPrivs:\t1\n"
    mountinfo = "\n".join((
        "1 0 0:1 / / ro,nosuid,nodev - tmpfs tmpfs rw",
        "2 1 8:1 /usr /usr ro,relatime - ext4 /dev/example rw",
        "3 1 8:1 /checkout /src ro,relatime - ext4 /dev/example rw",
        "4 1 0:4 / /proc ro,nosuid,nodev,noexec - proc proc rw",
        "5 1 0:5 / /dev ro,nosuid - tmpfs tmpfs rw,size=65536k",
        "6 5 0:6 /null /dev/null rw,nosuid - tmpfs tmpfs rw",
        "7 5 0:7 / /dev/pts rw,nosuid,noexec - devpts devpts rw,gid=5,mode=620,ptmxmode=666",
        "8 1 0:8 / /scratch rw,nosuid,nodev - tmpfs tmpfs rw,size=524288k",
        "9 3 0:9 / /src/.git ro,nosuid,nodev - tmpfs tmpfs rw,size=4k",
        "10 1 8:1 /compiler /toolchain/jdk ro,relatime - ext4 /dev/example rw",
    )) + "\n"
    return {
        "status": status, "mountinfo": mountinfo, "scratch_bytes": probe.MAX_SCRATCH_BYTES,
        "devices": {"/dev/null": (1, 3)}, "git": {"kind": "empty-directory"},
        "paths": {path: {"directory": True, "uid": 65534, "mode": 0o1777 if path == "/scratch" else 0o700}
                  for path in probe.SCRATCH_PATHS},
        "env_names": set(probe.REQUIRED_ENV) | set(probe.SHELL_ENV) | {"PROOF_DIAGNOSTICS"},
        "env_values": {**probe.REQUIRED_ENV, **probe.SHELL_ENV},
        "net_dev": "Inter-| Receive | Transmit\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs drop fifo colls carrier compressed\n"
                   "    lo: 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0\n",
        "ipv4_routes": "\t".join(probe.IPV4_HEADER) + "\n",
        "ipv6_routes": "",
    }


class ValidationTests(unittest.TestCase):
    def blocked(self, snapshot, code):
        with self.assertRaises(probe.ProbeFailure) as raised:
            probe.validate(snapshot)
        self.assertEqual(code, str(raised.exception))

    def test_complete_expected_snapshot_passes_without_io(self):
        with mock.patch.object(probe, "observe", side_effect=AssertionError("unexpected live observation")), \
             mock.patch.object(probe, "scratch_marker", side_effect=AssertionError("unexpected write")):
            result = probe.validate(fixture())
        self.assertEqual("SOURCE_PROOF_SANDBOX_READY", result["status"])
        self.assertEqual(probe.MAX_SCRATCH_BYTES, result["scratchLimitBytes"])
        self.assertNotIn("mountinfo", result)
        self.assertNotIn("env_values", result)

    def test_every_uid_gid_slot_is_checked(self):
        for field in ("Uid", "Gid"):
            for index in range(4):
                snapshot = fixture()
                values = ["65534"] * 4
                values[index] = "0"
                snapshot["status"] = snapshot["status"].replace(
                    field + ":\t65534\t65534\t65534\t65534", field + ":\t" + "\t".join(values))
                with self.subTest(field=field, index=index):
                    self.blocked(snapshot, "SANDBOX_IDENTITY_MISMATCH")

    def test_every_capability_set_must_be_empty(self):
        for field in probe.CAP_FIELDS:
            snapshot = fixture()
            snapshot["status"] = snapshot["status"].replace(field + ":\t0000000000000000", field + ":\t0000000000000001")
            with self.subTest(field=field):
                self.blocked(snapshot, "CAPABILITY_PRESENT")

    def test_missing_malformed_and_duplicate_identity_fields_fail(self):
        variants = (
            ("NoNewPrivs:\t1\n", "", "MISSING_IDENTITY_CONTROL"),
            ("NoNewPrivs:\t1", "NoNewPrivs:\t0", "NO_NEW_PRIVILEGES_NOT_SET"),
            ("CapEff:\t0000000000000000", "CapEff:\tinvalid", "INVALID_CAPABILITY_FIELD"),
            ("NoNewPrivs:\t1\n", "NoNewPrivs:\t1\nNoNewPrivs:\t1\n", "DUPLICATE_STATUS_FIELD"),
        )
        for before, after, code in variants:
            snapshot = fixture()
            snapshot["status"] = snapshot["status"].replace(before, after)
            self.blocked(snapshot, code)

    def test_all_required_real_mounts_are_readonly(self):
        for point in ("/", "/src", "/usr", "/proc", "/dev"):
            snapshot = fixture()
            snapshot["mountinfo"] = snapshot["mountinfo"].replace(" " + point + " ro,", " " + point + " rw,")
            with self.subTest(point=point):
                self.blocked(snapshot, "REQUIRED_MOUNT_NOT_READONLY")

    def test_submounts_and_toolchains_cannot_add_writable_filesystems(self):
        for point in ("/src/extra", "/usr/extra", "/proc/extra", "/scratch/host", "/toolchain/ndk", "/elsewhere"):
            snapshot = fixture()
            snapshot["mountinfo"] += "11 1 8:2 / " + point + " rw - ext4 /dev/example rw\n"
            with self.subTest(point=point):
                self.blocked(snapshot, "UNEXPECTED_WRITABLE_MOUNT")

    def test_existing_toolchain_bind_must_be_readonly(self):
        snapshot = fixture()
        snapshot["mountinfo"] = snapshot["mountinfo"].replace("/toolchain/jdk ro,", "/toolchain/jdk rw,")
        self.blocked(snapshot, "UNEXPECTED_WRITABLE_MOUNT")

    def test_only_exact_safe_character_device_identity_is_permitted(self):
        for value in ((8, 0), None, (1, 5)):
            snapshot = fixture()
            snapshot["devices"]["/dev/null"] = value
            self.blocked(snapshot, "UNSAFE_CHARACTER_DEVICE")

    def test_devpts_cannot_be_a_host_directory_bind(self):
        snapshot = fixture()
        snapshot["mountinfo"] = snapshot["mountinfo"].replace("- devpts devpts", "- ext4 /dev/example")
        self.blocked(snapshot, "UNSAFE_DEVPTS_MOUNT")

    def test_unlisted_writable_device_is_rejected(self):
        snapshot = fixture()
        snapshot["mountinfo"] += "11 5 0:6 /disk /dev/disk rw - tmpfs tmpfs rw\n"
        self.blocked(snapshot, "UNEXPECTED_WRITABLE_MOUNT")

    def test_scratch_type_root_size_and_real_capacity_are_checked(self):
        variants = (
            ("8 1 0:8 / /scratch", "8 1 0:8 /host /scratch", "SCRATCH_NOT_PRIVATE_TMPFS"),
            ("/scratch rw,", "/scratch ro,", "SCRATCH_NOT_PRIVATE_TMPFS"),
            ("tmpfs tmpfs rw,size=524288k", "ext4 /dev/example rw,size=524288k", "SCRATCH_NOT_PRIVATE_TMPFS"),
            ("size=524288k", "size=524289k", "SCRATCH_SIZE_EXCEEDS_LIMIT"),
            ("size=524288k", "size=50%", "INVALID_SCRATCH_SIZE"),
            ("rw,size=524288k", "rw", "SCRATCH_SIZE_NOT_EXPLICIT"),
            ("size=524288k", "size=524288k,size=1m", "SCRATCH_SIZE_NOT_EXPLICIT"),
        )
        for before, after, code in variants:
            snapshot = fixture()
            snapshot["mountinfo"] = snapshot["mountinfo"].replace(before, after)
            self.blocked(snapshot, code)
        for size in (0, -1, probe.MAX_SCRATCH_BYTES + 1, True):
            snapshot = fixture()
            snapshot["scratch_bytes"] = size
            self.blocked(snapshot, "SCRATCH_FILESYSTEM_SIZE_MISMATCH")

    def test_supported_explicit_tmpfs_size_units(self):
        for value, expected in (("536870912", probe.MAX_SCRATCH_BYTES), ("512m", probe.MAX_SCRATCH_BYTES),
                                ("524288K", probe.MAX_SCRATCH_BYTES), ("4096", 4096)):
            self.assertEqual(expected, probe.tmpfs_size(("rw", "size=" + value)))

    def test_duplicate_or_malformed_mountinfo_fails_closed(self):
        for suffix, code in (
            ("11 1 8:1 / /src ro - ext4 /dev/example rw\n", "STACKED_MOUNTPOINT"),
            ("10 1 8:1 / /another ro - ext4 /dev/example rw\n", "DUPLICATE_MOUNT_ID"),
            ("11 1 8:1 / /another ro,rw - ext4 /dev/example rw\n", "INVALID_MOUNT_FLAGS"),
            ("invalid row\n", "MALFORMED_MOUNTINFO"),
            ("11 1 8:1 / /another/../place ro - ext4 /dev/example rw\n", "NONCANONICAL_MOUNT_PATH"),
        ):
            snapshot = fixture()
            snapshot["mountinfo"] += suffix
            self.blocked(snapshot, code)

    def test_git_requires_mask_or_actual_absence(self):
        snapshot = fixture()
        snapshot["mountinfo"] = "\n".join(line for line in snapshot["mountinfo"].splitlines() if "/src/.git " not in line) + "\n"
        self.blocked(snapshot, "GIT_DIRECTORY_NOT_MASKED")
        snapshot["git"] = {"kind": "absent"}
        self.assertTrue(probe.validate(snapshot)["gitMaskedOrAbsent"])
        snapshot["git"] = {"kind": "regular-file"}
        self.blocked(snapshot, "GIT_NOT_MASKED")

    def test_git_empty_directory_must_be_own_readonly_tmpfs(self):
        snapshot = fixture()
        snapshot["mountinfo"] = snapshot["mountinfo"].replace("/ /src/.git ro,", "/original /src/.git ro,")
        self.blocked(snapshot, "GIT_DIRECTORY_NOT_MASKED")
        snapshot = fixture()
        snapshot["git"] = {"kind": "nonempty-directory"}
        self.blocked(snapshot, "GIT_NOT_MASKED")

    def test_git_file_may_be_readonly_null_device_mask(self):
        snapshot = fixture()
        snapshot["git"] = {"kind": "null-device"}
        self.assertTrue(probe.validate(snapshot)["gitMaskedOrAbsent"])
        snapshot["mountinfo"] = snapshot["mountinfo"].replace("/src/.git ro,", "/src/.git rw,")
        self.blocked(snapshot, "UNEXPECTED_WRITABLE_MOUNT")

    def test_unknown_environment_names_fail_without_value_disclosure(self):
        snapshot = fixture()
        snapshot["env_names"].add("DUMMY_CREDENTIAL")
        self.blocked(snapshot, "UNEXPECTED_ENVIRONMENT_VARIABLE")
        snapshot = fixture()
        snapshot["env_values"]["DUMMY_CREDENTIAL"] = "never print this"
        self.blocked(snapshot, "UNEXPECTED_ENVIRONMENT_VALUE")

    def test_missing_and_unsafe_environment_values_fail(self):
        for name in probe.REQUIRED_ENV:
            snapshot = fixture()
            snapshot["env_names"].remove(name)
            self.blocked(snapshot, "REQUIRED_ENVIRONMENT_MISSING")
        for name in (*probe.REQUIRED_ENV, *probe.SHELL_ENV):
            snapshot = fixture()
            snapshot["env_values"][name] = "/outside-scratch-or-other-value"
            self.blocked(snapshot, "UNSAFE_ENVIRONMENT_VALUE")

    def test_shell_variables_and_diagnostics_are_optional_but_not_arbitrary(self):
        snapshot = fixture()
        snapshot["env_names"] = set(probe.REQUIRED_ENV)
        snapshot["env_values"] = dict(probe.REQUIRED_ENV)
        self.assertTrue(probe.validate(snapshot)["environmentAllowlisted"])
        snapshot["env_names"].add("PROOF_DIAGNOSTICS")
        self.assertTrue(probe.validate(snapshot)["environmentAllowlisted"])

    def test_scratch_home_cache_and_temp_are_real_private_directories(self):
        for path in probe.SCRATCH_PATHS:
            snapshot = fixture()
            snapshot["paths"][path]["directory"] = False
            self.blocked(snapshot, "SCRATCH_PATH_NOT_DIRECTORY")
        for path in probe.SCRATCH_PATHS[1:]:
            for key, value in (("mode", 0o777), ("uid", 0)):
                snapshot = fixture()
                snapshot["paths"][path][key] = value
                self.blocked(snapshot, "SCRATCH_PRIVATE_DIRECTORY_MISMATCH")

    def test_only_loopback_interface_is_permitted(self):
        for name in ("eth0", "veth0", "lo:extra"):
            snapshot = fixture()
            snapshot["net_dev"] = snapshot["net_dev"].replace("    lo:", "    " + name + ":")
            with self.subTest(name=name):
                self.blocked(snapshot, "MALFORMED_INTERFACE_ROW" if ":" in name else "EXTERNAL_NETWORK_INTERFACE")
        snapshot = fixture()
        snapshot["net_dev"] += "    eth0: " + "0 " * 16 + "\n"
        self.blocked(snapshot, "EXTERNAL_NETWORK_INTERFACE")

    def test_loopback_ipv4_route_is_allowed(self):
        snapshot = fixture()
        snapshot["ipv4_routes"] += "lo 0000007F 00000000 0001 0 0 0 000000FF 0 0 0\n"
        self.assertIn("loopback-only", probe.validate(snapshot)["network"])

    def test_successfully_read_zero_byte_ipv4_table_means_no_routes(self):
        snapshot = fixture()
        snapshot["ipv4_routes"] = ""
        self.assertIn("loopback-only", probe.validate(snapshot)["network"])
        snapshot["net_dev"] += "    eth0: " + "0 " * 16 + "\n"
        self.blocked(snapshot, "EXTERNAL_NETWORK_INTERFACE")

    def test_nonempty_ipv4_table_still_requires_the_exact_header(self):
        for content in ("\n", " ", "invalid", "Iface Destination Gateway\n",
                        "lo 0000007F 00000000 0001 0 0 0 000000FF 0 0 0\n"):
            snapshot = fixture()
            snapshot["ipv4_routes"] = content
            self.blocked(snapshot, "MALFORMED_IPV4_TABLE")

    def test_forwarding_default_external_or_gateway_ipv4_route_is_rejected(self):
        for row, code in (
            ("lo 00000000 00000000 0001 0 0 0 00000000 0 0 0", "EXTERNAL_IPV4_ROUTE"),
            ("eth0 00000000 00000000 0001 0 0 0 00000000 0 0 0", "EXTERNAL_IPV4_ROUTE"),
            ("lo 0000000A 00000000 0001 0 0 0 000000FF 0 0 0", "EXTERNAL_IPV4_ROUTE"),
            ("lo 0000007F 0100007F 0003 0 0 0 000000FF 0 0 0", "EXTERNAL_IPV4_GATEWAY"),
        ):
            snapshot = fixture()
            snapshot["ipv4_routes"] += row + "\n"
            self.blocked(snapshot, code)

    def test_ipv6_loopback_and_kernel_reject_defaults_are_allowed(self):
        snapshot = fixture()
        snapshot["ipv6_routes"] = ("00000000000000000000000000000001 80 " + "0" * 32 + " 00 " +
                                   "0" * 32 + " 00000000 00000000 00000000 00000001 lo\n")
        snapshot["ipv6_routes"] += ("0" * 32 + " 00 " + "0" * 32 + " 00 " + "0" * 32 +
                                    " ffffffff 00000000 00000000 00200200 lo\n")
        self.assertIn("loopback-only", probe.validate(snapshot)["network"])

    def test_ipv6_forwarding_default_and_external_interfaces_fail(self):
        base = "0" * 32 + " 00 " + "0" * 32 + " 00 " + "0" * 32 + " 00000000 00000000 00000000 00000001 lo\n"
        for row in (base, base.replace("lo\n", "eth0\n"), base.replace("00000001 lo", "00200200 eth0")):
            snapshot = fixture()
            snapshot["ipv6_routes"] = row
            self.blocked(snapshot, "EXTERNAL_IPV6_ROUTE")

    def test_observation_bounds_and_missing_tables_fail(self):
        for key, content, code in (
            ("status", "x" * 65537, "STATUS_TOO_LARGE"),
            ("mountinfo", "x" * 262145, "MOUNTINFO_TOO_LARGE"),
            ("net_dev", "x" * 32769, "NETWORK_TABLE_TOO_LARGE"),
            ("ipv4_routes", "invalid", "MALFORMED_IPV4_TABLE"),
            ("ipv6_routes", "invalid", "EXTERNAL_IPV6_ROUTE"),
        ):
            snapshot = fixture()
            snapshot[key] = content
            self.blocked(snapshot, code)


class ObservationAndMarkerTests(unittest.TestCase):
    def test_host_identity_is_rejected_before_other_observations(self):
        status = fixture()["status"].replace("65534", "1000")
        with mock.patch.object(probe, "read_kernel", return_value=status) as reader, \
             mock.patch.object(probe.os, "lstat", side_effect=AssertionError("unexpected path read")):
            with self.assertRaisesRegex(probe.ProbeFailure, "SANDBOX_IDENTITY_MISMATCH"):
                probe.observe()
        reader.assert_called_once_with("/proc/self/status", 65536)

    def test_unknown_environment_is_rejected_without_fetching_any_values(self):
        class NamesOnlyEnvironment(dict):
            def __getitem__(self, name):
                raise AssertionError("No environment value should be read")
        snapshot = fixture()
        environment = NamesOnlyEnvironment({**probe.REQUIRED_ENV, "DUMMY_CREDENTIAL": "not disclosed"})
        with mock.patch.object(probe, "read_kernel", side_effect=[snapshot["status"], snapshot["mountinfo"]]), \
             mock.patch.object(probe.os, "environ", environment):
            with self.assertRaisesRegex(probe.ProbeFailure, "UNEXPECTED_ENVIRONMENT_VARIABLE"):
                probe.observe()

    @staticmethod
    def file_info(**changes):
        values = dict(st_mode=stat.S_IFREG | 0o600, st_nlink=1, st_uid=65534,
                      st_dev=42, st_ino=50, st_size=len(probe.MARKER_BYTES))
        values.update(changes)
        return SimpleNamespace(**values)

    def test_marker_uses_only_exclusive_nofollow_dir_relative_create_and_unlink(self):
        with mock.patch.object(probe.os, "open", side_effect=[10, 11]) as opened, \
             mock.patch.object(probe.os, "fstat", side_effect=[self.file_info(st_size=0), self.file_info()]), \
             mock.patch.object(probe.os, "write", return_value=len(probe.MARKER_BYTES)) as written, \
             mock.patch.object(probe.os, "stat", return_value=self.file_info()) as checked, \
             mock.patch.object(probe.os, "unlink") as unlinked, mock.patch.object(probe.os, "close") as closed:
            probe.scratch_marker()
        self.assertEqual("/scratch", opened.call_args_list[0].args[0])
        self.assertTrue(opened.call_args_list[0].args[1] & os.O_DIRECTORY)
        self.assertTrue(opened.call_args_list[0].args[1] & os.O_NOFOLLOW)
        self.assertEqual(probe.MARKER_NAME, opened.call_args_list[1].args[0])
        self.assertTrue(opened.call_args_list[1].args[1] & os.O_EXCL)
        self.assertTrue(opened.call_args_list[1].args[1] & os.O_NOFOLLOW)
        self.assertEqual({"dir_fd": 10}, opened.call_args_list[1].kwargs)
        written.assert_called_once_with(11, probe.MARKER_BYTES)
        checked.assert_called_once_with(probe.MARKER_NAME, dir_fd=10, follow_symlinks=False)
        unlinked.assert_called_once_with(probe.MARKER_NAME, dir_fd=10)
        self.assertEqual([mock.call(11), mock.call(10)], closed.call_args_list)

    def test_existing_marker_is_never_overwritten_or_deleted(self):
        with mock.patch.object(probe.os, "open", side_effect=[10, FileExistsError("dummy marker")]), \
             mock.patch.object(probe.os, "unlink") as unlinked, mock.patch.object(probe.os, "close") as closed:
            with self.assertRaises(FileExistsError):
                probe.scratch_marker()
        unlinked.assert_not_called()
        closed.assert_called_once_with(10)

    def test_failed_write_cleans_only_our_created_marker(self):
        with mock.patch.object(probe.os, "open", side_effect=[10, 11]), \
             mock.patch.object(probe.os, "fstat", return_value=self.file_info(st_size=0)), \
             mock.patch.object(probe.os, "write", return_value=1), \
             mock.patch.object(probe.os, "stat", return_value=self.file_info()), \
             mock.patch.object(probe.os, "unlink") as unlinked, mock.patch.object(probe.os, "close"):
            with self.assertRaisesRegex(probe.ProbeFailure, "SCRATCH_MARKER_WRITE_FAILED"):
                probe.scratch_marker()
        unlinked.assert_called_once_with(probe.MARKER_NAME, dir_fd=10)

    def test_replaced_marker_is_not_deleted(self):
        with mock.patch.object(probe.os, "open", side_effect=[10, 11]), \
             mock.patch.object(probe.os, "fstat", return_value=self.file_info()), \
             mock.patch.object(probe.os, "write", return_value=len(probe.MARKER_BYTES)), \
             mock.patch.object(probe.os, "stat", return_value=self.file_info(st_ino=999)), \
             mock.patch.object(probe.os, "unlink") as unlinked, mock.patch.object(probe.os, "close"):
            with self.assertRaisesRegex(probe.ProbeFailure, "SCRATCH_MARKER_IDENTITY_CHANGED"):
                probe.scratch_marker()
        unlinked.assert_not_called()

    def test_main_checks_before_marker_and_reports_only_structured_success(self):
        output = io.StringIO()
        with mock.patch.object(probe, "observe", return_value=fixture()), \
             mock.patch.object(probe, "scratch_marker") as marker, mock.patch("sys.stdout", output):
            self.assertEqual(0, probe.main([]))
        marker.assert_called_once_with()
        report = json.loads(output.getvalue())
        self.assertEqual("SOURCE_PROOF_SANDBOX_READY", report["status"])
        self.assertTrue(report["scratchCreateUnlinkVerified"])
        self.assertNotIn("/dev/example", output.getvalue())

    def test_failed_check_does_not_write_marker_or_echo_unsafe_values(self):
        snapshot = fixture()
        snapshot["env_values"]["HOME"] = "/dummy-private-value-not-for-output"
        output = io.StringIO()
        with mock.patch.object(probe, "observe", return_value=snapshot), \
             mock.patch.object(probe, "scratch_marker") as marker, mock.patch("sys.stdout", output):
            self.assertEqual(2, probe.main([]))
        marker.assert_not_called()
        self.assertEqual({"status": "SOURCE_PROOF_SANDBOX_BLOCKED", "code": "UNSAFE_ENVIRONMENT_VALUE"},
                         json.loads(output.getvalue()))
        self.assertNotIn("dummy-private", output.getvalue())

    def test_permission_denial_fails_closed_without_exception_details(self):
        output = io.StringIO()
        with mock.patch.object(probe, "observe", side_effect=PermissionError("dummy private host path")), \
             mock.patch.object(probe, "scratch_marker") as marker, mock.patch("sys.stdout", output):
            self.assertEqual(2, probe.main([]))
        marker.assert_not_called()
        self.assertEqual("OBSERVATION_UNAVAILABLE", json.loads(output.getvalue())["code"])
        self.assertNotIn("private", output.getvalue())

    def test_ipv4_read_failure_is_not_converted_to_a_zero_route_table(self):
        output = io.StringIO()
        with mock.patch("builtins.open", side_effect=PermissionError("dummy route read denied")), \
             mock.patch.object(probe, "observe", side_effect=lambda: probe.read_kernel("/proc/self/net/route", 32768)), \
             mock.patch.object(probe, "scratch_marker") as marker, mock.patch("sys.stdout", output):
            self.assertEqual(2, probe.main([]))
        marker.assert_not_called()
        self.assertEqual("OBSERVATION_UNAVAILABLE", json.loads(output.getvalue())["code"])
        self.assertNotIn("dummy", output.getvalue())

    def test_no_argument_can_disable_a_check_or_request_target_execution(self):
        for arguments in (["--skip-network"], ["--check-only"], ["--", "command"]):
            output = io.StringIO()
            with mock.patch.object(probe, "observe") as observed, mock.patch("sys.stdout", output):
                self.assertEqual(2, probe.main(arguments))
            observed.assert_not_called()
            self.assertEqual("UNEXPECTED_PROBE_ARGUMENT", json.loads(output.getvalue())["code"])


if __name__ == "__main__":
    unittest.main()
