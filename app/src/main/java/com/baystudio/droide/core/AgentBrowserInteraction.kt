package com.baystudio.droide.core

import kotlinx.serialization.json.*

internal object AgentBrowserInteraction {
    val operations = setOf("click", "type", "check", "key", "hover", "swipe", "drag", "scroll", "wait_for")

    fun script(operation: String, target: String, text: String?, submit: Boolean,
        checked: Boolean? = null, optionIndex: Int? = null): String {
        require(operation in operations + setOf("check_state", "verify_point")) { "Unsupported browser interaction" }
        val args = buildJsonObject {
            put("operation", operation); put("target", target); put("text", text ?: ""); put("submit", submit)
            checked?.let { put("checked", it) }; optionIndex?.let { put("optionIndex", it) }
        }
        return """
            (() => { try {
              ${AgentBrowserDom.runtime}
              const a=$args, fail=(code,message,retryable=false)=>JSON.stringify({ok:false,code,message,retryable});
              const resolved=a.operation==='scroll'&&!a.target?{el:document.scrollingElement}:D.resolve(a.target);
              if(!resolved.el) return fail(resolved.error||'target_not_found',resolved.message||'No scroll surface',resolved.retryable);
              const el=resolved.el, tag=el.tagName, type=String(el.type||'').toLowerCase();
              const state=()=>typeof el.checked==='boolean'?el.checked:(D.attr(el,'aria-checked')==='true');
              const result=extra=>JSON.stringify({ok:true,action:a.operation,target:D.ref(el),...extra});
              if(a.operation==='check_state') return result({checked:state()});
              if(a.operation==='scroll') {
                const direction=a.text||'down', before={x:el.scrollLeft,y:el.scrollTop};
                const dx=Math.max(40,el.clientWidth*.8),dy=Math.max(40,el.clientHeight*.8);
                if(!['up','down','left','right','top','bottom'].includes(direction)) return fail('invalid_direction','Use up/down/left/right/top/bottom.');
                if(el!==document.scrollingElement) {const g=D.geometry(el);if(g.error) return fail(g.error,g.message,g.retryable);}
                const x=direction==='left'?before.x-dx:direction==='right'?before.x+dx:before.x;
                const y=direction==='up'?before.y-dy:direction==='down'?before.y+dy:direction==='top'?0:direction==='bottom'?el.scrollHeight:before.y;
                el.scrollTo({left:x,top:y,behavior:'instant'});
                const after={x:el.scrollLeft,y:el.scrollTop};return result({before,after,moved:before.x!==after.x||before.y!==after.y});
              }
              const geometry=D.geometry(el,a.operation!=='verify_point');
              if(geometry.error) return fail(geometry.error,geometry.message,geometry.retryable);
              if(a.operation==='wait_for') return result({ready:true,...geometry});
              if(a.operation==='check') {
                if(!['checkbox','radio'].includes(type)&&!['checkbox','radio','switch'].includes(D.attr(el,'role')))
                  return fail('not_checkable','Use check only on a checkbox, radio button or switch.');
                if(typeof a.checked!=='boolean') return fail('invalid_checked','A checked boolean is required.');
                if((type==='radio'||D.attr(el,'role')==='radio')&&!a.checked) return fail('invalid_checked','Choose another radio option instead.');
                if(state()===a.checked) return result({already:true,checked:state()});
              }
              if(['click','check','hover','swipe','drag','verify_point'].includes(a.operation)) return result({prepared:true,...geometry});
              if(type==='password'||type==='file') return fail('human_input_required',type+' fields require direct human interaction.');
              if(el.readOnly||D.attr(el,'aria-readonly')==='true') return fail('readonly','Target is read-only.');
              if(a.operation==='key') {
                el.focus({preventScroll:true});
                if(el.getRootNode().activeElement!==el) return fail('not_focusable','Target could not receive keyboard focus.');
                return result({prepared:true});
              }
              const value=a.text;
              const focus=()=>{el.focus({preventScroll:true});return !D.disabled(el)&&!el.readOnly&&D.attr(el,'aria-readonly')!=='true'&&String(el.type||'').toLowerCase()===type;};
              if(tag==='SELECT') {
                let option=Number.isInteger(a.optionIndex)?el.options[a.optionIndex]:null;
                if(!Number.isInteger(a.optionIndex)) {
                  const matches=[],budget={remaining:20000};
                  for(let i=0;i<Math.min(el.options.length,10000);i++) {const o=el.options[i];if(o.value===value||D.text(o,200,budget)===value) matches.push(o);if(matches.length>1) break;}
                  if(budget.remaining<=0||el.options.length>10000) return fail('option_search_limit','Use an option index from read.');
                  if(matches.length>1) return fail('ambiguous_option','Use the unique option index from read.');
                  option=matches[0];
                }
                if(!option) return fail('option_not_found','Use an option index from the current snapshot.');
                if(option.disabled||option.parentElement?.disabled) return fail('option_disabled','The selected option is disabled.');
                if(!focus()) return fail('target_changed','Field became unavailable while focusing.');
                Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(el,option.value);
              } else if(tag==='INPUT'||tag==='TEXTAREA') {
                if(tag==='INPUT'&&!['text','search','email','tel','url','number','date','datetime-local','month','week','time','color','range'].includes(type))
                  return fail('not_typable','This input is not a text/value field.');
                if(el.maxLength>=0&&value.length>el.maxLength) return fail('value_too_long','Text exceeds this field maximum length.');
                const setter=Object.getOwnPropertyDescriptor(tag==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;
                const probe=el.cloneNode(false);setter.call(probe,value);
                const expected=tag==='INPUT'&&['text','search','email','tel','url'].includes(type)?value.replace(/[\r\n]/g,''):value;
                if(probe.value!==expected) return fail('invalid_value','The browser rejects or changes this value for the field type.');
                if(!focus()) return fail('target_changed','Field became unavailable while focusing.');setter.call(el,expected);
              } else if(el.isContentEditable) {if(!focus()) return fail('target_changed','Field became unavailable while focusing.');el.textContent=value;}
              else return fail('not_typable','Target is not editable.');
              const accepted=String(el.isContentEditable?el.textContent:el.value);
              el.dispatchEvent(new InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:value}));
              el.dispatchEvent(new Event('change',{bubbles:true,composed:true}));
              if(String(el.isContentEditable?el.textContent:el.value)!==accepted)
                return fail('value_not_retained','The page changed the entered value. Inspect it before retrying.');
              return result({method:'dom-fill',accepted:true,length:(el.isContentEditable?el.textContent:el.value).length,
                valid:el.validity?el.validity.valid:null,submitRequested:a.submit});
            } catch(_) {return JSON.stringify({ok:false,code:'script_failure',message:'Browser action preparation failed. Inspect the current page.'});} })()
        """.trimIndent()
    }

    /** Missing acknowledgments never become successful actions merely because a page read succeeds. */
    fun result(raw: String): JsonObject {
        val result = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: error("Browser action returned no valid acknowledgment")
        require(result["ok"] is JsonPrimitive && result["ok"]?.jsonPrimitive?.isString == false && result["ok"]?.jsonPrimitive?.booleanOrNull != null) {
            "Browser action acknowledgment has no boolean status"
        }
        return result
    }

    fun verifyPoint(target: String, x: Float, y: Float): String {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
        return """
            (() => { try { ${AgentBrowserDom.runtime}
              const r=D.resolve(${JsonPrimitive(target)}),v=window.visualViewport;
              const hit=D.hit($x*(v?.width||innerWidth)+(v?.offsetLeft||0),$y*(v?.height||innerHeight)+(v?.offsetTop||0));
              return JSON.stringify(r.el&&!D.disabled(r.el)&&D.contains(r.el,hit)?{ok:true}:{ok:false,code:'target_changed',message:'Target changed before dispatch. Read the page again.'});
            } catch(_) {return JSON.stringify({ok:false,code:'verification_failed'});} })()
        """.trimIndent()
    }
}
