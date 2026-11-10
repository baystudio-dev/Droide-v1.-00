package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionSidecarFailureTest {
    @Test(timeout = 30_000) fun blockedSummaryCacheCannotRejectACommittedPromptOrEraseItAtTheNextCheckpoint() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val session = manager.create("Primary conversation")
            val sidecar = File(root, ".droide/sessions/${session.id}.meta")
            check(sidecar.delete()); check(sidecar.mkdir())
            val obstruction = File(sidecar, "user-obstruction").apply { writeText("preserve") }
            val inbox = AgentPromptInbox(manager)
            inbox.load(emptyList(), session.id)
            val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
            val admitted = inbox.routeSubmission(session.id, "accepted queued work", AgentPromptDelivery.QUEUE, http.config)
                as AgentPromptInbox.SubmissionDecision.Admitted
            assertEquals(listOf(admitted.input), inbox.inputs.value)
            assertEquals(inbox.inputs.value, manager.getForUpdate(session.id)!!.pendingInputs)
            inbox.withPendingSnapshot { pending ->
                manager.update(session.id) { current ->
                    current!!.copy(messages = listOf(AgentMessage("assistant", "latest durable response")), pendingInputs = pending)
                }
            }
            val after = SessionManager(root).getForUpdate(session.id)!!
            assertEquals(listOf(admitted.input), after.pendingInputs)
            assertEquals("latest durable response", after.messages.single().content)
            // The saved conversation remains discoverable while its cache cannot be repaired.
            assertEquals(session.id, manager.listSummaries().single().id)
            assertEquals(1, manager.getSummary(session.id)!!.messageCount)
            assertTrue(sidecar.isDirectory)
            assertEquals("preserve", obstruction.readText())
            check(obstruction.delete())
            check(sidecar.delete())
            assertEquals(session.id, manager.getSummary(session.id)!!.id)
            assertTrue(sidecar.isFile)
            assertEquals(listOf(admitted.input), manager.getForUpdate(session.id)!!.pendingInputs)
            assertTrue(http.requests.isEmpty())
        } }
    }

    @Test(timeout = 30_000) fun primarySessionFailureStillRejectsAdmissionAndPreservesTheDraft() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val session = manager.create()
            val primary = File(root, ".droide/sessions/${session.id}.json")
            check(primary.delete()); check(primary.mkdir())
            val sentinel = File(primary, "user-data").apply { writeText("preserve") }
            val inbox = AgentPromptInbox(manager)
            inbox.load(emptyList(), session.id)
            val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
            var accepted = false
            val failure = runCatching {
                inbox.routeSubmission(session.id, "draft kept", AgentPromptDelivery.QUEUE, http.config)
                accepted = true
            }.exceptionOrNull()
            assertTrue(failure is SessionHistoryCorruptionException)
            assertFalse(accepted)
            assertTrue(inbox.inputs.value.isEmpty())
            assertTrue(primary.isDirectory)
            assertEquals("preserve", sentinel.readText())
            assertTrue(http.requests.isEmpty())
        } }
    }

    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-sidecar-failure-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
