package com.baystudio.droide

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.baystudio.droide.core.*
import com.baystudio.droide.ui.EditorWorkspaceState
import com.baystudio.droide.ui.prepareEditorExitCheckpointNow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowProcess

/** AUTHORED ONLY: not compiled or executed in the source-review environment. */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ExitActivityLifecycleTest {
    companion object {
        // Robolectric replaces Application per test; the process-owned substrate keeps one root.
        private val applicationRoot = Files.createTempDirectory("r64-lifecycle-application-").toFile().canonicalFile
    }

    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as DroideApplication
    private val activities = mutableListOf<ActivityController<MainActivity>>()
    private val services = mutableListOf<ServiceController<DevelopmentForegroundService>>()
    private val hosts = mutableListOf<WorkspaceRuntimeHost>()
    private val fixtureScopes = mutableListOf<CoroutineScope>()
    private var previousPid = 0

    @Before fun openIsolatedAdmission() {

        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory::class.java
            .getDeclaredField("_instance").apply { isAccessible = true }.set(null, null)
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(app)
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.reopenFromUser())

        assertNull(readField(app, "retainedHost"))
        assertNull(app.interfaceEpoch.value)
    }

    @After fun retireFixtureOwners() {
        fixtureScopes.forEach { it.cancel() }
        (readField(app, "exitScope") as CoroutineScope).cancel()
        activities.asReversed().forEach { controller ->
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
        services.toList().asReversed().forEach(::destroyService)
        hosts.asReversed().forEach { it.close() }
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        if (!ApplicationWorkAdmission.isOpen) ApplicationWorkAdmission.reopenFromUser()
        DevelopmentForegroundSessionRegistry.openForEpoch(ApplicationWorkAdmission.currentEpoch)
        shadowOf(Looper.getMainLooper()).idle()
        ShadowProcess.setPid(previousPid)
    }

    @Test fun duplicateExitSharesTheOriginalCleanupAndCancelsEachRegistrationOnce() {
        val controller = service()
        val epoch = ApplicationWorkAdmission.currentEpoch
        var cancellations = 0
        DevelopmentForegroundSessionRegistry.register("r64-duplicate-exit-operation") { cancellations++ }

        val originalInterface = Any()
        app.requestExit(epoch, originalInterface)
        await { app.exitState.value.stage == "Releasing notification and CPU leases" }
        val sequence = readField(app, "sequence")
        assertFalse(ApplicationWorkAdmission.isOpen)
        assertFalse(app.exitState.value.completed)
        assertFalse("Do not hide an unfinished shutdown behind a closed task", app.closeWorkspaceUi.value)
        assertFalse(app.shouldCloseUserInterface(originalInterface))
        assertFalse(app.shouldCloseUserInterface(Any()))

        // Android has accepted stopService, but has not acknowledged this instance's destroy.
        app.requestExit(epoch, Any())
        app.requestExit(epoch, Any())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(sequence, readField(app, "sequence"))
        assertEquals(1, cancellations)
        assertFalse(app.exitState.value.completed)

        destroyService(controller)
        await { app.exitState.value.completed }
        assertTrue(app.shouldCloseUserInterface(originalInterface))
        app.requestExit(epoch, Any())
        assertSame(sequence, readField(app, "sequence"))
        assertEquals(1, cancellations)
        assertNull(readField(app, "retainedHost"))
        assertNull(app.interfaceEpoch.value)
    }

    @Test fun notificationExitClosesOnlyTheCapturedInterfaceWithoutStartingAnActivity() {
        val controller = service()
        val epoch = ApplicationWorkAdmission.currentEpoch
        val originalInterface = Any()
        writeField(app, "currentInterface", java.lang.ref.WeakReference(originalInterface))
        val service = controller.get()
        service.onStartCommand(Intent().setAction(DevelopmentForegroundService.ACTION_REQUEST_EXIT)
            .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, epoch), 0, 1)
        await { app.exitState.value.stage == "Releasing notification and CPU leases" }
        assertFalse(ApplicationWorkAdmission.isOpen)
        assertFalse(app.shouldCloseUserInterface(originalInterface))
        assertFalse(app.shouldCloseUserInterface(Any()))
        assertFalse(app.exitState.value.completed)
        assertNull(shadowOf(app).nextStartedActivity)
        destroyService(controller)
        await { app.exitState.value.completed }
        assertTrue(app.shouldCloseUserInterface(originalInterface))
    }

    @Test fun notificationExitFinishesAStoppedActivityWithoutWaitingForAComposeFrame() {
        ApplicationWorkAdmission.closeForExit()
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        val controller = activity()
        assertNull(readField(app, "retainedHost"))
        controller.pause().stop()
        try {
            val epoch = ApplicationWorkAdmission.reopenFromUser()
            DevelopmentForegroundSessionRegistry.openForEpoch(epoch)
            val service = service()
            service.get().onStartCommand(Intent().setAction(DevelopmentForegroundService.ACTION_REQUEST_EXIT)
                .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, epoch), 0, 1)

            await { app.exitState.value.stage == "Releasing notification and CPU leases" }
            assertFalse(controller.get().isFinishing)
            destroyService(service)
            await { app.closeWorkspaceUi.value }
            assertTrue("Notification Exit must finish the stopped task without resuming its composition",
                controller.get().isFinishing)
            assertNull(shadowOf(app).nextStartedActivity)
            assertNull(readField(app, "retainedHost"))
            await { app.exitState.value.completed }
        } finally {
            controller.start().resume()
        }
    }

    @Test fun pendingServiceDestructionForbidsOpenAndCreationOfANewRuntimeHost() {
        val controller = service()
        val epoch = ApplicationWorkAdmission.currentEpoch
        app.requestExit(epoch, Any())
        await { app.exitState.value.stage == "Releasing notification and CPU leases" }

        val reopening = runCatching { app.openFromUser() }.exceptionOrNull()
        assertTrue(reopening is IllegalStateException)
        assertTrue(reopening?.message.orEmpty().contains("previous Exit is not complete"))
        val replacement = runCatching { app.workspaceHost }.exceptionOrNull()
        assertTrue(replacement is IllegalStateException)
        assertNull(readField(app, "retainedHost"))
        assertEquals(epoch, ApplicationWorkAdmission.currentEpoch)
        assertFalse(ApplicationWorkAdmission.isOpen)

        destroyService(controller)
        await { app.exitState.value.completed }
    }

    @Test fun realWorkspaceWithUninitializedCleanEditorExitsWithoutRecoveryRetry() {
        val host = realHost()
        val runtime = checkNotNull(host.coordinator.runtime.value)
        val editor = host.editorStateFor(runtime)
        assertFalse(editor.recoveryInitialized)
        val originalInterface = Any()
        app.requestExit(ApplicationWorkAdmission.currentEpoch, originalInterface)
        await { app.exitState.value.completed || app.exitState.value.failure != null }
        assertNull(app.exitState.value.failure)
        assertTrue(app.exitState.value.completed)
        assertTrue(app.shouldCloseUserInterface(originalInterface))
        assertFalse(ApplicationWorkAdmission.isOpen)
        assertNull(host.coordinator.runtime.value)
        assertNull(readField(app, "retainedHost"))
        assertEquals("saved disk sentinel", File(runtime.project.rootPath, "draft.txt").readText())
    }

    @Test fun preparingReplacementActivityRecreatesAsRecoveryPanelWithoutRebindingSealedEditor() {
        val fixture = sealedRecoveryFixture(failFirst = false)
        val controller = activity()
        compose.onNodeWithText("Preserving unsaved editor drafts").assertIsDisplayed()
        assertRecoveryOwnerIntact(fixture, controller.get())

        controller.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Preserving unsaved editor drafts").assertIsDisplayed()
        compose.onNodeWithText("Saving drafts. Droide will close automatically.").assertIsDisplayed()
        compose.onNodeWithText("Retry Exit").assertDoesNotExist()
        assertRecoveryOwnerIntact(fixture, controller.get())
        assertTrue(app.exitState.value.running)
        assertFalse(controller.get().isFinishing)
    }

    @Test fun failedReplacementActivityRecreatesWithRetryAndKeepsItsSealedDraft() {
        val fixture = sealedRecoveryFixture(failFirst = true)
        val controller = activity()
        compose.onNodeWithText("fixture checkpoint failure").assertIsDisplayed()
        controller.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("fixture checkpoint failure").assertIsDisplayed()
        compose.onNodeWithText("Retry Exit").assertIsDisplayed().assertIsEnabled()
        assertRecoveryOwnerIntact(fixture, controller.get())

        compose.onNodeWithText("Retry Exit").performClick()
        await { app.exitState.value.running && fixture.attempts() == 2 }
        compose.onNodeWithText("Saving drafts. Droide will close automatically.").assertIsDisplayed()
        assertRecoveryOwnerIntact(fixture, controller.get())
        assertFalse(controller.get().isFinishing)
    }

    @Test fun newlyCreatedActivityAfterCompletedExitOffersOpenInsteadOfFinishingItself() {
        // Exercise the actual Application pipeline, without an owned runtime/service to fake.
        app.requestExit(ApplicationWorkAdmission.currentEpoch, Any())
        await { app.exitState.value.completed }
        val controller = activity()

        compose.onNodeWithText("Session stopped").assertIsDisplayed()
        compose.onNodeWithText("Ready when you are").assertIsDisplayed()
        compose.onNodeWithText("Open Droide").assertIsDisplayed().assertIsEnabled()
        compose.waitForIdle()
        assertFalse(controller.get().isFinishing)
        assertFalse(controller.get().isDestroyed)
        assertNull(readField(app, "retainedHost"))
        assertFalse(ApplicationWorkAdmission.isOpen)

        controller.recreate()
        compose.onNodeWithText("Open Droide").assertIsDisplayed().assertIsEnabled()
        assertFalse(controller.get().isFinishing)
        // Clicking Open would use the default runtime constructor and its external setup. This test
        // claims the real enabled affordance/lifecycle only, not a full restart.
    }

    @Test fun exitNotificationFromAnOldEpochIsConsumedAndCurrentActionExitsDirectly() {
        val oldEpoch = ApplicationWorkAdmission.currentEpoch
        ApplicationWorkAdmission.closeForExit()
        val newEpoch = ApplicationWorkAdmission.reopenFromUser()
        assertNotEquals(oldEpoch, newEpoch)
        // The real handler needs no mounted workspace; do not start a default runtime.
        val target = Robolectric.buildActivity(MainActivity::class.java).get()
        val stale = exitIntent(oldEpoch)
        deliverExitIntent(target, stale)
        assertNull(stale.action)
        assertNull(readField(app, "sequence"))
        assertTrue(ApplicationWorkAdmission.isCurrentOpen(newEpoch))

        val current = exitIntent(newEpoch)
        deliverExitIntent(target, current)
        assertNull(current.action)
        assertNotNull(readField(app, "sequence"))
        assertFalse(ApplicationWorkAdmission.isOpen)
        assertEquals(newEpoch, ApplicationWorkAdmission.currentEpoch)
    }

    @Test fun staleApplicationExitRequestCannotCloseTheCurrentEpoch() {
        val oldEpoch = ApplicationWorkAdmission.currentEpoch
        ApplicationWorkAdmission.closeForExit()
        val newEpoch = ApplicationWorkAdmission.reopenFromUser()
        val failure = runCatching { app.requestExit(oldEpoch, Any()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("expired Droide session"))
        assertTrue(ApplicationWorkAdmission.isCurrentOpen(newEpoch))
        assertNull(readField(app, "sequence"))
        assertFalse(app.exitState.value.started)
    }

    @Test fun queuedOldServiceFinishAndLateDestroyCannotRetireTheNewHostOrWakeLease() {
        val oldController = service()
        val oldService = oldController.get()
        val oldEpoch = ApplicationWorkAdmission.currentEpoch
        val oldToken = "r64-old-service-operation"
        DevelopmentForegroundSessionRegistry.register(oldToken) {}
        oldService.onStartCommand(startIntent(oldToken, oldEpoch), 0, 1)

        DevelopmentForegroundService.finish(app, DevelopmentForegroundService.Handle(oldToken))
        ApplicationWorkAdmission.closeForExit()
        DevelopmentForegroundSessionRegistry.closeForExit().getOrThrow()
        val newEpoch = ApplicationWorkAdmission.reopenFromUser()
        DevelopmentForegroundSessionRegistry.openForEpoch(newEpoch)

        // Deliberately model an obsolete callback delivered after a replacement owns Main. Public
        // openFromUser cannot cross an unfinished Exit; that guard is tested above.
        val newController = service()
        val newService = newController.get()
        val newToken = "r64-new-service-operation"
        var newCancellation = 0
        DevelopmentForegroundSessionRegistry.register(newToken) { newCancellation++ }
        newService.onStartCommand(startIntent(newToken, newEpoch), 0, 1)
        newService.onStartCommand(Intent().setAction(DevelopmentForegroundService.ACTION_ACQUIRE_WAKE_LOCK)
            .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, newEpoch), 0, 2)
        val newManualLease = ShadowPowerManager.getLatestWakeLock()
        assertTrue(newManualLease.isHeld)
        val newHost = realHost()
        val newRuntime = checkNotNull(newHost.coordinator.runtime.value)

        destroyService(oldController)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DevelopmentForegroundService.manualWakeLock.value)
        assertTrue(newManualLease.isHeld)
        assertEquals(0, newCancellation)
        assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(newToken))
        assertSame(newHost, app.workspaceHost)
        assertSame(newRuntime, newHost.coordinator.runtime.value)
        assertTrue(ApplicationWorkAdmission.isCurrentOpen(newEpoch))
    }

    private data class RecoveryFixture(
        val host: WorkspaceRuntimeHost,
        val runtime: WorkspaceRuntime,
        val editor: EditorWorkspaceState,
        val disk: File,
        val attempts: () -> Int,
    )

    private fun sealedRecoveryFixture(failFirst: Boolean): RecoveryFixture {
        val host = realHost()
        val runtime = checkNotNull(host.coordinator.runtime.value)
        val editor = host.editorStateFor(runtime)
        val disk = File(runtime.files.root, "draft.txt")
        val document = editor.document("draft.txt")
        runBlocking { document.ensureLoaded(runtime.files) }
        document.capture("unsaved R64 lifecycle sentinel")
        editor.retainNavigation(listOf("draft.txt"), "draft.txt", 1)
        editor.completeRecovery()
        // Production checkpoint preparation seals the actual host-owned editor model. No native binding
        // is installed, and this test does not attempt encrypted writes.
        val checkpoint = prepareEditorExitCheckpointNow(editor, host.editorRecoveryStoreFor(runtime))
        writeField(host, "exitEditorCheckpoint", checkpoint)
        val originalActivityOwner = Any()
        writeField(app, "originalExitActivity", originalActivityOwner)
        val epoch = ApplicationWorkAdmission.currentEpoch
        app.interfaceEpoch.value = epoch
        ApplicationWorkAdmission.closeForExit()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also(fixtureScopes::add)
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val sequence = ApplicationExitSequence(scope, listOf(
            ApplicationExitSequence.Step("Preserving unsaved editor drafts") {
                attempts++
                if (failFirst && attempts == 1) error("fixture checkpoint failure")
                release.await()
            },
        ), observationMs = TimeUnit.MINUTES.toMillis(5))
        writeField(app, "sequence", sequence)
        @Suppress("UNCHECKED_CAST")
        val mirror = readField(app, "mutableExit") as MutableStateFlow<ApplicationExitSequence.State>
        scope.launch(start = CoroutineStart.UNDISPATCHED) { sequence.state.collect { mirror.value = it } }
        sequence.request()
        await { attempts == 1 && if (failFirst) app.exitState.value.failure != null else app.exitState.value.running }
        app.beforeActivityDestroyed(originalActivityOwner)
        assertFalse(app.mayPresentWorkspace(Any()))
        assertFalse(document.canEdit)
        return RecoveryFixture(host, runtime, editor, disk) { attempts }
    }

    private fun realHost(): WorkspaceRuntimeHost {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = applicationRoot
        }
        val root = context.filesDir
        val projectRoot = File(root, "projects/r64-lifecycle").apply { check(isDirectory || mkdirs()) }
        File(projectRoot, "draft.txt").writeText("saved disk sentinel")
        val project = DroideProject("r64-lifecycle", "R64 lifecycle", projectRoot.path)
        val store = object : ProjectStore {
            override suspend fun load() = listOf(project)
            override suspend fun loadActiveId() = project.id
            override suspend fun save(all: List<DroideProject>) { error("Fixture must not rewrite project catalog") }
            override suspend fun saveActiveId(id: String) { error("Fixture must not change active project") }
        }
        val recents = object : ProjectRecentsStore {
            override suspend fun load(): List<String>? = listOf(project.id)
            override suspend fun save(ids: List<String>) { error("Fixture must not rewrite recents") }
        }

        val cache = File(root, "models-dev/api.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"fixture":{"id":"fixture","models":{}}}""")
        }
        ModelsDevCatalog.parse(cache.readText())
        File(cache.parentFile, "cache.meta").writeText("fetchedAtMs=${System.currentTimeMillis()}")
        val host = WorkspaceRuntimeHost(app) { _, scope, timers ->
            val bridge = DeviceBridgeManager(context, scope, DeviceBridgeDependencies(
                configureIdentity = {}, create = { error("No transport in lifecycle fixture") },
                pair = { _, _, _ -> error("No pairing in lifecycle fixture") },
                discovery = { error("No discovery in lifecycle fixture") },
            ))
            WorkspaceCoordinator(context, scope, ProjectManager(root, store, recents),
                browserApplicationTimers = timers, deviceBridge = bridge,
                processReconciliation = { error("Fixture stops before installed component initialization") },
                initialUbuntuPreparation = { error("No automatic Ubuntu startup in lifecycle fixture") },
            )
        }.also(hosts::add)
        writeField(app, "retainedHost", host)
        await { host.initError.value != null }
        // Real recovery buttons run on Main; do not race the initializer from the test thread.
        compose.runOnUiThread { host.initializeWorkspace(WorkspaceStartupMode.TEMPORARY_BUILTINS) }
        await(details = { "startup=${host.coordinator.startup.value}; initError=${host.initError.value}" }) {
            host.coordinator.runtime.value != null && host.coordinator.startup.value.phase == WorkspaceStartupPhase.READY
        }
        assertEquals(WorkspaceStartupMode.TEMPORARY_BUILTINS, host.coordinator.runtime.value!!.startupMode)
        return host
    }

    private fun assertRecoveryOwnerIntact(fixture: RecoveryFixture, activity: MainActivity) {
        assertSame(fixture.host, app.workspaceHost)
        assertSame(fixture.runtime, fixture.host.coordinator.runtime.value)
        assertSame(fixture.editor, fixture.host.editorStateFor(fixture.runtime))
        assertFalse(app.mayPresentWorkspace(activity))
        assertFalse(fixture.editor.document("draft.txt").canEdit)
        assertEquals("unsaved R64 lifecycle sentinel", fixture.editor.document("draft.txt").content)
        assertEquals("saved disk sentinel", fixture.disk.readText())
        assertTrue("A sealed editor must reject a new presentation binding",
            runCatching { fixture.editor.bindProjectHandoffCapture {} }.isFailure)
        assertFalse("Recovery Activity unexpectedly mounted a native editor or WebView",
            descendants(activity.window.decorView).any { view ->
                view is WebView || generateSequence(view.javaClass as Class<*>?) { it.superclass }
                    .any { it.name == "io.github.rosemoe.sora.widget.CodeEditor" }
            })
    }

    private fun activity() = Robolectric.buildActivity(MainActivity::class.java)
        .setup().also(activities::add).also { compose.waitForIdle() }

    private fun service() = Robolectric.buildService(DevelopmentForegroundService::class.java)
        .create().also(services::add)

    private fun destroyService(controller: ServiceController<DevelopmentForegroundService>) {
        if (services.remove(controller)) controller.destroy()
    }

    private fun exitIntent(epoch: String) = Intent().setAction(ApplicationWorkAdmission.ACTION_REQUEST_EXIT)
        .putExtra(ApplicationWorkAdmission.EXTRA_EXIT_EPOCH, epoch)

    private fun startIntent(token: String, epoch: String) = Intent()
        .setAction("com.baystudio.droide.DEV_FGS_START").putExtra("token", token).putExtra("label", "R64 fixture")
        .putExtra(DevelopmentForegroundService.EXTRA_EPOCH, epoch)

    private fun deliverExitIntent(activity: MainActivity, intent: Intent) {
        MainActivity::class.java.getDeclaredMethod("handleExitIntent", Intent::class.java).apply {
            isAccessible = true
        }.invoke(activity, intent)
    }

    private fun readField(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun writeField(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun await(details: () -> String = { app.exitState.value.toString() }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("R64 lifecycle fixture did not reach its required state: ${details()}", condition())
    }
}
