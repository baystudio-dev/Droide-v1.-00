package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.baystudio.droide.core.*
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual Sora/Android integration. Authored, not executed by the dependency-free host palette suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorCodeStyleTest {
    private fun editor() = DroideCodeEditor(RuntimeEnvironment.getApplication(), "kotlin",
        codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
    private fun fixture(block: (ThemeManager, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("r65-theme-").toFile()
        try { block(ThemeManager(root), root) } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun textAndGutterUseCachedPlatformMonospace() {
        val e = editor(); try { assertEquals(Typeface.MONOSPACE, e.typefaceText); assertEquals(Typeface.MONOSPACE, e.typefaceLineNumber) } finally { e.release() }
    }
    @Test fun monospaceBasicLatinAdvancesAreEqual() {
        val e = editor(); try { val p = Paint().apply { typeface = e.typefaceText; textSize = 18f }; assertEquals(p.measureText("iiii"), p.measureText("WWWW"), 0.01f) } finally { e.release() }
    }
    @Test fun repeatedThemeDoesNotReplaceContentSelectionOrUserMetrics() = fixture { manager, _ ->
        val e = editor(); try {
            e.setText("val name = \"你好 🙂 é العربية\"\n"); e.setSelection(0, 4); e.setTextSize(21f); e.setWordwrap(true, true, false)
            val text = e.text.toString(); val size = e.textSizePx; val cursor = e.cursor.left; val padding = listOf(e.paddingLeft, e.paddingTop, e.paddingRight, e.paddingBottom)
            val theme = manager.snapshot(); e.applyDroideTheme(theme); val scheme = e.colorScheme
            repeat(10) { e.applyDroideTheme(theme) }
            assertSame(scheme, e.colorScheme); assertEquals(text, e.text.toString()); assertEquals(cursor, e.cursor.left)
            assertEquals(size, e.textSizePx, 0f); assertTrue(e.isWordwrap); assertEquals(padding, listOf(e.paddingLeft, e.paddingTop, e.paddingRight, e.paddingBottom))
        } finally { e.release() }
    }
    @Test fun lightSelectionStaysLightAndRestoresThroughManager() = fixture { manager, root ->
        manager.select("droide-light"); val theme = ThemeManager(root).snapshot(); assertFalse(theme.editor.isDark); assertEquals(0xFFF6F8FA.toInt(), theme.editor.background)
        val scheme = DroideEditorColorScheme(theme); assertEquals(theme.editor.keyword, scheme.getColor(EditorColorScheme.KEYWORD))
    }
    @Test fun customEditorSurfaceDoesNotRecolorPopupUi() = fixture { manager, root ->
        root.resolve(".droide/themes").mkdirs()
        root.resolve(".droide/themes/custom.json").writeText("""{"name":"custom-r65","dark":"#FFFFFF","light":"#111111","colors":{"editor.background":"#001122","editor.foreground":"#FFFFFF","editorWidget.background":"#EEEEEE","editorLineNumber.foreground":"#ABCDEF"}}""")
        manager.select("custom-r65"); val theme = manager.snapshot(); val scheme = DroideEditorColorScheme(theme)
        assertEquals(0xFF001122.toInt(), theme.editor.background); assertEquals(0xFFFFFFFF.toInt(), theme.editor.foreground); assertEquals(0xFFABCDEF.toInt(), theme.editor.lineNumber)
        assertEquals(theme.ui.surface2.toArgb(), scheme.getColor(EditorColorScheme.COMPLETION_WND_BACKGROUND)); assertEquals(theme.ui.text.toArgb(), scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))
        assertNotEquals(theme.editor.foreground, scheme.getColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY))
    }
    @Test fun soraSyntaxRolesUseSharedEditorPalette() = fixture { manager, _ ->
        val t = manager.snapshot(); val p = t.editor; val s = DroideEditorColorScheme(t)
        mapOf(EditorColorScheme.KEYWORD to p.keyword, EditorColorScheme.COMMENT to p.comment, EditorColorScheme.LITERAL to p.literal, EditorColorScheme.OPERATOR to p.operator, EditorColorScheme.FUNCTION_NAME to p.function, EditorColorScheme.IDENTIFIER_NAME to p.type, EditorColorScheme.IDENTIFIER_VAR to p.variable, EditorColorScheme.ANNOTATION to p.type, EditorColorScheme.HTML_TAG to p.keyword, EditorColorScheme.ATTRIBUTE_NAME to p.function, EditorColorScheme.ATTRIBUTE_VALUE to p.literal).forEach { (role, color) -> assertEquals(color, s.getColor(role)) }
    }
    @Test fun mixedUnicodeContentSurvivesLayoutAndDraw() = fixture { manager, _ ->
        val e = editor(); try {
            val text = "val café = \"你好 🙂 é العربية\"\n\t// 日本語\n"; e.setText(text); e.applyDroideTheme(manager.snapshot())
            e.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY)); e.layout(0, 0, 400, 240)
            e.draw(Canvas(Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888))); assertEquals(text, e.text.toString()); assertEquals(Typeface.MONOSPACE, e.typefaceText)
        } finally { e.release() }
    }
}
