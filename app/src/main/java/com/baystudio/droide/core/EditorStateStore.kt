package com.baystudio.droide.core

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Never lives inside a project and never participates in Git/SAF sync.
data class EditorSelectionSnapshot(val start: Int, val end: Int)

class EditorRecoveryBuffer internal constructor(
    val path: String,
    internal val blobName: String,
    internal val contentSha256: String?,
    internal val owner: Any,
    internal val maxContentBytes: Long? = null,
    val diskVersion: DiskFileVersion? = null,
    private val loader: suspend () -> String,
) {
    suspend fun readText(): String = loader()
}

data class EditorRecoverySnapshot(
    val openFiles: List<String>,
    val activeFile: String,
    val dirtyBuffers: Map<String, String>,
    val selections: Map<String, EditorSelectionSnapshot> = emptyMap(),
    val reviewFiles: Set<String> = emptySet(),
    val deferredBuffers: Map<String, EditorRecoveryBuffer> = emptyMap(),
    val conflictBuffers: Map<String, String> = emptyMap(),
    val deferredConflictBuffers: Map<String, EditorRecoveryBuffer> = emptyMap(),

    val diskVersions: Map<String, DiskFileVersion> = emptyMap(),
    val conflictDiskVersions: Map<String, DiskFileVersion> = emptyMap(),
    val conflictRevision: EditorRecoveryConflictAuthority.Revision? = null,
) {
    val dirtyPaths: Set<String> get() = dirtyBuffers.keys + deferredBuffers.keys
    val conflictPaths: Set<String> get() = conflictBuffers.keys + deferredConflictBuffers.keys
}

internal interface EditorRecoveryCipher {
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray
}

private class PlatformEditorRecoveryCipher : EditorRecoveryCipher {
    private val delegate = KeystoreBlobCipher("droide_editor_recovery_v1")
    override fun encrypt(plain: ByteArray, aad: ByteArray) = delegate.encrypt(plain, aad)
    override fun decrypt(encrypted: ByteArray, aad: ByteArray) = delegate.decrypt(encrypted, aad)
}

/** An archived leaf is not live recovery data and is never a cleanup authorization. Expected
 * identity always comes from the original authenticated v7 ledger, never rebaselined. */
data class EditorRecoveryEvidenceIssue(
    val blobName: String,
    val missing: Boolean,
    val expectedBytes: Long,
    val expectedSha256: String,
    val observedBytes: Long?,
    val observedSha256: String?,
)

class EditorStateStore internal constructor(
    context: Context,
    projectId: String,
    private val cipher: EditorRecoveryCipher,
    private val directorySyncOverride: ((File) -> Unit)? = null,
) {
    constructor(context: Context, projectId: String, authority: EditorRecoveryConflictAuthority) : this(context, projectId, PlatformEditorRecoveryCipher()) {
        bindConflictAuthority(authority)
    }
    private val applicationContext = context.applicationContext
    private val appFilesDir by lazy { applicationContext.filesDir.canonicalFile }
    private val safeProjectId = PathSecurity.safeLeafName(projectId)
    private val aad = safeProjectId.toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataRoot by lazy { File(appFilesDir, ".droide") }
    private val root by lazy { File(metadataRoot, "editor-recovery") }
    private val file by lazy { File(root, "$safeProjectId.bin") }
    private val blobs by lazy { File(root, "$safeProjectId.blobs") }
    private val mutex = storageMutex(appFilesDir.path + "/" + safeProjectId)
    private val retirement = AtomicReference<RetirementState?>(null)
    private val legacyPlaintextFile by lazy { File(root, "$safeProjectId.json") }

    private val evidenceIssues = MutableStateFlow<List<EditorRecoveryEvidenceIssue>>(emptyList())
    val archivedEvidenceIssues: StateFlow<List<EditorRecoveryEvidenceIssue>> = evidenceIssues.asStateFlow()

    private val conflictAuthority = AtomicReference<EditorRecoveryConflictAuthority?>(null)
    internal fun bindConflictAuthority(authority: EditorRecoveryConflictAuthority) {
        check(conflictAuthority.compareAndSet(null, authority)) { "Recovery conflict authority is already bound" }
    }
    private fun validateSnapshotRevision(snapshot: EditorRecoverySnapshot) {
        conflictAuthority.get()?.let { authority ->
            check(snapshot.conflictRevision?.owner === authority) { "Recovery snapshot is missing its runtime conflict authority" }
        }
        snapshot.conflictRevision?.requireCurrent()
    }

    /** Identity capability for one attempted handoff of this store. */
    class RetirementToken internal constructor()

    private class RetirementState(val token: RetirementToken, val committed: Boolean) {
        // Allocate both phases during admission, never at the no-throw publication boundary.
        val afterCommit: RetirementState = if (committed) this else RetirementState(token, true)
    }

    /** Refuse immediately while existing IO owns the store; never block Main waiting for it. */
    fun tryRetireWrites(): RetirementToken? {
        if (!mutex.tryLock()) return null
        try {
            if (retirement.get() != null || pendingDiscard != null) return null
            val token = RetirementToken()
            retirement.set(RetirementState(token, committed = false))
            return token
        } finally {
            mutex.unlock()
        }
    }

    /** Exit can suspend until admitted IO drains, without blocking Main or replaying it. */
    internal suspend fun retireWritesAfterPendingIo(): RetirementToken = mutex.withLock {
        check(retirement.get() == null) { "Editor recovery already belongs to another handoff" }
        check(pendingDiscard == null) { "A confirmed recovery removal needs reconciliation before Exit" }
        RetirementToken().also { retirement.set(RetirementState(it, committed = false)) }
    }

    fun commitRetirement(token: RetirementToken): Boolean {
        val state = retirement.get() ?: return false
        if (state.token !== token) return false
        return state.committed || retirement.compareAndSet(state, state.afterCommit) ||
            retirement.get() === state.afterCommit
    }

    /** Only this attempt may reopen ordinary writes, and never after its commit. */
    fun abortRetirement(token: RetirementToken): Boolean {
        val state = retirement.get() ?: return false
        return state.token === token && !state.committed && retirement.compareAndSet(state, null)
    }

    suspend fun load(): EditorRecoverySnapshot? = mutex.withLock { withContext(Dispatchers.IO) {
        prepareStorage(create = false)
        requireRegularOrAbsent(file)
        if (!file.exists()) { evidenceIssues.value = emptyList(); return@withContext null }
        require(file.isFile && file.length() in 1..MAX_FILE_BYTES) { "Editor recovery manifest is invalid; original data was preserved" }
        try {
            val encrypted = readEncrypted(file, MAX_FILE_BYTES)
            val plain = cipher.decrypt(encrypted, aad)
            require(plain.size <= MAX_PLAINTEXT_BYTES) { "Recovery payload is too large" }
            val rootObj = json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject
                ?: error("Recovery payload is not an object")
            val version = (rootObj["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            require(version in 1..7) { "Unsupported editor recovery format: $version" }
            verifyPreserved(rootObj, currentCoroutineContext())
            val open = (rootObj["open"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter(::validPath).distinct().take(MAX_OPEN_FILES)
            val active = (rootObj["active"] as? JsonPrimitive)?.contentOrNull?.takeIf(::validPath).orEmpty()
            val dirty = linkedMapOf<String, String>()
            val deferred = linkedMapOf<String, EditorRecoveryBuffer>()
            val conflicts = linkedMapOf<String, String>()
            val deferredConflicts = linkedMapOf<String, EditorRecoveryBuffer>()
            val diskVersions = linkedMapOf<String, DiskFileVersion>()
            val conflictDiskVersions = linkedMapOf<String, DiskFileVersion>()
            val dirtyEntries = rootObj["dirty"] as? JsonArray ?: JsonArray(emptyList())
            val conflictEntries = if (version >= 5) rootObj["conflicts"] as? JsonArray ?: JsonArray(emptyList())
                else JsonArray(emptyList())
            require(dirtyEntries.size + conflictEntries.size <= MAX_DIRTY_FILES) { "Too many recovery documents" }
            var total = 0L
            fun readEntries(entries: JsonArray, contents: MutableMap<String, String>, references: MutableMap<String, EditorRecoveryBuffer>, versions: MutableMap<String, DiskFileVersion>) {
                for (item in entries) {
                    val o = item as? JsonObject ?: error("Invalid recovery buffer entry")
                    val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: error("Missing recovery buffer path")
                    require(validPath(path) && path !in contents && path !in references) { "Invalid or duplicated recovery buffer path" }
                    // Old formats contain no original disk identity. Never infer it from disk or dirty text.
                    val diskVersion = if (version >= 6) readDiskVersion(o) else null
                    diskVersion?.let { versions[path] = it }
                    if (version >= 4) {
                        val id = (o["blob"] as? JsonPrimitive)?.contentOrNull
                        require(id != null && BLOB_NAME.matches(id)) { "Invalid recovery blob for $path" }
                        val blob = File(blobs, id)
                        requireRegularOrAbsent(blob)
                        require(blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Missing recovery buffer for $path" }
                        val hash = (o["sha256"] as? JsonPrimitive)?.contentOrNull
                        require(hash == null || hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid recovery checksum for $path" }
                        // V4 had no plaintext-size metadata. Its encrypted length is a bounded, conservative estimate,
                        // keeping recovery lazy without reading every blob.
                        val bound = if (version >= 5) (o["max_bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                            ?: error("Missing recovery buffer size for $path") else minOf(MAX_BUFFER_BYTES.toLong(), blob.length())
                        require(bound in 0..MAX_BUFFER_BYTES.toLong() && (version == 4 || total + bound <= MAX_TOTAL_BUFFER_BYTES)) { "Recovery exceeds safety limit" }
                        total += bound
                        references[path] = EditorRecoveryBuffer(path, id, hash, this@EditorStateStore, bound, diskVersion) {
                            mutex.withLock { withContext(Dispatchers.IO) { readBlob(path, id, hash, bound) } }
                        }
                    } else {
                        val content = (o["content"] as? JsonPrimitive)?.contentOrNull ?: error("Missing legacy recovery buffer")
                        val bytes = content.toByteArray(Charsets.UTF_8).size
                        require(bytes <= MAX_BUFFER_BYTES && total + bytes <= MAX_TOTAL_BUFFER_BYTES) { "Legacy recovery exceeds safety limit" }
                        contents[path] = content
                        total += bytes
                    }
                }
            }
            readEntries(dirtyEntries, dirty, deferred, diskVersions)
            readEntries(conflictEntries, conflicts, deferredConflicts, conflictDiskVersions)
            val selections = linkedMapOf<String, EditorSelectionSnapshot>()
            if (version >= 2) {
                val selectionArray = rootObj["selections"] as? JsonArray ?: JsonArray(emptyList())
                for (item in selectionArray.take(MAX_SNAPSHOT_ENTRIES)) {
                    val o = item as? JsonObject ?: continue
                    val path = (o["path"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val start = (o["start"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: continue
                    val end = (o["end"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: start
                    if (validPath(path) && start in 0..MAX_SELECTION_OFFSET && end in 0..MAX_SELECTION_OFFSET)
                        selections[path] = EditorSelectionSnapshot(start, end)
                }
            }
            val reviewFiles = if (version >= 3) {
                (rootObj["review"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .filter(::validPath).distinct().take(MAX_SNAPSHOT_ENTRIES).toSet()
            } else emptySet()
            EditorRecoverySnapshot(open, active, dirty, selections, reviewFiles, deferred, conflicts, deferredConflicts,
                diskVersions, conflictDiskVersions)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {

            throw IllegalStateException("Editor recovery could not be read; encrypted data was preserved", error)
        }
    } }

    internal enum class DiscardPhase { PREPARED, UNCERTAIN, DURABLE }
    internal data class ConflictDiscardOutcome(val phase: DiscardPhase, val ciphertextRemoved: Boolean, val message: String)
    internal class ConflictDiscardSelection internal constructor(
        internal val owner: EditorStateStore, val path: String, internal val blob: String,
        internal val plaintextSha256: String, val ciphertextBytes: Long, internal val ciphertextSha256: String,
        internal val manifestSha256: String, internal val fileKey: Any,
    ) {
        internal val consumed = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile internal var phase = DiscardPhase.PREPARED
        internal var intendedManifestSha256: String? = null
        internal var admittedManifestSha256: String? = null
        private var durableCallback: (() -> Unit)? = null
        internal fun installCallback(callback: () -> Unit) { durableCallback = callback }
        internal fun notifyDurable() { durableCallback?.invoke(); durableCallback = null }
    }
    @Volatile private var pendingDiscard: ConflictDiscardSelection? = null
    private fun digestBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Preview is not authorization. Only an exact persisted authenticated conflict can be selected. */
    internal suspend fun prepareConflictDiscard(path: String, plaintextSha256: String): ConflictDiscardSelection = mutex.withLock {
        check(retirement.get() == null && pendingDiscard == null) { "Recovery is not available for a new removal" }
        withContext(Dispatchers.IO) {
            prepareStorage(create = false)
            val manifestBytes = readEncrypted(file, MAX_FILE_BYTES)
            val manifest = checkNotNull(readManifestForMutation())
            val entry = (manifest["conflicts"] as? JsonArray).orEmpty().map { it.jsonObject }
                .singleOrNull { it["path"]?.jsonPrimitive?.content == path } ?: error("Selected older copy is not checkpointed; review again")
            val id = entry["blob"]?.jsonPrimitive?.content ?: error("Legacy copy must finish recovery before removal")
            require(BLOB_NAME.matches(id) && validPath(path))
            val hash = entry["sha256"]?.jsonPrimitive?.content ?: error("Recovery copy has no authenticated identity")
            require(hash == plaintextSha256 && hash.matches(HASH_PATTERN)) { "Selected recovery copy changed" }
            val bound = entry["max_bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: error("Recovery copy size is unknown")
            require(bound in 0..MAX_BUFFER_BYTES.toLong())
            requireUniqueDiscardReference(manifest, path, id)
            val blob = File(blobs, id); requireRegularOrAbsent(blob)
            val attributes = Files.readAttributes(blob.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(attributes.isRegularFile && !attributes.isSymbolicLink)
            val key = checkNotNull(attributes.fileKey()) { "Recovery file identity unavailable" }
            check(sha256(readBlob(path, id, hash, bound)) == hash)
            val identity = cipherIdentity(blob, currentCoroutineContext())
            check(Files.readAttributes(blob.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == key)
            ConflictDiscardSelection(this@EditorStateStore, path, id, hash, identity.bytes, identity.sha256, digestBytes(manifestBytes), key)
        }
    }
    private fun requireUniqueDiscardReference(manifest: JsonObject, path: String, id: String) {
        require(preservedMetadata(manifest).keys.none { it == id } && retiringMetadata(manifest).keys.none { it == id }) {
            "Archived or pending evidence is not eligible for this removal"
        }
        val all = listOf("dirty", "conflicts").flatMap { slot -> (manifest[slot] as? JsonArray).orEmpty().map { slot to it.jsonObject } }
        val references = all.filter { it.second["blob"]?.jsonPrimitive?.content == id }
        check(references.size == 1 && references.single().first == "conflicts" && references.single().second["path"]?.jsonPrimitive?.content == path) {
            "This encrypted payload is still referenced by other live recovery data"
        }
    }
    private fun validateDiscard(selection: ConflictDiscardSelection, manifest: JsonObject, snapshot: EditorRecoverySnapshot,
        context: kotlin.coroutines.CoroutineContext) {
        check(selection.owner === this && selection.consumed.get() && selection.phase == DiscardPhase.PREPARED)
        // Unrelated checkpoints may change the encrypted manifest. Consent remains bound to the exact
        // selected payload; capture the current manifest only for uncertain-publication reconciliation.
        selection.admittedManifestSha256 = digestBytes(readEncrypted(file, MAX_FILE_BYTES))
        requireUniqueDiscardReference(manifest, selection.path, selection.blob)
        val selectedEntry = (manifest["conflicts"] as? JsonArray).orEmpty().map { it.jsonObject }
            .single { it["path"]?.jsonPrimitive?.content == selection.path }
        check(selectedEntry["sha256"]?.jsonPrimitive?.content == selection.plaintextSha256) { "Selected recovery content identity changed" }
        check(selection.path !in snapshot.conflictPaths) { "Selected older copy remains in the proposed checkpoint" }
        val remaining = (manifest["conflicts"] as? JsonArray).orEmpty().map { it.jsonObject }
            .filter { it["path"]?.jsonPrimitive?.content != selection.path }
        check(remaining.map { it.getValue("path").jsonPrimitive.content }.toSet() == snapshot.conflictPaths) { "Other recovery copies changed; inspect again" }
        for (entry in remaining) {
            val path = entry.getValue("path").jsonPrimitive.content
            val expected = entry["sha256"]?.jsonPrimitive?.content ?: error("Other recovery copy identity is unknown")
            val actual = snapshot.conflictBuffers[path]?.let(::sha256) ?: snapshot.deferredConflictBuffers[path]?.contentSha256
            check(actual == expected) { "Another recovery copy changed; it was preserved" }
        }
        check((snapshot.deferredBuffers.values + snapshot.deferredConflictBuffers.values).none { it.blobName == selection.blob })
        val blob = File(blobs, selection.blob); requireRegularOrAbsent(blob)
        check(Files.readAttributes(blob.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey() == selection.fileKey)
        check(cipherIdentity(blob, context) == CipherIdentity(selection.ciphertextBytes, selection.ciphertextSha256)) { "Selected ciphertext changed; no removal is permitted" }
    }
    private fun markDiscardDurable(selection: ConflictDiscardSelection) {
        selection.phase = DiscardPhase.DURABLE
        // Revoke captured pending-generation snapshots before the mutex is released.
        selection.notifyDurable()
        pendingDiscard = null
    }
    internal fun conflictDiscardOutcome(selection: ConflictDiscardSelection, failure: Throwable? = null): ConflictDiscardOutcome {
        check(selection.owner === this)
        val removed = selection.phase == DiscardPhase.DURABLE && !Files.exists(File(blobs, selection.blob).toPath(), LinkOption.NOFOLLOW_LINKS)
        return ConflictDiscardOutcome(selection.phase, removed, when {
            selection.phase == DiscardPhase.UNCERTAIN -> "Removal publication needs reconciliation. The original copy remains retained; no space release is claimed. ${failure?.message.orEmpty()}"
            removed -> "Selected older recovery copy removed. Physical disk space released is not measured."
            selection.phase == DiscardPhase.DURABLE -> "Selected older copy was removed from recovery; its approved ciphertext cleanup is pending. ${failure?.message.orEmpty()}"
            else -> "Copy kept. ${failure?.message ?: "Removal did not commit"}"
        })
    }
    internal suspend fun discardConflict(snapshot: EditorRecoverySnapshot, selection: ConflictDiscardSelection,
        onDurable: () -> Unit): ConflictDiscardOutcome {
        check(selection.owner === this && selection.consumed.compareAndSet(false, true)) { "Removal preview was already used" }
        selection.installCallback(onDurable)
        try {

            ExportWriterAdmission.withQuiescentCapture(blobs, label = "Selected recovery removal") {
                saveChecked(snapshot, null, committedToken = false, discard = selection)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return conflictDiscardOutcome(selection, failure) }
        return conflictDiscardOutcome(selection)
    }
    internal suspend fun reconcileConflictDiscard(selection: ConflictDiscardSelection): ConflictDiscardOutcome =
        ExportWriterAdmission.withQuiescentCapture(blobs, label = "Confirmed recovery removal") {
            mutex.withLock { withContext(Dispatchers.IO) {
                check(selection.owner === this@EditorStateStore && selection.consumed.get())
                try {
                    requireStorage(create = false)
                    if (selection.phase == DiscardPhase.UNCERTAIN) {
                        check(pendingDiscard === selection)
                        val current = digestBytes(readEncrypted(file, MAX_FILE_BYTES))
                        if (current == selection.admittedManifestSha256) {
                            // Old manifest still owns the original payload; no publication occurred.
                            selection.phase = DiscardPhase.PREPARED; pendingDiscard = null
                            return@withContext conflictDiscardOutcome(selection)
                        }
                        check(current == selection.intendedManifestSha256) { "Recovery manifest no longer matches the confirmed action; preserve all evidence" }
                        syncDirectory(root)
                        markDiscardDurable(selection)
                    }
                    if (selection.phase == DiscardPhase.DURABLE) {
                        val manifest = checkNotNull(readManifestForMutation())
                        check(listOf("dirty", "conflicts").flatMap { (manifest[it] as? JsonArray).orEmpty() }
                            .none { it.jsonObject["blob"]?.jsonPrimitive?.content == selection.blob }) { "Selected payload has a live reference again; no cleanup" }
                        val blob = File(blobs, selection.blob)
                        if (Files.exists(blob.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                            val receipt = retiringMetadata(manifest)[selection.blob]
                            check(receipt == CipherIdentity(selection.ciphertextBytes, selection.ciphertextSha256)) { "Exact approved retirement receipt unavailable" }
                            check(cipherIdentity(blob, currentCoroutineContext()) == receipt) { "Approved ciphertext changed; preserve it" }
                            deleteOwnedBlob(blob); syncDirectory(blobs)
                        }
                    }
                    conflictDiscardOutcome(selection)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { conflictDiscardOutcome(selection, failure) }
            } }
        }

    suspend fun save(snapshot: EditorRecoverySnapshot) = saveChecked(snapshot, null, committedToken = false)

    suspend fun saveRetired(snapshot: EditorRecoverySnapshot, token: RetirementToken) =
        saveChecked(snapshot, token, committedToken = true)

    internal suspend fun saveTentative(snapshot: EditorRecoverySnapshot, token: RetirementToken) =
        saveChecked(snapshot, token, committedToken = false)

    private suspend fun saveChecked(
        snapshot: EditorRecoverySnapshot, token: RetirementToken?, committedToken: Boolean,
        discard: ConflictDiscardSelection? = null,
    ) = mutex.withLock {
        if (pendingDiscard != null && pendingDiscard !== discard) throw EditorRecoveryCheckpointDeferredException("A confirmed recovery removal needs reconciliation before another checkpoint")
        validateSnapshotRevision(snapshot)

        val state = retirement.get()
        check(if (token == null) state == null else state != null && state.token === token && state.committed == committedToken) {
            "Editor recovery write is not owned by the active store or its committed handoff"
        }
        withContext(Dispatchers.IO) {
        prepareStorage(create = true)
        requireRegularOrAbsent(file)
        val open = snapshot.openFiles.filter(::validPath).distinct().take(MAX_OPEN_FILES)
        val active = snapshot.activeFile.takeIf(::validPath).orEmpty()
        require(snapshot.dirtyPaths.size + snapshot.conflictPaths.size <= MAX_DIRTY_FILES) { "Too many unsaved documents to recover" }
        require(snapshot.diskVersions.keys.all { it in snapshot.dirtyPaths } &&
            snapshot.conflictDiskVersions.keys.all { it in snapshot.conflictPaths }) { "Orphan recovery disk identity" }
        requireDirectory(blobs, create = true)
        syncRecoveryHierarchy()
        for ((contents, references) in listOf(snapshot.dirtyBuffers to snapshot.deferredBuffers,
            snapshot.conflictBuffers to snapshot.deferredConflictBuffers)) {
            require(contents.keys.all(::validPath) && contents.keys.none { it in references }) { "Invalid or duplicated recovery path" }
            for ((path, reference) in references) require(validPath(path) && reference.path == path && reference.owner === this@EditorStateStore) {
                "Invalid deferred recovery ownership"
            }
        }
        val context = currentCoroutineContext()
        val previousRoot = readManifestForMutation()
        discard?.let { validateDiscard(it, checkNotNull(previousRoot), snapshot, context) }

        var liveBytes = 0L
        fun admitLive(bytes: Long) {
            require(bytes in 0..MAX_BUFFER_BYTES.toLong())
            liveBytes += bytes
            require(liveBytes <= MAX_TOTAL_BUFFER_BYTES) { "Recovery buffers exceed safety limit" }
        }
        (snapshot.dirtyBuffers.values + snapshot.conflictBuffers.values).forEach { content ->
            admitLive(BoundedUtf8.encodedSize(content, MAX_BUFFER_BYTES.toLong()) { error("Recovery buffer exceeds safety limit") })
        }
        (snapshot.deferredBuffers.values + snapshot.deferredConflictBuffers.values).forEach { reference ->
            admitLive(reference.maxContentBytes ?: minOf(MAX_BUFFER_BYTES.toLong(), File(blobs, reference.blobName).length()))
        }
        val deferredHashes = mutableMapOf<Pair<String, String>, String>()
        val deferredIdentities = mutableMapOf<String, Pair<String, String?>>()
        for ((slot, references, versions) in listOf(
            Triple("dirty", snapshot.deferredBuffers, snapshot.diskVersions),
            Triple("conflicts", snapshot.deferredConflictBuffers, snapshot.conflictDiskVersions))) {
            for ((path, reference) in references) {
                require(path !in versions || versions[path] == reference.diskVersion) { "Deferred recovery disk identity changed: $path" }
                require(BLOB_NAME.matches(reference.blobName) &&
                    (reference.contentSha256 == null || reference.contentSha256.matches(HASH_PATTERN)))
                val identity = path to reference.contentSha256
                require(deferredIdentities[reference.blobName] == null || deferredIdentities[reference.blobName] == identity) {
                    "Contradictory deferred blob identity"
                }
                deferredIdentities[reference.blobName] = identity
                val blob = File(blobs, reference.blobName)
                requireRegularOrAbsent(blob)
                require(blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Missing deferred recovery buffer: $path" }
                val bound = reference.maxContentBytes ?: minOf(MAX_BUFFER_BYTES.toLong(), blob.length())
                deferredHashes[slot to path] = reference.contentSha256 ?: sha256(readBlob(path, reference.blobName, null, bound))
            }
        }
        val plan = planBlobs(previousRoot, snapshot, context)
        discard?.let { check(plan.retire[it.blob] == CipherIdentity(it.ciphertextBytes, it.ciphertextSha256)) {
            "Selected payload is no longer an authenticated retirement candidate; original was preserved"
        } }
        val retained = plan.preserved.keys.toMutableSet()
        var generationCipherBytes = (plan.reuse.values + snapshot.deferredBuffers.values.map { it.blobName } +
            snapshot.deferredConflictBuffers.values.map { it.blobName }).toSet().sumOf { File(blobs, it).length() }
        require(generationCipherBytes <= MAX_GENERATION_CIPHERTEXT_BYTES) { "Recovery generation exceeds ciphertext budget" }
        var total = 0L
        fun account(bytes: Long, path: String) {
            require(bytes in 0..MAX_BUFFER_BYTES.toLong() && total + bytes <= MAX_TOTAL_BUFFER_BYTES) {
                "Recovery buffers exceed safety limit: $path"
            }
            total += bytes
        }
        fun writeEntries(slot: String, contents: Map<String, String>, references: Map<String, EditorRecoveryBuffer>, versions: Map<String, DiskFileVersion>): JsonArray {
            return buildJsonArray {
                for ((path, content) in contents) {
                    require(validPath(path) && path !in references) { "Invalid or duplicated recovery path" }
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    account(bytes.size.toLong(), path)
                    val hash = sha256(content)
                    val reused = plan.reuse[slot to path]
                    val id = reused ?: UUID.randomUUID().toString() + ".bin"
                    if (reused == null) {
                        context.ensureActive()
                        val encryptedBlob = cipher.encrypt(bytes, blobAad(path))
                        generationCipherBytes += encryptedBlob.size
                        require(generationCipherBytes <= MAX_GENERATION_CIPHERTEXT_BYTES) { "Recovery generation exceeds ciphertext budget" }
                        context.ensureActive()
                        writeNewBlob(File(blobs, id), encryptedBlob, context)
                    }
                    retained += id
                    add(buildJsonObject {
                        put("path", path); put("blob", id); put("sha256", hash); put("max_bytes", bytes.size)
                        put("baseline", diskVersionJson(versions[path]))
                    })
                }
                for ((path, reference) in references) {
                    require(validPath(path) && reference.path == path && reference.owner === this@EditorStateStore) { "Invalid deferred recovery ownership" }
                    require(path !in versions || versions[path] == reference.diskVersion) { "Deferred recovery disk identity changed: $path" }
                    val id = reference.blobName
                    val blob = File(blobs, id)
                    requireRegularOrAbsent(blob)
                    require(BLOB_NAME.matches(id) && blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Missing deferred recovery buffer: $path" }
                    val bound = reference.maxContentBytes ?: minOf(MAX_BUFFER_BYTES.toLong(), blob.length())
                    account(bound, path)
                    val hash = deferredHashes.getValue(slot to path)
                    retained += id
                    add(buildJsonObject {
                        put("path", path); put("blob", id); put("sha256", hash); put("max_bytes", bound)
                        put("baseline", diskVersionJson(reference.diskVersion))
                    })
                }
            }
        }
        val dirty = writeEntries("dirty", snapshot.dirtyBuffers, snapshot.deferredBuffers, snapshot.diskVersions)
        val conflicts = writeEntries("conflicts", snapshot.conflictBuffers, snapshot.deferredConflictBuffers, snapshot.conflictDiskVersions)
        val selections = buildJsonArray {
            val relevant = (open + snapshot.dirtyPaths).toSet()
            snapshot.selections.entries.filter { validPath(it.key) && it.key in relevant }
                .take(MAX_SNAPSHOT_ENTRIES).forEach { (path, selection) ->
                add(buildJsonObject {
                    put("path", path)
                    put("start", selection.start.coerceIn(0, MAX_SELECTION_OFFSET))
                    put("end", selection.end.coerceIn(0, MAX_SELECTION_OFFSET))
                })
            }
        }
        val rootObj = buildJsonObject {
            put("version", 7)
            put("preserved", plan.json())
            put("retiring", plan.retiringJson())
            put("active", active)
            put("open", buildJsonArray { open.forEach { add(JsonPrimitive(it)) } })
            put("dirty", dirty)
            put("conflicts", conflicts)
            put("selections", selections)
            put("review", buildJsonArray {
                val relevant = (open + snapshot.dirtyPaths).toSet()
                snapshot.reviewFiles.filter { validPath(it) && it in relevant }
                    .take(MAX_SNAPSHOT_ENTRIES).forEach { add(JsonPrimitive(it)) }
            })
        }
        val text = rootObj.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Recovery state too large" }
        val plain = text.toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plain, aad)
        require(encrypted.size <= MAX_FILE_BYTES) { "Encrypted recovery state too large" }

        context.ensureActive()
        validateSnapshotRevision(snapshot)
        discard?.let { it.intendedManifestSha256 = digestBytes(encrypted) }
        atomicWrite(file, encrypted, discard)
        // An uncertain write/sync leaves every staged generation for bounded retry inventory.
        cleanVerifiedBlobs(plan, retained, context)
    } }

    suspend fun clear() = mutex.withLock {
        check(conflictAuthority.get() == null) { "Runtime recovery clear requires an authoritative empty snapshot" }
        check(pendingDiscard == null) { "Confirmed removal needs reconciliation" }
        check(retirement.get() == null) { "Retired editor recovery cannot be cleared" }
        withContext(Dispatchers.IO) {
            requireStorage(create = false)
            requireRegularOrAbsent(file)
            requireRegularOrAbsent(legacyPlaintextFile)
            val context = currentCoroutineContext()
            if (root.isDirectory) syncRecoveryHierarchy()
            val plan = planBlobs(readManifestForMutation(), EditorRecoverySnapshot(emptyList(), "", emptyMap()), context)
            context.ensureActive()
            // The empty live manifest durably owns any pending retirement across a crash.
            val empty = buildJsonObject {
                put("version", 7); put("preserved", plan.json()); put("retiring", plan.retiringJson()); put("active", "")
                for (slot in listOf("open", "dirty", "conflicts", "selections", "review")) put(slot, JsonArray(emptyList()))
            }
            if (file.exists() || plan.preserved.isNotEmpty() || plan.retire.isNotEmpty()) {
                atomicWrite(file, cipher.encrypt(empty.toString().toByteArray(Charsets.UTF_8), aad))
            }
            cleanVerifiedBlobs(plan, plan.preserved.keys, context)
            if (plan.preserved.isEmpty()) {
                var removedManifest = false
                if (file.exists()) { check(file.delete()) { "Cannot delete empty editor recovery state" }; removedManifest = true }
                if (legacyPlaintextFile.exists()) {
                    check(legacyPlaintextFile.delete()) { "Cannot delete legacy editor recovery state" }; removedManifest = true
                }
                if (removedManifest) syncDirectory(root)
                if (blobs.isDirectory && blobs.list()?.isEmpty() == true) blobs.delete()
            }
        }
    }

    private fun diskVersionJson(version: DiskFileVersion?): kotlinx.serialization.json.JsonElement =
        version?.let { buildJsonObject {
            put("existed", it.existed); put("bytes", it.bytes); put("sha256", it.sha256)
        } } ?: JsonNull

    private fun readDiskVersion(entry: JsonObject): DiskFileVersion? {
        val value = entry["baseline"] ?: error("Missing recovery disk identity")
        if (value == JsonNull) return null
        val baseline = value as? JsonObject ?: error("Invalid recovery disk identity")
        val existed = baseline["existed"] as? JsonPrimitive ?: error("Missing recovery existence")
        val bytes = baseline["bytes"] as? JsonPrimitive ?: error("Missing recovery baseline size")
        val hash = baseline["sha256"] as? JsonPrimitive ?: error("Missing recovery baseline digest")
        require(!existed.isString && existed.content in setOf("true", "false") && !bytes.isString && hash.isString) {
            "Invalid recovery disk identity types"
        }
        return DiskFileVersion(existed.content == "true", bytes.content.toLongOrNull()
            ?: error("Invalid recovery baseline size"), hash.content)
    }

    private fun readBlob(path: String, id: String, expected: String?, maxBytes: Long = MAX_BUFFER_BYTES.toLong()): String {
        requireStorage(create = false)
        val blob = File(blobs, id)
        requireRegularOrAbsent(blob)
        require(BLOB_NAME.matches(id) && blob.isFile && blob.length() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Invalid recovery blob: $path" }
        val plain = cipher.decrypt(readEncrypted(blob, MAX_ENCRYPTED_BUFFER_BYTES), blobAad(path))
        require(plain.size <= MAX_BUFFER_BYTES && plain.size <= maxBytes) { "Recovery buffer too large: $path" }
        val content = plain.toString(Charsets.UTF_8)
        if (expected != null) require(sha256(content) == expected) { "Recovery buffer checksum failed: $path" }
        return content
    }

    private fun blobAad(path: String): ByteArray = (safeProjectId + "\u0000" + path).toByteArray(Charsets.UTF_8)

    private fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun validPath(path: String): Boolean = isRecoverablePath(path)

    private fun requireStorage(create: Boolean) {
        // Android may expose /data/data through a system symlink. The canonical application files
        // directory is the trusted anchor; every component owned below it is no-follow.
        check(appFilesDir.isDirectory && !PathSecurity.isSymbolicLink(appFilesDir)) { "Editor recovery application root is unavailable" }
        requireDirectory(metadataRoot, create)
        requireDirectory(root, create)
        requireDirectory(blobs, create = false)
    }

    private fun prepareStorage(create: Boolean) {
        // Storage validation and maintenance belong to handled suspend operations, so corrupt metadata
        // cannot turn constructing the editor into a repeat startup crash.
        requireStorage(create)
        requireRegularOrAbsent(legacyPlaintextFile)
        if (legacyPlaintextFile.isFile) check(legacyPlaintextFile.delete()) { "Cannot remove legacy editor recovery state" }
    }

    private fun requireDirectory(directory: File, create: Boolean) {
        check(!PathSecurity.isSymbolicLink(directory)) { "Editor recovery storage is a symbolic link; data was preserved" }
        if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Editor recovery storage is not a directory" }
        } else if (create) {
            check(directory.mkdir() || Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Cannot create editor recovery storage" }
            check(!PathSecurity.isSymbolicLink(directory)) { "Editor recovery storage changed during creation" }
        }
    }

    private fun requireRegularOrAbsent(target: File) {
        check(!PathSecurity.isSymbolicLink(target)) { "Editor recovery file is a symbolic link; data was preserved" }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Editor recovery file has an unsafe type" }
        }
    }

    private fun readEncrypted(target: File, limit: Long, allowEmpty: Boolean = false): ByteArray {
        requireStorage(create = false)
        requireRegularOrAbsent(target)
        val size = target.length()
        require(size in (if (allowEmpty) 0 else 1)..limit) { "Editor recovery file exceeds its safety limit" }
        return Files.newInputStream(target.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            // Allocate once at the admitted size; do not grow/copy a second large ciphertext buffer on a
            // phone. Atomic writers keep the opened generation stable.
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                require(count > 0) { "Editor recovery file changed during reading" }
                offset += count
            }
            require(input.read() == -1) { "Editor recovery file changed during reading" }
            bytes
        }
    }

    private data class CipherIdentity(val bytes: Long, val sha256: String)
    private data class PriorBlob(val path: String, val id: String, val hash: String?, val maxBytes: Long)
    private data class PreservedBlob(val id: String, val identity: CipherIdentity, val reason: String,
        val path: String?, val expectedHash: String?, val maxBytes: Long?) {
        fun json() = buildJsonObject {
            put("blob", id); put("bytes", identity.bytes); put("sha256", identity.sha256); put("reason", reason)
            put("path", path?.let(::JsonPrimitive) ?: JsonNull)
            put("expected_sha256", expectedHash?.let(::JsonPrimitive) ?: JsonNull)
            put("max_bytes", maxBytes?.let(::JsonPrimitive) ?: JsonNull)
        }
    }
    private data class BlobPlan(val preserved: Map<String, PreservedBlob>,
        val reuse: Map<Pair<String, String>, String>, val retire: Map<String, CipherIdentity>) {
        fun json() = JsonArray(preserved.values.map { it.json() })
        fun retiringJson() = JsonArray(retire.map { (id, identity) -> buildJsonObject {
            put("blob", id); put("bytes", identity.bytes); put("sha256", identity.sha256)
        } })
    }

    private fun readManifestForMutation(): JsonObject? {
        requireRegularOrAbsent(file)
        if (!file.exists()) return null
        val plain = cipher.decrypt(readEncrypted(file, MAX_FILE_BYTES), aad)
        require(plain.size <= MAX_PLAINTEXT_BYTES)
        return (json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject
            ?: error("Recovery manifest is not an object")).also {
            require(manifestVersion(it) in 1..7) { "Unsupported existing editor recovery format" }
        }
    }

    private fun manifestVersion(root: JsonObject) =
        (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

    private fun preservedMetadata(root: JsonObject?): LinkedHashMap<String, PreservedBlob> {
        val result = linkedMapOf<String, PreservedBlob>()
        if (root == null) return result
        if (manifestVersion(root) < 7) {
            require(root["preserved"] == null) { "Preservation metadata cannot be downgraded" }
            return result
        }
        val entries = root["preserved"] as? JsonArray ?: error("Missing preservation ledger")
        require(entries.size <= MAX_PRESERVED_BLOBS) { "Recovery evidence entry limit reached" }
        for (value in entries) {
            val entry = value as? JsonObject ?: error("Invalid preservation record")
            require(entry.keys == setOf("blob", "bytes", "sha256", "reason", "path", "expected_sha256", "max_bytes"))
            fun text(key: String): String = (entry[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("Invalid preservation $key")
            fun number(key: String): Long = (entry[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                ?: error("Invalid preservation $key")
            val id = text("blob"); val bytes = number("bytes"); val hash = text("sha256")
            require(BLOB_NAME.matches(id) && id !in result && bytes in 0..MAX_ENCRYPTED_BUFFER_BYTES && hash.matches(HASH_PATTERN))
            val reason = text("reason")
            val path = if (entry["path"] == JsonNull) null else text("path")
            val expected = if (entry["expected_sha256"] == JsonNull) null else text("expected_sha256")
            val bound = if (entry["max_bytes"] == JsonNull) null else number("max_bytes")
            require(expected == null || expected.matches(HASH_PATTERN))
            require(if (reason in setOf("orphan", "retirement_changed")) path == null && expected == null && bound == null
                else reason == "verification_failed" && path != null && validPath(path) && bound != null && bound in 0..MAX_BUFFER_BYTES.toLong())
            result[id] = PreservedBlob(id, CipherIdentity(bytes, hash), reason, path, expected, bound)
        }
        requirePreservationCapacity(result)
        return result
    }

    private fun retiringMetadata(root: JsonObject?): LinkedHashMap<String, CipherIdentity> {
        val result = linkedMapOf<String, CipherIdentity>()
        if (root == null) return result
        if (manifestVersion(root) < 7) {
            require(root["retiring"] == null) { "Retirement receipts cannot be downgraded" }
            return result
        }
        val entries = root["retiring"] as? JsonArray ?: error("Missing retirement receipts")
        require(entries.size <= MAX_DIRTY_FILES) { "Too many retirement receipts" }
        for (value in entries) {
            val entry = value as? JsonObject ?: error("Invalid retirement receipt")
            require(entry.keys == setOf("blob", "bytes", "sha256"))
            val id = (entry["blob"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid retirement blob")
            val bytes = (entry["bytes"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
                ?: error("Invalid retirement length")
            val hash = (entry["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid retirement hash")
            require(BLOB_NAME.matches(id) && id !in result && bytes in 1..MAX_ENCRYPTED_BUFFER_BYTES && hash.matches(HASH_PATTERN))
            result[id] = CipherIdentity(bytes, hash)
        }
        require(result.values.sumOf { it.bytes } <= MAX_GENERATION_CIPHERTEXT_BYTES) { "Retirement receipts exceed their byte budget" }
        return result
    }

    private fun requirePreservationCapacity(entries: Map<String, PreservedBlob>, observedBytes: Map<String, Long> = emptyMap(), additionalBytes: Long = 0L) {
        require(entries.size <= MAX_PRESERVED_BLOBS && entries.values.sumOf { maxOf(it.identity.bytes, observedBytes[it.id] ?: 0L) } + additionalBytes <= MAX_PRESERVED_BYTES) {
            "Recovery evidence capacity reached; existing ciphertext was preserved"
        }
    }

    private fun cipherIdentity(blob: File, context: kotlin.coroutines.CoroutineContext): CipherIdentity {
        requireStorage(create = false); requireRegularOrAbsent(blob)
        require(blob.isFile && blob.length() in 0..MAX_ENCRYPTED_BUFFER_BYTES) { "Recovery ciphertext is missing or exceeds its budget" }
        val size = blob.length(); val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
        Files.newInputStream(blob.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                context.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                require(n > 0 && count + n <= size) { "Recovery ciphertext changed during inspection" }
                digest.update(buffer, 0, n); count += n
            }
        }
        require(count == size) { "Recovery ciphertext changed during inspection" }
        return CipherIdentity(count, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun verifyPreserved(root: JsonObject, context: kotlin.coroutines.CoroutineContext) {
        val preserved = preservedMetadata(root)
        val retiring = retiringMetadata(root)
        require(retiring.keys.none { it in preserved }) { "Conflicting preservation and retirement membership" }
        val referenced = listOf("dirty", "conflicts").flatMap { (root[it] as? JsonArray).orEmpty() }
            .mapNotNull { ((it as? JsonObject)?.get("blob") as? JsonPrimitive)?.contentOrNull }
        require(retiring.keys.none { it in referenced }) { "Active blob cannot have a retirement receipt" }
        val issues = inspectPreserved(preserved, context)
        require(issues.none { it.blobName in referenced }) {
            "A live recovery reference has changed archived evidence; original metadata was preserved"
        }
        evidenceIssues.value = issues
    }

    /** Isolate only missing/changed archived bytes. Invalid metadata and unsafe filesystem objects
     * still fail closed. No leaf is moved, rewritten, adopted or marked deletable. */
    private fun inspectPreserved(entries: Map<String, PreservedBlob>,
        context: kotlin.coroutines.CoroutineContext): List<EditorRecoveryEvidenceIssue> {
        val issues = mutableListOf<EditorRecoveryEvidenceIssue>()
        var retainedBudget = 0L
        for (entry in entries.values) {
            context.ensureActive()
            val blob = File(blobs, entry.id)
            requireStorage(create = false)
            requireRegularOrAbsent(blob)
            val exists = Files.exists(blob.toPath(), LinkOption.NOFOLLOW_LINKS)
            val observedSize = if (exists) blob.length() else 0L
            require(!exists || observedSize in 0..MAX_ENCRYPTED_BUFFER_BYTES) { "Archived recovery ciphertext exceeds its budget" }
            // Keep charging missing evidence's original reservation; changed evidence cannot evade capacity
            // by replacing a small archived leaf with a larger one.
            retainedBudget += maxOf(entry.identity.bytes, observedSize)
            require(retainedBudget <= MAX_PRESERVED_BYTES) { "Recovery evidence byte limit reached; ciphertext was preserved" }
            val observed = if (exists) cipherIdentity(blob, context) else null
            require(observed == null || observed.bytes == observedSize) { "Archived evidence changed during inspection; retry" }
            if (observed != entry.identity) issues += EditorRecoveryEvidenceIssue(
                entry.id, observed == null, entry.identity.bytes, entry.identity.sha256,
                observed?.bytes, observed?.sha256,
            )
        }
        return issues.toList()
    }

    /** Admission/inspection precedes new payload writes. Cleanup receives only the verified allowlist. */
    private suspend fun planBlobs(previous: JsonObject?, snapshot: EditorRecoverySnapshot,
        context: kotlin.coroutines.CoroutineContext): BlobPlan {
        val preserved = preservedMetadata(previous)
        val retiring = retiringMetadata(previous)
        require(retiring.keys.none { it in preserved }) { "Conflicting preservation and retirement membership" }
        val prior = linkedMapOf<String, PriorBlob>()
        val bySlot = linkedMapOf<Pair<String, String>, PriorBlob>()
        val version = previous?.let(::manifestVersion) ?: 0
        if (version in 1..3) {
            val entries = previous?.get("dirty")?.let { it as? JsonArray ?: error("Invalid legacy recovery slot") }
                ?: JsonArray(emptyList())
            require(entries.size <= MAX_DIRTY_FILES) { "Too many legacy recovery documents" }
            val paths = hashSetOf<String>(); var legacyBytes = 0L
            for (value in entries) {
                val entry = value as? JsonObject ?: error("Invalid legacy recovery entry")
                val path = (entry["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing legacy recovery path")
                val content = (entry["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing legacy recovery content")
                require(validPath(path) && paths.add(path)) { "Invalid or duplicated legacy recovery path" }
                legacyBytes += BoundedUtf8.encodedSize(content, MAX_BUFFER_BYTES.toLong()) { error("Legacy recovery buffer exceeds budget") }
                require(legacyBytes <= MAX_TOTAL_BUFFER_BYTES) { "Legacy recovery exceeds byte budget" }
            }
        }
        if (version >= 4) for (slot in listOf("dirty", "conflicts")) {
            val array = if (slot == "conflicts" && version == 4) JsonArray(emptyList())
                else previous?.get(slot) as? JsonArray ?: error("Missing existing recovery slot")
            for (item in array) {
                require(bySlot.size < MAX_DIRTY_FILES) { "Too many previous recovery references" }
                val obj = item as? JsonObject ?: error("Invalid previous recovery reference")
                val path = (obj["path"] as? JsonPrimitive)?.contentOrNull ?: error("Missing previous path")
                val id = (obj["blob"] as? JsonPrimitive)?.contentOrNull ?: error("Missing previous blob")
                val hash = (obj["sha256"] as? JsonPrimitive)?.contentOrNull
                require(validPath(path) && BLOB_NAME.matches(id) && (hash == null || hash.matches(HASH_PATTERN)))
                val bound = if (version == 4) MAX_BUFFER_BYTES.toLong()
                    else (obj["max_bytes"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: error("Missing previous bound")
                require(bound in 0..MAX_BUFFER_BYTES.toLong())
                if (version >= 6) readDiskVersion(obj)
                val ref = PriorBlob(path, id, hash, bound)
                require(bySlot.put(slot to path, ref) == null) { "Duplicated previous path" }
                require(prior[id] == null || prior[id] == ref) { "Contradictory recovery blob identity" }
                prior[id] = ref
            }
        }
        require(retiring.keys.none { it in prior }) { "Active blob cannot have a retirement receipt" }
        val deferred = linkedSetOf<String>()
        for ((path, ref) in snapshot.deferredBuffers.entries + snapshot.deferredConflictBuffers.entries) {
            require(validPath(path) && ref.path == path && ref.owner === this && BLOB_NAME.matches(ref.blobName))
            deferred += ref.blobName
        }
        val requested = linkedMapOf<Pair<String, String>, String>()
        snapshot.dirtyBuffers.forEach { (path, text) -> requested["dirty" to path] = sha256(text) }
        snapshot.conflictBuffers.forEach { (path, text) -> requested["conflicts" to path] = sha256(text) }
        val reuseCandidates = requested.mapNotNull { (key, hash) -> bySlot[key]?.takeIf { it.hash == hash }?.id }.toSet()
        val inspectIds = prior.keys.filterTo(linkedSetOf()) { it !in deferred || it in reuseCandidates }
        inspectIds.removeAll(preserved.keys)
        val orphans = linkedSetOf<String>()
        if (blobs.isDirectory) Files.newDirectoryStream(blobs.toPath()).use { children ->
            var seen = 0
            for (child in children) {
                context.ensureActive(); require(++seen <= MAX_BLOB_DIRECTORY_ENTRIES) { "Recovery directory exceeds inspection limit" }
                val name = child.fileName.toString()
                if (BLOB_NAME.matches(name) && Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) &&
                    name !in prior && name !in preserved && name !in deferred && name !in retiring) orphans += name
            }
        }
        require(preserved.size + orphans.size <= MAX_PRESERVED_BLOBS) { "Recovery evidence entry limit reached" }

        var inspectBytes = 0L
        val pendingFiles = retiring.keys.filter { id ->
            val blob = File(blobs, id); requireRegularOrAbsent(blob)
            Files.exists(blob.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        for (id in preserved.keys + inspectIds + orphans + pendingFiles) {
            val blob = File(blobs, id); requireRegularOrAbsent(blob)
            if (id in preserved && !Files.exists(blob.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
            require(blob.isFile && blob.length() in 0..MAX_ENCRYPTED_BUFFER_BYTES) { "Recovery ciphertext is missing or oversized" }
            inspectBytes += blob.length()
            require(inspectBytes <= MAX_INSPECTION_BYTES) { "Recovery retirement inspection exceeds its byte budget" }
        }
        requirePreservationCapacity(preserved, additionalBytes = orphans.sumOf { File(blobs, it).length() })
        val issues = inspectPreserved(preserved, context)
        require(issues.none { it.blobName in prior || it.blobName in deferred }) {
            "A live recovery reference has changed archived evidence; data was preserved"
        }
        evidenceIssues.value = issues
        // A prior rename may have succeeded even when its directory sync failed. Confirm the visible
        // receipt manifest before any retry can unlink its payloads.
        if (retiring.isNotEmpty()) syncDirectory(root)
        // Resume only durable, previously verified retirements. A missing leaf is already done; a
        // changed leaf is evidence, never a fresh deletion authorization.
        for (id in pendingFiles) {
            val blob = File(blobs, id)
            val observed = cipherIdentity(blob, context)
            if (observed != retiring.getValue(id)) {
                preserved[id] = PreservedBlob(id, observed, "retirement_changed", null, null, null)
            } else if (id !in deferred) {
                context.ensureActive()
                deleteOwnedBlob(blob)
            }
        }
        if (retiring.isNotEmpty()) {
            // A new manifest may forget these receipts only after absence is durable, including a retry
            // where the previous attempt already unlinked the leaves.
            syncDirectory(if (blobs.isDirectory) blobs else root)
        }
        val verified = linkedMapOf<String, CipherIdentity>()
        for (id in inspectIds) {
            context.ensureActive()
            val ref = prior.getValue(id)
            val bytes = readEncrypted(File(blobs, id), MAX_ENCRYPTED_BUFFER_BYTES, allowEmpty = true)
            val identity = CipherIdentity(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) })
            val usable = try {
                val plain = cipher.decrypt(bytes, blobAad(ref.path))
                plain.size <= MAX_BUFFER_BYTES && plain.size <= ref.maxBytes &&
                    (ref.hash == null || sha256(plain.toString(Charsets.UTF_8)) == ref.hash)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            context.ensureActive()
            if (usable) verified[id] = identity
            else preserved[id] = PreservedBlob(id, identity, "verification_failed", ref.path, ref.hash, ref.maxBytes)
        }
        for (id in orphans) preserved[id] = PreservedBlob(id, cipherIdentity(File(blobs, id), context), "orphan", null, null, null)
        requirePreservationCapacity(preserved, issues.associate { it.blobName to (it.observedBytes ?: 0L) })
        val reuse = requested.mapNotNull { (key, hash) -> bySlot[key]?.takeIf {
            it.hash == hash && it.id in verified && it.id !in preserved
        }?.let { key to it.id } }.toMap()
        val retained = deferred + reuse.values + preserved.keys
        val retire = verified.filterKeys { it !in retained }
        require(retire.values.sumOf { it.bytes } <= MAX_GENERATION_CIPHERTEXT_BYTES) { "Retirement receipts exceed byte budget" }
        return BlobPlan(preserved, reuse, retire)
    }

    private fun cleanVerifiedBlobs(plan: BlobPlan, retained: Set<String>, context: kotlin.coroutines.CoroutineContext) {
        for ((id, observed) in plan.retire) {
            context.ensureActive()
            check(id !in plan.preserved && id !in retained)
            val blob = File(blobs, id)
            check(cipherIdentity(blob, context) == observed) {
                "Recovery retirement candidate changed; its bytes were preserved for the next review"
            }
            deleteOwnedBlob(blob)
        }
        if (plan.retire.isNotEmpty()) syncDirectory(blobs)
    }

    private fun writeNewBlob(target: File, bytes: ByteArray, context: kotlin.coroutines.CoroutineContext) {
        require(bytes.size.toLong() in 1..MAX_ENCRYPTED_BUFFER_BYTES) { "Encrypted recovery buffer exceeds safety limit" }
        requireStorage(create = false)
        // New payload generation must never replace an existing or preserved leaf.
        FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) { context.ensureActive(); out.write(buffer) }
            out.force(true)
        }
        context.ensureActive()
        syncDirectory(blobs)
    }

    private fun deleteOwnedBlob(blob: File) {
        requireStorage(create = false)
        if (blob.parentFile != blobs || !BLOB_NAME.matches(blob.name)) return
        // Only flat generated regular leaves are eligible. Unknown names, directories, and symbolic
        // links are retained; no recursive cleanup can enter a project/dependency.
        if (Files.isRegularFile(blob.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(blob.toPath())
    }

    private fun atomicWrite(target: File, bytes: ByteArray, discard: ConflictDiscardSelection? = null) {
        requireStorage(create = true)
        requireRegularOrAbsent(target)
        val tmp = File(target.parentFile, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) out.write(buffer)
                out.force(true)
            }
            requireStorage(create = false)
            requireRegularOrAbsent(target)
            discard?.let { pendingDiscard = it; it.phase = DiscardPhase.UNCERTAIN }
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(target.parentFile)
            discard?.let { markDiscardDurable(it) }
        } finally {
            requireStorage(create = false)
            if (Files.isRegularFile(tmp.toPath(), LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(tmp.toPath())
        }
    }

    private fun syncRecoveryHierarchy() {
        // Retry also confirms any mkdir whose previous parent-directory sync failed.
        syncDirectory(appFilesDir)
        syncDirectory(metadataRoot)
        syncDirectory(root)
    }

    private fun syncDirectory(directory: File) {
        requireStorage(create = false)
        directorySyncOverride?.let { sync -> sync(directory); return }
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) { "Editor recovery sync target is not a directory" }
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
    }

    companion object {
        // Serialize separate owners of the same app-local project within this process. This is not an
        // exclusion fence for another OS process or arbitrary filesystem writers.
        private val storageMutexes = mutableMapOf<String, java.lang.ref.WeakReference<Mutex>>()
        @Synchronized private fun storageMutex(key: String): Mutex {
            storageMutexes.entries.removeAll { it.value.get() == null }
            return storageMutexes[key]?.get() ?: Mutex().also { storageMutexes[key] = java.lang.ref.WeakReference(it) }
        }
        internal fun isRecoverablePath(path: String): Boolean {
            if (path.isBlank() || path.length > 1_024 || File(path).isAbsolute || '\u0000' in path) return false
            return path.split('/').none { it == "." || it == ".." }
        }
        /** Preflight for duplicating a retained copy without making the next checkpoint exceed its
         * budget. Call off Main. Unknown legacy bounds require normal recovery/checkpoint first. */
        internal fun requireAdditionalCopyCapacity(snapshot: EditorRecoverySnapshot, additionalBytes: Long) {
            require(snapshot.dirtyPaths.size + snapshot.conflictPaths.size < MAX_DIRTY_FILES) {
                "Recovery has 256 entries. Save or close other unsaved copies before opening another copy."
            }
            var total = 0L
            fun account(bytes: Long) {
                require(bytes in 0..MAX_BUFFER_BYTES.toLong()) { "Recovery copy exceeds the 20 MiB editor limit" }
                total = Math.addExact(total, bytes)
                require(total <= MAX_TOTAL_BUFFER_BYTES) {
                    "Recovery needs more than 40 MiB. Save or close other unsaved copies before opening this copy. Originals were kept."
                }
            }
            snapshot.dirtyBuffers.values.forEach { account(it.toByteArray(Charsets.UTF_8).size.toLong()) }
            snapshot.conflictBuffers.values.forEach { account(it.toByteArray(Charsets.UTF_8).size.toLong()) }
            (snapshot.deferredBuffers.values + snapshot.deferredConflictBuffers.values).forEach {
                account(requireNotNull(it.maxContentBytes) { "Recovery size is unknown. Finish loading recovery before opening another copy." })
            }
            account(additionalBytes)
        }
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
        private const val MAX_PRESERVED_BLOBS = 256
        private const val MAX_PRESERVED_BYTES = 64L * 1024 * 1024
        private const val MAX_GENERATION_CIPHERTEXT_BYTES = 64L * 1024 * 1024
        private const val MAX_INSPECTION_BYTES = 192L * 1024 * 1024
        private const val MAX_BLOB_DIRECTORY_ENTRIES = 4096
        private const val MAX_OPEN_FILES = 32
        private const val MAX_SELECTION_OFFSET = 100_000_000
        private const val MAX_DIRTY_FILES = 256
        private const val MAX_SNAPSHOT_ENTRIES = MAX_OPEN_FILES + MAX_DIRTY_FILES
        // Recovery must not become the hidden bottleneck after the editor enters large-file mode.
        private const val MAX_BUFFER_BYTES = 20 * 1024 * 1024
        private const val MAX_TOTAL_BUFFER_BYTES = 40 * 1024 * 1024
        private const val MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024
        private const val MAX_FILE_BYTES = 65L * 1024L * 1024L
        private const val MAX_ENCRYPTED_BUFFER_BYTES = 21L * 1024L * 1024L
        private val BLOB_NAME = Regex("[0-9a-f-]{36}\\.bin")
    }
}
