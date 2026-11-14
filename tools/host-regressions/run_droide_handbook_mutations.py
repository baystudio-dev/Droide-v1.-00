#!/usr/bin/env python3
"""Run isolated negative controls against an authoritative snapshotted handbook suite."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('--snapshot', type=Path, required=True)
p.add_argument('--toolchain', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
a.output.mkdir(parents=True, exist_ok=False)
core = Path('app/src/main/java/com/baystudio/droide/core')
mutations = [
    ('selection_whitelist', 'DroideHandbookPolicy.kt', 'require(section in sections)', 'require(true)'),
    ('section_bound', 'DroideHandbookPolicy.kt', 'require(text.length <= MAX_SECTION_CHARS)', 'require(true)'),
    ('group_bound', 'DroideHandbookPolicy.kt', 'require(count <= DroideHandbookPolicy.MAX_GROUP_CHARS - chars)', 'require(true)'),
    ('image_integrity', 'AgentDroideHelp.kt', 'hash == selected.sha256 && ', ''),
    ('stale_after_stage', 'AgentDroideHelp.kt',
     'val images = VisionSupport.stage(c.workDir, listOf(imported.path), c.llmConfig, ownerId = owner)\n                    requireCurrent()',
     'val images = VisionSupport.stage(c.workDir, listOf(imported.path), c.llmConfig, ownerId = owner)'),
    ('gated_bootstrap', 'AgentRuntimePrompt.kt', 'if (tools != null && "droide_help" in toolNames(tools))', 'if (true)'),
]
results = []
for name, file, before, after in mutations:
    directory = a.output / name
    fixture = directory / 'source-fixture'
    shutil.copytree(a.snapshot, fixture)
    path = fixture / core / file
    original = path.read_text()
    assert original.count(before) == 1, (name, original.count(before))
    changed = original.replace(before, after)
    path.write_text(changed)
    runner = fixture / 'tools/host-regressions/run_droide_handbook_handler.py'
    command = ['python3', str(runner), '--root', str(fixture), '--toolchain', str(a.toolchain), '--output', str(directory / 'run')]
    completed = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    (directory / 'control.log').write_text(completed.stdout)
    result = json.loads((directory / 'run/RESULT.json').read_text())
    killed = (len(result['steps']) == 2 and result['steps'][0]['exit_code'] == 0 and result['steps'][1]['exit_code'] != 0)
    row = {'name': name, 'file': str(core / file), 'before': before, 'after': after,
           'original_sha256': hashlib.sha256(original.encode()).hexdigest(), 'mutated_sha256': hashlib.sha256(changed.encode()).hexdigest(),
           'command': command, 'compile_passed': result['steps'][0]['exit_code'] == 0, 'test_rejected_mutation': killed,
           'process_exit_code': completed.returncode}
    results.append(row)
    print(f'{name}: compiled={row["compile_passed"]} rejected={killed}', flush=True)
    (a.output / 'RESULT.json').write_text(json.dumps({'scope': 'Isolated modified copies only; no production writes. A rejection requires compilation success and a failing behavioral test.', 'controls': results}, indent=2) + '\n')
assert all(v['test_rejected_mutation'] for v in results), 'A negative control survived or failed compilation'
