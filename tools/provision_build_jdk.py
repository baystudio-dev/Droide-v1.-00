#!/usr/bin/env python3
"""Provision the exact release compiler into a new directory outside the source tree."""
import argparse
import json
from pathlib import Path
import shutil
import tarfile
import urllib.request

import terminal_jdk_v2 as vendor

ROOT = Path(__file__).resolve().parents[1]


def provision(output, archive=None):
    output = vendor.safe_path(output)
    vendor.require(output != ROOT and ROOT not in output.parents and output not in ROOT.parents,
                   'JDK output must be outside the source tree')
    vendor.require(not output.exists(), 'JDK output already exists; choose a fresh path')
    manifest = json.loads((ROOT / 'tools/terminal-engine-v2-jdk.json').read_text())
    output.mkdir(parents=True, mode=0o755)
    destination = output / 'vendor.tar.gz'
    if archive:
        with vendor.safe_path(archive).open('rb') as source, destination.open('xb') as target:
            shutil.copyfileobj(source, target, 1024 * 1024)
    else:
        request = urllib.request.Request(manifest['archiveUrl'], headers={'User-Agent': 'Droide-Build'})
        with urllib.request.urlopen(request, timeout=60) as response, destination.open('xb') as target:
            count = 0
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                vendor.require(count <= 256 * 1024 * 1024, 'JDK download exceeded archive bound')
                target.write(chunk)
    vendor.archive_inventory(destination, manifest)
    # Extraction is allowed only after every archive member, symlink and hash matches the pinned manifest.
    with tarfile.open(destination, 'r:gz') as source:
        source.extractall(output, filter='data')
    java_home = output / ('jdk-' + manifest['version'])
    vendor.installed_inventory(java_home, manifest)
    return java_home


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--archive', type=Path)
    args = parser.parse_args()
    print(provision(args.output, args.archive))


if __name__ == '__main__':
    main()
