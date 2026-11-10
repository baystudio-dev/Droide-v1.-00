package com.baystudio.droide.core

import android.app.Application
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.MdnsDiscoveryState
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Authored Android-host regressions. In-memory transports only; no network or device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class DeviceBridgeExitTest {
    private val endpoint = MdnsEndpoint("adb-exit-A1b2C3", "192.0.2.1", 37123, MdnsServiceType.TLS_CONNECT)

    @Test(timeout = 10_000) fun ordinaryCloseRetainsFailedTransportForExitRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failClose = AtomicBoolean(true)
        val transport = Peer(closeFailure = failClose)
        val bridge = create(scope, transport)
        try {
            bridge.connect(endpoint)
            bridge.close()
            assertTrue(runCatching { bridge.stopAndAwaitForExit() }.isFailure)
            assertTrue(transport.open)
            failClose.set(false)
            bridge.stopAndAwaitForExit()
            assertFalse(transport.open)
        } finally { failClose.set(false); bridge.close(); scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test(timeout = 10_000) fun exitWaitsForExactConnectionWorkerAfterCancellation() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bridge = create(scope, Peer(entered = entered, release = release))
        val connecting = scope.async { runCatching { bridge.connect(endpoint) } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            bridge.close()
            val exit = async { bridge.stopAndAwaitForExit() }
            delay(100)
            assertFalse("Cancelled Future is not proof that its OS worker returned", exit.isCompleted)
            release.countDown()
            withTimeout(3_000) { exit.await() }
            connecting.await()
        } finally { release.countDown(); bridge.close(); scope.coroutineContext.job.cancelAndJoin() }
    }

    private fun create(scope: CoroutineScope, peer: Peer) = DeviceBridgeManager(
        RuntimeEnvironment.getApplication(), scope,
        DeviceBridgeDependencies({}, { peer }, { _, _, _ -> endpoint.name }, {
            object : DeviceBridgeDiscovery {
                override val state = MutableStateFlow(MdnsDiscoveryState())
                override fun start() = Unit
                override fun close() = Unit
            }
        }, DeviceBridgeTiming(connectionMs = 5_000)),
    )

    private class Peer(
        val closeFailure: AtomicBoolean = AtomicBoolean(false),
        val entered: CountDownLatch? = null,
        val release: CountDownLatch? = null,
    ) : DeviceBridgeTransport {
        private val live = AtomicBoolean(true)
        override val kadb: Kadb? = null
        override val open get() = live.get()
        override val encrypted = true
        override fun probe(command: String, outputLimit: Int): BridgeShellResult {
            entered?.countDown()
            release?.let { latch -> while (latch.count > 0) {
                try { latch.await() } catch (_: InterruptedException) {   }
            } }
            return BridgeShellResult(0, "DROIDE_BRIDGE_OK", "")
        }
        override fun close() { if (closeFailure.get()) throw IOException("fixture close failed"); live.set(false) }
    }
}
