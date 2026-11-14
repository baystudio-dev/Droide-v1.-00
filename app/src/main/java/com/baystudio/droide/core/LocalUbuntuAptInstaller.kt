package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

internal class LocalUbuntuAptInstaller(
    context: Context,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val root = context.applicationContext.filesDir.canonicalPath
    suspend fun install(recipe: UbuntuAptRecipe, progress: (String) -> Unit = {}): ManagedPackageRecord = withContext(Dispatchers.IO) {
        recipe.validate()
        require(UbuntuAptCatalog.find(recipe.familyId) == recipe) { "Unreviewed Ubuntu package recipe" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Ubuntu installers require ARM64 Android" }
        PackageBackendContract.requireSame("Local Ubuntu APT recipe", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        progress("Checking shared Ubuntu dependencies…\n")
        val layer = requireNotNull(ubuntu.ensurePackages(recipe.packages, progress))
        authority.adopt(layer)
        val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_APT)
        val installed = LocalPackageInstallJournal.transactionMutex.withLock {
            val previous = authority.recoveryReceipt(recipe.familyId, recipe.version, LocalManagedPackageMetadata.KIND_UBUNTU_APT)
            LocalPackageInstallJournal.recover(paths,
                previous?.let { LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY]) })
            LocalUbuntuAptIntegration.reuse(previous, recipe, "${layer.familyId}@${layer.version}",
                ubuntu.launcherPath(), authority::owns, authority::verify)?.let { reusable ->
                progress("Using verified installed ${recipe.familyId}; no command integration rebuild needed.\n")
                return@withLock reusable
            }
            check(File(paths.stage, "payload/bin").mkdirs())
            check(File(paths.stage, "payload/host-bin").mkdirs())
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val id = LocalPackageInstallJournal.prepare(paths.stage)
                val checks = recipe.commands.keys.map { ManagedPackageHealthCheck("host-bin/$it", recipe.probeArgs(it)) }
                ubuntu.verifyCommands(recipe.commands.mapValues { (name, guestCommand) ->
                    listOf(guestCommand) + recipe.launchArgs(name) + recipe.probeArgs(name)
                }, progress)
                recipe.commands.keys.forEach { name ->
                    File(paths.stage, "payload/bin/$name").apply {
                        writeText(LocalUbuntuAptIntegration.guestWrapper(recipe, name)); check(setExecutable(true, true))
                    }
                    File(paths.stage, "payload/host-bin/$name").apply {
                        writeText(LocalUbuntuAptIntegration.hostWrapper(recipe, name, ubuntu.launcherPath()))
                        check(setExecutable(true, true))
                    }
                }
                File(paths.stage, "APT_PACKAGES.txt").writeText(recipe.packages.sorted().joinToString("\n", postfix = "\n"))
                val payload = paths.stage.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                val manifest = File(paths.stage, "SHA256SUMS")
                val rows = ArrayList<String>()
                for (file in payload) rows += "${LocalUbuntuNpmPolicy.sha256(file)}  ${file.relativeTo(paths.stage).invariantSeparatorsPath}"
                manifest.writeText(rows.joinToString("\n", postfix = "\n"))
                val seal = LocalUbuntuNpmPolicy.sha256(manifest)
                progress("Activating verified command integration…\n")
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId, version = recipe.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name, active = true,
                    installRoot = paths.final.absolutePath, installedAtEpochMs = System.currentTimeMillis(),
                    pathEntries = listOf("${paths.final}/payload/host-bin"),
                    commands = recipe.commands.keys.associateWith { "${paths.final}/payload/host-bin/$it" },
                    dependencies = listOf("${layer.familyId}@${layer.version}"), healthChecks = checks,
                    artifactSha256 = seal, abi = "arm64-v8a", metadata = mapOf(
                        PackageBackendContract.METADATA_KEY to PackageBackendId.LOCAL_APP.name,
                        LocalManagedPackageMetadata.ACTIVATION_ID_KEY to id,
                        LocalManagedPackageMetadata.KIND_KEY to LocalManagedPackageMetadata.KIND_UBUNTU_APT,
                        LocalManagedPackageMetadata.PROFILE_KEY to DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name,
                        LocalManagedPackageMetadata.SEAL_SHA256_KEY to seal,
                    ),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, it) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("APT integration staging cleanup failed"))
                }
                // Distro packages are shared with the terminal; cancellation/removal never purges them.
                throw failure
            }
        }
        // Activation owns the same journal mutex; do it after releasing this transaction.
        if (!installed.active) authority.activate(installed)
        installed.copy(active = true)
    }
}

/** Current recipe identity plus the authority's fresh integrity and runtime health verification. */
internal object LocalUbuntuAptIntegration {
    fun guestWrapper(recipe: UbuntuAptRecipe, name: String): String =
        "#!/bin/sh\nset -eu\nexec ${command(recipe, name)} \"${'$'}@\"\n"

    fun hostWrapper(recipe: UbuntuAptRecipe, name: String, launcher: String): String =
        "#!/system/bin/sh\nset -eu\nexec /system/bin/sh ${LocalExecutionSubstrate.shellQuote(launcher)} ${command(recipe, name)} \"${'$'}@\"\n"

    suspend fun reuse(record: ManagedPackageRecord?, recipe: UbuntuAptRecipe, layerKey: String, launcher: String,
        owns: (ManagedPackageRecord) -> Boolean, verify: suspend (ManagedPackageRecord) -> Boolean,
    ): ManagedPackageRecord? {
        currentCoroutineContext().ensureActive()
        if (record == null || !owns(record) || record.familyId != recipe.familyId || record.version != recipe.version ||
            record.scope != ExecutionScope.LOCAL_LINUX_ARM64.name || record.abi != "arm64-v8a" ||
            record.metadata[LocalManagedPackageMetadata.KIND_KEY] != LocalManagedPackageMetadata.KIND_UBUNTU_APT ||
            record.metadata[LocalManagedPackageMetadata.PROFILE_KEY] != DeclarativeGuestProfile.UBUNTU_24_04_GLIBC_ARM64.name ||
            record.dependencies != listOf(layerKey) ||
            record.commands != recipe.commands.keys.associateWith { "${record.installRoot}/payload/host-bin/$it" } ||
            record.pathEntries != listOf("${record.installRoot}/payload/host-bin") ||
            record.healthChecks != recipe.commands.keys.map { ManagedPackageHealthCheck("host-bin/$it", recipe.probeArgs(it)) }) return null
        val seal = record.artifactSha256 ?: return null
        if (!seal.matches(Regex("[0-9a-f]{64}")) || record.metadata[LocalManagedPackageMetadata.SEAL_SHA256_KEY] != seal) return null
        val root = File(record.installRoot)
        if (root.canonicalPath != root.absolutePath) return null
        for (name in recipe.commands.keys) {
            if (!matchesFile(File(root, "payload/bin/$name"), guestWrapper(recipe, name)) ||
                !matchesFile(File(root, "payload/host-bin/$name"), hostWrapper(recipe, name, launcher))) return null
        }
        // No cached success here: dpkg changes, modified payloads or a failing executable are
        // still detected. Cancellation propagates before any new staging/activation is started.
        return record.takeIf { verify(it) }
    }

    private fun command(recipe: UbuntuAptRecipe, name: String): String =
        (listOf(recipe.commands.getValue(name)) + recipe.launchArgs(name)).joinToString(" ", transform = LocalExecutionSubstrate::shellQuote)

    private fun matchesFile(file: File, expected: String): Boolean = file.isFile && !PathSecurity.isSymbolicLink(file) &&
        file.canonicalPath == file.absolutePath && file.length() == expected.toByteArray(Charsets.UTF_8).size.toLong() && file.readText() == expected
}
