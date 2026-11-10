package com.baystudio.droide.core

import android.app.Application
import android.os.Process
import org.robolectric.shadows.ShadowProcess
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DevelopmentExtensionsRecoveryGateTest {

    private class Fixture(val root: File, val context: Context) {
        val project = File(root, "projects/fixture").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {}, create = { error("No transport permitted") },
            pair = { _, _, _ -> error("No pairing permitted") }, discovery = { error("No discovery permitted") },
        ))
        val development = AndroidDevelopmentManager(context, project, bridge)
        val capabilities = UniversalCapabilityRegistry("fixture", project)
        val registry = ManagedPackageRegistry(context)
        val managers = mutableListOf<DevelopmentExtensionsManager>()
        var starts = 0; var refreshEntries = 0
        fun manager(enabled: Boolean): DevelopmentExtensionsManager = DevelopmentExtensionsManager(
            context, project, development, bridge, capabilities, registry, enabled,
            startCli = { _, _, _ -> starts++ },
            beforeInstalledRefresh = { refreshEntries++; error("Installed refresh reached fail-fast boundary") },
        ).also { managers += it }
        fun close() { managers.forEach { it.close() }; capabilities.close(); bridge.close(); scope.cancel() }
    }
    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("recovery-maintenance-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        val previousPid = Process.myPid()
        var owned: Fixture? = null
        try {
            ShadowProcess.setPid(4242)
            val fixture = Fixture(root, context)
            owned = fixture
            block(fixture)
        } finally {
            try { owned?.close() } finally {
                ShadowProcess.setPid(previousPid)
                PathSecurity.deleteTreeNoFollow(root)
            }
        }
    }
    private fun snapshot(root: File): Map<String, String> = if (!root.exists()) emptyMap() else root.walkTopDown().associate { file ->
        file.relativeTo(root).path to if (file.isDirectory) "directory" else
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    @Test(timeout = 15_000) fun recoveryConstructorAndRefreshSkipCliJournalAndInstalledWorkEvenWithCorruptRegistry() = fixture { f ->
        val journal = File(f.root, "operation-journal/packages")
        assertFalse(journal.exists())
        val prefs = f.context.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("records_json", "corrupt fixture").commit())
        val beforePrefs = prefs.all.toMap()
        val manager = f.manager(enabled = false)
        repeat(3) {
            val snapshot = manager.refresh(if (it == 1) "b.kt" else "a.kt")
            assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, snapshot.message)
        }
        // Actual Agent catalog/status entry points must reject before the corrupt registry is read.
        val actions = AgentToolchainActions(manager)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { actions.list() }.exceptionOrNull()?.message)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { actions.status("fixture") }.exceptionOrNull()?.message)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { manager.unifiedAuthority.health("fixture") }.exceptionOrNull()?.message)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { manager.marketplace }.exceptionOrNull()?.message)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { manager.workspaceVersion("fixture") }.exceptionOrNull()?.message)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { manager.requestedItem("fixture", "latest") }.exceptionOrNull()?.message)
        assertEquals(0, f.starts); assertEquals(0, f.refreshEntries)
        assertFalse(journal.exists())
        assertEquals(beforePrefs, prefs.all.toMap())
        var operationRan = false
        val failure = runCatching { manager.installer.transaction("activate", "fixture") { operationRan = true } }.exceptionOrNull()
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, failure?.message)
        assertFalse(operationRan)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, runCatching { manager.installer.reconcileLocalPackages() }.exceptionOrNull()?.message)
        assertFalse(journal.exists())
        manager.close()
        assertNotNull(runCatching { manager.refresh() }.exceptionOrNull())
        assertEquals(0, f.starts); assertEquals(0, f.refreshEntries)
    }

    @Test(timeout = 15_000) fun recoveryDoesNotReconcileOrPruneExistingPackageJournal() = fixture { f ->
        val journal = LongRunningOperationJournal(File(f.root, "operation-journal"), "packages", 12345, "older-owner")
        journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "unfinished fixture")
        val directory = File(f.root, "operation-journal/packages")
        val before = snapshot(directory)
        val manager = f.manager(enabled = false)
        manager.refresh()
        manager.close()
        assertEquals(before, snapshot(directory))
        assertEquals(LongRunningOperationJournal.State.RUNNING, journal.list().single().state)
        assertEquals(0, f.starts); assertEquals(0, f.refreshEntries)
    }

    @Test(timeout = 15_000) fun normalConstructorStillReconcilesAndAcceptedActivationStartsCli() = fixture { f ->
        val journal = LongRunningOperationJournal(File(f.root, "operation-journal"), "packages", 12345, "older-owner")
        journal.begin(LongRunningOperationJournal.Kind.PACKAGE, "unfinished fixture")
        val manager = f.manager(enabled = true)
        assertEquals(0, f.starts)
        manager.activateCli()
        assertEquals(1, f.starts)
        assertEquals(LongRunningOperationJournal.State.INTERRUPTED, journal.list().single().state)
        val failure = runCatching { manager.refresh() }.exceptionOrNull()
        assertEquals("Installed refresh reached fail-fast boundary", failure?.message)
        assertEquals(1, f.refreshEntries)
    }
}
