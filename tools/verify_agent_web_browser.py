#!/usr/bin/env python3
"""Run exact production DOM JavaScript in Node fixtures; this is not Android WebView certification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

NODE_PROBE = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const {JSDOM}=require(path.join(process.argv[3],'jsdom'));
const scripts=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
let passed=0;const names=[],budgetEvidence=[];
const secrets=['password-secret','file-secret','textarea-secret','select-secret','editable-secret','readonly-child-secret'];
function fixture(html,{guardSnapshot=true,assertNoIdWritesDuringStyle=false}={}) {
  const dom=new JSDOM('<!doctype html><html><head><title>Source</title></head><body>'+html+'</body></html>',
    {url:'https://docs.example/page',runScripts:'outside-only',pretendToBeVisual:true});
  const win=dom.window,doc=win.document;
  let rectCalls=0,styleCalls=0,idWrites=0,urlConstructions=0;
  const NativeURL=win.URL;
  win.URL=class extends NativeURL {constructor(...args){super(...args);urlConstructions++;}};
  const nativeSetAttribute=win.Element.prototype.setAttribute;
  win.Element.prototype.setAttribute=function(name,value){
    if(name==='data-droide-agent-id') idWrites++;
    return nativeSetAttribute.call(this,name,value);
  };
  const nativeStyle=win.getComputedStyle.bind(win);
  win.getComputedStyle=e=>{
    if(assertNoIdWritesDuringStyle) assert.equal(idWrites,0,'ID writes must follow the complete style/layout pass');
    styleCalls++;return nativeStyle(e);
  };
  let lastMeasured=null;
  win.Element.prototype.getBoundingClientRect=function(){rectCalls++;lastMeasured=this;return {width:20,height:10,left:0,top:0,right:20,bottom:10};};
  // jsdom has no hit testing. Native delivery/actionability is covered separately by Chromium and Android transport fixtures.
  doc.elementFromPoint=()=>lastMeasured;
  win.HTMLElement.prototype.scrollIntoView=function(){};
  if(guardSnapshot) {
    Object.defineProperty(win.HTMLElement.prototype,'innerText',{get(){throw Error('Unbounded innerText access');}});
    Object.defineProperty(doc.body,'textContent',{get(){throw Error('Whole-body textContent access');}});
    for(const e of doc.querySelectorAll('input,textarea,select')) {
      Object.defineProperty(e,'value',{get(){throw Error('Form values must never be read');}});
    }
    doc.querySelectorAll=()=>{throw Error('Whole-DOM matching must not be materialized');};
  }
  return {dom,win,doc,context:dom.getInternalVMContext(),stats:()=>({rectCalls,styleCalls,idWrites,urlConstructions})};
}
function evaluate(f,name) {
  const source=scripts.find(s=>s.name===name);assert(source,'Missing production script '+name);
  return vm.runInContext(source.script,f.context,{timeout:20000});
}
function snapshot(f,name,{caseName=name}={}) {
  const raw=evaluate(f,name),result=JSON.parse(raw);
  assert.equal(result.ok,true);assert.equal(result.form_values_included,false);
  for(const secret of secrets) assert(!raw.includes(secret),'Private value leaked: '+secret);
  assert(raw.length<=18000,'Snapshot payload exceeded its output budget');
  assert(result.snapshot_work.nodes<=20000);assert(result.snapshot_work.style_checks<=10000);
  assert(result.snapshot_work.text_characters<=300000);
  passed++;names.push(caseName);return result;
}
const f=fixture('<div>first   line</div><div>needle here</div><div>third</div><div>Needle again</div><div>last</div>'+
  '<input id="password" type="password" value="password-secret"><input type="file" name="upload">'+
  '<textarea>textarea-secret</textarea><a href="/one">First</a><a href="/two">Second</a>'+
  '<a href="/one">Duplicate</a><a href="javascript:alert(1)">Unsafe</a>');
let a=snapshot(f,'read');assert.equal(a.lines[0],'first line');assert.equal(a.next_start_line,2);assert.equal(a.next_element_start,2);
const id=a.elements[0].id;let b=snapshot(f,'continue');assert.equal(b.start_line,2);assert.equal(b.elements[0].text,'');
assert.equal(f.win.__droideBrowserV3.resolve(id).el,f.doc.getElementById('password'));
assert.equal(f.doc.getElementById('password').getAttribute('data-droide-agent-id'),null);
a=snapshot(f,'large',{caseName:'stable_ids'});
assert.equal(new Set(a.elements.map(e=>e.id)).size,a.elements.length);
assert.equal(a.elements[0].id,id);assert.equal(a.source_incomplete,false);
a=snapshot(f,'find');assert.equal(a.matches[0].line,1);assert.equal(a.next_start_line,2);
b=snapshot(f,'find_continue');assert.equal(b.matches[0].line,3);assert.equal(b.next_start_line,null);
a=snapshot(f,'links');assert.equal(a.links[0].url,'https://docs.example/one');assert.equal(a.next_start,1);
b=snapshot(f,'links_continue');assert.equal(b.links[0].url,'https://docs.example/two');assert.equal(b.total_links,2);
assert(a.links[0].target&&b.links[0].target);
snapshot(f,'injection');assert.equal(f.win.injected,undefined);f.dom.window.close();
const privateDom=fixture('<p>Public before</p><textarea>textarea-secret</textarea>'+
  '<select><option selected value="select-secret">Public option label</option></select>'+
  '<div contenteditable="true">editable-secret<span contenteditable="false">readonly-child-secret</span></div>'+
  '<div contenteditable>editable-secret</div><div contenteditable="plaintext-only">editable-secret</div>'+
  '<div role="textbox">editable-secret</div><p>Public after</p>');
a=snapshot(privateDom,'large',{caseName:'initial_form_and_editable_values_excluded'});
assert.deepEqual(Array.from(a.lines),['Public before','Public after']);
assert(a.elements.length>=6);assert(a.elements.every(e=>e.text===''));
privateDom.dom.window.close();
const hidden=fixture('<style>.concealed {display:none}.invisible {visibility:hidden}</style>'+
  '<div class="concealed"><a href="/secret">editable-secret</a></div>'+
  '<div class="invisible"><button>textarea-secret</button></div><div hidden>select-secret</div>'+
  '<p>Hello <b>visible</b> text</p><pre>one\n  two</pre><a href="/shown">Shown</a>');
a=snapshot(hidden,'large',{caseName:'hidden_css_and_rendered_text'});
assert.deepEqual(Array.from(a.lines),['Hello visible text','one','two','Shown']);
b=snapshot(hidden,'links',{caseName:'only_rendered_links_with_stable_targets'});
assert.equal(b.total_links,1);assert.equal(b.links[0].url,'https://docs.example/shown');
assert(b.links[0].target);assert.equal(a.source_incomplete,false);hidden.dom.window.close();
const visibleOverride=fixture('<div style="visibility:hidden">editable-secret'+
  '<a href="/override" style="visibility:visible">Visible override</a>textarea-secret</div>'+
  '<p hidden style="display:block">Hidden attribute override</p>'+
  '<svg><a href="/svg"><text>SVG link</text></a></svg>');
a=snapshot(visibleOverride,'large',{caseName:'visibility_and_hidden_attribute_overrides'});
assert.deepEqual(Array.from(a.lines),['Visible override','Hidden attribute override','SVG link']);
b=snapshot(visibleOverride,'links',{caseName:'visible_override_and_svg_links'});
assert.equal(b.total_links,2);assert(b.links.every(link=>link.target));visibleOverride.dom.window.close();
const duplicate=fixture('<a id="first" href="/one" data-droide-agent-id="d11">First</a>'+
  '<a id="second" href="/two" data-droide-agent-id="d11">Second</a>'+
  '<button id="third" data-droide-agent-id="d1">Third</button>');
a=snapshot(duplicate,'large',{caseName:'duplicate_dom_ids_repaired'});
assert.equal(new Set(a.elements.map(e=>e.id)).size,3);
b=snapshot(duplicate,'large',{caseName:'repaired_ids_stable_on_next_read'});
assert.deepEqual(b.elements.map(e=>e.id),a.elements.map(e=>e.id));duplicate.dom.window.close();
const oversizedId=fixture('<button id="oversized" data-droide-agent-id="d'+('1'.repeat(40))+'">Repair</button>');
oversizedId.win.__droideAgentElementSeq=1.5;
a=snapshot(oversizedId,'large',{caseName:'oversized_ids_and_fractional_sequence_repaired'});
assert.notEqual(a.elements[0].id,oversizedId.doc.getElementById('oversized').getAttribute('data-droide-agent-id'));
assert(/^d[0-9a-f]+_[0-9]+$/.test(a.elements[0].id));assert(a.elements[0].id.length<=32);
b=snapshot(oversizedId,'large',{caseName:'oversized_id_repair_stays_addressable'});
assert.equal(a.elements[0].id,b.elements[0].id);oversizedId.dom.window.close();
const deferredIds=fixture('<style>[data-droide-agent-id] {padding:1px}</style>'+
  '<button id="deferred-button">Button</button><p>Between controls</p>'+
  '<a id="deferred-link" href="/deferred"><span>Link</span></a>',{assertNoIdWritesDuringStyle:true});
a=snapshot(deferredIds,'large',{caseName:'references_do_not_mutate_page_attributes_or_trigger_style_reflows'});
assert.equal(deferredIds.stats().idWrites,0);assert(deferredIds.stats().styleCalls>=5);
assert.equal(deferredIds.win.__droideBrowserV3.resolve(a.elements[0].id).el,deferredIds.doc.getElementById('deferred-button'));
assert.equal(deferredIds.win.__droideBrowserV3.resolve(a.elements[1].id).el,deferredIds.doc.getElementById('deferred-link'));
deferredIds.dom.window.close();
// Deterministic work counters, not elapsed-time benchmarks: only link listing
// requires URL normalization, and text find does not need hit-test geometry/IDs.
const mobileHtml='<div>needle before</div>'+Array.from({length:250},(_,i)=>
  '<a href="/mobile/'+i+'">needle '+i+'</a><button>Action</button>').join('');
const readOnly=fixture(mobileHtml);
a=snapshot(readOnly,'large',{caseName:'ordinary_read_does_not_normalize_unused_link_urls'});
assert.equal(readOnly.stats().urlConstructions,0);
assert(a.elements.length>0);assert(readOnly.stats().rectCalls>0);
budgetEvidence.push({fixture:'mobileRead',...a.snapshot_work,...readOnly.stats()});readOnly.dom.window.close();
const textFind=fixture(mobileHtml);
a=snapshot(textFind,'find',{caseName:'find_skips_control_geometry_id_mutation_and_url_normalization'});
assert.equal(textFind.stats().rectCalls,0);assert.equal(textFind.stats().idWrites,0);
assert.equal(textFind.stats().urlConstructions,0);assert.equal(textFind.win.__droideAgentElementSeq,undefined);
assert.equal(a.matches.length,1);assert(a.total_matches>0);assert.equal(a.source_incomplete,false);
budgetEvidence.push({fixture:'mobileFind',...a.snapshot_work,...textFind.stats()});textFind.dom.window.close();
const countFind=fixture('<pre>'+('needle\n'.repeat(10000))+'</pre>');
a=snapshot(countFind,'find',{caseName:'find_retains_bounded_matches_and_exact_total_with_continuation'});
assert.equal(a.total_matches,10000);assert.equal(a.matches.length,1);assert.equal(a.next_start_line,1);
assert.equal(a.matches[0].line,0);countFind.dom.window.close();
const escapedUrl=fixture('<a id="escaped">Long link</a>');
const exactUrl='https://docs.example/long?q='+('\\'.repeat(8000));
escapedUrl.win.history.replaceState({},'',exactUrl);
escapedUrl.doc.getElementById('escaped').setAttribute('href',exactUrl);
const encodedLongUrl=evaluate(escapedUrl,'links');a=JSON.parse(encodedLongUrl);
assert.equal(a.ok,false);assert.equal(a.url,escapedUrl.win.location.href);
assert(a.error.includes('output budget'));assert.equal(a.output_truncated,true);assert(encodedLongUrl.length<=18000);
assert.equal(a.next_start,undefined);passed++;names.push('escaped_long_urls_fail_bounded_without_changing_page_identity');
escapedUrl.dom.window.close();
const hugeText=fixture('<pre>'+('line &quot;\n'.repeat(50000))+'</pre><p>OUTSIDE_TEXT_BUDGET</p>');
a=snapshot(hugeText,'large',{caseName:'large_text_bounded_before_processing'});
assert.equal(a.source_incomplete,true);assert.equal(a.snapshot_limit,'text_budget');
assert(a.next_start_line!==null);assert(!a.lines.join('\n').includes('OUTSIDE_TEXT_BUDGET'));
budgetEvidence.push({fixture:'hugeText',...a.snapshot_work,...hugeText.stats()});hugeText.dom.window.close();
const excessiveLines=fixture('<pre>'+('x\n'.repeat(100001))+'</pre>');
a=snapshot(excessiveLines,'last_lines',{caseName:'line_continuation_stays_within_api_range'});
assert.equal(a.source_incomplete,true);assert.equal(a.snapshot_limit,'line_budget');
assert.equal(a.total_lines,100000);assert.equal(a.lines.length,1);assert.equal(a.next_start_line,null);
excessiveLines.dom.window.close();
const hugeNodes=fixture('<!--padding-->'.repeat(21000)+'<p>OUTSIDE_NODE_BUDGET</p>');
a=snapshot(hugeNodes,'large',{caseName:'comment_nodes_count_toward_scan_budget'});
assert.equal(a.source_incomplete,true);assert.equal(a.snapshot_limit,'node_budget');
assert(!a.lines.join('\n').includes('OUTSIDE_NODE_BUDGET'));
budgetEvidence.push({fixture:'hugeNodes',...a.snapshot_work,...hugeNodes.stats()});hugeNodes.dom.window.close();
const hugeControls=fixture('<a href="/one">visible</a>'.repeat(12000)+'<p>OUTSIDE_STYLE_BUDGET</p>');
a=snapshot(hugeControls,'large',{caseName:'large_control_dom_stops_at_style_budget'});
assert.equal(a.source_incomplete,true);assert.equal(a.snapshot_limit,'style_budget');
assert.equal(a.elements_incomplete,true);assert(hugeControls.stats().styleCalls<=10000);
b=snapshot(hugeControls,'links',{caseName:'links_expose_incomplete_bounded_scan'});
assert.equal(b.links_incomplete,true);assert.equal(b.links[0].url,'https://docs.example/one');
budgetEvidence.push({fixture:'hugeControls',...a.snapshot_work,firstReadStyleCalls:a.snapshot_work.style_checks});hugeControls.dom.window.close();
const deep=fixture('<div>'.repeat(270)+'<p>OUTSIDE_DEPTH_BUDGET</p>'+'</div>'.repeat(270));
a=snapshot(deep,'large',{caseName:'pathological_dom_depth_is_bounded'});
assert.equal(a.source_incomplete,true);assert.equal(a.snapshot_limit,'depth_budget');deep.dom.window.close();
const interactions=fixture('<select id="language"><option value="js">JavaScript</option><option value="go">Go</option></select>'+
  '<textarea id="notes"></textarea><input id="normal"><input id="stable" data-droide-agent-id="d42">'+
  '<input id="password" type="password" value="original">'+
  '<input id="file" type="file"><button id="button">Click</button>',{guardSnapshot:false});
const events=[];
interactions.doc.body.addEventListener('input',e=>events.push({type:e.type,id:e.target.id,data:e.data}));
interactions.doc.body.addEventListener('change',e=>events.push({type:e.type,id:e.target.id}));
function interaction(name,expected=true) {
  const result=JSON.parse(evaluate(interactions,name));assert.equal(result.ok,expected);passed++;names.push(name);return result;
}
interaction('interaction_select');assert.equal(interactions.doc.getElementById('language').value,'go');
assert.deepEqual(events.slice(-2).map(e=>e.type),['input','change']);
assert.equal(events.at(-2).data,'go');assert.equal(events.at(-1).id,'language');
let beforeInvalid=events.length;interaction('interaction_select_invalid',false);
assert.equal(interactions.doc.getElementById('language').value,'go');assert.equal(events.length,beforeInvalid);
interaction('interaction_known_element');assert.equal(interactions.doc.getElementById('stable').value,'stable-target');
interaction('interaction_textarea');assert.equal(interactions.doc.getElementById('notes').value,'A multiline\nplain text value');
assert.deepEqual(events.slice(-2).map(e=>e.type),['input','change']);
interaction('interaction_input_data');assert.equal(interactions.doc.getElementById('normal').value,'"); globalThis.injected=true; //');
assert.equal(interactions.win.injected,undefined);
const eventCount=events.length;interaction('interaction_password',false);interaction('interaction_file',false);
assert.equal(interactions.doc.getElementById('password').value,'original');
assert.equal(interactions.doc.getElementById('file').value,'');assert.equal(events.length,eventCount);
interaction('interaction_target_data',false);assert.equal(interactions.win.targetInjected,undefined);
let clicked=0;interactions.doc.getElementById('button').addEventListener('click',()=>clicked++);
const prepared=interaction('interaction_click');assert.equal(prepared.prepared,true);assert.equal(clicked,0);interactions.dom.window.close();
console.log(JSON.stringify({status:'PASS',cases:passed,names,budgetEvidence,domImplementation:'jsdom',
  jsdomVersion:require(path.join(process.argv[3],'jsdom/package.json')).version,layoutGeometryMocked:true,
  realChromium:false,realAndroidWebView:false}));
'''

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--jars',action='append',required=True)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--node-modules',type=Path,required=True,help='External node_modules containing jsdom (not bundled in source)')
    args=p.parse_args()
    jars=sorted({x.resolve() for d in args.jars for x in Path(d).glob('*.jar')})
    cp=os.pathsep.join(map(str,jars))
    sources=[ROOT/'app/src/main/java/com/baystudio/droide/core/AgentBrowserDom.kt',ROOT/'app/src/main/java/com/baystudio/droide/core/AgentBrowserSnapshot.kt',ROOT/'app/src/main/java/com/baystudio/droide/core/AgentBrowserInteraction.kt',ROOT/'tools/web-verification/AgentWebSnapshotProbe.kt']
    with tempfile.TemporaryDirectory(prefix='droide-browser-probe-') as temp:
        temp=Path(temp); classes=temp/'classes'
        subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(classes),*map(str,sources)],check=True)
        scripts=subprocess.check_output(['java','-cp',str(classes)+os.pathsep+cp,'com.baystudio.droide.core.AgentWebSnapshotProbe'],text=True)
        json.loads(scripts); (temp/'scripts.json').write_text(scripts); (temp/'probe.js').write_text(NODE_PROBE)
        proof=json.loads(subprocess.check_output(['node',str(temp/'probe.js'),str(temp/'scripts.json'),str(args.node_modules.resolve())],text=True))
    proof['productionInputs']={str(s.relative_to(ROOT)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources}
    proof['runnerSha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    args.output.parent.mkdir(parents=True,exist_ok=True); args.output.write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps(proof,indent=2))

if __name__=='__main__': main()
