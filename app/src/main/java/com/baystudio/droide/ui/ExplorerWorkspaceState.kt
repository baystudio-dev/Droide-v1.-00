package com.baystudio.droide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.baystudio.droide.core.DirectoryPage
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.FileEntry
import com.baystudio.droide.core.FileRepository
import com.baystudio.droide.core.ExplorerFilePolicy
import com.baystudio.droide.core.ExplorerSortOrder
import com.baystudio.droide.core.DirectoryListingOptions
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive









class ExplorerWorkspaceState {
    internal var moveNotice by mutableStateOf<String?>(null)
    internal var pendingReveal by mutableStateOf<String?>(null)
    internal var selectedPath by mutableStateOf<String?>(null)
    internal var undoMoves by mutableStateOf<List<FileRepository.MoveUndoToken>>(emptyList())
    internal var redoMoves by mutableStateOf<List<FileRepository.MoveUndoToken>>(emptyList())

    internal fun moved(from: String, entry: FileEntry) {
        val to = entry.path
        fun remap(path: String) = if (path == from || path.startsWith("$from/")) to + path.removePrefix(from) else path
        activeDirectory = if (activeDirectory == from || activeDirectory.startsWith("$from/")) remap(activeDirectory)
            else if (entry.isDir) to else to.substringBeforeLast('/', "")
        val expanded = expandedPaths.map(::remap).toMutableSet()
        var parent = to.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) { expanded += parent; parent = parent.substringBeforeLast('/', "") }
        // Refresh parents before descendants so the existing visibility/admission checks stay authoritative.
        expandedPaths = expanded.sortedWith(compareBy({ it.count { char -> char == '/' } }, { it }))
        query = ""
        selectedPath = to
        pendingReveal = to
    }

    internal fun recordMove(token: FileRepository.MoveUndoToken?, historyDirection: Int) {
        if (token == null) { undoMoves = emptyList(); redoMoves = emptyList(); return }
        val inverse = when (historyDirection) {
            -1 -> undoMoves.lastOrNull() ?: return
            1 -> redoMoves.lastOrNull() ?: return
            else -> null
        }
        // Accept each completed reverse receipt once, for exactly the selected edit.
        // A duplicate or a delayed receipt must not pop a different history entry.
        if (!token.claimPublication(inverse)) return
        fun rebase(tokens: List<FileRepository.MoveUndoToken>) = tokens.map { it.rebasedAfter(token) }
        when (historyDirection) {
            -1 -> { undoMoves = rebase(undoMoves.dropLast(1)); redoMoves = (rebase(redoMoves) + token).takeLast(32) }
            1 -> { redoMoves = rebase(redoMoves.dropLast(1)); undoMoves = (rebase(undoMoves) + token).takeLast(32) }
            else -> { undoMoves = (rebase(undoMoves) + token).takeLast(32); redoMoves = emptyList() }
        }
    }
    internal var pendingImportTicket by mutableStateOf<com.baystudio.droide.core.WorkspaceImportTicket?>(null)
    internal var lastImportResult: com.baystudio.droide.core.WorkspaceImportResult? = null
    internal var importNotice by mutableStateOf<String?>(null)
    val children = mutableStateMapOf<String, List<FileEntry>>()
    val moreAfter = mutableStateMapOf<String, DirectoryPageCursor>()
    var pageGeneration by mutableIntStateOf(0)
    var loadedRefreshToken by mutableIntStateOf(-1)
    internal var handledRefreshToken by mutableIntStateOf(-1)
    internal var refreshOutcome by mutableStateOf(ExplorerRefreshOutcome.IDLE)
    internal val pageFreshness = mutableStateMapOf<String, ExplorerPageFreshness>()
    internal val loadingPages = mutableStateMapOf<String, Int>()
    internal val refreshNotice: String?
        get() = when {
            pageFreshness.values.any { it.failure != null } ->
                "Explorer refresh incomplete: " + pageFreshness.values.first { it.failure != null }.failure
            refreshOutcome == ExplorerRefreshOutcome.PENDING -> "Refreshing Explorer…"
            refreshOutcome == ExplorerRefreshOutcome.INCOMPLETE -> "Explorer refresh incomplete; refresh to retry."
            else -> null
        }
    var expandedPaths by mutableStateOf<List<String>>(emptyList())
    var activeDirectory by mutableStateOf("")
    var sortOrder by mutableStateOf(ExplorerSortOrder.NAME_ASC)
        private set
    internal var sortPreferenceLoaded by mutableStateOf(false)
    internal val options get() = DirectoryListingOptions(sortOrder, hideDroideInternal = true)

    internal fun selectRoot() { activeDirectory = ""; selectedPath = null; pendingReveal = null }

    internal fun selectEntry(entry: FileEntry) {
        selectedPath = entry.path
        pendingReveal = null
        activeDirectory = if (ExplorerFilePolicy.isDroideInternalPath(entry.path)) ""
            else if (entry.isDir) entry.path else entry.path.substringBeforeLast('/', "")
    }

    internal fun changeSortOrder(order: ExplorerSortOrder) {
        if (sortOrder == order) return
        sortOrder = order
        // A cursor belongs to its ordering. Never append a page from an older sort.
        pageGeneration++
        children.clear()
        moreAfter.clear()
        pageFreshness.clear()
        loadingPages.clear()
        loadedRefreshToken = -1
        treeFirstVisibleItemIndex = 0
        treeFirstVisibleItemScrollOffset = 0
        requestRefresh()
    }

    internal fun sanitizeInternalSelection() {
        val stale = ExplorerFilePolicy.isDroideInternalPath(activeDirectory) ||
            expandedPaths.any(ExplorerFilePolicy::isDroideInternalPath) ||
            children.keys.any(ExplorerFilePolicy::isDroideInternalPath) ||
            children.values.any { entries -> entries.any { ExplorerFilePolicy.isDroideInternalPath(it.path) } } ||
            moreAfter.keys.any(ExplorerFilePolicy::isDroideInternalPath) ||
            moreAfter.values.any { it.options != options } ||
            searchResults.any(ExplorerFilePolicy::isDroideInternalPath)
        if (ExplorerFilePolicy.isDroideInternalPath(activeDirectory)) selectRoot()
        expandedPaths = expandedPaths.filterNot(ExplorerFilePolicy::isDroideInternalPath)
        if (searchResults.any(ExplorerFilePolicy::isDroideInternalPath)) {
            searchResults = searchResults.filterNot(ExplorerFilePolicy::isDroideInternalPath)
            searchNotice = null
        }
        if (stale) {
            pageGeneration++
            children.clear()
            moreAfter.clear()
            pageFreshness.clear()
            loadingPages.clear()
            loadedRefreshToken = -1
            requestRefresh()
        }
    }
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<String>>(emptyList())
    var searchNotice by mutableStateOf<String?>(null)
    var refreshToken by mutableIntStateOf(0)
        private set
    fun requestRefresh() { refreshToken++ }

    var treeFirstVisibleItemIndex by mutableIntStateOf(0)
    var treeFirstVisibleItemScrollOffset by mutableIntStateOf(0)
    var searchFirstVisibleItemIndex by mutableIntStateOf(0)
    var searchFirstVisibleItemScrollOffset by mutableIntStateOf(0)

    /** A collapsed subtree keeps its expansion intent, never its potentially large directory pages. */
    internal fun collapseDirectory(path: String) {
        if (path.isEmpty()) return
        if (activeDirectory == path || activeDirectory.startsWith("$path/")) {
            activeDirectory = path.substringBeforeLast('/', "")
        }
        expandedPaths = expandedPaths.filterNot { it == path }
        fun belongs(candidate: String) = candidate == path || candidate.startsWith("$path/")
        children.keys.filter(::belongs).forEach(children::remove)
        moreAfter.keys.filter(::belongs).forEach(moreAfter::remove)
        pageFreshness.keys.filter(::belongs).forEach(pageFreshness::remove)
        loadingPages.keys.filter(::belongs).forEach(loadingPages::remove)
        // Invalidate admitted reads; a late directory result must not undo this eviction.
        pageGeneration++
    }

    internal fun isDirectoryPageVisible(path: String): Boolean = isDirectoryPageVisible(path, expandedPaths.toHashSet())

    private fun isDirectoryPageVisible(path: String, expanded: Set<String>): Boolean {
        if (path.isEmpty()) return true
        if (path !in expanded) return false
        var current = path
        while (current.isNotEmpty()) {
            val parent = current.substringBeforeLast('/', "")
            if (children[parent].orEmpty().none { it.isDir && it.path == current }) return false
            if (parent.isNotEmpty() && parent !in expanded) return false
            current = parent
        }
        return true
    }

    internal fun visibleExpandedDirectories(): List<String> {
        val expanded = expandedPaths.toHashSet()
        return expanded.filter { isDirectoryPageVisible(it, expanded) }
    }

    internal fun firstMissingExpandedDirectory(excluded: Set<String> = emptySet()): String? {
        val expanded = expandedPaths.toHashSet()
        return expanded.asSequence()
            .filter { it !in excluded && it !in children && isDirectoryPageVisible(it, expanded) }
            .minWithOrNull(compareBy<String>({ it.count { character -> character == '/' } }, { it }))
    }

    internal fun pruneInvisibleDirectoryPages() {
        val expanded = expandedPaths.toHashSet()
        children.keys.filterNot { isDirectoryPageVisible(it, expanded) }.forEach(children::remove)
        moreAfter.keys.filterNot { isDirectoryPageVisible(it, expanded) }.forEach(moreAfter::remove)
        pruneRefreshMetadata()
    }

    internal fun pruneRefreshMetadata() {
        pageFreshness.keys.filterNot { it in children || isDirectoryPageVisible(it) }.forEach(pageFreshness::remove)
        loadingPages.keys.filterNot(::isDirectoryPageVisible).forEach(loadingPages::remove)
    }

    /** Patch only a complete page. An unvisited or failed sibling never implies deletion. */
    internal fun patchDirectoryPage(
        path: String,
        generation: Int,
        page: DirectoryPage,
        after: DirectoryPageCursor? = null,
    ): Boolean {
        if (generation != pageGeneration || !isDirectoryPageVisible(path)) return false
        if (after != null && moreAfter[path] != after) return false
        children[path] = if (after == null) page.entries else {
            val existing = children[path].orEmpty()
            val known = existing.mapTo(hashSetOf()) { it.path }
            existing + page.entries.filter { known.add(it.path) }
        }
        if (page.nextCursor == null) moreAfter.remove(path) else moreAfter[path] = page.nextCursor
        // Only a replaced authoritative ancestor can establish lost reachability.
        if (after == null) {
            fun below(candidate: String) = candidate != path && (path.isEmpty() || candidate.startsWith("$path/"))
            children.keys.filter { below(it) && !isDirectoryPageVisible(it) }.forEach(children::remove)
            moreAfter.keys.filter { below(it) && !isDirectoryPageVisible(it) }.forEach(moreAfter::remove)
            pruneRefreshMetadata()
        }
        return true
    }

    internal fun publishRefreshedDirectoryPages(
        generation: Int,
        requestedRefreshToken: Int,
        next: Map<String, List<FileEntry>>,
        cursors: Map<String, DirectoryPageCursor>,
    ): Boolean {
        if (generation != pageGeneration) return false
        children.keys.filterNot(next::containsKey).forEach(children::remove)
        children.putAll(next)
        pruneInvisibleDirectoryPages()
        moreAfter.clear()
        moreAfter.putAll(cursors.filterKeys(::isDirectoryPageVisible))
        loadedRefreshToken = requestedRefreshToken
        return true
    }

    /** The loader is suspendable; publication still belongs to the admitted visible generation. */
    internal suspend fun loadExpandedDirectoryPage(
        path: String,
        load: suspend (String) -> DirectoryPage,
    ): Boolean {
        if (!isDirectoryPageVisible(path)) return false
        val generation = pageGeneration
        val page = load(path)
        currentCoroutineContext().ensureActive()
        if (generation != pageGeneration || !isDirectoryPageVisible(path)) return false
        children[path] = page.entries
        if (page.nextCursor == null) moreAfter.remove(path) else moreAfter[path] = page.nextCursor
        return true
    }
}

internal data class ExplorerTreeRow(val entry: FileEntry?, val depth: Int, val parent: String = "")

/** Directory nesting cannot consume the UI thread's call stack or produce duplicate LazyColumn keys. */
internal fun flattenExplorerRows(
    children: Map<String, List<FileEntry>>,
    expandedPaths: Collection<String>,
    moreAfter: Map<String, DirectoryPageCursor>,
): List<ExplorerTreeRow> {
    data class Pending(val parent: String, val depth: Int, val entry: FileEntry? = null, val more: Boolean = false)
    val pending = java.util.ArrayDeque<Pending>()
    val visitedDirectories = hashSetOf<String>()
    val visitedEntries = hashSetOf<String>()
    val expanded = expandedPaths.toHashSet()
    val rows = mutableListOf<ExplorerTreeRow>()
    pending.addLast(Pending("", 0))
    while (pending.isNotEmpty()) {
        val item = pending.removeLast()
        val entry = item.entry
        when {
            item.more -> rows += ExplorerTreeRow(null, item.depth, item.parent)
            entry != null -> if (visitedEntries.add(entry.path)) {
                rows += ExplorerTreeRow(entry, item.depth)
                if (entry.isDir && entry.path in expanded) pending.addLast(Pending(entry.path, item.depth + 1))
            }
            visitedDirectories.add(item.parent) -> {
                if (item.parent in moreAfter) pending.addLast(Pending(item.parent, item.depth, more = true))
                val entries = children[item.parent].orEmpty()
                for (index in entries.indices.reversed()) pending.addLast(Pending(item.parent, item.depth, entries[index]))
            }
        }
    }
    return rows
}


internal enum class ExplorerRefreshOutcome { IDLE, PENDING, COMPLETE, INCOMPLETE }

/** Entries live only as long as a cached page or a reachable expansion requires them. */
internal data class ExplorerPageFreshness(
    val succeededAt: Long? = null,
    val attemptedAt: Long? = null,
    val dirtyVersion: Long = 0,
    val dirtySince: Long? = null,
    val failure: String? = null,
    val failedAt: Long? = null,
)

internal fun cleanedExplorerQuery(query: String): String =
    query.trim().filterNot { it == '*' || it == '?' || it == '[' || it == ']' }.take(80)

internal fun explorerTreeMode(query: String): Boolean = cleanedExplorerQuery(query).length < 2

/** Row keys change on viewport membership, not on every scrolling pixel. */
internal fun explorerViewportPages(state: ExplorerWorkspaceState, keys: Set<String>): Set<String> = buildSet {
    keys.forEach { key ->
        when {
            key.startsWith("more:") -> add(key.removePrefix("more:"))
            key.startsWith("entry:") -> {
                val path = key.removePrefix("entry:")
                add(path.substringBeforeLast('/', ""))
                if (state.isDirectoryPageVisible(path)) add(path)
            }
        }
    }
}.filterTo(linkedSetOf()) { state.isDirectoryPageVisible(it) && !ExplorerFilePolicy.isDroideInternalPath(it) }
