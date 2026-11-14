package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual manager/transport with authored byte-stream peers. No native SQL or guest execution. */
class LspSqruffIntegrationTest {
    @Test(timeout=10000) fun passiveEditingAndDeclinedConsentNeverDiscover()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql"); f.manager.didOpen("a.sql","select 1\r\n")
            assertTrue(runCatching { f.manager.diagnose("a.sql") }.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertEquals(0,f.host.discoveries);assertEquals(0,f.approvals);assertTrue(f.host.peers.isEmpty())
            f.allow=false;f.manager.enableProjectAnalysis("a.sql")
            assertTrue(runCatching {f.manager.formatDocument("a.sql")}.isFailure);assertTrue(f.host.peers.isEmpty())
        }
    }
    @Test(timeout=10000) fun managedIdentityIsRequiredBeforeAnyProcessAndStatusExplainsIt()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql"); f.manager.enableProjectAnalysis("a.sql");f.host.identity=null
            assertTrue(f.manager.diagnose("a.sql").contains("unavailable"));assertTrue(f.host.peers.isEmpty())
            assertTrue(f.manager.serverStatus("a.sql").contains("byte-verified"));assertEquals(1,f.approvals)
        }
    }
    @Test(timeout=10000) fun realRootManagedArgvAndResourceLimitsReachHostWithLfWireOnly()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("nested/a.sql");f.manager.didOpen("nested/a.sql","select '😀'\r\n")
            f.manager.enableProjectAnalysis("nested/a.sql");f.manager.diagnose("nested/a.sql")
            val p=f.host.peers.single();assertEquals(listOf("/managed/sqruff","lsp"),p.argv)
            assertEquals("/workspace/nested",f.host.cwd.single());assertEquals(ProcessResourceLimits(),f.host.limits.single())
            assertEquals(JsonPrimitive("file:///workspace/nested"),p.params("initialize")["rootUri"])
            val doc=p.params("textDocument/didOpen")["textDocument"]!!.jsonObject
            assertEquals(JsonPrimitive("sql"),doc["languageId"]);assertEquals(JsonPrimitive("select '😀'\n"),doc["text"])
            assertEquals(3,f.host.proofs)
            f.manager.didSaveSnapshot("nested/a.sql","select '😀'\r\n","select '😀'\r\n")
            assertFalse(p.observed.any{it["method"]==JsonPrimitive("textDocument/didSave")})
        }
    }
    @Test(timeout=10000) fun editsRetireOwnerAndNextDemandReplaysDirtySiblingsUnderSameGrant()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.file("b.sql");f.manager.didOpen("a.sql","select 1");f.manager.didOpen("b.sql","select 2")
            f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql");val old=f.host.peers.single()
            f.manager.didChange("a.sql","select 3");assertFalse(old.isAlive);assertEquals(1,f.host.peers.size)
            assertTrue(f.manager.projectAnalysisStatus("a.sql")!!.enabled);f.manager.diagnose("b.sql")
            val texts=f.host.peers.last().observed.filter{it["method"]==JsonPrimitive("textDocument/didOpen")}.map{it["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content}.toSet()
            assertEquals(setOf("select 3","select 2"),texts);assertEquals(1,f.approvals)
        }
    }
    @Test(timeout=10000) fun aToBToARevisionRetiresEvenWhenFinalBytesMatch()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val old=f.host.peers.single();f.manager.didChange("a.sql","select 2");f.manager.didChange("a.sql","select 1")
            assertFalse(old.isAlive);f.manager.diagnose("a.sql");assertEquals(2,f.host.peers.size)
        }
    }
    @Test(timeout=10000) fun scalarWarningsUseOriginalSnapshotAndCannotCertifyCleanOrSurviveEdits()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","😀x\r\n");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val old=f.host.peers.single();old.publish("a.sql",1,"warning")
            awaitUntil{f.manager.diagnostics.value.values.flatten().isNotEmpty()}
            assertEquals(3,f.manager.diagnostics.value.values.flatten().single().column)
            assertTrue(f.manager.diagnose("a.sql").contains("unverified"))
            f.manager.didChange("a.sql","new");assertTrue(f.manager.diagnostics.value.isEmpty());assertFalse(old.isAlive)
        }
    }
    @Test(timeout=10000) fun exactFormattingRepairsOnlyRangeRetiresBeforeReturningAndReplaysOnRejection()=runBlocking<Unit> {
        Fixture().use { f ->
            val original="select '😀'\r\n";val replacement="SELECT '😀';\n"
            f.file("a.sql");f.manager.didOpen("a.sql",original);f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val p=f.host.peers.single();p.formatResult=format(replacement)
            val proposal=f.manager.formatDocument("a.sql")!!;assertFalse(p.isAlive)
            assertEquals(JsonPrimitive(4),p.params("textDocument/formatting")["options"]!!.jsonObject["tabSize"])
            assertEquals(JsonPrimitive(true),p.params("textDocument/formatting")["options"]!!.jsonObject["insertSpaces"])
            assertEquals(original,proposal.expectedContent["a.sql"])
            assertEquals(replacement,TextEditApplier.apply(original,proposal.edits.map{it.range}))
            assertEquals(4,f.host.proofs)
            f.manager.diagnose("a.sql");assertEquals(JsonPrimitive(original.replace("\r\n","\n")),f.host.peers.last().params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
        }
    }

    @Test(timeout=10000) fun formattingOptionsReachExclusiveSqruffOwner()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val p=f.host.peers.single();p.formatResult=format("SELECT 1")
            assertNotNull(f.manager.formatDocument("a.sql",LspFormattingOptions(3,false)))
            val options=p.params("textDocument/formatting")["options"]!!.jsonObject
            assertEquals(JsonPrimitive(3),options["tabSize"]);assertEquals(JsonPrimitive(false),options["insertSpaces"])
            assertFalse(p.isAlive)
        }
    }
    @Test(timeout=10000) fun malformedAndEmptyFormattingAlwaysDiscardOwner()=runBlocking<Unit> {
        for(result in listOf<JsonElement>(JsonArray(emptyList()),JsonNull,format("SELECT 1").let{arr->JsonArray(listOf(JsonObject(arr[0].jsonObject+("range" to Json.parseToJsonElement("{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":99,\"character\":99}}")))))})) {
            Fixture().use {f->f.file("a.sql");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql");val p=f.host.peers.single();p.formatResult=result
                assertNull(f.manager.formatDocument("a.sql"));assertFalse(p.isAlive)}
        }
    }
    @Test(timeout=10000) fun changedManagedProofRejectsProposalAndClosesExactOwner()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.sql");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql");val p=f.host.peers.single();p.formatResult=format("SELECT 1")
            f.host.identity=f.host.identity!!.copy(sealSha256="changed")
            assertTrue(runCatching{f.manager.formatDocument("a.sql")}.isFailure);assertFalse(p.isAlive)
        }
    }
    @Test(timeout=10000) fun invalidTextHasNoPromptOrLaunchAndUnmanagedChangeClosesOldOwner()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.sql");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql");val p=f.host.peers.single()
            File(f.root,"a.sql").writeText("bad\rtext")
            assertTrue(runCatching{f.manager.diagnose("a.sql")}.isFailure);assertFalse(p.isAlive)
            f.file("b.sql").writeText("bad\rtext");assertTrue(runCatching{f.manager.enableProjectAnalysis("b.sql")}.isFailure)
            assertEquals(1,f.approvals)
        }
    }
    @Test(timeout=10000) fun closeRetiresSharedSqlOwnerWithoutReopeningClosedLedger()=runBlocking<Unit> {
        Fixture().use {f->f.file("a.sql");f.manager.didOpen("a.sql","dirty");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val p=f.host.peers.single();f.manager.didClose("a.sql");assertFalse(p.isAlive);f.manager.didSave("a.sql",null);assertEquals(1,f.host.peers.size)}
    }
    @Test(timeout=10000) fun editCancelsHeldInitializeAndFreshRequestUsesSameGrant()=runBlocking<Unit> {
        Fixture().use {f->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql");f.host.holdInitialize=true
            val held=async{runCatching{f.manager.diagnose("a.sql")}};awaitUntil{f.host.peers.isNotEmpty()};val old=f.host.peers.single();old.next("initialize")
            f.manager.didChange("a.sql","select 2");awaitUntil{!old.isAlive};withTimeout(2000){held.await()}
            f.host.holdInitialize=false;f.manager.diagnose("a.sql");assertEquals(1,f.approvals);assertTrue(f.host.peers.last().isAlive)
        }
    }
    @Test(timeout=10000) fun editDuringQuietIntervalSuppressesObsoleteSemanticLaunch()=runBlocking<Unit> {
        Fixture().use {f->f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql")
            val request=async{f.manager.semanticTokens("a.sql","select 1")};delay(100);f.manager.didChange("a.sql","select 2")
            assertTrue(request.await().isEmpty());assertTrue(f.host.peers.isEmpty())}
    }
    @Test(timeout=10000) fun strictSemanticTokensRejectSurrogateSplitsAndRetireOwner()=runBlocking<Unit> {
        Fixture().use {f->f.file("a.sql");f.manager.didOpen("a.sql","😀x");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val p=f.host.peers.single();p.tokenResult=Json.parseToJsonElement("{\"data\":[0,1,1,0,0]}")
            assertTrue(f.manager.semanticTokens("a.sql","😀x").isEmpty());assertFalse(p.isAlive)}
    }
    @Test(timeout=10000) fun formattingExcludesHeldOrdinaryResultsAndCancellationRetiresOwner()=runBlocking<Unit> {
        Fixture().use {f->f.file("a.sql");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql");val p=f.host.peers.single()
            p.holdFormat=true;val formatting=async{runCatching{f.manager.formatDocument("a.sql")}};p.next("textDocument/formatting")
            assertTrue(f.manager.diagnose("a.sql").contains("stale"));formatting.cancelAndJoin();assertFalse(p.isAlive)}
    }
    @Test(timeout=10000) fun unsupportedAutomaticFeaturesNeverStartTheNativeServer()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql")
            assertTrue(f.manager.completionItems("a.sql",1,1).isEmpty());assertTrue(f.manager.inlayHints("a.sql","select 1").isEmpty())
            assertTrue(f.manager.symbolItems("a.sql").isEmpty());assertTrue(f.manager.workspaceSymbols("a.sql","select").isEmpty())
            assertNull(f.manager.renameSymbol("a.sql",1,1,"name"));assertTrue(f.manager.codeActionsAt("a.sql",1,1).isEmpty())
            assertEquals(0,f.host.discoveries);assertTrue(f.host.peers.isEmpty())
        }
    }
    @Test(timeout=10000) fun heldSemanticResultCannotEscapeAnExclusiveFormattingLease()=runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql");f.manager.diagnose("a.sql")
            val p=f.host.peers.single();p.holdTokens=true;p.holdFormat=true
            val tokens=async{f.manager.semanticTokens("a.sql","select 1")};val tokenRequest=p.next("textDocument/semanticTokens/full")
            val format=async{runCatching{f.manager.formatDocument("a.sql")}};p.next("textDocument/formatting")
            p.reply(tokenRequest,Json.parseToJsonElement("{\"data\":[0,0,6,0,0]}"))
            assertTrue(tokens.await().isEmpty());format.cancelAndJoin();assertFalse(p.isAlive)
        }
    }
    @Test fun identityReplacementDuringHeldPreparationCreatesNoPeer()=runBlocking<Unit>{
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql")
            f.host.preparationGate=CompletableDeferred()
            val request=async{runCatching{f.manager.diagnose("a.sql")}}
            withTimeout(2000){f.host.preparationEntered.await()};f.host.identity=null;f.host.preparationGate!!.complete(Unit)
            withTimeout(2000){request.await()};assertTrue(f.host.peers.isEmpty())
        }
    }
    @Test fun revocationDuringHeldPreparationCreatesNoPeer()=runBlocking<Unit>{
        Fixture().use { f ->
            f.file("a.sql");f.manager.didOpen("a.sql","select 1");f.manager.enableProjectAnalysis("a.sql")
            f.host.preparationGate=CompletableDeferred()
            val request=async{runCatching{f.manager.diagnose("a.sql")}}
            withTimeout(2000){f.host.preparationEntered.await()};f.manager.revokeProjectAnalysis("a.sql")
            f.host.preparationGate!!.complete(Unit);withTimeout(2000){request.await()};assertTrue(f.host.peers.isEmpty())
        }
    }
    private class Fixture:AutoCloseable {
        val root=Files.createTempDirectory("sqruff-manager-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO);val host=Host(root)
        var approvals=0;var allow=true
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager{
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String=error("No CLI fallback")
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String=error("No CLI fallback")
        },remoteProcessHost=host,approveProjectAnalysis={_,current->approvals++;allow&&current()})
        fun file(path:String)=File(root,path).apply{parentFile.mkdirs();writeText("select 1")}
        override fun close(){manager.close();host.peers.forEach{it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        var discoveries=0;var proofs=0;var holdInitialize=false
        var preparationGate:CompletableDeferred<Unit>?=null
        val preparationEntered=CompletableDeferred<Unit>()
        var identity:SqruffManagedLaunchIdentity?=SqruffManagedLaunchIdentity("sqruff","0.41.0","fixture","archive","payload","receipt","recipe","seal","selected","/usr/bin/sqruff","/managed/sqruff","/native/sqruff")
        val peers=CopyOnWriteArrayList<Peer>();val cwd=CopyOnWriteArrayList<String>();val limits=CopyOnWriteArrayList<ProcessResourceLimits?>()
        override suspend fun resolveExecutable(command:String):String {discoveries++;return "/usr/bin/$command"}
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun reviewedSqruffLaunchIdentity(resolvedExecutable:String,workspaceRoot:String):SqruffManagedLaunchIdentity? {proofs++;return identity}
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("Expected project-scoped limits overload")
        override suspend fun startInWorkspace(argv:List<String>,environment:Map<String,String>,remoteCwd:String,resourceLimits:ProcessResourceLimits?):HostedStdioProcess = error("SQL must use reviewed-start admission")
        override suspend fun startReviewedSqruffInWorkspace(identity:SqruffManagedLaunchIdentity,argv:List<String>,environment:Map<String,String>,remoteCwd:String,resourceLimits:ProcessResourceLimits,admitStart:(()->HostedStdioProcess)->HostedStdioProcess):HostedStdioProcess {
            preparationEntered.complete(Unit);preparationGate?.await()
            check(this.identity==identity) { "Managed identity changed during preparation" }
            return admitStart { cwd+=remoteCwd;limits+=resourceLimits;Peer(argv,holdInitialize).also{peers+=it} }
        }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean):HostedStdioProcess {
        val observed=CopyOnWriteArrayList<JsonObject>();val alive=AtomicBoolean(true)
        val out=QueuedInput();val err=QueuedInput();val pending=ByteArrayOutputStream()
        var formatResult:JsonElement=format("SELECT 1");var tokenResult:JsonElement=Json.parseToJsonElement("{\"data\":[]}");var holdFormat=false;var holdTokens=false
        override val stdin=object:OutputStream(){
            @Synchronized override fun write(b:Int){pending.write(b)}
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int){pending.write(b,off,len)}
            @Synchronized override fun flush(){
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content){
                    "initialize"->if(!holdInitialize)reply(message,buildJsonObject{put("capabilities",buildJsonObject{
                        put("positionEncoding","utf-16");put("textDocumentSync",1);put("documentFormattingProvider",true)
                        put("semanticTokensProvider",buildJsonObject{put("full",true);put("range",false);put("legend",buildJsonObject{put("tokenTypes",JsonArray(LspSqruffProfile.tokenTypes.map(::JsonPrimitive)));put("tokenModifiers",JsonArray(emptyList()))})})
                    })})
                    "textDocument/semanticTokens/full"->if(!holdTokens)reply(message,tokenResult)
                    "textDocument/formatting"->if(!holdFormat)reply(message,formatResult)
                    "shutdown"->reply(message,JsonNull)
                    "exit"->close()
                }
            }
        }
        override val stdout:InputStream get()=out;override val stderr:InputStream get()=err
        override val isAlive get()=alive.get();override val exitCode get()=if(isAlive)null else 0
        override suspend fun awaitExit():Int{while(isAlive)delay(5);return 0}
        override fun close(){if(alive.compareAndSet(true,false)){out.close();err.close()}}
        fun params(method:String)=observed.last{it["method"]==JsonPrimitive(method)}["params"]!!.jsonObject
        suspend fun next(method:String):JsonObject=withTimeout(2000){while(true){observed.firstOrNull{it["method"]==JsonPrimitive(method)}?.let{return@withTimeout it};delay(5)};error("unreachable")}
        fun reply(request:JsonObject,result:JsonElement)=send(buildJsonObject{put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result)})
        fun publish(path:String,column:Int,message:String)=send(buildJsonObject{put("jsonrpc","2.0");put("method","textDocument/publishDiagnostics");put("params",buildJsonObject{
            put("uri","file:///workspace/$path");put("diagnostics",buildJsonArray{add(buildJsonObject{put("source","sqruff");put("severity",2);put("message",message);put("range",buildJsonObject{val p=buildJsonObject{put("line",0);put("character",column)};put("start",p);put("end",p)})})})
        })})
        fun send(message:JsonObject){val b=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(b,message.toString());out.offer(b.toByteArray())}
    }
    private class QueuedInput:InputStream(){
        val bytes=LinkedBlockingQueue<Int>();override fun read()=bytes.take()
        override fun read(b:ByteArray,off:Int,len:Int):Int{if(len==0)return 0;val first=read();if(first<0)return -1;b[off]=first.toByte();var size=1
            while(size<len){val next=bytes.poll()?:break;if(next<0){bytes.offer(-1);break};b[off+size++]=next.toByte()};return size}
        fun offer(value:ByteArray){value.forEach{bytes.add(it.toInt() and 255)}};override fun close(){bytes.offer(-1)}
    }
    companion object {
        private suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000){while(!check())delay(5)}
        private fun format(text:String)=buildJsonArray{add(buildJsonObject{put("newText",text);put("range",buildJsonObject{
            put("start",buildJsonObject{put("line",0);put("character",0)})
            put("end",buildJsonObject{put("line",text.count{it=='\n'}+if(text.isNotEmpty()&&!text.endsWith('\n'))1 else 0);put("character",text.codePointCount(0,text.length))})
        })})}
    }
}
