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
class AgentServiceSourceArtifactTest {
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
    // This suite exercises source publication with an explicit tool profile, without discovery steps.
    private fun agent(root:java.io.File,files:FileRepository=FileRepository(root),effect:PermEffect=PermEffect.ALLOW)=
        AgentService(files,AgentWorkflowTerminal(),GitManager(root),perms=PermissionEngine(listOf(PermRule("*","*",effect)),
            toolRestrictions = listOf(setOf("artifact", "write_file", "read_file", "edit_file"))),
            documentAuthority=docs,maxSubagentDepth=0)

    private fun artifact(id:String,type:String,text:String)=Call(id,"artifact",buildJsonObject {put("type",type);put("content",text)})
    private fun published(root:java.io.File)=root.resolve(".droide/artifacts").listFiles()?.filter {it.name.endsWith(".md")} ?: emptyList()

    @Test(timeout=90000) fun largeArtifactPreservesExactBytesHistoryAndDoesNotRepeatOnRestore()=runBlocking {
        val root=Files.createTempDirectory("source-artifact-service-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val text="artifact content\n".repeat(40000)
            val type="  report  "
            f.response={i,_,_->if(i==1)response(f,listOf(artifact("a",type,text))) else done(f)}
            val a=agent(root)
            try {withTimeout(60000){a.chat("Create the artifact",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),1,published(root).size)
                assertEquals("# report\n\n$text",published(root).single().readText())
                val messages=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}
                val call=messages.first {it["tool_calls"]!=null}.getValue("tool_calls").jsonArray.single().jsonObject
                assertEquals("artifact",call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
                val args=Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject
                assertEquals(type,args.getValue("type").jsonPrimitive.content);assertEquals(text,args.getValue("content").jsonPrimitive.content)
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)));a.loadSession(saved)
                withTimeout(30000){a.chat("Confirm only",f.config,maxTurns=1)}
                assertEquals(1,published(root).size)
                val current=checkNotNull(a.sessions.get(saved.id))
                val artifactIndex=current.messages.indexOfFirst {it.streamPart?.toolCallId=="a"}
                assertTrue(artifactIndex>=0)
                assertNotNull("Artifact row needs an explicit complete fork boundary",current.messages[artifactIndex].forkHistoryEnd)
                val fork=checkNotNull(a.sessions.fork(saved.id,artifactIndex))
                val native=checkNotNull(fork.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first()
                val resources=a.sessions.typedChatResources()
                assertEquals(ToolCallHistoryJournal.State.SETTLED,resources.journal.state(checkNotNull(resources.journal.locate(
                    OwnedResponseContentStore.Owner(fork.id,native.runId,"a"),"artifact"))))
                a.loadSession(fork);withTimeout(30000){a.chat("Continue branch without duplicate",f.config,maxTurns=1)}
                assertEquals(1,published(root).size)
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun originalUnsanitizedTypeIsPermissionResource()=runBlocking {
        val root=Files.createTempDirectory("source-artifact-policy-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val type="  report  ";val text="x".repeat(170000)
            f.response={i,_,_->if(i==1)response(f,listOf(artifact("a",type,text))) else done(f)}
            val perms=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("edit",type,PermEffect.DENY)),
                toolRestrictions = listOf(setOf("artifact", "write_file", "read_file", "edit_file")))
            val a=AgentService(FileRepository(root),AgentWorkflowTerminal(),GitManager(root),perms=perms,documentAuthority=docs,maxSubagentDepth=0)
            try {withTimeout(60000){a.chat("Create denied artifact",f.config,maxTurns=2)}
                assertTrue(published(root).isEmpty())
                val results=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.filter {it["role"]==JsonPrimitive("tool")}
                assertTrue(results.single().getValue("content").jsonPrimitive.content.contains("DENIED"))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun mixedArtifactAndWritePreserveIndependentLogicalResults()=runBlocking {
        val root=Files.createTempDirectory("source-artifact-mixed-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            ModelContextRegistry.remember(f.provider.id,f.model,1_000_000)
            val text="x".repeat(170000)
            f.response={i,_,_->if(i==1)response(f,listOf(artifact("a","report",text),write("b","b.txt",text),read("c","b.txt"))) else done(f)}
            val a=agent(root)
            try {withTimeout(60000){a.chat("Create artifact and file",f.config,maxTurns=2)}
                assertEquals(a.messages.value.toString(),1,published(root).size);assertEquals(text,root.resolve("b.txt").readText())
                val results=f.requests.last().getValue("messages").jsonArray.map {it.jsonObject}.filter {it["role"]==JsonPrimitive("tool")}
                assertEquals(listOf("a","b","c"),results.map {it.getValue("tool_call_id").jsonPrimitive.content})
                assertTrue(results[0].getValue("content").jsonPrimitive.content.startsWith("Created artifact"))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

    @Test(timeout=90000) fun cancelApprovalRetainsExactProposalAndPublishesNothing()=runBlocking {
        val root=Files.createTempDirectory("source-artifact-cancel-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val text="x".repeat(170000)
            f.response={i,_,_->if(i==1)response(f,listOf(artifact("a","report",text))) else done(f)}
            val a=agent(root,effect=PermEffect.ASK)
            try {
                val task=launch {a.chat("Create after approval",f.config,maxTurns=2)}
                val approval=withTimeout(30000){a.approvals.pending.filterNotNull().first()}
                val bytes=java.io.ByteArrayOutputStream();checkNotNull(approval.proposedContent).export(bytes)
                assertEquals(text,bytes.toString("UTF-8"));val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                task.cancelAndJoin();assertTrue(published(root).isEmpty());a.loadSession(saved)
                withTimeout(30000){a.chat("Confirm cancellation only",f.config,maxTurns=1)}
                assertTrue(published(root).isEmpty())
                assertTrue(f.requests.last().toString().contains("No artifact was published"))
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test(timeout=90000) fun malformedLaterArtifactRetainsPrivateRecoveryAndPreventsEarlierWrite()=runBlocking {
        val parent=Files.createTempDirectory("source-artifact-malformed-").toFile()
        val root=parent.resolve("workspace").apply{mkdir()}
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val store=OwnedResponseContentStore(parent.resolve("owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val journal=ToolCallHistoryJournal(parent.resolve("calls"),store)
            val recovery=OwnedChatRecoveryJournal(parent.resolve("recovery"))
            val resources=TypedChatResources(store,journal,CanonicalChatTurnRegistry(parent.resolve("turns"),store,journal),recovery)
            val text="x".repeat(170000)
            val malformed=Call("b","artifact",buildJsonObject {put("type","report");put("content",text);put("unsupported",true)})
            f.response={_,_,_->response(f,listOf(write("a","a.txt",text),artifact("good","report",text),malformed))}
            val a=agent(root);a.sessions.typedChatResourcesFactory={resources}
            try {withTimeout(60000){a.chat("Write then create artifact",f.config,maxTurns=2)}
                assertFalse(root.resolve("a.txt").exists());assertTrue(published(root).isEmpty())
                val folder=parent.resolve("recovery").listFiles()!!.single {it.isDirectory && !it.name.startsWith(".")}
                val manifest=Json.parseToJsonElement(folder.resolve("manifest.json").readText()).jsonObject
                val owner=OwnedResponseContentStore.Owner(manifest.getValue("session").jsonPrimitive.content,manifest.getValue("run").jsonPrimitive.content,manifest.getValue("response").jsonPrimitive.content)
                val target=ProviderReplayTarget(manifest.getValue("provider").jsonPrimitive.content,manifest.getValue("model").jsonPrimitive.content,ProviderReplayTarget.Protocol.OPENAI_CHAT,manifest.getValue("endpoint").jsonPrimitive.content)
                val retained=recovery.reopen(folder.name,owner,target)
                assertTrue(retained.stages.all {it.disposition=="UNRESOLVED"})
                val bStages=retained.stages.filter {it.callId=="b"}
                assertTrue(bStages.isNotEmpty())
                val bContent=bStages.map {store.authorize(owner.copy(callId="b")).open(OwnedResponseContentStore.Handle.parse(it.handle))}
                assertTrue(bContent.any {it.kind==OwnedResponseContentStore.Kind.TOOL_ARGUMENTS})
                val earlier=retained.stages.filter {it.callId=="good"}
                assertTrue(earlier.size>=2)
                val earlierContent=earlier.map {store.authorize(owner.copy(callId="good")).open(OwnedResponseContentStore.Handle.parse(it.handle))}
                assertTrue(earlierContent.any {it.kind==OwnedResponseContentStore.Kind.TOOL_CONTENT})
                assertTrue(retained.stages.any {it.callId==null})
                assertFalse(parent.resolve("calls").listFiles().orEmpty().any {it.extension=="json"})
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(parent)}
    }

    @Test(timeout=90000) fun successfulPreflightReturnCancellationRetainsDerivedOwnershipWithoutExecution()=runBlocking {
        val parent=Files.createTempDirectory("source-artifact-return-cancel-").toFile()
        val root=parent.resolve("workspace").apply{mkdir()}
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val store=OwnedResponseContentStore(parent.resolve("owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            val journal=ToolCallHistoryJournal(parent.resolve("calls"),store)
            val recovery=OwnedChatRecoveryJournal(parent.resolve("recovery"))
            val resources=TypedChatResources(store,journal,CanonicalChatTurnRegistry(parent.resolve("turns"),store,journal),recovery)
            val text="return boundary content".repeat(8000)
            f.response={_,_,_->response(f,listOf(artifact("a","report",text)))}
            val a=agent(root);a.sessions.typedChatResourcesFactory={resources}
            var returned=false;lateinit var invocation:Job
            a.publicationBoundaryProbe={boundary->
                if(boundary==AgentService.PublicationBoundary.SOURCE_PREFLIGHT_RETURN) {
                    returned=true
                    invocation.cancel(CancellationException("Stop after successful preparation, before IO return delivery"))
                }
            }
            try {
                invocation=launch(start=CoroutineStart.LAZY) {a.chat("Create artifact",f.config,maxTurns=2)}
                invocation.start();withTimeout(60000){invocation.join()}
                assertTrue(returned);assertTrue(invocation.isCancelled);assertTrue(published(root).isEmpty())
                assertFalse(parent.resolve("calls").listFiles().orEmpty().any {it.extension=="json"})
                val folder=parent.resolve("recovery").listFiles()!!.single {it.isDirectory && !it.name.startsWith(".")}
                val manifest=Json.parseToJsonElement(folder.resolve("manifest.json").readText()).jsonObject
                val owner=OwnedResponseContentStore.Owner(manifest.getValue("session").jsonPrimitive.content,manifest.getValue("run").jsonPrimitive.content,manifest.getValue("response").jsonPrimitive.content)
                val target=ProviderReplayTarget(manifest.getValue("provider").jsonPrimitive.content,manifest.getValue("model").jsonPrimitive.content,ProviderReplayTarget.Protocol.OPENAI_CHAT,manifest.getValue("endpoint").jsonPrimitive.content)
                val retained=recovery.reopen(folder.name,owner,target)
                assertTrue(retained.stages.all {it.disposition=="UNRESOLVED"})
                val callSession=store.authorize(owner.copy(callId="a"))
                val stages=retained.stages.filter {it.callId=="a"}.map {callSession.openCompleted(OwnedResponseContentStore.Handle.parse(it.handle)).content}
                assertTrue(stages.any {it.kind==OwnedResponseContentStore.Kind.TOOL_ARGUMENTS})
                val content=stages.single {it.kind==OwnedResponseContentStore.Kind.TOOL_CONTENT}
                assertEquals(text,callSession.openBytes(content.handle).bufferedReader().use {it.readText()})
                assertTrue(retained.stages.any {it.callId==null})
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(parent)}
    }

    @Test(timeout=90000) fun largeWriteRestoreRoundTripPreservesForkAnchorAndPrivateReceipt()=runBlocking {
        val root=Files.createTempDirectory("source-write-restored-fork-").toFile()
        try {withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS) {f->runBlocking {
            val text="write once".repeat(17000);var writes=0
            f.response={i,_,_->if(i==1)response(f,listOf(write("w","a.txt",text))) else done(f)}
            val a=agent(root,FileRepository(root,onMutation={if(it is FileRepository.Mutation.Write)writes++}))
            try {
                withTimeout(60000){a.chat("Write file",f.config,maxTurns=2)}
                val saved=checkNotNull(a.sessions.get(checkNotNull(a.currentSessionId)))
                a.loadSession(saved);withTimeout(30000){a.chat("Confirm only",f.config,maxTurns=1)}
                val current=checkNotNull(a.sessions.get(saved.id));val index=current.messages.indexOfFirst {it.streamPart?.toolCallId=="w"}
                assertTrue(index>=0);assertNotNull(current.messages[index].forkHistoryEnd)
                val fork=checkNotNull(a.sessions.fork(current.id,index))
                val native=checkNotNull(fork.typedChat).exact.filterIsInstance<TypedChatEntry.OwnedAssistant>().first()
                val resources=a.sessions.typedChatResources()
                assertEquals(ToolCallHistoryJournal.State.SETTLED,resources.journal.state(checkNotNull(resources.journal.locate(
                    OwnedResponseContentStore.Owner(fork.id,native.runId,"w"),"write_file"))))
                a.loadSession(fork);withTimeout(30000){a.chat("Continue without rewriting",f.config,maxTurns=1)}
                assertEquals(1,writes);assertEquals(text,root.resolve("a.txt").readText())
            } finally {a.shutdown()}
        }}} finally {PathSecurity.deleteTreeNoFollow(root)}
    }

}
