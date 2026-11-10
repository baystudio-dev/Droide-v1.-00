package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/** Structurally admitted private beforeimage; the later capture helper must prove provider-query completeness. */
@Serializable internal data class SafTreeEvidence(
    val version: Int = 1, val operationId: String, val treeUri: String, val rootPath: String,
    val rootDocument: SafOwnedDocument, val entries: List<SafTreeEvidenceEntry>,
)
@Serializable internal data class SafTreeEvidenceEntry(
    val relativePath: String, val document: SafOwnedDocument, val payload: SafEvidenceFile?,
)

/** Encodes one bounded entry at a time; a whole unbounded image is never serialized first. */
internal object SafTreeEvidenceFormat {
    fun encode(image: SafTreeEvidence, maximum: Long, limits: SafJournalLimits,
        validateDocument: (SafOwnedDocument) -> Unit, checkActive: () -> Unit): ByteArray {
        require(maximum in 1..limits.treeIndexBytes)
        require(image.entries.size in 1..limits.treeEntries)
        SafOperationJournal.requireUuid(image.operationId)
        SafOperationJournal.validPath(image.rootPath, false)
        require(image.treeUri.length <= 256 * 1024)
        validateDocument(image.rootDocument)
        val out = ByteArrayOutputStream()
        fun append(bytes: ByteArray) {
            checkActive()
            check(bytes.size.toLong() <= maximum - out.size()) { "SAF tree index exceeds its encoded capacity" }
            out.write(bytes)
        }
        fun literal(value: String) = append(value.toByteArray(Charsets.UTF_8))
        fun string(value: String) = append(SafJournalCodec.encode(String.serializer(), value))
        literal("{\"version\":${image.version},\"operationId\":")
        string(image.operationId)
        literal(",\"treeUri\":"); string(image.treeUri)
        literal(",\"rootPath\":"); string(image.rootPath)
        literal(",\"rootDocument\":")
        append(SafJournalCodec.encode(SafOwnedDocument.serializer(), image.rootDocument))
        literal(",\"entries\":[")
        image.entries.forEachIndexed { index, entry ->
            checkActive()
            require(entry.relativePath.length <= 32 * 1024)
            SafOperationJournal.validPath(image.rootPath + if (entry.relativePath.isEmpty()) "" else "/${entry.relativePath}", false)
            validateDocument(entry.document)
            entry.payload?.let { SafOperationJournal.validatePayloadReference(it) }
            if (index != 0) literal(",")
            append(SafJournalCodec.encode(SafTreeEvidenceEntry.serializer(), entry))
        }
        literal("]}")
        return out.toByteArray()
    }

    fun validate(image: SafTreeEvidence, operationId: String, treeUri: String, rootPath: String,
        rootDocument: SafOwnedDocument, limits: SafJournalLimits, validateDocument: (SafOwnedDocument) -> Unit,
        validatePayload: (SafEvidenceFile) -> Unit, checkActive: () -> Unit) {
        require(image.version == 1 && image.operationId == operationId && image.treeUri == treeUri &&
            image.rootPath == rootPath && image.rootDocument == rootDocument) { "SAF beforeimage binding mismatch" }
        require(image.entries.size in 1..limits.treeEntries)
        SafOperationJournal.validPath(rootPath, false)
        require(rootDocument.name == rootPath.substringAfterLast('/'))
        val paths = hashMapOf<String, SafOwnedDocument>()
        val ids = hashSetOf<String>()
        val uris = hashSetOf<String>()
        val payloads = hashSetOf<String>()
        var previous: String? = null
        var files = 0
        var bytes = 0L
        image.entries.forEachIndexed { index, entry ->
            checkActive()
            val path = entry.relativePath
            require(path.length <= 32 * 1024)
            require(previous == null || requireNotNull(previous) < path) { "Unsorted or duplicate SAF beforeimage path" }
            previous = path
            val fullPath = rootPath + if (path.isEmpty()) "" else "/$path"
            SafOperationJournal.validPath(fullPath, false)
            require(fullPath.count { it == '/' } + 1 <= limits.treeDepth)
            validateDocument(entry.document)
            require(entry.document.uri.startsWith("$treeUri/document/")) { "Foreign SAF beforeimage tree" }
            require(ids.add(entry.document.documentId) && uris.add(entry.document.uri)) { "Aliased or cyclic SAF beforeimage" }
            if (index == 0) require(path.isEmpty() && entry.document == rootDocument)
            else {
                require(path.isNotEmpty() && entry.document.name == path.substringAfterLast('/'))
                val parent = requireNotNull(paths[path.substringBeforeLast('/', "")]) { "Missing SAF beforeimage ancestor" }
                require(parent.mimeType == SafOperationJournal.DIRECTORY_MIME && entry.document.parentDocumentId == parent.documentId)
            }
            paths[path] = entry.document
            if (entry.document.mimeType == SafOperationJournal.DIRECTORY_MIME) require(entry.payload == null)
            else {
                check(++files <= limits.treeFiles) { "SAF beforeimage file limit exceeded" }
                val payload = requireNotNull(entry.payload) { "Missing SAF beforeimage bytes" }
                SafOperationJournal.validatePayloadReference(payload)
                require(payloads.add(payload.name)) { "SAF beforeimage payload is not exclusive to its file" }
                bytes = SafOperationJournal.boundedAdd(bytes, payload.identity.bytes, limits.treeBytes)
                validatePayload(payload)
            }
        }
    }
}
