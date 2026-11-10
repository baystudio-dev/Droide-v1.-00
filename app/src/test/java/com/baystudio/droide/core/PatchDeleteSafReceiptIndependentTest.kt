package com.baystudio.droide.core

import android.app.Application
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.ComponentName
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
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class PatchDeleteSafReceiptIndependentTest {
    private val roots = arrayListOf<File>()
    private val original = "original\n".toByteArray()

    @After fun cleanup() {
        try { assertEquals(0, SafPreservationDirectoryOs.openCount()) }
        finally { SafPreservationDirectoryOs.reset(); roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }
    }
    private fun operations(move: Boolean): List<PatchTool.Op> = if (move)
        listOf(PatchTool.Op.Update("sentinel.bin", "original", "updated", "destination.txt"))
        else listOf(PatchTool.Op.Delete("sentinel.bin"))
    private fun files(f: Fixture, mirror: SafMirror = f.mirror()) = FileRepository(f.local,
        newFilePublisher = NewProjectFilePublisher.platform, participant = mirror.repositoryParticipant())
    private fun journalImage(f: Fixture): Map<String, String> = f.journal.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(f.journal).invariantSeparatorsPath to it.readBytes().safDigest() }

    private suspend fun retainedProviderFailure(move: Boolean) {
        val f = fixture(); val mirror = f.mirror(); val files = files(f, mirror); val history = EditHistory(files)
        f.provider.afterDelete = { throw IOException("provider deleted but failed to return") }
        val result = PatchTool.apply(operations(move), files, history)
        assertTrue(result, result.startsWith("OK:")); assertTrue(result, result.contains("synchronization pending: sentinel.bin"))
        assertFalse(f.local.resolve("sentinel.bin").exists()); assertFalse(f.external.resolve("sentinel.bin").exists())
        if (move) {
            assertEquals("updated\n", f.local.resolve("destination.txt").readText())
            assertEquals("updated\n", f.external.resolve("destination.txt").readText())
        }
        assertEquals(1, history.depth())
        val localDelete = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
        val stage = f.local.resolve(localDelete.plan.local!!.destination.path)
        val key = SafOperationStorage().key(stage, false)
        assertArrayEquals(original, stage.readBytes())
        assertTrue(localDelete.plan.local!!.destination.path.startsWith(".droide/saf-delete-custody/"))
        val providerDelete = f.records().single { it.plan.kind == SafEffectKind.DELETE && it.phase == SafEffectPhase.RETURNED }

        assertEquals(SafEffectReturn.REJECTED, providerDelete.result)
        assertEquals(SafProviderReturnShape.BOOLEAN, providerDelete.providerReturn!!.shape)
        assertEquals(false, providerDelete.providerReturn!!.boolean)
        assertFalse(f.journal.resolve(localDelete.operationId).resolve("abandoned.json").exists())
        val mutations = f.provider.mutations; val retainedJournal = journalImage(f)
        val manifest = f.manifest.readBytes()
        // The provider becoming reachable is not a receipt for its previous uncertain effect.
        f.provider.afterDelete = {}
        val undo = history.undo()
        println("ACTUAL_SAF move=$move patch=$result undo=$undo mutations=$mutations->${f.provider.mutations} history=${history.depth()}")
        assertTrue(undo, undo.startsWith("Undo failed:")); assertEquals(1, history.depth())
        assertEquals(mutations, f.provider.mutations)
        assertFalse(f.local.resolve("sentinel.bin").exists())
        if (move) assertEquals("updated\n", f.local.resolve("destination.txt").readText())
        assertTrue(runCatching { mirror.refreshFromTree() }.isFailure)
        assertTrue(runCatching { f.mirror().refreshFromTree() }.isFailure)
        assertTrue(runCatching { files(f).writeText("sentinel.bin", "original\n") }.isFailure)
        assertTrue(runCatching { PatchTool.apply(operations(move), files(f), EditHistory(files(f))) }.isFailure)
        assertEquals(mutations, f.provider.mutations); assertEquals(retainedJournal, journalImage(f))
        assertArrayEquals(manifest, f.manifest.readBytes()); assertEquals(key, SafOperationStorage().key(stage, false))
        assertArrayEquals(original, stage.readBytes()); assertFalse(f.external.resolve(".droide").exists())
    }

    @Test(timeout = 30_000) fun actualSafDeleteFailureSealsLocalUndoButBlocksUncertainProviderReplay() = runBlocking { retainedProviderFailure(false) }
    @Test(timeout = 30_000) fun actualSafMoveSourceFailureRetainsDestinationAndBlocksUncertainProviderUndo() = runBlocking { retainedProviderFailure(true) }

    @Test(timeout = 30_000) fun successfulSafDeleteAndMoveAllowNormalUndoWithoutErasingCustody() = runBlocking {
        for (move in listOf(false, true)) {
            val f = fixture(); val files = files(f); val history = EditHistory(files)
            val result = PatchTool.apply(operations(move), files, history)
            assertTrue(result, result.startsWith("OK:")); assertFalse(result, result.contains("synchronization pending"))
            val delete = f.records().single { it.phase == SafEffectPhase.VERIFIED && it.plan.local?.effect == SafLocalEffect.DELETE_STAGE }
            val stage = f.local.resolve(delete.plan.local!!.destination.path)
            val key = SafOperationStorage().key(stage, false)
            assertEquals(1, history.depth()); assertArrayEquals(original, stage.readBytes())
            val undone = history.undo()
            assertTrue(undone, undone.startsWith("Undone:")); assertEquals(0, history.depth())
            assertArrayEquals(original, f.local.resolve("sentinel.bin").readBytes())
            assertArrayEquals(original, f.external.resolve("sentinel.bin").readBytes())
            assertFalse(f.local.resolve("destination.txt").exists()); assertFalse(f.external.resolve("destination.txt").exists())
            assertEquals(key, SafOperationStorage().key(stage, false)); assertArrayEquals(original, stage.readBytes())
        }
    }

    private fun fingerprint(bytes: ByteArray) = "f:${bytes.safDigest()}"
    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val parent = Files.createTempDirectory("saf-owned-runtime-").toFile().also { roots += it }
        SafPreservationDirectoryOs.allow(parent)
        val journalParent = app.filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(journalParent)
        val local = parent.resolve("local").apply { mkdir() }
        val external = parent.resolve("external").apply { mkdir() }
        local.resolve("sentinel.bin").writeBytes(original); external.resolve("sentinel.bin").writeBytes(original)
        val authority = "droide.owned.${UUID.randomUUID()}"
        val provider = Provider(external)
        val info = ProviderInfo().apply {
            this.authority = authority; name = "$authority.Provider"; packageName = app.packageName; applicationInfo = app.applicationInfo
            enabled = true; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }
        provider.attachInfo(app, info)
        Shadows.shadowOf(app.packageManager).apply {
            addOrUpdateProvider(info)
            addIntentFilterForProvider(ComponentName(info.packageName, info.name), IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        val bridge = Bridge(provider).apply { attachInfo(app, ProviderInfo().apply { this.authority = authority }) }
        ShadowContentResolver.registerProviderInternal(authority, bridge)
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val journal = app.filesDir.resolve("saf-owned-operations").resolve((tree.toString() + "\u0000" + local.absolutePath).toByteArray().safDigest())
        roots += journal
        return Fixture(parent, local, external, provider, tree, journal).also { it.seed(emptyMap()) }
    }
    private inner class Fixture(val parent: File, val local: File, val external: File, val provider: Provider, val tree: Uri, val journal: File) {
        val manifest get() = local.resolve(".droide/saf_manifest.json")
        fun mirror() = SafMirror(RuntimeEnvironment.getApplication(), tree.toString(), local)
        fun core() = SafOperationJournal.create(local, tree.toString(), journal, SafOperationStorage())
        fun parentDocument() = SafOwnedDocument(DocumentsContract.buildDocumentUriUsingTree(tree, "root").toString(),
            "root", external.name, DocumentsContract.Document.MIME_TYPE_DIR, "root")
        fun seed(extra: Map<String, String>) {
            manifest.parentFile!!.mkdirs()
            manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
                SafLegacyTrustedManifest(entries = (extra + ("sentinel.bin" to fingerprint(original))).toSortedMap())))
        }
        fun image() = SafJournalCodec.decode(SafManifestImage.serializer(), manifest.readBytes())
        fun records(): List<SafEffectRecord> = journal.listFiles().orEmpty().filter { it.isDirectory }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.name.startsWith("action-") }.map { SafJournalCodec.decode(SafEffectRecord.serializer(), it.readBytes()) }
        }
        fun sentinels() { assertArrayEquals(original, local.resolve("sentinel.bin").readBytes()); assertArrayEquals(original, external.resolve("sentinel.bin").readBytes()) }
    }
    private class Provider(private val root: File) : DocumentsProvider() {
        var mutations = 0
        var beforeRead: (String) -> Unit = {}
        var afterWriteOpen: () -> Unit = {}
        var afterCreate: () -> Unit = {}
        var afterDelete: () -> Unit = {}
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply { row(this, documentId, file(documentId)) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, "$parentDocumentId/${it.name}", it) }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if ('w' !in mode) beforeRead(documentId)
            val fd = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
            if ('w' in mode) {
                mutations++
                try { afterWriteOpen() } catch (failure: Throwable) { fd.close(); throw failure }
            }
            return fd
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            val file = file(parentDocumentId).resolve(PathSecurity.safeLeafName(displayName))
            check(!file.exists())
            check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
            mutations++; afterCreate()
            return "$parentDocumentId/$displayName"
        }
        override fun deleteDocument(documentId: String) { check(PathSecurity.deleteTreeNoFollow(file(documentId))); mutations++; afterDelete() }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File { require(id == "root" || id.startsWith("root/")); return if (id == "root") root else root.resolve(id.removePrefix("root/")) }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED))
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any>(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to if (file.isFile) file.length() else 0L, DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified())
            cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
        }
    }
    private class Bridge(private val delegate: DocumentsProvider) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
            delegate.query(uri, projection, Bundle().apply {
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (args != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            }, null)
        override fun query(uri: Uri, projection: Array<out String>?, args: Bundle?, signal: CancellationSignal?) = delegate.query(uri, projection, args, signal)
        override fun getType(uri: Uri) = delegate.getType(uri)
        override fun openFile(uri: Uri, mode: String) = delegate.openFile(uri, mode)
        override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?) = delegate.openFile(uri, mode, signal)
        override fun call(method: String, arg: String?, extras: Bundle?) = delegate.call(method, arg, extras)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("DocumentsContract only")
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("DocumentsContract only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("DocumentsContract only")
    }
}
