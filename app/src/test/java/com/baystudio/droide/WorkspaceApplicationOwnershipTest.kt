package com.baystudio.droide

import android.os.Looper
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.baystudio.droide.core.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess

/** Real ViewModel-store disposal and loopback provider transport, no cloud inference or Ubuntu setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DroideApplication::class)
class WorkspaceApplicationOwnershipTest {
    class Screen : ComponentActivity()
    private val application get() = RuntimeEnvironment.getApplication() as DroideApplication

    private var previousPid = 0
    private var ownedHost: WorkspaceRuntimeHost? = null

    @Before fun openUserSession() {
        previousPid = Process.myPid()
        ShadowProcess.setPid(4242)
        ApplicationWorkAdmission.initialize(application)
        ApplicationWorkAdmission.reopenFromUser()
        val cache = File(application.filesDir, "models-dev/api.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"fixture":{"id":"fixture","models":{}}}""")
        }
        ModelsDevCatalog.parse(cache.readText())
        File(cache.parentFile, "cache.meta").writeText("fetchedAtMs=${System.currentTimeMillis()}")
        ownedHost = application.workspaceHost
        assertTrue(ApplicationWorkAdmission.isOpen)
    }

    @After fun releaseProcessFixture() {
        try { ownedHost?.close() } finally { ShadowProcess.setPid(previousPid) }
    }

    private fun viewModelFor(activity: ComponentActivity): WorkspaceViewModel =
        ViewModelProvider(activity, ViewModelProvider.AndroidViewModelFactory(application))[WorkspaceViewModel::class.java]

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Condition did not settle", condition())
    }

    @Test fun runtimeChildFailureIsSurfacedWithoutKillingTheProcessOwnerOrItsSibling() {
        val host = application.workspaceHost
        val sibling = host.runtimeScope.launch { awaitCancellation() }
        val failed = host.runtimeScope.launch { throw java.io.IOException("Simulated runtime read failure") }
        await { failed.isCompleted && host.runtimeFailure.value != null }
        assertTrue(failed.isCancelled)
        assertTrue(sibling.isActive)
        assertTrue(host.runtimeScope.isActive)
        val event = requireNotNull(host.runtimeFailure.value)
        host.consumeRuntimeFailure(event)
        assertNull(host.runtimeFailure.value)
        sibling.cancel()
    }

    @Test fun finishingAndReopeningAnActivityReusesTheSameOwnerAndDoesNotCancelWork() {
        val first = Robolectric.buildActivity(Screen::class.java).setup()
        val model = viewModelFor(first.get())
        val host = application.workspaceHost
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = host.runtimeScope.launch { entered.complete(Unit); release.await() }
        await { entered.isCompleted }
        first.pause().stop()
        assertTrue(job.isActive)
        first.get().finish()
        first.destroy()
        assertTrue("Activity destruction is not Stop", job.isActive)
        val second = Robolectric.buildActivity(Screen::class.java).setup()
        try {
            val reopened = viewModelFor(second.get())
            assertNotSame(model, reopened)
            assertSame(model.coordinator, reopened.coordinator)
            assertSame(host, application.workspaceHost)
            release.complete(Unit)
            await { job.isCompleted }
            assertFalse(job.isCancelled)
        } finally { second.get().finish(); second.pause().stop().destroy() }
    }

    private fun builtinWorkflow(stop: Boolean) {
        val root = Files.createTempDirectory("agent-ui-owner-").toFile()
        val activity = Robolectric.buildActivity(Screen::class.java).setup()
        viewModelFor(activity.get())
        val host = application.workspaceHost
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { fixture ->
                fixture.ignoreExpectedClientDisconnect = stop
                fixture.response = { _, _, _ ->
                    started.countDown()
                    check(finish.await(15, TimeUnit.SECONDS))
                    fixture.event("""{"choices":[{"index":0,"delta":{"content":"work finished after screen closure"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                }
                val agent = AgentService(FileRepository(root), AgentWorkflowTerminal(), GitManager(root),
                    maxSubagentDepth = 0, runtimeScope = host.runtimeScope)
                try {
                    val admission = agent.submitAsync(caller, "Continue working", fixture.config, maxTurns = 1)
                    assertNotNull(admission)
                    await { started.count == 0L && agent.busy.value }
                    caller.cancel("Agent panel removed")
                    activity.get().finish(); activity.pause().stop().destroy()
                    assertTrue(agent.busy.value)
                    if (stop) agent.cancel()
                    finish.countDown()
                    await { !agent.busy.value }
                    val session = runBlocking { agent.sessions.get(checkNotNull(agent.currentSessionId)) }!!
                    if (stop) {
                        assertFalse(session.messages.any { it.content.contains("work finished after screen closure") })
                    } else {
                        assertTrue(session.messages.any { it.content.contains("work finished after screen closure") })
                    }
                    assertEquals(1, fixture.requests.size)
                } finally { finish.countDown(); agent.shutdown() }
            }
        } finally { finish.countDown(); caller.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun builtinAgentFinishesAndPersistsAfterPanelAndActivityAreDestroyed() = builtinWorkflow(stop = false)
    @Test fun explicitStopStillCancelsBuiltinAgentAfterScreenClosure() = builtinWorkflow(stop = true)
}
