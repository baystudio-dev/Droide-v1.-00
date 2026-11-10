package com.baystudio.droide.core

import kotlinx.serialization.json.JsonPrimitive

/** Application-owned DOM operations: model input is data, never executable JavaScript. */
internal object AgentBrowserSnapshot {
    fun script(operation: String = "read", start: Int = 0, count: Int = 80, query: String = "",
        elementStart: Int = 0, elementCount: Int = 30): String {
        require(operation in setOf("read", "find", "links")) { "Unsupported DOM read operation" }
        require(start in 0..100_000 && count in 1..200 && elementStart in 0..10_000 && elementCount in 1..40) { "Invalid browser read range" }
        require(query.length <= 1_000 && (operation != "find" || query.isNotBlank())) { "Browser find requires a bounded query" }
        val queryJson = JsonPrimitive(query).toString()
        return """
            (() => {
              ${AgentBrowserDom.runtime}
              const start=$start, count=$count, operation='$operation', query=$queryJson;
              // Never materialize body.innerText or all matching nodes before applying a cap.
              // Walk a rendered prefix once; form/editable subtrees remain private even when
              // their initial values are ordinary DOM text (textarea, option, contenteditable).
              const maxNodes=20000,maxStyles=10000,maxText=300000,maxElements=10000,maxDepth=256;
              const parts=[],bounded=[],found=[],seen=new Set(),controls=[],frames=[];
              let visited=0,styles=0,textRead=0,textLength=0,limit=null;
              const semanticBudget={remaining:20000};
              const stop=reason=>{if(!limit) limit=reason;return false;};
              const summary=value=>{
                const frame=controls[controls.length-1];
                if(frame&&frame.text.length<256) frame.text+=(value.slice(0,256-frame.text.length));
              };
              const append=value=>{
                const remaining=maxText-textLength;
                const piece=value.slice(0,remaining);
                if(piece) {parts.push(piece);textLength+=piece.length;summary(piece);}
                return value.length<=remaining||stop('text_budget');
              };
              const attr=(e,name,length)=>String(e.getAttribute(name)||'').slice(0,length);
              const blockDisplays=new Set(['block','flex','grid','table','table-row','list-item','flow-root']);
              const skippedTags=new Set(['SCRIPT','STYLE','NOSCRIPT','TEMPLATE','HEAD']);
              const formTags=new Set(['INPUT','TEXTAREA','SELECT','OPTION']);
              const editable=e=>{
                const value=attr(e,'contenteditable',32).trim().toLowerCase();
                return e.isContentEditable===true || e.hasAttribute('contenteditable')&&['','true','plaintext-only'].includes(value);
              };
              function identify(e) {
                return D.ref(e);
              }
              function walk(node,depth,whiteSpace,hiddenText=false) {
                if(limit) return false;
                if(visited>=maxNodes) return stop('node_budget');
                visited++;
                if(node.nodeType===3) {
                  if(hiddenText) return true;
                  const raw=node.nodeValue||'',remaining=Math.min(maxText-textRead,maxText-textLength);
                  if(!remaining&&raw.length) return stop('text_budget');
                  const clipped=raw.slice(0,remaining);textRead+=clipped.length;
                  const text=['pre','pre-wrap','pre-line','break-spaces'].includes(whiteSpace)?clipped.replace(/\r\n?/g,'\n'):clipped.replace(/[ \t\r\n\f]+/g,' ');
                  if(!append(text)) return false;
                  return raw.length<=remaining||stop('text_budget');
                }
                if(node.nodeType!==1) return true;
                if(depth>maxDepth) return stop('depth_budget');
                const e=node,tag=e.tagName.toUpperCase();
                if(skippedTags.has(tag)) return true;
                if(styles>=maxStyles) return stop('style_budget');
                styles++;
                const style=getComputedStyle(e);
                if(style.display==='none') return true;
                const hidesText=style.visibility==='hidden'||style.visibility==='collapse';
                if(tag==='BR') return hidesText||append('\n');
                const isEditable=editable(e),role=attr(e,'role',40);
                if(tag==='IFRAME'&&frames.length<16) frames.push({title:attr(e,'title',160),url:attr(e,'src',2048),available:false});
                const privateText=formTags.has(tag)||isEditable||['textbox','searchbox'].includes(role.toLowerCase());
                // Text search needs neither control geometry nor target-ID writes.
                const scrollable=(['auto','scroll'].includes(style.overflowY)&&e.scrollHeight>e.clientHeight)||
                  (['auto','scroll'].includes(style.overflowX)&&e.scrollWidth>e.clientWidth);
                const candidate=operation!=='find'&&(['A','BUTTON','INPUT','TEXTAREA','SELECT'].includes(tag)||
                  ['button','link','textbox','searchbox','checkbox','radio','switch','combobox','listbox','option','menuitem','tab','slider','spinbutton'].includes(role.toLowerCase())||isEditable||scrollable);
                let frame=null;
                if(candidate&&!hidesText) {
                  const rect=e.getBoundingClientRect();
                  if(rect.width>0&&rect.height>0) {
                    if(bounded.length>=maxElements) return stop('element_budget');
                    const item={id:identify(e),tag:tag.toLowerCase(),role,type:String(e.type||'').slice(0,40),name:D.name(e,semanticBudget),text:'',disabled:D.disabled(e),
                      readonly:!!e.readOnly||attr(e,'aria-readonly',10)==='true',scrollable};
                    if(typeof e.checked==='boolean'&&['checkbox','radio'].includes(e.type)) item.checked=e.checked;
                    else if(e.hasAttribute('aria-checked')) item.checked=attr(e,'aria-checked',10);
                    if(e.hasAttribute('aria-expanded')) item.expanded=attr(e,'aria-expanded',10)==='true';
                    if(tag==='SELECT') {
                      item.options=Array.from({length:Math.min(e.options.length,40)},(_,index)=>{const o=e.options[index];return {index,label:D.text(o,100,semanticBudget),selected:o.selected,disabled:o.disabled||!!o.parentElement?.disabled};});
                      item.total_options=e.options.length;item.options_incomplete=e.options.length>40;
                    }
                    bounded.push(item);frame={item,text:'',link:null};
                    if(semanticBudget.remaining<=0) stop('semantic_budget');
                    // Ordinary reads expose controls, but only links requests need URL parsing.
                    if(operation==='links'&&tag==='A'&&e.hasAttribute('href')) {
                      try {
                        const href=attr(e,'href',8193),base=document.baseURI||location.href;
                        if(href.length<=8192&&base.length<=8192) {
                          const u=new URL(href,base);
                          if(['https:','http:'].includes(u.protocol)&&!u.username&&!u.password&&u.href.length<=8192&&!seen.has(u.href)) {
                            seen.add(u.href);frame.link={url:u.href,title:'',target:item.id};found.push(frame.link);
                          }
                        }
                      } catch(_) {}
                    }
                    controls.push(frame);
                  }
                }
                const block=!hidesText&&blockDisplays.has(style.display);
                let complete=!block||append('\n');
                if(complete&&!privateText) {
                  for(let child=e.firstChild;child;child=child.nextSibling) {
                    if(!walk(child,depth+1,style.whiteSpace||whiteSpace,hidesText)) {complete=false;break;}
                  }
                  if(complete&&e.shadowRoot) for(let child=e.shadowRoot.firstChild;child;child=child.nextSibling) {
                    if(!walk(child,depth+1,style.whiteSpace||whiteSpace,hidesText)) {complete=false;break;}
                  }
                }
                if(complete&&block) complete=append('\n');
                if(frame) {
                  controls.pop();
                  const text=privateText?'':frame.text.replace(/\s+/g,' ').trim();
                  frame.item.text=text.slice(0,160);
                  if(frame.link) frame.link.title=(text||frame.link.url).slice(0,256);
                  summary(text);
                }
                return complete;
              }
              if(document.body) walk(document.body,0,'normal');
              const clipped=parts.join('');
              const extractedLines=clipped.replace(/\r\n?/g,'\n').split('\n').flatMap(line => {
                const clean=line.replace(/[ \t]+/g,' ').trim();
                return clean ? (clean.match(/.{1,180}/g)||[]) : [];
              });
              // Continuation must remain inside the Kotlin API's start_line range.
              if(extractedLines.length>100000) stop('line_budget');
              const lines=extractedLines.slice(0,100000);
              const sourceIncomplete=limit!==null||frames.length>0;
              const base={url:location.href.slice(0,8192),title:document.title.slice(0,512),operation,
                total_lines:lines.length,source_incomplete:sourceIncomplete,form_values_included:false,
                coverage:'top document and open shadow roots; frame documents and closed shadow roots excluded',frames,
                select_options_included:true,
                snapshot_limit:limit,snapshot_work:{nodes:visited,style_checks:styles,text_characters:textRead,semantic_nodes:20000-semanticBudget.remaining}};
              let out;
              if(operation==='read') {
                if(start>lines.length) return JSON.stringify({...base,ok:false,error:'start_line is beyond the rendered snapshot'});
                const elements=bounded.slice($elementStart,$elementStart+$elementCount);
                out={...base,ok:true,start_line:start,lines:lines.slice(start,start+count),elements,
                  element_start:$elementStart,total_elements:bounded.length,elements_incomplete:sourceIncomplete};
              } else if(operation==='find') {
                const matches=[],normalizedQuery=query.toLocaleLowerCase();
                let totalMatches=0;
                for(let i=start;i<lines.length;i++) if(lines[i].toLocaleLowerCase().includes(normalizedQuery)) {
                  totalMatches++;
                  // Count the bounded text prefix without retaining every match.
                  if(matches.length<Math.min(count,30)) matches.push({line:i,text:lines[i],context:lines.slice(Math.max(0,i-1),Math.min(lines.length,i+2)).join('\n')});
                }
                out={...base,ok:true,query,start_line:start,matches,total_matches:totalMatches};
              } else {
                out={...base,ok:true,start,links:found.slice(start,start+Math.min(count,30)),total_links:found.length,links_incomplete:sourceIncomplete};
              }
              function offsets() {
                if(operation==='read') {
                  out.end_line=start+out.lines.length;
                  out.next_start_line=out.end_line<lines.length?out.end_line:null;
                  out.next_element_start=$elementStart+out.elements.length<bounded.length?$elementStart+out.elements.length:null;
                } else if(operation==='find') {
                  out.next_start_line=out.total_matches>out.matches.length&&out.matches.length?out.matches[out.matches.length-1].line+1:null;
                } else out.next_start=start+out.links.length<out.total_links?start+out.links.length:null;
              }
              offsets();
              let encoded=JSON.stringify(out);
              while(encoded.length>18000) {
                out.output_truncated=true;
                if(out.lines?.length>1) out.lines.pop();
                else if(out.elements?.length>1) out.elements.pop();
                else if(out.matches?.length>1) out.matches.pop();
                else if(out.links?.length>1) out.links.pop();
                else if(out.title.length>64) out.title=out.title.slice(0,64);
                else return JSON.stringify({...base,title:base.title.slice(0,64),ok:false,
                  error:'Requested snapshot item exceeds the encoded output budget',output_truncated:true});
                offsets();encoded=JSON.stringify(out);
              }
              return encoded;
            })()
        """.trimIndent()
    }
}
