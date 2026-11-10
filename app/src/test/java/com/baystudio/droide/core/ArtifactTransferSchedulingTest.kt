package com.baystudio.droide.core

import android.Manifest
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import com.baystudio.droide.R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowJobScheduler.ShadowJobSchedulerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtifactTransferSchedulingTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = app.getSystemService(JobScheduler::class.java)
    private val store get() = ArtifactTransferStore(app)

    @Before fun clearFixtureState() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
        scheduler.cancelAll()
        java.io.File(app.filesDir, "artifact-transfers").deleteRecursively()
        java.io.File(app.cacheDir, "trusted-artifacts").deleteRecursively()
    }

    @Test fun mergedApplicationManifestDeclaresAllNetworkJobPermissions() {
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.RUN_USER_INITIATED_JOBS)) {
            assertTrue("Missing merged-manifest permission $permission", permission in permissions)
        }
    }

    @Test fun jobServiceIsEnabledOnNewAndroidAndRetainsItsSystemBindingPermission() {
        val service = app.packageManager.getServiceInfo(
            ComponentName(app, UserInitiatedArtifactTransferJobService::class.java), 0,
        )
        assertTrue(service.enabled)
        assertFalse(service.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        assertTrue(app.resources.getBoolean(R.bool.user_initiated_transfer_jobs_enabled))
    }

    @Test @Config(sdk = [29]) fun oldAndroidUsesTheDeclaredDataSyncForegroundService() {
        assertFalse(app.resources.getBoolean(R.bool.user_initiated_transfer_jobs_enabled))
        val request = store.ensure(spec())
        UserInitiatedArtifactTransfer(app).schedule(request.id)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        val intent = checkNotNull(shadowOf(app).nextStartedService)
        assertEquals(ComponentName(app, PackageTransferForegroundService::class.java), intent.component)
        assertEquals(request.id, intent.getStringExtra(ArtifactTransferStore.EXTRA_TRANSFER_ID))
        assertEquals(request.requestIdentity, intent.getStringExtra(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY))
        val service = app.packageManager.getServiceInfo(intent.component!!, 0)
        assertFalse(service.exported)
        assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }

    @Test fun sha256ArtifactUsesAUserInitiatedInternetJobAndItsImmutableRequestIdentity() {
        val request = store.ensure(spec(expectedBytes = 100L))
        UserInitiatedArtifactTransfer(app).schedule(request.id)
        val job = checkNotNull(scheduler.getPendingJob(ArtifactTransferStore.jobId(request.id)))
        assertEquals(ComponentName(app, UserInitiatedArtifactTransferJobService::class.java), job.service)
        assertTrue(job.isUserInitiated)
        assertFalse(job.isExpedited)
        assertFalse(job.isPersisted)
        assertTrue(checkNotNull(job.requiredNetwork).hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertEquals(request.id, job.extras.getString(ArtifactTransferStore.EXTRA_TRANSFER_ID))
        assertEquals(request.requestIdentity, job.extras.getString(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY))
        assertEquals(100L, job.estimatedNetworkDownloadBytes)
        assertEquals(0L, job.estimatedNetworkUploadBytes)
        assertEquals(100L, job.minimumNetworkChunkBytes)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        assertEquals(ArtifactTransferState.QUEUED, store.read(request.id)?.state)
    }

    @Test fun sha512ArtifactWithUnknownLengthUsesItsBoundRatherThanAnUntrustedObservedLength() {
        val request = store.ensure(TrustedSha512ArtifactSpec(
            "scheduler-sha512", "https://8.8.8.8/scheduler-test.bin", "a".repeat(128),
            "scheduler-test.bin", maxBytes = 2L * 1024L * 1024L,
        ))
        UserInitiatedArtifactTransfer(app).schedule(request.id)
        val job = checkNotNull(scheduler.getPendingJob(ArtifactTransferStore.jobId(request.id)))
        assertTrue(job.isUserInitiated)
        assertEquals(request.maxBytes, job.estimatedNetworkDownloadBytes)
        assertEquals(1024L * 1024L, job.minimumNetworkChunkBytes)
        assertNull(store.read(request.id)?.expectedBytes)
    }

    @Test fun androidScheduleRejectionEndsTheQueuedStateAndAllowsAFreshRetry() {
        val request = store.ensure(spec())
        shadowOf(scheduler).failOnJob(ArtifactTransferStore.jobId(request.id))
        try {
            UserInitiatedArtifactTransfer(app).schedule(request.id)
            fail("Expected Android admission rejection")
        } catch (failure: IllegalStateException) {
            assertTrue(checkNotNull(failure.message).contains("while Droide is visible"))
        }
        assertTrue(scheduler.allPendingJobs.isEmpty())
        val failed = checkNotNull(store.read(request.id))
        assertEquals(ArtifactTransferState.FAILED, failed.state)
        assertEquals(0, failed.attempts)
        assertNull(failed.ownerWorkerIdentity)
        val retry = store.ensure(spec())
        assertNotEquals(request.requestIdentity, retry.requestIdentity)
        assertEquals(ArtifactTransferState.QUEUED, retry.state)
        assertTrue(retry.detail.startsWith("Queued"))
    }

    @Test @Config(shadows = [RejectedSchedulerShadow::class])
    fun frameworkPermissionExceptionPropagatesWithoutKeepingTheSharedCallerRegistered() = runBlocking {
        val failure = SecurityException(
            "android.permission.ACCESS_NETWORK_STATE required for jobs with a connectivity constraint",
        )
        // Inject the documented framework rejection. Robolectric's scheduler shadow does not enforce
        // this permission; the separate test above checks the actual merged manifest.
        val rejected = shadowOf(scheduler) as RejectedSchedulerShadow
        rejected.failure = failure
        val transfer = UserInitiatedArtifactTransfer(app)
        var previousIdentity: String? = null
        repeat(2) {
            withTimeout(5_000L) {
                try {
                    transfer.withArtifact(spec()) { fail("Rejected transfer must not reach extraction") }
                    fail("Expected framework permission rejection")
                } catch (actual: SecurityException) {
                    assertEquals(failure.message, actual.message)
                }
            }
            val failed = checkNotNull(store.read(ArtifactTransferStore.transferId(spec())))
            assertEquals(ArtifactTransferState.FAILED, failed.state)
            assertEquals(failure.message, failed.detail)
            assertEquals(0, failed.attempts)
            assertNull(failed.ownerWorkerIdentity)
            assertNotEquals(previousIdentity, failed.requestIdentity)
            previousIdentity = failed.requestIdentity
        }
        assertEquals(2, rejected.calls)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        rejected.failure = null
        val retry = store.ensure(spec())
        UserInitiatedArtifactTransfer(app).schedule(retry.id)
        val job = checkNotNull(scheduler.getPendingJob(ArtifactTransferStore.jobId(retry.id)))
        assertEquals(retry.requestIdentity, job.extras.getString(ArtifactTransferStore.EXTRA_REQUEST_IDENTITY))
        assertNotEquals(previousIdentity, retry.requestIdentity)
    }

    @Test @Config(sdk = [29]) fun rejectedLegacyForegroundAdmissionAlsoPersistsFailure() {
        val failure = IllegalStateException("Android rejected the foreground service start")
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? = throw failure
        }
        val request = store.ensure(spec())
        try {
            UserInitiatedArtifactTransfer(context).schedule(request.id)
            fail("Expected foreground admission failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(ArtifactTransferState.FAILED, store.read(request.id)?.state)
        assertEquals(failure.message, store.read(request.id)?.detail)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    private fun spec(expectedBytes: Long? = 100L) = TrustedArtifactSpec(
        "scheduler-test", "https://8.8.8.8/scheduler-test.bin", "a".repeat(64),
        "scheduler-test.bin", maxBytes = 100L, expectedBytes = expectedBytes,
    )

    @Implements(className = "android.app.JobSchedulerImpl", isInAndroidSdk = false)
    class RejectedSchedulerShadow : ShadowJobSchedulerImpl() {
        var failure: SecurityException? = null
        var calls = 0
        @Implementation override fun schedule(job: JobInfo): Int {
            calls++
            failure?.let { throw it }
            return super.schedule(job)
        }
    }
}
