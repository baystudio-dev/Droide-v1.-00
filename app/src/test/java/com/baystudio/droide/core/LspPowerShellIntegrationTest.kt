package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspPowerShellIntegrationTest {
    @Test(timeout=10000) fun passiveAndColdHintNeverDiscoverAndCustomDoesNotInherit()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","${'$'}x")
        assertEquals(LspCompletionPolicy.POWERSHELL_NATIVE,f.manager.completionPolicy("a.ps1"))
        assertNull(f.manager.completionPolicy("a.py"));assertEquals(0,f.host.discoveries)
        assertTrue(runCatching{f.manager.completionItems("a.ps1",1,1)}.exceptionOrNull() is LspProjectAnalysisRequiredException)
        assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty())
    }}
    @Test(timeout=10000) fun exactManagedProofIsRequiredBeforeAnyPeerStarts()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","Get-A");f.host.managed=false
        f.manager.enableProjectAnalysis("a.ps1");awaitUntil{f.manager.projectAnalysisStatus("a.ps1")?.readiness==LspProjectAnalysisReadiness.FAILED}
        assertTrue(f.host.peers.isEmpty());assertTrue(f.manager.serverStatus("a.ps1").contains("failed"))
        repeat(3){runCatching{f.manager.completionItems("a.ps1",1,1)}};assertTrue(f.host.peers.isEmpty())
    }}
    @Test(timeout=10000) fun exactMappedRootsProfilesAndInitialTwoRealFramesPreserveDirtyCrLf()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.file("b.psd1");f.manager.didOpen("a.ps1","Get-A 😀\r\n");f.manager.didOpen("b.psd1","")
        f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val p=f.host.peers.single();val init=p.params("initialize")
        assertEquals(JsonPrimitive("file:///workspace"),init["rootUri"]);assertEquals("/workspace",f.host.cwd.single())
        assertEquals(Json.parseToJsonElement("""{"enableProfileLoading":true,"initialWorkingDirectory":"/workspace"}"""),init["initializationOptions"])
        val caps=init["capabilities"]!!.jsonObject;assertFalse(caps["workspace"]!!.jsonObject.containsKey("didChangeWatchedFiles"))
        assertEquals(JsonPrimitive(true),caps["workspace"]!!.jsonObject["configuration"])
        assertEquals(JsonPrimitive(true),caps["textDocument"]!!.jsonObject["semanticTokens"]!!.jsonObject["multilineTokenSupport"])
        assertFalse(p.observed.any{it["method"]==JsonPrimitive("workspace/didChangeConfiguration")})
        val frames=p.observed.filter{it["method"]?.jsonPrimitive?.content in setOf("textDocument/didOpen","textDocument/didChange")}
        assertEquals(4,frames.size)
        for(i in listOf(0,2)){val open=frames[i]["params"]!!.jsonObject["textDocument"]!!.jsonObject;val change=frames[i+1]["params"]!!.jsonObject
            assertEquals(JsonPrimitive(1),open["version"]);assertEquals(JsonPrimitive(2),change["textDocument"]!!.jsonObject["version"])
            assertEquals(open["text"],change["contentChanges"]!!.jsonArray.single().jsonObject["text"]);assertEquals(JsonPrimitive("powershell"),open["languageId"])}
        assertTrue(frames.any{it.toString().contains("😀")});f.manager.didChange("a.ps1","")
        assertEquals(Json.parseToJsonElement("[{\"text\":\"\"}]"),p.params("textDocument/didChange")["contentChanges"])
    }}
    @Test(timeout=10000) fun promptRepliesCancelDuringInitializeAndUnsupportedRequestsAreErrors()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.ps1");awaitUntil{f.host.peers.isNotEmpty()};val p=f.host.peers.single();p.next("initialize")
        val expected=Json.parseToJsonElement("""{"promptCancelled":true,"responseText":null}""")
        assertEquals(expected,p.request("powerShell/showInputPrompt",Json.parseToJsonElement("""{"name":"password","label":null}"""))["result"])
        assertEquals(expected,p.request("powerShell/showChoicePrompt",Json.parseToJsonElement("""{"isMultiChoice":false,"caption":"delete","message":"danger","choices":[{"label":"Yes"}],"defaultChoices":[0]}"""))["result"])
        assertEquals(JsonPrimitive(-32603),p.request("editor/insertText",buildJsonObject{})["error"]!!.jsonObject["code"])
        assertEquals(JsonPrimitive(-32603),p.request("powerShell/showInputPrompt",buildJsonObject{put("name",3)})["error"]!!.jsonObject["code"])
        p.finishInitialize();ready(f,"a.ps1")
    }}
    @Test(timeout=10000) fun finiteRenameConfigurationHonorsOrderAndOwnedScope()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","${'$'}x");f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val p=f.host.peers.single()
        val result=p.request("workspace/configuration",Json.parseToJsonElement("""{"items":[{"section":"powershell.rename"},{"section":"unknown"},{"section":"powershell.rename","scopeUri":"file:///workspace/a.ps1"},{"section":"powershell.rename","scopeUri":"file://foreign/workspace/a.ps1"}]}"""))["result"]!!.jsonArray
        val yes=Json.parseToJsonElement("""{"acceptDisclaimer":true,"createParameterAlias":false}""")
        assertEquals(JsonArray(listOf(yes,JsonNull,yes,JsonNull)),result)
    }}
    @Test(timeout=10000) fun allSixNativeCompletionContextsUseCurrentCaptureAndCaretGuard()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","Get-A");f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val p=f.host.peers.single()
        for(ch in listOf(".","-",":","\\","$"," ")){val text="x"+ch;f.manager.didChange("a.ps1",text)
            val item=f.manager.completionItems("a.ps1",1,text.length+1,null,text).single()
            val context=p.params("textDocument/completion")["context"]!!.jsonObject
            assertEquals(JsonPrimitive(2),context["triggerKind"]);assertEquals(JsonPrimitive(ch),context["triggerCharacter"])
            assertEquals(LspCompletionCaret(1,text.length+1),item.applicationGuard!!.requiredCaret)
        };assertEquals(1,f.host.peers.size)
    }}
    @Test(timeout=10000) fun callerCancellationDoesNotRetireNativeOwnerOrRepeatProfiles()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","Get-A");f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val p=f.host.peers.single();p.holdCompletion=true
        val request=async{f.manager.completionItems("a.ps1",1,1)};val frame=p.next("textDocument/completion")
        request.cancelAndJoin();assertTrue(p.isAlive);assertEquals(LspProjectAnalysisReadiness.READY,f.manager.projectAnalysisStatus("a.ps1")!!.readiness)
        p.reply(frame,p.completion());p.holdCompletion=false;delay(50)
        assertEquals("fixture",f.manager.completionItems("a.ps1",1,1).single().label);assertEquals(1,f.host.peers.size)
    }}
    @Test(timeout=12000) fun actualNativeDeadlineRetiresOnceAndRequiresExplicitRetry()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","Get-A");f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val old=f.host.peers.single();old.holdCompletion=true
        assertTrue(f.manager.completionItems("a.ps1",1,1).isEmpty());awaitUntil{!old.isAlive}
        assertEquals(LspProjectAnalysisReadiness.FAILED,f.manager.projectAnalysisStatus("a.ps1")!!.readiness)
        repeat(3){runCatching{f.manager.completionItems("a.ps1",1,1)}};assertEquals(1,f.host.peers.size)
        f.manager.retryProjectAnalysis("a.ps1");ready(f,"a.ps1");assertEquals(2,f.host.peers.size);assertEquals(1,f.approvalCalls)
    }}
    @Test(timeout=10000) fun ownedCloseRetiresAndDirtySiblingReplaysWithoutReapproval()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.file("b.psm1");f.manager.didOpen("a.ps1","discard");f.manager.didOpen("b.psm1","keep 😀")
        f.manager.enableProjectAnalysis("a.ps1");ready(f,"a.ps1");val old=f.host.peers.single();f.manager.didClose("a.ps1");assertFalse(old.isAlive)
        runCatching{f.manager.completionItems("b.psm1",1,1)};ready(f,"b.psm1");val p=f.host.peers.last()
        assertEquals("keep 😀",p.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(1,f.approvalCalls)
    }}
    @Test(timeout=10000) fun persistenceCancelsHeldStartupAndPreservesGrantWithoutSaveRestart()=runBlocking<Unit>{Fixture().use{f->
        f.file("a.ps1");f.manager.didOpen("a.ps1","dirty");f.host.holdInitialize=true;f.manager.enableProjectAnalysis("a.ps1")
        awaitUntil{f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize");f.manager.workspaceFilesChanged();awaitUntil{!old.isAlive}
        assertTrue(f.manager.projectAnalysisStatus("a.ps1")!!.enabled);f.manager.didSaveSnapshot("a.ps1","disk","dirty");assertEquals(1,f.host.peers.size)
    }}
    private suspend fun ready(f:Fixture,path:String)=awaitUntil{f.manager.projectAnalysisStatus(path)?.readiness==LspProjectAnalysisReadiness.READY}
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-powershell-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root);var approvalCalls=0;var fallbackCalls=0
        var approvalGate:CompletableDeferred<Unit>?=null
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String {fallbackCalls++;error("No CLI execution")}
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String {fallbackCalls++;error("No CLI execution")}
        },remoteProcessHost=host,approveProjectAnalysis={_,current -> approvalCalls++;approvalGate?.await();current()})
        fun file(path:String)=File(root,path).apply {parentFile.mkdirs();writeText("# inert host fixture")}
        override fun close() {manager.close();host.peers.forEach {it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>();val cwd=CopyOnWriteArrayList<String>()
        var discoveries=0;@Volatile var holdInitialize=false
        var prepareGate:CompletableDeferred<Unit>?=null
        var openGate:CountDownLatch?=null
        val prepareEntered=CompletableDeferred<Unit>();@Volatile var prepareCanceled=false
        var managed=true
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/opt/droide/bin/$command"}
        override suspend fun reviewedPowerShellLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):PowerShellManagedLaunchIdentity? = if(managed) identity else null
        private val identity=PowerShellManagedLaunchIdentity("lsp.powershell-editor-services","4.7.0","runtime.powershell","7.6.7",
            "powershell-7.6.7-pses-4.7.0-consolehost-pipe-v1","5084f0326cc88539e9d880b08f6c52ce5be4672bb8ecee8921e35f8895a6b9d7",
            "ba465efdfb0984c1c5dc5ed9eb0b79a7e91a1b52c45f99377058c0659b84d3f1",
            "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),
            "/opt/droide/bin/powershell-editor-services","/opt/droide/packages/lsp.powershell-editor-services/4.7.0/arm64-v8a/payload/bin/powershell-editor-services",
            "/opt/droide/packages/runtime.powershell/7.6.7/arm64-v8a/payload/tree/pwsh",
            "00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002")
        override suspend fun startReviewedPowerShellInWorkspace(identity:PowerShellManagedLaunchIdentity,argv:List<String>,environment:Map<String,String>,remoteCwd:String,admitStart:(()->HostedStdioProcess)->HostedStdioProcess):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            return admitStart {cwd+=remoteCwd;Peer(argv,holdInitialize,openGate).also{peers+=it}}
        }
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=Peer(argv,false).also {peers+=it}
        override suspend fun startInWorkspace(argv:List<String>,environment:Map<String,String>,workspaceDirectory:String):HostedStdioProcess {
            prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            cwd+=workspaceDirectory
            return Peer(argv,holdInitialize,openGate).also {peers+=it}
        }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean,val openGate:CountDownLatch?=null):HostedStdioProcess {
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
                    "initialize" -> if(!holdInitialize) reply(message,capabilities())
                    "textDocument/didOpen" -> openGate?.await()
                    "textDocument/completion" -> if(!holdCompletion) reply(message,completion(message["params"]!!.jsonObject["position"]!!.jsonObject))
                    "textDocument/hover" -> reply(message,buildJsonObject{put("contents","fixture hover")})
                    "textDocument/signatureHelp" -> reply(message,JsonNull)
                    "textDocument/semanticTokens/full" -> reply(message,Json.parseToJsonElement("{\"data\":[0,0,2,0,0]}"))
                    "workspace/symbol" -> if(!holdSymbols) reply(message,JsonArray(emptyList()))
                    "textDocument/definition", "textDocument/references" -> reply(message,Json.parseToJsonElement("""[{"uri":"file:///workspace/b.ps1","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]"""))
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
        fun completion(position:JsonObject=buildJsonObject{put("line",0);put("character",0)})=buildJsonArray {
            add(buildJsonObject {put("label","fixture");put("textEdit",buildJsonObject{
                put("range",buildJsonObject{put("start",position);put("end",position)});put("newText","fixture")})})
        }
        fun capabilities()=Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":{"openClose":true,"change":2,"save":{"includeText":true}},"hoverProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"completionProvider":{"triggerCharacters":[".","-",":","\\","$"," "]},"signatureHelpProvider":{"triggerCharacters":[" "]},"semanticTokensProvider":{"full":true,"legend":{"tokenTypes":["variable"],"tokenModifiers":[]}},"definitionProvider":true,"referencesProvider":true,"renameProvider":true,"codeActionProvider":{"codeActionKinds":["quickfix"]},"documentFormattingProvider":true}}""")
        fun finishInitialize()=reply(observed.first{it["method"]==JsonPrimitive("initialize")},capabilities())
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
    private companion object { suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000) { while(!check())delay(5) } }
}
