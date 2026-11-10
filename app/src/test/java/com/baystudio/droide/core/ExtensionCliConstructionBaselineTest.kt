package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

/** Authoring only; not executed or installed in the source tree. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExtensionCliConstructionBaselineTest {
    private class CliRecorder {
        val publishedOwner = Any()
        var owner: Any = publishedOwner
        val starts = mutableListOf<Pair<UnifiedPackageAuthority, String>>()
        fun start(context: Context, authority: UnifiedPackageAuthority, workspaceId: String) {
            check(context.applicationContext === context)
            starts += authority to workspaceId
            owner = authority
        }
    }

    private class Fixture(val root: File, val context: Context) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
            configureIdentity = {},
            create = { error("No device transport permitted") },
            pair = { _, _, _ -> error("No pairing permitted") },
            discovery = { error("No discovery permitted") },
        ))
        val project = File(root, "projects/candidate").apply { mkdirs() }
        val development = AndroidDevelopmentManager(context, project, bridge)
        val capabilities = UniversalCapabilityRegistry("candidate", project)
        val cli = CliRecorder()
        private val managers = mutableListOf<DevelopmentExtensionsManager>()
        var managerCloses = 0
        var installedRefreshEntries = 0

        fun manager(enabled: Boolean): DevelopmentExtensionsManager = DevelopmentExtensionsManager(
            context, project, development, bridge, capabilities, ManagedPackageRegistry(context), enabled,
            startCli = cli::start,
            beforeInstalledRefresh = {
                installedRefreshEntries++
                error("Installed discovery/refresh is forbidden in constructor baseline")
            },
        ).also(managers::add)

        fun release(manager: DevelopmentExtensionsManager) {
            if (managers.remove(manager)) {
                managerCloses++
                manager.close()
            }
        }

        fun close() {
            try {
                managers.toList().forEach(::release)
            } finally {
                try { capabilities.close() } finally { try { bridge.close() } finally { scope.cancel() } }
            }
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val app: Application = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("r29-cli-constructor-").toFile()
        val preferencePrefix = "r29-${root.name}-"
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                app.getSharedPreferences(preferencePrefix + name, mode)
        }
        val previousPid = Process.myPid()
        var owned: Fixture? = null
        try {
            ShadowProcess.setPid(4242)
            val f = Fixture(root, context)
            owned = f
            block(f)
        } finally {
            try { owned?.close() } finally {
                ShadowProcess.setPid(previousPid)
                PathSecurity.deleteTreeNoFollow(root)
            }
        }
    }

    @Test(timeout = 15000) fun preparingNormalManagerMustNotActivateCliBeforeRuntimeCommit() = fixture { f ->
        val candidate = f.manager(enabled = true)
        assertTrue(candidate.installedComponentsEnabled)
        assertEquals(0, f.installedRefreshEntries)
        assertEquals(
            "No runtime commit was performed, so candidate construction must not hand over the CLI",
            0,
            f.cli.starts.size,
        )
    }

    /** Expected RED on untouched : failure cleanup cannot undo the early start callback. */
    @Test(timeout = 15000) fun failedCandidatePreparationMustNotReplacePublishedCliOwner() = fixture { f ->
        val published = f.cli.publishedOwner
        val failure = runCatching {
            WorkspaceStartupResources.prepare(Dispatchers.Unconfined) {
                val candidate = f.manager(enabled = true)
                own { f.release(candidate) }
                throw IllegalStateException("fixture: later runtime constructor failed")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("fixture: later runtime constructor failed", failure?.message)
        assertEquals("The completed candidate is released once", 1, f.managerCloses)
        assertEquals(0, f.installedRefreshEntries)
        assertSame(
            "A failed unpublished candidate must never replace the already published CLI owner",
            published,
            f.cli.owner,
        )
    }

    @Test(timeout = 15000) fun temporaryBuiltinsConstructionDoesNotActivateCliOrInstalledRefresh() = fixture { f ->
        val candidate = f.manager(enabled = false)
        assertFalse(candidate.installedComponentsEnabled)
        assertEquals(TEMPORARY_COMPONENTS_UNAVAILABLE, candidate.temporaryRecoveryReason)
        assertTrue(f.cli.starts.isEmpty())
        assertSame(f.cli.publishedOwner, f.cli.owner)
        assertEquals(0, f.installedRefreshEntries)
    }

    /** Updated characterization: only explicit accepted activation invokes the recording hook. */
    @Test(timeout = 15000) fun acceptedActivationUsesRecordingHookOnceWithoutCallingRealCli() = fixture { f ->
        val candidate = f.manager(enabled = true)
        assertTrue(f.cli.starts.isEmpty())
        candidate.activateCli()
        candidate.activateCli()
        assertEquals(1, f.cli.starts.size)
        val (authority, workspaceId) = f.cli.starts.single()
        assertSame(candidate.unifiedAuthority, authority)
        assertEquals(candidate.workspaceId, workspaceId)
        assertEquals(0, f.installedRefreshEntries)
        assertFalse("No real CLI endpoint directory may be created", File(f.root, "cli").exists())
    }

    @Test(timeout = 15000) fun closedCandidateCannotActivateCli() = fixture { f ->
        val candidate = f.manager(enabled = true)
        f.release(candidate)
        assertTrue(runCatching { candidate.activateCli() }.exceptionOrNull() is IllegalStateException)
        assertTrue(f.cli.starts.isEmpty())
        assertSame(f.cli.publishedOwner, f.cli.owner)
        assertEquals(1, f.managerCloses)
    }

    @Test(timeout = 15000) fun acceptedTemporaryBuiltinsManagerKeepsCliDisabled() = fixture { f ->
        val candidate = f.manager(enabled = false)
        candidate.activateCli()
        candidate.activateCli()
        assertTrue(f.cli.starts.isEmpty())
        assertEquals(0, f.installedRefreshEntries)
    }
}
