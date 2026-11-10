import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_terminal_engine_regressions as runner


class RegressionInputGuards(unittest.TestCase):
    def test_fixture_family_includes_nested_classes_only_in_exact_package(self):
        families = ('com/termux/terminal/TerminalResizeFixtureRunner',)
        self.assertTrue(runner.fixture_entry(families[0] + '.class', families))
        self.assertTrue(runner.fixture_entry(families[0] + '$1.class', families))
        for name in ('elsewhere/TerminalResizeFixtureRunner.class', families[0] + 'Decoy.class',
                     families[0] + '$1.java', 'com/termux/terminal/TerminalResizeState.class'):
            self.assertFalse(runner.fixture_entry(name, families), name)

    def test_symlink_input_and_symlinked_parent_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'real').mkdir()
            (root / 'link').symlink_to(root / 'real', target_is_directory=True)
            for path in (root / 'link', root / 'link' / 'missing'):
                with self.assertRaises(ValueError): runner.clean_path(path)

    def test_parent_traversal_rejected(self):
        with self.assertRaises(ValueError): runner.clean_path(Path('/tmp/a/../b'))

    def test_regular_absolute_path_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'future'
            self.assertEqual(path, runner.clean_path(path))


if __name__ == '__main__':
    unittest.main()
