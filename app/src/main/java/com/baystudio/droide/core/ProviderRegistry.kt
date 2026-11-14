package com.baystudio.droide.core

import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


enum class ProviderWireProtocol { OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GOOGLE_GENERATE_CONTENT }

// compatibility marker: ProviderAuthScheme { NONE, BEARER, ANTHROPICXAPIKEY, GOOGLEAPIKEY, AZUREAPIKEY }
enum class ProviderAuthScheme {
    NONE,
    BEARER,
    ANTHROPIC_X_API_KEY,
    GOOGLE_API_KEY,
    AZURE_API_KEY,
    AWS_SIGV4_BEDROCK,
    GOOGLE_ADC,
    AZURE_ENTRA,
    GITHUB_COPILOT,
}

enum class ProviderNetworkScope { PUBLIC_HTTPS, LOOPBACK_ONLY }

data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val free: Boolean,
    val needsKey: Boolean,
    val helpUrl: String,
    val note: String,
    val wireProtocol: ProviderWireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
    val authScheme: ProviderAuthScheme = ProviderAuthScheme.BEARER,
    val catalogPackage: String? = null,
    val catalogEnv: List<String> = emptyList(),
    val catalogDiscovered: Boolean = false,
    val networkScope: ProviderNetworkScope = ProviderNetworkScope.PUBLIC_HTTPS,
    val configuredModels: List<String> = emptyList(),
    val authRegion: String? = null,
    /** Null keeps Droide's existing tolerance; positive override is an event-idle timeout, not a turn deadline. */
    val streamIdleTimeoutMs: Long? = null,
) {
    init { ProviderStreamPolicy.validateIdleTimeout(streamIdleTimeoutMs) }
}

object ProviderRegistry {
    /** Validated and detached from caller-owned collections before workspace publication. */
    class ConfiguredSnapshot internal constructor(internal val providers: List<AiProvider>)

    private val discovered = AtomicReference<List<AiProvider>>(emptyList())
    private val configured = AtomicReference<List<AiProvider>>(emptyList())
    private val managedRuntime = AtomicReference<List<AiProvider>>(emptyList())
    private data class UserSnapshot(val definitions: List<UserProviderDefinition>, val providers: List<AiProvider>)
    private val user = AtomicReference(UserSnapshot(emptyList(), emptyList()))
    private val _userRevision = MutableStateFlow(0L)
    val userRevision = _userRevision.asStateFlow()
    private val bootstrap = listOf(
        AiProvider("pollinations", "Pollinations", "https://gen.pollinations.ai/v1", "", true, true,
            "https://enter.pollinations.ai", "OpenAI-compatible API. Generation requests require a Pollinations API key."),
        AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "", false, true,
            "https://platform.openai.com/api-keys", "OpenAI API."),
        AiProvider("meta", "Meta", "https://api.meta.ai/v1", "", false, true,
            "https://dev.meta.ai/docs", "Meta Model API · Muse models. Requires a Meta API key.",
            wireProtocol = ProviderWireProtocol.OPENAI_RESPONSES),
        AiProvider("anthropic", "Anthropic", "https://api.anthropic.com/v1", "", false, true,
            "https://console.anthropic.com/settings/keys", "Native Anthropic Messages API.",
            wireProtocol = ProviderWireProtocol.ANTHROPIC_MESSAGES,
            authScheme = ProviderAuthScheme.ANTHROPIC_X_API_KEY),
        AiProvider("groq", "Groq", "https://api.groq.com/openai/v1", "", true, true,
            "https://console.groq.com/keys", "OpenAI-compatible API."),
        AiProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "", true, true,
            "https://aistudio.google.com/app/apikey", "Native Google GenerateContent API.",
            wireProtocol = ProviderWireProtocol.GOOGLE_GENERATE_CONTENT,
            authScheme = ProviderAuthScheme.GOOGLE_API_KEY),
        AiProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "", true, true,
            "https://openrouter.ai/keys", "OpenAI-compatible multi-model routing."),
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com", "", false, true,
            "https://platform.deepseek.com/api_keys", "OpenAI-compatible chat API."),
        AiProvider("together", "Together AI", "https://api.together.xyz/v1", "", false, true,
            "https://api.together.ai/settings/api-keys", "OpenAI-compatible endpoint."),
        AiProvider("fireworks", "Fireworks AI", "https://api.fireworks.ai/inference/v1", "", false, true,
            "https://app.fireworks.ai", "OpenAI-compatible endpoint."),
        AiProvider("deepinfra", "DeepInfra", "https://api.deepinfra.com/v1/openai", "", false, true,
            "https://deepinfra.com/dash/api_keys", "OpenAI-compatible endpoint."),
        AiProvider("huggingface", "Hugging Face Inference Providers", "https://router.huggingface.co/v1", "", false, true,
            "https://huggingface.co/settings/tokens", "OpenAI-compatible chat endpoint via HF router."),
        AiProvider("nvidia", "NVIDIA API Catalog", "https://integrate.api.nvidia.com/v1", "", false, true,
            "https://build.nvidia.com", "OpenAI-compatible NVIDIA endpoint."),
    )

    val all: List<AiProvider>
        get() {
            val runtime = configured.get()
            val managed = managedRuntime.get()
            val dynamic = discovered.get()
            val users = user.get().providers
            if (runtime.isEmpty() && managed.isEmpty() && dynamic.isEmpty() && users.isEmpty()) return bootstrap
            val reserved = bootstrap.asSequence().map { it.id.lowercase() }.toMutableSet()
            val safeUser = users.filter { reserved.add(it.id) }
            val safeRuntime = runtime.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            val safeManaged = managed.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            val safeDynamic = dynamic.asSequence().filter { reserved.add(it.id.lowercase()) }.toList()
            return bootstrap + safeUser + safeRuntime + safeManaged + safeDynamic.sortedBy { it.name.lowercase() }
        }

    internal fun userDefinition(id: String): UserProviderDefinition? = user.get().definitions.find { it.id == id }

    internal fun installUserConfigured(definitions: List<UserProviderDefinition>) {
        val safe = definitions.map { definition ->
            definition.normalized().let { it.copy(models = Collections.unmodifiableList(ArrayList(it.models))) }
        }
        val providers = safe.map { it.provider() }
        user.set(UserSnapshot(Collections.unmodifiableList(safe), Collections.unmodifiableList(providers)))
        _userRevision.update { if (it == Long.MAX_VALUE) 0L else it + 1L }
    }

    internal fun installDiscovered(providers: Collection<AiProvider>) {
        val bootstrapIds = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        val safe = providers.asSequence()
            .map { it.copy(id = it.id.trim().lowercase()) }
            .filter {
                it.catalogDiscovered && it.id.isNotBlank() && !it.id.startsWith("user.") && it.id !in bootstrapIds &&
                    it.baseUrl.startsWith("https://")
            }
            .distinctBy { it.id }
            .toList()
        discovered.set(safe)
    }


    internal fun prepareConfigured(providers: Collection<AiProvider>): ConfiguredSnapshot {
        val bootstrapIds = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        val safe = providers.asSequence()
            .map {
                it.copy(
                    id = it.id.trim().lowercase(),
                    catalogEnv = Collections.unmodifiableList(ArrayList(it.catalogEnv)),
                    configuredModels = Collections.unmodifiableList(ArrayList(it.configuredModels)),
                )
            }
            .filter { provider ->
                provider.id.matches(Regex("[a-z0-9._-]{1,96}")) && !provider.id.startsWith("user.") && provider.id !in bootstrapIds &&
                    runCatching {
                        val uri = URI(provider.baseUrl)
                        when (provider.networkScope) {
                            ProviderNetworkScope.PUBLIC_HTTPS -> uri.scheme.equals("https", true)
                            ProviderNetworkScope.LOOPBACK_ONLY -> uri.scheme.equals("http", true) || uri.scheme.equals("https", true)
                        }
                    }.getOrDefault(false)
            }
            .distinctBy { it.id }
            .toList()
        return ConfiguredSnapshot(Collections.unmodifiableList(safe))
    }

    /** Publication must stay assignment-only: preparation may not change the active workspace. */
    internal fun publishConfigured(snapshot: ConfiguredSnapshot) {
        configured.set(snapshot.providers)
    }

    internal fun installConfigured(providers: Collection<AiProvider>) {
        publishConfigured(prepareConfigured(providers))
    }


    internal fun installManagedRuntime(providers: Collection<AiProvider>) {
        val reserved = bootstrap.asSequence().map { it.id.lowercase() }.toHashSet()
        reserved += configured.get().map { it.id.lowercase() }
        val safe = providers.asSequence()
            .map { it.copy(id = it.id.trim().lowercase()) }
            .filter { provider ->
                provider.id.matches(Regex("local\\.[a-z0-9._-]{1,89}")) && provider.id !in reserved &&
                    provider.networkScope == ProviderNetworkScope.LOOPBACK_ONLY &&
                    provider.wireProtocol == ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS &&
                    runCatching {
                        val uri = URI(provider.baseUrl)
                        val host = uri.host.orEmpty()
                        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                            (host == "127.0.0.1" || host.equals("localhost", true) || host == "::1" || host == "[::1]") &&
                            uri.userInfo == null && uri.query == null && uri.fragment == null
                    }.getOrDefault(false)
            }
            .distinctBy { it.id }
            .toList()
        val replacing = safe.map { it.id }.toSet()
        while (true) {
            val current = managedRuntime.get()
            val next = (current.filterNot { it.id in replacing } + safe).distinctBy { it.id }
            if (managedRuntime.compareAndSet(current, next)) break
        }
    }

    internal fun removeManagedRuntime(providerId: String): Boolean {
        val id = providerId.trim().lowercase()
        while (true) {
            val current = managedRuntime.get()
            if (current.none { it.id == id }) return false
            val next = current.filterNot { it.id == id }
            if (managedRuntime.compareAndSet(current, next)) return true
        }
    }

    internal fun isBootstrapProvider(id: String): Boolean = bootstrap.any { it.id.equals(id, ignoreCase = true) }

    fun findById(id: String): AiProvider? = all.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

    /** Runtime-owned work must never fall through to another project's configured entries. */
    internal fun findById(id: String, snapshot: ConfiguredSnapshot?): AiProvider? {
        if (snapshot == null) return findById(id)
        val normalized = id.trim()
        fun List<AiProvider>.find() = firstOrNull { it.id.equals(normalized, ignoreCase = true) }
        return bootstrap.find() ?: user.get().providers.find() ?: snapshot.providers.find() ?: managedRuntime.get().find() ?: discovered.get().find()
    }

    internal fun findByCatalogId(id: String): AiProvider? = findById(id) ?: when (id.trim().lowercase()) {
        "google" -> findById("gemini")
        "togetherai" -> findById("together")
        "fireworks-ai" -> findById("fireworks")
        else -> null
    }

    fun byId(id: String): AiProvider = findById(id) ?: bootstrap.first()
}
