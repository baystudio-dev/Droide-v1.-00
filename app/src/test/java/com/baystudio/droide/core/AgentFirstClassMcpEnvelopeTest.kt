package com.baystudio.droide.core
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.util.concurrent.atomic.*
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentFirstClassMcpEnvelopeTest {
    private fun result(text:String,error:Boolean)=buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type","text");put("text",text) }) }
        put("isError",error)
    }
    private fun execute(payload:JsonObject, check:(String,String)->Unit)=runBlocking {
        val root=Files.createTempDirectory("firstclass-mcp-envelope").toFile()
        val host=FakeMcpHost(payload)
        try {
            root.resolve(".droide").mkdirs()
            root.resolve(".droide/mcp.json").writeText("""{"servers":{"local":{"command":"fake-mcp","protocol":"legacy-2025"}}}""")
            val c=mcpEnvelopeContext(root)
            val ctx=AgentTools.Ctx(c.files,c.terminal,c.git,c.approvals,c.questions,c.history,c.perms,c.mode,
                c.llm,c.llmConfig,root,c.todos,c.sessionId,c.lsp,mcpProcessHost=host)
            val descriptor=McpToolRegistry(root,processHost=host).discoverAgentTools(ctx.perms,ctx.permissionScope).single()
            val output=withTimeout(10000) { AgentTools.execute(descriptor.functionName,buildJsonObject {},ctx) }
            check(descriptor.functionName,output)
        } finally { McpManager.shutdownSessions(root,host);McpToolRegistry.invalidate(root);PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun failureEvidenceSurvivesEightToTenThousandCharResult() = execute(result("x".repeat(8500),true)) { name,output ->
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify(name,output).state)
        assertTrue(output.length in 8001..10000)
    }
    @Test fun successfulEnvelopeRemainsCompleteAndParseable() = execute(result("x".repeat(8500),false)) { name,output ->
        val parsed=Json.parseToJsonElement(output).jsonObject
        assertEquals("x".repeat(8500),parsed["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(AgentToolEvidenceState.SUCCEEDED,AgentToolResultSemantics.classify(name,output).state)
        assertTrue(output.length<=10000)
    }
    @Test fun shortResultMeaningUnchanged() = execute(result("short",true)) { name,output ->
        assertEquals(JsonPrimitive(true),Json.parseToJsonElement(output).jsonObject["isError"])
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify(name,output).state)
    }
    @Test fun oversizedResultStillUsesExistingBoundedProjection() = execute(result("x".repeat(30000),true)) { name,output ->
        val parsed=Json.parseToJsonElement(output).jsonObject
        assertEquals(JsonPrimitive(true),parsed["truncated"]);assertTrue(output.length<=10000)
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify(name,output).state)
    }
    @Test fun escapedOversizedResultRemainsBoundedValidJson() = execute(result("\"\n".repeat(10000),false)) { _,output ->
        assertEquals(JsonPrimitive(true),Json.parseToJsonElement(output).jsonObject["truncated"]);assertTrue(output.length<=10000)
    }
    private class FakeMcpHost(private val toolResult: JsonObject) : StdioProcessHost {
        override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
        val starts = mutableListOf<HostedStdioProcess>()
        val toolEntered = CountDownLatch(1)
        override suspend fun resolveExecutable(command: String): String = command
        override suspend fun start(
            argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?,
        ): HostedStdioProcess {
            val toServer = PipedOutputStream()
            val serverInput = PipedInputStream(toServer, 64 * 1024)
            val toClient = PipedOutputStream()
            val clientInput = PipedInputStream(toClient, 64 * 1024)
            val alive = AtomicBoolean(true)
            val worker = AtomicReference<Thread?>()
            val process = object : HostedStdioProcess {
                override val stdin = toServer
                override val stdout = clientInput
                override val stderr = ByteArrayInputStream(byteArrayOf())
                override val isAlive: Boolean get() = alive.get()
                override val exitCode: Int? get() = if (alive.get()) null else 0
                override suspend fun awaitExit(): Int = 0
                override fun close() {
                    if (alive.compareAndSet(true, false)) {
                        worker.get()?.interrupt()
                        runCatching { toServer.close() }
                        runCatching { serverInput.close() }
                        runCatching { toClient.close() }
                        runCatching { clientInput.close() }
                    }
                }
            }
            starts += process
            Thread({
                try {
                    serverInput.bufferedReader().use { input ->
                        toClient.bufferedWriter().use { output ->
                            while (true) {
                                val line = input.readLine() ?: break
                                val request = Json.parseToJsonElement(line).jsonObject
                                val method = request["method"]?.jsonPrimitive?.content.orEmpty()
                                val id = request["id"] ?: continue
                                if (method == "tools/call") {
                                    toolEntered.countDown()
                                    // deterministic in-memory reply
                                }
                                val result = when (method) {
                                    "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
                                    "tools/list" -> """{"tools":[{"name":"counter","inputSchema":{"type":"object"}}]}"""
                                    "tools/call" -> toolResult.toString()
                                    "resources/list" -> when (request["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content) {
                                        "second-page" -> """{"resources":[{"uri":"fixture://two","name":"two"}]}"""
                                        "repeat" -> """{"resources":[],"nextCursor":"repeat"}"""
                                        else -> """{"resources":[{"uri":"fixture://one","name":"one"}],"nextCursor":"second-page"}"""
                                    }
                                    "resources/templates/list" -> """{"resourceTemplates":[{"uriTemplate":"fixture://{name}","name":"fixture"}]}"""
                                    "resources/read" -> """{"contents":[{"uri":"fixture://one","mimeType":"text/plain","text":"resource body"}]}"""
                                    "prompts/list" -> """{"prompts":[{"name":"review"}]}"""
                                    "prompts/get" -> """{"messages":[{"role":"user","content":{"type":"text","text":"Review this code"}}]}"""
                                    else -> "{}"
                                }
                                output.write("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
                                output.newLine()
                                output.flush()
                            }
                        }
                    }
                } catch (_: Exception) {   }
            }, "fake-mcp-test").apply { isDaemon = true; worker.set(this); start() }
            return process
        }
    }
}

internal class McpEnvelopeTerminal : ITerminalSession {
    override val output = MutableStateFlow("")
    override val supportsCommandInput = true
    override fun start() = Unit
    override fun send(cmd: String) = Unit
    override suspend fun execOnce(cmd: String, timeoutMs: Long) = execStreaming(cmd, timeoutMs) {}
    override suspend fun execArgv(argv: List<String>, timeoutMs: Long) = LocalProcessSupervisor.capture(argv, File("/tmp"), timeoutMs = timeoutMs)
    override suspend fun execStreaming(cmd: String, timeoutMs: Long, onOutput: (String) -> Unit) =
        LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), File("/tmp"), timeoutMs = timeoutMs, onOutput = onOutput, keepTail = true)
    override suspend fun execStreamingWithInput(cmd: String, timeoutMs: Long, input: ProcessInputBuffer, onOutput: (String) -> Unit) =
        LocalProcessSupervisor.capture(listOf("/bin/sh", "-c", cmd), File("/tmp"), timeoutMs = timeoutMs,
            onOutput = onOutput, keepTail = true, streamInput = input)
    override fun interrupt() = Unit
    override fun clear() = Unit
    override fun destroy() = Unit
}

internal fun mcpEnvelopeContext(
    root: File, manager: BackgroundCommandManager? = null, documents: WorkspaceDocumentAuthority? = null,
    permissions: PermissionEngine = PermissionEngine(listOf(PermRule("*", "*", PermEffect.ALLOW))),
    mode: AgentMode = AgentMode.BUILD, imageSink: ((List<AgentImageRef>) -> Unit)? = null,
    webResearch: AgentWebResearch = AgentWebResearch(root),
    terminal: ITerminalSession = McpEnvelopeTerminal(),
    onProgress: (AgentToolProgress) -> Unit = {},
    browserController: AgentBrowserController? = null,
    provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
) : AgentTools.Ctx {
    val files = FileRepository(root)
    return AgentTools.Ctx(files, terminal, GitManager(root), ApprovalManager(), QuestionManager(), EditHistory(files, documents),
        permissions, mode, LlmClient(), AgentConfig(providerId = "openai", model = "gpt-4o"), root,
        TodoManager(), "test-session", object : LspManager {
            override val diagnostics = MutableStateFlow<Map<String, List<LspDiagnostic>>>(emptyMap())
            override suspend fun diagnose(relPath: String) = ""
            override suspend fun lsp(op: String, path: String, line: Int, col: Int) = ""
        }, backgroundCommands = manager, documentAuthority = documents, onImageReady = imageSink, webResearch = webResearch, onProgress = onProgress,
        browserController = browserController, provenance = provenance)
}
