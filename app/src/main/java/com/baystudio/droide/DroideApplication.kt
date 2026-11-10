package com.baystudio.droide

import android.app.Application
import java.lang.ref.WeakReference
import com.baystudio.droide.core.ApplicationExitSequence
import com.baystudio.droide.core.ExitOwnerBarrier
import com.baystudio.droide.core.ApplicationWorkAdmission
import com.baystudio.droide.core.ChatMediaCleanup
import com.baystudio.droide.core.ExportController
import com.baystudio.droide.core.DevelopmentForegroundService
import com.baystudio.droide.core.UserInitiatedArtifactTransfer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** One process owns work; only explicit Exit retires it. Activity recreation never reopens work. */
class DroideApplication : Application() {
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var retainedHost: WorkspaceRuntimeHost? = null
    internal val workspaceHost: WorkspaceRuntimeHost get() {
        retainedHost?.let { return it }
        ApplicationWorkAdmission.requireOpen()
        return WorkspaceRuntimeHost(this).also { retainedHost = it }
    }
    internal val interfaceEpoch = MutableStateFlow<String?>(null)
    private val mutableExit = MutableStateFlow(ApplicationExitSequence.State())
    internal val exitState: StateFlow<ApplicationExitSequence.State> = mutableExit
    internal val releaseWorkspaceUi = MutableStateFlow(false)
    internal val closeWorkspaceUi = MutableStateFlow(false)
    private var currentInterface = WeakReference<Any>(null)
    internal val startupFailure = MutableStateFlow<String?>(null)
    private var sequence: ApplicationExitSequence? = null
    private var originalExitActivity: Any? = null
    internal fun mayPresentWorkspace(activity: Any): Boolean = sequence == null || originalExitActivity === activity
    internal fun shouldCloseUserInterface(activity: Any): Boolean = closeWorkspaceUi.value && originalExitActivity === activity
    internal fun beforeActivityDestroyed(activity: Any) {
        if (currentInterface.get() === activity) currentInterface.clear()
        if (sequence != null && originalExitActivity === activity) {
            retainedHost?.beforeExitActivityDestroyed()
            originalExitActivity = null
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (Application.getProcessName().contains(":document_preview")) return
        try { ApplicationWorkAdmission.initialize(this) }
        catch (_: Exception) { startupFailure.value = "Saved work state could not be read. Droide remains stopped." }
    }

    internal fun attachUserInterface(activity: Any? = null) {
        if (activity != null && sequence == null) currentInterface = WeakReference(activity)
        if (ApplicationWorkAdmission.isOpen && sequence == null) {
            workspaceHost
            interfaceEpoch.value = ApplicationWorkAdmission.currentEpoch
            DevelopmentForegroundService.ensureSession(this)
        }
    }

    internal fun openFromUser() {
        check(sequence == null || mutableExit.value.completed) { "The previous Exit is not complete. Retry it before opening a new session." }
        check(retainedHost == null) { "Previous workspace ownership has not been released" }
        ApplicationWorkAdmission.reopenFromUser()
        sequence = null
        originalExitActivity = null
        mutableExit.value = ApplicationExitSequence.State()
        releaseWorkspaceUi.value = false
        closeWorkspaceUi.value = false
        startupFailure.value = null
        attachUserInterface()
    }

    internal fun requestExitFromNotification(expectedEpoch: String) {
        if (ApplicationWorkAdmission.currentEpoch != expectedEpoch) return
        if (!ApplicationWorkAdmission.isOpen && sequence == null) return
        requestExit(expectedEpoch, currentInterface.get() ?: this)
    }

    private fun closeOriginalUserInterface() {
        closeWorkspaceUi.value = true
        (originalExitActivity as? android.app.Activity)?.takeUnless { it.isDestroyed }?.finishAndRemoveTask()
    }

    internal fun requestExit(expectedEpoch: String, activity: Any) {
        sequence?.let {
            check(ApplicationWorkAdmission.currentEpoch == expectedEpoch) { "Expired Exit action" }
            it.request(); return
        }
        check(ApplicationWorkAdmission.isCurrentOpen(expectedEpoch)) { "This Exit action belongs to an expired Droide session" }
        originalExitActivity = activity
        val owner = retainedHost
        val backgroundStops = ExitOwnerBarrier(exitScope, listOf(
            ApplicationExitSequence.Step("Project exports") { ExportController.shutdownForExit(this) },
            ApplicationExitSequence.Step("Scheduled transfers") { UserInitiatedArtifactTransfer.shutdownForExit(this) },
            ApplicationExitSequence.Step("Media maintenance") { ChatMediaCleanup.shutdownForExit(this) },
        ))
        val steps = mutableListOf(ApplicationExitSequence.Step("Closing new work admission") {
            check(ApplicationWorkAdmission.currentEpoch == expectedEpoch) { "Droide session changed before Exit" }
            ApplicationWorkAdmission.closeForExit()
            ExportController.state.value?.takeUnless { it.terminal }?.let { ExportController.interrupt(it.id) }
            backgroundStops.requestStop()
        })
        val beforeCloseUi = listOf(
            ApplicationExitSequence.Step("Stopping background transfers and maintenance") { backgroundStops.stopAndAwait() },
            ApplicationExitSequence.Step("Releasing notification and CPU leases") { DevelopmentForegroundService.shutdownForExit(this) },
        )
        if (owner != null) steps += owner.exitSteps(exitScope,
            releaseUi = { releaseWorkspaceUi.value = true },
            closeUi = ::closeOriginalUserInterface,
            beforeCloseUi = beforeCloseUi,
        ) else {
            steps += beforeCloseUi
            steps += ApplicationExitSequence.Step("Closing workspace") { closeOriginalUserInterface() }
        }
        steps += listOf(
            ApplicationExitSequence.Step("Completing Exit") {
                check(retainedHost === owner) { "Workspace changed during Exit" }
                retainedHost = null
                interfaceEpoch.value = null
            },
        )
        val created = ApplicationExitSequence(exitScope, steps)
        sequence = created
        exitScope.launch(start = CoroutineStart.UNDISPATCHED) {
            created.state.first {
                mutableExit.value = it
                DevelopmentForegroundService.updateExitStatus(it)
                it.completed
            }
            // Finish Android's queued Activity work, then end only this process.

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (sequence === created && created.state.value.completed &&
                    !ApplicationWorkAdmission.isOpen && ApplicationWorkAdmission.currentEpoch == expectedEpoch) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        }
        created.request()
    }

    internal val uiWorkOwner get() = retainedHost?.uiWorkOwner
    internal val exitDraftUi get() = retainedHost?.exitDraftUi

    internal fun retryExit() { checkNotNull(sequence) { "No Exit is pending" }.request() }
}
