#!/usr/bin/env python3
"""Run the actual Groovy graph guard in minimal offline Gradle 8.13 fixtures."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
TASK=':app:assemblePrivateDebugDeviceUnvalidated'
PROPERTY='DROIDE_PRIVATE_DEBUG_DEVICE_UNVALIDATED'


class PrivateDebugGradleGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gradle=os.environ.get('DROIDE_TEST_GRADLE') or shutil.which('gradle')
        if not cls.gradle:
            raise RuntimeError('Set DROIDE_TEST_GRADLE to the installed Gradle 8.13 executable; do not silently skip guard execution')
        cls.temporary=tempfile.TemporaryDirectory(prefix='private-debug-graph-test-')
        cls.root=Path(cls.temporary.name)
        (cls.root/'app').mkdir();(cls.root/'settings.gradle').write_text("rootProject.name='private-guard-fixture'\ninclude ':app'\n")
        (cls.root/'guard.gradle').write_bytes((ROOT/'tools/private-debug-device-unvalidated.gradle').read_bytes())
        (cls.root/'app/build.gradle').write_text("""
apply from: rootProject.file('guard.gradle')
def mandatory = ['preparePrivateDebugDeviceUnvalidated','assembleDebug','verifyTermuxOverlay','verifyJGitAndroidCompat',
                 'mergeDebugNativeLibs','stripDebugDebugSymbols','packageDebug','lintDebug',
                 'mergeDebugNativeDebugMetadata','mergeDebugGlobalSynthetics',
                 'generateDebugLintReportModel','preDebugUnitTestBuild']
mandatory.each { tasks.register(it) { doLast { println 'FIXTURE_TASK_EXECUTED:' + name } } }
tasks.register('assemblePrivateDebugDeviceUnvalidated') {
    dependsOn mandatory.findAll { it != providers.gradleProperty('fixture.omit').orNull }
    doLast { println 'FIXTURE_PRIVATE_COMPLETE' }
}
tasks.register('publish')
tasks.register('assembleRelease')
if (providers.gradleProperty('fixture.extra').isPresent()) {
    tasks.register('unexpectedDebugTask')
    tasks.named('assemblePrivateDebugDeviceUnvalidated') { dependsOn('unexpectedDebugTask') }
}
if (providers.gradleProperty('fixture.disable').isPresent()) {
    tasks.named('verifyTermuxOverlay') { enabled=false }
}
""")

    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()

    def run_gradle(self,tasks=None,options=(),explicit=True):
        args=[self.gradle,'--offline','--daemon','--console=plain','--no-configuration-cache','--no-build-cache',
              '-Pandroid.builder.sdkDownload=false','-Dorg.gradle.jvmargs=-Xmx256m', '-Dorg.gradle.workers.max=1']
        if explicit:args+=['-P'+PROPERTY+'=true']
        args+=list(options)+([TASK] if tasks is None else tasks)
        return subprocess.run(args,cwd=self.root,capture_output=True,text=True,timeout=180)

    def rejected(self,result,phrase):
        self.assertNotEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn(phrase,result.stdout+result.stderr)
        self.assertNotIn('FIXTURE_TASK_EXECUTED:',result.stdout+result.stderr)
        self.assertNotIn('FIXTURE_PRIVATE_COMPLETE',result.stdout+result.stderr)

    def test_positive_actual_guard_executes_complete_exact_graph(self):
        result=self.run_gradle();self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        self.assertIn('FIXTURE_PRIVATE_COMPLETE',result.stdout)
        self.assertIn('Reviewed PRIVATE DEVICE-UNVALIDATED debug graph:',result.stdout)

    def test_release_mixed_publish_alias_and_direct_debug_roots_rejected(self):
        for tasks in ([':app:assembleRelease'],[TASK,':app:assembleRelease'],[':app:publish'],['assemblePrivateDebugDeviceUnvalidated'],[':app:assembleDebug'],[]):
            with self.subTest(tasks=tasks):self.rejected(self.run_gradle(tasks=tasks),'only the exact task path')

    def test_no_cli_opt_in_and_false_are_rejected(self):
        self.rejected(self.run_gradle(explicit=False),'requires explicit')
        self.rejected(self.run_gradle(explicit=False,options=['-P'+PROPERTY+'=false']),'requires explicit')

    def test_gradle_properties_true_does_not_count_as_cli_opt_in(self):
        path=self.root/'gradle.properties';path.write_text(PROPERTY+'=true\n')
        try:self.rejected(self.run_gradle(explicit=False),'requires explicit')
        finally:path.unlink()

    def test_host_mode_collision_rejected(self):
        self.rejected(self.run_gradle(options=['-PDROIDE_HOST_JVM_TESTS_ONLY=true']),'cannot be combined')

    def test_task_exclusion_rejected(self):
        self.rejected(self.run_gradle(options=['-x',':app:verifyTermuxOverlay']),'forbids task exclusions')

    def test_configuration_cache_rejected(self):
        self.rejected(self.run_gradle(options=['--configuration-cache']),'requires --no-configuration-cache')

    def test_build_cache_rejected(self):
        self.rejected(self.run_gradle(options=['--build-cache']),'requires --no-build-cache')

    def test_sdk_download_rejected(self):
        self.rejected(self.run_gradle(options=['-Pandroid.builder.sdkDownload=true']),'requires -Pandroid.builder.sdkDownload=false')

    def test_unexpected_graph_task_rejected_before_execution(self):
        self.rejected(self.run_gradle(options=['-Pfixture.extra=true']),'Forbidden or unreviewed private debug tasks')

    def test_missing_required_provenance_or_lint_rejected(self):
        for name in ('verifyTermuxOverlay','lintDebug'):
            with self.subTest(name=name):self.rejected(self.run_gradle(options=['-Pfixture.omit='+name]),'missing mandatory')

    def test_dependency_verification_cannot_be_disabled(self):
        self.rejected(self.run_gradle(options=['--dependency-verification','off']),'requires strict dependency verification')

    def test_dependency_pins_cannot_be_rewritten(self):
        for args in (['--write-verification-metadata','sha256'],['--write-locks'],['--update-locks','example:example']):
            with self.subTest(args=args):self.rejected(self.run_gradle(options=args),'cannot rewrite dependency')

    def test_disabled_provenance_rejected(self):
        self.rejected(self.run_gradle(options=['-Pfixture.disable=true']),'contains disabled work')


if __name__=='__main__':unittest.main()
