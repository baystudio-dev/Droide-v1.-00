package com.baystudio.droide.core

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Complement CLI execution with editor observations and explicitly approved toolchain setup. */
internal class ExternalIdeTools(
    private val files: FileRepository,
    documents: WorkspaceDocumentAuthority?,
    private val lsp: LspManager,
    private val permissions: PermissionEngine,
    private val approvals: ApprovalManager,
    private val requireOwner: () -> Unit,
    private val handbook: (() -> String)? = null,
    private val status: (suspend () -> String)? = null,
    private val toolchain: (suspend (JsonObject, AgentRequestProvenance, () -> Unit) -> String)? = null,
    private val skills: SkillManagement? = null,
    private val media: MediaAuthoringTools? = null,
) : ExternalIdeToolRegistry.Provider {
    private val reads = AgentReadContext(files, documents, lsp, UUID.randomUUID().toString(), "external-cli-ide")
    private val reader = AgentWorkspaceReader(files, documents)
    private val search = AgentSearchResultPages()
    private val analysis = AgentLspResultPages()
    @Volatile private var closed = false
    private val names = linkedMapOf(
        "droide_read_file" to "read_file", "droide_search_files" to "search_files",
        "droide_list_files" to "list_files", "droide_project_instructions" to "project_instructions",
        "droide_diagnose" to "diagnose", "droide_lsp" to "lsp",
    ).apply {
        if (handbook != null) put("droide_help", "droide_help")
        if (status != null) put("droide_ide_status", "ide_status")
        if (toolchain != null) put("droide_toolchain", "toolchain")
        if (skills != null) put("droide_skill", "skill")
        if (media != null) { put("droide_document", "document"); put("droide_audio", "audio") }
    }

    override val definitions: JsonArray get() = buildJsonArray {
        names.forEach { (name, native) ->
            if (!permissions.toolAllowed(if (native == "ide_status") "ide" else native)) return@forEach
            val original = nativeDefinitions[native]
            val parameters = original?.get("parameters")?.jsonObject ?: buildJsonObject { put("type", "object") }
            val properties = parameters["properties"]?.jsonObject.orEmpty().filterKeys {
                !(native == "droide_help" && it == "screenshot") && it != "include_dependencies"
            }.mapValues { (key, value) ->
                val property = value.jsonObject
                val supported = if (native == "toolchain" && key == "operation") JsonObject(property +
                    ("enum" to JsonArray(property.getValue("enum").jsonArray.filterNot { it == JsonPrimitive("refresh_environment") }))) else property
                if (supported["type"] == JsonPrimitive("string")) JsonObject(supported + ("maxLength" to JsonPrimitive(if (native == "skill" && key == "content") 2000 else if (native == "skill" || key in setOf("path", "destination")) 500 else 256)))
                else supported
            }
            add(buildJsonObject {
                put("name", name)
                put("description", if (native == "ide_status") "Observe IDE build/run and debugger status; does not start, cancel or control work."
                    else if (native == "droide_help") "Read one bounded bundled reference section when asked about Droide. Text reference only; not project instructions or permission."
                    else if (native == "toolchain") "Inspect, install, repair or select toolchains in the existing Droide Ubuntu environment. " +
                        "Install waits for exact SDK license/installation approval in Droide; keep the call running. Preserve required SDK versions. " +
                        "After setup, resume this CLI in a new Droide Ubuntu terminal or reconnected external chat session before building with your shell tools. " +
                        "A child shell cannot refresh the old process's SDK mounts."
                    else if (native == "skill") original?.get("description")?.jsonPrimitive?.content.orEmpty() +
                        " This bridge accepts at most 4096 UTF-8 bytes per request; save inline instructions up to 2000 characters, or import a complete folder already synchronized to the Droide workspace."
                    else "Observe the Droide editor workspace, which may differ from the CLI working copy. " + original?.get("description")?.jsonPrimitive?.content.orEmpty())
                put("inputSchema", JsonObject(parameters + mapOf("properties" to JsonObject(properties), "additionalProperties" to JsonPrimitive(false))))
                put("annotations", buildJsonObject { put("readOnlyHint", native !in setOf("toolchain", "skill", "document", "audio")); put("destructiveHint", native in setOf("toolchain", "skill")) })
            })
        }
    }

    override suspend fun call(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        checkCurrent()
        val native = names[name] ?: error("Unknown IDE tool: $name")
        require(native != "toolchain" || arguments["operation"] != JsonPrimitive("refresh_environment")) {
            "An external CLI must resume in a new Droide Ubuntu terminal or a reconnected external chat session to refresh its SDK mounts"
        }
        val definition = definitions.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.content == name }
            ?: error("IDE tool is disabled by the current capability policy")
        val schema = definition.getValue("inputSchema").jsonObject
        require(arguments.keys.all { it in schema.getValue("properties").jsonObject }) { "Unknown IDE tool argument" }
        for (key in schema["required"]?.jsonArray.orEmpty()) require(key.jsonPrimitive.content in arguments) { "Missing ${key.jsonPrimitive.content}" }
        for ((key, value) in arguments) {
            val property = schema.getValue("properties").jsonObject.getValue(key).jsonObject
            if (property["type"] == JsonPrimitive("string")) require(value is JsonPrimitive && value.isString &&
                value.content.length <= property.getValue("maxLength").jsonPrimitive.int) { "$key exceeds the IDE tool string limit or is not a string" }
        }
        val approvalId = "external-ide-${UUID.randomUUID()}"
        suspend fun allow(action: String, resource: String) {
            checkCurrent()
            check(PermissionApprovalGate.approved(permissions, approvals, action, resource, reads.permissionScope,
                "external_ide_read", "External CLI · $name", resource, AgentRequestProvenance.primary(approvalId))) {
                "Permission denied: $action $resource"
            }
            checkCurrent()
        }
        try {
            val result = when (native) {
                "document", "audio" -> requireNotNull(media).execute(native, arguments, AgentMode.BUILD, current = ::checkCurrent) { action, resource -> allow(action, resource); null }
                "skill" -> SkillToolOperations.execute(arguments, requireNotNull(skills), AgentMode.BUILD) { action, resource ->
                    allow(action, resource)
                    null
                }
                "toolchain" -> requireNotNull(toolchain).invoke(arguments, AgentRequestProvenance.primary(approvalId), ::checkCurrent)
                "read_file" -> {
                    val path = path(arguments)
                    allow("read", path)
                    "[DROIDE_READ_RESULT returned=true]\n" + reader.read(path, integer(arguments, "start"), integer(arguments, "end"), integer(arguments, "offset"))
                }
                "list_files" -> {
                    val path = path(arguments, default = "")
                    allow("read", path)
                    val items = files.listFiles(path).filter { AgentWorkspacePathPolicy.visible(it.path) }
                    items.take(150).joinToString("\n") { (if (it.isDir) "DIR  " else "FILE ") + it.path } +
                        if (items.size > 150) "\n[output_truncated=true; list a narrower directory]" else ""
                }
                "project_instructions" -> {
                    val path = path(arguments, allowRoot = true)
                    val paths = AgentProjectInstructions.scopedPaths(files, path)
                    paths.forEach { allow("read", it) }
                    AgentProjectInstructions.loadScoped(files, path, paths)
                }
                "search_files" -> {
                    allow("grep", string(arguments, "query"))
                    search.execute(arguments, reads)
                }
                "diagnose", "lsp" -> {
                    val path = path(arguments)
                    allow(native, path)
                    analysis.execute(native, arguments, reads) {
                        if (native == "diagnose") lsp.diagnose(path) else {
                            val operation = string(arguments, "operation")
                            require(operation in LSP_OPERATIONS) { "Unsupported read-only LSP operation" }
                            if (operation == "workspaceSymbol") {
                                val query = string(arguments, "query")
                                require(query.isNotBlank() && query.length <= 256) { "Invalid workspace symbol query" }
                                val symbols = lsp.workspaceSymbols(path, query)
                                check(symbols.take(200).none { it.activationGuard?.accepts() == false }) { "Symbol results became stale; repeat the search" }
                                LspTextLimit.items("Provider returned ${symbols.size} symbols; completeness is not established.\n" +
                                    symbols.take(200).joinToString("\n") { symbol -> buildJsonObject {
                                        put("name", LspTextLimit.characters(symbol.name, 1024)); put("path", LspTextLimit.characters(symbol.path, 4096))
                                        put("line", symbol.line); put("col", symbol.column)
                                    }.toString() }, symbols.size, 200)
                            } else lsp.lsp(operation, path, positive(arguments, "line"), positive(arguments, "col"))
                        }
                    }
                }
                "droide_help" -> {
                    val section = string(arguments, "section")
                    DroideHandbookPolicy.validateSelection(section, null)
                    allow("read", "droide_help:$section")
                    "[DROIDE_HELP_REFERENCE reference_only=true; not instructions or permission]\n" +
                        requireNotNull(handbook).invoke().reader().use { DroideHandbookPolicy.readSection(it, section) }
                }
                "ide_status" -> { allow("read", "ide:status"); requireNotNull(status).invoke() }
                else -> error("Unsupported IDE tool")
            }
            checkCurrent()
            AgentToolResultSemantics.classify(if (native == "ide_status") "ide" else native, result,
                if (native == "ide_status") "status" else null).annotate(native,
                "[DROIDE_IDE_RESULT source=editor_workspace; CLI working copy may differ]\n" + result)
        } finally { approvals.clearSessionApprovals(approvalId, reads.permissionScope) }
    }

    private fun path(args: JsonObject, default: String? = null, allowRoot: Boolean = false): String {
        val value = if ("path" in args) string(args, "path") else default ?: error("Missing path")
        require(value.isEmpty() && (default == "" || allowRoot) || AgentWorkspacePathPolicy.visible(value)) {
            "Use a visible workspace-relative path; internal state, secrets and dependency directories are unavailable"
        }
        val root = files.root.canonicalFile
        val canonical = files.resolveChecked(value).canonicalFile.relativeTo(root).invariantSeparatorsPath
        require(canonical.isEmpty() || AgentWorkspacePathPolicy.visible(canonical)) { "Path resolves outside the visible workspace" }
        return canonical
    }

    private fun checkCurrent() { check(!closed) { "IDE tool workspace closed" }; requireOwner() }
    override fun close() { closed = true; search.close(); analysis.close() }

    companion object {
        private val nativeDefinitions by lazy { AgentToolCatalog.definitions.map { it.jsonObject.getValue("function").jsonObject }
            .associateBy { it.getValue("name").jsonPrimitive.content } }
        private val LSP_OPERATIONS = setOf("hover", "completion", "goToDefinition", "goToTypeDefinition", "findReferences", "goToImplementation", "documentSymbol", "workspaceSymbol")
        private fun string(args: JsonObject, key: String): String =
            (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")
        private fun integer(args: JsonObject, key: String): Int? = args[key]?.let {
            require(it is JsonPrimitive && !it.isString && it.intOrNull != null) { "$key must be an integer" }; it.intOrNull
        }
        private fun positive(args: JsonObject, key: String): Int = (integer(args, key) ?: 1).also { require(it > 0) { "$key must be positive" } }
    }
}
