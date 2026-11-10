package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PatchCaretReviewTest {
    private fun scenario(move: (WorkspaceDocumentSnapshot) -> WorkspaceDocumentSnapshot, accepted: Boolean) = runBlocking {
        val root = Files.createTempDirectory("r62-caret-review").toFile()
        try {
            root.resolve("a").writeText("disk\n")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "unsaved\n"))
            val ctx = agentWorkflowContext(root, documents = documents)
            val ops = PatchTool.parse("*** Begin Patch\n*** Update File: a\n@@\n-unsaved\n+changed\n*** End Patch")
            val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, ctx.files, documents)
            documents.current = move(documents.current)
            val edited = documents.current
            val result = runCatching { PatchTool.applyWorkspaceReviewed(ops, ctx.files, ctx.history, documents, ctx.lsp, reviewed) }
            assertEquals(accepted, result.isSuccess)
            assertEquals("disk\n", root.resolve("a").readText())
            if (accepted) {
                assertEquals("changed\n", documents.current.content)
                ctx.history.undo()
                assertEquals("unsaved\n", documents.current.content)
            } else assertEquals(edited, documents.current)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun caretOnlyMoveDoesNotInvalidateApproval() = scenario({ it.copy(selectionStart = 2, selectionEnd = 6) }, true)
    @Test fun sameTextNewRevisionStillInvalidatesApproval() = scenario({ it.copy(revision = it.revision + 1) }, false)
    @Test fun changedUserTextStillInvalidatesApproval() = scenario({ it.copy(content = "new user text\n", changeVersion = it.changeVersion + 1) }, false)
    @Test fun readOnlyChangeStillInvalidatesApproval() = scenario({ it.copy(editable = false) }, false)
}
