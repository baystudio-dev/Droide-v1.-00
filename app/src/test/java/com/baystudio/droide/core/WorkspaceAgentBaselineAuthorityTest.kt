package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceAgentBaselineAuthorityTest {
    private fun fixture(trusted: Boolean, block: suspend (WorkspaceAgentPluginHost, File, File, () -> Int) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("agent-host-baseline-authority-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val source = File(root, "source").apply { mkdir() }
        val working = File(root, "working").apply { mkdir() }
        val forgedBase = MessageDigest.getInstance("SHA-256").digest("user version".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val guestText = WorkspaceSyncManifest.serialize(mapOf("file" to WorkspaceSyncManifest.Entry(forgedBase, 420)))
        val guest = File(working, ".droide-sync-manifest.tsv").apply { writeText(guestText) }
        var shellCalls = 0
        val process = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String): String? = error("Review must not discover or launch a model")
            override suspend fun pathMapper() = WorkspacePathMapper(source, working.absolutePath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
                error("Review must not launch a model")
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) { require(File(path).toPath().startsWith(working.toPath())) }
            override suspend fun refresh() = Unit
            override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry>? = if (trusted) emptyMap() else null
            override suspend fun snapshotEntries() = AgentWorkspaceArtifacts.snapshotEntries(working)
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
                shellCalls++
                return BridgeShellResult(0, guest.readText(), "")
            }
            override suspend fun pull(path: String, output: File) { AgentWorkspaceArtifacts.pull(working, File(path), output) }
            override suspend fun push(input: File, path: String) = error("No outbound projection in review fixture")
        }
        val host = WorkspaceAgentPluginHost(source, File(root, "cache"), FileRepository(source), process,
            ApprovalManager(), PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))), scope, io)
        try {
            withTimeout(10_000) { block(host, source, working) { shellCalls } }
            assertEquals(guestText, guest.readText())
        } finally {
            host.close(); scope.coroutineContext.job.cancelAndJoin(); PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun missingHostAuthorityCannotAdoptAForeignManifestOrPublishCliFiles() = fixture(false) { host, source, working, calls ->
        File(source, "file").writeText("user version")
        File(working, "file").writeText("CLI version")
        File(working, "new-file").writeText("unpublished CLI output")
        val failure = runCatching { host.reviewWorkspaceChanges("opencode") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Authoritative host workspace baseline"))
        assertEquals(0, calls())
        assertEquals("user version", File(source, "file").readText())
        assertEquals("CLI version", File(working, "file").readText())
        assertFalse(File(source, "new-file").exists())
        assertEquals("unpublished CLI output", File(working, "new-file").readText())
        assertEquals(WorkspaceAgentSyncPhase.INTERRUPTED, host.workspaceSyncState.value.phase)
    }

    @Test fun aTrustedEmptyHostBaselineCanReviewNewFilesWithoutReadingGuestMetadata() = fixture(true) { host, source, working, calls ->
        File(working, "new-file").writeText("reviewed CLI output")
        val reviewed = host.reviewWorkspaceChanges("opencode")
        assertEquals(listOf("new-file"), reviewed.changedFiles)
        assertTrue(reviewed.pendingFiles.isEmpty())
        assertEquals("reviewed CLI output", File(source, "new-file").readText())
        assertEquals("reviewed CLI output", File(working, "new-file").readText())
        assertEquals(0, calls())
    }
}
