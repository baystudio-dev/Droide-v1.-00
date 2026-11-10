package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.*

/** Bounded binary-format and generated-template witnesses. */

internal object TomlWasmDataAdmission {
    const val MAX_JS_BYTES = 67_108_864
    const val LIMITATION = "Trusted publisher/source-alignment witness; static scans do not prove arbitrary JS control flow, all imports, linked SBOM or source-to-binary identity. Genuine API/runtime health is separate."
    fun inspect(npm:File,active:()->Unit={}):JsonObject {
        val lsp=File(npm,"node_modules/@taplo/lsp");val core=File(npm,"node_modules/@taplo/core")
        for((identity,path)in listOf("@taplo/lsp@0.8.0" to lsp,"@taplo/core@0.2.0" to core))
            LocalUbuntuTomlPolicy.requirePackage(identity,LocalUbuntuTomlPolicy.strictObject(LocalUbuntuTomlPolicy.read(File(path,"package.json"),65536,active)))
        val js=LocalUbuntuTomlPolicy.read(File(lsp,"dist/index.js"),MAX_JS_BYTES,active)
        val types=LocalUbuntuTomlPolicy.read(File(lsp,"dist/index.d.ts"),1_048_576,active)
        require(listOf("TaploLsp","initialize","send","dispose").all { it in types }) { "Taplo public declaration witness changed" }
        val coreJs=LocalUbuntuTomlPolicy.read(File(core,"dist/index.js"),1_048_576,active)
        LocalUbuntuTomlPolicy.read(File(core,"dist/index.d.ts"),1_048_576,active)
        val module=inlineModule(js,active);val wasm=inspectModule(module,active)
        val imports=wasm.getValue("imports").jsonArray
        imports.forEach { require(it.jsonObject.getValue("name").jsonPrimitive.content in js) { "WASM import missing from original JS glue witness" } }
        val references=(literalReferences(js)+literalReferences(coreJs)).distinct().sorted()
        return buildJsonObject {
            put("schema",1);put("sourceBindingSha256",LocalUbuntuTomlPolicy.SOURCE_BINDING_SHA256)
            put("entrySha256",LocalUbuntuTomlPolicy.sha256(js.toByteArray()));put("typesSha256",LocalUbuntuTomlPolicy.sha256(types.toByteArray()))
            put("coreEntrySha256",LocalUbuntuTomlPolicy.sha256(coreJs.toByteArray()));put("wasm",wasm)
            put("literalReferences",JsonArray(references.map(::JsonPrimitive)))
            put("dynamicConstructsObserved",Regex("\\b(?:require|import)\\s*\\(\\s*[^'\"\\s]").containsMatchIn(js))
            put("limitation",LIMITATION)
        }
    }

    internal fun inlineModule(js:String,active:()->Unit={}):ByteArray {
        require(js.toByteArray().size<=MAX_JS_BYTES);var at=0;var found:ByteArray?=null;var strings=0
        while(at<js.length) {
            if(at%65536==0)active()
            val quote=js[at++]
            if(quote!='\''&&quote!='"')continue
            val start=at;var escaped=false;var end=-1
            while(at<js.length) { val c=js[at++];if(c=='\\'){escaped=true;require(at<js.length);at++}else if(c==quote){end=at-1;break} }
            if(end<0)break // Not a JS parser: comment/regex quote fragments are not semantic rejection.
            require(++strings<=262144) { "Taplo string witness cap" }
            val length=end-start
            if(length<12||length>MAX_JS_BYTES)continue
            val raw=js.substring(start,end)
            val value=if(escaped && raw.take(32).contains("\\"))decodeLiteral(raw) else if(!escaped)raw else continue
            if(!value.startsWith("AGFzbQ"))continue
            require(value.length%4==0&&value.all{it in 'A'..'Z'||it in 'a'..'z'||it in '0'..'9'||it in "+/="}) { "Unsupported inline WASM base64 encoding" }
            val bytes=runCatching{Base64.getDecoder().decode(value)}.getOrElse{error("Invalid inline WASM base64")}
            require(Base64.getEncoder().encodeToString(bytes)==value&&bytes.size<=67_108_864)
            if(bytes.size>=8&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0,97,115,109,1,0,0,0))) {
                require(found==null) { "Ambiguous inline WASM literals" };found=bytes
            }
        }
        return requireNotNull(found) { "No supported contiguous version-1 inline WASM literal; original encoding needs review" }
    }
    private fun decodeLiteral(raw:String):String {
        val out=StringBuilder();var i=0
        while(i<raw.length) { val c=raw[i++];if(c!='\\')out.append(c)else {
            require(i<raw.length);when(val e=raw[i++]) {
                '\\','\'','"','/'->out.append(e)
                'x','u'->{val n=if(e=='x')2 else 4;require(i+n<=raw.length);out.append(raw.substring(i,i+n).toInt(16).toChar());i+=n}
                else->error("Unsupported inline WASM string escape")
            }
        } };return out.toString()
    }
    internal fun literalReferences(js:String):List<String> {
        val refs=Regex("(?:\\brequire\\s*\\(|\\bimport\\s*\\(|\\bfrom\\s+)[\\s]*['\"]([^'\"\\r\\n]{1,4096})['\"]").findAll(js).map{it.groupValues[1]}.toList()
        require(refs.size<=65536)
        val builtins=setOf("assert","buffer","crypto","events","fs","http","https","module","os","path","perf_hooks","process","stream","string_decoder","timers","url","util","v8","vm","worker_threads","zlib")
        for(ref in refs) {
            require(!ref.endsWith(".node")&&!ref.endsWith(".wasm")) { "Unsupported external native/WASM loader: $ref" }
            require(ref=="@taplo/core"||ref.removePrefix("node:") in builtins||ref.startsWith("./")&&!ref.contains("../")) { "Uncovered literal module target: $ref" }
        };return refs
    }
    internal fun inspectModule(bytes:ByteArray,active:()->Unit={}):JsonObject {
        require(bytes.size in 8..67_108_864&&bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0,97,115,109,1,0,0,0)))
        val r=Reader(bytes,8,bytes.size);val sections=mutableListOf<JsonElement>();val imports=mutableListOf<JsonElement>();val exports=mutableListOf<JsonElement>();val memories=mutableListOf<JsonElement>();val tables=mutableListOf<JsonElement>()
        var order=0;val seen=hashSetOf<Int>()
        while(r.remaining>0) { active();val id=r.byte();require(id in 0..12);val length=r.u32();val s=r.slice(length)
            if(id!=0){val rank=if(id==12)9 else if(id>=10)id+1 else id;require(seen.add(id)&&rank>order);order=rank}
            sections+=buildJsonObject{put("id",id);put("bytes",length)}
            when(id) {
                0-> { s.name();s.skip() }
                2->{repeat(s.count(65536)){val module=s.name();val name=s.name();val kind=s.byte();require(module=="wbg"&&kind==0&&(name.startsWith("__wbg_")||name.startsWith("__wbindgen_"))) { "Unexpected WASM import: $module/$name/$kind" };val type=s.u32();imports+=buildJsonObject{put("module",module);put("name",name);put("kind",kind);put("type",type)}}}
                4->{repeat(s.count(4)){val type=s.byte();require(type in setOf(0x70,0x6f));tables+=s.limits(false)}}
                5->{repeat(s.count(1)){memories+=s.limits(true)}}
                7->{val names=hashSetOf<String>();repeat(s.count(65536)){val name=s.name();require(names.add(name));val kind=s.byte();require(kind in 0..3);val index=s.u32();exports+=buildJsonObject{put("name",name);put("kind",kind);put("index",index)}}}
                else->s.skip()
            };require(s.remaining==0) { "WASM section length mismatch" }
        }
        require(memories.size==1&&exports.isNotEmpty()) { "Expected one defined unshared WASM memory and exports" }
        return buildJsonObject{put("sha256",LocalUbuntuTomlPolicy.sha256(bytes));put("bytes",bytes.size);put("version",1);put("sections",JsonArray(sections));put("imports",JsonArray(imports));put("exports",JsonArray(exports));put("memories",JsonArray(memories));put("tables",JsonArray(tables))}
    }
    private class Reader(val bytes:ByteArray,var at:Int,val end:Int) {
        val remaining get()=end-at
        fun byte():Int { require(at<end);return bytes[at++].toInt() and 255 }
        fun u32():Int { var n=0L;var shift=0;repeat(5){val b=byte();require(it<4||b<=15);n=n or ((b and 127).toLong() shl shift);if(b and 128==0){require(n<=Int.MAX_VALUE);return n.toInt()};shift+=7};error("WASM LEB overflow") }
        fun count(max:Int)=u32().also{require(it<=max)}
        fun name():String { val n=count(65536);require(n<=remaining);val s=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,at,n)).toString();at+=n;return s }
        fun slice(n:Int):Reader { require(n<=remaining);return Reader(bytes,at,at+n).also{at+=n} }
        fun skip(){at=end}
        fun limits(memory:Boolean):JsonObject { val flags=byte();require(flags in 0..1) { "Shared/memory64 limits unsupported" };val initial=u32();val maximum=if(flags==1)u32()else null;require(maximum==null||maximum>=initial);if(memory)require(initial<=4096&&(maximum==null||maximum<=65536));return buildJsonObject{put("initial",initial);put("maximum",maximum?.let(::JsonPrimitive)?:JsonNull);put("shared",false);put("memory64",false)} }
    }
}
