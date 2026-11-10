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

/** Exact production transport/manager; byte-stream peers model protocol, not upstream acceptance. */
class LspVimIntegrationTest {
    @Test fun exactVimFileNamesAndThreeGapsHaveHonestRouting() {
        for (path in listOf("plugin/main.vim", ".vimrc", "home/_vimrc")) {
            assertEquals("vim", LanguageRegistry.forFile(path)?.id)
            assertEquals("vim", LanguageServerRegistry.protocolLanguageId(path))
            assertEquals(listOf("vim-language-server", "--stdio"), LanguageServerRegistry.forPath(path).single().commands.single())
        }
        assertEquals("bat", LanguageServerRegistry.protocolLanguageId("run.cmd"))
        for (path in listOf("run.bat", "run.cmd", "settings.ini", "messages.properties")) {
            assertTrue(LanguageServerRegistry.forPath(path).isEmpty())
            assertNotNull(LanguageServerRegistry.unavailableReason(path))
        }
        assertEquals("vue-language-server", LanguageServerRegistry.forPath("Component.vue").single().id)
        assertEquals("svelte-language-server", LanguageServerRegistry.forPath("Component.svelte").single().id)
        assertEquals(55, LanguageRegistry.all.size)
        assertEquals(46, LanguageServerRegistry.builtIns.size)
    }

    @Test fun oldNineteenProfilesKeepWorkspaceRootAndEmptyInitializationOptions() {
        Fixture().use { f ->
            val file = f.file("nested/main.py")
            f.file("nested/.git")
            val old = LegacyLspCatalogFixture.workspaceProfiles()
            assertEquals(19, old.size)
            for (spec in old) {
                assertEquals(f.root, LspServerProfiles.projectRoot(spec, f.root, file))
                assertTrue(LspServerProfiles.initializationOptions(spec, f.root).isEmpty())
            }
        }
    }

    @Test fun projectMarkersAreNearestAndNeverSearchAboveWorkspace() {
        Fixture().use { f ->
            val spec = LanguageServerRegistry.forPath("main.vim").single()
            f.file(".git")
            val outer = f.file("plugin/main.vim")
            assertEquals(f.root, LspServerProfiles.projectRoot(spec, f.root, outer))
            f.file("nested/.git")
            val nested = f.file("nested/plugin/nested.vim")
            assertEquals(File(f.root,"nested"), LspServerProfiles.projectRoot(spec,f.root,nested))
            val sub = File(f.root,"isolated").apply { mkdirs() }
            val plain = f.file("isolated/sub/plain.vim")
            assertEquals(plain.parentFile, LspServerProfiles.projectRoot(spec,sub,plain))
            val opts=LspServerProfiles.initializationOptions(spec,plain.parentFile)
            assertEquals(listOf("."),opts["indexes"]!!.jsonObject["projectRootPatterns"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun vimOptionsDisableFabricatedRuntimePathsAndKeepActualParserDiagnostics() {
        Fixture().use { f ->
            val spec=LanguageServerRegistry.forPath("main.vim").single()
            f.file(".git")
            val opts=LspServerProfiles.initializationOptions(spec,f.root)
            assertEquals(false,opts["isNeovim"]!!.jsonPrimitive.boolean)
            assertEquals("",opts["runtimepath"]!!.jsonPrimitive.content)
            assertEquals("",opts["vimruntime"]!!.jsonPrimitive.content)
            assertEquals(false,opts["suggest"]!!.jsonObject["fromRuntimepath"]!!.jsonPrimitive.boolean)
            assertEquals(false,opts["suggest"]!!.jsonObject["fromVimruntime"]!!.jsonPrimitive.boolean)
            assertEquals(true,opts["diagnostic"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean)
            assertEquals(listOf(".git"),opts["indexes"]!!.jsonObject["projectRootPatterns"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun aSymlinkMarkerCannotSelectAnOutsideProject() {
        Fixture().use { f ->
            val target=Files.createTempDirectory("vim-marker-outside").toFile()
            try {
                val doc=f.file("nested/main.vim")
                Files.createSymbolicLink(File(f.root,".git").toPath(),target.toPath())
                val spec=LanguageServerRegistry.forPath("main.vim").single()
                assertEquals(doc.parentFile,LspServerProfiles.projectRoot(spec,f.root,doc))
            } finally { target.deleteRecursively() }
        }
    }

    @Test(timeout=10000) fun twoProjectsHaveIndependentInitializeOpenEditAndDiagnosticsOwnership() = runBlocking {
        Fixture().use { f ->
            f.file("alpha/.git");f.file("beta/.git")
            f.file("alpha/a.vim");f.file("beta/b.vim")
            f.manager.didOpen("alpha/a.vim","let a = 1")
            f.manager.didOpen("beta/b.vim","let b = 1")
            assertEquals("fixture",f.manager.completionItems("alpha/a.vim",1,1).single().label)
            assertEquals("fixture",f.manager.completionItems("beta/b.vim",1,1).single().label)
            assertEquals(2,f.host.peers.size)
            val a=f.host.peers[0];val b=f.host.peers[1]
            assertEquals(listOf("/usr/bin/vim-language-server","--stdio"),a.argv)
            assertEquals("file:///workspace/alpha",a.params("initialize")["rootUri"]!!.jsonPrimitive.content)
            assertEquals("file:///workspace/beta",b.params("initialize")["rootUri"]!!.jsonPrimitive.content)
            assertEquals("vim",a.params("textDocument/didOpen")["textDocument"]!!.jsonObject["languageId"]!!.jsonPrimitive.content)
            assertFalse(a.observed.any { it.toString().contains("beta/b.vim") })
            f.manager.didChange("alpha/a.vim","let a = 2")
            assertEquals(2,a.params("textDocument/didChange")["textDocument"]!!.jsonObject["version"]!!.jsonPrimitive.int)
            assertFalse(b.observed.any { it["method"]?.jsonPrimitive?.content=="textDocument/didChange" })
            a.publish("file:///workspace/alpha/a.vim","alpha",2)
            awaitUntil { f.manager.diagnostics.value[f.uri("alpha/a.vim")]?.singleOrNull()?.message=="alpha" }
            a.publish("file:///workspace/beta/b.vim","wrong-owner",1)
            b.publish("file:///workspace/beta/b.vim","beta",1)
            awaitUntil { f.manager.diagnostics.value[f.uri("beta/b.vim")]?.singleOrNull()?.message=="beta" }
            assertEquals("alpha",f.manager.diagnostics.value[f.uri("alpha/a.vim")]!!.single().message)
            f.manager.didClose("alpha/a.vim")
            assertTrue(a.observed.any { it["method"]?.jsonPrimitive?.content=="textDocument/didClose" })
            assertFalse(f.manager.diagnostics.value.containsKey(f.uri("alpha/a.vim")))
            assertEquals("beta",f.manager.diagnostics.value[f.uri("beta/b.vim")]!!.single().message)
            f.manager.shutdown()
            assertTrue(f.host.peers.all { !it.isAlive })
        }
    }

    @Test(timeout=10000) fun sameProjectSharesServerAndRestartReplaysLatestUnsavedBuffers() = runBlocking {
        Fixture().use { f ->
            f.file(".git");f.file("plugin/a.vim");f.file("autoload/b.vim")
            f.manager.didOpen("plugin/a.vim","let a = 1")
            f.manager.didOpen("autoload/b.vim","let b = 1")
            f.manager.completionItems("plugin/a.vim",1,1)
            f.manager.completionItems("autoload/b.vim",1,1)
            assertEquals(1,f.host.peers.size)
            f.host.peers[0].close()
            f.manager.didChange("autoload/b.vim","let b = 2")
            f.manager.completionItems("plugin/a.vim",1,1)
            assertEquals(2,f.host.peers.size)
            val opens=f.host.peers[1].observed.filter { it["method"]?.jsonPrimitive?.content=="textDocument/didOpen" }
            assertEquals(2,opens.size)
            assertTrue(opens.any { it.toString().contains("let b = 2") })
        }
    }

    @Test(timeout=10000) fun canceledVimRequestSendsCancelAndLateReplyCannotReturnItems() = runBlocking {
        Fixture().use { f ->
            f.file(".git");f.file("main.vim")
            f.manager.completionItems("main.vim",1,1)
            val peer=f.host.peers.single();peer.holdCompletion=true
            val pending=async { f.manager.completionItems("main.vim",1,1) }
            val request=peer.next("textDocument/completion",after=1)
            pending.cancelAndJoin()
            val cancel=peer.next("$/cancelRequest")
            assertEquals(request["id"],cancel["params"]!!.jsonObject["id"])
            peer.reply(request,Json.parseToJsonElement("[{\"label\":\"late\"}]"))
            assertTrue(pending.isCancelled)
        }
    }

    @Test(timeout=10000) fun markerChangeRetiresOldOwnerAndDiscardsHeldResult() = runBlocking {
        Fixture().use { f ->
            f.file(".git");f.file("sub/main.vim")
            f.manager.didOpen("sub/main.vim","let value = 1")
            f.manager.completionItems("sub/main.vim",1,1)
            val old=f.host.peers.single();old.holdCompletion=true
            val pending=async { f.manager.completionItems("sub/main.vim",1,1) }
            val request=old.next("textDocument/completion",after=1)
            f.file("sub/.git")
            old.reply(request,Json.parseToJsonElement("[{\"label\":\"stale-root\"}]"))
            assertTrue(pending.await().isEmpty())
            assertEquals("fixture",f.manager.completionItems("sub/main.vim",1,1).single().label)
            assertFalse(old.isAlive)
            assertEquals(2,f.host.peers.size)
            assertEquals("file:///workspace/sub",f.host.peers[1].params("initialize")["rootUri"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout=10000) fun editorCloseEditAndSaveRetireRecordedOwnerAfterRootChangeWithoutStartingServer() = runBlocking {
        for (action in listOf("close", "edit", "save")) Fixture().use { f ->
            f.file(".git"); f.file("sub/main.vim")
            f.manager.didOpen("sub/main.vim", "let value = 1")
            f.manager.completionItems("sub/main.vim", 1, 1)
            val old = f.host.peers.single()
            old.publish("file:///workspace/sub/main.vim", "old-root", 1)
            awaitUntil { f.manager.diagnostics.value.containsKey(f.uri("sub/main.vim")) }
            f.file("sub/.git")
            when (action) {
                "close" -> f.manager.didClose("sub/main.vim")
                "edit" -> f.manager.didChange("sub/main.vim", "let value = 2")
                "save" -> f.manager.didSaveSnapshot("sub/main.vim", "let value = 1", "let value = 2")
            }
            assertTrue(action, old.observed.any { it["method"]?.jsonPrimitive?.content == "textDocument/didClose" })
            assertFalse(action, old.isAlive)
            assertFalse(action, f.manager.diagnostics.value.containsKey(f.uri("sub/main.vim")))
            assertEquals(action, 1, f.host.peers.size)
        }
    }

    @Test(timeout=10000) fun replacingMarkerAtSameRootInvalidatesCapturedProfileAndHeldResponse() = runBlocking {
        Fixture().use { f ->
            val marker = f.file(".git"); f.file("main.vim")
            f.manager.didOpen("main.vim", "let value = 1")
            f.manager.completionItems("main.vim", 1, 1)
            val old = f.host.peers.single(); old.holdCompletion = true
            val pending = async { f.manager.completionItems("main.vim", 1, 1) }
            val request = old.next("textDocument/completion", after=1)
            assertTrue(marker.delete()); File(f.root, "plugin").mkdir()
            old.reply(request, Json.parseToJsonElement("[{\"label\":\"obsolete-marker\"}]"))
            assertTrue(pending.await().isEmpty())
            assertEquals("fixture", f.manager.completionItems("main.vim", 1, 1).single().label)
            assertFalse(old.isAlive)
            val replacement = f.host.peers[1].params("initialize")
            assertEquals(old.params("initialize")["rootUri"], replacement["rootUri"])
            val patterns = replacement["initializationOptions"]!!.jsonObject["indexes"]!!.jsonObject["projectRootPatterns"]!!.jsonArray
            assertEquals(listOf("plugin"), patterns.map { it.jsonPrimitive.content })
        }
    }

    @Test(timeout=10000) fun crashedOwnerCanCloseOrRerouteWithoutLeakingDiagnostics() = runBlocking {
        for (reroute in listOf(false, true)) Fixture().use { f ->
            f.file(".git"); f.file("sub/main.vim")
            f.manager.didOpen("sub/main.vim", "let value = 1")
            f.manager.completionItems("sub/main.vim", 1, 1)
            val old = f.host.peers.single()
            old.publish("file:///workspace/sub/main.vim", "before-crash", 1)
            awaitUntil { f.manager.diagnostics.value.containsKey(f.uri("sub/main.vim")) }
            old.close()
            if (reroute) {
                f.file("sub/.git")
                assertEquals("fixture", f.manager.completionItems("sub/main.vim", 1, 1).single().label)
                assertEquals(2, f.host.peers.size)
            } else f.manager.didClose("sub/main.vim")
            assertFalse(f.manager.diagnostics.value.containsKey(f.uri("sub/main.vim")))
        }
    }

    @Test(timeout=10000) fun failedCloseWriteRetiresUncertainTransportAndReplaysSurvivingBuffer() = runBlocking {
        Fixture().use { f ->
            f.file(".git"); f.file("a.vim"); f.file("b.vim")
            f.manager.didOpen("a.vim", "let a = 1"); f.manager.didOpen("b.vim", "let b = 2")
            f.manager.completionItems("a.vim", 1, 1)
            val old = f.host.peers.single()
            old.publish("file:///workspace/a.vim", "old", 1)
            awaitUntil { f.manager.diagnostics.value.containsKey(f.uri("a.vim")) }
            old.failCloseWrite = true
            f.manager.didClose("a.vim")
            assertFalse(old.isAlive)
            assertTrue(f.manager.diagnostics.value.isEmpty())
            assertEquals("fixture", f.manager.completionItems("b.vim", 1, 1).single().label)
            val opens = f.host.peers[1].observed.filter { it["method"]?.jsonPrimitive?.content == "textDocument/didOpen" }
            assertEquals(1, opens.size)
            assertTrue(opens.single().toString().contains("let b = 2"))
        }
    }

    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("droide-vim-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root)
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String)="fallback"
            override suspend fun lsp(op:String,path:String,line:Int,col:Int)="fallback"
        },remoteProcessHost=host)
        fun file(path:String):File=File(root,path).apply { parentFile.mkdirs();writeText("let value = 1") }
        fun uri(path:String)=File(root,path).toURI().toString()
        override fun close() { manager.close();host.peers.forEach { it.close() };job.cancel();PathSecurity.deleteTreeNoFollow(root) }
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>()
        override suspend fun resolveExecutable(command:String)="/usr/bin/$command"
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess = Peer(argv).also { peers+=it }
    }
    private class Peer(val argv:List<String>):HostedStdioProcess {
        val observed=CopyOnWriteArrayList<JsonObject>()
        private val alive=AtomicBoolean(true)
        private val out=QueuedInput();private val err=QueuedInput();private val pending=ByteArrayOutputStream()
        @Volatile var holdCompletion=false
        @Volatile var failCloseWrite=false
        override val stdin=object:OutputStream() {
            @Synchronized override fun write(b:Int) { pending.write(b) }
            @Synchronized override fun write(b:ByteArray,off:Int,len:Int) { pending.write(b,off,len) }
            @Synchronized override fun flush() {
                val message=Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset();observed+=message
                when(message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> reply(message,Json.parseToJsonElement("{\"capabilities\":{\"textDocumentSync\":2,\"completionProvider\":{},\"hoverProvider\":true}}"))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
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
        fun params(method:String)=observed.last { it["method"]?.jsonPrimitive?.content==method }["params"]!!.jsonObject
        suspend fun next(method:String,after:Int=0):JsonObject=withTimeout(2000) {
            while(true) { val matches=observed.filter { it["method"]?.jsonPrimitive?.content==method };if(matches.size>after)return@withTimeout matches[after];delay(5) }
            error("unreachable")
        }
        fun reply(request:JsonObject,result:JsonElement)=send(buildJsonObject { put("jsonrpc","2.0");put("id",request["id"]!!);put("result",result) })
        fun publish(uri:String,message:String,version:Int)=send(buildJsonObject {
            put("jsonrpc","2.0");put("method","textDocument/publishDiagnostics")
            put("params",buildJsonObject { put("uri",uri);put("version",version);put("diagnostics",buildJsonArray {
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
