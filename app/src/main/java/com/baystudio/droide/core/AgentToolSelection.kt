package com.baystudio.droide.core

import java.util.Locale
import kotlinx.serialization.json.*

/** Run-owned, bounded tool discovery. Catalogs are already filtered by capability and permission.
 * Loading metadata never executes a tool or grants permission. The next request's schemas, and
 * the normal execution-time checks, remain authoritative. No filesystem/network discovery here.
 */
internal class AgentToolSelection {
    private var catalog = linkedMapOf<String, JsonObject>()
    private val loaded = linkedSetOf<String>()
    private var schemaBudget = MAX_LOADED_BYTES

    @Synchronized
    fun project(definitions: JsonArray, discoveryAllowed: Boolean = true,
        maxAdditionalSchemaBytes: Int = MAX_LOADED_BYTES): JsonArray {
        require(maxAdditionalSchemaBytes >= 0)
        schemaBudget = minOf(MAX_LOADED_BYTES, maxAdditionalSchemaBytes)
        if (!discoveryAllowed) {
            catalog.clear(); loaded.clear()
            return definitions // A restricted profile without discovery must not lose its tools.
        }
        catalog = linkedMapOf<String, JsonObject>().apply {
            definitions.forEach { raw ->
                val definition = raw.jsonObject
                val name = definition.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
                // Compatibility aliases still execute for old history; do not advertise duplicates.
                if (AgentModePolicy.canonicalTool(name) == name || McpToolIdentity.isFirstClassName(name)) put(name, definition)
            }
        }
        loaded.retainAll(catalog.keys)
        trimLoaded()
        // Stable core prefix benefits provider caching as extra tools are loaded/evicted.
        return JsonArray(catalog.filterKeys { it in CORE }.values + DEFINITION +
            catalog.filterKeys { it !in CORE && it in loaded }.values)
    }

    @Synchronized
    fun search(args: JsonObject): String {
        require(args.keys.all { it in setOf("query", "offset", "limit") }) { "Unknown tool_search argument" }
        val query = requireNotNull((args["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "Tool query must be a string; use an empty string to list tools"
        }.trim()
        require(query.length <= 200) { "Tool query must be at most 200 characters" }
        fun integer(name: String, default: Int): Int = if (name !in args) default else
            requireNotNull((args[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull) { "$name must be an integer" }
        val offset = integer("offset", 0)
        val limit = integer("limit", 3)
        require(offset >= 0 && limit in 1..5) { "Invalid tool search page" }
        val words = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}_]+" )).filter { it.isNotBlank() }
        fun score(name: String, definition: JsonObject): Int {
            val lowerName = name.lowercase(Locale.ROOT)
            if (lowerName == query.lowercase(Locale.ROOT)) return Int.MAX_VALUE
            val description = definition.getValue("function").jsonObject["description"]?.jsonPrimitive?.content.orEmpty().lowercase(Locale.ROOT)
            return words.sumOf { word -> if (lowerName.contains(word)) 20 else if (description.contains(word)) 1 else 0 }
        }
        val matches = catalog.entries.map { it to score(it.key, it.value) }
            .filter { words.isEmpty() || it.second > 0 }
            .sortedWith(compareByDescending<Pair<Map.Entry<String, JsonObject>, Int>> { it.second }.thenBy { it.first.key })
        val page = matches.drop(offset).take(limit).map { it.first }
        // Reserve this page together. Multiple search results may not promise mutually evicted tools.
        val requested = page.filter { it.key !in CORE }
        val requestedBytes = requested.sumOf { schemaBytes(it.value).toLong() }
        val fits = requestedBytes <= schemaBudget
        if (fits) {
            requested.forEach { loaded.remove(it.key) }
            requested.forEach { loaded.add(it.key) }
            trimLoaded()
        }
        return buildJsonObject {
            put("matches", buildJsonArray {
                page.forEach { (name, definition) -> add(buildJsonObject {
                    put("name", name)
                    put("description", shortDescription(definition))
                    put("available_next_request", name in CORE || name in loaded)
                }) }
            })
            put("total_matches", matches.size)
            if (offset.toLong() + page.size < matches.size) put("next_offset", offset + page.size)
            put("note", if (fits)
                "Use the schemas in the next request. Discovery does not execute tools or grant permission; older loaded schemas may be evicted. Search only for capabilities needed for the user's task."
            else "This page exceeds the selected model's additional schema budget ($schemaBudget bytes); search an exact name or use limit=1. Nothing new was loaded. A single oversized schema needs more model input room; for MCP, the generic mcp tool may provide bounded inspection and calling.")
        }.toString()
    }

    private fun trimLoaded() {
        var bytes = loaded.sumOf { catalog[it]?.let(::schemaBytes)?.toLong() ?: 0 }
        while (loaded.size > MAX_LOADED_TOOLS || bytes > schemaBudget) {
            val oldest = loaded.first()
            bytes -= catalog[oldest]?.let(::schemaBytes) ?: 0
            loaded.remove(oldest)
        }
    }

    private fun schemaBytes(definition: JsonObject) = ContextBudget.serializedUtf8Bytes(definition)
    private fun shortDescription(definition: JsonObject): String {
        val value = definition.getValue("function").jsonObject["description"]?.jsonPrimitive?.content.orEmpty()
        var end = minOf(240, value.length)
        if (end in 1 until value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
        return value.substring(0, end)
    }

    companion object {
        private const val MAX_LOADED_TOOLS = 12
        private const val MAX_LOADED_BYTES = 65_536
        // At most one fifth of the available input allowance (using bytes/4), with an
        // independent absolute bound for unknown/large models. Never invent a model limit.
        fun extraBudget(availableInputTokens: Int?): Int = availableInputTokens?.let {
            (it.coerceAtLeast(0).toLong() * 4 / 5).coerceAtMost(MAX_LOADED_BYTES.toLong()).toInt()
        } ?: MAX_LOADED_BYTES
        private val CORE = setOf("list_files", "read_file", "glob", "search_files", "project_instructions",
            "write_file", "edit_file", "apply_patch", "save_files", "exec_command", "write_stdin",
            "environment_probe", "git_status", "git_diff", "todowrite", "question", "skill", "rule")
        val DEFINITION = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", "tool_search")
                put("description", "Find and load additional tools for the next request by capability or exact name. Covers Droide help/handbook, browser/web research, documents/PDF/DOCX, audio/transcription, image generation/media, IDE/build/test/LSP, toolchains/extensions, Git, subagents, recovery, MCP and installed plugin/custom tools. Use English keywords or exact names, e.g. droide_help, browser, document, audio, generate_image, ide, task, mcp. Empty query lists the permitted catalog with pagination. Do not inventory tools without a task. Core tools are already callable. Loading is metadata only; execution still needs normal permissions.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject { put("type", "string"); put("maxLength", 200) })
                        put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                        put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 5) })
                    })
                    put("required", buildJsonArray { add("query") })
                    put("additionalProperties", false)
                })
            })
        }
    }
}
