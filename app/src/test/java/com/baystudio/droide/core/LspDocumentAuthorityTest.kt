package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Canonical document/revision contracts. Fake protocol peers never execute a language server. */
class LspDocumentAuthorityTest {
    @Test(timeout=10000) fun allOpenDirtyDocumentsReachFirstServerBeforeIntelligence() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "from other import fresh\n")
            f.manager.didOpen("other.py", "fresh = 42\n")
            f.manager.completionItems("main.py", 1, 1)
            val opened=f.host.observed.filter { it["method"]?.jsonPrimitive?.content == "textDocument/didOpen" }
                .map { it["params"]!!.jsonObject["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content }
            println("AUDIT replay opened=$opened")
            assertTrue("Other dirty open document must be replayed before main.py intelligence", "file:///workspace/other.py" in opened)
        }
    }
    @Test(timeout=10000) fun olderCompletionSnapshotCannotOverwriteNewerDidChangeDuringStartup() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "old = 1\n")
            f.host.discoveryEntered=CompletableDeferred(); f.host.releaseDiscovery=CompletableDeferred()
            val q=async { f.manager.completionItems("main.py",1,1,null,"old = 1\n") }
            withTimeout(2000) { f.host.discoveryEntered!!.await() }
            f.manager.didChange("main.py", "newest = 2\n")
            f.host.releaseDiscovery!!.complete(Unit);q.await()
            val opened=f.host.next("textDocument/didOpen")["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content
            println("AUDIT stale_request wire_text=${opened.trim()}")
            assertEquals("newest = 2\n",opened)
        }
    }
    @Test(timeout=10000) fun closeDuringStartupMustNotReopenClosedBuffer() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "unsaved = 1\n")
            f.host.discoveryEntered=CompletableDeferred();f.host.releaseDiscovery=CompletableDeferred()
            val q=async { f.manager.completionItems("main.py",1,1,null,"unsaved = 1\n") }
            withTimeout(2000) { f.host.discoveryEntered!!.await() }
            f.manager.didClose("main.py")
            f.host.releaseDiscovery!!.complete(Unit); q.await()
            val opened=f.host.observed.count { it["method"]?.jsonPrimitive?.content == "textDocument/didOpen" }
            println("AUDIT closed_during_startup reopened_count=$opened")
            assertEquals(0,opened)
        }
    }
    @Test(timeout=10000) fun optedOutChangesMustNotSilentlyUseStaleCompletionText() = runBlocking {
        Fixture().use { f ->
            f.host.noChange=true
            f.manager.didOpen("main.py", "old = 1\n")
            f.manager.completionItems("main.py",1,1,null,"old = 1\n")
            val attempt=runCatching { f.manager.completionItems("main.py",1,1,null,"newest = 2\n") }
            val changes=f.host.observed.count { it["method"]?.jsonPrimitive?.content == "textDocument/didChange" }
            println("AUDIT sync_none second_query_succeeded=${attempt.isSuccess} changes=$changes")
            assertTrue("A server refusing changes needs an explicit unsupported/Save result, not stale success",attempt.isFailure)
        }
    }
    @Test(timeout=10000) fun formattingAnUnopenedFileCanCaptureItsOwnRequestSnapshot() = runBlocking {
        Fixture().use { f ->
            val edit=f.manager.formatDocument("main.py")
            println("AUDIT unopened_format edit=$edit")
            assertNotNull("File-backed Agent format should not require the editor tab to be open",edit)
        }
    }
    @Test(timeout=10000) fun equivalentPathMustUseTheExistingDirtySnapshot() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py", "unsaved = 2\n")
            f.manager.completionItems("./main.py",1,1)
            val opened=f.host.next("textDocument/didOpen")["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content
            println("AUDIT alias wire_text=${opened.trim()}")
            assertEquals("unsaved = 2\n",opened)
        }
    }
    @Test(timeout=10000) fun newestUnmanagedRequestWinsAcrossSuspendedStartup() = runBlocking {
        Fixture().use { f ->
            f.host.discoveryEntered=CompletableDeferred(); f.host.releaseDiscovery=CompletableDeferred()
            val old=async { f.manager.completionItems("./main.py",1,1,null,"old request\n") }
            withTimeout(2000) { f.host.discoveryEntered!!.await() }
            val newest=async(start=CoroutineStart.UNDISPATCHED) { f.manager.completionItems("main.py",1,1,null,"new request\n") }
            f.host.releaseDiscovery!!.complete(Unit)
            old.await(); newest.await()
            val opens=f.host.messages("textDocument/didOpen")
            assertEquals(1,opens.size)
            assertEquals("new request\n",opens.single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(1,f.host.completionRequests.get())
        }
    }

    @Test(timeout=10000) fun nestedAliasesUnicodeSpacesAndSymlinkShareOneAuthority() = runBlocking {
        Fixture().use { f ->
            f.root.resolve("folder").mkdirs()
            val file=f.root.resolve("café main.py"); file.writeText("saved\n")
            Files.createSymbolicLink(f.root.resolve("alias.py").toPath(),file.toPath())
            f.manager.didOpen("./café main.py","dirty 😀\n")
            f.manager.completionItems("folder/../alias.py",1,1)
            f.manager.didChange("./alias.py","latest 😀\n")
            f.manager.completionItems("folder/../café main.py",1,1,null,"latest 😀\n")
            assertEquals(1,f.host.messages("textDocument/didOpen").size)
            assertEquals("dirty \ud83d\ude00\n",f.host.messages("textDocument/didOpen").single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
            val uri=f.host.messages("textDocument/didOpen").single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content
            assertEquals("file:///workspace/café%20main.py",uri)
            f.manager.didClose("alias.py")
            assertEquals(uri,f.host.messages("textDocument/didClose").single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
            assertEquals("saved\n",file.readText())
        }
    }

    @Test(timeout=10000) fun escapingAliasesFailBeforeDiscoveryOrTransmission() = runBlocking {
        Fixture().use { f ->
            val outside=Files.createTempFile("outside-lsp-", ".py").toFile()
            try {
                outside.writeText("private")
                Files.createSymbolicLink(f.root.resolve("escape.py").toPath(),outside.toPath())
                for (path in listOf("escape.py","../${outside.name}",outside.absolutePath)) {
                    assertTrue(runCatching { f.manager.didOpen(path,"do not transmit") }.isFailure)
                    assertTrue(runCatching { f.manager.completionItems(path,1,1) }.isFailure)
                }
                assertEquals(0,f.host.starts.get()); assertTrue(f.host.observed.isEmpty())
            } finally { outside.delete() }
        }
    }

    @Test(timeout=10000) fun mismatchedRequestCannotRewindAnAlreadyNewerEditorRevision() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py","latest\n")
            assertTrue(runCatching { f.manager.completionItems("main.py",1,1,null,"old\n") }.isFailure)
            assertEquals(0,f.host.completionRequests.get())
            f.manager.completionItems("./main.py",1,1,null,"latest\n")
            assertEquals(1,f.host.completionRequests.get())
            assertEquals("latest\n",f.host.messages("textDocument/didOpen").single()["params"]!!.jsonObject["textDocument"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        }
    }

    @Test(timeout=10000) fun centralSyncNoneGuardRejectsNewLedgerTextAndSavedDiskStillCannotPretendDelivery() = runBlocking {
        Fixture().use { f ->
            f.host.noChange=true
            f.manager.didOpen("main.py","old\n")
            f.manager.completionItems("main.py",1,1)
            assertTrue(runCatching { f.manager.didChange("main.py","new\n") }.isFailure)
            assertTrue(runCatching { f.manager.completionItems("main.py",1,1) }.isFailure)
            f.root.resolve("main.py").writeText("new\n")
            assertTrue(runCatching { f.manager.completionItems("main.py",1,1) }.isFailure)
            assertEquals(1,f.host.completionRequests.get())
            assertTrue(f.host.messages("textDocument/didChange").isEmpty())
            f.manager.didClose("main.py")
            f.manager.didOpen("main.py","new\n")
            f.manager.completionItems("main.py",1,1)
            assertEquals(2,f.host.completionRequests.get())
            assertEquals(2,f.host.messages("textDocument/didOpen").size)
        }
    }

    @Test(timeout=10000) fun noOpenNoChangeServerAllowsOnlyMatchingDiskContent() = runBlocking {
        Fixture().use { f ->
            f.host.noOpen=true
            assertTrue(runCatching { f.manager.completionItems("main.py",1,1,null,"dirty\n") }.isFailure)
            f.manager.completionItems("main.py",1,1)
            assertEquals(1,f.host.completionRequests.get())
            assertTrue(f.host.messages("textDocument/didOpen").isEmpty())
            assertTrue(f.host.messages("textDocument/didChange").isEmpty())
        }
    }

    @Test(timeout=10000) fun newerEditDuringBlockedReplayReachesWireAndObsoleteCursorIsSuppressed() = runBlocking {
        Fixture().use { f ->
            f.host.fullSync=true
            f.host.heldMethod="textDocument/didOpen"; f.host.writeEntered=CompletableDeferred();f.host.releaseWrite=CountDownLatch(1)
            f.manager.didOpen("main.py","old\n")
            val query=async { f.manager.completionItems("main.py",1,1,null,"old\n") }
            withTimeout(2000) { f.host.writeEntered!!.await() }
            val update=async(start=CoroutineStart.UNDISPATCHED) { f.manager.didChange("main.py","newest\n") }
            f.host.releaseWrite!!.countDown()
            query.await();update.await()
            assertEquals("newest\n",f.host.messages("textDocument/didChange").last()["params"]!!.jsonObject["contentChanges"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals(0,f.host.completionRequests.get())
            f.manager.completionItems("main.py",1,1,null,"newest\n")
            assertEquals(1,f.host.completionRequests.get())
        }
    }

    @Test(timeout=10000) fun closeAndSameTextReopenRejectsOldRequestGeneration() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py","same\n")
            f.host.discoveryEntered=CompletableDeferred();f.host.releaseDiscovery=CompletableDeferred()
            val query=async { f.manager.completionItems("main.py",1,1,null,"same\n") }
            withTimeout(2000) { f.host.discoveryEntered!!.await() }
            f.manager.didClose("./main.py");f.manager.didOpen("main.py","same\n")
            f.host.releaseDiscovery!!.complete(Unit);query.await()
            assertEquals(0,f.host.completionRequests.get())
            f.manager.completionItems("main.py",1,1,null,"same\n")
            assertEquals(1,f.host.completionRequests.get())
        }
    }

    @Test(timeout=10000) fun changedThenRevertedTextStillInvalidatesOldResponse() = runBlocking {
        Fixture().use { f ->
            f.host.holdCompletion=true
            f.manager.didOpen("main.py","same\n")
            val query=async { f.manager.completionItems("main.py",1,1) }
            f.host.next("textDocument/completion")
            f.manager.didChange("main.py","different\n");f.manager.didChange("main.py","same\n")
            f.host.finishCompletion()
            assertTrue(query.await().isEmpty())
        }
    }

    @Test(timeout=10000) fun unopenedFormattingRetainsImmutableBaselineAcrossDiskChange() = runBlocking {
        Fixture().use { f ->
            f.host.holdFormatting=true
            val query=async { f.manager.formatDocument("./main.py") }
            f.host.next("textDocument/formatting")
            awaitUntil { f.host.pendingFormatting != null }
            f.root.resolve("main.py").writeText("external disk edit\n")
            f.host.finishFormatting()
            val edit=query.await()!!
            assertEquals(mapOf("main.py" to "value = 1\n"),edit.expectedContent)
            assertNotEquals(edit.expectedContent["main.py"],f.root.resolve("main.py").readText())
            assertEquals("external disk edit\n",f.root.resolve("main.py").readText())
        }
    }

    @Test(timeout=10000) fun unopenedAdditionalEditTargetRemainsRejected() = runBlocking {
        Fixture().use { f ->
            f.host.renameResponse=Json.parseToJsonElement("""{"changes":{"file:///workspace/other.py":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"x"}]}}""")
            val failure=runCatching { f.manager.renameSymbol("main.py",1,1,"newName") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("Open other.py"))
            assertEquals("other = 1\n",f.root.resolve("other.py").readText())
        }
    }

    @Test(timeout=10000) fun rustAndContributedServersReplayOnlyMatchingDirtyBuffers() = runBlocking {
        val custom=LanguageServerSpec("test:rust",setOf("rust"),setOf("rs"),listOf(listOf("custom-rust","--stdio")),source="extension")
        for (extra in listOf(emptyList(),listOf(custom))) Fixture(extra).use { f ->
            f.root.resolve("src").mkdirs()
            f.root.resolve("src/lib.rs").writeText("pub fn saved() {}\n")
            f.root.resolve("src/other.rs").writeText("pub fn old() {}\n")
            f.manager.didOpen("src/lib.rs","mod other;\n")
            f.manager.didOpen("./src/other.rs","pub fn dirty() {}\n")
            f.manager.didOpen("main.py","unrelated\n")
            f.manager.completionItems("src/../src/lib.rs",1,1)
            assertEquals(if(extra.isEmpty()) "/usr/bin/rust-analyzer" else "/usr/bin/custom-rust",f.host.launchedArgv!!.first())
            val opened=f.host.messages("textDocument/didOpen").map { it["params"]!!.jsonObject["textDocument"]!!.jsonObject }
            assertEquals(setOf("file:///workspace/src/lib.rs","file:///workspace/src/other.rs"),opened.map { it["uri"]!!.jsonPrimitive.content }.toSet())
            assertTrue(opened.all { it["languageId"]!!.jsonPrimitive.content=="rust" })
            assertTrue(opened.any { it["text"]!!.jsonPrimitive.content=="pub fn dirty() {}\n" })
            val methods=f.host.observed.map { it["method"]?.jsonPrimitive?.content }
            assertTrue(methods.lastIndexOf("textDocument/didOpen") < methods.indexOf("textDocument/completion"))
        }
    }

    @Test(timeout=10000) fun replacementServerReplaysEveryLatestOpenBuffer() = runBlocking {
        Fixture().use { f ->
            f.manager.didOpen("main.py","from other import newest\n")
            f.manager.didOpen("other.py","old = 1\n")
            f.manager.completionItems("main.py",1,1)
            val first=f.host
            first.close()
            f.manager.didChange("other.py","newest = 2\n")
            f.manager.completionItems("main.py",1,1)
            assertNotSame(first,f.host)
            val opened=f.host.messages("textDocument/didOpen").map { it["params"]!!.jsonObject["textDocument"]!!.jsonObject }
            assertEquals(2,opened.size)
            assertTrue(opened.any { it["uri"]!!.jsonPrimitive.content=="file:///workspace/other.py" && it["text"]!!.jsonPrimitive.content=="newest = 2\n" })
            assertEquals(1,f.host.completionRequests.get())
        }
    }

    private class Fixture(extra: List<LanguageServerSpec> = emptyList()) : AutoCloseable {
        val root = Files.createTempDirectory("droide-lsp-lifetime-").toFile()
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.IO)
        private val router = RestartingHost(root)
        val host: Host get() = router.current
        val manager = ProfessionalLspManager(scope, FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = "fallback"
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = "fallback"
        }, remoteProcessHost = router, extraLanguageServers = { extra })
        init {
            root.resolve("main.py").writeText("value = 1\n")
            root.resolve("other.py").writeText("other = 1\n")
            root.resolve("component.tsx").writeText("export const view = <p>saved</p>;")
        }
        fun localUri(path: String) = root.resolve(path).toURI().toString()
        override fun close() {
            manager.close()
            host.releaseDiscovery?.complete(Unit)
            host.close()
            parent.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class Host(private val root: File) : StdioProcessHost, AutoCloseable {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = AtomicInteger()
        val completionRequests = AtomicInteger()
        val closed = AtomicBoolean()
        var holdInitialize = false
        var holdShutdown = false
        var publishOnInitialized = false
        var saveSnapshots = false
        var discoveryEntered: CompletableDeferred<Unit>? = null
        var releaseDiscovery: CompletableDeferred<Unit>? = null
        var releaseInitialized: CountDownLatch? = null
        var launchedArgv: List<String>? = null
        val observed = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        var noChange = false
        var noOpen = false
        var fullSync = false
        var heldMethod: String? = null
        var writeEntered: CompletableDeferred<Unit>? = null
        var releaseWrite: CountDownLatch? = null
        var holdCompletion = false
        var holdFormatting = false
        @Volatile var pendingCompletion: JsonObject? = null
        @Volatile var pendingFormatting: JsonObject? = null
        var renameResponse: JsonElement = JsonNull
        private val frames = Channel<JsonObject>(Channel.UNLIMITED)
        private val stdout = QueuedInput()
        private val stderr = QueuedInput()
        private val pending = ByteArrayOutputStream()
        private val stdin = object : OutputStream() {
            @Synchronized override fun write(b: Int) { pending.write(b) }
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
            @Synchronized override fun flush() {
                val message = Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray()))!!).jsonObject
                pending.reset()
                observed.add(message)
                frames.trySend(message)
                val method = message["method"]?.jsonPrimitive?.content
                if (method == heldMethod) {
                    writeEntered?.complete(Unit)
                    releaseWrite?.let { check(it.await(5,TimeUnit.SECONDS)) { "Blocked test write was not released" } }
                    heldMethod = null
                }
                when (method) {
                    "initialize" -> if (!holdInitialize) reply(message, buildJsonObject {
                        put("capabilities", buildJsonObject {
                            if (noOpen) put("textDocumentSync",0)
                            else if (fullSync) put("textDocumentSync",1)
                            else if (noChange) put("textDocumentSync", buildJsonObject { put("openClose", true); put("change", 0) })
                            else if (saveSnapshots) put("textDocumentSync", buildJsonObject {
                                put("openClose", true); put("change", 2)
                                put("save", buildJsonObject { put("includeText", true) })
                            }) else put("textDocumentSync", 2)
                            put("completionProvider", buildJsonObject {})
                            put("documentFormattingProvider", true)
                            put("renameProvider", true)
                        })
                    })
                    "initialized" -> {
                        if (publishOnInitialized) publish("file:///workspace/main.py", "initial diagnostic")
                        releaseInitialized?.let { check(it.await(5, TimeUnit.SECONDS)) { "Held initialize fixture was not released" } }
                    }
                    "textDocument/completion" -> {
                        completionRequests.incrementAndGet()
                        if (holdCompletion) pendingCompletion=message else reply(message, JsonArray(emptyList()))
                    }
                    "textDocument/formatting" -> if (holdFormatting) pendingFormatting=message else reply(message, formattingResult())
                    "textDocument/rename" -> reply(message,renameResponse)
                    "shutdown" -> if (!holdShutdown) reply(message, JsonNull)
                }
            }
        }
        override suspend fun resolveExecutable(command: String): String {
            discoveryEntered?.complete(Unit)
            releaseDiscovery?.let { withContext(NonCancellable) { it.await() } }
            return "/usr/bin/$command"
        }
        override suspend fun pathMapper() = WorkspacePathMapper(root, "/workspace")
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            starts.incrementAndGet()
            launchedArgv = argv.toList()
            return object : HostedStdioProcess {
                override val stdin = this@Host.stdin
                override val stdout = this@Host.stdout
                override val stderr = this@Host.stderr
                override val isAlive get() = !closed.get()
                override val exitCode get() = if (closed.get()) 0 else null
                override suspend fun awaitExit(): Int { while (!closed.get()) delay(5); return 0 }
                override fun close() = this@Host.close()
            }
        }
        fun messages(method: String): List<JsonObject> = observed.filter { it["method"]?.jsonPrimitive?.content == method }
        fun finishCompletion() { reply(requireNotNull(pendingCompletion),Json.parseToJsonElement("""[{"label":"stale","insertText":"stale"}]""")) }
        fun finishFormatting() { reply(requireNotNull(pendingFormatting),formattingResult()) }
        private fun formattingResult() = Json.parseToJsonElement("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},"newText":"formatted"}]""")
        suspend fun next(method: String): JsonObject = withTimeout(2_000) {
            var message: JsonObject
            do { message = frames.receive() } while (message["method"]?.jsonPrimitive?.content != method)
            message
        }
        fun publish(uri: String, message: String, version: Int? = null) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
            put("params", buildJsonObject {
                put("uri", uri)
                version?.let { put("version", it) }
                put("diagnostics", buildJsonArray { add(buildJsonObject {
                    put("message", message)
                    put("range", buildJsonObject {
                        put("start", buildJsonObject { put("line", 0); put("character", 0) })
                        put("end", buildJsonObject { put("line", 0); put("character", 1) })
                    })
                }) })
            })
        })
        private fun reply(request: JsonObject, result: JsonElement) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result)
        })
        private fun send(message: JsonObject) {
            val bytes = ByteArrayOutputStream()
            ContentLengthProtocol.writeMessage(bytes, message.toString())
            stdout.offer(bytes.toByteArray())
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            releaseWrite?.countDown()
            stdout.close(); stderr.close(); frames.close()
        }
    }

    private class RestartingHost(private val root: File) : StdioProcessHost {
        @Volatile var current=Host(root)
        override val executionScope=ProcessExecutionScope.LOCAL_LINUX_ARM64
        override suspend fun resolveExecutable(command: String) = current.resolveExecutable(command)
        override suspend fun pathMapper() = WorkspacePathMapper(root,"/workspace")
        override suspend fun start(argv: List<String>, environment: Map<String,String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            if (current.closed.get()) current=Host(root)
            return current.start(argv,environment,resourceLimits)
        }
    }

    private class QueuedInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        override fun read(): Int = bytes.take()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read()
            if (first < 0) return -1
            buffer[off] = first.toByte()
            var size = 1
            while (size < len) {
                val value = bytes.poll() ?: break
                if (value < 0) { bytes.offer(-1); break }
                buffer[off + size++] = value.toByte()
            }
            return size
        }
        fun offer(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }

    private companion object {
        suspend fun awaitUntil(predicate: () -> Boolean) = withTimeout(2_000) {
            while (!predicate()) delay(5)
        }
    }
}
