package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tiny host-encrypted fixtures only; no AndroidKeyStore, native editor, PTY, or device execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorStateStoreRetirementTest {
    @Test(timeout = 15_000)
    fun successfulHandoffRejectsStaleCheckpointQueuedBehindTokenOwnedSave() = fixture { f -> runBlocking {
        f.store.save(snapshot("prior checkpoint"))
        val staleCheckpoint = snapshot("captured before handoff")
        val token = requireNotNull(f.store.tryRetireWrites())
        assertTrue(f.store.commitRetirement(token))
        val gate = IoGate()
        f.cipher.beforeEncrypt = gate::enter
        val ownedSave = async(start = CoroutineStart.UNDISPATCHED) {
            f.store.saveRetired(snapshot("final captured text"), token)
        }
        try {
            gate.awaitEntered()
            // UNDISPATCHED reaches mutex.lock before returning, so this is a queued caller,
            // not merely a coroutine scheduled to start after the token-owned write finishes.
            val staleSave = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { f.store.save(staleCheckpoint) }.exceptionOrNull()
            }
            assertFalse("Stale caller must wait behind the admitted token-owned IO", staleSave.isCompleted)
            gate.release()
            ownedSave.await()
            val committedBytes = f.file.readBytes()
            assertTrue(staleSave.await() is IllegalStateException)
            assertArrayEquals(committedBytes, f.file.readBytes())
            assertEquals("final captured text", f.text())
            assertFalse(f.store.abortRetirement(token))
            rejected { f.store.save(snapshot("another late stale checkpoint")) }
            assertEquals("final captured text", f.text())
        } finally {
            gate.release()
            f.cipher.beforeEncrypt = null
        }
    } }

    @Test(timeout = 15_000)
    fun exactPendingAbortRestoresOrdinarySaveButCannotAbortTheNextAttempt() = fixture { f -> runBlocking {
        f.store.save(snapshot("preserved before preparation"))
        val first = requireNotNull(f.store.tryRetireWrites())
        rejected { f.store.save(snapshot("pending ordinary write")) }
        rejected { f.store.saveRetired(snapshot("uncommitted token write"), first) }
        assertEquals("preserved before preparation", f.text())
        assertTrue(f.store.abortRetirement(first))
        assertFalse(f.store.abortRetirement(first))
        assertFalse(f.store.commitRetirement(first))
        f.store.save(snapshot("ordinary save after aborted switch"))
        assertEquals("ordinary save after aborted switch", f.text())

        val second = requireNotNull(f.store.tryRetireWrites())
        assertNotSame(first, second)
        assertFalse(f.store.abortRetirement(first))
        assertFalse(f.store.commitRetirement(first))
        rejected { f.store.saveRetired(snapshot("old token write"), first) }
        rejected { f.store.save(snapshot("second attempt must remain fenced")) }
        assertTrue(f.store.abortRetirement(second))
        f.store.save(snapshot("ordinary save restored again"))
        assertEquals("ordinary save restored again", f.text())
    } }

    @Test(timeout = 15_000)
    fun inFlightOrdinarySaveRefusesRetirementWithoutLosingTheCheckpoint() = fixture { f -> runBlocking {
        f.store.save(snapshot("previous checkpoint"))
        val priorBytes = f.file.readBytes()
        val gate = IoGate()
        f.cipher.beforeEncrypt = gate::enter
        val saving = async(start = CoroutineStart.UNDISPATCHED) {
            f.store.save(snapshot("in-flight checkpoint"))
        }
        try {
            gate.awaitEntered()
            assertNull("Retirement must return without waiting for the gated IO", f.store.tryRetireWrites())
            assertArrayEquals(priorBytes, f.file.readBytes())
            gate.release()
            saving.await()
            assertEquals("in-flight checkpoint", f.text())
            f.store.save(snapshot("store remains writable after refusal"))
            assertEquals("store remains writable after refusal", f.text())
            val token = requireNotNull(f.store.tryRetireWrites())
            assertTrue(f.store.abortRetirement(token))
        } finally {
            gate.release()
            f.cipher.beforeEncrypt = null
        }
    } }

    @Test(timeout = 15_000)
    fun inFlightDeferredReadAlsoRefusesRetirementAndKeepsRecoveryReadable() = fixture { f -> runBlocking {
        f.store.save(snapshot("lazy recovery text"))
        val deferred = f.store.load()!!.deferredBuffers.getValue(PATH)
        val priorBytes = f.file.readBytes()
        val gate = IoGate()
        f.cipher.beforeDecrypt = gate::enter
        val reading = async(start = CoroutineStart.UNDISPATCHED) { deferred.readText() }
        try {
            gate.awaitEntered()
            assertNull(f.store.tryRetireWrites())
            assertArrayEquals(priorBytes, f.file.readBytes())
            gate.release()
            assertEquals("lazy recovery text", reading.await())
            val token = requireNotNull(f.store.tryRetireWrites())
            assertTrue(f.store.commitRetirement(token))
            assertEquals("lazy recovery text", deferred.readText())
        } finally {
            gate.release()
            f.cipher.beforeDecrypt = null
        }
    } }

    @Test(timeout = 15_000)
    fun foreignTokenCannotCommitAbortOrWriteAnotherStore() = fixture { f -> fixture { other -> runBlocking {
        f.store.save(snapshot("this store's checkpoint"))
        val priorBytes = f.file.readBytes()
        val own = requireNotNull(f.store.tryRetireWrites())
        val foreign = requireNotNull(other.store.tryRetireWrites())
        assertTrue(other.store.commitRetirement(foreign))
        assertFalse(f.store.commitRetirement(foreign))
        assertFalse(f.store.abortRetirement(foreign))
        rejected { f.store.saveRetired(snapshot("foreign pending replacement"), foreign) }
        rejected { f.store.save(snapshot("foreign abort must not reopen writes")) }
        assertTrue(f.store.commitRetirement(own))
        assertFalse(f.store.commitRetirement(foreign))
        assertFalse(f.store.abortRetirement(foreign))
        rejected { f.store.saveRetired(snapshot("foreign committed replacement"), foreign) }
        assertArrayEquals(priorBytes, f.file.readBytes())
        assertEquals("this store's checkpoint", f.text())
        f.store.saveRetired(snapshot("own token checkpoint"), own)
        assertEquals("own token checkpoint", f.text())
    } } }

    @Test(timeout = 15_000)
    fun committedTokenSurvivesLateAbortAndNeverReopensOrdinaryWrites() = fixture { f -> runBlocking {
        f.store.save(snapshot("before commit"))
        val token = requireNotNull(f.store.tryRetireWrites())
        assertNull(f.store.tryRetireWrites())
        assertTrue(f.store.commitRetirement(token))
        assertTrue("Committing the same exact token is idempotent", f.store.commitRetirement(token))
        assertFalse(f.store.abortRetirement(token))
        assertNull(f.store.tryRetireWrites())
        rejected { f.store.save(snapshot("late old-runtime write")) }
        f.store.saveRetired(snapshot("committed token recovery"), token)
        assertFalse(f.store.abortRetirement(token))
        rejected { f.store.save(snapshot("write after token checkpoint")) }
        assertEquals("committed token recovery", f.text())
    } }

    @Test(timeout = 15_000)
    fun clearRejectsPendingAndCommittedRetirementWithoutDeletingRecovery() = fixture { f -> runBlocking {
        f.store.save(snapshot("clear must preserve this"))
        val deferred = f.store.load()!!.deferredBuffers.getValue(PATH)
        val priorBytes = f.file.readBytes()
        val blob = File(f.blobs, deferred.blobName)
        val priorBlob = blob.readBytes()
        val token = requireNotNull(f.store.tryRetireWrites())
        rejected { f.store.clear() }
        assertArrayEquals(priorBytes, f.file.readBytes())
        assertArrayEquals(priorBlob, blob.readBytes())
        assertTrue(f.store.commitRetirement(token))
        rejected { f.store.clear() }
        assertFalse(f.store.abortRetirement(token))
        rejected { f.store.clear() }
        assertArrayEquals(priorBytes, f.file.readBytes())
        assertArrayEquals(priorBlob, blob.readBytes())
        assertEquals("clear must preserve this", deferred.readText())
    } }

    @Test(timeout = 15_000)
    fun loadAndDeferredLiveAndConflictRecoveryStillWorkWithCommittedToken() = fixture { f -> runBlocking {
        f.store.save(snapshot("live text").copy(conflictBuffers = mapOf(PATH to "older conflict")))
        val before = f.store.load()!!
        val live = before.deferredBuffers.getValue(PATH)
        val conflict = before.deferredConflictBuffers.getValue(PATH)
        val token = requireNotNull(f.store.tryRetireWrites())
        assertTrue(f.store.commitRetirement(token))
        assertEquals("live text", live.readText())
        assertEquals("older conflict", conflict.readText())
        f.store.saveRetired(before, token)
        val after = f.store.load()!!
        assertTrue(after.dirtyBuffers.isEmpty())
        assertTrue(after.conflictBuffers.isEmpty())
        assertEquals(live.blobName, after.deferredBuffers.getValue(PATH).blobName)
        assertEquals(conflict.blobName, after.deferredConflictBuffers.getValue(PATH).blobName)
        assertEquals("live text", after.deferredBuffers.getValue(PATH).readText())
        assertEquals("older conflict", after.deferredConflictBuffers.getValue(PATH).readText())
    } }

    @Test(timeout = 15_000)
    fun tentativeExitWriteIsExactOwnerBoundReversibleAndRejectedAfterCommit() = fixture { f -> fixture { other -> runBlocking {
        f.store.save(snapshot("previous recovery"))
        val token = requireNotNull(f.store.tryRetireWrites())
        val foreign = requireNotNull(other.store.tryRetireWrites())
        rejected { f.store.saveTentative(snapshot("foreign token"), foreign) }
        rejected { f.store.save(snapshot("stale ordinary writer")) }
        f.store.saveTentative(snapshot("verified Exit draft"), token)
        assertEquals("verified Exit draft", f.text())
        assertTrue(f.store.abortRetirement(token))
        rejected { f.store.saveTentative(snapshot("aborted token"), token) }
        f.store.save(snapshot("ordinary writer restored"))
        val next = requireNotNull(f.store.tryRetireWrites())
        assertTrue(f.store.commitRetirement(next))
        rejected { f.store.saveTentative(snapshot("tentative after commit"), next) }
        f.store.saveRetired(snapshot("final release draft"), next)
        assertEquals("final release draft", f.text())
        assertTrue(other.store.abortRetirement(foreign))
    } } }

    private suspend fun rejected(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected retirement rejection, got $failure", failure is IllegalStateException)
    }

    private fun snapshot(text: String) = EditorRecoverySnapshot(listOf(PATH), PATH, mapOf(PATH to text))

    private class IoGate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun enter() {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS)) { "Fixture IO was not released" }
        }
        fun awaitEntered() = assertTrue("Fixture IO did not enter", entered.await(5, TimeUnit.SECONDS))
        fun release() { released.countDown() }
    }

    private class HostCipher : EditorRecoveryCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        @Volatile var beforeEncrypt: (() -> Unit)? = null
        @Volatile var beforeDecrypt: (() -> Unit)? = null
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            beforeEncrypt?.invoke()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            beforeDecrypt?.invoke()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private class Fixture(base: Context) {
        val files = File(base.filesDir, "retirement-fixture-${UUID.randomUUID()}").apply { mkdir() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        val id = "retirement-test-${UUID.randomUUID()}"
        val cipher = HostCipher()
        // Match the existing host-store fixture; production still uses Android Os.fsync.
        private val directorySync: (File) -> Unit = { directory ->
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS))
            FileChannel.open(directory.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        }
        val store = EditorStateStore(context, id, cipher, directorySync)
        private val root = File(context.filesDir, ".droide/editor-recovery")
        val file = File(root, "$id.bin")
        val blobs = File(root, "$id.blobs")
        suspend fun text(): String = store.load()!!.deferredBuffers.getValue(PATH).readText()
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture(RuntimeEnvironment.getApplication())
        try { block(f) } finally { PathSecurity.deleteTreeNoFollow(f.files) }
    }

    companion object {
        private const val PATH = "main.kt"
    }
}
