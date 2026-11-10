package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentSearchInstructionsWorkflowTest {
    @Test fun advancedLiteralSearchHonorsGlobCaseWordsAndDirtyOverlay() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-search-options-").toFile()
        try {
            root.resolve("a.kt").writeText("stale disk Marker\n")
            root.resolve("b.txt").writeText("Marker\n")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", "Marker marker Markers\n"))
            val result = AgentWorkspaceSearch(FileRepository(root), documents).search("Marker", options = TextSearchOptions(
                caseSensitive = true, wholeWord = true, includeGlob = "*.kt"))
            assertTrue(result.contains("a.kt:1:")); assertTrue(result.contains("column=1"))
            assertFalse(result.contains("stale disk")); assertFalse(result.contains("b.txt:"))
            assertTrue(result.contains("returned=1")); assertTrue(result.contains("result_limit=false"))
            val dispatched = AgentTools.execute("grep", buildJsonObject {
                put("query", "Marker"); put("case_sensitive", true); put("whole_word", true); put("include_glob", "*.kt")
            }, agentWorkflowContext(root, documents = documents))
            assertEquals(result, dispatched)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun unreadableDirtyLargeBufferNeverLeaksStaleDiskHits() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-large-overlay-").toFile()
        try {
            root.resolve("a.kt").writeText("stale secretneedle\n")
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", "", WorkspaceDocumentKind.LARGE, editable = false))
            val result = AgentWorkspaceSearch(FileRepository(root), documents).search("secretneedle")
            assertFalse(result.contains("a.kt:1:")); assertTrue(result.contains("skipped_dirty=1"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun searchOutputLimitsAreVisibleBeforeTheExcerpt() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-search-limit-").toFile()
        try {
            root.resolve("a.kt").writeText((1..100).joinToString("\n") { "needle " + "x".repeat(300) })
            val result = AgentWorkspaceSearch(FileRepository(root)).search("needle", 100)
            assertTrue(result.contains("output_limit=true")); assertTrue(result.contains("Results are incomplete"))
            assertTrue(result.length < 10_000)
            assertTrue(runCatching { AgentWorkspaceSearch(FileRepository(root)).search("needle", 501) }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun readFullAndRangeExposeAContinuationWithinTheEngineBudget() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-read-page-").toFile()
        try {
            val text = (1..100).joinToString("\n") { "line$it " + "x".repeat(300) }
            root.resolve("a.kt").writeText(text)
            val documents = AgentWorkflowDocuments(agentWorkflowSnapshot("a.kt", text))
            val full = AgentWorkspaceReader(FileRepository(root), documents).read("a.kt")
            assertTrue(full.contains("truncated=true")); assertTrue(full.contains("next_start=")); assertTrue(full.length < 12_000)
            val ranged = AgentWorkspaceReader(FileRepository(root), documents).read("a.kt", 1, 100)
            assertTrue(ranged.contains("next_start=")); assertTrue(ranged.length < 12_000)
            val diskRange = AgentWorkspaceReader(FileRepository(root)).read("a.kt", 1, 100)
            assertTrue(diskRange.contains("next_start=")); assertTrue(diskRange.length < 12_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun scopedInstructionsUseAncestorsInOrderAndNeverSiblingRules() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-scoped-instructions-").toFile()
        try {
            root.resolve("src/deep").mkdirs(); root.resolve("other").mkdirs()
            root.resolve("AGENTS.md").writeText("root instruction")
            root.resolve("src/AGENTS.md").writeText("src instruction")
            root.resolve("src/deep/AGENTS.md").writeText("nearest instruction")
            root.resolve("other/AGENTS.md").writeText("sibling must not apply")
            val files = FileRepository(root)
            assertEquals(listOf("AGENTS.md", "src/AGENTS.md", "src/deep/AGENTS.md"), AgentProjectInstructions.scopedPaths(files, "src/deep/new.kt"))
            val instructions = AgentProjectInstructions.loadScoped(files, "src/deep/new.kt")
            assertTrue(instructions.indexOf("root instruction") < instructions.indexOf("nearest instruction"))
            assertFalse(instructions.contains("sibling must not apply"))
            assertTrue(runCatching { AgentProjectInstructions.scopedPaths(files, "../outside/a") }.isFailure)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun instructionsReadPermissionIsCheckedForEveryApplicableFile() = runBlocking {
        val root = Files.createTempDirectory("droide-agent-instructions-permission-").toFile()
        try {
            root.resolve("src").mkdirs(); root.resolve("AGENTS.md").writeText("allowed")
            root.resolve("src/AGENTS.md").writeText("restricted")
            val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("read", "src/AGENTS.md", PermEffect.DENY)))
            val output = AgentTools.execute("project_instructions", buildJsonObject { put("path", "src/new.kt") }, agentWorkflowContext(root, permissions = deny))
            assertTrue(output.startsWith("DENIED")); assertFalse(output.contains("restricted"))
            root.resolve("AGENTS.md").writeText("x".repeat(11_000))
            assertTrue(AgentProjectInstructions.loadScoped(FileRepository(root), "src/new.kt").contains("incomplete=true"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
