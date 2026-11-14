package com.baystudio.droide.core

import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

data class ElpOwnerRequest(val workspaceRoot: String, val rootPath: String, val workspaceLifetime: String,
    val ownerGeneration: Long, val observation: ElpSelectionInspection, val permit: ElpProjectAnalysisPermit)
data class ElpTeardownReceipt(val complete: Boolean, val directChildReaped: Boolean, val groupEmpty: Boolean,
    val birthCustodyVerified: Boolean, val factorySettled: Boolean, val writersSettled: Boolean,
    val readersSettled: Boolean, val closeAttemptsSettled: Boolean, val privateCleanupVerified: Boolean,
    val reason: String? = null) {
    val settled get() = directChildReaped && groupEmpty && birthCustodyVerified && factorySettled &&
        writersSettled && readersSettled && closeAttemptsSettled
    companion object { fun unknown(reason: String) = ElpTeardownReceipt(false, false, false, false, false, false, false, false, false, reason) }
}
interface ElpPreparedOwner: Closeable {
    val identity: ElpManagedLaunchIdentity
    val request: ElpOwnerRequest
    val token: String
    val isCurrent: Boolean
    val runtimeEvidence: ElpRuntimeEvidence
    fun fence()
    suspend fun retire(deadline: ElpDeadline): ElpTeardownReceipt
}
interface ElpHostedProcess: HostedStdioProcess {
    val owner: ElpPreparedOwner
    suspend fun stopAndAwaitOwnedGroup(deadline: ElpDeadline): ElpTeardownReceipt
}
data class ElpStageHealthResult(val healthPassed: Boolean, val cleanup: ElpTeardownReceipt, val evidence: String)

/** Cancellation never discards a blocking native factory/pipe/close attempt. */
internal class ElpIoAttempt<T>(name: String, action: () -> T) {
    @Volatile var complete = false; private set
    @Volatile var failure: Throwable? = null; private set
    private var value: T? = null
    init { Thread({ try { value = action() } catch (error: Throwable) { failure = error } finally { complete = true } },
        "droide-elp-$name").apply { isDaemon = true; start() } }
    val succeeded get() = complete && failure == null
    @Suppress("UNCHECKED_CAST") fun result(): T { check(complete); failure?.let { throw it }; return value as T }
    suspend fun await(deadline: ElpDeadline): Boolean { while (!complete && !deadline.expired) delay(10); return complete }
}

/** All writes enqueue owned copies. A blocked native pipe never owns the synchronous fence. */
internal class ElpAsyncWriter(private val output: OutputStream, private val fail: () -> Unit) {
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queued = 0
    private var accepting = true
    private var ending = false
    @Volatile var settled = false; private set
    @Volatile var failure: Throwable? = null; private set
    init { Thread({
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (queue.isEmpty() && accepting && !ending) lock.wait()
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: break
                output.write(next); output.flush(); synchronized(lock) { queued -= next.size }
            }
            if (ending) output.close()
        } catch (error: Throwable) { failure = error; fail() } finally { settled = true }
    }, "droide-elp-writer").apply { isDaemon = true; start() } }
    fun offer(bytes: ByteArray, off: Int, length: Int) = synchronized(lock) {
        require(off >= 0 && length >= 0 && off <= bytes.size - length)
        check(accepting && !ending && failure == null)
        require(length <= ElpResourcePolicy.FRAME_BYTES_MAX + 8192 && queued + length <= ElpResourcePolicy.QUEUED_BYTES_MAX && queue.size < 64)
        if (length > 0) { queue += bytes.copyOfRange(off, off + length); queued += length; lock.notifyAll() }
    }
    fun finishInput() = synchronized(lock) { ending = true; lock.notifyAll() }
    fun fence() = synchronized(lock) { accepting = false; queue.clear(); lock.notifyAll() }
}

/** Bounded raw byte delivery; native LSP frames are neither rewritten nor reinterpreted as shell text. */
internal class ElpAsyncInput(private val input: InputStream, private val maximum: Int, private val tail: Boolean,
    private val fail: () -> Unit): InputStream() {
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<ByteArray>()
    private var queued = 0
    private var current: ByteArray? = null
    private var offset = 0
    private var ended = false
    @Volatile var settled = false; private set
    @Volatile var failure: Throwable? = null; private set
    @Volatile var totalRead = 0L; private set
    private var retainedTail = byteArrayOf()
    fun tailBytes(): ByteArray = synchronized(lock) { retainedTail.copyOf() }
    init { Thread({
        try {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break; require(count > 0)
                synchronized(lock) {
                    totalRead = Math.addExact(totalRead, count.toLong())
                    if (tail) retainedTail = (retainedTail + buffer.copyOf(count)).takeLast(maximum).toByteArray()
                    if (!ended) {
                        if (tail) while (queue.isNotEmpty() && queued + count > maximum) queued -= queue.removeFirst().size
                        require(queued + count <= maximum) { "ELP output queue overflow" }
                        queue += buffer.copyOf(count); queued += count; lock.notifyAll()
                    }
                }
            }
        } catch (error: Throwable) { failure = error; fail() } finally {
            settled = true; synchronized(lock) { ended = true; lock.notifyAll() }
        }
    }, "droide-elp-reader").apply { isDaemon = true; start() } }
    override fun read(): Int { val byte = ByteArray(1); return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255 }
    override fun read(bytes: ByteArray, off: Int, length: Int): Int = synchronized(lock) {
        require(off >= 0 && length >= 0 && off <= bytes.size - length); if (length == 0) return@synchronized 0
        while (current == null) {
            if (queue.isNotEmpty()) { current = queue.removeFirst(); offset = 0; break }
            if (ended) { failure?.let { throw IOException("ELP pipe failed", it) }; return@synchronized -1 }
            lock.wait()
        }
        val value = requireNotNull(current); val count = minOf(length, value.size - offset)
        value.copyInto(bytes, off, offset, offset + count); offset += count; queued -= count
        if (offset == value.size) current = null
        count
    }
    override fun close() = synchronized(lock) { ended = true; queue.clear(); current = null; queued = 0; lock.notifyAll() }
}

/** One process group and one private tree, retained until every blocking operation settles. */
internal class ElpExecutionOwner private constructor(val localRoot: File, val guestRoot: String, val token: String,
    val purpose: String, private val current: () -> Boolean, private val operationDeadline: ElpDeadline,
    private val admissionCurrent: () -> Boolean, private val release: (ElpTeardownReceipt) -> Unit) {
    val privateIdentity = GraphqlWire.directoryIdentity(localRoot)
    private val parentIdentity = GraphqlWire.canonicalDirectory(requireNotNull(localRoot.parentFile))
    private val currentFlag = AtomicBoolean(true)
    private val claimed = AtomicBoolean(false)
    private val creationAdmitted = AtomicBoolean(false)
    private val process = AtomicReference<ElpOwnedNativeProcess?>()
    private val retirement = Mutex()
    private val operations = CopyOnWriteArrayList<ElpIoAttempt<*>>()
    private val retained = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifetime = ElpDeadline.after(ElpResourcePolicy.SESSION_MS)
    private var factory: Deferred<Unit>? = null
    @Volatile private var factorySettled = true
    @Volatile private var factoryFailure: Throwable? = null
    @Volatile private var completed: ElpTeardownReceipt? = null
    @Volatile var hostBirth: GraphqlWire.ProcIdentity? = null
    val isCurrent get() = currentFlag.get() && !lifetime.expired && current()
    fun fence() { currentFlag.set(false); process.get()?.fence() }
    fun verifyPrivate() {
        require(GraphqlWire.canonicalDirectory(requireNotNull(localRoot.parentFile)) == parentIdentity)
        require(GraphqlWire.directoryIdentity(localRoot) == privateIdentity)
        require(GraphqlWire.readBytes(File(localRoot, "custody.json"), 16_384).toString(Charsets.UTF_8) == custodyText())
    }
    private fun custodyText() = buildJsonObject { put("version", 1); put("token", token); put("purpose", purpose); put("directoryIdentity", privateIdentity) }.toString()
    fun write(name: String, bytes: ByteArray, executable: Boolean = false) {
        require(name in setOf("runtime-report.escript", "metadata-observer.py", "shim/rebar3"))
        verifyPrivate(); GraphqlWire.write(File(localRoot, name), bytes)
        if (executable) Files.setPosixFilePermissions(File(localRoot, name).toPath(), PosixFilePermissions.fromString("rwx------"))
    }
    fun shellCommand(command: List<String>): String = GraphqlWire.shellCommand(localRoot, token, command)
    fun <T> operation(name: String, action: () -> T): ElpIoAttempt<T> {
        operations.removeAll { it.complete }; require(operations.size < 8)
        return ElpIoAttempt(name, action).also { operations += it }
    }
    fun beginFactory(block: suspend () -> ElpOwnedNativeProcess) {
        check(isCurrent && claimed.compareAndSet(false, true)); factorySettled = false
        factory = retained.async {
            try { val result = block(); check(result === process.get()); if (!isCurrent) result.killLate() }
            catch (error: Throwable) { factoryFailure = error; fence() }
            finally { factorySettled = true }
            if (!isCurrent) retire(ElpDeadline.retirement())
        }
    }
    fun create(factory: () -> Process): ElpOwnedNativeProcess {
        check(isCurrent)
        ElpGenerationAdmission.withAdmission {
            // Only atomic state/token reads belong in this tiny gate. Native creation can block outside it.
            check(currentFlag.get() && !lifetime.expired && admissionCurrent() &&
                process.get() == null && creationAdmitted.compareAndSet(false, true))
        }
        val raw = factory()
        val hosted = ElpOwnedNativeProcess(raw, this, retained)
        check(process.compareAndSet(null, hosted)); ExitHostedProcessRegistry.retain(hosted)
        if (!isCurrent) hosted.killLate()
        return hosted
    }
    suspend fun awaitFactory(deadline: ElpDeadline): ElpOwnedNativeProcess {
        while (!factorySettled && !deadline.expired) delay(10)
        deadline.check(); factoryFailure?.let { throw it }; check(isCurrent)
        return requireNotNull(process.get())
    }
    suspend fun awaitBirth(deadline: ElpDeadline) {
        while (true) {
            deadline.check(); check(isCurrent); verifyPrivate()
            val record = File(localRoot, "host.json")
            if (record.isFile) {
                val data = GraphqlWire.strict(GraphqlWire.readBytes(record, 16_384))
                require(data["token"] == JsonPrimitive(token)); val birth = GraphqlWire.recordedBirth(data)
                val now = ElpProcfs.currentOrGone(birth.pid)
                if (now != null) require(now.start == birth.start && now.group == birth.group)
                else require(process.get()?.isAlive == false && ElpProcfs.snapshot(birth.group, token, deadline).owned.isEmpty()) {
                    "ELP birth disappeared before direct process completion"
                }
                hostBirth = birth; return
            }
            check(process.get()?.isAlive == true); delay(10)
        }
    }
    fun beginMonitoring() {
        retained.launch {
            while (isCurrent) {
                try {
                    val sample = operation("resource-sample") {
                        process.get()?.observe()
                        ElpPrivateTree.inspect(localRoot, privateIdentity, false, ElpDeadline.after(200))
                    }
                    check(sample.await(ElpDeadline.after(1000))) { "ELP resource observation stalled" }
                    sample.result()
                } catch (_: Throwable) { fence() }
                delay(ElpResourcePolicy.MONITOR_MS)
            }
            retire(if (operationDeadline.expired) ElpDeadline.retirement() else operationDeadline)
        }
    }
    fun close() { fence(); retained.launch { retire(ElpDeadline.retirement()) } }
    suspend fun retire(deadline: ElpDeadline): ElpTeardownReceipt = withContext(NonCancellable + Dispatchers.IO) {
        fence()
        while (!retirement.tryLock()) { if (deadline.expired) return@withContext ElpTeardownReceipt.unknown("ELP cleanup is still owned"); delay(10) }
        try {
            completed?.let { return@withContext it }
            val raw = process.get()
            val stopped = if (raw == null) ElpTeardownReceipt(false, true, true, !claimed.get(), factorySettled, true, true, true, false)
                else runCatching { raw.stop(deadline, factorySettled) }.getOrElse { ElpTeardownReceipt.unknown("ELP group/pipe settlement failed") }
            while (operations.any { !it.complete } && !deadline.reserve(250).expired) delay(10)
            val operationsSettled = operations.all { it.complete }
            val canRemove = stopped.settled && factorySettled && operationsSettled && !deadline.expired
            val removed = canRemove && runCatching {
                verifyPrivate()
                val cleanupBudget = deadline.limited(2000)
                ElpPrivateTree.inspect(localRoot, privateIdentity, false, cleanupBudget)
                ElpPrivateTree.inspect(localRoot, privateIdentity, true, cleanupBudget); true
            }.getOrDefault(false)
            val receipt = stopped.copy(complete = canRemove && removed, factorySettled = factorySettled,
                privateCleanupVerified = removed, readersSettled = stopped.readersSettled && operationsSettled, reason = if (removed) null else "ELP exact owner retained; cleanup UNKNOWN")
            if (receipt.complete) { release(receipt); completed = receipt; ElpOwnerCustody.release(this@ElpExecutionOwner) }
            receipt
        } finally { retirement.unlock() }
    }
    companion object {
        fun allocateCapture(appRoot: File, purpose: String, current: () -> Boolean, deadline: ElpDeadline,
            acquireActivity: (() -> ElpPackageActivityHold)?): ElpExecutionOwner {
            deadline.check(); check(current())
            val activity = acquireActivity?.invoke()
            // A throwing allocation may already own a private directory. Only its exact receipt releases this lease.
            return allocate(appRoot, purpose, current, deadline, release = { activity?.release(it) })
        }
        fun allocate(appRoot: File, purpose: String, current: () -> Boolean, deadline: ElpDeadline,
            admissionCurrent: () -> Boolean = { true }, release: (ElpTeardownReceipt) -> Unit = {}): ElpExecutionOwner {
            deadline.check(); require(purpose.length in 1..4096 && current())
            require(appRoot.canonicalFile == appRoot.absoluteFile && appRoot.isDirectory)
            val parent = File(appRoot, "runtime-tmp")
            if (!Files.exists(parent.toPath(), NOFOLLOW_LINKS))
                Files.createDirectory(parent.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(parent.canonicalFile == parent.absoluteFile && Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS))
            val token = UUID.randomUUID().toString().replace("-", "")
            val root = File(parent, if (purpose.startsWith("health:")) "droide-elp-health-$token" else "droide-elp-$token")
            Files.createDirectory(root.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val owner = ElpExecutionOwner(root, "/run/droide-elp/${root.name}", token, purpose, current, deadline, admissionCurrent, release)
            GraphqlWire.write(File(root, "custody.json"), owner.custodyText().toByteArray())
            ElpOwnerCustody.claim(owner)
            for (name in listOf("tmp", "work", "home", "logs", "shim", "config", "cache", "data", "state", "run"))
                Files.createDirectory(File(root, name).toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            return owner
        }
    }
}

/** A dispatcher-return cancellation must not discard an owner that the caller never received. */
internal object ElpPreparedDelivery {
    suspend fun <T : ElpPreparedOwner> deliver(deadline: ElpDeadline, prepare: suspend () -> T): T {
        val captured = AtomicReference<T?>()
        try {
            return withContext(Dispatchers.IO) { prepare().also { captured.set(it) } }
        } catch (failure: Throwable) {
            captured.get()?.let { exact ->
                exact.fence()
                withContext(NonCancellable + Dispatchers.IO) {
                    try { exact.retire(deadline) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
            }
            throw failure
        }
    }
}

internal class LocalElpPreparedOwner(override val identity: ElpManagedLaunchIdentity,
    override val request: ElpOwnerRequest, override val runtimeEvidence: ElpRuntimeEvidence,
    internal val execution: ElpExecutionOwner, internal val launchSpec: TerminalLaunchSpec,
    internal val preflightDeadline: ElpDeadline): ElpPreparedOwner {
    override val token get() = execution.token
    override val isCurrent get() = execution.isCurrent && request.permit.isCurrent
    override fun fence() = execution.fence()
    override fun close() = execution.close()
    override suspend fun retire(deadline: ElpDeadline) = execution.retire(deadline)
}
internal class LocalElpHostedProcess(private val native: ElpOwnedNativeProcess,
    override val owner: LocalElpPreparedOwner): ElpHostedProcess, HostedStdioProcess by native {
    internal fun nativeProcess() = native
    override fun close() = owner.close()
    override suspend fun stopAndAwaitOwnedGroup(deadline: ElpDeadline) = owner.retire(deadline)
}

internal class ElpOwnedNativeProcess(private val process: Process, private val owner: ElpExecutionOwner,
    private val retained: CoroutineScope): HostedStdioProcess {
    private val attempts = CopyOnWriteArrayList<ElpIoAttempt<*>>()
    private val stopping = AtomicBoolean(false)
    private val lateKill = AtomicBoolean(false)
    private val observed = linkedMapOf<Int, GraphqlWire.ProcIdentity>()
    private val writer = ElpAsyncWriter(process.outputStream) { owner.fence() }
    private val output = ElpAsyncInput(process.inputStream, ElpResourcePolicy.QUEUED_BYTES_MAX, false) { owner.fence() }
    private val errors = ElpAsyncInput(process.errorStream, ElpResourcePolicy.STDERR_BYTES_MAX, true) { owner.fence() }
    override val stdin = object: OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(bytes: ByteArray, off: Int, len: Int) { check(owner.isCurrent); writer.offer(bytes, off, len) }
        override fun flush() { check(owner.isCurrent) }
        override fun close() = writer.finishInput()
    }
    override val stdout: InputStream get() = output
    override val stderr: InputStream get() = errors
    override val isAlive get() = process.isAlive
    override val exitCode get() = runCatching { process.exitValue() }.getOrNull()
    override suspend fun awaitExit(): Int { while (process.isAlive) delay(10); return process.exitValue() }
    suspend fun awaitOutputSettlement(deadline: ElpDeadline) {
        while (!output.settled || !errors.settled) { deadline.check(); delay(10) }
        check(output.failure == null && errors.failure == null) { "ELP output pipe did not end cleanly" }
    }
    override fun close() = owner.close()
    fun fence() { writer.fence() }
    private fun <T> attempt(name: String, action: () -> T): ElpIoAttempt<T> {
        require(attempts.size < 20); return ElpIoAttempt(name, action).also { attempts += it }
    }
    val stderrBytes: Long get() = errors.totalRead
    fun stderrTail(): ByteArray = errors.tailBytes()
    fun killLate() {
        owner.fence()
        if (lateKill.compareAndSet(false, true)) {
            attempt("late-kill") { process.destroyForcibly() }; attempt("late-reap") { process.waitFor() }
        }
    }
    fun observe() {
        val birth = requireNotNull(owner.hostBirth)
        val sample = ElpProcfs.snapshot(birth.group, owner.token, ElpDeadline.after(1000))
        val group = sample.owned
        require(group.size <= ElpResourcePolicy.GROUP_MEMBERS_MAX && group.sumOf { it.rss } <= ElpResourcePolicy.RSS_BYTES_MAX)
        if (process.isAlive) require(group.any { it.pid == birth.pid && it.start == birth.start })
        val previous = synchronized(observed) {
            for (entry in group) {
                val prior = observed[entry.pid]; require(prior == null || prior.start == entry.start); observed[entry.pid] = entry
            }
            observed.values.toList()
        }
        require(sample.escaped.isEmpty()) { "Token-bearing ELP descendant escaped its owned group; custody UNKNOWN" }
        for (entry in previous) {
            val now = ElpProcfs.currentOrGone(entry.pid)
            require(now == null || now.start != entry.start || now.group == birth.group) { "Observed ELP child escaped owned group" }
        }
    }
    suspend fun stop(deadline: ElpDeadline, factorySettled: Boolean): ElpTeardownReceipt {
        owner.fence(); output.close(); errors.close()
        if (stopping.compareAndSet(false, true)) {
            attempt("stdin-close") { process.outputStream.close() }
            attempt("reap") { process.waitFor() }
            val birth = owner.hostBirth ?: runCatching {
                val record = GraphqlWire.strict(GraphqlWire.readBytes(File(owner.localRoot, "host.json"), 16_384))
                require(record["token"] == JsonPrimitive(owner.token)); GraphqlWire.recordedBirth(record)
            }.getOrNull()
            if (birth != null) {
                owner.hostBirth = birth
                attempt("group-stop") {
                    for (signal in listOf(15, 9)) {
                        val group = ElpProcfs.snapshot(birth.group, owner.token, deadline).owned.filter { it.group == birth.group }; if (group.isEmpty()) break
                        require(group.singleOrNull { it.pid == birth.pid }?.start == birth.start)
                        GraphqlWire.signalGroup(birth, signal)
                        if (signal == 15 && !deadline.expired) Thread.sleep(minOf(200, deadline.remainingMillis()))
                    }
                }
            }
            if (process.isAlive) attempt("direct-kill") { process.destroyForcibly() }
            attempt("stdout-close") { process.inputStream.close() }
            attempt("stderr-close") { process.errorStream.close() }
        }
        while (!deadline.reserve(250).expired && (attempts.any { !it.complete } || !writer.settled || !output.settled || !errors.settled)) delay(10)
        val reaped = !process.isAlive && runCatching { process.waitFor(0, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val birth = owner.hostBirth
        var empty = false; var escapedGone = false
        if (birth != null && !deadline.expired) {
            val finalSample = attempt("final-custody") {
                val sample = ElpProcfs.snapshot(birth.group, owner.token, deadline)
                val old = synchronized(observed) { observed.values.toList() }
                sample.owned.isEmpty() to old.all { entry ->
                    val now = ElpProcfs.currentOrGone(entry.pid); now == null || now.start != entry.start
                }
            }
            if (finalSample.await(deadline.reserve(100)) && finalSample.succeeded) {
                val observedEnd = finalSample.result(); empty = observedEnd.first; escapedGone = observedEnd.second
            }
        }
        val custody = birth != null && empty && escapedGone && attempts.all { it.succeeded }
        return ElpTeardownReceipt(false, reaped, empty, custody, factorySettled, writer.settled,
            output.settled && errors.settled, attempts.all { it.succeeded }, false)
    }
}

internal object ElpPrivateTree {
    fun inspect(root: File, identity: String, remove: Boolean, deadline: ElpDeadline) {
        require(GraphqlWire.directoryIdentity(root) == identity)
        var count = 0; var bytes = 0L
        fun visit(file: File, depth: Int) {
            deadline.check(); require(++count <= ElpResourcePolicy.PRIVATE_ENTRIES_MAX && depth <= 32)
            val attributes = Files.readAttributes(file.toPath(), "unix:mode,size", NOFOLLOW_LINKS)
            val type = (attributes.getValue("mode") as Number).toInt() and 0xf000
            require(type in setOf(0x4000, 0x8000)) { "Unresolved private-tree link or special file" }
            bytes += (attributes.getValue("size") as Number).toLong(); require(bytes <= ElpResourcePolicy.PRIVATE_BYTES_MAX)
            if (type == 0x4000) Files.newDirectoryStream(file.toPath()).use { children -> children.forEach { visit(it.toFile(), depth + 1) } }
            if (remove) { deadline.check(); Files.delete(file.toPath()) }
        }
        visit(root, 0)
    }
}

/** Strong current-process ownership plus durable exact directories. No historical sweep is permitted. */
internal object ElpOwnerCustody {
    private val owners = CopyOnWriteArrayList<ElpExecutionOwner>()
    fun claim(owner: ElpExecutionOwner) {
        synchronized(owners) {
            require(owners.size < 64 && owners.none { it.purpose == owner.purpose }) { "ELP prior exact owner still has custody" }
            Files.newDirectoryStream(requireNotNull(owner.localRoot.parentFile).toPath()).use { entries ->
                var count = 0
                for (entry in entries) {
                    require(++count <= 16_384)
                    if (entry == owner.localRoot.toPath() || !entry.fileName.toString().startsWith("droide-elp-")) continue
                    require(!Files.isSymbolicLink(entry))
                    val record = GraphqlWire.strict(GraphqlWire.readBytes(entry.resolve("custody.json").toFile(), 16_384))
                    require(record["purpose"] != JsonPrimitive(owner.purpose)) { "Historical ELP owner requires verified cleanup" }
                }
            }
            owners += owner
        }
    }
    fun release(owner: ElpExecutionOwner) { owners.remove(owner) }
    fun requireNoCustody(appRoot: String) {
        require(owners.isEmpty()) { "ELP owner still retains process or I/O custody" }
        val parent = File(appRoot, "runtime-tmp"); if (!parent.exists()) return
        require(parent.canonicalFile == parent.absoluteFile)
        Files.newDirectoryStream(parent.toPath()).use { entries ->
            var count = 0
            for (entry in entries) { require(++count <= 16_384); require(!entry.fileName.toString().startsWith("droide-elp-")) { "Historical ELP custody requires explicit repair" } }
        }
    }
}


/** Bounded kernel metadata sampling; unrelated processes are never signalled or treated as owned. */
internal object ElpProcfs {
    data class Snapshot(val owned: List<GraphqlWire.ProcIdentity>, val escaped: List<GraphqlWire.ProcIdentity>)
    fun currentOrGone(pid: Int): GraphqlWire.ProcIdentity? = try { GraphqlWire.proc(pid) }
        catch (failure: FileNotFoundException) { if (File("/proc/$pid").exists()) throw failure else null }
        catch (failure: java.nio.file.NoSuchFileException) { if (File("/proc/$pid").exists()) throw failure else null }
    private fun bytes(file: File, maximum: Int, deadline: ElpDeadline): ByteArray = file.inputStream().use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { deadline.check(); val count = input.read(buffer); if (count < 0) break
            require(output.size() + count <= maximum); output.write(buffer, 0, count) }
        output.toByteArray()
    }
    fun snapshot(group: Int, token: String, deadline: ElpDeadline): Snapshot {
        require(group > 1 && token.matches(Regex("[0-9a-f]{32}")))
        val self = File("/proc/self").canonicalFile
        val uid = (Files.getAttribute(self.toPath(), "unix:uid") as Number).toLong()
        val owned = mutableListOf<GraphqlWire.ProcIdentity>(); var scanned = 0; var environmentBytes = 0
        Files.newDirectoryStream(File("/proc").toPath()).use { entries ->
            for (entry in entries) {
                deadline.check(); require(++scanned <= ElpResourcePolicy.PROC_SCAN_MAX)
                val pid = entry.fileName.toString().toIntOrNull()?.takeIf { it > 1 } ?: continue
                try {
                    val identity = currentOrGone(pid) ?: continue
                    val sameUid = (Files.getAttribute(entry, "unix:uid") as Number).toLong() == uid
                    val hasToken = if (sameUid && identity.state != "Z") {
                        val value = bytes(entry.resolve("environ").toFile(), 1_048_576, deadline)
                        environmentBytes = Math.addExact(environmentBytes, value.size); require(environmentBytes <= 8_388_608)
                        value.toString(Charsets.ISO_8859_1).split('\u0000').any { it == "DROIDE_ELP_OWNER_TOKEN=$token" }
                    } else false
                    if (identity.group == group || hasToken) owned += identity
                    require(owned.size <= ElpResourcePolicy.GROUP_MEMBERS_MAX)
                } catch (failure: FileNotFoundException) { if (Files.exists(entry)) throw failure }
                catch (failure: java.nio.file.NoSuchFileException) { if (Files.exists(entry)) throw failure }
            }
        }
        return Snapshot(owned.toList(), owned.filter { it.group != group })
    }
}
