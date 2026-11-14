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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Tiny staged controls for the Mirror-only unchanged proof. Not executed by the author. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafUnchangedRefreshProofTest {
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
    fun trustedLegacyNoOpReadsExactlyTwoContentPassesAndAllocatesNoHistory(): Unit = runBlocking {
        val f = fixture()
        val before = capture(f.local)
        val external = capture(f.external)
        var externalBytes = 0L
        var localBytes = 0L
        val phases = arrayListOf<SafMirrorPhase>()
        val observations = f.observeRefresh()
        assertEquals("SAF synchronized: 2 files", f.refresh(observations,
            reads = { provider, bytes -> if (provider) externalBytes += bytes else localBytes += bytes },
            progress = { phases += it.phase }))
        assertEquals(2L * (original.size + dependency.size), externalBytes)
        assertEquals(2L * (original.size + dependency.size), localBytes)
        assertEquals("two reads of each of the two files", 4, f.provider.readOpens)
        assertEquals(listOf(SafMirrorPhase.PREPARING, SafMirrorPhase.RESOLVING), phases)
        assertFalse(f.journal.exists())
        assertEquals(Effects(0, 0, 0), f.provider.effects())
        assertEquals(before, capture(f.local)); assertEquals(external, capture(f.external))
        assertTrue(observations.artifacts.isEmpty()); assertTrue(observations.failures.isEmpty())
    }

    @Test(timeout = 30_000)
    fun twoNoOpsAfterOneChangedRefreshKeepExactlyOneOperation(): Unit = runBlocking {
        val f = fixture()
        f.local.resolve("sentinel.bin").writeBytes(replacement)
        f.refresh()
        f.assertPayload(replacement)
        assertEquals(1, f.core().recoverAll().size)
        val journal = capture(f.journal)
        val local = capture(f.local)
        val external = capture(f.external)
        val effects = f.provider.effects()
        repeat(2) {
            var externalBytes = 0L
            var localBytes = 0L
            val reads = f.provider.readOpens
            f.refresh(reads = { provider, bytes -> if (provider) externalBytes += bytes else localBytes += bytes })
            assertEquals(2L * (replacement.size + dependency.size), externalBytes)
            assertEquals(2L * (replacement.size + dependency.size), localBytes)
            assertEquals(4, f.provider.readOpens - reads)
            assertEquals(1, f.core().recoverAll().size)
            assertEquals(journal, capture(f.journal)); assertEquals(local, capture(f.local)); assertEquals(external, capture(f.external))
            assertEquals(effects, f.provider.effects())
        }
    }

    @Test(timeout = 30_000)
    fun equalBytesUnderReplacedLocalIdentityCannotQualify(): Unit = runBlocking {
        val f = fixture()
        val manifest = f.manifest.readBytes()
        val beforeKey = SafOperationStorage().key(f.local.resolve("sentinel.bin"), false)
        var changed = false
        val result = runCatching { f.refresh(progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
            changed = true
            val temp = f.parent.resolve("replacement.bin").apply { writeBytes(original) }
            Files.move(temp.toPath(), f.local.resolve("sentinel.bin").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } }) }
        assertTrue(changed); assertTrue(result.isFailure)
        assertNotEquals(beforeKey, SafOperationStorage().key(f.local.resolve("sentinel.bin"), false))
        f.assertPayload(original)
        assertArrayEquals(manifest, f.manifest.readBytes())
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun equalBytesUnderReplacedProviderIdentityCannotQualify(): Unit = runBlocking {
        val f = fixture()
        val local = capture(f.local)
        var changed = false
        val result = runCatching { f.refresh(progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
            changed = true; f.provider.alternateSentinelIdentity = true
        } }) }
        assertTrue(changed); assertTrue(result.isFailure)
        assertEquals(local, capture(f.local)); f.assertPayload(original)
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun cancellationBeforeSecondPassPreservesAllBytesAndAllocatesNothing(): Unit = runBlocking {
        val f = fixture()
        val local = capture(f.local)
        val external = capture(f.external)
        var reached = false
        var externalBytes = 0L
        var localBytes = 0L
        val child = launch {
            val self = this
            f.refresh(reads = { provider, bytes -> if (provider) externalBytes += bytes else localBytes += bytes },
                progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
                    reached = true; self.cancel(CancellationException("stop before final proof"))
                } })
        }
        child.join()
        assertTrue(reached); assertTrue(child.isCancelled)
        assertEquals((original.size + dependency.size).toLong(), externalBytes)
        assertEquals((original.size + dependency.size).toLong(), localBytes)
        assertEquals(local, capture(f.local)); assertEquals(external, capture(f.external))
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun replacedVisibleManifestIsRejectedAfterBothContentPasses(): Unit = runBlocking {
        val f = fixture()
        val manifest = f.manifest.readBytes()
        val key = SafOperationStorage().key(f.manifest, false)
        var reached = false
        val result = runCatching { f.refresh(progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
            reached = true
            val temp = f.parent.resolve("manifest-copy").apply { writeBytes(manifest) }
            Files.move(temp.toPath(), f.manifest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } }) }
        assertTrue(reached); assertTrue(result.isFailure)
        assertEquals(4, f.provider.readOpens)
        assertNotEquals(key, SafOperationStorage().key(f.manifest, false))
        assertArrayEquals(manifest, f.manifest.readBytes()); f.assertPayload(original)
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun preparedAbandonmentMakesThisInvocationIneligibleForNoOp(): Unit = runBlocking {
        val f = fixture()
        val pending = f.createCore().begin(SafOperationRequest(SafOperationKind.REFRESH, emptyList()), listOf(""),
            SafJournalReservation(0, 4096, 4096, indexBytes = 2048, evidenceFiles = 0))
        var proofBytes = 0L
        assertEquals("SAF synchronized: 2 files", f.refresh(reads = { _, bytes -> proofBytes += bytes }))
        val operations = f.core().recoverAll()
        assertEquals("one abandoned preparation and one genuine completed Refresh", 2, operations.size)
        assertTrue("abandoned.json" in operations.single { it.id == pending.id }.files)
        assertTrue("committed.json" in operations.single { it.id != pending.id }.files)
        assertEquals("a mutating admission must skip the no-op proof", 0L, proofBytes)
        assertEquals(Effects(0, 0, 0), f.provider.effects()); f.assertPayload(original)
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun sameSizeSameMtimeChangedContentUsesNormalSyncWithBoundedPreflight(): Unit = runBlocking {
        val f = fixture()
        val changed = original.reversedArray()
        val file = f.local.resolve("sentinel.bin")
        val time = file.lastModified()
        file.writeBytes(changed); assertTrue(file.setLastModified(time))
        assertEquals(time, file.lastModified()); assertEquals(original.size.toLong(), file.length())
        var externalBytes = 0L
        var localBytes = 0L
        f.refresh(reads = { provider, bytes -> if (provider) externalBytes += bytes else localBytes += bytes })
        assertTrue(externalBytes <= (original.size + dependency.size).toLong())
        assertTrue(localBytes <= (original.size + dependency.size).toLong())
        assertTrue(f.provider.writeOpens > 0)
        assertEquals(1, f.core().recoverAll().size)
        f.assertPayload(changed); f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun corruptJournalAppearingAfterInitialAdmissionCannotBeBypassed(): Unit = runBlocking {
        val f = fixture()
        f.createCore()
        val before = capture(f.journal)
        val local = capture(f.local)
        var reached = false
        val result = runCatching { f.refresh(progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
            reached = true; f.journal.resolve("foreign").writeBytes(replacement)
        } }) }
        assertTrue(reached); assertTrue(result.isFailure)
        assertArrayEquals(replacement, f.journal.resolve("foreign").readBytes())
        before.forEach { (path, node) -> assertEquals(node, capture(f.journal)[path]) }
        assertEquals(local, capture(f.local)); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun noProviderCallbackCanMutateLocalBytesAfterTheFinalLocalContentPass(): Unit = runBlocking {
        val f = fixture()
        val local = capture(f.local)
        val external = capture(f.external)
        val expected = 2L * (original.size + dependency.size)
        var externalBytes = 0L
        var localBytes = 0L
        var lateCalls = 0
        f.provider.observe = {
            if (externalBytes == expected && localBytes == expected) {
                lateCalls++
                // V1 allowed this same-inode edit after hashing and still returned a no-op.
                f.local.resolve("sentinel.bin").writeBytes(original.reversedArray())
            }
        }
        assertEquals("SAF synchronized: 2 files", f.refresh(reads = { provider, bytes ->
            if (provider) externalBytes += bytes else localBytes += bytes
        }))
        assertEquals(expected, externalBytes); assertEquals(expected, localBytes)
        assertEquals("all provider callbacks must precede the final local content pass", 0, lateCalls)
        assertEquals(local, capture(f.local)); assertEquals(external, capture(f.external))
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun providerCallbackAfterExternalProofIsDetectedByTheFinalLocalContentPass(): Unit = runBlocking {
        val f = fixture()
        val manifest = f.manifest.readBytes()
        val expected = 2L * (original.size + dependency.size)
        var externalBytes = 0L
        var localBytes = 0L
        var injected = false
        val file = f.local.resolve("sentinel.bin")
        val key = SafOperationStorage().key(file, false)
        f.provider.observe = {
            if (!injected && externalBytes == expected) {
                injected = true
                val modified = file.lastModified()
                file.writeBytes(original.reversedArray())
                check(file.setLastModified(modified)) // Same identity, size and millisecond mtime.
            }
        }
        val result = runCatching { f.refresh(reads = { provider, bytes ->
            if (provider) externalBytes += bytes else localBytes += bytes
        }) }
        assertTrue(injected); assertTrue(result.isFailure)
        assertEquals(key, SafOperationStorage().key(file, false))
        assertArrayEquals(original.reversedArray(), file.readBytes())
        assertArrayEquals(original, f.external.resolve("sentinel.bin").readBytes())
        assertArrayEquals(manifest, f.manifest.readBytes())
        assertEquals(expected, externalBytes); assertTrue(localBytes <= expected)
        assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
        f.assertNoTemporaryArtifacts()
    }

    @Test(timeout = 30_000)
    fun checkpointPresentNoOpsPreserveExactHeadHistoryAndReadBounds(): Unit = runBlocking {
        val f = fixture()
        val limits = SafJournalLimits(operations = 3)
        val storage = SafOperationStorage()
        fun reopen() = SafOperationJournal.open(f.local, f.tree.toString(), f.journal, storage, limits)
        SafOperationJournal.create(f.local, f.tree.toString(), f.journal, storage, limits)
        val parent = SafOwnedDocument(DocumentsContract.buildDocumentUriUsingTree(f.tree, "root").toString(),
            "root", f.external.name, SafOperationJournal.DIRECTORY_MIME, "root")
        val document = SafOwnedDocument(DocumentsContract.buildDocumentUriUsingTree(f.tree, "root/sentinel.bin").toString(),
            "root/sentinel.bin", "sentinel.bin", "application/octet-stream", "root")
        val intended = f.parent.resolve("modeled-intended.bin")
        var modeledWrites = 0
        WorkspaceFileMutationLocks.withRoot(f.local) { guard ->
            val poll = { guard.requireRoot(f.local) }
            repeat(3) { ordinal ->
                val core = reopen()
                val next = byteArrayOf((ordinal + 1).toByte())
                intended.writeBytes(next)
                val operation = core.begin(SafOperationRequest(SafOperationKind.WRITE, listOf("sentinel.bin")),
                    listOf("sentinel.bin"), SafJournalReservation(1, 64L * 1024, 64L * 1024,
                        indexBytes = 8L * 1024, evidenceFiles = 2), poll)
                val before = core.preserve(operation, f.external.resolve("sentinel.bin"), poll)
                val desired = core.preserve(operation, intended, poll)
                val action = core.prepare(operation, SafEffectPlan(SafEffectKind.WRITE_FILE, "sentinel.bin",
                    document, parent, null, before, desired), poll)
                core.attempting(action, poll)
                // Model exactly one successful ordinary write; no provider/device process is used.
                f.external.resolve("sentinel.bin").writeBytes(next)
                f.local.resolve("sentinel.bin").writeBytes(next)
                modeledWrites++
                core.returned(action, SafEffectReturn.COMPLETED, SafProviderReturn(SafProviderReturnShape.VOID), poll)
                core.verified(action, document, poll)
                val publisher = SafDurableManifestCommit(core)
                publisher.plan(operation, mapOf("sentinel.bin" to fingerprint(next)), poll)
                val committed = publisher.publish(operation, poll)
                assertTrue(committed.acknowledged); assertNull(committed.retainedAcknowledgementFailure)
            }
        }
        assertEquals(3, modeledWrites)
        assertTrue(f.journal.resolve(SafJournalRetirement.CURRENT).isFile)
        val head = requireNotNull(SafJournalRetirement(reopen()).load {})
        assertTrue(head.outstanding.isEmpty())
        assertEquals(2, reopen().recoverAll().size)
        val journal = capture(f.journal)
        val local = capture(f.local)
        val external = capture(f.external)
        repeat(2) {
            var externalBytes = 0L
            var localBytes = 0L
            val reads = f.provider.readOpens
            f.refresh(reads = { provider, count -> if (provider) externalBytes += count else localBytes += count })
            assertEquals(2L * (1 + dependency.size), externalBytes)
            assertEquals(2L * (1 + dependency.size), localBytes)
            assertEquals(4, f.provider.readOpens - reads)
            assertEquals(2, reopen().recoverAll().size)
            assertEquals(journal, capture(f.journal)); assertEquals(local, capture(f.local)); assertEquals(external, capture(f.external))
            assertEquals(head.identity, requireNotNull(SafJournalRetirement(reopen()).load {}).identity)
            assertEquals(Effects(0, 0, 0), f.provider.effects())
            f.assertPayload(byteArrayOf(3)); f.assertNoTemporaryArtifacts()
        }
    }

    @Test(timeout = 30_000)
    fun lateEmptyDirectoryOrIncompleteCursorCannotQualifyAsNoOp(): Unit = runBlocking {
        for (incomplete in listOf(false, true)) {
            val f = fixture()
            val local = capture(f.local)
            var injected = false
            val result = runCatching { f.refresh(progress = { if (it.phase == SafMirrorPhase.RESOLVING) {
                injected = true
                if (incomplete) f.provider.incompleteCursor = true
                else check(f.external.resolve("new-empty").mkdir())
            } }) }
            assertTrue(injected); assertTrue(result.isFailure)
            if (!incomplete) assertTrue(f.external.resolve("new-empty").isDirectory)
            assertEquals(local, capture(f.local)); f.assertPayload(original)
            assertFalse(f.journal.exists()); assertEquals(Effects(0, 0, 0), f.provider.effects())
            f.assertNoTemporaryArtifacts()
        }
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
        suspend fun refresh(observations: Observations? = null, reads: ((Boolean, Int) -> Unit)? = null,
            progress: ((SafMirrorProgress) -> Unit)? = null): String {
            val app = RuntimeEnvironment.getApplication()
            val mirror = if (reads == null) SafMirror(app, tree.toString(), local)
                else SafMirror(app, tree.toString(), local, reads)
            return mirror.refreshFromTree(maxFiles = 4, maxBytes = 256, maxDepth = 8,
                onProgress = { observations?.poll?.invoke(); progress?.invoke(it) })
        }
        fun core() = SafOperationJournal.open(local, tree.toString(), journal, SafOperationStorage())
        fun createCore() = SafOperationJournal.create(local, tree.toString(), journal, SafOperationStorage())
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
        var alternateSentinelIdentity = false
        var incompleteCursor = false
        fun effects() = Effects(creates, deletes, writeOpens)
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
        override fun queryDocument(documentId: String, projection: Array<out String>?) = cursor(projection).apply {
            observe(); row(this, documentId, file(documentId))
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = cursor(projection).apply {
            observe(); requireNotNull(file(parentDocumentId).listFiles()).sortedBy { it.name }.forEach {
                row(this, "$parentDocumentId/${it.name}" +
                    if (alternateSentinelIdentity && it.name == "sentinel.bin") "#new" else "", it)
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
            return if (id == "root") root else root.resolve(id.removePrefix("root/").removeSuffix("#new"))
        }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)).apply {
                if (incompleteCursor) extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) }
            }
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
