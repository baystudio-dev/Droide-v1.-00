package com.baystudio.droide.core

import java.net.URI
import kotlinx.serialization.json.*

/** Fixed LemMinX 0.31.2 client policy; remote grammar access requires project consent. */
internal object LspLemMinxProfile {
    val extensions = setOf("xml", "xsd", "xsl", "xslt", "svg", "rng", "dtd")
    const val MAX_CONFIGURATION_ITEMS = 64
    const val MAX_SCOPE_URI_LENGTH = 8_192

    fun settings(): JsonObject = buildJsonObject {
        put("useCache", true)
        put("downloadExternalResources", buildJsonObject { put("enabled", true) })
        put("validation", buildJsonObject {
            put("enabled", true)
            put("schema", buildJsonObject { put("enabled", "always") })
            put("resolveExternalEntities", false)
            put("disallowDocTypeDecl", false)
            put("xInclude", buildJsonObject { put("enabled", false) })
            put("noGrammar", "hint")
            put("filters", buildJsonArray {})
        })
        put("catalogs", buildJsonArray {})
        put("fileAssociations", buildJsonArray {})
        put("format", buildJsonObject { put("enabled", true); put("insertSpaces", true); put("tabSize", 2) })
        put("completion", buildJsonObject { put("autoCloseTags", true) })
        put("codeLens", buildJsonObject { put("enabled", false) })
        put("telemetry", buildJsonObject { put("enabled", false) })
        put("logs", buildJsonObject { put("client", false) })
    }

    fun configuration(): JsonObject = buildJsonObject {
        put("settings", buildJsonObject { put("xml", settings()) })
    }

    fun initializationOptions(): JsonObject = buildJsonObject {
        configuration().forEach { (key, value) -> put(key, value) }
        put("extendedClientCapabilities", buildJsonObject {
            put("actionableNotificationSupport", false)
            put("openSettingsCommandSupport", false)
            put("bindingWizardSupport", false)
            put("shouldLanguageServerExitOnShutdown", false)
        })
    }

    /** No arbitrary settings lookup, absent-scope fallback or permission side effect. */
    fun configurationReply(params: JsonElement?, ownsScope: (String) -> Boolean): JsonArray {
        val request = params as? JsonObject ?: error("XML configuration parameters must be an object")
        val items = request["items"] as? JsonArray ?: error("XML configuration items must be an array")
        require(items.size <= MAX_CONFIGURATION_ITEMS) { "XML configuration request exceeds its item limit" }
        // Validate the entire request before consulting live ownership; never truncate.
        val entries = items.map { item ->
            val entry = item as? JsonObject ?: error("XML configuration item must be an object")
            fun optionalString(key: String): String? {
                val value = entry[key] ?: return null
                if (value == JsonNull) return null
                require(value is JsonPrimitive && value.isString) { "XML configuration $key must be a string" }
                return value.content
            }
            optionalString("section") to optionalString("scopeUri")
        }
        return buildJsonArray {
            entries.forEach { (section, scope) ->
                val known = section == "xml.format.insertSpaces" || section == "xml.format.tabSize"
                val uri = scope?.takeIf { it.length <= MAX_SCOPE_URI_LENGTH }?.let { runCatching { URI(it) }.getOrNull() }
                val admitted = known && uri != null && uri.scheme == "file" &&
                    uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
                    !uri.isOpaque && runCatching { ownsScope(requireNotNull(scope)) }.getOrDefault(false)
                add(if (!admitted) JsonNull else if (section == "xml.format.insertSpaces") JsonPrimitive(true) else JsonPrimitive(2))
            }
        }
    }
}
