package com.baystudio.droide.core

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real adapter/child processes and TCP sockets; Android LocalSocket/native CLI are separate gates. */
class ExternalIdeMcpTransportTest {
    @get:org.junit.Rule val applicationWork: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private class Host : Closeable {
        val root = Files.createTempDirectory("mcp-transport-").toFile()
        val entered = LinkedBlockingQueue<String>()
        val cancelled = LinkedBlockingQueue<String>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val pids = ConcurrentHashMap.newKeySet<Long>()
        val clients = CopyOnWriteArrayList<Client>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> failures.add(e) })
        val server = ServerSocket(0, 32, InetAddress.getLoopbackAddress())
        val binding = ExternalIdeToolRegistry.bind("transport-${server.localPort}", root, object : ExternalIdeToolRegistry.Provider {
            override val definitions = JsonArray(emptyList())
            override suspend fun call(name: String, arguments: JsonObject): String {
                val label = arguments["label"]!!.jsonPrimitive.content
                entered.add(label)
                if (arguments["wait"] == JsonPrimitive(true)) {
                    try { awaitCancellation() } finally { cancelled.add(label) }
                }
                return arguments["text"]?.jsonPrimitive?.content?.repeat(8) ?: label
            }
        })
        val bridge = root.resolve("mock-cli ' quoted").apply {
            writeText("""
                #!/usr/bin/python3
                import json, os, signal, socket, sys
                with socket.create_connection(("127.0.0.1", ${server.localPort})) as connection:
                    connection.sendall((json.dumps({"pid": os.getpid(), "args": sys.argv[1:]}) + "\n").encode())
                    if json.loads(sys.argv[4]).get("params", {}).get("arguments", {}).get("label") == "flood":
                        signal.signal(signal.SIGTERM, signal.SIG_IGN)
                        sys.stdout.buffer.write(b"x" * 1000000)
                        sys.stdout.buffer.flush()
                        signal.pause()
                    while True:
                        data = connection.recv(8192)
                        if not data:
                            break
                        sys.stdout.buffer.write(data)
                        sys.stdout.buffer.flush()
            """.trimIndent() + "\n")
            check(setExecutable(true))
        }
        val acceptor = Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) {
                    if (!server.isClosed) failures.add(e)
                    break
                }
                scope.launch {
                    socket.use {
                        socket.soTimeout = 3000
                        val request = Json.parseToJsonElement(socket.getInputStream().bufferedReader().readLine()).jsonObject
                        pids.add(request["pid"]!!.jsonPrimitive.long)
                        val args = request["args"]!!.jsonArray.map { it.jsonPrimitive.content }
                        check(args.first() == "ide")
                        socket.soTimeout = 0
                        CliConnectionLifetime.serve(
                            operation = { ExternalIdeProtocol.cli(binding.workspaceId, args.drop(1)) },
                            readPeer = { socket.getInputStream().read() },
                            closePeer = { socket.close() },
                            writeResponse = { result ->
                                check(result.exitCode == 0) { result.stderr }
                                socket.getOutputStream().write(result.stdout.toByteArray())
                                socket.getOutputStream().flush()
                            },
                        )
                    }
                }
            }
        }, "mcp-fixture-accept").apply { start() }

        fun client() = Client(this).also { clients.add(it) }
        override fun close() {
            clients.forEach { it.close() }
            server.close()
            acceptor.join(3000)
            runBlocking { withTimeout(5000) { scope.coroutineContext.job.cancelAndJoin() } }
            ExternalIdeToolRegistry.unbind(binding)
            assertFalse(acceptor.isAlive)
            assertTrue(failures.toString(), failures.isEmpty())
            assertTrue("Owned CLI children still alive: $pids", pids.none { java.io.File("/proc/$it").exists() })
            assertTrue(PathSecurity.deleteTreeNoFollow(root))
        }
    }

    private class Client(host: Host) : Closeable {
        val errors = host.root.resolve("stderr-${host.clients.size}.log")
        val process = ProcessBuilder("/bin/sh", "-c", ExternalIdeProtocol.stdioScript, "mcp", host.bridge.absolutePath, host.binding.token)
            .directory(host.root).redirectError(errors).start()
        val input = process.outputStream.bufferedWriter()
        val lines = LinkedBlockingQueue<String>()
        val reader = Thread({ process.inputStream.bufferedReader().useLines { stream -> stream.forEach { lines.add(it) } } }, "mcp-fixture-output").apply { start() }
        fun send(value: JsonObject) = send(value.toString())
        fun send(value: String) { input.write(value + "\n"); input.flush() }
        fun response(): JsonObject = Json.parseToJsonElement(checkNotNull(lines.poll(4, TimeUnit.SECONDS)) {
            "No MCP response; ${errors.readText()}"
        }).jsonObject
        fun cancel(id: JsonElement) = send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/cancelled")
            put("params", buildJsonObject { put("requestId", id) })
        })
        override fun close() {
            runCatching { input.close() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS)
                fail("MCP failed to drain owned children on EOF: ${errors.readText()}")
            }
            reader.join(2000)
            assertFalse(reader.isAlive)
        }
    }

    private fun request(id: JsonElement, label: String, wait: Boolean = false, text: String? = null) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
        put("params", buildJsonObject {
            put("name", "droide_toolchain"); put("arguments", buildJsonObject {
                put("label", label); put("wait", wait); if (text != null) put("text", text)
            })
        })
    }
    private fun expect(queue: LinkedBlockingQueue<String>, value: String) = assertEquals(value, queue.poll(4, TimeUnit.SECONDS))

    @Test(timeout = 15_000) fun cancellationReachesWaitingHostWhileOtherRequestsKeepWorking() {
        Host().use { host ->
            val client = host.client()
            client.send(request(JsonPrimitive(1), "waiting", true)); expect(host.entered, "waiting")
            client.send(request(JsonPrimitive(2), "quick")); expect(host.entered, "quick")
            assertEquals(JsonPrimitive(2), client.response()["id"])
            client.cancel(JsonPrimitive(1)); expect(host.cancelled, "waiting")
            client.send(request(JsonPrimitive(3), "after")); expect(host.entered, "after")
            assertEquals(JsonPrimitive(3), client.response()["id"])
            assertTrue(client.lines.isEmpty()); assertTrue(client.process.isAlive)
        }
    }

    @Test(timeout = 15_000) fun idsAreTypedAndScopedToTheirStdioConnection() {
        Host().use { host ->
            val a = host.client(); val b = host.client()
            a.send(request(JsonPrimitive(0), "a-number", true)); expect(host.entered, "a-number")
            a.send(request(JsonPrimitive("0"), "a-string", true)); expect(host.entered, "a-string")
            b.send(request(JsonPrimitive(0), "b-number", true)); expect(host.entered, "b-number")
            a.cancel(JsonPrimitive(0)); expect(host.cancelled, "a-number")
            a.cancel(JsonPrimitive(true)) // Boolean must never alias numeric ID 1/0.
            a.cancel(JsonPrimitive("0")); expect(host.cancelled, "a-string")
            assertTrue(host.cancelled.isEmpty())
            b.cancel(JsonPrimitive(0)); expect(host.cancelled, "b-number")
        }
    }

    @Test(timeout = 15_000) fun eofCancelsAndReapsAnOutstandingHostOperation() {
        Host().use { host ->
            val client = host.client()
            client.send(request(JsonPrimitive(7), "eof", true)); expect(host.entered, "eof")
            client.close(); expect(host.cancelled, "eof")
            assertEquals(0, client.process.exitValue())
        }
    }

    @Test(timeout = 15_000) fun oversizedOutputFailsClosedAndReapsAChildIgnoringTermination() {
        Host().use { host ->
            val client = host.client()
            client.send(request(JsonPrimitive(8), "flood", true)); expect(host.entered, "flood")
            assertTrue(client.process.waitFor(6, TimeUnit.SECONDS))
            expect(host.cancelled, "flood")
            assertNotEquals(0, client.process.exitValue())
            assertTrue(client.errors.readText().contains("exceeds transport capacity"))
            assertTrue(client.lines.isEmpty())
        }
    }

    @Test(timeout = 15_000) fun terminationSignalDrainsOutstandingRequests() {
        Host().use { host ->
            val client = host.client()
            client.send(request(JsonPrimitive(8), "signal", true)); expect(host.entered, "signal")
            client.process.destroy()
            assertTrue(client.process.waitFor(5, TimeUnit.SECONDS))
            expect(host.cancelled, "signal")
        }
    }

    @Test(timeout = 15_000) fun duplicateIdCannotReplaceOwnerAndCancelWorksAtCapacity() {
        Host().use { host ->
            val client = host.client()
            for (id in 1..16) {
                client.send(request(JsonPrimitive(id), "slot-$id", true)); expect(host.entered, "slot-$id")
            }
            client.send(request(JsonPrimitive(1), "duplicate", true))
            assertEquals(JsonPrimitive(-32600), client.response()["error"]!!.jsonObject["code"])
            client.send(request(JsonPrimitive(17), "overflow", true))
            assertEquals(JsonPrimitive(-32000), client.response()["error"]!!.jsonObject["code"])
            client.cancel(JsonPrimitive(1)); expect(host.cancelled, "slot-1")
            assertTrue(host.entered.isEmpty())
        }
    }

    @Test(timeout = 15_000) fun concurrentLargeResponsesStayWholeAndShellMetacharactersStayData() {
        Host().use { host ->
            val client = host.client()
            val text = "${'$'}(touch escaped) `touch escaped` ' quoted 🌌 " + "z".repeat(2700)
            for (id in 1..8) client.send(request(JsonPrimitive(id), "large-$id", text = text))
            val responses = (1..8).map { client.response() }
            assertEquals((1..8).toSet(), responses.map { it["id"]!!.jsonPrimitive.int }.toSet())
            responses.forEach { assertEquals(text.repeat(8), it["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content) }
            assertFalse(host.root.resolve("escaped").exists())
        }
    }

    @Test(timeout = 15_000) fun cancelBeforeSpawnAndMalformedNotificationsNeverExecuteTools() {
        Host().use { host ->
            val client = host.client()
            // One write makes both messages available before an invocation can start.
            val call = request(JsonPrimitive(9), "never", true)
            val cancel = """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":9}}"""
            client.send(call.toString() + "\n" + cancel)
            client.send("""{"jsonrpc":"2.0","method":"tools/call","params":{"name":"droide_toolchain"}}""")
            client.send(request(JsonPrimitive(10), "sentinel"))
            assertEquals(JsonPrimitive(10), client.response()["id"])
            expect(host.entered, "sentinel"); assertTrue(host.entered.isEmpty())
        }
    }
}
