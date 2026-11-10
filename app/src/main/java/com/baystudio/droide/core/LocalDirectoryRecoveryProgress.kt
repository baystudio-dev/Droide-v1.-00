package com.baystudio.droide.core

/** These are observations of separate stages, not a single percentage or a commit receipt. */
internal enum class LocalDirectoryRecoveryPhase {
    CAPTURING, CREATING_TAR, VERIFYING_TAR, STAGED_TAR_VERIFIED,
    PREPARING_PUBLICATION, COPYING_TO_PROJECT, PUBLISHING_ARCHIVE, SYNCHRONIZING_PROJECT,
}

internal data class LocalDirectoryRecoveryProgress(
    val phase: LocalDirectoryRecoveryPhase,
    val entries: Long? = null,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
)

internal class AgentLocalDirectoryRecoveryProgress(
    private val emit: (AgentToolProgress) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var phase: LocalDirectoryRecoveryPhase? = null
    private var lastEmission = 0L

    fun report(progress: LocalDirectoryRecoveryProgress) {
        val now = nanoTime()
        if (phase == progress.phase && now - lastEmission < MIN_INTERVAL_NANOS) return
        phase = progress.phase
        lastEmission = now
        val label = when (progress.phase) {
            LocalDirectoryRecoveryPhase.CAPTURING -> "Reading retained folder"
            LocalDirectoryRecoveryPhase.CREATING_TAR -> "Creating recovery archive"
            LocalDirectoryRecoveryPhase.VERIFYING_TAR -> "Checking recovery archive"
            LocalDirectoryRecoveryPhase.STAGED_TAR_VERIFIED -> "Archive checked · preparing to save"
            LocalDirectoryRecoveryPhase.PREPARING_PUBLICATION -> "Preparing to save archive"
            LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT -> "Saving archive to project"
            LocalDirectoryRecoveryPhase.PUBLISHING_ARCHIVE -> "Finishing archive save"
            LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT -> "Updating project"
        }
        val entries = progress.entries?.let { " · $it entries" }.orEmpty()
        val bytes = progress.totalBytes?.let { " · ${progress.bytes}/$it bytes" }
            ?: " · ${progress.bytes} bytes"

        val detail = if (progress.phase == LocalDirectoryRecoveryPhase.STAGED_TAR_VERIFIED) label
            else label + entries + bytes
        emit(AgentToolProgress(detail = detail))
    }

    private companion object { const val MIN_INTERVAL_NANOS = 250_000_000L }
}
