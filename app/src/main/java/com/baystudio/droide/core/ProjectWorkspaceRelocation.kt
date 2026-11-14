package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Cooperative project registry authority. It neither excludes arbitrary writers nor releases payloads. */
internal class ProjectWorkspaceRelocation(
    appFilesDir: File,
    private val checkpoint: (String) -> Unit = {},
    private val moveDirectory: (AnchoredWorkspaceFiles, String, String) -> AnchoredWorkspaceFiles.MoveReceipt =
        { files, source, destination -> files.moveNoReplaceDurable(source, files, destination) },
) {
    private val app = appFilesDir.canonicalFile
    private val metadata = File(app, ".droide")
    private val root get() = ProjectWorkspacePolicy.projectsRoot(app)
    private val mutex = mutexFor(app.path)
    private var recordSlots = emptySet<String>()
    private data class RecordVersion(
        val key: String,
        val bytes: Long,
        val modified: java.nio.file.attribute.FileTime,
        val changed: java.nio.file.attribute.FileTime,
    )
    private fun versionOf(observed: AnchoredWorkspaceFiles.Observation) = RecordVersion(
        checkNotNull(observed.attributes.fileKey()).toString(), observed.attributes.size(),
        observed.attributes.lastModifiedTime(), observed.changed,
    )
    private val observedRecords = mutableMapOf<Intent, RecordVersion>()
    private var ownedOperation: String? = null // Access only under the shared authority.

    // Owned by this authority frame, never a global readiness assertion. Both names
    // stay reserved through publication and exact-intent recovery, including cancellation.
    private class NamespaceLease(val intent: Intent, private val captures: List<AutoCloseable>) : AutoCloseable {
        override fun close() {
            var failure: Throwable? = null
            for (capture in captures.asReversed()) try { capture.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }
    private var namespaceLease: NamespaceLease? = null

    private fun acquireNamespace(intent: Intent): NamespaceLease {
        val paths = listOf(File(root, intent.source).canonicalFile,
            File(root, quarantine(intent.projectId)).canonicalFile).sortedBy { it.path }
        check(paths[0] != paths[1])
        val captures = mutableListOf<AutoCloseable>()
        try {
            for (path in paths) captures += ExportWriterAdmission.beginCapture(path, "Workspace relocation")
            return NamespaceLease(intent, captures.toList())
        } catch (failure: Throwable) {
            try { NamespaceLease(intent, captures).close() } catch (closeFailure: Throwable) {
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    private inline fun <T> withRecoveryNamespace(intent: Intent, required: Boolean, action: () -> T): T {
        if (!required) return action()
        val held = namespaceLease
        if (held != null) {
            check(held.intent == intent) { "Relocation admission belongs to a different intent" }
            return action()
        }
        return acquireNamespace(intent).use { action() }
    }

    private interface RegistryLease : java.io.Closeable {
        val identity: String
        fun requireHeld()
    }
    private var heldLease: RegistryLease? = null
    private var readOnlyScope = false

    fun requireHeld() { heldLease?.requireHeld() }

    // Lock order: shared registry mutex, dedicated inode lease, manager recents lock,
    // non-waiting namespace admission, store publication. No managed-owner callbacks.
    // Keep acquisition ownership in this frame so prompt cancellation cannot leak the lease.
    suspend fun <T> withLock(readOnly: Boolean = false, action: suspend () -> T): T = mutex.withLock {
        var failure: Throwable? = null
        readOnlyScope = readOnly
        try {
            withContext(Dispatchers.IO) { heldLease = acquire(readOnly = true) }
            requireHeld()
            action().also { requireHeld() }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            ownedOperation = null
            observedRecords.clear()
            recordSlots = emptySet()
            var releaseFailure: Throwable? = null
            try { namespaceLease?.close() } catch (caught: Throwable) { releaseFailure = caught }
            finally { namespaceLease = null }
            try {
                try { heldLease?.close() } catch (caught: Throwable) {
                    if (releaseFailure == null) releaseFailure = caught
                    else if (releaseFailure !== caught) releaseFailure.addSuppressed(caught)
                }
                releaseFailure?.let { throw it }
            } catch (closeFailure: Throwable) {
                if (failure == null) throw closeFailure
                if (failure !== closeFailure) failure.addSuppressed(closeFailure)
            } finally {
                heldLease = null
                readOnlyScope = false
            }
        }
    }

    // Healthy writable startup or a requested new relocation enrolls this authority only
    // after existing startup evidence has passed its own admission checks.
    suspend fun activate() {
        check(!readOnlyScope) { "Read-only inspection cannot enroll workspace relocation authority" }
        if (heldLease == null) withContext(Dispatchers.IO) { heldLease = acquire(readOnly = false) }
        requireHeld()
    }

    private fun acquire(readOnly: Boolean): RegistryLease? {
        val lockFile = File(metadata, "workspace-relocation.lock")
        if (readOnly && !Files.exists(lockFile.toPath(), NOFOLLOW_LINKS)) {
            check(records().isEmpty()) { "Workspace relocation authority is missing; retained evidence requires review" }
            return null
        }
        AnchoredWorkspaceFiles(app).use { parent ->
            if (!Files.exists(metadata.toPath(), NOFOLLOW_LINKS)) check(parent.createDirectory(metadata.name).durable)
            parent.directoryKey(metadata.name)
        }
        val anchor = AnchoredWorkspaceFiles(metadata)
        try {
            if (!Files.exists(lockFile.toPath(), NOFOLLOW_LINKS)) {
                try { anchor.createNew(lockFile.name).use { it.force(384) } }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* Another cooperative runtime enrolled it. */ }
            }
            val input = anchor.openRead(lockFile.name)
            try {
                val before = input.observe().attributes
                check(before.isRegularFile && before.size() == 0L && before.fileKey() != null) {
                    "Invalid relocation lock; evidence was preserved"
                }
                val lock = input.acquireExclusiveMetadataLease()
                try {
                    anchor.directoryKey()
                    check(Files.readAttributes(lockFile.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == before.fileKey())
                    return object : RegistryLease {
                        override val identity = before.fileKey().toString()

                        override fun requireHeld() {
                            lock.requireHeld()
                            anchor.directoryKey()
                            check(Files.readAttributes(lockFile.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == before.fileKey()) {
                                "Workspace relocation lock identity changed; retained evidence was preserved"
                            }
                        }
                        override fun close() {
                            try { lock.close() } finally {
                                try { input.close() } finally { anchor.close() }
                            }
                        }
                    }
                } catch (failure: Throwable) { lock.close(); throw failure }
            } catch (failure: Throwable) { input.close(); throw failure }
        } catch (failure: Throwable) { anchor.close(); throw failure }
    }

    enum class State { PREPARED, RESTORED, COMMITTED }

    data class Intent(
        val id: String,
        val projectId: String,
        val source: String,
        val sourceKey: String,
        val appKey: String,
        val containerKey: String,
        val metadataKey: String,
        val lockKey: String,
        val before: DiskFileVersion,
        val after: DiskFileVersion,
        val state: State,
    )

    suspend fun prepare(
        project: DroideProject,
        before: List<DroideProject>,
        after: List<DroideProject>,
        store: ProjectStore,
    ): Intent {
        activate()
        recover(store)
        val disk = diskCatalog(store)
        check(disk.projects == before) { "Project catalog changed before relocation; no tree was moved" }
        val source = ProjectWorkspacePolicy.requireDirectChild(root, File(project.rootPath))
        val prior = recordFile(project.id)
        val old = if (Files.exists(prior.toPath(), NOFOLLOW_LINKS)) read(prior) else null
        check(old == null || old.state == State.RESTORED) { "Previous workspace relocation is not restored" }
        if (old == null) check(prior.name in recordSlots || recordSlots.size < MAX_RECORDS) { "Workspace relocation evidence is full; existing trees were retained" }
        val intent = AnchoredWorkspaceFiles(root).use { files ->
            check(files.kind(quarantine(project.id)) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Existing deletion evidence needs review" }
            Intent(UUID.randomUUID().toString(), project.id, source.name, files.directoryKey(source.name),
                AnchoredWorkspaceFiles(app).use { it.directoryKey() }, files.directoryKey(),
                AnchoredWorkspaceFiles(metadata).use { it.directoryKey() }, checkNotNull(heldLease).identity, disk.revision,
                DiskFileVersion.fromText(FileProjectStore(app).encodeCatalog(after)), State.PREPARED)
        }
        val stage = File(metadata, prior.name + ".stage")
        val prepared = if (Files.exists(stage.toPath(), NOFOLLOW_LINKS)) {
            val staged = read(stage)
            check(staged.copy(id = intent.id) == intent) { "Existing staged intent does not match this deletion; evidence preserved" }
            staged
        } else intent
        check(namespaceLease == null) { "A relocation already owns namespace admission" }
        namespaceLease = acquireNamespace(prepared)
        save(prepared, old)
        ownedOperation = prepared.id
        return prepared
    }

    fun move(intent: Intent) {
        check(namespaceLease?.intent == intent) { "Relocation requires its exact namespace admission" }
        checkpoint("before-relocation")
        validateIdentity(intent)
        requireRecord(intent)
        AnchoredWorkspaceFiles(root).use { files ->
            check(files.directoryKey(intent.source) == intent.sourceKey)
            check(files.kind(quarantine(intent.projectId)) == AnchoredWorkspaceFiles.Kind.ABSENT)
            WorkspaceProcessPathGuard.requireUnused(File(root, intent.source))
            requireHeld()
            val receipt = moveDirectory(files, intent.source, quarantine(intent.projectId))
            check(receipt.durable) { "Workspace relocation was not durably confirmed; retained locations require recovery" }
            check(files.directoryKey(quarantine(intent.projectId)) == intent.sourceKey)
        }
    }

    suspend fun finish(store: ProjectStore) {
        ownedOperation = null
        recover(store)
    }

    fun abandon() { ownedOperation = null }

    /** Pending operations resolve only against their exact catalog before/after revisions.
     * Terminal receipts admit unrelated edits, but never a different source or retained tree.
     * Read-only recovery validates these invariants without moving or publishing anything.
     */
    suspend fun recover(store: ProjectStore, readOnly: Boolean = false) {
        val entries = records()
        if (entries.isEmpty()) return
        val intents = entries.map(::read)
        val disk = diskCatalog(store)
        for (intent in intents) {
            validateIdentity(intent)
            requireRecord(intent)
            if (intent.id == ownedOperation) continue
            withRecoveryNamespace(intent, !readOnly && intent.state == State.PREPARED) {
            AnchoredWorkspaceFiles(root).use { files ->
                val sourceKind = files.kind(intent.source)
                val retained = quarantine(intent.projectId)
                val retainedKind = files.kind(retained)
                val original = sourceKind == AnchoredWorkspaceFiles.Kind.DIRECTORY && files.directoryKey(intent.source) == intent.sourceKey
                val quarantined = retainedKind == AnchoredWorkspaceFiles.Kind.DIRECTORY && files.directoryKey(retained) == intent.sourceKey
                val owned = disk.projects.any { it.id == intent.projectId || File(it.rootPath).canonicalFile == File(root, intent.source) }
                fun restored() {
                    check(original && retainedKind == AnchoredWorkspaceFiles.Kind.ABSENT) { "Original workspace is not restored: ${File(root, intent.source)}; retained tree: ${File(root, retained)}" }
                }
                fun committed() {
                    check(!owned && sourceKind == AnchoredWorkspaceFiles.Kind.ABSENT && quarantined) { "Committed workspace relocation identity changed; both locations were preserved" }
                }
                when (intent.state) {
                    State.RESTORED -> {
                        restored()
                        check(disk.projects.singleOrNull { it.id == intent.projectId }?.let {
                            File(it.rootPath).canonicalFile == File(root, intent.source)
                        } == true && disk.projects.none { it.id != intent.projectId && File(it.rootPath).canonicalFile == File(root, intent.source) }) {
                            "Restored project registration no longer matches its recorded workspace"
                        }
                    }
                    State.COMMITTED -> committed()
                    State.PREPARED -> when (disk.revision) {
                        intent.before -> {
                            if (!original || retainedKind != AnchoredWorkspaceFiles.Kind.ABSENT) {
                                check(!readOnly && sourceKind == AnchoredWorkspaceFiles.Kind.ABSENT && quarantined) {
                                    "Workspace relocation requires recovery: ${File(root, intent.source)}; retained tree: ${File(root, retained)}"
                                }
                                checkpoint("before-restoration")
                                validateIdentity(intent)
                                requireRecord(intent)
                                WorkspaceProcessPathGuard.requireUnused(File(root, retained))
                                check(moveDirectory(files, retained, intent.source).durable) { "Workspace restoration durability is unconfirmed" }
                                check(files.directoryKey(intent.source) == intent.sourceKey && files.kind(retained) == AnchoredWorkspaceFiles.Kind.ABSENT)
                            }
                            if (!readOnly) {
                                files.forceDirectory()
                                save(intent.copy(state = State.RESTORED), intent)
                            }
                        }
                        intent.after -> {
                            committed()
                            if (!readOnly) {
                                files.forceDirectory()
                                save(intent.copy(state = State.COMMITTED), intent)
                            }
                        }
                        else -> error("Catalog changed during workspace relocation; retained evidence needs review")
                    }
                }
                // A prior rename may have become visible before its durability confirmation.
                // Writable recovery re-confirms terminal namespace and receipt persistence.
                if (!readOnly && intent.state != State.PREPARED) {
                    files.forceDirectory()
                    confirmRecord(intent)
                }
            }
            }
        }
    }

    private fun confirmRecord(intent: Intent) {
        requireRecord(intent)
        val expected = checkNotNull(observedRecords[intent])
        AnchoredWorkspaceFiles(metadata).use { anchor ->
            anchor.openRead(recordFile(intent.projectId).name).use { input ->
                check(versionOf(input.observe()) == expected)
                input.forceContents()
            }
            anchor.forceDirectory()
        }
        validateIdentity(intent)
        requireRecord(intent)
    }

    suspend fun requireRegistry(store: ProjectStore, expected: List<DroideProject>) {
        if (records().isNotEmpty()) check(diskCatalog(store).projects == expected) {
            "Project registration changed in another session; reopen before publishing a selection or catalog update"
        }
    }

    private suspend fun diskCatalog(store: ProjectStore): ProjectCatalogSnapshot {
        val disk = FileProjectStore(app).loadCatalog()
        check(disk.revision.existed && store.load() == disk.projects) { "Workspace relocation requires the exact durable disk catalog" }
        requireHeld()
        check(FileProjectStore(app).loadCatalog() == disk) { "Disk catalog changed during relocation review" }
        return disk
    }

    private fun requireRecord(intent: Intent) {
        val expected = checkNotNull(observedRecords[intent])
        check(read(recordFile(intent.projectId)) == intent && observedRecords[intent] == expected) {
            "Workspace relocation receipt identity or version changed; evidence preserved"
        }
    }

    private fun validateIdentity(intent: Intent) {
        requireHeld()
        check(checkNotNull(heldLease).identity == intent.lockKey) { "Relocation lock binding changed; evidence preserved" }
        check(AnchoredWorkspaceFiles(app).use { it.directoryKey() } == intent.appKey &&
            AnchoredWorkspaceFiles(root).use { it.directoryKey() } == intent.containerKey &&
            AnchoredWorkspaceFiles(metadata).use { it.directoryKey() } == intent.metadataKey) {
            "Workspace relocation container identity changed; existing evidence was preserved"
        }
    }

    private fun records(): List<File> {
        if (!Files.exists(metadata.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(!Files.isSymbolicLink(metadata.toPath()) && metadata.isDirectory)
        return Files.newDirectoryStream(metadata.toPath()).use { stream ->
            val slots = mutableSetOf<String>()
            buildList {
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (!name.startsWith(PREFIX)) continue
                    check(name.endsWith(".json") || name.endsWith(".json.stage")) {
                        "Unknown workspace relocation metadata was preserved"
                    }
                    slots += name.removeSuffix(".stage")
                    check(slots.size <= MAX_RECORDS) { "Workspace relocation record count exceeds its bound; evidence preserved" }
                    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    check(attributes.isRegularFile && attributes.size() <= MAX_BYTES) {
                        "Workspace relocation metadata is unsafe or oversized; evidence preserved"
                    }
                    if (name.endsWith(".json")) add(path.toFile())
                }
                recordSlots = slots
            }
        }
    }

    private fun read(file: File): Intent = AnchoredWorkspaceFiles(metadata).use { anchor ->
        var identity: RecordVersion? = null
        val text = anchor.openRead(file.name).use { input ->
            val before = input.observe()
            identity = versionOf(before)
            check(before.attributes.size() in 1..MAX_BYTES)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size().toLong() + count <= MAX_BYTES) { "Relocation record grew beyond its bound" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            check(bytes.size.toLong() == before.attributes.size())
            val after = input.observe()
            check(after.attributes.fileKey() == before.attributes.fileKey() && after.attributes.size() == before.attributes.size() && after.changed == before.changed)
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        val value = Json.parseToJsonElement(text).jsonObject
        check(value.keys == FIELDS && value.getValue("version").jsonPrimitive.int == 1)
        fun s(key: String) = value.getValue(key).jsonPrimitive.also { check(it.isString) }.content
        fun version(key: String): DiskFileVersion = DiskFileVersion(true, value.getValue(key + "Bytes").jsonPrimitive.long,
            s(key + "Hash")).also { check(it.bytes in 1..2_000_000 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
        val projectId = s("projectId").also { PathSecurity.safeLeafName(it); check(it != "default") }
        check(file.name == recordFile(projectId).name || file.name == recordFile(projectId).name + ".stage")
        val source = s("source").also { PathSecurity.safeLeafName(it); check(!it.startsWith(".delete-")) }
        Intent(s("id").also { UUID.fromString(it) }, projectId, source, s("sourceKey"), s("appKey"),
            s("containerKey"), s("metadataKey"), s("lockKey"), version("before"), version("after"), State.valueOf(s("state"))).also {
            observedRecords[it] = checkNotNull(identity)
        }
    }

    private fun save(intent: Intent, previous: Intent?) {
        validateIdentity(intent)
        val text = buildJsonObject {
            put("version", 1)
            put("id", intent.id)
            put("projectId", intent.projectId)
            put("source", intent.source)
            put("sourceKey", intent.sourceKey)
            put("appKey", intent.appKey)
            put("containerKey", intent.containerKey)
            put("metadataKey", intent.metadataKey)
            put("lockKey", intent.lockKey)
            put("beforeBytes", intent.before.bytes)
            put("beforeHash", intent.before.sha256)
            put("afterBytes", intent.after.bytes)
            put("afterHash", intent.after.sha256)
            put("state", intent.state.name)
        }.toString()
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val target = recordFile(intent.projectId)
        val stage = File(metadata, target.name + ".stage")
        val previousIdentity = previous?.let { checkNotNull(observedRecords[it]) }
        AnchoredWorkspaceFiles(metadata).use { anchor ->
            if (!Files.exists(stage.toPath(), NOFOLLOW_LINKS)) {
                anchor.createNew(stage.name).use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                    output.force(384)
                }
            }
            check(read(stage) == intent) { "Existing staged relocation evidence differs; it was preserved" }
            val stageIdentity = checkNotNull(observedRecords[intent])
            anchor.openRead(stage.name).use { input ->
                check(versionOf(input.observe()) == stageIdentity)
                input.forceContents()
            }
            anchor.forceDirectory()
            validateIdentity(intent)
            if (previous != null) {
                check(read(target) == previous && observedRecords[previous] == previousIdentity) {
                    "Workspace relocation receipt identity changed"
                }
            } else check(!Files.exists(target.toPath(), NOFOLLOW_LINKS))
            check(read(stage) == intent && observedRecords[intent] == stageIdentity)
            ProjectMetadataPublication.replace(stage.toPath(), target.toPath(),
                if (previous != null) arrayOf(java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                else arrayOf(java.nio.file.StandardCopyOption.ATOMIC_MOVE))
            checkpoint("receipt-published")
            validateIdentity(intent)
            anchor.forceDirectory()
        }
        check(read(target) == intent)
    }

    private fun recordFile(id: String) = File(metadata, "$PREFIX${PathSecurity.safeLeafName(id)}.json")
    private fun quarantine(id: String) = ".delete-${PathSecurity.safeLeafName(id)}"
    companion object {
        private val locks = mutableMapOf<String, java.lang.ref.WeakReference<kotlinx.coroutines.sync.Mutex>>()
        private fun mutexFor(path: String): kotlinx.coroutines.sync.Mutex = synchronized(locks) {
            locks.entries.removeAll { it.value.get() == null }
            locks[path]?.get() ?: kotlinx.coroutines.sync.Mutex().also { locks[path] = java.lang.ref.WeakReference(it) }
        }
        private const val PREFIX = "workspace-relocation-"
        private const val MAX_RECORDS = 128
        private const val MAX_BYTES = 16_384L
        private val FIELDS = setOf("version", "id", "projectId", "source", "sourceKey", "appKey", "containerKey", "metadataKey", "lockKey", "beforeBytes", "beforeHash", "afterBytes", "afterHash", "state")
    }
}
