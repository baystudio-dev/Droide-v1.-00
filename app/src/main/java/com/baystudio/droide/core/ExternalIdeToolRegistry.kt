package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The endpoint's workspace selects the provider. A client cannot select another project. */
internal object ExternalIdeToolRegistry {
    interface Provider {
        val definitions: JsonArray
        suspend fun call(name: String, arguments: JsonObject): String
        fun close() = Unit
    }

    data class Binding(val workspaceId: String, val token: String, val root: File)
    private class Entry(val binding: Binding, val provider: Provider) {
        val jobs = mutableSetOf<Job>()
    }
    private val entries = mutableMapOf<String, Entry>()

    fun bind(workspaceId: String, root: File, provider: Provider): Binding {
        require(workspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}")))
        val binding = Binding(workspaceId, UUID.randomUUID().toString(), root.canonicalFile)
        val previous = synchronized(entries) { entries.put(workspaceId, Entry(binding, provider)) }
        retire(previous)
        return binding
    }

    fun unbind(binding: Binding) = retire(binding).invoke()

    /** Remove admission under the owner's publication boundary; cancel work after leaving it. */
    fun retire(binding: Binding?): () -> Unit {
        val removed = synchronized(entries) {
            binding?.let { entries[it.workspaceId]?.takeIf { entry -> entry.binding == it }
                ?.also { _ -> entries.remove(it.workspaceId) } }
        }
        return { retire(removed) }
    }

    private fun retire(entry: Entry?) {
        if (entry == null) return
        val jobs = synchronized(entries) { entry.jobs.toList() }
        jobs.forEach { it.cancel(CancellationException("IDE tool workspace was closed or replaced")) }
        entry.provider.close()
    }

    fun binding(workspaceId: String?): Binding? = synchronized(entries) { entries[workspaceId]?.binding }
    fun forProject(root: File): Binding? {
        val canonical = root.canonicalFile
        return synchronized(entries) { entries.values.singleOrNull { it.binding.root == canonical }?.binding }
    }

    suspend fun <T> use(workspaceId: String?, token: String? = null, action: suspend (Provider) -> T): T =
        useBound(workspaceId, token, true, action)

    /** Installation has its own deadline; human review must not consume the observation timeout. */
    suspend fun call(workspaceId: String?, token: String, name: String, args: JsonObject): String =
        useBound(workspaceId, token, name != "droide_toolchain") { it.call(name, args) }

    private suspend fun <T> useBound(workspaceId: String?, token: String?, bounded: Boolean,
        action: suspend (Provider) -> T): T = coroutineScope {
        val epoch = ApplicationWorkAdmission.requireOpen()
        val operation = currentCoroutineContext().job
        val entry = synchronized(entries) {
            val current = checkNotNull(entries[workspaceId]) { "IDE tools are unavailable for this workspace" }
            check(token == null || token == current.binding.token) { "IDE tool connection expired; reconnect to the current workspace" }
            current.jobs.add(operation)
            current
        }
        fun requireCurrent() {
            operation.ensureActive()
            check(ApplicationWorkAdmission.isCurrentOpen(epoch) && synchronized(entries) { entries[workspaceId] === entry }) {
                "IDE tool workspace was closed or replaced"
            }
        }
        try {
            requireCurrent()
            (if (bounded) withTimeout(60_000) { action(entry.provider) } else action(entry.provider))
                .also { requireCurrent() }
        } finally { synchronized(entries) { entry.jobs.remove(operation) } }
    }
}
