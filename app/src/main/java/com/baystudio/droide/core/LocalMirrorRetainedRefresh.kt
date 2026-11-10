package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class LocalMirrorRetainedRefresh(
    private val source: File,
    private val working: File,
    private val authority: MirrorMetadataAuthority,
    private val fingerprints: WorkspaceFileFingerprintCache,
) {
    private val mutations = MirrorWorkingCopyMutations(source, working, authority)
    private val blocked = linkedSetOf<String>()
    private val conflicts = linkedSetOf<String>()
    private val notices = linkedSetOf<String>()
    var sourceCoverageNotice: String? = null
        private set
    var workingCoverageNotice: String? = null
        private set

    suspend fun refresh(guard: WorkspaceMutationGuard): String? {
        val bootstrap = LocalMirrorAuthorityBootstrap(source, working)
        bootstrap.readLinkedState(guard, authority)
        mutations.inspectRetainedVersions(guard)
        var state = authority.load()
        blocked += state.conflicts
        conflicts += state.conflicts
        if (blocked.isNotEmpty()) notice("CLI conflicts need review; protected paths: ${blocked.take(8).joinToString()}")
        val context = currentCoroutineContext()
        val canonical = snapshot(source)
        var copy = snapshot(working)
        state = agree(state, canonical, copy)

        val directoriesByDepth = canonical.directories.groupBy { it.count { c -> c == '/' } }.toSortedMap()
        for (directories in directoriesByDepth.values) {
            val history = history(state)
            val proposals = mutableListOf<MirrorMetadataAuthority.Proposal>()
            for (path in directories.sorted()) {
                context.ensureActive()
                if (protected(path) || opaque(canonical, path) || opaque(copy, path) || path in copy.directories) continue
                val old = copy.files[path]?.entry()
                if (old == null && hasHistory(history, path)) {
                    pendingDeletion(path)
                } else if (old != null && old != state.baseline[path]) {
                    conflict(path, "CLI file differs from the published version")
                } else {
                    proposals += MirrorMetadataAuthority.Proposal(path, if (old == null) "create" else "directory",
                        old?.let(MirrorMetadataAuthority.Node::file) ?: MirrorMetadataAuthority.Node.absent, EMPTY_DIRECTORY)
                }
            }
            state = apply(guard, state, proposals)
            if (proposals.isNotEmpty()) copy = snapshot(working)
        }

        val history = history(state)
        val files = mutableListOf<MirrorMetadataAuthority.Proposal>()
        for ((path, value) in canonical.files) {
            context.ensureActive()
            if (protected(path) || opaque(copy, path)) continue
            val next = value.entry()
            val old = copy.files[path]?.entry()
            if (next == old) continue
            val before = when {
                old != null && old == state.baseline[path] -> MirrorMetadataAuthority.Node.file(old)
                old != null -> {
                    if (next != state.baseline[path]) conflict(path, "Source and CLI both changed")
                    else notice("Unpublished CLI edit preserved: $path")
                    continue
                }
                path in copy.directories -> MirrorWorkingCopyMutations.directoryNode(File(working, path), context)
                hasHistory(history, path) -> { pendingDeletion(path); continue }
                else -> MirrorMetadataAuthority.Node.absent
            }
            val kind = when (before.type) {
                MirrorMetadataAuthority.NodeType.ABSENT -> "create"
                MirrorMetadataAuthority.NodeType.FILE -> "replace"
                MirrorMetadataAuthority.NodeType.DIRECTORY -> "directory"
            }
            files += MirrorMetadataAuthority.Proposal(path, kind, before, MirrorMetadataAuthority.Node.file(next))
        }
        state = apply(guard, state, files)
        if (files.isNotEmpty()) copy = snapshot(working)

        // Retire a removed directory as one node; never fall back to deleting its children.
        val removedRoots = linkedSetOf<String>()
        val removals = mutableListOf<MirrorMetadataAuthority.Proposal>()
        val remainingHistory = history(state)
        for (path in copy.directories.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })) {
            context.ensureActive()
            if (under(path, removedRoots) || protected(path) || !absent(canonical, path) || !hasHistory(remainingHistory, path)) continue
            removedRoots += path
            removals += MirrorMetadataAuthority.Proposal(path, "delete",
                MirrorWorkingCopyMutations.directoryNode(File(working, path), context), MirrorMetadataAuthority.Node.absent)
        }
        for ((path, value) in copy.files) {
            context.ensureActive()
            if (under(path, removedRoots) || protected(path) || !absent(canonical, path)) continue
            if (value.entry() == state.baseline[path]) {
                removals += MirrorMetadataAuthority.Proposal(path, "delete", MirrorMetadataAuthority.Node.file(value.entry()),
                    MirrorMetadataAuthority.Node.absent)
            } else if (path in state.baseline) conflict(path, "Source removed a file with unpublished CLI edits")
        }
        state = apply(guard, state, removals)
        if (conflicts != state.conflicts) authority.commit(state.revision, state.baseline, state.conflicts + conflicts)
        bootstrap.readLinkedState(guard, authority)
        AnchoredWorkspaceFiles(working).use { anchor ->
            if (anchor.kind(".droide-sync-manifest.tsv") != AnchoredWorkspaceFiles.Kind.ABSENT) {
                notice("CLI manifest preserved; synchronization uses host metadata")
            }
        }
        return notices.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private suspend fun snapshot(root: File): AnchoredWorkspaceTree.Snapshot {
        val result = AnchoredWorkspaceTree.snapshot(root, fingerprints, currentCoroutineContext()) {
            !AgentWorkspacePathPolicy.visible(it) || protected(it)
        }
        if (root == source) sourceCoverageNotice = AgentWorkspacePathPolicy.coverageNotice(result.opaque, "Project")
        else workingCoverageNotice = AgentWorkspacePathPolicy.coverageNotice(result.opaque, "CLI workspace")
        return result
    }

    private fun agree(
        state: MirrorMetadataAuthority.State,
        source: AnchoredWorkspaceTree.Snapshot,
        copy: AnchoredWorkspaceTree.Snapshot,
    ): MirrorMetadataAuthority.State {
        val files = state.baseline.toMutableMap()
        val directories = state.directoryHistory.toMutableSet()
        for ((path, value) in source.files) {
            if (value.entry() != copy.files[path]?.entry()) continue
            if (value.entry() == files[path]) continue
            files.keys.removeAll { it.startsWith("$path/") }
            directories.removeAll { it == path || it.startsWith("$path/") }
            files[path] = value.entry()
        }
        for (path in source.directories) if (path in copy.directories) {
            files.remove(path)
            directories += path
        }
        files.keys.removeAll { !protected(it) && absent(source, it) && absent(copy, it) }
        directories.removeAll { !protected(it) && absent(source, it) && absent(copy, it) }
        if (files == state.baseline && directories == state.directoryHistory) return state
        return authority.commitWithHistory(state.revision, files, state.conflicts, directories)
    }

    private suspend fun apply(
        guard: WorkspaceMutationGuard,
        state: MirrorMetadataAuthority.State,
        proposals: List<MirrorMetadataAuthority.Proposal>,
    ): MirrorMetadataAuthority.State {
        var current = state
        if (conflicts != current.conflicts) current = authority.commit(current.revision, current.baseline, current.conflicts + conflicts)
        if (proposals.isEmpty()) return current
        for (batch in proposals.chunked(20_000)) {
            val results = mutations.apply(guard, current.revision, batch.filterNot { protected(it.path) })
            for (result in results) result.conflict?.let { conflict(result.path, it) }
            current = authority.load()
            blocked += current.conflicts
            conflicts += current.conflicts
        }
        return current
    }

    private fun WorkspaceFileFingerprintCache.Fingerprint.entry() =
        WorkspaceSyncManifest.Entry(sha256, if (executable) 493 else 420)

    private fun history(state: MirrorMetadataAuthority.State) = TreeSet<String>().apply {
        addAll(state.baseline.keys)
        addAll(state.directoryHistory)
    }

    private fun hasHistory(history: TreeSet<String>, path: String) =
        path in history || history.ceiling("$path/")?.startsWith("$path/") == true

    private fun protected(path: String) = blocked.any { it == path || it.startsWith("$path/") || path.startsWith("$it/") }

    private fun under(path: String, roots: Set<String>): Boolean {
        var ancestor = path
        while (true) {
            if (ancestor in roots) return true
            val slash = ancestor.lastIndexOf('/')
            if (slash < 0) return false
            ancestor = ancestor.substring(0, slash)
        }
    }

    private fun opaque(tree: AnchoredWorkspaceTree.Snapshot, path: String) = under(path, tree.opaque)

    private fun absent(tree: AnchoredWorkspaceTree.Snapshot, path: String) =
        path !in tree.files && path !in tree.directories && !opaque(tree, path)

    private fun pendingDeletion(path: String) {
        blocked += path
        notice("Unpublished CLI deletion preserved: $path")
    }

    private fun conflict(path: String, reason: String) {
        blocked += path
        conflicts += path
        notice("$reason: $path; review is required and existing versions were preserved")
    }

    private fun notice(message: String) {
        if (notices.size < 16) notices += message
    }

    private companion object {
        val EMPTY_DIRECTORY = MirrorMetadataAuthority.Node.directory(MessageDigest.getInstance("SHA-256")
            .digest(byteArrayOf()).joinToString("") { "%02x".format(it) })
    }
}
