package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Candidate-only tests. Identity cipher isolates the real serialization; no cryptography claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorRecoveryIdentityTest {
    @get:Rule val temp = TemporaryFolder()
    private val path = "main.txt"

    private class CountingCipher : EditorRecoveryCipher {
        var reads = 0
        override fun encrypt(plain: ByteArray, aad: ByteArray) = plain.copyOf()
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray { reads++; return encrypted.copyOf() }
    }
    private class Fixture(val project: File, val source: File, val store: EditorStateStore,
        val manifest: File, val blobs: File, val cipher: CountingCipher)

    private fun fixture(): Fixture {
        val app = temp.newFolder()
        val project = File(app, "projects/project").apply { check(mkdirs()) }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = app
            override fun getApplicationContext(): Context = this
        }
        val cipher = CountingCipher()
        val store = EditorStateStore(context, "project", cipher) { directory ->
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        return Fixture(project, File(project, path), store,
            File(app, ".droide/editor-recovery/project.bin"), File(app, ".droide/editor-recovery/project.blobs"), cipher)
    }

    private suspend fun captured(f: Fixture, original: String? = "A", edited: String = "U"): EditorRecoverySnapshot {
        if (original == null) { if (f.source.exists()) check(f.source.delete()) } else f.source.writeText(original)
        val state = EditorWorkspaceState()
        state.document(path).apply { ensureLoaded(FileRepository(f.project)); capture(edited) }
        return checkNotNull(state.recoverySnapshot(listOf(path), path))
    }

    private suspend fun restore(f: Fixture, snapshot: EditorRecoverySnapshot): EditorWorkspaceState {
        val state = EditorWorkspaceState()
        EditorTabRetention.restoreDocuments(snapshot, FileRepository(f.project), state)
        state.document(path).ensureLoaded(FileRepository(f.project))
        return state
    }

    @Test(timeout = 15_000)
    fun schemasOneThroughFiveRemainUnknownAfterRestoreAndRecheckpoint() = runBlocking {
        for (version in 1..5) {
            val f = fixture()
            f.store.save(captured(f, edited = "same as current disk"))
            val root = Json.parseToJsonElement(f.manifest.readText()).jsonObject
            val legacy = JsonObject(root.toMutableMap().apply {
                put("version", JsonPrimitive(version))
                put("dirty", if (version <= 3) buildJsonArray { add(buildJsonObject {
                    put("path", path); put("content", "same as current disk")
                }) } else JsonArray(root.getValue("dirty").jsonArray.map { item ->
                    JsonObject(item.jsonObject.filterKeys { it != "baseline" && (version != 4 || it != "max_bytes") })
                }))
            })
            f.manifest.writeText(legacy.toString())
            f.source.writeText("same as current disk")
            val loaded = checkNotNull(f.store.load())
            assertTrue("v$version must not infer a disk baseline", loaded.diskVersions.isEmpty())
            assertTrue(loaded.deferredBuffers.values.all { it.diskVersion == null })
            val state = restore(f, loaded)
            val doc = state.document(path)
            doc.capture(doc.content)
            assertTrue("v$version must retain dirty recovery even when text equals disk", doc.dirty)
            assertNull(doc.savedDiskVersion)
            assertTrue(doc.save(FileRepository(f.project)).isFailure)
            f.store.save(checkNotNull(state.recoverySnapshot(listOf(path), path)))
            val migrated = checkNotNull(f.store.load())
            assertNull(migrated.deferredBuffers.getValue(path).diskVersion)
            assertTrue(migrated.diskVersions.isEmpty())
            assertEquals("same as current disk", migrated.deferredBuffers.getValue(path).readText())
            assertEquals("same as current disk", f.source.readText())
        }
    }

    @Test(timeout = 15_000)
    fun equalNewDiskTextStaysDirtyAcrossCaptureAndDeferredRecheckpointUntilExplicitDiscard() = runBlocking {
        val f = fixture()
        val original = captured(f, original = "A", edited = "B")
        f.source.writeText("B")
        val state = restore(f, original)
        val doc = state.document(path)
        doc.capture("B")
        assertTrue(doc.captureInsert(0, "x")); assertTrue(doc.captureDelete(0, "x"))
        assertTrue(doc.dirty)
        assertEquals(DiskFileVersion.fromText("A"), doc.savedDiskVersion)
        f.store.save(checkNotNull(state.recoverySnapshot(listOf(path), path)))
        val restarted = restore(f, checkNotNull(f.store.load()))
        val recovered = restarted.document(path)
        assertTrue(recovered.dirty)
        val authority = EditorWorkspaceDocumentAuthority(restarted, Dispatchers.Unconfined, FileRepository(f.project))
        assertEquals(listOf(path), authority.reconcilePersistedWorkspace().conflicts)
        assertTrue(recovered.save(FileRepository(f.project)).isFailure)
        assertEquals("B", f.source.readText())
        recovered.discardUnsaved()
        assertFalse(recovered.dirty)
        assertEquals(DiskFileVersion.fromText("B"), recovered.savedDiskVersion)
        recovered.capture("explicit later edit")
        assertTrue(recovered.save(FileRepository(f.project)).isSuccess)
        assertEquals("explicit later edit", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun dirtyAndConflictTokensSurviveLazyRepeatedCheckpointWithoutBodyReads() = runBlocking {
        val f = fixture()
        val original = captured(f)
        val conflictVersion = DiskFileVersion.fromText("older original")
        f.store.save(original.copy(conflictBuffers = mapOf(path to "older recovery"),
            conflictDiskVersions = mapOf(path to conflictVersion)))
        val beforeLoad = f.cipher.reads
        val loaded = checkNotNull(f.store.load())
        assertEquals(beforeLoad + 1, f.cipher.reads)
        assertEquals(original.diskVersions.getValue(path), loaded.deferredBuffers.getValue(path).diskVersion)
        assertEquals(conflictVersion, loaded.deferredConflictBuffers.getValue(path).diskVersion)
        val dirtyId = loaded.deferredBuffers.getValue(path).blobName
        val conflictId = loaded.deferredConflictBuffers.getValue(path).blobName
        val state = EditorWorkspaceState()
        EditorTabRetention.restoreDocuments(loaded, FileRepository(f.project), state)
        val checkpoint = checkNotNull(state.recoverySnapshot(listOf(path), path))
        assertEquals(beforeLoad + 1, f.cipher.reads)
        f.store.save(checkpoint)
        assertEquals(beforeLoad + 2, f.cipher.reads)
        val again = checkNotNull(f.store.load())
        assertEquals(beforeLoad + 3, f.cipher.reads)
        assertEquals(dirtyId, again.deferredBuffers.getValue(path).blobName)
        assertEquals(conflictId, again.deferredConflictBuffers.getValue(path).blobName)
        assertEquals(original.diskVersions, again.diskVersions)
        assertEquals(mapOf(path to conflictVersion), again.conflictDiskVersions)
    }

    @Test(timeout = 15_000)
    fun identicalRecoveryTextWithDifferentOriginalVersionIsRetainedAsAConflict() = runBlocking {
        val f = fixture()
        val old = captured(f, original = "A", edited = "same unsaved text")
        f.source.writeText("B")
        val live = EditorWorkspaceState()
        live.document(path).apply { ensureLoaded(FileRepository(f.project)); capture("same unsaved text") }
        EditorTabRetention.restoreDocuments(old, FileRepository(f.project), live)
        val snapshot = checkNotNull(live.recoverySnapshot(listOf(path), path))
        assertEquals("same unsaved text", snapshot.dirtyBuffers[path])
        assertEquals("same unsaved text", snapshot.conflictBuffers[path])
        assertEquals(DiskFileVersion.fromText("B"), snapshot.diskVersions[path])
        assertEquals(DiskFileVersion.fromText("A"), snapshot.conflictDiskVersions[path])
        f.store.save(snapshot)
        val roundTrip = checkNotNull(f.store.load())
        assertEquals(snapshot.diskVersions, roundTrip.diskVersions)
        assertEquals(snapshot.conflictDiskVersions, roundTrip.conflictDiskVersions)
    }

    @Test(timeout = 15_000)
    fun knownAbsenceIsSerializedDistinctlyFromUnknown() = runBlocking {
        val f = fixture()
        f.store.save(captured(f, original = null))
        val loaded = checkNotNull(f.store.load())
        assertEquals(DiskFileVersion(false, 0L, ""), loaded.diskVersions[path])
        assertEquals(DiskFileVersion(false, 0L, ""), loaded.deferredBuffers.getValue(path).diskVersion)
        f.store.save(loaded.copy(conflictBuffers = mapOf("legacy.txt" to "unknown original")))
        val again = checkNotNull(f.store.load())
        assertEquals(DiskFileVersion(false, 0L, ""), again.deferredBuffers.getValue(path).diskVersion)
        assertNull(again.deferredConflictBuffers.getValue("legacy.txt").diskVersion)
        assertFalse("legacy.txt" in again.conflictDiskVersions)
    }

    @Test(timeout = 15_000)
    fun malformedVersionSixIdentityFailsClosedAndPreservesManifestAndBlobs() = runBlocking {
        val f = fixture()
        f.store.save(captured(f))
        val root = Json.parseToJsonElement(f.manifest.readText()).jsonObject
        val originalEntry = root.getValue("dirty").jsonArray.single().jsonObject
        val valid = originalEntry.getValue("baseline").jsonObject
        val malformed = listOf<JsonElement?>(null, JsonPrimitive("unknown"),
            JsonObject(valid.toMutableMap().apply { put("existed", JsonPrimitive("true")) }),
            JsonObject(valid.toMutableMap().apply { put("bytes", JsonPrimitive(-1)) }),
            JsonObject(valid.toMutableMap().apply { put("bytes", JsonPrimitive(EditorLargeFilePolicy.MAX_EDITABLE_BYTES + 1)) }),
            JsonObject(valid.toMutableMap().apply { put("sha256", JsonPrimitive("bad")) }),
            JsonObject(valid.toMutableMap().apply { put("existed", JsonPrimitive(false)) }))
        val blobs = f.blobs.listFiles()!!.associate { it.name to it.readBytes().toList() }
        for (bad in malformed) {
            val entry = JsonObject(originalEntry.toMutableMap().apply {
                if (bad == null) remove("baseline") else put("baseline", bad)
            })
            f.manifest.writeText(JsonObject(root.toMutableMap().apply { put("dirty", JsonArray(listOf(entry))) }).toString())
            val before = f.manifest.readBytes()
            assertTrue(runCatching { f.store.load() }.isFailure)
            assertTrue(runCatching { f.store.save(EditorRecoverySnapshot(emptyList(), "", emptyMap())) }.isFailure)
            assertArrayEquals(before, f.manifest.readBytes())
            assertEquals(blobs, f.blobs.listFiles()!!.associate { it.name to it.readBytes().toList() })
        }
    }

    @Test(timeout = 15_000)
    fun failedNativeCaptureLeavesThePreviousRecoveryCheckpointUntouched() = runBlocking {
        val f = fixture()
        f.store.save(captured(f, edited = "previous complete checkpoint"))
        val previous = f.manifest.readBytes()
        val state = EditorWorkspaceState()
        state.document(path).apply {
            restoreUnsaved("\n".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_LINES))
            assertTrue(captureInsert(0, "native pending"))
        }
        for (callback in listOf<(() -> Unit)?>(null, { error("fixture capture failed") })) {
            val bridge = ActiveEditorBridge().apply { snapshot = callback }
            assertTrue(runCatching {
                bridge.captureForSave()
                f.store.save(checkNotNull(state.recoverySnapshot(listOf(path), path)))
            }.isFailure)
            assertArrayEquals(previous, f.manifest.readBytes())
            assertTrue(state.document(path).dirty)
            assertTrue(state.document(path).requiresBufferCapture)
        }
        assertEquals("previous complete checkpoint", f.store.load()!!.deferredBuffers.getValue(path).readText())
    }

    @Test(timeout = 15_000)
    fun capturesAndSnapshotsReuseTheCachedDiskIdentity() = runBlocking {
        val f = fixture(); f.source.writeText("original")
        val state = EditorWorkspaceState(); val doc = state.document(path)
        doc.ensureLoaded(FileRepository(f.project))
        val accepted = checkNotNull(doc.savedDiskVersion)
        repeat(4) {
            doc.capture("edit $it")
            assertSame(accepted, doc.savedDiskVersion)
            assertSame(accepted, state.recoverySnapshot(listOf(path), path)!!.diskVersions[path])
        }
    }

    @Test(timeout = 15_000)
    fun committedCancelledSaveAdvancesIdentityWhileKeepingNewerTyping() = runBlocking {
        val f = fixture(); f.source.writeText("A")
        val doc = EditorDocument(path)
        var interrupt = true
        val files = FileRepository(f.project, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Write && interrupt) {
                interrupt = false; doc.capture("newer typing")
                throw CancellationException("fixture cancelled after publication")
            }
        })
        doc.ensureLoaded(files); doc.capture("committed text")
        assertTrue(runCatching { doc.save(files) }.exceptionOrNull() is CancellationException)
        assertEquals("committed text", f.source.readText())
        assertEquals(DiskFileVersion.fromText("committed text"), doc.savedDiskVersion)
        assertEquals("newer typing", doc.content); assertTrue(doc.dirty)
        assertTrue(doc.save(files).isSuccess)
        assertEquals("newer typing", f.source.readText())
    }

    @Test(timeout = 15_000)
    fun cleanRemapKeepsIdentityAndDirtyRemapIsRejectedWithoutDroppingIt() = runBlocking {
        val f = fixture(); f.source.writeText("A")
        val state = EditorWorkspaceState()
        val authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val files = FileRepository(f.project, beforeMutation = authority::validatePersistedMutation,
            onMutation = authority::persistedMutation)
        val doc = state.document(path); doc.ensureLoaded(files)
        val accepted = checkNotNull(doc.savedDiskVersion)
        files.move(path, "next.txt")
        assertSame(doc, state.document("next.txt")); assertSame(accepted, doc.savedDiskVersion)
        doc.capture("U")
        assertTrue(runCatching { files.move("next.txt", "blocked.txt") }.isFailure)
        assertEquals("next.txt", doc.path); assertSame(accepted, doc.savedDiskVersion)
        assertSame(accepted, state.recoverySnapshot(listOf("next.txt"), "next.txt")!!.diskVersions["next.txt"])
        assertEquals("A", File(f.project, "next.txt").readText())
        assertFalse(File(f.project, "blocked.txt").exists())
    }
}
