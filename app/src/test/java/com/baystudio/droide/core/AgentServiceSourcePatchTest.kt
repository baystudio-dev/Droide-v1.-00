package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class AgentServiceSourcePatchTest {
    @org.junit.Before fun initialize() {
        ApplicationWorkAdmission.initialize(org.robolectric.RuntimeEnvironment.getApplication())
        ApplicationWorkAdmission.reopenFromUser()
    }
    private val docs=object:WorkspaceDocumentAuthority {
        override suspend fun snapshot(path:String):WorkspaceDocumentSnapshot?=null
        override suspend fun snapshots():List<WorkspaceDocumentSnapshot> = emptyList()
        override suspend fun observeRecovery(path:String)=WorkspaceRecoveryEditorObservation(emptyList(),emptySet(),{})
        override suspend fun commitDiskOnly(path:String,action:()->Unit)=action()
    }
    private data class Call(val id:String,val name:String,val args:JsonObject)
    private fun write(id:String,path:String,text:String)=Call(id,"write_file",buildJsonObject { put("path",path);put("content",text) })
    private fun read(id:String,path:String,padding:String?=null)=Call(id,"read_file",buildJsonObject { put("path",path);padding?.let { put("padding",it) } })
    private fun response(f:ProviderStreamFixture,calls:List<Call>)=buildString {
        calls.forEachIndexed { i,call -> call.args.toString().chunked(12000).forEachIndexed { fragment,part ->
            append(f.event(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject {
                put("delta",buildJsonObject { put("tool_calls",buildJsonArray { add(buildJsonObject {
                    put("index",i);if(fragment==0){put("id",call.id);put("type","function")}
                    put("function",buildJsonObject { if(fragment==0)put("name",call.name);put("arguments",part) })
                }) }) })
            }) }) }.toString()))
        } }
        append(f.event("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""));append("data: [DONE]\n\n")
    }
    private fun done(f:ProviderStreamFixture)=f.event("""{"choices":[{"delta":{"content":"Completed once"},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
    private fun agent(root:java.io.File,files:FileRepository=FileRepository(root),effect:PermEffect=PermEffect.ALLOW)=
        AgentService(files,AgentWorkflowTerminal(),GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",effect))),
            documentAuthority=docs,maxSubagentDepth=0)

    private fun patch(id:String,text:String)=Call(id,"apply_patch",buildJsonObject {put("patchText",text)})
    @Test(timeout=90000) fun largeCanonicalMixedPatchPreservesNativeReplayAndPrivateForkReceipt()=runBlocking {
        val root=Files.createTempDirectory("source-patch-service-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            root.resolve("update.txt").writeText("before\n");root.resolve("delete.txt").writeText("delete me")
            val content="a".repeat(170000)
            val text="*** Begin Patch\n*** Add File: new.txt\n+$content\n*** Update File: update.txt\n@@\n-before\n+after\n*** Delete File: delete.txt\n*** End Patch"
            f.response={i,_,_->if(i==1)response(f,listOf(patch("p",text))) else done(f)}
            val a=agent(root)
            try {
                withTimeout(60000){a.chat("Apply reviewed patch",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),content+"\n",root.resolve("new.txt").readText())
                assertEquals("after\n",root.resolve("update.txt").readText());assertFalse(root.resolve("delete.txt").exists())
                val messages=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}
                val call=messages.first {it["tool_calls"]!=null}.getValue("tool_calls").jsonArray.single().jsonObject
                val args=Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject
                assertEquals("apply_patch",call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                assertEquals(text,args.getValue("patchText").jsonPrimitive.content)
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));a.loadSession(saved)
                withTimeout(30000){a.chat("Confirm only",f.config,maxTurns=1)}
                val current=checkNotNull(a.sessions.get(saved.id));val index=current.messages.indexOfFirst {it.streamPart?.toolCallId=="p"}
                assertTrue(index>=0);assertNotNull(current.messages[index].forkHistoryEnd)
                val fork=checkNotNull(a.sessions.fork(saved.id,index));val native=checkNotNull(fork.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first()
                val journal=a.sessions.typedChatResources().journal
                assertEquals(ToolCallHistoryJournal.State.SETTLED,journal.state(checkNotNull(journal.locate(OwnedResponseContentStore.Owner(fork.id,native.runId,"p"),"apply_patch"))))
                a.loadSession(fork);withTimeout(30000){a.chat("No new changes",f.config,maxTurns=1)}
                assertEquals(content+"\n",root.resolve("new.txt").readText())
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun largeLegacySearchReplaceAndMoveUseExistingDialect()=runBlocking {
        val root=Files.createTempDirectory("source-patch-legacy-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            root.resolve("old.txt").writeText("needle")
            val replacement="z".repeat(170000)
            val text="*** Update File: old.txt\n*** Search: needle\n*** Replace: $replacement\n*** Move to: moved.txt"
            f.response={i,_,_->if(i==1)response(f,listOf(patch("p",text))) else done(f)}
            val a=agent(root)
            try {withTimeout(60000){a.chat("Update and move",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),replacement,root.resolve("moved.txt").readText());assertFalse(root.resolve("old.txt").exists())
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun malformedLaterPatchPreventsEarlierMixedWrite()=runBlocking {
        val root=Files.createTempDirectory("source-patch-invalid-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val text="*** Begin Patch\n*** Add File: never.txt\n+"+"x".repeat(170000)
            f.response={_,_,_->response(f,listOf(write("w","earlier.txt","no"),patch("p",text)))}
            val a=agent(root)
            try {withTimeout(60000){a.chat("Invalid mixed patch",f.config,maxTurns=2)}
                assertFalse(root.resolve("earlier.txt").exists());assertFalse(root.resolve("never.txt").exists())
                assertTrue(a.sessions.typedChatResources().journal.locate(OwnedResponseContentStore.Owner("missing","missing","p"),"apply_patch")==null)
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun deniedMoveDestinationPreventsAllEffects()=runBlocking {
        val root=Files.createTempDirectory("source-patch-deny-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            root.resolve("old.txt").writeText("needle")
            val text="*** Update File: old.txt\n*** Search: needle\n*** Replace: "+"z".repeat(170000)+"\n*** Move to: denied.txt"
            f.response={i,_,_->if(i==1)response(f,listOf(patch("p",text))) else done(f)}
            val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("edit","denied.txt",PermEffect.DENY)))
            val a=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),perms=perms,documentAuthority=docs,maxSubagentDepth=0)
            try {withTimeout(60000){a.chat("Move blocked",f.config,maxTurns=2)}
                assertEquals("needle",root.resolve("old.txt").readText());assertFalse(root.resolve("denied.txt").exists())
                val results=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.filter {it["role"]==JsonPrimitive("tool")}
                assertTrue(results.single().getValue("content").jsonPrimitive.content.contains("DENIED"))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
}
