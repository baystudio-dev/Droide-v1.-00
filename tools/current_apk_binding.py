#!/usr/bin/env python3
"""Pure structural comparison for internally generated clean current APK builds.

No CLI reference override; only the orchestrator may use this comparison after
it builds both roots. Equality proves packaging fidelity, not runtime behavior.
"""
import hashlib
import json
import re
import struct
from verify_pty_dex_binding import Dex, DEX_NAME, checked_archive, require
REQUIRED = {'Lcom/baystudio/droide/DroideApplication;',
    'Lcom/baystudio/droide/core/TerminalResizeClientBridge;',
    'Lcom/baystudio/droide/core/TerminalResizeClientBridge$Listener;',
    'Lcom/baystudio/droide/core/NativePtyTerminalSession;',
    'Lcom/baystudio/droide/core/NativeTerminalGeometrySession;',
    'Lcom/baystudio/droide/core/TerminalResizeReceipt;',
    'Lcom/baystudio/droide/core/TerminalViewportGeometry;',
    'Lcom/baystudio/droide/core/TerminalGeometrySnapshot;',
    'Lcom/baystudio/droide/ui/TerminalPaneKt;',
    'Lcom/baystudio/droide/ui/DroideNativeTerminalView;',
    'Lcom/baystudio/droide/ui/TerminalResizeRetry;',
    'Lcom/termux/terminal/TerminalResizeState;',
    'Lcom/termux/terminal/TerminalSession;',
    'Lcom/termux/terminal/TerminalSessionClient;'}
SIGNATURE = re.compile('META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC))$')

def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()

def canonical_entries(entries: dict[str, bytes]) -> dict[str, str]:
    """Bind every packaged class, including compiler-generated consumers."""
    result = {}
    require('classes.dex' in entries, 'No primary DEX')
    for name, data in entries.items():
        if name.endswith('.dex'):
            require(DEX_NAME.fullmatch(name), 'Hidden/unexpected DEX path')
            dex = Dex(data)
            for descriptor in dex.classes:
                require(descriptor not in result, 'Duplicate class across DEX: ' + descriptor)
                require('OwnedJniHostContract' not in descriptor and 'FixtureRunner' not in descriptor,
                    'Host fixture packaged')
                value = dex.canonical_class(descriptor)
                result[descriptor] = sha(json.dumps(value, separators=(',', ':'), ensure_ascii=True).encode())
    require(REQUIRED <= set(result), 'Required live resize/app classes missing')
    return result


def elf_load_alignment(data: bytes) -> dict:
    """Measure ELF LOAD alignment separately from APK ZIP alignment."""
    require(len(data) >= 52 and data[:4] == b'\x7fELF', 'Native provider is not ELF')
    require(data[5] == 1 and data[4] in (1, 2), 'Unsupported ELF encoding')
    elf_class = data[4]
    machine = struct.unpack_from('<H', data, 18)[0]
    if elf_class == 1:
        table = struct.unpack_from('<I', data, 28)[0]
        entry_size, count = struct.unpack_from('<HH', data, 42)
        expected_size = 32
    else:
        require(len(data) >= 64, 'Truncated ELF64 header')
        table = struct.unpack_from('<Q', data, 32)[0]
        entry_size, count = struct.unpack_from('<HH', data, 54)
        expected_size = 56
    require(entry_size == expected_size and 0 < count <= 256, 'Invalid ELF program table')
    require(table + count * entry_size <= len(data), 'Truncated ELF program table')
    loads = []
    for index in range(count):
        offset = table + index * entry_size
        if struct.unpack_from('<I', data, offset)[0] != 1:
            continue
        if elf_class == 1:
            file_offset, address = struct.unpack_from('<II', data, offset + 4)
            alignment = struct.unpack_from('<I', data, offset + 28)[0]
            file_size, memory_size = struct.unpack_from('<II', data, offset + 16)
        else:
            file_offset, address = struct.unpack_from('<QQ', data, offset + 8)
            alignment = struct.unpack_from('<Q', data, offset + 48)[0]
            file_size, memory_size = struct.unpack_from('<QQ', data, offset + 32)
        require(file_size <= memory_size and file_offset + file_size <= len(data),
                'Invalid ELF LOAD extent')
        loads.append({'fileOffset': file_offset, 'virtualAddress': address, 'pAlign': alignment,
                      'fileSize': file_size, 'memorySize': memory_size})
    require(loads, 'ELF provider has no LOAD segment')
    aligned = all(row['pAlign'] >= 16384 and row['pAlign'] & (row['pAlign'] - 1) == 0
                  and row['fileOffset'] % 16384 == row['virtualAddress'] % 16384 for row in loads)
    return {'elfClass': elf_class, 'machine': machine, 'loadSegments': loads,
            'loadAlignment16KiB': aligned, 'deviceCompatibilityEstablished': False}

def compare(primary: bytes, reference: bytes, providers: dict[str, str]) -> dict:
    """Compare internally built APKs; caller must separately verify both signatures."""
    a, b = (checked_archive(primary), checked_archive(reference))
    aa, bb = (canonical_entries(a), canonical_entries(b))
    signature_names = lambda entries: {name for name in entries if SIGNATURE.fullmatch(name)}
    require(signature_names(a) == signature_names(b), 'APK signature entry names differ')

    require(aa == bb, 'Whole-APK class set or register-exact body differs from clean source reference')
    require('AndroidManifest.xml' in a and 'resources.arsc' in a, 'Missing manifest/resources')

    def rest(e):
        return {n: sha(v) for n, v in e.items() if not DEX_NAME.fullmatch(n) and (not SIGNATURE.fullmatch(n))}
    require(rest(a) == rest(b), 'APK manifest/resources/assets/provider content differs')
    actual = {n: sha(v) for n, v in a.items() if n.startswith('lib/') and (not n.endswith('/'))}
    require(actual == providers, 'APK native providers differ from exact composite inventory')
    elf_alignment = {name: elf_load_alignment(a[name]) for name in actual}
    require(not any(('DROIDE-TERMUX-TEST-ONLY' in n for n in a)), 'Host-only marker packaged')
    return {'providerElfAlignment': elf_alignment, 'boundClasses': len(aa),
        'canonicalClassSha256': aa,
        'nonDexEntrySha256': rest(a),
        'apkSha256': sha(primary),
        'independentApkSha256': sha(reference),
        'deviceValidated': False,
        'releaseCompatible': False}


def verify_manifest_policy(badging: str, xml_tree: str) -> None:
    """Check the actual binary-manifest diagnostics, not only Gradle intent."""
    expected = (
        "package: name='com.baystudio.droide'",
        "versionCode='44'",
        "versionName='1.00-current-debug-unadmitted'",
        "sdkVersion:'29'",
        "targetSdkVersion:'36'",
        "application-label:'Droide Current Debug (Unadmitted)'",
        "application-debuggable",
        "launchable-activity: name='com.baystudio.droide.MainActivity'",
    )
    for value in expected:
        require(value in badging, 'Actual manifest policy missing: ' + value)
    roots = []
    stack = []
    for line in xml_tree.splitlines():
        element = re.match(r"^(\s*)E: ([A-Za-z0-9_-]+)", line)
        if element:
            depth = len(element.group(1))
            while stack and stack[-1]['depth'] >= depth:
                stack.pop()
            node = {'name': element.group(2), 'depth': depth, 'attributes': [], 'children': []}
            (stack[-1]['children'] if stack else roots).append(node)
            stack.append(node)
        elif stack and re.match(r"^\s*A: ", line):
            stack[-1]['attributes'].append(line)

    def attribute(node: dict, name: str) -> str:
        pattern = r"\bA: android:" + name + r'(?:\([^)]*\))?="([^"]*)"'
        values = [match.group(1) for line in node['attributes']
                  if (match := re.search(pattern, line))]
        require(len(values) == 1, 'Missing/duplicate manifest attribute: ' + name)
        return values[0]

    manifests = [node for node in roots if node['name'] == 'manifest']
    require(len(manifests) == 1, 'Missing/duplicate manifest root')
    applications = [node for node in manifests[0]['children'] if node['name'] == 'application']
    require(len(applications) == 1, 'Missing/duplicate application element')
    application = applications[0]
    require(attribute(application, 'name') in {
        'com.baystudio.droide.DroideApplication', '.DroideApplication',
    }, 'Unexpected application entrypoint')
    status_name = 'com.baystudio.droide.CURRENT_BUILD_STATUS'
    status_nodes = [node for node in application['children']
                    if node['name'] == 'meta-data' and attribute(node, 'name') == status_name]
    require(len(status_nodes) == 1, 'Missing/duplicate direct application build metadata')
    require(attribute(status_nodes[0], 'value') == 'UNADMITTED_DEVICE_UNVALIDATED',
            'Incorrect current-build status value')
