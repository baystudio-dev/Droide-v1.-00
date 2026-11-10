package com.baystudio.droide.core

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Current-API, report-only candidate. Only bounded temporary file IO; all external backends fail closed. */
class AgentPatchTargetAuthorityTest {
    @Test(timeout = 20_000) fun deniedSecondTargetCannotHideInsideAllowedAggregate() = fixture(allowWith("blocked.txt", PermEffect.DENY)) { f ->
        val result = f.execute(patch(add("allowed.txt"), add("blocked.txt")), prompt = false)
        f.unchanged(mapOf("allowed.txt" to null, "blocked.txt" to null))
        assertTrue(result, result.startsWith("DENIED"))
        val direct = AgentTools.execute("write_file", buildJsonObject { put("path", "blocked.txt"); put("content", "x") }, f.ctx)
        assertTrue(direct, direct.startsWith("DENIED"))
    }

    @Test(timeout = 20_000) fun canonicalAliasCannotBypassActualTargetRule() = fixture(allowWith("folder/blocked.txt", PermEffect.DENY)) { f ->
        val result = f.execute(patch(add("folder//blocked.txt")), prompt = false)
        f.unchanged(mapOf("folder/blocked.txt" to null))
        assertTrue(result, result.startsWith("DENIED"))
    }

    @Test(timeout = 20_000) fun dotSegmentInputsKeepExistingMutationRejection() {
        for (target in listOf("./created.txt", "folder/../created.txt", "folder\\..\\created.txt")) {
            fixture(PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW)))) { f ->
                val result = f.execute(patch(add(target)), prompt = false)
                assertTrue(result, result.startsWith("ERROR:") && result.contains("Dot path segments"))
                f.unchanged(mapOf("created.txt" to null))
            }
        }
        fixture(PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW)))) { f ->
            f.write("old.txt", "same\n")
            val result = f.execute(patch(move("old.txt", "./new.txt")), prompt = false)
            assertTrue(result, result.startsWith("ERROR:") && result.contains("Dot path segments"))
            f.unchanged(mapOf("old.txt" to "same\n", "new.txt" to null))
        }
    }

    @Test(timeout = 20_000) fun deniedMoveDestinationIsCheckedBeforeAnyPrompt() = fixture(allowWith("blocked.txt", PermEffect.DENY)) { f ->
        f.write("old.txt", "same\n")
        val result = f.execute(patch(move("old.txt", "blocked.txt")), prompt = false)
        f.unchanged(mapOf("old.txt" to "same\n", "blocked.txt" to null))
        assertTrue(result, result.startsWith("DENIED"))
    }

    @Test(timeout = 20_000) fun targetDenyWinsOverGenericAsk() = fixture(PermissionEngine(listOf(
        PermRule("edit", "*", PermEffect.ASK), PermRule("edit", "blocked.txt", PermEffect.DENY)))) { f ->
        val result = f.execute(patch(add("allowed.txt"), add("blocked.txt")), prompt = false)
        f.unchanged(mapOf("allowed.txt" to null, "blocked.txt" to null))
        assertTrue(result, result.startsWith("DENIED"))
    }

    @Test(timeout = 20_000) fun oneAskedTargetMakesOneContextualProposal() = fixture(allowWith("asked.txt", PermEffect.ASK)) { f ->
        val result = f.execute(patch(add("allowed.txt"), add("asked.txt")), prompt = true) { request ->
            assertEquals("edit", request.action)
            assertEquals(AgentChangeTotals(0, 0), request.proposedChangeTotals)
            f.unchanged(mapOf("allowed.txt" to null, "asked.txt" to null))
            ApprovalResolution.DENY
        }
        assertTrue(result, result.startsWith("DENIED"))
        f.unchanged(mapOf("allowed.txt" to null, "asked.txt" to null))
    }

    @Test(timeout = 20_000) fun changedSecondTargetPolicyRevokesApprovedProposal() = fixture(askAll()) { f ->
        val result = f.execute(patch(add("allowed.txt"), add("blocked.txt")), prompt = true) {
            f.ctx.perms.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("edit", "blocked.txt", PermEffect.DENY))))
            ApprovalResolution.ALLOW_ONCE
        }
        f.unchanged(mapOf("allowed.txt" to null, "blocked.txt" to null))
        assertTrue(result, result.startsWith("DENIED"))
    }

    @Test(timeout = 20_000) fun sourceOnlySessionGrantCannotAuthorizeNewMoveDestination() = fixture(askAll()) { f ->
        f.write("old.txt", "a\n")
        assertTrue(f.execute(patch(update("old.txt", "a", "b")), prompt = true) { ApprovalResolution.ALLOW_SESSION }.startsWith("OK:"))
        val depth = f.ctx.history.depth()
        val published = f.publishedCount()
        val result = f.execute(patch(move("old.txt", "new.txt")), prompt = true) { ApprovalResolution.DENY }
        assertTrue(result, result.startsWith("DENIED"))
        f.assertFiles(mapOf("old.txt" to "b\n", "new.txt" to null))
        assertEquals(depth, f.ctx.history.depth()); assertEquals(published, f.publishedCount())
    }

    @Test(timeout = 20_000) fun cachedSourceGrantStillCannotOverrideDeniedDestination() = fixture(askAll()) { f ->
        f.write("old.txt", "a\n")
        f.execute(patch(update("old.txt", "a", "b")), prompt = true) { ApprovalResolution.ALLOW_SESSION }
        f.ctx.perms.replacePolicy(PermissionPolicyDocument(rules = listOf(PermRule("edit", "blocked.txt", PermEffect.DENY))))
        val depth = f.ctx.history.depth()
        val published = f.publishedCount()
        val result = f.execute(patch(move("old.txt", "blocked.txt")), prompt = false)
        assertTrue(result, result.startsWith("DENIED"))
        f.assertFiles(mapOf("old.txt" to "b\n", "blocked.txt" to null))
        assertEquals(depth, f.ctx.history.depth()); assertEquals(published, f.publishedCount())
    }

    @Test(timeout = 20_000) fun truncatedVisibleLabelsCannotCollideAcrossDifferentTargetSets() = fixture(askAll()) { f ->
        val longPath = (1..6).joinToString("/") { "segment$it-" + "x".repeat(80) } + "/shared.txt"
        assertTrue(longPath.length > 500)
        f.write(longPath, "a\n"); f.write("first.txt", "a\n"); f.write("second.txt", "a\n")
        f.execute(patch(update(longPath, "a", "b"), update("first.txt", "a", "b")), prompt = true) { ApprovalResolution.ALLOW_SESSION }
        val depth = f.ctx.history.depth(); val published = f.publishedCount()
        val result = f.execute(patch(update(longPath, "b", "c"), update("second.txt", "a", "c")), prompt = true) { ApprovalResolution.DENY }
        assertTrue(result, result.startsWith("DENIED"))
        f.assertFiles(mapOf(longPath to "b\n", "first.txt" to "b\n", "second.txt" to "a\n"))
        assertEquals(depth, f.ctx.history.depth()); assertEquals(published, f.publishedCount())
    }

    @Test(timeout = 20_000) fun commaLabelsCannotCollideAcrossDifferentTargetSets() = fixture(askAll()) { f ->
        listOf("a,b", "c", "a", "b,c").forEach { f.write(it, "x\n") }
        f.execute(patch(update("a,b", "x", "y"), update("c", "x", "y")), prompt = true) { ApprovalResolution.ALLOW_SESSION }
        val result = f.execute(patch(update("a", "x", "z"), update("b,c", "x", "z")), prompt = true) { ApprovalResolution.DENY }
        assertTrue(result, result.startsWith("DENIED"))
        f.assertFiles(mapOf("a,b" to "y\n", "c" to "y\n", "a" to "x\n", "b,c" to "x\n"))
    }

    @Test(timeout = 20_000) fun legitimateSingleTargetSessionGrantRemainsReusableForNewContent() = fixture(askAll()) { f ->
        f.write("same.txt", "a\n")
        f.execute(patch(update("same.txt", "a", "b")), prompt = true) { ApprovalResolution.ALLOW_SESSION }
        assertTrue(f.ctx.approvals.hasSessionApproval("r38-patch", "build", "edit", "same.txt"))
        val result = f.execute(patch(update("same.txt", "b", "c")), prompt = false)
        assertTrue(result, result.startsWith("OK:")); f.assertFiles(mapOf("same.txt" to "c\n"))
    }

    @Test(timeout = 20_000) fun currentMultiTargetSessionGrantReusesOnlyItsCompleteSet() = fixture(askAll()) { f ->
        f.write("first.txt", "a\n"); f.write("second.txt", "a\n")
        f.execute(patch(update("first.txt", "a", "b"), update("second.txt", "a", "b")), prompt = true) { ApprovalResolution.ALLOW_SESSION }
        // Operation order and later content do not broaden this explicit same-target edit grant.
        val result = f.execute(patch(update("second.txt", "b", "c"), update("first.txt", "b", "c")), prompt = false)
        assertTrue(result, result.startsWith("OK:"))
        f.assertFiles(mapOf("first.txt" to "c\n", "second.txt" to "c\n"))
    }

    @Test(timeout = 20_000) fun revokedExactOwnerCannotResolveOrCacheThePatch() = fixture(askAll()) { f ->
        val monitor = Any()
        var current = true
        val owner = ApprovalOwnerAdmission(monitor) { current }
        var grant: ApprovalRequest? = null
        val result = f.execute(patch(add("first.txt"), add("second.txt")), prompt = true, owner = owner) { request ->
            grant = request
            synchronized(monitor) { current = false }
            ApprovalResolution.ALLOW_SESSION
        }
        assertTrue(result, result.startsWith("DENIED"))
        f.unchanged(mapOf("first.txt" to null, "second.txt" to null))
        val request = requireNotNull(grant)
        assertFalse(f.ctx.approvals.hasSessionApproval(request.approvalSessionId, request.permissionScope,
            request.sessionGrantAction, request.sessionGrantResource))
    }

    @Test(timeout = 20_000) fun currentAllowedTargetsStillApplyOnceWithCorrectMetadata() = fixture(PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW)))) { f ->
        f.write("old.txt", "same\n")
        val result = f.execute(patch(add("created.txt"), move("old.txt", "renamed.txt")), prompt = false)
        assertTrue(result, result.startsWith("OK:"))
        f.assertFiles(mapOf("created.txt" to "", "old.txt" to null, "renamed.txt" to "same\n"))
        assertEquals(1, f.publishedCount()); assertEquals(1, f.ctx.history.depth())
        assertEquals(AgentChangeTotals(0, 0), AgentFileChanges.totals(f.progress.mapNotNull { it.fileChanges }.single()))
    }

    @Test(timeout = 20_000) fun moveOnlyProposalDisclosesBothEndpointsBeforeDenyOrAllow() {
        for (resolution in listOf(ApprovalResolution.DENY, ApprovalResolution.ALLOW_ONCE)) fixture(askAll()) { f ->
            f.write("old.txt", "same\n")
            val result = f.execute(patch(move("old.txt", "renamed.txt")), prompt = true) { request ->
                val file = UnifiedDiffParser.parse(request.detail).single()
                assertEquals("old.txt", file.oldPath); assertEquals("renamed.txt", file.newPath)
                assertTrue(file.hunks.isEmpty())
                assertEquals(AgentChangeTotals(0, 0), request.proposedChangeTotals)
                f.unchanged(mapOf("old.txt" to "same\n", "renamed.txt" to null))
                resolution
            }
            if (resolution == ApprovalResolution.DENY) {
                assertTrue(result, result.startsWith("DENIED")); f.unchanged(mapOf("old.txt" to "same\n", "renamed.txt" to null))
            } else {
                assertTrue(result, result.startsWith("OK:")); f.assertFiles(mapOf("old.txt" to null, "renamed.txt" to "same\n"))
                val change = f.progress.mapNotNull { it.fileChanges }.single().single()
                assertEquals("old.txt", change.previousPath); assertEquals("renamed.txt", change.path)
                assertEquals(AgentChangeTotals(0, 0), AgentFileChanges.totals(listOf(change)))
            }
        }
    }

    private fun fixture(permissions: PermissionEngine, body: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture(permissions)
        try { withTimeout(12_000) { body(f) }; assertEquals(0, f.forbiddenCalls.get()) }
        finally { f.close() }
    }

    private class Fixture(permissions: PermissionEngine) {
        val root: File = Files.createTempDirectory("droide-r38-patch-").toFile()
        val forbiddenCalls = AtomicInteger()
        val progress = CopyOnWriteArrayList<AgentToolProgress>()
        private fun forbidden(): Nothing { forbiddenCalls.incrementAndGet(); throw AssertionError("R38 external backend is forbidden") }
        private val terminal = object : ITerminalSession {
            override val output = MutableStateFlow("")
            override val supportsCommandInput = false
            override suspend fun prepareCommandInput(): Boolean = forbidden()
            override fun start() = forbidden()
            override fun send(cmd: String) = forbidden()
            override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
            override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
            override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
            override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit): ExecResult = forbidden()
            override fun interrupt() = forbidden()
            override fun clear() = forbidden()
            override fun destroy() = forbidden()
        }
        private val web = AgentWebResearch(root, AgentWebTransport { _, _ -> forbidden() }, object : AgentWebPdfBackend {
            override fun pageCount(file: File): Int = forbidden()
            override fun text(file: File, page: Int): String? = forbidden()
            override fun screenshot(file: File, page: Int, output: OutputStream): AgentWebImageSize = forbidden()
        })
        private val blockedProvider = OkHttpClient.Builder().addInterceptor { forbidden() }.build()
        private val llm = LlmClient().also { client ->
            // Existing API has no injected provider. Replace its private transport only in this fixture; the
            // application interceptor throws before DNS/socket/request transmission.
            val field = LlmClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(client, blockedProvider)
            check(field.get(client) === blockedProvider) { "Provider blocker was not installed" }
        }
        private val files = FileRepository(root)
        val ctx = AgentTools.Ctx(files, terminal, GitManager(root), ApprovalManager(), QuestionManager(), EditHistory(files),
            permissions, AgentMode.BUILD, llm, AgentConfig(providerId = "openai", model = "fixture-never-requested"), root,
            TodoManager(), "r38-patch", object : LspManager {
                override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
                override suspend fun diagnose(relPath: String): String = forbidden()
                override suspend fun lsp(op: String, path: String, line: Int, col: Int): String = forbidden()
            }, provenance = AgentRequestProvenance.primary("r38-patch"), webResearch = web, onProgress = progress::add)
        fun write(path: String, content: String) { root.resolve(path).apply { parentFile.mkdirs(); writeText(content) } }
        fun publishedCount() = progress.count { it.fileChanges != null }
        fun assertFiles(expected: Map<String, String?>) = expected.forEach { (path, content) ->
            if (content == null) assertFalse("Unexpected file: $path", root.resolve(path).exists())
            else assertArrayEquals("Changed bytes: $path", content.toByteArray(), root.resolve(path).readBytes())
        }
        fun unchanged(expected: Map<String, String?>) {
            assertFiles(expected); assertEquals(0, ctx.history.depth()); assertEquals(0, publishedCount())
        }
        suspend fun execute(text: String, prompt: Boolean, owner: ApprovalOwnerAdmission? = null,
            resolve: (ApprovalRequest) -> ApprovalResolution = { ApprovalResolution.DENY }): String = coroutineScope {
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                val execute: suspend () -> String = { AgentTools.execute("apply_patch", buildJsonObject { put("patchText", text) }, ctx) }
                if (owner == null) execute() else ctx.approvals.withApprovalOwner(owner, execute)
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { ctx.approvals.pending.filterNotNull().first() }
            try {
                val event = withTimeout(5_000) { select<Pair<ApprovalRequest?, String?>> {
                    pending.onAwait { it to null }
                    work.onAwait { null to it }
                } }
                if (event.first == null) {
                    assertFalse("Expected one proposal, but execution completed: ${event.second}", prompt)
                    requireNotNull(event.second)
                } else {
                    val request = requireNotNull(event.first)
                    assertTrue("Unexpected approval for denied/allowed target set", prompt)
                    assertEquals("r38-patch", request.approvalSessionId)
                    assertEquals("build", request.permissionScope)
                    assertEquals("r38-patch", request.provenance.sessionId)
                    assertFalse(work.isCompleted)
                    assertTrue(ctx.approvals.resolve(request.id, resolve(request)))
                    withTimeout(5_000) { work.await() }.also { assertNull(ctx.approvals.pending.value) }
                }
            } finally {
                withContext(NonCancellable) { withTimeout(2_000) { pending.cancelAndJoin(); work.cancelAndJoin() } }
            }
        }
        fun close() {
            web.close(); blockedProvider.dispatcher.executorService.shutdownNow(); blockedProvider.connectionPool.evictAll()
            check(PathSecurity.deleteTreeNoFollow(root)) { "Fixture retained: $root" }
        }
    }

    private fun allowWith(path: String, effect: PermEffect) = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW), PermRule("edit", path, effect)))
    private fun askAll() = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ASK)))
    private fun patch(vararg operations: String) = "*** Begin Patch\n" + operations.joinToString("\n") + "\n*** End Patch"
    private fun add(path: String) = "*** Add File: $path"
    private fun move(from: String, to: String) = "*** Update File: $from\n*** Move to: $to"
    private fun update(path: String, old: String, new: String) = "*** Update File: $path\n@@\n-$old\n+$new"
}
