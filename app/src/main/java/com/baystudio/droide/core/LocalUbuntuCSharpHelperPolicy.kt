package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

/** Independent C#-only receipt/tree for the accepted original Node artifact. npm/npx/corepack
 * stay data. */
internal object LocalUbuntuCSharpHelperPolicy {
    const val FAMILY = "runtime.csharp-helper"
    const val VERSION = "22.23.3"
    const val COMMAND = "droide-csharp-helper"
    const val TAG = "v22.23.3"
    const val TOP = "node-v22.23.3-linux-arm64"
    const val ENTRY = "$TOP/bin/node"
    const val ASSET = "$TOP.tar.gz"
    const val ASSET_URL = "https://nodejs.org/dist/v$VERSION/$ASSET"
    const val CHECKSUM_URL = "https://nodejs.org/dist/v$VERSION/SHASUMS256.txt"
    const val SHA256 = "5ced2d48d1d7198739b7f86804de0171aefb6823b684b12341d3321afc3cb0b2"
    const val SOURCE_COMMIT = "80dc632040e6bada37aac1220dde9c79581c9c22"
    const val SOURCE_BINDING_SHA256 = "cbe2acd9df03cee1f27aa02d4aabc938f4cb66b2e437c3359c398298b469b4c0"
    const val ARTIFACT_BYTES = 56_655_163L
    const val MAX_FILES = 8192
    const val MAX_TREE_BYTES = 512L * 1024 * 1024
    const val ROOT = "/opt/droide/packages/$FAMILY/$VERSION/arm64-v8a"
    const val EXECUTABLE = "$ROOT/payload/tree/$ENTRY"
    const val KEY = "$FAMILY@$VERSION"
    const val CONFIG = "$TOP/include/node/config.gypi"
    val recipe = CSharpInstallRecipe(FAMILY, VERSION, COMMAND, CSharpArtifactRole.PRIVATE_NODE_HELPER, ASSET_URL, ASSET,
        CSharpArtifactDigest(CSharpArtifactDigest.Algorithm.SHA256, SHA256), ARTIFACT_BYTES, ARTIFACT_BYTES,
        MAX_FILES, MAX_TREE_BYTES, ENTRY, CHECKSUM_URL)
    fun requireTree(entry: File, active: () -> Unit = {}): NimRuntimeClosure.Inventory {
        require(entry.name == "node" && entry.parentFile?.name == "bin" && entry.parentFile?.parentFile?.name == TOP)
        val root = entry.parentFile!!.parentFile!!.parentFile!!
        return NimRuntimeClosure.inspectTree(root,setOf(ENTRY),setOf(ENTRY,CONFIG,"$TOP/LICENSE","$TOP/README.md"),
            MAX_FILES,MAX_TREE_BYTES,top=TOP,safeLinks=true,active=active).also { requireConfiguration(File(root,CONFIG),active) }
    }
    fun requireConfiguration(file: File, active: () -> Unit = {}): String {
        val raw = NimRuntimeClosure.readText(file,2*1024*1024,active)
        val config = GypData(raw).parse() as? JsonObject ?: error("Node shipped config must be a dictionary")
        val vars = config["variables"] as? JsonObject ?: error("Node shipped variables missing")
        fun text(key: String): String = (vars[key] as? JsonPrimitive)?.content ?: error("Node config $key missing")
        require(text("target_arch") == "arm64" && text("node_shared") == "false" && text("node_shared_libuv") == "false")
        require(text("node_builtin_modules_path") == "" && text("node_module_version") == "127")
        require(vars["node_shared_builtin_modules"] == null || vars["node_shared_builtin_modules"] == JsonArray(emptyList()))
        require(vars["node_snapshot_main"] == null || vars["node_snapshot_main"] == JsonPrimitive(""))
        require(vars["node_builtin_modules"] == null || vars["node_builtin_modules"] == JsonArray(emptyList()))
        require(vars["node_linked_module"] == null || vars["node_linked_module"] == JsonArray(emptyList()) || vars["node_linked_module"] == JsonPrimitive(""))
        return NimRuntimeClosure.measure(file,2L*1024*1024,active).second
    }
    /** Data-only GYP literal grammar. No eval, import, interpolation, tuple, names, or duplicate keys. */
    internal class GypData(private val s:String) {
        private var i=0; private var nodes=0
        fun parse():JsonElement { require(s.length in 1..2*1024*1024); val result=value(0); white(); require(i==s.length); return result }
        private fun white() { while(i<s.length) { if(s[i].isWhitespace()) i++ else if(s[i]=='#') { while(i<s.length && s[i]!='\n') i++ } else break } }
        private fun take(c:Char):Boolean { white(); return if(i<s.length&&s[i]==c){i++;true}else false }
        private fun string():String {
            white(); require(i<s.length && s[i] in "'\""); val quote=s[i++]; val out=StringBuilder()
            while(i<s.length) { val c=s[i++]; if(c==quote)return out.toString(); require(c.code>=32 || c=='\n' || c=='\t')
                if(c=='\\') { require(i<s.length); out.append(when(val e=s[i++]) { '\\'->'\\'; '\''->'\''; '"'->'"'; 'n'->'\n'; 'r'->'\r'; 't'->'\t'; else->error("Unsupported GYP escape $e") }) } else out.append(c)
            }; error("Unterminated GYP string")
        }
        private fun value(depth:Int):JsonElement {
            white(); require(depth<=32 && ++nodes<=100_000 && i<s.length)
            if(s[i] in "'\"")return JsonPrimitive(string())
            if(take('{')) { val out=linkedMapOf<String,JsonElement>(); if(take('}'))return JsonObject(out)
                while(true) { val k=string(); require(take(':')); require(out.put(k,value(depth+1))==null); if(take('}'))break; require(take(',')); if(take('}'))break }; return JsonObject(out) }
            if(take('[')) { val out=mutableListOf<JsonElement>(); if(take(']'))return JsonArray(out)
                while(true) { out+=value(depth+1); if(take(']'))break; require(take(',')); if(take(']'))break }; return JsonArray(out) }
            val start=i; while(i<s.length && (s[i].isLetterOrDigit()||s[i]=='-')) i++
            val word=s.substring(start,i)
            return when(word) { "True"->JsonPrimitive(true); "False"->JsonPrimitive(false); "None"->JsonNull
                else->{ require(word.matches(Regex("-?(0|[1-9][0-9]{0,17})"))); JsonPrimitive(word.toLong()) } }
        }
    }
}
