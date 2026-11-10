package com.baystudio.droide.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Trusted host paths only. */

internal class SourceArtifactPublisher(
    workDir: File,
    private val privateRoot: Path,
    private val admission: OwnedResponseContentStore.DiskAdmission,
    private val publisher: Publication = Publication.platform,
) {
    fun interface Publication {
        fun move(sourceRoot: Path, source: String, targetRoot: Path, destination: String): AnchoredWorkspaceFiles.MoveReceipt
        companion object {
            val platform = Publication { sourceRoot, source, targetRoot, destination ->
                if (System.getProperty("java.vm.name") == "Dalvik" || System.getProperty("java.runtime.name") == "Android Runtime") {
                    AnchoredWorkspaceFiles(sourceRoot.toFile()).use { from ->
                        AnchoredWorkspaceFiles(targetRoot.toFile()).use { to -> from.moveNoReplaceDurable(source, to, destination) }
                    }
                } else {
                    // Host tooling only; never an Android fallback when JNI is unavailable.
                    Files.createLink(targetRoot.resolve(destination), sourceRoot.resolve(source))
                    Files.delete(sourceRoot.resolve(source))
                    FileChannel.open(targetRoot.resolve(destination).parent, READ).use { it.force(true) }
                    AnchoredWorkspaceFiles.MoveReceipt(true, 0)
                }
            }
        }
    }
    private val root = workDir.toPath().toAbsolutePath().normalize()
    private val rootKey = directoryKey(root)
    private val privateKey = directoryKey(privateRoot)
    private val artifacts get() = root.resolve(".droide/artifacts")
    class Observation internal constructor(
        val operationId: String, val owner: OwnedResponseContentStore.Owner,
        val argumentsSha256: String, val originalType: String, val rootIdentity: String,
        val id: String, val path: String, val bytes: Long, val sha256: String,
        internal val contentSha256: String,
        internal val session: OwnedResponseContentStore.Session?,
        internal val content: OwnedResponseContentStore.Handle?,
    )
    class Receipt internal constructor(val observation: Observation, val durabilityConfirmed: Boolean,
        val currentContentVerified: Boolean)
    class PublicationFailure(val operationId: String, val receipt: Receipt?, cause: Exception) :
        IOException("Artifact publication needs reconciliation; do not create a replacement operation", cause)
    sealed class Recovery {
        object Reserved : Recovery()
        class Prepared(val observation: Observation) : Recovery()
        class Published(val receipt: Receipt) : Recovery()
        class Historical(val observation: Observation, val receipt: Receipt) : Recovery()
    }

    /** Private reservation only, no project writes. A second prepare may attach the same verified
     * source after process restart; publication still requires a fresh authority callback. */
    fun prepare(operationId: String, owner: OwnedResponseContentStore.Owner, argumentsSha256: String,
        originalType: String, session: OwnedResponseContentStore.Session,
        content: OwnedResponseContentStore.Handle, checkCurrent: () -> Unit = {}): Observation {
        checkCurrent(); roots(); validId(operationId); digest(argumentsSha256)
        require(originalType.length <= 4096) { "Artifact type metadata exceeds 4096 UTF16 units" }
        Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(originalType))
        val stored = session.openCompleted(content, checkCurrent).content
        require(stored.kind == OwnedResponseContentStore.Kind.TOOL_CONTENT)
        val md = MessageDigest.getInstance("SHA-256")
        val header = header(originalType); md.update(header)
        var length = header.size.toLong()
        session.openBytes(content, checkCurrent).use { input ->
            val buffer = ByteArray(65536); var received = 0L
            while (true) { checkCurrent(); val n = input.read(buffer); if (n < 0) break
                check(n > 0); received = Math.addExact(received, n.toLong()); check(received <= stored.byteLength)
                length = Math.addExact(length, n.toLong()); md.update(buffer, 0, n) }
            check(received == stored.byteLength)
        }
        val binding = listOf(owner.sessionId, owner.runId, owner.callId, argumentsSha256,
            originalType, rootKey, stored.sha256, length.toString(), hex(md.digest()))
        val op = privateRoot.resolve(operationId)
        if (!Files.exists(op, NOFOLLOW_LINKS)) {
            admission.reserve(4096).use { Files.createDirectory(op) }; sync(privateRoot)
        }
        return withLock(op, createLock = true) {
            val reservation = op.resolve("reservation")
            if (Files.exists(reservation, NOFOLLOW_LINKS)) check(readRecord(reservation) == binding) { "Artifact operation identity mismatch" }
            else writeRecord(reservation, binding)
            checkCurrent(); observation(operationId, binding, session, content)
        }
    }

    /** One registration spans every project stage write and publication. Callback is not needed for
     * read-only reconciliation of a prior publication; it MUST run before any fresh stage. */
    fun publish(observation: Observation, beforePublication: () -> Unit,
        checkCurrent: () -> Unit = {}): Receipt {
            val op = privateRoot.resolve(observation.operationId)
            return withLock(op) {
                var attempted = false
                try {
                    checkCurrent(); roots(); verifyRecord(observation)
                    val readyPath = op.resolve("ready")
                    if (Files.exists(readyPath, NOFOLLOW_LINKS)) {
                        val recovered = inspectUnlocked(observation, checkCurrent)
                        if (recovered is Recovery.Published) return@withLock recovered.receipt
                        check(recovered !is Recovery.Historical) { "Artifact was previously published but changed or removed; never recreate" }
                        // Existing stage after interruption is not implicitly restarted.
                        error("Artifact staging already exists; explicit recovery required, no automatic publication")
                    }
                    check(!Files.exists(stage(observation), NOFOLLOW_LINKS)) { "Unfinished artifact stage retained; no automatic retry" }
                    beforePublication(); checkCurrent(); roots()
                    val session = requireNotNull(observation.session) { "Reattach validated content with prepare" }
                    val content = requireNotNull(observation.content)
                    val stored = session.openCompleted(content, checkCurrent).content
                    check(stored.kind == OwnedResponseContentStore.Kind.TOOL_CONTENT && stored.sha256 == observation.contentSha256)
                    check(!Files.exists(target(observation), NOFOLLOW_LINKS)) { "Artifact target already exists" }
                    admission.reserve(4096).use {
                        FileChannel.open(stage(observation), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
                            fun append(bytes: ByteArray, count: Int) {
                                checkCurrent(); roots(); directoryKey(op)
                                admission.reserve(count.toLong()).use {
                                    val buffer = ByteBuffer.wrap(bytes, 0, count)
                                    while (buffer.hasRemaining()) { checkCurrent(); check(out.write(buffer) > 0) }
                                }
                            }
                            val h = header(observation.originalType); append(h, h.size)
                            session.openBytes(content, checkCurrent).use { input ->
                                val buffer = ByteArray(65536)
                                while (true) { checkCurrent(); val n = input.read(buffer); if (n < 0) break
                                    check(n > 0); append(buffer, n) }
                            }
                            out.force(true)
                        }
                    }
                    sync(op)
                    verifyBytes(stage(observation), observation.bytes, observation.sha256, checkCurrent)
                    writeRecord(readyPath, listOf(rootKey, fileKey(stage(observation))))
                    ExportWriterAdmission.withWriter(root.toFile(), "Agent artifact source publication") {
                        checkCurrent(); roots(); beforePublication(); checkCurrent()
                        createDirectory(root.resolve(".droide")); createDirectory(artifacts)
                        writeRecord(op.resolve("destination"), listOf(directoryKey(artifacts)))
                        verifyStage(observation, checkCurrent)
                        attempted = true
                        val result = publisher.move(op, "payload", root, observation.path)
                        check(result.published) { "Artifact publication failed without replacement (errno=${result.error})" }
                        check(result.durable) { "Artifact published but directory durability unconfirmed (errno=${result.error})" }
                        verifiedReceipt(observation, {})
                        writeRecord(op.resolve("committed"), committedBinding(observation))
                        val receipt = verifiedReceipt(observation, {})
                        checkCurrent()
                        receipt
                    }
                } catch (failure: Exception) {
                    if (!attempted) throw failure
                    val receipt = try { settlementReceipt(observation, {}) } catch (_: Exception) { null }
                    throw PublicationFailure(observation.operationId, receipt, failure)
                }
            }
        }

    fun inspect(operationId: String, owner: OwnedResponseContentStore.Owner, argumentsSha256: String,
        checkCurrent: () -> Unit = {}): Recovery {
        roots(); validId(operationId); digest(argumentsSha256)
        val op = privateRoot.resolve(operationId)
        return withLock(op) {
            val binding = readRecord(op.resolve("reservation"))
            check(binding.size == 9 && binding.take(4) == listOf(owner.sessionId, owner.runId, owner.callId, argumentsSha256))
            check(binding[5] == rootKey)
            if (!Files.exists(op.resolve("ready"), NOFOLLOW_LINKS)) Recovery.Reserved
            else inspectUnlocked(observation(operationId, binding, null, null), checkCurrent)
        }
    }
    private fun inspectUnlocked(o: Observation, check: () -> Unit): Recovery {
        roots(); verifyRecord(o)
        val committed = privateRoot.resolve(o.operationId).resolve("committed")
        if (Files.exists(committed, NOFOLLOW_LINKS)) {
            check(readRecord(committed) == committedBinding(o))
            return try { Recovery.Published(verifiedReceipt(o, check)) }
            catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { Recovery.Historical(o, Receipt(o, true, false)) }
        }
        return if (Files.exists(target(o), NOFOLLOW_LINKS)) Recovery.Published(verifiedReceipt(o, check))
            else { verifyStage(o, check); Recovery.Prepared(o) }
    }
    private fun verifiedReceipt(o: Observation, check: () -> Unit): Receipt {
        roots(); verifyRecord(o)
        val ready = readRecord(privateRoot.resolve(o.operationId).resolve("ready"))
        check(ready.size == 2 && ready[0] == rootKey)
        val destination = readRecord(privateRoot.resolve(o.operationId).resolve("destination"))
        check(destination == listOf(directoryKey(artifacts)))
        check(fileKey(target(o)) == ready[1]) { "Artifact target has no attributable publication identity" }
        verifyBytes(target(o), o.bytes, o.sha256, check)
        val committed = privateRoot.resolve(o.operationId).resolve("committed")
        val durable = Files.exists(committed, NOFOLLOW_LINKS) && readRecord(committed) == committedBinding(o)
        return Receipt(o, durable, true)
    }
    /** Historical settlement proof is separate from a claim about currently visible bytes. COMMITTED
     * binds private reservation + READY inode + destination directory identity. */
    fun verify(receipt: Receipt, checkCurrent: () -> Unit = {}): Receipt =
        withLock(privateRoot.resolve(receipt.observation.operationId)) { settlementReceipt(receipt.observation, checkCurrent) }

    private fun settlementReceipt(o: Observation, checkCurrent: () -> Unit): Receipt {
        checkCurrent(); roots(); verifyRecord(o)
        val committed = privateRoot.resolve(o.operationId).resolve("committed")
        if (!Files.exists(committed, NOFOLLOW_LINKS)) return verifiedReceipt(o, checkCurrent)
        check(readRecord(committed) == committedBinding(o)) { "Artifact commit proof mismatch" }
        return try { verifiedReceipt(o, checkCurrent) }
        catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) { Receipt(o, true, false) }
    }

    private fun committedBinding(o: Observation): List<String> {
        verifyRecord(o)
        val op = privateRoot.resolve(o.operationId)
        val ready = readRecord(op.resolve("ready")); val destination = readRecord(op.resolve("destination"))
        check(ready.size == 2 && ready[0] == rootKey && destination.size == 1)
        return listOf(o.sha256, ready[1], destination[0], o.path, o.argumentsSha256, o.contentSha256,
            o.rootIdentity, o.owner.sessionId, o.owner.runId, o.owner.callId)
    }

    private fun verifyRecord(o: Observation) {
        validId(o.operationId)
        val actual = observation(o.operationId, readRecord(privateRoot.resolve(o.operationId).resolve("reservation")), null, null)
        check(listOf(actual.owner, actual.argumentsSha256, actual.originalType, actual.rootIdentity, actual.bytes,
            actual.sha256, actual.contentSha256, actual.id, actual.path) ==
            listOf(o.owner, o.argumentsSha256, o.originalType, o.rootIdentity, o.bytes,
                o.sha256, o.contentSha256, o.id, o.path)) { "Artifact observation mismatch" }
    }
    private fun verifyStage(o: Observation, check: () -> Unit) {
        roots(); val ready = readRecord(privateRoot.resolve(o.operationId).resolve("ready"))
        check(ready.size == 2 && o.rootIdentity == rootKey && ready[0] == rootKey)
        check(fileKey(stage(o)) == ready[1]); verifyBytes(stage(o), o.bytes, o.sha256, check)
    }
    private fun observation(id: String, b: List<String>, session: OwnedResponseContentStore.Session?,
        content: OwnedResponseContentStore.Handle?): Observation {
        check(b.size == 9 && b[5] == rootKey); digest(b[3]); digest(b[6]); digest(b[8])
        val n = b[7].toLong(); check(n >= 0)
        return Observation(id, OwnedResponseContentStore.Owner(b[0], b[1], b[2]), b[3], b[4], b[5],
            "a-$id", ".droide/artifacts/a-$id.md", n, b[8], b[6], session, content)
    }
    private fun header(type: String): ByteArray {
        val limited = type.trim().ifBlank { "artifact" }.take(80).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        val safe = limited.replace(Regex("[\\r\\n]"), " ")
        return "# $safe\n\n".toByteArray(Charsets.UTF_8)
    }
    private fun stage(o: Observation) = privateRoot.resolve(o.operationId).resolve("payload")
    private fun target(o: Observation) = artifacts.resolve("${o.id}.md")
    private fun roots() { check(directoryKey(root) == rootKey && directoryKey(privateRoot) == privateKey) }
    private fun createDirectory(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) { admission.reserve(4096).use { Files.createDirectory(path) }; sync(path.parent) }
        directoryKey(path)
    }
    private fun writeRecord(path: Path, values: List<String>) {
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { d ->
            d.writeInt(values.size); values.forEach { d.writeUTF(it) }
        } }.toByteArray()
        require(bytes.size <= 32768)
        admission.reserve(bytes.size.toLong() + 4096).use {
            FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) check(channel.write(b) > 0)
                channel.force(true)
            }
        }; sync(path.parent)
    }
    private fun readRecord(path: Path): List<String> {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= 32768)
        return DataInputStream(Files.newInputStream(path, NOFOLLOW_LINKS)).use { d ->
            val count = d.readInt(); check(count in 1..10)
            List(count) { d.readUTF() }.also { check(d.read() == -1) }
        }
    }
    private fun <T> withLock(op: Path, createLock: Boolean = false, body: () -> T): T {
        roots(); require(op.parent == privateRoot); validId(op.fileName.toString()); directoryKey(op)
        val path = op.resolve("lock")
        if (createLock && !Files.exists(path, NOFOLLOW_LINKS)) {
            admission.reserve(4096).use { FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { it.force(true) } }
            sync(op)
        }
        // Existing lock only: inspect does not allocate or reserve disk on a full filesystem.
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { channel -> val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            checkNotNull(lock) { "Artifact operation is busy; no publication attempted by this invocation" }.use { return body() } }
    }
    private fun verifyBytes(path: Path, expected: Long, hash: String, check: () -> Unit) {
        val md = MessageDigest.getInstance("SHA-256"); var n = 0L
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val b = ByteArray(65536)
            while (true) { check(); val k = input.read(b); if (k < 0) break
                check(k > 0); n = Math.addExact(n, k.toLong()); check(n <= expected); md.update(b, 0, k) }
        }
        check(n == expected && hex(md.digest()) == hash) { "Artifact integrity mismatch" }
    }
    private fun sync(path: Path) { FileChannel.open(path, READ).use { it.force(true) } }
    private fun fileKey(path: Path): String {
        val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(a.isRegularFile); return requireNotNull(a.fileKey()).toString()
    }
    private fun directoryKey(path: Path): String {
        require(path.isAbsolute && path == path.normalize())
        var p: Path? = path
        while (p != null) { check(!Files.isSymbolicLink(p)); p = p.parent }
        val a = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(a.isDirectory); return requireNotNull(a.fileKey()).toString()
    }
    private fun validId(id: String) { require(UUID.fromString(id).toString() == id) }
    private fun digest(value: String) { require(Regex("[0-9a-f]{64}").matches(value)) }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
