package com.baystudio.droide.core

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** A directory deletion is a namespace move, never permission to release its descendants. */

internal class LocalDirectoryDeleteCustody(
    private val root: File,
    private val checkpoint: (String) -> Unit = {},
    private val moveDirectory: (AnchoredWorkspaceFiles, String, String) -> AnchoredWorkspaceFiles.MoveReceipt =
        { files, source, destination -> files.moveNoReplaceDurable(source, files, destination) },
    private val availableBytes: () -> Long = { root.usableSpace },
) {
    data class Selection(val source: String, val sourceKey: String, val rootKey: String,
                         val parents: List<Pair<String, String>>)
    data class Token(val operation: String, val preparedSha256: String)
    enum class State { PREPARED, MOVED_UNCONFIRMED, RETAINED, RECOVERY_REQUIRED }
    enum class SourceState { ABSENT, ORIGINAL, RECREATED, PARENTS_CHANGED, UNVERIFIABLE }
    enum class ObserverState { NOT_STARTED, ATTEMPTED, COMPLETED, FAILED, INTERRUPTED, UNVERIFIABLE }
    data class Inspection(val operation: String, val source: String?, val retainedPath: String,
                          val token: Token?, val state: State, val sourceState: SourceState,
                          val observer: ObserverState, val detail: String) {
        fun candidate(path: String = retainedPath) = RetainedWorkCandidate(path,
            if (state == State.RECOVERY_REQUIRED) RetainedWorkKind.UNVERIFIABLE else RetainedWorkKind.PRESENT,
            null, detail, RetainedWorkPurpose.DIRECTORY_DELETE, this)
    }
    internal data class Prepared(val id: String, val selection: Selection, val metadataKey: String,
                                val homeKey: String, val operationKey: String, val lockKey: String) {
        val directory get() = "$HOME/$id"
        val original get() = "$directory/original"
    }

    fun select(source: String): Selection = AnchoredWorkspaceFiles(root).use { files ->
        require(source.isNotEmpty() && source.length <= MAX_PATH &&
            ExplorerFilePolicy.requireUnaliasedProjectPath(root, source) == source) { "Choose an ordinary project directory" }
        requireOrdinaryMutation(FileRepository.Mutation.Delete(source), root)
        val parents = parentPaths(source).map { it to files.directoryKey(it) }
        Selection(source, files.directoryKey(source), files.directoryKey(), parents).also { validateSelection(files, it) }
    }

    fun prepare(selection: Selection, guard: WorkspaceMutationGuard): Operation {
        guard.requireRoot(root)
        val files = AnchoredWorkspaceFiles(root)
        try {
            validateSelection(files, selection)
            check(availableBytes() >= MIN_FREE_BYTES) {
                "Directory retention needs at least $MIN_FREE_BYTES free bytes for durable evidence; source was not moved. Retention does not free payload space."
            }
            ensureDirectory(files, ".droide")
            ensureDirectory(files, HOME)
            val id = UUID.randomUUID().toString()
            val directory = "$HOME/$id"
            check(files.createDirectory(directory).durable) { "Cannot durably create exclusive directory custody; source was not moved" }
            files.createNew("$directory/lock").use { it.force(384) }
            val lockKey = readKey(files, "$directory/lock")
            val prepared = Prepared(id, selection, files.directoryKey(".droide"), files.directoryKey(HOME),
                files.directoryKey(directory), lockKey)
            val operation = openOperation(files, prepared, guard)
            try {
                operation.validate(requireSource = true)
                operation.publish("prepared.json", encode(prepared))
                operation.token = Token(id, digest(encode(prepared)))
                checkpoint("prepared")
                operation.validate(requireSource = true)
                return operation
            } catch (failure: Throwable) { operation.close(); throw failure }
        } catch (failure: Throwable) { files.close(); throw failure }
    }

    inner class Operation internal constructor(
        private val files: AnchoredWorkspaceFiles,
        private val prepared: Prepared,
        private val guard: WorkspaceMutationGuard,
        private val lockFile: AnchoredWorkspaceFiles.OpenedFile,
        private val lease: AnchoredWorkspaceFiles.OpenedFile.MetadataLease,
        private val registration: Closeable,
    ) : Closeable {
        lateinit var token: Token; internal set
        val retainedPath get() = prepared.original
        val source get() = prepared.selection.source
        private var closed = false

        internal fun validate(requireSource: Boolean = false) {
            guard.requireRoot(root); lease.requireHeld()
            validateCustody(files, prepared)
            check(lockFile.observe().attributes.fileKey().toString() == prepared.lockKey)
            check(fileKeyAt("${prepared.directory}/lock") == prepared.lockKey) { "Directory custody lock was replaced" }
            if (requireSource) validateSelection(files, prepared.selection)
            if (::token.isInitialized) requirePrepared(files, prepared, token)
        }

        fun move(): Boolean {
            validate(requireSource = true)
            requireSlots(files, prepared)
            check(files.kind(prepared.original) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Directory custody slot is occupied; all data preserved" }
            checkpoint("before-move")
            validate(requireSource = true)
            val receipt = moveDirectory(files, source, prepared.original)
            checkpoint("after-move")
            validate()
            check(receipt.durable) {
                "Directory move durability is unconfirmed (published=${receipt.published}, errno=${receipt.error}); inspect $retainedPath before retry"
            }
            requireMoved(files, prepared, requireOriginalParents = true)
            publishRetained()
            checkpoint("retained")
            return true
        }

        private fun publishRetained() {
            validate(); requireMoved(files, prepared, requireOriginalParents = true)
            publish("retained.json", marker(token, "retained"))
            validate(); requireMoved(files, prepared, requireOriginalParents = true)
        }

        fun observerStarted() {
            validate()
            requireRetained(files, prepared, token)
            check(files.kind("${prepared.directory}/observer-attempt.json") == AnchoredWorkspaceFiles.Kind.ABSENT)
            publish("observer-attempt.json", marker(token, "attempted"))
            checkpoint("observer-attempted")
        }

        fun observerFinished(failure: Throwable?) {
            validate()
            val state = when {
                failure == null -> "completed"
                failure is CancellationException || failure !is Exception -> "interrupted"
                else -> "failed"
            }
            publish("observer-outcome.json", marker(token, state))
            checkpoint("observer-outcome")
        }

        internal fun publish(name: String, bytes: ByteArray) {
            check(bytes.size <= MAX_RECORD_BYTES)
            validateCustody(files, prepared)
            files.createNew("${prepared.directory}/$name").use { output -> output.write(bytes); output.force(384) }
            check(read(files, "${prepared.directory}/$name").contentEquals(bytes)) { "Directory custody evidence changed during publication" }
            validateCustody(files, prepared)
        }

        /** Reconfirmation appends a retained receipt only for the exact already-moved inode. It never
         * renames an existing/recreated source, and never replays the observer. */
        internal fun reconfirm(): Inspection {
            validate()
            val before = inspectOne(files, prepared.id, token)
            if (before.state == State.MOVED_UNCONFIRMED) {
                requireMoved(files, prepared, requireOriginalParents = true)
                checkpoint("before-reconfirm")
                validate(); requireMoved(files, prepared, requireOriginalParents = true)
                files.openDirectory(prepared.selection.source.substringBeforeLast('/', "")).use { it.forceDirectory() }
                files.openDirectory(prepared.directory).use { it.forceDirectory() }
                files.forceDirectory()
                requireMoved(files, prepared, requireOriginalParents = true)
                publishRetained()
            } else if (before.state == State.RETAINED) {
                // Existing receipt may have survived a lost force/return. Confirm this inode, not a different
                // operation, and force the evidence and namespace again.
                requireRetained(files, prepared, token)
                files.openRead("${prepared.directory}/retained.json").use { it.forceContents() }
                files.openDirectory(prepared.directory).use { it.forceDirectory() }
                files.forceDirectory()
            }
            return inspectOne(files, prepared.id, token)
        }

        override fun close() {
            if (closed) return
            closed = true
            try { lease.close() } finally { try { lockFile.close() } finally { try { files.close() } finally { registration.close() } } }
        }
    }

    private fun openOperation(files: AnchoredWorkspaceFiles, prepared: Prepared, guard: WorkspaceMutationGuard): Operation {
        // Register BEFORE opening this inode. Closing a failed contender's descriptor can release
        // another descriptor's POSIX process lock, even when FileLock remains valid.
        val registration = LocalRecoveryMetadataLocks.claim(prepared.selection.rootKey, prepared.lockKey)
        var input: AnchoredWorkspaceFiles.OpenedFile? = null
        try {
            input = files.openRead("${prepared.directory}/lock")
            check(input.observe().attributes.size() == 0L && input.observe().attributes.fileKey().toString() == prepared.lockKey)
            val lease = input.acquireExclusiveMetadataLease()
            return try { Operation(files, prepared, guard, input, lease, registration) }
            catch (failure: Throwable) { lease.close(); throw failure }
        } catch (failure: Throwable) { try { input?.close() } finally { registration.close() }; throw failure }
    }

    /** Read-only custody ownership survives release of the workspace mutex. Other owners use root ->
     * nonblocking metadata lease order. No live mutation guard is retained. */
    inner class RecoveryLease internal constructor(
        private val files: AnchoredWorkspaceFiles,
        private val prepared: Prepared,
        val token: Token,
        private val input: AnchoredWorkspaceFiles.OpenedFile,
        private val lease: AnchoredWorkspaceFiles.OpenedFile.MetadataLease,
        private val registration: Closeable,
    ) : Closeable {
        val retainedPath get() = prepared.original
        private var closed = false
        fun validate(): Inspection {
            check(!closed); lease.requireHeld()
            validateCustody(files, prepared); requirePrepared(files, prepared, token)
            check(input.observe().attributes.fileKey().toString() == prepared.lockKey)
            return inspectOne(files, prepared.id, token).also {
                check(it.state == State.RETAINED) { "Folder custody is not confirmed; preserve all locations and inspect again" }
            }
        }
        override fun close() {
            if (closed) return
            closed = true
            try { lease.close() } finally { try { input.close() } finally { try { files.close() } finally { registration.close() } } }
        }
    }

    fun acquireRecovery(token: Token, guard: WorkspaceMutationGuard): RecoveryLease {
        guard.requireRoot(root); requireId(token.operation); requireDigest(token.preparedSha256)
        val files = AnchoredWorkspaceFiles(root)
        var registration: Closeable? = null
        var input: AnchoredWorkspaceFiles.OpenedFile? = null
        try {
            val prepared = decode(read(files, "$HOME/${token.operation}/prepared.json"))
            requirePrepared(files, prepared, token); validateCustody(files, prepared)
            registration = LocalRecoveryMetadataLocks.claim(prepared.selection.rootKey, prepared.lockKey)
            input = files.openRead("${prepared.directory}/lock")
            check(input.observe().attributes.size() == 0L && input.observe().attributes.fileKey().toString() == prepared.lockKey)
            val lease = input.acquireExclusiveMetadataLease()
            val result = RecoveryLease(files, prepared, token, input, lease, registration)
            try { result.validate(); return result } catch (failure: Throwable) { result.close(); throw failure }
        } catch (failure: Throwable) {
            try { input?.close() } finally { try { files.close() } finally { registration?.close() } }
            throw failure
        }
    }

    fun reconcile(token: Token, guard: WorkspaceMutationGuard): Inspection {
        guard.requireRoot(root); requireId(token.operation); requireDigest(token.preparedSha256)
        val files = AnchoredWorkspaceFiles(root)
        try {
            val prepared = decode(read(files, "$HOME/${token.operation}/prepared.json"))
            check(prepared.id == token.operation)
            requirePrepared(files, prepared, token)
            val operation = openOperation(files, prepared, guard)
            operation.token = token
            return operation.use { it.reconfirm() }
        } catch (failure: Throwable) { files.close(); throw failure }
    }

    fun inspect(operation: String, expected: Token? = null): Inspection {
        requireId(operation)
        return try { AnchoredWorkspaceFiles(root).use { inspectOne(it, operation, expected) } }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { problem(operation, failure) }
    }

    /** Bounded direct-folder discovery. Unknown directories/files are reported, never adopted. An
     * operation can always be inspected directly, even beyond this listing's scan budget. */
    fun inspectDirectory(directory: String): RetainedWorkInventory {
        require(directory == HOME || directory.startsWith("$HOME/") && '/' !in directory.removePrefix("$HOME/"))
        val candidates = ArrayList<RetainedWorkCandidate>()
        var problem: String? = null
        try {
            AnchoredWorkspaceFiles(root).use { files ->
                if (directory == HOME) {
                    if (files.kind(HOME) == AnchoredWorkspaceFiles.Kind.ABSENT)
                        return RetainedWorkInventory(directory, emptyList(), true, null)
                    var scanned = 0
                    files.forEachChild(HOME) { id ->
                        check(++scanned <= MAX_SCAN && candidates.size < MAX_INSPECT) {
                            "Directory custody inspection is partial; inspect a known operation folder directly. No evidence was removed."
                        }
                        val result = try { requireId(id); inspectOne(files, id, null) }
                        catch (failure: Exception) { problem(id, failure) }
                        candidates += result.candidate("$HOME/$id")
                        if (result.state == State.RECOVERY_REQUIRED && problem == null) problem = result.detail
                    }
                } else {
                    val id = directory.removePrefix("$HOME/")
                    requireId(id)
                    val result = inspectOne(files, id, null)
                    candidates += result.candidate()
                    if (result.state == State.RECOVERY_REQUIRED) problem = result.detail
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { problem = (failure.message ?: "Directory custody inspection unavailable").take(500) }
        return RetainedWorkInventory(directory, candidates, problem == null, problem)
    }

    private fun inspectOne(files: AnchoredWorkspaceFiles, id: String, expected: Token?): Inspection {
        requireId(id)
        val bytes = read(files, "$HOME/$id/prepared.json")
        val prepared = decode(bytes)
        check(prepared.id == id)
        val token = Token(id, digest(bytes))
        check(expected == null || token == expected) { "Directory delete receipt does not match this operation" }
        validateCustody(files, prepared); requirePrepared(files, prepared, token); requireSlots(files, prepared)
        val source = sourceState(files, prepared.selection)
        val slot = files.kind(prepared.original)
        val exact = slot == AnchoredWorkspaceFiles.Kind.DIRECTORY && files.directoryKey(prepared.original) == prepared.selection.sourceKey
        val retained = files.kind("${prepared.directory}/retained.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        if (retained) requireRetained(files, prepared, token)
        val observer = observerState(files, prepared, token, retained)
        val state = when {
            retained && exact && source != SourceState.ORIGINAL -> State.RETAINED
            !retained && slot == AnchoredWorkspaceFiles.Kind.ABSENT && source == SourceState.ORIGINAL -> State.PREPARED
            !retained && exact && source == SourceState.ABSENT -> State.MOVED_UNCONFIRMED
            else -> State.RECOVERY_REQUIRED
        }
        validateCustody(files, prepared); requirePrepared(files, prepared, token)
        if (retained) requireRetained(files, prepared, token)
        check(sourceState(files, prepared.selection) == source && files.kind(prepared.original) == slot &&
            (!exact || files.directoryKey(prepared.original) == prepared.selection.sourceKey)) {
            "Directory custody changed during inspection; preserve all locations and inspect again"
        }
        val synchronization = when (observer) {
            ObserverState.COMPLETED -> "completed"
            ObserverState.NOT_STARTED -> "pending (not started)"
            ObserverState.ATTEMPTED -> "pending (attempt outcome unconfirmed)"
            ObserverState.FAILED -> "pending (last attempt failed)"
            ObserverState.INTERRUPTED -> "pending (interrupted)"
            ObserverState.UNVERIFIABLE -> "unverifiable"
        }
        val detail = when (state) {
            State.RETAINED -> "Folder deletion retained at ${prepared.original}. Original path: ${prepared.selection.source}. " +
                "Current source: ${source.name.lowercase()}. Synchronization: $synchronization. " +
                "Descendants may still change; disk space has not been released."
            State.PREPARED -> "Prepared folder deletion has not committed; the selected source remains. Evidence: ${prepared.directory}."
            State.MOVED_UNCONFIRMED -> "Folder is retained at ${prepared.original}, but namespace durability needs explicit receipt reconciliation. Do not repeat path deletion."
            State.RECOVERY_REQUIRED -> "Folder deletion needs recovery review at ${prepared.directory} and ${prepared.selection.source}; all evidence was preserved."
        }
        return Inspection(id, prepared.selection.source, prepared.original, token, state, source, observer, detail)
    }

    private fun observerState(files: AnchoredWorkspaceFiles, p: Prepared, token: Token, retained: Boolean): ObserverState {
        val attempt = files.kind("${p.directory}/observer-attempt.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        val outcome = files.kind("${p.directory}/observer-outcome.json") != AnchoredWorkspaceFiles.Kind.ABSENT
        check(!attempt || retained); check(!outcome || attempt)
        if (attempt) requireMarker(files, p, "observer-attempt.json", token, setOf("attempted"))
        return if (outcome) when (requireMarker(files, p, "observer-outcome.json", token, setOf("completed", "failed", "interrupted"))) {
            "completed" -> ObserverState.COMPLETED
            "failed" -> ObserverState.FAILED
            else -> ObserverState.INTERRUPTED
        } else if (attempt) ObserverState.ATTEMPTED else ObserverState.NOT_STARTED
    }

    private fun requireRetained(files: AnchoredWorkspaceFiles, p: Prepared, token: Token) {
        requireMarker(files, p, "retained.json", token, setOf("retained"))
        check(files.kind(p.original) == AnchoredWorkspaceFiles.Kind.DIRECTORY && files.directoryKey(p.original) == p.selection.sourceKey) {
            "Retained directory identity changed; all evidence was preserved"
        }
    }
    private fun requireMarker(files: AnchoredWorkspaceFiles, p: Prepared, name: String, token: Token, states: Set<String>): String {
        val json = Json.parseToJsonElement(read(files, "${p.directory}/$name").toString(Charsets.UTF_8)).jsonObject
        check(json.keys == setOf("schema", "operation", "preparedSha256", "state") && json["schema"]?.jsonPrimitive?.int == 1)
        check(json.string("operation") == token.operation && json.string("preparedSha256") == token.preparedSha256)
        return json.string("state").also { check(it in states) }
    }
    private fun requirePrepared(files: AnchoredWorkspaceFiles, p: Prepared, token: Token) {
        val bytes = read(files, "${p.directory}/prepared.json")
        check(token.operation == p.id && digest(bytes) == token.preparedSha256 && decode(bytes) == p) { "Prepared directory deletion evidence changed" }
    }
    private fun validateCustody(files: AnchoredWorkspaceFiles, p: Prepared) {
        check(files.directoryKey() == p.selection.rootKey && files.directoryKey(".droide") == p.metadataKey &&
            files.directoryKey(HOME) == p.homeKey && files.directoryKey(p.directory) == p.operationKey &&
            fileKeyAt("${p.directory}/lock") == p.lockKey) { "Workspace or directory custody identity changed; evidence preserved" }
    }
    private fun validateSelection(files: AnchoredWorkspaceFiles, s: Selection) {
        check(files.directoryKey() == s.rootKey && ExplorerFilePolicy.requireUnaliasedProjectPath(root, s.source) == s.source)
        requireParents(files, s)
        check(files.directoryKey(s.source) == s.sourceKey) { "Selected folder identity changed; choose the folder again" }
    }
    private fun requireParents(files: AnchoredWorkspaceFiles, s: Selection) {
        s.parents.forEach { (path, key) -> check(files.directoryKey(path) == key) { "Source parent changed; data preserved" } }
    }
    private fun requireMoved(files: AnchoredWorkspaceFiles, p: Prepared, requireOriginalParents: Boolean) {
        if (requireOriginalParents) requireParents(files, p.selection)
        check(files.kind(p.selection.source) == AnchoredWorkspaceFiles.Kind.ABSENT &&
            files.kind(p.original) == AnchoredWorkspaceFiles.Kind.DIRECTORY && files.directoryKey(p.original) == p.selection.sourceKey) {
            "Source/custody identities are ambiguous; inspect both paths without retrying the move"
        }
    }
    private fun sourceState(files: AnchoredWorkspaceFiles, s: Selection): SourceState = try {
        if (s.parents.any { (path, key) -> files.directoryKey(path) != key }) SourceState.PARENTS_CHANGED
        else when (files.kind(s.source)) {
            AnchoredWorkspaceFiles.Kind.ABSENT -> SourceState.ABSENT
            AnchoredWorkspaceFiles.Kind.DIRECTORY -> if (files.directoryKey(s.source) == s.sourceKey) SourceState.ORIGINAL else SourceState.RECREATED
            else -> SourceState.RECREATED
        }
    } catch (_: Exception) { SourceState.PARENTS_CHANGED }

    private fun requireSlots(files: AnchoredWorkspaceFiles, p: Prepared) {
        var count = 0
        files.forEachChild(p.directory) { check(++count <= 6 && it in SLOTS) { "Unknown directory custody record slot; evidence preserved" } }
    }
    private fun ensureDirectory(files: AnchoredWorkspaceFiles, path: String) {
        if (files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT)
            check(files.createDirectory(path).durable) { "Directory custody namespace durability is unavailable; source was not moved" }
        files.directoryKey(path)
    }
    private fun readKey(files: AnchoredWorkspaceFiles, path: String) = files.openRead(path).use { checkNotNull(it.observe().attributes.fileKey()).toString() }
    private fun fileKeyAt(path: String): String {
        val a = Files.readAttributes(File(root, path).toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(a.isRegularFile && !a.isSymbolicLink && a.size() == 0L)
        check((Files.getAttribute(File(root, path).toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() == 1L) { "Dedicated recovery lock has aliases; evidence preserved" }
        return checkNotNull(a.fileKey()).toString()
    }
    private fun read(files: AnchoredWorkspaceFiles, path: String): ByteArray = files.openRead(path).use { input ->
        val before = input.observe()
        check(before.attributes.size() in 1..MAX_RECORD_BYTES.toLong()) { "Directory custody metadata size is invalid" }
        val bytes = ByteArray(before.attributes.size().toInt())
        var count = 0
        while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); check(n > 0); count += n }
        check(input.read() == -1)
        val after = input.observe()
        files.openRead(path).use { current ->
            check(sameVersion(before, after) && sameVersion(after, current.observe())) { "Directory custody metadata changed while reading" }
        }
        bytes
    }
    private fun sameVersion(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation) =
        a.attributes.fileKey() == b.attributes.fileKey() && a.attributes.size() == b.attributes.size() &&
            a.attributes.lastModifiedTime() == b.attributes.lastModifiedTime() && a.changed == b.changed && a.executable == b.executable

    private fun encode(p: Prepared): ByteArray = buildJsonObject {
        put("schema", 1); put("operation", p.id); put("source", p.selection.source); put("sourceKey", p.selection.sourceKey)
        put("rootKey", p.selection.rootKey); put("metadataKey", p.metadataKey); put("homeKey", p.homeKey)
        put("operationKey", p.operationKey); put("lockKey", p.lockKey)
        putJsonArray("parents") { p.selection.parents.forEach { (path, key) -> addJsonObject { put("path", path); put("key", key) } } }
    }.toString().toByteArray(Charsets.UTF_8)
    private fun decode(bytes: ByteArray): Prepared {
        val j = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(j.keys == setOf("schema", "operation", "source", "sourceKey", "rootKey", "metadataKey", "homeKey", "operationKey", "lockKey", "parents"))
        check(j["schema"]?.jsonPrimitive?.int == 1)
        val id = j.string("operation").also(::requireId)
        val source = j.string("source").also { require(it.length in 1..MAX_PATH && it != ".droide" && it != HOME && !it.startsWith("$HOME/") &&
            !it.startsWith('/') && '\\' !in it && '\u0000' !in it && it.split('/').all { part -> part.isNotEmpty() && part != "." && part != ".." }) }
        fun key(name: String) = j.string(name).also { check(it.length in 1..512) }
        val parents = j["parents"]!!.jsonArray.also { check(it.size in 1..MAX_PARENTS) }.map {
            val entry = it.jsonObject; check(entry.keys == setOf("path", "key"))
            entry.string("path") to entry.string("key").also { key -> check(key.length in 1..512) }
        }
        check(parents.map { it.first } == parentPaths(source))
        return Prepared(id, Selection(source, key("sourceKey"), key("rootKey"), parents),
            key("metadataKey"), key("homeKey"), key("operationKey"), key("lockKey"))
    }
    private fun marker(token: Token, state: String) = buildJsonObject {
        put("schema", 1); put("operation", token.operation); put("preparedSha256", token.preparedSha256); put("state", state)
    }.toString().toByteArray(Charsets.UTF_8)
    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.let { check(it.isString); it.content }
    private fun problem(id: String, failure: Exception) = Inspection(id, null, "$HOME/$id/original", null,
        State.RECOVERY_REQUIRED, SourceState.UNVERIFIABLE, ObserverState.UNVERIFIABLE,
        "Directory custody needs recovery review at $HOME/$id. ${(failure.message ?: "Unverifiable evidence").take(300)}")
    private fun parentPaths(source: String): List<String> {
        val parts = source.split('/'); require(parts.size <= MAX_PARENTS)
        return listOf("") + (1 until parts.size).map { parts.take(it).joinToString("/") }
    }

    companion object {
        const val HOME = ".droide/local-delete-custody"
        const val MAX_RECORD_BYTES = 32_768
        const val MAX_PATH = 4096
        const val MAX_PARENTS = 128
        const val MAX_INSPECT = 64
        const val MAX_SCAN = 20_000
        const val MIN_FREE_BYTES = 1_048_576L
        private val SLOTS = setOf("lock", "prepared.json", "original", "retained.json", "observer-attempt.json", "observer-outcome.json")
        private fun requireId(id: String) { require(UUID.fromString(id).toString() == id) }
        private fun requireDigest(digest: String) { require(digest.matches(Regex("[a-f0-9]{64}"))) }
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun requireOrdinaryMutation(mutation: FileRepository.Mutation, root: File) {
            fun checkPath(path: String) {
                fun requireOutside(value: String) { require(value != ".droide" && value != HOME && !value.startsWith("$HOME/") &&
                    value != LocalDirectoryArchiveJournal.HOME && !value.startsWith("${LocalDirectoryArchiveJournal.HOME}/")) {
                    "Retained directory custody is protected; inspect its receipt instead of mutating or releasing it"
                } }
                requireOutside(path)
                requireOutside(ExplorerFilePolicy.normalizeProjectPath(root, path))
            }
            when (mutation) {
                is FileRepository.Mutation.Delete -> checkPath(mutation.path)
                is FileRepository.Mutation.Move -> { checkPath(mutation.from); checkPath(mutation.to) }
                is FileRepository.Mutation.Write -> checkPath(mutation.path)
                is FileRepository.Mutation.CreateDirectory -> if (mutation.path != ".droide") checkPath(mutation.path)
                is FileRepository.Mutation.Sync -> Unit
            }
        }
    }
}
