package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.io.File
import java.util.concurrent.ConcurrentHashMap

 
class TerminalManager private constructor(
    private val scope: CoroutineScope,
    private val defaultDir: File,
    private val context: Context? = null,
    private val deviceBridge: DeviceBridgeManager? = null,
    private val androidDevelopment: AndroidDevelopmentManager? = null,
    private val projectKey: String,
    private val localSessionFactory: ((File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession)?,
    private val linuxLaunchSpecFactory: (suspend (File, Boolean) -> TerminalLaunchSpec)?,
    val ubuntuOnly: Boolean,
    startupRecoveryOverride: StartupRecoveryPolicy? = null,
    private val nativeSessionFactory: ((File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession)? = null,
    private val commandPipeSessionFactory: ((File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession)? = null,
) {
    constructor(
        scope: CoroutineScope,
        defaultDir: File,
        context: Context? = null,
        deviceBridge: DeviceBridgeManager? = null,
        androidDevelopment: AndroidDevelopmentManager? = null,
        projectKey: String = defaultDir.absolutePath,
    ) : this(scope, defaultDir, context, deviceBridge, androidDevelopment, projectKey, null, null, true)

    internal constructor(
        scope: CoroutineScope,
        defaultDir: File,
        localSessionFactory: (File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession,
    ) : this(scope, defaultDir, null, null, null, defaultDir.absolutePath, localSessionFactory, null, false)

    internal constructor(
        scope: CoroutineScope,
        defaultDir: File,
        linuxLaunchSpecFactory: suspend (File, Boolean) -> TerminalLaunchSpec,
        localSessionFactory: (File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession,
    ) : this(scope, defaultDir, null, null, null, defaultDir.absolutePath, localSessionFactory, linuxLaunchSpecFactory, false)

    internal constructor(
        scope: CoroutineScope,
        defaultDir: File,
        ubuntuOnly: Boolean,
        linuxLaunchSpecFactory: suspend (File, Boolean) -> TerminalLaunchSpec,
        localSessionFactory: (File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession,
        startupRecovery: StartupRecoveryPolicy = StartupRecoveryPolicy.NORMAL,
    ) : this(scope, defaultDir, null, null, null, defaultDir.absolutePath, localSessionFactory, linuxLaunchSpecFactory, ubuntuOnly, startupRecovery)

    /** Exercise Android backend routing without native processes in host regressions. */
    internal constructor(
        scope: CoroutineScope,
        defaultDir: File,
        context: Context,
        startupRecovery: StartupRecoveryPolicy,
        linuxLaunchSpecFactory: suspend (File, Boolean) -> TerminalLaunchSpec,
        nativeSessionFactory: (File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession,
        commandPipeSessionFactory: (File, CoroutineScope, TerminalLaunchSpec) -> ITerminalSession,
    ) : this(scope, defaultDir, context, null, null, defaultDir.absolutePath, null, linuxLaunchSpecFactory, true,
        startupRecovery, nativeSessionFactory, commandPipeSessionFactory)

    enum class Profile { LOCAL, LOCAL_LINUX_ARM64, LOCAL_QEMU, DEVICE_ADB }

    data class Tab(
        val id: String,
        val session: ITerminalSession,
        val title: String,
        val profile: Profile,
    )

    data class Snapshot(val tabs: List<Tab>, val activeId: String?) {
        val activeTab: Tab? get() = tabs.firstOrNull { it.id == activeId }
    }

    // Android-shell launch history belongs to a different backend. Preserve it on disk,
    // but do not revive it or reinterpret its commands as Ubuntu sessions.
    private val persistentLocalHost = context?.takeUnless { ubuntuOnly }?.let(PersistentLocalTerminalHost::get)
    private val startupRecovery = startupRecoveryOverride ?: context?.let(StartupRecoveryPolicy::forProcess) ?: StartupRecoveryPolicy.NORMAL
    private val restoredLocalTabs = persistentLocalHost?.snapshot(projectKey).orEmpty().map { hosted ->
        Tab(hosted.id, hosted.session, hosted.title, Profile.LOCAL)
    }
    private val _tabs = MutableStateFlow<List<Tab>>(restoredLocalTabs)
    val tabs: StateFlow<List<Tab>> = _tabs
    val canCreateTab: Boolean get() = ApplicationWorkAdmission.isOpen && !disposed && scope.isActive && _tabs.value.size < MAX_TABS
    private val _active = MutableStateFlow<String?>(
        persistentLocalHost?.activeId(projectKey)?.takeIf { id -> restoredLocalTabs.any { it.id == id } }
            ?: restoredLocalTabs.firstOrNull()?.id
    )
    val active: StateFlow<String?> = _active
    private val _snapshot = MutableStateFlow(Snapshot(restoredLocalTabs, _active.value))
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val titleObservationJobs = ConcurrentHashMap<String, Job>()
    private val linuxCreationMutex = Mutex()
    private val preparationJobs = mutableSetOf<Job>()
    data class UbuntuPreparationState(val pending: Int = 0, val error: String? = null) {
        val preparing: Boolean get() = pending > 0
    }
    private val _ubuntuPreparation = MutableStateFlow(UbuntuPreparationState())
    val ubuntuPreparation: StateFlow<UbuntuPreparationState> = _ubuntuPreparation
    private var initialUbuntuJob: Job? = null
    private var ownershipVersion: Any = Any() // guarded by manager monitor
    private val retiredSessions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ITerminalSession, Boolean>())
    private class StopAttempt {
        val completion = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        @Volatile var outcome: Result<Unit>? = null
        lateinit var worker: Job
    }
    // Cleanup remains owned even when the workspace/Exit observer is cancelled.
    private val cleanupScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val stopAttempts = java.util.IdentityHashMap<ITerminalSession, StopAttempt>()
    private val exitSessions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ITerminalSession, Boolean>())
    private var exitComplete = false
    @Volatile private var disposed = false
    @Volatile private var linuxUnavailableReason: String = "Ubuntu workstation is not ready. Open the Ubuntu terminal to prepare it."

    internal fun observeOwnership(): TerminalRegistryObservation = synchronized(this) {
        val version = ownershipVersion
        val sessions = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ITerminalSession, Boolean>())
        sessions.addAll(_tabs.value.map { it.session }); sessions.addAll(retiredSessions)
        sessions.addAll(exitSessions); sessions.addAll(stopAttempts.keys)
        TerminalRegistryObservation(sessions.toList(), disposed,
            stopAttempts.values.count { it.outcome == null }, stopAttempts.values.count { it.outcome?.isFailure == true },
            preparationJobs.size + if (initialUbuntuJob != null) 1 else 0) {
            synchronized(this) { check(ownershipVersion === version) { "Terminal manager ownership changed during review" } }
        }
    }

    fun automatedExecutionUnavailableReason(): String = linuxUnavailableReason

    fun dismissUbuntuPreparationError() = synchronized(this) {
        _ubuntuPreparation.value = _ubuntuPreparation.value.copy(error = null)
    }

    init {
        restoredLocalTabs.forEach { tab -> observeDynamicTitle(tab.id, tab.title, tab.session) }
    }

    fun create(title: String = "Local", workDir: File = defaultDir): Tab = createLocal(title, workDir)

    @Synchronized
    fun createLocal(title: String = "Local", workDir: File = defaultDir, activate: Boolean = true, launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(), profile: Profile = Profile.LOCAL): Tab {
        ApplicationWorkAdmission.requireOpen()
        check(!disposed) { "Terminal manager is closed" }
        scope.ensureActive()
        require(!ubuntuOnly || profile == Profile.LOCAL_LINUX_ARM64) {
            "This workspace uses Ubuntu only. Open an Ubuntu terminal."
        }
        require(_tabs.value.size < MAX_TABS) { "Too many terminal tabs (max $MAX_TABS)" }
        require(profile != Profile.DEVICE_ADB) { "Device sessions require the device backend" }
        require(profile != Profile.LOCAL || launchSpec.canReviveAsAndroidShell) {
            "Custom launchers require a distinct backend profile"
        }
        require(profile != Profile.LOCAL_LINUX_ARM64 || launchSpec.prefix.isNotEmpty()) {
            "Linux execution requires an explicit backend launcher"
        }
        

        // Recovery blocks automatic startup, not a fresh requested PTY. A crash
        // in another surface must not turn every new terminal into a pipe.
        val nativeReady = context != null && NativePtyCompatibility.prepare()
        
        val hosted = if (nativeReady && profile == Profile.LOCAL) persistentLocalHost?.create(projectKey, defaultDir, title, workDir, activate) else null
        val id = hosted?.id ?: "t${System.nanoTime()}"
        val session: ITerminalSession = hosted?.session ?: if (localSessionFactory != null) {
            startSession { localSessionFactory.invoke(workDir, scope, launchSpec) }
        } else if (nativeReady) {
            TerminalBackendStart.start(
                native = { startSession {
                    nativeSessionFactory?.invoke(workDir, scope, launchSpec)
                        ?: NativePtyTerminalSession(requireNotNull(context), workDir, scope, launchSpec)
                } },
                commandPipe = { failure -> startCommandPipe(workDir, launchSpec, failure) },
            )
        } else startCommandPipe(workDir, launchSpec)
        val tab = Tab(id, session, title, profile)
        if (profile == Profile.LOCAL_LINUX_ARM64) {
            linuxUnavailableReason = "Ubuntu is available. Open or select its terminal before running commands."
            _ubuntuPreparation.value = _ubuntuPreparation.value.copy(error = null)
        }
        publish(_tabs.value + tab, if (activate || _active.value == null) id else _active.value)
        observeDynamicTitle(id, title, session)
        return tab
    }

    private fun startCommandPipe(workDir: File, launchSpec: TerminalLaunchSpec, failure: NativePtyUnavailableException? = null): ITerminalSession =
        startSession {
            commandPipeSessionFactory?.invoke(workDir, scope, launchSpec)
                ?: TerminalSession(workDir, scope, launchSpec, commandModeReason = failure?.commandModeReason,
                    humanCpuFactory = context?.applicationContext?.let { application ->
                        { stop, warn -> humanTerminalCpuProtection(application, stop, warn) }
                    })
        }


    suspend fun createLocalOnMain(
        title: String = "Local",
        workDir: File = defaultDir,
        activate: Boolean = true,
        launchSpec: TerminalLaunchSpec = TerminalLaunchSpec(),
        profile: Profile = Profile.LOCAL
    ): Tab = if (context == null) {
        createLocal(title, workDir, activate, launchSpec, profile)
    } else {
        withContext(Dispatchers.Main.immediate) { createLocal(title, workDir, activate, launchSpec, profile) }
    }

    @Synchronized
    fun createDeviceWorkstation(title: String = "Workstation", activate: Boolean = true): Tab {
        check(!ubuntuOnly) { "Use Ubuntu for project commands. ADB remains available for Android debugging." }
        ApplicationWorkAdmission.requireOpen()
        check(!disposed) { "Terminal manager is closed" }
        scope.ensureActive()
        require(_tabs.value.size < MAX_TABS) { "Too many terminal tabs (max $MAX_TABS)" }
        val bridge = requireNotNull(deviceBridge) { "Device Bridge is not available" }
        val development = requireNotNull(androidDevelopment) { "Android Development environment is not available" }
        check(bridge.state.value.supported) { "Device Workstation requires Android 11 or newer; local editor, Git and terminal remain available on Android 10" }
        check(bridge.state.value.connected != null) { "Connect Device Workstation first" }
        val id = "d${System.nanoTime()}"
        val session = startSession { DeviceWorkstationTerminalSession(bridge, development, scope) }
        val tab = Tab(id, session, title, Profile.DEVICE_ADB)
        publish(_tabs.value + tab, if (activate || _active.value == null) id else _active.value)
        observeDynamicTitle(id, title, session)
        return tab
    }


    suspend fun localExecutionSession(createIfMissing: Boolean = true): ITerminalSession? {
        check(!ubuntuOnly) { "Android shell is not a project execution backend; use Ubuntu."
        }
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL }?.session?.let { return it }
        return if (createIfMissing) createLocalOnMain(title = "Local", workDir = defaultDir, activate = false).session else null
    }

    suspend fun linuxExecutionSession(createIfMissing: Boolean = true): ITerminalSession? = prepareLinuxSession {
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL_LINUX_ARM64 }?.session?.let { return@prepareLinuxSession it }
        if (!createIfMissing) return@prepareLinuxSession null
        try {
            val launchSpec = prepareLaunchSpec(qemu = false)
            createLocalOnMain(
                title = "Ubuntu", workDir = defaultDir, activate = false,
                launchSpec = launchSpec, profile = Profile.LOCAL_LINUX_ARM64,
            ).session
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            recordUbuntuFailure(error)
            null
        }
    }

    suspend fun openLinuxTerminal(qemu: Boolean = false): Tab = prepareLinuxSession {
        require(!ubuntuOnly || !qemu) { "This workspace uses Ubuntu only; Alpine/QEMU terminals are unavailable." }
        val spec = prepareLaunchSpec(qemu)
        createLocalOnMain(title = if (qemu) "QEMU (Alpine)" else "Ubuntu", workDir = defaultDir,
            launchSpec = spec, profile = if (qemu) Profile.LOCAL_QEMU else Profile.LOCAL_LINUX_ARM64)
    }

     
    suspend fun createLinuxTerminal(title: String = "Ubuntu", activate: Boolean = true): Tab =
        prepareLinuxSession {
            val spec = prepareLaunchSpec(qemu = false)
            createLocalOnMain(
                title = title,
                workDir = defaultDir,
                activate = activate,
                launchSpec = spec,
                profile = Profile.LOCAL_LINUX_ARM64,
            )
        }

    internal suspend fun createToolchainTerminal(requireCurrent: () -> Unit): Tab = prepareLinuxSession {
        requireCurrent()
        val spec = prepareLaunchSpec(qemu = false)
        fun publish(): Tab {
            requireCurrent()
            return createLocal(title = "Ubuntu · refreshed tools", workDir = defaultDir, activate = true,
                launchSpec = spec, profile = Profile.LOCAL_LINUX_ARM64)
        }
        if (context == null) publish() else withContext(Dispatchers.Main.immediate) { publish() }
    }

    private suspend fun <T> prepareLinuxSession(block: suspend () -> T): T = coroutineScope {
        // Own a child of the caller so dispose cancels setup, not the caller's whole scope.
        val preparation = currentCoroutineContext()[Job]!!
        synchronized(this@TerminalManager) {
            ApplicationWorkAdmission.requireOpen()
            check(!disposed) { "Terminal manager is closed" }
            scope.ensureActive()
            ownershipVersion = Any()
            preparationJobs += preparation
            _ubuntuPreparation.value = UbuntuPreparationState(preparationJobs.size)
        }
        try {
            linuxCreationMutex.withLock {
                check(!disposed) { "Terminal manager is closed" }
                scope.ensureActive()
                block()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            recordUbuntuFailure(failure)
            throw failure
        } catch (failure: LinkageError) {
            recordUbuntuFailure(failure)
            throw failure
        } finally {
            synchronized(this@TerminalManager) {
                ownershipVersion = Any()
                preparationJobs -= preparation
                _ubuntuPreparation.value = _ubuntuPreparation.value.copy(pending = preparationJobs.size)
            }
        }
    }

    private fun recordUbuntuFailure(failure: Throwable) = synchronized(this) {
        linuxUnavailableReason = failure.message ?: "Ubuntu setup failed"
        _ubuntuPreparation.value = _ubuntuPreparation.value.copy(error = linuxUnavailableReason)
    }

    /** Editor initialization must not wait for rootfs extraction or start an Android shell. */
    @Synchronized
    fun prepareInitialUbuntuTerminal() {
        // Do not start a pipe or prepare the rootfs either: both can repeat work
        // that was interrupted by the previous crash. +Ubuntu/Run can retry.
        if (!startupRecovery.allowAutomaticTerminalStart) return
        if (!ApplicationWorkAdmission.isOpen || disposed || !scope.isActive || existingAutomatedExecutionSession() != null || initialUbuntuJob?.isActive == true) return
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                linuxExecutionSession(createIfMissing = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                recordUbuntuFailure(failure)
            } catch (failure: LinkageError) {
                recordUbuntuFailure(failure)
            }
        }
        ownershipVersion = Any()
        initialUbuntuJob = job
        job.invokeOnCompletion { synchronized(this) { if (initialUbuntuJob === job) { ownershipVersion = Any(); initialUbuntuJob = null } } }
        job.start()
    }

    fun cancelUbuntuPreparation() {
        val jobs = synchronized(this) { preparationJobs.toList() + listOfNotNull(initialUbuntuJob) }
        jobs.forEach { it.cancel(CancellationException("Ubuntu setup canceled")) }
    }

    private suspend fun prepareLaunchSpec(qemu: Boolean): TerminalLaunchSpec {
        require(_tabs.value.size < MAX_TABS) { "Too many terminal tabs (max $MAX_TABS)" }
        val spec = linuxLaunchSpecFactory?.invoke(defaultDir, qemu)
            ?: if (qemu) LocalExecutionSubstrate.ensureQemuReady(defaultDir) else LocalExecutionSubstrate.ensureLinuxReady(defaultDir)
        currentCoroutineContext().ensureActive()
        ApplicationWorkAdmission.requireOpen()
        check(!disposed) { "Terminal manager is closed" }
        scope.ensureActive()
        return spec
    }

    fun workstationExecutionSession(createIfMissing: Boolean = true): ITerminalSession? {
        if (ubuntuOnly) return null
        if (deviceBridge?.state?.value?.connected == null || androidDevelopment == null) return null
        _tabs.value.firstOrNull { it.profile == Profile.DEVICE_ADB }?.session?.let { return it }
        return if (createIfMissing) createDeviceWorkstation(title = "Workstation", activate = false).session else null
    }


    suspend fun automatedExecutionSession(createIfMissing: Boolean = true): ITerminalSession? =
        linuxExecutionSession(createIfMissing)

    // Shell path must be derived from the actual backend tab, never from caller intent.
    fun commandShellFor(session: ITerminalSession): String = when (
        _tabs.value.firstOrNull { it.session === session }?.profile
            ?: error("Terminal session is not owned by this workspace")
    ) {
        Profile.LOCAL -> "/system/bin/sh"
        Profile.LOCAL_LINUX_ARM64, Profile.LOCAL_QEMU -> "/bin/sh"
        Profile.DEVICE_ADB -> "/system/bin/sh"
    }

    fun existingAutomatedExecutionSession(): ITerminalSession? =
        _tabs.value.firstOrNull { it.profile == Profile.LOCAL_LINUX_ARM64 }?.session

    fun isDeviceWorkstationConnected(): Boolean =
        deviceBridge?.state?.value?.connected != null && androidDevelopment != null

    suspend fun executionSession(preferWorkstation: Boolean = true): ITerminalSession? =
        if (ubuntuOnly) linuxExecutionSession(createIfMissing = true)
        else if (preferWorkstation) workstationExecutionSession(createIfMissing = true) ?: localExecutionSession(createIfMissing = true)
        else localExecutionSession(createIfMissing = true)

     
    fun existingExecutionSession(preferWorkstation: Boolean = true): ITerminalSession? {
        if (ubuntuOnly) return existingAutomatedExecutionSession()
        val workstation = if (deviceBridge?.state?.value?.connected != null) {
            _tabs.value.firstOrNull { it.profile == Profile.DEVICE_ADB }?.session
        } else null
        val local = _tabs.value.firstOrNull { it.profile == Profile.LOCAL }?.session
        return if (preferWorkstation) workstation ?: local else local ?: workstation
    }


    suspend fun revivePersistedLocalSessions(): Int {
        if (!ApplicationWorkAdmission.isOpen || disposed) return 0
        val host = persistentLocalHost ?: return 0
        if (!startupRecovery.allowTerminalRevive || !startupRecovery.allowAutomaticTerminalStart) return 0
        val nativeReady = if (context == null) false else withContext(Dispatchers.Main.immediate) { NativePtyCompatibility.prepare() }
        if (!nativeReady || disposed) return 0
        val revived = host.reviveProject(projectKey, defaultDir)
        if (revived.isEmpty()) return 0
        return synchronized(this) {
            if (disposed) return@synchronized 0
            val currentIds = _tabs.value.mapTo(mutableSetOf()) { it.id }
            val additions = revived.filterNot { it.id in currentIds }.map { hosted ->
                Tab(hosted.id, hosted.session, hosted.title, Profile.LOCAL)
            }
            val wantedActive = host.activeId(projectKey)
            publish(_tabs.value + additions, wantedActive ?: _active.value)
            additions.forEach { tab -> observeDynamicTitle(tab.id, tab.title, tab.session) }
            additions.size
        }
    }

    private var exitTabs: List<Tab>? = null
    private var exitPreparationJobs: List<Job>? = null
    suspend fun stopAndAwaitForExit() {
        val first = synchronized(this) {
            if (exitComplete) return
            if (exitTabs == null) {
                ownershipVersion = Any()
                disposed = true
                exitTabs = _tabs.value.toList()
                exitPreparationJobs = (preparationJobs.toList() + listOfNotNull(initialUbuntuJob)).distinct()
            }
            exitSessions.addAll(exitTabs.orEmpty().map { it.session })
            exitSessions.addAll(retiredSessions)
            exitSessions.map { stopAttemptLocked(it, retryFailure = true) }
        }
        // No suspension/cancellation point between publication and starting every
        // attempt. Each worker dispatches independently, never inline under a lock.
        first.forEach { it.worker.start() }
        exitPreparationJobs.orEmpty().forEach { it.cancel(CancellationException("Droide Exit")) }
        exitPreparationJobs.orEmpty().forEach { it.join() }
        val all = synchronized(this) {
            exitSessions.addAll(retiredSessions)
            exitSessions.map { stopAttemptLocked(it, retryFailure = false) }
        }
        all.forEach { it.worker.start() }
        // Awaiting is cancellable for this observer, not for retained cleanup.
        val outcomes = all.map { it.completion.await() }
        OwnedResourceCleanup.run(*outcomes.map { result -> { result.getOrThrow() } }.toTypedArray()).getOrThrow()
        synchronized(this) {
            if (exitComplete) return
            check(exitSessions.all { stopAttempts[it]?.outcome?.isSuccess == true }) {
                "A retained terminal still needs verified cleanup; retry Exit"
            }
            // Metadata removal is exact-owner only and must not destroy a session
            // again after its verified completion. Failure remains retryable.
            exitTabs.orEmpty().filter { it.profile == Profile.LOCAL }.forEach { tab ->
                persistentLocalHost?.removeAfterVerifiedExit(projectKey, defaultDir, tab.id, tab.session)
            }
            titleObservationJobs.values.forEach { it.cancel() }
            titleObservationJobs.clear()
            preparationJobs.clear()
            initialUbuntuJob = null
            _ubuntuPreparation.value = _ubuntuPreparation.value.copy(pending = 0)
            retiredSessions.removeAll(exitSessions)
            publish(emptyList(), null)
            exitComplete = true
            cleanupScope.coroutineContext[Job]?.cancel()
        }
    }

    suspend fun flushReviveState() {
        persistentLocalHost?.flush(projectKey, defaultDir)
    }

    fun activeSession(): ITerminalSession? = _snapshot.value.activeTab?.session
    fun activeTab(): Tab? = _snapshot.value.activeTab
    @Synchronized
    fun switch(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        publish(_tabs.value, id)
        if (tab.profile == Profile.LOCAL) persistentLocalHost?.markActive(projectKey, defaultDir, id)
    }

    @Synchronized
    fun close(id: String): Result<Unit> {
        val current = _tabs.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return Result.success(Unit)
        val tab = current[index]
        titleObservationJobs.remove(id)?.cancel()
        val remaining = current.filterNot { it.id == id }
        val nextActive = if (_active.value == id) remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id else _active.value
        // Publish one coherent list/selection before detaching the old view or killing its process.
        publish(remaining, nextActive)
        return runCatching {
            releaseTab(tab)
            if (tab.profile == Profile.LOCAL && !startupRecovery.allowTerminalRevive) {
                persistentLocalHost?.acknowledgeSuppressedLocalTabClose(projectKey, defaultDir)
            }
            _snapshot.value.activeTab?.takeIf { it.profile == Profile.LOCAL }
                ?.let { persistentLocalHost?.markActive(projectKey, defaultDir, it.id) }
        }
    }


    @Synchronized
    fun dispose(preserveLocalSessions: Boolean): Result<Unit> {
        ownershipVersion = Any()
        disposed = true
        initialUbuntuJob?.cancel(CancellationException("Terminal workspace closed during setup"))
        initialUbuntuJob = null
        val pendingPreparations = preparationJobs.toList()
        preparationJobs.clear()
        _ubuntuPreparation.value = _ubuntuPreparation.value.copy(pending = 0)
        pendingPreparations.forEach { it.cancel(CancellationException("Terminal workspace closed during setup")) }
        titleObservationJobs.values.forEach { it.cancel() }
        titleObservationJobs.clear()
        val current = _tabs.value
        publish(emptyList(), null)
        var failure: Throwable? = null
        current.forEach { tab ->
            if (preserveLocalSessions && isHostedLocal(tab)) return@forEach
            runCatching { releaseTab(tab) }.onFailure { error ->
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        return failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun publish(tabs: List<Tab>, requestedActive: String?) {
        ownershipVersion = Any()
        val active = requestedActive?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
        _tabs.value = tabs
        _active.value = active
        _snapshot.value = Snapshot(tabs, active)
    }

    private fun isHostedLocal(tab: Tab): Boolean = tab.profile == Profile.LOCAL &&
        persistentLocalHost?.owns(projectKey, tab.id, tab.session) == true

    private fun releaseTab(tab: Tab) {
        retainRetiredSession(tab.session)
        // Exit owns all cleanup and host metadata from its first frozen snapshot.
        if (exitTabs != null) return
        if (!isHostedLocal(tab) || persistentLocalHost?.remove(projectKey, defaultDir, tab.id) != true) {
            tab.session.destroy()
        }
    }

    /** Called only while holding the manager monitor. */
    private fun stopAttemptLocked(session: ITerminalSession, retryFailure: Boolean): StopAttempt {
        val existing = stopAttempts[session]
        if (existing != null && !(retryFailure && existing.outcome?.isFailure == true)) return existing
        check(!exitComplete) { "Terminal Exit already completed" }
        val attempt = StopAttempt()
        ownershipVersion = Any()
        stopAttempts[session] = attempt
        attempt.worker = cleanupScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val outcome = try {
                session.stopAndAwaitForExit()
                Result.success(Unit)
            } catch (failure: Throwable) {
                Result.failure(if (failure is Exception) failure else
                    IllegalStateException("Terminal cleanup failed before verified completion", failure))
            }
            synchronized(this@TerminalManager) {
                ownershipVersion = Any()
                attempt.outcome = outcome
                if (outcome.isSuccess) {
                    retiredSessions -= session
                    // Before Exit, completed closed tabs need no permanent ledger.
                    // During Exit retain successful receipts so retries/dispose do not replay.
                    if (exitTabs == null && stopAttempts[session] === attempt) stopAttempts.remove(session)
                }
            }
            attempt.completion.complete(outcome)
        }
        return attempt
    }

    private fun retainRetiredSession(session: ITerminalSession) {
        val attempt = synchronized(this) {
            if (exitComplete) return
            ownershipVersion = Any()
            retiredSessions += session
            if (exitTabs != null) exitSessions += session
            stopAttemptLocked(session, retryFailure = false).also {
                if (it.outcome?.isSuccess == true) retiredSessions -= session
            }
        }
        attempt.worker.start()
    }

    private fun startSession(create: () -> ITerminalSession): ITerminalSession {
        val session = create()
        try {
            session.start()
            return session
        } catch (failure: Throwable) {
            retainRetiredSession(session)
            runCatching { session.destroy() }.onFailure { cleanup ->
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    private fun observeDynamicTitle(id: String, fallback: String, session: ITerminalSession) {
        val metadata = session as? TerminalMetadataSession ?: return
        titleObservationJobs.remove(id)?.cancel()
        val job = scope.launch {
            metadata.terminalTitle.collect { reported ->
                val display = TerminalTitlePolicy.display(reported)
                    ?: TerminalTitlePolicy.display(fallback)
                    ?: "Terminal"
                synchronized(this@TerminalManager) {
                    if (disposed) return@synchronized
                    val current = _tabs.value
                    val index = current.indexOfFirst { it.id == id }
                    if (index < 0 || current[index].title == display) return@synchronized
                    publish(current.toMutableList().apply {
                        this[index] = current[index].copy(title = display)
                    }, _active.value)
                }
            }
        }
        titleObservationJobs[id] = job
        job.invokeOnCompletion { titleObservationJobs.remove(id, job) }
    }

    private companion object {
        const val MAX_TABS = 16
    }

}
