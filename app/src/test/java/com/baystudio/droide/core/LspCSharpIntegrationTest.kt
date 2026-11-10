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

/** Production manager and transport, authored byte-stream native peer only. No native payload or sockets. */
class LspCSharpIntegrationTest {
 @Test(timeout=12000) fun saveIsMetadataOnlyAndDeniedGrantNeverPrepares()=runBlocking {
  Fixture().use{f-> f.open();f.save();assertEquals(0,f.host.starts);assertTrue(f.host.owners.isEmpty());f.allow=false
   f.manager.enableProjectAnalysis("a.cs");assertEquals(1,f.approvals);assertTrue(f.host.owners.isEmpty());assertEquals(0,f.host.generic)
  }
 }
 @Test(timeout=12000) fun nativeStartupConfigurationCompletesWithoutReadyDeadlock()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();val peer=f.host.peers.single()
   assertEquals(0,f.host.generic);assertEquals(1,f.host.starts)
   assertFalse(peer.params("initialize").containsKey("rootPath"))
   val configuration=peer.observed.single{it["id"]==JsonPrimitive("config")}["result"] as JsonArray
   assertEquals(LspCSharpProfile.section(File(f.root,"a.slnx").path),configuration.single())
   assertEquals(configuration.single(),peer.params("workspace/didChangeConfiguration")["settings"]!!.jsonObject["csharp"])
   assertEquals(-32603,peer.observed.single{it["id"]==JsonPrimitive("unsupported")}["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
   assertTrue(f.manager.serverStatus("a.cs").contains("completeness"));assertTrue(f.manager.serverStatus("a.cs").contains("No current-document"))
  }
 }
 @Test(timeout=12000) fun completionRetainsNativeTextAndRequeriesIncompleteWithCurrentIncrementalText()=runBlocking {
  Fixture().use{f->f.open("na");f.save();f.enable();val first=f.manager.completionItems("a.cs",1,3,null,"na")
   assertEquals("NativeName",first.single().insertText);assertEquals("na",first.single().filterText)
   assertEquals(1,first.single().editStartColumn);assertTrue(first.single().applicationGuard!!.isCurrent())
   f.manager.didChange("a.cs","nam");assertFalse(first.single().applicationGuard!!.isCurrent())
   val next=f.manager.completionItems("a.cs",1,4,null,"nam");assertEquals(1,next.size)
   val p=f.host.peers.single();assertEquals(3,p.params("textDocument/completion")["context"]!!.jsonObject["triggerKind"]!!.jsonPrimitive.int)
   val change=p.params("textDocument/didChange")["contentChanges"]!!.jsonArray.single().jsonObject
   assertTrue("Original incremental negotiation must retain ordinary shared sync",change.containsKey("range"));assertEquals("m",change["text"]!!.jsonPrimitive.content)
   assertTrue(p.methods().none{it.startsWith("droide/vue/")});assertEquals("na",File(f.root,"a.cs").readText())
  }
 }
 @Test(timeout=12000) fun heldResultsAndAllOrNothingRenameUseSameCapture()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable()
   val locations=f.manager.locationItems("goToTypeDefinition","a.cs",1,1);assertTrue(locations.single().activationGuard!!.accepts())
   val hover=f.manager.heldTextResult("hover","a.cs",1,1);assertEquals("native hover",hover.text)
   val rename=f.manager.renameSymbol("a.cs",1,1,"next")!!;assertTrue(rename.applicationGuard!!.invoke())
   f.host.peers.single().unknownRename=true;assertTrue(runCatching{f.manager.renameSymbol("a.cs",1,1,"next")}.isFailure)
   f.manager.didChange("a.cs","changed");assertFalse(locations.single().activationGuard!!.accepts());assertFalse(hover.guard!!.accepts());assertFalse(rename.applicationGuard!!.invoke())
  }
 }
 @Test(timeout=12000) fun emptyDiagnosticWithoutIdRetainsMeaningfulReport()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();assertTrue(f.manager.diagnose("a.cs").contains("native error"))
   f.host.peers.single().emptyNoId=true;val text=f.manager.diagnose("a.cs");assertTrue(text.contains("retained"));assertTrue(f.manager.diagnostics.value.values.flatten().any{it.message=="native error"})
  }
 }
 @Test(timeout=12000) fun missingRootStillRevokesRecordedOwnerAndDoesNotFallback()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();f.root.deleteRecursively()
   val state=f.manager.projectAnalysisStatus("a.cs")!!;assertFalse(state.enabled);assertTrue(state.canRevoke);assertEquals(f.root.path,state.projectPath)
   f.manager.revokeProjectAnalysis("a.cs");until{!f.host.owners.single().isCurrent};assertEquals(1,f.host.starts);assertEquals(0,f.host.generic)
  }
 }
 @Test(timeout=12000) fun stableGrantRetryUsesFreshOwnerOnlyAfterVerifiedCleanup()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();val old=f.host.owners.single();f.manager.retryProjectAnalysis("a.cs");f.ready()
   assertEquals(1,f.approvals);assertEquals(2,f.host.owners.size);assertFalse(old.isCurrent)
   assertNotEquals(old.token,f.host.owners.last().token);assertEquals(old.request.consentIdentity,f.host.owners.last().request.consentIdentity)
   assertTrue(f.host.retiredBeforeNew)
  }
 }
 @Test(timeout=12000) fun unknownCleanupBlocksReplacement()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();f.host.unknownCleanup=true;f.manager.retryProjectAnalysis("a.cs")
   until{f.manager.projectAnalysisStatus("a.cs")!!.readiness==LspProjectAnalysisReadiness.FAILED};assertEquals(1,f.host.starts);assertEquals(1,f.host.owners.size)
  }
 }
 @Test(timeout=12000) fun sourceWarningRemainsVisibleAndCommandsUnavailable()=runBlocking {
  Fixture().use{f->f.open();f.save();f.enable();f.host.peers.single().warn("MSBuild load failed for authored fixture")
   until{f.manager.serverStatus("a.cs").contains("MSBuild load failed")}
   assertTrue(f.manager.codeActionsAt("a.cs",1,1).isEmpty());assertEquals(0,f.host.generic)
  }
 }

 @Test(timeout=12000) fun formattingOptionsReachCsharpAndLegacyDefaultsRemain()=runBlocking {
  Fixture().use { f ->
   f.open();f.save();f.enable()
   f.manager.formatDocument("a.cs",LspFormattingOptions(3,false))
   val explicit=f.host.peers.single().params("textDocument/formatting")["options"]!!.jsonObject
   assertEquals(JsonPrimitive(3),explicit["tabSize"]);assertEquals(JsonPrimitive(false),explicit["insertSpaces"])
   f.manager.formatDocument("a.cs")
   val defaults=f.host.peers.single().params("textDocument/formatting")["options"]!!.jsonObject
   assertEquals(JsonPrimitive(4),defaults["tabSize"]);assertEquals(JsonPrimitive(true),defaults["insertSpaces"])
  }
 }
 private class Fixture:AutoCloseable {
  val root=Files.createTempDirectory("csharp-manager").toFile().canonicalFile
  val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.Default);val host=Host(root);var allow=true;var approvals=0
  private val fallback=object:LspManager {override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap());override suspend fun diagnose(relPath:String)=error("no fallback");override suspend fun lsp(op:String,path:String,line:Int,col:Int)=error("no fallback")}
  val manager=ProfessionalLspManager(scope,FileRepository(root),fallback,remoteProcessHost=host,approveProjectAnalysis={_,current->approvals++;allow&&current()})
  suspend fun open(text:String="name"){File(root,"a.cs").writeText(text);File(root,"a.slnx").writeText("<Solution />");manager.didOpen("a.cs",text)}
  suspend fun save(){manager.saveCsharpSelection("a.cs",root.path,CSharpAnalysisSelections.Mode.EXPLICIT_SOLUTION,File(root,"a.slnx").path)}
  suspend fun enable(){manager.enableProjectAnalysis("a.cs");ready()}
  suspend fun ready(){until{manager.projectAnalysisStatus("a.cs")!!.readiness==LspProjectAnalysisReadiness.READY}}
  override fun close(){runBlocking{manager.shutdown()};host.peers.forEach{it.close()};job.cancel();root.deleteRecursively()}
 }
 private class Host(val root:File):StdioProcessHost {
  override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
  override suspend fun resolveExecutable(command:String):String? { generic++;error("No generic discovery") }
  var generic=0;var starts=0;var unknownCleanup=false;var retiredBeforeNew=true
  val owners=CopyOnWriteArrayList<Owner>();val peers=CopyOnWriteArrayList<Peer>()
  val identity=CSharpManagedLaunchIdentity("lsp.csharp-ls","0.28.0","runtime.csharp-sdk","10.0.402","runtime.csharp-helper","22.23.3","fixture",
   "a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),"e".repeat(64),"f".repeat(64),"selection","/fixture/csharp","/fixture/launch","/fixture/server","/fixture/dotnet","/fixture/sdk","/fixture/node","server-active","sdk-active","helper-active","0".repeat(64))
  override suspend fun inspectCSharpSelection(workspaceRoot:String,rootPath:String,solutionPath:String?)=CSharpSdkSelection.inspect(workspaceRoot,rootPath,solutionPath)
  override suspend fun reviewedCSharpLaunchIdentity(resolvedExecutable:String,workspaceRoot:String)=identity
  override suspend fun prepareReviewedCSharpOwner(identity:CSharpManagedLaunchIdentity,request:CSharpOwnerRequest):CSharpPreparedOwner {
   retiredBeforeNew=retiredBeforeNew&&owners.all{!it.isCurrent};return Owner(identity,request,this).also{owners+=it}
  }
  override suspend fun startReviewedCSharpInWorkspace(identity:CSharpManagedLaunchIdentity,owner:CSharpPreparedOwner,admitStart:(()->CSharpHostedProcess)->CSharpHostedProcess):CSharpHostedProcess=admitStart{starts++;Peer(owner as Owner).also{owner.peer=it;peers+=it}}
  override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess {generic++;error("No generic launch")}
 }
 private class Owner(override val identity:CSharpManagedLaunchIdentity,override val request:CSharpOwnerRequest,val host:Host):CSharpPreparedOwner {
  private val active=AtomicBoolean(true);override val isCurrent get()=active.get();override val token="fixture-${request.ownerGeneration}";var peer:Peer?=null
  override fun close(){if(active.getAndSet(false))peer?.close()}
  override suspend fun retire():CSharpTeardownReceipt {close();return CSharpTeardownReceipt(!host.unknownCleanup,true,!host.unknownCleanup,true,"Authored in-memory retirement")}
 }
 private class Peer(override val owner:Owner):CSharpHostedProcess {
  val observed=CopyOnWriteArrayList<JsonObject>();private val alive=AtomicBoolean(true);private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream()
  private var initialize:JsonObject?=null;var unknownRename=false;var emptyNoId=false
  override val stdin=object:OutputStream(){
   @Synchronized override fun write(b:Int){pending.write(b)}
   @Synchronized override fun write(b:ByteArray,off:Int,len:Int){pending.write(b,off,len)}
   @Synchronized override fun flush(){
    val m=Json.parseToJsonElement(requireNotNull(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray())))).jsonObject;pending.reset();observed+=m
    val method=(m["method"] as? JsonPrimitive)?.content
    when {
     method=="initialize" -> {
      initialize=m
      send(buildJsonObject {
       put("jsonrpc","2.0");put("id","config");put("method","workspace/configuration")
       put("params",buildJsonObject { put("items",buildJsonArray { add(buildJsonObject { put("section","csharp") }) }) })
      })
     }
     m["id"]==JsonPrimitive("config")-> {send(buildJsonObject{put("jsonrpc","2.0");put("id","unsupported");put("method","client/registerCapability");put("params",buildJsonObject{})})}
     m["id"]==JsonPrimitive("unsupported")-> reply(requireNotNull(initialize),Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":2,"completionProvider":{"triggerCharacters":["."]},"hoverProvider":true,"signatureHelpProvider":{"triggerCharacters":["(",","]},"definitionProvider":true,"implementationProvider":true,"typeDefinitionProvider":true,"referencesProvider":true,"renameProvider":true,"documentFormattingProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"diagnosticProvider":{"interFileDependencies":true,"workspaceDiagnostics":false}}}"""))
     method?.startsWith("textDocument/")==true&&m.containsKey("id")-> feature(m,method)
     method=="shutdown"->reply(m,JsonNull)
    }
   }
  }
  fun methods()=observed.mapNotNull{(it["method"] as? JsonPrimitive)?.content}
  fun params(method:String)=observed.last{it["method"]==JsonPrimitive(method)}["params"]!!.jsonObject
  private fun feature(m:JsonObject,method:String){val uri=m["params"]!!.jsonObject["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content
   val value=when(method){
    "textDocument/completion"->Json.parseToJsonElement("""{"isIncomplete":true,"items":[{"label":"NativeName","insertText":"NativeName","filterText":"na","sortText":"007"}]}""")
    "textDocument/hover"->buildJsonObject{put("contents","native hover")}
    "textDocument/signatureHelp"->Json.parseToJsonElement("""{"signatures":[{"label":"Fn(x)","parameters":[{"label":[3,4]}]}]}""")
    "textDocument/definition","textDocument/typeDefinition","textDocument/implementation","textDocument/references"->buildJsonArray{add(buildJsonObject{put("uri",uri);put("range",range())})}
    "textDocument/documentSymbol"->buildJsonArray{add(buildJsonObject{put("name","NativeSymbol");put("kind",12);put("range",range());put("selectionRange",range())})}
    "textDocument/rename"->buildJsonObject{put("changes",buildJsonObject{put(uri,buildJsonArray{add(edit())});if(unknownRename)put(uri.replace("a.cs","unknown.cs"),buildJsonArray{add(edit())})})}
    "textDocument/formatting"->buildJsonArray{add(edit())}
    "textDocument/diagnostic"->buildJsonObject{put("kind","full");if(!emptyNoId)put("resultId","identified");put("items",buildJsonArray{if(!emptyNoId)add(buildJsonObject{put("range",range());put("message","native error");put("severity",1)})})}
    else->JsonNull
   };reply(m,value)
  }
  fun warn(message:String)=send(buildJsonObject{put("jsonrpc","2.0");put("method","window/logMessage");put("params",buildJsonObject{put("type",3);put("message",message)})})
  private fun reply(m:JsonObject,result:JsonElement)=send(buildJsonObject{put("jsonrpc","2.0");put("id",m.getValue("id"));put("result",result)})
  private fun send(m:JsonObject){val b=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(b,m.toString());out.offer(b.toByteArray())}
  override val stdout:InputStream get()=out;override val stderr:InputStream get()=err;override val isAlive get()=alive.get();override val exitCode get()=if(isAlive)null else 0
  override suspend fun awaitExit():Int{while(isAlive)delay(5);return 0}
  override fun close(){if(alive.getAndSet(false)){out.close();err.close();owner.close()}}
  override suspend fun stopAndAwaitOwnedGroup()=owner.retire()
 }
 private class QueuedInput:InputStream(){private val bytes=LinkedBlockingQueue<Int>();override fun read()=bytes.take();override fun read(buffer:ByteArray,off:Int,len:Int):Int{
  if(len==0)return 0;val first=read();if(first<0)return -1;buffer[off]=first.toByte();var count=1
  while(count<len){val b=bytes.poll()?:break;if(b<0){bytes.offer(-1);break};buffer[off+count++]=b.toByte()};return count
 };fun offer(value:ByteArray){value.forEach{bytes.add(it.toInt() and 255)}};override fun close(){bytes.offer(-1)}}
 private companion object {
  suspend fun until(check:()->Boolean)=withTimeout(7000){while(!check())delay(5)}
  fun range()=buildJsonObject{put("start",buildJsonObject{put("line",0);put("character",0)});put("end",buildJsonObject{put("line",0);put("character",2)})}
  fun edit()=buildJsonObject{put("range",range());put("newText","next")}
 }
}
