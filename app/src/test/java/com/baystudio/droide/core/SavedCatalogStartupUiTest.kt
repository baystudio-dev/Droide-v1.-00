package com.baystudio.droide.core

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.ui.ProjectCatalogRecoveryEntry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedCatalogStartupUiTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val admission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test fun coordinatorKeepsSameManagerAndExistingFailureEntryInspectsPending() {
        val app = Files.createTempDirectory("selected-startup-ui-").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = app
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var coordinator: WorkspaceCoordinator? = null
        try {
            runBlocking {
                val disk = FileProjectStore(app)
                val default = DroideProject("default", "Default", File(app, "projects/default").apply { mkdirs() }.path)
                val target = DroideProject("stored", "Stored", File(app, "projects/stored").apply { mkdirs() }.path, workspaceRemoved = true)
                disk.save(listOf(default, target)); disk.saveActiveId("default")
                val storage = File(app, "catalog-startup").apply { mkdir() }
                val selected = ProjectCatalogAdmission(app, storage, 1_000_000)
                val enrollment = CanonicalJournalBootstrap(selected.updateSlot, 1_000_000).createWithMembership(
                    File(app, ".droide"), ProjectCatalogUpdate.AUTHORITY_ID, selected.updateMembership, EmptyCoroutineContext)
                val selection = selected.recordUpdateChoice(enrollment)
                val store = JournaledProjectStore(selection, CanonicalJournalRetention.Policy(8, 100_000_000), checkpoint = {
                    if (it == "admission-after-commit") throw IOException("lost admission")
                })
                store.enableMembershipCommands()
                assertNotNull(runCatching { store.restoreMembership("stored", disk.loadCatalog().revision) }.exceptionOrNull())
            }
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport") }, pair = { _, _, _ -> error("No pairing") },
                discovery = { error("No discovery") }))
            val owner = WorkspaceCoordinator(context, scope, deviceBridge = bridge, processReconciliation = { error("No process startup") })
            coordinator = owner
            val manager = owner.projects
            assertFalse(manager.supportsCatalogRecoveryReview)
            runBlocking { assertNotNull(runCatching { manager.init() }.exceptionOrNull()) }
            assertSame(manager, owner.projects); assertTrue(manager.supportsCatalogRecoveryReview)
            assertTrue(manager.projects.value.isEmpty()); assertNull(owner.runtime.value)

            compose.setContent { MaterialTheme { ProjectCatalogRecoveryEntry(owner.projects) } }
            compose.onNodeWithText("Review project changes").assertIsDisplayed().performClick()
            compose.onNodeWithText("Saved project changes").assertIsDisplayed()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText("A pending change must be reviewed before another update.").assertIsDisplayed() }.isSuccess
            }
            runBlocking { assertNotNull(manager.inspectCatalogRecovery().pending) }
        } finally {
            coordinator?.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(app)
        }
    }
}
