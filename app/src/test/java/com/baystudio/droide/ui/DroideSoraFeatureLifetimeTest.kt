package com.baystudio.droide.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.IndentStyle
import com.baystudio.droide.core.LspDiagnostic
import com.baystudio.droide.core.LspInlayHint
import com.baystudio.droide.core.LspManager
import io.github.rosemoe.sora.lang.styling.inlayHint.TextInlayHint
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.layout.Layout
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DroideSoraFeatureLifetimeTest {
    @get:Rule val compose = createComposeRule()
    private val root = Files.createTempDirectory("sora-feature-lifetime").toFile()
    private val files = FileRepository(root)
    private val views = mutableListOf<DroideCodeEditor>()
    private val pending = mutableListOf<Continuation<List<LspInlayHint>>>()
    private val lsp = object : LspManager {
        override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
        override suspend fun diagnose(relPath: String) = ""
        override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        // Deliberately model a transport that reports completion despite owner cancellation.
        override suspend fun inlayHints(path: String, currentText: String?) =
            suspendCoroutine<List<LspInlayHint>> { continuation -> pending += continuation }
    }
    private fun view(): DroideCodeEditor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "kotlin",
        codeStyle = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "test")).also {
        it.setText("val value = 1")
        views += it
    }
    private fun document(): EditorDocument {
        root.resolve("Main.kt").writeText("val value = 1")
        return EditorDocument("Main.kt").also { runBlocking { it.ensureLoaded(files) } }
    }
    private fun complete(continuation: Continuation<List<LspInlayHint>>, label: String) {
        pending.remove(continuation)
        continuation.resume(listOf(LspInlayHint(1, 4, label)))
    }
    private fun advanceToRequest() {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @After fun cleanUp() {
        for (continuation in pending.toList()) complete(continuation, "cleanup")
        for (editor in views) if (!editor.isReleased) editor.release()
        root.deleteRecursively()
    }

    @Test fun releasedEditorDoesNotReattachDiagnosticsOrInlayResources() {
        val editor = view()
        editor.release()
        editor.applyDroideDiagnostics(listOf(LspDiagnostic(1, 1, 1, 4, 1, "error", "test")))
        editor.applyDroideInlayHints(listOf(LspInlayHint(1, 4, "type")))
        assertNull(editor.diagnostics)
        assertNull(editor.inlayHints)
    }

    @Test fun cancelledOldViewRequestCannotDeliverToReleasedOrReplacementEditor() {
        val document = document()
        val original = view()
        val current = mutableStateOf<DroideCodeEditor?>(original)
        compose.mainClock.autoAdvance = false
        compose.setContent { BindDroideSoraFeatures(current.value, document, lsp, files, "Main.kt") }
        advanceToRequest()
        lateinit var originalRequest: Continuation<List<LspInlayHint>>
        lateinit var replacement: DroideCodeEditor
        compose.runOnIdle {
            originalRequest = pending.single()
            original.release()
            replacement = view()
            current.value = replacement
        }
        compose.runOnIdle { complete(originalRequest, "stale") }
        compose.runOnIdle {
            assertNull(original.inlayHints)
            assertNull(replacement.inlayHints)
        }
        advanceToRequest()
        compose.runOnIdle { complete(pending.single(), "fresh") }
        compose.runOnIdle {
            val hints = requireNotNull(replacement.inlayHints) { "The replacement editor must receive its current response" }
            assertEquals("fresh", (hints.getForLine(0).single() as TextInlayHint).text)
        }
    }

    @Test fun changedDocumentRejectsOldInlayResponseAndAcceptsCurrentResponse() {
        val document = document()
        val editor = view()
        compose.mainClock.autoAdvance = false
        compose.setContent { BindDroideSoraFeatures(editor, document, lsp, files, "Main.kt") }
        advanceToRequest()
        lateinit var oldRequest: Continuation<List<LspInlayHint>>
        compose.runOnIdle {
            oldRequest = pending.single()
            document.capture("val value = 12345")
            editor.setText(document.content)
        }
        compose.runOnIdle { complete(oldRequest, "stale") }
        compose.runOnIdle { assertNull(editor.inlayHints) }
        advanceToRequest()
        compose.runOnIdle { complete(pending.single(), "fresh") }
        compose.runOnIdle {
            val hints = requireNotNull(editor.inlayHints) { "The edited document must receive its current response" }
            assertEquals("fresh", (hints.getForLine(0).single() as TextInlayHint).text)
        }
    }

    @Test fun recoverableLayoutErrorInFeatureDeliveryReportsSurfaceFailure() {
        val document = document()
        val editor = view()
        val failures = mutableListOf<Throwable>()
        editor.onEditorFailure = { failures += it }
        val layoutField = CodeEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
        val realLayout = layoutField.get(editor)
        val rejectedLayout = Proxy.newProxyInstance(Layout::class.java.classLoader, arrayOf(Layout::class.java)) { _, method, args ->
            if (method.name == "invalidateLines") throw IllegalStateException("Feature layout rejected")
            method.invoke(realLayout, *(args ?: emptyArray()))
        }
        layoutField.set(editor, rejectedLayout)
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent { BindDroideSoraFeatures(editor, document, lsp, files, "Main.kt") }
            advanceToRequest()
            compose.runOnIdle { complete(pending.single(), "type") }
            compose.runOnIdle {
                ShadowLooper.idleMainLooper()
                assertEquals("Feature layout rejected", failures.single().message)
                assertFalse(editor.acceptsFeatureUpdates)
            }
        } finally { layoutField.set(editor, realLayout) }
    }
}
