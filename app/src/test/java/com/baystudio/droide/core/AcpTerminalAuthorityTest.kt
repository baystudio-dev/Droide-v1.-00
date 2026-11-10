package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AcpTerminalAuthorityTest {
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val f = Fixture()
        try { withTimeout(5_000) { f.block() } }
        finally {
            f.finishCleanup?.complete(Unit)
            f.controller.close(); f.owner.stopAndAwait(); f.scope.cancel(); f.root.deleteRecursively()
        }
    }
    private class Fixture {
        val root = Files.createTempDirectory("acp-r118-authority").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val approvals = ApprovalManager()
        val access = WorkspaceAgentAccessPolicy().apply { setMode(WorkspaceAgentMode.EDIT) }
        var starts = 0
        var closes = 0
        var starting: CompletableDeferred<Unit>? = null
        var finishStart: CompletableDeferred<Unit>? = null
        var finishCleanup: CompletableDeferred<Unit>? = null
        val owner = SessionHostedProcessOwner()
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.DEVICE_ADB
            override suspend fun resolveExecutable(command: String) = "/fixture/$command"
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.canonicalPath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                starts++
                starting?.complete(Unit)
                finishStart?.await()
                return object : HostedStdioProcess {
                    override val stdin = ByteArrayOutputStream()
                    override val stdout = ByteArrayInputStream(byteArrayOf())
                    override val stderr = ByteArrayInputStream(byteArrayOf())
                    override val isAlive = false
                    override val exitCode = 0
                    override suspend fun awaitExit(): Int { finishCleanup?.await(); return 0 }
                    override fun close() { closes++ }
                }
            }
        }
        val controller = AcpTerminalController(owner.bind(host), WorkspacePathMapper(root, root.canonicalPath), approvals,
            PermissionEngine(), AcpPermissionLedger(), access, scope).apply { bindApprovalSession("owner") }
        suspend fun create() = controller.create(buildJsonObject { put("command", "fixture"); put("sessionId", "session") })
        suspend fun pending(): ApprovalRequest {
            while (approvals.pending.value == null) delay(5)
            return approvals.pending.value!!
        }
    }
    @Test fun releasedTerminalRemainsOwnedUntilItsCleanupReceipt() = fixture {
        finishCleanup = CompletableDeferred()
        coroutineScope {
            val creation = async { create() }
            approvals.resolve(pending().id, ApprovalResolution.ALLOW_ONCE)
            val terminal = creation.await()
            controller.release(terminal)
            val cleanup = async { owner.stopAndAwait() }
            yield()
            assertFalse(cleanup.isCompleted)
            finishCleanup!!.complete(Unit)
            cleanup.await()
        }
    }
    @Test fun approvalAfterEndTurnCannotLaunchProcess() = fixture {
        coroutineScope {
            val result = async { runCatching { create() } }
            val pending = pending()
            controller.cancelActive(); access.setMode(WorkspaceAgentMode.PLAN)
            approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE)
            assertTrue(result.await().isFailure)
            assertEquals(0, starts)
        }
    }
    @Test fun approvalFromPreviousEditTurnCannotLaunchInNewEditTurn() = fixture {
        coroutineScope {
            val result = async { runCatching { create() } }
            val pending = pending()
            controller.cancelActive()
            approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE)
            assertTrue(result.await().isFailure)
            assertEquals(0, starts)
        }
    }
    @Test fun approvalFromPreviousSessionCannotLaunchAfterRebind() = fixture {
        coroutineScope {
            val result = async { runCatching { create() } }
            val pending = pending()
            controller.bindApprovalSession("new-owner")
            approvals.resolve(pending.id, ApprovalResolution.ALLOW_SESSION)
            assertTrue(result.await().isFailure)
            assertFalse(approvals.hasSessionApproval("owner", EXTERNAL_ACP_PERMISSION_SCOPE,
                pending.sessionGrantAction, pending.sessionGrantResource))
            assertEquals(0, starts)
        }
    }
    @Test fun processReturnedAfterCancellationIsReapedRatherThanRegistered() = fixture {
        starting = CompletableDeferred(); finishStart = CompletableDeferred()
        coroutineScope {
            val result = async { runCatching { create() } }
            approvals.resolve(pending().id, ApprovalResolution.ALLOW_ONCE)
            starting!!.await()
            controller.cancelActive()
            finishStart!!.complete(Unit)
            assertTrue(result.await().isFailure)
            assertEquals(1, starts)
            assertEquals(1, closes)
        }
    }
    @Test fun queuedCreateCannotAdoptNextTurnAfterWaitingForAnotherApproval() = fixture {
        coroutineScope {
            val first = async { runCatching { create() } }
            val pending = pending()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { create() } }
            controller.cancelActive()
            approvals.resolve(pending.id, ApprovalResolution.ALLOW_ONCE)
            assertTrue(first.await().isFailure)
            assertTrue(second.await().isFailure)
            assertNull(approvals.pending.value)
            assertEquals(0, starts)
        }
    }

    @Test fun matchingLiveApprovalStillLaunches() = fixture {
        coroutineScope {
            val result = async { create() }
            approvals.resolve(pending().id, ApprovalResolution.ALLOW_ONCE)
            assertNotNull(result.await()["terminalId"])
            assertEquals(1, starts)
        }
    }
}
