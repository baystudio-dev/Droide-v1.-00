#!/usr/bin/env python3
"""Explicit deterministic Java-only JGit overlay build/rebuild check. No Gradle/native/network."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile
import verify_jgit_android_compat as check


def build(args):
    root = args.root.resolve()
    inputs, pins, original = check.verify_sources(root)
    for relative, expected in pins['jdk']['files'].items():
        check.require(check.digest((args.jdk / relative).read_bytes()) == expected, 'Unapproved JDK file: ' + relative)
    check.require(check.digest(args.android_jar.read_bytes()) == pins['android_jar_sha256'], 'Wrong Android API36 JAR')
    check.require(check.digest(args.slf4j_jar.read_bytes()) == pins['slf4j_sha256'], 'Wrong SLF4J JAR')
    if args.check:
        check.verify(root)
    vendor, jar_dest, pom_dest = check.paths(root)
    with tempfile.TemporaryDirectory(prefix='droide-jgit-reconstruction-') as directory:
        temp = Path(directory)
        classes_dir = temp / 'classes'; classes_dir.mkdir()
        sourcepath = temp / 'empty-sourcepath'; sourcepath.mkdir()
        home = temp / 'home'; home.mkdir()
        command = [str(args.jdk.resolve() / 'bin/javac')] + check.COMPILER_OPTIONS + ['-sourcepath', str(sourcepath), '-classpath',
                   os.pathsep.join(str(p.resolve()) for p in [check.upstream_path(root), args.android_jar, args.slf4j_jar]),
                   '-d', str(classes_dir)]
        command += [str(vendor / 'src' / (f + '.java')) for f in sorted(check.FAMILIES)]
        # Do not inherit compiler agents/options or annotation processors from the ambient shell.
        environment = {'PATH': os.defpath, 'HOME': str(home), 'LANG': 'C.UTF-8', 'LC_ALL': 'C.UTF-8', 'TZ': 'UTC'}
        subprocess.run(command, check=True, cwd=temp, env=environment)
        classes = {str(p.relative_to(classes_dir)): p.read_bytes() for p in sorted(classes_dir.rglob('*.class'))}
        check.require(all(check.is_family(n) for n in classes), 'Compiler emitted unexpected classes')
        output = {n: b for n, b in original.items() if n != 'META-INF/MANIFEST.MF' and not check.is_family(n) and n not in check.REMOVED and not check.is_signature(n)}
        output.update(classes); output.update(check.extra_entries(root, inputs))
        jar = temp / jar_dest.name
        # STORED avoids zlib-version-dependent compressed output. Metadata/order are fixed.
        with zipfile.ZipFile(jar, 'w', compression=zipfile.ZIP_STORED) as archive:
            for name in sorted(output):
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.create_system = 3; info.external_attr = 0o100644 << 16
                archive.writestr(info, output[name])
        class_hashes = {n: check.digest(b) for n, b in classes.items()}
        inventory = check.check_output(root, jar, inputs, original, class_hashes)
        pom_bytes = (vendor / 'jgit-android-compat.pom').read_bytes()
        receipt = {'schema': 1, 'coordinate': 'com.baystudio.compat:jgit-android-compat:' + check.VERSION,
                   'inputs': inputs, 'toolchain': pins, 'runtime_validated': False,
                   'compiler_options': check.COMPILER_OPTIONS,
                   'classes': class_hashes, 'inventory': inventory,
                   'artifacts': {str(jar_dest.relative_to(root)): check.digest(jar.read_bytes()), str(pom_dest.relative_to(root)): check.digest(pom_bytes)}}
        receipt_bytes = check.json_bytes(receipt)
        if args.check:
            check.require(jar.read_bytes() == jar_dest.read_bytes(), 'Deterministic rebuild differs from published JAR')
            check.require(receipt_bytes == (vendor / 'REBUILD.json').read_bytes(), 'Rebuild receipt differs')
            print('Byte-identical Java-only rebuild verified; no outputs changed')
        else:
            jar_dest.parent.mkdir(parents=True, exist_ok=True)
            jar_dest.write_bytes(jar.read_bytes()); pom_dest.write_bytes(pom_bytes)
            (vendor / 'REBUILD.json').write_bytes(receipt_bytes)
            check.verify(root)
            print('Built Java-only Android JGit derivative; runtime unverified')
        for name, sha in receipt['artifacts'].items():
            print(sha + '  ' + name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--jdk', required=True, type=Path)
    parser.add_argument('--android-jar', required=True, type=Path)
    parser.add_argument('--slf4j-jar', required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--build', action='store_true')
    mode.add_argument('--check', action='store_true', help='Compile in temporary directory and compare; never update output')
    build(parser.parse_args())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print('JGit Java-only build failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
