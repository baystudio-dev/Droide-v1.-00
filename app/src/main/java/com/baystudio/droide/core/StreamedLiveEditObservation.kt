package com.baystudio.droide.core

import java.io.File

/** Runtime identity for a reviewed live-buffer mutation. Never a disk publication receipt. */
internal class StreamedLiveEditObservation(
    val root: File,
    val path: String,
    val before: WorkspaceDocumentSnapshot,
    val replacement: WorkspaceSourceTextReplacement,
    val argumentsSha256: String,
    val undo: EditHistory.PendingLive,
) {
    private val originalRoot = ProjectCopyEvidence.root(root)
    val canonicalRoot: String = originalRoot.first.path
    val rootIdentity: String = originalRoot.second
    val backupRelative: String
    val backupIdentity: String
    private lateinit var backupFence: Pair<ProjectCopyEvidence.Identity, String>
    val beforeContent: DiskFileVersion = DiskFileVersion.fromText(before.content)
    @Volatile private var undoAcknowledged: Boolean = false

    init {
        require(path == before.path && path == undo.path)
        require(before.loaded && before.kind == WorkspaceDocumentKind.TEXT && before.editable && before.documentId != null)
        require(argumentsSha256.matches(Regex("[a-f0-9]{64}")))
        require(undo.backupExpected == beforeContent)
        require(PathSecurity.contains(root.canonicalFile, undo.backup.canonicalFile))
        backupRelative = root.canonicalFile.toPath().relativize(undo.backup.canonicalFile.toPath()).toString()
        backupIdentity = ProjectCopyEvidence.identity(undo.backup).key
        verifyUndo()
    }

    fun requireRootCurrent() { require(ProjectCopyEvidence.root(root) == originalRoot) { "Original workspace root changed" } }

    private fun readBackupFence(): Pair<ProjectCopyEvidence.Identity, String> {
        ChatMediaStoragePaths.requireNoLinks(undo.backup)
        return ProjectCopyEvidence.identity(undo.backup) to java.nio.file.Files.getAttribute(
            undo.backup.toPath(), "unix:ctime", java.nio.file.LinkOption.NOFOLLOW_LINKS).toString()
    }

    /** Constant-size metadata admission after queue/native prepare; unsupported ctime fails closed. */
    fun requireBackupFence() {
        requireRootCurrent()
        require(readBackupFence() == backupFence) { "Reviewed undo backup changed before native mutation" }
    }

    fun verifyUndo() {
        requireRootCurrent()
        ChatMediaStoragePaths.requireNoLinks(undo.backup)
        val beforeFence = readBackupFence()
        require(beforeFence.first.key == backupIdentity && beforeContent.matches(undo.backup)) {
            "Reviewed live undo backup changed; the source mutation was not admitted"
        }
        require(readBackupFence() == beforeFence) { "Undo backup changed during verification" }
        backupFence = beforeFence
    }

    fun acknowledgeUndo() {
        check(replacement.publication != null)
        undoAcknowledged = true
    }

    class PublicationProof private constructor(
        val observation: StreamedLiveEditObservation,
        val publication: WorkspaceSourceTextReplacement.Publication,
        val undoAcknowledged: Boolean,
    ) {
        companion object {
            internal fun admit(observation: StreamedLiveEditObservation): PublicationProof {
                val p = checkNotNull(observation.replacement.publication)
                check(p.replacement === observation.replacement && p.path == observation.path)
                check(p.before == observation.before.version && p.beforeSnapshot.content == observation.before.content)
                check(p.after.documentId == observation.before.documentId && p.effect == "LIVE_BUFFER")
                return PublicationProof(observation, p, observation.undoAcknowledged)
            }
        }
    }

    fun publicationProof(): PublicationProof = PublicationProof.admit(this)
}
