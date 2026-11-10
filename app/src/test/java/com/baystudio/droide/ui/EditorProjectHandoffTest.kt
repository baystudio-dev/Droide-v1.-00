package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Model/checkpoint wiring only. Identity cipher, no native editor/IME, socket or process. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorProjectHandoffTest {
    @get:Rule val temp = TemporaryFolder()
    private class Cipher : EditorRecoveryCipher {
        var encryptions = 0
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray { encryptions++; return plain.copyOf() }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray) = encrypted.copyOf()
    }
    private class Fixture(val source: File, val files: FileRepository, val state: EditorWorkspaceState,
        val store: EditorStateStore, val cipher: Cipher, val manifest: File)

    private suspend fun fixture(text: String = "saved", sync: ((File) -> Unit)? = null): Fixture {
        val app = temp.newFolder()
        val project = File(app, "projects/old").apply { check(mkdirs()) }
        val source = File(project, "keep.txt").apply { writeText(text) }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = app
        }
        val cipher = Cipher()
        val store = EditorStateStore(context, "old", cipher, sync ?: { directory ->
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        })
        val files = FileRepository(project)
        val state = EditorWorkspaceState()
        state.document("keep.txt").ensureLoaded(files)
        state.retainNavigation(listOf("keep.txt"), "keep.txt", 1)
        state.completeRecovery()
        return Fixture(source, files, state, store, cipher, File(app, ".droide/editor-recovery/old.bin"))
    }

    @Test(timeout = 15_000) fun cleanHandoffAwaitsOneCheckpointAndKeepsConflictEvidence() = runBlocking {
        val f = fixture()
        f.state.retainRecoveryConflict("old-copy.txt", "retained conflict", null)
        val admission = prepareEditorProjectHandoff(f.state, f.store)
        assertTrue(f.manifest.isFile)
        val checkpoint = checkNotNull(f.store.load())
        assertEquals(listOf("keep.txt"), checkpoint.openFiles)
        assertEquals("retained conflict", checkpoint.deferredConflictBuffers.getValue("old-copy.txt").readText())
        val encryptions = f.cipher.encryptions
        admission()
        admission()
        assertEquals("Final admission must not rewrite recovery", encryptions, f.cipher.encryptions)
        assertEquals("saved", f.source.readText())
    }

    @Test(timeout = 15_000) fun dirtyAdmissionKeepsPreviousCheckpointWithoutWritingSource() = runBlocking {
        val f = fixture()
        prepareEditorProjectHandoff(f.state, f.store)
        val before = f.manifest.readBytes()
        val encryptions = f.cipher.encryptions
        f.state.document("keep.txt").capture("newer typing")
        assertTrue(runCatching { prepareEditorProjectHandoff(f.state, f.store) }.isFailure)
        assertArrayEquals(before, f.manifest.readBytes())
        assertEquals(encryptions, f.cipher.encryptions)
        assertEquals("newer typing", f.state.document("keep.txt").content)
        assertEquals("saved", f.source.readText())
    }

    @Test(timeout = 15_000) fun lateEditDuringCheckpointIoRefusesFinalAdmissionWithoutRetry() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val once = AtomicBoolean(false)
        val f = fixture(sync = {
            if (once.compareAndSet(false, true)) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)) { "fixture checkpoint gate timed out" }
            }
        })
        val preparing = async { runCatching { prepareEditorProjectHandoff(f.state, f.store) } }
        try {
            withTimeout(5_000) { entered.await() }
            f.state.document("keep.txt").capture("typed during checkpoint IO")
        } finally { release.countDown() }
        val outcome = preparing.await()
        assertTrue(outcome.isFailure)
        assertTrue(f.state.document("keep.txt").dirty)
        assertEquals("typed during checkpoint IO", f.state.document("keep.txt").content)
        assertEquals(1, f.cipher.encryptions)
        assertEquals("saved", f.source.readText())
    }

    @Test(timeout = 15_000) fun checkpointFailureDoesNotReturnASuccessShapedAdmission() = runBlocking {
        val f = fixture(sync = { throw IOException("checkpoint refused") })
        val failure = runCatching { prepareEditorProjectHandoff(f.state, f.store) }.exceptionOrNull()
        assertEquals("checkpoint refused", failure?.message)
        assertFalse(f.manifest.exists())
        assertEquals("saved", f.source.readText())
    }

    @Test(timeout = 15_000) fun finalAdmissionCapturesLatestOwnerAndRejectsNewTyping() = runBlocking {
        val f = fixture()
        var late = false
        val binding = f.state.bindProjectHandoffCapture {
            if (late) f.state.document("keep.txt").capture("captured late text")
        }
        try {
            val admission = prepareEditorProjectHandoff(f.state, f.store)
            late = true
            assertTrue(runCatching(admission).isFailure)
            assertEquals("captured late text", f.state.document("keep.txt").content)
            assertTrue(f.state.document("keep.txt").dirty)
        } finally { binding.close() }
    }

    @Test(timeout = 15_000) fun missingNativeCaptureCannotAdmitAnIncompleteLargeModel() = runBlocking {
        val f = fixture("x".repeat(EditorLargeFilePolicy.FULL_INTELLIGENCE_MAX_BYTES.toInt() + 1))
        val doc = f.state.document("keep.txt")
        assertTrue(doc.largeFileOptimized)
        doc.captureInsert(0, "new")
        assertTrue(doc.requiresBufferCapture)
        assertTrue(runCatching { prepareEditorProjectHandoff(f.state, null) }.isFailure)
        assertEquals(0, f.cipher.encryptions)
    }

    @Test(timeout = 15_000) fun oldCaptureDisposalAndOldAuthorityAdmissionCannotOwnReplacement() = runBlocking {
        val f = fixture()
        var oldCaptures = 0
        var newCaptures = 0
        val oldCapture = f.state.bindProjectHandoffCapture { oldCaptures++ }
        val newCapture = f.state.bindProjectHandoffCapture { newCaptures++ }
        oldCapture.close()
        f.state.captureForProjectHandoff()
        assertEquals(0, oldCaptures)
        assertEquals(1, newCaptures)
        val bridge = WorkspaceDocumentAuthorityBridge()
        val old = bridge.bind(EditorWorkspaceDocumentAuthority(f.state, Dispatchers.Unconfined))
        val admission = bridge.prepareProjectHandoff()
        val replacement = bridge.bind(EditorWorkspaceDocumentAuthority(EditorWorkspaceState(), Dispatchers.Unconfined))
        try {
            old.close()
            assertTrue(bridge.isBound)
            assertTrue(runCatching(admission).isFailure)
        } finally { replacement.close(); newCapture.close() }
    }
}
