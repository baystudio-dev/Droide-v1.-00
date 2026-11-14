#!/usr/bin/env python3
"""Fresh exact-R8 DEX reference for the independently admitted R63 candidate.

Reuses the immutable R46 producer's pinned JDK17/API36/R8 invocation and temporary
input-race checks. No downloaded tool, SDK D8 fallback or supplied reference is
accepted. A fresh reference covers the entire candidate AAR, including compiler
synthetics, rather than just the changed TerminalSession outer class.
"""
from contextlib import contextmanager
from pathlib import Path

import build_pty_dex_reference as R46
from verify_pty_dex_binding import checked_archive, digest, require
import verify_r63_resize_derivative as V


@contextmanager
def build_fresh_reference(root, r8_jar, android_jar, java_home):
    root = Path(root)
    admission = V.verify_derivative_inputs(root)
    candidate = root / V.AAR
    original = R46.regular_bytes(candidate)
    entries = checked_archive(checked_archive(original)['classes.jar'])
    class_hashes = {name: digest(data) for name, data in sorted(entries.items()) if name.endswith('.class')}
    require(class_hashes == {name: sha for name, sha in admission['aarInspection']['classSha256ByPath'].items()
                           if name.endswith('.class')}, 'reference AAR class set differs from R63 admission')
    with R46.build_fresh_reference(candidate, Path(r8_jar), Path(android_jar), Path(java_home)) as fresh:
        require(fresh['provenance']['candidateAarSha256'] == admission['output']['sha256'],
                'reference source differs from R63 admission')
        provenance = dict(fresh['provenance'], kind='FRESH_R63_RESIZE_D8_REFERENCE',
                          candidateClassSha256ByPath=class_hashes,
                          r63ReferenceBuilderSha256=digest(Path(__file__).read_bytes()))
        yield {'dexEntries': fresh['dexEntries'], 'provenance': provenance, 'admission': admission}
    require(R46.regular_bytes(candidate) == original, 'R63 candidate changed after reference generation')
