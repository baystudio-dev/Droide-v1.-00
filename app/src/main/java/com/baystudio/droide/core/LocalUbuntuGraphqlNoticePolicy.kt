package com.baystudio.droide.core

import java.io.File
import kotlinx.serialization.json.*

/** Original versus supplemental notice provenance is explicit; no filename-only absence inference. */
internal object LocalUbuntuGraphqlNoticePolicy {
    const val PREFIX="payload/notices/"
    val hashes=mapOf("NOTICE-REQUIREMENTS.json" to "1d0ada5f81923e397dedf0d65491f3c9e3aef8d7ce037619cbc273098a6c60af",
        "GraphQL-5.7.0-NOTICES.txt" to "b93f53bfce8b3588e86805930a40068e90a94cf751c360adc5e31e20ad211e1d")
    fun prepare(stage:File,readAsset:(String)->ByteArray) {
        for((name,hash)in hashes) {
            val bytes=readAsset(if(name=="NOTICE-REQUIREMENTS.json")"lsp/graphql/$name"else"legal/licenses/$name")
            require(bytes.size<=262144&&LocalUbuntuGraphqlPolicy.sha256(bytes)==hash)
            LocalUbuntuGraphqlInstaller.write(stage,PREFIX+name,bytes.toString(Charsets.UTF_8))
        }
    }
    fun requireBundle(root:File,active:()->Unit={}) {
        for((name,hash)in hashes)require(NimRuntimeClosure.measure(File(root,PREFIX+name),262144,active).second==hash)
    }
    fun derive(root:File,inventory:JsonObject,active:()->Unit={}):JsonObject {
        require(inventory.keys==LocalUbuntuGraphqlPolicy.placements.keys);requireBundle(root,active)
        val requirements=LocalUbuntuGraphqlPolicy.strictObject(LocalUbuntuGraphqlPolicy.read(File(root,PREFIX+"NOTICE-REQUIREMENTS.json"),262144,active))
        val source=requirements.getValue("records").jsonArray;require(source.size==5)
        val witnesses=sortedMapOf<String,JsonElement>();val all=sortedMapOf<String,JsonElement>();val coverage=mutableListOf<JsonElement>()
        for((placement,artifact)in inventory)for((path,record)in artifact.jsonObject.getValue("files").jsonObject) {
            active();val full="$placement/$path";all[full]=record
            val file=File(root,"payload/npm/$full");val measured=NimRuntimeClosure.measure(file,8_388_608,active)
            require(measured.second==record.jsonObject.getValue("sha256").jsonPrimitive.content&&measured.first==record.jsonObject.getValue("size").jsonPrimitive.long)
            val text=runCatching{LocalUbuntuGraphqlPolicy.read(file,8_388_608,active)}.getOrNull()
            // Every byte is preserved even when not identified as a notice. Text markers trigger conservative review.
            if(Regex("(?i)(license|notice|copying|copyright|readme)").containsMatchIn(path)||text?.contains("opyright")==true||text?.contains("SPDX-License-Identifier:")==true)witnesses[full]=record
            if(text!=null)Regex("SPDX-License-Identifier:[ \\t]*([^\\r\\n*]{1,200})").findAll(text).forEach {
                require(it.groupValues[1].trim()=="MIT") { "Unreviewed original GraphQL SPDX notice in $full" }
            }
        }
        for(item in source) {
            val s=item.jsonObject;val path=s.getValue("originalRelative").jsonPrimitive.content;val hash=s.getValue("sourceSha256").jsonPrimitive.content
            val original=all[path]?.jsonObject;val supplemental=s.getValue("supplementalText").jsonPrimitive.content
            require(LocalUbuntuGraphqlPolicy.sha256(supplemental.toByteArray())==hash)
            if(original!=null)require(original.getValue("sha256").jsonPrimitive.content==hash) { "Original GraphQL notice differs from source review: $path" }
            coverage+=buildJsonObject { put("package",s.getValue("name"));put("originalRelative",path);put("sourceSha256",hash);put("originalPresent",original!=null);put("supplementalAsset",PREFIX+"GraphQL-5.7.0-NOTICES.txt");put("provenance",if(original!=null)"original_distribution_and_reviewed_source"else"reviewed_source_supplement_retained") }
        }
        return buildJsonObject {
            put("schema",1);put("status","complete_originals_with_reviewed_source_supplements");put("originalInventorySha256",LocalUbuntuGraphqlPolicy.sha256(inventory.toString().toByteArray()))
            put("retainedOriginalFiles",JsonObject(all));put("retainedOriginalNoticeWitnesses",JsonObject(witnesses));put("coverage",JsonArray(coverage));put("assets",JsonObject(hashes.mapValues{JsonPrimitive(it.value)}))
            put("releaseReproducibilityAttested",false);put("limitation",LocalUbuntuGraphqlDataAdmission.LIMITATION)
        }
    }
}
