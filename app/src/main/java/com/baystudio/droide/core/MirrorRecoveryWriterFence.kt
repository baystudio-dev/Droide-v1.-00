package com.baystudio.droide.core

import java.io.File

/** Recovery publishes metadata from an observed filesystem version. */

internal object MirrorRecoveryWriterFence {
    suspend fun <T> mutate(source: File, working: File,
        action: suspend (WorkspaceMutationGuard, WorkspaceMutationGuard) -> T): T =
        ExportWriterAdmission.withQuiescentCapture(source, label = "Recovery") {
            ExportWriterAdmission.withQuiescentCapture(working, label = "Recovery") {
                WorkspaceFileMutationLocks.withCaptureRoot(source) { sourceGuard ->
                    WorkspaceFileMutationLocks.withCaptureRoot(working) { workingGuard ->
                        sourceGuard.requireRoot(source)
                        workingGuard.requireRoot(working)
                        action(sourceGuard, workingGuard).also {
                            sourceGuard.requireRoot(source)
                            workingGuard.requireRoot(working)
                        }
                    }
                }
            }
        }
}
