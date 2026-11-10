"""Reject incomplete or stale-looking host test reports before release acceptance."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_release_tests as runner


class ReleaseReportTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.classes = ['com.baystudio.droide.ExampleTest']

    def report(self, body):
        (self.root / 'TEST-fixture.xml').write_text('<testsuite>' + body + '</testsuite>')

    def case(self, content='', name=None):
        return '<testcase classname="' + (name or self.classes[0]) + '" name="behavior">' + content + '</testcase>'

    def test_complete_executed_class_is_counted(self):
        self.report(self.case())
        self.assertEqual({self.classes[0]: 1}, runner.verify_results(self.root, self.classes))

    def test_missing_and_empty_results_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing or empty'):
            runner.verify_results(self.root, self.classes)
        self.report('')
        with self.assertRaisesRegex(ValueError, 'Missing or empty'):
            runner.verify_results(self.root, self.classes)

    def test_failure_error_and_skip_are_not_success(self):
        for tag in ('failure', 'error', 'skipped'):
            with self.subTest(tag=tag):
                self.report(self.case('<' + tag + '/>'))
                with self.assertRaisesRegex(ValueError, 'failed or skipped'):
                    runner.verify_results(self.root, self.classes)

    def test_unrequested_report_class_is_rejected(self):
        self.report(self.case(name='com.baystudio.droide.OtherTest'))
        with self.assertRaisesRegex(ValueError, 'Unexpected'):
            runner.verify_results(self.root, self.classes)

    def test_every_selected_class_must_run(self):
        self.report(self.case())
        with self.assertRaisesRegex(ValueError, 'Missing or empty'):
            runner.verify_results(self.root, self.classes + ['com.baystudio.droide.MissingTest'])

    def test_manifest_selects_existing_application_test_sources(self):
        self.assertGreater(len(runner.selections()), 0)


if __name__ == '__main__':
    unittest.main()
