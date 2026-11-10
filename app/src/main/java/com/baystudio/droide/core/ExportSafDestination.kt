package com.baystudio.droide.core

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Android-only destination boundary. Never translates content URIs into filesystem paths. */
internal class ExportSafDestination(
    private val resolver: ContentResolver,
    private val destination: Uri,
    actualGrantFlags: Int,
    private val format: ExportFormat,
    private val jobDirectory: File,
    private val outerName: String,
    private val recordCreated: (String) -> Unit,
    private val checkActive: () -> Unit,
) : ExportDestination {
    private val index = File(jobDirectory, "destination-index").apply { mkdirs() }
    private var rawUsed = false
    private var initialized = false
    init { requireAccess(destination, actualGrantFlags) }
    companion object {
        fun requireAccess(destination: Uri, actualGrantFlags: Int) {
            val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(actualGrantFlags and required == required) { "Read and write access are required for verified export" }
            require(destination.scheme == "content") { "A document provider destination is required" }
        }
    }
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun pathFile(path: String) = File(index, "path-${key(path)}")
    private fun identity(uri: Uri): String = "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
    private fun remember(uri: Uri, path: String, onOwned: () -> Unit = {}) {
        val identityFile = File(index, "uri-${key(identity(uri))}")
        check(identityFile.createNewFile()) { "DestinationCollision: provider reused a document identity" }
        onOwned()
        pathFile(path).writeText(uri.toString())
    }
    private fun query(uri: Uri): Pair<String, Int> {
        checkActive()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_FLAGS)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            check(cursor.moveToFirst()) { "ProviderUnavailable: document metadata is unavailable" }
            cursor.getString(0) to cursor.getInt(1)
        } ?: error("ProviderUnavailable: document metadata is unavailable")
    }
    private fun initialize() {
        if (initialized) return
        checkActive()
        if (format == ExportFormat.TREE) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(destination, DocumentsContract.getTreeDocumentId(destination))
            check(query(parent).second and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "DestinationReadOnly" }
            // Record all pre-existing identities once; never overwrite or claim ownership of them.
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(destination, DocumentsContract.getDocumentId(parent))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    checkActive()
                    File(index, "uri-${key("${parent.authority}:${cursor.getString(0)}")}").createNewFile()
                }
            } ?: error("ProviderUnavailable: cannot establish a fresh destination")
            File(index, "uri-${key(identity(parent))}").createNewFile()
            val root = create(parent, DocumentsContract.Document.MIME_TYPE_DIR, outerName, "", exactName = false)
            check(query(root).second and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) { "DestinationReadOnly" }
        } else {
            check(query(destination).second and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) { "DestinationReadOnly" }
            // Only ACTION_CREATE_DOCUMENT results may be passed for raw/archive output. Controller journals
            // this picker-created URI before capture starts.
            remember(destination, "")
        }
        initialized = true
    }
    private fun create(parent: Uri, mime: String, name: String, path: String, exactName: Boolean): Uri {
        checkActive()
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "NameNotRepresentable" }
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: error("ProviderUnavailable: create outcome is uncertain; inspect the selected destination")
        check(uri.scheme == "content" && uri.authority == parent.authority) { "DestinationCollision: provider returned an unrelated document" }
        // Check identity before recording ownership: a provider may return an existing object.
        remember(uri, path) { recordCreated(uri.toString()) }
        checkActive()
        val metadata = query(uri)
        check(!exactName || metadata.first == name) { "NameNotRepresentable: provider changed a source entry name; use TAR" }
        return uri
    }
    private fun parent(path: String): Uri {
        require(path.isNotEmpty() && !path.startsWith('/') && path.split('/').none { it == "." || it == ".." || it.isEmpty() }) { "Invalid export path" }
        val parentPath = path.substringBeforeLast('/', "")
        val file = pathFile(parentPath)
        check(file.isFile) { "Missing destination directory" }
        return Uri.parse(file.readText())
    }
    override fun createDirectory(relativePath: String) {
        initialize()
        if (relativePath.isEmpty()) return
        check(format == ExportFormat.TREE)
        check(!pathFile(relativePath).exists()) { "DestinationCollision" }
        create(parent(relativePath), DocumentsContract.Document.MIME_TYPE_DIR, relativePath.substringAfterLast('/'), relativePath, true)
    }
    override fun createFile(relativePath: String): ExportOutput {
        initialize()
        val uri = if (format == ExportFormat.TREE) {
            check(!pathFile(relativePath).exists()) { "DestinationCollision" }
            create(parent(relativePath), ExportFileNames.mimeType(relativePath), relativePath.substringAfterLast('/'), relativePath, true)
        } else {
            check(relativePath.isEmpty() && !rawUsed) { "DestinationCollision" }
            rawUsed = true
            destination
        }
        check(query(uri).second and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0) { "DestinationReadOnly" }
        return object : ExportOutput {
            private var written = false
            private var read = false
            override fun openWrite(): OutputStream {
                checkActive(); check(!written) { "Output may only be written once" }; written = true
                // SAF's "w" mode need not truncate. Even a newly created document may contain provider-generated
                // bytes; stale tails must not survive.
                return resolver.openOutputStream(uri, "wt") ?: error("WriteFailed: provider did not open the output")
            }
            override fun openRead(): InputStream {
                checkActive(); check(written && !read) { "Readback may only run once after writing" }; read = true
                return resolver.openInputStream(uri) ?: throw ExportFailure("ReadbackUnavailable")
            }
        }
    }
}
