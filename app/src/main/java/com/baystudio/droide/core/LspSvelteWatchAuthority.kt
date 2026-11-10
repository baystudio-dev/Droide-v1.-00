package com.baystudio.droide.core

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Exact source-pinned dynamic registration and one coalesced, lifetime-owned filesystem observer. */
internal class LspSvelteWatchAuthority(
    val ownerKey: String,
    root: File,
    scope: CoroutineScope,
    private val toUri: (File) -> String?,
    private val onInvalidated: (Invalidation) -> Unit,
    private val onEvents: suspend (EventDrain) -> Unit,
    limits: LspSvelteFilePoller.Limits = LspSvelteFilePoller.Limits(),
    private val settings: Settings = Settings(),
    private val clockNanos: () -> Long = System::nanoTime,
    initialSnapshot: LspSvelteFilePoller.Snapshot? = null,
    private val onObservation: (Observation) -> Unit = {},
) {
    data class Settings(val pollMillis: Long = 3_000, val maximumAgeMillis: Long = 10_000, val registrationTimeoutMillis: Long = 20_000) {
        init {
            require(pollMillis in 10..60_000 && maximumAgeMillis in 10..120_000 && maximumAgeMillis >= pollMillis)
            require(registrationTimeoutMillis in 10..120_000)
        }
    }
    data class Registration(val id: String, val mask: Int)
    data class Observation(
        val ownerKey: String,
        val registrationEpoch: Long,
        val generation: Long,
        val snapshot: LspSvelteFilePoller.Snapshot,
    ) {
        val criticalDigest: String get() = snapshot.criticalDigest
    }
    enum class InvalidationKind { DIFFERENCE, COVERAGE_FAILURE, REGISTRATION_FAILURE, UNREGISTERED }
    data class Invalidation(
        val ownerKey: String,
        val epoch: Long,
        val kind: InvalidationKind,
        val reason: String,
        val difference: LspSvelteFilePoller.Difference? = null,
    ) {
        val criticalChange: Boolean get() = difference?.critical == true
        val relativePath: String? get() = difference?.relativePath
    }
    data class WatchedEvent(val uri: String, val type: Int)
    data class EventDrain(
        val ownerKey: String,
        val registrationEpoch: Long,
        val invalidationEpoch: Long,
        val registrationIds: Set<String>,
        val events: List<WatchedEvent>,
        val snapshot: LspSvelteFilePoller.Snapshot,
        val criticalChanged: Boolean,
    ) {
        fun params(): JsonObject = buildJsonObject {
            put("changes", buildJsonArray { events.forEach { event -> add(buildJsonObject {
                put("uri", event.uri); put("type", event.type)
            }) } })
        }
    }
    sealed interface RegistrationResult {
        val ownerKey: String
        val registrationEpoch: Long
        val invalidationEpoch: Long
        data class Accepted(val observation: Observation?, override val ownerKey: String, override val registrationEpoch: Long,
            override val invalidationEpoch: Long) : RegistrationResult
        data class Rejected(val reason: String, override val ownerKey: String, override val registrationEpoch: Long,
            override val invalidationEpoch: Long) : RegistrationResult
    }
    class UnavailableException(message: String) : Exception(message)

    private val lock = Any()
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val observerScope = CoroutineScope(scope.coroutineContext + lifetime)
    private val poller = LspSvelteFilePoller(root, limits, clockNanos)
    private var active = true
    private var invalidated = false
    private var drainAllowed = false
    private var registrationEpoch = 0L
    private var invalidationEpoch = 0L
    private var generation = 0L
    private var registrations: Map<String, Registration> = emptyMap()
    private var current: Observation? = null
    private var baseline: LspSvelteFilePoller.Snapshot? = initialSnapshot
    private var scan: Deferred<Observation>? = null
    private var polling: Job? = null
    private var currentDrain: EventDrain? = null
    private var lastRegistrationResult: RegistrationResult? = null
    private val firstRegistration = CompletableDeferred<Unit>()

    init {
        require(ownerKey.isNotBlank())
        lifetime.invokeOnCompletion { close() }
    }

    /** Atomic closed-schema batch. Success is returned only after a complete baseline and a live poll task. */
    suspend fun register(params: JsonElement?): RegistrationResult {
        val parsed = parseRegistration(params)
            ?: return rejectRegistration("Unsupported Svelte watched-file registration")
        val problem = synchronized(lock) {
            when {
                !active || invalidated -> "Svelte watcher owner is no longer live"
                registrations.size + parsed.size > MAX_REGISTRATIONS -> "Too many Svelte watcher registrations"
                parsed.any { it.id in registrations } -> "Duplicate Svelte watcher registration ID"
                (registrations.values + parsed).fold(0) { mask, item -> mask or item.mask } != 7 -> "Incomplete Svelte watcher kind coverage"
                else -> {
                    registrations = Collections.unmodifiableMap(LinkedHashMap(registrations).apply { parsed.forEach { put(it.id, it) } })
                    registrationEpoch++
                    current = null
                    null
                }
            }
        }
        if (problem != null) return rejectRegistration(problem)
        ensurePolling()
        try {
            val observed = observation()
            if (!isCurrent(observed)) return rejectRegistration("Svelte baseline expired before registration acknowledgement")
            firstRegistration.complete(Unit)
            return synchronized(lock) { RegistrationResult.Accepted(observed, ownerKey, registrationEpoch, invalidationEpoch).also { lastRegistrationResult = it } }
        } catch (failure: CancellationException) {
            if (lifetime.isActive) failCoverage("Svelte registration baseline was cancelled")
            throw failure
        } catch (failure: Exception) {
            return rejectRegistration(failure.message ?: "Svelte baseline unavailable")
        }
    }

    /** LSP's intentionally misspelled `unregisterations` key is the only supported wire spelling. */
    fun unregister(params: JsonElement?): RegistrationResult {
        val ids = parseUnregistration(params)
            ?: return rejectRegistration("Unsupported Svelte watcher unregistration")
        var removedCoverage = false
        val outcome = synchronized(lock) {
            when {
                !active || invalidated -> "Svelte watcher owner is no longer live"
                ids.any { it !in registrations } -> "Unknown Svelte watcher registration ID"
                else -> {
                    registrations = Collections.unmodifiableMap(LinkedHashMap(registrations).apply { ids.forEach { remove(it) } })
                    registrationEpoch++
                    current = null
                    removedCoverage = registrations.values.fold(0) { mask, item -> mask or item.mask } != 7
                    if (removedCoverage) {
                        // Reserve invalidation atomically with removal. A concurrent register cannot restore an owner's
                        // lost coverage before the out-of-lock callback runs.
                        invalidated = true
                        drainAllowed = false
                        currentDrain = null
                        lastRegistrationResult = null
                    }
                    null
                }
            }
        }
        if (outcome != null) return rejectRegistration(outcome)
        if (removedCoverage) {
            val notification = synchronized(lock) {
                if (active && lifetime.isActive) Invalidation(ownerKey, ++invalidationEpoch, InvalidationKind.UNREGISTERED,
                    "Required Svelte watcher coverage was removed") else null
            }
            if (notification != null && synchronized(lock) { active && lifetime.isActive && notification.epoch == invalidationEpoch }) {
                onInvalidated(notification)
                firstRegistration.completeExceptionally(UnavailableException(notification.reason))
            }
        }
        return synchronized(lock) { RegistrationResult.Accepted(null, ownerKey, registrationEpoch, invalidationEpoch).also { lastRegistrationResult = it } }
    }

    suspend fun awaitRegisteredObservation(): Observation = try {
        withTimeout(settings.registrationTimeoutMillis) {
            firstRegistration.await()
            observation()
        }
    } catch (failure: TimeoutCancellationException) {
        failCoverage("Svelte watcher registration timed out")
        throw UnavailableException("Svelte watcher registration timed out")
    }

    fun registrationCurrent(result: RegistrationResult): Boolean = synchronized(lock) {
        active && lifetime.isActive && result === lastRegistrationResult && result.ownerKey == ownerKey && result.registrationEpoch == registrationEpoch &&
            result.invalidationEpoch == invalidationEpoch &&
            (result !is RegistrationResult.Accepted || result.observation == null || currentLocked(result.observation))
    }

    /** Missing/expired observations share exactly one pass; fresh completed observations require no I/O. */
    suspend fun observation(): Observation {
        val work = synchronized(lock) {
            checkAvailable()
            current?.takeIf { currentLocked(it) }?.let { return it }
            scan?.takeIf { !it.isCompleted } ?: createScanLocked().also { scan = it }
        }
        work.start()
        return work.await()
    }

    fun isCurrent(observation: Observation): Boolean = synchronized(lock) { currentLocked(observation) }

    fun isDrainCurrent(drain: EventDrain): Boolean = synchronized(lock) {
        active && lifetime.isActive && invalidated && drainAllowed && drain === currentDrain && drain.ownerKey == ownerKey &&
            drain.registrationEpoch == registrationEpoch && drain.invalidationEpoch == invalidationEpoch &&
            drain.registrationIds == registrations.keys
    }

    /** Idempotent. Cancels only this exact owner's jobs. Already executing callbacks still need the manager's owner guard. */
    fun close() {
        val changed = synchronized(lock) {
            if (!active) false else {
                active = false
                invalidated = true
                drainAllowed = false
                current = null
                currentDrain = null
                lastRegistrationResult = null
                registrations = emptyMap()
                invalidationEpoch++
                true
            }
        }
        if (changed) {
            firstRegistration.cancel(CancellationException("Svelte watcher closed"))
            lifetime.cancel()
        }
    }

    private fun currentLocked(observation: Observation): Boolean = active && lifetime.isActive && !invalidated &&
        observation === current && observation.ownerKey == ownerKey && observation.registrationEpoch == registrationEpoch &&
        polling?.isActive == true && clockNanos() - observation.snapshot.completedNanos in 0..settings.maximumAgeMillis * 1_000_000L

    private fun checkAvailable() {
        if (!active || !lifetime.isActive || invalidated || registrations.isEmpty()) throw UnavailableException("Svelte watcher coverage is unavailable")
    }

    private fun ensurePolling() {
        val task = synchronized(lock) {
            if (!active || invalidated) return
            polling ?: observerScope.launch(start = CoroutineStart.LAZY) {
                while (true) {
                    delay(settings.pollMillis)
                    try {
                        val work = synchronized(lock) {
                            checkAvailable()
                            scan?.takeIf { !it.isCompleted } ?: createScanLocked().also { scan = it }
                        }
                        work.start()
                        work.await()
                    } catch (_: UnavailableException) { return@launch }
                }
            }.also { polling = it }
        }
        task.start()
    }

    // Creation is inert under the lock. The caller starts the coroutine after releasing it.
    private fun createScanLocked(): Deferred<Observation> {
        val expectedEpoch = registrationEpoch
        val prior = baseline
        return observerScope.async(start = CoroutineStart.LAZY) {
            try {
                val completed = poller.scan(prior) { difference ->
                    invalidate(InvalidationKind.DIFFERENCE, "Svelte filesystem changed", difference, allowDrain = true)
                }
                val differences = if (prior == null) emptyList() else (prior.entries.keys + completed.entries.keys).sorted().mapNotNull { path ->
                    val before = prior.entries[path]
                    val after = completed.entries[path]
                    if (before == after) null else LspSvelteFilePoller.Difference(path, before, after)
                }
                if (differences.isNotEmpty()) {
                    val drain = synchronized(lock) {
                        if (!active || !lifetime.isActive || !drainAllowed || registrationEpoch != expectedEpoch) null
                        else Triple(registrations.toMap(), invalidationEpoch, registrationEpoch)
                    }
                    if (drain == null && synchronized(lock) { active && drainAllowed }) {
                        failCoverage("Svelte registration changed during a filesystem difference")
                    }
                    if (drain != null) {
                        val mask = drain.first.values.fold(0) { acc, registration -> acc or registration.mask }
                        val events = differences.filter { it.watched && mask and eventMask(it.eventType) != 0 }.map { difference ->
                            val uri = toUri(File(completed.canonicalRoot, difference.relativePath))
                                ?: throw LspSvelteFilePoller.CoverageException("Svelte watched file has no exact mapped URI")
                            WatchedEvent(uri, difference.eventType)
                        }
                        val token = EventDrain(ownerKey, drain.third, drain.second,
                            Collections.unmodifiableSet(drain.first.keys.toSet()), Collections.unmodifiableList(events), completed,
                            prior?.criticalDigest != completed.criticalDigest)
                        synchronized(lock) {
                            if (active && lifetime.isActive && drainAllowed && registrationEpoch == token.registrationEpoch &&
                                invalidationEpoch == token.invalidationEpoch) currentDrain = token
                        }
                        if (isDrainCurrent(token)) onEvents(token)
                    }
                    throw UnavailableException("Svelte changed owner must retire")
                }
                val observation = synchronized(lock) {
                    checkAvailable()
                    // Every registration has exactly the same root/glob. A complete no-change pass can establish the
                    // latest subscription epoch without weakening its filesystem coverage.
                    baseline = completed
                    Observation(ownerKey, registrationEpoch, ++generation, completed).also { current = it }
                }
                // Never call the owner while holding the observer lock. A completed pass replaces freshness, but
                // must not retag an older diagnostic report.
                onObservation(observation)
                observation
            } catch (failure: CancellationException) {
                if (lifetime.isActive) failCoverage("Svelte observation cancelled")
                throw failure
            } catch (failure: Exception) {
                if (failure !is UnavailableException || synchronized(lock) { !invalidated }) {
                    failCoverage(failure.message ?: "Svelte observation failed")
                }
                throw UnavailableException(failure.message ?: "Svelte observation unavailable")
            }
        }
    }

    private fun failCoverage(reason: String) {
        invalidate(InvalidationKind.COVERAGE_FAILURE, reason, allowDrain = false)
    }

    private fun rejectRegistration(reason: String): RegistrationResult.Rejected {
        invalidate(InvalidationKind.REGISTRATION_FAILURE, reason, allowDrain = false)
        return synchronized(lock) { RegistrationResult.Rejected(reason, ownerKey, registrationEpoch, invalidationEpoch).also { lastRegistrationResult = it } }
    }

    private fun invalidate(
        kind: InvalidationKind,
        reason: String,
        difference: LspSvelteFilePoller.Difference? = null,
        allowDrain: Boolean,
    ) {
        val notification = synchronized(lock) {
            if (!active || !lifetime.isActive || (invalidated && (allowDrain || !drainAllowed))) null else {
                invalidated = true
                drainAllowed = allowDrain
                current = null
                currentDrain = null
                lastRegistrationResult = null
                Invalidation(ownerKey, ++invalidationEpoch, kind, reason, difference)
            }
        }
        // Never call the manager under lock: its lifetime lock may call our cheap token checks.

        if (notification != null && synchronized(lock) { active && lifetime.isActive && notification.epoch == invalidationEpoch }) {
            onInvalidated(notification)
            if (!allowDrain) firstRegistration.completeExceptionally(UnavailableException(reason))
        }
    }

    companion object {
        const val METHOD = "workspace/didChangeWatchedFiles"
        const val MAX_REGISTRATIONS = 8
        private fun eventMask(type: Int): Int = when (type) { 1 -> 1; 2 -> 2; 3 -> 4; else -> 0 }
        private fun exactString(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun validId(value: JsonElement?): String? = exactString(value)?.takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) }
        private fun parseRegistration(params: JsonElement?): List<Registration>? {
            val outer = params as? JsonObject ?: return null
            if (outer.keys != setOf("registrations")) return null
            val batch = outer["registrations"] as? JsonArray ?: return null
            if (batch.size !in 1..MAX_REGISTRATIONS) return null
            val results = ArrayList<Registration>()
            for (element in batch) {
                val item = element as? JsonObject ?: return null
                if (item.keys != setOf("id", "method", "registerOptions")) return null
                val id = validId(item["id"]) ?: return null
                if (exactString(item["method"]) != METHOD) return null
                val options = item["registerOptions"] as? JsonObject ?: return null
                if (options.keys != setOf("watchers")) return null
                val watchers = options["watchers"] as? JsonArray ?: return null
                if (watchers.size !in 1..8) return null
                var combined = 0
                val seenMasks = HashSet<Int>()
                for (watcher in watchers) {
                    val shape = watcher as? JsonObject ?: return null
                    if (shape.keys != setOf("globPattern") && shape.keys != setOf("globPattern", "kind")) return null
                    if (exactString(shape["globPattern"]) != LspSvelteFilePoller.GLOB) return null
                    val kind = if ("kind" !in shape) 7 else {
                        val value = shape["kind"] as? JsonPrimitive ?: return null
                        if (value.isString || !value.content.matches(Regex("[1-7]"))) return null
                        value.intOrNull ?: return null
                    }
                    if (!seenMasks.add(kind)) return null
                    combined = combined or kind
                }
                if (results.any { it.id == id }) return null
                results.add(Registration(id, combined))
            }
            return results
        }
        private fun parseUnregistration(params: JsonElement?): List<String>? {
            val outer = params as? JsonObject ?: return null
            if (outer.keys != setOf("unregisterations")) return null
            val batch = outer["unregisterations"] as? JsonArray ?: return null
            if (batch.size !in 1..MAX_REGISTRATIONS) return null
            val ids = ArrayList<String>()
            for (element in batch) {
                val item = element as? JsonObject ?: return null
                if (item.keys != setOf("id", "method") || exactString(item["method"]) != METHOD) return null
                val id = validId(item["id"]) ?: return null
                if (id in ids) return null
                ids.add(id)
            }
            return ids
        }
    }
}
