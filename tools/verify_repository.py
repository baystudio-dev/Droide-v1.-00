#!/usr/bin/env python3
"""Validate the public source tree without reading or printing credential values."""
from pathlib import Path
import argparse
import os
import re
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    'README.md', 'CONTRIBUTING.md', 'SECURITY.md', 'LICENSE', 'COPYING', 'COPYING.LESSER',
    'LICENSE_SCOPE.md', 'RELEASE_AND_COMPLIANCE.md', 'docs/ARCHITECTURE.md',
    'docs/BUILDING.md', 'docs/TESTING.md', 'docs/DEPENDENCIES.md', 'docs/RELEASING.md',
    'gradlew', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/verification-metadata.xml',
    'app/gradle.lockfile', '.github/workflows/android-ci.yml',
)
PRIVATE_NAMES = {'local.properties', '.env', 'auth.json', 'signing-credentials.json', 'id_rsa', 'id_ed25519'}
PRIVATE_SUFFIXES = {'.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab', '.idsig'}
CACHE_NAMES = {'.gradle', '.kotlin', '.cxx', '.externalNativeBuild', '.idea', '__pycache__', '.pytest_cache', 'node_modules'}
HISTORY = re.compile(r'\b(?:CP[0-9]{1,4}|R[0-9]{2,4}|F[0-9]{3,4})\b', re.IGNORECASE)
COMPARISON = re.compile(r'\b(?:VS\s*Code|Codex|ChatGPT|Acode|OpenCode)\b', re.IGNORECASE)
SECRET = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=]{40,}|\b(?:ghp_[A-Za-z0-9]{30,}|AIza[A-Za-z0-9_-]{30,}|sk-(?:proj-)?[A-Za-z0-9_-]{32,})\b')
TEXT_SUFFIXES = {'.kt', '.kts', '.java', '.py', '.sh', '.gradle', '.groovy', '.json', '.yaml', '.yml', '.xml', '.md', '.txt', '.properties', '.ts', '.js', '.c', '.h'}


def audit(root: Path, allow_build_output=False):
    failures = []
    for relative in REQUIRED:
        path = root / relative
        if not path.is_file() or not path.stat().st_size:
            failures.append(relative + ': required file missing or empty')
    if (root / 'gradlew').is_file() and not os.access(root / 'gradlew', os.X_OK):
        failures.append('gradlew: executable permission missing')
    for directory, dirs, files in os.walk(root, followlinks=False):
        base = Path(directory)
        for name in list(dirs):
            relative = (base / name).relative_to(root).as_posix()
            if name == '.git':
                dirs.remove(name)
            elif (base / name).is_symlink():
                failures.append(relative + ': symlink is not permitted in a source export')
                dirs.remove(name)
            elif name in CACHE_NAMES or name == 'build' or name.startswith('build-'):
                if not allow_build_output:
                    failures.append(relative + ': generated directory in source export')
                dirs.remove(name)
        for name in files:
            path = base / name
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                failures.append(relative + ': symlink is not permitted in a source export')
                continue
            if name in PRIVATE_NAMES or path.suffix.lower() in PRIVATE_SUFFIXES or (name.startswith('.env.') and name != '.env.example'):
                failures.append(relative + ': private or generated file in source export')
                continue
            if path.suffix not in TEXT_SUFFIXES:
                continue
            raw = path.read_bytes()
            if SECRET.search(raw):
                failures.append(relative + ': possible credential; value omitted')
            public_doc = relative.startswith('docs/') or ('/' not in relative and path.suffix == '.md')
            if public_doc:
                text = raw.decode('utf-8')
                if HISTORY.search(text):
                    failures.append(relative + ': development milestone in public documentation')
                if COMPARISON.search(text):
                    failures.append(relative + ': comparison platform in public documentation')
                for target in re.findall(r'\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
                    target = unquote(target.split('#', 1)[0])
                    if not target or re.match(r'[A-Za-z][A-Za-z0-9+.-]*:', target):
                        continue
                    resolved = (path.parent / target).resolve()
                    if not resolved.is_relative_to(root.resolve()) or not resolved.exists():
                        failures.append(relative + ': broken local documentation link: ' + target)
    return sorted(set(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--allow-build-output', action='store_true')
    args = parser.parse_args()
    failures = audit(args.root, args.allow_build_output)
    if failures:
        raise SystemExit('REPOSITORY_CHECK_FAILED\n' + '\n'.join(failures))
    print('REPOSITORY_CHECK_OK')


if __name__ == '__main__':
    main()
