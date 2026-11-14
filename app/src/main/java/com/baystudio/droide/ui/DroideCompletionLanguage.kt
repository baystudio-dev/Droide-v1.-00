package com.baystudio.droide.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.baystudio.droide.core.CodeStyleProfile
import com.baystudio.droide.core.CompletionCandidate
import com.baystudio.droide.core.LspCompletionCaret
import com.baystudio.droide.core.LspCompletionItem
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.LspNativeSnippetPolicy
import com.baystudio.droide.core.LspGraphqlSnippetPolicy
import com.baystudio.droide.core.LspCompletionPolicy
import com.baystudio.droide.core.LspCMakeCompletionContext
import com.baystudio.droide.core.LspPowerShellCompletionContext
import com.baystudio.droide.core.LspProjectAnalysisNotReadyException
import com.baystudio.droide.core.LspPrismaCompletionContext
import com.baystudio.droide.core.LspDocumentSupportPhase
import com.baystudio.droide.core.LspDocumentSupportUnavailableException
import com.baystudio.droide.core.ProfessionalCompletion
import com.baystudio.droide.core.ProfessionalSnippets
import com.baystudio.droide.core.ProfessionalSmartTyping
import com.baystudio.droide.core.TextEditApplier
import com.baystudio.droide.core.TextRangeEdit
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.lang.completion.SimpleSnippetCompletionItem
import io.github.rosemoe.sora.lang.completion.SnippetDescription
import io.github.rosemoe.sora.lang.completion.snippet.PlaceholderItem
import io.github.rosemoe.sora.lang.completion.snippet.PlainTextItem
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CancellationException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull








/** Captured UI binding; validate only on Main without recapturing a replacement view. */
internal class NativeCompletionEditorCapture(val owner: CodeEditor, val isCurrent: () -> Boolean)

/** Bounds completion's wait for Main; cancellation prevents a queued read from running late. */
internal fun <T> completionUiRead(timeoutMillis: Long = 500L, read: () -> T): T? {
    require(timeoutMillis > 0)
    if (Looper.myLooper() == Looper.getMainLooper()) return read()
    val handler = Handler(Looper.getMainLooper())
    val task = FutureTask(Callable { read() })
    if (!handler.post(task)) return null
    return try {
        task.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (failure: ExecutionException) {
        throw (failure.cause ?: failure)
    } finally {
        task.cancel(false)
        handler.removeCallbacks(task)
    }
}

/** View access and binding callbacks share Main; the full document is copied by the worker. */
internal fun captureCurrentNativeCompletionEditor(
    expectedContent: Content,
    capture: (() -> NativeCompletionEditorCapture?)?,
): NativeCompletionEditorCapture? {
    val captureCurrent = capture ?: return null
    return completionUiRead {
        captureCurrent()?.takeIf { nativeCompletionEditorMatches(it, expectedContent) }
    }
}

private fun nativeCompletionEditorMatches(captured: NativeCompletionEditorCapture?, expectedContent: Content): Boolean {
    check(Looper.myLooper() == Looper.getMainLooper())
    return captured != null && captured.owner.text === expectedContent &&
        runCatching { captured.isCurrent() }.getOrDefault(false)
}

internal class DroideCompletionLanguage(
    private val path: String,
    private val languageId: String,
    private val lsp: LspManager,
    private val codeStyle: CodeStyleProfile,
    private val onCompletionRejected: (String) -> Unit = {},
    private val captureNativeCompletionEditor: (() -> NativeCompletionEditorCapture?)? = null,
) : EmptyLanguage() {

    private val symbolPairs: SymbolPairMatch = droideSymbolPairs(languageId)
    private val newlineHandlers: Array<NewlineHandler> = arrayOf(DroideSmartNewlineHandler(languageId, codeStyle))

    override fun getSymbolPairs(): SymbolPairMatch = symbolPairs

    override fun getNewlineHandlers(): Array<NewlineHandler> = newlineHandlers

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int =
        ProfessionalSmartTyping.indentAdvance(
            languageId,
            content.getLine(line).substring(0, column),
            codeStyle.indentSize,
            codeStyle.continuationIndent,
        )

    override fun useTab(): Boolean = codeStyle.useTabs

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        publisher.checkCancelled()
        val lineText = content.getLine(position.line)
        val prefix = ProfessionalCompletion.prefixFromLine(languageId, lineText, position.column)
        val triggerCharacter = ProfessionalCompletion.triggerCharacter(languageId, lineText, position.column)
        val localSample = nearbyText(content, position.line)
        val local = if (lsp.completionPolicy(path) in setOf(LspCompletionPolicy.VUE_NATIVE, LspCompletionPolicy.CSHARP_NATIVE, LspCompletionPolicy.TOML_NATIVE, LspCompletionPolicy.GRAPHQL_NATIVE, LspCompletionPolicy.ELP_NATIVE)) emptyList() else ProfessionalCompletion.localCandidates(
            languageId = languageId,
            documentText = localSample,
            cursorIndex = localSample.length / 2,
            prefix = prefix,
            triggerCharacter = triggerCharacter,
            codeStyle = codeStyle,
        )

        publisher.setUpdateThreshold(Int.MAX_VALUE)
        publisher.setComparator(Comparator { left, right ->
            (left.sortText ?: "9-${left.label}").compareTo(right.sortText ?: "9-${right.label}", ignoreCase = true)
        })
        local.forEachIndexed { index, candidate ->
            publisher.addItem(candidate.toSora(prefix.length, "1-${index.toString().padStart(3, '0')}-${candidate.label}"))
        }
        if (local.isNotEmpty()) publisher.updateList(true)

        // A non-null support seam identifies the built-in route, not a custom Prisma server.
        // Sora supplies no manual/automatic distinction here: every actual Prisma callback may query.
        val policy = lsp.completionPolicy(path)
        val powershell = policy == LspCompletionPolicy.POWERSHELL_NATIVE
        val cmake = policy == LspCompletionPolicy.CMAKE_NATIVE
        val vue = policy == LspCompletionPolicy.VUE_NATIVE
        val csharp = policy == LspCompletionPolicy.CSHARP_NATIVE
        val toml = policy == LspCompletionPolicy.TOML_NATIVE
        val graphql = policy == LspCompletionPolicy.GRAPHQL_NATIVE
        val elp = policy == LspCompletionPolicy.ELP_NATIVE
        val nativeCaret = powershell || cmake || vue || csharp || toml || graphql || elp
        val prisma = !nativeCaret && lsp.documentSupportStatus(path) != null
        // Only actual provider callbacks query. No synthetic whitespace event or
        // incomplete-list cache; the cold policy does not grant runspace authority.
        if (!nativeCaret && !prisma && !ProfessionalCompletion.shouldQueryLsp(languageId, prefix, triggerCharacter)) return
        publisher.checkCancelled()
        try {
            Thread.sleep(ProfessionalCompletion.LSP_DEBOUNCE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            publisher.checkCancelled()
            return
        }
        publisher.checkCancelled()

        

        val capturedVersion = content.getDocumentVersion()
        val currentText = content.reference.toString()
        if (content.getDocumentVersion() != capturedVersion) return
        val support = if (nativeCaret) null else lsp.documentSupportStatus(path, currentText)
        if (support?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8 ||
            support?.phase == LspDocumentSupportPhase.CLOSED) {
            onCompletionRejected(support.message)
            return
        }
        // Native profiles capture source and the actual current UTF-16 caret AFTER
        // debounce. The original callback position can have moved meanwhile.
        val capturedCaret = if (nativeCaret) completionUiRead {
            val source = content.reference
            val cursor = source.cursor
            if (source.documentVersion != capturedVersion || cursor.isSelected) null
            else LspCompletionCaret(cursor.leftLine + 1, cursor.leftColumn + 1)
        } else null
        if (nativeCaret && capturedCaret == null) { publisher.checkCancelled(); return }
        val queryLine = capturedCaret?.line ?: position.line + 1
        val queryColumn = capturedCaret?.column ?: position.column + 1
        val nativeContext = if (powershell) LspPowerShellCompletionContext.capture(currentText, queryLine, queryColumn) else null
        if (powershell && nativeContext == null) return
        val cmakeContext = if (cmake) LspCMakeCompletionContext.capture(currentText, queryLine, queryColumn) else null
        if (cmake && cmakeContext == null) return
        val csharpContext = if (csharp) com.baystudio.droide.core.LspCSharpCompletionContext.capture(currentText, queryLine, queryColumn) else null
        if (csharp && csharpContext == null) { onCompletionRejected("C# completion is unavailable in this lexical context."); return }
        val tomlContext = if (toml) com.baystudio.droide.core.LspTomlCompletionContext.capture(currentText, queryLine, queryColumn) else null
        if (toml && tomlContext == null) { onCompletionRejected("Native TOML completion requires an ASCII-next or logical line-end cursor boundary."); return }
        val graphqlEditor = if (graphql || elp) captureCurrentNativeCompletionEditor(content.reference, captureNativeCompletionEditor) else null
        if ((graphql || elp) && graphqlEditor == null) {
            onCompletionRejected("Native completion requires the current editable view and LF or CRLF line separators.")
            return
        }
        fun nativeCaptureCurrent(): Boolean {
            if (!nativeCaret) return true
            if (lsp.completionPolicy(path) != policy || content.getDocumentVersion() != capturedVersion ||
                content.reference.toString() != currentText) return false
            return completionUiRead {
                val source = content.reference
                val cursor = source.cursor
                source.documentVersion == capturedVersion && !cursor.isSelected &&
                    cursor.leftLine + 1 == queryLine && cursor.leftColumn + 1 == queryColumn &&
                    (!(graphql || elp) || nativeCompletionEditorMatches(graphqlEditor, source))
            } == true
        }
        if (!nativeCaptureCurrent()) return
        val context = if (prisma) LspPrismaCompletionContext.capture(currentText, queryLine, queryColumn) else null
        if (prisma && context == null) return
        val queryPrefix = if (graphql || toml || elp) "" else csharpContext?.prefix ?: cmakeContext?.prefix ?: nativeContext?.prefix ?: context?.prefix ?: prefix
        val queryTrigger = if (elp) elpCapturedTrigger(currentText, queryLine, queryColumn) else if (graphql) null else if (toml) currentText.getOrNull((tomlContext?.offset ?: 0) - 1)?.toString() else if (csharp) csharpContext?.triggerCharacter else if (cmake) cmakeContext?.triggerCharacter else if (powershell) nativeContext?.triggerCharacter else if (prisma) context?.triggerCharacter else if (vue) vueCapturedTrigger(languageId, currentText, queryLine, queryColumn) else triggerCharacter
        val serverItems = try {
            runBlocking {
                val response = withTimeoutOrNull(if (graphql || elp) 16_000L else if (toml) com.baystudio.droide.core.LspTomlCompletionContext.UI_WATCHDOG_MS else if (csharp) com.baystudio.droide.core.LspCSharpCompletionContext.UI_WATCHDOG_MS else if (vue) 9_000L else if (cmake) LspCMakeCompletionContext.UI_WATCHDOG_MS else if (powershell) LspPowerShellCompletionContext.UI_WATCHDOG_MS else ProfessionalCompletion.LSP_TIMEOUT_MS) {
                    lsp.completionItems(
                        path = path,
                        line = queryLine,
                        col = queryColumn,
                        triggerCharacter = queryTrigger,
                        currentText = currentText,
                    )
                }
                if (response == null && nativeCaret) {
                    onCompletionRejected("${if (elp) "Erlang" else if (graphql) "GraphQL" else if (toml) "TOML" else if (csharp) "C#" else if (vue) "Vue" else if (cmake) "CMake" else "PowerShell"} completion is taking too long. Request completion again.")
                }
                response.orEmpty()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            publisher.checkCancelled()
            return
        } catch (_: CancellationException) {
            publisher.cancel()
            publisher.checkCancelled()
            return
        } catch (failure: LspDocumentSupportUnavailableException) {
            onCompletionRejected(failure.status.message)
            emptyList()
        } catch (failure: LspProjectAnalysisNotReadyException) {
            if (nativeCaret) onCompletionRejected(failure.message ?: "${if (elp) "Erlang" else if (graphql) "GraphQL" else if (toml) "TOML" else if (csharp) "C#" else if (vue) "Vue" else if (cmake) "CMake" else "PowerShell"} project analysis is not ready.")
            emptyList()
        } catch (failure: Throwable) {
            if (graphql || toml || elp) onCompletionRejected(failure.message?.take(320) ?: "Native ${if (elp) "Erlang" else if (graphql) "GraphQL" else "TOML"} completion is unavailable.")
            else if (nativeCaret) onCompletionRejected("${if (elp) "Erlang" else if (graphql) "GraphQL" else if (toml) "TOML" else if (csharp) "C#" else if (vue) "Vue" else if (cmake) "CMake" else "PowerShell"} completion is unavailable. Check project analysis status.")
            emptyList()
        }
        publisher.checkCancelled()
        if (!nativeCaptureCurrent()) return
        if (serverItems.isEmpty()) return

        // CMake owns its Unicode prefix and native order. Generic filtering
        // must not hide a documentation-bearing native item such as project.
        val displayed = if (elp) serverItems.filter { item ->
            item.nativeOrigin == "elp" && item.nativeBatch != null && item.nativeItemIdentity != null &&
                item.applicationGuard?.accepts(currentText, LspCompletionCaret(queryLine, queryColumn), false) == true
        }.take(120) else if (graphql) serverItems.filter { item ->
            item.nativeOrigin == "graphql-local-schema" && item.nativeBatch != null && item.nativeItemIdentity != null &&
                item.applicationGuard?.accepts(currentText, LspCompletionCaret(queryLine, queryColumn), false) == true
        } else if (toml) serverItems.filter { item ->
            item.nativeOrigin == "taplo-toml-wasm" && item.nativeBatch != null && item.nativeItemIdentity != null &&
                item.applicationGuard?.accepts(currentText, com.baystudio.droide.core.LspCompletionCaret(queryLine, queryColumn), false) == true
        } else if (csharp) serverItems.filter { item ->
            item.nativeOrigin == "csharp-ls" && item.nativeBatch != null && item.nativeItemIdentity != null &&
                item.applicationGuard?.accepts(currentText, com.baystudio.droide.core.LspCompletionCaret(queryLine, queryColumn), false) == true
        } else if (vue) serverItems.filter { item ->
            item.nativeOrigin != null && item.nativeBatch != null && item.nativeItemIdentity != null &&
                item.applicationGuard?.accepts(currentText, com.baystudio.droide.core.LspCompletionCaret(queryLine, queryColumn), false) == true
        }.take(32) else if (cmake) serverItems.filter {
            it.applicationGuard?.requiredCaret == cmakeContext?.requestCaret && it.applicationGuard != null
        }.take(LspCMakeCompletionContext.MAX_SHOWN_ITEMS) else ProfessionalCompletion.filterLsp(serverItems, queryPrefix)
        // Local word suggestions do not own native edit/import semantics.
        displayed
            .forEachIndexed { index, item ->
                publisher.addItem(
                    DroideLspCompletionItem(
                        item = item,
                        fallbackPrefixLength = queryPrefix.length,
                        onCompletionRejected = onCompletionRejected,
                        nativeEditorCapture = graphqlEditor,
                        sortKey = "0-${index.toString().padStart(3, '0')}-${item.sortText ?: item.label}",
                    )
                )
            }
        publisher.checkCancelled()
        publisher.updateList(true)
    }

    private fun nearbyText(content: ContentReference, cursorLine: Int): String {
        val out = StringBuilder()
        val first = (cursorLine - 100).coerceAtLeast(0)
        val last = (cursorLine + 100).coerceAtMost(content.lineCount - 1)
        for (line in first..last) {
            if (out.length >= ProfessionalCompletion.DOCUMENT_SCAN_CHARS) break
            val remaining = ProfessionalCompletion.DOCUMENT_SCAN_CHARS - out.length
            out.append(content.getLine(line).take(remaining))
            out.append('\n')
        }
        return out.toString()
    }

    private fun CompletionCandidate.toSora(prefixLength: Int, key: String): CompletionItem {
        if (isSnippet) {
            val parsed = runCatching { CodeSnippetParser.parse(ProfessionalSnippets.sanitizeForSora(insertText)) }.getOrNull()
            if (parsed != null && parsed.checkContent()) {
                return DroideGuardedCompletionItem(SimpleSnippetCompletionItem(
                    label,
                    detail,
                    SnippetDescription(prefixLength, parsed, true),
                ).apply { sortText = key })
            }
        }
        val soraKind = mapKind(kind)
        val commitText = if (isSnippet) ProfessionalSnippets.plainTextFallback(insertText) else insertText
        return DroideGuardedCompletionItem(SimpleCompletionItem(label, detail, prefixLength, commitText).apply {
            sortText = key
            kind(soraKind)
        })
    }
}

/** Local simple/snippet items also use raw Content when accepted directly. */
internal class DroideGuardedCompletionItem(private val delegate: CompletionItem) :
    CompletionItem(delegate.label, delegate.desc, delegate.icon) {
    init {
        detail = delegate.detail
        deprecated = delegate.deprecated
        kind = delegate.kind
        prefixLength = delegate.prefixLength
        sortText = delegate.sortText
        filterText = delegate.filterText
    }

    override fun performCompletion(editor: CodeEditor, text: Content, position: CharPosition) {
        if (!editor.isEditable) return
        delegate.performCompletion(editor, text, position)
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        if (!editor.isEditable) return
        delegate.performCompletion(editor, text, line, column)
    }
}

private class DroideLspCompletionItem(
    private val item: LspCompletionItem,
    private val fallbackPrefixLength: Int,
    sortKey: String,
    private val onCompletionRejected: (String) -> Unit = {},
    private val nativeEditorCapture: NativeCompletionEditorCapture? = null,
) : CompletionItem(item.label, listOfNotNull(item.detail, item.documentation).joinToString(" · ").take(500)) {

    init {
        sortText = sortKey
        kind(mapKind(item.kind))
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        if (!editor.isEditable) {
            onCompletionRejected("Editor is not ready for edits. Request completion again.")
            return
        }
        if (item.nativeSnippetPolicy != null && (item.applicationGuard == null ||
                !((item.nativeSnippetPolicy == LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW && item.nativeOrigin == "graphql-local-schema") ||
                    (item.nativeSnippetPolicy == LspNativeSnippetPolicy.ELP_NUMERIC_RAW && item.nativeOrigin == "elp")) || !item.isSnippet)) {
            onCompletionRejected("Native snippet requires its current managed completion owner.")
            return
        }
        if (item.nativeOrigin in setOf("graphql-local-schema", "elp") && (nativeEditorCapture?.owner !== editor ||
                !runCatching { nativeEditorCapture?.isCurrent?.invoke() == true }.getOrDefault(false))) {
            onCompletionRejected("Native completion's editor binding expired.")
            return
        }
        if (item.applicationGuard != null) {
            applyGuardedCompletion(editor, text)
            return
        }
        val primary = explicitPrimaryEdit() ?: TextRangeEdit(
            startLine = line + 1,
            startColumn = (column - fallbackPrefixLength).coerceAtLeast(0) + 1,
            endLine = line + 1,
            endColumn = column + 1,
            newText = item.insertText,
        )
        val original = text.toString()

        if (item.isSnippet) {
            applySnippetCompletion(editor, text, original, primary)
            return
        }

        val edits = (item.additionalTextEdits + primary).take(33)
        
        runCatching { TextEditApplier.apply(original, edits) }.getOrElse {
            applySingle(text, primary)
            return
        }
        edits.sortedWith(editOrder).forEach { applySingle(text, it) }
    }

    /** Manager-authored guarded responses. The default-null path preserves other profiles. */
    private fun applyGuardedCompletion(editor: CodeEditor, text: Content) {
        val guard = item.applicationGuard ?: return
        fun current(): Boolean = editor.isEditable &&
            (item.nativeOrigin !in setOf("graphql-local-schema", "elp") || (nativeEditorCapture?.owner === editor &&
                runCatching { nativeEditorCapture?.isCurrent?.invoke() == true }.getOrDefault(false))) &&
            guard.accepts(text.toString(), LspCompletionCaret(editor.cursor.leftLine + 1,
                editor.cursor.leftColumn + 1), editor.cursor.isSelected)
        fun reject() = onCompletionRejected("Completion expired or could not be applied safely. Request fresh completion.")
        if (!current()) { reject(); return }
        // Clojure and PSES need a native range. Prisma missing-range responses already
        // carry the manager's bounded captured range; never infer from this popup cursor.
        val primary = explicitPrimaryEdit() ?: run { reject(); return }
        val original = guard.capturedText
        val edits = item.additionalTextEdits + primary
        if (item.additionalTextEdits.size > 32 ||
            item.additionalTextEdits.sumOf { it.newText.length.toLong() } > 200_000L ||
            edits.map { it.startLine to it.startColumn }.distinct().size != edits.size) { reject(); return }
        // Prepare every edit, snippet parse and adjusted range before the first mutation.
        val applyPrepared = runCatching<() -> Unit> {
            TextEditApplier.apply(original, edits)
            if (item.isSnippet) {
                val rawGraphql = item.nativeSnippetPolicy == LspNativeSnippetPolicy.GRAPHQL_NUMERIC_RAW
                val rawElp = item.nativeSnippetPolicy == LspNativeSnippetPolicy.ELP_NUMERIC_RAW
                val safeSource = if (rawGraphql) item.insertText.also { LspGraphqlSnippetPolicy.parts(it) }
                    else if (rawElp) item.insertText.also {
                        check(com.baystudio.droide.core.LspElpCompletionContext.validSnippet(it)) { "Invalid Erlang numeric snippet" }
                    } else ProfessionalSnippets.sanitizeForSora(item.insertText)
                val snippet = CodeSnippetParser.parse(safeSource)
                check(snippet != null && snippet.checkContent()) { "Invalid completion snippet" }
                if (rawGraphql) {
                    val parsed = snippet.items.map { part ->
                        when (part) {
                            is PlainTextItem -> LspGraphqlSnippetPolicy.Part(literal = part.text)
                            is PlaceholderItem -> {
                                check(part.definition.transform == null && part.definition.choices.isNullOrEmpty() &&
                                    part.definition.elements.isEmpty()) { "Unsupported GraphQL placeholder" }
                                LspGraphqlSnippetPolicy.Part(tabStop = part.definition.id)
                            }
                            else -> error("Unsupported GraphQL snippet item")
                        }
                    }
                    check(snippet.placeholderDefinitions.all { it.transform == null && it.choices.isNullOrEmpty() && it.elements.isEmpty() })
                    check(snippet.placeholderDefinitions.map { it.id }.toSet() == parsed.mapNotNull { it.tabStop }.toSet())
                    LspGraphqlSnippetPolicy.requireSameParts(safeSource, parsed)
                }
                val plan = ProfessionalSnippets.planSnippetEdits(original, primary, item.additionalTextEdits)
                val afterAdditional = TextEditApplier.apply(original, item.additionalTextEdits)
                val selectedText = afterAdditional.substring(plan.adjustedStart, plan.adjustedEnd)
                val ordered = item.additionalTextEdits.sortedWith(editOrder)
                val apply: () -> Unit = {
                    ordered.forEach { applySingle(text, it) }
                    text.delete(plan.adjustedStart, plan.adjustedEnd)
                    editor.snippetController.startSnippet(plan.adjustedStart, snippet, selectedText)
                }
                apply
            } else {
                val ordered = edits.sortedWith(editOrder)
                val apply: () -> Unit = { ordered.forEach { applySingle(text, it) } }
                apply
            }
        }.getOrElse { reject(); return }
        if (!current()) { reject(); return }
        text.beginBatchEdit()
        try {
            applyPrepared()
        } finally {
            text.endBatchEdit()
        }
    }

    private fun applySnippetCompletion(editor: CodeEditor, text: Content, original: String, primary: TextRangeEdit) {
        val safeSource = runCatching { ProfessionalSnippets.sanitizeForSora(item.insertText) }.getOrElse {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }
        val snippet = runCatching { CodeSnippetParser.parse(safeSource) }.getOrNull()
        if (snippet == null || !snippet.checkContent()) {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }
        val plan = runCatching {
            ProfessionalSnippets.planSnippetEdits(original, primary, item.additionalTextEdits)
        }.getOrElse {
            applySingle(text, primary.copy(newText = ProfessionalSnippets.plainTextFallback(item.insertText)))
            return
        }

        

        item.additionalTextEdits.sortedWith(editOrder).forEach { applySingle(text, it) }
        val selectedText = text.subSequence(plan.adjustedStart, plan.adjustedEnd).toString()
        text.delete(plan.adjustedStart, plan.adjustedEnd)
        editor.snippetController.startSnippet(plan.adjustedStart, snippet, selectedText)
    }

    private val editOrder = compareByDescending<TextRangeEdit> { it.startLine }
        .thenByDescending { it.startColumn }
        .thenByDescending { it.endLine }
        .thenByDescending { it.endColumn }

    private fun explicitPrimaryEdit(): TextRangeEdit? {
        val sl = item.editStartLine ?: return null
        val sc = item.editStartColumn ?: return null
        val el = item.editEndLine ?: return null
        val ec = item.editEndColumn ?: return null
        return TextRangeEdit(sl, sc, el, ec, item.insertText)
    }

    private fun applySingle(text: Content, edit: TextRangeEdit) {
        text.replace(
            edit.startLine - 1,
            edit.startColumn - 1,
            edit.endLine - 1,
            edit.endColumn - 1,
            edit.newText,
        )
    }
}

private fun mapKind(kind: Int?): CompletionItemKind = when (kind) {
    1 -> CompletionItemKind.Text
    2 -> CompletionItemKind.Method
    3 -> CompletionItemKind.Function
    4 -> CompletionItemKind.Constructor
    5 -> CompletionItemKind.Field
    6 -> CompletionItemKind.Variable
    7 -> CompletionItemKind.Class
    8 -> CompletionItemKind.Interface
    9 -> CompletionItemKind.Module
    10 -> CompletionItemKind.Property
    11 -> CompletionItemKind.Unit
    12 -> CompletionItemKind.Value
    13 -> CompletionItemKind.Enum
    14 -> CompletionItemKind.Keyword
    15 -> CompletionItemKind.Snippet
    16 -> CompletionItemKind.Color
    17 -> CompletionItemKind.File
    18 -> CompletionItemKind.Reference
    19 -> CompletionItemKind.Folder
    20 -> CompletionItemKind.EnumMember
    21 -> CompletionItemKind.Constant
    22 -> CompletionItemKind.Struct
    23 -> CompletionItemKind.Event
    24 -> CompletionItemKind.Operator
    25 -> CompletionItemKind.TypeParameter
    else -> CompletionItemKind.Identifier
}

/** The Vue trigger belongs to the same post-debounce text/caret as its native request. */
internal fun vueCapturedTrigger(languageId: String, text: String, line: Int, column: Int): String? {
    if (line < 1 || column < 1) return null
    val lineText = text.lineSequence().elementAtOrNull(line - 1)?.removeSuffix("\r") ?: return null
    if (column > lineText.length + 1) return null
    return ProfessionalCompletion.triggerCharacter(languageId, lineText, column - 1)
}

/** Only the actual post-debounce caret may contribute an ELP trigger. */
internal fun elpCapturedTrigger(text: String, line: Int, column: Int): String? {
    if (line < 1 || column < 1) return null
    val lineText = text.split('\n').getOrNull(line - 1)?.removeSuffix("\r") ?: return null
    if (column > lineText.length + 1 || column == 1 || '\r' in lineText) return null
    return lineText[column - 2].takeIf { it in ":#?.-\\" }?.toString()
}
