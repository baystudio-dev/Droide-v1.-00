package com.baystudio.droide.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real auto-created service owner, paused at existing approval before backup preparation starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTextEditPreBackupRootTest {
    @get:org.junit.Rule
    val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    @Test(timeout = 30_000)
    fun autoCreatedSessionKeepsItsTextEditRootAcrossPreBackupApprovalSuspension() {
        val container = Files.createTempDirectory("text-edit-pre-backup-").toFile()
        try {
            val root = container.resolve("work").apply { check(mkdir()) }
            root.resolve("note.txt").writeText("a")
            withProviderStreamFixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS, authRequired = false) { provider ->
                provider.secret = ""
                val config = provider.config.copy(apiKey = "")
                assertEquals("127.0.0.1", URI(config.baseUrl).host)
                assertEquals(ProviderNetworkScope.LOOPBACK_ONLY, provider.provider.networkScope)
                val terminal = NoProcessTerminal()
                val mutationCalls = AtomicInteger()
                val files = FileRepository(root, beforeMutation = { mutationCalls.incrementAndGet() })
                val agent = AgentService(files, terminal, GitManager(root), perms = PermissionEngine(listOf(
                    PermRule("*", "*", PermEffect.ALLOW), PermRule("edit", "note.txt", PermEffect.ASK),
                )), maxSubagentDepth = 0)
                provider.response = { request, _, streaming ->
                    check(request in 1..2 && streaming) { "Only one loopback tool response and one final text response are permitted" }
                    if (request == 2) {
                        provider.event("""{"choices":[{"index":0,"delta":{"content":"Fixture complete"},"finish_reason":"stop"}]}""") + "data: [DONE]\n\n"
                    } else {
                    val arguments = buildJsonObject { put("path", "note.txt"); put("content", "b") }
                    val tool = buildJsonObject {
                        put("index", 0); put("id", "root-bound-write"); put("type", "function")
                        put("function", buildJsonObject { put("name", "write_file"); put("arguments", arguments.toString()) })
                    }
                    provider.event("""{"choices":[{"index":0,"delta":{"tool_calls":[$tool]},"finish_reason":"tool_calls"}]}""") + "data: [DONE]\n\n"
                    }
                }
                try { runBlocking { withTimeout(15_000) {
                    assertNull(agent.currentSessionId)
                    val work = async(Dispatchers.Default) { runCatching { agent.chat("Write b to note.txt", config, maxTurns = 2) } }
                    try {
                        val waitingApproval = async { requireNotNull(agent.approvals.pending.first { it?.kind == "write_file" }) }
                        val approval = try {
                            select<ApprovalRequest> {
                                waitingApproval.onAwait { it }
                                work.onAwait { result -> error("Agent returned before write approval: requests=${provider.requests.size}, pending_kind=${agent.approvals.pending.value?.kind}, failure=${result.exceptionOrNull()?.javaClass?.simpleName}, last_message=${agent.messages.value.lastOrNull()?.content?.take(200)}") }
                            }
                        } finally { waitingApproval.cancelAndJoin() }
                        val originalId = requireNotNull(agent.currentSessionId)
                        assertEquals(originalId, approval.provenance.sessionId)
                        assertEquals("note.txt", approval.resource)
                        assertFalse(root.resolve(".droide/backups").exists())
                        val originalSessionBytes = root.resolve(".droide/sessions/$originalId.json").readBytes()
                        val oldRoot = container.resolve("original-root")
                        Files.move(root.toPath(), oldRoot.toPath())
                        assertTrue(root.mkdir())
                        root.resolve("note.txt").writeText("replacement target")
                        val replacement = SessionManager(root).create("Replacement namespace sentinel")
                        val replacementFile = root.resolve(".droide/sessions/${replacement.id}.json")
                        val replacementBytes = replacementFile.readBytes()
                        val beforeMetadata = metadata(root)
                        assertTrue(agent.approvals.resolve(approval.id, ApprovalResolution.ALLOW_ONCE))
                        work.await()
                        assertEquals(originalId, agent.currentSessionId)
                        assertNotEquals(replacement.id, agent.currentSessionId)
                        assertEquals("a", oldRoot.resolve("note.txt").readText())
                        assertArrayEquals(originalSessionBytes, oldRoot.resolve(".droide/sessions/$originalId.json").readBytes())
                        assertEquals("replacement target", root.resolve("note.txt").readText())
                        assertArrayEquals(replacementBytes, replacementFile.readBytes())
                        assertFalse(root.resolve(".droide/sessions/.text-edits").exists())
                        assertFalse(oldRoot.resolve(".droide/sessions/.text-edits").exists())
                        assertEquals(0, mutationCalls.get())
                        assertEquals(2, provider.requests.size)
                        assertEquals(0, terminal.executionCalls.get())
                        val afterMetadata = metadata(root)
                        val added = afterMetadata.keys - beforeMetadata.keys
                        val changed = beforeMetadata.count { (name, bytes) -> afterMetadata[name]?.contentEquals(bytes) != true }
                        val backups = root.resolve(".droide/backups").listFiles().orEmpty().filter { it.isFile }
                        // Report preparatory/ordinary-session effects separately; do not claim this sidecar guard
                        // already fences every existing .droide writer.
                        println("PRE_BACKUP_ROOT metadata_added=${added.size} metadata_changed=$changed preparatory_backup_files=${backups.size}")
                        println("PRE_BACKUP_ROOT added_relative_paths=${added.sorted().joinToString(",")}")
                        assertEquals("Root replacement during approval must not create a preparatory backup", 0, backups.size)
                        assertEquals("Original ledger must not publish a history page into the replacement workspace", 0,
                            added.count { it.startsWith(".droide/sessions/.history-pages/") })
                    } finally {
                        agent.approvals.resolve(ApprovalResolution.DENY)
                        withContext(NonCancellable) { withTimeout(5_000) { work.cancelAndJoin() } }
                    }
                } } } finally { agent.shutdown(); assertEquals(0, terminal.executionCalls.get()) }
            }
        } finally { PathSecurity.deleteTreeNoFollow(container) }
    }

    private fun metadata(root: File): Map<String, ByteArray> = root.resolve(".droide").walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes() }

    private class NoProcessTerminal : ITerminalSession {
        override val output = MutableStateFlow("")
        val executionCalls = AtomicInteger()
        private fun forbidden(): Nothing { executionCalls.incrementAndGet(); throw AssertionError("Process execution is forbidden") }
        override fun start() = forbidden()
        override fun send(cmd: String) = forbidden()
        override suspend fun execOnce(cmd: String, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execArgv(argv: List<String>, timeoutMs: Long): ExecResult = forbidden()
        override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit): ExecResult = forbidden()
        override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer,
            onOutput: (String) -> Unit): ExecResult = forbidden()
        override fun interrupt() = Unit
        override fun clear() = Unit
        override fun destroy() = Unit
    }
}
