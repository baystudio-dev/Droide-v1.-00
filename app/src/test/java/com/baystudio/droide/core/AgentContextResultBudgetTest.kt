package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentContextResultBudgetTest {
    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-context-result-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun skill(root: File, name: String = "alpha", body: String = "Body", description: String = "Description", folder: String = "one") =
        root.resolve(".droide/skills/$folder/SKILL.md").apply {
            parentFile.mkdirs(); writeText("---\nname: $name\ndescription: $description\n---\n$body")
        }

    private fun plugin(root: File, id: String = "fixture", body: String = "Plugin body"): AgentPluginSource {
        val dir = root.resolve("plugin").apply { mkdir(); resolve("rules").mkdir() }
        val text = "---\nname: alpha\ndescription: Description\n---\n$body"
        dir.resolve("SKILL.md").writeText(text); dir.resolve("rules/alpha.md").writeText(text)
        return AgentPluginSource { listOf(AgentPluginContributionRoot(id, "Fixture", "1", "", AgentPluginFormat.DROIDE, dir, "a".repeat(64))) }
    }

    private fun context(root: File, source: AgentPluginSource = AgentPluginSource.EMPTY,
                        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW)))): AgentTools.Ctx {
        val c = agentWorkflowContext(root, permissions = permissions)
        return AgentTools.Ctx(c.files, c.terminal, c.git, c.approvals, c.questions, c.history, c.perms, c.mode,
            c.llm, c.llmConfig, root, c.todos, c.sessionId, c.lsp, pluginSource = source)
    }

    private suspend fun call(ctx: AgentTools.Ctx, name: String, tool: String = "skill") =
        AgentTools.execute(tool, buildJsonObject { put("name", name) }, ctx)

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun assertUnicode(text: String) {
        var at = 0
        while (at < text.length) {
            val c = text[at++]
            if (c.isHighSurrogate()) assertTrue(at < text.length && text[at++].isLowSurrogate())
            else assertFalse(c.isLowSurrogate())
        }
    }

    @Test fun ordinarySkillReturnsExactBodyAndVersionedContinuationReceipt() = fixture { root ->
        val body = "# Code\nprint('hello')\nTail 😀"
        val file = skill(root, body = body)
        val output = call(context(root), "alpha")
        assertTrue(output.startsWith("# alpha: Description\nSource: workspace\n\n$body"))
        assertTrue(output.contains("[skill_sha256=${digest(file.readText())}"))
        assertFalse(output.contains("next_offset="))
    }

    @Test fun oversizedNamesAreRejectedBeforePluginDiscoveryAndNeverEchoed() = fixture { root ->
        var discoveries = 0
        val ctx = context(root, AgentPluginSource { discoveries++; emptyList() })
        val name = "a".repeat(180_000)
        val result = call(ctx, name)
        assertTrue(result.startsWith("ERROR:")); assertTrue(result.length < 1000)
        assertFalse(result.contains(name)); assertEquals(0, discoveries)
    }

    @Test fun validLongNamesRemainDistinctWithoutTruncatingLoadIdentity() = fixture { root ->
        val a = "x".repeat(250) + "first"; val b = "x".repeat(250) + "second"
        skill(root, a, "First", folder = "a"); skill(root, b, "Second", folder = "b")
        val first = call(context(root), a); val second = call(context(root), b)
        assertTrue(first.startsWith("# $a:")); assertTrue(second.startsWith("# $b:"))
        assertTrue(first.contains("\n\nFirst\n[skill_sha256=")); assertTrue(second.contains("\n\nSecond\n[skill_sha256="))
    }

    @Test fun skillPagesReconstructLongUnicodeBodyWithExactOffsetsAndHashes() = fixture { root ->
        val body = "a".repeat(7999) + "😀" + "漢字".repeat(9000) + " END"
        val file = skill(root, body = body); val ctx = context(root)
        val hash = digest(file.readText()); val restored = StringBuilder(); var offset = 0
        do {
            val output = AgentTools.execute("skill", buildJsonObject {
                put("name", "alpha"); put("offset", offset)
                if (offset > 0) put("expected_sha256", hash)
            }, ctx)
            assertTrue(output.length < 10_000); assertUnicode(output)
            assertTrue(output.contains("[skill_sha256=$hash"))
            restored.append(output.substringAfter("\n\n").substringBefore("\n[next_offset=").substringBefore("\n[skill_sha256="))
            val next = Regex("next_offset=([0-9]+)").find(output)?.groupValues?.get(1)?.toInt()
            if (next != null) assertTrue(next > offset)
            offset = next ?: -1
        } while (offset >= 0)
        assertEquals(body, restored.toString())
    }

    @Test fun metadataCannotConsumeTheBoundedInstructionPage() = fixture { root ->
        val name = "a".repeat(256); val body = "INSTRUCTIONS " + "b".repeat(7000)
        skill(root, name, body, description = "d".repeat(4000))
        val result = call(context(root), name)
        assertTrue(result.length < 10_000); assertTrue(result.contains(body))
        assertFalse(result.contains("d".repeat(1025))); assertUnicode(result)
    }

    @Test fun metadataBoundaryDoesNotSplitASurrogatePair() = fixture { root ->
        skill(root, description = "a".repeat(1023) + "😀end")
        val result = call(context(root), "alpha")
        assertUnicode(result); assertTrue(result.contains("\n\nBody\n[skill_sha256="))
        assertEquals("a".repeat(1023), SkillManager(root).catalog().single().description)
    }

    @Test fun permissionDenialIsBoundedAndDoesNotDiscoverOrExposeSkillContents() = fixture { root ->
        var discoveries = 0
        val source = AgentPluginSource { discoveries++; emptyList() }
        val deny = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.DENY)))
        val result = call(context(root, source, deny), "alpha")
        assertTrue(result.startsWith("DENIED")); assertTrue(result.length < 2300)
        assertEquals(0, discoveries)
        assertEquals(AgentToolEvidenceState.DENIED, AgentToolResultSemantics.classify("skill", result, null).state)
    }

    @Test fun fullValidNameRemainsThePermissionIdentity() = fixture { root ->
        val allowed = "a".repeat(240) + "allowed"; val denied = "a".repeat(240) + "denied"
        skill(root, allowed, "Allowed body", folder = "allowed"); skill(root, denied, "Denied body", folder = "denied")
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", denied, PermEffect.DENY)))
        val ctx = context(root, permissions = permissions)
        assertTrue(call(ctx, allowed).contains("Allowed body"))
        val result = call(ctx, denied)
        assertTrue(result.startsWith("DENIED")); assertFalse(result.contains("Denied body"))
    }

    @Test fun userDenialKeepsTheExactValidApprovalIdentity() = fixture { root -> coroutineScope {
        val name = "a".repeat(256)
        val permissions = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW), PermRule("skill", "*", PermEffect.ASK)))
        val ctx = context(root, permissions = permissions)
        val running = async(start = CoroutineStart.UNDISPATCHED) { call(ctx, name) }
        try {
            val pending = withTimeout(3000) { ctx.approvals.pending.filterNotNull().first() }
            assertEquals(name, pending.resource)
            assertTrue(ctx.approvals.resolve(pending.id, ApprovalResolution.DENY))
            val result = withTimeout(3000) { running.await() }
            assertTrue(result.startsWith("DENIED")); assertTrue(result.length < 2300)
        } finally { running.cancel() }
    } }

    @Test fun missingSkillUsesABoundedErrorInsteadOfEchoingItsName() = fixture { root ->
        val result = call(context(root), "missing")
        assertTrue(result.startsWith("ERROR:")); assertTrue(result.length < 1000)
        assertTrue(AgentToolResultSemantics.classify("skill", result, null).isFailure)
    }

    @Test fun ordinaryRuleRetainsItsIdentityProvenanceAndActivation() = fixture { root ->
        assertEquals("# Rule fixture:alpha: Description\nSource: plugin:fixture@aaaaaaaaaaaa\nActivation: model\n\nPlugin body",
            call(context(root, plugin(root)), "fixture:alpha", "rule"))
    }

    @Test fun longRuleStillDisclosesClippingAndNeverSplitsUnicode() = fixture { root ->
        val source = plugin(root, body = "a".repeat(9999) + "😀tail")
        val output = call(context(root, source), "fixture:alpha", "rule")
        assertTrue(output.contains("Instructions truncated")); assertUnicode(output)
        assertTrue(output.length < 12_000)
    }

    @Test fun escapedSkillOutputFitsTransportWithoutDroppingSourceText() = fixture { root ->
        val body = "\u0001".repeat(7900) + "Z"
        skill(root, body = body)
        val result = call(context(root), "alpha")
        assertTrue(result.contains(body)); assertTrue(JsonPrimitive(result).toString().toByteArray().size < 64_000)
    }
}
