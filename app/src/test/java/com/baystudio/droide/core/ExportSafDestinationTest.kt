package com.baystudio.droide.core

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ExportSafDestinationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private lateinit var provider: Provider
    private val grants = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val tree get() = DocumentsContract.buildTreeDocumentUri("export.test", "parent")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        directory = Files.createTempDirectory("export-provider").toFile()
        provider = Provider(File(directory, "provider").apply { mkdir() })
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "export.test"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })

        val bridge = LegacyResolverBridge(provider).apply {
            attachInfo(context, ProviderInfo().apply { authority = "export.test" })
        }
        ShadowContentResolver.registerProviderInternal("export.test", bridge)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun destination(owned: MutableList<String> = mutableListOf()) = ExportSafDestination(context.contentResolver, tree, grants,
        ExportFormat.TREE, File(directory, "job").apply { mkdir() }, "fresh-export", owned::add, {})
    @Test fun freshTreePreservesEmptyDirectoriesAndReopensExactlyOnce() {
        val owned = mutableListOf<String>()
        val destination = destination(owned)
        destination.createDirectory("")
        destination.createDirectory("empty")
        val output = destination.createFile("binary.dat")
        val bytes = byteArrayOf(0, 1, -1, 10, 13)
        output.openWrite().use { it.write(bytes) }
        assertArrayEquals(bytes, output.openRead().use { it.readBytes() })
        assertEquals(3, owned.size)
        assertFalse(owned.any { DocumentsContract.getDocumentId(android.net.Uri.parse(it)) == "parent" })
        try { output.openRead(); fail("Second readback was permitted") } catch (_: IllegalStateException) { }
    }
    @Test fun providerRenamingSourceEntryFailsRatherThanSilentlySucceeding() {
        val destination = destination()
        destination.createDirectory("")
        provider.renameChildren = true
        try { destination.createFile("A"); fail("Renaming accepted") } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("NameNotRepresentable"))
        }
    }
    @Test fun providerIdentityCollisionNeverOpensExistingOutput() {
        val owned = mutableListOf<String>()
        val destination = destination(owned)
        destination.createDirectory("")
        provider.reuseParent = true
        try { destination.createFile("A"); fail("Collision accepted") } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("DestinationCollision"))
        }
        assertEquals(1, owned.size)
        assertEquals(0, provider.opens)
    }
    @Test fun actualReadGrantIsMandatory() {
        try {
            ExportSafDestination(context.contentResolver, tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION, ExportFormat.TREE,
                directory, "export", {}, {})
            fail("Write-only grant accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun revokedReadbackIsNotTreatedAsVerified() {
        val destination = destination()
        destination.createDirectory("")
        val output = destination.createFile("A")
        output.openWrite().use { it.write(1) }
        provider.failRead = true
        try { output.openRead(); fail("Readback succeeded unexpectedly") } catch (_: java.io.FileNotFoundException) { }
    }
    private class LegacyResolverBridge(private val provider: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            query(uri, projection, selection, selectionArgs, sortOrder, null)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? =
            provider.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }, cancellationSignal)
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? =
            provider.query(uri, projection, queryArgs, cancellationSignal)
        override fun getType(uri: Uri): String? = provider.getType(uri)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = provider.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = provider.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = provider.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Fixture uses DocumentsContract creation")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture uses DocumentsContract deletion")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Fixture uses DocumentsContract writes")
    }

    class Provider(private val base: File) : DocumentsProvider() {
        data class Entry(val name: String, val parent: String?, val mime: String)
        private val entries = linkedMapOf("parent" to Entry("parent", null, DocumentsContract.Document.MIME_TYPE_DIR))
        var renameChildren = false
        var reuseParent = false
        var failRead = false
        var opens = 0
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))
        private fun cursor(ids: List<String>, projection: Array<out String>?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
            return MatrixCursor(columns).also { cursor -> ids.forEach { id ->
                val entry = entries.getValue(id)
                cursor.addRow(columns.map<String, Any?> { column -> when(column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> entry.mime
                    DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    else -> null
                } }.toTypedArray())
            } }
        }
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(listOf(documentId), projection)
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) =
            cursor(entries.filterValues { it.parent == parentDocumentId }.keys.toList(), projection)
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId == parentDocumentId || entries[documentId]?.parent == parentDocumentId || parentDocumentId == "parent"
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            if (reuseParent) return parentDocumentId
            val id = "doc-${entries.size}"
            entries[id] = Entry(if(renameChildren) "$displayName-changed" else displayName, parentDocumentId, mimeType)
            return id
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            opens++
            if (mode == "r" && failRead) throw java.io.FileNotFoundException("Readback revoked")
            return ParcelFileDescriptor.open(File(base, documentId), ParcelFileDescriptor.parseMode(mode))
        }
    }
}
