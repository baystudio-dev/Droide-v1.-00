#!/usr/bin/env python3
"""Offline source-readiness contracts: XML/mocked HTTP, no R8 execution."""
from __future__ import annotations

from contextlib import nullcontext, redirect_stdout
from email.message import Message
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import xml.etree.ElementTree as ET


HELPER = Path(__file__).resolve().parents[1] / "fdroid_r8_registry_parity.py"
SPEC = importlib.util.spec_from_file_location("fdroid_r8_registry_parity", HELPER)
assert SPEC is not None and SPEC.loader is not None
checker = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = checker
SPEC.loader.exec_module(checker)
REVIEWED_ARTIFACTS = checker.ARTIFACTS


class Response(io.BytesIO):
    def __init__(self, item, data, *, url=None, status=200, length=None):
        super().__init__(data)
        self.url, self.status = url or item.official_url, status
        self.headers = Message()
        self.headers["Content-Length"] = str(len(data) if length is None else length)
        self.requests = []

    def geturl(self):
        return self.url

    def read(self, size=-1):
        self.requests.append(size)
        return super().read(size)


class Opener:
    def __init__(self, response=None, error=None):
        self.response, self.error, self.requests = response, error, []

    def open(self, request, timeout):
        self.requests.append((request, timeout))
        if self.error is not None:
            raise self.error
        return self.response


class R8RegistryParityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="r8-registry-parity-test-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / "checkout"
        self.root.mkdir()
        self.output = self.base / "proof"
        self.pin_path = self.root / checker.GRADLE_PINS
        self.pin_path.parent.mkdir()
        self.write_xml(self.make_tree())
        self.timer = patch.object(checker, "download_deadline", side_effect=lambda: nullcontext())
        self.timer.start()
        self.addCleanup(self.timer.stop)

    def make_tree(self):
        tree = ET.Element(f"{{{checker.XML_NAMESPACE}}}verification-metadata")
        components = ET.SubElement(tree, f"{{{checker.XML_NAMESPACE}}}components")
        component = ET.SubElement(components, f"{{{checker.XML_NAMESPACE}}}component",
                                  group="com.android.tools", name="r8", version="9.1.29")
        for item in REVIEWED_ARTIFACTS:
            artifact = ET.SubElement(component, f"{{{checker.XML_NAMESPACE}}}artifact", name=item.filename)
            ET.SubElement(artifact, f"{{{checker.XML_NAMESPACE}}}sha256", value=item.sha256, origin="fixture")
        return tree

    def write_xml(self, tree):
        self.pin_path.write_bytes(ET.tostring(tree))

    def destination(self, item=None, label="download"):
        item = item or REVIEWED_ARTIFACTS[0]
        directory = self.base / label
        directory.mkdir()
        return directory / (item.filename + ".download")

    def download(self, response=None, *, item=None, opener=None, remaining=None, label="download"):
        item = item or REVIEWED_ARTIFACTS[0]
        opener = opener or Opener(response)
        destination = self.destination(item, label)
        result = checker.download_file(item, destination, opener,
                                       checker.MAX_TOTAL_BYTES if remaining is None else remaining)
        return result, destination, opener

    @staticmethod
    def matched_row(item, destination, _opener, _remaining, *, output_fd=None):
        # Orchestration fixture, not a claim that synthetic bytes match R8.
        return {"file": destination.name, "officialUrl": item.official_url,
                "expectedSha256": item.sha256, "status": "MATCH", "networkAttempted": True,
                "bytesReceived": 10, "completeBodyReceived": True, "sha256": item.sha256,
                "matchesPinnedBytes": True, "redirectsFollowed": 0}

    def test_fixed_inventory_has_exact_two_original_pins_and_limits(self):
        self.assertEqual((
            ("r8-9.1.29.jar", "9a7d1e9e4c2d3ee05482b9695df5f4d8f92c22ebb74603d6be4eab0505467f1a", 67108864),
            ("r8-9.1.29.pom", "136af50c91e46198fc439f2e233094edd39c5d68df88f33045526b872a4a6e06", 65536),
        ), REVIEWED_ARTIFACTS)
        self.assertEqual(67174400, checker.MAX_TOTAL_BYTES)
        self.assertEqual(sum(item.maximum_bytes for item in REVIEWED_ARTIFACTS), checker.MAX_TOTAL_BYTES)
        self.assertEqual(8, len(checker.utilities.ARTIFACTS))
        self.assertNotIn(checker.COORDINATE, {item.coordinate for item in checker.utilities.ARTIFACTS})
        self.assertEqual(60, checker.utilities.ARTIFACT_DEADLINE_SECONDS)

    def test_default_reads_only_pin_metadata_without_network_or_output(self):
        before = self.pin_path.read_bytes()
        with patch.object(checker, "make_opener", side_effect=AssertionError("network forbidden")), \
                redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(0, checker.main(["--root", str(self.root)]))
        result = json.loads(stdout.getvalue())
        self.assertEqual("LOCAL_R8_PINS_VERIFIED", result["status"])
        self.assertFalse(result["networkAttempted"])
        self.assertFalse(result["registryParityVerified"])
        self.assertEqual(before, self.pin_path.read_bytes())
        self.assertEqual([self.pin_path], [path for path in self.root.rglob("*") if path.is_file()])
        self.assertFalse(self.output.exists())

    def test_explicit_check_inputs_is_offline(self):
        with patch.object(checker, "make_opener") as opener, redirect_stdout(io.StringIO()):
            self.assertEqual(0, checker.main(["--check-inputs", "--root", str(self.root)]))
        opener.assert_not_called()

    def test_offline_cli_loads_fixed_sibling_under_isolated_python(self):
        # The enclosing suite must already be in the enforced offline, read-only
        # OS sandbox; this child inherits that isolation and resource limits.
        environment = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C", "TZ": "UTC"}
        for name, relative in (("HOME", "home"), ("TMPDIR", "tmp"), ("XDG_CACHE_HOME", "cache")):
            directory = self.base / relative
            directory.mkdir()
            environment[name] = str(directory)
        result = subprocess.run([sys.executable, "-I", "-B", str(HELPER), "--root", str(self.root)],
                                cwd=self.base, env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("LOCAL_R8_PINS_VERIFIED", report["status"])
        self.assertFalse(report["networkAttempted"])
        self.assertFalse(report["registryParityVerified"])
        self.assertFalse(self.output.exists())

    def test_symlink_invocation_cannot_load_a_shadow_sibling(self):
        proxy = self.base / "proxy"
        proxy.mkdir()
        invocation = proxy / "r8-parity.py"
        invocation.symlink_to(HELPER)
        (proxy / "fdroid_maven_registry_parity.py").write_text(
            'raise AssertionError("Shadow sibling must never be imported")\n', encoding="utf-8")
        environment = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C", "TZ": "UTC"}
        for name, relative in (("HOME", "home"), ("TMPDIR", "tmp"), ("XDG_CACHE_HOME", "cache")):
            directory = self.base / relative
            directory.mkdir()
            environment[name] = str(directory)
        result = subprocess.run([sys.executable, "-I", "-B", str(invocation), "--root", str(self.root)],
                                cwd=proxy, env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual("LOCAL_R8_PINS_VERIFIED", report["status"])
        self.assertFalse(report["networkAttempted"])
        self.assertNotIn("Shadow sibling", result.stderr)

    def test_direct_cli_without_bytecode_flag_does_not_write_sibling_cache(self):
        # Copy trusted source into writable scratch so a cache-writing defect
        # cannot be masked by the enclosing sandbox's read-only source mount.
        copied_tools = self.base / "trusted-copy" / "tools"
        copied_tools.mkdir(parents=True)
        copied_helper = copied_tools / HELPER.name
        copied_helper.write_bytes(HELPER.read_bytes())
        sibling = HELPER.with_name("fdroid_maven_registry_parity.py")
        (copied_tools / sibling.name).write_bytes(sibling.read_bytes())
        environment = {"PATH": "/usr/bin:/bin", "LANG": "C", "LC_ALL": "C", "TZ": "UTC"}
        for name, relative in (("HOME", "home"), ("TMPDIR", "tmp"), ("XDG_CACHE_HOME", "cache")):
            directory = self.base / relative
            directory.mkdir()
            environment[name] = str(directory)
        result = subprocess.run([sys.executable, "-I", str(copied_helper), "--root", str(self.root)],
                                cwd=self.base, env=environment, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("LOCAL_R8_PINS_VERIFIED", json.loads(result.stdout)["status"])
        self.assertEqual({copied_helper.name, sibling.name}, {path.name for path in copied_tools.iterdir()})
        self.assertFalse((copied_tools / "__pycache__").exists())

    def test_download_and_new_output_are_both_explicit(self):
        for args in (["--download-and-compare"], ["--output-dir", str(self.output)],
                     ["--check-inputs", "--download-and-compare", "--output-dir", str(self.output)]):
            with self.subTest(args=args), patch("sys.stderr", new=io.StringIO()), \
                    patch.object(checker, "make_opener") as opener, self.assertRaises(SystemExit):
                checker.main(args)
            opener.assert_not_called()

    def test_wrong_pin_fails_before_network_or_output_creation(self):
        self.pin_path.write_bytes(self.pin_path.read_bytes().replace(REVIEWED_ARTIFACTS[0].sha256.encode(), b"0" * 64))
        with patch.object(checker, "make_opener") as opener, self.assertRaisesRegex(checker.ParityError, "SHA-256"):
            checker.download_and_compare(self.output, self.root)
        opener.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_missing_duplicate_or_other_version_component_is_rejected(self):
        for variant in ("missing", "duplicate", "version", "additional-version", "nested"):
            tree = self.make_tree()
            container = tree.find("g:components", checker.NS)
            component = container[0]
            if variant == "missing":
                container.remove(component)
            elif variant == "duplicate":
                container.append(component)
            elif variant == "version":
                component.set("version", "9.1.30")
            elif variant == "additional-version":
                ET.SubElement(container, component.tag, group="com.android.tools", name="r8", version="9.1.30")
            else:
                container.remove(component)
                ET.SubElement(container, "unexpected").append(component)
            self.write_xml(tree)
            with self.subTest(variant=variant), self.assertRaisesRegex(checker.ParityError, "component"):
                checker.check_inputs(self.root)

    def test_wrong_schema_and_multiple_components_containers_are_rejected(self):
        for duplicate in (False, True):
            tree = self.make_tree()
            if duplicate:
                ET.SubElement(tree, f"{{{checker.XML_NAMESPACE}}}components")
            else:
                tree.tag = "verification-metadata"
            self.write_xml(tree)
            with self.subTest(duplicate=duplicate), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_missing_duplicate_extra_and_wrong_named_artifact_are_rejected(self):
        for variant in ("missing", "duplicate", "extra", "name", "attribute"):
            tree = self.make_tree()
            component = tree.find("g:components/g:component", checker.NS)
            if variant == "missing":
                component.remove(component[1])
            elif variant == "duplicate":
                component[1].set("name", component[0].get("name"))
            elif variant == "extra":
                ET.SubElement(component, f"{{{checker.XML_NAMESPACE}}}artifact", name="extra.jar")
            elif variant == "name":
                component[0].set("name", "r8-9.1.30.jar")
            else:
                component[0].set("unexpected", "true")
            self.write_xml(tree)
            with self.subTest(variant=variant), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_alternative_checksum_nested_trust_and_unexpected_digest_are_rejected(self):
        for variant in ("missing", "duplicate", "nested", "sha1", "trusted-key", "unknown-attribute"):
            tree = self.make_tree()
            artifact = tree.find("g:components/g:component/g:artifact", checker.NS)
            pin = artifact[0]
            if variant == "missing":
                artifact.remove(pin)
            elif variant == "duplicate":
                artifact.append(pin)
            elif variant == "nested":
                ET.SubElement(pin, f"{{{checker.XML_NAMESPACE}}}also-trust", value="0" * 64)
            elif variant in ("sha1", "trusted-key"):
                ET.SubElement(artifact, f"{{{checker.XML_NAMESPACE}}}{variant}", value="0" * 64)
            else:
                pin.set("alternative", "0" * 64)
            self.write_xml(tree)
            with self.subTest(variant=variant), self.assertRaisesRegex(checker.ParityError, "alternative bytes"):
                checker.check_inputs(self.root)

    def test_dtd_entity_non_utf8_and_malformed_xml_fail_closed(self):
        fixtures = [b'<!DOCTYPE x [<!ENTITY y "data">]><x/>', b'<!ENTITY x "data">',
                    '<!DOCTYPE x [<!ENTITY y "data">]><x/>'.encode("utf-16"), b"\xff", b"<broken>"]
        for raw in fixtures:
            self.pin_path.write_bytes(raw)
            with self.subTest(raw=raw[:20]), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_missing_empty_and_oversized_metadata_are_rejected(self):
        self.pin_path.unlink()
        with self.assertRaises(OSError):
            checker.check_inputs(self.root)
        for raw in (b"", b" " * (checker.MAX_METADATA_BYTES + 1)):
            self.pin_path.write_bytes(raw)
            with self.subTest(size=len(raw)), self.assertRaises(checker.ParityError):
                checker.check_inputs(self.root)

    def test_symlinked_metadata_and_parent_are_rejected(self):
        external = self.base / "pins.xml"
        external.write_bytes(self.pin_path.read_bytes())
        self.pin_path.unlink()
        self.pin_path.symlink_to(external)
        with self.assertRaisesRegex(checker.ParityError, "symlinks"):
            checker.check_inputs(self.root)
        self.pin_path.unlink()
        self.pin_path.parent.rmdir()
        self.pin_path.parent.symlink_to(self.base, target_is_directory=True)
        with self.assertRaisesRegex(checker.ParityError, "symlinks"):
            checker.check_inputs(self.root)

    def test_output_must_be_new_external_canonical_and_absolute(self):
        self.output.mkdir()
        alias = self.base / "alias"
        alias.symlink_to(self.base, target_is_directory=True)
        for choice in (Path("relative"), self.output, self.root / "new", self.base,
                       self.base / "missing" / "new", alias / "new"):
            with self.subTest(choice=choice), self.assertRaises(checker.ParityError):
                checker.checked_output(choice, self.root)

    def test_url_and_coordinate_variations_are_rejected(self):
        item = REVIEWED_ARTIFACTS[0]
        for url in (item.official_url.replace("https:", "http:"), item.official_url + "?token=x",
                    item.official_url + "#fragment", item.official_url.replace("dl.google.com", "dl.google.com:443"),
                    item.official_url.replace("dl.google.com", "user:password@dl.google.com"),
                    item.official_url.replace("dl.google.com", "dl.google.com.example.invalid"),
                    REVIEWED_ARTIFACTS[1].official_url, "file:///nonexistent"):
            with self.subTest(url=url), self.assertRaises(checker.ParityError):
                checker.validate_target(url, item)
        for changed in (item._replace(sha256="0" * 64), item._replace(maximum_bytes=1),
                        item._replace(filename="r8-9.1.30.jar")):
            with self.assertRaises(checker.ParityError):
                checker.validate_target(changed.official_url, changed)

    def test_every_redirect_is_rejected_even_self_redirect_without_followup(self):
        item = REVIEWED_ARTIFACTS[0]
        for index, code in enumerate((301, 302, 303, 307, 308)):
            headers = Message()
            headers["Location"] = item.official_url if index % 2 else "https://external.invalid/never"
            error = urllib.error.HTTPError(item.official_url, code, "redirect", headers, None)
            result, destination, opener = self.download(opener=Opener(error=error), label=f"redirect-{index}")
            self.assertEqual("FAILED", result["status"])
            self.assertIn("redirect is forbidden", result["error"])
            self.assertEqual(1, len(opener.requests))
            self.assertEqual(0, result["redirectsFollowed"])
            self.assertFalse(destination.exists())

    def test_http_failure_and_request_error_have_no_retry_fallback_or_secret_text(self):
        item = REVIEWED_ARTIFACTS[0]
        errors = [urllib.error.HTTPError(item.official_url, 404, "missing", Message(), None),
                  urllib.error.URLError("https://secret:password@private-proxy.invalid")]
        for index, error in enumerate(errors):
            result, _, opener = self.download(opener=Opener(error=error), label=f"network-{index}")
            self.assertEqual("FAILED", result["status"])
            self.assertEqual(1, len(opener.requests))
            self.assertNotIn("secret", json.dumps(result))
            self.assertNotIn("private-proxy", json.dumps(result))
        self.assertIn("HTTP 404", self.download(opener=Opener(error=errors[0]), label="missing")[0]["error"])

    def test_status_encoding_transfer_and_final_url_fail_before_file_creation(self):
        item = REVIEWED_ARTIFACTS[0]
        responses = [Response(item, b"data", status=206), Response(item, b"data", status=302),
                     Response(item, b"data", url=REVIEWED_ARTIFACTS[1].official_url)]
        for header, values in (("Content-Encoding", ["gzip"]), ("Content-Encoding", ["identity", "identity"]),
                               ("Transfer-Encoding", ["chunked"])):
            response = Response(item, b"data")
            for value in values:
                response.headers[header] = value
            responses.append(response)
        for index, response in enumerate(responses):
            result, destination, _ = self.download(response, label=f"headers-{index}")
            self.assertEqual("FAILED", result["status"])
            self.assertFalse(result["fileCreated"])
            self.assertFalse(destination.exists())

    def test_content_length_is_required_single_positive_canonical_and_bounded(self):
        item = REVIEWED_ARTIFACTS[0]
        for index, values in enumerate(([], ["4", "4"], ["0"], ["-1"], ["04"], [" 4"], ["4,4"],
                                        ["no"], ["9" * 20], [str(item.maximum_bytes + 1)])):
            response = Response(item, b"data")
            del response.headers["Content-Length"]
            for value in values:
                response.headers["Content-Length"] = value
            result, destination, _ = self.download(response, label=f"length-{index}")
            self.assertFalse(result["fileCreated"])
            self.assertEqual("FAILED", result["status"])
            self.assertFalse(destination.exists())
            self.assertEqual([], response.requests)

    def test_pom_and_remaining_total_have_independent_strict_bounds(self):
        pom = REVIEWED_ARTIFACTS[1]
        result, _, _ = self.download(Response(pom, b"small", length=65537), item=pom, label="pom")
        self.assertIn("byte bound", result["error"])
        result, _, _ = self.download(Response(REVIEWED_ARTIFACTS[0], b"data"), remaining=3, label="total")
        self.assertIn("remaining cumulative", result["error"])
        for budget in (0, -1, True, checker.MAX_TOTAL_BYTES + 1):
            with self.subTest(budget=budget), self.assertRaises(checker.ParityError):
                checker.download_file(pom, self.base / (pom.filename + ".download"), Opener(), budget)

    def test_stream_reads_are_64k_or_smaller_and_body_digest_is_real(self):
        item = REVIEWED_ARTIFACTS[0]
        payload = b"x" * (2 * checker.CHUNK_BYTES + 7)
        response = Response(item, payload)
        result, destination, opener = self.download(response)
        self.assertEqual([65536, 65536, 7], response.requests)
        self.assertEqual(payload, destination.read_bytes())
        self.assertEqual(len(payload), result["bytesReceived"])
        self.assertEqual(hashlib.sha256(payload).hexdigest(), result["sha256"])
        self.assertTrue(result["completeBodyReceived"])
        self.assertFalse(result["matchesPinnedBytes"])
        self.assertIn("differ from the existing pin", result["error"])
        request, timeout = opener.requests[0]
        self.assertEqual("GET", request.get_method())
        self.assertEqual("identity", request.get_header("Accept-encoding"))
        self.assertIsNone(request.get_header("Authorization"))
        self.assertIsNone(request.get_header("Cookie"))
        self.assertEqual(15, timeout)
        self.assertEqual(0, destination.stat().st_mode & 0o111)

    def test_truncated_stream_reports_partial_failure_and_retains_only_data(self):
        payload = b"partial"
        result, destination, _ = self.download(Response(REVIEWED_ARTIFACTS[0], payload, length=100))
        self.assertEqual("FAILED", result["status"])
        self.assertFalse(result["completeBodyReceived"])
        self.assertIsNone(result["sha256"])
        self.assertEqual(hashlib.sha256(payload).hexdigest(), result["partialBodySha256"])
        self.assertEqual(len(payload), result["bytesReceived"])
        self.assertEqual(payload, destination.read_bytes())

    def test_incomplete_read_exception_is_sanitized_and_does_not_claim_match(self):
        response = Response(REVIEWED_ARTIFACTS[0], b"data")
        with patch.object(response, "read", side_effect=checker.http.client.IncompleteRead(b"private-data", 8)):
            result, _, _ = self.download(response)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("IncompleteRead", result["error"])
        self.assertNotIn("private-data", json.dumps(result))
        self.assertFalse(result["matchesPinnedBytes"])

    def test_response_cannot_return_more_than_requested(self):
        response = Response(REVIEWED_ARTIFACTS[0], b"x", length=1)
        with patch.object(response, "read", return_value=b"too long"):
            result, destination, _ = self.download(response)
        self.assertEqual("FAILED", result["status"])
        self.assertEqual(0, destination.stat().st_size)
        self.assertIn("bounded read", result["error"])

    def test_downloaded_pom_is_not_parsed_even_when_it_looks_like_xml(self):
        item = REVIEWED_ARTIFACTS[1]
        payload = b'<!DOCTYPE project [<!ENTITY x SYSTEM "file:///not-read">]><project>&x;</project>'
        with patch.object(checker.ET, "fromstring", side_effect=AssertionError("downloaded POM must remain opaque")):
            result, destination, _ = self.download(Response(item, payload), item=item)
        self.assertEqual(payload, destination.read_bytes())
        self.assertIn("differ from the existing pin", result["error"])

    def test_download_output_is_never_overwritten_or_followed(self):
        item = REVIEWED_ARTIFACTS[0]
        for symlink in (False, True):
            destination = self.destination(item, f"existing-{symlink}")
            original = self.base / f"keep-{symlink}"
            original.write_bytes(b"keep")
            if symlink:
                destination.symlink_to(original)
            else:
                destination.write_bytes(b"keep")
            result = checker.download_file(item, destination, Opener(Response(item, b"data")), checker.MAX_TOTAL_BYTES)
            self.assertEqual("FAILED", result["status"])
            self.assertFalse(result["fileCreated"])
            self.assertEqual(b"keep", original.read_bytes())
            self.assertEqual(b"keep", destination.read_bytes())

    def test_output_parent_swap_during_request_cannot_redirect_any_write(self):
        before = self.pin_path.read_bytes()
        moved = self.base / "original-output"
        item = REVIEWED_ARTIFACTS[0]

        def swap_then_respond(_request, timeout):
            self.assertEqual(15, timeout)
            self.output.rename(moved)
            self.output.symlink_to(self.root, target_is_directory=True)
            return Response(item, b"data")

        opener = Opener()
        with patch.object(opener, "open", side_effect=swap_then_respond), \
                patch.object(checker, "make_opener", return_value=opener):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertTrue(report["networkAttempted"])
        self.assertFalse(report["downloads"][0]["fileCreated"])
        self.assertEqual([], list(moved.iterdir()))
        self.assertEqual([self.pin_path], [path for path in self.root.rglob("*") if path.is_file()])
        self.assertEqual(before, self.pin_path.read_bytes())
        self.assertIn("inputsAfter", report)

    def test_output_parent_swap_after_open_keeps_stream_writes_in_anchored_directory(self):
        before = self.pin_path.read_bytes()
        moved = self.base / "original-output"
        item = REVIEWED_ARTIFACTS[0]
        response = Response(item, b"data")
        original_read = response.read

        def swap_then_read(requested):
            self.output.rename(moved)
            self.output.symlink_to(self.root, target_is_directory=True)
            return original_read(requested)

        with patch.object(response, "read", side_effect=swap_then_read), \
                patch.object(checker, "make_opener", return_value=Opener(response)):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertTrue(report["downloads"][0]["fileCreated"])
        self.assertEqual(b"data", (moved / (item.filename + ".download")).read_bytes())
        self.assertFalse((moved / "PARITY.json").exists())
        self.assertEqual([self.pin_path], [path for path in self.root.rglob("*") if path.is_file()])
        self.assertEqual(before, self.pin_path.read_bytes())

    def test_output_readback_mutation_is_a_separate_failure(self):
        item = REVIEWED_ARTIFACTS[0]
        with patch.object(checker, "read_input", return_value=({"bytes": 4, "sha256": "0" * 64}, b"")):
            result, _, _ = self.download(Response(item, b"data"))
        self.assertEqual("FAILED", result["status"])
        self.assertIn("changed after", result["error"])

    def test_matching_hash_path_requires_stream_and_readback_identity(self):
        item = REVIEWED_ARTIFACTS[0]
        # Mock cryptographic identities only for this branch fixture. Production
        # pins/inventory stay fixed; real mismatching SHA-256 is tested above.
        with patch.object(checker, "new_digest") as digest, \
                patch.object(checker, "read_input", return_value=({"bytes": 4, "sha256": item.sha256}, b"")):
            digest.return_value.hexdigest.return_value = item.sha256
            result, destination, _ = self.download(Response(item, b"data"))
        self.assertEqual("MATCH", result["status"])
        self.assertTrue(result["matchesPinnedBytes"])
        self.assertEqual(b"data", destination.read_bytes())

    def test_two_matches_produce_narrow_receipt_and_no_new_inventory(self):
        original_other_inventory = checker.utilities.ARTIFACTS
        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=self.matched_row) as download:
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("BOTH_R8_REGISTRY_BYTES_MATCH_PINS", report["status"])
        self.assertTrue(report["registryParityVerified"])
        self.assertEqual(2, download.call_count)
        self.assertEqual([checker.MAX_TOTAL_BYTES, checker.MAX_TOTAL_BYTES - 10],
                         [call.args[3] for call in download.call_args_list])
        self.assertEqual(20, report["bytesReceived"])
        self.assertEqual(report["inputsBefore"], report["inputsAfter"])
        self.assertEqual(report, json.loads((self.output / "PARITY.json").read_text()))
        self.assertEqual(0, (self.output / "PARITY.json").stat().st_mode & 0o111)
        for field in ("sourceBuildVerified", "fdroidAcceptanceVerified", "repositoryWritesPerformed",
                      "downloadedArtifactsExecuted", "downloadedArchivesUnpacked", "downloadedPomParsed"):
            self.assertIs(report[field], False)
        self.assertIs(original_other_inventory, checker.utilities.ARTIFACTS)
        self.assertIs(REVIEWED_ARTIFACTS, checker.ARTIFACTS)

    def test_first_mismatch_stops_before_pom_and_still_rechecks_local_pins(self):
        opener = Opener(Response(REVIEWED_ARTIFACTS[0], b"wrong bytes"))
        with patch.object(checker, "make_opener", return_value=opener):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertEqual(1, len(opener.requests))
        self.assertEqual(1, len(report["downloads"]))
        self.assertIn("inputsAfter", report)
        self.assertTrue(report["downloads"][0]["completeBodyReceived"])

    def test_second_partial_failure_preserves_first_success_without_all_success_claim(self):
        def fixture(item, destination, opener, remaining, *, output_fd=None):
            if item == REVIEWED_ARTIFACTS[0]:
                return self.matched_row(item, destination, opener, remaining)
            return {"status": "FAILED", "matchesPinnedBytes": False, "networkAttempted": True,
                    "bytesReceived": 3, "completeBodyReceived": False, "error": "synthetic partial failure"}
        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=fixture):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertEqual("MATCH", report["downloads"][0]["status"])
        self.assertEqual(13, report["bytesReceived"])
        self.assertIn("inputsAfter", report)

    def test_after_pin_verification_detects_even_whitespace_only_change(self):
        def fixture(*args, **kwargs):
            self.pin_path.write_bytes(self.pin_path.read_bytes() + b"\n")
            return self.matched_row(*args, **kwargs)
        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=fixture):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertIn("changed during", report["errors"][0])

    def test_after_pin_verification_rejects_actual_digest_drift(self):
        def fixture(*args, **kwargs):
            self.pin_path.write_bytes(self.pin_path.read_bytes().replace(REVIEWED_ARTIFACTS[1].sha256.encode(), b"0" * 64))
            return self.matched_row(*args, **kwargs)
        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=fixture):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertTrue(any("SHA-256" in error for error in report["errors"]))

    def test_after_pin_read_rejects_replaced_symlinked_parent(self):
        moved = self.root / "original-gradle"
        unrelated = self.base / "unrelated"
        unrelated.mkdir()
        marker = unrelated / self.pin_path.name
        marker.write_bytes(b"private fixture that must not be parsed")

        def fixture(*args, **kwargs):
            if not moved.exists():
                self.pin_path.parent.rename(moved)
                self.pin_path.parent.symlink_to(unrelated, target_is_directory=True)
            return self.matched_row(*args, **kwargs)

        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=fixture):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertTrue(any("symlinks" in error for error in report["errors"]))
        self.assertNotIn("private fixture", json.dumps(report))
        self.assertEqual(b"private fixture that must not be parsed", marker.read_bytes())

    def test_receipt_write_failure_cannot_return_success(self):
        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=self.matched_row), \
                patch.object(checker, "write_report", side_effect=OSError("private details")):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("FAILED", report["status"])
        self.assertFalse(report["registryParityVerified"])
        self.assertEqual(2, len(report["downloads"]))
        self.assertNotIn("private details", json.dumps(report))

    def test_valid_success_json_then_io_failure_cannot_publish_final_receipt(self):
        original_dump = checker.json.dump
        original_fdopen = checker.os.fdopen

        def dump_then_fail(value, stream, **kwargs):
            original_dump(value, stream, **kwargs)
            stream.flush()
            raise OSError("private write failure")

        class CloseFailure:
            def __init__(self, stream):
                self.stream = stream

            def __enter__(self):
                return self.stream

            def __exit__(self, *_args):
                self.stream.close()
                raise OSError("private close failure")

        def failing_close(fd, mode, **kwargs):
            stream = original_fdopen(fd, mode, **kwargs)
            return CloseFailure(stream) if mode == "w" else stream

        failures = (
            ("write", patch.object(checker.json, "dump", side_effect=dump_then_fail)),
            ("sync", patch.object(checker.os, "fsync", side_effect=OSError("private sync failure"))),
            ("close", patch.object(checker.os, "fdopen", side_effect=failing_close)),
        )
        for name, injected in failures:
            output = self.base / ("failed-receipt-" + name)
            with self.subTest(phase=name), injected, \
                    patch.object(checker, "make_opener", return_value=Opener()), \
                    patch.object(checker, "download_file", side_effect=self.matched_row):
                report = checker.download_and_compare(output, self.root)
            self.assertEqual("FAILED", report["status"])
            self.assertFalse(report["registryParityVerified"])
            self.assertFalse((output / "PARITY.json").exists())
            pending = json.loads((output / "PARITY.pending.json").read_text())
            self.assertEqual("BOTH_R8_REGISTRY_BYTES_MATCH_PINS", pending["status"])
            self.assertNotIn("private", json.dumps(report))

    def test_pending_and_final_receipts_are_never_overwritten_or_followed(self):
        for name in ("PARITY.pending.json", "PARITY.json"):
            for symlink in (False, True):
                output = self.base / (name + str(symlink))
                output.mkdir()
                original = self.base / ("keep-" + name + str(symlink))
                original.write_bytes(b"keep")
                blocker = output / name
                if symlink:
                    blocker.symlink_to(original)
                else:
                    blocker.write_bytes(b"keep")
                with self.subTest(name=name, symlink=symlink), checker.anchored_directory(output) as anchor, \
                        self.assertRaises(FileExistsError):
                    checker.write_report(output, {"status": "synthetic-success"}, output_fd=anchor)
                self.assertEqual(b"keep", original.read_bytes())
                self.assertEqual(b"keep", blocker.read_bytes())
                if name == "PARITY.pending.json":
                    self.assertFalse((output / "PARITY.json").exists())

    def test_success_receipt_is_published_after_close_with_no_postpublication_cleanup(self):
        original_link = checker.os.link
        original_fdopen = checker.os.fdopen
        observed = []
        receipt_streams = []

        def record_stream(fd, mode, **kwargs):
            stream = original_fdopen(fd, mode, **kwargs)
            if mode == "w":
                receipt_streams.append(stream)
            return stream

        def publish(source, destination, **kwargs):
            pending = self.output / source
            self.assertEqual("PARITY.pending.json", source)
            self.assertEqual("PARITY.json", destination)
            self.assertFalse((self.output / destination).exists())
            self.assertEqual("BOTH_R8_REGISTRY_BYTES_MATCH_PINS", json.loads(pending.read_text())["status"])
            self.assertEqual(1, len(receipt_streams))
            self.assertTrue(receipt_streams[0].closed)
            observed.append((kwargs["src_dir_fd"], kwargs["dst_dir_fd"]))
            return original_link(source, destination, **kwargs)

        with patch.object(checker, "make_opener", return_value=Opener()), \
                patch.object(checker, "download_file", side_effect=self.matched_row), \
                patch.object(checker.os, "link", side_effect=publish), \
                patch.object(checker.os, "fdopen", side_effect=record_stream), \
                patch.object(checker.os, "unlink", side_effect=AssertionError("No postpublication cleanup")):
            report = checker.download_and_compare(self.output, self.root)
        self.assertEqual("BOTH_R8_REGISTRY_BYTES_MATCH_PINS", report["status"])
        self.assertEqual(1, len(observed))
        self.assertEqual(observed[0][0], observed[0][1])
        self.assertEqual((self.output / "PARITY.pending.json").stat().st_ino,
                         (self.output / "PARITY.json").stat().st_ino)

    def test_deadline_expiry_is_failure_with_no_retry(self):
        opener = Opener(error=checker.ParityError("The per-artifact signal deadline expired"))
        result, _, _ = self.download(opener=opener)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("deadline expired", result["error"])
        self.assertEqual(1, len(opener.requests))

    def test_deadline_limits_do_not_claim_os_hard_enforcement(self):
        limits = checker.report_limits()
        self.assertEqual(60, limits["signalDeadlinePerArtifactSeconds"])
        self.assertEqual(180, limits["requiredOuterProcessDeadlineSeconds"])
        self.assertTrue(limits["externalProcessSupervisionRequired"])
        self.assertFalse(limits["externalProcessSupervisionVerified"])
        self.assertFalse(limits["osHardPerArtifactDeadlineGuaranteed"])
        self.assertEqual(0, limits["maximumRedirects"])
        self.assertEqual(0, limits["retries"])

    def test_signal_timer_is_restored_and_existing_timer_is_not_clobbered(self):
        self.timer.stop()
        with patch.object(checker.utilities.signal, "getitimer", return_value=(0.0, 0.0)), \
                patch.object(checker.utilities.signal, "signal", return_value=signal.SIG_DFL) as handler, \
                patch.object(checker.utilities.signal, "setitimer") as timer:
            with self.assertRaisesRegex(ValueError, "fixture"):
                with checker.download_deadline():
                    raise ValueError("fixture")
        self.assertEqual((signal.ITIMER_REAL, 60), timer.call_args_list[0].args)
        self.assertEqual((signal.ITIMER_REAL, 0), timer.call_args_list[-1].args)
        self.assertEqual((signal.SIGALRM, signal.SIG_DFL), handler.call_args_list[-1].args)
        with patch.object(checker.utilities.signal, "getitimer", return_value=(1.0, 0.0)), \
                patch.object(checker.utilities.signal, "setitimer") as timer, self.assertRaises(checker.ParityError):
            with checker.download_deadline():
                self.fail("Existing timer must stop entry")
        timer.assert_not_called()

    def test_main_reports_failed_local_metadata_without_network(self):
        self.pin_path.unlink()
        with patch.object(checker, "make_opener") as opener, redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(1, checker.main(["--root", str(self.root)]))
        opener.assert_not_called()
        self.assertEqual("FAILED", json.loads(stdout.getvalue())["status"])


if __name__ == "__main__":
    unittest.main()
