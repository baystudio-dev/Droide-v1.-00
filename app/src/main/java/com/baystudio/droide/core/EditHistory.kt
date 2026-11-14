package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EditHistory(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
    private val lsp: LspManager? = null,
    private val streamCommitAuthority: StreamedDiskCommitAuthority? = documents,
) {
    data class Snapshot(
        val path: String,
        val backup: File,
        val existed: Boolean,
        val liveExpected: WorkspaceDocumentVersion? = null,
        val diskExpected: DiskFileVersion? = null,
        val backupExpected: DiskFileVersion? = null,
        val liveBefore: WorkspaceDocumentVersion? = null,
        val streamExpected: StreamContentVersion? = null,
        val streamBackup: StreamContentVersion? = null,
        val streamIdentity: String? = null,
        val streamRootIdentity: String? = null,
        val streamBackupIdentity: String? = null,
    )
    data class Entry(val label: String, val snapshots: List<Snapshot>, val id: String = UUID.randomUUID().toString())
    data class PendingLive internal constructor(val path: String, val backup: File, val backupExpected: DiskFileVersion)
    data class PendingBatch internal constructor(
        val label: String, val snapshots: List<Snapshot>,
        internal val predecessorIds: Map<String, String> = emptyMap(),
    ) { internal var streamCommitReceipt: String? = null }
    data class LiveTransition(val before: WorkspaceDocumentVersion, val after: WorkspaceDocumentVersion) {
        init { require(before.documentId == after.documentId) { "Live edit changed document generation" } }
        companion object {
            fun from(applied: WorkspaceDocumentMutationResult.Applied) = LiveTransition(applied.before.version, applied.after.version)
        }
    }
    private class LiveRestoreObservation {
        var applied: WorkspaceDocumentMutationResult.Applied? = null
    }

    private val stack = ArrayDeque<Entry>()
    private val stackLock = Any()
    private val undoLock = Mutex()
    private var activeUndo: String? = null

    private fun ensureDir(): File {
        val parent = File(files.root, ".droide")
        val rawDir = File(parent, "backups")
        require(!PathSecurity.isSymbolicLink(parent) && !PathSecurity.isSymbolicLink(rawDir)) {
            "Edit-history directory must not be a symbolic link"
        }
        val dir = PathSecurity.resolveWithin(files.root, ".droide/backups")
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create edit-history directory: ${dir.path}" }
        // Old/other-session backups are recovery data. Starting a new history never deletes them.
        return dir
    }

    private fun backup(path: String, content: String?): Snapshot =
        ExportWriterAdmission.withWriter(files.root, "Edit-history backup") {
        require(path.isNotBlank()) { "Undo path is empty" }
        files.resolveChecked(path)
        val text = content.orEmpty()
        val version = DiskFileVersion.fromText(text)
        require(version.bytes <= EditorLargeFilePolicy.MAX_EDITABLE_BYTES) { "Undo snapshot exceeds safe text limit: $path" }
        val file = File.createTempFile("undo-", ".bak", ensureDir())
        try {
            file.writeText(text)
            Snapshot(path, file, content != null, backupExpected = version)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    suspend fun snapshot(path: String, current: String?) = snapshotBatch("edit $path", mapOf(path to current))

    suspend fun prepareLive(path: String, before: String): PendingLive = withContext(Dispatchers.IO) {
        val snapshot = backup(path, before)
        PendingLive(path, snapshot.backup, requireNotNull(snapshot.backupExpected))
    }

    fun commitLive(pending: PendingLive, transition: LiveTransition) {
        synchronized(stackLock) {
            stack.addLast(Entry("edit ${pending.path}", listOf(Snapshot(
                pending.path, pending.backup, true, transition.after, backupExpected = pending.backupExpected,
                liveBefore = transition.before,
            ))))
            trim()
        }
    }

    suspend fun discardLive(pending: PendingLive) = withContext(Dispatchers.IO) {
        runCatching { deleteBackup(pending.backup) }
    }

    suspend fun snapshotBatch(label: String, states: Map<String, String?>) {
        val pending = prepareBatch(label, states)
        synchronized(stackLock) {
            // Unsealed entries cannot be undone until their mutation commits a known after-payload.
            stack.addLast(Entry(pending.label, pending.snapshots))
            trim()
        }
    }

    suspend fun prepareBatch(label: String, states: Map<String, String?>): PendingBatch =
        ExportWriterAdmission.withSuspendingWriter(files.root, "Edit-history batch") {
            withContext(Dispatchers.IO) {
                require(states.isNotEmpty()) { "No states to snapshot" }
                val snapshots = mutableListOf<Snapshot>()
                val predecessors = synchronized(stackLock) {
                    states.keys.mapNotNull { path ->
                        stack.lastOrNull { entry -> entry.snapshots.any { it.path == path } }?.let { path to it.id }
                    }.toMap()
                }
                try {
                    for ((path, content) in states) {
                        currentCoroutineContext().ensureActive()
                        snapshots += backup(path, content)
                    }
                    PendingBatch(label, snapshots, predecessors)
                } catch (failure: Throwable) {
                    snapshots.forEach { runCatching { deleteSnapshotBackup(it) } }
                    throw failure
                }
            }
        }

    private suspend fun requireNoLiveStreamTarget(path: String) {
        val editor = documents?.observeRecovery(path) ?: return
        require(editor.snapshots.isEmpty() && editor.pendingPaths.isEmpty()) {
            "Streamed disk mutation cannot bypass live or pending editor work"
        }
        editor.validate()
    }

    /** Streaming backup remains under the same repository root guard and checks reviewed CAS twice. */
    internal suspend fun prepareStream(path: String, reviewed: StreamFileVersion): PendingBatch =
        withContext(Dispatchers.IO) {
            checkNotNull(streamCommitAuthority) { "Streamed edit undo requires a fenced editor authority" }
            WorkspaceFileMutationLocks.withRoot(files.root) { guard ->
                guard.requireRoot(files.root)
                reviewed.requireMatches(files.root, path)
                requireNoLiveStreamTarget(path)
                val predecessor = synchronized(stackLock) { stack.lastOrNull { e -> e.snapshots.any { it.path == path } }?.id }
                val dir = ensureDir()
                val count = if (reviewed.existed) reviewed.bytes else 0L
                require(files.root.usableSpace >= count && files.root.usableSpace - count >= 32L * 1024 * 1024) {
                    "Insufficient space for exact undo backup"
                }
                val backup = File.createTempFile("undo-stream-", ".bak", dir)
                val backupIdentity = ProjectCopyEvidence.identity(backup).key
                try {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    java.nio.channels.FileChannel.open(backup.toPath(), java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS).use { output ->
                        if (reviewed.existed) java.nio.file.Files.newInputStream(files.resolveChecked(path).toPath(),
                            java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                guard.requireRoot(files.root)
                                val n = input.read(buffer); if (n < 0) break
                                check(n > 0 && n.toLong() <= count - total) { "Undo source grew" }
                                val chunk = java.nio.ByteBuffer.wrap(buffer, 0, n)
                                while (chunk.hasRemaining()) {
                                    currentCoroutineContext().ensureActive()
                                    check(output.write(chunk) > 0) { "Undo backup write made no progress" }
                                }
                                digest.update(buffer, 0, n); total += n
                            }
                        }
                        output.force(true)
                    }
                    val saved = StreamContentVersion(true, total, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
                    check(total == count && (!reviewed.existed || saved.sha256 == reviewed.sha256)) { "Undo source changed" }
                    check(ProjectCopyEvidence.identity(backup).key == backupIdentity) { "Undo backup identity changed" }
                    ProjectCopyJournal.Durability.platform.forceDirectory(dir)
                    reviewed.requireMatches(files.root, path)
                    guard.requireRoot(files.root)
                    PendingBatch("edit $path", listOf(Snapshot(path, backup, reviewed.existed, streamBackup = saved,
                        streamRootIdentity = guard.rootIdentity, streamBackupIdentity = backupIdentity)),
                        if (predecessor == null) emptyMap() else mapOf(path to predecessor))
                } catch (failure: Throwable) {
                    runCatching { guard.requireRoot(files.root)
                        if (ProjectCopyEvidence.identity(backup).key == backupIdentity) deleteBackup(backup) }
                    throw failure
                }
            }
        }

    internal fun commitStream(pending: PendingBatch, receipt: StreamPublicationReceipt) {
        val preparation = receipt.preparation
        require(pending.snapshots.size == 1 && pending.snapshots.single().path == preparation.relativePath)
        require(preparation.canonicalRoot == files.root.canonicalPath && receipt.destinationIdentity == preparation.stageIdentity)
        val before = pending.snapshots.single()
        require(before.streamRootIdentity == preparation.rootIdentity)
        require(before.streamBackup != null && before.streamExpected == null)
        val snapshot = before.copy(streamExpected = StreamContentVersion.of(preparation), streamIdentity = receipt.destinationIdentity)
        synchronized(pending) {
            check(pending.streamCommitReceipt == null) { "Streamed undo already committed" }
            synchronized(stackLock) { stack.addLast(Entry(pending.label, listOf(snapshot))); trim() }
            pending.streamCommitReceipt = preparation.publicationId
        }
    }

    /** Seal only the intended successful payload, rather than blessing any later user/terminal change. */
    fun commitLast(diskAfter: Map<String, String?>) {
        synchronized(stackLock) {
            val entry = stack.lastOrNull() ?: error("No pending edit to commit")
            require(entry.snapshots.all { it.liveExpected == null && it.diskExpected == null }) {
                "Edit is already committed"
            }
            stack[stack.lastIndex] = entry.copy(snapshots = seal(entry.snapshots, emptyMap(), diskAfter))
        }
    }

    fun commitBatch(
        pending: PendingBatch,
        liveTransitions: Map<String, LiveTransition> = emptyMap(),
        diskAfter: Map<String, String?> = emptyMap(),
    ) {
        val snapshots = seal(pending.snapshots, liveTransitions, diskAfter)
        synchronized(stackLock) {
            stack.addLast(Entry(pending.label, snapshots))
            trim()
        }
    }

    private fun seal(
        snapshots: List<Snapshot>,
        liveTransitions: Map<String, LiveTransition>,
        diskAfter: Map<String, String?>,
    ): List<Snapshot> = snapshots.map { snapshot ->
        val liveTransition = liveTransitions[snapshot.path]
        val liveVersion = liveTransition?.after
        require(liveVersion != null || diskAfter.containsKey(snapshot.path)) {
            "Missing successful mutation payload for undo: ${snapshot.path}"
        }
        snapshot.copy(liveExpected = liveVersion, liveBefore = liveTransition?.before,
            diskExpected = if (liveVersion == null) DiskFileVersion.fromText(diskAfter[snapshot.path]) else null)
    }

    suspend fun discardBatch(pending: PendingBatch) = withContext(Dispatchers.IO) {
        pending.snapshots.forEach { runCatching { deleteSnapshotBackup(it) } }
    }

    suspend fun discardLast() = undoLock.withLock {
        val entry = synchronized(stackLock) { stack.removeLastOrNull() }
        withContext(Dispatchers.IO) { entry?.snapshots?.forEach { runCatching { deleteSnapshotBackup(it) } } }
    }

    suspend fun undo(): String = undoLock.withLock {
        val entry = synchronized(stackLock) {
            stack.lastOrNull()?.also { activeUndo = it.id }
        } ?: return@withLock "Tidak ada yang bisa di-undo."
        var restored = 0
        val syncPending = mutableListOf<String>()
        val restoredLive = mutableListOf<WorkspaceDocumentMutationResult.Applied>()
        val output = try {
            // Discover existing conflicts for every target before changing the first file.
            for (snapshot in entry.snapshots) {
                currentCoroutineContext().ensureActive()
                requireBackup(snapshot)
                validateTarget(snapshot)
            }
            for (snapshot in entry.snapshots.asReversed()) {
                currentCoroutineContext().ensureActive()
                if (snapshot.streamExpected != null) {
                    val restoredStream = restoreStream(snapshot)
                    synchronized(stackLock) {
                        val index = stack.indexOfFirst { it.id == entry.id }
                        if (index >= 0) {
                            val remaining = stack[index].snapshots.filterNot { it.path == snapshot.path }
                            if (remaining.isEmpty()) stack.removeAt(index)
                            else stack[index] = stack[index].copy(snapshots = remaining)
                        }
                    }
                    restored++
                    if (restoredStream.first) syncPending += snapshot.path
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { deleteSnapshotBackup(snapshot) } }
                    restoredStream.second?.let { throw it }
                    continue
                }
                val backupText = readBackup(snapshot)
                val writeObservation = if (snapshot.liveExpected == null && snapshot.existed) {
                    files.prepareTextWriteObservation(snapshot.path, backupText, requireNotNull(snapshot.diskExpected))
                } else null
                val deleteObservation = if (snapshot.liveExpected == null && !snapshot.existed && snapshot.diskExpected?.existed == true) {
                    files.prepareDeleteObservation(snapshot.path, snapshot.diskExpected)
                } else null
                val liveObservation = LiveRestoreObservation()
                withContext(NonCancellable) {
                    var restoreFailure: Throwable? = null
                    try {
                        if (restore(snapshot, backupText, writeObservation, deleteObservation, liveObservation)) syncPending += snapshot.path
                    } catch (failure: Throwable) {
                        // A returned live Applied receipt survives failed/cancelled LSP delivery.
                        // The repository verifies this invocation's receipt under its original
                        // guard, after observers finish. Matching backup bytes cannot prove
                        // that Undo published them; do not reacquire or hash during cleanup.
                        if (liveObservation.applied == null &&
                            !(failure is CancellationException &&
                                (writeObservation?.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT ||
                                    deleteObservation?.ownedCurrent == true && deleteObservation.synchronizationFailed))) throw failure
                        restoreFailure = failure
                    }
                    // Keep only unfinished targets if a later mutation fails/cancellation arrives.
                    synchronized(stackLock) {
                        val index = stack.indexOfFirst { it.id == entry.id }
                        if (index >= 0) {
                            val restoredVersion = liveObservation.applied?.after?.version
                            if (snapshot.liveBefore != null && restoredVersion != null) {
                                rebasePredecessor(snapshot.path, snapshot.liveBefore, restoredVersion, index)
                            }
                            val remaining = stack[index].snapshots.filterNot { it.path == snapshot.path }
                            if (remaining.isEmpty()) stack.removeAt(index)
                            else stack[index] = stack[index].copy(snapshots = remaining)
                        }
                    }
                    liveObservation.applied?.let(restoredLive::add)
                    restored++
                    withContext(Dispatchers.IO) { runCatching { deleteSnapshotBackup(snapshot) } }
                    restoreFailure?.let { throw it }
                }
            }
            "Undone: ${entry.label} (${entry.snapshots.size} file${if (entry.snapshots.size == 1) "" else "s"})" +
                if (syncPending.isEmpty()) "" else "; restored locally; synchronization pending: ${syncPending.joinToString()}"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            buildString {
                append("Undo failed: ").append(failure.message)
                if (restored > 0) append("; $restored file(s) already restored, remaining backups preserved")
                if (syncPending.isNotEmpty()) append("; synchronization pending: ").append(syncPending.joinToString())
            }
        } finally {
            synchronized(stackLock) { activeUndo = null }
        }
        output + AgentEditPersistence.save(documents, restoredLive)
    }

    private suspend fun requireBackup(snapshot: Snapshot) = withContext(Dispatchers.IO) {
        snapshot.streamBackup?.let { stream ->
            require(ProjectCopyEvidence.root(files.root).second == snapshot.streamRootIdentity)
            require(PathSecurity.contains(files.root, snapshot.backup) &&
                ProjectCopyEvidence.identity(snapshot.backup).key == snapshot.streamBackupIdentity)
            stream.requireMatches(snapshot.backup)
            return@withContext
        }
        val expected = snapshot.backupExpected ?: error("Undo backup has no integrity token: ${snapshot.path}")
        require(PathSecurity.contains(files.root, snapshot.backup) && expected.matches(snapshot.backup)) {
            "Undo backup is missing or changed: ${snapshot.path}; recovery data was preserved"
        }
    }

    private suspend fun readBackup(snapshot: Snapshot): String {
        requireBackup(snapshot)
        val expected = requireNotNull(snapshot.backupExpected)
        val relative = files.root.canonicalFile.toPath().relativize(snapshot.backup.canonicalFile.toPath()).toString()
        val text = files.readEditorText(relative, expected.bytes.coerceAtLeast(1L))
        require(DiskFileVersion.fromText(text) == expected) { "Undo backup changed while reading: ${snapshot.path}" }
        return text
    }

    private suspend fun validateTarget(snapshot: Snapshot) {
        if (snapshot.streamExpected != null) {
            require(ProjectCopyEvidence.root(files.root).second == snapshot.streamRootIdentity)
            requireNoLiveStreamTarget(snapshot.path)
            val observed = StreamFileVersion.capture(files.root, snapshot.path)
            require(StreamContentVersion.of(observed) == snapshot.streamExpected)
            require(ProjectCopyEvidence.identity(files.resolveChecked(snapshot.path)).key == snapshot.streamIdentity) {
                "Streamed undo target identity changed; backup retained"
            }
        } else if (snapshot.liveExpected != null) {
            val current = documents?.snapshot(snapshot.path)
                ?: error("Live document is no longer available: ${snapshot.path}")
            require(current.version == snapshot.liveExpected) { "Live document changed after Agent edit: ${snapshot.path}" }
            require(current.editable) { "Live document is read-only: ${snapshot.path}" }
        } else {
            val expected = snapshot.diskExpected ?: error("Agent edit has not committed its undo token: ${snapshot.path}")
            require(documents?.snapshot(snapshot.path)?.dirty != true) {
                "Undo conflicts with unsaved editor buffer: ${snapshot.path}"
            }
            require(files.matchesVersion(snapshot.path, expected)) {
                "File changed after Agent edit: ${snapshot.path}; newer workspace data was preserved"
            }
        }
    }

    /** Exact byte undo with fresh CAS and mandatory no-live-document commit barrier. */
    private suspend fun restoreStream(snapshot: Snapshot): Pair<Boolean, Throwable?> {
        val barrier = checkNotNull(streamCommitAuthority) { "Streamed undo editor fence unavailable" }
        val target = StreamFileVersion.capture(files.root, snapshot.path)
        require(StreamContentVersion.of(target) == snapshot.streamExpected &&
            ProjectCopyEvidence.identity(files.resolveChecked(snapshot.path)).key == snapshot.streamIdentity)
        requireBackup(snapshot)
        var failure: Throwable? = null
        var owned = false
        if (!snapshot.existed) {
            val observation = files.prepareStreamDeleteObservation(snapshot.path, target, barrier)
            try { files.deleteFileIfUnchanged(observation) } catch (error: Throwable) { failure = error }
            owned = observation.ownedCurrent
        } else {
            val expected = checkNotNull(snapshot.streamBackup)
            var settlement: StreamPublicationSettlement? = null
            val observer = object : StreamPublicationObserver, StreamPublicationCommitBarrier, StreamPublicationSettlementObserver {
                override fun beforePublish(preparation: StreamPublicationPreparation) {
                    require(StreamContentVersion.of(preparation) == expected)
                }
                override fun onPublished(receipt: StreamPublicationReceipt) = Unit
                override suspend fun commitPublication(action: () -> Unit) = barrier.commitDiskOnly(snapshot.path, action)
                override fun onSettled(value: StreamPublicationSettlement) { settlement = value }
            }
            try {
                val input = java.nio.file.Files.newInputStream(snapshot.backup.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
                files.writeStream(snapshot.path, input, expected.bytes, expectedVersion = target, publicationObserver = observer)
            } catch (error: Throwable) { failure = error }
            owned = settlement?.ownedCurrent == true
        }
        if (!owned) throw IllegalStateException("Streamed undo outcome is unverified; backup retained; do not retry automatically", failure)
        if (failure != null && failure !is CancellationException && failure !is ProjectFileSyncException) {
            throw IllegalStateException("Streamed undo has a receipt but delivery failed; backup retained", failure)
        }
        return (failure is ProjectFileSyncException) to failure?.takeIf { it is CancellationException }
    }

    /** True means the restore committed locally and only its synchronization is pending. */
    private suspend fun restore(
        snapshot: Snapshot,
        backupText: String,
        writeObservation: FileRepository.TextWriteObservation?,
        deleteObservation: FileRepository.DeleteObservation?,
        liveObservation: LiveRestoreObservation,
    ): Boolean {
        if (snapshot.liveExpected != null) {
            val authority = documents ?: error("Live editor authority is unavailable for undo: ${snapshot.path}")
            when (val result = authority.replaceText(snapshot.path, snapshot.liveExpected, backupText, "Undo Agent edit")) {
                is WorkspaceDocumentMutationResult.Applied -> {
                    check(result.before.version == snapshot.liveExpected &&
                        result.after.documentId == snapshot.liveExpected.documentId) {
                        "Live Undo receipt changed document generation: ${snapshot.path}"
                    }
                    liveObservation.applied = result
                    if (runSuspendCatching { lsp?.didChange(snapshot.path, backupText) }.isFailure) return true
                }
                is WorkspaceDocumentMutationResult.Conflict -> error("Live document changed after Agent edit: ${snapshot.path}")
                is WorkspaceDocumentMutationResult.ReadOnly -> error("Live document is read-only: ${snapshot.path}")
                WorkspaceDocumentMutationResult.NotLive -> error("Live document is no longer available: ${snapshot.path}")
            }
        } else {
            val expected = requireNotNull(snapshot.diskExpected)
            if (snapshot.existed) {
                val observation = requireNotNull(writeObservation)
                documents?.validatePersistedMutation(FileRepository.Mutation.Write(snapshot.path, backupText))
                try {
                    files.writeTextIfUnchanged(observation)
                } catch (saved: ProjectFileSyncException) {
                    if (observation.disposition != FileRepository.TextWriteDisposition.OWNED_CURRENT ||
                        saved.relativePath != observation.path || saved.bytes != observation.expectedAfter.bytes) {
                        throw IllegalStateException(
                            "Undo restore publication was not verified for ${snapshot.path}; recovery backup retained", saved,
                        )
                    }
                    return true
                }
                check(observation.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT) {
                    "Undo restore publication was not verified for ${snapshot.path}; recovery backup retained"
                }
            } else if (expected.existed) {
                documents?.validatePersistedMutation(FileRepository.Mutation.Delete(snapshot.path))
                val observation = requireNotNull(deleteObservation)
                try {
                    check(files.deleteFileIfUnchanged(observation)) { "Undo deletion failed: ${snapshot.path}" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!observation.ownedCurrent || !observation.synchronizationFailed) throw failure
                    return true
                }
                check(observation.ownedCurrent) {
                    "Undo deletion publication was not verified for ${snapshot.path}; recovery backup retained"
                }
            } else require(files.matchesVersion(snapshot.path, expected)) { "File changed after Agent edit: ${snapshot.path}" }
        }
        return false
    }

    /** A successful failed-batch rollback restores a prior live history frontier, not a new edit. */
    fun recordLiveRollback(
        pending: PendingBatch,
        path: String,
        original: LiveTransition,
        rollback: WorkspaceDocumentMutationResult.Applied,
    ) {
        require(rollback.before.path == path && rollback.after.path == path && rollback.before.version == original.after &&
            rollback.after.documentId == original.before.documentId) {
            "Live rollback receipt does not match its applied edit: $path"
        }
        synchronized(stackLock) {
            val predecessorId = pending.predecessorIds[path] ?: return
            val captured = stack.indexOfFirst { it.id == predecessorId }
            if (captured < 0 || stack[captured].snapshots.none { it.path == path }) return
            // The exact frontier was nearest before this batch began. Later commits must
            // keep their own tokens; they cannot hide an attributable rollback from it.
            rebasePredecessor(path, original.before, rollback.after.version, captured + 1)
        }
    }

    /** Called only while holding stackLock. Never skip a nearer mismatching/disk frontier. */
    private fun rebasePredecessor(
        path: String,
        expected: WorkspaceDocumentVersion,
        restored: WorkspaceDocumentVersion,
        beforeIndex: Int,
    ) {
        if (expected.documentId == null || expected.documentId != restored.documentId) return
        for (index in beforeIndex - 1 downTo 0) {
            val entry = stack[index]
            val target = entry.snapshots.indexOfFirst { it.path == path }
            if (target < 0) continue
            val snapshot = entry.snapshots[target]
            if (snapshot.liveExpected == expected) {
                val snapshots = entry.snapshots.toMutableList()
                snapshots[target] = snapshot.copy(liveExpected = restored)
                stack[index] = entry.copy(snapshots = snapshots)
            }
            return
        }
    }

    fun depth(): Int = synchronized(stackLock) { stack.size }

    private fun deleteSnapshotBackup(snapshot: Snapshot): Boolean {
        if (snapshot.streamBackup != null) {
            if (ProjectCopyEvidence.root(files.root).second != snapshot.streamRootIdentity ||
                ProjectCopyEvidence.identity(snapshot.backup).key != snapshot.streamBackupIdentity) return false
        }
        return deleteBackup(snapshot.backup)
    }

    private fun deleteBackup(backup: File): Boolean =
        ExportWriterAdmission.withWriter(files.root, "Edit-history cleanup") { backup.delete() }

    private fun trim() {
        while (stack.size > 30) {
            val index = stack.indexOfFirst { it.id != activeUndo }
            if (index < 0) break
            stack.removeAt(index).snapshots.forEach { runCatching { deleteSnapshotBackup(it) } }
        }
    }
}

/** Keep both failures after completed rollbacks and remaining history have been settled. */
internal fun rethrowLiveRollbackInterruption(original: Throwable, interruption: Throwable?) {
    val primary = when {
        original is Error -> original
        interruption is Error -> interruption
        original is CancellationException -> original
        interruption != null -> interruption
        original !is Exception -> original
        else -> return
    }
    val secondary = if (primary === original) interruption else original
    if (secondary != null && secondary !== primary) primary.addSuppressed(secondary)
    throw primary
}
