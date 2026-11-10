package com.baystudio.droide.core

import android.content.Context
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class WorkspaceToolchainPreferences(
    context: Context,
    projectRoot: File,
    // Execute the entire mutation under its owner's lifecycle admission, not a check-then-write.
    private val admitMutation: (() -> Unit) -> Unit = { mutation -> mutation() },
) {
    private val prefs = context.applicationContext.getSharedPreferences("droide_workspace_toolchains", Context.MODE_PRIVATE)
    val workspaceId = AndroidDevelopmentManager.stableProjectId(projectRoot)
    private val workspaceKey = "workspace_" + workspaceId
    private val json = Json { ignoreUnknownKeys = false }
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun selections(): Map<String, String> = synchronized(preferenceLock) { readSelections() }

    private fun readSelections(): Map<String, String> {
        val raw = prefs.getString(workspaceKey, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyMap())
            .filter { (family, version) ->
                family.matches(Regex("[A-Za-z0-9._-]{1,120}")) &&
                    version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))
            }
            .toList()
            .take(64)
            .toMap()
    }

    /** Called only inside the owner's lifecycle admission; never acquire that admission from this action. */
    internal fun <T> withUnchangedSelections(expected: Map<String, String>, action: () -> T): T = synchronized(preferenceLock) {
        check(readSelections() == expected) { "Workspace toolchain selections changed before process creation" }
        action()
    }

    /** Vue-only pure token. Unrelated families and no-op mutations preserve it. */
    internal fun vueSelectionGeneration(): Any = vueGeneration.get()
    internal fun isVueSelectionGenerationCurrent(token: Any): Boolean = vueGeneration.get() === token
    /** Token-only check under the existing preference lock; called inside lifecycle admission. */
    internal fun <T> withVueSelectionGeneration(expected: Any, action: () -> T): T = synchronized(preferenceLock) {
        check(isVueSelectionGenerationCurrent(expected)) { "Vue selections changed before process creation" }
        action()
    }
    /** C# package/SDK policy selections have a separate pure generation. */
    internal fun csharpSelectionGeneration(): Any = csharpGeneration.get()
    internal fun isCSharpSelectionGenerationCurrent(token: Any): Boolean = csharpGeneration.get() === token
    internal fun <T> withCSharpSelectionGeneration(expected: Any, action: () -> T): T = synchronized(preferenceLock) {
        check(isCSharpSelectionGenerationCurrent(expected)) { "C# selections changed before process creation" }
        action()
    }
    /** TOML-only pure token. Terminal Node and unrelated families are deliberately excluded. */
    internal fun tomlSelectionGeneration(): Any = tomlGeneration.get()
    internal fun isTomlSelectionGenerationCurrent(token: Any): Boolean = tomlGeneration.get() === token
    internal fun <T> withTomlSelectionGeneration(expected: Any, action: () -> T): T = synchronized(preferenceLock) {
        check(isTomlSelectionGenerationCurrent(expected)) { "TOML selections changed before process creation" }
        action()
    }
    internal fun graphqlSelectionGeneration(): Any = graphqlGeneration.get()
    internal fun isGraphqlSelectionGenerationCurrent(token: Any): Boolean = graphqlGeneration.get() === token
    internal fun <T> withGraphqlSelectionGeneration(expected: Any, action: () -> T): T = synchronized(preferenceLock) {
        check(isGraphqlSelectionGenerationCurrent(expected)) { "GraphQL selections changed before process creation" }
        action()
    }
    internal fun elpSelectionGeneration(): Any = elpGeneration.get()
    internal fun isElpSelectionGenerationCurrent(token: Any): Boolean = elpGeneration.get() === token
    internal fun <T> withElpSelectionGeneration(expected: Any, action: () -> T): T = synchronized(preferenceLock) {
        check(isElpSelectionGenerationCurrent(expected)) { "ELP selections changed before process creation" }; action()
    }
    private fun publishElpAware(next: Map<String,String>, publish: () -> Unit) = publishGraphqlAware(next) {
        ElpGenerationAdmission.withAdmission {
            val changed=readSelections()[LocalUbuntuElpPolicy.FAMILY]!=next[LocalUbuntuElpPolicy.FAMILY]
            if(changed) elpGeneration.set(Any())
            try { publish() } finally { if(changed) elpGeneration.set(Any()) }
        }
    }
    private fun publishGraphqlAware(next: Map<String, String>, publish: () -> Unit) = publishTomlAware(next) {
        GraphqlGenerationAdmission.withAdmission {
            val families = setOf("lsp.graphql-library", "runtime.graphql-node")
            val changed = readSelections().filterKeys { it in families } != next.filterKeys { it in families }
            if (changed) graphqlGeneration.set(Any())
            try { publish() } finally { if (changed) graphqlGeneration.set(Any()) }
        }
    }
    private fun publishTomlAware(next: Map<String, String>, publish: () -> Unit) = publishVueAware(next) {
        TomlGenerationAdmission.withAdmission {
            val families = setOf("lsp.taplo-wasm", "runtime.taplo-node")
            val changed = readSelections().filterKeys { it in families } != next.filterKeys { it in families }
            if (changed) tomlGeneration.set(Any())
            try { publish() } finally { if (changed) tomlGeneration.set(Any()) }
        }
    }
    private fun publishVueAware(next: Map<String, String>, publish: () -> Unit) = VueGenerationAdmission.withAdmission {
        CSharpGenerationAdmission.withAdmission {
            val before = readSelections()
            val families = setOf("lsp.vue", "runtime.vue-helper")
            val csharpFamilies = setOf("lsp.csharp-ls", "runtime.csharp-sdk", "runtime.csharp-helper", "runtime.dotnet", "toolchain.dotnet", "tool.dotnet", "dotnet")
            val changed = before.filterKeys { it in families } != next.filterKeys { it in families }
            val csharpChanged = before.filterKeys { it in csharpFamilies } != next.filterKeys { it in csharpFamilies }
            if (changed) vueGeneration.set(Any())
            if (csharpChanged) csharpGeneration.set(Any())
            try { publish() } finally {
                if (changed) vueGeneration.set(Any())
                if (csharpChanged) csharpGeneration.set(Any())
            }
        }
    }

    fun selectedVersion(familyId: String): String? = selections()[familyId]

    fun set(familyId: String, version: String) {
        require(familyId.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid package family" }
        require(version.matches(Regex("[A-Za-z0-9._+ -]{1,80}"))) { "Invalid package version" }
        mutate {
            val next = readSelections().toMutableMap().apply { put(familyId, version) }
            require(next.size <= 64) { "Too many workspace toolchain overrides" }
            publishElpAware(next) {
                prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
            }
        }
    }

    fun clear(familyId: String) {
        mutate {
            val next = readSelections().toMutableMap().apply { remove(familyId) }
            publishElpAware(next) {
                if (next.isEmpty()) prefs.edit().remove(workspaceKey).apply()
                else prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
            }
        }
    }

    fun prune(valid: Set<Pair<String, String>>) {
        mutate {
            val next = readSelections().filter { (family, version) -> (family to version) in valid }
            publishElpAware(next) {
                if (next.isEmpty()) prefs.edit().remove(workspaceKey).apply()
                else prefs.edit().putString(workspaceKey, json.encodeToString(serializer, next)).apply()
            }
        }
    }

    private fun mutate(block: () -> Unit) {
        // Lock order is always lifecycle admission -> preference RMW. A refresh already owns lifecycle
        // admission when it prunes; never acquire that gate while holding this lock.
        admitMutation { synchronized(preferenceLock) { block() } }
    }

    private companion object {
        // Hold one bounded, process-wide monitor across the whole RMW, including apply, for every
        // instance.
        val preferenceLock = Any()
        val elpGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        val graphqlGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        val tomlGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        val csharpGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
        val vueGeneration = java.util.concurrent.atomic.AtomicReference<Any>(Any())
    }
}
