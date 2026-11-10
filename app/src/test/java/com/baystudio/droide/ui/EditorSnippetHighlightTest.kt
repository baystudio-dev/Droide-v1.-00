package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.baystudio.droide.core.*
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSnippetHighlightTest {
    private val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "fixture")

    @Test fun allBuiltinThemesUseTranslucentSnippetRolesWithoutChangingSyntaxOrSelection() {
        val root = Files.createTempDirectory("snippet-themes-").toFile()
        try {
            val themes = ThemeManager(root)
            for (name in listOf("droide-dark", "droide-light", "matrix", "tokyonight")) {
                themes.select(name)
                val snapshot = themes.snapshot()
                val scheme = DroideEditorColorScheme(snapshot)
                for (role in listOf(EditorColorScheme.SNIPPET_BACKGROUND_EDITING,
                    EditorColorScheme.SNIPPET_BACKGROUND_RELATED, EditorColorScheme.SNIPPET_BACKGROUND_INACTIVE)) {
                    val alpha = scheme.getColor(role) ushr 24
                    assertTrue("$name must not erase glyphs with role $role", alpha in 1..0x28)
                }
                assertEquals(snapshot.editor.foreground, scheme.getColor(EditorColorScheme.TEXT_NORMAL))
                assertEquals(snapshot.editor.keyword, scheme.getColor(EditorColorScheme.KEYWORD))
                assertEquals(snapshot.editor.selection, scheme.getColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND))
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun darkSnippetGlyphsKeepContrastWhereTheOldOpaqueOverlayMadeThemUnreadable() = verifyGlyphs("droide-dark")
    @Test fun lightSnippetGlyphsRemainVisibleAndTabstopsStillInsertAndMirrorText() = verifyGlyphs("droide-light")

    private fun verifyGlyphs(theme: String) {
        val root = Files.createTempDirectory("snippet-pixels-").toFile()
        val themes = ThemeManager(root).apply { select(theme) }
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "kotlin", codeStyle = style)
        try {
            editor.applyDroideTheme(themes.snapshot())
            editor.setLineNumberEnabled(false)
            editor.setText("")
            editor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 480, 220)
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!editor.isEditable && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(2)
            }
            assertTrue("Native layout must be ready before snippet insertion", editor.isEditable)
            val snippet = CodeSnippetParser.parse("when (\${1:value}) {\n    \$1 -> \${2:result}\n    else -> \$0\n}")
            editor.snippetController.startSnippet(0, snippet)
            ShadowLooper.idleMainLooper()
            assertTrue(editor.snippetController.isInSnippet())
            val spans = MappedSpans.Builder(editor.text.lineCount)
            repeat(editor.text.lineCount) { spans.add(it, Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))) }
            spans.determine(editor.text.lineCount - 1)
            editor.setStyles(Styles(spans.build()))
            val stop = requireNotNull(editor.snippetController.getEditingTabStop())
            val start = editor.text.indexer.getCharPosition(stop.startIndex)
            val end = editor.text.indexer.getCharPosition(stop.endIndex)
            val left = editor.getCharOffsetX(start.line, start.column).toInt() + 3
            val right = editor.getCharOffsetX(end.line, end.column).toInt() - 3
            val bottom = editor.getCharOffsetY(start.line, start.column).toInt()
            val top = bottom - editor.rowHeight + 4
            val scheme = editor.colorScheme
            val fixed = scheme.getColor(EditorColorScheme.SNIPPET_BACKGROUND_EDITING)
            fun render(color: Int, name: String): Double {
                scheme.setColor(EditorColorScheme.SNIPPET_BACKGROUND_EDITING, color)
                val bitmap = Bitmap.createBitmap(480, 220, Bitmap.Config.ARGB_8888)
                try {
                    editor.draw(Canvas(bitmap))
                    var low = 1.0
                    var high = 0.0
                    for (y in top until bottom - 4) for (x in left until right) {
                        val luminance = ThemeContrastPolicy.luminance(bitmap.getPixel(x, y))
                        low = minOf(low, luminance); high = maxOf(high, luminance)
                    }
                    System.getProperty("droide.editor.proofDir")?.let { directory ->
                        val file = java.io.File(directory, "$theme-$name.png").apply { parentFile.mkdirs() }
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    return (high + .05) / (low + .05)
                } finally { bitmap.recycle() }
            }
            val before = render(0xFFCCCCCC.toInt(), "old-opaque-overlay")
            val readable = render(fixed, "fixed-translucent-overlay")
            if (themes.snapshot().editor.isDark) {
                assertTrue("The old opaque placeholder should fail normal-text contrast: $before", before < 4.5)
            }
            assertTrue("Native placeholder glyphs need readable contrast: $readable", readable >= 4.5)
            System.getProperty("droide.editor.proofDir")?.let { directory ->
                java.io.File(directory, "$theme-contrast.txt").writeText("before=$before\nafter=$readable\n")
            }
            assertEquals("value", editor.text.subSequence(stop.startIndex, stop.endIndex).toString())
            editor.commitText("answer", false)
            assertTrue(editor.text.toString().contains("when (answer)"))
            assertTrue(editor.text.toString().contains("answer -> result"))
            editor.snippetController.shiftToNextTabStop()
            assertTrue(editor.snippetController.isInSnippet())
            editor.commitText("output", false)
            assertTrue(editor.text.toString().contains("answer -> output"))
        } finally { editor.release(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
