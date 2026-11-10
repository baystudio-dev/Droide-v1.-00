package com.baystudio.droide.core

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class LocalLinuxProcessHost(
    private val root: File,
    private val scope: CoroutineScope,
    private val workingDirectory: File = root,
    private val toolchainWorkspaceRoot: File = root,
) : StdioProcessHost {
    private val directoryIdentity = WorkspaceDirectoryIdentity(root)
    private val workingDirectoryIdentity = if (workingDirectory == root) directoryIdentity else WorkspaceDirectoryIdentity(workingDirectory)

    private fun requireCurrentDirectories() {
        directoryIdentity.requireCurrent()
        if (workingDirectoryIdentity !== directoryIdentity) workingDirectoryIdentity.requireCurrent()
    }

    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    override suspend fun pathMapper(): WorkspacePathMapper {
        directoryIdentity.requireCurrent()
        return WorkspacePathMapper(root, root.canonicalPath)
    }
    override suspend fun resolveExecutable(command: String): String? = resolveExecutable(command) {
        LocalExecutionSubstrate.prepareUserToolchainLaunchSpec(workingDirectory, workspaceRoot = root, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
    }

    internal suspend fun resolveExecutable(command: String, prepareLaunch: suspend () -> TerminalLaunchSpec): String? {
        val applicationEpoch = ApplicationWorkAdmission.requireOpen()
        val context = currentCoroutineContext()
        fun requireLookupContext() {
            context.ensureActive()
            scope.ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(applicationEpoch)) {
                "Executable lookup belongs to an expired application session"
            }
            requireCurrentDirectories()
        }

        requireLookupContext()
        ProcessSecurityPolicy.requireExecutableName(command)
        val spec = prepareLaunch()
        requireLookupContext()
        val result = LocalProcessSupervisor.captureAdmitted(
            spec.shellCommand("command -v ${LocalExecutionSubstrate.shellQuote(command)}"), workingDirectory, spec.environment,
            maxOutputBytes = 8192, timeoutMs = 10_000,
        ) { create ->
            withWorkspaceProcessCreation(root) {
                requireLookupContext()
                create().also { requireLookupContext() }
            }
        }
        requireLookupContext()
        return result.output.trim().takeIf {
            result.exitCode == 0 && !result.timedOut && it.startsWith('/') && '\n' !in it && '\r' !in it
        }
    }
    override suspend fun startInWorkspace(
        argv: List<String>,
        environment: Map<String, String>,
        remoteCwd: String,
    ): HostedStdioProcess = startInWorkspace(argv, environment, remoteCwd, null)

    override suspend fun startInWorkspace(
        argv: List<String>,
        environment: Map<String, String>,
        remoteCwd: String,
        resourceLimits: ProcessResourceLimits?,
    ): HostedStdioProcess = startInWorkspaceAdmitted(argv, environment, remoteCwd, resourceLimits) { create ->
        withWorkspaceProcessCreation(root, create)
    }

    internal suspend fun startInWorkspaceAdmitted(
        argv: List<String>,
        environment: Map<String, String>,
        remoteCwd: String,
        resourceLimits: ProcessResourceLimits?,
        processCustody: WorkspaceProcessCustody? = null,
        admitStart: suspend (() -> LocalHostedProcess) -> LocalHostedProcess,
    ): HostedStdioProcess {
        directoryIdentity.requireCurrent()
        val cwd = File(remoteCwd).canonicalFile
        require(cwd.toPath().startsWith(root.canonicalFile.toPath()) && cwd.isDirectory) { "Process cwd escaped workspace" }
        val child = LocalLinuxProcessHost(root, scope, cwd, toolchainWorkspaceRoot)
        directoryIdentity.requireCurrent()
        return child.startAdmitted(argv, environment, resourceLimits, processCustody = processCustody) { create ->
            admitStart {
                directoryIdentity.requireCurrent()
                create().also { directoryIdentity.requireCurrent() }
            }.also { directoryIdentity.requireCurrent() }
        }
    }

    override suspend fun inspectElpSelection(workspaceRoot: String, rootPath: String, deadline: ElpDeadline): ElpSelectionInspection {
        scope.ensureActive(); deadline.check(); require(File(workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.inspectElpSelection(root, rootPath, deadline)
    }
    override suspend fun observeElpPaths(inspection: ElpSelectionInspection, guestPaths: List<String>, deadline: ElpDeadline): Map<String, ElpObservedFile> {
        scope.ensureActive(); require(File(inspection.workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.observeElpPaths(inspection, guestPaths, deadline)
    }
    override suspend fun inspectElpToml(request: ElpMetadataInspectionRequest, permit: ElpMetadataInspectionPermit,
        deadline: ElpDeadline): ElpMetadataInspectionResult {
        scope.ensureActive(); require(File(request.inspection.workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.inspectElpToml(request, permit, deadline)
    }
    override suspend fun reviewedElpLaunchIdentity(resolvedExecutable: String, workspaceRoot: String, deadline: ElpDeadline): ElpManagedLaunchIdentity? {
        scope.ensureActive(); require(File(workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.reviewedElpLaunchIdentity(resolvedExecutable, toolchainWorkspaceRoot, deadline)
    }
    override suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest,
        deadline: ElpDeadline): ElpPreparedOwner {
        scope.ensureActive(); require(File(request.workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.prepareReviewedElpOwner(identity, request, root, toolchainWorkspaceRoot, deadline)
    }
    override suspend fun startReviewedElp(owner: ElpPreparedOwner, deadline: ElpDeadline,
        admitStart: (() -> ElpHostedProcess) -> ElpHostedProcess): ElpHostedProcess {
        require(owner is LocalElpPreparedOwner && File(owner.request.workspaceRoot).canonicalFile == root.canonicalFile)
        val work = deadline.reserve(ElpResourcePolicy.RETIRE_MS); work.check()
        val epoch = ApplicationWorkAdmission.requireOpen()
        val exact = java.util.concurrent.atomic.AtomicReference<LocalElpHostedProcess?>()
        try {
            owner.execution.beginFactory {
                scope.ensureActive(); work.check()
                LocalExecutionSubstrate.revalidateElpOwner(owner, toolchainWorkspaceRoot, work)
                val selection = requireNotNull(owner.request.observation.tools)
                val command = owner.launchSpec.prefix + ElpResourcePolicy.ANALYSIS.shellPrefix() + LspElpLaunchPolicy.nativeArgv(owner.identity, selection)
                val builder = ProcessBuilder("/system/bin/sh", "-c", owner.execution.shellCommand(command))
                    .directory(owner.execution.localRoot).apply { environment().clear(); environment().putAll(owner.launchSpec.environment) }
                var created: LocalElpHostedProcess? = null
                val accepted = admitStart {
                    check(created == null) { "ELP exact native factory invoked twice" }
                    work.check(); scope.ensureActive(); check(owner.isCurrent)
                    LocalElpHostedProcess(owner.execution.create { builder.start() }, owner).also { created = it }
                }
                check(accepted === created) { "ELP admission replaced the protected process" }
                check(exact.compareAndSet(null, requireNotNull(created)))
                // Native factory custody is recorded before any late cancellation check.
                requireNotNull(created).nativeProcess()
            }
            val native = owner.execution.awaitFactory(work)
            owner.execution.awaitBirth(work); owner.execution.beginMonitoring()
            check(owner.isCurrent); work.check()
            check(requireNotNull(exact.get()).nativeProcess() === native)
            return ExitHostedProcessRegistry.admit(requireNotNull(exact.get()), epoch) as ElpHostedProcess
        } catch (failure: Throwable) {
            owner.fence(); withContext(NonCancellable) { owner.retire(deadline) }; throw failure
        }
    }
    override suspend fun reviewedGraphqlLaunchIdentity(resolvedExecutable:String,workspaceRoot:String,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity? {
        deadline.check();scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return LocalExecutionSubstrate.reviewedGraphqlLaunchIdentity(resolvedExecutable,toolchainWorkspaceRoot,deadline)
    }
    override suspend fun prepareReviewedGraphqlOwner(identity:GraphqlManagedLaunchIdentity,request:GraphqlOwnerRequest,deadline:GraphqlDeadline):GraphqlPreparedOwner {
        deadline.check();scope.ensureActive();currentCoroutineContext().ensureActive()
        return LocalExecutionSubstrate.prepareReviewedGraphqlOwner(identity,request,root,toolchainWorkspaceRoot,deadline)
    }
    override suspend fun startReviewedGraphql(owner:GraphqlPreparedOwner,deadline:GraphqlDeadline,
        admitStart:(()->GraphqlHostedProcess)->GraphqlHostedProcess):GraphqlHostedProcess {
        require(owner is LocalGraphqlPreparedOwner);deadline.check();val work=deadline.reserve(GraphqlResourcePolicy.RETIRE_MS)
        val epoch=ApplicationWorkAdmission.requireOpen()
        try {
            // The entire native creation attempt remains strongly owned if a JVM call stalls or returns
            // late.
            owner.beginFactory {
                work.check();scope.ensureActive();val identity=owner.identity
                val spec=owner.requirePreparedLaunchSpec();val command=spec.prefix+LspGraphqlLaunchPolicy.adapterArgv(identity,owner)
                val builder=ProcessBuilder("/system/bin/sh","-c",owner.shellCommand(command)).directory(owner.localRoot).apply{environment().clear();environment().putAll(spec.environment)}
                LocalExecutionSubstrate.withReviewedGraphqlLaunchIdentity(identity,owner,toolchainWorkspaceRoot,deadline) { selectionAdmission ->
                    var created:GraphqlHostedProcess?=null
                    val admitted=admitStart { check(created==null){"GraphQL protected factory invoked twice"}
                        selectionAdmission {scope.ensureActive();work.check();check(owner.isCurrent);owner.createProcess{builder.start()}.also{created=it}}
                    }
                    check(admitted===created){"GraphQL admission replaced exact process"};requireNotNull(created)
                }
            }
            val process=owner.awaitFactory(work);owner.beginObservation();owner.awaitServer(work);process.awaitBootstrapAccepted(work)
            work.check();check(owner.isCurrent);ExitHostedProcessRegistry.admit(process,epoch);return process
        } catch(failure:Throwable) {owner.fence();withContext(NonCancellable){owner.retire(deadline)};throw failure}
    }
    override suspend fun reviewedTomlLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):TomlManagedLaunchIdentity? {
        scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return LocalExecutionSubstrate.reviewedTomlLaunchIdentity(resolvedExecutable,toolchainWorkspaceRoot)
    }
    override suspend fun prepareReviewedTomlOwner(identity:TomlManagedLaunchIdentity,request:TomlOwnerRequest):TomlPreparedOwner {
        scope.ensureActive();currentCoroutineContext().ensureActive();var created:LocalTomlPreparedOwner?=null
        try{return withContext(Dispatchers.IO){LocalExecutionSubstrate.prepareReviewedTomlOwner(identity,request,root,toolchainWorkspaceRoot).also{created=it}}}
        catch(failure:Throwable){withContext(NonCancellable){created?.retire()};throw failure}
    }
    override suspend fun startReviewedTomlInWorkspace(identity:TomlManagedLaunchIdentity,owner:TomlPreparedOwner,
        admitStart:(()->TomlHostedProcess)->TomlHostedProcess):TomlHostedProcess {
        require(owner is LocalTomlPreparedOwner&&owner.identity==identity)
        val epoch=ApplicationWorkAdmission.requireOpen();var created:TomlHostedProcess?=null
        try{val process=withContext(Dispatchers.IO){scope.ensureActive();val context=currentCoroutineContext();context.ensureActive()
            val spec=owner.requirePreparedLaunchSpec();val command=spec.prefix+LspTomlLaunchPolicy.adapterArgv(identity,owner)
            val builder=ProcessBuilder("/system/bin/sh","-c",owner.shellCommand(command)).directory(owner.localRoot).apply{environment().clear();environment().putAll(spec.environment)}
            LocalExecutionSubstrate.withReviewedTomlLaunchIdentity<TomlHostedProcess>(identity,owner,toolchainWorkspaceRoot){selectionAdmission->
                val admitted=admitStart{check(created==null){"TOML protected factory invoked twice"};selectionAdmission{scope.ensureActive();context.ensureActive();check(owner.isCurrent);owner.createProcess{builder.start()}.also{created=it}}}
                check(admitted===created){"TOML admission replaced exact protected process"};requireNotNull(created)
            }
        };owner.beginObservation();owner.awaitServer();ExitHostedProcessRegistry.admit(process,epoch);return process
        }catch(failure:Throwable){withContext(NonCancellable){runCatching{owner.retire()}.exceptionOrNull()?.let{if(it!==failure)failure.addSuppressed(it)}};throw failure}
    }
    override suspend fun inspectCSharpSelection(workspaceRoot:String,rootPath:String,solutionPath:String?):CSharpSelectionInspection {
        scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return withContext(Dispatchers.IO) { CSharpSdkSelection.inspect(workspaceRoot,rootPath,solutionPath) }
    }
    override suspend fun reviewedCSharpLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):CSharpManagedLaunchIdentity? {
        scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return LocalExecutionSubstrate.reviewedCSharpLaunchIdentity(resolvedExecutable,toolchainWorkspaceRoot)
    }
    override suspend fun prepareReviewedCSharpOwner(identity:CSharpManagedLaunchIdentity,request:CSharpOwnerRequest):CSharpPreparedOwner {
        scope.ensureActive();currentCoroutineContext().ensureActive();var created:LocalCSharpPreparedOwner?=null
        try {return withContext(Dispatchers.IO){LocalExecutionSubstrate.prepareReviewedCSharpOwner(identity,request,root,toolchainWorkspaceRoot).also {created=it}}}
        catch(failure:Throwable){withContext(NonCancellable){created?.retire()};throw failure}
    }
    override suspend fun startReviewedCSharpInWorkspace(identity:CSharpManagedLaunchIdentity,owner:CSharpPreparedOwner,
        admitStart:(()->CSharpHostedProcess)->CSharpHostedProcess):CSharpHostedProcess {
        require(owner is LocalCSharpPreparedOwner && owner.identity==identity)
        val epoch=ApplicationWorkAdmission.requireOpen();var created:CSharpHostedProcess?=null
        try {
            val process=withContext(Dispatchers.IO) {
                scope.ensureActive();val context=currentCoroutineContext();context.ensureActive()
                val spec=owner.requirePreparedLaunchSpec();val lease=RemoteProcessLease.createLocal("csharp-stdio")
                val command=spec.prefix+lease.environment.map {(k,v)->"$k=$v"}+LspCSharpLaunchPolicy.supervisorArgv(identity,owner)
                val builder=ProcessBuilder("/system/bin/sh","-c",lease.wrap(command.joinToString(" ",transform=LocalExecutionSubstrate::shellQuote)))
                    .directory(File(owner.request.projectLocalCanonical)).apply {environment().clear();environment().putAll(spec.environment)}
                LocalExecutionSubstrate.withReviewedCSharpLaunchIdentity<CSharpHostedProcess>(identity,owner,toolchainWorkspaceRoot) {selectionAdmission->
                    val admitted=admitStart {
                        check(created==null){"CSharp protected factory invoked twice"}
                        selectionAdmission {scope.ensureActive();context.ensureActive();check(owner.isCurrent)
                            owner.createProcess({builder.start()},{LocalExecutionSubstrate.terminateLease(lease)}).also {created=it}
                        }
                    }
                    check(admitted===created){"CSharp admission replaced the exact protected process"};requireNotNull(created)
                }
            }
            owner.awaitServer();ExitHostedProcessRegistry.admit(process,epoch);return process
        }catch(failure:Throwable){withContext(NonCancellable){runCatching {owner.retire()}.exceptionOrNull()?.let {if(it!==failure)failure.addSuppressed(it)}};throw failure}
    }
    override suspend fun reviewedVueLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):VueManagedLaunchIdentity? {
        scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return LocalExecutionSubstrate.reviewedVueLaunchIdentity(resolvedExecutable,toolchainWorkspaceRoot)
    }
    override suspend fun prepareReviewedVueOwner(identity:VueManagedLaunchIdentity,request:VueOwnerRequest):VuePreparedOwner {
        scope.ensureActive();currentCoroutineContext().ensureActive();var created:LocalVuePreparedOwner?=null
        try {return withContext(Dispatchers.IO){LocalExecutionSubstrate.prepareReviewedVueOwner(identity,request,root,toolchainWorkspaceRoot).also {created=it}}}
        catch(failure:Throwable){withContext(NonCancellable){created?.retire()};throw failure}
    }
    override suspend fun startReviewedVueInWorkspace(identity:VueManagedLaunchIdentity,owner:VuePreparedOwner,
        admitStart:(()->VueHostedProcess)->VueHostedProcess):VueHostedProcess {
        require(owner is LocalVuePreparedOwner && owner.identity==identity)
        val epoch=ApplicationWorkAdmission.requireOpen();var created:VueHostedProcess?=null
        try {
            val process=withContext(Dispatchers.IO) {
                scope.ensureActive();val context=currentCoroutineContext();context.ensureActive()
                val spec=owner.requirePreparedLaunchSpec();val lease=RemoteProcessLease.createLocal("vue-stdio")
                val command=spec.prefix+lease.environment.map {(k,v)->"$k=$v"}+LspVueLaunchPolicy.supervisorArgv(identity,owner)
                val builder=ProcessBuilder("/system/bin/sh","-c",lease.wrap(command.joinToString(" ",transform=LocalExecutionSubstrate::shellQuote)))
                    .directory(File(owner.request.projectLocalCanonical)).apply {environment().clear();environment().putAll(spec.environment)}
                LocalExecutionSubstrate.withReviewedVueLaunchIdentity<VueHostedProcess>(identity,owner,toolchainWorkspaceRoot) {selectionAdmission->
                    val admitted=admitStart {
                        check(created==null){"Vue protected factory invoked twice"}
                        selectionAdmission {scope.ensureActive();context.ensureActive();check(owner.isCurrent)
                            owner.createProcess({builder.start()},{LocalExecutionSubstrate.terminateLease(lease)}).also {created=it}
                        }
                    }
                    check(admitted===created){"Vue admission replaced the exact protected process"};requireNotNull(created)
                }
            }
            owner.awaitServer();ExitHostedProcessRegistry.admit(process,epoch);return process
        }catch(failure:Throwable){withContext(NonCancellable){runCatching {owner.retire()}.exceptionOrNull()?.let {if(it!==failure)failure.addSuppressed(it)}};throw failure}
    }
    override suspend fun reviewedNimLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):NimManagedLaunchIdentity? {
        scope.ensureActive();require(File(workspaceRoot).canonicalFile==root.canonicalFile)
        return LocalExecutionSubstrate.reviewedNimLaunchIdentity(resolvedExecutable,toolchainWorkspaceRoot)
    }
    override suspend fun prepareReviewedNimOwner(identity:NimManagedLaunchIdentity,request:NimOwnerRequest):NimPreparedOwner {
        scope.ensureActive();currentCoroutineContext().ensureActive();var created:LocalNimPreparedOwner?=null
        try {return withContext(Dispatchers.IO){LocalExecutionSubstrate.prepareReviewedNimOwner(identity,request,root,toolchainWorkspaceRoot).also {created=it}}}
        catch(failure:Throwable){withContext(NonCancellable){created?.retire()};throw failure}
    }
    override suspend fun startReviewedNimInWorkspace(identity:NimManagedLaunchIdentity,owner:NimPreparedOwner,
        admitStart:(()->NimHostedProcess)->NimHostedProcess):NimHostedProcess {
        require(owner is LocalNimPreparedOwner && owner.identity==identity)
        val epoch=ApplicationWorkAdmission.requireOpen();var created:NimHostedProcess?=null
        try {
            val process=withContext(Dispatchers.IO) {
                scope.ensureActive();val context=currentCoroutineContext();context.ensureActive()
                val spec=owner.requirePreparedLaunchSpec();val lease=RemoteProcessLease.createLocal("nim-stdio")
                val command=spec.prefix+lease.environment.map {(k,v)->"$k=$v"}+LspNimLaunchPolicy.supervisorArgv(identity,owner)
                val builder=ProcessBuilder("/system/bin/sh","-c",lease.wrap(command.joinToString(" ",transform=LocalExecutionSubstrate::shellQuote)))
                    .directory(File(owner.request.projectLocalCanonical)).apply {environment().clear();environment().putAll(spec.environment)}
                LocalExecutionSubstrate.withReviewedNimLaunchIdentity<NimHostedProcess>(identity,owner,toolchainWorkspaceRoot) {selectionAdmission->
                    val admitted=admitStart {
                        check(created==null){"Nim protected factory invoked twice"}
                        selectionAdmission {scope.ensureActive();context.ensureActive();check(owner.isCurrent)
                            owner.createProcess({builder.start()},{LocalExecutionSubstrate.terminateLease(lease)}).also {created=it}
                        }
                    }
                    check(admitted===created){"Nim admission replaced the exact protected process"};requireNotNull(created)
                }
            }
            owner.awaitServer();ExitHostedProcessRegistry.admit(process,epoch);return process
        }catch(failure:Throwable){withContext(NonCancellable){runCatching {owner.retire()}.exceptionOrNull()?.let {if(it!==failure)failure.addSuppressed(it)}};throw failure}
    }
    override suspend fun cmakeSelectionContext(workspaceRoot: String): CMakeSelectionContext? {
        val workspace = File(workspaceRoot).canonicalFile
        require(workspace == root.canonicalFile) { "CMake selection workspace changed" }
        return LocalExecutionSubstrate.cmakeSelectionContext(workspace)
    }
    override suspend fun reviewedCMakeLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): CMakeManagedLaunchIdentity? {
        scope.ensureActive()
        require(File(workspaceRoot).canonicalFile == root.canonicalFile)
        return LocalExecutionSubstrate.reviewedCMakeLaunchIdentity(resolvedExecutable, toolchainWorkspaceRoot)
    }
    override suspend fun prepareReviewedCMakeOwner(identity: CMakeManagedLaunchIdentity, request: CMakeOwnerRequest): CMakePreparedOwner {
        scope.ensureActive(); currentCoroutineContext().ensureActive()
        var owned: CMakePreparedOwner? = null
        try {
            return withContext(Dispatchers.IO) {
                LocalExecutionSubstrate.prepareReviewedCMakeOwner(identity, request, root, toolchainWorkspaceRoot).also { owned = it }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { owned?.retire() }
            throw failure
        }
    }
    override suspend fun startReviewedCMakeInWorkspace(identity: CMakeManagedLaunchIdentity, owner: CMakePreparedOwner,
        admitStart: (() -> CMakeHostedProcess) -> CMakeHostedProcess): CMakeHostedProcess {
        require(owner is LocalCMakePreparedOwner && owner.identity == identity)
        val epoch = ApplicationWorkAdmission.requireOpen()
        var created: CMakeHostedProcess? = null
        try {
            val process = withContext(Dispatchers.IO) {
                scope.ensureActive()
                val spec = owner.requirePreparedLaunchSpec()
                owner.observeSelection()
                val context = currentCoroutineContext(); context.ensureActive()
                val command = spec.prefix + LocalUbuntuCMakeLaunchPolicy.supervisorArgv(identity, owner)

                val control = owner.privateState.localRoot
                val script = "set -euC; umask 077; printf '%s\\n' \"$$\" > " + LocalExecutionSubstrate.shellQuote(File(control, "outer.pid").absolutePath) +
                    "; /system/bin/cat /proc/$$/stat > " + LocalExecutionSubstrate.shellQuote(File(control, "outer.stat").absolutePath) +
                    "; /system/bin/cat /proc/sys/kernel/random/boot_id > " + LocalExecutionSubstrate.shellQuote(File(control, "outer.boot").absolutePath) +
                    "; /system/bin/readlink /proc/$$/ns/pid > " + LocalExecutionSubstrate.shellQuote(File(control, "outer.namespace").absolutePath) +
                    "; exec " + command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)
                val builder = ProcessBuilder("/system/bin/sh", "-c", script).directory(File(owner.request.selection.sourceLocalCanonical))
                    .apply { environment().clear(); environment().putAll(spec.environment) }
                LocalExecutionSubstrate.withReviewedCMakeLaunchIdentity<CMakeHostedProcess>(identity, owner, toolchainWorkspaceRoot) { selectionAdmission ->
                    val admitted = admitStart {
                        check(created == null) { "CMake protected factory invoked twice" }
                        selectionAdmission {
                            scope.ensureActive(); context.ensureActive()
                            owner.createProcess { builder.start() }.also { created = it }
                        }
                    }
                    check(admitted === created) { "CMake admission replaced its exact created owner" }
                    requireNotNull(created) { "CMake admission did not invoke protected factory" }
                }
            }
            // Cancellation after creation cannot discard the only cleanup owner.
            owner.awaitReady()
            ExitHostedProcessRegistry.admit(process, epoch)
            return process
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { owner.retire() }.exceptionOrNull()?.let { if (it !== failure) failure.addSuppressed(it) } }
            throw failure
        }
    }
    override suspend fun startReviewedSqruffInWorkspace(identity: SqruffManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String, resourceLimits: ProcessResourceLimits,
        admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess {
        require(argv == listOf(identity.launchExecutable, "lsp")) { "Reviewed Sqruff launch command changed" }
        val cwd = File(remoteCwd).canonicalFile
        require(cwd.toPath().startsWith(root.canonicalFile.toPath()) && cwd.isDirectory) { "Process cwd escaped workspace" }
        val epoch = ApplicationWorkAdmission.requireOpen()
        val process = handoffLocalHostedProcess {
            scope.ensureActive()
            ProcessSecurityPolicy.validateArgv(argv); ProcessSecurityPolicy.validateEnvironment(environment); resourceLimits.validate()

            val spec = LocalExecutionSubstrate.prepareUbuntuLaunchSpec(cwd, workspaceRoot = root, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
            scope.ensureActive()
            val creationContext = currentCoroutineContext()
            creationContext.ensureActive()
            val lease = RemoteProcessLease.createLocal("stdio")
            val l = resourceLimits
            val payload = listOf("/bin/bash", "-c", "ulimit -c 0 && ulimit -t ${l.cpuSeconds} && " +
                "ulimit -f ${l.fileSizeKiB} && ulimit -n ${l.openFiles} && " +
                "ulimit -u ${l.processes} && ulimit -v ${l.virtualMemoryKiB} && exec \"\$@\"", "droide") + argv
            val command = spec.prefix + (environment + lease.environment).map { (k, v) -> "$k=$v" } + payload
            val builder = ProcessBuilder("/system/bin/sh", "-c", lease.wrap(command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)))
                .directory(cwd).apply { environment().putAll(spec.environment) }
            LocalExecutionSubstrate.withReviewedSqruffLaunchIdentity<LocalHostedProcess>(identity, toolchainWorkspaceRoot) { selectionAdmission ->
                var created: LocalHostedProcess? = null
                try {
                    // The manager wraps this action in its own non-suspending lifecycle admission.
                    val admitted = admitStart {
                        check(created == null) { "Reviewed Sqruff creation action was invoked twice" }
                        scope.ensureActive(); creationContext.ensureActive()
                        selectionAdmission {

                            scope.ensureActive(); creationContext.ensureActive()
                            createOwnedLocalHostedProcess(
                                start = { builder.start() },
                                scope = scope,
                                cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                            ).also {
                                created = it
                                ExitHostedProcessRegistry.retain(it)
                            }
                        }
                    }
                    check(admitted === created) { "Reviewed Sqruff admission replaced the created owner" }
                    requireNotNull(created) { "Reviewed Sqruff admission did not create a process" }
                } catch (failure: Throwable) {
                    try { created?.close() } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
        return ExitHostedProcessRegistry.admit(process, epoch)
    }
    override suspend fun reviewedSqruffLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): SqruffManagedLaunchIdentity? {
        scope.ensureActive()
        val workspace = File(workspaceRoot).canonicalFile
        require(workspace.toPath().startsWith(root.canonicalFile.toPath()) && workspace.isDirectory) { "Sqruff identity root escaped workspace" }
        return LocalExecutionSubstrate.reviewedSqruffLaunchIdentity(resolvedExecutable, toolchainWorkspaceRoot)
    }
    override suspend fun startReviewedSvelteInWorkspace(identity: SvelteManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String, admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess {
        require(argv == listOf(identity.launchExecutable, "--stdio")) { "Reviewed Svelte launch command changed" }
        val cwd = File(remoteCwd).canonicalFile
        require(cwd.toPath().startsWith(root.canonicalFile.toPath()) && cwd.isDirectory) { "Process cwd escaped workspace" }
        val epoch = ApplicationWorkAdmission.requireOpen()
        val process = handoffLocalHostedProcess {
            scope.ensureActive()
            ProcessSecurityPolicy.validateArgv(argv); ProcessSecurityPolicy.validateEnvironment(environment)

            val spec = LocalExecutionSubstrate.prepareUbuntuLaunchSpec(cwd, workspaceRoot = root, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
            scope.ensureActive()
            val creationContext = currentCoroutineContext()
            creationContext.ensureActive()
            val lease = RemoteProcessLease.createLocal("stdio")
            require(environment.keys.none { it in setOf("NODE_OPTIONS", "NODE_PATH", "LD_PRELOAD", "LD_LIBRARY_PATH") }) { "Svelte runtime injection override is not admitted" }
            val command = spec.prefix + (environment + lease.environment).map { (k, v) -> "$k=$v" } + argv
            val builder = ProcessBuilder("/system/bin/sh", "-c", lease.wrap(command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)))
                .directory(cwd).apply { environment().clear(); environment().putAll(spec.environment) }
            LocalExecutionSubstrate.withReviewedSvelteLaunchIdentity<LocalHostedProcess>(identity, toolchainWorkspaceRoot) { selectionAdmission ->
                var created: LocalHostedProcess? = null
                try {
                    // The manager wraps this action in its own non-suspending lifecycle admission.
                    val admitted = admitStart {
                        check(created == null) { "Reviewed Svelte creation action was invoked twice" }
                        scope.ensureActive(); creationContext.ensureActive()
                        selectionAdmission {

                            scope.ensureActive(); creationContext.ensureActive()
                            createOwnedLocalHostedProcess(
                                start = { builder.start() },
                                scope = scope,
                                cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                            ).also {
                                created = it
                                ExitHostedProcessRegistry.retain(it)
                            }
                        }
                    }
                    check(admitted === created) { "Reviewed Svelte admission replaced the created owner" }
                    requireNotNull(created) { "Reviewed Svelte admission did not create a process" }
                } catch (failure: Throwable) {
                    try { created?.close() } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
        return ExitHostedProcessRegistry.admit(process, epoch)
    }
    override suspend fun reviewedSvelteLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): SvelteManagedLaunchIdentity? {
        scope.ensureActive()
        val workspace = File(workspaceRoot).canonicalFile
        require(workspace.toPath().startsWith(root.canonicalFile.toPath()) && workspace.isDirectory) { "Svelte identity root escaped workspace" }
        return LocalExecutionSubstrate.reviewedSvelteLaunchIdentity(resolvedExecutable, toolchainWorkspaceRoot)
    }
    override suspend fun startReviewedPowerShellInWorkspace(identity: PowerShellManagedLaunchIdentity, argv: List<String>,
        environment: Map<String, String>, remoteCwd: String, admitStart: (() -> HostedStdioProcess) -> HostedStdioProcess): HostedStdioProcess {
        require(argv == listOf(identity.launchExecutable)) { "Reviewed PowerShell launch command changed" }
        val cwd = File(remoteCwd).canonicalFile
        require(cwd.toPath().startsWith(root.canonicalFile.toPath()) && cwd.isDirectory) { "Process cwd escaped workspace" }
        val epoch = ApplicationWorkAdmission.requireOpen()
        val process = handoffLocalHostedProcess {
            scope.ensureActive()
            ProcessSecurityPolicy.validateArgv(argv); ProcessSecurityPolicy.validateEnvironment(environment)

            val spec = LocalExecutionSubstrate.prepareUbuntuLaunchSpec(cwd, workspaceRoot = root, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
            scope.ensureActive()
            val creationContext = currentCoroutineContext()
            creationContext.ensureActive()
            val lease = RemoteProcessLease.createLocal("stdio")
            require(LspPowerShellLaunchPolicy.matchesIdentity(identity))
            require(environment.keys.all { it in setOf("HOME", "PATH", "PSModulePath", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR") }) {
                "Unreviewed PowerShell editor environment override"
            }
            val command = spec.prefix + (environment + lease.environment).map { (k, v) -> "$k=$v" } + argv
            val builder = ProcessBuilder("/system/bin/sh", "-c", lease.wrap(command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)))
                .directory(cwd).apply { environment().clear(); environment().putAll(spec.environment) }
            LocalExecutionSubstrate.withReviewedPowerShellLaunchIdentity<LocalHostedProcess>(identity, toolchainWorkspaceRoot) { selectionAdmission ->
                var created: LocalHostedProcess? = null
                try {
                    // The manager wraps this action in its own non-suspending lifecycle admission.
                    val admitted = admitStart {
                        check(created == null) { "Reviewed PowerShell creation action was invoked twice" }
                        scope.ensureActive(); creationContext.ensureActive()
                        selectionAdmission {

                            scope.ensureActive(); creationContext.ensureActive()
                            createOwnedLocalHostedProcess(
                                start = { builder.start() },
                                scope = scope,
                                cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
                            ).also {
                                created = it
                                ExitHostedProcessRegistry.retain(it)
                            }
                        }
                    }
                    check(admitted === created) { "Reviewed PowerShell admission replaced the created owner" }
                    requireNotNull(created) { "Reviewed PowerShell admission did not create a process" }
                } catch (failure: Throwable) {
                    try { created?.close() } catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
        return ExitHostedProcessRegistry.admit(process, epoch)
    }
    override suspend fun reviewedPowerShellLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): PowerShellManagedLaunchIdentity? {
        scope.ensureActive()
        val workspace = File(workspaceRoot).canonicalFile
        require(workspace.toPath().startsWith(root.canonicalFile.toPath()) && workspace.isDirectory) { "PowerShell identity root escaped workspace" }
        return LocalExecutionSubstrate.reviewedPowerShellLaunchIdentity(resolvedExecutable, toolchainWorkspaceRoot)
    }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess =
        startAdmitted(argv, environment, resourceLimits) { create ->
            withWorkspaceProcessCreation(root, create)
        }

    internal suspend fun startAdmitted(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
        processCustody: WorkspaceProcessCustody? = null,
        admitStart: suspend (() -> LocalHostedProcess) -> LocalHostedProcess,
    ): HostedStdioProcess = startAdmitted(
        argv, environment, resourceLimits,
        prepareStart = { prepareStdioProcess(argv, environment, resourceLimits) },
        processCustody = processCustody,
        admitStart = admitStart,
    )

    internal suspend fun startAdmitted(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
        prepareStart: suspend () -> (() -> LocalHostedProcess),
        processCustody: WorkspaceProcessCustody? = null,
        admitStart: suspend (() -> LocalHostedProcess) -> LocalHostedProcess,
    ): HostedStdioProcess {
        requireCurrentDirectories()
        val epoch = ApplicationWorkAdmission.requireOpen()
        val process = handoffLocalHostedProcess {
            val context = currentCoroutineContext()
            fun requireStartContext() {
                context.ensureActive()
                scope.ensureActive()
                check(ApplicationWorkAdmission.isCurrentOpen(epoch)) {
                    "Process start belongs to an expired application session"
                }
                requireCurrentDirectories()
            }

            requireStartContext()
            ProcessSecurityPolicy.validateArgv(argv); ProcessSecurityPolicy.validateEnvironment(environment); resourceLimits?.validate()
            require(argv.first().startsWith('/') || Regex("[A-Za-z0-9_][A-Za-z0-9._+-]{0,127}").matches(argv.first())) {
                "Executable must be an absolute guest path or a bare command name"
            }
            val createPrepared = prepareStart()
            requireStartContext()
            admitLocalHostedProcess({ create -> admitStart(create).also { requireStartContext() } }) {
                requireStartContext()
                WorkspaceProcessWriteAdmission.create(root, processCustody,
                    // Durable admission can block on IO; abort before entering the process-capable factory when
                    // cancellation/Exit won meanwhile.
                    beforeFactory = {
                        requireStartContext()
                        (createPrepared as? PreparedLocalNativeProcess)?.validateBeforeStart()
                    },
                ) { createPrepared() }
            }
        }
        return ExitHostedProcessRegistry.admit(process, epoch)
    }

    private suspend fun prepareStdioProcess(
        argv: List<String>,
        environment: Map<String, String>,
        resourceLimits: ProcessResourceLimits?,
    ): () -> LocalHostedProcess {
        val spec = LocalExecutionSubstrate.prepareUserToolchainLaunchSpec(workingDirectory, workspaceRoot = root, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
        scope.ensureActive()
        currentCoroutineContext().ensureActive()
        val lease = RemoteProcessLease.createLocal("stdio")
        val payload = if (resourceLimits == null) argv else {
            val l = resourceLimits
            listOf("/bin/bash", "-c", "ulimit -c 0 && ulimit -t ${l.cpuSeconds} && " +
                "ulimit -f ${l.fileSizeKiB} && ulimit -n ${l.openFiles} && " +
                "ulimit -u ${l.processes} && ulimit -v ${l.virtualMemoryKiB} && exec \"\$@\"", "droide") + argv
        }
        val command = spec.prefix + (environment + lease.environment).map { (k, v) -> "$k=$v" } + payload
        currentCoroutineContext().ensureActive()
        return PreparedLocalNativeProcess(
            ProcessBuilder("/system/bin/sh", "-c", lease.wrap(command.joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)))
                .directory(workingDirectory).apply { environment().putAll(spec.environment) },
            scope,
            cleanup = { LocalExecutionSubstrate.terminateLease(lease) },
        )
    }
}

/** Final native factory: validation observes exactly the builder later started. */

internal class PreparedLocalNativeProcess(
    private val builder: ProcessBuilder,
    private val scope: CoroutineScope,
    private val cleanup: () -> Unit,
) : () -> LocalHostedProcess {
    fun validateBeforeStart() = NativeProcessStartPreflight.validate(builder)
    override fun invoke(): LocalHostedProcess = createOwnedLocalHostedProcess(
        start = { builder.start() }, scope = scope, cleanup = cleanup,
    )
}

// Hold the shared guard only for creation; the caller owns any process rejected at return.
internal suspend fun <T> withWorkspaceProcessCreation(root: File, create: () -> T): T =
    WorkspaceFileMutationLocks.withCaptureRoot(root) { guard ->
        guard.requireRoot(root)
        create().also { guard.requireRoot(root) }
    }

internal suspend fun admitLocalHostedProcess(
    admission: suspend (() -> LocalHostedProcess) -> LocalHostedProcess,
    create: () -> LocalHostedProcess,
): LocalHostedProcess {
    val context = currentCoroutineContext()
    var owned: LocalHostedProcess? = null
    try {
        val accepted = admission {
            context.ensureActive()
            check(owned == null) { "Process creation was requested more than once" }
            create().also {
                owned = it
                context[SessionHostedProcessOwner]?.retain(it)
                ExitHostedProcessRegistry.retain(it)
            }
        }
        check(accepted === owned) { "Process admission returned an unowned handle" }
        context.ensureActive()
        return accepted
    } catch (failure: Throwable) {
        owned?.let { process ->
            try {
                OwnedResourceCleanup.run({ ExitHostedProcessRegistry.retain(process) }, process::close).getOrThrow()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
        throw failure
    }
}

internal suspend fun handoffLocalHostedProcess(
    create: suspend CoroutineScope.() -> LocalHostedProcess,
): LocalHostedProcess {
    var owned: LocalHostedProcess? = null
    try {
        return withContext(Dispatchers.IO) { create().also {
            owned = it
            // Register before the dispatch handoff can discard a cancelled return value.
            currentCoroutineContext()[SessionHostedProcessOwner]?.retain(it)
            ExitHostedProcessRegistry.retain(it)
        } }
    } catch (failure: Throwable) {
        // The return handoff can discard a created handle when its caller is cancelled.
        try { owned?.close() } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

internal class LocalHostedProcess(
    private val process: Process,
    private val scope: CoroutineScope,
    private val cleanup: () -> Unit,
) : HostedStdioProcess {
    private val closed = AtomicBoolean(false)
    private val cleanupStarted = AtomicBoolean(false)
    private val leaseCleanup = OwnedLeaseCleanupAttempt(cleanup)
    private val completed = CompletableDeferred<Int>()
    private val waiter: Job
    private val cleanupWorker: Job
    private val closeWorker: Job
    @Volatile private var code: Int? = null
    @Volatile private var waitFailure: Throwable? = null
    @Volatile private var closeFailure: Throwable? = null
    @Volatile private var processPipesClosed = false
    override val stdin get() = process.outputStream
    override val stdout get() = process.inputStream
    override val stderr get() = process.errorStream
    override val isAlive get() = process.isAlive
    override val exitCode get() = code
    override suspend fun awaitExit(): Int {
        try { return completed.await() }
        finally {
            waiter.join()
            cleanupWorker.join()
            if (closed.get()) {
                closeWorker.join()
                closeFailure?.let { throw IllegalStateException("Owned process pipe close failed", it) }
            }
        }
    }
    init {
        // One retained cleanup job, including when parent cancellation prevents waiter startup.
        cleanupWorker = scope.launch(NonCancellable + Dispatchers.IO, start = CoroutineStart.LAZY) {
            releaseOwnedProcess()
        }
        closeWorker = scope.launch(NonCancellable + Dispatchers.IO, start = CoroutineStart.LAZY) {
            cleanupWorker.join()
            if (!processPipesClosed) runCatching {
                LocalProcessSupervisor.terminate(process)
                processPipesClosed = true
            }.onFailure { closeFailure = it }
        }
        // Never run a blocking wait undispatched in the host's start() call: the caller needs these
        // streams while its LSP/DAP/Agent peer is alive.
        waiter = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                code = runInterruptible { process.waitFor() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                waitFailure = failure
            } finally {
                scheduleCleanup()
            }
        }
        // Parent cancellation may win before a lazy coroutine ever enters its body. That must still reap
        // the already-created OS process.
        waiter.invokeOnCompletion {
            if (!completed.isCompleted) scheduleCleanup()
        }
        waiter.start()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        ExitHostedProcessRegistry.retain(this)
        waiter.cancel()

        scheduleCleanup()
        closeWorker.start()
    }

    private fun scheduleCleanup() { cleanupWorker.start() }

    private fun releaseOwnedProcess() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        var failure = waitFailure
        try { leaseCleanup.observe() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        try {
            // Natural exit leaves unread stdout/stderr available for protocol readers. Explicit stop,
            // cancellation, and transport failure close the streams after terminating the owned process.
            if (process.isAlive || closed.get() || waiter.isCancelled || waitFailure != null) {
                LocalProcessSupervisor.terminate(process)
                processPipesClosed = true
            }
            check(!process.isAlive) { "Owned process still reports live after termination" }
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        } finally {
            code = code ?: if (waitFailure == null) 143 else -1
            // A failed lease cleanup is observable by the process owner, not an uncaught launch exception
            // capable of crashing the Android IDE.
            if (failure == null) completed.complete(code ?: 143)
            else completed.completeExceptionally(IllegalStateException("Owned process lifecycle failed", failure))
        }
    }
}

/** A failed wrapper may have partially started workers, so this fallback only reaps known
 * resources: it NEVER certifies workspace custody or clears the unknown-factory fence. */
internal fun createOwnedLocalHostedProcess(
    start: () -> Process,
    scope: CoroutineScope,
    cleanup: () -> Unit,
    wrap: (Process, CoroutineScope, () -> Unit) -> LocalHostedProcess = ::LocalHostedProcess,
    retainFailure: (HostedStdioProcess) -> Unit = ExitHostedProcessRegistry::retain,
): LocalHostedProcess {
    val fallback = FailedLocalProcessConstruction()
    val raw = start()
    fallback.adopt(raw)
    try { return wrap(raw, scope, cleanup) }
    catch (failure: Throwable) {
        fallback.failure = failure
        retainFailure(fallback)
        throw failure
    }
}

/** Retained retries use the same Process object, never a rediscovered PID. Even a successful
 * teardown cannot prove absence of partial-wrapper workers/descendants. */
private class FailedLocalProcessConstruction : HostedStdioProcess {
    private lateinit var process: Process
    lateinit var failure: Throwable
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var attempt: Deferred<Unit>? = null
    fun adopt(raw: Process) { check(!::process.isInitialized); process = raw }
    override val stdin get() = process.outputStream
    override val stdout get() = process.inputStream
    override val stderr get() = process.errorStream
    override val isAlive get() = process.isAlive
    override val exitCode: Int? get() = runCatching { process.exitValue() }.getOrNull()
    override fun close() = Unit
    override suspend fun awaitExit(): Int = withContext(NonCancellable) {
        val pending = synchronized(this@FailedLocalProcessConstruction) {
            attempt?.takeUnless { it.isCompleted && it.isCancelled } ?: cleanupScope.async {
                reapKnownChild()
            }.also { attempt = it }
        }
        // Process.destroy itself can block closing a pipe held by another reader. Timeout only
        // observation, never cancel/replace a still-running attempt.
        withTimeout(2_000) { pending.await() }
        throw IllegalStateException("Raw child reaped after wrapper failure; complete workspace cleanup remains unconfirmed", failure)
    }
    private fun reapKnownChild() {
        // Do not retry lease cleanup: a partially initialized wrapper may own it. Reap before touching
        // streams whose monitors may belong to blocked readers.
        if (process.isAlive) process.destroy()
        if (!process.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                "Raw child did not exit after wrapper failure"
            }
        }
        check(!process.isAlive) { "Raw child still alive after wrapper failure" }
        process.waitFor()
        // Do not explicitly close pipes: partial-wrapper readers or escaped descendants can still hold
        // them. All custody stays retained.
    }
}
