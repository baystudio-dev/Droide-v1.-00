package com.baystudio.droide

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.baystudio.droide.core.EditorRecoveryRequest
import com.baystudio.droide.core.WorkspaceRuntime
import com.baystudio.droide.core.WorkspaceFailureEvent

/** A screen facade, never the owner of an Agent, terminal, setup, or build operation. */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val applicationOwner = application as DroideApplication
    private val host get() = applicationOwner.workspaceHost
    val coordinator get() = host.coordinator
    val startupRecovery get() = host.startupRecovery
    val initError get() = host.initError
    internal val environmentSetup get() = host.environmentSetup
    internal val runtimeFailure get() = host.runtimeFailure
    internal fun consumeRuntimeFailure(event: WorkspaceFailureEvent) = host.consumeRuntimeFailure(event)

    fun initializeWorkspace() = host.initializeWorkspace()
    fun initializeWithBuiltinsTemporarily() =
        host.initializeWorkspace(com.baystudio.droide.core.WorkspaceStartupMode.TEMPORARY_BUILTINS)
    fun editorStateFor(runtime: WorkspaceRuntime) = host.editorStateFor(runtime)
    fun editorRecoveryStoreFor(runtime: WorkspaceRuntime) = host.editorRecoveryStoreFor(runtime)
    fun explorerStateFor(runtime: WorkspaceRuntime) = host.explorerStateFor(runtime)
    fun explorerRefreshFor(runtime: WorkspaceRuntime) = host.explorerRefreshFor(runtime)
    fun agentUiStateFor(runtime: WorkspaceRuntime) = host.agentUiStateFor(runtime)
    fun persistEditorRecovery(request: EditorRecoveryRequest) = host.persistEditorRecovery(request)
    fun persistTerminalRecovery() = host.persistTerminalRecovery()
}
