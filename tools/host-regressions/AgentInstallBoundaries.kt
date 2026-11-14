package com.baystudio.droide.core

import java.io.File

// The verifier extracts package/bridge data contracts from production source separately.
class DeviceBridgeManager {
    suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("ADB is outside the local fixture")
    suspend fun shell(command: String): BridgeShellResult = error("ADB is outside the local fixture")
    suspend fun pull(path: String, output: File): Unit = error("ADB is outside the local fixture")
    suspend fun push(input: File, path: String, mode: Int): Unit = error("ADB is outside the local fixture")
    companion object {
        fun requireSafeRemotePath(path: String) { require(path.startsWith('/')) }
        fun shellQuote(value: String) = LocalExecutionSubstrate.shellQuote(value)
    }
}

internal class LocalManagedPackageAuthority {
    val backendId = PackageBackendId.LOCAL_APP
    var adopted: ManagedPackageRecord? = null
    var failAdoption = false
    var failFamily: String? = null
    val receipts = linkedMapOf<String, ManagedPackageRecord>()
    var verifyHandler: (suspend (ManagedPackageRecord) -> Boolean)? = null
    fun owns(record: ManagedPackageRecord) = true
    suspend fun verify(record: ManagedPackageRecord) = verifyHandler?.invoke(record) ?: true
    fun ownedReceipt(family: String, version: String) = receipts["$family@$version"] ?: adopted?.takeIf { it.familyId == family && it.version == version }
    suspend fun adopt(record: ManagedPackageRecord) { check(!failAdoption && failFamily != record.familyId) { "Fixture receipt write failure" }; adopted = record; receipts["${record.familyId}@${record.version}"] = record }
    suspend fun activate(record: ManagedPackageRecord) { receipts["${record.familyId}@${record.version}"] = record.copy(active = true) }
}

internal class FoundryUbuntuGuestEnvironmentManager {
    val backendId = PackageBackendId.LOCAL_APP
    var ensureCount = 0
    suspend fun ensureInstalled(): String { ensureCount++; return "Fixture guest" }
    fun launcherPath() = "/fixture/ubuntu-launcher"
    var packages: List<String> = emptyList()
    var healthResult = ExecResult(0, "fixture version", false)
    val executedCommands = mutableListOf<List<String>>()
    var executeHandler: (suspend () -> Unit)? = null
    suspend fun ensurePackages(items: List<String>, progress: (String) -> Unit = {}): ManagedPackageRecord? {
        packages = items; progress("Fixture APT packages\n")
        return ManagedPackageRecord("runtime.ubuntu-deps.fixture", "24.04.5.r2", ExecutionScope.LOCAL_LINUX_ARM64.name,
            "/fixture/shared-apt", System.currentTimeMillis())
    }
    suspend fun execute(argv: List<String>, maxOutputBytes: Int): ExecResult { executedCommands.add(argv); executeHandler?.invoke(); return healthResult }
    val healthTimeouts = mutableListOf<Long>()
    suspend fun verifyCommands(commands: Map<String, List<String>>, progress: (String) -> Unit = {}) {
        UbuntuCommandHealthChecks.verify(commands, { ensureInstalled() }, { argv, limit, timeout ->
            healthTimeouts.add(timeout); execute(argv, limit)
        }, progress)
    }
}

internal data class SafeHttpResponse(val code: Int, val body: String, val finalUrl: String)
internal object SafeHttp {
    var response: SafeHttpResponse? = null
    suspend fun get(rawUrl: String, accept: String, maxBytes: Int): SafeHttpResponse = response ?: error("Fixture metadata not configured")
}
internal object LocalUbuntuJdkPolicy { const val FAMILY = "toolchain.jdk" }
