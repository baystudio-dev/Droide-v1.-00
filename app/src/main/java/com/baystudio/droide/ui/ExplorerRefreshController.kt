package com.baystudio.droide.ui

import com.baystudio.droide.core.DirectoryListingOptions
import com.baystudio.droide.core.DirectoryPage
import com.baystudio.droide.core.DirectoryPageCursor
import com.baystudio.droide.core.DiscoveryBudget
import com.baystudio.droide.core.DiscoveryLimits
import com.baystudio.droide.core.ExplorerFilePolicy
import com.baystudio.droide.core.WorkspaceDiscoveryTraversal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

internal fun interface ExplorerDirectoryWatchFactory {
    /** Validation and registration run on the owner's IO dispatcher. Null means unconfirmed coverage. */
    fun open(path: String, invalidate: (selfChanged: Boolean) -> Unit): AutoCloseable?
}

/** Internal policy limits, not device timing or syscall interruption guarantees. */
internal data class ExplorerRefreshLimits(
    val watches: Int = 64,
    val inbox: Int = 64,
    val demands: Int = 64,
    val candidates: Int = 4,
    val discovery: DiscoveryLimits = DiscoveryLimits(directoryOpens = 4),
    val coalesceMillis: Long = 200,
    val minimumCooldownMillis: Long = 2_000,
    val safetyIntervalMillis: Long = 60_000,
    val staleMillis: Long = 300_000,
) {
    init { require(watches in 1..64 && inbox in 1..64 && demands in 1..64 && candidates in 1..4) }
}

/**
 * One host-retained tree read lane. UI attachments own visibility, never the worker or repository.
 * All non-callback methods run on the host/Main dispatcher. The callback touches only gate/inbox.
 */
class ExplorerRefreshController internal constructor(
    private val state: ExplorerWorkspaceState,
    private val scope: CoroutineScope,
    private val lane: Mutex,
    private val load: suspend (String, DirectoryPageCursor?, DirectoryListingOptions, DiscoveryBudget, WorkspaceDiscoveryTraversal) -> DirectoryPage,
    private val watchFactory: ExplorerDirectoryWatchFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val limits: ExplorerRefreshLimits = ExplorerRefreshLimits(),
) : AutoCloseable {
    class UiLease internal constructor(internal val id: Long) {
        internal var viewport: Set<String> = emptySet()
        internal var disposed = false
    }
    private data class Watch(val id: Long, val activation: Long, var handle: AutoCloseable? = null)
    private data class Dirty(val firstAt: Long, val selfChanged: Boolean)
    private data class Demand(val after: DirectoryPageCursor?, val at: Long, val explicit: Boolean)
    private data class PassRequest(val targets: List<String>, val token: Int?, val at: Long, val manual: Boolean, val notBefore: Long = at)
    private class Pass(val request: PassRequest, var index: Int = -1, var failed: Boolean = false)
    private enum class Source { PASS, DEMAND, DIRTY, MISSING, SAFETY }
    private data class Candidate(val path: String, val after: DirectoryPageCursor?, val at: Long, val source: Source, val explicit: Boolean = false, val notBefore: Long = at)

    private val gate = Any()
    private val watches = linkedMapOf<String, Watch>()
    private val inbox = linkedMapOf<String, Dirty>()
    private var inboxSaturated = false
    private var watchIdentity = 0L
    private var activation = 0L
    private var closed = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var admitted: Job? = null
    private var leaseIdentity = 0L
    private var lease: UiLease? = null
    private var visible = false
    private var bindingVersion = 0L
    private var observedGeneration = state.pageGeneration
    private var watchScopeChanged = false
    private val rebindAfterRead = linkedSetOf<String>()
    private val demands = linkedMapOf<String, Demand>()
    private var pass: Pass? = null
    private var nextPass: PassRequest? = null
    private var deferred: Candidate? = null
    private var safety: Candidate? = null
    private var nextSafetyAt = Long.MAX_VALUE
    private var cooldownUntil = 0L
    private var bypassAvailable = false

    internal fun newUiLease(): UiLease = UiLease(++leaseIdentity)

    /** Called from committed SideEffect; merely constructing an owner/lease is inert. */
    internal fun updateVisibility(attachment: UiLease, resumedAndReady: Boolean, paneVisible: Boolean, treeMode: Boolean) {
        if (closed || attachment.disposed || attachment.id < (lease?.id ?: 0L)) return
        val replaced = lease !== attachment
        if (replaced) {
            deactivate()
            lease = attachment
        }
        val next = resumedAndReady && paneVisible && treeMode
        if (visible == next) return
        if (!next) { deactivate(); return }
        visible = true
        bindingVersion++
        synchronized(gate) { activation++ }
        nextSafetyAt = now() + limits.safetyIntervalMillis
        watchScopeChanged = true
        queuePass(manual = false, token = null)
        bypassAvailable = true
        ensureWorker()
        signal()
    }

    internal fun detach(attachment: UiLease) {
        attachment.disposed = true
        attachment.viewport = emptySet()
        if (lease === attachment) deactivate()
    }

    internal fun updateViewport(attachment: UiLease, pages: Set<String>) {
        if (closed || attachment.disposed) return
        val next = pages.filterTo(linkedSetOf()) { validPath(it) && state.isDirectoryPageVisible(it) }
        if (attachment.viewport == next) return
        val entered = next - attachment.viewport
        attachment.viewport = next
        if (lease !== attachment || !visible) return
        demands.entries.filter { !it.value.explicit && !inViewport(it.key) }.map { it.key }.forEach(demands::remove)
        entered.forEach { path ->
            val record = state.pageFreshness[path]
            if (record?.failure == null && (record?.succeededAt == null || record.dirtySince != null || now() - record.succeededAt >= limits.staleMillis)) {
                enqueueDemand(path, null, explicit = false)
            }
        }
        watchScopeChanged = true
        signal()
    }

    internal fun updateTreeScope(attachment: UiLease) {
        if (closed || lease !== attachment || attachment.disposed) return
        if (observedGeneration != state.pageGeneration) {
            observedGeneration = state.pageGeneration
            bindingVersion++
            admitted?.cancel()
            deferred = null
            demands.clear()
            state.loadingPages.clear()
        }
        state.pruneRefreshMetadata()
        demands.keys.filterNot(state::isDirectoryPageVisible).forEach(demands::remove)
        rebindAfterRead.removeAll { !state.isDirectoryPageVisible(it) }
        watchScopeChanged = true
        signal()
    }

    internal fun requestAppearance(attachment: UiLease) {
        if (!current(attachment) || !visible) return
        // Visibility's first appearance has already queued this pass.
        if (pass == null && nextPass == null) queuePass(manual = false, token = null)
        signal()
    }

    internal fun requestWorkspaceChange() {
        if (closed || !visible) return
        val targets = (listOf("") + lease?.viewport.orEmpty()).distinct().take(limits.watches)
        for (path in targets) {
            val record = state.pageFreshness[path] ?: ExplorerPageFreshness()
            state.pageFreshness[path] = record.copy(dirtyVersion = record.dirtyVersion + 1,
                dirtySince = record.dirtySince ?: now(), failure = null, failedAt = null)
        }
        queuePass(manual = false, token = null, fromEvents = true)
        signal()
    }

    internal fun requestRefresh(attachment: UiLease, token: Int) {
        if (!current(attachment) || state.handledRefreshToken == token) return
        state.handledRefreshToken = token
        // A whole refresh invalidates an admitted append, but never starts a competing IO job.
        state.pageGeneration++
        observedGeneration = state.pageGeneration
        bindingVersion++
        admitted?.cancel()
        pass = null
        deferred = null
        demands.clear()
        state.refreshOutcome = ExplorerRefreshOutcome.PENDING
        queuePass(manual = true, token = token)
        if (admitted == null && pass == null) bypassAvailable = true
        signal()
    }

    internal fun requestExpansion(attachment: UiLease, path: String) {
        if (!current(attachment) || !state.isDirectoryPageVisible(path)) return
        watchScopeChanged = true
        enqueueDemand(path, null, explicit = true)
    }

    internal fun requestMore(attachment: UiLease, path: String) {
        if (!current(attachment) || !state.isDirectoryPageVisible(path)) return
        val cursor = state.moreAfter[path] ?: return
        absorbInbox()
        val record = state.pageFreshness[path]
        // Displaying an old cursor never grants permission to append to an invalidated page.
        enqueueDemand(path, if (record?.dirtySince != null || record?.failure != null) null else cursor, explicit = true)
    }

    private fun current(attachment: UiLease) = !closed && !attachment.disposed && lease === attachment
    private fun validPath(path: String) = path.length <= limits.discovery.pathChars &&
        !ExplorerFilePolicy.isDroideInternalPath(path) && !path.startsWith('/') &&
        path.split('/').none { it == "." || it == ".." } && '\\' !in path
    private fun eligible(path: String) = validPath(path) && state.isDirectoryPageVisible(path)
    private fun admissible(candidate: Candidate) = eligible(candidate.path) && (candidate.explicit || inViewport(candidate.path))
    private fun inViewport(path: String) = path.isEmpty() || path in lease?.viewport.orEmpty()
    private fun signal() { wake.trySend(Unit) }

    private fun enqueueDemand(path: String, after: DirectoryPageCursor?, explicit: Boolean) {
        if (!validPath(path)) return
        if (path in demands) {
            val old = demands.getValue(path)
            demands[path] = old.copy(after = if (after == null) null else old.after, explicit = explicit || old.explicit)
        } else if (demands.size < limits.demands) {
            if (explicit && admitted == null && demands.isEmpty() && pass == null) bypassAvailable = true
            demands[path] = Demand(after, now(), explicit)
        }
        // Overflow expansions are derived from retained expansion intent, not a second command queue.
        signal()
    }

    private fun queuePass(manual: Boolean, token: Int?, fromEvents: Boolean = false) {
        if (!manual && nextPass?.manual == true) return
        val firstAt = if (!manual) nextPass?.at ?: now() else now()
        nextPass = PassRequest(if (manual) state.expandedPaths else lease?.viewport.orEmpty().filter { it.isNotEmpty() },
            token, firstAt, manual, firstAt + if (fromEvents) limits.coalesceMillis else 0)
    }

    private fun deactivate() {
        visible = false
        bindingVersion++
        admitted?.cancel()
        nextSafetyAt = Long.MAX_VALUE
        pass?.request?.takeIf { it.token != null }?.let { interrupted ->
            if (nextPass?.token == null) nextPass = interrupted
        }
        pass = null; deferred = null; safety = null
        if (nextPass?.token == null) nextPass = null
        demands.clear()
        state.loadingPages.clear()
        stopWatches()
        signal()
    }

    private fun stopWatches() {
        val handles = synchronized(gate) {
            activation++ // Fence before stop; FileObserver may call back after stopWatching.
            val old = watches.values.mapNotNull { it.handle }
            watches.clear(); inbox.clear(); inboxSaturated = false
            old
        }
        handles.forEach { runCatching { it.close() } }
        rebindAfterRead.clear()
    }

    override fun close() {
        if (closed) return
        synchronized(gate) { closed = true }
        deactivate()
        lease = null
        nextPass = null
        worker?.cancel()
        wake.close()
    }

    private fun onEvent(path: String, id: Long, owner: Long, selfChanged: Boolean) {
        try {
            synchronized(gate) {
                val watch = watches[path]
                if (closed || watch?.id != id || watch.activation != owner || activation != owner) return
                fun put(target: String, self: Boolean) {
                    val previous = inbox[target]
                    if (previous == null && inbox.size >= limits.inbox) { inboxSaturated = true; return }
                    inbox[target] = Dirty(previous?.firstAt ?: now(), self || previous?.selfChanged == true)
                }
                put(path, selfChanged)
                if (selfChanged && path.isNotEmpty()) put(path.substringBeforeLast('/', ""), false)
            }
            signal()
        } catch (_: Exception) { /* A platform callback must not escape into FileObserver's thread. */ }
    }

    private fun absorbInbox() {
        val (events, saturated) = synchronized(gate) {
            val result = inbox.toMap() to inboxSaturated
            inbox.clear(); inboxSaturated = false
            result
        }
        events.forEach { (path, event) ->
            if (eligible(path)) {
                val old = state.pageFreshness[path] ?: ExplorerPageFreshness()
                // All page invalidations share the page's revision, including repository changes.
                // A separate callback counter can collide with that revision during an append.
                state.pageFreshness[path] = old.copy(dirtyVersion = old.dirtyVersion + 1, dirtySince = old.dirtySince ?: event.firstAt)
                if (event.selfChanged) { rebindAfterRead += path; watchScopeChanged = true }
            }
        }
        if (saturated) queuePass(manual = false, token = null, fromEvents = true)
    }

    private fun ensureWorker() {
        if (worker != null) return
        worker = scope.launch {
            while (!closed) {
                if (!visible) { wake.receive(); continue }
                absorbInbox()
                if (watchScopeChanged) lane.withLock { reconcileWatches() }
                if (!visible) continue
                absorbInbox()
                if (watchScopeChanged) continue // A replacement attachment must attach before its first read.
                prepareSafety()
                val candidate = candidate()
                if (candidate == null) {
                    waitForWake((nextSafetyAt - now()).coerceAtLeast(1))
                    continue
                }
                val eventReady = if (candidate.source == Source.DIRTY) candidate.at + limits.coalesceMillis else candidate.notBefore
                val due = maxOf(eventReady,
                    if (bypassAvailable && candidate.source != Source.SAFETY) now() else cooldownUntil)
                if (due > now()) {
                    val timer = nextSafetyAt.takeIf { it > now() } ?: due
                    waitForWake(minOf(due, timer) - now())
                    continue
                }
                bypassAvailable = false
                val started = now()
                try { runDrain() }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
                finally { cooldownUntil = now() + maxOf(limits.minimumCooldownMillis, (now() - started).coerceAtLeast(0) * 9) }
            }
        }
    }

    private suspend fun waitForWake(millis: Long) {
        if (millis == Long.MAX_VALUE) wake.receive()
        else withTimeoutOrNull(millis.coerceAtLeast(1)) { wake.receive() }
    }

    private suspend fun reconcileWatches() {
        watchScopeChanged = false
        if (!visible) return
        val wanted = linkedSetOf<String>()
        fun add(path: String) { if (wanted.size < limits.watches && eligible(path) && path !in rebindAfterRead) wanted += path }
        add("")
        lease?.viewport.orEmpty().forEach(::add)
        var active = state.activeDirectory
        while (active.isNotEmpty()) { add(active); active = active.substringBeforeLast('/', "") }
        val retained = synchronized(gate) { watches.keys.toList() }
        retained.forEach(::add)
        val removed = synchronized(gate) {
            watches.keys.filterNot(wanted::contains).mapNotNull { watches.remove(it)?.handle }
        }
        removed.forEach { runCatching { it.close() } }
        val fence = bindingVersion
        var registered = 0
        for (path in wanted) {
            currentCoroutineContext().ensureActive()
            if (!visible || bindingVersion != fence) break
            val watch = synchronized(gate) {
                if (path in watches) null else Watch(++watchIdentity, activation).also { watches[path] = it }
            } ?: continue
            // Keep ownership of a returned handle even if detach occurs during blocking registration.
            withContext(kotlinx.coroutines.NonCancellable) {
                val handle = withContext(io) {
                    try { watchFactory.open(path) { self -> onEvent(path, watch.id, watch.activation, self) } }
                    catch (_: Exception) { null }
                }
                val retain = synchronized(gate) {
                    if (!closed && visible && bindingVersion == fence && watches[path] === watch) {
                        watch.handle = handle
                        true
                    } else false
                }
                if (!retain) runCatching { handle?.close() }
            }
            registered++
            if (registered % 8 == 0) yield()
        }
    }

    private fun prepareSafety() {
        if (!visible || safety != null || now() < nextSafetyAt) return
        val candidates = sequenceOf("") + lease?.viewport.orEmpty().asSequence()
        val path = candidates.filter(::eligible).distinct().filter { path ->
            val record = state.pageFreshness[path]
            val lastSuccess = record?.succeededAt ?: 0L
            val lastFailure = record?.failedAt
            now() - lastSuccess >= limits.staleMillis && (lastFailure == null || now() - lastFailure >= limits.staleMillis)
        }.minByOrNull {
            val record = state.pageFreshness[it]
            record?.attemptedAt ?: maxOf(record?.succeededAt ?: Long.MIN_VALUE, record?.failedAt ?: Long.MIN_VALUE)
        }
        if (path == null) nextSafetyAt = now() + limits.safetyIntervalMillis
        else safety = Candidate(path, null, now(), Source.SAFETY)
    }

    private fun passCandidate(): Candidate? {
        if (pass == null) nextPass?.let { pass = Pass(it); nextPass = null }
        val current = pass ?: return null
        while (current.index < current.request.targets.size) {
            val path = if (current.index < 0) "" else current.request.targets[current.index]
            if (eligible(path) && (current.request.manual || inViewport(path))) return Candidate(path, null, current.request.at, Source.PASS, current.request.manual, current.request.notBefore)
            current.index++
        }
        current.request.token?.let { token ->
            if (state.handledRefreshToken == token) {
                state.refreshOutcome = if (current.failed) ExplorerRefreshOutcome.INCOMPLETE else ExplorerRefreshOutcome.COMPLETE
                if (!current.failed) state.loadedRefreshToken = token
            }
        }
        pass = null
        return if (nextPass != null) passCandidate() else null
    }

    private fun candidate(): Candidate? {
        deferred?.let { if (admissible(it)) return it else deferred = null }
        safety?.let { if (admissible(it)) return it else safety = null }
        val passCandidate = passCandidate()
        val demand = demands.entries.firstOrNull { eligible(it.key) && (it.value.explicit || inViewport(it.key)) }?.let { Candidate(it.key, it.value.after, it.value.at, Source.DEMAND, it.value.explicit) }
        val dirty = state.pageFreshness.entries.asSequence()
            .filter { eligible(it.key) && inViewport(it.key) && it.value.failure == null && it.value.dirtySince != null }
            .minByOrNull { it.value.dirtySince!! }
            ?.let { Candidate(it.key, null, it.value.dirtySince!!, Source.DIRTY) }
        val chosen = listOfNotNull(passCandidate, demand, dirty).minByOrNull { it.at }
        if (chosen != null) return chosen
        val failed = state.pageFreshness.filterValues { it.failure != null }.keys
        return state.firstMissingExpandedDirectory(failed)?.let { Candidate(it, null, now(), Source.MISSING, explicit = true) }
    }

    private fun complete(candidate: Candidate, failed: Boolean) {
        if (deferred?.path == candidate.path) deferred = null
        demands.remove(candidate.path)
        if (safety?.path == candidate.path) { safety = null; nextSafetyAt = now() + limits.safetyIntervalMillis }
        val current = pass
        val target = current?.let { if (it.index < 0) "" else it.request.targets.getOrNull(it.index) }
        if (target == candidate.path && candidate.after == null && current != null) { current.failed = current.failed || failed; current.index++ }
        passCandidate() // Publish a terminal outcome now, not after an unrelated wake.
    }

    private suspend fun runDrain() = coroutineScope {
        val fence = bindingVersion
        val task = async {
            lane.withLock {
                if (!visible || fence != bindingVersion) return@withLock
                val budget = DiscoveryBudget(limits.discovery, nanoTime = { now() * 1_000_000L })
                val traversal = WorkspaceDiscoveryTraversal()
                for (index in 0 until limits.candidates) {
                    currentCoroutineContext().ensureActive()
                    if (!visible || fence != bindingVersion) break
                    absorbInbox()
                    val chosen = candidate() ?: break
                    val readyAt = if (chosen.source == Source.DIRTY) chosen.at + limits.coalesceMillis else chosen.notBefore
                    if (index > 0 && readyAt > now()) break
                    if (chosen.source == Source.SAFETY) nextSafetyAt = now() + limits.safetyIntervalMillis
                    val generation = state.pageGeneration
                    val options = state.options
                    val previous = state.pageFreshness[chosen.path] ?: ExplorerPageFreshness()
                    val version = previous.dirtyVersion
                    val after = chosen.after?.takeIf {
                        previous.dirtySince == null && previous.failure == null && state.moreAfter[chosen.path] == it && it.options == options
                    }
                    state.pageFreshness[chosen.path] = previous.copy(dirtySince = null, attemptedAt = now())
                    state.loadingPages[chosen.path] = generation
                    try {
                        val page = withContext(io) {
                            budget.checkpoint()
                            budget.admitPath(chosen.path)
                            budget.requireCompleteDirectory(chosen.path)
                            load(chosen.path, after, options, budget, traversal)
                        }
                        currentCoroutineContext().ensureActive()
                        budget.requireCompleteDirectory(chosen.path)
                        absorbInbox()
                        if (!visible || fence != bindingVersion || generation != state.pageGeneration || !eligible(chosen.path)) break
                        val latest = state.pageFreshness[chosen.path] ?: previous
                        if (after != null && latest.dirtyVersion != version) {
                            demands[chosen.path] = Demand(null, chosen.at, chosen.explicit)
                            continue
                        }
                        if (state.patchDirectoryPage(chosen.path, generation, page, after)) {
                            state.pageFreshness[chosen.path] = latest.copy(
                                succeededAt = if (latest.dirtyVersion == version) now() else latest.succeededAt,
                                failure = null, failedAt = null,
                            )
                            rebindAfterRead.remove(chosen.path)
                            watchScopeChanged = true
                            complete(chosen, failed = false)
                        }
                    } catch (cancelled: CancellationException) {
                        // An unrelated collapse can cancel this generation without consuming its event.
                        if (!closed && eligible(chosen.path)) {
                            val latest = state.pageFreshness[chosen.path] ?: previous
                            previous.dirtySince?.let { dirtyAt ->
                                state.pageFreshness[chosen.path] = latest.copy(
                                    dirtyVersion = maxOf(version, latest.dirtyVersion),
                                    dirtySince = minOf(dirtyAt, latest.dirtySince ?: dirtyAt),
                                )
                            }
                            if (chosen.source == Source.DEMAND) enqueueDemand(chosen.path, null, chosen.explicit)
                        }
                        throw cancelled
                    } catch (failure: Exception) {
                        if (!visible || fence != bindingVersion || generation != state.pageGeneration || !eligible(chosen.path)) break
                        if (budget.incomplete && index > 0) {
                            // Remaining allowance is not evidence that this directory exceeds a whole batch.
                            deferred = chosen.copy(after = null)
                        } else {
                            val latest = state.pageFreshness[chosen.path] ?: previous
                            state.pageFreshness[chosen.path] = latest.copy(
                                failure = failure.message ?: "Could not read ${chosen.path.ifBlank { "/" }}",
                                failedAt = now(), dirtySince = null,
                            )
                            complete(chosen, failed = true)
                        }
                        if (budget.incomplete) break
                    } finally {
                        if (state.loadingPages[chosen.path] == generation) state.loadingPages.remove(chosen.path)
                    }
                }
            }
        }
        admitted = task
        try { task.await() }
        finally {
            // Joining belongs to the worker, never Main's caller. Non-cancellable IO cleanup keeps
            // this lane locked; a recreated UI or replacement owner must wait for the same Mutex.
            task.cancelAndJoin()
            if (admitted === task) admitted = null
        }
    }

    internal fun pendingInvalidationsForTest(): Int = synchronized(gate) { inbox.size }
    internal fun retainedWatchesForTest(): Int = synchronized(gate) { watches.size }
    internal fun pendingDemandsForTest(): Int = demands.size
}
