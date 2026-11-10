package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DebugConsoleInputIntegrityTest {
    private fun checkExpression(expression: String, accepted: Boolean) = runBlocking {
        val root = Files.createTempDirectory("debug-console-input-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DebugProtocolFixture(root)
        val debugger = DebugManager(scope, FileRepository(root), TerminalManager(scope, root), remoteProcessHost = host)
        val observed = java.util.concurrent.CopyOnWriteArrayList<String>()
        val adapter = scope.launch {
            for (request in host.requests) {
                when (request["command"]?.jsonPrimitive?.content) {
                    "launch" -> {
                        host.reply(request)
                        host.send(buildJsonObject { put("seq", 100); put("type", "event"); put("event", "initialized") })
                    }
                    "evaluate" -> {
                        observed += request["arguments"]!!.jsonObject["expression"]!!.jsonPrimitive.content
                        host.reply(request, buildJsonObject { put("result", "ok") })
                    }
                    else -> host.reply(request)
                }
            }
        }
        try {
            debugger.start(DebugConfiguration("fixture", "launch", listOf("fixture-dap"), buildJsonObject {}, "test"))
            if (accepted) {
                assertEquals("ok", debugger.evaluate(expression))
                assertEquals(listOf(expression), observed.toList())
            } else {
                val error = runCatching { debugger.evaluate(expression) }.exceptionOrNull()
                assertTrue(error is IllegalArgumentException)
                assertTrue("Oversized expression must never reach the adapter", observed.isEmpty())
            }
        } finally { debugger.close(); host.close(); adapter.cancel(); scope.cancel(); PathSecurity.deleteTreeNoFollow(root) }
    }
    @Test fun oversizedExpressionNeverReachesAdapter() = checkExpression("x".repeat(20_000) + " + suffix", false)
    @Test fun boundaryUnicodeExpressionReachesAdapterUnchanged() = checkExpression("x".repeat(19_998) + "\uD83D\uDE00", true)
}
