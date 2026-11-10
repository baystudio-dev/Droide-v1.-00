package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Opt-in real public internet probe. Never called by offline JUnit; never uses an LLM/API key. */
object AgentWebLiveInternetProbe {
    @JvmStatic fun main(args:Array<String>) = runBlocking {
        require(args.size==1) { "Provide the output JSON file" }
        val root=Files.createTempDirectory("droide-public-internet-").toFile()
        val unsupportedPdf=object:AgentWebPdfBackend {
            override fun pageCount(file:File):Int=error("Android PDF rendering is not executed by this JVM probe")
            override fun text(file:File,page:Int):String?=error("No native PDF claim")
            override fun screenshot(file:File,page:Int,output:java.io.OutputStream):AgentWebImageSize=error("No native PDF claim")
        }
        val web=AgentWebResearch(root,SafeAgentWebTransport(),unsupportedPdf)
        val permissions=mutableListOf<String>()
        val authorize:suspend(String,String)->Unit={ action,url -> permissions+="$action $url" }
        val searches=mutableListOf<JsonObject>();val sources=mutableListOf<JsonObject>();val errors=mutableListOf<JsonObject>()
        val owner="live-public-probe"
        try {
            for(engine in listOf("duckduckgo","bing","brave")) {
                try { searches+=web.search(owner,AgentWebSearch.Request("Python urllib request official documentation",engine,5),authorize) {} }
                catch(e:Exception) { errors+=buildJsonObject { put("operation","search");put("engine",engine);put("error",e.message.orEmpty()); if(e is AgentWebSearchUnavailable) put("attempts",AgentWebResearch.attempts(e.attempts)) } }
            }
            for(url in listOf("https://opencode.ai/docs/tools/","https://docs.python.org/3/library/urllib.request.html","https://www.rfc-editor.org/rfc/rfc9110.html")) {
                try {
                    val page=web.open(owner,url,null,0,40,false,authorize,null)
                    val id=page.getValue("ref_id").jsonPrimitive.content
                    val total=page.getValue("total_lines").jsonPrimitive.int
                    val continuation=if(total>100) web.open(owner,null,id,total/2,20,false,authorize,null) else null
                    val found=web.find(owner,id,if(url.contains("opencode")) "webfetch" else if(url.contains("python")) "urlopen" else "HTTP",0,5,false,authorize)
                    val links=web.links(owner,id,0,5,authorize)
                    var followed:JsonObject?=null
                    for(link in links.getValue("links").jsonArray) {
                        val ref=link.jsonObject.getValue("ref_id").jsonPrimitive.content
                        try { followed=web.open(owner,null,ref,0,10,false,authorize,null);break }
                        catch(e:Exception) { errors+=buildJsonObject { put("operation","follow_link"); put("url",link.jsonObject["url"]!!); put("error",e.message.orEmpty()) } }
                    }
                    sources+=buildJsonObject {
                        put("open",page); continuation?.let { put("continuation",it) };put("find",found);put("links",links); followed?.let { put("followed",it) }
                    }
                } catch(e:Exception) { errors+=buildJsonObject { put("operation","open");put("url",url);put("error",e.message.orEmpty()) } }
            }
            var image:JsonObject?=null
            try { image=web.open(owner,"https://www.python.org/static/img/python-logo.png",null,0,10,false,authorize,null) }
            catch(e:Exception) { errors+=buildJsonObject { put("operation","image");put("error",e.message.orEmpty()) } }
            val proof=buildJsonObject {
                put("status",if(searches.isNotEmpty() && sources.size>=2) "PASS" else "PARTIAL")
                put("retrieved_at",Instant.now().toString());put("realPublicHttpRequests",true);put("apiKeysUsed",false)
                put("liveLlmInferenceExecuted",false);put("nativeAndroidPdfExecuted",false);put("realAndroidWebViewExecuted",false)
                put("searchSuccesses",searches.size);put("sourcesOpened",sources.size);put("searches",JsonArray(searches));put("sources",JsonArray(sources))
                image?.let { put("image",it) };put("errors",JsonArray(errors));put("permissionChecks",JsonArray(permissions.map(::JsonPrimitive)))
                put("note","Current public endpoints and production transport/parsers/store were exercised. Engine results can be irrelevant; blocks/errors remain recorded. This is not all-site availability, hosted Codex backend, device PDF/WebView or model-inference certification.")
            }
            val output=File(args.single());output.parentFile.mkdirs();output.writeText(proof.toString()+"\n")
            println("DROIDE_PUBLIC_INTERNET_PROBE ${proof["status"]} searches=${searches.size} sources=${sources.size} errors=${errors.size}")
            check(proof["status"]==JsonPrimitive("PASS")) { "Real internet workflow was incomplete; inspect the recorded failures" }
        } finally { web.close(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
