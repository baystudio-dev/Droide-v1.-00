package com.baystudio.droide.core

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ManagedPackageRecord(
    val familyId: String,
    val version: String,
    val scope: String,
    val installRoot: String,
    val installedAtEpochMs: Long,
    val active: Boolean = true,
    val pathEntries: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val commands: Map<String, String> = emptyMap(),
    val dependencies: List<String> = emptyList(),
    val healthChecks: List<ManagedPackageHealthCheck> = emptyList(),
    val artifactSha256: String? = null,
    val checksumFile: String = "SHA256SUMS",
    val abi: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)


internal object ManagedPackageMutationGate {
    val mutex = Mutex()
}

/** Vue-only final admission. No action here may acquire a lifecycle/preference/registry lock.
 * Writers enter after their existing mutation lock and retain this monitor through publication.
 */
internal object VueGenerationAdmission {
    private val monitor = Any()
    fun <T> withAdmission(action: () -> T): T = synchronized(monitor) { action() }
}

/** C# admission monitor is entered after existing mutation/registry locks and Vue admission.
 * Its callbacks must not acquire registry, preference, lifecycle or Vue admission locks. */
internal object CSharpGenerationAdmission {
    private val monitor = Any()
    fun <T> withAdmission(action: () -> T): T = synchronized(monitor) { action() }
}

/** TOML final admission is innermost after existing mutation, preference/registry and older-family gates.
 * Actions must not acquire any of those outer locks or call lifecycle/user callbacks. */
internal object TomlGenerationAdmission {
    private val monitor = Any()
    fun <T> withAdmission(action: () -> T): T = synchronized(monitor) { action() }
}

/** GraphQL final creation gate, innermost after all existing mutation/registry/preference gates.
 * Callbacks may not acquire outer locks or invoke lifecycle/teardown callbacks. Fencing is atomic. */
internal object GraphqlGenerationAdmission {
    private val monitor = Any()
    fun <T> withAdmission(action: () -> T): T = synchronized(monitor) { action() }
}

/** ELP is innermost after all older-family publication gates. No IO, probes or lifecycle callbacks. */
internal object ElpGenerationAdmission {
    private val monitor = Any()
    fun <T> withAdmission(action: () -> T): T = synchronized(monitor) { action() }
}

// Persisted state remains the source of truth.


class ManagedPackageRegistry(context: Context) {
    private val elpAppRoot = context.applicationContext.filesDir.canonicalPath
    private val prefs = context.applicationContext.getSharedPreferences("droide_managed_packages", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = false }

    @Synchronized
    fun list(): List<ManagedPackageRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ManagedPackageRecord>>(raw) }
            .getOrElse { failure -> throw IllegalStateException("Managed package registry is corrupt", failure) }
    }

    @Synchronized
    fun find(familyId: String, version: String): ManagedPackageRecord? =
        list().firstOrNull { it.familyId == familyId && it.version == version }

    @Synchronized
    fun put(record: ManagedPackageRecord) {
        validateRecord(record)
        val existing = list().filterNot { it.familyId == record.familyId && it.version == record.version }
        val normalized = if (record.active) existing.map { if (it.familyId == record.familyId) it.copy(active = false) else it } else existing
        replaceAll(normalized + record)
    }

    @Synchronized
    fun replaceAll(records: List<ManagedPackageRecord>) = replaceAllReviewed(records,null)

    @Synchronized
    internal fun replaceAllElp(records:List<ManagedPackageRecord>,installer:ElpInstallerClaim) = replaceAllReviewed(records,installer)

    private fun replaceAllReviewed(records:List<ManagedPackageRecord>,installer:ElpInstallerClaim?) {
        require(records.size <= 512) { "Too many managed package records" }
        records.forEach(::validateRecord)
        require(records.map { it.familyId to it.version }.distinct().size == records.size) { "Duplicate managed package record" }
        publishElpAware(records,installer) {
            check(prefs.edit().putString(KEY_RECORDS, json.encodeToString(records)).commit()) { "Failed to durably persist managed package registry" }
        }
    }

    /** Process-wide Vue token; read-only acceptance never reads preferences or acquires a lock. */
    internal fun vuePackageGeneration(): Any = vueGeneration.get()
    internal fun isVuePackageGenerationCurrent(token: Any): Boolean = vueGeneration.get() === token
    internal fun csharpPackageGeneration(): Any = csharpGeneration.get()
    internal fun isCSharpPackageGenerationCurrent(token: Any): Boolean = csharpGeneration.get() === token
    internal fun tomlPackageGeneration(): Any = tomlGeneration.get()
    internal fun isTomlPackageGenerationCurrent(token: Any): Boolean = tomlGeneration.get() === token
    internal fun graphqlPackageGeneration(): Any = graphqlGeneration.get()
    internal fun isGraphqlPackageGenerationCurrent(token: Any): Boolean = graphqlGeneration.get() === token
    internal fun elpPackageGeneration(): Any = elpGeneration.get()
    internal fun isElpPackageGenerationCurrent(token: Any): Boolean = elpGeneration.get() === token
    private fun publishElpAware(records: List<ManagedPackageRecord>, installer:ElpInstallerClaim?=null, publish: () -> Unit) = publishGraphqlAware(records) {
        ElpGenerationAdmission.withAdmission {
            val raw = prefs.getString(KEY_RECORDS,null)
            val current = if(raw==null) emptyList() else json.decodeFromString<List<ManagedPackageRecord>>(raw)
            val changed = current.filter { it.familyId==LocalUbuntuElpPolicy.FAMILY } != records.filter { it.familyId==LocalUbuntuElpPolicy.FAMILY }
            if(changed) LocalUbuntuElpLaunchAuthority.requireNoActivity(elpAppRoot,installer)
            if(changed) elpGeneration.set(Any())
            try { publish() } finally { if(changed) elpGeneration.set(Any()) }
        }
    }
    private fun publishGraphqlAware(records: List<ManagedPackageRecord>, publish: () -> Unit) = publishTomlAware(records) {
        GraphqlGenerationAdmission.withAdmission {
            fun relevant(values: List<ManagedPackageRecord>) = values.filter {
                it.familyId in setOf("lsp.graphql-library", "runtime.graphql-node")
            }.sortedWith(compareBy({ it.familyId }, { it.version }))
            val raw = prefs.getString(KEY_RECORDS, null)
            val current = if (raw == null) emptyList() else json.decodeFromString<List<ManagedPackageRecord>>(raw)
            val changed = relevant(current) != relevant(records)
            if (changed) graphqlGeneration.set(Any())
            try { publish() } finally { if (changed) graphqlGeneration.set(Any()) }
        }
    }
    private fun publishTomlAware(records: List<ManagedPackageRecord>, publish: () -> Unit) = publishCSharpAware(records) {
        TomlGenerationAdmission.withAdmission {
            fun relevant(values: List<ManagedPackageRecord>) = values.filter {
                it.familyId in setOf("lsp.taplo-wasm", "runtime.taplo-node")
            }.sortedWith(compareBy({ it.familyId }, { it.version }))
            val raw = prefs.getString(KEY_RECORDS, null)
            val current = if (raw == null) emptyList() else json.decodeFromString<List<ManagedPackageRecord>>(raw)
            val changed = relevant(current) != relevant(records)
            if (changed) tomlGeneration.set(Any())
            try { publish() } finally { if (changed) tomlGeneration.set(Any()) }
        }
    }
    private fun publishCSharpAware(records: List<ManagedPackageRecord>, publish: () -> Unit) = publishVueAware(records) {
        CSharpGenerationAdmission.withAdmission {
            fun relevant(values: List<ManagedPackageRecord>) = values.filter {
                it.familyId in setOf("lsp.csharp-ls", "runtime.csharp-sdk", "runtime.csharp-helper")
            }.sortedWith(compareBy({ it.familyId }, { it.version }))
            val raw = prefs.getString(KEY_RECORDS, null)
            val current = if (raw == null) emptyList() else json.decodeFromString<List<ManagedPackageRecord>>(raw)
            val changed = relevant(current) != relevant(records)
            if (changed) csharpGeneration.set(Any())
            try { publish() } finally { if (changed) csharpGeneration.set(Any()) }
        }
    }
    private fun publishVueAware(records: List<ManagedPackageRecord>, publish: () -> Unit) = VueGenerationAdmission.withAdmission {
        fun relevant(values: List<ManagedPackageRecord>) = values.filter { it.familyId in setOf("lsp.vue", "runtime.vue-helper") }.sortedWith(compareBy({ it.familyId }, { it.version }))
        // Read this instance's storage directly: this method already owns its registry monitor.
        // Do not acquire another registry monitor from the Vue admission monitor.
        val raw = prefs.getString(KEY_RECORDS, null)
        val current = if (raw == null) emptyList() else json.decodeFromString<List<ManagedPackageRecord>>(raw)
        val changed = relevant(current) != relevant(records)
        if (changed) vueGeneration.set(Any())
        try { publish() } finally {
            // A cross-instance proof can see the pre-publication token with old storage.
            // Revoke that token as well, including on failed/uncertain persistence.
            if (changed) vueGeneration.set(Any())
        }
    }

    private fun validateRecord(record: ManagedPackageRecord) {
        require(record.familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
        require(record.version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid package version" }
        require(record.scope in ExecutionScope.entries.map { it.name }) { "Invalid package scope" }
        require(record.installRoot.length in 1..400) { "Invalid package install root" }
        require(record.pathEntries.size <= 64) { "Too many package PATH entries" }
        require(record.environment.size <= 32) { "Too many package environment variables" }
        require(record.environment.keys.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) }) { "Invalid package environment key" }
        require(record.commands.size <= 128) { "Too many package commands" }
        require(record.commands.keys.all { it.matches(Regex("[A-Za-z0-9._+-]{1,80}")) }) { "Invalid package command name" }
        require(record.dependencies.size <= 32) { "Too many package dependencies" }
        require(record.dependencies.all { it.matches(Regex("[A-Za-z0-9._-]{1,120}@[A-Za-z0-9._+ -]{1,80}")) }) { "Invalid package dependency" }
        require(record.healthChecks.size <= 32) { "Too many package health checks" }
        require(record.artifactSha256 == null || record.artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid package artifact SHA-256" }
        require(record.checksumFile.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid package checksum filename" }
        require(record.abi == null || record.abi in AndroidToolchainCatalogEntry.SUPPORTED_ABIS) { "Invalid package ABI" }
        require(record.metadata.size <= 16) { "Too many package metadata entries" }
        require(record.metadata.keys.all { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }) { "Invalid package metadata key" }
        require(record.metadata.values.all { it.length <= 4096 && '\u0000' !in it && '\n' !in it && '\r' !in it }) { "Invalid package metadata value" }
        require(record.healthChecks.all { check ->
            val path = check.executable.replace('\\', '/')
            path.length in 1..300 && !path.startsWith('/') &&
                path.split('/').none { it.isBlank() || it == "." || it == ".." } &&
                check.args.size <= 16 && check.args.all { it.length <= 200 && '\u0000' !in it && '\n' !in it && '\r' !in it }
        }) { "Invalid package health check" }
    }

    @Synchronized
    fun remove(familyId: String, version: String) {
        val next = list().filterNot { it.familyId == familyId && it.version == version }
        publishElpAware(next) {
            check(prefs.edit().putString(KEY_RECORDS, json.encodeToString(next)).commit()) { "Failed to durably persist managed package registry" }
        }
    }

    companion object {
        private val elpGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        private val graphqlGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        private val tomlGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        private val csharpGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        private val vueGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        private const val KEY_RECORDS = "records_json"
    }
}
