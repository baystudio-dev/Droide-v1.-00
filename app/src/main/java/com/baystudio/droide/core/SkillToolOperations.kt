package com.baystudio.droide.core

import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SkillToolOperations {
    private val operations = setOf("list", "load", "list_resources", "read_resource", "copy_resource", "save", "import")
    private val fields = setOf("operation", "name", "query", "path", "destination", "scope", "content", "expected_sha256", "offset")
    private class Denied(val result: String) : IllegalStateException(result)

    fun definition(catalog: List<SkillSummary> = emptyList(), hasMore: Boolean = false): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "skill")
            put("description", buildString {
                append("Discover/load reusable SKILL.md instructions on demand. Explicit /skill/name or @skill/name selects a skill: load it before following it. ")
                append("Use list with query/offset to find skills; load/read_resource return bounded pages; continue using offset plus expected_sha256 from the first page. Save full SKILL.md frontmatter+body to workspace (default) or user library. Reads combine libraries unless scope is supplied. ")
                append("Updating requires expected_sha256 from the current load. Import a saved project skill folder, preserving resources. Import never executes scripts or grants permissions. ")
                append("Use copy_resource to materialize an asset/script at a new project destination before using ordinary file/shell tools. ")
                var listed = 0
                var remainingBytes = 8_000
                for (item in catalog.take(40)) {
                    if (item.name.length > 500) continue
                    val segment = "${if (listed == 0) "Available: " else "; "}${item.name}: ${clip(item.description, 120)}"
                    val bytes = JsonPrimitive(segment).toString().toByteArray(Charsets.UTF_8).size
                    if (bytes > remainingBytes) continue
                    append(segment); listed++; remainingBytes -= bytes
                }
                if (hasMore || listed < catalog.size) append("; more available via operation=list.")
            })
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("operation", buildJsonObject { put("type", "string"); put("enum", JsonArray(operations.map(::JsonPrimitive))); put("description", "Default: load") })
                    for (key in listOf("name", "query", "path", "destination", "expected_sha256"))
                        put(key, buildJsonObject { put("type", "string"); put("maxLength", 500) })
                    put("scope", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("workspace", "user").map(::JsonPrimitive))) })
                    put("content", buildJsonObject { put("type", "string"); put("maxLength", SkillDocumentFormat.MAX_BYTES) })
                    put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", SkillDocumentFormat.MAX_BYTES) })
                })
            })
        })
    }

    suspend fun execute(
        args: JsonObject,
        management: SkillManagement,
        mode: AgentMode,
        permission: suspend (String, String) -> String?,
    ): String = withContext(Dispatchers.IO) { executeOnIo(args, management, mode, permission) }

    private suspend fun executeOnIo(args: JsonObject, management: SkillManagement, mode: AgentMode,
        permission: suspend (String, String) -> String?): String = try {
        require(args.keys.all { it in fields }) { "Unknown skill argument" }
        fun text(key: String, default: String = ""): String {
            val value = args[key] ?: return default
            require(value is JsonPrimitive && value.isString && value.content.length <= if (key == "content") SkillDocumentFormat.MAX_BYTES else 500) { "Invalid $key" }
            return value.content
        }
        args.keys.filter { it != "offset" }.forEach { text(it) }
        fun required(key: String) = text(key).also { require(it.isNotBlank()) { "Missing $key" } }
        suspend fun allow(action: String, resource: String) { permission(action, resource)?.let { throw Denied(it) } }
        val operation = text("operation", "load")
        require(operation in operations) { "Unsupported skill operation" }
        val name = text("name")
        val expectedHash = text("expected_sha256").takeIf { it.isNotEmpty() }
        val offset = args["offset"]?.let { value ->
            require(value is JsonPrimitive && !value.isString && value.intOrNull != null) { "Invalid skill offset" }
            value.intOrNull!!
        } ?: 0
        require(offset in 0..SkillDocumentFormat.MAX_BYTES)
        allow("skill", name.ifBlank { "catalog" })
        val requestedScope = text("scope").takeIf { "scope" in args }
        require(requestedScope == null || requestedScope in setOf("workspace", "user")) { "Invalid skill scope" }
        val scope = requestedScope ?: "workspace"
        fun destination(skillName: String) = if (scope == "user") "personal-skill:$skillName" else ".droide/skills/$skillName/SKILL.md"
        if (operation in setOf("save", "import", "copy_resource"))
            require(mode == AgentMode.BUILD) { "Saving/importing skills requires Build mode" }
        when (operation) {
            "list" -> {
                val query = text("query")
                val catalog = management.manager(requestedScope).modelCatalog().filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
                require(offset <= catalog.size) { "Catalog changed. Start listing at offset 0." }
                val page = catalog.drop(offset).take(20)
                buildJsonObject {
                    put("discovery_limit", 160)
                    put("skills", JsonArray(page.map { buildJsonObject {
                        put("name", it.name); put("description", clip(it.description, 240)); put("source", it.sourceLabel)
                    } }))
                    put("next_offset", if (offset + page.size < catalog.size) JsonPrimitive(offset + page.size) else JsonNull)
                }.toString()
            }
            "load" -> {
                val document = management.document(required("name"), requestedScope)
                val body = SkillDocumentFormat.body(document.markdown)
                "# ${document.skill.name}: ${document.skill.description}\nSource: ${document.skill.sourceLabel}\n\n" +
                    page(body, offset, expectedHash, document.version.sha256) + "\n[skill_sha256=${document.version.sha256}; resources: use list_resources/read_resource]"
            }
            "list_resources" -> {
                val paths = management.resources(required("name"), requestedScope)
                require(offset <= paths.size) { "Resources changed. Start at offset 0." }
                buildJsonObject {
                    put("paths", JsonArray(paths.drop(offset).take(16).map(::JsonPrimitive)))
                    put("next_offset", if (offset + 16 < paths.size) JsonPrimitive(offset + 16) else JsonNull)
                }.toString()
            }
            "read_resource" -> {
                allow("read", "skill:${required("name")}/${required("path")}")
                val content = management.readResource(name, required("path"), requestedScope)
                val hash = DiskFileVersion.fromText(content).sha256
                page(content, offset, expectedHash, hash) + "\n[resource_sha256=$hash]"
            }
            "copy_resource" -> {
                allow("read", "skill:${required("name")}/${required("path")}")
                allow("edit", required("destination"))
                "Saved skill resource to " + management.copyResource(name, required("path"), required("destination"), requestedScope)
            }
            "save" -> {
                val markdown = required("content")
                val skillName = SkillDocumentFormat.validate(markdown)
                if (name.isNotEmpty()) require(name == skillName) { "Name differs from SKILL.md frontmatter" }
                allow("edit", destination(skillName))
                receipt(management.save(markdown, scope, expectedHash))
            }
            else -> {
                allow("read", required("path"))
                receipt(management.importDirectory(required("path"), scope) { skillName -> allow("edit", destination(skillName)) })
            }
        }
    } catch (denied: Denied) { denied.result }

    private fun clip(text: String, limit: Int): String {
        var end = minOf(text.length, limit)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }

    private fun receipt(document: SkillDocument): String = buildJsonObject {
        put("saved", true); put("name", document.skill.name); put("scope", document.managedScope)
        put("sha256", document.version.sha256); put("scriptsExecuted", false)
    }.toString()

    private fun page(text: String, offset: Int, expectedHash: String?, actualHash: String): String {
        require(if (offset == 0) expectedHash == null || expectedHash == actualHash else expectedHash == actualHash) {
            "Skill/resource changed or continuation has no expected_sha256. Restart at offset 0."
        }
        require(offset <= text.length && !(offset > 0 && offset < text.length && text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())) { "Invalid text offset" }
        var end = minOf(text.length, offset + 8_000)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(offset, end) + if (end < text.length) "\n[next_offset=$end; continue with the same skill/resource, expected_sha256 and offset]" else ""
    }
}
