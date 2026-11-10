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

/** Production manager/transport with authored typed-owner streams; no CMake or Python execution. */
class LspCMakeIntegrationTest {
    @Test(timeout=10000) fun selectionSaveAndConsentAreSeparateFromLaunch() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.select()
            assertEquals(0, f.host.preparations)
            assertEquals(0, f.host.genericDiscoveries)
            assertTrue(f.approvals.isEmpty())
            assertFalse(f.manager.projectAnalysisStatus("CMakeLists.txt")!!.enabled)
            assertTrue(runCatching { f.complete() }.getOrNull().isNullOrEmpty())
            assertEquals(0, f.host.preparations)
            f.enable()
            assertEquals(0, f.host.preparations)
            val approval = f.approvals.single().detail
            assertTrue(approval.contains(f.root.path))
            assertTrue(approval.contains("/usr/bin/cmake"))
            assertTrue(approval.contains("unexpected cache"))
            assertEquals("project", f.complete().single().label)
            assertEquals(1, f.host.preparations)
            assertEquals(0, f.host.genericDiscoveries)
        }
    }
    @Test(timeout=10000) fun configuredSelectionUsesExactGuestBuildAndDisclosesEffects() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); val build=File(f.root,"build").apply {mkdir()}
            File(build,"CMakeCache.txt").writeText("CMAKE_HOME_DIRECTORY:INTERNAL=/workspace\n")
            f.select(build.path); f.enable(); f.complete()
            val approval=f.approvals.single().detail
            assertTrue(approval.contains(build.path)); assertTrue(approval.contains("/workspace/build"))
            assertTrue(approval.contains("reconfigure this exact build"))
            val init=f.host.peers.single().params("initialize")
            assertEquals("/workspace/build",init["initializationOptions"]!!.jsonObject["buildDirectory"]!!.jsonPrimitive.content)
            assertEquals("file:///workspace",init["rootUri"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun scalarWirePositionsAndFullChangesPreserveAstralCrLfAndEmptyText() = runBlocking<Unit> {
        Fixture().use { f ->
            val text="# 😀\r\n😀 pro"; f.open(text); f.select(); f.enable()
            val item=f.manager.completionItems("CMakeLists.txt",2,7,null,text).single()
            val peer=f.host.peers.single()
            assertEquals("utf-32",peer.params("initialize")["capabilities"]!!.jsonObject["general"]!!.jsonObject["positionEncodings"]!!.jsonArray.single().jsonPrimitive.content)
            assertEquals(5,peer.params("textDocument/completion")["position"]!!.jsonObject["character"]!!.jsonPrimitive.int)
            assertEquals(4,item.editStartColumn); assertEquals(7,item.editEndColumn)
            assertEquals(text,peer.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            f.manager.didChange("CMakeLists.txt","")
            peer.next("textDocument/didChange")
            assertEquals(buildJsonArray {add(buildJsonObject {put("text","")})},peer.params("textDocument/didChange")["contentChanges"])
        }
    }
    @Test(timeout=10000) fun invalidScalarBoundaryAndUnsupportedTextNeverPrepareAnOwner() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open("😀");f.select();f.enable()
            assertTrue(f.manager.completionItems("CMakeLists.txt",1,2,null,"😀").isEmpty())
            assertEquals(0,f.host.preparations)
            f.manager.didChange("CMakeLists.txt","bad\rtext")
            assertTrue(runCatching { f.manager.completionItems("CMakeLists.txt",1,1,null,"bad\rtext") }.getOrNull().isNullOrEmpty())
            assertEquals(0,f.host.preparations)
        }
    }
    @Test(timeout=10000) fun formatterAdvertisementAndUnsupportedFeaturesStayInert() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open();f.select();f.enable()
            assertNull(f.manager.formatDocument("CMakeLists.txt"))
            assertTrue(f.manager.locationItems("goToDefinition","CMakeLists.txt",1,1).isEmpty())
            assertTrue(f.manager.symbolItems("CMakeLists.txt").isEmpty())
            assertTrue(f.manager.codeActionsAt("CMakeLists.txt",1,1).isEmpty())
            val staleHierarchyItem=LspCallHierarchyItem("old",path="CMakeLists.txt",line=1,column=1,rawJson="{}")
            assertTrue(f.manager.incomingCalls(staleHierarchyItem).isEmpty())
            assertTrue(f.manager.outgoingCalls(staleHierarchyItem).isEmpty())
            assertEquals(0,f.host.preparations)
            f.complete();assertNull(f.manager.formatDocument("CMakeLists.txt"))
            assertFalse(f.host.peers.single().methods().contains("textDocument/formatting"))
            assertEquals(0,f.fallbackCalls)
        }
    }
    @Test(timeout=10000) fun saveRetiresWithoutSendingSaveOrRewindingNewestText() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open();f.select();f.enable();f.complete();val old=f.host.peers.single()
            f.manager.didChange("CMakeLists.txt","project_new")
            f.manager.didSaveSnapshot("CMakeLists.txt","older disk","stale caller")
            awaitUntil {!old.isAlive};assertEquals(1,f.host.peers.size)
            assertFalse(old.methods().contains("textDocument/didSave"))
            f.manager.completionItems("CMakeLists.txt",1,12)
            val fresh=f.host.peers.last();assertNotSame(old,fresh)
            assertEquals("project_new",fresh.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(1,f.approvals.size)
        }
    }
    @Test(timeout=10000) fun closedNullSaveCannotReopenOrStart() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open();f.select();f.enable();f.manager.didClose("CMakeLists.txt")
            f.manager.didSave("CMakeLists.txt",null)
            f.manager.didSaveSnapshot("CMakeLists.txt","pro","pro")
            assertEquals(0,f.host.preparations)
        }
    }
    @Test(timeout=10000) fun completedPopupCaptureExpiresAfterEditAndRestore() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open();f.select();f.enable();val item=f.complete().single()
            val guard=requireNotNull(item.applicationGuard);assertTrue(guard.isCurrent())
            f.manager.didChange("CMakeLists.txt","changed");f.manager.didChange("CMakeLists.txt","pro")
            assertFalse(guard.isCurrent())
        }
    }
    @Test(timeout=10000) fun revokeCancelsHeldTypedPreparationWithoutConstructingPeer() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open();f.select();f.enable();f.host.prepareGate=CompletableDeferred()
            val pending=async {runCatching {f.complete()}}
            withTimeout(2000) {f.host.prepareEntered.await()}
            f.manager.revokeProjectAnalysis("CMakeLists.txt")
            withTimeout(2000) {pending.await()};awaitUntil {f.host.prepareCanceled}
            assertTrue(f.host.peers.isEmpty())
            f.host.prepareGate=null;f.enable();assertEquals("project",f.complete().single().label)
        }
    }
    @Test(timeout=10000) fun savingBroaderSourceReplacesTheExactPriorSelectionAndRequiresApproval() = runBlocking<Unit> {
        Fixture().use { f ->
            val path="nested/CMakeLists.txt";File(f.root,path).apply {parentFile.mkdir();writeText("pro")}
            f.manager.didOpen(path,"pro")
            f.manager.saveCmakeSelection(path,File(f.root,"nested").path,null)
            f.manager.enableProjectAnalysis(path)
            f.manager.saveCmakeSelection(path,f.root.path,null)
            assertEquals(f.root.path,f.manager.cmakeSelectionStatus(path)!!.selection!!.sourceLocalCanonical)
            assertFalse(f.manager.projectAnalysisStatus(path)!!.enabled)
            assertEquals(0,f.host.preparations)
        }
    }
    private class Fixture:AutoCloseable {
        val root=Files.createTempDirectory("droide-cmake-manager-").toFile().canonicalFile
        private val job=SupervisorJob();private val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root);val approvals=CopyOnWriteArrayList<LspProjectAnalysisRequest>();var fallbackCalls=0
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String):String {fallbackCalls++;error("No fallback")}
            override suspend fun lsp(op:String,path:String,line:Int,col:Int):String {fallbackCalls++;error("No fallback")}
        },remoteProcessHost=host,approveProjectAnalysis={request,current->approvals+=request;current()})
        suspend fun open(text:String="pro") {File(root,"CMakeLists.txt").writeText(text);manager.didOpen("CMakeLists.txt",text)}
        suspend fun select(build:String?=null) {manager.saveCmakeSelection("CMakeLists.txt",root.path,build)}
        suspend fun enable() {manager.enableProjectAnalysis("CMakeLists.txt")}
        suspend fun complete()=manager.completionItems("CMakeLists.txt",1,4)
        override fun close() {manager.close();host.peers.forEach {it.close()};job.cancel();PathSecurity.deleteTreeNoFollow(root)}
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>();var preparations=0;var genericDiscoveries=0
        @Volatile var prepareGate:CompletableDeferred<Unit>?=null
        val prepareEntered=CompletableDeferred<Unit>();@Volatile var prepareCanceled=false
        private val proof=CMakeManagedLaunchIdentity("cmake-language-server","0.1.11","fixture-policy","a".repeat(64),"b".repeat(64),"c".repeat(64),"fixture-generation","/managed/cmake-language-server","/managed/cmake-language-server","/managed/bootstrap.py","/managed/supervisor.py","/managed/purelib","/usr/bin/python3.12","/usr/bin/cmake","fixture-runtime","d".repeat(64),"e".repeat(64))
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun cmakeSelectionContext(workspaceRoot:String)=CMakeSelectionContext("fixture-environment","fixture-map",pathMapper(),object:CMakeAnalysisSelections.Inspector {
            override fun directoryIdentity(directory:File,environmentIdentity:String):CMakeDirectoryIdentity {
                check(directory.isDirectory && directory.canonicalFile==directory)
                return CMakeDirectoryIdentity(environmentIdentity,1,directory.path.hashCode().toLong(),1000,448)
            }
            override fun readCache(cache:File,maximumBytes:Int):ByteArray {check(cache.isFile && cache.length()<=maximumBytes);return cache.readBytes()}
        })
        override suspend fun reviewedCMakeLaunchIdentity(resolvedExecutable:String,workspaceRoot:String)=proof
        override suspend fun prepareReviewedCMakeOwner(identity:CMakeManagedLaunchIdentity,request:CMakeOwnerRequest):CMakePreparedOwner {
            preparations++;prepareEntered.complete(Unit)
            try {prepareGate?.await()} catch(cancel:CancellationException) {prepareCanceled=true;throw cancel}
            return Owner(identity,request)
        }
        override suspend fun startReviewedCMakeInWorkspace(identity:CMakeManagedLaunchIdentity,owner:CMakePreparedOwner,admitStart:(()->CMakeHostedProcess)->CMakeHostedProcess):CMakeHostedProcess =
            admitStart {Peer(owner as Owner).also {owner.peer=it;peers+=it}}
        override suspend fun resolveExecutable(command:String):String? {genericDiscoveries++;error("CMake must not use generic discovery")}
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess=error("CMake must use typed owner")
    }
    private class Owner(override val identity:CMakeManagedLaunchIdentity,override val request:CMakeOwnerRequest):CMakePreparedOwner {
        override val ownerGeneration=request.ownerGeneration
        override val buildGuestAbsolute=request.selection.configuredBuildGuestAbsolute?:"/private/owner-$ownerGeneration/build"
        @Volatile var peer:Peer?=null
        override fun close() {peer?.close()}
        override suspend fun retire():CMakeTeardownReceipt? {close();return null}
    }
    private class Peer(override val owner:Owner):CMakeHostedProcess {
        val observed=CopyOnWriteArrayList<JsonObject>();private val alive=AtomicBoolean(true)
        private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream();private val token=Any()
        override suspend fun checkedAdmission()=if(isAlive)CMakeHostAdmissionObservation(token,owner.ownerGeneration)else null
        override fun isAdmissionCurrent(observation:CMakeHostAdmissionObservation)=isAlive&&observation.owner===token&&observation.generation==owner.ownerGeneration
        override suspend fun stopAndAwaitOwnedGroup():CMakeTeardownReceipt=error("Fake peer never attests an OS group")
        override val stdin=object:OutputStream() {
            @Synchronized override fun write(b:Int) {pending.write(b)}
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int) {pending.write(b,off,len)}
            @Synchronized override fun flush() {
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content) {
                    "initialize"->reply(message,Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-32","textDocumentSync":2,"completionProvider":{"triggerCharacters":["{","("]},"hoverProvider":true,"documentFormattingProvider":true},"serverInfo":{"name":"cmake-language-server","version":"0.1.11"}}"""))
                    "textDocument/completion"->reply(message,Json.parseToJsonElement("""[{"label":"project","kind":3}]"""))
                    "textDocument/hover"->reply(message,JsonNull)
                }
            }
        }
        override val stdout:InputStream get()=out
        override val stderr:InputStream get()=err
        override val isAlive get()=alive.get()
        override val exitCode get()=if(isAlive)null else 0
        override suspend fun awaitExit():Int {while(isAlive)delay(5);return 0}
        override fun close() {if(alive.compareAndSet(true,false)){out.close();err.close()}}
        fun methods()=observed.mapNotNull {it["method"]?.jsonPrimitive?.content}
        fun params(method:String)=observed.last {it["method"]?.jsonPrimitive?.content==method}["params"]!!.jsonObject
        suspend fun next(method:String)=withTimeout(2000) {while(method !in methods())delay(5)}
        private fun reply(request:JsonObject,result:JsonElement) {
            val response=buildJsonObject {put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result)}
            val bytes=ByteArrayOutputStream();ContentLengthProtocol.writeMessage(bytes,response.toString());out.offer(bytes.toByteArray())
        }
    }
    private class QueuedInput:InputStream() {
        private val bytes=LinkedBlockingQueue<Int>()
        override fun read()=bytes.take()
        override fun read(buffer:ByteArray,off:Int,len:Int):Int {
            if(len==0)return 0;val first=read();if(first<0)return -1
            buffer[off]=first.toByte();var size=1
            while(size<len){val next=bytes.poll()?:break;if(next<0){bytes.offer(-1);break};buffer[off+size++]=next.toByte()}
            return size
        }
        fun offer(value:ByteArray){value.forEach {bytes.add(it.toInt() and 255)}}
        override fun close(){bytes.offer(-1)}
    }
    private companion object {suspend fun awaitUntil(check:()->Boolean)=withTimeout(2000){while(!check())delay(5)}}
}
