package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.Constants
import org.junit.Assert.*
import org.junit.Test

class GitProfessionalWorkflowTest {
    private fun conflicted(root: File) {
        Git.init().setDirectory(root).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Workflow Test")
                setString("user", null, "email", "workflow@example.com"); save()
            }
            root.resolve("Main.txt").writeText("base\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("base").call()
            val originalBranch = git.repository.branch
            git.checkout().setCreateBranch(true).setName("feature").call()
            root.resolve("Main.txt").writeText("feature\n")
            git.add().addFilepattern(".").call()
            val feature = git.commit().setMessage("feature").call()
            git.checkout().setName(originalBranch).call()
            root.resolve("Main.txt").writeText("main\n")
            git.add().addFilepattern(".").call(); git.commit().setMessage("main").call()
            assertEquals(MergeResult.MergeStatus.CONFLICTING, git.merge().include(feature).call().mergeStatus)
        }
    }

    @Test fun stageAllDoesNotResolveMergeConflictsImplicitly() = runBlocking {
        val root = Files.createTempDirectory("git-stage-conflict-").toFile()
        try {
            conflicted(root); root.resolve("Other.txt").writeText("unrelated\n")
            val before = root.resolve("Main.txt").readText()
            runCatching { GitManager(root).stageAll() }
            Git.open(root).use { assertTrue("Stage all must retain the conflict index", "Main.txt" in it.status().call().conflicting) }
            assertEquals(before, root.resolve("Main.txt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun commitAllCannotCommitUnresolvedConflictMarkers() = runBlocking {
        val root = Files.createTempDirectory("git-commit-conflict-").toFile()
        try {
            conflicted(root)
            val head = Git.open(root).use { it.repository.resolve(Constants.HEAD) }
            runCatching { GitManager(root).commitAll("must not commit conflict") }
            Git.open(root).use {
                assertEquals("Unresolved merge must not move HEAD", head, it.repository.resolve(Constants.HEAD))
                assertTrue("Main.txt" in it.status().call().conflicting)
            }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun conflictResolutionRespectsDirtyEditorAndReconcilesWhenClean() = runBlocking {
        val root = Files.createTempDirectory("git-editor-conflict-").toFile()
        try {
            conflicted(root)
            val authority = object : WorkspaceDocumentAuthority {
                var dirty = true
                var reconciled = false
                override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null
                override suspend fun snapshots() = if (!dirty) emptyList() else listOf(
                    WorkspaceDocumentSnapshot("Main.txt", "unsaved", true, WorkspaceDocumentKind.TEXT, true, true, 1, 1, 0, 0, 7)
                )
                override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation {
                    reconciled = true
                    return WorkspacePersistedReconciliation(refreshed = listOf("Main.txt"))
                }
            }
            val git = GitManager(root, authority)
            val before = root.resolve("Main.txt").readText()
            assertTrue(git.resolveConflictBlock("Main.txt", 0, ConflictChoice.CURRENT).startsWith("git error:"))
            assertEquals(before, root.resolve("Main.txt").readText()); assertFalse(authority.reconciled)
            authority.dirty = false
            assertFalse(git.resolveConflictBlock("Main.txt", 0, ConflictChoice.CURRENT).startsWith("git error:"))
            assertTrue(authority.reconciled); assertFalse(MergeConflictResolver.hasMarkers(root.resolve("Main.txt").readText()))
            assertTrue(git.stage("Main.txt", allowConflictResolution = true).contains("Marked resolved"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun firstCommitCanBeUnstagedWithoutDeletingWorkspaceFiles() = runBlocking {
        val root = Files.createTempDirectory("git-first-commit-").toFile()
        try {
            val git = GitManager(root)
            git.init(); git.configureIdentity("Workflow Test", "workflow@example.com")
            root.resolve("Main.txt").writeText("source\n")
            root.resolve("Keep.txt").writeText("keep staged\n")
            git.stageAll()
            assertTrue(git.unstage("Main.txt").contains("Unstaged"))
            val changes = git.changes()
            assertTrue(changes.any { it.path == "Main.txt" && !it.staged && it.kind == GitChangeKind.UNTRACKED })
            assertTrue(changes.any { it.path == "Keep.txt" && it.staged })
            assertEquals("source\n", root.resolve("Main.txt").readText())
            git.stage("Main.txt"); assertTrue(git.commitStaged("first").contains("Committed"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun commitAllIncludesTrackedDeletion() = runBlocking {
        val root = Files.createTempDirectory("git-commit-delete-").toFile()
        try {
            val git = GitManager(root)
            git.init(); git.configureIdentity("Workflow Test", "workflow@example.com")
            root.resolve("Main.txt").writeText("source\n")
            git.stageAll(); git.commitStaged("first")
            root.resolve("Main.txt").delete()
            assertTrue(git.commitAll("delete source").contains("Committed"))
            assertTrue("Commit all must include removal", git.changes().isEmpty())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun stagingWholeRepositoryCannotBypassConflictReview() = runBlocking {
        val root = Files.createTempDirectory("git-root-conflict-").toFile()
        try {
            conflicted(root)
            val git = GitManager(root)
            assertTrue(runCatching { git.stage(".") }.isFailure)
            assertTrue(runCatching { git.stage(".", allowConflictResolution = true) }.isFailure)
            Git.open(root).use { assertTrue("Main.txt" in it.status().call().conflicting) }
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun stagingDirectoryCannotIncludePrivateFilesSilently() = runBlocking {
        val root = Files.createTempDirectory("git-folder-private-").toFile()
        try {
            val git = GitManager(root); git.init()

            Git.open(root).use { repository ->
                val excludes = root.resolve(".git/info/workflow-test-excludes").apply { writeText("") }
                repository.repository.config.apply {
                    setString("core", null, "excludesfile", excludes.absolutePath)
                    save()
                }
            }
            root.resolve("src").mkdir()
            root.resolve("src/Main.kt").writeText("source\n")
            root.resolve("src/.env").writeText("FIXTURE=value\n")
            Git.open(root).use { repository ->
                val status = repository.status().call()
                assertTrue("Fixture private file must be untracked", "src/.env" in status.untracked)
                assertFalse("Fixture private file must not inherit an ignore", "src/.env" in status.ignoredNotInIndex)
            }
            assertTrue(runCatching { git.stage("src") }.isFailure)
            Git.open(root).use { assertTrue(it.status().call().added.isEmpty()) }
            assertTrue(git.stage("src/Main.kt").contains("Staged"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun initialCommitDirectoryUnstagePreservesOtherStagedFiles() = runBlocking {
        val root = Files.createTempDirectory("git-folder-unstage-").toFile()
        try {
            val git = GitManager(root); git.init()
            root.resolve("src").mkdir()
            root.resolve("src/Main.kt").writeText("source\n")
            root.resolve("Keep.txt").writeText("keep\n")
            git.stageAll(); git.unstage("src")
            assertTrue(git.changes().any { it.path == "src/Main.kt" && !it.staged })
            assertTrue(git.changes().any { it.path == "Keep.txt" && it.staged })
            assertEquals("source\n", root.resolve("src/Main.kt").readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
