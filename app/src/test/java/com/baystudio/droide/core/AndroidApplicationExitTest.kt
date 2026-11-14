package com.baystudio.droide.core

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidApplicationExitTest {
    @Test fun cancelsScheduledWorkAndRequestsEveryServiceStopEvenIfOneFails() {
        val app = RuntimeEnvironment.getApplication()
        val jobs = app.getSystemService(JobScheduler::class.java)
        jobs.schedule(JobInfo.Builder(100, ComponentName(app, ChatMediaCleanupJobService::class.java))
            .setMinimumLatency(100_000).build())
        val stops = mutableListOf<String>()
        val context = object : ContextWrapper(app) {
            override fun stopService(intent: Intent): Boolean {
                stops += requireNotNull(intent.component).className
                if (stops.size == 1) error("Service already stopping")
                return true
            }
        }
        requestAndroidApplicationClose(context)
        assertTrue(jobs.allPendingJobs.isEmpty())
        assertEquals(setOf(DevelopmentForegroundService::class.java.name, PackageTransferForegroundService::class.java.name,
            ExportForegroundService::class.java.name, PdfPreviewService::class.java.name), stops.toSet())
    }
}
