package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ArtifactTransferExitOwnershipR64Test {
    @Test fun exitEnumeratesOnlyTransferMetadataAndPreservesTerminalResultsAndPartialBytes() {
        val root = Files.createTempDirectory("transfer-exit-r64").toFile()
        try {
            val store = ArtifactTransferStore(root, 1, "a".repeat(32))
            val running = store.ensure(spec("running"))
            store.markRunning(running.id, running.requestIdentity, "b".repeat(32))
            store.markProgress(running.id, "b".repeat(32), TrustedArtifactDownloadProgress(40, 100))
            val success = store.ensure(spec("success"))
            store.markRunning(success.id, success.requestIdentity, "c".repeat(32))
            store.markSucceeded(success.id, "c".repeat(32), 100)
            val failed = store.ensure(spec("failed"))
            store.markRunning(failed.id, failed.requestIdentity, "d".repeat(32))
            store.markFailed(failed.id, "d".repeat(32), "Digest mismatch")
            File(root, ".staged.tmp").writeText("keep")
            val partial = File(root, "fixture.part").apply { writeText("keep partial bytes") }
            assertEquals(setOf(running.id, success.id, failed.id), store.idsForExit().toSet())
            store.idsForExit().forEach { store.markCanceledForExit(it, "Exit") }
            val canceled = store.read(running.id)!!
            assertEquals(ArtifactTransferState.CANCELED, canceled.state)
            assertEquals(40L, canceled.bytesDownloaded)
            assertNull(canceled.ownerWorkerIdentity)
            assertNull(store.markRetry(running.id, "b".repeat(32), "late callback"))
            assertEquals(ArtifactTransferState.SUCCEEDED, store.read(success.id)!!.state)
            assertEquals(ArtifactTransferState.FAILED, store.read(failed.id)!!.state)
            assertEquals("Digest mismatch", store.read(failed.id)!!.detail)
            assertEquals("keep partial bytes", partial.readText())
            val fresh = store.ensure(spec("running"))
            assertNotEquals(running.requestIdentity, fresh.requestIdentity)
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptMetadataIsReportedWithoutDeletingItOrOtherRecords() {
        val root = Files.createTempDirectory("transfer-corrupt-r64").toFile()
        try {
            val store = ArtifactTransferStore(root, 1, "a".repeat(32))
            val id = "f".repeat(32)
            val file = File(root, "$id.properties").apply { writeText("corrupt") }
            assertEquals(listOf(id), store.idsForExit())
            assertTrue(runCatching { store.markCanceledForExit(id, "Exit") }.isFailure)
            assertEquals("corrupt", file.readText())
            assertNull(store.markCanceledForExit("e".repeat(32), "Exit"))
        } finally { root.deleteRecursively() }
    }

    @Test fun transferTrackerRetainsCanceledRegistryWorkersUntilTheirFinallyFinishes() = runBlocking {
        val registry = ArtifactTransferWorkerRegistry<String>()
        val entered = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val worker = registry.start("id", "request") {
            launch {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); finish.await() } }
            }
        }!!
        ArtifactTransferExitWorkers.track("id", "epoch", worker.job)
        try {
            withTimeout(5_000L) { started.await() }
            registry.cancel("id", "request")
            withTimeout(5_000L) { entered.await() }
            assertEquals(0, registry.size())
            assertTrue(ArtifactTransferExitWorkers.snapshot().any { it.job === worker.job })
            finish.complete(Unit)
            withTimeout(5_000L) { worker.job.join() }
            assertFalse(ArtifactTransferExitWorkers.snapshot().any { it.job === worker.job })
        } finally { finish.complete(Unit); worker.job.cancel(); worker.job.join() }
    }

    @Test fun mediaTrackerRetainsCanceledWorkersUntilTheirFinallyFinishes() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val worker = launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { entered.complete(Unit); finish.await() } }
        }
        ChatMediaCleanupExitWorkers.track(worker)
        try {
            withTimeout(5_000L) { started.await() }
            worker.cancel()
            withTimeout(5_000L) { entered.await() }
            assertTrue(ChatMediaCleanupExitWorkers.snapshot().any { it.job === worker })
            finish.complete(Unit)
            withTimeout(5_000L) { worker.join() }
            assertFalse(ChatMediaCleanupExitWorkers.snapshot().any { it.job === worker })
        } finally { finish.complete(Unit); worker.cancel(); worker.join() }
    }

    private fun spec(name: String) = TrustedArtifactSpec("exit-$name", "https://8.8.8.8/$name.bin", "a".repeat(64),
        "$name.bin", 100L, 100L)
}
