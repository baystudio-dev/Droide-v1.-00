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

    def test_ndk_release_provenance_is_public_documentation(self):
        examples = (
            'Historical Android NDK r29 recipe.',
            'Record NDK version r29 and NDK release `r28`.',
            '| Historical NDK version | `NDK r29` |',
            '[NDK r29 setup](https://example.test/immutable/toolchain.sh)',
            'NDK\trelease\t`r29`',
            'ndk R29; NDK VERSION R28; NDK RELEASE r27',
        )
        for text in examples:
            with self.subTest(text=text):
                self.add('docs/ARCHITECTURE.md', text)
                self.assertEqual([], policy.audit(self.root))

    def test_ndk_archive_names_and_exact_source_urls_are_preserved(self):
        examples = (
            '`android-ndk-r29-linux.zip`',
            'https://dl.google.com/android/repository/android-ndk-r29-linux.zip',
            'https://dl.google.com/android/repository/android-ndk-r29-darwin.dmg',
            '`android-ndk-r21-windows-x86_64.zip`',
            '`android-ndk-r10-linux-x86.zip`',
        )
        for text in examples:
            with self.subTest(text=text):
                self.add('docs/ARCHITECTURE.md', text)
                self.assertEqual([], policy.audit(self.root))

    def test_bare_milestones_remain_rejected_case_insensitively(self):
        for marker in ('CP1', 'cp123', 'R29', 'r29', 'R1234', 'F114', 'f1234'):
            with self.subTest(marker=marker):
                self.add('docs/ARCHITECTURE.md', marker)
                self.assertEqual(['docs/ARCHITECTURE.md: development milestone in public documentation'], policy.audit(self.root))

    def test_ndk_context_does_not_hide_other_milestones_on_same_line(self):
        examples = (
            'NDK r29; internal R29.',
            'NDK r29 CP123',
            'NDK release r29 F114',
            'R30 android-ndk-r29-linux.zip',
            'android-ndk-r29-linux.zip CP123 F114',
            'NDK r29\nR29',
            '[NDK r29 setup](https://example.test/internal/R29/recipe)',
        )
        for text in examples:
            with self.subTest(text=text):
                self.add('docs/ARCHITECTURE.md', text)
                self.assertEqual(['docs/ARCHITECTURE.md: development milestone in public documentation'], policy.audit(self.root))

    def test_ndk_exception_is_not_a_general_line_or_url_exemption(self):
        examples = (
            'NDK CP123',
            'NDK F114',
            'NDK\nr29',
            'NDK unrelated-text r29',
            'https://example.test/upstream/r29/source.zip',
            'android-ndk-r29-internal.zip',
        )
        for text in examples:
            with self.subTest(text=text):
                self.add('docs/ARCHITECTURE.md', text)
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
