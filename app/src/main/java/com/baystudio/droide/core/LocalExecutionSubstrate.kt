package com.baystudio.droide.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SubstrateState(
    val ready: Boolean = false,
    val prootAvailable: Boolean = false,
    val ubuntuAvailable: Boolean = false
)

object LocalExecutionSubstrate {
    private val _state = MutableStateFlow(SubstrateState())
    val state: StateFlow<SubstrateState> = _state

    private lateinit var filesDir: File
    private lateinit var appContext: Context
    private val processToken = UUID.randomUUID().toString().replace("-", "")
    /** Legacy native leases are retained: a filename/token cannot recover native ownership. */
    @Volatile internal var unresolvedNativePtyLeaseCount: Int = 0
        private set

    internal fun processIdentity(): String = processToken

    internal fun processBootIdentity(working: File): WorkspaceProcessBootIdentity.Identity? =
        if (::appContext.isInitialized) WorkspaceProcessBootIdentity.forApplication(appContext, working) else null

    internal fun localTerminalPromptEnvironment(): Map<String, String> =
        if (::filesDir.isInitialized && File("/system/bin/sh").canExecute()) {
            TerminalPromptPresentation.androidEnvironment(File(filesDir, "terminal-home/presentation"))
        } else mapOf("PS1" to TerminalPromptPresentation.COMPACT_PROMPT)

    fun init(context: Context) = init(context, {})

    internal fun init(context: Context, checkCancelled: () -> Unit) {
        checkCancelled()
        val requested = context.applicationContext.filesDir.canonicalFile
        if (::filesDir.isInitialized) {
            check(filesDir.canonicalFile == requested) { "Cannot rebind the local execution substrate to another application root" }
        }
        filesDir = requested
        appContext = context.applicationContext
        val guest = ubuntuGuestRoot()
        val inspected = SubstrateState(
            // File presence and ELF admission do not certify the local execution gate.
            ready = false,
            prootAvailable = PackagedLinuxEngine.unavailableReason(appContext, checkCancelled) == null,
            ubuntuAvailable = File(guest, "rootfs").isDirectory
        )
        checkCancelled()
        _state.value = inspected
    }

    private fun ubuntuGuestRoot(): File = File(filesDir, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}")

    // Reconciles command-only leases left by an older app process. Native PTY leases remain
    // unresolved; numeric legacy cleanup has no native identity guarantee.
    suspend fun reconcileStaleLocalProcesses(): Int = withContext(Dispatchers.IO) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        val marker = File(filesDir, "run/.app-process")
        val previous = runCatching {
            marker.takeIf { it.isFile && !PathSecurity.isSymbolicLink(it) && it.length() in 1..128 }
                ?.readText(Charsets.UTF_8)?.trim()
        }.getOrNull()
        if (previous == processToken) return@withContext 0
        val runDir = File(filesDir, "run").apply { mkdirs() }
        check(runDir.isDirectory && !PathSecurity.isSymbolicLink(runDir)) { "Local process lease directory is unavailable" }
        val staleLeases = runDir.listFiles { file ->
            file.name.endsWith(".pid") && (NativePtyCleanupReceipt.isLegacyLeaseName(file.name) ||
                file.isFile || PathSecurity.isSymbolicLink(file))
        }.orEmpty()
        unresolvedNativePtyLeaseCount = staleLeases.count { NativePtyCleanupReceipt.isLegacyLeaseName(it.name) }
        val staleCount = staleLeases.size - unresolvedNativePtyLeaseCount
        if (unresolvedNativePtyLeaseCount > 0) {
            Log.w("DroideTerminal", "Retained $unresolvedNativePtyLeaseCount historical native PTY lease(s); process cleanup is unresolved. No numeric signal or file deletion was attempted for these leases.")
        }
        if (staleCount > 0) {
            val result = LocalProcessSupervisor.capture(
                argv = listOf(if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "/bin/sh", "-c", RemoteProcessLease.reapAllLocalCommand()),
                cwd = filesDir,
                maxOutputBytes = 16_384,
                timeoutMs = 15_000,
            )
            check(!result.timedOut) { "Stale local process cleanup timed out" }
            check(result.exitCode == 0) {
                "Could not reconcile stale local processes (exit ${result.exitCode}): ${result.output.takeLast(2_000)}"
            }
            result.output.lineSequence()
                .firstOrNull { it.startsWith(NativePtyCleanupReceipt.LEGACY_UNRESOLVED_PREFIX) }
                ?.removePrefix(NativePtyCleanupReceipt.LEGACY_UNRESOLVED_PREFIX)?.toIntOrNull()
                ?.takeIf { it >= 0 }?.let { unresolvedNativePtyLeaseCount = it }
            Log.i("DroideTerminal", "Local stale-lease scan finished; $unresolvedNativePtyLeaseCount native PTY lease(s) remain unresolved.")
        }
        check(!PathSecurity.isSymbolicLink(marker)) { "Local process marker is unsafe" }
        val temp = File(runDir, ".app-process.${UUID.randomUUID().toString().take(8)}.tmp")
        check(!PathSecurity.isSymbolicLink(temp)) { "Local process marker temp path is unsafe" }
        try {
            java.io.FileOutputStream(temp).use { output ->
                output.write((processToken + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                java.nio.file.Files.move(
                    temp.toPath(), marker.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(temp.toPath(), marker.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
        staleCount
    }

    suspend fun inspectLinuxState(): SubstrateState = withContext(Dispatchers.IO) {
        if (!::appContext.isInitialized) return@withContext SubstrateState()
        val inspectionContext = currentCoroutineContext()
        val engine = PackagedLinuxEngine.unavailableReason(appContext) { inspectionContext.ensureActive() } == null
        val installed = File(ubuntuGuestRoot(), "rootfs").isDirectory
        val healthy = engine && installed && runSuspendCatching {
            FoundryUbuntuGuestEnvironmentManager(appContext).isHealthy()
        }.getOrDefault(false)
        SubstrateState(healthy, engine, installed).also { inspectionContext.ensureActive(); _state.value = it }
    }

    fun linuxLaunchSpec(workDir: File, alpine: Boolean = false, workspaceRoot: File = workDir, toolchainWorkspaceRoot: File = workspaceRoot): TerminalLaunchSpec =
        buildLinuxLaunchSpec(workDir, alpine, workspaceRoot, toolchainWorkspaceRoot, null, {})

    /** Observe user environment without changing the caller's existing runtime preparation policy. */
    internal suspend fun userToolchainLaunchSpec(workDir: File, workspaceRoot: File = workDir,
        toolchainWorkspaceRoot: File = workspaceRoot): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        resolveUserToolchainLaunch(workDir, workspaceRoot, toolchainWorkspaceRoot) {
            linuxLaunchSpec(workDir, workspaceRoot = workspaceRoot, toolchainWorkspaceRoot = toolchainWorkspaceRoot)
        }.spec
    }

    internal suspend fun userToolchainProbeLaunchSpec(workDir: File): LocalUserToolchainEnvironment.Resolution = withContext(Dispatchers.IO) {
        resolveUserToolchainLaunch(workDir, workDir, workDir) { linuxLaunchSpec(workDir) }
    }

    internal suspend fun prepareUserToolchainLaunchSpec(workDir: File, workspaceRoot: File = workDir,
        toolchainWorkspaceRoot: File = workspaceRoot): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        resolveUserToolchainLaunch(workDir, workspaceRoot, toolchainWorkspaceRoot) {
            prepareUbuntuLaunchSpec(workDir, workspaceRoot, toolchainWorkspaceRoot)
        }.spec
    }

    /** Refresh an IDE task's subprocess, never the user's already-running interactive terminal. */
    internal suspend fun workspaceToolchainLaunchSpec(current: TerminalLaunchSpec, directory: File,
        workspaceRoot: File): TerminalLaunchSpec? {
        if (!::appContext.isInitialized || current.shell != "/bin/bash") return null
        val rootIndex = current.prefix.indexOf("-r")
        val currentRoot = current.prefix.getOrNull(rootIndex + 1).takeIf { rootIndex >= 0 } ?: return null
        if (File(currentRoot).canonicalFile != File(ubuntuGuestRoot(), "rootfs").canonicalFile) return null
        return prepareUserToolchainLaunchSpec(directory, workspaceRoot, workspaceRoot)
    }

    private suspend fun resolveUserToolchainLaunch(workDir: File, workspaceRoot: File,
        toolchainWorkspaceRoot: File, prepare: suspend () -> TerminalLaunchSpec): LocalUserToolchainEnvironment.Resolution {
        check(::appContext.isInitialized) { "Local substrate is not initialized" }
        val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val selected = preferences.selections()
        val base = prepare()
        val resolved = LocalUserToolchainEnvironment.resolveDetailed(base, workDir, workspaceRoot, selected)
        currentCoroutineContext().ensureActive()
        check(preferences.selections() == selected) { "Toolchain selections changed during environment discovery; retry" }
        return resolved
    }

    private fun buildLinuxLaunchSpec(workDir: File, alpine: Boolean, workspaceRoot: File, toolchainWorkspaceRoot: File,
        attempt: PackagedNativeAdmissionAttempt?, checkCancelled: () -> Unit, cmakeOwner: LocalCMakePreparedOwner? = null, nimOwner: LocalNimPreparedOwner? = null, vueOwner: LocalVuePreparedOwner? = null, csharpOwner: LocalCSharpPreparedOwner? = null, csharpHealth: CSharpHealthPrivateState? = null, csharpHealthStage: File? = null, tomlOwner: LocalTomlPreparedOwner? = null, tomlHealth: TomlHealthPrivateState? = null, tomlHealthStage: File? = null, graphqlOwner: LocalGraphqlPreparedOwner? = null, graphqlHealth: GraphqlHealthPrivateState? = null, graphqlHealthStage: File? = null, graphqlDeadline: GraphqlDeadline? = null): TerminalLaunchSpec {
        if (alpine) LegacyAlpineRuntimeRetirement.reject()
        checkCancelled()
        check(attempt == null || !alpine) { "Complete native admission belongs to Ubuntu preparation" }
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        check(android.os.Process.is64Bit() && android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
            "Local Linux currently requires an ARM64 Android process"
        }
        val guest = if (alpine) File(filesDir, "guest/${WorkstationGuestEnvironmentSpec.ROOT_DIR}") else ubuntuGuestRoot()
        val rootfs = File(guest, "rootfs")
        check(rootfs.isDirectory && File(rootfs, if (alpine) "bin/busybox" else "usr/bin/bash").isFile) {
            "Local Ubuntu PC Environment is not installed"
        }
        val hardLinkStore = File(rootfs, ".l2s")
        check(!PathSecurity.isSymbolicLink(hardLinkStore) && (hardLinkStore.isDirectory || hardLinkStore.mkdir())) {
            "Local Linux hard-link backing directory is unavailable"
        }
        val lock = File(guest, "BOOTSTRAP_LOCK.txt")
        check(lock.isFile && lock.length() <= 64 * 1024 && lock.readLines().contains(
            if (alpine) "environment=${WorkstationGuestEnvironmentSpec.ID}" else "profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}"
        )) { "Local Ubuntu PC Environment has not completed activation" }
        val workspace = workspaceRoot.canonicalPath
        val cwd = workDir.canonicalPath
        require(PathSecurity.contains(workspaceRoot, workDir) && workDir.isDirectory) { "Linux cwd escaped workspace" }
        require(':' !in workspace) { "Workspace path cannot be represented by a PRoot bind" }
        val temp = File(filesDir, "runtime-tmp").apply { mkdirs() }
        check(temp.isDirectory) { "Local runtime temporary directory is unavailable" }
        val packageRoot = File(filesDir, "packages").apply { mkdirs() }
        val managedUbuntuBin = File(filesDir, "managed/ubuntu-bin").apply { mkdirs() }
        val workspaceToolchains = if (alpine) null else WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val admitted = attempt?.require(checkCancelled)
        val cliBinary = if (alpine) null else (admitted?.cli ?: DroideCliClientInstaller.ensureInstalled(appContext))
        val cliDiscovery = workspaceToolchains?.let { File(filesDir, "cli/${it.workspaceId}").apply { mkdirs() } }
        if (!alpine) {
            val guestManagedPackages = File(rootfs, "opt/droide/packages").apply { mkdirs() }
            val guestManagedBin = File(rootfs, "opt/droide/bin").apply { mkdirs() }
            val guestCoreBin = File(rootfs, "opt/droide/core-bin").apply { mkdirs() }
            val guestCli = File(rootfs, "opt/droide/cli").apply { mkdirs() }
            check(packageRoot.isDirectory && managedUbuntuBin.isDirectory && cliBinary?.isFile == true && cliDiscovery?.isDirectory == true &&
                guestManagedPackages.isDirectory && guestManagedBin.isDirectory && guestCoreBin.isDirectory && guestCli.isDirectory) {
                "Local managed-package/CLI projection is unavailable"
            }
        }

        checkCancelled()
        val launcher = SystemLinkerExec.wrap((admitted?.launcher ?: PackagedLinuxEngine.requireReady(appContext)).absolutePath, emptyList())
        checkCancelled()
        if (cmakeOwner != null) {
            check(!alpine)
            val finalAdmission = attempt?.require(checkCancelled)
            return buildReviewedCMakeLaunchSpec(cmakeOwner, launcher, rootfs, workspace, temp, packageRoot, hardLinkStore,
                finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext), checkCancelled)
        }
        if (nimOwner != null) {
            check(!alpine && cmakeOwner == null)
            val finalAdmission = attempt?.require(checkCancelled)
            return buildReviewedNimLaunchSpec(nimOwner, launcher, rootfs, workspace, temp, packageRoot, hardLinkStore,
                finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext), checkCancelled)
        }
        if (vueOwner != null) {
            check(!alpine && cmakeOwner == null && nimOwner == null)
            val finalAdmission = attempt?.require(checkCancelled)
            return buildReviewedVueLaunchSpec(vueOwner, launcher, rootfs, workspace, temp, packageRoot, hardLinkStore,
                finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext), checkCancelled)
        }
        if (graphqlOwner != null) {
            require(!alpine && cmakeOwner==null && nimOwner==null && vueOwner==null && csharpOwner==null && tomlOwner==null && graphqlHealth==null)
            requireNotNull(graphqlDeadline).check();val finalAdmission=attempt?.require(checkCancelled)
            val loaderEnvironment=finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)
            return buildReviewedGraphqlLaunchSpec(graphqlOwner,launcher,rootfs,temp,packageRoot,hardLinkStore,loaderEnvironment,checkCancelled)
        }
        if (graphqlHealth != null) {
            require(!alpine && cmakeOwner==null && nimOwner==null && vueOwner==null && csharpOwner==null && tomlOwner==null && graphqlOwner==null)
            requireNotNull(graphqlDeadline).check();val finalAdmission=attempt?.require(checkCancelled);graphqlHealth.verify();val stage=requireNotNull(graphqlHealthStage)
            val loaderEnvironment=finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)
            val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,"-b","/dev","-b","/proc","-b","/sys",
                "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${graphqlHealth.localRoot.absolutePath}:${graphqlHealth.guestRoot}","-b","${stage.absolutePath}:${if(graphqlHealth.serverHealth)LspGraphqlLaunchPolicy.ROOT else LspGraphqlLaunchPolicy.NODE_ROOT}",
                "-w","${graphqlHealth.guestRoot}/workspace","/usr/bin/env","-i")+LspGraphqlLaunchPolicy.privateEnvironment(graphqlHealth.guestRoot,graphqlHealth.nodeExecutable).map{(k,v)->"$k=$v"}
            checkCancelled();return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
        }
        if (tomlOwner != null) {
            check(!alpine && cmakeOwner == null && nimOwner == null && vueOwner == null && csharpOwner == null)
            val finalAdmission = attempt?.require(checkCancelled)
            return buildReviewedTomlLaunchSpec(tomlOwner, launcher, rootfs, workspace, temp, packageRoot, hardLinkStore,
                finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext), checkCancelled)
        }
        if (tomlHealth != null) {
            check(!alpine && cmakeOwner == null && nimOwner == null && vueOwner == null && csharpOwner == null && tomlOwner == null)
            val finalAdmission=attempt?.require(checkCancelled);tomlHealth.verify();val stage=requireNotNull(tomlHealthStage)
            require(stage.canonicalPath==stage.absolutePath && ':' !in stage.absolutePath)
            val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,
                "-b","/dev","-b","/proc","-b","/sys","-b","${stage.absolutePath}:${stage.absolutePath}",
                "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${tomlHealth.localRoot.absolutePath}:${tomlHealth.guestRoot}",
                "-w",tomlHealth.guestRoot,"/usr/bin/env","-i")+LspTomlLaunchPolicy.privateEnvironment(tomlHealth.guestRoot,tomlHealth.nodeExecutable).map{(k,v)->"$k=$v"}
            return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),
                environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+(finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)))
        }
        if (csharpOwner != null) {
            check(!alpine && cmakeOwner == null && nimOwner == null && vueOwner == null)
            val finalAdmission = attempt?.require(checkCancelled)
            return buildReviewedCSharpLaunchSpec(csharpOwner, launcher, rootfs, workspace, temp, packageRoot, hardLinkStore,
                finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext), checkCancelled)
        }
        if (csharpHealth != null) {
            check(!alpine && cmakeOwner == null && nimOwner == null && vueOwner == null && csharpOwner == null)
            val finalAdmission=attempt?.require(checkCancelled)
            csharpHealth.verify()
            val stage=requireNotNull(csharpHealthStage)
            require(stage.canonicalPath==stage.absolutePath && ':' !in stage.absolutePath)
            val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,
                "-b","/dev","-b","/proc","-b","/sys","-b","${stage.absolutePath}:${stage.absolutePath}",
                "-b","${packageRoot.absolutePath}:/opt/droide/packages",
                "-b","${csharpHealth.localRoot.absolutePath}:${csharpHealth.guestRoot}",
                "-b","${File(csharpHealth.localRoot,"tmp").absolutePath}:/tmp",
                "-w",csharpHealth.guestWorkspace,"/usr/bin/env","-i")+
                LspCSharpLaunchPolicy.privateEnvironment(csharpHealth.guestRoot).map { (k,v)->"$k=$v" }
            return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),
                environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+
                    (finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)))
        }
        val managedRecords = if (alpine) emptyList() else ManagedPackageRegistry(appContext).list()
        val workspaceSelections = workspaceToolchains?.selections().orEmpty()
        val managedGuestPaths = if (alpine) emptyList() else LocalLinuxPackageEnvironment.guestBinPaths(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        val managedGuestEnvironment = if (alpine) emptyMap() else LocalLinuxPackageEnvironment.guestEnvironment(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        val androidSdkBindings = if (alpine) emptyList() else LocalAndroidSdkComponentEnvironment.bindings(
            records = managedRecords,
            workspaceSelections = workspaceSelections,
            appRoot = filesDir.absolutePath,
        )
        if (!alpine && androidSdkBindings.isNotEmpty()) {
            LocalAndroidSdkComponentEnvironment.prepareGuestTargets(rootfs, androidSdkBindings)
        }
        val injectedGuestEnvironment = LocalLinuxUserEnvironment.guestEnvironment(
            managedGuestEnvironment + LocalAndroidSdkComponentEnvironment.environment(androidSdkBindings),
            workspaceSelections,
            if (alpine) null else LocalLinuxUserEnvironment.installedUserAndroidSdkRoot(rootfs),
        )

        val finalAdmission = attempt?.require(checkCancelled)
        val prefix = buildList {
            addAll(launcher)
            addAll(listOf("-0", "--link2symlink", "--kill-on-exit", "-r", rootfs.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys"))
            addAll(listOf("-b", "$workspace:$workspace", "-b", "${temp.absolutePath}:/tmp"))
            if (!alpine) {
                addAll(listOf("-b", "${packageRoot.absolutePath}:/opt/droide/packages"))
                addAll(listOf("-b", "${managedUbuntuBin.absolutePath}:/opt/droide/bin"))
                addAll(listOf("-b", "${requireNotNull(cliBinary).absolutePath}:/opt/droide/core-bin/droide"))
                addAll(listOf("-b", "${requireNotNull(cliDiscovery).absolutePath}:/opt/droide/cli"))
                androidSdkBindings.forEach { binding ->
                    addAll(listOf("-b", "${binding.hostPath}:${binding.guestPath}"))
                }
            }
            addAll(listOf("-w", cwd, "/usr/bin/env", "-i", "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8"))
            add("TERM=xterm-256color")
            add(if (alpine) "SHELL=/bin/sh" else "SHELL=/bin/bash")
            injectedGuestEnvironment.toSortedMap().forEach { (key, value) -> add("$key=$value") }
            val managedBins = listOf("/opt/droide/core-bin") + managedGuestPaths + "/opt/droide/bin"
            val workspaceBins = if (alpine) emptyList() else LocalLinuxPackageEnvironment.guestBinPaths(
                managedRecords.filter { workspaceSelections[it.familyId] == it.version }, workspaceSelections, filesDir.absolutePath)
            add("PATH=" + LocalLinuxUserEnvironment.path(if (alpine) emptyList() else managedBins,
                if (alpine) emptyList() else listOf("/opt/droide/core-bin") + workspaceBins))
        }
        val spec = TerminalLaunchSpec(
            prefix = prefix,
            shell = if (alpine) "/bin/sh" else "/bin/bash",
            interactiveArgs = if (alpine) listOf("-i") else TerminalPromptPresentation.bashInteractiveArgs(rootfs),
            interactiveEnvironment = if (alpine) TerminalPromptPresentation.ashEnvironment(rootfs)
                else TerminalPromptPresentation.bashStartupEnvironment(),
            environment = mapOf("PROOT_TMP_DIR" to temp.absolutePath, "PROOT_NO_SECCOMP" to "1", "PROOT_L2S_DIR" to hardLinkStore.absolutePath) +
                (finalAdmission?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)),
        )
        checkCancelled()
        return spec
    }

    suspend fun ensureLinuxReady(workDir: File): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        prepareUbuntuLaunchSpec(workDir).also {
            currentCoroutineContext().ensureActive()
            _state.value = SubstrateState(true, true, true)
        }
    }

    internal suspend fun runCSharpStagedHealth(appRoot:String,stage:File,sdk:ManagedPackageRecord,helper:ManagedPackageRecord):ExecResult = withContext(Dispatchers.IO) {
        check(::filesDir.isInitialized && ::appContext.isInitialized && filesDir.absolutePath==appRoot)
        require(sdk.familyId=="runtime.csharp-sdk"&&sdk.version=="10.0.402"&&helper.familyId=="runtime.csharp-helper"&&helper.version=="22.23.3")
        require(stage==LocalPackageInstallJournal.paths(appRoot,"lsp.csharp-ls","0.28.0",LocalUbuntuCSharpPolicy.KIND).stage)
        val owner=CSharpHealthPrivateState.allocate(filesDir)
        // Retained directory is the durable UNKNOWN witness if preparation/capture fails.
        val context=currentCoroutineContext();val active={context.ensureActive()}
        val spec=prepareUbuntuLaunchSpec(owner.localWorkspace,owner.localWorkspace,owner.localWorkspace,csharpHealth=owner,csharpHealthStage=stage)
        owner.verify();LocalUbuntuCSharpInstaller.proveStaged(appRoot,stage,active);LocalUbuntuCSharpInstaller.provePackage(appRoot,sdk,active);LocalUbuntuCSharpInstaller.provePackage(appRoot,helper,active)
        val lease=RemoteProcessLease.createLocal("csharp-health")
        val payload=CSharpResourcePolicy.HEALTH.shellPrefix()+listOf(LspCSharpLaunchPolicy.HELPER)+CSharpResourcePolicy.HEALTH.helperArguments+
            listOf("${stage.absolutePath}/${LspCSharpLaunchPolicy.HEALTH}","--health",stage.absolutePath,LspCSharpLaunchPolicy.SDK_ROOT,owner.guestRoot)
        val command=spec.prefix+lease.environment.map { (k,v)->"$k=$v" }+payload
        active()
        val process=ProcessBuilder("/system/bin/sh","-c",lease.wrap(command.joinToString(" ",transform=::shellQuote)))
            .directory(owner.localWorkspace).redirectErrorStream(true).apply { environment().clear();environment().putAll(spec.environment) }.start()
        val result=LocalProcessSupervisor.captureOwned(process,maxOutputBytes=1_048_576,timeoutMs=LocalUbuntuCSharpLspHealth.TIMEOUT_MS,cleanup={terminateLease(lease)})
        if(LocalUbuntuCSharpLspHealth.cleanupVerified(result)&&owner.localRoot.exists()) result.copy(exitCode=70,output=result.output+"\nC# private cleanup not confirmed") else result
    }

    internal suspend fun runTomlStagedHealth(appRoot:String,stage:File,node:ManagedPackageRecord?,hold:LocalPackageInstallJournal.TomlHealthHold):ExecResult=withContext(Dispatchers.IO){
        val deadline=TomlDeadline.after(LocalUbuntuTomlLspHealth.TIMEOUT_MS)
        check(::filesDir.isInitialized&&::appContext.isInitialized&&filesDir.absolutePath==appRoot)
        val family=if(node==null)"runtime.taplo-node" else "lsp.taplo-wasm"
        require(stage==LocalPackageInstallJournal.paths(appRoot,family,LocalUbuntuTomlPolicy.version(family),LocalUbuntuTomlPolicy.kind(family)).stage)
        if(node!=null)require(node.familyId=="runtime.taplo-node"&&node.version=="22.23.3")
        val executable=if(node==null)"${stage.absolutePath}/payload/tree/node-v22.23.3-linux-arm64/bin/node" else LspTomlLaunchPolicy.NODE
        val context=currentCoroutineContext();val active={context.ensureActive();deadline.reserve(TomlResourcePolicy.RETIRE_MS).check()}
        active();val owner=TomlHealthPrivateState.allocate(filesDir,executable);LocalPackageInstallJournal.bindTomlHealthOwner(hold,owner.localRoot.absolutePath,owner.token,owner.identity)
        // Preparation/proof share this deadline. Filesystem operations are cooperatively checked; an
        // uninterruptible filesystem syscall is not claimed to have a hard JVM wall bound.
        val spec=withTimeout(deadline.reserve(TomlResourcePolicy.RETIRE_MS).remainingMillis().coerceAtLeast(1)){prepareUbuntuLaunchSpec(owner.localWorkspace,owner.localWorkspace,owner.localWorkspace,tomlHealth=owner,tomlHealthStage=stage)}
        active();owner.verify();LocalUbuntuTomlInstaller.proveStaged(appRoot,stage,active);node?.let{LocalUbuntuTomlInstaller.provePackage(appRoot,it,active)};active()
        val args=if(node==null)listOf("${stage.absolutePath}/${LspTomlLaunchPolicy.NODE_HEALTH}","--node-health",owner.guestRoot)else listOf("${stage.absolutePath}/${LspTomlLaunchPolicy.HEALTH}",stage.absolutePath,owner.guestRoot)
        // The helper receives only the remaining work budget; the captured host deadline remains
        // authoritative across process creation, every helper phase, drain and cleanup.
        val command=spec.prefix+TomlResourcePolicy.HEALTH.shellPrefix()+listOf(executable)+TomlResourcePolicy.HEALTH.nodeArguments+args+deadline.reserve(TomlResourcePolicy.RETIRE_MS).remainingMillis().toString()
        active();val process=ProcessBuilder("/system/bin/sh","-c",owner.shellCommand(command)).directory(owner.localRoot).redirectErrorStream(true).apply{environment().clear();environment().putAll(spec.environment)}.start()
        TomlHealthCapture.capture(process,deadline,owner::observe,owner::terminateExact){owner.finish(process)}
    }

    /** Guest-view inspection is byte-only and never installs or boots a selected tool. */
    private fun elpFiles(workspace: File? = null): ElpGuestFilesystem {
        check(::filesDir.isInitialized && ::appContext.isInitialized)
        val rootfs = File(ubuntuGuestRoot(), "rootfs")
        require(rootfs.isDirectory && rootfs.canonicalFile == rootfs.absoluteFile) { "ELP requires an already installed local Ubuntu guest" }
        return ElpGuestFilesystem(rootfs, workspace, LspElpLaunchPolicy.WORKSPACE_ROOT)
    }
    internal suspend fun inspectElpSelection(workspace: File, rootPath: String, deadline: ElpDeadline): ElpSelectionInspection = withContext(Dispatchers.IO) {
        deadline.check(); ElpMetadataInspection.inspect(elpFiles(workspace), workspace.canonicalPath, rootPath, deadline)
    }
    internal suspend fun observeElpPaths(inspection: ElpSelectionInspection, guestPaths: List<String>, deadline: ElpDeadline): Map<String, ElpObservedFile> = withContext(Dispatchers.IO) {
        require(guestPaths.size <= 4096 && guestPaths.distinct().size == guestPaths.size)
        val files = elpFiles(File(inspection.workspaceRoot)); var total = 0L
        guestPaths.associateWith { path ->
            require(path == inspection.guestWorkspaceRoot || path.startsWith(inspection.guestWorkspaceRoot + "/"))
            files.observe(path, deadline).also { total += it.bytes?.size ?: 0; require(total <= 8_388_608) }
        }
    }
    internal suspend fun inspectElpHealthSelection(deadline: ElpDeadline): ElpToolSelection = withContext(Dispatchers.IO) {
        val files = elpFiles(); deadline.check()
        val erl = requireNotNull(files.candidate("erl", deadline)) { "No selected preinstalled erl" }
        val escript = requireNotNull(files.candidate("escript", deadline)) { "No selected preinstalled escript" }
        require(erl.installationRoot == escript.installationRoot)
        ElpToolSelection(erl, escript)
    }
    private fun elpAsset(name: String): ByteArray {
        require(name in setOf(LspElpLaunchPolicy.REPORT, LspElpLaunchPolicy.OBSERVER, LspElpLaunchPolicy.SHIM))
        val bytes = appContext.assets.open("lsp/elp/$name").use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 262_144); out.write(buffer, 0, count) }
            out.toByteArray()
        }
        require(LspElpLaunchPolicy.sha(bytes) == LocalUbuntuElpPolicy.helperHashes.getValue("payload/$name"))
        return bytes
    }
    /** This builds only an already admitted host engine launch; it never calls ensureUbuntuInstalled. */
    private suspend fun elpLaunchSpec(owner: ElpExecutionOwner, selection: ElpToolSelection?, workspace: File?,
        cwd: String, deadline: ElpDeadline, stage: File? = null): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        deadline.check(); owner.verifyPrivate(); check(owner.isCurrent)
        require(android.os.Process.is64Bit() && android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val context = currentCoroutineContext()
        val active = { context.ensureActive(); deadline.check(); check(owner.isCurrent) }
        val attempt = PackagedNativeAdmissionAttempt.create(appContext, active)
        try {
            val admitted = attempt.require(active)
            val rootfs = File(ubuntuGuestRoot(), "rootfs")
            require(rootfs.isDirectory && File(rootfs, "usr/bin/bash").isFile)
            val activation = File(ubuntuGuestRoot(), "BOOTSTRAP_LOCK.txt")
            require(activation.isFile && activation.length() <= 65_536 && activation.readLines().contains("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}"))
            val hardLinks = File(rootfs, ".l2s")
            require(!PathSecurity.isSymbolicLink(hardLinks) && hardLinks.isDirectory && hardLinks.canonicalFile == hardLinks.absoluteFile)
            val packages = File(filesDir, "packages")
            require(packages.isDirectory && packages.canonicalFile == packages.absoluteFile)
            val launcher = SystemLinkerExec.wrap(admitted.launcher.absolutePath, emptyList())
            val prefix = (launcher + listOf("-0", "--link2symlink", "--kill-on-exit", "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${packages.absolutePath}:/opt/droide/packages",
                "-b", "${owner.localRoot.absolutePath}:${owner.guestRoot}")).toMutableList()
            if (workspace != null) {
                require(workspace.canonicalFile == workspace.absoluteFile && ':' !in workspace.absolutePath)
                require(cwd == LspElpLaunchPolicy.WORKSPACE_ROOT || cwd.startsWith(LspElpLaunchPolicy.WORKSPACE_ROOT + "/"))
                prefix += listOf("-b", "${workspace.absolutePath}:${LspElpLaunchPolicy.WORKSPACE_ROOT}")
            } else require(cwd == "${owner.guestRoot}/work")
            stage?.let { require(it.canonicalFile == it.absoluteFile && ':' !in it.absolutePath); prefix += listOf("-b", "${it.absolutePath}:${it.absolutePath}") }
            prefix += listOf("-w", cwd, "/usr/bin/env", "-i")
            prefix += LspElpLaunchPolicy.environment(owner.guestRoot, selection, workspace == null,
                if (workspace == null) emptyMap() else mapOf("HOME" to "/root", "PATH" to LocalLinuxUserEnvironment.path(emptyList())), owner.token).map { (key, value) -> "$key=$value" }
            active()
            TerminalLaunchSpec(prefix = prefix, shell = "/bin/bash", interactiveArgs = emptyList(), interactiveEnvironment = emptyMap(),
                environment = mapOf("PROOT_TMP_DIR" to File(owner.localRoot, "tmp").absolutePath, "PROOT_NO_SECCOMP" to "1", "PROOT_L2S_DIR" to hardLinks.absolutePath) + admitted.loaderEnvironment)
        } finally { attempt.close() }
    }
    private data class ElpCapture(val stdout: ByteArray, val stderr: ByteArray, val exitCode: Int, val cleanup: ElpTeardownReceipt)
    private suspend fun elpCapture(selection: ElpToolSelection?, purpose: String, current: () -> Boolean,
        deadline: ElpDeadline, execution: ElpDeadline, argv: (ElpExecutionOwner) -> List<String>,
        input: ByteArray = byteArrayOf(), beforeFactory: () -> Unit = {}, helper: String? = null,
        acquireActivity: (() -> ElpPackageActivityHold)? = null): ElpCapture {
        val owner = ElpExecutionOwner.allocateCapture(filesDir, purpose, current, deadline, acquireActivity)
        try {
            helper?.let { owner.write(it, elpAsset(it)) }
            val spec = elpLaunchSpec(owner, selection, null, "${owner.guestRoot}/work", execution)
            owner.beginFactory {
                execution.check(); check(current()); beforeFactory(); owner.verifyPrivate()
                val command = spec.prefix + ElpResourcePolicy.HEALTH.shellPrefix() + argv(owner)
                val builder = ProcessBuilder("/system/bin/sh", "-c", owner.shellCommand(command)).directory(owner.localRoot)
                    .apply { environment().clear(); environment().putAll(spec.environment) }

                owner.create { execution.check(); check(current()); builder.start() }
            }
            val process = owner.awaitFactory(execution); owner.awaitBirth(execution)
            fun collect(stream: InputStream, maximum: Int): ByteArray {
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = stream.read(buffer); if (count < 0) break; require(out.size() + count <= maximum); out.write(buffer, 0, count) }
                return out.toByteArray()
            }
            val output = owner.operation("probe-stdout") { collect(process.stdout, if (helper == LspElpLaunchPolicy.OBSERVER) 4_194_304 else 16_384) }
            val errors = owner.operation("probe-stderr") { collect(process.stderr, ElpResourcePolicy.STDERR_BYTES_MAX) }
            if (input.isNotEmpty()) process.stdin.write(input)
            process.stdin.close(); owner.beginMonitoring()
            check(output.await(execution) && errors.await(execution)) { "ELP selected-tool probe exceeded its deadline" }
            while (process.isAlive) { execution.check(); kotlinx.coroutines.delay(10) }
            execution.check(); val code = requireNotNull(process.exitCode)
            require(process.stderrBytes <= ElpResourcePolicy.STDERR_BYTES_MAX) { "ELP selected-tool stderr overflow" }
            val stdout = output.result(); val stderr = errors.result()
            val cleanup = owner.retire(deadline)
            check(cleanup.complete) { "ELP selected-tool cleanup UNKNOWN; exact custody retained" }
            return ElpCapture(stdout, stderr, code, cleanup)
        } catch (failure: Throwable) {
            owner.fence(); val receipt = withContext(kotlinx.coroutines.NonCancellable) { owner.retire(deadline) }
            if (!receipt.complete) failure.addSuppressed(IllegalStateException("ELP selected-tool custody UNKNOWN"))
            throw failure
        }
    }
    internal suspend fun inspectElpToml(request: ElpMetadataInspectionRequest, permit: ElpMetadataInspectionPermit,
        deadline: ElpDeadline): ElpMetadataInspectionResult = withContext(Dispatchers.IO) {
        val total = deadline.limited(ElpResourcePolicy.METADATA_MS); total.check()
        require(request.tomlBytes.size <= 1_048_576 && LspElpLaunchPolicy.sha(request.tomlBytes) == request.inputSha256)
        require(request.python == request.inspection.python && request.python.role == "python")
        val files = elpFiles(File(request.inspection.workspaceRoot))
        val toml = files.capture(request.tomlGuestPath, 1_048_576, total)
        require(toml.first.sha256 == request.inputSha256 && toml.second.contentEquals(request.tomlBytes))
        ElpMetadataInspection.verifyObservation(files, request.inspection, total)
        permit.claim(request)
        val captured = elpCapture(null, "metadata:${request.identity}", { permit.isCurrent }, total,
            total.limited(ElpResourcePolicy.PYTHON_MS), { owner -> listOf(request.python.file.guestPath, "-I", "-S", "${owner.guestRoot}/${LspElpLaunchPolicy.OBSERVER}") },
            (request.inputSha256 + "\n").toByteArray() + request.tomlBytes,
            { permit.requireCurrent(request); elpFiles().verify(request.python, total) }, LspElpLaunchPolicy.OBSERVER)
        require(captured.exitCode == 0 && captured.stderr.isEmpty()) { "Selected CPython metadata observer failed" }
        permit.requireCurrent(request)
        val validated = ElpMetadataInspection.validateObserver(request, captured.stdout, elpFiles(), total)
        ElpMetadataInspectionResult(request.inputSha256, validated.second, validated.first, captured.cleanup)
    }
    private fun elpProbeActivity(identity: ElpManagedLaunchIdentity, deadline: ElpDeadline): ElpPackageActivityHold {
        deadline.check()
        LocalPackageInstallJournal.requireNoElpHealthHold(LocalPackageInstallJournal.paths(filesDir.absolutePath,
            LocalUbuntuElpPolicy.FAMILY, LocalUbuntuElpPolicy.VERSION, LocalUbuntuElpPolicy.KIND))
        deadline.check()
        return LocalUbuntuElpLaunchAuthority.acquireActivity(filesDir.absolutePath, identity.activationIdentity)
    }
    private suspend fun elpRuntimeProbes(selection: ElpToolSelection, needsRebar: Boolean,
        current: () -> Boolean, deadline: ElpDeadline, acquireActivity: (() -> ElpPackageActivityHold)?): ElpRuntimeEvidence {
        val aggregate = deadline.limited(ElpResourcePolicy.PREFLIGHT_MS); aggregate.check()
        fun validateSelection() {
            check(current()); val files = elpFiles(); files.verify(selection.erl, aggregate); files.verify(selection.escript, aggregate)
            if (needsRebar) files.verify(requireNotNull(selection.rebar), aggregate)
        }
        validateSelection()
        val helper = elpAsset(LspElpLaunchPolicy.REPORT).toString(Charsets.UTF_8)
        val expression = helper.substringAfter("main([]) ->").substringBefore(";\nmain(_)").trim().removeSuffix(";").removeSuffix(".") + "."
        require(expression.isNotEmpty() && !expression.contains("main("))
        val erl = elpCapture(selection.copy(rebar = null, python = null), "probe:erl:${selection.consentIdentity}", current, aggregate,
            aggregate.limited(ElpResourcePolicy.PROBE_MS), { listOf(selection.erl.file.guestPath, "+S1", "+A0", "+P", "1024", "+Q", "1024",
                "-boot", "no_dot_erlang", "-noshell", "-eval", expression, "-s", "erlang", "halt") }, beforeFactory = ::validateSelection, acquireActivity = acquireActivity)
        require(erl.exitCode == 0 && erl.stderr.isEmpty()) { "Selected erl compatibility probe failed" }
        val escript = elpCapture(selection.copy(rebar = null, python = null), "probe:escript:${selection.consentIdentity}", current, aggregate,
            aggregate.limited(ElpResourcePolicy.PROBE_MS), { owner -> listOf(selection.escript.file.guestPath, "${owner.guestRoot}/${LspElpLaunchPolicy.REPORT}") },
            beforeFactory = ::validateSelection, helper = LspElpLaunchPolicy.REPORT, acquireActivity = acquireActivity)
        require(escript.exitCode == 0 && escript.stderr.isEmpty()) { "Selected escript compatibility probe failed" }
        val erlReport = ElpToolPolicy.parseReport(erl.stdout); val escriptReport = ElpToolPolicy.parseReport(escript.stdout)
        val rebar = if (needsRebar) {
            val result = elpCapture(selection, "probe:rebar:${selection.consentIdentity}", current, aggregate,
                aggregate.limited(ElpResourcePolicy.PROBE_MS), { listOf(selection.escript.file.guestPath, requireNotNull(selection.rebar).file.guestPath, "version") },
                beforeFactory = ::validateSelection, acquireActivity = acquireActivity)
            require(result.exitCode == 0 && result.stderr.isEmpty())
            ElpToolPolicy.rebarVersion(result.stdout.toString(Charsets.UTF_8), erlReport.getValue("otp_release").toInt())
        } else null
        validateSelection()
        return ElpToolPolicy.validatePair(selection, erlReport, escriptReport, rebar, needsRebar, elpFiles(), aggregate)
    }
    internal suspend fun reviewedElpLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File,
        deadline: ElpDeadline): ElpManagedLaunchIdentity? = withContext(Dispatchers.IO) {
        deadline.check(); if (!::filesDir.isInitialized || !::appContext.isInitialized) return@withContext null
        val registry = ManagedPackageRegistry(appContext); val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val records = registry.list(); val selections = preferences.selections()
        val record = records.singleOrNull { it.familyId == "lsp.elp" && it.version == "2026.10.14-otp27.3" } ?: return@withContext null
        val identity = LocalUbuntuElpLaunchAuthority.inspect(filesDir.absolutePath, record, records, selections, resolvedExecutable, deadline)
        if (registry.list() == records && preferences.selections() == selections) identity else null
    }
    internal suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest,
        workspaceRoot: File, toolchainWorkspaceRoot: File, deadline: ElpDeadline): LocalElpPreparedOwner = ElpPreparedDelivery.deliver(deadline) {
        val work = deadline.reserve(ElpResourcePolicy.RETIRE_MS); work.check()
        require(LspElpLaunchPolicy.matchesIdentity(identity) && request.workspaceRoot == workspaceRoot.canonicalPath)
        require(request.observation.guestWorkspaceRoot == LspElpLaunchPolicy.WORKSPACE_ROOT)
        require(request.rootPath == request.observation.guestWorkspaceRoot || request.rootPath.startsWith(request.observation.guestWorkspaceRoot + "/"))
        val localProject = File(workspaceRoot, request.rootPath.removePrefix(request.observation.guestWorkspaceRoot).removePrefix("/"))
        require(localProject.canonicalFile == localProject.absoluteFile && localProject.isDirectory)
        require(request.workspaceLifetime.isNotBlank() && request.ownerGeneration > 0)
        request.permit.requireCurrent(request.observation, identity)
        val preflight = work.limited(ElpResourcePolicy.PREFLIGHT_MS)
        ElpMetadataInspection.verifyObservation(elpFiles(workspaceRoot), request.observation, preflight)
        val selection = requireNotNull(request.observation.tools)
        val evidence = elpRuntimeProbes(selection, request.permit.needsRebar, { request.permit.isCurrent }, preflight,
            acquireActivity = { elpProbeActivity(identity, preflight) })
        val registry = ManagedPackageRegistry(appContext); val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val packageToken = registry.elpPackageGeneration(); val selectionToken = preferences.elpSelectionGeneration()
        val records = registry.list()
        LocalUbuntuElpLaunchAuthority.prove(filesDir.absolutePath, identity, records, work)
        check(reviewedElpLaunchIdentity(identity.discoveredExecutable, toolchainWorkspaceRoot, work) == identity)
        LocalPackageInstallJournal.requireNoElpHealthHold(LocalPackageInstallJournal.paths(filesDir.absolutePath,
            LocalUbuntuElpPolicy.FAMILY, LocalUbuntuElpPolicy.VERSION, LocalUbuntuElpPolicy.KIND))
        val hold = ElpGenerationAdmission.withAdmission {
            check(registry.isElpPackageGenerationCurrent(packageToken) && preferences.isElpSelectionGenerationCurrent(selectionToken))
            LocalUbuntuElpLaunchAuthority.acquireActivity(filesDir.absolutePath, identity.activationIdentity)
        }
        var owned: ElpExecutionOwner? = null
        try {
            val current = { request.permit.isCurrent && registry.isElpPackageGenerationCurrent(packageToken) && preferences.isElpSelectionGenerationCurrent(selectionToken) }
            val execution = ElpExecutionOwner.allocate(filesDir, "analysis:${request.workspaceRoot}:${request.rootPath}", current, deadline,
                admissionCurrent = { registry.isElpPackageGenerationCurrent(packageToken) && preferences.isElpSelectionGenerationCurrent(selectionToken) }) { hold.release(it) }.also { owned = it }
            execution.write("shim/rebar3", elpAsset(LspElpLaunchPolicy.SHIM), true)
            val spec = elpLaunchSpec(execution, selection.copy(rebar = selection.rebar.takeIf { request.permit.needsRebar }), workspaceRoot, request.rootPath, work)
            LocalElpPreparedOwner(identity, request, evidence, execution, spec, preflight)
        } catch (failure: Throwable) {
            // Allocation may have retained custody before returning; absence of a returned owner is not
            // proof of cleanup.
            withContext(kotlinx.coroutines.NonCancellable) { owned?.retire(deadline) }
            throw failure
        }
    }
    internal suspend fun revalidateElpOwner(owner: LocalElpPreparedOwner, toolchainWorkspaceRoot: File, deadline: ElpDeadline) {
        deadline.check(); owner.preflightDeadline.check(); check(owner.isCurrent)
        owner.request.permit.requireCurrent(owner.request.observation, owner.identity)
        owner.execution.verifyPrivate()
        val files = elpFiles()
        for (file in owner.runtimeEvidence.libraryFiles)
            require(files.capture(file.guestPath, 16 * 1024 * 1024, owner.preflightDeadline).first == file) { "ELP selected runtime library changed" }
        for (directory in owner.runtimeEvidence.libraryDirectories)
            require(files.directory(directory.guestPath) == directory) { "ELP selected runtime directory changed" }
        ElpMetadataInspection.verifyObservation(elpFiles(File(owner.request.workspaceRoot)), owner.request.observation, deadline)
        require(reviewedElpLaunchIdentity(owner.identity.discoveredExecutable, toolchainWorkspaceRoot, deadline) == owner.identity)
        LocalUbuntuElpLaunchAuthority.prove(filesDir.absolutePath, owner.identity, ManagedPackageRegistry(appContext).list(), deadline)
        deadline.check(); owner.preflightDeadline.check(); check(owner.isCurrent)
    }
    internal suspend fun runElpStagedHealth(stage: File, identity: ElpManagedLaunchIdentity, selection: ElpToolSelection,
        permit: ElpStageHealthPermit, hold: LocalPackageInstallJournal.ElpHealthHold, deadline: ElpDeadline): ElpStageHealthResult = withContext(Dispatchers.IO) {
        val work = deadline.reserve(ElpResourcePolicy.RETIRE_MS); permit.requireCurrent(selection, permit.attemptId); permit.requireClaimed()
        require(selection.rebar == null && selection.python == null && identity.launchExecutable == "${stage.absolutePath}/payload/tree/elp")
        val current = { runCatching { permit.requireCurrent(selection, permit.attemptId); true }.getOrDefault(false) }
        val owner = ElpExecutionOwner.allocate(filesDir, "health:${permit.attemptId}", current, deadline)
        LocalPackageInstallJournal.bindElpHealthOwner(hold, owner.localRoot.absolutePath, owner.token, owner.privateIdentity)
        var probeCustodyComplete = false; var passed = false; var summary = "ELP health did not complete"
        try {
            require(inspectElpHealthSelection(work) == selection)
            // These probes belong to the installer's existing claim, never a normal analysis activity lease.
            val evidence = elpRuntimeProbes(selection, false, current, work.limited(ElpResourcePolicy.PREFLIGHT_MS), acquireActivity = null)
            probeCustodyComplete = true
            LocalUbuntuElpInstaller.proveStaged(stage, identity) { work.check(); permit.requireCurrent() }
            owner.write("shim/rebar3", elpAsset(LspElpLaunchPolicy.SHIM), true)
            val spec = elpLaunchSpec(owner, selection, null, "${owner.guestRoot}/work", work, stage)
            owner.beginFactory {
                permit.requireCurrent(); require(inspectElpHealthSelection(work) == selection)
                LocalUbuntuElpInstaller.proveStaged(stage, identity) { work.check(); permit.requireCurrent() }
                val command = spec.prefix + ElpResourcePolicy.HEALTH.shellPrefix() + listOf(identity.launchExecutable,
                    "--erl", selection.erl.file.guestPath, "--escript", selection.escript.file.guestPath, "server")
                val builder = ProcessBuilder("/system/bin/sh", "-c", owner.shellCommand(command)).directory(owner.localRoot)
                    .apply { environment().clear(); environment().putAll(spec.environment) }
                owner.create { work.check(); permit.requireCurrent(); builder.start() }
            }
            val process = owner.awaitFactory(work); owner.awaitBirth(work)
            val exercise = owner.operation("health-lifecycle") { kotlinx.coroutines.runBlocking { LocalUbuntuElpLspHealth.exercise(process, evidence, work) } }
            owner.beginMonitoring()
            check(exercise.await(work)); exercise.result(); process.awaitOutputSettlement(work); work.check()
            require(process.stderrBytes <= ElpResourcePolicy.STDERR_BYTES_MAX)
            require(!Regex("could not find otp|panic|failed|error", RegexOption.IGNORE_CASE).containsMatchIn(process.stderrTail().toString(Charsets.UTF_8))) {
                "ELP stderr reported a runtime failure"
            }
            passed = true
            summary = "OTP ${evidence.otpVersion}, ERTS ${evidence.ertsVersion}, ${evidence.architecture}; " +
                "root ${evidence.rootDir}; selection ${evidence.selectionIdentity}; libraries ${evidence.libraryIdentity}; " +
                "native transport only; project/embedded-service/eqWAlizer acceptance unverified"
        } catch (failure: Throwable) { summary = "${failure.javaClass.simpleName}: ${failure.message?.take(512)}" }
        owner.fence(); val cleanup = withContext(kotlinx.coroutines.NonCancellable) { owner.retire(deadline) }
        val complete = if (probeCustodyComplete) cleanup else cleanup.copy(complete = false, reason = "ELP selected probe custody requires verification; stage retained")
        if (complete.complete) LocalPackageInstallJournal.finishElpHealth(hold, complete)
        ElpStageHealthResult(passed && complete.complete, complete, summary)
    }

    internal suspend fun runGraphqlStagedHealth(appRoot:String,stage:File,node:ManagedPackageRecord?,hold:LocalPackageInstallJournal.GraphqlHealthHold,deadline:GraphqlDeadline):ExecResult=withContext(Dispatchers.IO) {
        val work=deadline.reserve(GraphqlResourcePolicy.RETIRE_MS);work.check()
        check(::filesDir.isInitialized&&::appContext.isInitialized&&filesDir.absolutePath==appRoot)
        val family=if(node==null)"runtime.graphql-node"else"lsp.graphql-library"
        require(stage==LocalPackageInstallJournal.paths(appRoot,family,LocalUbuntuGraphqlPolicy.version(family),LocalUbuntuGraphqlPolicy.kind(family)).stage)
        if(node!=null)require(node.familyId=="runtime.graphql-node"&&node.version=="22.23.3")
        val context=currentCoroutineContext();val active={context.ensureActive();work.check()}
        active();val owner=GraphqlHealthPrivateState.allocate(filesDir,work,node!=null)
        LocalPackageInstallJournal.bindGraphqlHealthOwner(hold,owner.localRoot.absolutePath,owner.token,owner.identity)
        active();LocalUbuntuGraphqlInstaller.proveStaged(appRoot,stage,active);val nodeProof=node?.let{LocalUbuntuGraphqlInstaller.provePackage(appRoot,it,active)}
        owner.writeDescriptor(LocalUbuntuGraphqlLspHealth.descriptor(stage,nodeProof,owner,work))
        val spec=prepareUbuntuLaunchSpec(owner.localWorkspace,owner.localWorkspace,owner.localWorkspace,graphqlHealth=owner,graphqlHealthStage=stage,graphqlDeadline=work)
        active();owner.verify();LocalUbuntuGraphqlInstaller.proveStaged(appRoot,stage,active);node?.let{LocalUbuntuGraphqlInstaller.provePackage(appRoot,it,active)}
        val helper=if(node==null)LspGraphqlLaunchPolicy.NODE_ROOT+"/"+LspGraphqlLaunchPolicy.NODE_HEALTH else LspGraphqlLaunchPolicy.ROOT+"/"+LspGraphqlLaunchPolicy.HEALTH
        val args=if(node==null)listOf(helper,"--node-health",owner.guestRoot)else listOf(helper,LspGraphqlLaunchPolicy.ROOT,owner.guestRoot)
        val command=spec.prefix+GraphqlResourcePolicy.HEALTH.shellPrefix()+listOf(owner.nodeExecutable)+GraphqlResourcePolicy.HEALTH.nodeArguments+args+work.remainingMillis().toString()
        active();val builder=ProcessBuilder("/system/bin/sh","-c",owner.shellCommand(command)).directory(owner.localRoot).redirectErrorStream(true).apply{environment().clear();environment().putAll(spec.environment)}
        GraphqlHealthCapture.capture({active();owner.verify();builder.start()},deadline,owner)
    }

    /** GraphQL identity is inert proof, with the caller total established before any wait or capture. */
    internal suspend fun reviewedGraphqlLaunchIdentity(resolvedExecutable:String,toolchainWorkspaceRoot:File,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity?=withContext(Dispatchers.IO){
        deadline.check();if(!::appContext.isInitialized||!::filesDir.isInitialized)return@withContext null
        withTimeout(deadline.remainingMillis().coerceAtLeast(1)){ManagedPackageMutationGate.mutex.withLock{
            deadline.check();val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
            val records=registry.list();val selections=preferences.selections();val record=records.singleOrNull{it.familyId=="lsp.graphql-library"&&it.version=="5.7.0"}?:return@withLock null
            val identity=LocalUbuntuGraphqlLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,resolvedExecutable,deadline)
            deadline.check();if(registry.list()==records&&preferences.selections()==selections)identity else null
        }}
    }
    internal suspend fun prepareReviewedGraphqlOwner(identity:GraphqlManagedLaunchIdentity,request:GraphqlOwnerRequest,workspaceRoot:File,toolchainWorkspaceRoot:File,deadline:GraphqlDeadline):LocalGraphqlPreparedOwner=withContext(Dispatchers.IO){
        val work=deadline.reserve(GraphqlResourcePolicy.RETIRE_MS);work.check();require(File(request.workspaceRoot).canonicalFile==workspaceRoot.canonicalFile)
        val owner=LocalGraphqlPreparedOwner.allocate(filesDir,identity,request,deadline)
        try{work.check();val spec=prepareUbuntuLaunchSpec(File(request.rootPath),workspaceRoot,toolchainWorkspaceRoot,graphqlOwner=owner,graphqlDeadline=work);work.check();owner.verifyProject();owner.verifyPrivate();owner.storePreparedLaunchSpec(spec);owner}
        catch(t:Throwable){owner.fence();withContext(NonCancellable){owner.retire(deadline)};throw t}
    }
    internal fun buildReviewedGraphqlLaunchSpec(owner:LocalGraphqlPreparedOwner,launcher:List<String>,rootfs:File,temp:File,packageRoot:File,hardLinkStore:File,loaderEnvironment:Map<String,String>,checkCancelled:()->Unit):TerminalLaunchSpec {
        checkCancelled();owner.verifyPrivate();require(':' !in owner.localRoot.absolutePath&&':' !in owner.guestRoot)
        val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,"-b","/dev","-b","/proc","-b","/sys",
            "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${owner.localRoot.absolutePath}:${owner.guestRoot}","-w","${owner.guestRoot}/workspace","/usr/bin/env","-i")+LspGraphqlLaunchPolicy.privateEnvironment(owner.guestRoot,owner.identity.nodeExecutable).map{(k,v)->"$k=$v"}
        checkCancelled();return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
    }
    internal suspend fun<T> withReviewedGraphqlLaunchIdentity(identity:GraphqlManagedLaunchIdentity,owner:LocalGraphqlPreparedOwner,toolchainWorkspaceRoot:File,deadline:GraphqlDeadline,create:(selectionAdmission:(()->T)->T)->T):T {
        val work=deadline.reserve(GraphqlResourcePolicy.RETIRE_MS);work.check();check(::appContext.isInitialized&&::filesDir.isInitialized)
        return withTimeout(work.remainingMillis().coerceAtLeast(1)){LocalPackageInstallJournal.transactionMutex.withLock{ManagedPackageMutationGate.mutex.withLock{
            work.check();val context=currentCoroutineContext();val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
            val packageToken=registry.graphqlPackageGeneration();val selectionToken=preferences.graphqlSelectionGeneration();val selections=preferences.selections();val records=registry.list()
            val record=records.single{it.familyId==identity.familyId&&it.version==identity.version}
            check(LocalUbuntuGraphqlLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,identity.discoveredExecutable,work)==identity)
            LocalUbuntuGraphqlLaunchAuthority.prove(filesDir.absolutePath,identity,records,work){context.ensureActive()};owner.verifyProject();owner.verifyPrivate();work.check();check(registry.list()==records)
            check(registry.isGraphqlPackageGenerationCurrent(packageToken)&&preferences.isGraphqlSelectionGenerationCurrent(selectionToken))
            owner.bindSelectionCurrentness{registry.isGraphqlPackageGenerationCurrent(packageToken)&&preferences.isGraphqlSelectionGenerationCurrent(selectionToken)}
            create{action->preferences.withGraphqlSelectionGeneration(selectionToken){GraphqlGenerationAdmission.withAdmission{work.check();context.ensureActive();check(owner.isCurrent&&registry.isGraphqlPackageGenerationCurrent(packageToken));action()}}}
        }}}
    }

    internal suspend fun reviewedTomlLaunchIdentity(resolvedExecutable:String,toolchainWorkspaceRoot:File):TomlManagedLaunchIdentity?=withContext(Dispatchers.IO){
        if(!::appContext.isInitialized||!::filesDir.isInitialized)return@withContext null
        ManagedPackageMutationGate.mutex.withLock{val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
            val records=registry.list();val selections=preferences.selections();val record=records.singleOrNull{it.familyId=="lsp.taplo-wasm"&&it.version=="0.8.0"}?:return@withLock null
            val identity=LocalUbuntuTomlLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,resolvedExecutable)
            if(registry.list()==records&&preferences.selections()==selections)identity else null
        }
    }
    internal suspend fun prepareReviewedTomlOwner(identity:TomlManagedLaunchIdentity,request:TomlOwnerRequest,workspaceRoot:File,toolchainWorkspaceRoot:File):LocalTomlPreparedOwner {
        require(File(request.workspaceRoot).canonicalFile==workspaceRoot.canonicalFile);val owner=LocalTomlPreparedOwner.allocate(filesDir,identity,request)
        try{val spec=prepareUbuntuLaunchSpec(File(request.rootPath),workspaceRoot,toolchainWorkspaceRoot,tomlOwner=owner);owner.verifyProject();owner.verifyPrivate();owner.storePreparedLaunchSpec(spec);return owner}
        catch(t:Throwable){withContext(NonCancellable){owner.retire()};throw t}
    }
    internal fun buildReviewedTomlLaunchSpec(owner:LocalTomlPreparedOwner,launcher:List<String>,rootfs:File,workspace:String,temp:File,packageRoot:File,hardLinkStore:File,loaderEnvironment:Map<String,String>,checkCancelled:()->Unit):TerminalLaunchSpec {
        checkCancelled();owner.verifyPrivate();require(':' !in owner.localRoot.absolutePath&&':' !in owner.guestRoot)

        val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,"-b","/dev","-b","/proc","-b","/sys",
            "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${owner.localRoot.absolutePath}:${owner.guestRoot}","-w",owner.guestRoot,"/usr/bin/env","-i")+
            LspTomlLaunchPolicy.privateEnvironment(owner.guestRoot,owner.identity.nodeExecutable).map{(k,v)->"$k=$v"}
        checkCancelled();return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
    }
    internal suspend fun<T> withReviewedTomlLaunchIdentity(identity:TomlManagedLaunchIdentity,owner:LocalTomlPreparedOwner,toolchainWorkspaceRoot:File,create:(selectionAdmission:(()->T)->T)->T):T {
        check(::appContext.isInitialized&&::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock{ManagedPackageMutationGate.mutex.withLock{
            val context=currentCoroutineContext();val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
            val packageToken=registry.tomlPackageGeneration();val selectionToken=preferences.tomlSelectionGeneration();val selections=preferences.selections();val records=registry.list()
            val record=records.single{it.familyId==identity.familyId&&it.version==identity.version}
            check(LocalUbuntuTomlLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,identity.discoveredExecutable)==identity)
            LocalUbuntuTomlLaunchAuthority.prove(filesDir.absolutePath,identity,records){context.ensureActive()};owner.verifyProject();owner.verifyPrivate();check(registry.list()==records)
            check(registry.isTomlPackageGenerationCurrent(packageToken)&&preferences.isTomlSelectionGenerationCurrent(selectionToken))
            owner.bindSelectionCurrentness{registry.isTomlPackageGenerationCurrent(packageToken)&&preferences.isTomlSelectionGenerationCurrent(selectionToken)}
            create{action->preferences.withTomlSelectionGeneration(selectionToken){TomlGenerationAdmission.withAdmission{context.ensureActive();check(owner.isCurrent);action()}}}
        }}
    }

    /** CSharp discovery is metadata-only and never invokes package health or prepares native runtime. */
    internal suspend fun reviewedCSharpLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): CSharpManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
                val records=registry.list();val selections=preferences.selections()
                val record=records.singleOrNull { it.familyId=="lsp.csharp-ls" && it.version=="0.28.0" } ?: return@withLock null
                val identity=LocalUbuntuCSharpLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,resolvedExecutable)
                if(registry.list()==records && preferences.selections()==selections)identity else null
            }
        }
    internal suspend fun prepareReviewedCSharpOwner(identity:CSharpManagedLaunchIdentity,request:CSharpOwnerRequest,
        workspaceRoot:File,toolchainWorkspaceRoot:File):LocalCSharpPreparedOwner {
        require(File(request.workspaceRoot).canonicalFile==workspaceRoot.canonicalFile)
        val owner=LocalCSharpPreparedOwner.allocate(filesDir,identity,request)
        try {
            val spec=prepareUbuntuLaunchSpec(File(request.projectLocalCanonical),workspaceRoot,toolchainWorkspaceRoot,csharpOwner=owner)
            owner.verifyProject();owner.verifyPrivate();owner.storePreparedLaunchSpec(spec);return owner
        }catch(failure:Throwable){withContext(NonCancellable){owner.retire()};throw failure}
    }

    internal fun buildReviewedCSharpLaunchSpec(owner:LocalCSharpPreparedOwner,launcher:List<String>,rootfs:File,
        workspace:String,temp:File,packageRoot:File,hardLinkStore:File,loaderEnvironment:Map<String,String>,checkCancelled:()->Unit):TerminalLaunchSpec {
        checkCancelled();owner.verifyPrivate();require(':' !in owner.localRoot.absolutePath && ':' !in owner.guestRoot)
        val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,
            "-b","/dev","-b","/proc","-b","/sys","-b","$workspace:$workspace",
            "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${owner.localRoot.absolutePath}:${owner.guestRoot}",
            "-b","${File(owner.localRoot,"tmp").absolutePath}:/tmp",
            "-w",owner.request.rootPath,"/usr/bin/env","-i")+LspCSharpLaunchPolicy.environment(owner.identity,owner.guestRoot).map { (k,v)->"$k=$v" }
        checkCancelled()
        return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),
            environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
    }

    internal suspend fun <T> withReviewedCSharpLaunchIdentity(identity:CSharpManagedLaunchIdentity,owner:LocalCSharpPreparedOwner,
        toolchainWorkspaceRoot:File,create:(selectionAdmission:(()->T)->T)->T):T {
        check(::appContext.isInitialized&&::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock { ManagedPackageMutationGate.mutex.withLock {
            val context=currentCoroutineContext();val registry=ManagedPackageRegistry(appContext)
            val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)

            val packageToken=registry.csharpPackageGeneration();val selectionToken=preferences.csharpSelectionGeneration()
            val selections=preferences.selections();val records=registry.list()
            val record=records.single {it.familyId==identity.familyId&&it.version==identity.version}
            check(LocalUbuntuCSharpLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,identity.discoveredExecutable)==identity)
            LocalUbuntuCSharpLaunchAuthority.prove(filesDir.absolutePath,identity,records){context.ensureActive()}
            owner.verifyPrivate();owner.verifyProject();CSharpSdkSelection.requireExplicitSelections(selections);
            CSharpSdkSelection.requireGuestAncestors(File(filesDir,"guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs"),owner.request.workspaceRoot);check(registry.list()==records)
            check(registry.isCSharpPackageGenerationCurrent(packageToken) && preferences.isCSharpSelectionGenerationCurrent(selectionToken))
            owner.bindSelectionCurrentness { registry.isCSharpPackageGenerationCurrent(packageToken) && preferences.isCSharpSelectionGenerationCurrent(selectionToken) }
            create { action ->
                preferences.withCSharpSelectionGeneration(selectionToken) {
                    CSharpGenerationAdmission.withAdmission {
                        context.ensureActive();check(owner.isCurrent);action()
                    }
                }
            }
        } }
    }

    /** Vue discovery is metadata-only and never invokes package health or prepares native runtime. */
    internal suspend fun reviewedVueLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): VueManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
                val records=registry.list();val selections=preferences.selections()
                val record=records.singleOrNull { it.familyId=="lsp.vue" && it.version=="3.3.13" } ?: return@withLock null
                val identity=LocalUbuntuVueLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,resolvedExecutable)
                if(registry.list()==records && preferences.selections()==selections)identity else null
            }
        }
    internal suspend fun prepareReviewedVueOwner(identity:VueManagedLaunchIdentity,request:VueOwnerRequest,
        workspaceRoot:File,toolchainWorkspaceRoot:File):LocalVuePreparedOwner {
        require(File(request.workspaceRoot).canonicalFile==workspaceRoot.canonicalFile)
        val owner=LocalVuePreparedOwner.allocate(filesDir,identity,request)
        try {
            val spec=prepareUbuntuLaunchSpec(File(request.projectLocalCanonical),workspaceRoot,toolchainWorkspaceRoot,vueOwner=owner)
            owner.verifyProject();owner.verifyPrivate();owner.storePreparedLaunchSpec(spec);return owner
        }catch(failure:Throwable){withContext(NonCancellable){owner.retire()};throw failure}
    }

    internal fun buildReviewedVueLaunchSpec(owner:LocalVuePreparedOwner,launcher:List<String>,rootfs:File,
        workspace:String,temp:File,packageRoot:File,hardLinkStore:File,loaderEnvironment:Map<String,String>,checkCancelled:()->Unit):TerminalLaunchSpec {
        checkCancelled();owner.verifyPrivate();require(':' !in owner.localRoot.absolutePath && ':' !in owner.guestRoot)
        val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,
            "-b","/dev","-b","/proc","-b","/sys","-b","$workspace:$workspace",
            "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${owner.localRoot.absolutePath}:${owner.guestRoot}",
            "-w",owner.request.rootPath,"/usr/bin/env","-i")+LspVueLaunchPolicy.environment(owner.identity,owner.guestRoot).map { (k,v)->"$k=$v" }
        checkCancelled()
        return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),
            environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
    }

    internal suspend fun <T> withReviewedVueLaunchIdentity(identity:VueManagedLaunchIdentity,owner:LocalVuePreparedOwner,
        toolchainWorkspaceRoot:File,create:(selectionAdmission:(()->T)->T)->T):T {
        check(::appContext.isInitialized&&::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock { ManagedPackageMutationGate.mutex.withLock {
            val context=currentCoroutineContext();val registry=ManagedPackageRegistry(appContext)
            val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)

            val packageToken=registry.vuePackageGeneration();val selectionToken=preferences.vueSelectionGeneration()
            val selections=preferences.selections();val records=registry.list()
            val record=records.single {it.familyId==identity.familyId&&it.version==identity.version}
            check(LocalUbuntuVueLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,identity.discoveredExecutable)==identity)
            LocalUbuntuVueLaunchAuthority.prove(filesDir.absolutePath,identity,records){context.ensureActive()}
            owner.verifyPrivate();owner.verifyProject();check(registry.list()==records)
            check(registry.isVuePackageGenerationCurrent(packageToken) && preferences.isVueSelectionGenerationCurrent(selectionToken))
            owner.bindSelectionCurrentness { registry.isVuePackageGenerationCurrent(packageToken) && preferences.isVueSelectionGenerationCurrent(selectionToken) }
            create { action ->
                preferences.withVueSelectionGeneration(selectionToken) {
                    VueGenerationAdmission.withAdmission {
                        context.ensureActive();check(owner.isCurrent);action()
                    }
                }
            }
        } }
    }

    /** Nim discovery is metadata-only and never invokes package health or prepares native runtime. */
    internal suspend fun reviewedNimLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): NimManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry=ManagedPackageRegistry(appContext);val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
                val records=registry.list();val selections=preferences.selections()
                val record=records.singleOrNull { it.familyId=="lsp.nimlangserver" && it.version=="1.16.0" } ?: return@withLock null
                val identity=LocalUbuntuNimLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,resolvedExecutable)
                if(registry.list()==records && preferences.selections()==selections)identity else null
            }
        }
    internal suspend fun prepareReviewedNimOwner(identity:NimManagedLaunchIdentity,request:NimOwnerRequest,
        workspaceRoot:File,toolchainWorkspaceRoot:File):LocalNimPreparedOwner {
        require(File(request.workspaceRoot).canonicalFile==workspaceRoot.canonicalFile)
        val owner=LocalNimPreparedOwner.allocate(filesDir,identity,request)
        try {
            val spec=prepareUbuntuLaunchSpec(File(request.projectLocalCanonical),workspaceRoot,toolchainWorkspaceRoot,nimOwner=owner)
            owner.verifyProject();owner.verifyPrivate();owner.storePreparedLaunchSpec(spec);return owner
        }catch(failure:Throwable){withContext(NonCancellable){owner.retire()};throw failure}
    }

    internal fun buildReviewedNimLaunchSpec(owner:LocalNimPreparedOwner,launcher:List<String>,rootfs:File,
        workspace:String,temp:File,packageRoot:File,hardLinkStore:File,loaderEnvironment:Map<String,String>,checkCancelled:()->Unit):TerminalLaunchSpec {
        checkCancelled();owner.verifyPrivate();require(':' !in owner.localRoot.absolutePath && ':' !in owner.guestRoot)
        val prefix=launcher+listOf("-0","--link2symlink","--kill-on-exit","-r",rootfs.absolutePath,
            "-b","/dev","-b","/proc","-b","/sys","-b","$workspace:$workspace",
            "-b","${packageRoot.absolutePath}:/opt/droide/packages","-b","${owner.localRoot.absolutePath}:${owner.guestRoot}",
            "-w",owner.request.rootPath,"/usr/bin/env","-i")+LspNimLaunchPolicy.environment(owner.identity,owner.guestRoot).map { (k,v)->"$k=$v" }
        checkCancelled()
        return TerminalLaunchSpec(prefix=prefix,shell="/bin/bash",interactiveArgs=emptyList(),interactiveEnvironment=emptyMap(),
            environment=mapOf("PROOT_TMP_DIR" to temp.absolutePath,"PROOT_NO_SECCOMP" to "1","PROOT_L2S_DIR" to hardLinkStore.absolutePath)+loaderEnvironment)
    }

    internal suspend fun <T> withReviewedNimLaunchIdentity(identity:NimManagedLaunchIdentity,owner:LocalNimPreparedOwner,
        toolchainWorkspaceRoot:File,create:(selectionAdmission:(()->T)->T)->T):T {
        check(::appContext.isInitialized&&::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock { ManagedPackageMutationGate.mutex.withLock {
            val context=currentCoroutineContext();val registry=ManagedPackageRegistry(appContext)
            val preferences=WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot);val selections=preferences.selections();val records=registry.list()
            val record=records.single {it.familyId==identity.familyId&&it.version==identity.version}
            check(LocalUbuntuNimLaunchAuthority.inspect(filesDir.absolutePath,record,records,selections,identity.discoveredExecutable)==identity)
            LocalUbuntuNimLaunchAuthority.prove(filesDir.absolutePath,identity,records){context.ensureActive()}
            owner.verifyPrivate();owner.verifyProject();check(registry.list()==records)
            create {action->preferences.withUnchangedSelections(selections){context.ensureActive();check(registry.list()==records&&owner.isCurrent);action()}}
        } }
    }

    /** CMake selection observations are metadata-only and do not prepare any native runtime. */
    internal fun cmakeSelectionContext(workspaceRoot: File): CMakeSelectionContext? {
        if (!::filesDir.isInitialized || !::appContext.isInitialized) return null
        return runCatching {
            val workspace = workspaceRoot.canonicalFile
            val rootfs = File(ubuntuGuestRoot(), "rootfs")
            CMakeSelectionInspector.requireCanonicalNoSymlinks(rootfs)
            val stat = android.system.Os.lstat(rootfs.absolutePath)
            check(android.system.OsConstants.S_ISDIR(stat.st_mode) && !PathSecurity.isSymbolicLink(rootfs))
            val environment = "cmake-local-ubuntu:${filesDir.canonicalPath}:${stat.st_dev}:${stat.st_ino}"
            CMakeSelectionContext(environment, "cmake-local-identity-bind-v1", WorkspacePathMapper(workspace, workspace.canonicalPath), CMakeSelectionInspector)
        }.getOrNull()
    }

    internal suspend fun reviewedCMakeLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): CMakeManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry = ManagedPackageRegistry(appContext)
                val authority = LocalManagedPackageAuthority(appContext, registry)
                val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                val identity = CMakeManagedLaunchAuthority.inspect(filesDir.absolutePath, authority, records, selections, resolvedExecutable)
                if (records == registry.list() && selections == preferences.selections()) identity else null
            }
        }

    internal suspend fun prepareReviewedCMakeOwner(identity: CMakeManagedLaunchIdentity, request: CMakeOwnerRequest,
        workspaceRoot: File, toolchainWorkspaceRoot: File): LocalCMakePreparedOwner {
        val context = requireNotNull(cmakeSelectionContext(workspaceRoot)) { "CMake selection environment unavailable" }
        check(request.selection.environmentIdentity == context.environmentIdentity && request.selection.mappingRevision == context.mappingRevision)
        val registry = ManagedPackageRegistry(appContext)
        val authority = LocalManagedPackageAuthority(appContext, registry)
        val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
        val coroutine = currentCoroutineContext()
        var admittedRecords: List<ManagedPackageRecord> = emptyList()
        var admittedSelections: Map<String, String> = emptyMap()
        var witness: CMakeManagedAdmissionWitness? = null
        fun currentManaged(requireFullSweep: Boolean, checkCancelled: () -> Unit): Boolean {
            val valid = try {
                checkCancelled()
                val bound = requireNotNull(witness)
                val admittedPackage = admittedRecords.single { it.familyId == identity.familyId && it.version == identity.version }
                val admittedLayer = admittedRecords.single { "${it.familyId}@${it.version}" == admittedPackage.dependencies.single() }
                val currentContext = cmakeSelectionContext(workspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                currentContext?.environmentIdentity == context.environmentIdentity && currentContext.mappingRevision == context.mappingRevision &&
                    records == admittedRecords && selections == admittedSelections &&
                    listOf(admittedPackage, admittedLayer).all { authority.ownedReceipt(it.familyId, it.version) == it } &&
                    (if (requireFullSweep) bound.requireCurrentBeforeStart(records, selections, checkCancelled)
                     else bound.isCurrent(records, selections, checkCancelled)) &&
                    registry.list() == records && preferences.selections() == selections
            } catch (cancelled: kotlinx.coroutines.CancellationException) { witness?.retire(); throw cancelled }
              catch (_: Exception) { false }
            if (!valid) witness?.retire()
            return valid
        }
        val leases = CMakeBuildOwnerLeases.forApp(filesDir)
        val lease = try { leases.recover(); leases.reserve(request) }
            catch (failure: Throwable) { witness?.retire(); throw failure }
        var privateState: CMakePrivateState? = null
        try {
            currentCoroutineContext().ensureActive()
            val state = CMakePrivateState.allocate(filesDir, context.environmentIdentity)
            privateState = state
            return LocalCMakePreparedOwner(identity, request, state, leases, lease, context.mapper,
                checkManagedBeforeStart = { checkCancelled -> currentManaged(true, checkCancelled) },
                retireManagedIdentity = { witness?.retire() }) {
                val admissionContext = currentCoroutineContext()
                ManagedPackageMutationGate.mutex.withLock { currentManaged(false) { admissionContext.ensureActive() } }
            }.also { owner ->
                owner.observeSelection()
                val launch = kotlinx.serialization.json.buildJsonObject {
                    put("nonce", kotlinx.serialization.json.JsonPrimitive(owner.nonce))
                    put("leaseId", kotlinx.serialization.json.JsonPrimitive(owner.leaseId))
                    put("ownerGeneration", kotlinx.serialization.json.JsonPrimitive(owner.ownerGeneration))
                    put("environmentIdentity", kotlinx.serialization.json.JsonPrimitive(context.environmentIdentity))
                    put("policy", kotlinx.serialization.json.JsonPrimitive("editor"))
                    put("cwd", kotlinx.serialization.json.JsonPrimitive(request.selection.sourceGuestAbsolute))
                    put("privateDevice", kotlinx.serialization.json.JsonPrimitive(state.identity.device))
                    put("privateInode", kotlinx.serialization.json.JsonPrimitive(state.identity.inode))
                    put("sourceDevice", kotlinx.serialization.json.JsonPrimitive(request.selection.sourceDirectoryIdentity.device))
                    put("sourceInode", kotlinx.serialization.json.JsonPrimitive(request.selection.sourceDirectoryIdentity.inode))
                    put("sourceMode", kotlinx.serialization.json.JsonPrimitive(request.selection.sourceDirectoryIdentity.mode))
                    val buildIdentity = request.selection.configuredBuildDirectoryIdentity ?: CMakeSelectionInspector.directoryIdentity(File(state.localRoot, "build"), context.environmentIdentity)
                    put("buildDevice", kotlinx.serialization.json.JsonPrimitive(buildIdentity.device))
                    put("buildInode", kotlinx.serialization.json.JsonPrimitive(buildIdentity.inode))
                    put("buildModeBits", kotlinx.serialization.json.JsonPrimitive(buildIdentity.mode))
                    put("buildMode", kotlinx.serialization.json.JsonPrimitive(request.selection.buildMode.name))
                    put("buildGuest", kotlinx.serialization.json.JsonPrimitive(owner.buildGuestAbsolute))
                    put("pythonExecutable", kotlinx.serialization.json.JsonPrimitive(identity.pythonExecutableGuestAbsolute))
                    put("bootstrapSha256", kotlinx.serialization.json.JsonPrimitive(identity.bootstrapSha256))
                    put("supervisorSha256", kotlinx.serialization.json.JsonPrimitive(identity.supervisorSha256))
                    put("path", kotlinx.serialization.json.JsonPrimitive(LocalUbuntuCMakeLaunchPolicy.environment(identity, state).getValue("PATH")))
                }
                state.writeRecord("launch.json", launch.toString())
                // Complete our own directory/bind/native preparation writes before observing ancestor metadata.
                // The exact typed spec stays with this prepared owner.
                val launchSpec = prepareReviewedCMakeLaunchSpec(owner, workspaceRoot, toolchainWorkspaceRoot)
                val (records, selections, captured) = ManagedPackageMutationGate.mutex.withLock {
                    val records = registry.list(); val selections = preferences.selections()
                    val selected = records.single { it.familyId == identity.familyId && it.version == identity.version }
                    val layer = records.single { "${it.familyId}@${it.version}" == selected.dependencies.single() }
                    check(listOf(selected, layer).all { authority.ownedReceipt(it.familyId, it.version) == it })
                    check(LocalLinuxPackageEnvironment.selectedRecords(records, selections, filesDir.absolutePath)
                        .singleOrNull { it.familyId == selected.familyId } == selected &&
                        (selections[selected.familyId] == null || selections[selected.familyId] == selected.version))
                    val captured = CMakeManagedLaunchAuthority.proveAndCaptureAdmission(filesDir.absolutePath, identity, records,
                        checkCancelled = { coroutine.ensureActive() })
                    try {
                        check(records == registry.list() && selections == preferences.selections())
                        Triple(records, selections, captured)
                    } catch (failure: Throwable) { captured.retire(); throw failure }
                }
                admittedRecords = records; admittedSelections = selections; witness = captured
                check(cmakeSelectionContext(workspaceRoot)?.let {
                    it.environmentIdentity == context.environmentIdentity && it.mappingRevision == context.mappingRevision
                } == true)
                owner.storePreparedLaunchSpec(launchSpec)
                currentCoroutineContext().ensureActive()
            }
        } catch (failure: Throwable) {
            witness?.retire()
            leases.releaseNeverStarted(lease)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { privateState?.cleanup(true) }
            throw failure
        }
    }

    /** Prepare runtime and exact private bind before any manager lifetime lock is entered. */
    internal suspend fun prepareReviewedCMakeLaunchSpec(owner: LocalCMakePreparedOwner, workspaceRoot: File,
        toolchainWorkspaceRoot: File): TerminalLaunchSpec {
        val source = File(owner.request.selection.sourceLocalCanonical)
        owner.privateState.verify()
        // PRoot bind targets live only in the private app-owned guest namespace.
        val target = File(File(ubuntuGuestRoot(), "rootfs"), owner.privateState.guestRoot.removePrefix("/"))
        CMakeSelectionInspector.requireCanonicalNoSymlinks(target)
        check(!target.exists() && target.mkdirs())
        android.system.Os.chmod(target.absolutePath, 448)
        owner.privateState.registerGuestBindTarget(target)
        return prepareUbuntuLaunchSpec(source, workspaceRoot = workspaceRoot, toolchainWorkspaceRoot = toolchainWorkspaceRoot, cmakeOwner = owner)
    }

    internal fun buildReviewedCMakeLaunchSpec(owner: LocalCMakePreparedOwner, launcher: List<String>, rootfs: File,
        workspace: String, temp: File, packageRoot: File, hardLinkStore: File,
        loaderEnvironment: Map<String, String>, checkCancelled: () -> Unit): TerminalLaunchSpec {
        checkCancelled(); owner.privateState.verify()
        require(':' !in owner.privateState.localRoot.absolutePath && ':' !in owner.privateState.guestRoot)
        val prefix = launcher + listOf("-0", "--link2symlink", "--kill-on-exit", "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "$workspace:$workspace",
            "-b", "${packageRoot.absolutePath}:/opt/droide/packages",
            "-b", "${owner.privateState.localRoot.absolutePath}:${owner.privateState.guestRoot}",
            "-w", owner.request.selection.sourceGuestAbsolute, "/usr/bin/env", "-i") +
            LocalUbuntuCMakeLaunchPolicy.environment(owner.identity, owner.privateState).map { (key, value) -> "$key=$value" }
        checkCancelled()
        return TerminalLaunchSpec(prefix = prefix, shell = "/bin/bash", interactiveArgs = emptyList(), interactiveEnvironment = emptyMap(),
            environment = mapOf("PROOT_TMP_DIR" to temp.absolutePath, "PROOT_NO_SECCOMP" to "1", "PROOT_L2S_DIR" to hardLinkStore.absolutePath) + loaderEnvironment)
    }

    internal suspend fun <T> withReviewedCMakeLaunchIdentity(identity: CMakeManagedLaunchIdentity, owner: LocalCMakePreparedOwner, toolchainWorkspaceRoot: File,
        create: (selectionAdmission: (() -> T) -> T) -> T): T {
        check(::appContext.isInitialized && ::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock {
            ManagedPackageMutationGate.mutex.withLock {
                val registry = ManagedPackageRegistry(appContext); val authority = LocalManagedPackageAuthority(appContext, registry)
                val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                val context = currentCoroutineContext()
                CMakeManagedLaunchAuthority.prove(filesDir.absolutePath, identity, records) { context.ensureActive() }
                owner.requireManagedIdentityBeforeStart { context.ensureActive() }
                check(registry.list() == records)
                create { action -> preferences.withUnchangedSelections(selections) {
                    context.ensureActive(); check(registry.list() == records); action()
                } }
            }
        }
    }

    /** Inspection only: never prepares a runtime, invokes a shell or runs package health. */
    internal suspend fun reviewedSqruffLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): SqruffManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                reviewedSqruffLaunchIdentityLocked(resolvedExecutable, toolchainWorkspaceRoot)
            }
        }

    /** Caller owns the IO/created-handle handoff. Do not dispatch after the non-suspending creation action. */
    internal suspend fun <T> withReviewedSqruffLaunchIdentity(identity: SqruffManagedLaunchIdentity,
        toolchainWorkspaceRoot: File, create: (selectionAdmission: (() -> T) -> T) -> T): T {
        check(::appContext.isInitialized && ::filesDir.isInitialized) { "Local substrate is not initialized" }

        return LocalPackageInstallJournal.transactionMutex.withLock {
            ManagedPackageMutationGate.mutex.withLock {
                val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
                val selections = preferences.selections()
                check(reviewedSqruffLaunchIdentityLocked(identity.discoveredExecutable, toolchainWorkspaceRoot,
                    preferences, selections) == identity) { "SQL managed identity changed before process creation" }
                currentCoroutineContext().ensureActive()
                // The caller nests this bounded selection check inside manager admission, never the reverse.
                create { action -> preferences.withUnchangedSelections(selections, action) }
            }
        }
    }

    /** Existing package gate is already held; this helper only reads bounded owned state. */
    private suspend fun reviewedSqruffLaunchIdentityLocked(resolvedExecutable: String,
        toolchainWorkspaceRoot: File,
        preferences: WorkspaceToolchainPreferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot),
        selections: Map<String, String> = preferences.selections()): SqruffManagedLaunchIdentity? {
        currentCoroutineContext().ensureActive()
        val registry = ManagedPackageRegistry(appContext)
        val authority = LocalManagedPackageAuthority(appContext, registry)
        val records = registry.list()
        val receipt = authority.ownedReceipt(LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuSqruffPolicy.VERSION) ?: return null
        val identity = LocalUbuntuSqruffLaunchAuthority.inspect(filesDir.absolutePath, receipt, records, selections, resolvedExecutable)
        currentCoroutineContext().ensureActive()

        return if (records != registry.list() || selections != preferences.selections() ||
            receipt != authority.ownedReceipt(LocalUbuntuSqruffPolicy.FAMILY, LocalUbuntuSqruffPolicy.VERSION)) null else identity
    }

    /** Svelte health-free package proof; never inside the manager lifetime lock. */
    internal suspend fun reviewedSvelteLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): SvelteManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry = ManagedPackageRegistry(appContext)
                val authority = LocalManagedPackageAuthority(appContext,registry)
                val preferences = WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                val identity = SvelteManagedLaunchAuthority.inspect(filesDir.absolutePath,authority,records,selections,resolvedExecutable)
                if (records == registry.list() && selections == preferences.selections()) identity else null
            }
        }

    internal suspend fun <T> withReviewedSvelteLaunchIdentity(identity: SvelteManagedLaunchIdentity, toolchainWorkspaceRoot: File,
        create: (selectionAdmission: (() -> T) -> T) -> T): T {
        check(::appContext.isInitialized && ::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock {
            ManagedPackageMutationGate.mutex.withLock {
                val registry = ManagedPackageRegistry(appContext); val authority = LocalManagedPackageAuthority(appContext,registry)
                val preferences = WorkspaceToolchainPreferences(appContext,toolchainWorkspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                check(SvelteManagedLaunchAuthority.inspect(filesDir.absolutePath,authority,records,selections,identity.discoveredExecutable) == identity) {
                    "Svelte managed identity changed before creation"
                }
                currentCoroutineContext().ensureActive()
                check(registry.list() == records)
                create { action -> preferences.withUnchangedSelections(selections) {
                    check(registry.list() == records) { "Svelte package generation changed at creation" }; action()
                } }
            }
        }
    }

    /** Metadata only: no runtime preparation, subprocess, tree walk, or health recursion. */
    internal suspend fun reviewedPowerShellLaunchIdentity(resolvedExecutable: String, toolchainWorkspaceRoot: File): PowerShellManagedLaunchIdentity? =
        withContext(Dispatchers.IO) {
            if (!::appContext.isInitialized || !::filesDir.isInitialized) return@withContext null
            ManagedPackageMutationGate.mutex.withLock {
                val registry = ManagedPackageRegistry(appContext)
                val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
                val records = registry.list(); val selections = preferences.selections()
                val record = records.singleOrNull { it.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY && it.version == LocalUbuntuPowerShellEditorServicesPolicy.VERSION }
                    ?: return@withLock null
                val identity = LocalUbuntuPowerShellLaunchAuthority.inspect(filesDir.absolutePath, record, records, selections, resolvedExecutable)
                if (registry.list() == records && preferences.selections() == selections) identity else null
            }
        }

    /** Preserve transaction→mutation→manager→preference order and prove payloads only once per creation. */
    internal suspend fun <T> withReviewedPowerShellLaunchIdentity(identity: PowerShellManagedLaunchIdentity,
        toolchainWorkspaceRoot: File, create: (selectionAdmission: (() -> T) -> T) -> T): T {
        check(::appContext.isInitialized && ::filesDir.isInitialized)
        return LocalPackageInstallJournal.transactionMutex.withLock {
            ManagedPackageMutationGate.mutex.withLock {
                val context = currentCoroutineContext(); val registry = ManagedPackageRegistry(appContext)
                val preferences = WorkspaceToolchainPreferences(appContext, toolchainWorkspaceRoot)
                val selections = preferences.selections(); val records = registry.list()
                val record = records.single { it.familyId == identity.familyId && it.version == identity.version }
                check(LocalUbuntuPowerShellLaunchAuthority.inspect(filesDir.absolutePath, record, records, selections, identity.discoveredExecutable) == identity) {
                    "PowerShell managed generation changed before proof"
                }
                LocalUbuntuPowerShellLaunchAuthority.prove(filesDir.absolutePath, identity, records) { context.ensureActive() }
                check(registry.list() == records && LocalUbuntuPowerShellLaunchAuthority.inspect(filesDir.absolutePath, record, records,
                    selections, identity.discoveredExecutable) == identity) { "PowerShell managed generation changed during proof" }
                context.ensureActive()
                create { action -> preferences.withUnchangedSelections(selections) {
                    context.ensureActive()
                    check(registry.list() == records) { "PowerShell managed receipt changed at creation" }
                    action()
                } }
            }
        }
    }

    /** Each terminal/STDIO preparation owns a fresh complete admission, never a runtime field. */
    internal suspend fun prepareUbuntuLaunchSpec(workDir: File, workspaceRoot: File = workDir,
        toolchainWorkspaceRoot: File = workspaceRoot, cmakeOwner: LocalCMakePreparedOwner? = null, nimOwner: LocalNimPreparedOwner? = null, vueOwner: LocalVuePreparedOwner? = null, csharpOwner: LocalCSharpPreparedOwner? = null, csharpHealth: CSharpHealthPrivateState? = null, csharpHealthStage: File? = null, tomlOwner: LocalTomlPreparedOwner? = null, tomlHealth: TomlHealthPrivateState? = null, tomlHealthStage: File? = null, graphqlOwner: LocalGraphqlPreparedOwner? = null, graphqlHealth: GraphqlHealthPrivateState? = null, graphqlHealthStage: File? = null, graphqlDeadline: GraphqlDeadline? = null): TerminalLaunchSpec = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "Local substrate is not initialized" }
        val preparationContext = currentCoroutineContext()
        val checkpoint: () -> Unit = { preparationContext.ensureActive(); graphqlDeadline?.check(); Unit }
        val attempt = PackagedNativeAdmissionAttempt.create(appContext, checkpoint)
        try {
            attempt.require(checkpoint)
            ensureUbuntuInstalled(attempt, checkpoint)
            buildLinuxLaunchSpec(workDir, false, workspaceRoot, toolchainWorkspaceRoot, attempt, { checkpoint(); graphqlDeadline?.check() }, cmakeOwner, nimOwner, vueOwner, csharpOwner, csharpHealth, csharpHealthStage, tomlOwner, tomlHealth, tomlHealthStage, graphqlOwner, graphqlHealth, graphqlHealthStage, graphqlDeadline)
                .also { checkpoint() }
        } finally {
            attempt.close()
        }
    }

    internal suspend fun ensureUbuntuInstalled() = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "Local substrate is not initialized" }
        FoundryUbuntuGuestEnvironmentManager(appContext).ensureInstalled()
        Unit
    }

    private suspend fun ensureUbuntuInstalled(attempt: PackagedNativeAdmissionAttempt, checkCancelled: () -> Unit) {
        checkCancelled()
        FoundryUbuntuGuestEnvironmentManager(appContext).ensureInstalled(attempt, checkCancelled)
        checkCancelled()
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun ensureQemuReady(workDir: File): TerminalLaunchSpec = LegacyAlpineRuntimeRetirement.reject()

    suspend fun shellBounded(command: String, maxOutputBytes: Int = 1048576, timeoutMs: Long = 30_000, onOutput: ((String) -> Unit)? = null, keepTail: Boolean = false): ExecResult {
        val lease = RemoteProcessLease.createLocal("bootstrap")
        return LocalProcessSupervisor.capture(listOf("/system/bin/sh", "-c", lease.wrap(command)), filesDir,
            maxOutputBytes = maxOutputBytes, timeoutMs = timeoutMs, cleanup = { terminateLease(lease) }, onOutput = onOutput, keepTail = keepTail)
    }

    internal fun terminateLease(lease: RemoteProcessLease) {
        val cleanup = ProcessBuilder("/system/bin/sh", "-c", lease.terminateCommand())
            .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
        LocalLeaseCleanup.await(cleanup)
    }

    suspend fun shell(command: String): ExecResult = shellBounded(command, timeoutMs = 120_000)

    fun requireContextRoot(expectedRoot: String) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        check(filesDir.canonicalFile == File(expectedRoot).canonicalFile) { "Local guest belongs to another application root" }
    }

    fun localRoot(): String = filesDir.absolutePath

    suspend fun pushStream(input: InputStream, path: String, mode: Int = 0) = withContext(Dispatchers.IO) {
        requireSafeLocalPath(path)
        val file = File(path)
        file.parentFile?.mkdirs()
        file.outputStream().use { out -> input.copyTo(out) }
        if (mode != 0) {
            val executable = (mode and 0b001_000_000) != 0
            if (executable) file.setExecutable(true, false)
        }
    }

    fun requireSafeLocalPath(path: String) {
        check(::filesDir.isInitialized) { "Local execution substrate has not been initialized" }
        require(path.isNotBlank() && '\u0000' !in path) { "Invalid local storage path" }
        val root = filesDir.canonicalFile.toPath()
        require(File(path).canonicalFile.toPath().startsWith(root)) { "Path escapes local sandbox: $path" }
    }

    fun shellQuote(value: String): String {
        if (value.isEmpty()) return "''"
        if (value.all { it.isLetterOrDigit() || it in "-_.,+/" }) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    suspend fun push(source: File, path: String, mode: Int = 0) {
        source.inputStream().use { pushStream(it, path, mode) }
    }
}
