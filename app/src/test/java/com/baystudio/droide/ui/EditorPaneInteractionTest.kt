package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h600dp")
class EditorPaneInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("editor-ui-").toFile()
    private val files = FileRepository(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val terminals = TerminalManager(scope, root)
    private val formatter = FormatterManager(root, terminals)
    private val bridge = ActiveEditorBridge()
    private val state = EditorWorkspaceState()
    private lateinit var composeView: View
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

    private fun drawEditorFrame(): DroideCodeEditor {
        val editor = editors().single()
        assertTrue(editor.width > 0 && editor.height > 0)
        val bitmap = Bitmap.createBitmap(editor.width, editor.height, Bitmap.Config.ARGB_8888)
        try { editor.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        return editor
    }

    private fun mount(initialText: String? = null, wordwrap: Boolean = false) {
        root.resolve("install.sh").writeText(initialText ?: "if true; then\n  echo \"${"long line ".repeat(70)}\"\nfi\n")
        root.resolve("README.md").writeText("# Readme\n\nOriginal text\n")
        root.resolve("server.log").writeText("server started\nrequest completed\n")
        runBlocking { for (path in listOf("install.sh", "README.md", "server.log")) state.document(path).ensureLoaded(files) }
        val theme = ThemeManager(root).snapshot()
        compose.setContent {
            composeView = LocalView.current
            DroideTheme {
                var path by remember { mutableStateOf("install.sh") }
                var editorVisible by remember { mutableStateOf(true) }
                val focus = remember { AccessoryInputFocusController() }
                val uiScope = rememberCoroutineScope()
                val saves = remember { EditorSaveCoordinator(state, files, bridge, lsp) }
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(180.dp)) {
                        Button(onClick = { bridge.capture(); editorVisible = !editorVisible }) { Text("Toggle editor") }
                        for (file in listOf("install.sh", "README.md", "server.log")) {
                            Button(onClick = { bridge.capture(); path = file }) { Text(file) }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val document = state.document(path)
                        Column(Modifier.fillMaxSize()) {
                            DroideEditorToolbar(path, false, false, {}, {},
                                { uiScope.launch { saves.saveDocument(document) } }, {}, {},
                                onResetZoom = { bridge.requestResetZoom() })
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                if (editorVisible) key(path, document) {
                                    EditorPane(files, path, document, bridge, lsp = lsp, formatter = formatter,
                                        theme = theme, wordwrap = wordwrap, accessoryInputFocus = focus, accessoryKeysExpanded = false,
                                        onAccessoryKeysExpandedChange = {})
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun toolbarSaveCapturesTheActualEditorAndRetainsBomAndCrLfInLandscape() = checkNativeSave()

    @Test @Config(qualifiers = "w420dp-h860dp")
    fun toolbarSaveCapturesTheActualEditorAndRetainsBomAndCrLfInPortrait() = checkNativeSave()

    private fun checkNativeSave() {
        val before = "\uFEFFfirst\r\nsecond\r\n"
        val expected = "\uFEFFfirst\r\nupdated second\r\n"
        mount(before)
        compose.runOnIdle {
            val editor = editors().single()
            assertEquals(before, editor.text.toString())
            editor.setSelection(1, 0); editor.commitText("updated ", false)
            assertTrue(state.document("install.sh").dirty)
            assertEquals(before, root.resolve("install.sh").readText())
        }
        compose.onNodeWithContentDescription("Save").performClick()
        compose.waitUntil(5000) { root.resolve("install.sh").readText() == expected && !state.document("install.sh").dirty }
        compose.runOnIdle {
            assertEquals(expected, editors().single().text.toString())
            assertEquals(expected, state.document("install.sh").savedContent)
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), root.resolve("install.sh").readBytes())
        }
    }

    @Test fun clickingFilesRepeatedlyPreservesUnsavedTextAndReleasesOldEditors() {
        mount()
        lateinit var original: DroideCodeEditor
        compose.runOnIdle {
            original = editors().single()
            original.setSelection(0, 0)
            original.commitText("# unsaved\n", false)
            original.scroller.startScroll(0, 0, 240, 0, 0)
            original.scroller.abortAnimation()
            assertTrue(original.offsetX > 0)
        }
        compose.onNodeWithText("README.md").performClick()
        compose.runOnIdle {
            assertTrue(original.isReleased)
            assertEquals("# Readme\n\nOriginal text\n", editors().single().text.toString())
            assertTrue(state.document("install.sh").content.startsWith("# unsaved\n"))
            assertTrue(state.document("install.sh").dirty)
        }
        repeat(6) {
            compose.onNodeWithText("install.sh").performClick()
            compose.runOnIdle { assertTrue(editors().single().text.toString().startsWith("# unsaved\n")) }
            compose.onNodeWithText("README.md").performClick()
        }
        compose.runOnIdle {
            assertEquals(1, editors().size)
            assertFalse(editors().single().isReleased)
            assertFalse(root.resolve("install.sh").readText().startsWith("# unsaved\n"))
        }
        compose.onNodeWithText("server.log").performClick()
        compose.runOnIdle { assertEquals("server started\nrequest completed\n", editors().single().text.toString()) }
    }

    @Test fun reopeningFileRestoresReadPositionInsteadOfCursorAndKeepsItsZoom() {
        mount((0 until 400).joinToString("\n") { "line $it ${"wide ".repeat(80)}" })
        var savedX = 0
        var savedY = 0
        var selected = 0
        compose.runOnIdle {
            val editor = drawEditorFrame()
            assertEquals(13f, editor.textSizePx / editor.resources.displayMetrics.scaledDensity, 0.01f)
            editor.setSelection(0, 2)
            selected = editor.cursor.left
            editor.setTextSize(17f)
            editor.scroller.startScroll(0, 0, 180, 2_400, 0)
            editor.scroller.abortAnimation()
            savedX = editor.offsetX
            savedY = editor.offsetY
            assertTrue(savedX > 0 && savedY > 0)
        }
        compose.onNodeWithText("README.md").performClick()
        compose.runOnIdle {
            val editor = drawEditorFrame()
            assertEquals(13f, editor.textSizePx / editor.resources.displayMetrics.scaledDensity, 0.01f)
        }
        compose.onNodeWithText("install.sh").performClick()
        compose.runOnIdle {
            val editor = drawEditorFrame()
            assertEquals(savedX, editor.offsetX)
            assertEquals(savedY, editor.offsetY)
            assertEquals(selected, editor.cursor.left)
            assertEquals(17f, editor.textSizePx / editor.resources.displayMetrics.scaledDensity, 0.01f)
        }
        repeat(3) {
            compose.onNodeWithText("README.md").performClick()
            compose.onNodeWithText("install.sh").performClick()
        }
        compose.runOnIdle {
            assertEquals(savedX, drawEditorFrame().offsetX)
            assertEquals(savedY, drawEditorFrame().offsetY)
        }
    }

    @Test fun restoredViewportIsClampedWhenTheFileBecomesShorterWhileAway() {
        mount((0 until 300).joinToString("\n") { "line $it ${"wide ".repeat(80)}" })
        compose.runOnIdle {
            val editor = drawEditorFrame()
            editor.scroller.startScroll(0, 0, 180, 2_400, 0)
            editor.scroller.abortAnimation()
        }
        compose.onNodeWithText("README.md").performClick()
        compose.runOnIdle { state.document("install.sh").captureNativeText("short") }
        compose.onNodeWithText("install.sh").performClick()
        compose.runOnIdle {
            val editor = drawEditorFrame()
            assertEquals("short", editor.text.toString())
            assertTrue(editor.offsetY in 0..editor.scrollMaxY)
            assertTrue(editor.offsetX in 0..editor.scrollMaxX)
        }
    }


    @Test fun leavingTheEditorPaneAndReturningKeepsTheLastReadViewport() {
        mount((0 until 400).joinToString("\n") { "line $it ${"wide ".repeat(60)}" })
        var savedY = 0
        compose.runOnIdle {
            val editor = drawEditorFrame()
            editor.setSelection(0, 0)
            editor.scroller.startScroll(0, 0, 120, 2_400, 0)
            editor.scroller.abortAnimation()
            savedY = editor.offsetY
        }
        compose.onNodeWithText("Toggle editor").performClick()
        compose.runOnIdle { assertTrue(editors().isEmpty()) }
        compose.onNodeWithText("Toggle editor").performClick()
        compose.runOnIdle {
            assertEquals(savedY, drawEditorFrame().offsetY)
            assertEquals(120, drawEditorFrame().offsetX)
            assertEquals(0, editors().single().cursor.left)
        }
    }

    @Test
    @Config(sdk = [29, 35])
    fun accessibilityFontScalingDoesNotChangeZoomAcrossRepeatedRestoration() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val configuration = android.content.res.Configuration(app.resources.configuration).apply { fontScale = 2f }
        val context = app.createConfigurationContext(configuration)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val pixels = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 24f, context.resources.displayMetrics)
            assertNotEquals(24f, pixels / context.resources.displayMetrics.scaledDensity, 0.01f)
        }
        compose.runOnUiThread {
            for (size in listOf(13f, 17f, 24f, 48f)) {
                val editor = DroideCodeEditor(context, "plaintext", codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 4, "font-scale-test"))
                try {
                    editor.setTextSize(size)
                    val originalPixels = editor.textSizePx
                    repeat(5) {
                        val snapshot = editor.captureViewport()
                        assertEquals(size, snapshot.textSizeSp, 0.01f)
                        editor.setTextSize(snapshot.textSizeSp)
                        assertEquals(originalPixels, editor.textSizePx, 0.01f)
                    }
                } finally { editor.release() }
            }
        }
    }

    private fun settleEditorLayout() {
        try {
            compose.waitUntil(5_000) {
                var ready = false
                compose.runOnIdle {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    // Native AndroidView drawing is not driven by Compose's test clock.
                    if (editors().singleOrNull()?.let { it.width > 0 && it.height > 0 } == true) drawEditorFrame()
                    ready = editors().singleOrNull()?.isEditable == true
                }
                ready
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val editor = editors().singleOrNull()
            val details = io.github.rosemoe.sora.widget.CodeEditor::class.java.declaredFields
                .filter { it.name in setOf("layoutBusy", "editable") }.associate { field -> field.isAccessible = true; field.name to field.get(editor) }
            throw AssertionError("Editor layout did not settle: features=${editor?.acceptsFeatureUpdates}, status=${state.peek("install.sh")?.status}, dimensions=${editor?.width}x${editor?.height}, flags=$details, callbacks=${editor?.pendingEditorCallbackCount}", failure)
        }
        compose.runOnIdle { drawEditorFrame() }
    }

    @Test fun resetZoomKeepsReadingAnchorSelectionAndUndoHistory() = checkZoomReset()

    @Test @Config(qualifiers = "w420dp-h860dp")
    fun resetZoomKeepsReadingAnchorInPortrait() = checkZoomReset()

    private fun checkZoomReset() {
        val content = (0 until 200).joinToString("\n") { "line $it ${"wide ".repeat(60)}" }
        mount(content)
        settleEditorLayout()
        val editor = editors().single()
        compose.runOnIdle {
            editor.setSelection(0, 0)
            editor.commitText("unsaved ", false)
            editor.setSelectionRegion(2, 3, 2, 8)
        }
        val beforeText = editor.text.toString()
        val left = editor.cursor.left
        val right = editor.cursor.right
        for (size in listOf(24f, 9f)) {
            compose.runOnIdle { editor.setTextSize(size) }
            settleEditorLayout()
            var line = 0
            var column = 0
            var rowFraction = 0f
            var horizontalEm = 0f
            var revision = 0
            compose.runOnIdle {
                editor.scroller.startScroll(0, 0, 180, editor.rowHeight * 80 + editor.rowHeight / 3, 0)
                editor.scroller.abortAnimation()
                val point = editor.getPointPosition(editor.measureTextRegionOffset() + editor.offsetX, editor.offsetY.toFloat())
                line = io.github.rosemoe.sora.util.IntPair.getFirst(point)
                column = io.github.rosemoe.sora.util.IntPair.getSecond(point)
                val offsets = editor.layout.getCharLayoutOffset(line, column)
                rowFraction = (editor.offsetY - (offsets[0] - editor.rowHeight)) / editor.rowHeight
                horizontalEm = (editor.offsetX - offsets[1]) / editor.textSizePx
                revision = state.document("install.sh").changeVersion
            }
            compose.onNodeWithContentDescription("Reset editor zoom").performClick()
            settleEditorLayout()
            compose.runOnIdle {
                assertSame(editor, drawEditorFrame())
                assertEquals(DefaultEditorTextSizeSp, editor.captureViewport().textSizeSp, .01f)
                assertEquals(line, editor.firstVisibleLine)
                val offsets = editor.layout.getCharLayoutOffset(line, column)
                assertEquals(rowFraction, (editor.offsetY - (offsets[0] - editor.rowHeight)) / editor.rowHeight, .06f)
                assertEquals(horizontalEm, (editor.offsetX - offsets[1]) / editor.textSizePx, .06f)
                assertEquals(left, editor.cursor.left); assertEquals(right, editor.cursor.right)
                assertEquals(beforeText, editor.text.toString())
                assertEquals(revision, state.document("install.sh").changeVersion)
                assertTrue(state.document("install.sh").dirty)
            }
        }
        compose.runOnIdle { editor.undo(); assertEquals(content, editor.text.toString()) }
    }

    @Test
    @Config(shadows = [SoraHostIteratorShadow::class])
    fun resetWrappedZoomRetainsTheReadingRowAcrossTabSwitch() {
        mount((0 until 120).joinToString("\n") { "row $it ${"word ".repeat(40)}" }, wordwrap = true)
        settleEditorLayout()
        val editor = editors().single()
        compose.runOnIdle { editor.setSelection(0, 2); editor.setTextSize(25f) }
        settleEditorLayout()
        var anchorLine = 0
        var anchorColumn = 0
        compose.runOnIdle {
            editor.scroller.startScroll(0, 0, 0, editor.rowHeight * 80 + editor.rowHeight / 4, 0)
            editor.scroller.abortAnimation()
            val point = editor.getPointPosition(editor.measureTextRegionOffset(), editor.offsetY.toFloat())
            anchorLine = io.github.rosemoe.sora.util.IntPair.getFirst(point)
            anchorColumn = io.github.rosemoe.sora.util.IntPair.getSecond(point)
        }
        compose.onNodeWithContentDescription("Reset editor zoom").performClick()
        compose.onNodeWithText("README.md").performClick()
        compose.onNodeWithText("install.sh").performClick()
        settleEditorLayout()
        compose.runOnIdle {
            val restored = drawEditorFrame()
            assertEquals(DefaultEditorTextSizeSp, restored.captureViewport().textSizeSp, .01f)
            assertEquals(2, restored.cursor.left)
            assertEquals(anchorLine, restored.firstVisibleLine)
            val bottom = restored.layout.getCharLayoutOffset(anchorLine, anchorColumn)[0] - restored.offsetY
            assertTrue("Reading anchor should remain on the top visible row: $bottom", bottom in 0f..restored.rowHeight.toFloat())
            assertFalse(state.document("install.sh").dirty)
        }
    }

    @Test fun resetAtDefaultIsAnExactViewportNoOpAndBridgeClearsWithPane() {
        mount((0 until 200).joinToString("\n") { "line $it ${"wide ".repeat(30)}" })
        settleEditorLayout()
        lateinit var before: EditorViewportSnapshot
        compose.runOnIdle {
            val editor = drawEditorFrame()
            editor.scroller.startScroll(0, 0, 100, editor.rowHeight * 50 + 3, 0)
            editor.scroller.abortAnimation()
            before = editor.captureViewport()
        }
        repeat(3) { compose.onNodeWithContentDescription("Reset editor zoom").performClick() }
        compose.runOnIdle { assertEquals(before, drawEditorFrame().captureViewport()) }
        compose.onNodeWithText("Toggle editor").performClick()
        compose.runOnIdle { assertFalse(bridge.requestResetZoom()) }
    }

    @Test fun resetQueuedDuringReflowRunsWhenTheCurrentEditorIsReady() {
        mount((0 until 100).joinToString("\n") { "line $it" })
        settleEditorLayout()
        val editor = editors().single()
        compose.runOnIdle { editor.setTextSize(24f) }
        settleEditorLayout()
        compose.runOnIdle { editor.setLayoutBusy(true) }
        compose.onNodeWithContentDescription("Reset editor zoom").performClick()
        compose.runOnIdle {
            assertEquals(24f, editor.captureViewport().textSizeSp, .01f)
            editor.setLayoutBusy(false)
        }
        settleEditorLayout()
        compose.runOnIdle { assertEquals(DefaultEditorTextSizeSp, editor.captureViewport().textSizeSp, .01f) }
    }

}

/**
 * Robolectric uses the host JDK line breaker. Sora's iterator returns the first character
 * instead of DONE when previous() reaches the start, making that JDK breaker loop forever.
 * Supply the CharacterIterator boundary contract only for this host word-wrap test. The
 * real Sora breaker, layout, reflow, editor and reset action are otherwise unchanged.
 */
@org.robolectric.annotation.Implements(
    value = io.github.rosemoe.sora.text.CharSequenceIterator::class,
    isInAndroidSdk = false,
)
class SoraHostIteratorShadow {
    @org.robolectric.annotation.RealObject
    private lateinit var iterator: io.github.rosemoe.sora.text.CharSequenceIterator

    @org.robolectric.annotation.Implementation
    fun previous(): Char = if (iterator.index <= iterator.beginIndex) java.text.CharacterIterator.DONE
        else iterator.setIndex(iterator.index - 1)

    @org.robolectric.annotation.Implementation
    fun next(): Char = iterator.setIndex((iterator.index + 1).coerceAtMost(iterator.endIndex))
}
