package com.baystudio.droide.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentAvailabilityProbeTest {
    @Test fun allTerminalInstallationsAreFoundWithoutReceipts() = runBlocking {
        val commands = setOf("opencode", "codex", "codex-acp", "claude", "claude-agent-acp", "gemini", "copilot", "agy", "muse-code-acp", "muse", "kimi", "dsh")
        val calls = mutableListOf<String>()
        val actual = WorkspaceAgentAvailabilityProbe.inspect { name ->
            calls += name
            if (name in commands) "/root/.local/bin/$name" else null
        }
        assertEquals(listOf("opencode", "codex", "claude", "gemini", "copilot", "antigravity", "muse", "kimi", "deepseek"), actual.map { it.spec.id })
        assertTrue(actual.all { it.available && it.cliExecutable != null && !it.needsAdapter })
        assertEquals(commands, calls.toSet())
        assertEquals(commands.size, calls.size)
    }

    @Test fun standaloneCodexAndClaudeAreDetectedButNotLaunchedAsAcp() = runBlocking {
        val actual = WorkspaceAgentAvailabilityProbe.inspect { name ->
            if (name in setOf("codex", "claude", "muse")) "/usr/local/bin/$name" else null
        }
        for (id in listOf("codex", "claude", "muse")) {
            val entry = actual.single { it.spec.id == id }
            assertFalse(entry.available)
            assertTrue(entry.needsAdapter)
            assertEquals("/usr/local/bin/$id", entry.cliExecutable)
            assertNull(entry.executable)
        }
    }

    @Test fun repeatedProbeObservesInstallsAndRemovalsInSameHost() = runBlocking {
        val commands = mutableSetOf<String>()
        val resolve: suspend (String) -> String? = { if (it in commands) "/usr/local/bin/$it" else null }
        assertTrue(WorkspaceAgentAvailabilityProbe.inspect(resolve).none { it.available })
        commands += WorkspaceAgentPluginCatalog.entries.map { it.command }
        assertTrue(WorkspaceAgentAvailabilityProbe.inspect(resolve).all { it.available })
        commands.clear()
        assertTrue(WorkspaceAgentAvailabilityProbe.inspect(resolve).none { it.available })
    }

    @Test fun oneFailedExecutableProbeDoesNotHideOtherAgents() = runBlocking {
        val actual = WorkspaceAgentAvailabilityProbe.inspect { name ->
            if (name == "opencode") throw IOException("Guest path unavailable")
            "/usr/local/bin/$name"
        }
        assertFalse(actual.single { it.spec.id == "opencode" }.available)
        assertTrue(actual.filterNot { it.spec.id == "opencode" }.all { it.available })
    }

    @Test fun cancellationIsNotConvertedToAnUninstalledCatalog() = runBlocking {
        try {
            WorkspaceAgentAvailabilityProbe.inspect { throw CancellationException("picker closed") }
            fail("Cancellation must propagate")
        } catch (cancelled: CancellationException) { assertEquals("picker closed", cancelled.message) }
    }

    @Test fun nativeAcpFlagsAndCommunityAdaptersStayVendorSpecific() {
        val specs = WorkspaceAgentPluginCatalog.entries.associateBy { it.id }
        assertEquals(listOf("acp"), specs.getValue("opencode").args)
        assertEquals(listOf("--acp"), specs.getValue("gemini").args)
        assertEquals(listOf("--acp", "--stdio"), specs.getValue("copilot").args)
        assertEquals("codex-acp", specs.getValue("codex").command)
        assertEquals("claude-agent-acp", specs.getValue("claude").command)
    }
}
