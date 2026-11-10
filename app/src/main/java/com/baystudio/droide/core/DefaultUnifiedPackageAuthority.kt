package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultUnifiedPackageAuthority(
    private val registry: ManagedPackageRegistry,
    private val installer: ManagedPackageInstaller,
    private val catalog: PackageSourceCatalog,
    private val extensionInstaller: ManagedExtensionInstaller? = null,
    private val itemResolver: (suspend (String, String) -> ExtensionVersionState?)? = null,
    private val itemLister: (suspend () -> List<ExtensionVersionState>)? = null,
    private val workspaceToolchains: WorkspaceToolchainPreferences? = null
) : UnifiedPackageAuthority {

    private val transactionMutex = Mutex()
    private val _transactionState = MutableStateFlow(UnifiedPackageTransactionState(false, null, null, emptyList(), null, false))
    override val transactionState: StateFlow<UnifiedPackageTransactionState> = _transactionState.asStateFlow()

    override suspend fun search(query: String): List<UnifiedPackageSearchResult> {
        val installed = installedRecords()
        val normalizedQuery = query.trim()
        val results = linkedMapOf<String, UnifiedPackageSearchResult>()

        catalog.entries.asSequence()
            .filter { normalizedQuery.isBlank() || it.familyId.contains(normalizedQuery, ignoreCase = true) || it.packageName.contains(normalizedQuery, ignoreCase = true) }
            .forEach { entry ->
                val installedRec = installed.firstOrNull { it.familyId == entry.familyId && it.active }
                results[entry.familyId] = UnifiedPackageSearchResult(
                    familyId = entry.familyId,
                    name = entry.packageName,
                    description = "Source: ${entry.source}",
                    versions = installed.filter { it.familyId == entry.familyId }.map { it.version }.distinct().ifEmpty { listOf("latest") },
                    installedVersion = installedRec?.version,
                )
            }

        itemLister?.invoke().orEmpty()
            .groupBy { it.family.id }
            .values
            .forEach { states ->
                val family = states.first().family
                if (normalizedQuery.isNotBlank() &&
                    !family.id.contains(normalizedQuery, ignoreCase = true) &&
                    !family.name.contains(normalizedQuery, ignoreCase = true) &&
                    !family.description.contains(normalizedQuery, ignoreCase = true) &&
                    family.keywords.none { it.contains(normalizedQuery, ignoreCase = true) }
                ) return@forEach
                val installedRec = installed.firstOrNull { it.familyId == family.id && it.active }
                val versions = states.map { it.version.version }.distinct()
                results[family.id] = UnifiedPackageSearchResult(
                    familyId = family.id,
                    name = family.name,
                    description = family.description,
                    versions = versions,
                    installedVersion = installedRec?.version,
                )
            }
        return results.values.sortedBy { it.familyId }
    }

    override suspend fun info(familyId: String, version: String): UnifiedPackageInfo? {
        val candidates = installedRecords().filter { it.familyId == familyId }
        val record = if (version == "latest") {
            candidates.firstOrNull { it.active } ?: candidates.singleOrNull()
        } else candidates.firstOrNull { it.version == version }
        if (record != null) {
            return UnifiedPackageInfo(
                familyId = record.familyId,
                version = record.version,
                state = if (record.active) "active" else "inactive",
                sizeBytes = 0L,
                dependencies = record.dependencies,
            )
        }

        val states = itemLister?.invoke().orEmpty().filter { it.family.id == familyId }
        val candidate = if (version == "latest") {
            states.firstOrNull { it.version.recommended && it.state == ExtensionState.AVAILABLE }
                ?: states.firstOrNull { it.state == ExtensionState.AVAILABLE }
                ?: states.firstOrNull { it.version.recommended }
                ?: states.firstOrNull()
        } else states.firstOrNull { it.version.version == version }
        candidate ?: return null
        return UnifiedPackageInfo(
            familyId = candidate.family.id,
            version = candidate.version.version,
            state = candidate.state.name.lowercase(),
            sizeBytes = 0L,
            dependencies = candidate.version.requires.sorted(),
        )
    }

    override suspend fun install(familyId: String, version: String): UnifiedPackageTransactionResult =
        mutate("install", familyId) {
            val owner = requireNotNull(extensionInstaller) { "Installer dependencies not wired" }
            val resolve = requireNotNull(itemResolver) { "Package catalog resolver not wired" }
            val item = resolve(familyId, version) ?: error("Item $familyId@$version not found in catalog")
            owner.install(owner.prepare(item))
        }

    suspend fun installPrepared(plan: ExtensionInstallPlan): UnifiedPackageTransactionResult =
        mutate("install", plan.familyId) {
            // Recheck queued ELP permission without discovering/probing a different runtime.
            if(plan.familyId==LocalUbuntuElpPolicy.FAMILY) {
                require(plan is ExtensionInstallPlan.LocalVendor)
                requireNotNull(plan.elpHealthPermit) { "ELP runtime health requires explicit Extensions approval" }.requireCurrent()
            }

            requireNotNull(extensionInstaller) { "Installer dependencies not wired" }.install(plan)
        }

    private suspend fun mutate(
        operation: String,
        familyId: String,
        action: suspend () -> String,
    ): UnifiedPackageTransactionResult = withContext(Dispatchers.IO) {
        val requester = currentCoroutineContext()[PackageProgressObserver]
        requester?.publish(PackageProgressSnapshot("queued", "Request received for $operation $familyId; awaiting installer admission."))
        transactionMutex.withLock {
            val opId = "$operation-$familyId-${UUID.randomUUID().toString().take(8)}"
            _transactionState.value = UnifiedPackageTransactionState(true, opId, null, emptyList(), "SYSTEM", false)
            try {
                requester?.publish(PackageProgressSnapshot("admitted", "Operation $opId; waiting for installer ownership."))
                val observer = PackageProgressObserver { snapshot ->
                    _transactionState.value = _transactionState.value.copy(
                        stage = snapshot.stage, message = snapshot.message,
                        logs = snapshot.log.lines().takeLast(32),
                    )
                    requester?.publish(snapshot)
                }
                val message = withContext(observer) {
                    extensionInstaller?.transaction(operation, familyId, action) ?: action()
                }
                UnifiedPackageTransactionResult(true, message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                UnifiedPackageTransactionResult(false, failure.message ?: "$operation failed")
            } finally {
                _transactionState.value = UnifiedPackageTransactionState(false, null, null)
            }
        }
    }

    override suspend fun upgrade(familyId: String): UnifiedPackageTransactionResult = install(familyId, "latest")

    override suspend fun downgrade(familyId: String, version: String): UnifiedPackageTransactionResult = install(familyId, version)

    override suspend fun activate(familyId: String, version: String, workspaceId: String?): UnifiedPackageTransactionResult =
        mutate("activate", familyId) {
            val record = installedRecord(familyId, version)
            if (workspaceId != null) {
                val selections = requireNotNull(workspaceToolchains) { "Workspace version selection is unavailable" }
                require(workspaceId == selections.workspaceId) { "Workspace ID does not match the active workspace: ${selections.workspaceId}" }
                val local = extensionInstaller?.localPackageAuthority
                val localSelection = local != null && local.owns(record) &&
                    LocalLinuxPackageEnvironment.supportsWorkspaceSelection(record, local.appRoot)
                require(localSelection || installer.owns(record)) {
                    "Workspace overrides for this package backend are not connected yet; global activation remains available"
                }
                check(if (localSelection) requireNotNull(local).verify(record) else installer.verify(record)) {
                    "Package failed verification before workspace selection"
                }
                selections.set(familyId, version)
                "Activated $familyId@$version for workspace $workspaceId."
            } else {
                val local = extensionInstaller?.localPackageAuthority
                if (local?.owns(record) == true) local.activate(record) else installer.activate(record)
                "Activated $familyId@$version globally."
            }
        }

    override suspend fun health(familyId: String): UnifiedPackageHealthStatus {
        val record = installedRecords().find { it.familyId == familyId && it.active }
            ?: return UnifiedPackageHealthStatus(familyId, false, listOf("Not installed or active"))

        val local = extensionInstaller?.localPackageAuthority
        val isHealthy = if (local?.owns(record) == true) local.verify(record) else installer.verify(record)
        return UnifiedPackageHealthStatus(familyId, isHealthy, if(familyId==LocalUbuntuElpPolicy.FAMILY)
            listOf(if(isHealthy) "ELP stored-byte verification passed without executing tools; selected runtime health needs explicit recheck approval. Project acceptance remains unverified." else "ELP stored-byte verification failed; command unavailable")
            else if (isHealthy) emptyList() else listOf("Verification failed"))
    }

    override suspend fun repair(familyId: String): UnifiedPackageTransactionResult = repairInstalled(familyId, null)

    override suspend fun repairVersion(familyId: String, version: String): UnifiedPackageTransactionResult =
        repairInstalled(familyId, version)

    private suspend fun repairInstalled(familyId: String, version: String?): UnifiedPackageTransactionResult =
        mutate("repair", familyId) {
            val owner = requireNotNull(extensionInstaller) { "Installer dependencies not wired" }
            val record = installedRecord(familyId, version)
            val activeBefore = installedRecords().firstOrNull { it.familyId == familyId && it.active }
            val item = requireNotNull(itemResolver).invoke(familyId, record.version)
                ?: error("Installed version is missing from the catalog")
            val result = owner.repair(item)
            if (activeBefore != null && activeBefore.version != record.version) {
                val previous = installedRecords().firstOrNull { it.familyId == familyId && it.version == activeBefore.version }
                    ?: error("Repair completed but the previous default receipt is missing")
                if (!previous.active) {
                    if (owner.localPackageAuthority.owns(previous)) owner.localPackageAuthority.activate(previous)
                    else installer.activate(previous)
                }
            }
            result
        }

    override suspend fun uninstall(familyId: String, version: String): UnifiedPackageTransactionResult =
        mutate("uninstall", familyId) {
            val record = installedRecord(familyId, version.takeUnless { it == "latest" })
            val owner = requireNotNull(extensionInstaller) { "Extension removal coordinator not wired" }
            val item = requireNotNull(itemResolver).invoke(familyId, record.version)
                ?: error("Installed version is missing from the catalog")
            val result = owner.uninstall(item)
            workspaceToolchains?.prune(installedRecords().map { it.familyId to it.version }.toSet())
            result
        }

    private fun installedRecords(): List<ManagedPackageRecord> {
        val records = registry.list()
        val local = extensionInstaller?.localPackageAuthority ?: return records
        return DevelopmentExtensionProjection.recordsForUbuntuSnapshot(
            records, recoveryProblem = null, appRoot = local.appRoot,
            ownsLocal = local::owns, normalize = local::normalizeReceipt,
        ).catalogRecords
    }

    private fun installedRecord(familyId: String, version: String?): ManagedPackageRecord {
        val candidates = installedRecords().filter { it.familyId == familyId }
        return if (version != null) {
            candidates.firstOrNull { it.version == version }
                ?: error("Package $familyId@$version is not installed in this workstation")
        } else {
            candidates.firstOrNull { it.active } ?: candidates.singleOrNull()
                ?: error("Select an installed version for $familyId")
        }
    }

    override suspend fun list(): List<UnifiedPackageInfo> {
        return installedRecords().map { record ->
            UnifiedPackageInfo(
                familyId = record.familyId,
                version = record.version,
                state = if (record.active) "active" else "inactive",
                sizeBytes = 0L,
                dependencies = record.dependencies
            )
        }
    }
}
