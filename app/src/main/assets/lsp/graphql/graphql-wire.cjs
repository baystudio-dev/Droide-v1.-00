'use strict';
// Authored finite JSON/stdio boundary. No GraphQL grammar or language intelligence.
const { TextDecoder } = require('node:util');
const LIMIT = Object.freeze({ header:8192, body:8388608, bytes:16777216, inputFrames:32,
  outputFrames:64, depth:64, nodes:200000, semantic:2097152, descriptor:12582912 });
const own = (o,k) => Object.prototype.hasOwnProperty.call(o,k);
function check(ok, code='invalid_data') { if (!ok) { const e=new Error(code); e.rpcCode=-32602; throw e; } }
function scalarString(s) {
  check(typeof s === 'string', 'string_required');
  for (let i=0;i<s.length;i++) { const c=s.charCodeAt(i);
    if (c>=0xd800&&c<=0xdbff) { const d=s.charCodeAt(++i); check(d>=0xdc00&&d<=0xdfff,'unpaired_surrogate'); }
    else check(!(c>=0xdc00&&c<=0xdfff),'unpaired_surrogate');
  } return s;
}
function exact(o, keys, optional=[]) {
  check(o!==null&&typeof o==='object'&&!Array.isArray(o),'object_required');
  check(keys.every(k=>own(o,k))&&Object.keys(o).every(k=>keys.includes(k)||optional.includes(k)),'object_keys'); return o;
}
function integer(n,min=0) { check(Number.isSafeInteger(n)&&n>=min,'safe_integer_required'); return n; }
function parse(bytes, cap=LIMIT.body) {
  check(Buffer.isBuffer(bytes)||bytes instanceof Uint8Array,'byte_input'); check(bytes.length<=cap,'json_bytes');
  check(!(bytes[0]===0xef&&bytes[1]===0xbb&&bytes[2]===0xbf),'json_bom');
  const s=new TextDecoder('utf-8',{fatal:true}).decode(bytes); let p=0,nodes=0;
  const white=()=>{while(p<s.length&&' \t\r\n'.includes(s[p]))p++;};
  function str(){ check(s[p]==='"','json_string'); const start=p++; let escaped=false;
    while(p<s.length){ const c=s[p++]; if(c==='"'&&!escaped)return scalarString(JSON.parse(s.slice(start,p))); if(c==='\\')escaped=!escaped;else escaped=false; }
    throw new Error('json_string_unterminated'); }
  function value(depth){check(depth<=LIMIT.depth&&++nodes<=LIMIT.nodes,'json_complexity');white(); const c=s[p];
    if(c==='{'){p++;white();const o=Object.create(null),seen=new Set();if(s[p]==='}'){p++;return o;}
      for(;;){white();const key=str();check(!seen.has(key),'json_duplicate_key');seen.add(key);white();check(s[p++]===':','json_colon');o[key]=value(depth+1);white();const end=s[p++];if(end==='}')return o;check(end===',','json_object_separator');}}
    if(c==='['){p++;white();const a=[];if(s[p]===']'){p++;return a;}for(;;){a.push(value(depth+1));white();const end=s[p++];if(end===']')return a;check(end===',','json_array_separator');}}
    if(c==='"')return str();
    for(const [word,v] of [['true',true],['false',false],['null',null]])if(s.startsWith(word,p)){p+=word.length;return v;}
    const m=/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(s.slice(p));check(m,'json_token');p+=m[0].length;const v=Number(m[0]);check(Number.isFinite(v)&&(!Number.isInteger(v)||Number.isSafeInteger(v)),'json_number');return v;
  }
  const result=value(0);white();check(p===s.length,'json_trailing');return result;
}
function data(v) {
  let nodes=0;const active=new Set();
  // Explicit depth avoids permitting custom toJSON/accessors/prototypes.
  function visit(x,d){check(++nodes<=LIMIT.nodes&&d<=LIMIT.depth,'json_complexity');if(x===null||typeof x==='boolean')return;if(typeof x==='string'){scalarString(x);return;}if(typeof x==='number'){check(Number.isFinite(x)&&(!Number.isInteger(x)||Number.isSafeInteger(x)),'json_number');return;}
    check(typeof x==='object'&&!active.has(x),'json_value');check(Array.isArray(x)||[Object.prototype,null].includes(Object.getPrototypeOf(x)),'json_plain_object');active.add(x);
    if(Array.isArray(x))check(Object.keys(x).length===x.length,'json_sparse_array');
    for(const k of Object.keys(x)){scalarString(k);const prop=Object.getOwnPropertyDescriptor(x,k);check(prop&&own(prop,'value'),'json_accessor');visit(prop.value,d+1);}active.delete(x);
  }visit(v,0);return v;
}
function encode(v,cap=LIMIT.body){data(v);const b=Buffer.from(JSON.stringify(v),'utf8');check(b.length>0&&b.length<=cap,'json_bytes');return b;}
function idKey(id){check(typeof id==='string'||Number.isSafeInteger(id),'rpc_id');if(typeof id==='string'){scalarString(id);check(id.length<=1024,'rpc_id_length');}return typeof id+':'+id;}
function rpc(m){check(m&&typeof m==='object'&&!Array.isArray(m)&&m.jsonrpc==='2.0','rpc_object');if(own(m,'id'))idKey(m.id);
  if(own(m,'method')){check(typeof m.method==='string'&&m.method.length>0&&m.method.length<=256&&!own(m,'result')&&!own(m,'error'),'rpc_request');exact(m,['jsonrpc','method'],['id','params']);}
  else{exact(m,['jsonrpc','id'],['result','error']);check(own(m,'result')!==own(m,'error'),'rpc_response');}return m;
}
function frame(v,cap=LIMIT.body){const b=encode(v,cap);return Buffer.concat([Buffer.from('Content-Length: '+b.length+'\r\n\r\n'),b]);}
class Frames {
  constructor(receive,fail){this.receive=receive;this.fail=fail;this.buffer=Buffer.alloc(0);this.length=null;this.failed=false;}
  push(bytes){if(this.failed)return;try{check(Buffer.isBuffer(bytes)||bytes instanceof Uint8Array,'frame_bytes');check(this.buffer.length+bytes.length<=LIMIT.bytes,'input_bytes');this.buffer=Buffer.concat([this.buffer,bytes]);let count=0;
    for(;;){if(this.length===null){const at=this.buffer.indexOf('\r\n\r\n');if(at<0){check(this.buffer.length<=LIMIT.header,'header_bytes');break;}check(at+4<=LIMIT.header,'header_bytes');const h=this.buffer.subarray(0,at);check(h.every(c=>c>=32&&c<127||c===9||c===13||c===10),'header_ascii');let len=null,type=false;
      for(const line of h.toString('ascii').split('\r\n')){const m=/^([A-Za-z-]+):[ \t]*(.*)$/.exec(line);check(m,'header_format');if(m[1].toLowerCase()==='content-length'){check(len===null&&/^[1-9][0-9]{0,7}$/.test(m[2]),'content_length');len=Number(m[2]);}else{check(m[1].toLowerCase()==='content-type'&&!type&&/^application\/(?:vscode-jsonrpc|json)(?:;[ \t]*charset=utf-8)?$/i.test(m[2]),'content_type');type=true;}}
      check(len>0&&len<=LIMIT.body,'frame_body');this.length=len;this.buffer=this.buffer.subarray(at+4);}
      if(this.buffer.length<this.length)break;check(++count<=LIMIT.inputFrames,'input_frame_burst');const b=this.buffer.subarray(0,this.length);this.buffer=this.buffer.subarray(this.length);this.length=null;this.receive(parse(b),b.length);}
  }catch(e){this.failed=true;this.fail(e);}}
  end(){if(!this.failed&&(this.length!==null||this.buffer.length)){this.failed=true;this.fail(new Error('truncated_frame'));}}
}
class Writer {
  constructor(output,fail,timeout=15000){this.output=output;this.fail=fail;this.timeout=timeout;this.queue=[];this.bytes=0;this.active=null;this.closed=false;this.settled=false;
    output.on('error',e=>this.abort(e));output.on('close',()=>{if(!this.settled)this.abort(new Error('output_closed'));});}
  send(v,cap=LIMIT.body){check(!this.closed,'writer_closed');const b=frame(v,cap);check(this.queue.length+(this.active?1:0)<LIMIT.outputFrames&&this.bytes+b.length<=LIMIT.bytes,'output_queue');this.bytes+=b.length;return new Promise((resolve,reject)=>{this.queue.push({b,resolve,reject});this.pump();});}
  pump(){if(this.closed||this.active||!this.queue.length)return;const q=this.active=this.queue.shift();let callback=false,drained=false,returned=false;
    const finish=()=>{if(!returned||!callback||!drained||this.active!==q)return;clearTimeout(q.timer);this.output.removeListener('drain',onDrain);this.bytes-=q.b.length;this.active=null;q.resolve();this.pump();};
    const onDrain=()=>{drained=true;finish();};q.cleanup=()=>this.output.removeListener('drain',onDrain);this.output.once('drain',onDrain);
    q.timer=setTimeout(()=>this.abort(new Error('output_timeout')),this.timeout);
    try{const accepted=this.output.write(q.b,e=>{if(e){this.abort(e);return;}callback=true;finish();});drained=drained||accepted;returned=true;finish();}catch(e){this.abort(e);}
  }
  abort(e){if(this.closed)return;this.closed=true;if(this.active){clearTimeout(this.active.timer);this.active.cleanup();this.active.reject(e);}for(const q of this.queue)q.reject(e);this.queue=[];this.fail(e);/* active ownership retained until host observes real stream settlement */}
  idle(){return !this.active&&!this.queue.length;}
  finish(){check(this.idle()&&!this.closed,'writer_not_settled');this.closed=true;this.settled=true;}
}
module.exports={LIMIT,own,check,exact,integer,scalarString,parse,data,encode,idKey,rpc,frame,Frames,Writer};
