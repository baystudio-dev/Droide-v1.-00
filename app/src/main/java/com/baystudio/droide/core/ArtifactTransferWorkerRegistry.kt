package com.baystudio.droide.core

import java.util.UUID
import kotlinx.coroutines.Job

/** Worker generations outlive cancellation until their finally blocks have run. */
internal class ArtifactTransferWorkerRegistry<K> {
    internal data class Worker(val requestIdentity: String?, val identity: String, val job: Job)
    private val lock = Any()
    private val workers = mutableMapOf<K, Worker>()

    fun start(key: K, requestIdentity: String?, create: (String) -> Job): Worker? = synchronized(lock) {
        val previous = workers[key]
        if (previous != null && previous.requestIdentity == requestIdentity &&
            !previous.job.isCancelled && !previous.job.isCompleted) return@synchronized null
        val identity = UUID.randomUUID().toString().replace("-", "")
        val worker = Worker(requestIdentity, identity, create(identity))
        workers[key] = worker
        previous?.job?.cancel()
        worker
    }

    fun finish(key: K, identity: String): Boolean = synchronized(lock) {
        if (workers[key]?.identity != identity) return@synchronized false
        workers.remove(key)
        true
    }

    fun <T> withCurrent(key: K, identity: String, block: () -> T): T? = synchronized(lock) {
        if (workers[key]?.identity != identity) return@synchronized null
        block()
    }

    fun cancel(key: K, requestIdentity: String?): Worker? = synchronized(lock) {
        val worker = workers[key]?.takeIf { it.requestIdentity == requestIdentity } ?: return@synchronized null
        workers.remove(key)
        worker.job.cancel()
        worker
    }

    fun clear(): List<Pair<K, Worker>> = synchronized(lock) {
        val current = workers.entries.map { it.key to it.value }
        workers.clear()
        current.forEach { it.second.job.cancel() }
        current
    }

    fun keys(): List<K> = synchronized(lock) { workers.keys.toList() }
    fun size(): Int = synchronized(lock) { workers.size }
}
