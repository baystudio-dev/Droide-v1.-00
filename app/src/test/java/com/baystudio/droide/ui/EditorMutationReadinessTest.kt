package com.baystudio.droide.ui

import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.baystudio.droide.core.*
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
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
class EditorMutationReadinessTest {
    private val style = CodeStyleProfile(IndentStyle.SPACES, 4, 4, 8, "fixture")

    private fun awaitReady(editor: DroideCodeEditor) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!editor.isEditable && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(2)
        }
        assertTrue("Native layout must finish before the controlled busy interval", editor.isEditable)
    }

    private fun withEditor(body: (DroideCodeEditor) -> Unit) {
        val editor = DroideCodeEditor(RuntimeEnvironment.getApplication(), "kotlin", codeStyle = style)
        try {
            editor.setText("old")
            awaitReady(editor)
            editor.setSelection(0, 3)
            body(editor)
        } finally {
            editor.release()
            ShadowLooper.idleMainLooper()
        }
    }

    private fun rejectsWhileBusy(action: (DroideCodeEditor) -> Unit, expected: String) = withEditor { editor ->
        editor.setLayoutBusy(true)
        action(editor)
        assertEquals("A busy editor must keep the entire original text", "old", editor.text.toString())
        editor.setLayoutBusy(false)
        assertEquals("Rejected input must not be replayed later", "old", editor.text.toString())
        action(editor)
        assertEquals("Explicit input works again after layout readiness", expected, editor.text.toString())
    }

    @Test fun smartCommitWaitsForLayoutReadiness() = rejectsWhileBusy({ it.commitText("!", false) }, "old!")

    @Test fun rawCommitWaitsForLayoutReadiness() = rejectsWhileBusy({ it.commitText("!", false, false) }, "old!")

    @Test fun insertionWaitsForLayoutReadiness() = rejectsWhileBusy({ it.insertText("!", 1) }, "old!")

    @Test fun deletionWaitsForLayoutReadiness() = rejectsWhileBusy({ it.deleteText() }, "ol")

    @Test fun indentWaitsForLayoutReadiness() = rejectsWhileBusy({ it.indentOrCommitTab() }, "old    ")

    @Test fun undoAndRedoDoNotChangeContentWhileLayoutIsBusy() = withEditor { editor ->
        editor.commitText("!", false)
        assertTrue(editor.canUndo())
        editor.setLayoutBusy(true)
        editor.undo()
        assertEquals("old!", editor.text.toString())
        editor.setLayoutBusy(false)
        editor.undo()
        assertEquals("old", editor.text.toString())
        assertTrue(editor.canRedo())
        editor.setLayoutBusy(true)
        editor.redo()
        assertEquals("old", editor.text.toString())
        editor.setLayoutBusy(false)
        editor.redo()
        assertEquals("old!", editor.text.toString())
    }

    private fun simpleCompletion() = DroideGuardedCompletionItem(SimpleCompletionItem("older", "", 3, "older"))

    @Test fun completionByPositionDoesNotReplaceItsPrefixWhileBusy() = rejectsWhileBusy({
        simpleCompletion().performCompletion(it, it.text, CharPosition(0, 3, 3))
    }, "older")

    @Test fun completionByCoordinatesDoesNotReplaceItsPrefixWhileBusy() = rejectsWhileBusy({
        simpleCompletion().performCompletion(it, it.text, 0, 3)
    }, "older")

    @Test fun snippetDoesNotDeletePrefixOrOpenTabstopsWhileBusy() = withEditor { editor ->
        val snippet = CodeSnippetParser.parse("\${1:name}(\$0)")
        val completion = DroideGuardedCompletionItem(SimpleSnippetCompletionItem(
            "call", "", SnippetDescription(3, snippet, true)))
        editor.setLayoutBusy(true)
        completion.performCompletion(editor, editor.text, CharPosition(0, 3, 3))
        assertEquals("old", editor.text.toString())
        assertFalse(editor.snippetController.isInSnippet())
        editor.setLayoutBusy(false)
        completion.performCompletion(editor, editor.text, CharPosition(0, 3, 3))
        assertEquals("name()", editor.text.toString())
        assertTrue(editor.snippetController.isInSnippet())
    }

    @Test fun readOnlyCompletionAndCommandsPreserveText() = withEditor { editor ->
        editor.setEditable(false)
        simpleCompletion().performCompletion(editor, editor.text, 0, 3)
        editor.commitText("!", false)
        editor.deleteText()
        editor.duplicateLine()
        assertEquals("old", editor.text.toString())
        editor.setEditable(true)
        simpleCompletion().performCompletion(editor, editor.text, 0, 3)
        assertEquals("older", editor.text.toString())
    }

    @Test fun documentLoadingStillReplacesContentWhileLayoutIsRebuilding() = withEditor { editor ->
        editor.setLayoutBusy(true)
        editor.setText("loaded document")
        awaitReady(editor)
        assertEquals("loaded document", editor.text.toString())
    }

    @Test fun languageServerCompletionReportsBusyStateAndCanBeRequestedAgain() = withEditor { editor ->
        val rejected = mutableListOf<String>()
        val provider = object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = ""
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
            override suspend fun completionItems(path: String, line: Int, col: Int) =
                listOf(LspCompletionItem("olderNative", "olderNative",
                    editStartLine = 1, editStartColumn = 1, editEndLine = 1, editEndColumn = 4))
        }
        val language = DroideCompletionLanguage("main.kt", "kotlin", provider, style, rejected::add)
        val publisher = CompletionPublisher(Handler(Looper.getMainLooper()), {}, Language.INTERRUPTION_LEVEL_STRONG)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                language.requireAutoComplete(ContentReference(editor.text), CharPosition(0, 3, 3), publisher, Bundle())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }, "readiness-completion")
        try {
            worker.start()
            val deadline = System.nanoTime() + 4_000_000_000L
            while (worker.isAlive && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper()
                worker.join(2)
            }
            assertFalse("Completion worker must finish", worker.isAlive)
            failure.get()?.let { throw AssertionError("Completion failed", it) }
            ShadowLooper.idleMainLooper()
            val item: CompletionItem = publisher.items.single { it.label.toString() == "olderNative" }
            editor.setLayoutBusy(true)
            item.performCompletion(editor, editor.text, 0, 3)
            assertEquals("old", editor.text.toString())
            assertEquals(listOf("Editor is not ready for edits. Request completion again."), rejected)
            editor.setLayoutBusy(false)
            item.performCompletion(editor, editor.text, 0, 3)
            assertEquals("olderNative", editor.text.toString())
        } finally {
            worker.interrupt()
            worker.join(1_000)
            assertFalse("No completion worker may survive the fixture", worker.isAlive)
        }
    }
}
