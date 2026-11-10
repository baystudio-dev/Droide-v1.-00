package com.baystudio.droide.core

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class OwnedProcessDiagnosticsTest {
    @get:org.junit.Rule
    val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class BlockingTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val started = CompletableDeferred<Unit>()
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult {
            started.complete(Unit)
            awaitCancellation()
        }
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = execOnce(argv.joinToString(" "), timeoutMs)
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun ownedJobDiagnosticsDoNotNeedProcfsOrExposeSecretCommandContents() = runBlocking {
        val terminal = BlockingTerminal()
        val manager = BackgroundCommandManager(terminal)
        try {
            val job = manager.start("AUTH_TOKEN=private-secret node server.js")
            withTimeout(5_000) { terminal.started.await() }
            val diagnostic = OwnedProcessDiagnostics(manager)
            val list = diagnostic.list()
            assertTrue(list.contains(job.id))
            assertTrue(list.contains("state=running"))
            assertTrue(list.contains("system_process_table=not_used"))
            assertFalse(list.contains("AUTH_TOKEN"))
            assertFalse(list.contains("private-secret"))
            assertTrue(diagnostic.status(job.id)!!.contains("state=running"))
            assertTrue(withTimeout(5_000) { diagnostic.stop(job.id) }!!.contains("state=killed"))
            assertEquals(BackgroundCommandManager.State.KILLED, manager.snapshot(job.id)!!.state)
        } finally { manager.close() }
    }

    @Test fun pidAndPortAreNeverAcceptedAsStopTargets() = runBlocking {
        val manager = BackgroundCommandManager(BlockingTerminal())
        try {
            for (target in listOf("3000", "1234", "-1", "bg-test;kill 1", "bg-")) {
                assertThrows(IllegalArgumentException::class.java) { runBlocking { OwnedProcessDiagnostics(manager).stop(target) } }
            }
            assertNull(OwnedProcessDiagnostics(manager).stop("bg-unowned-fixture-1"))
        } finally { manager.close() }
    }

    @Test fun socketBindProbeDetectsOccupiedPortWithoutReadingProcOrStoppingListener() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            val result = LoopbackPortProbe.inspect(listener.localPort)
            assertEquals(LoopbackPortState.IN_USE, result.state)
            assertFalse(listener.isClosed)
            assertTrue(result.render().contains("No process was stopped"))
            assertTrue(result.render().contains("VM and remote workstation ports are separate"))
        }
    }

    @Test fun successfulProbeReleasesItsSocketAndIsExplicitlyNotReservation() {
        val port = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }
        val result = LoopbackPortProbe.inspect(port)
        assertEquals(LoopbackPortState.BIND_SUCCEEDED, result.state)
        ServerSocket(port, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { assertFalse(it.isClosed) }
        assertTrue(result.render().contains("not a reservation"))
    }

    @Test fun invalidPortIsRejectedBeforeSocketOperation() {
        for (port in listOf(-1, 0, 65_536)) assertThrows(IllegalArgumentException::class.java) { LoopbackPortProbe.inspect(port) }
    }

    @Test fun registryReplacementSurvivesStaleWorkspaceCloseAndNeverCrossesWorkspace() {
        val root = Files.createTempDirectory("droide-owned-diagnostics").toFile()
        val firstManager = BackgroundCommandManager(BlockingTerminal())
        val secondManager = BackgroundCommandManager(BlockingTerminal())
        val id = "diagnostics-${java.util.UUID.randomUUID()}"
        val first = OwnedWorkspaceDiagnosticsRegistry.bind(id, OwnedProcessDiagnostics(firstManager), root)
        val secondProcesses = OwnedProcessDiagnostics(secondManager)
        val second = OwnedWorkspaceDiagnosticsRegistry.bind(id, secondProcesses, root)
        try {
            OwnedWorkspaceDiagnosticsRegistry.unbind(first)
            assertSame(secondProcesses, OwnedWorkspaceDiagnosticsRegistry.provider(id)!!.processes)
            assertNull(OwnedWorkspaceDiagnosticsRegistry.provider("$id-other"))
            assertNull(OwnedWorkspaceDiagnosticsRegistry.provider(null))
            OwnedWorkspaceDiagnosticsRegistry.unbind(second)
            assertNull(OwnedWorkspaceDiagnosticsRegistry.provider(id))
        } finally {
            OwnedWorkspaceDiagnosticsRegistry.unbind(first)
            OwnedWorkspaceDiagnosticsRegistry.unbind(second)
            firstManager.close(); secondManager.close(); root.deleteRecursively()
        }
    }

    @Test fun registryRejectsInvalidIdentityAndUnavailableRoot() {
        val manager = BackgroundCommandManager(BlockingTerminal())
        val root = Files.createTempDirectory("droide-owned-diagnostics-invalid").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                OwnedWorkspaceDiagnosticsRegistry.bind("../other", OwnedProcessDiagnostics(manager), root)
            }
            assertThrows(IllegalArgumentException::class.java) {
                OwnedWorkspaceDiagnosticsRegistry.bind("valid", OwnedProcessDiagnostics(manager), File(root, "absent"))
            }
        } finally { manager.close(); root.deleteRecursively() }
    }
}
