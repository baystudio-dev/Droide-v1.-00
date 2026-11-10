package com.baystudio.droide.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

/** Production manager and framed transport against authored streams only. No native executable, socket or project evaluation. */
class LspNimIntegrationTest {
    @Test(timeout = 10000) fun passiveAndDeniedAnalysisNeverDiscoverOrPrepareTools() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open()
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            f.manager.serverStatus("a.nim")
            assertTrue(runCatching { f.complete() }.exceptionOrNull() is LspProjectAnalysisRequiredException)
            f.manager.didChange("a.nim", "dirty 😀")
            f.manager.didSaveSnapshot("a.nim", "disk", "old caller")
            assertEquals(0, f.host.discoveries.get())
            assertEquals(0, f.host.identityChecks.get())
            f.allowApproval = false
            f.manager.enableProjectAnalysis("a.nim")
            assertEquals(1, f.approvals.size)
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            assertEquals(0, f.host.discoveries.get())
            assertTrue(f.host.owners.isEmpty())
            assertTrue(f.host.peers.isEmpty())
            assertEquals(0, f.fallbackCalls)
        }
    }

    @Test(timeout = 10000) fun approvedOwnerUsesExactRealRootConfigurationAndInitialFullDirtyReplay() = runBlocking<Unit> {
        Fixture().use { f ->
            val dirty = "let café = \"😀\"\r\n"
            f.open(text = dirty); f.open("b.nim", "")
            f.enableAndReady()
            val peer = f.host.peers.single()
            val owner = f.host.owners.single()
            assertEquals(f.root.path, owner.request.workspaceRoot)
            assertEquals(f.root.path, owner.request.rootPath)
            assertEquals(f.root.path, owner.request.projectLocalCanonical)
            assertTrue(owner.request.consentIdentity.matches(Regex("[0-9a-f]{64}")))
            assertEquals(1, f.host.protectedCreations.get())
            assertEquals(0, f.host.genericStarts.get())
            val init = peer.params("initialize")
            assertEquals(f.uri(), init["rootUri"]!!.jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), init["initializationOptions"])
            assertEquals("utf-16", init["capabilities"]!!.jsonObject["general"]!!.jsonObject["positionEncodings"]!!.jsonArray.single().jsonPrimitive.content)
            val sync = init["capabilities"]!!.jsonObject["textDocument"]!!.jsonObject["synchronization"]!!.jsonObject
            assertEquals(JsonPrimitive(false), sync["didSave"])
            val reply = peer.request("workspace/configuration", buildJsonObject {
                put("items", buildJsonArray {
                    add(buildJsonObject { put("section", "nim"); put("scopeUri", f.uri()) })
                    add(buildJsonObject { put("section", "unrelated") })
                })
            })
            assertEquals(JsonArray(listOf(LspNimProfile.configuration(), JsonNull)), reply["result"])
            for ((path, text) in listOf("a.nim" to dirty, "b.nim" to "")) {
                val frames = peer.observed.filter { it["method"]?.jsonPrimitive?.content in setOf("textDocument/didOpen", "textDocument/didChange") &&
                    it["params"]?.jsonObject?.get("textDocument")?.jsonObject?.get("uri") == JsonPrimitive(f.uri(path)) }
                assertEquals(listOf("textDocument/didOpen", "textDocument/didChange"), frames.map { it["method"]!!.jsonPrimitive.content })
                val opened = frames[0]["params"]!!.jsonObject["textDocument"]!!.jsonObject
                assertEquals(JsonPrimitive("nim"), opened["languageId"])
                assertEquals(JsonPrimitive(1), opened["version"])
                assertEquals(JsonPrimitive(text), opened["text"])
                val changed = frames[1]["params"]!!.jsonObject
                assertEquals(JsonPrimitive(2), changed["textDocument"]!!.jsonObject["version"])
                assertEquals(buildJsonArray { add(buildJsonObject { put("text", text) }) }, changed["contentChanges"])
            }
            val disclosure = f.approvals.single().detail
            assertTrue(disclosure.contains("2.2.12")); assertTrue(disclosure.contains("1.16.0"))
            assertTrue(disclosure.contains("overriding automatic Nimble compiler selection"))
            assertTrue(disclosure.contains("not a sandbox"))
        }
    }

    @Test(timeout = 10000) fun heldMetadataKeepsTypedPreparingAndDoesNotReplayDocuments() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.metadataGate = CompletableDeferred()
            f.manager.enableProjectAnalysis("a.nim")
            awaitUntil { f.host.peers.isNotEmpty() }
            val peer = f.host.peers.single()
            withTimeout(2000) { peer.metadataEntered.await() }
            val failure = runCatching { withTimeout(250) { f.complete() } }.exceptionOrNull()
            assertTrue(failure is LspProjectAnalysisNotReadyException)
            assertEquals(LspProjectAnalysisReadiness.PREPARING, (failure as LspProjectAnalysisNotReadyException).readiness)
            assertFalse(peer.methods().contains("textDocument/didOpen"))
            assertTrue(f.manager.diagnostics.value.isEmpty())
            f.host.metadataGate!!.complete(Unit)
            f.ready()
            assertEquals("fixture", f.complete().single().label)
        }
    }

    @Test(timeout = 10000) fun revokeCancelsHeldPreparationBeforeConstructingAnyPeer() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.prepareGate = CompletableDeferred()
            f.manager.enableProjectAnalysis("a.nim")
            withTimeout(2000) { f.host.prepareEntered.await() }
            f.manager.revokeProjectAnalysis("a.nim")
            awaitUntil { f.host.prepareCanceled }
            assertTrue(f.host.peers.isEmpty())
            assertEquals(0, f.host.protectedCreations.get())
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
        }
    }

    @Test(timeout = 10000) fun revokeBetweenPreparationAndProtectedCreationRejectsLateFactory() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.creationGate = CompletableDeferred()
            try {
                f.manager.enableProjectAnalysis("a.nim")
                withTimeout(2000) { f.host.creationEntered.await() }
                f.manager.revokeProjectAnalysis("a.nim")
                f.host.creationGate!!.complete(Unit)
                awaitUntil { f.host.creationReturned }
                assertEquals(0, f.host.protectedCreations.get())
                assertTrue(f.host.peers.isEmpty())
                assertFalse(f.host.owners.single().isCurrent)
            } finally { f.host.creationGate!!.complete(Unit) }
        }
    }

    @Test(timeout = 10000) fun revokeHeldInitializeRetiresExactOwnerAndIgnoresLateReply() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.holdInitialize = true
            f.manager.enableProjectAnalysis("a.nim")
            awaitUntil { f.host.peers.isNotEmpty() }
            val peer = f.host.peers.single(); peer.next("initialize")
            f.manager.revokeProjectAnalysis("a.nim")
            awaitUntil { !peer.isAlive }
            assertFalse(peer.owner.isCurrent)
            peer.finishInitialize()
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            assertFalse(peer.methods().contains("textDocument/didOpen"))
            assertEquals(1, f.host.peers.size)
        }
    }

    @Test(timeout = 10000) fun revokeHeldMetadataCannotPublishReadinessOrReplay() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.metadataGate = CompletableDeferred()
            f.manager.enableProjectAnalysis("a.nim")
            awaitUntil { f.host.peers.isNotEmpty() }
            val peer = f.host.peers.single()
            withTimeout(2000) { peer.metadataEntered.await() }
            f.manager.revokeProjectAnalysis("a.nim")
            f.host.metadataGate!!.complete(Unit)
            awaitUntil { !peer.isAlive }
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            assertFalse(peer.methods().contains("textDocument/didOpen"))
        }
    }

    @Test(timeout = 10000) fun nativeFormattingAndSaveStayMaskedAndNextDemandReplaysNewestLedger() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val old = f.host.peers.single()
            assertNull(f.manager.formatDocument("a.nim"))
            assertTrue(f.manager.inlayHints("a.nim", "name").isEmpty())
            assertTrue(f.manager.codeActionsAt("a.nim", 1, 1).isEmpty())
            f.manager.didChange("a.nim", "latest 😀")
            f.manager.didSaveSnapshot("a.nim", "older disk", "stale caller")
            awaitUntil { !old.isAlive }
            assertEquals(1, f.host.peers.size)
            assertFalse(old.methods().any { it in setOf("textDocument/didSave", "textDocument/willSave", "textDocument/willSaveWaitUntil", "textDocument/formatting") })
            assertTrue(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            assertTrue(runCatching { f.complete() }.exceptionOrNull() is LspProjectAnalysisNotReadyException)
            f.ready()
            val fresh = f.host.peers.last()
            assertNotSame(old, fresh)
            assertEquals(JsonPrimitive("latest 😀"), fresh.params("textDocument/didOpen")["textDocument"]!!.jsonObject["text"])
            assertEquals(1, f.approvals.size)
            assertEquals(0, f.fallbackCalls)
        }
    }

    @Test(timeout = 10000) fun persistedWorkspaceMutationRetiresWithoutAutomaticRestart() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.enableAndReady(); val old = f.host.peers.single()
            f.manager.workspaceFilesChanged()
            awaitUntil { !old.isAlive }
            assertEquals(1, f.host.peers.size)
            assertTrue(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            runCatching { f.complete() }; f.ready()
            assertEquals(2, f.host.peers.size)
            assertEquals(1, f.approvals.size)
        }
    }

    @Test(timeout = 10000) fun missingManagedIdentityFailsWithoutGenericProcessOrFallback() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.host.managedAvailable = false
            f.manager.enableProjectAnalysis("a.nim")
            awaitUntil { f.manager.projectAnalysisStatus("a.nim")!!.readiness == LspProjectAnalysisReadiness.FAILED }
            repeat(3) { assertTrue(runCatching { f.complete() }.exceptionOrNull() is LspProjectAnalysisNotReadyException) }
            assertEquals(1, f.host.identityChecks.get())
            assertTrue(f.host.peers.isEmpty()); assertTrue(f.host.owners.isEmpty())
            assertEquals(0, f.host.genericStarts.get()); assertEquals(0, f.fallbackCalls)
        }
    }

    @Test(timeout = 10000) fun navigationUsesOnlyCapturedOpenUtf16TargetsAndReportsOmissions() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.open("b.nim", "😀 target\r\n"); f.file("closed.nim", "target")
            f.enableAndReady()
            f.host.peers.single().locationResult = buildJsonArray {
                add(location(f.uri("b.nim"), 3, 9))
                add(location(f.uri("b.nim"), 1, 2))
                add(location(f.uri("closed.nim"), 0, 6))
                add(location("file:///outside.nim", 0, 1))
            }
            val results = f.manager.locationItems("goToDefinition", "a.nim", 1, 1)
            assertEquals(listOf(LspLocation("b.nim", 1, 4)), results)
            assertTrue(f.manager.serverStatus("a.nim").contains("omitted"))
        }
    }

    @Test(timeout = 10000) fun renamePreflightsAllCapturedTargetsAndExpiresAfterTargetEditRestore() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.open("b.nim", "😀 name\r\n"); f.enableAndReady()
            val peer = f.host.peers.single()
            peer.renameResult = edits(f.uri("a.nim") to edit(0, 4), f.uri("b.nim") to edit(3, 7))
            val rename = requireNotNull(f.manager.renameSymbol("a.nim", 1, 1, "next"))
            assertEquals(2, rename.fileCount)
            assertEquals(mapOf("a.nim" to "name", "b.nim" to "😀 name\r\n"), rename.expectedContent)
            assertEquals("😀 next\r\n", TextEditApplier.apply("😀 name\r\n", rename.edits.filter { it.path == "b.nim" }.map { it.range }))
            assertTrue(requireNotNull(rename.applicationGuard).invoke())
            f.manager.didChange("b.nim", "changed")
            f.manager.didChange("b.nim", "😀 name\r\n")
            assertFalse(rename.applicationGuard!!.invoke())
        }
    }

    @Test(timeout = 10000) fun renameRejectsWholeTransactionForUncapturedOrSplitSurrogateTarget() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.open("b.nim", "😀 name"); f.file("closed.nim", "name"); f.enableAndReady()
            val peer = f.host.peers.single()
            peer.renameResult = edits(f.uri("a.nim") to edit(0, 4), f.uri("closed.nim") to edit(0, 4))
            assertNull(runCatching { f.manager.renameSymbol("a.nim", 1, 1, "next") }.getOrNull())
            peer.renameResult = edits(f.uri("a.nim") to edit(0, 4), f.uri("b.nim") to edit(1, 2))
            assertNull(runCatching { f.manager.renameSymbol("a.nim", 1, 1, "next") }.getOrNull())
            assertEquals("name", File(f.root, "a.nim").readText())
            assertEquals("name", File(f.root, "closed.nim").readText())
        }
    }

    @Test(timeout = 10000) fun missingRootIsUnavailableAndRestorationRetainsExactPriorGrant() = runBlocking<Unit> {
        Fixture().use { f ->
            f.file("nested/pkg.nimble", "# authored metadata fixture")
            f.open("nested/a.nim", "name"); f.enableAndReady("nested/a.nim")
            val old = f.host.peers.single(); val nested = File(f.root, "nested")
            val moved = File(f.root, "temporarily-moved")
            assertTrue(nested.renameTo(moved))
            val missing = f.manager.projectAnalysisStatus("nested/a.nim")!!
            assertFalse(missing.enabled); assertTrue(missing.canRevoke)
            assertEquals(nested.path, missing.projectPath)
            awaitUntil { !old.isAlive }
            assertEquals(1, f.host.peers.size)
            assertTrue(moved.renameTo(nested))
            assertTrue(f.manager.projectAnalysisStatus("nested/a.nim")!!.enabled)
            runCatching { f.complete("nested/a.nim") }; f.ready("nested/a.nim")
            assertEquals(2, f.host.peers.size)
            assertEquals(1, f.approvals.size)
            assertEquals(nested.path, f.host.owners.last().request.rootPath)
        }
    }

    @Test(timeout = 10000) fun customNimDeclarationRequiresOwnConsentAndUsesOnlyGenericHost() = runBlocking<Unit> {
        Fixture().use { f ->
            f.open(); f.enableAndReady()
            val managed = f.host.peers.single()
            f.extras = listOf(LanguageServerSpec("nimlangserver", setOf("nim"), setOf("nim"), listOf(listOf("custom-nim", "--stdio")), source = "custom"))
            assertFalse(f.manager.projectAnalysisStatus("a.nim")!!.enabled)
            assertTrue(runCatching { f.complete() }.exceptionOrNull() is LspProjectAnalysisRequiredException)
            f.manager.enableProjectAnalysis("a.nim")
            assertEquals("fixture", f.complete().single().label)
            assertEquals(2, f.approvals.size)
            assertTrue(f.approvals.last().detail.contains("custom", ignoreCase = true))
            assertEquals(1, f.host.protectedCreations.get())
            assertEquals(1, f.host.identityChecks.get())
            assertEquals(1, f.host.genericStarts.get())
            assertEquals(listOf("/fixture/custom-nim", "--stdio"), f.host.genericPeers.single().argv)
            assertFalse(f.host.genericPeers.single().methods().contains("textDocument/didChange"))
            assertTrue(f.manager.serverStatus("a.nim").contains("unverified"))
            awaitUntil { !managed.isAlive }
        }
    }

    @Test(timeout = 12000) fun rootSearchExhaustionPreservesStatusAndRecordedRevoke() = runBlocking<Unit> {
        Fixture().use { f ->
            val directory = (1..128).joinToString("/") { "d" }
            val path = "$directory/a.nim"
            val manifest = f.file("$directory/local.nimble", "# inert")
            f.open(path); f.enableAndReady(path)
            val old = f.host.peers.single()
            assertTrue(manifest.delete())
            val unavailable = f.manager.projectAnalysisStatus(path)!!
            assertFalse(unavailable.enabled); assertTrue(unavailable.canRevoke)
            assertTrue(f.manager.serverStatus(path).contains("unavailable"))
            f.manager.revokeProjectAnalysis(path)
            awaitUntil { !old.isAlive }
            manifest.writeText("# restored")
            assertFalse(f.manager.projectAnalysisStatus(path)!!.enabled)
            assertEquals(1, f.approvals.size)
        }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("droide-nim-manager-").toFile().canonicalFile
        private val job = SupervisorJob()
        private val scope = CoroutineScope(job + Dispatchers.IO)
        val host = Host(root)
        val approvals = CopyOnWriteArrayList<LspProjectAnalysisRequest>()
        @Volatile var allowApproval = true
        @Volatile var extras: List<LanguageServerSpec> = emptyList()
        var fallbackCalls = 0
        val manager = ProfessionalLspManager(scope, FileRepository(root), object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String): String { fallbackCalls++; error("No CLI execution") }
            override suspend fun lsp(op: String, path: String, line: Int, col: Int): String { fallbackCalls++; error("No CLI execution") }
        }, remoteProcessHost = host, extraLanguageServers = { extras }, approveProjectAnalysis = { request, current ->
            approvals += request; allowApproval && current()
        })
        init { file("fixture.nimble", "# Inert authored manifest; never evaluated") }
        fun file(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        suspend fun open(path: String = "a.nim", text: String = "name") { file(path, text); manager.didOpen(path, text) }
        fun uri(path: String = "") = WorkspacePathMapper(root, root.path).remoteUriFor(if (path.isEmpty()) root else File(root, path)).trimEnd('/')
        suspend fun enableAndReady(path: String = "a.nim") { manager.enableProjectAnalysis(path); ready(path) }
        suspend fun ready(path: String = "a.nim") = awaitUntil { manager.projectAnalysisStatus(path)?.readiness == LspProjectAnalysisReadiness.READY }
        suspend fun complete(path: String = "a.nim") = manager.completionItems(path, 1, 1)
        override fun close() {
            host.creationGate?.complete(Unit); host.metadataGate?.complete(Unit); host.prepareGate?.complete(Unit)
            manager.close(); host.peers.forEach { it.close() }; host.genericPeers.forEach { it.close() }; job.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    private class Host(private val root: File) : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val discoveries = AtomicInteger(); val identityChecks = AtomicInteger()
        val protectedCreations = AtomicInteger(); val genericStarts = AtomicInteger()
        val owners = CopyOnWriteArrayList<Owner>(); val peers = CopyOnWriteArrayList<Peer>()
        val genericPeers = CopyOnWriteArrayList<WirePeer>()
        @Volatile var managedAvailable = true
        @Volatile var holdInitialize = false
        @Volatile var prepareGate: CompletableDeferred<Unit>? = null
        @Volatile var creationGate: CompletableDeferred<Unit>? = null
        @Volatile var metadataGate: CompletableDeferred<Unit>? = null
        val prepareEntered = CompletableDeferred<Unit>(); val creationEntered = CompletableDeferred<Unit>()
        @Volatile var prepareCanceled = false
        @Volatile var creationReturned = false
        private val identity = NimManagedLaunchIdentity(
            familyId = "lsp.nimlangserver", version = "1.16.0", toolchainFamilyId = "runtime.nim", toolchainVersion = "2.2.12",
            helperFamilyId = "runtime.nim-helper", helperVersion = "22.23.3", sourcePolicyRevision = "authored-fixture-policy",
            publisherArchiveSha256 = "a".repeat(64), toolchainArchiveSha256 = "b".repeat(64), helperArchiveSha256 = "c".repeat(64),
            receiptSha256 = "d".repeat(64), toolchainReceiptSha256 = "e".repeat(64), helperReceiptSha256 = "f".repeat(64),
            sealSha256 = "1".repeat(64), toolchainSealSha256 = "2".repeat(64), helperSealSha256 = "3".repeat(64),
            selectedPackageIdentity = "authored-fixture-generation", discoveredExecutable = "/fixture/nimlangserver", launchExecutable = "/fixture/launch-nim",
            serverExecutable = "/fixture/server", compilerExecutable = "/fixture/nim", backendExecutable = "/fixture/nimsuggest",
            nimbleExecutable = "/fixture/nimble", helperExecutable = "/fixture/helper",
            activationIdentity = "fixture-server", toolchainActivationIdentity = "fixture-toolchain", helperActivationIdentity = "fixture-helper",
            helperConfigSha256 = "4".repeat(64), helperSourceBindingSha256 = "5".repeat(64), closureSha256 = "6".repeat(64),
        )
        override suspend fun resolveExecutable(command: String): String { discoveries.incrementAndGet(); return "/fixture/$command" }
        override suspend fun pathMapper() = WorkspacePathMapper(root, root.path)
        override suspend fun reviewedNimLaunchIdentity(resolvedExecutable: String, workspaceRoot: String): NimManagedLaunchIdentity? {
            identityChecks.incrementAndGet()
            check(workspaceRoot == root.path && resolvedExecutable == identity.discoveredExecutable)
            return identity.takeIf { managedAvailable }
        }
        override suspend fun prepareReviewedNimOwner(identity: NimManagedLaunchIdentity, request: NimOwnerRequest): NimPreparedOwner {
            check(identity == this.identity); prepareEntered.complete(Unit)
            try { withTimeout(3000) { prepareGate?.await() } }
            catch (cancel: CancellationException) { prepareCanceled = true; throw cancel }
            return Owner(identity, request).also { owners += it }
        }
        override suspend fun startReviewedNimInWorkspace(identity: NimManagedLaunchIdentity, owner: NimPreparedOwner,
            admitStart: (() -> NimHostedProcess) -> NimHostedProcess): NimHostedProcess {
            check(identity == this.identity && owner.identity == identity)
            creationEntered.complete(Unit)
            try {
                // Deliberately finish host preparation after cancellation: manager's actual protected callback
                // must fence creation.
                withContext(NonCancellable) { withTimeout(3000) { creationGate?.await() } }
                return admitStart {
                    protectedCreations.incrementAndGet()
                    Peer(owner as Owner, holdInitialize, metadataGate).also { owner.peer = it; peers += it }
                }
            } finally { creationReturned = true }
        }
        override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
            check(argv.first() == "/fixture/custom-nim") { "Built-in Nim must use its protected typed owner" }
            genericStarts.incrementAndGet()
            return WirePeer(argv, false).also { genericPeers += it }
        }
    }

    private class Owner(override val identity: NimManagedLaunchIdentity, override val request: NimOwnerRequest) : NimPreparedOwner {
        private val current = AtomicBoolean(true)
        override val token = "authored-owner-${request.ownerGeneration}"
        override val isCurrent get() = current.get()
        @Volatile var peer: Peer? = null
        override fun close() { current.set(false); peer?.close() }
        override suspend fun retire(): NimTeardownReceipt {
            close()
            return NimTeardownReceipt(false, false, false, true, "Authored in-memory peer has no native process group")
        }
    }

    private class Peer(override val owner: Owner, holdInitialize: Boolean, private val metadataGate: CompletableDeferred<Unit>?) :
        WirePeer(listOf(owner.identity.launchExecutable), holdInitialize), NimHostedProcess {
        val metadataEntered = CompletableDeferred<Unit>()
        private val observation = NimMetadataObservation(owner.token, owner.request.ownerGeneration, "7".repeat(64),
            owner.request.rootPath + "/" + File(owner.request.projectLocalCanonical).listFiles()!!.single { it.name.endsWith(".nimble") }.name,
            owner.request.rootPath + "/a.nim")
        @Volatile private var admitted = false
        override suspend fun awaitMetadataAdmission(): NimMetadataObservation {
            metadataEntered.complete(Unit)
            withTimeout(3000) { metadataGate?.await() }
            check(owner.isCurrent && isAlive); admitted = true
            return observation
        }
        override suspend fun checkedAdmission() = observation.takeIf { admitted && owner.isCurrent && isAlive }
        override fun isAdmissionCurrent(observation: NimMetadataObservation) =
            admitted && owner.isCurrent && isAlive && observation == this.observation
        override suspend fun stopAndAwaitOwnedGroup() = owner.retire()
        override fun close() { super.close(); if (owner.isCurrent) owner.close() }
    }

    private open class WirePeer(val argv: List<String>, private val holdInitialize: Boolean) : HostedStdioProcess {
        val observed = CopyOnWriteArrayList<JsonObject>()
        private val alive = AtomicBoolean(true)
        private val out = QueuedInput(); private val err = QueuedInput(); private val pending = ByteArrayOutputStream()
        private val requestIds = AtomicInteger()
        @Volatile var locationResult: JsonElement = JsonArray(emptyList())
        @Volatile var renameResult: JsonElement = JsonNull
        override val stdin = object : OutputStream() {
            @Synchronized override fun write(b: Int) { pending.write(b) }
            @Synchronized override fun write(b: ByteArray, off: Int, len: Int) { pending.write(b, off, len) }
            @Synchronized override fun flush() {
                val message = Json.parseToJsonElement(requireNotNull(ContentLengthProtocol.readMessage(ByteArrayInputStream(pending.toByteArray())))).jsonObject
                pending.reset(); observed += message
                when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" -> if (!holdInitialize) reply(message, capabilities())
                    "textDocument/completion" -> reply(message, Json.parseToJsonElement("""[{"label":"fixture","kind":3}]"""))
                    "textDocument/definition", "textDocument/references" -> reply(message, locationResult)
                    "textDocument/rename" -> reply(message, renameResult)
                    "textDocument/hover" -> reply(message, JsonNull)
                    "textDocument/formatting" -> reply(message, JsonArray(emptyList()))
                    "shutdown" -> reply(message, JsonNull)
                    "exit" -> close()
                }
            }
        }
        override val stdout: InputStream get() = out
        override val stderr: InputStream get() = err
        override val isAlive get() = alive.get()
        override val exitCode get() = if (isAlive) null else 0
        override suspend fun awaitExit(): Int { withTimeout(5000) { while (isAlive) delay(5) }; return 0 }
        override fun close() { if (alive.compareAndSet(true, false)) { out.close(); err.close() } }
        fun methods() = observed.mapNotNull { it["method"]?.jsonPrimitive?.content }
        fun params(method: String) = observed.last { it["method"] == JsonPrimitive(method) }["params"]!!.jsonObject
        suspend fun next(method: String) = awaitUntil { method in methods() }
        fun finishInitialize() = reply(observed.first { it["method"] == JsonPrimitive("initialize") }, capabilities())
        suspend fun request(method: String, params: JsonElement): JsonObject {
            val id = JsonPrimitive("authored-callback-${requestIds.incrementAndGet()}")
            send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
            return withTimeout(2000) {
                while (true) { observed.firstOrNull { it["id"] == id && it["method"] == null }?.let { return@withTimeout it }; delay(5) }
                error("unreachable")
            }
        }
        private fun capabilities() = Json.parseToJsonElement("""{"capabilities":{"positionEncoding":"utf-16","textDocumentSync":{"openClose":true,"change":1,"save":{"includeText":true},"willSave":false,"willSaveWaitUntil":true},"hoverProvider":true,"completionProvider":{"resolveProvider":false,"triggerCharacters":["."]},"signatureHelpProvider":{"triggerCharacters":["(",","]},"definitionProvider":true,"referencesProvider":true,"documentSymbolProvider":true,"workspaceSymbolProvider":true,"renameProvider":true,"documentFormattingProvider":true,"inlayHintProvider":true,"codeActionProvider":true}}""")
        private fun reply(request: JsonObject, result: JsonElement) = send(buildJsonObject { put("jsonrpc", "2.0"); put("id", request["id"]!!); put("result", result) })
        private fun send(message: JsonObject) {
            val bytes = ByteArrayOutputStream(); ContentLengthProtocol.writeMessage(bytes, message.toString()); out.offer(bytes.toByteArray())
        }
    }

    private class QueuedInput : InputStream() {
        private val bytes = LinkedBlockingQueue<Int>()
        override fun read() = bytes.take()
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = read(); if (first < 0) return -1
            buffer[off] = first.toByte(); var size = 1
            while (size < len) { val next = bytes.poll() ?: break; if (next < 0) { bytes.offer(-1); break }; buffer[off + size++] = next.toByte() }
            return size
        }
        fun offer(value: ByteArray) { value.forEach { bytes.add(it.toInt() and 255) } }
        override fun close() { bytes.offer(-1) }
    }

    private companion object {
        suspend fun awaitUntil(check: () -> Boolean) = withTimeout(3000) { while (!check()) delay(5) }
        fun range(start: Int, end: Int) = buildJsonObject {
            put("start", buildJsonObject { put("line", 0); put("character", start) })
            put("end", buildJsonObject { put("line", 0); put("character", end) })
        }
        fun location(uri: String, start: Int, end: Int) = buildJsonObject { put("uri", uri); put("range", range(start, end)) }
        fun edit(start: Int, end: Int) = buildJsonObject { put("range", range(start, end)); put("newText", "next") }
        fun edits(vararg entries: Pair<String, JsonObject>) = buildJsonObject {
            put("changes", buildJsonObject { entries.forEach { (uri, edit) -> put(uri, JsonArray(listOf(edit))) } })
        }
    }
}
