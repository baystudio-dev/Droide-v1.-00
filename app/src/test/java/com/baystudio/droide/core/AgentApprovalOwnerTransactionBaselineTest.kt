package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentApprovalOwnerTransactionBaselineTest {
    @get:Rule val applicationWork: TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000) fun revokedSameSessionHookCannotLeaveAGrantForTheNextRun() = scenario("revoke")
    @Test(timeout = 30_000) fun currentOwnerGrantRemainsReusableAfterSameSessionLoad() = scenario("current")
    @Test(timeout = 30_000) fun cancelledWaiterCannotReceiveTheHeldGrant() = scenario("cancel")

    private data class Visible(val session: String?, val stream: AgentStreamState, val busy: Boolean,
        val messages: List<AgentMessage>, val api: List<JsonObject>)
    private fun visible(agent: AgentService) = Visible(agent.currentSessionId, agent.streamState.value,
        agent.busy.value, agent.messages.value.toList(), agent.boundedApiHistory())

    private fun scenario(change: String) {
        val root = Files.createTempDirectory("droide-approval-owner-").toFile()
        val gate = CountDownLatch(1)
        val sampled = CompletableDeferred<Unit>()
        val samples = AtomicInteger()
        val gateTimedOut = AtomicBoolean(false)
        val sampledVerdict = AtomicReference<Boolean>()
        val terminal = LiteralTerminal()
        try {
            root.resolve(".droide").mkdirs()

            root.resolve(".droide/hooks.json").writeText("""{"enabled":true,"events":{"SessionStart":[{"command":"approval-owner-start"}],"UserPromptSubmit":[{"command":"approval-owner-prompt"}]}}""")
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.response = { _, _, _ -> throw AssertionError("No provider request is permitted") }
                val agent = AgentService(FileRepository(root), terminal, GitManager(root), maxSubagentDepth = 0,
                    perms = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW),
                        PermRule("hook", "workspace:SessionStart:*", PermEffect.ASK))))
                try { runBlocking { withTimeout(15_000) {
                    val work = launch(Dispatchers.Default) { agent.chat("Hook approval task", provider.config, maxTurns = 1) }
                    var resolver: Deferred<Boolean>? = null
                    var reuse: Deferred<ApprovalDecision>? = null
                    try {
                        val request = requireNotNull(agent.approvals.pending.first { it?.action == "hook" })
                        val sessionId = requireNotNull(agent.currentSessionId)
                        assertEquals(sessionId, request.approvalSessionId)
                        assertEquals(sessionId, request.provenance.sessionId)
                        assertEquals("build", request.permissionScope)
                        assertTrue(request.canAllowForSession)
                        assertEquals(0, terminal.fakeCalls.get())
                        wrapSavedPredicate(agent.approvals, request.id) { original ->
                            {
                                val current = original()
                                samples.incrementAndGet()
                                sampledVerdict.set(current)
                                sampled.complete(Unit)
                                val released = gate.await(5, TimeUnit.SECONDS)
                                if (!released) gateTimedOut.set(true)
                                check(released) { "Fixture grant gate timed out" }
                                current
                            }
                        }
                        resolver = async(Dispatchers.Default) { agent.approvals.resolve(request.id, ApprovalResolution.ALLOW_SESSION) }
                        sampled.await()
                        assertEquals(true, sampledVerdict.get())
                        var replacement: Visible? = null
                        var replacementBytes: ByteArray? = null
                        val savedFile = root.resolve(".droide/sessions/$sessionId.json")
                        when (change) {
                            "revoke" -> {
                                val saved = requireNotNull(agent.sessions.get(sessionId))
                                agent.loadSession(saved)
                                replacement = visible(agent)
                                replacementBytes = savedFile.readBytes()
                                assertEquals(sessionId, replacement.session)
                                assertFalse(replacement.busy)
                            }
                            "cancel" -> work.cancelAndJoin()
                        }
                        gate.countDown()
                        val resolved = requireNotNull(resolver).await()
                        work.join()
                        assertEquals(1, samples.get())
                        assertFalse("A fixture timeout is never ownership evidence", gateTimedOut.get())
                        assertEquals(0, provider.requests.size)
                        assertEquals(0, terminal.forbiddenCalls.get())
                        assertNull(agent.approvals.pending.value)
                        assertEquals(sessionId, agent.currentSessionId)
                        if (change == "revoke") {
                            assertEquals(replacement, visible(agent))
                            assertArrayEquals(replacementBytes, savedFile.readBytes())
                            assertEquals(0, terminal.fakeCalls.get())
                        } else if (change == "cancel") {
                            assertFalse(resolved)
                            assertEquals(0, terminal.fakeCalls.get())
                            assertFalse(agent.approvals.hasSessionApproval(sessionId, request.permissionScope,
                                request.sessionGrantAction, request.sessionGrantResource))
                        } else {
                            assertTrue(resolved)
                            assertEquals(2, terminal.fakeCalls.get())
                            // A legitimate session grant is intentionally not a per-run grant.
                            agent.loadSession(requireNotNull(agent.sessions.get(sessionId)))
                        }
                        if (change != "cancel") {
                            reuse = async(start = CoroutineStart.UNDISPATCHED) {
                                agent.approvals.requestApproval("run_command", "same-session next hook", "fixture",
                                    action = request.action, resource = request.resource,
                                    permissionScope = request.permissionScope,
                                    provenance = AgentRequestProvenance.primary(sessionId))
                            }
                            if (change == "revoke") {
                                assertFalse("A stale hook grant must not auto-approve the next same-session request", requireNotNull(reuse).isCompleted)
                                val next = requireNotNull(agent.approvals.pending.value)
                                assertTrue(next.id > request.id)
                                assertFalse(agent.approvals.hasSessionApproval(sessionId, request.permissionScope,
                                    request.sessionGrantAction, request.sessionGrantResource))
                                assertTrue(agent.approvals.resolve(next.id, ApprovalResolution.DENY))
                                assertSame(ApprovalDecision.Denied, requireNotNull(reuse).await())
                            } else {
                                assertTrue(requireNotNull(reuse).isCompleted)
                                assertSame(ApprovalDecision.Approved, requireNotNull(reuse).await())
                            }
                        }
                    } finally {
                        gate.countDown(); agent.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(5_000) {
                            reuse?.cancelAndJoin(); resolver?.cancelAndJoin(); work.cancelAndJoin()
                        } }
                    }
                } } } finally { gate.countDown(); agent.shutdown() }
                assertEquals(0, terminal.forbiddenCalls.get())
            }
        } finally { gate.countDown(); PathSecurity.deleteTreeNoFollow(root) }
    }

    private fun wrapSavedPredicate(manager: ApprovalManager, id: Long,
        wrap: (() -> Boolean) -> (() -> Boolean)) {
        val lock = ApprovalManager::class.java.getDeclaredField("stateLock").apply { isAccessible = true }.get(manager)
        @Suppress("UNCHECKED_CAST")
        val checks = ApprovalManager::class.java.getDeclaredField("validityChecks").apply { isAccessible = true }
            .get(manager) as MutableMap<Long, () -> Boolean>
        synchronized(lock) {
            checks[id] = wrap(requireNotNull(checks[id]) { "The accepted hook owner predicate must be installed" })
        }
    }

    private class LiteralTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val fakeCalls = AtomicInteger()
        val forbiddenCalls = AtomicInteger()
        private fun forbidden(): Nothing {
            forbiddenCalls.incrementAndGet(); throw AssertionError("No execution backend is permitted")
        }
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            if (cmd !in setOf("approval-owner-start", "approval-owner-prompt")) forbidden()
            fakeCalls.incrementAndGet()
            return ExecResult(0, if (cmd == "approval-owner-prompt")
                """{"decision":"block","reason":"fixture stops before provider"}""" else "{}", false)
        }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
