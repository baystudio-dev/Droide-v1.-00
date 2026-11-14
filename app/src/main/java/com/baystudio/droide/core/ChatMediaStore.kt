package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Workspace-private copies, shared by explicit owners and reclaimed without touching originals. */
class ChatMediaStore(
    workDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val fileBytes: Long = MAX_FILE_BYTES,
        val cacheBytes: Long = MAX_CACHE_BYTES,
        val entries: Int = MAX_ENTRIES,
        val derivativeBytes: Long = MAX_DERIVATIVE_BYTES,
    ) {
        init {
            require(fileBytes in 1..MAX_FILE_BYTES && cacheBytes in fileBytes..MAX_CACHE_BYTES)
            require(entries in 1..MAX_ENTRIES && derivativeBytes in 1..MAX_DERIVATIVE_BYTES)
        }
    }

    @Serializable private data class Entry(
        val ref: ChatMediaRef,
        val derivatives: Map<String, Long> = emptyMap(),
        val originalSha256: String? = null,
        val integrityOrigin: String = "import",
    ) {
        val totalBytes: Long get() = ref.bytes + derivatives.values.sum()
    }
    @Serializable private data class Index(
        val version: Int = 1,
        val entries: Map<String, Entry> = emptyMap(),
        val owners: Map<String, Set<String>> = emptyMap(),
        val retired: Map<String, Entry> = emptyMap(),
    ) {
        val totalBytes: Long get() = entries.values.sumOf { it.totalBytes } + retired.values.sumOf { it.totalBytes }
    }
    private data class Stamp(val size: Long, val modified: Long, val key: String?)
    private class SharedState {
        var index: Index? = null
        var stamp: Stamp? = null
        val reservations = mutableMapOf<String, Long>()
        val importingOwners = mutableMapOf<String, String>()
        val revokedOwners = linkedSetOf<String>()
    }

    private val workRoot = ChatMediaStoragePaths.canonicalWorkspace(workDir)
    private val base = ChatMediaStoragePaths.baseDirectory(workRoot)
    private val root = ChatMediaStoragePaths.directory(workRoot)
    private val blobs = File(root, "blobs")
    private val staging = File(root, "staging")
    private val manifest = File(root, "index.json")
    private val state = sharedState(root.absolutePath)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    val storageDirectory: File get() = root

    fun hasManifest(): Boolean {
        ChatMediaStoragePaths.requireNoLinks(manifest)
        return Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    suspend fun import(ownerId: String, name: String, mimeType: String, input: InputStream): ChatMediaRef = withContext(Dispatchers.IO) {
        validateOwner(ownerId)
        val id = "m-${UUID.randomUUID()}"
        val reservation = synchronized(state) {
            val index = readIndex()
            require(ownerId !in state.revokedOwners) { "This conversation was removed; attach the file to a new conversation." }
            reserve(index, id, limits.fileBytes).also { state.importingOwners[id] = ownerId }
        }
        val temp = File(staging, "$id.part")
        val context = currentCoroutineContext()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val copied = copyBounded(input, temp, reservation, digest) { context.ensureActive() }
            val originalSha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            context.ensureActive()
            val now = clock()
            require(now >= 0L && now <= Long.MAX_VALUE - RETENTION_MS) { "Media creation time is invalid" }
            val type = normalizeMime(mimeType)
            val ref = ChatMediaRef(id, "$RELATIVE_ROOT/blobs/$id/original.${extension(name, type)}",
                safeDisplayName(name), type, copied, now, now + RETENTION_MS)
            synchronized(state) {
                val index = readIndex()
                require(ownerId !in state.revokedOwners) { "This conversation was removed before its attachment finished." }
                val target = checkedPath(ref.path, allowMissing = true)
                check(target.parentFile.isDirectory || target.parentFile.mkdir()) { "Cannot create attachment directory" }
                try {
                    move(temp, target)
                    val next = index.copy(entries = index.entries + (id to Entry(ref, originalSha256 = originalSha256)),
                        owners = index.owners + (ownerId to ((index.owners[ownerId].orEmpty()) + id)))
                    writeIndex(next)
                } catch (failure: Throwable) {
                    // Only this import's exact leaf is ours; never recursively sweep an unexpected child.
                    target.delete()
                    target.parentFile.delete()
                    throw failure
                }
                ref
            }
        } finally {
            temp.delete()
            synchronized(state) {
                state.reservations.remove(id)
                state.importingOwners.remove(id)
                trimRevokedOwners()
            }
        }
    }

    fun retain(ownerId: String, refs: List<ChatMediaRef>) = synchronized(state) {
        validateOwner(ownerId)
        val index = readIndex()
        require(ownerId !in state.revokedOwners) { "This conversation was removed; media cannot be attached to it." }
        val ids = refs.mapNotNull { ref ->
            validateRef(ref)
            if (ref.expiresAt <= clock()) {
                require(index.entries[ref.id]?.ref == ref || ref.id !in index.entries) { "Attachment metadata does not match" }
                return@mapNotNull null // Durable text/history resaves keep expected expired placeholders usable.
            }
            require(index.entries[ref.id]?.ref == ref && fileLocked(ref, index) != null) {
                "Attachment is expired or unavailable; choose the file again."
            }
            ref.id
        }.toSet()
        val previous = index.owners[ownerId].orEmpty()
        if (!previous.containsAll(ids)) writeIndex(index.copy(owners = index.owners + (ownerId to (previous + ids))))
    }

    fun releaseOwner(ownerId: String) = synchronized(state) {
        validateOwner(ownerId)
        state.revokedOwners += ownerId
        trimRevokedOwners()
        val index = readIndex()
        retireUnowned(index.copy(owners = index.owners - ownerId))
    }

    /** History deletion has already committed. Reclamation must never block its UI/session reset. */
    fun releaseDeletedOwner(ownerId: String): Boolean = synchronized(state) {
        runCatching {
            validateOwner(ownerId)
            state.revokedOwners += ownerId
            if (!root.exists()) return@synchronized true
            ensureStructure()
            val intents = readDeletionIntents() + ownerId
            require(intents.size <= MAX_OWNERS) { "Media deletion queue is full" }
            val temp = File(root, ".deletions-${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temp).use { out -> out.write(json.encodeToString(intents.toList()).toByteArray(Charsets.UTF_8)); out.fd.sync() }
                move(temp, File(root, "deleted-owners.json"), replace = true)
            } finally { temp.delete() }
            readIndex() // Applies the durable intent when the ownership index is readable.
            true
        }.getOrDefault(false)
    }

    private fun readDeletionIntents(): Set<String> {
        val file = File(root, "deleted-owners.json")
        ChatMediaStoragePaths.requireNoLinks(file)
        if (!file.exists()) return emptySet()
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..MAX_MANIFEST_BYTES)
        return json.decodeFromString<List<String>>(file.readText(Charsets.UTF_8)).also {
            require(it.size <= MAX_OWNERS); it.forEach(::validateOwner)
        }.toSet()
    }

    fun release(ownerId: String, ref: ChatMediaRef) = synchronized(state) {
        validateOwner(ownerId)
        val index = readIndex()
        require(index.entries[ref.id]?.ref == ref || ref.id !in index.entries) { "Attachment metadata does not match" }
        val remaining = index.owners[ownerId].orEmpty() - ref.id
        val owners = if (remaining.isEmpty()) index.owners - ownerId else index.owners + (ownerId to remaining)
        retireUnowned(index.copy(owners = owners))
    }

    fun ownerRefs(ownerId: String): List<ChatMediaRef> = synchronized(state) {
        validateOwner(ownerId)
        val index = readIndex()
        index.owners[ownerId].orEmpty().mapNotNull { index.entries[it]?.ref }
    }

    fun copyOwner(sourceOwnerId: String, targetOwnerId: String) = synchronized(state) {
        validateOwner(sourceOwnerId)
        validateOwner(targetOwnerId)
        if (!hasManifest() && !root.exists()) return@synchronized
        val index = readIndex()
        require(targetOwnerId !in state.revokedOwners) { "The target conversation was removed" }
        val ids = index.owners[sourceOwnerId].orEmpty().filter { id ->
            index.entries[id]?.ref?.let { fileLocked(it, index) != null } == true
        }.toSet()
        if (ids.isNotEmpty()) writeIndex(index.copy(owners = index.owners + (targetOwnerId to (index.owners[targetOwnerId].orEmpty() + ids))))
    }

    fun findByPath(path: String): ChatMediaRef? = synchronized(state) {
        val index = readIndex()
        index.entries.values.firstOrNull { entry -> entry.ref.path == path ||
            entry.derivatives.keys.any { leaf -> path == "$RELATIVE_ROOT/blobs/${entry.ref.id}/$leaf" }
        }?.ref
    }

    fun ref(id: String): ChatMediaRef? = synchronized(state) { readIndex().entries[id]?.ref }

    /** Original-only authority check. A preview or arbitrary filesystem path is not an asset source. */
    internal fun requireOwnedOriginal(ownerId: String, path: String): ChatMediaRef = synchronized(state) {
        ownedOriginalLocked(ownerId, path, readIndex())
    }

    /**
     * Check ownership and open the original under the same short store lock as cleanup.
     * The descriptor remains readable if cleanup later unlinks its chat copy. No temporary
     * owner, TTL extension or store lock is held while the project copy is being streamed.
     */
    internal suspend fun openOwnedOriginal(ownerId: String, ref: ChatMediaRef): IntegrityCheckedOriginal {
        val context = currentCoroutineContext()
        val opened = synchronized(state) {
            val index = readIndex()
            require(ownedOriginalLocked(ownerId, ref.path, index) == ref) { "Attachment changed; choose it again" }
            val file = requireNotNull(fileLocked(ref, index)) { "Attachment is expired or unavailable; choose the file again." }
            val before = originalStamp(file)
            val channel = Files.newByteChannel(file.toPath(), java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            try {
                require(channel.size() == ref.bytes && before == originalStamp(file)) { "Attachment changed; choose it again" }
                val entry = index.entries.getValue(ref.id)
                OpenOriginal(file, channel, before, entry.originalSha256, entry.integrityOrigin)
            } catch (failure: Throwable) {
                try { channel.close() } catch (close: Exception) { failure.addSuppressed(close) }
                throw failure
            }
        }
        try {
            val digest = opened.digest ?: run {
                // Upgrade old manifests without invalidating existing conversations. This establishes
                // the stable CURRENT bytes only, never historical upload authenticity. No store lock
                // is held during either bounded pass; cleanup/replacement must fail revalidation.
                val first = hashOriginal(opened.channel, ref.bytes) { context.ensureActive() }
                val second = hashOriginal(opened.channel, ref.bytes) { context.ensureActive() }
                require(first == second && opened.stamp == originalStamp(opened.file)) {
                    "Legacy attachment changed during integrity migration; existing attachment was preserved."
                }
                synchronized(state) {
                    val index = readIndex()
                    require(ownedOriginalLocked(ownerId, ref.path, index) == ref &&
                        opened.stamp == originalStamp(opened.file)) {
                        "Legacy attachment changed or expired during integrity migration; existing data was preserved."
                    }
                    val entry = index.entries.getValue(ref.id)
                    require(entry.originalSha256 == null || entry.originalSha256 == first) {
                        "Legacy attachment integrity migration conflicted; existing data was preserved."
                    }
                    if (entry.originalSha256 == null) writeIndex(index.copy(entries = index.entries +
                        (ref.id to entry.copy(originalSha256 = first, integrityOrigin = "legacy_snapshot"))))
                }
                first
            }
            context.ensureActive()
            opened.channel.position(0)
            return IntegrityCheckedOriginal(java.nio.channels.Channels.newInputStream(opened.channel), ref.bytes, digest,
                if (opened.digest == null) "legacy_snapshot" else opened.integrityOrigin)
        } catch (failure: Throwable) {
            try { opened.channel.close() } catch (close: Exception) { failure.addSuppressed(close) }
            throw failure
        }
    }

    private data class OriginalStamp(val size: Long, val modified: java.nio.file.attribute.FileTime, val key: Any)
    private data class OpenOriginal(val file: File, val channel: java.nio.channels.SeekableByteChannel,
        val stamp: OriginalStamp, val digest: String?, val integrityOrigin: String)

    private fun originalStamp(file: File): OriginalStamp {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile) { "Attachment must remain a regular file" }
        return OriginalStamp(attributes.size(), attributes.lastModifiedTime(),
            requireNotNull(attributes.fileKey()) { "Attachment identity cannot be verified on this filesystem" })
    }

    private fun hashOriginal(channel: java.nio.channels.SeekableByteChannel, expected: Long, checkActive: () -> Unit): String {
        require(expected in 1..MAX_FILE_BYTES)
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.nio.ByteBuffer.allocate(32_768)
        var total = 0L
        var emptyReads = 0
        while (true) {
            checkActive()
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), expected - total + 1).toInt())
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) { require(++emptyReads <= 16) { "Attachment stopped providing data" }; continue }
            emptyReads = 0
            total += count
            require(total <= expected) { "Attachment grew during integrity migration" }
            digest.update(buffer.array(), 0, count)
        }
        require(total == expected && channel.size() == expected) { "Attachment changed during integrity migration" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun ownedOriginalLocked(ownerId: String, path: String, index: Index): ChatMediaRef {
        validateOwner(ownerId)
        val ref = index.entries.values.firstOrNull { it.ref.path == path }?.ref
            ?: throw IllegalArgumentException("Attachment is expired or unavailable; use its original managed path.")
        require(ownerId !in state.revokedOwners && ref.id in index.owners[ownerId].orEmpty()) {
            "This attachment does not belong to the active conversation"
        }
        require(fileLocked(ref, index) != null) { "Attachment is expired or unavailable; choose the file again." }
        return ref
    }

    fun file(ref: ChatMediaRef): File? = synchronized(state) { fileLocked(ref, readIndex()) }

    fun resolvePath(path: String): File? = synchronized(state) {
        val index = readIndex()
        val entry = index.entries.values.firstOrNull { entry -> entry.ref.path == path ||
            entry.derivatives.keys.any { leaf -> path == "$RELATIVE_ROOT/blobs/${entry.ref.id}/$leaf" }
        } ?: return@synchronized null
        if (path == entry.ref.path) fileLocked(entry.ref, index)
        else derivativeFileLocked(entry.ref, path.substringAfterLast('/'), index)
    }

    fun aliasForFile(file: File): String = synchronized(state) {
        ChatMediaStoragePaths.requireNoLinks(file)
        require(file.absoluteFile.toPath().normalize().startsWith(root.toPath())) { "File is not a registered chat attachment" }
        val alias = "$RELATIVE_ROOT/${file.absoluteFile.relativeTo(root).invariantSeparatorsPath}"
        require(resolvePath(alias)?.absoluteFile == file.absoluteFile) { "File is not a registered live chat attachment" }
        alias
    }

    fun nextExpiry(): Long? = synchronized(state) {
        val index = readIndex()
        if (index.retired.isNotEmpty()) clock() else index.entries.values.minOfOrNull { it.ref.expiresAt }
    }

    /** Returns the number of expired entries retired; pending deletion is retried without scanning projects. */
    fun cleanupExpired(now: Long = clock()): Int = synchronized(state) {
        val index = readIndex()
        val expired = index.entries.filterValues { it.ref.expiresAt <= now }
        val owners = index.owners.mapValues { (_, ids) -> ids - expired.keys }.filterValues { it.isNotEmpty() }
        val next = index.copy(entries = index.entries - expired.keys, owners = owners,
            retired = index.retired + expired)
        retireUnowned(next)
        cleanupStaging()
        expired.size
    }

    suspend fun importDerivative(ref: ChatMediaRef, leafName: String, input: InputStream): File = withContext(Dispatchers.IO) {
        val leaf = derivativeLeaf(leafName)
        synchronized(state) { derivativeFileLocked(ref, leaf, readIndex()) }?.let { return@withContext it }
        val ticket = "d-${UUID.randomUUID()}"
        val reservation = synchronized(state) {
            val index = readIndex()
            require(fileLocked(ref, index) != null) { "Attachment is expired or unavailable" }
            require(index.entries.getValue(ref.id).derivatives.size < MAX_DERIVATIVES ||
                leaf in index.entries.getValue(ref.id).derivatives) { "Attachment preview limit reached" }
            reserve(index, ticket, limits.derivativeBytes, newEntry = false)
        }
        val temp = File(staging, "$ticket.part")
        val context = currentCoroutineContext()
        try {
            val copied = copyBounded(input, temp, reservation) { context.ensureActive() }
            context.ensureActive()
            synchronized(state) {
                val index = readIndex()
                require(fileLocked(ref, index) != null) { "Attachment expired or was removed while creating its preview" }
                val entry = index.entries.getValue(ref.id)
                val target = checkedPath("$RELATIVE_ROOT/blobs/${ref.id}/$leaf", allowMissing = true)
                // Immutable derivative names avoid replacing an active reader's file during index publication.
                if (leaf in entry.derivatives) return@synchronized requireNotNull(derivativeFileLocked(ref, leaf, index))
                require(entry.derivatives.size < MAX_DERIVATIVES) { "Attachment preview limit reached" }
                move(temp, target)
                try {
                    writeIndex(index.copy(entries = index.entries + (ref.id to entry.copy(derivatives = entry.derivatives + (leaf to copied)))))
                } catch (failure: Throwable) {
                    target.delete()
                    throw failure
                }
                target
            }
        } finally {
            temp.delete()
            synchronized(state) { state.reservations.remove(ticket) }
        }
    }

    fun derivativeFile(ref: ChatMediaRef, leafName: String): File? = synchronized(state) {
        derivativeFileLocked(ref, derivativeLeaf(leafName), readIndex())
    }

    private fun derivativeFileLocked(ref: ChatMediaRef, leaf: String, index: Index): File? {
        if (fileLocked(ref, index) == null) return null
        val bytes = index.entries[ref.id]?.derivatives?.get(leaf) ?: return null
        val file = checkedPath("$RELATIVE_ROOT/blobs/${ref.id}/$leaf", allowMissing = true)
        return file.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.length() == bytes }
    }

    private fun fileLocked(ref: ChatMediaRef, index: Index): File? {
        if (index.entries[ref.id]?.ref != ref || ref.expiresAt <= clock()) return null
        val file = checkedPath(ref.path, allowMissing = true)
        return file.takeIf { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.length() == ref.bytes }
    }

    private fun reserve(index: Index, ticket: String, ceiling: Long, newEntry: Boolean = true): Long {
        if (newEntry) require(index.entries.size + index.retired.size + state.reservations.keys.count { it.startsWith("m-") } < limits.entries) {
            "Chat attachment limit reached; delete older conversations or save their media before attaching more files."
        }
        val available = limits.cacheBytes - index.totalBytes - state.reservations.values.sum()
        require(available > 0) { "Chat media cache is full; delete older conversations or wait for seven-day media cleanup." }
        return minOf(ceiling, available).also { state.reservations[ticket] = it }
    }

    private fun copyBounded(input: InputStream, target: File, maxBytes: Long,
        digest: MessageDigest? = null, checkActive: () -> Unit): Long {
        val buffer = ByteArray(32_768)
        var total = 0L
        var emptyReads = 0
        FileOutputStream(target).use { output ->
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    require(++emptyReads <= 16) { "Attachment source stopped providing data; choose the file again." }
                    continue
                }
                emptyReads = 0
                require(count.toLong() <= maxBytes - total) { "Attachment exceeds the available chat media space or ${maxBytes / (1024 * 1024)} MiB file limit; choose a smaller file." }
                output.write(buffer, 0, count)
                digest?.update(buffer, 0, count)
                total += count
            }
            require(total > 0L) { "Attachment is empty" }
            output.fd.sync()
        }
        return total
    }

    /** Checks the actual bytes consumed by the project copy, not a separate racy preliminary hash. */
    internal class IntegrityCheckedOriginal(input: InputStream, private val expectedBytes: Long,
        private val expectedDigest: String, val integrityOrigin: String) : java.io.FilterInputStream(input) {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var count = 0L
        private var verified = false
        override fun read(): Int {
            val value = `in`.read()
            if (value < 0) verify() else { count++; digest.update(value.toByte()); checkLength() }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = `in`.read(bytes, offset, length)
            if (read < 0) verify() else if (read > 0) {
                count += read; checkLength(); digest.update(bytes, offset, read)
            }
            return read
        }
        private fun checkLength() {
            require(count <= expectedBytes) { "Attachment changed while copying; project asset was not saved" }
        }
        private fun verify() {
            if (verified) return
            require(count == expectedBytes && digest.digest().joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            } == expectedDigest) { "Attachment integrity check failed; project asset was not saved. Attach the original again." }
            verified = true
        }
    }

    private fun retireUnowned(index: Index) {
        val owned = index.owners.values.flatten().toSet()
        val unowned = index.entries.filterKeys { it !in owned }
        val next = index.copy(entries = index.entries - unowned.keys,
            retired = index.retired + unowned)
        if (next != state.index) writeIndex(next)
        if (next.retired.isEmpty()) return
        val removed = next.retired.filterValues(::deleteRetiredEntry).keys
        if (removed.isNotEmpty()) writeIndex(next.copy(retired = next.retired - removed.toSet()))
    }

    /** Delete only recorded leaves. Unknown files/subdirectories remain untouched, even inside a blob. */
    private fun deleteRetiredEntry(entry: Entry): Boolean {
        val directory = checkedPath("$RELATIVE_ROOT/blobs/${entry.ref.id}", allowMissing = true)
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val names = listOf(entry.ref.path.substringAfterLast('/')) + entry.derivatives.keys
        for (name in names) {
            val leaf = File(directory, name).toPath()
            // Unlink a replaced leaf symlink itself, never its destination. Never recurse into directories.
            if (Files.isRegularFile(leaf, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(leaf)) {
                try { Files.deleteIfExists(leaf) } catch (_: java.io.IOException) { return false }
            }
        }
        return directory.delete() // A nonempty directory is deliberately preserved and retried later.
    }

    /** Only Droide's known partial names, not arbitrary files, can be removed after a worker disappears. */
    private fun cleanupStaging() {
        Files.newDirectoryStream(staging.toPath()).use { stream ->
            var examined = 0
            for (path in stream) {
                if (++examined > MAX_ENTRIES) break
                val name = path.fileName.toString()
                val ticket = name.removeSuffix(".part")
                val known = name.endsWith(".part") && (ticket.startsWith("m-") || ticket.startsWith("d-")) &&
                    ID.matches("m-" + ticket.substring(2))
                if (known && ticket !in state.reservations &&
                    (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))) {
                    Files.deleteIfExists(path)
                }
            }
        }
    }

    private fun readIndex(): Index {
        val index = readStoredIndex()
        val intents = readDeletionIntents()
        if (intents.isEmpty()) return index
        val removed = index.owners.keys.filter { owner -> intents.any { owner == it || owner.startsWith("queued:$it:") } }.toSet()
        state.revokedOwners += intents + removed
        trimRevokedOwners()
        retireUnowned(index.copy(owners = index.owners - removed))
        Files.deleteIfExists(File(root, "deleted-owners.json").toPath())
        return requireNotNull(state.index)
    }

    private fun readStoredIndex(): Index {
        ensureStructure()
        val stamp = stamp()
        if (state.index != null && stamp == state.stamp) return state.index!!
        if (stamp == null) {
            require(blobs.listFiles().orEmpty().isEmpty()) { "Chat media index is unavailable; existing attachments were preserved." }
            val index = Index()
            writeIndex(index)
            return index
        }
        try {
            require(stamp.size in 1..MAX_MANIFEST_BYTES) { "Chat media index is too large" }
            val index = json.decodeFromString<Index>(manifest.readText(Charsets.UTF_8))
            validateIndex(index)
            state.index = index
            state.stamp = stamp
            return index
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException("Chat media index could not be read; existing attachments were preserved.", failure)
        }
    }

    private fun writeIndex(index: Index) {
        validateIndex(index)
        val encoded = json.encodeToString(index).toByteArray(Charsets.UTF_8)
        require(encoded.size.toLong() <= MAX_MANIFEST_BYTES) { "Chat media ownership index is full; remove older conversations first." }
        val temp = File(root, ".index-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output -> output.write(encoded); output.fd.sync() }
            move(temp, manifest, replace = true)
            state.index = index
            state.stamp = stamp()
        } finally { temp.delete() }
    }

    private fun validateIndex(index: Index) {
        require(index.version == 1 && index.entries.size + index.retired.size <= MAX_ENTRIES) { "Invalid chat media index" }
        require(index.owners.size <= MAX_OWNERS && index.owners.values.sumOf { it.size } <= MAX_OWNER_REFERENCES) { "Chat media ownership limit reached; remove older conversations first." }
        (index.entries + index.retired).forEach { (id, entry) ->
            val ref = entry.ref
            require(ref.id == id)
            validateRef(ref)
            require(entry.originalSha256 == null || entry.originalSha256.length == 64 &&
                entry.originalSha256.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid attachment integrity digest" }
            require(entry.integrityOrigin in setOf("import", "legacy_snapshot")) { "Invalid attachment integrity origin" }
            require(entry.derivatives.size <= MAX_DERIVATIVES)
            entry.derivatives.forEach { (leaf, size) -> require(derivativeLeaf(leaf) == leaf && size in 1..MAX_DERIVATIVE_BYTES) }
        }
        index.owners.forEach { (owner, ids) -> validateOwner(owner); require(ids.isNotEmpty() && ids.all { it in index.entries }) }
        index.retired.forEach { (id, _) -> require(id !in index.entries) }
        require(index.totalBytes <= MAX_CACHE_BYTES) { "Chat media cache index exceeds its size limit" }
    }

    private fun ensureStructure() {
        listOf(base, root, blobs, staging).forEach { directory ->
            ChatMediaStoragePaths.requireNoLinks(directory)
            check(directory.isDirectory || directory.mkdir()) { "Cannot create chat media storage" }
        }
        require(!Files.isSymbolicLink(manifest.toPath())) { "Chat media index cannot be a symbolic link; existing files were preserved." }
    }

    private fun checkedPath(relative: String, allowMissing: Boolean): File {
        val file = ChatMediaStoragePaths.physicalPathIn(root, relative, allowBlobDirectory = true)
        if (!allowMissing) require(file.exists()) { "Managed media file is unavailable" }
        return file
    }

    private fun stamp(): Stamp? {
        if (!Files.exists(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val attrs = Files.readAttributes(manifest.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attrs.isRegularFile) { "Chat media index is not a regular file" }
        return Stamp(attrs.size(), attrs.lastModifiedTime().toMillis(), attrs.fileKey()?.toString())
    }

    private fun move(source: File, target: File, replace: Boolean = false) {
        val replaceOptions = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, *replaceOptions)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), *replaceOptions)
        }
    }

    private fun validateOwner(owner: String) {
        require(owner.isNotBlank() && owner.length <= 160 && owner.none { it.code < 0x20 || it.code == 0x7f || it in "/\\" }) { "Invalid chat media owner" }
    }
    private fun validateRef(ref: ChatMediaRef) {
        require(ID.matches(ref.id) && ORIGINAL_PATH.matches(ref.path) && ref.path.substringAfter("/blobs/").substringBefore('/') == ref.id) { "Invalid managed attachment reference" }
        require(ref.name == safeDisplayName(ref.name) && ref.mediaType == normalizeMime(ref.mediaType)) { "Invalid attachment metadata" }
        require(ref.bytes in 1..MAX_FILE_BYTES && ref.createdAt >= 0L && ref.createdAt <= Long.MAX_VALUE - RETENTION_MS && ref.expiresAt == ref.createdAt + RETENTION_MS) { "Invalid attachment size or retention time" }
    }
    private fun trimRevokedOwners() {
        if (state.revokedOwners.size <= MAX_OWNERS) return
        val active = state.importingOwners.values.toSet()
        val iterator = state.revokedOwners.iterator()
        while (state.revokedOwners.size > MAX_OWNERS && iterator.hasNext()) {
            if (iterator.next() !in active) iterator.remove()
        }
    }
    private fun derivativeLeaf(value: String): String {
        require(DERIVATIVE_LEAF.matches(value) && !value.startsWith("original.")) { "Invalid attachment preview filename" }
        return value
    }
    private fun safeDisplayName(value: String): String = value.replace(Regex("[\\p{Cntrl}/\\\\]"), " ").trim().take(160).ifBlank { "attachment" }
    private fun normalizeMime(value: String): String = value.substringBefore(';').trim().lowercase().takeIf { MIME.matches(it) } ?: "application/octet-stream"
    private fun extension(name: String, type: String): String = when (type) {
        "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"
        "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"
        else -> name.substringAfterLast('.', "bin").lowercase().takeIf { Regex("[a-z0-9]{1,12}").matches(it) } ?: "bin"
    }

    companion object {
        fun pendingOwner(sessionId: String, inputId: String): String = "queued:$sessionId:$inputId"
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val MAX_ENTRIES = 512
        const val MAX_DERIVATIVE_BYTES = 4L * 1024 * 1024
        private const val MAX_DERIVATIVES = 8
        private const val MAX_OWNERS = 4_096
        private const val MAX_OWNER_REFERENCES = 8_192
        private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
        private const val RELATIVE_ROOT = ".droide/chat-media"
        private val ID = Regex("m-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val ORIGINAL_PATH = Regex("\\.droide/chat-media/blobs/m-[a-f0-9-]{36}/original\\.[a-z0-9]{1,12}")
        private val DERIVATIVE_LEAF = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")
        private val MIME = Regex("[a-z0-9.+-]{1,64}/[a-z0-9.+-]{1,64}")
        private val states = ConcurrentHashMap<String, WeakReference<SharedState>>()
        private fun sharedState(path: String): SharedState = synchronized(states) {
            states[path]?.get()?.let { return@synchronized it }
            if (states.size > 64) states.entries.removeIf { it.value.get() == null }
            SharedState().also { states[path] = WeakReference(it) }
        }
    }
}
