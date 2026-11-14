package com.baystudio.droide.core

import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
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

/**
 * Frozen existing-API before/after assertions. Exactly two tiny full Refresh cases.
 * Uses the reviewed R24 DocumentsProvider bridge and SafPreservationDirectoryOs fixture.
 * No maintenance API, production seam, real provider, device, native, PTY, or process work.
 * Passive artifact observations are not an exhaustive filesystem syscall trace.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafUnchangedRefreshBaselineTest {
    private val roots = arrayListOf<File>()
    private val original = byteArrayOf(0, -1, 127, 10, 42)
    private val replacement = byteArrayOf(60, 0, -9, 20)
    private val dependency = byteArrayOf(91, 0, -128)

    @After fun cleanup() {
        try { assertEquals(0, SafPreservationDirectoryOs.openCount()) }
        finally {
            SafPreservationDirectoryOs.reset()
            roots.asReversed().forEach { PathSecurity.deleteTreeNoFollow(it) }
        }
    }

    @Test(timeout = 30_000)
    fun identicalFullRefreshWithTrustedLegacyManifestAllocatesNothing(): Unit = runBlocking {
        val f = fixture()
        assertFalse("fixture: journal must start absent", f.journal.exists())
        val legacy = SafLegacyEvidence(RuntimeEnvironment.getApplication().filesDir,
            f.local, f.tree.toString()).inspect()
        assertEquals("fixture: trusted legacy JSON", SafLegacyManifestState.JSON_V2, legacy.manifestState)
        assertEquals("fixture: exact seeded baseline", f.entries(original), legacy.baselineEntries)
        legacy.revalidateAndRequireDisjoint(f.local, listOf("")) {}
        f.assertPayload(original)
        f.assertNoTemporaryArtifacts()
        val localBefore = capture(f.local)
        val externalBefore = capture(f.external)
        val manifestBefore = f.manifest.readBytes()
        val observations = f.observeRefresh()

        val result = runCatching { f.refresh(observations) }

        // These must pass before the intended assertion. A routing/fsync/fixture error is not
        // evidence of the unchanged-Refresh defect.
        assertTrue("fixture: full Refresh must finish successfully: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("fixture: successful full Refresh result", "SAF synchronized: 2 files", result.getOrNull())
        f.assertPayload(original)
        assertEquals("unchanged Refresh must have zero provider effects", Effects(0, 0, 0), f.provider.effects())
        assertTrue("fixture: real provider reads must occur", f.provider.readOpens > 0)
        assertTrue("fixture: artifact observation must remain readable", observations.failures.isEmpty())

        // Central intended baseline failure on R24: identical bytes currently bootstrap a journal.
        assertFalse("UNCHANGED_REFRESH_ALLOCATED_JOURNAL: trusted equal trees must not bootstrap owned history", f.journal.exists())
        assertTrue("unchanged Refresh must never stage or back up: ${observations.artifacts}", observations.artifacts.isEmpty())
        assertArrayEquals("legacy manifest bytes must be retained exactly", manifestBefore, f.manifest.readBytes())
        assertEquals("local root, files, directories and manifest identities/bytes must be retained", localBefore, capture(f.local))
        assertEquals("provider backing identities/bytes must be retained", externalBefore, capture(f.external))
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun identicalFullRefreshAfterOneChangedSyncPreservesEveryJournalObject(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("sentinel.bin").writeBytes(replacement)
        val first = runCatching { f.refresh() }
        assertTrue("fixture: genuine changed full Refresh must succeed: ${first.exceptionOrNull()}", first.isSuccess)
        assertEquals("SAF synchronized: 2 files", first.getOrNull())
        f.assertPayload(replacement)
        assertTrue("fixture: first sync must perform a provider write", f.provider.writeOpens > 0)
        assertTrue("fixture: first sync must create owned history", f.journal.isDirectory)
        val core = SafOperationJournal.open(f.local, f.tree.toString(), f.journal, SafOperationStorage())
        val operations = core.recoverAll()
        assertEquals("fixture: exactly one genuine successful operation", 1, operations.size)
        assertTrue("fixture: publication must have an acknowledgement", "committed.json" in operations.single().files)
        assertTrue("fixture: operation must be terminal", core.isTerminal(operations.single()))
        val publisher = SafDurableManifestCommit(core)
        assertNotNull("fixture: exact visible manifest must be admitted", publisher.admitBeforeEffects(operations) {})
        assertEquals("fixture: acknowledged baseline must match both trees", f.entries(replacement), publisher.trusted().entries)
        f.assertNoTemporaryArtifacts()
        val journalBefore = capture(f.journal)
        val localBefore = capture(f.local)
        val externalBefore = capture(f.external)
        val manifestBefore = f.manifest.readBytes()
        val effectsBefore = f.provider.effects()
        val readsBefore = f.provider.readOpens
        val observations = f.observeRefresh()

        val result = runCatching { f.refresh(observations) }

        assertTrue("fixture: repeated full Refresh must finish successfully: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("SAF synchronized: 2 files", result.getOrNull())
        f.assertPayload(replacement)
        assertEquals("unchanged Refresh must add zero provider effects", effectsBefore, f.provider.effects())
        assertTrue("fixture: repeated Refresh must read the provider", f.provider.readOpens > readsBefore)
        assertTrue("fixture: artifact observation must remain readable", observations.failures.isEmpty())
        val journalAfter = capture(f.journal)
        journalBefore.forEach { (path, objectBefore) ->
            assertEquals("existing journal object must retain identity and bytes: $path", objectBefore, journalAfter[path])
        }

        // Central intended baseline failure on R24: one more operation appears despite equality.
        assertEquals("UNCHANGED_REFRESH_GREW_JOURNAL: no new operation, checkpoint, receipt or other object is allowed",
            journalBefore.keys, journalAfter.keys)
        assertEquals("journal object count must not change", journalBefore.size, journalAfter.size)
        assertEquals("every journal identity and byte must be unchanged", journalBefore, journalAfter)
        assertTrue("unchanged Refresh must never stage or back up: ${observations.artifacts}", observations.artifacts.isEmpty())
        assertArrayEquals("owned manifest bytes must be retained exactly", manifestBefore, f.manifest.readBytes())
        assertEquals("local root, files, directories and manifest identities/bytes must be retained", localBefore, capture(f.local))
        assertEquals("provider backing identities/bytes must be retained", externalBefore, capture(f.external))
        f.assertNoTemporaryArtifacts()
    }

    private fun fingerprint(bytes: ByteArray) = "f:${bytes.safDigest()}"
    private data class CapturedNode(val key: String, val directory: Boolean, val bytes: List<Byte>?)

    /** Tiny fixture inventory, not a general unbounded tree reader. Root identity is included. */
    private fun capture(root: File): Map<String, CapturedNode> {
        val out = linkedMapOf<String, CapturedNode>()
        var totalBytes = 0L
        fun visit(file: File, path: String, depth: Int) {
            check(depth <= 12 && out.size < 512) { "fixture inventory exceeds its fixed bound" }
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile))
            val bytes = if (attrs.isRegularFile) {
                check(attrs.size() <= 1_048_576L - totalBytes)
                file.readBytes().also { data ->
                    check(data.size.toLong() == attrs.size())
                    totalBytes += data.size
                    check(totalBytes <= 1_048_576L)
                }.toList()
            } else null
            out[path] = CapturedNode(requireNotNull(attrs.fileKey()).toString(), attrs.isDirectory, bytes)
            if (attrs.isDirectory) requireNotNull(file.listFiles()).sortedBy { it.name }.forEach {
                visit(it, if (path.isEmpty()) it.name else "$path/${it.name}", depth + 1)
            }
        }
        visit(root, "", 0)
        return out
    }

    private fun fixture(): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val parent = Files.createTempDirectory("saf-unchanged-refresh-").toFile().also { roots += it }
        SafPreservationDirectoryOs.allow(parent)
        val journalParent = app.filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(journalParent)
        val local = parent.resolve("local").apply { check(mkdir()) }
        val external = parent.resolve("external").apply { check(mkdir()) }
        for (root in listOf(local, external)) {
            root.resolve("sentinel.bin").writeBytes(original)
            root.resolve("node_modules/pkg/index.js").apply { check(parentFile!!.mkdirs()); writeBytes(dependency) }
            check(root.resolve("empty").mkdir())
        }
        val authority = "droide.unchanged.${UUID.randomUUID()}"
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
        val journal = journalParent.resolve((tree.toString() + "\u0000" + local.absolutePath).toByteArray().safDigest())
        roots += journal
        return Fixture(parent, local, external, provider, tree, journal).also { f ->
            check(f.manifest.parentFile!!.mkdirs())
            f.manifest.writeBytes(SafJournalCodec.encode(SafLegacyTrustedManifest.serializer(),
                SafLegacyTrustedManifest(entries = f.entries(original))))
        }
    }

    private class Observations {
        val artifacts = sortedSetOf<String>()
        val failures = arrayListOf<String>()
        var poll: () -> Unit = {}
    }

    private inner class Fixture(val parent: File, val local: File, val external: File,
        val provider: Provider, val tree: Uri, val journal: File) {
        val manifest get() = local.resolve(".droide/saf_manifest.json")
        fun entries(sentinel: ByteArray) = sortedMapOf("sentinel.bin" to fingerprint(sentinel),
            "node_modules" to "d", "node_modules/pkg" to "d",
            "node_modules/pkg/index.js" to fingerprint(dependency), "empty" to "d")
        suspend fun refresh(observations: Observations? = null): String =
            SafMirror(RuntimeEnvironment.getApplication(), tree.toString(), local).refreshFromTree(
                maxFiles = 4, maxBytes = 256, maxDepth = 8,
                onProgress = { observations?.poll?.invoke() })
        fun assertPayload(sentinel: ByteArray) {
            for (root in listOf(local, external)) {
                assertArrayEquals("fixture: sentinel bytes in ${root.name}", sentinel, root.resolve("sentinel.bin").readBytes())
                assertArrayEquals("dependency bytes remain fully mirrored", dependency, root.resolve("node_modules/pkg/index.js").readBytes())
                assertTrue("empty directory remains fully mirrored", root.resolve("empty").isDirectory)
            }
        }
        fun temporaryArtifacts() = requireNotNull(parent.listFiles()).filter {
            it.name.startsWith(".${local.name}.saf-stage-") || it.name.startsWith(".${local.name}.saf-backup-")
        }.map { it.name }
        fun assertNoTemporaryArtifacts() {
            assertTrue("fixture: no remaining stage or backup", temporaryArtifacts().isEmpty())
            assertFalse("fixture: no activation marker", local.resolve(".droide/saf_activation_pending.json").exists())
        }
        fun observeRefresh() = Observations().also { observations ->
            observations.poll = {
                try { observations.artifacts.addAll(temporaryArtifacts()) }
                catch (failure: Throwable) { observations.failures += failure.javaClass.name }
            }
            provider.observe = observations.poll
            observations.poll()
        }
    }

    private data class Effects(val creates: Int, val deletes: Int, val writeOpens: Int)
    private class Provider(private val root: File) : DocumentsProvider() {
        var creates = 0
        var deletes = 0
        var writeOpens = 0
        var readOpens = 0
        var observe: () -> Unit = {}
        fun effects() = Effects(creates, deletes, writeOpens)
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply {
            observe(); row(this, documentId, file(documentId))
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection).apply {
            observe(); requireNotNull(file(parentDocumentId).listFiles()).sortedBy { it.name }.forEach {
                row(this, "$parentDocumentId/${it.name}", it)
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            observe()
            val fd = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
            if ('w' in mode) writeOpens++ else readOpens++
            return fd
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            observe()
            val file = file(parentDocumentId).resolve(PathSecurity.safeLeafName(displayName))
            check(!file.exists())
            check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile())
            creates++
            return "$parentDocumentId/$displayName"
        }
        override fun deleteDocument(documentId: String) {
            observe(); check(PathSecurity.deleteTreeNoFollow(file(documentId))); deletes++
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File {
            require(id == "root" || id.startsWith("root/"))
            return if (id == "root") root else root.resolve(id.removePrefix("root/"))
        }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS))
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any?>(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to if (file.isFile) file.length() else 0L,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
                DocumentsContract.Document.COLUMN_FLAGS to (DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                    if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else DocumentsContract.Document.FLAG_SUPPORTS_WRITE))
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
