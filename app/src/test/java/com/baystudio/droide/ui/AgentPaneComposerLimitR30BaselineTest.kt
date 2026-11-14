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
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Proposed current-API full-pane baseline. Authoring only; not compiled or run here.
 * One bounded 64,001-UTF-16-unit edit, no send, CLI connection, provider, process, or device.
 * This tests the actual BasicTextField.onValueChange path rather than an unattached helper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentPaneComposerLimitR30BaselineTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val root = Files.createTempDirectory("r30-composer-limit-").toFile()
    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + Dispatchers.Main.immediate)
    private val ui = AgentWorkspaceUiState(scope)
    private val bridge by lazy { DeviceBridgeManager(app, scope, DeviceBridgeDependencies(
        configureIdentity = {},
        create = { error("Device transport is forbidden") },
        pair = { _, _, _ -> error("Pairing is forbidden") },
        discovery = { error("Discovery is forbidden") },
        resetIdentity = { error("Identity changes are forbidden") },
    )) }
    private val connections by lazy { ProviderConnectionBackend(app) }
    private val localModels by lazy { LocalLlamaAgentModelController(app, bridge, connections,
        LocalLlamaServerManager(app, bridge, providerBackend = connections)) }
    private val agent = AgentService(FileRepository(root), DeniedTerminal(), GitManager(root),
        maxSubagentDepth = 0, runtimeScope = scope)
    private var compositionHost: AbstractComposeView? = null
    private var attemptedMount = false
    private var compositionDetached = false
    private val limitError = "That edit exceeds the 64,000 UTF-16 code-unit limit. Your previous draft is unchanged."

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

    @After fun cleanup() {
        val deadline = System.nanoTime() + 3_000_000_000L
        val failures = mutableListOf<Throwable>()
        fun cleanupStep(action: () -> Unit) { runCatching(action).exceptionOrNull()?.let(failures::add) }
        fun remainingMs() = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)
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
        if ((!attemptedMount || compositionDetached) && joined && failures.isEmpty()) PathSecurity.deleteTreeNoFollow(root)
        else throw AssertionError("Fixture cleanup did not settle within 3s; preserved workspace: $root").also {
            failures.forEach(it::addSuppressed)
        }
    }

    private fun mount() {
        runBlocking { assertTrue(AndroidLocalGgufImporter(app).listStored().isEmpty()) }
        ui.backendId.value = "codex"
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

    @Test(timeout = 20_000) fun anOverLimitEditKeepsThePreviousDraftAndShowsAnExplicitError() {
        mount()
        val field = compose.onNode(hasSetTextAction())
        val previous = "Keep this draft \uD83E\uDDEA intact."
        field.performTextInput(previous)
        val rejected = "x".repeat(64_001)
        assertEquals(AgentService.MAX_USER_MESSAGE_CHARS + 1, rejected.length)
        field.performTextReplacement(rejected)

        compose.runOnIdle { assertEquals(previous, ui.input.value) }
        field.assertTextContains(previous)
        compose.onNodeWithText(limitError).assertExists()
        assertFalse(agent.busy.value)
        assertFalse(ui.externalController.busy)

        val revised = "Shorter valid draft \uD83E\uDDEA."
        field.performTextReplacement(revised)
        compose.runOnIdle { assertEquals(revised, ui.input.value) }
        compose.onNodeWithText(limitError).assertDoesNotExist()
    }

    @Test(timeout = 20_000) fun anUnderLimitSupplementaryUnicodeEditIsPreservedExactly() {
        mount()
        val text = "Review \uD83E\uDDEA and \uD83D\uDE80 without changing either symbol."
        assertEquals(text.length - 2, text.codePointCount(0, text.length))
        compose.onNode(hasSetTextAction()).performTextReplacement(text)

        compose.runOnIdle { assertEquals(text, ui.input.value) }
        compose.onNode(hasSetTextAction()).assertTextContains(text)
        compose.onNodeWithText(limitError).assertDoesNotExist()
        assertFalse(agent.busy.value)
        assertFalse(ui.externalController.busy)
    }

    @Test(timeout = 20_000) fun aValidEditKeepsAnUnrelatedProviderError() {
        mount()
        val providerError = "Fixture provider preparation failed; reconnect the provider."
        compose.runOnIdle { ui.submissionError.value = providerError }
        val text = "A valid revised draft \uD83E\uDDEA."
        compose.onNode(hasSetTextAction()).performTextReplacement(text)

        compose.runOnIdle {
            assertEquals(text, ui.input.value)
            assertEquals(providerError, ui.submissionError.value)
        }
        compose.onNodeWithText(providerError).assertExists()
        compose.onNodeWithText(limitError).assertDoesNotExist()
    }
}
