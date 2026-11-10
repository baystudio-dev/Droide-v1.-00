package com.baystudio.droide.core

/** Stage, not arbitrary server stderr, is safe to display in the editor. Cause stays internal. */
enum class LspStartupStage { PROCESS_START, INITIALIZE, DOCUMENT_REPLAY }

class LspStartupFailure internal constructor(
    val serverId: String,
    val stage: LspStartupStage,
    val timedOut: Boolean,
    internal val cause: Throwable,
) {
    val message: String get() {
        val label = serverId.filter { it.isLetterOrDigit() || it in "-_." }.take(80)
        val phase = when (stage) {
            LspStartupStage.PROCESS_START -> "process startup"
            LspStartupStage.INITIALIZE -> "protocol initialization"
            LspStartupStage.DOCUMENT_REPLAY -> "document initialization"
        }
        return "LSP $label: $phase ${if (timedOut) "timed out" else "failed"}. Executable was found; installation is not missing. Retry language server in Code actions after resolving the environment/server problem."
    }
}
