package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportAgentWriterAdmissionTest {
    @Test fun captureRejectsPromptBeforeAcceptanceAndDoesNotStrandStartup() {
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { provider ->
            runBlocking {
                val admissionField = ApplicationWorkAdmission::class.java.getDeclaredField("state").apply { isAccessible = true }
                val previous = admissionField.get(null)
                val admission = ApplicationWorkAdmissionState(
                    read = { ApplicationWorkAdmissionState.Snapshot("a".repeat(32), true) }, write = {})
                admission.initialize()
                admissionField.set(null, admission)
                val root = Files.createTempDirectory("export-agent-writer").toFile()
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
                val owner = SupervisorJob(coroutineContext[Job])
                val scope = CoroutineScope(coroutineContext + owner)
                try {
                    var accepted = false
                    ExportWriterAdmission.beginCapture(root).use {
                        checkNotNull(agent.submitAsync(scope, "keep my draft", provider.config,
                            onAccepted = { accepted = true })).join()
                        assertFalse(accepted)
                        assertTrue(agent.compactCurrent(provider.config).startsWith("Compaction failed: Export is capturing"))
                        assertNull(agent.currentSessionId)
                        assertTrue(provider.requests.isEmpty())
                        assertFalse(root.resolve(".droide/sessions").exists())
                    }
                    // A later submission can reserve STARTING again. Cancel before model dispatch.
                    withTimeout(5_000) {
                        checkNotNull(agent.submitAsync(scope, "later draft", provider.config,
                            onAccepted = { accepted = true; agent.cancel() })).join()
                        agent.stopAndCheckpointForExit()
                    }
                    assertTrue(accepted)
                    ExportWriterAdmission.beginCapture(root).close()
                } finally {
                    agent.shutdown()
                    owner.cancelAndJoin()
                    admissionField.set(null, previous)
                    PathSecurity.deleteTreeNoFollow(root)
                }
            }
        }
    }
}
