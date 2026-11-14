package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class CMakeSelectionContext(
    val environmentIdentity: String,
    val mappingRevision: String,
    val mapper: WorkspacePathMapper,
    val inspector: CMakeAnalysisSelections.Inspector,
)

interface CMakeHostedProcess : HostedStdioProcess {
    val owner: CMakePreparedOwner
    suspend fun checkedAdmission(): CMakeHostAdmissionObservation?
    fun isAdmissionCurrent(observation: CMakeHostAdmissionObservation): Boolean
    suspend fun stopAndAwaitOwnedGroup(): CMakeTeardownReceipt
}

interface CMakePreparedOwner : java.io.Closeable {
    val identity: CMakeManagedLaunchIdentity
    val request: CMakeOwnerRequest
    val buildGuestAbsolute: String
    val ownerGeneration: Long
    suspend fun retire(): CMakeTeardownReceipt?
}

/** Immutable paths and revocable host generation; cleanup ownership exists before child creation. */
internal class LocalCMakePreparedOwner(
    override val identity: CMakeManagedLaunchIdentity,
    override val request: CMakeOwnerRequest,
    val privateState: CMakePrivateState,
    internal val leases: CMakeBuildOwnerLeases,
    internal val lease: CMakeBuildOwnerLeases.Lease,
    private val mapper: WorkspacePathMapper,
    private val checkManagedBeforeStart: ((() -> Unit) -> Boolean) = { false },
    private val retireManagedIdentity: () -> Unit = {},
    private val checkManagedIdentity: suspend () -> Boolean,
) : CMakePreparedOwner {
    override val buildGuestAbsolute: String = request.selection.configuredBuildGuestAbsolute ?: privateState.guest("build")
    override val ownerGeneration: Long get() = request.ownerGeneration
    val nonce: String get() = lease.nonce
    val leaseId: String get() = lease.id
    private val token = Any()
    private val generation = AtomicLong(1)
    private val retired = AtomicBoolean(false)
    private val lifecycle = Any()
    private val retirement = Mutex()
    private val retainedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: LocalCMakeHostedProcess? = null
    @Volatile private var receipt: CMakeTeardownReceipt? = null
    @Volatile private var ready: CMakeReadyProof? = null
    @Volatile private var neverStartedRetired = false
    private val observationMutex = Mutex()
    private var preparedLaunchSpec: TerminalLaunchSpec? = null

    internal fun storePreparedLaunchSpec(spec: TerminalLaunchSpec) = synchronized(lifecycle) {
        check(!retired.get() && process == null && preparedLaunchSpec == null)
        preparedLaunchSpec = spec
    }
    internal fun requirePreparedLaunchSpec(): TerminalLaunchSpec = synchronized(lifecycle) {
        check(!retired.get() && process == null)
        requireNotNull(preparedLaunchSpec)
    }

    /** Called outside the manager lock while the package transaction/mutation gates are held. */
    internal fun requireManagedIdentityBeforeStart(checkCancelled: () -> Unit) {
        check(!retired.get())
        val valid = try { checkManagedBeforeStart(checkCancelled) }
        catch (failure: Throwable) { revoke(); throw failure }
        if (!valid) { revoke(); error("CMake immutable owner admission changed before start") }
    }

    internal fun createProcess(factory: () -> Process): LocalCMakeHostedProcess = synchronized(lifecycle) {
        check(!retired.get() && process == null)
        leases.starting(lease)
        try {
            LocalCMakeHostedProcess(factory(), this, retainedScope).also {
                process = it
                ExitHostedProcessRegistry.retain(it)
                it.beginObservation()
            }
        } catch (failure: Throwable) {
            revoke(); throw failure
        }
    }
    internal suspend fun awaitReady() {
        val proof = withTimeout(LocalUbuntuCMakeLaunchPolicy.STARTUP_MS) {
            while (true) {
                currentCoroutineContext().ensureActive()
                check(!retired.get()) { "CMake owner retired before readiness" }
                val file = File(privateState.localRoot, "ready.json")
                if (file.exists()) {
                    privateState.verifyRoot()
                    val value = CMakeControlRecords.read(file)
                    privateState.verifyRoot()
                    val observed = CMakeControlRecords.parseReady(value)
                    check(observed.nonce == nonce && observed.leaseId == leaseId && observed.ownerGeneration == ownerGeneration &&
                        observed.environmentIdentity == request.selection.environmentIdentity && CMakeControlRecords.sameKernel(observed))
                    check(CMakeControlRecords.procStartTicks(observed.outer.pid) == observed.outer.startTicks &&
                        CMakeControlRecords.procStartTicks(observed.supervisor.pid) == observed.supervisor.startTicks &&
                        CMakeControlRecords.procStartTicks(observed.leader.pid) == observed.leader.startTicks)
                    return@withTimeout observed
                }
                check(process?.isAlive == true) { "CMake supervisor exited before READY" }
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
        synchronized(lifecycle) {
            check(!retired.get()); leases.ready(lease, proof); ready = proof
        }
    }
    suspend fun checkedAdmission(): CMakeHostAdmissionObservation? = observationMutex.withLock {
        if (retired.get() || ready == null || process?.isAlive != true) return@withLock null
        val expected = generation.get()
        val valid = try {
            withTimeout(5_000) {
                withContext(Dispatchers.IO) {
                    checkManagedIdentity() && observeSelection()
                }
            }
        } catch (_: Exception) { false }
        if (!valid) { close(); return@withLock null }
        if (retired.get() || expected != generation.get() || process?.isAlive != true) return@withLock null
        CMakeHostAdmissionObservation(token, expected)
    }
    /** Pure final wire/application check: no filesystem, blocking calls or suspension. */
    fun isAdmissionCurrent(observation: CMakeHostAdmissionObservation): Boolean =
        observation.owner === token && observation.generation == generation.get() && !retired.get() && process?.knownLive == true

    internal fun observeSelection(): Boolean {
        val selected = request.selection
        privateState.verify()
        require(mapper.localToRemote(File(selected.sourceLocalCanonical)) == selected.sourceGuestAbsolute &&
            mapper.remoteToLocal(selected.sourceGuestAbsolute)?.path == selected.sourceLocalCanonical)
        require(selected.sourceDirectoryIdentity.environmentIdentity == selected.environmentIdentity)
        require((selected.buildMode == CMakeAnalysisSelections.BuildMode.BUILTIN_ONLY_PRIVATE) == (selected.configuredBuildDirectoryIdentity == null))
        if (selected.configuredBuildDirectoryIdentity == null) {
            require(selected.configuredBuildLocalCanonical == null && selected.configuredBuildGuestAbsolute == null && selected.cacheSourceAssociation == null)
        } else {
            require(selected.configuredBuildDirectoryIdentity.environmentIdentity == selected.environmentIdentity)
            require(mapper.localToRemote(File(requireNotNull(selected.configuredBuildLocalCanonical))) == selected.configuredBuildGuestAbsolute &&
                mapper.remoteToLocal(requireNotNull(selected.configuredBuildGuestAbsolute))?.path == selected.configuredBuildLocalCanonical)
        }
        check(CMakeSelectionInspector.directoryIdentity(File(selected.sourceLocalCanonical), selected.environmentIdentity) == selected.sourceDirectoryIdentity)
        if (selected.configuredBuildDirectoryIdentity == null) {
            val build = File(privateState.localRoot, "build")
            val actual = CMakeSelectionInspector.directoryIdentity(build, selected.environmentIdentity)
            check(actual.ownerUid == privateState.identity.ownerUid && actual.mode and 511 == 448)
            check(!Files.exists(File(build, "CMakeCache.txt").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Unexpected cache appeared in private CMake build"
            }
        } else {
            val build = File(requireNotNull(selected.configuredBuildLocalCanonical))
            check(CMakeSelectionInspector.directoryIdentity(build, selected.environmentIdentity) == selected.configuredBuildDirectoryIdentity)
            val bytes = CMakeSelectionInspector.readCache(File(build, "CMakeCache.txt"), 1_048_576)
            check(CMakeAnalysisSelections.validateCacheAssociation(bytes, selected, mapper) == selected.cacheSourceAssociation)
            check(CMakeSelectionInspector.directoryIdentity(build, selected.environmentIdentity) == selected.configuredBuildDirectoryIdentity)
        }
        check(CMakeSelectionInspector.directoryIdentity(File(selected.sourceLocalCanonical), selected.environmentIdentity) == selected.sourceDirectoryIdentity)
        return true
    }
    internal fun revoke() = synchronized(lifecycle) {
        if (retired.compareAndSet(false, true)) { generation.incrementAndGet(); retireManagedIdentity() }
        Unit
    }
    override fun close() {
        revoke()
        retainedScope.launch { runCatching { retire() } }
    }
    /** Idempotent finite retirement, also valid when cancellation wins before factory publication. */
    override suspend fun retire(): CMakeTeardownReceipt? = withContext(NonCancellable + Dispatchers.IO) {
        revoke()
        retirement.withLock {
            receipt?.let { return@withLock it }
            if (neverStartedRetired) return@withLock null
            val owned = synchronized(lifecycle) { process }
            if (owned == null) {
                val notStarted = lease.phase == "RESERVED"
                if (notStarted) leases.releaseNeverStarted(lease) else leases.quarantine(lease)
                privateState.cleanup(notStarted)
                neverStartedRetired = true
                return@withLock null
            }
            val result = owned.finishOwnedGroup(ready)
            if (result.releasesBuild) leases.release(lease, result) else leases.quarantine(lease)
            val cleanup = privateState.cleanup(result.releasesBuild)
            result.copy(privateCleanupStatus = cleanup).also { receipt = it }
        }
    }
    internal fun recordExit() { revoke() }
    internal suspend fun requireReceipt(): CMakeTeardownReceipt = requireNotNull(retire())
}

internal class LocalCMakeHostedProcess(
    private val process: Process,
    override val owner: LocalCMakePreparedOwner,
    private val scope: CoroutineScope,
) : CMakeHostedProcess {
    @Volatile internal var knownLive = true
        private set
    @Volatile private var code: Int? = null
    private val exited = CompletableDeferred<Int>()
    override val stdin: OutputStream get() = process.outputStream
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override val isAlive: Boolean get() = process.isAlive
    override val exitCode: Int? get() = code
    private val waiter = scope.launch(start = CoroutineStart.LAZY) {
        try { code = process.waitFor() }
        finally {
            knownLive = false; owner.recordExit()
            runCatching {
                val result = requireNotNull(owner.retire())
                check(result.releasesBuild) { "CMake owned group remains quarantined; ordinary exit is not release proof" }
            }.fold(onSuccess = { exited.complete(code ?: -1) }, onFailure = { exited.completeExceptionally(it) })
        }
    }
    internal fun beginObservation() { waiter.start() }
    override suspend fun awaitExit(): Int = exited.await()
    override fun close() = owner.close()
    override suspend fun checkedAdmission() = owner.checkedAdmission()
    override fun isAdmissionCurrent(observation: CMakeHostAdmissionObservation) = owner.isAdmissionCurrent(observation)
    override suspend fun stopAndAwaitOwnedGroup(): CMakeTeardownReceipt = owner.requireReceipt()

    internal suspend fun finishOwnedGroup(expected: CMakeReadyProof?): CMakeTeardownReceipt {
        var forcedOuter = false
        if (process.isAlive) runCatching { owner.privateState.writeRecord("stop", owner.nonce) }
        var reaped = process.waitFor(9_000, TimeUnit.MILLISECONDS)
        if (!reaped) {
            forcedOuter = true
            process.destroy()
            reaped = process.waitFor(250, TimeUnit.MILLISECONDS)
            if (!reaped) { process.destroyForcibly(); reaped = process.waitFor(750, TimeUnit.MILLISECONDS) }
        }
        if (reaped) { code = process.exitValue(); knownLive = false }
        // Closing an externally read pipe may block on its monitor. Never call it in the manager lock
        // or infer completion when a bounded close worker fails to finish.
        val closed = CompletableDeferred<Boolean>()
        scope.launch {
            val clean = runCatching { process.outputStream.close(); process.inputStream.close(); process.errorStream.close(); true }.getOrDefault(false)
            closed.complete(clean)
        }
        val transportClosed = withTimeoutOrNull(1_000) { closed.await() } == true
        val raw = runCatching {
            owner.privateState.verifyRoot()
            val value = CMakeControlRecords.read(File(owner.privateState.localRoot, "receipt.json"))
            owner.privateState.verifyRoot()
            CMakeControlRecords.requireReceiptShape(value)
            value
        }.getOrNull()
        val proof = raw?.let { runCatching { CMakeControlRecords.parseReceiptReady(it) }.getOrNull() }
        val correct = proof != null && proof == expected && !forcedOuter && reaped &&
            raw["status"]?.jsonPrimitive?.content == "COMPLETE" && raw["supervisorTransportClosed"]?.jsonPrimitive?.booleanOrNull == true
        return CMakeTeardownReceipt(
            status = if (correct) CMakeTeardownStatus.COMPLETE else CMakeTeardownStatus.UNKNOWN,
            leaseId = owner.leaseId, ownerGeneration = owner.ownerGeneration, supervisorNonce = owner.nonce,
            environmentIdentity = owner.request.selection.environmentIdentity,
            directChildReaped = raw?.get("directChildReaped")?.jsonPrimitive?.booleanOrNull == true,
            groupEmpty = raw?.get("groupEmpty")?.jsonPrimitive?.booleanOrNull == true,
            outerChildReaped = reaped, transportClosed = transportClosed,
            forcedTermination = forcedOuter || raw?.get("forcedTermination")?.jsonPrimitive?.booleanOrNull != false,
            privateCleanupStatus = CMakePrivateCleanupStatus.NOT_STARTED, ready = if (correct) proof else expected,
            preReapScans = raw?.get("preReapScans")?.jsonPrimitive?.intOrNull ?: 0,
            minimumSeparationMs = raw?.get("minimumSeparationMs")?.jsonPrimitive?.longOrNull ?: 0,
            postReapGroupObservation = raw?.get("postReapGroupObservation")?.jsonPrimitive?.content ?: "UNKNOWN",
            reason = if (correct) null else "Missing or ambiguous source-correlated CMake group/outer/transport receipt",
        )
    }
}

internal object CMakeControlRecords {
    private val readyFields = setOf("nonce", "leaseId", "environmentIdentity", "ownerGeneration", "bootId", "namespace",
        "outerPid", "outerStartTicks", "supervisorPid", "supervisorStartTicks", "leaderPid", "leaderStartTicks")
    private val receiptFields = readyFields + setOf("status", "directChildReaped", "groupEmpty", "forcedTermination",
        "supervisorTransportClosed", "preReapScans", "minimumSeparationMs", "postReapGroupObservation", "exitCode", "reason")
    fun read(file: File): JsonObject {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..16_384)
        val bytes = CMakeSelectionInspector.readCache(file, 16_384)
        return strictObject(bytes)
    }
    fun strictObject(bytes: ByteArray): JsonObject {
        require(bytes.size in 1..16_384)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return LocalUbuntuCMakePolicy.strictObject(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(), 16_384)
    }
    fun requireReceiptShape(value: JsonObject) {
        require(value.keys == receiptFields || value.keys == receiptFields - "reason")
        parseReady(JsonObject(value.filterKeys { it in readyFields }))
        for (key in listOf("directChildReaped", "groupEmpty", "forcedTermination", "supervisorTransportClosed")) {
            val item = value[key] as? JsonPrimitive ?: error("Missing CMake receipt boolean")
            require(!item.isString && item.booleanOrNull != null)
        }
        for (key in listOf("preReapScans", "minimumSeparationMs")) {
            val item = value[key] as? JsonPrimitive ?: error("Missing CMake receipt integer")
            require(!item.isString && item.longOrNull != null && item.long >= 0)
        }
        for (key in listOf("status", "postReapGroupObservation")) require(value[key]?.jsonPrimitive?.isString == true)
        require(value["status"]?.jsonPrimitive?.content in setOf("COMPLETE", "UNKNOWN"))
        value["exitCode"]?.let { require(it == JsonNull || (it is JsonPrimitive && !it.isString && it.intOrNull != null)) }
        value["reason"]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 1024) }
    }
    fun parseReceiptReady(value: JsonObject): CMakeReadyProof {
        requireReceiptShape(value)
        return parseReady(JsonObject(value.filterKeys { it in readyFields }))
    }
    fun parseReady(value: JsonObject): CMakeReadyProof {
        require(value.keys == readyFields)
        fun text(key: String): String {
            val primitive = value[key] as? JsonPrimitive ?: error("Missing CMake READY field")
            require(primitive.isString)
            return primitive.content.also { require(it.length in 1..4096) }
        }
        val boot = text("bootId"); val namespace = text("namespace")
        require(boot.matches(Regex("[a-f0-9-]{36}")) && namespace.matches(Regex("pid:\\[[0-9]+\\]")))
        require(text("nonce").matches(Regex("[a-f0-9]{32}")) && text("leaseId").matches(Regex("[a-f0-9]{32}")))
        require(text("environmentIdentity").none(Char::isISOControl))
        require(value["ownerGeneration"]?.jsonPrimitive?.isString == false && requireNotNull(value["ownerGeneration"]?.jsonPrimitive?.longOrNull) >= 0)
        fun birth(prefix: String): CMakeProcessBirth {
            require(value["${prefix}Pid"]?.jsonPrimitive?.isString == false && value["${prefix}StartTicks"]?.jsonPrimitive?.isString == false)
            val pid = requireNotNull(value["${prefix}Pid"]?.jsonPrimitive?.intOrNull).also { require(it > 1) }
            val start = requireNotNull(value["${prefix}StartTicks"]?.jsonPrimitive?.longOrNull).also { require(it > 0) }
            return CMakeProcessBirth(pid, start, boot, namespace)
        }
        return CMakeReadyProof(text("nonce"), text("leaseId"), text("environmentIdentity"),
            requireNotNull(value["ownerGeneration"]?.jsonPrimitive?.longOrNull), birth("outer"), birth("supervisor"), birth("leader"))
    }
    fun readyJson(value: CMakeReadyProof) = buildJsonObject {
        put("nonce", value.nonce); put("leaseId", value.leaseId); put("environmentIdentity", value.environmentIdentity)
        put("ownerGeneration", value.ownerGeneration); put("bootId", value.outer.bootId); put("namespace", value.outer.namespace)
        listOf("outer" to value.outer, "supervisor" to value.supervisor, "leader" to value.leader).forEach { (prefix, birth) ->
            put("${prefix}Pid", birth.pid); put("${prefix}StartTicks", birth.startTicks)
        }
    }
    fun sameKernel(value: CMakeReadyProof): Boolean = runCatching {
        val boot = boundedText(File("/proc/sys/kernel/random/boot_id"), 128).trim()
        val namespace = Files.readSymbolicLink(File("/proc/self/ns/pid").toPath()).toString()
        listOf(value.outer, value.supervisor, value.leader).all { it.bootId == boot && it.namespace == namespace }
    }.getOrDefault(false)
    fun procStartTicks(pid: Int): Long {
        require(pid > 1)
        val value = boundedText(File("/proc/$pid/stat"), 4096)
        val suffix = value.substring(value.lastIndexOf(')') + 2).split(' ')
        require(suffix.size >= 20)
        return suffix[19].toLong()
    }
    private fun boundedText(file: File, maximum: Int): String = file.inputStream().use { stream ->
        val buffer = ByteArray(maximum + 1); var size = 0
        while (size < buffer.size) { val count = stream.read(buffer, size, buffer.size - size); if (count < 0) break; size += count }
        require(size <= maximum); String(buffer, 0, size, Charsets.UTF_8)
    }
}
