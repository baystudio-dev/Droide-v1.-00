package com.baystudio.droide.ui

import com.baystudio.droide.core.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PluginHistoryOwnerProbe {
    private val root = Files.createTempDirectory("owner-probe-").toFile()
    private val stores = mutableListOf<PluginConversationHistory>()
    private fun repository() = SqlitePluginConversationHistory(root).also { stores += it }
    private suspend fun begin(store: PluginConversationHistory) = store.beginTurn(null, "opencode", "Question", WorkspaceAgentMode.EDIT, "original-native").summary.id
    @After fun cleanup() = runBlocking {
        stores.asReversed().forEach { it.close() }
        assertTrue(PathSecurity.deleteTreeNoFollow(root))
    }
    @Test fun foreignLoadMustNotStopLiveOwnerContinuation() = runBlocking {
        val owner = repository(); val id = begin(owner)
        owner.appendPartial(id, listOf("first "))
        val stranger = repository()
        runCatching { stranger.load(id) }
        val continued = runCatching { owner.appendPartial(id, listOf("second")) }
        assertTrue("Foreign load terminated the actual live writer: ${continued.exceptionOrNull()}", continued.isSuccess)
        val done = owner.finish(id, null, emptyList(), PluginHistoryStatus.COMPLETED)
        assertEquals("first second", done.messages.last().content)
    }
    @Test fun foreignDeleteMustPreserveActiveConversation() = runBlocking {
        val owner = repository(); val id = begin(owner)
        owner.appendPartial(id, listOf("saved live bytes"))
        val stranger = repository(); runCatching { stranger.delete(id) }
        assertEquals("Foreign delete erased an actually running conversation", id, owner.listPage().entries.singleOrNull()?.id)
        assertEquals("saved live bytes", owner.finish(id, null, emptyList(), PluginHistoryStatus.COMPLETED).messages.last().content)
    }
    @Test fun foreignListMustNotInventInterruption() = runBlocking {
        val owner = repository(); val id = begin(owner)
        assertEquals(PluginHistoryStatus.RUNNING, repository().listPage().entries.single { it.id == id }.status)
    }
    @Test fun foreignBindMustNotReplaceLiveNativeIdentity() = runBlocking {
        val owner = repository(); val id = begin(owner)
        runCatching { repository().bind(id, "wrong-native", "wrong-model") }
        assertEquals("original-native", owner.listPage().entries.single { it.id == id }.nativeSessionId)
        owner.finish(id, "Done", emptyList(), PluginHistoryStatus.COMPLETED); Unit
    }
    @Test fun replacementControllerRestoreMustNotFinalizeOldOwner() = runBlocking {
        val owner = repository(); val id = begin(owner)
        owner.appendPartial(id, listOf("durable partial"))
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val controller = ExternalAgentSessionController(scope, history = repository(), workAdmission = { true })
        try {
            val spec = WorkspaceAgentPluginCatalog.entries.first { it.id == "opencode" }
            assertTrue(controller.restoreLatest(spec))
            withTimeout(5000) { while (controller.busy) delay(1) }
            val continued = runCatching { owner.appendPartial(id, listOf(" continued")) }
            assertTrue("Actual controller restoreLatest terminated old store output: ${continued.exceptionOrNull()}", continued.isSuccess)
            assertEquals("durable partial continued", owner.finish(id, null, emptyList(), PluginHistoryStatus.COMPLETED).messages.last().content)
        } finally {
            controller.close(); scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
