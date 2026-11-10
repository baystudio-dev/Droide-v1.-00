package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Conservative included-for-obligations census, not a linked SBOM or legal/source-to-binary attestation. */
internal object LocalUbuntuTomlNoticePolicy {
    const val PREFIX = "payload/notices/"
    const val SOURCE_ROOT = "https://docs.rs/crate/webpki-roots/0.22.4/source/"
    const val MAX_RESPONSE = 2_097_152
    const val MAX_TOTAL = 16_777_216L
    val hashes = mapOf(
        "COMPONENTS.json" to "aa8ce3a82514c263b02e6f2221f6ca624414b9fbbb68365a9884523dd8acbc9b",
        "TAPLO-THIRD-PARTY-NOTICES.txt" to "c67d4c7be6723482ae45e3a46ba3d6904299b6365d9756f4316427eb2968d1e4",
        "TOML_NOTICE_COVERAGE.json" to "2e81d528a4865eed57429059df4a212c0c991fc81e3757ce6fa46acf52181cfa",
        "UNRESOLVED.json" to "384354080b303e426e9fc394d6367a2cf1cf8263a98ed3be54ca3ff98075a12c",
        "DISPOSITIONS.json" to "8f8569dc98c17dd026174ebb09983bd6bc491f639e1f51bb8ac5d80c0c0bcff1",
        "NOTICE-FULFILLMENT.json" to "2964553e7df3044f6e5ec96e82da77bfa63013f57e2c227cf706448d4ce58c25",
        "RING-CLARIFICATION.json" to "c52cd64e89d7b400b9c18797a3b344bd03aa491fe640a607db82b2a8196b9e15",
        "SOURCE-AVAILABILITY.json" to "11c2ab08e4752e1a9f1cda632b5e6d3a3d6b4a3e55940a25847dd7a6654ffe3d",
        "TAPLO-NOTICE-FULFILLMENT.txt" to "8e6dd55ed244f68f8256da4a05573b2adac3266160557572b7cb311a6980691c")
    fun prepare(stage:File,readAsset:(String)->ByteArray) {
        for((name,hash)in hashes) { val bytes=readAsset("lsp/toml/notices/$name");require(bytes.size<=2_097_152&&LocalUbuntuTomlPolicy.sha256(bytes)==hash);LocalUbuntuTomlInstaller.write(stage,PREFIX+name,bytes.toString(Charsets.UTF_8)) }
    }
    private fun source(root:File,active:()->Unit={}) = LocalUbuntuTomlPolicy.strictObject(LocalUbuntuTomlPolicy.read(File(root,PREFIX+"SOURCE-AVAILABILITY.json"),65536,active))
    fun requireBundle(root:File,active:()->Unit={}) {
        for((name,hash)in hashes)require(NimRuntimeClosure.measure(File(root,PREFIX+name),2_097_152,active).second==hash) { "Taplo supplemental legal asset changed: $name" }
        val dispositions=LocalUbuntuTomlPolicy.strictObject(LocalUbuntuTomlPolicy.read(File(root,PREFIX+"DISPOSITIONS.json"),262144,active)).getValue("records").jsonArray
        require(dispositions.size==244&&dispositions.map{it.jsonObject.getValue("id").jsonPrimitive.content}.toSet().size==244)
        require(dispositions.all{it.jsonObject.getValue("disposition")==JsonPrimitive("conservative_include_for_obligations")})
    }
    /** Called only by acquisition/publication. Launch proof validates the bound receipt without network. */
    suspend fun refresh(root:File):JsonObject = withTimeout(60_000) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
        requireBundle(root);val inventory=source(root)
        val files=inventory.getValue("files").jsonArray;val dirs=inventory.getValue("directories").jsonArray
        require(files.size==12&&dirs.size==5)
        val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(10,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
        val total=AtomicLong();val gate=Semaphore(4)
        val receipts=coroutineScope {(files.map{it.jsonObject to false}+dirs.map{it.jsonObject to true}).map { (entry,dir)->async(Dispatchers.IO){gate.withPermit {
            ensureActive();val url=entry.getValue("url").jsonPrimitive.content
            require(url==SOURCE_ROOT+entry.getValue("source_path").jsonPrimitive.content)
            val call=client.newCall(Request.Builder().url(url).header("Accept","text/html").header("User-Agent","Droide-Taplo-source-availability/1").build())
            call.timeout().deadlineNanoTime(deadline)
            call.execute().use { response->
                require(response.code==200&&response.request.url.toString()==url) { "Exact covered source unavailable: $url HTTP ${response.code}" }
                val body=requireNotNull(response.body);require(body.contentType()?.type=="text") { "Nontext covered source: $url" }
                val out=java.io.ByteArrayOutputStream();body.byteStream().use{stream->val b=ByteArray(8192);while(true){ensureActive();val n=stream.read(b);if(n<0)break;require(out.size()+n<=MAX_RESPONSE&&total.addAndGet(n.toLong())<=MAX_TOTAL);out.write(b,0,n)}}
                checkResponse(entry,dir,out.toByteArray())
            }
        }}}.awaitAll()}
        buildJsonObject{put("schema",1);put("checkedAtEpochMs",System.currentTimeMillis());put("inventorySha256",hashes.getValue("SOURCE-AVAILABILITY.json"));put("sourceForm","published_preferred_editable_source");put("modifications","none_for_this_route");put("publicWithoutAuthentication",true);put("responses",JsonArray(receipts.sortedBy{it.getValue("url").jsonPrimitive.content}))}
    }
    internal fun checkResponse(entry:JsonObject,directory:Boolean,bytes:ByteArray):JsonObject {
        require(bytes.size in 1..MAX_RESPONSE);val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val doc=Jsoup.parse(text);val url=entry.getValue("url").jsonPrimitive.content
        require(url==SOURCE_ROOT+entry.getValue("source_path").jsonPrimitive.content)
        val content=if(directory) {
            val entries=doc.select("a[href]").map{it.attr("href")}.filter{it.startsWith("./")}.map{it.removePrefix("./")}.filter{it!="../"}.sorted()
            require(entries.toSet().size==entries.size&&entries==entry.getValue("entries").jsonArray.map{it.jsonPrimitive.content}.sorted()) { "Incomplete covered-source directory: $url" }
            entries.joinToString("\n").toByteArray()
        } else {
            val nodes=doc.select("div#source-code pre code");require(nodes.size==1) { "Covered-source text missing: $url" }
            val value=nodes.single().wholeText().toByteArray(Charsets.UTF_8);val expected=entry.getValue("content").jsonObject
            require(value.size.toLong()==expected.getValue("bytes").jsonPrimitive.long&&LocalUbuntuTomlPolicy.sha256(value)==expected.getValue("sha256").jsonPrimitive.content) { "Exact covered-source text changed: $url" };value
        }
        return buildJsonObject{put("url",url);put("kind",if(directory)"directory"else"file");put("contentSha256",LocalUbuntuTomlPolicy.sha256(content));put("responseBytes",bytes.size);put("responseSha256",LocalUbuntuTomlPolicy.sha256(bytes));put("httpStatus",200)}
    }
    fun validateAvailability(root:File,receipt:JsonObject,active:()->Unit={}) {
        require(receipt.keys==setOf("schema","checkedAtEpochMs","inventorySha256","sourceForm","modifications","publicWithoutAuthentication","responses"))
        require(receipt["schema"]==JsonPrimitive(1)&&receipt["inventorySha256"]==JsonPrimitive(hashes.getValue("SOURCE-AVAILABILITY.json"))&&receipt["sourceForm"]==JsonPrimitive("published_preferred_editable_source")&&receipt["modifications"]==JsonPrimitive("none_for_this_route")&&receipt["publicWithoutAuthentication"]==JsonPrimitive(true))
        require(receipt.getValue("checkedAtEpochMs").jsonPrimitive.long>0)
        val inventory=source(root,active);val expected=(inventory.getValue("files").jsonArray.map{val e=it.jsonObject;e.getValue("url").jsonPrimitive.content to e.getValue("content").jsonObject.getValue("sha256").jsonPrimitive.content}+
            inventory.getValue("directories").jsonArray.map{val e=it.jsonObject;e.getValue("url").jsonPrimitive.content to LocalUbuntuTomlPolicy.sha256(e.getValue("entries").jsonArray.map{v->v.jsonPrimitive.content}.sorted().joinToString("\n").toByteArray())}).toMap()
        val responses=receipt.getValue("responses").jsonArray;require(responses.size==17)
        require(responses.map{it.jsonObject.getValue("url").jsonPrimitive.content}.toSet()==expected.keys)
        var bytes=0L
        for(item in responses) { val e=item.jsonObject;require(e["httpStatus"]==JsonPrimitive(200)&&e.getValue("contentSha256").jsonPrimitive.content==expected.getValue(e.getValue("url").jsonPrimitive.content));val n=e.getValue("responseBytes").jsonPrimitive.long;require(n in 1..MAX_RESPONSE);bytes+=n;require(bytes<=MAX_TOTAL) }
    }
    fun derive(root:File,inventory:JsonObject,availability:JsonObject,active:()->Unit={}):JsonObject {
        require(inventory.keys==LocalUbuntuTomlPolicy.placements.keys) { "Taplo notice inventory must contain both exact originals" }
        requireBundle(root,active);validateAvailability(root,availability,active)
        val originals=linkedMapOf<String,JsonElement>();val markers=linkedSetOf<String>()
        val known=LocalUbuntuTomlPolicy.read(File(root,PREFIX+"TAPLO-THIRD-PARTY-NOTICES.txt"),2_097_152,active)+LocalUbuntuTomlPolicy.read(File(root,PREFIX+"TAPLO-NOTICE-FULFILLMENT.txt"),65536,active)
        for((placement,artifact)in inventory)for((path,record)in artifact.jsonObject.getValue("files").jsonObject) {
            val full="$placement/$path";val file=File(root,"payload/npm/$full")
            if(Regex("(?i)(license|notice|copying|copyright|readme)").containsMatchIn(path)||path.endsWith(".js")||path.endsWith(".ts")) {
                originals[full]=record
                val text=LocalUbuntuTomlPolicy.read(file,67_108_864,active)
                Regex("SPDX-License-Identifier:[ \\t]*([^\\r\\n*]{1,200})").findAll(text).forEach { match->
                    val expression=match.groupValues[1].trim();markers+=expression
                    expression.split(Regex("[ ()]+|AND|OR|WITH")).filter{it.isNotBlank()}.forEach { id->require(id in known) { "Concrete uncovered original SPDX term: $id in $full" } }
                }
            }
        }
        return buildJsonObject{put("schema",1);put("status","CONSERVATIVE_INCLUDED_FOR_OBLIGATIONS");put("candidateCount",244);put("linkedSbom",false);put("originalInventorySha256",LocalUbuntuTomlPolicy.sha256(inventory.toString().toByteArray()));put("retainedOriginalNoticeWitnesses",JsonObject(originals));put("observedSpdx",JsonArray(markers.sorted().map(::JsonPrimitive)));put("assets",JsonObject(hashes.mapValues{JsonPrimitive(it.value)}));put("availability",availability);put("limitations",TomlWasmDataAdmission.LIMITATION)}
    }
}
