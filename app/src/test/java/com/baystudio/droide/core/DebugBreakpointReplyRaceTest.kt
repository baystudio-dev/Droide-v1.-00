package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test

class DebugBreakpointReplyRaceTest {
    private fun race(readd: Boolean, addNew: Boolean, outOfOrder: Boolean = false, immediate: Boolean = false, toggle: Boolean = false) = runBlocking {
        val root = Files.createTempDirectory("debug-breakpoint-reply-").toFile()
        root.resolve("main.py").writeText("pass\n")
        val scope = CoroutineScope(SupervisorJob() + if (immediate) Dispatchers.Unconfined else Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val held = Channel<JsonObject>(Channel.UNLIMITED)
        val adapter = scope.launch {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "setBreakpoints" -> held.send(request)
                    else -> host.reply(request)
                }
            }
        }
        try {
            debugger.start(DebugConfiguration("fixture", "launch", listOf("fixture-dap"), buildJsonObject {}, "test"))
            if (immediate) {
                if (toggle) debugger.toggleBreakpoint("main.py", 1) else debugger.addBreakpoint("main.py", 1)
                val request = withTimeout(5_000) { held.receive() }
                host.reply(request, buildJsonObject {
                    putJsonArray("breakpoints") { add(buildJsonObject { put("id", 333); put("line", 1); put("verified", true) }) }
                })
                withTimeout(5_000) {
                    while (debugger.breakpointForRequestedLine("main.py", 1)?.verified != true) delay(1)
                }
                assertEquals(333, debugger.breakpointForRequestedLine("main.py", 1)!!.id)
                return@runBlocking
            }
            // Seed the initial model without scheduling an additional request, so the exact production
            // suspend call under test can be awaited rather than relying on sleeps.
            @Suppress("UNCHECKED_CAST")
            val model = DebugManager::class.java.getDeclaredField("_breakpoints").apply { isAccessible = true }
                .get(debugger) as MutableStateFlow<Map<String, List<DebugBreakpoint>>>
            model.value = mapOf("main.py" to listOf(DebugBreakpoint("main.py", 1)))
            val process = DebugManager::class.java.getDeclaredField("dap").apply { isAccessible = true }.get(debugger)
            val method = DebugManager::class.java.declaredMethods.single { it.name == "syncBreakpoints" }.apply { isAccessible = true }
            val sync = async {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    method.invoke(debugger, "main.py", process, continuation)
                }
            }
            val old = withTimeout(5_000) { held.receive() }
            if (outOfOrder) {
                val newerSync = async {
                    suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                        method.invoke(debugger, "main.py", process, continuation)
                    }
                }
                val newer = withTimeout(5_000) { held.receive() }
                host.reply(newer, buildJsonObject {
                    putJsonArray("breakpoints") { add(buildJsonObject { put("id", 222); put("line", 2); put("verified", true) }) }
                })
                withTimeout(5_000) { newerSync.await() }
            } else {
                if (!addNew) debugger.clearBreakpoint("main.py", 1)
                if (readd) debugger.addBreakpoint("main.py", 1)
                if (addNew) debugger.addBreakpoint("main.py", 2)
            }
            host.reply(old, buildJsonObject {
                putJsonArray("breakpoints") { add(buildJsonObject { put("id", 999); put("line", 10); put("verified", true) }) }
            })
            withTimeout(5_000) { sync.await() }
            val current = debugger.breakpoints.value["main.py"].orEmpty()
            when {
                outOfOrder -> { assertEquals(222, current.single().id); assertEquals(2, current.single().line) }
                addNew -> assertEquals(listOf(1, 2), current.map { it.requestedLine }.sorted())
                readd -> { assertEquals(1, current.size); assertNull(current.single().id); assertEquals(1, current.single().line) }
                else -> assertTrue("Late reply resurrected removed breakpoint", current.isEmpty())
            }
        } finally {
            debugger.close(); host.close(); adapter.cancel(); scope.cancel()
            PathSecurity.deleteTreeNoFollow(root)
        }
    }

    @Test fun oldReplyCannotOverwriteNewerVerification() = race(false, false, outOfOrder = true)
    @Test fun immediateAddPublishesItsOwnFreshReply() = race(false, false, immediate = true)
    @Test fun immediateToggleAddPublishesItsOwnFreshReply() = race(false, false, immediate = true, toggle = true)
    @Test fun oldReplyCannotResurrectRemovedBreakpoint() = race(readd = false, addNew = false)
    @Test fun oldReplyCannotDeleteNewBreakpoint() = race(readd = false, addNew = true)
    @Test fun oldReplyCannotAttachMetadataToReaddedBreakpoint() = race(readd = true, addNew = false)
}
