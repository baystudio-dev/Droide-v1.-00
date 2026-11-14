package com.baystudio.droide.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.*
import java.io.File
import kotlinx.coroutines.*

/** Read-only local reader: extracted text is never the editor's save/recovery buffer. */
@Composable
internal fun DocumentPreviewPane(workspaceIdentity: Any, path: String, resolveSource: () -> File) {
    val context = LocalContext.current.applicationContext
    val kind = remember(path) { DocumentPreviewPolicy.kind(path) }
    val owner = remember(workspaceIdentity, path) { Any() }
    val exitOwner = LocalExitUiWorkOwner.current
    var attempt by remember(owner) { mutableIntStateOf(0) }
    var snapshot by remember(owner) { mutableStateOf<File?>(null) }
    var opening by remember(owner) { mutableStateOf(true) }
    var loadingPage by remember(owner) { mutableStateOf(false) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var docx by remember(owner) { mutableStateOf<DocxTextReader.Result?>(null) }
    var result by remember(owner) { mutableStateOf<PdfPreviewPage?>(null) }
    var page by remember(owner) { mutableIntStateOf(0) }
    var pageCount by remember(owner) { mutableIntStateOf(0) }
    var textMode by remember(owner) { mutableStateOf(false) }
    var zoom by remember(owner) { mutableIntStateOf(1) }
    var paused by remember(owner) { mutableStateOf(false) }
    var openEpoch by remember(owner) { mutableIntStateOf(0) }
    var renderEpoch by remember(owner) { mutableIntStateOf(0) }

    DisposableEffect(owner) {
        onDispose { snapshot?.delete() }
    }
    LaunchedEffect(owner, attempt, paused) {
        currentCoroutineContext()[Job]?.let { exitOwner?.register(it) }
        val epoch = ++openEpoch
        val requestedAttempt = attempt
        val requestedPaused = paused
        var applicationEpoch: String? = null
        fun currentOpen() = epoch == openEpoch && attempt == requestedAttempt && paused == requestedPaused &&
            applicationEpoch?.let(ApplicationWorkAdmission::isCurrentOpen) == true
        snapshot?.delete(); snapshot = null
        result = null; docx = null; error = null; opening = !paused; loadingPage = false
        page = 0; pageCount = 0
        if (paused) return@LaunchedEffect
        var owned: File? = null
        try {
            applicationEpoch = ApplicationWorkAdmission.requireOpen()
            if (kind == DocumentPreviewPolicy.Kind.LEGACY_WORD) {
                error = "This Word format is not supported. Convert a copy to DOCX or PDF. DOC, DOCM and templates are not DOCX documents."
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                val job = currentCoroutineContext()
                owned = DocumentPreviewPolicy.snapshot(resolveSource(), File(context.cacheDir, "document-previews")) {
                    job.ensureActive()
                    if (!ApplicationWorkAdmission.isCurrentOpen(requireNotNull(applicationEpoch))) throw CancellationException("Application Exit")
                }
            }
            ensureActive()
            if (!currentOpen()) return@LaunchedEffect
            val copy = requireNotNull(owned)
            if (kind == DocumentPreviewPolicy.Kind.DOCX) {
                val parsed = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    DocxTextReader.read(copy) {
                        job.ensureActive()
                        if (!ApplicationWorkAdmission.isCurrentOpen(requireNotNull(applicationEpoch))) throw CancellationException("Application Exit")
                    }
                }
                ensureActive()
                if (!currentOpen()) return@LaunchedEffect
                docx = parsed
                copy.delete(); owned = null
            } else {
                snapshot = copy; owned = null
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            ensureActive()
            if (currentOpen()) error = failure.message?.take(240) ?: "This document cannot be previewed."
        }
        finally { owned?.delete(); if (epoch == openEpoch) opening = false }
    }

    LaunchedEffect(owner, snapshot, page, textMode, zoom, paused) {
        currentCoroutineContext()[Job]?.let { exitOwner?.register(it) }
        val epoch = ++renderEpoch
        val source = snapshot
        if (source == null) { loadingPage = false; return@LaunchedEffect }
        if (paused) { result = null; loadingPage = false; return@LaunchedEffect }
        val requestedPage = page
        val requestedText = textMode
        val requestedZoom = zoom
        val requestedAttempt = attempt
        var applicationEpoch: String? = null
        fun currentPage() = epoch == renderEpoch && snapshot === source && !paused &&
            page == requestedPage && textMode == requestedText && zoom == requestedZoom && attempt == requestedAttempt &&
            applicationEpoch?.let(ApplicationWorkAdmission::isCurrentOpen) == true
        result = null; error = null; loadingPage = true
            var pending: PdfPreviewPage? = null
            try {
                applicationEpoch = ApplicationWorkAdmission.requireOpen()
                pending = LocalPdfPreview.read(context, source, requestedPage, requestedText, requestedZoom)
                ensureActive()
                if (!currentPage()) return@LaunchedEffect
                val rendered = requireNotNull(pending)
                result = rendered; pageCount = rendered.pages; pending = null
            } catch (timeout: TimeoutCancellationException) {
                ensureActive()
                if (currentPage()) error = "PDF preview timed out. Try another page or reopen the document."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                ensureActive()
                if (currentPage()) error = failure.message?.take(240) ?: "This PDF cannot be previewed."
            }
            finally { pending?.bitmap?.recycle(); if (epoch == renderEpoch) loadingPage = false }
    }

    Column(Modifier.fillMaxSize()) {
        Text(if (kind == DocumentPreviewPolicy.Kind.DOCX) "DOCX · read-only text" else "Document preview · read-only",
            style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kind == DocumentPreviewPolicy.Kind.PDF) {
                TextButton(onClick = { textMode = false }) { Text("Pages") }
                TextButton(onClick = { textMode = true }) { Text("Text") }
                TextButton(onClick = { page-- }, enabled = page > 0 && !opening && !paused) { Text("Previous") }
                Text("${page + 1} / ${if (pageCount > 0) pageCount.toString() else "…"}", Modifier.padding(vertical = 12.dp))
                TextButton(onClick = { page++ }, enabled = pageCount > page + 1 && !opening && !paused) { Text("Next") }
                if (!textMode) TextButton(onClick = { zoom = if (zoom == 1) 2 else 1 }) { Text("Zoom ${zoom}×") }
            }
            if (kind != DocumentPreviewPolicy.Kind.LEGACY_WORD) TextButton(onClick = { paused = !paused }) { Text(if (paused) "Resume" else "Stop") }
            TextButton(onClick = { paused = false; attempt++ }) { Text("Reopen") }
        }
        if (kind == DocumentPreviewPolicy.Kind.DOCX) {
            DismissibleNotice("Main-body paragraphs and tables. Original page layout, images, headers and footnotes are not reproduced.", ownerKey = owner)
        }
        HorizontalDivider()
        when {
            opening || loadingPage -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(); Text(if (opening) "Opening locally…" else "Loading page locally…")
            }
            error != null -> DismissibleNotice(error.orEmpty(), color = MaterialTheme.colorScheme.error, ownerKey = owner)
            paused -> Text("Preview stopped.", Modifier.padding(16.dp))
            docx != null -> {
                val reading = requireNotNull(docx)
                if (reading.notice.isNotBlank()) DismissibleNotice(reading.notice, ownerKey = owner)
                DocumentPreviewText(reading.text.ifBlank { "No main-body text was found." })
            }
            result != null -> {
                val current = requireNotNull(result)
                if (textMode) {
                    if (!current.textSupported) Text("Text extraction requires Android 15 or newer in this preview. Use Pages to read this PDF.",
                        Modifier.padding(16.dp))
                    else {
                        if (current.truncated) DismissibleNotice("Text preview limited to 64,000 characters on this page.", ownerKey = owner)
                        DocumentPreviewText(current.text?.takeIf { it.isNotBlank() }
                            ?: "No extractable text on this page. It may be a scan; use Pages. OCR is not included.")
                    }
                } else current.bitmap?.let { bitmap ->
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val displayWidth = maxWidth * zoom
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
                            Image(bitmap.asImageBitmap(), contentDescription = "PDF page ${page + 1}",
                                modifier = Modifier.width(displayWidth).aspectRatio(bitmap.width.toFloat() / bitmap.height))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentPreviewText(text: String) {
    val chunks = remember(text) { text.chunked(8_000) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chunks) { chunk -> SelectionContainer { Text(chunk, style = MaterialTheme.typography.bodyMedium) } }
    }
}

