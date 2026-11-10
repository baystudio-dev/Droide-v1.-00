package com.baystudio.droide.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** PRoot mounts and launch environment belong to a process, not to an interactive shell's children. */
internal class ToolchainEnvironmentRefresh(private val terminals: TerminalManager) {
    suspend fun open(requireCurrent: () -> Unit): String {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val tab = terminals.createToolchainTerminal(requireCurrent)
        currentCoroutineContext().ensureActive()
        requireCurrent()
        return "TOOLCHAIN_ENVIRONMENT_RESULT\nsuccess=true\nterminal_id=${tab.id}\n" +
            "A new Ubuntu terminal is selected with the current toolchain environment. " +
            "Continue with exec_command. Existing terminals and their running work were preserved."
    }

    companion object {
        const val NEXT_STEP = "environment_refresh=required_for_existing_sessions\n" +
            "After all installations and selections: the built-in Agent should call toolchain with " +
            "operation=refresh_environment once, then continue the build with exec_command. " +
            "External CLI agents must resume in a new Droide Ubuntu terminal or a reconnected external chat session. " +
            "Starting bash or sourcing a profile inside the old process does not refresh SDK mounts.\n"
    }
}
