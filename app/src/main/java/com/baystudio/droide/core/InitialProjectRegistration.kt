package com.baystudio.droide.core

import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Explicit integration only; normal startup does not enroll or resume this protocol
// automatically.
internal class InitialProjectRegistration(
    appFilesDir: File,
    private val enrollment: CanonicalFileOperationJournal.Enrollment,
    private val maxBytes: Long,
    private val checkpoint: (String) -> Unit = {},
) {
    data class Prepared(val operationId: String, val directory: ProjectDirectoryRegistration.Inspection)

    // Explicit admission for an already registered catalog; never provisions or replays recovery.
    class Startup private constructor(appFilesDir: File, slot: File, private val membership: File, private val maxBytes: Long,
                                      private val admission: ProjectCatalogAdmission.Selection?) {
        constructor(appFilesDir: File, slot: File, membership: File, maxBytes: Long) :
            this(appFilesDir, slot, membership, maxBytes, null)

        internal constructor(admission: ProjectCatalogAdmission.Selection) :
            this(admission.appFilesDir, admission.slot, admission.membership, admission.maxBytes, admission) {
            require(admission.route == ProjectCatalogAdmission.Route.REGISTERED_CATALOG)
        }

        val appFilesDir: File = appFilesDir.canonicalFile
        private val metadata = File(this.appFilesDir, ".droide")
        private val bootstrap = CanonicalJournalBootstrap(slot, maxBytes)

        suspend fun requireReady(): CanonicalJournalBootstrap.Inspection = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            admission?.requireCurrent(context)
            val inspected = bootstrap.inspectWithMembership(metadata, AUTHORITY_ID, membership, context)
            check(inspected.history.pending == null) {
                "Initial project registration is pending (${inspected.history.pending?.phase}); " +
                    "recover it before opening the catalog. Project files and editor recovery were preserved."
            }
            val completed = checkNotNull(inspected.history.completed.singleOrNull()) {
                "Initial project registration has no unique completion receipt; existing data was not reinitialized"
            }
            check(completed.phase == CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED) {
                "Initial project registration is incomplete; existing data requires review"
            }
            InitialProjectRegistration(appFilesDir, inspected.enrollment, maxBytes).requireInitialIntent(completed)
            admission?.requireCurrent(context)
            context.ensureActive()
            inspected
        }
    }

    private val app = appFilesDir.canonicalFile
    private val metadata = File(app, ".droide")
    private val projects = ProjectWorkspacePolicy.projectsRoot(app)
    private val projectRoot = ProjectWorkspacePolicy.defaultRoot(projects, DEFAULT_ID)
    private val project = DroideProject(DEFAULT_ID, "Default", projectRoot.path)
    private val store = FileProjectStore(app)
    private val authority = File(enrollment.authorityPath)
    private val owner = requireNotNull(authority.parentFile)
    private val directorySlot = File(owner, "initial-directory")
    private val directory = ProjectDirectoryRegistration(directorySlot) { checkpoint("directory/$it") }
    private val retention = CanonicalJournalRetention.Policy(1, Math.multiplyExact(maxBytes, 3L))

    init {
        require(maxBytes > 0 && enrollment.projectId == AUTHORITY_ID && enrollment.rootPath == metadata.path) {
            "Initial registration requires its explicitly enrolled catalog authority"
        }
        require(!directorySlot.toPath().startsWith(projects.toPath())) { "Registration evidence must remain outside project storage" }
    }

    suspend fun prepare(): Prepared = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val projectsIdentity = WorkspaceDirectoryIdentity(projects)
        val initial = store.loadCatalog()
        check(initial.projects.isEmpty() && initial.revision == DiskFileVersion.fromText(null)) {
            "Initial registration requires an absent catalog; existing registrations were preserved"
        }
        requireProjectEntries(emptySet())
        val journal = journal()
        check(journal.read() == null && journal.readCompletedMetadataForRecovery().isEmpty()) {
            "Initial registration has previous work; inspect or resume it instead of preparing again"
        }
        AnchoredWorkspaceFiles(owner).use { files ->
            check(files.directoryKey() == enrollment.authorityParentKey &&
                files.kind(directorySlot.name) == AnchoredWorkspaceFiles.Kind.ABSENT &&
                files.kind(PROPOSAL) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                "Initial registration evidence already exists or has changed"
            }
        }
        val text = store.encodeCatalog(listOf(project))
        val bytes = text.toByteArray(Charsets.UTF_8)
        val content = CanonicalFileOperationJournal.Content(bytes.size.toLong(), hash(bytes), 384)
        check(content.size <= maxBytes) { "Initial catalog exceeds the enrolled publication budget" }
        context.ensureActive()
        appIdentity.requireCurrent()
        projectsIdentity.requireCurrent()
        journal.enableCompletionRetention(retention, context)
        val intent = journal.admitCreation(CATALOG, content, context)
        AnchoredWorkspaceFiles(owner).use { files ->
            check(files.directoryKey() == enrollment.authorityParentKey) { "Catalog authority parent changed" }
            val expected = files.createNew(PROPOSAL).use { output ->
                output.write(bytes)
                checkpoint("proposal-before-force")
                context.ensureActive()
                output.force(384)
                CanonicalFileSnapshot(maxBytes).inspect(files, PROPOSAL, context).also { observed ->
                    check(observed.identity == output.observe().attributes.fileKey().toString() &&
                        observed.size == content.size && observed.sha256 == content.sha256 && observed.mode == content.mode) {
                        "Initial catalog proposal changed while it was being prepared"
                    }
                }
            }
            checkpoint("proposal-forced")
            journal.captureProposal(intent.id, files, PROPOSAL, expected, context)
        }
        journal.stagePublication(intent.id, context)
        appIdentity.requireCurrent()
        projectsIdentity.requireCurrent()
        requireProjectEntries(emptySet())
        val owned = directory.prepareBound(projectRoot, DEFAULT_ID, binding(intent), context)
        checkpoint("prepared")
        appIdentity.requireCurrent()
        projectsIdentity.requireCurrent()
        journal.verifyPublicationStage(intent.id, context)
        requireBoundDirectory(intent, context, ProjectDirectoryRegistration.State.PREPARED)
        Prepared(intent.id, owned)
    }

    suspend fun resume(): ProjectCatalogSnapshot = serialized { context ->
        val appIdentity = WorkspaceDirectoryIdentity(app)
        val journal = journal()
        val completed = journal.readCompletedMetadataForRecovery()
        check(completed.size <= 1) { "Initial registration has ambiguous catalog history" }
        if (completed.isNotEmpty()) {
            check(journal.read() == null) { "Initial catalog authority has unrelated pending work" }
            val intent = completed.single()
            requireInitialIntent(intent)
            check(intent.phase == CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED &&
                journal.reviewCompletedCustody(intent.id, context).all { it.matches }) {
                "Initial catalog history is incomplete or its retained proposal changed"
            }
            requireBoundDirectory(intent, context, ProjectDirectoryRegistration.State.PUBLISHED)
            return@serialized readRegisteredCatalog(intent, context, appIdentity)
        }

        val intent = checkNotNull(journal.read()) { "Initial catalog intent is missing; registration was not recreated" }
        requireInitialIntent(intent)
        check(intent.phase in RESUMABLE_PHASES) { "Initial catalog preparation is incomplete; retained work needs review" }
        val owned = requireBoundDirectory(intent, context)
        when (owned.state) {
            ProjectDirectoryRegistration.State.PREPARED -> {
                check(intent.phase == CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED) {
                    "Catalog publication started before its project directory was published"
                }
                requireProjectEntries(emptySet())
                journal.verifyPublicationStage(intent.id, context)
                AnchoredWorkspaceFiles(metadata).use { files ->
                    check(files.kind(CATALOG) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                        "Initial catalog destination changed; staged project and catalog were preserved"
                    }
                }
                directory.publish(projectRoot, DEFAULT_ID, context)
            }
            ProjectDirectoryRegistration.State.PUBLISHED_NEEDS_RECOVERY ->
                directory.recoverPublished(projectRoot, DEFAULT_ID, context)
            ProjectDirectoryRegistration.State.PUBLICATION_PENDING ->
                error("Project directory publication is unresolved; no directory movement was replayed")
            ProjectDirectoryRegistration.State.PUBLISHED -> Unit
        }
        checkpoint("directory-ready")
        fun requireOwnedDirectory() {
            appIdentity.requireCurrent()
            requireBoundDirectory(intent, context, ProjectDirectoryRegistration.State.PUBLISHED)
            requireProjectEntries(setOf(DEFAULT_ID))
        }
        requireOwnedDirectory()
        val publisher = journal(::requireOwnedDirectory)
        when (intent.phase) {
            CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED -> publisher.publishStaged(intent.id, context)
            CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED -> publisher.recoverPublishedState(intent.id, context)
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED -> {
                val outcome = checkNotNull(intent.publication) { "Initial catalog native outcome is missing" }
                if (outcome.published && !outcome.durable) publisher.recoverPublishedState(intent.id, context)
                else publisher.verifyPublication(intent.id, context)
            }
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED -> publisher.verifyPublication(intent.id, context)
            else -> error("Initial catalog phase cannot be resumed")
        }
        requireOwnedDirectory()
        publisher.recordPublicationCompletion(intent.id, context)
        requireOwnedDirectory()
        publisher.archiveCompleted(intent.id, context)
        checkpoint("registered")
        readRegisteredCatalog(intent, context, appIdentity)
    }

    private suspend fun requireInitialIntent(intent: CanonicalFileOperationJournal.Intent) {
        val expected = DiskFileVersion.fromText(store.encodeCatalog(listOf(project)))
        check(intent.kind == CanonicalFileOperationJournal.OperationKind.CREATE && intent.before == null &&
            intent.path == CATALOG && intent.proposal.size == expected.bytes && intent.proposal.sha256 == expected.sha256 &&
            intent.proposal.mode == 384) { "Catalog intent belongs to a different registration; no state was adopted" }
    }

    private suspend fun readRegisteredCatalog(intent: CanonicalFileOperationJournal.Intent, context: CoroutineContext,
                                              appIdentity: WorkspaceDirectoryIdentity): ProjectCatalogSnapshot {
        val snapshot = store.loadCatalog()
        val registered = snapshot.projects.singleOrNull { it.id == DEFAULT_ID }
        check(registered != null && !registered.workspaceRemoved && registered.rootPath == projectRoot.path) {
            "Registered Default project no longer matches its directory receipt"
        }
        context.ensureActive()
        appIdentity.requireCurrent()
        requireBoundDirectory(intent, context, ProjectDirectoryRegistration.State.PUBLISHED)
        return snapshot
    }

    private fun requireBoundDirectory(intent: CanonicalFileOperationJournal.Intent, context: CoroutineContext,
                                      state: ProjectDirectoryRegistration.State? = null): ProjectDirectoryRegistration.Inspection =
        directory.inspect(projectRoot, DEFAULT_ID, context).also { observed ->
            check(observed.bindingId == binding(intent) && observed.rootPath == projectRoot.path &&
                (state == null || observed.state == state)) {
                "Project directory does not belong to this catalog transaction; all existing evidence was preserved"
            }
        }

    private fun requireProjectEntries(expected: Set<String>) = AnchoredWorkspaceFiles(projects).use { files ->
        val actual = mutableSetOf<String>()
        files.forEachChild { actual += it }
        check(actual == expected) { "Project storage changed during initial registration; unregistered folders were preserved" }
    }

    private fun binding(intent: CanonicalFileOperationJournal.Intent): String = hash(
        "initial-project-v1:${CanonicalFileOperationJournal.enrollmentHash(enrollment)}:${intent.id}:${intent.proposal.sha256}"
            .toByteArray(Charsets.UTF_8))

    private fun journal(guard: (() -> Unit)? = null) =
        CanonicalFileOperationJournal(metadata, authority, enrollment, maxBytes, checkpoint = { point ->
            checkpoint("catalog/$point")
            guard?.invoke()
        })

    private suspend fun <T> serialized(action: suspend (CoroutineContext) -> T): T = withContext(Dispatchers.IO) {
        registrationLock(app.path).withLock {
            currentCoroutineContext().ensureActive()
            action(currentCoroutineContext())
        }
    }

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val AUTHORITY_ID = "initial-project-catalog"
        private const val DEFAULT_ID = "default"
        private const val CATALOG = "projects.json"
        private const val PROPOSAL = "initial-catalog-source"
        private val locks = mutableMapOf<String, WeakReference<Mutex>>()
        private val RESUMABLE_PHASES = setOf(CanonicalFileOperationJournal.Phase.PUBLICATION_STAGED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_PREPARED, CanonicalFileOperationJournal.Phase.PUBLICATION_RECORDED,
            CanonicalFileOperationJournal.Phase.PUBLICATION_RECOVERED, CanonicalFileOperationJournal.Phase.PUBLICATION_COMPLETED)

        private fun registrationLock(path: String): Mutex = synchronized(locks) {
            locks.entries.removeAll { it.value.get() == null }
            locks[path]?.get() ?: Mutex().also { locks[path] = WeakReference(it) }
        }
    }
}
