package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit Linux host control. This does not certify Android, ARM64 or PRoot. */
class DebugJavascriptActualHostProofTest {
    @Test(timeout = 90_000) fun officialAdapterThroughProductionClientBreaksInspectsStepsAndCleansOwnedSessions() = runBlocking {
        val entry = System.getenv("DROIDE_JS_DEBUG_PROOF_ENTRY")?.let(::File)
        val node = System.getenv("DROIDE_JS_DEBUG_PROOF_NODE")?.let(::File)
        assumeTrue("External official js-debug and host Node fixtures are required; test never downloads or installs", entry?.isFile == true && node?.isFile == true)
        val root = Files.createTempDirectory("droide-js-debug-actual-").toFile()
        val program = root.resolve("main.js")
        program.writeText("""
            const value = 40;
            function add(v) { const next = v + 2; return next; }
            const result = add(value);
            console.log(result);
            setTimeout(() => {}, 10000);
        """.trimIndent() + "\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val starts = AtomicInteger()
        var serverProcess: Process? = null
        val explicitHostControl = object : StdioProcessHost {
            // Replace only the tested spawn boundary. Production remains Ubuntu-only.
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = node!!.absolutePath
            override suspend fun pathMapper() = WorkspacePathMapper(root, root.absolutePath)
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?): HostedStdioProcess {
                starts.incrementAndGet()
                require(argv.first() == node!!.absolutePath && argv[1] == entry!!.absolutePath)
                val process = ProcessBuilder(argv).directory(root).start()
                serverProcess = process
                return object : HostedStdioProcess {
                    override val stdin get() = process.outputStream
                    override val stdout get() = process.inputStream
                    override val stderr get() = process.errorStream
                    override val isAlive get() = process.isAlive
                    override val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()
                    override suspend fun awaitExit() = withContext(Dispatchers.IO) { process.waitFor() }
                    override fun close() {
                        runCatching { process.outputStream.close() }; runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }

                        runCatching {
                            val handle = Process::class.java.getMethod("toHandle").invoke(process)
                            val type = Class.forName("java.lang.ProcessHandle")
                            (type.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>).use { children ->
                                children.forEach { type.getMethod("destroyForcibly").invoke(it) }
                            }
                        }
                        process.destroyForcibly()
                    }
                }
            }
        }
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = explicitHostControl)
        try {
            debugger.addBreakpoint("main.js", 3)
            debugger.start(DebugConfiguration("Node host proof", "launch",
                listOf(node!!.absolutePath, entry!!.absolutePath, "0", "127.0.0.1"),
                buildJsonObject {
                    put("type", "pwa-node"); put("program", program.absolutePath); put("cwd", root.absolutePath)
                    put("runtimeExecutable", node.absolutePath); put("console", "internalConsole")
                    put("autoAttachChildProcesses", true)
                    putJsonObject("trace") { put("logFile", root.resolve("host-dap-trace.json").absolutePath); put("stdio", false) }
                }, "explicit Linux host proof", adapterTransport = DebugAdapterTransport.JAVASCRIPT_DEBUG_TCP_SERVER))
            withTimeout(30_000) {
                while (debugger.state.value != DebugState.STOPPED || debugger.frames.value.isEmpty() ||
                    debugger.variables.value.isEmpty() || debugger.breakpointForRequestedLine("main.js", 3)?.verified != true) delay(20)
            }
            assertEquals(1, starts.get()) // Child connects to existing server; it never spawns another server.
            assertTrue(debugger.targetSessionCount >= 1)
            assertTrue(debugger.breakpointForRequestedLine("main.js", 3)!!.verified)
            assertEquals("main.js", debugger.frames.value.first().path)
            assertEquals(3, debugger.frames.value.first().line)
            assertTrue(debugger.variables.value.any { it.name == "value" && it.value == "40" })
            val generation = debugger.lastStop.value!!.generation
            debugger.next()
            withTimeout(15_000) { while ((debugger.lastStop.value?.generation ?: 0) <= generation) delay(20) }
            assertTrue(DebugCertificationContract.provesStep(debugger.lastStop.value!!))
            debugger.continueExecution()
            withTimeout(5000) { while (!debugger.output.value.contains("42")) delay(20) }
            debugger.stop()
            withTimeout(5000) { while (serverProcess?.isAlive == true) delay(20) }
            assertEquals(0, debugger.targetSessionCount)
            assertEquals(DebugState.IDLE, debugger.state.value)
            assertTrue(scope.isActive)
        } catch (failure: Throwable) {
            val trace = root.resolve("host-dap-trace.json").takeIf { it.isFile }?.readText()?.takeLast(8000)
            throw AssertionError("Host debug control failed: state=${debugger.state.value}; status=${debugger.status.value}; targets=${debugger.targetSessionCount}; frames=${debugger.frames.value}; variables=${debugger.variables.value}; output=${debugger.output.value.takeLast(6000)}; trace=$trace", failure)
        } finally { debugger.close(); serverProcess?.destroyForcibly(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun stoppedVariablesCanPrecedeBreakpointVerificationEvent() = runBlocking {
        val root = Files.createTempDirectory("droide-debug-verification-order-").toFile()
        root.resolve("main.js").writeText("const value = 40;\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val adapter = scope.launch(Dispatchers.IO) {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "setBreakpoints" -> host.reply(request, buildJsonObject {
                        putJsonArray("breakpoints") { add(buildJsonObject { put("id", 7); put("line", 1); put("verified", false) }) }
                    })
                    "threads" -> host.reply(request, buildJsonObject {
                        putJsonArray("threads") { add(buildJsonObject { put("id", 1); put("name", "main") }) }
                    })
                    "stackTrace" -> host.reply(request, buildJsonObject {
                        putJsonArray("stackFrames") { add(buildJsonObject { put("id", 1); put("name", "main"); put("line", 1) }) }
                    })
                    "scopes" -> host.reply(request, buildJsonObject {
                        putJsonArray("scopes") { add(buildJsonObject { put("name", "Local"); put("variablesReference", 1) }) }
                    })
                    "variables" -> host.reply(request, buildJsonObject {
                        putJsonArray("variables") { add(buildJsonObject { put("name", "value"); put("value", "40"); put("variablesReference", 0) }) }
                    })
                    else -> host.reply(request)
                }
            }
        }
        try {
            debugger.addBreakpoint("main.js", 1)
            debugger.start(DebugConfiguration("event order", "launch", listOf("/usr/bin/adapter"), buildJsonObject {}, "host protocol fixture"))
            host.send(buildJsonObject {
                put("seq", 101); put("type", "event"); put("event", "stopped")
                putJsonObject("body") { put("reason", "breakpoint"); put("threadId", 1) }
            })
            withTimeout(3_000) {
                while (debugger.state.value != DebugState.STOPPED || debugger.frames.value.isEmpty() || debugger.variables.value.isEmpty()) delay(10)
            }
            assertTrue(debugger.variables.value.any { it.name == "value" && it.value == "40" })
            assertFalse("Stopped data does not imply a later breakpoint event has arrived", debugger.breakpointForRequestedLine("main.js", 1)!!.verified)
            host.send(buildJsonObject {
                put("seq", 102); put("type", "event"); put("event", "breakpoint")
                putJsonObject("body") {
                    put("reason", "changed")
                    putJsonObject("breakpoint") { put("id", 7); put("line", 1); put("verified", true) }
                }
            })
            withTimeout(3_000) { while (debugger.breakpointForRequestedLine("main.js", 1)?.verified != true) delay(10) }
            assertEquals(DebugState.STOPPED, debugger.state.value)
            debugger.stop()
            assertTrue(scope.isActive)
        } finally {
            debugger.close(); host.close(); adapter.cancel(); scope.cancel()
            withTimeout(3_000) { scope.coroutineContext[Job]?.join() }
            PathSecurity.deleteTreeNoFollow(root)
        }
    }
}
