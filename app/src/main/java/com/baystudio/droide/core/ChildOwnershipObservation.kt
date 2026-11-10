package com.baystudio.droide.core

import java.lang.ref.WeakReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Immutable direct-child diagnostics. No Job, terminal, disposer or strong owner escapes. */
internal class ChildOwnershipObservation(retainedOwners: List<String>, unknownCoverage: List<String> = emptyList(), private val validateOwner: () -> Unit) {
    private val unavailable = unknownCoverage.toList()
    val retainedOwners: List<String> = java.util.Collections.unmodifiableList(retainedOwners.toList())
    fun validateMetadata() = validateOwner()
    fun evidence() = CatalogReleasePreflight.ProcessEvidence(emptyList(),
        unknownDescendants = listOf("Child resource callbacks do not establish descendant closure"),
        unknownDescriptors = listOf("Child resource callbacks do not establish descriptor closure"),
        unknownCoverage = listOf("Removed/nested child history and lifecycle/admission coverage are unavailable; direct active ownership only") + unavailable,
        retainedOwners = retainedOwners)
}

/** Encapsulates the existing exact task/attempt job map, not an additional registry. */
internal class SubagentJobOwners {
    private val lock = Any()
    private val entries = LinkedHashMap<String, SubagentOwnedJob>()
    private var version: Any = Any()
    operator fun get(id: String): SubagentOwnedJob? = synchronized(lock) { entries[id] }
    operator fun set(id: String, owned: SubagentOwnedJob) = synchronized(lock) {
        version = Any(); entries[id] = owned
    }
    val values: List<SubagentOwnedJob> get() = synchronized(lock) { entries.values.toList() }
    fun remove(id: String, owned: SubagentOwnedJob): Boolean = synchronized(lock) {
        if (entries[id] !== owned) false else {
            version = Any(); entries.remove(id); true
        }
    }
    fun clear() = synchronized(lock) { version = Any(); entries.clear() }

    fun attachResources(id: String, attempt: Int, executingJob: kotlinx.coroutines.Job, root: String, resource: SubagentOwnedResources): AutoCloseable {
        val owned = synchronized(lock) {
            val exact = checkNotNull(entries[id]) { "Child job ownership is unavailable" }
            check(exact.attempt == attempt && exact.job === executingJob && exact.resource == null) { "Child task attempt ownership changed" }
            version = Any(); exact.resource = resource; exact.resourceRoot = root; exact
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { if (released.compareAndSet(false, true)) synchronized(lock) {
            // Clear this exact record even if it was removed; never clear a replacement retry.
            if (owned.resource === resource) {
                owned.resource = null; owned.resourceRoot = null
                if (entries[id] === owned) version = Any()
            }
        } }
    }

    private data class Scope(val id: String, val attempt: Int, val root: String?,
                             val resource: WeakReference<SubagentOwnedResources>?)
    suspend fun observe(): ChildOwnershipObservation {
        currentCoroutineContext().ensureActive()
        val snapshot = synchronized(lock) {
            version to entries.map { (id, owned) -> Scope(id, owned.attempt, owned.resourceRoot,
                owned.resource?.let { WeakReference(it) }) }
        }
        val resources = snapshot.second.mapNotNull { entry ->
            entry.resource?.let { reference ->
                (reference.get() ?: error("Child resource authority unavailable")).observeTerminals()
            }
        }
        val weak = WeakReference(this)
        val token = snapshot.first
        val summaries = buildList {
            snapshot.second.forEach { entry ->
                add("Child task ownership: ${entry.id} attempt ${entry.attempt}; root ${entry.root ?: "unavailable"}")
            }
            resources.forEach { addAll(it.retainedOwners) }
        }
        // Resource observations themselves contain only immutable summaries and weak validators.
        return ChildOwnershipObservation(summaries) {
            val owner = weak.get() ?: error("Child job authority unavailable")
            owner.requireVersion(token)
            resources.forEach { it.validateMetadata() }
            owner.requireVersion(token)
        }.also { currentCoroutineContext().ensureActive(); it.validateMetadata() }
    }
    private fun requireVersion(expected: Any) = synchronized(lock) {
        check(version === expected) { "Child task ownership changed during review" }
    }
}
