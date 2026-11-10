package com.baystudio.droide.core

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentRestoreOwnershipTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    private val original = buildJsonObject { put("role", "user"); put("content", "Original context") }
    private val replacement = buildJsonObject { put("role", "user"); put("content", "Replacement context") }
    private val pending = AgentPendingInput("pending-original", "Keep this queued request", AgentPromptDelivery.QUEUE, "pollinations", "test-model")

    private suspend fun seeded(root: java.io.File): AgentService {
        val files = FileRepository(root)
        val agent = AgentService(files, AgentWorkflowTerminal(), GitManager(root), maxSubagentDepth = 0)
        val originalSession = agent.sessions.create("Original").copy(apiHistory = listOf(original), forkHistory = listOf(original),
            forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION, messages = listOf(AgentMessage("user", "Original context")), pendingInputs = listOf(pending))
        agent.sessions.save(originalSession)
        agent.loadSession(originalSession)
        return agent
    }

    @Test(timeout = 30_000) fun clearWhileActualRestoreIoIsBlockedCannotPublishOldContextOrStayBusy() = runBlocking {
        val root = Files.createTempDirectory("droide-restore-clear-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val agent = seeded(root)
        try {
            val blocked = object : AbstractList<JsonObject>() {
                override val size = 1
                private val first = AtomicBoolean(true)
                override fun get(index: Int): JsonObject {
                    if (first.getAndSet(false)) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    return replacement
                }
            }
            val restoring = async(Dispatchers.Default) {
                runCatching { agent.loadSession(DroideSession("s-replacement", "Replacement", apiHistory = listOf(replacement),
                    forkHistory = blocked, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)) }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            agent.clear()
            release.countDown()
            assertTrue(restoring.await().exceptionOrNull() is CancellationException)
            assertNull(agent.currentSessionId)
            assertTrue(agent.messages.value.isEmpty())
            assertTrue(agent.pendingInputs.value.isEmpty())
            assertFalse(agent.busy.value)
        } finally { release.countDown(); agent.shutdown(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout = 30_000) fun actualRestoreBlocksDirectAndAsyncPromptAdmissionBeforeAnyProviderSetup() = runBlocking {
        val root = Files.createTempDirectory("droide-restore-admission-").toFile()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val agent = seeded(root)
        try {
            val blocked = object : AbstractList<JsonObject>() {
                override val size = 1
                private val first = AtomicBoolean(true)
                override fun get(index: Int): JsonObject {
                    if (first.getAndSet(false)) { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    return replacement
                }
            }
            val restoring = async(Dispatchers.Default) { agent.loadSession(DroideSession("s-replacement", "Replacement",
                apiHistory = listOf(replacement), forkHistory = blocked, forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val config = AgentConfig() // Deliberately uncertified: restore guard must run before provider validation.
            assertNull(agent.submitAsync(this, "Do not cross sessions", config))
            assertTrue(runCatching { agent.chat("No request during restore", config) }.exceptionOrNull() is CancellationException)
            assertTrue(agent.persistenceError.value!!.contains("still loading"))
            release.countDown(); restoring.await()
            assertEquals("s-replacement", agent.currentSessionId)
            assertFalse(agent.busy.value)
        } finally { release.countDown(); agent.shutdown(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun failedActualRestorePreservesPreviousSessionAndItsDurablePendingQueue() = runBlocking {
        val root = Files.createTempDirectory("droide-restore-failure-").toFile()
        val agent = seeded(root)
        try {
            val previous = agent.currentSessionId!!
            val failing = object : AbstractList<JsonObject>() {
                override val size = 1
                override fun get(index: Int): JsonObject = throw IOException("Synthetic unreadable page")
            }
            assertTrue(runCatching { agent.loadSession(DroideSession("s-failing", "Unavailable", forkHistory = failing,
                forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)) }.exceptionOrNull() is IOException)
            assertEquals(previous, agent.currentSessionId)
            assertEquals(listOf(pending), agent.pendingInputs.value)
            assertEquals(listOf(AgentMessage("user", "Original context", forkHistoryEnd = 1)), agent.messages.value)
            agent.sessions.savePendingInputs(previous, agent.pendingInputs.value)
            assertEquals(listOf(pending), agent.sessions.get(previous)!!.pendingInputs)
            assertFalse(agent.busy.value)
        } finally { agent.shutdown(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
