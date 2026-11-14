package com.baystudio.droide.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/** Reads source only. Never constructs an Android, shell, process, VM, transfer or network owner. */
class LegacyAlpineRetirementWiringTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(10)

    private fun source(name: String): String {
        val suffix = "src/main/java/com/baystudio/droide/core/$name.kt"
        val file = listOf(File(suffix), File("app/$suffix"), File("../app/$suffix")).firstOrNull { it.isFile }
        return requireNotNull(file) { "Missing source fixture $name" }.also {
            require(it.length() in 1..512_000) { "Source fixture exceeds bound" }
        }.readText()
    }

    private fun between(text: String, start: String, end: String): String {
        val first = text.indexOf(start)
        val last = text.indexOf(end, first + start.length)
        assertTrue("Missing bounded section: $start", first >= 0 && last > first)
        return text.substring(first, last)
    }

    private fun rejects(body: String) {
        assertTrue(body.contains("= LegacyAlpineRuntimeRetirement.reject()"))
        listOf("withContext", "withLock", "shell", "ensureInstalled(", "requireReady(", "mkdir", "evict(",
            "ProcessBuilder", "SafeHttp", "journal.", "withArtifact", "callback(").forEach {
            assertFalse("Retired body reaches $it", body.contains(it))
        }
    }

    @Test fun environmentAndBundleAreFailBeforeEffectsTombstones() {
        val text = source("WorkstationGuestPackageInstaller")
        val manager = between(text, "class WorkstationGuestEnvironmentManager(", "data class WorkstationGuestAdmissionProbe(")
        listOf("suspend fun ensureQemuInstalled(): String", "suspend fun ensureInstalled(): String",
            "internal suspend fun ensureInstalledUnlocked(): String", "suspend fun isHealthy(): Boolean").forEach {
            assertTrue(manager.contains("$it = LegacyAlpineRuntimeRetirement.reject()"))
        }
        assertTrue(manager.contains("maxOutputBytes: Int = 1_500_000): ExecResult = LegacyAlpineRuntimeRetirement.reject()"))
        assertTrue(manager.contains("timeoutMs: Long = 900_000): ExecResult = LegacyAlpineRuntimeRetirement.reject()"))
        listOf("BundledRootfsExtractor", "repairExistingLauncher", "reconcileInterruptedBootstrap", "LocalExecutionSubstrate.shell",
            "PackagedWorkstationRootfs", "launcherScript", "mkdir", "FileOutputStream").forEach { assertFalse(manager.contains(it)) }
        val bundle = source("BundledQemuGuestRuntime")
        assertTrue(bundle.contains("suspend fun ensureInstalled(): String = LegacyAlpineRuntimeRetirement.reject()"))
        listOf("obtainPinned", "assets.open", "apk --no-network", "cleanup(", "evict(", "delete(", "mkdir").forEach {
            assertFalse(bundle.contains(it))
        }
    }

    @Test fun packageInstallAndRepairRejectAtThePublicBoundary() {
        val text = source("WorkstationGuestPackageInstaller").substringAfter("class WorkstationGuestPackageInstaller(")
        rejects(between(text, "suspend fun install(", "suspend fun uninstall("))
        rejects(between(text, "suspend fun uninstall(", "suspend fun repair("))
        rejects(between(text, "suspend fun repair(", "suspend fun reconcilePendingGuestCleanup("))
        val managed = source("ManagedExtensionInstaller")
        val repair = between(managed, "suspend fun repair(item:", "suspend fun uninstall(item:")
        val gate = repair.indexOf("if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) LegacyAlpineRuntimeRetirement.reject()")
        assertTrue(gate >= 0 && gate < repair.indexOf("else operation(") && gate < repair.indexOf("registry.find("))
        val uninstall = between(managed, "suspend fun uninstall(item:", "private suspend fun cleanupOrphanLocalUbuntuDependencies(")
        val uninstallGate = uninstall.indexOf("if (item.version.installKind == ExtensionInstallKind.GUEST_PACKAGE) LegacyAlpineRuntimeRetirement.reject()")
        assertTrue(uninstallGate >= 0 && uninstallGate < uninstall.indexOf("else operation(") && uninstallGate < uninstall.indexOf("registry.find("))
        val install = between(managed, "suspend fun install(plan:", "private suspend fun installAndroid(")
        val installGate = install.indexOf("if (plan is ExtensionInstallPlan.GuestPackage) LegacyAlpineRuntimeRetirement.reject()")
        assertTrue(installGate >= 0 && installGate < install.indexOf("else operation("))
        assertTrue(install.indexOf("UbuntuExtensionPlanPolicy.requireSupported(plan, localReviewed)") < install.indexOf("longOperationJournal.begin("))
    }

    @Test fun substrateAndVmEntryPointsCannotPrepareOrStartRetiredRuntime() {
        val substrate = source("LocalExecutionSubstrate")
        val launch = between(substrate, "private fun buildLinuxLaunchSpec(", "suspend fun ensureLinuxReady(")
        val gate = launch.indexOf("if (alpine) LegacyAlpineRuntimeRetirement.reject()")
        assertTrue(gate >= 0 && gate < launch.indexOf("checkCancelled()") && gate < launch.indexOf("mkdir"))
        rejects(between(substrate, "suspend fun ensureQemuReady(", "suspend fun shellBounded("))
        val vm = source("QemuVmRuntime")
        rejects(between(vm, "suspend fun start(profile:", "suspend fun awaitBootHealth("))
        val constructor = between(vm, "private val appContext =", "fun imagePath(")
        assertFalse(constructor.contains("mkdir"))
        assertFalse(constructor.contains("LongRunningOperationJournal("))
        assertFalse(constructor.contains("WorkstationGuestEnvironmentManager("))
        assertTrue(vm.contains("internal fun interruptedSessions(): List<LongRunningOperationJournal.Record> = LegacyAlpineRuntimeRetirement.reject()"))
        val provider = source("AlpineQemuVmImageProvider")
        rejects(between(provider, "suspend fun <T> withResolvedImage(", "companion object"))
        listOf("SafeHttp", "UserInitiatedArtifactTransfer", "fetchSha512(", "withArtifact(", "mkdir").forEach { assertFalse(provider.contains(it)) }
        val defaultImage = between(vm, "suspend fun installDefaultAlpineImage(", "suspend fun importVerifiedBaseImage(")
        assertTrue(defaultImage.contains("imageProvider.withResolvedImage(onProgress)"))
    }

    @Test fun cleanupIsDiagnosticOnlyAndNeverDeletesOrExecutes() {
        val text = source("WorkstationGuestPackageInstaller")
        val cleanup = text.substringAfter("suspend fun reconcilePendingGuestCleanup()")
        assertTrue(cleanup.contains("LegacyAlpineRuntimeRetirement.pendingCleanupWarning"))
        listOf("environment.", "readCleanupIntent", "delete(", "registryHas(", "completeCleanupIntent(").forEach { assertFalse(cleanup.contains(it)) }
        assertFalse(text.contains("completeCleanupIntent("))
        assertFalse(text.contains("writeCleanupIntent("))
        val policy = source("LegacyAlpineRuntimeRetirement")
        listOf("ProcessBuilder", "LocalExecutionSubstrate", "SafeHttp", "readText(", "readLines(", "delete(", "mkdir", "renameTo(", "writeText(").forEach {
            assertFalse("Diagnostic inspection must not use $it", policy.contains(it))
        }
        assertTrue(policy.contains("LinkOption.NOFOLLOW_LINKS"))
        assertTrue(policy.contains("NoSuchFileException"))
    }

    @Test fun legacyWarningDoesNotBecomeAnUbuntuRecoveryFailure() {
        val installer = between(source("ManagedExtensionInstaller"), "suspend fun reconcileLocalPackages()", "suspend fun verifyManagedRecord(")
        assertTrue(installer.indexOf("localPackageAuthority.reconcile()") < installer.indexOf("guestPackageInstaller.reconcilePendingGuestCleanup()"))
        assertTrue(installer.contains("val legacyRecoveryPending = localPackageAuthority.reconcile()"))
        assertTrue(installer.contains("return if (legacyRecoveryPending) LegacyAlpineRuntimeRetirement.PENDING_MESSAGE else legacyCleanupWarning"))
        assertFalse(installer.contains("runCatching"))
        val refresh = source("DevelopmentExtensionsManager")
        assertTrue(refresh.contains("val localRecovery = runSuspendCatching { installer.reconcileLocalPackages() }"))
        assertTrue(refresh.contains("val localRecoveryProblem = localRecovery.exceptionOrNull()"))
        assertTrue(refresh.contains("val legacyCleanupWarning = localRecovery.getOrNull()"))
        assertTrue(refresh.contains("records, localRecoveryProblem, installer.localPackageAuthority.appRoot"))
        assertTrue(refresh.contains("listOfNotNull(status, legacyCleanupWarning).joinToString(\" · \")"))
    }
}
