package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentDiskMutationWorkflowTest {
    @Test fun detachedProgressUiCannotReportACommittedPatchAsFailed() = workspace { root ->
        root.resolve("main.kt").writeText("old\n")
        val ctx = context(root, FileRepository(root)) { throw IllegalStateException("UI detached") }
        val result = runBlocking { AgentTools.execute("apply_patch", buildJsonObject {
            put("patchText", "*** Begin Patch\n*** Update File: main.kt\n@@\n-old\n+updated\n*** End Patch")
        }, ctx) }
        assertTrue(result, result.startsWith("OK:"))
        assertEquals("updated\n", root.resolve("main.kt").readText())
        assertEquals(1, ctx.history.depth())
        assertTrue(runBlocking { ctx.history.undo() }.startsWith("Undone:"))
        assertEquals("old\n", root.resolve("main.kt").readText())
    }

    @Test fun syncPendingWriteAndEditReportSavedAndRetainUsableUndo() {
        for (tool in TOOLS) workspace { root ->
            root.resolve("main.kt").writeText("old")
            var failSync = true
            val files = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write && failSync) {
                    failSync = false
                    throw IOException("mirror disconnected")
                }
            })
            val progress = mutableListOf<AgentToolProgress>()
            val ctx = context(root, files, progress::add)
            val result = runBlocking { execute(tool, ctx) }
            assertTrue(result, result.startsWith("OK:"))
            assertTrue(result, result.contains("synchronization pending"))
            assertTrue(result, result.contains("do not repeat"))
            assertEquals("updated", root.resolve("main.kt").readText())
            assertEquals(1, ctx.history.depth())
            assertEquals(1, progress.count { it.fileChanges != null })
            assertTrue(runBlocking { ctx.history.undo() }.startsWith("Undone:"))
            assertEquals("old", root.resolve("main.kt").readText())
            assertEquals(0, ctx.history.depth())
        }
    }

    @Test fun terminalChangeInsidePreWriteCallbackCannotBypassTheReviewedDiskVersion() {
        for (tool in TOOLS) workspace { root ->
            root.resolve("main.kt").writeText("old")
            val files = FileRepository(root, beforeMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) root.resolve("main.kt").writeText("new terminal work")
            })
            val ctx = context(root, files)
            val result = runBlocking { execute(tool, ctx) }
            assertTrue(result, result.startsWith("ERROR:"))
            assertTrue(result, result.contains("changed on disk"))
            assertEquals("new terminal work", root.resolve("main.kt").readText())
            assertEquals(0, ctx.history.depth())
        }
    }

    @Test fun newFileCreatedAfterReviewIsPreservedAndNotRecordedAsAnAgentEdit() = workspace { root ->
        val files = FileRepository(root, beforeMutation = { root.resolve("main.kt").writeText("new user file") })
        val ctx = context(root, files)
        val result = runBlocking { execute("write_file", ctx) }
        assertTrue(result, result.startsWith("ERROR:"))
        assertEquals("new user file", root.resolve("main.kt").readText())
        assertEquals(0, ctx.history.depth())
    }

    @Test fun foreignPostCommitReplacementNeverReceivesASavedReceiptOrAnUndoToken() {
        for (tool in TOOLS) workspace { root ->
            root.resolve("main.kt").writeText("old")
            val files = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write) {
                    val replacement = root.resolve("replacement").apply { writeText("foreign bytes") }
                    Files.move(replacement.toPath(), root.resolve("main.kt").toPath(), StandardCopyOption.REPLACE_EXISTING)
                    throw IOException("sync failed after replacement")
                }
            })
            val ctx = context(root, files)
            val result = runBlocking { execute(tool, ctx) }
            assertTrue(result, result.startsWith("ERROR:"))
            assertFalse(result.contains("saved locally"))
            assertTrue(result, result.contains("recovery backup retained"))
            assertEquals("foreign bytes", root.resolve("main.kt").readText())
            assertEquals(0, ctx.history.depth())
            assertEquals("old", root.resolve(".droide/backups").listFiles()!!.single().readText())
        }
    }

    @Test fun cancellationAfterCommitPropagatesButKeepsUndoForThePublishedFile() {
        for (tool in TOOLS) workspace { root ->
            root.resolve("main.kt").writeText("old")
            var stopOnce = true
            val cancelled = CancellationException("user stopped")
            val files = FileRepository(root, onMutation = { mutation ->
                if (mutation is FileRepository.Mutation.Write && stopOnce) {
                    stopOnce = false
                    throw cancelled
                }
            })
            val ctx = context(root, files)
            assertOriginatingFailure(cancelled, assertThrows(CancellationException::class.java) { runBlocking { execute(tool, ctx) } })
            assertEquals("updated", root.resolve("main.kt").readText())
            assertEquals(1, ctx.history.depth())
            assertTrue(runBlocking { ctx.history.undo() }.startsWith("Undone:"))
            assertEquals("old", root.resolve("main.kt").readText())
        }
    }

    @Test fun detachedProgressUiDoesNotTurnCommittedDiskWritesIntoToolErrors() {
        for (tool in TOOLS) workspace { root ->
            root.resolve("main.kt").writeText("old")
            val ctx = context(root, FileRepository(root)) { throw IllegalStateException("UI detached") }
            val result = runBlocking { execute(tool, ctx) }
            assertTrue(result, result.startsWith("OK:"))
            assertEquals("updated", root.resolve("main.kt").readText())
            assertEquals(1, ctx.history.depth())
        }
    }

    @Test fun preCommitFailureIsAnErrorAndCannotLeaveAUsableUndoEntry() = workspace { root ->
        root.resolve("main.kt").writeText("old")
        val files = FileRepository(root, beforeMutation = { throw IOException("permission denied") })
        val ctx = context(root, files)
        val result = runBlocking { execute("write_file", ctx) }
        assertTrue(result, result.startsWith("ERROR:"))
        assertFalse(result.contains("saved locally"))
        assertEquals("old", root.resolve("main.kt").readText())
        assertEquals(0, ctx.history.depth())
    }

    private suspend fun execute(tool: String, ctx: AgentTools.Ctx): String = AgentTools.execute(tool, buildJsonObject {
        put("path", "main.kt")
        if (tool == "write_file") put("content", "updated") else {
            put("search", "old")
            put("replace", "updated")
        }
    }, ctx)

    private fun context(root: File, files: FileRepository, onProgress: (AgentToolProgress) -> Unit = {}): AgentTools.Ctx {
        val base = agentWorkflowContext(root)
        return AgentTools.Ctx(files, base.terminal, base.git, base.approvals, base.questions,
            EditHistory(files), base.perms, base.mode, base.llm, base.llmConfig, root,
            base.todos, base.sessionId, base.lsp, onProgress = onProgress)
    }

    private fun workspace(test: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-agent-disk-mutation-").toFile()
        try { test(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    companion object { private val TOOLS = listOf("write_file", "edit_file") }
}
