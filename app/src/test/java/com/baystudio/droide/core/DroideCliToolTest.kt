package com.baystudio.droide.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DroideCliToolTest {
    @get:org.junit.Rule
    val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Authority(private val cancelInstall: Boolean = true, private val reportsInstalled: Boolean = true) : UnifiedPackageAuthority {
        var activation: Triple<String, String, String?>? = null
        var repaired: Pair<String, String?>? = null
        var installed: Pair<String, String>? = null
        var uninstalled: Pair<String, String>? = null
        override val transactionState = MutableStateFlow(UnifiedPackageTransactionState(false, null, null))
        override suspend fun activate(familyId: String, version: String, workspaceId: String?): UnifiedPackageTransactionResult {
            activation = Triple(familyId, version, workspaceId)
            return UnifiedPackageTransactionResult(true, "activated")
        }
        override suspend fun install(familyId: String, version: String): UnifiedPackageTransactionResult {
            if (cancelInstall) throw CancellationException("cancelled")
            installed = familyId to version
            return UnifiedPackageTransactionResult(true, "installed")
        }
        override suspend fun search(query: String) = listOf(
            UnifiedPackageSearchResult("plugin.opencode", "OpenCode", "plugin", listOf("2.0.3"), null),
            UnifiedPackageSearchResult("runtime.python", "Python", "runtime", listOf("3.13"), "3.13"),
            UnifiedPackageSearchResult("runtime.deps.internal", "Internal", "internal", listOf("1"), "1"),
            UnifiedPackageSearchResult("toolchain.go", "Go", "toolchain", listOf("1.26"), null),
        ).filter { query.isBlank() || it.familyId.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
        override suspend fun info(familyId: String, version: String): UnifiedPackageInfo? = null
        override suspend fun upgrade(familyId: String) = error("unexpected upgrade")
        override suspend fun downgrade(familyId: String, version: String) = error("unexpected downgrade")
        override suspend fun health(familyId: String) = error("unexpected health")
        override suspend fun repair(familyId: String): UnifiedPackageTransactionResult {
            repaired = familyId to null
            return UnifiedPackageTransactionResult(true, "repaired active")
        }
        override suspend fun repairVersion(familyId: String, version: String): UnifiedPackageTransactionResult {
            repaired = familyId to version
            return UnifiedPackageTransactionResult(true, "repaired exact")
        }
        override suspend fun uninstall(familyId: String, version: String): UnifiedPackageTransactionResult {
            uninstalled = familyId to version
            return UnifiedPackageTransactionResult(true, "uninstalled")
        }
        override suspend fun list() = if (!reportsInstalled) emptyList() else listOf(
            UnifiedPackageInfo("plugin.opencode", "2.0.3", "active", 0, emptyList()),
            UnifiedPackageInfo("runtime.python", "3.13", "active", 0, emptyList()),
            UnifiedPackageInfo("runtime.deps.internal", "1", "active", 0, emptyList()),
            UnifiedPackageInfo("toolchain.go", "1.26", "active", 0, emptyList()),
        )
    }

    @Test fun rootAndNamespaceHelpSucceedWithoutCallingAnInstaller() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val cli = DroideCliTool(authority)
        for (args in listOf(emptyList(), listOf("--help"), listOf("-h"), listOf("help"),
            listOf("pkg", "--help"), listOf("runtime", "-h"), listOf("plugin", "help"), listOf("process", "--help"))) {
            val result = cli.executeResult(args)
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("Usage:"))
            assertEquals("", result.stderr)
        }
        assertNull(authority.installed)
        assertNull(authority.repaired)
        assertNull(authority.activation)
    }

    @Test fun unknownCategoryAndUnsupportedRunRemainFailuresWithAccurateGuidance() = runBlocking {
        val cli = DroideCliTool(Authority(cancelInstall = false))
        assertEquals(2, cli.executeResult(listOf("missing", "--help")).exitCode)
        val run = cli.executeResult(listOf("run", "hello.py"))
        assertEquals(2, run.exitCode)
        assertTrue(run.stderr.contains("python3 hello.py"))
        assertTrue(run.stderr.contains("Check/install the runtime"))
        assertFalse(AgentSystemPrompt.TEXT.contains("droide run <file>"))
    }

    @Test fun transactionStatusIsReadOnlyAndNeverClaimsPackageCompletion() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val cli = DroideCliTool(authority)
        authority.transactionState.value = UnifiedPackageTransactionState(true, "install-python-fixture", null,
            listOf("Downloaded 12 MB"), stage = "downloading", message = "Fetching archive")
        val status = cli.executeResult(listOf("pkg", "status"))
        assertEquals(0, status.exitCode)
        assertTrue(status.stdout.contains("stage=downloading"))
        assertTrue(status.stdout.contains("Downloaded 12 MB"))
        assertTrue(status.stdout.contains("not proof"))
        assertNull(authority.installed)
        assertNull(authority.repaired)
        authority.transactionState.value = UnifiedPackageTransactionState(false, null, null)
        assertTrue(cli.executeResult(listOf("pkg", "status")).stdout.contains("No managed package transaction"))
        assertEquals(2, cli.executeResult(listOf("pkg", "unlock")).exitCode)
    }

    @Test fun agentCatalogFindsPythonWithoutAnyInstalledReceipts() = runBlocking {
        val result = AgentToolchainCatalog.query(Authority(reportsInstalled = false), " Python ")
        assertTrue(result.contains("runtime.python · Python"))
        assertTrue(result.contains("versions=3.13 · installed=none"))
        assertFalse(result.contains("plugin.opencode"))
        assertTrue(result.contains("does not prove a runtime is installed or installable"))
    }

    @Test fun agentCatalogDistinguishesInstalledReceiptFromCatalogMetadata() = runBlocking {
        val result = AgentToolchainCatalog.query(Authority(), null)
        assertTrue(result.contains("plugin.opencode · OpenCode · versions=2.0.3 · installed=2.0.3 (active)"))
        assertTrue(AgentToolchainCatalog.query(Authority(), "absent").contains("No matching catalog entries"))
    }

    @Test fun invalidWorkspaceOptionNeverMutatesGlobalSelection() = runBlocking {
        val authority = Authority()
        val result = DroideCliTool(authority).execute(listOf("pkg", "use", "python@3.13", "--workspce", "project"))
        assertTrue(result.startsWith("Usage:"))
        assertNull(authority.activation)
    }

    @Test fun validWorkspaceOptionIsForwardedUnchanged() = runBlocking {
        val authority = Authority()
        assertEquals("activated", DroideCliTool(authority).execute(listOf("pkg", "use", "python@3.13", "--workspace", "project")))
        assertEquals(Triple("python", "3.13", "project"), authority.activation)
    }

    @Test fun cancellationPropagatesFromPackageTransaction() {
        assertThrows(CancellationException::class.java) {
            runBlocking { DroideCliTool(Authority()).execute(listOf("pkg", "install", "python@3.13")) }
        }
    }

    @Test fun repairWithVersionPreservesTheRequestedVersion() = runBlocking {
        val authority = Authority()
        assertEquals("repaired exact", DroideCliTool(authority).execute(listOf("pkg", "repair", "runtime.python@3.11")))
        assertEquals("runtime.python" to "3.11", authority.repaired)
    }

    @Test fun repairWithoutVersionUsesActiveSelection() = runBlocking {
        val authority = Authority()
        assertEquals("repaired active", DroideCliTool(authority).execute(listOf("pkg", "repair", "runtime.python")))
        assertEquals("runtime.python" to null, authority.repaired)
    }

    @Test fun malformedRepairArgumentsNeverReachTheInstaller() = runBlocking {
        val authority = Authority()
        for (args in listOf(
            listOf("pkg", "repair", "runtime.python@3.11", "ignored"),
            listOf("pkg", "repair", "runtime.python@"),
            listOf("pkg", "repair", "runtime.python@3.11@3.12"),
        )) assertTrue(DroideCliTool(authority).execute(args).startsWith("Error:"))
        assertNull(authority.repaired)
    }

    @Test fun downgradeCannotResolveAMovingLatestTarget() = runBlocking {
        for (target in listOf("runtime.python", "runtime.python@latest", "runtime.python@Latest official")) {
            assertTrue(DroideCliTool(Authority()).execute(listOf("pkg", "downgrade", target)).startsWith("Error:"))
        }
    }
    @Test fun structuredFailureUsesNonZeroExitAndStderr() = runBlocking {
        val result = DroideCliTool(Authority()).executeResult(listOf("pkg", "info", "missing@1.0"))
        assertEquals(DroideCliExitCode.NOT_FOUND, result.exitCode)
        assertTrue(result.stdout.isBlank())
        assertTrue(result.stderr.contains("Package not found"))
    }

    @Test fun workspaceBoundEndpointRejectsCrossWorkspaceMutation() = runBlocking {
        val authority = Authority()
        val result = DroideCliTool(authority, "workspace-a").executeResult(
            listOf("pkg", "use", "python@3.13", "--workspace", "workspace-b"),
        )
        assertEquals(DroideCliExitCode.USAGE, result.exitCode)
        assertNull(authority.activation)
    }

    @Test fun workspaceBoundEndpointAcceptsItsOwnExplicitWorkspace() = runBlocking {
        val authority = Authority()
        val result = DroideCliTool(authority, "workspace-a").executeResult(
            listOf("pkg", "use", "python@3.13", "--workspace", "workspace-a"),
        )
        assertEquals(DroideCliExitCode.OK, result.exitCode)
        assertEquals(Triple("python", "3.13", "workspace-a"), authority.activation)
    }

    @Test fun workspaceBoundEndpointDefaultsUseToItsOwnWorkspace() = runBlocking {
        val authority = Authority()
        val result = DroideCliTool(authority, "workspace-a").executeResult(
            listOf("pkg", "use", "python@3.13"),
        )
        assertEquals(DroideCliExitCode.OK, result.exitCode)
        assertEquals(Triple("python", "3.13", "workspace-a"), authority.activation)
    }

    @Test fun pluginNamespaceUsesUnifiedAuthorityAndAddsFamilyPrefix() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val result = DroideCliTool(authority).executeResult(listOf("plugin", "install", "opencode@2.0.3"))
        assertEquals(DroideCliExitCode.OK, result.exitCode)
        assertEquals("plugin.opencode" to "2.0.3", authority.installed)
    }

    @Test fun runtimeNamespaceUsesUnifiedAuthorityAndAddsFamilyPrefix() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val result = DroideCliTool(authority).executeResult(listOf("runtime", "install", "python@3.13"))
        assertEquals(DroideCliExitCode.OK, result.exitCode)
        assertEquals("runtime.python" to "3.13", authority.installed)
    }

    @Test fun scopedNamespacesRejectCrossDomainMutation() = runBlocking {
        val pluginAuthority = Authority(cancelInstall = false)
        val plugin = DroideCliTool(pluginAuthority).executeResult(listOf("plugin", "install", "runtime.python@3.13"))
        assertEquals(DroideCliExitCode.USAGE, plugin.exitCode)
        assertNull(pluginAuthority.installed)

        val runtimeAuthority = Authority(cancelInstall = false)
        val runtime = DroideCliTool(runtimeAuthority).executeResult(listOf("runtime", "uninstall", "plugin.opencode@2.0.3"))
        assertEquals(DroideCliExitCode.USAGE, runtime.exitCode)
        assertNull(runtimeAuthority.uninstalled)
    }

    @Test fun scopedSearchAndListHideOtherDomainsAndInternalRuntimeLayers() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val pluginSearch = DroideCliTool(authority).executeResult(listOf("plugin", "search", ""))
        assertTrue(pluginSearch.stdout.contains("plugin.opencode"))
        assertFalse(pluginSearch.stdout.contains("runtime.python"))

        val runtimeList = DroideCliTool(authority).executeResult(listOf("runtime", "list"))
        assertTrue(runtimeList.stdout.contains("runtime.python"))
        assertFalse(runtimeList.stdout.contains("runtime.deps.internal"))
        assertFalse(runtimeList.stdout.contains("toolchain.go"))
    }

    private fun diagnosticTerminal() = object : ITerminalSession {
        override val output = MutableStateFlow("")
        override fun start() = Unit
        override fun send(cmd: String) = Unit
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = awaitCancellation()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = awaitCancellation()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }

    @Test fun processCliStopsOnlyWorkspaceOwnedJobIdsAndDoesNotRunPackageMutation() = runBlocking {
        val authority = Authority(cancelInstall = false)
        val manager = BackgroundCommandManager(diagnosticTerminal())
        val root = java.nio.file.Files.createTempDirectory("droide-cli-owned-process").toFile()
        val workspace = "cli-process-${java.util.UUID.randomUUID()}"
        val binding = OwnedWorkspaceDiagnosticsRegistry.bind(workspace, OwnedProcessDiagnostics(manager), root)
        try {
            val job = manager.start("AUTH_TOKEN=private-secret node server.js")
            val cli = DroideCliTool(authority, workspace)
            val list = cli.executeResult(listOf("process", "list"))
            assertEquals(DroideCliExitCode.OK, list.exitCode)
            assertTrue(list.stdout.contains(job.id))
            assertFalse(list.stdout.contains("private-secret"))
            assertEquals(DroideCliExitCode.USAGE, cli.executeResult(listOf("process", "stop", "3000")).exitCode)
            assertEquals(DroideCliExitCode.NOT_FOUND, cli.executeResult(listOf("process", "stop", "bg-unknown-1")).exitCode)
            val stopped = cli.executeResult(listOf("process", "stop", job.id))
            assertEquals(DroideCliExitCode.OK, stopped.exitCode)
            assertTrue(stopped.stdout.contains("state=killed"))
            assertNull(authority.installed)
            assertNull(authority.uninstalled)
        } finally { OwnedWorkspaceDiagnosticsRegistry.unbind(binding); manager.close(); root.deleteRecursively() }
    }

    @Test fun processCliFailsClosedAcrossWorkspaceBoundariesAndInvalidArguments() = runBlocking {
        val manager = BackgroundCommandManager(diagnosticTerminal())
        val root = java.nio.file.Files.createTempDirectory("droide-cli-process-scope").toFile()
        val workspace = "cli-scope-${java.util.UUID.randomUUID()}"
        val binding = OwnedWorkspaceDiagnosticsRegistry.bind(workspace, OwnedProcessDiagnostics(manager), root)
        try {
            val job = manager.start("known owned fixture")
            val wrong = DroideCliTool(Authority(), "$workspace-other")
            assertEquals(DroideCliExitCode.UNAVAILABLE, wrong.executeResult(listOf("process", "stop", job.id)).exitCode)
            assertEquals(BackgroundCommandManager.State.RUNNING, manager.snapshot(job.id)!!.state)
            val cli = DroideCliTool(Authority(), workspace)
            for (args in listOf(
                listOf("process", "port", "0"), listOf("process", "port", "65536"),
                listOf("process", "port", "3000;kill 1"), listOf("process", "list", "extra"),
                listOf("process", "kill-port", "3000"),
            )) assertEquals(DroideCliExitCode.USAGE, cli.executeResult(args).exitCode)
        } finally { OwnedWorkspaceDiagnosticsRegistry.unbind(binding); manager.close(); root.deleteRecursively() }
    }

    @Test fun filePermissionCliInspectsExactWorkspaceFileWithoutChmodAndRejectsEscape() = runBlocking {
        val manager = BackgroundCommandManager(diagnosticTerminal())
        val root = java.nio.file.Files.createTempDirectory("droide-cli-file-permission").toFile()
        val script = java.io.File(root, "install.sh").apply { writeText("#!/bin/sh\necho fixture\n"); setExecutable(false, false) }
        val workspace = "cli-file-${java.util.UUID.randomUUID()}"
        val binding = OwnedWorkspaceDiagnosticsRegistry.bind(workspace, OwnedProcessDiagnostics(manager), root)
        try {
            val cli = DroideCliTool(Authority(), workspace)
            val result = cli.executeResult(listOf("file", "permissions", "install.sh"))
            assertEquals(DroideCliExitCode.OK, result.exitCode)
            assertTrue(result.stdout.contains("executable=false"))
            assertTrue(result.stdout.contains("chmod u+x -- './install.sh'"))
            assertFalse(script.canExecute())
            assertEquals(DroideCliExitCode.USAGE, cli.executeResult(listOf("file", "permissions", "../install.sh")).exitCode)
            assertEquals(DroideCliExitCode.NOT_FOUND, cli.executeResult(listOf("file", "permissions", "absent.sh")).exitCode)
        } finally { OwnedWorkspaceDiagnosticsRegistry.unbind(binding); manager.close(); root.deleteRecursively() }
    }

}
