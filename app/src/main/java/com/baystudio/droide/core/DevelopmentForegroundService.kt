package com.baystudio.droide.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.baystudio.droide.MainActivity
import com.baystudio.droide.DroideApplication
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class DevelopmentForegroundService : Service() {
    internal data class Handle(val token: String)
    private val activeSessions = linkedMapOf<String, String>()
    private var residentEpoch: String? = null
    private var serviceEpoch: String? = null
    private var exitShutdown = false
    private var foregroundPromoted = false
    private val destruction = kotlinx.coroutines.CompletableDeferred<Unit>()
    private fun maintainOwnedLease(action: () -> Unit) {
        check(activeInstance === this && !exitShutdown && serviceEpoch == ApplicationWorkAdmission.currentEpoch) {
            "CPU lease belongs to a retired developer service"
        }
        // Closing new work is not retirement of existing work. Preserve CPU protection
        // during the coordinator's durable-save phase, including a slow failed save.
        action()
    }
    private val cpuLease by lazy { DevelopmentCpuWakeLease(getSystemService(PowerManager::class.java), onFailure = {
        if (ApplicationWorkAdmission.isOpen) {
            DevelopmentForegroundSessionRegistry.protectionLostAll(activeSessions.keys.toList(), "Automatic CPU protection became unavailable")
            activeSessions.clear()
        } else _sessionFailure.value = "CPU protection failed while Exit is preserving work"
        refreshOrStop()
    }, acquireAdmission = ::maintainOwnedLease) }
    private val manualLease by lazy { DevelopmentCpuWakeLease(getSystemService(PowerManager::class.java),
        onFailure = {
            _manualWakeLock.value = false
            _sessionFailure.value = "Manual CPU wake lock could not be renewed"
            Log.e("DroideLifecycle", "Manual CPU wake lock could not be renewed")
            refreshOrStop()
        }, tag = "Droide:manual-session-wake-lock",
        acquireAdmission = ::maintainOwnedLease) }

    override fun onCreate() {
        super.onCreate()
        runCatching { ApplicationWorkAdmission.initialize(applicationContext) }.onFailure(::reportFailure)
        activeInstance = this
        serviceEpoch = ApplicationWorkAdmission.currentEpoch
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: run { if (activeSessions.isEmpty() && residentEpoch == null) stopSelf(startId); return START_NOT_STICKY }
        val epoch = request.getStringExtra(EXTRA_EPOCH)
        if (request.action == ACTION_REQUEST_EXIT) {
            if (epoch != null && epoch == ApplicationWorkAdmission.currentEpoch && !exitShutdown) {
                runCatching { (application as? DroideApplication)?.requestExitFromNotification(epoch) }
                    .onFailure(::reportFailure)
            }
            if (activeSessions.isEmpty() && residentEpoch == null && !foregroundPromoted) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (epoch == null || !ApplicationWorkAdmission.isCurrentOpen(epoch)) {
            if (epoch == serviceEpoch && epoch == ApplicationWorkAdmission.currentEpoch && !exitShutdown) {
                if (stopUnpromotedOwnerlessService(foregroundPromoted, activeSessions.isNotEmpty(), residentEpoch != null)) {
                    // A rejected start with no established owner must not wait for promotion.
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // Late completion may release its own lease; no late start or acquire may
                // cancel existing owners or tear down foreground protection during Save.
                if (request.action == ACTION_FINISH) finishToken(request.getStringExtra(EXTRA_TOKEN).orEmpty())
                if (request.action == ACTION_RELEASE_WAKE_LOCK) applyManualWakeLock(false)
            } else if (activeSessions.isEmpty() && residentEpoch == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try { ApplicationWorkAdmission.withOpen(epoch) { when (request.action) {
            ACTION_RESIDENT -> {
                residentEpoch = epoch
                refreshOrStop()
            }
            ACTION_ACQUIRE_WAKE_LOCK, ACTION_RELEASE_WAKE_LOCK -> {
                residentEpoch = epoch
                try { applyManualWakeLock(request.action == ACTION_ACQUIRE_WAKE_LOCK) }
                catch (failure: RuntimeException) { reportFailure(failure) }
                refreshOrStop()
            }
            ACTION_START -> {
                val token = request.getStringExtra(EXTRA_TOKEN).orEmpty()
                val label = request.getStringExtra(EXTRA_LABEL).orEmpty().take(120)
                if (!token.matches(Regex("[A-Za-z0-9_-]{12,100}")) || label.isBlank()) {
                    DevelopmentForegroundSessionRegistry.remove(token)
                    refreshOrStop()
                    return@withOpen
                }
                if (!DevelopmentForegroundSessionRegistry.isRegistered(token)) {
                    refreshOrStop()
                    return@withOpen
                }
                activeSessions[token] = label
                try {
                    ensureChannel()
                    promoteOrRefreshForeground()
                    DevelopmentForegroundSessionRegistry.acquired(token)
                    if (!DevelopmentForegroundSessionRegistry.isRegistered(token)) finishToken(token)
                } catch (error: RuntimeException) {
                    activeSessions.remove(token)
                    DevelopmentForegroundSessionRegistry.protectionLost(token, "Automatic CPU protection could not start")
                    reportFailure(error)
                    refreshOrStop()
                }
            }
            ACTION_STOP -> {
                val token = request.getStringExtra(EXTRA_TOKEN).orEmpty()
                activeSessions.remove(token)
                DevelopmentForegroundSessionRegistry.cancel(token)
                refreshOrStop()
            }
            ACTION_FINISH -> finishToken(request.getStringExtra(EXTRA_TOKEN).orEmpty())
        } } } catch (failure: RuntimeException) {
            reportFailure(failure)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            if (activeInstance === this) { activeInstance = null; _manualWakeLock.value = false }
            DevelopmentForegroundSessionRegistry.protectionLostAll(activeSessions.keys.toList(), "Automatic CPU protection became unavailable")
            activeSessions.clear()
            residentEpoch = null
            val cleanup = OwnedResourceCleanup.run({ cpuLease.release() }, { manualLease.release() })
            cleanup.onFailure(::reportFailure)
            super.onDestroy()
            if (cleanup.isSuccess) destruction.complete(Unit)
            else destruction.completeExceptionally(checkNotNull(cleanup.exceptionOrNull()))
        } catch (failure: Throwable) {
            destruction.completeExceptionally(failure)
            throw failure
        }
    }

    private fun applyManualWakeLock(held: Boolean) {
        if (held) {
            // First promote, then acquire. A denied foreground service never grants CPU residency.
            ensureChannel()
            promoteOrRefreshForeground()
            manualLease.hold()
            _manualWakeLock.value = true
            _sessionFailure.value = null
        } else {
            try { manualLease.release(); _sessionFailure.value = null } finally { _manualWakeLock.value = false }
        }
    }
    private fun finishToken(token: String) {
        DevelopmentForegroundSessionRegistry.remove(token)
        activeSessions.remove(token)
        refreshOrStop()
    }
    private fun refreshOrStop() {
        if (!ApplicationWorkAdmission.isOpen && !exitShutdown) {
            // Existing owners are still being preserved/stopped by the Exit coordinator.
            // Do not interpret its admission fence as a service or wake-lock failure.
            if (activeSessions.keys.none(DevelopmentForegroundSessionRegistry::cpuWork)) cpuLease.release()
            return
        } else if (activeSessions.isEmpty() && residentEpoch == null) {
            cpuLease.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            runCatching { ensureChannel(); promoteOrRefreshForeground() }.onFailure { failure ->
                reportFailure(failure)
                DevelopmentForegroundSessionRegistry.protectionLostAll(activeSessions.keys.toList(), "Automatic CPU protection became unavailable")
                stopForExitRequests().onFailure(::reportFailure)
            }
        }
    }
    private fun stopForExitRequests(): Result<Unit> {
        exitShutdown = true
        activeSessions.clear()
        residentEpoch = null
        _manualWakeLock.value = false
        return OwnedResourceCleanup.run(
            { cpuLease.release() }, { manualLease.release() },
            { stopForeground(STOP_FOREGROUND_REMOVE) }, { stopSelf() },
        )
    }
    private fun promoteOrRefreshForeground() = ApplicationWorkAdmission.withOpen {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
        foregroundPromoted = true
        if (activeSessions.keys.any(DevelopmentForegroundSessionRegistry::cpuWork)) cpuLease.hold() else cpuLease.release()
    }
    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Developer operations", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Droide session, developer operations and CPU wake-lock controls"
                setShowBadge(false)
            },
        )
    }
    private fun buildNotification(exit: ApplicationExitSequence.State? = null): Notification {
        val epoch = ApplicationWorkAdmission.currentEpoch
        val manual = _manualWakeLock.value
        val text = when {
            exit?.failure != null -> "Exit needs attention · saved drafts retained"
            exit?.started == true -> exit.stage.ifBlank { "Stopping Droide" }
            activeSessions.isEmpty() -> if (manual) "CPU wake lock acquired" else "Session available · CPU wake lock released"
            activeSessions.size == 1 -> activeSessions.values.last()
            else -> "${activeSessions.size} developer operations active · ${activeSessions.values.last()}"
        }
        val open = PendingIntent.getActivity(this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setData(sessionUri(epoch, "open")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val wakeAction = if (manual) ACTION_RELEASE_WAKE_LOCK else ACTION_ACQUIRE_WAKE_LOCK
        val wake = PendingIntent.getService(this, REQUEST_WAKE,
            Intent(this, DevelopmentForegroundService::class.java).setAction(wakeAction)
                .setData(sessionUri(epoch, if (manual) "release" else "acquire")).putExtra(EXTRA_EPOCH, epoch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exitAction = PendingIntent.getService(this, REQUEST_EXIT,
            Intent(this, DevelopmentForegroundService::class.java).setAction(ACTION_REQUEST_EXIT)
                .setData(sessionUri(epoch, "exit")).putExtra(EXTRA_EPOCH, epoch),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, CHANNEL_ID).withDroideIcons(this)
            .setContentTitle(if (exit?.started == true) "Stopping Droide" else "Droide developer session")
            .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open)
        if (exit?.started != true) builder.addAction(Notification.Action.Builder(android.R.drawable.ic_lock_idle_lock,
            if (manual) "Release WakeLock" else "Acquire WakeLock", wake).build())
        if (exit?.started != true || exit?.failure != null) builder.addAction(Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, if (exit?.failure != null) "Retry Exit" else "Exit", exitAction).build())
        return builder.build()
    }
    private fun reportFailure(error: Throwable) {
        _sessionFailure.value = error.message ?: "Developer-session lifecycle request failed"
        Log.e("DroideLifecycle", "Developer-session lifecycle request failed", error)
    }

    companion object {
        const val ACTION_REQUEST_EXIT = ApplicationWorkAdmission.ACTION_REQUEST_EXIT
        const val EXTRA_EXIT_EPOCH = ApplicationWorkAdmission.EXTRA_EXIT_EPOCH
        internal const val ACTION_ACQUIRE_WAKE_LOCK = "com.baystudio.droide.DEV_FGS_ACQUIRE_WAKE_LOCK"
        internal const val ACTION_RELEASE_WAKE_LOCK = "com.baystudio.droide.DEV_FGS_RELEASE_WAKE_LOCK"
        internal const val EXTRA_EPOCH = "application_epoch"
        private const val CHANNEL_ID = "droide.developer.operations"
        private const val NOTIFICATION_ID = 1701
        private const val REQUEST_OPEN = 1702
        private const val REQUEST_WAKE = 1703
        private const val REQUEST_EXIT = 1704
        private const val ACTION_START = "com.baystudio.droide.DEV_FGS_START"
        private const val ACTION_STOP = "com.baystudio.droide.DEV_FGS_STOP"
        private const val ACTION_FINISH = "com.baystudio.droide.DEV_FGS_FINISH"
        private const val ACTION_RESIDENT = "com.baystudio.droide.DEV_FGS_RESIDENT"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_LABEL = "label"
        @Volatile private var activeInstance: DevelopmentForegroundService? = null
        private val _manualWakeLock = MutableStateFlow(false)
        val manualWakeLock: StateFlow<Boolean> = _manualWakeLock.asStateFlow()
        private val _sessionFailure = MutableStateFlow<String?>(null)
        val sessionFailure: StateFlow<String?> = _sessionFailure.asStateFlow()
        private fun sessionUri(epoch: String, operation: String) = Uri.parse("droide://developer-session/$epoch/$operation")

        internal fun updateExitStatus(state: ApplicationExitSequence.State) {
            val service = activeInstance ?: return
            if (!state.started || state.completed || service.exitShutdown || !service.foregroundPromoted) return
            runCatching {
                service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, service.buildNotification(state))
            }.onFailure(service::reportFailure)
        }

        /** Request session notification residency without a CPU lease. */
        internal fun ensureSession(context: Context): Boolean {
            return runCatching {
                ApplicationWorkAdmission.initialize(context)
                ApplicationWorkAdmission.withOpen {
                    val epoch = ApplicationWorkAdmission.requireOpen()
                    DevelopmentForegroundSessionRegistry.openForEpoch(epoch)
                    checkNotNull(ContextCompat.startForegroundService(context.applicationContext,
                        Intent(context.applicationContext, DevelopmentForegroundService::class.java)
                            .setAction(ACTION_RESIDENT).putExtra(EXTRA_EPOCH, epoch).setData(sessionUri(epoch, "resident")))) { "Developer session service was not found" }
                }
            }.onFailure {
                _sessionFailure.value = "Session notification could not start; check Android notification settings"
                Log.e("DroideLifecycle", "Session notification was not admitted", it)
            }.isSuccess
        }
        internal fun setManualWakeLock(context: Context, held: Boolean, epoch: String = ApplicationWorkAdmission.currentEpoch): Boolean {
            return runCatching {
                ApplicationWorkAdmission.initialize(context)
                ApplicationWorkAdmission.withOpen(epoch) {
                    checkNotNull(ContextCompat.startForegroundService(context.applicationContext,
                        Intent(context.applicationContext, DevelopmentForegroundService::class.java)
                            .setAction(if (held) ACTION_ACQUIRE_WAKE_LOCK else ACTION_RELEASE_WAKE_LOCK)
                            .setData(sessionUri(epoch, if (held) "acquire" else "release")).putExtra(EXTRA_EPOCH, epoch))) { "Developer session service was not found" }
                }
            }.onFailure {
                _sessionFailure.value = it.message ?: "CPU wake-lock request was not admitted"
                Log.e("DroideLifecycle", "CPU wake-lock request was not admitted", it)
            }.isSuccess
        }
        internal fun start(context: Context, label: String, cancel: () -> Unit): Handle? = startWithCpuPolicy(context, label, true, cancel)
        internal fun startWithoutCpu(context: Context, label: String, cancel: () -> Unit): Handle? = startWithCpuPolicy(context, label, false, cancel)
        internal fun startHumanCommand(context: Context, onAcquired: () -> Unit,
            onProtectionLost: (String) -> Unit, cancel: () -> Unit): Handle? =
            startWithCpuPolicy(context, "User terminal command", true, cancel, onAcquired, onProtectionLost)
        private fun startWithCpuPolicy(context: Context, label: String, cpuWork: Boolean, cancel: () -> Unit,
            onAcquired: (() -> Unit)? = null, onProtectionLost: ((String) -> Unit)? = null): Handle? {
            val token = UUID.randomUUID().toString().replace("-", "")
            return try {
                ApplicationWorkAdmission.initialize(context)
                ApplicationWorkAdmission.withOpen {
                    val epoch = ApplicationWorkAdmission.requireOpen()
                    DevelopmentForegroundSessionRegistry.openForEpoch(epoch)
                    DevelopmentForegroundSessionRegistry.registerWithCpuPolicy(token, cpuWork, cancel, onAcquired, onProtectionLost)
                    checkNotNull(ContextCompat.startForegroundService(context.applicationContext,
                        Intent(context.applicationContext, DevelopmentForegroundService::class.java).setAction(ACTION_START)
                            .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_LABEL, label.take(120)).putExtra(EXTRA_EPOCH, epoch))) { "Developer session service was not found" }
                    Handle(token)
                }
            } catch (error: RuntimeException) {
                DevelopmentForegroundSessionRegistry.remove(token)
                _sessionFailure.value = "Automatic CPU protection could not start; work may pause with the screen off"
                runCatching { onProtectionLost?.invoke("Automatic CPU protection could not start") }
                    .onFailure { Log.e("DroideLifecycle", "CPU protection loss observer failed", it) }
                Log.e("DroideLifecycle", "Developer operation foreground session was not admitted", error)
                null
            }
        }
        internal fun startRequired(context: Context, label: String, cancel: () -> Unit): Handle =
            checkNotNull(start(context, label, cancel)) { "Android did not allow the required foreground developer session to start" }
        internal fun setCpuWork(handle: Handle, required: Boolean) {
            if (required && !ApplicationWorkAdmission.isOpen) return
            DevelopmentForegroundSessionRegistry.setCpuWork(handle.token, required)
            val service = activeInstance ?: return
            Handler(Looper.getMainLooper()).post { if (activeInstance === service) service.refreshOrStop() }
        }
        internal fun finish(context: Context, handle: Handle?) {
            val token = handle?.token ?: return
            DevelopmentForegroundSessionRegistry.remove(token)
            val service = activeInstance
            if (service != null) {
                Handler(Looper.getMainLooper()).post { if (activeInstance === service) service.finishToken(token) }
            }
            // No cleanup start intent: a queued ACTION_START rejects an already removed registration.
        }
        /** Cancels admissions and sends stop requests. Individual operation owners must still be joined. */
        internal suspend fun shutdownForExit(context: Context): Unit = withContext(NonCancellable) {
            check(!ApplicationWorkAdmission.isOpen) { "Close application work admission before stopping developer sessions" }
            val cancellation = DevelopmentForegroundSessionRegistry.closeForExit()
            var retiring: DevelopmentForegroundService? = null
            val serviceResult = withContext(Dispatchers.Main.immediate) {
                retiring = activeInstance
                OwnedResourceCleanup.run(
                    { retiring?.stopForExitRequests()?.getOrThrow() },
                    { _manualWakeLock.value = false },
                    { context.applicationContext.stopService(Intent(context.applicationContext, DevelopmentForegroundService::class.java)); Unit },
                    { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) },
                )
            }
            OwnedResourceCleanup.run({ cancellation.getOrThrow() }, { serviceResult.getOrThrow() }).getOrThrow()
            retiring?.destruction?.await()
            check(activeInstance == null || activeInstance === retiring) { "A late service instance still needs retirement; retry Exit" }
        }
    }
}
