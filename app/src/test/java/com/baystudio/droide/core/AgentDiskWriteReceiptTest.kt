package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/** Small disk-only integration cases. External execution surfaces are explicitly fail-closed. */
class AgentDiskWriteReceiptTest {
    @Test(timeout = 15_000)
    fun ownedWriteHasOneUsableUndoEntry() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        withContext(root, FileRepository(root)) { ctx ->
            val result = write(ctx)
            assertTrue(result, result.startsWith("OK:"))
            assertEquals(AFTER, root.resolve(PATH).readText())
            assertEquals(1, ctx.history.depth())
            assertTrue(ctx.history.undo().startsWith("Undone:"))
            assertEquals(BEFORE, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun refusalWithoutAnyWriteDiscardsOnlyTheUnneededBackup() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val files = FileRepository(root, beforeMutation = { throw IOException("before publication") })
        withContext(root, files) { ctx ->
            assertTrue(write(ctx).startsWith("ERROR:"))
            assertEquals(BEFORE, root.resolve(PATH).readText())
            assertEquals(0, ctx.history.depth())
            assertTrue(backups(root).isEmpty())
        }
    }

    @Test(timeout = 15_000)
    fun unownedMatchingBytesKeepReviewedOriginalForRecovery() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val files = FileRepository(root, beforeMutation = {
            root.resolve(PATH).writeText(AFTER)
            throw IOException("foreign write before refusal")
        })
        withContext(root, files) { ctx ->
            val result = write(ctx)
            assertRecovery(result, ctx, root)
            assertEquals(AFTER, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun forgedPrePublicationSyncFailureCannotClaimSavedStatus() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val files = FileRepository(root, beforeMutation = {
            root.resolve(PATH).writeText(AFTER)
            throw ProjectFileSyncException(PATH, DiskFileVersion.fromText(AFTER).bytes, IOException("forged receipt"))
        })
        withContext(root, files) { ctx ->
            val result = write(ctx)
            assertRecovery(result, ctx, root)
            assertFalse(result, result.contains("saved locally", ignoreCase = true))
            assertEquals(AFTER, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun actualPostPublicationSyncFailureKeepsSavedStatusAndUndo() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        var failOnce = true
        var writeCalls = 0
        val files = FileRepository(root, beforeMutation = { if (it is FileRepository.Mutation.Write) writeCalls++ },
            onMutation = { if (it is FileRepository.Mutation.Write && failOnce) {
                failOnce = false
                throw IOException("mirror unavailable")
            } })
        withContext(root, files) { ctx ->
            val result = write(ctx)
            assertTrue(result, result.startsWith("OK:"))
            assertTrue(result, result.contains("saved locally"))
            assertTrue(result, result.contains("synchronization pending"))
            assertEquals(1, writeCalls)
            assertEquals(AFTER, root.resolve(PATH).readText())
            assertEquals(1, ctx.history.depth())
            assertTrue(ctx.history.undo().startsWith("Undone:"))
            assertEquals(BEFORE, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun cancellationAfterPublicationKeepsTheOriginatingFailureBytesAndUndo() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val stopped = CancellationException("fixture stopped after commit")
        var stopOnce = true
        val files = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write && stopOnce) {
            stopOnce = false
            throw stopped
        } })
        withContext(root, files) { ctx ->
            val failure = try { write(ctx); fail("Expected cancellation"); null } catch (actual: CancellationException) { actual }
            assertTrue("Original cancellation must remain in the cause chain", generateSequence(failure as Throwable?) { it.cause }.any { it === stopped })
            assertEquals(AFTER, root.resolve(PATH).readText())
            assertEquals(1, ctx.history.depth())
            assertTrue(ctx.history.undo().startsWith("Undone:"))
            assertEquals(BEFORE, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAfterSyncErrorGetsRecoveryOnly() = foreignReplacement(failSync = true)

    @Test(timeout = 15_000)
    fun sameByteForeignReplacementAfterNormalObserverReturnGetsRecoveryOnly() = foreignReplacement(failSync = false)

    @Test(timeout = 15_000)
    fun ownedInodeMovedBehindLeafSymlinkGetsRecoveryOnly() = workspace { root ->
        val target = root.resolve(PATH).apply { writeText(BEFORE) }
        val moved = root.resolve("owned-moved.kt")
        var observerCalls = 0
        val files = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) {
            observerCalls++
            Files.move(target.toPath(), moved.toPath())
            Files.createSymbolicLink(target.toPath(), moved.toPath().fileName)
        } })
        withContext(root, files) { ctx ->
            assertRecovery(write(ctx), ctx, root)
            assertEquals(1, observerCalls)
            assertTrue(Files.isSymbolicLink(target.toPath()))
            assertTrue(Files.isSameFile(target.toPath(), moved.toPath()))
            assertEquals(AFTER, moved.readText())
        }
    }

    @Test(timeout = 15_000)
    fun ownedInodeMovedBehindAncestorSymlinkAtFinishGetsRecoveryOnly() = workspace { root ->
        val directory = root.resolve("src").apply { mkdir() }
        directory.resolve(PATH).writeText(BEFORE)
        val moved = root.resolve("owned-moved-src")
        var finishCalls = 0
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) {
                    finishCalls++
                    Files.move(directory.toPath(), moved.toPath())
                    Files.createSymbolicLink(directory.toPath(), moved.toPath().fileName)
                }
            }
        }
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        withContext(root, files) { ctx ->
            assertRecovery(write(ctx, "src/$PATH"), ctx, root)
            assertEquals(1, finishCalls)
            assertTrue(Files.isSymbolicLink(directory.toPath()))
            assertTrue(Files.isSameFile(directory.resolve(PATH).toPath(), moved.resolve(PATH).toPath()))
            assertEquals(AFTER, moved.resolve(PATH).readText())
        }
    }

    private fun foreignReplacement(failSync: Boolean) = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val files = FileRepository(root, onMutation = { if (it is FileRepository.Mutation.Write) {
            val replacement = root.resolve("foreign-stage").apply { writeText(AFTER) }
            Files.move(replacement.toPath(), root.resolve(PATH).toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (failSync) throw IOException("sync failed after foreign replacement")
        } })
        withContext(root, files) { ctx ->
            assertRecovery(write(ctx), ctx, root)
            assertEquals(AFTER, root.resolve(PATH).readText())
        }
    }

    @Test(timeout = 15_000)
    fun failedPublicationAttemptKeepsBackupEvenWhenOriginalAbsenceStillMatches() = workspace { root ->
        val attempts = AtomicInteger()
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher { _, _ ->
            attempts.incrementAndGet()
            throw IOException("publication outcome uncertain")
        })
        withContext(root, files) { ctx ->
            val result = write(ctx)
            assertTrue(result, result.startsWith("ERROR:"))
            assertTrue(result, result.contains("recovery backup retained"))
            assertEquals(1, attempts.get())
            assertFalse(root.resolve(PATH).exists())
            assertEquals(0, ctx.history.depth())
            assertEquals("", backups(root).single().readText())
        }
    }

    @Test(timeout = 15_000)
    fun finishRestoreToOriginalAfterAttemptStillKeepsRecoveryBackup() = workspace { root ->
        root.resolve(PATH).writeText(BEFORE)
        val participant = WorkspaceMutationParticipant { guard, mutation ->
            object : WorkspaceMutationSession(guard, mutation) {
                override fun finish(outcome: WorkspaceMutationOutcome) { root.resolve(PATH).writeText(BEFORE) }
            }
        }
        val files = FileRepository(root, newFilePublisher = NewProjectFilePublisher.platform, participant = participant)
        withContext(root, files) { ctx ->
            assertRecovery(write(ctx), ctx, root)
            assertEquals(BEFORE, root.resolve(PATH).readText())
        }
    }

    private fun assertRecovery(result: String, ctx: AgentTools.Ctx, root: File) {
        assertTrue(result, result.startsWith("ERROR:"))
        assertFalse(result, result.contains("saved locally", ignoreCase = true))
        assertTrue(result, result.contains("recovery backup retained"))
        assertEquals(0, ctx.history.depth())
        assertEquals(BEFORE, backups(root).single().readText())
    }

    private suspend fun write(ctx: AgentTools.Ctx, path: String = PATH): String = AgentTools.execute("write_file", buildJsonObject {
        put("path", path); put("content", AFTER)
    }, ctx)

    private fun backups(root: File): List<File> = root.resolve(".droide/backups").listFiles().orEmpty().filter { it.isFile }

    private fun withContext(root: File, files: FileRepository, body: suspend (AgentTools.Ctx) -> Unit) {
        val externalCalls = AtomicInteger()
        fun forbidden(): Nothing {
            externalCalls.incrementAndGet()
            throw AssertionError("External execution is forbidden in this disk receipt fixture")
        }
        val web = AgentWebResearch(root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
            override fun pageCount(file: File): Int = forbidden()
            override fun text(file: File, page: Int): String? = forbidden()
            override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
        })
        try {
            val lsp = object : LspManager {
                override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                override suspend fun diagnose(relPath: String): String = forbidden()
                override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
            }
            val ctx = AgentTools.Ctx(files, NoProcessTerminal(::forbidden), GitManager(root), ApprovalManager(), QuestionManager(),
                EditHistory(files), PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), AgentMode.BUILD,
                LlmClient(), AgentConfig(), root, TodoManager(), "receipt-integration", lsp, webResearch = web)
            runBlocking { withTimeout(8_000) { body(ctx) } }
            assertEquals(0, externalCalls.get())
        } finally { web.close() }
    }

    private class NoProcessTerminal(private val forbidden: () -> Nothing) : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private fun workspace(body: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-agent-write-receipt-").toFile()
        try { body(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object {
        private const val PATH = "main.kt"
        private const val BEFORE = "reviewed original"
        private const val AFTER = "requested replacement"
    }
}
