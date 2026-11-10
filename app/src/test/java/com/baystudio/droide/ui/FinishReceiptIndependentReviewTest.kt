package com.baystudio.droide.ui

import android.database.sqlite.SQLiteDatabase
import com.baystudio.droide.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FinishReceiptIndependentReviewTest {
    private fun root() = Files.createTempDirectory("f158-review-").toFile()
    private fun database(root: File) = File(root, ".droide/sessions/plugins.sqlite")
    private fun snapshot(root: File): List<String> = SQLiteDatabase.openDatabase(database(root).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", null).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        buildList {
            for (table in tables) db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { c ->
                add("TABLE:$table:${c.columnNames.toList()}")
                while(c.moveToNext()) add((0 until c.columnCount).joinToString("|") { n ->
                    if(c.isNull(n)) "NULL" else when(c.getType(n)) {
                        android.database.Cursor.FIELD_TYPE_BLOB -> "B:"+Base64.getEncoder().encodeToString(c.getBlob(n))
                        else -> "${c.getType(n)}:"+c.getString(n)
                    }
                })
            }
        }
    }
    private suspend fun refused(block: suspend () -> Any?) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("Must refuse without mutation", failure)
    }
    private suspend fun admit(store: PluginHistoryCheckpointStore, id: String? = null, prompt: String = "identical prompt") =
        store.beginCheckpointTurn(id, "opencode", prompt, WorkspaceAgentMode.EDIT, "native-old")
    private fun request(identity: PluginHistoryTurnIdentity, reply: String? = "identical answer", media: List<ChatMediaRef> = emptyList()) =
        PluginHistoryFinishRequest(identity, reply, listOf("first notice", "second notice"), PluginHistoryStatus.COMPLETED, media)

    @Test fun staleIdentityCannotWriteAnyPreparatoryOperationOrFinishNewerIdenticalTurn() = runBlocking {
        val root = root(); val store = SqlitePluginConversationHistory(root)
        try {
            val first = admit(store); val old = request(first.identity)
            val firstFinal = store.finishCheckpoint(old)
            assertEquals(firstFinal, store.finishCheckpoint(old))
            val second = admit(store, first.identity.conversationId)
            assertNotEquals(first.identity.turnId, second.identity.turnId)
            val running = snapshot(root)
            refused { store.bindCheckpoint(first.identity, "stale-native", "stale-model") }
            refused { store.appendCheckpointPartial(first.identity, listOf("stale partial")) }
            refused { store.appendCheckpointTranscript(first.identity, listOf(ExternalTranscriptUpdate(AgentStreamPart("stale", 0, AgentStreamPartKind.TEXT, AgentStreamPartState.COMPLETED), textDelta="stale activity text"))) }
            refused { store.finishCheckpoint(old) }
            assertEquals(running, snapshot(root))
            store.bindCheckpoint(second.identity, "native-new", "new-model")
            val current = request(second.identity)
            val saved = store.finishCheckpoint(current)
            assertEquals(8, saved.summary.messageCount)
            assertEquals("native-new", saved.summary.nativeSessionId)
            val completed = snapshot(root)
            refused { store.finishCheckpoint(old) }
            refused { store.bindCheckpoint(first.identity, "stale-native") }
            assertEquals(completed, snapshot(root))
            assertEquals(saved, store.finishCheckpoint(current))
            assertEquals(completed, snapshot(root))
        } finally { store.close() }
    }

    @Test fun fullRequestFingerprintDistinguishesEveryFieldAndExactUtf16() = runBlocking {
        val root = root(); val store = SqlitePluginConversationHistory(root)
        try {
            val admission = admit(store)
            val a = ChatMediaRef("one", "path/one", "name", "image/png", 9, 10, 11)
            val b = ChatMediaRef("two", "path/two", "two", "image/png", 12, 13, 14)
            val original = request(admission.identity, "answer\uD800", listOf(a,b)).copy(notices=listOf("notice\uD800", "other"))
            assertNotEquals(pluginHistoryFinishFingerprint(original.copy(media=listOf(a.copy(name="name\uD800")))),
                pluginHistoryFinishFingerprint(original.copy(media=listOf(a.copy(name="name\uD801")))))
            val saved = store.finishCheckpoint(original); val initial = snapshot(root)
            val changedMedia = listOf(a.copy(id="other"),a.copy(path="other"),a.copy(name="changed name"),a.copy(mediaType="image/jpeg"),a.copy(bytes=10),a.copy(createdAt=11),a.copy(expiresAt=12))
            val variants = listOf(original.copy(reply="answer\uD801"),original.copy(reply=""),original.copy(reply=null),
                original.copy(notices=listOf("notice\uD801","other")),original.copy(notices=original.notices.reversed()),
                original.copy(notices=listOf("notice\uD800other")),original.copy(status=PluginHistoryStatus.STOPPED),
                original.copy(media=original.media.reversed()),original.copy(media=listOf(a)),
                original.copy(identity=original.identity.copy(turnId=UUID.randomUUID().toString()))) + changedMedia.map { original.copy(media=listOf(it,b)) }
            for (different in variants) { refused { store.finishCheckpoint(different) }; assertEquals(initial,snapshot(root)) }
            assertEquals(saved,store.finishCheckpoint(original.copy(notices=original.notices.toList(),media=original.media.toList())))
            assertEquals(initial,snapshot(root))
        } finally { store.close() }
    }

    @Test fun nullReplyIsDistinctFromEmptyAndLegacyFinishDoesNotAcquireReceiptAuthority() = runBlocking {
        val root=root(); val store=SqlitePluginConversationHistory(root)
        try {
            val admitted=admit(store);store.appendCheckpointPartial(admitted.identity,listOf("retained\uD800"))
            val original=request(admitted.identity,null).copy(status=PluginHistoryStatus.STOPPED)
            val saved=store.finishCheckpoint(original);val initial=snapshot(root)
            refused { store.finishCheckpoint(original.copy(reply="")) }
            refused { store.finish(admitted.identity.conversationId,null,original.notices,original.status) }
            assertEquals(initial,snapshot(root));assertEquals(saved,store.finishCheckpoint(original))
            val legacy=store.beginTurn(null,"opencode","legacy",WorkspaceAgentMode.EDIT,null)
            store.finish(legacy.summary.id,"legacy answer",emptyList(),PluginHistoryStatus.COMPLETED)
            refused { store.finish(legacy.summary.id,"legacy answer",emptyList(),PluginHistoryStatus.COMPLETED) }
        } finally { store.close() }
    }

    @Test fun foreignLiveOwnerClosedOwnerAndReopenedOwnerNeverRecoverOriginalReceipt() = runBlocking {
        val root=root(); val store=SqlitePluginConversationHistory(root); val foreign=SqlitePluginConversationHistory(root)
        try {
            val admitted=admit(store);val original=request(admitted.identity)
            val active=snapshot(root)
            refused { foreign.finishCheckpoint(original) };refused { foreign.bindCheckpoint(admitted.identity,"foreign") }
            refused { foreign.appendCheckpointPartial(admitted.identity,listOf("foreign")) }
            assertEquals(active,snapshot(root))
            assertNotNull(foreign.load(admitted.identity.conversationId)!!.readOnlyReason)
            val saved=store.finishCheckpoint(original);val finished=snapshot(root)
            refused { foreign.finishCheckpoint(original) };assertEquals(finished,snapshot(root))
            store.close();refused { store.finishCheckpoint(original) }
            val reopened=SqlitePluginConversationHistory(root)
            try { refused { reopened.finishCheckpoint(original) };assertEquals(saved,reopened.load(admitted.identity.conversationId));assertEquals(finished,snapshot(root)) }
            finally { reopened.close() }
        } finally { foreign.close();store.close() }
    }

    @Test fun realTransactionRollsBackBeforeCommitAndRecoversLostReturnAfterCommit() = runBlocking {
        val root=root();val store=SqlitePluginConversationHistory(root)
        try {
            val admitted=admit(store);val original=request(admitted.identity,"a".repeat(17000)+"\uD83D\uDE80\uD800")
            val active=snapshot(root);var before=true
            store.lifecycleCheckpoint={ if(it=="before-finish-commit" && before){before=false;throw IOException("precommit review fault")} }
            refused { store.finishCheckpoint(original) };assertFalse(before);assertEquals(active,snapshot(root))
            var after=true
            store.lifecycleCheckpoint={ if(it=="after-finish-commit" && after){after=false;throw IOException("postcommit review fault")} }
            refused { store.finishCheckpoint(original) };assertFalse(after)
            val finished=snapshot(root)
            val saved=store.finishCheckpoint(original)
            assertEquals(4,saved.summary.messageCount);assertEquals(original.reply,saved.messages[1].content)
            assertEquals(finished,snapshot(root))
        } finally { store.lifecycleCheckpoint={};store.close() }
    }

    private class LostReturnStore(val durable: SqlitePluginConversationHistory): PluginConversationHistory by durable, PluginHistoryCheckpointStore by durable {
        var lose=true;var finishAttempts=0;var binds=0;var partials=0;var transcripts=0;var closed=false
        var recoveryGate:CompletableDeferred<Unit>?=null
        val recoveryEntered=CompletableDeferred<Unit>()
        override suspend fun close(){durable.close();closed=true}
        override suspend fun bindCheckpoint(identity: PluginHistoryTurnIdentity,nativeSessionId:String,modelId:String?) { binds++;durable.bindCheckpoint(identity,nativeSessionId,modelId) }
        override suspend fun appendCheckpointPartial(identity:PluginHistoryTurnIdentity,fragments:List<String>) { partials++;durable.appendCheckpointPartial(identity,fragments) }
        override suspend fun appendCheckpointTranscript(identity:PluginHistoryTurnIdentity,updates:List<ExternalTranscriptUpdate>) { transcripts++;durable.appendCheckpointTranscript(identity,updates) }
        override suspend fun finishCheckpoint(request:PluginHistoryFinishRequest):PluginHistoryWindow {
            finishAttempts++
            if(finishAttempts==2 && recoveryGate!=null){recoveryEntered.complete(Unit);recoveryGate!!.await()}
            val result=durable.finishCheckpoint(request)
            if(lose){lose=false;throw IOException("actual durable finish return lost")};return result
        }
    }
    private class Backend(val failedActivity:Boolean):WorkspaceAgentChatBackend {
        val prompts=mutableListOf<String>()
        override suspend fun runTurn(agentId:String,prompt:String,mode:WorkspaceAgentMode,resumeSessionId:String?,onEvent:(WorkspaceAgentEvent)->Unit):WorkspaceAgentTurnResult {
            prompts+=prompt;onEvent(WorkspaceAgentEvent.SessionBound("native-controller"))
            if(failedActivity){onEvent(WorkspaceAgentEvent.Tool("Inspect","done"));onEvent(WorkspaceAgentEvent.Text("partial"));throw IOException("backend fail")}
            return WorkspaceAgentTurnResult(agentId,"native-controller","answer $prompt","end_turn",emptyList(),emptyList())
        }
        override suspend fun prepareSession(agentId:String,resumeSessionId:String?,onEvent:(WorkspaceAgentEvent)->Unit):WorkspaceAgentEvent.Configuration=error("must not prepare")
        override suspend fun newSession(agentId:String,onEvent:(WorkspaceAgentEvent)->Unit):String=error("must not reset")
        override suspend fun selectModel(agentId:String,expectedSessionId:String,modelId:String):WorkspaceAgentEvent.Configuration=error("must not change model")
        override suspend fun cancel(agentId:String,expectedSessionId:String?) {}
    }
    private suspend fun idle(c:ExternalAgentSessionController)=withTimeout(10000){while(c.busy)delay(1)}
    private suspend fun controllerCase(activity:Boolean) {
        val root=root();val store=SqlitePluginConversationHistory(root);val wrapped=LostReturnStore(store);val job=SupervisorJob();val scope=CoroutineScope(currentCoroutineContext()+job)
        val c=ExternalAgentSessionController(scope,history=wrapped,workAdmission={true});val host=Backend(activity)
        val first=WorkspaceAgentPluginCatalog.entries.first();val other=WorkspaceAgentPluginCatalog.entries[1]
        try {
            assertTrue(c.submit(host,first,"first",WorkspaceAgentMode.EDIT));idle(c)
            assertNotNull(c[first.id].persistenceError);val saved=snapshot(root);val writes=Triple(wrapped.binds,wrapped.partials,wrapped.transcripts)
            val draft=listOf(ChatMediaRef("draft","unused/draft","draft","image/png",3,4,5))
            assertFalse(c.submit(host,other,"retained new draft",WorkspaceAgentMode.EDIT,media=draft));idle(c)
            assertEquals(2,wrapped.finishAttempts);assertEquals(writes,Triple(wrapped.binds,wrapped.partials,wrapped.transcripts))
            assertEquals(saved,snapshot(root));assertNull(c[first.id].persistenceError);assertEquals(listOf("first"),host.prompts)
            assertTrue("Only a later explicit Send may run backend",c.submit(host,first,"second",WorkspaceAgentMode.EDIT));idle(c)
            assertEquals(listOf("first","second"),host.prompts);assertNull(c[first.id].persistenceError)
        } finally { c.close();job.cancelAndJoin();store.close() }
    }
    @Test fun actualControllerTextFinishReceiptThenSeparateSendWorks()=runBlocking{controllerCase(false)}
    @Test fun actualControllerActivityFinishReceiptDoesNotRepeatAnyPreparatoryWrites()=runBlocking{controllerCase(true)}
    private suspend fun recoveryRace(action:String) {
        val root=root();val store=SqlitePluginConversationHistory(root);val wrapped=LostReturnStore(store)
        val job=SupervisorJob();val scope=CoroutineScope(currentCoroutineContext()+job)
        val c=ExternalAgentSessionController(scope,history=wrapped,workAdmission={true});val host=Backend(false)
        val spec=WorkspaceAgentPluginCatalog.entries.first();val gate=CompletableDeferred<Unit>()
        try {
            assertTrue(c.submit(host,spec,"first",WorkspaceAgentMode.EDIT));idle(c);assertNotNull(c[spec.id].persistenceError)
            val snapshot=snapshot(root);wrapped.recoveryGate=gate
            assertFalse(c.submit(host,spec,"kept draft",WorkspaceAgentMode.EDIT));withTimeout(10000){wrapped.recoveryEntered.await()}
            assertFalse(c.submit(host,spec,"repeated draft",WorkspaceAgentMode.EDIT));c.newSession(host,spec)
            assertEquals(2,wrapped.finishAttempts)
            var exiting:Job?=null
            when(action){"close"->c.close();"stop"->c.cancel(host);"exit"->exiting=scope.launch{c.stopAndCheckpointForExit(host)}}
            yield();assertFalse("Storage cannot close before owned recovery finishes",wrapped.closed)
            if(action!="close")assertTrue(c.busy)
            assertFalse(c.submit(host,spec,"must stay draft",WorkspaceAgentMode.EDIT))
            assertEquals(listOf("first"),host.prompts);gate.complete(Unit)
            if(action=="close")withTimeout(10000){while(!wrapped.closed)delay(1)}
            else {exiting?.join();idle(c);assertNull(c[spec.id].persistenceError);if(action=="exit")c.closeAfterExitCheckpoint()}
            assertEquals(snapshot,snapshot(root));assertEquals(2,wrapped.finishAttempts);assertEquals(listOf("first"),host.prompts)
        }finally{gate.complete(Unit);c.close();job.cancelAndJoin();store.close()}
    }
    @Test fun stopJoinsExactReceiptRecoveryWithoutBackendReplay()=runBlocking{recoveryRace("stop")}
    @Test fun closeJoinsExactReceiptRecoveryBeforeClosingStore()=runBlocking{recoveryRace("close")}
    @Test fun exitJoinsExactReceiptRecoveryAndClosesOnlyAfterReceipt()=runBlocking{recoveryRace("exit")}

    @Test fun newAdmissionRollbackKeepsOriginalReceiptAndMissingReceiptNeverReplaysFinish()=runBlocking {
        val root=root();val store=SqlitePluginConversationHistory(root)
        try {
            val admitted=admit(store);val original=request(admitted.identity);val saved=store.finishCheckpoint(original);val finished=snapshot(root)
            store.lifecycleCheckpoint={if(it=="before-turn-commit")throw IOException("new turn admission rollback")}
            refused{admit(store,admitted.identity.conversationId)};assertEquals(finished,snapshot(root))
            store.lifecycleCheckpoint={};assertEquals(saved,store.finishCheckpoint(original))
            SQLiteDatabase.openDatabase(database(root).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("UPDATE conversations SET finish_digest=NULL WHERE id=?",arrayOf(admitted.identity.conversationId))
            }
            val corrupt=snapshot(root);refused{store.finishCheckpoint(original)};assertEquals(corrupt,snapshot(root))
        }finally{store.lifecycleCheckpoint={};store.close()}
    }

}
