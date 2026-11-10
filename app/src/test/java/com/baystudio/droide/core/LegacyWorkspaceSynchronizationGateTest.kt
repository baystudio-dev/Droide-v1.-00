package com.baystudio.droide.core

import android.app.Application
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LegacyWorkspaceSynchronizationGateTest {
    private fun fixture(connected: Boolean, block: suspend (AndroidDevelopmentManager, DeviceBridgeManager, () -> Int) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("legacy-project-sync-gate-").toFile()
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val previousPid = android.os.Process.myPid()
        var probes = 0
        val transport = object : DeviceBridgeTransport {
            override val kadb: Kadb? = null
            private var live = false
            override val open get() = live
            override val encrypted = true
            override fun probe(command: String, outputLimit: Int): BridgeShellResult {
                probes++; live = true
                return BridgeShellResult(0, "DROIDE_BRIDGE_OK", "")
            }
            override fun close() { live = false }
        }
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies({}, { transport },
            { _, _, _ -> error("No pairing in synchronization test") }, { error("No discovery in synchronization test") }))
        try {
            ShadowProcess.setPid(4242)
            File(root, "pending").writeText("user project")
            if (connected) bridge.connect(MdnsEndpoint("adb-fixture-A1b2C3", "192.0.2.1", 37123, MdnsServiceType.TLS_CONNECT))
            val before = probes
            val manager = AndroidDevelopmentManager(context, root, bridge)
            block(manager, bridge) { probes - before }
            assertEquals("user project", File(root, "pending").readText())
            assertEquals(listOf("pending"), root.listFiles()!!.map { it.name })
        } finally {
            try { bridge.close(); scope.coroutineContext.job.cancelAndJoin() }
            finally { ShadowProcess.setPid(previousPid); PathSecurity.deleteTreeNoFollow(root) }
        }
    }

    @Test fun defaultLegacyProjectSyncFailsBeforeBridgeOrToolchainDiscovery() = fixture(false) { manager, _, calls ->
        val failure = runCatching { manager.prepareInteractiveShell() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Legacy device project synchronization is unavailable"))
        assertEquals(0, calls())
    }

    @Test fun connectedAdbSessionIsPreservedWhenUnsafeProjectSynchronizationIsRejected() = fixture(true) { manager, bridge, calls ->
        val endpoint = bridge.state.value.connected
        val failure = runCatching { manager.prepareInteractiveShell(syncProject = true) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("file ancestry cannot be verified"))
        assertEquals(endpoint, bridge.state.value.connected)
        assertEquals(0, calls())
    }

    @Test fun nonsynchronizingAdbPreparationRetainsItsSeparateConnectionAdmission() = fixture(false) { manager, _, calls ->
        val failure = runCatching { manager.prepareInteractiveShell(syncProject = false) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Connect Device Workstation first", failure!!.message)
        assertEquals(0, calls())
    }
}
