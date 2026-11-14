package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real model/store + host AES; native lifecycle is a controlled binding, not a real Android IME. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorExitCheckpointR64Test {
    @get:Rule val temp = TemporaryFolder()

    private class HostCipher : EditorRecoveryCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        var failWrite = false
        var failRead = false
        var beforeWrite: (() -> Unit)? = null
        var writes = 0
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            writes++
            beforeWrite?.invoke()
            if (failWrite) throw IOException("fixture write refused")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            if (failRead) throw IOException("fixture readback refused")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private data class Fixture(
        val project: File, val source: File, val state: EditorWorkspaceState,
        val store: EditorStateStore, val cipher: HostCipher, val manifest: File,
    ) {
        val doc get() = state.document("draft.txt")
        suspend fun recoveredText(path: String = "draft.txt") = store.load()!!.deferredBuffers.getValue(path).readText()
    }

    private suspend fun fixture(original: String? = "disk original", initialized: Boolean = true): Fixture {
        val app = temp.newFolder()
        val project = File(app, "project").apply { check(mkdir()) }
        val source = File(project, "draft.txt")
        if (original != null) source.writeText(original)
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = app
        }
        val cipher = HostCipher()
        val store = EditorStateStore(context, "project", cipher) { /* Host does not emulate Android directory fsync. */ }
        val state = EditorWorkspaceState()
        state.document("draft.txt").ensureLoaded(FileRepository(project))
        state.retainNavigation(listOf("draft.txt"), "draft.txt", 1)
        if (initialized) state.completeRecovery()
        return Fixture(project, source, state, store, cipher, File(app, ".droide/editor-recovery/project.bin"))
    }

    private class Native(private val doc: EditorDocument) {
        val owner = Any()
        var composingText: String? = null
        var batchPending = false
        var leased = false
        var committed = false
        var released = false
        private var onReleased: ((Throwable?) -> Unit)? = null
        val binding = EditorNativeHandoffBinding(owner, begin = { callback ->
            composingText?.let { doc.captureNativeText(it); composingText = null }
            check(!batchPending) { "Native batch edit is still pending" }
            leased = true
            onReleased = callback
            object : EditorNativeHandoffLease {
                override fun commit() { committed = true }
                override fun abort() { if (!committed) { leased = false; onReleased = null } }
            }
        }, requireReady = { check(!released) { "Native editor released" } })
        fun release(text: String? = null, failure: Throwable? = null) {
            check(committed)
            text?.let(doc::captureNativeText)
            released = true
            onReleased?.invoke(failure)
        }
    }

    @Test(timeout = 15_000) fun cleanUninitializedEditorKeepsUnreadableBackupByteForByteAndExits() = runBlocking {
        val f = fixture(initialized = false)
        f.store.save(EditorRecoverySnapshot(listOf("older.txt"), "older.txt", mapOf("older.txt" to "older unsaved work")))
        val storage = checkNotNull(f.manifest.parentFile)
        fun storedBytes() = storage.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(storage).path to it.readBytes().toList() }
        val before = storedBytes(); val writes = f.cipher.writes
        f.cipher.failRead = true; f.cipher.failWrite = true
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertTrue(checkpoint.preservationComplete)
        assertFalse(f.state.recoveryInitialized)
        assertEquals(before, storedBytes()); assertEquals(writes, f.cipher.writes)
        assertEquals("disk original", f.source.readText())
    }

    @Test(timeout = 15_000) fun cleanUninitializedEditorDoesNotCreateRecoveryJustToExit() = runBlocking {
        val f = fixture(initialized = false)
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertTrue(checkpoint.preservationComplete); assertFalse(f.manifest.exists()); assertEquals(0, f.cipher.writes)
    }

    @Test(timeout = 15_000) fun dirtyUninitializedEditorMergesOldCopiesAndFinalNativeDelta() = runBlocking {
        val f = fixture(initialized = false)
        val baseline = checkNotNull(f.doc.savedDiskVersion)
        f.store.save(EditorRecoverySnapshot(listOf("draft.txt", "older.txt"), "older.txt",
            mapOf("draft.txt" to "older version", "older.txt" to "unopened draft"),
            conflictBuffers = mapOf("conflict.txt" to "existing conflict"),
            diskVersions = mapOf("draft.txt" to baseline)))
        val before = f.store.load()!!
        f.doc.capture("current draft")
        val native = Native(f.doc); f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease()
        native.release("final IME delta"); checkpoint.awaitReleaseAndCheckpoint()
        val after = f.store.load()!!
        assertEquals("final IME delta", f.recoveredText())
        assertEquals("unopened draft", after.deferredBuffers.getValue("older.txt").readText())
        assertEquals("older version", after.deferredConflictBuffers.getValue("draft.txt").readText())
        assertEquals("existing conflict", after.deferredConflictBuffers.getValue("conflict.txt").readText())
        assertEquals(before.deferredBuffers.getValue("draft.txt").blobName, after.deferredConflictBuffers.getValue("draft.txt").blobName)
        assertEquals(baseline, after.conflictDiskVersions.getValue("draft.txt"))
        assertEquals("disk original", f.source.readText()); assertTrue(checkpoint.preservationComplete)
    }

    @Test(timeout = 15_000) fun nativeDeltaAfterCleanUninitializedPreparationIsNotSkipped() = runBlocking {
        val f = fixture(initialized = false)
        f.store.save(EditorRecoverySnapshot(listOf("older.txt"), "older.txt", mapOf("older.txt" to "kept older draft")))
        val native = Native(f.doc); f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease()
        native.release("last native edit"); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("last native edit", f.recoveredText())
        assertEquals("kept older draft", f.recoveredText("older.txt")); assertTrue(checkpoint.preservationComplete)
    }

    @Test(timeout = 15_000) fun uninitializedDirtyEditorCannotReplaceUnreadableOlderRecovery() = runBlocking {
        val f = fixture(initialized = false)
        f.store.save(EditorRecoverySnapshot(listOf("older.txt"), "older.txt", mapOf("older.txt" to "old draft")))
        val original = f.manifest.readBytes()
        f.doc.capture("new draft"); f.cipher.failRead = true
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        assertArrayEquals(original, f.manifest.readBytes()); assertEquals("new draft", f.doc.content)
        f.cipher.failRead = false
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("new draft", f.recoveredText()); assertEquals("old draft", f.recoveredText("older.txt"))
    }

    @Test(timeout = 15_000) fun partiallyRestoredSameDraftCanAdvanceWithoutInventingAConflict() = runBlocking {
        val f = fixture(initialized = false); f.doc.capture("already restored")
        f.store.save(checkNotNull(f.state.recoverySnapshot(listOf("draft.txt"), "draft.txt")))
        val native = Native(f.doc); f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease()
        native.release("new final text"); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("new final text", f.recoveredText()); assertTrue(f.store.load()!!.conflictPaths.isEmpty())
    }

    @Test(timeout = 15_000) fun mergedPublicationReadbackFailureCanRetryWithANewerNativeSnapshot() = runBlocking {
        val f = fixture(initialized = false)
        f.store.save(EditorRecoverySnapshot(listOf("draft.txt"), "draft.txt", mapOf("draft.txt" to "older copy")))
        f.doc.capture("initial live draft")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        f.cipher.beforeWrite = { f.cipher.failRead = true }
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        f.cipher.beforeWrite = null; f.cipher.failRead = false
        f.doc.captureNativeText("newer native draft")
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("newer native draft", f.recoveredText())
        assertEquals("older copy", f.store.load()!!.deferredConflictBuffers.getValue("draft.txt").readText())
    }

    @Test(timeout = 15_000) fun threeDifferentCopiesNeverSilentlyDropAnOlderConflict() = runBlocking {
        val f = fixture(initialized = false)
        f.store.save(EditorRecoverySnapshot(listOf("draft.txt"), "draft.txt", mapOf("draft.txt" to "older draft"),
            conflictBuffers = mapOf("draft.txt" to "oldest conflict")))
        val before = f.manifest.readBytes(); f.doc.capture("new draft")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        assertTrue(runCatching { checkpoint.saveAndVerify() }.exceptionOrNull()?.message.orEmpty().contains("Another recovery copy"))
        assertArrayEquals(before, f.manifest.readBytes()); assertEquals("new draft", f.doc.content)
        assertFalse(checkpoint.releaseCommitted); assertTrue(checkpoint.abortBeforeRelease())
    }

    @Test(timeout = 15_000) fun pendingStoreWriteDrainsWithoutRetryAndItsBackupIsMerged() = runBlocking {
        val f = fixture(initialized = false); f.doc.capture("current dirty text")
        val entered = CompletableDeferred<Unit>(); val resume = CountDownLatch(1)
        f.cipher.beforeWrite = { entered.complete(Unit); check(resume.await(5, TimeUnit.SECONDS)) }
        val saving = async { f.store.save(EditorRecoverySnapshot(emptyList(), "", mapOf("older.txt" to "in-flight old draft"))) }
        try {
            withTimeout(3_000) { entered.await() }
            val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
            val preserving = async { checkpoint.saveAndVerify() }
            yield(); assertFalse(preserving.isCompleted); assertFalse(f.doc.canEdit)
            resume.countDown(); saving.await(); preserving.await()
            checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
            assertEquals("current dirty text", f.recoveredText()); assertEquals("in-flight old draft", f.recoveredText("older.txt"))
        } finally { resume.countDown(); saving.join() }
    }

    @Test(timeout = 15_000) fun recoveryAdmissionCannotRestoreOverFrozenExitModel() = runBlocking {
        val f = fixture(initialized = false)
        val admission = f.state.beginRecovery()
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        assertFalse(f.state.isRecoveryCurrent(admission)); assertFalse(f.state.canRestoreDocument(admission, "draft.txt"))
        checkpoint.abortBeforeRelease()
        assertTrue(f.state.isRecoveryCurrent(admission))
    }

    @Test(timeout = 15_000) fun unchangedFinalCaptureReusesVerifiedCheckpointWithoutMoreEncryption() = runBlocking {
        val f = fixture(); f.doc.capture("one durable checkpoint")
        val native = Native(f.doc); f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); val before = f.cipher.writes
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); native.release()
        checkpoint.awaitReleaseAndCheckpoint(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals(before, f.cipher.writes); assertTrue(checkpoint.preservationComplete)
    }

    @Test(timeout = 15_000)
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun sealedNativeEditorCanReleaseAndPreserveItsFinalDeltaWithoutACompositionFrame() = runBlocking {
        val f = fixture()
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        try {
            editor.setText("native draft")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!editor.isEditable && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue("Native layout must be ready", editor.isEditable)
            editor.nativeCapture = { f.doc.captureNativeText(editor.text.toString()) }
            val native = EditorNativeHandoffBinding(editor, editor::beginProjectHandoff,
                requireReady = { check(!editor.isReleased) })
            f.state.bindProjectHandoffCapture({ editor.nativeCapture?.invoke() }, { native })
            val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
            checkpoint.saveAndVerify()
            editor.subscribeEvent(io.github.rosemoe.sora.event.EditorReleaseEvent::class.java) { _, _ ->
                editor.text.insert(0, 0, "final ")
            }
            checkpoint.commitForRelease()
            val exactOwner = checkpoint.nativeOwnerToRelease as DroideCodeEditor
            assertSame(editor, exactOwner)
            exactOwner.release()
            assertTrue(editor.isReleased)
            assertTrue(checkpoint.releaseObserved)
            checkpoint.awaitReleaseAndCheckpoint()
            assertTrue(checkpoint.preservationComplete)
            assertEquals("final native draft", f.recoveredText())
            assertEquals("disk original", f.source.readText())
        } finally { editor.release() }
    }

    @Test(timeout = 15_000) fun dirtyDraftIsEncryptedVerifiedAndNeverWrittenToProject() = runBlocking {
        val f = fixture()
        f.doc.capture("private unsaved draft")
        val baseline = f.doc.savedDiskVersion
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        assertFalse(f.doc.canEdit)
        checkpoint.saveAndVerify()
        checkpoint.saveAndVerify()
        assertEquals("private unsaved draft", f.recoveredText())
        assertEquals(baseline, f.store.load()!!.diskVersions["draft.txt"])
        assertFalse(f.manifest.readBytes().toString(Charsets.ISO_8859_1).contains("private unsaved draft"))
        assertEquals("disk original", f.source.readText())
        checkpoint.commitForRelease()
        checkpoint.commitForRelease()
        checkpoint.awaitReleaseAndCheckpoint()
        checkpoint.awaitReleaseAndCheckpoint()
        assertTrue(checkpoint.preservationComplete)
        assertFalse(checkpoint.abortBeforeRelease())
        assertTrue(f.doc.dirty)
        assertTrue(runCatching { f.store.save(EditorRecoverySnapshot(emptyList(), "", emptyMap())) }.isFailure)
    }

    @Test(timeout = 15_000) fun failedInitialWriteRetainsReversibleLeaseAndExplicitRetry() = runBlocking {
        val f = fixture(); f.doc.capture("kept draft")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        f.cipher.failWrite = true
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertFalse(checkpoint.releaseCommitted)
        assertFalse(f.doc.canEdit)
        assertEquals("kept draft", f.doc.content)
        f.cipher.failWrite = false
        checkpoint.saveAndVerify()
        assertTrue(checkpoint.abortBeforeRelease())
        assertTrue(checkpoint.abortBeforeRelease())
        assertTrue(f.doc.canEdit)
        f.doc.capture("new edit after cancelled Exit")
        f.store.save(checkNotNull(f.state.recoverySnapshot(listOf("draft.txt"), "draft.txt")))
        assertEquals("new edit after cancelled Exit", f.recoveredText())
        assertEquals("disk original", f.source.readText())
    }

    @Test(timeout = 15_000) fun nativeCompositionCapturedAndPendingBatchRollsBackPreparation() = runBlocking {
        val f = fixture()
        val native = Native(f.doc).apply { composingText = "last composition"; batchPending = true }
        f.state.bindProjectHandoffCapture({}, { native.binding })
        assertTrue(runCatching { prepareEditorExitCheckpoint(f.state, f.store) }.isFailure)
        assertTrue(f.doc.canEdit)
        assertEquals("last composition", f.doc.content)
        assertFalse(native.leased)
        val freshToken = checkNotNull(f.store.tryRetireWrites())
        assertTrue(f.store.abortRetirement(freshToken))
        native.batchPending = false
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify()
        assertEquals("last composition", f.recoveredText())
        checkpoint.abortBeforeRelease()
        assertFalse(native.leased)
    }

    @Test(timeout = 15_000) fun synchronousRetirementRefusesActiveSaveWithoutHidingPublication() = runBlocking {
        val f = fixture(); f.doc.capture("already admitted Save")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val files = FileRepository(f.project, onMutation = { entered.complete(Unit); resume.await() })
        val saving = async { f.doc.save(files) }
        try {
            withTimeout(5_000) { entered.await() }
            assertTrue(runCatching { prepareEditorExitCheckpointNow(f.state, f.store) }.isFailure)
            assertTrue(f.doc.canEdit)
        } finally { resume.complete(Unit) }
        assertTrue(saving.await().isSuccess)
        assertEquals("already admitted Save", f.source.readText())
    }

    @Test(timeout = 15_000) fun exitAwaitsAcceptedSaveThenPreservesItsPublishedBaseline() = runBlocking {
        val f = fixture(); f.doc.capture("already admitted Save")
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val files = FileRepository(f.project, onMutation = { entered.complete(Unit); resume.await() })
        val saving = async { f.doc.save(files) }
        try {
            withTimeout(5_000) { entered.await() }
            val preparing = async { prepareEditorExitCheckpoint(f.state, f.store) }
            yield(); assertFalse(preparing.isCompleted); assertTrue(f.doc.canEdit)
            resume.complete(Unit); assertTrue(saving.await().isSuccess)
            val checkpoint = preparing.await()
            checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
            assertTrue(checkpoint.preservationComplete); assertEquals("already admitted Save", f.source.readText())
            assertTrue(f.store.load()!!.dirtyPaths.isEmpty())
        } finally { resume.complete(Unit); saving.join() }
    }

    @Test(timeout = 15_000) fun releaseDeltaMustBeCheckpointedAndFailureCanRetryWithoutReleasingAgain() = runBlocking {
        val f = fixture(); f.doc.capture("initial frozen draft")
        val native = Native(f.doc)
        f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify()
        checkpoint.commitForRelease()
        native.release("release callback delivered final text")
        f.cipher.failWrite = true
        assertTrue(runCatching { checkpoint.awaitReleaseAndCheckpoint() }.isFailure)
        assertFalse(checkpoint.preservationComplete)
        assertFalse(checkpoint.abortBeforeRelease())
        assertEquals("release callback delivered final text", f.doc.content)
        f.cipher.failWrite = false
        checkpoint.retryFinalCheckpoint()
        assertTrue(checkpoint.preservationComplete)
        assertEquals("release callback delivered final text", f.recoveredText())
        assertEquals("disk original", f.source.readText())
    }

    @Test(timeout = 15_000) fun releaseFailureNeverBecomesSuccessFromAnOlderCheckpoint() = runBlocking {
        val f = fixture(); f.doc.capture("retained draft")
        val native = Native(f.doc)
        f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease()
        native.release(failure = IOException("native capture failed"))
        assertTrue(runCatching { checkpoint.awaitReleaseAndCheckpoint() }.isFailure)
        assertTrue(runCatching { checkpoint.retryFinalCheckpoint() }.isFailure)
        assertFalse(checkpoint.preservationComplete)
        assertTrue(checkpoint.finalCaptureFailed)
        assertEquals("retained draft", f.doc.content)
        assertEquals("retained draft", f.recoveredText())
    }

    @Test(timeout = 15_000) fun changedFrozenNativeTextRequiresNewVerifiedCheckpoint() = runBlocking {
        val f = fixture(); f.doc.capture("first draft")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify()
        f.doc.captureNativeText("late native text")
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        assertFalse(checkpoint.releaseCommitted)
        checkpoint.saveAndVerify()
        checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("late native text", f.recoveredText())
    }

    @Test(timeout = 15_000) fun readbackFailurePreventsReleaseAndKeepsLeaseRetryable() = runBlocking {
        val f = fixture(); f.doc.capture("readback must see this")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        f.cipher.failRead = true
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        f.cipher.failRead = false
        checkpoint.saveAndVerify(); checkpoint.abortBeforeRelease()
        assertEquals("readback must see this", f.recoveredText())
    }

    @Test(timeout = 15_000) fun emptyKnownAbsentFileAndConflictCopySurviveWithoutCreation() = runBlocking {
        val f = fixture(original = null)
        assertFalse(f.doc.dirty)
        assertTrue(f.doc.persistenceRequired)
        f.state.retainRecoveryConflict("older.txt", "older retained draft", null)
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        val recovered = f.store.load()!!
        assertTrue("draft.txt" in recovered.dirtyPaths)
        assertEquals("", recovered.deferredBuffers.getValue("draft.txt").readText())
        assertEquals(false, recovered.diskVersions.getValue("draft.txt").existed)
        assertEquals("older retained draft", recovered.deferredConflictBuffers.getValue("older.txt").readText())
        assertFalse(f.source.exists())
    }

    @Test(timeout = 15_000) fun restoreKeepsOriginalBaselineAndDoesNotOverwriteExternalEdit() = runBlocking {
        val f = fixture(); f.doc.capture("unsaved work")
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        f.source.writeText("external editor changed disk")
        val restored = EditorWorkspaceState()
        EditorTabRetention.restoreDocuments(f.store.load()!!, FileRepository(f.project), restored)
        val document = restored.document("draft.txt")
        document.ensureLoaded(FileRepository(f.project))
        assertEquals("unsaved work", document.content)
        assertTrue(document.save(FileRepository(f.project)).isFailure)
        assertEquals("external editor changed disk", f.source.readText())
    }

    @Test(timeout = 15_000) fun deferredDraftAndConflictReadBackWithoutChangingBlobOwnership() = runBlocking {
        val f = fixture(); f.doc.capture("deferred unsaved work")
        f.state.retainRecoveryConflict("older.txt", "older conflict", null)
        f.store.save(checkNotNull(f.state.recoverySnapshot(listOf("draft.txt"), "draft.txt")))
        val before = f.store.load()!!
        val restored = EditorWorkspaceState()
        EditorTabRetention.restoreDocuments(before, FileRepository(f.project), restored)
        restored.retainNavigation(listOf("draft.txt"), "draft.txt", 1)
        restored.completeRecovery()
        assertNotNull(restored.document("draft.txt").pendingRecoveryBuffer)
        val checkpoint = prepareEditorExitCheckpoint(restored, f.store)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        val after = f.store.load()!!
        assertEquals(before.deferredBuffers.getValue("draft.txt").blobName, after.deferredBuffers.getValue("draft.txt").blobName)
        assertEquals(before.deferredConflictBuffers.getValue("older.txt").blobName, after.deferredConflictBuffers.getValue("older.txt").blobName)
        assertEquals("deferred unsaved work", after.deferredBuffers.getValue("draft.txt").readText())
        assertEquals("older conflict", after.deferredConflictBuffers.getValue("older.txt").readText())
        assertEquals(before.diskVersions, after.diskVersions)
        assertEquals("disk original", f.source.readText())
    }

    @Test(timeout = 15_000) fun recreationBeforeInitialSaveAwaitsExactReleaseAndPreservesItsDelta() = runBlocking {
        val f = fixture(); f.doc.capture("before Activity loss")
        val native = Native(f.doc)
        val binding = f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpointNow(f.state, f.store)
        checkpoint.allowNativeReleaseForRecreation()
        checkpoint.allowNativeReleaseForRecreation()
        assertFalse(checkpoint.abortBeforeRelease())
        binding.close()
        val preserving = async(start = CoroutineStart.UNDISPATCHED) { checkpoint.saveAndVerify() }
        assertTrue(checkpoint.recreationReleasePending)
        assertFalse(preserving.isCompleted)
        native.release("final text from original destroyed pane")
        preserving.await()
        assertFalse(checkpoint.releaseCommitted)
        assertEquals("final text from original destroyed pane", f.recoveredText())
        checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        checkpoint.allowNativeReleaseForRecreation() // Closed recreation cannot reopen anything.
        assertTrue(checkpoint.preservationComplete)
        assertEquals("disk original", f.source.readText())
    }

    @Test(timeout = 15_000) fun recreationAfterFailedWriteRetriesFromExactReleasedModelWithoutOldBridge() = runBlocking {
        val f = fixture(); f.doc.capture("before failed preservation")
        val native = Native(f.doc)
        var disposed = false
        val binding = f.state.bindProjectHandoffCapture({ check(!disposed) { "disposed bridge invoked" } }, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        f.cipher.failWrite = true
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        checkpoint.allowNativeReleaseForRecreation()
        disposed = true; binding.close()
        native.release("newest release-time text")
        f.cipher.failWrite = false
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("newest release-time text", f.recoveredText())
        assertTrue(checkpoint.preservationComplete)
    }

    @Test(timeout = 15_000) fun recreationDuringIoCannotValidateAnEarlierSnapshotAsFinal() = runBlocking {
        val f = fixture(); f.doc.capture("snapshot before IO")
        val native = Native(f.doc)
        val binding = f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        f.cipher.beforeWrite = { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        val preserving = async(start = CoroutineStart.UNDISPATCHED) { runCatching { checkpoint.saveAndVerify() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            checkpoint.allowNativeReleaseForRecreation()
            binding.close(); native.release("newer text during IO")
        } finally { resume.countDown(); f.cipher.beforeWrite = null }
        assertTrue(preserving.await().isFailure)
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("newer text during IO", f.recoveredText())
    }

    @Test(timeout = 15_000) fun recreationCaptureFailureKeepsEarlierRecoveryAndCannotBeAcknowledged() = runBlocking {
        val f = fixture(); f.doc.capture("verified earlier draft")
        val native = Native(f.doc)
        val binding = f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify()
        checkpoint.allowNativeReleaseForRecreation()
        binding.close(); native.release(failure = IOException("final native capture failed"))
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        assertTrue(checkpoint.finalCaptureFailed)
        assertFalse(checkpoint.abortBeforeRelease())
        assertEquals("verified earlier draft", f.recoveredText())
    }

    @Test(timeout = 15_000) fun recreationAfterVerifiedInitialSaveRequiresNewReleaseDeltaCheckpoint() = runBlocking {
        val f = fixture(); f.doc.capture("verified initial snapshot")
        val native = Native(f.doc)
        val binding = f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.allowNativeReleaseForRecreation()
        binding.close(); native.release("final text after initial verification")
        assertTrue(runCatching { checkpoint.commitForRelease() }.isFailure)
        assertFalse(checkpoint.releaseCommitted)
        checkpoint.saveAndVerify(); checkpoint.commitForRelease(); checkpoint.awaitReleaseAndCheckpoint()
        assertEquals("final text after initial verification", f.recoveredText())
    }

    @Test(timeout = 15_000) fun lateModelBytesCannotImpersonateSuccessfulOriginalReleaseReceipt() = runBlocking {
        val f = fixture(); f.doc.capture("original snapshot")
        val native = Native(f.doc)
        val binding = f.state.bindProjectHandoffCapture({}, { native.binding })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.allowNativeReleaseForRecreation()
        binding.close(); native.release("original release receipt text")
        f.doc.captureNativeText("unattributed bytes after native receipt")
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertTrue(checkpoint.finalCaptureFailed)
        assertFalse(checkpoint.preservationComplete)
        assertEquals("original snapshot", f.recoveredText())
    }

    @Test(timeout = 15_000) fun replacementNativeOwnerCannotSubstituteForOriginalRecreationReceipt() = runBlocking {
        val f = fixture(); f.doc.capture("original draft")
        val original = Native(f.doc); val replacement = Native(f.doc)
        var current = original.binding
        f.state.bindProjectHandoffCapture({}, { current })
        val checkpoint = prepareEditorExitCheckpoint(f.state, f.store)
        checkpoint.saveAndVerify(); checkpoint.allowNativeReleaseForRecreation()
        current = replacement.binding
        original.release("original final text")
        assertTrue(runCatching { checkpoint.saveAndVerify() }.isFailure)
        assertTrue(checkpoint.finalCaptureFailed)
        assertFalse(checkpoint.preservationComplete)
        assertEquals("original draft", f.recoveredText())
    }
}
