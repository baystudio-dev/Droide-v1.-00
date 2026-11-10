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

/** Six finite current-API alias controls; local temporary symlinks and fake exact-path editor authority only. */
class AgentPatchAliasIdentityTest {
    @Test(timeout = 20_000) fun liveAliasUpdateKeepsTheExactOpenBufferAndDiskUnchanged() = fixture("unsaved\n", dirty = true) { f ->
        val result = f.execute(update("unsaved", "edited"))
        assertTrue(result, result.startsWith("OK:"))
        f.assertDisk()
        assertEquals("edited\n", requireNotNull(f.documents).current.content)
        assertEquals(1, f.ctx.history.depth())
        val changes = f.progress.mapNotNull { it.fileChanges }.single()
        assertEquals("alias.txt", changes.single().path)
        assertTrue(changes.single().diff.contains("-unsaved"))
    }

    @Test(timeout = 20_000) fun dirtyAliasDeleteRetainsExistingUnsavedProtection() = fixture("unsaved\n", dirty = true) { f ->
        val before = requireNotNull(f.documents).current
        val result = f.execute("*** Delete File: alias.txt")
        assertTrue(result, result.startsWith("ERROR:") && result.contains("unsaved editor buffer"))
        f.unchanged(); assertEquals(before, requireNotNull(f.documents).current)
    }

    @Test(timeout = 20_000) fun openAliasMoveRetainsExistingIdentityProtection() = fixture("disk\n", dirty = false) { f ->
        val before = requireNotNull(f.documents).current
        val result = f.execute("*** Update File: alias.txt\n*** Move to: moved.txt")
        assertTrue(result, result.startsWith("ERROR:") && result.contains("source to be closed"))
        f.unchanged(); assertEquals(before, requireNotNull(f.documents).current)
        assertFalse(f.root.resolve("moved.txt").exists())
    }

    @Test(timeout = 20_000) fun retargetDuringApprovalCannotRedirectRawOperationWithMatchingBytes() = fixture(
        permissions = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ASK)))) { f ->
        val result = f.execute(update("disk", "edited"), prompt = true) {
            // Equal bytes make the existing content-only re-review pass after retargeting.
            Files.delete(f.root.resolve("alias.txt").toPath())
            Files.createSymbolicLink(f.root.resolve("alias.txt").toPath(), f.root.resolve("other.txt").toPath())
            ApprovalResolution.ALLOW_ONCE
        }
        assertTrue(result, result.startsWith("DENIED") && result.contains("targets changed"))
        f.unchanged(aliasTarget = "other.txt")
    }

    @Test(timeout = 20_000) fun rawAliasDenyStillWinsWhenCanonicalTargetIsAllowed() = fixture(
        permissions = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW),
            PermRule("edit", "alias.txt", PermEffect.DENY)))) { f ->
        val result = f.execute(update("disk", "edited"))
        assertTrue(result, result.startsWith("DENIED")); f.unchanged()
    }

    @Test(timeout = 20_000) fun rawAliasAskStillRequiresOneProposalWhenCanonicalTargetIsAllowed() = fixture(
        permissions = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW),
            PermRule("edit", "alias.txt", PermEffect.ASK)))) { f ->
        val result = f.execute(update("disk", "edited"), prompt = true) { request ->
            assertEquals("edit", request.action)
            f.unchanged()
            ApprovalResolution.DENY
        }
        assertTrue(result, result.startsWith("DENIED")); f.unchanged()
    }

    private fun fixture(content: String? = null, dirty: Boolean = false,
        permissions: PermissionEngine = PermissionEngine(listOf(PermRule("edit", "*", PermEffect.ALLOW))),
        block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture(content, dirty, permissions)
        try { withTimeout(12_000) { block(f) }; assertEquals(0, f.forbiddenCalls.get()) }
        finally { f.close() }
    }

    private class Fixture(content: String?, dirty: Boolean, permissions: PermissionEngine) {
        val root = Files.createTempDirectory("droide-r38-alias-").toFile()
        val forbiddenCalls = AtomicInteger()
        private fun forbidden(): Nothing { forbiddenCalls.incrementAndGet(); throw AssertionError("R38 alias fixture backend forbidden") }
        val documents = content?.let { AgentWorkflowDocuments(agentWorkflowSnapshot("alias.txt", it).copy(dirty = dirty)) }
        val progress = CopyOnWriteArrayList<AgentToolProgress>()
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
        val ctx = agentWorkflowContext(root, documents = documents, permissions = permissions, terminal = terminal,
            webResearch = web, provenance = AgentRequestProvenance.primary("r38-alias"), onProgress = progress::add).also { context ->
            val field = LlmClient::class.java.getDeclaredField("client").apply { isAccessible = true }
            field.set(context.llm, blockedProvider)
            check(field.get(context.llm) === blockedProvider) { "Provider blocker was not installed" }
        }
        init {
            root.resolve("actual.txt").writeText("disk\n"); root.resolve("other.txt").writeText("disk\n")
            Files.createSymbolicLink(root.resolve("alias.txt").toPath(), root.resolve("actual.txt").toPath())
        }
        fun assertDisk(aliasTarget: String = "actual.txt") {
            assertEquals("disk\n", root.resolve("actual.txt").readText())
            assertEquals("disk\n", root.resolve("other.txt").readText())
            assertEquals(root.resolve(aliasTarget).toPath(), Files.readSymbolicLink(root.resolve("alias.txt").toPath()))
        }
        fun unchanged(aliasTarget: String = "actual.txt") {
            assertDisk(aliasTarget); assertEquals(0, ctx.history.depth()); assertTrue(progress.none { it.fileChanges != null })
        }
        suspend fun execute(operation: String, prompt: Boolean = false,
            resolve: (ApprovalRequest) -> ApprovalResolution = { ApprovalResolution.DENY }): String = coroutineScope {
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                AgentTools.execute("apply_patch", buildJsonObject { put("patchText", "*** Begin Patch\n$operation\n*** End Patch") }, ctx)
            }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { ctx.approvals.pending.filterNotNull().first() }
            try {
                val event = withTimeout(5_000) { select<Pair<ApprovalRequest?, String?>> {
                    pending.onAwait { it to null }; work.onAwait { null to it }
                } }
                if (event.first == null) {
                    assertFalse("Expected proposal; completed: ${event.second}", prompt)
                    requireNotNull(event.second)
                } else {
                    assertTrue("Unexpected proposal", prompt)
                    val request = requireNotNull(event.first)
                    assertFalse(work.isCompleted)
                    assertTrue(ctx.approvals.resolve(request.id, resolve(request)))
                    withTimeout(5_000) { work.await() }.also { assertNull(ctx.approvals.pending.value) }
                }
            } finally { withContext(NonCancellable) { withTimeout(2_000) { pending.cancelAndJoin(); work.cancelAndJoin() } } }
        }
        fun close() {
            web.close(); blockedProvider.dispatcher.executorService.shutdownNow(); blockedProvider.connectionPool.evictAll()
            check(PathSecurity.deleteTreeNoFollow(root)) { "Fixture retained: $root" }
        }
    }
    private fun update(before: String, after: String) = "*** Update File: alias.txt\n@@\n-$before\n+$after"
}
