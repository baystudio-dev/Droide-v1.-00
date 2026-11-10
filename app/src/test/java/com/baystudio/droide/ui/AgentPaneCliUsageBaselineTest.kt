package com.baystudio.droide.ui

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** AUTHORING ONLY: not compiled or executed. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPaneCliUsageBaselineTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("r30-cli-usage-").toFile()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
    private val ui = AgentWorkspaceUiState(scope)
    private val provider = AiProvider(
        "r30-usage-fixture", "R30 usage fixture", "https://r30.invalid/v1", "fixture-model",
        true, false, "", "Test data only; never requested", authScheme = ProviderAuthScheme.NONE,
    )
    private val bridge by lazy { DeviceBridgeManager(app, scope, DeviceBridgeDependencies(
        configureIdentity = {},
        create = { error("Device transport is forbidden in this UI fixture") },
        pair = { _, _, _ -> error("Pairing is forbidden in this UI fixture") },
        discovery = { error("Discovery is forbidden in this UI fixture") },
        resetIdentity = { error("Identity changes are forbidden in this UI fixture") },
    )) }
    private val connections by lazy { ProviderConnectionBackend(app) }
    private val localModels by lazy { LocalLlamaAgentModelController(app, bridge, connections,
        LocalLlamaServerManager(app, bridge, providerBackend = connections)) }
    private val agent = AgentService(FileRepository(root), DeniedTerminal(), GitManager(root),
        maxSubagentDepth = 0, runtimeScope = scope)
    private val spec = WorkspaceAgentPluginCatalog.entries.first { it.id == "codex" }
    private var compositionHost: AbstractComposeView? = null
    private var attemptedMount = false
    private var compositionDetached = false
    private var previousPreferences: Preferences? = null
    private var previousConnection: ProviderConnectionRecord? = null
    private var seededConnection = false

    @Before fun openUserSession() {
        ApplicationWorkAdmission.initialize(app)
        ApplicationWorkAdmission.reopenFromUser()
    }

    private class DeniedTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start(): Unit = error("Terminal start is forbidden")
        override fun send(cmd: String): Unit = error("Terminal input is forbidden")
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = error("Process execution is forbidden")
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = error("Process execution is forbidden")
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    private class UsageBackend(private val usage: WorkspaceAgentUsageSnapshot) : WorkspaceAgentChatBackend {
        override suspend fun runTurn(agentId: String, prompt: String, mode: WorkspaceAgentMode,
            resumeSessionId: String?, onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentTurnResult {
            onEvent(WorkspaceAgentEvent.SessionBound("r30-cli-session"))
            onEvent(WorkspaceAgentEvent.Usage(usage))
            return WorkspaceAgentTurnResult(agentId, "r30-cli-session", "Fixture reply", "end_turn", emptyList(), emptyList())
        }
        override suspend fun prepareSession(agentId: String, resumeSessionId: String?,
            onEvent: (WorkspaceAgentEvent) -> Unit): WorkspaceAgentEvent.Configuration = error("Not used")
        override suspend fun selectModel(agentId: String, expectedSessionId: String,
            modelId: String): WorkspaceAgentEvent.Configuration = error("Not used")
        override suspend fun newSession(agentId: String, onEvent: (WorkspaceAgentEvent) -> Unit): String = error("Not used")
        override suspend fun cancel(agentId: String, expectedSessionId: String?) = Unit
    }

    @After fun cleanup() {
        val deadline = System.nanoTime() + 3_000_000_000L
        val failures = mutableListOf<Throwable>()
        fun cleanupStep(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
        fun remainingMs() = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
        // Dispose the real hosting ComposeView before cancelling retained owners. The rule's eventual
        // second disposal is harmless; do not leave recomposition reading closed state.
        cleanupStep { compose.runOnUiThread { compositionHost?.disposeComposition() } }
        cleanupStep { agent.shutdown() }
        cleanupStep { ui.close() }
        cleanupStep { bridge.close() }
        scope.cancel()
        cleanupStep { shadowOf(Looper.getMainLooper()).idle() }
        while (!owner.isCompleted && remainingMs() > 0L) {
            cleanupStep { shadowOf(Looper.getMainLooper()).idle() }
            if (!owner.isCompleted) Thread.sleep(minOf(10L, remainingMs()))
        }
        val joined = runBlocking { withTimeoutOrNull(remainingMs().coerceAtLeast(1L)) { owner.join(); true } } == true
        var preferencesRestored = previousPreferences == null
        cleanupStep {
            previousPreferences?.let { previous ->
                preferencesRestored = runBlocking {
                    withTimeoutOrNull(remainingMs().coerceAtLeast(1L)) {
                        app.agentPreferences.updateData { previous }
                        true
                    }
                } == true
            }
        }
        cleanupStep {
            if (seededConnection) {
                val store = ProviderConnectionStateStore(app)
                assertTrue(previousConnection?.let(store::put) ?: store.remove(provider.id))
            }
            ProviderModelCertificationRegistry.revoke(provider.id)
            ProviderRegistry.installConfigured(emptyList())
        }
        val settled = (!attemptedMount || compositionDetached) && joined && preferencesRestored && failures.isEmpty()
        if (settled) PathSecurity.deleteTreeNoFollow(root)
        else throw AssertionError("Fixture cleanup did not settle within 3s; preserved workspace: $root").also {
            failures.forEach(it::addSuppressed)
        }
    }

    private fun mount(backendId: String) {

        runBlocking { assertTrue(AndroidLocalGgufImporter(app).listStored().isEmpty()) }
        ui.backendId.value = backendId
        attemptedMount = true
        compose.setContent {
            val view = LocalView.current
            SideEffect {
                compositionHost = generateSequence(view as View?) { it.parent as? View }
                    .filterIsInstance<AbstractComposeView>().firstOrNull()
            }
            DisposableEffect(Unit) { onDispose { compositionDetached = true } }
            DroideTheme { Box(Modifier.size(360.dp, 640.dp)) {
                AgentPane(agent, ui, connections, localModels)
            } }
        }
    }

    private fun publishUsage(usage: WorkspaceAgentUsageSnapshot) {
        compose.runOnIdle {
            assertTrue(ui.externalController.submit(UsageBackend(usage), spec, "Fixture prompt", WorkspaceAgentMode.EDIT))
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            !ui.externalController.busy && ui.externalController[spec.id].usage == usage
        }
    }

    private fun configureBuiltinSession() = runBlocking {
        // In-memory certification and fixture-only no-auth connection metadata. No connect() call.
        ProviderRegistry.installConfigured(listOf(provider))
        ProviderModelCertificationRegistry.certify(provider, listOf(provider.model))
        previousPreferences = app.agentPreferences.data.first()
        val store = ProviderConnectionStateStore(app)
        previousConnection = store.get(provider.id)
        seededConnection = true
        assertTrue(store.remove(provider.id))
        assertTrue(store.put(ProviderConnectionRecord(provider.id, "none", 1L, 1,
            ProviderConnectionIdentity.fingerprint(provider))))
        AgentPreferences.saveProvider(app, provider.id, provider.model)
        val saved = agent.sessions.create("Builtin fixture")
        agent.loadSession(saved)
        agent.tokens.reset()
        agent.tokens.add(provider.model, "Builtin-only fixture",
            """{"usage":{"prompt_tokens":700,"completion_tokens":77,"total_tokens":777,"cost":3.50}}""")
        assertEquals(777, agent.tokens.usage.value.total)
    }

    @Test(timeout = 20_000) fun selectedCliShowsItsOwnContextAndReportedCurrency() {
        configureBuiltinSession()
        mount(spec.id)
        publishUsage(WorkspaceAgentUsageSnapshot(96_000L, 128_000L, 1.25, "EUR"))

        compose.onNodeWithContentDescription("CLI context usage").assertExists().performClick()
        compose.onNodeWithText("Last reported context usage").assertExists()
        compose.onNodeWithText("96.0k / 128.0k tokens").assertExists()
        compose.onNodeWithText("EUR 1.25").assertExists()
        compose.onNodeWithText("777").assertDoesNotExist()
        compose.onNodeWithText("View Usage & Billing").assertDoesNotExist()
        compose.onNodeWithText("Compact session").assertDoesNotExist()
        assertEquals(777, agent.tokens.usage.value.total)
    }

    @Test(timeout = 20_000) fun missingCliTelemetryIsUnknownEvenWhenBuiltinUsageExists() {
        configureBuiltinSession()
        mount(spec.id)

        compose.onNodeWithContentDescription("CLI context usage").performClick()
        compose.onNodeWithText("This CLI has not reported context usage.").assertExists()
        compose.onNodeWithText("777").assertDoesNotExist()
        compose.onNodeWithText("View Usage & Billing").assertDoesNotExist()
        compose.onNodeWithText("Compact session").assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun switchingToCliRemovesAnAlreadyOpenBuiltinCompactMenu() {
        configureBuiltinSession()
        mount("droide")
        compose.onNodeWithContentDescription("Usage and billing").performClick()

        compose.onNodeWithText("Compact session").assertIsEnabled()
        compose.onNodeWithText("View Usage & Billing").assertExists()

        compose.runOnIdle { ui.backendId.value = spec.id }
        compose.onNodeWithText("Compact session").assertDoesNotExist()
        compose.onNodeWithText("View Usage & Billing").assertDoesNotExist()
        compose.onNodeWithContentDescription("CLI context usage").assertExists()
    }

    @Test(timeout = 20_000) fun switchingToCliRemovesAnAlreadyOpenBuiltinBillingSheet() {
        configureBuiltinSession()
        mount("droide")
        compose.onNodeWithContentDescription("Usage and billing").performClick()
        compose.onNodeWithText("View Usage & Billing").performClick()
        compose.onNodeWithText("Token Usage").assertExists()

        compose.runOnIdle { ui.backendId.value = spec.id }
        compose.onNodeWithText("Token Usage").assertDoesNotExist()
        compose.onNodeWithText("Reset usage history").assertDoesNotExist()
        compose.onNodeWithContentDescription("CLI context usage").assertExists()
    }
}
