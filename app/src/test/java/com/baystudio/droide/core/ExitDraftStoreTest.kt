package com.baystudio.droide.core

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
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

/** Real codec/store, bounded synthetic text, host AES-GCM and directory sync seam. Does not
 * exercise AndroidKeyStore, Android Os.fsync, Compose, providers, terminals or media bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExitDraftStoreTest {
    @Test(timeout = 10_000) fun unchangedDraftsReuseAuthenticatedCiphertextButStillRequireDurability() = fixture { f ->
        val old = draft("Keep my exact unsent draft 🧪")
        f.store.preserve(listOf(old))
        val bytes = f.file.readBytes()
        f.cipher.beforeEncrypt = { error("An unchanged archive must not be encrypted again") }
        assertEquals(listOf(old), f.store.preserve(listOf(old)))
        assertEquals(listOf(old), f.newStore().preserve(emptyList()))
        assertArrayEquals(bytes, f.file.readBytes())
        f.failSync = true
        rejectedSuspend { f.store.preserve(listOf(old)) }
        assertArrayEquals(bytes, f.file.readBytes())
        f.failSync = false
        assertEquals(listOf(old), f.store.preserve(listOf(old)))
    }

    @Test fun codecPreservesFullUnicodeTextPreviewCompletenessAndExpiry() {
        val text = "Draft \uD83E\uDDEA\n" + "x".repeat(63_980)
        val preview = "p".repeat(99_998) + "\uD83D\uDE80"
        val ref = ChatMediaRef("m-test", ".droide/chat-media/blobs/m-test/original.txt", "example.txt", "text/plain", 1_000, 10, 20)
        val record = ExitDraftRecord("codex", "session-a", text, listOf(ExitDraftAttachment("example.txt", preview, ref, true)))
        val decoded = ExitDraftCodec.decode(ExitDraftCodec.encode(listOf(record))).single()
        assertEquals(record, decoded)
        assertEquals(text, decoded.text)
        assertEquals(preview, decoded.attachments.single().preview)
        assertTrue(decoded.attachments.single().previewTruncated)
        assertEquals(20L, decoded.attachments.single().media!!.expiresAt)
    }

    @Test fun codecRejectsOverlongPreviewAndMalformedUtf8RatherThanSilentlyShortening() {
        rejected { ExitDraftCodec.encode(listOf(ExitDraftRecord("droide", null, "keep",
            listOf(ExitDraftAttachment("file.txt", "x".repeat(100_001)))))) }
        rejected { ExitDraftCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        rejected { ExitDraftCodec.encode(listOf(ExitDraftRecord("droide", null, "broken-\uD800"))) }
    }

    @Test fun legacyVersionOneWithoutCompletenessFlagKeepsItsExactPreview() {
        val bytes = """{"version":1,"drafts":[{"backend":"droide","text":"old exact text","attachments":[{"name":"legacy.txt","preview":"old exact preview"}]}]}""".toByteArray()
        val record = ExitDraftCodec.decode(bytes).single()
        assertEquals("old exact text", record.text)
        assertEquals("old exact preview", record.attachments.single().preview)
        assertFalse(record.attachments.single().previewTruncated)
        assertEquals(record, ExitDraftCodec.decode(ExitDraftCodec.encode(listOf(record))).single())
    }

    @Test fun ordinaryMaximumCurrentInputsFitEvenWithWorstCaseJsonEscaping() {
        val native = (0 until 33).map { ExitDraftRecord("droide", "native-$it", "\u0001".repeat(64_000)) }
        val external = (0 until 16).map { ExitDraftRecord("codex", "external-$it", "\u0002".repeat(32_000)) } +
            ExitDraftRecord("codex", "external-inflight", "\u0003".repeat(64_000))
        val composer = ExitDraftRecord("droide", "composer", "\u0004".repeat(64_000),
            (0 until 4).map { ExitDraftAttachment("file-$it", "\u0005".repeat(12_000)) })
        val records = native + external + composer
        assertEquals(51, records.size)
        val encoded = ExitDraftCodec.encode(records)
        assertTrue(encoded.size < ExitDraftCodec.MAX_BYTES)
        assertEquals(records, ExitDraftCodec.decode(encoded))
    }

    @Test fun malformedMetadataFailsClosed() {
        rejected { ExitDraftCodec.decode("""{"version":1,"drafts":[{"backend":"droide","session":1,"text":"x","attachments":[]}]}""".toByteArray()) }
        rejected { ExitDraftCodec.decode("""{"version":2,"drafts":[]}""".toByteArray()) }
        rejected { ExitDraftCodec.encode(listOf(ExitDraftRecord("droide", null, "x", listOf(ExitDraftAttachment("a", "preview",
            ChatMediaRef("m", "path", "a", "text/plain", 1, 200, 100)))))) }
    }

    @Test(timeout = 10_000) fun emptyUntouchedComposerNeedsNoCipherOrNewArchive() = fixture { f ->
        f.cipher.beforeEncrypt = { error("No encryption should be needed for no drafts") }
        assertTrue(f.store.preserve(emptyList()).isEmpty())
        assertFalse(f.file.exists())
        assertFalse(File(f.root, ".droide").exists())
    }

    @Test(timeout = 10_000) fun preservesAndReloadsEncryptedMergeWithoutReplacingOldDrafts() = fixture { f ->
        val old = draft("old user-authored unsent text")
        val next = draft("new user-authored unsent text")
        assertEquals(listOf(old), f.store.preserve(listOf(old)))
        assertFalse(f.file.readBytes().toString(Charsets.ISO_8859_1).contains(old.text))
        assertEquals(listOf(old, next), f.newStore().preserve(listOf(next)))
        assertEquals(listOf(old, next), f.store.preserve(listOf(next)))
        assertEquals(listOf(old, next), f.newStore().load())
        assertEquals(listOf(old, next), f.store.preserve(emptyList()))
        assertTrue(f.synced.contains(checkNotNull(f.file.parentFile)))
        assertTrue(checkNotNull(f.file.parentFile).listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test(timeout = 10_000) fun capacityNeverDropsOldCopiesAndExplicitSelectedDeletionMakesRoom() = fixture { f ->
        val full = (0 until ExitDraftCodec.MAX_RECORDS).map { draft("saved-$it") }
        f.store.preserve(full)
        val before = f.file.readBytes()
        val new = draft("current composer must remain")
        val failure = rejectedSuspend { f.store.preserve(listOf(new)) }
        assertTrue(failure.message.orEmpty().contains("explicitly delete"))
        assertArrayEquals(before, f.file.readBytes())
        assertEquals(full, f.store.load())
        val selected = full[13]
        assertEquals(full.filterNot { it == selected }, f.store.remove(selected.identity))
        // Idempotent retry after a potentially uncertain acknowledgement deletes no other copy.
        assertEquals(full.filterNot { it == selected }, f.store.remove(selected.identity))
        assertEquals(full.filterNot { it == selected } + new, f.store.preserve(listOf(new)))
    }

    @Test(timeout = 10_000) fun byteCapacityFailureAndInvalidDeleteLeaveExistingCiphertextUntouched() = fixture { f ->
        val old = draft("retained")
        f.store.preserve(listOf(old))
        val before = f.file.readBytes()
        rejectedSuspend { f.store.preserve((0..(ExitDraftCodec.MAX_BYTES / 500_000)).map { draft(it.toString().padStart(3, '0') + "x".repeat(499_997)) }) }
        assertArrayEquals(before, f.file.readBytes())
        rejectedSuspend { f.store.remove("not-a-selected-identity") }
        assertArrayEquals(before, f.file.readBytes())
        assertEquals(listOf(old), f.store.load())
    }

    @Test(timeout = 10_000) fun lastCopyDeletionPublishesEmptyArchiveAndDoesNotDeleteOtherFiles() = fixture { f ->
        val old = draft("delete this exact saved copy")
        val unrelated = File(f.root, "original.txt").apply { writeText("original file") }
        f.store.preserve(listOf(old))
        assertEquals(emptyList<ExitDraftRecord>(), f.store.remove(old.identity))
        assertTrue(f.file.isFile)
        assertEquals(emptyList<ExitDraftRecord>(), f.store.load())
        assertEquals("original file", unrelated.readText())
    }

    @Test(timeout = 10_000) fun corruptArchiveRefusesPreserveAndDeletionWithoutDestroyingEvidence() = fixture { f ->
        val old = draft("previous")
        f.store.preserve(listOf(old))
        val corrupted = f.file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.file.writeBytes(corrupted)
        rejectedSuspend { f.store.load() }
        rejectedSuspend { f.store.preserve(listOf(draft("new"))) }
        rejectedSuspend { f.store.remove(old.identity) }
        assertArrayEquals(corrupted, f.file.readBytes())
    }

    @Test(timeout = 10_000) fun ciphertextIsVerifiedBeforeReplacingPreviouslySavedCopies() = fixture { f ->
        val old = draft("old")
        f.store.preserve(listOf(old))
        val before = f.file.readBytes()
        f.cipher.damageNextEncryption = true
        rejectedSuspend { f.store.preserve(listOf(draft("new"))) }
        assertArrayEquals(before, f.file.readBytes())
        assertEquals(listOf(old), f.store.load())
        assertTrue(checkNotNull(f.file.parentFile).listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test(timeout = 10_000) fun directorySyncFailureIsVisibleAndRetryKeepsEverySavedRecord() = fixture { f ->
        val old = draft("old")
        val next = draft("new")
        f.store.preserve(listOf(old))
        f.failSync = true
        rejectedSuspend { f.store.preserve(listOf(next)) }
        // Rename may already have published. Failure is never reported as a confirmed Exit.
        assertEquals(listOf(old, next), f.store.load())
        f.failSync = false
        assertEquals(listOf(old, next), f.store.preserve(listOf(next)))
    }

    @Test(timeout = 10_000) fun noFollowRejectsArchiveSymlinkWithoutTouchingItsTarget() = fixture { f ->
        f.store.preserve(listOf(draft("seed")))
        val outside = File(f.root, "outside.bin").apply { writeText("do not overwrite") }
        Files.delete(f.file.toPath())
        Files.createSymbolicLink(f.file.toPath(), outside.toPath())
        rejectedSuspend { f.store.load() }
        rejectedSuspend { f.store.preserve(listOf(draft("new"))) }
        assertEquals("do not overwrite", outside.readText())
        assertTrue(Files.isSymbolicLink(f.file.toPath()))
    }

    @Test(timeout = 10_000) fun noFollowRejectsMetadataDirectorySymlinkBeforeCreatingRecoveryFiles() = fixture { f ->
        val outside = File(f.root, "elsewhere").apply { mkdir() }
        Files.createSymbolicLink(File(f.root, ".droide").toPath(), outside.toPath())
        rejectedSuspend { f.store.preserve(listOf(draft("new"))) }
        assertTrue(outside.listFiles()!!.isEmpty())
    }

    @Test(timeout = 10_000) fun sharedProjectMutexPreventsLostDraftsAcrossStoreInstances() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.cipher.beforeEncrypt = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val first = async(start = CoroutineStart.UNDISPATCHED) { f.store.preserve(listOf(draft("first"))) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { f.newStore().preserve(listOf(draft("second"))) }
            f.cipher.beforeEncrypt = null
            release.countDown()
            first.await(); second.await()
            assertEquals(listOf(draft("first"), draft("second")), f.store.load())
        } finally { release.countDown() }
    }

    private fun draft(text: String) = ExitDraftRecord("droide", "session-test", text)
    private fun rejected(block: () -> Unit): Throwable = runCatching(block).exceptionOrNull() ?: throw AssertionError("Expected rejection")
    private suspend fun rejectedSuspend(block: suspend () -> Unit): Throwable = try { block(); throw AssertionError("Expected rejection") }
        catch (failure: Exception) { failure }

    private fun fixture(block: suspend kotlinx.coroutines.CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { block(f) } finally { PathSecurity.deleteTreeNoFollow(f.root) }
    }
    private class Fixture {
        val root = Files.createTempDirectory("r64-exit-drafts-").toFile()
        private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val cipher = HostCipher()
        val synced = mutableListOf<File>()
        @Volatile var failSync = false
        val file = File(root, ".droide/exit-drafts/test-project.bin")
        fun newStore() = ExitDraftStore(context, "test-project", cipher) { directory ->
            check(!failSync) { "Fixture directory sync failed" }
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
            synced += directory
        }
        val store = newStore()
    }
    private class HostCipher : ExitDraftCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        @Volatile var beforeEncrypt: (() -> Unit)? = null
        @Volatile var damageNextEncryption = false
        override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
            beforeEncrypt?.invoke()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(aad)
            return (cipher.iv + cipher.doFinal(plain)).also {
                if (damageNextEncryption) { damageNextEncryption = false; it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            }
        }
        override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12))); cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }
}
