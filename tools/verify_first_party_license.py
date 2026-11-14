#!/usr/bin/env python3
"""Verify first-party license packaging; not a legal-compliance certification."""
from pathlib import Path
import hashlib

ROOT = Path(__file__).resolve().parents[1]
# Full unmodified GNU texts, already carried by the third-party legal bundle.
TEXT_HASHES = {
    'LGPL-3.0.txt': 'e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118',
    'GPL-3.0.txt': '3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986',
}

def verify(root=ROOT):
    legal = root / 'app/src/main/assets/legal'
    texts = {}
    for name, digest in TEXT_HASHES.items():
        raw = (legal / 'licenses' / name).read_bytes()
        if hashlib.sha256(raw).hexdigest() != digest:
            raise ValueError('changed GNU license text: ' + name)
        texts[name] = raw
    for name, origin in [('LICENSE','LGPL-3.0.txt'),('COPYING.LESSER','LGPL-3.0.txt'),('COPYING','GPL-3.0.txt')]:
        if (root/name).read_bytes() != texts[origin]:
            raise ValueError('missing or changed license copy: ' + name)
    if hashlib.sha256((legal/'licenses/Droide-Previous-MIT.txt').read_bytes()).hexdigest() != '29ca6add6d568f9da387a7e8f416326b7c1c2bc4613f2eec27e61dfb826f55f0':
        raise ValueError('historical MIT notice missing or changed')
    for name in ['SettingsSheet.kt', 'SettingsDiscovery.kt']:
        settings = (root/'app/src/main/java/com/baystudio/droide/ui'/name).read_text()
        if 'LGPLv3' not in settings or 'MIT License' in settings:
            raise ValueError('stale settings license label: '+name)
    scope = (root/'LICENSE_SCOPE.md').read_text()
    if 'SPDX-License-Identifier: LGPL-3.0-only' not in scope or 'remain under their respective licenses' not in scope or 'retain their MIT permissions' not in scope:
        raise ValueError('first-party license scope or prior-grant preservation missing')
    packaged = (legal/'DROIDE_LICENSE.md').read_bytes()
    if b'SPDX-License-Identifier: LGPL-3.0-only' not in packaged:
        raise ValueError('packaged first-party identity missing')
    for raw in texts.values():
        if raw not in packaged:
            raise ValueError('packaged full GNU text missing')
    for p in ['README.md','RELEASE_AND_COMPLIANCE.md','app/src/main/assets/legal/DISTRIBUTION_COMPLIANCE.md']:
        if 'LGPL-3.0-only' not in (root/p).read_text():
            raise ValueError('stale first-party license declaration: '+p)
    return True

if __name__ == '__main__':
    verify()
    print('FIRST_PARTY_LICENSE_PACKAGING_OK (not release compliance certification)')
