package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class DevelopmentExtensionsSnapshot(
    val catalogRevision: String = "source-v1.00",
    val workspace: WorkspaceEnvironmentSnapshot,
    val items: List<ExtensionVersionState>,
    val deviceConnected: Boolean,
    val workstationReady: Boolean,
    val message: String = "",
    val localLinuxExecutables: Map<String, String> = emptyMap(),
)

/** Request identity only; all access uses the installer's existing close/publication monitor. */
internal class DevelopmentExtensionRefreshAdmission(private val admit: (() -> Unit) -> Unit) {
    private var generation = 0L

    fun begin(eligible: () -> Boolean = { true }): Long? {
        var token: Long? = null
        admit { if (eligible()) token = ++generation }
        return token
    }

    suspend fun publish(token: Long, block: () -> Unit): Boolean {
        val context = currentCoroutineContext()
        var published = false
        admit {
            context.ensureActive()
            if (token == generation) {
                block()
                published = true
            }
        }
        return published
    }
}

internal const val TEMPORARY_COMPONENTS_UNAVAILABLE =
    "Installed extensions, language servers and automatic tool setup are temporarily unavailable in built-in startup recovery."

class DevelopmentExtensionsManager internal constructor(
    private val context: Context,
    private val projectRoot: File,
    private val androidDevelopment: AndroidDevelopmentManager,
    private val bridge: DeviceBridgeManager,
    val capabilities: UniversalCapabilityRegistry,
    val registry: ManagedPackageRegistry,
    val installedComponentsEnabled: Boolean,
    private val startCli: (Context, UnifiedPackageAuthority, String) -> Unit,
    private val beforeInstalledRefresh: () -> Unit,
) {
    constructor(
        context: Context,
        projectRoot: File,
        androidDevelopment: AndroidDevelopmentManager,
        bridge: DeviceBridgeManager,
        capabilities: UniversalCapabilityRegistry,
        registry: ManagedPackageRegistry = ManagedPackageRegistry(context),
        installedComponentsEnabled: Boolean = true,
    ) : this(context, projectRoot, androidDevelopment, bridge, capabilities, registry,
        installedComponentsEnabled, DroideCliServer::start, {})

    val temporaryRecoveryReason: String? get() =
        if (installedComponentsEnabled) null else TEMPORARY_COMPONENTS_UNAVAILABLE

    private val sourceCatalog: PackageSourceCatalog = PackageSourceCatalog.load(context.applicationContext)
    val installer: ManagedExtensionInstaller = ManagedExtensionInstaller(context.applicationContext, androidDevelopment, bridge, registry, sourceCatalog, projectRoot, installedComponentsEnabled)
    private val ownedMarketplace = ExtensionMarketplaceManager(context.applicationContext)
    val marketplace: ExtensionMarketplaceManager get() {
        requireInstalledComponents()
        return ownedMarketplace
    }
    private val workspaceToolchains = WorkspaceToolchainPreferences(
        context.applicationContext, projectRoot, installer::publishSnapshot)
    private val refreshAdmission = DevelopmentExtensionRefreshAdmission(installer::publishSnapshot)

    private val ownedAuthority = DefaultUnifiedPackageAuthority(
        registry = registry,
        installer = installer.packageInstaller,
        catalog = sourceCatalog,
        extensionInstaller = installer,
        workspaceToolchains = workspaceToolchains,
        itemResolver = { family, version -> requestedItem(family, version) },
        itemLister = { refresh().items },
    )
    val unifiedAuthority: DefaultUnifiedPackageAuthority get() {
        requireInstalledComponents()
        return ownedAuthority
    }

    private fun requireInstalledComponents() {
        check(installedComponentsEnabled) { TEMPORARY_COMPONENTS_UNAVAILABLE }
    }

    private val lifecycleLock = Any()
    private var closed = false
    private var cliActivated = false

    init {
        val id = workspaceToolchains.workspaceId
        require(id.matches(Regex("[A-Za-z0-9._-]{1,160}")) && id != "." && id != "..") {
            "Invalid CLI workspace identity"
        }
    }

    /** Construction is preparation. Only the accepted runtime may hand over the global CLI. */
    internal fun activateCli() = synchronized(lifecycleLock) {
        check(!closed) { "Extension manager is closed" }
        if (installedComponentsEnabled && !cliActivated) {
            startCli(context.applicationContext, ownedAuthority, workspaceToolchains.workspaceId)
            cliActivated = true
        }
    }

    private val initialWorkspace = WorkspaceEnvironmentDetector.detect(projectRoot)
    private val _snapshot = MutableStateFlow(
        DevelopmentExtensionsSnapshot(
            workspace = initialWorkspace,
            items = initialStates(),
            deviceConnected = false,
            workstationReady = false,
            message = temporaryRecoveryReason ?: "Checking extensions…",
        )
    )
    val snapshot: StateFlow<DevelopmentExtensionsSnapshot> = _snapshot.asStateFlow()

    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A known tool family is not a whitelist of version numbers. */
    internal suspend fun requestedItem(familyId: String, requested: String): ExtensionVersionState? {
        requireInstalledComponents()
        val version = ToolUpdatePlanner.request(requested)
        val items = refresh().items.filter { it.family.id == familyId }
        return ToolVersionRequests.select(items, familyId, version)
    }

    suspend fun prepareRequestedVersion(familyId: String, version: String): ExtensionInstallPlan =
        installer.prepare(requireNotNull(requestedItem(familyId, version)) { "Tool family was not found" })

    // Retained by the same runtime as installScope, never by an Extensions pane or Dialog.
    internal val installProgress = InstallerProgressPresentation()

    /** Called only by the explicit selected-path/fingerprint health approval button. */
    fun installWithElpHealthApproval(plan:ExtensionInstallPlan.LocalVendor,request:ElpRuntimeHealthApprovalRequest):Deferred<UnifiedPackageTransactionResult> {
        require(plan.familyId==LocalUbuntuElpPolicy.FAMILY&&plan.elpHealthPermit==null)
        LocalUbuntuElpPolicy.requireReviewed(plan.recipe)
        return installInBackground(plan.copy(elpHealthPermit=ElpStageHealthPermit.acceptedFromExtensions(request)))
    }

    fun installInBackground(plan: ExtensionInstallPlan): Deferred<UnifiedPackageTransactionResult> =
        installProgress.launch(installScope, InstallerProgressDetails(
            plan.familyId, plan.familyName, plan.resolvedVersion, plan.components,
        )) { report ->
            val fractions = when (plan) {
                is ExtensionInstallPlan.Android -> installer.androidProviderState.map { it.fraction }
                is ExtensionInstallPlan.Package -> installer.packageProviderState.map { it.fraction }
                else -> null
            }
            withInstallerProgressReporting(fractions, report) {
                val result = unifiedAuthority.installPrepared(plan)
                refresh()
                result
            }
        }
    suspend fun closeAndJoinForExit() {
        synchronized(lifecycleLock) { closed = true }
        DroideCliServer.stop(ownedAuthority)
        installer.closeAndJoinForExit()
        installScope.coroutineContext[Job]?.cancelAndJoin()
        DroideCliServer.stopAndAwaitForExit(ownedAuthority)
    }

    fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        OwnedResourceCleanup.run(
            { installer.close() }, { installScope.cancel() }, { DroideCliServer.stop(ownedAuthority) },
        ).getOrThrow()
    }

    val workspaceId: String get() = workspaceToolchains.workspaceId

    fun workspaceVersion(familyId: String): String? {
        requireInstalledComponents()
        return workspaceToolchains.selectedVersion(familyId)
    }

    suspend fun useInWorkspace(familyId: String, version: String) {
        val result = unifiedAuthority.activate(familyId, version, workspaceId)
        check(result.success) { result.message }
    }

    suspend fun clearWorkspaceVersion(familyId: String) {
        installer.transaction("activate", familyId) { workspaceToolchains.clear(familyId) }
    }

    /** Explicit refresh retries a failed shell observation without changing automatic refresh cadence. */
    suspend fun refreshUserEnvironment(activeFile: String? = null): DevelopmentExtensionsSnapshot {
        currentCoroutineContext().ensureActive()
        LocalUserToolchainEnvironment.invalidate()
        return refresh(activeFile)
    }

    suspend fun refresh(activeFile: String? = null): DevelopmentExtensionsSnapshot {
        if (!installedComponentsEnabled) {
            currentCoroutineContext().ensureActive()
            var snapshot: DevelopmentExtensionsSnapshot? = null
            installer.publishSnapshot { snapshot = _snapshot.value }
            return checkNotNull(snapshot)
        }
        beforeInstalledRefresh()
        // Admit before IO dispatch: A -> B -> A contains three distinct requests, never one key. A
        // skipped UI refresh must not invalidate an installer's own nested catalog read.
        val ownsOperation = installer.ownsCurrentOperation()
        val request = refreshAdmission.begin { !installer.state.value.running || ownsOperation }
            ?: return _snapshot.value
        return withContext(Dispatchers.IO) {
            if (installer.state.value.running && !installer.ownsCurrentOperation()) return@withContext _snapshot.value
            installer.withSnapshot {
                val workspace = WorkspaceEnvironmentDetector.detect(projectRoot, activeFile)
                val connected = bridge.state.value.connected != null
                // ADB connectivity is for deploy/debug; it is not a package execution backend.
                val workstation: AndroidDevelopmentManager.WorkstationInfo? = null
                val toolchainCatalog = runCatching { AndroidToolchainCatalog.load(context) }.getOrNull()
                val localAndroidComponentCatalog = runCatching { AndroidLocalComponentCatalog.load(context) }.getOrNull()
                val localAndroidComponents = localAndroidComponentCatalog?.entries.orEmpty()
                val certifiedAndroidEntries = toolchainCatalog?.entries
                    ?.filter { it.abi in Build.SUPPORTED_ABIS }
                    .orEmpty()
                val packageCatalog = runCatching { ManagedPackageCatalog.load(context) }.getOrNull()
                val managedPackageEntries = packageCatalog?.entries
                    ?.filter { it.abi in Build.SUPPORTED_ABIS }
                    .orEmpty()
                val managedPackages = managedPackageEntries.map { it.familyId to it.version }.toSet()

                // Existing filesystem reconciliation retains its own mutation/cleanup contract.

                val localRecovery = runSuspendCatching { installer.reconcileLocalPackages() }
                val localRecoveryProblem = localRecovery.exceptionOrNull()
                    ?.let { "Local package recovery failed: ${it.message ?: it.javaClass.simpleName}" }
                val legacyCleanupWarning = localRecovery.getOrNull()

                val externalExecutableMap = detectExternalLocalLinuxExecutables()
                val localRuntime = LocalExecutionSubstrate.inspectLinuxState()
                val externalCommands = externalExecutableMap.keys
                val records = registry.list()
                val recordViews = DevelopmentExtensionProjection.recordsForUbuntuSnapshot(
                    records, localRecoveryProblem, installer.localPackageAuthority.appRoot,
                    installer.localPackageAuthority::owns, installer.localPackageAuthority::normalizeReceipt,
                )
                val verifiedKeys = recordViews.catalogRecords.map { it.familyId to it.version }.toSet()
                val visibleRecords = recordViews.catalogRecords

                val localIdeRecords = recordViews.localCapabilityRecords
                val localDebugAdapters = PackageProtocolCapabilityEngine.debugAdapters(sourceCatalog.manifests, localIdeRecords)
                // A disconnected bridge is not evidence that a durable remote package disappeared.

                // The current request and open owner admit all three side effects together. Projection is
                // synchronous; no suspend point or IO dispatch holds this monitor.
                refreshAdmission.publish(request) {
                    if (localRecoveryProblem == null) workspaceToolchains.prune(records.map { it.familyId to it.version }.toSet())
                    val workspaceSelections = workspaceToolchains.selections()
                    val items = DevelopmentExtensionCatalog.families.flatMap { baseFamily ->
                        val family = baseFamily.copy(versions = projectedVersions(
                            baseFamily, workstation, certifiedAndroidEntries, localAndroidComponents, managedPackageEntries, visibleRecords,
                        ))
                        family.versions.map { version ->
                            resolveState(
                                family = family,
                                version = version,
                                workstation = workstation,
                                androidProviderAvailable = when (family.id) {
                                    "sdk.android" -> version.version.toIntOrNull()?.let { api -> certifiedAndroidEntries.any { api in it.compileSdks } } == true
                                    "toolchain.jdk" -> version.version.toIntOrNull()?.let { java -> certifiedAndroidEntries.any { it.javaVersion == java } } == true
                                    "build.android-tools" -> certifiedAndroidEntries.any { version.version in it.buildToolsVersions }
                                    "meta.android-development" -> certifiedAndroidEntries.isNotEmpty()
                                    else -> false
                                },
                                managedPackages = managedPackages,
                                externalCommands = externalCommands,
                                records = visibleRecords,
                                workspaceSelections = workspaceSelections,
                                connected = connected,
                                localRecoveryProblem = localRecoveryProblem,
                                verifiedKeys = verifiedKeys,
                            )
                        }
                    }
                    val next = DevelopmentExtensionsSnapshot(
                        workspace = workspace,
                        items = DevelopmentExtensionProjection.visibleStates(items),
                        deviceConnected = connected,
                        workstationReady = workstation != null,
                        localLinuxExecutables = externalExecutableMap,
                        message = when {
                            localRecoveryProblem != null -> localRecoveryProblem
                            localRuntime.ready -> "Local Ubuntu ready · ${externalCommands.size} command(s) detected"
                            localRuntime.ubuntuAvailable -> "Local Ubuntu needs attention; open the Linux terminal for details"
                            !localRuntime.prootAvailable -> "Local Linux engine is unavailable on this device"
                            else -> "Activate local Ubuntu from Terminal → + Linux"
                        }.let { status -> listOfNotNull(status, legacyCleanupWarning).joinToString(" · ") },
                    )
                    capabilities.updateExternalExecutables(externalExecutableMap)
                    capabilities.updateManagedPackages(localIdeRecords)
                    capabilities.updateManagedDebugAdapters(localDebugAdapters)
                    _snapshot.value = next
                }
                _snapshot.value
            }
        }
    }

    private fun projectedVersions(
        family: ExtensionFamily,
        workstation: AndroidDevelopmentManager.WorkstationInfo?,
        certifiedEntries: List<AndroidToolchainCatalogEntry>,
        localAndroidComponents: List<AndroidLocalComponentCatalogEntry>,
        managedPackageEntries: List<ManagedPackageCatalogEntry>,
        installedRecords: List<ManagedPackageRecord>,
    ): List<ExtensionVersion> {
        val dynamic = when (family.id) {
            "sdk.android" -> {
                val installed = workstation?.sdkPlatforms.orEmpty()
                val certified = certifiedEntries.flatMap { it.compileSdks }.toSet()
                val local = localAndroidComponents
                    .filter { it.familyId == family.id && it.kind == AndroidLocalComponentKind.SDK_PLATFORM }
                    .mapNotNull { it.version.toIntOrNull() }
                    .toSet()
                (installed + certified + local).sortedDescending().map { api ->
                    val localEntry = api in local
                    val certifiedPack = api in certified
                    ExtensionVersion(
                        version = api.toString(),
                        channel = when {
                            localEntry -> "google-local-ubuntu"
                            certifiedPack -> "certified"
                            else -> "installed"
                        },
                        installKind = when {
                            localEntry -> ExtensionInstallKind.ANDROID_LOCAL_COMPONENT
                            certifiedPack -> ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN
                            else -> ExtensionInstallKind.CATALOG_ONLY
                        },
                        provides = setOf("android-sdk:$api"),
                        note = when {
                            localEntry -> "Official Google SDK Platform $api archive, byte-pinned and projected into local Ubuntu."
                            certifiedPack -> "A certified Android toolchain artifact declares Android SDK $api capability."
                            else -> "Android SDK $api was discovered from the active Android toolchain profile."
                        },
                    )
                }
            }
            "toolchain.jdk" -> {
                val installedAndroid = workstation?.javaVersion?.let(::setOf).orEmpty()
                val certifiedAndroid = certifiedEntries.map { it.javaVersion }.toSet()
                val androidVersions = (installedAndroid + certifiedAndroid).sortedDescending().map { java ->
                    ExtensionVersion(
                        version = java.toString(),
                        channel = if (java in certifiedAndroid) "certified" else "installed",
                        installKind = if (java in certifiedAndroid) ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN else ExtensionInstallKind.CATALOG_ONLY,
                        provides = setOf("jdk:$java", "java", "javac", "jar"),
                        note = if (java in certifiedAndroid) {
                            "A certified Android toolchain artifact declares JDK $java capability."
                        } else {
                            "JDK $java was discovered from the active Android toolchain profile."
                        },
                    )
                }
                val reviewedSource = sourceCatalog.installable(family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
                val managedInstalled = installedRecords.filter { it.familyId == family.id && installer.localPackageAuthority.owns(it) }.map { it.version }.distinct().sortedDescending().map { version ->
                    ExtensionVersion(
                        version = version,
                        channel = "managed-ubuntu",
                        installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("java", "javac", "jar"),
                        note = "Managed Ubuntu ARM64 JDK installed with workspace-selectable JAVA_HOME.",
                    )
                }
                val latest = if (reviewedSource != null) listOf(
                    ExtensionVersion(
                        version = requireNotNull(reviewedSource.vendorPinnedVersion),
                        channel = "managed-ubuntu",
                        recommended = true,
                        installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("java", "javac", "jar"),
                        note = "Pinned Eclipse Temurin ARM64 JDK for local Ubuntu; publisher SHA-256 verified.",
                    )
                ) else emptyList()
                (latest + managedInstalled + androidVersions).distinctBy { it.version }
            }
            "build.android-tools" -> {
                val installed = workstation?.buildToolsVersions.orEmpty()
                val certified = certifiedEntries.flatMap { it.buildToolsVersions }.toSet()
                val local = localAndroidComponents
                    .filter { it.familyId == family.id && it.kind == AndroidLocalComponentKind.BUILD_TOOLS_ARM64 }
                    .map { it.version }
                    .toSet()
                (installed + certified + local).sortedDescending().map { revision ->
                    val localEntry = revision in local
                    val certifiedPack = revision in certified
                    ExtensionVersion(
                        version = revision,
                        channel = when {
                            localEntry -> "google-plus-aosp-arm64"
                            certifiedPack -> "certified"
                            else -> "installed"
                        },
                        installKind = when {
                            localEntry -> ExtensionInstallKind.ANDROID_LOCAL_COMPONENT
                            certifiedPack -> ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN
                            else -> ExtensionInstallKind.CATALOG_ONLY
                        },
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = setOf("aapt2", "aidl", "apksigner", "zipalign", "split-select"),
                        note = when {
                            localEntry -> "Build Tools $revision uses Google Java/metadata plus pinned AOSP-derived linux-glibc-arm64 native tools for local Ubuntu."
                            certifiedPack -> "Build Tools $revision is available from a certified Android toolchain artifact."
                            else -> "Build Tools $revision is installed in the active Android toolchain profile."
                        },
                    )
                }
            }
            else -> {
                val catalogVersions = managedPackageEntries.filter { it.familyId == family.id }.map { it.version }.toSet()
                val installedVersions = installedRecords.filter { it.familyId == family.id }.map { it.version }.toSet()
                val baseRequires = family.versions.flatMap { it.requires }.toSet()
                val verified = catalogVersions.sortedDescending().map { version ->
                    ExtensionVersion(
                        version = version,
                        channel = if (version in installedVersions) "installed" else "verified",
                        installKind = ExtensionInstallKind.MANAGED_PACKAGE,
                        provides = family.versions.flatMap { it.provides }.toSet(),
                        requires = baseRequires,
                        note = "Available for this device ABI.",
                    )
                }
                val guest = WorkstationGuestPackageCatalog.forFamily(family.id).map { recipe ->
                    ExtensionVersion(
                        version = recipe.version,
                        channel = "rootless-alpine",
                        recommended = true,
                        installKind = ExtensionInstallKind.GUEST_PACKAGE,
                        scope = ExecutionScope.LOCAL_LINUX_ARM64,
                        provides = recipe.commands.keys,
                        requires = baseRequires,
                        note = "Rootless Alpine ARM64 package.",
                    )
                }
                val source = sourceCatalog.installable(family.id)
                val followerSource = sourceCatalog.find(family.id)
                val bundle = sourceCatalog.manifest(family.id)?.bundle?.takeIf { it.primaryFamilyId != family.id }
                val bundlePrimarySource = bundle?.let { sourceCatalog.installable(it.primaryFamilyId) }
                val sourceLatest = when {
                    source != null -> listOf(
                        ExtensionVersion(
                            version = PackageSourceChoicePresentation.version(source),
                            channel = "${source.source.label.lowercase()}-official",
                            recommended = true,
                            installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                            scope = ExecutionScope.LOCAL_LINUX_ARM64,
                            provides = setOf(source.command),
                            requires = baseRequires,
                            note = PackageSourceChoicePresentation.note(source),
                        )
                    )
                    bundlePrimarySource != null && followerSource != null -> listOf(
                        ExtensionVersion(
                            version = PackageSourceChoicePresentation.version(bundlePrimarySource),
                            channel = "bundle-${bundle!!.id}",
                            recommended = true,
                            installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                            scope = ExecutionScope.LOCAL_LINUX_ARM64,
                            provides = setOf(followerSource.command),
                            requires = baseRequires,
                            note = "Shared ${bundle.id} capability via ${bundle.primaryFamilyId}. " + PackageSourceChoicePresentation.note(bundlePrimarySource),
                        )
                    )
                    else -> emptyList()
                }
                val known = (catalogVersions + guest.map { it.version }).toSet()
                val discovered = (installedVersions - known).sortedDescending().map { version ->
                    val projectionOwned = source != null || bundlePrimarySource != null
                    ExtensionVersion(
                        version = version,
                        channel = "installed",
                        installKind = if (projectionOwned) ExtensionInstallKind.REVIEWED_RECIPE else ExtensionInstallKind.CATALOG_ONLY,
                        provides = (source ?: followerSource)?.let { setOf(it.command) } ?: family.versions.flatMap { it.provides }.toSet(),
                        requires = baseRequires,
                        note = when {
                            source != null -> "Installed via ${source.source.label}."
                            bundlePrimarySource != null && bundle != null -> "Shared ${bundle.id} install via ${bundle.primaryFamilyId}."
                            else -> "Installed package detected."
                        },
                    )
                }
                verified + guest + sourceLatest + discovered
            }
        }
        val local = buildList {
            addAll(DevelopmentExtensionProjection.localVersions(family))
            installedRecords.filter { it.familyId == family.id && installer.localPackageAuthority.owns(it) }.forEach { record ->
                add(ExtensionVersion(record.version, "installed-ubuntu", installKind = ExtensionInstallKind.REVIEWED_RECIPE,
                    provides = record.commands.keys, note = "Installed app-local command integration."))
            }
        }
        val choices = dynamic + family.versions + listOfNotNull(DevelopmentExtensionProjection.userInstalledChoice(family))
        return (local + if (local.isEmpty()) choices else choices.filter { it.installKind != ExtensionInstallKind.GUEST_PACKAGE &&
            it.version != PackageSourceCatalog.LATEST_OFFICIAL_VERSION }).distinctBy { it.version }
    }

    private fun initialStates(): List<ExtensionVersionState> = DevelopmentExtensionProjection.visibleStates(DevelopmentExtensionCatalog.families.flatMap { family ->
        (DevelopmentExtensionProjection.localVersions(family) + family.versions).distinctBy { it.version }.map { version ->
            if (version.installKind == ExtensionInstallKind.BUILT_IN) {
                ExtensionVersionState(family, version, ExtensionState.BUILT_IN, if (family.category == ExtensionCategory.LANGUAGES) LanguageRuntimePresentation.syntaxStatus(family.id.removePrefix("language.")) else "Built-in")
            } else {
                ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE, version.note.ifBlank { "Not checked yet" })
            }
        }
    })

    private fun resolveState(
        family: ExtensionFamily,
        version: ExtensionVersion,
        workstation: AndroidDevelopmentManager.WorkstationInfo?,
        androidProviderAvailable: Boolean,
        managedPackages: Set<Pair<String, String>>,
        externalCommands: Set<String>,
        records: List<ManagedPackageRecord>,
        workspaceSelections: Map<String, String>,
        connected: Boolean,
        localRecoveryProblem: String?,
        verifiedKeys: Set<Pair<String, String>>,
    ): ExtensionVersionState {
        if (version.installKind == ExtensionInstallKind.BUILT_IN) {
            return ExtensionVersionState(family, version, ExtensionState.BUILT_IN, if (family.category == ExtensionCategory.LANGUAGES) LanguageRuntimePresentation.syntaxStatus(family.id.removePrefix("language.")) else "Built-in")
        }
        val apt = UbuntuAptCatalog.find(family.id)?.takeIf { version.version == it.version }
        val localNpm = UbuntuNpmCatalog.find(family.id)
        val localRuby = LocalUbuntuRubyCatalog.find(family.id, version.version)
        val localAndroidWorkstation = family.id == LocalAndroidWorkstationPolicy.FAMILY && version.version == LocalAndroidWorkstationPolicy.VERSION
        val localCSharp = LocalUbuntuCSharpPolicy.isFamily(family.id) && version.version == LocalUbuntuCSharpPolicy.version(family.id)
        val localNode = family.id == "runtime.node" && version.version == LocalUbuntuNodeDistribution.VERSION
        val localVendor = LocalUbuntuVendorCatalog.find(family.id)?.takeIf { version.version == it.offeredVersion }
        val localAlias = LocalUbuntuBundleAliasPolicy.find(family.id)?.takeIf { version.version == LocalUbuntuBundleAliasPolicy.VERSION_CHOICE }
        val localReviewedSource = sourceCatalog.installable(family.id)?.takeIf(LocalUbuntuReviewedArtifactPolicy::supports)
        val localNpmRecipe = WorkstationInstallRecipeCatalog.find(family.id, version.version)?.takeIf(LocalUbuntuNpmPolicy::supports)
        records.firstOrNull { it.familyId == family.id && it.version == version.version }?.let { record ->
            val workspaceSelected = workspaceSelections[family.id] == version.version
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.INSTALLED,
                when {
                    record.familyId==LocalUbuntuElpPolicy.FAMILY && installer.localPackageAuthority.owns(record) ->
                        if(record.active) "ELP installed: prior native transport and selected OTP health passed. Current selection and project analysis require fresh admission; project/embedded-service/eqWAlizer acceptance unverified."
                        else "ELP inactive; stored data does not grant executable health or project analysis."
                    LocalUbuntuGraphqlPolicy.isFamily(record.familyId) && installer.localPackageAuthority.owns(record) -> {
                        val missing=record.familyId==LocalUbuntuGraphqlPolicy.FAMILY && records.none{it.familyId==LocalUbuntuGraphqlNodePolicy.FAMILY&&it.version==LocalUbuntuGraphqlNodePolicy.VERSION&&installer.localPackageAuthority.owns(it)}
                        if(missing) "Installed GraphQL component is incomplete: exact independent Node prerequisite missing"
                        else "Installed exact GraphQL component; original health and current bounded local-schema consent are separate gates"
                    }
                    LocalUbuntuTomlPolicy.isFamily(record.familyId) && installer.localPackageAuthority.owns(record) -> {
                        val missing=record.familyId==LocalUbuntuTomlPolicy.FAMILY && records.none{it.familyId==LocalUbuntuTomlNodePolicy.FAMILY&&it.version==LocalUbuntuTomlNodePolicy.VERSION&&installer.localPackageAuthority.owns(it)}
                        if(missing) "Installed Taplo component is incomplete: exact independent Node prerequisite missing"
                        else "Installed exact Taplo component; original byte/health and current local-snapshot consent are separate gates"
                    }
                    LocalUbuntuCSharpPolicy.isFamily(record.familyId) && installer.localPackageAuthority.owns(record) -> {
                        val missing=if(record.familyId==LocalUbuntuCSharpPolicy.FAMILY)LocalUbuntuCSharpPolicy.dependencyKeys.filter{key->records.none{"${it.familyId}@${it.version}"==key&&installer.localPackageAuthority.owns(it)}}else emptyList()
                        if(missing.isEmpty())"Installed exact private C# component · verify or repair; native project readiness needs a selection and Enable"
                        else "Installed C# server is incomplete: missing exact private prerequisites ${missing.joinToString()}"
                    }
                    LocalUbuntuVuePolicy.isFamily(record.familyId) && installer.localPackageAuthority.owns(record) -> {
                        val missing = record.familyId == LocalUbuntuVuePolicy.FAMILY && records.none {
                            it.familyId == LocalUbuntuVueHelperPolicy.FAMILY && it.version == LocalUbuntuVueHelperPolicy.VERSION && installer.localPackageAuthority.owns(it)
                        }
                        if(missing) "Installed Vue bundle is incomplete: exact private Node prerequisite missing"
                        else "Installed exact private Vue component · verify or repair; paired native readiness and project consent are separate"
                    }
                    LocalUbuntuNimPolicy.isFamily(record.familyId) && installer.localPackageAuthority.owns(record) -> {
                        val required=if(record.familyId==LocalUbuntuNimPolicy.FAMILY) setOf(LocalUbuntuNimHelperPolicy.KEY,LocalUbuntuNimToolchainPolicy.KEY) else emptySet()
                        val missing=required.filter { key->records.none { "${it.familyId}@${it.version}"==key && installer.localPackageAuthority.owns(it) } }
                        if(missing.isEmpty()) "Installed exact private Nim component · verify or repair; native readiness is separate"
                        else "Installed Nim server is incomplete: missing private prerequisites ${missing.joinToString()}"
                    }
                    installer.localPackageAuthority.owns(record) -> when {
                        workspaceSelected -> "Installed app-local · workspace default · verify or repair"
                        record.active -> "Installed app-local · global default · verify or repair"
                        else -> "Installed app-local · verify or repair"
                    }
                    !connected && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name -> "Installed Device Workstation · verification pending"
                    (record.familyId to record.version) !in verifiedKeys -> "Installed · verification failed or pending; receipt preserved for repair"
                    workspaceSelected -> "Installed Device Workstation · workspace default"
                    record.active -> "Installed Device Workstation · global default"
                    else -> "Installed Device Workstation"
                },
            )
        }
        DevelopmentExtensionProjection.detectedDeviceComponent(family, version, workstation)?.let { return it }

        val appLocalProvider = version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT ||
            (version.installKind == ExtensionInstallKind.REVIEWED_RECIPE && (apt != null || localRuby != null ||
                localCSharp || localAndroidWorkstation || localNode || localNpm != null || localVendor != null || localAlias != null ||
                localReviewedSource != null || localNpmRecipe != null))
        if (appLocalProvider) {
            if (localRecoveryProblem != null) {
                return ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE, localRecoveryProblem)
            }
            DevelopmentExtensionProjection.platformProblem(Build.SUPPORTED_ABIS.toList(), android.os.Process.is64Bit())?.let { problem ->
                return ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE, problem)
            }
        }

        val executableProvides = version.provides.filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }
        val canUseExecutableDetection = DevelopmentExtensionProjection.canDetectExternal(version) &&
            !localCSharp && apt == null && version.version != UbuntuNpmCatalog.VERSION && localRuby == null && !localAndroidWorkstation && !localNode && localVendor == null && localAlias == null &&
            (family.id != LocalUbuntuJdkPolicy.FAMILY || version.version in setOf("Project-selected", "User-installed"))
        val detected = if (family.id == LocalUbuntuJdkPolicy.FAMILY) {
            setOf("java", "javac", "jar").all { it in externalCommands }
        } else version.detectsExternalCommands(externalCommands)
        if (canUseExecutableDetection && executableProvides.isNotEmpty() && detected) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.EXTERNAL,
                "Detected in local Ubuntu · user-managed",
            )
        }

        if (version.installKind == ExtensionInstallKind.ANDROID_LOCAL_COMPONENT) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.AVAILABLE,
                "Reviewed Android SDK component · verified downloads · local Ubuntu projection",
            )
        }

        if (version.installKind == ExtensionInstallKind.ANDROID_MANAGED_TOOLCHAIN) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                if (androidProviderAvailable) {
                    "Local Ubuntu install transaction is not implemented yet; install this toolchain from the Linux terminal"
                } else {
                    version.note.ifBlank { "No compatible Android toolchain package" }
                },
            )
        }

        if (version.installKind == ExtensionInstallKind.GUEST_PACKAGE) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "This installer targets a different guest; install the tool in local Ubuntu from the Linux terminal",
            )
        }

        if (version.installKind == ExtensionInstallKind.REVIEWED_RECIPE) {
            if (localCSharp || apt != null || localRuby != null || localAndroidWorkstation || localNode || localNpm != null || localVendor != null || localAlias != null) return ExtensionVersionState(family, version, ExtensionState.AVAILABLE,
                "Ready to install in local Ubuntu; dependencies and commands verified before activation")
            if (localNpmRecipe != null) {
                return ExtensionVersionState(family, version, ExtensionState.AVAILABLE,
                    "Original CLI + adapter in Ubuntu · Node.js and npm prerequisites installed on request")
            }
            if (localReviewedSource != null) {
                return ExtensionVersionState(
                    family,
                    version,
                    ExtensionState.AVAILABLE,
                    "Reviewed Linux/ARM64 artifact · app-local Ubuntu/glibc transaction",
                )
            }
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "Local Ubuntu install transaction is not implemented yet for this artifact type; install the tool from the Linux terminal",
            )
        }

        if (version.installKind == ExtensionInstallKind.MANAGED_PACKAGE) {
            return ExtensionVersionState(
                family,
                version,
                ExtensionState.UNAVAILABLE,
                "Managed installer currently targets Device Workstation, not the local Ubuntu IDE backend",
            )
        }

        if (family.id == "framework.flutter") {
            return ExtensionVersionState(family, version, ExtensionState.UNAVAILABLE,
                "Official Flutter Linux releases do not provide an ARM64 SDK for this workstation; install a compatible user-managed SDK or use Flutter on a supported remote workstation")
        }
        val pendingSource = sourceCatalog.find(family.id)?.takeIf { !it.installerReady }
        return ExtensionVersionState(
            family,
            version,
            ExtensionState.UNAVAILABLE,
            pendingSource?.let {
                "${it.source.label} installer is not available yet"
            } ?: version.note.ifBlank { "No compatible managed package" },
        )
    }

    private suspend fun detectExternalLocalLinuxExecutables(): Map<String, String> {
        // A known absent environment has no tools. A broken/blocked launcher is not absence.
        if (!LocalExecutionSubstrate.inspectLinuxState().ubuntuAvailable) return emptyMap()
        val spec = checkedToolchainProbe("Ubuntu environment") { LocalExecutionSubstrate.userToolchainLaunchSpec(projectRoot) }
        val catalogCommands = DevelopmentExtensionCatalog.families
            .asSequence()
            .flatMap { it.versions.asSequence() }
            .flatMap { it.provides.asSequence() }
            .filter { it.matches(Regex("[A-Za-z0-9._+-]{1,64}")) }
            .distinct()
            .toList()
        // Shells belong to the guest environment, not a separate managed package. Resolve the full
        // catalog in one builtin batch; no tool is omitted by list ordering.
        val commands = (catalogCommands + listOf("sh", "bash")).distinct()
        if (commands.isEmpty()) return emptyMap()

        return ExternalToolchainDiscovery.observe(commands) { script ->
            LocalProcessSupervisor.capture(
                spec.shellCommand(script), projectRoot, spec.environment,
                maxOutputBytes = 64 * 1024, timeoutMs = 12_000,
            ).let { ExecResult(it.exitCode, it.output, it.timedOut) }
        }
    }

}
