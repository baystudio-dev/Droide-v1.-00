package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorRecoveryDiscardTest {
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun snapshot(a: EditorRecoveryConflictAuthority, dirty: Map<String,String> = emptyMap(), conflicts: Map<String,String> = emptyMap()) =
        EditorRecoverySnapshot(emptyList(), "", dirty, conflictBuffers = conflicts, conflictRevision = a.capture())
    private suspend fun setup(f: Fixture): EditorRecoveryConflictAuthority {
        val authority = EditorRecoveryConflictAuthority(); f.store.bindConflictAuthority(authority)
        f.store.save(snapshot(authority, mapOf("a.txt" to "new dirty text"), mapOf("a.txt" to "older", "other.txt" to "other copy")))
        return authority
    }
    @Test fun exactOlderGenerationRemovedWhileSamePathDirtyAndOtherConflictSurvive() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        val old = File(f.blobs, selected.blob); val stale = snapshot(a, conflicts = mapOf("a.txt" to "older"))
        a.revoke()
        val result = f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke)
        assertEquals(EditorStateStore.DiscardPhase.DURABLE, result.phase); assertTrue(result.ciphertextRemoved); assertFalse(old.exists())
        val loaded = f.store.load()!!
        assertEquals("new dirty text", loaded.deferredBuffers.getValue("a.txt").readText())
        assertEquals(setOf("other.txt"), loaded.conflictPaths)
        assertEquals("other copy", loaded.deferredConflictBuffers.getValue("other.txt").readText())
        assertTrue(runCatching { f.store.save(stale) }.isFailure)
        assertFalse(result.message.contains("bytes freed"))
    } }
    @Test fun unrelatedCheckpointBetweenPreviewAndConfirmDoesNotInvalidateConsent() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        f.store.save(snapshot(a, mapOf("a.txt" to "even newer typing"), mapOf("a.txt" to "older", "other.txt" to "other copy")))
        a.revoke()
        val result = f.store.discardConflict(snapshot(a, mapOf("a.txt" to "even newer typing"), mapOf("other.txt" to "other copy")), selected, a::revoke)
        assertTrue(result.ciphertextRemoved)
        assertEquals("even newer typing", f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
    } }
    @Test fun changedSelectedCiphertextRefusesWithoutPublication() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        val blob = File(f.blobs, selected.blob); blob.writeBytes(byteArrayOf(9))
        val before = f.file.readBytes()
        val result = f.store.discardConflict(snapshot(a, conflicts = mapOf("other.txt" to "other copy")), selected, a::revoke)
        assertEquals(EditorStateStore.DiscardPhase.PREPARED, result.phase); assertFalse(result.ciphertextRemoved)
        assertArrayEquals(before, f.file.readBytes()); assertArrayEquals(byteArrayOf(9), blob.readBytes())
    } }
    @Test fun selectedLiveBlobAliasCannotBePrepared() = fixture { f -> runBlocking {
        setup(f); val m = f.manifest(); val selected = m.getValue("conflicts").jsonArray.first().jsonObject
        f.writeManifest(JsonObject(m.toMutableMap().apply { put("dirty", JsonArray(listOf(selected))) }))
        val before = f.file.readBytes()
        assertTrue(runCatching { f.store.prepareConflictDiscard("a.txt", hash("older")) }.isFailure)
        assertArrayEquals(before, f.file.readBytes())
    } }
    @Test fun unknownOrphanIsNeverSelectedByPathOrName() = fixture { f -> runBlocking {
        val a = setup(f); val orphan = File(f.blobs, "${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(8)) }
        f.store.save(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("a.txt" to "older", "other.txt" to "other copy")))
        assertTrue(runCatching { f.store.prepareConflictDiscard(orphan.name, hash("older")) }.isFailure)
        assertArrayEquals(byteArrayOf(8), orphan.readBytes())
    } }
    @Test fun symlinkReplacementPreservesForeignTarget() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older")); val blob = File(f.blobs, selected.blob)
        val foreign = File(f.files, "foreign").apply { writeBytes(blob.readBytes()) }; Files.delete(blob.toPath()); Files.createSymbolicLink(blob.toPath(), foreign.toPath())
        val before = foreign.readBytes()
        val result = f.store.discardConflict(snapshot(a, conflicts = mapOf("other.txt" to "other copy")), selected, a::revoke)
        assertEquals(EditorStateStore.DiscardPhase.PREPARED, result.phase); assertTrue(Files.isSymbolicLink(blob.toPath())); assertArrayEquals(before, foreign.readBytes())
    } }
    @Test fun participatingRecoveryWriterBlocksButUnrelatedWorkspaceWriterDoesNot() = fixture { f -> runBlocking {
        val a = setup(f); val first = f.store.prepareConflictDiscard("a.txt", hash("older"))
        ExportWriterAdmission.beginWriter(f.blobs, "native recovery writer").use {
            assertEquals(EditorStateStore.DiscardPhase.PREPARED, f.store.discardConflict(snapshot(a, conflicts = mapOf("other.txt" to "other copy")), first, a::revoke).phase)
        }
        val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        ExportWriterAdmission.beginWriter(File(f.files, "unrelated-workspace").apply { mkdir() }, "terminal").use {
            assertTrue(f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke).ciphertextRemoved)
        }
    } }
    @Test fun cancellationBeforePublicationLeavesOriginalAndManifest() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older")); val before = f.file.readBytes()
        f.cipher.beforeEncrypt = { throw kotlinx.coroutines.CancellationException("cancel before commit") }
        assertTrue(runCatching { f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke) }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        f.cipher.beforeEncrypt = null
        assertEquals(EditorStateStore.DiscardPhase.PREPARED, selected.phase); assertArrayEquals(before, f.file.readBytes()); assertTrue(File(f.blobs, selected.blob).exists())
    } }
    @Test fun uncertainFsyncFencesQueuedCheckpointUntilExactReconciliation() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older")); a.revoke()
        val pendingSnapshot = snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("a.txt" to "older", "other.txt" to "other copy"))
        var roots = 0; f.beforeSync = { if (it == f.root && ++roots >= 2) throw java.io.IOException("injected after rename") }
        val result = f.store.discardConflict(pendingSnapshot.copy(conflictBuffers = pendingSnapshot.conflictBuffers - "a.txt"), selected, a::revoke)
        assertEquals(EditorStateStore.DiscardPhase.UNCERTAIN, result.phase); assertFalse(result.ciphertextRemoved)
        assertTrue(File(f.blobs, selected.blob).exists()); assertNull(f.store.tryRetireWrites())
        assertTrue(runCatching { f.store.save(pendingSnapshot) }.isFailure)
        f.beforeSync = null
        val reconciled = f.store.reconcileConflictDiscard(selected)
        assertTrue(reconciled.ciphertextRemoved); assertTrue(runCatching { f.store.save(pendingSnapshot) }.isFailure)
        assertFalse("a.txt" in f.store.load()!!.conflictPaths)
    } }
    @Test fun restartFinishesOnlyConfirmedV7Receipt() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        var roots = 0; f.beforeSync = { if (it == f.root && ++roots >= 2) throw java.io.IOException("restart after rename") }
        assertEquals(EditorStateStore.DiscardPhase.UNCERTAIN, f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke).phase)
        f.beforeSync = null
        val reopened = EditorStateStore(f.context, f.id, f.cipher, f.directorySync)
        val recovered = reopened.load()!!; assertFalse("a.txt" in recovered.conflictPaths)
        reopened.save(recovered)
        assertFalse(File(f.blobs, selected.blob).exists()); assertEquals("other copy", reopened.load()!!.deferredConflictBuffers.getValue("other.txt").readText())
    } }
    @Test fun changedNativeWriterAtCleanupIsKeptAndNoRemovalClaimed() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older")); val blob = File(f.blobs, selected.blob)
        var roots = 0; f.beforeSync = { if (it == f.root && ++roots == 2) blob.writeBytes(byteArrayOf(77)) }
        val result = f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke)
        assertEquals(EditorStateStore.DiscardPhase.DURABLE, result.phase); assertFalse(result.ciphertextRemoved)
        f.beforeSync = null; assertFalse(f.store.reconcileConflictDiscard(selected).ciphertextRemoved)
        assertArrayEquals(byteArrayOf(77), blob.readBytes())
    } }
    @Test fun productionBindingRejectsNullForeignStaleAndRetiredSnapshots() = fixture { f -> runBlocking {
        val a = setup(f); val stale = snapshot(a, conflicts = mapOf("a.txt" to "older")); a.revoke()
        assertTrue(runCatching { f.store.save(EditorRecoverySnapshot(emptyList(), "", emptyMap(), conflictBuffers = mapOf("a.txt" to "older"))) }.isFailure)
        assertTrue(runCatching { f.store.save(snapshot(EditorRecoveryConflictAuthority())) }.isFailure)
        assertTrue(runCatching { f.store.save(stale) }.isFailure); assertTrue(runCatching { f.store.clear() }.isFailure)
        val token = checkNotNull(f.store.tryRetireWrites())
        assertTrue(runCatching { f.store.saveTentative(stale, token) }.isFailure)
        assertTrue(f.store.commitRetirement(token)); assertTrue(runCatching { f.store.saveRetired(stale, token) }.isFailure)
    } }
    @Test fun duplicateConfirmationCannotRemoveAnotherCopy() = fixture { f -> runBlocking {
        val a = setup(f); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        assertTrue(f.store.discardConflict(snapshot(a, mapOf("a.txt" to "new dirty text"), mapOf("other.txt" to "other copy")), selected, a::revoke).ciphertextRemoved)
        assertTrue(runCatching { f.store.discardConflict(snapshot(a), selected, a::revoke) }.isFailure)
        assertEquals(setOf("other.txt"), f.store.load()!!.conflictPaths)
    } }
    @Test fun snapshotCapturedAfterDurableBeforeMainFinalizationCannotResurrectCopy() = fixture { f -> runBlocking {
        val editor = com.baystudio.droide.ui.EditorWorkspaceState(); editor.completeRecovery()
        editor.retainRecoveryConflict("a.txt", "older", null)
        f.store.bindConflictAuthority(editor.recoveryConflictAuthority)
        val project = File(f.files, "project").apply { mkdir() }; File(project, "a.txt").writeText("disk")
        val files = FileRepository(project)
        val current = editor.document("a.txt"); current.ensureLoaded(files); current.capture("new dirty text")
        f.store.save(editor.recoverySnapshot(emptyList(), "")!!)
        val signatureBefore = editor.recoveryCheckpointSignature(emptyList(), "")
        val copy = editor.retainedCopies().single()
        val preparedOpen = com.baystudio.droide.ui.EditorRecoveryCopies(editor, files).prepare(copy, "copied.txt")
        val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        val transaction = editor.beginRecoveryDiscard(copy)
        assertTrue(runCatching { preparedOpen.open() }.isFailure)
        val result = f.store.discardConflict(editor.snapshotForRecoveryDiscard(transaction, emptyList(), ""), selected, editor::revokeDiscardCheckpoints)
        assertTrue(result.ciphertextRemoved)
        // Deliberately pause Main finalization: maps still contain the selected copy.
        val capturedInGap = editor.recoverySnapshot(emptyList(), "")!!
        assertTrue("a.txt" in capturedInGap.conflictPaths)
        assertTrue(runCatching { f.store.save(capturedInGap) }.isFailure)
        editor.finishRecoveryDiscard(transaction, committed = true)
        assertTrue(editor.retainedCopies().isEmpty()); assertEquals("new dirty text", current.content); assertTrue(current.dirty)
        assertNotEquals(signatureBefore, editor.recoveryCheckpointSignature(emptyList(), ""))
        f.store.save(editor.recoverySnapshot(emptyList(), "")!!)
        assertTrue(f.store.load()!!.conflictPaths.isEmpty()); assertEquals("disk", File(project, "a.txt").readText())
    } }
    @Test fun failedPendingRemovalNeverRollsBackNewConflictOrDirtyChanges() = fixture { f -> runBlocking {
        val editor = com.baystudio.droide.ui.EditorWorkspaceState(); editor.completeRecovery()
        editor.retainRecoveryConflict("a.txt", "older", null); f.store.bindConflictAuthority(editor.recoveryConflictAuthority)
        f.store.save(editor.recoverySnapshot(emptyList(), "")!!)
        val copy = editor.retainedCopies().single(); val selected = f.store.prepareConflictDiscard("a.txt", hash("older"))
        val transaction = editor.beginRecoveryDiscard(copy)
        val beforeNewConflict = editor.snapshotForRecoveryDiscard(transaction, emptyList(), "")
        editor.retainRecoveryConflict("new.txt", "new retained copy", null)
        val result = f.store.discardConflict(beforeNewConflict, selected, editor::revokeDiscardCheckpoints)
        assertEquals(EditorStateStore.DiscardPhase.PREPARED, result.phase)
        editor.finishRecoveryDiscard(transaction, committed = false)
        assertEquals(setOf("a.txt", "new.txt"), editor.retainedCopies().map { it.path }.toSet())
        f.store.save(editor.recoverySnapshot(emptyList(), "")!!)
        assertEquals("new retained copy", f.store.load()!!.deferredConflictBuffers.getValue("new.txt").readText())
    } }
    private class HostCipher : EditorRecoveryCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        var decryptions = 0
        var beforeEncrypt: ((ByteArray) -> Unit)? = null
        var beforeDecrypt: ((ByteArray) -> Unit)? = null
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            beforeEncrypt?.invoke(aad)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            decryptions++
            beforeDecrypt?.invoke(aad)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private class Fixture(base: Context) {
        val files = File(base.filesDir, "store-fixture-${UUID.randomUUID()}").apply { mkdir() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val id = "recovery-test-${UUID.randomUUID()}"
        val cipher = HostCipher()
        val syncedDirectories = CopyOnWriteArrayList<File>()
        var beforeSync: ((File) -> Unit)? = null
        // Robolectric ShadowLinux cannot open directory descriptors. Exercise real host
        // filesystem sync here; the public production constructor still uses Android Os.
        val directorySync: (File) -> Unit = { directory ->
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
            syncedDirectories += directory
            beforeSync?.invoke(directory)
        }
        val store = EditorStateStore(context, id, cipher, directorySync)
        val root = File(context.filesDir, ".droide/editor-recovery")
        val file = File(root, "$id.bin")
        val blobs = File(root, "$id.blobs")
        fun manifest() = Json.parseToJsonElement(cipher.decrypt(file.readBytes(), id.toByteArray()).toString(Charsets.UTF_8)).jsonObject
        fun writeManifest(value: JsonObject) = file.writeBytes(cipher.encrypt(value.toString().toByteArray(), id.toByteArray()))
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture(RuntimeEnvironment.getApplication())
        try { block(f) } finally { PathSecurity.deleteTreeNoFollow(f.files) }
    }
}
