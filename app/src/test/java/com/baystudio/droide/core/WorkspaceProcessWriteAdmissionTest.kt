package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceProcessWriteAdmissionTest {
    private class Peer : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        @Volatile var alive = true
        var failure: Throwable? = null
        val completion = CompletableDeferred<Unit>()
        override val isAlive get() = alive
        override val exitCode get() = if (alive) null else 0
        override fun close() { alive = false }
        override suspend fun awaitExit(): Int { completion.await(); failure?.let { throw it }; return 0 }
        suspend fun confirm() {
            alive = false; failure = null; completion.complete(Unit)
            awaitExit(); WorkspaceProcessWriteAdmission.confirmCompletion(this)
        }
    }
    private fun fixture(action: suspend CoroutineScope.(File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("workspace-process-writer-").toFile()
        try { withTimeout(8_000) { action(root) } }
        finally { root.deleteRecursively() }
    }
    private fun busy(root: File) {
        assertTrue("An unconfirmed process must block capture", runCatching { ExportWriterAdmission.beginCapture(root).close() }.isFailure)
    }

    @Test fun factoryRootOverlapIncludesFilesystemRootAndUsesPathSegments() {
        assertTrue(WorkspaceProcessWriteAdmission.factoryRootsOverlap("/", "/tmp/workspace"))
        assertTrue(WorkspaceProcessWriteAdmission.factoryRootsOverlap("/tmp/workspace", "/"))
        assertTrue(WorkspaceProcessWriteAdmission.factoryRootsOverlap("/", "/"))
        assertTrue(WorkspaceProcessWriteAdmission.factoryRootsOverlap("/tmp/workspace", "/tmp/workspace/child"))
        assertFalse(WorkspaceProcessWriteAdmission.factoryRootsOverlap("/tmp/workspace", "/tmp/workspace-other"))
    }

    @Test(timeout = 10_000) fun launchIsRejectedBeforeCreationWhileCaptureOwnsTheRoot() = fixture { root ->
        var created = false
        ExportWriterAdmission.beginCapture(root).use {
            assertTrue(runCatching { WorkspaceProcessWriteAdmission.create(root) { created = true; Peer() } }.isFailure)
        }
        assertFalse(created)
        val peer = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        try { busy(root) } finally { peer.confirm() }
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun deadProcessAndFailedReceiptDoNotReleaseWriterOwnership() = fixture { root ->
        val peer = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        try {
            peer.close(); busy(root)
            peer.failure = IllegalStateException("lease cleanup failed"); peer.completion.complete(Unit)
            assertTrue(runCatching { peer.awaitExit() }.isFailure)
            busy(root)
        } finally { peer.confirm() }
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun factoryExceptionWithoutHandlePreservesUnconfirmedWriterTicket() = fixture { root ->
        assertTrue(runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(root) { error("spawn failed") } }.isFailure)
        busy(root)
        var entered = false
        assertTrue(runCatching { WorkspaceProcessWriteAdmission.create(root) { entered = true; Peer() } }.isFailure)
        assertFalse(entered)
        val nested = root.resolve("nested").apply { mkdir() }
        assertTrue(runCatching { WorkspaceProcessWriteAdmission.create(nested) { entered = true; Peer() } }.isFailure)
        assertFalse(entered)
    }

    @Test(timeout = 10_000) fun rejectionBeforeFactoryDoesNotLeaveAWriterTicket() = fixture { root ->
        var entered = false
        assertTrue(runCatching {
            WorkspaceProcessWriteAdmission.create(root, beforeFactory = { error("pre-start rejection") }) {
                entered = true; Peer()
            }
        }.isFailure)
        assertFalse(entered); ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun concurrentUnknownFactoryBlocksWaitingAdmissionButNotOtherWorkspace() = fixture { root ->
        val spawned = CountDownLatch(1); val failFactory = CountDownLatch(1)
        val validating = CountDownLatch(1); val finishValidation = CountDownLatch(1)
        val child = java.util.concurrent.atomic.AtomicReference<Process?>()
        val other = File(root.parentFile, root.name + "-isolated").apply { mkdir() }
        val existing = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        var waitingFactoryEntered = false
        try {
            val failure = async(Dispatchers.IO) { runCatching {
                WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(root) {
                    child.set(ProcessBuilder("/bin/sh", "-c", "exec sleep 30").directory(root).start())
                    spawned.countDown(); check(failFactory.await(5, TimeUnit.SECONDS))
                    error("post-spawn factory failure")
                }
            }.exceptionOrNull() }
            assertTrue(withContext(Dispatchers.IO) { spawned.await(3, TimeUnit.SECONDS) })
            val waiting = async(Dispatchers.IO) { runCatching {
                WorkspaceProcessWriteAdmission.create(root, beforeFactory = {
                    validating.countDown(); check(finishValidation.await(5, TimeUnit.SECONDS))
                }) { waitingFactoryEntered = true; Peer() }
            }.exceptionOrNull() }
            assertTrue(withContext(Dispatchers.IO) { validating.await(3, TimeUnit.SECONDS) })
            failFactory.countDown(); assertEquals("post-spawn factory failure", failure.await()?.message)
            finishValidation.countDown(); assertNotNull(waiting.await()); assertFalse(waitingFactoryEntered)
            assertTrue(checkNotNull(child.get()).isAlive)
            existing.confirm(); busy(root) // exact old completion cannot clear uncertainty
            val isolated = WorkspaceProcessWriteAdmission.create(other) { Peer() }
            isolated.confirm(); ExportWriterAdmission.beginCapture(other).close()
            assertTrue(runCatching { WorkspaceProcessWriteAdmission.create(checkNotNull(root.parentFile)) { Peer() } }.isFailure)
        } finally {
            failFactory.countDown(); finishValidation.countDown()
            child.get()?.let { it.destroyForcibly(); check(it.waitFor(5, TimeUnit.SECONDS)) }
            existing.confirm(); other.deleteRecursively()
        }
    }

    @Test(timeout = 10_000) fun cancellationBeforeFactoryReleasesOnlyUninvokedAdmission() = fixture { root ->
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val custody = object : WorkspaceProcessCustody() {
            override fun acquire(root: File) = object : WorkspaceProcessCustody.Receipt() {
                override fun enterFactory() = Unit
                override fun confirmCleanupOrAbort() { closes.incrementAndGet() }
            }
        }
        var entered = false
        val failure = runCatching {
            WorkspaceProcessWriteAdmission.create(root, custody,
                beforeFactory = { throw CancellationException("cancelled before factory") }) { entered = true; Peer() }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertFalse(entered); assertEquals(1, closes.get())
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun cancellationInsideFactoryCannotConfirmCleanup() = fixture { root ->
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val custody = object : WorkspaceProcessCustody() {
            override fun acquire(root: File) = object : WorkspaceProcessCustody.Receipt() {
                override fun enterFactory() = Unit
                override fun confirmCleanupOrAbort() { closes.incrementAndGet() }
            }
        }
        val failure = runCatching {
            WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(root, custody) {
                throw CancellationException("factory cancelled after possible spawn")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertEquals(0, closes.get()); busy(root)
    }

    @Test(timeout = 10_000) fun rejectedPreFactoryWithFailedCustodyReceiptKeepsAnOwnedCleanupRetry() = fixture { root ->
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val retry = CompletableDeferred<Unit>()
        val custody = object : WorkspaceProcessCustody() {
            override fun acquire(root: File) = object : WorkspaceProcessCustody.Receipt() {
                override fun enterFactory() = Unit
                override fun confirmCleanupOrAbort() {
                    if (attempts.incrementAndGet() == 1) error("Receipt persistence failed")
                    retry.complete(Unit)
                }
            }
        }
        val failure = runCatching { WorkspaceProcessWriteAdmission.create<HostedStdioProcess>(root, custody,
            beforeFactory = { error("Process creation failed") }) { error("Unexpected factory entry") } }.exceptionOrNull()
        assertEquals("Process creation failed", failure?.message)
        assertTrue(failure!!.suppressed.any { it.message == "Receipt persistence failed" })
        retry.await()
        withTimeout(3_000) { while (ExportWriterAdmission.snapshotBlockers(root).isNotEmpty()) yield() }
        ExportWriterAdmission.beginCapture(root).close()
        assertEquals(2, attempts.get())
    }

    @Test(timeout = 10_000) fun oldCompletionCannotReleaseANewerProcessOrAnotherWorkspace() = fixture { root ->
        val other = File(root.parentFile, root.name + "-other").apply { mkdir() }
        val first = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        val next = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        val sibling = WorkspaceProcessWriteAdmission.create(other) { Peer() }
        try {
            first.confirm(); WorkspaceProcessWriteAdmission.confirmCompletion(first)
            busy(root); busy(other)
            next.confirm(); ExportWriterAdmission.beginCapture(root).close(); busy(other)
        } finally { first.confirm(); next.confirm(); sibling.confirm(); other.deleteRecursively() }
    }

    @Test(timeout = 10_000) fun actualCaptureKeepsFenceAfterRootExitUntilCleanupFinishes() = fixture { root ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val capture = async {
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), root, cleanup = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            })
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
            busy(root); assertFalse(capture.isCompleted)
        } finally { release.countDown() }
        assertEquals(0, capture.await().exitCode)
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun cancelledAdmissionHandoffStillReapsAndReleasesTheExactCapture() = fixture { root ->
        val born = CompletableDeferred<Unit>()
        val task = launch {
            LocalProcessSupervisor.captureWithAdmission(listOf("/bin/sh", "-c", "exec sleep 30"), root,
                emptyMap(), 1_024, 0, admitStart = { create ->
                    create(); born.complete(Unit); awaitCancellation()
                })
        }
        born.await(); busy(root)
        task.cancelAndJoin()
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun actualCaptureCleanupFailureStaysVisibleAndKeepsTheFence() = fixture { root ->
        val failure = runCatching {
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exit 0"), root,
                cleanup = { error("Capture lease cleanup failed") })
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message.orEmpty().contains("Capture lease cleanup failed"))
        busy(root)
        // The process is dead, but its failed receipt deliberately remains owned. This class runs in a
        // fresh JVM; no test clears a failed production ownership ledger.
    }

    @Test(timeout = 10_000) fun sessionCleanupReleasesOnlyItsConfirmedWriterBeforeReturning() = fixture { root ->
        val owner = SessionHostedProcessOwner()
        val peer = WorkspaceProcessWriteAdmission.create(root) { Peer() }.also(owner::retain)
        val sibling = WorkspaceProcessWriteAdmission.create(root) { Peer() }
        val stopped = async { owner.stopAndAwait() }
        try {
            yield(); busy(root); assertFalse(stopped.isCompleted)
            peer.completion.complete(Unit)
            stopped.await()
            busy(root)
        } finally { peer.confirm(); sibling.confirm() }
        ExportWriterAdmission.beginCapture(root).close()
    }

    @Test(timeout = 10_000) fun changedCwdDoesNotEraseOwnershipOfAnOpenWorkspaceDescriptor() = fixture { root ->
        root.resolve("held").writeText("retained")
        val ready = CompletableDeferred<Unit>()
        val task = launch {
            LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", "exec 3<>held; cd /; printf ready; exec sleep 30"), root,
                timeoutMs = 0, onOutput = { if (it.contains("ready")) ready.complete(Unit) })
        }
        try {
            ready.await()
            assertTrue(WorkspaceProcessPathGuard.occupants(root).isEmpty())
            busy(root)
        } finally { task.cancelAndJoin() }
        ExportWriterAdmission.beginCapture(root).close()
        assertEquals("retained", root.resolve("held").readText())
    }
}
