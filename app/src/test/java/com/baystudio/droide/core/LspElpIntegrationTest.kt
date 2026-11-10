package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal class ElpAuthoredHost(val root: File, val guestRoot: String = root.path): StdioProcessHost {
    override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
    var starts = 0; var prepares = 0; var generic = 0; var metadataRuns = 0; var cleanupUnknown = false
    var beforePrepare: CompletableDeferred<Unit>? = null; var continuePrepare: CompletableDeferred<Unit>? = null
    var beforeInspect: CompletableDeferred<Unit>? = null; var continueInspect: CompletableDeferred<Unit>? = null
    var beforeRetire: CompletableDeferred<Unit>? = null; var continueRetire: CompletableDeferred<Unit>? = null
    var heldCompletion: Pair<Peer, JsonPrimitive>? = null; var holdCompletion = false
    val peers = CopyOnWriteArrayList<Peer>(); val owners = CopyOnWriteArrayList<Owner>()
    val h = "a".repeat(64)
    val identity = ElpManagedLaunchIdentity("lsp.elp", "2026.10.14-otp27.3", "elp-original-otp27.3-v1", h, h, h, "elp", "/authored/elp", "activation", h, 1000, emptyMap())
    fun tool(role: String) = ElpSelectedToolEntry(role, ElpFileIdentity("/authored/otp/bin/$role", "/authored/otp/bin/$role", 1, h, "authored", emptyList()), "/authored/otp")
    val tools = ElpToolSelection(tool("erl"), tool("escript"), tool("rebar3"), tool("python"))
    fun observe(path: String): ElpObservedFile {
        val file = File(if (path == guestRoot || path.startsWith(guestRoot + "/")) root.path + path.removePrefix(guestRoot) else path)
        if (!file.exists()) return ElpObservedFile(path, "absent")
        if (file.isDirectory) return ElpObservedFile(path, "directory", directoryIdentity = "directory:$path")
        val bytes = file.readBytes()
        return ElpObservedFile(path, "regular", ElpFileIdentity(path, path, bytes.size.toLong(), LspElpProjectObservation.sha(bytes), "fixture", emptyList()), bytes)
    }
    override suspend fun inspectElpSelection(workspaceRoot: String, rootPath: String, deadline: ElpDeadline): ElpSelectionInspection {
        assertEquals(root.absolutePath, workspaceRoot)
        beforeInspect?.complete(Unit); continueInspect?.await()
        val guestPath = guestRoot + rootPath.removePrefix(root.path)
        val ancestors = mutableListOf<ElpGuestAncestor>(); var path = File(guestPath).parentFile.absolutePath
        while (true) {
            val names = listOf(".elp.toml", ".elp_lint.toml", "rebar.config", "rebar.config.script", "rebar.lock", "build_info.json", "src", "include", "test")
            val markers = names.associateWith { name -> val target = if (path == "/") "/$name" else "$path/$name"
                if (target.startsWith(guestRoot + "/")) observe(target) else ElpObservedFile(target, "absent") }
            ancestors += ElpGuestAncestor(path, "directory:$path", markers)
            if (path == "/") break; path = File(path).parent ?: "/"
        }
        val id = LspElpProjectObservation.sha((rootPath + ancestors.flatMap { it.markers.values }.map { "${it.guestPath}:${it.kind}:${it.identity}" }.joinToString()).toByteArray())
        return ElpSelectionInspection(workspaceRoot, rootPath, guestPath, ancestors, tools, tools.python, id, guestWorkspaceRoot = guestRoot)
    }
    override suspend fun observeElpPaths(inspection: ElpSelectionInspection, guestPaths: List<String>, deadline: ElpDeadline) = guestPaths.associateWith(::observe)
    override suspend fun inspectElpToml(request: ElpMetadataInspectionRequest, permit: ElpMetadataInspectionPermit, deadline: ElpDeadline): ElpMetadataInspectionResult {
        permit.claim(request); metadataRuns++
        return ElpMetadataInspectionResult(request.inputSha256, h, buildJsonObject {}, complete())
    }
    override suspend fun reviewedElpLaunchIdentity(resolvedExecutable: String, workspaceRoot: String, deadline: ElpDeadline) = identity
    override suspend fun resolveExecutable(command: String): String? { generic++; error("No generic discovery before ELP authority") }
    override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess { generic++; error("No generic start") }
    override suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest, deadline: ElpDeadline): ElpPreparedOwner {
        request.permit.requireCurrent(request.observation, identity); prepares++; beforePrepare?.complete(Unit); continuePrepare?.await()
        return Owner(identity, request).also { owners += it }
    }
    override suspend fun startReviewedElp(owner: ElpPreparedOwner, deadline: ElpDeadline, admitStart: (() -> ElpHostedProcess) -> ElpHostedProcess): ElpHostedProcess = admitStart { starts++; Peer(owner).also { peers += it } }
    fun complete() = ElpTeardownReceipt(true, true, true, true, true, true, true, true, true)
    inner class Owner(override val identity: ElpManagedLaunchIdentity, override val request: ElpOwnerRequest): ElpPreparedOwner {
        val active = AtomicBoolean(true)
        override val token = "authored-$prepares"
        override val isCurrent get() = active.get() && request.permit.isCurrent
        override val runtimeEvidence = ElpRuntimeEvidence("27.3", 27, "15.2", "aarch64-linux", "/authored/otp", "/authored/otp/lib", "3.25.1", tools.consentIdentity, h, true)
        override fun fence() { active.set(false) }
        override fun close() = fence()
        override suspend fun retire(deadline: ElpDeadline): ElpTeardownReceipt { fence(); beforeRetire?.complete(Unit); continueRetire?.await(); peers.filter { it.owner === this }.forEach { it.close() }; return if (cleanupUnknown) ElpTeardownReceipt.unknown("authored unknown") else complete() }
    }
    class QueueInput: InputStream() {
        private val values = LinkedBlockingQueue<Int>()
        override fun read(): Int = values.take()
        fun add(bytes: ByteArray) { bytes.forEach { values.put(it.toInt() and 255) } }
        override fun close() { values.offer(-1) }
    }
    inner class Peer(override val owner: ElpPreparedOwner): ElpHostedProcess {
        private val output = QueueInput(); private val alive = AtomicBoolean(true)
        val frames = CopyOnWriteArrayList<JsonObject>()
        val documents = linkedMapOf<String, Pair<String, Int>>()
        override val stdout: InputStream = output
        override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())
        override val isAlive get() = alive.get()
        override val exitCode get() = if (isAlive) null else 0
        override suspend fun awaitExit(): Int { while (isAlive) delay(10); return 0 }
        override suspend fun stopAndAwaitOwnedGroup(deadline: ElpDeadline) = owner.retire(deadline)
        override fun close() { alive.set(false); output.close() }
        fun send(value: JsonObject) { val out = ByteArrayOutputStream(); ContentLengthProtocol.writeMessage(out, value.toString()); output.add(out.toByteArray()) }
        fun notification(method: String, params: JsonObject) = send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) })
        fun response(id: JsonPrimitive, result: JsonElement) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) })
        fun range() = buildJsonObject { put("start", buildJsonObject { put("line", 0); put("character", 0) }); put("end", buildJsonObject { put("line", 0); put("character", 1) }) }
        fun hierarchy(uri: String) = buildJsonObject { put("name", "foo"); put("kind", 12); put("uri", uri); put("range", range()); put("selectionRange", range()); put("data", "original") }
        fun edit(uri: String) = buildJsonObject { put("changes", buildJsonObject { put(uri, buildJsonArray { add(buildJsonObject { put("range", range()); put("newText", "g") }) }) }) }
        fun completion() = buildJsonArray { add(buildJsonObject { put("label", "map/2"); put("insertText", "map(\${1:RELATIVE_FILEPATH})"); put("insertTextFormat", 2); put("command", buildJsonObject { put("title", ""); put("command", "editor.action.triggerParameterHints") }) }) }
        fun diagnostic(uri: String, version: Int = documents[uri]!!.second) = notification("textDocument/publishDiagnostics", buildJsonObject {
            put("uri", uri); put("version", version); put("diagnostics", buildJsonArray { add(buildJsonObject { put("range", range()); put("message", "authored native report"); put("source", "elp"); put("code", "E0001"); put("severity", 1) }) }) })
        override val stdin: OutputStream = object: ByteArrayOutputStream() {
            override fun flush() {
                val bytes = toByteArray(); reset(); if (bytes.isEmpty()) return
                val obj = Json.parseToJsonElement(ContentLengthProtocol.readMessage(ByteArrayInputStream(bytes))!!).jsonObject
                frames += obj; val method = obj["method"]?.jsonPrimitive?.content; val params = obj["params"] as? JsonObject ?: buildJsonObject {}; val id = obj["id"] as? JsonPrimitive
                if (method == null && id == JsonPrimitive("compile")) {
                    notification("$/progress", buildJsonObject { put("token", "compile"); put("value", buildJsonObject { put("kind", "begin"); put("title", "ELP compiling dependencies for EqWAlizer") }) })
                    notification("$/progress", buildJsonObject { put("token", "compile"); put("value", buildJsonObject { put("kind", "end") }) }); return
                }
                if (method == "initialized") {
                    notification("elp/status", buildJsonObject { put("status", "Running") })
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", "compile"); put("method", "window/workDoneProgress/create"); put("params", buildJsonObject { put("token", "compile") }) }); return
                }
                if (method == "textDocument/didOpen" || method == "textDocument/didChange") {
                    val doc = params["textDocument"]!!.jsonObject; val uri = doc["uri"]!!.jsonPrimitive.content
                    val text = if (method.endsWith("didOpen")) doc["text"]!!.jsonPrimitive.content else params["contentChanges"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
                    documents[uri] = text to doc["version"]!!.jsonPrimitive.int; return
                }
                if (id == null || method == null) return
                val uri = (params["textDocument"] as? JsonObject)?.get("uri")?.jsonPrimitive?.content ?: documents.keys.firstOrNull().orEmpty()
                val result = when (method) {
                    "initialize" -> buildJsonObject { put("capabilities", buildJsonObject {
                        put("textDocumentSync", buildJsonObject { put("openClose", true); put("change", 2); put("save", true) })
                        put("completionProvider", buildJsonObject { put("triggerCharacters", JsonArray(listOf(JsonPrimitive(":")))) })
                        put("signatureHelpProvider", buildJsonObject { put("triggerCharacters", JsonArray(listOf(JsonPrimitive("("), JsonPrimitive(",")))) })
                        listOf("hoverProvider", "definitionProvider", "typeDefinitionProvider", "implementationProvider", "referencesProvider", "documentSymbolProvider", "workspaceSymbolProvider", "renameProvider", "callHierarchyProvider", "inlayHintProvider").forEach { put(it, true) }
                        put("codeActionProvider", buildJsonObject { put("resolveProvider", true) })
                        put("semanticTokensProvider", buildJsonObject { put("full", true); put("legend", buildJsonObject { put("tokenTypes", JsonArray(listOf(JsonPrimitive("function")))); put("tokenModifiers", JsonArray(emptyList())) }) })
                    }) }
                    "textDocument/completion" -> { if (holdCompletion) { heldCompletion = this@Peer to id; return }; completion() }
                    "textDocument/hover" -> buildJsonObject { put("contents", buildJsonObject { put("kind", "markdown"); put("value", "native authored hover") }); put("range", range()) }
                    "textDocument/signatureHelp" -> buildJsonObject { put("signatures", buildJsonArray { add(buildJsonObject { put("label", "foo(A)"); put("parameters", buildJsonArray { add(buildJsonObject { put("label", "A") }) }) }) }) }
                    "textDocument/definition", "textDocument/typeDefinition", "textDocument/implementation", "textDocument/references" -> buildJsonArray { add(buildJsonObject { put("uri", uri); put("range", range()) }) }
                    "textDocument/documentSymbol", "workspace/symbol" -> buildJsonArray { add(buildJsonObject { put("name", "foo"); put("kind", 12); put("location", buildJsonObject { put("uri", uri); put("range", range()) }) }) }
                    "textDocument/semanticTokens/full" -> buildJsonObject { put("data", JsonArray(listOf(0, 0, 1, 0, 0).map(::JsonPrimitive))) }
                    "textDocument/inlayHint" -> buildJsonArray { add(buildJsonObject { put("position", buildJsonObject { put("line", 0); put("character", 1) }); put("label", "A:") }) }
                    "textDocument/codeAction" -> buildJsonArray { add(buildJsonObject { put("title", "Native edit"); put("data", "original-action") }) }
                    "codeAction/resolve" -> JsonObject(params + ("edit" to edit(uri)))
                    "textDocument/rename" -> edit(uri)
                    "textDocument/prepareCallHierarchy" -> JsonArray(listOf(hierarchy(uri)))
                    "callHierarchy/incomingCalls", "callHierarchy/outgoingCalls" -> buildJsonArray { add(buildJsonObject { put(if (method.endsWith("incomingCalls")) "from" else "to", hierarchy(uri)); put("fromRanges", JsonArray(listOf(range()))) }) }
                    else -> JsonNull
                }
                response(id, result)
            }
        }
    }
}
internal suspend fun elpReady(manager: LspManager, path: String = "q.erl") = withTimeout(5000) {
    while (manager.projectAnalysisStatus(path)?.readiness != LspProjectAnalysisReadiness.READY) {
        check(manager.projectAnalysisStatus(path)?.readiness != LspProjectAnalysisReadiness.FAILED) { manager.serverStatus(path) }; delay(10)
    }
}
internal fun elpRoot(): File = Files.createTempDirectory("elp-authored-manager-").toFile().also { File(it, "q.erl").writeText("lists:ma"); File(it, "h.hrl").writeText("-define(X, 1).") }
class LspElpIntegrationTest {
    @Test fun deniedGrantNeverCallsAnyToolOrServer() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> false }); manager.didOpen("q.erl", "lists:ma")
        assertTrue(runCatching { manager.enableProjectAnalysis("q.erl") }.isFailure); assertEquals(0, host.prepares); assertEquals(0, host.starts); assertEquals(0, host.generic); manager.shutdown(); scope.cancel()
    }
    @Test fun realManagerNativeApisKeepOriginalCaptureAndDirtySibling() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true }); manager.didOpen("q.erl", "lists:ma"); manager.didOpen("h.hrl", "-define(X, 1).")
        manager.enableProjectAnalysis("q.erl"); elpReady(manager)
        val item = manager.completionItems("q.erl", 1, 9).single(); assertEquals("map(\${1:RELATIVE_FILEPATH})", item.insertText); assertEquals(7, item.editStartColumn); assertEquals(9, item.editEndColumn); assertTrue(item.applicationGuard!!.isCurrent())
        val hover = manager.heldTextResult("hover", "q.erl", 1, 1); assertTrue(hover.guard!!.accepts())
        assertNotNull(manager.signatureHelp("q.erl", 1, 1, "lists:ma"))
        for (op in listOf("goToDefinition", "goToTypeDefinition", "goToImplementation", "findReferences")) assertEquals(1, manager.locationItems(op, "q.erl", 1, 1).size)
        assertEquals(1, manager.symbolItems("q.erl").size); assertEquals(1, manager.workspaceSymbols("q.erl", "f").size)
        val tokens = manager.semanticTokens("q.erl", "lists:ma"); assertEquals(1, tokens.size); assertTrue(tokens.single().activationGuard!!.accepts())
        val hints = manager.inlayHints("q.erl", "lists:ma"); assertTrue(hints.single().activationGuard!!.accepts())
        val action = manager.codeActionsAt("q.erl", 1, 1).single(); val resolved = manager.resolveCodeAction("q.erl", action); assertTrue(resolved.edit!!.applicationGuard!!.invoke())
        val hierarchy = manager.prepareCallHierarchy("q.erl", 1, 1).single(); assertEquals(1, manager.incomingCalls(hierarchy).size); assertEquals(1, manager.outgoingCalls(hierarchy).size)
        assertNotNull(manager.renameSymbol("q.erl", 1, 1, "g"))
        manager.didChange("h.hrl", "-define(X, 2).")
        assertFalse(item.applicationGuard!!.isCurrent()); assertFalse(hover.guard!!.accepts()); assertFalse(hints.single().activationGuard!!.accepts()); assertFalse(tokens.single().activationGuard!!.accepts())
        assertTrue(runCatching { manager.incomingCalls(hierarchy) }.isFailure); assertTrue(runCatching { manager.resolveCodeAction("q.erl", action) }.isFailure)
        val next = manager.completionItems("q.erl", 1, 9).single(); assertTrue(next.applicationGuard!!.isCurrent())
        assertEquals("-define(X, 2).", host.peers.last().documents[LspElpProfile.uri(File(root, "h.hrl").path)]!!.first)
        assertEquals(0, host.generic); manager.shutdown(); scope.cancel()
    }
    @Test fun nativeDiagnosticCodeAndSnapshotUncertaintyAreRetained() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true }); manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager)
        val peer = host.peers.single(); val uri = LspElpProfile.uri(File(root, "q.erl").path); peer.diagnostic(uri)
        withTimeout(2000) { while (manager.diagnostics.value[uri].isNullOrEmpty()) delay(10) }
        val d = manager.diagnostics.value[uri]!!.single(); assertEquals(JsonPrimitive("E0001"), d.code); assertTrue(d.originNote!!.contains("unverified")); assertTrue(d.reportGuard!!.accepts())
        manager.codeActions("q.erl", d); assertTrue(peer.frames.last { it["method"] == JsonPrimitive("textDocument/codeAction") }.toString().contains("E0001"))
        manager.didOpen("h.hrl", "-define(X, 1)."); assertFalse(d.reportGuard!!.accepts()); assertTrue(manager.diagnostics.value.isEmpty()); assertTrue(runCatching { manager.codeActions("q.erl", d) }.isFailure)
        manager.shutdown(); scope.cancel()
    }
    @Test fun metadataPermissionIsIndependentAndLeavesNoProjectGrantOnDecline() = runBlocking {
        val root = elpRoot(); File(root, ".elp.toml").writeText(""); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val purposes = mutableListOf<LspProjectAnalysisPurpose>()
        val manager = ElpLspManager(scope, root, host, { true }, { request, _ -> purposes += request.purpose; if (request.purpose == LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION) { assertFalse(request.detail.contains("grant stays in memory")); true } else false })
        manager.didOpen("q.erl", "lists:ma"); assertTrue(runCatching { manager.enableProjectAnalysis("q.erl") }.isFailure)
        assertEquals(listOf(LspProjectAnalysisPurpose.ELP_METADATA_INSPECTION, LspProjectAnalysisPurpose.PROJECT_ANALYSIS), purposes)
        assertEquals(1, host.metadataRuns); assertEquals(0, host.prepares); assertFalse(manager.projectAnalysisStatus("q.erl").enabled); manager.shutdown(); scope.cancel()
    }
    @Test fun lateCompletionAndNativeOwnerDeathCannotPublish() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true }); manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager)
        host.holdCompletion = true; val old = async { runCatching { manager.completionItems("q.erl", 1, 9) }.getOrDefault(emptyList()) }
        withTimeout(2000) { while (host.heldCompletion == null) delay(10) }; manager.didChange("q.erl", "lists:map")
        host.heldCompletion!!.let { (peer, id) -> peer.response(id, peer.completion()) }; assertTrue(old.await().isEmpty())
        host.peers.last().close(); withTimeout(2000) { while (manager.projectAnalysisStatus("q.erl").readiness != LspProjectAnalysisReadiness.FAILED) delay(10) }
        manager.shutdown(); scope.cancel()
    }
    @Test fun closeReopenRetiresOriginalOwnerAndUnknownCleanupBlocksReplacement() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true }); manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager)
        val item = manager.completionItems("q.erl", 1, 9).single(); host.cleanupUnknown = true
        manager.didClose("q.erl"); manager.didOpen("q.erl", "lists:ma"); assertFalse(item.applicationGuard!!.isCurrent())
        withTimeout(2000) { while (!manager.serverStatus("q.erl").contains("unconfirmed")) delay(10) }
        assertTrue(runCatching { manager.retryProjectAnalysis("q.erl") }.isFailure); assertEquals(1, host.starts); manager.shutdown(); scope.cancel()
    }
    @Test fun originalFeatureDoesNotRebindAcrossInspection() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        try {
            manager.didOpen("q.erl", "lists:ma"); manager.didOpen("h.hrl", "-define(X, 1).")
            manager.enableProjectAnalysis("q.erl"); elpReady(manager)
            host.beforeInspect = CompletableDeferred(); host.continueInspect = CompletableDeferred()
            val result = async { runCatching { manager.completionItems("q.erl", 1, 9, null, "lists:ma") }.getOrDefault(emptyList()) }
            withTimeout(2000) { host.beforeInspect!!.await() }; manager.didChange("h.hrl", "-define(X, 2).")
            host.continueInspect!!.complete(Unit)
            assertTrue("Original feature must not be rebound to the newer sibling ledger", result.await().isEmpty())
        } finally { host.continueInspect?.complete(Unit); manager.shutdown(); scope.cancel() }
    }

    @Test fun mappedGuestUrisReturnOnlyCapturedLocalTargets() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root, "/workspace"); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        try {
            manager.didOpen("q.erl", "lists:ma"); manager.didOpen("h.hrl", "-define(X, 1).")
            manager.enableProjectAnalysis("q.erl"); elpReady(manager)
            assertEquals("/workspace", host.owners.single().request.rootPath)
            assertEquals(1, manager.completionItems("q.erl", 1, 9).size)
            assertEquals("q.erl", manager.locationItems("goToDefinition", "q.erl", 1, 1).single().path)
            assertEquals("q.erl", manager.symbolItems("q.erl").single().path)
            assertEquals(setOf("q.erl"), manager.renameSymbol("q.erl", 1, 1, "foo")!!.expectedContent.keys)
            val action = manager.codeActionsAt("q.erl", 1, 1).single()
            assertEquals("q.erl", manager.resolveCodeAction("q.erl", action).edit!!.edits.single().path)
            val hierarchy = manager.prepareCallHierarchy("q.erl", 1, 1).single()
            assertEquals("q.erl", hierarchy.path); assertEquals("q.erl", manager.outgoingCalls(hierarchy).single().path)
            val peer = host.peers.single(); assertEquals(setOf("file:///workspace/q.erl", "file:///workspace/h.hrl"), peer.documents.keys)
            assertFalse(peer.frames.any { it.toString().contains(root.path) })
            peer.diagnostic("file:///workspace/q.erl")
            val local = LspElpProfile.uri(File(root, "q.erl").path)
            withTimeout(2000) { while (manager.diagnostics.value[local].isNullOrEmpty()) delay(5) }
            assertEquals(setOf(local), manager.diagnostics.value.keys); assertTrue(manager.diagnostics.value[local]!!.single().reportGuard!!.accepts())
        } finally { manager.shutdown(); scope.cancel() }
    }
    @Test fun nativeIncrementalUsesExactRangeLessPrimaryAndSiblingReplacements() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        try {
            manager.didOpen("q.erl", "lists:ma"); manager.didOpen("h.hrl", "-define(X, 1).")
            manager.enableProjectAnalysis("q.erl"); elpReady(manager)
            val peer = host.peers.single()
            for (text in listOf("", "😀\r\nlists:ma", "α\nlists:ma", "lists:map")) {
                manager.didChange("q.erl", text); manager.didChange("h.hrl", text)
                val line = text.count { it == '\n' } + 1; val column = text.length - text.lastIndexOf('\n')
                assertEquals(1, manager.completionItems("q.erl", line, column).size)
                assertEquals(text, peer.documents[LspElpProfile.uri(File(root, "q.erl").path)]!!.first)
                assertEquals(text, peer.documents[LspElpProfile.uri(File(root, "h.hrl").path)]!!.first)
                peer.frames.filter { it["method"] == JsonPrimitive("textDocument/didChange") }.forEach {
                    val change = it["params"]!!.jsonObject["contentChanges"]!!.jsonArray.single().jsonObject
                    assertEquals(setOf("text"), change.keys)
                }
            }
            assertEquals(8, peer.frames.count { it["method"] == JsonPrimitive("textDocument/didChange") })
        } finally { manager.shutdown(); scope.cancel() }
    }
    @Test fun amendedForcedRetirementFencesImmediatelyAndAwaitsExactOwner() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        try {
            manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager)
            val held = manager.completionItems("q.erl", 1, 9).single(); val peer = host.peers.single()
            host.beforeRetire = CompletableDeferred(); host.continueRetire = CompletableDeferred()
            val stopped = async { manager.shutdown() }; withTimeout(2000) { host.beforeRetire!!.await() }
            assertFalse(host.owners.single().isCurrent); assertFalse(held.applicationGuard!!.isCurrent()); assertFalse(stopped.isCompleted)
            assertFalse(peer.frames.any { it["method"] in setOf(JsonPrimitive("shutdown"), JsonPrimitive("exit")) })
            host.continueRetire!!.complete(Unit); withTimeout(2000) { stopped.await() }; assertFalse(peer.isAlive)
        } finally { host.continueRetire?.complete(Unit); manager.close(); scope.cancel() }
    }
    @Test fun saveMismatchBlocksUntilSameTextEditorWitness() = runBlocking {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        try {
            manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager)
            manager.didSaveSnapshot("q.erl", "lists:ma", "lists:map")
            assertTrue(runCatching { manager.retryProjectAnalysis("q.erl") }.isFailure)
            assertTrue(runCatching { manager.heldTextResult("hover", "q.erl", 1, 1) }.isFailure)
            manager.didChange("q.erl", "lists:ma"); manager.retryProjectAnalysis("q.erl"); elpReady(manager)
            assertEquals(1, manager.completionItems("q.erl", 1, 9).size)
        } finally { manager.shutdown(); scope.cancel() }
    }

    private class PreparationFixture : AutoCloseable {
        val base = ElpAuthoredHost(elpRoot())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val settled = CompletableDeferred<Unit>()
        @Volatile var calls = 0
        lateinit var oldRequest: ElpOwnerRequest
        val host = object : StdioProcessHost by base {
            override suspend fun prepareReviewedElpOwner(identity: ElpManagedLaunchIdentity, request: ElpOwnerRequest, deadline: ElpDeadline): ElpPreparedOwner {
                val number = ++calls
                if (number == 1) {
                    oldRequest = request
                    try { withContext(NonCancellable) { entered.complete(Unit); release.await(); error("Authored old prepare settlement") } }
                    finally { settled.complete(Unit) }
                }
                return base.prepareReviewedElpOwner(identity, request, deadline)
            }
        }
        val manager = ElpLspManager(scope, base.root, host, { true }, { _, _ -> true })
        suspend fun start() { manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); withTimeout(2000) { entered.await() } }
        override fun close() { release.complete(Unit); manager.close(); scope.cancel() }
    }
    @Test fun canceledPreparationRetainsGlobalReplacementBarrier() = runBlocking {
        PreparationFixture().use { f -> f.start(); f.manager.retryProjectAnalysis("q.erl"); delay(150)
            assertEquals("Replacement preflight must await the exact canceled preparation", 1, f.calls)
            f.release.complete(Unit); elpReady(f.manager); assertEquals(2, f.calls)
        }
    }
    @Test fun retiredPreparationPermitCannotRemainCurrent() = runBlocking {
        PreparationFixture().use { f -> f.start(); assertTrue(f.oldRequest.permit.isCurrent)
            f.manager.retryProjectAnalysis("q.erl")
            assertFalse("Old attempt permit must fence synchronously even when the project grant survives Retry", f.oldRequest.permit.isCurrent)
        }
    }
    @Test fun stalePrepareFailureCannotOverwriteNewGenerationReadiness() = runBlocking {
        PreparationFixture().use { f -> f.start(); f.manager.retryProjectAnalysis("q.erl"); delay(150)
            if (f.calls > 1) elpReady(f.manager)
            f.release.complete(Unit); withTimeout(2000) { f.settled.await() }; delay(150)
            elpReady(f.manager); assertEquals(LspProjectAnalysisReadiness.READY, f.manager.projectAnalysisStatus("q.erl").readiness)
        }
    }
    @Test fun shutdownRetainsUnpublishedPreparationSettlement() = runBlocking {
        PreparationFixture().use { f -> f.start(); val stopped = async { f.manager.shutdown() }; delay(100)
            assertFalse("Shutdown must retain canceled prepublication settlement", stopped.isCompleted)
            f.release.complete(Unit); withTimeout(2000) { stopped.await() }; assertEquals(0, f.base.starts)
        }
    }
    private class ObservationFixture : AutoCloseable {
        val root = elpRoot(); val host = ElpAuthoredHost(root); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ElpLspManager(scope, root, host, { true }, { _, _ -> true })
        suspend fun ready() { manager.didOpen("q.erl", "lists:ma"); manager.enableProjectAnalysis("q.erl"); elpReady(manager) }
        suspend fun status(value: String) { host.peers.last().notification("elp/status", buildJsonObject { put("status", value) }); withTimeout(2000) {
            while (manager.projectAnalysisStatus("q.erl").readiness != if (value == "Running") LspProjectAnalysisReadiness.READY else LspProjectAnalysisReadiness.PREPARING) delay(5)
        } }
        override fun close() { host.continueInspect?.complete(Unit); manager.close(); scope.cancel() }
    }
    @Test fun positivelyObservedControllingChangeWithdrawsOldAuthority() = runBlocking { ObservationFixture().use { f ->
        f.ready(); val held = f.manager.heldTextResult("hover", "q.erl", 1, 1)
        File(f.root, "rebar.config").writeText("[]")
        val unavailable = runCatching { f.manager.completionItems("q.erl", 1, 9) }
        assertTrue(unavailable.isFailure || unavailable.getOrNull().isNullOrEmpty())
        assertFalse("Observed controlling change must invalidate old held results", held.guard!!.accepts())
        assertFalse(f.host.owners.single().isCurrent); assertNotEquals(LspProjectAnalysisReadiness.READY, f.manager.projectAnalysisStatus("q.erl").readiness)
    } }
    @Test fun positivelyObservedNestedSelectionWithdrawsOldAuthority() = runBlocking { ObservationFixture().use { f ->
        File(f.root, "src").mkdir(); File(f.root, "nested").mkdir(); File(f.root, "nested/h.hrl").writeText("header")
        f.manager.didOpen("nested/h.hrl", "header"); f.ready(); val held = f.manager.heldTextResult("hover", "q.erl", 1, 1)
        File(f.root, "nested/.elp.toml").writeText("")
        val unavailable = runCatching { f.manager.completionItems("q.erl", 1, 9) }
        assertTrue(unavailable.isFailure || unavailable.getOrNull().isNullOrEmpty())
        assertFalse("Known nested project split must invalidate old project results", held.guard!!.accepts())
    } }
    @Test fun loadingThenRunningCannotReviveOldHeldResult() = runBlocking { ObservationFixture().use { f ->
        f.ready(); val held = f.manager.heldTextResult("hover", "q.erl", 1, 1)
        f.status("Loading"); assertFalse(held.guard!!.accepts()); f.status("Running")
        assertFalse("Native Loading invalidation is monotonic across later Running", held.guard!!.accepts())
    } }
    @Test fun queuedOriginalDemandCannotRebindAfterNativeReload() = runBlocking { ObservationFixture().use { f ->
        f.ready(); f.host.beforeInspect = CompletableDeferred(); f.host.continueInspect = CompletableDeferred()
        val result = async { runCatching { f.manager.completionItems("q.erl", 1, 9) }.getOrDefault(emptyList()) }
        withTimeout(2000) { f.host.beforeInspect!!.await() }; f.status("Loading"); f.status("Running"); f.host.continueInspect!!.complete(Unit)
        assertTrue("Original demand cannot rebind across native reload", result.await().isEmpty())
    } }
}
