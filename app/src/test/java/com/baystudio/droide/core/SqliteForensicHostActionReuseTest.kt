package com.baystudio.droide.core

import com.baystudio.droide.ui.retainedRecoveryActions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real production action adapter -> plugin host -> local recovery -> metadata authority. */

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteForensicHostActionReuseTest {
    private class Fixture {
        val root = Files.createTempDirectory("f159-real-forensic-action-").toFile()
        val source = root.resolve("source").apply { mkdir() }
        val cache = root.resolve("cache").apply { mkdir() }
        val store = root.resolve("persistent-workspaces")
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> failures += t })
        val hosts = mutableListOf<WorkspaceAgentPluginHost>()
        lateinit var local: LocalAgentWorkspaceProcessHost
        lateinit var host: WorkspaceAgentPluginHost
        lateinit var working: File
        lateinit var authority: File
        suspend fun start() {
            local = LocalAgentWorkspaceProcessHost.enrollRetained(source, cache, scope, store)
            local.workspaceIo.refresh()
            working = File(local.pathMapper().remoteRoot)
            authority = File(working.parentFile, ".${working.name}-sync-authority")
            val database = authority.resolve("state.sqlite").readBytes()
            authority.resolve("state.sqlite-journal").writeBytes(journal(database))
            local = LocalAgentWorkspaceProcessHost.forWorkspace(source, cache, scope, store)
            host = newHost(local)
            println("PRESERVED_REAL_HOST_FIXTURE ${root.path}")
        }
        fun newHost(local: LocalAgentWorkspaceProcessHost = LocalAgentWorkspaceProcessHost.forWorkspace(source, cache, scope, store)): WorkspaceAgentPluginHost =
            WorkspaceAgentPluginHost(source, cache, FileRepository(source), local, ApprovalManager(),
                PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), scope, local.workspaceIo).also(hosts::add)
        fun captures() = authority.listFiles()!!.filter { it.name.startsWith("sqlite-review-") }.sortedBy { it.name }
        fun candidates() = captures().flatMap { capture -> capture.listFiles()!!.filter { it.name.startsWith("candidate-") } }
        fun files() = authority.walkTopDown().filter { it.isFile }.associate { it.relativeTo(authority).path to hash(it.readBytes()) }
        fun originals() = listOf("state.sqlite", "state.sqlite-journal").associateWith { hash(authority.resolve(it).readBytes()) }
        suspend fun finish() {
            hosts.forEach { it.closeAndAwait() }
            scope.coroutineContext.job.cancelAndJoin()
            assertTrue("Unexpected background failures: $failures", failures.isEmpty())
        }
    }

    private fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        // Preserve the singleton's previous owner/state; never disable its production checks.
        val owner = ApplicationWorkAdmission::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val state = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
        val priorOwner = owner.get(ApplicationWorkAdmission); val priorState = state.get(ApplicationWorkAdmission)
        ApplicationWorkAdmission.initialize(RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
        val f = Fixture()
        try { withTimeout(30_000) { f.start(); block(f) } }
        finally {
            withContext(NonCancellable) { f.finish() }
            owner.set(ApplicationWorkAdmission, priorOwner); state.set(ApplicationWorkAdmission, priorState)
        }
    }
    private fun gate(host: WorkspaceAgentPluginHost) = WorkspaceAgentPluginHost::class.java
        .getDeclaredField("workspaceTurns").apply { isAccessible = true }.get(host) as Mutex
    @Suppress("UNCHECKED_CAST")
    private fun owners(host: WorkspaceAgentPluginHost) = WorkspaceAgentPluginHost::class.java
        .getDeclaredField("sessionOwners").apply { isAccessible = true }.get(host) as ConcurrentHashMap<String, WorkspaceAgentSessionLifetime>
    private fun preserved(before: Map<String, String>, f: Fixture) {
        before.forEach { (path, digest) -> assertEquals("Previously retained bytes must remain: $path", digest, hash(f.authority.resolve(path).readBytes())) }
    }
    private fun assertNotice(notice: String) {
        assertTrue(notice.startsWith("Unverified forensic candidate at sqlite-review-"))
        assertTrue(notice.contains("Source coherence and writer exclusion are unproven."))
        assertTrue(notice.contains("workspace activation and cleanup remain blocked."))
    }

    @Test fun eightPreserveActionsDoNotBlockFirstBuildAndRetryOrRecreatedHost() = fixture { f ->
        val actions = f.host.retainedRecoveryActions()
        repeat(8) { assertTrue(actions.captureSqliteEvidence().startsWith("Raw database and rollback journal preserved")) }
        val captures = f.captures(); val bytes = f.files(); val originals = f.originals()
        assertEquals(8, captures.size)
        val built = runCatching { actions.buildSqliteCandidate() }
        preserved(bytes, f); assertEquals(originals, f.originals())
        assertTrue("Actual retained recovery Build must consume the existing eighth-capacity evidence: ${built.exceptionOrNull()}", built.isSuccess)
        val notice = built.getOrThrow(); assertNotice(notice)
        assertEquals(captures, f.captures()); assertEquals(1, f.candidates().size)
        val completed = f.files()
        repeat(3) { assertEquals(notice, actions.buildSqliteCandidate()); assertEquals(completed, f.files()) }
        f.host.closeAndAwait()
        val restarted = f.newHost()
        assertEquals(notice, restarted.retainedRecoveryActions().buildSqliteCandidate())
        assertEquals(completed, f.files()); assertEquals(originals, f.originals())
        assertNotNull(runCatching { MirrorMetadataAuthority(f.authority, f.source, f.working).load() }.exceptionOrNull())
    }

    @Test fun repeatedActualBuildReturnsTheSamePublishedCandidate() = fixture { f ->
        val first = f.host.retainedRecoveryActions().buildSqliteCandidate(); assertNotice(first)
        val completed = f.files(); val captures = f.captures(); val candidates = f.candidates()
        val second = f.host.retainedRecoveryActions().buildSqliteCandidate()
        preserved(completed, f)
        assertEquals("Actual Build retry must return the already published path", first, second)
        assertEquals(completed, f.files()); assertEquals(captures, f.captures()); assertEquals(candidates, f.candidates())
    }

    @Test fun recreatedActualHostReopensTheSamePublishedCandidate() = fixture { f ->
        val first = f.host.retainedRecoveryActions().buildSqliteCandidate()
        val completed = f.files()
        f.host.closeAndAwait()
        val second = f.newHost().retainedRecoveryActions().buildSqliteCandidate()
        preserved(completed, f)
        assertEquals("Fresh host and recovery authority must reopen the existing candidate", first, second)
        assertEquals(completed, f.files())
    }

    @Test fun actualBuildReopensCompletedReportAfterLostReturnAtCapacity() = fixture { f ->
        repeat(7) { f.host.retainedRecoveryActions().captureSqliteEvidence() }
        val recovery = f.local.retainedRecovery.interrupted
        val field = MirrorInterruptedRecovery::class.java.getDeclaredField("authority").apply { isAccessible = true }
        val injected = MirrorMetadataAuthority(f.authority, f.source, f.working, processCheckpoint = {
            check(it != "metadata-before-database-open") { "Forensic action must not open live SQLite" }
            if (it == "forensic-after-report-publication") error("Injected lost return after completed report")
        })

        field.set(recovery, injected)
        val lost = runCatching { f.host.retainedRecoveryActions().buildSqliteCandidate() }
        assertTrue("Fault must occur after actual report publication: ${lost.exceptionOrNull()}",
            lost.exceptionOrNull()?.message.orEmpty().contains("Injected lost return"))
        val published = f.candidates().single { it.resolve("report").isFile }
        val rawCount = f.captures().size
        val bytes = f.files()
        field.set(recovery, MirrorMetadataAuthority(f.authority, f.source, f.working))
        val retry = runCatching { f.host.retainedRecoveryActions().buildSqliteCandidate() }
        preserved(bytes, f)
        assertTrue("Actual Build must reopen the published candidate after return loss: ${retry.exceptionOrNull()}", retry.isSuccess)
        assertTrue(retry.getOrThrow().contains("${published.parentFile.name}/${published.name}/candidate.sqlite"))
        assertEquals(rawCount, f.captures().size); assertEquals(listOf(published), f.candidates())
    }

    @Test fun changedCurrentInputAllocatesNewEvidenceWithoutChangingOldResult() = fixture { f ->
        val first = f.host.retainedRecoveryActions().buildSqliteCandidate()
        val oldEvidence = f.files().filterKeys { it.startsWith("sqlite-review-") }
        val database = f.authority.resolve("state.sqlite").readBytes()
        f.authority.resolve("state.sqlite-journal").writeBytes(journal(database, nonce = 987))
        val second = f.host.retainedRecoveryActions().buildSqliteCandidate()
        assertNotice(second); assertNotEquals(first, second)
        assertEquals(2, f.captures().size); assertEquals(2, f.candidates().size)
        preserved(oldEvidence, f)
    }

    @Test fun cancelledCallerQueuedBehindRealTurnGateCannotAllocateEvidence() = fixture { f ->
        val gate = gate(f.host); gate.lock(); val before = f.files()
        val action = async(start = CoroutineStart.UNDISPATCHED) { f.host.retainedRecoveryActions().buildSqliteCandidate() }
        assertFalse(action.isCompleted)
        action.cancel(); gate.unlock(); action.join()
        assertTrue(action.isCancelled); assertEquals(before, f.files()); assertTrue(f.captures().isEmpty())
    }

    @Test fun epochChangedWhileQueuedBehindRealTurnGateCannotAllocateEvidence() = fixture { f ->
        val gate = gate(f.host); gate.lock(); val before = f.files()
        val action = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.host.retainedRecoveryActions().buildSqliteCandidate() } }
        assertFalse(action.isCompleted)
        ApplicationWorkAdmission.closeForExit(); ApplicationWorkAdmission.reopenFromUser()
        gate.unlock()
        val failure = action.await().exceptionOrNull()
        assertTrue("Expected stale epoch denial, got $failure", failure?.message.orEmpty().contains("closed application session"))
        assertEquals(before, f.files()); assertTrue(f.captures().isEmpty())
    }

    @Test fun closedActualHostCannotBuildOrAllocateEvidence() = fixture { f ->
        f.host.closeAndAwait(); val before = f.files()
        assertNotNull(runCatching { f.host.retainedRecoveryActions().buildSqliteCandidate() }.exceptionOrNull())
        assertEquals(before, f.files()); assertTrue(f.captures().isEmpty())
    }

    @Test fun actualBuildWaitsForTrackedOwnerRetirementBeforeCapturingEvidence() = fixture { f ->
        val owner = WorkspaceAgentSessionLifetime(f.scope)
        val closeRequested = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
        val alive = AtomicBoolean(true); val closes = AtomicInteger()
        val process = object : HostedStdioProcess {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(byteArrayOf())
            override val stderr = ByteArrayInputStream(byteArrayOf())
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override fun close() { closes.incrementAndGet(); closeRequested.complete(Unit) }
            override suspend fun awaitExit(): Int { exited.await(); return 0 }
        }
        owner.processes.retain(process)
        check(owners(f.host).putIfAbsent("injected-owned-session", owner) == null)
        val before = f.files()
        val action = async { f.host.retainedRecoveryActions().buildSqliteCandidate() }
        try {
            withTimeout(5_000) { closeRequested.await() }
            assertTrue(owner.processes.isClosing); assertFalse(action.isCompleted)
            assertEquals(before, f.files()); assertTrue(f.captures().isEmpty())
        } finally { alive.set(false); exited.complete(Unit) }
        assertNotice(action.await()); assertTrue(owners(f.host).isEmpty()); assertEquals(1, closes.get())
        assertEquals(1, f.captures().size)
    }

    companion object {
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun journal(database: ByteArray, nonce: Int = 123): ByteArray {
            val encoded = ((database[16].toInt() and 255) shl 8) or (database[17].toInt() and 255)
            val size = if (encoded == 1) 65536 else encoded
            val bytes = ByteArray(512 + size + 8)
            byteArrayOf(0xd9.toByte(), 0xd5.toByte(), 5, 0xf9.toByte(), 0x20, 0xa1.toByte(), 0x63, 0xd7.toByte()).copyInto(bytes)
            fun put(offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).putInt(value) }
            put(8, 1); put(12, nonce); put(16, database.size / size); put(20, 512); put(24, size); put(512, 1)
            database.copyInto(bytes, 516, 0, size)
            var checksum = nonce.toLong(); var index = size - 200
            while (index > 0) { checksum = (checksum + (database[index].toInt() and 255)) and 0xffffffffL; index -= 200 }
            put(516 + size, checksum.toInt())
            return bytes
        }
    }
}
