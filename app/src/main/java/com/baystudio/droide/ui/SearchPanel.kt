package com.baystudio.droide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.FileQueryPage
import com.baystudio.droide.core.TextSearchPage
import com.baystudio.droide.core.TextSearchOptions
import com.baystudio.droide.core.WorkspaceDocumentAuthority
import com.baystudio.droide.core.WorkspaceSearchInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext




@Composable
fun SearchPanel(
    files: FileRepository,
    documents: WorkspaceDocumentAuthority? = null,
    onSearchStart: () -> Unit = {},
    onOpen: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    val workspaceKey = files.root.absolutePath
    var query by rememberSaveable(workspaceKey) { mutableStateOf("") }
    var globQuery by rememberSaveable(workspaceKey) { mutableStateOf("**/*.py") }
    var includeGlob by rememberSaveable(workspaceKey) { mutableStateOf("") }
    var caseSensitive by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    var wholeWord by rememberSaveable(workspaceKey) { mutableStateOf(false) }
    var results by remember(workspaceKey) { mutableStateOf<TextSearchPage?>(null) }
    var globResults by remember(workspaceKey) { mutableStateOf<FileQueryPage?>(null) }
    var busy by remember(workspaceKey) { mutableStateOf(false) }
    var searchError by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var globBusy by remember(workspaceKey) { mutableStateOf(false) }
    val queryAdmitted = WorkspaceSearchInput.admitted(query)
    val filterAdmitted = WorkspaceSearchInput.admitted(includeGlob)
    val trimmedQuery = if (queryAdmitted) query.trim() else ""
    val trimmedFilter = if (filterAdmitted) includeGlob.trim() else ""

    var globError by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var textJob by remember(workspaceKey) { mutableStateOf<Job?>(null) }
    var globJob by remember(workspaceKey) { mutableStateOf<Job?>(null) }
    var textRequest by remember(workspaceKey) { mutableIntStateOf(0) }
    var patternRequest by remember(workspaceKey) { mutableIntStateOf(0) }
    val scope = rememberWorkspaceCoroutineScope()
    fun invalidateText() {
        textRequest++; textJob?.cancel(); results = null; searchError = null; busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Text Search", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(query, {
                if (query != it) invalidateText()
                query = it
            }, Modifier.fillMaxWidth(),
                label = { Text("Search text") }, placeholder = { Text("minimum 2 characters") }, singleLine = true,
                isError = (!queryAdmitted || trimmedQuery.length > 256))
            if ((!queryAdmitted || trimmedQuery.length > 256)) Text("Search text must be at most 256 characters", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = caseSensitive, onClick = { invalidateText(); caseSensitive = !caseSensitive }, label = { Text("Match case") })
                FilterChip(selected = wholeWord, onClick = { invalidateText(); wholeWord = !wholeWord }, label = { Text("Whole word") })
            }
            OutlinedTextField(includeGlob, {
                if (includeGlob != it) invalidateText()
                includeGlob = it
            }, Modifier.fillMaxWidth(), label = { Text("Files to include (optional)") },
                placeholder = { Text("**/*.kt") }, singleLine = true, isError = (!filterAdmitted || trimmedFilter.length > 256))
            if ((!filterAdmitted || trimmedFilter.length > 256)) Text("File filter must be at most 256 characters", color = MaterialTheme.colorScheme.error)
            searchError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val request = query
                val options = TextSearchOptions(caseSensitive, wholeWord, includeGlob)
                val serial = ++textRequest
                busy = true; searchError = null; results = null
                textJob = scope.launch {
                    try {
                        onSearchStart()
                        val page = files.searchPage(request, options = options, documents = documents)
                        if (serial == textRequest) results = page
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (serial == textRequest) searchError = "Search failed: ${error.message ?: error::class.java.simpleName}"
                    } finally { if (serial == textRequest) { busy = false; textJob = null } }
                }
            }, enabled = queryAdmitted && filterAdmitted && trimmedQuery.length in 2..256 && trimmedFilter.length <= 256 && !busy) { Text(if (busy) "Searching…" else "Search") }
            LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                results?.let { page ->
                    if (page.scanLimitReached) item { Text("Search stopped after ${page.scannedEntries} entries; results may be incomplete. " +
                        (page.scanStopReasons.map { it.name.lowercase() } + page.searchStopReasons.map { it.name.lowercase() }).joinToString()) }
                    else if (page.resultLimitReached) item { Text("Showing first ${page.hits.size} matches; refine the query for more.") }
                    if (page.unreadableFiles > 0) item { Text("${page.unreadableFiles} unreadable file(s) skipped.") }
                    if (page.skippedLargeFiles > 0) item { Text("${page.skippedLargeFiles} file(s) over 20 MiB skipped.") }
                    if (page.skippedDirtyBuffers > 0) item { Text("${page.skippedDirtyBuffers} unsaved buffer(s) unavailable for search; saved copies were excluded.") }
                    if (page.hits.isEmpty()) item { Text("No matches in scanned files (first 20 folder levels).", style = MaterialTheme.typography.bodySmall) }
                }
                items(results?.hits.orEmpty(), key = { "${it.path}:${it.line}:${it.column}" }) { hit ->
                    FileIdentityLabel(
                        path = hit.path,
                        label = "${hit.path}:${hit.line}:${hit.column}: ${hit.excerpt}".take(480),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(hit.path, hit.line, hit.column) }.padding(vertical = 4.dp),
                        iconSize = 14.dp,
                        monospaced = true,
                    )
                }
            }
        }
        HorizontalDivider()
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("File Pattern", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(globQuery, {
                if (globQuery != it) { patternRequest++; globJob?.cancel(); globResults = null; globError = null; globBusy = false }
                globQuery = it
            }, Modifier.fillMaxWidth(), label = { Text("Glob pattern") }, placeholder = { Text("**/*.py") }, singleLine = true,
                isError = globQuery.length > 256)
            if (globQuery.length > 256) Text("Pattern must be at most 256 characters", color = MaterialTheme.colorScheme.error)
            globError?.let { DismissibleNotice(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val request = globQuery
                val serial = ++patternRequest
                globBusy = true; globError = null; globResults = null
                globJob = scope.launch {
                    try {
                        val page = withContext(Dispatchers.IO) { files.globPage(request) }
                        if (serial == patternRequest) globResults = page
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (serial == patternRequest) globError = "File search failed: ${error.message ?: error::class.java.simpleName}"
                    } finally { if (serial == patternRequest) { globBusy = false; globJob = null } }
                }
            }, enabled = globQuery.length <= 256 && globQuery.isNotBlank() && !globBusy) { Text(if (globBusy) "Searching…" else "Find files") }
            LazyColumn(Modifier.heightIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                globResults?.let { page ->
                    if (page.scanLimitReached) item { Text("Search stopped after ${page.scannedEntries} entries; results may be incomplete. " +
                        (page.scanStopReasons.map { it.name.lowercase() } + page.searchStopReasons.map { it.name.lowercase() }).joinToString()) }
                    else if (page.resultLimitReached) item { Text("Showing first ${page.paths.size} files; narrow the pattern for more.") }
                    if (page.paths.isEmpty()) item { Text("No files found in the scanned folders (first 20 levels).", style = MaterialTheme.typography.labelSmall) }
                }
                items(globResults?.paths.orEmpty(), key = { it }) { path ->
                    FileIdentityLabel(
                        path = path, label = path.take(220),
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(path, 1, 1) }.padding(vertical = 4.dp),
                        iconSize = 14.dp, monospaced = true,
                    )
                }
            }
        }
    }
}
