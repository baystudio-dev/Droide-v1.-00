package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** All discovery, pairing and transports are in-memory fakes. No sockets, DNS or devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DeviceBridgeManagerConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bridges = mutableListOf<DeviceBridgeManager>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("droide_device_bridge", Context.MODE_PRIVATE)
    private val endpoint = MdnsEndpoint("adb-real-A1b2C3", "192.0.2.1", 37123, MdnsServiceType.TLS_CONNECT)

    @Before fun prepare() { prefs.edit().clear().commit() }
    @After fun cleanup() { bridges.forEach { it.close() }; scope.cancel() }

    private fun bridge(
        timing: DeviceBridgeTiming = DeviceBridgeTiming(),
        create: (MdnsEndpoint) -> DeviceBridgeTransport,
        pair: (MdnsEndpoint, String, com.flyfishxu.kadb.KadbConnectionControl) -> String = { _, _, _ -> endpoint.name },
        discovery: () -> DeviceBridgeDiscovery = { Discovery(emptyList()) },
        resetIdentity: () -> Unit = {},
    ): DeviceBridgeManager = DeviceBridgeManager(context, scope,
        DeviceBridgeDependencies({}, create, pair, discovery, timing, resetIdentity)).also { bridges.add(it) }

    @Test(timeout = 5_000) fun cancelAbortsOwnedBlockingProbeWithoutPublication() = runBlocking {
        val transport = Transport(block = true)
        val bridge = bridge(create = { transport })
        val attempt = async { runCatching { bridge.connect(endpoint) } }
        await(transport.started)
        bridge.cancelConnect(requireNotNull(bridge.state.value.attemptId))
        assertTrue(withTimeout(2_000) { attempt.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, transport.closes.get())
        assertNull(bridge.state.value.connected)
        assertNull(bridge.state.value.attemptId)
    }

    @Test(timeout = 5_000) fun redundantConnectAndProtocolMismatchDoNotProbeOrClosePublishedSession() = runBlocking {
        val transport = Transport()
        var creations = 0
        val bridge = bridge(create = { creations++; transport })
        bridge.connect(endpoint)
        val probes = transport.probes.get()
        assertTrue(bridge.connect(endpoint.copy(port = 40111)).startsWith("Already connected"))
        assertTrue(runCatching { bridge.connect(endpoint.copy(serviceType = MdnsServiceType.ADB)) }.isFailure)
        assertEquals(1, creations)
        assertEquals(probes, transport.probes.get())
        assertEquals(0, transport.closes.get())
        assertEquals(endpoint, bridge.state.value.connected)
    }

    @Test(timeout = 5_000) fun cancellingHealthProbePreservesPublishedSession() = runBlocking {
        val published = Transport()
        val probe = Transport(block = true)
        var count = 0
        val bridge = bridge(create = { if (count++ == 0) published else probe })
        bridge.connect(endpoint)
        val health = async { runCatching { bridge.ensureHealthyConnection() } }
        await(probe.started)
        bridge.cancelConnect(requireNotNull(bridge.state.value.attemptId))
        assertTrue(withTimeout(2_000) { health.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, published.closes.get())
        assertTrue(published.open)
        assertEquals(1, probe.closes.get())
        assertEquals(endpoint, bridge.state.value.connected)
    }

    @Test(timeout = 5_000) fun legacyHostPreferenceCannotStartAutomaticDiscovery() = runBlocking {
        prefs.edit().putString("last_paired_host", endpoint.host).commit()
        var discoveries = 0
        val bridge = bridge(create = { error("must not connect") }, discovery = { discoveries++; Discovery(listOf(endpoint)) })
        assertTrue(runCatching { bridge.ensurePackageBackend() }.exceptionOrNull()?.message.orEmpty().contains("Connect manually"))
        assertEquals(0, discoveries)
    }

    @Test(timeout = 5_000) fun successfulManualLegacyConnectDoesNotEnrollAndRemainsUsable() = runBlocking {
        val bridge = bridge(create = { Transport(encrypted = false) })
        bridge.connect(endpoint.copy(serviceType = MdnsServiceType.ADB))
        assertNull(prefs.getString("last_paired_guid", null))
        assertNull(prefs.getString("last_paired_host", null))
        assertTrue(bridge.ensurePackageBackend().startsWith("reachable:"))
    }

    @Test(timeout = 5_000) fun authenticatedPairResultSelectsOnlyMatchingGuidAtChangedHost() = runBlocking {
        val moved = endpoint.copy(host = "192.0.2.9", port = 40111)
        var selected: MdnsEndpoint? = null
        val bridge = bridge(create = { selected = it; Transport() },
            discovery = { Discovery(listOf(endpoint.copy(name = "adb-other-A1b2C3"), moved)) })
        bridge.pair(endpoint.copy(name = "untrusted-advertised-name", serviceType = MdnsServiceType.TLS_PAIRING), "123456")
        assertEquals(endpoint.name, prefs.getString("last_paired_guid", null))
        bridge.ensurePackageBackend()
        assertEquals(moved, selected)
    }

    @Test(timeout = 5_000) fun tlsLabelCannotPublishUnencryptedCandidate() = runBlocking {
        val plain = Transport(encrypted = false)
        val bridge = bridge(create = { plain })
        assertTrue(runCatching { bridge.connect(endpoint) }.isFailure)
        assertNull(bridge.state.value.connected)
        assertEquals(1, plain.closes.get())
    }

    @Test(timeout = 5_000) fun reusedTlsEndpointStillRequiresActualEncryptionWithoutClosingSession() = runBlocking {
        val transport = Transport()
        val bridge = bridge(create = { transport })
        bridge.connect(endpoint)
        transport.encrypted = false
        assertTrue(runCatching { bridge.connect(endpoint) }.isFailure)
        assertEquals(0, transport.closes.get())
        assertEquals(endpoint, bridge.state.value.connected)
    }

    @Test(timeout = 5_000) fun resetFencesLatePairingEnrollmentAndClosesOnlyPairingWork() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = bridge(create = { error("no connect") }, pair = { _, _, control ->
            control.own(Closeable { release.countDown() })
            entered.countDown()
            release.await()
            endpoint.name
        })
        val pairing = async { runCatching { bridge.pair(endpoint.copy(serviceType = MdnsServiceType.TLS_PAIRING), "123456") } }
        await(entered)
        bridge.resetPairingIdentity()
        assertTrue(withTimeout(2_000) { pairing.await() }.exceptionOrNull() is CancellationException)
        assertNull(prefs.getString("last_paired_guid", null))
        assertNull(prefs.getString("last_paired_host", null))
    }

    @Test(timeout = 5_000) fun failedIdentityResetStillDetachesSessionStopsDiscoveryAndFencesEnrollment() = runBlocking {
        val published = Transport()
        val discovered = Discovery(listOf(endpoint))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = bridge(create = { published }, discovery = { discovered },
            resetIdentity = { throw java.io.IOException("fixture delete failure") },
            pair = { _, _, control ->
                control.own(Closeable { release.countDown() })
                entered.countDown()
                release.await()
                endpoint.name
            })
        bridge.connect(endpoint)
        prefs.edit().putString("last_paired_guid", endpoint.name).putString("last_paired_host", endpoint.host).commit()
        bridge.startDiscovery()
        val pairing = async { runCatching { bridge.pair(endpoint.copy(serviceType = MdnsServiceType.TLS_PAIRING), "123456") } }
        await(entered)
        assertTrue(runCatching { bridge.resetPairingIdentity() }.exceptionOrNull() is java.io.IOException)
        assertTrue(withTimeout(2_000) { pairing.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, published.closes.get())
        assertTrue(discovered.closed)
        assertNull(bridge.state.value.connected)
        assertNull(bridge.state.value.attemptId)
        assertFalse(bridge.state.value.discovering)
        assertTrue(bridge.state.value.lastError.orEmpty().contains("Could not reset pairing identity"))
        assertNull(prefs.getString("last_paired_guid", null))
        assertNull(prefs.getString("last_paired_host", null))
    }

    @Test(timeout = 5_000) fun probeDeadlineClosesBlockingIoAndReturnsTimeout() = runBlocking {
        val transport = Transport(block = true)
        val bridge = bridge(timing = DeviceBridgeTiming(connectionMs = 2_000, probeMs = 50), create = { transport })
        assertTrue(runCatching { bridge.connect(endpoint) }.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1, transport.closes.get())
        assertNull(bridge.state.value.connected)
        assertTrue(bridge.state.value.lastError.orEmpty().contains("timed out"))
    }

    @Test(timeout = 5_000) fun totalDeadlineOwnsPairingIoAndCannotEnrollLateResult() = runBlocking {
        val closed = CountDownLatch(1)
        val bridge = bridge(timing = DeviceBridgeTiming(connectionMs = 80), create = { error("no connect") },
            pair = { _, _, control ->
                control.own(Closeable { closed.countDown() })
                closed.await()
                endpoint.name
            })
        assertTrue(runCatching { bridge.pair(endpoint.copy(serviceType = MdnsServiceType.TLS_PAIRING), "123456") }
            .exceptionOrNull() is TimeoutCancellationException)
        assertEquals(0L, closed.count)
        assertNull(prefs.getString("last_paired_guid", null))
    }

    @Test(timeout = 5_000) fun cancelledNoncooperativePairingDoesNotKeepConnectionMutex() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = bridge(create = { Transport() }, pair = { _, _, _ ->
            entered.countDown()
            while (release.count != 0L) {
                try { release.await() } catch (_: InterruptedException) { /* Simulate platform DNS. */ }
            }
            endpoint.name
        })
        try {
            val pairing = async { runCatching { bridge.pair(endpoint.copy(serviceType = MdnsServiceType.TLS_PAIRING), "123456") } }
            await(entered)
            bridge.cancelConnect(requireNotNull(bridge.state.value.attemptId))
            assertTrue(withTimeout(2_000) { pairing.await() }.exceptionOrNull() is CancellationException)
            assertTrue(withTimeout(2_000) { bridge.connect(endpoint) }.startsWith("Connected"))
            assertEquals(1L, release.count)
            assertNull(prefs.getString("last_paired_guid", null))
        } finally { release.countDown() }
    }

    @Test(timeout = 5_000) fun saturatedNoncooperativeWorkersKeepDeadlineAndRemoveCancelledQueuedProbe() = runBlocking {
        val entered = java.util.concurrent.LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        val queued = Transport()
        val next = Transport()
        var creations = 0
        val bridge = bridge(timing = DeviceBridgeTiming(connectionMs = 1_000, probeMs = 100),
            create = { if (creations++ == 0) queued else next }, pair = { _, _, _ ->
                entered.put(Unit)
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { /* Simulate platform DNS. */ }
                }
                endpoint.name
            })
        try {
            repeat(2) {
                val pairing = async { runCatching { bridge.pair(endpoint.copy(serviceType = MdnsServiceType.TLS_PAIRING), "123456") } }
                withContext(Dispatchers.IO) { assertNotNull(entered.poll(2, TimeUnit.SECONDS)) }
                assertTrue(bridge.cancelConnect(requireNotNull(bridge.state.value.attemptId)))
                assertTrue(withTimeout(2_000) { pairing.await() }.exceptionOrNull() is CancellationException)
            }
            assertTrue(runCatching { bridge.connect(endpoint) }.exceptionOrNull() is TimeoutCancellationException)
            assertEquals(0, queued.probes.get())
            assertEquals(1, queued.closes.get())
            assertNull(bridge.state.value.connected)
            release.countDown()
            assertTrue(withTimeout(2_000) { bridge.connect(endpoint) }.startsWith("Connected"))
            assertEquals(0, queued.probes.get())
        } finally { release.countDown() }
    }

    @Test(timeout = 5_000) fun stoppedDiscoveryCannotOverwriteNewEpoch() = runBlocking {
        val first = Discovery(listOf(endpoint))
        val nextEndpoint = endpoint.copy(name = "adb-next-A1b2C3")
        val second = Discovery(listOf(nextEndpoint))
        var count = 0
        val bridge = bridge(create = { error("no connection") }, discovery = { if (count++ == 0) first else second })
        bridge.startDiscovery()
        withTimeout(2_000) { bridge.state.first { it.connectEndpoints == listOf(endpoint) } }
        bridge.startDiscovery()
        withTimeout(2_000) { bridge.state.first { it.connectEndpoints == listOf(nextEndpoint) } }
        first.state.value = MdnsDiscoveryState(connectDevices = listOf(endpoint.copy(name = "stale")))
        assertEquals(listOf(nextEndpoint), bridge.state.value.connectEndpoints)
        assertTrue(first.closed)
    }

    @Test(timeout = 5_000) fun delayedResetCleanupCannotStopNewerDiscoveryEpoch() = runBlocking {
        val first = Discovery(listOf(endpoint))
        val newerEndpoint = endpoint.copy(name = "adb-newer-A1b2C3")
        val second = Discovery(listOf(newerEndpoint))
        val resetting = CountDownLatch(1)
        val releaseReset = CountDownLatch(1)
        var discoveries = 0
        val bridge = bridge(create = { error("no connection") },
            discovery = { if (discoveries++ == 0) first else second },
            resetIdentity = { resetting.countDown(); releaseReset.await() })
        try {
            bridge.startDiscovery()
            val reset = async { bridge.resetPairingIdentity() }
            await(resetting)
            bridge.startDiscovery()
            withTimeout(2_000) { bridge.state.first { it.connectEndpoints == listOf(newerEndpoint) } }
            releaseReset.countDown()
            withTimeout(2_000) { reset.await() }
            assertTrue(first.closed)
            assertFalse(second.closed)
            assertTrue(bridge.state.value.discovering)
            assertEquals(listOf(newerEndpoint), bridge.state.value.connectEndpoints)
        } finally { releaseReset.countDown() }
    }

    @Test(timeout = 5_000) fun explicitDisconnectClosesPublishedSession() = runBlocking {
        val transport = Transport()
        val bridge = bridge(create = { transport })
        bridge.connect(endpoint)
        bridge.disconnect()
        assertEquals(1, transport.closes.get())
        assertNull(bridge.state.value.connected)
    }

    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertTrue(latch.await(2, TimeUnit.SECONDS))
    }

    private class Discovery(private val endpoints: List<MdnsEndpoint>) : DeviceBridgeDiscovery {
        override val state = MutableStateFlow(MdnsDiscoveryState())
        var closed = false
        override fun start() { state.value = MdnsDiscoveryState(connectDevices = endpoints) }
        override fun close() { closed = true }
    }

    private class Transport(override var encrypted: Boolean = true, private val block: Boolean = false) : DeviceBridgeTransport {
        override val kadb: Kadb? = null
        @Volatile private var live = false
        override val open get() = live
        val closes = AtomicInteger()
        val probes = AtomicInteger()
        val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val closed = AtomicBoolean(false)
        override fun probe(command: String, outputLimit: Int): BridgeShellResult {
            probes.incrementAndGet()
            started.countDown()
            if (block) release.await()
            check(!closed.get()) { "closed" }
            live = true
            return BridgeShellResult(0, if (command.contains("DROIDE_BRIDGE_HEALTH")) "DROIDE_BRIDGE_HEALTH" else "DROIDE_BRIDGE_OK", "")
        }
        override fun close() {
            if (closed.compareAndSet(false, true)) { closes.incrementAndGet(); live = false; release.countDown() }
        }
    }
}
