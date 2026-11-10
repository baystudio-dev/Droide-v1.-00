package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentInitialInstructionsCompletenessTest {
    @Test fun longRootInstructionsAdvertiseTheMissingTailBeforeThePartialBody() = runBlocking {
        val root = Files.createTempDirectory("droide-instructions-tail-").toFile()
        try {
            root.resolve("AGENTS.md").writeText("x".repeat(8_500) + "\nDo not modify generated files")
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(brief.startsWith("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]"))
            assertTrue(brief.contains("AGENTS.md"))
            assertTrue(brief.contains("read_file"))
            assertTrue(brief.contains("file_limit"))
            assertTrue(brief.length <= 28_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun oversizedRootInstructionsAreVisibleRatherThanSilentlyAbsent() = runBlocking {
        val root = Files.createTempDirectory("droide-instructions-oversize-").toFile()
        try {
            root.resolve("GEMINI.md").writeText("z".repeat(60_000))
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(brief.startsWith("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]"))
            assertTrue(brief.contains("GEMINI.md"))
            assertTrue(brief.contains("file_limit"))
            assertTrue(brief.length <= 28_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun aggregateBudgetPreservesTheIncompleteNoticeAndEverySourceIdentity() = runBlocking {
        val root = Files.createTempDirectory("droide-instructions-total-").toFile()
        try {
            val rules = root.resolve(".agents/rules").apply { mkdirs() }
            for (n in 1..4) rules.resolve("rule$n.md").writeText("---\ntrigger: always_on\n---\n" + "a".repeat(10_500))
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(brief.startsWith("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]"))
            assertTrue(brief.contains("total_limit"))
            for (n in 1..4) assertTrue(brief.contains(".agents/rules/rule$n.md"))
            assertTrue(brief.length <= 28_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun smallCompleteInstructionsArePreservedWithoutAFalseIncompleteWarning() = runBlocking {
        val root = Files.createTempDirectory("droide-instructions-complete-").toFile()
        try {
            root.resolve(".droide").mkdirs()
            root.resolve("AGENTS.md").writeText("Root rule")
            root.resolve(".droide/AGENTS.md").writeText("Workspace rule")
            root.resolve("GEMINI.md").writeText("Model rule")
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertFalse(brief.contains("incomplete=true"))
            for (text in listOf("Root rule", "Workspace rule", "Model rule")) assertTrue(brief.contains(text))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun oversizedAlwaysOnRulesHaveAVisibleSourceAndLimit() = runBlocking {
        val root = Files.createTempDirectory("droide-rules-oversize-").toFile()
        try {
            root.resolve(".agents/rules").mkdirs()
            root.resolve(".agents/rules/large.md").writeText("---\ntrigger: always_on\n---\n" + "a".repeat(20_000))
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(brief.startsWith("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]"))
            assertTrue(brief.contains(".agents/rules/large.md"))
            assertTrue(brief.contains("file_limit"))
            assertTrue(brief.length <= 28_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun ruleCountLimitTellsTheAgentToInspectTheRuleDirectories() = runBlocking {
        val root = Files.createTempDirectory("droide-rules-count-").toFile()
        try {
            root.resolve(".agents/rules").mkdirs()
            for (n in 1..25) root.resolve(".agents/rules/rule$n.md").writeText("---\ntrigger: always_on\n---\nRule $n")
            val brief = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(brief.startsWith("[DROIDE_PROJECT_INSTRUCTIONS incomplete=true]"))
            assertTrue(brief.contains("rule_limit"))
            assertTrue(brief.contains(".agents/rules"))
            assertTrue(brief.length <= 28_000)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
