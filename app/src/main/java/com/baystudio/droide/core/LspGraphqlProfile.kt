package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.net.URI

/** Exact managed profile. Matching this record does not replace typed package admission. */
internal object LspGraphqlProfile {
    const val ID = "graphql-local-schema"
    const val COMMAND = "droide-graphql-local-lsp"
    const val IDENTITY = "graphql-language-service-5.7.0-graphql-16.13.2-local-schema-v1"
    val extensions = setOf("graphql", "gql")
    fun supportsPath(path: String): Boolean = path.substringAfterLast('.', "").map { if (it in 'A'..'Z') it + 32 else it }.joinToString("") in extensions
    fun rootUri(root: String): String = URI("file", "", root.trimEnd('/') + "/", null).toASCIIString()
    fun uri(path: String): String = URI("file", "", path, null).toASCIIString()
    fun requireOwnedUri(raw: String, root: String): String {
        val value = URI(raw)
        require(value.scheme == "file" && value.rawAuthority.isNullOrEmpty() && value.rawQuery == null && value.rawFragment == null)
        val path = value.path ?: error("Missing GraphQL URI path")
        require(path.startsWith(root.trimEnd('/') + "/") && uri(path) == raw && !path.contains('\\'))
        require(path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
        return path
    }
    fun clientCapabilities(): JsonObject = buildJsonObject {
        put("general", buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-16") }) })
        put("workspace", buildJsonObject { put("applyEdit", false); put("configuration", false) })
        put("textDocument", buildJsonObject {
            put("completion", buildJsonObject { put("completionItem", buildJsonObject { put("snippetSupport", true); put("commitCharactersSupport", false) }) })
            put("documentSymbol", buildJsonObject { put("hierarchicalDocumentSymbolSupport", false) })
            put("diagnostic", buildJsonObject { put("relatedDocumentSupport", true) })
        })
    }
    fun capabilities(result: JsonElement): LspNegotiatedCapabilities {
        val raw = (result as? JsonObject)?.get("capabilities") as? JsonObject ?: error("GraphQL capabilities missing")
        require(raw["positionEncoding"] == JsonPrimitive("utf-16"))
        val caps = LspNegotiatedCapabilities.fromInitialize(result)
        require(caps.sync.openClose && caps.sync.change == LspTextSyncPolicy.ChangeKind.FULL)
        return caps.copy(sync = caps.sync.copy(save = false, saveIncludeText = false),
            signatureHelp = false, signatureHelpTriggerCharacters = emptySet(), signatureHelpRetriggerCharacters = emptySet(),
            definition = false, references = false, implementation = false, typeDefinition = false, workspaceSymbol = false,
            codeAction = false, codeActionResolve = false, callHierarchy = false, formatting = false, rename = false,
            semanticTokens = false, inlayHint = false)
    }
    const val APPROVAL_EXPLANATION = "Read this actual workspace's .graphqlrc.json, up to 8 explicitly named local SDL schema files and 32 fragment-only files, root competitor/package metadata, and authoritative unsaved buffers. Native completion, hover, diagnostics and named outline use the original GraphQL libraries. Selecting a completion may edit the current editor. This route does not write project files, evaluate project configuration or send project content over the network."
    const val INSTALLATION_DISCLOSURE = "Requires separately admitted graphql-language-service 5.7.0, graphql 16.13.2 and private Node 22.23.3. Enabling does not install prerequisites or switch terminal Node. This cooperative route is not an OS security sandbox."
    const val FEATURE_NOTE = "GraphQL local SDL schema: native completion, hover, source-owned diagnostics and named outline. Exact .graphqlrc.json paths only; no globs, remote schema, code loaders, embedded queries, definition, rename or formatting."
}
