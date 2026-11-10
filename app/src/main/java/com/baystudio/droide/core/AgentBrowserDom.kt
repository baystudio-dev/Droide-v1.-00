package com.baystudio.droide.core

/** Shared bounded DOM helpers. References identify observed nodes, never copied HTML attributes. */
internal object AgentBrowserDom {
    val runtime = """
        const D = (() => {
          if (window.__droideBrowserV3) return window.__droideBrowserV3;
          const ids=new WeakMap(), nodes=new Map(); let sequence=0;
          const documentId=Array.from(crypto.getRandomValues(new Uint32Array(2))).map(n=>n.toString(16)).join('');
          const attr=(e,k,n=256)=>String(e.getAttribute?.(k)||'').slice(0,n);
          function each(root,visit,max=20000) {
            const roots=[root]; let count=0;
            for(let i=0;i<roots.length&&i<256;i++) {
              const walker=document.createTreeWalker(roots[i],NodeFilter.SHOW_ELEMENT);
              let e=roots[i].nodeType===1?roots[i]:walker.nextNode();
              for(;e;e=walker.nextNode()) {
                if(++count>max) return false;
                if(visit(e)===false) return true;
                if(e.shadowRoot) roots.push(e.shadowRoot);
              }
            }
            return roots.length<=256;
          }
          function text(e,max=160,budget=null) {
            let out='',seen=0;
            function walk(n,depth) {
              if(++seen>512||depth>32||out.length>=max||budget&&budget.remaining<=0) return;
              if(budget) budget.remaining--;
              if(n.nodeType===3) {out+=(n.nodeValue||'').slice(0,max-out.length);return;}
              if(n.nodeType!==1||['INPUT','TEXTAREA','SELECT','SCRIPT','STYLE'].includes(n.tagName)||n.isContentEditable||
                ['textbox','searchbox'].includes(attr(n,'role',40).toLowerCase())||
                n.hasAttribute('contenteditable')&&['','true','plaintext-only'].includes(attr(n,'contenteditable',32).toLowerCase())) return;
              for(let c=n.firstChild;c&&seen<=512&&out.length<max;c=c.nextSibling) walk(c,depth+1);
            }
            walk(e,0);return out.replace(/\s+/g,' ').trim();
          }
          function name(e,budget=null) {
            const root=e.getRootNode(), refs=attr(e,'aria-labelledby').split(/\s+/).slice(0,8);
            const labelled=refs.map(id=>root.getElementById?.(id)).filter(Boolean).map(x=>text(x,100,budget)).join(' ').trim();
            if(labelled) return labelled.slice(0,160);
            if(attr(e,'aria-label')) return attr(e,'aria-label',160);
            if(e.labels?.length) return Array.from({length:Math.min(e.labels.length,4)},(_,i)=>text(e.labels[i],100,budget)).join(' ').slice(0,160);
            return attr(e,'alt',160)||attr(e,'title',160)||attr(e,'placeholder',160)||text(e,160,budget)||attr(e,'name',160);
          }
          function ref(e) {
            let id=ids.get(e); if(id&&nodes.has(id)) return id;
            if(nodes.size>=10000) for(const [key,node] of nodes) if(!node.isConnected) nodes.delete(key);
            if(nodes.size>=10000) throw new Error('element reference budget reached; reload before continuing');
            id='d'+documentId+'_'+(++sequence);ids.set(e,id);nodes.set(id,e);return id;
          }
          function resolve(target) {
            if(/^d(?:[0-9]+|[0-9a-f]+_[0-9]+)${'$'}/.test(target)) {
              const e=nodes.get(target);
              return e?.isConnected?{el:e}:{error:'stale_reference',message:'Read the page again; this element reference is no longer attached.'};
            }
            const matches=[];
            try {
              document.documentElement.matches(target);
              const complete=each(document,e=>{if(e.matches(target)) matches.push(e);return matches.length<2;});
              if(!complete) return {error:'target_search_limit',message:'Read the page and use its element reference.'};
            } catch(_) {return {error:'invalid_selector',message:'Use a valid CSS selector or an observed element reference.'};}
            if(matches.length>1) return {error:'ambiguous_target',message:'The selector matches multiple elements. Use a unique reference.'};
            return matches.length?{el:matches[0]}:{error:'target_not_found',message:'Target is not present.',retryable:true};
          }
          function parent(e) {return e.parentElement||e.getRootNode()?.host;}
          function disabled(e) {
            if(e.matches(':disabled')) return true;
            for(let p=e,depth=0;p&&depth++<256;p=parent(p))
              if(p.hasAttribute('inert')||attr(p,'aria-disabled')==='true') return true;
            return false;
          }
          function hit(x,y) {
            let e=document.elementFromPoint(x,y);
            for(let i=0;i<32&&e?.shadowRoot;i++) {
              const inner=e.shadowRoot.elementFromPoint?.(x,y);if(!inner||inner===e) break;e=inner;
            }
            return e;
          }
          function contains(e,other) {
            for(let p=other,i=0;p&&i++<256;p=parent(p)) if(p===e) return true;
            return false;
          }
          function geometry(e,scroll=true) {
            const s=getComputedStyle(e);
            if(!e.isConnected||s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse')
              return {error:'not_visible',message:'Target is not visible.',retryable:true};
            if(disabled(e)) return {error:'disabled',message:'Target is disabled or inert.',retryable:true};
            let r=e.getBoundingClientRect();
            const vv=window.visualViewport,w=vv?.width||innerWidth,h=vv?.height||innerHeight,ox=vv?.offsetLeft||0,oy=vv?.offsetTop||0;
            if(scroll&&(r.bottom<=oy||r.top>=oy+h||r.right<=ox||r.left>=ox+w)) {
              e.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});r=e.getBoundingClientRect();
            }
            const left=Math.max(ox,r.left),right=Math.min(ox+w,r.right),top=Math.max(oy,r.top),bottom=Math.min(oy+h,r.bottom);
            if(right<=left||bottom<=top) return {error:'outside_viewport',message:'Target has no visible area.',retryable:true};
            for(const [fx,fy] of [[.5,.5],[.2,.2],[.8,.2],[.2,.8],[.8,.8]]) {
              const x=left+(right-left)*fx,y=top+(bottom-top)*fy;
              if(contains(e,hit(x,y))) return {point:{x:(x-ox)/w,y:(y-oy)/h},
                bounds:{left:(left-ox)/w,top:(top-oy)/h,right:(right-ox)/w,bottom:(bottom-oy)/h},
                viewport:{width:w,height:h},ref:ref(e)};
            }
            if(scroll) {e.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});return geometry(e,false);}
            return {error:'covered',message:'Another element intercepts the target.',retryable:true};
          }
          const api={each,text,name,ref,resolve,disabled,geometry,attr,contains,hit};
          Object.defineProperty(window,'__droideBrowserV3',{value:api,configurable:true});return api;
        })();
    """.trimIndent()
}
