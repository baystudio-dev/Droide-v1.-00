#!/usr/bin/env python3
"""Pre-execution guards and binary-manifest policy fixtures; no Gradle build."""
import argparse
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_current_debug_apk as recipe
import current_apk_binding as binding


class RecipeGuards(unittest.TestCase):
    def arguments(self, root):
        return argparse.Namespace(
            root=recipe.ROOT, output=root / 'output', composite=root / 'composite',
            java_home=root / 'jdk', sdk=root / 'sdk',
            gradle=root / 'gradle/bin/gradle', dependency_cache=root / 'modules-2',
            gradle_archive=root / 'gradle.zip', robolectric_dir=root / 'robolectric',
        )

    def test_wrong_import_root_rejected_before_commands(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            arguments = self.arguments(base)
            with mock.patch.object(recipe.inputs, '__file__', str(base / 'fake.py')):
                with mock.patch.object(recipe, 'run') as launch:
                    with self.assertRaisesRegex(ValueError, 'Imported verifier'):
                        recipe.build(arguments)
                    launch.assert_not_called()
            self.assertFalse(arguments.output.exists())

    def test_output_inside_gradle_distribution_rejected_without_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            arguments = self.arguments(base)
            (base / 'gradle/lib').mkdir(parents=True)
            marker = base / 'gradle/lib/untouched'
            marker.write_text('original')
            arguments.output = base / 'gradle/new-output'
            with mock.patch.object(recipe, 'run') as launch:
                with self.assertRaisesRegex(ValueError, 'overlaps'):
                    recipe.build(arguments)
                launch.assert_not_called()
            self.assertEqual('original', marker.read_text())
            self.assertFalse(arguments.output.exists())

    def manifest(self):
        badging = """package: name='com.baystudio.droide' versionCode='44' versionName='1.00-current-debug-unadmitted'
sdkVersion:'29'
targetSdkVersion:'36'
application-label:'Droide Current Debug (Unadmitted)'
application-debuggable
launchable-activity: name='com.baystudio.droide.MainActivity'
"""
        xml = """E: manifest
  E: application
    A: android:name(0x01010003)="com.baystudio.droide.DroideApplication"
    E: meta-data
      A: android:name(0x01010003)="com.baystudio.droide.CURRENT_BUILD_STATUS"
      A: android:value(0x01010024)="UNADMITTED_DEVICE_UNVALIDATED"
"""
        return badging, xml

    def test_manifest_positive(self):
        binding.verify_manifest_policy(*self.manifest())

    def test_actual_manifest_drift_rejected(self):
        badging, xml = self.manifest()
        for before, after in [
            ('application-debuggable', ''), ('MainActivity', 'StaleActivity'),
            ("sdkVersion:'29'", "sdkVersion:'28'"),
            ('1.00-current-debug-unadmitted', '1.00'),
            ('Droide Current Debug (Unadmitted)', 'Droide'),
        ]:
            with self.subTest(before=before), self.assertRaises(Exception):
                binding.verify_manifest_policy(badging.replace(before, after), xml)
        with self.assertRaises(Exception):
            binding.verify_manifest_policy(badging, xml.replace('UNADMITTED_DEVICE_UNVALIDATED', 'READY'))
        with self.assertRaises(Exception):
            binding.verify_manifest_policy(badging, xml + xml)
        displaced = xml.replace('    E: meta-data', '  E: meta-data')
        with self.assertRaises(Exception):
            binding.verify_manifest_policy(badging, displaced)
        decoy = xml.replace('    A: android:name(0x01010003)="com.baystudio.droide.DroideApplication"',
                            '    E: activity\n      A: android:name(0x01010003)="com.baystudio.droide.DroideApplication"')
        with self.assertRaises(Exception):
            binding.verify_manifest_policy(badging, decoy)



if __name__ == '__main__':
    unittest.main()
