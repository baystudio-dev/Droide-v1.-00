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
class AgentServiceSourceAliasTest {
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
    private fun write(id:String,path:String,text:String)=Call(id,"write",buildJsonObject { put("path",path);put("content",text) })
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

    @Test(timeout=90000) fun mixedLargeWriteThenSmallReadPreservesExactNativeAndOrderedResults()=runBlocking {
        val root=Files.createTempDirectory("mixed-native-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val text="alpha line\n".repeat(20000);var writes=0
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",text),read("b","a.txt"))) else done(f)}
            val a=agent(root,FileRepository(root,onMutation={if(it is FileRepository.Mutation.Write)writes++}))
            try { runBlocking { withTimeout(60000){a.chat("Write and read the file",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),1,writes);assertEquals(text,root.resolve("a.txt").readText())
                val messages=f.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
                val calls=messages.first { it["tool_calls"]!=null }.getValue("tool_calls").jsonArray
                assertEquals(text,Json.parseToJsonElement(calls[0].jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject.getValue("content").jsonPrimitive.content)
                assertEquals(listOf("a","b"),messages.filter { it["role"]==JsonPrimitive("tool") }.map { it.getValue("tool_call_id").jsonPrimitive.content })
                assertTrue(messages.last { it["role"]==JsonPrimitive("tool") }.getValue("content").jsonPrimitive.content.contains("alpha"))
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));assertTrue(saved.typedChat!!.exact.any { it is TypedChatEntry.OwnedAssistant && it.callIds==listOf("a","b") })
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun multipleLargeWritesRetainIndependentResultsAndRestore()=runBlocking {
        val root=Files.createTempDirectory("mixed-multi-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val first="first".repeat(30000);val second="second".repeat(30000);var writes=0
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",first),write("b","b.txt",second),read("c","b.txt"))) else done(f)}
            val a=agent(root,FileRepository(root,onMutation={if(it is FileRepository.Mutation.Write)writes++}))
            try { runBlocking { withTimeout(60000){a.chat("Write both files then inspect the second",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),2,writes);assertEquals(first,root.resolve("a.txt").readText());assertEquals(second,root.resolve("b.txt").readText())
                val messages=f.requests.last().getValue("messages").jsonArray.map { it.jsonObject }
                assertEquals(listOf("a","b","c"),messages.filter { it["role"]==JsonPrimitive("tool") }.map { it.getValue("tool_call_id").jsonPrimitive.content })
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));a.loadSession(saved)
                withTimeout(30000){a.chat("Confirm without modifying files",f.config,maxTurns=1)};assertEquals(2,writes)
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=60000) fun unsupportedLaterLargeSchemaPreventsEarlierWrite()=runBlocking {
        val root=Files.createTempDirectory("mixed-invalid-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            f.response={_,_,_->response(f,listOf(write("a","a.txt","x".repeat(150000)),read("b","a.txt","y".repeat(150000))))}
            val a=agent(root)
            try { runBlocking { a.chat("Write and inspect",f.config,maxTurns=2)
                assertFalse(root.resolve("a.txt").exists());assertEquals(1,f.requests.size)
                assertTrue(a.messages.value.toString(),a.messages.value.any { it.content.contains("no tool in this batch was executed") })
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun stopAfterFirstRenameSettlesProofAndNeverRunsSecondWrite()=runBlocking {
        val root=Files.createTempDirectory("mixed-stop-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            val first="first".repeat(30000);val second="second".repeat(30000)
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",first),write("b","b.txt",second))) else done(f)}
            lateinit var run:Job;var writes=0
            val a=agent(root,FileRepository(root,onMutation={if(it is FileRepository.Mutation.Write){writes++;run.cancel(CancellationException("Stop after first publication"))}}))
            try { runBlocking {
                run=launch(start=CoroutineStart.LAZY){a.chat("Write two files",f.config,maxTurns=2)};run.start();withTimeout(60000){run.join()}
                assertEquals(a.messages.value.toString(),1,writes);assertTrue(root.resolve("a.txt").exists());assertFalse(root.resolve("b.txt").exists())
                assertTrue(a.messages.value.toString(),a.messages.value.any { it.streamPart?.output?.contains("Local write verified") == true })
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));a.loadSession(saved)
                withTimeout(30000){a.chat("Continue without repeating completed writes",f.config,maxTurns=1)}
                assertEquals(1,writes);assertEquals(2,f.requests.size)
                val results=f.requests.last().getValue("messages").jsonArray.map { it.jsonObject }.filter { it["role"]==JsonPrimitive("tool") }
                assertEquals(listOf("a","b"),results.map { it.getValue("tool_call_id").jsonPrimitive.content })
                assertTrue(results[0].getValue("content").jsonPrimitive.content.contains("Do not repeat"))
                assertTrue(results[1].getValue("content").jsonPrimitive.content.contains("No write was performed"))
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test(timeout=90000) fun perCallPermissionDenialDoesNotGrantSecondWriteOrHideItsResult()=runBlocking {
        val root=Files.createTempDirectory("mixed-deny-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            val text="reviewed".repeat(20000)
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",text),write("b","b.txt",text),read("c","a.txt"))) else done(f)}
            val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("edit","b.txt",PermEffect.DENY)))
            val a=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),perms=perms,documentAuthority=docs,maxSubagentDepth=0)
            try { runBlocking { withTimeout(60000){a.chat("Write both paths then inspect",f.config,maxTurns=2)}
                assertTrue(a.messages.value.toString(),root.resolve("a.txt").exists());assertFalse(root.resolve("b.txt").exists())
                val results=f.requests.last().getValue("messages").jsonArray.map { it.jsonObject }.filter { it["role"]==JsonPrimitive("tool") }
                assertEquals(listOf("a","b","c"),results.map { it.getValue("tool_call_id").jsonPrimitive.content })
                assertTrue(results[1].getValue("content").jsonPrimitive.content.contains("DENIED"))
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun stopAtFirstApprovalSettlesAllProvenUnstartedCalls()=runBlocking {
        val root=Files.createTempDirectory("mixed-ask-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            val text="reviewed".repeat(20000)
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",text),write("b","b.txt",text))) else done(f)}
            val a=agent(root,effect=PermEffect.ASK)
            try { runBlocking {
                val run=launch { a.chat("Write after review",f.config,maxTurns=2) }
                val request=withTimeout(30000){a.approvals.pending.filterNotNull().first()}
                assertNotNull(request.proposedContent);assertFalse(root.resolve("a.txt").exists())
                withTimeout(30000){run.cancelAndJoin()}
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));a.loadSession(saved)
                withTimeout(30000){a.chat("Confirm cancellation only",f.config,maxTurns=1)}
                assertFalse(root.resolve("a.txt").exists());assertFalse(root.resolve("b.txt").exists());assertEquals(2,f.requests.size)
                val results=f.requests.last().getValue("messages").jsonArray.map { it.jsonObject }.filter { it["role"]==JsonPrimitive("tool") }
                assertEquals(listOf("a","b"),results.map { it.getValue("tool_call_id").jsonPrimitive.content })
                assertTrue(results.all { it.getValue("content").jsonPrimitive.content.contains("No write was performed") })
            } } finally { a.shutdown() }
        } } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test(timeout=90000) fun largeWriteThenLargeEditUsesPostWriteSnapshotAndActualToolName()=runBlocking {
        val root=Files.createTempDirectory("mixed-edit-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val original="original-".repeat(18000);val replacement="new-".repeat(40000)
            val edit=Call("b","edit",buildJsonObject {put("path","a.txt");put("search",original);put("replace",replacement)})
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",original),edit,read("c","a.txt"))) else done(f)}
            val a=agent(root)
            try { runBlocking { withTimeout(60000){a.chat("Write then replace exact content",f.config,maxTurns=2)} }
                assertEquals(a.messages.value.toString(),replacement,root.resolve("a.txt").readText())
                val messages=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}
                val calls=messages.first {it["tool_calls"]!=null}.getValue("tool_calls").jsonArray
                assertEquals("edit",calls[1].jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                assertEquals(listOf("a","b","c"),messages.filter {it["role"]==JsonPrimitive("tool")}.map {it.getValue("tool_call_id").jsonPrimitive.content})
            } finally {a.shutdown()}
        } } finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun versionlessLargeLiveWriteIsRejectedWithoutDiskFallback()=runBlocking {
        val root=Files.createTempDirectory("single-live-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            val files=FileRepository(root);root.resolve("a.txt").writeText("saved")
            val state=com.baystudio.droide.ui.EditorWorkspaceState().apply {completeRecovery()}
            val authority=com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined)
            val document=state.document("a.txt");runBlocking { document.ensureLoaded(files) };document.capture("dirty")
            var nativeText="dirty";var mutations=0
            document.nativeMutationBinding=com.baystudio.droide.ui.EditorNativeMutationBinding(Any(),{true},{},{old,new,publish->
                check(old==nativeText);nativeText=new;mutations++;publish()
            })
            val text="live".repeat(40000)
            f.response={i,_,_->if(i==1)response(f,listOf(write("a","a.txt",text))) else done(f)}
            val a=AgentService(files,AgentWorkflowTerminal(),GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=authority,maxSubagentDepth=0)
            try {runBlocking { withTimeout(60000){a.chat("Update open buffer",f.config,maxTurns=2)} }
                assertEquals(a.messages.value.toString(),"dirty",document.content);assertEquals("dirty",nativeText)
                assertEquals(0,mutations);assertEquals("saved",root.resolve("a.txt").readText())
                val result=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.single {it["role"]==JsonPrimitive("tool")}
                assertTrue(result.getValue("content").jsonPrimitive.content.contains("expected_document_id="))
            } finally {a.shutdown()}
        } } finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun explicitLargeLiveEditPreservesDiskAndOriginalToolIdentity()=runBlocking {
        val root=Files.createTempDirectory("single-live-").toFile()
        try { withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) { f ->
            val files=FileRepository(root);root.resolve("a.txt").writeText("saved")
            val state=com.baystudio.droide.ui.EditorWorkspaceState().apply {completeRecovery()}
            val authority=com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority(state,Dispatchers.Unconfined)
            val document=state.document("a.txt");runBlocking { document.ensureLoaded(files) };document.capture("dirty")
            var nativeText="dirty";var mutations=0
            document.nativeMutationBinding=com.baystudio.droide.ui.EditorNativeMutationBinding(Any(),{true},{},{old,new,publish->
                check(old==nativeText);nativeText=new;mutations++;publish()
            })
            val text="live".repeat(40000)
            f.response={i,_,_->if(i==1)response(f,listOf(Call("a","edit",buildJsonObject {put("path","a.txt");put("search","dirty");put("replace",text);put("expected_document_id",document.documentId);put("expected_revision",document.revision);put("expected_change_version",document.changeVersion)}))) else done(f)}
            val a=AgentService(files,AgentWorkflowTerminal(),GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW))),documentAuthority=authority,maxSubagentDepth=0)
            try {runBlocking { withTimeout(60000){a.chat("Update open buffer",f.config,maxTurns=2)} }
                assertEquals(a.messages.value.toString(),text,document.content);assertEquals(text,nativeText)
                assertEquals(1,mutations);assertEquals("saved",root.resolve("a.txt").readText())
                val result=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.single {it["role"]==JsonPrimitive("tool")}
                assertTrue(result.getValue("content").jsonPrimitive.content.contains("not saved to disk"))
            } finally {a.shutdown()}
        } } finally {PathSecurity.deleteTreeNoFollow(root)}
    }

}
