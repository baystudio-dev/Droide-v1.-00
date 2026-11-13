#!/usr/bin/env python3
"""Synthetic data and mocked HTTP only; never fetch or execute an artifact."""
from __future__ import annotations

from contextlib import nullcontext, redirect_stdout
from email.message import Message
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import xml.etree.ElementTree as ET


HELPER = Path(__file__).resolve().parents[1] / "fdroid_maven_registry_parity.py"
SPEC = importlib.util.spec_from_file_location("fdroid_maven_registry_parity", HELPER)
assert SPEC is not None and SPEC.loader is not None
checker = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = checker
SPEC.loader.exec_module(checker)
REVIEWED_ARTIFACTS = checker.ARTIFACTS


class Response(io.BytesIO):
    def __init__(self, url, data, *, status=200, length=True, encoding=None):
        super().__init__(data)
        self.url, self.status = url, status
        self.headers = Message()
        if length:
            self.headers["Content-Length"] = str(len(data))
        if encoding is not None:
            self.headers["Content-Encoding"] = encoding

    def geturl(self):
        return self.url


class Opener:
    def __init__(self, payloads):
        self.payloads = payloads
        self.requests = []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        return Response(request.full_url, self.payloads[request.full_url])


class RegistryParityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="registry-parity-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "checkout"
        self.root.mkdir()
        self.output = self.base / "proof"
        self.payloads = {a.official_url: f"synthetic data only: {a.coordinate}\n".encode()
                         for a in REVIEWED_ARTIFACTS}
        self.inventory = tuple(a._replace(sha256=hashlib.sha256(self.payloads[a.official_url]).hexdigest())
                               for a in REVIEWED_ARTIFACTS)
        self.patch_inventory = patch.object(checker, "ARTIFACTS", self.inventory)
        self.patch_inventory.start()
        self.addCleanup(self.patch_inventory.stop)
        self.timer = patch.object(checker, "download_deadline", side_effect=lambda: nullcontext())
        self.timer.start()
        self.addCleanup(self.timer.stop)
        for item in self.inventory:
            self.write(item.local_path, self.payloads[item.official_url])
        self.write(checker.EMBEDDED, "".join(f"{a.sha256}  {a.local_path}\n" for a in self.inventory if a.embedded).encode())
        tree = ET.Element(f"{{{checker.XML_NAMESPACE}}}verification-metadata")
        components = ET.SubElement(tree, f"{{{checker.XML_NAMESPACE}}}components")
        for item in self.inventory:
            if not item.embedded:
                continue
            group, name, version = item.coordinate.split(":")
            component = ET.SubElement(components, f"{{{checker.XML_NAMESPACE}}}component",
                                      group=group, name=name, version=version)
            artifact = ET.SubElement(component, f"{{{checker.XML_NAMESPACE}}}artifact", name=Path(item.local_path).name)
            ET.SubElement(artifact, f"{{{checker.XML_NAMESPACE}}}sha256", value=item.sha256)
        self.write(checker.GRADLE_PINS, ET.tostring(tree))
        self.write(checker.XZ_PROVENANCE, json.dumps({"version": "1.12", "jar_sha256": self.inventory[-1].sha256}).encode())

    def write(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def run_download(self, opener=None):
        opener = opener or Opener(self.payloads)
        with patch.object(checker, "make_opener", return_value=opener):
            return checker.download_and_compare(self.output, self.root)

    def test_reviewed_inventory_exact_eight_versions_and_registries(self):
        self.assertEqual({
            "com.squareup.okio:okio-jvm:3.17.0", "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcprov-jdk18on:1.84", "org.bouncycastle:bcutil-jdk18on:1.84",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
            "androidx.documentfile:documentfile:1.1.0", "org.lsposed.hiddenapibypass:hiddenapibypass:6.1",
            "org.tukaani:xz:1.12"}, {a.coordinate for a in REVIEWED_ARTIFACTS})
        with patch.object(checker, "ARTIFACTS", REVIEWED_ARTIFACTS):
            checker.validate_inventory()
        self.assertEqual(7, sum(a.official_url.startswith(checker.CENTRAL) for a in REVIEWED_ARTIFACTS))
        self.assertEqual(1, sum(a.official_url.startswith(checker.GOOGLE) for a in REVIEWED_ARTIFACTS))

    def test_default_is_read_only_offline_and_does_not_claim_registry_parity(self):
        files_before = sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*") if p.is_file())
        with patch.object(checker, "make_opener", side_effect=AssertionError("network forbidden")), redirect_stdout(io.StringIO()) as output:
            code = checker.main(["--root", str(self.root)])
        report = json.loads(output.getvalue())
        self.assertEqual(0, code)
        self.assertEqual("LOCAL_INPUTS_MATCH_PINS", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertFalse(report["networkAttempted"])
        self.assertEqual(files_before, sorted(str(p.relative_to(self.root)) for p in self.root.rglob("*") if p.is_file()))
        self.assertFalse(self.output.exists())

    def test_explicit_offline_mode(self):
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main(["--check-inputs", "--root", str(self.root)]))

    def test_network_and_output_are_both_explicit(self):
        for args in (["--download-and-compare"], ["--output-dir", str(self.output)],
                     ["--check-inputs", "--download-and-compare", "--output-dir", str(self.output)]):
            with self.subTest(args=args), patch.object(checker, "make_opener") as opener, patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
                checker.main(args)
            opener.assert_not_called()

    def test_local_pin_mismatch_fails_before_network_or_output_creation(self):
        self.write(self.inventory[0].local_path, b"changed")
        with patch.object(checker, "make_opener") as opener, self.assertRaisesRegex(checker.ParityError, "Local artifact"):
            checker.download_and_compare(self.output, self.root)
        opener.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_embedded_pin_mismatch_and_duplicate_rejected(self):
        original = (self.root / checker.EMBEDDED).read_bytes()
        for altered in (original.replace(self.inventory[0].sha256.encode(), b"0" * 64), original + original.splitlines()[0] + b"\n"):
            with self.subTest(altered=altered[:64]):
                self.write(checker.EMBEDDED, altered)
                with self.assertRaises(checker.ParityError):
                    checker.check_inputs(self.root)

    def test_gradle_pin_mismatch_rejected(self):
        xml = (self.root / checker.GRADLE_PINS).read_bytes()
        self.write(checker.GRADLE_PINS, xml.replace(self.inventory[0].sha256.encode(), b"0" * 64))
        with self.assertRaisesRegex(checker.ParityError, "Gradle SHA-256"):
            checker.check_inputs(self.root)

    def test_gradle_alternative_digest_and_duplicate_component_rejected(self):
        original = (self.root / checker.GRADLE_PINS).read_bytes()
        for duplicate in (False, True):
            tree = ET.fromstring(original)
            if duplicate:
                components = tree.find("g:components", checker.NS)
                components.append(components[0])
            else:
                pin = tree.find("g:components/g:component/g:artifact/g:sha256", checker.NS)
                ET.SubElement(pin, f"{{{checker.XML_NAMESPACE}}}also-trust", value="0" * 64)
            self.write(checker.GRADLE_PINS, ET.tostring(tree))
            with self.subTest(duplicate=duplicate), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_xz_uses_provenance_pin_and_version(self):
        for value in ({"version": "1.13", "jar_sha256": self.inventory[-1].sha256},
                      {"version": "1.12", "jar_sha256": "0" * 64}, []):
            self.write(checker.XZ_PROVENANCE, json.dumps(value).encode())
            with self.subTest(value=value), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_metadata_dtd_is_not_processed(self):
        self.write(checker.GRADLE_PINS, b'<!DOCTYPE test [<!ENTITY x "test">]><test/>')
        with self.assertRaisesRegex(checker.ParityError, "DTD"):
            checker.check_inputs(self.root)

    def test_missing_empty_and_oversized_inputs_rejected(self):
        item = self.inventory[0]
        path = self.root / item.local_path
        path.unlink()
        with self.assertRaises(OSError):
            checker.check_inputs(self.root)
        self.write(item.local_path, b"")
        with self.assertRaises(checker.ParityError):
            checker.check_inputs(self.root)
        self.write(item.local_path, b"1234")
        with self.assertRaises(checker.ParityError):
            checker.read_input(self.root, item.local_path, 3)

    def test_symlinked_input_and_parent_rejected(self):
        item = self.inventory[0]
        path = self.root / item.local_path
        outside = self.base / "outside.jar"
        outside.write_bytes(self.payloads[item.official_url])
        path.unlink()
        path.symlink_to(outside)
        with self.assertRaisesRegex(checker.ParityError, "symlinks"):
            checker.check_inputs(self.root)
        path.unlink()
        path.parent.rmdir()
        path.parent.symlink_to(self.base, target_is_directory=True)
        with self.assertRaisesRegex(checker.ParityError, "symlinks"):
            checker.check_inputs(self.root)

    def test_output_must_be_new_external_absolute_and_no_symlink(self):
        self.output.mkdir()
        alias = self.base / "alias"
        alias.symlink_to(self.base, target_is_directory=True)
        choices = [Path("relative"), self.output, self.root / "new", self.base / "missing" / "new", alias / "new"]
        for choice in choices:
            with self.subTest(choice=choice), self.assertRaises(checker.ParityError):
                checker.checked_output(choice, self.root)

    def test_all_eight_matching_results_have_narrow_claims_and_nonexecutable_outputs(self):
        opener = Opener(self.payloads)
        report = self.run_download(opener)
        self.assertEqual("ALL_EIGHT_REGISTRY_BYTES_MATCH_PINS", report["status"])
        self.assertTrue(report["registryParityVerified"])
        self.assertFalse(report["sourceBuildVerified"])
        self.assertFalse(report["fdroidAcceptanceVerified"])
        self.assertFalse(report["repositoryWritesPerformed"])
        self.assertEqual(report["inputsBefore"], report["inputsAfter"])
        self.assertEqual(8, len(report["downloads"]))
        self.assertEqual(8, len(opener.requests))
        self.assertEqual(report, json.loads((self.output / "PARITY.json").read_text()))
        for path in self.output.iterdir():
            self.assertEqual(0, path.stat().st_mode & 0o111)
        for request, timeout in opener.requests:
            self.assertEqual("GET", request.get_method())
            self.assertEqual("identity", request.get_header("Accept-encoding"))
            self.assertIsNone(request.get_header("Authorization"))
            self.assertIsNone(request.get_header("Cookie"))
            self.assertEqual(checker.SOCKET_TIMEOUT_SECONDS, timeout)

    def test_same_size_registry_mismatch_retained_and_stops(self):
        payloads = dict(self.payloads)
        payloads[self.inventory[0].official_url] = b"x" * len(payloads[self.inventory[0].official_url])
        opener = Opener(payloads)
        report = self.run_download(opener)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertEqual(1, len(opener.requests))
        self.assertFalse(report["downloads"][0]["matchesPinnedBytes"])
        self.assertEqual(payloads[self.inventory[0].official_url], (self.output / report["downloads"][0]["file"]).read_bytes())

    def test_input_pin_rechecked_after_download(self):
        owner = self
        class MutatingOpener(Opener):
            def open(self, request, timeout):
                result = super().open(request, timeout)
                owner.write(owner.inventory[0].local_path, b"modified during comparison")
                return result
        report = self.run_download(MutatingOpener(self.payloads))
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertTrue(any("Local artifact" in error for error in report["errors"]))

    def test_metadata_change_even_without_pin_change_prevents_success(self):
        owner = self
        class MutatingOpener(Opener):
            def open(self, request, timeout):
                result = super().open(request, timeout)
                path = owner.root / checker.EMBEDDED
                path.write_bytes(path.read_bytes() + b"\n")
                return result
        report = self.run_download(MutatingOpener(self.payloads))
        self.assertEqual("FAILED", report["status"])
        self.assertTrue(any("changed during" in error for error in report["errors"]))

    def test_network_errors_are_redacted_and_after_inputs_still_checked(self):
        with patch.object(checker, "make_opener") as make:
            make.return_value.open.side_effect = urllib.error.URLError("https://secret:credential@private-proxy.invalid")
            report = checker.download_and_compare(self.output, self.root)
        serialized = json.dumps(report)
        self.assertNotIn("secret", serialized)
        self.assertNotIn("private-proxy", serialized)
        self.assertIn("inputsAfter", report)
        self.assertEqual("FAILED", report["status"])

    def test_official_endpoint_allowlist_is_exact(self):
        original = self.inventory[0].official_url
        bad = [original.replace("https:", "http:"), original + "?token=secret", original + "#fragment",
               original.replace("repo.maven.apache.org", "repo.maven.apache.org.evil.invalid"),
               original.replace("repo.maven.apache.org", "user:password@repo.maven.apache.org"),
               original.replace("repo.maven.apache.org", "repo.maven.apache.org:443"),
               self.inventory[1].official_url, "file:///etc/passwd"]
        for url in bad:
            with self.subTest(url=url), self.assertRaises(checker.ParityError):
                checker.validate_target(url, original)
        checker.validate_target(original, original)

    def test_redirect_to_external_or_different_artifact_is_rejected_before_following(self):
        for target in ("https://example.invalid/a.jar", self.inventory[1].official_url):
            headers = Message()
            headers["Location"] = target
            with patch.object(checker, "make_opener") as make:
                opener = make.return_value
                opener.open.side_effect = urllib.error.HTTPError(self.inventory[0].official_url, 302, "redirect", headers, None)
                with self.assertRaisesRegex(checker.ParityError, "Redirect/URL"):
                    checker.download_file(self.inventory[0], len(self.payloads[self.inventory[0].official_url]), self.base / "redirect.download", opener)
                self.assertEqual(1, opener.open.call_count)

    def test_canonical_self_redirect_is_bounded(self):
        headers = Message()
        headers["Location"] = self.inventory[0].official_url
        with patch.object(checker, "make_opener") as make:
            opener = make.return_value
            opener.open.side_effect = lambda *args, **kwargs: (_ for _ in ()).throw(
                urllib.error.HTTPError(self.inventory[0].official_url, 302, "redirect", headers, None))
            with self.assertRaisesRegex(checker.ParityError, "redirect bound"):
                checker.download_file(self.inventory[0], len(self.payloads[self.inventory[0].official_url]), self.base / "redirect.download", opener)
            self.assertEqual(checker.MAX_REDIRECTS + 1, opener.open.call_count)

    def test_missing_duplicate_redirect_location_is_rejected(self):
        for count in (0, 2):
            headers = Message()
            for _ in range(count):
                headers["Location"] = self.inventory[0].official_url
            with patch.object(checker, "make_opener") as make:
                opener = make.return_value
                opener.open.side_effect = urllib.error.HTTPError(self.inventory[0].official_url, 302, "redirect", headers, None)
                with self.subTest(count=count), self.assertRaises(checker.ParityError):
                    checker.download_file(self.inventory[0], len(self.payloads[self.inventory[0].official_url]), self.base / "redirect.download", opener)

    def test_http_failure_no_fallback(self):
        with patch.object(checker, "make_opener") as make:
            make.return_value.open.side_effect = urllib.error.HTTPError(self.inventory[0].official_url, 404, "missing", Message(), None)
            report = checker.download_and_compare(self.output, self.root)
            self.assertEqual(1, make.return_value.open.call_count)
        self.assertEqual("FAILED", report["status"])
        self.assertIn("HTTP 404", report["errors"][0])

    def test_response_status_encoding_final_url_and_content_length_validated(self):
        item = self.inventory[0]
        data = self.payloads[item.official_url]
        responses = [Response(item.official_url, data, status=206),
                     Response(item.official_url, data, encoding="gzip"),
                     Response("https://example.invalid/a.jar", data),
                     Response(item.official_url, data + b"extra")]
        duplicate = Response(item.official_url, data)
        duplicate.headers["Content-Length"] = str(len(data))
        responses.append(duplicate)
        malformed = Response(item.official_url, data, length=False)
        malformed.headers["Content-Length"] = "not-a-number"
        responses.append(malformed)
        for index, response in enumerate(responses):
            with patch.object(checker, "make_opener") as make:
                make.return_value.open.return_value = response
                with self.subTest(index=index), self.assertRaises(checker.ParityError):
                    checker.download_file(item, len(data), self.base / f"headers-{index}.download", make.return_value)
                self.assertFalse((self.base / f"headers-{index}.download").exists())

    def test_streaming_byte_limit_and_truncation_without_content_length(self):
        item = self.inventory[0]
        data = self.payloads[item.official_url]
        for index, body in enumerate((data + b"extra", data[:-1])):
            with patch.object(checker, "make_opener") as make:
                make.return_value.open.return_value = Response(item.official_url, body, length=False)
                with self.subTest(index=index), self.assertRaises(checker.ParityError):
                    checker.download_file(item, len(data), self.base / f"stream-{index}.download", make.return_value)
                self.assertLessEqual((self.base / f"stream-{index}.download").stat().st_size, len(data))

    def test_matching_response_without_content_length_is_allowed(self):
        item = self.inventory[0]
        data = self.payloads[item.official_url]
        with patch.object(checker, "make_opener") as make:
            make.return_value.open.return_value = Response(item.official_url, data, length=False)
            result = checker.download_file(item, len(data), self.base / "no-length.download", make.return_value)
        self.assertTrue(result["matchesPinnedBytes"])

    def test_destination_not_overwritten(self):
        item = self.inventory[0]
        destination = self.base / "existing.download"
        destination.write_bytes(b"keep")
        with self.assertRaises(FileExistsError):
            checker.download_file(item, len(self.payloads[item.official_url]), destination, Opener(self.payloads))
        self.assertEqual(b"keep", destination.read_bytes())

    def test_total_input_size_bound(self):
        with patch.object(checker, "MAX_TOTAL_BYTES", 1), self.assertRaisesRegex(checker.ParityError, "total download-byte"):
            checker.check_inputs(self.root)

    def test_incomplete_http_body_is_reported_not_unhandled(self):
        with patch.object(checker, "make_opener") as make:
            response = make.return_value.open.return_value
            response.geturl.return_value = self.inventory[0].official_url
            response.status = 200
            response.headers = Message()
            response.read.side_effect = checker.http.client.IncompleteRead(b"partial", 100)
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertIn("IncompleteRead", report["errors"][0])
        self.assertIn("inputsAfter", report)

    def test_deadline_handler_is_failure(self):
        with self.assertRaisesRegex(checker.ParityError, "deadline expired"):
            checker.deadline_handler(None, None)

    def test_signal_timer_is_not_reported_as_an_os_hard_deadline(self):
        limits = checker.report_limits()
        self.assertEqual(60, limits["signalDeadlinePerArtifactSeconds"])
        self.assertNotIn("hardDeadlinePerArtifactSeconds", limits)
        self.assertFalse(limits["osHardPerArtifactDeadlineGuaranteed"])
        self.assertTrue(limits["externalProcessSupervisionRequired"])
        self.assertFalse(limits["externalProcessSupervisionVerified"])

    def test_download_deadline_installs_restores_timer_without_real_wait(self):
        self.timer.stop()
        with patch.object(checker.signal, "getitimer", return_value=(0.0, 0.0)), \
                patch.object(checker.signal, "signal", return_value=signal.SIG_DFL) as handler, \
                patch.object(checker.signal, "setitimer") as timer:
            with self.assertRaisesRegex(ValueError, "synthetic"):
                with checker.download_deadline():
                    raise ValueError("synthetic")
        self.assertEqual((signal.ITIMER_REAL, checker.ARTIFACT_DEADLINE_SECONDS), timer.call_args_list[0].args)
        self.assertEqual((signal.ITIMER_REAL, 0), timer.call_args_list[-1].args)
        self.assertEqual((signal.SIGALRM, signal.SIG_DFL), handler.call_args_list[-1].args)

    def test_existing_timer_refused_without_clobber(self):
        self.timer.stop()
        with patch.object(checker.signal, "getitimer", return_value=(1.0, 0.0)), \
                patch.object(checker.signal, "signal") as handler, \
                patch.object(checker.signal, "setitimer") as timer, self.assertRaises(checker.ParityError):
            with checker.download_deadline():
                self.fail("must not enter")
        handler.assert_not_called()
        timer.assert_not_called()


if __name__ == "__main__":
    unittest.main()
