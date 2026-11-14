package com.baystudio.droide.core
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext


 
data class AgentToolProgress(
    val outputDelta: String = "",
    val detail: String = "",
    val title: String = "",
    val fileChanges: List<AgentFileChange>? = null,
    val producedFiles: List<String>? = null,
)
/** Only thrown before an append/hook is admitted; never signals rollback of persisted history. */
internal class BrowserPublicationRevokedException(message: String) : IllegalStateException(message)

object AgentTools {
    private const val SKILL_CATALOG_LIMIT = 40
    private const val RULE_CATALOG_LIMIT = 64
    val definitions: JsonArray = AgentToolCatalog.definitions

     
    fun definitionsWithCustom(
        skills: SkillManager,
        customs: CustomToolManager,
        allowSubagents: Boolean = true,
        mode: AgentMode = AgentMode.BUILD,
        permissions: PermissionEngine? = null,
        permissionScope: String = mode.name.lowercase(),
        subagentProfiles: List<AgentProfileSummary> = AgentProfileManager.builtinSubagentSummaries(),
        mcpTools: List<McpAgentTool> = emptyList(),
        rules: AgentRuleManager? = null,
        pluginTools: List<AgentPluginToolDef> = emptyList(),
        ideAvailable: Boolean = false,
        lspEditAvailable: Boolean = false,
        toolchainAvailable: Boolean = false,
        capabilityAvailable: Boolean = false,
        browserAvailable: Boolean = false,
        commandSessionsAvailable: Boolean = false,
        visionAvailable: Boolean = false,
        mediaAvailable: Boolean = false,
        generationAvailable: Boolean = false,
        handbookAvailable: Boolean = false,
        authoringAvailable: Boolean = false,
    ): JsonArray = definitionsWithCustom(skills, customs, allowSubagents, mode, permissions,
        permissionScope, subagentProfiles, mcpTools, rules, pluginTools, ideAvailable,
        lspEditAvailable, toolchainAvailable, capabilityAvailable, browserAvailable,
        commandSessionsAvailable, visionAvailable, mediaAvailable, generationAvailable,
        handbookAvailable, authoringAvailable, localArchiveAvailable = false)

    fun definitionsWithCustom(
        skills: SkillManager,
        customs: CustomToolManager,
        allowSubagents: Boolean = true,
        mode: AgentMode = AgentMode.BUILD,
        permissions: PermissionEngine? = null,
        permissionScope: String = mode.name.lowercase(),
        subagentProfiles: List<AgentProfileSummary> = AgentProfileManager.builtinSubagentSummaries(),
        mcpTools: List<McpAgentTool> = emptyList(),
        rules: AgentRuleManager? = null,
        pluginTools: List<AgentPluginToolDef> = emptyList(),
        ideAvailable: Boolean = false,
        lspEditAvailable: Boolean = false,
        toolchainAvailable: Boolean = false,
        capabilityAvailable: Boolean = false,
        browserAvailable: Boolean = false,
        commandSessionsAvailable: Boolean = false,
        visionAvailable: Boolean = false,
        mediaAvailable: Boolean = false,
        generationAvailable: Boolean = false,
        handbookAvailable: Boolean = false,
        authoringAvailable: Boolean = false,
        localArchiveAvailable: Boolean,
    ): JsonArray {
        val skillCatalog = skills.modelCatalog(limit = SKILL_CATALOG_LIMIT + 1)
        val ruleDiscovery = rules?.catalogWithDiagnostics(limit = RULE_CATALOG_LIMIT + 1)
        val modelRules = ruleDiscovery?.values.orEmpty()
        val projectedDefinitions = definitions.map { element ->
            val fn = (element as? JsonObject)?.get("function") as? JsonObject
            when ((fn?.get("name") as? JsonPrimitive)?.contentOrNull) {
                "task" -> taskTool(subagentProfiles)
                "skill" -> SkillToolOperations.definition(skillCatalog.take(SKILL_CATALOG_LIMIT), skillCatalog.size > SKILL_CATALOG_LIMIT)
                "rule" -> contextLoaderTool("rule", "Load an Agent rule on demand",
                    modelRules.take(RULE_CATALOG_LIMIT).map { it.name to "[${it.activation.name.lowercase()}] ${it.description}" }, modelRules.size > RULE_CATALOG_LIMIT,
                    ruleDiscovery?.notice().orEmpty())
                else -> element
            }
        }
        val base = JsonArray(projectedDefinitions.filter { element ->
            val tool = element as? JsonObject ?: return@filter true
            val fn = tool["function"] as? JsonObject ?: return@filter true
            val name = (fn["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val action = actionOf(name)
            val permissionVisible = permissions == null || permissions.actionVisible(action, permissionScope) ||
                (name == "web" && permissions.actionVisible("websearch", permissionScope)) ||
                (name == "mcp" && permissions.actionVisible("mcp_repair", permissionScope))
            AgentModePolicy.isToolVisible(mode, name) &&
                (name != "ide" || ideAvailable) &&
                (name != "lsp_edit" || lspEditAvailable) &&
                (name != "toolchain" || toolchainAvailable) &&
                (name != "capability" || capabilityAvailable) &&
                (name != "browser" || browserAvailable) &&
                (name !in setOf("exec_command", "write_stdin") || commandSessionsAvailable) &&
                (name != "view_image" || visionAvailable) &&
                (name != "droide_help" || handbookAvailable) &&
                (name !in setOf("document", "audio", "transcribe_audio") || authoringAvailable) &&
                (name != "archive_deleted_folder" || (localArchiveAvailable && (permissions == null || permissions.actionVisible("read", permissionScope)))) &&
                (name != "view_video" || (visionAvailable && mediaAvailable)) &&
                (name !in setOf("send_media", "save_media_to_project") || (mediaAvailable && (permissions == null || permissions.actionVisible("read", permissionScope)))) &&
                (name != "generate_image" || (mediaAvailable && generationAvailable && (permissions == null || permissions.actionVisible("webfetch", permissionScope)))) &&
                permissionVisible &&
                (permissions == null || permissions.toolAllowed(name)) &&
                (allowSubagents || name !in setOf("task", "task_control", "explore_codebase"))
        })
        val extra = buildJsonArray {
            

            customs.discover().forEach { ct ->
                val name = "custom_${ct.name}"
                if (AgentModePolicy.isToolVisible(mode, name) &&
                    (permissions == null || permissions.actionVisible("shell", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(name))) {
                    add(tool(name, ct.description,
                        buildJsonObject { put("input", buildJsonObject { put("type", "string") }) }))
                }
            }
            pluginTools.forEach { pt ->
                if (mode == AgentMode.BUILD &&
                    (permissions == null || permissions.actionVisible("shell", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(pt.functionName))) {
                    add(toolWithParameters(pt.functionName, pt.description, pt.inputSchema))
                }
            }
            // Runtime permission and identity checks remain authoritative at execution time.

            mcpTools.forEach { mt ->
                if (AgentModePolicy.isToolVisible(mode, mt.functionName) &&
                    (permissions == null || permissions.actionVisible("mcp", permissionScope)) &&
                    (permissions == null || permissions.toolAllowed(mt.functionName))) {
                    add(mcpTool(mt))
                }
            }
        }
        return JsonArray(base + extra)
    }
    private fun contextLoaderTool(name: String, base: String, items: List<Pair<String, String>>, hasMore: Boolean, discoveryNotice: String = ""): JsonObject {
        val available = items.distinctBy { it.first }.sortedBy { it.first }
        val listed = mutableListOf<JsonPrimitive>()
        val hint = StringBuilder()
        var remainingBytes = 12_000
        for ((identifier, description) in available) {
            if (identifier.length > 256) continue
            var end = minOf(description.length, 120)
            if (end in 1 until description.length && description[end - 1].isHighSurrogate() && description[end].isLowSurrogate()) end--
            val segment = (if (hint.isEmpty()) "" else "; ") + identifier + ": " + description.substring(0, end)
            val value = JsonPrimitive(identifier)
            val bytes = value.toString().toByteArray(Charsets.UTF_8).size +
                JsonPrimitive(segment).toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes > remainingBytes) continue
            remainingBytes -= bytes
            listed += value
            hint.append(segment)
        }
        val omitted = available.size - listed.size
        val notice = if (omitted == 0) "" else
            ". $omitted entries omitted from this summary (size limit). Use the exact name from the entry's metadata to load an omitted entry."
        val continuation = if (!hasMore) "" else
            ". Additional entries exist beyond this summary (catalog limit). Load an unlisted entry using its exact name from metadata."
        return tool(name, base + (if (hint.isEmpty()) "" else ". Available: $hint") + notice + continuation +
            (if (discoveryNotice.isEmpty()) "" else ". $discoveryNotice"),
            buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    // A partial enum would reject valid names that remain loadable.
                    if (listed.isNotEmpty() && omitted == 0 && !hasMore && discoveryNotice.isEmpty()) put("enum", JsonArray(listed))
                })
            }, listOf("name"))
    }

    private fun mcpTool(tool: McpAgentTool): JsonObject = toolWithParameters(
        tool.functionName,
        ("MCP ${tool.serverName}/${tool.toolName}: " + tool.description).take(1_200),
        tool.inputSchema,
    )

    private fun toolWithParameters(name: String, description: String, parameters: JsonObject): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", parameters)
        })
    }

    private fun taskTool(profiles: List<AgentProfileSummary>): JsonObject {
        val available = profiles.filter { it.subagent }.distinctBy { it.name }.sortedBy { it.name }
        val detail = available.joinToString("; ") { "${it.name}: ${it.description.take(180)}" }
        return tool("task", "Delegate work to a fresh or resumable child-agent session" +
            if (detail.isBlank()) "" else ". Available profiles: $detail",
            buildJsonObject {
                put("description", buildJsonObject { put("type", "string"); put("description", "Short 3-8 word task label") })
                put("prompt", buildJsonObject { put("type", "string"); put("description", "Detailed autonomous instructions and expected result") })
                put("subagent_type", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(available.map { JsonPrimitive(it.name) }))
                    put("description", "Choose a discovered Agent profile; profile capabilities can only narrow runtime authority.")
                })
                put("task_id", buildJsonObject { put("type", "string"); put("description", "Resume a previous child session") })
                put("background", buildJsonObject { put("type", "boolean"); put("description", "Run concurrently and notify parent when done") })
                put("workspace", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("auto"); add("isolated"); add("shared") })
                    put("description", "Workspace policy. BUILD profiles default to isolated; read-only profiles default to shared.")
                })
            }, listOf("description", "prompt", "subagent_type"))
    }

    private fun tool(name: String, desc: String, props: JsonObject, required: List<String> = emptyList()) =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name); put("description", desc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", props)
                    put("required", JsonArray(required.map { JsonPrimitive(it) }))
                })
            })
        }

     
    internal fun parallelReadAccess(tool: String, args: JsonObject): AgentToolReadAccess? {
        val realName = when (tool) {
            "read" -> "read_file"
            "grep" -> "search_files"
            else -> tool
        }
        return when (realName) {
            "list_files" -> AgentToolReadAccess("read", args["path"]?.jsonPrimitive?.contentOrNull ?: "")
            "read_file" -> AgentToolReadAccess("read", args["path"]?.jsonPrimitive?.contentOrNull ?: return null)
            "glob" -> AgentToolReadAccess("glob", args["pattern"]?.jsonPrimitive?.contentOrNull ?: return null)
            "search_files" -> AgentToolReadAccess("grep", args["query"]?.jsonPrimitive?.contentOrNull ?: return null)
            else -> null
        }
    }

     
    fun actionOf(tool: String): String = AgentToolPermissionPolicy.actionOf(tool)

    class Ctx(
        val files: FileRepository,
        val terminal: ITerminalSession,
        val git: GitManager,
        val approvals: ApprovalManager,
        val questions: QuestionManager,
        val history: EditHistory,
        val perms: PermissionEngine,
        val mode: AgentMode,
        val llm: LlmClient,
        val llmConfig: AgentConfig,
        val workDir: java.io.File,
        val todos: TodoManager,
        val sessionId: String?,
        val lsp: LspManager,
        val backgroundCommands: BackgroundCommandManager? = null,
        val subagents: SubagentManager? = null,
        val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
        val pluginSource: AgentPluginSource = AgentPluginSource.EMPTY,
        val documentAuthority: WorkspaceDocumentAuthority? = null,
        val activeToolNames: Set<String> = emptySet(),
        val ideActions: AgentIdeActions? = null,
        val lspEditActions: AgentLspEditActions? = null,
        val toolchainActions: AgentToolchainActions? = null,
        val capabilityActions: AgentCapabilityActions? = null,
        val browserController: AgentBrowserController? = null,
        val browserToolWork: AgentBrowserController.ToolWork? = null,
        val browserRequestCurrent: () -> Boolean = { true },
        val browserPublication: BrowserPublicationAuthority = BrowserPublicationAuthority(),
        val browserRunId: String = java.util.UUID.randomUUID().toString(),
        val browserCallId: String = java.util.UUID.randomUUID().toString(),
        val mcpProcessHost: StdioProcessHost? = null,
        val webResearch: AgentWebResearch = AgentWebResearch(workDir),
        val onImageReady: ((List<AgentImageRef>) -> Unit)? = null,
        val onMediaReady: ((ChatMediaRef) -> Unit)? = null,
        val appContext: android.content.Context? = null,
        val imageGeneration: AgentImageGeneration? = null,
        internal val providerConfiguration: ProviderRegistry.ConfiguredSnapshot? = null,
        val mediaRunId: String? = null,
        val mediaRequestCurrent: () -> Boolean = { false },
        // Unknown for restored/queued inputs until their original admission carries trusted evidence.
        val mediaRequestAdmittedAtNanos: Long? = null,
        val mediaCopyAttempts: MediaCopyAttemptLedger = MediaCopyAttemptLedger(),
        val onProgress: (AgentToolProgress) -> Unit = {},
    ) {
        val permissionScope: String get() = AgentPermissionScope.id(mode, provenance)
        // Service-owned production recorder. Direct local/test callers retain their existing
        // behavior and do not claim durable session evidence without this original owner.
        internal var textEditCall: AgentTextEditCall? = null
        internal var sourceCommand: ToolCallHistoryJournal.CommandBinding? = null
        internal var sourceStdin: BackgroundCommandManager.SourceStdinBinding? = null
        internal var toolSearch: ((JsonObject) -> String)? = null
        internal var handbookBudget = DroideHandbookBudget()
        internal var lspResultPages = AgentLspResultPages()
        internal var searchResultPages = AgentSearchResultPages()
        // Bound by the owning AgentService call; absent in unowned/direct contexts.
        internal var localRecoveryOwner: HookInvocationAuthority? = null
        internal var localRecoveryCache: java.io.File? = null
    }

    /** One prepared call owns this receipt; it never grants authority to another call. */
    class BrowserPublicationAuthority {
        @Volatile private var check: (() -> String?)? = null
        @Volatile private var rejected: String? = null
        private val grants = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()
        internal fun bindSessionGrant(current: () -> Boolean) { grants += current }
        internal fun capture(check: () -> String?) { this.check = check }
        internal val captured: Boolean get() = check != null
        // A rejected publication is never revived by a later policy toggle.
        fun denial(): String? = rejected ?: (if (grants.any { !it() })
            "DENIED: browser session permission was revoked; do not replay an action already dispatched."
            else check?.invoke())?.also { rejected = it }
    }

    private class BrowserToolWorkScope(
        val controller: AgentBrowserController,
        val ticket: AgentBrowserController.ToolWork,
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<BrowserToolWorkScope>
    }

    /** Includes approvals and lifecycle hooks in the same browser-only cancellation scope. */
    internal suspend fun withBrowserToolWork(name: String, c: Ctx, block: suspend () -> String): String {
        val controller = c.browserController
        if (name != "browser" || controller == null) return block()
        val inherited = currentCoroutineContext()[BrowserToolWorkScope]
        if (inherited != null && inherited.controller === controller &&
            (c.browserToolWork == null || inherited.ticket === c.browserToolWork)) {
            currentCoroutineContext().ensureActive()
            return block()
        }
        // Direct callers must be counted before even the first permission gate.
        val ticket = c.browserToolWork ?: controller.registerToolWork()
        return try {
            withContext(BrowserToolWorkScope(controller, ticket)) {
                controller.withToolWork(ticket, block)
            }
        } catch (changed: BrowserSurfaceChangedException) {
            // A real Agent cancellation still propagates; only browser-owned stop is recoverable.
            currentCoroutineContext().ensureActive()
            buildJsonObject {
                put("success", false)
                put("kind", "browser_unavailable")
                put("recoverable", true)
                put("error", changed.message ?: "Browser work was stopped")
                put("note", "Inspect the current page before retrying. This browser tool was not replayed.")
            }.toString()
        } finally {
            // Also covers cancellation before withContext enters the controller scope.
            controller.releaseToolWork(ticket)
        }
    }

    private fun startManagedCommand(c:Ctx,manager:BackgroundCommandManager,command:String,timeout:Long?,
        disk:AgentDiskExecutionState,keep:Boolean?=null,stop:Boolean=false,tty:Boolean=false):BackgroundCommandManager.Snapshot {
        val inputOpen = AgentTtyPolicy.keepStdinOpen(tty, keep)
        c.sourceCommand?.let {return it.start(manager,command,timeout,disk,inputOpen,stop,tty)}
        return manager.start(command,timeout,disk,inputOpen,stop,tty)
    }

    suspend fun execute(name: String, args: JsonObject, c: Ctx): String {
        val result = withBrowserToolWork(name, c) { executeUnscoped(name, args, c) }
        return if (name == "skill" || name == "rule") AgentContextToolResult.diagnostic(result) else result
    }

    private suspend fun executeUnscoped(name: String, args: JsonObject, c: Ctx): String {
        return try {
        
        val realName = when (name) {
            "bash" -> "run_command"
            "edit" -> "edit_file"
            "write" -> "write_file"
            "read" -> "read_file"
            "grep" -> "search_files"
            else -> name
        }
        
        if (!c.perms.toolAllowed(realName)) return "DENIED (agent profile): tool '$realName' is outside this Agent profile capability set."
        if (McpToolIdentity.isFirstClassName(realName)) {
            AgentModePolicy.check(c.mode, realName)?.let { return it }
            val registry = McpToolRegistry(c.workDir, c.pluginSource, c.mcpProcessHost)
            val descriptor = registry.resolve(realName, c.perms, c.permissionScope)
                ?: return "ERROR: MCP tool definition is stale or unavailable; refresh the Agent tool catalog."
            val manager = McpManager(c.workDir, c.pluginSource, c.mcpProcessHost)
            val selection = manager.select(descriptor.serverName)
            val resource = selection.permissionResource(descriptor.toolName)
            require(resource.startsWith("${descriptor.serverName}/${descriptor.toolName}@cfg:")) {
                "MCP permission identity is invalid for advertised tool."
            }
            require(descriptor.runtimeIdentity == selection.runtimeIdentity) {
                "MCP server configuration changed after tool advertisement. Refresh the Agent tool catalog."
            }
            gate("mcp", resource, c)?.let { return it }
            return manager.callTool(selection, descriptor.toolName, args)
        }
        AgentModePolicy.check(c.mode, realName)?.let { return it }
        if (realName == "tool_search") {
            if (!c.perms.actionVisible("read", c.permissionScope)) return "DENIED: tool discovery is no longer available"
            return c.toolSearch?.invoke(args) ?: "ERROR: tool discovery is unavailable for this request"
        }
        if (realName.startsWith("plugin_")) {
            val manager = AgentPluginToolManager(c.workDir, c.pluginSource)
            val def = manager.discover().firstOrNull { it.functionName == realName } ?: return "ERROR: Agent plugin tool not found: $realName"
            // Snapshot once so review, permission identity and execution cannot diverge
            // if a caller retains mutable backing collections for its JsonObject.
            val payload = args.toString()
            val input = Json.parseToJsonElement(payload).jsonObject
            val resource = manager.permissionResource(def, input)
            val review = inputReviewFactory(ApprovalInputReview.Label.CUSTOM_TOOL, payload,
                "Plugin: ${def.pluginId}\nTool: ${def.name}\nProject: ${c.workDir.absolutePath}\nTool SHA-256: ${def.fingerprint}\nTimeout ms: ${def.timeoutMs}", c)
            gate("shell_escape", resource, c, inputReviewFactory = review)?.let { return it }
            gate("shell", resource, c, inputReviewFactory = review)?.let { return it }
            return manager.execute(def, input, c.terminal)
        }
        if (realName.startsWith("custom_")) {
            val real = realName.removePrefix("custom_")
            val custom = CustomToolManager(c.workDir)
            val fingerprint = custom.permissionFingerprint(real) ?: return "ERROR: custom tool not found: $real"
            

            val input = args["input"]?.jsonPrimitive?.contentOrNull ?: args.toString()
            val review = inputReviewFactory(ApprovalInputReview.Label.CUSTOM_TOOL, input,
                "Tool: $real\nProject: ${c.workDir.absolutePath}\nScript SHA-256: $fingerprint", c)
            gate("shell_escape", ShellContainmentPreflight.customToolEscapeResource(real, fingerprint), c,
                inputReviewFactory = review)?.let { return it }
            gate("shell", "custom:$real@sha:${fingerprint.take(20)} ${args.toString().take(120)}", c,
                sessionGrantResource = "", inputReviewFactory = review)?.let { return it }
            return custom.execute(
                real,
                input,
                terminal = c.terminal,
                expectedFingerprint = fingerprint,
            )
        }

        if (realName in setOf("read_file", "view_image", "view_video", "send_media")) {
            args["path"]?.jsonPrimitive?.contentOrNull?.let { path ->
                AgentMediaTools.requireManagedOwnership(c.workDir, path, c.sessionId)
            }
        }
        AgentToolPermissionPolicy.runtimeRequirements(realName, args, c.sessionId).forEach { requirement ->
            gate(requirement.action, requirement.resource, c)?.let { return it }
        }
        when (realName) {
            "list_files" -> {
                gate("read", args["path"]?.jsonPrimitive?.contentOrNull ?: "", c)
                    ?: c.files.listFiles(args["path"]?.jsonPrimitive?.contentOrNull ?: "",
                        includeDependencies = includeDependencies(args))
                        .joinToString("\n") { (if (it.isDir) "DIR  " else "FILE ") + it.path }
                        .ifEmpty { "(kosong)" }
            }
            "read_file" -> {
                val p = req(args, "path")
                gate("read", p, c) ?: run {
                    val s = args["start"]?.jsonPrimitive?.intOrNull
                    val e = args["end"]?.jsonPrimitive?.intOrNull
                    val offset = args["offset"]?.let {
                        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.intOrNull
                            ?: throw IllegalArgumentException("offset must be an integer")
                    }
                    if (p.startsWith(AgentToolOutputStore.PREFIX)) {
                        require(s == null && e == null) { "Retained output uses offset, not start/end lines" }
                        val owner = requireNotNull(c.sessionId) { "Active conversation required for retained output" }
                        return "[DROIDE_READ_RESULT returned=true]\n" +
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                AgentToolOutputStore.forWorkspace(c.workDir).read(owner, p, offset ?: 0)
                            }
                    }
                    "[DROIDE_READ_RESULT returned=true]\n" + if (p.startsWith(".droide/chat-media/blobs/"))
                        AgentMediaTextReader.read(c.workDir, p, s, e, offset, c.sessionId)
                    else AgentWorkspaceReader(c.files, c.documentAuthority).read(p, s, e, offset)
                }
            }
            "project_instructions" -> {
                val path = req(args, "path")
                val paths = AgentProjectInstructions.scopedPaths(c.files, path)
                for (instruction in paths) gate("read", instruction, c)?.let { return it }
                AgentProjectInstructions.loadScoped(c.files, path, paths)
            }
            "droide_help" -> AgentDroideHelp.execute(args, c) { action, resource -> gate(action, resource, c) }
            "local_deleted_folders", "archive_deleted_folder" -> AgentLocalDirectoryRecovery.execute(realName, args, c,
                readGate = { resource -> gate("read", resource, c) },
                editGate = { resource, summary, detail ->
                    gateWithPreview("edit", resource, "archive_deleted_folder", summary, detail, c)
                })
            "view_image" -> {
                val p = req(args, "path")
                val deliver = c.onImageReady ?: return "ERROR: image delivery is unavailable in this Agent context"
                require(VisionSupport.providerAcceptsImageUrl(c.llmConfig)) { "Selected model does not support image input" }
                gate("read", p, c)?.let { return it }
                if (p.startsWith(".droide/chat-media/blobs/")) {
                    val store = ChatMediaStore(c.workDir)
                    val ref = requireNotNull(store.findByPath(p)) { "Attachment is expired or unavailable" }
                    require(c.sessionId != null && store.ownerRefs(c.sessionId).any { it.id == ref.id }) {
                        "This attachment does not belong to the active conversation"
                    }
                }
                val paths = if (c.appContext != null && c.sessionId != null && p.startsWith(".droide/chat-media/blobs/")) {
                    val ref = requireNotNull(ChatMediaStore(c.workDir).findByPath(p)) { "Attachment is expired or unavailable" }
                    // Uploaded/generated originals may exceed the provider's image budget. Reuse the
                    // same bounded cached image projection as prompt attachments instead of rejecting them.
                    if (p == ref.path) {
                        require(ref.mediaType.startsWith("image/")) { "Use view_video for a video attachment" }
                        ChatMediaProcessing.prepareModelImages(c.workDir, listOf(ref), c.sessionId).imagePaths
                    } else listOf(p)
                } else listOf(p)
                val refs = VisionSupport.stage(c.workDir, paths, c.llmConfig, ownerId = c.sessionId)
                deliver(refs)
                "IMAGE_ATTACHED path=$p media_type=${refs.single().mediaType} bytes=${refs.single().bytes}; " +
                    "actual image data will be delivered with the next model request. This result does not verify image contents."
            }
            "transcribe_audio" -> AgentAudioTranscription().execute(args, c) { action, resource -> gate(action, resource, c) }
            "document", "audio" -> {
                val context = requireNotNull(c.appContext) { "Media authoring is unavailable in this context" }
                MediaAuthoringTools(c.files, c.terminal, { context.assets.open(MediaAuthoringTools.ASSET).bufferedReader().use { it.readText() } },
                    c.documentAuthority, { check(c.mediaRequestCurrent()) { "Media request is no longer current" } },
                    onProducedFile = { c.onProgress(AgentToolProgress(producedFiles = listOf(it))) })
                    .execute(name, args, c.mode, c.sessionId) { action, resource -> gate(action, resource, c) }
            }
            "generate_image" -> (c.imageGeneration ?: AgentImageGeneration()).generate(args, c) { action, resource -> gate(action, resource, c) }
            "view_video" -> AgentMediaTools.viewVideo(args, c) { action, resource -> gate(action, resource, c) }
            "send_media" -> AgentMediaTools.sendMedia(args, c) { action, resource -> gate(action, resource, c) }
            "save_media_to_project" -> AgentMediaTools.saveMediaToProject(args, c) { action, resource -> gate(action, resource, c) }
            "glob" -> {
                val pat = req(args, "pattern")
                gate("glob", pat, c) ?: c.files.glob(pat, includeDependencies = includeDependencies(args))
            }
            "search_files" -> {
                val q = req(args, "query")
                gate("grep", q, c) ?: c.searchResultPages.execute(args, c)
            }
            "write_file" -> {
                val requestedPath = req(args, "path")
                val content = (args["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("Missing or invalid string arg: content")
                require(content.length <= 2_000_000) { "write_file content exceeds 2,000,000 characters" }
                val mutation = AgentWorkspaceMutation(c.files, c.documentAuthority)
                when (val target = mutation.inspect(requestedPath)) {
                    is AgentWorkspaceMutation.Target.Live -> {
                        val expected = mutation.expectedVersion(args) ?: return mutation.versionRequired(target)
                        if (target.snapshot.version != expected) return mutation.versionConflict(target.path, target.snapshot)
                        val change = AgentFileChanges.preview(target.path, target.content, content)
                        gateWithPreview("edit", target.path, "write_file", "Write ${target.path}", change.diff, c, AgentFileChanges.totals(listOf(change)))?.let { return it }
                        val undo = c.history.prepareLive(target.path, target.content)
                        mutation.finalizeLive(
                            c.history, c.lsp, undo,
                            { mutation.replaceLive(target, expected, content, "Agent write") },
                            target.path, "write", requireSaveCurrent = { c.requireSavePermission(target.path, "write_file") },
                        ) { result ->
                            c.onProgress(AgentToolProgress(fileChanges = listOf(change)))
                            "OK: updated live editor buffer ${target.path}; document_id=${result.after.documentId} revision=${result.after.revision} change_version=${result.after.changeVersion}"
                        }
                    }
                    is AgentWorkspaceMutation.Target.Disk -> {
                        if (mutation.expectsLiveDocument(args)) return mutation.liveDocumentUnavailable(target.path)
                        val change = AgentFileChanges.preview(target.path, target.content.takeIf { target.existed }, content)
                        gateWithPreview("edit", target.path, "write_file", "Write ${target.path}", change.diff, c, AgentFileChanges.totals(listOf(change)))?.let { return it }
                        val expectedDisk = if (target.existed) target.content else null
                        val syncPending = writeReviewedDisk(c, target.path, expectedDisk, content)
                        publishDiskChanges(c, listOf(change))
                        "OK: wrote ${target.path}" + savedSyncNote(syncPending)
                    }
                }
            }
            "edit_file" -> {
                val requestedPath = req(args, "path")
                val search = req(args, "search")
                val replace = req(args, "replace")
                require(search.isNotEmpty()) { "search must not be empty" }
                val mutation = AgentWorkspaceMutation(c.files, c.documentAuthority)
                when (val target = mutation.inspect(requestedPath)) {
                    is AgentWorkspaceMutation.Target.Live -> {
                        val expected = mutation.expectedVersion(args) ?: return mutation.versionRequired(target)
                        if (target.snapshot.version != expected) return mutation.versionConflict(target.path, target.snapshot)
                        val occurrences = countOccurrences(target.content, search)
                        if (occurrences != 1) return "ERROR: search must match exactly once in ${target.path} (found $occurrences)"
                        val updated = target.content.replaceFirst(search, replace)
                        val change = AgentFileChanges.preview(target.path, target.content, updated)
                        gateWithPreview("edit", target.path, "edit_file", "Edit ${target.path}", change.diff, c, AgentFileChanges.totals(listOf(change)))?.let { return it }
                        val undo = c.history.prepareLive(target.path, target.content)
                        mutation.finalizeLive(
                            c.history, c.lsp, undo,
                            { mutation.replaceLive(target, expected, updated, "Agent edit") },
                            target.path, "edit", requireSaveCurrent = { c.requireSavePermission(target.path, "edit_file") },
                        ) { result ->
                            c.onProgress(AgentToolProgress(fileChanges = listOf(change)))
                            "OK: 1 replacement(s) in live editor buffer ${target.path}; document_id=${result.after.documentId} revision=${result.after.revision} change_version=${result.after.changeVersion}"
                        }
                    }
                    is AgentWorkspaceMutation.Target.Disk -> {
                        if (mutation.expectsLiveDocument(args)) return mutation.liveDocumentUnavailable(target.path)
                        val occurrences = countOccurrences(target.content, search)
                        if (occurrences != 1) return "ERROR: search must match exactly once in ${target.path} (found $occurrences)"
                        val updated = target.content.replaceFirst(search, replace)
                        val change = AgentFileChanges.preview(target.path, target.content, updated)
                        gateWithPreview("edit", target.path, "edit_file", "Edit ${target.path}", change.diff, c, AgentFileChanges.totals(listOf(change)))?.let { return it }
                        val syncPending = writeReviewedDisk(c, target.path, target.content, updated)
                        publishDiskChanges(c, listOf(change))
                        "OK: 1 replacement(s) in ${target.path}" + savedSyncNote(syncPending)
                    }
                }
            }
            "save_files" -> AgentSaveFilesTool.execute(args, c) { path, content ->
                gateWithPreview("edit", path, "save_files", "Save $path",
                    "Save this exact editor version, including any user draft it contains.\n\n" + content.take(24_000), c)
            }
            "apply_patch" -> {
                val patch = req(args, "patchText")
                // Preserve exact editor identities and existing patch preflight semantics.
                val ops = PatchTool.parse(patch)
                val reviewed = PatchTool.reviewWorkspaceSnapshot(ops, c.files, c.documentAuthority)
                val endpoints = patchEndpointBindings(reviewed.before.keys, c.files)
                val changes = AgentFileChanges.patch(ops, reviewed.before)
                val preview = changes.joinToString("\n") { it.diff }
                // The reviewed snapshot includes every source and move destination, never a clipped label.
                gatePatchWithPreview(endpoints, "Terapkan patch (${ops.size} operasi)", preview, c,
                    AgentFileChanges.totals(changes))?.let { return it }
                // Raw aliases must not redirect approved operations to different canonical endpoints.
                if (patchEndpointBindings(reviewed.before.keys, c.files) != endpoints)
                    return "DENIED (patch targets changed after review): re-run the patch for the current targets"
                PatchTool.applyWorkspaceReviewed(ops, c.files, c.history, c.documentAuthority, c.lsp, reviewed,
                    requireSaveCurrent = { reviewed.before.keys.forEach { c.requireSavePermission(it, "apply_patch") } }).also {
                    publishDiskChanges(c, changes)
                }
            }
            "run_command" -> {
                val cmd = req(args, "command")
                val requiresTty = args["requires_tty"]?.jsonPrimitive?.booleanOrNull == true
                val background = args["background"]?.jsonPrimitive?.booleanOrNull == true
                val requestedTimeout = args["timeout_ms"]?.jsonPrimitive?.longOrNull
                val commandManager = c.backgroundCommands
                val reviewedYieldMs = if (!background && commandManager != null)
                    AgentCommandSessions.yieldMs(args, AgentTimeoutPolicy.COMMAND_YIELD_MS) else null
                val inputReview = inputReviewFactory(ApprovalInputReview.Label.COMMAND, cmd,
                    "Project: ${c.workDir.absolutePath}\nInteractive: $requiresTty\nBackground: $background\nKeep stdin open: $requiresTty\nTimeout ms: ${requestedTimeout ?: "none"}\nYield ms: ${reviewedYieldMs ?: "not used"}", c)
                shellContainmentGate(cmd, c, inputReview)?.let { return it }
                if (requiresTty) gateWithPreview("shell", cmd, "run_command", "Run interactive command in the selected terminal tab", "The human shell is preserved; the owned interactive process will use this tab until it exits.\n\n$cmd", c, inputReviewFactory = inputReview)?.let { return it }
                else gate("shell", cmd, c, inputReviewFactory = inputReview)?.let { return it }
                if (requestedTimeout != null) {
                    require(requestedTimeout in BackgroundCommandManager.MIN_TIMEOUT_MS..BackgroundCommandManager.MAX_TIMEOUT_MS) {
                        "timeout_ms must be between ${BackgroundCommandManager.MIN_TIMEOUT_MS} and ${BackgroundCommandManager.MAX_TIMEOUT_MS}"
                    }
                }
                AgentEditPersistence.commandBlocker(c.documentAuthority)?.let { return it }
                if (background) {
                    val diskState = AgentDiskExecutionState.capture(c.documentAuthority)
                    val manager = commandManager ?: return "ERROR: background command manager unavailable"
                    val job = startManagedCommand(c,manager,cmd,requestedTimeout,diskState,tty=requiresTty)
                    diskState.annotate(
                        "run_command(background)",
                        "BACKGROUND_JOB_STARTED id=${job.id} state=${job.state.name.lowercase()}" +
                            (job.timeoutMs?.let { " timeout_ms=$it" } ?: " timeout=none") +
                            "\nContinue independent work; use background_job(action=\"status\"|\"output\"|\"kill\", job_id=\"${job.id}\") when needed.",
                    ).also { c.sourceCommand?.recordNormalReturn(it) }
                } else {
                    c.onProgress(AgentToolProgress(detail = "Running command"))
                    val manager = commandManager
                    if (requiresTty && manager == null) return "ERROR: Interactive command session manager is unavailable. No command was launched."
                    if (manager == null) c.diskBacked("run_command", reconcileAfter = true, commandEnvelope = true) {
                        // Embedded callers without a session manager still have caller cancellation.
                        val r = c.terminal.execStreaming(cmd, requestedTimeout ?: BackgroundCommandManager.NO_TIMEOUT) { chunk ->
                            c.onProgress(AgentToolProgress(outputDelta = chunk, detail = "Streaming command output"))
                        }
                        "exit=${r.exitCode}\noutput_incomplete=${r.outputIncomplete}\n${r.output}"
                    } else {
                        val yieldMs = requireNotNull(reviewedYieldMs)
                        val diskState = AgentDiskExecutionState.capture(c.documentAuthority)
                        currentCoroutineContext().ensureActive()
                        val job = startManagedCommand(c,manager,cmd,requestedTimeout,diskState,stop=true,tty=requiresTty)
                        val current = try {
                            currentCoroutineContext().ensureActive()
                            manager.awaitCompletion(job.id, yieldMs) { chunk ->
                                c.onProgress(AgentToolProgress(outputDelta = chunk, detail = "Streaming command output"))
                            }
                        } catch (cancelled: CancellationException) {
                            withContext(NonCancellable) { manager.kill(job.id) }
                            throw cancelled
                        }
                        AgentCommandSessions.renderForAgent(current, 0, "run_command",
                            reconciliation = c.documentAuthority?.reconcilePersistedWorkspace(), includeContinuation = true).also { c.sourceCommand?.recordNormalReturn(it) }
                    }
                }
            }
            "exec_command" -> {
                val manager = c.backgroundCommands ?: return "ERROR: background command manager unavailable"
                val cmd = req(args, "cmd")
                val requiresTty = args["requires_tty"]?.jsonPrimitive?.booleanOrNull == true
                val yieldMs = AgentCommandSessions.yieldMs(args)
                val timeout = args["timeout_ms"]?.jsonPrimitive?.longOrNull
                val keepInput = AgentTtyPolicy.keepStdinOpen(requiresTty, args["keep_stdin_open"]?.jsonPrimitive?.booleanOrNull)
                val inputReview = inputReviewFactory(ApprovalInputReview.Label.COMMAND, cmd,
                    "Project: ${c.workDir.absolutePath}\nInteractive: $requiresTty\nKeep stdin open: $keepInput\nTimeout ms: ${timeout ?: "none"}\nYield ms: $yieldMs", c)
                shellContainmentGate(cmd, c, inputReview)?.let { return it }
                if (requiresTty) gateWithPreview("shell", cmd, "exec_command", "Run interactive command in the selected terminal tab", "The human shell is preserved; the owned interactive process will use this tab until it exits.\n\n$cmd", c, inputReviewFactory = inputReview)?.let { return it }
                else gate("shell", cmd, c, inputReviewFactory = inputReview)?.let { return it }
                AgentEditPersistence.commandBlocker(c.documentAuthority)?.let { return it }
                // BackgroundCommandManager.start binds the user-selected tab before
                // checking stdin capability. A separate router preflight can race
                // a tab switch and incorrectly authorize a different backend.
                val diskState = AgentDiskExecutionState.capture(c.documentAuthority)
                currentCoroutineContext().ensureActive()
                val job = startManagedCommand(c,manager,cmd,timeout,diskState,keepInput,stop=true,tty=requiresTty)
                val current = try {
                    currentCoroutineContext().ensureActive()
                    manager.awaitOutput(job.id, 0, yieldMs)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { manager.kill(job.id) }
                    throw cancelled
                }
                AgentCommandSessions.renderForAgent(current, 0, "exec_command",
                    reconciliation = c.documentAuthority?.reconcilePersistedWorkspace()).also { c.sourceCommand?.recordNormalReturn(it) }
            }
            "write_stdin" -> {
                val manager = c.backgroundCommands ?: return "ERROR: background command manager unavailable"
                val id = req(args, "session_id")
                val chars = args["chars"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val closeInput = args["close_stdin"]?.jsonPrimitive?.booleanOrNull ?: false
                val interrupt = args["interrupt"]?.jsonPrimitive?.booleanOrNull ?: false
                val waitForCompletion = args["wait_for_completion"]?.jsonPrimitive?.booleanOrNull ?: false
                require(!interrupt || (chars.isEmpty() && !closeInput)) { "interrupt cannot be combined with stdin input" }
                val cursor = AgentCommandSessions.cursor(args)
                val yieldMs = AgentCommandSessions.yieldMs(args)
                val binding = c.sourceStdin
                val current = if (binding != null) manager.beginSourceStdin(binding, id, chars, closeInput, interrupt)
                    else manager.snapshot(id) ?: return "ERROR: command session not found: $id"
                require(cursor in 0..current.outputEndCursor) { "Invalid session output cursor" }
                val inputReview = inputReviewFactory(ApprovalInputReview.Label.STDIN, chars,
                    "Project: ${c.workDir.absolutePath}\nSession: $id\nClose stdin: $closeInput\nInterrupt: $interrupt\nWait for completion: $waitForCompletion\nYield ms: $yieldMs", c)
                gate("read", "command-session:$id:output", c, inputReviewFactory = inputReview)?.let { return it }
                if (chars.isNotEmpty() || closeInput || interrupt) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(chars.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    val resource = "command-session:$id:input-sha256=$digest:close=$closeInput:interrupt=$interrupt"
                    // Input can complete shell syntax created by a previous turn; it has no independent containment proof.
                    if (chars.isNotEmpty()) gate("shell_escape", resource, c, inputReviewFactory = inputReview)?.let { return it }
                    gate("shell", resource, c, inputReviewFactory = inputReview)?.let { return it }
                    if (binding == null) {
                        if (interrupt) manager.kill(id) else manager.sendInput(id, chars, closeInput)
                    } else {
                        if (interrupt) manager.interruptSource(binding, id)
                        else manager.sendSourceInput(binding, id, chars, closeInput)
                    }
                }
                val result = if (waitForCompletion) {
                    c.onProgress(AgentToolProgress(detail = "Waiting for command completion"))
                    manager.awaitExit(id, cursor, onActivity = { detail -> c.onProgress(AgentToolProgress(detail = detail)) }) { chunk ->
                        c.onProgress(AgentToolProgress(outputDelta = chunk, detail = "Streaming command output"))
                    }
                } else manager.awaitOutput(id, cursor, yieldMs)
                AgentCommandSessions.renderForAgent(result, cursor, "write_stdin", interrupt,
                    reconciliation = c.documentAuthority?.reconcilePersistedWorkspace())
            }
            "environment_probe" -> AgentEnvironmentProbe.run(args, c)
            "ide" -> {
                val request = AgentIdeRequest.parse(args)
                val actions = c.ideActions ?: return "ERROR: IDE action manager unavailable in this Agent context"
                val approvedPython = actions.preparePythonRun(request)
                if (approvedPython != null) {
                    gateWithPreview("ide_execute", approvedPython.permissionResource(request.permissionResource),
                        "ide_run_file", "Run Python file", approvedPython.preview, c)?.let { return it }
                } else gate("ide_execute", request.permissionResource, c)?.let { return it }
                c.onProgress(AgentToolProgress(detail = "IDE ${request.operation.replace('_', ' ')}"))
                if (request.usesDisk && request.reconcileAfter) AgentEditPersistence.commandBlocker(c.documentAuthority)?.let { return it }
                if (request.usesDisk) c.diskBacked("ide:${request.operation}", reconcileAfter = request.reconcileAfter) {
                    actions.execute(request, approvedPython)
                } else actions.execute(request, approvedPython)
            }
            "undo_agent_edit" -> {
                gate("edit", "agent-history:last", c)?.let { return it }
                if (c.history.depth() <= 0) return "ERROR: no tracked Agent edit is available to undo"
                val result = c.history.undo()
                if (result.startsWith("Undo failed", ignoreCase = true)) "ERROR: $result" else result
            }
            "background_job" -> {
                val manager = c.backgroundCommands ?: return "ERROR: background command manager unavailable"
                val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                when (action) {
                    "list" -> manager.list().joinToString("\n") { it.agentSummary() }.ifEmpty { "No background jobs." } + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:list")
                    "status" -> {
                        val id = req(args, "job_id")
                        val job = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        job.agentSummary(includeDiskNotice = true) + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:$id:status")
                    }
                    "output" -> {
                        val id = req(args, "job_id")
                        val job = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        val reconciliation = c.documentAuthority?.reconcilePersistedWorkspace()
                        job.agentSummary(includeDiskNotice = true) + "\n" + job.output.ifBlank { "(no output yet)" } +
                            reconciliation.agentReconciliationNotice("background_job:$id:output")
                    }
                    "kill" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): background process termination requires BUILD mode."
                        val id = req(args, "job_id")
                        val existing = manager.snapshot(id) ?: return "ERROR: background job not found: $id"
                        gate("shell", "background-job:$id:kill:${existing.command.take(200)}", c)?.let { return it }
                        val killed = manager.kill(id) ?: return "ERROR: background job not found: $id"
                        killed.agentSummary() + c.documentAuthority?.reconcilePersistedWorkspace().agentReconciliationNotice("background_job:$id:kill")
                    }
                    else -> "ERROR: invalid background_job action: $action"
                }
            }
            "git_status" -> c.diskBacked("git_status") { c.git.status() }
            "git_branch" -> c.diskBacked("git_branch", reconcileAfter = true) {
                val a = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val name = args["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val permissionResource = when (a) { "checkout" -> "git checkout $name"; "create" -> "git checkout -b $name"; else -> "git branch" }
                gate("shell", permissionResource, c)?.let { return@diskBacked it }
                when (a) { "checkout" -> c.git.checkout(name); "create" -> c.git.checkout(name, create = true); else -> c.git.branches() }
            }
            "git_log" -> c.diskBacked("git_log") { c.git.log(args["max"]?.jsonPrimitive?.intOrNull ?: 20) }
            "git_diff" -> c.diskBacked("git_diff") { c.git.diff().take(12_000) }
            "git_commit" -> c.diskBacked("git_commit", reconcileAfter = true) {
                val message = req(args, "message")
                gate("shell", "git commit -m <message>", c)?.let { return@diskBacked it }
                c.git.commitAll(message)
            }
            "git_pull" -> c.diskBacked("git_pull", reconcileAfter = true) {
                gate("shell", "git pull", c)?.let { return@diskBacked it }
                c.git.pull()
            }
            "git_push" -> c.diskBacked("git_push", reconcileAfter = true) {
                gate("shell", "git push", c)?.let { return@diskBacked it }
                c.git.push()
            }
            "skill" -> SkillToolOperations.execute(args,
                SkillManagement(c.files, c.pluginSource, SkillManagement.personalRoot(c.appContext)), c.mode,
            ) { action, resource -> gate(action, resource, c) }
            "rule" -> {
                val n = req(args, "name")
                gate("skill", "rule:$n", c)?.let { return it }
                val discovery = AgentRuleManager(c.workDir, c.pluginSource).loadWithDiagnostics(n)
                val rule = discovery.values.firstOrNull()
                    ?: return if (discovery.isComplete) "ERROR: rule tidak ada: ${AgentContextToolResult.identifier(n)}"
                    else "ERROR: Rule unavailable: ${AgentContextToolResult.identifier(n)}. ${discovery.notice()} The requested name was not resolved; absence is not confirmed."
                AgentContextToolResult.loaded(rule.name, rule.description, rule.sourceLabel, rule.body, rule.activation.name.lowercase())
            }
            "worktree" -> {
                val a = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val n = args["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val wm = WorktreeManager(c.workDir)
                gate("shell", if (a == "create") "worktree create $n" else "worktree list", c)?.let { return it }
                if (a == "create") wm.create(n) else wm.list().joinToString("\n").ifEmpty { "(belum ada worktree)" }
            }
            "task" -> {
                val manager = c.subagents ?: return "DENIED: subagent depth limit reached or subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: task requires an active parent session"
                val description = req(args, "description").take(160)
                val prompt = req(args, "prompt")
                val type = args["subagent_type"]?.jsonPrimitive?.contentOrNull ?: "general"
                if (c.mode != AgentMode.BUILD && manager.profileMode(type) == AgentMode.BUILD) {
                    return "DENIED (${c.mode} mode): mutating BUILD subagents require BUILD mode; choose a read-only profile."
                }
                gate("subagent", "$type:${description.take(120)}", c)?.let { return it }
                manager.run(
                    parentSessionId = parentSessionId,
                    description = description,
                    prompt = prompt,
                    profileName = type,
                    taskId = args["task_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    background = args["background"]?.jsonPrimitive?.booleanOrNull ?: false,
                    workspace = args["workspace"]?.jsonPrimitive?.contentOrNull,
                    config = c.llmConfig,
                    onProgress = c.onProgress,
                ).take(20_000)
            }
            "task_control" -> {
                val manager = c.subagents ?: return "DENIED: subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: task_control requires an active parent session"
                val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "list"
                val taskId = args["task_id"]?.jsonPrimitive?.contentOrNull
                val message = args["message"]?.jsonPrimitive?.contentOrNull
                gate("subagent", "$action:${taskId ?: "*"}", c)?.let { return it }
                manager.control(parentSessionId, action, taskId, message).take(20_000)
            }
            "explore_codebase" -> {
                val q = req(args, "query")
                val manager = c.subagents ?: return "DENIED: subagent depth limit reached or subagents unavailable in this context."
                val parentSessionId = c.sessionId ?: return "ERROR: explore_codebase requires an active parent session"
                gate("subagent", "explore:${q.take(100)}", c)?.let { return it }
                manager.run(
                    parentSessionId = parentSessionId,
                    description = q.take(80).ifBlank { "Explore codebase" },
                    prompt = q,
                    profileName = "explore",
                    taskId = null,
                    background = false,
                    config = c.llmConfig,
                    onProgress = c.onProgress,
                ).take(20_000)
            }
            "diagnose" -> {
                val p = req(args, "path")
                gate("diagnose", p, c)?.let { return it }
                c.lspResultPages.execute("diagnose", args, c) { c.lsp.diagnose(p) }
            }
            "lsp" -> {
                gate("lsp", args["path"]?.jsonPrimitive?.contentOrNull ?: "*", c)?.let { return it }
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "hover"
                val p = req(args, "path")
                val query = if (op == "workspaceSymbol") {
                    val value = args["query"] as? JsonPrimitive
                    require(value?.isString == true && value.content.isNotBlank() && value.content.length <= 256) {
                        "workspaceSymbol requires a nonblank query string of at most 256 characters; path only selects the language server"
                    }
                    value.content
                } else null
                c.lspResultPages.execute("lsp", args, c) {
                    if (query != null) {
                        val symbols = c.lsp.workspaceSymbols(p, query)
                        check(symbols.take(200).none { it.activationGuard?.accepts() == false }) {
                            "Workspace symbol results became stale; repeat the search"
                        }
                        val header = "Workspace symbol provider returned ${symbols.size} items; completeness is not established.\n"
                        if (symbols.isEmpty()) header + "No matching items were returned; this can also mean unsupported analysis or a stale request. Use search_files for a text search if needed."
                        else LspTextLimit.items(header + symbols.take(200).joinToString("\n") { symbol ->
                            buildJsonObject {
                                put("name", LspTextLimit.characters(symbol.name, 1_024))
                                symbol.kind?.let { put("kind", it) }
                                put("path", LspTextLimit.characters(symbol.path, 4_096))
                                put("line", symbol.line); put("col", symbol.column)
                            }.toString()
                        }, symbols.size, 200)
                    } else c.lsp.lsp(op, p, args["line"]?.jsonPrimitive?.intOrNull ?: 1,
                            args["col"]?.jsonPrimitive?.intOrNull ?: 1)
                }
            }
            "lsp_edit" -> {
                val actions = c.lspEditActions ?: return "ERROR: LSP edit authority unavailable"
                val op = args["operation"]?.jsonPrimitive?.contentOrNull ?: "list_actions"
                val path = req(args, "path")
                val line = args["line"]?.jsonPrimitive?.intOrNull ?: 1
                val col = args["col"]?.jsonPrimitive?.intOrNull ?: 1
                when (op) {
                    "list_actions" -> {
                        gate("lsp", path, c)?.let { return it }
                        actions.listActions(path, line, col)
                    }
                    "apply_action" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): applying LSP edits requires BUILD mode."
                        gate("lsp", path, c)?.let { return it }
                        val prepared = actions.prepareCodeAction(path, line, col, req(args, "title"))
                        gateWithPreview("lsp_edit", prepared.permissionResource, "lsp_edit", prepared.title, prepared.preview, c, AgentFileChanges.totals(prepared.fileChanges))?.let { return it }
                        actions.apply(prepared) { c.requireSavePermission(prepared.permissionResource, "lsp_edit", "lsp_edit") }.also { c.onProgress(AgentToolProgress(fileChanges = prepared.fileChanges)) }
                    }
                    "format" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): LSP formatting mutation requires BUILD mode."
                        gate("lsp", path, c)?.let { return it }
                        val prepared = actions.prepareFormat(path)
                        gateWithPreview("lsp_edit", prepared.permissionResource, "lsp_edit", prepared.title, prepared.preview, c, AgentFileChanges.totals(prepared.fileChanges))?.let { return it }
                        actions.apply(prepared) { c.requireSavePermission(prepared.permissionResource, "lsp_edit", "lsp_edit") }.also { c.onProgress(AgentToolProgress(fileChanges = prepared.fileChanges)) }
                    }
                    else -> "ERROR: unsupported lsp_edit operation: $op"
                }
            }
            "toolchain" -> {
                val actions = c.toolchainActions ?: return "ERROR: managed toolchain authority unavailable"
                actions.execute(args, c.mode, c.perms, c.approvals, c.permissionScope, c.provenance,
                    progress = { c.onProgress(AgentToolProgress(detail = it)) })
            }
            "capability" -> {
                val actions = c.capabilityActions ?: return "ERROR: universal capability authority unavailable"
                when (val op = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "list") {
                    "list" -> actions.list(args["kind"]?.jsonPrimitive?.contentOrNull)
                    "status" -> actions.status(req(args, "tool_id"))
                    "run" -> {
                        if (c.mode != AgentMode.BUILD) return "DENIED (${c.mode} mode): contributed capability execution requires BUILD mode."
                        val prepared = actions.prepareRun(
                            req(args, "tool_id"),
                            args["path"]?.jsonPrimitive?.contentOrNull,
                        )
                        val resource = actions.permissionResource(prepared)
                        gateWithPreview(
                            "shell", resource, "capability_tool",
                            "Run ${prepared.title}", prepared.preview, c,
                        )?.let { return it }
                        actions.run(prepared)
                    }
                    else -> "ERROR: unsupported capability operation: $op"
                }
            }
            "todowrite" -> {
                
                val arr = args["todos"]?.jsonArray
                if (arr == null) return c.todos.snapshot(c.sessionId)
                val list = arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    Todo(
                        content = o["content"]?.jsonPrimitive?.contentOrNull ?: "",
                        status = o["status"]?.jsonPrimitive?.contentOrNull ?: "pending",
                        priority = o["priority"]?.jsonPrimitive?.contentOrNull ?: "medium",
                    )
                }
                c.todos.set(list, c.sessionId)
                "OK: ${list.size} todos"
            }
            "web", "webfetch", "websearch" -> AgentWebTools.execute(realName, args, c) { action, resource -> gate(action, resource, c) }
            "mcp" -> McpAgentOperations.execute(
                args, c,
                gate = { action, resource -> gate(action, resource, c) },
                gatePreview = { action, resource, kind, summary, detail -> gateWithPreview(action, resource, kind, summary, detail, c) },
            )
            "question" -> {
                gate("question", "*", c)?.let { return it }
                val header = args["header"]?.jsonPrimitive?.contentOrNull ?: "Choose"
                val q = args["question"]?.jsonPrimitive?.contentOrNull ?: req(args, "question")
                val opts = args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                c.onProgress(AgentToolProgress(detail = "Waiting for user answer"))
                val ans = c.questions.ask(
                    header,
                    q,
                    opts.ifEmpty { listOf("Yes", "No") },
                    provenance = c.provenance,
                )
                c.onProgress(AgentToolProgress(detail = "User answered"))
                "User answered: $ans"
            }
            "browser" -> {
                val controller = c.browserController ?: return "ERROR: integrated Android browser is unavailable"
                val op = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(java.util.Locale.ROOT) ?: "read"
                require(op in setOf("open", "navigate", "read", "find", "links", "scroll", "status", "console", "dialog",
                    "back", "reload", "wait", "screenshot", "click", "type", "check", "key", "hover", "swipe", "drag", "wait_for", "dialog_respond")) { "Unsupported browser operation" }
                val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim()
                val target = args["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val input = args["text"]?.jsonPrimitive?.contentOrNull
                val submit = args["submit"]?.jsonPrimitive?.booleanOrNull ?: false
                val dialogText = args["dialog_text"]?.jsonPrimitive?.contentOrNull
                val actionOptions = BrowserActionOptions(
                    checked = args["checked"]?.jsonPrimitive?.booleanOrNull,
                    optionIndex = args["option_index"]?.jsonPrimitive?.intOrNull,
                    keyStroke = args["key"]?.jsonPrimitive?.contentOrNull,
                    endTarget = args["end_target"]?.jsonPrimitive?.contentOrNull,
                    timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 4_000,
                    durationMs = args["duration_ms"]?.jsonPrimitive?.longOrNull ?: 350)
                require(op != "check" || actionOptions.checked != null) { "check requires a boolean checked value" }
                if (AgentRepairCompletionPolicy.browserInteracts(op) && c.mode != AgentMode.BUILD) {
                    return "DENIED (${c.mode} mode): interactive browser mutation requires BUILD mode."
                }
                if (op == "dialog_respond") require(input in setOf("accept", "dismiss")) { "dialog_respond text must be accept or dismiss" }
                require(input == null || input.length <= 20_000) { "Browser input is too large" }
                require(dialogText == null || dialogText.length <= AgentBrowserDialogs.MAX_RESPONSE_CHARACTERS) { "Browser dialog response is too large" }
                // Capture before the first approval suspension, including read/local approvals.
                browserUnverifiedOutputDenial(op, c)?.let { return it }
                val reviewIdentity = controller.captureReviewIdentity()
                val fullPage = args["full_page"]?.jsonPrimitive?.booleanOrNull ?: false
                val frozenConsole = if (op == "console") controller.freezeConsole(
                    args["start_line"]?.jsonPrimitive?.intOrNull ?: args["start"]?.jsonPrimitive?.intOrNull ?: 0,
                    args["num_lines"]?.jsonPrimitive?.intOrNull ?: args["num_results"]?.jsonPrimitive?.intOrNull ?: 80,
                    reviewIdentity) else null
                val expectedDocument = reviewIdentity.takeUnless { op in setOf("open", "navigate") }
                val knownDialog = controller.pendingDialog.value
                val reviewedDialog = knownDialog.takeIf { op == "dialog_respond" }
                val effectiveUrl = AgentRepairCompletionPolicy.browserPermissionTarget(op, url, reviewIdentity.url)
                require(effectiveUrl.length <= 8_192) { "Browser URL exceeds 8192 characters" }
                require(target.length <= 20_000) { "Browser target exceeds 20000 characters" }
                fun browserReview(action: String, page: String) = BrowserApprovalContext(
                    operation = op,
                    url = page,
                    target = target.takeIf { it.isNotBlank() },
                    reason = when (action) {
                        "browser_local" -> "Access a development server on this device."
                        "browser_interact" -> when (op) {
                            "type" -> if (submit)
                                "Enter text and submit it on this page." else "Enter text on this page."
                            "dialog_respond" -> if (input == "dismiss") "Dismiss the website's pending dialog without submitting an answer."
                                else "Accept the website's pending dialog." + if (dialogText != null)
                                    " Send the supplied prompt answer (${dialogText.length} characters; content hidden from this preview)." else ""
                            else -> "Interact with an element on this page."
                        }
                        else -> "Read this browser page or its output."
                    },
                )
                val readResource = effectiveUrl.ifBlank { "browser:$op" }
                val loopback = AgentRepairCompletionPolicy.classifyBrowserTarget(effectiveUrl) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP
                val interactionResource = if (AgentRepairCompletionPolicy.browserInteracts(op))
                    BrowserInteractionAuthority.policyResource(effectiveUrl, op, target) else null
                val consoleSources = frozenConsole?.observation?.sources?.map { it.url }.orEmpty().distinct()
                val requiredPermissions = browserKnownPermissions(op, effectiveUrl, target, knownDialog) +
                    browserSourcePermissions(consoleSources) + if (op == "drag")
                        browserKnownPermissions(op, effectiveUrl, requireNotNull(actionOptions.endTarget), null) else emptyList()
                browserPermissionDenial(requiredPermissions, c)?.let { return it }
                browserDialogReviewError(op, target, reviewedDialog)?.let { return it }
                val reviewStillCurrent: () -> Boolean = {
                    c.browserRequestCurrent() && browserPermissionDenial(requiredPermissions, c) == null &&
                        browserUnverifiedOutputDenial(op, c) == null &&
                        (expectedDocument == null || controller.isReviewIdentityCurrent(expectedDocument)) &&
                        (reviewedDialog == null || controller.pendingDialog.value == reviewedDialog)
                }
                suspend fun reviewPermission(action: String, resource: String, sessionResource: String = resource): String? {
                    // Check every known requirement before each prompt, including after
                    // another approval suspended. One ASK must never mask a later DENY.
                    browserPermissionDenial(requiredPermissions, c)?.let { return it }
                    val result = gate(action, resource, c, sessionResource,
                        browserReview(action, effectiveUrl), reviewStillCurrent)
                    return browserPermissionDenial(requiredPermissions, c) ?: result
                }
                browserPermissionDenial(requiredPermissions, c)?.let { return it }
                if (interactionResource != null) {
                    reviewPermission("browser_interact", interactionResource,
                        BrowserInteractionAuthority.sessionResource(reviewIdentity.authorityKey, effectiveUrl, op, target, input, submit, dialogText))?.let { return it }
                }
                reviewPermission("webfetch", readResource, BrowserSessionApprovalScope.browserReadGrantResource(readResource))?.let { return it }
                if (loopback) reviewPermission("browser_local", effectiveUrl)?.let { return it }
                // The native dialog's requesting URL is a known source, not a claim
                // that the top-level document owns every frame. Review both scopes.
                reviewedDialog?.sourceUrl?.takeIf { it != effectiveUrl }?.let { source ->
                    val sourceInteraction = BrowserInteractionAuthority.policyResource(source, op, target)
                    browserPermissionDenial(requiredPermissions, c)?.let { return it }
                    gate("browser_interact", sourceInteraction, c,
                        BrowserInteractionAuthority.sessionResource(reviewIdentity.authorityKey, source, op, target, input, submit, dialogText),
                        browserReview("browser_interact", source), reviewStillCurrent)?.let { return it }
                    browserPermissionDenial(requiredPermissions, c)?.let { return it }
                    gate("webfetch", source, c, BrowserSessionApprovalScope.browserReadGrantResource(source),
                        browserReview("webfetch", source), reviewStillCurrent)?.let { return it }
                    if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP) {
                        browserPermissionDenial(requiredPermissions, c)?.let { return it }
                        gate("browser_local", source, c, browserContext = browserReview("browser_local", source),
                            approvalValid = reviewStillCurrent)?.let { return it }
                    }
                }
                for (source in consoleSources.filter { it != effectiveUrl }) {
                    // Site-session consent covers captured embedded output, not future navigation/control of that origin.
                    // Concrete DENY rules remain in requiredPermissions before dispatch and publication.
                    if (BrowserSiteSession.resource(effectiveUrl)?.let { resource ->
                        c.approvals.hasSessionApproval(c.provenance.sessionId.ifBlank { c.sessionId.orEmpty() },
                            c.permissionScope, BrowserSiteSession.ACTION, resource)
                    } == true) continue
                    gate("webfetch", source, c, BrowserSessionApprovalScope.browserReadGrantResource(source),
                        browserReview("webfetch", source), reviewStillCurrent)?.let { return it }
                    if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP)
                        gate("browser_local", source, c, browserContext = browserReview("browser_local", source),
                            approvalValid = reviewStillCurrent)?.let { return it }
                }
                if (!reviewStillCurrent()) return "DENIED: browser policy, request, document or dialog changed during review; inspect again."
                if (requiredPermissions.any { (action, resource) -> !ApprovalPolicyGuard.remainsPermitted(c.perms, action, resource, c.permissionScope) }) {
                    return "DENIED (policy changed during review): browser operation was not dispatched"
                }
                val outputConsent = if (op in setOf("screenshot", "console")) BrowserOutputConsent.create(c.perms,
                    BrowserOutputConsent.Request(requireNotNull(c.sessionId), c.browserRunId, c.browserCallId,
                        c.permissionScope, reviewIdentity, op, fullPage = op == "screenshot" && fullPage,
                        consoleIdentity = frozenConsole?.identity),
                    requestCurrent = { c.browserRequestCurrent() && browserPermissionDenial(requiredPermissions, c) == null },
                    documentCurrent = { controller.isReviewIdentityCurrent(reviewIdentity) }) else null
                var consentPublished = false
                try {
                    if (outputConsent != null) {
                        val scope = if (frozenConsole != null)
                            "Selected console snapshot, lines ${frozenConsole.start} to ${frozenConsole.end} (end exclusive). Reported source URLs are diagnostics, not certified frame identities."
                        else if (fullPage)
                            "Bounded vertical full-page screenshot at viewport width. Capture limits apply; live content may change and fixed overlays may repeat."
                        else "Visible browser viewport screenshot. Live content may change."
                        val approval = c.approvals.withBrowserValidity(
                            check = { reviewStillCurrent() && outputConsent.validForApproval() },
                            admission = { controller.sampleCurrentUrlForPreflight() == reviewIdentity.url &&
                                reviewStillCurrent() && outputConsent.validForApproval() }) {
                            c.approvals.requestApproval(
                                kind = "browser_unverified_output", summary = "Allow one browser $op output?",
                                detail = "$scope\n${BrowserOutputConsent.DISCLOSURE}",
                                action = "webfetch", resource = readResource, permissionScope = c.permissionScope,
                                provenance = c.provenance,
                                approvalSessionId = c.provenance.sessionId.ifBlank { c.sessionId.orEmpty() },
                                sessionGrantAction = BrowserSiteSession.ACTION,
                                sessionGrantResource = BrowserSiteSession.resource(effectiveUrl).orEmpty(),
                                browserContext = BrowserApprovalContext(op, effectiveUrl,
                                    reason = "Allow this output. Session choice: ${BrowserSiteSession.DESCRIPTION}", outputScope = scope,
                                    sessionAccess = BrowserSiteSession.resource(effectiveUrl) != null),
                                isStillCurrent = { reviewStillCurrent() && outputConsent.validForApproval() })
                        }
                        if (approval is ApprovalDecision.Denied || !outputConsent.approve()) return BrowserOutputConsent.WITHHELD
                        bindBrowserSiteGrant(c, effectiveUrl)
                    }
                    c.browserPublication.capture {
                        if (!c.browserRequestCurrent() || browserPermissionDenial(requiredPermissions, c) != null ||
                            outputConsent?.validForDispatch() == false)
                            "DENIED (policy or request changed): Browser output was withheld; do not replay the action automatically."
                        else null
                    }
                    val observation = try { kotlinx.coroutines.withContext(actionOptions) { controller.executeObserved(
                        operation = op,
                        url = url,
                        expectedCurrentUrl = effectiveUrl.takeIf { op !in setOf("open", "navigate") },
                        expectedDocument = expectedDocument,
                        expectedDialog = reviewedDialog,
                        validateAuthority = {
                            c.browserPublication.denial()?.let { throw BrowserPublicationRevokedException(it) }
                        },
                        target = target,
                        text = input,
                        submit = submit,
                        waitMs = (args["wait_ms"]?.jsonPrimitive?.longOrNull ?: 150L).coerceIn(50L, 10_000L),
                        allowLoopback = loopback,
                        start = args["start_line"]?.jsonPrimitive?.intOrNull ?: args["start"]?.jsonPrimitive?.intOrNull ?: 0,
                        count = args["num_lines"]?.jsonPrimitive?.intOrNull ?: args["num_results"]?.jsonPrimitive?.intOrNull ?: 80,
                        query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        elementStart = args["element_start"]?.jsonPrimitive?.intOrNull ?: 0,
                        elementCount = args["element_count"]?.jsonPrimitive?.intOrNull ?: 30,
                        dialogText = dialogText,
                        fullPage = fullPage,
                        outputConsent = outputConsent,
                        frozenConsole = frozenConsole,
                        reviewedLoopbackOrigin = BrowserSessionApprovalScope.canonicalOrigin(effectiveUrl).takeIf { loopback },
                        mediaOwnerId = c.sessionId,
                    ) } } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (changed: BrowserSurfaceChangedException) {
                        throw changed
                    } catch (revoked: BrowserPublicationRevokedException) {
                        return revoked.message.orEmpty()
                    } catch (failure: Exception) {
                        // Renderer/JavaScript exception strings may contain unreviewed page
                        // data. Diagnostics stay generic until a separately authorized read.
                        return "ERROR: browser operation could not complete (${failure.javaClass.simpleName}); inspect the active page before retrying. It was not replayed."
                    }
                    var published = false
                    var retainedMediaOwner: String? = null
                    try {
                        browserObservationGate(observation, readResource, loopback, c, op, target, requiredPermissions,
                            (listOfNotNull(reviewedDialog?.sourceUrl) + consoleSources).distinct())?.let { return it }
                        // Capture immutable operation and observed-source scopes before any
                        // retained-media callback or outer post-tool hook can suspend.
                        c.browserPublication.capture {
                            if (!c.browserRequestCurrent() || browserPermissionDenial(requiredPermissions, c) != null)
                                "DENIED (policy or request changed): Browser output was withheld; do not replay the action automatically."
                            else browserObservationPolicyCheck(observation, readResource, c, op)
                        }
                        c.browserPublication.denial()?.let { return it }
                        val result = observation.payload
                        if (op == "screenshot" && c.sessionId != null && observation.media != null) {
                            retainedMediaOwner = c.sessionId
                            controller.retainObservationMedia(observation, c.sessionId)
                            // Disk ownership transfer can suspend; policy must still permit
                            // this immutable source before text or media becomes visible.
                            c.browserPublication.denial()?.let { return it }
                            currentCoroutineContext().ensureActive()
                            c.onMediaReady?.invoke(observation.media)
                            c.appContext?.let { ChatMediaCleanup.schedule(it, c.workDir) }
                        }
                        published = true
                        consentPublished = true
                        result
                    } finally {
                        if (!published) controller.discardObservation(observation, retainedMediaOwner)
                        else if (retainedMediaOwner != null) controller.discardObservation(observation)
                    }
                } finally {
                    if (!consentPublished) outputConsent?.invalidate()
                }
            }
            "artifact" -> {
                gate("edit", req(args, "type"), c)?.let { return it }
                val type = req(args, "type")
                val content = req(args, "content")
                val art = ArtifactManager(c.workDir).create(type, content)
                try { c.onProgress(AgentToolProgress(producedFiles = listOf(".droide/artifacts/${art.id}.md"))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The artifact remains published even if its display detached. */ }
                "Created artifact ${art.id} ($type) at ${art.file.absolutePath}"
            }
            else -> "Unknown tool: $name"
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (revoked: BrowserPublicationRevokedException) {
        revoked.message.orEmpty()
    } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    /** Match the reviewed payload under the repository lock, including after permission callbacks. */
    private suspend fun writeReviewedDisk(c: Ctx, path: String, before: String?, after: String): Boolean {
        val expectedBefore = DiskFileVersion.fromText(before)
        val expectedAfter = DiskFileVersion.fromText(after)
        val observation = c.files.prepareTextWriteObservation(path, after, expectedBefore)
        // Approval and hooks can suspend after this invocation captured its original root.
        c.textEditCall?.requirePreparationOwner()
        val pending = c.history.prepareBatch("edit $path", mapOf(path to before))
        var failure: Throwable? = null
        try {
            c.textEditCall?.admit(observation, pending.snapshots.single())
            c.files.writeTextIfUnchanged(observation)
        } catch (error: Throwable) {
            failure = error
        }
        if (failure != null && failure !is Exception) throw failure // Preserve backup and fatal identity.
        val metadataFailure = c.textEditCall?.finish(observation, failure)
        if (metadataFailure != null) {
            if (failure != null && failure !== metadataFailure) failure.addSuppressed(metadataFailure)
            else failure = metadataFailure
        }
        val committed = observation.disposition == FileRepository.TextWriteDisposition.OWNED_CURRENT
        // The repository captured this disposition before releasing its original guard.
        // Matching bytes alone never establish a historical commit, and Stop never waits
        // in NonCancellable to acquire a second mutation owner's root lock.
        when (observation.disposition) {
            FileRepository.TextWriteDisposition.OWNED_CURRENT -> c.history.commitBatch(pending, diskAfter = mapOf(path to after))
            FileRepository.TextWriteDisposition.UNCHANGED_WITHOUT_WRITE ->
                withContext(NonCancellable) { c.history.discardBatch(pending) }
            else -> {
                val recovery = "File publication or current ownership could not be verified; recovery backup retained at ${pending.snapshots.single().backup.path}"
                if (failure is CancellationException) failure.addSuppressed(IllegalStateException(recovery))
                else {
                    val detail = if (failure is ProjectFileSyncException) "Write publication was not verified for $path"
                        else failure?.message ?: "File state changed during the write"
                    throw IllegalStateException("$detail; $recovery", failure)
                }
            }
        }
        if (failure is ProjectFileSyncException && failure.relativePath == path &&
            failure.bytes == expectedAfter.bytes && committed && metadataFailure == null) return true
        if (failure != null) {
            // A callback can throw this type before publication. Its message must not
            // manufacture saved-local status when there is no attributable receipt.
            if (failure is ProjectFileSyncException && !committed)
                throw IllegalStateException("Write publication was not verified for $path", failure)
            throw failure
        }
        check(committed) { "Write publication was not verified for $path" }
        return false
    }

    private fun savedSyncNote(syncPending: Boolean): String = if (syncPending)
        "; saved locally, synchronization pending. Use the existing project file; do not repeat this write." else ""

    private fun publishDiskChanges(c: Ctx, changes: List<AgentFileChange>) {
        try {
            c.onProgress(AgentToolProgress(fileChanges = changes))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The canonical file and its undo entry are committed; a detached UI is not a failed edit.
        }
    }


    private fun patchEndpointBindings(paths: Set<String>, files: FileRepository): Map<String, String> {
        require(paths.isNotEmpty() && paths.size <= 200) { "Invalid patch endpoint set" }
        val root = files.root.canonicalFile.toPath()
        return paths.associateWith { value ->
            val normalized = value.replace('\\', '/')
            // Match FileRepository.normalizeRel's existing input rejection before resolving aliases.
            require(normalized.split('/').none { it == "." || it == ".." }) { "Dot path segments are not allowed: $value" }
            root.relativize(files.resolveChecked(normalized).toPath())
                .toString().replace(java.io.File.separatorChar, '/')
                .also { require(it.isNotBlank()) { "Patch target is the project root" } }
        }
    }

    /** One proposal, all concrete targets. A display label never carries edit authority. */
    private suspend fun gatePatchWithPreview(
        endpoints: Map<String, String>, summary: String, detail: String, c: Ctx, totals: AgentChangeTotals?,
    ): String? {
        // At most 200 reviewed endpoints, each with its raw editor identity and canonical disk target.
        val targets = (endpoints.keys + endpoints.values).distinct().sorted()
        require(targets.isNotEmpty() && targets.size <= 400) { "Invalid patch target set" }
        val decisions = targets.map { it to c.perms.decide("edit", it, c.permissionScope) }
        decisions.firstOrNull { it.second == PermEffect.DENY }?.let { return "DENIED (policy): edit ${it.first}" }
        if (decisions.none { it.second == PermEffect.ASK }) return null

        // Preserve legitimate single-file edit grants only when normalization is lossless.
        // Multi-target identity is namespaced so no real filename can collide with its digest.
        val ordinaryTarget = targets.singleOrNull()?.takeIf { target ->
            target == target.trim() && target.length <= 4_096 &&
                '\u0000' !in target && '\n' !in target && '\r' !in target
        }
        val grantAction = if (ordinaryTarget != null) "edit" else "apply_patch"
        val grantResource = ordinaryTarget ?: java.security.MessageDigest.getInstance("SHA-256")
            .digest(JsonArray(targets.map(::JsonPrimitive)).toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val label = targets.joinToString(", ").take(500)
        c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
        val decision = c.approvals.requestApproval(
            "apply_patch", summary, detail, action = "edit", resource = label,
            permissionScope = c.permissionScope, provenance = c.provenance,
            sessionGrantAction = grantAction, sessionGrantResource = grantResource,
            proposedChangeTotals = totals,
        )
        if (decision is ApprovalDecision.Denied) {
            c.onProgress(AgentToolProgress(detail = "Approval denied"))
            return "DENIED oleh user — batalkan edit $label"
        }
        // Includes cached manager grants. Approval never overrides a newly denied endpoint.
        val denied = targets.firstOrNull { c.perms.decide("edit", it, c.permissionScope) == PermEffect.DENY }
        if (denied != null) {
            c.onProgress(AgentToolProgress(detail = "Approval revoked by updated policy"))
            return "DENIED (policy changed after approval): edit $denied"
        }
        c.onProgress(AgentToolProgress(detail = "Approved · continuing"))
        return null
    }

    // Permission gate that uses the mutation preview as the single user approval prompt.
    private suspend fun gateWithPreview(
        action: String, resource: String, kind: String, summary: String, detail: String, c: Ctx,
        proposedChangeTotals: AgentChangeTotals? = null,
        inputReviewFactory: (() -> ApprovalInputReview)? = null,
    ): String? = when (c.perms.decide(action, resource, c.permissionScope)) {
        PermEffect.ALLOW -> null
        PermEffect.DENY -> "DENIED (policy): $action $resource"
        PermEffect.ASK -> {
            c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
            val dec = c.approvals.requestApproval(
                kind, summary, detail, action = action, resource = resource,
                permissionScope = c.permissionScope, provenance = c.provenance,
                proposedChangeTotals = proposedChangeTotals,
                inputReview = inputReviewFactory?.invoke(),
            )
            if (dec is ApprovalDecision.Denied) {
                c.onProgress(AgentToolProgress(detail = "Approval denied"))
                "DENIED oleh user — batalkan $action $resource"
            } else if (!ApprovalPolicyGuard.remainsPermitted(c.perms, action, resource, c.permissionScope)) {
                c.onProgress(AgentToolProgress(detail = "Approval revoked by updated policy"))
                "DENIED (policy changed after approval): $action $resource"
            } else {
                c.onProgress(AgentToolProgress(detail = "Approved · continuing"))
                null
            }
        }
    }

    // pre-execution containment permission order.


    /** Capture original immutable input and owner once; hashing/reader creation occurs only ASK. */
    private suspend fun inputReviewFactory(label: ApprovalInputReview.Label, text: String,
                                           options: String, c: Ctx): () -> ApprovalInputReview {
        val job = currentCoroutineContext().job
        val owner = c.localRecoveryOwner
        return { ApprovalInputReview.text(label, text, options) {
            job.ensureActive()
            owner?.requireCurrent()
        } }
    }

    private suspend fun shellContainmentGate(command: String, c: Ctx, inputReviewFactory: (() -> ApprovalInputReview)? = null): String? {
        val result = ShellContainmentPreflight.analyze(command, c.workDir, c.workDir)
        result.externalDirectories.forEach { boundary ->
            gate("external_directory", boundary, c, inputReviewFactory = inputReviewFactory)?.let { return it }
        }
        if (result.requiresShellEscape) {
            gate("shell_escape", ShellContainmentPreflight.shellEscapeResource(command), c, inputReviewFactory = inputReviewFactory)?.let { return it }
        }
        return null
    }

    internal suspend fun permissionGateForProbe(action: String, resource: String, c: Ctx): String? = gate(action, resource, c)

    /** Read-only preflight: no mount, DNS, approval, session grant or native operation. */
    internal fun browserRequestPreflight(name: String, args: JsonObject, c: Ctx, sampledCurrentUrl: String? = null): String? {
        if (name != "browser") return null
        val controller = c.browserController ?: return "ERROR: integrated Android browser is unavailable"
        return try {
            val operation = args["operation"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(java.util.Locale.ROOT) ?: "read"
            val target = args["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = AgentRepairCompletionPolicy.browserPermissionTarget(operation,
                args["url"]?.jsonPrimitive?.contentOrNull?.trim(), sampledCurrentUrl ?: controller.currentUrl())
            val dialog = controller.pendingDialog.value
            browserUnverifiedOutputDenial(operation, c) ?:
            browserPermissionDenial(browserKnownPermissions(operation, url, target, dialog) +
                browserKnownConsolePermissions(operation, args, controller), c)
                ?: browserDialogReviewError(operation, target, dialog)
        } catch (invalid: IllegalArgumentException) {
            "ERROR: invalid browser request (${invalid.message})"
        }
    }

    private fun browserKnownConsolePermissions(operation: String, args: JsonObject,
        controller: AgentBrowserController): List<Pair<String, String>> {
        if (operation != "console") return emptyList()
        val start = args["start_line"]?.jsonPrimitive?.intOrNull ?: args["start"]?.jsonPrimitive?.intOrNull ?: 0
        val count = args["num_lines"]?.jsonPrimitive?.intOrNull ?: args["num_results"]?.jsonPrimitive?.intOrNull ?: 80
        return browserSourcePermissions(controller.cachedConsoleSourceUrls(start, count))
    }

    private fun browserSourcePermissions(sources: List<String>): List<Pair<String, String>> = sources.flatMap { source ->
        buildList {
            add("webfetch" to source)
            if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP)
                add("browser_local" to source)
        }
    }

    private fun browserUnverifiedOutputDenial(operation: String, c: Ctx): String? =
        if (operation in setOf("screenshot", "console") && c.perms.browserOutputAuthority(c.permissionScope).unresolvedDeny)
            "DENIED: unverified browser contributors could match an applicable read/local DENY. Output was withheld."
        else null

    private fun browserDialogReviewError(operation: String, target: String, dialog: AgentBrowserDialogs.Dialog?): String? {
        if (operation != "dialog_respond") return null
        if (dialog == null || dialog.id != target) return "DENIED: browser dialog changed or was already answered; inspect the pending dialog again."
        if (dialog.sourceUrl == null || AgentRepairCompletionPolicy.classifyBrowserTarget(dialog.sourceUrl) == AgentRepairCompletionPolicy.BrowserTargetClass.BLOCKED)
            return "DENIED: the requesting dialog source is unknown or unsupported for Agent response. Answer it directly in the visible browser."
        return null
    }

    private fun browserKnownPermissions(operation: String, url: String, target: String,
        dialog: AgentBrowserDialogs.Dialog?): List<Pair<String, String>> = buildList {
        for (source in (listOf(url) + listOfNotNull(dialog?.sourceUrl)).distinct()) {
            add("webfetch" to source.ifBlank { "browser:$operation" })
            if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP)
                add("browser_local" to source)
            if (AgentRepairCompletionPolicy.browserInteracts(operation) && (source == url || operation == "dialog_respond"))
                add("browser_interact" to BrowserInteractionAuthority.policyResource(source, operation, target))
        }
    }

    /** Full concrete resources stay authoritative even when an ASK has a reusable grant. */
    private fun browserPermissionDenial(required: List<Pair<String, String>>, c: Ctx): String? =
        required.firstOrNull { (action, resource) ->
            c.perms.decide(action, resource, c.permissionScope) == PermEffect.DENY
        }?.let { (action, resource) -> "DENIED (policy): $action $resource" }

    /** The publication boundary accepts captured provenance, never mutable controller state. */
    internal suspend fun browserObservationGate(observation: AgentBrowserController.Observation,
        reviewedResource: String, reviewedLocal: Boolean, c: Ctx, operation: String, target: String? = null,
        operationPermissions: List<Pair<String, String>> = emptyList(),
        reviewedAdditionalSources: List<String> = emptyList()): String? {
        val withheld = "Browser output was withheld. The action may already have taken effect; do not replay it automatically."
        val sources = observation.sources.map { it.url }.distinct()
        val requiredPermissions = operationPermissions + (sources + reviewedResource).distinct().flatMap { source ->
            buildList {
                add("webfetch" to source)
                if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP) {
                    add("browser_local" to source)
                }
            }
        }
        browserPermissionDenial(requiredPermissions, c)?.let { return "$it\n$withheld" }
        if (operation in setOf("screenshot", "console") && observation.outputConsent?.permits(observation) != true)
            return BrowserOutputConsent.WITHHELD
        if (observation.hasUnknownSource && (operation !in setOf("screenshot", "console") || observation.outputConsent?.permits(observation) != true)) return "DENIED: browser output has an unknown document source. Inspect the source in the visible browser. $withheld"
        val approvalValid = { c.browserRequestCurrent() && browserPermissionDenial(requiredPermissions, c) == null }
        fun review(source: String) = BrowserApprovalContext(operation, source, target, "Read this captured browser output.")
        for (source in sources) {
            if (source != reviewedResource && source !in reviewedAdditionalSources) {
                browserPermissionDenial(requiredPermissions, c)?.let { return "$it\n$withheld" }
                gate("webfetch", source, c, BrowserSessionApprovalScope.browserReadGrantResource(source), review(source), approvalValid)?.let { return "${browserPermissionDenial(requiredPermissions, c) ?: it}\n$withheld" }
            }
            if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP &&
                (!reviewedLocal || source != reviewedResource) && source !in reviewedAdditionalSources) {
                browserPermissionDenial(requiredPermissions, c)?.let { return "$it\n$withheld" }
                gate("browser_local", source, c, browserContext = review(source), approvalValid = approvalValid)?.let { return "${browserPermissionDenial(requiredPermissions, c) ?: it}\n$withheld" }
            }
        }
        // A later source approval can suspend. Recheck all sources against DENY
        // immediately before the caller publishes text or screenshot media.
        return browserObservationPolicyCheck(observation, reviewedResource, c, operation)
    }

    private fun browserObservationPolicyCheck(observation: AgentBrowserController.Observation,
        reviewedResource: String, c: Ctx, operation: String): String? {
        if (operation in setOf("screenshot", "console") && observation.outputConsent?.permits(observation) != true)
            return BrowserOutputConsent.WITHHELD
        val withheld = "Browser output was withheld. The action may already have taken effect; do not replay it automatically."
        for (source in (observation.sources.map { it.url } + reviewedResource).distinct()) {
            if (!ApprovalPolicyGuard.remainsPermitted(c.perms, "webfetch", source, c.permissionScope)) {
                return "DENIED (policy changed): $withheld"
            }
            if (AgentRepairCompletionPolicy.classifyBrowserTarget(source) == AgentRepairCompletionPolicy.BrowserTargetClass.LOOPBACK_HTTP &&
                !ApprovalPolicyGuard.remainsPermitted(c.perms, "browser_local", source, c.permissionScope)) {
                return "DENIED (policy changed): $withheld"
            }
        }
        return null
    }

    // Permission gate → return String bila diblokir/ditolak (untuk langsung return), else null.
    private suspend fun gate(action: String, resource: String, c: Ctx, sessionGrantResource: String = resource,
                             browserContext: BrowserApprovalContext? = null, approvalValid: (() -> Boolean)? = null,
                             inputReviewFactory: (() -> ApprovalInputReview)? = null): String? {
        if (approvalValid?.invoke() == false) return "DENIED: browser document changed or request, dialog or policy became invalid during permission review; inspect the page and request fresh approval."
        return when (c.perms.decide(action, resource, c.permissionScope)) {
            PermEffect.ALLOW -> null
            PermEffect.DENY -> "DENIED (policy): $action $resource"
            PermEffect.ASK -> {
                val siteResource = browserContext?.url?.let(BrowserSiteSession::resource)
                val sessionId = c.provenance.sessionId.ifBlank { c.sessionId.orEmpty() }
                if (siteResource == null || !c.approvals.hasSessionApproval(sessionId, c.permissionScope, BrowserSiteSession.ACTION, siteResource))
                    c.onProgress(AgentToolProgress(detail = "Waiting for approval"))
                val dec = c.approvals.requestApproval(
                    if (action == "shell") "run_command" else action,
                    "$action: ${resource.take(120)}", resource.take(2_000),
                    action = action, resource = resource, permissionScope = c.permissionScope,
                    provenance = c.provenance, approvalSessionId = sessionId,
                    sessionGrantAction = if (siteResource != null) BrowserSiteSession.ACTION else action,
                    sessionGrantResource = siteResource ?: sessionGrantResource,
                    browserContext = if (siteResource != null) browserContext.copy(sessionAccess = true,
                        reason = browserContext.reason + " Session choice: " + BrowserSiteSession.DESCRIPTION) else browserContext,
                    isStillCurrent = approvalValid, inputReview = inputReviewFactory?.invoke())
                if (approvalValid?.invoke() == false) {
                    c.onProgress(AgentToolProgress(detail = "Browser document changed; fresh review required"))
                    "DENIED: browser document changed or request, dialog or policy became invalid during permission review; inspect the page and request fresh approval."
                } else if (dec is ApprovalDecision.Denied) {
                    c.onProgress(AgentToolProgress(detail = "Approval denied"))
                    "DENIED oleh user — batalkan $action $resource"
                } else if (!ApprovalPolicyGuard.remainsPermitted(c.perms, action, resource, c.permissionScope)) {
                    c.onProgress(AgentToolProgress(detail = "Approval revoked by updated policy"))
                    "DENIED (policy changed after approval): $action $resource"
                } else {
                    if (siteResource != null) bindBrowserSiteGrant(c, requireNotNull(browserContext).url)
                    c.onProgress(AgentToolProgress(detail = "Approved · continuing"))
                    null
                }
            }
        }
    }

    private fun bindBrowserSiteGrant(c: Ctx, url: String) {
        val resource = BrowserSiteSession.resource(url) ?: return
        c.approvals.sessionApprovalValidity(c.provenance.sessionId.ifBlank { c.sessionId.orEmpty() },
            c.permissionScope, BrowserSiteSession.ACTION, resource)?.let(c.browserPublication::bindSessionGrant)
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val i = text.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }

    private fun includeDependencies(args: JsonObject): Boolean = args["include_dependencies"]?.let {
        (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull
            ?: throw IllegalArgumentException("include_dependencies must be a boolean")
    } ?: false

    private fun req(args: JsonObject, k: String) =
        args[k]?.jsonPrimitive?.contentOrNull ?: throw IllegalArgumentException("Missing arg: $k")
}
