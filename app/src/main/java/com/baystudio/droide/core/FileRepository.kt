package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LargeFileException(val relativePath: String, val bytes: Long) :
    IllegalStateException("File exceeds this bounded read ($bytes bytes): $relativePath. Use the editor large-file path or a bounded read range.")

class UnsupportedTextEncodingException(val relativePath: String) :
    IllegalStateException("Unsupported or invalid UTF-8 encoding: $relativePath. Original bytes are unchanged; convert a copy to UTF-8 explicitly before editing.")

/** Local directory creation completed; only a subsequent observer/mirror synchronization failed. */
class ProjectDirectorySyncException(val relativePath: String, cause: Exception) :
    java.io.IOException("Folder exists locally at $relativePath, but synchronization is pending. " +
        "Use the existing project folder and refresh; do not repeat creation.", cause)

/** Publication returned, but the canonical destination could not be attributed to this write. */
class ProjectFilePublicationUncertainException(val relativePath: String, val bytes: Long, cause: Throwable) :
    java.io.IOException("Publication outcome is uncertain at $relativePath. Preserve the destination and " +
        "inspect recovery before retrying; the saved file could not be verified.", cause)

internal class RetainedCopyIncompleteException(
    val relativePath: String,
    val operationId: String?,
    val publicationAttempted: Boolean,
    cause: Exception,
) : java.io.IOException("Recovery copy at $relativePath requires review before retrying. Retained data was preserved.", cause)

internal data class TextReadPage(val text: String, val nextOffset: Int, val hasMore: Boolean)

enum class StreamWritePhase {
    PREPARING,
    COPYING,
    COMMITTING,
    SYNCHRONIZING,
}

data class StreamWriteProgress(
    val phase: StreamWritePhase,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { total ->
            (completedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }

    val cancellable: Boolean
        get() = phase == StreamWritePhase.PREPARING || phase == StreamWritePhase.COPYING
}

class FileRepository internal constructor(
    val root: File,
    private val beforeMutation: (suspend (Mutation) -> Unit)? = null,
    private val onMutation: (suspend (Mutation) -> Unit)? = null,
    private val createRoot: Boolean = true,
    private val newFilePublisher: NewProjectFilePublisher,
    private val pathMover: WorkspacePathMove = WorkspacePathMove.platform,
    private val streamDurability: StreamPublicationDurability = StreamPublicationDurability.platform,
    private val participant: WorkspaceMutationParticipant? = null,
    private val directoryCustody: LocalDirectoryDeleteCustody = LocalDirectoryDeleteCustody(root),
) {
    constructor(
        root: File,
        beforeMutation: (suspend (Mutation) -> Unit)? = null,
        onMutation: (suspend (Mutation) -> Unit)? = null,
        createRoot: Boolean = true,
    ) : this(root, beforeMutation, onMutation, createRoot, NewProjectFilePublisher.platform)

    sealed interface Mutation {
        data class Write(val path: String, val text: String? = null) : Mutation
        data class CreateDirectory(val path: String) : Mutation
        data class Delete(val path: String) : Mutation
        data class Move(val from: String, val to: String) : Mutation

        data class Sync(val path: String) : Mutation
    }

    /** An Explorer operation observes local effects even when IO-return cancellation hides its return value. */
    internal class MoveObservation internal constructor(
        internal val owner: FileRepository, val from: String, val to: String,
        internal val inverse: MoveUndoToken? = null,
    ) {
        internal var started = false
        internal var reached = false
        internal var original: PathMutationVersion? = null
        internal var parents: List<Pair<File, String>> = emptyList()
        internal var preparation: WorkspaceLocalPreparation? = null
        internal var rollbackCompleted = false
        internal var failedAttemptParents: Map<String, MoveParentSnapshot>? = null
        internal var finishFailed = false
        internal var parentTransitions: List<MoveParentTransition> = emptyList()
        var committed = false; internal set
        var uncertain = false; internal set
        var failure: Throwable? = null; internal set
        var undo: MoveUndoToken? = null; internal set
    }

    internal class MoveUndoToken internal constructor(
        internal val owner: FileRepository, val from: String, val to: String,
        internal val version: PathMutationVersion, internal val parents: List<Pair<File, String>>,
        private val inverseIdentity: Any? = null,
        private val parentTransitions: List<MoveParentTransition> = emptyList(),
        private val authority: MoveHistoryAuthority = MoveHistoryAuthority(),
        private val generation: Long = 0L,
    ) {
        // Exact publication identity without retaining an unbounded inverse-token chain.
        internal val identity = Any()

        internal fun requireCurrent() = synchronized(authority) {
            check(!authority.attempted && generation == authority.generation) {
                "Move history is stale; choose the path again"
            }
        }

        // Hold inverse authority during the attempt. A thrown call releases it only when the repository
        // proves the bounded unchanged pre-effect observation.
        internal fun attempting() = synchronized(authority) {
            requireCurrent()
            authority.attempted = true
        }

        internal fun unchangedAttempt() = synchronized(authority) {
            check(generation == authority.generation && authority.attempted)
            authority.attempted = false
        }

        internal fun claimPublication(expectedInverse: MoveUndoToken?): Boolean = synchronized(authority) {
            if (authority.published || authority.attempted || generation != authority.generation ||
                inverseIdentity !== expectedInverse?.identity) return@synchronized false
            authority.published = true
            true
        }

        private fun publicationClaimed(): Boolean = synchronized(authority) { authority.published }

        internal fun rebasedAfter(published: MoveUndoToken): MoveUndoToken {
            if (!published.publicationClaimed() || owner !== published.owner || !version.directory) return this
            return synchronized(authority) {
                if (authority.attempted || generation != authority.generation) return@synchronized this
                val transition = published.parentTransitions.firstOrNull {
                    it.path == from && it.before.version == version
                } ?: return@synchronized this
                // No live-version refresh: this exact old version must be the before-side of a completed,
                // verified immediate-parent rename receipt.
                authority.generation++
                MoveUndoToken(owner, from, to, transition.after.version, parents, inverseIdentity,
                    parentTransitions, authority, authority.generation)
            }
        }
    }

    internal class MoveHistoryAuthority {
        var generation = 0L
        var attempted = false
        var published = false
    }

    internal data class MoveParentSnapshot(
        val version: PathMutationVersion,
        val entries: Map<String, PathMutationVersion>,
    )

    internal data class MoveParentTransition(
        val path: String, val before: MoveParentSnapshot, val after: MoveParentSnapshot,
    )

    internal fun observeMove(from: String, to: String): MoveObservation =
        MoveObservation(this, normalizeRel(from), normalizeRel(to))

    internal fun observeUndo(token: MoveUndoToken): MoveObservation {
        require(token.owner === this) { "Move history belongs to another workspace" }
        return MoveObservation(this, token.from, token.to, token)
    }

    internal suspend fun moveObserved(observation: MoveObservation): Boolean {
        check(observation.owner === this && !observation.started) { "Move request is stale; choose the path again" }
        observation.started = true
        return moveInternal(observation.from, observation.to, true, observation)
    }

    init { check(root.isDirectory || participant == null && createRoot && root.mkdirs()) { "Cannot open project root: ${root.path}" } }

    private var streamWriteAdmission: (suspend (WorkspaceMutationGuard) -> Unit)? = null

    private suspend fun requireStreamWriteAdmission(guard: WorkspaceMutationGuard) {
        val admission = streamWriteAdmission ?: return
        guard.requireRoot(root)
        admission(guard)
        guard.requireRoot(root)
    }

    @Volatile internal var copyRecoveryHome: File? = null
        private set

    @Synchronized internal fun configureCopyRecoveryHome(home: File) {
        val location = home.canonicalFile
        check(copyRecoveryHome == null || copyRecoveryHome == location) { "Copy recovery authority is already configured" }
        require(!location.toPath().startsWith(root.canonicalFile.toPath())) { "Copy recovery authority must be outside the workspace" }
        copyRecoveryHome = location
    }

    internal fun copyRecoveryJournal(): ProjectCopyJournal = copyRecoveryHome?.let { ProjectCopyJournal(root, it) }
        ?: ProjectCopyJournal(root)

    internal val localDirectoryRecovery by lazy { LocalDirectoryRecovery(this, directoryCustody) }

    private val retainedWorkLock = Any()
    private val _retainedWork = MutableStateFlow(RetainedWorkSnapshot("", emptyList(), 0L))
    internal val retainedWork: StateFlow<RetainedWorkSnapshot> = _retainedWork.asStateFlow()

    internal fun acknowledgeRetainedWork(token: String) = synchronized(retainedWorkLock) {
        if (_retainedWork.value.token == token) _retainedWork.value = RetainedWorkSnapshot("", emptyList(), 0L)
    }

    internal suspend fun inspectRetainedWork(directory: String = ""): RetainedWorkInventory =
        withContext(Dispatchers.IO) {
            if (directory == LocalDirectoryDeleteCustody.HOME ||
                directory.startsWith("${LocalDirectoryDeleteCustody.HOME}/") &&
                '/' !in directory.removePrefix("${LocalDirectoryDeleteCustody.HOME}/"))
                directoryCustody.inspectDirectory(directory)
            else RetainedWorkPolicy.inspect(root, directory).let { inventory ->
                if (directory.isEmpty() && inventory.problem == null &&
                    Files.exists(File(root, LocalDirectoryDeleteCustody.HOME).toPath(), LinkOption.NOFOLLOW_LINKS))
                    inventory.copy(problem = "Recovery location: retained folder deletions are in ${LocalDirectoryDeleteCustody.HOME}. Inspect that folder separately; this listing covers direct temporary candidates only.")
                else inventory
            }
        }

    internal suspend fun inspectDirectoryDeletion(token: LocalDirectoryDeleteCustody.Token): LocalDirectoryDeleteCustody.Inspection =
        withContext(Dispatchers.IO) { directoryCustody.inspect(token.operation, token) }

    /** Exact-token reconciliation never repeats deletion or synchronization, even after return loss. */
    internal suspend fun reconcileDirectoryDeletion(token: LocalDirectoryDeleteCustody.Token): LocalDirectoryDeleteCustody.Inspection =
        withContext(Dispatchers.IO) { WorkspaceFileMutationLocks.withRoot(root) { guard ->
            directoryCustody.reconcile(token, guard).also { reportDirectoryDeletion(it) }
        } }

    private fun reportDirectoryDeletion(result: LocalDirectoryDeleteCustody.Inspection) {
        reportRetainedWork(RetainedWorkNotice(result.source ?: "Folder deletion", result.candidate(),
            historicalWriteReturned = false, purpose = RetainedWorkPurpose.DIRECTORY_DELETE))
    }

    private fun reportRetainedWork(notice: RetainedWorkNotice) = synchronized(retainedWorkLock) {
        val before = _retainedWork.value
        val unique = before.notices.filterNot { it.candidate.path == notice.candidate.path } + notice
        val dropped = (unique.size - 8).coerceAtLeast(0)
        _retainedWork.value = RetainedWorkSnapshot(java.util.UUID.randomUUID().toString(),
            java.util.Collections.unmodifiableList(unique.takeLast(8)),
            before.omitted + minOf(dropped.toLong(), Long.MAX_VALUE - before.omitted))
    }

    suspend fun listFiles(rel: String = "", maxEntries: Int = MAX_DIRECTORY_ENTRIES,
                          includeDependencies: Boolean = true): List<FileEntry> = withContext(Dispatchers.IO) {
        listFilesBounded(rel, maxEntries, includeDependencies, DiscoveryBudget(), WorkspaceDiscoveryTraversal())
    }

    internal suspend fun listFilesBounded(
        rel: String,
        maxEntries: Int,
        includeDependencies: Boolean,
        budget: DiscoveryBudget,
        traversal: WorkspaceDiscoveryTraversal,
    ): List<FileEntry> {
        val dir = resolve(rel)
        require(dir.isDirectory) { "Not a directory: $rel" }
        val limit = maxEntries.coerceIn(1, MAX_DIRECTORY_ENTRIES)
        val entries = ArrayList<FileEntry>(minOf(limit, 256))
        traversal.scanDirectory(dir, budget) { child ->
            if (PathSecurity.isSymbolicLink(child) || !PathSecurity.contains(root, child)) return@scanDirectory
            val path = relPath(child)
            if (!budget.admitPath(path)) { budget.requireCompleteDirectory(rel); return@scanDirectory }
            val isDirectory = child.isDirectory
            // The requested directory is explicit; only newly discovered child directories are filtered.
            if (DependencyDiscoveryPolicy.isExcluded(child.name, isDirectory, includeDependencies)) return@scanDirectory
            if (entries.size >= limit) throw IllegalStateException("Directory has more than $limit visible entries; narrow the project layout")
            entries += FileEntry(path, isDirectory, child.length(), child.lastModified())
        }
        budget.requireCompleteDirectory(rel)
        val sorted = entries.sortedWith(compareBy({ !it.isDir }, { it.path.substringAfterLast('/').lowercase() }))
        budget.requireCompleteDirectory(rel)
        return sorted
    }

    suspend fun listFilesPage(
        rel: String = "",
        after: DirectoryPageCursor? = null,
        pageSize: Int = 250,
        options: DirectoryListingOptions = DirectoryListingOptions(),
    ): DirectoryPage = withContext(Dispatchers.IO) {
        listFilesPageBounded(rel, after, pageSize, options, DiscoveryBudget(), WorkspaceDiscoveryTraversal())
    }

    internal suspend fun listFilesPageBounded(
        rel: String,
        after: DirectoryPageCursor?,
        pageSize: Int,
        options: DirectoryListingOptions,
        budget: DiscoveryBudget,
        traversal: WorkspaceDiscoveryTraversal,
    ): DirectoryPage {
        val normalized = normalizeRel(rel)
        val dir = resolve(normalized)
        require(dir.isDirectory) { "Not a directory: $rel" }
        require(pageSize in 1..MAX_DIRECTORY_ENTRIES) { "Invalid directory page size" }
        require(after == null || after.path.substringBeforeLast('/', "") == normalized) { "Directory cursor belongs to another folder" }
        require(after == null || after.options == options) { "Directory cursor belongs to another listing policy" }
        require(after == null || normalizeRel(after.path) == after.path) { "Invalid directory cursor path" }
        val canonicalDirectoryPath = ExplorerFilePolicy.normalizeProjectPath(root, normalized)
        require(!options.hideDroideInternal || !ExplorerFilePolicy.isDroideInternalPath(canonicalDirectoryPath)) {
            "Internal Droide folders are hidden from the Explorer"
        }
        require(after == null || !options.hideDroideInternal || !ExplorerFilePolicy.isDroideInternalPath(after.path)) {
            "Internal Droide cursor is not visible in the Explorer"
        }
        val order = ExplorerFilePolicy.order(options)
        val cursor = after?.let { FileEntry(it.path, it.isDir, 0L, it.modified) }
        val best = PriorityQueue<FileEntry>(pageSize + 2, order.reversed())
        traversal.scanDirectory(dir, budget) { child ->
            if (PathSecurity.isSymbolicLink(child) || !PathSecurity.contains(root, child)) return@scanDirectory
            val path = relPath(child)
            if (!budget.admitPath(path)) { budget.requireCompleteDirectory(rel); return@scanDirectory }
            if (options.hideDroideInternal && ExplorerFilePolicy.isDroideInternalPath(path)) return@scanDirectory
            val entry = FileEntry(path, child.isDirectory, child.length(), child.lastModified())
            if (cursor != null && order.compare(entry, cursor) <= 0) return@scanDirectory
            best += entry
            if (best.size > pageSize + 1) best.poll()
        }
        budget.requireCompleteDirectory(rel)
        val sorted = best.toList().sortedWith(order)
        val visible = sorted.take(pageSize)
        budget.requireCompleteDirectory(rel)
        return DirectoryPage(visible, if (sorted.size > pageSize) visible.last().let {
            DirectoryPageCursor(it.path, it.isDir, options, it.modified)
        } else null)
    }

    suspend fun exists(rel: String): Boolean = withContext(Dispatchers.IO) { resolve(rel).exists() }
    suspend fun isDirectory(rel: String): Boolean = withContext(Dispatchers.IO) { resolve(rel).isDirectory }

    // Large files are never represented by a destructive preview buffer.
    suspend fun readText(rel: String, maxBytes: Long = 500_000): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        if (f.length() > maxBytes) throw LargeFileException(rel, f.length())
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = true)
    }

    suspend fun readEditorText(rel: String, maxBytes: Long = EditorLargeFilePolicy.MAX_EDITABLE_BYTES): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        if (f.length() > maxBytes) throw LargeFileException(rel, f.length())
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = true).also(EditorTextAdmission::requireNoNul)
    }

    suspend fun fileSize(rel: String): Long = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        f.length()
    }

    suspend fun readTextForEdit(rel: String, maxBytes: Long = 2_000_000): String = withContext(Dispatchers.IO) {
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        require(f.length() <= maxBytes) { "File too large to edit safely: $rel (${f.length()} bytes)" }
        rejectBinary(f, rel)
        readUtf8Bounded(f, maxBytes, rel, largeFileException = false)
    }

    /** Bounded character paging also works for minified files larger than readText's limit. */
    internal suspend fun readTextPage(rel: String, offset: Int, maxChars: Int = 10_000): TextReadPage = withContext(Dispatchers.IO) {
        require(offset >= 0) { "offset must be non-negative" }
        require(maxChars in 2..10_000) { "Invalid text page size" }
        val file = resolve(rel)
        require(file.isFile) { "Not a file: $rel" }
        require(offset.toLong() <= file.length()) { "offset is beyond end of file" }
        rejectBinary(file, rel)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            java.io.InputStreamReader(file.inputStream(), decoder).buffered(8_192).use { reader ->
                var remaining = offset.toLong()
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val skipped = reader.skip(minOf(remaining, 8_192L))
                    require(skipped > 0) { "offset is beyond end of file" }
                    remaining -= skipped
                }

                val buffer = CharArray(maxChars + 1)
                var count = 0
                while (count < buffer.size) {
                    currentCoroutineContext().ensureActive()
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                }
                require(count == 0 || !buffer[0].isLowSurrogate()) { "offset is inside a UTF-16 surrogate pair; use the returned next_offset" }
                var returned = minOf(count, maxChars)
                if (returned > 0 && returned < count && buffer[returned - 1].isHighSurrogate() && buffer[returned].isLowSurrogate()) returned--
                TextReadPage(String(buffer, 0, returned), offset + returned, count > returned)
            }
        } catch (_: CharacterCodingException) {
            throw UnsupportedTextEncodingException(rel)
        }
    }

    suspend fun readRange(rel: String, start: Int = 1, end: Int = 200, maxOutputChars: Int = Int.MAX_VALUE): String = withContext(Dispatchers.IO) {
        require(maxOutputChars >= 128) { "Read range output limit is too small" }
        val f = resolve(rel)
        require(f.isFile) { "Not a file: $rel" }
        rejectBinary(f, rel)
        val s = start.coerceAtLeast(1)
        val e = end.coerceIn(s, s + 500)
        val selected = ArrayList<Pair<Int, String>>()
        var lineNo = 1
        var sawAnyByte = false
        var afterCr = false
        var truncatedLine = false
        var hasMore = false
        var outputLimit = false
        var longLineTruncated = false
        var renderedChars = 0
        var lineBuffer = ByteArrayOutputStream(512)
        val maxLineBytes = 2_000

        fun finishLine() {
            if (lineNo in s..e) {
                val bytes = lineBuffer.toByteArray()
                val decoded = if (truncatedLine) {
                    // The byte cap is a stream boundary, not EOF. Do not invent a replacement character for a valid
                    // code point split by it.
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
                    val chars = CharBuffer.allocate(bytes.size)
                    check(decoder.decode(ByteBuffer.wrap(bytes), chars, false).isUnderflow)
                    chars.flip()
                    chars.toString()
                } else bytes.toString(Charsets.UTF_8)
                val text = decoded + if (truncatedLine) "…" else ""
                if (renderedChars + text.length + 16 > maxOutputChars) { outputLimit = true; hasMore = true }
                else {
                    selected += lineNo to text
                    renderedChars += text.length + 16
                    if (truncatedLine) longLineTruncated = true
                }
            }
            lineNo++
            lineBuffer = ByteArrayOutputStream(512)
            truncatedLine = false
        }

        f.inputStream().buffered(64 * 1024).use { input ->
            val buf = ByteArray(64 * 1024)
            loop@ while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n <= 0) break
                sawAnyByte = true
                for (i in 0 until n) {
                    val b = buf[i]

                    if (afterCr) {
                        afterCr = false
                        if (b == '\n'.code.toByte()) continue
                    }
                    if (lineNo > e) { hasMore = true; break@loop }
                    if (b == '\n'.code.toByte() || b == '\r'.code.toByte()) {
                        finishLine()
                        afterCr = b == '\r'.code.toByte()
                        if (outputLimit) break@loop
                    } else if (lineNo in s..e) {
                        if (lineBuffer.size() < maxLineBytes) lineBuffer.write(b.toInt()) else truncatedLine = true
                    }
                }
            }
        }
        if (sawAnyByte && lineBuffer.size() > 0 && lineNo <= e) finishLine()

        if (selected.isEmpty()) return@withContext "(range $s–$e is beyond end of file)"
        buildString {
            appendLine("… showing lines $s–${selected.last().first}${if (hasMore) "; more lines follow; next_start=${selected.last().first + 1}" else ""} …")
            if (longLineTruncated) appendLine("[DROIDE_READ_PAGE long_line_truncated=true; use read_file offset=0 then next_offset for exact character pages]")
            selected.forEach { (i, line) -> appendLine(String.format("%4d│ %s", i, line)) }
        }
    }

    // Never returns internal or sensitive paths.
    suspend fun findFiles(query: String, maxResults: Int = 100, includeDependencies: Boolean = false): List<String> =
        findFilesPage(query, maxResults, includeDependencies).paths

    suspend fun findFilesPage(query: String, maxResults: Int = 100, includeDependencies: Boolean = false): FileQueryPage = withContext(Dispatchers.IO) {
        require(query.length <= WorkspaceSearchInput.MAX_RAW_INPUT_CHARS) { "query too long" }
        val q = query.trim().lowercase()
        require(q.length <= 256) { "query too long" }
        val limit = maxResults.coerceIn(1, 300)
        val budget = DiscoveryBudget()
        val ignore = loadIgnoreRules()
        val ranked = ArrayList<Pair<Int, String>>(minOf(limit * 4, 1_200))
        WorkspaceDiscoveryTraversal().walk(root, budget,
            canDescend = { canSearchDescend(it, ignore, includeDependencies) }) { f ->
            if (PathSecurity.isSymbolicLink(f) || !f.isFile) return@walk
            val rel = relPath(f)
            if (isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) ||
                DependencyDiscoveryPolicy.isExcluded(rel, false, includeDependencies) || ignored(rel, ignore)) return@walk
            val score = QuickOpenMatcher.score(q, rel) ?: return@walk
            ranked += score to rel
            if (ranked.size > limit * 8) {
                ranked.sortWith(compareBy<Pair<Int, String>>({ it.first }, { it.second.length }, { it.second }))
                ranked.subList(limit * 4, ranked.size).clear()
            }
        }
        val paths = ranked.sortedWith(compareBy<Pair<Int, String>>({ it.first }, { it.second.length }, { it.second }))
            .take(limit).map { it.second }
        budget.checkpoint()
        FileQueryPage(paths, budget.scannedEntries, budget.incomplete, ranked.size > limit, budget.stopReasons)
    }

    suspend fun glob(pattern: String, max: Int = 200, includeDependencies: Boolean = false): String = try {
        globInternal(pattern, max, includeDependencies)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        "(glob error: ${error.message?.take(320) ?: error::class.java.simpleName})"
    }

    private suspend fun globInternal(pattern: String, max: Int = 200, includeDependencies: Boolean = false): String {
        val page = globPage(pattern, max, includeDependencies)
        return buildString {
            page.paths.forEach { appendLine(it) }
            if (page.scanLimitReached) appendLine("… scan incomplete after ${page.scannedEntries} entries " +
                "(${(page.scanStopReasons.map { it.name.lowercase() } + page.searchStopReasons.map { it.name.lowercase() }).joinToString()}) …")
            else if (page.resultLimitReached) appendLine("… results limited to ${max.coerceIn(1, MAX_GLOB_RESULTS)} …")
        }.ifBlank { "(no results for $pattern)" }
    }

    suspend fun globPage(pattern: String, max: Int = 200, includeDependencies: Boolean = false): FileQueryPage =
        globPageBounded(pattern, max, includeDependencies)

    internal suspend fun globPageBounded(
        pattern: String,
        max: Int = 200,
        includeDependencies: Boolean = false,
        limits: WorkspaceSearchLimits = WorkspaceSearchLimits(),
        nanoTime: () -> Long = System::nanoTime,
        traversal: WorkspaceDiscoveryTraversal = WorkspaceDiscoveryTraversal(),
    ): FileQueryPage = withContext(Dispatchers.IO) {
        val work = WorkspaceSearchBudget(limits, currentCoroutineContext(), WorkspaceRequestDeadline(limits.durationNanos, nanoTime))
        val budget = DiscoveryBudget(deadline = work.deadline)
        val resultLimit = max.coerceIn(1, MAX_GLOB_RESULTS)
        val paths = ArrayList<String>(minOf(resultLimit, 128))
        try {
            work.input(pattern)
            val trimmed = pattern.trim()
            require(trimmed.isNotEmpty() && trimmed.length <= 256) { "Invalid glob pattern" }
            val ignore = loadIgnoreRules()
            val matcher = WorkspaceGlobMatcher(trimmed)
            traversal.walk(root, budget,
                canDescend = { canSearchDescend(it, ignore, includeDependencies) },
                shouldContinue = { paths.size < resultLimit }) { file ->
                work.checkpoint()
                if (PathSecurity.isSymbolicLink(file) || !file.isFile) return@walk
                val rel = relPath(file)
                if (isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) ||
                    DependencyDiscoveryPolicy.isExcluded(rel, false, includeDependencies) || ignored(rel, ignore)) return@walk
                if (matcher.matches(rel, work) || matcher.matches(file.name, work)) paths += rel
            }
            work.checkpoint()
        } catch (_: WorkspaceSearchLimitException) {
            // Work exhaustion is never a false/no-match result.
        }
        budget.checkpoint()
        FileQueryPage(paths, budget.scannedEntries, budget.incomplete || work.incomplete,
            paths.size >= resultLimit, budget.stopReasons, work.stopReasons, work.stats)
    }

    suspend fun writeText(rel: String, content: String) = writeTextBounded(rel, content, MAX_TEXT_WRITE_BYTES)

    suspend fun writeTextIfUnchanged(
        rel: String,
        content: String,
        expected: DiskFileVersion,
        requireExistingParent: Boolean = false,
    ) = writeTextBounded(rel, content, MAX_TEXT_WRITE_BYTES, expected, requireExistingParent)

    internal enum class TextWriteMode(val verificationMaxBytes: Long) {
        CHECKED_TEXT(MAX_TEXT_WRITE_BYTES),
        EDITOR(EditorLargeFilePolicy.MAX_EDITABLE_BYTES),
    }

    internal enum class TextWriteDisposition { UNVERIFIED, UNCHANGED_WITHOUT_WRITE, OWNED_CURRENT, RECOVERY_REQUIRED }

    internal data class TextWriteAdmission(val canonicalRoot: String, val rootIdentity: String,
        val operationId: String, val isCurrent: () -> Boolean)

    /** One invocation's local evidence; it is neither a durable journal nor permission to retry. */
    internal class TextWriteObservation internal constructor(
        internal val repository: FileRepository,
        internal val path: String,
        internal val content: String,
        internal val expectedBefore: DiskFileVersion,
        internal val expectedAfter: DiskFileVersion,
        internal val mode: TextWriteMode = TextWriteMode.CHECKED_TEXT,
    ) {
        internal val verificationMaxBytes: Long = mode.verificationMaxBytes
        internal val invocationToken = Any()
        private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        private var boundGuard: WorkspaceMutationGuard? = null
        private var admission: TextWriteAdmission? = null
        @Volatile var localAttempted: Boolean = false
            private set
        @Volatile var writeAttempted: Boolean = false
            private set
        @Volatile var receipt: WorkspaceLocalReceipt? = null
            private set
        @Volatile var disposition: TextWriteDisposition = TextWriteDisposition.UNVERIFIED
            private set

        internal fun claim(repository: FileRepository, mode: TextWriteMode) {
            check(this.repository === repository) { "Text write observation belongs to another repository" }
            check(this.mode == mode) { "Text write observation belongs to another writer mode" }
            check(claimed.compareAndSet(false, true)) { "Text write observation was already used" }
        }

        internal fun bindAdmission(binding: TextWriteAdmission) {
            check(!claimed.get() && admission == null)
            require(ProjectCopyEvidence.UUID_PATTERN.matches(binding.operationId))
            admission = binding
        }

        internal fun matchesAdmission(root: String, rootIdentity: String, operationId: String): Boolean {
            val binding = admission ?: return !claimed.get() && !localAttempted && disposition == TextWriteDisposition.UNVERIFIED
            return binding.canonicalRoot == root && binding.rootIdentity == rootIdentity && binding.operationId == operationId
        }

        internal fun requireAdmission(guard: WorkspaceMutationGuard) {
            val binding = admission ?: return
            guard.requireRoot(repository.root)
            check(guard.canonicalRoot.path == binding.canonicalRoot && guard.rootIdentity == binding.rootIdentity && binding.isCurrent()) {
                "Original text edit workspace or run was replaced"
            }
        }

        internal fun attemptingLocal(guard: WorkspaceMutationGuard) {
            check(boundGuard === guard)
            localAttempted = true
        }

        internal fun bind(guard: WorkspaceMutationGuard, mutation: Mutation) {
            check(claimed.get() && boundGuard == null && mutation == Mutation.Write(path, content)) {
                "Text write observation belongs to another invocation"
            }
            guard.requireRoot(repository.root)
            requireAdmission(guard)
            boundGuard = guard
        }

        internal fun attempting(guard: WorkspaceMutationGuard, preparation: WorkspaceLocalPreparation) {
            check(boundGuard === guard && preparation.effect == WorkspaceLocalEffect.WRITE && preparation.relativePath == path)
            localAttempted = true
            writeAttempted = true
        }

        internal fun returned(guard: WorkspaceMutationGuard, observation: WorkspaceLocalReceipt) {
            check(boundGuard === guard)
            receipt = observation
        }

        internal fun completed(guard: WorkspaceMutationGuard, result: TextWriteDisposition) {
            check(boundGuard === guard)
            disposition = result
            boundGuard = null
        }
    }

    internal fun prepareTextWriteObservation(rel: String, content: String, expected: DiskFileVersion) =
        TextWriteObservation(this, normalizeRel(rel), content, expected, DiskFileVersion.fromText(content))

    internal suspend fun writeTextIfUnchanged(observation: TextWriteObservation) {
        // Claim before dispatch, so cancellation before IO entry cannot make this holder reusable.
        observation.claim(this, TextWriteMode.CHECKED_TEXT)
        writeTextBounded(observation.path, observation.content, MAX_TEXT_WRITE_BYTES,
            observation.expectedBefore, observation = observation)
    }

    /** Prepare on IO; this is the editor snapshot's only expected-after hash. */
    internal fun prepareEditorTextWriteObservation(rel: String, content: String, expected: DiskFileVersion) =
        TextWriteObservation(this, normalizeRel(rel), content, expected, DiskFileVersion.fromText(content), TextWriteMode.EDITOR)

    internal suspend fun writeEditorTextIfUnchanged(
        observation: TextWriteObservation,
        commitPublication: (suspend (() -> Unit) -> Unit)? = null,
    ) {
        observation.claim(this, TextWriteMode.EDITOR)
        writeTextBounded(observation.path, observation.content, EditorLargeFilePolicy.MAX_EDITABLE_BYTES,
            observation.expectedBefore, requireExistingParent = true, requireUserDestination = false,
            observation = observation, commitPublication = commitPublication)
    }

    suspend fun matchesVersion(rel: String, expected: DiskFileVersion): Boolean = withContext(Dispatchers.IO) {
        expected.matches(resolve(rel))
    }

    suspend fun writeEditorText(rel: String, content: String) =
        writeTextBounded(rel, content, EditorLargeFilePolicy.MAX_EDITABLE_BYTES)

    suspend fun writeEditorTextIfUnchanged(rel: String, content: String, expected: DiskFileVersion) =
        writeTextBounded(rel, content, EditorLargeFilePolicy.MAX_EDITABLE_BYTES, expected,
            requireExistingParent = true, requireUserDestination = false)

    /** Retry observers for an already committed editor snapshot without staging or rewriting its bytes. */
    suspend fun synchronizeEditorTextIfUnchanged(rel: String, content: String, expected: DiskFileVersion) =
        withContext(Dispatchers.IO) {
            val normalized = normalizeRel(rel)
            withMutation(Mutation.Write(normalized, content)) { invocation ->
                require(normalized.isNotBlank()) { "File path is empty" }
                val target = resolve(normalized)
                requireVersion(target, normalized, expected)
                invocation.admit(callBeforeMutation = false)
                try {
                    invocation.synchronize()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    throw ProjectFileSyncException(normalized, expected.bytes, failure)
                }
                requireVersion(target, normalized, expected)
            }
        }

    private suspend fun writeTextBounded(
        rel: String,
        content: String,
        maxBytes: Long,
        expected: DiskFileVersion? = null,
        requireExistingParent: Boolean = false,
        requireUserDestination: Boolean = requireExistingParent,
        observation: TextWriteObservation? = null,
        commitPublication: (suspend (() -> Unit) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        withMutation(Mutation.Write(normalized, content), observation) { invocation ->
            require(maxBytes in 1..EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { "Invalid text write limit" }
            check(observation == null || observation.verificationMaxBytes == maxBytes) {
                "Text write observation limit does not match this writer"
            }
            val encodedBytes = BoundedUtf8.encodedSize(content, maxBytes) {
                throw IllegalArgumentException("Text write exceeds ${maxBytes / (1024 * 1024)} MiB safety limit")
            }
            require(normalized.isNotBlank()) { "File path is empty" }
            val f = resolve(normalized)
            require(!f.isDirectory) { "Cannot overwrite directory: $rel" }
            fun validateParent() {
                if (requireExistingParent) {
                    if (requireUserDestination) requireExplorerDestination(normalized, f) else requireParentExists(f)
                }
            }
            validateParent()
            expected?.let { requireVersion(f, rel, it) }
            invocation.admit()
            expected?.let { requireVersion(f, rel, it) }
            if (requireExistingParent) validateParent() else invocation.createParents(f)
            atomicWrite(f, content, invocation, encodedBytes, requireAbsent = expected?.existed == false, commitPublication = commitPublication, beforeCommit = {
                validateParent()
                expected?.let { requireVersion(f, rel, it) }
            }) { published ->
                try {
                    invocation.synchronize()
                } catch (t: Throwable) {
                    // Never undo publication or remove newer work after that boundary.

                    invocation.reconcileBestEffort(Mutation.Sync(normalized))
                    throw synchronizationFailure(t, normalized, encodedBytes, f, published)
                }
            }
        }
    }

    suspend fun writeStream(
        rel: String,
        input: java.io.InputStream,
        expectedBytes: Long? = null,
        requireAbsent: Boolean = false,
        requireExistingParent: Boolean = false,
        onProgress: (suspend (StreamWriteProgress) -> Unit)? = null,
        publicationObserver: StreamPublicationObserver? = null,
        beforePublication: (suspend () -> Unit)? = null,
        expectedVersion: StreamFileVersion? = null,
        executable: Boolean? = null,
    ): Long = withContext(Dispatchers.IO) {

        val streamCaller = currentCoroutineContext()
        input.use { source ->
            val normalized = normalizeRel(rel)
            var settle: (MutationInvocation, Throwable?, Boolean) -> Unit = { _, _, _ ->
                (publicationObserver as? StreamPublicationSettlementObserver)?.onSettled(StreamPublicationSettlement(null, null, false, false))
            }
            withMutation(Mutation.Write(normalized), streamCompletion = { invocation, failure, finished -> settle(invocation, failure, finished) }) { invocation ->
                require(normalized.isNotBlank()) { "File path is empty" }
                requireStreamWriteAdmission(invocation.guard)
                expectedVersion?.requireMatches(root, normalized)
                val f = resolve(normalized)
                require(!f.isDirectory) { "Cannot overwrite directory: $rel" }
                if (expectedVersion?.existed == true) {
                    require(PosixFilePermission.OWNER_WRITE in
                        Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "File is read-only: ${f.name}; change its permissions explicitly before saving"
                    }
                }
                if (requireExistingParent) requireExplorerDestination(normalized, f)
                if (requireAbsent) require(!f.exists()) { "A file already exists: $rel; choose another name" }
                val expected = expectedBytes?.takeIf { it >= 0L }
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.PREPARING, totalBytes = expected))
                ensureImportFreeSpace(root, expected ?: 0L)
                invocation.admit()
                expectedVersion?.requireMatches(root, normalized)
                if (requireAbsent) require(!f.exists()) { "A file appeared during import: $rel; existing data was preserved" }
                if (requireExistingParent) requireExplorerDestination(normalized, f) else invocation.createParents(f)
                val publicationParents = capturePublicationParents(f)
                val projectIdentity = publicationParents.first()
                val plannedStage = (publicationObserver as? StreamStagePlan)?.plannedStagePath
                if (plannedStage != null) ProjectCopyEvidence.validateStagePath(normalized, plannedStage)
                val stageName = plannedStage?.substringAfterLast('/') ?: tempSibling(f).name
                invocation.planStage(File(f.parentFile, stageName), publicationParents.last().second)
                var streamPreparation: StreamPublicationPreparation? = null
                var streamReceipt: StreamPublicationReceipt? = null
                var streamAttempted = false
                var streamPublished: PublicationVersion? = null
                settle = { finalInvocation, failure, finished ->
                    val current = try {
                        finalInvocation.guard.requireRoot(root)
                        requirePublicationParents(publicationParents)
                        finished && (failure == null || failure is Exception) && finalInvocation.evidenceComplete &&
                            streamReceipt != null && streamPublished != null && publicationVersion(f) == streamPublished
                    } catch (_: Exception) { false }
                    (publicationObserver as? StreamPublicationSettlementObserver)?.onSettled(
                        StreamPublicationSettlement(streamPreparation, streamReceipt, streamAttempted, current))
                }
                OwnedStreamStage.create(f, stageName).use { stage ->
                    invocation.trackStage(stage)
                    reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, totalBytes = expected))
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val count = java.security.DigestOutputStream(stage.output, digest).buffered(IMPORT_COPY_BUFFER_BYTES).use { out ->
                        copyStream(source, out, root, expected, onProgress)
                    }
                    val overwritePermissions = if (expectedVersion?.existed == true) {
                        expectedVersion.requireMetadataMatches(root, normalized)
                        Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS).also { permissions ->
                            require(PosixFilePermission.OWNER_WRITE in permissions) {
                                "File is read-only: ${f.name}; change its permissions explicitly before saving"
                            }
                            stage.requireOwned()
                            Files.setPosixFilePermissions(stage.file.toPath(), permissions)
                        }
                    } else null
                    val publicationPermissions = if (executable != null) {
                        val permissions = Files.getPosixFilePermissions(stage.file.toPath(), LinkOption.NOFOLLOW_LINKS).toMutableSet()
                        if (executable) permissions += PosixFilePermission.OWNER_EXECUTE
                        else permissions.removeAll(setOf(PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))
                        Files.setPosixFilePermissions(stage.file.toPath(), permissions)
                        permissions.toSet()
                    } else overwritePermissions
                    stage.force(streamDurability)
                    val stagedVersion = requireNotNull(publicationVersion(stage.file)) { "Import stage identity is unavailable" }
                    check(stagedVersion.key.toString() == stage.identity && stagedVersion.bytes == count)
                    val preparation = StreamPublicationPreparation(
                        publicationId = java.util.UUID.randomUUID().toString(),
                        canonicalRoot = projectIdentity.first.path,
                        rootIdentity = projectIdentity.second,
                        relativePath = normalized,
                        bytes = count,
                        sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
                        stageIdentity = stage.identity,
                    )
                    streamPreparation = preparation
                    currentCoroutineContext().ensureActive()
                    val local = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, normalized,
                        relPath(stage.file), stage.identity, count, preparation.sha256, preparation)
                    invocation.prepare(local, stage)
                    reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COMMITTING, count, expected))
                    // Recheck editor/media admission after every potentially long preparation and UI callback,
                    // immediately before the bounded publication block.
                    beforePublication?.invoke()
                    (publicationObserver as? StreamPublicationAdmission)?.preparePublication(preparation)
                    currentCoroutineContext().ensureActive()
                    if (expectedVersion != null) {
                        // Keep fallback hashing and editor re-admission cancellable. This is the same transaction, not
                        // another journal begin or synchronization.
                        invocation.beforeAttempt(local)
                        if (publicationObserver != null) stage.retainForRecovery()
                        publicationObserver?.beforePublish(preparation)
                        expectedVersion.requireMatches(root, normalized)
                        invocation.recheckAdmission()
                    }
                    var published: PublicationVersion? = null
                    withContext(NonCancellable) {
                        if (requireExistingParent) requireExplorerDestination(normalized, f)
                        requirePublicationParents(publicationParents)
                        stage.requireOwned()
                        check(publicationVersion(stage.file) == stagedVersion) { "Import stage changed during preparation" }

                        if (expectedVersion == null) {
                            invocation.beforeAttempt(local)
                            if (publicationObserver != null) stage.retainForRecovery()
                            publicationObserver?.beforePublish(preparation)
                        }
                        requireStreamWriteAdmission(invocation.guard)
                        requirePublicationParents(publicationParents)
                        expectedVersion?.requireMetadataMatches(root, normalized)
                        if (publicationPermissions != null) {
                            check(Files.getPosixFilePermissions(stage.file.toPath(), LinkOption.NOFOLLOW_LINKS) == publicationPermissions) {
                                "Import stage permissions changed after admission"
                            }
                        }
                        stage.requireOwned()
                        check(publicationVersion(stage.file) == stagedVersion) { "Import stage changed after admission" }
                        expectedVersion?.requireMetadataMatches(root, normalized)
                        var publicationReturned = false
                        var receiptRecorded = false
                        try {
                            var barrierUsed = false
                            val publishOnce = {
                                check(!barrierUsed) { "Stream publication barrier invoked twice" }
                                barrierUsed = true
                                streamCaller.ensureActive()
                                requirePublicationParents(publicationParents)
                                stage.requireOwned()
                                expectedVersion?.requireMetadataMatches(root, normalized)
                                check(publicationVersion(stage.file) == stagedVersion)
                                (publicationObserver as? StreamPublicationRevalidator)?.revalidateBeforePublication()
                                streamAttempted = true
                                invocation.localAttempted = true
                                if (requireAbsent || expectedVersion?.existed == false) publishFileAbsent(stage.file, f)
                                else replaceFile(stage.file, f)
                                publicationReturned = true
                                Unit
                            }
                            val barrier = publicationObserver as? StreamPublicationCommitBarrier
                            if (barrier == null) publishOnce() else barrier.commitPublication(publishOnce)
                            check(barrierUsed) { "Stream publication barrier did not publish" }
                            publicationReturned = true
                            published = stagedVersion
                            streamPublished = stagedVersion
                            check(publicationVersion(f) == stagedVersion) { "Published import identity changed" }
                            // Also establish every ancestor entry, including newly created parents. Forcing only file data
                            // or its immediate parent is insufficient.
                            publicationParents.asReversed().forEach { (parent, _) -> streamDurability.forceDirectory(parent) }
                            requirePublicationParents(publicationParents)
                            check(publicationVersion(f) == stagedVersion) { "Published import changed during durability checks" }
                            receiptRecorded = true
                            // Both mandatory observers receive the exact committed observation, even when the other observer
                            // cannot persist its own receipt.
                            var receiptFailure: Throwable? = null
                            try { invocation.returned(WorkspaceLocalReceipt(local, true, stage.identity, durable = true)) }
                            catch (failure: Throwable) { receiptFailure = failure }
                            streamReceipt = StreamPublicationReceipt(preparation, stage.identity)
                            try { publicationObserver?.onPublished(requireNotNull(streamReceipt)) }
                            catch (failure: Throwable) {
                                if (receiptFailure == null) receiptFailure = failure
                                else if (receiptFailure !== failure) receiptFailure.addSuppressed(failure)
                            }
                            receiptFailure?.let { throw it }
                            invocation.guard.requireRoot(root)
                            requirePublicationParents(publicationParents)
                            check(publicationVersion(f) == stagedVersion) {
                                "Published import changed during receipt callbacks; retained staging data requires recovery"
                            }
                        } catch (failure: Throwable) {
                            if (!receiptRecorded) invocation.recordFailedReturn(local, publicationReturned, failure)
                            // Invocation may change the canonical path before reporting an error (including an interrupted
                            // native operation). A missing return is not proof that publication had no effect.
                            if (failure is Exception && failure !is CancellationException) {
                                throw StreamPublicationUncertainException(normalized, count, publicationReturned, failure)
                            }
                            throw failure
                        }
                    }
                    // Provider work may copy/scan large trees: never inherit the short publication block's
                    // NonCancellable job.
                    stage.retainForRecovery()
                    try {
                        currentCoroutineContext().ensureActive()
                        reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.SYNCHRONIZING, count, expected))
                        invocation.synchronize()
                        invocation.guard.requireRoot(root)
                        requirePublicationParents(publicationParents)
                        releaseStageAfterSynchronization(stage, f, stagedVersion)
                    } catch (t: Throwable) {
                        invocation.reconcileBestEffort(Mutation.Sync(normalized))
                        throw synchronizationFailure(t, normalized, count, f, published)
                    }
                    count
                }
            }
        }
    }

    internal data class RetainedCopyReceipt(val operationId: String, val relativePath: String,
                                           val version: CanonicalFileSnapshot.Version)

    // Explicit CREATE-only integration; the caller supplies an already enrolled destination journal.
    internal suspend fun createRetainedCopy(
        rel: String,
        retained: CanonicalFileOperationJournal.RetainedContent,
        journal: CanonicalFileOperationJournal,
    ): RetainedCopyReceipt = createRetainedCopy(rel, retained, journal) {}

    internal suspend fun createRetainedCopy(
        rel: String,
        retained: CanonicalFileOperationJournal.RetainedContent,
        journal: CanonicalFileOperationJournal,
        requireAdmission: () -> Unit,
    ): RetainedCopyReceipt = withContext(Dispatchers.IO) {
        requireAdmission()
        check(participant == null) { "This workspace requires a recovery copy adapter for its synchronization provider" }
        val path = ExplorerFilePolicy.requireUserTarget(root, normalizeRel(rel))
        withMutation(Mutation.Write(path)) { invocation ->
            val context = currentCoroutineContext()
            requireAdmission()
            journal.requireWorkspaceBinding(invocation.guard.canonicalRoot, context)
            requireExplorerDestination(path, resolve(path))
            invocation.admit()
            requireAdmission()
            journal.requireWorkspaceBinding(invocation.guard.canonicalRoot, context)
            val version = retained.content.version
            var operationId: String? = null
            var publicationAttempted = false
            try {
                val intent = journal.admitCreation(path,
                    CanonicalFileOperationJournal.Content(version.size, version.sha256, version.mode), context)
                operationId = intent.id
                val bytes = retained.content.openStream().use { it.readBytes() }
                journal.captureProposal(intent.id, bytes, context)
                val staged = journal.stagePublication(intent.id, context)
                val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, path,
                    sourceIdentity = staged.identity, bytes = staged.size, sha256 = staged.sha256)
                invocation.prepare(preparation)
                invocation.recheckAdmission()
                requireAdmission()
                journal.requireWorkspaceBinding(invocation.guard.canonicalRoot, context)
                invocation.beforeAttempt(preparation)
                context.ensureActive()
                publicationAttempted = true
                invocation.localAttempted = true
                val published = try {
                    journal.publishStaged(intent.id, context)
                } catch (failure: Throwable) {
                    invocation.recordFailedReturn(preparation, false, failure)
                    throw failure
                }
                invocation.returned(WorkspaceLocalReceipt(preparation, true, published.identity, durable = true))
                journal.recordPublicationCompletion(intent.id, context)
                requireAdmission()
                invocation.synchronize()
                requireAdmission()
                journal.archiveCompleted(intent.id, context)
                invocation.guard.requireRoot(root)
                context.ensureActive()
                requireAdmission()
                RetainedCopyReceipt(intent.id, path, published)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw RetainedCopyIncompleteException(path, operationId, publicationAttempted, failure)
            }
        }
    }

    suspend fun createDirectory(rel: String, requireExistingParent: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel).trimEnd('/')
        withMutation(Mutation.CreateDirectory(normalized)) { invocation ->
            require(normalized.isNotBlank()) { "Directory path is empty" }
            val dir = resolve(normalized)
            require(!dir.isFile) { "A file already exists at $rel" }
            if (requireExistingParent) requireExplorerDestination(normalized, dir)
            invocation.admit()
            if (requireExistingParent) requireExplorerDestination(normalized, dir)
            require(!dir.isFile && !PathSecurity.isSymbolicLink(dir)) { "Destination changed before folder creation: $rel" }
            val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_DIRECTORY, normalized)
            val created = dir.isDirectory || invocation.localEffect(preparation, dir) {
                if (requireExistingParent) dir.mkdir() else dir.mkdirs()
            }
            if (!created) return@withMutation false
            val published = pathMutationVersion(dir)
            try {
                invocation.synchronize()
            } catch (t: Throwable) {

                invocation.reconcileBestEffort(Mutation.Sync(normalized))
                if (t is Exception && t !is CancellationException && published != null && pathMutationVersion(dir) == published) {
                    throw ProjectDirectorySyncException(normalized, t)
                }
                throw t
            }
            true
        }
    }

    /** Single-invocation observation, not durable custody or permission to retry deletion. */
    internal class DeleteObservation internal constructor(
        internal val repository: FileRepository,
        internal val path: String,
        internal val expected: DiskFileVersion?,
        internal val streamExpected: StreamFileVersion? = null,
        internal val streamBarrier: StreamedDiskCommitAuthority? = null,
    ) {
        private val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        internal var guard: WorkspaceMutationGuard? = null
        internal var preparation: WorkspaceLocalPreparation? = null
        internal var original: PathMutationVersion? = null
        internal var parents: List<Pair<File, String>> = emptyList()
        internal var receipt: WorkspaceLocalReceipt? = null
        internal var cleanupCompleted = false
        internal var finishFailed = false
        internal var identityPin: java.io.InputStream? = null
        @Volatile internal var synchronizationFailed = false
        @Volatile internal var ownedCurrent = false
        internal fun claim(repository: FileRepository) {
            check(this.repository === repository) { "Delete observation belongs to another repository" }
            check(claimed.compareAndSet(false, true)) { "Delete observation was already used" }
        }
        internal fun bind(guard: WorkspaceMutationGuard, mutation: Mutation) {
            check(claimed.get() && this.guard == null && mutation == Mutation.Delete(path))
            guard.requireRoot(repository.root)
            this.guard = guard
        }
    }

    internal fun prepareDeleteObservation(rel: String, expected: DiskFileVersion): DeleteObservation {
        require(expected.existed) { "Observed deletion requires an existing bounded file" }
        return DeleteObservation(this, normalizeRel(rel), expected)
    }

    internal suspend fun deleteFileIfUnchanged(observation: DeleteObservation): Boolean {
        observation.claim(this)
        return deleteChecked(observation.path, observation.expected, streamExpected = observation.streamExpected,
            deleteObservation = observation)
    }

    internal fun prepareStreamDeleteObservation(rel: String, expected: StreamFileVersion,
        barrier: StreamedDiskCommitAuthority): DeleteObservation {
        require(expected.existed)
        return DeleteObservation(this, normalizeRel(rel), null, expected, barrier)
    }

    suspend fun delete(rel: String): Boolean = deleteChecked(rel)

    suspend fun deleteFileIfUnchanged(rel: String, expected: DiskFileVersion): Boolean = deleteChecked(rel, expected)

    suspend fun deleteFileIfUnchanged(rel: String, expected: StreamFileVersion,
                                    beforeDelete: (suspend () -> Unit)? = null): Boolean =
        deleteChecked(rel, streamExpected = expected, beforeDelete = beforeDelete)

    private suspend fun deleteChecked(
        rel: String,
        expected: DiskFileVersion? = null,
        streamExpected: StreamFileVersion? = null,
        beforeDelete: (suspend () -> Unit)? = null,
        deleteObservation: DeleteObservation? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val normalized = normalizeRel(rel)
            withMutation(Mutation.Delete(normalized), deleteObservation = deleteObservation) { invocation ->
                require(normalized.isNotBlank()) { "Refusing to delete project root" }
                streamExpected?.requireMatches(root, normalized)
                val f = resolve(normalized)
                expected?.let { requireVersion(f, rel, it) }
                if (!f.exists()) return@withMutation false
                val directorySelection = if (f.isDirectory) {
                    check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, rel) == normalized)
                    directoryCustody.select(normalized)
                } else null
                invocation.admit()
                // SAF owns its existing protected stage. Other local directory deletions, including calls
                // without observers, retain the tree under durable root custody.
                val selectedStage = if (directorySelection != null) invocation.prepareDeleteStage(normalized) else null
                if (directorySelection != null && selectedStage == null) {
                    check(expected == null && streamExpected == null && deleteObservation == null) {
                        "Regular-file observations cannot authorize a directory deletion"
                    }
                    return@withMutation deleteDirectoryRetained(directorySelection, invocation)
                }
                if (directorySelection == null && onMutation == null && participant == null && deleteObservation == null) {
                    check(!f.isDirectory) { "Delete source became a directory during admission; choose it again" }
                    expected?.let { requireVersion(f, rel, it) }
                    streamExpected?.requireMatches(root, normalized)
                    if (streamExpected != null) invocation.recheckAdmission()
                    beforeDelete?.invoke()
                    streamExpected?.requireMetadataMatches(root, normalized)
                    // This branch was selected for a non-directory. Never recursively unlink a directory substituted
                    // during admission or immediately before deletion.
                    return@withMutation Files.deleteIfExists(f.toPath())
                }

                val staged = selectedStage ?: invocation.prepareDeleteStage(normalized) ?: tempSibling(f)

                expected?.let { requireVersion(f, rel, it) }
                streamExpected?.requireMatches(root, normalized)
                val original = pathMutationVersion(f)
                check(directorySelection != null || original?.directory != true) {
                    "Delete source became a directory during preparation; choose it again"
                }
                val retainedOriginal = if (original?.regularFile == true) f.inputStream() else null
                // The original inode must stay pinned through participant.finish and final observation, not just
                // through synchronization/cleanup.
                val scopedOriginal = if (deleteObservation == null) retainedOriginal else {
                    deleteObservation.identityPin = retainedOriginal
                    null
                }
                scopedOriginal.use {
                    val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE, relPath(staged),
                        normalized, original?.key?.toString())
                    deleteObservation?.let {
                        check(original?.regularFile == true) { "Observed delete source is not a regular file" }
                        it.original = original
                        it.preparation = preparation
                        it.parents = capturePublicationParents(f) + capturePublicationParents(staged)
                    }
                    require(invocation.localEffect(preparation, staged, beforeEffect = if (streamExpected == null) null else {
                        {
                            streamExpected.requireMatches(root, normalized)
                            invocation.recheckAdmission()
                            beforeDelete?.invoke()
                        }
                    }, publicationBarrier = deleteObservation?.streamBarrier?.let { barrier ->
                        { action -> barrier.commitDiskOnly(normalized, action) }
                    }) {
                        streamExpected?.requireMetadataMatches(root, normalized)
                        check(pathMutationVersion(f) == original) { "Delete source changed during preparation" }
                        deleteObservation?.let {
                            requirePublicationParents(it.parents)
                            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, normalized) == normalized)
                            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, relPath(staged)) == relPath(staged))
                            it.expected?.let { version -> requireVersion(f, normalized, version) }
                            it.streamExpected?.requireMetadataMatches(root, normalized)
                            check(pathMutationVersion(f) == original) { "Observed delete source changed while verifying" }
                        }
                        invocation.requireDeleteStage(staged)
                        moveLocal(f, staged, requireExistingParent = participant != null)
                    }) { "Cannot stage deletion: $rel" }
                    val deleteReceipt = invocation.deleteStageReceipt(preparation)
                    try {
                        invocation.synchronize()
                    } catch (t: Throwable) {
                        deleteObservation?.synchronizationFailed = true
                        val restored = invocation.rollbackAllowed(t) && rollbackMovedFile(normalized, relPath(staged), f, staged, original)
                        invocation.reconcileBestEffort(Mutation.Sync(normalized))
                        if (restored) throw t
                        throw mutationSynchronizationFailure(t,
                            "Delete synchronization failed; newer workspace changes were preserved. " +
                                "The original deletion was staged at ${relPath(staged)}; inspect that path and $normalized before recovery or retry.")
                    }
                    // Custody rejection is outside the synchronization rollback path. An owned retained stage must
                    // never be erased by the ordinary recursive cleanup.
                    if (invocation.retainsDeleteStage(deleteReceipt)) return@withMutation true
                    // Synchronizers can suspend while another actor replaces or edits a path. Delete only the stage
                    // still attributable to this operation.
                    check(original != null && pathMutationVersion(staged) == original) {
                        "Delete completed at $normalized, but staged data changed and was preserved at ${relPath(staged)}; inspect it before cleanup."
                    }
                    // Never erase changed stage evidence before the final observation.
                    if (deleteObservation != null) check(verifyOwnedDelete(deleteObservation, invocation.guard)) {
                        "Delete stage changed; recovery evidence and Undo history were preserved"
                    }
                    check(original.regularFile) {
                        "Non-file deletion is retained at ${relPath(staged)}; its participant did not confirm custody"
                    }
                    // Regular-file cleanup is never recursive, even if the slot is substituted after its final
                    // observation. Directories require their custody authority.
                    check(Files.deleteIfExists(staged.toPath())) {
                        "Delete completed at $normalized; cleanup is pending at ${relPath(staged)}."
                    }
                    deleteObservation?.cleanupCompleted = true
                    true
                }
            }
        }

    private suspend fun deleteDirectoryRetained(
        selection: LocalDirectoryDeleteCustody.Selection,
        invocation: MutationInvocation,
    ): Boolean {
        directoryCustody.prepare(selection, invocation.guard).use { operation ->
            invocation.directoryDeleteToken = operation.token
            try {
                val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.DELETE_STAGE,
                    operation.retainedPath, selection.source, selection.sourceKey)
                check(invocation.localEffect(preparation, File(root, operation.retainedPath),
                    beforeEffect = { invocation.recheckAdmission() }) {
                    WorkspaceProcessPathGuard.requireSafe(root, invocation.mutation)
                    operation.move()
                })
                val localReceipt = invocation.deleteStageReceipt(preparation)
                // Durable local commitment precedes synchronization. Failure here is a pending notification, not
                // permission to roll back, erase descendants, or replay Delete.
                var observerFailure: Throwable? = null
                try {
                    operation.observerStarted()
                    invocation.synchronize()
                } catch (failure: Throwable) {
                    observerFailure = failure
                }
                try { operation.observerFinished(observerFailure) }
                catch (recordFailure: Throwable) {
                    if (observerFailure == null) throw recordFailure
                    if (recordFailure !== observerFailure) observerFailure.addSuppressed(recordFailure)
                }
                if (observerFailure == null) invocation.retainsDeleteStage(localReceipt)
                val inspection = directoryCustody.inspect(operation.token.operation, operation.token)
                reportDirectoryDeletion(inspection)
                if (observerFailure != null && (!invocation.observerAttempted ||
                    observerFailure is CancellationException || observerFailure !is Exception)) {
                    observerFailure.addSuppressed(IllegalStateException("Directory deletion was retained at ${operation.retainedPath}; synchronization is incomplete."))
                    throw observerFailure
                }
                check(inspection.state == LocalDirectoryDeleteCustody.State.RETAINED) {
                    "Directory custody changed after synchronization; inspect ${operation.retainedPath} before retry"
                }
                return true
            } catch (failure: Throwable) {
                // Even cancellation/return loss leaves a discoverable typed receipt. Never replace the
                // originating cancellation or fatal failure while reporting it.
                try { reportDirectoryDeletion(directoryCustody.inspect(operation.token.operation, operation.token)) }
                catch (noticeFailure: Throwable) { if (noticeFailure !== failure) failure.addSuppressed(noticeFailure) }
                throw failure
            }
        }
    }

    suspend fun move(from: String, to: String, requireExistingParent: Boolean = false): Boolean =
        moveInternal(from, to, requireExistingParent, null)

    private suspend fun moveInternal(from: String, to: String, requireExistingParent: Boolean,
        observation: MoveObservation?): Boolean = withContext(Dispatchers.IO) {
        val normalizedFrom = normalizeRel(from)
        val normalizedTo = normalizeRel(to)
        withMutation(Mutation.Move(normalizedFrom, normalizedTo), moveObservation = observation) { invocation ->
            require(normalizedFrom.isNotBlank() && normalizedTo.isNotBlank()) { "Move path is empty" }
            val src = resolve(normalizedFrom)
            val dst = resolve(normalizedTo)
            fun validateInverse() { observation?.inverse?.let { token ->
                token.requireCurrent()
                requirePublicationParents(token.parents)
                check(pathMutationVersion(src) == token.version) { "Moved item changed; existing data was preserved. Move it explicitly instead of using Undo." }
            } }
            validateInverse()
            require(src.exists()) { "Source missing: $from" }
            require(!dst.exists()) { "Destination already exists: $to" }
            if (requireExistingParent) {
                requireExplorerSource(normalizedFrom)
                requireExplorerDestination(normalizedTo, dst)
            }
            // A strict Explorer move must still name the same source and parent directories afterwards.

            val admittedSource = if (requireExistingParent) checkNotNull(pathMutationVersion(src)) {
                "Move source identity is unavailable; refresh Explorer"
            } else null
            val admittedParents = if (requireExistingParent)
                capturePublicationParents(src) + capturePublicationParents(dst) else emptyList()
            fun validateAdmissionIdentity() {
                if (admittedSource != null) {
                    requirePublicationParents(admittedParents)
                    check(pathMutationVersion(src) == admittedSource) {
                        "Move source changed during admission; existing data was preserved. Refresh Explorer."
                    }
                }
            }
            invocation.admit()
            validateInverse()
            require(src.exists()) { "Source disappeared before move: $from" }
            require(!dst.exists()) { "Destination appeared before move: $to" }
            if (requireExistingParent) {
                requireExplorerSource(normalizedFrom)
                requireExplorerDestination(normalizedTo, dst)
            } else invocation.createParents(dst)
            validateAdmissionIdentity()
            val original = pathMutationVersion(src)
            observation?.let {
                it.original = original
                it.parents = capturePublicationParents(src) + capturePublicationParents(dst)
            }
            val retainedOriginal = if (original?.regularFile == true) src.inputStream() else null
            retainedOriginal.use {
                val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.MOVE, normalizedTo,
                    normalizedFrom, original?.key?.toString())
                observation?.preparation = preparation
                if (!invocation.localEffect(preparation, dst,
                    beforeEffect = if (requireExistingParent) ({ invocation.recheckAdmission() }) else null,
                ) {
                    validateAdmissionIdentity()
                    check(pathMutationVersion(src) == original) { "Move source changed during preparation" }
                    validateInverse()
                    val beforeParents = observation?.let { captureMoveParents(it) }.orEmpty()

                    validateAdmissionIdentity()
                    validateInverse()
                    observation?.inverse?.attempting()
                    val moved = try { moveLocal(src, dst, requireExistingParent || participant != null) }
                    catch (failure: Throwable) {
                        if (failure is Exception && observation?.inverse != null)
                            observation.failedAttemptParents = beforeParents
                        throw failure
                    }
                    observation?.let {
                        it.reached = moved
                        if (moved) it.parentTransitions = observeMoveParentTransitions(it, beforeParents)
                    }
                    moved
                }) return@withMutation false
                try {
                    invocation.synchronize()
                } catch (t: Throwable) {
                    val rolledBack = invocation.rollbackAllowed(t) && rollbackMovedFile(normalizedFrom, normalizedTo, src, dst, original)
                    val sourceReconciled = invocation.reconcileBestEffort(Mutation.Sync(normalizedFrom))
                    val destinationReconciled = invocation.reconcileBestEffort(
                        if (rolledBack) Mutation.Delete(normalizedTo) else Mutation.Sync(normalizedTo))
                    observation?.rollbackCompleted = rolledBack && sourceReconciled && destinationReconciled
                    if (!rolledBack) throw mutationSynchronizationFailure(t,
                        "Move synchronization failed; newer workspace changes were preserved. " +
                            "The move reached $normalizedTo before synchronization; inspect it and $normalizedFrom before recovery or retry. No rollback was performed.")
                    throw t
                }
                true
            }
        }
    }

    suspend fun applySearchReplace(rel: String, search: String, replace: String, replaceAll: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            require(search.isNotEmpty()) { "Search string must not be empty" }
            val old = readTextForEdit(rel)
            val count = countOccurrences(old, search)
            require(count > 0) { "Search string not found in $rel" }
            if (!replaceAll) require(count == 1) { "Search string must match exactly once in $rel (found $count)" }
            val updated = if (replaceAll) old.replace(search, replace) else old.replaceFirst(search, replace)
            writeTextIfUnchanged(rel, updated, DiskFileVersion.fromText(old))
            if (replaceAll) count else 1
        }

    suspend fun isSearchVisible(rel: String, includeDependencies: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        if (normalized.isBlank() || isInternalPath(normalized) || SensitivePathPolicy.isSensitive(normalized)) return@withContext false
        val isDirectory = resolve(normalized).isDirectory
        !loadIgnoreRules().isIgnored(normalized, isDirectory = isDirectory) &&
            !DependencyDiscoveryPolicy.isExcluded(normalized, isDirectory, includeDependencies)
    }

    suspend fun search(
        query: String,
        maxResults: Int = 100,
        excludedPaths: Set<String> = emptySet(),
        includeDependencies: Boolean = false,
    ): String {
        val page = searchPage(query, maxResults, excludedPaths, includeDependencies = includeDependencies)
        if (query.length <= WorkspaceSearchInput.MAX_RAW_INPUT_CHARS && query.length < 2) return "(query too short)"
        return buildString {
            page.hits.forEach { appendLine("${it.path}:${it.line}: ${it.excerpt}") }
            if (page.scanLimitReached) appendLine("… scan incomplete after ${page.scannedEntries} entries " +
                "(${(page.scanStopReasons.map { it.name.lowercase() } + page.searchStopReasons.map { it.name.lowercase() }).joinToString()}) …")
            else if (page.resultLimitReached) appendLine("… results limited to ${maxResults.coerceIn(1, MAX_SEARCH_RESULTS)} …")
            if (page.unreadableFiles > 0) appendLine("… ${page.unreadableFiles} unreadable file(s) skipped …")
        }
    }

    suspend fun searchPage(
        query: String,
        maxResults: Int = 100,
        excludedPaths: Set<String> = emptySet(),
        options: TextSearchOptions = TextSearchOptions(),
        documents: WorkspaceDocumentAuthority? = null,
        includeDependencies: Boolean = false,
    ): TextSearchPage = searchPageBounded(query, maxResults, excludedPaths, options, documents, includeDependencies)

    internal suspend fun searchPageBounded(
        query: String,
        maxResults: Int = 100,
        excludedPaths: Set<String> = emptySet(),
        options: TextSearchOptions = TextSearchOptions(),
        documents: WorkspaceDocumentAuthority? = null,
        includeDependencies: Boolean = false,
        limits: WorkspaceSearchLimits = WorkspaceSearchLimits(),
        nanoTime: () -> Long = System::nanoTime,
        openInput: (File) -> java.io.InputStream = { it.inputStream() },
        traversal: WorkspaceDiscoveryTraversal = WorkspaceDiscoveryTraversal(),
    ): TextSearchPage = withContext(Dispatchers.IO) {
        val work = WorkspaceSearchBudget(limits, currentCoroutineContext(), WorkspaceRequestDeadline(limits.durationNanos, nanoTime))
        val budget = DiscoveryBudget(deadline = work.deadline)
        val resultLimit = maxResults.coerceIn(1, MAX_SEARCH_RESULTS)
        val hits = ArrayList<TextSearchHit>(minOf(resultLimit, 100))
        var unreadable = 0
        var skippedLarge = 0
        var skippedDirty = 0
        var captureComplete = false
        val dirtyText = linkedMapOf<String, String>()
        val suppressed = hashSetOf<String>()
        try {
            work.input(query)
            work.input(options.includeGlob)
            val q = query
            if (q.length < 2) return@withContext TextSearchPage(emptyList(), 0, false, false, 0)
            require(q.length <= 256) { "query too long" }
            val includePattern = options.includeGlob.trim()
            require(includePattern.length <= 256) { "file filter too long" }
            val include = includePattern.takeIf { it.isNotEmpty() }?.let(::WorkspaceGlobMatcher)
            if (excludedPaths.size > limits.excludedPaths) work.stop(WorkspaceSearchStopReason.EXCLUSION_LIMIT)
            var exclusionChars = 0L

            for (path in excludedPaths) {
                work.checkpoint()
                if (path.length > limits.pathChars || path.length.toLong() > limits.excludedPathChars - exclusionChars)
                    work.stop(WorkspaceSearchStopReason.EXCLUSION_LIMIT)
                exclusionChars += path.length.toLong()
            }
            val excluded = excludedPaths.mapTo(hashSetOf()) { work.checkpoint(); normalizeRel(it) }
            val ignore = loadIgnoreRules()
            work.checkpoint()
            val capture = documents?.captureSearchSnapshots(WorkspaceSearchCaptureRequest(work)) ?: WorkspaceSearchCapture()
            work.checkpoint()
            if (!capture.supported || capture.snapshots.size > limits.snapshots ||
                capture.snapshots.size != work.stats.snapshotsCaptured) work.stop(WorkspaceSearchStopReason.UNSUPPORTED_AUTHORITY)
            for (snapshot in capture.snapshots) {
                work.checkpoint()
                if (!snapshot.dirty) continue
                // Producer admission must precede normalization; retain a defensive check for custom providers.
                if (snapshot.path.length > limits.pathChars) work.stop(WorkspaceSearchStopReason.SNAPSHOT_PATH_LIMIT)
                val rel = runCatching { normalizeRel(snapshot.path).also { resolve(it) } }.getOrNull() ?: continue
                if (rel.isBlank() || isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) ||
                    DependencyDiscoveryPolicy.isExcluded(rel, false, includeDependencies) || ignored(rel, ignore)) continue
                if (include != null && !include.matches(rel, work) && !include.matches(rel.substringAfterLast('/'), work)) continue
                excluded += rel // Never show a stale disk match when the editor owns this path.
                suppressed += rel
                if (snapshot.loaded && snapshot.editable && snapshot.kind in setOf(WorkspaceDocumentKind.TEXT, WorkspaceDocumentKind.LARGE) &&
                    snapshot.content.length <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) {
                    dirtyText[rel] = snapshot.content
                } else skippedDirty++
            }
            captureComplete = true
            val reader = WorkspaceSearchReader(work, openInput)
            for (path in dirtyText.keys.sorted()) {
                reader.scanDirty(dirtyText.getValue(path), { hits.size < resultLimit }) { line, raw ->
                    WorkspaceTextMatcher.appendLine(path, line, raw, q, options, resultLimit, hits, work)
                }
                if (hits.size >= resultLimit) break
            }
            traversal.walk(root, budget,
                canDescend = { canSearchDescend(it, ignore, includeDependencies) },
                shouldContinue = { hits.size < resultLimit }) { file ->
                work.checkpoint()
                if (PathSecurity.isSymbolicLink(file) || !file.isFile) return@walk
                val rel = relPath(file)
                if (rel in excluded || isInternalPath(rel) || SensitivePathPolicy.isSensitive(rel) ||
                    DependencyDiscoveryPolicy.isExcluded(rel, false, includeDependencies) || ignored(rel, ignore)) return@walk
                if (include != null && !include.matches(rel, work) && !include.matches(file.name, work)) return@walk
                if (file.length() > EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { skippedLarge++; return@walk }
                try {
                    if (reader.looksBinary(file)) return@walk
                    reader.scanFile(file, { hits.size < resultLimit }) { line, raw ->
                        WorkspaceTextMatcher.appendLine(rel, line, raw, q, options, resultLimit, hits, work)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (stopped: WorkspaceSearchLimitException) {
                    throw stopped
                } catch (_: Exception) {
                    unreadable++
                }
            }
            work.checkpoint()
        } catch (_: WorkspaceSearchLimitException) {
            // Typed partial result. Incomplete dirty admission never reaches traversal or text opens.
        }
        budget.checkpoint()
        TextSearchPage(hits, budget.scannedEntries, budget.incomplete || work.incomplete, hits.size >= resultLimit,
            unreadable, skippedLarge, skippedDirty, budget.stopReasons, work.stopReasons, work.stats,
            dirtyText.size, suppressed.size, captureComplete)
    }

    suspend fun syncExisting(rel: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        withMutation(Mutation.Write(normalized)) { invocation ->
            require(normalized.isNotBlank()) { "File path is empty" }
            val f = resolve(normalized)
            require(f.isFile) { "Not a file: $rel" }
            invocation.admit(callBeforeMutation = false)
            invocation.synchronize()
        }
    }

    suspend fun reconcileExternal(rel: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeRel(rel)
        withMutation(Mutation.Sync(normalized)) { invocation ->
            require(normalized.isNotBlank()) { "Refusing to reconcile the project root implicitly" }
            invocation.admit(callBeforeMutation = false)
            invocation.synchronize()
        }
    }

    fun resolveChecked(rel: String): File = resolve(rel)

    private fun resolve(rel: String): File = PathSecurity.resolveWithin(root, rel).also { target ->
        val path = root.canonicalFile.toPath().relativize(target.toPath()).joinToString("/")
        val parts = path.split('/')
        val custodyLock = parts.size == 4 && parts[0] == ".droide" && parts[1] == "local-delete-custody" && parts.last() == "lock"
        val archiveLock = parts.size == 5 && parts[0] == ".droide" && parts[1] == "local-delete-archives" && parts.last() == "lock"
        require(!custodyLock && !archiveLock) {
            "Dedicated folder recovery lock files are not ordinary project content; use local_deleted_folders to inspect recovery evidence"
        }
    }

    private fun relPath(f: File): String =
        root.canonicalFile.toPath().relativize(f.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')

    private suspend fun readUtf8Bounded(file: File, maxBytes: Long, rel: String, largeFileException: Boolean): String {
        require(maxBytes in 1..Int.MAX_VALUE.toLong()) { "Invalid read limit" }
        return try {
            BoundedUtf8.read(file.inputStream(), maxBytes) { total ->
                if (largeFileException) throw LargeFileException(rel, maxOf(file.length(), total))
                throw IllegalArgumentException("File grew beyond safe edit limit: $rel")
            }
        } catch (invalid: CharacterCodingException) {
            throw UnsupportedTextEncodingException(rel)
        }
    }

    private suspend fun copyStream(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        freeSpaceRoot: File,
        expectedBytes: Long?,
        onProgress: (suspend (StreamWriteProgress) -> Unit)?,
    ): Long {
        val buffer = ByteArray(IMPORT_COPY_BUFFER_BYTES)
        var total = 0L
        var bytesSinceSpaceCheck = IMPORT_SPACE_CHECK_INTERVAL_BYTES
        var bytesSinceProgress = 0L
        var lastProgressAtNanos = System.nanoTime()
        var emptyReads = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) {
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, total, expectedBytes))
                return total
            }
            if (n == 0) {
                require(++emptyReads <= 16) { "Import source stopped providing data; no file was published" }
                continue
            }
            emptyReads = 0
            // File lengths use Long; guard arithmetic without an arbitrary MB/GiB cap.
            check(n.toLong() <= Long.MAX_VALUE - total) { "File length exceeds filesystem byte accounting" }
            bytesSinceSpaceCheck += n
            if (bytesSinceSpaceCheck >= IMPORT_SPACE_CHECK_INTERVAL_BYTES) {
                // Provider size is a hint, not a content limit. Include the pending chunk.
                val remaining = maxOf(expectedBytes?.let { (it - total).coerceAtLeast(0L) } ?: 0L, n.toLong())
                ensureImportFreeSpace(freeSpaceRoot, remaining)
                bytesSinceSpaceCheck = 0L
            }
            output.write(buffer, 0, n)
            total += n
            bytesSinceProgress += n
            val now = System.nanoTime()
            if (bytesSinceProgress >= IMPORT_PROGRESS_INTERVAL_BYTES || now - lastProgressAtNanos >= IMPORT_PROGRESS_MAX_SILENCE_NS) {
                reportStreamProgress(onProgress, StreamWriteProgress(StreamWritePhase.COPYING, total, expectedBytes))
                bytesSinceProgress = 0L
                lastProgressAtNanos = now
            }
        }
    }

    private suspend fun reportStreamProgress(
        callback: (suspend (StreamWriteProgress) -> Unit)?,
        progress: StreamWriteProgress,
        propagateCancellation: Boolean = true,
    ) {
        if (callback == null) return
        try {
            callback(progress)
        } catch (cancelled: CancellationException) {
            if (propagateCancellation) throw cancelled
        } catch (_: Throwable) {
            // A detached/recomposed UI must never turn a successful filesystem transaction into a failed
            // import.

        }
    }

    private fun ensureImportFreeSpace(freeSpaceRoot: File, remainingBytes: Long) {
        WorkspaceTransferSpace.requireAvailable(freeSpaceRoot, remainingBytes, MIN_IMPORT_FREE_BYTES)
    }

    private fun normalizeRel(rel: String): String {
        val normalized = rel.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Absolute paths are not allowed: $rel" }
        require('\u0000' !in normalized) { "Path contains NUL" }
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Dot path segments are not allowed: $rel" }
        return segments.joinToString("/")
    }

    private fun requireOrdinaryMutation(mutation: Mutation) {
        LocalDirectoryDeleteCustody.requireOrdinaryMutation(mutation, root)
        ProjectCopyJournal.requireOrdinaryMutation(mutation, root)
    }

    private suspend fun <T> withMutation(
        mutation: Mutation,
        observation: TextWriteObservation? = null,
        moveObservation: MoveObservation? = null,
        deleteObservation: DeleteObservation? = null,
        streamCompletion: ((MutationInvocation, Throwable?, Boolean) -> Unit)? = null,
        action: suspend (MutationInvocation) -> T,
    ): T =
        WorkspaceFileMutationLocks.withRoot(root) { guard ->
            requireOrdinaryMutation(mutation)
            val invocation = MutationInvocation(guard, mutation, observation, deleteObservation)
            var failure: Throwable? = null
            try {
                action(invocation)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                var finishSucceeded = false
                try { invocation.finish(failure); finishSucceeded = true }
                catch (finishFailure: Throwable) {
                    deleteObservation?.finishFailed = true
                    moveObservation?.finishFailed = true
                    if (failure == null) { failure = finishFailure; throw finishFailure }
                    if (finishFailure !== failure) failure.addSuppressed(finishFailure)
                } finally {
                    if (deleteObservation != null) {
                        try {
                            deleteObservation.ownedCurrent = (failure == null || failure is Exception) &&
                                !deleteObservation.finishFailed && invocation.evidenceComplete &&
                                (failure == null || deleteObservation.synchronizationFailed) &&
                                verifyOwnedDelete(deleteObservation, guard)
                        } catch (completionFailure: Throwable) {
                            deleteObservation.ownedCurrent = false
                            if (failure == null) throw completionFailure
                            if (completionFailure !== failure) failure.addSuppressed(completionFailure)
                        } finally {
                            deleteObservation.guard = null
                            try { deleteObservation.identityPin?.close() }
                            catch (closeFailure: Throwable) {
                                deleteObservation.ownedCurrent = false
                                if (failure == null) throw closeFailure
                                if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                            } finally { deleteObservation.identityPin = null }
                        }
                    }
                    if (moveObservation != null) completeMoveObservation(moveObservation, invocation, failure)
                    // Do not reacquire this root in NonCancellable after IO-return cancellation. All verification
                    // uses this invocation's still-held guard, after observers.
                    if (observation != null) {
                        try { completeTextWriteObservation(observation, guard, failure) }
                        catch (completionFailure: Throwable) {
                            if (failure == null) throw completionFailure
                            if (completionFailure !== failure) failure.addSuppressed(completionFailure)
                        }
                    }
                    if (streamCompletion != null) {
                        try { streamCompletion(invocation, failure, finishSucceeded) }
                        catch (completionFailure: Throwable) {
                            if (failure == null) throw completionFailure
                            if (completionFailure !== failure) failure.addSuppressed(completionFailure)
                        }
                    }
                }
            }
        }

    private fun verifyOwnedDelete(observation: DeleteObservation, guard: WorkspaceMutationGuard): Boolean = try {
        check(observation.guard === guard)
        guard.requireRoot(root)
        requirePublicationParents(observation.parents)
        val preparation = checkNotNull(observation.preparation)
        val receipt = checkNotNull(observation.receipt)
        val original = checkNotNull(observation.original)
        check(original.regularFile && preparation.effect == WorkspaceLocalEffect.DELETE_STAGE &&
            preparation.sourcePath == observation.path && receipt.preparation === preparation &&
            receipt.returned && receipt.successful && receipt.failure == null &&
            receipt.destinationIdentity == original.key.toString() && preparation.sourceIdentity == receipt.destinationIdentity)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.path) == observation.path)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, preparation.relativePath) == preparation.relativePath)
        val source = resolve(observation.path)
        val stage = resolve(preparation.relativePath)
        check(Files.notExists(source.toPath(), LinkOption.NOFOLLOW_LINKS))
        if (observation.cleanupCompleted) {
            check(Files.notExists(stage.toPath(), LinkOption.NOFOLLOW_LINKS))
        } else {
            check(pathMutationVersion(stage) == original)
            if (observation.expected != null) check(observation.expected.matches(stage))
            else StreamContentVersion.of(requireNotNull(observation.streamExpected)).requireMatchesBlocking(stage)
            check(pathMutationVersion(stage) == original)
        }
        guard.requireRoot(root)
        requirePublicationParents(observation.parents)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.path) == observation.path)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, preparation.relativePath) == preparation.relativePath)
        Files.notExists(source.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    private suspend fun completeMoveObservation(
        observation: MoveObservation, invocation: MutationInvocation, failure: Throwable?,
    ) {
        observation.failure = failure
        if (!observation.reached) {
            // Failed-return observers and participant completion run after the local throw. They must not
            // invalidate an already-released retry authority.
            val before = observation.failedAttemptParents
            if (failure is Exception && before != null && !observation.finishFailed &&
                invocation.evidenceComplete && unchangedMoveAttempt(observation, invocation.guard, before))
                observation.inverse?.unchangedAttempt()
            return
        }
        fun committed(): Boolean = try {
            invocation.guard.requireRoot(root)
            requirePublicationParents(observation.parents)
            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.from) == observation.from)
            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.to) == observation.to)
            val original = observation.original
            val destination = pathMutationVersion(resolve(observation.to))
            original != null && destination != null && original.key == destination.key &&
                original.directory == destination.directory && original.regularFile == destination.regularFile &&
                !Files.exists(resolve(observation.from).toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) { false }
        if (committed()) {
            // This is a notification of the original local move, never a second mirror mutation. The normal
            // callback runs once; a failed mirror must not prevent editor path remapping.
            if (!invocation.observerAttempted) withContext(NonCancellable) {
                try { kotlinx.coroutines.withTimeout(5_000) { invocation.notifyLocalObserver() } }
                catch (error: Throwable) {
                    if (failure != null && error !== failure) failure.addSuppressed(error)
                    observation.failure = failure ?: error
                }
            }
            observation.committed = committed()
            if (observation.committed && observation.failure == null) {
                val version = pathMutationVersion(resolve(observation.to))
                if (version == observation.original && version != null) {
                    val transitions = if (invocation.hasSuccessfulMoveReceipt(observation))
                        observation.parentTransitions.filter { transition ->
                            captureMoveParent(transition.path, observation.parents) == transition.after
                        } else emptyList()
                    observation.undo = MoveUndoToken(this, observation.to, observation.from, version,
                        observation.parents, observation.inverse?.identity, transitions)
                }
            }
        }

        observation.uncertain = !observation.committed && runCatching {
            invocation.guard.requireRoot(root)
            requirePublicationParents(observation.parents)
            observation.original != null && ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.from) == observation.from &&
                pathMutationVersion(resolve(observation.from)) == observation.original &&
                !Files.exists(resolve(observation.to).toPath(), LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false).not()
        // Only an explicitly returned regular-file rollback can restore inverse retry authority.

        if (observation.rollbackCompleted && !observation.finishFailed && invocation.evidenceComplete &&
            observation.original?.regularFile == true && !observation.committed && !observation.uncertain &&
            unchangedMoveEndpoints(observation, invocation.guard)) observation.inverse?.unchangedAttempt()
    }

    private fun completeTextWriteObservation(
        observation: TextWriteObservation,
        guard: WorkspaceMutationGuard,
        failure: Throwable?,
    ) {
        val disposition = if (failure != null && failure !is Exception) TextWriteDisposition.RECOVERY_REQUIRED else try {
            guard.requireRoot(root)
            // resolve() canonicalizes: an observer may have moved our inode and left a leaf/ancestor symlink
            // to it. Receipt identity alone cannot authorize that alias.
            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.path) == observation.path)
            val target = resolve(observation.path)
            val before = publicationVersion(target)
            val receipt = observation.receipt
            val owned = observation.writeAttempted && receipt != null && before != null && receipt.preparation.effect == WorkspaceLocalEffect.WRITE &&
                receipt.preparation.relativePath == observation.path && receipt.returned && receipt.successful &&
                receipt.failure == null && receipt.destinationIdentity != null &&
                receipt.destinationIdentity == receipt.preparation.sourceIdentity &&
                before.key.toString() == receipt.destinationIdentity && before.bytes == receipt.preparation.bytes
            // Real local IO, bounded by this writer mode's unchanged cap. This is a stable observation, not
            // a cross-process compare-and-swap or a durable-publication claim.
            val result = when {
                owned && observation.expectedAfter.bytes <= observation.verificationMaxBytes && observation.expectedAfter.matches(target) ->
                    TextWriteDisposition.OWNED_CURRENT
                !observation.writeAttempted && observation.expectedBefore.bytes <= observation.verificationMaxBytes && observation.expectedBefore.matches(target) ->
                    TextWriteDisposition.UNCHANGED_WITHOUT_WRITE
                else -> TextWriteDisposition.RECOVERY_REQUIRED
            }
            guard.requireRoot(root)
            check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.path) == observation.path)
            if (publicationVersion(target) == before) result else TextWriteDisposition.RECOVERY_REQUIRED
        } catch (_: Exception) { TextWriteDisposition.RECOVERY_REQUIRED }
        observation.completed(guard, disposition)
    }

    private inner class MutationInvocation(
        val guard: WorkspaceMutationGuard,
        val mutation: Mutation,
        private val textObservation: TextWriteObservation? = null,
        private val deleteObservation: DeleteObservation? = null,
    ) {
        init { textObservation?.bind(guard, mutation); deleteObservation?.bind(guard, mutation) }
        val evidenceComplete: Boolean get() = !localEvidenceFailed
        private var admitted = false
        private var session: WorkspaceMutationSession? = null
        private var localEvidenceFailed = false
        var localAttempted = false
        var directoryDeleteToken: LocalDirectoryDeleteCustody.Token? = null
        var observerAttempted = false; private set
        private var receipt: WorkspaceLocalReceipt? = null
        private var protectedDeleteStage: Pair<File, List<Pair<File, String>>>? = null
        private var stagePlan: Triple<File, String, String>? = null
        private var trackedStage: OwnedStreamStage? = null

        fun planStage(file: File, parentIdentity: String) {
            check(stagePlan == null)
            val relative = guard.canonicalRoot.toPath().relativize(file.toPath()).joinToString("/")
            check(relative.isNotBlank() && relative.split('/').none { it == ".." || it == "." })
            stagePlan = Triple(file, parentIdentity, relative)
        }

        fun trackStage(stage: OwnedStreamStage) {
            check(stagePlan?.first == stage.file && trackedStage == null)
            trackedStage = stage
        }

        private fun reportStage() {
            val plan = stagePlan ?: return
            val target = (mutation as? Mutation.Write)?.path ?: return
            val observed = trackedStage?.closeObservation ?: OwnedStreamStage.observeCandidate(plan.first, plan.second, null)
            val kind = when (observed.state) {
                StagePathState.ABSENT_AT_RECORDED_PATH -> return
                StagePathState.PRESENT -> RetainedWorkKind.PRESENT
                StagePathState.FOREIGN -> RetainedWorkKind.FOREIGN
                StagePathState.UNVERIFIABLE -> RetainedWorkKind.UNVERIFIABLE
            }
            val saved = receipt?.let { it.returned && it.successful && it.preparation.effect == WorkspaceLocalEffect.WRITE &&
                it.preparation.sourcePath == plan.third && it.preparation.relativePath == target } == true
            reportRetainedWork(RetainedWorkNotice(target,
                RetainedWorkCandidate(plan.third, kind, observed.bytes, observed.detail), saved))
        }

        suspend fun prepareDeleteStage(sourcePath: String): File? {
            val owner = session ?: return null
            check(admitted && mutation is Mutation.Delete && mutation.path == sourcePath)
            guard.requireRoot(root)
            currentCoroutineContext().ensureActive()
            val relative = owner.prepareDeleteStage()
            currentCoroutineContext().ensureActive()
            guard.requireRoot(root)
            if (relative == null) return null
            val parts = relative.split('/')
            check(parts.size == 4 && parts[0] == ".droide" && parts[1] == "saf-delete-custody" &&
                parts[3] == "original" && java.util.UUID.fromString(parts[2]).toString() == parts[2]) {
                "Participant Delete stage is outside its protected namespace"
            }
            check(normalizeRel(relative) == relative &&
                ExplorerFilePolicy.requireUnaliasedProjectPath(root, relative) == relative &&
                relative != sourcePath && !relative.startsWith("$sourcePath/") && !sourcePath.startsWith("$relative/")) {
                "Participant Delete stage aliases or overlaps its source"
            }
            val destination = resolve(relative)
            requireParentExists(destination)
            check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Participant Delete stage already exists" }
            protectedDeleteStage = destination to capturePublicationParents(destination)
            return destination
        }

        fun requireDeleteStage(destination: File) {
            val selected = protectedDeleteStage ?: return
            guard.requireRoot(root)
            check(selected.first == destination)
            requirePublicationParents(selected.second)
            check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Participant Delete stage appeared before move" }
        }

        fun rollbackAllowed(failure: Throwable): Boolean {
            if (session?.externalEffectAttempted == true || localEvidenceFailed) return false
            val retainLocal = try { session?.retainLocalOnSyncFailure == true }
            catch (dispositionFailure: Throwable) {
                // A broken disposition cannot authorize rollback or replace the original failure (especially
                // cancellation). Keep both pieces of evidence.
                if (dispositionFailure !== failure) failure.addSuppressed(dispositionFailure)
                return false
            }
            return !retainLocal && runCatching { guard.requireRoot(root) }.isSuccess
        }

        fun hasSuccessfulMoveReceipt(observation: MoveObservation): Boolean {
            val saved = receipt ?: return false
            val prepared = observation.preparation ?: return false
            return evidenceComplete && saved.preparation === prepared &&
                prepared.effect == WorkspaceLocalEffect.MOVE &&
                prepared.sourcePath == observation.from && prepared.relativePath == observation.to &&
                prepared.sourceIdentity == observation.original?.key?.toString() &&
                saved.destinationIdentity == prepared.sourceIdentity && saved.returned &&
                saved.successful && saved.failure == null
        }

        fun deleteStageReceipt(preparation: WorkspaceLocalPreparation): WorkspaceLocalReceipt? {
            if (session == null) return null
            check(admitted)
            guard.requireRoot(root)
            val saved = checkNotNull(receipt) { "Delete stage receipt is missing" }
            check(mutation is Mutation.Delete && preparation.effect == WorkspaceLocalEffect.DELETE_STAGE &&
                preparation.sourcePath == mutation.path && saved.preparation === preparation &&
                saved.returned && saved.successful && saved.failure == null &&
                preparation.sourceIdentity != null && saved.destinationIdentity == preparation.sourceIdentity) {
                "Delete stage receipt does not belong to this invocation"
            }
            return saved
        }

        fun retainsDeleteStage(saved: WorkspaceLocalReceipt?): Boolean {
            if (saved == null) return false
            check(deleteStageReceipt(saved.preparation) === saved) { "Delete stage receipt changed" }
            val retained = session?.retainsDeleteStage(saved) == true
            guard.requireRoot(root)
            return retained
        }

        suspend fun admit(callBeforeMutation: Boolean = true) {
            check(!admitted)
            requireOrdinaryMutation(mutation)
            guard.requireRoot(root)
            WorkspaceProcessPathGuard.requireSafe(root, mutation)
            textObservation?.requireAdmission(guard)
            if (callBeforeMutation) beforeMutation?.invoke(mutation)
            requireOrdinaryMutation(mutation)
            guard.requireRoot(root)
            textObservation?.requireAdmission(guard)
            currentCoroutineContext().ensureActive()
            session = participant?.begin(guard, mutation)?.also {
                check(it.guard === guard && it.mutation === mutation) { "Participant returned a different mutation session" }
            }
            requireOrdinaryMutation(mutation)
            textObservation?.requireAdmission(guard)
            admitted = true
        }

        suspend fun prepare(preparation: WorkspaceLocalPreparation, stage: OwnedStreamStage? = null) {
            check(admitted)
            guard.requireRoot(root)
            currentCoroutineContext().ensureActive()
            if (session != null) stage?.retainForRecovery()
            session?.prepareLocal(preparation)
            requireOrdinaryMutation(mutation)
            currentCoroutineContext().ensureActive()
            guard.requireRoot(root)
            textObservation?.requireAdmission(guard)
        }

        suspend fun recheckAdmission() {
            check(admitted)
            guard.requireRoot(root)
            beforeMutation?.invoke(mutation)
            requireOrdinaryMutation(mutation)
            guard.requireRoot(root)
            currentCoroutineContext().ensureActive()
        }

        fun beforeAttempt(preparation: WorkspaceLocalPreparation) {
            requireOrdinaryMutation(mutation)
            guard.requireRoot(root)
            if (preparation.effect == WorkspaceLocalEffect.MOVE || preparation.effect == WorkspaceLocalEffect.DELETE_STAGE)
                WorkspaceProcessPathGuard.requireSafe(root, mutation)
            textObservation?.requireAdmission(guard)
            try { session?.beforeLocalAttempt(preparation) }
            catch (failure: Throwable) { localEvidenceFailed = true; throw failure }
            requireOrdinaryMutation(mutation)
            guard.requireRoot(root)
            textObservation?.requireAdmission(guard)
        }

        fun returned(observation: WorkspaceLocalReceipt) {
            // Store before calling any observer, and before any dispatcher return can cancel.
            receipt = observation
            textObservation?.returned(guard, observation)
            deleteObservation?.receipt = observation
            try { session?.localReturned(observation) }
            catch (failure: Throwable) { localEvidenceFailed = true; throw failure }
        }

        fun attemptingTextWrite(preparation: WorkspaceLocalPreparation) {
            localAttempted = true
            textObservation?.attempting(guard, preparation)
        }

        fun recordFailedReturn(preparation: WorkspaceLocalPreparation, didReturn: Boolean, failure: Throwable) {
            try { returned(WorkspaceLocalReceipt(preparation, didReturn, null, failure = failure)) }
            catch (recordFailure: Throwable) { if (recordFailure !== failure) failure.addSuppressed(recordFailure) }
        }

        suspend fun localEffect(
            preparation: WorkspaceLocalPreparation,
            destination: File,
            beforeEffect: (suspend () -> Unit)? = null,
            publicationBarrier: (suspend (() -> Unit) -> Unit)? = null,
            effect: () -> Boolean,
        ): Boolean {
            prepare(preparation)
            beforeAttempt(preparation)
            beforeEffect?.invoke()
            requireOrdinaryMutation(mutation)
            localAttempted = true
            textObservation?.attemptingLocal(guard)
            val result = try {
                if (publicationBarrier == null) effect() else {
                    var result: Boolean? = null
                    var entered = false
                    val caller = currentCoroutineContext()
                    publicationBarrier { check(!entered); entered = true; caller.ensureActive(); result = effect() }
                    checkNotNull(result) { "Delete barrier did not execute" }
                }
            }
            catch (failure: Throwable) { recordFailedReturn(preparation, false, failure); throw failure }
            returned(WorkspaceLocalReceipt(preparation, true,
                pathMutationVersion(destination)?.key?.toString(), successful = result))
            return result
        }

        suspend fun createParents(target: File) {
            val parent = target.parentFile ?: return
            if (parent.isDirectory) return
            val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.CREATE_PARENTS, relPath(parent))
            check(localEffect(preparation, parent) { parent.mkdirs() }) { "Cannot create destination parents" }
        }

        suspend fun synchronize() {
            check(admitted)
            guard.requireRoot(root)
            currentCoroutineContext().ensureActive()
            session?.synchronize()
            notifyLocalObserver()
        }

        suspend fun notifyLocalObserver() {
            guard.requireRoot(root)
            check(!observerAttempted)
            observerAttempted = true
            onMutation?.invoke(mutation)
            guard.requireRoot(root)
        }

        suspend fun reconcileBestEffort(mutation: Mutation): Boolean {
            // An owned invocation must never manufacture a second unjournaled operation. Keep the
            // established non-participant recovery contract unchanged.
            return participant != null || this@FileRepository.reconcileBestEffort(mutation)
        }

        fun finish(failure: Throwable?) {
            var finishFailure: Throwable? = null
            try {
                session?.finish(WorkspaceMutationOutcome(localAttempted, receipt,
                    session?.externalEffectAttempted == true, failure))
            } catch (caught: Throwable) { finishFailure = caught; throw caught }
            finally {
                try { reportStage() } catch (_: Exception) { } catch (_: LinkageError) { }
                directoryDeleteToken?.let { token ->
                    try {
                        val final = directoryCustody.inspect(token.operation, token)
                        reportDirectoryDeletion(final)
                        if (failure == null && finishFailure == null) check(final.state == LocalDirectoryDeleteCustody.State.RETAINED) {
                            "Directory custody changed during mutation completion; inspect ${final.retainedPath} before retry"
                        }
                    } catch (inspectionFailure: Throwable) {
                        val original = finishFailure ?: failure
                        if (original == null) throw inspectionFailure
                        if (inspectionFailure !== original) original.addSuppressed(inspectionFailure)
                    }
                }
            }
        }
    }

    // Reconciliation must run even if the user cancelled the original mutation.
    private suspend fun reconcileBestEffort(mutation: Mutation): Boolean =
        withContext(NonCancellable) {
            try {
                onMutation?.invoke(mutation)
                true
            } catch (_: Throwable) {
                // The original mutation error remains authoritative. A failed callback must not silently restore
                // authority for a rolled-back inverse.
                false
            }
        }

    private data class PublicationVersion(val key: Any, val bytes: Long, val modified: java.nio.file.attribute.FileTime)

    private fun capturePublicationParents(target: File): List<Pair<File, String>> {
        val canonicalRoot = root.canonicalFile
        val relativeParent = canonicalRoot.toPath().relativize(target.parentFile.toPath())
        require(!relativeParent.isAbsolute && relativeParent.none { it.toString() == ".." })
        val parents = mutableListOf(canonicalRoot)
        var parent = canonicalRoot
        relativeParent.forEach { segment -> if (segment.toString().isNotEmpty()) {
            parent = File(parent, segment.toString())
            parents += parent
        } }
        return parents.map { directory ->
            val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            require(attributes.isDirectory) { "Import parent is not a verified directory" }
            directory to requireNotNull(attributes.fileKey()) { "Import parent identity is unavailable" }.toString()
        }
    }

    private fun requirePublicationParents(parents: List<Pair<File, String>>) {
        parents.forEach { (directory, identity) ->
            val attributes = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isDirectory && attributes.fileKey()?.toString() == identity) {
                "Import project or parent changed; inspect recovery before trying again"
            }
        }
    }

    private fun publicationVersion(file: File): PublicationVersion? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isRegularFile) null else attributes.fileKey()?.let {
            PublicationVersion(it, attributes.size(), attributes.lastModifiedTime())
        }
    }.getOrNull()

    private fun synchronizationFailure(
        failure: Throwable, relativePath: String, bytes: Long, target: File, published: PublicationVersion?,
    ): Throwable {
        // Never delete by pathname after a suspending callback: a terminal/editor may have replaced or
        // edited it.
        if (failure is CancellationException || failure !is Exception || published == null) return failure
        return if (publicationVersion(target) == published) ProjectFileSyncException(relativePath, bytes, failure) else failure
    }

    private fun moveLocal(src: File, dst: File, requireExistingParent: Boolean = false): Boolean {
        if (requireExistingParent) requireParentExists(dst) else dst.parentFile?.mkdirs()
        pathMover.move(src, dst)
        return true
    }

    internal data class PathMutationVersion(
        val key: Any, val regularFile: Boolean, val directory: Boolean,
        val bytes: Long, val modified: java.nio.file.attribute.FileTime,
    )

    private fun pathMutationVersion(file: File): PathMutationVersion? = runCatching {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attrs.isRegularFile && !attrs.isDirectory) null else attrs.fileKey()?.let {
            PathMutationVersion(it, attrs.isRegularFile, attrs.isDirectory, attrs.size(), attrs.lastModifiedTime())
        }
    }.getOrNull()

    /** Optional, bounded evidence for one direct parent, never a recursive tree scan or a writer
     * barrier. */
    private fun captureMoveParent(path: String, parents: List<Pair<File, String>>): MoveParentSnapshot? = try {
        requirePublicationParents(parents)
        val directory = resolve(path)
        val before = checkNotNull(pathMutationVersion(directory))
        check(before.directory)
        val entries = linkedMapOf<String, PathMutationVersion>()
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (entry in stream) {
                check(entries.size < 256) { "Move history parent exceeds its bounded snapshot" }
                entries[entry.fileName.toString()] = checkNotNull(pathMutationVersion(entry.toFile()))
            }
        }
        check(pathMutationVersion(directory) == before)
        requirePublicationParents(parents)
        MoveParentSnapshot(before, entries.toMap())
    } catch (_: Exception) { null }

    private fun captureMoveParents(observation: MoveObservation): Map<String, MoveParentSnapshot> =
        listOf(observation.from.substringBeforeLast('/', ""), observation.to.substringBeforeLast('/', ""))
            .distinct().mapNotNull { path -> captureMoveParent(path, observation.parents)?.let { path to it } }.toMap()

    private fun unchangedMoveEndpoints(observation: MoveObservation, guard: WorkspaceMutationGuard): Boolean = try {
        guard.requireRoot(root)
        requirePublicationParents(observation.parents)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.from) == observation.from)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.to) == observation.to)
        observation.original != null && pathMutationVersion(resolve(observation.from)) == observation.original &&
            Files.notExists(resolve(observation.to).toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    private fun unchangedMoveAttempt(
        observation: MoveObservation, guard: WorkspaceMutationGuard,
        before: Map<String, MoveParentSnapshot>,
    ): Boolean = try {
        guard.requireRoot(root)
        requirePublicationParents(observation.parents)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.from) == observation.from)
        check(ExplorerFilePolicy.requireUnaliasedProjectPath(root, observation.to) == observation.to)
        val expectedParents = setOf(observation.from.substringBeforeLast('/', ""),
            observation.to.substringBeforeLast('/', ""))
        check(before.keys == expectedParents)
        check(observation.original != null && pathMutationVersion(resolve(observation.from)) == observation.original)
        check(Files.notExists(resolve(observation.to).toPath(), LinkOption.NOFOLLOW_LINKS))
        check(before.all { (path, snapshot) -> captureMoveParent(path, observation.parents) == snapshot })
        guard.requireRoot(root)
        requirePublicationParents(observation.parents)
        pathMutationVersion(resolve(observation.from)) == observation.original &&
            Files.notExists(resolve(observation.to).toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    private fun observeMoveParentTransitions(
        observation: MoveObservation, before: Map<String, MoveParentSnapshot>,
    ): List<MoveParentTransition> {
        val original = observation.original ?: return emptyList()
        val sourceParent = observation.from.substringBeforeLast('/', "")
        val destinationParent = observation.to.substringBeforeLast('/', "")
        return before.mapNotNull { (path, snapshot) ->
            val expected = snapshot.entries.toMutableMap()
            if (path == sourceParent && expected.remove(observation.from.substringAfterLast('/')) != original)
                return@mapNotNull null
            if (path == destinationParent && expected.put(observation.to.substringAfterLast('/'), original) != null)
                return@mapNotNull null
            val after = captureMoveParent(path, observation.parents) ?: return@mapNotNull null
            if (snapshot.version.key != after.version.key || after.entries != expected) null
            else MoveParentTransition(path, snapshot, after)
        }
    }

    private fun rollbackMovedFile(from: String, to: String, src: File, dst: File, original: PathMutationVersion?): Boolean {
        // A directory's metadata cannot prove that its descendants were not edited. Preserve that
        // directory at its moved/staged path instead of scanning/hashing an entire tree.
        if (original?.regularFile != true) return false
        return try {
            require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, from) == from)
            require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, to) == to)
            requireParentExists(src)
            if (src.exists() || PathSecurity.isSymbolicLink(src) || pathMutationVersion(dst) != original) return false
            // The metadata check is not a filesystem CAS; the final no-replace publication nevertheless
            // cannot overwrite a source recreated between this check and rename.
            pathMover.move(dst, src)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private fun mutationSynchronizationFailure(failure: Throwable, message: String): Throwable {
        if (failure is CancellationException || failure !is Exception) {
            failure.addSuppressed(IllegalStateException(message))
            return failure
        }
        return IllegalStateException(message, failure)
    }

    private fun requireVersion(target: File, rel: String, expected: DiskFileVersion) {
        require(expected.matches(target)) { "File changed on disk: $rel; newer workspace data was preserved. Review or reload before retrying." }
    }

    private fun requireParentExists(target: File) {
        require(target.parentFile?.isDirectory == true) { "Destination folder no longer exists; choose another project folder" }
    }

    private fun requireExplorerDestination(relative: String, target: File) {
        require(ExplorerFilePolicy.requireUserTarget(root, relative) == relative) { "Destination folder changed; choose it again" }
        requireParentExists(target)
    }

    private fun requireExplorerSource(relative: String) {
        // .droide may be the source of an explicit user-file recovery move, never its target.
        require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, relative) == relative) { "Source folder changed; choose it again" }
    }

    private suspend fun atomicWrite(
        target: File, content: String, invocation: MutationInvocation, encodedBytes: Long, requireAbsent: Boolean = false,
        beforeCommit: () -> Unit = {}, commitPublication: (suspend (() -> Unit) -> Unit)? = null,
        afterCommit: suspend (PublicationVersion?) -> Unit,
    ) {
        val publicationParents = capturePublicationParents(target)
        ensureImportFreeSpace(root, encodedBytes)
        val planned = tempSibling(target)
        invocation.planStage(planned, publicationParents.last().second)
        OwnedStreamStage.create(target, planned.name).use { stage ->
            invocation.trackStage(stage)
            val tmp = stage.file
            currentCoroutineContext().ensureActive()
            BoundedUtf8.write(stage.output, content)
            currentCoroutineContext().ensureActive()
            beforeCommit()
            fun applyCurrentPermissions() {
                if (!target.isFile || requireAbsent) return
                // Rename replaces the inode. Preserve ordinary rwx bits, including executable scripts, and do
                // not silently defeat a user's read-only file via its parent.
                val permissions = Files.getPosixFilePermissions(target.toPath(), LinkOption.NOFOLLOW_LINKS)
                require(PosixFilePermission.OWNER_WRITE in permissions) {
                    "File is read-only: ${target.name}; change its permissions explicitly before saving"
                }
                stage.requireOwned()
                Files.setPosixFilePermissions(tmp.toPath(), permissions)
            }
            currentCoroutineContext().ensureActive()
            val published = requireNotNull(publicationVersion(tmp)) { "Text stage identity is unavailable" }
            check(published.key.toString() == stage.identity && published.bytes == encodedBytes) {
                "Text staging file changed before preparation"
            }
            val preparation = WorkspaceLocalPreparation(WorkspaceLocalEffect.WRITE, relPath(target),
                relPath(tmp), published.key.toString(), encodedBytes)
            invocation.prepare(preparation, stage)
            beforeCommit()
            requirePublicationParents(publicationParents)
            applyCurrentPermissions()
            stage.requireOwned()
            check(publicationVersion(tmp) == published) { "Text staging file changed during preparation" }
            tmp.inputStream().use {
                invocation.beforeAttempt(preparation)
                beforeCommit()
                requirePublicationParents(publicationParents)
                applyCurrentPermissions()
                stage.requireOwned()
                check(publicationVersion(tmp) == published) { "Text staging file changed during admission" }
                var observed: PublicationVersion? = null
                // Only the final rename and its receipt run on the editor owner. Encoding, hashing, staging and
                // observer delivery stay outside this short barrier.
                val publish = {
                    invocation.attemptingTextWrite(preparation)
                    try {
                        if (requireAbsent) publishFileAbsent(tmp, target) else replaceFile(tmp, target)
                    } catch (failure: Throwable) {
                        invocation.recordFailedReturn(preparation, false, failure)
                        throw failure
                    }
                    val receiptVersion = try {
                        invocation.guard.requireRoot(root)
                        requirePublicationParents(publicationParents)
                        requireNotNull(publicationVersion(target)) { "Published text destination is missing or not a regular file" }
                            .also { check(it == published) { "Published text destination changed before its receipt" } }
                    } catch (failure: Throwable) {
                        invocation.recordFailedReturn(preparation, true, failure)
                        if (failure is CancellationException || failure !is Exception) throw failure
                        throw ProjectFilePublicationUncertainException(preparation.relativePath, encodedBytes, failure)
                    }
                    try {
                        // Existing editor text publication does not force data/parents. The receipt truthfully reports
                        // local visibility only, not durability.
                        invocation.returned(WorkspaceLocalReceipt(preparation, true, receiptVersion.key.toString()))
                        invocation.guard.requireRoot(root)
                        requirePublicationParents(publicationParents)
                        check(publicationVersion(target) == receiptVersion) {
                            "Published text changed during receipt callbacks; retained staging data requires recovery"
                        }
                    } catch (failure: Throwable) {
                        throw synchronizationFailure(failure, relPath(target), encodedBytes, target, published)
                    }
                    stage.retainForRecovery()
                    observed = receiptVersion
                }
                if (commitPublication == null) publish() else commitPublication(publish)
                afterCommit(published)
                invocation.guard.requireRoot(root)
                requirePublicationParents(publicationParents)
                releaseStageAfterSynchronization(stage, target, checkNotNull(observed))
            }
        }
    }

    private fun releaseStageAfterSynchronization(stage: OwnedStreamStage, target: File, published: PublicationVersion) {
        if (publicationVersion(target) == published) {
            stage.publicationVerified()
        } else {

            check(publicationVersion(stage.file)?.key != published.key) {
                "Published file changed during synchronization; retained staging data requires recovery"
            }
        }
    }

    // A valid 255-byte target name must not become an invalid staging name after adding a suffix.
    private fun tempSibling(target: File): File = File(target.parentFile, ".droide-tmp-${java.util.UUID.randomUUID()}")

    private fun replaceFile(tmp: File, target: File) {
        // A failed replacement can have taken effect; never retry an uncertain mutation.
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun publishFileAbsent(tmp: File, target: File) {
        newFilePublisher.publish(tmp, target)
    }

    private fun rejectBinary(f: File, rel: String) {
        if (looksBinary(f)) throw BinaryFileException(rel)
    }

    private fun looksBinary(f: File): Boolean {
        if (!f.isFile || f.length() == 0L) return false
        val head = ByteArray(4096)
        val n = f.inputStream().use { it.read(head) }
        if (n <= 0) return false
        var suspicious = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xff
            if (b == 0) return true
            if (b < 0x09 || (b in 0x0E..0x1F)) suspicious++
        }
        return suspicious > n / 10
    }

    private suspend fun loadIgnoreRules(): RootIgnoreRules {
        val file = File(root.canonicalFile, ".gitignore")
        try {
            val attributes = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: java.nio.file.NoSuchFileException) { return RootIgnoreRules.EMPTY }
            if (!attributes.isRegularFile) throw RootIgnoreException("expected a regular non-symlink file")
            return RootIgnoreRules.read(Files.newInputStream(file.toPath(),
                java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RootIgnoreException) { throw failure }
        catch (failure: Exception) { throw RootIgnoreException("cannot read exclusions; search was stopped", failure) }
    }

    private fun ignored(rel: String, rules: RootIgnoreRules): Boolean = rules.isIgnored(rel)

    private fun isInternalPath(rel: String): Boolean = rel == ".git" || rel.startsWith(".git/") ||
        rel == ".droide" || rel.startsWith(".droide/")

    // Search already excludes these results; pruning prevents runtime caches and Git objects from
    // using the bounded scan budget before project files can be visited.
    private fun canSearchDescend(directory: File, ignore: RootIgnoreRules, includeDependencies: Boolean): Boolean {
        if (!PathSecurity.canDescend(root, directory)) return false
        val relative = relPath(directory)
        if (relative.isBlank()) return true
        return !isInternalPath(relative) && !SensitivePathPolicy.isSensitive(relative) &&
            !DependencyDiscoveryPolicy.isExcluded(relative, true, includeDependencies) && ignore.canDescend(relative)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

    companion object {
        internal fun forAdmittedStreamWrites(
            root: File,
            admission: suspend (WorkspaceMutationGuard) -> Unit,
        ): FileRepository = FileRepository(root, createRoot = false).also { it.streamWriteAdmission = admission }

        private const val MAX_DIRECTORY_ENTRIES = 5_000
        private const val MAX_GLOB_RESULTS = 500
        private const val MAX_SEARCH_RESULTS = 500
        internal const val MAX_TEXT_WRITE_BYTES = 5L * 1024L * 1024L

        private const val IMPORT_COPY_BUFFER_BYTES = 256 * 1024
        private const val IMPORT_SPACE_CHECK_INTERVAL_BYTES = 16L * 1024 * 1024
        private const val IMPORT_PROGRESS_INTERVAL_BYTES = 4L * 1024 * 1024
        private const val IMPORT_PROGRESS_MAX_SILENCE_NS = 250L * 1_000_000L
        private const val MIN_IMPORT_FREE_BYTES = 192L * 1024 * 1024
    }
}

data class FileEntry(val path: String, val isDir: Boolean, val size: Long, val modified: Long)

data class DirectoryPageCursor(
    val path: String,
    val isDir: Boolean,
    val options: DirectoryListingOptions = DirectoryListingOptions(),
    val modified: Long = 0L,
)
data class DirectoryPage(val entries: List<FileEntry>, val nextCursor: DirectoryPageCursor?)
