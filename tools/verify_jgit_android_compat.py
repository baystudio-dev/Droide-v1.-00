#!/usr/bin/env python3
"""Offline, fail-closed JGit reconstruction provenance check. Never compiles/downloads."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

VERSION = '6.10.1.202505221210-r-droide-reconstruction1'
UPSTREAM_VERSION = '6.10.1.202505221210-r'
COMMIT = '9d12b89dd9e7c17eb958d923dde4001995c47a5c'
UPSTREAM_SHA = '8f0135ca45d00c4da8e7ba2e96d44e1ade452bf279d79ca4eb54921e8f27952c'
SOURCE_SHA = '0e784465d1c6d03629327b35800ac2e80288f85e601538aa90fb28c2fdef5310'
ANDROID_SHA = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'
SLF4J_SHA = 'd3ef575e3e4979678dc01bf1dcce51021493b4d11fb7f1be8ad982877c16a1c0'
FAMILIES = (
    'org/eclipse/jgit/internal/storage/file/GC',
    'org/eclipse/jgit/internal/storage/file/WindowCache',
    'org/eclipse/jgit/storage/file/WindowCacheStats',
    'org/eclipse/jgit/transport/HttpAuthMethod',
)
REMOVED = {
    'org/eclipse/jgit/util/Monitoring.class',
    'org/eclipse/jgit/util/GSSManagerFactory.class',
    'org/eclipse/jgit/util/GSSManagerFactory$DefaultGSSManagerFactory.class',
    'org/eclipse/jgit/util/GSSManagerFactory$SunGSSManagerFactory.class',
}
FORBIDDEN = (b'java/lang/ProcessHandle', b'java/lang/management/', b'javax/management/', b'org/ietf/jgss/')
META = 'META-INF/droide-jgit/'
COMPILER_OPTIONS = ['--release', '11', '-proc:none', '-implicit:none', '-encoding', 'UTF-8', '-g:source,lines']


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True) + '\n').encode()


def paths(root):
    vendor = root / 'third_party/jgit'
    dest = root / 'third_party/maven/com/baystudio/compat/jgit-android-compat' / VERSION
    name = 'jgit-android-compat-' + VERSION
    return vendor, dest / (name + '.jar'), dest / (name + '.pom')


def entries(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries: ' + str(path))
        return {i.filename: jar.read(i) for i in jar.infolist() if not i.is_dir()}


def is_family(name):
    return name.endswith('.class') and any(name == f + '.class' or name.startswith(f + '$') for f in FAMILIES)


def is_signature(name):
    return name.startswith('META-INF/') and '/' not in name[len('META-INF/'):] and name.upper().endswith(('.SF', '.RSA', '.DSA', '.EC'))


def upstream_path(root):
    return paths(root)[0] / 'upstream' / ('org.eclipse.jgit-' + UPSTREAM_VERSION + '.jar')


def source_path(root):
    return paths(root)[0] / 'upstream' / ('org.eclipse.jgit-' + UPSTREAM_VERSION + '-sources.jar')


def input_hashes(root):
    vendor = paths(root)[0]
    files = [p for p in vendor.rglob('*') if p.is_file() and p.name != 'REBUILD.json']
    files += [root / 'tools/build_jgit_android_compat.py', root / 'tools/verify_jgit_android_compat.py']
    return {str(p.relative_to(root)): digest(p.read_bytes()) for p in sorted(files)}


def verify_sources(root):
    vendor = paths(root)[0]
    require(digest(upstream_path(root).read_bytes()) == UPSTREAM_SHA, 'Upstream JAR differs from fixed pin')
    require(digest(source_path(root).read_bytes()) == SOURCE_SHA, 'Source JAR differs from fixed pin')
    original = entries(upstream_path(root))
    sources = entries(source_path(root))
    require(('git-commit-id: ' + COMMIT).encode() in original['META-INF/MANIFEST.MF'], 'Wrong upstream commit')
    proof = json.loads((vendor / 'SOURCE_COMMIT_VERIFICATION.json').read_text())
    require(proof['commit'] == COMMIT and proof['status'] == 'verified', 'Independent source-to-commit proof missing')
    expected = {f + '.java' for f in FAMILIES}
    actual = {str(p.relative_to(vendor / 'src')) for p in (vendor / 'src').rglob('*.java')}
    require(actual == expected and set(proof['files']) == expected, 'Unexpected overlay source set')
    patch = []
    for name in sorted(expected):
        data = sources[name]
        item = proof['files'][name]
        require(item['sha256'] == digest(data), 'Source receipt SHA mismatch: ' + name)
        require(item['git_blob_sha1'] == hashlib.sha1(('blob ' + str(len(data)) + '\0').encode() + data).hexdigest(), 'Git blob mismatch')
        require(item['commit_source_url'] == 'https://eclipse.googlesource.com/jgit/jgit/+/' + COMMIT + '/org.eclipse.jgit/src/' + name + '?format=TEXT', 'Unpinned commit source URL')
        saved = 'upstream/commit-' + COMMIT + '/' + name
        require(item['saved_commit_source'] == saved and item['http_status'] == 200, 'Missing retained commit source receipt')
        require((vendor / saved).read_bytes() == data, 'Retained official commit source differs')
        modified = (vendor / 'src' / name).read_text()
        patch += difflib.unified_diff(data.decode().splitlines(True), modified.splitlines(True), fromfile='upstream/' + name, tofile='android/' + name)
    require(''.join(patch) == (vendor / 'patches/0001-android-platform-compatibility.patch').read_text(), 'Overlay patch is stale')
    require((vendor / 'UPSTREAM_ABOUT.html').read_bytes() == sources['about.html'] == original['about.html'], 'Upstream notices changed')
    pins = json.loads((vendor / 'TOOLCHAIN_PINS.json').read_text())
    require(pins['android_api'] == 36 and pins['android_jar_sha256'] == ANDROID_SHA, 'Android SDK pin mismatch')
    require(pins['slf4j_version'] == '1.7.36' and pins['slf4j_sha256'] == SLF4J_SHA, 'SLF4J pin mismatch')
    require(set(pins['jdk']['files']) == {'bin/java', 'bin/javac', 'lib/modules', 'release'}, 'JDK pin inventory mismatch')
    pom = ET.fromstring((vendor / 'jgit-android-compat.pom').read_bytes())
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    require(pom.findtext('m:groupId', namespaces=ns) == 'com.baystudio.compat', 'Wrong derivative group')
    require(pom.findtext('m:artifactId', namespaces=ns) == 'jgit-android-compat', 'Wrong derivative artifact')
    require(pom.findtext('m:version', namespaces=ns) == VERSION, 'Wrong derivative version')
    deps = {(d.findtext('m:groupId', namespaces=ns), d.findtext('m:artifactId', namespaces=ns), d.findtext('m:version', namespaces=ns)) for d in pom.findall('m:dependencies/m:dependency', ns)}
    require(deps == {('com.googlecode.javaewah', 'JavaEWAH', '1.2.3'), ('org.slf4j', 'slf4j-api', '1.7.36'), ('commons-codec', 'commons-codec', '1.17.0')}, 'Dependency closure changed')
    return input_hashes(root), pins, original


def manifest():
    headers = [('Manifest-Version', '1.0'), ('Implementation-Title', 'Droide Android JGit reconstruction'),
               ('Implementation-Vendor', 'Droide compatibility maintainers'), ('Implementation-Version', VERSION),
               ('Automatic-Module-Name', 'com.baystudio.compat.jgit.android'),
               ('Upstream-Coordinate', 'org.eclipse.jgit:org.eclipse.jgit:' + UPSTREAM_VERSION),
               ('Upstream-Git-Commit', COMMIT), ('Upstream-Jar-SHA256', UPSTREAM_SHA)]
    lines = []
    for key, value in headers:
        line = key + ': ' + value
        while len(line.encode()) > 70:
            lines.append(line[:70]); line = ' ' + line[70:]
        lines.append(line)
    return ('\r\n'.join(lines) + '\r\n\r\n').encode()


def extra_entries(root, inputs):
    vendor = paths(root)[0]
    return {'META-INF/MANIFEST.MF': manifest(),
            META + 'UPSTREAM.MF': entries(upstream_path(root))['META-INF/MANIFEST.MF'],
            META + 'NOTICE.md': (vendor / 'NOTICE.md').read_bytes(),
            META + 'PROVENANCE.json': json_bytes({'schema': 1, 'coordinate': 'com.baystudio.compat:jgit-android-compat:' + VERSION,
                 'upstream_jar_sha256': UPSTREAM_SHA, 'upstream_sources_sha256': SOURCE_SHA, 'upstream_commit': COMMIT,
                 'source_inputs': inputs, 'runtime_validated': False})}


def check_output(root, jar_path, inputs, original, classes):
    output = entries(jar_path)
    extras = extra_entries(root, inputs)
    kept = {n: b for n, b in original.items() if n != 'META-INF/MANIFEST.MF' and not is_family(n) and n not in REMOVED and not is_signature(n)}
    require(set(output) == set(kept) | set(classes) | set(extras), 'Derivative entry inventory mismatch')
    require(all(output[n] == b for n, b in kept.items()), 'Unrelated upstream entry changed')
    require(all(output[n] == b for n, b in extras.items()), 'Derivative provenance/manifest differs')
    require(all(f + '.class' in classes for f in FAMILIES), 'Missing replacement outer class')
    require(all(is_family(n) for n in classes), 'Unexpected replacement class')
    require('org/eclipse/jgit/transport/HttpAuthMethod$Negotiate.class' not in classes, 'Negotiate implementation retained')
    for n, b in output.items():
        if n.endswith('.class'):
            require(not any(ref in b for ref in FORBIDDEN), 'Unavailable Android API reference: ' + n)
        if n in classes:
            require(digest(b) == classes[n], 'Replacement class hash mismatch: ' + n)
            require(b[:4] == b'\xca\xfe\xba\xbe' and struct.unpack('>H', b[6:8])[0] == 55, 'Replacement not Java11: ' + n)
    return {'unchanged_file_entries': len(kept), 'replacement_classes': len(classes),
            'removed_entries': sorted(set(original) - set(output)), 'added_entries': sorted(set(output) - set(original))}


def verify(root):
    inputs, pins, original = verify_sources(root)
    vendor, jar, pom = paths(root)
    receipt_path = vendor / 'REBUILD.json'
    require(receipt_path.is_file(), 'No reconstructed derivative receipt; source-only state is not build-ready')
    receipt = json.loads(receipt_path.read_text())
    require(receipt['schema'] == 1 and receipt['coordinate'] == 'com.baystudio.compat:jgit-android-compat:' + VERSION, 'Wrong receipt identity')
    require(receipt['inputs'] == inputs and receipt['toolchain'] == pins, 'Stale source/toolchain receipt')
    require(receipt['compiler_options'] == COMPILER_OPTIONS, 'Compiler options receipt mismatch')
    require(receipt['runtime_validated'] is False, 'Build receipt must not assert runtime certification')
    require(receipt['artifacts'] == {str(p.relative_to(root)): digest(p.read_bytes()) for p in [jar, pom]}, 'Artifact SHA mismatch')
    require(pom.read_bytes() == (vendor / 'jgit-android-compat.pom').read_bytes(), 'Published POM differs')
    require(receipt['inventory'] == check_output(root, jar, inputs, original, receipt['classes']), 'Entry inventory receipt mismatch')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--source-only', action='store_true', help='Check source inputs only; NEVER use as a build gate')
    args = parser.parse_args()
    if args.source_only:
        verify_sources(args.root.resolve())
        print('SOURCE INPUTS ONLY verified; derivative and runtime remain unverified')
    else:
        verify(args.root.resolve())
        print('JGit derivative/source provenance verified; runtime unverified')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, ET.ParseError) as error:
        print('JGit provenance check failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
