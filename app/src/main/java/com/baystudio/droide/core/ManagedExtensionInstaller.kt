package com.baystudio.droide.core
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
sealed interface ExtensionInstallPlan {
    val familyId: String
    val familyName: String
    val requestedVersion: String
    val components: List<String>
    data class Android(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val toolchainEntry: AndroidToolchainCatalogEntry,
        val license: AndroidSdkLicense,
        val licenseAccepted: Boolean,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class AndroidLocalComponent(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val entry: AndroidLocalComponentCatalogEntry,
        val license: AndroidSdkLicense,
        val licenseAccepted: Boolean,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class AndroidLocalWorkstation(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val entries: List<AndroidLocalComponentCatalogEntry>, val gradle: LocalUbuntuVendorRecipe,
        val license: AndroidSdkLicense, val licenseAccepted: Boolean, override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Package(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val entries: List<ManagedPackageCatalogEntry>,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Recipe(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: WorkstationInstallRecipe,
        val nodeDependency: VendorArtifactInstallRecipe? = null,
        override val components: List<String>,
        val vueHelperDependency: LocalUbuntuVendorRecipe? = null,
        val tomlNodeDependency: LocalUbuntuVendorRecipe? = null,
        val graphqlNodeDependency: LocalUbuntuVendorRecipe? = null,
        val museRuntimeDependency: LocalUbuntuVendorRecipe? = null,
    ) : ExtensionInstallPlan
    data class UserManaged(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val update: UserToolUpdateRecipe,
        val miseDependency: LocalUbuntuVendorRecipe? = null,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class UbuntuApt(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: UbuntuAptRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class LocalCMake(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val recipe: LocalUbuntuCMakeRecipe, override val components: List<String>,
    ) : ExtensionInstallPlan
    data class LocalRuby(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val recipe: LocalUbuntuRubyRecipe, override val components: List<String>,
    ) : ExtensionInstallPlan
    data class LocalCSharp(
        override val familyId:String, override val familyName:String, override val requestedVersion:String,
        val recipe:CSharpInstallRecipe, override val components:List<String>,
    ): ExtensionInstallPlan
    data class LocalVendor(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val recipe: LocalUbuntuVendorRecipe, override val components: List<String>,
        val nodeDependency: VendorArtifactInstallRecipe? = null,
        val powerShellDependency: LocalUbuntuVendorRecipe? = null,
        val nimDependencies: List<LocalUbuntuVendorRecipe> = emptyList(),
        val elpHealthPermit: ElpStageHealthPermit? = null,
    ) : ExtensionInstallPlan
    data class LocalVendorAlias(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val recipe: LocalUbuntuBundleAliasPolicy.Recipe, val primary: LocalUbuntuVendorRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class LocalBundleAlias(
        override val familyId: String, override val familyName: String, override val requestedVersion: String,
        val recipe: LocalUbuntuBundleAliasPolicy.Recipe, val primary: VendorArtifactInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class PyPi(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: PyPiInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class Go(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: GoInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class RubyGems(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: RubyGemsInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class GitHubRelease(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: GitHubReleaseInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class VendorOfficial(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: VendorArtifactInstallRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class BundleProjection(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val bundleId: String,
        val primaryFamilyId: String,
        val primarySource: PackageSourceDefinition,
        val primaryRecipe: ReviewedBundlePrimaryRecipe,
        val command: String,
        val healthArgs: List<String>,
        val provenanceUrl: String,
        override val components: List<String>,
    ) : ExtensionInstallPlan
    data class GuestPackage(
        override val familyId: String,
        override val familyName: String,
        override val requestedVersion: String,
        val recipe: WorkstationGuestPackageRecipe,
        override val components: List<String>,
    ) : ExtensionInstallPlan
}

val ExtensionInstallPlan.resolvedVersion: String
    get() = when (this) {
        is ExtensionInstallPlan.Android -> requestedVersion
        is ExtensionInstallPlan.AndroidLocalComponent -> requestedVersion
        is ExtensionInstallPlan.AndroidLocalWorkstation -> LocalAndroidWorkstationPolicy.VERSION
        is ExtensionInstallPlan.Package -> entries.first { it.familyId == familyId }.version
        is ExtensionInstallPlan.Recipe -> recipe.version
        is ExtensionInstallPlan.UserManaged -> requestedVersion
        is ExtensionInstallPlan.UbuntuApt -> recipe.version
        is ExtensionInstallPlan.LocalCMake -> recipe.version
        is ExtensionInstallPlan.LocalRuby -> recipe.version
        is ExtensionInstallPlan.LocalCSharp -> recipe.version
        is ExtensionInstallPlan.LocalVendor -> recipe.version
        is ExtensionInstallPlan.LocalVendorAlias -> "${recipe.bundle}-${primary.version}"
        is ExtensionInstallPlan.LocalBundleAlias -> "${recipe.bundle}-${primary.version}"
        is ExtensionInstallPlan.PyPi -> recipe.version
        is ExtensionInstallPlan.Go -> recipe.version
        is ExtensionInstallPlan.RubyGems -> recipe.version
        is ExtensionInstallPlan.GitHubRelease -> recipe.version
        is ExtensionInstallPlan.VendorOfficial -> recipe.version
        is ExtensionInstallPlan.GuestPackage -> recipe.version
        is ExtensionInstallPlan.BundleProjection -> primaryRecipe.version
    }

class ManagedExtensionInstaller(
    context: Context,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    private val registry: ManagedPackageRegistry,
    private val sourceCatalog: PackageSourceCatalog = PackageSourceCatalog.load(context.applicationContext),
    private val projectRoot: java.io.File,
    private val installedComponentsEnabled: Boolean = true,
) {
    enum class Phase { IDLE, RESOLVING, LICENSE, DOWNLOADING, INSTALLING, ACTIVATING, REPAIRING, REMOVING, READY, CANCELED, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val familyId: String? = null,
        val version: String? = null,
        val log: String = "",
        val operationActive: Boolean = false,

        val verifiedCompletionMessage: String? = null,
    ) {
        val running: Boolean get() = operationActive || phase in setOf(Phase.RESOLVING, Phase.DOWNLOADING, Phase.INSTALLING, Phase.ACTIVATING, Phase.REPAIRING, Phase.REMOVING)
    }

    private val appContext = context.applicationContext
    private val longOperationJournal by lazy {
        LongRunningOperationJournal(
            java.io.File(appContext.filesDir, "operation-journal"),
            "packages",
            android.os.Process.myPid(),
            currentProcessIdentity = LocalExecutionSubstrate.processIdentity(),
        )
    }
    init { if (installedComponentsEnabled) longOperationJournal.reconcileInterrupted() }
    private val licenseManager = AndroidSdkLicenseManager(appContext)
    private val androidInstaller = ManagedAndroidToolchainInstaller(appContext, androidDevelopment, licenseManager)
    val packageInstaller = ManagedPackageInstaller(appContext, bridge, registry)
    val localPackageAuthority = LocalManagedPackageAuthority(appContext, registry)
    private val recipeInstaller = ReviewedWorkstationRecipeInstaller(androidDevelopment, bridge, packageInstaller)
    private val pypiRecipeInstaller = ReviewedPyPiRecipeInstaller(appContext, bridge, packageInstaller)
    private val goRecipeInstaller = ReviewedGoRecipeInstaller(appContext, bridge, packageInstaller)
    private val rubyGemsRecipeInstaller = ReviewedRubyGemsRecipeInstaller(appContext, bridge, packageInstaller)
    private val guestEnvironment = WorkstationGuestEnvironmentManager(appContext)
    private val guestPackageInstaller = WorkstationGuestPackageInstaller(appContext, localPackageAuthority, guestEnvironment)
    private val localUbuntuEnvironment = FoundryUbuntuGuestEnvironmentManager(appContext)
    private val localVendorResolver = LocalUbuntuVendorResolver()
    private val localBundleAliasInstaller = LocalUbuntuBundleAliasInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val aptInstaller = LocalUbuntuAptInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val localNpmInstaller = LocalUbuntuNpmRecipeInstaller(appContext, projectRoot, localPackageAuthority, localUbuntuEnvironment)
    private val localCMakeInstaller = LocalUbuntuCMakeInstaller(appContext, projectRoot, localPackageAuthority, localUbuntuEnvironment)
    private val localRubyInstaller = LocalUbuntuRubyInstaller(appContext, projectRoot, localPackageAuthority, localUbuntuEnvironment)
    private val localAndroidSdkComponentInstaller = LocalAndroidSdkComponentInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val localAndroidWorkstationInstaller = LocalAndroidWorkstationInstaller(appContext, projectRoot, localPackageAuthority, localUbuntuEnvironment)
    private val localReviewedRawInstaller = LocalReviewedRawArtifactInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val localCSharpInstaller by lazy { LocalUbuntuCSharpInstaller(context.applicationContext,localPackageAuthority) }
    private val localReviewedWholeTreeInstaller = LocalReviewedWholeTreeArtifactInstaller(appContext, localPackageAuthority, localUbuntuEnvironment)
    private val runtimeDependencyEngine = PackageRuntimeDependencyEngine(registry, packageInstaller, guestPackageInstaller, guestEnvironment)
    private val reviewedArtifactBackend = ReviewedArtifactBackendRouter(appContext, bridge, packageInstaller, runtimeDependencyEngine, registry, sourceCatalog, guestEnvironment)
    private val artifactInstallCoordinator = ReviewedArtifactInstallCoordinator(registry, packageInstaller, reviewedArtifactBackend)
    private val bundleProjectionInstaller = ReviewedBundleProjectionInstaller(bridge, packageInstaller)
    private val sourceResolver = PackageSourceResolver(sourceCatalog)
    private val bundleProjectionCoordinator = ReviewedBundleProjectionCoordinator(sourceCatalog, sourceResolver, registry, packageInstaller, reviewedArtifactBackend, bundleProjectionInstaller)
    private val operations = InstallerOperationCoordinator()
    private val _state = OperationStatePublisher(operations)

    internal class OperationStatePublisher(private val operations: InstallerOperationCoordinator) {
        private val flow = MutableStateFlow(State())
        // Attached only after the coordinator grants ownership, cleared before its mutex releases.
        @Volatile var progressObserver: PackageProgressObserver? = null
        var value: State
            get() = flow.value
            set(next) {
                operations.publish(allowWhenClosed = next.phase in setOf(Phase.CANCELED, Phase.FAILED)) {
                    flow.value = normalized(next)
                    observe()
                }
            }
        private fun normalized(next: State): State = next.copy(
            operationActive = operations.active,
            verifiedCompletionMessage = when (next.phase) {
                Phase.READY -> next.message.takeIf { it.isNotBlank() }
                Phase.CANCELED, Phase.FAILED -> null
                else -> next.verifiedCompletionMessage
            },
            // Helpers may finish before prerequisite restoration and the durable operation lease.
            phase = if (operations.active && next.phase == Phase.READY) Phase.ACTIVATING else next.phase,
        )
        fun update(transform: (State) -> State) {
            operations.publish {
                flow.update { previous -> normalized(transform(previous)) }
                observe()
            }
        }
        private fun observe() {
            val current = flow.value
            progressObserver?.publish(PackageProgressSnapshot(
                current.phase.name.lowercase(), current.message, current.log,
            ))
        }
        fun asStateFlow(): StateFlow<State> = flow.asStateFlow()

        internal fun finishSuccess(operationPhase: Phase) = update {
            val fallback = when (operationPhase) {
                Phase.ACTIVATING -> "Activation completed."
                Phase.REPAIRING -> "Repair completed."
                Phase.REMOVING -> "Uninstall completed."
                else -> "Installation completed."
            }
            it.copy(
                phase = if (operationPhase == Phase.RESOLVING) {
                    if (it.phase == Phase.LICENSE) Phase.LICENSE else Phase.IDLE
                } else Phase.READY,
                message = if (operationPhase == Phase.RESOLVING) it.message else
                    InstallerExecutionPolicy.finalMessage(it.verifiedCompletionMessage != null,
                        it.verifiedCompletionMessage.orEmpty(), fallback),
                operationActive = false,
            )
        }
    }
    val state: StateFlow<State> = _state.asStateFlow()
    val androidProviderState: StateFlow<ManagedAndroidToolchainInstaller.State> = androidInstaller.state
    val packageProviderState: StateFlow<ManagedPackageInstaller.State> = packageInstaller.state

    private val progressLog = InstallerProgressLog()
    fun cancelInstallation() { operations.cancel() }
    fun close() { operations.close() }
    internal suspend fun closeAndJoinForExit() { operations.closeAndJoin() }
    private fun progress(chunk: String) {
        val log = progressLog.append(chunk)
        _state.update { it.copy(message = log.lineSequence().filter { it.isNotBlank() }.lastOrNull()?.take(200) ?: it.message, log = log) }
    }

    private fun activity(message: String) {

        _state.update { it.copy(message = message.trim().take(200)) }
    }

    internal suspend fun ownsCurrentOperation(): Boolean = operations.ownsCurrentOperation()
    internal suspend fun <T> withSnapshot(block: suspend () -> T): T = operations.snapshot(block)
    internal fun requireOpen() = operations.requireOpen()
    internal fun publishSnapshot(block: () -> Unit) { check(operations.publish(block = block)) { "Extension manager is closed" } }

    /** Includes the authority's selection/default restoration in the same cancellable operation. */
    internal suspend fun <T> transaction(actionName: String, family: String, block: suspend () -> T): T =
        operation(family, "", when (actionName) {
            "activate" -> Phase.ACTIVATING
            "repair" -> Phase.REPAIRING
            "uninstall" -> Phase.REMOVING
            else -> Phase.INSTALLING
        }, block = block)

    private suspend fun <T> operation(family: String, version: String, phase: Phase,
        timeoutMs: Long = InstallerExecutionPolicy.operationDeadlineMs(phase),
        block: suspend () -> T): T {
        requireInstalledComponents()
        val observer = currentCoroutineContext()[PackageProgressObserver]
        return operations.operation(
            onStart = {
                _state.progressObserver = observer
                progressLog.clear()
                _state.value = State(phase, "Preparing operation…", family, version)
            },
            onFinish = { failure ->
                try {
                    if (operations.isClosed) {
                        _state.value = State(Phase.CANCELED, "Operation canceled because the workspace closed.", family, version,
                            progressLog.append("\nWorkspace closed\n"))
                    } else if (failure != null && _state.value.phase !in setOf(Phase.FAILED, Phase.CANCELED)) {
                        _state.value = State(
                            if (failure is CancellationException) Phase.CANCELED else Phase.FAILED,
                            if (failure is CancellationException) "Operation canceled." else "Operation failed: ${failure.javaClass.simpleName}",
                            family, version, progressLog.append(""),
                        )
                    } else {
                        if (failure == null) _state.finishSuccess(phase)
                        _state.update { it.copy(operationActive = false, log = progressLog.append("")) }
                    }
                } finally { _state.progressObserver = null }
            },
        ) {
            _state.update { it.copy(phase = phase, familyId = family, version = version) }
            try {
                withInstallerOperationDeadline(timeoutMs) {
                    if (phase == Phase.RESOLVING) block()
                    else OwnedDevelopmentOperation.run(appContext, "Package ${phase.name.lowercase()} · $family") { block() }
                }
            } catch (timeout: InstallerOperationDeadlineExceeded) {
                val message = "Operation deadline reached. Check the log and retry."
                _state.value = State(Phase.FAILED, message, family, version.ifBlank { _state.value.version.orEmpty() }, progressLog.append(message))
                throw IllegalStateException(message, timeout)
            } catch (cancelled: CancellationException) {
                _state.value = State(Phase.CANCELED, "Operation canceled. Shared Ubuntu packages are retained.", family, version.ifBlank { _state.value.version.orEmpty() },
                    progressLog.append("\nCanceled\n"))
                throw cancelled
            } catch (failure: Exception) {
                val log = progressLog.append("\n${failure.message ?: "Operation failed"}\n")
                _state.value = State(Phase.FAILED, log.lineSequence().filter { it.isNotBlank() }.last().take(300), family,
                    version.ifBlank { _state.value.version.orEmpty() }, log)
                throw IllegalStateException(_state.value.message, failure)
            } catch (fatal: Error) {
                _state.value = State(Phase.FAILED, "Operation failed: ${fatal.javaClass.simpleName}", family, version)
                throw fatal
            }
        }
    }
    suspend fun prepare(item: ExtensionVersionState): ExtensionInstallPlan =
        operation(item.family.id, item.version.version, Phase.RESOLVING, 120_000) { prepareUnlocked(item) }

    private suspend fun prepareUnlocked(item: ExtensionVersionState): ExtensionInstallPlan {
        require(item.state == ExtensionState.AVAILABLE) { "This extension is not currently installable" }
        _state.value = State(Phase.RESOLVING, "Resolving package and dependencies…", item.family.id, item.version.version)

        return when (item.version.installKind) {
            ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> prepareAndroid(item)
            ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> prepareAndroidLocalComponent(item)
            ExtensionInstallKind.MANAGED_PACKAGE -> preparePackage(item)
            ExtensionInstallKind.GUEST_PACKAGE -> prepareGuestPackage(item)
            ExtensionInstallKind.REVIEWED_RECIPE -> prepareRecipe(item)
            ExtensionInstallKind.USER_MANAGED -> prepareUserManaged(item)
            ExtensionInstallKind.BUILT_IN -> error("Built-in extensions do not need installation")
            ExtensionInstallKind.CATALOG_ONLY -> error("No verified managed installer exists for this package")
        }
    }

    private suspend fun prepareUserManaged(item: ExtensionVersionState): ExtensionInstallPlan.UserManaged {
        DevelopmentExtensionProjection.platformProblem(Build.SUPPORTED_ABIS.toList(), android.os.Process.is64Bit())?.let { error(it) }
        val source = sourceCatalog.find(item.family.id)
        val update = ToolUpdatePlanner.recipe(item.family, item.version.version, source)
        ToolUpdatePlanner.prerequisiteCheck(item.family.id, source)?.let { command ->
            check(LocalExecutionSubstrate.inspectLinuxState().ready) { ToolUpdatePlanner.ANDROID_PREREQUISITE_GUIDANCE }
            val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
            val result = LocalProcessSupervisor.capture(launch.shellCommand(command), projectRoot, launch.environment,
                maxOutputBytes = 4_000, timeoutMs = 10_000)
            check(!result.timedOut && !result.outputIncomplete && result.exitCode == 0) {
                result.output.trim().take(2_000).ifBlank { ToolUpdatePlanner.ANDROID_PREREQUISITE_GUIDANCE }
            }
        }
        val mise = if (update.usesMise && !userCommandAvailable("mise")) localVendorResolver.resolve("cli.mise") else null
        return ExtensionInstallPlan.UserManaged(item.family.id, item.family.name, item.version.version, update, mise,
            listOf("${update.provider}: ${item.version.version}", "Uses the same Ubuntu workstation and current project folder",
                "User-owned tools and dependencies; managed version snapshots stay available") +
                if (mise == null) emptyList() else listOf("Install mise ${mise.version} once from its official ARM64 publisher archive"))
    }
    private suspend fun userCommandAvailable(command: String): Boolean {
        if (!LocalExecutionSubstrate.inspectLinuxState().ready) return false
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        val result = LocalProcessSupervisor.capture(launch.shellCommand("command -v " + LocalExecutionSubstrate.shellQuote(command)),
            projectRoot, launch.environment, maxOutputBytes = 2_000, timeoutMs = 10_000)
        return result.exitCode == 0 && result.output.isNotBlank()
    }
    private suspend fun installUserManaged(plan: ExtensionInstallPlan.UserManaged): String {
        localUbuntuEnvironment.ensureInstalled()
        plan.miseDependency?.let { installLocalVendor(it, null) }
        if (plan.update.aptPrerequisites.isNotEmpty()) localUbuntuEnvironment.ensurePackages(plan.update.aptPrerequisites, ::progress)
        if (plan.update.provider == "npm" && !userCommandAvailable("npm")) {
            localReviewedWholeTreeInstaller.install(resolveNode(), ::progress)
        }
        _state.value = State(Phase.INSTALLING, "Updating ${plan.familyName} with ${plan.update.provider}…", plan.familyId, plan.requestedVersion)
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        val result = withInstallerOutputHeartbeat(::progress, ::activity) { output ->
            OwnedInstallerCommand.capture(launch, projectRoot, listOf(launch.shell, "-c", plan.update.command),
                maxOutputBytes = 24_000, timeoutMs = InstallerExecutionPolicy.INSTALL_OPERATION_DEADLINE_MS,
                onOutput = output)
        }
        check(!result.timedOut && result.exitCode == 0) {
            "${plan.update.provider} update failed (exit ${result.exitCode}). " + result.output.takeLast(4_000)
        }
        progress("Checking updated commands…\n")
        val verification = if (plan.update.healthCheckedDuringInstall) {
            "Provider component checks passed during installation; existing processes retain their environment."
        } else {
            // Rebuild PATH/SDK projection after mutation; do not reuse a pre-install executable path.
            val updatedLaunch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
            suspend fun executeHealth(command: String) = LocalProcessSupervisor.capture(
                updatedLaunch.shellCommand(command), projectRoot, updatedLaunch.environment,
                maxOutputBytes = 16_000, timeoutMs = 30_000)
            plan.update.healthCheckCommand?.let { command ->
                val health = executeHealth(command)
                check(!health.timedOut && !health.outputIncomplete && health.exitCode == 0) {
                    "The package manager finished, but the provider-specific check failed. " +
                        health.output.takeLast(2_000) + " Check the selected project toolchain and upstream prerequisites."
                }
                "Provider-specific check passed; selected PATH/version is not independently verified."
            } ?: UserToolUpdateHealth.verify(plan.update, ::executeHealth).summary
        }
        progress("$verification\n")
        _state.value = State(Phase.READY, "${plan.familyName}: $verification", plan.familyId, plan.requestedVersion)
        return "${plan.familyName} via ${plan.update.provider}. $verification"
    }

    private suspend fun prepareAndroid(item: ExtensionVersionState): ExtensionInstallPlan.Android {
        val catalog = AndroidToolchainCatalog.load(appContext)
        val supported = Build.SUPPORTED_ABIS.toList()
        val entry = when (item.family.id) {
            "sdk.android" -> {
                val api = item.version.version.toIntOrNull() ?: error("Invalid Android SDK version")
                AndroidToolchainCatalog.select(catalog, AndroidToolchainRequirements(compileSdk = api), supported)
            }
            "toolchain.jdk" -> {
                val java = item.version.version.toIntOrNull() ?: error("Invalid JDK version")
                catalog.entries.filter { it.abi in supported && it.javaVersion == java }
                    .minWithOrNull(compareBy<AndroidToolchainCatalogEntry> { AndroidToolchainRuntime.runtimePriority(AndroidToolchainRuntimeSpec(it.executionMode)) }
                        .thenByDescending { it.compileSdk })
            }
            "build.android-tools" -> catalog.entries.filter { entry ->
                entry.abi in supported && item.version.version in entry.buildToolsVersions
            }.minWithOrNull(compareBy<AndroidToolchainCatalogEntry> { AndroidToolchainRuntime.runtimePriority(AndroidToolchainRuntimeSpec(it.executionMode)) }
                .thenByDescending { it.compileSdk })
            "meta.android-development" -> AndroidToolchainCatalog.select(catalog, AndroidToolchainRequirements(), supported)
            else -> null
        } ?: error("No certified ${Build.SUPPORTED_ABIS.firstOrNull() ?: "device"} artifact is pinned for ${item.family.name} ${item.version.version}")

        _state.value = State(Phase.LICENSE, "Loading exact Android SDK license…", item.family.id, item.version.version)
        val license = licenseManager.fetchCurrent()
        require(license.sha256 == entry.sdkLicenseSha256) { "Android SDK license hash does not match the pinned package catalog" }
        val accepted = licenseManager.isAccepted(license)
        _state.value = State(Phase.LICENSE, if (accepted) "Package ready to install." else "Review the exact SDK license before installation.", item.family.id, item.version.version)
        return ExtensionInstallPlan.Android(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            toolchainEntry = entry,
            license = license,
            licenseAccepted = accepted,
            components = buildList {
                add("JDK ${entry.javaVersion}")
                add("Android SDK ${entry.compileSdks.sorted().joinToString(",")}")
                if (entry.buildToolsVersions.isNotEmpty()) add("Build Tools ${entry.buildToolsVersions.joinToString(",")}")
                if (entry.ndkVersions.isNotEmpty()) add("NDK ${entry.ndkVersions.joinToString(",")}")
                if (entry.cmakeVersions.isNotEmpty()) add("CMake ${entry.cmakeVersions.joinToString(",")}")
                add("Gradle Wrapper compatible runtime (${entry.gradleVersion} certification baseline)")
            },
        )
    }

    private suspend fun prepareAndroidLocalComponent(item: ExtensionVersionState): ExtensionInstallPlan.AndroidLocalComponent {
        val catalog = AndroidLocalComponentCatalog.load(appContext)
        val entry = AndroidLocalComponentCatalog.find(catalog, item.family.id, item.version.version)
            ?: error("No verified local Android component is pinned for ${item.family.name} ${item.version.version}")
        _state.value = State(Phase.LICENSE, "Loading current Android SDK license…", item.family.id, item.version.version)
        val license = licenseManager.fetchCurrent()
        val accepted = licenseManager.isAccepted(license)
        _state.value = State(
            Phase.LICENSE,
            if (accepted) "Local Android component is ready to install." else "Review the Android SDK license before installation.",
            item.family.id,
            item.version.version,
        )
        val detail = when (entry.kind) {
            AndroidLocalComponentKind.SDK_PLATFORM -> listOf(
                "Google Android SDK Platform ${entry.version} revision 2",
                "Exact Google archive: ${entry.fileName} (${entry.sizeBytes} bytes)",
                "Google SHA-1 ${entry.upstreamSha1}",
            )
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> listOf(
                "Google Android SDK Build Tools ${entry.version} Java/metadata payload",
                "Exact Google archive: ${entry.fileName} (${entry.sizeBytes} bytes)",
                "Google SHA-1 ${entry.upstreamSha1}",
                "Linux ARM64 native overlay: ${entry.nativeTools.sortedBy { it.name }.joinToString { it.name }}",
                "Native overlay provenance: Commit451/android-arm-build-tools platform-tools-${entry.version}",
            )
        }
        return ExtensionInstallPlan.AndroidLocalComponent(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            entry = entry,
            license = license,
            licenseAccepted = accepted,
            components = detail + listOf(
                "Local Ubuntu projection: ${LocalAndroidSdkComponentEnvironment.GUEST_SDK_ROOT}/${entry.guestTarget}",
                "Primary SHA-256 ${entry.sha256}",
            ) + entry.nativeTools.sortedBy { it.name }.map { "${it.name} SHA-256 ${it.sha256}" },
        )
    }

    private suspend fun preparePackage(item: ExtensionVersionState): ExtensionInstallPlan.Package {
        val document = ManagedPackageCatalog.load(appContext)
        val target = ManagedPackageCatalog.select(document, item.family.id, item.version.version, Build.SUPPORTED_ABIS.toList())
            ?: error("No verified ${Build.SUPPORTED_ABIS.firstOrNull() ?: "device"} package is pinned for ${item.family.name} ${item.version.version}")
        val resolved = resolveDependencies(document, target)
        _state.value = State(Phase.IDLE, "Package plan ready.", item.family.id, item.version.version)
        return ExtensionInstallPlan.Package(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            entries = resolved,
            components = resolved.map { "${it.familyId} ${it.version} (${it.abi})" },
        )
    }

    private suspend fun prepareGuestPackage(item: ExtensionVersionState): ExtensionInstallPlan.GuestPackage {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Rootless Alpine extension packages currently require ARM64"
        }
        val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
            ?: error("No reviewed Alpine package recipe exists for ${item.family.name} ${item.version.version}")
        _state.value = State(Phase.IDLE, "Reviewed Alpine package recipe ready.", item.family.id, item.version.version)
        return ExtensionInstallPlan.GuestPackage(
            familyId = item.family.id,
            familyName = item.family.name,
            requestedVersion = item.version.version,
            recipe = recipe,
            components = buildList {
                add("Rootless ${WorkstationGuestEnvironmentSpec.VERSION} ARM64 environment")
                addAll(recipe.packages.map { "apk: $it" })
                if (recipe.commands.isNotEmpty()) add("Commands: ${recipe.commands.keys.sorted().joinToString(", ")}")
                add("SHA-256 pinned PRoot + Alpine bootstrap")
            },
        )
    }

    private suspend fun prepareRecipe(item: ExtensionVersionState): ExtensionInstallPlan {
        if (item.family.id == LocalAndroidWorkstationPolicy.FAMILY && item.version.version == LocalAndroidWorkstationPolicy.VERSION) {
            return prepareAndroidLocalWorkstation(item)
        }
        LocalUbuntuCMakeCatalog.find(item.family.id, item.version.version)?.let { recipe ->
            _state.value = State(Phase.IDLE, "Exact five-wheel CMake plan ready.", item.family.id, recipe.version)
            return ExtensionInstallPlan.LocalCMake(item.family.id, item.family.name, item.version.version, recipe,
                listOf("Original CMake language server 0.1.11 and four exact pure-Python dependencies", "Five publisher SHA-256 and size-bound wheels; no pip, sdist or extras",
                    "Signed Ubuntu CPython 3.12 / Unicode 15 and CMake 3.28 prerequisites; interactive defaults preserved",
                    "Finite ZIP/RECORD, metadata/license checks, runtime fingerprints and isolated real main entrypoint",
                    "Zero-document stdio lifecycle and private CMake help probes; project execution requires separate Enable analysis consent",
                    "Original wheel layout/license and target-device acceptance remain separately unverified until this authorized installation"))
        }
        LocalUbuntuRubyCatalog.find(item.family.id, item.version.version)?.let { recipe ->
            _state.value = State(Phase.IDLE, "Pinned Ruby language server plan ready.", item.family.id, recipe.version)
            return ExtensionInstallPlan.LocalRuby(item.family.id, item.family.name, item.version.version, recipe,
                listOf("Original Ruby LSP ${recipe.version} and five exact runtime dependencies", "Ruby-platform gem archives with official SHA-256",
                    "Signed Ubuntu Ruby 3.2, Bundler and native compiler prerequisites", "Offline native extension builds",
                    "Private frozen bundle; project Gemfile and lock remain under user control", "Full LSP initialize/shutdown health check"))
        }
        UbuntuAptCatalog.find(item.family.id)?.takeIf { item.version.version == it.version }?.let { recipe ->
            _state.value = State(Phase.IDLE, "Ubuntu package plan ready.", item.family.id, item.version.version)
            return ExtensionInstallPlan.UbuntuApt(item.family.id, item.family.name, item.version.version, recipe,
                listOf("Ubuntu 24.04 ARM64 signed APT repository", "Packages: ${recipe.packages.joinToString()}",
                    "Commands: ${recipe.commands.keys.joinToString()}", "Shared distro packages stay installed when integration is removed."))
        }
        if (item.family.id == "runtime.node") {
            val recipe = resolveNode(item.version.version.takeUnless { it in setOf(LocalUbuntuNodeDistribution.VERSION, "latest", PackageSourceCatalog.LATEST_OFFICIAL_VERSION) })
            _state.value = State(Phase.IDLE, "Official Node.js plan ready.", item.family.id, recipe.version)
            return ExtensionInstallPlan.VendorOfficial(item.family.id, item.family.name, item.version.version, recipe,
                listOf("Official Linux ARM64 Node.js ${recipe.version} + npm/npx", "Publisher SHA-256 ${recipe.assetSha256}",
                    "Ubuntu runtime and shared library prerequisites", "Toolchain downloaded on request"))
        }
        if (item.family.id == "plugin.opencode" && item.version.version == LocalUbuntuVendorPolicy.VERSION_CHOICE) {
            val recipe = localVendorResolver.resolve("plugin.opencode")
            return ExtensionInstallPlan.LocalVendor(item.family.id, item.family.name, item.version.version, recipe,
                listOf("Official standalone Ubuntu ARM64 OpenCode ${recipe.version}; no Node.js setup",
                    "Publisher SHA-256 ${recipe.assetSha256}", "Direct download with real progress and resume"))
        }
        if (LocalUbuntuCSharpPolicy.isFamily(item.family.id)) {
            val recipe=LocalUbuntuCSharpPolicy.requested(item.family.id,item.version.version)
            return ExtensionInstallPlan.LocalCSharp(item.family.id,item.family.name,item.version.version,recipe,
                listOf("Original ${recipe.familyId} ${recipe.version}; ${recipe.publisherDigest.algorithm.jca} publisher authentication",
                    "Complete original layout, managed/native dependency and component notice validation",
                    "Private SDK 10.0.402/runtime 10.0.13 and Node 22.23.3; zero-project owned health before server activation"))
        }
        if (item.family.id == LocalUbuntuGraphqlPolicy.FAMILY) {
            require(item.version.version==LocalUbuntuGraphqlPolicy.VERSION) { LocalUbuntuGraphqlPolicy.PREREQUISITE_MESSAGE }
            return npmPlan(item,LocalUbuntuGraphqlPolicy.recipe())
        }
        if (item.family.id == LocalUbuntuTomlPolicy.FAMILY) {
            require(item.version.version==LocalUbuntuTomlPolicy.VERSION) { LocalUbuntuTomlPolicy.PREREQUISITE_MESSAGE }
            return npmPlan(item,LocalUbuntuTomlPolicy.recipe())
        }
        if (item.family.id == LocalUbuntuVuePolicy.FAMILY) {
            require(item.version.version == LocalUbuntuVuePolicy.VERSION) { LocalUbuntuVuePolicy.PREREQUISITE_MESSAGE }
            return npmPlan(item,LocalUbuntuVuePolicy.recipe())
        }
        UbuntuNpmCatalog.find(item.family.id)?.let { entry ->
            val version = if (item.version.version in setOf(UbuntuNpmCatalog.VERSION, PackageSourceCatalog.LATEST_OFFICIAL_VERSION, "latest")) "latest" else item.version.version
            require(entry.allowsPublisherUpdates || entry.reviewedVersion == null || version == entry.reviewedVersion) {
                "${entry.packageName} $version has not been reviewed. Select the explicit reviewed ${entry.reviewedVersion} recipe; no other version was substituted."
            }
            val data = npmMetadata(entry.packageName, version)
            if (entry.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) LocalUbuntuSveltePolicy.validateMetadata(data)
            if (entry.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) LocalUbuntuPrismaPolicy.validateMetadata(data)
            val exact = data["version"]?.jsonPrimitive?.content ?: error("npm version is missing")
            entry.reviewedIntegrity?.takeIf { exact == entry.reviewedVersion }?.let { expected ->
                require(data["dist"]?.jsonObject?.get("integrity")?.jsonPrimitive?.content == expected) { "Reviewed npm integrity changed" }
            }
            val additional = when (entry.familyId) {
                "lsp.typescript" -> mapOf("typescript" to resolveTypeScriptServerVersion())
                LocalUbuntuPerlNavigatorPolicy.FAMILY_ID -> if (exact == entry.reviewedVersion) LocalUbuntuPerlNavigatorPolicy.dependencies else emptyMap()
                LocalUbuntuSveltePolicy.FAMILY_ID -> LocalUbuntuSveltePolicy.dependencies
                LocalUbuntuPrismaPolicy.FAMILY_ID -> LocalUbuntuPrismaPolicy.dependencies
                else -> emptyMap()
            }
            val recipe = WorkstationInstallRecipe(entry.familyId, exact, entry.packageName, exact, entry.command, entry.minimumNodeMajor,
                entry.provenance, registryIntegrity = data["dist"]?.jsonObject?.get("integrity")?.jsonPrimitive?.content,
                allowLifecycleScripts = entry.allowLifecycleScripts, lifecycleScriptReview = entry.lifecycleScriptReview,
                additionalDependencies = additional).also { it.validate(); require(LocalUbuntuNpmPolicy.supports(it)) }
            return npmPlan(item, recipe, data)
        }
        LocalUbuntuBundleAliasPolicy.find(item.family.id)?.let { recipe ->
            val requested = item.version.version.takeIf { it.startsWith(recipe.bundle + "-") }?.removePrefix(recipe.bundle + "-")
            if (recipe.primaryFamilyId == "build.gradle") {
                val primary = localVendorResolver.resolve("build.gradle", requested ?: "Latest official")
                return ExtensionInstallPlan.LocalVendorAlias(item.family.id, item.family.name, item.version.version, recipe, primary,
                    listOf("Bundled commands: ${recipe.commands.keys.joinToString()}", "Gradle ${primary.version}",
                        "Publisher SHA-256 ${primary.assetSha256}", "Exact Gradle dependency; independent command integration"))
            }
            val primary = if (recipe.primaryFamilyId == "runtime.node") resolveNode(requested)
                else sourceResolver.resolveReviewedVendorOfficial(recipe.primaryFamilyId, requested ?: PackageSourceCatalog.LATEST_OFFICIAL_VERSION)
            require(localReviewedWholeTreeInstaller.supports(primary)) { "Primary SDK has no local Ubuntu whole-tree transaction" }
            return ExtensionInstallPlan.LocalBundleAlias(item.family.id, item.family.name, item.version.version, recipe, primary,
                listOf("Bundled commands: ${recipe.commands.keys.joinToString()}", "${recipe.primaryFamilyId} ${primary.version}",
                    "Publisher SHA-256 ${primary.assetSha256}", "Exact SDK dependency; independent command integration"))
        }
        LocalUbuntuVendorCatalog.find(item.family.id)?.let { definition ->
            val version = if (item.version.version == LocalUbuntuVendorPolicy.VERSION_CHOICE) "Latest official" else item.version.version
            val recipe = localVendorResolver.resolve(definition, version)
            if(recipe.familyId==LocalUbuntuElpPolicy.FAMILY) return ExtensionInstallPlan.LocalVendor(item.family.id,item.family.name,item.version.version,recipe,
                listOf("Exact original ELP ${recipe.releaseTag}, ${recipe.assetSize} bytes, SHA-256 ${recipe.assetSha256}",
                    "Preinstalled selected OTP and escript are prerequisites with unverified provenance; no runtime is installed or substituted.",
                    "A separate explicit health approval will show selected paths and fingerprints before execution.",
                    "No Rebar/project execution in installer health. Native transport plus OTP compatibility and complete cleanup gate activation.",LocalUbuntuElpPolicy.COVERAGE))
            if (recipe.familyId == LocalUbuntuNimPolicy.FAMILY) {
                val helper=localVendorResolver.resolve(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuNimHelperPolicy.VERSION)
                val tool=localVendorResolver.resolve(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuNimToolchainPolicy.VERSION)
                return ExtensionInstallPlan.LocalVendor(item.family.id,item.family.name,item.version.version,recipe,
                    listOf("Three original artifacts, separately journaled; one server. A later failure may leave an unused private prerequisite installed.",
                        "Private Node ${helper.version}: ${helper.assetSize} bytes, SHA-256 ${helper.assetSha256}",
                        "Private Nim/nimsuggest ${tool.version} and Nimble 0.24.1: ${tool.assetSize} bytes, SHA-256 ${tool.assetSha256}",
                        "nimlangserver ${recipe.version}: ${recipe.assetSize} bytes, SHA-256 ${recipe.assetSha256}",
                        "Fixed-toolchain analysis requires informed project consent. Automatic Nimble compiler selection is disabled; metadata tools such as git/hg are unavailable on the restricted PATH.",
                        "Interactive Nim and Node defaults are unchanged. Unknown layout/ELF/config/closure fails closed; zero-document health proves transport and owned cleanup only."),
                    nimDependencies=listOf(helper,tool))
            }
            val node = if (LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe)) resolveNode(LocalUbuntuJsDebugPolicy.NODE_VERSION) else null
            val powerShell = if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)
                localVendorResolver.resolve(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellRuntimePolicy.VERSION) else null
            return ExtensionInstallPlan.LocalVendor(item.family.id, item.family.name, item.version.version, recipe,
                listOf(if (powerShell != null) "Original publisher cross-platform PSES module ZIP" else if (node == null) "Original publisher Linux ARM64 artifact" else "Original publisher cross-platform JavaScript DAP bundle",
                    "Version ${recipe.version}", recipe.assetSha512?.let { "Publisher SHA-512 $it" } ?: "Publisher SHA-256 ${recipe.assetSha256}",
                    "Ubuntu prerequisites: ${recipe.requiredPackages.joinToString()}", "Bounded extraction, command health and atomic activation") +
                    if (node == null) emptyList() else listOf("Exact owned Node.js ${node.version}; existing healthy installation is reused", "Node.js publisher SHA-256 ${node.assetSha256}"),
                nodeDependency = node, powerShellDependency = powerShell).let { plan ->
                    if (powerShell == null) plan else plan.copy(components = plan.components + listOf(
                        "Exact owned PowerShell ${powerShell.version} runtime: ${powerShell.assetSize} bytes; SHA-256 ${powerShell.assetSha256}",
                        "PSES ${recipe.version}: ${recipe.assetSize} bytes; two separately journaled artifacts, one language server",
                        "Node supervises the editor private pipe and health; no dotnet SDK or Gallery installation",
                        "Signed Ubuntu prerequisites execute ordinary package configuration scripts; pending shared repair must be completed separately",
                        "Interactive PowerShell default is preserved, including no selected default; profiles and live module/completer code require project consent"))
                }
        }
        prepareBundleProjection(item)?.let { return it }
        WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)?.let { recipe ->
            _state.value = State(Phase.IDLE, "Reviewed exact-version package recipe ready. Node.js and npm prerequisites will be checked in local Ubuntu.", item.family.id, item.version.version)
            return npmPlan(item, recipe)
        }

        val source = sourceCatalog.installable(item.family.id)
            ?: error("No certified transactional installer exists for ${item.family.name}")
        return when (source.source) {
            PackageSourceKind.NPM -> {
                val recipe = sourceResolver.resolveReviewedNpm(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official npm metadata resolved to an exact integrity-bound recipe.", item.family.id, recipe.version)
                npmPlan(item, recipe)
            }
            PackageSourceKind.PYPI -> {
                val recipe = sourceResolver.resolveReviewedPyPi(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official PyPI metadata resolved to an exact wheel-hash-bound recipe.", item.family.id, recipe.version)
                ExtensionInstallPlan.PyPi(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("PyPI ${recipe.packageName}==${recipe.packageVersion}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Release wheel SHA-256 candidates: ${recipe.releaseWheelSha256.size}")
                        add("Provenance: ${recipe.provenanceUrl}")
                        recipe.requiresPython?.let { add("Upstream Requires-Python: $it") }
                        add("Python ${recipe.minimumPythonMajor}.${recipe.minimumPythonMinor}+")
                        add("Wheel-only dependency closure")
                        add("Complete SHA-256 hash lock + offline installation")
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + health check")
                    },
                )
            }
            PackageSourceKind.GO -> {
                val recipe = sourceResolver.resolveReviewedGo(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official Go module metadata resolved to an exact sumdb-authenticated recipe.", item.family.id, recipe.version)
                ExtensionInstallPlan.Go(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("Go ${recipe.packagePath}@${recipe.version}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Module: ${recipe.modulePath}")
                        add("Provenance: ${recipe.provenanceUrl}")
                        add("Go ${recipe.minimumGoMajor}.${recipe.minimumGoMinor}+")
                        add("proxy.golang.org only; VCS fallback disabled")
                        add("sum.golang.org checksum authentication")
                        add("Workspace command: ${recipe.command}")
                        add("Embedded module-closure lock + post-install integrity manifest")
                    },
                )
            }
            PackageSourceKind.RUBYGEMS -> {
                val recipe = sourceResolver.resolveReviewedRubyGems(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official RubyGems metadata resolved to an exact SHA-256-bound Bundler transaction.", item.family.id, recipe.version)
                ExtensionInstallPlan.RubyGems(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("RubyGems ${recipe.gemName}==${recipe.version}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Official root SHA-256: ${recipe.rootSha256}")
                        recipe.requiredRuby?.let { add("Upstream Ruby requirement: $it") }
                        add("Bundler exact dependency lock + CHECKSUMS")
                        add("Offline final install from verified vendor/cache")
                        add("Workspace command: ${recipe.command}")
                    },
                )
            }
            PackageSourceKind.GITHUB_RELEASE -> {
                val recipe = sourceResolver.resolveReviewedGitHubRelease(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official GitHub release metadata resolved to one exact SHA-256-bound Linux/ARM64 artifact.", item.family.id, recipe.version)
                ExtensionInstallPlan.GitHubRelease(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("GitHub ${recipe.repository}@${recipe.releaseTag}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Asset: ${recipe.assetName} (${recipe.assetSize} bytes)")
                        add("GitHub asset SHA-256: ${recipe.assetSha256}")
                        add("Artifact format: ${recipe.artifactFormat.name}")
                        if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                            add("Managed interpreter: ${recipe.interpreterFamilyId}/${recipe.interpreterCommand}")
                            add("Authenticated single-file PHAR projection")
                        } else if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                            add("Safe bounded whole-tree projection: ${recipe.treeEntryPoint}")
                            add("Expanded bounds: ${recipe.treeMaxFiles} files / ${recipe.treeMaxUnpackedBytes} bytes")
                        } else {
                            add("Platform: Linux ARM64 / ELF64 AArch64")
                            add("Safe single-executable projection: ${recipe.executableBasename}")
                        }
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + health check")
                    },
                )
            }
            PackageSourceKind.VENDOR_OFFICIAL -> {
                val recipe = sourceResolver.resolveReviewedVendorOfficial(item.family.id, item.version.version)
                _state.value = State(Phase.IDLE, "Official vendor metadata resolved to an exact publisher-checksum-bound Linux/ARM64 artifact.", item.family.id, recipe.version)
                ExtensionInstallPlan.VendorOfficial(
                    familyId = item.family.id,
                    familyName = item.family.name,
                    requestedVersion = item.version.version,
                    recipe = recipe,
                    components = buildList {
                        add("Official vendor ${recipe.releaseTag}")
                        if (item.version.version == PackageSourceCatalog.LATEST_OFFICIAL_VERSION) add("Resolved exact version: ${recipe.version}")
                        add("Asset: ${recipe.assetName}")
                        add("Publisher SHA-256: ${recipe.assetSha256}")
                        add("Artifact format: ${recipe.artifactFormat.name}")
                        if (recipe.artifactFormat == DeclarativeGitHubArtifactFormat.PHAR) {
                            add("Managed interpreter: ${recipe.interpreterFamilyId}/${recipe.interpreterCommand}")
                            add("Authenticated vendor PHAR projection")
                        } else if (recipe.artifactLayout == DeclarativeGitHubArtifactLayout.WHOLE_TREE) {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                            add("Safe bounded whole-tree projection: ${recipe.treeEntryPoint}")
                            add("Expanded bounds: ${recipe.treeMaxFiles} files / ${recipe.treeMaxUnpackedBytes} bytes")
                        } else {
                            add("Platform: Linux ARM64 / ${recipe.runtimeCompatibility.libc.name}")
                        }
                        add("Workspace command: ${recipe.command}")
                        add("Post-install integrity manifest + guest health check")
                    },
                )
            }
            else -> error("Certified transaction backend is not implemented for ${source.source.label}")
        }
    }

    private suspend fun prepareBundleProjection(item: ExtensionVersionState) = bundleProjectionCoordinator.prepare(item)?.also {
        _state.value = State(Phase.IDLE, "Shared bundle projection ready. The primary package is installed once and reused transactionally.", item.family.id, item.version.version)
    }

    private suspend fun prepareAndroidLocalWorkstation(item: ExtensionVersionState): ExtensionInstallPlan.AndroidLocalWorkstation {
        val entries = LocalAndroidWorkstationPolicy.components(AndroidLocalComponentCatalog.load(appContext))
        val gradle = localVendorResolver.resolve("build.gradle", LocalAndroidWorkstationPolicy.GRADLE_VERSION).also(LocalAndroidWorkstationPolicy::requireGradle)
        val license = licenseManager.fetchCurrent()
        return ExtensionInstallPlan.AndroidLocalWorkstation(item.family.id, item.family.name, item.version.version, entries, gradle,
            license, licenseManager.isAccepted(license), listOf("Signed Ubuntu OpenJDK 17", "Google Android SDK Platform 36",
                "Google Build Tools 36.0.0 with four pinned ARM64 native overlays", "Original Gradle 8.13 with publisher SHA-256",
                "Owned component receipts and complete command health check", "Project Gradle Wrapper remains the build authority",
                "Completed components remain installed if a later step is canceled", "Run/debug on a device uses the configured Android connection"))
    }

    private suspend fun npmPlan(item: ExtensionVersionState, recipe: WorkstationInstallRecipe,
        preparedMetadata: JsonObject? = null): ExtensionInstallPlan.Recipe {
        if (recipe.familyId == LocalUbuntuGraphqlPolicy.FAMILY) {
            require(LocalUbuntuGraphqlPolicy.supports(recipe)&&item.version.version==LocalUbuntuGraphqlPolicy.VERSION)
            val node=localVendorResolver.resolve(LocalUbuntuGraphqlNodePolicy.FAMILY,LocalUbuntuGraphqlNodePolicy.VERSION)
            return ExtensionInstallPlan.Recipe(item.family.id,item.family.name,item.version.version,recipe,null,
                listOf("Two explicitly disclosed separately journaled installations; one GraphQL server configuration. A later server failure may leave the private Node prerequisite installed.",
                    "Five exact original GraphQL npm archives: publisher SHA-512 authentication and strict bounded data-only extraction. No npm, lifecycle/build scripts or original server/CLI imports.",
                    "Independent runtime.graphql-node 22.23.3: 56,655,163 bytes, SHA-256 ${node.assetSha256}; terminal Node defaults unchanged.",
                    "All original contents/notices retained. Strict original manifest/import/notice and full Node/bash/env native closure are activation gates.",
                    "Genuine isolated native validation/completion/hover/outline and verified owned cleanup required. Original/native/device acceptance remains unrun in the source checkpoint."),graphqlNodeDependency=node)
        }
        if (recipe.familyId == LocalUbuntuTomlPolicy.FAMILY) {
            require(LocalUbuntuTomlPolicy.supports(recipe)&&item.version.version==LocalUbuntuTomlPolicy.VERSION)
            val node=localVendorResolver.resolve(LocalUbuntuTomlNodePolicy.FAMILY,LocalUbuntuTomlNodePolicy.VERSION)
            return ExtensionInstallPlan.Recipe(item.family.id,item.family.name,item.version.version,recipe,null,
                listOf("Two explicit separately journaled installs, one TOML server configuration. A later failure may leave unused private Node installed.",
                    "Original @taplo/lsp 0.8.0 and @taplo/core 0.2.0; exact publisher SHA-512 before bounded data-only archive decoding. No npm or lifecycle execution.",
                    "Independent runtime.taplo-node 22.23.3: 56,655,163 bytes, SHA-256 ${node.assetSha256}; terminal Node selection stays unchanged.",
                    "All originals and the 244-candidate conservative legal bundle are retained; not a linked SBOM. Exact public MPL source availability is checked at installation/publication.",
                    "Seventeen fixed HTTPS docs.rs/crate/webpki-roots/0.22.4/source/ requests: 2 MiB each, 16 MiB total, 10 seconds each/60 seconds total, maximum four concurrent; no redirects to another URL.",
                    "Genuine local schema/format/symbol health and verified owned cleanup gate activation. Original/device acceptance remains unrun in the source checkpoint."),tomlNodeDependency=node)
        }
        if (recipe.familyId == LocalUbuntuVuePolicy.FAMILY) {
            require(LocalUbuntuVuePolicy.supports(recipe) && item.version.version == LocalUbuntuVuePolicy.VERSION)
            val helper = localVendorResolver.resolve(LocalUbuntuVueHelperPolicy.FAMILY,LocalUbuntuVueHelperPolicy.VERSION)
            return ExtensionInstallPlan.Recipe(item.family.id,item.family.name,item.version.version,recipe,null,
                listOf("Two explicit separately journaled package prerequisites, one Vue configuration; a later failure may leave the unused private Node installed.",
                    "Original Vue/server plugin 3.3.13, TypeScript 6.0.3 and private TS-LSP 6.0.2; 89 SHA-512 identities in 90 physical placements.",
                    "Independent private Node 22.23.3: 56,655,163 compressed bytes; SHA-256 ${helper.assetSha256}. Terminal Node and Nim defaults remain unchanged.",
                    "Finite data-only archive extraction; no npm, lifecycle scripts, moving latest or project compiler/runtime substitution.",
                    "Native paired staged health and verified owned cleanup gate activation. Trusted project/ancestor config, plugins and network effects need separate project consent.",
                    "All shipped license data is retained. Complete attribution of unopened TS-LSP embedded bundle remains unresolved; source/native/device acceptance is separate."),
                vueHelperDependency=helper)
        }
        val svelte = recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID
        val runtimeReady = !LocalUbuntuNpmPolicy.supports(recipe) || hasNodeRuntime(recipe, preparedMetadata)
        require(!svelte || runtimeReady) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
        val dependency = if (!svelte && !runtimeReady) resolveNode() else null
        val museRuntime = if (recipe.familyId == LocalUbuntuMusePolicy.FAMILY)
            localVendorResolver.resolve(LocalUbuntuAgentDistributions.MUSE_RUNTIME) else null
        _state.value = State(Phase.IDLE, "Exact npm plan ready.", item.family.id, recipe.version)
        return ExtensionInstallPlan.Recipe(item.family.id, item.family.name, item.version.version, recipe, dependency,
            buildList {
                add("npm ${recipe.packageName}@${recipe.packageVersion}")
                museRuntime?.let {
                    add("Official Meta Muse ${it.version}: ${it.assetSize} bytes; SHA-256 ${it.assetSha256}")
                    add("Community ACP adapter with official Meta SDK; terminal and chat use the same versioned Muse executable")
                    add("Separately managed runtime and adapter; a failed adapter install may leave its reusable runtime installed")
                }
                recipe.additionalDependencies.forEach { (name, version) -> add("npm $name@$version") }
                if (dependency != null) {
                    add("Download Node.js ${dependency.version} + npm for Ubuntu ARM64")
                    add("Node.js publisher SHA-256 ${dependency.assetSha256}")
                } else add(if (svelte) "Use the already owned, reviewed and selected Node.js 22+ runtime with npm 10+; no automatic prerequisite setup"
                    else "Use installed Linux ARM64 Node.js ${recipe.minimumNodeMajor}+ and npm 10+")
                add("Commands: ${LocalUbuntuNpmPolicy.commands(recipe).keys.joinToString()}")
                add(if (recipe.allowLifecycleScripts) "OpenCode root binary selector enabled; dependency scripts blocked"
                    else "Dependency lifecycle scripts blocked")
                add("Integrity seal and health check before activation")
                if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) {
                    add("Exact Svelte 0.18.5, TypeScript 5.9.3, 64 required identities and every physical dependency/peer edge")
                    add("Reviewed prettier-plugin-svelte4 alias to prettier-plugin-svelte 4.1.1; optional audit peers must remain absent")
                    add("Owned Node and private state before every probe; original public bin, zero documents and bounded owned cleanup")
                    add("Actual npm resolution and ARM64 native semantics remain unaccepted until tested; a source recipe is not runtime certification")
                    add("Project analysis requires separate consent for compiler, Vite/Svelte/preprocessor/Prettier code and filesystem/network access")
                }
                if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) {
                    add("Exact 44-package Prisma closure with a public-root launcher and dedicated zero-document health")
                    add("Legacy-schema integration; Prisma Next/ORM 8 and Prisma 6 compatibility are not established")
                    add("Health uses private empty directories and environment; it does not sandbox filesystem, credentials or network access")
                    add("Project analysis separately requires consent to execute project JavaScript/TypeScript configuration")
                    add("Discovered/custom command provenance remains unverified; managed installation does not certify semantic or device acceptance")
                }
            }, museRuntimeDependency = museRuntime)
    }
    private suspend fun npmMetadata(name: String, version: String): kotlinx.serialization.json.JsonObject {
        require(name.matches(Regex("(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+")))
        require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")))
        val key = java.net.URLEncoder.encode(name, "UTF-8")
        val response = SafeHttp.get("https://registry.npmjs.org/$key/$version", accept = "application/json", maxBytes = 1_000_000)
        check(response.code == 200) { "Official npm metadata is unavailable (HTTP ${response.code})" }
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(response.body).jsonObject
        require(obj["name"]?.jsonPrimitive?.content == name)
        require(version.firstOrNull()?.isLetter() == true || obj["version"]?.jsonPrimitive?.content == version) { "Official npm metadata resolved a different version" }
        return obj
    }
    private suspend fun resolveTypeScriptServerVersion(): String {
        val latest = npmMetadata("typescript", "latest")
        return runCatching { LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(latest) }.getOrElse {
            // Compiler releases that no longer ship tsserver cannot service the legacy LSP adapter. Keep a
            // compatible analysis engine, not a compiler version cap.
            LocalUbuntuTypeScriptServerPolicy.requireCompatibleMetadata(npmMetadata("typescript", LocalUbuntuTypeScriptServerPolicy.VERSION))
        }
    }
    private suspend fun resolveNode(exact: String? = null): VendorArtifactInstallRecipe {
        val selector = exact?.removePrefix("v")
        val authority = when {
            selector == null || selector == "latest" -> LocalUbuntuNodeDistribution.LIVE_AUTHORITY
            selector.matches(Regex("[0-9]+")) -> "https://nodejs.org/download/release/latest-v$selector.x/SHASUMS256.txt"
            selector == "lts" -> {
                val index = SafeHttp.get("https://nodejs.org/download/release/index.json", maxBytes = 1_000_000)
                check(index.code == 200) { "Node.js release metadata is unavailable" }
                val version = Json.parseToJsonElement(index.body).jsonArray.mapNotNull { it as? JsonObject }
                    .firstOrNull { it["lts"]?.jsonPrimitive?.contentOrNull?.let { value -> value != "false" && value.isNotBlank() } == true }
                    ?.get("version")?.jsonPrimitive?.content?.removePrefix("v") ?: error("Node.js LTS release is unavailable")
                "https://nodejs.org/download/release/v$version/SHASUMS256.txt"
            }
            else -> {
                require(selector.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) { "Use latest, lts, a Node.js major or an exact version" }
                "https://nodejs.org/download/release/v$selector/SHASUMS256.txt"
            }
        }
        val result = SafeHttp.get(authority, maxBytes = 128_000)
        check(result.code == 200) { "Official Node.js checksums are unavailable (HTTP ${result.code})" }
        return LocalUbuntuNodeDistribution.resolve(result.body).also { recipe ->
            selector?.takeIf { it != "lts" && '.' in it }?.let { require(recipe.version == it) { "Node.js publisher resolved a different exact version" } }
        }
    }
    private suspend fun hasNodeRuntime(recipe: WorkstationInstallRecipe, preparedMetadata: JsonObject? = null): Boolean {
        if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) return runSuspendCatching {
            val node = selectedSvelteManagedNode() ?: return@runSuspendCatching false
            localNpmInstaller.probeSvelteRuntime(node); true
        }.getOrDefault(false)
        if (!LocalExecutionSubstrate.inspectLinuxState().ready) return false
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        // A present metadata object without engines is authoritative for this preparation.
        val metadata = preparedMetadata ?: npmMetadata(recipe.packageName, recipe.packageVersion)
        val node = LocalProcessSupervisor.capture(launch.command(LocalUbuntuNpmRuntimePolicy.command(
            recipe.minimumNodeMajor, LocalUbuntuNpmRuntimePolicy.engine(metadata))),
            projectRoot, launch.environment, maxOutputBytes = 4_000, timeoutMs = 10_000)
        if (node.exitCode != 0 || node.timedOut || !LocalUbuntuNpmRuntimePolicy.compatible(node.output)) return false
        val npm = LocalProcessSupervisor.capture(launch.command(listOf("npm", "--version")), projectRoot, launch.environment,
            maxOutputBytes = 4_000, timeoutMs = 10_000)
        return npm.exitCode == 0 && npm.output.trim().substringBefore('.').toIntOrNull()?.let { it >= 10 } == true
    }

    private suspend fun selectedSvelteManagedNode(): ManagedPackageRecord? {
        val records = registry.list()
        val selections = WorkspaceToolchainPreferences(appContext, projectRoot).selections()
        val record = LocalLinuxPackageEnvironment.selectedRecords(records,selections,appContext.filesDir.canonicalPath)
            .singleOrNull { it.familyId == "runtime.node" } ?: return null
        if (selections["runtime.node"] != null && selections["runtime.node"] != record.version) return null
        return record.takeIf { localPackageAuthority.verifySvelteNode(it) }
    }

    private suspend fun selectedLocalManagedNode(): ManagedPackageRecord? {
        if (!LocalExecutionSubstrate.inspectLinuxState().ready) return null
        val launch = LocalExecutionSubstrate.linuxLaunchSpec(projectRoot)
        val probe = LocalProcessSupervisor.capture(launch.command(listOf("node", "-p", "process.execPath")),
            projectRoot, launch.environment, maxOutputBytes = 4_000, timeoutMs = 10_000)
        if (probe.exitCode != 0 || probe.timedOut) return null
        val executable = probe.output.trim()
        return registry.list().firstOrNull { record ->
            record.active && localPackageAuthority.owns(record) &&
                LocalUbuntuNpmRuntimePolicy.matchesManagedNode(record, executable, appContext.filesDir.canonicalPath)
        }
    }

    private fun resolveDependencies(document: ManagedPackageCatalogDocument, target: ManagedPackageCatalogEntry): List<ManagedPackageCatalogEntry> =
        ManagedPackageDependencyResolver.resolve(document, target, Build.SUPPORTED_ABIS.toList())

    suspend fun acceptLicense(plan: ExtensionInstallPlan.Android) {
        requireInstalledComponents()
        require(plan.license.sha256 == plan.toolchainEntry.sdkLicenseSha256) { "License/catalog mismatch" }
        licenseManager.accept(plan.license)
        _state.value = State(Phase.LICENSE, "Android SDK license accepted for this exact revision.", plan.familyId, plan.requestedVersion)
    }

    suspend fun acceptLicense(plan: ExtensionInstallPlan.AndroidLocalComponent) {
        requireInstalledComponents()
        licenseManager.accept(plan.license)
        _state.value = State(Phase.LICENSE, "Android SDK license accepted for this component install.", plan.familyId, plan.requestedVersion)
    }
    suspend fun acceptLicense(plan: ExtensionInstallPlan.AndroidLocalWorkstation) {
        requireInstalledComponents()
        licenseManager.accept(plan.license)
        _state.value = State(Phase.LICENSE, "Android SDK license accepted for this workstation install.", plan.familyId, plan.requestedVersion)
    }

    /** Observe paths/entry fingerprints only; never run selected tools before the dialog. */
    suspend fun requestElpHealthApproval(plan:ExtensionInstallPlan.LocalVendor):ElpRuntimeHealthApprovalRequest {
        require(plan.familyId==LocalUbuntuElpPolicy.FAMILY&&plan.elpHealthPermit==null)
        LocalUbuntuElpPolicy.requireReviewed(plan.recipe)
        val selection=LocalExecutionSubstrate.inspectElpHealthSelection(ElpDeadline.after(20_000))
        return ElpRuntimeHealthApprovalRequest(selection).also{it.validate()}
    }

    suspend fun install(plan: ExtensionInstallPlan): String =
        if (LocalUbuntuGraphqlPolicy.isFamily(plan.familyId)) installGraphqlPlan(plan)
        else if (plan is ExtensionInstallPlan.GuestPackage) LegacyAlpineRuntimeRetirement.reject()
        else operation(
        plan.familyId, plan.resolvedVersion, Phase.INSTALLING,
        timeoutMs = InstallerExecutionPolicy.INSTALL_OPERATION_DEADLINE_MS,
    ) {

        val localReviewed = when (plan) {
            is ExtensionInstallPlan.GitHubRelease ->
                localReviewedRawInstaller.supports(plan.recipe) || localReviewedWholeTreeInstaller.supports(plan.recipe)
            is ExtensionInstallPlan.VendorOfficial -> localReviewedWholeTreeInstaller.supports(plan.recipe)
            else -> false
        }
        UbuntuExtensionPlanPolicy.requireSupported(plan, localReviewed)
        val operationLease = longOperationJournal.begin(
            LongRunningOperationJournal.Kind.PACKAGE,
            "${plan.familyId}@${plan.resolvedVersion}",
        )
        try {
            val localAppPlan = plan is ExtensionInstallPlan.LocalCSharp || localReviewed || plan is ExtensionInstallPlan.UserManaged || plan is ExtensionInstallPlan.AndroidLocalWorkstation || plan is ExtensionInstallPlan.LocalCMake || plan is ExtensionInstallPlan.LocalRuby || plan is ExtensionInstallPlan.LocalVendorAlias || plan is ExtensionInstallPlan.LocalVendor || plan is ExtensionInstallPlan.LocalBundleAlias || plan is ExtensionInstallPlan.UbuntuApt || plan is ExtensionInstallPlan.AndroidLocalComponent ||
                (plan is ExtensionInstallPlan.Recipe && LocalUbuntuNpmPolicy.supports(plan.recipe))
            when (plan) {
                is ExtensionInstallPlan.GuestPackage -> guestPackageInstaller.requireBackend(plan.familyId)
                is ExtensionInstallPlan.LocalCSharp, is ExtensionInstallPlan.UserManaged, is ExtensionInstallPlan.AndroidLocalWorkstation, is ExtensionInstallPlan.LocalCMake, is ExtensionInstallPlan.LocalRuby, is ExtensionInstallPlan.LocalVendorAlias, is ExtensionInstallPlan.LocalVendor, is ExtensionInstallPlan.LocalBundleAlias, is ExtensionInstallPlan.UbuntuApt, is ExtensionInstallPlan.Android, is ExtensionInstallPlan.AndroidLocalComponent, is ExtensionInstallPlan.Package, is ExtensionInstallPlan.Recipe -> Unit
                is ExtensionInstallPlan.GitHubRelease, is ExtensionInstallPlan.VendorOfficial -> if (!localReviewed) guestPackageInstaller.requireMixedBackend()
                else -> guestPackageInstaller.requireMixedBackend()
            }
            if (plan !is ExtensionInstallPlan.GuestPackage && !localAppPlan) ensurePackageBackend(plan.familyId, plan.requestedVersion)
            val result = when (plan) {
                is ExtensionInstallPlan.UserManaged -> installUserManaged(plan)
                is ExtensionInstallPlan.Android -> ManagedPackageMutationGate.mutex.withLock { installAndroid(plan) }
                is ExtensionInstallPlan.AndroidLocalComponent -> installAndroidLocalComponent(plan)
                is ExtensionInstallPlan.AndroidLocalWorkstation -> installAndroidLocalWorkstation(plan)
                is ExtensionInstallPlan.Package -> installPackage(plan)
                is ExtensionInstallPlan.GuestPackage -> installGuestPackage(plan)
                is ExtensionInstallPlan.UbuntuApt -> {
                    val record = aptInstaller.install(plan.recipe, ::progress)
                    _state.value = State(Phase.READY, "Ubuntu commands installed and verified.", plan.familyId, plan.recipe.version, progressLog.append("\nCompleted\n"))
                    "Installed ${record.familyId}: ${record.commands.keys.joinToString()}"
                }
                is ExtensionInstallPlan.LocalCMake -> {
                    val record = localCMakeInstaller.install(plan.recipe, ::progress)
                    _state.value = State(Phase.READY, "CMake five-wheel integration installed and lifecycle verified.", plan.familyId, record.version)
                    "Installed exact CMake ${record.version}; choose source/build and Enable analysis before project execution."
                }
                is ExtensionInstallPlan.LocalRuby -> {
                    val record = localRubyInstaller.install(plan.recipe, ::progress)
                    _state.value = State(Phase.READY, "Ruby LSP installed and verified in local Ubuntu.", plan.familyId, record.version)
                    "Installed original Ruby LSP ${record.version} with its frozen dependency bundle."
                }
                is ExtensionInstallPlan.LocalCSharp -> {
                    val record=localCSharpInstaller.install(plan.recipe,::progress)
                    _state.value=State(Phase.READY,"Exact C# original artifact installed and verified.",plan.familyId,record.version)
                    "Installed ${record.familyId} ${record.version}; project analysis requires a saved selection and Enable."
                }
                is ExtensionInstallPlan.LocalVendor -> {
                    val record = installLocalVendor(plan.recipe, plan.nodeDependency, plan.powerShellDependency, plan.nimDependencies, plan.elpHealthPermit)
                    _state.value = State(Phase.READY, if(plan.familyId==LocalUbuntuElpPolicy.FAMILY) LocalUbuntuElpLspHealth.LABEL else "Publisher artifact installed and verified in Ubuntu.", plan.familyId, record.version)
                    "Installed ${record.familyId} ${record.version}: ${record.commands.keys.joinToString()}"
                }
                is ExtensionInstallPlan.LocalVendorAlias -> {
                    val existing = localPackageAuthority.ownedReceipt(plan.primary.familyId, plan.primary.version)?.takeIf {
                        it.artifactSha256 == plan.primary.assetSha256 && localPackageAuthority.verify(it)
                    }
                    val primary = existing ?: localReviewedWholeTreeInstaller.install(plan.primary, ::progress)
                    val record = localBundleAliasInstaller.install(plan.recipe, primary, ::progress)
                    _state.value = State(Phase.READY, "Gradle commands installed and verified.", plan.familyId, record.version)
                    "Installed ${record.familyId} ${record.version}: ${record.commands.keys.joinToString()}"
                }
                is ExtensionInstallPlan.LocalBundleAlias -> {
                    val existing = localPackageAuthority.ownedReceipt(plan.primary.familyId, plan.primary.version)?.takeIf {
                        it.artifactSha256 == plan.primary.assetSha256 && localPackageAuthority.verify(it)
                    }
                    val primary = existing ?: localReviewedWholeTreeInstaller.install(plan.primary, ::progress)
                    val record = localBundleAliasInstaller.install(plan.recipe, primary, ::progress)
                    _state.value = State(Phase.READY, "SDK commands installed and verified.", plan.familyId, record.version)
                    "Installed ${record.familyId} ${record.version}: ${record.commands.keys.joinToString()}"
                }
                is ExtensionInstallPlan.Recipe -> installRecipe(plan)
                is ExtensionInstallPlan.PyPi -> installPyPiRecipe(plan)
                is ExtensionInstallPlan.Go -> installGoRecipe(plan)
                is ExtensionInstallPlan.RubyGems -> installRubyGemsRecipe(plan)
                is ExtensionInstallPlan.GitHubRelease -> installGitHubRelease(plan)
                is ExtensionInstallPlan.VendorOfficial -> installVendorOfficial(plan)
                is ExtensionInstallPlan.BundleProjection -> installBundleProjection(plan)
            }
            operationLease.complete(LongRunningOperationJournal.summary(
                if (plan is ExtensionInstallPlan.UserManaged) result else "Package transaction committed"))
            result
        } catch (cancelled: CancellationException) {
            operationLease.cancel("Package installation canceled")
            _state.value = State(Phase.CANCELED, (if (plan is ExtensionInstallPlan.UserManaged) "Update canceled. Changes already applied by the package manager are retained." else "Installation canceled. No incomplete package was activated."), plan.familyId, plan.requestedVersion)
            throw cancelled
        } catch (error: Throwable) {
            operationLease.fail(LongRunningOperationJournal.summary(error.message ?: "Package installation failed"))
            _state.value = State(Phase.FAILED, error.message ?: "Installation failed", plan.familyId, plan.requestedVersion)
            throw error
        }
    }

    private suspend fun installGraphqlPlan(plan:ExtensionInstallPlan):String {
        val deadline=GraphqlDeadline.install()
        return withTimeout(deadline.remainingMillis().coerceAtLeast(1)) {
            operation(plan.familyId,plan.resolvedVersion,Phase.INSTALLING,timeoutMs=deadline.remainingMillis().coerceAtLeast(1)) {
                deadline.check();UbuntuExtensionPlanPolicy.requireSupported(plan,false)
                val lease=longOperationJournal.begin(LongRunningOperationJournal.Kind.PACKAGE,"${plan.familyId}@${plan.resolvedVersion}")
                try {
                    val installer=LocalUbuntuGraphqlInstaller(appContext,localPackageAuthority)
                    val record=when(plan) {
                        is ExtensionInstallPlan.LocalVendor -> {
                            require(plan.familyId==LocalUbuntuGraphqlNodePolicy.FAMILY&&plan.nodeDependency==null&&plan.powerShellDependency==null&&plan.nimDependencies.isEmpty())
                            installer.installNode(plan.recipe,::progress,deadline)
                        }
                        is ExtensionInstallPlan.Recipe -> {
                            require(plan.familyId==LocalUbuntuGraphqlPolicy.FAMILY&&LocalUbuntuGraphqlPolicy.supports(plan.recipe))
                            val prerequisite=requireNotNull(plan.graphqlNodeDependency);LocalUbuntuGraphqlNodePolicy.requireReviewed(prerequisite)
                            val node=LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId,prerequisite.version),
                                prerequisite.familyId,prerequisite.version,prerequisite.assetSha256,localPackageAuthority::owns,
                                { localPackageAuthority.verifyGraphqlPackageWithoutHealth(it,deadline) })
                                ?:installer.installNode(prerequisite,::progress,deadline)
                            installer.install(plan.recipe,node,::progress,deadline)
                        }
                        else -> error("Unsupported GraphQL installation plan")
                    }
                    lease.complete("Exact GraphQL transaction committed")
                    _state.value=State(Phase.READY,"Exact GraphQL component installed; local-schema analysis requires explicit consent.",plan.familyId,record.version)
                    "Installed ${record.familyId} ${record.version}; one GraphQL configuration with its separate private Node prerequisite."
                } catch(failure:Throwable) {
                    if(failure is CancellationException)lease.cancel("GraphQL installation canceled; uncertain owned state is retained")
                    else lease.fail(LongRunningOperationJournal.summary(failure.message?:"GraphQL installation failed"))
                    throw failure
                }
            }
        }
    }

    private suspend fun installAndroidLocalComponent(plan: ExtensionInstallPlan.AndroidLocalComponent): String {
        check(licenseManager.isAccepted(plan.license)) { "Accept the Android SDK license first" }
        _state.value = State(Phase.DOWNLOADING, "Downloading verified Android SDK component…", plan.familyId, plan.requestedVersion)
        val record = localAndroidSdkComponentInstaller.install(plan.entry)
        _state.value = State(Phase.ACTIVATING, "Verifying local Ubuntu SDK projection…", plan.familyId, plan.requestedVersion)
        check(localPackageAuthority.verify(record)) { "Installed Android SDK component failed local verification" }
        _state.value = State(Phase.READY, "Installed, integrity-checked and projected into local Ubuntu.", plan.familyId, plan.requestedVersion)
        return when (plan.entry.kind) {
            AndroidLocalComponentKind.SDK_PLATFORM -> "Installed Android SDK Platform ${plan.entry.version} for local Ubuntu."
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64 -> "Installed Android Build Tools ${plan.entry.version} for local Ubuntu with pinned ARM64 native overlays."
        }
    }

    private suspend fun installAndroidLocalWorkstation(plan: ExtensionInstallPlan.AndroidLocalWorkstation): String {
        check(licenseManager.isAccepted(plan.license)) { "Accept the Android SDK license first" }
        require(plan.familyId == LocalAndroidWorkstationPolicy.FAMILY && plan.resolvedVersion == LocalAndroidWorkstationPolicy.VERSION &&
            plan.entries == LocalAndroidWorkstationPolicy.components(AndroidLocalComponentCatalog.load(appContext))) { "Unreviewed Android workstation plan" }
        LocalAndroidWorkstationPolicy.requireGradle(plan.gradle)
        val dependencies = mutableListOf<ManagedPackageRecord>()
        suspend fun healthy(familyId: String, version: String) = localPackageAuthority.ownedReceipt(familyId, version)?.takeIf { localPackageAuthority.verify(it) }
        dependencies += healthy("toolchain.jdk", UbuntuAptCatalog.VERSION) ?: aptInstaller.install(requireNotNull(UbuntuAptCatalog.find("toolchain.jdk")), ::progress)
        for (entry in plan.entries) dependencies += healthy(entry.familyId, entry.version) ?: localAndroidSdkComponentInstaller.install(entry)
        dependencies += healthy("build.gradle", LocalAndroidWorkstationPolicy.GRADLE_VERSION) ?: localReviewedWholeTreeInstaller.install(plan.gradle, ::progress)
        val record = localAndroidWorkstationInstaller.install(dependencies, ::progress)
        _state.value = State(Phase.READY, "Android workstation components installed and verified together.", plan.familyId, record.version)
        return "Installed local Android workstation: OpenJDK 17, SDK 36, ARM64 Build Tools 36.0.0 and Gradle 8.13."
    }

    private suspend fun installAndroid(plan: ExtensionInstallPlan.Android): String {
        check(licenseManager.isAccepted(plan.license)) { "Accept the exact Android SDK license first" }
        _state.value = State(Phase.DOWNLOADING, "Downloading verified Android workstation package…", plan.familyId, plan.requestedVersion)
        val result = androidInstaller.install(plan.toolchainEntry, plan.license)
        _state.value = State(Phase.ACTIVATING, "Activating and health-checking package…", plan.familyId, plan.requestedVersion)
        val info = androidDevelopment.workstationInfo() ?: error("Installed package failed workstation health check")
        require(info.compileSdk == plan.toolchainEntry.compileSdk) { "Installed SDK does not match package plan" }
        require(info.javaVersion == plan.toolchainEntry.javaVersion) { "Installed JDK does not match package plan" }

        val root = "${DeviceBridgeManager.remoteRoot()}/toolchains/current"
        val now = System.currentTimeMillis()
        buildList {
            add(ManagedPackageRecord("sdk.android", info.compileSdk.toString(), ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            add(ManagedPackageRecord("toolchain.jdk", info.javaVersion.toString(), ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            info.buildToolsVersions.sorted().forEach { revision ->
                add(ManagedPackageRecord("build.android-tools", revision, ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
            }
            add(ManagedPackageRecord("meta.android-development", "SDK ${info.compileSdk}", ExecutionScope.LOCAL_LINUX_ARM64.name, root, now))
        }.forEach(registry::put)
        _state.value = State(Phase.READY, "Installed, activated and health-checked.", plan.familyId, plan.requestedVersion)
        return result
    }

    private suspend fun installPackage(plan: ExtensionInstallPlan.Package): String {
        _state.value = State(Phase.DOWNLOADING, "Installing verified package dependencies…", plan.familyId, plan.requestedVersion)
        val activeBefore = registry.list().filter { it.active }.associateBy { it.familyId }
        var installed = 0
        plan.entries.forEach { entry ->
            val existing = registry.find(entry.familyId, entry.version)
            val validExisting = existing
                ?.takeIf { it.scope == ExecutionScope.LOCAL_LINUX_ARM64.name }
                ?.let { record -> packageInstaller.verify(record) } == true
            if (!validExisting) {
                packageInstaller.install(entry)
                installed++
            } else if (existing != null && !existing.active) {
                packageInstaller.activate(existing)
            }
        }
        // Installing an exact dependency must not silently change an existing user default for that
        // dependency family.

        plan.entries.asSequence().filter { it.familyId != plan.familyId }.map { it.familyId }.distinct().forEach { family ->
            val previous = activeBefore[family] ?: return@forEach
            val current = registry.find(previous.familyId, previous.version) ?: return@forEach
            if (!current.active) packageInstaller.activate(current)
        }
        _state.value = State(Phase.READY, "Installed, activated and available through managed/bin.", plan.familyId, plan.requestedVersion)
        return if (installed == 0) "Package and dependencies were already installed and healthy." else "Installed $installed managed package${if (installed == 1) "" else "s"}. Commands are available through managed/bin in existing and new Device Workstation terminals."
    }

    suspend fun reconcileManagedPackages(): List<ManagedPackageRecord> {
        requireInstalledComponents()
        return packageInstaller.reconcile()
    }
    suspend fun reconcileLocalPackages(): String? {
        requireInstalledComponents()
        val legacyRecoveryPending = localPackageAuthority.reconcile()
        val legacyCleanupWarning = guestPackageInstaller.reconcilePendingGuestCleanup()
        return if (legacyRecoveryPending) LegacyAlpineRuntimeRetirement.PENDING_MESSAGE else legacyCleanupWarning
    }

    suspend fun verifyManagedRecord(record: ManagedPackageRecord): Boolean {
        requireInstalledComponents()
        return if (localPackageAuthority.owns(record)) localPackageAuthority.verify(record) else packageInstaller.verify(record)
    }

    suspend fun activate(item: ExtensionVersionState): String = operation(item.family.id, item.version.version, Phase.ACTIVATING) {
        val record = registry.find(item.family.id, item.version.version)
            ?: error("Managed package is not installed")
        require(item.version.installKind in setOf(ExtensionInstallKind.ANDROID_LOCAL_COMPONENT, ExtensionInstallKind.MANAGED_PACKAGE, ExtensionInstallKind.GUEST_PACKAGE, ExtensionInstallKind.REVIEWED_RECIPE)) { "This provider does not support version switching" }
        _state.value = State(Phase.ACTIVATING, "Activating ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        if (localPackageAuthority.owns(record)) localPackageAuthority.activate(record) else packageInstaller.activate(record)
        _state.value = State(Phase.READY, "Version activated.", item.family.id, item.version.version)
        "${item.family.name} ${item.version.version} is now active."
    }

    private suspend fun <T> withPreservedDefault(previous: ManagedPackageRecord?, block: suspend () -> T): T =
        withInstallerPrerequisiteRestoration(restore = {
            if (previous != null) {
                val current = registry.find(previous.familyId, previous.version)
                    ?: error("Previous prerequisite default receipt is missing")
                if (!current.active) {
                    if (localPackageAuthority.owns(current)) localPackageAuthority.activate(current)
                    else packageInstaller.activate(current)
                }
            }
        }, block = block)

    /** Only an explicit Install/Repair calls this; starting a debugger cannot install tools. */
    private suspend fun installLocalVendor(recipe: LocalUbuntuVendorRecipe,
        nodeRecipe: VendorArtifactInstallRecipe?, powerShellRecipe: LocalUbuntuVendorRecipe? = null,
        nimRecipes: List<LocalUbuntuVendorRecipe> = emptyList(), elpHealthPermit:ElpStageHealthPermit?=null): ManagedPackageRecord {
        if(recipe.familyId==LocalUbuntuElpPolicy.FAMILY) {
            require(nodeRecipe==null&&powerShellRecipe==null&&nimRecipes.isEmpty())
            return localReviewedWholeTreeInstaller.install(recipe,::progress,elpHealthPermit=elpHealthPermit)
        }
        require(elpHealthPermit==null)
        if (recipe.familyId == LocalUbuntuNimPolicy.FAMILY) {
            require(nodeRecipe==null && powerShellRecipe==null && nimRecipes.map { it.familyId }==listOf(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuNimToolchainPolicy.FAMILY))
            nimRecipes.forEach { it.validate() }
            val dependencies=nimRecipes.map { prerequisite ->
                LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId,prerequisite.version),
                    prerequisite.familyId,prerequisite.version,prerequisite.assetSha256,localPackageAuthority::owns,localPackageAuthority::verifyNimPackageWithoutHealth)
                    ?: localReviewedWholeTreeInstaller.install(prerequisite,::progress)
            }
            return localReviewedWholeTreeInstaller.install(recipe,::progress,nimDependencies=dependencies)
        }
        require(nimRecipes.isEmpty())
        if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) {
            require(nodeRecipe == null)
            val prerequisite = requireNotNull(powerShellRecipe) { "PSES plan must include its exact PowerShell runtime before confirmation" }
            LocalUbuntuPowerShellRuntimePolicy.requireReviewed(prerequisite)
            val previous = registry.list().filter { it.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY && it.active }
                .also { require(it.size <= 1) { "PowerShell default is ambiguous; explicit package repair is required" } }.singleOrNull()
            return withInstallerPrerequisiteRestoration(restore = { localPackageAuthority.restorePowerShellDefault(previous) }) {
                val runtime = LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId, prerequisite.version),
                    prerequisite.familyId, prerequisite.version, prerequisite.assetSha256, localPackageAuthority::owns, localPackageAuthority::verify)
                    ?: localReviewedWholeTreeInstaller.install(prerequisite, ::progress)
                LocalUbuntuPowerShellEditorServicesPolicy.requireRuntime(runtime, appContext.filesDir.canonicalPath)
                localReviewedWholeTreeInstaller.install(recipe, ::progress, powerShellDependency = runtime)
            }
        }
        require(powerShellRecipe == null) { "This recipe has no PowerShell prerequisite" }
        if (!LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe)) {
            require(nodeRecipe == null) { "This publisher recipe does not own a Node.js prerequisite" }
            return localReviewedWholeTreeInstaller.install(recipe, ::progress)
        }
        val prerequisite = requireNotNull(nodeRecipe) { "js-debug install plan is missing its exact Node.js prerequisite" }
        prerequisite.validate()
        require(prerequisite == LocalUbuntuNodeDistribution.recipe(LocalUbuntuJsDebugPolicy.NODE_VERSION, prerequisite.assetSha256)) {
            "js-debug install plan substituted its reviewed official Node.js recipe"
        }
        val previous = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
        return withPreservedDefault(previous) {
            val node = LocalInstalledPackageReuse.artifact(
                localPackageAuthority.ownedReceipt(prerequisite.familyId, prerequisite.version),
                prerequisite.familyId, prerequisite.version, prerequisite.assetSha256,
                localPackageAuthority::owns, localPackageAuthority::verify,
            )?.also { progress("Using verified installed Node.js ${it.version}; no download or extraction needed.\n") }
                ?: localReviewedWholeTreeInstaller.install(prerequisite, ::progress)
            LocalUbuntuJsDebugPolicy.requireNode(node, appContext.filesDir.canonicalPath)
            localReviewedWholeTreeInstaller.install(recipe, ::progress, nodeDependency = node)
        }
    }

    private suspend fun installRecipe(plan: ExtensionInstallPlan.Recipe): String {
        require(plan.recipe.familyId!=LocalUbuntuGraphqlPolicy.FAMILY) { "GraphQL requires its single absolute-deadline plan" }
        require(plan.graphqlNodeDependency==null) { "Unexpected GraphQL prerequisite on another plan" }
        require((plan.recipe.familyId == LocalUbuntuMusePolicy.FAMILY) == (plan.museRuntimeDependency != null)) {
            "Muse installation plan must include its native runtime"
        }
        if (plan.recipe.familyId == LocalUbuntuTomlPolicy.FAMILY) {
            require(LocalUbuntuTomlPolicy.supports(plan.recipe)&&plan.nodeDependency==null&&plan.vueHelperDependency==null)
            val prerequisite=requireNotNull(plan.tomlNodeDependency) { LocalUbuntuTomlPolicy.PREREQUISITE_MESSAGE }
            LocalUbuntuTomlNodePolicy.requireReviewed(prerequisite)
            val node=LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId,prerequisite.version),
                prerequisite.familyId,prerequisite.version,prerequisite.assetSha256,localPackageAuthority::owns,localPackageAuthority::verifyTomlPackageWithoutHealth)
                ?:localReviewedWholeTreeInstaller.install(prerequisite,::progress)
            val record=localNpmInstaller.install(plan.recipe,node,progress=::progress,activity=::activity)
            _state.value=State(Phase.READY,"Exact Taplo library and independent private Node installed; local-snapshot analysis needs project consent.",plan.familyId,plan.recipe.version)
            return "Installed ${record.familyId} ${record.version}; one TOML configuration with its explicit private Node prerequisite."
        }
        require(plan.tomlNodeDependency==null) { "Unexpected Taplo prerequisite on another plan" }
        if (plan.recipe.familyId == LocalUbuntuVuePolicy.FAMILY) {
            require(LocalUbuntuVuePolicy.supports(plan.recipe) && plan.nodeDependency == null)
            val prerequisite = requireNotNull(plan.vueHelperDependency) { LocalUbuntuVuePolicy.PREREQUISITE_MESSAGE }
            LocalUbuntuVueHelperPolicy.requireReviewed(prerequisite)
            val helper = LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId,prerequisite.version),
                prerequisite.familyId,prerequisite.version,prerequisite.assetSha256,localPackageAuthority::owns,localPackageAuthority::verifyVuePackageWithoutHealth)
                ?: localReviewedWholeTreeInstaller.install(prerequisite,::progress)
            val record = localNpmInstaller.install(plan.recipe,helper,progress=::progress,activity=::activity)
            _state.value = State(Phase.READY,"Exact Vue bundle and private Node installed; project consent is required before analysis.",plan.familyId,plan.recipe.version)
            return "Installed ${record.familyId} ${record.version}; one Vue configuration with its independent private Node prerequisite."
        }
        require(plan.vueHelperDependency == null) { "Unexpected Vue prerequisite on another package plan" }
        if (plan.recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) {
            // Svelte may only consume the already selected byte-verified Node generation. Do not enter
            // generic prerequisite install, health, or default-restoration paths.
            require(plan.nodeDependency == null) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
            val node = requireNotNull(selectedSvelteManagedNode()) { LocalUbuntuSveltePolicy.PREREQUISITE_MESSAGE }
            _state.value = State(Phase.INSTALLING, "Installing Svelte with the selected owned Node.js runtime…", plan.familyId, plan.requestedVersion)
            val record = localNpmInstaller.install(plan.recipe, node, progress = ::progress, activity = ::activity)
            _state.value = State(Phase.READY, InstallerExecutionPolicy.successMessage(plan.familyId),
                plan.familyId, plan.recipe.version, progressLog.append("\nCompleted\n"))
            return "Installed ${record.familyId} ${record.version} in local Ubuntu. Commands: ${record.commands.keys.sorted().joinToString()}"
        }
        if (LocalUbuntuNpmPolicy.supports(plan.recipe)) {
            val previousNodeDefault = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
            return withPreservedDefault(previousNodeDefault) {
                _state.value = State(Phase.INSTALLING, "Installing and verifying the original CLI and editor adapter in local Ubuntu…", plan.familyId, plan.requestedVersion)
                // Reuse only the managed executable selected by PATH, retaining its dependency edge.
                val node = plan.nodeDependency?.let { recipe ->
                    LocalInstalledPackageReuse.artifact(
                        localPackageAuthority.ownedReceipt(recipe.familyId, recipe.version),
                        recipe.familyId, recipe.version, recipe.assetSha256,
                        localPackageAuthority::owns, localPackageAuthority::verify,
                    )?.also { progress("Using verified installed Node.js ${it.version}.\n") }
                        ?: localReviewedWholeTreeInstaller.install(recipe, ::progress)
                }
                    ?: selectedLocalManagedNode()
                val museRuntime = plan.museRuntimeDependency?.let { prerequisite ->
                    prerequisite.validate()
                    require(prerequisite.familyId == LocalUbuntuAgentDistributions.MUSE_RUNTIME)
                    LocalInstalledPackageReuse.artifact(localPackageAuthority.ownedReceipt(prerequisite.familyId, prerequisite.version),
                        prerequisite.familyId, prerequisite.version, prerequisite.assetSha256, localPackageAuthority::owns, localPackageAuthority::verify)
                        ?: localReviewedWholeTreeInstaller.install(prerequisite, ::progress)
                }
                val record = localNpmInstaller.install(plan.recipe, node, progress = ::progress, activity = ::activity,
                    museRuntime = museRuntime)
                _state.value = State(Phase.READY, InstallerExecutionPolicy.successMessage(plan.familyId),
                    plan.familyId, plan.recipe.version, progressLog.append("\nCompleted\n"))
                "Installed ${record.familyId} ${record.version} in local Ubuntu. Commands: ${record.commands.keys.sorted().joinToString()}"
            }
        }
        _state.value = State(Phase.INSTALLING, "Provisioning exact Node.js dependency and installing reviewed package recipe…", plan.familyId, plan.requestedVersion)
        val previousNodeDefault = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
        return withPreservedDefault(previousNodeDefault) {
            val nodeDependency = ensureManagedNodeDependency(plan.recipe.minimumNodeMajor)
            val record = recipeInstaller.install(plan.recipe, nodeDependency)
            _state.value = State(Phase.READY, "Installed, dependency-locked, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        }
    }

    private suspend fun installPyPiRecipe(plan: ExtensionInstallPlan.PyPi): String {
        _state.value = State(Phase.INSTALLING, "Provisioning exact Python dependency and installing hash-locked PyPI wheel closure…", plan.familyId, plan.requestedVersion)
        val previousPythonDefault = registry.list().firstOrNull { it.familyId == "runtime.python" && it.active }
        return withPreservedDefault(previousPythonDefault) {
            val pythonDependency = ensureManagedPythonDependency(plan.recipe.minimumPythonMajor, plan.recipe.minimumPythonMinor)
            val record = pypiRecipeInstaller.install(plan.recipe, pythonDependency)
            _state.value = State(Phase.READY, "Installed, dependency-locked, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        }
    }

    private suspend fun installGoRecipe(plan: ExtensionInstallPlan.Go): String {
        _state.value = State(Phase.INSTALLING, "Provisioning exact Go dependency and installing checksum-authenticated Go command…", plan.familyId, plan.requestedVersion)
        val previousGoDefault = registry.list().firstOrNull { it.familyId == "toolchain.go" && it.active }
        return withPreservedDefault(previousGoDefault) {
            val goDependency = ensureManagedGoDependency(plan.recipe.minimumGoMajor, plan.recipe.minimumGoMinor)
            val record = goRecipeInstaller.install(plan.recipe, goDependency)
            _state.value = State(Phase.READY, "Installed, sumdb-authenticated, integrity-checked and activated.", plan.familyId, plan.requestedVersion)
            "Installed ${record.familyId} ${record.version}."
        }
    }

    private suspend fun installRubyGemsRecipe(plan: ExtensionInstallPlan.RubyGems): String {
        _state.value = State(Phase.INSTALLING, "Provisioning Ruby/Bundler and installing checksum-locked RubyGems closure…", plan.familyId, plan.requestedVersion)
        val ruby = ensureGuestCommandDependency("runtime.ruby", "ruby").record
        val bundler = ensureGuestCommandDependency("package.bundler", "bundle").record
        val nativeBuild = ensureGuestCommandDependency("toolchain.ruby-native-build", "gcc").record
        val record = rubyGemsRecipeInstaller.install(plan.recipe, ruby, bundler, nativeBuild)
        _state.value = State(Phase.READY, "Installed, lock-checksummed, offline-installed and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version}."
    }

    private suspend fun installGitHubRelease(plan: ExtensionInstallPlan.GitHubRelease): String {
        _state.value = State(Phase.DOWNLOADING, "Downloading exact GitHub release asset and verifying SHA-256…", plan.familyId, plan.requestedVersion)
        val record = when {
            localReviewedRawInstaller.supports(plan.recipe) -> {
                _state.value = State(Phase.INSTALLING, "Installing verified Linux/ARM64 ELF into local Ubuntu…", plan.familyId, plan.recipe.version)
                localReviewedRawInstaller.install(plan.recipe)
            }
            localReviewedWholeTreeInstaller.supports(plan.recipe) -> {
                _state.value = State(Phase.INSTALLING, "Extracting and atomically activating reviewed Linux/ARM64 tree in local Ubuntu…", plan.familyId, plan.recipe.version)
                localReviewedWholeTreeInstaller.install(plan.recipe, ::progress)
            }
            else -> artifactInstallCoordinator.install(plan.recipe, ::ensureGuestCommandDependency)
        }
        _state.value = State(Phase.READY, "Installed, SHA-256 verified, health-checked and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} from ${plan.recipe.repository} ${plan.recipe.releaseTag}."
    }

    private suspend fun installVendorOfficial(plan: ExtensionInstallPlan.VendorOfficial): String {
        _state.value = State(Phase.DOWNLOADING, "Downloading exact official-vendor artifact and verifying publisher SHA-256…", plan.familyId, plan.requestedVersion)
        val record = if (localReviewedWholeTreeInstaller.supports(plan.recipe)) {
            _state.value = State(Phase.INSTALLING, "Extracting and atomically activating reviewed vendor tree in local Ubuntu…", plan.familyId, plan.recipe.version)
            localReviewedWholeTreeInstaller.install(plan.recipe, ::progress)
        } else {
            artifactInstallCoordinator.install(plan.recipe, ::ensureGuestCommandDependency)
        }
        _state.value = State(Phase.READY, "Installed, publisher-SHA-256 verified, health-checked and activated.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} from reviewed vendor authority."
    }

    private suspend fun installBundleProjection(plan: ExtensionInstallPlan.BundleProjection): String {
        _state.value = State(Phase.INSTALLING, "Ensuring shared bundle primary and creating lightweight capability projection…", plan.familyId, plan.requestedVersion)
        val record = bundleProjectionCoordinator.install(plan, ::ensureGuestCommandDependency)
        _state.value = State(Phase.READY, "Installed as a dependency-safe projection of ${plan.primaryFamilyId}; shared SDK bytes were not duplicated.", plan.familyId, record.version)
        return "Installed ${record.familyId} ${record.version} as a shared ${plan.bundleId} capability projection."
    }

    private suspend fun ensureGuestCommandDependency(familyId: String, command: String): GitHubArtifactInterpreterDependency {
        val candidates = WorkstationGuestPackageCatalog.forFamily(familyId).filter { command in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed interpreter provider is available for $familyId/$command" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val guestExecutable = requireNotNull(recipe.commands[command])
            return GitHubArtifactInterpreterDependency(record, guestExecutable)
        }
        throw IllegalStateException("Could not provision reviewed managed interpreter $familyId/$command", lastFailure)
    }

    private suspend fun ensureManagedGoDependency(minimumMajor: Int, minimumMinor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("toolchain.go")
            .filter { "go" in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed Go provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val goVersion = goRecipeInstaller.probeGoVersion(record)
            if (goVersion != null && (goVersion.first > minimumMajor || goVersion.first == minimumMajor && goVersion.second >= minimumMinor)) return record
            lastFailure = IllegalStateException("Managed Go ${record.version} does not satisfy Go $minimumMajor.$minimumMinor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Go $minimumMajor.$minimumMinor+ dependency", lastFailure)
    }

    private suspend fun ensureManagedPythonDependency(minimumMajor: Int, minimumMinor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("runtime.python")
            .filter { "python3" in it.commands && ("pip3" in it.commands || "pip" in it.commands) }
        require(candidates.isNotEmpty()) { "No reviewed managed Python provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val python = pypiRecipeInstaller.probePythonVersion(record)
            if (python != null && (python.first > minimumMajor || python.first == minimumMajor && python.second >= minimumMinor)) return record
            lastFailure = IllegalStateException("Managed Python ${record.version} does not satisfy Python $minimumMajor.$minimumMinor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Python $minimumMajor.$minimumMinor+ dependency", lastFailure)
    }

    private suspend fun ensureManagedNodeDependency(minimumMajor: Int): ManagedPackageRecord {
        val candidates = WorkstationGuestPackageCatalog.forFamily("runtime.node")
            .filter { "node" in it.commands && "npm" in it.commands }
        require(candidates.isNotEmpty()) { "No reviewed managed Node.js provider is available" }
        var lastFailure: Throwable? = null
        for (recipe in candidates) {
            val existing = registry.find(recipe.familyId, recipe.version)
            val record = try {
                if (existing != null && packageInstaller.verify(existing)) existing
                else guestPackageInstaller.install(recipe)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fatal: Error) {
                throw fatal
            } catch (failure: Exception) {
                lastFailure = failure
                continue
            }
            val nodeMajor = recipeInstaller.probeNodeMajor(record)
            if (nodeMajor != null && nodeMajor >= minimumMajor) return record
            lastFailure = IllegalStateException("Managed Node.js ${record.version} does not satisfy Node.js $minimumMajor+")
        }
        throw IllegalStateException("Could not provision a reviewed managed Node.js $minimumMajor+ dependency", lastFailure)
    }

    private suspend fun ensurePackageBackend(familyId: String, version: String) {
        if (bridge.state.value.connected != null) return
        _state.value = State(Phase.RESOLVING, "Reconnecting paired Device Workstation…", familyId, version)
        bridge.ensurePackageBackend()
    }

    private suspend fun installGuestPackage(plan: ExtensionInstallPlan.GuestPackage): String {
        _state.value = State(Phase.INSTALLING, "Bootstrapping verified Linux environment and installing Alpine packages…", plan.familyId, plan.requestedVersion)
        val record = guestPackageInstaller.install(plan.recipe)
        _state.value = State(Phase.READY, "Installed, integrity-checked and activated through app-local managed/bin.", plan.familyId, plan.requestedVersion)
        return "Installed ${record.familyId} ${record.version} in the app-local rootless Alpine guest."
    }

    suspend fun repair(item: ExtensionVersionState): String =
        if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) LegacyAlpineRuntimeRetirement.reject()
        else operation(item.family.id, item.version.version, Phase.REPAIRING) {
        require(item.state == ExtensionState.INSTALLED) { "Only installed packages can be repaired" }
        _state.value = State(Phase.REPAIRING, "Repairing ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        try {
            val owned = registry.find(item.family.id, item.version.version)?.takeIf(localPackageAuthority::owns)
            when {
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE -> {
                    val recipe = localVendorResolver.resolve(item.family.id, owned.version)
                    require(recipe.assetSha256 == owned.artifactSha256) { "Publisher artifact changed for this installed version" }
                    installLocalVendor(recipe, if (LocalUbuntuJsDebugPolicy.requiresLegacyNode(recipe)) resolveNode(LocalUbuntuJsDebugPolicy.NODE_VERSION) else null,
                        if (recipe.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) localVendorResolver.resolve(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellRuntimePolicy.VERSION) else null,
                        if(recipe.familyId==LocalUbuntuNimPolicy.FAMILY) listOf(localVendorResolver.resolve(LocalUbuntuNimHelperPolicy.FAMILY,LocalUbuntuNimHelperPolicy.VERSION),
                            localVendorResolver.resolve(LocalUbuntuNimToolchainPolicy.FAMILY,LocalUbuntuNimToolchainPolicy.VERSION)) else emptyList())
                    return@operation "Repaired exact publisher artifact ${owned.version}."
                }
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS -> {
                    val recipe = requireNotNull(LocalUbuntuBundleAliasPolicy.find(owned.familyId))
                    val key = owned.metadata[LocalUbuntuBundleAliasPolicy.PRIMARY_KEY] ?: error("SDK dependency is missing")
                    val primary = registry.list().singleOrNull { "${it.familyId}@${it.version}" == key } ?: error("Primary SDK receipt is missing")
                    localBundleAliasInstaller.install(recipe, primary, ::progress)
                    return@operation "Repaired bundled SDK command integration."
                }
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_UBUNTU_APT -> {
                    aptInstaller.install(requireNotNull(UbuntuAptCatalog.find(item.family.id)), ::progress)
                    return@operation "Repaired Ubuntu command integration. Shared distro packages retained."
                }
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE -> {
                    localCMakeInstaller.install(requireNotNull(LocalUbuntuCMakeCatalog.find(owned.familyId, owned.version)), ::progress)
                    return@operation "Repaired the same exact CMake five-wheel integration; shared runtime packages and defaults retained."
                }
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_UBUNTU_RUBY -> {
                    localRubyInstaller.install(requireNotNull(LocalUbuntuRubyCatalog.find(owned.familyId, owned.version)), ::progress)
                    return@operation "Repaired original Ruby LSP with the same pinned gem archives and dependency lock."
                }
                owned?.metadata?.get(LocalManagedPackageMetadata.KIND_KEY) == LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION -> {
                    return@operation installAndroidLocalWorkstation(prepareAndroidLocalWorkstation(item))
                }
                owned?.familyId == "runtime.node" && owned.version.startsWith("22.") -> {
                    val recipe = resolveNode(owned.version)
                    require(recipe.assetSha256 == owned.artifactSha256) { "Node publisher artifact changed" }
                    localReviewedWholeTreeInstaller.install(recipe, ::progress)
                    return@operation "Repaired exact Node.js ${recipe.version}."
                }
                owned != null && UbuntuNpmCatalog.find(item.family.id) != null -> {
                    installRecipe(npmPlan(item, requireNotNull(LocalUbuntuNpmPolicy.fromReceipt(owned))))
                    return@operation "Repaired exact npm package and dependency lock."
                }
            }
            val localReviewedSource = sourceCatalog.installable(item.family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
            if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version) == null &&
                    sourceCatalog.installable(item.family.id)?.source != PackageSourceKind.NPM &&
                    localReviewedSource == null)) {
                if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) guestPackageInstaller.requireBackend(item.family.id)
                else guestPackageInstaller.requireMixedBackend()
            }
            val localAppKind = item.version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)?.let(LocalUbuntuNpmPolicy::supports) == true)
            if (item.version.installKind != ExtensionInstallKind.GUEST_PACKAGE && localReviewedSource == null && !localAppKind) {
                ensurePackageBackend(item.family.id, item.version.version)
            }
            val result = when (item.version.installKind) {
                ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> {
                    ManagedPackageMutationGate.mutex.withLock { androidDevelopment.repairToolchain() }
                }
                ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> {
                    val catalog = AndroidLocalComponentCatalog.load(appContext)
                    val entry = AndroidLocalComponentCatalog.find(catalog, item.family.id, item.version.version)
                        ?: error("Pinned local Android SDK component is missing for repair")
                    val activeBefore = registry.list().firstOrNull { it.familyId == item.family.id && it.active }
                    val record = localAndroidSdkComponentInstaller.install(entry)
                    check(localPackageAuthority.verify(record)) { "Repaired Android SDK component failed local verification" }
                    if (activeBefore != null && activeBefore.version != item.version.version) {
                        registry.find(activeBefore.familyId, activeBefore.version)?.let { previous ->
                            check(localPackageAuthority.owns(previous)) { "Previous Android SDK component changed backend during repair" }
                            localPackageAuthority.activate(previous)
                        }
                    }
                    "Reinstalled and integrity-checked ${item.family.name} ${item.version.version} in local Ubuntu."
                }
                ExtensionInstallKind.REVIEWED_RECIPE -> {
                    val bundleProjection = prepareBundleProjection(item)
                    if (bundleProjection != null) {
                        installBundleProjection(bundleProjection)
                        "Reinstalled and health-checked ${item.family.name} through its shared bundle primary."
                    } else {
                    val staticNpm = WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version)
                    val source = sourceCatalog.installable(item.family.id)
                    when {
                        staticNpm != null || source?.source == PackageSourceKind.NPM -> {
                            val recipe = staticNpm ?: sourceResolver.resolveReviewedNpm(item.family.id, item.version.version)
                            if (LocalUbuntuNpmPolicy.supports(recipe)) {
                                installRecipe(npmPlan(item, recipe))
                            } else {
                                val previousNodeDefault = registry.list().firstOrNull { it.familyId == "runtime.node" && it.active }
                                withPreservedDefault(previousNodeDefault) {
                                    val nodeDependency = ensureManagedNodeDependency(recipe.minimumNodeMajor)
                                    recipeInstaller.install(recipe, nodeDependency)
                                }
                            }
                        }
                        source?.source == PackageSourceKind.PYPI -> {
                            val recipe = sourceResolver.resolveReviewedPyPi(item.family.id, item.version.version)
                            val previousPythonDefault = registry.list().firstOrNull { it.familyId == "runtime.python" && it.active }
                            withPreservedDefault(previousPythonDefault) {
                                val pythonDependency = ensureManagedPythonDependency(recipe.minimumPythonMajor, recipe.minimumPythonMinor)
                                pypiRecipeInstaller.install(recipe, pythonDependency)
                            }
                        }
                        source?.source == PackageSourceKind.GO -> {
                            val recipe = sourceResolver.resolveReviewedGo(item.family.id, item.version.version)
                            val previousGoDefault = registry.list().firstOrNull { it.familyId == "toolchain.go" && it.active }
                            withPreservedDefault(previousGoDefault) {
                                val goDependency = ensureManagedGoDependency(recipe.minimumGoMajor, recipe.minimumGoMinor)
                                goRecipeInstaller.install(recipe, goDependency)
                            }
                        }
                        source?.source == PackageSourceKind.RUBYGEMS -> {
                            val recipe = sourceResolver.resolveReviewedRubyGems(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.rootSha256) {
                                    "Upstream RubyGems bytes changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            val ruby = ensureGuestCommandDependency("runtime.ruby", "ruby").record
                            val bundler = ensureGuestCommandDependency("package.bundler", "bundle").record
                            val nativeBuild = ensureGuestCommandDependency("toolchain.ruby-native-build", "gcc").record
                            rubyGemsRecipeInstaller.install(recipe, ruby, bundler, nativeBuild)
                        }
                        source?.source == PackageSourceKind.GITHUB_RELEASE -> {
                            val recipe = sourceResolver.resolveReviewedGitHubRelease(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.assetSha256) {
                                    "Upstream GitHub release asset changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            when {
                                localReviewedRawInstaller.supports(recipe) -> localReviewedRawInstaller.install(recipe)
                                localReviewedWholeTreeInstaller.supports(recipe) -> localReviewedWholeTreeInstaller.install(recipe)
                                else -> artifactInstallCoordinator.install(recipe, ::ensureGuestCommandDependency)
                            }
                        }
                        source?.source == PackageSourceKind.VENDOR_OFFICIAL -> {
                            val recipe = sourceResolver.resolveReviewedVendorOfficial(item.family.id, item.version.version)
                            registry.find(item.family.id, item.version.version)?.artifactSha256?.let { installedDigest ->
                                require(installedDigest == recipe.assetSha256) {
                                    "Upstream vendor artifact changed for the installed version; Repair is blocked to preserve reproducibility. Uninstall and explicitly reinstall to accept new bytes."
                                }
                            }
                            if (localReviewedWholeTreeInstaller.supports(recipe)) localReviewedWholeTreeInstaller.install(recipe)
                            else artifactInstallCoordinator.install(recipe, ::ensureGuestCommandDependency)
                        }
                        else -> error("No certified repair transaction owns ${item.family.name} ${item.version.version}")
                    }
                    "Reinstalled and health-checked ${item.family.name} ${item.version.version}."
                    }
                }
                ExtensionInstallKind.GUEST_PACKAGE -> {
                    val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
                        ?: error("Reviewed Alpine repair recipe is missing")
                    guestPackageInstaller.repair(recipe, registry.find(item.family.id, item.version.version))
                    "Reinstalled and health-checked ${item.family.name} ${item.version.version} inside the rootless guest."
                }
                ExtensionInstallKind.MANAGED_PACKAGE -> {
                    val catalog = ManagedPackageCatalog.load(appContext)
                    val entry = ManagedPackageCatalog.select(catalog, item.family.id, item.version.version, Build.SUPPORTED_ABIS.toList())
                        ?: error("No verified repair artifact is pinned for ${item.family.name} ${item.version.version}")
                    val activeBefore = registry.list().firstOrNull { it.familyId == item.family.id && it.active }
                    val resolved = resolveDependencies(catalog, entry)
                    for (dependency in resolved) {
                        val existing = registry.find(dependency.familyId, dependency.version)
                        val healthy = existing?.let { packageInstaller.verify(it) } == true
                        if (!healthy || dependency.familyId == item.family.id && dependency.version == item.version.version) {
                            packageInstaller.install(dependency)
                        }
                    }
                    if (activeBefore != null && activeBefore.version != item.version.version) {
                        registry.find(activeBefore.familyId, activeBefore.version)?.let { packageInstaller.activate(it) }
                    }
                    "Reinstalled dependencies and health-checked ${item.family.name} ${item.version.version}."
                }
                else -> error("This extension is not managed by an installable provider")
            }
            _state.value = State(Phase.READY, "Repair completed.", item.family.id, item.version.version)
            result
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Repair canceled. Existing healthy package state was preserved where possible.", item.family.id, item.version.version)
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Exception) {
            _state.value = State(Phase.FAILED, error.message ?: "Repair failed", item.family.id, item.version.version)
            throw error
        }
    }

    suspend fun uninstall(item: ExtensionVersionState): String =
        if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) LegacyAlpineRuntimeRetirement.reject()
        else operation(item.family.id, item.version.version, Phase.REMOVING) {
        require(item.state == ExtensionState.INSTALLED) { "Only installed packages can be uninstalled" }
        _state.value = State(Phase.REMOVING,
            if (item.version.installKind == ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) "Removing entire managed Android JDK/SDK/Build Tools pack…"
            else "Uninstalling ${item.family.name} ${item.version.version}…", item.family.id, item.version.version)
        try {
            val localReviewedSource = sourceCatalog.installable(item.family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
            if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE ||
                (item.version.installKind == ExtensionInstallKind.REVIEWED_RECIPE &&
                    registry.find(item.family.id, item.version.version)?.let(localPackageAuthority::owns) != true &&
                    WorkstationInstallRecipeCatalog.find(item.family.id, item.version.version) == null &&
                    sourceCatalog.installable(item.family.id)?.source != PackageSourceKind.NPM &&
                    localReviewedSource == null)) {
                if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) guestPackageInstaller.requireBackend(item.family.id)
                else guestPackageInstaller.requireMixedBackend()
            }
            val localAppKind = item.version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT ||
                registry.find(item.family.id, item.version.version)?.let(localPackageAuthority::owns) == true
            if (item.version.installKind != ExtensionInstallKind.GUEST_PACKAGE && localReviewedSource == null && !localAppKind) {
                ensurePackageBackend(item.family.id, item.version.version)
            }
            val result = when (item.version.installKind) {
                ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN -> {
                    ManagedPackageMutationGate.mutex.withLock {

                        val result = androidDevelopment.removeToolchain()
                        registry.replaceAll(
                            registry.list().filterNot {
                                it.familyId in setOf("sdk.android", "toolchain.jdk", "build.android-tools", "meta.android-development")
                            }
                        )
                        result
                    }
                }
                ExtensionInstallKind.ANDROID_LOCAL_COMPONENT -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Local Android SDK component receipt is missing")
                    check(localPackageAuthority.owns(record)) { "Local Android SDK component belongs to a different backend" }
                    localPackageAuthority.uninstall(record)
                }
                ExtensionInstallKind.MANAGED_PACKAGE -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    packageInstaller.uninstall(record)
                }
                ExtensionInstallKind.REVIEWED_RECIPE -> {
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    val dependenciesBefore = record.dependencies.toList()
                    val source = sourceCatalog.entries.firstOrNull { it.familyId == item.family.id }
                    val localOwned = localPackageAuthority.owns(record)
                    val result = if (localOwned) {
                        val removed = localPackageAuthority.uninstall(record)
                        if (record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_APT)
                            "Removed command integration. Shared Ubuntu packages remain installed." else removed
                    } else {
                        packageInstaller.uninstall(record)
                    }
                    (source?.githubRuntimeCompatibility ?: source?.vendorRuntimeCompatibility)?.let { runtime ->
                        if (localOwned) {
                            if (record.metadata[LocalManagedPackageMetadata.KIND_KEY] != LocalManagedPackageMetadata.KIND_UBUNTU_APT)
                                cleanupOrphanLocalUbuntuDependencies(runtime, dependenciesBefore)
                        } else reviewedArtifactBackend.cleanupOrphanDependencies(runtime, dependenciesBefore)
                    }
                    result
                }
                ExtensionInstallKind.GUEST_PACKAGE -> {
                    val recipe = WorkstationGuestPackageCatalog.find(item.family.id, item.version.version)
                        ?: error("Reviewed Alpine uninstall recipe is missing")
                    val record = registry.find(item.family.id, item.version.version)
                        ?: error("Managed package record is missing")
                    guestPackageInstaller.uninstall(recipe, record)
                }
                else -> error("This extension is not managed by an installable provider")
            }
            _state.value = State(Phase.READY, "Uninstall completed.", item.family.id, item.version.version)
            result
        } catch (cancelled: CancellationException) {
            _state.value = State(Phase.CANCELED, "Uninstall canceled. Package state was not reported as successfully removed.", item.family.id, item.version.version)
            throw cancelled
        } catch (fatal: Error) {
            throw fatal
        } catch (error: Exception) {
            _state.value = State(Phase.FAILED, error.message ?: "Uninstall failed", item.family.id, item.version.version)
            throw error
        }
    }

    private suspend fun cleanupOrphanLocalUbuntuDependencies(
        contract: DeclarativeRuntimeCompatibility,
        dependencyKeys: List<String>,
    ) {
        if (contract.guestProfile != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64) return
        val expected = localUbuntuEnvironment.layerRecordKey(contract.requiredGuestPackages) ?: return
        if (expected !in dependencyKeys || registry.list().any { expected in it.dependencies }) return
        val familyId = expected.substringBeforeLast('@')
        val version = expected.substringAfterLast('@')
        val layer = registry.find(familyId, version) ?: return
        if (!localPackageAuthority.owns(layer)) return

        // Remove only the durable ownership anchor and its transaction files. Shared distro packages may
        // also belong to terminal users and remain available after integration removal.
        localPackageAuthority.uninstall(layer)
        runSuspendCatching {
            localUbuntuEnvironment.releaseLayer(contract.requiredGuestPackages)
        }
    }

    private fun requireInstalledComponents() {
        check(installedComponentsEnabled) { TEMPORARY_COMPONENTS_UNAVAILABLE }
    }

    fun reset() { check(!operations.active) { "An extension operation is still running" }; _state.value = State() }
}
