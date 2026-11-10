package com.baystudio.droide.core

import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class WorkspaceRetainedConflictReviewTest {
    private class Fixture(val root: File, val scope: CoroutineScope) {
        val source = File(root, "source").apply { mkdir() }
        val working = File(root, "working")
        val metadata = File(root, ".working-sync-authority")
        val store get() = MirrorMetadataAuthority(metadata, source, working)
        lateinit var mirror: LocalAgentWorkspaceMirror
        lateinit var host: WorkspaceAgentPluginHost
        var conflictReader: (() -> Set<String>)? = null
        var conflictReads = 0

        fun sourceFile(path: String, text: String) = File(source, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun cliFile(path: String, text: String) = File(working, path).apply { parentFile!!.mkdirs(); writeText(text) }

        suspend fun prepare() {
            mirror = LocalAgentWorkspaceMirror.prepareRetained(source, working)
            val process = object : StdioProcessHost {
                override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
                override suspend fun resolveExecutable(command: String): String? = error("Review must not launch an agent")
                override suspend fun pathMapper() = WorkspacePathMapper(source, working.absolutePath)
                override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
                    error("Review must not launch an agent")
            }
            val io = Proxy.newProxyInstance(AgentWorkspaceIo::class.java.classLoader, arrayOf(AgentWorkspaceIo::class.java)) { _, method, args ->
                when (method.name) {
                    "getChecksumCommand" -> "sha256sum"
                    "requirePath" -> {
                        require(File(args[0] as String).toPath().startsWith(working.toPath())); Unit
                    }
                    "refresh" -> runBlocking { mirror.refresh() }
                    "baselineEntries" -> runBlocking { mirror.baseline() }
                    "baseline" -> runBlocking { mirror.baseline().mapValues { it.value.sha256 } }
                    "snapshotEntries" -> runBlocking { mirror.inventoryEntries() }
                    "snapshot" -> runBlocking { mirror.inventory() }
                    "synchronizationNotice" -> mirror.metadataNotice
                    "retainedConflictPaths" -> { conflictReads++; conflictReader?.invoke() ?: store.load().conflicts }
                    "reviewText" -> null
                    "pull" -> runBlocking { AgentWorkspaceArtifacts.pull(working, File(args[0] as String), args[1] as File) }
                    "requireExecutionAbsent" -> {
                        check(!Files.exists(File(args[0] as String).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)); Unit
                    }
                    "shellBounded", "push" -> error("Review must use the host inventory and must not project over CLI bytes")
                    "toString" -> "RetainedWorkspaceReviewFixture"
                    else -> error("Unexpected workspace call: ${method.name}")
                }
            } as AgentWorkspaceIo
            host = WorkspaceAgentPluginHost(source, File(root, "cache"), FileRepository(source), process, ApprovalManager(),
                PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), scope, io)
        }

        suspend fun retainLateWrite(): MirrorMetadataAuthority.Intent {
            sourceFile("file", "base")
            mirror.refresh()
            RandomAccessFile(File(working, "file"), "rw").use { writer ->
                sourceFile("file", "host")
                mirror.refresh()
                writer.setLength(0)
                writer.write("unpublished CLI".toByteArray())
                writer.fd.sync()
            }
            mirror.refresh()
            return store.operations().single { it.path == "file" && it.before.type == MirrorMetadataAuthority.NodeType.FILE }
        }

        suspend fun review() = host.reviewWorkspaceChanges("opencode")
    }

    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-retained-review-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fixture = Fixture(root, scope)
        try {
            fixture.prepare()
            withTimeout(30_000) { fixture.block() }
        } finally {
            fixture.host.close()
            scope.coroutineContext.job.cancelAndJoin()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun liveHashAgreementDoesNotHideAChangedRetainedInode() = fixture {
        val record = retainLateWrite()
        assertEquals(mirror.baseline(), mirror.inventoryEntries())
        val result = review()
        assertTrue(result.changedFiles.isEmpty())
        assertEquals(listOf("file"), result.pendingFiles)
        assertEquals(WorkspaceAgentSyncPhase.PENDING, host.workspaceSyncState.value.phase)
        assertEquals(listOf("file"), host.workspaceSyncState.value.pendingPaths)
        assertEquals("unpublished CLI", File(metadata, "retained/${record.retainedName}").readText())
        assertEquals("base", File(metadata, "retained/${record.stableName}").readText())
        assertEquals("host", File(source, "file").readText())
        assertEquals("host", File(working, "file").readText())
    }

    @Test fun unrelatedAcceptedFileDoesNotClearRetainedConflict() = fixture {
        val record = retainLateWrite()
        cliFile("new", "CLI addition")
        val result = review()
        assertEquals(listOf("new"), result.changedFiles)
        assertEquals(listOf("file"), result.pendingFiles)
        assertEquals("CLI addition", File(source, "new").readText())
        assertEquals("unpublished CLI", File(metadata, "retained/${record.retainedName}").readText())
        assertEquals(setOf("file"), store.load().conflicts)
        assertEquals(WorkspaceAgentSyncPhase.PENDING, host.workspaceSyncState.value.phase)
    }

    @Test fun retainedConflictBlocksPublicationOfItsLivePath() = fixture {
        retainLateWrite()
        cliFile("file", "second CLI branch")
        val result = review()
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("file" in result.pendingFiles)
        assertEquals("host", File(source, "file").readText())
        assertEquals("second CLI branch", File(working, "file").readText())
    }

    @Test fun retainedDirectoryConflictProtectsItsDescendants() = fixture {
        sourceFile("dir/child", "base")
        mirror.refresh()
        cliFile("dir/child", "CLI")
        mirror.preserveAttachmentConflict("dir")
        val result = review()
        assertTrue(result.changedFiles.isEmpty())
        assertTrue("dir" in result.pendingFiles)
        assertEquals("base", File(source, "dir/child").readText())
        assertEquals("CLI", File(working, "dir/child").readText())
    }

    @Test fun newConflictAfterCandidateCollectionStopsPublication() = fixture {
        sourceFile("file", "base")
        mirror.refresh()
        cliFile("file", "CLI")
        conflictReader = { if (conflictReads == 1) emptySet() else setOf("file") }
        val result = review()
        assertTrue(result.changedFiles.isEmpty())
        assertEquals(listOf("file"), result.pendingFiles)
        assertEquals("base", File(source, "file").readText())
        assertEquals("CLI", File(working, "file").readText())
    }

    @Test fun finalRefreshConflictPreventsASyncedStatusWithoutLiveChanges() = fixture {
        conflictReader = { if (conflictReads == 1) emptySet() else setOf("retired") }
        val result = review()
        assertEquals(listOf("retired"), result.pendingFiles)
        assertEquals(WorkspaceAgentSyncPhase.PENDING, host.workspaceSyncState.value.phase)
        assertFalse(File(source, "retired").exists())
    }

    @Test fun unreadableRetainedStateStopsBeforeAnyPublication() = fixture {
        cliFile("new", "CLI")
        conflictReader = { error("Retained state unavailable") }
        val failure = runCatching { review() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is IllegalStateException)
        assertFalse(File(source, "new").exists())
        assertEquals("CLI", File(working, "new").readText())
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, host.workspaceSyncState.value.phase)
    }

    @Test fun malformedRetainedPathsAreNotSilentlyIgnored() = fixture {
        cliFile("new", "CLI")
        conflictReader = { setOf("../outside") }
        assertTrue(runCatching { review() }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(source, "new").exists())
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, host.workspaceSyncState.value.phase)
    }

    @Test fun excessiveRetainedStateFailsClosed() = fixture {
        cliFile("new", "CLI")
        conflictReader = { (0..128).mapTo(linkedSetOf()) { "file-$it" } }
        assertTrue(runCatching { review() }.exceptionOrNull() is IllegalArgumentException)
        assertFalse(File(source, "new").exists())
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, host.workspaceSyncState.value.phase)
    }

    @Test fun repeatedReviewDoesNotRewriteMetadataOrResolveRetainedWork() = fixture {
        retainLateWrite()
        val database = File(metadata, "state.sqlite")
        val before = database.readBytes()
        val stamp = Files.getLastModifiedTime(database.toPath())
        repeat(2) { assertEquals(listOf("file"), review().pendingFiles) }
        assertArrayEquals(before, database.readBytes())
        assertEquals(stamp, Files.getLastModifiedTime(database.toPath()))
        assertEquals(setOf("file"), store.load().conflicts)
    }

    @Test fun healthyUnchangedWorkspaceStillReportsSynced() = fixture {
        sourceFile("file", "base")
        mirror.refresh()
        val result = review()
        assertTrue(result.changedFiles.isEmpty())
        assertTrue(result.pendingFiles.isEmpty())
        assertEquals(WorkspaceAgentSyncPhase.SYNCED, host.workspaceSyncState.value.phase)
    }
}
