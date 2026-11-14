package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface LocalAgentExecutionAdmission {
    suspend fun requireReadyLocalExecution()
}

internal interface AgentWorkspaceIo {
    val checksumCommand: String
    fun requirePath(path: String)
    suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult
    suspend fun refresh()
    /** Optional host-side inventory avoids launching Linux just to inspect local files. */
    suspend fun snapshot(): Map<String, String>? = null
    /** A host-owned baseline cannot be replaced by the external CLI's guest manifest. */
    suspend fun baseline(): Map<String, String>? = null
    suspend fun snapshotEntries(): Map<String, WorkspaceSyncManifest.Entry>? =
        snapshot()?.mapValues { WorkspaceSyncManifest.Entry(it.value, null) }
    suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry>? =
        baseline()?.mapValues { WorkspaceSyncManifest.Entry(it.value, null) }
    fun synchronizationNotice(): String? = null
    suspend fun retainedConflictPaths(): Set<String> = emptySet()
    suspend fun reviewText(path: String, expectedHash: String): String? = null
    suspend fun requireExecutionAbsent(path: String) {
        requirePath(path)
        val result = shellBounded("test ! -e ${DeviceBridgeManager.shellQuote(path)} && test ! -L ${DeviceBridgeManager.shellQuote(path)}", 1_024)
        check(result.exitCode == 0) { "CLI recreated this path; deletion was not applied" }
    }
    /** Optional local execution view. Null preserves existing remote bridge semantics. */
    suspend fun readExecutionText(path: String): String? = null
    suspend fun prepareTextProjection(path: String, canonical: StreamFileVersion): AcpTextProjection? = null
    suspend fun pull(path: String, output: File)
    /** Typed media is streamed from the execution workspace, never substituted with source bytes. */
    suspend fun openMedia(path: String): java.io.InputStream = error("This workspace cannot stream media outputs")
    suspend fun push(input: File, path: String)
    suspend fun requireNewPath(path: String): Unit = error("This workspace cannot publish a new attachment")
    /** Project asset publication must not replace a CLI-only file at the chosen destination. */
    suspend fun pushNew(input: File, path: String): Unit = error("This workspace cannot publish a new attachment")
}

internal class BridgeAgentWorkspaceIo(
    private val bridge: DeviceBridgeManager,
    private val resync: suspend () -> Unit = {},
) : AgentWorkspaceIo {
    override val checksumCommand = "toybox sha256sum"
    override fun requirePath(path: String) = DeviceBridgeManager.requireSafeRemotePath(path)
    override suspend fun shellBounded(command: String, maxOutputBytes: Int) = bridge.shellBounded(command, maxOutputBytes)
    override suspend fun refresh() = resync()
    override suspend fun pull(path: String, output: File) { bridge.pull(path, output) }
    override suspend fun push(input: File, path: String) {
        requirePath(path)
        val result = bridge.shell("mkdir -p ${DeviceBridgeManager.shellQuote(path.substringBeforeLast('/'))}")
        check(result.exitCode == 0) { result.combined }
        bridge.push(input, path, mode = if (input.canExecute()) 493 else 420)
    }
}

internal class LocalAgentWorkspaceProcessHost private constructor(
    private val source: File,
    private val cacheDir: File,
    private val scope: CoroutineScope,
    private val persistentWorkspaceStore: File?,
    private val retainedOnDemand: Boolean,
) : StdioProcessHost {
    constructor(source: File, cacheDir: File, scope: CoroutineScope, persistentWorkspaceStore: File? = null) :
        this(source, cacheDir, scope, persistentWorkspaceStore, retainedOnDemand = false)

    private val sourceIdentity = WorkspaceDirectoryIdentity(source)
    private val sourceProcessHost = LocalLinuxProcessHost(source, scope)
    private val identity = java.security.MessageDigest.getInstance("SHA-256")
        .digest(source.canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }
    private val storage = AgentWorkspaceStorage(source, File(cacheDir, "agent-workspaces/$identity"),
        File(persistentWorkspaceStore ?: File(cacheDir, "agent-workspaces"), identity))
    internal val retainedRecovery by lazy { MirrorRetainedRecovery(source, storage.directory) }
    @Volatile private var mirror = LocalAgentWorkspaceMirror(source, storage.directory)
    private val preparationMutex = Mutex()
    private var retainedPrepared = false
    private val delegate get() = LocalLinuxProcessHost(mirror.directory, scope, toolchainWorkspaceRoot = source)
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    @Volatile private var hasCompletedRefresh = false
    val workspaceIo: AgentWorkspaceIo = object : AgentWorkspaceIo, LocalAgentExecutionAdmission {
        override val checksumCommand = "sha256sum"
        override fun requirePath(path: String) { executionRelativePath(path) }
        override suspend fun refresh() {
            prepareWorkspace()
            mirror.refresh()
            hasCompletedRefresh = true
        }
        override suspend fun requireReadyLocalExecution() {
            check(hasCompletedRefresh) { "CLI working copy has not completed initial synchronization; execution was not started" }
            mirror.withExecutionBaseline { Unit }
        }
        override fun synchronizationNotice(): String? = mirror.synchronizationNotice()
        override suspend fun retainedConflictPaths(): Set<String> = mirror.retainedConflictPaths()
        override suspend fun reviewText(path: String, expectedHash: String): String? {
            return mirror.withExecutionRead {
                requirePath(path)
                WorkspaceReviewPreview.text(mirror.directory, executionRelativePath(path), expectedHash)
            }
        }
        override suspend fun requireExecutionAbsent(path: String) = mirror.withExecutionRead {
            requirePath(path)
            check(!Files.exists(executionFile(path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "CLI recreated this path; deletion was not applied"
            }
        }
        override suspend fun snapshot(): Map<String, String> { prepareWorkspace(); return mirror.inventory() }
        override suspend fun baseline(): Map<String, String> { prepareWorkspace(); return mirror.baseline().mapValues { it.value.sha256 } }
        override suspend fun snapshotEntries(): Map<String, WorkspaceSyncManifest.Entry> { prepareWorkspace(); return mirror.inventoryEntries() }
        override suspend fun baselineEntries(): Map<String, WorkspaceSyncManifest.Entry> { prepareWorkspace(); return mirror.baseline() }
        override suspend fun readExecutionText(path: String): String? {
            prepareWorkspace()
            return mirror.withExecutionRead {
                requirePath(path)
                val relative = executionRelativePath(path)
                // Only absent excluded files may fall back to the explicitly approved canonical route.
                if (!AgentWorkspacePathPolicy.visible(relative) &&
                    !Files.exists(executionFile(path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return@withExecutionRead null
                AcpWorkspaceText.read(FileRepository(mirror.directory, createRoot = false), relative)
            }
        }
        override suspend fun prepareTextProjection(path: String, canonical: StreamFileVersion): AcpTextProjection {
            prepareWorkspace()
            return mirror.withExecutionBaseline { baseline ->
                requirePath(path)
                val relative = executionRelativePath(path)
                val targetFiles = executionWriteFiles()
                val expected = AcpWorkspaceText.version(targetFiles, relative)
                if (AgentWorkspacePathPolicy.visible(relative)) {
                    val base = baseline[relative]?.sha256
                    val localHash = canonical.sha256.takeIf { canonical.existed }
                    val executionHash = expected.sha256.takeIf { expected.existed }
                    check(localHash == base || localHash == executionHash) {
                        "Project file changed outside this CLI execution view: $relative; both versions were preserved"
                    }
                }
                object : AcpTextProjection {
                    override suspend fun requireUnchanged() = mirror.withExecutionRead {
                        expected.requireMatches(mirror.directory, relative)
                    }
                    override suspend fun publish(content: String, requireCurrent: () -> Unit) {
                        requireCurrent()
                        val bytes = content.toByteArray(Charsets.UTF_8)
                        targetFiles.writeStream(relative, bytes.inputStream(), expectedBytes = bytes.size.toLong(),
                            requireAbsent = !expected.existed, expectedVersion = expected,
                            beforePublication = { requireCurrent() },
                            publicationObserver = object : StreamPublicationObserver {
                                override fun beforePublish(preparation: StreamPublicationPreparation) = requireCurrent()
                                override fun onPublished(receipt: StreamPublicationReceipt) = Unit
                            })
                    }
                }
            }
        }
        override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult {
            mirror.withExecutionRead { Unit }
            val launch = LocalExecutionSubstrate.linuxLaunchSpec(mirror.directory, toolchainWorkspaceRoot = source)
            val result = mirror.withProcessCustody {
                LocalProcessSupervisor.captureAdmitted(launch.shellCommand(command), mirror.directory, launch.environment,
                    maxOutputBytes = maxOutputBytes, timeoutMs = 60_000) { start -> mirror.withExecutionRead { start() } }
            }
            return BridgeShellResult(if (result.timedOut) 124 else result.exitCode, result.output, "",
                truncated = result.outputIncomplete || result.output.toByteArray(Charsets.UTF_8).size >= maxOutputBytes)
        }
        override suspend fun pull(path: String, output: File) = mirror.withExecutionRead {
            requirePath(path)
            AgentWorkspaceArtifacts.pull(mirror.directory, executionFile(path), output)
            Unit
        }
        override suspend fun openMedia(path: String): java.io.InputStream =
            AgentWorkspaceMediaInput.openAdmitted(mirror.directory, executionFile(path)) { open ->
                mirror.withExecutionRead { requirePath(path); open() }
            }
        override suspend fun requireNewPath(path: String): Unit = withContext(Dispatchers.IO) {
            val occupied = mirror.withExecutionRead {
                requirePath(path)
                val relative = executionRelativePath(path)
                require(AgentWorkspacePathPolicy.visible(relative)) { "Choose an ordinary source/project attachment destination" }
                if (Files.exists(executionFile(path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) relative
                else { ChatMediaProjectAssets.destination(mirror.directory, relative); null }
            }
            if (occupied != null) {
                val relative = occupied
                withContext(NonCancellable) { mirror.preserveAttachmentConflict(relative) }
                error("CLI workspace already contains $relative; it was preserved. Choose another attachment filename.")
            }
        }
        override suspend fun pushNew(input: File, path: String): Unit = withContext(Dispatchers.IO) {
            requireNewPath(path)
            val relative = executionRelativePath(path)
            try {
                Files.newInputStream(input.toPath(), java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { source ->
                    executionWriteFiles().writeStream(relative, source,
                        expectedBytes = input.length(), requireAbsent = true)
                }
            } catch (failure: Exception) {
                // A terminal can create this path after preflight. Preserve it across reconciliation,
                // later refreshes and host recreation; never roll back the committed project asset.
                try {
                    if (Files.exists(executionFile(path).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        withContext(NonCancellable) { mirror.preserveAttachmentConflict(relative) }
                    }
                } catch (preservationFailure: Exception) {
                    if (preservationFailure !== failure) failure.addSuppressed(preservationFailure)
                }
                throw failure
            }
            Unit
        }
        override suspend fun push(input: File, path: String): Unit = throw UnsupportedOperationException(
            "Local CLI writes require a versioned text projection or new-attachment publication")
    }
    /**
     * The mapper exposes the canonical host root, while Android may supply the
     * stored root through a trusted parent alias (for example its app-data path).
     * Derive the relative name in the mapper's namespace, then address I/O through
     * the original identity-checked root. Never canonicalize an untrusted child:
     * anchored readers/writers must still reject child symlinks and path races.
     */
    private fun executionRelativePath(path: String): String {
        sourceIdentity.requireCurrent()
        mirror.requireCurrent()
        val base = mirror.directory.canonicalFile.toPath()
        val requested = File(path).absoluteFile.toPath().normalize()
        require(requested.startsWith(base) && requested.toFile().canonicalFile.toPath().startsWith(base)) {
            "Agent path escaped local mirror"
        }
        return base.relativize(requested).toString().replace(File.separatorChar, '/')
    }

    private fun executionFile(path: String): File = File(mirror.directory, executionRelativePath(path))

    private fun executionWriteFiles(): FileRepository =
        FileRepository.forAdmittedStreamWrites(mirror.directory, mirror::requireExecutionWriteAdmission)

    /** ELP always observes and launches the approved source root, never the CLI mirror. */
    override suspend fun inspectElpSelection(workspaceRoot: String, rootPath: String, deadline: ElpDeadline): ElpSelectionInspection? {
        require(File(workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).inspectElpSelection(workspaceRoot, rootPath, deadline)
    }
    override suspend fun observeElpPaths(inspection: ElpSelectionInspection, guestPaths: List<String>, deadline: ElpDeadline): Map<String, ElpObservedFile> {
        require(File(inspection.workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).observeElpPaths(inspection, guestPaths, deadline)
    }
    override suspend fun inspectElpToml(request: ElpMetadataInspectionRequest, permit: ElpMetadataInspectionPermit, deadline: ElpDeadline): ElpMetadataInspectionResult {
        require(File(request.inspection.workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).inspectElpToml(request, permit, deadline)
    }
    override suspend fun reviewedElpLaunchIdentity(resolvedExecutable: String, workspaceRoot: String, deadline: ElpDeadline): ElpManagedLaunchIdentity? {
        require(File(workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).reviewedElpLaunchIdentity(resolvedExecutable, workspaceRoot, deadline)
    }
    override suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest, deadline: ElpDeadline): ElpPreparedOwner {
        require(File(request.workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).prepareReviewedElpOwner(identity, request, deadline)
    }
    override suspend fun startReviewedElp(owner: ElpPreparedOwner, deadline: ElpDeadline, admitStart: (() -> ElpHostedProcess) -> ElpHostedProcess): ElpHostedProcess {
        require(File(owner.request.workspaceRoot).canonicalFile == source.canonicalFile)
        return LocalLinuxProcessHost(source, scope).startReviewedElp(owner, deadline, admitStart)
    }
    /** GraphQL captures the approved source root, never the independently refreshed CLI mirror. */
    override suspend fun reviewedGraphqlLaunchIdentity(resolvedExecutable:String,workspaceRoot:String,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity? {
        require(File(workspaceRoot).canonicalFile==source.canonicalFile)
        return LocalLinuxProcessHost(source,scope).reviewedGraphqlLaunchIdentity(resolvedExecutable,workspaceRoot,deadline)
    }
    override suspend fun prepareReviewedGraphqlOwner(identity:GraphqlManagedLaunchIdentity,request:GraphqlOwnerRequest,deadline:GraphqlDeadline):GraphqlPreparedOwner {
        require(File(request.workspaceRoot).canonicalFile==source.canonicalFile)
        return LocalLinuxProcessHost(source,scope).prepareReviewedGraphqlOwner(identity,request,deadline)
    }
    override suspend fun startReviewedGraphql(owner:GraphqlPreparedOwner,deadline:GraphqlDeadline,admitStart:(()->GraphqlHostedProcess)->GraphqlHostedProcess):GraphqlHostedProcess {
        require(File(owner.request.workspaceRoot).canonicalFile==source.canonicalFile)
        return LocalLinuxProcessHost(source,scope).startReviewedGraphql(owner,deadline,admitStart)
    }
    override suspend fun resolveExecutable(command: String): String? {
        sourceIdentity.requireCurrent()
        return sourceProcessHost.resolveExecutable(command).also { sourceIdentity.requireCurrent() }
    }
    override suspend fun pathMapper(): WorkspacePathMapper {
        prepareWorkspace()
        preparationMutex.withLock {
            if (!mirror.isPrepared || retainedOnDemand && !hasCompletedRefresh) {
                mirror.refresh()
                hasCompletedRefresh = true
            }
        }
        return mirror.withExecutionRead { WorkspacePathMapper(source, mirror.directory.canonicalPath) }
    }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
        mirror.withExecutionRead { Unit }
        return delegate.startAdmitted(argv, environment, resourceLimits, processCustody = mirror.processCustody) {
            start -> mirror.withExecutionRead { start() }
        }
    }
    override suspend fun startInWorkspace(argv: List<String>, environment: Map<String, String>, remoteCwd: String): HostedStdioProcess {
        mirror.withExecutionRead { Unit }
        return delegate.startInWorkspaceAdmitted(argv, environment, remoteCwd, null, processCustody = mirror.processCustody) {
            start -> mirror.withExecutionRead { start() }
        }
    }

    private suspend fun prepareWorkspace() {
        if (retainedOnDemand) prepareRetainedMirror() else storage.prepare()
    }

    private suspend fun prepareRetainedMirror() {
        val epoch = ApplicationWorkAdmission.requireOpen()
        val caller = currentCoroutineContext()
        fun requireEnrollment() {
            caller.ensureActive()
            scope.ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch)) { "CLI enrollment belongs to an expired application session" }
            sourceIdentity.requireCurrent()
        }

        requireEnrollment()
        preparationMutex.withLock {
            requireEnrollment()
            if (retainedPrepared) return@withLock
            val persistent = requireNotNull(persistentWorkspaceStore) { "Retained CLI workspace requires persistent storage" }
            require(!PathSecurity.contains(cacheDir, persistent)) {
                "Persistent CLI workspace storage must be outside the application cache; existing work was preserved"
            }
            storage.prepare()
            requireEnrollment()
            LocalMirrorAuthorityBootstrap(source, storage.directory).prepare()
            requireEnrollment()
            val prepared = LocalAgentWorkspaceMirror.openRetained(source, storage.directory)
            requireEnrollment()
            mirror = prepared
            retainedPrepared = true
        }
    }

    companion object {
        fun forWorkspace(source: File, cacheDir: File, scope: CoroutineScope, persistentWorkspaceStore: File): LocalAgentWorkspaceProcessHost =
            LocalAgentWorkspaceProcessHost(source, cacheDir, scope, persistentWorkspaceStore, retainedOnDemand = true)

        suspend fun enrollRetained(
            source: File,
            cacheDir: File,
            scope: CoroutineScope,
            persistentWorkspaceStore: File,
        ): LocalAgentWorkspaceProcessHost = LocalAgentWorkspaceProcessHost(source, cacheDir, scope, persistentWorkspaceStore).also {
            it.prepareRetainedMirror()
        }

        suspend fun openRetained(
            source: File,
            cacheDir: File,
            scope: CoroutineScope,
            persistentWorkspaceStore: File? = null,
        ): LocalAgentWorkspaceProcessHost {
            val host = LocalAgentWorkspaceProcessHost(source, cacheDir, scope, persistentWorkspaceStore)
            host.sourceIdentity.requireCurrent()
            host.storage.prepare()
            host.mirror = LocalAgentWorkspaceMirror.openRetained(source, host.storage.directory)
            host.sourceIdentity.requireCurrent()
            return host
        }
    }
}
