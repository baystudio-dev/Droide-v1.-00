package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Original manager/transport with authored in-memory peers, never Prisma or project execution. */
class LspPrismaIntegrationTest {
    @Test(timeout=10000) fun unsupportedOpenRejectsBeforeGrantDiscoveryAndFallback()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.prisma"); f.manager.didOpen("a.prisma",NEXT)
            assertEquals(LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8,f.manager.documentSupportStatus("a.prisma")!!.phase)
            assertTrue(f.manager.serverStatus("a.prisma").contains("Next"))
            for(op in listOf<suspend ()->Any?>({f.manager.enableProjectAnalysis("a.prisma")},{f.manager.completionItems("a.prisma",1,1)},
                {f.manager.diagnose("a.prisma")},{f.manager.workspaceSymbols("a.prisma","User")},{f.manager.formatDocument("a.prisma")},
                {f.manager.resolveCodeAction("a.prisma",LspCodeAction("old",edit=LspWorkspaceEdit(emptyList())))})) {
                assertTrue(runCatching {op()}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            }
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries);assertEquals(0,f.fallbackCalls)
            assertTrue(f.manager.signatureHelpTriggers("a.prisma").triggerCharacters.isEmpty())
        }
    }
    @Test(timeout=10000) fun unknownFileIsClassifiedBeforeEnablePrompt()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma").writeText(NEXT)
            assertEquals(LspDocumentSupportPhase.INSPECTION_PENDING,f.manager.documentSupportStatus("a.prisma")!!.phase)
            assertTrue(runCatching {f.manager.enableProjectAnalysis("a.prisma")}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun passiveLegacyStillRequiresExplicitProjectConsent()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY)
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.host.discoveries);assertEquals(0,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun exactStartupUsesStdioScopedCwdUtf16AndNoSettings()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("nested/a.prisma");f.manager.didOpen("nested/a.prisma",LEGACY)
            f.manager.enableProjectAnalysis("nested/a.prisma");assertEquals("fixture",f.manager.completionItems("nested/a.prisma",1,1).single().label)
            val p=f.host.peers.single();assertEquals(listOf("/usr/bin/prisma-language-server","--stdio"),p.argv)
            assertEquals("/workspace/nested",f.host.cwd.single())
            val init=p.params("initialize");assertEquals(JsonPrimitive("file:///workspace/nested"),init["rootUri"])
            assertEquals(JsonPrimitive(false),init["capabilities"]!!.jsonObject["workspace"]!!.jsonObject["configuration"])
            assertEquals(JsonPrimitive(true),init["capabilities"]!!.jsonObject["textDocument"]!!.jsonObject["definition"]!!.jsonObject["linkSupport"])
            assertEquals(buildJsonObject{},init["initializationOptions"])
            assertFalse(p.observed.any{it["method"]==JsonPrimitive("workspace/didChangeConfiguration")})
            assertEquals(JsonPrimitive("prisma"),p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"])
        }
    }
    @Test(timeout=10000) fun authoritativeEditorTextWinsSuppliedTextAndDisk()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma").writeText(LEGACY);f.manager.didOpen("a.prisma",NEXT)
            assertEquals(LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8,f.manager.documentSupportStatus("a.prisma",LEGACY)!!.phase)
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1,null,LEGACY)}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertEquals(0,f.host.discoveries)
            f.manager.didChange("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma")
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1,null,NEXT)}.isFailure)
            assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun explicitUnsupportedFileInspectionPublishesStatusWithoutExecution()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma").writeText(NEXT)
            val revision=f.manager.statusRevision.value
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertEquals(LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8,f.manager.documentSupportStatus("a.prisma")!!.phase)
            assertTrue(f.manager.statusRevision.value>revision)
            assertEquals(0,f.approvalCalls);assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun unsupportedFileBackedInspectionRetiresItsRecordedSoleOwner()=runBlocking<Unit> {
        Fixture().use {f->
            val file=f.file("a.prisma");file.writeText(LEGACY)
            f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val old=f.host.peers.single();assertTrue(old.isAlive)
            file.writeText(NEXT)
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertFalse(old.isAlive);assertTrue(f.manager.projectAnalysisStatus("a.prisma")!!.canRevoke)
            assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun unsupportedFileBackedInspectionClosesOldUriAndPreservesOpenSibling()=runBlocking<Unit> {
        Fixture().use {f->
            val file=f.file("a.prisma");file.writeText(LEGACY)
            f.file("b.prisma");f.manager.didOpen("b.prisma",LEGACY)
            f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val owner=f.host.peers.single();file.writeText(NEXT)
            assertTrue(runCatching {f.manager.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertTrue(owner.isAlive)
            assertEquals(JsonPrimitive("file:///workspace/a.prisma"),owner.params("textDocument/didClose")["textDocument"]!!.jsonObject["uri"])
            assertEquals("fixture",f.manager.completionItems("b.prisma",1,1).single().label)
            assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun generationChangeClosesRecordedOwnerClearsDiagnosticsAndKeepsGrant()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val old=f.host.peers.single();old.publish("file:///workspace/a.prisma","old")
            awaitUntil{f.manager.diagnostics.value.values.flatten().isNotEmpty()}
            f.manager.didChange("a.prisma",NEXT)
            assertTrue(f.manager.diagnostics.value.isEmpty());assertFalse(old.isAlive)
            assertTrue(f.manager.projectAnalysisStatus("a.prisma")!!.canRevoke)
            assertTrue(f.manager.projectAnalysisStatus("a.prisma")!!.enabled)
            f.manager.didChange("a.prisma",LEGACY);f.manager.completionItems("a.prisma",1,1)
            assertEquals(1,f.approvalCalls);assertEquals(2,f.host.peers.size)
        }
    }
    @Test(timeout=10000) fun unsupportedSiblingIsNotReplayedAndLegacySiblingRemainsUsable()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.file("b.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.didOpen("b.prisma",NEXT)
            f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val p=f.host.peers.single();assertEquals(1,p.observed.count{it["method"]==JsonPrimitive("textDocument/didOpen")})
            f.manager.didChange("b.prisma",LEGACY);f.manager.completionItems("b.prisma",1,1)
            f.manager.didChange("a.prisma",NEXT);assertTrue(p.isAlive)
            assertEquals("fixture",f.manager.completionItems("b.prisma",1,1).single().label);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun heldInitializeIsCanceledOnUnsupportedTransition()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma");f.host.holdInitialize=true
            val pending=async{runCatching{f.manager.completionItems("a.prisma",1,1)}}
            awaitUntil{f.host.peers.isNotEmpty()};val p=f.host.peers.single();p.next("initialize")
            f.manager.didChange("a.prisma",NEXT);awaitUntil{!p.isAlive};withTimeout(2000){pending.await()}
            f.host.holdInitialize=false;f.manager.didChange("a.prisma",LEGACY)
            assertEquals("fixture",f.manager.completionItems("a.prisma",1,1).single().label);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun heldPreparationIsCanceledBeforeProcessConstruction()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma");f.host.prepareGate=CompletableDeferred()
            val pending=async{runCatching{f.manager.completionItems("a.prisma",1,1)}}
            withTimeout(2000){f.host.prepareEntered.await()};f.manager.didChange("a.prisma",NEXT)
            awaitUntil{f.host.prepareCanceled};withTimeout(2000){pending.await()};assertTrue(f.host.peers.isEmpty())
        }
    }
    @Test(timeout=10000) fun heldApprovalCannotGrantAfterUnsupportedTransition()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.approvalGate=CompletableDeferred()
            val pending=async{f.manager.enableProjectAnalysis("a.prisma")};awaitUntil{f.approvalCalls==1}
            f.manager.didChange("a.prisma",NEXT);f.approvalGate!!.complete(Unit)
            assertTrue(pending.await().contains("disabled"));assertFalse(f.manager.projectAnalysisStatus("a.prisma")!!.enabled)
            assertEquals(0,f.host.discoveries)
        }
    }
    @Test(timeout=10000) fun heldResultCannotEscapeAfterNextAndBackToLegacy()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.file("b.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.didOpen("b.prisma",LEGACY)
            f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val p=f.host.peers.single();p.holdCompletion=true
            val pending=async{f.manager.completionItems("a.prisma",1,1)};val request=p.next("textDocument/completion",1)
            f.manager.didChange("a.prisma",NEXT);f.manager.didChange("a.prisma",LEGACY)
            p.reply(request,Json.parseToJsonElement("[{\"label\":\"obsolete\"}]"));assertTrue(pending.await().isEmpty())
        }
    }
    @Test(timeout=10000) fun guardedCompletionRejectsTextAndRevokeWithoutApplying()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma")
            val item=f.manager.completionItems("a.prisma",1,1).single();assertNotNull(item.applicationGuard);assertTrue(item.applicationGuard!!.isCurrent())
            f.manager.didChange("a.prisma",NEXT);assertFalse(item.applicationGuard!!.isCurrent())
            f.manager.didChange("a.prisma",LEGACY);val fresh=f.manager.completionItems("a.prisma",1,1).single()
            f.manager.revokeProjectAnalysis("a.prisma");assertFalse(fresh.applicationGuard!!.isCurrent())
        }
    }
    @Test(timeout=10000) fun unknownSiblingDiagnosticsCannotInheritLegacySupport()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.file("b.prisma");f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val p=f.host.peers.single();p.publish("file:///workspace/b.prisma","unknown")
            delay(60);assertTrue(f.manager.diagnostics.value.isEmpty())
            p.publish("file:///workspace/a.prisma","known");awaitUntil{f.manager.diagnostics.value.values.flatten().any{it.message=="known"}}
            assertTrue(f.manager.diagnose("a.prisma").contains("unverified"))
        }
    }
    @Test(timeout=10000) fun nullTextSavesNeverOpenClosedOrNeverOpenedPrisma()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.enableProjectAnalysis("a.prisma");f.manager.didSave("a.prisma",null)
            assertTrue(f.host.peers.isEmpty());f.manager.didOpen("a.prisma",LEGACY);f.manager.didClose("a.prisma")
            f.manager.didSave("a.prisma",null);f.manager.didSaveSnapshot("a.prisma",LEGACY,LEGACY)
            assertTrue(f.host.peers.isEmpty());assertEquals(LspDocumentSupportPhase.CLOSED,f.manager.documentSupportStatus("a.prisma")!!.phase)
        }
    }
    @Test(timeout=10000) fun saveUsesLiveGenerationAndMutationRequiresDemandWithoutReprompt()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val p=f.host.peers.single();f.manager.didSaveSnapshot("a.prisma",LEGACY,NEXT)
            assertFalse(p.isAlive);assertEquals(LspDocumentSupportPhase.UNSUPPORTED_NEXT_OR_8,f.manager.documentSupportStatus("a.prisma")!!.phase)
            f.manager.didChange("a.prisma",LEGACY);f.manager.completionItems("a.prisma",1,1);val fresh=f.host.peers.last()
            f.manager.workspaceFilesChanged();assertFalse(fresh.isAlive);assertEquals(2,f.host.peers.size)
            f.manager.completionItems("a.prisma",1,1);assertEquals(1,f.approvalCalls)
        }
    }
    @Test(timeout=10000) fun errorCallbackMarksOnlyExactOwnerDegradedWithoutInventingSuccess()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",LEGACY);f.manager.enableProjectAnalysis("a.prisma");f.manager.completionItems("a.prisma",1,1)
            val p=f.host.peers.single();val reply=p.request("window/showMessageRequest",buildJsonObject{put("type",1);put("message","private secret details")})
            assertEquals(JsonNull,reply["result"]);val status=f.manager.serverStatus("a.prisma")
            assertTrue(status.contains("server reported an error"));assertFalse(status.contains("private secret"))
            assertEquals("fixture",f.manager.completionItems("a.prisma",1,1).single().label)
        }
    }
    @Test(timeout=10000) fun provisionerRejectsKnownNextAndDefersUnknownWithoutPresenceProbe()=runBlocking<Unit> {
        Fixture().use {f->
            var checks=0;val wrapper=ProvisioningLspManager(f.manager,{checks++;true})
            f.file("a.prisma").writeText(NEXT)
            assertTrue(runCatching{wrapper.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspDocumentSupportUnavailableException)
            assertEquals(0,checks);assertEquals(0,f.host.discoveries)
            f.manager.didOpen("a.prisma",NEXT);assertTrue(runCatching{wrapper.formatDocument("a.prisma")}.isFailure);assertEquals(0,checks)
        }
    }
    @Test(timeout=10000) fun customPrismaRouteRequiresOwnGrantButDoesNotInheritGenerationFilter()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.prisma");f.manager.didOpen("a.prisma",NEXT)
            f.extras=listOf(LanguageServerSpec("prisma-language-server",setOf("prisma"),setOf("prisma"),listOf(listOf("custom-prisma","--stdio")),source="custom"))
            assertNull(f.manager.documentSupportStatus("a.prisma"))
            assertTrue(runCatching{f.manager.completionItems("a.prisma",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
            f.manager.enableProjectAnalysis("a.prisma");assertTrue(f.manager.projectAnalysisStatus("a.prisma")!!.enabled)
        }
    }
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-prisma-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root);var approvalCalls=0;var fallbackCalls=0
        var approvalGate:CompletableDeferred<Unit>?=null
        var extras:List<LanguageServerSpec> = emptyList()
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String {fallbackCalls++;error("No CLI execution")}
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String {fallbackCalls++;error("No CLI execution")}
        },remoteProcessHost=host,extraLanguageServers={extras},approveProjectAnalysis={_,current -> approvalCalls++;approvalGate?.await();current()})
        fun file(path:String)=File(root,path).apply {parentFile.mkdirs();writeText("model User {}")}
        override fun close() {manager.close();host.peers.forEach {it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>();val cwd=CopyOnWriteArrayList<String>()
        var discoveries=0;@Volatile var holdInitialize=false
        var prepareGate:CompletableDeferred<Unit>?=null
        val prepareEntered=CompletableDeferred<Unit>();@Volatile var prepareCanceled=false
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/usr/bin/$command"}
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Builtin Prisma must use its project cwd")
        override suspend fun startInWorkspace(argv:List<String>,environment:Map<String,String>,workspaceDirectory:String):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            cwd+=workspaceDirectory
            return Peer(argv,holdInitialize).also {peers+=it}
        }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean):HostedStdioProcess {
        val observed=CopyOnWriteArrayList<JsonObject>()
        private val alive=AtomicBoolean(true)
        private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream()
        var actionResult:JsonElement=JsonArray(emptyList())
        var resolvedAction:JsonElement=buildJsonObject {}
        var formatResult:JsonElement=JsonArray(emptyList())
        @Volatile var holdCompletion=false
        @Volatile var holdSymbols=false
        @Volatile var failCloseWrite=false
        override val stdin=object:OutputStream() {
            @Synchronized override fun write(b:Int) { pending.write(b) }
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int) { pending.write(b,off,len) }
            @Synchronized override fun flush() {
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> if(!holdInitialize) reply(message,Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":2,"definitionProvider":true,"documentFormattingProvider":true,"hoverProvider":true,"renameProvider":true,"documentSymbolProvider":true,"referencesProvider":true,"completionProvider":{"resolveProvider":true,"triggerCharacters":["@","\"","."]},"codeActionProvider":{"codeActionKinds":["quickfix"]}}}"""))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.prisma","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
                    "textDocument/formatting" -> reply(message,formatResult)
                    "textDocument/codeAction" -> reply(message,actionResult)
                    "codeAction/resolve" -> reply(message,resolvedAction)
                    "textDocument/didClose" -> if (failCloseWrite) throw IOException("fixture close write failed")
                    "shutdown" -> reply(message,JsonNull)
                    "exit" -> close()
                }
            }
        }
        override val stdout:InputStream get()=out
        override val stderr:InputStream get()=err
        override val isAlive get()=alive.get()
        override val exitCode get()=if(isAlive)null else 0
        override suspend fun awaitExit():Int { while(isAlive)delay(5);return 0 }
        override fun close() { if(alive.compareAndSet(true,false)) {out.close();err.close()} }
        suspend fun request(method:String,params:JsonElement):JsonObject {
            val id="inbound-"+observed.size+"-"+System.nanoTime()
            send(buildJsonObject {put("jsonrpc","2.0");put("id",id);put("method",method);put("params",params)})
            return withTimeout(2000) {while(true) {observed.firstOrNull {it["id"]==JsonPrimitive(id)}?.let{return@withTimeout it};delay(5)};error("unreachable")}
        }
        fun params(method:String)=observed.last { it["method"]?.jsonPrimitive?.content==method }["params"]!!.jsonObject
        suspend fun next(method:String,after:Int=0):JsonObject=withTimeout(2000) {
            while(true) { val matches=observed.filter { it["method"]?.jsonPrimitive?.content==method };if(matches.size>after)return@withTimeout matches[after];delay(5) }
            error("unreachable")
        }
        fun reply(request:JsonObject,result:JsonElement)=send(buildJsonObject { put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result) })
        fun publish(uri:String,message:String,version:Int?=null)=send(buildJsonObject {
            put("jsonrpc","2.0");put("method","textDocument/publishDiagnostics")
            put("params",buildJsonObject { put("uri",uri);version?.let {put("version",it)};put("diagnostics",buildJsonArray {
                add(buildJsonObject { put("message",message);put("range",buildJsonObject {
                    put("start",buildJsonObject { put("line",0);put("character",0) });put("end",buildJsonObject { put("line",0);put("character",1) })
                }) })
            }) })
        })
        fun send(message:JsonObject) { val bytes=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(bytes,message.toString());out.offer(bytes.toByteArray()) }
    }
    private class QueuedInput:InputStream() {
        val bytes=LinkedBlockingQueue<Int>()
        override fun read()=bytes.take()
        override fun read(buffer:ByteArray,off:Int,len:Int):Int {
            if(len==0)return 0
            val first=read();if(first<0)return -1
            buffer[off]=first.toByte();var size=1
            while(size<len) { val next=bytes.poll()?:break;if(next<0) { bytes.offer(-1);break };buffer[off+size++]=next.toByte() }
            return size
        }
        fun offer(value:ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }
    private companion object { const val LEGACY="model User { id Int @id }"; const val NEXT="// use prisma-next\nmodel User {}"; suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000) { while(!check())delay(5) } }
}
