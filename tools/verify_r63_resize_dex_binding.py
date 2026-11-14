#!/usr/bin/env python3
"""Register-exact final APK binding for EVERY class in the admitted R63 AAR.

Immutable R46 bounds-checked DEX parsing/canonicalization is reused unchanged.
All original classes, every changed/new nested class, and any exact fresh-R8
compiler synthetics are bound. No caller-selected target/allowlist/reference.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path

from build_pty_dex_reference import regular_bytes
from build_r63_resize_dex_reference import build_fresh_reference
from verify_pty_dex_binding import BindingError, DEX_NAME, Dex, checked_archive, digest, require
import verify_r63_resize_derivative as V

TERMINAL_PREFIX = 'Lcom/termux/terminal/'


def parsed_dexes(entries):
    names = sorted(name for name in entries if DEX_NAME.fullmatch(name))
    require('classes.dex' in names, 'missing primary APK DEX')
    require(all(not name.endswith('.dex') or DEX_NAME.fullmatch(name) for name in entries),
            'unexpected DEX path/name')
    result = {}
    for name in names:
        parsed = Dex(entries[name])
        for descriptor in parsed.classes:
            require(descriptor not in result, 'duplicate class across DEX files: ' + descriptor)
            result[descriptor] = (name, parsed)
    return result


def compare_candidate_classes(actual, reference, candidate_class_paths):
    """Structural comparison only; caller data never substitutes for admission."""
    packaged, expected = parsed_dexes(actual), parsed_dexes(reference)
    required = {'L' + name[:-6] + ';' for name in candidate_class_paths if name.endswith('.class')}
    require(required and required <= set(expected), 'fresh compiler omitted an admitted class')
    # The fresh exact compiler can introduce synthetic classes; compare them too.
    # Reject namespace injection alongside a valid candidate, including extra nested classes.
    require({n for n in packaged if n.startswith(TERMINAL_PREFIX)} ==
            {n for n in expected if n.startswith(TERMINAL_PREFIX)},
            'packaged terminal namespace differs from fresh candidate reference')
    result = {}
    for target in sorted(expected):
        require(target in packaged, 'packaged candidate/synthetic class missing: ' + target)
        name, actual_dex = packaged[target]
        _, reference_dex = expected[target]
        value, wanted = actual_dex.canonical_class(target), reference_dex.canonical_class(target)
        require(value == wanted, 'packaged class differs from fresh candidate D8 reference: ' + target)
        encoded = json.dumps(value, ensure_ascii=True, separators=(',', ':')).encode()
        result[target] = {'dexEntry': name, 'canonicalClassSha256': digest(encoded),
                          'registerExactFullCodeMatch': True}
    return {'classBindingByDescriptor': result, 'boundClassCount': len(result),
            'allAdmittedClassesBound': True, 'freshCompilerSyntheticClassesBound': True}


def verify_apk_binding(root, apk_path, *, d8_jar_path, android_jar_path, java_home):
    root, apk_path = Path(root), Path(apk_path)
    admission = V.verify_derivative_inputs(root)
    apk = regular_bytes(apk_path)
    with build_fresh_reference(root, d8_jar_path, android_jar_path, java_home) as reference:
        require(reference['admission'] == admission, 'R63 reference admission changed')
        result = compare_candidate_classes(checked_archive(apk), reference['dexEntries'],
                    reference['provenance']['candidateClassSha256ByPath'])
        report = dict(result, apkSha256=digest(apk), candidateAarSha256=admission['output']['sha256'],
                      referenceProvenance=reference['provenance'],
                      releaseCompatible=False, deviceValidated=False, runtimeEvidence=None)
    require(regular_bytes(apk_path) == apk, 'APK changed during R63 binding')
    require(V.verify_derivative_inputs(root) == admission, 'R63 admission changed during binding')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=V.ROOT)
    for name in ('apk', 'r8-jar', 'android-jar', 'java-home'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify_apk_binding(args.root, args.apk, d8_jar_path=args.r8_jar,
                                   android_jar_path=args.android_jar, java_home=args.java_home)
    except (BindingError, OSError, ValueError, KeyError) as error:
        raise SystemExit('R63_RESIZE_DEX_BINDING_FAILED: ' + str(error)) from error
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
