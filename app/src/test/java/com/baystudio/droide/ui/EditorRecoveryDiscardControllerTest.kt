package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorRecoveryDiscardControllerTest {
    @org.junit.Before fun installMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @org.junit.After fun resetMain() { Dispatchers.resetMain() }
    private suspend fun awaitResult(controller: EditorRecoveryDiscardController) = withTimeout(5000) {
        while (controller.state.value.mode == EditorRecoveryDiscardMode.WORKING) delay(5)
    }
    private fun editor(f: Fixture): EditorWorkspaceState = EditorWorkspaceState().also {
        it.completeRecovery(); it.retainRecoveryConflict("a.txt", "older", null)
        f.store.bindConflictAuthority(it.recoveryConflictAuthority)
    }
    @Test fun actualAdapterPreviewCheckpointsOnlyAndConfirmPreservesNewTyping() = fixture { f -> runBlocking {
        val e = editor(f); val project = File(f.files,"project").apply { mkdir() }; File(project,"a.txt").writeText("disk")
        val doc = e.document("a.txt"); doc.ensureLoaded(FileRepository(project)); doc.capture("draft")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val c = EditorRecoveryDiscardController(e,f.store,scope) { true }
            val preview = c.prepare(e.retainedCopies().single())
            assertEquals("disk",File(project,"a.txt").readText()); assertEquals(1,e.retainedCopies().size)
            doc.capture("new typing after preview")
            c.confirm(preview); awaitResult(c)
            assertEquals(EditorRecoveryDiscardMode.REMOVED,c.state.value.mode)
            assertTrue(e.retainedCopies().isEmpty()); assertEquals("new typing after preview",doc.content); assertTrue(doc.dirty)
            assertEquals("new typing after preview",f.store.load()!!.deferredBuffers.getValue("a.txt").readText())
            assertEquals("disk",File(project,"a.txt").readText())
        } finally { scope.cancel() }
    } }
    @Test fun revokedPreviewNeverMutatesAndCancellationBeforeCommitKeepsCopy() = fixture { f -> runBlocking {
        val e=editor(f); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val c=EditorRecoveryDiscardController(e,f.store,scope){true}
            val revoked=c.prepare(e.retainedCopies().single());c.revoke(revoked);c.confirm(revoked)
            assertEquals(EditorRecoveryDiscardMode.KEPT,c.state.value.mode);assertEquals(1,e.retainedCopies().size)
            val preview=c.prepare(e.retainedCopies().single());f.cipher.beforeEncrypt={throw CancellationException("cancel before manifest")}
            c.confirm(preview);awaitResult(c);f.cipher.beforeEncrypt=null
            assertEquals(EditorRecoveryDiscardMode.KEPT,c.state.value.mode);assertEquals(1,e.retainedCopies().size)
            assertEquals("older",f.store.load()!!.deferredConflictBuffers.getValue("a.txt").readText())
        } finally { scope.cancel() }
    } }
    @Test fun uncertainAdapterKeepsSelectedPendingUntilExplicitRetry() = fixture { f -> runBlocking {
        val e=editor(f);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            val c=EditorRecoveryDiscardController(e,f.store,scope){true};val copy=e.retainedCopies().single();val preview=c.prepare(copy)
            var count=0;f.beforeSync={if(it==f.root && ++count>=2)throw java.io.IOException("fsync uncertain")}
            c.confirm(preview);awaitResult(c)
            assertEquals(EditorRecoveryDiscardMode.PENDING,c.state.value.mode);assertEquals(1,e.retainedCopies().size)
            assertTrue(runCatching{e.requireRetainedCopy(copy)}.isFailure)
            assertTrue(runCatching{f.store.save(e.recoverySnapshot(emptyList(),"")!!)}.exceptionOrNull() is EditorRecoveryCheckpointDeferredException)
            f.beforeSync=null;c.reconcile();awaitResult(c)
            assertEquals(EditorRecoveryDiscardMode.REMOVED,c.state.value.mode);assertTrue(e.retainedCopies().isEmpty())
        } finally { scope.cancel() }
    } }
    @Test fun runtimeCancellationAfterDurableCommitStillFinalizesSelectedMemory() = fixture { f -> runBlocking {
        val e=editor(f);val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Main.immediate)
        val c=EditorRecoveryDiscardController(e,f.store,scope){true};val preview=c.prepare(e.retainedCopies().single())
        var count=0;f.beforeSync={if(it==f.root && ++count==2)job.cancel()}
        c.confirm(preview);awaitResult(c);f.beforeSync=null
        assertTrue(e.retainedCopies().isEmpty())
        assertTrue(c.state.value.mode==EditorRecoveryDiscardMode.PENDING || c.state.value.mode==EditorRecoveryDiscardMode.REMOVED)
        val reopened=EditorStateStore(f.context,f.id,f.cipher,f.directorySync);val recovered=reopened.load()!!
        assertTrue(recovered.conflictPaths.isEmpty());reopened.save(recovered)
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
