package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SessionWriteConcurrencyTest {
    @Test(timeout = 30_000) fun pendingAdmissionWaitsForTheLatestConversationTransactionAcrossManagers() = workspace { root -> runBlocking {
        val first = SessionManager(root)
        val second = SessionManager(root)
        val session = first.create()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val input = pending("in-new")
        withContext(Dispatchers.IO) {
            val checkpoint = async(start = CoroutineStart.UNDISPATCHED) {
                first.update(session.id) { current ->
                    entered.complete(Unit); release.await()
                    current!!.copy(messages = listOf(AgentMessage("assistant", "newest answer")),
                        apiHistory = listOf(message("assistant", "newest answer")))
                }
            }
            entered.await()
            val admission = async(start = CoroutineStart.UNDISPATCHED) { second.savePendingInputs(session.id, listOf(input)) }
            assertFalse(admission.isCompleted)
            release.complete(Unit)
            checkpoint.await(); admission.await()
        }
        val saved = first.getForUpdate(session.id)!!
        assertEquals("newest answer", saved.messages.single().content)
        assertEquals(listOf(message("assistant", "newest answer")), saved.apiHistory)
        assertEquals(listOf(input), saved.pendingInputs)
    } }

    @Test(timeout = 30_000) fun inboxSnapshotCannotClobberAnAdmissionThatWaitsDuringCheckpoint() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val session = manager.create()
            val inbox = AgentPromptInbox(manager)
            val lease = inbox.reserveDirectRun()
            assertTrue(inbox.markReady(lease))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.IO) {
                val checkpoint = async(start = CoroutineStart.UNDISPATCHED) {
                    inbox.withPendingSnapshot { inputs ->
                        entered.complete(Unit); release.await()
                        manager.update(session.id) { it!!.copy(pendingInputs = inputs,
                            messages = listOf(AgentMessage("assistant", "checkpoint"))) }
                    }
                }
                entered.await()
                val admission = async(start = CoroutineStart.UNDISPATCHED) {
                    inbox.routeSubmission(session.id, "next task", AgentPromptDelivery.QUEUE, http.config)
                }
                assertFalse(admission.isCompleted)
                release.complete(Unit)
                checkpoint.await(); admission.await()
            }
            val stored = manager.getForUpdate(session.id)!!
            assertEquals(inbox.inputs.value, stored.pendingInputs)
            assertEquals("next task", stored.pendingInputs.single().text)
            assertEquals("checkpoint", stored.messages.single().content)
        } }
    }

    @Test(timeout = 30_000) fun staleConversationResultCannotReplaceANewerCheckpoint() = workspace { root -> runBlocking {
        val first = SessionManager(root)
        val second = SessionManager(root)
        val session = first.create()
        val captured = first.snapshotForUpdate(session.id)!!
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async(Dispatchers.IO) {
            preparing.complete(Unit); release.await()
            runCatching { first.update(session.id, expectedRevision = captured.revision) {
                captured.session.copy(messages = listOf(AgentMessage("assistant", "obsolete")))
            } }
        }
        preparing.await()
        second.update(session.id) { it!!.copy(messages = listOf(AgentMessage("assistant", "latest"))) }
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is StaleSessionWriteException)
        assertEquals("latest", first.getForUpdate(session.id)!!.messages.single().content)
    } }

    @Test(timeout = 30_000) fun finalGenerationAdmissionRunsAfterSerializationWithoutReplacingDisk() = workspace { root -> runBlocking {
        val manager = SessionManager(root)
        val session = manager.create()
        val before = File(root, ".droide/sessions/${session.id}.json").readBytes()
        var admitted = false
        val failure = runCatching {
            manager.update(session.id, commitAdmission = {
                admitted = true
                assertTrue(File(root, ".droide/sessions").listFiles()!!.any { it.name.contains(".tmp-") && it.length() > 0 })
                throw CancellationException("a new run owns this conversation")
            }) { it!!.copy(messages = listOf(AgentMessage("assistant", "stale"))) }
        }.exceptionOrNull()
        assertTrue(admitted)
        assertTrue(failure is CancellationException)
        assertArrayEquals(before, File(root, ".droide/sessions/${session.id}.json").readBytes())
        assertFalse(File(root, ".droide/sessions").listFiles()!!.any { it.name.contains(".tmp-") })
    } }

    @Test(timeout = 30_000) fun deletionWinsAgainstAnAlreadyQueuedOldSaveAndFreshUuidCreationStillWorks() = workspace { root -> runBlocking {
        val first = SessionManager(root)
        val second = SessionManager(root)
        val original = first.create()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withContext(Dispatchers.IO) {
            val holding = async(start = CoroutineStart.UNDISPATCHED) {
                first.update(original.id) { current -> entered.complete(Unit); release.await(); current!! }
            }
            entered.await()
            val deletion = async(start = CoroutineStart.UNDISPATCHED) { second.delete(original.id) }
            val staleSave = async(start = CoroutineStart.UNDISPATCHED) { runCatching { first.save(original) } }
            assertFalse(deletion.isCompleted); assertFalse(staleSave.isCompleted)
            release.complete(Unit)
            holding.await(); deletion.await()
            assertTrue(staleSave.await().isFailure)
        }
        assertNull(first.getForUpdate(original.id))
        assertFalse(File(root, ".droide/sessions/${original.id}.json").exists())
        assertNotEquals(original.id, second.create().id)
    } }

    @Test(timeout = 30_000) fun providerCompactionHoldsNoWriteLockAndCannotCommitAfterANewerTurn() = workspace { root ->
        withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { http -> runBlocking {
            val manager = SessionManager(root)
            val history = listOf(message("user", "old constraints ".repeat(1_000)), message("assistant", "old evidence"), message("user", "continue"))
            val session = manager.create().copy(apiHistory = history)
            manager.save(session)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            http.response = { _, _, _ ->
                entered.complete(Unit)
                check(release.await(10, TimeUnit.SECONDS)) { "Compaction response was never released" }
                buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("message", message("assistant", "Historical summary")); put("finish_reason", "stop")
                }) }) }.toString()
            }
            val compact = async(Dispatchers.IO) { runCatching { manager.compact(session, http.config, keepRecentTokens = 1000) } }
            try {
                withTimeout(5_000) { entered.await() }
                val input = pending("in-during-compaction")
                withTimeout(5_000) {
                    manager.update(session.id) { it!!.copy(apiHistory = history + message("assistant", "new turn finished")) }
                    manager.savePendingInputs(session.id, listOf(input))
                }
                release.countDown()
                assertTrue(compact.await().exceptionOrNull() is StaleSessionWriteException)
                val saved = manager.getForUpdate(session.id)!!
                assertEquals(history + message("assistant", "new turn finished"), saved.apiHistory)
                assertEquals(listOf(input), saved.pendingInputs)
                assertEquals(1, http.requests.size)
            } finally { release.countDown(); compact.cancelAndJoin() }
        } }
    }

    @Test(timeout = 30_000) fun pendingFieldUpdateDoesNotInvalidateCompactionRevisionOrReloadTheColdLedger() = workspace { root -> runBlocking {
        val manager = SessionManager(root)
        val exact = listOf(message("user", "source ".repeat(80_000)))
        val session = manager.create().copy(apiHistory = listOf(message("user", "active checkpoint")),
            forkHistory = exact, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
        manager.save(session)
        val captured = manager.snapshotForUpdate(session.id)!!
        assertTrue(captured.session.forkHistoryPages.isNotEmpty())
        val input = pending("in-survives")
        manager.savePendingInputs(session.id, listOf(input))
        val committed = manager.update(session.id, expectedRevision = captured.revision) {
            captured.session.copy(apiHistory = listOf(message("assistant", "compacted")), pendingInputs = it!!.pendingInputs)
        }
        assertEquals(listOf(input), committed.pendingInputs)
        assertEquals(captured.session.forkHistoryPages, manager.getForUpdate(session.id)!!.forkHistoryPages)
        assertEquals(exact, manager.get(session.id)!!.forkHistory)
    } }

    private fun pending(id: String) = AgentPendingInput(id, "next task", AgentPromptDelivery.QUEUE, "fixture", "fixture-model")
    private fun message(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }
    private fun workspace(block: (File) -> Unit) {
        val root = Files.createTempDirectory("droide-session-write-race-").toFile()
        try { block(root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
