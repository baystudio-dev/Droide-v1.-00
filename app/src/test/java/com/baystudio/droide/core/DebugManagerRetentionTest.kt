package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DebugManagerRetentionTest {
    private val original = """{"version":1,"breakpoints":{"Main.kt":[4]},"watches":["kept"]}""".toByteArray()

    private fun fixture(block: suspend CoroutineScope.(File, File, CoroutineScope) -> Unit) = runBlocking<Unit> {
        val root = Files.createTempDirectory("droide-debug-manager-retention-").toFile()
        val project = File(root, "workspace").apply { mkdirs(); File(this, "Main.kt").writeText("fun main() = Unit") }
        val state = File(root, "state").apply { mkdirs() }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(project, state, owner) }
        finally {
            owner.cancel()
            withTimeout(4_000) { owner.coroutineContext[Job]?.join() }
            root.deleteRecursively()
        }
    }

    @Test fun failedStartupRestoreReportsWarningAndRejectsEveryPersistentMutation() = fixture { root, state, owner ->
        val file = File(state, "project.bin").apply { writeBytes(original) }
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { _, _ -> throw IOException("temporary") })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        try {
            debugger.restoreState()
            assertEquals(DebugState.IDLE, debugger.state.value)
            assertTrue(debugger.output.value.contains("Automatic writes are paused"))
            assertFalse(debugger.persistenceStatus.value.writable)
            listOf<() -> Unit>(
                { debugger.addBreakpoint("Main.kt", 2) }, { debugger.toggleBreakpoint("Main.kt", 2) },
                { debugger.clearBreakpoint("Main.kt", 4) }, { debugger.addWatch("new") },
                { debugger.removeWatch("kept") }, { debugger.clearWatches() },
            ).forEach { mutation -> assertThrows(IllegalStateException::class.java, mutation) }
            assertTrue(debugger.breakpoints.value.isEmpty())
            assertTrue(debugger.watches.value.isEmpty())
            assertArrayEquals(original, file.readBytes())
        } finally { debugger.close() }
    }

    @Test fun explicitRetryInstallsRecoveredModelBeforeAllowingChanges() = fixture { root, state, owner ->
        File(state, "project.bin").writeBytes(original)
        val attempts = AtomicInteger()
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { bytes, _ ->
            if (attempts.incrementAndGet() == 1) throw IOException("temporary")
            bytes
        })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        try {
            debugger.restoreState()
            debugger.retryRestoreState()
            assertTrue(debugger.persistenceStatus.value.writable)
            assertEquals(listOf(4), debugger.breakpoints.value.getValue("Main.kt").map { it.requestedLine })
            assertEquals(listOf("kept"), debugger.watches.value.map { it.expression })
            debugger.addWatch("new")
            val reads = attempts.get()
            debugger.retryRestoreState()
            assertEquals(reads, attempts.get())
            assertEquals(listOf("kept", "new"), debugger.watches.value.map { it.expression })
        } finally { debugger.close() }
    }

    @Test fun retryInFlightRejectsNewMutationsAndPreservesRecoveredState() = fixture { root, state, owner ->
        File(state, "project.bin").writeBytes(original)
        val attempts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { bytes, _ ->
            if (attempts.incrementAndGet() == 1) throw IOException("temporary")
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            bytes
        })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        var retry: Deferred<Unit>? = null
        try {
            debugger.restoreState()
            val request = async(Dispatchers.Default) { debugger.retryRestoreState() }
            retry = request
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(debugger.persistenceStatus.value.retrying)
            assertThrows(IllegalStateException::class.java) { debugger.addWatch("must not replace recovered state") }
            release.countDown()
            request.await()
            assertEquals(listOf("kept"), debugger.watches.value.map { it.expression })
            assertTrue(debugger.persistenceStatus.value.writable)
        } finally {
            release.countDown(); retry?.cancel()
            withTimeout(4_000) { retry?.join() }
            debugger.close()
        }
    }
    @Test fun rejectedStoredPathPreservesWholeModelAndBlob() = fixture { root, state, owner ->
        val outside = File(state, "external.kt").apply { writeText("outside workspace") }
        val linked = File(root, "linked.kt")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val blob = "{\"version\":1,\"breakpoints\":{\"Main.kt\":[4],\"linked.kt\":[1]},\"watches\":[\"kept\"]}".toByteArray()
        val file = File(state, "project.bin").apply { writeBytes(blob) }
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { bytes, _ -> bytes })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        try {
            debugger.restoreState()
            assertFalse(debugger.persistenceStatus.value.writable)
            assertTrue(debugger.breakpoints.value.isEmpty())
            assertTrue(debugger.watches.value.isEmpty())
            assertTrue(runCatching { store.save(DebugPersistentState(emptyMap(), emptyList())) }.isFailure)
            assertArrayEquals(blob, file.readBytes())
            Files.delete(linked.toPath())
            debugger.retryRestoreState()
            assertTrue(debugger.persistenceStatus.value.writable)
            assertEquals(setOf("Main.kt", "linked.kt"), debugger.breakpoints.value.keys)
        } finally { debugger.close() }
    }

    @Test fun syntheticActiveSessionStateDefersRetryWithoutReadingOrMutating() = fixture { root, state, owner ->
        val file = File(state, "project.bin").apply { writeBytes(original) }
        val reads = AtomicInteger()
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { bytes, _ ->
            if (reads.incrementAndGet() == 1) throw IOException("temporary")
            bytes
        })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        try {
            debugger.restoreState()
            val field = DebugManager::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val synthetic = field.get(debugger) as kotlinx.coroutines.flow.MutableStateFlow<DebugState>
            for (active in listOf(DebugState.STARTING, DebugState.RUNNING, DebugState.STOPPED, DebugState.ERROR, DebugState.TERMINATED)) {
                synthetic.value = active
                val failure = runCatching { debugger.retryRestoreState() }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("Stop debugging"))
                assertEquals(1, reads.get())
                assertFalse(debugger.persistenceStatus.value.writable)
                assertArrayEquals(original, file.readBytes())
            }
            synthetic.value = DebugState.IDLE
            debugger.retryRestoreState()
            assertEquals(2, reads.get())
            assertTrue(debugger.persistenceStatus.value.writable)
        } finally { debugger.close() }
    }

    @Test fun duplicateRetryDoesNotQueueAnotherReadAfterFailure() = fixture { root, state, owner ->
        val file = File(state, "project.bin").apply { writeBytes(original) }
        val attempts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = DebugStateStore(state, "project", { bytes, _ -> bytes }, { _, _ ->
            if (attempts.incrementAndGet() > 1) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            throw IOException("still unavailable")
        })
        val debugger = DebugManager(owner, FileRepository(root), TerminalManager(owner, root), stateStore = store)
        var retry: Deferred<Unit>? = null
        try {
            debugger.restoreState()
            val request = async(Dispatchers.Default) { debugger.retryRestoreState() }
            retry = request
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val duplicate = async(start = CoroutineStart.UNDISPATCHED) { debugger.retryRestoreState() }
            assertTrue("Duplicate retry must return without waiting for the first", duplicate.isCompleted)
            duplicate.await()
            release.countDown()
            request.await()
            assertEquals(2, attempts.get())
            assertFalse(debugger.persistenceStatus.value.writable)
            assertArrayEquals(original, file.readBytes())
        } finally {
            release.countDown(); retry?.cancel()
            withTimeout(4_000) { retry?.join() }
            debugger.close()
        }
    }

}
