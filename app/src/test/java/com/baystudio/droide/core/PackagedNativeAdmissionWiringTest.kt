package com.baystudio.droide.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source call-site checks only; never instantiate the process/socket/Android owners inspected. */
class PackagedNativeAdmissionWiringTest {
    private fun source(name: String): String {
        val suffix = "src/main/java/com/baystudio/droide/core/$name.kt"
        val file = listOf(File(suffix), File("app/$suffix"), File("../app/$suffix")).firstOrNull { it.isFile }
        return requireNotNull(file) { "Missing source fixture $name" }.readText()
    }

    private fun between(text: String, start: String, end: String): String {
        val first = text.indexOf(start)
        val last = text.indexOf(end, first + start.length)
        assertTrue("Missing bounded source section: $start", first >= 0 && last > first)
        return text.substring(first, last)
    }

    @Test fun terminalAndStdioUseOneScopedPreparation() {
        val substrate = source("LocalExecutionSubstrate")
        val prepare = between(substrate, "internal suspend fun prepareUbuntuLaunchSpec", "internal suspend fun ensureUbuntuInstalled()")
        assertEquals(1, Regex("PackagedNativeAdmissionAttempt.create").findAll(prepare).count())
        assertTrue(prepare.contains("val preparationContext = currentCoroutineContext()"))
        assertTrue(prepare.contains("ensureUbuntuInstalled(attempt, checkpoint)"))
        val launch = between(prepare, "buildLinuxLaunchSpec(", ".also { checkpoint() }")
        assertEquals(1, Regex("buildLinuxLaunchSpec\\(").findAll(prepare).count())
        assertTrue(launch.contains("workDir, false, workspaceRoot, toolchainWorkspaceRoot, attempt,"))
        assertTrue(launch.contains("{ checkpoint(); graphqlDeadline?.check() }"))
        assertTrue(prepare.contains("preparationContext.ensureActive(); graphqlDeadline?.check()"))
        assertTrue(prepare.contains("finally {\n            attempt.close()"))
        val ensure = between(substrate, "suspend fun ensureLinuxReady", "internal suspend fun prepareUbuntuLaunchSpec")
        assertTrue(ensure.contains("prepareUbuntuLaunchSpec(workDir)"))
        val host = source("LocalLinuxProcessHost")
        val functions = Regex("(?m)^    override suspend fun (\\w+)\\(").findAll(host).toList()
        val preparations = functions.mapIndexedNotNull { index, function ->
            val body = host.substring(function.range.first, functions.getOrNull(index + 1)?.range?.first ?: host.length)
            val calls = Regex("LocalExecutionSubstrate\\.prepareUbuntuLaunchSpec\\(").findAll(body).count()
            if (calls == 0) null else function.groupValues[1] to calls
        }.toMap()
        assertEquals(setOf("resolveExecutable", "start", "startReviewedSqruffInWorkspace",
            "startReviewedSvelteInWorkspace", "startReviewedPowerShellInWorkspace"), preparations.keys)
        preparations.forEach { (name, count) -> assertEquals("$name must prepare once", 1, count) }
        assertFalse(host.contains("LocalExecutionSubstrate.ensureUbuntuInstalled()"))
        assertFalse(host.contains("LocalExecutionSubstrate.linuxLaunchSpec("))
        assertTrue(source("TerminalManager").contains("LocalExecutionSubstrate.ensureLinuxReady(defaultDir)"))
        val foundry = source("FoundryUbuntuGuestEnvironment")
        val attach = between(foundry, "private suspend fun ensureInstalledWithAdmission", "private suspend fun ensureInstalledUnlocked")
        assertTrue(attach.indexOf("attempt?.require(checkCancelled)") < attach.indexOf("packageAttachAdmission.mayAttach()"))
        assertTrue(attach.contains("ensureInstalledUnlocked(attempt, checkCancelled)"))
        val script = between(foundry, "private fun launcherScript", "private fun bootstrapLock")
        assertEquals(1, Regex("attempt\\?\\.require\\(checkCancelled\\)").findAll(script).count())
        assertTrue(script.contains("admitted?.loaderEnvironment"))
        assertTrue(script.contains("admitted?.launcher"))
        val owner = source("PackagedNativeAdmissionAttempt")
        assertTrue(owner.contains("PackagedLinuxEngine.requireFresh(app, checkpoint)"))
        assertTrue(owner.contains("DroideCliClientInstaller.ensureInstalled(app, checkpoint)"))
        val fresh = between(source("PackagedLinuxEngine"), "internal fun requireFresh", "private fun checkedLauncher")
        assertFalse(fresh.contains("admissions.require"))
        assertFalse(source("WorkspaceCoordinator").contains("PackagedNativeAdmissionAttempt"))
        assertFalse(source("TerminalManager").contains("PackagedNativeAdmissionAttempt"))
    }

    @Test fun cliUsesItsOwnCancellationContextAndFatalAbsenceIsNotHidden() {
        val cli = between(source("DroideCliServer"), "fun start()", "private suspend fun serve")
        assertTrue(cli.contains("val startContext = currentCoroutineContext()"))
        assertTrue(cli.contains("DroideCliClientInstaller.ensureInstalled(appContext) { startContext.ensureActive() }"))
        assertTrue(cli.indexOf("startContext.ensureActive()") < cli.indexOf("LocalServerSocket(socketName)"))
        assertTrue(cli.contains("catch (cancelled: CancellationException)"))
        assertFalse(cli.contains("PackagedNativeAdmissionAttempt"))
        val engine = source("PackagedLinuxEngine").substringAfter("internal fun unavailableReason")
        assertTrue(engine.contains("catch (cancelled: CancellationException)"))
        assertTrue(engine.contains("throw cancelled"))
        assertTrue(engine.contains("catch (failure: Exception)"))
        assertFalse(engine.contains("catch (failure: Throwable)"))
        assertFalse(engine.contains("runCatching"))
    }

    @Test fun startupUsesCurrentContextAfterLiveGuardAndRecoveryGatesRemain() {
        val coordinator = source("WorkspaceCoordinator")
        val init = between(coordinator, "suspend fun init(requestedMode:", "suspend fun switchProject(id:")
        val guard = init.indexOf("if (_runtime.value != null) return@withLock")
        val failureOnly = init.indexOf("check(requestedMode == WorkspaceStartupMode.NORMAL || _startup.value.phase == WorkspaceStartupPhase.FAILED)")
        val mode = init.indexOf("startupMode = requestedMode")
        val inspection = init.indexOf("LocalExecutionSubstrate.init(context.applicationContext) { inspectionContext.ensureActive() }")
        val recovery = init.indexOf("if (builtinsOnly) {")
        assertTrue(guard >= 0 && failureOnly > guard && mode > failureOnly && inspection > mode && recovery > inspection)
        val optionalInspection = between(init, "        try {", "            if (builtinsOnly) {")
        assertTrue(optionalInspection.contains("withContext(Dispatchers.IO) {"))
        assertTrue(optionalInspection.contains("val inspectionContext = kotlinx.coroutines.currentCoroutineContext()"))
        assertTrue(optionalInspection.contains("LocalExecutionSubstrate.init(context.applicationContext) { inspectionContext.ensureActive() }"))
        assertFalse(optionalInspection.contains("PackagedNativeAdmissionAttempt"))
        assertFalse(optionalInspection.contains("ensureLinuxReady"))
        val registryGate = between(init, "if (builtinsOnly) {", "stage(WorkspaceStartupPhase.PROJECT_CATALOG")
        assertTrue(registryGate.contains("DeclarativeExtensionRuntime.suspendInstalledProjections()"))
        assertTrue(registryGate.contains("} else stage(WorkspaceStartupPhase.RUNTIME_REGISTRIES"))
        assertTrue(registryGate.indexOf("} else stage(") < registryGate.indexOf("processReconciliation()"))
        assertTrue(coordinator.contains("installedComponentsEnabled = !builtinsOnly"))
        assertTrue(coordinator.contains("if (!builtinsOnly) initialUbuntuPreparation(runtime.terminals)"))
        val activation = between(source("DevelopmentExtensionsManager"), "internal fun activateCli()", "private val initialWorkspace")
        val closedGate = activation.indexOf("check(!closed)")
        val installedGate = activation.indexOf("if (installedComponentsEnabled && !cliActivated)")
        val start = activation.indexOf("startCli(context.applicationContext, ownedAuthority, workspaceToolchains.workspaceId)")
        val activated = activation.indexOf("cliActivated = true")
        assertTrue(closedGate >= 0 && installedGate > closedGate && start > installedGate && activated > start)
        assertEquals(1, Regex("startCli\\(").findAll(activation).count())
    }

    @Test fun standaloneDefaultsRemainSeparate() {
        val substrate = source("LocalExecutionSubstrate")
        assertTrue(substrate.contains("buildLinuxLaunchSpec(workDir, alpine, workspaceRoot, toolchainWorkspaceRoot, null, {})"))
        val qemu = between(substrate, "suspend fun ensureQemuReady", "suspend fun shellBounded")
        assertTrue(qemu.contains("= LegacyAlpineRuntimeRetirement.reject()"))
        assertFalse(qemu.contains("linuxLaunchSpec(workDir, alpine = true)"))
        assertFalse(qemu.contains("PackagedNativeAdmissionAttempt"))
        val foundry = source("FoundryUbuntuGuestEnvironment")
        assertTrue(foundry.contains("override suspend fun ensureInstalled(): String = ensureInstalledWithAdmission(null, {})"))
        assertTrue(foundry.contains("readinessSeal(admittedEngine)"))
        assertTrue(source("PackagedLinuxEngine").contains("fun requireReady(context: Context): File = requireReady(context, {})"))
        assertTrue(source("DroideCliClientInstaller").contains("fun ensureInstalled(context: Context): File = ensureInstalled(context, {})"))
        assertFalse(source("WorkstationGuestPackageInstaller").contains("PackagedNativeAdmissionAttempt"))
        assertFalse(source("BundledQemuGuestRuntime").contains("PackagedNativeAdmissionAttempt"))
    }
}
