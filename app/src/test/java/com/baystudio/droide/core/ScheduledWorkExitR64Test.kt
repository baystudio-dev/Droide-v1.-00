package com.baystudio.droide.core

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.os.PersistableBundle
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ScheduledWorkExitR64Test {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = app.getSystemService(JobScheduler::class.java)
    private val store get() = ArtifactTransferStore(app)
    private lateinit var work: File
    private var originalPid = 0

    @Before fun openFixture() {
        originalPid = android.os.Process.myPid()
        ShadowProcess.setPid(4242)
        scheduler.cancelAll()
        File(app.filesDir, "artifact-transfers").deleteRecursively()
        File(app.filesDir, "chat-media-cleanup").deleteRecursively()
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
        work = File(app.filesDir, "projects/exit-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun closeFixture() {
        try {
            runBlocking {
                ApplicationWorkAdmission.closeForExit()
                scheduler.cancelAll()
                ArtifactTransferExitWorkers.snapshot().forEach { it.job.cancel(); it.job.join() }
                ChatMediaCleanupExitWorkers.snapshot().forEach { it.job.cancel(); it.job.join() }
                if (::work.isInitialized) work.deleteRecursively()
            }
        } finally { ShadowProcess.setPid(originalPid) }
    }

    @Test fun transferExitCancelsDurableAndPendingRequestsButPreservesTerminalResultsAndFiles() = runBlocking {
        val pending = store.ensure(spec("pending"))
        val durableOnly = store.ensure(spec("durable"))
        val succeeded = store.ensure(spec("success"))
        assertNotNull(store.markRunning(succeeded.id, succeeded.requestIdentity, "1".repeat(32)))
        assertEquals(ArtifactTransferState.SUCCEEDED, store.markSucceeded(succeeded.id, "1".repeat(32), 100)?.state)
        val failed = store.ensure(spec("failed"))
        assertNotNull(store.markRunning(failed.id, failed.requestIdentity, "2".repeat(32)))
        assertEquals(ArtifactTransferState.FAILED, store.markFailed(failed.id, "2".repeat(32), "Digest mismatch")?.state)
        val cache = File(app.cacheDir, "trusted-artifacts/exit-fixture.part").apply {
            parentFile!!.mkdirs(); writeText("verified partial sentinel")
        }
        UserInitiatedArtifactTransfer(app).schedule(pending.id)
        val expectedEpoch = ApplicationWorkAdmission.requireOpen()
        assertEquals(expectedEpoch, scheduler.getPendingJob(ArtifactTransferStore.jobId(pending.id))!!
            .extras.getString(ArtifactTransferStore.EXTRA_ADMISSION_EPOCH))
        ApplicationWorkAdmission.closeForExit()
        UserInitiatedArtifactTransfer.shutdownForExit(app)
        UserInitiatedArtifactTransfer.shutdownForExit(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertEquals(ArtifactTransferState.CANCELED, store.read(pending.id)!!.state)
        assertEquals(ArtifactTransferState.CANCELED, store.read(durableOnly.id)!!.state)
        assertEquals(ArtifactTransferState.SUCCEEDED, store.read(succeeded.id)!!.state)
        assertEquals(ArtifactTransferState.FAILED, store.read(failed.id)!!.state)
        assertEquals("Digest mismatch", store.read(failed.id)!!.detail)
        assertEquals("verified partial sentinel", cache.readText())
    }

    @Test fun aPendingJobWithoutAReadableRecordIsStillRemovedAndCorruptionIsReported() = runBlocking {
        val request = store.ensure(spec("corrupt"))
        UserInitiatedArtifactTransfer(app).schedule(request.id)
        val file = File(app.filesDir, "artifact-transfers/${request.id}.properties")
        file.writeText("invalid metadata")
        ApplicationWorkAdmission.closeForExit()
        val failure = runCatching { UserInitiatedArtifactTransfer.shutdownForExit(app) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.suppressed.any { it.message.orEmpty().contains("Could not read artifact") })
        assertNull(scheduler.getPendingJob(ArtifactTransferStore.jobId(request.id)))
        assertEquals("invalid metadata", file.readText())
    }

    @Test fun orphanSchedulerJobsAreIncludedWithoutRequiringADurableRecord() = runBlocking {
        val id = "3".repeat(32)
        val info = JobInfo.Builder(ArtifactTransferStore.jobId(id), ComponentName(app, UserInitiatedArtifactTransferJobService::class.java))
            .setMinimumLatency(1_000L)
            .setExtras(PersistableBundle().apply { putString(ArtifactTransferStore.EXTRA_TRANSFER_ID, id) }).build()
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(info))
        ApplicationWorkAdmission.closeForExit()
        UserInitiatedArtifactTransfer.shutdownForExit(app)
        assertNull(scheduler.getPendingJob(info.id))
    }

    @Test fun closedAdmissionRejectsSchedulingAndOldStartsStayRejectedAfterReopen() = runBlocking {
        val request = store.ensure(spec("stale"))
        UserInitiatedArtifactTransfer(app).schedule(request.id)
        val oldExtras = scheduler.getPendingJob(ArtifactTransferStore.jobId(request.id))!!.extras
        ApplicationWorkAdmission.closeForExit()
        assertTrue(runCatching { UserInitiatedArtifactTransfer(app).schedule(request.id) }.isFailure)
        UserInitiatedArtifactTransfer.shutdownForExit(app)
        ApplicationWorkAdmission.reopenFromUser()
        val fresh = store.ensure(spec("stale"))
        UserInitiatedArtifactTransfer(app).schedule(fresh.id)
        val service = Robolectric.buildService(UserInitiatedArtifactTransferJobService::class.java).create().get()
        assertFalse(service.onStartJob(params(oldExtras)))
        assertFalse(service.onStopJob(params(oldExtras)))
        assertEquals(fresh.requestIdentity, store.read(fresh.id)!!.requestIdentity)
        assertEquals(ArtifactTransferState.QUEUED, store.read(fresh.id)!!.state)
        assertTrue(ArtifactTransferExitWorkers.snapshot().isEmpty())
        service.onDestroy()
    }

    @Test fun staleNotificationCannotCancelAFreshRequestAfterReopen() = runBlocking {
        val old = store.ensure(spec("notification"))
        val oldAction = ArtifactTransferNotifications.build(app, old, true).actions.single().actionIntent
        val oldIntent = shadowOf(oldAction).savedIntent
        ApplicationWorkAdmission.closeForExit()
        UserInitiatedArtifactTransfer.shutdownForExit(app)
        ApplicationWorkAdmission.reopenFromUser()
        val fresh = store.ensure(spec("notification"))
        val freshAction = ArtifactTransferNotifications.build(app, fresh, true).actions.single().actionIntent
        assertNotEquals(oldAction, freshAction)
        ArtifactTransferCancelReceiver().onReceive(app, oldIntent)
        assertEquals(ArtifactTransferState.QUEUED, store.read(fresh.id)!!.state)
        ArtifactTransferCancelReceiver().onReceive(app, shadowOf(freshAction).savedIntent)
        assertEquals(ArtifactTransferState.CANCELED, store.read(fresh.id)!!.state)
    }

    @Test fun transferExitWaitsForRetiredWorkerFinallyAndReportsItsFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> })
        val worker = scope.launch {
            try { started.complete(Unit); awaitCancellation() }
            finally {
                withContext(NonCancellable) { enteredCleanup.complete(Unit); releaseCleanup.await() }
                throw IllegalStateException("Transfer finalizer failed")
            }
        }
        ArtifactTransferExitWorkers.track("4".repeat(32), ApplicationWorkAdmission.requireOpen(), worker)
        try {
            withTimeout(5_000L) { started.await() }
            ApplicationWorkAdmission.closeForExit()
            val shutdown = async { runCatching { UserInitiatedArtifactTransfer.shutdownForExit(app) } }
            withTimeout(5_000L) { enteredCleanup.await() }
            assertFalse(shutdown.isCompleted)
            releaseCleanup.complete(Unit)
            val failure = withTimeout(5_000L) { shutdown.await().exceptionOrNull() }
            assertNotNull(failure)
            assertTrue(failure!!.suppressed.any { it.message == "Transfer finalizer failed" })
            assertTrue(worker.isCompleted)
        } finally { releaseCleanup.complete(Unit); scope.cancel(); worker.join() }
    }

    @Test fun mediaExitPreservesRegistryAndAttachmentThenOnlyFreshEpochCanResume() = runBlocking {
        val media = ChatMediaStore(work)
        val ref = media.import("session", "keep.txt", "text/plain", ByteArrayInputStream("retained".toByteArray()))
        assertNull(ChatMediaCleanup.schedule(app, work))
        val oldExtras = scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!.extras
        val registry = File(app.filesDir, "chat-media-cleanup/workspaces.properties")
        val before = registry.readBytes()
        ApplicationWorkAdmission.closeForExit()
        ChatMediaCleanup.shutdownForExit(app)
        ChatMediaCleanup.shutdownForExit(app)
        assertNull(scheduler.getPendingJob(ChatMediaCleanup.JOB_ID))
        assertArrayEquals(before, registry.readBytes())
        assertEquals("retained", media.file(ref)!!.readText())
        assertNotNull(ChatMediaCleanup.resume(app))
        assertNull(scheduler.getPendingJob(ChatMediaCleanup.JOB_ID))
        ApplicationWorkAdmission.reopenFromUser()
        val service = Robolectric.buildService(ChatMediaCleanupJobService::class.java).create().get()
        assertFalse(service.onStartJob(params(oldExtras)))
        assertFalse(service.onStopJob(params(oldExtras)))
        assertNull(ChatMediaCleanup.resume(app))
        assertEquals(ApplicationWorkAdmission.requireOpen(), scheduler.getPendingJob(ChatMediaCleanup.JOB_ID)!!
            .extras.getString(ChatMediaCleanup.EXTRA_ADMISSION_EPOCH))
        service.onDestroy()
    }

    @Test fun mediaExitWaitsForWorkerCancellationFinalization() = runBlocking {
        val enteredCleanup = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val worker = scope.launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { enteredCleanup.complete(Unit); releaseCleanup.await() } }
        }
        ChatMediaCleanupExitWorkers.track(worker)
        try {
            withTimeout(5_000L) { started.await() }
            ApplicationWorkAdmission.closeForExit()
            val shutdown = async { ChatMediaCleanup.shutdownForExit(app) }
            withTimeout(5_000L) { enteredCleanup.await() }
            assertFalse(shutdown.isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(5_000L) { shutdown.await() }
            assertTrue(worker.isCompleted)
        } finally { releaseCleanup.complete(Unit); scope.cancel(); worker.join() }
    }

    private fun params(extras: PersistableBundle): JobParameters = ReflectionHelpers.newInstance(JobParameters::class.java).also {
        ReflectionHelpers.setField(it, "extras", extras)
    }

    private fun spec(name: String) = TrustedArtifactSpec(
        "exit-$name", "https://8.8.8.8/$name.bin", "a".repeat(64), "$name.bin", maxBytes = 100L, expectedBytes = 100L,
    )
}
