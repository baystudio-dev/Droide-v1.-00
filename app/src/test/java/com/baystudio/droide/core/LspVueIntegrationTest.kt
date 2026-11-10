package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LspVueIntegrationTest {
    @Test(timeout = 10000) fun validNativeParameterTupleRemainsAvailable() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val help = f.manager.signatureHelp("a.vue", 1, 1, "name\n")!!
            assertEquals("Valid private TS native parameter must not disappear", 1, help.signatures.single().parameters.size)
            val parameter = help.signatures.single().parameters.single()
            assertEquals("x", parameter.label); assertEquals(3, parameter.startOffset); assertEquals(4, parameter.endOffset)
        }
    }
    @Test(timeout = 10000) fun typeDefinitionUsesPrivateNativeProviderWithCapturedGuard() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val result = f.manager.locationItems("goToTypeDefinition", "a.vue", 1, 1)
            assertTrue(f.host.peers.single().featureMethods().contains("textDocument/typeDefinition"))
            assertTrue(result.isNotEmpty()); assertTrue(result.single().activationGuard!!.accepts())
            f.manager.didChange("a.vue", "changed")
            assertFalse(result.single().activationGuard!!.accepts())
        }
    }
    @Test(timeout = 10000) fun passiveAndDeniedPathsNeverProbeGenericHealthOrCreateProcesses() = runBlocking {
        Fixture().use { f ->
            f.open(); f.allowApproval = false
            val probes = AtomicInteger(); val wrapper = ProvisioningLspManager(f.manager, { probes.incrementAndGet(); true })
            assertTrue(runCatching { wrapper.completionItems("a.vue", 1, 1) }.exceptionOrNull() is LspProjectAnalysisRequiredException)
            assertFalse(f.manager.projectAnalysisStatus("a.vue")!!.enabled)
            f.manager.enableProjectAnalysis("a.vue")
            assertEquals(0, probes.get()); assertEquals(0, f.host.discoveries.get())
            assertTrue(f.host.peers.isEmpty()); assertTrue(f.host.owners.isEmpty())
            assertEquals(0, f.fallbackCalls)
            assertTrue(f.approvals.single().detail.contains("ongoing trusted project and ancestor"))
        }
    }
    @Test(timeout = 15000) fun typedPreparationAndCompleteDirtySetPrecedeFeatures() = runBlocking {
        Fixture().use { f ->
            f.open(); f.open("dep.ts", "disk"); f.manager.didChange("dep.ts", "unsaved number")
            f.enableAndReady(); val peer = f.host.peers.single()
            assertEquals(0, f.host.genericStarts.get()); assertEquals(0, f.host.discoveries.get())
            assertEquals(1, f.host.protectedCreations.get())
            assertTrue(f.host.owners.single().request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            val docs = peer.params("droide/vue/synchronize")["documents"] as JsonArray
            assertEquals(2, docs.size)
            assertEquals("unsaved number", docs.map { it.jsonObject }.single { it["languageId"] == JsonPrimitive("typescript") }["text"]!!.jsonPrimitive.content)
            assertEquals("disk", File(f.root, "dep.ts").readText())
            assertTrue(peer.methods().none { it in setOf("textDocument/didOpen", "textDocument/didChange", "textDocument/didClose", "textDocument/didSave") })
            assertEquals(listOf("vue", "private-ts-lsp"), f.complete().map { it.nativeOrigin })
        }
    }
    @Test(timeout = 10000) fun newCompletionBatchInvalidatesOldItemsButNotRenameTicket() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val first = f.complete(); val rename = f.manager.renameSymbol("a.vue", 1, 1, "next")!!
            assertTrue(first.first().applicationGuard!!.isCurrent()); assertTrue(rename.applicationGuard!!.invoke())
            f.complete()
            assertFalse(first.first().applicationGuard!!.isCurrent()); assertTrue(rename.applicationGuard!!.invoke())
            val syncs = f.host.peers.single().methods().count { it == "droide/vue/synchronize" }
            assertEquals(1, syncs)
        }
    }
    @Test(timeout = 10000) fun newCompanionInvalidatesWholeCaptureWithoutChangingPrimaryBytes() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady(); val items = f.complete()
            f.open("new.ts", "unsaved companion")
            assertFalse(items.first().applicationGuard!!.isCurrent())
            val next = f.complete(); assertTrue(next.first().applicationGuard!!.isCurrent())
            assertEquals(2, (f.host.peers.last().params("droide/vue/synchronize")["documents"] as JsonArray).size)
        }
    }
    @Test(timeout = 10000) fun missingRootRetainsRecordedRevokeAndNeverFallsUpward() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady(); val peer = f.host.peers.single()
            f.root.deleteRecursively()
            val status = f.manager.projectAnalysisStatus("a.vue")!!
            assertEquals(f.root.path, status.projectPath); assertFalse(status.enabled); assertTrue(status.canRevoke)
            f.manager.revokeProjectAnalysis("a.vue")
            assertFalse(f.host.owners.single().isCurrent); assertFalse(peer.isAlive)
            assertEquals(1, f.host.protectedCreations.get())
        }
    }
    @Test(timeout = 10000) fun persistedSaveRetiresWithoutAutostartAndKeepsNewerEditorText() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            f.manager.didChange("a.vue", "newest\n")
            f.manager.didSaveSnapshot("a.vue", "older disk", "older caller")
            assertEquals(1, f.host.protectedCreations.get()); assertFalse(f.host.owners.single().isCurrent)
            assertTrue(runCatching { f.complete() }.exceptionOrNull() is LspProjectAnalysisNotReadyException)
            f.ready()
            val docs = f.host.peers.last().params("droide/vue/synchronize")["documents"] as JsonArray
            assertEquals("newest\n", docs.single().jsonObject["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout = 10000) fun heldNavigationSignatureAndHoverGuardRejectCompanionChange() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val locations = f.manager.locationItems("goToDefinition", "a.vue", 1, 1)
            val symbols = f.manager.symbolItems("a.vue")
            val signature = f.manager.signatureHelp("a.vue", 1, 1, "name\n")!!
            f.manager.lsp("hover", "a.vue", 1, 1); val hover = f.manager.heldResultGuard("a.vue")!!
            assertTrue(locations.single().activationGuard!!.accepts()); assertTrue(symbols.single().activationGuard!!.accepts())
            assertTrue(signature.applicationGuard!!.isCurrent()); assertTrue(hover.accepts())
            f.open("new.ts", "dirty")
            assertFalse(locations.single().activationGuard!!.accepts()); assertFalse(symbols.single().activationGuard!!.accepts())
            assertFalse(signature.applicationGuard!!.isCurrent()); assertFalse(hover.accepts())
        }
    }
    @Test(timeout = 10000) fun malformedReceiptNeverReleasesReadyOrFeatureWrite() = runBlocking {
        Fixture().use { f ->
            f.open(); f.host.badReceipt = true; f.manager.enableProjectAnalysis("a.vue")
            awaitUntil { f.manager.projectAnalysisStatus("a.vue")!!.readiness == LspProjectAnalysisReadiness.FAILED }
            assertTrue(f.host.peers.single().featureMethods().isEmpty())
            awaitUntil { !f.host.owners.single().isCurrent }
            assertFalse(f.host.owners.single().isCurrent)
        }
    }
    @Test(timeout = 10000) fun sourceErrorsCannotPublishCleanDiagnostics() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            assertTrue(f.manager.diagnose("a.vue").contains("these four sources in this captured open file"))
            f.host.peers.single().diagnosticFailure = true
            val report = f.manager.diagnose("a.vue")
            assertFalse(report.startsWith("No diagnostics reported")); assertTrue(report.contains("incomplete"))
        }
    }
    @Test(timeout = 10000) fun unknownRenameTargetRejectsWholeEdit() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady(); f.host.peers.single().unknownRename = true
            assertTrue(runCatching { f.manager.renameSymbol("a.vue", 1, 1, "next") }.isFailure)
            assertEquals("name\n", File(f.root, "a.vue").readText())
        }
    }
    @Test(timeout = 10000) fun dirtyScriptCompanionKeepsTheSameNativePair() = runBlocking {
        Fixture().use { f ->
            f.open(); f.open("dep.ts", "saved"); f.enableAndReady()
            val owner = f.host.owners.single()
            f.manager.didChange("dep.ts", "dirty companion")
            assertEquals("A TypeScript primary route must not displace its separate Vue companion ownership",
                LspProjectAnalysisReadiness.READY, f.manager.projectAnalysisStatus("a.vue")!!.readiness)
            assertTrue(owner.isCurrent)
            assertEquals(2, f.complete().size)
            assertEquals(1, f.host.protectedCreations.get())
            val docs = f.host.peers.single().params("droide/vue/synchronize")["documents"] as JsonArray
            assertEquals("dirty companion", docs.map { it.jsonObject }.single { it["languageId"] == JsonPrimitive("typescript") }["text"]!!.jsonPrimitive.content)
        }
    }
    @Test(timeout = 10000) fun successfulDiagnosticErrorsSurviveAnotherSourceFailure() = runBlocking {
        Fixture().use { f ->
            f.open(); f.enableAndReady(); f.host.peers.single().diagnosticFailure = true
            val report = f.manager.diagnose("a.vue")
            assertTrue("A failed TS source must not discard the captured native Vue error", report.contains("genuine captured Vue error"))
            assertTrue(report.contains("incomplete", ignoreCase = true))
            assertTrue(f.manager.diagnostics.value.values.flatten().any { it.message == "genuine captured Vue error" })
        }
    }

    @Test(timeout=10000) fun formattingOptionsSurviveVueOwnedOperationReentry()=runBlocking {
        Fixture().use { f ->
            f.open();f.enableAndReady()
            f.manager.formatDocument("a.vue",LspFormattingOptions(3,false))
            val options=f.host.peers.single().featureParams("textDocument/formatting")["options"]!!.jsonObject
            assertEquals(JsonPrimitive(3),options["tabSize"]);assertEquals(JsonPrimitive(false),options["insertSpaces"])
            f.manager.formatDocument("a.vue")
            assertEquals(JsonPrimitive(4),f.host.peers.single().featureParams("textDocument/formatting")["options"]!!.jsonObject["tabSize"])
        }
    }
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("vue-manager-").toFile().canonicalFile
        private val job = SupervisorJob(); private val scope = CoroutineScope(job + Dispatchers.IO)
        val host = Host(root); val approvals = CopyOnWriteArrayList<LspProjectAnalysisRequest>()
        var allowApproval = true; var fallbackCalls = 0
        val manager = ProfessionalLspManager(scope, FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String): String { fallbackCalls++; error("No fallback") }
            override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { fallbackCalls++; error("No fallback") }
        }, remoteProcessHost = host, approveProjectAnalysis = { request, current -> approvals += request; allowApproval && current() })
        suspend fun open(path: String = "a.vue", text: String = "name\n") { File(root, path).apply { parentFile.mkdirs(); writeText(text) }; manager.didOpen(path, text) }
        suspend fun enableAndReady() { manager.enableProjectAnalysis("a.vue"); ready() }
        suspend fun ready() = awaitUntil { manager.projectAnalysisStatus("a.vue")?.readiness == LspProjectAnalysisReadiness.READY }
        suspend fun complete() = manager.completionItems("a.vue", 1, 1)
        override fun close() { manager.close(); host.peers.forEach { it.close() }; job.cancel(); root.deleteRecursively() }
    }
    private class Host(val root: File) : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val discoveries = AtomicInteger(); val genericStarts = AtomicInteger(); val protectedCreations = AtomicInteger()
        val peers = CopyOnWriteArrayList<Peer>(); val owners = CopyOnWriteArrayList<Owner>()
        var badReceipt = false
        val identity = VueManagedLaunchIdentity("lsp.vue", "3.3.13", "runtime.vue-helper", "22.23.3", "fixture",
            "a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64), "fixture-selected", "/fixture/vue", "/fixture/launch",
            "/fixture/vue.cjs", "/fixture/tls.mjs", "/fixture/tsserver.js", "/fixture/typescript/lib", "/fixture/plugin/index.js",
            "/fixture/node", "/fixture/npm", "fixture-active", "fixture-helper", "e".repeat(64), "f".repeat(64), "0".repeat(64))
        override suspend fun resolveExecutable(command: String): String { discoveries.incrementAndGet(); error("No generic discovery") }
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.path)
        override suspend fun reviewedVueLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): VueManagedLaunchIdentity {
            check(resolvedExecutable == LspVueProfile.COMMAND && workspaceRoot == root.path); return identity
        }
        override suspend fun prepareReviewedVueOwner(identity: VueManagedLaunchIdentity, request: VueOwnerRequest): VuePreparedOwner =
            Owner(identity, request).also { owners += it }
        override suspend fun startReviewedVueInWorkspace(identity: VueManagedLaunchIdentity, owner: VuePreparedOwner,
            admitStart: (() -> VueHostedProcess) -> VueHostedProcess): VueHostedProcess = admitStart {
                protectedCreations.incrementAndGet(); Peer(owner as Owner, badReceipt).also { owner.peer = it; peers += it }
            }
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            genericStarts.incrementAndGet(); error("No generic launch")
        }
    }
    private class Owner(override val identity: VueManagedLaunchIdentity, override val request: VueOwnerRequest) : VuePreparedOwner {
        private val current = AtomicBoolean(true); override val token = "fixture-${request.ownerGeneration}"
        override val isCurrent get() = current.get(); var peer: Peer? = null
        override fun close() { if (current.getAndSet(false)) peer?.close() }
        override suspend fun retire(): VueTeardownReceipt { close(); return VueTeardownReceipt(false, false, false, true, "In-memory peer has no process group") }
    }
    private class Peer(override val owner: Owner, val badReceipt: Boolean) : VueHostedProcess {
        val observed = CopyOnWriteArrayList<JsonObject>(); private val alive = AtomicBoolean(true)
        private val out = QueuedInput(); private val err = QueuedInput(); private val pending = ByteArrayOutputStream()
        var diagnosticFailure = false; var unknownRename = false
        override val stdin = object : OutputStream() {
            @Synchronized override fun write(b: Int) { pending.write(b) }
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
            @Synchronized override fun flush() {
                val m = Json.parseToJsonElement(requireNotNull(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray())))).jsonObject
                pending.reset(); observed += m
                when ((m["method"] as? JsonPrimitive)?.content) {
                    "initialize" -> reply(m, Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":0,"completionProvider":{},"hoverProvider":true,"signatureHelpProvider":{"triggerCharacters":["(",","]},"definitionProvider":true,"implementationProvider":true,"typeDefinitionProvider":true,"referencesProvider":true,"renameProvider":true,"documentFormattingProvider":true,"documentSymbolProvider":true,"diagnosticProvider":{"interFileDependencies":true,"workspaceDiagnostics":false}}}"""))
                    "droide/vue/synchronize" -> { val p = m["params"]!!.jsonObject; reply(m, buildJsonObject {
                        listOf("pairEpoch", "syncSerial", "captureDigest").forEach { put(it, p.getValue(it)) }
                        if (badReceipt) put("captureDigest", "wrong")
                        put("wireVersions", buildJsonObject { (p["documents"] as JsonArray).forEach { put(it.jsonObject["uri"]!!.jsonPrimitive.content, 1) } })
                    }) }
                    "droide/vue/request" -> feature(m)
                    "shutdown" -> reply(m, JsonNull)
                    "exit" -> close()
                }
            }
        }
        fun methods() = observed.mapNotNull { (it["method"] as? JsonPrimitive)?.content }
        fun featureMethods() = observed.filter { it["method"] == JsonPrimitive("droide/vue/request") }.map { it["params"]!!.jsonObject["method"]!!.jsonPrimitive.content }
        fun params(method: String) = observed.last { it["method"] == JsonPrimitive(method) }["params"]!!.jsonObject
        fun featureParams(method: String) = observed.last {
            it["method"] == JsonPrimitive("droide/vue/request") &&
                it["params"]!!.jsonObject["method"] == JsonPrimitive(method)
        }["params"]!!.jsonObject["params"]!!.jsonObject
        private fun feature(m: JsonObject) {
            val p = m["params"]!!.jsonObject; val method = p["method"]!!.jsonPrimitive.content
            val params = p["params"]!!.jsonObject; val uri = params["textDocument"]!!.jsonObject["uri"]!!.jsonPrimitive.content
            val result: JsonElement = when (method) {
                "textDocument/completion" -> buildJsonObject { put("items", buildJsonArray {
                    for (origin in listOf("vue", "private-ts-lsp")) add(buildJsonObject {
                        put("label", "same"); put("droideVueOrigin", origin); put("droideVueBatch", p.getValue("completionBatch")); put("droideVueItem", "$origin:0")
                        put("textEdit", edit())
                    })
                }) }
                "textDocument/documentSymbol" -> buildJsonArray { add(buildJsonObject { put("name", "native"); put("kind", 12); put("range", range()); put("selectionRange", range()) }) }
                "textDocument/definition", "textDocument/typeDefinition" -> buildJsonArray { add(buildJsonObject { put("uri", uri); put("range", range()) }) }
                "textDocument/signatureHelp" -> Json.parseToJsonElement("""{"signatures":[{"label":"fn(x)","parameters":[{"label":[3,4]}]}]}""")
                "textDocument/hover" -> buildJsonObject { put("contents", "native hover") }
                "textDocument/rename" -> buildJsonObject { put("changes", buildJsonObject {
                    put(uri, buildJsonArray { add(edit()) }); if (unknownRename) put(uri.replace("a.vue", "unknown.ts"), buildJsonArray { add(edit()) })
                }) }
                "textDocument/diagnostic" -> buildJsonObject { put("kind", "full"); put("droideVueRefreshGeneration", 0); put("items", buildJsonArray {
                    if (diagnosticFailure) add(buildJsonObject { put("range", range()); put("message", "genuine captured Vue error"); put("source", "vue"); put("severity", 1) })
                }) }
                "textDocument/formatting" -> buildJsonArray { add(edit()) }
                else -> JsonNull
            }
            reply(m, buildJsonObject {
                listOf("pairEpoch", "syncSerial", "captureDigest", "requestSerial").forEach { put(it, p.getValue(it)) }
                put("sourceOutcomes", buildJsonObject {
                    val sources = if (method == "textDocument/diagnostic") listOf("vue", "ts-syntactic", "ts-semantic", "ts-suggestion")
                        else if (method == "textDocument/completion") listOf("vue", "private-ts-lsp")
                        else if (method in setOf("textDocument/documentSymbol", "textDocument/formatting")) listOf("vue") else listOf("private-ts-lsp")
                    for (source in sources) put(source, buildJsonObject { put("status", if (diagnosticFailure && source == "ts-semantic") "error" else "ok"); put("selected", 1); put("accepted", 1); put("rejected", 0) })
                }); put("result", result)
            })
        }
        private fun reply(request: JsonObject, result: JsonElement) {
            val b = ByteArrayOutputStream(); ContentLengthProtocol.writeMessage(b, buildJsonObject { put("jsonrpc", "2.0"); put("id", request.getValue("id")); put("result", result) }.toString()); out.offer(b.toByteArray())
        }
        override val stdout: InputStream get() = out; override val stderr: InputStream get() = err
        override val isAlive get() = alive.get(); override val exitCode get() = if (isAlive) null else 0
        override suspend fun awaitExit(): Int { while (isAlive) delay(5); return 0 }
        override fun close() { if (alive.getAndSet(false)) { out.close(); err.close(); owner.close() } }
        override suspend fun stopAndAwaitOwnedGroup() = owner.retire()
    }
    private class QueuedInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        override fun read() = bytes.take()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0; val first = read(); if (first < 0) return -1
            buffer[off] = first.toByte(); var count = 1
            while (count < len) { val b = bytes.poll() ?: break; if (b < 0) { bytes.offer(-1); break }; buffer[off + count++] = b.toByte() }
            return count
        }
        fun offer(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }
    private companion object {
        suspend fun awaitUntil(check: () -> Boolean) = withTimeout(6000) { while (!check()) delay(5) }
        fun range() = buildJsonObject { put("start", buildJsonObject { put("line", 0); put("character", 0) }); put("end", buildJsonObject { put("line", 0); put("character", 4) }) }
        fun edit() = buildJsonObject { put("range", range()); put("newText", "next") }
    }
}
