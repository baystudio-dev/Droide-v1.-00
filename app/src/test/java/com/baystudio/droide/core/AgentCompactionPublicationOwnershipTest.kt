package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentCompactionPublicationOwnershipTest {
    @Test(timeout = 25_000)
    fun automaticCompactionCannotReplaceHistoryRevokedAfterItsLastCheck() = scenario(overflow = false, revoke = true)

    @Test(timeout = 25_000)
    fun overflowCompactionCannotReplaceHistoryRevokedAfterItsLastCheck() = scenario(overflow = true, revoke = true)

    @Test(timeout = 25_000)
    fun currentAutomaticCompactionStillReplacesActiveHistory() = scenario(overflow = false, revoke = false)

    @Test(timeout = 25_000)
    fun currentOverflowCompactionStillReplacesActiveHistory() = scenario(overflow = true, revoke = false)

    private fun scenario(overflow: Boolean, revoke: Boolean) {
        val root = Files.createTempDirectory("droide-compaction-publication-").toFile()
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""

                ModelContextRegistry.remember(provider.provider.id, provider.model, 16_384)
                provider.response = { _, _, streaming ->
                    assertFalse(streaming)
                    """{"choices":[{"message":{"role":"assistant","content":"Historical checkpoint"},"finish_reason":"stop"}]}"""
                }
                try { runBlocking { withTimeout(12_000) {
                    val sessions = SessionManager(root)
                    val history = AgentConversationHistory()
                    val original = listOf(message("user", "h".repeat(20_000)),
                        message("assistant", "old answer"), message("user", "current request"))
                    val session = sessions.create().copy(apiHistory = original, forkHistory = original,
                        forkHistoryVersion = SessionForkHistory.SCHEMA_VERSION)
                    sessions.save(session)
                    history.load(session)
                    val retainedList = history.active
                    val originalBytes = retainedList.map(JsonObject::toString)
                    val owner = history.ownershipVersion
                    val admissionLock = Any()
                    val tokens = TokenTracker()
                    var checks = 0
                    var commits = 0
                    fun requireOwner() {
                        if (history.ownershipVersion != owner) throw CancellationException("Compaction owner revoked")
                    }
                    val outcome = runCatching {
                        AgentRequestSupport.compact(sessions, tokens, TodoManager(), AgentMode.BUILD,
                            emptyList(), emptyList(), retainedList, original, provider.config, session.id, overflow,
                            ensureCurrent = {
                                synchronized(admissionLock) { requireOwner(); checks++ }
                                if (checks == 2 && revoke) synchronized(admissionLock) {
                                    // This happens after the last successful owner read, before returning to the old helper's
                                    // unguarded clear/add sequence.
                                    history.beginRestore()
                                }
                            },
                            commitAdmission = { publish -> synchronized(admissionLock) {
                                requireOwner()
                                commits++
                                publish()
                            } },
                        )
                    }
                    // Both implementations must reach a real completed compaction before assertions.
                    assertEquals(2, checks)
                    assertEquals(1, provider.requests.size)
                    assertEquals(1, tokens.history.value.size)
                    val saved = requireNotNull(sessions.get(session.id))
                    assertTrue(saved.apiHistory.first()["content"].toString().contains("Historical checkpoint"))
                    assertSame(retainedList, history.active)
                    if (revoke) {
                        assertEquals("Revoked compaction must preserve the retained active history", originalBytes,
                            history.active.map(JsonObject::toString))
                        assertTrue(outcome.exceptionOrNull() is CancellationException)
                        assertEquals(1, commits)
                    } else {
                        assertEquals(true, outcome.getOrThrow())
                        assertEquals(saved.apiHistory, history.active)
                    }
                } } } finally { ModelContextRegistry.forget(provider.provider.id) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role); put("content", content)
    }
}
