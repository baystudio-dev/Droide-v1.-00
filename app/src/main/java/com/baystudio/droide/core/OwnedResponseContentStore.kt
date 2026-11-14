package com.baystudio.droide.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** App-private immutable response blobs. Not a provider parser or tool execution authorization.
 *
 * The host must supply a private, trusted root and an authorized Owner. Provider strings are
 * never paths. NOFOLLOW, random names and root-identity checks reduce accidental traversal;
 * portable JVM APIs do NOT protect against a hostile same-UID process racing directory changes.
 * A completed blob means the caller supplied validated protocol completion, not that arbitrary
 * JSON is executable. Tool admission and its exactly-once journal remain separate responsibilities.
 */
internal class OwnedResponseContentStore(
    root: Path,
    private val admission: DiskAdmission,
    private val inlineThreshold: Int = 64 * 1024,
    private val cancelled: () -> Boolean = { false },
    private val channelWrite: (FileChannel, ByteBuffer) -> Int = { channel, bytes -> channel.write(bytes) },
) {
    interface DiskAdmission {
        /** Admission must precede every allocation/write; lease covers the in-flight write. */
        fun reserve(bytes: Long): AutoCloseable
    }

    /** Share ONE instance across stores/writers using the same filesystem. No logical output cap. */
    class HeadroomAdmission(private val freeBytes: () -> Long, private val headroomBytes: Long) : DiskAdmission {
        private var reserved = 0L
        init { require(headroomBytes >= 0) }
        @Synchronized override fun reserve(bytes: Long): AutoCloseable {
            require(bytes >= 0)
            val usable = (freeBytes() - headroomBytes).coerceAtLeast(0)
            if (bytes > usable - reserved) throw IOException("Insufficient temporary storage headroom")
            reserved = Math.addExact(reserved, bytes)
            var released = false
            return AutoCloseable { synchronized(this) { if (!released) { reserved -= bytes; released = true } } }
        }
    }

    data class Owner(val sessionId: String, val runId: String, val callId: String) {
        init { listOf(sessionId, runId, callId).forEach { require(it.isNotBlank() && it.length <= 512) } }
    }
    enum class Kind { ANSWER, TOOL_ARGUMENTS, PRIVATE_CONTINUATION, EVENT, TOOL_CONTENT }
    enum class State { COMPLETE, INCOMPLETE }
    enum class AbortTailStatus {
        PRESERVED_AVAILABLE_BYTES,
        UNPAIRED_UTF16_NOT_REPRESENTABLE,
        FAILED_INPUT_PREFIX_ONLY,
    }
    data class AbortCheckpoint(val content: Content, val tailStatus: AbortTailStatus, val inputHadFailed: Boolean)
    class Handle private constructor(val serialized: String) {
        companion object {
            fun parse(encoded: String): Handle {
                require(HANDLE.matches(encoded)) { "Invalid owned content handle" }
                return Handle(encoded)
            }
            private val HANDLE = Regex("orc1\\.[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.[A-Za-z0-9_-]{43}")
        }
        internal val id get() = serialized.split('.')[1]
        internal val token get() = Base64.getUrlDecoder().decode(serialized.split('.')[2])
        override fun toString() = "OwnedContentHandle(redacted)"
    }

    sealed class Content {
        abstract val handle: Handle
        abstract val byteLength: Long
        abstract val utf16Length: Long
        abstract val kind: Kind
        abstract val state: State
        abstract val sha256: String
        class InlineSmall internal constructor(
            override val handle: Handle, override val byteLength: Long, override val utf16Length: Long,
            override val kind: Kind, override val state: State, override val sha256: String,
            // No byte-array getter: immutable API even for the fast path.
            private val bytes: ByteArray,
        ) : Content()
        class FileBacked internal constructor(
            override val handle: Handle, override val byteLength: Long, override val utf16Length: Long,
            override val kind: Kind, override val state: State, override val sha256: String,
        ) : Content()
    }

    /** Only produced by openCompleted after integrity and lifecycle admission. Semantics not attested. */
    class Completed internal constructor(val content: Content)

    class PageReader internal constructor(input: InputStream) : Closeable {
        private val reader = PushbackReader(InputStreamReader(input, Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), 1)
        /** Pages never split a surrogate pair. EOF is null; there is no whole-content read API. */
        fun readPage(maxChars: Int = 8192): String? {
            require(maxChars in 2..65536)
            val chars = CharArray(maxChars)
            var count = reader.read(chars)
            if (count < 0) return null
            if (count > 0 && Character.isHighSurrogate(chars[count - 1])) {
                reader.unread(chars[count - 1].code); count--
                if (count == 0) { // Reader may legally return a single UTF-16 code unit.
                    chars[0] = reader.read().toChar(); val low = reader.read()
                    if (low < 0 || !Character.isLowSurrogate(low.toChar())) throw IOException("Incomplete stored Unicode")
                    chars[1] = low.toChar(); count = 2
                }
            }
            return String(chars, 0, count)
        }
        override fun close() = reader.close()
    }

    private val directory: Path
    private val rootKey: Any?
    init {
        require(inlineThreshold in 0..1024 * 1024)
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root)) { "Content root must not be a symlink" }
        directory = root.toRealPath()
        rootKey = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    }

    fun authorize(owner: Owner): Session = Session(ownerHash(owner))

    inner class Session internal constructor(private val owner: ByteArray) {
        /** Internal scratch shares this store's filesystem headroom coordinator. No path is sent to a provider. */
        internal fun scratchResources(): Pair<Path, DiskAdmission> {
            checkCancelled(); checkRoot()
            return directory to admission
        }

        fun begin(kind: Kind): Writer {
            checkCancelled(); checkRoot()
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val handle = Handle.parse("orc1.${UUID.randomUUID()}.${Base64.getUrlEncoder().withoutPadding().encodeToString(token)}")
            val meta = Meta(owner, digest(token), kind, State.INCOMPLETE, 0, 0, ByteArray(32))
            writeNew(path(handle, ".lease"), encode(meta))
            val leaseChannel = FileChannel.open(path(handle, ".lease"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            try {
                val lock = leaseChannel.tryLock() ?: throw IOException("Owned stage is already active")
                return Writer(handle, meta, leaseChannel, lock)
            } catch (e: Exception) { leaseChannel.close(); throw e }
        }

        fun open(handle: Handle): Content = open(handle) {}
        fun open(handle: Handle, additionalCheck: () -> Unit): Content {
            additionalCheck()
            val meta = metadata(handle)
            verifiedInput(path(handle, ".blob"), meta, additionalCheck).use { input ->
                return if (meta.bytes <= inlineThreshold) Content.InlineSmall(handle, meta.bytes, meta.chars, meta.kind,
                    meta.state, hex(meta.digest), readSmall(input, meta.bytes.toInt()))
                else Content.FileBacked(handle, meta.bytes, meta.chars, meta.kind, meta.state, hex(meta.digest))
            }
        }

        fun openCompleted(handle: Handle): Completed = openCompleted(handle) {}
        fun openCompleted(handle: Handle, additionalCheck: () -> Unit): Completed {
            val content = open(handle, additionalCheck)
            check(content.state == State.COMPLETE) { "Incomplete response content cannot be admitted for execution" }
            return Completed(content)
        }

        /** Integrity verified in a streaming pass on the same descriptor BEFORE exposing any bytes. */
        fun openBytes(handle: Handle): InputStream = openBytes(handle) {}
        fun openBytes(handle: Handle, additionalCheck: () -> Unit): InputStream {
            additionalCheck()
            val meta = metadata(handle)
            return verifiedInput(path(handle, ".blob"), meta, additionalCheck)
        }
        fun openPages(handle: Handle): PageReader = PageReader(openBytes(handle))
        fun openPages(handle: Handle, additionalCheck: () -> Unit): PageReader = PageReader(openBytes(handle, additionalCheck))

        /** Explicit crash-recovery inventory, not automatic eviction. Opaque handles are caller-held. */
        fun hasRecoverableStage(handle: Handle): Boolean {
            checkRoot()
            if (!Files.exists(path(handle, ".lease"), LinkOption.NOFOLLOW_LINKS)) return false
            requireOwner(handle, readMeta(path(handle, ".lease")))
            return !Files.exists(path(handle, ".meta"), LinkOption.NOFOLLOW_LINKS) &&
                (Files.exists(path(handle, ".part"), LinkOption.NOFOLLOW_LINKS) ||
                    Files.exists(path(handle, ".blob"), LinkOption.NOFOLLOW_LINKS))
        }

        /** Explicit recovery is always INCOMPLETE, including data from an interrupted seal. */
        fun recoverIncomplete(handle: Handle, expectedKind: Kind? = null): Content = withStageLock(handle) {
            check(hasRecoverableStage(handle)) { "No unpublished owned stage to recover" }
            val lease = readMeta(path(handle, ".lease"))
            check(expectedKind == null || lease.kind == expectedKind) { "Owned stage has an unexpected content kind" }
            val part = path(handle, ".part")
            val blob = path(handle, ".blob")
            val source = if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) part else blob
            val scan = scanFile(source)
            val meta = lease.copy(state = State.INCOMPLETE, bytes = scan.first, chars = -1, digest = scan.second)
            // Interrupted writes can end in a partial UTF-8 sequence: byte export is exact;
            // strict text paging reports malformed Unicode rather than inventing characters.
            commit(handle, meta, source == part)
            open(handle)
        }

        /** Explicitly discard only this uncommitted owned stage; published blobs are never deleted. */
        fun discardStage(handle: Handle) = withStageLock(handle) {
            checkRoot()
            if (Files.exists(path(handle, ".meta"), LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("Published content is retained")
            requireOwner(handle, readMeta(path(handle, ".lease")))
            listOf(".part", ".seal", ".blob", ".lease").forEach { suffix -> deleteOwnedRegular(path(handle, suffix)) }
        }

        private fun <T> withStageLock(handle: Handle, action: () -> T): T {
            requireOwner(handle, readMeta(path(handle, ".lease")))
            FileChannel.open(path(handle, ".lease"), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val lock = try { channel.tryLock() } catch (e: java.nio.channels.OverlappingFileLockException) { null }
                check(lock != null) { "Owned stage is still active" }
                lock.use { return action() }
            }
        }

        private fun metadata(handle: Handle): Meta = readMeta(path(handle, ".meta")).also { requireOwner(handle, it) }
        private fun requireOwner(handle: Handle, meta: Meta) {
            check(MessageDigest.isEqual(owner, meta.owner) && MessageDigest.isEqual(digest(handle.token), meta.token)) {
                "Content handle does not belong to this authorized session/run/call"
            }
        }

        inner class Writer internal constructor(
            val recoveryHandle: Handle, private val lease: Meta,
            private val leaseChannel: FileChannel, private val leaseLock: java.nio.channels.FileLock,
        ) : Closeable {
            private var memory: ByteArrayOutputStream? = ByteArrayOutputStream(minOf(inlineThreshold, 8192))
            private var channel: FileChannel? = null
            private val hash = MessageDigest.getInstance("SHA-256")
            private var bytes = 0L
            private var chars = 0L
            private var pendingHigh: Char? = null
            private var inputMode = 0
            private var failed = false
            private var sealed = false
            private var closed = false
            private var abortAttempted = false
            private val utf8 = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            private val utf8Buffer = ByteBuffer.allocate(8192)
            private var utf8TailReliable = true
            private val decoded = CharBuffer.allocate(4096)

            /** Incremental UTF-16; a high surrogate may be completed by the next append. */
            @Synchronized fun append(text: CharSequence) = guarded {
                require(inputMode != 2) { "Do not mix UTF-16 and UTF-8 writer modes" }; inputMode = 1
                appendChars(text)
            }

            /** Incremental strict UTF-8, including byte chunks splitting multibyte scalars. */
            @Synchronized fun appendUtf8(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) = guarded {
                require(offset >= 0 && length >= 0 && offset <= data.size - length)
                require(inputMode != 1) { "Do not mix UTF-16 and UTF-8 writer modes" }; inputMode = 2
                var cursor = offset
                val end = offset + length
                while (cursor < end) {
                    checkCancelled()
                    val n = minOf(utf8Buffer.remaining(), end - cursor)
                    utf8Buffer.put(data, cursor, n); cursor += n
                    decodeUtf8(false)
                }
            }

            /** Caller must already have validated provider completion. Validator must consume EOF.
             * Tool callers must supply full argument validation here before obtaining Completed.
             * This foundation intentionally does not manufacture protocol/JSON validation evidence.
             */
            @Synchronized fun sealCompleted(validate: (InputStream) -> Unit): Completed = guarded {
                finishEncoding()
                persistMemory(); channel!!.force(true); channel!!.close(); channel = null
                val meta = lease.copy(state = State.COMPLETE, bytes = bytes, chars = chars, digest = hash.digest())
                verifiedInput(path(recoveryHandle, ".part"), meta).use { source ->
                    val validatorInput = EofWitness(source)
                    validate(validatorInput)
                    check(validatorInput.sawEof) { "Content validation must consume the complete document" }
                }
                checkCancelled()
                commit(recoveryHandle, meta, true)
                sealed = true
                Completed(open(recoveryHandle))
            }

            /** Deliberately publish inspectable partial content without execution eligibility. */
            @Synchronized fun sealIncomplete(): Content = guarded {
                // UTF-16 has no valid UTF-8 representation for an unpaired surrogate.
                // Fail explicitly rather than silently omit it or manufacture replacement text.
                check(pendingHigh == null) { "Cannot seal partial UTF-16 with an unpaired surrogate" }
                val rawTail = if (inputMode == 2) utf8Buffer.position() else 0
                if (rawTail > 0) {
                    // Preserve received incomplete UTF-8 bytes verbatim for byte export.
                    // Text paging is strict and will report the malformed final scalar.
                    utf8TailReliable = false
                    writeBytes(utf8Buffer.array(), rawTail)
                    utf8Buffer.clear(); utf8TailReliable = true
                }
                persistMemory(); channel!!.force(true); channel!!.close(); channel = null
                val meta = lease.copy(state = State.INCOMPLETE, bytes = bytes,
                    chars = if (rawTail > 0) -1 else chars, digest = hash.digest())
                commit(recoveryHandle, meta, true)
                sealed = true
                open(recoveryHandle)
            }

            /** Terminal Stop checkpoint, NOT a cancellation reset or provider continuation.
             * Only this writer's already-held bounded buffer and existing owned file may be
             * persisted. New input/completion are disabled first. Root/disk/lock checks remain.
             * A prior failed append may have accepted only a prefix; that limitation is explicit.
             * ENOSPC/I/O can still prevent checkpointing. This method never produces Completed.
             */
            @Synchronized fun abortToIncomplete(): AbortCheckpoint {
                check(!closed && !sealed && !abortAttempted) { "Owned writer cannot be checkpointed" }
                abortAttempted = true
                val wasFailed = failed
                failed = true // no append or completed seal can resume, even if checkpoint fails
                check(leaseLock.isValid) { "Owned writer lease is no longer held" }
                checkRoot()
                check(!Files.exists(path(recoveryHandle, ".meta"), LinkOption.NOFOLLOW_LINKS)) { "Published content is retained" }
                val inline = memory?.toByteArray() // at most inlineThreshold
                val tail = if (utf8TailReliable && inputMode == 2) utf8Buffer.array().copyOf(utf8Buffer.position()) else ByteArray(0)
                val status = when {
                    pendingHigh != null -> AbortTailStatus.UNPAIRED_UTF16_NOT_REPRESENTABLE
                    wasFailed -> AbortTailStatus.FAILED_INPUT_PREFIX_ONLY
                    else -> AbortTailStatus.PRESERVED_AVAILABLE_BYTES
                }
                channel?.close(); channel = null
                val part = path(recoveryHandle, ".part")
                val blob = path(recoveryHandle, ".blob")
                val source = if (Files.exists(blob, LinkOption.NOFOLLOW_LINKS)) blob else part

                // This scope is deliberately local: no shared cancellation flag is changed,
                // and normal methods/other writers/readers cannot use these cleanup operations.
                fun checkpointWrite(target: FileChannel, data: ByteBuffer) {
                    while (data.hasRemaining()) {
                        check(leaseLock.isValid); checkRoot()
                        admission.reserve(data.remaining().toLong()).use {
                            if (channelWrite(target, data) <= 0) throw IOException("Checkpoint write made no progress")
                        }
                    }
                }
                fun checkpointNew(target: Path): FileChannel {
                    check(leaseLock.isValid); checkRoot()
                    return admission.reserve(4096).use {
                        FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                    }
                }
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) checkpointNew(source).close()
                FileChannel.open(source, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { file ->
                    if (inline != null) {
                        // A failed spill can leave a prefix of the inline buffer on disk.
                        // Verify it before completing the remainder, never append it twice.
                        check(file.size() <= inline.size) { "Failed spill does not match retained inline buffer" }
                        val existing = file.size().toInt()
                        val checkBytes = ByteBuffer.allocate(minOf(8192, maxOf(existing, 1)))
                        var position = 0
                        while (position < existing) {
                            checkBytes.clear(); checkBytes.limit(minOf(checkBytes.capacity(), existing - position))
                            val n = file.read(checkBytes); if (n <= 0) throw EOFException()
                            for (i in 0 until n) check(checkBytes.array()[i] == inline[position + i]) { "Failed spill content mismatch" }
                            position += n
                        }
                        file.position(existing.toLong())
                        checkpointWrite(file, ByteBuffer.wrap(inline, existing, inline.size - existing))
                    }
                    file.position(file.size())
                    checkpointWrite(file, ByteBuffer.wrap(tail))
                    file.force(true)
                }
                // Hash the actual persisted prefix, not optimistic append counters after a
                // partial write. Bounded memory; work is finite in existing staged-file size.
                val scan = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { file ->
                    val buffer = ByteBuffer.allocate(65536)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    while (true) {
                        check(leaseLock.isValid); checkRoot(); buffer.clear()
                        val n = file.read(buffer); if (n < 0) break
                        if (n == 0) throw IOException("Checkpoint read made no progress")
                        size = Math.addExact(size, n.toLong()); buffer.flip(); digest.update(buffer)
                    }
                    size to digest.digest()
                }
                val meta = lease.copy(state = State.INCOMPLETE, bytes = scan.first, chars = -1, digest = scan.second)
                val seal = path(recoveryHandle, ".seal")
                deleteOwnedRegular(seal)
                checkpointNew(seal).use { checkpointWrite(it, ByteBuffer.wrap(encode(meta))); it.force(true) }
                checkRoot(); check(leaseLock.isValid)
                if (source == part) Files.move(part, blob, StandardCopyOption.ATOMIC_MOVE)
                Files.move(seal, path(recoveryHandle, ".meta"), StandardCopyOption.ATOMIC_MOVE)
                sealed = true; memory = null
                // Return metadata only. Normal read APIs remain cancelled until the caller
                // opens a new non-cancelled authorized session/store after Stop has settled.
                val content = Content.FileBacked(recoveryHandle, meta.bytes, meta.chars, meta.kind, State.INCOMPLETE, hex(meta.digest))
                return AbortCheckpoint(content, status, wasFailed)
            }

            /** Close drops only this writer's unpublished memory/FD. Disk stages survive for explicit recovery/discard. */
            @Synchronized override fun close() {
                if (closed) return
                closed = true; memory = null
                try { channel?.close() } finally {
                    channel = null
                    try { leaseLock.release() } finally { leaseChannel.close() }
                }
            }

            private fun finishEncoding() {
                if (inputMode == 2) {
                    decodeUtf8(true)
                    do {
                        decoded.clear(); val result = utf8.flush(decoded); result.throwIfError()
                        decoded.flip(); appendChars(decoded)
                    } while (result.isOverflow)
                }
                check(pendingHigh == null) { "Incomplete Unicode scalar at response completion" }
            }

            private fun decodeUtf8(end: Boolean) {
                utf8TailReliable = false
                utf8Buffer.flip()
                while (true) {
                    decoded.clear()
                    val result = utf8.decode(utf8Buffer, decoded, end)
                    result.throwIfError()
                    decoded.flip(); appendChars(decoded)
                    if (!result.isOverflow) break
                }
                utf8Buffer.compact(); utf8TailReliable = true
                if (end) check(utf8Buffer.position() == 0) { "Incomplete UTF-8 at response completion" }
            }

            private fun appendChars(text: CharSequence) {
                val chunk = ByteArray(8192)
                var size = 0
                fun emit(value: Int) { if (size == chunk.size) { writeBytes(chunk, size); size = 0 }; chunk[size++] = value.toByte() }
                for (i in 0 until text.length) {
                    if (i % 2048 == 0) checkCancelled()
                    val c = text[i]
                    val high = pendingHigh
                    val scalar: Int
                    if (high != null) {
                        require(Character.isLowSurrogate(c)) { "Malformed UTF-16 surrogate pair" }
                        scalar = Character.toCodePoint(high, c); pendingHigh = null
                        chars = Math.addExact(chars, 2)
                    } else {
                        if (Character.isHighSurrogate(c)) { pendingHigh = c; continue }
                        require(!Character.isLowSurrogate(c)) { "Unexpected UTF-16 low surrogate" }
                        scalar = c.code; chars = Math.addExact(chars, 1)
                    }
                    when {
                        scalar < 0x80 -> emit(scalar)
                        scalar < 0x800 -> { emit(0xc0 or (scalar shr 6)); emit(0x80 or (scalar and 63)) }
                        scalar < 0x10000 -> { emit(0xe0 or (scalar shr 12)); emit(0x80 or ((scalar shr 6) and 63)); emit(0x80 or (scalar and 63)) }
                        else -> { emit(0xf0 or (scalar shr 18)); emit(0x80 or ((scalar shr 12) and 63)); emit(0x80 or ((scalar shr 6) and 63)); emit(0x80 or (scalar and 63)) }
                    }
                }
                if (size > 0) writeBytes(chunk, size)
            }

            private fun writeBytes(data: ByteArray, length: Int) {
                checkCancelled()
                val buffer = memory
                if (buffer != null && bytes + length <= inlineThreshold) buffer.write(data, 0, length)
                else { persistMemory(); writeChannel(channel!!, ByteBuffer.wrap(data, 0, length)) }
                hash.update(data, 0, length); bytes = Math.addExact(bytes, length.toLong())
            }
            private fun persistMemory() {
                val buffer = memory ?: return
                channel = newChannel(path(recoveryHandle, ".part"))
                val data = buffer.toByteArray() // bounded by configured inline spill threshold
                writeChannel(channel!!, ByteBuffer.wrap(data)); memory = null
            }
            private inline fun <T> guarded(block: () -> T): T {
                check(!closed && !sealed && !failed) { "Owned writer is no longer writable" }
                return try { checkCancelled(); block() } catch (e: Exception) { failed = true; channel?.close(); channel = null; throw e }
            }
        }
    }

    internal data class Meta(val owner: ByteArray, val token: ByteArray, val kind: Kind, val state: State,
        val bytes: Long, val chars: Long, val digest: ByteArray)
    private class EofWitness(input: InputStream) : FilterInputStream(input) {
        var sawEof = false
        override fun read(): Int = super.read().also { if (it == -1) sawEof = true }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = `in`.read(bytes, offset, length).also { if (it == -1) sawEof = true }
        override fun skip(count: Long): Long = 0 // validation must read all content, not seek over it
        override fun markSupported() = false
    }

    private fun path(handle: Handle, suffix: String): Path { checkRoot(); return directory.resolve(handle.id + suffix) }
    private fun checkRoot() {
        val attrs = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attrs.isDirectory && !attrs.isSymbolicLink && (rootKey == null || rootKey == attrs.fileKey())) { "Owned content root changed" }
    }
    private fun checkCancelled() { if (cancelled()) throw java.util.concurrent.CancellationException("Response content operation cancelled") }
    private fun newChannel(path: Path): FileChannel {
        checkCancelled(); checkRoot()
        // Admit filesystem metadata allocation too, including an empty response.
        return admission.reserve(4096).use {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        }
    }
    private fun writeChannel(channel: FileChannel, bytes: ByteBuffer) {
        while (bytes.hasRemaining()) {
            checkCancelled(); checkRoot()
            val n = bytes.remaining().toLong()
            admission.reserve(n).use {
                val wrote = channelWrite(channel, bytes)
                if (wrote <= 0) throw IOException("Storage write made no progress")
            }
        }
    }
    private fun writeNew(path: Path, bytes: ByteArray) {
        // If creation itself fails, this call does not own any existing path.
        val channel = newChannel(path)
        try { channel.use { writeChannel(it, ByteBuffer.wrap(bytes)); it.force(true) } }
        catch (failure: Exception) {
            // This call created the unpublished metadata file, so it can clean its own
            // failed partial write. In particular begin() must not orphan an unusable lease.
            try { deleteOwnedRegular(path) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun commit(handle: Handle, meta: Meta, movePart: Boolean) {
        checkCancelled(); checkRoot()
        val seal = path(handle, ".seal")
        // Only our unpublished random-name commit staging file is replaced on recovery.
        deleteOwnedRegular(seal)
        writeNew(seal, encode(meta))
        if (movePart) Files.move(path(handle, ".part"), path(handle, ".blob"), StandardCopyOption.ATOMIC_MOVE)
        Files.move(seal, path(handle, ".meta"), StandardCopyOption.ATOMIC_MOVE)
        // Directory fsync is not portable across Android/JVM filesystems; atomic visibility is
        // guaranteed by required ATOMIC_MOVE, power-loss durability still requires device tests.
    }
    private fun readMeta(path: Path): Meta {
        checkRoot()
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() !in 1..2048) throw IOException("Malformed owned content metadata")
            val bytes = ByteBuffer.allocate(channel.size().toInt())
            while (bytes.hasRemaining()) if (channel.read(bytes) <= 0) throw EOFException()
            return DataInputStream(ByteArrayInputStream(bytes.array())).use { input ->
                check(input.readInt() == 0x4f524331) { "Unknown owned content format" }
                val owner = ByteArray(32).also(input::readFully); val token = ByteArray(32).also(input::readFully)
                val kind = Kind.entries.getOrNull(input.readUnsignedByte()) ?: throw IOException("Invalid content kind")
                val state = State.entries.getOrNull(input.readUnsignedByte()) ?: throw IOException("Invalid content state")
                val length = input.readLong(); val chars = input.readLong()
                check(length >= 0 && chars >= -1)
                val digest = ByteArray(32).also(input::readFully)
                check(input.read() == -1) { "Trailing content metadata" }
                Meta(owner, token, kind, state, length, chars, digest)
            }
        }
    }
    private fun encode(meta: Meta): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
        it.writeInt(0x4f524331); it.write(meta.owner); it.write(meta.token); it.writeByte(meta.kind.ordinal); it.writeByte(meta.state.ordinal)
        it.writeLong(meta.bytes); it.writeLong(meta.chars); it.write(meta.digest)
    } }.toByteArray()
    private fun verifiedInput(path: Path, meta: Meta, additionalCheck: () -> Unit = {}): InputStream {
        additionalCheck()
        checkRoot(); checkCancelled()
        val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        try {
            check(channel.size() == meta.bytes) { "Owned response content length mismatch" }
            val scan = scanChannel(channel, additionalCheck)
            check(scan.first == meta.bytes && MessageDigest.isEqual(scan.second, meta.digest)) { "Owned response content integrity mismatch" }
            channel.position(0)
            return object : InputStream() {
                override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    additionalCheck(); checkCancelled(); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                    if (length == 0) return 0
                    return channel.read(ByteBuffer.wrap(bytes, offset, minOf(length, 65536)))
                }
                override fun close() = channel.close()
            }
        } catch (e: Exception) { channel.close(); throw e }
    }
    private fun scanFile(path: Path): Pair<Long, ByteArray> = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use(::scanChannel)
    private fun scanChannel(channel: FileChannel): Pair<Long, ByteArray> = scanChannel(channel) {}
    private fun scanChannel(channel: FileChannel, additionalCheck: () -> Unit): Pair<Long, ByteArray> {
        val buffer = ByteBuffer.allocate(65536); val hash = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        while (true) {
            additionalCheck(); checkCancelled(); buffer.clear(); val n = channel.read(buffer)
            if (n < 0) break
            if (n == 0) throw IOException("Storage read made no progress")
            bytes = Math.addExact(bytes, n.toLong()); buffer.flip(); hash.update(buffer)
        }
        return bytes to hash.digest()
    }
    private fun deleteOwnedRegular(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Owned temporary path is not a regular file" }
        Files.delete(path)
    }
    private fun readSmall(input: InputStream, length: Int): ByteArray = ByteArray(length).also { bytes ->
        var read = 0; while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) throw EOFException(); read += n }
        check(input.read() == -1)
    }
    private fun ownerHash(owner: Owner): ByteArray = digest(ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
        listOf(owner.sessionId, owner.runId, owner.callId).forEach { part -> val encoded = part.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
    } }.toByteArray())
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun java.nio.charset.CoderResult.throwIfError() { if (isError) throwException() }
}
