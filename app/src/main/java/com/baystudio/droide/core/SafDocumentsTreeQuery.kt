package com.baystudio.droide.core

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

internal data class SafDocumentNode(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
    val flags: Int? = null,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    val isFile: Boolean get() = !isDirectory
    val identity: SafProviderIdentity get() = SafProviderIdentity(documentId, uri.toString(), name, mimeType)
}

internal class SafDirectQueryException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A failed or partial cursor is never proof that a document is absent. */
internal class SafDocumentsTreeQuery(
    private val context: Context,
    private val treeUri: Uri,
) {
    fun rootDocumentId(): String = DocumentsContract.getTreeDocumentId(treeUri).also {
        require(it.isNotBlank()) { "SAF tree has no document identity" }
    }

    fun readDocument(documentId: String, checkCancelled: () -> Unit = {}): SafDocumentNode? {
        val rows = read(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId), 1, checkCancelled)
        return rows.singleOrNull()?.also {
            check(it.documentId == documentId) { "SAF document query returned a different identity" }
        }
    }

    fun listChildren(parentDocumentId: String, maxEntries: Int = MAX_QUERY_ENTRIES,
        checkCancelled: () -> Unit = {}): List<SafDocumentNode> = read(
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId), maxEntries, checkCancelled,
    )

    fun findChild(parentDocumentId: String, leaf: String, checkCancelled: () -> Unit = {}): SafDocumentNode? {
        PathSecurity.safeLeafName(leaf)
        return listChildren(parentDocumentId, MAX_QUERY_ENTRIES, checkCancelled).singleOrNull { it.name == leaf }
    }

    private fun read(queryUri: Uri, maxEntries: Int, checkCancelled: () -> Unit): List<SafDocumentNode> {
        require(maxEntries >= 0) { "Invalid SAF query limit" }
        checkCancelled()
        // minSdk 29 supports the Bundle overload. Never fall back from errors to absence.
        val cursor = context.contentResolver.query(queryUri, PROJECTION, null as Bundle?, null)
            ?: throw SafDirectQueryException("SAF provider returned no cursor")
        return cursor.use { rows ->
            checkCancelled()
            requireComplete(rows)
            val idIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
            val out = ArrayList<SafDocumentNode>(minOf(maxEntries, 256))
            val names = hashSetOf<String>()
            val ids = hashSetOf<String>()
            var metadataChars = 0L
            while (true) {
                checkCancelled()
                val hasRow = rows.moveToNext()
                checkCancelled()
                if (!hasRow) break
                check(out.size < minOf(maxEntries, MAX_QUERY_ENTRIES)) { "SAF directory exceeds its bounded entry limit" }
                val id = rows.getString(idIndex)?.takeIf { it.isNotBlank() }
                    ?: throw SafDirectQueryException("SAF row has no document identity")
                val name = rows.getString(nameIndex)?.let(PathSecurity::safeLeafName)
                    ?: throw SafDirectQueryException("SAF row has no name")
                val mime = rows.getString(mimeIndex)?.takeIf { it.isNotBlank() }
                    ?: throw SafDirectQueryException("SAF row has no type")
                check(id.length <= MAX_ID_CHARS && mime.length <= MAX_MIME_CHARS) { "SAF row metadata exceeds its limit" }
                metadataChars += id.length.toLong() + name.length + mime.length
                check(metadataChars <= MAX_METADATA_CHARS) { "SAF directory metadata exceeds its limit" }
                check(names.add(name) && ids.add(id)) { "SAF directory contains duplicate names or identities" }
                out += SafDocumentNode(id, DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, mime,
                    if (sizeIndex >= 0 && !rows.isNull(sizeIndex)) rows.getLong(sizeIndex).also {
                        check(it >= -1L) { "SAF row has an invalid size" }
                    } else -1L,
                    if (modifiedIndex >= 0 && !rows.isNull(modifiedIndex)) rows.getLong(modifiedIndex).coerceAtLeast(0L) else 0L,
                    if (flagsIndex >= 0 && rows.getType(flagsIndex) == Cursor.FIELD_TYPE_INTEGER)
                        rows.getLong(flagsIndex).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() else null)
            }
            checkCancelled()
            requireComplete(rows)
            out
        }
    }

    private fun requireComplete(cursor: Cursor) {
        val extras = cursor.extras
        check(!extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) { "SAF provider is still loading; retry after it completes" }
        check(!extras.containsKey(DocumentsContract.EXTRA_ERROR)) { "SAF provider returned an incomplete query with an error" }
    }

    companion object {
        internal const val MAX_QUERY_ENTRIES = 100_000
        private const val MAX_ID_CHARS = 16_384
        private const val MAX_MIME_CHARS = 1_024
        private const val MAX_METADATA_CHARS = 4L * 1024 * 1024
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
