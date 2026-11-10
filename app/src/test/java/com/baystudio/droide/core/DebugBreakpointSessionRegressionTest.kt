package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugBreakpointSessionRegressionTest {
    @Test fun stoppedEventBeforeStepResponseIsNotOverwrittenAsRunning() = runBlocking {
        val root = Files.createTempDirectory("debug-step-order-").toFile()
        root.resolve("main.py").writeText("value = 40\nvalue += 2\n")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        suspend fun stopped(reason: String) = host.send(buildJsonObject {
            put("seq", 101); put("type", "event"); put("event", "stopped")
            putJsonObject("body") { put("reason", reason); put("threadId", 1) }
        })
        val adapter = scope.launch(Dispatchers.IO) {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "threads" -> host.reply(request, buildJsonObject {
                        putJsonArray("threads") { add(buildJsonObject { put("id", 1); put("name", "main") }) }
                    })
                    "stackTrace" -> host.reply(request, buildJsonObject {
                        putJsonArray("stackFrames") { add(buildJsonObject { put("id", 1); put("name", "main"); put("line", 2) }) }
                    })
                    "next" -> {
                        val previous = debugger.lastStop.value!!.generation
                        stopped("step")
                        launch {
                            withTimeout(3000) { while ((debugger.lastStop.value?.generation ?: 0) <= previous) delay(10) }
                            host.reply(request)
                        }
                    }
                    "disconnect" -> { host.reply(request); break }
                    else -> host.reply(request)
                }
            }
        }
        try {
            debugger.start(DebugConfiguration("fixture", "launch", listOf("/usr/bin/adapter"), buildJsonObject {}, "test"))
            stopped("breakpoint")
            withTimeout(3000) { while (debugger.state.value != DebugState.STOPPED || debugger.frames.value.isEmpty()) delay(10) }
            debugger.next()
            assertEquals(DebugState.STOPPED, debugger.state.value)
            assertEquals("step", debugger.lastStop.value!!.reason)
            debugger.stop()
            assertTrue(scope.isActive)
        } finally { adapter.cancelAndJoin(); debugger.close(); host.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun relocatedBreakpointsAlwaysResendRequestedLineAndResetOnDisconnect() = runBlocking {
        val root = Files.createTempDirectory("debug-breakpoint-session-").toFile()
        root.resolve("main.py").writeText((1..20).joinToString("\n") { "value = $it" })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val breakpointPayloads = Channel<List<Int>>(4)
        val exceptionPayload = CompletableDeferred<List<String>>()
        val adapter = scope.launch(Dispatchers.IO) {
            for (request in host.requests) {
                if (request["type"]?.jsonPrimitive?.content != "request") continue
                val command = request["command"]!!.jsonPrimitive.content
                val arguments = request["arguments"] as? JsonObject
                when (command) {
                    "initialize" -> host.reply(request, buildJsonObject {
                        put("supportsConfigurationDoneRequest", true)
                        put("exceptionBreakpointFilters", buildJsonArray {
                            add(buildJsonObject { put("filter", "uncaught"); put("default", true) })
                            add(buildJsonObject { put("filter", "all"); put("default", false) })
                        })
                    })
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "setBreakpoints" -> {
                        val lines = arguments!!["breakpoints"]!!.jsonArray.map { it.jsonObject["line"]!!.jsonPrimitive.int }
                        host.reply(request, buildJsonObject {
                            put("breakpoints", buildJsonArray { lines.forEach { line -> add(buildJsonObject {
                                put("verified", true); put("id", line); put("line", line + 1)
                            }) } })
                        })
                        breakpointPayloads.send(lines)
                    }
                    "setExceptionBreakpoints" -> {
                        exceptionPayload.complete(arguments!!["filters"]!!.jsonArray.map { it.jsonPrimitive.content })
                        host.reply(request)
                    }
                    "disconnect" -> { host.reply(request); break }
                    else -> host.reply(request)
                }
            }
        }
        try {
            debugger.addBreakpoint("main.py", 11)
            debugger.start(DebugConfiguration("fixture", "launch", listOf("/usr/bin/adapter"), buildJsonObject {}, "test"))
            assertEquals(listOf(11), withTimeout(3000) { breakpointPayloads.receive() })
            assertEquals(listOf("uncaught"), exceptionPayload.await())
            val relocated = debugger.breakpointForRequestedLine("main.py", 11)!!
            assertEquals(12, relocated.line); assertEquals(11, relocated.requestedLine)
            debugger.addBreakpoint("main.py", 15)
            assertEquals(listOf(11, 15), withTimeout(3000) { breakpointPayloads.receive() })
            debugger.stop()
            val reset = debugger.breakpointForRequestedLine("main.py", 11)!!
            assertEquals(11, reset.line); assertNull(reset.id); assertFalse(reset.verified)
            assertTrue(scope.isActive)
        } finally { adapter.cancelAndJoin(); debugger.close(); host.close(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
}
