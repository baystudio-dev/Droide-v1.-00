package com.baystudio.droide.ui

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.baystudio.droide.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FileExplorer(
    files: FileRepository,
    state: ExplorerWorkspaceState,
    refreshController: ExplorerRefreshController,
    refreshLease: ExplorerRefreshController.UiLease,
    onOpen: (String) -> Unit,
    canMutatePath: (String) -> Boolean = { true },
    onRenamed: (String, String) -> Unit = { _, _ -> },
    onDeleted: (String) -> Unit = {},
    safLinked: Boolean = false,
    onReconcileSaf: suspend () -> String? = { null },
    imports: WorkspaceImportController? = null,
    onBeforeImport: () -> Unit = {},
    onExport: ((path: String, directory: Boolean) -> Unit)? = null,
    onExportStatus: (() -> Unit)? = null,
    exportStatusLabel: String? = null,
    onDismissExportStatus: (() -> Unit)? = null,
    preparePathMutation: suspend (List<String>) -> Unit = {},
) {
    key(files.root.absolutePath, state) {
        WorkspaceFileExplorer(files, state, refreshController, refreshLease, onOpen, canMutatePath, onRenamed, onDeleted, safLinked, onReconcileSaf, imports, onBeforeImport, onExport, onExportStatus, exportStatusLabel, onDismissExportStatus, preparePathMutation)
    }
}

@Composable
private fun WorkspaceFileExplorer(
    files: FileRepository,
    state: ExplorerWorkspaceState,
    refreshController: ExplorerRefreshController,
    refreshLease: ExplorerRefreshController.UiLease,
    onOpen: (String) -> Unit,
    canMutatePath: (String) -> Boolean,
    onRenamed: (String, String) -> Unit,
    onDeleted: (String) -> Unit,
    safLinked: Boolean,
    onReconcileSaf: suspend () -> String?,
    imports: WorkspaceImportController?,
    onBeforeImport: () -> Unit,
    onExport: ((String, Boolean) -> Unit)?,
    onExportStatus: (() -> Unit)?,
    exportStatusLabel: String?,
    onDismissExportStatus: (() -> Unit)?,
    preparePathMutation: suspend (List<String>) -> Unit,
) {
    val scope = rememberWorkspaceCoroutineScope()
    val ctx = LocalContext.current
    val configuration = LocalConfiguration.current
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val entryStyle = explorerEntryStyle(portrait)
    val treeIconSize = entryStyle.iconSize
    val treeActionSize = entryStyle.actionSize
    val treeActionIconSize = entryStyle.actionIconSize
    val treeLabelStyle = entryStyle.label
    val searchResultMinHeight = if (portrait) 40.dp else 32.dp
    val workspaceKey = files.root.absolutePath
    val children = state.children
    val treeListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.treeFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.treeFirstVisibleItemScrollOffset,
    )
    val searchListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.searchFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.searchFirstVisibleItemScrollOffset,
    )
    var searchRevision by remember { mutableIntStateOf(0) }
    var mutationBusy by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var searchBusy by remember(workspaceKey) { mutableStateOf(false) }
    var err by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var mutationNotice by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var syncMessage by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var syncBusy by remember(workspaceKey) { mutableStateOf(false) }
    var showNewFile by remember(workspaceKey) { mutableStateOf(false) }
    var newDirectory by rememberSaveable(workspaceKey) { mutableStateOf("") }
    var toolbarMenu by remember(workspaceKey) { mutableStateOf(false) }
    var showMove by remember(workspaceKey) { mutableStateOf<FileEntry?>(null) }
    var moveDirectory by remember(workspaceKey) { mutableStateOf("") }
    var movingRecoveredFile by remember(workspaceKey) { mutableStateOf(false) }
    var showRecovery by remember(workspaceKey) { mutableStateOf(false) }
    var recoveryEntries by remember(workspaceKey) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var recoveryCursor by remember(workspaceKey) { mutableStateOf<DirectoryPageCursor?>(null) }
    var recoveryPageRequest by remember(workspaceKey) { mutableIntStateOf(0) }
    var recoveryLoaded by remember(workspaceKey) { mutableStateOf(false) }
    var recoveryBusy by remember(workspaceKey) { mutableStateOf(false) }
    var recoveryError by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var recoveryNotice by remember(workspaceKey) { mutableStateOf<String?>(null) }
    val recoveryLoadVersion = remember(workspaceKey) { intArrayOf(0) }
    val sortPreferences = remember(ctx) { ctx.getSharedPreferences("explorer_preferences", android.content.Context.MODE_PRIVATE) }
    val sortPreferenceKey = remember(workspaceKey) {
        "sort_" + java.security.MessageDigest.getInstance("SHA-256").digest(workspaceKey.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    var showNewFolder by remember(workspaceKey) { mutableStateOf(false) }
    var showRename by remember(workspaceKey) { mutableStateOf<String?>(null) }
    var showDelete by remember(workspaceKey) { mutableStateOf<FileEntry?>(null) }
    var actionTarget by remember(workspaceKey) { mutableStateOf<FileEntry?>(null) }
    var newName by remember(workspaceKey) { mutableStateOf("") }
    var newLang by remember(workspaceKey) { mutableStateOf("kotlin") }
    val importState = imports?.state?.collectAsState()?.value ?: WorkspaceImportState(closed = true)
    val importRecoveryEnabled = imports != null && !importState.closed && !importState.busy && !mutationBusy && !syncBusy
    val importRecovery = imports?.let { controller ->
        rememberProjectCopyRecoveryController(controller, workspaceKey,
            ProjectCopyJournal.Scope(ProjectCopyJournal.Kind.EXPLORER_IMPORT), importRecoveryEnabled,
            importCopyRecoveryActions(controller))
    }
    val paging = state.loadingPages
    val treeMode = explorerTreeMode(state.query)

    suspend fun userDirectory(requested: String): String = withContext(Dispatchers.IO) {
        val normalized = ExplorerFilePolicy.requireUserTarget(files.root, requested)
        require(files.resolveChecked(normalized).isDirectory) { "Destination folder no longer exists; select a project folder again" }
        normalized
    }

    fun selectedDirectory(): String {
        state.sanitizeInternalSelection()
        return state.activeDirectory
    }

    suspend fun requireRecoveryRoot(): Boolean = withContext(Dispatchers.IO) {
        val directory = java.io.File(files.root, ".droide")
        require(!PathSecurity.isSymbolicLink(directory)) { "Internal data is linked elsewhere; recover your files from the terminal" }
        require(ExplorerFilePolicy.normalizeProjectPath(files.root, ".droide") == ".droide") { "Internal data path changed; refresh Explorer" }
        if (!directory.exists()) false else {
            require(directory.isDirectory) { "Internal data path is not a folder" }
            true
        }
    }

    suspend fun loadRecoveryPage(reset: Boolean, version: Int) {
        if (recoveryBusy) return
        recoveryBusy = true
        recoveryError = null
        try {
            if (!requireRecoveryRoot()) {
                if (version != recoveryLoadVersion[0] || !showRecovery) return
                recoveryEntries = emptyList(); recoveryCursor = null; recoveryLoaded = true
                return
            }
            val after = if (reset) null else recoveryCursor
            val page = files.listFilesPage(".droide", after, pageSize = 100)
            if (!showRecovery || version != recoveryLoadVersion[0]) return
            val known = if (reset) emptyList() else recoveryEntries
            val next = (known + page.entries.filter { ExplorerRecoveryPolicy.canRecover(it.path) }).distinctBy { it.path }
            recoveryEntries = next.take(250)
            recoveryCursor = if (next.size >= 250) null else page.nextCursor
            recoveryNotice = if (next.size > 250 || (next.size >= 250 && page.nextCursor != null)) "Showing the first 250 candidates. Move your files, then reopen this list for more." else null
            recoveryLoaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (version == recoveryLoadVersion[0] && showRecovery) {
                recoveryError = failure.message ?: "Could not list misplaced files"
                recoveryLoaded = true
            }
        } finally { if (version == recoveryLoadVersion[0]) recoveryBusy = false }
    }

    fun refreshLocalTreeAfterMutation() {
        state.requestRefresh()
        refreshController.requestRefresh(refreshLease, state.refreshToken)
    }

    fun loadMore(parent: String) { refreshController.requestMore(refreshLease, parent) }

    fun openPath(path: String) {
        if (ExplorerFilePolicy.isDroideInternalPath(path)) { err = "Internal Droide files are hidden; use Recover misplaced files for your own files"; return }
        state.selectEntry(FileEntry(path, false, 0L, 0L))
        runUiCatching(onError = { err = "Could not open file: ${it.message ?: "unavailable"}" }) { onOpen(path) }
    }

    fun mutate(block: suspend () -> Unit) {
        if (mutationBusy) return
        mutationNotice = null
        state.importNotice = null
        mutationBusy = true
        dialogError = null
        scope.launchUiCatching(onError = { dialogError = it.message ?: "File operation failed" }) {
            try { block() } finally { mutationBusy = false }
        }
    }

    suspend fun performMove(observation: FileRepository.MoveObservation, target: FileEntry, historyDirection: Int = 0) {
        var failure: Throwable? = null
        try { check(files.moveObserved(observation)) { "Move failed" } }
        catch (error: Throwable) { failure = error }
        if (observation.committed) {
            state.moved(observation.from, target.copy(path = observation.to))
            state.recordMove(if (failure == null && observation.failure == null && !movingRecoveredFile) observation.undo else null, historyDirection)
            state.moveNotice = (failure ?: observation.failure)?.let {
                "Moved to /${observation.to}. Workspace update incomplete: ${it.message ?: "refresh to check synchronization"}"
            }
            showMove = null
            try { onRenamed(observation.from, observation.to) }
            catch (error: Exception) {
                state.moveNotice = "Moved to /${observation.to}. Editor refresh incomplete: ${error.message}"
                state.recordMove(null, 0)
                if (failure == null) failure = error
            }
            refreshLocalTreeAfterMutation(); searchRevision++
        } else if (observation.uncertain) {
            // Do not guess a remap when an external writer changed the observed object or parents.
            state.selectRoot(); state.recordMove(null, 0)
            state.moveNotice = "Move needs review. Inspect /${observation.from} and /${observation.to}; existing data was preserved."
            showMove = null
            refreshLocalTreeAfterMutation(); searchRevision++
        }
        failure?.let { if (it is CancellationException || it !is Exception || !observation.committed) throw it }
    }

    fun replayMove(redo: Boolean) {
        val token = (if (redo) state.redoMoves else state.undoMoves).lastOrNull() ?: return
        mutate {
            try {
                preparePathMutation(listOf(token.from, token.to))
                check(canMutatePath(token.from) && canMutatePath(token.to)) { "Save or discard unsaved editor changes before moving this path" }
                val target = withContext(Dispatchers.IO) {
                    val file = files.resolveChecked(token.from)
                    FileEntry(token.from, file.isDirectory, file.length(), file.lastModified())
                }
                movingRecoveredFile = false
                performMove(files.observeUndo(token), target, if (redo) 1 else -1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.moveNotice = failure.message ?: "Could not undo Move; existing data was preserved" }
        }
    }

    fun refresh() { scope.launch {
        mutationNotice = null
        state.importNotice = null
        if (safLinked) {
            syncBusy = true
            syncMessage = "Synchronizing linked folder…"
            try { syncMessage = onReconcileSaf() ?: "Linked folder unavailable" }
            catch (cancelled: CancellationException) { syncMessage = "Linked folder sync pending · refresh again"; throw cancelled }
            catch (failure: Exception) { syncMessage = "Linked folder sync pending/conflict: ${failure.message ?: "unknown error"}" }
            finally { syncBusy = false }
        }
        state.requestRefresh()
    } }

    fun toggleDirectory(entry: FileEntry) {
        state.selectEntry(entry)
        val open = entry.path in state.expandedPaths
        if (open) {
            state.collapseDirectory(entry.path)
            if (state.activeDirectory == entry.path || state.activeDirectory.startsWith("${entry.path}/")) {
                state.activeDirectory = entry.path.substringBeforeLast('/', "")
            }
        }
        else {
            state.expandedPaths = (state.expandedPaths + entry.path).distinct()
            refreshController.requestExpansion(refreshLease, entry.path)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticket = state.pendingImportTicket
        state.pendingImportTicket = null
        if (uri != null) runUiCatching(onError = { err = it.message ?: "Cannot start file import" }) {
            if (ticket == null || imports == null) err = "Import destination changed; select a project folder and import again"
            else {
                // Capture the native editor now; the runtime's repository validates the actual destination when
                // publication begins. No view callback travels with the copy.
                onBeforeImport()
                when (imports.submit(ticket, uri)) {
                    WorkspaceImportAdmission.ACCEPTED -> { err = null; mutationNotice = null; state.importNotice = null }
                    WorkspaceImportAdmission.BUSY -> err = "A file import is already running"
                    WorkspaceImportAdmission.STALE, WorkspaceImportAdmission.CLOSED ->
                        err = "Import workspace changed; select a project folder and import again"
                }
            }
        }
    }
    LaunchedEffect(imports, importState.result) {
        val result = importState.result ?: return@LaunchedEffect
        if (state.lastImportResult === result) return@LaunchedEffect
        state.lastImportResult = result
        // Directory refresh owns its loading error only. The bounded import outcome must survive that
        // refresh and a detached/recreated pane, including cleanup warnings.
        state.importNotice = result.message
        if (result.status == WorkspaceImportResultStatus.SYNC_PENDING) {
            result.relativePath?.let { path -> state.selectEntry(FileEntry(path, false, result.bytes ?: 0L, 0L)) }
        }
        state.requestRefresh()
        searchRevision++
    }
    LaunchedEffect(imports) {
        state.pendingImportTicket?.let { ticket ->
            if (imports?.isCurrentPicker(ticket) != true) state.pendingImportTicket = null
        }
    }

    LaunchedEffect(workspaceKey) {
        state.sanitizeInternalSelection()
        if (!state.sortPreferenceLoaded) {
            val saved = sortPreferences.getString(sortPreferenceKey, null)
            ExplorerSortOrder.entries.firstOrNull { it.name == saved }?.let(state::changeSortOrder)
            state.sortPreferenceLoaded = true
        }
    }
    LaunchedEffect(showRecovery, recoveryLoaded, recoveryPageRequest) {
        val version = ++recoveryLoadVersion[0]
        recoveryBusy = false
        if (showRecovery && (!recoveryLoaded || recoveryPageRequest > 0)) {
            loadRecoveryPage(reset = recoveryPageRequest == 0, version = version)
        }
    }
    LaunchedEffect(treeListState) {
        snapshotFlow { treeListState.firstVisibleItemIndex to treeListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.treeFirstVisibleItemIndex = index
                state.treeFirstVisibleItemScrollOffset = offset
            }
    }
    LaunchedEffect(searchListState) {
        snapshotFlow { searchListState.firstVisibleItemIndex to searchListState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                state.searchFirstVisibleItemIndex = index
                state.searchFirstVisibleItemScrollOffset = offset
            }
    }
    SideEffect { refreshController.updateTreeScope(refreshLease) }
    DisposableEffect(refreshController, refreshLease) {
        onDispose { refreshController.updateViewport(refreshLease, emptySet()) }
    }
    LaunchedEffect(treeListState, treeMode, refreshController, refreshLease) {
        if (!treeMode) { refreshController.updateViewport(refreshLease, emptySet()); return@LaunchedEffect }
        snapshotFlow { explorerViewportPages(state, treeListState.layoutInfo.visibleItemsInfo.map { it.key.toString() }.toSet()) }
            .collect { pages -> refreshController.updateViewport(refreshLease, pages) }
    }
    LaunchedEffect(workspaceKey, state.refreshToken, state.sortPreferenceLoaded, refreshController, refreshLease) {
        if (state.sortPreferenceLoaded) refreshController.requestRefresh(refreshLease, state.refreshToken)
    }
    LaunchedEffect(workspaceKey, state.query, state.refreshToken, searchRevision) {
        val requestQuery = state.query
        val clean = cleanedExplorerQuery(requestQuery)
        if (clean.length < 2) {
            state.searchResults = emptyList(); state.searchNotice = null; searchBusy = false
            return@LaunchedEffect
        }
        searchBusy = true
        try {
            delay(220)
            val page = files.globPage("**/*$clean*", 200)
            if (state.query == requestQuery) {
                state.searchResults = page.paths.filterNot(ExplorerFilePolicy::isDroideInternalPath)
                state.searchNotice = when {
                    page.scanLimitReached -> "Scan stopped after ${page.scannedEntries} entries; results may be incomplete."
                    page.resultLimitReached -> "Showing first ${page.paths.size} files; narrow the name for more."
                    else -> null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (state.query == requestQuery) {
                state.searchResults = emptyList()
                state.searchNotice = "File search failed: ${failure.message ?: failure::class.java.simpleName}"
            }
        } finally { if (state.query == requestQuery) searchBusy = false }
    }
    LaunchedEffect(showRename) { newName = showRename?.substringAfterLast('/') ?: newName }

    LaunchedEffect(state.pendingReveal, state.pageGeneration, state.loadedRefreshToken,
        state.children.toMap(), state.moreAfter.toMap(), state.loadingPages.toMap(), state.refreshOutcome) {
        val target = state.pendingReveal ?: return@LaunchedEffect
        if (state.refreshOutcome == ExplorerRefreshOutcome.PENDING) return@LaunchedEffect
        // Resolve each ancestor through the normal paged loader. Never invent entries or bypass
        // stale-page guards.
        var parent = ""
        for (segment in target.split('/')) {
            if (state.loadingPages[parent] == state.pageGeneration) return@LaunchedEffect
            if (state.pageFreshness[parent]?.failure != null) return@LaunchedEffect
            val path = if (parent.isEmpty()) segment else "$parent/$segment"
            if (state.children[parent].orEmpty().none { it.path == path }) {
                if (parent !in state.children) refreshController.requestExpansion(refreshLease, parent)
                else if (state.moreAfter[parent] != null) loadMore(parent)
                else state.pendingReveal = null
                return@LaunchedEffect
            }
            parent = path
        }
        val rows = flattenExplorerRows(state.children, state.expandedPaths, state.moreAfter)
        val index = rows.indexOfFirst { it.entry?.path == target }
        if (index >= 0) {

            treeListState.scrollToItem(index)
            if (state.pendingReveal == target) state.pendingReveal = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactToolbar = maxWidth < 320.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        val treeWidth = maxWidth
        val treeReservedWidth = entryStyle.reservedWidth
        val canImport = !importState.busy && !importState.closed && state.pendingImportTicket == null
        val importFile: () -> Unit = {
            runUiCatching(onError = { state.pendingImportTicket = null; err = "Cannot open file picker: ${it.message ?: "unavailable"}" }) {
                state.pendingImportTicket = checkNotNull(imports).capturePicker(selectedDirectory())
                importLauncher.launch(arrayOf("*/*"))
            }
        }
        val newFile: () -> Unit = { dialogError = null; newName = ""; newDirectory = selectedDirectory(); showNewFile = true }
        val newFolder: () -> Unit = { dialogError = null; newName = ""; newDirectory = selectedDirectory(); showNewFolder = true }
        Column(Modifier.fillMaxSize().background(DroideColors.Surface)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = {
                    if (state.query != it) {
                        state.searchResults = emptyList(); state.searchNotice = null
                        state.searchFirstVisibleItemIndex = 0; state.searchFirstVisibleItemScrollOffset = 0
                        scope.launch { searchListState.scrollToItem(0) }
                    }
                    state.query = it
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                placeholder = { Text("Search files") },
                singleLine = true,
                leadingIcon = { if (searchBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp) else Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    when {
                        state.query.isNotBlank() -> IconButton(onClick = { state.query = "" }) { Icon(Icons.Default.Close, "Clear file search", Modifier.size(17.dp)) }
                    }
                },
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = state::selectRoot, contentPadding = PaddingValues(horizontal = 5.dp)) { Text("/", style = MaterialTheme.typography.labelSmall) }
                Text(state.activeDirectory.ifBlank { "Project root" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compactToolbar) {
                    IconButton(onClick = importFile, enabled = canImport, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Upload, "Import file", Modifier.size(18.dp))
                    }
                    IconButton(onClick = newFile, Modifier.size(34.dp)) { Icon(Icons.Default.NoteAdd, "New file", Modifier.size(18.dp)) }
                    IconButton(onClick = newFolder, Modifier.size(34.dp)) { Icon(Icons.Default.CreateNewFolder, "New folder", Modifier.size(18.dp)) }
                    IconButton(onClick = ::refresh, Modifier.size(34.dp), enabled = !syncBusy) { Icon(Icons.Default.Refresh, "Refresh Explorer and linked folder", Modifier.size(18.dp)) }
                }
                Box {
                    IconButton(onClick = { toolbarMenu = true }, Modifier.size(34.dp)) { Icon(Icons.Default.MoreVert, "Explorer options", Modifier.size(18.dp)) }
                    DropdownMenu(expanded = toolbarMenu, onDismissRequest = { toolbarMenu = false }) {
                        if (compactToolbar) {
                            DropdownMenuItem(text = { Text("Import file") }, enabled = canImport,
                                onClick = { toolbarMenu = false; importFile() })
                            DropdownMenuItem(text = { Text("New file") }, onClick = { toolbarMenu = false; newFile() })
                            DropdownMenuItem(text = { Text("New folder") }, onClick = { toolbarMenu = false; newFolder() })
                            DropdownMenuItem(text = { Text("Refresh Explorer and linked folder") }, enabled = !syncBusy,
                                onClick = { toolbarMenu = false; refresh() })
                            HorizontalDivider()
                        }
                        DropdownMenuItem(text = { Text("Undo Move") }, enabled = !mutationBusy && state.undoMoves.isNotEmpty(),
                            leadingIcon = { Icon(Icons.Default.Undo, null) }, onClick = { toolbarMenu = false; replayMove(false) })
                        DropdownMenuItem(text = { Text("Redo Move") }, enabled = !mutationBusy && state.redoMoves.isNotEmpty(),
                            leadingIcon = { Icon(Icons.Default.Redo, null) }, onClick = { toolbarMenu = false; replayMove(true) })
                        HorizontalDivider()
                        onExport?.let { export ->
                            DropdownMenuItem(text = { Text("Export all") },
                                leadingIcon = { Icon(Icons.Default.Download, null, Modifier.size(16.dp)) },
                                onClick = { toolbarMenu = false; export("", true) })
                            HorizontalDivider()
                        }
                        onExportStatus?.let { status ->
                            DropdownMenuItem(text = { Text("Export status") }, onClick = { toolbarMenu = false; status() })
                        }
                        ExplorerSortOrder.entries.forEach { order ->
                            val label = when (order) {
                                ExplorerSortOrder.NAME_ASC -> "Name · A to Z"
                                ExplorerSortOrder.NAME_DESC -> "Name · Z to A"
                                ExplorerSortOrder.TYPE -> "File type"
                                ExplorerSortOrder.MODIFIED_DESC -> "Modified · newest first"
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                leadingIcon = { if (state.sortOrder == order) Icon(Icons.Default.Check, null, Modifier.size(16.dp)) },
                                onClick = { state.changeSortOrder(order); sortPreferences.edit { putString(sortPreferenceKey, order.name) }; toolbarMenu = false },
                            )
                        }
                        HorizontalDivider()
                        ProjectCopyRecoveryMenuItem(imports = true, enabled = importRecoveryEnabled, onOpen = {
                            toolbarMenu = false
                            importRecovery?.open()
                        })
                        DropdownMenuItem(text = { Text("Recover misplaced files") }, onClick = {
                            toolbarMenu = false; recoveryError = null; recoveryEntries = emptyList(); recoveryCursor = null
                            recoveryPageRequest = 0; recoveryLoaded = false; recoveryNotice = null; showRecovery = true
                        })
                    }
                }
            }
            if (safLinked) Text(syncMessage ?: "Linked folder · Refresh reconciles external and terminal changes", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = if (syncMessage?.contains("pending/conflict") == true) MaterialTheme.colorScheme.error else DroideColors.Muted)
            if (exportStatusLabel != null && onExportStatus != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onExportStatus, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(exportStatusLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                        Text("Details", style = MaterialTheme.typography.labelSmall)
                    }
                    onDismissExportStatus?.let { dismiss ->
                        IconButton(onClick = dismiss) {
                            Icon(Icons.Default.Close, "Dismiss export status", Modifier.size(18.dp))
                        }
                    }
                }
            }
            HorizontalDivider(color = DroideColors.Border)
            importState.active?.let { state ->
                val progress = state.progress
                val label = when {
                    state.cancelling -> "Cancelling import…"
                    progress.phase == StreamWritePhase.PREPARING -> "Preparing ${state.displayName}…"
                    progress.phase == StreamWritePhase.COPYING -> "Importing ${state.displayName}"
                    progress.phase == StreamWritePhase.COMMITTING -> "Committing ${state.displayName}…"
                    else -> "Finishing import safely…"
                }
                Column(
                    Modifier.fillMaxWidth().background(DroideColors.Surface2).padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val total = progress.totalBytes
                            val detail = if (total != null && total > 0L) {
                                val percent = ((progress.completedBytes.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0).toInt()
                                "$percent% · ${formatTransferBytes(progress.completedBytes)} / ${formatTransferBytes(total)}"
                            } else if (progress.completedBytes > 0L) {
                                "${formatTransferBytes(progress.completedBytes)} copied"
                            } else {
                                "Checking source and available storage"
                            }
                            Text(detail, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                        }
                        if (progress.cancellable && !state.cancelling) {
                            TextButton(
                                onClick = {
                                    imports?.cancel()
                                },
                            ) { Text("Cancel") }
                        }
                    }
                    if (progress.phase == StreamWritePhase.COPYING && progress.fraction != null && progress.completedBytes > 0L) {
                        LinearProgressIndicator(progress = { progress.fraction!! }, modifier = Modifier.fillMaxWidth())
                    } else {

                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                HorizontalDivider(color = DroideColors.Border)
            }
            (err ?: state.importNotice ?: mutationNotice)?.let { DismissibleNotice(it, color = DroideColors.Error, ownerKey = state) }
            state.moveNotice?.let { DismissibleNotice(it, color = DroideColors.Warning, ownerKey = state) }
            state.refreshNotice?.let { DismissibleNotice(it, ownerKey = state) }

            if (!treeMode) {
                LazyColumn(Modifier.fillMaxSize(), state = searchListState) {
                    state.searchNotice?.let { notice -> item { DismissibleNotice(notice, ownerKey = state, color = if (notice.startsWith("File search failed")) DroideColors.Error else DroideColors.Muted) } }
                    items(state.searchResults.filterNot(ExplorerFilePolicy::isDroideInternalPath), key = { it }) { path ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = searchResultMinHeight).clickable { openPath(path) }.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FileTypeBrandIcon(path = path, size = treeIconSize)
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(path.substringAfterLast('/'), style = treeLabelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(path, style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (!searchBusy && state.searchResults.isEmpty() && state.searchNotice?.startsWith("File search failed") != true) item { Text("No matching files in the first 20 folder levels", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                }
            } else {
                val rows = flattenExplorerRows(children, state.expandedPaths, state.moreAfter)
                    .filterNot { row -> ExplorerFilePolicy.isDroideInternalPath(row.entry?.path ?: row.parent) }
                LazyColumn(Modifier.fillMaxSize(), state = treeListState) {
                    items(rows, key = { row -> row.entry?.let { "entry:${it.path}" } ?: "more:${row.parent}" }) { row ->
                        val entry = row.entry
                        if (entry == null) {
                            TextButton(
                                onClick = { loadMore(row.parent) },
                                enabled = paging[row.parent] != state.pageGeneration,
                                modifier = Modifier.padding(start = 8.dp + explorerIndent(row.depth, treeWidth, treeReservedWidth)),
                            ) { Text(if (paging[row.parent] == state.pageGeneration) "Loading…" else "Load more files in ${row.parent.ifBlank { "/" }}") }
                        } else {
                            ExplorerEntryRow(entry, row.depth, expanded = entry.path in state.expandedPaths,
                                selected = state.selectedPath == entry.path, style = entryStyle,
                                onOpen = { if (entry.isDir) toggleDirectory(entry) else openPath(entry.path) },
                                onActions = { actionTarget = entry })
                        }
                    }
                    if (rows.isEmpty()) item { Text("Empty project", Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted) }
                }
            }
        }
    }

    importRecovery?.let { controller ->
        ProjectCopyRecoverySheet(controller, imports = true, projectKey = workspaceKey, enabled = importRecoveryEnabled)
    }

    actionTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = {
                if (target.isDir) Text(target.path.substringAfterLast('/'))
                else FileIdentityLabel(target.path, target.path.substringAfterLast('/'), iconSize = 18.dp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target.isDir) Text(target.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    else FileIdentityLabel(target.path, target.path, iconSize = 15.dp, monospaced = true)
                    onExport?.let { export ->
                        OutlinedButton(onClick = { actionTarget = null; export(target.path, target.isDir) }) {
                            Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (target.isDir) "Export folder" else "Export file")
                        }
                    }
                }
            },
            confirmButton = { Row {
                TextButton(onClick = { dialogError = null; moveDirectory = target.path.substringBeforeLast('/', ""); movingRecoveredFile = false; showMove = target; actionTarget = null }) { Text("Move to…") }
                TextButton(onClick = { dialogError = null; showRename = target.path; actionTarget = null }) { Text("Rename") }
            } },
            dismissButton = { TextButton(onClick = { dialogError = null; showDelete = target; actionTarget = null }) { Text("Delete") } },
        )
    }

    @Composable
    fun MutationError() {
        dialogError?.let { Text(it, color = DroideColors.Error, style = MaterialTheme.typography.labelSmall) }
        if (mutationBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }

    if (showRecovery && showMove == null) AlertDialog(
        onDismissRequest = { if (!mutationBusy) showRecovery = false },
        title = { Text("Recover misplaced files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Move only your own files and folders. Droide system files are protected.", style = MaterialTheme.typography.bodySmall)
                recoveryError?.let { DismissibleNotice(it, color = DroideColors.Error, ownerKey = state) }
                recoveryNotice?.let { DismissibleNotice(it, color = DroideColors.Muted, ownerKey = state) }
                if (recoveryBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(recoveryEntries, key = { it.path }) { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (entry.isDir) Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = DroideColors.Warning)
                            else FileTypeBrandIcon(entry.path, size = 18.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(entry.path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = {
                                dialogError = null; moveDirectory = ""; movingRecoveredFile = true; showMove = entry
                            }, enabled = !mutationBusy) { Text("Move out") }
                        }
                    }
                    if (recoveryLoaded && recoveryEntries.isEmpty() && recoveryError == null) item {
                        Text("No misplaced files found", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    }
                    if (recoveryCursor != null) item {
                        TextButton(onClick = { recoveryPageRequest++ }, enabled = !recoveryBusy) { Text("Load more candidates") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showRecovery = false }, enabled = !mutationBusy) { Text("Close") } },
    )

    showMove?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!mutationBusy) showMove = null },
            title = { Text("Move to folder") },
            text = {
                Column(Modifier.heightIn(max = (configuration.screenHeightDp * .60f).dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(
                        value = moveDirectory,
                        onValueChange = { moveDirectory = it },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !mutationBusy,
                        label = { Text("Destination folder") },
                        placeholder = { Text("src or leave empty for project root") },
                        singleLine = true,
                    )
                    ExplorerMoveDestination(files, target, moveDirectory, !mutationBusy) { moveDirectory = it }
                    Text("Update affected code references after moving.", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    Text("Save or discard unsaved edits before moving. Pending sync for saved files is retried automatically.",
                        style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                    if (movingRecoveredFile) Text("Move only files or folders you created.", style = MaterialTheme.typography.labelSmall)
                    MutationError()
                }
            },
            confirmButton = { TextButton(enabled = !mutationBusy, onClick = {
                val requestedDirectory = moveDirectory
                val recovering = movingRecoveredFile
                mutate {
                    val source = target.path
                    if (recovering) {
                        check(ExplorerRecoveryPolicy.canRecover(source)) { "Internal Droide data cannot be moved" }
                        check(requireRecoveryRoot()) { "Source folder no longer exists" }
                        withContext(Dispatchers.IO) {
                            check(!PathSecurity.isSymbolicLink(java.io.File(files.root, source))) { "Linked files cannot be recovered here" }
                            check(ExplorerFilePolicy.normalizeProjectPath(files.root, source) == source) { "Source path changed; reopen the recovery list" }
                        }
                    } else {
                        check(ExplorerFilePolicy.requireUserTarget(files.root, source) == source) { "Selected path changed; refresh Explorer" }
                    }
                    val directory = userDirectory(requestedDirectory)
                    check(!target.isDir || (directory != source && !directory.startsWith("$source/"))) { "A folder cannot be moved into itself" }
                    val destination = ExplorerFilePolicy.requireUserTarget(files.root, if (directory.isBlank()) source.substringAfterLast('/') else "$directory/${source.substringAfterLast('/')}")
                    check(destination != source) { "This file or folder is already in the selected destination" }
                    preparePathMutation(listOf(source, destination))
                    check(canMutatePath(source) && canMutatePath(destination)) { "Save or discard unsaved editor changes before moving this path" }
                    check(!files.exists(destination)) { "A file or folder already exists at /$destination; existing data was preserved" }
                    performMove(files.observeMove(source, destination), target)
                    if (recovering) { recoveryEntries = emptyList(); recoveryCursor = null; recoveryPageRequest = 0; recoveryLoaded = false }
                }
            }) { Text("Move") } },
            dismissButton = { TextButton(enabled = !mutationBusy, onClick = { showMove = null }) { Text("Cancel") } },
        )
    }

    if (showNewFile) AlertDialog(
        onDismissRequest = { if (!mutationBusy) showNewFile = false },
        title = { Text("New File") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, modifier = Modifier.fillMaxWidth(), enabled = !mutationBusy, label = { Text("File name") }, placeholder = { Text("Main.kt") }, singleLine = true)
                var langMenu by remember { mutableStateOf(false) }
                val langDef = LanguageRegistry.all.firstOrNull { it.id == newLang } ?: LanguageRegistry.all.first()
                Box {
                    OutlinedButton(onClick = { langMenu = true }, enabled = !mutationBusy) { Text(langDef.name); Icon(Icons.Default.ArrowDropDown, null) }
                    DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        LanguageRegistry.all.forEach { lang -> DropdownMenuItem({ Text(lang.name) }, onClick = {
                            newLang = lang.id
                            // An explicit language choice may suggest a visible name, never rewrite one.
                            if (newName.isBlank()) newName = lang.filenames.firstOrNull() ?: when (lang.id) {
                                "dockerfile" -> "Dockerfile"
                                "makefile" -> "Makefile"
                                else -> lang.extensions.firstOrNull()?.let { "untitled.$it" } ?: "untitled"
                            }
                            langMenu = false
                        }) }
                    }
                }
                Text("Location: /$newDirectory", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
                MutationError()
            }
        },
        confirmButton = {
            TextButton(enabled = !mutationBusy, onClick = {
                val requestedName = newName
                val requestedDirectory = newDirectory
                mutate {
                    val directory = userDirectory(requestedDirectory)
                    require(requestedName.isNotBlank()) { "Enter a file name" }
                    val name = PathSecurity.safeLeafName(requestedName)
                    val path = ExplorerFilePolicy.requireUserTarget(files.root, if (directory.isBlank()) name else "$directory/$name")
                    preparePathMutation(listOf(path))
                    check(canMutatePath(path)) { "Save or discard unsaved editor changes at the destination" }
                    check(!files.exists(path)) { "A file named $name already exists here" }
                    val syncPending = try {
                        files.writeTextIfUnchanged(path, LanguageRegistry.templateFor(name), DiskFileVersion.fromText(null), requireExistingParent = true)
                        null
                    } catch (saved: ProjectFileSyncException) { saved }
                    refreshLocalTreeAfterMutation(); searchRevision++; showNewFile = false; newName = ""
                    syncPending?.let { err = savedFileSyncMessage(it) }
                    openPath(path)
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !mutationBusy, onClick = { showNewFile = false }) { Text("Cancel") } },
    )

    if (showNewFolder) AlertDialog(
        onDismissRequest = { if (!mutationBusy) showNewFolder = false },
        title = { Text("New Folder") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, modifier = Modifier.fillMaxWidth(), enabled = !mutationBusy, label = { Text("Folder name") }, singleLine = true)
            Text("Location: /$newDirectory", style = MaterialTheme.typography.labelSmall, color = DroideColors.Muted)
            MutationError()
        } },
        confirmButton = {
            TextButton(enabled = !mutationBusy, onClick = {
                val requestedName = newName
                val requestedDirectory = newDirectory
                mutate {
                    val directory = userDirectory(requestedDirectory)
                    require(requestedName.isNotBlank()) { "Enter a folder name" }
                    val name = PathSecurity.safeLeafName(requestedName)
                    val path = ExplorerFilePolicy.requireUserTarget(files.root, if (directory.isBlank()) name else "$directory/$name")
                    preparePathMutation(listOf(path))
                    check(canMutatePath(path)) { "Save or discard unsaved editor changes at the destination" }
                    check(!files.exists(path)) { "A file or folder named $name already exists here" }
                    val syncPending = try {
                        check(files.createDirectory(path, requireExistingParent = true)) { "Cannot create folder" }
                        null
                    } catch (saved: ProjectDirectorySyncException) {
                        if (saved.relativePath != path) throw saved
                        saved
                    }
                    refreshLocalTreeAfterMutation(); searchRevision++; showNewFolder = false; newName = ""
                    state.selectEntry(FileEntry(path, true, 0L, 0L)); state.expandedPaths = (state.expandedPaths + path).distinct()
                    syncPending?.let { mutationNotice = "Folder /${it.relativePath} is available. Workspace sync is pending; do not repeat creation." }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !mutationBusy, onClick = { showNewFolder = false }) { Text("Cancel") } },
    )

    if (showRename != null) AlertDialog(
        onDismissRequest = { if (!mutationBusy) showRename = null },
        title = { Text("Rename") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(newName, { newName = it }, modifier = Modifier.fillMaxWidth(), enabled = !mutationBusy, label = { Text("New name") }, singleLine = true)
            MutationError()
        } },
        confirmButton = {
            TextButton(enabled = !mutationBusy, onClick = {
                val old = showRename ?: return@TextButton
                val requestedName = newName
                mutate {
                    val source = ExplorerFilePolicy.requireUserTarget(files.root, old)
                    check(source == old) { "Selected path changed; refresh Explorer" }
                    val leaf = PathSecurity.safeLeafName(requestedName)
                    val directory = userDirectory(old.substringBeforeLast('/', ""))
                    val newPath = ExplorerFilePolicy.requireUserTarget(files.root, if (directory.isBlank()) leaf else "$directory/$leaf")
                    if (old != newPath) {
                        preparePathMutation(listOf(old, newPath))
                        check(canMutatePath(old)) { "Save or discard unsaved editor changes before renaming this path" }
                        check(canMutatePath(newPath)) { "Save or discard unsaved editor changes at the destination" }
                        check(files.move(old, newPath, requireExistingParent = true)) { "Rename failed" }
                        state.renamed(old, newPath)
                        onRenamed(old, newPath)
                        refreshLocalTreeAfterMutation(); searchRevision++
                    }
                    showRename = null; newName = ""
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(enabled = !mutationBusy, onClick = { showRename = null }) { Text("Cancel") } },
    )

    showDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!mutationBusy) showDelete = null },
            title = { Text(if (target.isDir) "Delete folder?" else "Delete file?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!target.isDir) FileIdentityLabel(target.path, target.path, iconSize = 16.dp, monospaced = true)
                    Text(explorerDeleteExplanation(target.path, target.isDir, safLinked))
                    MutationError()
                }
            },
            confirmButton = {
                TextButton(enabled = !mutationBusy, onClick = {
                    mutate {
                        check(ExplorerFilePolicy.requireUserTarget(files.root, target.path) == target.path) { "Selected path changed; refresh Explorer" }
                        preparePathMutation(listOf(target.path))
                        check(canMutatePath(target.path)) { "Save or discard unsaved editor changes before deleting this path" }
                        check(files.delete(target.path)) { "Delete failed" }
                        state.expandedPaths = state.expandedPaths.filterNot { it == target.path || it.startsWith("${target.path}/") }
                        if (state.activeDirectory == target.path || state.activeDirectory.startsWith("${target.path}/")) state.activeDirectory = target.path.substringBeforeLast('/', "")
                        onDeleted(target.path); refreshLocalTreeAfterMutation(); searchRevision++
                        showDelete = null
                        if (target.isDir && !safLinked) mutationNotice = "Folder removed from the project. Recovery data is retained; storage has not been reclaimed."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(enabled = !mutationBusy, onClick = { showDelete = null }) { Text("Cancel") } },
        )
    }
}

private fun savedFileSyncMessage(saved: ProjectFileSyncException): String =
    "Saved /${saved.relativePath}. Workspace sync is pending; the project file is available."

private fun formatTransferBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
