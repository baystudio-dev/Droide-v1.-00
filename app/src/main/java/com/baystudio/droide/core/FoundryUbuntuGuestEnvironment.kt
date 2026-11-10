package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object FoundryUbuntuGuestEnvironmentSpec {
    const val ID = "ubuntu-base-24.04.5-arm64"
    const val VERSION = "Ubuntu 24.04"
    const val ROOT_DIR = "ubuntu-base-24.04.5"
    const val PROFILE_REVISION = "foundry-glibc-r2-layered"
    const val LEGACY_PROFILE_REVISION = "foundry-glibc-r1"
    const val LEGACY_BROAD_BASE_MARKER = "legacy_broad_base_preserved=1"
    const val LEGACY_LAYER_OWNERSHIP_MARKER = "legacy_layer_ownership_complete=1"
    const val LEGACY_PHYSICAL_COMPACTION_MARKER = "legacy_physical_compaction_complete=1"
    const val LAYER_RECORD_VERSION = "24.04.5.r2"
    const val LAYER_METADATA_KIND = "droide.runtime.kind"
    const val LAYER_METADATA_PACKAGES = "droide.runtime.packages"
    const val LAYER_KIND = "ubuntu-apt-layer"

    // Nim private-helper recipes request their own exact package layer; baseline Node/Nim selections
    // are unchanged.
    val baselinePackages = listOf(
        "ca-certificates",
    )

    val canonicalBasePackages = FoundryUbuntuBaseManifest.packages

    val legacyBroadRootPackages = listOf(
        "ca-certificates", "binutils", "curl", "git", "gnupg2", "libc6-dev",
        "libcurl4-openssl-dev", "libedit2", "libgcc-13-dev", "libncurses-dev",
        "libpython3-dev", "libsqlite3-0", "libstdc++-13-dev", "libxml2-dev",
        "libz3-dev", "pkg-config", "python3", "tzdata", "unzip", "zip", "zlib1g-dev",
        "libicu74", "libssl3", "libssl3t64", "libunwind8", "libkrb5-3",
    )

    const val MAX_LAYER_PACKAGES = 48
    const val LAYER_TRANSACTION_OVERHEAD_BYTES = 64L * 1024L * 1024L
    private val PACKAGE_RE = Regex("[A-Za-z0-9][A-Za-z0-9+_.@-]{0,119}")

    fun normalizeLayerPackages(packages: List<String>): List<String> {
        require(packages.size <= MAX_LAYER_PACKAGES) { "Ubuntu capability layer is too large" }
        require(packages.all { PACKAGE_RE.matches(it) }) { "Ubuntu capability layer contains an invalid package name" }
        return packages.distinct().sorted()
    }

    val rootfsArtifact = TrustedArtifactSpec(
        id = "ubuntu-base-24.04.5-arm64",
        url = FoundryUbuntuBaseManifest.ROOTFS_URL,
        sha256 = FoundryUbuntuBaseManifest.ROOTFS_SHA256,
        fileName = "ubuntu-base-24.04.5-base-arm64.tar.gz",
        maxBytes = FoundryUbuntuBaseManifest.ROOTFS_BYTES,
        expectedBytes = FoundryUbuntuBaseManifest.ROOTFS_BYTES,
    )
}

interface ReviewedGuestExecutionEnvironment {
    val backendId: PackageBackendId
    val environmentId: String
    val environmentVersion: String
    fun launcherPath(): String
    suspend fun isHealthy(): Boolean
    suspend fun ensureInstalled(): String
    suspend fun execute(argv: List<String>, maxOutputBytes: Int = 1_500_000): ExecResult
}

class AlpineReviewedGuestEnvironment(
    context: Context,

    private val delegate: WorkstationGuestEnvironmentManager = WorkstationGuestEnvironmentManager(context.applicationContext),
) : ReviewedGuestExecutionEnvironment {
    override val backendId: PackageBackendId get() = delegate.backendId
    override val environmentId: String = WorkstationGuestEnvironmentSpec.ID
    override val environmentVersion: String = WorkstationGuestEnvironmentSpec.VERSION
    override fun launcherPath(): String = delegate.launcherPath()
    override suspend fun isHealthy(): Boolean = delegate.isHealthy()
    override suspend fun ensureInstalled(): String = delegate.ensureInstalled()
    override suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult = delegate.execute(argv, maxOutputBytes)
}

private object FoundryUbuntuGuestMutationGate {
    val mutex = Mutex()
}

/** Serialize trusted guest argv without interpreting its contents in the Android host shell. */
internal object FoundryUbuntuGuestCommand {
    const val MAX_ARGUMENTS = 128
    const val MAX_ARGUMENT_BYTES = 32_768
    const val MAX_COMMAND_BYTES = 32_768

    const val MAX_LEASE_QUOTED_BYTES = 98_304

    fun downloadedArchiveSizesCommand(cacheDir: String): List<String> = listOf(
        "sh", "-lc",
        "set -eu; db=0; ik=0; for f in ${LocalExecutionSubstrate.shellQuote(cacheDir)}/*.deb; do [ -f \"\$f\" ] || continue; " +
            "n=\$(stat -c %s \"\$f\"); db=\$((db+n)); " +
            "n=\$(dpkg-deb -f \"\$f\" Installed-Size 2>/dev/null || printf 0); ik=\$((ik+n)); done; " +
            "printf 'download=%s\ninstalled_kib=%s\n' \"\$db\" \"\$ik\"",
    )

    fun build(launcher: String, argv: List<String>): String {
        require(argv.isNotEmpty()) { "Guest command is empty" }
        require(argv.size <= MAX_ARGUMENTS) { "Ubuntu guest command has too many arguments" }
        require(argv.first().isNotEmpty()) { "Guest executable is empty" }
        require(launcher.startsWith('/') && '\u0000' !in launcher && launcher.length <= 4_096) {
            "Invalid Ubuntu guest launcher"
        }
        val command = buildString {
            append("/system/bin/sh ").append(LocalExecutionSubstrate.shellQuote(launcher))
            argv.forEach { arg ->
                // LF/CR, spaces and shell metacharacters are legitimate argv data. Single-quote each argument so
                // only the explicitly selected guest shell can interpret scripts.
                require('\u0000' !in arg && arg.length <= MAX_ARGUMENT_BYTES &&
                    arg.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) { "Unsafe Ubuntu guest argument" }
                append(' ').append(LocalExecutionSubstrate.shellQuote(arg))
                require(length <= MAX_COMMAND_BYTES) { "Ubuntu guest command is too large" }
            }
        }
        require(command.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) { "Ubuntu guest command is too large" }

        fun leaseQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        require(leaseQuote(leaseQuote(command)).toByteArray(Charsets.UTF_8).size <= MAX_LEASE_QUOTED_BYTES) {
            "Ubuntu guest command exceeds the process-lease argument limit"
        }
        return command
    }
}

class FoundryUbuntuGuestEnvironmentManager(
    context: Context,
) : ReviewedGuestExecutionEnvironment {
    companion object {

        private val readinessCache = UbuntuGuestReadinessCache()
        private val packageAttachAdmission = UbuntuPackageAttachAdmission()
        private val guestGroupNames = UbuntuGuestGroupNames()
    }
    private val appContext = context.applicationContext
    private val root = context.applicationContext.filesDir.canonicalPath
    override val backendId: PackageBackendId = PackageBackendId.LOCAL_APP
    private val guestBase = "$root/guest"
    private val finalRoot = "$guestBase/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
    private val launcher = "$finalRoot/launch"
    private val legacyCompactor = FoundryUbuntuLegacyCompactor( finalRoot, launcher)

    override val environmentId: String = FoundryUbuntuGuestEnvironmentSpec.ID
    override val environmentVersion: String = FoundryUbuntuGuestEnvironmentSpec.VERSION
    override fun launcherPath(): String = launcher

    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        LocalExecutionSubstrate.requireContextRoot(root)

        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(launcher)} && test -f ${q(profileLock)} && " +
                "grep -Fxq ${q("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")} ${q(profileLock)} && " +
                "/system/bin/sh ${q(launcher)} /bin/sh -lc ${q("cd . && /bin/pwd -P >/dev/null && test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64")}",
            maxOutputBytes = 16_384,
        )
        probe.exitCode == 0
    }

    override suspend fun ensureInstalled(): String = ensureInstalledWithAdmission(null, {})

    internal suspend fun ensureInstalled(attempt: PackagedNativeAdmissionAttempt, checkCancelled: () -> Unit): String =
        ensureInstalledWithAdmission(attempt, checkCancelled)

    private suspend fun ensureInstalledWithAdmission(attempt: PackagedNativeAdmissionAttempt?, checkCancelled: () -> Unit): String = withContext(Dispatchers.IO) {
        checkCancelled()
        LocalExecutionSubstrate.requireContextRoot(root)
        requireArm64()

        attempt?.require(checkCancelled)
        // Package downloads do not replace the guest.

        if (packageAttachAdmission.mayAttach()) return@withContext finalRoot
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            val installed = ensureInstalledUnlocked(attempt, checkCancelled)
            UbuntuAptCacheDefaults.install(File("$finalRoot/rootfs"))
            PackagedWorkstationRootfs.evict(appContext, FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.fileName, FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256)
            installed
        }
    }

    private suspend fun ensureInstalledUnlocked(attempt: PackagedNativeAdmissionAttempt? = null, checkCancelled: () -> Unit = {}): String {
        checkCancelled()
        LocalExecutionSubstrate.requireContextRoot(root)
        requireArm64()
        attempt?.require(checkCancelled)?.launcher ?: PackagedLinuxEngine.requireReady(appContext)
        // A warm attach still builds its PATH/binds afresh; this only avoids repeated PRoot
        // recovery/health probes while the admitted guest identity is unchanged.
        if (readinessCache.mayReuse(readinessSeal(attempt = attempt, checkCancelled = checkCancelled))) return finalRoot
        readinessCache.invalidate()
        reconcileInterruptedBootstrap()
        reconcileInterruptedLegacyCompactionUnlocked()
        repairExistingLauncher(attempt, checkCancelled)
        if (isHealthy()) {
            repairGuestLoopbackHostsUnlocked(checkCancelled)
            repairGuestGroupNamesUnlocked(checkCancelled)
            readinessCache.admit(readinessSeal(attempt = attempt, checkCancelled = checkCancelled))
            return finalRoot
        }
        if (isLegacyHealthyUnlocked()) {
            migrateLegacyProfileInPlaceUnlocked()
            check(isHealthy()) { "Migrated Ubuntu guest failed layered-profile health verification" }
            repairGuestLoopbackHostsUnlocked(checkCancelled)
            repairGuestGroupNamesUnlocked(checkCancelled)
            readinessCache.admit(readinessSeal(attempt = attempt, checkCancelled = checkCancelled))
            return finalRoot
        }
        if (existingGuestPresent()) {
            error(
                "Existing Ubuntu guest failed its health check and was preserved. " +
                    "Droide will not replace user packages or /root automatically; use an explicit recovery/reset action."
            )
        }

        DeviceWorkstationStorageGuard.requireHeadroom(

            additionalBytes = maxOf(
                384L * 1024L * 1024L,
                requireNotNull(FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.expectedBytes) * 10L,
            ),
            purpose = "bootstrap the Foundry Ubuntu ARM64 runtime",
        )
        val stage = "$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        val previous = "$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        listOf(guestBase, stage, previous, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)

        val prep = LocalExecutionSubstrate.shell(
            "set -eu; rm -rf ${q(stage)}; mkdir -p ${q("$stage/rootfs")} ${q("$guestBase/.previous")}",
        )
        check(prep.exitCode == 0) { "Could not prepare Ubuntu guest environment: ${prep.output}" }

        try {
            BundledRootfsExtractor.extract(appContext, BundledRootfsExtractor.ubuntu, File("$stage/rootfs"))
            val loopbackContext = currentCoroutineContext()
            val loopback = UbuntuGuestLoopbackHosts.repair(File("$stage/rootfs")) {
                loopbackContext.ensureActive()
                checkCancelled()
            }
            check(loopback.hasLocalhost) { "Could not initialize guest-local localhost: ${loopback.diagnostic}" }

            val dns = LocalExecutionSubstrate.shell(
                "set -eu; mkdir -p ${q("$stage/rootfs/etc")}; " +
                    "printf '%s\\n' 'nameserver 1.1.1.1' 'nameserver 8.8.8.8' > ${q("$stage/rootfs/etc/resolv.conf")}",
            )
            check(dns.exitCode == 0) { "Could not prepare Ubuntu guest DNS configuration: ${dns.output}" }

            pushText("$stage/launch", launcherScript(attempt, checkCancelled), 493)
            LocalGuestCertificates.seed(File("$stage/rootfs"))
            pushText("$stage/BOOTSTRAP_LOCK.txt", bootstrapLock() + "bootstrap_tls=android-system-ca\n", 420)
            val stageHealth = LocalExecutionSubstrate.shellBounded(
                "/system/bin/sh ${q("$stage/launch")} /bin/sh -lc ${q("cd . && /bin/pwd -P >/dev/null && test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64 && test -s /etc/ssl/certs/ca-certificates.crt")}",
                maxOutputBytes = 16_384,
            )
            check(stageHealth.exitCode == 0) { "Rootless Ubuntu bootstrap health check failed: ${stageHealth.output}" }

            val move = LocalExecutionSubstrate.shell(
                // Bootstrap is creation-only. An existing guest, healthy or not, is never replaced by an
                // automatic health path. Re-check at commit time to close the race window.
                "set -eu; if [ -e ${q(finalRoot)} ] || [ -L ${q(finalRoot)} ]; then exit 73; fi; mv ${q(stage)} ${q(finalRoot)}",
            )
            check(move.exitCode == 0) { "Could not activate Ubuntu guest environment: ${move.output}" }
            check(isHealthy()) { "Activated Ubuntu guest environment failed health verification" }
            repairGuestLoopbackHostsUnlocked(checkCancelled)
            repairGuestGroupNamesUnlocked(checkCancelled)
            readinessCache.admit(readinessSeal(attempt = attempt, checkCancelled = checkCancelled))
            // A legacy `.previous` recovery anchor is intentionally retained. Fresh creation does not create
            // one, and automatic cleanup never deletes a user-recoverable guest tree.
            withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            return finalRoot
        } catch (failure: Throwable) {
            // Cleanup only the staging tree. Current/previous guests are recovery state and are never
            // deleted or swapped merely because activation/health verification failed.
            withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            throw failure
        }
    }

    /** Optional existing-guest repair preserves custom files and never strands the shell. */
    private suspend fun repairGuestLoopbackHostsUnlocked(checkCancelled: () -> Unit) {
        val owner = currentCoroutineContext()
        val result = UbuntuGuestLoopbackHosts.repair(File("$finalRoot/rootfs")) {
            owner.ensureActive()
            checkCancelled()
        }
        if (!result.hasLocalhost) Log.w("DroideUbuntu", result.diagnostic + " The existing Ubuntu shell remains available.")
    }

    /** Optional name repair cannot turn a healthy guest into a failed terminal admission. */
    private suspend fun repairGuestGroupNamesUnlocked(checkCancelled: () -> Unit) {
        guestGroupNames.repair(
            rootfs = File("$finalRoot/rootfs"),
            execute = { argv, limit, timeout -> executeUnlocked(argv, limit, timeout) },
            checkCancelled = checkCancelled,
            diagnostic = { Log.w("DroideUbuntu", "$it\nThe existing Ubuntu shell remains available.") },
        )
    }

    /** Refresh the APK-owned launcher after an app update without replacing the user's guest. */
    private fun readinessSeal(engine: File? = null, attempt: PackagedNativeAdmissionAttempt? = null, checkCancelled: () -> Unit = {}): String? = UbuntuGuestReadinessCache.fingerprint(
        guest = File(finalRoot), engine = engine ?: attempt?.require(checkCancelled)?.launcher ?: PackagedLinuxEngine.requireReady(appContext),
        expectedLauncher = launcherScript(attempt, checkCancelled),
        recoveryPaths = listOf(
            File("$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"),
            File("$finalRoot/.launch-next"),
            File("$finalRoot/rootfs${FoundryUbuntuLegacyCompactor.TX_ROOT}"),
        ),
        retainedPrevious = File("$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"),
    )?.let { runtime ->
        // Both warm and package-operation fast attaches must observe the admitted name files. A
        // changed/missing/unsafe file declines reuse; only the gate-owned path may repair it.
        UbuntuGuestLoopbackHosts.fingerprint(File("$finalRoot/rootfs"))?.let { "$runtime:$it" }
    }

    /** Refresh the APK-owned launcher after an app update without replacing the user's guest. */
    private suspend fun repairExistingLauncher(attempt: PackagedNativeAdmissionAttempt? = null, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        val lock = File(finalRoot, "BOOTSTRAP_LOCK.txt")
        val existing = File(launcher)
        if (!lock.isFile || !existing.isFile || !File(finalRoot, "rootfs").isDirectory) return
        check(!PathSecurity.isSymbolicLink(lock) && !PathSecurity.isSymbolicLink(existing) &&
            lock.length() in 1..65536 && existing.length() in 1..65536) {
            "Cannot migrate an unsafe Ubuntu launcher"
        }
        check(lock.readLines().contains("environment=${FoundryUbuntuGuestEnvironmentSpec.ID}")) {
            "Cannot migrate an unowned Ubuntu guest"
        }
        val next = launcherScript(attempt, checkCancelled)
        val old = existing.readText()
        if (next == old) return
        fun replace(text: String) {
            val pending = File(finalRoot, ".launch-next")
            LocalExecutionSubstrate.requireSafeLocalPath(pending.absolutePath)
            FileOutputStream(pending).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            check(pending.renameTo(existing)) { "Cannot atomically update Ubuntu launcher" }
        }
        replace(next)
        if (!isHealthy() && !isLegacyHealthyUnlocked()) {
            withContext(NonCancellable) { replace(old) }
            error("Packaged PRoot could not start the existing Ubuntu guest; original launcher restored")
        }
    }

    /** True only for an r1 guest that has been migrated in place but whose historical package
     * consumers have not yet been rebound to explicit r2 capability-layer ownership. */
    suspend fun needsLegacyOwnershipAdoption(): Boolean = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            isLegacyBroadBasePreservedUnlocked() && !isLegacyLayerOwnershipCompleteUnlocked()
        }
    }

    /** Persist the point at which every registry-visible legacy glibc consumer has an explicit
     * capability-layer dependency. */
    suspend fun markLegacyOwnershipComplete() = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy ownership completion is only valid for an in-place r1 migration" }
            check(isHealthy()) { "Ubuntu guest must be healthy before sealing legacy layer ownership" }
            val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
            val marker = FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER
            val seal = LocalExecutionSubstrate.shellBounded(
                "set -eu; grep -Fxq ${q(marker)} ${q(profileLock)} || printf '%s\n' ${q(marker)} >> ${q(profileLock)}",
                maxOutputBytes = 16_384,
            )
            check(seal.exitCode == 0) { "Could not seal legacy Ubuntu capability-layer ownership" }
        }
    }

    suspend fun needsLegacyPhysicalCompaction(): Boolean = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            isLegacyBroadBasePreservedUnlocked() &&
                isLegacyLayerOwnershipCompleteUnlocked() &&
                !isLegacyPhysicalCompactionCompleteUnlocked()
        }
    }

    internal suspend fun beginLegacyPhysicalCompaction(
        protectedPackageSets: List<List<String>>,
    ): LegacyUbuntuCompactionTransaction = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            ensureInstalledUnlocked()
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy Ubuntu broad base is not present" }
            check(isLegacyLayerOwnershipCompleteUnlocked()) { "Legacy Ubuntu ownership is not complete" }
            check(!isLegacyPhysicalCompactionCompleteUnlocked()) { "Legacy Ubuntu guest is already compacted" }
            ensureAptHealthyUnlocked()
            legacyCompactor.begin(
                legacyBroadRoots = FoundryUbuntuGuestEnvironmentSpec.legacyBroadRootPackages,
                canonicalBaseRoots = FoundryUbuntuGuestEnvironmentSpec.canonicalBasePackages,
                baselineRoots = FoundryUbuntuGuestEnvironmentSpec.baselinePackages,
                protectedPackageSets = protectedPackageSets,
            )
        }
    }

    internal suspend fun rollbackLegacyPhysicalCompaction(transaction: LegacyUbuntuCompactionTransaction) = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            legacyCompactor.rollback(transaction)
            check(isHealthy()) { "Ubuntu guest failed health verification after compaction rollback" }
        }
    }

    internal suspend fun finalizeLegacyPhysicalCompaction(transaction: LegacyUbuntuCompactionTransaction) = withContext(Dispatchers.IO) {
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            check(legacyCompactor.exists()) { "Legacy Ubuntu compaction transaction disappeared before commit" }
            check(isLegacyBroadBasePreservedUnlocked()) { "Legacy Ubuntu broad-base marker changed before commit" }
            check(isLegacyLayerOwnershipCompleteUnlocked()) { "Legacy Ubuntu ownership marker changed before commit" }
            check(isHealthy()) { "Ubuntu guest is unhealthy before compaction commit" }
            val lockText = bootstrapLock() +
                FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER + "\n" +
                FoundryUbuntuGuestEnvironmentSpec.LEGACY_PHYSICAL_COMPACTION_MARKER + "\n"
            val lockPath = "$finalRoot/BOOTSTRAP_LOCK.txt"
            val lockStage = "$finalRoot/.BOOTSTRAP_LOCK.compaction.tmp"
            var committed = false
            try {
                pushText(lockStage, lockText, 420)
                val commitLock = LocalExecutionSubstrate.shellBounded(
                    "set -eu; chmod 0644 ${q(lockStage)}; mv -f ${q(lockStage)} ${q(lockPath)}",
                    maxOutputBytes = 16_384,
                )
                check(commitLock.exitCode == 0) { "Could not atomically commit legacy Ubuntu compaction marker" }
                committed = true
                legacyCompactor.discard(transaction)
                check(isHealthy()) { "Ubuntu guest failed health verification after compaction commit" }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (!committed) {
                        runCatching { LocalExecutionSubstrate.shellBounded("rm -f ${q(lockStage)}", maxOutputBytes = 8_192) }
                        try {
                            legacyCompactor.rollback(transaction)
                            check(isHealthy()) { "Ubuntu guest failed health verification after failed compaction commit rollback" }
                        } catch (rollbackFailure: Throwable) {
                            failure.addSuppressed(rollbackFailure)
                        }
                    }
                }
                throw failure
            }
        }
    }

    private val aptStorage by lazy { UbuntuAptTransactionStorage(File(finalRoot)) }
    private val aptDependencies by lazy {
        UbuntuAptDependencyInstaller(
            commands = UbuntuAptCommandRunner(::executeUnlocked),
            metadata = UbuntuAptMetadataCache(File("$finalRoot/rootfs"), File(finalRoot, ".apt-index-cache.json")),
            requireSpace = { bytes, purpose -> DeviceWorkstationStorageGuard.requireHeadroom(bytes, purpose) },
            layerMarked = { id -> File("$finalRoot/rootfs/var/lib/droide-foundry/layers/$id.lock").let { it.isFile && !PathSecurity.isSymbolicLink(it) } },
            markLayer = ::writeLayerLockUnlocked,
            cleanup = aptStorage::cleanup,
        )
    }

    suspend fun ensurePackages(packages: List<String>, progress: (String) -> Unit = {}, allowSharedRepair: Boolean = true): ManagedPackageRecord? = withContext(Dispatchers.IO) {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return@withContext null
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            progress("Checking shared Ubuntu runtime…\n")
            if (!allowSharedRepair && existingGuestPresent()) {
                check(isHealthy()) { "Managed Ubuntu requires its explicit repair before this reviewed installation" }
                aptDependencies.requireNoPendingConfiguration()
            } else ensureInstalledUnlocked()
            UbuntuAptCacheDefaults.install(File("$finalRoot/rootfs"))
            val layerId = layerId(normalized)
            // Pin the admitted packaged-engine path once. Attach probes stat that file; they must not rehash
            // all APK-native binaries during a long download.
            val admittedEngine = PackagedLinuxEngine.requireReady(appContext)
            packageAttachAdmission.duringPackageOperation({ readinessSeal(admittedEngine) }) {
                aptStorage.operation(progress) {
                    val reclaimed = aptStorage.pruneAbandoned()
                    if (reclaimed > 0) progress("Removed $reclaimed abandoned installer download cache(s).\n")
                    aptDependencies.ensure(normalized, layerId, progress, allowSharedRepair)
                    createLayerAnchorUnlocked(layerId, normalized)
                }
            }
        }
    }

    fun layerRecordKey(packages: List<String>): String? {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return null
        return "${layerFamilyId(normalized)}@${FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION}"
    }

    /** Releasing an integration never removes shared Ubuntu packages, homes or APT caches. */
    suspend fun releaseLayer(packages: List<String>) = withContext(Dispatchers.IO) {
        val normalized = FoundryUbuntuGuestEnvironmentSpec.normalizeLayerPackages(packages)
        if (normalized.isEmpty()) return@withContext
        FoundryUbuntuGuestMutationGate.mutex.withLock {
            val exists = LocalExecutionSubstrate.shell("test -d ${q(finalRoot)}").exitCode == 0
            if (!exists) return@withLock
            check(isHealthy()) { "Ubuntu guest is unhealthy; preserving capability-layer ownership for repair" }
            val cleanup = executeUnlocked(
                FoundryUbuntuLayerReleasePolicy.command(layerId(normalized)),
                maxOutputBytes = 64_000,
            )
            check(cleanup.exitCode == 0) { "Could not finalize Ubuntu capability-layer cleanup" }
        }
    }

    override suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult = withContext(Dispatchers.IO) {
        ensureInstalled()
        executeUnlocked(argv, maxOutputBytes)
    }

    internal suspend fun verifyCommands(commands: Map<String, List<String>>, progress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        UbuntuCommandHealthChecks.verify(commands, prepare = { ensureInstalled() },
            execute = { argv, limit, timeout -> executeUnlocked(argv, limit, timeout) }, progress = progress)
    }

    private suspend fun executeUnlocked(argv: List<String>, maxOutputBytes: Int, timeoutMs: Long = 900_000, onOutput: ((String) -> Unit)? = null): ExecResult {
        val command = FoundryUbuntuGuestCommand.build(launcher, argv)
        return LocalExecutionSubstrate.shellBounded(command, maxOutputBytes, timeoutMs = timeoutMs, onOutput = onOutput, keepTail = true)
    }

    private suspend fun isLegacyHealthyUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(launcher)} && test -f ${q(profileLock)} && " +
                "grep -Fxq ${q("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.LEGACY_PROFILE_REVISION}")} ${q(profileLock)} && " +
                "/system/bin/sh ${q(launcher)} /bin/sh -lc ${q("cd . && /bin/pwd -P >/dev/null && test -x /usr/bin/apt-get && test \"\$(/usr/bin/dpkg --print-architecture)\" = arm64")}",
            maxOutputBytes = 16_384,
        )
        return probe.exitCode == 0
    }

    private suspend fun migrateLegacyProfileInPlaceUnlocked() {
        // New and repaired artifacts gain explicit r2 layer ownership, while legacy artifacts continue
        // to run until a later ownership-complete compaction can prove every dependency is covered.
        ensureAptHealthyUnlocked()
        val migratedLock = bootstrapLock() + FoundryUbuntuGuestEnvironmentSpec.LEGACY_BROAD_BASE_MARKER + "\n"
        pushText("$finalRoot/BOOTSTRAP_LOCK.txt", migratedLock, 420)
    }

    private suspend fun isLegacyBroadBasePreservedUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_BROAD_BASE_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun isLegacyLayerOwnershipCompleteUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_LAYER_OWNERSHIP_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun isLegacyPhysicalCompactionCompleteUnlocked(): Boolean {
        val profileLock = "$finalRoot/BOOTSTRAP_LOCK.txt"
        val probe = LocalExecutionSubstrate.shellBounded(
            "test -f ${q(profileLock)} && grep -Fxq ${q(FoundryUbuntuGuestEnvironmentSpec.LEGACY_PHYSICAL_COMPACTION_MARKER)} ${q(profileLock)}",
            maxOutputBytes = 8_192,
        )
        return probe.exitCode == 0
    }

    private suspend fun reconcileInterruptedLegacyCompactionUnlocked() {
        UbuntuGuestRecoveryInspection.recoverLegacyCompactionIfPresent(
            File(launcher), File("$finalRoot/rootfs${FoundryUbuntuLegacyCompactor.TX_ROOT}"),
        ) { legacyCompactor.recoverInterrupted(isLegacyPhysicalCompactionCompleteUnlocked()) }
    }

    private suspend fun ensureAptHealthyUnlocked(progress: (String) -> Unit = {}) {
        aptStorage.operation(progress) {
            val repairLayer = "000000000000000000000000"
            try { aptDependencies.ensureHealthy(progress, repairLayer) }
            finally { withContext(NonCancellable) { aptStorage.cleanup(repairLayer) } }
        }
    }

    private suspend fun writeLayerLockUnlocked(layerId: String, packages: List<String>) {
        val guestDir = "/var/lib/droide-foundry/layers"
        val prep = executeUnlocked(listOf("sh", "-lc", "mkdir -p $guestDir"), maxOutputBytes = 16_384)
        check(prep.exitCode == 0) { "Could not prepare Ubuntu capability-layer metadata" }
        val hostPath = "$finalRoot/rootfs$guestDir/$layerId.lock"
        pushText(
            hostPath,
            buildString {
                appendLine("schema=1")
                appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
                appendLine("layer_id=$layerId")
                appendLine("packages=${packages.joinToString(",")}")
            },
            420,
        )
    }

    private fun layerId(packages: List<String>): String {
        val identity = FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION + "\n" + packages.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun layerFamilyId(packages: List<String>): String = "runtime.ubuntu-deps.${layerId(packages)}"

    private suspend fun createLayerAnchorUnlocked(layerId: String, packages: List<String>): ManagedPackageRecord {
        val familyId = layerFamilyId(packages)
        val version = FoundryUbuntuGuestEnvironmentSpec.LAYER_RECORD_VERSION
        val installRoot = "$root/packages/$familyId/$version"
        val stage = "$root/packages/.staging/$familyId.$version"
        listOf(installRoot, stage).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val layerText = buildString {
            appendLine("schema=1")
            appendLine("kind=${FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND}")
            appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
            appendLine("layer_id=$layerId")
            appendLine("packages=${packages.joinToString(",")}")
        }
        val expectedMarker = "layer_id=$layerId"
        val guestHealth = "set -eu; test \"\$(dpkg --print-architecture)\" = arm64; " +
            "test -f /var/lib/droide-foundry/layers/$layerId.lock; " + FoundryUbuntuAptPolicy.installedPackagesHealthCommand(packages)
        val healthScript = "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${q(launcher)} /bin/sh -lc ${q(guestHealth)}\n"
        val healthSha = MessageDigest.getInstance("SHA-256").digest(healthScript.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val existing = LocalExecutionSubstrate.shellBounded(
            "cd ${q(installRoot)} 2>/dev/null && toybox sha256sum -c SHA256SUMS >/dev/null 2>&1 && grep -Fxq ${q(expectedMarker)} LAYER.txt && " +
                "printf '%s\\n' ${q("$healthSha  payload/verify-layer")} | toybox sha256sum -c - >/dev/null 2>&1",
            maxOutputBytes = 16_384,
        )
        if (existing.exitCode != 0) {
            val prep = LocalExecutionSubstrate.shell("rm -rf ${q(stage)}; mkdir -p ${q("$stage/payload")}")
            check(prep.exitCode == 0) { "Could not prepare Ubuntu layer ownership anchor" }
            pushText("$stage/LAYER.txt", layerText, 420)
            pushText("$stage/payload/verify-layer", healthScript, 493)
            val seal = LocalExecutionSubstrate.shell(
                "set -eu; cd ${q(stage)}; toybox sha256sum LAYER.txt payload/verify-layer > SHA256SUMS; " +
                    "mkdir -p ${q(installRoot.substringBeforeLast('/'))}; rm -rf ${q(installRoot)}; mv ${q(stage)} ${q(installRoot)}",
            )
            check(seal.exitCode == 0) { "Could not seal Ubuntu layer ownership anchor: ${seal.output.takeLast(4_000)}" }
        }
        return ManagedPackageRecord(
            familyId = familyId,
            version = version,
            scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
            installRoot = installRoot,
            installedAtEpochMs = System.currentTimeMillis(),
            active = true,
            healthChecks = listOf(ManagedPackageHealthCheck("verify-layer", emptyList())),
            metadata = mapOf(
                PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_KIND to FoundryUbuntuGuestEnvironmentSpec.LAYER_KIND,
                FoundryUbuntuGuestEnvironmentSpec.LAYER_METADATA_PACKAGES to packages.joinToString(","),
            ),
        )
    }

    private suspend fun reconcileInterruptedBootstrap() {
        val previous = "$guestBase/.previous/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        val stage = "$guestBase/.staging/${FoundryUbuntuGuestEnvironmentSpec.ROOT_DIR}"
        listOf(previous, stage, finalRoot).forEach(LocalExecutionSubstrate::requireSafeLocalPath)
        val state = UbuntuGuestRecoveryInspection.bootstrap(File(previous), File(stage), File(finalRoot))
        val previousExists = state.previousExists
        val stageExists = state.stageExists
        val finalExists = state.finalExists

        // Its presence must never authorize deletion/rollback of a current guest that may contain newer
        // user data.

        if (finalExists) {
            if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
            return
        }
        if (previousExists) {
            withContext(NonCancellable) {
                val restore = LocalExecutionSubstrate.shell(
                    "set -eu; mv ${q(previous)} ${q(finalRoot)}; rm -rf ${q(stage)}",
                )
                check(restore.exitCode == 0) { "Could not restore interrupted Ubuntu runtime: ${restore.output.takeLast(4_000)}" }
            }
            return
        }
        if (stageExists) withContext(NonCancellable) { LocalExecutionSubstrate.shell("rm -rf ${q(stage)}") }
    }

    private fun existingGuestPresent(): Boolean {
        val guest = File(finalRoot)
        // Never infer permission to replace a guest from its health.

        return guest.exists() || PathSecurity.isSymbolicLink(guest)
    }

    private suspend fun verifyRemoteArtifact(path: String, spec: TrustedArtifactSpec) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        val size = LocalExecutionSubstrate.shellBounded("toybox stat -c %s ${q(path)}", maxOutputBytes = 8_192)
        check(size.exitCode == 0 && size.output.trim().lineSequence().lastOrNull()?.toLongOrNull() == spec.expectedBytes) {
            "Remote ${spec.id} size does not match pinned metadata"
        }
        val digest = LocalExecutionSubstrate.shellBounded("toybox sha256sum ${q(path)}", maxOutputBytes = 8_192)
        val actual = digest.output.trim().substringBefore(' ').lowercase()
        check(digest.exitCode == 0 && actual == spec.sha256.lowercase()) { "Remote ${spec.id} SHA-256 mismatch" }
    }

    private fun launcherScript(attempt: PackagedNativeAdmissionAttempt? = null, checkCancelled: () -> Unit = {}): String {
        checkCancelled()
        val admitted = attempt?.require(checkCancelled)
        val loaders = admitted?.loaderEnvironment ?: PackagedLinuxEngine.loaderEnvironment(appContext)
        val engine = admitted?.launcher ?: PackagedLinuxEngine.requireReady(appContext)
        checkCancelled()
        return """#!/system/bin/sh
set -eu
SELF="${'$'}0"
BASE="${'$'}{SELF%/*}"
ROOTFS="${'$'}BASE/rootfs"
DROIDE_ROOT="${LocalExecutionSubstrate.localRoot()}"
mkdir -p "${'$'}ROOTFS/opt/droide/packages" "${'$'}ROOTFS/opt/droide/bin" "${'$'}DROIDE_ROOT/packages" "${'$'}DROIDE_ROOT/managed/ubuntu-bin"
WORK="${'$'}{PWD:-${LocalExecutionSubstrate.localRoot()}}"
case "${'$'}WORK" in
  "${LocalExecutionSubstrate.localRoot()}"|"${LocalExecutionSubstrate.localRoot()}"/*) ;;
  *) WORK="${LocalExecutionSubstrate.localRoot()}" ;;
esac
PROOT_NO_SECCOMP=1
PROOT_LOADER=${q(loaders.getValue("PROOT_LOADER"))}
PROOT_LOADER_32=${q(loaders.getValue("PROOT_LOADER_32"))}
PROOT_TMP_DIR=${q(File(root, "runtime-tmp").apply { mkdirs() }.absolutePath)}
test ! -L "${'$'}ROOTFS/.l2s"
mkdir -p "${'$'}ROOTFS/.l2s"
PROOT_L2S_DIR="${'$'}ROOTFS/.l2s"
export PROOT_NO_SECCOMP PROOT_LOADER PROOT_LOADER_32 PROOT_TMP_DIR PROOT_L2S_DIR
exec /system/bin/linker64 ${q(engine.absolutePath)} -0 --link2symlink --kill-on-exit -r "${'$'}ROOTFS" \
  -b /dev -b /proc -b /sys -b "${'$'}DROIDE_ROOT:${'$'}DROIDE_ROOT" \
  -b "${'$'}DROIDE_ROOT/packages:/opt/droide/packages" -b "${'$'}DROIDE_ROOT/managed/ubuntu-bin:/opt/droide/bin" -w "${'$'}WORK" \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root \
  DROIDE_PROCESS_LEASE="${'$'}{DROIDE_PROCESS_LEASE:-}" \
  ${LocalLinuxUserEnvironment.environment.entries.joinToString(" ") { (key, value) -> "$key=$value" }} \
  PATH=${LocalLinuxUserEnvironment.path(listOf("/opt/droide/bin"))} \
  TERM="${'$'}{TERM:-xterm-256color}" LANG=C.UTF-8 LC_ALL=C.UTF-8 DEBIAN_FRONTEND=noninteractive "${'$'}@"
"""
    }

    private fun bootstrapLock(): String = buildString {
        appendLine("schema=1")
        appendLine("environment=${FoundryUbuntuGuestEnvironmentSpec.ID}")
        appendLine("profile=UBUNTU_24_04_GLIBC_ARM64")
        appendLine("profile_revision=${FoundryUbuntuGuestEnvironmentSpec.PROFILE_REVISION}")
        appendLine("baseline_packages=on-demand")
        appendLine("engine_source=APK_NATIVE_LIBRARY")
        appendLine("engine_name=${PackagedLinuxEngine.LIBRARY_NAME}")
        appendLine("rootfs_sha256=${FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.sha256}")
        appendLine("rootfs_bytes=${FoundryUbuntuGuestEnvironmentSpec.rootfsArtifact.expectedBytes}")
        appendLine("rootfs_source=https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS")
    }

    private suspend fun pushText(path: String, text: String, mode: Int) {
        LocalExecutionSubstrate.requireSafeLocalPath(path)
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { LocalExecutionSubstrate.pushStream(it, path, mode = mode) }
    }

    private fun requireArm64() {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) {
            "Foundry Ubuntu developer environment currently requires ARM64"
        }
    }

    private fun q(value: String): String = LocalExecutionSubstrate.shellQuote(value)
}
