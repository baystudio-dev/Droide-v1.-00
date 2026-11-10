package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CodexPatchWorkflowTest {
    private fun patch(body: String) = PatchTool.parse("*** Begin Patch\n$body\n*** End Patch")

    @Test fun canonicalAddStripsPrefixesAndLegacyAddRemainsLiteral() {
        assertEquals("first\n+second\n\n", (patch("*** Add File: new.txt\n+first\n++second\n+").single() as PatchTool.Op.Add).content)
        assertEquals("+first", (PatchTool.parse("*** Add File: old.txt\n+first").single() as PatchTool.Op.Add).content)
    }

    @Test fun firstHunkWithoutHeaderAndEmptyContextLinesAreAccepted() {
        val update = patch("*** Update File: a\n before\n\n-old\n+new\n after").single() as PatchTool.Op.Update
        assertEquals("before\n\nnew\nafter\n", PatchTool.updatedText("before\n\nold\nafter\n", update))
    }

    @Test fun multipleHunksPreserveUntouchedTextAndCrLf() {
        val update = patch("*** Update File: a\n@@\n first\n-old\n+new\n@@\n last\n-old2\n+new2\n*** End of File").single() as PatchTool.Op.Update
        assertEquals("first\r\nnew\r\nmiddle\r\nlast\r\nnew2\r\n", PatchTool.updatedText("first\r\nold\r\nmiddle\r\nlast\r\nold2\r\n", update))
    }

    @Test fun ambiguousContextFailsWhileHeadingAndEndMarkerDisambiguate() {
        val ambiguous = patch("*** Update File: a\n@@\n-old\n+new").single() as PatchTool.Op.Update
        assertThrows(IllegalArgumentException::class.java) { PatchTool.updatedText("section\nold\nnext\nold\n", ambiguous) }
        val anchored = patch("*** Update File: a\n@@ next\n-old\n+new\n*** End of File").single() as PatchTool.Op.Update
        assertEquals("section\nold\nnext\nnew\n", PatchTool.updatedText("section\nold\nnext\nold\n", anchored))
    }

    @Test fun invalidSecondFilePreflightNeverWritesFirstFile() = runBlocking {
        val root = Files.createTempDirectory("droide-codex-atomic-").toFile()
        try {
            root.resolve("a").writeText("old\n"); root.resolve("b").writeText("different\n")
            val ctx = agentWorkflowContext(root)
            val ops = patch("*** Update File: a\n@@\n-old\n+new\n*** Update File: b\n@@\n-missing\n+new")
            assertTrue(runCatching { PatchTool.apply(ops, ctx.files, ctx.history) }.isFailure)
            assertEquals("old\n", root.resolve("a").readText())
            assertEquals("different\n", root.resolve("b").readText())
            assertEquals(0, ctx.history.depth())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun reviewedPatchRejectsChangedDiskSnapshot() = runBlocking {
        val root = Files.createTempDirectory("droide-codex-stale-").toFile()
        try {
            root.resolve("a").writeText("old\n")
            val ctx = agentWorkflowContext(root)
            val ops = patch("*** Update File: a\n@@\n-old\n+new")
            val reviewed = PatchTool.reviewSnapshot(ops, ctx.files)
            root.resolve("a").writeText("user change\nold\n")
            assertTrue(runCatching { PatchTool.applyReviewed(ops, ctx.files, ctx.history, reviewed) }.isFailure)
            assertEquals("user change\nold\n", root.resolve("a").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun addMoveDeleteAndUndoAreOneTransaction() = runBlocking {
        val root = Files.createTempDirectory("droide-codex-move-").toFile()
        try {
            root.resolve("old").writeText("before\n"); root.resolve("delete").writeText("keep for undo\n")
            val ctx = agentWorkflowContext(root)
            val ops = patch("*** Add File: added\n+hello\n*** Update File: old\n*** Move to: renamed\n@@\n-before\n+after\n*** Delete File: delete")
            PatchTool.apply(ops, ctx.files, ctx.history)
            assertEquals("after\n", root.resolve("renamed").readText())
            assertEquals("hello\n", root.resolve("added").readText())
            assertFalse(root.resolve("old").exists()); assertFalse(root.resolve("delete").exists())
            assertEquals(1, ctx.history.depth())
            ctx.history.undo()
            assertEquals("before\n", root.resolve("old").readText())
            assertEquals("keep for undo\n", root.resolve("delete").readText())
            assertFalse(root.resolve("renamed").exists()); assertFalse(root.resolve("added").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun liveCanonicalPatchUsesCasAndPreservesDisk() = runBlocking {
        val root = Files.createTempDirectory("droide-codex-live-").toFile()
        try {
            root.resolve("a").writeText("disk\n")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "unsaved\n"))
            val ctx = agentWorkflowContext(root, documents = documents)
            val ops = patch("*** Update File: a\n@@\n-unsaved\n+changed")
            val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, ctx.files, documents)
            PatchTool.applyWorkspaceReviewed(ops, ctx.files, ctx.history, documents, ctx.lsp, reviewed)
            assertEquals("changed\n", documents.current.content)
            assertEquals("disk\n", root.resolve("a").readText())
            ctx.history.undo()
            assertEquals("unsaved\n", documents.current.content)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun malformedWrappersPrefixesAndTraversalAreRejected() = runBlocking {
        listOf("*** Begin Patch\n*** Add File: a\nno plus\n*** End Patch", "*** Begin Patch\n*** End Patch\ntrailing").forEach {
            assertTrue(runCatching { PatchTool.parse(it) }.isFailure)
        }
        val root = Files.createTempDirectory("droide-codex-path-").toFile()
        try { assertTrue(runCatching { PatchTool.reviewSnapshot(patch("*** Add File: ../outside\n+bad"), FileRepository(root)) }.isFailure) }
        finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun expensiveAmbiguitySearchStopsBeforeMutatingInsteadOfExhaustingMobileResources() {
        val text = (1..30).joinToString("\n") { "row$it" } + "\n" + "pad\n".repeat(150_000)
        val hunks = (1..30).map { CodexPatchHunk(null, listOf("row$it"), listOf("updated$it"), false) }
        val failure = assertThrows(IllegalArgumentException::class.java) { CodexPatchParser.apply(text, hunks, "large.kt") }
        assertTrue(failure.message.orEmpty().contains("mobile work budget"))
    }
}
