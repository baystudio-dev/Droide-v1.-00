package com.baystudio.droide.core

import android.app.Application
import android.content.ContentProvider
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [SafPreservationDirectoryOs::class])
class SafPreflightIntegrityRegressionTest {
    private val roots = mutableListOf<File>()
    private val original = byteArrayOf(0, 1, -1, 127, 10, 42)
    private val changed = byteArrayOf(80, 0, -8, 9)

    @After fun cleanup() {
        try {
            assertEquals("Directory durability descriptors must close", 0, SafPreservationDirectoryOs.openCount())
        } finally {
            SafPreservationDirectoryOs.reset()
            roots.forEach { PathSecurity.deleteTreeNoFollow(it) }
        }
    }

    @Test fun deletedOrReplacedAncestorCannotDiscardNewExternalDescendant() = runBlocking {
        for (replaceWithFile in listOf(false, true)) {
            val f = fixture()
            for (root in listOf(f.local, f.external)) root.resolve("package/original.bin").apply {
                parentFile!!.mkdirs(); writeBytes(original)
            }
            f.seedManifest()
            PathSecurity.deleteTreeNoFollow(f.local.resolve("package"))
            if (replaceWithFile) f.local.resolve("package").writeBytes(changed)
            f.external.resolve("package/new/deep.bin").apply { parentFile!!.mkdirs(); writeBytes(changed) }

            val failure = runCatching { f.mirror().refreshFromTree() }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("conflict"))
            assertArrayEquals(original, f.external.resolve("package/original.bin").readBytes())
            assertArrayEquals(changed, f.external.resolve("package/new/deep.bin").readBytes())
            if (replaceWithFile) assertArrayEquals(changed, f.local.resolve("package").readBytes())
            else assertFalse(f.local.resolve("package").exists())
            f.assertUntouchedSentinels()
        }
    }

    @Test fun localChangeAfterScanFailsCompleteStageVerificationBeforeProviderEffects() = runBlocking {
        val f = fixture()
        f.local.resolve("node_modules/pkg/deep/new.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
        var injected = false
        val failure = runCatching {
            f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.RESOLVING && !injected) {
                    injected = true
                    f.local.resolve("node_modules/pkg/deep/new.bin").writeBytes(changed)
                }
            })
        }.exceptionOrNull()

        assertTrue(injected)
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("staging"))
        assertArrayEquals(changed, f.local.resolve("node_modules/pkg/deep/new.bin").readBytes())
        assertFalse(f.external.resolve("node_modules").exists())
        f.assertUntouchedSentinels()
    }

    @Test fun completeNestedStageExistsBeforeApplyingAndLiveChangeIsNotPublished() = runBlocking {
        val f = fixture()
        f.local.resolve("node_modules/pkg/deep/new.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
        f.local.resolve("node_modules/pkg/empty").mkdirs()
        var stageObserved = false
        val failure = runCatching {
            f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.APPLYING) {
                    val stage = f.parent.listFiles()!!.single { file -> file.name.startsWith(".local.saf-stage-") }
                    assertArrayEquals(original, stage.resolve("node_modules/pkg/deep/new.bin").readBytes())
                    assertTrue(stage.resolve("node_modules/pkg/empty").isDirectory)
                    stageObserved = true
                    f.local.resolve("node_modules/pkg/deep/new.bin").writeBytes(changed)
                }
            })
        }.exceptionOrNull()
        assertTrue(stageObserved)
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("changed before provider"))
        assertArrayEquals(changed, f.local.resolve("node_modules/pkg/deep/new.bin").readBytes())
        assertFalse(f.external.resolve("node_modules").exists())
        f.assertUntouchedSentinels()
    }

    @Test fun damagedStagedPayloadFailsBeforeAnyProviderEffect() = runBlocking {
        val f = fixture()
        f.local.resolve("new.bin").writeBytes(original)
        var injected = false
        val failure = runCatching {
            f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.APPLYING) {
                    val stage = f.parent.listFiles()!!.single { file -> file.name.startsWith(".local.saf-stage-") }
                    stage.resolve("new.bin").writeBytes(changed)
                    injected = true
                }
            })
        }.exceptionOrNull()
        assertTrue(injected)
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("staging changed"))
        assertArrayEquals(original, f.local.resolve("new.bin").readBytes())
        assertFalse(f.external.resolve("new.bin").exists())
        f.assertUntouchedSentinels()
    }

    @Test fun allProviderWinnersArePreflightedBeforeFirstDeletion() = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) {
            root.resolve("delete.bin").writeBytes(original)
            root.resolve("write.bin").writeBytes(original)
        }
        f.seedManifest()
        f.local.resolve("delete.bin").delete()
        f.local.resolve("write.bin").writeBytes(changed)
        var injected = false
        val failure = runCatching {
            f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.APPLYING) {
                    f.external.resolve("write.bin").writeBytes(byteArrayOf(91))
                    injected = true
                }
            })
        }.exceptionOrNull()
        assertTrue(injected)
        assertNotNull(failure)
        assertArrayEquals(original, f.external.resolve("delete.bin").readBytes())
        assertArrayEquals(byteArrayOf(91), f.external.resolve("write.bin").readBytes())
        f.assertUntouchedSentinels()
    }

    @Test fun sameBytesUnderDifferentProviderIdentityAreNotTheScannedTarget() = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) root.resolve("write.bin").writeBytes(original)
        f.seedManifest()
        f.local.resolve("write.bin").writeBytes(changed)
        var injected = false
        val failure = runCatching {
            f.mirror().refreshFromTree(onProgress = {
                if (it.phase == SafMirrorPhase.APPLYING) {
                    f.provider.newIdentities = true
                    injected = true
                }
            })
        }.exceptionOrNull()
        assertTrue(injected)
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("conflict"))
        assertArrayEquals(original, f.external.resolve("write.bin").readBytes())
        assertArrayEquals(changed, f.local.resolve("write.bin").readBytes())
        f.assertUntouchedSentinels()
    }

    @Test fun directTypeChangesFailClosedIncludingNestedSyncAndMoveDestination() = runBlocking {
        for (kind in listOf("write", "sync-file", "create-directory", "sync-directory", "nested-sync", "move")) {
            val f = fixture()
            val wasDirectory = kind == "write" || kind == "sync-file" || kind == "move"
            val target = if (kind == "nested-sync") "parent/target" else "target"
            for (root in listOf(f.local, f.external)) {
                val path = root.resolve(target)
                path.parentFile!!.mkdirs()
                if (wasDirectory) path.resolve("original.bin").apply { parentFile!!.mkdirs(); writeBytes(original) }
                else path.writeBytes(original)
            }
            f.seedManifest()
            PathSecurity.deleteTreeNoFollow(f.local.resolve(target))
            if (wasDirectory) f.local.resolve(target).writeBytes(changed)
            else f.local.resolve(target).mkdirs()
            val mutation = when (kind) {
                "write" -> FileRepository.Mutation.Write(target)
                "create-directory" -> FileRepository.Mutation.CreateDirectory(target)
                "move" -> FileRepository.Mutation.Move("source", target)
                "nested-sync" -> FileRepository.Mutation.Sync("parent")
                else -> FileRepository.Mutation.Sync(target)
            }
            val failure = runCatching { f.mirror().apply(mutation) }.exceptionOrNull()
            assertNotNull(kind, failure)
            assertTrue(kind, failure!!.message.orEmpty().contains("type replacement"))
            assertArrayEquals(original, f.external.resolve(if (wasDirectory) "$target/original.bin" else target).readBytes())
            f.assertUntouchedSentinels()
        }
    }

    @Test fun directoryCountAndActualBytesAreBoundedWithoutProviderMutation() = runBlocking {
        val wide = fixture()
        repeat(5) { wide.external.resolve("empty-$it").mkdirs() }
        assertTrue(runCatching { wide.mirror().refreshFromTree(maxFiles = 2) }.isFailure)
        wide.assertUntouchedSentinels()

        val growing = fixture()
        growing.external.resolve("large.bin").writeBytes(ByteArray(33) { 42 })
        growing.provider.declaredSize = 0L
        val failure = runCatching { growing.mirror().refreshFromTree(maxBytes = 16) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("limit"))
        assertArrayEquals(ByteArray(33) { 42 }, growing.external.resolve("large.bin").readBytes())
        growing.assertUntouchedSentinels()
    }

    @Test fun explicitDeleteAndExistingDirectoryApplyRemainAvailable() = runBlocking {
        val f = fixture()
        for (root in listOf(f.local, f.external)) {
            root.resolve("retained-directory").mkdirs()
            root.resolve("delete.bin").writeBytes(changed)
        }
        f.seedManifest()
        f.mirror().apply(FileRepository.Mutation.CreateDirectory("retained-directory"))
        assertEquals(0, f.provider.mutations)
        f.provider.allowDelete = true
        f.local.resolve("delete.bin").delete()

        f.mirror().apply(FileRepository.Mutation.Delete("delete.bin"))

        assertEquals(1, f.provider.mutations)
        assertFalse(f.external.resolve("delete.bin").exists())
        assertTrue(f.external.resolve("retained-directory").isDirectory)
        assertArrayEquals(original, f.local.resolve("sentinel.bin").readBytes())
        assertArrayEquals(original, f.external.resolve("sentinel.bin").readBytes())
        assertFalse(f.local.resolve(".droide/saf_manifest.json").readText().contains("delete.bin"))
    }

    @Test fun incompleteMalformedOrDuplicateQueriesNeverBecomeAbsence() {
        val badCursors: List<() -> Cursor?> = listOf(
            { null },
            { rows().apply { extras = Bundle().apply { putBoolean(DocumentsContract.EXTRA_LOADING, true) } } },
            { rows().apply { extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, "incomplete") } } },
            { MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) },
            { rows(arrayOf(null, "file", "text/plain")) },
            { rows(arrayOf("id", null, "text/plain")) },
            { rows(arrayOf("id", "file", null)) },
            { rows(arrayOf("id", "../escape", "text/plain")) },
            { rows(arrayOf("a", "duplicate", "text/plain"), arrayOf("b", "duplicate", "text/plain")) },
            { rows(arrayOf("same", "one", "text/plain"), arrayOf("same", "two", "text/plain")) },
            { object : MatrixCursor(COLUMNS) {
                override fun onMove(oldPosition: Int, newPosition: Int): Boolean = throw IllegalStateException("injected cursor failure")
            }.apply { addRow(arrayOf("id", "file", "text/plain")) } },
        )
        for ((index, cursor) in badCursors.withIndex()) {
            val provider = QueryProvider(cursor)
            val uri = register(provider)
            assertTrue("case $index", runCatching { SafDocumentsTreeQuery(RuntimeEnvironment.getApplication(), uri).findChild("root", "absent") }.isFailure)
            assertEquals(0, provider.mutations)
            assertEquals(1, provider.queries)
        }
    }

    @Test fun queryChecksCompletionAfterIterationAndPollsCancellationWithinRows() {
        val lateLoading = object : MatrixCursor(COLUMNS) {
            override fun getExtras(): Bundle = Bundle().apply {
                putBoolean(DocumentsContract.EXTRA_LOADING, position >= count)
            }
        }.apply { addRow(arrayOf("id", "file", "text/plain")) }
        val late = QueryProvider { lateLoading }
        assertTrue(runCatching { SafDocumentsTreeQuery(RuntimeEnvironment.getApplication(), register(late)).listChildren("root") }.isFailure)

        var polls = 0
        val cancellable = QueryProvider { rows(*Array(20) { arrayOf<Any?>("id-$it", "file-$it", "text/plain") }) }
        val query = SafDocumentsTreeQuery(RuntimeEnvironment.getApplication(), register(cancellable))
        val failure = runCatching { query.listChildren("root", checkCancelled = {
            if (++polls == 8) throw CancellationException("cancel read")
        }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(8, polls)
        assertEquals(0, cancellable.mutations)
    }

    @Test fun queryRowLimitAndReadBudgetRejectOverflow() {
        val provider = QueryProvider { rows(arrayOf("a", "one", "text/plain"), arrayOf("b", "two", "text/plain")) }
        val query = SafDocumentsTreeQuery(RuntimeEnvironment.getApplication(), register(provider))
        assertTrue(runCatching { query.listChildren("root", maxEntries = 1) }.isFailure)
        val budget = SafReadBudget(3)
        budget.consume(3)
        assertTrue(runCatching { budget.consume(1) }.isFailure)
        assertEquals(3L, budget.bytes)
    }

    @Test fun transientZeroReadsKeepBytesAndRepeatedZeroReadsAreBounded() {
        val bytes = ByteArrayInputStream(original)
        var first = true
        val transient = object : InputStream() {
            override fun read(): Int = bytes.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return bytes.read(buffer, offset, length)
            }
        }
        val buffer = ByteArray(64)
        assertEquals(original.size, readSafChunk(transient, buffer) {})
        assertArrayEquals(original, buffer.copyOf(original.size))
        assertEquals(-1, readSafChunk(transient, buffer) {})
        var reads = 0
        val stalled = object : InputStream() {
            override fun read(): Int = error("Unexpected single-byte read")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return 0 }
        }
        assertTrue(runCatching { readSafChunk(stalled, buffer) {} }.isFailure)
        assertEquals(32, reads)
    }

    private fun fixture(): Fixture {
        val parent = Files.createTempDirectory("droide-saf-preflight-").toFile().also { roots += it }
        SafPreservationDirectoryOs.allow(parent)
        val ownedJournal = RuntimeEnvironment.getApplication().filesDir.resolve("saf-owned-operations").apply { mkdirs() }
        SafPreservationDirectoryOs.allow(ownedJournal)
        val local = parent.resolve("local").apply { mkdirs() }
        val external = parent.resolve("external").apply { mkdirs() }
        local.resolve("sentinel.bin").writeBytes(original)
        external.resolve("sentinel.bin").writeBytes(original)
        val provider = FileProvider(external)
        return Fixture(parent, local, external, provider, register(provider))
    }

    private fun register(provider: ContentProvider): Uri {
        val authority = "droide.saf.preflight.${UUID.randomUUID()}"
        val application = RuntimeEnvironment.getApplication()
        val providerInfo = ProviderInfo().apply {
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
        provider.attachInfo(application, providerInfo)
        // isDocumentUri also requires PackageManager discovery, separately from resolver dispatch.
        Shadows.shadowOf(application.packageManager).apply {
            addOrUpdateProvider(providerInfo)
            addIntentFilterForProvider(ComponentName(providerInfo.packageName, providerInfo.name),
                IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "root/sentinel.bin")
        check(DocumentsContract.isDocumentUri(application, childUri)) { "SAF fixture provider is not discoverable" }
        check(androidx.documentfile.provider.DocumentFile.fromTreeUri(application, childUri)?.uri == childUri) {
            "SAF fixture tree wrapper did not retain its child identity"
        }
        return treeUri
    }

    private inner class Fixture(val parent: File, val local: File, val external: File, val provider: FileProvider, val uri: Uri) {
        fun mirror() = SafMirror(RuntimeEnvironment.getApplication(), uri.toString(), local)
        fun seedManifest() {
            val entries = local.walkTopDown().filter { it != local }.associate { file ->
                local.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/') to
                    if (file.isDirectory) "d" else "f:" + MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            }
            local.resolve(".droide/saf_manifest.json").apply {
                parentFile!!.mkdirs(); writeText("""{"version":2,"entries":${Json.encodeToString(entries)}}""")
            }
        }
        fun assertUntouchedSentinels() {
            assertEquals(0, provider.mutations)
            assertArrayEquals(original, local.resolve("sentinel.bin").readBytes())
            assertArrayEquals(original, external.resolve("sentinel.bin").readBytes())
            assertFalse(local.resolve(".droide/saf_activation_pending.json").exists())
        }
    }

    private class QueryProvider(val result: () -> Cursor?) : ContentProvider() {
        var queries = 0
        var mutations = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queries++; return result()
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; error("Unexpected insert") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("Unexpected update") }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; error("Unexpected delete") }
    }

    private class FileProvider(private val root: File) : DocumentsProvider() {
        var mutations = 0
        var declaredSize: Long? = null
        var allowDelete = false
        var newIdentities = false
        override fun onCreate() = true
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray<String>())
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).apply {
            if (file(documentId).exists()) row(this, documentId, file(documentId))
        }
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).apply {
            file(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }.forEach { row(this, "$parentDocumentId/${it.name}", it) }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            if ('w' in mode) mutations++
            return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
        }
        override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
            mutations++
            error("Unexpected provider create during preflight")
        }
        override fun deleteDocument(documentId: String) {
            mutations++
            check(allowDelete) { "Unexpected provider delete during preflight" }
            check(PathSecurity.deleteTreeNoFollow(file(documentId)))
        }
        override fun isChildDocument(parentDocumentId: String, documentId: String) = documentId.startsWith("$parentDocumentId/")
        private fun file(id: String): File {
            require(id == "root" || id.startsWith("root/"))
            return if (id == "root") root else PathSecurity.resolveWithin(root, id.removePrefix("root/").removeSuffix("@replacement"))
        }
        private fun cursor(projection: Array<out String>?) = MatrixCursor(projection ?: COLUMNS)
        private fun row(cursor: MatrixCursor, id: String, file: File) {
            val values = mapOf<String, Any>(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID to if (newIdentities && id != "root" && !id.endsWith("@replacement")) "$id@replacement" else id,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to file.name,
                DocumentsContract.Document.COLUMN_MIME_TYPE to if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
                DocumentsContract.Document.COLUMN_SIZE to (declaredSize ?: file.length()),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),
            )
            cursor.addRow(cursor.columnNames.map { values[it] }.toTypedArray())
        }
    }

    companion object {
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        private fun rows(vararg values: Array<Any?>): MatrixCursor = MatrixCursor(COLUMNS).apply { values.forEach { addRow(it) } }
    }
}
