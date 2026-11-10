package com.baystudio.droide.ui

import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.baystudio.droide.core.CodeStyleAuthority
import com.baystudio.droide.core.CodeStyleDefaults
import com.baystudio.droide.core.CodingKeyboardMode
import com.baystudio.droide.core.CodeStyleSnippets
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.DroideThemeSnapshot
import com.baystudio.droide.core.FormatterManager
import com.baystudio.droide.core.ITerminalSession
import com.baystudio.droide.core.LanguageRegistry
import com.baystudio.droide.core.LspFormattingOptions
import com.baystudio.droide.core.LspManager
import com.baystudio.droide.core.LspDocumentSupportPhase
import com.baystudio.droide.core.LspDocumentSupportUnavailableException
import com.baystudio.droide.core.LspProjectAnalysisNotReadyException
import com.baystudio.droide.core.LspProjectAnalysisReadiness
import com.baystudio.droide.core.LspProjectAnalysisRequiredException
import com.baystudio.droide.core.LspCallHierarchyItem
import com.baystudio.droide.core.LspCodeAction
import com.baystudio.droide.core.LspLocation
import com.baystudio.droide.core.LspSymbol
import com.baystudio.droide.core.LspWorkspaceEdit
import com.baystudio.droide.core.ProfessionalSnippets
import com.baystudio.droide.core.runSuspendCatching
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.EditorFocusChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.completion.snippet.parser.CodeSnippetParser
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import kotlinx.coroutines.*

@Composable
internal fun EditorPane(
    files: FileRepository,
    path: String,
    document: EditorDocument,
    bridge: ActiveEditorBridge,
    terminal: ITerminalSession? = null,
    lsp: LspManager,
    formatter: FormatterManager,
    theme: DroideThemeSnapshot,
    codeStyleDefaults: CodeStyleDefaults = CodeStyleDefaults(),
    wordwrap: Boolean = false,
    accessoryKeysComfortable: Boolean = false,
    codingKeyboardMode: CodingKeyboardMode = CodingKeyboardMode.DEFAULT,
    accessoryInputFocus: AccessoryInputFocusController,
    accessoryKeysExpanded: Boolean,
    onAccessoryKeysExpandedChange: (Boolean) -> Unit,
    onToggleBreakpoint: ((Int) -> Unit)? = null,
    onOpenLocation: ((String, Int, Int) -> Unit)? = null,
    onApplyWorkspaceEdit: ((LspWorkspaceEdit) -> Unit)? = null,
    navigateLine: Int? = null,
    navigateColumn: Int = 1,
    navigationToken: Int = 0,
    onNavigationConsumed: () -> Unit = {},
) {
    if (com.baystudio.droide.core.DocumentPreviewPolicy.kind(path) != null) {
        LaunchedEffect(document) { document.ensureLoaded(files) }
        DocumentPreviewPane(files, path) { files.resolveChecked(path) }
        return
    }
    // Reading this flow in composition makes background readiness visible without typing.
    val lspStatusRevision by lsp.statusRevision.collectAsState()
    val scope = rememberWorkspaceCoroutineScope()
    val workspaceKey = files.root.absolutePath
    val editorEvents = remember(workspaceKey, path) { EditorEventSubscriptions() }
    var editorRef by remember(workspaceKey, path) { mutableStateOf<DroideCodeEditor?>(null) }
    val accessoryModifiers = remember(workspaceKey, path) { AccessoryModifierController() }
    val accessoryFocusOwner = remember(workspaceKey, path) { accessoryInputFocus.owner(AccessoryInputTarget.EDITOR) }
    var showFind by rememberSaveable(workspaceKey, path) { mutableStateOf(false) }
    var findQuery by rememberSaveable(workspaceKey, path) { mutableStateOf("") }
    var replaceQuery by rememberSaveable(workspaceKey, path) { mutableStateOf("") }
    var findCount by remember(workspaceKey, path) { mutableIntStateOf(0) }
    var confirmReload by remember(workspaceKey, path) { mutableStateOf(false) }
    var lastAppliedRevision by remember(workspaceKey, path) { mutableIntStateOf(-1) }
    var applyingDocumentText by remember(workspaceKey, path) { mutableStateOf(false) }
    var editMenu by remember(workspaceKey, path) { mutableStateOf(false) }
    var navigateMenu by remember(workspaceKey, path) { mutableStateOf(false) }
    var lspMenu by remember(workspaceKey, path) { mutableStateOf(false) }
    var showCmakeSelection by remember(workspaceKey, path) { mutableStateOf(false) }
    var showCsharpSelection by remember(workspaceKey, path) { mutableStateOf(false) }
    var lspResultTitle by remember(workspaceKey, path) { mutableStateOf<String?>(null) }
    var lspResultText by remember(workspaceKey, path) { mutableStateOf("") }
    var lspResultGuard by remember(workspaceKey, path) { mutableStateOf<com.baystudio.droide.core.LspHeldResultGuard?>(null) }
    var codeActions by remember(workspaceKey, path) { mutableStateOf<List<LspCodeAction>>(emptyList()) }
    var showCodeActions by remember(workspaceKey, path) { mutableStateOf(false) }
    var codeActionError by remember(workspaceKey, path) { mutableStateOf<String?>(null) }
    var callHierarchyTitle by remember(workspaceKey, path) { mutableStateOf<String?>(null) }
    var callHierarchyItems by remember(workspaceKey, path) { mutableStateOf<List<LspCallHierarchyItem>>(emptyList()) }
    var callHierarchyHistory by remember(workspaceKey, path) { mutableStateOf<List<Pair<String, List<LspCallHierarchyItem>>>>(emptyList()) }
    var callHierarchyError by remember(workspaceKey, path) { mutableStateOf<String?>(null) }
    var locationTitle by remember(workspaceKey, path) { mutableStateOf<String?>(null) }
    var locationItems by remember(workspaceKey, path) { mutableStateOf<List<LspLocation>>(emptyList()) }
    var symbolItems by remember(workspaceKey, path) { mutableStateOf<List<LspSymbol>>(emptyList()) }
    var showRename by rememberSaveable(workspaceKey, path) { mutableStateOf(false) }
    var renameName by rememberSaveable(workspaceKey, path) { mutableStateOf("") }
    var pendingRenameEdit by remember(workspaceKey, path) { mutableStateOf<LspWorkspaceEdit?>(null) }
    var codeIntelBusy by remember(workspaceKey, path) { mutableStateOf(false) }
    fun launchEditorAction(block: suspend () -> Unit) = scope.launchUiCatching(
        onError = { failure ->
            codeIntelBusy = false
            document.status = "Editor action failed: ${failure.message ?: failure::class.java.simpleName}"
        }, block = block,
    )

    val effectiveWordWrap = wordwrap && !document.largeFileOptimized
    val languageId = remember(path) { LanguageRegistry.forFile(path)?.id ?: "plaintext" }
    val codeStyleAuthority = remember(workspaceKey) { CodeStyleAuthority(files.root) }
    val codeStyle = remember(workspaceKey, path, languageId, document.loaded, codeStyleDefaults) {
        codeStyleAuthority.resolve(path, languageId, if (document.loaded) document.content else "", codeStyleDefaults)
    }
    val signatureControllerRef = remember(workspaceKey, path) { arrayOfNulls<DroideSignatureHelpController>(1) }
    val highlightControllerRef = remember(workspaceKey, path) { arrayOfNulls<DroideEditorHighlightController>(1) }
    LaunchedEffect(lspStatusRevision) {
        signatureControllerRef[0]?.invalidateHeldResult()
        highlightControllerRef[0]?.invalidateHeldResult()
        if (codeActions.any { it.activationGuard?.accepts() == false }) codeActions = emptyList()
        if (callHierarchyItems.any { it.activationGuard?.accepts() == false } ||
            callHierarchyHistory.any { entry -> entry.second.any { it.activationGuard?.accepts() == false } }) {
            callHierarchyItems = emptyList(); callHierarchyHistory = emptyList(); callHierarchyTitle = null
        }
        if (lspResultGuard?.accepts() == false) { lspResultTitle = null; lspResultText = ""; lspResultGuard = null }
        if (locationItems.any { it.activationGuard?.accepts() == false }) { locationItems = emptyList(); locationTitle = null }
        if (symbolItems.any { it.activationGuard?.accepts() == false }) symbolItems = emptyList()
    }
    fun clearEditorControllers() {
        fun cleanup(block: () -> Unit) {
            runUiCatching(onError = { document.status = "Editor cleanup failed: ${it.message}" }, block = block)
        }
        cleanup { editorEvents.clear() }
        val signature = signatureControllerRef[0]
        signatureControllerRef[0] = null
        cleanup { signature?.release() }
        val highlighting = highlightControllerRef[0]
        highlightControllerRef[0] = null
        cleanup { highlighting?.release() }
    }
    val completionLanguage = remember(workspaceKey, path, lsp, languageId, document.largeFileOptimized, codeStyle) {
        if (document.largeFileOptimized) EmptyLanguage()
        else DroideCompletionLanguage(
            path = path,
            languageId = languageId,
            lsp = lsp,
            codeStyle = codeStyle,
            onCompletionRejected = { document.status = it },
            captureNativeCompletionEditor = {
                val capturedEditor = editorRef
                val capturedBinding = document.nativeMutationBinding
                val capturedLineSeparator = capturedEditor?.lineSeparator?.content
                if (capturedEditor == null || capturedBinding?.owner !== capturedEditor ||
                    capturedLineSeparator !in setOf("\n", "\r\n")) null
                else NativeCompletionEditorCapture(capturedEditor) {
                    editorRef === capturedEditor && document.nativeMutationBinding === capturedBinding &&
                        capturedEditor.lineSeparator.content == capturedLineSeparator &&
                        document.canEdit && capturedEditor.acceptsMutations &&
                        runCatching { capturedBinding.requireCaptureAllowed() }.isSuccess
                }
            },
        )
    }

    LaunchedEffect(document, document.pendingRecoveryBuffer, workspaceKey, path, lsp) {
        document.ensureLoaded(files)
    }
    EditorLspDocumentEffect(document, path, lsp)
    LaunchedEffect(document.performanceMode) {
        if (document.largeFileOptimized) {
            signatureControllerRef[0]?.release()
            signatureControllerRef[0] = null
            highlightControllerRef[0]?.release()
            highlightControllerRef[0] = null
        }
    }

    LaunchedEffect(editorRef, navigationToken, navigateLine, navigateColumn, document.loaded, document.revision) {
        val ed = editorRef ?: return@LaunchedEffect
        val line = navigateLine ?: return@LaunchedEffect
        if (!document.loaded || document.kind != EditorDocumentKind.TEXT) return@LaunchedEffect

        val lineIndex = (line - 1).coerceIn(0, (ed.text.lineCount - 1).coerceAtLeast(0))
        val lineLength = runCatching { ed.text.getColumnCount(lineIndex) }.getOrDefault(0)
        val columnIndex = (navigateColumn - 1).coerceIn(0, lineLength)
        ed.cancelViewportRestore()
        runCatching { ed.setSelection(lineIndex, columnIndex) }
        onNavigationConsumed()
    }
    BindDroideSoraFeatures(editorRef, document, lsp, files, path)

    LaunchedEffect(document, findQuery, document.content, document.performanceMode) {
        findCount = if (document.largeFileOptimized || findQuery.length < 2) 0 else countOccurrences(document.content, findQuery)
    }
    DisposableEffect(document, path) {
        val captureSnapshot: () -> Unit = {
            val editor = editorRef
            if (editor != null && !editor.isReleased) {

                editor.nativeCapture?.invoke()
            }
        }
        bridge.snapshot = captureSnapshot
        onDispose {

            runUiCatching(onError = { document.status = "Could not capture editor: ${it.message}" }) { captureSnapshot() }
            if (bridge.nativeHandoff?.owner === editorRef) bridge.nativeHandoff = null
            if (bridge.snapshot === captureSnapshot) bridge.clear()
            clearEditorControllers()
            accessoryModifiers.clear()
            accessoryInputFocus.clearIf(accessoryFocusOwner)
        }
    }

    fun requireFullIntelligence(feature: String) = document.requireFullIntelligence(feature)
    fun cursorLineColumn(): Pair<Int, Int> = editorCursorLineColumn(editorRef, document)
    fun requestCompletion() {
        if (!document.canEdit) { document.status = "Review Mode · completion insertion is disabled"; return }
        if (!requireFullIntelligence("Completion")) return
        editorRef?.restoreInputFocus(showKeyboard = true)
        editorRef?.getComponent(EditorAutoCompletion::class.java)?.requireCompletion()
    }
    fun requestSignatureHelp() {
        if (!requireFullIntelligence("Parameter Info")) return
        val support = lsp.documentSupportStatus(path, document.content)
        if (support?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8 ||
            support?.phase == LspDocumentSupportPhase.CLOSED) {
            document.status = support.message
            return
        }
        val analysis = lsp.projectAnalysisStatus(path)
        if (analysis?.readiness == LspProjectAnalysisReadiness.PREPARING ||
            analysis?.readiness == LspProjectAnalysisReadiness.FAILED) {
            document.status = analysis.readinessDetail?.take(320)
                ?: "Project analysis is ${analysis.readiness.name.lowercase()}. Check Code actions for Revoke or Retry."
            return
        }
        if (document.canEdit) editorRef?.restoreInputFocus(showKeyboard = true)
        signatureControllerRef[0]?.triggerManual()
    }

    fun shiftSnippet(forward: Boolean) {
        if (!document.canEdit) return
        val controller = editorRef?.snippetController ?: return
        if (!controller.isInSnippet()) return
        if (forward) controller.shiftToNextTabStop() else controller.shiftToPreviousTabStop()
        editorRef?.restoreInputFocus(showKeyboard = true)
    }

    fun requestCodeActionsAtCursor() = launchEditorAction {
        if (!document.canEdit) { document.status = "Review Mode · code actions that mutate buffers are disabled"; return@launchEditorAction }
        if (!requireFullIntelligence("Quick Fix / Refactor")) return@launchEditorAction
        bridge.capture()
        val (line, col) = cursorLineColumn()
        codeIntelBusy = true
        codeActionError = null
        val result = runSuspendCatching { lsp.codeActionsAt(path, line, col) }
        codeActions = result.getOrElse {
            codeActionError = it.message ?: it::class.java.simpleName
            emptyList()
        }
        codeActions = codeActions.filter { it.activationGuard?.accepts() != false }
        codeIntelBusy = false
        showCodeActions = true
    }

    fun requestCallHierarchy() = launchEditorAction {
        if (!requireFullIntelligence("Call Hierarchy")) return@launchEditorAction
        bridge.capture()
        val (line, col) = cursorLineColumn()
        codeIntelBusy = true
        callHierarchyError = null
        val result = runSuspendCatching { lsp.prepareCallHierarchy(path, line, col) }
        callHierarchyItems = result.getOrElse {
            callHierarchyError = it.message ?: it::class.java.simpleName
            emptyList()
        }
        callHierarchyItems = callHierarchyItems.filter { it.activationGuard?.accepts() != false }
        callHierarchyHistory = emptyList()
        callHierarchyTitle = "Call Hierarchy"
        codeIntelBusy = false
    }

    fun requestCallRelations(item: LspCallHierarchyItem, incoming: Boolean) = launchEditorAction {
        if (item.activationGuard?.accepts() == false) return@launchEditorAction
        if (!requireFullIntelligence("Call Hierarchy")) return@launchEditorAction
        codeIntelBusy = true
        callHierarchyError = null
        val previousTitle = callHierarchyTitle ?: "Call Hierarchy"
        val previousItems = callHierarchyItems
        val result = runSuspendCatching { if (incoming) lsp.incomingCalls(item) else lsp.outgoingCalls(item) }
        val next = result.getOrElse {
            callHierarchyError = it.message ?: it::class.java.simpleName
            emptyList()
        }
        if (result.isSuccess && item.activationGuard?.accepts() != false) {
            callHierarchyHistory = (callHierarchyHistory + (previousTitle to previousItems)).takeLast(20)
            callHierarchyItems = next.filter { it.activationGuard?.accepts() != false }
            callHierarchyTitle = "${if (incoming) "Incoming" else "Outgoing"} · ${item.name}"
        }
        codeIntelBusy = false
    }

    fun requestLocations(op: String, title: String, navigateSingle: Boolean) = launchEditorAction {
        if (!requireFullIntelligence("Code navigation")) return@launchEditorAction
        bridge.capture()
        val (line, col) = cursorLineColumn()
        codeIntelBusy = true
        val result = runSuspendCatching { lsp.locationItems(op, path, line, col) }
        codeIntelBusy = false
        val pending = result.exceptionOrNull() as? LspProjectAnalysisNotReadyException
        if (pending != null) {
            lspResultTitle = title
            lspResultText = pending.message.orEmpty()
            return@launchEditorAction
        }
        val items = result.getOrDefault(emptyList())
        when {
            items.size == 1 && navigateSingle && onOpenLocation != null -> {
                val item = items.single()
                if (item.activationGuard?.accepts() != false) onOpenLocation(item.path, item.line, item.column)
                document.status = "$title → ${item.path}:${item.line}:${item.column}"
            }
            items.isNotEmpty() -> {
                locationTitle = title
                locationItems = items
            }
            else -> {
                lspResultTitle = title
                val held = lsp.heldTextResult(op, path, line, col)
                lspResultGuard = held.guard
                lspResultText = if (held.guard?.accepts() == false) "Result changed; request it again." else held.text.take(20_000)
            }
        }
    }

    fun requestSymbols() = launchEditorAction {
        if (!requireFullIntelligence("Document Symbols")) return@launchEditorAction
        bridge.capture()
        codeIntelBusy = true
        val result = runSuspendCatching { lsp.symbolItems(path) }
        codeIntelBusy = false
        val pending = result.exceptionOrNull() as? LspProjectAnalysisNotReadyException
        if (pending != null) {
            lspResultTitle = "Document Symbols"
            lspResultText = pending.message.orEmpty()
            return@launchEditorAction
        }
        val items = result.getOrDefault(emptyList())
        if (items.isEmpty()) {
            val (line, col) = cursorLineColumn()
            lspResultTitle = "Document Symbols"
            val held = lsp.heldTextResult("documentSymbol", path, line, col)
            lspResultGuard = held.guard
            lspResultText = if (held.guard?.accepts() == false) "Result changed; request it again." else held.text.take(20_000)
        } else symbolItems = items
    }

    fun requestRename() = launchEditorAction {
        if (!document.canEdit) { document.status = "Review Mode · rename is disabled"; return@launchEditorAction }
        if (!requireFullIntelligence("Rename Symbol")) return@launchEditorAction
        val candidate = if (lsp.completionPolicy(path) == com.baystudio.droide.core.LspCompletionPolicy.TOML_NATIVE) renameName else renameName.trim()
        if (candidate.isBlank()) return@launchEditorAction
        bridge.capture()
        val (line, col) = cursorLineColumn()
        codeIntelBusy = true
        val edit = runSuspendCatching { lsp.renameSymbol(path, line, col, candidate) }
            .onFailure {
                lspResultTitle = "Rename Symbol"
                lspResultText = "Rename failed: ${it.message ?: it::class.java.simpleName}"
            }
            .getOrNull()
        codeIntelBusy = false
        if (edit == null || edit.edits.isEmpty()) {
            if (lspResultTitle == null) {
                lspResultTitle = "Rename Symbol"
                lspResultText = "Language server returned no rename edits."
            }
        } else {
            pendingRenameEdit = edit
            showRename = false
        }
    }

    fun runLspOperation(op: String, title: String) = launchEditorAction {
        if (!requireFullIntelligence("Language intelligence")) return@launchEditorAction
        bridge.capture()
        val (line, col) = cursorLineColumn()
        codeIntelBusy = true
        val result = lsp.heldTextResult(op, path, line, col)
        codeIntelBusy = false
        lspResultTitle = title
        lspResultGuard = result.guard
        lspResultText = if (result.guard?.accepts() == false) "Result changed; request it again." else result.text.take(20_000)
    }

    fun reload(discardUnsaved: Boolean = false) = launchEditorAction {
        bridge.capture()
        document.reload(files, discardUnsaved)
    }
    fun findNext() {
        val ed = editorRef ?: return
        val text: CharSequence = ed.text
        if (findQuery.isEmpty()) return
        val idx = text.indexOf(findQuery, (ed.cursor?.left ?: 0) + 1)
        val pos = if (idx == -1) text.indexOf(findQuery) else idx
        if (pos != -1) ed.setSelectionOffsets(pos, pos + findQuery.length)
    }
    fun replaceOne() {
        if (!document.canEdit) { document.status = "Review Mode · replace is disabled"; return }
        val ed = editorRef ?: return
        val text: CharSequence = ed.text
        if (findQuery.isEmpty()) return
        val cur = ed.cursor?.left ?: 0
        val idx = text.indexOf(findQuery, cur).let { if (it >= 0) it else text.indexOf(findQuery) }
        if (idx != -1) {

            ed.setSelectionOffsets(idx, idx + findQuery.length)
            ed.commitText(replaceQuery, false)
        }
    }
    fun replaceAll() {
        if (!document.canEdit) { document.status = "Review Mode · replace is disabled"; return }
        val ed = editorRef ?: return
        if (findQuery.isEmpty()) return
        val count = ed.replaceAllBounded(findQuery, replaceQuery)
        if (count == null) document.status = "Replace All stopped: more than 50000 matches; narrow the search first"
        else document.status = "Replaced $count occurrence${if (count == 1) "" else "s"}"
    }

    fun formatDocument() = launchEditorAction {
        if (lsp.cmakeSelectionStatus(path) != null) {
            document.status = "CMake formatting is disabled for this managed profile."
            return@launchEditorAction
        }
        if (!document.canEdit) { document.status = "Review Mode · formatting is disabled"; return@launchEditorAction }
        if (!requireFullIntelligence("Format Document")) return@launchEditorAction
        bridge.capture()
        val support = lsp.documentSupportStatus(path, document.content)
        if (support?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8 ||
            support?.phase == LspDocumentSupportPhase.CLOSED) {
            document.status = support.message
            return@launchEditorAction
        }
        if (lsp.projectAnalysisStatus(path)?.enabled == false) {
            document.status = "Project analysis is disabled. Use Code actions → Enable project analysis; no external formatter was started."
            return@launchEditorAction
        }
        bridge.capture()
        codeIntelBusy = true
        val lspResult = runSuspendCatching { lsp.formatDocument(path, LspFormattingOptions.fromCodeStyle(codeStyle)) }
        codeIntelBusy = false
        val unsupported = lspResult.exceptionOrNull() as? LspDocumentSupportUnavailableException
        val currentSupport = lsp.documentSupportStatus(path, document.content)
        if (unsupported != null || currentSupport?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8 ||
            currentSupport?.phase == LspDocumentSupportPhase.CLOSED) {
            document.status = unsupported?.status?.message ?: currentSupport?.message.orEmpty()
            return@launchEditorAction
        }
        if (lspResult.exceptionOrNull() is LspProjectAnalysisRequiredException || lsp.projectAnalysisStatus(path)?.enabled == false) {
            document.status = "Project analysis is disabled or was revoked; no external formatter was started."
            return@launchEditorAction
        }
        val pending = lspResult.exceptionOrNull() as? LspProjectAnalysisNotReadyException
        if (pending != null) {
            document.status = pending.message.orEmpty()
            return@launchEditorAction
        }
        val lspEdit = lspResult.getOrNull()
        if (!document.canEdit) return@launchEditorAction
        if (lspEdit != null && lspEdit.edits.isNotEmpty() && onApplyWorkspaceEdit != null) {
            onApplyWorkspaceEdit(lspEdit)
            document.status = "LSP formatting returned · workspace edits requested"
            return@launchEditorAction
        }
        lsp.projectAnalysisStatus(path)?.let { analysis ->
            document.status = "No language-server formatting edits received. " +
                (analysis.capabilityNote ?: "The server or its formatter may be unavailable; no external formatter was started.")
            return@launchEditorAction
        }
        val revision = document.revision; val version = document.changeVersion; val snapshot = document.content; codeIntelBusy = true
        try {
            val formatted = runSuspendCatching { formatter.format(path, snapshot, codeStyle) }.getOrElse { failure ->
                document.status = "External format failed: ${failure.message ?: failure.javaClass.simpleName}"
                return@launchEditorAction
            }
            if (document.revision != revision || document.changeVersion != version || document.content != snapshot || !document.canEdit) {
                document.status = "Format skipped: editor changed while formatter was running"; return@launchEditorAction
            }
            if (formatted == null) document.status = "No LSP formatter; external formatter is disabled or unavailable for this file"
            else if (formatted.text == snapshot) document.status = "Already formatted via ${formatted.formatter}"
            else document.applyWorkspaceText(formatted.text, "Formatted via ${formatted.formatter} · review before saving")
        } finally { codeIntelBusy = false }
    }

    SideEffect {
        bridge.find = { showFind = true }
        bridge.quickFix = { requestCodeActionsAtCursor() }
        bridge.renameSymbol = { renameName = ""; showRename = true }
        bridge.documentSymbols = { requestSymbols() }
        bridge.goToDefinition = { requestLocations("goToDefinition", "Definition", navigateSingle = true) }
        bridge.findReferences = { requestLocations("findReferences", "References", navigateSingle = false) }
        bridge.formatDocument = { formatDocument() }
        bridge.completion = { requestCompletion() }
        bridge.signatureHelp = { requestSignatureHelp() }
        bridge.resetZoom = {
            val editor = editorRef
            if (editor != null && editor.acceptsFeatureUpdates && document.nativeMutationBinding?.owner === editor) {
                runUiCatching(onError = { document.status = "Could not reset editor zoom: ${it.message}" }) {
                    editor.resetZoomPreservingViewport()
                    document.captureViewport(editor.captureViewport())
                }
            }
        }
        bridge.cursorLocation = { cursorLineColumn() }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { if (!codeIntelBusy) showRename = false },
            title = { Text("Rename Symbol") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (lsp.completionPolicy(path) == com.baystudio.droide.core.LspCompletionPolicy.TOML_NATIVE) com.baystudio.droide.core.LspTomlProfile.RENAME_GUIDANCE else "Ask the active language server for a workspace-wide rename. Nothing is saved automatically.")
                    OutlinedTextField(
                        value = renameName,
                        onValueChange = { renameName = it.take(256) },
                        label = { Text("New name") },
                        singleLine = true,
                    )
                    if (codeIntelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { requestRename() }, enabled = document.canEdit && renameName.isNotBlank() && !codeIntelBusy) { Text("Preview") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }, enabled = !codeIntelBusy) { Text("Cancel") } },
        )
    }

    pendingRenameEdit?.let { edit ->
        val paths = remember(edit) { edit.edits.map { it.path }.distinct().sorted() }
        AlertDialog(
            onDismissRequest = { pendingRenameEdit = null },
            title = { Text("Review Rename") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${edit.edits.size} edits in ${edit.fileCount} files · review before Save All")
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(paths, key = { it }) { p ->
                            val count = edit.edits.count { it.path == p }
                            ListItem(
                                headlineContent = { FileIdentityLabel(p, p, iconSize = 15.dp, maxLines = 2) },
                                supportingContent = { Text("$count edit(s)") },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    pendingRenameEdit = null
                    onApplyWorkspaceEdit?.invoke(edit)
                }, enabled = document.canEdit && onApplyWorkspaceEdit != null) { Text("Apply to buffers") }
            },
            dismissButton = { TextButton(onClick = { pendingRenameEdit = null }) { Text("Cancel") } },
        )
    }

    if (symbolItems.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { symbolItems = emptyList() },
            title = { Text("Outline") },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(symbolItems, key = { "${it.path}:${it.line}:${it.column}:${it.name}" }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name, maxLines = 1) },
                            supportingContent = { FileIdentityLabel(item.path, "${item.path}:${item.line}:${item.column}", iconSize = 14.dp) },
                            modifier = Modifier
                                .padding(start = (item.depth.coerceIn(0, 8) * 12).dp)
                                .clickable {
                                    if (item.activationGuard?.accepts() != false) onOpenLocation?.invoke(item.path, item.line, item.column)
                                    symbolItems = emptyList()
                                },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { symbolItems = emptyList() }) { Text("Close") } },
        )
    }

    locationTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { locationTitle = null; locationItems = emptyList() },
            title = { Text(title) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(locationItems, key = { "${it.path}:${it.line}:${it.column}" }) { item ->
                        ListItem(
                            headlineContent = { FileIdentityLabel(item.path, item.path, iconSize = 15.dp, maxLines = 2) },
                            supportingContent = { Text("${item.line}:${item.column}") },
                            modifier = Modifier.clickable {
                                if (item.activationGuard?.accepts() != false) onOpenLocation?.invoke(item.path, item.line, item.column)
                                locationTitle = null
                                locationItems = emptyList()
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { locationTitle = null; locationItems = emptyList() }) { Text("Close") } },
        )
    }

    if (lspResultTitle != null) {
        AlertDialog(
            onDismissRequest = { lspResultTitle = null },
            title = { Text(lspResultTitle ?: "Code Intelligence") },
            text = {
                Surface(Modifier.fillMaxWidth().heightIn(max = 420.dp), tonalElevation = 1.dp) {
                    Text(lspResultText.ifBlank { "(no result)" }, Modifier.padding(12.dp), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { lspResultTitle = null }) { Text("Close") } },
        )
    }

    if (callHierarchyTitle != null) {
        AlertDialog(
            onDismissRequest = { callHierarchyTitle = null },
            title = { Text(callHierarchyTitle ?: "Call Hierarchy") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    callHierarchyError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = document) }
                    if (callHierarchyItems.isEmpty() && callHierarchyError == null) {
                        Text("No call hierarchy items returned by the language server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(callHierarchyItems, key = { "${it.path}:${it.line}:${it.column}:${it.name}" }) { item ->
                            ListItem(
                                headlineContent = { Text(item.name, maxLines = 1) },
                                supportingContent = {
                                    Column {
                                        item.detail?.let { Text(it, maxLines = 1) }
                                        FileIdentityLabel(item.path, "${item.path}:${item.line}:${item.column}", iconSize = 14.dp)
                                    }
                                },
                                trailingContent = {
                                    Row {
                                        TextButton(onClick = { requestCallRelations(item, incoming = true) }) { Text("In") }
                                        TextButton(onClick = { requestCallRelations(item, incoming = false) }) { Text("Out") }
                                    }
                                },
                                modifier = Modifier.clickable { if (item.activationGuard?.accepts() != false) onOpenLocation?.invoke(item.path, item.line, item.column) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = callHierarchyHistory.isNotEmpty(),
                    onClick = {
                        val previous = callHierarchyHistory.lastOrNull() ?: return@TextButton
                        if (previous.second.any { it.activationGuard?.accepts() == false }) return@TextButton
                        callHierarchyHistory = callHierarchyHistory.dropLast(1)
                        callHierarchyTitle = previous.first
                        callHierarchyItems = previous.second
                        callHierarchyError = null
                    },
                ) { Text("Back") }
            },
            dismissButton = { TextButton(onClick = { callHierarchyTitle = null }) { Text("Close") } },
        )
    }

    if (showCodeActions) {
        AlertDialog(
            onDismissRequest = { showCodeActions = false },
            title = { Text("Quick Fix / Refactor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    codeActionError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error, ownerKey = document) }
                    if (codeActionError == null && codeActions.isEmpty()) {
                        Text("No code actions available at the cursor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(codeActions, key = { "${it.title}:${it.kind}:${it.preferred}" }) { action ->
                            val enabled = document.canEdit && action.activationGuard?.accepts() != false && action.disabledReason == null && (action.edit != null || !action.rawJson.isNullOrBlank())
                            ListItem(
                                headlineContent = { Text(action.title) },
                                supportingContent = {
                                    Text(
                                        action.disabledReason
                                            ?: listOfNotNull(action.kind, if (action.preferred) "preferred" else null).joinToString(" • ").ifBlank {
                                                if (action.edit != null) "Workspace edit" else "Resolve on selection"
                                            }
                                    )
                                },
                                modifier = if (enabled) Modifier.clickable {
                                    launchEditorAction {
                                        if (action.activationGuard?.accepts() == false) return@launchEditorAction
                                        codeIntelBusy = true
                                        val result = runSuspendCatching { lsp.resolveCodeAction(path, action) }
                                        codeIntelBusy = false
                                        val pending = result.exceptionOrNull() as? LspProjectAnalysisNotReadyException
                                        if (pending != null) {
                                            codeActionError = pending.message
                                            return@launchEditorAction
                                        }
                                        if (action.activationGuard?.accepts() == false ||
                                            (action.activationGuard != null && result.isFailure)) {
                                            codeActionError = "Code action expired. Request it again."
                                            return@launchEditorAction
                                        }
                                        val resolved = result.getOrNull() ?: action
                                        if (resolved.activationGuard?.accepts() == false) return@launchEditorAction
                                        val edit = resolved.edit
                                        if (edit != null && onApplyWorkspaceEdit != null) {
                                            onApplyWorkspaceEdit(edit)
                                            document.status = "Code action applied to buffer — review before saving"
                                            showCodeActions = false
                                        } else {
                                            codeActionError = resolved.disabledReason
                                                ?: if (resolved.commandTitle != null) "This action requires a server command and cannot be previewed safely."
                                                else "Language server returned no applicable workspace edit."
                                        }
                                    }
                                } else Modifier,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCodeActions = false }) { Text("Close") } },
        )
    }

    if (confirmReload) {
        AlertDialog(
            onDismissRequest = { confirmReload = false },
            title = { Text("Discard unsaved changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FileIdentityLabel(path, path.substringAfterLast('/'), iconSize = 18.dp)
                    Text("Reloading this file will replace the current unsaved buffer with the version on disk.")
                }
            },
            confirmButton = {
                Button(onClick = { confirmReload = false; reload(discardUnsaved = true) }) { Text("Discard & reload") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmReload = false }) { Text("Cancel") } },
        )
    }

    fun insertAccessoryText(inserted: String) {
        if (!document.canEdit) return
        val editor = editorRef ?: return
        if (!editor.isEditable) {
            document.status = "Editor is temporarily unavailable for editing. Try again in a moment."
            return
        }
        if (accessoryModifiers.hasCtrlOrAlt() && inserted.length == 1) {
            val keyCode = android.view.KeyEvent.keyCodeFromString("KEYCODE_${inserted[0].uppercaseChar()}")
            if (keyCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
                dispatchAccessoryKey(editor, keyCode, accessoryModifiers.androidMetaState())
            } else {
                editor.commitText(inserted, false)
            }
        } else {
            editor.commitText(inserted, false)
        }
        editor.restoreInputFocus(showKeyboard = true)
    }

    fun insertAccessorySnippet(source: String) {
        if (!document.canEdit) return
        val editor = editorRef ?: return
        if (!editor.isEditable) {
            document.status = "Editor is temporarily unavailable for editing. Try again in a moment."
            return
        }
        if (!editor.acceptsMutations) return
        val normalizedSource = CodeStyleSnippets.normalize(source, codeStyle)
        val safe = runCatching { ProfessionalSnippets.sanitizeForSora(normalizedSource) }.getOrElse {
            insertAccessoryText(ProfessionalSnippets.plainTextFallback(normalizedSource))
            return
        }
        val snippet = runCatching { CodeSnippetParser.parse(safe) }.getOrNull()
        if (snippet == null || !snippet.checkContent()) {
            insertAccessoryText(ProfessionalSnippets.plainTextFallback(normalizedSource))
            return
        }
        val cursor = editor.cursor
        val start = cursor.left.coerceAtLeast(0)
        val end = cursor.right.coerceAtLeast(start)
        val selectedText = editor.text.subSequence(start, end).toString()
        if (end > start) editor.text.delete(start, end)
        editor.snippetController.startSnippet(start, snippet, selectedText)
        editor.restoreInputFocus(showKeyboard = true)
    }

    fun sendAccessoryKey(keyCode: Int, forcedMetaState: Int) {
        if (!document.canEdit) return
        editorRef?.let { editor ->
            dispatchAccessoryKey(editor, keyCode, accessoryModifiers.androidMetaState(forcedMetaState))
            editor.restoreInputFocus(showKeyboard = true)
        }
    }

    // The revision is a re-read signal only; it is never grant or request authority.
    val analysisStatus = lspStatusRevision.let { lsp.projectAnalysisStatus(path) }
    val csharpSelectionStatus = lspStatusRevision.let { lsp.csharpSelectionStatus(path) }
    if (showCsharpSelection && csharpSelectionStatus != null) {
        CSharpAnalysisSelectionDialog(csharpSelectionStatus, onDismiss = { showCsharpSelection = false }, onSave = { root, mode, solution ->
            document.status = lsp.saveCsharpSelection(path, root, mode, solution)
        })
    }
    val cmakeSelectionStatus = lspStatusRevision.let { lsp.cmakeSelectionStatus(path) }
    if (showCmakeSelection && cmakeSelectionStatus != null) {
        CMakeAnalysisSelectionDialog(cmakeSelectionStatus, onDismiss = { showCmakeSelection = false }, onSave = { source, build ->
            document.status = lsp.saveCmakeSelection(path, source, build)
        })
    }
    val documentSupport = lspStatusRevision.let { lsp.documentSupportStatus(path, document.content) }
    val documentAnalysisUnavailable = documentSupport?.phase == LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8 ||
        documentSupport?.phase == LspDocumentSupportPhase.CLOSED
    val currentServerStatus = lspStatusRevision.let { lsp.serverStatus(path) }
    AccessoryKeysScaffold(
        focusOwner = accessoryFocusOwner,
        focusController = accessoryInputFocus,
        expanded = accessoryKeysExpanded,
        onExpandedChange = onAccessoryKeysExpandedChange,
        modifiers = accessoryModifiers,
        comfortable = accessoryKeysComfortable,
        languageId = languageId,
        onInsertText = ::insertAccessoryText,
        onInsertSnippet = ::insertAccessorySnippet,
        onKeyCode = ::sendAccessoryKey,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            val langName = LanguageRegistry.forFile(path)?.name ?: "Text"
            Surface(Modifier.weight(1f), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (document.dirty) { Icon(Icons.Default.Circle, "Unsaved changes", Modifier.size(7.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)) }
                    if (document.reviewMode) { Icon(Icons.Default.Lock, "Review Mode", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(5.dp)) }
                    val editorStatus = if (document.largeFileOptimized) "Large File · optimized" else currentServerStatus
                    Text("${if (document.reviewMode) "Review · " else ""}$langName · ${codeStyle.label} · $editorStatus", maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(
                onClick = {
                    bridge.capture()
                    document.setReviewMode(!document.reviewMode)
                    editorRef?.applyReviewMode(document.reviewMode)
                    accessoryModifiers.clear()
                    accessoryInputFocus.clearIf(accessoryFocusOwner)
                },
                enabled = document.editable,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    if (document.reviewMode) Icons.Default.Lock else Icons.Default.LockOpen,
                    if (document.reviewMode) "Disable Review Mode" else "Enable Review Mode",
                    tint = if (document.reviewMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { editMenu = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Edit, "Edit actions") }
                DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                    DropdownMenuItem({ Text("Find / Replace") }, { editMenu = false; showFind = true }, leadingIcon = { Icon(Icons.Default.FindReplace, null) })
                    DropdownMenuItem({ Text("Undo") }, { editMenu = false; runCatching { editorRef?.undo() } }, enabled = document.canEdit, leadingIcon = { Icon(Icons.Default.Undo, null) })
                    DropdownMenuItem({ Text("Redo") }, { editMenu = false; runCatching { editorRef?.redo() } }, enabled = document.canEdit, leadingIcon = { Icon(Icons.Default.Redo, null) })
                    if (document.canEdit && editorRef?.snippetController?.isInSnippet() == true) {
                        DropdownMenuItem({ Text("Previous Snippet Placeholder") }, { editMenu = false; shiftSnippet(forward = false) })
                        DropdownMenuItem({ Text("Next Snippet Placeholder") }, { editMenu = false; shiftSnippet(forward = true) })
                    }
                    DropdownMenuItem({ Text("Reload from Disk") }, { editMenu = false; bridge.capture(); if (document.dirty) confirmReload = true else reload() }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                }
            }
            Box {
                IconButton(onClick = { navigateMenu = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Explore, "Navigate code") }
                DropdownMenu(expanded = navigateMenu, onDismissRequest = { navigateMenu = false }) {
                    DropdownMenuItem({ Text("Hover") }, { navigateMenu = false; runLspOperation("hover", "Hover") }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Go to Definition") }, { navigateMenu = false; requestLocations("goToDefinition", "Definition", true) }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) in setOf(com.baystudio.droide.core.LspCompletionPolicy.VUE_NATIVE, com.baystudio.droide.core.LspCompletionPolicy.CSHARP_NATIVE, com.baystudio.droide.core.LspCompletionPolicy.TOML_NATIVE))
                        DropdownMenuItem({ Text("Go to Type Definition") }, { navigateMenu = false; requestLocations("goToTypeDefinition", "Type Definition", true) }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Go to Implementation") }, { navigateMenu = false; requestLocations("goToImplementation", "Implementation", true) }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Find References") }, { navigateMenu = false; requestLocations("findReferences", "References", false) }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Call Hierarchy") }, { navigateMenu = false; requestCallHierarchy() }, enabled = document.fullIntelligence)
                    DropdownMenuItem({ Text("Document Symbols") }, { navigateMenu = false; requestSymbols() }, enabled = document.fullIntelligence)
                }
            }
            Box {
                IconButton(onClick = { lspMenu = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.AutoFixHigh, "Code actions") }
                DropdownMenu(expanded = lspMenu, onDismissRequest = { lspMenu = false }) {
                    if (csharpSelectionStatus != null) DropdownMenuItem({ Text("Select C# root/solution…") }, {
                        lspMenu = false
                        showCsharpSelection = true
                    })
                    if (cmakeSelectionStatus != null) DropdownMenuItem({ Text("Select CMake source/build…") }, {
                        lspMenu = false
                        showCmakeSelection = true
                    })
                    if (lsp.startupFailure(path) != null) {
                        DropdownMenuItem({ Text("Retry language server") }, {
                            lspMenu = false
                            launchEditorAction { document.status = lsp.retryStartup(path) }
                        }, enabled = document.fullIntelligence)
                    }
                    analysisStatus?.let { analysis ->
                        DropdownMenuItem({ Text(if (analysis.canRevoke) "Revoke project analysis"
                            else if (documentAnalysisUnavailable) "Document analysis unavailable" else "Enable project analysis…") }, {
                            lspMenu = false
                            launchEditorAction {
                                document.status = if (analysis.canRevoke) lsp.revokeProjectAnalysis(path) else lsp.enableProjectAnalysis(path)
                            }
                        }, enabled = analysis.canRevoke || (document.fullIntelligence && !documentAnalysisUnavailable && csharpSelectionStatus?.available != false))
                        if (cmakeSelectionStatus != null && analysis.enabled) {
                            DropdownMenuItem({ Text("Refresh CMake analysis") }, {
                                lspMenu = false
                                launchEditorAction { document.status = lsp.retryProjectAnalysis(path) }
                            }, enabled = document.fullIntelligence)
                        }
                        if (analysis.enabled && !documentAnalysisUnavailable && csharpSelectionStatus?.available != false && analysis.readiness == LspProjectAnalysisReadiness.FAILED) {
                            DropdownMenuItem({ Text("Retry project analysis") }, {
                                lspMenu = false
                                launchEditorAction { document.status = lsp.retryProjectAnalysis(path) }
                            }, enabled = document.fullIntelligence)
                        }
                    }
                    DropdownMenuItem({ Text("Completion") }, { lspMenu = false; requestCompletion() }, enabled = document.canEdit && document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Parameter Info") }, { lspMenu = false; requestSignatureHelp() }, enabled = document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Quick Fix / Refactor") }, { lspMenu = false; requestCodeActionsAtCursor() }, enabled = document.canEdit && document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Rename Symbol") }, { lspMenu = false; renameName = ""; showRename = true }, enabled = document.canEdit && document.fullIntelligence)
                    if (lsp.completionPolicy(path) != com.baystudio.droide.core.LspCompletionPolicy.GRAPHQL_NATIVE)
                        DropdownMenuItem({ Text("Format Document") }, { lspMenu = false; formatDocument() }, enabled = document.canEdit && document.fullIntelligence)
                    if (onToggleBreakpoint != null) DropdownMenuItem({ Text("Toggle Breakpoint") }, {
                        lspMenu = false
                        runUiCatching(onError = { document.status = it.message ?: "Cannot change breakpoint" }) {
                            onToggleBreakpoint((editorRef?.cursor?.leftLine ?: 0) + 1)
                        }
                    })
                    DropdownMenuItem({ Text("Diagnose File") }, {
                        lspMenu = false
                        if (!requireFullIntelligence("Diagnostics")) return@DropdownMenuItem
                        if (documentAnalysisUnavailable) {
                            document.status = documentSupport?.message.orEmpty()
                        } else if (terminal != null || analysisStatus?.readiness == LspProjectAnalysisReadiness.PREPARING ||
                            analysisStatus?.readiness == LspProjectAnalysisReadiness.FAILED) {
                            launchEditorAction { bridge.capture(); document.status = lsp.diagnose(path).take(500) }
                        }
                        else document.status = "Terminal unavailable for diagnostics"
                    }, enabled = document.fullIntelligence, leadingIcon = { Icon(Icons.Default.BugReport, null) })
                }
            }
        }

        csharpSelectionStatus?.let { selection ->
            EditorAnalysisMessage(document, "csharp-selection", selection.message)
        }
        cmakeSelectionStatus?.let { selection ->
            EditorAnalysisMessage(document, "cmake-selection", selection.message)
        }
        documentSupport?.let { support ->
            EditorAnalysisMessage(document, "document-support", support.message)
        }
        analysisStatus?.let { analysis ->
            if (analysis.enabled && analysis.readiness != LspProjectAnalysisReadiness.ON_DEMAND) {
                val phase = when (analysis.readiness) {
                    LspProjectAnalysisReadiness.PREPARING -> "Preparing"
                    LspProjectAnalysisReadiness.READY -> if (csharpSelectionStatus != null) "Transport initialized" else "Ready"
                    LspProjectAnalysisReadiness.FAILED -> "Failed"
                    LspProjectAnalysisReadiness.ON_DEMAND -> "On demand"
                }
                EditorAnalysisMessage(document, "analysis-readiness",
                    "Project analysis · $phase" + analysis.readinessDetail?.take(320)?.let { " · $it" }.orEmpty())
            }
            if (documentSupport != null || analysis.serverId == "clojure-lsp" || analysis.readiness != LspProjectAnalysisReadiness.ON_DEMAND) {
                analysis.capabilityNote?.let { note ->
                    EditorAnalysisMessage(document, "analysis-capability", note)
                }
            }
        }
        if (codeIntelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (showFind) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(findQuery, { findQuery = it }, Modifier.weight(1f), label = { Text("Find") }, placeholder = { Text("text") }, singleLine = true)
                    if (findQuery.isNotEmpty() && !document.largeFileOptimized) Text("$findCount found", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically), style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(replaceQuery, { replaceQuery = it }, Modifier.weight(1f), label = { Text("Replace") }, singleLine = true)
                    IconButton(onClick = { findNext() }) { Icon(Icons.Default.Search, "Find next") }
                    IconButton(onClick = { replaceOne() }, enabled = document.canEdit) { Icon(Icons.Default.FindReplace, "Replace") }
                    Button(onClick = { replaceAll() }, enabled = document.canEdit) { Text("All") }
                    IconButton(onClick = { showFind = false }) { Icon(Icons.Default.Close, "Close") }
                }
            }
            HorizontalDivider()
        }

        val largeFileOwnsStatus = document.largeFileOptimized && (
            document.status.startsWith("Large File Performance Mode") ||
                document.status.startsWith("Buffer exceeds 20 MiB")
            )
        if (document.status.isNotEmpty() && !largeFileOwnsStatus && document.loadError == null) {
            EditorTransientStatus(document.status) { document.status = "" }
        }
        EditorLargeFileStatusBar(document)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
            document.loadError != null -> EditorLoadError(document.loadError.orEmpty(), onRetry = { reload() })
            !document.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            document.kind == EditorDocumentKind.IMAGE -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                AsyncImage(
                    model = runCatching { files.resolveChecked(path) }.getOrNull(),
                    contentDescription = path,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            document.kind == EditorDocumentKind.LARGE -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Large file — read-only preview", style = MaterialTheme.typography.titleMedium)
                Text("Files over 20 MiB open as read-only previews.", style = MaterialTheme.typography.bodySmall)
                Surface(Modifier.fillMaxSize(), tonalElevation = 1.dp) {
                    Text(document.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            document.kind == EditorDocumentKind.BINARY -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Binary file", style = MaterialTheme.typography.titleMedium)
                Text(document.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    launchEditorAction {
                        val abs = runCatching { files.resolveChecked(path).absolutePath }.getOrNull()
                        val res = if (abs != null) terminal?.execArgv(listOf("hexdump", "-C", abs), timeoutMs = 5_000) else null
                        document.status = res?.output?.lineSequence()?.take(20)?.joinToString("\n")?.take(800) ?: "Terminal/hexdump unavailable"
                    }
                }) { Text("Hexdump") }
            }
            else -> EditorAndroidSurface(
                modifier = Modifier.fillMaxSize(),
                createEditor = { ctx -> DroideCodeEditor(ctx, languageId, accessoryModifiers, codeStyle) },
                initializeEditor = { editor ->
                    editor.apply {
                        applyKeyboardMode(codingKeyboardMode)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        applyDroideTheme(theme)
                        document.viewport?.let { setTextSize(it.textSizeSp) }

                        setLineNumberEnabled(true)
                        setPinLineNumber(true)
                        setFirstLineNumberAlwaysVisible(true)
                        setHorizontalScrollBarEnabled(!effectiveWordWrap)
                        setInterceptParentHorizontalScrollIfNeeded(false); setWordwrap(effectiveWordWrap, true, false)
                        props.cacheRenderNodeForLongLines = !document.largeFileOptimized; configureDroideProfessionalEditorFeatures(document.largeFileOptimized)
                        setEditorLanguage(completionLanguage)
                        configureDroideSnippetVariables(this, files.root, path)
                        val signatureController = if (document.fullIntelligence) DroideSignatureHelpController(this, path, lsp, scope) else null
                        signatureControllerRef[0]?.release()
                        signatureControllerRef[0] = signatureController
                        val highlightController = if (document.fullIntelligence) DroideEditorHighlightController(this, path, languageId, lsp, scope, codeStyle) else null
                        highlightControllerRef[0]?.release()
                        highlightControllerRef[0] = highlightController

                        props.autoCompletionOnComposing = true
                        props.cancelCompletionNs = 25L * 1_000_000L
                        getComponent(EditorAutoCompletion::class.java).apply {
                            setCompletionWndPositionMode(EditorAutoCompletion.WINDOW_POS_MODE_AUTO)
                            setEnabledAnimation(false)
                        }
                        editorEvents.clear()
                        nativeContentSubscription = subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                            if (!applyingDocumentText) {
                                val changed = event.changedText.toString()
                                val applied = if (!document.canEdit || !acceptsMutations) false else when (event.action) {
                                    ContentChangeEvent.ACTION_INSERT -> document.captureInsert(event.changeStart.index, changed)
                                    ContentChangeEvent.ACTION_DELETE -> document.captureDelete(event.changeStart.index, changed)
                                    ContentChangeEvent.ACTION_SET_NEW_TEXT -> {
                                        document.captureNativeText(changed)
                                        true
                                    }
                                    else -> false
                                }
                                // Fail-safe for unexpected event semantics or a stale durable buffer.
                                if (!applied) document.captureNativeText(text.toString())
                                if (document.canEdit && acceptsFeatureUpdates && document.fullIntelligence) {
                                    signatureController?.onContentChanged(event.action, changed)
                                    highlightController?.schedule(document.content)
                                }
                            }
                        }
                        editorEvents.selection = subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
                            if (!applyingDocumentText) {
                                document.captureSelection(event.left.index, event.right.index); revealSelectionForActiveIme()
                                if (document.fullIntelligence) signatureController?.onSelectionChanged(event)
                            }
                        }
                        editorEvents.focus = subscribeEvent(EditorFocusChangeEvent::class.java) { event, _ ->
                            val ownsInput = event.isGainFocus && document.canEdit
                            accessoryInputFocus.onFocusChanged(accessoryFocusOwner, ownsInput)
                            if (!event.isGainFocus) accessoryModifiers.clear()
                        }
                        applyingDocumentText = true
                        try {
                            setText(document.content); highlightController?.installSafeBaseStyles(document.content)
                            setSelectionOffsets(document.selectionStart, document.selectionEnd, makeVisible = document.viewport == null)
                            document.viewport?.let(::restoreViewport)
                        } finally { applyingDocumentText = false }
                        lateinit var mutationBinding: EditorNativeMutationBinding
                        mutationBinding = EditorNativeMutationBinding(
                            owner = this,
                            isCurrent = { editorRef === this && document.nativeMutationBinding === mutationBinding &&
                                acceptsMutations && document.canEdit },
                            captureSettled = { settleWorkspaceMutation(); nativeCapture?.invoke() },
                            replaceAndPublish = { expectedText, replacement, publish ->
                                check(text.toString() == expectedText) { "Native editor changed after review" }
                                val selectionStart = document.selectionStart.coerceIn(0, replacement.length)
                                val selectionEnd = document.selectionEnd.coerceIn(0, replacement.length)
                                applyingDocumentText = true
                                try {

                                    if (expectedText != replacement) {
                                        text.beginBatchEdit()
                                        try { text.replace(0, text.length, replacement) }
                                        finally { text.endBatchEdit() }
                                    }
                                    check(text.toString() == replacement) { "Native editor did not accept the complete edit" }
                                    setSelectionOffsets(selectionStart, selectionEnd)
                                    publish()
                                    document.captureSelection(selectionStart, selectionEnd)
                                    lastAppliedRevision = document.revision

                                    signatureControllerRef[0]?.onContentChanged(ContentChangeEvent.ACTION_SET_NEW_TEXT, replacement)
                                    highlightControllerRef[0]?.installSafeBaseStyles(replacement)
                                    if (document.fullIntelligence) highlightControllerRef[0]?.schedule(replacement, immediate = true)
                                } catch (failure: Throwable) {
                                    // A partial native failure is real content, never a successful model-only edit.
                                    try {
                                        document.captureNativeText(text.toString())
                                        document.captureSelection(cursor.left, cursor.right)
                                        lastAppliedRevision = document.revision
                                        document.status = "Workspace edit failed; current editor text was preserved: ${failure.message}"
                                    } catch (captureFailure: Throwable) {
                                        if (captureFailure !== failure) failure.addSuppressed(captureFailure)
                                    }
                                    throw failure
                                } finally { applyingDocumentText = false }
                            },
                        )
                        document.nativeMutationBinding = mutationBinding
                        nativeCapture = {
                            check(!applyingDocumentText) { "Native editor mutation is still publishing" }
                            document.captureNativeText(text.toString())
                            document.captureSelection(cursor.left, cursor.right)
                            document.captureViewport(captureViewport())
                        }
                        bridge.yieldInputFocus = {
                            // This synchronous closure owns this exact view; it cannot clear a replacement.
                            if (editorRef === this && document.nativeMutationBinding === mutationBinding &&
                                !isReleased && hasFocus()) clearFocus()
                        }
                        bridge.nativeHandoff = EditorNativeHandoffBinding(
                            owner = this,
                            begin = ::beginProjectHandoff,
                            requireReady = {
                                check(!isReleased && nativeHandoffFailure == null && nativeCapture != null) {
                                    "Native editor capture/release failed before project publication"
                                }
                            },
                        )
                        applyReviewMode(document.reviewMode)
                        if (document.fullIntelligence) highlightController?.schedule(document.content, immediate = true)
                        lastAppliedRevision = document.revision
                        editorRef = this
                    }
                },
                updateEditor = update@ { view ->
                    if (!view.acceptsFeatureUpdates) return@update
                    view.applyDroideTheme(theme)
                    view.applyKeyboardMode(codingKeyboardMode)
                    view.applyCodeStyle(codeStyle); view.configureDroideProfessionalEditorFeatures(document.largeFileOptimized)
                    highlightControllerRef[0]?.updateCodeStyle(codeStyle, document.content)
                    view.applyReviewMode(document.reviewMode)
                    if (view.isWordwrap != effectiveWordWrap) view.setWordwrap(effectiveWordWrap, true, false)
                    view.setLineNumberEnabled(true)
                    view.setPinLineNumber(true)
                    view.setHorizontalScrollBarEnabled(!effectiveWordWrap)
                    view.setInterceptParentHorizontalScrollIfNeeded(false)
                    view.props.cacheRenderNodeForLongLines = !document.largeFileOptimized
                    if (view.editorLanguage !== completionLanguage) {
                        view.setEditorLanguage(completionLanguage)
                    }
                    if (document.fullIntelligence && signatureControllerRef[0] == null) {
                        signatureControllerRef[0] = DroideSignatureHelpController(view, path, lsp, scope)
                    }
                    if (document.fullIntelligence && highlightControllerRef[0] == null) {
                        highlightControllerRef[0] = DroideEditorHighlightController(view, path, languageId, lsp, scope, codeStyle)
                    }
                    if (lastAppliedRevision != document.revision) {
                        applyingDocumentText = true
                        try {
                            val viewport = view.captureViewport()
                            view.setText(document.content); highlightControllerRef[0]?.installSafeBaseStyles(document.content)
                            view.setSelectionOffsets(document.selectionStart, document.selectionEnd, makeVisible = false)
                            view.restoreViewport(viewport)
                        } finally { applyingDocumentText = false }
                        if (document.fullIntelligence) highlightControllerRef[0]?.schedule(document.content, immediate = true)
                        lastAppliedRevision = document.revision
                    }
                    editorRef = view
                    accessoryInputFocus.onFocusChanged(
                        accessoryFocusOwner,
                        view.hasFocus() && document.canEdit,
                    )
                },
                onReleaseEditor = { editor ->
                    try {
                        editor.nativeCapture?.invoke()
                    } finally {
                        if (bridge.nativeHandoff?.owner === editor) bridge.nativeHandoff = null
                        if (document.nativeMutationBinding?.owner === editor) document.nativeMutationBinding = null
                        if (editorRef === editor) {
                            bridge.yieldInputFocus = null
                            editorRef = null
                            clearEditorControllers()
                            accessoryModifiers.clear()
                            accessoryInputFocus.clearIf(accessoryFocusOwner)
                        }
                    }
                },
            )
            }
        }
    }
}

@Composable
private fun EditorAnalysisMessage(document: EditorDocument, kind: String, message: String) {
    if (message.isNotBlank() && document.dismissedAnalysisMessages[kind] != message) {
        EditorTransientStatus(message) { document.dismissedAnalysisMessages[kind] = message }
    }
}
