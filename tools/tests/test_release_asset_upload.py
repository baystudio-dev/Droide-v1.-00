"""Release upload boundary and content-integrity regressions."""
import base64
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('release_upload', Path(__file__).resolve().parents[2] / '.github/scripts/upload_release_assets.py')
upload = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upload)


class ReleaseAssetUploadTests(unittest.TestCase):
    def row(self, content=b'public metadata'):
        return {'name': 'release-verification.json', 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(), 'base64': base64.b64encode(content).decode()}

    def test_private_keys_and_path_traversal_are_rejected(self):
        for name in ('release.p12', 'signing.keystore', 'signing-credentials.json', 'auth.json', '../release.json', '/release.json'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                upload.validate_manifest(json.dumps([{**self.row(), 'name': name}]))

    def test_duplicate_names_are_rejected(self):
        with self.assertRaises(ValueError):
            upload.validate_manifest(json.dumps([self.row(), self.row()]))

    def test_cleartext_and_unapproved_redirect_targets_are_rejected(self):
        for url in ('http://codeload.github.com/a', 'https://127.0.0.1/a', 'https://example.org/a', 'https://codeload.github.com.evil.test/a'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                upload.validate_url(url)

    def test_verified_inline_content_is_preserved(self):
        row = upload.validate_manifest(json.dumps([self.row()]))[0]
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(b'public metadata', upload.receive_asset(row, Path(directory)).read_bytes())

    def test_content_hash_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            upload.receive_asset({**self.row(), 'sha256': '0' * 64}, Path(directory))

    def test_content_size_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            upload.receive_asset({**self.row(), 'bytes': 1}, Path(directory))

    def test_private_pem_is_rejected_even_with_matching_hash(self):
        row = {**self.row(b'-----BEGIN PRIVATE KEY-----'), 'name': 'certificate.pem'}
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            upload.receive_asset(row, Path(directory))

    def test_existing_asset_requires_matching_server_digest_and_size(self):
        row = self.row()
        asset = {'state': 'uploaded', 'size': row['bytes'], 'digest': 'sha256:' + row['sha256']}
        self.assertTrue(upload.existing_matches(asset, row))
        self.assertFalse(upload.existing_matches({**asset, 'digest': None}, row))
        self.assertFalse(upload.existing_matches({**asset, 'size': 1}, row))

    def draft_api(self, draft=True):
        row = self.row()
        env = {'GH_REPO': 'owner/project', 'GH_TOKEN': 'test-token', 'RELEASE_ID': '123', 'RELEASE_TAG': 'v1.00', 'RELEASE_COMMIT': 'a' * 40, 'RELEASE_ASSETS': json.dumps([row])}
        release = {'id': 123, 'draft': draft, 'tag_name': 'v1.00', 'assets': [{'name': row['name'], 'state': 'uploaded', 'size': row['bytes'], 'digest': 'sha256:' + row['sha256']}]}
        replies = {'https://api.github.com/repos/owner/project/commits/v1.00': {'sha': 'a' * 40}, 'https://api.github.com/repos/owner/project/releases/123': release}
        return env, lambda request, **kwargs: io.BytesIO(json.dumps(replies[request.full_url]).encode())

    def test_draft_is_resolved_by_id_without_published_tag_lookup(self):
        env, reply = self.draft_api()
        with patch.dict(upload.os.environ, env), patch.object(upload.urllib.request, 'urlopen', side_effect=reply) as request, redirect_stdout(io.StringIO()):
            upload.main()
        self.assertEqual(2, request.call_count)

    def test_published_release_is_rejected_before_receiving_files(self):
        env, reply = self.draft_api(draft=False)
        with patch.dict(upload.os.environ, env), patch.object(upload.urllib.request, 'urlopen', side_effect=reply), patch.object(upload, 'receive_asset') as receive:
            with self.assertRaisesRegex(ValueError, 'intended draft'):
                upload.main()
            receive.assert_not_called()


if __name__ == '__main__':
    unittest.main()
