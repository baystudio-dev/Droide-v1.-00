package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class MirrorRetainedRecovery(private val source: File, private val working: File) {
    enum class Role(val label: String) { ORIGINAL("CLI version"), STABLE("Stable snapshot"), PREPARED("Prepared version") }
    data class Item(val operation: MirrorMetadataAuthority.Intent, val role: Role,
                    val kind: AnchoredWorkspaceFiles.Kind, val bytes: Long?) {
        val canCopy: Boolean get() = kind == AnchoredWorkspaceFiles.Kind.FILE
    }
    data class Cursor(val revision: Long, val after: String)
    data class Page(val items: List<Item>, val next: Cursor?,
                    val interrupted: List<MirrorMetadataAuthority.Intent> = emptyList(),
                    val conflicts: List<String> = emptyList())
    class Inspection internal constructor(
        internal val owner: MirrorRetainedRecovery,
        val item: Item,
        val version: CanonicalFileSnapshot.Version,
        internal val parentIdentity: String,
    )

    private val directory = File(working.parentFile, ".${working.name}-sync-authority")
    private val authority = MirrorMetadataAuthority(directory, source, working)
    private val snapshots = CanonicalFileSnapshot(Long.MAX_VALUE)
    val interrupted = MirrorInterruptedRecovery(source, working)
    val conflicts = MirrorConflictRecovery(source, working)

    suspend fun list(cursor: Cursor? = null): Page = read { state ->
        check(cursor == null || cursor.revision == state.revision) { "Retained history changed; refresh the list" }
        val records = authority.recoveryOperations(cursor?.after, PAGE_SIZE + 1)
        val items = records.take(PAGE_SIZE).flatMap { record ->
            Role.entries.mapNotNull { role ->
                AnchoredWorkspaceFiles(parent(role)).use { files ->
                    val kind = files.kind(name(record, role))
                    if (kind == AnchoredWorkspaceFiles.Kind.ABSENT) null else Item(record, role, kind,
                        if (kind == AnchoredWorkspaceFiles.Kind.FILE) files.openRead(name(record, role)).use {
                            it.observe().attributes.size()
                        } else null)
                }
            }
        }
        Page(items, records.takeIf { it.size > PAGE_SIZE }?.let { Cursor(state.revision, it[PAGE_SIZE - 1].id) },
            records.take(PAGE_SIZE).filter { it.phase == MirrorMetadataAuthority.Phase.PREPARED || it.phase == MirrorMetadataAuthority.Phase.RETAINED },
            state.conflicts.sorted())
    }

    /** Observed diagnostics only: no release capability, receipt, or custody transfer. */
    data class RetentionReview internal constructor(
        val item: Item,
        val revision: Long,
        internal val observation: MirrorRecoverySnapshot.Observation,
        val documentVersions: Map<String, WorkspaceDocumentVersion>,
        val dirtyPaths: List<String>,
        val pendingOperations: Int,
        val conflicts: Int,
        val recordedOwners: Int,
        val editorAvailable: Boolean,
        val editorUnavailableReason: String? = null,
        val pendingEditorPaths: Set<String> = emptySet(),
    ) {
        val sha256: String get() = observation.node.sha256
        val blockers: List<String> get() = buildList {
            if (!editorAvailable) add(editorUnavailableReason ?: "Editor ownership is unavailable.")
            if (dirtyPaths.isNotEmpty()) add("${dirtyPaths.size} overlapping editor document(s) have unsaved changes.")
            if (pendingEditorPaths.isNotEmpty()) add("${pendingEditorPaths.size} overlapping editor document(s) have pending save, sync or buffer work.")
            if (pendingOperations > 0) add("$pendingOperations overlapping synchronization operation(s) are unfinished.")
            if (conflicts > 0) add("$conflicts overlapping conflict(s) remain unresolved.")
            if (recordedOwners > 0) add("$recordedOwners recorded process owner(s) remain; this check does not retire them.")
            add("Pending editor writes are not covered by a held release barrier.")
            add("Closure of all writers and inherited descriptors is unproven, even if no process owners are recorded.")
            add("No durable release receipt or interruption-safe payload removal is available.")
        }
    }

    suspend fun reviewRetention(item: Item, documents: WorkspaceDocumentAuthority?, requireCurrent: () -> Unit): RetentionReview = read { state ->
        requireCurrent(); requireRecord(item)
        fun overlaps(path: String) = path == item.operation.path || path.startsWith("${item.operation.path}/") || item.operation.path.startsWith("$path/")
        var unavailable: String? = null
        val editor = try { documents?.observeRecovery(item.operation.path) }
            catch (missing: WorkspaceRecoveryEditorUnavailableException) { unavailable = missing.message; null }
        val before = editor?.snapshots
        val owners = authority.pendingProcessOwners()
        val context = currentCoroutineContext()
        val observed = MirrorRecoverySnapshot.observe(parent(item.role), name(item.operation, item.role), context)
        check(observed.node.type != MirrorMetadataAuthority.NodeType.ABSENT) { "Retained version disappeared; refresh recovery" }
        // Preparing/transiting an operation need not advance the baseline revision.
        // Compare membership as well, rather than interpreting a stable revision as quiescence.
        fun pendingSnapshot(): Pair<Int, String> {
            var count = 0
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            authority.visitOperations { page ->
                context.ensureActive(); requireCurrent()
                page.filter { overlaps(it.path) && it.phase in setOf(
                MirrorMetadataAuthority.Phase.PREPARED, MirrorMetadataAuthority.Phase.RETAINED) }.forEach { operation ->
                context.ensureActive()
                count = Math.addExact(count, 1)
                val bytes = operation.toString().toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            } }
            return count to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val pending = pendingSnapshot()
        editor?.validate()
        check(owners == authority.pendingProcessOwners()) { "Process ownership changed during retention review; inspect again" }
        check(observed == MirrorRecoverySnapshot.observe(parent(item.role), name(item.operation, item.role), context)) {
            "Retained bytes or identity changed during review; inspect again"
        }
        check(pending == pendingSnapshot()) { "Pending synchronization changed during retention review; inspect again" }
        editor?.validate()
        requireRecord(item); context.ensureActive(); requireCurrent()
        RetentionReview(item, state.revision, observed,
            before.orEmpty().associate { it.path to it.version }, before.orEmpty().filter { it.dirty }.map { it.path },
            pending.first, state.conflicts.count(::overlaps), owners.size, editor != null, unavailable, editor?.pendingPaths.orEmpty())
    }

    suspend fun inspect(item: Item): Inspection = read {
        require(item.canCopy) { "Select a regular file; folder and link recovery is not available here" }
        requireRecord(item)
        AnchoredWorkspaceFiles(parent(item.role)).use { files ->
            val parentKey = files.directoryKey()
            val version = snapshots.inspect(files, name(item.operation, item.role), currentCoroutineContext())
            check(files.directoryKey() == parentKey) { "Retained directory changed; inspect again" }
            Inspection(this, item, version, parentKey)
        }
    }

    suspend fun copy(inspection: Inspection, files: FileRepository, destination: String,
                     requireCurrent: () -> Unit): Long = withContext(Dispatchers.IO) {
        requireCurrent()
        require(inspection.owner === this@MirrorRetainedRecovery && files.root.canonicalFile == source.canonicalFile) {
            "Retained selection belongs to another workspace"
        }
        val path = ExplorerFilePolicy.requireUserTarget(files.root, destination)
        val caller = currentCoroutineContext()
        var acquired: Lease? = null
        val lease = try { read {
            requireCurrent()
            requireRecord(inspection.item)
            val anchor = AnchoredWorkspaceFiles(parent(inspection.item.role))
            try {
                check(anchor.directoryKey() == inspection.parentIdentity) { "Retained directory changed; inspect again" }
                val input = anchor.openRead(name(inspection.item.operation, inspection.item.role))
                try {
                    val observed = input.observe()
                    check(observed.attributes.fileKey().toString() == inspection.version.identity &&
                        observed.attributes.size() == inspection.version.size && input.permissionMode() == inspection.version.mode) {
                        "Retained file changed; inspect it again before saving a copy"
                    }
                    Lease(anchor, input, name(inspection.item.operation, inspection.item.role), observed).also { acquired = it }
                } catch (failure: Throwable) {
                    try { input.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                    throw failure
                }
            } catch (failure: Throwable) {
                try { anchor.close() } catch (close: Throwable) { failure.addSuppressed(close) }
                throw failure
            }
        } } catch (failure: Throwable) {
            try { acquired?.close() } catch (close: Throwable) { failure.addSuppressed(close) }
            throw failure
        }
        lease.use {
            caller.ensureActive()
            requireCurrent()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            files.writeStream(path, java.security.DigestInputStream(lease.input, digest), expectedBytes = inspection.version.size,
                requireAbsent = true, requireExistingParent = true,
                executable = inspection.version.mode and 64 != 0,
                beforePublication = {
                    check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == inspection.version.sha256) {
                        "Retained contents changed after inspection; no destination file was published"
                    }
                    read {
                        caller.ensureActive()
                        requireCurrent()
                        requireRecord(inspection.item)
                        lease.requireUnchanged()
                    }
                },
                publicationObserver = object : StreamPublicationObserver {
                    override fun beforePublish(preparation: StreamPublicationPreparation) {
                        caller.ensureActive()
                        requireCurrent()
                        check(preparation.bytes == inspection.version.size && preparation.sha256 == inspection.version.sha256) {
                            "Retained contents changed after inspection; no destination file was published"
                        }
                        lease.requireUnchanged()
                    }
                    override fun onPublished(receipt: StreamPublicationReceipt) = Unit
                })
        }
    }

    /** Reuse the verified export pipeline to rescue a complete folder, including hidden files,
     * empty directories and links. Archive publication never overwrites a project entry. */
    suspend fun archive(item: Item, files: FileRepository, destination: String, cache: File,
                        requireCurrent: () -> Unit): Long = withContext(Dispatchers.IO) {
        require(item.kind == AnchoredWorkspaceFiles.Kind.DIRECTORY) { "Select a retained folder" }
        require(files.root.canonicalFile == source.canonicalFile) { "Archive belongs to another workspace" }
        val path = ExplorerFilePolicy.requireUserTarget(files.root, destination)
        require(path.endsWith(".tar", ignoreCase = true)) { "Use a .tar name for the recovered folder archive" }
        requireCurrent()
        val caller = currentCoroutineContext()
        val cacheRoot = cache.canonicalFile
        require(!PathSecurity.contains(source, cacheRoot) && !PathSecurity.contains(working, cacheRoot) &&
            !PathSecurity.contains(directory, cacheRoot)) { "Recovery staging must be outside the source and retained data" }
        java.nio.file.Files.createDirectories(cacheRoot.toPath())
        val staging = java.nio.file.Files.createTempDirectory(cacheRoot.toPath(), "retained-export-").toFile()
        val control = ExportControl(checkCancelled = { caller.ensureActive(); requireCurrent() })
        try {
            val captured = read {
                requireRecord(item)
                AnchoredWorkspaceFiles(parent(item.role)).use { anchor ->
                    val rootIdentity = anchor.directoryKey()
                    val selected = name(item.operation, item.role)
                    val folderIdentity = anchor.directoryKey(selected)
                    val permit = object : ExportCapturePermit {
                        override val root = parent(item.role)
                        override val scope = ExportScope(selected)
                        override val stabilityAssumptions = listOf("Retained working-copy owner is serialized; uncooperative external writers are detected by observed identity/metadata checks")
                        override fun validate() {
                            control.checkCancelled()
                            check(anchor.directoryKey() == rootIdentity && anchor.directoryKey(selected) == folderIdentity) {
                                "Retained folder changed during capture; original data was kept"
                            }
                        }
                    }
                    ExportCaptureStore(File(staging, "capture")).capture(permit, control).also { requireRecord(item) }
                }
            }
            captured.use {
                val archive = File(staging, "folder.tar")
                ExportEngine().transfer(captured, ExportFormat.TAR, object : ExportDestination {
                    override fun createDirectory(relativePath: String) = error("TAR uses a single archive output")
                    override fun createFile(relativePath: String): ExportOutput {
                        check(relativePath.isEmpty())
                        return object : ExportOutput {
                            override fun openWrite() = java.nio.file.Files.newOutputStream(archive.toPath(),
                                java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)
                            override fun openRead() = java.nio.file.Files.newInputStream(archive.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        }
                    }
                }, control)
                val version = AnchoredWorkspaceFiles(staging).use { snapshots.inspect(it, archive.name, caller) }
                archive.inputStream().use { input ->
                    files.writeStream(path, input, expectedBytes = version.size, requireAbsent = true, requireExistingParent = true,
                        beforePublication = { read { control.checkCancelled(); requireRecord(item) } },
                        publicationObserver = object : StreamPublicationObserver {
                            override fun beforePublish(preparation: StreamPublicationPreparation) {
                                control.checkCancelled()
                                check(preparation.bytes == version.size && preparation.sha256 == version.sha256) {
                                    "Recovery archive changed before publication; no project file was overwritten"
                                }
                            }
                            override fun onPublished(receipt: StreamPublicationReceipt) = Unit
                        })
                }
            }
        } finally {
            // Only this call's private staging is reclaimed, never retained/user data.
            PathSecurity.deleteTreeNoFollow(staging)
        }
    }

    private suspend fun <T> read(action: suspend (MirrorMetadataAuthority.State) -> T): T = withContext(Dispatchers.IO) {
        WorkspaceFileMutationLocks.withCaptureRoot(working) { guard ->
            val bootstrap = LocalMirrorAuthorityBootstrap(source, working)
            val state = bootstrap.readLinkedState(guard, authority)
            val result = action(state)
            currentCoroutineContext().ensureActive()
            check(bootstrap.readLinkedState(guard, authority).revision == state.revision) {
                "Retained history changed during inspection; retry without deleting any version"
            }
            result
        }
    }

    private fun requireRecord(item: Item) {
        check(authority.operation(item.operation.id) == item.operation) { "Retained operation changed; inspect again" }
    }

    private fun parent(role: Role) = File(directory, if (role == Role.PREPARED) "prepared" else "retained")
    private fun name(operation: MirrorMetadataAuthority.Intent, role: Role) = when (role) {
        Role.ORIGINAL -> operation.retainedName
        Role.STABLE -> operation.stableName
        Role.PREPARED -> operation.id
    }

    private class Lease(val files: AnchoredWorkspaceFiles, val input: AnchoredWorkspaceFiles.OpenedFile,
                        val name: String, val initial: AnchoredWorkspaceFiles.Observation) : AutoCloseable {
        private val parentKey = files.directoryKey()
        fun requireUnchanged() {
            fun matches(other: AnchoredWorkspaceFiles.Observation) =
                initial.attributes.fileKey() == other.attributes.fileKey() &&
                    initial.attributes.size() == other.attributes.size() &&
                    initial.attributes.lastModifiedTime() == other.attributes.lastModifiedTime() &&
                    initial.changed == other.changed && initial.executable == other.executable
            check(files.directoryKey() == parentKey && matches(input.observe()) &&
                files.openRead(name).use { matches(it.observe()) }) { "Retained file changed during copying; originals were preserved" }
        }
        override fun close() {
            try { input.close() } finally { files.close() }
        }
    }

    private companion object { const val PAGE_SIZE = 32 }
}
