package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// App-local package ownership and projection. Never uses an ADB transport.
class LocalManagedPackageAuthority(
    context: Context,
    private val registry: ManagedPackageRegistry,
    private val projectionObserver: ((File, String) -> Unit)? = null,
) {
    val backendId = PackageBackendId.LOCAL_APP
    private val pathIdentity = LocalPackagePathIdentity(context.applicationContext.filesDir)
    private val root = pathIdentity.root
    private val androidComponents by lazy { AndroidLocalComponentCatalog.load(context.applicationContext) }
    val appRoot: String get() = root
    private val managed = File(root, "managed")
    private val bin = File(managed, "bin")
    private val ubuntuBin = File(managed, "ubuntu-bin")
    private val trashRoot = File(root, "packages/.trash/local-authority")

    private fun removalJournal(record: ManagedPackageRecord) = File(
        trashRoot,
        LocalPackageRemovalJournal.entryName(record),
    )

    private fun legacyRemovalJournal(record: ManagedPackageRecord) = File(
        trashRoot,
        "${record.familyId}-${record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")}",
    )

    private fun legacyPilotJournal(record: ManagedPackageRecord) = File(
        root,
        "packages/.trash/${record.familyId}-${record.version.replace(Regex("[^A-Za-z0-9._+-]"), "_")}",
    )

    fun requirePilot(familyId: String) {
        LegacyAlpineRuntimeRetirement.reject()
    }

    fun normalizeReceipt(record: ManagedPackageRecord): ManagedPackageRecord = pathIdentity.normalizeLayerReceipt(record)

    fun owns(record: ManagedPackageRecord): Boolean = normalizeReceipt(record).let {
        PackageBackendContract.recordOwner(it.installRoot, it.metadata, root) == backendId &&
            pathIdentity.canonicalPackagePath(it.installRoot) == it.installRoot
    }

    fun hasReceipt(familyId: String, version: String): Boolean =
        registry.find(familyId, version)?.let(::owns) == true

    fun ownedReceipt(familyId: String, version: String): ManagedPackageRecord? =
        registry.find(familyId, version)?.let(::normalizeReceipt)?.takeIf(::owns)

    // Installers hold the transaction mutex. Rejected durable claims are custody conflicts, never
    // evidence that the requested transaction has no receipt.
    internal fun recoveryReceipt(familyId: String, version: String, kind: String): ManagedPackageRecord? {
        val destination = LocalPackageInstallJournal.paths(root, familyId, version, kind).final.absolutePath
        val durable = registry.list()
        val matching = durable.filter { it.familyId == familyId && it.version == version }
        check(matching.size <= 1) { "Duplicate local package recovery receipts were preserved for repair" }
        check(durable.none { record ->
            (record.familyId != familyId || record.version != version) &&
                (record.installRoot == destination || pathIdentity.canonicalPackagePath(record.installRoot) == destination)
        }) { "Local package transaction destination has a conflicting durable receipt; all trees were preserved for repair" }
        val record = matching.singleOrNull()?.let(::normalizeReceipt) ?: return null
        requireOwner(record)
            requireGraphqlMutationAvailable(record)
        check(record.metadata[LocalManagedPackageMetadata.KIND_KEY] == kind && record.installRoot == destination) {
            "Local package recovery receipt does not match its requested transaction kind or destination"
        }
        return record
    }

    // Legacy Alpine jq receipts predate typed Ubuntu receipts and carry neither kind nor profile
    // metadata. A typed/foreign receipt must never fall back by family.
    private fun isLegacyPilot(record: ManagedPackageRecord): Boolean =
        record.familyId == PILOT_FAMILY &&
            LocalManagedPackageMetadata.KIND_KEY !in record.metadata &&
            LocalManagedPackageMetadata.PROFILE_KEY !in record.metadata &&
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND !in record.metadata

    private fun legacyPilotRoot(): String =
        "$root/packages/$PILOT_FAMILY/${WorkstationGuestEnvironmentSpec.VERSION.replace(Regex("[^A-Za-z0-9._+-]"), "_")}/arm64-v8a"

    // Recognition is for custody only. Unproven old-looking identities never gain execution or
    // cleanup authority, even when an unsupported record happens to be inactive.
    private fun namesLegacyPilotRoot(installRoot: String): Boolean =
        installRoot == legacyPilotRoot() || pathIdentity.canonicalPackagePath(installRoot) == legacyPilotRoot()

    private fun isLegacyReceiptCandidate(record: ManagedPackageRecord): Boolean =
        namesLegacyPilotRoot(record.installRoot) || record.familyId == PILOT_FAMILY &&
            (isLegacyPilot(record) || record.version == WorkstationGuestEnvironmentSpec.VERSION)

    private fun retainedLegacyReceipts(records: List<ManagedPackageRecord>): List<ManagedPackageRecord> {
        check(records.count { it.familyId == PILOT_FAMILY && it.version == WorkstationGuestEnvironmentSpec.VERSION } <= 1) {
            "Duplicate historical local package receipts were preserved for repair"
        }
        return records.filter(::isLegacyReceiptCandidate).onEach { record ->
            val recipe = requireNotNull(WorkstationGuestPackageCatalog.find(PILOT_FAMILY, WorkstationGuestEnvironmentSpec.VERSION))
            val destination = legacyPilotRoot()
            val historicalMetadata = recipe.admissionContractSha256()?.let {
                mapOf(WORKSTATION_GUEST_ADMISSION_CONTRACT_KEY to it)
            }.orEmpty()
            check(isLegacyPilot(record) && record.version == recipe.version && record.installRoot == destination &&
                record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
                record.commands == mapOf("jq" to "$destination/payload/bin/jq") &&
                record.pathEntries == listOf("$destination/payload/bin") && record.environment.isEmpty() &&
                record.dependencies.isEmpty() && record.healthChecks == recipe.requiredManagedHealthChecks() &&
                record.checksumFile == "SHA256SUMS" && record.artifactSha256 == null &&
                record.metadata - PackageBackendContract.METADATA_KEY == historicalMetadata) {
                "Unproven historical local package identity was preserved for repair"
            }
            PackageBackendContract.requireOwner(backendId, record.installRoot, record.metadata, root)
            check(pathIdentity.canonicalPackagePath(record.installRoot) == record.installRoot) {
                "Historical local package path conflict was preserved for repair"
            }
            requireRetainedDirectory(File(record.installRoot))
        }
    }

    // Read only, from the filesystem root downward. Missing ancestors are permitted; an existing
    // symlink or non-directory is a conflict, never a benign absence.
    private fun requireRetainedDirectory(directory: File): Boolean {
        LocalExecutionSubstrate.requireSafeLocalPath(directory.absolutePath)
        val path = directory.toPath().toAbsolutePath()
        var cursor = requireNotNull(path.root)
        for (part in path) {
            cursor = cursor.resolve(part)
            val attributes = try {
                Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) { return false }
            check(attributes.isDirectory && !attributes.isSymbolicLink) {
                "Unsafe historical local package path was preserved for repair"
            }
        }
        return true
    }

    private fun requireLegacyIntent(intent: LocalPackageRemovalJournal.Intent, trash: File,
        owner: ManagedPackageRecord?) {
        check(intent.familyId == PILOT_FAMILY && intent.version == WorkstationGuestEnvironmentSpec.VERSION &&
            intent.installRoot == legacyPilotRoot()) { "Unproven historical removal intent was preserved for repair" }
        if (owner != null) check(LocalPackageRemovalJournal.belongsTo(intent, owner) &&
            intent.activationId == owner.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) {
            "Conflicting historical removal ownership or generation was preserved for repair"
        }
        requireRetainedDirectory(File(intent.installRoot))
        if (requireRetainedDirectory(trash)) check(LocalPackageRemovalJournal.matchesIntent(intent, trash)) {
            "Conflicting historical removal generation was preserved for repair"
        }
    }

    private fun retainedLegacyRecovery(records: List<ManagedPackageRecord>): Boolean {
        val identity = ManagedPackageRecord(PILOT_FAMILY, WorkstationGuestEnvironmentSpec.VERSION,
            ExecutionScope.LOCAL_LINUX_ARM64.name, legacyPilotRoot(), 0)
        val owner = records.singleOrNull()
        var pending = owner != null
        val current = removalJournal(identity)
        for (trash in listOf(current, legacyRemovalJournal(identity), legacyPilotJournal(identity))) {
            val present = requireRetainedDirectory(trash)
            val intent = if (trash == current) LocalPackageRemovalJournal.read(trash) else null
            if (intent != null) requireLegacyIntent(intent, trash, owner)
            if (present || intent != null) pending = true
        }
        return pending
    }

    private fun normalizeCurrentReceipts(records: List<ManagedPackageRecord>): List<ManagedPackageRecord> =
        records.map { if (isLegacyReceiptCandidate(it)) it else normalizeReceipt(it) }

    private fun isUbuntuAptReceipt(record: ManagedPackageRecord): Boolean =
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_APT &&
            FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND !in record.metadata &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name

    private fun supportedKind(record: ManagedPackageRecord): Boolean = when {
        record.familyId == LocalUbuntuElpPolicy.FAMILY -> record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalUbuntuElpPolicy.KIND
        LocalUbuntuGraphqlPolicy.isFamily(record.familyId) -> record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalUbuntuGraphqlPolicy.kind(record.familyId)
        LocalUbuntuTomlPolicy.isFamily(record.familyId) -> record.metadata[LocalManagedPackageMetadata.KIND_KEY]==LocalUbuntuTomlPolicy.kind(record.familyId)
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalUbuntuCSharpPolicy.KIND -> LocalUbuntuCSharpPolicy.isFamily(record.familyId)
        record.familyId == PILOT_FAMILY -> isLegacyPilot(record) || isUbuntuAptReceipt(record)
        record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] in setOf(LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE, LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS) -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_APT -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_RUBY -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION -> true
        record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> true
        else -> false
    }

    private fun transactionPaths(record: ManagedPackageRecord): LocalPackageInstallJournal.Paths? {
        val kind = record.metadata[LocalManagedPackageMetadata.KIND_KEY] ?: return null
        return when (kind) {
            LocalUbuntuElpPolicy.KIND,
            LocalUbuntuCSharpPolicy.KIND,
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
            LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
            LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
            LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS,
            LocalManagedPackageMetadata.KIND_UBUNTU_APT,
            LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
            LocalManagedPackageMetadata.KIND_UBUNTU_RUBY,
            LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE,
            LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION,
            LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT ->
                LocalPackageInstallJournal.paths(root, record.familyId, record.version, kind)
            else -> null
        }
    }

    private fun requireOwner(candidate: ManagedPackageRecord) {
        val record = normalizeReceipt(candidate)
        check(supportedKind(record)) { "Local package type is not certified for app-local ownership" }
        PackageBackendContract.requireOwner(backendId, record.installRoot, record.metadata, root)
        check(record.installRoot.startsWith("$root/packages/") &&
            record.installRoot.removePrefix("$root/packages/").split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Local package receipt escaped app-owned package storage"
        }
        check(pathIdentity.canonicalPackagePath(record.installRoot) == record.installRoot) {
            "Local package receipt traverses a package-storage symlink"
        }
        LocalExecutionSubstrate.requireSafeLocalPath(record.installRoot)
        transactionPaths(record)?.let { transaction ->
            if(LocalUbuntuTomlPolicy.isFamily(record.familyId)) LocalPackageInstallJournal.requireNoTomlHealthHold(transaction)
            if(LocalUbuntuCSharpPolicy.isFamily(record.familyId)) LocalPackageInstallJournal.requireNoCSharpHealthHold(transaction)
            if(LocalUbuntuVuePolicy.isFamily(record.familyId)) LocalPackageInstallJournal.requireNoVueHealthHold(transaction)
            check(transaction.final.absolutePath == File(record.installRoot).absolutePath) {
                "Local package receipt does not match its transaction destination"
            }
        }
    }

    /** Call only for a mutation target, never for an unchanged row in a shared projection. */
    private fun requireGraphqlMutationAvailable(record:ManagedPackageRecord,elpInstaller:ElpInstallerClaim?=null) {
        if(record.familyId==LocalUbuntuElpPolicy.FAMILY) {
            LocalUbuntuElpLaunchAuthority.requireNoActivity(root,elpInstaller)
            ElpOwnerCustody.requireNoCustody(root)
            transactionPaths(record)?.let(LocalPackageInstallJournal::requireNoElpHealthHold)
        }
        if(!LocalUbuntuGraphqlPolicy.isFamily(record.familyId))return
        GraphqlRootOwners.requireNoCustody(root,record.familyId)
        transactionPaths(record)?.let(LocalPackageInstallJournal::requireNoGraphqlHealthHold)
    }

    // Files must already be staged before this durable receipt/projection commit.
    suspend fun adopt(candidate: ManagedPackageRecord) = adoptWithDeadline(candidate,null)

    /** Exact installer owns its package claim across promotion and this publication. */
    internal suspend fun adoptElp(candidate:ManagedPackageRecord,installer:ElpInstallerClaim) {
        require(candidate.familyId==LocalUbuntuElpPolicy.FAMILY)
        adoptWithDeadline(candidate,null,installer)
    }

    /** The GraphQL installer already owns transactionMutex. Do not reacquire it here. */
    internal suspend fun adoptGraphql(candidate:ManagedPackageRecord,deadline:GraphqlDeadline) {
        require(LocalUbuntuGraphqlPolicy.isFamily(candidate.familyId));deadline.check()
        kotlinx.coroutines.withTimeout(deadline.remainingMillis().coerceAtLeast(1)){adoptWithDeadline(candidate,deadline)}
    }
    private suspend fun adoptWithDeadline(candidate:ManagedPackageRecord,graphqlDeadline:GraphqlDeadline?,elpInstaller:ElpInstallerClaim?=null) = ManagedPackageMutationGate.mutex.withLock {
        graphqlDeadline?.check()
        if (isLegacyReceiptCandidate(candidate)) LegacyAlpineRuntimeRetirement.reject()
        val record = normalizeReceipt(candidate)
        requireOwner(record)
            requireGraphqlMutationAvailable(record,elpInstaller)
        val persisted = registry.list()
        retainedLegacyReceipts(persisted)
        val before = normalizeCurrentReceipts(persisted)
        check(before.filter { it.familyId == record.familyId }.all(::owns)) {
            "Package family already belongs to another backend"
        }
        for (dependencyKey in record.dependencies) {
            val dependency = dependencyRecord(before, dependencyKey)
                ?: error("Local dependency $dependencyKey has no receipt")
            check(owns(dependency) && supportedKind(dependency)) { "Local package cannot acquire a cross-backend dependency" }
            val dependencyValid = if (record.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) verifySvelteNode(dependency) else verifyPayload(dependency,graphqlDeadline)
            graphqlDeadline?.check()
            check(dependencyValid) { "Local dependency $dependencyKey failed integrity or health verification" }
        }
        check(verifyPayload(record,graphqlDeadline)) { "Local package failed integrity or health verification" }
        val next = before.filterNot { it.familyId == record.familyId && it.version == record.version }
            .map { if (it.familyId == record.familyId && !isLegacyReceiptCandidate(it)) it.copy(active = false) else it } +
            record.copy(active = true, metadata = record.metadata + (PackageBackendContract.METADATA_KEY to backendId.name))
        graphqlDeadline?.check()
        PackageProjectionCommit.commit(persisted, next, ::project) { records ->
            if(elpInstaller==null) registry.replaceAll(records) else registry.replaceAllElp(records,elpInstaller)
        }
    }

    suspend fun verify(record: ManagedPackageRecord): Boolean = ManagedPackageMutationGate.mutex.withLock {
        if (isLegacyReceiptCandidate(record) || !owns(record) || !supportedKind(record)) return@withLock false
        verifyPayload(record)
    }

    suspend fun activate(candidate: ManagedPackageRecord) = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            if (isLegacyReceiptCandidate(candidate)) LegacyAlpineRuntimeRetirement.reject()
            val record = normalizeReceipt(candidate)
            requireOwner(record)
            requireGraphqlMutationAvailable(record)
            val persisted = registry.list()
            retainedLegacyReceipts(persisted)
            val before = normalizeCurrentReceipts(persisted)
            check(before.filter { it.familyId == record.familyId }.all(::owns)) { "Package family belongs to another backend" }
            val current = before.firstOrNull { it.familyId == record.familyId && it.version == record.version }
                ?: error("Local package receipt is missing")
            if (isLegacyReceiptCandidate(current)) LegacyAlpineRuntimeRetirement.reject()
            check(verifyPayload(current)) { "Local package failed verification before activation" }
            val next = before.map {
                if (it.familyId == record.familyId && !isLegacyReceiptCandidate(it)) it.copy(active = it.version == record.version) else it
            }
            PackageProjectionCommit.commit(persisted, next, ::project, registry::replaceAll)
        }
    }

    /** PSES prerequisite ownership must not invent an interactive PowerShell default. */
    internal suspend fun restorePowerShellDefault(previous: ManagedPackageRecord?) = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            require(previous == null || previous.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY)
            val persisted = registry.list(); val before = normalizeCurrentReceipts(persisted)
            if (previous != null) check(before.any { it.familyId == previous.familyId && it.version == previous.version }) {
                "Previous PowerShell default receipt is missing"
            }
            val next = before.map { if (it.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) it.copy(active = it.version == previous?.version) else it }
            PackageProjectionCommit.commit(persisted, next, ::project, registry::replaceAll)
        }
    }

    suspend fun uninstall(selected: ManagedPackageRecord): String = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            val persisted = registry.list()
            val selectedDurable = persisted.singleOrNull { it.familyId == selected.familyId && it.version == selected.version }
                ?: error("Managed package receipt is missing or duplicated")
            if (isLegacyReceiptCandidate(selectedDurable)) LegacyAlpineRuntimeRetirement.reject()
            val retained = retainedLegacyReceipts(persisted)
            val before = normalizeCurrentReceipts(persisted)
            // Keep dependency protection over the complete durable registry. Only fallback selection
            // excludes validated retired receipts; their values remain unchanged.
            val key = "${selected.familyId}@${selected.version}"
            val dependents = before.filter { (it.familyId != selected.familyId || it.version != selected.version) && key in it.dependencies }
            check(dependents.isEmpty()) { "Package is required by: " + dependents.joinToString { "${it.familyId}@${it.version}" } }
            val removal = ManagedPackageRemovalPlan.resolve(before.filterNot(::isLegacyReceiptCandidate), selected.familyId, selected.version)
            val record = removal.current
            requireOwner(record)
            requireGraphqlMutationAvailable(record)
            check(before.filter { it.familyId == record.familyId }.all(::owns)) { "Package family belongs to another backend" }
            ManagedPackageRemovalPlan.requireHealthyFallback(removal, ::verifyPayload)
            val source = File(record.installRoot)
            LocalPackageInstallJournal.requireSafeParents(source)
            val trash = removalJournal(record)
            LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
            check(!trash.exists()) { "Interrupted local package removal needs recovery before retry" }
            check(trash.parentFile?.mkdirs() == true || trash.parentFile?.isDirectory == true) { "Cannot prepare local removal journal" }
            LocalPackageRemovalJournal.prepare(trash, record)
            check(source.isDirectory && !PathSecurity.isSymbolicLink(source) && source.renameTo(trash)) {
                "Could not stage local package removal"
            }
            val remaining = removal.remaining.iterator()
            val next = if (retained.isEmpty()) removal.remaining else before.mapNotNull {
                when {
                    isLegacyReceiptCandidate(it) -> it
                    it.familyId == record.familyId && it.version == record.version -> null
                    else -> remaining.next()
                }
            }
            try {
                PackageProjectionCommit.commit(persisted, next, ::project, registry::replaceAll)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try { LocalPackageRemovalJournal.restore(source, trash, File(trashRoot, "conflicts")) }
                    catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                    try { project(persisted) } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
            if (runCatching { LocalPackageRemovalJournal.discard(trash) }.getOrDefault(false)) "Uninstalled ${record.familyId} ${record.version}."
            else "Uninstalled ${record.familyId} ${record.version}; staged files will be reclaimed during package recovery."
        }
    }

    suspend fun reconcile(): Boolean = LocalPackageInstallJournal.transactionMutex.withLock {
        ManagedPackageMutationGate.mutex.withLock {
            val before = registry.list()
            val retained = retainedLegacyReceipts(before)
            val normalized = normalizeCurrentReceipts(before)
            var legacyPending = retainedLegacyRecovery(retained)
            val records = normalized.filter { !isLegacyReceiptCandidate(it) && owns(it) && supportedKind(it) }
            for (record in records) {
                requireOwner(record)
            requireGraphqlMutationAvailable(record)
                val currentJournal = removalJournal(record)
                val oldJournal = legacyRemovalJournal(record)
                val legacyJournal = if (record.familyId == PILOT_FAMILY) legacyPilotJournal(record) else null
                for (trash in listOfNotNull(currentJournal, oldJournal, legacyJournal).distinct()) {
                    LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
                    val intent = if (trash == currentJournal) LocalPackageRemovalJournal.read(trash)?.let(::normalizeRemovalIntent) else null
                    if (intent != null) check(LocalPackageRemovalJournal.belongsTo(intent, record)) {
                        "Conflicting local removal intent was preserved for repair"
                    }
                    if (!trash.exists() && !PathSecurity.isSymbolicLink(trash)) {
                        if (intent != null && File(record.installRoot).isDirectory) {
                            check(PathSecurity.deleteTreeNoFollow(LocalPackageRemovalJournal.metadata(trash))) { "Cannot clean unused local removal intent" }
                        }
                        continue
                    }
                    check(trash.isDirectory && !PathSecurity.isSymbolicLink(trash)) { "Unsafe local removal journal was preserved" }
                    check(intent == null || LocalPackageRemovalJournal.matchesIntent(intent, trash)) {
                        "Conflicting local removal generation was preserved for repair"
                    }
                    val source = File(record.installRoot)
                    val backupMatches = LocalPackageRemovalJournal.matchesTree(record, trash) ||
                        record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] == null &&
                        (intent != null || record.familyId == PILOT_FAMILY)
                    if (!source.exists() && !PathSecurity.isSymbolicLink(source)) {
                        check(backupMatches) { "Ambiguous legacy local removal backup was preserved for repair" }
                        LocalPackageRemovalJournal.restore(source, trash, File(trashRoot, "conflicts"))
                    } else if (verifyPayload(record)) {
                        // A durable, healthy current generation supersedes this verified removal intent.
                        if (backupMatches || intent != null) check(LocalPackageRemovalJournal.discard(trash)) { "Cannot clean committed local removal journal" }
                    } else if (backupMatches) {
                        LocalPackageRemovalJournal.restore(source, trash, File(trashRoot, "conflicts"))
                    }
                }
                transactionPaths(record)?.let { transaction ->
            if(LocalUbuntuGraphqlPolicy.isFamily(record.familyId)){LocalPackageInstallJournal.requireNoGraphqlHealthHold(transaction);GraphqlRootOwners.requireNoCustody(root,record.familyId)}
            if(LocalUbuntuTomlPolicy.isFamily(record.familyId)) LocalPackageInstallJournal.requireNoTomlHealthHold(transaction)
            if(LocalUbuntuCSharpPolicy.isFamily(record.familyId)) LocalPackageInstallJournal.requireNoCSharpHealthHold(transaction)
                    LocalPackageInstallJournal.recover(
                        transaction,
                        LocalPackageInstallJournal.Receipt(record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]),
                    )
                }
            }
            if (trashRoot.isDirectory && !PathSecurity.isSymbolicLink(trashRoot)) {
                trashRoot.listFiles().orEmpty().filter { it.name.endsWith(".receipt") }.forEach { metadata ->
                    val trash = File(trashRoot, metadata.name.removeSuffix(".receipt"))
                    LocalExecutionSubstrate.requireSafeLocalPath(trash.absolutePath)
                    val originalIntent = LocalPackageRemovalJournal.read(trash) ?: return@forEach
                    if (namesLegacyPilotRoot(originalIntent.installRoot) ||
                        originalIntent.familyId == PILOT_FAMILY && originalIntent.version != UbuntuAptCatalog.VERSION) {
                        requireLegacyIntent(originalIntent, trash, retained.singleOrNull())
                        legacyPending = true
                        return@forEach
                    }
                    // Resolve the original durable identity before normalizing addresses. A rejected sealed alias
                    // still retains custody of its removal evidence.
                    val durableOwners = before.filter {
                        it.familyId == originalIntent.familyId && it.version == originalIntent.version
                    }
                    check(durableOwners.size <= 1) { "Duplicate local removal owners were preserved for repair" }
                    val owner = durableOwners.singleOrNull()?.let(::normalizeReceipt)
                    if (owner != null) { requireOwner(owner); requireGraphqlMutationAvailable(owner) }
                    val intent = normalizeRemovalIntent(originalIntent)
                    if (owner != null) {
                        check(LocalPackageRemovalJournal.belongsTo(intent, owner)) {
                            "Conflicting local removal ownership was preserved for repair"
                        }
                    } else {
                        check(intent.installRoot.startsWith("$root/packages/") &&
                            intent.installRoot.removePrefix("$root/packages/").split('/').none { it.isBlank() || it == "." || it == ".." }) {
                            "Unsafe local removal intent was preserved"
                        }
                        LocalExecutionSubstrate.requireSafeLocalPath(intent.installRoot)
                        check(LocalPackageRemovalJournal.discard(trash)) { "Could not clean committed local removal journal" }
                    }
                }
            }

            if (retained.isNotEmpty() || before == normalized) project(normalized)
            else PackageProjectionCommit.commit(before, normalized, ::project, registry::replaceAll)
            legacyPending
        }
    }

    private fun normalizeRemovalIntent(intent: LocalPackageRemovalJournal.Intent): LocalPackageRemovalJournal.Intent =
        intent.copy(installRoot = pathIdentity.canonicalPackagePath(intent.installRoot) ?: intent.installRoot)

    private suspend fun verifyPayload(candidate:ManagedPackageRecord):Boolean = verifyPayload(candidate,null)
    private suspend fun verifyPayload(candidate: ManagedPackageRecord,graphqlDeadline:GraphqlDeadline?): Boolean = withContext(Dispatchers.IO) {
        if (isLegacyReceiptCandidate(candidate)) return@withContext false
        val record = normalizeReceipt(candidate)
        if (!owns(record) || !supportedKind(record)) return@withContext false
        if (runCatching { requireOwner(record) }.isFailure) return@withContext false
        record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]?.let { id ->
            if (runCatching { LocalPackageInstallJournal.matchesActivation(File(record.installRoot), id) }.getOrDefault(false).not()) return@withContext false
        }
        when {
            record.familyId==LocalUbuntuElpPolicy.FAMILY -> verifyElpPackageWithoutHealth(record,ElpDeadline.start())
            LocalUbuntuGraphqlPolicy.isFamily(record.familyId) -> verifyGraphqlPackageWithoutHealth(record,graphqlDeadline ?: GraphqlDeadline.start())
            LocalUbuntuTomlPolicy.isFamily(record.familyId) -> verifyTomlPackageWithoutHealth(record)
            LocalUbuntuCSharpPolicy.isFamily(record.familyId) -> verifyCSharpPackageWithoutHealth(record)
            LocalUbuntuVuePolicy.isFamily(record.familyId) -> verifyVuePackageWithoutHealth(record) && verifyHealthChecks(record,record.healthChecks)
            record.familyId == PILOT_FAMILY -> when {
                isUbuntuAptReceipt(record) -> verifyUbuntuApt(record)
                else -> false
            }
            record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND -> verifyUbuntuLayer(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW -> verifyUbuntuReviewedRaw(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] in setOf(LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE) -> verifyUbuntuReviewedTree(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS -> {
                val key = record.metadata[LocalUbuntuBundleAliasPolicy.PRIMARY_KEY] ?: return@withContext false
                val primary = dependencyRecord(registry.list(), key) ?: return@withContext false
                LocalUbuntuBundleAliasPolicy.verifyReceipt(record, primary, root) && verifyPayload(primary) && verifyHealthChecks(record, record.healthChecks)
            }
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_APT -> verifyUbuntuApt(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM -> verifyUbuntuNpm(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE -> verifyUbuntuCMake(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_RUBY -> verifyUbuntuRuby(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION -> verifyAndroidWorkstation(record)
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_ANDROID_SDK_COMPONENT -> verifyAndroidSdkComponent(record)
            else -> false
        }
    }

    private suspend fun verifyUbuntuApt(record: ManagedPackageRecord): Boolean {
        if (!isUbuntuAptReceipt(record)) return false
        val recipe = UbuntuAptCatalog.find(record.familyId) ?: return false
        if (record.version != recipe.version || record.dependencies.size != 1) return false
        val layer = dependencyRecord(registry.list(), record.dependencies.single()) ?: return false
        if (!owns(layer) || layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] != FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND ||
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted() != recipe.packages.sorted()) return false
        val manifest = File(record.installRoot, record.checksumFile)
        val seal = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return false
        if (!manifest.isFile || PathSecurity.isSymbolicLink(manifest) || manifest.length() > 32_000 || sha256(manifest) != seal ||
            record.artifactSha256 != seal || !verifyChecksumManifest(record)) return false
        val packages = File(record.installRoot, "APT_PACKAGES.txt")
        if (!packages.isFile || PathSecurity.isSymbolicLink(packages) || packages.length() > 8_000 ||
            packages.readLines() != recipe.packages.sorted()) return false
        if (record.commands != recipe.commands.keys.associateWith { "${record.installRoot}/payload/host-bin/$it" }) return false
        val health = recipe.commands.keys.map { ManagedPackageHealthCheck("host-bin/$it", recipe.probeArgs(it)) }
        return record.healthChecks == health && verifyPayload(layer) && verifyHealthChecks(record, health)
    }

    /** Health-free Node byte proof for Svelte; never resolves or executes an ambient runtime. */
    internal suspend fun verifySvelteNode(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        require(record.familyId == "runtime.node" && record.version.substringBefore('.').toInt() >= 22 && owns(record) &&
            ownedReceipt(record.familyId,record.version) == record && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a")
        requireOwner(record)
        require(record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name && record.checksumFile == "SHA256SUMS")
        require(LocalPackageInstallJournal.matchesActivation(File(record.installRoot), record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)))
        require(record.artifactSha256?.matches(Regex("[0-9a-f]{64}")) == true && record.environment.isEmpty())
        require(LocalUbuntuNodeIntegration.verifyWrappers(record, root))
        verifyUbuntuReviewedTree(record, svelteNodeProofOnly = true)
    }.getOrDefault(false)

    internal suspend fun verifySveltePackageWithoutHealth(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        require(record.familyId == LocalUbuntuSveltePolicy.FAMILY_ID && record.version == LocalUbuntuSveltePolicy.VERSION && owns(record) &&
            ownedReceipt(record.familyId,record.version) == record && record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a")
        requireOwner(record)
        require(record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_NPM &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name && record.checksumFile == "SHA256SUMS")
        require(LocalPackageInstallJournal.matchesActivation(File(record.installRoot), record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)))
        verifyUbuntuNpm(record, svelteProofOnly = true)
    }.getOrDefault(false)

    private suspend fun verifyUbuntuNpm(record: ManagedPackageRecord, svelteProofOnly: Boolean = false): Boolean {
        val recipe = WorkstationInstallRecipeCatalog.find(record.familyId, record.version) ?: LocalUbuntuNpmPolicy.fromReceipt(record) ?: return false
        val muse = recipe.familyId == LocalUbuntuMusePolicy.FAMILY
        if (!LocalUbuntuNpmPolicy.supports(recipe) || record.dependencies.size > (if (muse) 2 else 1) ||
            record.dependencies.distinct().size != record.dependencies.size ||
            muse && record.dependencies.count(LocalUbuntuMusePolicy::isRuntimeKey) != 1 ||
            recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID && record.dependencies.size != 1) return false
        var museExecutable: String? = null
        for (key in record.dependencies) {
            val dependency = dependencyRecord(registry.list(), key) ?: return false
            if (muse && LocalUbuntuMusePolicy.isRuntimeKey(key)) {
                museExecutable = LocalUbuntuMusePolicy.requireRuntime(dependency, root)
                if (!owns(dependency) || !verifyPayload(dependency)) return false
            } else if (!LocalUbuntuNpmRuntimePolicy.admitsDependency(recipe, dependency) || !owns(dependency) ||
                !(if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) verifySvelteNode(dependency) else verifyPayload(dependency))) return false
        }
        val expected = LocalUbuntuNpmPolicy.commands(recipe).keys
        if (record.commands != expected.associateWith { "${record.installRoot}/payload/host-bin/$it" }) return false
        if (record.healthChecks != expected.map { ManagedPackageHealthCheck("host-bin/$it", LocalUbuntuNpmPolicy.healthArgs(recipe, it)) }) return false
        val root = File(record.installRoot)
        if (!LocalUbuntuNpmPolicy.verifyLanguageServerProbe(root, recipe)) return false
        val seal = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return false
        if (!LocalUbuntuNpmPolicy.verifyTree(root, seal)) return false
        val lock = File(root, "payload/npm/package-lock.json")
        if (LocalUbuntuNpmPolicy.sha256(lock) != record.artifactSha256) return false
        val review = kotlinx.serialization.json.Json.parseToJsonElement(File(root, "RECIPE_LOCK.json").readText())
            as? kotlinx.serialization.json.JsonObject ?: return false
        val integrity = (review["registryIntegrity"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return false
        LocalUbuntuNpmPolicy.validateLock(lock.readText(), recipe.copy(registryIntegrity = integrity))
        if (muse) {
            if (review["museRuntime"] != kotlinx.serialization.json.JsonPrimitive(record.dependencies.single(LocalUbuntuMusePolicy::isRuntimeKey))) return false
            val environment = LocalUbuntuMusePolicy.environment(requireNotNull(museExecutable))
            if (expected.any { !File(root, "payload/bin/$it").readText().contains(environment) }) return false
        }
        if (recipe.familyId == LocalUbuntuSveltePolicy.FAMILY_ID) {
            val paths = LocalPackageInstallJournal.paths(this@LocalManagedPackageAuthority.root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            if (root.absolutePath != paths.final.absolutePath || record.environment.isNotEmpty() ||
                record.pathEntries != listOf("${record.installRoot}/payload/host-bin")) return false
            val base = "${this@LocalManagedPackageAuthority.root}/packages/"
            val stageGuest = LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath, base) ?: return false
            val finalGuest = LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath, base) ?: return false
            val activation = record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] ?: return false
            val profile = LocalUbuntuSvelteLaunchPolicy.validateReview(review, record.dependencies.singleOrNull())
            val nodeCommand = (profile["nodeCommand"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return false
            record.dependencies.singleOrNull()?.let { key ->
                val node = dependencyRecord(registry.list(), key) ?: return false
                if (!LocalUbuntuNpmRuntimePolicy.matchesManagedNode(node, nodeCommand, this@LocalManagedPackageAuthority.root)) return false
            }
            if (!LocalUbuntuSvelteLaunchPolicy.verifyIntegration(root, stageGuest, finalGuest, activation,
                record.dependencies.singleOrNull(), "${this@LocalManagedPackageAuthority.root}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch")) return false
        }
        if (recipe.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID) {
            val paths = LocalPackageInstallJournal.paths(this@LocalManagedPackageAuthority.root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_NPM)
            if (root.absolutePath != paths.final.absolutePath || record.environment.isNotEmpty() ||
                record.pathEntries != listOf("${record.installRoot}/payload/host-bin")) return false
            val base = "${this@LocalManagedPackageAuthority.root}/packages/"
            val stageGuest = LocalLinuxPackageEnvironment.guestPath(paths.stage.absolutePath, base) ?: return false
            val finalGuest = LocalLinuxPackageEnvironment.guestPath(paths.final.absolutePath, base) ?: return false
            val activation = record.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY] ?: return false
            val profile = LocalUbuntuPrismaLaunchPolicy.validateReview(review, record.dependencies.singleOrNull())
            val nodeCommand = (profile["nodeCommand"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return false
            record.dependencies.singleOrNull()?.let { key ->
                val node = dependencyRecord(registry.list(), key) ?: return false
                if (!LocalUbuntuNpmRuntimePolicy.matchesManagedNode(node, nodeCommand, this@LocalManagedPackageAuthority.root)) return false
            }
            if (!LocalUbuntuPrismaLaunchPolicy.verifyIntegration(root, stageGuest, finalGuest, activation,
                record.dependencies.singleOrNull(), "${this@LocalManagedPackageAuthority.root}/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch")) return false
        }
        return svelteProofOnly || verifyHealthChecks(record, record.healthChecks)
    }

    internal suspend fun verifyCMakePackageWithoutHealth(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        val context = currentCoroutineContext()
        require(owns(record) && ownedReceipt(record.familyId, record.version) == record)
        requireOwner(record)
        LocalUbuntuCMakePolicy.verifyInstalled(record, root) { context.ensureActive() }
        CMakeManagedLaunchAuthority.layer(registry.list(), record, root) { context.ensureActive() }
        true
    }.getOrDefault(false)

    private suspend fun verifyUbuntuCMake(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        val context = currentCoroutineContext()
        LocalUbuntuCMakePolicy.verifyInstalled(record, root) { context.ensureActive() }
        CMakeManagedLaunchAuthority.layer(registry.list(), record, root) { context.ensureActive() }
        verifyHealthChecks(record, record.healthChecks)
    }.getOrDefault(false)

    private suspend fun verifyUbuntuRuby(record: ManagedPackageRecord): Boolean {
        val recipe = LocalUbuntuRubyCatalog.find(record.familyId, record.version) ?: return false
        if (!LocalUbuntuRubyPolicy.verifyIntegration(record, root) || record.dependencies.size != 1) return false
        val layer = dependencyRecord(registry.list(), record.dependencies.single()) ?: return false
        if (!owns(layer) || layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] != FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND ||
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted() != LocalUbuntuRubyPolicy.packages.sorted() ||
            !verifyPayload(layer)) return false
        val packageRoot = File(record.installRoot)
        if (!LocalUbuntuRubyPolicy.verifyFiles(packageRoot, recipe)) return false
        val seal = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return false
        if (!LocalUbuntuNpmPolicy.verifyTree(packageRoot, seal)) return false
        if (runCatching { LocalUbuntuRubyPolicy.admitNativeLibraries(packageRoot) }.isFailure) return false
        return verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyAndroidWorkstation(record: ManagedPackageRecord): Boolean {
        val policy = LocalAndroidWorkstationPolicy
        if (record.familyId != policy.FAMILY || record.version != policy.VERSION || record.scope != ExecutionScope.LOCAL_LINUX_ARM64.name ||
            record.abi != "arm64-v8a" || record.environment.isNotEmpty() || record.dependencies.toSet() != policy.dependencyKeys || record.dependencies.size != 4) return false
        val lock = policy.lock(androidComponents)
        if (record.artifactSha256 != policy.hash(lock)) return false
        val packageRoot = File(record.installRoot)
        fun original(relative: String, expected: String, executable: Boolean = false): Boolean {
            val file = File(packageRoot, relative)
            return file.isFile && !PathSecurity.isSymbolicLink(file) && (!executable || file.canExecute()) &&
                file.length() == expected.toByteArray().size.toLong() && file.readText() == expected
        }
        if (!original("WORKSTATION_LOCK.json", lock.toString()) ||
            record.commands != mapOf(policy.COMMAND to "${record.installRoot}/payload/host-bin/${policy.COMMAND}") ||
            record.pathEntries != listOf("${record.installRoot}/payload/host-bin") ||
            record.healthChecks != listOf(ManagedPackageHealthCheck("host-bin/${policy.COMMAND}", listOf("--droide-health")))) return false
        val dependencies = record.dependencies.map { dependencyRecord(registry.list(), it) ?: return false }
        for (dependency in dependencies) if (!owns(dependency) || !verifyPayload(dependency)) return false
        val jdk = dependencies.single { it.familyId == "toolchain.jdk" }
        if (jdk.metadata[LocalManagedPackageMetadata.KIND_KEY] != LocalManagedPackageMetadata.KIND_UBUNTU_APT) return false
        val gradle = dependencies.single { it.familyId == "build.gradle" }
        if (gradle.metadata[LocalManagedPackageMetadata.KIND_KEY] != LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE || gradle.artifactSha256 != policy.GRADLE_SHA256) return false
        val guest = LocalLinuxPackageEnvironment.guestPath(record.installRoot, "$root/packages/") ?: return false
        val gradleGuest = LocalLinuxPackageEnvironment.guestPath(gradle.installRoot, "$root/packages/") ?: return false
        if (!original("payload/bin/${policy.COMMAND}", policy.doctor("$gradleGuest/payload/bin/gradle"), true) ||
            !original("payload/host-bin/${policy.COMMAND}", policy.hostWrapper("$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch", guest), true)) return false
        val seal = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return false
        return LocalUbuntuNpmPolicy.verifyTree(packageRoot, seal) && verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyUbuntuLayer(record: ManagedPackageRecord): Boolean {
        if (record.commands.isNotEmpty() || record.dependencies.isNotEmpty()) return false
        val packages = record.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]
            ?.split(',')?.filter(String::isNotBlank) ?: return false
        if (packages.isEmpty() || packages.distinct().size != packages.size) return false
        if (!verifyChecksumManifest(record)) return false
        if (record.healthChecks != listOf(ManagedPackageHealthCheck("verify-layer", emptyList()))) return false
        return verifyHealthChecks(record, record.healthChecks)
    }

    private suspend fun verifyAndroidSdkComponent(record: ManagedPackageRecord): Boolean {
        if (record.commands.isNotEmpty() || record.dependencies.isNotEmpty()) return false
        val entry = AndroidLocalComponentCatalog.find(androidComponents, record.familyId, record.version) ?: return false
        val lockFile = File(record.installRoot, "COMPONENT_LOCK.json")
        if (!lockFile.isFile || PathSecurity.isSymbolicLink(lockFile) || lockFile.length() !in 1L..65_536L) return false
        val lock = runCatching { kotlinx.serialization.json.Json.decodeFromString<LocalAndroidSdkComponentLock>(lockFile.readText()) }
            .getOrNull() ?: return false
        if (!AndroidLocalComponentReceiptPolicy.matches(record, lock, entry)) return false
        if (!verifyChecksumManifest(record)) return false
        val component = File(record.installRoot, "payload/component")
        if (!component.isDirectory || PathSecurity.isSymbolicLink(component)) return false

        return when (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_KIND_KEY]) {
            AndroidLocalComponentKind.SDK_PLATFORM.name -> verifySdkPlatformComponent(record, component)
            AndroidLocalComponentKind.BUILD_TOOLS_ARM64.name -> verifyBuildToolsComponent(record, component)
            else -> false
        }
    }

    private fun verifySdkPlatformComponent(record: ManagedPackageRecord, component: File): Boolean {
        if (record.familyId != "sdk.android") return false
        val api = record.version.toIntOrNull()?.takeIf { it in 1..999 } ?: return false
        if (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY] != "platforms/android-$api") return false
        if (record.metadata.containsKey(LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY)) return false
        val androidJar = File(component, "android.jar")
        val sourceProperties = File(component, "source.properties")
        if (!androidJar.isFile || PathSecurity.isSymbolicLink(androidJar) || androidJar.length() <= 1_000_000L) return false
        if (!sourceProperties.isFile || PathSecurity.isSymbolicLink(sourceProperties) || sourceProperties.length() !in 1L..65_536L) return false
        val values = parseSimpleProperties(sourceProperties) ?: return false
        return values["AndroidVersion.ApiLevel"] == api.toString() && values["Pkg.Revision"]?.substringBefore('.') == "2"
    }

    private fun verifyBuildToolsComponent(record: ManagedPackageRecord, component: File): Boolean {
        if (record.familyId != "build.android-tools" || !record.version.matches(Regex("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}"))) return false
        if (record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_GUEST_TARGET_KEY] != "build-tools/${record.version}") return false
        val manifest = parseNativeToolManifest(record.metadata[LocalManagedPackageMetadata.ANDROID_COMPONENT_NATIVE_TOOLS_KEY]) ?: return false
        if (manifest.keys != AndroidLocalNativeTool.REQUIRED_BUILD_TOOLS_NATIVE_NAMES) return false
        val sourceProperties = File(component, "source.properties")
        val apksignerJar = File(component, "lib/apksigner.jar")
        val d8Jar = File(component, "lib/d8.jar")
        if (!sourceProperties.isFile || PathSecurity.isSymbolicLink(sourceProperties) || sourceProperties.length() !in 1L..65_536L) return false
        if (parseSimpleProperties(sourceProperties)?.get("Pkg.Revision") != record.version) return false
        if (!apksignerJar.isFile || PathSecurity.isSymbolicLink(apksignerJar) || apksignerJar.length() <= 100_000L) return false
        if (!d8Jar.isFile || PathSecurity.isSymbolicLink(d8Jar) || d8Jar.length() <= 1_000_000L) return false
        for ((name, expectedSha) in manifest) {
            val file = File(component, name)
            if (!file.isFile || PathSecurity.isSymbolicLink(file) || !file.canExecute() || sha256(file) != expectedSha) return false
            if (runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(file) }.isFailure) return false
        }
        return AndroidBuildToolsPayload.verifyLaunchers(component)
    }

    private fun parseSimpleProperties(file: File): Map<String, String>? = runCatching {
        file.readLines(Charsets.UTF_8).mapNotNull { line ->
            val split = line.indexOf('=')
            if (split <= 0) null else line.substring(0, split).trim() to line.substring(split + 1).trim()
        }.toMap()
    }.getOrNull()

    private fun parseNativeToolManifest(value: String?): Map<String, String>? {
        if (value == null || value.length !in 1..1024) return null
        val pairs = value.split(';')
        if (pairs.isEmpty() || pairs.size > 16) return null
        val out = linkedMapOf<String, String>()
        for (pair in pairs) {
            val split = pair.indexOf('=')
            if (split <= 0 || split == pair.lastIndex) return null
            val name = pair.substring(0, split)
            val sha = pair.substring(split + 1)
            if (!name.matches(Regex("[A-Za-z0-9._+-]{1,64}")) || !sha.matches(Regex("[0-9a-f]{64}")) || out.put(name, sha) != null) return null
        }
        return out
    }

    private suspend fun verifyUbuntuReviewedRaw(record: ManagedPackageRecord): Boolean {
        if (record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name) return false
        if (record.metadata[LocalManagedPackageMetadata.LIBC_KEY] != DeclarativeLibcCompatibility.GLIBC.name) return false
        if (record.metadata[LocalManagedPackageMetadata.ELF_INTERPRETER_KEY] != LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) return false
        val command = record.metadata[LocalManagedPackageMetadata.COMMAND_KEY] ?: return false
        if (record.commands != mapOf(command to "${record.installRoot}/payload/host-bin/$command")) return false
        if (record.artifactSha256 == null || !verifyChecksumManifest(record)) return false
        val payload = File(record.installRoot, "payload/bin/$command")
        if (!payload.isFile || PathSecurity.isSymbolicLink(payload)) return false
        if (sha256(payload) != record.artifactSha256) return false
        val elf = runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(payload) }.getOrNull() ?: return false
        if (elf.interpreter != record.metadata[LocalManagedPackageMetadata.ELF_INTERPRETER_KEY]) return false
        val wrapper = "${record.installRoot}/payload/host-bin/$command"
        LocalExecutionSubstrate.requireSafeLocalPath(wrapper)
        if (LocalExecutionSubstrate.shell("test -x ${q(wrapper)}").exitCode != 0) return false
        return verifyHealthChecks(record, record.healthChecks)
    }

    /** Standalone verification only; an install/start caller must use its existing deadline overload. */
    internal fun elpPackageGeneration():Any=registry.elpPackageGeneration()
    internal fun isElpPackageGenerationCurrent(token:Any):Boolean=registry.isElpPackageGenerationCurrent(token)
    internal suspend fun verifyElpPackageWithoutHealth(record:ManagedPackageRecord,deadline:ElpDeadline=ElpDeadline.start()):Boolean = runSuspendCatching {
        val context=currentCoroutineContext();require(owns(record));LocalUbuntuElpLaunchAuthority.provePackage(root,record){context.ensureActive();deadline.check()};true
    }.getOrDefault(false)
    internal suspend fun reviewedElpLaunchIdentity(discovered:String,selections:Map<String,String>,deadline:ElpDeadline):ElpManagedLaunchIdentity? {
        val generation=registry.elpPackageGeneration()
        val records=registry.list().map(::normalizeReceipt)
        val record=LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull{it.familyId==LocalUbuntuElpPolicy.FAMILY}?:return null
        val identity=LocalUbuntuElpLaunchAuthority.inspect(root,record,records,selections,discovered,deadline)
        return identity.takeIf{registry.isElpPackageGenerationCurrent(generation)}
    }
    internal suspend fun proveElpLaunchIdentity(identity:ElpManagedLaunchIdentity,deadline:ElpDeadline) {
        val context=currentCoroutineContext();LocalUbuntuElpLaunchAuthority.prove(root,identity,registry.list().map(::normalizeReceipt),deadline){context.ensureActive()}
    }
    internal suspend fun verifyGraphqlPackageWithoutHealth(record:ManagedPackageRecord):Boolean = verifyGraphqlPackageWithoutHealth(record,GraphqlDeadline.start())
    internal suspend fun verifyGraphqlPackageWithoutHealth(record:ManagedPackageRecord,deadline:GraphqlDeadline):Boolean = runSuspendCatching {
        deadline.check();require(owns(record)&&LocalUbuntuGraphqlPolicy.isFamily(record.familyId))
        val context=currentCoroutineContext();val active={context.ensureActive();deadline.check()}
        val proof=LocalUbuntuGraphqlInstaller.provePackage(root,record,active)
        if(record.familyId==LocalUbuntuGraphqlPolicy.FAMILY){val records=registry.list().map(::normalizeReceipt);val node=requireNotNull(dependencyRecord(records,LocalUbuntuGraphqlNodePolicy.KEY));require(owns(node))
            val runtime=LocalUbuntuGraphqlInstaller.provePackage(root,node,active);require(proof.dependencyReceipts==mapOf(node.familyId to runtime.receiptSha256))};active();true
    }.getOrDefault(false)
    internal suspend fun reviewedGraphqlLaunchIdentity(discovered:String,selections:Map<String,String>,deadline:GraphqlDeadline):GraphqlManagedLaunchIdentity? =
        kotlinx.coroutines.withTimeout(deadline.remainingMillis().coerceAtLeast(1)){ManagedPackageMutationGate.mutex.withLock {
            deadline.check();val records=registry.list().map(::normalizeReceipt).filter(::owns)
            val record=LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull{it.familyId==LocalUbuntuGraphqlPolicy.FAMILY}?:return@withLock null
            LocalUbuntuGraphqlLaunchAuthority.inspect(root,record,records,selections,discovered,deadline)
        }}

    internal suspend fun verifyTomlPackageWithoutHealth(record:ManagedPackageRecord):Boolean = runSuspendCatching {
        require(owns(record)&&LocalUbuntuTomlPolicy.isFamily(record.familyId))
        val context=currentCoroutineContext();val active={context.ensureActive()}
        val proof=LocalUbuntuTomlInstaller.provePackage(root,record,active)
        if(record.familyId==LocalUbuntuTomlPolicy.FAMILY) {
            val records=registry.list().map(::normalizeReceipt)
            val node=requireNotNull(dependencyRecord(records,LocalUbuntuTomlNodePolicy.KEY));require(owns(node))
            val runtime=LocalUbuntuTomlInstaller.provePackage(root,node,active)
            require(proof.dependencyReceipts==mapOf(node.familyId to runtime.receiptSha256))
        };true
    }.getOrDefault(false)

    internal suspend fun reviewedTomlLaunchIdentity(discovered:String,selections:Map<String,String>):TomlManagedLaunchIdentity? =
        ManagedPackageMutationGate.mutex.withLock {
            val records=registry.list().map(::normalizeReceipt).filter(::owns)
            val record=LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull{it.familyId==LocalUbuntuTomlPolicy.FAMILY}?:return@withLock null
            LocalUbuntuTomlLaunchAuthority.inspect(root,record,records,selections,discovered)
        }

    internal suspend fun verifyCSharpPackageWithoutHealth(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        require(owns(record) && LocalUbuntuCSharpPolicy.isFamily(record.familyId))
        val context = currentCoroutineContext(); val active = { context.ensureActive() }
        val proof = LocalUbuntuCSharpInstaller.provePackage(root, record, active)
        if (record.familyId == LocalUbuntuCSharpPolicy.FAMILY) {
            val records = registry.list().map(::normalizeReceipt)
            val dependencies = LocalUbuntuCSharpPolicy.dependencyKeys.map { key -> requireNotNull(dependencyRecord(records, key)) }
            require(dependencies.all(::owns))
            val proofs = dependencies.map { LocalUbuntuCSharpInstaller.provePackage(root, it, active) }
            require(proof.dependencyReceipts == proofs.associate { it.record.familyId to it.receiptSha256 })
        }
        true
    }.getOrDefault(false)

    internal suspend fun reviewedCSharpLaunchIdentity(discovered: String, selections: Map<String,String>): CSharpManagedLaunchIdentity? =
        ManagedPackageMutationGate.mutex.withLock {
            val records = registry.list().map(::normalizeReceipt).filter(::owns)
            val record = LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull { it.familyId == LocalUbuntuCSharpPolicy.FAMILY }
                ?: return@withLock null
            LocalUbuntuCSharpLaunchAuthority.inspect(root,record,records,selections,discovered)
        }

    /** Vue-only receipt/closure admission; pure shared file readers never grant another family's authority. */
    internal suspend fun verifyVuePackageWithoutHealth(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        require(LocalUbuntuVuePolicy.isFamily(record.familyId) && record.version == LocalUbuntuVuePolicy.version(record.familyId) && owns(record))
        requireOwner(record)
        val command = if(record.familyId == LocalUbuntuVuePolicy.FAMILY) LocalUbuntuVuePolicy.COMMAND else LocalUbuntuVueHelperPolicy.COMMAND
        val kind = if(record.familyId == LocalUbuntuVuePolicy.FAMILY) LocalManagedPackageMetadata.KIND_UBUNTU_NPM else LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE
        require(record.metadata[LocalManagedPackageMetadata.KIND_KEY] == kind &&
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] == DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name &&
            record.metadata[LocalManagedPackageMetadata.LIBC_KEY] == DeclarativeLibcCompatibility.GLIBC.name &&
            record.scope == ExecutionScope.LOCAL_LINUX_ARM64.name && record.abi == "arm64-v8a" && record.checksumFile == "SHA256SUMS" &&
            record.environment.isEmpty() && record.pathEntries == listOf("${record.installRoot}/payload/host-bin") &&
            record.commands == mapOf(command to "${record.installRoot}/payload/host-bin/$command") &&
            record.healthChecks == listOf(ManagedPackageHealthCheck("host-bin/$command",listOf("--health"))))
        val context = currentCoroutineContext()
        val records = registry.list()
        require(records.size <= 512 && records.map { it.familyId to it.version }.distinct().size == records.size)
        require(LocalPackageInstallJournal.matchesActivation(File(record.installRoot),record.metadata.getValue(LocalManagedPackageMetadata.ACTIVATION_ID_KEY)))
        if (record.familyId == LocalUbuntuVueHelperPolicy.FAMILY) {
            val recipe = requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
            LocalUbuntuVueHelperPolicy.requireReviewed(recipe)
            require(record.dependencies.size == 1)
            val layer = requireNotNull(dependencyRecord(records,record.dependencies.single()))
            require(owns(layer) && layer.commands.isEmpty() && layer.dependencies.isEmpty() &&
                layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] == FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND &&
                layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted() == recipe.requiredPackages.sorted())
            require(verifyChecksumManifest(layer))
            LocalUbuntuVueLaunchAuthority.provePackage(root,record) { context.ensureActive() }
        } else {
            require(record.dependencies == listOf(LocalUbuntuVueHelperPolicy.KEY))
            val helper = requireNotNull(dependencyRecord(records,LocalUbuntuVueHelperPolicy.KEY))
            require(owns(helper) && verifyVuePackageWithoutHealth(helper))
            LocalUbuntuVueLaunchAuthority.proveOwnedPackages(root,record,listOf(helper)) { context.ensureActive() }
        }
        true
    }.getOrDefault(false)

    internal suspend fun reviewedVueLaunchIdentity(discovered: String, selections: Map<String,String>): VueManagedLaunchIdentity? =
        ManagedPackageMutationGate.mutex.withLock {
            val records = registry.list()
            val record = LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull { it.familyId == LocalUbuntuVuePolicy.FAMILY }
                ?: return@withLock null
            LocalPackageInstallJournal.requireNoVueHealthHold(LocalPackageInstallJournal.paths(root,record.familyId,record.version,LocalManagedPackageMetadata.KIND_UBUNTU_NPM))
            val selectedCommand = if (discovered == LocalUbuntuVuePolicy.COMMAND)
                "${LocalUbuntuVuePolicy.ROOT}/payload/bin/${LocalUbuntuVuePolicy.COMMAND}" else discovered
            LocalUbuntuVueLaunchAuthority.inspect(root,record,records,selections,selectedCommand)
        }

    internal suspend fun verifyNimPackageWithoutHealth(record: ManagedPackageRecord): Boolean = runSuspendCatching {
        require(LocalUbuntuNimPolicy.isFamily(record.familyId) && owns(record))
        val context = currentCoroutineContext()
        val records=registry.list()
        require(records.size<=512 && records.map { it.familyId to it.version }.distinct().size==records.size)
        val recipe=requireNotNull(LocalUbuntuVendorPolicy.fromReceipt(record))
        val wanted=if(record.familyId==LocalUbuntuNimPolicy.FAMILY) setOf(LocalUbuntuNimToolchainPolicy.KEY,LocalUbuntuNimHelperPolicy.KEY) else emptySet()
        val layerKey=record.dependencies.single { it !in wanted }
        require(record.dependencies.size==wanted.size+1 && record.dependencies.toSet()==wanted+layerKey)
        val layer=requireNotNull(dependencyRecord(records,layerKey))
        require(owns(layer) && layer.commands.isEmpty() && layer.dependencies.isEmpty() &&
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND]==FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND &&
            layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted()==recipe.requiredPackages.sorted())
        require(verifyChecksumManifest(layer)) // Data only; never the guest layer's executable health selector.
        if(wanted.isEmpty()) LocalUbuntuNimLaunchAuthority.provePackage(root,record) { context.ensureActive() }
        else {
            val deps=wanted.map { key -> requireNotNull(dependencyRecord(records,key)).also { require(owns(it) && it.dependencies==listOf(layerKey)) } }
            LocalUbuntuNimLaunchAuthority.proveOwnedPackages(root,record,deps) { context.ensureActive() }
        }
        true
    }.getOrDefault(false)

    internal suspend fun reviewedNimLaunchIdentity(discovered: String, selections: Map<String,String>): NimManagedLaunchIdentity? =
        ManagedPackageMutationGate.mutex.withLock {
            val records=registry.list()
            val record=LocalLinuxPackageEnvironment.selectedRecords(records,selections,root).singleOrNull { it.familyId==LocalUbuntuNimPolicy.FAMILY }
                ?: return@withLock null
            LocalUbuntuNimLaunchAuthority.inspect(root,record,records,selections,discovered)
        }

    private suspend fun verifyUbuntuReviewedTree(record: ManagedPackageRecord, svelteNodeProofOnly: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (LocalUbuntuNimPolicy.isFamily(record.familyId)) {
            if (!verifyNimPackageWithoutHealth(record)) return@withContext false
            return@withContext record.familyId==LocalUbuntuNimToolchainPolicy.FAMILY || verifyHealthChecks(record,record.healthChecks)
        }
        if (svelteNodeProofOnly && (record.familyId != "runtime.node" || record.metadata[LocalManagedPackageMetadata.KIND_KEY] != LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE)) return@withContext false
        val vendor = if (record.metadata[LocalManagedPackageMetadata.KIND_KEY] == LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE)
            LocalUbuntuVendorPolicy.fromReceipt(record) ?: return@withContext false else null
        if (vendor != null) {
            if (!LocalUbuntuVendorPolicy.verifyIntegration(record, vendor, root)) return@withContext false
            // Publisher libraries belong to an owned Ubuntu layer. Only the reviewed js-debug route may
            // additionally own exact official Node.
            val expectedRuntime = if (vendor.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_KEY
                else if (LocalUbuntuJsDebugPolicy.requiresLegacyNode(vendor)) LocalUbuntuJsDebugPolicy.NODE_KEY else null
            if (record.dependencies.size != (if (expectedRuntime == null) 1 else 2) ||
                record.dependencies.distinct().size != record.dependencies.size) return@withContext false
            if (expectedRuntime != null) {
                if (expectedRuntime !in record.dependencies) return@withContext false
                val node = dependencyRecord(registry.list(), expectedRuntime) ?: return@withContext false
                val valid = runCatching {
                    if (vendor.familyId == LocalUbuntuPowerShellEditorServicesPolicy.FAMILY) LocalUbuntuPowerShellEditorServicesPolicy.requireRuntime(node, root)
                    else LocalUbuntuJsDebugPolicy.requireNode(node, root)
                }.isSuccess
                if (!valid || !owns(node) || !verifyPayload(node)) return@withContext false
            }
            val layerKey = record.dependencies.singleOrNull { it != expectedRuntime } ?: return@withContext false
            val layer = dependencyRecord(registry.list(), layerKey) ?: return@withContext false
            if (layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND] != FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND ||
                layer.metadata[FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES]?.split(',')?.sorted() != vendor.requiredPackages.sorted() ||
                !verifyPayload(layer)) return@withContext false
        }
        if (record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name) return@withContext false
        if (record.metadata[LocalManagedPackageMetadata.LIBC_KEY] != DeclarativeLibcCompatibility.GLIBC.name) return@withContext false
        val primaryCommand = record.metadata[LocalManagedPackageMetadata.COMMAND_KEY] ?: return@withContext false
        val expectedTreeSha = record.metadata[LocalManagedPackageMetadata.TREE_SHA256_KEY] ?: return@withContext false
        val expectedLinksSha = record.metadata[LocalManagedPackageMetadata.TREE_LINKS_SHA256_KEY] ?: return@withContext false
        val expectedCommandsSha = record.metadata[LocalManagedPackageMetadata.TREE_COMMANDS_SHA256_KEY] ?: return@withContext false
        val expectedSealSha = record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] ?: return@withContext false
        if (record.commands.isEmpty() || primaryCommand !in record.commands || record.artifactSha256 == null) return@withContext false
        val sealFile = File(record.installRoot, record.checksumFile)
        if (!sealFile.isFile || PathSecurity.isSymbolicLink(sealFile) || sha256(sealFile) != expectedSealSha) return@withContext false
        val verifiedFiles = LocalPackageIntegrity.verifiedChecksums(File(record.installRoot), sealFile)
            ?: return@withContext false

        val treeRoot = File(record.installRoot, "payload/tree")
        val guestBin = File(record.installRoot, "payload/bin")
        val hostBin = File(record.installRoot, "payload/host-bin")
        if (!treeRoot.isDirectory || PathSecurity.isSymbolicLink(treeRoot) || !guestBin.isDirectory || !hostBin.isDirectory) return@withContext false

        val linksFile = File(record.installRoot, "TREE_LINKS.tsv")
        val commandsFile = File(record.installRoot, "TREE_COMMANDS.tsv")
        if (!linksFile.isFile || !commandsFile.isFile || PathSecurity.isSymbolicLink(linksFile) || PathSecurity.isSymbolicLink(commandsFile)) return@withContext false
        if (sha256(linksFile) != expectedLinksSha || sha256(commandsFile) != expectedCommandsSha) return@withContext false

        val links = parseTreeLinks(linksFile) ?: return@withContext false
        val commandTargets = parseTreeCommands(commandsFile) ?: return@withContext false
        if (commandTargets.keys != record.commands.keys || primaryCommand !in commandTargets) return@withContext false
        if (!verifyTreeLinks(treeRoot, links)) return@withContext false
        if (computeTreeSha(treeRoot, links, verifiedFiles) != expectedTreeSha) return@withContext false

        for ((command, target) in commandTargets) {
            val hostWrapper = File(hostBin, command)
            val guestWrapper = File(guestBin, command)
            if (!hostWrapper.isFile || !hostWrapper.canExecute() || PathSecurity.isSymbolicLink(hostWrapper)) return@withContext false
            if (!guestWrapper.isFile || !guestWrapper.canExecute() || PathSecurity.isSymbolicLink(guestWrapper)) return@withContext false
            if (record.commands[command] != hostWrapper.absolutePath) return@withContext false
            val resolved = resolveTreeTarget(target, links) ?: return@withContext false
            val executable = File(treeRoot, resolved)
            if (!executable.isFile || PathSecurity.isSymbolicLink(executable)) return@withContext false
            if (vendor != null) {
                if (runCatching { LocalUbuntuVendorPolicy.admit(vendor, executable) }.isFailure) return@withContext false
                continue
            }
            if (LocalUbuntuNodeDistribution.isAuxScript(record.familyId, record.version, command, target, executable)) continue
            val elf = runCatching { LinuxArm64ElfAdmission.requireUbuntuGlibc(executable) }.getOrNull() ?: return@withContext false
            if (elf.interpreter != LinuxArm64ElfAdmission.AARCH64_GLIBC_INTERPRETER) return@withContext false
        }
        if (record.familyId == "runtime.node" && !LocalUbuntuNodeIntegration.verifyWrappers(record, root)) return@withContext false
        if (record.familyId == LocalUbuntuJdkPolicy.FAMILY) {
            if (primaryCommand != "java") return@withContext false
            val expectedEnvironment = runCatching {
                LocalUbuntuJdkPolicy.environment(record.familyId, treeRoot.absolutePath, commandTargets)
            }.getOrNull() ?: return@withContext false
            if (record.environment != expectedEnvironment) return@withContext false
            val javaHome = File(expectedEnvironment.getValue("JAVA_HOME"))
            if (!javaHome.isDirectory || PathSecurity.isSymbolicLink(javaHome) ||
                !javaHome.canonicalPath.startsWith(treeRoot.canonicalPath + File.separator)) return@withContext false
            val launcher = "$root/guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/launch"
            val command = listOf("/system/bin/sh", launcher, "/bin/sh", "-c",
                LocalUbuntuJdkPolicy.healthScript(javaHome.absolutePath, compileSample = false)).joinToString(" ", transform = ::q)
            if (LocalExecutionSubstrate.shellBounded(command, maxOutputBytes = 128_000, timeoutMs = 60_000).exitCode != 0) return@withContext false
        }
        if (vendor != null && LocalUbuntuVendorPolicy.isPowerShell(vendor)) {
            val runtime = if (record.familyId == LocalUbuntuPowerShellRuntimePolicy.FAMILY) record
                else dependencyRecord(registry.list(), LocalUbuntuPowerShellEditorServicesPolicy.RUNTIME_KEY) ?: return@withContext false
            val powerShellContext = currentCoroutineContext()
            val inventory = LocalUbuntuPowerShellRuntimePolicy.requireTree(File(runtime.installRoot, "payload/tree/pwsh")) { powerShellContext.ensureActive() }
            LocalUbuntuPowerShellRuntimePolicy.requireExecutionClosure(inventory, File(root, "guest/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}/rootfs")) { powerShellContext.ensureActive() }
        }
        svelteNodeProofOnly || verifyHealthChecks(record, record.healthChecks)
    }

    private data class LocalTreeLink(val path: String, val target: String, val resolved: String)

    private fun parseTreeLinks(file: File): List<LocalTreeLink>? = runCatching {
        if (file.length() > 256 * 1024) return@runCatching null
        file.readLines(Charsets.UTF_8).filter(String::isNotBlank).map { line ->
            val parts = line.split('\t')
            require(parts.size == 3)
            requireSafeTreePath(parts[0]); requireSafeTreeRelativeTarget(parts[1]); requireSafeTreePath(parts[2])
            require(resolveRelativeTreeLink(parts[0], parts[1]) == parts[2]) { "Whole-tree link manifest has an invalid resolved path" }
            LocalTreeLink(parts[0], parts[1], parts[2])
        }.also { links -> require(links.map { it.path }.distinct().size == links.size) }
    }.getOrNull()

    private fun parseTreeCommands(file: File): Map<String, String>? = runCatching {
        if (file.length() > 64 * 1024) return@runCatching null
        linkedMapOf<String, String>().apply {
            file.readLines(Charsets.UTF_8).filter(String::isNotBlank).forEach { line ->
                val parts = line.split('\t')
                require(parts.size == 2 && parts[0].matches(Regex("[A-Za-z0-9._+-]{1,80}")))
                requireSafeTreePath(parts[1])
                require(put(parts[0], parts[1]) == null)
            }
            require(size in 1..17)
        }
    }.getOrNull()

    private fun verifyTreeLinks(root: File, links: List<LocalTreeLink>): Boolean {
        val actualLinks = mutableSetOf<String>()
        fun walk(directory: File): Boolean {
            val children = directory.listFiles() ?: return false
            for (child in children) {
                val relative = child.relativeTo(root).invariantSeparatorsPath
                if (PathSecurity.isSymbolicLink(child)) {
                    actualLinks += relative
                    continue
                }
                if (child.isDirectory && !walk(child)) return false
            }
            return true
        }
        if (!walk(root)) return false
        if (actualLinks != links.map { it.path }.toSet()) return false
        val canonicalRoot = root.canonicalFile
        return links.all { link ->
            val file = File(root, link.path)
            runCatching {
                android.system.Os.readlink(file.absolutePath) == link.target &&
                    file.exists() &&
                    file.canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
            }.getOrDefault(false)
        }
    }

    private suspend fun computeTreeSha(root: File, links: List<LocalTreeLink>, verifiedFiles: Map<String, String>): String? = try {
        val files = mutableListOf<Pair<String, File>>()
        suspend fun walk(directory: File) {
            directory.listFiles()?.sortedBy { it.name }?.forEach { child ->
                currentCoroutineContext().ensureActive()
                if (PathSecurity.isSymbolicLink(child)) return@forEach
                if (child.isDirectory) walk(child)
                else if (child.isFile) files += child.relativeTo(root).invariantSeparatorsPath to child
                else error("Unsupported installed tree entry")
            } ?: error("Cannot enumerate installed whole-tree payload")
        }
        walk(root)
        val prefix = "payload/tree/"
        require(files.map { prefix + it.first }.toSet() == verifiedFiles.keys.filter { it.startsWith(prefix) }.toSet()) {
            "Installed whole-tree inventory does not match the verified manifest"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.first }.forEach { (path, file) ->
            currentCoroutineContext().ensureActive()
            requireSafeTreePath(path)
            val mode = if (file.canExecute()) 493 else 420
            digest.update(path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(mode.toString().toByteArray(Charsets.US_ASCII)); digest.update(0)
            digest.update(verifiedFiles.getValue(prefix + path).toByteArray(Charsets.US_ASCII)); digest.update('\n'.code.toByte())
        }
        links.sortedBy { it.path }.forEach { link ->
            currentCoroutineContext().ensureActive()
            digest.update("LINK".toByteArray(Charsets.US_ASCII)); digest.update(0)
            digest.update(link.path.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(link.target.toByteArray(Charsets.UTF_8)); digest.update(0)
            digest.update(link.resolved.toByteArray(Charsets.UTF_8)); digest.update('\n'.code.toByte())
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    private fun resolveTreeTarget(path: String, links: List<LocalTreeLink>): String? = runCatching {
        val byPath = links.associateBy { it.path }
        var current = path
        val seen = mutableSetOf<String>()
        while (true) {
            require(seen.add(current))
            val link = byPath[current] ?: break
            current = link.resolved
        }
        requireSafeTreePath(current)
        current
    }.getOrNull()

    private fun requireSafeTreePath(value: String) {
        require(value.length in 1..500 && !value.startsWith('/') && '\\' !in value && '\u0000' !in value && '\n' !in value && '\r' !in value)
        val segments = value.split('/')
        require(segments.size in 1..16 && segments.all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".." && segment.length <= 180 &&
                segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))
        })
    }

    private fun requireSafeTreeRelativeTarget(value: String) {
        require(value.isNotBlank() && !value.startsWith('/') && '\\' !in value && '\u0000' !in value && '\n' !in value && '\r' !in value && value.length <= 500)
        value.split('/').forEach { segment ->
            require(segment.isNotBlank() && (segment == "." || segment == ".." || segment.matches(Regex("[A-Za-z0-9._+@=~%\\[\\]-]{1,180}"))))
        }
    }

    private fun resolveRelativeTreeLink(path: String, target: String): String {
        requireSafeTreePath(path)
        requireSafeTreeRelativeTarget(target)
        val stack = path.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
        target.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> require(stack.isNotEmpty()) { "Whole-tree link escapes the payload root" }.also { stack.removeAt(stack.lastIndex) }
                else -> stack += segment
            }
        }
        require(stack.isNotEmpty()) { "Whole-tree link resolves to the payload root" }
        return stack.joinToString("/").also(::requireSafeTreePath)
    }

    private suspend fun verifyChecksumManifest(record: ManagedPackageRecord): Boolean {
        return LocalPackageIntegrity.verify(File(record.installRoot), File(record.installRoot, record.checksumFile))
    }

    private suspend fun verifyHealthChecks(record: ManagedPackageRecord, checks: List<ManagedPackageHealthCheck>): Boolean {
        for (health in checks) {
            val executable = "${record.installRoot}/payload/${health.executable}"
            LocalExecutionSubstrate.requireSafeLocalPath(executable)
            val command = (listOf("/system/bin/sh", executable) + health.args).joinToString(" ") { q(it) }
            val vueHold = if(LocalUbuntuVuePolicy.isFamily(record.familyId)) LocalPackageInstallJournal.beginVueHealth(
                LocalPackageInstallJournal.paths(root,record.familyId,record.version,record.metadata.getValue(LocalManagedPackageMetadata.KIND_KEY))) else null
            val result = LocalExecutionSubstrate.shellBounded(command, maxOutputBytes = 128_000, timeoutMs = 60_000)
            vueHold?.let { LocalPackageInstallJournal.finishVueHealth(it,if(record.familyId == LocalUbuntuVuePolicy.FAMILY)
                LocalUbuntuVueLspHealth.cleanupVerified(result) else LocalUbuntuVueLspHealth.helperCleanupVerified(result)) }
            if (record.familyId == LocalUbuntuVuePolicy.FAMILY && !LocalUbuntuVueLspHealth.succeeded(result)) return false
            if (record.familyId == LocalUbuntuVueHelperPolicy.FAMILY && !LocalUbuntuVueLspHealth.helperSucceeded(result)) return false
            if (record.familyId == LocalUbuntuNimPolicy.FAMILY && !LocalUbuntuNimLspHealth.succeeded(result)) return false
            if (LocalUbuntuNimPolicy.isFamily(record.familyId) && (result.timedOut || result.outputIncomplete)) return false
            if (record.familyId == LocalUbuntuCMakePolicy.FAMILY && !LocalUbuntuCMakeLspHealth.succeeded(result)) return false
            if (record.familyId == LocalUbuntuSveltePolicy.FAMILY_ID && !LocalUbuntuSvelteLspHealth.succeeded(result)) return false
            if (result.exitCode != 0 || ((record.familyId == LocalUbuntuPrismaPolicy.FAMILY_ID ||
                    record.familyId in setOf(LocalUbuntuPowerShellRuntimePolicy.FAMILY, LocalUbuntuPowerShellEditorServicesPolicy.FAMILY)) &&
                    (result.timedOut || result.outputIncomplete))) return false
        }
        return true
    }

    private fun dependencyRecord(records: List<ManagedPackageRecord>, key: String): ManagedPackageRecord? {
        val familyId = key.substringBeforeLast('@', missingDelimiterValue = "")
        val version = key.substringAfterLast('@', missingDelimiterValue = "")
        if (familyId.isBlank() || version.isBlank()) return null
        return records.firstOrNull { it.familyId == familyId && it.version == version }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun project(records: List<ManagedPackageRecord>) = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)
        LocalCommandProjectionPublisher.requireSafeDirectories(listOf(
            bin, ubuntuBin, File(managed, ".staging"), File(managed, ".previous"),
            File(managed, ".ubuntu-staging"), File(managed, ".ubuntu-previous"),
        ))
        val retained = retainedLegacyReceipts(records)
        val active = LocalCommandProjectionPolicy.project(
            records.filterNot(::isLegacyReceiptCandidate).map(::normalizeReceipt).filter(::owns), root)
        active.forEach(::requireOwner)
        val owners = linkedMapOf<String, String>()
        active.forEach { record -> record.commands.forEach { (name, _) ->
            check(owners.putIfAbsent(name, "${record.familyId}@${record.version}") == null) { "Local command collision for $name" }
        } }
        projectAndroidHostCommands(active, retained)
        projectUbuntuGuestCommands(active.filter {
            it.metadata[LocalManagedPackageMetadata.KIND_KEY] in setOf(
                LocalUbuntuElpPolicy.KIND,
                LocalUbuntuCSharpPolicy.KIND,
                LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_RAW,
                LocalManagedPackageMetadata.KIND_UBUNTU_REVIEWED_TREE,
                LocalManagedPackageMetadata.KIND_UBUNTU_VENDOR_TREE,
                LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS,
                LocalManagedPackageMetadata.KIND_UBUNTU_NPM,
                LocalManagedPackageMetadata.KIND_UBUNTU_RUBY,
            LocalManagedPackageMetadata.KIND_UBUNTU_CMAKE,
                LocalManagedPackageMetadata.KIND_ANDROID_WORKSTATION,
                LocalManagedPackageMetadata.KIND_UBUNTU_APT,
            )
        })
    }

    private suspend fun projectAndroidHostCommands(active: List<ManagedPackageRecord>, retained: List<ManagedPackageRecord>) {
        check(managed.mkdir() || managed.isDirectory) { "Cannot prepare local command projection" }
        val stage = File(managed, ".staging")
        val previous = File(managed, ".previous")
        val scripts = linkedMapOf<String, String>()
        active.forEach { record -> record.commands.forEach { (name, target) ->
            require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
            check(target.startsWith("${record.installRoot}/")) { "Local command target escaped package" }
            scripts[name] = "#!/system/bin/sh\nexec /system/bin/sh ${q(target)} \"${'$'}@\"\n"
        } }
        if (!java.nio.file.Files.exists(stage.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !java.nio.file.Files.exists(previous.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            LocalCommandProjectionSnapshot.matches(bin, scripts)) return
        LocalCommandProjectionPublisher.publish(bin, stage, previous, scripts,
            { file, text -> ownsProjectionScript(text, guest = false) ||
                file.name == "jq" && retained.any { record ->
                    text == "#!/system/bin/sh\nexec /system/bin/sh ${q(record.commands.getValue("jq"))} \"${'$'}@\"\n"
                } }, projectionObserver)
    }

    private suspend fun projectUbuntuGuestCommands(active: List<ManagedPackageRecord>) {
        check(managed.mkdir() || managed.isDirectory) { "Cannot prepare Ubuntu command projection" }
        val stage = File(managed, ".ubuntu-staging")
        val previous = File(managed, ".ubuntu-previous")
        val scripts = linkedMapOf<String, String>()
        active.forEach { record -> record.commands.keys.forEach { name ->
            require(name.matches(Regex("[A-Za-z0-9._+-]{1,80}")))
            val relativeRoot = record.installRoot.removePrefix("$root/packages/")
            check(relativeRoot != record.installRoot && relativeRoot.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Ubuntu package projection escaped app-owned package storage"
            }
            val guestTarget = "/opt/droide/packages/$relativeRoot/payload/bin/$name"
            scripts[name] = "#!/bin/sh\nexec ${q(guestTarget)} \"${'$'}@\"\n"
        } }
        if (!java.nio.file.Files.exists(stage.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !java.nio.file.Files.exists(previous.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            LocalCommandProjectionSnapshot.matches(ubuntuBin, scripts)) return
        LocalCommandProjectionPublisher.publish(ubuntuBin, stage, previous, scripts,
            { _, text -> ownsProjectionScript(text, guest = true) }, projectionObserver)
    }

    private fun ownsProjectionScript(text: String, guest: Boolean): Boolean {
        val prefix = if (guest) "#!/bin/sh\nexec " else "#!/system/bin/sh\nexec /system/bin/sh "
        val suffix = " \"${'$'}@\"\n"
        if (!text.startsWith(prefix) || !text.endsWith(suffix)) return false
        val encoded = text.removePrefix(prefix).removeSuffix(suffix)
        val target = if (encoded.length >= 2 && encoded.startsWith("'") && encoded.endsWith("'"))
            encoded.substring(1, encoded.length - 1).replace("'\\''", "'") else encoded
        if (q(target) != encoded || target.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val rootPrefix = if (guest) "/opt/droide/packages/" else "$root/packages/"
        if (!target.startsWith(rootPrefix)) return false
        val segments = target.removePrefix(rootPrefix).split('/')
        val directory = if (guest) "bin" else "host-bin"
        return segments.size >= 5 && segments.none { it.isBlank() || it == "." || it == ".." } &&
            segments.takeLast(3).take(2) == listOf("payload", directory) &&
            segments.last().matches(Regex("[A-Za-z0-9._+-]{1,80}"))
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)

    companion object { const val PILOT_FAMILY = "cli.jq" }
}
