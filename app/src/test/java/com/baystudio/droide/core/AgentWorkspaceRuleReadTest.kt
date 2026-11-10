package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class AgentWorkspaceRuleReadTest {
    private suspend fun withRule(block: suspend (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("bounded-workspace-rule").toFile()
        try { block(root.resolve("rule.md")) } finally { root.deleteRecursively() }
    }
    @Test fun exactByteLimitPreservesWholeRule() = runBlocking {
        withRule { file ->
            val body = "x".repeat(12_000)
            file.writeText(body)
            assertEquals(body, AgentProjectInstructions.readWorkspaceRule(file))
        }
    }
    @Test fun growthAfterMetadataObservationNeverReturnsTruncatedGuidance() = runBlocking {
        withRule { file ->
            file.writeText("small")
            assertTrue(file.length() < 12_000)
            file.appendText("x".repeat(20_000))
            try { AgentProjectInstructions.readWorkspaceRule(file); fail("Grown rule must not be returned") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun limitCountsUtf8BytesRatherThanCharacters() = runBlocking {
        withRule { file ->
            file.writeText("é".repeat(6_000))
            assertEquals("é".repeat(6_000), AgentProjectInstructions.readWorkspaceRule(file))
            file.appendText("é")
            try { AgentProjectInstructions.readWorkspaceRule(file); fail("Over byte limit") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun malformedUtf8DoesNotBecomeSilentReplacementInstructions() = runBlocking {
        withRule { file ->
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            try { AgentProjectInstructions.readWorkspaceRule(file); fail("Malformed UTF-8") }
            catch (_: CharacterCodingException) { }
        }
    }
    @Test fun finalSymlinkIsNotFollowed() = runBlocking {
        withRule { file ->
            val target = file.parentFile.resolve("target.md").apply { writeText("outside guidance") }
            Files.createSymbolicLink(file.toPath(), target.toPath())
            try { AgentProjectInstructions.readWorkspaceRule(file); fail("Symlink must not be followed") }
            catch (_: java.io.IOException) { }
        }
    }
    @Test fun malformedWorkspaceRuleReportsItsSourceAsUnreadable() = runBlocking {
        val root = Files.createTempDirectory("workspace-rule-diagnostic").toFile()
        try {
            val rule = root.resolve(".agents/rules/example.md").apply { parentFile.mkdirs() }
            rule.writeBytes("---\ntrigger: always_on\n---\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28))
            val result = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(result.contains("incomplete=true"))
            assertTrue(result.contains(".agents/rules/example.md (unreadable)"))
        } finally { root.deleteRecursively() }
    }
    @Test fun normalQuotedRuleBodyStillLoadsThroughPublicApi() = runBlocking {
        val root = Files.createTempDirectory("workspace-rule-body").toFile()
        try {
            root.resolve(".agent/rules/example.md").apply { parentFile.mkdirs() }
                .writeText("---\ntrigger: 'always'\n---\nKeep this exact guidance.")
            val result = AgentProjectInstructions.load(FileRepository(root), root)
            assertTrue(result.contains("Keep this exact guidance."))
            assertFalse(result.contains("incomplete=true"))
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationIsNotConvertedIntoSuccessfulRead() = runBlocking {
        withRule { file ->
            file.writeText("ordinary guidance")
            val job = Job().apply { cancel() }
            try { withContext(job) { AgentProjectInstructions.readWorkspaceRule(file) }; fail("cancel") }
            catch (_: CancellationException) { }
        }
    }
}
