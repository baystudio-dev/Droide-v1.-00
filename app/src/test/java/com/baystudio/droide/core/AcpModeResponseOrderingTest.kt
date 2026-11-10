package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Candidate regression sources only. NOT compiled or executed. Uses only fake process pipes. */
class AcpModeResponseOrderingTest {
    private class Peer(private val root: File, inputCapacity: Int = 1_024) : AutoCloseable {
        val clientInput = PipedOutputStream()
        val serverInput = PipedInputStream(clientInput, inputCapacity)
        val serverOutput = PipedOutputStream()
        val clientOutput = PipedInputStream(serverOutput, 1_024)
        val serverError = PipedOutputStream()
        val clientError = PipedInputStream(serverError, 1_024)
        private val alive = AtomicBoolean(true)
        private var worker: Thread? = null
        val failure = AtomicReference<Throwable?>()
        val process = object : HostedStdioProcess {
            override val stdin = clientInput
            override val stdout = clientOutput
            override val stderr = clientError
            override val isAlive get() = alive.get()
            override val exitCode get() = if (alive.get()) null else 0
            override suspend fun awaitExit() = 0
            override fun close() { this@Peer.close() }
        }
        val mapper = WorkspacePathMapper(root, root.canonicalPath)
        val host = object : StdioProcessHost {
            override val executionScope = ProcessExecutionScope.LOCAL_LINUX_ARM64
            override suspend fun resolveExecutable(command: String) = command
            override suspend fun pathMapper() = mapper
            override suspend fun start(argv: List<String>, environment: Map<String, String>, resourceLimits: ProcessResourceLimits?) = process
        }
        val io = object : AgentWorkspaceIo {
            override val checksumCommand = "sha256sum"
            override fun requirePath(path: String) = Unit
            override suspend fun refresh() = Unit
            override suspend fun shellBounded(command: String, maxOutputBytes: Int): BridgeShellResult = error("No execution in transport fixture")
            override suspend fun pull(path: String, output: File) = error("No transfer in transport fixture")
            override suspend fun push(input: File, path: String) = error("No transfer in transport fixture")
        }
        fun run(block: Peer.() -> Unit) {
            worker = Thread({ try { block() } catch (error: Exception) { if (alive.get()) failure.set(error) } }, "droide-acp-transport-test")
                .apply { isDaemon = true; start() }
        }
        fun respond(request: JsonObject, result: String = "{}") {
            serverOutput.write("""{"jsonrpc":"2.0","id":${request["id"]},"result":$result}
""".toByteArray()); serverOutput.flush()
        }
        override fun close() {
            if (alive.compareAndSet(true, false)) {
                worker?.interrupt()
                listOf(clientInput, serverInput, serverOutput, clientOutput, serverError, clientError).forEach { runCatching { it.close() } }
            }
        }
        fun join() { worker?.join(2_000); check(worker?.isAlive != true) }
    }

    private fun fixture(inputCapacity: Int = 1_024, block: suspend CoroutineScope.(Peer, AcpNdjsonConnection) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("droide-acp-feedback-").toFile()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> uncaught += error })
        val peer = Peer(root, inputCapacity)
        val rpc = AcpNdjsonConnection(peer.process, FileRepository(root), peer.mapper, peer.host, ApprovalManager(), PermissionEngine(), scope, peer.io)
        try { withTimeout(5_000) { block(peer, rpc) }; assertNull(peer.failure.get()) }
        finally {
            rpc.close(); peer.close(); peer.join(); scope.coroutineContext.job.cancelAndJoin(); root.deleteRecursively()
            assertTrue("Protocol tasks escaped their boundary: $uncaught", uncaught.isEmpty())
        }
    }

    @Test(timeout = 8_000) fun responseCallbackPrecedesLaterModeNotificationOnTheWire() = fixture { peer, rpc ->
        val order = CopyOnWriteArrayList<String>()
        rpc.bindSession("owned_session", "approval_owner")
        rpc.configurationListener = { order += "update:" + it["currentModeId"]!!.jsonPrimitive.content }
        peer.run {
            val reader = serverInput.bufferedReader()
            val change = Json.parseToJsonElement(reader.readLine()).jsonObject
            respond(change)
            serverOutput.write(("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"owned_session","update":{"sessionUpdate":"current_mode_update","currentModeId":"plan"}}}""" + "\n").toByteArray())
            serverOutput.flush()
            val barrier = Json.parseToJsonElement(reader.readLine()).jsonObject
            respond(barrier)
            Thread.sleep(4_000)
        }
        rpc.request("session/set_mode", JsonObject(emptyMap()), 3_000, onResponse = { order += "response:default" })
        rpc.request("test_barrier", JsonObject(emptyMap()), 3_000)
        assertEquals(listOf("response:default", "update:plan"), order)
    }

    @Test(timeout = 8_000) fun invalidModeResponseFailsCallerWithoutStrandingReaderOrNextRequest() = fixture { peer, rpc ->
        peer.run {
            val reader = serverInput.bufferedReader()
            repeat(2) { respond(Json.parseToJsonElement(reader.readLine()).jsonObject) }
            Thread.sleep(4_000)
        }
        val failure = runCatching {
            rpc.request("session/set_config_option", JsonObject(emptyMap()), 3_000, onResponse = {
                error("Mode response did not confirm requested profile")
            })
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Mode response did not confirm requested profile", failure!!.message)
        assertEquals(JsonObject(emptyMap()), rpc.request("test_barrier", JsonObject(emptyMap()), 3_000))
    }

    @Test(timeout = 8_000) fun nonObjectResultsNeverBecomeSuccessfulEmptyModeAcknowledgements() = fixture { peer, rpc ->
        val malformed = listOf("null", "false", "17", "\"ack\"", "[]")
        val callbacks = CopyOnWriteArrayList<String>()
        peer.run {
            val reader = serverInput.bufferedReader()
            malformed.forEach { result -> respond(Json.parseToJsonElement(reader.readLine()).jsonObject, result) }
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject)
            Thread.sleep(4_000)
        }
        malformed.forEach {
            val failure = runCatching { rpc.request("session/set_mode", JsonObject(emptyMap()), 3_000,
                onResponse = { callbacks += "incorrect success" }) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("ACP response result must be an object", failure!!.message)
        }
        assertTrue(callbacks.isEmpty())
        assertEquals(JsonObject(emptyMap()), rpc.request("test_barrier", JsonObject(emptyMap()), 3_000))
    }

    @Test(timeout = 8_000) fun malformedOrConflictingErrorOutcomesFailWithoutStrandingRemovedCalls() = fixture { peer, rpc ->
        val bodies = listOf("\"error\":null", "\"error\":[]", "\"error\":{}",
            "\"error\":{\"code\":\"-32602\",\"message\":\"bad\"}",
            "\"error\":{\"code\":-32602,\"message\":[]}",
            "\"result\":{},\"error\":{\"code\":-32602,\"message\":\"bad\"}")
        val callbacks = CopyOnWriteArrayList<String>()
        peer.run {
            val reader = serverInput.bufferedReader()
            bodies.forEach { body ->
                val request = Json.parseToJsonElement(reader.readLine()).jsonObject
                serverOutput.write(("{\"jsonrpc\":\"2.0\",\"id\":${request["id"]},$body}\n").toByteArray())
                serverOutput.flush()
            }
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject)
            Thread.sleep(4_000)
        }
        bodies.forEach {
            val failure = runCatching { rpc.request("session/set_mode", JsonObject(emptyMap()), 3_000,
                onResponse = { callbacks += "incorrect success" }) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
            assertFalse(failure is AcpRequestTimeoutException)
        }
        assertTrue(callbacks.isEmpty())
        assertEquals(JsonObject(emptyMap()), rpc.request("test_barrier", JsonObject(emptyMap()), 3_000))
    }

    @Test(timeout = 8_000) fun startupModeUpdatesBeforeBindAndAfterHandoffRemainAuthoritative() = fixture { peer, rpc ->
        var configuration: WorkspaceAgentSessionConfiguration? = null
        peer.run {
            val reader = serverInput.bufferedReader()
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject,
                """{"sessionId":"owned_session","modes":{"currentModeId":"default","availableModes":[{"id":"default"},{"id":"plan"}]}}""")
            serverOutput.write(("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"owned_session","update":{"sessionUpdate":"current_mode_update","currentModeId":"plan"}}}""" + "\n").toByteArray())
            serverOutput.flush()
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject)
            val afterInstall = Json.parseToJsonElement(reader.readLine()).jsonObject
            serverOutput.write(("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"owned_session","update":{"sessionUpdate":"current_mode_update","currentModeId":"default"}}}""" + "\n").toByteArray())
            serverOutput.flush(); respond(afterInstall)
            Thread.sleep(4_000)
        }
        rpc.request("session/new", JsonObject(emptyMap()), 3_000)
        rpc.request("test_prebind_barrier", JsonObject(emptyMap()), 3_000)
        rpc.bindSession("owned_session", "approval_owner")
        rpc.installConfigurationListener("owned_session", { configuration = it }, {
            configuration = WorkspaceAgentSessionConfigurations.update(it, checkNotNull(configuration))
        })
        assertEquals("plan", configuration!!.currentModeId)
        rpc.request("test_postinstall_barrier", JsonObject(emptyMap()), 3_000)
        assertEquals("default", configuration!!.currentModeId)
    }

    @Test(timeout = 8_000) fun loadHandoffUsesRequestedIdentityAndIgnoresAnotherSessionUpdate() = fixture { peer, rpc ->
        var configuration: WorkspaceAgentSessionConfiguration? = null
        peer.run {
            val reader = serverInput.bufferedReader()
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject,
                """{"modes":{"currentModeId":"default","availableModes":[{"id":"default"},{"id":"plan"}]}}""")
            serverOutput.write(("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"another_session","update":{"sessionUpdate":"current_mode_update","currentModeId":"plan"}}}""" + "\n").toByteArray())
            serverOutput.flush()
            respond(Json.parseToJsonElement(reader.readLine()).jsonObject)
            Thread.sleep(4_000)
        }
        rpc.request("session/load", buildJsonObject { put("sessionId", "loaded_session") }, 3_000)
        rpc.request("test_barrier", JsonObject(emptyMap()), 3_000)
        rpc.bindSession("loaded_session", "approval_owner")
        assertTrue(runCatching { rpc.installConfigurationListener("another_session", {}, {}) }.isFailure)
        rpc.installConfigurationListener("loaded_session", { configuration = it }, {})
        assertEquals("default", configuration!!.currentModeId)
    }

    @Test(timeout = 8_000) fun failedSnapshotDeliveryRetainsHandoffAndNeverPretendsSubscriptionSucceeded() = fixture { peer, rpc ->
        var configuration: WorkspaceAgentSessionConfiguration? = null
        peer.run {
            val request = Json.parseToJsonElement(serverInput.bufferedReader().readLine()).jsonObject
            respond(request, """{"sessionId":"owned_session","modes":{"currentModeId":"plan","availableModes":[{"id":"plan"}]}}""")
            Thread.sleep(4_000)
        }
        rpc.request("session/new", JsonObject(emptyMap()), 3_000)
        rpc.bindSession("owned_session", "approval_owner")
        assertTrue(runCatching { rpc.installConfigurationListener("owned_session", { error("snapshot receiver failed") }, {}) }.isFailure)
        rpc.installConfigurationListener("owned_session", { configuration = it }, {})
        assertEquals("plan", configuration!!.currentModeId)
        assertTrue(runCatching { rpc.installConfigurationListener("owned_session", {}, {}) }.isFailure)
    }
}
