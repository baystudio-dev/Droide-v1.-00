package com.baystudio.droide.core

import java.net.URI
import kotlinx.serialization.json.*

/** Exact original ELP profile. A route match is not package or runtime authority. */
internal object LspElpProfile {
    const val ID = "elp"
    const val COMMAND = "elp"
    const val IDENTITY = "elp-2026-10-14-b36ba373-native-utf16-policy-1"
    val extensions = setOf("erl", "hrl")
    fun supportsPath(path: String): Boolean = path.substringAfterLast('.', "") in extensions
    fun uri(path: String): String = URI("file", "", path, null).toASCIIString()
    fun rootUri(path: String): String = uri(path.trimEnd('/') + "/")
    fun ownedPath(raw: String, root: String): String {
        val uri = URI(raw)
        require(uri.scheme == "file" && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null)
        val path = requireNotNull(uri.path)
        require(path.startsWith(root.trimEnd('/') + "/") && uri(path) == raw && '\\' !in path)
        require(path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
        require(!SensitivePathPolicy.isSensitive(path))
        return path
    }
    val settings: JsonObject = buildJsonObject {
        put("diagnostics", buildJsonObject { put("onSave", buildJsonObject { put("enable", false) }) })
        put("signatureHelp", buildJsonObject { put("enable", true) })
        put("inlayHints", buildJsonObject { put("parameterHints", buildJsonObject { put("enable", true) }) })
        put("lens", buildJsonObject { put("enable", false) })
        put("hoverActions", buildJsonObject { put("enable", false) })
    }
    fun clientCapabilities(): JsonObject {
        val base = LspProtocolContract.clientCapabilities()
        val text = requireNotNull(base["textDocument"] as? JsonObject)
        val action = requireNotNull(text["codeAction"] as? JsonObject)
        return JsonObject(base + mapOf(
            "textDocument" to JsonObject((text - "formatting" - "diagnostic") + mapOf(
                "typeDefinition" to buildJsonObject { put("dynamicRegistration", false) },
                "codeAction" to JsonObject(action + ("resolveSupport" to buildJsonObject {
                    put("properties", buildJsonArray { add("edit") })
                })),
            )),
            "workspace" to buildJsonObject {
                put("applyEdit", false); put("configuration", true); put("workspaceFolders", false)
                put("symbol", buildJsonObject { put("dynamicRegistration", false) })
                put("didChangeWatchedFiles", buildJsonObject { put("dynamicRegistration", false) })
            },
            "window" to buildJsonObject { put("workDoneProgress", true) },
            "experimental" to buildJsonObject { put("serverStatusNotification", true) },
        ))
    }
    fun capabilities(value: JsonElement): LspNegotiatedCapabilities {
        val raw = (value as? JsonObject)?.get("capabilities") as? JsonObject ?: error("ELP capabilities missing")
        require(raw["positionEncoding"] == null || raw["positionEncoding"] == JsonPrimitive("utf-16"))
        val caps = LspNegotiatedCapabilities.fromInitialize(value, enableTypeDefinition = true)
        require(caps.sync.openClose && caps.sync.change == LspTextSyncPolicy.ChangeKind.INCREMENTAL && caps.sync.save) { "ELP native synchronization contract changed" }
        return caps.copy(formatting = false, pullDiagnostics = false)
    }
    const val REPORT_NOTE = "ELP latest native report; originating snapshot/compilation health unverified."
    const val RUNNING_NOTE = "ELP running; compilation and diagnostics are not verified."
    const val WATCH_NOTE = "IDE file changes restart analysis; changes made in a terminal or another editor require Refresh/Retry."
    const val FEATURE_NOTE = "Native Erlang completion, hover, signature, navigation, symbols, semantic tokens, inlay labels, call hierarchy, rename and edit actions. Native diagnostic origin and compilation health remain unverified. " + WATCH_NOTE
    const val APPROVAL_EXPLANATION = "ELP will run the selected OTP and its embedded Erlang service/eqWAlizer. Rebar projects may evaluate rebar.config.script, load plugins, fetch dependencies over the network, run hooks and compile dependencies. Parsing can run project parse transforms and test-related evaluation. It may read included/dependency files outside the editor root and write build outputs, caches and temporary files using your Ubuntu account. Known IDE saves/file changes and Close/discard can restart ELP and repeat this work. Selected preinstalled tools have unverified provenance; fixed probes must validate compatibility before project execution. Revoke stops owned analysis processes but does not undo files already written or downloads already made."
    const val INSTALLATION_DISCLOSURE = "No tool installation, silent runtime bootstrap, terminal runtime change, credential/security change, migration or deployment is authorized."
}
