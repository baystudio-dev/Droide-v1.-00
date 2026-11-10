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
class SafRefreshTypeReplacementRegressionTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, -1, 127, 10, 42)
    private val replacement = byteArrayOf(60, 0, -9, 20)

    @After fun cleanup() {
        try { assertEquals(0, SafPreservationDirectoryOs.openCount()) }
        finally { SafPreservationDirectoryOs.reset(); roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) } }
    }

    @Test(timeout = 30_000) fun fullRefreshReplacesFileWithDirectoryAndRetainsBeforeimage(): Unit = runBlocking {
        val f = fixture()
        f.external.resolve("change").writeBytes(original)
        f.local.resolve("change").writeBytes(original)
        f.seed(mapOf("change" to fingerprint(original)))
        assertTrue(f.local.resolve("change").delete())
        f.local.resolve("change/node_modules/pkg/index.js").apply { parentFile!!.mkdirs(); writeBytes(replacement) }
        assertTrue(f.local.resolve("change/empty").mkdir())

        val result = runCatching { f.mirror().refreshFromTree() }
        assertTrue("Full Refresh must support an owned file-to-directory replacement: ${result.exceptionOrNull()}", result.isSuccess)
        for (root in listOf(f.local, f.external)) {
            assertTrue(root.resolve("change").isDirectory)
            assertTrue(root.resolve("change/empty").isDirectory)
            assertArrayEquals(replacement, root.resolve("change/node_modules/pkg/index.js").readBytes())
        }
        assertEquals("d", f.image().entries["change"])
        assertEquals(fingerprint(replacement), f.image().entries["change/node_modules/pkg/index.js"])
        assertOriginalTree(f, mapOf("" to original), setOf(""))
        f.mirror().refreshFromTree()
        assertOriginalTree(f, mapOf("" to original), setOf(""))
        for (root in listOf(f.local, f.external)) {
            assertTrue(root.resolve("change/empty").isDirectory)
            assertArrayEquals(replacement, root.resolve("change/node_modules/pkg/index.js").readBytes())
        }
        f.sentinels()
    }

    @Test(timeout = 30_000) fun fullRefreshReplacesDirectoryWithFileAndRetainsCompleteBeforeimage(): Unit = runBlocking {
        val f = fixture()
        val nested = byteArrayOf(89, 0, -128, 13)
        for (root in listOf(f.local, f.external)) {
            root.resolve("change/old.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
            root.resolve("change/node_modules/pkg/index.js").apply { parentFile!!.mkdirs(); writeBytes(nested) }
            assertTrue(root.resolve("change/empty").mkdir())
        }
        f.seed(mapOf("change" to "d", "change/old.bin" to fingerprint(original),
            "change/node_modules" to "d", "change/node_modules/pkg" to "d",
            "change/node_modules/pkg/index.js" to fingerprint(nested), "change/empty" to "d"))
        assertTrue(PathSecurity.deleteTreeNoFollow(f.local.resolve("change")))
        f.local.resolve("change").writeBytes(replacement)

        val result = runCatching { f.mirror().refreshFromTree() }
        assertTrue("Full Refresh must support an owned directory-to-file replacement: ${result.exceptionOrNull()}", result.isSuccess)
        for (root in listOf(f.local, f.external)) {
            assertTrue(root.resolve("change").isFile)
            assertArrayEquals(replacement, root.resolve("change").readBytes())
        }
        assertEquals(fingerprint(replacement), f.image().entries["change"])
        assertFalse(f.image().entries.keys.any { it.startsWith("change/") })
        val payloads = mapOf("old.bin" to original, "node_modules/pkg/index.js" to nested)
        val entries = setOf("", "old.bin", "node_modules", "node_modules/pkg", "node_modules/pkg/index.js", "empty")
        assertOriginalTree(f, payloads, entries)
        f.mirror().refreshFromTree()
        assertOriginalTree(f, payloads, entries)
        for (root in listOf(f.local, f.external)) assertArrayEquals(replacement, root.resolve("change").readBytes())
        f.sentinels()
    }

    @Test(timeout = 30_000) fun unsupportedOrUnknownReplacementCapabilitiesHaveNoProviderEffect(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) for (mode in listOf("absent", "null", "string", "overflow", "no-delete", "no-create")) {
            val f = replacementFixture(directoryBefore)
            val manifest = f.manifest.readBytes()
            f.provider.omitFlags = mode == "absent"
            f.provider.flagsOverride = { id, _ -> when (mode) {
                "null" -> null
                "string" -> "12"
                "overflow" -> Long.MAX_VALUE
                "no-delete" -> if (id == "root/change") 0 else 12
                "no-create" -> if (id == "root") 4 else 12
                else -> 12
            } }
            assertTrue(mode, runCatching { f.mirror().refreshFromTree() }.isFailure)
            assertEquals(mode, 0, f.provider.mutations)
            assertArrayEquals(manifest, f.manifest.readBytes())
            assertArrayEquals(original, (if (directoryBefore) f.external.resolve("change/old.bin") else f.external.resolve("change")).readBytes())
            assertArrayEquals(replacement, desiredFile(f, directoryBefore).readBytes())
            f.sentinels()
        }
    }

    @Test(timeout = 30_000) fun cancellationBeforeReplacementDeleteLeavesBothSidesUntouched(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = replacementFixture(directoryBefore)
            val manifest = f.manifest.readBytes()
            var reached = false
            val child = launch {
                val self = this
                runCatching { f.mirror().refreshFromTree(onProgress = { if (it.phase == SafMirrorPhase.APPLYING) {
                    reached = true; self.cancel(CancellationException("cancel before replacement"))
                } }) }
            }
            child.join()
            assertTrue(reached); assertTrue(child.isCancelled)
            assertEquals(0, f.provider.mutations)
            assertTrue(f.records().isEmpty())
            assertArrayEquals(manifest, f.manifest.readBytes())
            assertArrayEquals(original, (if (directoryBefore) f.external.resolve("change/old.bin") else f.external.resolve("change")).readBytes())
            assertArrayEquals(replacement, desiredFile(f, directoryBefore).readBytes())
            f.sentinels()
        }
    }

    @Test(timeout = 30_000) fun cancellationAfterDeleteRetainsRawResultBeforeCreatingReplacement(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = replacementFixture(directoryBefore)
            val manifest = f.manifest.readBytes()
            val child = launch {
                val self = this
                f.provider.afterDelete = { self.cancel(CancellationException("cancel after delete")) }
                runCatching { f.mirror().refreshFromTree() }
            }
            child.join()
            assertTrue(child.isCancelled)
            assertEquals(1, f.provider.mutations)
            val raw = f.records().single { it.plan.kind == SafEffectKind.DELETE && it.phase == SafEffectPhase.RETURNED }
            assertEquals(SafEffectReturn.COMPLETED, raw.result)
            assertEquals(true, raw.providerReturn!!.boolean)
            assertFalse(f.records().any { it.plan.kind != SafEffectKind.DELETE && it.phase >= SafEffectPhase.ATTEMPTING })
            assertInterruptedReplacement(f, directoryBefore, manifest)
        }
    }

    @Test(timeout = 30_000) fun cancellationAfterVerifiedDeleteStillCannotReplayOrStartCreate(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = replacementFixture(directoryBefore)
            val manifest = f.manifest.readBytes()
            var reached = false
            val child = launch {
                val self = this
                f.provider.beforeQuery = {
                    if (f.provider.mutations == 1 && f.records().any { it.plan.kind == SafEffectKind.DELETE && it.phase == SafEffectPhase.VERIFIED }) {
                        reached = true; self.cancel(CancellationException("cancel before create call"))
                    }
                }
                runCatching { f.mirror().refreshFromTree() }
            }
            child.join()
            assertTrue(reached); assertTrue(child.isCancelled)
            assertEquals(1, f.provider.mutations)
            assertTrue(f.records().any { it.plan.kind == SafEffectKind.DELETE && it.phase == SafEffectPhase.VERIFIED })
            assertFalse(f.records().any { it.plan.kind != SafEffectKind.DELETE && it.phase >= SafEffectPhase.RETURNED })
            f.provider.beforeQuery = {}
            assertInterruptedReplacement(f, directoryBefore, manifest)
        }
    }

    @Test(timeout = 30_000) fun cancellationAfterCreateRetainsRawUriAndEveryOriginalByte(): Unit = runBlocking {
        for (directoryBefore in listOf(false, true)) {
            val f = replacementFixture(directoryBefore)
            val manifest = f.manifest.readBytes()
            val child = launch {
                val self = this
                f.provider.afterCreate = { self.cancel(CancellationException("cancel after replacement create")) }
                runCatching { f.mirror().refreshFromTree() }
            }
            child.join()
            assertTrue(child.isCancelled)
            assertEquals(2, f.provider.mutations)
            val raw = f.records().single { it.plan.kind in setOf(SafEffectKind.CREATE_FILE, SafEffectKind.CREATE_DIRECTORY) && it.phase == SafEffectPhase.RETURNED }
            assertEquals(SafEffectReturn.COMPLETED, raw.result)
            assertNotNull(raw.providerReturn!!.uri)
            assertNull(raw.returnedDocument)
            assertFalse(f.records().any { it.actionId == raw.actionId && it.phase == SafEffectPhase.VERIFIED })
            assertInterruptedReplacement(f, directoryBefore, manifest)
        }
    }

    private fun replacementFixture(directoryBefore: Boolean): Fixture = fixture().also { f ->
        for (root in listOf(f.local, f.external)) {
            if (directoryBefore) {
                root.resolve("change/old.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
                root.resolve("change/empty").mkdir()
            } else root.resolve("change").writeBytes(original)
        }
        f.seed(if (directoryBefore) mapOf("change" to "d", "change/old.bin" to fingerprint(original), "change/empty" to "d")
            else mapOf("change" to fingerprint(original)))
        check(PathSecurity.deleteTreeNoFollow(f.local.resolve("change")))
        desiredFile(f, directoryBefore).apply { parentFile!!.mkdirs(); writeBytes(replacement) }
    }

    private fun desiredFile(f: Fixture, directoryBefore: Boolean) = f.local.resolve(if (directoryBefore) "change" else "change/new.bin")

    private suspend fun assertInterruptedReplacement(f: Fixture, directoryBefore: Boolean, manifest: ByteArray) {
        val prepared = f.records().single { it.plan.kind == SafEffectKind.DELETE && it.phase == SafEffectPhase.PREPARED }
        val directory = f.journal.resolve(prepared.operationId)
        val image = SafJournalCodec.decode(SafTreeEvidence.serializer(), directory.resolve(prepared.plan.beforeTree!!.name).readBytes())
        assertEquals(if (directoryBefore) setOf("", "old.bin", "empty") else setOf(""), image.entries.map { it.relativePath }.toSet())
        assertArrayEquals(original, directory.resolve(image.entries.single { it.payload != null }.payload!!.name).readBytes())
        assertArrayEquals(replacement, desiredFile(f, directoryBefore).readBytes())
        assertArrayEquals(manifest, f.manifest.readBytes())
        val saved = directory.listFiles()!!.associate { it.name to it.readBytes() }
        val count = f.provider.mutations
        assertTrue(runCatching { f.mirror().refreshFromTree() }.isFailure)
        assertEquals(count, f.provider.mutations)
        assertFalse(directory.resolve("abandoned.json").exists())
        saved.forEach { (name, bytes) -> assertArrayEquals(bytes, directory.resolve(name).readBytes()) }
        f.sentinels()
    }

    private fun assertOriginalTree(f: Fixture, payloads: Map<String, ByteArray>, entries: Set<String>) {
        val deletion = f.records().single { it.plan.kind == SafEffectKind.DELETE && it.plan.path == "change" && it.phase == SafEffectPhase.VERIFIED }
        assertEquals(SafEffectReturn.COMPLETED, deletion.result)
        val directory = f.journal.resolve(deletion.operationId)
        val ref = requireNotNull(deletion.plan.beforeTree)
        val image = SafJournalCodec.decode(SafTreeEvidence.serializer(), directory.resolve(ref.name).readBytes())
        assertEquals(deletion.operationId, image.operationId)
        assertEquals("change", image.rootPath)
        assertEquals(deletion.plan.original, image.rootDocument)
        assertEquals(entries, image.entries.map { it.relativePath }.toSet())
        assertEquals(payloads.keys, image.entries.filter { it.payload != null }.map { it.relativePath }.toSet())
        image.entries.forEach { entry ->
            val payload = entry.payload
            if (payload == null) assertEquals(SafOperationJournal.DIRECTORY_MIME, entry.document.mimeType)
            else assertArrayEquals(payloads.getValue(entry.relativePath), directory.resolve(payload.name).readBytes())
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
        var beforeQuery: () -> Unit = {}
        var omitFlags = false
        var flagsOverride: ((String, File) -> Any?)? = null
        var beforeRead: (String) -> Unit = {}
        var afterWriteOpen: () -> Unit = {}
        var afterCreate: () -> Unit = {}
        var afterDelete: () -> Unit = {}
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply { beforeQuery(); row(this, documentId, file(documentId)) }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection).apply {
            beforeQuery(); file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, "$parentDocumentId/${it.name}", it) }
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
        private fun cursor(projection: Array<out String>?) = MatrixCursor((projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS)).filter { !omitFlags || it != DocumentsContract.Document.COLUMN_FLAGS }.toTypedArray())
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any?>(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to if (file.isFile) file.length() else 0L, DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
                DocumentsContract.Document.COLUMN_FLAGS to (if (flagsOverride != null) flagsOverride!!.invoke(id, file) else
                    (DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE)))
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
