package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Blocks the actual session transaction while clear/restore changes inbox ownership. */
class AgentPromptInboxOwnershipTest {
    @Test(timeout = 30_000) fun clearedOrRestoredStartLeaseCannotInstallAnOldRun() = workspace { root -> runBlocking {
        val inbox = AgentPromptInbox(SessionManager(root))
        val old = inbox.reserveDirectRun()
        inbox.clear()
        assertTrue(runCatching { inbox.requireStartRun(old) }.exceptionOrNull() is CancellationException)
        val next = inbox.reserveDirectRun()
        assertNotEquals(old, next)
        inbox.requireStartRun(next)
        inbox.load(listOf(pending("in-new")), "s-replacement")
        assertTrue(runCatching { inbox.requireStartRun(next) }.exceptionOrNull() is CancellationException)
        assertEquals("in-new", inbox.inputs.value.single().id)
    } }
    @Test(timeout = 30_000) fun clearDuringAdmissionPreservesTheDraftAndDoesNotReviveTheOldInbox() = admissionRace(restore = false)
    @Test(timeout = 30_000) fun restoreDuringAdmissionCannotReplaceTheNewSessionsInputsOrOldDiskMetadata() = admissionRace(restore = true)
    @Test(timeout = 30_000) fun clearDuringRemovalCannotPublishOldInputsOrDeleteTheOldSavedPrompt() = removalRace(restore = false)
    @Test(timeout = 30_000) fun restoreDuringRemovalCannotReplaceTheNewSessionsInputs() = removalRace(restore = true)

    private fun admissionRace(restore: Boolean) = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val old = manager.create()
            val replacement = manager.create()
            val current = AtomicReference<String?>(old.id)
            val inbox = AgentPromptInbox(manager)
            inbox.load(emptyList(), old.id)
            val lease = inbox.reserveDirectRun(); assertTrue(inbox.markReady(lease))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var accepted = false
            val newInput = pending("in-restored")
            withContext(Dispatchers.IO) {
                val holding = async(start = CoroutineStart.UNDISPATCHED) {
                    manager.update(old.id) { before -> entered.complete(Unit); release.await(); before!! }
                }
                entered.await()
                val admitting = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        inbox.routeSubmission(old.id, "old draft", AgentPromptDelivery.STEER, http.config,
                            sessionIsCurrent = { current.get() == old.id })
                        accepted = true
                    }
                }
                assertFalse(admitting.isCompleted)
                current.set(if (restore) replacement.id else null)
                if (restore) inbox.load(listOf(newInput), replacement.id) else inbox.clear()
                release.complete(Unit); holding.await()
                assertTrue(admitting.await().exceptionOrNull() is CancellationException)
            }
            assertFalse(accepted)
            assertEquals(if (restore) listOf(newInput) else emptyList<AgentPendingInput>(), inbox.inputs.value)
            assertTrue(manager.getForUpdate(old.id)!!.pendingInputs.isEmpty())
        } }
    }

    private fun removalRace(restore: Boolean) = workspace { root -> runBlocking {
        val manager = SessionManager(root)
        val oldInput = pending("in-old")
        val old = manager.create()
        manager.savePendingInputs(old.id, listOf(oldInput))
        val replacement = manager.create()
        val current = AtomicReference<String?>(old.id)
        val inbox = AgentPromptInbox(manager)
        inbox.load(listOf(oldInput), old.id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val newInput = pending("in-restored")
        withContext(Dispatchers.IO) {
            val holding = async(start = CoroutineStart.UNDISPATCHED) {
                manager.update(old.id) { before -> entered.complete(Unit); release.await(); before!! }
            }
            entered.await()
            val removing = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { inbox.remove(old.id, oldInput.id, sessionIsCurrent = { current.get() == old.id }) }
            }
            assertFalse(removing.isCompleted)
            current.set(if (restore) replacement.id else null)
            if (restore) inbox.load(listOf(newInput), replacement.id) else inbox.clear()
            release.complete(Unit); holding.await()
            assertTrue(removing.await().exceptionOrNull() is CancellationException)
        }
        assertEquals(if (restore) listOf(newInput) else emptyList<AgentPendingInput>(), inbox.inputs.value)
        assertEquals(listOf(oldInput), manager.getForUpdate(old.id)!!.pendingInputs)
    } }

    private fun pending(id: String) = AgentPendingInput(id, "saved prompt", AgentPromptDelivery.QUEUE, "fixture", "model")
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-inbox-owner-race-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
