#!/usr/bin/env python3
"""Production DOM-script regression fixtures in isolated Chromium, not Android WebView certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--chromium', default='/usr/bin/chromium')
    parser.add_argument('--temp-root', type=Path, default=Path('/dev/shm'))
    args = parser.parse_args()
    inputs = [ROOT / f'app/src/main/java/com/baystudio/droide/core/{name}.kt'
              for name in ['AgentBrowserDom', 'AgentBrowserInteraction', 'AgentBrowserSnapshot']]
    inputs.append(ROOT / 'tools/web-verification/BrowserActionProbe.kt')
    cp = os.pathsep.join(str(p.resolve()) for p in sorted(args.jars.glob('*.jar')))
    cases = []
    with tempfile.TemporaryDirectory(prefix='droide-browser-actions-', dir=args.temp_root) as temporary:
        os.environ['TMPDIR'] = temporary
        classes = Path(temporary) / 'classes'
        subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-classpath', cp, '-d', str(classes), *map(str, inputs)], check=True)
        bridge = subprocess.Popen(['java', '-cp', str(classes) + os.pathsep + cp, 'com.baystudio.droide.core.BrowserActionProbe'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        def script(operation, target='', **options):
            bridge.stdin.write(json.dumps(dict(operation=operation, target=target, **options)) + '\n'); bridge.stdin.flush()
            return json.loads(bridge.stdout.readline())
        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path=args.chromium, headless=True,
                    args=['--no-sandbox', '--disable-dev-shm-usage'], env={**os.environ, 'TMPDIR': temporary})
                context = browser.new_context(viewport={'width': 640, 'height': 480}, has_touch=True)
                context.route('**/*', lambda route: route.fulfill(status=200, content_type='text/html', body='<!doctype html><body></body>'))
                def page(body):
                    p = context.new_page(); p.goto('https://droide-fixture.test/page')
                    p.set_content('<!doctype html><html><body>' + body + '</body></html>'); return p
                def call(p, operation, target='#target', **options):
                    return json.loads(p.evaluate(script(operation, target, **options)))
                def tap(p, target='#target', **options):
                    result = call(p, 'check' if 'checked' in options else 'click', target, **options)
                    assert result['ok'], result
                    if result.get('already'): return result
                    assert result['prepared'], result
                    point = result['point']; p.touchscreen.tap(point['x'] * 640, point['y'] * 480)
                    return result
                def reject(name, html, operation, expected, observation, unchanged, **options):
                    p = page(html); result = call(p, operation, **options)
                    assert result['ok'] is False and result['code'] == expected, (name, result)
                    assert p.evaluate(observation) == unchanged, name
                    cases.append(name); p.close()
                rejects = [
                    ('disabled_click', '<button id="target" disabled onclick="window.count++">x</button>', 'click', 'disabled'),
                    ('hidden_click', '<button id="target" style="display:none" onclick="window.count++">x</button>', 'click', 'not_visible'),
                    ('covered_click', '<button id="target" onclick="window.count++">x</button><div style="position:fixed;inset:0;z-index:100">cover</div>', 'click', 'covered'),
                    ('ambiguous_selector', '<button class="target" onclick="window.count++">x</button><button class="target">y</button>', 'click', 'ambiguous_target'),
                    ('readonly_input', '<input id="target" readonly value="old">', 'type', 'readonly'),
                    ('disabled_input', '<input id="target" disabled value="old">', 'type', 'disabled'),
                    ('invalid_number', '<input id="target" type="number" value="7">', 'type', 'invalid_value'),
                    ('submit_not_typable', '<input id="target" type="submit" value="old">', 'type', 'not_typable'),
                    ('button_not_typable', '<button id="target" value="old">Button</button>', 'type', 'not_typable'),
                    ('password_guard', '<input id="target" type="password" value="old">', 'type', 'human_input_required'),
                    ('file_guard', '<input id="target" type="file">', 'type', 'human_input_required'),
                    ('maxlength_guard', '<input id="target" maxlength="2" value="old">', 'type', 'value_too_long'),
                ]
                for name, html, operation, code in rejects:
                    is_click = operation == 'click'; old = 0 if is_click else '7' if name == 'invalid_number' else '' if name == 'file_guard' else 'old'
                    reject(name, '<script>window.count=0</script>' + html, operation, code,
                           'window.count' if is_click else 'document.querySelector("#target").value', old,
                           target='.target' if name == 'ambiguous_selector' else '#target', text='invalid-number' if name == 'invalid_number' else 'changed')
                for name, html in [
                    ('tiny_html', '<button id="target" style="width:12px;height:12px;padding:0" onclick="count++">x</button>'),
                    ('tiny_svg', '<svg id="target" role="button" width="12" height="12" onclick="count++"><rect width="12" height="12" fill="blue"/></svg>'),
                    ('pointer_widget', '<div id="target" role="button" style="width:100px;height:40px" onpointerdown="count++">Pointer</div>'),
                    ('trusted_widget', '<button id="target" onclick="if(event.isTrusted)count++">Trusted</button>')]:
                    p = page('<script>window.count=0</script>' + html); tap(p); assert p.evaluate('count') == 1, name
                    cases.append(name); p.close()
                p = page('<div id="host"></div><script>window.count=0;host.attachShadow({mode:"open"}).innerHTML=`<label for="name">Your name</label><input id="name"><button id="target">Shadow</button>`;host.shadowRoot.querySelector("button").onclick=()=>count++</script>')
                snapshot = call(p, 'snapshot'); assert any(e['name'] == 'Your name' for e in snapshot['elements'])
                tap(p); assert p.evaluate('count') == 1; cases.append('shadow_discovery_and_click'); p.close()
                p = page('<button id="target">Original</button>')
                ref = call(p, 'snapshot')['elements'][0]['id']
                p.evaluate('(ref)=>target.outerHTML=`<button data-droide-agent-id="${ref}" id="target">Replacement</button>`', ref)
                result = call(p, 'click', ref); assert result['ok'] is False and result['code'] == 'stale_reference'
                cases.append('copied_attribute_cannot_retarget_reference'); p.close()
                p = page('<button id="target">First document</button>'); old_ref = call(p, 'snapshot')['elements'][0]['id']
                p.goto('https://droide-fixture.test/another'); p.set_content('<button id="target">New document</button>')
                new_ref = call(p, 'snapshot')['elements'][0]['id']; assert old_ref != new_ref
                assert call(p, 'click', old_ref)['code'] == 'stale_reference'
                cases.append('references_do_not_cross_documents'); p.close()
                p = page('<button id="target" disabled>Later</button><script>setTimeout(()=>target.disabled=false,200)</script>')
                deadline = time.monotonic() + 2
                while True:
                    result = call(p, 'click')
                    if result['ok']: break
                    assert result.get('retryable') and time.monotonic() < deadline; time.sleep(.07)
                cases.append('delayed_ready_preparation'); p.close()
                p = page('<label for="target">Accept</label><input id="target" type="checkbox"><script>window.count=0;target.onchange=()=>count++</script>')
                tap(p, checked=True); assert tap(p, checked=True)['already']
                assert p.evaluate('target.checked && count===1'); assert call(p, 'snapshot')['elements'][0]['checked'] is True
                cases.append('checkbox_idempotent_and_observable'); p.close()
                p = page('<style>html,body{height:100%;overflow:hidden}</style><div id="target" style="width:200px;height:100px;overflow:auto"><div style="width:1500px;height:1500px">Long</div></div>')
                assert call(p, 'scroll', text='down')['moved']; assert call(p, 'scroll', text='right')['moved']
                assert p.evaluate('target.scrollTop>0&&target.scrollLeft>0'); assert call(p, 'snapshot')['elements'][0]['scrollable']
                cases.append('nested_horizontal_vertical_scroll'); p.close()
                p = page('<input id="target"><script>window.injected=false</script>')
                value = '\");window.injected=true;// 🚀'
                assert call(p, 'type', text=value)['ok']; assert p.evaluate('target.value') == value and not p.evaluate('injected')
                assert call(p, 'type', text='')['ok'] and p.evaluate('target.value') == ''
                cases.append('escaped_unicode_and_empty_fill'); p.close()
                p = page('<input id="target" value="old" oninput="this.value=\'old\'">')
                assert call(p, 'type', text='new')['code'] == 'value_not_retained'; cases.append('controlled_field_rejection_reported'); p.close()
                p = page('<label for="target">City</label><select id="target"><option value="secret-a">Jakarta</option><option value="secret-b">Bandung</option></select>')
                snapshot = call(p, 'snapshot'); field = snapshot['elements'][0]
                assert field['name'] == 'City' and field['options'][1]['label'] == 'Bandung' and 'secret-b' not in json.dumps(snapshot)
                assert call(p, 'type', option_index=1)['ok'] and p.evaluate('target.value') == 'secret-b'
                cases.append('select_discovery_and_index'); p.close()
                p = page('<iframe srcdoc="<button>Frame</button>"></iframe>')
                snapshot = call(p, 'snapshot'); assert snapshot['source_incomplete'] and snapshot['frames'][0]['available'] is False
                cases.append('frame_coverage_explicit'); p.close()
                p = page('<form id="form"><label for="name">Name</label><input id="name" name="name" required><label for="email">Email</label><input id="email" name="email" type="email" required><select id="city" name="city"><option value="jkt">Jakarta</option><option value="bdg">Bandung</option></select><textarea id="note" name="note"></textarea><input id="terms" name="terms" type="checkbox" required><input id="regular" type="radio" name="shipping" value="regular" checked><input id="express" type="radio" name="shipping" value="express"><button id="send">Send</button></form><script>window.sent=[];form.onsubmit=e=>{e.preventDefault();sent.push(Object.fromEntries(new FormData(form)))}</script>')
                fields = call(p, 'snapshot')['elements']; name_ref = next(e['id'] for e in fields if e['name'] == 'Name')
                for target, value in [(name_ref, 'Nadia'), ('#email', 'nadia@example.test'), ('#note', 'Delivery note')]:
                    assert call(p, 'type', target, text=value)['ok']
                assert call(p, 'type', '#city', option_index=1)['ok']
                tap(p, '#terms', checked=True); tap(p, '#express', checked=True); tap(p, '#send')
                assert p.evaluate('sent') == [{'name':'Nadia','email':'nadia@example.test','city':'bdg','note':'Delivery note','terms':'on','shipping':'express'}]
                cases.append('form_discovery_fill_and_single_submission'); p.close()
                proof = {'status':'PASS','cases':cases,'count':len(cases),'chromium':browser.version,
                         'productionDomScripts':True,'inputDriver':'Playwright touch on prepared production coordinates',
                         'androidWebViewTested':False,'network':'All requests intercepted locally; no external forms submitted.',
                         'productionInputs':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}}
                browser.close()
        finally:
            bridge.stdin.close()
            try: bridge.wait(timeout=5)
            except subprocess.TimeoutExpired: bridge.kill(); bridge.wait()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(proof, indent=2) + '\n'); print(json.dumps(proof, indent=2))


if __name__ == '__main__':
    main()
