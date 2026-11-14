package com.baystudio.droide.core

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugInspectionLifecycleTest {
    private class Session {
        val root = Files.createTempDirectory("debug-inspection-").toFile()
        private val unhandledFailure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
            unhandledFailure.compareAndSet(null, failure)
        })
        val host = DebugProtocolFixture(root)
        val manager = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val pendingScopes = Channel<JsonObject>(Channel.UNLIMITED)
        private val frame = AtomicInteger()
        private val adapter = scope.launch(Dispatchers.IO) {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        event("initialized")
                    }
                    "threads" -> host.reply(request, buildJsonObject {
                        putJsonArray("threads") { add(buildJsonObject { put("id", 7); put("name", "main") }) }
                    })
                    "stackTrace" -> host.reply(request, buildJsonObject {
                        putJsonArray("stackFrames") {
                            add(buildJsonObject { put("id", frame.get()); put("name", "frame"); put("line", 1) })
                        }
                    })
                    "evaluate" -> {
                        val id = request["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int
                        host.reply(request, buildJsonObject { put("result", "watch-$id"); put("variablesReference", 0) })
                    }
                    "scopes" -> pendingScopes.send(request)
                    "variables" -> {
                        val reference = request["arguments"]!!.jsonObject["variablesReference"]!!.jsonPrimitive.int
                        host.reply(request, buildJsonObject {
                            putJsonArray("variables") {
                                add(buildJsonObject { put("name", "value"); put("value", reference.toString()); put("variablesReference", 0) })
                            }
                        })
                    }
                    "continue", "next", "stepIn", "stepOut" -> {
                        host.reply(request)
                        event("continued", buildJsonObject { put("threadId", 7); put("allThreadsContinued", true) })
                    }
                    else -> host.reply(request)
                }
            }
        }

        fun nextFrame(id: Int) { frame.set(id) }

        suspend fun start() = manager.start(DebugConfiguration(
            "inspection fixture", "launch", listOf("fixture-dap"), buildJsonObject {}, "controlled DAP transport",
        ))

        suspend fun event(name: String, body: JsonObject = buildJsonObject {}) = host.send(buildJsonObject {
            put("seq", 100); put("type", "event"); put("event", name); put("body", body)
        })

        suspend fun stopAt(id: Int): JsonObject {
            frame.set(id)
            event("stopped", buildJsonObject { put("reason", "breakpoint"); put("threadId", 7) })
            return withTimeout(5_000) { pendingScopes.receive() }.also {
                assertEquals(id, it["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int)
            }
        }

        suspend fun completeScopes(request: JsonObject) {
            val id = request["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int
            host.reply(request, buildJsonObject {
                putJsonArray("scopes") { add(buildJsonObject { put("name", "Local"); put("variablesReference", id) }) }
            })
        }

        suspend fun rejectScopes(request: JsonObject) = host.send(buildJsonObject {
            put("seq", 101); put("type", "response"); put("success", false)
            put("request_seq", request["seq"]!!); put("command", "scopes"); put("message", "Stack frame not found")
        })

        suspend fun output(text: String) = event("output", buildJsonObject { put("category", "stdout"); put("output", text) })

        suspend fun until(condition: () -> Boolean) = withTimeout(5_000) {
            while (!condition()) delay(1)
        }

        suspend fun close() {
            try { adapter.cancelAndJoin() } finally {
                manager.close()
                host.close()
                scope.cancel()
                pendingScopes.close()
                PathSecurity.deleteTreeNoFollow(root)
            }
            assertNull("Unexpected background failure: ${unhandledFailure.get()}", unhandledFailure.get())
        }
    }

    private fun scenario(block: suspend Session.() -> Unit) = runBlocking {
        val session = Session()
        try { session.start(); session.block() } finally { session.close() }
    }

    @Test fun delayedScopeFailureAfterContinueDoesNotTerminateTheTarget() = scenario {
        val request = stopAt(11)
        manager.continueExecution()
        rejectScopes(request)
        output("continued-output")
        until { manager.state.value == DebugState.ERROR || manager.output.value.contains("continued-output") }
        assertFalse(host.closed.get())
        assertEquals(DebugState.RUNNING, manager.state.value)
        assertTrue(manager.output.value.contains("continued-output"))
        assertTrue(scope.isActive)
    }

    @Test fun delayedSuccessfulScopeReplyCannotPopulateVariablesAfterContinue() = scenario {
        val request = stopAt(12)
        assertTrue(manager.variables.value.isEmpty())
        manager.continueExecution()
        completeScopes(request)
        output("resumed-output")
        until { manager.output.value.contains("resumed-output") }
        assertEquals(DebugState.RUNNING, manager.state.value)
        assertTrue("Resumed target received stale variables", manager.variables.value.isEmpty())
        assertFalse(host.closed.get())
    }

    @Test fun laterStopCanBeInspectedWithoutWaitingForAnOlderScopeReply() = scenario {
        val older = stopAt(21)
        val newer = stopAt(22)
        completeScopes(newer)
        until { manager.variables.value.any { it.name == "value" && it.value == "22" } }
        completeScopes(older)
        output("newer-stop-output")
        until { manager.output.value.contains("newer-stop-output") }
        assertEquals(DebugState.STOPPED, manager.state.value)
        assertEquals(22, manager.selectedFrame.value)
        assertEquals(listOf("22"), manager.variables.value.filter { it.name == "value" }.map { it.value })
        assertFalse(host.closed.get())
    }

    @Test fun currentScopeFailureIsReportedWithoutDestroyingTheDebugSession() = scenario {
        rejectScopes(stopAt(31))
        until { manager.state.value == DebugState.ERROR || manager.output.value.contains("Stack frame not found") }
        assertEquals(DebugState.STOPPED, manager.state.value)
        assertFalse(host.closed.get())
        assertTrue(manager.output.value.contains("Stack frame not found"))
        output("still-connected")
        until { manager.output.value.contains("still-connected") }
    }

    @Test fun pendingInspectionDoesNotBlockOutputEvents() = scenario {
        val request = stopAt(41)
        output("progress-while-paused")
        until { manager.output.value.contains("progress-while-paused") }
        assertTrue(manager.variables.value.isEmpty())
        completeScopes(request)
        until { manager.variables.value.any { it.name == "value" && it.value == "41" } }
        assertEquals(DebugState.STOPPED, manager.state.value)
    }

    @Test fun explicitStopStillClosesTheOwnedTransportDuringInspection() = scenario {
        stopAt(51)
        manager.stop()
        assertTrue(host.closed.get())
        assertEquals(DebugState.IDLE, manager.state.value)
        assertTrue(scope.isActive)
    }
    private fun staleFailureAfterStep(command: String) = scenario {
        val request = stopAt(61)
        when (command) {
            "next" -> manager.next()
            "stepIn" -> manager.stepIn()
            "stepOut" -> manager.stepOut()
            else -> error("Unexpected fixture command")
        }
        rejectScopes(request)
        output("step-output")
        until { manager.state.value == DebugState.ERROR || manager.output.value.contains("step-output") }
        assertEquals(DebugState.RUNNING, manager.state.value)
        assertFalse(host.closed.get())
        assertTrue(manager.output.value.contains("step-output"))
    }

    @Test fun nextCancelsInspectionWithoutTerminatingTheTarget() = staleFailureAfterStep("next")
    @Test fun stepInCancelsInspectionWithoutTerminatingTheTarget() = staleFailureAfterStep("stepIn")
    @Test fun stepOutCancelsInspectionWithoutTerminatingTheTarget() = staleFailureAfterStep("stepOut")

    @Test fun changingFrameKeepsNewVariablesWhenThePreviousScopeReplyArrivesLate() = scenario {
        val previous = stopAt(71)
        val selected = scope.async { manager.selectFrame(72) }
        val current = withTimeout(5_000) { pendingScopes.receive() }
        assertEquals(72, current["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int)
        completeScopes(current)
        withTimeout(5_000) { selected.await() }
        completeScopes(previous)
        output("selected-frame-output")
        until { manager.output.value.contains("selected-frame-output") }
        assertEquals(72, manager.selectedFrame.value)
        assertEquals(listOf("72"), manager.variables.value.filter { it.name == "value" }.map { it.value })
        assertFalse(host.closed.get())
    }


    @Test fun changingThreadRefreshesWatchForItsSelectedFrame() = scenario {
        manager.addWatch("value")
        completeScopes(stopAt(81))
        until { manager.watches.value.single().value == "watch-81" }
        nextFrame(82)
        val selected = scope.async { manager.selectThread(8) }
        val request = withTimeout(5_000) { pendingScopes.receive() }
        assertEquals(82, request["arguments"]!!.jsonObject["frameId"]!!.jsonPrimitive.int)
        completeScopes(request)
        withTimeout(5_000) { selected.await() }
        assertEquals(8, manager.selectedThread.value)
        assertEquals(82, manager.selectedFrame.value)
        assertEquals("watch-82", manager.watches.value.single().value)
        assertFalse(host.closed.get())
    }

    @Test fun changingFrameStillRefreshesWatchForItsSelectedFrame() = scenario {
        manager.addWatch("value")
        completeScopes(stopAt(91))
        until { manager.watches.value.single().value == "watch-91" }
        val selected = scope.async { manager.selectFrame(92) }
        completeScopes(withTimeout(5_000) { pendingScopes.receive() })
        withTimeout(5_000) { selected.await() }
        assertEquals("watch-92", manager.watches.value.single().value)
    }

    @Test fun continuingDuringThreadInspectionDoesNotRestoreStaleWatchValues() = scenario {
        manager.addWatch("value")
        completeScopes(stopAt(101))
        until { manager.watches.value.single().value == "watch-101" }
        nextFrame(102)
        val selected = scope.async { manager.selectThread(8) }
        val request = withTimeout(5_000) { pendingScopes.receive() }
        manager.continueExecution()
        completeScopes(request)
        withTimeout(5_000) { selected.await() }
        assertEquals(DebugState.RUNNING, manager.state.value)
        assertEquals("", manager.watches.value.single().value)
        assertFalse(host.closed.get())
    }
}
