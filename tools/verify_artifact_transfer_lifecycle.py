#!/usr/bin/env python3
from pathlib import Path
import subprocess
import os
import json
import argparse
import tempfile

parser = argparse.ArgumentParser(description='Verify production artifact transfer generation and cancellation contracts on the JVM')
parser.add_argument('--jars', action='append', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
core = root / 'app/src/main/java/com/baystudio/droide/core'
test = root / 'app/src/test/java/com/baystudio/droide/core'
temporary = tempfile.TemporaryDirectory(prefix='droide-transfer-verification-')
evidence = Path(temporary.name)
classes = evidence / 'worker-classes'
boundaries = evidence / 'host-boundaries'
boundaries.mkdir(exist_ok=True)
jars = sorted({p.resolve() for directory in args.jars for p in Path(directory).glob('*.jar')})
for name in ['kotlin-compiler', 'kotlin-stdlib', 'coroutines-core', 'junit', 'hamcrest']:
    if not any(name in p.name for p in jars):
        raise SystemExit('Missing host dependency: ' + name)
cp = os.pathsep.join(map(str, jars))
transfer = (core / 'UserInitiatedArtifactTransfer.kt').read_text()
store = transfer[transfer.index('internal enum class ArtifactTransferState'):transfer.index(
    '@androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)\nclass UserInitiatedArtifactTransferJobService')]
constructor = '''    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "artifact-transfers"),
        android.os.Process.myPid(), LocalExecutionSubstrate.processIdentity(),
    )
'''
assert constructor in store
store = store.replace(constructor, '')
(boundaries / 'ArtifactTransferStoreHost.kt').write_text(
    'package com.baystudio.droide.core\nimport java.io.File\nimport java.io.FileOutputStream\n'
    'import java.security.MessageDigest\nimport java.util.Properties\nimport java.util.UUID\n' + store)
contracts = (core / 'TrustedArtifactDownloader.kt').read_text().split('// Specs are app-owned data', 1)[1].split('// Keep untrusted input', 1)[0]
(boundaries / 'ArtifactContractsHost.kt').write_text(
    'package com.baystudio.droide.core\nimport java.net.URI\n'
    '// Original descriptor methods retained; URL policy boundary is not exercised by store tests.\n'
    'object NetworkSecurity { fun validatePublicHttpsTarget(url:String) { require(URI(url).scheme=="https") } }\n'
    + contracts[contracts.index('data class TrustedArtifactDownloadProgress'):])
names = ['ArtifactTransferWaiterRegistryTest', 'ArtifactTransferWorkerRegistryTest', 'ArtifactTransferStoreTest']
sources = [core / 'ArtifactTransferWaiterRegistry.kt', core / 'ArtifactTransferWorkerRegistry.kt']
sources += [test / (n + '.kt') for n in names] + sorted(boundaries.glob('*.kt'))
commands = [
    ['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
     '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, sources)],
    ['java', '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
     *['com.baystudio.droide.core.' + n for n in names]],
]
with (evidence / 'transfer-lifecycle.log').open('w') as log:
    for command in commands:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        log.write(result.stdout)
        log.flush()
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.returncode)
(evidence / 'transfer-lifecycle.exit').write_text('0\n')
(evidence / 'worker-commands.json').write_text(json.dumps(commands, indent=2) + '\n')

temporary.cleanup()
