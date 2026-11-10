package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentFileChangeTest {
    @Test fun separatedEditsExcludeUnchangedMiddleLines() {
        val change = AgentFileChanges.preview("Main.kt", "one\nkeep\nkeep too\nfour\n", "ONE\nkeep\nkeep too\nFOUR\n")
        assertEquals(2, change.addedLines)
        assertEquals(2, change.removedLines)
        val lines = UnifiedDiffParser.parse(change.diff).flatMap { it.hunks }.flatMap { it.lines }
        assertEquals(2, lines.count { it.kind == DiffLineKind.ADD })
        assertTrue(lines.any { it.kind == DiffLineKind.CONTEXT && it.text == "keep" })
    }

    @Test fun creationDeletionAndNoOpHaveHonestCounts() {
        val created = AgentFileChanges.preview("a.js", null, "a\nb\nc\n")
        assertEquals(3, created.addedLines); assertEquals(0, created.removedLines)
        assertTrue(created.diff.contains("--- /dev/null"))
        val deleted = AgentFileChanges.preview("a.js", "a\nb\n", null)
        assertEquals(0, deleted.addedLines); assertEquals(2, deleted.removedLines)
        assertTrue(deleted.diff.contains("+++ /dev/null"))
        val unchanged = AgentFileChanges.preview("a.js", "a\n", "a\n")
        assertEquals(0, unchanged.addedLines); assertFalse(unchanged.truncated)
    }

    @Test fun boundedPreviewKeepsFullCountsAndLargeFilesDoNotInventCounts() {
        val change = AgentFileChanges.preview("big.js", null, (1..500).joinToString("\n") { "const line$it = '$it';" }, limit = 768)
        assertEquals(500, change.addedLines); assertTrue(change.truncated)
        assertTrue(change.diff.toByteArray().size <= 768)
        val oversized = AgentFileChanges.preview("huge", "", "x".repeat(800_000))
        assertNull(oversized.addedLines); assertTrue(oversized.truncated)
    }

    @Test fun writeAndEditPublishDiffEvidenceAfterMutationWithoutInflatingToolResponse() = runBlocking {
        val root = Files.createTempDirectory("droide-diff-tools-").toFile()
        try {
            val progress = mutableListOf<AgentToolProgress>()
            val ctx = agentWorkflowContext(root, onProgress = progress::add)
            val write = AgentTools.execute("write_file", buildJsonObject { put("path", "main.js"); put("content", "const x = 1;\n") }, ctx)
            assertEquals("const x = 1;\n", root.resolve("main.js").readText())
            assertTrue(write.startsWith("OK:")); assertFalse(write.contains("@@"))
            assertEquals(1, progress.last().fileChanges!!.single().addedLines)
            AgentTools.execute("edit_file", buildJsonObject { put("path", "main.js"); put("search", "1"); put("replace", "2") }, ctx)
            val edit = progress.last().fileChanges!!.single()
            assertEquals(1, edit.addedLines); assertEquals(1, edit.removedLines)
            assertTrue(edit.diff.contains("+const x = 2;"))
            ctx.history.undo()
            assertEquals("const x = 1;\n", root.resolve("main.js").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun deniedAndConflictingEditsNeverPublishAppliedChanges() = runBlocking {
        val root = Files.createTempDirectory("droide-diff-denied-").toFile()
        try {
            root.resolve("a").writeText("disk\n")
            val progress = mutableListOf<AgentToolProgress>()
            val denied = agentWorkflowContext(root, permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.DENY))), onProgress = progress::add)
            assertTrue(AgentTools.execute("write_file", buildJsonObject { put("path", "a"); put("content", "bad") }, denied).startsWith("DENIED"))
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "unsaved\n"))
            val ctx = agentWorkflowContext(root, documents = docs, onProgress = progress::add)
            assertTrue(AgentTools.execute("write_file", buildJsonObject { put("path", "a"); put("content", "bad"); put("expected_revision", 99); put("expected_change_version", 99) }, ctx).startsWith("ERROR:"))
            assertTrue(progress.none { it.fileChanges != null })
            assertEquals("disk\n", root.resolve("a").readText())
            assertEquals("unsaved\n", docs.current.content)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun liveEditDiffUsesUnsavedBufferAndPreservesDisk() = runBlocking {
        val root = Files.createTempDirectory("droide-diff-live-").toFile()
        try {
            root.resolve("a").writeText("disk\n")
            val docs = AgentWorkflowDocuments(agentWorkflowSnapshot("a", "unsaved\n"))
            val progress = mutableListOf<AgentToolProgress>()
            val ctx = agentWorkflowContext(root, documents = docs, onProgress = progress::add)
            val result = AgentTools.execute("edit_file", buildJsonObject {
                put("path", "a"); put("search", "unsaved"); put("replace", "changed")
                put("expected_document_id", docs.current.documentId)
                put("expected_revision", docs.current.revision); put("expected_change_version", docs.current.changeVersion)
            }, ctx)
            assertTrue(result.startsWith("OK:"))
            val diff = progress.last().fileChanges!!.single().diff
            assertTrue(diff.contains("-unsaved")); assertTrue(diff.contains("+changed")); assertFalse(diff.contains("disk"))
            assertEquals("disk\n", root.resolve("a").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun transactionalPatchRetainsPerFileDiffsIncludingMoveAndDeletion() = runBlocking {
        val root = Files.createTempDirectory("droide-diff-patch-").toFile()
        try {
            root.resolve("old").writeText("before\n"); root.resolve("gone").writeText("remove me\n")
            val progress = mutableListOf<AgentToolProgress>()
            val ctx = agentWorkflowContext(root, onProgress = progress::add)
            val patch = "*** Begin Patch\n*** Add File: added.js\n+hello\n*** Update File: old\n*** Move to: renamed\n@@\n-before\n+after\n*** Delete File: gone\n*** End Patch"
            val result = AgentTools.execute("apply_patch", buildJsonObject { put("patchText", patch) }, ctx)
            assertTrue(result, result.startsWith("OK:"))
            val changes = progress.last().fileChanges!!
            assertEquals(listOf("added.js", "renamed", "gone"), changes.map { it.path })
            assertEquals("old", changes[1].previousPath)
            assertEquals(1, changes[1].addedLines); assertEquals(1, changes[1].removedLines)
            assertEquals(1, changes[2].removedLines)
            ctx.history.undo()
            assertEquals("before\n", root.resolve("old").readText()); assertTrue(root.resolve("gone").exists())
            assertFalse(root.resolve("added.js").exists())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun durablePartsKeepDiffMetadataAndOldHistoryStillDecodes() {
        val old = """{"id":"old","step":1,"kind":"TOOL","state":"COMPLETED"}"""
        assertTrue(Json.decodeFromString<AgentStreamPart>(old).fileChanges.isEmpty())
        val part = Json.decodeFromString<AgentStreamPart>(old).copy(fileChanges = listOf(AgentFileChanges.preview("a", "old", "new")))
        assertEquals(part, Json.decodeFromString<AgentStreamPart>(Json.encodeToString(part)))
    }

    @Test fun lspFormattingKeepsReviewedDiffAndRefusesChangedFile() = runBlocking {
        val root = Files.createTempDirectory("droide-diff-lsp-").toFile()
        try {
            root.resolve("a.kt").writeText("val x=1\n")
            val files = FileRepository(root)
            val lsp = object : LspManager {
                override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                override suspend fun diagnose(relPath: String) = ""
                override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
                override suspend fun formatDocument(path: String) = LspWorkspaceEdit(
                    listOf(LspTextEdit(path, TextRangeEdit(1, 1, 1, 8, "val x = 1"))), expectedContent = mapOf(path to "val x=1\n"))
            }
            val actions = AgentLspEditActions(files, lsp, EditHistory(files), null)
            val prepared = actions.prepareFormat("a.kt")
            assertEquals(1, prepared.fileChanges.single().addedLines)
            assertTrue(prepared.preview.contains("+val x = 1"))
            assertTrue(actions.apply(prepared).startsWith("LSP_EDIT_APPLIED"))
            assertEquals("val x = 1\n", root.resolve("a.kt").readText())
            root.resolve("a.kt").writeText("user edit\n")
            assertTrue(runCatching { actions.apply(prepared) }.isFailure)
            assertEquals("user edit\n", root.resolve("a.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun legacyHunksAndMultipleFilesRemainColoredAndSeparate() {
        val legacy = UnifiedDiffParser.parse("@@ old 1-1 | new 1-1 @@\n-old\n+new")
        assertEquals(DiffLineKind.ADD, legacy.single().hunks.single().lines.last().kind)
        val combined = AgentFileChanges.preview("a", "old\n", "new\n").diff + AgentFileChanges.preview("b", null, "new\n").diff
        assertEquals(listOf("a", "b"), UnifiedDiffParser.parse(combined).map { it.newPath })
    }

    @Test fun incrementDecrementCodeIsNotConfusedWithFileHeaders() {
        val change = AgentFileChanges.preview("count.cpp", "-- count;\n--counter;\n", "++ count;\n++counter;\n")
        val file = UnifiedDiffParser.parse(change.diff).single()
        assertEquals("count.cpp", file.newPath)
        val lines = file.hunks.single().lines
        assertEquals(2, lines.count { it.kind == DiffLineKind.ADD })
        assertEquals(2, lines.count { it.kind == DiffLineKind.REMOVE })
        assertTrue(lines.any { it.kind == DiffLineKind.ADD && it.text == "++counter;" })
    }

    @Test fun truncatedMultiFilePreviewsStillKeepTheirFileBoundaries() {
        val first = AgentFileChanges.preview("a.js", null, "first\n".repeat(200), limit = 256)
        val second = AgentFileChanges.preview("b.js", null, "second\n".repeat(200), limit = 256)
        assertTrue(first.truncated)
        val files = UnifiedDiffParser.parse(first.diff + second.diff)
        assertEquals(listOf("a.js", "b.js"), files.map { it.newPath })
    }
}
