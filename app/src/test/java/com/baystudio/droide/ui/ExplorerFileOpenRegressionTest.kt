package com.baystudio.droide.ui

import android.app.Application
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
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

/** Real Explorer clicks into initially unloaded documents and their Android editor surface. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h640dp-port")
class ExplorerFileOpenRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("explorer-open-").toFile()
    private val files = FileRepository(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val terminals = TerminalManager(scope, root)
    private val formatter = FormatterManager(root, terminals)
    private val bridge = ActiveEditorBridge()
    private val editorState = EditorWorkspaceState()
    private val explorerState = ExplorerWorkspaceState()
    private lateinit var composeView: View
    private var selectedPath by mutableStateOf<String?>(null)
    private var forcedLandscape by mutableStateOf<Boolean?>(null)
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
    }

    @After fun cleanUp() { terminals.dispose(false); scope.cancel(); root.deleteRecursively() }

    private fun editors(view: View = composeView): List<DroideCodeEditor> = when (view) {
        is DroideCodeEditor -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { editors(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun mount(editorConfig: String? = null) {
        root.resolve("main.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        root.resolve("README.md").writeText("# Readme\n\nOriginal text\n")
        editorConfig?.let { root.resolve(".editorconfig").writeText(it) }
        val theme = ThemeManager(root).snapshot()
        assertNull("Opening must exercise repository I/O, not a preloaded document", editorState.peek("main.kt"))
        compose.setContent {
            composeView = LocalView.current
            val original = LocalConfiguration.current
            val configuration = Configuration(original).apply {
                forcedLandscape?.let { orientation = if (it) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                DroideTheme {
                    val focus = remember { AccessoryInputFocusController() }
                    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    @Composable fun explorer() = compose.TestFileExplorer(files, explorerState, onOpen = { path ->
                        bridge.capture()
                        selectedPath = path
                    })
                    @Composable fun editor() {
                        val path = selectedPath ?: return
                        val document = editorState.document(path)
                        Column(Modifier.fillMaxSize().droideContentMotion(path)) {
                            DroideEditorTabs(listOf(path), path, { document.dirty }, {}, {})
                            DroideEditorToolbar(path, false, false, {}, {}, {}, {}, {})
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                key(path, document) {
                                    EditorPane(files, path, document, bridge, lsp = lsp, formatter = formatter,
                                        theme = theme, accessoryInputFocus = focus, accessoryKeysExpanded = false,
                                        onAccessoryKeysExpandedChange = {})
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        Button(onClick = { bridge.capture(); selectedPath = null }) { Text("Show Explorer") }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (landscape) Row(Modifier.fillMaxSize()) {
                                Box(Modifier.width(250.dp).fillMaxHeight()) { explorer() }
                                Box(Modifier.weight(1f).fillMaxHeight()) { editor() }
                            } else if (selectedPath == null) explorer() else editor()
                        }
                    }
                }
            }
        }
        await { explorerState.children[""] != null }
        compose.waitForIdle()
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(5_000) {
        compose.runOnIdle(condition)
    }

    private fun open(path: String) {
        // FileExplorer's actual clickable Row (not a test Button) owns this action.
        compose.onNode(hasText(path, substring = false) and hasClickAction()).performClick()
        await { editorState.peek(path)?.loaded == true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNull(editorState.document(path).loadError)
            assertEquals(1, editors().size)
            assertFalse(editors().single().isReleased)
            assertEquals(editorState.document(path).content, editors().single().text.toString())
        }
    }

    private fun reopenExplorer() = compose.onNode(hasText("Show Explorer") and hasClickAction()).performClick()

    private fun repeatedFileClicks() {
        mount()
        open("main.kt")
        lateinit var first: DroideCodeEditor
        compose.runOnIdle {
            first = editors().single()
            first.setSelection(0, 0)
            first.commitText("// unsaved\n", false)
        }
        reopenExplorer()
        open("README.md")
        compose.runOnIdle {
            assertTrue(first.isReleased)
            assertTrue(editorState.document("main.kt").dirty)
            assertTrue(editorState.document("main.kt").content.startsWith("// unsaved\n"))
        }
        repeat(5) {
            reopenExplorer(); open("main.kt")
            compose.runOnIdle { assertTrue(editors().single().text.toString().startsWith("// unsaved\n")) }
            reopenExplorer(); open("README.md")
        }
        compose.runOnIdle { assertFalse(root.resolve("main.kt").readText().startsWith("// unsaved\n")) }
    }

    @Test fun portraitExplorerOpensUnloadedFilesRepeatedlyWithoutLosingEdits() = repeatedFileClicks()

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeExplorerOpensUnloadedFilesRepeatedlyWithoutLosingEdits() = repeatedFileClicks()

    @Test
    @Config(qualifiers = "w800dp-h240dp-land")
    fun shortLandscapeExplorerWithRealEditorChromeOpensFiles() = repeatedFileClicks()

    @Test
    @Config(qualifiers = "w320dp-h480dp-port")
    fun smallPortraitExplorerWithRealEditorChromeOpensFiles() = repeatedFileClicks()

    private fun openBinaryAndImage() {
        root.resolve("asset.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0))
        root.resolve("pixel.png").writeBytes(java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))
        mount()
        for ((path, kind) in listOf("asset.bin" to EditorDocumentKind.BINARY, "pixel.png" to EditorDocumentKind.IMAGE)) {
            compose.onNode(hasText(path, substring = false) and hasClickAction()).performClick()
            await { editorState.peek(path)?.loaded == true }
            compose.waitForIdle()
            compose.runOnIdle {
                assertNull(editorState.document(path).loadError)
                assertEquals(kind, editorState.document(path).kind)
                assertTrue("Non-text documents must not mount Sora", editors().isEmpty())
            }
            reopenExplorer()
        }
        open("main.kt")
    }

    @Test fun portraitExplorerOpensBinaryImageThenText() = openBinaryAndImage()

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun landscapeExplorerOpensBinaryImageThenText() = openBinaryAndImage()

    @Test fun malformedEditorConfigDoesNotCrashPortraitFileSelection() {
        mount("root=true\n[[broken]\nindent_size=2\n")
        open("main.kt")
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun malformedEditorConfigDoesNotCrashLandscapeFileSelection() {
        mount("root=true\n[[broken]\nindent_size=2\n")
        open("main.kt")
    }

    @Test fun expandingDirectoryAndSelectingItsFileUsesSafeAsyncOpen() {
        root.resolve("src").mkdir()
        root.resolve("src/child.kt").writeText("val nested = true\n")
        mount()
        compose.onNode(hasText("src", substring = false) and hasClickAction()).performClick()
        await { explorerState.children["src"]?.any { it.path == "src/child.kt" } == true }
        compose.waitForIdle()
        // This row displays the leaf while its callback opens the full relative path.
        compose.onNode(hasText("child.kt", substring = false) and hasClickAction()).performClick()
        await { editorState.peek("src/child.kt")?.loaded == true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("src/child.kt", selectedPath)
            assertEquals("val nested = true\n", editors().single().text.toString())
            assertFalse(editors().single().isReleased)
        }
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun selectingSearchResultLoadsDocumentOnSameEditorPath() {
        mount()
        compose.onNode(hasSetTextAction()).performTextInput("README")
        await { "README.md" in explorerState.searchResults }
        compose.waitForIdle()
        open("README.md")
        compose.runOnIdle { assertEquals("README", explorerState.query) }
    }

    @Test
    @Config(qualifiers = "w720dp-h360dp-land")
    fun collapsingAndReopeningParentReloadsRetainedNestedExpansion() {
        root.resolve("src/sub").mkdirs()
        root.resolve("src/sub/child.kt").writeText("val before = true\n")
        mount()
        compose.onNode(hasText("src", substring = false) and hasClickAction()).performClick()
        await { explorerState.children["src"] != null }
        compose.waitForIdle()
        compose.onNode(hasText("sub", substring = false) and hasClickAction()).performClick()
        await { explorerState.children["src/sub"] != null }
        compose.waitForIdle()
        compose.onNode(hasText("src", substring = false) and hasClickAction()).performClick()
        compose.runOnIdle {
            assertFalse("src" in explorerState.children)
            assertFalse("src/sub" in explorerState.children)
            assertTrue("src/sub" in explorerState.expandedPaths)
            root.resolve("src/sub/child.kt").writeText("val fresh = true\n")
        }
        compose.onNode(hasText("src", substring = false) and hasClickAction()).performClick()
        await { explorerState.children["src/sub"] != null }
        compose.waitForIdle()
        compose.onNode(hasText("child.kt", substring = false) and hasClickAction()).performClick()
        await { editorState.peek("src/sub/child.kt")?.loaded == true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("val fresh = true\n", editors().single().text.toString()) }
    }

    @Test fun changingOrientationWithOpenUnsavedFileReleasesOnlyOldSurface() {
        mount()
        open("main.kt")
        val old = mutableListOf<DroideCodeEditor>()
        compose.runOnIdle {
            old += editors().single()
            old.single().setSelection(0, 0)
            old.single().commitText("// orientation edit\n", false)
            forcedLandscape = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(old.single().isReleased)
            assertEquals(1, editors().size)
            assertTrue(editors().single().text.toString().startsWith("// orientation edit\n"))
            old += editors().single()
            forcedLandscape = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(old.all { it.isReleased })
            assertEquals(1, editors().size)
            assertTrue(editors().single().text.toString().startsWith("// orientation edit\n"))
            assertTrue(editorState.document("main.kt").dirty)
        }
    }
}
