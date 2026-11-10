package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** This is not a JavaScript sandbox. */

internal object LocalUbuntuGraphqlDataAdmission {
    const val LIMITATION="Original compiled-layout/import/notice and genuine runtime/semantic/device acceptance are unrun in this source checkpoint. Literal import inspection is a conservative trusted-original correspondence gate, not arbitrary-JavaScript safety or a hostile same-UID sandbox."
    val entries=mapOf("service" to "node_modules/graphql-language-service/dist/index.js","graphql" to "node_modules/graphql/index.js")
    fun inspect(npm:File,active:()->Unit={}):JsonObject {
        NimRuntimeClosure.requireDirectory(npm);require(npm.absoluteFile==npm.canonicalFile)
        val files=sortedMapOf<String,JsonElement>();val manifests=sortedMapOf<String,JsonElement>();var bytes=0L
        val snapshot=LocalUbuntuGraphqlInstaller.snapshot(npm,active)
        require(snapshot.keys.all{it=="node_modules"||LocalUbuntuGraphqlPolicy.placements.keys.any{p->it==p||it.startsWith("$p/")}})
        for((placement,identity)in LocalUbuntuGraphqlPolicy.placements) {
            val manifest=File(npm,"$placement/package.json");val value=LocalUbuntuGraphqlPolicy.strictObject(LocalUbuntuGraphqlPolicy.read(manifest,262144,active))
            LocalUbuntuGraphqlPolicy.requirePackage(identity,value);manifests[placement]=JsonPrimitive(NimRuntimeClosure.measure(manifest,262144,active).second)
        }
        for((path,value)in snapshot) {
            val record=value.jsonObject
            when(record.getValue("kind").jsonPrimitive.content) {
                "directory" -> Unit
                "file" -> { val size=record.getValue("size").jsonPrimitive.long;bytes=Math.addExact(bytes,size);require(bytes<=LocalUbuntuGraphqlPolicy.MAX_TREE_BYTES&&files.size<LocalUbuntuGraphqlPolicy.MAX_FILES)
                    require(!path.endsWith(".node",true)&&!path.endsWith(".wasm",true)) { "Unreviewed native payload in original GraphQL closure" }
                    files[path]=buildJsonObject{put("sha256",record.getValue("sha256"));put("size",size);put("mode",record.getValue("mode"))}
                }
                else -> error("GraphQL original npm tree contains nonregular member")
            }
        }
        require(files.size==LocalUbuntuGraphqlPolicy.EXPECTED_FILES&&bytes==LocalUbuntuGraphqlPolicy.EXPECTED_BYTES)
        val imports=sortedMapOf<String,JsonElement>();val pending=java.util.ArrayDeque(entries.values);val visited=hashSetOf<String>()
        while(pending.isNotEmpty()) {
            active();val path=pending.removeFirst();if(!visited.add(path))continue;require(visited.size<=2048&&path in files)
            require(!Regex("(^|/)(server|cli|MessageProcessor|temp-bin)([./]|$)",RegexOption.IGNORE_CASE).containsMatchIn(path)) { "GraphQL original server/CLI route not admitted" }
            val targets=sortedMapOf<String,JsonElement>()
            if(path.endsWith(".js"))for(request in literalRequires(LocalUbuntuGraphqlPolicy.read(File(npm,path),2_097_152,active),path=="node_modules/vscode-languageserver-types/lib/umd/main.js")) {
                val target=resolve(path,request,files.keys);targets[request]=JsonPrimitive(target);pending.add(target)
            } else require(path.endsWith(".json"))
            imports[path]=JsonObject(targets)
        }
        return buildJsonObject {
            put("schema",1);put("profile",LocalUbuntuGraphqlPolicy.POLICY);put("entries",JsonObject(entries.mapValues{JsonPrimitive(it.value)}))
            put("files",JsonObject(files));put("imports",JsonObject(imports));put("manifests",JsonObject(manifests));put("reviewStatus","source-bound-finite-cjs-data-admission")
        }
    }
    internal fun resolve(parent:String,request:String,inventory:Set<String>):String {
        require(request.isNotEmpty()&&request.length<=4096&&request.none{it.code<32||it.code==127||it=='\\'})
        val base=if(request.startsWith("./")||request.startsWith("../")) {
            val root=LocalUbuntuGraphqlPolicy.placements.keys.single{parent.startsWith("$it/")}
            java.nio.file.Paths.get(parent).parent.resolve(request).normalize().toString().replace(File.separatorChar,'/').also { require(it.startsWith("$root/")) }
        } else {
            require(!request.startsWith('/')&&':' !in request)
            val name=request.substringBefore('/');val root="node_modules/$name";require(root in LocalUbuntuGraphqlPolicy.placements)
            if(request==name) {
                val identity=LocalUbuntuGraphqlPolicy.placements.getValue(root);val main=LocalUbuntuGraphqlPolicy.artifacts.getValue(identity).getValue("main").jsonPrimitive.content.removePrefix("./")
                "$root/$main"
            } else { require(request.split('/').none{it=="."||it==".."});"node_modules/$request" }
        }
        LocalUbuntuGraphqlPolicy.safeRelative(base)
        // Node's first resolution candidate is bound. A directory/package.json fallback is not guessed.
        return listOf(base,"$base.js","$base.json","$base/index.js").firstOrNull{it in inventory}
            ?.also{require(it.endsWith(".js")||it.endsWith(".json"))}?:error("Unresolved original GraphQL import: $parent -> $request")
    }
    private data class Token(val value:String,val quoted:Boolean=false)
    /** A bounded emitted-JS lexer identifies literals, comments, regex and template expressions. */

    private class Lexer(private val source:String) {
        val tokens=ArrayList<Token>();private var at=0
        private fun add(value:String,quoted:Boolean=false){require(tokens.size<200_000);tokens+=Token(value,quoted)}
        private fun string(quote:Char) {
            at++;val value=StringBuilder();var closed=false
            while(at<source.length) {
                val c=source[at++];if(c==quote){closed=true;break};require(c!='\n'&&c!='\r')
                if(c=='\\'){require(at<source.length);value.append(when(source[at++]){'\\'->'\\';'\''->'\'';'"'->'"';'n'->'\n';'r'->'\r';'t'->'\t';else->'\u0000'})}else value.append(c)
            };require(closed);add(value.toString(),true)
        }
        private fun regexp() {
            at++;var characterClass=false;var closed=false
            while(at<source.length) {
                val c=source[at++];require(c!='\n'&&c!='\r')
                if(c=='\\'){require(at<source.length);at++}
                else if(c=='[')characterClass=true else if(c==']')characterClass=false
                else if(c=='/'&&!characterClass){closed=true;break}
            };require(closed&&!characterClass)
            while(at<source.length&&source[at].isLetter()){require(source[at] in "dgimsuvy");at++};add("<regexp>")
        }
        private fun template(depth:Int) {
            at++;var closed=false
            while(at<source.length) {
                when(val c=source[at++]) {
                    '\\' -> {require(at<source.length);at++}
                    '`' -> {closed=true;break}
                    '$' -> if(at<source.length&&source[at]=='{'){at++;add("\${");scan(true,depth+1);add("}")}
                    else -> require(c!='\u0000')
                }
            };require(closed);add("<template>")
        }
        fun scan(stopAtBrace:Boolean=false,depth:Int=0) {
            require(depth<=64);var braces=0
            while(at<source.length) {
                val c=source[at]
                if(c.isWhitespace()){at++;continue}
                if(c=='/'&&at+1<source.length&&source[at+1]=='/'){at+=2;while(at<source.length&&source[at]!='\n')at++;continue}
                if(c=='/'&&at+1<source.length&&source[at+1]=='*'){val end=source.indexOf("*/",at+2);require(end>=0);at=end+2;continue}
                if(c=='\''||c=='"'){string(c);continue}
                if(c=='`'){template(depth);continue}
                val previous=tokens.lastOrNull()
                if(c=='/'&&(previous==null||!previous.quoted&&previous.value in setOf("(","[","{","=",":",",",";","!","?","&","|","+","-","*","%","^","~","<",">","return","throw","case","yield","=>"))){regexp();continue}
                if(c.isLetter()||c=='_'||c=='$'){val start=at++;while(at<source.length&&(source[at].isLetterOrDigit()||source[at]=='_'||source[at]=='$'))at++;add(source.substring(start,at));continue}
                require(c!='\\'&&c!='\u0000'){"Escaped emitted identifier not reviewed"}
                at++
                if(c=='}') {if(stopAtBrace&&braces==0)return;braces--;require(braces>=0)}
                if(c=='{')braces++
                add(c.toString())
            }
            require(!stopAtBrace&&braces==0){"Unclosed emitted template expression or braces"}
        }
    }
    internal fun literalRequires(source:String,allowReviewedTypesUmd:Boolean=false):Set<String> {
        require(source.toByteArray().size<=2_097_152);val lexer=Lexer(source);lexer.scan();val tokens=lexer.tokens;val requests=linkedSetOf<String>()
        for(i in tokens.indices) {
            val t=tokens[i];if(t.quoted)continue
            require(t.value !in setOf("import","eval","Function","mainModule","createRequire","_load","_compile")) { "Unreviewed dynamic original loader ${t.value}" }
            if(t.value=="require") {
                val reviewedUmd=allowReviewedTypesUmd&&i>=2&&i+3<tokens.size&&tokens[i-1].value=="("&&tokens[i-2].value in setOf("factory","function")&&tokens[i+1].value==","&&tokens[i+2].value=="exports"&&tokens[i+3].value==")"
                if(reviewedUmd)continue
                require(i==0||tokens[i-1].value !in setOf(".","?"))
                require(i+3<tokens.size&&tokens[i+1].value=="("&&tokens[i+2].quoted&&tokens[i+3].value==")") { "Computed or aliased original require rejected" }
                val value=tokens[i+2].value;require('\u0000' !in value);requests+=value
            }
            if(t.value=="module") {
                val exports=i+2<tokens.size&&tokens[i+1].value=="."&&tokens[i+2].value=="exports"
                val typeCheck=allowReviewedTypesUmd&&i>0&&tokens[i-1].value=="typeof"
                require(exports||typeCheck) { "Unexpected original module loader access" }
            }
        };return requests
    }
}
