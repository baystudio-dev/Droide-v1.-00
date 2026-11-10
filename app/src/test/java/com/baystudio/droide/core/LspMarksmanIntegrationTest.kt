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

class LspMarksmanIntegrationTest {
    private val spec = LanguageServerRegistry.builtIns.single { it.id == "marksman" }
    @Test fun onlySupportedMarkdownExtensionsRouteToBuiltInMarksman() {
        for (path in listOf("a.md", "a.markdown", "A.MD", "A.MARKDOWN")) {
            assertEquals("markdown", LanguageRegistry.forFile(path)?.id)
            assertEquals("markdown", LanguageServerRegistry.protocolLanguageId(path))
            assertEquals(listOf("marksman", "server"), LanguageServerRegistry.forPath(path).single().commands.single())
        }
        assertEquals("markdown", LanguageRegistry.forFile("component.mdx")?.id)
        assertTrue(LanguageServerRegistry.forPath("component.mdx").isEmpty())
        assertFalse(LspServerProfiles.requiresProjectAnalysis(spec, "a.md"))
    }
    @Test fun markersHaveExactTypesAndStayInsideWorkspace() {
        Fixture().use { f ->
            val doc=f.file("nested/a.md")
            f.file("nested/.git") // worktree file is not a Marksman directory marker
            File(f.root,"nested/.marksman.toml").mkdir()
            assertEquals(f.root,LspServerProfiles.projectRoot(spec,f.root,doc))
            assertFalse(LspServerProfiles.publishesWorkspaceRoot(spec,f.root))
            File(f.root,".hg").mkdir()
            assertEquals(f.root,LspServerProfiles.projectRoot(spec,f.root,doc))
            assertTrue(LspServerProfiles.publishesWorkspaceRoot(spec,f.root))
            File(f.root,"nested/.marksman.toml").delete();f.file("nested/.marksman.toml")
            assertEquals(File(f.root,"nested"),LspServerProfiles.projectRoot(spec,f.root,doc))
        }
    }
    @Test fun symbolicMarkerNeverCreatesAProject() {
        Fixture().use { f ->
            val outside=Files.createTempDirectory("marksman-outside").toFile()
            try {
                val doc=f.file("a.md");Files.createSymbolicLink(File(f.root,".git").toPath(),outside.toPath())
                assertEquals(f.root,LspServerProfiles.projectRoot(spec,f.root,doc))
                assertFalse(LspServerProfiles.publishesWorkspaceRoot(spec,f.root))
            } finally {PathSecurity.deleteTreeNoFollow(outside)}
        }
    }
    @Test(timeout=10000) fun markerlessSessionUsesNullRootAndFullDirtyTextWithoutInventedOptions() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.manager.didOpen("a.md","# unsaved")
            assertEquals("fixture",f.manager.completionItems("a.md",1,1).single().label)
            val peer=f.host.peers.single();val init=peer.params("initialize")
            assertEquals(listOf("/usr/bin/marksman","server"),peer.argv)
            assertEquals(JsonNull,init["rootUri"]);assertTrue(init["workspaceFolders"]!!.jsonArray.isEmpty())
            assertTrue(init["initializationOptions"]!!.jsonObject.isEmpty())
            val open=peer.params("textDocument/didOpen")["textDocument"]!!.jsonObject
            assertEquals("markdown",open["languageId"]!!.jsonPrimitive.content)
            assertEquals("# unsaved",open["text"]!!.jsonPrimitive.content)
            f.manager.didChange("a.md","# changed")
            val change=peer.params("textDocument/didChange")["contentChanges"]!!.jsonArray.single().jsonObject
            assertEquals("# changed",change["text"]!!.jsonPrimitive.content);assertFalse("range" in change)
        }
    }
    @Test(timeout=10000) fun samePathMarkerCreationAndRemovalChangesSessionMode() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.manager.didOpen("a.md","# dirty");f.manager.completionItems("a.md",1,1)
            f.file(".marksman.toml");f.manager.completionItems("a.md",1,1)
            assertEquals(2,f.host.peers.size);assertFalse(f.host.peers[0].isAlive)
            assertEquals("file:///workspace",f.host.peers[1].params("initialize")["rootUri"]!!.jsonPrimitive.content)
            File(f.root,".marksman.toml").delete();f.manager.completionItems("a.md",1,1)
            assertEquals(3,f.host.peers.size);assertFalse(f.host.peers[1].isAlive)
            assertEquals(JsonNull,f.host.peers[2].params("initialize")["rootUri"])
        }
    }
    @Test(timeout=10000) fun persistedMutationRetiresOnlyMarksmanAndReplaysCurrentBuffer() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.file("a.vim")
            f.manager.didOpen("a.md","# current");f.manager.completionItems("a.md",1,1)
            f.manager.completionItems("a.vim",1,1)
            val old=f.host.peers[0];val vim=f.host.peers[1]
            f.manager.workspaceFilesChanged()
            assertFalse(old.isAlive);assertTrue(vim.isAlive);assertEquals(2,f.host.peers.size)
            f.manager.completionItems("a.md",1,1)
            assertEquals("# current",f.host.peers[2].params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout=10000) fun heldInitializationCannotPublishAfterPersistedMutation() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.host.holdInitialize=true
            val pending=async {f.manager.completionItems("a.md",1,1)}
            awaitUntil {f.host.peers.isNotEmpty()};val old=f.host.peers.single();val init=old.next("initialize")
            f.manager.workspaceFilesChanged();old.reply(init,Json.parseToJsonElement("{\"capabilities\":{\"completionProvider\":{}}}"))
            assertTrue(pending.await().isEmpty());assertFalse(old.isAlive)
            f.host.holdInitialize=false;assertEquals("fixture",f.manager.completionItems("a.md",1,1).single().label)
        }
    }
    @Test(timeout=10000) fun heldResultIsRejectedAfterMutation() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.manager.completionItems("a.md",1,1);val peer=f.host.peers.single();peer.holdCompletion=true
            val pending=async {runCatching {f.manager.completionItems("a.md",1,1)}};peer.next("textDocument/completion",1)
            f.manager.workspaceFilesChanged()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
        }
    }
    @Test(timeout=10000) fun sameIdCustomOverrideCannotReuseBuiltInRootProfile() = runBlocking<Unit> {
        var extra=emptyList<LanguageServerSpec>()
        Fixture {extra}.use { f ->
            f.file("nested/.marksman.toml");f.file("nested/a.md");f.manager.completionItems("nested/a.md",1,1)
            extra=listOf(spec.copy(source="custom",commands=listOf(listOf("custom-marksman"))))
            f.manager.completionItems("nested/a.md",1,1)
            assertEquals(listOf("/usr/bin/custom-marksman"),f.host.peers.last().argv)
            assertEquals("file:///workspace",f.host.peers.last().params("initialize")["rootUri"]!!.jsonPrimitive.content)
            assertEquals(extra,LanguageServerRegistry.forPath("component.mdx",extra))
        }
    }
    @Test fun exactSemanticLegendPreservesDuplicateIndices() {
        val provider=Json.parseToJsonElement("""{"legend":{"tokenTypes":["class","class","enumMember"],"tokenModifiers":[]},"full":{"delta":false},"range":true}""").jsonObject
        val legend=LspSemanticTokensProtocol.legendFromProvider(provider)
        assertEquals(listOf("class","class","enumMember"),legend.tokenTypes)
        val tokens=LspSemanticTokensProtocol.parseFull(Json.parseToJsonElement("""{"data":[0,0,1,1,0,0,2,1,2,0]}"""),legend)
        assertEquals(listOf("class","enumMember"),tokens.map {it.type})
    }
    @Test(timeout=10000) fun createFileActionNeverBecomesAnImplicitEdit() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("a.md");f.manager.didOpen("a.md","# current")
            val actions=f.manager.codeActionsAt("a.md",1,1)
            assertEquals("Create missing file",actions.single().title)
            assertNull(actions.single().edit)
            assertFalse(File(f.root,"missing.md").exists())
        }
    }
    private class Fixture(val extras: () -> List<LanguageServerSpec> = { emptyList() }) : AutoCloseable {
        val root=Files.createTempDirectory("droide-marksman-").toFile().canonicalFile
        val job=SupervisorJob();val scope=CoroutineScope(job+Dispatchers.IO)
        val host=Host(root)
        val manager=ProfessionalLspManager(scope,FileRepository(root),object:LspManager {
            override val diagnostics=MutableStateFlow<Map<String,List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath:String)="fallback"
            override suspend fun lsp(op:String,path:String,line:Int,col:Int)="fallback"
        },remoteProcessHost=host,extraLanguageServers=extras)
        fun file(path:String):File=File(root,path).apply { parentFile.mkdirs();writeText("# heading") }
        fun uri(path:String)=File(root,path).toURI().toString()
        override fun close() { manager.close();host.peers.forEach { it.close() };job.cancel();PathSecurity.deleteTreeNoFollow(root) }
    }
    private class Host(private val root:File):StdioProcessHost {
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        val peers=CopyOnWriteArrayList<Peer>()
        @Volatile var holdInitialize=false
        override suspend fun resolveExecutable(command:String)="/usr/bin/$command"
        override suspend fun pathMapper()=WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv:List<String>,environment:Map<String,String>,resourceLimits:ProcessResourceLimits?):HostedStdioProcess = Peer(argv,holdInitialize).also { peers+=it }
    }
    private class Peer(val argv:List<String>,val holdInitialize:Boolean):HostedStdioProcess {
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
                    "initialize" -> if (!holdInitialize) reply(message,Json.parseToJsonElement("{\"capabilities\":{\"textDocumentSync\":1,\"completionProvider\":{},\"hoverProvider\":true,\"codeActionProvider\":true}}"))
                    "textDocument/completion" -> if(!holdCompletion) reply(message,Json.parseToJsonElement("[{\"label\":\"fixture\"}]"))
                    "textDocument/codeAction" -> reply(message,Json.parseToJsonElement("""[{"title":"Create missing file","edit":{"documentChanges":[{"kind":"create","uri":"file:///workspace/missing.md"}]}}]"""))
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
