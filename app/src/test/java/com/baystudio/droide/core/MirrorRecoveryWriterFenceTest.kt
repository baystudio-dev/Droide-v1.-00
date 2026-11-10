package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MirrorRecoveryWriterFenceTest {
    @Test fun recoveryBlocksLateLaunchAndRepositoryWriterUntilPublicationEnds() = runBlocking {
        val root = Files.createTempDirectory("recovery-fence-").toFile()
        val source = root.resolve("source").apply { mkdir() }; val working = root.resolve("working").apply { mkdir() }
        try {
            MirrorRecoveryWriterFence.mutate(source, working) { sourceGuard, workingGuard ->
                sourceGuard.requireRoot(source); workingGuard.requireRoot(working)
                val error = runCatching { ExportWriterAdmission.beginWriter(working, "late CLI") }.exceptionOrNull()
                assertTrue(error is ExportCaptureBusyException)
                assertTrue(error!!.message.orEmpty().startsWith("Recovery is capturing"))
                assertTrue(runCatching { WorkspaceFileMutationLocks.withRoot(source) { fail("late repository mutation") } }.isFailure)
            }
            ExportWriterAdmission.beginWriter(source, "new source writer").close()
            ExportWriterAdmission.beginWriter(working, "new CLI").close()
        } finally { root.deleteRecursively() }
    }

    @Test fun existingWriterRefusesRecoveryWithoutPublishingOrStrandingOtherRootFence() = runBlocking {
        val root = Files.createTempDirectory("recovery-busy-").toFile()
        val source = root.resolve("source").apply { mkdir() }; val working = root.resolve("working").apply { mkdir() }
        try {
            ExportWriterAdmission.beginWriter(working, "owned CLI cleanup pending").use {
                assertTrue(runCatching { MirrorRecoveryWriterFence.mutate(source, working) { _, _ -> fail("must not publish") } }.isFailure)
                ExportWriterAdmission.beginWriter(source, "unrelated source edit").close()
            }
            MirrorRecoveryWriterFence.mutate(source, working) { _, _ -> Unit }
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationReleasesBothFencesWithoutTouchingAnotherWorkspace() = runBlocking {
        val root = Files.createTempDirectory("recovery-cancel-").toFile()
        val source = root.resolve("source").apply { mkdir() }; val working = root.resolve("working").apply { mkdir() }
        val other = root.resolve("other").apply { mkdir() }
        try {
            val entered = CompletableDeferred<Unit>()
            val action = launch { MirrorRecoveryWriterFence.mutate(source, working) { _, _ -> entered.complete(Unit); awaitCancellation() } }
            entered.await()
            ExportWriterAdmission.beginWriter(other, "other workspace").use {
                action.cancelAndJoin()
                ExportWriterAdmission.beginCapture(source).close()
                ExportWriterAdmission.beginCapture(working).close()
                assertTrue(runCatching { ExportWriterAdmission.beginCapture(other) }.isFailure)
            }
        } finally { root.deleteRecursively() }
    }
}
