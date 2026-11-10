package com.baystudio.droide.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.IndentStyle
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRenderingTest {
    @Test fun postedLayoutFailureIsReportedAndLaterEditorCallbacksStop() {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        var staleCallbackRan = false
        try {
            editor.postInLifecycle { throw IllegalStateException("Layout callback rejected") }
            editor.postDelayedInLifecycle({ staleCallbackRan = true }, 10)
            ShadowLooper.idleMainLooper()
            assertEquals("Layout callback rejected", failures.single().message)
            ShadowLooper.idleMainLooper(20, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertFalse(staleCallbackRan)
        } finally { editor.release() }
    }

    @Test fun rendererFailureReportsOnceAndRestoresCanvasForOtherPanels() {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "plaintext",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test"))
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        try {
            editor.setText("unsaved text")
            editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 400, 220)
            val normal = MappedSpans.Builder(1).apply {
                add(0, Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)))
                determine(0)
            }.build()
            editor.setStyles(Styles(object : Spans by normal {
                override fun read(): Spans.Reader = throw IllegalStateException("Broken render spans")
            }))
            val canvas = Canvas(Bitmap.createBitmap(400, 220, Bitmap.Config.ARGB_8888))
            val saves = canvas.saveCount
            editor.draw(canvas)
            assertEquals(saves, canvas.saveCount)
            ShadowLooper.idleMainLooper()
            assertEquals("Broken render spans", failures.single().message)
            editor.draw(canvas)
            ShadowLooper.idleMainLooper()
            assertEquals(1, failures.size)
            assertEquals("unsaved text", editor.text.toString())
        } finally { editor.release() }
    }

    @Test fun horizontallyScrolledStickyLinesStayInsideEditorBounds() {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "shell",
            codeStyle = CodeStyleProfile(IndentStyle.SPACES, 2, 2, 4, "test"))
        try {
            editor.setText((0..30).joinToString("\n") { if (it == 0) "if ${"long_shell_code ".repeat(40)}; then" else "  echo line$it" })
            editor.setLineNumberEnabled(true)
            editor.setPinLineNumber(true)
            editor.props.stickyScroll = true
            val spans = MappedSpans.Builder(31)
            repeat(31) { spans.add(it, Span.obtain(0, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL))) }
            spans.determine(30)
            val styles = Styles(spans.build(), true)
            styles.addCodeBlock(CodeBlock().apply { startLine = 0; startColumn = 0; endLine = 30; endColumn = 0 })
            styles.finishBuilding()
            editor.setStyles(styles)
            editor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.EXACTLY))
            editor.layout(0, 0, 400, 220)
            editor.scroller.startScroll(0, 0, 320, editor.rowHeight * 8, 0)
            editor.scroller.abortAnimation()
            val bitmap = Bitmap.createBitmap(700, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            val canvas = Canvas(bitmap)

            canvas.translate(150f - editor.scrollX, 30f - editor.scrollY)
            editor.draw(canvas)
            var outsidePixels = 0
            var insidePixels = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != Color.MAGENTA) {
                    if (x in 150 until 550 && y in 30 until 250) insidePixels++ else outsidePixels++
                }
            }
            assertTrue("Editor must draw actual content", insidePixels > 0)
            assertNotEquals("Scrolling must leave the full viewport visible", Color.MAGENTA, bitmap.getPixel(540, 240))
            assertEquals("Sticky header must not paint over neighboring panels", 0, outsidePixels)
        } finally { editor.release() }
    }
}
