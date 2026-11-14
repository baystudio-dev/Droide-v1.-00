package com.baystudio.droide.core

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent

/** Android IPC runs separately from the kill deadline; it never waits for app-owned cleanup. */
internal fun requestAndroidApplicationClose(context: Context) {
    runCatching { context.getSystemService(JobScheduler::class.java)?.cancelAll() }
    listOf(DevelopmentForegroundService::class.java, PackageTransferForegroundService::class.java,
        ExportForegroundService::class.java, PdfPreviewService::class.java).forEach { service ->
        runCatching { context.stopService(Intent(context, service)) }
    }
    runCatching { context.getSystemService(NotificationManager::class.java)?.cancelAll() }
    runCatching { context.getSystemService(ActivityManager::class.java)?.appTasks }.getOrNull()
        ?.forEach { task -> runCatching { task.finishAndRemoveTask() } }
}
