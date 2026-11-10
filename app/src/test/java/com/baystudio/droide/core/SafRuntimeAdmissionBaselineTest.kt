package com.baystudio.droide.core

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Current runtime APIs only. No new protocol helpers, native calls, process, network, or background work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafRuntimeAdmissionBaselineTest {
    private val ownedRoots = mutableListOf<File>()
    private val original = byteArrayOf(0, 1, -1, 127, 10, 42)
    private val intended = byteArrayOf(90, 0, -9, 11)

    @After fun cleanup() {
        // Only bounded fixture-owned directories, including the unique legacy hash directory. No pending
        // coroutine, provider writer, sleep, or asynchronous cleanup can outlive a test.
        try {
            assertEquals("Directory durability descriptors must close", 0, SafPreservationDirectoryOs.openCount())
        } finally {
            SafPreservationDirectoryOs.reset()
            ownedRoots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) }
        }
    }

    @Test(timeout = 20_000) fun legacyMatchingPendingEvidenceIsNotClearedByDisjointApply(): Unit = runBlocking {
        for (alreadyInManifest in listOf(false, true)) {
            val f = fixture()
            val visible = if (alreadyInManifest) intended else original
            f.local.resolve("pending.bin").writeBytes(visible)
            f.external.resolve("pending.bin").writeBytes(visible)
            f.seedManifest(mapOf("sentinel.bin" to fingerprint(original), "kept" to "d", "pending.bin" to fingerprint(visible)))
            val entry = f.legacy.resolve(UUID.randomUUID().toString()).apply { check(mkdirs()) }
            entry.resolve("previous").writeBytes(original)
            entry.resolve("intended").writeBytes(intended)
            entry.resolve("pending.json").writeText(
                """{"path":"pending.bin","previous":"${fingerprint(original)}","intended":"${fingerprint(intended)}"}""")
            val evidence = entry.listFiles()!!.associate { it.name to it.readBytes() }

            val failure = runCatching { f.mirror().apply(FileRepository.Mutation.CreateDirectory("kept")) }.exceptionOrNull()

            f.assertNoUnexpectedCalls()
            assertNull("A valid disjoint existing-directory operation should remain available", failure)
            assertEquals(0, f.provider.creates)
            assertArrayEquals(visible, f.external.resolve("pending.bin").readBytes())
            f.assertSentinels()
            assertTrue("Matching bytes/old manifest must not authorize deleting legacy evidence", entry.isDirectory)
            assertEquals(evidence.keys, entry.list()!!.toSet())
            evidence.forEach { (name, bytes) -> assertArrayEquals(bytes, entry.resolve(name).readBytes()) }
        }
    }

    @Test(timeout = 20_000) fun orphanLegacyDirectoryBlocksBeforeAnyNewProviderMutation(): Unit = runBlocking {
        val f = fixture()
        f.seedManifest(mapOf("sentinel.bin" to fingerprint(original), "kept" to "d"))
        val manifestBefore = f.manifest.readBytes()
        val orphan = f.legacy.resolve(UUID.randomUUID().toString()).apply { check(mkdirs()) }
        orphan.resolve("retained-original.bin").writeBytes(original)

        val failure = runCatching { f.mirror().apply(FileRepository.Mutation.CreateDirectory("new-dir")) }.exceptionOrNull()

        f.assertNoUnexpectedCalls()
        f.assertSentinels()
        assertNotNull("Unscoped orphan evidence must block before a new provider mutation", failure)
        assertEquals(0, f.provider.creates)
        assertFalse(f.external.resolve("new-dir").exists())
        assertArrayEquals(manifestBefore, f.manifest.readBytes())
        assertTrue("Orphan evidence must not be automatically deleted", orphan.isDirectory)
        assertArrayEquals(original, orphan.resolve("retained-original.bin").readBytes())
    }

    @Test(timeout = 20_000) fun corruptJsonManifestCannotBootstrapAWrite(): Unit = runBlocking {
        val f = fixture()
        val corrupt = "{ invalid retained manifest".toByteArray(Charsets.UTF_8)
        f.manifest.parentFile!!.mkdirs()
        f.manifest.writeBytes(corrupt)

        val failure = runCatching { f.mirror().apply(FileRepository.Mutation.CreateDirectory("new-dir")) }.exceptionOrNull()

        f.assertNoUnexpectedCalls()
        f.assertSentinels()
        assertNotNull("Existing corrupt metadata must not become an empty trusted baseline", failure)
        assertEquals(0, f.provider.creates)
        assertFalse(f.external.resolve("new-dir").exists())
        assertArrayEquals(corrupt, f.manifest.readBytes())
    }

    private fun fixture(): Fixture {
        val parent = Files.createTempDirectory("droide-saf-runtime-baseline-").toFile().also { ownedRoots += it }
        SafPreservationDirectoryOs.allow(parent)
        val ownedJournal = RuntimeEnvironment.getApplication().filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(ownedJournal)
        val local = parent.resolve("local").apply { check(mkdir()) }
        val external = parent.resolve("external").apply { check(mkdir()) }
        for (root in listOf(local, external)) {
            root.resolve("sentinel.bin").writeBytes(original)
            check(root.resolve("kept").mkdir())
        }
        val application = RuntimeEnvironment.getApplication()
        val authority = "droide.saf.runtime.baseline.${UUID.randomUUID()}"
        val provider = BoundedProvider(external)
        val info = ProviderInfo().apply {
            this.authority = authority
            name = "$authority.FixtureProvider"
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            enabled = true
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider.attachInfo(application, info)
        Shadows.shadowOf(application.packageManager).apply {
            addOrUpdateProvider(info)
            addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        val bridge = ResolverBridge(provider).apply {
            attachInfo(application, ProviderInfo().apply { this.authority = authority })
        }
        ShadowContentResolver.registerProviderInternal(authority, bridge)
        val uri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val child = DocumentsContract.buildDocumentUriUsingTree(uri, "root/sentinel.bin")
        check(DocumentsContract.isDocumentUri(application, child))
        check(androidx.documentfile.provider.DocumentFile.fromTreeUri(application, child)?.uri == child)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, "root")
        requireNotNull(application.contentResolver.query(rootUri, null, null, null, null)).use {
            check(it.moveToFirst())
            check(it.getString(it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) == DocumentsContract.Document.MIME_TYPE_DIR)
        }
        val binding = digest((uri.toString() + "\u0000" + local.absolutePath).toByteArray(Charsets.UTF_8))
        val legacy = application.filesDir.resolve(".droide/saf-write-recovery/$binding").also { ownedRoots += it }
        return Fixture(local, external, provider, uri, legacy)
    }

    private inner class Fixture(val local: File, val external: File, val provider: BoundedProvider, val uri: Uri, val legacy: File) {
        val manifest get() = local.resolve(".droide/saf_manifest.json")
        fun mirror() = SafMirror(RuntimeEnvironment.getApplication(), uri.toString(), local)
        fun seedManifest(entries: Map<String, String>) {
            manifest.parentFile!!.mkdirs()
            manifest.writeText("""{"version":2,"entries":${Json.encodeToString(entries)}}""")
        }
        fun assertSentinels() {
            assertArrayEquals(original, local.resolve("sentinel.bin").readBytes())
            assertArrayEquals(original, external.resolve("sentinel.bin").readBytes())
            assertTrue(local.resolve("kept").isDirectory)
            assertTrue(external.resolve("kept").isDirectory)
            assertFalse(local.resolve(".droide/saf_activation_pending.json").exists())
        }
        fun assertNoUnexpectedCalls() {
            assertEquals("No provider write/delete should be needed by these baseline calls", 0, provider.unexpectedCalls)
            assertTrue("Queries are finite and fixture-local", provider.queries in 1..128)
        }
    }

    private class ResolverBridge(private val provider: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
            query(uri, projection, selection, selectionArgs, sortOrder, null)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor? =
            provider.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                if (sortOrder != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }, cancellationSignal)
        override fun query(uri: Uri, projection: Array<out String>?, queryArgs: Bundle?, cancellationSignal: CancellationSignal?): Cursor? = provider.query(uri, projection, queryArgs, cancellationSignal)
        override fun getType(uri: Uri): String? = provider.getType(uri)
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = provider.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? = provider.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = provider.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected fixture insert")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected fixture delete")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Unexpected fixture update")
    }

    private class BoundedProvider(private val root: File) : DocumentsProvider() {
        var queries = 0
        var creates = 0
        var unexpectedCalls = 0
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray<String>())
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            check(++queries <= 128) { "Fixture query budget exceeded" }
            return cursor(projection).apply { if (file(documentId).exists()) row(this, documentId, file(documentId)) }
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
            check(++queries <= 128) { "Fixture query budget exceeded" }
            return cursor(projection).apply {
                file(parentDocumentId).listFiles()!!.sortedBy { it.name }.forEach { row(this, "$parentDocumentId/${it.name}", it) }
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if (mode != "r") { unexpectedCalls++; error("Unexpected fixture write") }
            return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            check(++creates <= 1) { "Fixture create budget exceeded" }
            check(mimeType == DocumentsContract.Document.MIME_TYPE_DIR)
            check(file(parentDocumentId).resolve(PathSecurity.safeLeafName(displayName)).mkdir())
            return "$parentDocumentId/$displayName"
        }
        override fun deleteDocument(documentId: String) { unexpectedCalls++; error("Unexpected fixture delete") }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File {
            require(id == "root" || id.startsWith("root/"))
            return if (id == "root") root else PathSecurity.resolveWithin(root, id.removePrefix("root/"))
        }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE))
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any>(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to file.length(),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified())
            cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
        }
    }

    private fun fingerprint(bytes: ByteArray) = "f:" + digest(bytes)
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
