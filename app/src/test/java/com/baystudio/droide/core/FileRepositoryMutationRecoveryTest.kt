package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileRepositoryMutationRecoveryTest {
    @Test fun committedDirectorySyncFailureKeepsTheFolderAndReportsLocalAvailability() = workspace { root ->
        val failure = IOException("directory sync offline")
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.CreateDirectory) throw failure })
        val actual = assertThrows(ProjectDirectorySyncException::class.java) {
            runBlocking { repo.createDirectory("src", requireExistingParent = true) }
        }
        assertSame(failure, actual.cause)
        assertEquals("src", actual.relativePath)
        assertTrue(root.resolve("src").isDirectory)
        assertEquals("dependency", root.resolve("dependency").readText())
    }

    @Test fun cachedDirectoryExistenceCannotTurnAConcurrentFileReplacementIntoSuccess() = workspace { root ->
        root.resolve("src").mkdir()
        var observerCalls = 0
        val repo = FileRepository(root, beforeMutation = {
            assertTrue(root.resolve("src").delete())
            root.resolve("src").writeText("terminal file")
        }, onMutation = { observerCalls++ })
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.createDirectory("src") } }
        assertEquals(0, observerCalls)
        assertEquals("terminal file", root.resolve("src").readText())
    }

    @Test fun directoryReplacementDuringSyncIsPreservedWithoutAFalseCreatedReceipt() = workspace { root ->
        val failure = IOException("sync unavailable")
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.CreateDirectory) {
                assertTrue(root.resolve("src").delete())
                root.resolve("src").writeText("foreign file")
                throw failure
            }
        })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { repo.createDirectory("src") } })
        assertEquals("foreign file", root.resolve("src").readText())
    }

    @Test fun failedDeleteNeverOverwritesARecreatedSourceAndReportsItsRetainedStage() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Delete) {
                root.resolve("main.kt").writeText("new terminal source")
                error("provider disconnected")
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { repo.delete("main.kt") } }
        val stage = root.listFiles()!!.single { it.name.contains(".droide-tmp-") }
        assertEquals("original", stage.readText())
        assertTrue(failure.message!!.contains(stage.name))
        assertEquals("new terminal source", root.resolve("main.kt").readText())
        assertEquals("dependency", root.resolve("dependency").readText())
    }

    @Test fun unchangedStagedFileCanBeRestoredAfterDeleteSyncFailure() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        val failure = IOException("sync offline")
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Delete) throw failure })
        assertOriginatingFailure(failure, assertThrows(IOException::class.java) { runBlocking { repo.delete("main.kt") } })
        assertEquals("original", root.resolve("main.kt").readText())
        assertEquals(setOf("main.kt", "dependency"), root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun editedDeleteStageIsPreservedRatherThanRelocatedOrCleaned() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        lateinit var stage: File
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Delete) {
                stage = root.listFiles()!!.single { it.name.contains(".droide-tmp-") }
                stage.writeText("edited staged file")
                error("sync failure")
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { repo.delete("main.kt") } }
        assertTrue(failure.message!!.contains(stage.name))
        assertFalse(root.resolve("main.kt").exists())
        assertEquals("edited staged file", stage.readText())
    }

    @Test fun directoryDeleteSyncFailureKeepsTheRecoverableTreeAndReportsPendingSynchronization() = workspace { root ->
        root.resolve("src").mkdir()
        root.resolve("src/main.kt").writeText("original")
        lateinit var stage: File
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Delete) {
                stage = root.resolve(LocalDirectoryDeleteCustody.HOME).listFiles()!!.single().resolve("original")
                stage.resolve("main.kt").writeText("newer nested edit")
                error("provider unavailable")
            }
        })
        assertTrue(runBlocking { repo.delete("src") })
        val notice = repo.retainedWork.value.notices.single()
        assertEquals(stage.relativeTo(root).invariantSeparatorsPath, notice.candidate.path)
        assertEquals(LocalDirectoryDeleteCustody.ObserverState.FAILED, notice.candidate.directoryDeletion!!.observer)
        assertFalse(notice.historicalWriteReturned)
        assertFalse(root.resolve("src").exists())
        assertEquals("newer nested edit", stage.resolve("main.kt").readText())
        assertEquals("dependency", root.resolve("dependency").readText())
    }

    @Test fun replacedStageAfterSuccessfulDeleteSynchronizationIsNotDeleted() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        lateinit var stage: File
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Delete) {
                stage = root.listFiles()!!.single { it.name.contains(".droide-tmp-") }
                val replacement = root.resolve("terminal-file").apply { writeText("foreign replacement") }
                Files.move(replacement.toPath(), stage.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { repo.delete("main.kt") } }
        assertTrue(failure.message!!.contains(stage.name))
        assertFalse(root.resolve("main.kt").exists())
        assertEquals("foreign replacement", stage.readText())
    }

    @Test fun everyMoveModePreservesRecreatedSourceAndCommittedDestinationOnSyncFailure() {
        for (strict in listOf(false, true)) workspace { root ->
            root.resolve("main.kt").writeText("original")
            val repo = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Move) {
                    root.resolve("main.kt").writeText("recreated source")
                    error("sync failed")
                }
            })
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.move("main.kt", "renamed.kt", strict) } }
            assertEquals("original", root.resolve("renamed.kt").readText())
            assertEquals("recreated source", root.resolve("main.kt").readText())
        }
    }

    @Test fun everyMoveModeKeepsInPlaceDestinationEditsAtTheNewPath() {
        for (strict in listOf(false, true)) workspace { root ->
            root.resolve("main.kt").writeText("original")
            val repo = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Move) {
                    val destination = root.resolve("renamed.kt")
                    val before = Files.getLastModifiedTime(destination.toPath())
                    destination.writeText("modified") // Same length as the original; verify mtime too.
                    Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(before.toMillis() + 2000))
                    error("sync failed")
                }
            })
            assertThrows(IllegalStateException::class.java) { runBlocking { repo.move("main.kt", "renamed.kt", strict) } }
            assertFalse(root.resolve("main.kt").exists())
            assertEquals("modified", root.resolve("renamed.kt").readText())
        }
    }

    @Test fun failedDirectoryMoveLeavesTheTreeAtDestinationForExplicitRecovery() = workspace { root ->
        root.resolve("src").mkdir()
        root.resolve("src/main.kt").writeText("original")
        val repo = FileRepository(root, onMutation = { mutation ->
            if (mutation is FileRepository.Mutation.Move) {
                root.resolve("dest/main.kt").writeText("new editor work")
                error("sync offline")
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) { runBlocking { repo.move("src", "dest") } }
        assertTrue(failure.message!!.contains("dest"))
        assertFalse(root.resolve("src").exists())
        assertEquals("new editor work", root.resolve("dest/main.kt").readText())
    }

    @Test fun sourceCreatedAtTheFinalRollbackBoundaryCannotBeReplaced() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        var moves = 0
        val repo = FileRepository(root,
            onMutation = { if (it is FileRepository.Mutation.Move) error("sync offline") },
            newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { source, destination ->
                moves++
                if (moves == 2) destination.writeText("concurrent source")
                Files.move(source.toPath(), destination.toPath())
            })
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.move("main.kt", "renamed.kt") } }
        assertEquals(2, moves)
        assertEquals("concurrent source", root.resolve("main.kt").readText())
        assertEquals("original", root.resolve("renamed.kt").readText())
    }

    @Test fun finalMoveCollisionFailsWithoutAnyCopyOrReplaceFallback() = workspace { root ->
        root.resolve("main.kt").writeText("original")
        var mutations = 0
        val repo = FileRepository(root, onMutation = { mutations++ },
            newFilePublisher = NewProjectFilePublisher.platform,
            pathMover = WorkspacePathMove { source, destination ->
                destination.writeText("terminal destination")
                Files.move(source.toPath(), destination.toPath())
            })
        assertThrows(FileAlreadyExistsException::class.java) { runBlocking { repo.move("main.kt", "renamed.kt") } }
        assertEquals(0, mutations)
        assertEquals("original", root.resolve("main.kt").readText())
        assertEquals("terminal destination", root.resolve("renamed.kt").readText())
    }

    @Test fun cancellationDuringDirectoryDeleteKeepsItsTypeAndRecoverableStage() = workspace { root ->
        root.resolve("src").mkdir()
        root.resolve("src/main.kt").writeText("original")
        val cancelled = CancellationException("stopped")
        val repo = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Delete) throw cancelled })
        assertOriginatingFailure(cancelled, assertThrows(CancellationException::class.java) { runBlocking { repo.delete("src") } })
        val stage = root.resolve(LocalDirectoryDeleteCustody.HOME).listFiles()!!.single().resolve("original")
        assertEquals("original", stage.resolve("main.kt").readText())
        assertTrue(cancelled.suppressed.any { it.message!!.contains(stage.name) })
    }

    private fun workspace(test: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-mutation-recovery-").toFile()
        try {
            root.resolve("dependency").writeText("dependency")
            test(root)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
