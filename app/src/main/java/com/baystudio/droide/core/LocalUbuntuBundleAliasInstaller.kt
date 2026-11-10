package com.baystudio.droide.core

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

internal class LocalUbuntuBundleAliasInstaller(
    context: Context,
    private val authority: LocalManagedPackageAuthority,
    private val ubuntu: FoundryUbuntuGuestEnvironmentManager,
) {
    private val root = context.applicationContext.filesDir.canonicalPath

    suspend fun install(
        recipe: LocalUbuntuBundleAliasPolicy.Recipe,
        primary: ManagedPackageRecord,
        progress: (String) -> Unit = {},
    ): ManagedPackageRecord = withContext(Dispatchers.IO) {
        require(LocalUbuntuBundleAliasPolicy.find(recipe.familyId) == recipe) { "Unreviewed SDK bundle alias" }
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "aarch64" }) { "Ubuntu SDK aliases require ARM64 Android" }
        PackageBackendContract.requireSame("SDK bundle alias", PackageBackendId.LOCAL_APP, authority.backendId, ubuntu.backendId)
        require(ubuntu.launcherPath() == LocalUbuntuBundleAliasPolicy.launcherPath(root)) { "SDK alias launcher belongs to another guest" }
        LocalPackageInstallJournal.transactionMutex.withLock {
            val current = authority.ownedReceipt(primary.familyId, primary.version) ?: error("Primary SDK receipt is missing")
            val plan = LocalUbuntuBundleAliasPolicy.plan(recipe.familyId, current, root)
            check(authority.verify(current)) { "Primary SDK failed integrity or command health verification" }
            val paths = LocalPackageInstallJournal.paths(root, recipe.familyId, plan.version, LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS)
            val previous = authority.recoveryReceipt(recipe.familyId, plan.version, LocalManagedPackageMetadata.KIND_UBUNTU_BUNDLE_ALIAS)
            LocalPackageInstallJournal.recover(paths, previous?.let {
                LocalPackageInstallJournal.Receipt(it.metadata[LocalManagedPackageMetadata.ACTIVATION_ID_KEY])
            })
            check(File(paths.stage, "payload/bin").mkdirs())
            check(File(paths.stage, "payload/host-bin").mkdirs())
            var activation: LocalPackageInstallJournal.Activation? = null
            try {
                val id = LocalPackageInstallJournal.prepare(paths.stage)
                val payload = LocalUbuntuBundleAliasPolicy.files(plan, paths.final.absolutePath, root)
                payload.forEach { (relative, text) ->
                    currentCoroutineContext().ensureActive()
                    File(paths.stage, relative).apply {
                        writeText(text, Charsets.UTF_8)
                        if (relative.startsWith("payload/")) check(setExecutable(true, true))
                    }
                }
                plan.recipe.commands.forEach { (name, command) ->
                    progress("Checking bundled $name from ${plan.primaryKey}…\n")
                    val target = "${plan.primaryGuestRoot}/payload/bin/${command.primaryCommand}"
                    val health = LocalExecutionSubstrate.shellBounded(
                        "exec /system/bin/sh " + LocalExecutionSubstrate.shellQuote(ubuntu.launcherPath()) + " " +
                            (listOf(target) + command.healthFixedArgs + command.healthArgs).joinToString(" ", transform = LocalExecutionSubstrate::shellQuote),
                        64_000, timeoutMs = 60_000)
                    check(!health.timedOut && health.exitCode == 0) { "Bundled $name failed its health check: ${health.output.takeLast(2_000)}" }
                }
                val manifest = File(paths.stage, "SHA256SUMS")
                manifest.writeText(LocalUbuntuBundleAliasPolicy.manifest(paths.stage, payload.keys + "DROIDE_ACTIVATION_ID"))
                val seal = LocalUbuntuBundleAliasPolicy.sha256(manifest)
                activation = LocalPackageInstallJournal.activate(paths.stage, paths.final, paths.previous)
                val record = ManagedPackageRecord(
                    familyId = recipe.familyId, version = plan.version, scope = ExecutionScope.LOCAL_LINUX_ARM64.name,
                    installRoot = paths.final.absolutePath, installedAtEpochMs = System.currentTimeMillis(),
                    pathEntries = listOf("${paths.final}/payload/host-bin"),
                    commands = recipe.commands.keys.associateWith { "${paths.final}/payload/host-bin/$it" },
                    dependencies = listOf(plan.primaryKey), healthChecks = plan.healthChecks(),
                    artifactSha256 = seal, abi = "arm64-v8a", metadata = LocalUbuntuBundleAliasPolicy.metadata(plan, id, seal),
                )
                authority.adopt(record)
                activation = null
                withContext(NonCancellable) { LocalPackageInstallJournal.commit(paths.previous) }
                record
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    activation?.let { runCatching { LocalPackageInstallJournal.rollback(paths.final, paths.previous, it) }.exceptionOrNull()?.let(failure::addSuppressed) }
                    if (!PathSecurity.deleteTreeNoFollow(paths.stage)) failure.addSuppressed(IllegalStateException("SDK alias staging cleanup failed"))
                }
                throw failure
            }
        }
    }
}
