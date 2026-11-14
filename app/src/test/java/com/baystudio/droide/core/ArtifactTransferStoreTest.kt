package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactTransferStoreTest {
    private val oldWorker = "1".repeat(32)
    private val newWorker = "2".repeat(32)

    @Test fun integrityProgressRemainsVisibleEvenAfterAllNetworkBytesHaveArrived() = withStore { store, _ ->
        val request = store.ensure(spec())
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(100, 100,
            detail = "Verifying downloaded archive 0 / 100 KiB…"))
        assertEquals(ArtifactTransferState.RUNNING, store.read(request.id)?.state)
        assertEquals("Verifying downloaded archive 0 / 100 KiB…", store.read(request.id)?.detail)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(100, 100,
            detail = "Verifying downloaded archive 100 / 100 KiB…"))
        assertTrue(store.read(request.id)!!.detail.contains("100 / 100"))
        store.markSucceeded(request.id, oldWorker, 100)
        assertNull(store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(100, 100, detail = "late")))
        assertEquals(ArtifactTransferState.SUCCEEDED, store.read(request.id)?.state)
    }

    @Test fun aRejectedScheduleIsDurableAndAnExplicitRetryGetsANewIdentity() = withStore { store, root ->
        val request = store.ensure(spec())
        val detail = "android.permission.ACCESS_NETWORK_STATE required for jobs with a connectivity constraint"
        assertNotNull(store.markSchedulingFailed(request.id, request.requestIdentity, detail))
        val reopened = ArtifactTransferStore(root, 2, "b".repeat(32))
        val failed = checkNotNull(reopened.read(request.id))
        assertEquals(ArtifactTransferState.FAILED, failed.state)
        assertEquals(detail, failed.detail)
        assertEquals(0, failed.attempts)
        assertNull(failed.ownerWorkerIdentity)
        val retry = reopened.ensure(spec())
        assertEquals(ArtifactTransferState.QUEUED, retry.state)
        assertNotEquals(request.requestIdentity, retry.requestIdentity)
        assertTrue(retry.detail.startsWith("Queued"))
        assertNull(reopened.markSchedulingFailed(request.id, request.requestIdentity, "Late rejected schedule"))
        assertEquals(retry, reopened.read(request.id))
    }

    @Test fun scheduleFailureCannotOverwriteAClaimedWorkerOrTerminalAcknowledgment() = withStore { store, _ ->
        for (state in listOf(ArtifactTransferState.RUNNING, ArtifactTransferState.SUCCEEDED,
            ArtifactTransferState.FAILED, ArtifactTransferState.CANCELED)) {
            val request = store.ensure(spec())
            store.markRunning(request.id, request.requestIdentity, oldWorker)
            when (state) {
                ArtifactTransferState.SUCCEEDED -> store.markSucceeded(request.id, oldWorker, 100)
                ArtifactTransferState.FAILED -> store.markFailed(request.id, oldWorker, "Worker failed")
                ArtifactTransferState.CANCELED -> store.markCanceled(request.id, "User stopped")
                else -> Unit
            }
            val expected = checkNotNull(store.read(request.id))
            assertNull(store.markSchedulingFailed(request.id, request.requestIdentity, "Late scheduler failure"))
            assertEquals(expected, store.read(request.id))
            if (state == ArtifactTransferState.RUNNING) store.markCanceled(request.id, "Next fixture")
        }
    }

    @Test fun scheduleFailureKeepsResumeBytesAndBoundsItsPersistedDiagnostic() = withStore { store, _ ->
        val request = store.ensure(spec())
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(40, 100))
        store.markRetry(request.id, oldWorker, "Connection interrupted")
        val failed = checkNotNull(store.markSchedulingFailed(request.id, request.requestIdentity, "x".repeat(2_000)))
        assertEquals(ArtifactTransferState.FAILED, failed.state)
        assertEquals(40L, failed.bytesDownloaded)
        assertEquals(100L, failed.expectedBytes)
        assertEquals(500, failed.detail.length)
        assertEquals(failed, store.read(request.id))
    }

    @Test fun anOldWorkerCannotWriteOverTheNewRunningGeneration() = withStore { store, _ ->
        val request = store.ensure(spec())
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markRunning(request.id, request.requestIdentity, newWorker)
        store.markProgress(request.id, newWorker, TrustedArtifactDownloadProgress(20, 100))
        val expected = checkNotNull(store.read(request.id))
        assertNull(store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(80, 100)))
        assertNull(store.markSucceeded(request.id, oldWorker, 100))
        assertNull(store.markFailed(request.id, oldWorker, "Old worker failed"))
        assertNull(store.markRetry(request.id, oldWorker, "Old worker canceled"))
        assertEquals(expected, store.read(request.id))
        assertNotNull(store.markSucceeded(request.id, newWorker, 100))
        assertEquals(ArtifactTransferState.SUCCEEDED, store.read(request.id)?.state)
    }

    @Test fun oldQueuedStartAndStopCommandsCannotAffectAnExplicitRetry() = withStore { store, _ ->
        val old = store.ensure(spec())
        store.markRunning(old.id, old.requestIdentity, oldWorker)
        store.markCanceled(old.id, "caller canceled")
        val retry = store.ensure(spec())
        assertNotEquals(old.requestIdentity, retry.requestIdentity)
        assertNotNull(store.markRunning(retry.id, retry.requestIdentity, newWorker))
        val expected = checkNotNull(store.read(retry.id))
        assertNull(store.markRunning(old.id, old.requestIdentity, oldWorker))
        assertFalse(store.markCanceledIfRequest(old.id, old.requestIdentity, "Old Android stop"))
        assertNull(store.markRetry(old.id, oldWorker, "Old worker cleanup"))
        assertEquals(expected, store.read(retry.id))
        assertTrue(store.markCanceledIfRequest(retry.id, retry.requestIdentity, "Current Android stop"))
        assertEquals(ArtifactTransferState.CANCELED, store.read(retry.id)?.state)
    }

    @Test fun terminalAcknowledgmentCannotBeOverwrittenByWorkerCleanup() = withStore { store, _ ->
        for (state in listOf(ArtifactTransferState.SUCCEEDED, ArtifactTransferState.FAILED, ArtifactTransferState.CANCELED)) {
            val request = store.ensure(spec())
            store.markRunning(request.id, request.requestIdentity, oldWorker)
            when (state) {
                ArtifactTransferState.SUCCEEDED -> store.markSucceeded(request.id, oldWorker, 100)
                ArtifactTransferState.FAILED -> store.markFailed(request.id, oldWorker, "failure")
                else -> store.markCanceled(request.id, "user canceled")
            }
            assertNull(store.markRetry(request.id, oldWorker, "Late finally"))
            assertEquals(state, store.read(request.id)?.state)
        }
    }

    @Test fun interruptionRecoveryKeepsTheRequestAndInvalidatesTheDeadWorkersWrites() = withStore { store, root ->
        val request = store.ensure(spec())
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(40, 100))
        val restartedProcess = ArtifactTransferStore(root, 2, "b".repeat(32))
        restartedProcess.recoverStaleRunning(request.id)
        val recovered = checkNotNull(restartedProcess.read(request.id))
        assertEquals(ArtifactTransferState.QUEUED, recovered.state)
        assertEquals(request.requestIdentity, recovered.requestIdentity)
        assertEquals(40L, recovered.bytesDownloaded)
        assertNull(recovered.ownerWorkerIdentity)
        assertNull(store.markFailed(request.id, oldWorker, "dead process failed"))
        assertNotNull(restartedProcess.markRunning(request.id, request.requestIdentity, newWorker))
        assertEquals(newWorker, restartedProcess.read(request.id)?.ownerWorkerIdentity)
    }

    @Test fun aLegacySchemaTwoRecordRemainsReadableAndGainsARequestIdentity() = withStore { store, root ->
        val request = store.ensure(spec())
        val file = File(root, "${request.id}.properties")
        val legacy = Properties().apply { file.inputStream().use { load(it) } }
        legacy.remove("requestIdentity")
        legacy.remove("ownerWorkerIdentity")
        legacy.remove("expectedBytes")
        file.outputStream().use { legacy.store(it, null) }
        assertNull(store.read(request.id)?.requestIdentity)
        val recovered = store.ensure(spec())
        assertTrue(checkNotNull(recovered.requestIdentity).matches(Regex("[0-9a-f]{32}")))
        assertEquals(ArtifactTransferState.QUEUED, recovered.state)
        assertEquals(100L, recovered.expectedBytes)
        assertNotNull(store.markRunning(request.id, recovered.requestIdentity, oldWorker))
    }

    @Test fun migrationDoesNotInvalidateALiveLegacyAndroidCallback() = withStore { store, root ->
        val request = store.ensure(spec())
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        val file = File(root, "${request.id}.properties")
        val legacy = Properties().apply { file.inputStream().use { load(it) } }
        legacy.remove("requestIdentity")
        legacy.remove("expectedBytes")
        file.outputStream().use { legacy.store(it, null) }
        val joined = store.ensure(spec())
        assertNull(joined.requestIdentity)
        assertEquals(oldWorker, joined.ownerWorkerIdentity)
        assertTrue(store.markCanceledIfRequest(request.id, null, "Legacy Android user stop"))
        assertNotNull(store.ensure(spec()).requestIdentity)
    }

    @Test fun unknownExpectedLengthRemainsUnknownAfterProgressAndExplicitRetry() = withStore { store, _ ->
        val descriptor = spec(expectedBytes = null)
        val request = store.ensure(descriptor)
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(40, 100))
        val joined = store.ensure(descriptor)
        assertEquals(100L, joined.totalBytes)
        assertNull(joined.expectedBytes)
        assertNull(joined.descriptor().expectedBytes)
        store.markFailed(request.id, oldWorker, "temporary failure")
        val retry = store.ensure(descriptor)
        assertNull(retry.expectedBytes)
        assertNull(retry.totalBytes)
        assertNotEquals(request.requestIdentity, retry.requestIdentity)
        assertNotNull(store.markRunning(retry.id, retry.requestIdentity, newWorker))
    }

    @Test fun legacyObservedLengthDoesNotFalselyRejectItsOriginalUnknownLengthDescriptor() = withStore { store, root ->
        val descriptor = spec(expectedBytes = null)
        val request = store.ensure(descriptor)
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(40, 100))
        val file = File(root, "${request.id}.properties")
        val legacy = Properties().apply { file.inputStream().use { load(it) } }
        legacy.remove("expectedBytes")
        file.outputStream().use { legacy.store(it, null) }
        val migrated = store.ensure(descriptor)
        assertNull(migrated.expectedBytes)
        assertEquals(100L, migrated.totalBytes)
        assertTrue(migrated.hasStoredExpectedBytes)
        store.markCanceled(request.id, "retry")
        assertNull(store.ensure(descriptor).expectedBytes)
    }

    @Test fun aKnownExpectedLengthMismatchStillRejectsTheStoredRecord() = withStore { store, root ->
        val request = store.ensure(spec())
        val file = File(root, "${request.id}.properties")
        val altered = Properties().apply { file.inputStream().use { load(it) } }
        altered.setProperty("expectedBytes", "90")
        file.outputStream().use { altered.store(it, null) }
        try {
            store.ensure(spec())
            error("Expected immutable-length mismatch")
        } catch (failure: IllegalStateException) {
            assertEquals("Check failed.", failure.message)
        }
        assertEquals(90L, store.read(request.id)?.expectedBytes)
    }

    @Test fun aBackgroundLegacyWorkerDoesNotSealObservedLengthAsAnImmutableExpectation() = withStore { store, root ->
        val descriptor = spec(expectedBytes = null)
        val request = store.ensure(descriptor)
        store.markRunning(request.id, request.requestIdentity, oldWorker)
        store.markProgress(request.id, oldWorker, TrustedArtifactDownloadProgress(40, 100))
        val file = File(root, "${request.id}.properties")
        val legacy = Properties().apply { file.inputStream().use { load(it) } }
        legacy.remove("expectedBytes")
        file.outputStream().use { legacy.store(it, null) }
        val resumed = checkNotNull(store.markRunning(request.id, request.requestIdentity, newWorker))
        assertNull(resumed.descriptor().expectedBytes)
        store.markProgress(request.id, newWorker, TrustedArtifactDownloadProgress(50, 100))
        assertNull(store.ensure(descriptor).expectedBytes)
        assertEquals(100L, store.read(request.id)?.totalBytes)
    }

    private inline fun withStore(block: (ArtifactTransferStore, File) -> Unit) {
        val root = Files.createTempDirectory("droide-transfer-state-").toFile()
        try { block(ArtifactTransferStore(root, 1, "a".repeat(32)), root) }
        finally { root.deleteRecursively() }
    }

    // These tests exercise file-backed transfer state; URL/DNS policy has separate coverage.
    private fun spec(expectedBytes: Long? = 100L): TrustedArtifactDescriptor = object : TrustedArtifactDescriptor {
        override val id = "test-artifact"
        override val url = "https://example.com/test-artifact.bin"
        override val fileName = "test-artifact.bin"
        override val maxBytes = 100L
        override val expectedBytes = expectedBytes
        override val digestAlgorithm = "SHA-256"
        override val digestHex = "a".repeat(64)
        override fun validateDescriptor() = validateStructure()
    }
}
