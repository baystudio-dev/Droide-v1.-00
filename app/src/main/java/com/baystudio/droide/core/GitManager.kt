package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.filter.PathFilter

enum class GitChangeKind { ADDED, MODIFIED, DELETED, UNTRACKED, CONFLICT }

data class GitChange(
    val path: String,
    val kind: GitChangeKind,
    val staged: Boolean,
)

data class GitStash(val index: Int, val id: String, val message: String)

data class GitCommitSummary(
    val id: String,
    val shortId: String,
    val subject: String,
    val author: String,
    val epochMillis: Long,
    val parentCount: Int,
)

class GitManager(
    private val workDir: File,
    private val documentAuthority: WorkspaceDocumentAuthority? = null,
    private val githubCredentialResolver: (suspend () -> GitHubTransportCredential?)? = null,
    private val syncLinkedFolder: (suspend () -> String?)? = null,
) {
    // Even read-oriented JGit entry points maintain info/exclude. Admission covers opening through
    // close, not only the final worktree/index write.
    private suspend fun <T> gitIo(action: suspend () -> T): T =
        ExportWriterAdmission.withSuspendingWriter(workDir, "Git operation") {
            withContext(Dispatchers.IO) { action() }
        }

    private fun gitDir(): File = PathSecurity.resolveWithin(workDir, ".git")

    private fun openExisting(): Git? {
        val gitDir = runCatching(::gitDir).getOrNull() ?: return null
        if (!gitDir.isDirectory) return null
        return runCatching {
            ensureRuntimeExclude()
            Git(FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(workDir).readEnvironment().build())
        }.getOrNull()
    }

    internal fun forWorkDir(root: File, documentAuthority: WorkspaceDocumentAuthority? = null): GitManager =
        GitManager(root, documentAuthority, githubCredentialResolver)

    // A Git worktree mutation must never silently diverge from the editor or linked SAF folder.
    private suspend fun mutatePersisted(label: String, action: suspend () -> String): String =
        ExportWriterAdmission.withSuspendingWriter(workDir, "Git $label") {
            mutatePersistedAdmitted(label, action)
        }

    private suspend fun mutatePersistedAdmitted(label: String, action: suspend () -> String): String {
        val dirty = documentAuthority?.dirtySnapshots().orEmpty().filter { it.dirty }
        if (dirty.isNotEmpty()) {
            val paths = dirty.take(8).joinToString { it.path }
            val extra = if (dirty.size > 8) " … +${dirty.size - 8} more" else ""
            return "git error: Save or discard unsaved editor changes before $label: $paths$extra"
        }
        var cancellation: CancellationException? = null
        val outcome = try { action() }
            catch (cancelled: CancellationException) { cancellation = cancelled; "git error: $label interrupted; inspect workspace state" }
            catch (failure: Exception) { "git error: ${failure.message ?: label}" }

        val settled = withContext(NonCancellable + Dispatchers.IO) {
            val sync = runSuspendCatching { syncLinkedFolder?.invoke() }
            val reconciliation = runSuspendCatching { documentAuthority?.reconcileAfterPersistedMutation() }
            val warnings = buildList {
                sync.exceptionOrNull()?.let { add("linked folder sync pending/conflict: ${it.message}") }
                reconciliation.exceptionOrNull()?.let { add("editor reconciliation failed: ${it.message}") }
                reconciliation.getOrNull()?.conflicts?.takeIf { it.isNotEmpty() }?.let { add("editor conflicts: ${it.take(8).joinToString()}") }
            }
            val changed = reconciliation.getOrNull()?.let { it.refreshed.size + it.removed.size + it.remapped.size } ?: 0
            val result = outcome + (if (changed > 0) "; refreshed $changed open document(s)" else "") +
                sync.getOrNull()?.takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty()
            if (warnings.isEmpty()) result
            else "git error: $label post-operation sync/reconcile incomplete: ${warnings.joinToString("; ")}\n$result"
        }
        cancellation?.let { throw it }
        return settled
    }

    private suspend fun credentialsForClone(
        url: String,
        username: String?,
        token: String?,
    ): org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider? {
        if (!token.isNullOrBlank()) return GitTransportAuth.forClone(url, username, token)
        if (!GitTransportAuth.mayUseConnectedAccountForClone(url)) return null
        val account = githubCredentialResolver?.invoke() ?: return null
        return GitTransportAuth.forClone(url, account.username, account.accessToken)
    }

    private suspend fun credentialsForRemote(
        repository: Repository,
        remoteName: String,
        push: Boolean,
        username: String?,
        token: String?,
    ): org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider? {
        if (!token.isNullOrBlank()) return GitTransportAuth.forRemote(repository, remoteName, push, username, token)
        if (!GitTransportAuth.mayUseConnectedAccountForRemote(repository, remoteName, push)) return null
        val account = githubCredentialResolver?.invoke() ?: return null
        return GitTransportAuth.forRemote(repository, remoteName, push, account.username, account.accessToken)
    }

    suspend fun isRepository(): Boolean = gitIo {
        openExisting()?.use { true } ?: false
    }

    suspend fun init(): String = gitIo {
        openExisting()?.use { return@gitIo "Git repository already initialized" }
        Git.init().setDirectory(workDir).call().close()
        ensureRuntimeExclude()
        "Initialized Git repository"
    }

    suspend fun status(): String = gitIo {
        val g = openExisting() ?: return@gitIo "(not a git repository)"
        try {
            g.use {
                val s = it.status().call()
                buildString {
                    appendLine("Added: ${summarizePaths(s.added)}")
                    appendLine("Changed: ${summarizePaths(s.changed)}")
                    appendLine("Modified: ${summarizePaths(s.modified)}")
                    appendLine("Removed: ${summarizePaths(s.removed + s.missing)}")
                    appendLine("Untracked: ${summarizePaths(s.untracked)}")
                    appendLine("Branch: ${runCatching { it.repository.branch }.getOrNull() ?: "?"}")
                    val identity = GitIdentityPolicy.effective(it.repository)
                    appendLine("Identity: ${identity?.let { id -> "${id.name} <${id.email}>" } ?: "(not configured)"}")
                    val remotes = it.remoteList().call().asSequence()
                        .flatMap { r -> r.getURIs().asSequence() }
                        .map { uri -> GitRemoteSecurity.redact(uri.toString()) }
                        .take(10)
                        .joinToString()
                    if (remotes.isNotBlank()) appendLine("Remotes: $remotes")
                }.take(16_000)
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun changes(): List<GitChange> = gitIo {
        val g = openExisting() ?: return@gitIo emptyList()
        g.use {
            val s = it.status().call()
            buildList {
                s.added.sorted().forEach { path -> add(GitChange(path, GitChangeKind.ADDED, true)) }
                s.changed.sorted().forEach { path -> add(GitChange(path, GitChangeKind.MODIFIED, true)) }
                s.removed.sorted().forEach { path -> add(GitChange(path, GitChangeKind.DELETED, true)) }
                s.modified.sorted().forEach { path -> add(GitChange(path, GitChangeKind.MODIFIED, false)) }
                s.missing.sorted().forEach { path -> add(GitChange(path, GitChangeKind.DELETED, false)) }
                s.untracked.sorted().forEach { path -> add(GitChange(path, GitChangeKind.UNTRACKED, false)) }
                s.conflicting.sorted().forEach { path -> add(GitChange(path, GitChangeKind.CONFLICT, false)) }
            }.distinctBy { Triple(it.path, it.kind, it.staged) }
        }
    }

    suspend fun stage(path: String, allowConflictResolution: Boolean = false): String = gitIo {
        val repoPath = validateRepoPath(path)
        require(!SensitivePathPolicy.isSensitive(repoPath)) { "Refusing to stage sensitive path: $repoPath" }
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        var resolvedConflict = false
        g.use {
            val status = it.status().call()
            fun selected(candidate: String): Boolean = repoPath == "." || candidate == repoPath || candidate.startsWith(repoPath.trimEnd('/') + "/")
            val selectedSensitive = (status.added + status.changed + status.modified + status.missing + status.untracked)
                .filter { candidate -> selected(candidate) && SensitivePathPolicy.isSensitive(candidate) }.sorted()
            require(selectedSensitive.isEmpty()) { "Refusing to stage sensitive path(s): ${selectedSensitive.take(8).joinToString()}" }
            val selectedConflicts = status.conflicting.filter(::selected)
            require(selectedConflicts.isEmpty() || (allowConflictResolution && selectedConflicts == listOf(repoPath))) {
                "Resolve and explicitly stage each conflicted file: ${selectedConflicts.sorted().take(8).joinToString()}"
            }
            val conflicting = repoPath in status.conflicting
            require(!conflicting || allowConflictResolution) { "Resolve conflict before staging: $repoPath" }
            if (conflicting && allowConflictResolution) {
                val target = PathSecurity.resolveWithin(workDir, repoPath)
                require(target.isFile && target.length() <= 2_000_000) { "Conflict file is unavailable or too large for safe resolution: $repoPath" }
                require(!MergeConflictResolver.hasMarkers(target.readText())) { "Conflict markers remain in $repoPath" }
            }
            resolvedConflict = conflicting
            if (repoPath in status.missing) {
                it.add().setUpdate(true).addFilepattern(repoPath).call()
            } else {
                it.add().addFilepattern(repoPath).call()
            }
        }
        if (resolvedConflict) "Marked resolved and staged $repoPath" else "Staged $repoPath"
    }

    suspend fun conflictBlocks(path: String): List<MergeConflictBlock> = gitIo {
        val repoPath = validateRepoPath(path)
        val g = openExisting() ?: return@gitIo emptyList()
        g.use {
            require(repoPath in it.status().call().conflicting) { "Path is not currently conflicted: $repoPath" }
        }
        val target = PathSecurity.resolveWithin(workDir, repoPath)
        require(target.isFile && target.length() <= 2_000_000) { "Conflict file is unavailable or too large to resolve in-app" }
        val text = target.readText()
        val blocks = MergeConflictResolver.blocks(text)
        require(blocks.isNotEmpty() || !MergeConflictResolver.hasMarkers(text)) { "Malformed merge-conflict markers require manual editing" }
        blocks
    }

    suspend fun resolveConflictBlock(path: String, blockIndex: Int, choice: ConflictChoice): String = mutatePersisted("Resolve conflict") { gitIo {
        val repoPath = validateRepoPath(path)
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use {
            require(repoPath in it.status().call().conflicting) { "Path is not currently conflicted: $repoPath" }
        }
        val target = PathSecurity.resolveWithin(workDir, repoPath)
        require(target.isFile && target.length() <= 2_000_000) { "Conflict file is unavailable or too large to resolve in-app" }
        val before = target.readText()
        val after = MergeConflictResolver.resolve(before, blockIndex, choice)
        require(after != before) { "Conflict resolution produced no change" }
        atomicWrite(target, after)
        val remaining = MergeConflictResolver.blocks(after).size
        if (remaining == 0) "Resolved final conflict block in $repoPath; review, then mark resolved & stage"
        else "Resolved conflict block ${blockIndex + 1} in $repoPath ($remaining remaining)"
    } }

    suspend fun stageAll(): String = gitIo {
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use {
            val s = it.status().call()
            require(s.conflicting.isEmpty()) { "Resolve and explicitly stage merge conflicts before Stage all: ${s.conflicting.sorted().take(8).joinToString()}" }
            val candidates = s.modified + s.missing + s.untracked + s.added + s.changed + s.removed
            val sensitive = candidates.filter(SensitivePathPolicy::isSensitive).sorted()
            require(sensitive.isEmpty()) { "Refusing to stage sensitive path(s): ${sensitive.take(8).joinToString()}" }
            if (candidates.isEmpty()) return@gitIo "Nothing to stage"
            it.add().addFilepattern(".").call()
            it.add().setUpdate(true).addFilepattern(".").call()
        }
        "Staged working tree changes"
    }

    suspend fun unstage(path: String): String = gitIo {
        val repoPath = validateRepoPath(path)
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use {
            if (it.repository.resolve(Constants.HEAD) != null) {
                it.reset().setRef(Constants.HEAD).addPath(repoPath).call()
            } else {
                val index = it.repository.lockDirCache()
                try {
                    val editor = index.editor()
                    editor.add(DirCacheEditor.DeletePath(repoPath))
                    editor.add(DirCacheEditor.DeleteTree(repoPath))
                    check(editor.commit()) { "Could not update the Git index" }
                } finally { index.unlock() }
            }
        }
        "Unstaged $repoPath"
    }

    suspend fun discardWorkingTree(path: String): String = mutatePersisted("Discard") { gitIo {
        val repoPath = validateRepoPath(path)
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use {
            val s = it.status().call()
            require(repoPath !in s.conflicting) { "Resolve the conflict explicitly; discard is disabled for conflicted files" }
            require(repoPath !in s.untracked) { "Untracked files are never deleted by Discard. Delete the file explicitly instead." }
            require(repoPath !in s.added && repoPath !in s.changed && repoPath !in s.removed) {
                "Unstage $repoPath before discarding working-tree changes"
            }
            require(repoPath in s.modified || repoPath in s.missing) { "No discardable working-tree change for $repoPath" }
            it.checkout().addPath(repoPath).call()
        }
        "Discarded working-tree changes in $repoPath"
    } }

    suspend fun identity(): GitIdentity? = gitIo {
        val g = openExisting() ?: return@gitIo null
        g.use { GitIdentityPolicy.effective(it.repository) }
    }

    suspend fun configureIdentity(name: String, email: String): String = gitIo {
        val g = openExisting() ?: return@gitIo "git error: initialize repository first"
        g.use {
            val identity = GitIdentityPolicy.configureLocal(it.repository, name, email)
            "Git identity set to ${identity.name} <${identity.email}>"
        }
    }

    suspend fun clearIdentity(): String = gitIo {
        val g = openExisting() ?: return@gitIo "git error: initialize repository first"
        g.use { GitIdentityPolicy.clearLocal(it.repository) }
        "Cleared repository Git identity"
    }

    suspend fun commitStaged(message: String): String = gitIo {
        val safeMessage = validateCommitMessage(message)
        val g = openExisting() ?: return@gitIo "git error: initialize repository first"
        ensureRuntimeExclude()
        g.use {
            val s = it.status().call()
            val staged = s.added + s.changed + s.removed
            require(s.conflicting.isEmpty()) { "Resolve and explicitly stage all merge conflicts before committing" }
            require(staged.isNotEmpty()) { "Nothing staged to commit" }
            val sensitive = staged.filter(SensitivePathPolicy::isSensitive).sorted()
            require(sensitive.isEmpty()) { "Refusing to commit sensitive path(s): ${sensitive.take(8).joinToString()}" }
            val identity = GitIdentityPolicy.require(it.repository)
            val c = it.commit()
                .setMessage(safeMessage)
                .setAuthor(identity.name, identity.email)
                .setCommitter(identity.name, identity.email)
                .call()
            "Committed ${c.name.take(7)}: ${safeMessage.take(120)}"
        }
    }

    suspend fun fetch(username: String? = null, token: String? = null): String = gitIo {
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        try {
            g.use {
                requireConfiguredRemotesAllowed(it.repository)
                val remote = GitTransportAuth.fetchRemote(it.repository)
                GitTransportSecurity.requireAvailable(GitTransportSecurity.operationUris(it.repository, remote, push = false))
                val credentials = credentialsForRemote(it.repository, remote, push = false, username, token)
                try {
                    val cmd = it.fetch().setRemote(remote).setTimeout(60).setRemoveDeletedRefs(true)
                    cmd.setTransportConfigCallback(GitTransportSecurity.callback(credentials != null))
                    credentials?.let(cmd::setCredentialsProvider)
                    val result = cmd.call()
                    val updates = result.trackingRefUpdates.size
                    "Fetch successful ($updates tracking ref update${if (updates == 1) "" else "s"})"
                } finally {
                    credentials?.clear()
                }
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun stash(includeUntracked: Boolean = true): String = mutatePersisted("Stash") { gitIo {
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use {
            val status = it.status().call()
            require(status.conflicting.isEmpty()) { "Resolve merge conflicts before stashing" }
            if (!status.hasUncommittedChanges() && status.untracked.isEmpty()) return@gitIo "Nothing to stash"
            val sensitiveUntracked = if (includeUntracked) status.untracked.filter(SensitivePathPolicy::isSensitive) else emptyList()
            require(sensitiveUntracked.isEmpty()) { "Refusing to stash sensitive untracked path(s): ${sensitiveUntracked.take(8).joinToString()}" }
            val commit = it.stashCreate().setIncludeUntracked(includeUntracked).call()
                ?: return@gitIo "Nothing to stash"
            "Stashed ${commit.name.take(7)}"
        }
    } }

    suspend fun stashes(): List<GitStash> = gitIo {
        val g = openExisting() ?: return@gitIo emptyList()
        g.use {
            it.stashList().call().mapIndexed { index, c -> GitStash(index, c.name.take(12), c.shortMessage) }
        }
    }

    suspend fun applyStash(index: Int): String = mutatePersisted("Apply stash") { gitIo {
        require(index >= 0) { "Invalid stash index" }
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use { it.stashApply().setStashRef("stash@{$index}").call() }
        "Applied stash@{$index}"
    } }

    suspend fun dropStash(index: Int): String = gitIo {
        require(index >= 0) { "Invalid stash index" }
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        g.use { it.stashDrop().setStashRef(index).call() }
        "Dropped stash@{$index}"
    }

    suspend fun branches(): String = gitIo {
        val g = openExisting() ?: return@gitIo "(not a git repository)"
        try {
            g.use {
                val cur = runCatching { it.repository.branch }.getOrNull() ?: "?"
                val all = it.branchList().call().map { ref -> ref.name.removePrefix(Constants.R_HEADS) } +
                    it.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                        .call().map { ref -> ref.name }
                buildString {
                    appendLine("Current: $cur")
                    all.distinct().forEach { name -> appendLine(if (name == cur || name.endsWith("/$cur")) "* $name" else "  $name") }
                }
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun checkout(ref: String, create: Boolean = false): String = mutatePersisted("Checkout") { gitIo {
        val requested = ref.trim()
        require(GitBranchSwitchAuthority.isValidBranchName(requested)) { "Invalid branch name" }
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        try {
            g.use { git ->
                val localBranches = git.branchList().call()
                    .map { branch -> branch.name.removePrefix(Constants.R_HEADS) }
                    .toSet()
                val remoteBranches = git.branchList()
                    .setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.REMOTE)
                    .call()
                    .map { branch -> branch.name }
                    .toSet()
                val plan = GitBranchSwitchAuthority.plan(
                    requested,
                    create,
                    GitBranchSwitchAuthority.Inventory(localBranches, remoteBranches),
                )
                val targetRef = Constants.R_HEADS + plan.localName
                if (!create && git.repository.fullBranch == targetRef) {
                    return@gitIo "Already on ${plan.localName}"
                }

                val dirty = documentAuthority?.dirtySnapshots().orEmpty().filter { it.dirty }
                require(dirty.isEmpty()) {
                    val listed = dirty.take(8).joinToString { it.path }
                    val extra = (dirty.size - 8).coerceAtLeast(0)
                    "Save or discard unsaved editor changes before switching branches: $listed${if (extra > 0) " … +$extra more" else ""}"
                }

                val beforeBranch = git.repository.fullBranch
                val beforeHead = git.repository.resolve(Constants.HEAD)?.name
                try {
                    when (plan) {
                        is GitBranchSwitchAuthority.Plan.ExistingLocal -> {
                            git.checkout().setName(plan.localName).call()
                        }
                        is GitBranchSwitchAuthority.Plan.NewLocal -> {
                            git.checkout()
                                .setName(plan.localName)
                                .setCreateBranch(true)
                                .setStartPoint(Constants.HEAD)
                                .call()
                        }
                        is GitBranchSwitchAuthority.Plan.TrackRemote -> {
                            git.checkout()
                                .setName(plan.localName)
                                .setCreateBranch(true)
                                .setStartPoint(plan.remoteRef)
                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                .call()
                        }
                    }
                } catch (t: Throwable) {
                    val afterFailureBranch = git.repository.fullBranch
                    val afterFailureHead = git.repository.resolve(Constants.HEAD)?.name
                    check(afterFailureBranch == beforeBranch && afterFailureHead == beforeHead) {
                        "Branch switch failed after repository state changed; inspect Source Control before continuing"
                    }
                    throw t
                }

                check(git.repository.fullBranch == targetRef) {
                    "Branch switch postcondition failed: expected ${plan.localName}, got ${git.repository.branch}"
                }
                val reconciliation = documentAuthority?.reconcileAfterPersistedMutation()
                check(reconciliation?.conflicts.orEmpty().isEmpty()) {
                    "Branch switched, but editor reconciliation detected concurrent unsaved changes: " +
                        reconciliation?.conflicts.orEmpty().take(8).joinToString()
                }
                val refreshSuffix = reconciliation?.let {
                    val changed = it.refreshed.size + it.removed.size + it.remapped.size
                    if (changed > 0) "; refreshed $changed open document${if (changed == 1) "" else "s"}" else ""
                }.orEmpty()
                val remoteSuffix = if (plan is GitBranchSwitchAuthority.Plan.TrackRemote) {
                    "; tracking ${plan.remoteRef.removePrefix("refs/remotes/")}"
                } else ""
                "Switched to ${plan.localName}$remoteSuffix$refreshSuffix"
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    } }

    suspend fun log(max: Int = 20): String = gitIo {
        val g = openExisting() ?: return@gitIo "(not a git repository)"
        try {
            g.use {
                it.log().setMaxCount(max.coerceIn(1, 100)).call().joinToString("\n") { c ->
                    "${c.name.take(7)} ${c.shortMessage} — ${c.authorIdent.name}"
                }.ifEmpty { "(no commits)" }
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun history(max: Int = 50): List<GitCommitSummary> = gitIo {
        val g = openExisting() ?: return@gitIo emptyList()
        g.use { git ->
            runCatching {
                git.log().setMaxCount(max.coerceIn(1, 200)).call().map { c ->
                    GitCommitSummary(
                        id = c.name,
                        shortId = c.name.take(8),
                        subject = c.shortMessage.take(500),
                        author = c.authorIdent.name.take(200),
                        epochMillis = c.commitTime.toLong() * 1_000L,
                        parentCount = c.parentCount,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun diffPath(path: String, staged: Boolean): String = gitIo {
        val repoPath = validateRepoPath(path)
        require(!SensitivePathPolicy.isSensitive(repoPath)) { "Refusing to expose diff for sensitive path: $repoPath" }
        val g = openExisting() ?: return@gitIo "(not a git repository)"
        try {
            g.use { git ->
                val out = BoundedOutput(96_000)
                git.diff()
                    .setCached(staged)
                    .setPathFilter(PathFilter.create(repoPath))
                    .setContextLines(3)
                    .setOutputStream(out)
                    .call()
                val sanitized = sanitizeDiff(out.utf8()).take(40_000)
                buildString {
                    append(sanitized.ifBlank { if (staged) "(no staged diff for $repoPath)" else "(no tracked working-tree diff for $repoPath)" })
                    if (out.truncated) append("\n[diff truncated; ${out.totalBytes} bytes generated]")
                }.take(41_000)
            }
        } catch (e: Exception) {
            "git error: ${e.message}"
        }
    }

    suspend fun diff(): String = gitIo {
        val g = openExisting() ?: return@gitIo "(not a git repository)"
        try {
            g.use {
                val out = BoundedOutput(96_000)
                it.diff().setOutputStream(out).call()
                val sanitized = sanitizeDiff(out.utf8()).take(24_000)
                buildString {
                    append(sanitized.ifBlank { "(no changes)" })
                    if (out.truncated) append("\n[diff truncated; ${out.totalBytes} bytes generated]")
                }.take(25_000)
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun pull(username: String? = null, token: String? = null): String = mutatePersisted("Pull") { gitIo {
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        try {
            g.use {
                requireConfiguredRemotesAllowed(it.repository)
                val remote = GitTransportAuth.pullRemote(it.repository)
                GitTransportSecurity.requireAvailable(GitTransportSecurity.operationUris(it.repository, remote, push = false))
                val credentials = credentialsForRemote(it.repository, remote, push = false, username, token)
                try {
                    val cmd = it.pull().setRemote(remote).setTimeout(60)
                    cmd.setTransportConfigCallback(GitTransportSecurity.callback(credentials != null))
                    credentials?.let(cmd::setCredentialsProvider)
                    val r = cmd.call()
                    "Pull ${if (r.isSuccessful) "successful" else "failed"}: ${r.mergeResult?.mergeStatus ?: ""}"
                } finally {
                    credentials?.clear()
                }
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    } }

    suspend fun push(username: String? = null, token: String? = null): String = gitIo {
        val g = openExisting() ?: return@gitIo "git error: not a git repository"
        try {
            g.use {
                requireConfiguredRemotesAllowed(it.repository)
                val remote = GitTransportAuth.pushRemote(it.repository)
                GitTransportSecurity.requireAvailable(GitTransportSecurity.operationUris(it.repository, remote, push = true))
                val credentials = credentialsForRemote(it.repository, remote, push = true, username, token)
                try {
                    val cmd = it.push().setRemote(remote).setTimeout(60)
                    cmd.setTransportConfigCallback(GitTransportSecurity.callback(credentials != null))
                    credentials?.let(cmd::setCredentialsProvider)
                    val res = cmd.call()
                    GitPushOutcome.render(res)
                } finally {
                    credentials?.clear()
                }
            }
        } catch (e: Exception) { "git error: ${e.message}" }
    }

    suspend fun clone(url: String, username: String? = null, token: String? = null): String = gitIo {
        require(GitRemoteSecurity.isAllowed(url)) { "Only HTTPS or SSH Git remotes without embedded HTTPS credentials are allowed" }
        if (gitDir().exists()) return@gitIo "Repository already exists in ${workDir.name}"
        GitTransportSecurity.requireAvailable(listOf(GitTransportSecurity.cloneUri(url)))
        val credentials = credentialsForClone(url, username, token)
        try {
            Git.cloneRepository().setURI(url).setDirectory(workDir).setTimeout(120).apply {
                setTransportConfigCallback(GitTransportSecurity.callback(credentials != null))
                credentials?.let(::setCredentialsProvider)
            }.call().close()
        } finally {
            credentials?.clear()
        }
        ensureRuntimeExclude()
        "Clone completed"
    }

    // Pull and push must therefore re-validate both fetch and push URLs.

    private fun requireConfiguredRemotesAllowed(repository: Repository) {
        val config = repository.config
        val remotes = config.getSubsections("remote")
            .asSequence()
            .flatMap { name ->
                sequence {
                    config.getStringList("remote", name, "url").forEach { yield(it) }
                    config.getStringList("remote", name, "pushurl").forEach { yield(it) }
                }
            }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        require(remotes.isNotEmpty()) { "No Git remote configured" }
        val blocked = remotes.filterNot(GitRemoteSecurity::isAllowed)
        require(blocked.isEmpty()) {
            "Git remote blocked by security policy: ${blocked.take(4).joinToString { GitRemoteSecurity.redact(it) }}"
        }
    }

    suspend fun commitAll(message: String): String = gitIo {
        val safeMessage = validateCommitMessage(message)
        val g = openExisting() ?: return@gitIo "git error: initialize repository first"
        ensureRuntimeExclude()
        g.use {
            val before = it.status().call()
            require(before.conflicting.isEmpty()) { "Resolve and explicitly stage all merge conflicts before Commit all" }
            val candidates = before.added + before.changed + before.modified + before.untracked + before.untrackedFolders + before.removed + before.missing
            val sensitive = candidates.filter(SensitivePathPolicy::isSensitive).sorted()
            require(sensitive.isEmpty()) { "Refusing to commit sensitive path(s): ${sensitive.take(8).joinToString()}" }
            it.add().addFilepattern(".").call()
            it.add().setUpdate(true).addFilepattern(".").call()
            val identity = GitIdentityPolicy.require(it.repository)
            val c = it.commit()
                .setMessage(safeMessage)
                .setAuthor(identity.name, identity.email)
                .setCommitter(identity.name, identity.email)
                .call()
            "Committed ${c.name.take(7)}: ${safeMessage.take(120)}"
        }
    }

    private fun ensureRuntimeExclude() {
        val gd = gitDir()
        if (!gd.isDirectory) return
        // Imported metadata can link to user files or contain oversized custom rules. Exclusion
        // maintenance must never follow such links or replace unread rules.
        val rawGit = File(workDir, ".git")
        val rawInfo = File(rawGit, "info")
        val rawExclude = File(rawInfo, "exclude")
        if (listOf(rawGit, rawInfo, rawExclude).any { Files.isSymbolicLink(it.toPath()) }) return
        val f = PathSecurity.resolveWithin(workDir, ".git/info/exclude")
        if (f.exists() && (!f.isFile || f.length() > 1_000_000)) return
        val wanted = listOf(
            // Runtime files must not pollute status or block Stage all as sensitive. Existing tracked
            // project configuration stays tracked by Git.
            "/.droide/",

            ".droide/sessions/",
            ".droide/backups/",
            ".droide/history/",
            ".droide/shares/",
            ".droide/transactions/",
            ".droide/worktrees/",
            ".droide/auth.json",
        )
        val existing = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
        var changed = false
        wanted.forEach { if (it !in existing) { existing += it; changed = true } }
        if (changed || !f.exists()) atomicWrite(f, existing.joinToString("\n").trimEnd() + "\n")
    }

    private fun summarizePaths(paths: Collection<String>, max: Int = 50): String {
        if (paths.isEmpty()) return "[]"
        val sorted = paths.asSequence().sorted().toList()
        val shown = sorted.take(max).joinToString(prefix = "[", postfix = "]")
        return if (sorted.size > max) "$shown (+${sorted.size - max} more)" else shown
    }

    private fun sanitizeDiff(raw: String): String {
        if (raw.isBlank()) return raw
        val out = StringBuilder(minOf(raw.length, 24_000))
        var skip = false
        raw.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                val parts = line.removePrefix("diff --git ").split(' ', limit = 2)
                val a = parts.getOrNull(0)?.removePrefix("a/").orEmpty()
                val b = parts.getOrNull(1)?.removePrefix("b/").orEmpty()
                skip = SensitivePathPolicy.isSensitive(a) || SensitivePathPolicy.isSensitive(b)
                if (skip) out.appendLine("diff --git [sensitive path omitted]")
                else out.appendLine(line)
            } else if (!skip) {
                out.appendLine(line)
            }
        }
        return out.toString().trimEnd()
    }

    private fun validateRepoPath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotBlank() && normalized.length <= 4_000 && '\u0000' !in normalized) { "Invalid repository path" }
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:/").containsMatchIn(normalized)) { "Absolute repository paths are not allowed" }
        PathSecurity.resolveWithin(workDir, normalized)
        return normalized.removePrefix("./")
    }

    private fun validateCommitMessage(message: String): String {
        val safe = message.trim()
        require(safe.isNotBlank() && safe.length <= 500 && '\u0000' !in safe) { "Invalid commit message" }
        return safe
    }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
