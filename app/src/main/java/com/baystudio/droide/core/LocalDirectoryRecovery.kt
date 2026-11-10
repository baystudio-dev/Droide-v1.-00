package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Base64
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class LocalDirectoryRecovery(private val repository: FileRepository,
    private val custody: LocalDirectoryDeleteCustody = LocalDirectoryDeleteCustody(repository.root),
    private val checkpoint: (String) -> Unit = {}) {
    private val root = repository.root
    private val journal = LocalDirectoryArchiveJournal(root, checkpoint)
    data class Page(val items: List<LocalDirectoryDeleteCustody.Inspection>, val nextCursor: String?, val complete: Boolean)
    class Review internal constructor(internal val owner: LocalDirectoryRecovery,
        val inspection: LocalDirectoryDeleteCustody.Inspection, val destination: String,
        val archiveId: String, internal val parents: List<LocalDirectoryArchiveJournal.Parent>,
        internal val historical: LocalDirectoryArchiveJournal.Record?)
    data class Result(val committed: Boolean = true, val historical: Boolean,
        val currentFileReverified: Boolean = false, val destination: String, val bytes: Long,
        val sha256: String, val archiveId: String, val receiptPath: String, val retainedPath: String,
        val syncPending: Boolean?, val synchronizationStatus: String, val detail: String)

    data class ArchiveInspection(val status: String, val archiveId: String, val destination: String,
        val receiptPath: String, val bytes: Long?, val sha256: String?,
        val currentFileReverified: Boolean = false, val detail: String)

    fun archiveReadPath(token: LocalDirectoryDeleteCustody.Token, destination: String): String {
        require(java.util.UUID.fromString(token.operation).toString() == token.operation && token.preparedSha256.matches(Regex("[a-f0-9]{64}")))
        val path = ordinaryDestination(destination, inspectCurrent = false)
        return "${LocalDirectoryArchiveJournal.HOME}/${token.operation}/${journal.id(token, path)}"
    }

    suspend fun inspectArchive(token: LocalDirectoryDeleteCustody.Token, destination: String): ArchiveInspection = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val path = ordinaryDestination(destination, inspectCurrent = false)
        val selected = custody.inspect(token.operation, token)
        check(selected.token == token) { "Exact deletion token is no longer verifiable; preserve custody and archive evidence" }
        val observed = journal.inspectStatus(token, path)
        val receipt = observed.record?.receipt
        ArchiveInspection(observed.status, journal.id(token, path), path, journal.receiptPath(token, path),
            receipt?.preparation?.bytes, receipt?.preparation?.sha256, detail = observed.detail)
    }

    /** Constant page-sized selection heap, no global operation cap. A cursor binds the directory
     * membership observation; additions/removals require an explicit refresh. */
    suspend fun list(cursor: String? = null): Page = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext()
        WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
            AnchoredWorkspaceFiles(root).use { files ->
                guard.requireRoot(root)
                if (files.kind(LocalDirectoryDeleteCustody.HOME) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    check(cursor == null) { "Folder recovery membership changed; refresh the list" }
                    return@withCaptureRoot Page(emptyList(), null, true)
                }
                val revision = membership(files)
                val parsed = cursor?.let(::decodeCursor)
                check(parsed == null || parsed.first == revision) { "Folder recovery membership changed; refresh the list" }
                val after = parsed?.second
                val selected = PriorityQueue<String>(PAGE_SIZE + 1, reverseOrder())
                files.forEachChild(LocalDirectoryDeleteCustody.HOME) { name ->
                    caller.ensureActive()
                    if (after == null || name > after) {
                        selected.add(name)
                        if (selected.size > PAGE_SIZE + 1) selected.remove()
                    }
                }
                val names = selected.toList().sorted()
                val items = names.take(PAGE_SIZE).map { name ->
                    caller.ensureActive()
                    try { custody.inspect(name) }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) {
                        LocalDirectoryDeleteCustody.Inspection(name, null,
                            "${LocalDirectoryDeleteCustody.HOME}/$name", null,
                            LocalDirectoryDeleteCustody.State.RECOVERY_REQUIRED,
                            LocalDirectoryDeleteCustody.SourceState.UNVERIFIABLE,
                            LocalDirectoryDeleteCustody.ObserverState.UNVERIFIABLE,
                            "Unknown custody entry was preserved. ${(failure.message ?: "Unverifiable operation").take(200)}")
                    }
                }
                guard.requireRoot(root)
                check(membership(files) == revision) { "Folder recovery membership changed; refresh the list" }
                Page(items, if (names.size > PAGE_SIZE) encodeCursor(revision, names[PAGE_SIZE - 1]) else null, names.size <= PAGE_SIZE)
            }
        }
    }

    suspend fun inspect(token: LocalDirectoryDeleteCustody.Token): LocalDirectoryDeleteCustody.Inspection =
        withContext(Dispatchers.IO) { currentCoroutineContext().ensureActive(); custody.inspect(token.operation, token) }

    suspend fun prepareArchive(token: LocalDirectoryDeleteCustody.Token, destination: String): Review = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val path = ordinaryDestination(destination, inspectCurrent = false)
        WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
            val inspection = requireArchivable(token)
            val previous = journal.inspect(token, path)

            val parents = if (previous?.sealed == true) previous.intent.parents else journal.parents(ordinaryDestination(path)).also {
                AnchoredWorkspaceFiles(root).use { files ->
                    check(files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Recovery destination is occupied; choose a new .tar name" }
                    if (previous != null) {
                        check(previous.intent.parents == it) { "Earlier recovery destination parents changed; choose a fresh destination" }
                        check(files.kind(previous.intent.stagePath) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Earlier archive staging remains; preserve it and choose a fresh destination" }
                    }
                }
            }
            guard.requireRoot(root)
            Review(this@LocalDirectoryRecovery, inspection, path, journal.id(token, path), parents, previous?.takeIf { it.sealed })
        }
    }

    /** Policy preflight only. Every suspend boundary has no metadata lease or root mutex. Names are
     * enumerated through no-follow anchored directories, never link targets. */
    suspend fun authorizeReadEntries(review: Review, authorize: suspend (relativePath: String) -> Unit) = withContext(Dispatchers.IO) {
        requireReview(review)
        if (review.historical != null) return@withContext
        val caller = currentCoroutineContext()
        suspend fun visit(relative: String) {
            caller.ensureActive(); authorize(relative); caller.ensureActive(); requireReview(review)
            val path = review.inspection.retainedPath + if (relative.isEmpty()) "" else "/$relative"
            AnchoredWorkspaceFiles(root).use { files ->
                if (files.kind(path) == AnchoredWorkspaceFiles.Kind.DIRECTORY) {
                    // A directory stream is lazy and bounded; do not materialize the tree.
                    files.openDirectory(path).use { folder ->
                        var after: String? = null
                        while (true) {
                            val names = PriorityQueue<String>(PAGE_SIZE + 1, reverseOrder())
                            folder.forEachChild { name ->
                                caller.ensureActive()
                                if (after == null || name > after!!) {
                                    names.add(name)
                                    if (names.size > PAGE_SIZE) names.remove()
                                }
                            }
                            if (names.isEmpty()) break
                            for (name in names.toList().sorted()) {
                                requireReview(review)
                                check(files.directoryKey(path) == folder.directoryKey()) { "Retained directory changed during read approval" }
                                require(name.isNotEmpty() && '/' !in name && '\u0000' !in name)
                                visit(if (relative.isEmpty()) name else "$relative/$name")
                                after = name
                            }
                        }
                        check(files.directoryKey(path) == folder.directoryKey()) { "Retained directory changed during read approval" }
                    }
                }
            }
            requireReview(review)
        }
        visit("")
    }

    suspend fun archive(review: Review, cacheRoot: File, requireCurrent: () -> Unit,
        checkReadEntry: (relativePath: String) -> Unit = {},
        beforePublication: suspend () -> Unit = {},
        onProgress: (LocalDirectoryRecoveryProgress) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        check(review.owner === this@LocalDirectoryRecovery) { "Folder recovery review belongs to another workspace request" }
        val caller = currentCoroutineContext()
        fun current() { caller.ensureActive(); requireCurrent() }
        fun progress(value: LocalDirectoryRecoveryProgress, repositoryProgress: Boolean = false) {
            fun requireActive() {
                try { current() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (revoked: Exception) {
                    // FileRepository isolates ordinary progress failures. An actual policy revocation must cross
                    // that boundary and stop further work promptly.
                    if (repositoryProgress) throw RecoveryProgressRevoked(revoked)
                    throw revoked
                }
            }
            requireActive()
            // A failed display callback is not cancellation or transaction authority. A real caller
            // cancellation or changed request still wins after the callback.
            try { onProgress(value) } catch (_: Exception) { /* Display failure only. */ }
            requireActive()
        }
        current()
        val token = checkNotNull(review.inspection.token)
        val selected = requireReview(review)
        if (review.historical != null) {
            val record = journal.inspect(token, review.destination)
            check(record == review.historical && record.sealed) { "Archive receipt changed after approval; inspect again" }
            current()
            return@withContext result(review, checkNotNull(record.receipt), true, false)
        }
        beforePublication(); current(); requireReview(review)
        val cache = cacheRoot.canonicalFile
        require(!PathSecurity.contains(root.canonicalFile, cache)) { "Recovery staging must be outside the workspace and custody" }
        Files.createDirectories(cache.toPath())
        val staging = Files.createTempDirectory(cache.toPath(), "local-folder-export-").toFile()
        var custodyLease: LocalDirectoryDeleteCustody.RecoveryLease? = null
        var archiveLease: LocalDirectoryArchiveJournal.Lease? = null
        var captured: ExportCapturedSource? = null
        try {
            val control = ExportControl(checkCancelled = { current() }, onProgress = { observed ->
                val phase = when (observed.phase) {
                    ExportPhase.CAPTURING -> LocalDirectoryRecoveryPhase.CAPTURING
                    ExportPhase.WRITING -> LocalDirectoryRecoveryPhase.CREATING_TAR
                    ExportPhase.VERIFYING -> LocalDirectoryRecoveryPhase.VERIFYING_TAR
                    // ExportEngine verified only the private TAR, not project publication.
                    ExportPhase.COMPLETED_VERIFIED -> LocalDirectoryRecoveryPhase.STAGED_TAR_VERIFIED
                }
                progress(LocalDirectoryRecoveryProgress(phase, observed.entries, observed.bytes))
            })
            WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
                current(); requireReview(review)
                journal.requireParents(review.parents, review.destination)
                if (selected.state == LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED) {
                    val reconciled = custody.reconcile(token, guard)
                    check(reconciled.state == LocalDirectoryDeleteCustody.State.RETAINED) { "Folder move cannot be confirmed safely; all originals were preserved" }
                }
                custodyLease = custody.acquireRecovery(token, guard)
                archiveLease = journal.begin(token, review.destination, review.parents, guard)
                val lease = checkNotNull(custodyLease)
                val retained = lease.validate()
                val permit = object : ExportEntryAdmissionPermit {
                    override val root = this@LocalDirectoryRecovery.root
                    override val scope = ExportScope(retained.retainedPath)
                    override val stabilityAssumptions = listOf(
                        "Observed retained tree only. Metadata checks detect observed changes; no writer exclusion or point-in-time snapshot is promised.",
                        "Original custody is preserved and may continue changing through existing descriptors or other writers.")
                    override fun requireEntry(relativePath: String) { current(); checkReadEntry(relativePath) }
                    override fun validate() { current(); guard.requireRoot(root); lease.validate() }
                }
                checkpoint("before-capture")
                progress(LocalDirectoryRecoveryProgress(LocalDirectoryRecoveryPhase.CAPTURING))
                captured = ExportCaptureStore(File(staging, "capture")).capture(permit, control)
                lease.validate(); current(); checkpoint("captured")
            }
            val archive = File(staging, "folder.tar")
            ExportEngine().transfer(checkNotNull(captured), ExportFormat.TAR, object : ExportDestination {
                override fun createDirectory(relativePath: String) = error("TAR is one archive output")
                override fun createFile(relativePath: String): ExportOutput {
                    check(relativePath.isEmpty())
                    return object : ExportOutput {
                        override fun openWrite() = Files.newOutputStream(archive.toPath(), CREATE_NEW, WRITE)
                        override fun openRead() = Files.newInputStream(archive.toPath(), NOFOLLOW_LINKS)
                    }
                }
            }, control)
            val version = archive.inputStream().use { ExportStreams.copy(it, null, control) }
            val lease = checkNotNull(archiveLease)
            val observer = object : StreamPublicationObserver, StreamStagePlan, StreamPublicationRevalidator {
                override val plannedStagePath get() = lease.plannedStagePath
                override fun beforePublish(preparation: StreamPublicationPreparation) {
                    current(); checkNotNull(custodyLease).validate(); lease.validate()
                    journal.requireParents(review.parents, review.destination)
                    check(preparation.bytes == version.first && preparation.sha256 == version.second) { "Captured archive changed before publication" }
                    lease.beforePublish(preparation)
                    current(); checkNotNull(custodyLease).validate()
                }
                override fun revalidateBeforePublication() {
                    current(); checkNotNull(custodyLease).validate(); lease.validate()
                    journal.requireParents(review.parents, review.destination)
                    // The route binds its full policy snapshot in current(); payload entry reads were admitted
                    // during capture. Keep this commit check bounded.
                    current()
                }
                override fun onPublished(receipt: StreamPublicationReceipt) { lease.onPublished(receipt) }
            }
            val completed = try {
                repository.writeStream(review.destination, archive.inputStream(), expectedBytes = version.first,
                    requireAbsent = true, requireExistingParent = true, publicationObserver = observer,
                    onProgress = { observed ->
                        try {
                            val phase = when (observed.phase) {
                                StreamWritePhase.PREPARING -> LocalDirectoryRecoveryPhase.PREPARING_PUBLICATION
                                StreamWritePhase.COPYING -> LocalDirectoryRecoveryPhase.COPYING_TO_PROJECT
                                StreamWritePhase.COMMITTING -> LocalDirectoryRecoveryPhase.PUBLISHING_ARCHIVE
                                StreamWritePhase.SYNCHRONIZING -> LocalDirectoryRecoveryPhase.SYNCHRONIZING_PROJECT
                            }
                            progress(LocalDirectoryRecoveryProgress(phase, bytes = observed.completedBytes,
                                totalBytes = observed.totalBytes), repositoryProgress = true)
                        } catch (fatal: Error) {

                            throw RecoveryProgressFatal(fatal)
                        }
                    },
                    beforePublication = {
                        current(); beforePublication(); current(); checkNotNull(custodyLease).validate(); lease.validate()
                        journal.requireParents(review.parents, review.destination)
                    })
                result(review, checkNotNull(lease.committedReceipt), false, false)
            } catch (failure: RecoveryProgressFatal) { throw failure.fatal }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                val receipt = lease.committedReceipt ?: throw failure
                // Canonical publication and our durable receipt succeeded. A later repository
                // synchronization/callback error cannot turn that into a safe automatic retry.
                result(review, receipt, false, true)
            }
            // Repository progress is best effort; its callback isolation must not suppress the request's
            // final authority check after a committed synchronization stage.
            current()
            completed
        } finally {
            try { archiveLease?.close() } finally { try { custodyLease?.close() } finally {
                captured?.close()
                // Only our private, externally staged capture is disposed; never originals, archive authority,
                // or FileRepository's retained destination sibling stage.
                PathSecurity.deleteTreeNoFollow(staging)
            } }
        }
    }

    private fun requireReview(review: Review): LocalDirectoryDeleteCustody.Inspection {
        check(review.owner === this)
        ordinaryDestination(review.destination, inspectCurrent = review.historical == null)
        val current = requireArchivable(checkNotNull(review.inspection.token))
        check(current == review.inspection) { "Folder custody changed after inspection; inspect and approve again" }
        if (review.historical == null) journal.requireParents(review.parents, review.destination)
        return current
    }
    private fun requireArchivable(token: LocalDirectoryDeleteCustody.Token): LocalDirectoryDeleteCustody.Inspection =
        custody.inspect(token.operation, token).also {
            check(it.token == token && it.state in setOf(LocalDirectoryDeleteCustody.State.RETAINED,
                LocalDirectoryDeleteCustody.State.MOVED_UNCONFIRMED)) { "Folder custody is prepared, ambiguous, or changed; preserve all locations and inspect its exact token" }
        }
    private fun ordinaryDestination(destination: String, inspectCurrent: Boolean = true): String {
        require(destination.length in 1..500 && destination.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            '\\' !in destination && '\u0000' !in destination && !destination.startsWith('/')) { "Choose an exact ordinary relative .tar path without aliases" }
        require(!ExplorerFilePolicy.isDroideInternalPath(destination) && destination.endsWith(".tar", true)) { "Choose an ordinary .tar destination" }
        if (inspectCurrent) require(ExplorerFilePolicy.requireUserTarget(root, destination) == destination) { "Choose an unaliased .tar destination" }
        return destination
    }
    private fun result(review: Review, receipt: StreamPublicationReceipt, historical: Boolean, syncPending: Boolean) = Result(
        historical = historical, destination = review.destination, bytes = receipt.preparation.bytes,
        sha256 = receipt.preparation.sha256, archiveId = review.archiveId,
        receiptPath = journal.receiptPath(checkNotNull(review.inspection.token), review.destination),
        retainedPath = review.inspection.retainedPath, syncPending = if (historical) null else syncPending,
        synchronizationStatus = if (historical) "UNKNOWN_NOT_REPLAYED" else if (syncPending) "PENDING" else "COMPLETED",
        detail = if (historical) "This exact archive was historically published. No new capture or synchronization was performed, and the current destination was not reverified. Original custody remains preserved."
            else "An observed folder archive was published. Original custody remains preserved and may still change. The archive is not a frozen historical snapshot." +
                if (syncPending) " Repository synchronization needs attention; do not repeat this archive publication." else "")

    private fun membership(files: AnchoredWorkspaceFiles): String {
        val home = LocalDirectoryDeleteCustody.HOME
        val path = File(root, home).toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(attributes.isDirectory && !attributes.isSymbolicLink)
        val ctime = Files.getAttribute(path, "unix:ctime", NOFOLLOW_LINKS)
        val value = listOf(root.canonicalPath, files.directoryKey(), files.directoryKey(".droide"), files.directoryKey(home),
            attributes.lastModifiedTime().toString(), ctime.toString()).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    }
    private fun encodeCursor(revision: String, after: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        buildJsonObject { put("v", 1); put("revision", revision); put("after", after) }.toString().toByteArray(Charsets.UTF_8))
    private fun decodeCursor(cursor: String): Pair<String, String> {
        require(cursor.length in 1..4096)
        val j = Json.parseToJsonElement(Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8)).jsonObject
        require(j.keys == setOf("v", "revision", "after") && j["v"]!!.jsonPrimitive.int == 1)
        val revision = j["revision"]!!.jsonPrimitive.content
        val after = j["after"]!!.jsonPrimitive.content
        require(revision.matches(Regex("[a-f0-9]{64}")) && after.isNotEmpty() && after.length <= 1024 && '/' !in after && '\u0000' !in after)
        return revision to after
    }
    private class RecoveryProgressFatal(val fatal: Error) : CancellationException("Recovery progress failed") {
        init { initCause(fatal) }
    }

    private class RecoveryProgressRevoked(cause: Exception) : CancellationException(
        "Recovery authority changed. Inspect the exact token and destination receipt before retrying; publication may already have occurred.") {
        init { initCause(cause) }
    }

    companion object { const val PAGE_SIZE = 32 }
}
