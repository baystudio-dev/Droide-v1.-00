package com.baystudio.droide.core

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class UbuntuAptDependencyInstaller(
    private val commands: UbuntuAptCommandRunner,
    private val metadata: UbuntuAptMetadataState,
    private val requireSpace: suspend (Long, String) -> Unit,
    private val layerMarked: (String) -> Boolean,
    private val markLayer: suspend (String, List<String>) -> Unit,
    private val cleanup: (String) -> Unit,
) {
    suspend fun ensure(packages: List<String>, layerId: String, progress: (String) -> Unit, allowSharedRepair: Boolean = true) {
        require(layerId.matches(Regex("[0-9a-f]{24}")))
        var failure: Throwable? = null
        try {
            if (!allowSharedRepair) requireNoPendingConfiguration()
            progress("Checking installed Ubuntu dependencies…\n")
            var missing = missing(packages)
            if (missing.isEmpty()) {
                if (!layerMarked(layerId)) markLayer(layerId, packages)
                progress("Using installed Ubuntu dependencies; no download or reinstall needed.\n")
                return
            }
            if (allowSharedRepair) ensureHealthy(progress, layerId) else requireNoPendingConfiguration()
            missing = missing(packages)
            if (missing.isEmpty()) { markLayer(layerId, packages); progress("Ubuntu dependencies are ready.\n"); return }
            for (attempt in 0..1) {
                ensureIndexes(progress)
                val txRoot = "/var/cache/droide-foundry/$layerId"
                val archives = "$txRoot/archives"
                progress("Planning missing Ubuntu dependencies: ${missing.joinToString()}…\n")
                val plan = commands.run(listOf("sh", "-lc", FoundryUbuntuAptPolicy.planCommand(missing, "$txRoot/plan", archives)), progress = progress)
                requireSuccess(plan, "plan Ubuntu dependencies")
                val downloadBytes = total(plan, "download")
                val installedBytes = Math.multiplyExact(total(plan, "installed_kib"), 1_024L)
                progress("Additional storage planned: ${installedBytes / (1_024L * 1_024L)} MiB installed, ${downloadBytes / (1_024L * 1_024L)} MiB temporary download.\n")
                requireSpace(Math.addExact(Math.addExact(downloadBytes, installedBytes), FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES), "install missing Ubuntu dependencies")
                val args = missing.joinToString(" ")
                progress("Downloading missing Ubuntu packages…\n")
                val download = commands.run(listOf("sh", "-lc", "set -eu; mkdir -p $archives/partial; " +
                    "apt-get ${FoundryUbuntuAptPolicy.OPTIONS} -y --no-install-recommends --download-only -o Dir::Cache::archives=$archives install $args"), timeoutMs = 0L, progress = progress)
                if (attempt == 0 && FoundryUbuntuAptPolicy.staleArchiveIndex(download)) {
                    metadata.invalidate(); cleanup(layerId)
                    progress("Repository archives changed; refreshing signed indexes once…\n")
                    continue
                }
                requireSuccess(download, "download Ubuntu dependencies")
                val exact = commands.run(FoundryUbuntuGuestCommand.downloadedArchiveSizesCommand(archives), maxOutputBytes = 128_000)
                requireSuccess(exact, "measure downloaded Ubuntu dependencies")
                requireSpace(Math.addExact(Math.multiplyExact(total(exact, "installed_kib"), 1_024L), FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES), "activate downloaded Ubuntu dependencies")
                progress("Installing missing Ubuntu dependencies…\n")
                val install = commands.run(listOf("sh", "-lc", "set -eu; apt-get ${FoundryUbuntuAptPolicy.OPTIONS} -y --no-install-recommends --no-download -o Dir::Cache::archives=$archives install $args; " +
                    "apt-mark manual $args >/dev/null"), maxOutputBytes = 512_000, timeoutMs = 0L, progress = progress)
                requireSuccess(install, "install Ubuntu dependencies")
                check(missing(packages).isEmpty()) { "Ubuntu dependencies did not finish configuring; retry after the package operation finishes" }
                markLayer(layerId, packages)
                return
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            // capture() has reaped its owned command before this point.

            withContext(NonCancellable) {
                try { cleanup(layerId) }
                catch (error: Exception) { failure?.addSuppressed(error) ?: throw error }
            }
        }
    }

    private suspend fun missing(packages: List<String>): List<String> {
        val result = commands.run(FoundryUbuntuAptPolicy.installedPackagesCommand(packages), maxOutputBytes = 32_000, timeoutMs = 30_000)
        check(!result.timedOut && result.exitCode in setOf(0, 1)) { "Could not check installed Ubuntu dependencies: ${result.output.takeLast(2_000)}" }
        return packages.filterNot { it in FoundryUbuntuAptPolicy.installedPackages(result, packages) }
    }

    private suspend fun ensureIndexes(progress: (String) -> Unit) {
        if (metadata.isFresh()) { progress("Using cached signed Ubuntu package indexes…\n"); return }
        progress("Refreshing signed Ubuntu package indexes…\n")
        val update = commands.run(listOf("sh", "-lc", "apt-get ${FoundryUbuntuAptPolicy.OPTIONS} update"), timeoutMs = 0L, progress = progress)
        requireSuccess(update, "refresh signed Ubuntu indexes")
        metadata.refreshed()
    }

    internal suspend fun requireNoPendingConfiguration() {
        val audit = commands.run(listOf("dpkg", "--audit"), maxOutputBytes = 64_000, timeoutMs = 30_000)
        requireSuccess(audit, "inspect Ubuntu package state")
        check(audit.output.isBlank()) { "Managed Ubuntu has pending package configuration; complete its explicit repair before installing PowerShell" }
    }

    suspend fun ensureHealthy(progress: (String) -> Unit, layerId: String) {
        val audit = commands.run(listOf("dpkg", "--audit"), maxOutputBytes = 64_000, timeoutMs = 30_000)
        requireSuccess(audit, "inspect Ubuntu package state")
        if (audit.output.isBlank()) return
        progress("Finishing pending Ubuntu package configuration…\n")
        var configured = commands.run(listOf("dpkg", "--configure", "-a"), timeoutMs = 0L, progress = progress)
        if (configured.timedOut) requireSuccess(configured, "finish Ubuntu package configuration")
        if (configured.exitCode != 0) {
            ensureIndexes(progress)
            val archives = "/var/cache/droide-foundry/$layerId/archives"
            val plan = commands.run(listOf("sh", "-lc", FoundryUbuntuAptPolicy.repairPlanCommand(
                "/var/cache/droide-foundry/$layerId/repair-plan", archives)), progress = progress)
            requireSuccess(plan, "plan pending Ubuntu dependency repair")
            requireSpace(Math.addExact(Math.addExact(total(plan, "download"), Math.multiplyExact(total(plan, "installed_kib"), 1_024L)),
                FoundryUbuntuGuestEnvironmentSpec.LAYER_TRANSACTION_OVERHEAD_BYTES), "repair pending Ubuntu dependencies")
            val repaired = commands.run(listOf("sh", "-lc", "set -eu; mkdir -p $archives/partial; apt-get ${FoundryUbuntuAptPolicy.OPTIONS} " +
                "-o Dir::Cache::archives=$archives -f install -y --no-install-recommends"), maxOutputBytes = 512_000, timeoutMs = 0L, progress = progress)
            requireSuccess(repaired, "repair pending Ubuntu dependencies")
            configured = commands.run(listOf("dpkg", "--configure", "-a"), timeoutMs = 0L, progress = progress)
        }
        requireSuccess(configured, "finish Ubuntu package configuration")
        val checked = commands.run(listOf("dpkg", "--audit"), maxOutputBytes = 64_000, timeoutMs = 30_000)
        requireSuccess(checked, "verify Ubuntu package configuration")
        check(checked.output.isBlank()) { "Ubuntu has unfinished package configuration: ${checked.output.takeLast(2_000)}" }
    }

    private fun requireSuccess(result: ExecResult, purpose: String) {
        check(result.exitCode == 0 && !result.timedOut) { "Could not $purpose: ${result.output.takeLast(4_000)}" }
    }
    private fun total(result: ExecResult, key: String): Long = result.output.lineSequence()
        .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        ?: error("Ubuntu package plan did not report $key")
}
