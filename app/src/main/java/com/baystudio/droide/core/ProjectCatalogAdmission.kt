package com.baystudio.droide.core

import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class UndeclaredCatalogUpdateException(val retainedLocation: File) : IllegalStateException(
    "Catalog update data at ${retainedLocation.path} needs review before this startup route can be used. " +
        "Catalog and project files were preserved.",
)

// Records an explicit startup choice; absence never authorizes setup or legacy fallback.
internal class ProjectCatalogAdmission(
    appFilesDir: File,
    private val storage: File,
    internal val maxBytes: Long,
    private val checkpoint: (String) -> Unit = {},
) {
    enum class Route { EXISTING_CATALOG, REGISTERED_CATALOG }

    @Serializable internal data class Record(
        val version: Int = 1,
        val route: Route,
        val appPath: String,
        val appKey: String,
        val metadataKey: String,
        val projectsKey: String,
        val storagePath: String,
        val storageKey: String,
        val fileKey: String,
        val id: String,
        val maxBytes: Long,
    )

    @Serializable internal data class UpdateRecord(
        val protocol: String = "journaled-catalog-v1",
        val admission: Record,
        val enrollment: CanonicalFileOperationJournal.Enrollment,
    )

    class UpdateSelection internal constructor(private val owner: ProjectCatalogAdmission, private val record: UpdateRecord) {
        internal val appFilesDir: File get() = owner.appFilesDir

        internal fun requireCurrentBinding(context: CoroutineContext) {
            check(owner.readUpdateRecord(context, bindingOnly = true) == record) {
                "Catalog update selection disappeared or changed; retained work requires review"
            }
        }

        internal fun guardedUpdate(retention: CanonicalJournalRetention.Policy, context: CoroutineContext,
                                   checkpoint: (String) -> Unit): ProjectCatalogUpdate {
            requireCurrentBinding(context)
            return ProjectCatalogUpdate(owner.appFilesDir, record.enrollment, owner.updateMembership,
                owner.maxBytes, retention) { point ->
                checkpoint(point)
                requireCurrentBinding(context)
            }
        }

        internal suspend fun persistedRetentionPolicy(): CanonicalJournalRetention.Policy = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            requireCurrentBinding(context)
            val policy = CanonicalFileOperationJournal(owner.metadata, File(record.enrollment.authorityPath),
                record.enrollment, owner.maxBytes).readRetentionPolicy(context)
            requireCurrentBinding(context)
            context.ensureActive()
            policy
        }

        suspend fun readReadyCatalog(): ProjectCatalogSnapshot = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            fun requireCurrent() {
                check(owner.readUpdateRecord(context) == record) {
                    "Catalog update selection disappeared or changed; retained work requires review"
                }
            }
            requireCurrent()
            val current = owner.updateReader(record.enrollment).readReadyCatalog()
            requireCurrent()
            context.ensureActive()
            current
        }

        suspend fun inspectRecovery(): CanonicalFileOperationJournal.RecoveryMetadata = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            requireCurrentBinding(context)
            val history = owner.updateReader(record.enrollment).inspect()
            requireCurrentBinding(context)
            context.ensureActive()
            history
        }

        suspend fun inspectOperationRecovery(operationId: String): CanonicalFileOperationJournal.OperationRecoveryReview =
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                requireCurrentBinding(context)
                val review = owner.updateReader(record.enrollment).inspectOperationRecovery(operationId)
                requireCurrentBinding(context)
                context.ensureActive()
                review
            }

        suspend fun readRetainedContent(operationId: String, role: CanonicalFileOperationJournal.CustodyRole,
                                       expected: CanonicalFileSnapshot.Version): CanonicalFileOperationJournal.RetainedContent =
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                requireCurrentBinding(context)
                val content = owner.updateReader(record.enrollment).readRetainedContent(operationId, role, expected)
                requireCurrentBinding(context)
                context.ensureActive()
                content
            }
    }

    class Selection internal constructor(private val owner: ProjectCatalogAdmission, private val record: Record) {
        val route: Route get() = record.route
        internal val appFilesDir: File get() = owner.appFilesDir
        internal val slot: File get() = owner.slot
        internal val membership: File get() = owner.membership
        internal val maxBytes: Long get() = owner.maxBytes

        fun registeredStartup(): InitialProjectRegistration.Startup {
            check(route == Route.REGISTERED_CATALOG) { "Existing-catalog admission does not authorize registered setup" }
            return InitialProjectRegistration.Startup(this)
        }

        internal fun requireCurrent(context: CoroutineContext) {
            check(owner.readRecord(context) == record) {
                "Catalog startup admission disappeared or changed; existing workspace data was preserved"
            }
        }
    }

    internal val appFilesDir = appFilesDir.canonicalFile
    internal val slot = File(storage, "initial-project-registration")
    internal val membership = File(storage, "catalog-membership.json")
    internal val updateSlot = File(storage, "catalog-updates")
    internal val updateMembership = File(storage, "catalog-update-membership.json")
    private val metadata = File(this.appFilesDir, ".droide")
    private val store = FileProjectStore(this.appFilesDir)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    init {
        require(maxBytes > 0 && maxBytes <= Long.MAX_VALUE / 3L)
        require(storage.isAbsolute && storage == storage.canonicalFile)
        val projects = ProjectWorkspacePolicy.projectsRoot(this.appFilesDir).toPath()
        require(!storage.toPath().startsWith(metadata.toPath()) && !metadata.toPath().startsWith(storage.toPath()) &&
            !storage.toPath().startsWith(projects) && !projects.startsWith(storage.toPath())) {
            "Startup admission must remain outside catalog and project storage"
        }
    }

    suspend fun inspect(): Selection? = withContext(Dispatchers.IO) {
        readRecord(currentCoroutineContext())?.let { Selection(this@ProjectCatalogAdmission, it) }
    }

    suspend fun inspectUpdateChoice(): UpdateSelection? = withContext(Dispatchers.IO) {
        readUpdateRecord(currentCoroutineContext())?.let { UpdateSelection(this@ProjectCatalogAdmission, it) }
    }

    // Explicit, read-only admission; the legacy manager cannot accept this selection type.
    suspend fun recordUpdateChoice(enrollment: CanonicalFileOperationJournal.Enrollment): UpdateSelection =
        withContext(Dispatchers.IO) {
            ProjectCatalogMutationLocks.withUpdateChoice(appFilesDir) {
                val context = currentCoroutineContext()
                context.ensureActive()
                AnchoredWorkspaceFiles(appFilesDir).use { app ->
                    AnchoredWorkspaceFiles(storage).use { files ->
                        requireNoPending(files)
                        check(files.kind(RECORD) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                            "Catalog startup choice already exists; no migration or replacement was performed"
                        }
                        requireNoRegistration(files)
                        val appKey = app.directoryKey()
                        val metadataKey = app.directoryKey(".droide")
                        val projectsKey = app.directoryKey("projects")
                        val storageKey = files.directoryKey()
                        requireUpdateAuthority(enrollment, context)
                        val reader = updateReader(enrollment)
                        val catalog = reader.readReadyCatalog()
                        check(catalog.revision.existed && catalog.projects.isNotEmpty()) {
                            "Catalog update selection requires a readable saved catalog"
                        }
                        checkpoint("update-choice-validated")
                        context.ensureActive()
                        publishChoice(files, context) {
                            val record = files.createNew(PENDING).use { output ->
                                val chosen = UpdateRecord(admission = Record(route = Route.EXISTING_CATALOG,
                                    appPath = appFilesDir.path, appKey = appKey, metadataKey = metadataKey,
                                    projectsKey = projectsKey, storagePath = storage.path, storageKey = storageKey,
                                    fileKey = output.observe().attributes.fileKey().toString(),
                                    id = UUID.randomUUID().toString(), maxBytes = maxBytes), enrollment = enrollment)
                                val bytes = json.encodeToString(chosen).toByteArray(Charsets.UTF_8)
                                require(bytes.size in 1..MAX_RECORD_BYTES)
                                output.write(bytes)
                                checkpoint("update-choice-before-force")
                                context.ensureActive()
                                output.force(384)
                                requireUpdateRecord(app, files, PENDING, chosen, context)
                                checkpoint("update-choice-before-publication")
                                context.ensureActive()
                                check(reader.readReadyCatalog().revision == catalog.revision) {
                                    "Catalog changed during update selection; all evidence was retained"
                                }
                                requireUpdateRecord(app, files, PENDING, chosen, context)
                                val outcome = files.moveNoReplaceDurable(PENDING, files, RECORD)
                                checkpoint("update-choice-after-publication")
                                check(outcome.durable) {
                                    "Catalog update selection publication is uncertain (published=${outcome.published}, errno=${outcome.error}); retained records need review"
                                }
                                chosen
                            }
                            check(readUpdateRecord(context) == record) { "Catalog update selection changed after publication" }
                            check(reader.readReadyCatalog().revision == catalog.revision) {
                                "Catalog changed after update selection; all evidence was retained"
                            }
                            context.ensureActive()
                            UpdateSelection(this@ProjectCatalogAdmission, record)
                        }
                    }
                }
            }
        }

    suspend fun recordExplicitChoice(route: Route): Selection = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        context.ensureActive()
        AnchoredWorkspaceFiles(appFilesDir).use { app ->
            AnchoredWorkspaceFiles(storage).use { files ->
                check(readRecord(app, files, context) == null) { "Catalog startup choice is already recorded; it was not replaced" }
                requireNoRegistration(files)
                val appKey = app.directoryKey()
                val metadataKey = app.directoryKey(".droide")
                val projectsKey = app.directoryKey("projects")
                val storageKey = files.directoryKey()
                val catalog = store.loadCatalog()
                when (route) {
                    Route.EXISTING_CATALOG -> check(catalog.revision.existed && catalog.projects.isNotEmpty()) {
                        "Existing-catalog admission requires a readable saved catalog"
                    }
                    Route.REGISTERED_CATALOG -> {
                        check(!catalog.revision.existed) { "A saved catalog already exists; no new setup was admitted" }
                        AnchoredWorkspaceFiles(ProjectWorkspacePolicy.projectsRoot(appFilesDir)).use { projects ->
                            projects.forEachChild { error("Project files already exist; no new setup was admitted") }
                        }
                    }
                }
                checkpoint("choice-validated")
                context.ensureActive()
                publishChoice(files, context) {
                    val record = files.createNew(PENDING).use { output ->
                        val chosen = Record(route = route, appPath = appFilesDir.path, appKey = appKey,
                            metadataKey = metadataKey, projectsKey = projectsKey,
                            storagePath = storage.path, storageKey = storageKey,
                            fileKey = output.observe().attributes.fileKey().toString(), id = UUID.randomUUID().toString(),
                            maxBytes = maxBytes)
                        val bytes = json.encodeToString(chosen).toByteArray(Charsets.UTF_8)
                        require(bytes.size in 1..MAX_RECORD_BYTES)
                        output.write(bytes)
                        checkpoint("choice-before-force")
                        context.ensureActive()
                        output.force(384)
                        requireRecord(app, files, PENDING, chosen, context)
                        checkpoint("choice-before-publication")
                        context.ensureActive()
                        requireNoRegistration(files)
                        check(store.loadCatalog().revision == catalog.revision) { "Project catalog changed during startup admission" }
                        requireRecord(app, files, PENDING, chosen, context)
                        val outcome = files.moveNoReplaceDurable(PENDING, files, RECORD)
                        checkpoint("choice-after-publication")
                        check(outcome.durable) {
                            "Startup admission publication is uncertain (published=${outcome.published}, errno=${outcome.error}); retained records need review"
                        }
                        chosen
                    }
                    check(readRecord(app, files, context) == record) { "Startup admission changed after publication" }
                    check(store.loadCatalog().revision == catalog.revision) { "Project catalog changed after startup admission" }
                    context.ensureActive()
                    Selection(this@ProjectCatalogAdmission, record)
                }
            }
        }
    }

    private suspend fun <T> publishChoice(files: AnchoredWorkspaceFiles, context: CoroutineContext,
                                         publish: suspend () -> T): T = publicationMutex(storage.path).withLock {
        context.ensureActive()
        requireNoPending(files)
        check(files.kind(RECORD) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            "Catalog startup choice already exists; no replacement or pending record was created"
        }
        publish()
    }

    private fun readRecord(context: CoroutineContext): Record? {
        context.ensureActive()
        return AnchoredWorkspaceFiles(appFilesDir).use { app ->
            AnchoredWorkspaceFiles(storage).use { files -> readRecord(app, files, context) }
        }
    }

    private fun readRecord(app: AnchoredWorkspaceFiles, files: AnchoredWorkspaceFiles, context: CoroutineContext): Record? {
        context.ensureActive()
        requireNoPending(files)
        requireNoUndeclaredUpdates(files)
        if (files.kind(RECORD) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            requireNoRegistration(files)
            return null
        }
        val record = json.decodeFromString<Record>(readText(files, RECORD, context))
        requireRecord(app, files, RECORD, record, context)
        if (record.route == Route.EXISTING_CATALOG) requireNoRegistration(files)
        requireNoPending(files)
        return record
    }

    private fun requireRecord(app: AnchoredWorkspaceFiles, files: AnchoredWorkspaceFiles, name: String,
                              record: Record, context: CoroutineContext) {
        requireIdentity(app, files, name, record, context)
        check(readText(files, name, context) == json.encodeToString(record)) {
            "Startup admission changed; no replacement choice was made"
        }
        requireNoUndeclaredUpdates(files)
    }

    private fun requireIdentity(app: AnchoredWorkspaceFiles, files: AnchoredWorkspaceFiles, name: String,
                                record: Record, context: CoroutineContext) {
        context.ensureActive()
        check(record.version == 1 && record.appPath == appFilesDir.path && record.appKey == app.directoryKey() &&
            record.metadataKey == app.directoryKey(".droide") && record.projectsKey == app.directoryKey("projects") &&
            record.storagePath == storage.path && record.storageKey == files.directoryKey() &&
            record.fileKey == files.openRead(name).use { it.observe().attributes.fileKey().toString() } &&
            record.maxBytes == maxBytes && record.id == UUID.fromString(record.id).toString()) {
            "Startup admission is unsupported, foreign or changed; no replacement choice was made"
        }
    }

    private fun readUpdateRecord(context: CoroutineContext, bindingOnly: Boolean = false): UpdateRecord? {
        context.ensureActive()
        return AnchoredWorkspaceFiles(appFilesDir).use { app ->
            AnchoredWorkspaceFiles(storage).use storage@{ files ->
                requireNoPending(files)
                if (files.kind(RECORD) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    requireNoUndeclaredUpdates(files)
                    requireNoRegistration(files)
                    return@storage null
                }
                val record = json.decodeFromString<UpdateRecord>(readText(files, RECORD, context))
                requireUpdateRecord(app, files, RECORD, record, context, bindingOnly)
                requireNoPending(files)
                record
            }
        }
    }

    private fun requireUpdateRecord(app: AnchoredWorkspaceFiles, files: AnchoredWorkspaceFiles, name: String,
                                    record: UpdateRecord, context: CoroutineContext, bindingOnly: Boolean = false) {
        requireIdentity(app, files, name, record.admission, context)
        check(record.protocol == "journaled-catalog-v1" && record.admission.route == Route.EXISTING_CATALOG) {
            "Catalog update selection is unsupported; retained work requires review"
        }
        requireNoRegistration(files)
        requireUpdateAuthority(record.enrollment, context, bindingOnly)
        check(readText(files, name, context) == json.encodeToString(record)) {
            "Catalog update selection changed; no replacement choice was made"
        }
        requireIdentity(app, files, name, record.admission, context)
    }

    private fun requireUpdateAuthority(enrollment: CanonicalFileOperationJournal.Enrollment, context: CoroutineContext,
                                       bindingOnly: Boolean = false) {
        val bootstrap = CanonicalJournalBootstrap(updateSlot, maxBytes)
        val observed = if (bindingOnly) {
            bootstrap.readBindingWithMembership(metadata, ProjectCatalogUpdate.AUTHORITY_ID, updateMembership, context)
        } else bootstrap.inspectWithMembership(metadata, ProjectCatalogUpdate.AUTHORITY_ID, updateMembership, context).enrollment
        check(observed == enrollment) {
            "Catalog update authority differs from the saved selection; retained work requires review"
        }
    }

    private fun updateReader(enrollment: CanonicalFileOperationJournal.Enrollment) = ProjectCatalogUpdate(
        appFilesDir, enrollment, updateMembership, maxBytes, CanonicalJournalRetention.Policy(1, maxBytes * 3L),
    )

    private fun requireNoUndeclaredUpdates(files: AnchoredWorkspaceFiles) {
        for (name in listOf(updateSlot.name, updateMembership.name, ".${updateMembership.name}.pending")) {
            if (files.kind(name) != AnchoredWorkspaceFiles.Kind.ABSENT) {
                throw UndeclaredCatalogUpdateException(File(storage, name))
            }
        }
    }

    private fun requireNoRegistration(files: AnchoredWorkspaceFiles) {
        check(listOf(slot.name, membership.name, ".${membership.name}.pending").all {
            files.kind(it) == AnchoredWorkspaceFiles.Kind.ABSENT
        }) { "Registration evidence already exists; inspect it before choosing a startup route" }
    }

    private fun requireNoPending(files: AnchoredWorkspaceFiles) {
        check(files.kind(PENDING) == AnchoredWorkspaceFiles.Kind.ABSENT) {
            "Startup admission has an incomplete or competing publication; retained records need review"
        }
    }

    private fun readText(files: AnchoredWorkspaceFiles, name: String, context: CoroutineContext): String =
        readChoiceText(files, name, context, checkpoint)

    companion object {
        internal suspend fun restoreSavedUpdate(appFilesDir: File, checkpoint: (String) -> Unit = {}): UpdateSelection? = withContext(Dispatchers.IO) {
            val app = appFilesDir.canonicalFile
            val storage = File(app, "catalog-startup")
            val context = currentCoroutineContext()
            val text = AnchoredWorkspaceFiles(app).use { root ->
                if (root.kind(storage.name) == AnchoredWorkspaceFiles.Kind.ABSENT) return@withContext null
                root.openDirectory(storage.name).use { files ->
                    check(files.kind(PENDING) == AnchoredWorkspaceFiles.Kind.ABSENT) { "Startup choice publication is incomplete" }
                    if (files.kind(RECORD) == AnchoredWorkspaceFiles.Kind.ABSENT) return@withContext null
                    readChoiceText(files, RECORD, context, checkpoint)
                }
            }
            val decoded = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }.decodeFromString<UpdateRecord>(text)
            // The first decode supplies only a bounded scalar, never a path or authority permission.
            val owner = ProjectCatalogAdmission(app, storage, decoded.admission.maxBytes, checkpoint)
            val record = checkNotNull(owner.readUpdateRecord(context))
            check(owner.json.encodeToString(record) == text) { "Saved choice changed during restoration" }
            context.ensureActive()
            UpdateSelection(owner, record)
        }

        private fun readChoiceText(files: AnchoredWorkspaceFiles, name: String, context: CoroutineContext,
                                   checkpoint: (String) -> Unit): String =
        files.openRead(name).use { input ->
            val before = input.observe()
            check(before.attributes.size() in 1..MAX_RECORD_BYTES.toLong()) { "Startup admission is empty or oversized" }
            val bytes = ByteArray(before.attributes.size().toInt())
            var offset = 0
            while (offset < bytes.size) {
                context.ensureActive()
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "Startup admission was truncated" }
                offset += count
            }
            check(input.read() == -1) { "Startup admission grew while reading" }
            checkpoint("choice-read")
            context.ensureActive()
            fun requireSame(observed: AnchoredWorkspaceFiles.Observation) {
                check(before.attributes.fileKey() == observed.attributes.fileKey() &&
                    before.attributes.size() == observed.attributes.size() &&
                    before.attributes.lastModifiedTime() == observed.attributes.lastModifiedTime() && before.changed == observed.changed) {
                    "Startup admission changed while reading"
                }
            }
            requireSame(input.observe())
            files.openRead(name).use { requireSame(it.observe()) }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }

        // Cooperating callers only; external writers still require native no-replace and retained-conflict checks.
        private val publicationLocks = mutableMapOf<String, WeakReference<Mutex>>()
        private fun publicationMutex(key: String): Mutex = synchronized(publicationLocks) {
            publicationLocks.entries.removeAll { it.value.get() == null }
            publicationLocks[key]?.get() ?: Mutex().also { publicationLocks[key] = WeakReference(it) }
        }

        private const val RECORD = "startup-admission.json"
        private const val PENDING = ".startup-admission.json.pending"
        private const val MAX_RECORD_BYTES = 16_384
    }
}
