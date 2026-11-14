package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Picker/source ticket. A project ID, runtime owner, request generation and app epoch are distinct. */
data class ExportSourceTicket internal constructor(
    val projectId: String,
    val runtimeOwner: String,
    val epoch: String,
    val generation: Long,
    val rootPath: String,
    val scope: ExportScope,
    val dirtyVersions: Map<String, WorkspaceDocumentVersion>,
    val sourceTreeUri: String?,
    internal val rootIdentity: String,
    internal val scopeIdentity: String,
) {
    /** Identity-bound, not a filename/extension heuristic. Directories, symlinks and app-owned
     * metadata stay strict. In particular a cold SQLite owner may write before registration. */
    val concurrentFileCapture: Boolean get() = scopeIdentity.endsWith(":false:false:true") &&
        scope.relativePath.substringBefore('/') != ".droide"
}

/** A past observation, not a lease, reservation, or proof that external writers are quiet. */
internal data class ExportPreflightSnapshot(val blockers: List<String>) {
    val canChooseDestination: Boolean get() = blockers.isEmpty()
}

/** Local capture only. Neither picker nor destination/provider I/O may run in withCapture. */
class ExportSourceAdmission internal constructor(
    private val projectId: String,
    private val root: File,
    private val documents: WorkspaceDocumentAuthority,
    private val requireOwnerSafe: () -> Unit,
    private val requireSourceSafe: (ExportScope) -> Unit = {},
    private val sourceTreeUri: String? = null,
    private val knownWriterBlockers: () -> List<String> = { emptyList() },
    private val requireEpoch: () -> String = ApplicationWorkAdmission::requireOpen,
    private val epochOpen: (String) -> Boolean = ApplicationWorkAdmission::isCurrentOpen,
) {
    private val owner = UUID.randomUUID().toString()
    private val monitor = Any()
    private var generation = 0L
    private var retired = false
    private var paused = false
    private var captureJob: Job? = null
    private var captureDone: CompletableDeferred<Unit>? = null

    suspend fun ticket(scope: ExportScope): ExportSourceTicket {
        val epoch = requireEpoch()
        val (rootIdentity, selectedIdentity) = withContext(Dispatchers.IO) {
            requireOwnerSafe()
            identity(root, requireDirectory = true) to identity(scopeFile(scope))
        }
        val dirty = dirtyVersions(scope)
        return synchronized(monitor) {
            check(!retired && !paused) { "Export owner is retired or switching projects" }
            check(captureJob == null) { "An export capture is already active" }
            ExportSourceTicket(projectId, owner, epoch, ++generation, root.absolutePath, scope, dirty, sourceTreeUri, rootIdentity, selectedIdentity)
        }
    }

    private fun validateOwnerRoot(ticket: ExportSourceTicket) {
        synchronized(monitor) {
            check(!retired && !paused && ticket.runtimeOwner == owner && ticket.projectId == projectId && ticket.generation == generation) {
                "Stale export request; select the source again"
            }
        }
        check(epochOpen(ticket.epoch)) { "Application Exit closed this export request" }
        requireOwnerSafe()
        check(ticket.rootPath == root.absolutePath && identity(root, true) == ticket.rootIdentity) {
            "Export source identity changed; select the source again"
        }
    }

    fun validate(ticket: ExportSourceTicket) {
        validateOwnerRoot(ticket)
        check(identity(scopeFile(ticket.scope)) == ticket.scopeIdentity) { "Export scope identity changed; select the source again" }
    }

    /** Call only after the existing scoped save coordinator reports ready using its save receipts.
     * An atomic save legitimately replaces a file inode; never rebind the root or runtime owner. */
    suspend fun refreshAfterScopedSave(ticket: ExportSourceTicket, savedPaths: Set<String>): ExportSourceTicket {
        withContext(Dispatchers.IO) { validateOwnerRoot(ticket) }
        check(dirtyVersions(ticket.scope).isEmpty()) { "New editor changes need review after scoped save" }
        val nextIdentity = withContext(Dispatchers.IO) {
            validateOwnerRoot(ticket)
            identity(scopeFile(ticket.scope)).also { next ->
                val savedSelectedFile = ticket.scope.relativePath in savedPaths && ticket.scopeIdentity.endsWith(":false:false:true")
                if (!savedSelectedFile) check(next == ticket.scopeIdentity) { "Unsaved export scope identity changed" }
                check(next.split(':').takeLast(3) == ticket.scopeIdentity.split(':').takeLast(3)) { "Saved export source changed entry type" }
            }
        }
        return ticket.copy(scopeIdentity = nextIdentity, dirtyVersions = emptyMap())
    }

    /** Save is performed by the existing editor coordinator BEFORE calling this method. */
    suspend fun prepare(ticket: ExportSourceTicket, allowDirtySavedVersion: Boolean): ExportSourceTicket {
        withContext(Dispatchers.IO) { validate(ticket) }
        val dirty = dirtyVersions(ticket.scope)
        if (allowDirtySavedVersion) check(dirty == ticket.dirtyVersions) {
            "Editor changes changed while choosing export; review the saved-version choice again"
        } else check(dirty.isEmpty()) { "Save the selected editor changes before exporting" }
        withContext(Dispatchers.IO) { validate(ticket) }
        return ticket.copy(dirtyVersions = dirty)
    }

    /** Inspect known local blockers before a picker can create an output. No database close,
     * checkpoint, journal replay, source write or capture lease is performed here.
     * The atomic admission in withCapture remains authoritative after this observation. */
    internal suspend fun preflight(ticket: ExportSourceTicket): ExportPreflightSnapshot = withContext(Dispatchers.IO) {
        validate(ticket)
        requireSourceSafe(ticket.scope)
        val blockers = if (ticket.concurrentFileCapture)
            ExportWriterAdmission.snapshotVerifiedFileBlockers(scopeFile(ticket.scope))
        else (ExportWriterAdmission.snapshotBlockers(scopeFile(ticket.scope), prepareManagedWriters = true) + knownWriterBlockers()).distinct().sorted()
        validate(ticket)
        ExportPreflightSnapshot(blockers)
    }

    suspend fun <T> withCapture(ticket: ExportSourceTicket, externalQuiescenceConfirmed: Boolean,
        action: suspend (ExportCapturePermit) -> T): T {
        check(ticket.concurrentFileCapture || externalQuiescenceConfirmed) { "Confirm that external tools are not writing this workspace before capture" }
        validate(ticket)
        check(dirtyVersions(ticket.scope) == ticket.dirtyVersions) { "Editor changes changed after export preparation" }
        val job = checkNotNull(currentCoroutineContext()[Job]) { "Export capture requires a cancellable owner" }
        synchronized(monitor) {
            check(!retired && !paused && captureJob == null && ticket.generation == generation) { "Export capture owner changed" }
            captureJob = job
            captureDone = CompletableDeferred()
        }
        try {
            // Existing mutex includes repository participants, receipts, and reconciliation. Do not
            // acquire transition/editor locks here and never save or call a provider from action.
            return WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
                validate(ticket)
                check(dirtyVersions(ticket.scope) == ticket.dirtyVersions) { "Editor changes changed while waiting for capture" }
                // Repository mutations still hold the workspace-wide mutex. The
                // auxiliary writer fence covers the selected subtree: an idle
                // database in .droide/sessions must not block exporting an APK.
                requireSourceSafe(ticket.scope)
                suspend fun capture(): T {
                    validate(ticket)
                    if (!ticket.concurrentFileCapture) check(knownWriterBlockers().isEmpty()) { "A workspace task started during export preparation" }
                    val permit = object : VerifiedFileExportCapturePermit {
                        override val verifySingleFile: Boolean = ticket.concurrentFileCapture
                        override val selectedFileIdentity: String = ticket.scopeIdentity
                        override val root: File = this@ExportSourceAdmission.root
                        override val scope: ExportScope = ticket.scope
                        override val stabilityAssumptions: List<String> = if (verifySingleFile) listOf(
                            "One regular saved file copied with stable identity and nanosecond change metadata, then source bytes verified again; no OS snapshot is claimed.",
                            "Other tasks may continue. Folder snapshots and managed database ownership keep their separate coordination.",
                        ) else listOf(
                            "User confirmed external shell, build, and other tools do not write during capture; no OS snapshot is claimed.",
                            "Repository mutations are blocked across this workspace only during local capture.",
                        )
                        override fun validate() {
                            job.ensureActive()
                            guard.requireRoot(root)
                            this@ExportSourceAdmission.validate(ticket)
                            if (!verifySingleFile) {
                                val active = knownWriterBlockers()
                                check(active.isEmpty()) { "Source is not quiescent: ${active.joinToString()}" }
                            }
                        }
                    }
                    return action(permit).also { permit.validate() }
                }
                if (ticket.concurrentFileCapture) {
                    ExportWriterAdmission.withVerifiedFileCapture(scopeFile(ticket.scope)) { capture() }
                } else {
                    val blockers = knownWriterBlockers()
                    check(blockers.isEmpty()) { "Source is not quiescent: ${blockers.joinToString()}" }
                    ExportWriterAdmission.withQuiescentCapture(scopeFile(ticket.scope)) { capture() }
                }
            }
        } finally {
            val done = synchronized(monitor) {
                if (captureJob === job) {
                    captureJob = null
                    captureDone.also { captureDone = null }
                } else null
            }
            // Completion can resume a switch coroutine inline; never deliver under the monitor.
            done?.complete(Unit)
        }
    }

    /** Invalidates picker callbacks immediately. Only a local capture is cancelled/drained. */
    internal suspend fun pauseAndDrain() {
        val (job, done) = synchronized(monitor) { paused = true; generation++; captureJob to captureDone }
        job?.cancel(CancellationException("Workspace is switching before export capture sealed"))
        done?.await()
    }
    internal fun resume() { synchronized(monitor) { if (!retired) paused = false } }
    internal fun retire() {
        val job = synchronized(monitor) { retired = true; generation++; captureJob }
        job?.cancel(CancellationException("Export source owner retired"))
    }
    internal suspend fun retireAndDrain() { retire(); synchronized(monitor) { captureDone }?.await() }

    private suspend fun dirtyVersions(scope: ExportScope): Map<String, WorkspaceDocumentVersion> =
        documents.dirtySnapshots().filter { it.dirty && inScope(scope.relativePath, it.path) }.associate { it.path to it.version }

    private fun scopeFile(scope: ExportScope): File {
        val relative = scope.relativePath
        require(!relative.startsWith('/') && '\u0000' !in relative) { "Invalid export scope" }
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "Invalid export scope" }
        var current = root
        identity(current, true)
        parts.forEachIndexed { index, part ->
            current = File(current, part)
            if (index < parts.lastIndex) identity(current, true)
        }
        return current
    }
    private fun identity(file: File, requireDirectory: Boolean = false): String {
        // Bind ticket and descriptor stamps to the same numeric dev/inode representation.
        // Do not read fileKey and dev/inode in separate syscalls (rename ABA window).
        val attrs = Files.readAttributes(file.toPath(), "unix:dev,ino,isDirectory,isSymbolicLink,isRegularFile", NOFOLLOW_LINKS)
        val directory = attrs.getValue("isDirectory") as Boolean
        val symlink = attrs.getValue("isSymbolicLink") as Boolean
        val regular = attrs.getValue("isRegularFile") as Boolean
        if (requireDirectory) check(directory && !symlink) { "Export ancestor is not a real directory" }
        return "${(attrs.getValue("dev") as Number).toLong()}:${(attrs.getValue("ino") as Number).toLong()}:$directory:$symlink:$regular"
    }
    companion object {
        internal fun inScope(scope: String, path: String): Boolean = scope.isEmpty() || path == scope || path.startsWith("$scope/")
    }
}
