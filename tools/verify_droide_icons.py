#!/usr/bin/env python3
"""Fail-closed static branding check. Stdlib only; does not build an APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--overlay', type=Path)
    args = parser.parse_args()
    def file(path):
        candidate = args.overlay / path if args.overlay else args.source_root / path
        return candidate if candidate.exists() else args.source_root / path
    a = '{http://schemas.android.com/apk/res/android}'
    res = 'app/src/main/res/'
    app = ET.parse(file('app/src/main/AndroidManifest.xml')).getroot().find('application')
    assert app.get(a+'icon') == '@mipmap/ic_launcher'
    assert app.get(a+'roundIcon') == '@mipmap/ic_launcher_round'
    for node in app.iter():
        if node is not app:
            assert a+'icon' not in node.attrib, 'An activity/service overrides the brand'
    for version in (26, 33):
        for name in ('ic_launcher', 'ic_launcher_round'):
            root = ET.parse(file(res+f'mipmap-anydpi-v{version}/{name}.xml')).getroot()
            assert root.tag == 'adaptive-icon'
            assert root.find('background').get(a+'drawable') == '@color/droide_launcher_background'
            assert root.find('foreground').get(a+'drawable') == '@drawable/ic_droide_foreground'
            assert (root.find('monochrome') is not None) == (version >= 33)
            if version >= 33: assert root.find('monochrome').get(a+'drawable') == '@drawable/ic_droide_monochrome'
    paths = {}
    for name, size in [('ic_droide_foreground', 108), ('ic_droide_monochrome', 108), ('ic_stat_droide', 24)]:
        vector = ET.parse(file(res+f'drawable/{name}.xml')).getroot()
        assert vector.tag == 'vector'
        assert vector.get(a+'width') == f'{size}dp'
        assert vector.get(a+'height') == f'{size}dp'
        assert vector.get(a+'viewportWidth') == str(size)
        assert vector.get(a+'viewportHeight') == str(size)
        assert len(vector) == 1 and vector[0].tag == 'path', 'No square background or clip mask'
        path = vector[0]
        paths[name] = path.get(a+'pathData')
        assert paths[name].count('M') == 3 and paths[name].count('Z') == 3
        if name != 'ic_droide_foreground': assert path.get(a+'fillColor') == '#FFFFFFFF'
        coords = [(float(x), float(y)) for x,y in re.findall(r'(-?\d+\.\d+),(-?\d+\.\d+)', paths[name])]
        assert len(coords) > 100
        for x,y in coords:
            if size == 108: assert (x-54)**2+(y-54)**2 < 33**2
            else: assert 2-0.001 <= x <= 22.001 and 2-0.001 <= y <= 22.001
    assert paths['ic_droide_foreground'] == paths['ic_droide_monochrome']
    for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
        for name in ('ic_launcher','ic_launcher_round'):
            data=file(res+f'mipmap-{density}/{name}.png').read_bytes()
            assert data[:8] == b'\x89PNG\r\n\x1a\n'
            assert struct.unpack('>II',data[16:24]) == (size,size)
    provenance=json.loads(file('resources/branding/source-provenance.json').read_text())
    assert hashlib.sha256(file('resources/branding/droide-logo-source.png').read_bytes()).hexdigest() == provenance['source_sha256']
    sources = {p.relative_to(args.source_root) for p in (args.source_root/'app/src').rglob('*') if p.suffix in ('.kt','.java')}
    if args.overlay: sources.update(p.relative_to(args.overlay) for p in (args.overlay/'app/src').rglob('*') if p.suffix in ('.kt','.java'))
    calls=[]
    icon_helper = Path('app/src/main/java/com/baystudio/droide/core/DroideNotificationIcons.kt')
    helper = file(icon_helper).read_text()
    assert 'setSmallIcon(R.drawable.ic_stat_droide)' in helper
    assert 'setLargeIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))' in helper
    assert 'import com.baystudio.droide.R' in helper
    for rel in sorted(sources):
        if 'main' not in rel.parts: continue
        text=file(rel).read_text()
        if rel != icon_helper:
            assert not re.search(r'\bset(?:Small|Large)Icon\s*\(', text), f'Notification bypasses shared branding: {rel}'
        for match in re.finditer(r'\.withDroideIcons\s*\(([^)]*)\)',text):
            if rel == icon_helper: continue  # Extension declaration, not a notification caller.
            assert match.group(1).strip() in ('this', 'context'), f'Unexpected icon context: {rel}'
            calls.append({'file':str(rel),'line':text[:match.start()].count('\n')+1})
    assert len(calls) == 4, f'Unexpected notification callsite count: {len(calls)}'
    print(json.dumps({'status':'PASS','adaptive_version_variants':[26,33],'legacy_pngs':10,'notification_callsites':calls,'source_sha256':provenance['source_sha256'],'scope':'Static resource and wiring checks, not device UI acceptance'},indent=2))

if __name__ == '__main__': main()
