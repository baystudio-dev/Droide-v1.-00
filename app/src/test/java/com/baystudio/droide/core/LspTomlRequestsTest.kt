package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Authored byte-stream peers exercise actual manager/writer/callback/snapshot application paths. */
class LspTomlRequestsTest {
    private class Incoming : InputStream() {
        private val queue=LinkedBlockingQueue<Int>()
        fun put(bytes:ByteArray) { bytes.forEach { queue.put(it.toInt() and 255) } }
        override fun read():Int=queue.take()
        override fun read(b:ByteArray,off:Int,len:Int):Int { if(len==0)return 0;val first=read();if(first<0)return -1;b[off]=first.toByte();var n=1;while(n<len){val next=queue.poll()?:break;if(next<0){queue.put(-1);break};b[off+n++]=next.toByte()};return n }
        override fun close(){queue.put(-1)}
    }
    private class Owner(override val identity:TomlManagedLaunchIdentity,override val request:TomlOwnerRequest,val cleanupComplete:Boolean):TomlPreparedOwner {
        override val token="0123456789abcdef0123456789abcdef"
        @Volatile var active=true
        var peer:Peer?=null
        override val isCurrent:Boolean get()=active && peer?.fatalControl==null
        override suspend fun retire():TomlTeardownReceipt {active=false;peer?.close();return TomlTeardownReceipt(cleanupComplete,cleanupComplete,cleanupComplete,cleanupComplete)}
        override fun close(){active=false;peer?.close()}
    }
    private class Peer(override val owner:Owner,val configGate:CompletableDeferred<Unit>?,val miss:(Peer,String)->String?,val cancelOnPoison:Boolean=false,val holdCompletion:Boolean=false):TomlHostedProcess {
        private val incoming=Incoming();private val pending=ByteArrayOutputStream();val frames=CopyOnWriteArrayList<JsonObject>()
        private val configured=CompletableDeferred<JsonPrimitive>();private val listeners=CopyOnWriteArrayList<(TomlControlEvent)->Unit>()
        @Volatile override var fatalControl:TomlControlEvent?=null
        @Volatile private var alive=true
        override val isAlive:Boolean get()=alive
        override val exitCode:Int? get()=if(alive)null else 0
        override val stdout:InputStream=incoming
        override val stderr:InputStream=ByteArrayInputStream(ByteArray(0))
        override val stdin:OutputStream=object:OutputStream(){override fun write(b:Int){write(byteArrayOf(b.toByte()))};override fun write(b:ByteArray,off:Int,len:Int){synchronized(pending){pending.write(b,off,len);drain()}}}
        private fun drain() {
            while(true){val bytes=pending.toByteArray();val text=String(bytes,Charsets.UTF_8);val header=text.indexOf("\r\n\r\n");if(header<0)return
                val count=text.substring(0,header).lineSequence().first { it.startsWith("Content-Length:",true) }.substringAfter(":").trim().toInt();if(bytes.size<header+4+count)return
                val message=Json.parseToJsonElement(String(bytes,header+4,count,Charsets.UTF_8)).jsonObject
                pending.reset();pending.write(bytes,header+4+count,bytes.size-header-4-count);receive(message)
            }
        }
        fun emit(value:JsonObject){val bytes=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(bytes,value.toString());incoming.put(bytes.toByteArray())}
        fun notify(method:String,params:JsonObject)=emit(buildJsonObject{put("jsonrpc","2.0");put("method",method);put("params",params)})
        private fun receive(message:JsonObject) {
            frames.add(message)
            val method=(message["method"] as? JsonPrimitive)?.content
            val id=message["id"]
            fun reply(value:JsonElement)=emit(buildJsonObject{put("jsonrpc","2.0");put("id",requireNotNull(id));put("result",value)})
            when(method){
                "initialize" -> reply(Json.parseToJsonElement("""{"capabilities":{"textDocumentSync":1,"hoverProvider":true,"completionProvider":{},"renameProvider":{"prepareProvider":true},"documentFormattingProvider":true,"documentSymbolProvider":true,"semanticTokensProvider":{"full":true,"legend":{"tokenTypes":["tomlArrayKey","tomlTableKey"],"tokenModifiers":["readonly"]}}}}"""))
                "initialized" -> emit(buildJsonObject{put("jsonrpc","2.0");put("id","native-config");put("method","workspace/configuration");put("params",buildJsonObject{put("items",buildJsonArray{
                    add(buildJsonObject{put("section",LspTomlProfile.SECTION)});add(buildJsonObject{put("section",LspTomlProfile.SECTION);put("scopeUri",owner.request.snapshot.rootUri)})
                })})})
                null -> if(id==JsonPrimitive("native-config")) {require(message["result"]==JsonArray(listOf(LspTomlProfile.settings(),LspTomlProfile.settings())));configured.complete(JsonPrimitive("native-config"))}
                "taplo/listSchemas" -> reply(JsonArray(emptyList()))
                "textDocument/completion" -> {val path=miss(this,method);if(path!=null)poison(path) else if(!holdCompletion) reply(Json.parseToJsonElement("""[{"label":"native-key","insertText":"native-key = "}]"""))}
                "textDocument/formatting" -> reply(JsonArray(emptyList()))
                "textDocument/hover" -> reply(Json.parseToJsonElement("""{"contents":"native hover"}"""))
                "textDocument/prepareRename" -> reply(Json.parseToJsonElement("""{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}"""))
                "textDocument/rename" -> {val p=message["params"]!!.jsonObject;reply(buildJsonObject{put("changes",buildJsonObject{put(p["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content,buildJsonArray{add(buildJsonObject{put("range",Json.parseToJsonElement("""{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}"""));put("newText",p["newName"]!!)})})})})}
                "textDocument/documentSymbol" -> reply(JsonArray(emptyList()))
                "shutdown" -> reply(JsonNull)
                "exit" -> close()
            }
        }
        fun poison(path:String){fatalControl=TomlControlEvent("read_miss",path,"unknown_snapshot_path");listeners.toList().forEach{it(fatalControl!!)};incoming.close();if(cancelOnPoison)throw CancellationException("Authored owned writer poisoned") }
        override suspend fun awaitConfigurationResponseAccepted():JsonPrimitive {val id=configured.await();configGate?.await();return id}
        override fun onControl(listener:(TomlControlEvent)->Unit){listeners+=listener;fatalControl?.let(listener)}
        override suspend fun stopAndAwaitOwnedGroup():TomlTeardownReceipt=owner.retire()
        override suspend fun awaitExit():Int {while(alive)delay(1);return 0}
        override fun close(){alive=false;incoming.close()}
    }
    private class Host(val gate:CompletableDeferred<Unit>?=null,val cleanupComplete:Boolean=true,val cancelOnPoison:Boolean=false,val holdCompletion:Boolean=false,val miss:(Peer,String)->String?={_,_->null}):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        override suspend fun resolveExecutable(command:String):String?=null
        val peers=CopyOnWriteArrayList<Peer>()
        override suspend fun reviewedTomlLaunchIdentity(command:String,workspaceRoot:String)=TomlManagedLaunchIdentity(
            "lsp.taplo-wasm","0.8.0","runtime.taplo-node","22.23.3","fixture-v1","a".repeat(64),"b".repeat(64),
            "c".repeat(64),"d".repeat(64),"fixture-selection","/fixture/taplo","/fixture/taplo","/fixture/node",
            "fixture-server-activation","fixture-node-activation","e".repeat(64),"f".repeat(64),"1".repeat(64),"2".repeat(64),"3".repeat(64),mapOf("adapter" to "4".repeat(64)))
        override suspend fun prepareReviewedTomlOwner(identity:TomlManagedLaunchIdentity,request:TomlOwnerRequest)=Owner(identity,request,cleanupComplete)
        override suspend fun startReviewedTomlInWorkspace(identity:TomlManagedLaunchIdentity,owner:TomlPreparedOwner,admitStart:(()->TomlHostedProcess)->TomlHostedProcess):TomlHostedProcess = admitStart{
            Peer(owner as Owner,gate,miss,cancelOnPoison,holdCompletion).also{owner.peer=it;peers+=it}
        }
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Generic process start forbidden")
    }
    private suspend fun waitFor(block:()->Boolean) {withTimeout(5000){while(!block())delay(5)}}
    @Test fun formattingWireShouldUseResolvedProjectIndent()=runBlocking {
        val root=Files.createTempDirectory("toml-format-options-").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val host=Host()
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {
            File(root,".editorconfig").writeText("root=true\n[*.toml]\nindent_style=space\nindent_size=2")
            val style=CodeStyleAuthority(root).resolve("a.toml","toml","key = 1",CodeStyleDefaults(detectIndentation=false))
            assertEquals(2,style.indentSize)
            manager.didOpen("a.toml","key = 1");manager.enableProjectAnalysis("a.toml")
            manager.formatDocument("a.toml",LspFormattingOptions.fromCodeStyle(style))
            val options=host.peers.single().frames.last{it["method"]==JsonPrimitive("textDocument/formatting")}["params"]!!.jsonObject.getValue("options").jsonObject
            assertEquals(style.indentSize,options.getValue("tabSize").jsonPrimitive.int)
            manager.formatDocument("a.toml")
            val old=host.peers.single().frames.last{it["method"]==JsonPrimitive("textDocument/formatting")}["params"]!!.jsonObject.getValue("options").jsonObject
            assertEquals(JsonPrimitive(4),old["tabSize"]);assertEquals(JsonPrimitive(true),old["insertSpaces"])
        } finally {manager.shutdown();scope.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    @Test fun realCallbackAcceptancePrecedesListBarrierAndFullDirtyReplay()=runBlocking {
        val root=Files.createTempDirectory("toml-manager-").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val gate=CompletableDeferred<Unit>();val host=Host(gate)
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {
            manager.didOpen("a.toml","key = ");manager.observeEditorDocument("schema.json","{\"type\":\"object\"}")
            val enable=async { manager.enableProjectAnalysis("a.toml") }
            waitFor{host.peers.firstOrNull()?.frames?.any{it["id"]==JsonPrimitive("native-config")&&it["result"]!=null}==true}
            assertFalse(host.peers.single().frames.any{it["method"]==JsonPrimitive("taplo/listSchemas")})
            gate.complete(Unit);enable.await()
            val peer=host.peers.single();val methods=peer.frames.mapNotNull{(it["method"] as? JsonPrimitive)?.content}
            assertTrue(methods.indexOf("taplo/listSchemas")<methods.indexOf("textDocument/didOpen"))
            val opens=peer.frames.filter{it["method"]==JsonPrimitive("textDocument/didOpen")}
            assertEquals(1,opens.size);assertEquals("key = ",opens.single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            val completion=manager.completionItems("a.toml",1,7,null,"key = ").single()
            manager.didChange("a.toml","key =  ")
            assertFalse(completion.applicationGuard!!.accepts("key = ",LspCompletionCaret(1,7),false))
            manager.completionItems("a.toml",1,8,null,"key =  ")
            val change=peer.frames.last{it["method"]==JsonPrimitive("textDocument/didChange")}["params"]!!.jsonObject["contentChanges"]!!.jsonArray
            assertEquals(1,change.size);assertEquals(setOf("text"),change.single().jsonObject.keys)
            assertEquals(1,host.peers.size)
        } finally {manager.shutdown();scope.cancel()}
    }
    @Test fun nativePrepareAndRenameShareExactCaptureAndSpelling()=runBlocking {
        val root=Files.createTempDirectory("toml-rename-").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val host=Host();val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {manager.didOpen("a.toml","key = 1");manager.enableProjectAnalysis("a.toml")
            val result=requireNotNull(manager.renameSymbol("a.toml",1,1,"'clé space'"));assertEquals("'clé space'",result.edits.single().range.newText)
            val methods=host.peers.single().frames.mapNotNull{(it["method"] as? JsonPrimitive)?.content}
            assertTrue(methods.indexOf("textDocument/prepareRename")<methods.indexOf("textDocument/rename"))
            manager.observeEditorDocument("unrelated.json","{}");assertFalse(result.applicationGuard!!.invoke())
        } finally {manager.shutdown();scope.cancel()}
    }
    @Test fun poisonedWriterCancellationGrowsFreshOwnerWithSameDirtyCapture()=runBlocking {
        val root=Files.createTempDirectory("toml-grow-").toFile();val schema=File(root,"schema.json").apply{writeText("{\"type\":\"object\"}")}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val host=Host(cancelOnPoison=true,miss={peer,_->if(peer.owner.request.snapshot.entries.none{it.path==schema.path})schema.path else null})
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {manager.didOpen("a.toml","");manager.observeEditorDocument("schema.json","{\"type\":\"string\"}");manager.enableProjectAnalysis("a.toml")
            assertEquals("native-key",manager.completionItems("a.toml",1,1).single().label)
            assertEquals(2,host.peers.size)
            val captured=host.peers.last().owner.request.snapshot.entries.single{it.path==schema.path}
            assertEquals("{\"type\":\"string\"}",String(captured.bytes!!))
            assertFalse(host.peers.first().owner.isCurrent)
        } finally {manager.shutdown();scope.cancel()}
    }
    @Test fun sameTextNoopPreservesHeldResultAndPerPathOverrideRejectsIt()=runBlocking {
        val root=Files.createTempDirectory("toml-current-").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val host=Host();var managed=true
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true},routeForPath={managed})
        try {manager.didOpen("a.toml","");manager.enableProjectAnalysis("a.toml");val item=manager.completionItems("a.toml",1,1).single()
            manager.didChange("a.toml","");assertTrue(item.applicationGuard!!.accepts("",LspCompletionCaret(1,1),false))
            managed=false;assertFalse(item.applicationGuard!!.accepts("",LspCompletionCaret(1,1),false))
        } finally {manager.shutdown();scope.cancel()}
    }
    @Test fun originalLogicalBudgetNeverResetsAcrossRounds()=runBlocking {
        var clock=0L;var fenced=false;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val requests=LspTomlRequests(scope,{fenced=true},{clock})
        try {
            val result=requests.owned("textDocument/completion",{true}) { operation ->
                assertEquals(30_000L,operation.remainingMs());operation.markDispatched()
                repeat(8) { clock+=3_000_000_000L;assertEquals(30_000L-(it+1)*3000,operation.remainingMs()) }
                clock=30_000_000_001L;"late native result"
            }
            assertNull(result);assertTrue(fenced)
        } finally {requests.close();scope.cancel()}
    }
    @Test fun eightGrowingRestartsAreFiniteAndCleanupUncertaintyBlocksReplacement()=runBlocking {
        val root=Files.createTempDirectory("toml-rounds-").toFile()
        val schemas=(0..8).map { File(root,"schema$it.json").apply{writeText("true")} }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val host=Host(miss={peer,_->schemas.firstOrNull { file -> peer.owner.request.snapshot.entries.none { it.path==file.path } }?.path})
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {
            manager.didOpen("a.toml","");manager.enableProjectAnalysis("a.toml")
            assertTrue(runCatching{manager.completionItems("a.toml",1,1)}.isFailure)
            assertEquals(9,host.peers.size)
        } finally {manager.shutdown();scope.cancel()}
        val scope2=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val blocked=Host(cleanupComplete=false,miss={_,_->schemas.first().path})
        val manager2=TomlLspManager(scope2,root,blocked,{true},{_,_->true})
        try {
            manager2.didOpen("b.toml","");manager2.enableProjectAnalysis("b.toml")
            assertTrue(runCatching{manager2.completionItems("b.toml",1,1)}.isFailure)
            assertEquals(1,blocked.peers.size)
            assertTrue(manager2.projectAnalysisStatus("b.toml").canRevoke)
        } finally {manager2.shutdown();scope2.cancel()}
    }
    @Test fun closeThenSaveCannotReopenAndCompanionChangeWithdrawsDiagnostics()=runBlocking {
        val root=Files.createTempDirectory("toml-advisory-").toFile();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val host=Host()
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {
            manager.didOpen("a.toml","a");manager.enableProjectAnalysis("a.toml")
            val uri=java.net.URI("file","",File(root,"a.toml").path,null).toASCIIString()
            host.peers.single().notify("textDocument/publishDiagnostics",buildJsonObject{put("uri",uri);put("diagnostics",buildJsonArray{add(Json.parseToJsonElement("""{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"native advisory"}"""))})})
            waitFor{manager.diagnostics.value.isNotEmpty()}
            assertTrue(manager.diagnostics.value.values.single().single().source!!.contains("unversioned native advisory"))
            manager.observeEditorDocument("schema.json","true");assertTrue(manager.diagnostics.value.isEmpty())
            manager.didClose("a.toml");manager.didSave("a.toml","new text")
            assertTrue(runCatching{manager.enableProjectAnalysis("a.toml")}.isFailure)
        } finally {manager.shutdown();scope.cancel()}
    }

    @Test fun actualCallerCancellationRemainsDetachedAndCannotRestartPoisonedOwner()=runBlocking {
        val root=Files.createTempDirectory("toml-caller-cancel-").toFile()
        val schema=File(root,"schema.json").apply{writeText("{}")}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val host=Host(holdCompletion=true)
        val manager=TomlLspManager(scope,root,host,{true},{_,_->true})
        try {
            manager.didOpen("a.toml","");manager.enableProjectAnalysis("a.toml")
            val demand=async { manager.completionItems("a.toml",1,1) }
            waitFor { host.peers.single().frames.any { it["method"]==JsonPrimitive("textDocument/completion") } }
            demand.cancelAndJoin();assertTrue(demand.isCancelled)
            host.peers.single().poison(schema.path)
            val field=TomlLspManager::class.java.getDeclaredField("requests").apply{isAccessible=true}
            waitFor { (field.get(manager) as LspTomlRequests).isIdle() }
            assertEquals(1,host.peers.size)
        } finally {manager.shutdown();scope.cancel()}
    }

}
