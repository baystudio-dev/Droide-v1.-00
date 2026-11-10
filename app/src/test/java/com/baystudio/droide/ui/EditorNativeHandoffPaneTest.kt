package com.baystudio.droide.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import io.github.rosemoe.sora.event.EditorAttachStateChangeEvent
import io.github.rosemoe.sora.event.SubscriptionReceipt
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorNativeHandoffPaneTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = object : TemporaryFolder() {
        override fun after() {
            // A timed-out cancellation remains a test failure. Keep its directory rather than deleting
            // storage that an unfinished real-IO operation may still own.
            if (ownerJob.isCompleted) super.after()
        }
    }
    private val ownerJob = SupervisorJob()
    private val ownerScope = CoroutineScope(ownerJob + Dispatchers.Main.immediate)
    private var terminals: TerminalManager? = null
    private val linuxPreparations = AtomicInteger()
    private val terminalCreations = AtomicInteger()
    private var view: DroideCodeEditor? = null
    private var binding: AutoCloseable? = null
    private var detachReceipt: SubscriptionReceipt<EditorAttachStateChangeEvent>? = null
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
    }
    private class Cipher : EditorRecoveryCipher {
        override fun encrypt(plain: ByteArray, aad: ByteArray) = plain.copyOf()
        override fun decrypt(encrypted: ByteArray, aad: ByteArray) = encrypted.copyOf()
    }
    @After fun cleanUp() {
        // The detach assertion belongs to the measured transition, not Activity-rule teardown after a
        // failed setup. Stop that test-only listener before fallback manual release.
        detachReceipt?.unsubscribe()
        detachReceipt = null
        ownerJob.cancel()
        try {
            runBlocking {
                withTimeout(5_000) {
                    // A cancelled IO operation still resumes through Android Main. Drain and join it before
                    // TemporaryFolder's rule removes its recovery directory.
                    while (!ownerJob.isCompleted) {
                        shadowOf(Looper.getMainLooper()).idle()
                        yield()
                    }
                    ownerJob.join()
                }
            }
        } finally {
            try {
                binding?.close()
                view?.release()
            } finally {
                terminals?.dispose(false)
                assertEquals("The pane fixture must not prepare a Linux backend", 0, linuxPreparations.get())
                assertEquals("The pane fixture must not create a terminal session", 0, terminalCreations.get())
            }
        }
    }
    private fun awaitOwnerResult(condition: () -> Boolean) = compose.waitUntil(5_000) {
        // Compose's frame clock does not run the real Main Handler return from store IO.
        shadowOf(Looper.getMainLooper()).idle()
        condition()
    }
    private fun editors(root: View): List<DroideCodeEditor> = when (root) {
        is DroideCodeEditor -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { editors(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test(timeout = 15_000) fun normalDetachCommandCannotMutateAfterRealPaneAdmission() = checkRelease(rawDelta = false)
    @Test(timeout = 15_000) fun unexpectedRawDetachDeltaSurvivesPaneDisposalAndAcknowledgedRecovery() = checkRelease(rawDelta = true)

    private fun checkRelease(rawDelta: Boolean) {
        val app = temp.newFolder()
        val root = File(app, "projects/A").apply { check(mkdirs()) }
        val source = File(root, "keep.txt").apply { writeText("saved") }
        val files = FileRepository(root)
        val state = EditorWorkspaceState()
        runBlocking { state.document("keep.txt").ensureLoaded(files) }
        state.retainNavigation(listOf("keep.txt"), "keep.txt", 1)
        state.completeRecovery()
        val originalVersion = state.document("keep.txt").savedDiskVersion
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = app
        }
        val store = EditorStateStore(context, "A", Cipher(), {})
        val bridge = ActiveEditorBridge()
        // Same retained binding as IdeScreen; the pane supplies its exact live native owner.
        binding = state.bindProjectHandoffCapture(bridge::captureForSave) { bridge.nativeHandoff }
        val terminalOwner = TerminalManager(ownerScope, root,
            linuxLaunchSpecFactory = { _, _ ->
                linuxPreparations.incrementAndGet()
                throw AssertionError("The pane fixture must never prepare a Linux backend")
            },
            localSessionFactory = { _, _, _ ->
                terminalCreations.incrementAndGet()
                throw AssertionError("The pane fixture must never create a terminal session")
            },
        ).also { terminals = it }
        val formatter = FormatterManager(root, terminalOwner)
        val theme = ThemeManager(root).snapshot()
        var visible by mutableStateOf(true)
        lateinit var composeRoot: View
        compose.setContent {
            composeRoot = LocalView.current
            DroideTheme {
                if (visible) EditorPane(
                    files, "keep.txt", state.document("keep.txt"), bridge,
                    lsp = lsp, formatter = formatter, theme = theme,
                    accessoryInputFocus = remember { AccessoryInputFocusController() },
                    accessoryKeysExpanded = false, onAccessoryKeysExpandedChange = {},
                )
            }
        }
        val gate = WorkspaceHandoffGate()
        val publishedB = Any()
        var prepared: Result<WorkspaceHandoffAdmission>? = null
        var detached = 0
        compose.runOnIdle {
            view = editors(composeRoot).single()
            detachReceipt = checkNotNull(view).subscribeEvent(EditorAttachStateChangeEvent::class.java) { event, _ ->
                if (!event.isAttachedToWindow) {
                    detached++
                    val old = checkNotNull(view)
                    assertFalse(old.isReleased)
                    if (rawDelta) old.text.insert(0, 5, "!") else old.commitText("!", false)
                }
            }
            ownerScope.launch { prepared = runCatching { prepareEditorProjectHandoff(state, store, gate) } }
        }
        awaitOwnerResult { prepared != null }
        compose.runOnIdle {
            val admission = checkNotNull(prepared).getOrThrow()
            admission()
            assertFalse(state.document("keep.txt").canEdit)
            admission.commit()
            gate.publishOwner(publishedB)
            visible = false
        }
        compose.runOnIdle {
            assertEquals(1, detached)
            assertTrue(checkNotNull(view).isReleased)
            assertNull(bridge.nativeHandoff)
            assertNull(checkNotNull(view).nativeContentSubscription)
            assertEquals(if (rawDelta) "saved!" else "saved", state.document("keep.txt").content)
            if (rawDelta) assertTrue(runCatching { gate.requireClear() }.isFailure)
            else gate.requireClear()
        }
        if (rawDelta) {
            var acknowledged: Result<Unit>? = null
            compose.runOnIdle {
                ownerScope.launch { acknowledged = runCatching { gate.retryPending(publishedB) } }
            }
            awaitOwnerResult { acknowledged != null }
            checkNotNull(acknowledged).getOrThrow()
        }
        val recovery = runBlocking { checkNotNull(store.load()) }
        if (rawDelta) {
            assertEquals("saved!", runBlocking { recovery.deferredBuffers.getValue("keep.txt").readText() })
            assertEquals(originalVersion, recovery.diskVersions.getValue("keep.txt"))
        } else assertTrue(recovery.dirtyPaths.isEmpty())
        assertEquals("saved", source.readText())
        compose.runOnIdle { gate.requireClear() }
    }
}
