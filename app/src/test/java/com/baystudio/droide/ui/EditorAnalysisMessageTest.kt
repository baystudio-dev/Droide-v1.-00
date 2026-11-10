package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w420dp-h860dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAnalysisMessageTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("editor-analysis-message-").toFile()
    private val files = FileRepository(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val terminals = TerminalManager(scope, root)
    private val formatter = FormatterManager(root, terminals)
    private val bridge = ActiveEditorBridge()
    private val state = EditorWorkspaceState()
    private var path by mutableStateOf("untitled.cs")
    private var visible by mutableStateOf(true)
    private var languageMessage by mutableStateOf(
        "Select and save an analysis directory and .sln/.slnx, or explicitly choose recursive directory discovery. Enable is separate.")
    private val revision = MutableStateFlow(0L)
    private var analysisChanges = 0
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override val statusRevision = revision
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        override fun csharpSelectionStatus(path: String) =
            CSharpAnalysisSelections.Status(null, root.path, false, languageMessage)
        override suspend fun enableProjectAnalysis(path: String): String { analysisChanges++; return "enabled" }
        override fun revokeProjectAnalysis(path: String): String { analysisChanges++; return "revoked" }
    }

    @After fun cleanup() { terminals.dispose(false); scope.cancel(); root.deleteRecursively() }

    private fun mount() {
        for (name in listOf("untitled.cs", "other.cs")) root.resolve(name).writeText("Console.WriteLine(\"Hello\");\n")
        runBlocking { for (name in listOf("untitled.cs", "other.cs")) state.document(name).ensureLoaded(files) }
        state.document(path).status = ""
        val theme = ThemeManager(root).snapshot()
        compose.setContent {
            DroideTheme {
                val focus = remember { AccessoryInputFocusController() }
                Column(Modifier.fillMaxSize()) {
                    if (visible) key(path) {
                        EditorPane(files, path, state.document(path), bridge, lsp = lsp, formatter = formatter,
                            theme = theme, accessoryInputFocus = focus, accessoryKeysExpanded = false,
                            onAccessoryKeysExpandedChange = {})
                    }
                }
            }
        }
    }

    private fun dismissAnalysisMessage() {
        compose.onNode(hasAnySibling(hasText(languageMessage)) and
            hasContentDescription("Dismiss editor message")).performClick()
    }

    @Test fun closingCsharpNoticeSurvivesEditorReentryWithoutChangingAnalysisPermissions() {
        mount()
        compose.onNodeWithText(languageMessage).assertExists()
        dismissAnalysisMessage()
        compose.onNodeWithText(languageMessage).assertDoesNotExist()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle { visible = true; revision.value++ }
        compose.onNodeWithText(languageMessage).assertDoesNotExist()
        assertEquals(0, analysisChanges)
        assertEquals("Console.WriteLine(\"Hello\");\n", root.resolve(path).readText())
    }

    @Test fun changedNoticeReappearsAndClosingItDoesNotClearDirtyBufferOrConflict() {
        mount()
        compose.runOnIdle {
            state.document(path).applyWorkspaceText("unsaved user code", "Changed on disk · unsaved edits kept.")
        }
        val dirtyText = state.document(path).content
        assertTrue(state.document(path).dirty)
        dismissAnalysisMessage()
        compose.onNodeWithText("Changed on disk · unsaved edits kept.").assertExists()
        compose.runOnIdle { languageMessage = "Selected C# root or solution is unavailable."; revision.value++ }
        compose.onNodeWithText(languageMessage).assertExists()
        dismissAnalysisMessage()
        assertEquals(dirtyText, state.document(path).content)
        assertTrue(state.document(path).dirty)
        assertEquals("Changed on disk · unsaved edits kept.", state.document(path).status)
        assertEquals(0, analysisChanges)
    }
}
