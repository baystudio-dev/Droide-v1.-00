"""Negative controls for the current CLI static source connection gate."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SERVER = 'app/src/main/java/com/baystudio/droide/core/DroideCliServer.kt'
FILES = ['app/src/main/jniLibs/arm64-v8a/libdroide_cli.so', 'app/build.gradle.kts',
         'app/src/main/cpp/droide_cli_client.c', SERVER]
FILES += ['app/src/main/java/com/baystudio/droide/core/'+name+'.kt' for name in
          ('DroideCliClientInstaller', 'DroideCliWireProtocol', 'CliEndpointPublication', 'LocalExecutionSubstrate')]


class CurrentCliGuards(unittest.TestCase):
    def run_gate(self, mutation=None, target_source=SERVER):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in FILES + ['tools/verify_current_droide_cli_v1.py']:
                target = root/name; target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT/name, target)
            if mutation:
                server = root/target_source; server.write_text(mutation(server.read_text()))
            return subprocess.run([sys.executable, '-B', str(root/'tools/verify_current_droide_cli_v1.py')],
                                  cwd=root, capture_output=True, text=True, timeout=30)

    def test_current_stronger_guard_and_all_existing_contracts_pass(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout+result.stderr)
        self.assertIn('CURRENT_DROIDE_CLI_CONTRACT_V1_OK', result.stdout)

    def test_bad_connections_fail(self):
        changes = {
            'missing-admission': lambda s:s.replace(' && ApplicationWorkAdmission.isOpen', ''),
            'inverted-admission': lambda s:s.replace('&& ApplicationWorkAdmission.isOpen', '&& !ApplicationWorkAdmission.isOpen'),
            'or-admission': lambda s:s.replace('&& ApplicationWorkAdmission.isOpen', '|| ApplicationWorkAdmission.isOpen'),
            'wrong-owner': lambda s:s.replace('active === it', 'active !== it'),
            'closed-session': lambda s:s.replace('!it.isClosed', 'it.isClosed'),
            'disconnected-publish': lambda s:s.replace('replace = { it.endpoint.publishPrepared() }', 'replace = { Unit }'),
            'decoy-comment': lambda s:s.replace(' && ApplicationWorkAdmission.isOpen', '') +
                '\n// isCurrent = { active === it && !it.isClosed && ApplicationWorkAdmission.isOpen },\n// replace = { it.endpoint.publishPrepared() },\n',
        }
        for name, mutation in changes.items():
            with self.subTest(name=name):
                result = self.run_gate(mutation)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('CLI server publication is disconnected', result.stderr)

    def test_atomic_publication_requirement_is_retained(self):
        result = self.run_gate(lambda s:s.replace('ATOMIC_MOVE, REPLACE_EXISTING', 'REPLACE_EXISTING'),
                               'app/src/main/java/com/baystudio/droide/core/CliEndpointPublication.kt')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no longer atomically publishes', result.stderr)

    def test_historical_gate_is_unchanged(self):
        digest = hashlib.sha256((ROOT/'tools/verify_droide_cli_client.py').read_bytes()).hexdigest()
        self.assertEqual('d3b636ec6a5292a3c6a43be8ce1a596622b128c09ad2ac682f704eea0e23ca28', digest)


if __name__ == '__main__':
    unittest.main()
