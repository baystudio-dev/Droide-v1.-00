package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.baystudio.droide.core.EditorRecoveryCipher
import com.baystudio.droide.core.EditorRecoverySnapshot
import com.baystudio.droide.core.EditorStateStore
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.WorkspaceDocumentAuthorityBridge
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * PROPOSED, UNEXECUTED baseline regression for the R28 source audit.
 * This file is deliberately outside production/test source roots.
 * Uses the real editor, repository, recovery serializer and authority bridge.
 * The identity cipher only isolates serialization; it does not test encryption.
 * No native editor, subprocess, shell, network, provider or Android build is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorRecoveredDiskBaselineR29Test {
    @get:Rule val temp = TemporaryFolder()

    private enum class Mode { EAGER_SNAPSHOT, DEFERRED_STORE }

    @Test(timeout = 15_000) fun eagerRecoveryCannotOverwriteNewerDisk() = scenario(Mode.EAGER_SNAPSHOT, "base A", "newer B", false)
    @Test(timeout = 15_000) fun deferredRecoveryCannotOverwriteNewerDisk() = scenario(Mode.DEFERRED_STORE, "base A", "newer B", false)

    @Test(timeout = 15_000) fun eagerUnchangedDiskControlCanSave() = scenario(Mode.EAGER_SNAPSHOT, "base A", "base A", true)
    @Test(timeout = 15_000) fun deferredUnchangedDiskControlCanSave() = scenario(Mode.DEFERRED_STORE, "base A", "base A", true)

    @Test(timeout = 15_000) fun eagerRecoveryCannotRecreateExternallyDeletedOriginal() = scenario(Mode.EAGER_SNAPSHOT, "base A", null, false)
    @Test(timeout = 15_000) fun deferredRecoveryCannotRecreateExternallyDeletedOriginal() = scenario(Mode.DEFERRED_STORE, "base A", null, false)

    @Test(timeout = 15_000) fun eagerNewFileRecoveryCannotOverwriteNewlyCreatedDiskFile() = scenario(Mode.EAGER_SNAPSHOT, null, "newer B", false)
    @Test(timeout = 15_000) fun deferredNewFileRecoveryCannotOverwriteNewlyCreatedDiskFile() = scenario(Mode.DEFERRED_STORE, null, "newer B", false)

    @Test(timeout = 15_000) fun eagerStillAbsentNewFileControlCanSave() = scenario(Mode.EAGER_SNAPSHOT, null, null, true)
    @Test(timeout = 15_000) fun deferredStillAbsentNewFileControlCanSave() = scenario(Mode.DEFERRED_STORE, null, null, true)

    private fun scenario(mode: Mode, initialDisk: String?, restartDisk: String?, shouldSave: Boolean) = runBlocking {
        val app = temp.newFolder("app")
        val project = File(app, "projects/project").apply { check(mkdirs()) }
        val path = "main.txt"
        val source = File(project, path)
        initialDisk?.let { source.writeText(it) }
        val recoveredText = "user edit based on ${initialDisk ?: "a new file"}"

        val bridge = WorkspaceDocumentAuthorityBridge()
        val files = FileRepository(project,
            beforeMutation = bridge::validatePersistedMutation,
            onMutation = bridge::persistedMutation)
        val oldState = EditorWorkspaceState()
        bridge.bind(EditorWorkspaceDocumentAuthority(oldState, Dispatchers.Unconfined, files))
        oldState.document(path).apply { ensureLoaded(files); capture(recoveredText) }
        val captured = checkNotNull(oldState.recoverySnapshot(listOf(path), path))

        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = app
            override fun getApplicationContext(): Context = this
        }
        val cipher = object : EditorRecoveryCipher {
            override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = plain.copyOf()
            override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray = encrypted.copyOf()
        }
        val store = EditorStateStore(context, "project", cipher) { directory ->
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
        val snapshot: EditorRecoverySnapshot = when (mode) {
            Mode.EAGER_SNAPSHOT -> captured
            Mode.DEFERRED_STORE -> {
                store.save(captured)
                checkNotNull(store.load()).also {
                    assertTrue(it.dirtyBuffers.isEmpty())
                    assertTrue(path in it.deferredBuffers)
                }
            }
        }

        // A terminal, Git tool, or other workspace actor changes disk after the checkpoint.
        if (restartDisk == null) {
            if (source.exists()) check(source.delete())
        } else source.writeText(restartDisk)

        // A new editor owner models process restart; no old live baseline is retained.
        val newState = EditorWorkspaceState()
        bridge.bind(EditorWorkspaceDocumentAuthority(newState, Dispatchers.Unconfined, files))
        EditorTabRetention.restoreDocuments(snapshot, files, newState)
        val recovered = newState.document(path)
        recovered.ensureLoaded(files)
        assertEquals(recoveredText, recovered.content)
        assertTrue(recovered.dirty)

        // Exercise the independent external-reconciliation guard as in the real runtime.
        bridge.reconcilePersistedWorkspace()
        assertEquals(recoveredText, recovered.content)
        val result = EditorSaveCoordinator(newState, files, ActiveEditorBridge()).saveDocument(recovered)
        if (shouldSave) {
            assertTrue("Unchanged original baseline should permit Save: $result", result.ready)
            assertEquals(recoveredText, source.readText())
        } else {
            assertFalse("Recovered text must not adopt the current disk as its old baseline", result.ready)
            assertTrue("Unsaved recovery must remain available", recovered.dirty)
            assertEquals(recoveredText, recovered.content)
            if (restartDisk == null) assertFalse("External deletion must survive", source.exists())
            else assertEquals("Newer disk content must survive", restartDisk, source.readText())
        }
    }
}
