"""Mutation tests for public repository boundaries."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify_repository as policy


class RepositoryPolicyTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for relative in policy.REQUIRED:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture\n')
        (self.root / 'gradlew').chmod(0o755)

    def add(self, relative, text='fixture'):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_complete_source_tree_passes(self):
        self.assertEqual([], policy.audit(self.root))

    def test_required_document_is_not_optional(self):
        (self.root / 'docs/BUILDING.md').unlink()
        self.assertTrue(any('BUILDING.md' in item for item in policy.audit(self.root)))

    def test_executable_wrapper_is_required(self):
        (self.root / 'gradlew').chmod(0o644)
        self.assertTrue(any('executable' in item for item in policy.audit(self.root)))

    def test_signing_material_cannot_hide_in_nested_folder(self):
        self.add('resources/hidden/release.p12')
        self.assertTrue(any('release.p12' in item for item in policy.audit(self.root)))

    def test_output_and_cache_directories_are_rejected_before_reading_contents(self):
        self.add('app/build-source-release/private.txt')
        self.assertTrue(any('generated directory' in item for item in policy.audit(self.root)))
        self.assertEqual([], policy.audit(self.root, allow_build_output=True))

    def test_symlink_cannot_bypass_private_file_boundary(self):
        (self.root / 'alias').symlink_to('/outside/source')
        self.assertTrue(any('symlink' in item for item in policy.audit(self.root)))

    def test_error_never_echoes_secret_value(self):
        secret = 'ghp_' + 'A' * 36
        self.add('config.json', secret)
        failures = policy.audit(self.root)
        self.assertTrue(any('possible credential' in item for item in failures))
        self.assertNotIn(secret, '\n'.join(failures))

    def test_progress_notes_are_rejected_but_original_notices_remain(self):
        marker = 'C' + 'P123'
        self.add('docs/ARCHITECTURE.md', marker)
        self.add('third_party/upstream/NOTICE.md', marker)
        self.assertEqual(['docs/ARCHITECTURE.md: development milestone in public documentation'], policy.audit(self.root))

    def test_comparison_prose_is_not_public_product_documentation(self):
        self.add('docs/ARCHITECTURE.md', 'Co' + 'dex')
        self.assertTrue(any('comparison platform' in item for item in policy.audit(self.root)))

    def test_local_links_are_checked_and_https_links_are_allowed(self):
        self.add('README.md', '[Build](docs/BUILDING.md) [Website](https://example.test)')
        self.assertEqual([], policy.audit(self.root))
        self.add('README.md', '[Old](docs/missing.md)')
        self.assertTrue(any('broken local' in item for item in policy.audit(self.root)))


if __name__ == '__main__':
    unittest.main()
