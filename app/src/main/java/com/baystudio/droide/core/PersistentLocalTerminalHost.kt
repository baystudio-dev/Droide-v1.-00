package com.baystudio.droide.core

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PersistentLocalTerminalHost private constructor(
    private val appContext: Context,
    private val sessionFactory: (File, CoroutineScope) -> NativePtySessionHandle = { dir, scope ->
        NativePtyTerminalSession(appContext, dir, scope)
    },
    private val reviveOverride: Boolean? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val startupRecoveryOverride: StartupRecoveryPolicy? = null,
) {
    data class HostedTab(
        val id: String,
        val title: String,
        val workDir: File,
        val session: NativePtySessionHandle,
    )

    // Cancelling a WorkspaceViewModel therefore cannot silently break execOnce/execArgv on a
    // retained PTY.

    private val processScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    private val reviveStore = TerminalReviveStore(appContext.filesDir)
    private val hydratedProjects = mutableSetOf<String>()
    private val startupRecovery = startupRecoveryOverride ?: StartupRecoveryPolicy.forProcess(appContext)
    private val reviveAllowedForProcess = reviveOverride ?: startupRecovery.allowTerminalRevive
    private val preserveSuppressedRevive = reviveOverride == null && !reviveAllowedForProcess && !startupRecovery.clearTerminalRevive

    init {
        if (reviveOverride == null && startupRecovery.clearTerminalRevive) reviveStore.clearAll()
    }
    private var ownershipVersion: Any = Any()
    private val tabsByProject = linkedMapOf<String, LinkedHashMap<String, HostedTab>>()
    private val activeLocalByProject = mutableMapOf<String, String>()
    private val explicitlyClosedRecoveryProjects = mutableSetOf<String>()

    @Synchronized private fun requireCapacity(projectKey: String, requested: Int) {
        check((tabsByProject[projectKey]?.size ?: 0) + requested <= MAX_LOCAL_TABS_PER_PROJECT) {
            "Too many retained local terminal tabs (max $MAX_LOCAL_TABS_PER_PROJECT). Close unused tabs before adding another."
        }
        check(tabsByProject.values.sumOf { it.size } + requested <= MAX_RETAINED_LOCAL_TABS) {
            "Too many retained local terminals across projects (max $MAX_RETAINED_LOCAL_TABS). Return to another project and close unused local tabs. Existing shells were preserved."
        }
    }

    @Synchronized private fun markHydrated(projectKey: String) {
        hydratedProjects.remove(projectKey)
        hydratedProjects.add(projectKey)
        while (hydratedProjects.size > MAX_HYDRATED_HISTORY) {
            val empty = hydratedProjects.firstOrNull { tabsByProject[it].isNullOrEmpty() } ?: break
            hydratedProjects.remove(empty)
        }
    }

    @Synchronized
    fun snapshot(projectKey: String): List<HostedTab> =
        tabsByProject[projectKey]?.values?.toList().orEmpty()

    internal fun observeOwnership(): TerminalRegistryObservation = synchronized(this) {
        val version = ownershipVersion
        val sessions = tabsByProject.values.flatMap { tabs -> tabs.values.map { it.session } }
        TerminalRegistryObservation(sessions, false, 0, 0, 0) {
            synchronized(this) { check(ownershipVersion === version) { "Retained terminal host ownership changed during review" } }
        }
    }

    @Synchronized
    fun activeId(projectKey: String): String? =
        activeLocalByProject[projectKey]?.takeIf { id -> tabsByProject[projectKey]?.containsKey(id) == true }

    @Synchronized
    fun owns(projectKey: String, id: String, session: ITerminalSession): Boolean =
        tabsByProject[projectKey]?.get(id)?.session === session

    internal val hydratedHistorySize: Int
        get() = synchronized(this) { hydratedProjects.size }

    suspend fun reviveProject(projectKey: String, projectRoot: File): List<HostedTab> {
        synchronized(this) {
            tabsByProject[projectKey]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (projectKey in hydratedProjects) return emptyList()
        }
        if (!reviveAllowedForProcess) {
            markHydrated(projectKey)
            return emptyList()
        }
        val snapshot = withContext(Dispatchers.IO) { reviveStore.load(projectKey, projectRoot) }
        if (snapshot == null) {
            markHydrated(projectKey)
            return emptyList()
        }
        return withContext(mainDispatcher) {
            NativePtyThreadPolicy.requireMainThread(
                isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
            )
            synchronized(this@PersistentLocalTerminalHost) {
                tabsByProject[projectKey]?.values?.toList()?.takeIf { it.isNotEmpty() }?.let { existing ->
                    markHydrated(projectKey)
                    return@withContext existing
                }
            }
            val descriptors = snapshot.tabs.take(MAX_LOCAL_TABS_PER_PROJECT)
            // Capacity rejection is not a corrupt revive snapshot: preserve it for a later retry.
            requireCapacity(projectKey, descriptors.size)
            val created = mutableListOf<HostedTab>()
            try {
                descriptors.forEach { descriptor ->
                    val hosted = HostedTab(
                        id = descriptor.id,
                        title = descriptor.title,
                        workDir = descriptor.workDir,
                        session = sessionFactory(descriptor.workDir, processScope),
                    )
                    created += hosted
                    // Track the handle before start: a failed native startup still owns partially-created
                    // threads/process resources that must be destroyed.
                    hosted.session.start()
                }
                synchronized(this@PersistentLocalTerminalHost) {
                    val projectTabs = linkedMapOf<String, HostedTab>()
                    created.forEach { projectTabs[it.id] = it }
                    ownershipVersion = Any()
                    if (projectTabs.isNotEmpty()) tabsByProject[projectKey] = projectTabs
                    val active = snapshot.activeId?.takeIf(projectTabs::containsKey) ?: projectTabs.keys.firstOrNull()
                    if (active != null) activeLocalByProject[projectKey] = active
                    markHydrated(projectKey)
                    projectTabs.values.toList()
                }
            } catch (t: Throwable) {
                created.forEach { runCatching { it.session.destroy() } }
                withContext(Dispatchers.IO) { reviveStore.delete(projectKey) }
                markHydrated(projectKey)
                throw t
            }
        }
    }

    fun create(projectKey: String, projectRoot: File, title: String, workDir: File, activate: Boolean): HostedTab {
        NativePtyThreadPolicy.requireMainThread(
            isMainThread = Looper.getMainLooper().thread === Thread.currentThread(),
        )
        requireCapacity(projectKey, 1)
        val hosted = HostedTab(
            id = "t${System.nanoTime()}",
            title = title,
            workDir = workDir,
            session = sessionFactory(workDir, processScope),
        )
        try { hosted.session.start() } catch (failure: Throwable) {
            runCatching { hosted.session.destroy() }.onFailure { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
        synchronized(this) {
            val projectTabs = tabsByProject.getOrPut(projectKey) { linkedMapOf() }

            try { requireCapacity(projectKey, 1) } catch (failure: IllegalStateException) {
                if (projectTabs.isEmpty()) tabsByProject.remove(projectKey)
                hosted.session.destroy()
                throw failure
            }
            ownershipVersion = Any()
            projectTabs[hosted.id] = hosted
            if (activate || activeLocalByProject[projectKey] == null) {
                activeLocalByProject[projectKey] = hosted.id
            }
            markHydrated(projectKey)
        }
        schedulePersist(projectKey, projectRoot)
        return hosted
    }

    fun markActive(projectKey: String, projectRoot: File, id: String) {
        val changed = synchronized(this) {
            if (tabsByProject[projectKey]?.containsKey(id) == true) {
                activeLocalByProject[projectKey] = id
                true
            } else false
        }
        if (changed) schedulePersist(projectKey, projectRoot)
    }

    /** Closing the recovery Local tab is explicit intent; merely stopping the Activity is not. */
    fun acknowledgeSuppressedLocalTabClose(projectKey: String, projectRoot: File) {
        if (!preserveSuppressedRevive) return
        synchronized(this) { explicitlyClosedRecoveryProjects += projectKey }
        schedulePersist(projectKey, projectRoot)
    }

    fun remove(projectKey: String, projectRoot: File, id: String): Boolean {
        val removed = synchronized(this) {
            val projectTabs = tabsByProject[projectKey] ?: return@synchronized null
            val tab = projectTabs.remove(id) ?: return@synchronized null
            ownershipVersion = Any()
            if (activeLocalByProject[projectKey] == id) {
                projectTabs.keys.firstOrNull()?.let { activeLocalByProject[projectKey] = it }
                    ?: activeLocalByProject.remove(projectKey)
            }
            if (projectTabs.isEmpty()) tabsByProject.remove(projectKey)
            tab
        } ?: return false
        try { removed.session.destroy() }
        finally { schedulePersist(projectKey, projectRoot) }
        return true
    }

    internal fun removeAfterVerifiedExit(projectKey: String, projectRoot: File, id: String, expected: ITerminalSession) {
        synchronized(this) {
            val projectTabs = tabsByProject[projectKey]
            val tab = projectTabs?.get(id)
            check(tab == null || tab.session === expected) { "Terminal host owner changed during Exit" }
            if (tab != null) {
                ownershipVersion = Any()
                projectTabs.remove(id)
                if (activeLocalByProject[projectKey] == id) {
                    projectTabs.keys.firstOrNull()?.let { activeLocalByProject[projectKey] = it }
                        ?: activeLocalByProject.remove(projectKey)
                }
                if (projectTabs.isEmpty()) tabsByProject.remove(projectKey)
            }
        }
        // Retry persistence even if a previous attempt removed metadata then failed. Never call destroy
        // here: the exact completed stop must not be replayed.
        schedulePersist(projectKey, projectRoot)
    }

    fun destroyProject(projectKey: String, projectRoot: File) {
        val removed = synchronized(this) {
            ownershipVersion = Any()
            activeLocalByProject.remove(projectKey)
            tabsByProject.remove(projectKey)?.values?.toList().orEmpty()
        }
        var failure: Throwable? = null
        try {
            removed.forEach { tab ->
                runCatching { tab.session.destroy() }.onFailure { error ->
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
        } finally { schedulePersist(projectKey, projectRoot) }
        failure?.let { throw it }
    }

    suspend fun flush(projectKey: String, projectRoot: File) {
        persistenceMutex.withLock { persistLatest(projectKey, projectRoot) }
    }

    private fun schedulePersist(projectKey: String, projectRoot: File) {
        persistenceScope.launch {
            runCatching { persistenceMutex.withLock { persistLatest(projectKey, projectRoot) } }
        }
    }

    private fun persistLatest(projectKey: String, projectRoot: File) {
        val state = synchronized(this) {
            val tabs = tabsByProject[projectKey]?.values.orEmpty().map { hosted ->
                TerminalReviveDescriptor(hosted.id, hosted.title, hosted.workDir)
            }
            tabs to activeLocalByProject[projectKey]
        }
        // A conservative crash restart uses a pipe shell. It has no new native descriptors; onStop must
        // not turn that absence into deletion of the prior launch metadata.
        if (state.first.isEmpty() && preserveSuppressedRevive && synchronized(this) {
            projectKey !in explicitlyClosedRecoveryProjects
        }) return
        if (state.first.isEmpty()) reviveStore.delete(projectKey)
        else reviveStore.save(projectKey, projectRoot, state.first, state.second)
        if (state.first.isEmpty()) synchronized(this) {
            explicitlyClosedRecoveryProjects.remove(projectKey)
            if (tabsByProject[projectKey].isNullOrEmpty()) hydratedProjects.remove(projectKey)
        }
    }

    companion object {
        private const val MAX_LOCAL_TABS_PER_PROJECT = 16
        internal const val MAX_RETAINED_LOCAL_TABS = 32
        internal const val MAX_HYDRATED_HISTORY = 256

        internal fun forTesting(
            context: Context,
            mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
            recoveryPolicy: StartupRecoveryPolicy? = null,
            factory: (File, CoroutineScope) -> NativePtySessionHandle,
        ): PersistentLocalTerminalHost = PersistentLocalTerminalHost(
            context, factory, if (recoveryPolicy == null) true else null, mainDispatcher, recoveryPolicy,
        )

        @Volatile
        private var instance: PersistentLocalTerminalHost? = null

        /** Read only: never initialize the host or hydrate/rewrite persisted revive state. */
        internal fun existing(): PersistentLocalTerminalHost? = instance

        fun get(context: Context): PersistentLocalTerminalHost =
            instance ?: synchronized(this) {
                instance ?: PersistentLocalTerminalHost(context.applicationContext).also { instance = it }
            }
    }
}
