package com.baystudio.droide.core

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Complete-set synchronization. A receipt is evidence of the helper's real native barrier only. */
internal class LspVueCompanionAuthority {
    data class Document(val path: String, val uri: String, val languageId: String, val lifetime: Long, val revision: Long, val text: String)
    data class Capture(val era: Long, val mutation: Long, val documents: List<Document>, val digest: String)
    data class Receipt(val capture: Capture, val pairEpoch: Long, val serial: Long, val versions: Map<String, Int>)
    private var serial = 0L
    private var receipt: Receipt? = null
    fun reusable(capture: Capture, pairEpoch: Long): Receipt? = receipt?.takeIf { it.capture == capture && it.pairEpoch == pairEpoch }
    fun synchronize(capture: Capture, pairEpoch: Long): JsonObject = buildJsonObject {
        put("contractVersion", 1); put("pairEpoch", pairEpoch); put("syncSerial", ++serial); put("captureDigest", capture.digest)
        put("documents", buildJsonArray { capture.documents.forEach { d -> add(buildJsonObject {
            put("uri", d.uri); put("languageId", d.languageId); put("lifetime", d.lifetime); put("revision", d.revision); put("text", d.text)
        }) } })
    }.also { require(it.toString().toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "Vue synchronization exceeds 6 MiB" } }
    fun accept(capture: Capture, request: JsonObject, response: JsonElement): Receipt {
        val obj = response as? JsonObject ?: error("Vue synchronization receipt missing")
        require(listOf("pairEpoch", "syncSerial", "captureDigest").all { obj[it] == request[it] }) { "Vue synchronization receipt mismatched" }
        val raw = obj["wireVersions"] as? JsonObject ?: error("Vue wire versions missing")
        require(raw.keys == capture.documents.map { it.uri }.toSet()) { "Vue synchronization membership mismatched" }
        val versions = raw.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 }
            ?: error("Vue synchronization version invalid") }
        return Receipt(capture, requireNotNull((request["pairEpoch"] as? JsonPrimitive)?.longOrNull),
            requireNotNull((request["syncSerial"] as? JsonPrimitive)?.longOrNull), versions).also { receipt = it }
    }
    fun clear() { receipt = null }
    companion object {
        const val MAX_OPEN = 128
        const val MAX_DOCUMENT_BYTES = 1024 * 1024
        const val MAX_AGGREGATE_BYTES = 4 * 1024 * 1024
        const val MAX_REQUEST_BYTES = 6 * 1024 * 1024
        /** The monotonic mutation token includes closes; complete membership detects new companions. */
        fun capture(era: Long, mutation: Long, documents: List<Document>): Capture {
            require(documents.size <= MAX_OPEN) { "Vue supports at most 128 open relevant documents; no dirty buffer was evicted" }
            require(documents.map { it.uri }.distinct().size == documents.size)
            var bytes = 0L
            val sorted = documents.sortedBy { it.uri }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("$era:$mutation\n".toByteArray())
            for (d in sorted) {
                require(d.lifetime > 0 && d.revision > 0 && LspVueResultAuthority.validText(d.text)) { "Vue text or lifetime is invalid" }
                val encoded = d.text.toByteArray(Charsets.UTF_8)
                require(encoded.size <= MAX_DOCUMENT_BYTES) { "Vue document exceeds 1 MiB" }
                bytes += encoded.size
                require(bytes <= MAX_AGGREGATE_BYTES) { "Vue open documents exceed 4 MiB; no dirty companion was evicted" }
                digest.update("${d.uri.length}:${d.uri}:${d.languageId}:${d.lifetime}:${d.revision}:${encoded.size}:".toByteArray())
                digest.update(encoded)
            }
            return Capture(era, mutation, sorted, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }
}
