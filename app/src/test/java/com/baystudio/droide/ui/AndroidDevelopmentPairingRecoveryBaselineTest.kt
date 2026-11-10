package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowProcess

/** No live identity store, sockets, discovery, device, or process launch. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w900dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidDevelopmentPairingRecoveryBaselineTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("pairing-recovery-ui-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app get() = RuntimeEnvironment.getApplication()
    private val resets = AtomicInteger()
    private val pairCalls = AtomicInteger()
    private val forbiddenCalls = ConcurrentLinkedQueue<String>()
    @Volatile private var fixtureTransport: FixtureTransport? = null
    private val connectionEndpoint = MdnsEndpoint("adb-fixture-A1b2C3", "192.0.2.1", 37123, MdnsServiceType.TLS_CONNECT)
    private var previousPid: Int? = null
    private var ownsJournalDirectory = false
    private val journalDirectory by lazy {
        File(app.filesDir, "operation-journal/${AndroidDevelopmentManager.stableProjectId(root)}")
    }
    private val failure = "ADB host identity cannot be decrypted; reset pairing identity and pair again"
    private val bridgeDelegate = lazy {
        DeviceBridgeManager(app, scope, DeviceBridgeDependencies(
            configureIdentity = {},
            create = { fixtureTransport ?: forbidden("bridge transport") },
            pair = { _, _, _ -> pairCalls.incrementAndGet(); throw IllegalStateException(failure) },
            discovery = { forbidden("bridge discovery") },
            timing = DeviceBridgeTiming(connectionMs = 12_000, probeMs = 10_000),
            resetIdentity = { resets.incrementAndGet(); Unit },
        ))
    }
    private val bridge by bridgeDelegate
    private val terminalsDelegate = lazy {
        TerminalManager(scope, root,
            linuxLaunchSpecFactory = { _, _ -> forbidden("terminal Linux preparation") },
            localSessionFactory = { _, _, _ -> forbidden("terminal session creation") })
    }
    private val terminals by terminalsDelegate
    private val development by lazy { AndroidDevelopmentManager(app, root, bridge) }

    private fun forbidden(action: String): Nothing {
        forbiddenCalls.add(action)
        error("Fixture must never execute $action")
    }

    private fun assertNoAndroidProject() {
        listOf("settings.gradle.kts", "settings.gradle", "gradlew").forEach {
            assertFalse("Fixture must not admit Android toolchain discovery: $it", File(root, it).exists())
        }
        assertNull(AndroidProjectDetector.detect(root))
    }

    @Before fun prepare() {
        previousPid = android.os.Process.myPid()
        ShadowProcess.setPid(4242)
        assertNoAndroidProject()
        assertFalse("Fixture journal must be new", journalDirectory.exists())
        ownsJournalDirectory = true
    }

    @After fun cleanup() {
        try {
            try {
                if (bridgeDelegate.isInitialized()) bridge.close()
            } finally {
                if (terminalsDelegate.isInitialized()) terminals.dispose(false).getOrThrow()
            }
        } finally {
            try {
                scope.cancel()
                try {
                    PathSecurity.deleteTreeNoFollow(root)
                } finally {
                    if (ownsJournalDirectory) PathSecurity.deleteTreeNoFollow(journalDirectory)
                }
            } finally {
                previousPid?.let(ShadowProcess::setPid)
                assertEquals("No real subprocess, terminal or bridge work is allowed", emptyList<String>(), forbiddenCalls.toList())
            }
        }
    }

    private fun mountSettings() {
        val themes = ThemeManager(root)
        val formatter = FormatterManager(root, terminals)
        val github = GitHubAccountManager(app)
        // The automatic refresh must take the detector's null-model return before any probe.
        assertNoAndroidProject()
        compose.setContent { DroideTheme { Box(Modifier.size(900.dp, 640.dp)) {
            SettingsScreen(themes, formatter, false, false, true, CodingKeyboardMode.COMPACT,
                CodeStyleDefaults(), development, bridge, github, "Recovery fixture", PermissionPolicyDocument.EMPTY,
                onWordwrap = {}, onHardwareShortcuts = {}, onAccessoryKeysComfortable = {},
                onCodingKeyboardMode = {}, onDetectIndentation = {}, onIndentStyle = {}, onTabWidth = {},
                onIndentSize = {}, onContinuationIndent = {}, onTheme = {}, onPermissionRuleChange = { _, _ -> PermissionPolicyDocument.EMPTY },
                onOpenProviders = {}, onOpenModels = {}, onOpenExtensions = {}, onOpenFiles = {},
                onOpenTerminal = {}, onOpenGit = {}, onOpenRunDebug = {}, onOpenAgent = {},
                onOpenAgentSessions = {}, onOpenCapabilityHealth = {}, mcpHealth = emptyList(),
                onRetryMcp = { "Retry checked" }, onOpenMcpConfig = {}, initialCategoryName = "RUN", onDismiss = {})
        } } }
        compose.onNodeWithText("Switch to Workspace settings").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            development.status.value.message == "Not an Android Gradle project."
        }
        assertFalse(development.status.value.androidProject)
        assertEquals(AndroidDevelopmentManager.Readiness.UNSUPPORTED, development.status.value.readiness)
        assertTrue("Fresh journal must stay empty", journalDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(emptyList<String>(), forbiddenCalls.toList())
    }

    @Test(timeout = 15_000) fun disconnectedIdentityFailureHasAnExplicitRecoveryActionInFullSettings() {
        val endpoint = MdnsEndpoint("fixture-pair", "192.0.2.1", 37123, MdnsServiceType.TLS_PAIRING)
        val error = runBlocking { withTimeout(3_000) { runCatching { bridge.pair(endpoint, "123456") }.exceptionOrNull() } }
        assertEquals(failure, error?.message)
        assertNull(bridge.state.value.connected)
        assertNull(bridge.state.value.attemptId)
        assertEquals(failure, bridge.state.value.lastError)
        assertEquals(1, pairCalls.get())

        mountSettings()
        compose.onNodeWithText(failure).assertExists()
        compose.runOnIdle { assertEquals("Rendering an error must not reset identity", 0, resets.get()) }
        // This assertion should fail on unmodified . It must become reachable after the UI repair.
        compose.onNodeWithText("Reset pairing").assertExists().performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 3_000) { resets.get() == 1 }
        compose.onNodeWithText("Pairing reset.").assertExists()
        assertNull(bridge.state.value.connected)
    }

    @Test(timeout = 15_000) fun ordinaryDisconnectedSettingsExposeResetWithoutResettingOnRender() {
        mountSettings()
        compose.onNodeWithText("Reset pairing").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, resets.get())
            assertEquals(0, pairCalls.get())
            assertNull(bridge.state.value.connected)
        }
    }

    @Test(timeout = 15_000) fun connectedSettingsKeepDisconnectAndResetAndCloseOnlyAfterExplicitReset() {
        val transport = FixtureTransport().also { fixtureTransport = it }
        runBlocking { withTimeout(3_000) { bridge.connect(connectionEndpoint) } }
        mountSettings()
        compose.onNodeWithText("Disconnect").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Reset pairing").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(connectionEndpoint, bridge.state.value.connected)
            assertTrue(transport.open)
            assertEquals(2, transport.probes.get())
            assertEquals(0, transport.closes.get())
            assertEquals(0, resets.get())
        }
        compose.onNodeWithText("Reset pairing").performClick()
        compose.waitUntil(timeoutMillis = 3_000) {
            resets.get() == 1 && transport.closes.get() == 1 && bridge.state.value.connected == null
        }
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.onNodeWithText("Reset pairing").assertIsEnabled()
    }

    @Test(timeout = 15_000) fun activeConnectDisablesResetAndCancelRestoresItWithoutResetting() {
        val transport = FixtureTransport(blockProbe = true).also { fixtureTransport = it }
        mountSettings()
        val connecting = scope.async { runCatching { bridge.connect(connectionEndpoint) } }
        try {
            compose.waitUntil(timeoutMillis = 3_000) {
                transport.started.count == 0L && bridge.state.value.attemptId != null
            }
            compose.onNodeWithText("Reset pairing").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled().performClick()
            val result = runBlocking { withTimeout(3_000) { connecting.await() } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            compose.waitUntil(timeoutMillis = 3_000) { bridge.state.value.attemptId == null && transport.closes.get() == 1 }
            compose.onNodeWithText("Reset pairing").performScrollTo().assertIsEnabled()
            assertEquals(0, resets.get())
            assertNull(bridge.state.value.connected)
        } finally {
            bridge.state.value.attemptId?.let(bridge::cancelConnect)
            transport.close()
            connecting.cancel()
        }
    }

    private class FixtureTransport(private val blockProbe: Boolean = false) : DeviceBridgeTransport {
        override val kadb: Kadb? = null
        override val encrypted = true
        @Volatile private var live = false
        override val open get() = live
        val probes = AtomicInteger()
        val closes = AtomicInteger()
        val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val closed = AtomicBoolean()

        override fun probe(command: String, outputLimit: Int): BridgeShellResult {
            check(command == "printf DROIDE_BRIDGE_OK" || command == RemoteProcessLease.reapAllCommand())
            probes.incrementAndGet()
            started.countDown()
            if (blockProbe) check(release.await(8, TimeUnit.SECONDS)) { "Fixture probe was not cancelled" }
            check(!closed.get()) { "Fixture transport closed" }
            live = true
            return BridgeShellResult(0, "DROIDE_BRIDGE_OK", "")
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                live = false
                closes.incrementAndGet()
                release.countDown()
            }
        }
    }
}
