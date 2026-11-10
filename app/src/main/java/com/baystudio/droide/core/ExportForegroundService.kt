package com.baystudio.droide.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.baystudio.droide.MainActivity
import kotlinx.coroutines.*

class ExportForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var jobId: String? = null
    private var observation: Job? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        val epoch = intent?.getStringExtra(EXTRA_EPOCH)
        if (id == null || epoch == null || !ApplicationWorkAdmission.isCurrentOpen(epoch)) {
            if (jobId == null) stopSelf(startId); return START_NOT_STICKY
        }
        val state = ExportController.state.value
        if (state?.id != id || state.epoch != epoch || state.terminal) {
            if (jobId == null) stopSelf(startId); return START_NOT_STICKY
        }
        if (intent.action == ACTION_CANCEL) { ExportController.cancel(id); return START_NOT_STICKY }
        if (intent.action != ACTION_START) { if (jobId == null) stopSelf(startId); return START_NOT_STICKY }
        jobId = id
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Project exports", NotificationManager.IMPORTANCE_LOW))
            startForeground(NOTIFICATION_ID, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val started = ExportController.launch(scope, applicationContext, id, epoch) {
                scope.launch { if (jobId == id) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
            }
            if (!started) { if (observation == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }; return START_NOT_STICKY }
            observation?.cancel()
            observation = scope.launch {
                ExportController.state.collect { value ->
                    if (value?.id == id && !value.terminal) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(value))
                }
            }
        } catch (_: Exception) {
            ExportController.interrupt(id)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun notification(state: ExportJobState): Notification {
        val content = PendingIntent.getActivity(this, 8200, Intent(this, MainActivity::class.java)
            .setAction(ACTION_SHOW).putExtra(EXTRA_ID, state.id).putExtra(EXTRA_EPOCH, state.epoch),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 8201, Intent(this, ExportForegroundService::class.java)
            .setAction(ACTION_CANCEL).putExtra(EXTRA_ID, state.id).putExtra(EXTRA_EPOCH, state.epoch),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .withDroideIcons(this)
            .setContentTitle("Project export")
            .setContentText("${state.phase.name.lowercase().replace('_', ' ')} · ${state.entries} entries · ${state.bytes} bytes")
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        jobId?.let(ExportController::interrupt)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        jobId?.let(ExportController::interrupt)
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "project_exports"
        private const val NOTIFICATION_ID = 7213
        private const val ACTION_START = "com.baystudio.droide.EXPORT_START"
        private const val ACTION_CANCEL = "com.baystudio.droide.EXPORT_CANCEL"
        const val ACTION_SHOW = "com.baystudio.droide.EXPORT_SHOW"
        const val EXTRA_ID = "export_job_id"
        const val EXTRA_EPOCH = "export_epoch"
        internal fun start(context: Context, id: String, epoch: String) {
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "Application session has ended" }
            ContextCompat.startForegroundService(context, Intent(context, ExportForegroundService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_ID, id).putExtra(EXTRA_EPOCH, epoch))
        }
    }
}
