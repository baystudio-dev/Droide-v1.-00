package com.baystudio.droide.core

import java.net.URI
import java.util.Collections
import kotlinx.serialization.json.*

/** User settings contain endpoint metadata only. Credentials belong to SecretStore. */
data class UserProviderDefinition(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderWireProtocol = ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,
    val requiresKey: Boolean = true,
    val models: List<String> = emptyList(),
) {
    fun normalized(): UserProviderDefinition {
        val key = id.trim().lowercase()
        require(key.matches(Regex("user\\.[a-z0-9][a-z0-9._-]{0,79}"))) {
            "Provider ID must use letters, numbers, dots, underscores or hyphens (up to 80 characters)."
        }
        val label = name.trim()
        require(label.length in 1..160 && label.none(Char::isISOControl)) { "Enter a provider name (1–160 characters)." }
        val url = baseUrl.trim().trimEnd('/')
        require(url.length in 1..2048) { "Enter an API base URL (up to 2048 characters)." }
        val uri = runCatching { URI(url) }.getOrElse { error("Enter a valid API base URL.") }
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Base URL must include a host and cannot contain credentials, a query or a fragment."
        }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid API port." }
        require(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && isLoopback(uri.host))) {
            "Use HTTPS for a remote provider, or HTTP for localhost."
        }
        require(!url.endsWith("/chat/completions") && !url.endsWith("/responses") && !url.endsWith("/messages")) {
            "Enter the API base URL, for example https://api.example.com/v1, without the request path."
        }
        require(models.size <= MAX_MODELS) { "Configure at most $MAX_MODELS model IDs." }
        return copy(id = key, name = label, baseUrl = url, models = models.map(ProviderModelIdentity::normalize).distinct())
    }

    internal fun provider(): AiProvider {
        val value = normalized()
        val local = isLoopback(URI(value.baseUrl).host)
        return AiProvider(
            id = value.id, name = value.name, baseUrl = value.baseUrl, model = "", free = local,
            needsKey = value.requiresKey, helpUrl = "", note = "Custom provider · ${value.protocol.label}",
            wireProtocol = value.protocol,
            authScheme = if (!value.requiresKey) ProviderAuthScheme.NONE else when (value.protocol) {
                ProviderWireProtocol.ANTHROPIC_MESSAGES -> ProviderAuthScheme.ANTHROPIC_X_API_KEY
                ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> ProviderAuthScheme.GOOGLE_API_KEY
                else -> ProviderAuthScheme.BEARER
            },
            networkScope = if (local) ProviderNetworkScope.LOOPBACK_ONLY else ProviderNetworkScope.PUBLIC_HTTPS,
            configuredModels = Collections.unmodifiableList(ArrayList(value.models)),
        )
    }

    companion object {
        const val MAX_MODELS = 16
        private fun isLoopback(host: String): Boolean = host.lowercase() in setOf(
            "localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]",
        )
    }
}

val ProviderWireProtocol.label: String get() = when (this) {
    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS -> "OpenAI Chat Completions"
    ProviderWireProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    ProviderWireProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT -> "Google GenerateContent"
}

/** A small, versioned, strict format prevents accidental persistence of keys or arbitrary headers. */
internal object UserProviderCodec {
    const val MAX_BYTES = 128 * 1024
    const val MAX_PROVIDERS = 32

    fun decode(raw: String): List<UserProviderDefinition> {
        require(raw.toByteArray().size <= MAX_BYTES) { "Custom provider settings are too large." }
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid custom provider settings.")
        require(root.keys == setOf("version", "providers") && root["version"] == JsonPrimitive(1)) { "Unsupported custom provider settings version." }
        val rows = root["providers"] as? JsonArray ?: error("Invalid provider list.")
        require(rows.size <= MAX_PROVIDERS) { "Too many custom providers." }
        val definitions = rows.map { row ->
            val obj = row as? JsonObject ?: error("Invalid provider definition.")
            require(obj.keys == setOf("id", "name", "baseUrl", "protocol", "requiresKey", "models")) { "Unknown custom provider field." }
            fun string(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid $key.")
            UserProviderDefinition(
                id = string("id"), name = string("name"), baseUrl = string("baseUrl"),
                protocol = ProviderWireProtocol.valueOf(string("protocol")),
                requiresKey = (obj["requiresKey"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("Invalid authentication setting."),
                models = (obj["models"] as? JsonArray ?: error("Invalid model list.")).map {
                    (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Model IDs must be strings.")
                },
            ).normalized()
        }
        require(definitions.map { it.id }.distinct().size == definitions.size) { "Duplicate custom provider ID." }
        return definitions
    }

    fun encode(values: List<UserProviderDefinition>): String = buildJsonObject {
        put("version", 1)
        putJsonArray("providers") {
            values.forEach { value -> add(buildJsonObject {
                put("id", value.id); put("name", value.name); put("baseUrl", value.baseUrl)
                put("protocol", value.protocol.name); put("requiresKey", value.requiresKey)
                put("models", JsonArray(value.models.map(::JsonPrimitive)))
            }) }
        }
    }.toString().also { decode(it) }
}
