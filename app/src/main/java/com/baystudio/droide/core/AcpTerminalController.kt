package com.baystudio.droide.core

import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Agents receive process semantics, never a free-form shell string.


internal class AcpTerminalController(
    private val processHost: StdioProcessHost,
    private val mapper: WorkspacePathMapper,
    private val approvals: ApprovalManager,
    private val permissions: PermissionEngine,
    private val permissionLedger: AcpPermissionLedger,
    private val accessPolicy: WorkspaceAgentAccessPolicy,
    private val scope: CoroutineScope,
    private val lifecycleLock: Any = Any(),
) : Closeable {
    private data class TerminalSession(
        val id: String,
        val process: HostedStdioProcess,
        val outputLimitBytes: Int,
        val output: StringBuilder = StringBuilder(),
        val lock: Any = Any(),
        @Volatile var truncated: Boolean = false,
        @Volatile var exitCode: Int? = null,
        val readers: MutableList<Job> = mutableListOf(),
        val completion: CompletableDeferred<Int> = CompletableDeferred(),
    )

    private val ids = AtomicLong(1)
    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val createMutex = Mutex()
    @Volatile private var approvalSessionId: String? = null
    // This operation has no ACP stdin counterpart. Keep its potentially blocking
    // close off the caller/owner monitor, without making it an exit prerequisite.
    internal var startInputCloseWorker: (Runnable) -> Unit = { action ->
        Thread(action, "droide-acp-terminal-eof").apply { isDaemon = true }.start()
    }
    private var lifecycleEpoch = 0L

    fun bindApprovalSession(value: String) = synchronized(lifecycleLock) {
        lifecycleEpoch++
        releasedPreviews.clear()
        approvalSessionId = value
    }

    suspend fun create(params: JsonObject, isCurrentRequest: () -> Boolean = { true }): JsonObject {
        // Capture before the mutex: an old queued request must not adopt a new turn.
        val epoch = synchronized(lifecycleLock) { lifecycleEpoch }
        val owner = approvalSessionId ?: error("ACP terminal approval session is not bound")
        return createMutex.withLock {
        fun requireCurrent() {
            check(synchronized(lifecycleLock) { lifecycleEpoch == epoch && approvalSessionId == owner && isCurrentRequest() }) {
                "ACP terminal request belongs to a retired turn or session"
            }
            accessPolicy.requireMutation("terminal execution")
        }
        requireCurrent()
        check(terminals.size < MAX_TERMINALS) { "Too many active ACP terminals" }
        val command = params["command"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal command is missing")
        ProcessSecurityPolicy.requireExecutableName(command)
        val args = (params["args"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        val environment = parseEnvironment(params["env"] as? JsonArray)
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull ?: mapper.remoteRoot
        val cwdLocal = mapper.remoteToLocal(cwd) ?: error("ACP terminal cwd escaped the workspace")
        val outputLimit = (params["outputByteLimit"]?.jsonPrimitive?.longOrNull ?: DEFAULT_OUTPUT_BYTES.toLong())
            .coerceIn(MIN_OUTPUT_BYTES.toLong(), MAX_OUTPUT_BYTES.toLong()).toInt()
        val resolved = processHost.resolveExecutable(command) ?: error("ACP terminal executable '$command' is unavailable")
        val argv = listOf(resolved) + args
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(environment)

        val canonicalCwd = mapper.localToRemote(cwdLocal)
        val identity = AcpPermissionIdentities.execute(command, args, environment, canonicalCwd)
        val preapproved = permissionLedger.consume(identity.kind, identity.operationFingerprint)
        val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal session id is missing")
        val approved = approvals.withApprovalOwner(ApprovalOwnerAdmission(lifecycleLock) {
            lifecycleEpoch == epoch && approvalSessionId == owner && isCurrentRequest() && accessPolicy.allowsWorkspaceMutation()
        }) { PermissionApprovalGate.approved(
            permissions = permissions,
            approvals = approvals,
            action = identity.action,
            resource = identity.policyResource,
            permissionScope = EXTERNAL_ACP_PERMISSION_SCOPE,
            kind = "agent_plugin_terminal",
            summary = "Run ${argv.take(4).joinToString(" ")}${if (argv.size > 4) " …" else ""}",
            detail = buildString {
                appendLine("cwd: $cwd")
                appendLine("argv: ${argv.joinToString(" ")}")
                if (environment.isNotEmpty()) appendLine("environment keys: ${environment.keys.sorted().joinToString()}")
            },
            provenance = AgentRequestProvenance.primary(sid),
            approvalSessionId = owner,
            sessionGrantAction = identity.action,
            sessionGrantResource = identity.sessionGrantResource,
            preapproved = preapproved,
        ) }
        require(approved) { "ACP terminal command denied" }

        requireCurrent()
        val process = processHost.startInWorkspace(argv, environment, cwd)
        val id = "droide-acp-terminal-${ids.getAndIncrement()}"
        val session = TerminalSession(id, process, outputLimit)
        try {
            // start may suspend while the turn ends. Register against the same epoch
            // under the cancellation lock, or reap the newly returned owned process.
            synchronized(lifecycleLock) {
                requireCurrent()
                terminals[id] = session
            }
        } catch (failure: Throwable) {
            runCatching { process.close() }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
        val outputReaders = listOf(process.stdout, process.stderr).map { input -> scope.launch(Dispatchers.IO) {
            try { drain(input, session) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                session.truncated = true
                appendBounded(session, "\n[terminal output incomplete: ${error.message?.take(200) ?: error.javaClass.simpleName}]\n")
            }
        } }
        session.readers += outputReaders
        session.readers += scope.launch(Dispatchers.IO) {
            try {
                val reportedCode = try { process.awaitExit() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { process.exitCode ?: 1 }
                val code = reportedCode.takeIf { it >= 0 } ?: 1
                // This drain budget starts only after the process exits, never during a build.
                if (withTimeoutOrNull(OUTPUT_DRAIN_MS) { outputReaders.joinAll(); true } != true) {
                    session.truncated = true
                    appendBounded(session, "\n[terminal output incomplete: a pipe remained open after process exit]\n")
                    runCatching { process.close() }.onFailure { error ->
                        appendBounded(session, "\n[terminal cleanup failed: ${error.message?.take(200) ?: error.javaClass.simpleName}]\n")
                    }
                    outputReaders.forEach(Job::cancel)
                }
                synchronized(session.lock) { session.exitCode = code }
                session.completion.complete(code)
            } catch (cancelled: CancellationException) {
                session.completion.cancel(cancelled)
                throw cancelled
            } catch (error: Exception) {
                synchronized(session.lock) {
                    session.truncated = true
                    appendBounded(session, "\n[terminal completion failed: ${error.message?.take(200) ?: error.javaClass.simpleName}]\n")
                    session.exitCode = 1
                }
                session.completion.complete(1)
            }
        }
        closeUnusedInput(session)
        buildJsonObject { put("terminalId", id) }
        }
    }

    private fun closeUnusedInput(session: TerminalSession) {
        fun report(failure: Throwable, stage: String): Boolean = synchronized(session.lock) {
            // A late EOF result cannot rewrite a proven completion snapshot.
            if (session.exitCode != null) return@synchronized false
            session.truncated = true
            appendBounded(session,
                "\n[terminal $stage failed: ${failure.message?.take(200) ?: failure.javaClass.simpleName}]\n")
            true
        }
        try {
            startInputCloseWorker(Runnable {
                try { session.process.stdin.close() }
                catch (failure: Throwable) {
                    if (report(failure, "stdin EOF")) {
                        // Only this already-retained handle is stopped. The existing
                        // completion observer still requires its real exit/cleanup.
                        try { session.process.close() }
                        catch (cleanup: Throwable) { report(cleanup, "cleanup after stdin EOF") }
                    }
                }
            })
        } catch (failure: Throwable) {
            // No worker was admitted. Keep the registered process available to
            // Stop/release/session cleanup; never block create on close here.
            report(failure, "stdin EOF worker startup")
        }
    }

    // Display-only snapshots survive release until the owning turn is retired.
    private val releasedPreviews = linkedMapOf<String, String>()
    fun preview(id: String): String? = synchronized(lifecycleLock) {
        terminals[id]?.let { session -> synchronized(session.lock) {
            session.output.toString().takeLast(32_000) +
                if (session.truncated || session.output.length > 32_000) "\n[Preview truncated]" else ""
        } } ?: releasedPreviews[id]
    }

    fun output(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        val (snapshot, truncated, code) = synchronized(terminal.lock) {
            Triple(terminal.output.toString(), terminal.truncated, terminal.exitCode)
        }
        return buildJsonObject {
            put("output", snapshot)
            put("truncated", truncated)
            if (code != null) put("exitStatus", buildJsonObject { put("exitCode", code.coerceAtLeast(0)) })
        }
    }

    suspend fun waitForExit(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        val code = terminal.completion.await()
        return buildJsonObject { put("exitCode", code.coerceAtLeast(0)) }
    }

    fun kill(params: JsonObject): JsonObject {
        val terminal = requireTerminal(params)
        terminal.process.close()
        return JsonObject(emptyMap())
    }

    fun release(params: JsonObject): JsonObject {
        val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal id is missing")
        val terminal = synchronized(lifecycleLock) {
            val current = terminals[id] ?: error("Unknown ACP terminal: $id")
            preview(id)?.let { releasedPreviews[id] = it }
            while (releasedPreviews.size > 64) releasedPreviews.remove(releasedPreviews.keys.first())
            terminals.remove(id)
            current
        }
        terminal.readers.forEach(Job::cancel)
        terminal.completion.cancel(CancellationException("Terminal released"))
        terminal.process.close()
        return JsonObject(emptyMap())
    }

    fun cancelActive(): Result<Unit> {
        val closing = synchronized(lifecycleLock) {
            releasedPreviews.clear()
            lifecycleEpoch++ // Invalidate approvals and starts that have not registered yet.
            terminals.entries.toList().mapNotNull { (id, terminal) ->
                if (terminals.remove(id, terminal)) terminal else null
            }
        }
        var failure: Throwable? = null
        closing.forEach { terminal ->
            terminal.readers.forEach(Job::cancel)
            terminal.completion.cancel(CancellationException("Terminal session closed"))
            runCatching { terminal.process.close() }.onFailure { error ->
                val prior = failure
                if (prior == null) failure = error else if (prior !== error) prior.addSuppressed(error)
            }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun requireTerminal(params: JsonObject): TerminalSession {
        val id = params["terminalId"]?.jsonPrimitive?.contentOrNull ?: error("ACP terminal id is missing")
        return terminals[id] ?: error("Unknown ACP terminal: $id")
    }

    private suspend fun drain(input: java.io.InputStream, session: TerminalSession) = withContext(Dispatchers.IO) {
        input.use { stream ->
            // Keep partial UTF-8 bytes private to this stream. A char-buffer boundary
            // can split a surrogate pair before the retained byte cap is applied.
            val decoder = Utf8StreamDecoder { appendBounded(session, it) }
            val bytes = ByteArray(4_096)
            while (true) {
                val count = stream.read(bytes)
                // A zero/late blocking read must still observe cancellation, not EOF.
                currentCoroutineContext().ensureActive()
                if (count < 0) break
                if (count > 0) decoder.accept(bytes, count)
            }
            decoder.finish()
        }
    }

    private fun appendBounded(session: TerminalSession, value: String) {
        if (value.isEmpty()) return
        synchronized(session.lock) {
            // A cancelled blocking reader may wake late. Completion freezes the snapshot.
            if (session.exitCode != null) return
            session.output.append(value)
            val bytes = session.output.toString().toByteArray(Charsets.UTF_8)
            if (bytes.size <= session.outputLimitBytes) return
            session.truncated = true
            val kept = bytes.copyOfRange(bytes.size - session.outputLimitBytes, bytes.size)
            var start = 0
            while (start < kept.size && (kept[start].toInt() and 0xC0) == 0x80) start++
            session.output.setLength(0)
            session.output.append(kept.copyOfRange(start, kept.size).toString(Charsets.UTF_8))
        }
    }

    private fun parseEnvironment(raw: JsonArray?): Map<String, String> {
        if (raw == null) return emptyMap()
        require(raw.size <= 64) { "ACP terminal environment is too large" }
        val out = linkedMapOf<String, String>()
        raw.mapNotNull { it as? JsonObject }.forEach { entry ->
            val name = entry["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value = entry["value"]?.jsonPrimitive?.contentOrNull ?: ""
            require(name !in out) { "Duplicate ACP terminal environment key: $name" }
            out[name] = value
        }
        return out
    }

    override fun close() { cancelActive().getOrThrow() }

    private companion object {
        const val MIN_OUTPUT_BYTES = 4 * 1024
        const val DEFAULT_OUTPUT_BYTES = 256 * 1024
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val MAX_TERMINALS = 8
        const val OUTPUT_DRAIN_MS = 2_000L
    }
}

internal enum class AcpPermissionKind { MUTATION, EXECUTE }

// One-shot bridge between ACP's permission request and the immediately following client operation.


internal class AcpPermissionLedger {
    private data class Grant(
        val toolCallId: String,
        val operationFingerprint: String,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val grants = mutableMapOf<AcpPermissionKind, Grant>()

    fun grant(kind: AcpPermissionKind, toolCallId: String, operationFingerprint: String) {
        require(toolCallId.isNotBlank() && toolCallId.length <= 512)
        require(operationFingerprint.matches(Regex("[0-9a-f]{64}")))
        synchronized(lock) {
            grants[kind] = Grant(
                toolCallId = toolCallId,
                operationFingerprint = operationFingerprint,
                expiresAt = System.currentTimeMillis() + GRANT_TTL_MS,
            )
        }
    }

    fun consume(kind: AcpPermissionKind, operationFingerprint: String): Boolean = synchronized(lock) {
        val grant = grants.remove(kind) ?: return@synchronized false
        System.currentTimeMillis() <= grant.expiresAt &&
            constantTimeEquals(grant.operationFingerprint, operationFingerprint)
    }

    fun clear() = synchronized(lock) { grants.clear() }

    companion object {
        private const val GRANT_TTL_MS = 15_000L

        fun mutationFingerprint(canonicalRemotePath: String, content: String): String = digest(
            listOf("mutation", canonicalRemotePath, content),
        )

        fun executeFingerprint(
            command: String,
            args: List<String>,
            environment: Map<String, String>,
            canonicalCwd: String,
        ): String = digest(
            buildList {
                add("execute")
                add(command)
                add(canonicalCwd)
                add(args.size.toString())
                addAll(args)
                add(environment.size.toString())
                environment.toSortedMap().forEach { (key, value) ->
                    add(key)
                    add(value)
                }
            },
        )

        private fun digest(parts: List<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                md.update(((bytes.size ushr 24) and 0xff).toByte())
                md.update(((bytes.size ushr 16) and 0xff).toByte())
                md.update(((bytes.size ushr 8) and 0xff).toByte())
                md.update((bytes.size and 0xff).toByte())
                md.update(bytes)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))
    }
}
