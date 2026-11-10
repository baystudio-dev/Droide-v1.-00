package com.baystudio.droide.core

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

// It never participates in project Git or SAF sync.
data class DebugPersistentState(
    val breakpoints: Map<String, List<Int>>,
    val watches: List<String>,
)

data class DebugPersistenceStatus(val writable: Boolean, val message: String? = null, val retrying: Boolean = false)

internal class DebugStateUnavailableException(cause: Throwable? = null) : IllegalStateException(
    "Saved debugger state could not be restored. Automatic writes are paused. Retry in Run & Debug.", cause,
)

class DebugStateStore internal constructor(
    private val root: File,
    projectId: String,
    private val encrypt: (ByteArray, ByteArray) -> ByteArray,
    private val decrypt: (ByteArray, ByteArray) -> ByteArray,
) {
    constructor(context: Context, projectId: String) : this(
        File(context.applicationContext.filesDir, ".droide/debug-state"), projectId, KeystoreBlobCipher("droide_debug_state_v1"),
    )

    private constructor(root: File, projectId: String, cipher: KeystoreBlobCipher) :
        this(root, projectId, cipher::encrypt, cipher::decrypt)

    private val safeProjectId = PathSecurity.safeLeafName(projectId)
    private val aad = safeProjectId.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(root, "$safeProjectId.bin")
    private val gate = gateFor(file.absoluteFile.toPath().normalize().toString())
    internal val status: StateFlow<DebugPersistenceStatus> = gate.status.asStateFlow()

    suspend fun load(): DebugPersistentState? = restore(retry = false) {}

    internal suspend fun restore(retry: Boolean, accept: (DebugPersistentState?) -> Unit): DebugPersistentState? =
        withContext(Dispatchers.IO) { gate.io.withLock { readLocked(retry, accept) } }

    internal fun <T> withEditableState(action: () -> T): T = synchronized(gate.admission) {
        check(gate.loaded && !gate.reading && gate.failure == null) {
            gate.status.value.message ?: "Saved debugger state is still loading. Try again shortly."
        }
        action()
    }

    private fun readLocked(retry: Boolean, accept: (DebugPersistentState?) -> Unit): DebugPersistentState? {
        synchronized(gate.admission) {
            if (!retry) gate.failure?.let { throw it }
            gate.reading = true
            gate.publishStatus()
        }
        try {
            val saved = readState()
            accept(saved)
            synchronized(gate.admission) { gate.loaded = true; gate.failure = null }
            return saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val unavailable = failure as? DebugStateUnavailableException ?: DebugStateUnavailableException(failure)
            synchronized(gate.admission) { gate.failure = unavailable }
            if (failure is Error) throw failure
            throw unavailable
        } finally {
            synchronized(gate.admission) { gate.reading = false; gate.publishStatus() }
        }
    }

    private fun readState(): DebugPersistentState? {
        val attributes = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (missing: NoSuchFileException) {
            check(!gate.observedFile) { "Previously observed debugger state is missing" }
            return null
        }
        gate.observedFile = true
        require(attributes.isRegularFile && attributes.size() in 1..MAX_FILE_BYTES) { "Invalid debugger state file" }
        val expectedSize = attributes.size().toInt()
        val bytes = ByteArray(expectedSize + 1)
        var count = 0
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) {
                    val next = input.read()
                    if (next < 0) break
                    bytes[count++] = next.toByte()
                } else count += read
            }
        }
        require(count == expectedSize) { "Debug state size changed while reading" }
        val plain = decrypt(bytes.copyOf(count), aad)
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Debug state too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
        val obj = json.parseToJsonElement(text) as? JsonObject ?: error("Debug state is not an object")
        val version = obj["version"] as? JsonPrimitive
        require(version != null && !version.isString && version.intOrNull == VERSION) { "Unsupported debug state version" }
        val bpObj = obj["breakpoints"] as? JsonObject ?: error("Invalid stored breakpoints")
        require(bpObj.size <= MAX_BREAKPOINT_FILES) { "Too many stored breakpoint files" }
        val breakpoints = linkedMapOf<String, List<Int>>()
        var total = 0
        for ((path, value) in bpObj) {
            require(validPath(path)) { "Invalid stored breakpoint path" }
            val raw = value as? JsonArray ?: error("Invalid stored breakpoint lines")
            require(raw.size <= MAX_BREAKPOINTS_PER_FILE) { "Too many stored breakpoints" }
            val lines = raw.map {
                val primitive = it as? JsonPrimitive
                val line = primitive?.takeUnless { value -> value.isString }?.intOrNull
                require(line != null && line in 1..MAX_LINE) { "Invalid stored breakpoint line" }
                line
            }.distinct().sorted()
            if (lines.isNotEmpty()) {
                total += lines.size
                require(total <= MAX_BREAKPOINTS_TOTAL) { "Too many stored breakpoints" }
                breakpoints[path] = lines
            }
        }
        val rawWatches = obj["watches"] as? JsonArray ?: error("Invalid stored watch expressions")
        require(rawWatches.size <= MAX_WATCHES) { "Too many stored watch expressions" }
        val watches = rawWatches.map {
            val expression = (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            require(expression != null && expression.isNotBlank() && expression.length <= MAX_WATCH_LENGTH) { "Invalid stored watch expression" }
            expression.trim()
        }.distinct()
        return DebugPersistentState(breakpoints, watches)
    }

    internal class PreparedSave internal constructor(
        internal val owner: Any,
        internal val ticket: Any,
        internal val state: DebugPersistentState,
    )

    internal fun prepareSave(state: DebugPersistentState): PreparedSave = synchronized(gate.admission) {
        gate.failure?.let { throw it }
        val frozen = DebugPersistentState(state.breakpoints.mapValues { (_, lines) -> lines.toList() }, state.watches.toList())
        val ticket = Any()
        gate.latestSave = ticket
        PreparedSave(gate, ticket, frozen)
    }

    suspend fun save(state: DebugPersistentState) = savePrepared(prepareSave(state))

    internal suspend fun savePrepared(request: PreparedSave) = withContext(Dispatchers.IO) {
        gate.io.withLock {
            require(request.owner === gate) { "Debug state save belongs to another project" }
            if (!isCurrentSave(request)) return@withLock
            if (!gate.loaded) readLocked(retry = false) {}
            if (isCurrentSave(request)) saveLocked(request.state)
        }
    }

    private fun isCurrentSave(request: PreparedSave): Boolean = synchronized(gate.admission) {
        if (request.ticket !== gate.latestSave) false
        else { gate.failure?.let { throw it }; true }
    }

    private fun saveLocked(state: DebugPersistentState) {
        var total = 0
        val bp = buildJsonObject {
            for ((path, rawLines) in state.breakpoints.entries.take(MAX_BREAKPOINT_FILES)) {
                if (!validPath(path)) continue
                val lines = rawLines.filter { it in 1..MAX_LINE }.distinct().sorted().take(MAX_BREAKPOINTS_PER_FILE)
                if (lines.isEmpty()) continue
                total += lines.size
                require(total <= MAX_BREAKPOINTS_TOTAL) { "Too many breakpoints" }
                put(path, buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
            }
        }
        val watches = state.watches.asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() }
            .distinct().take(MAX_WATCHES).toList()
        require(watches.all { it.length <= MAX_WATCH_LENGTH }) { "Watch expression too large" }
        val obj = buildJsonObject {
            put("version", VERSION)
            put("breakpoints", bp)
            put("watches", buildJsonArray { watches.forEach { add(JsonPrimitive(it)) } })
        }
        val plain = obj.toString().toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Debug state too large" }
        val encrypted = encrypt(plain, aad)
        require(encrypted.size <= MAX_FILE_BYTES) { "Encrypted debug state too large" }
        synchronized(gate.admission) { gate.failure?.let { throw it } }
        atomicWrite(encrypted)
        gate.observedFile = true
    }

    private fun validPath(path: String): Boolean {
        if (path.isBlank() || path.length > 1_024 || File(path).isAbsolute || '\u0000' in path) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    private fun atomicWrite(bytes: ByteArray) {
        root.mkdirs()
        val tmp = File(root, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private class Gate {
        val admission = Any()
        val io = Mutex()
        var latestSave: Any? = null
        var loaded = false
        var observedFile = false
        var reading = false
        var failure: DebugStateUnavailableException? = null
        val status = MutableStateFlow(DebugPersistenceStatus(false, "Loading saved debugger state…"))
        fun publishStatus() {
            status.value = DebugPersistenceStatus(loaded && !reading && failure == null,
                failure?.message ?: if (reading || !loaded) "Loading saved debugger state…" else null, reading)
        }
    }

    private companion object {
        private val gates = mutableMapOf<String, WeakReference<Gate>>()
        private fun gateFor(path: String): Gate = synchronized(gates) {
            gates.entries.removeAll { it.value.get() == null }
            gates[path]?.get() ?: Gate().also { gates[path] = WeakReference(it) }
        }

        const val VERSION = 1
        const val MAX_BREAKPOINT_FILES = 256
        const val MAX_BREAKPOINTS_PER_FILE = 256
        const val MAX_BREAKPOINTS_TOTAL = 2_000
        const val MAX_LINE = 10_000_000
        const val MAX_WATCHES = 200
        const val MAX_WATCH_LENGTH = 20_000
        const val MAX_PLAINTEXT_BYTES = 1_000_000
        const val MAX_FILE_BYTES = 1_100_000L
    }
}
