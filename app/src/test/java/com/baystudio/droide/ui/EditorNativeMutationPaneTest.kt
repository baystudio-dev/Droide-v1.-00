package com.baystudio.droide.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual EditorPane/Sora/authority wiring. Authored; requires restored Android test dependencies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorNativeMutationPaneTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var state: EditorWorkspaceState
    private lateinit var document: EditorDocument
    private lateinit var bridge: ActiveEditorBridge
    private lateinit var authority: EditorWorkspaceDocumentAuthority
    private lateinit var editor: DroideCodeEditor
    private lateinit var sourceFile: java.io.File
    private var terminal: TerminalManager? = null
    private var visible by mutableStateOf(true)
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
    }
    private fun editors(view: View): List<DroideCodeEditor> = when (view) {
        is DroideCodeEditor -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { editors(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun mount() {
        val root = temp.newFolder()
        sourceFile = root.resolve("a.txt").apply { writeText("abc") }
        val files = FileRepository(root)
        state = EditorWorkspaceState()
        document = state.document("a.txt")
        runBlocking { document.ensureLoaded(files) }
        bridge = ActiveEditorBridge()
        authority = EditorWorkspaceDocumentAuthority(state, Dispatchers.Unconfined)
        val terminals = TerminalManager(scope, root,
            linuxLaunchSpecFactory = { _, _ -> error("No Linux launch permitted in this fixture") },
            localSessionFactory = { _, _, _ -> error("No terminal creation permitted in this fixture") },
        ).also { terminal = it }
        val formatter = FormatterManager(root, terminals)
        val theme = ThemeManager(root).snapshot()
        lateinit var rootView: View
        compose.setContent {
            rootView = LocalView.current
            DroideTheme { if (visible) EditorPane(files, "a.txt", document, bridge,
                lsp = lsp, formatter = formatter, theme = theme,
                accessoryInputFocus = remember { AccessoryInputFocusController() },
                accessoryKeysExpanded = false, onAccessoryKeysExpandedChange = {}) }
        }
        compose.runOnIdle { editor = editors(rootView).single() }
    }
    private fun snapshot() = runBlocking { checkNotNull(authority.snapshot("a.txt")) }
    private fun replace(expected: WorkspaceDocumentVersion, text: String) = runBlocking {
        authority.replaceText("a.txt", expected, text, "Agent edit")
    }
    @After fun cleanUp() { scope.cancel(); terminal?.dispose(false) }

    @Test fun appliedReturnsWithNativeModelAndImmediateCaptureSynchronized() {
        mount()
        compose.runOnIdle {
            val before = snapshot()
            assertTrue(replace(before.version, "XYZ") is WorkspaceDocumentMutationResult.Applied)
            assertEquals("XYZ", editor.text.toString())
            assertEquals("XYZ", document.content)
            assertTrue(bridge.capture())
            assertEquals("XYZ", document.content)
            assertEquals("abc", sourceFile.readText())
            editor.text.insert(0, 3, "!")
            assertEquals("XYZ!", document.content)
        }
    }
    @Test fun currentCaretIsClampedWithoutRejectingReviewedVersion() {
        mount()
        compose.runOnIdle {
            val before = snapshot()
            editor.setSelectionOffsets(2, 3)
            assertTrue(replace(before.version, "x") is WorkspaceDocumentMutationResult.Applied)
            assertEquals(1, editor.cursor.left)
            assertEquals(1, editor.cursor.right)
            assertEquals(1, document.selectionStart)
        }
    }
    @Test fun userTypingAfterReviewRemainsConflictAndIsNotOverwritten() {
        mount()
        compose.runOnIdle {
            val before = snapshot()
            editor.text.insert(0, 3, " user")
            assertTrue(replace(before.version, "XYZ") is WorkspaceDocumentMutationResult.Conflict)
            assertEquals("abc user", editor.text.toString())
            assertEquals("abc user", document.content)
        }
    }
    @Test fun existingUndoHistoryAndAgentReplacementAreSeparateUndoSteps() {
        mount()
        compose.runOnIdle {
            editor.text.insert(0, 3, "!")
            assertTrue(replace(snapshot().version, "XYZ") is WorkspaceDocumentMutationResult.Applied)
            editor.undo()
            assertEquals("abc!", editor.text.toString())
            assertEquals("abc!", document.content)
            editor.undo()
            assertEquals("abc", editor.text.toString())
            editor.redo()
            editor.redo()
            assertEquals("XYZ", editor.text.toString())
            assertEquals("XYZ", document.content)
        }
    }
    @Test fun retainedCompositionSettlesBeforeFinalVersionCheck() {
        mount()
        compose.runOnIdle {
            val before = snapshot()
            editor.setSelectionOffsets(3, 3)
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.setComposingText(" typed", 1))
            assertTrue(replace(before.version, "XYZ") is WorkspaceDocumentMutationResult.Conflict)
            assertFalse(editor.hasComposingText())
            assertEquals("abc typed", editor.text.toString())
            assertEquals("abc typed", document.content)
        }
    }
    @Test fun unfinishedBatchFailsWithoutReplacingTextAndCanRecover() {
        mount()
        compose.runOnIdle {
            val before = snapshot()
            editor.text.beginBatchEdit()
            try { assertTrue(runCatching { replace(before.version, "XYZ") }.isFailure) }
            finally { editor.text.endBatchEdit() }
            assertEquals("abc", editor.text.toString())
            assertTrue(replace(before.version, "XYZ") is WorkspaceDocumentMutationResult.Applied)
        }
    }
    @Test fun focusYieldDoesNotReleaseEditorOrMakeItReadOnly() {
        mount()
        compose.runOnIdle {
            editor.requestFocus()
            assertTrue(bridge.yieldToAgentComposer())
            assertFalse(editor.hasFocus())
            assertFalse(editor.isReleased)
            assertTrue(document.canEdit)
            assertTrue(replace(snapshot().version, "XYZ") is WorkspaceDocumentMutationResult.Applied)
        }
    }
    @Test fun detachedBindingCannotPublishOrClearReplacementFocus() {
        mount()
        lateinit var oldBinding: EditorNativeMutationBinding
        var oldYield: (() -> Unit)? = null
        compose.runOnIdle { oldBinding = checkNotNull(document.nativeMutationBinding); oldYield = bridge.yieldInputFocus; visible = false }
        compose.runOnIdle { assertNull(document.nativeMutationBinding); visible = true }
        compose.runOnIdle {
            val current = checkNotNull(document.nativeMutationBinding)
            assertNotSame(oldBinding, current)
            val replacement = current.owner as DroideCodeEditor
            replacement.requestFocus()
            oldYield?.invoke()
            assertTrue(replacement.hasFocus())
            assertTrue(runCatching { oldBinding.prepare() }.isFailure)
        }
    }
}
