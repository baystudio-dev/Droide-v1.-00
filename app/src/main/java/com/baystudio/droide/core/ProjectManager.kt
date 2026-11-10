package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.CopyOption
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull

interface ProjectRecentsStore {
    // Null means this installation has never persisted recent-project presentation state.
    suspend fun load(): List<String>?
    suspend fun save(ids: List<String>)
}

class FileProjectRecentsStore internal constructor(
    private val dir: File,
    private val replace: (Path, Path, Array<CopyOption>) -> Unit,
) : ProjectRecentsStore {
    constructor(dir: File) : this(dir, ProjectMetadataPublication::replace)
    private val f = File(dir, ".droide/recent_projects.json")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override suspend fun load(): List<String>? = withContext(Dispatchers.IO) {
        if (!f.exists()) return@withContext null
        if (!f.isFile || f.length() > 256_000) return@withContext emptyList()
        runCatching {
            val arr = json.parseToJsonElement(f.readText()) as? kotlinx.serialization.json.JsonArray
                ?: return@runCatching emptyList()
            arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                .mapNotNull { runCatching { PathSecurity.safeLeafName(it) }.getOrNull() }
                .distinct()
                .take(128)
        }.getOrDefault(emptyList())
    }

    override suspend fun save(ids: List<String>) = withContext(Dispatchers.IO) {
        val safe = ids.asSequence()
            .mapNotNull { runCatching { PathSecurity.safeLeafName(it) }.getOrNull() }
            .distinct()
            .take(128)
            .map { kotlinx.serialization.json.JsonPrimitive(it) }
            .toList()
        ProjectMetadataPublication.write(f, kotlinx.serialization.json.JsonArray(safe).toString(), replace)
    }
}

class ProjectSelectionUncertainException internal constructor(cause: Throwable) : IllegalStateException(
    "Project selection could not be verified. Project files and editor recovery were kept; " +
        "restart before switching or deleting a workspace.", cause,
)

class ProjectCatalogUncertainException private constructor(cause: Throwable, message: String) : IllegalStateException(message, cause) {
    internal constructor(cause: Throwable) : this(cause,
        cause.takeIf { it is UndeclaredCatalogUpdateException }?.message
            ?: "Project catalog changes could not be verified. Project files were preserved; " +
            "restart and review the saved projects before creating, switching or deleting a workspace.")

    internal constructor(cause: Throwable, original: File, recovery: File) : this(cause,
        "Project deletion could not be confirmed. Original workspace: ${original.path}. " +
            "Last recovery location: ${recovery.path}. Inspect both locations before retrying.")
}

// The recovery path is a location hint, not proof of the current directory's ownership or
// contents.
data class WorkspaceRemoval(val projectId: String, val recoveryPath: String?)

class ProjectManager private constructor(
    private val appFilesDir: File,
    store: ProjectStore,
    private val recentsStore: ProjectRecentsStore,
    private val catalogStartup: InitialProjectRegistration.Startup?,
    private val existingCatalogAdmission: ProjectCatalogAdmission.Selection?,
    defaultStartup: DefaultProjectStartup? = null,
    private val generatedWorkspace: GeneratedProjectWorkspace = GeneratedProjectWorkspace(),
    private val applicationRouteResolver: (suspend () -> ResolvedProjectCatalog)? = null,
) {
    @Volatile private var resolvedCatalog: ResolvedProjectCatalog? =
        if (applicationRouteResolver == null) ResolvedProjectCatalog(store, defaultStartup) else null
    private val store: ProjectStore get() = checkNotNull(resolvedCatalog) { "Project catalog startup has not resolved" }.store
    private val defaultStartup: DefaultProjectStartup? get() = checkNotNull(resolvedCatalog) { "Project catalog startup has not resolved" }.defaultStartup

    private suspend fun resolveCatalogForStartup() {
        if (resolvedCatalog != null) return
        val selected = checkNotNull(applicationRouteResolver).invoke()
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        resolvedCatalog = selected // One immutable route; retries never replace the owner.
    }

    constructor(appFilesDir: File, store: ProjectStore = FileProjectStore(appFilesDir),
                recentsStore: ProjectRecentsStore = FileProjectRecentsStore(appFilesDir)) :
        this(appFilesDir, store, recentsStore, null, null)

    internal constructor(appFilesDir: File, store: ProjectStore, recentsStore: ProjectRecentsStore,
                         generatedWorkspace: GeneratedProjectWorkspace) :
        this(appFilesDir, store, recentsStore, null, null, generatedWorkspace = generatedWorkspace)

    internal constructor(startup: InitialProjectRegistration.Startup,
                         recentsStore: ProjectRecentsStore = FileProjectRecentsStore(startup.appFilesDir)) :
        this(startup.appFilesDir, FileProjectStore(startup.appFilesDir), recentsStore, startup, null)

    internal constructor(selection: ProjectCatalogAdmission.Selection,
                         recentsStore: ProjectRecentsStore = FileProjectRecentsStore(selection.appFilesDir)) :
        this(selection.appFilesDir, FileProjectStore(selection.appFilesDir), recentsStore,
            selection.takeIf { it.route == ProjectCatalogAdmission.Route.REGISTERED_CATALOG }?.registeredStartup(),
            selection.takeIf { it.route == ProjectCatalogAdmission.Route.EXISTING_CATALOG })

    private val hasSelectedCatalogStartup: Boolean
        get() = catalogStartup != null || existingCatalogAdmission != null || store is JournaledProjectStore

    private val _projects = MutableStateFlow<List<DroideProject>>(emptyList())
    // Stored records remain here to retain project identity and root ownership.
    val projects: StateFlow<List<DroideProject>> = _projects
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId
    // Registration and active-project changes share one commit authority. Clone and template work
    // happens outside it; only read-modify-save-publish is serialized.
    private val relocation = ProjectWorkspaceRelocation(appFilesDir)
    private val registryMutex = relocation
    private val versionedStore: VersionedProjectStore? get() = store as? VersionedProjectStore
    // Owned by this manager under registryMutex, never a store-wide mutable "last read" token.
    private var catalogRevision: DiskFileVersion? = null
    private val recentMutex = Mutex()
    @Volatile private var selectionUncertainty: ProjectSelectionUncertainException? = null
    @Volatile private var catalogUncertainty: ProjectCatalogUncertainException? = null
    internal val selectionIsUncertain: Boolean get() = selectionUncertainty != null || catalogUncertainty != null
    private val _recentIds = MutableStateFlow<List<String>>(emptyList())
    private val _recentProjects = MutableStateFlow<List<DroideProject>>(emptyList())
    val recentProjects: StateFlow<List<DroideProject>> = _recentProjects
    private val _otherProjects = MutableStateFlow<List<DroideProject>>(emptyList())
    val otherProjects: StateFlow<List<DroideProject>> = _otherProjects
    private val _storedProjects = MutableStateFlow<List<DroideProject>>(emptyList())
    val storedProjects: StateFlow<List<DroideProject>> = _storedProjects

    @Volatile
    private var githubCredentialResolver: (suspend () -> GitHubTransportCredential?)? = null

    internal fun bindGitHubCredentialResolver(resolver: suspend () -> GitHubTransportCredential?) {
        githubCredentialResolver = resolver
    }

    private fun defaultProject(): DroideProject =
        DroideProject("default", "Default", File(appFilesDir, "projects/default").absolutePath)

    private var startupCleanup: List<File> = emptyList()

    suspend fun init(readOnlyExisting: Boolean = false) = withContext(Dispatchers.IO) { registryMutex.withLock(readOnly = readOnlyExisting) {
        requireSelectionCertain()
        resolveCatalogForStartup()
        relocation.recover(store, readOnlyExisting)
        if (!readOnlyExisting) defaultStartup?.prepare()
        val startup = requireCatalogStartupReady()
        if (readOnlyExisting) {
            initializeExistingLocked(startup)
            return@withLock
        }
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
        val snapshot = loadStartupCatalog()
        var nextRevision = snapshot?.revision
        val loaded = (snapshot?.projects ?: store.load()).map { project ->
            val safeId = PathSecurity.safeLeafName(project.id)
            val root = if (project.workspaceRemoved)
                ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(project.rootPath))
                else ProjectWorkspacePolicy.restoreRoot(projectsRoot, safeId, project.rootPath)
            project.copy(id = safeId, rootPath = root.canonicalPath)
        }.toMutableList()
        val historical = loaded.filterNot { it.workspaceRemoved }
        val def = defaultProject()
        if (loaded.none { it.id == def.id }) loaded.add(0, def)
        // Validate ownership and every historical root before recovery, creation, or publication.
        ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, loaded)
        historical.forEach { project ->
            val root = File(project.rootPath)
            val available = workspaceDirectory(root)
            check(available == true || available == null &&
                workspaceDirectory(File(projectsRoot, ".delete-${project.id}")) == true) {
                "Saved workspace is unavailable: ${root.path}. Restore the original folder before reopening; catalog and recovery were preserved."
            }
        }
        startupCleanup = ProjectWorkspaceMaintenance.prepare(projectsRoot, historical.associate { it.id to File(it.rootPath) })
        historical.forEach { project ->
            check(workspaceDirectory(File(project.rootPath)) == true) {
                "Saved workspace is unavailable: ${project.rootPath}. Recovery did not restore the original folder; catalog was preserved."
            }
        }
        if (historical.none { it.id == def.id }) {
            val root = File(def.rootPath)
            AnchoredWorkspaceFiles(projectsRoot).use { storage ->
                val created = storage.createDirectory(root.name)
                check(created.durable) {
                    "Default workspace creation could not be confirmed: ${root.path} " +
                        "(created=${created.published}, errno=${created.error}). " +
                        "Existing folders were not adopted; project data was preserved for review."
                }
                storage.directoryKey(root.name)
            }
        }
        try {
            val versioned = versionedStore
            if (versioned != null) {
                nextRevision = versioned.saveCatalog(loaded, checkNotNull(nextRevision))
            } else store.save(loaded)
        } catch (conflict: ProjectCatalogConflictException) {
            rejectCatalogConflict(conflict)
        } catch (failure: Throwable) {
            rejectUnconfirmedCatalog(failure)
            throw failure
        }
        val wanted = store.loadActiveId()
        val chosen = loaded.firstOrNull { it.id == wanted && !it.workspaceRemoved }?.id ?: def.id
        if (wanted != chosen) {
            try { store.saveActiveId(chosen) } catch (failure: Throwable) {
                rejectUnconfirmedSelection(failure)
                throw failure
            }
        }
        val registeredIds = loaded.filterNot { it.workspaceRemoved }.map { it.id }
        val persistedRecent = recentsStore.load()
        val recentIds = ProjectHistoryPolicy.initial(
            persisted = persistedRecent,
            registeredIds = registeredIds,
            activeId = chosen,
        )
        if (persistedRecent != recentIds) recentsStore.save(recentIds)
        requireCatalogStartupReady(startup)
        requireStartupCatalogRevision(nextRevision)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // Validate its current receipts and catalog under the held lease; never repair a newly
        // discovered operation and publish the older loaded list.
        relocation.activate()
        relocation.recover(store, readOnly = true)
        relocation.requireRegistry(store, loaded)
        requireCatalogStartupReady(startup)
        requireStartupCatalogRevision(nextRevision)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        relocation.requireHeld()
        catalogRevision = nextRevision
        _projects.value = loaded
        _activeId.value = chosen
        _recentIds.value = recentIds
        refreshProjectPresentation()
    } }

    private fun workspaceDirectory(file: File): Boolean? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory
    } catch (_: NoSuchFileException) { null }

    companion object {
        internal fun forApplication(appFilesDir: File): ProjectManager = forApplication(appFilesDir, {})

        internal fun forApplication(appFilesDir: File, checkpoint: (String) -> Unit): ProjectManager {
            val mode = DefaultCatalogModeGuard(appFilesDir)
            val legacy = ResolvedProjectCatalog(
                FileProjectStore(appFilesDir, StreamPublicationDurability.platform,
                    ProjectMetadataPublication::replace, mode::requireDefaultMode),
                DefaultProjectStartup(appFilesDir, mode))
            return ProjectManager(appFilesDir, legacy.store, FileProjectRecentsStore(appFilesDir), null, null,
                legacy.defaultStartup, applicationRouteResolver = {
                    val selected = ProjectCatalogAdmission.restoreSavedUpdate(appFilesDir, checkpoint)
                    val policy = selected?.persistedRetentionPolicy()
                    val route = if (selected == null) {
                        mode.requireDefaultMode()
                        legacy
                    } else {
                        checkpoint("application-catalog-policy-read")
                        selected.requireCurrentBinding(kotlinx.coroutines.currentCoroutineContext())
                        ResolvedProjectCatalog(JournaledProjectStore(selected, checkNotNull(policy)), null)
                    }
                    checkpoint("application-catalog-before-resolution")
                    if (selected != null) {
                        selected.requireCurrentBinding(kotlinx.coroutines.currentCoroutineContext())
                        check(selected.persistedRetentionPolicy() == policy) { "Saved catalog retention policy changed during startup resolution" }
                    } else mode.requireDefaultMode()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    route
                })
        }
    }

    // Temporary built-in startup opens only an existing selected project. It must not repair/move
    // trees or persist catalog, active-project or recent state.
    private suspend fun initializeExistingLocked(startup: CanonicalJournalBootstrap.Inspection?) {
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
        fun requireExisting(project: DroideProject) {
            require(!project.workspaceRemoved) { "Restore the stored project before opening it" }
            PathSecurity.safeLeafName(project.id)
            val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(project.rootPath))
            check(root.isDirectory) { "Saved active project is unavailable; existing project data was preserved" }
        }
        active()?.let { current ->
            ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, _projects.value)
            requireExisting(current)
            requireCatalogStartupReady(startup)
            requireStartupCatalogRevision(catalogRevision)
            relocation.requireHeld()
            startupCleanup = emptyList()
            return
        }
        val snapshot = loadStartupCatalog()
        val loaded = snapshot?.projects ?: store.load()
        ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, loaded)
        val wanted = store.loadActiveId()
            ?: error("No saved active project; existing project data was preserved")
        val selected = loaded.firstOrNull { it.id == wanted }
            ?: error("Saved active project is not registered; existing project data was preserved")
        requireExisting(selected)
        val recent = ProjectHistoryPolicy.initial(recentsStore.load(), loaded.filterNot { it.workspaceRemoved }.map { it.id }, wanted)
        requireCatalogStartupReady(startup)
        requireStartupCatalogRevision(snapshot?.revision)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        relocation.requireHeld()
        startupCleanup = emptyList()
        catalogRevision = snapshot?.revision
        _projects.value = loaded
        _activeId.value = wanted
        _recentIds.value = recent
        refreshProjectPresentation()
    }

    private suspend fun loadStartupCatalog(): ProjectCatalogSnapshot? = try {
        versionedStore?.loadCatalog().also { snapshot ->
            if (hasSelectedCatalogStartup) {
                val current = checkNotNull(snapshot) { "Selected startup requires a versioned project catalog" }
                check(current.revision.existed) {
                    "Selected project catalog is missing; existing registration and project data were preserved"
                }
                val expected = defaultProject()
                val registered = current.projects.singleOrNull { it.id == expected.id }
                check(registered != null && !registered.workspaceRemoved && registered.rootPath == expected.rootPath) {
                    "Registered Default workspace is missing or redirected; review the current catalog before reopening"
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (hasSelectedCatalogStartup) throw uncertainCatalog(failure)
        throw failure
    }

    private suspend fun requireStartupCatalogRevision(expected: DiskFileVersion?) {
        if (!hasSelectedCatalogStartup) return
        if (loadStartupCatalog()?.revision != expected) throw uncertainCatalog(ProjectCatalogConflictException())
    }

    private suspend fun requireCatalogStartupReady(expected: CanonicalJournalBootstrap.Inspection? = null):
        CanonicalJournalBootstrap.Inspection? {
        return try {
            defaultStartup?.requireDefaultMode()
            existingCatalogAdmission?.requireCurrent(kotlinx.coroutines.currentCoroutineContext())
            catalogStartup?.requireReady()?.also { current ->
                check(expected == null || current == expected) { "Catalog registration changed during startup; no new session was published" }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw uncertainCatalog(failure)
        }
    }

    // Caller holds registryMutex; metadata checks must not block the caller's UI dispatcher.
    private suspend fun requireCatalogSessionReady() {
        relocation.requireHeld()
        requireSelectionCertain()
        withContext(Dispatchers.IO) {
            relocation.recover(store)
            relocation.requireRegistry(store, _projects.value)
        }
        if (!hasSelectedCatalogStartup && defaultStartup == null) return
        withContext(Dispatchers.IO) {
            requireSelectionCertain()
            defaultStartup?.requireDefaultMode()
            if (!hasSelectedCatalogStartup) return@withContext
            requireCatalogStartupReady()
            requireStartupCatalogRevision(catalogRevision)
        }
    }

    internal suspend fun requireCatalogReadyForTransition() = registryMutex.withLock {
        requireCatalogSessionReady()
    }

    // Read-only review remains available after uncertainty; it never clears the mutation guard.
    internal val supportsCatalogRecoveryReview: Boolean get() = resolvedCatalog?.store is JournaledProjectStore

    internal suspend fun inspectCatalogRecovery(): CanonicalFileOperationJournal.RecoveryMetadata = registryMutex.withLock(readOnly = true) {
        journaledStoreForReview().inspectRecovery()
    }

    internal suspend fun inspectCatalogOperationRecovery(operationId: String): CanonicalFileOperationJournal.OperationRecoveryReview =
        registryMutex.withLock(readOnly = true) { journaledStoreForReview().inspectOperationRecovery(operationId) }

    internal suspend fun readCatalogRetainedContent(operationId: String, role: CanonicalFileOperationJournal.CustodyRole,
                                                  expected: CanonicalFileSnapshot.Version): CanonicalFileOperationJournal.RetainedContent =
        registryMutex.withLock(readOnly = true) { journaledStoreForReview().readRetainedContent(operationId, role, expected) }

    private fun journaledStoreForReview(): JournaledProjectStore = checkNotNull(store as? JournaledProjectStore) {
        "Catalog recovery review requires an explicitly selected journaled store; existing catalog data was not migrated"
    }

    internal suspend fun collectStartupGarbage(): List<String> {
        requireSelectionCertain()
        val prepared = startupCleanup
        startupCleanup = emptyList()
        return ProjectWorkspaceMaintenance.collect(ProjectWorkspacePolicy.projectsRoot(appFilesDir), prepared)
    }

    suspend fun add(name: String, rootPath: String? = null, treeUri: String? = null): DroideProject = withContext(Dispatchers.IO) { registryMutex.withLock {
        requireCatalogSessionReady()
        val id = "p-${UUID.randomUUID()}"
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val requested = rootPath?.let(::File) ?: ProjectWorkspacePolicy.defaultRoot(projectsRoot, id)
        val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, requested)
        require(_projects.value.none { existing -> runCatching { File(existing.rootPath).canonicalFile == root }.getOrDefault(false) }) {
            "Project workspace is already registered"
        }
        if (rootPath == null) {
            // Publish the catalog only after no-replace creation and parent-directory sync.
            generatedWorkspace.create(projectsRoot, root)
        } else {

            check(root.isDirectory || root.mkdirs()) { "Cannot create project workspace" }
        }
        val p = DroideProject(
            id = id,
            name = name.trim().ifBlank { "Project" }.take(120),
            rootPath = root.canonicalPath,
            treeUri = treeUri,
        )
        val updated = _projects.value + p
        val interrupted = persistCatalog(updated)
        _projects.value = updated
        refreshProjectPresentation()
        rethrowCommittedInterruption(interrupted)
        p
    } }

    suspend fun createFromTemplate(name: String, templateId: String): Pair<DroideProject, String?> {
        val template = ProjectTemplateRegistry.byId(templateId) ?: error("Unknown project template: $templateId")
        val project = add(name.ifBlank { template.name })

        withContext(Dispatchers.IO) { ProjectTemplateWriter.write(File(project.rootPath), template) }
        return project to template.primaryFile
    }

    // Authentication is transport-only and never persisted here.

    suspend fun cloneFromGit(
        name: String,
        url: String,
        username: String? = null,
        token: String? = null,
    ): DroideProject = withContext(Dispatchers.IO) {
        registryMutex.withLock { requireCatalogSessionReady() }
        val safeUrl = url.trim()
        require(GitRemoteSecurity.isAllowed(safeUrl)) {
            "Only HTTPS or SSH Git remotes without embedded HTTPS credentials are allowed"
        }
        val id = "p-${UUID.randomUUID()}"
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        val root = ProjectWorkspacePolicy.defaultRoot(projectsRoot, id)
        require(!root.exists()) { "Clone workspace collision" }
        check(root.mkdirs()) { "Cannot create clone workspace" }
        val result = GitManager(root, githubCredentialResolver = githubCredentialResolver)
            .clone(safeUrl, username = username, token = token)
        require(result == "Clone completed") { result }
        val project = DroideProject(
            id = id,
            name = name.trim().ifBlank { GitClonePolicy.suggestProjectName(safeUrl) }.take(120),
            rootPath = root.canonicalPath,
        )
        registryMutex.withLock {
            requireSelectionCertain()

            require(_projects.value.none { it.id == project.id || File(it.rootPath).canonicalFile == root.canonicalFile }) {
                "Clone workspace is already registered"
            }
            val updated = _projects.value + project
            val interrupted = persistCatalog(updated)
            // Publish the verified catalog before propagating committed cancellation.
            _projects.value = updated
            refreshProjectPresentation()
            rethrowCommittedInterruption(interrupted)
        }
        project
    }

    // Recents are presentation, not part of the selected-project ownership commit.
    suspend fun switch(id: String) {
        selectAndPublish(id)
        recordSelectedProject(id)
    }

    /** Caller owns its runtime transition and dispatcher. Validation and publication never suspend.
     * publish must only assign the accepted owner; service activation/cleanup happens afterwards. */
    internal suspend fun selectAndPublish(
        id: String,
        validate: () -> Unit = {},
        publish: () -> Unit = {},
    ): Throwable? {
        var warning: Throwable? = null
        registryMutex.withLock {
            requireCatalogSessionReady()
            val expectedProjects = _projects.value
            val expectedRevision = catalogRevision
            val currentCatalog = withContext(Dispatchers.IO) {
                versionedStore?.let { versioned ->
                    val current = versioned.loadCatalog()
                    current.projects == expectedProjects && current.revision == expectedRevision
                } ?: (store.load() == expectedProjects)
            }
            check(currentCatalog) {
                "Project catalog changed in another session; reopen before switching projects"
            }
            val selected = requireNotNull(_projects.value.firstOrNull { it.id == id }) { "Project not found: $id" }
            require(!selected.workspaceRemoved) { "Restore the stored project before opening it" }
            val previous = checkNotNull(_activeId.value) { "Project selection is not initialized" }
            var interrupted: Throwable? = null
            try {
                store.saveActiveId(id)
            } catch (failure: Throwable) {
                rejectUnconfirmedSelection(failure)
                interrupted = failure
                // A dispatcher can discard a successful write's return. Resolve one tiny ID, retaining both
                // locks; never shield the ordinary write or runtime preparation.
                val selected = withContext(NonCancellable) {
                    try { store.loadActiveId() } catch (readFailure: Throwable) {
                        if (readFailure !== failure) failure.addSuppressed(readFailure)
                        throw unresolvedSelection(failure, readFailure)
                    }
                }
                when (selected) {
                    id -> Unit
                    previous -> throw failure
                    else -> throw unresolvedSelection(failure)
                }
            }
            // Fatal write failure is never a success-shaped commit, even if its ID reached disk.
            if (interrupted is Error) rejectSelection(previous, interrupted)
            try {
                check(_activeId.value == previous) { "Project selection changed during commit" }
                validate()
            } catch (rejected: Throwable) {
                if (interrupted != null && interrupted !== rejected) rejected.addSuppressed(interrupted)
                rejectSelection(previous, rejected)
            }
            try { relocation.requireHeld() } catch (failure: Throwable) {
                throw unresolvedSelection(failure)
            }

            _activeId.value = id
            publish()
            if (interrupted is CancellationException) throw interrupted
            warning = interrupted
        }
        return warning
    }

    private suspend fun rejectSelection(previous: String, rejected: Throwable): Nothing {
        try {
            withContext(NonCancellable) { restoreSelection(previous, rejected) }
        } catch (restoration: Throwable) {
            if (rejected is Error) {
                if (restoration !== rejected) rejected.addSuppressed(restoration)
                throw rejected
            }
            throw restoration
        }
        throw rejected
    }

    private suspend fun restoreSelection(previous: String, rejected: Throwable) {
        // This is only called before publishing the new ID/runtime.
        if (_activeId.value != previous) throw unresolvedSelection(rejected)
        try {
            store.saveActiveId(previous)
        } catch (restoreFailure: Throwable) {
            if (restoreFailure !== rejected) rejected.addSuppressed(restoreFailure)
            rejectUnconfirmedSelection(restoreFailure)
            val selected = try { store.loadActiveId() } catch (readFailure: Throwable) {
                if (readFailure !== rejected) rejected.addSuppressed(readFailure)
                throw unresolvedSelection(rejected, if (restoreFailure is Error) restoreFailure else readFailure)
            }
            if (selected != previous) throw unresolvedSelection(rejected, restoreFailure)
            if (restoreFailure is Error) throw restoreFailure
        }
    }

    private fun uncertainSelection(cause: Throwable): ProjectSelectionUncertainException =
        (selectionUncertainty ?: ProjectSelectionUncertainException(cause).also { selectionUncertainty = it })

    private fun unresolvedSelection(cause: Throwable, resolutionFailure: Throwable? = null): Throwable {
        val uncertainty = uncertainSelection(cause)
        return when {
            cause is Error -> cause
            resolutionFailure is Error -> resolutionFailure
            else -> uncertainty
        }
    }

    internal fun requireSelectionCertain() {
        selectionUncertainty?.let { throw it }
        catalogUncertainty?.let { throw it }
    }

    private fun rejectUnconfirmedSelection(failure: Throwable) {
        if (!ProjectMetadataPublication.isUnconfirmed(failure) && failure !is DefaultCatalogModeGuard.Blocked) return
        val blocked = unresolvedSelection(failure)
        if (failure is CancellationException) throw failure
        throw blocked
    }

    private fun rejectUnconfirmedCatalog(failure: Throwable) {
        if (!ProjectMetadataPublication.isUnconfirmed(failure) && failure !is DefaultCatalogModeGuard.Blocked) return
        val blocked = uncertainCatalog(failure)
        if (failure is CancellationException) throw failure
        throw blocked
    }

    /** registryMutex must be held. */

    private suspend fun persistCatalog(updated: List<DroideProject>): Throwable? {
        requireCatalogSessionReady()
        versionedStore?.let { return persistVersionedCatalog(it, updated) }
        try {
            store.save(updated)
            return null
        } catch (failure: Throwable) {
            rejectUnconfirmedCatalog(failure)
            val saved = try {
                withContext(NonCancellable) { withTimeout(5_000) { store.load() } }
            } catch (readFailure: Throwable) {
                if (readFailure !== failure) failure.addSuppressed(readFailure)
                throw uncertainCatalog(failure, readFailure)
            }
            try {
                val root = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
                // Do not let canonicalization bless a newly replaced root symlink or a conflicting external
                // catalog as evidence of our commit.
                ProjectWorkspacePolicy.requireDistinctRoots(root, saved)
                ProjectWorkspacePolicy.requireDistinctRoots(root, updated)
                ProjectWorkspacePolicy.requireDistinctRoots(root, _projects.value)
                fun canonical(records: List<DroideProject>) =
                    records.map { it.copy(rootPath = File(it.rootPath).canonicalPath) }
                val actual = canonical(saved)
                if (actual == canonical(updated)) return failure
                if (actual == canonical(_projects.value)) throw failure
            } catch (comparisonFailure: Throwable) {
                if (comparisonFailure === failure) throw failure
                failure.addSuppressed(comparisonFailure)
                throw uncertainCatalog(failure)
            }
            throw uncertainCatalog(failure)
        }
    }

    private fun rejectCatalogConflict(conflict: ProjectCatalogConflictException): Nothing {
        // A rejected prepublication check is not an ambiguous commit and must never be reconciled as
        // one.
        if (catalogUncertainty == null) catalogUncertainty = ProjectCatalogUncertainException(conflict)
        throw conflict
    }

    private suspend fun persistVersionedCatalog(
        versioned: VersionedProjectStore,
        updated: List<DroideProject>,
    ): Throwable? {
        val expected = checkNotNull(catalogRevision) { "Project catalog must be loaded before saving" }
        val proposed = versioned.catalogRevision(updated)
        try {
            catalogRevision = versioned.saveCatalog(updated, expected)
            return null
        } catch (conflict: ProjectCatalogConflictException) {
            rejectCatalogConflict(conflict)
        } catch (failure: Throwable) {
            rejectUnconfirmedCatalog(failure)
            val saved = try {
                withContext(NonCancellable) { withTimeout(5_000) { versioned.loadCatalog() } }
            } catch (readFailure: Throwable) {
                if (readFailure !== failure) failure.addSuppressed(readFailure)
                throw uncertainCatalog(failure, readFailure)
            }
            try {
                val root = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
                ProjectWorkspacePolicy.requireDistinctRoots(root, saved.projects)
                ProjectWorkspacePolicy.requireDistinctRoots(root, updated)
                ProjectWorkspacePolicy.requireDistinctRoots(root, _projects.value)
                // Exact serialized bytes, not parsed-list equality or an arbitrary current external digest.
                if (saved.revision == proposed) {
                    catalogRevision = proposed
                    return failure
                }
                if (saved.revision == expected) throw failure
            } catch (comparisonFailure: Throwable) {
                if (comparisonFailure === failure) throw failure
                failure.addSuppressed(comparisonFailure)
                throw uncertainCatalog(failure)
            }
            throw uncertainCatalog(failure)
        }
    }

    private fun uncertainCatalog(cause: Throwable, resolutionFailure: Throwable? = null): Throwable {
        val uncertainty = catalogUncertainty ?: ProjectCatalogUncertainException(cause).also {
            catalogUncertainty = it
        }
        return when {
            cause is Error -> cause
            resolutionFailure is Error -> resolutionFailure
            else -> uncertainty
        }
    }

    private fun rethrowCommittedInterruption(interrupted: Throwable?) {
        if (interrupted is CancellationException || interrupted is Error) throw interrupted
    }

    internal suspend fun recordSelectedProject(id: String) {
        if (id != "default") {
            runSuspendCatching { markRecent(id) }.onFailure { refreshProjectPresentation() }
        } else {
            refreshProjectPresentation()
        }
    }

    suspend fun removeFromRecent(id: String) {
        require(id != "default") { "Default project is pinned" }
        require(_activeId.value != id) { "Switch away from a project before removing it from Recent" }
        recentMutex.withLock {
            val next = ProjectHistoryPolicy.remove(_recentIds.value, id)
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    suspend fun restoreToRecent(id: String) {
        val project = requireNotNull(_projects.value.firstOrNull { it.id == id }) { "Project not found: $id" }
        require(!project.workspaceRemoved) { "Restore the stored project before adding it to Recent" }
        if (id != "default") markRecent(id)
    }

    suspend fun clearRecent() {
        recentMutex.withLock {
            val next = ProjectHistoryPolicy.clear(_activeId.value)
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    // Membership removal leaves files, working copies and project recovery at their original paths.
    suspend fun removeWorkspace(id: String): DroideProject? = withContext(Dispatchers.IO) {
        registryMutex.withLock { recentMutex.withLock recent@ {
            requireCatalogSessionReady()
            require(id != "default") { "Default project cannot be removed" }
            require(_activeId.value != id) { "Switch away from a project before removing its workspace" }
            check(store.loadActiveId() == _activeId.value) {
                "The active project changed in another session; reopen before removing a workspace"
            }
            val target = _projects.value.firstOrNull { it.id == id } ?: return@recent null
            if (target.workspaceRemoved) return@recent target
            val removed = target.copy(workspaceRemoved = true)
            val updatedProjects = _projects.value.map { if (it.id == id) removed else it }
            val updatedRecent = ProjectHistoryPolicy.remove(_recentIds.value, id)
            val interrupted = persistCatalog(updatedProjects)
            runCatching { recentsStore.save(updatedRecent) }
            _projects.value = updatedProjects
            _recentIds.value = updatedRecent
            refreshProjectPresentation()
            rethrowCommittedInterruption(interrupted)
            removed
        } }
    }

    suspend fun restoreWorkspace(id: String): DroideProject = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            requireCatalogSessionReady()
            val plan = ProjectMembershipRestore.plan(appFilesDir, _projects.value, id)
            if (!plan.target.workspaceRemoved) return@withLock plan.target
            val restored = plan.restored
            val updated = plan.projects
            val interrupted = persistCatalog(updated)
            _projects.value = updated
            refreshProjectPresentation()
            rethrowCommittedInterruption(interrupted)
            restored
        }
    }

    /** Opt-in catalog-membership result only; never selection, opening, or human delivery. */
    internal suspend fun restoreMembershipCommand(id: String): CatalogMembershipCommands.Receipt = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            requireCatalogSessionReady()
            val selected = store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required")
            ProjectMembershipRestore.plan(appFilesDir, _projects.value, id)
            val expected = checkNotNull(catalogRevision)
            var returnedReceipt = false
            try {
                val receipt = selected.restoreMembership(id, expected)
                returnedReceipt = true
                val current = selected.loadCatalog()
                check(current.revision == DiskFileVersion.fromText(receipt.request.proposal)) {
                    "Membership outcome is historical; reopen before adopting current state"
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                catalogRevision = current.revision
                _projects.value = current.projects
                refreshProjectPresentation()
                receipt
            } catch (failure: Throwable) {
                if (returnedReceipt) {
                    val uncertain = uncertainCatalog(failure)
                    if (failure is CancellationException) throw failure
                    throw uncertain
                }
                if (failure is ProjectCatalogConflictException) rejectCatalogConflict(failure)
                rejectUnconfirmedCatalog(failure)
                throw failure
            }
        }
    }

    internal suspend fun discoverMembershipCommands(): List<CatalogMembershipCommands.Receipt> =
        (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required")).inspectMembershipCommands()

    /** Explicit metadata expiry, not implied by acceptance and never a filesystem cleanup. */
    internal suspend fun expireAcceptedNoChange(token: String, exactOutcomeIdentity: String): CatalogMembershipCommands.Expiry =
        registryMutex.withLock {
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .expireAcceptedNoChange(token, exactOutcomeIdentity)
        }

    internal suspend fun continueMembershipPreparation(token: String, operationId: String, envelopeHash: String): ProjectCatalogUpdate.Prepared =
        registryMutex.withLock {
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .continueMembershipPreparation(token, operationId, envelopeHash)
        }

    /** Historical service result only. No project adoption, acceptance or uncertainty reset. */
    internal suspend fun completeMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt =
        registryMutex.withLock {
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .completeMembershipCommand(token, operationId, admissionHash)
        }

    /** Explicit interrupted-phase recovery; no state adoption or acknowledgement. */
    internal suspend fun recoverMembershipCommand(token: String, operationId: String, admissionHash: String): CatalogMembershipCommands.Receipt =
        registryMutex.withLock {
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .recoverMembershipCommand(token, operationId, admissionHash)
        }

    /** Explicit envelope compaction only; canonical completion and custody remain retained. */
    internal suspend fun expireAcceptedOperation(token: String, exactOutcomeIdentity: String): CatalogMembershipCommands.Expiry =
        registryMutex.withLock {
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .expireAcceptedOperation(token, exactOutcomeIdentity)
        }

    internal suspend fun acknowledgeMembershipResult(token: String, exactOutcomeIdentity: String): CatalogMembershipCommands.Receipt =
        registryMutex.withLock {
            // Deliberate service acceptance only. Does not clear session uncertainty or adopt historical
            // state.
            (store as? JournaledProjectStore ?: error("An explicitly selected journaled store is required"))
                .acknowledgeMembership(token, exactOutcomeIdentity)
        }

    // Removal retains the app-private tree; the external SAF folder is not modified.

    suspend fun deleteWorkspace(id: String): WorkspaceRemoval? = withContext(Dispatchers.IO) {
        registryMutex.withLock { recentMutex.withLock recent@ {
        requireCatalogSessionReady()
        require(id != "default") { "Default project cannot be deleted" }
        require(_activeId.value != id) { "Switch away from a project before deleting its workspace" }
        check(store.loadActiveId() == _activeId.value) {
            "The active project changed in another session; reopen before deleting a workspace"
        }
        val target = _projects.value.firstOrNull { it.id == id } ?: return@recent null
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir).apply { mkdirs() }
        // Check live ownership again: an on-disk path may have been replaced since startup. In
        // particular an inactive ID must never delete the active/default project's root.
        ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, _projects.value)
        val root = ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(target.rootPath))
        val quarantine = File(projectsRoot, ".delete-${PathSecurity.safeLeafName(id)}")
        require(!Files.exists(quarantine.toPath(), NOFOLLOW_LINKS)) { "A previous delete transaction still needs recovery" }
        val updatedProjects = _projects.value.filterNot { it.id == id }
        val updatedRecent = ProjectHistoryPolicy.remove(_recentIds.value, id)
        // Every new relocation starts from the exact durable catalog and tree identity.
        val intent = if (Files.exists(root.toPath(), NOFOLLOW_LINKS))
            relocation.prepare(target, _projects.value, updatedProjects, store) else null
        var interrupted: Throwable? = null
        try {
            if (intent != null) relocation.move(intent)
            interrupted = persistCatalog(updatedProjects)

            _projects.value = updatedProjects
            _recentIds.value = updatedRecent
            refreshProjectPresentation()
            runCatching { recentsStore.save(updatedRecent) }
            if (intent != null) relocation.finish(store)
        } catch (caught: Throwable) {
            val committedInterruption = interrupted
            val failure = if (committedInterruption is Error || committedInterruption is CancellationException)
                committedInterruption else caught
            if (failure !== caught) failure.addSuppressed(caught)
            // Resolve only a proven disk outcome; never overwrite an occupied original location.
            try { if (intent != null) withContext(NonCancellable) { relocation.finish(store) } }
            catch (recovery: Throwable) {
                if (recovery is Error && failure !is Error) {
                    if (recovery !== failure) recovery.addSuppressed(failure)
                    throw recovery
                }
                if (recovery !== failure) failure.addSuppressed(recovery)
                if (failure !is Error && failure !is CancellationException && failure !is ProjectCatalogUncertainException) {
                    throw java.io.IOException("Project deletion requires recovery. Original workspace: ${root.path}. " +
                        "Retained workspace: ${quarantine.path}. Both locations were preserved.", failure)
                }
            } finally { relocation.abandon() }
            throw failure
        } finally { relocation.abandon() }

        rethrowCommittedInterruption(interrupted)
        WorkspaceRemoval(id, quarantine.path.takeIf { intent != null })
        } }
    }

    // Presentation-only removal must use the referenced API.

    suspend fun remove(id: String, deleteWorkspace: Boolean) {
        require(deleteWorkspace) { "Use removeFromRecent() for presentation-only removal" }
        this.deleteWorkspace(id)
    }

    private suspend fun markRecent(id: String) {
        recentMutex.withLock {
            val target = _projects.value.firstOrNull { it.id == id }
            require(target?.workspaceRemoved != true) { "Restore the stored project before adding it to Recent" }
            val registered = target != null
            if (!registered || id == "default") {
                refreshProjectPresentation()
                return@withLock
            }
            val next = ProjectHistoryPolicy.markRecent(
                current = _recentIds.value,
                id = id,
                registeredIds = _projects.value.filterNot { it.workspaceRemoved }.map { it.id }.toSet(),
            )
            recentsStore.save(next)
            _recentIds.value = next
            refreshProjectPresentation()
        }
    }

    private fun refreshProjectPresentation() {
        val all = _projects.value.filterNot { it.workspaceRemoved }
        val byId = all.associateBy { it.id }
        val recent = _recentIds.value.mapNotNull(byId::get)
        val default = byId["default"]
        _recentProjects.value = listOfNotNull(default) + recent.filterNot { it.id == "default" }
        val recentSet = _recentIds.value.toSet()
        _otherProjects.value = all.filter { it.id != "default" && it.id !in recentSet }
        _storedProjects.value = _projects.value.filter { it.workspaceRemoved }
    }

    fun active(): DroideProject? = _projects.value.firstOrNull { it.id == _activeId.value && !it.workspaceRemoved }
    fun activeRoot(): File {
        val projectsRoot = ProjectWorkspacePolicy.projectsRoot(appFilesDir)
        val project = active() ?: return ProjectWorkspacePolicy.defaultRoot(projectsRoot, "default")
        return ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(project.rootPath))
    }

}
