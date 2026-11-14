package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.*

/** Exact fixed-toolchain declaration. Root inspection never executes a project or probes a tool. */
internal object LspNimProfile {
    const val ID = "nimlangserver"
    const val COMMAND = "nimlangserver"
    const val VERSION = "1.16.0"
    const val NIM_VERSION = "2.2.12"
    const val IDENTITY = "nimlangserver-1.16.0-nim-2.2.12-fixed-analysis-v1"
    const val ROOT_POLICY = "nim-nearest-typed-marker-exact-unavailable-parent-one-manifest-v1"
    const val REQUEST_POLICY = "nim-owned-10s-absolute-16-latest-completion-signature-hover-v1"
    const val MAX_ANCESTORS = 128
    const val MAX_DIRECTORY_ENTRIES = 4096
    const val MAX_CONFIGURATION_ITEMS = 32
    val extensions = setOf("nim")
    val rootFiles = listOf("nim.cfg", "config.nims")

    fun supportsPath(path: String): Boolean = path.replace('\\', '/').substringAfterLast('/').endsWith(".nim")

    private enum class Kind { ABSENT, FILE, DIRECTORY, INVALID }
    private fun kind(file: File): Kind = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attrs.isSymbolicLink || !Files.isReadable(file.toPath()) -> Kind.INVALID
            attrs.isRegularFile -> Kind.FILE
            attrs.isDirectory -> Kind.DIRECTORY
            else -> Kind.INVALID
        }
    } catch (_: NoSuchFileException) { Kind.ABSENT } catch (_: Exception) { Kind.INVALID }

    // A bounded directory stream, rather than listFiles(), limits immediate name retention.
    private fun manifestEntries(directory: File): List<File>? = try {
        val manifests = ArrayList<File>()
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (++count > MAX_DIRECTORY_ENTRIES) return null
                if (entry.fileName.toString().endsWith(".nimble")) manifests.add(entry.toFile())
            }
        }
        manifests
    } catch (_: Exception) { null }

    fun projectRoot(workspace: File, document: File): File {
        require(supportsPath(document.path)) { "Fixed Nim analysis supports original-case .nim documents only" }
        val boundary = workspace.canonicalFile
        val file = document.canonicalFile
        require(supportsPath(file.path)) { "Canonical Nim document must retain original-case .nim extension" }
        require(PathSecurity.contains(boundary, file)) { "Nim document escaped the workspace" }
        val immediate = file.parentFile ?: boundary
        // The exact missing/inaccessible parent is an unavailable sentinel. Never fall upward.
        if (kind(immediate) != Kind.DIRECTORY) return immediate
        var candidate = immediate
        repeat(MAX_ANCESTORS) {
            val manifests = manifestEntries(candidate) ?: return candidate
            val configPresent = rootFiles.any { kind(File(candidate, it)) != Kind.ABSENT }
            val gitPresent = kind(File(candidate, ".git")) != Kind.ABSENT
            val hg = kind(File(candidate, ".hg"))
            // Wrong-type present markers are barriers, subsequently unavailable, not broader grants.
            if (manifests.isNotEmpty() || configPresent || gitPresent || hg != Kind.ABSENT) return candidate
            if (candidate == boundary) return boundary
            val parent = candidate.parentFile ?: return candidate
            if (!PathSecurity.contains(boundary, parent)) return candidate
            if (kind(parent) != Kind.DIRECTORY) return parent
            candidate = parent
        }
        // This exact parent was already scanned without a manifest/marker. Keep
        // that unavailable sentinel on bound exhaustion so status and recorded-owner
        // Revoke/Close remain usable; never substitute a broader workspace root.
        return immediate
    }

    fun projectAvailable(project: File): Boolean {
        if (kind(project) != Kind.DIRECTORY) return false
        if (rootFiles.any { kind(File(project, it)) !in setOf(Kind.ABSENT, Kind.FILE) }) return false
        if (kind(File(project, ".git")) !in setOf(Kind.ABSENT, Kind.FILE, Kind.DIRECTORY)) return false
        if (kind(File(project, ".hg")) !in setOf(Kind.ABSENT, Kind.DIRECTORY)) return false
        val manifests = manifestEntries(project) ?: return false
        return manifests.size == 1 && kind(manifests.single()) == Kind.FILE
    }

    /** Stable policy identity; liveness/availability is deliberately not part of consent identity. */
    fun selectionIdentity(project: File): JsonObject = buildJsonObject {
        put("profile", IDENTITY); put("root", project.canonicalPath)
        put("rootPolicy", ROOT_POLICY); put("requestPolicy", REQUEST_POLICY)
        put("configuration", configuration())
    }

    // 1.16.0 ignores initializationOptions. These are not a pre-initialize enforcement seam.
    fun initializationOptions(): JsonObject = buildJsonObject { }

    fun configuration(): JsonObject = buildJsonObject {
        put("nimsuggestPath", ""); put("maxNimsuggestProcesses", 1)
        put("useNimCheck", false); put("useNimTrack", false)
        put("nimExpandArc", false); put("nimExpandMacro", false)
        put("formatOnSave", false); put("logNimsuggest", false); put("autoRestart", false)
        put("projectMapping", buildJsonArray { }); put("workingDirectoryMapping", buildJsonArray { })
        put("inlayHints", buildJsonObject {
            listOf("typeHints", "parameterHints", "exceptionHints").forEach {
                put(it, buildJsonObject { put("enable", false) })
            }
        })
    }

    /** Manager must additionally admit this exact owner inside the serialized response writer. */
    fun configurationReply(params: JsonElement?, scopeAllowed: (String) -> Boolean): JsonArray {
        val root = params as? JsonObject ?: error("Nim configuration request must be an object")
        require(root.keys == setOf("items")) { "Unexpected Nim configuration request fields" }
        val items = root["items"] as? JsonArray ?: error("Nim configuration items missing")
        require(items.size <= MAX_CONFIGURATION_ITEMS) { "Nim configuration request is too large" }
        return JsonArray(items.map { raw ->
            val item = raw as? JsonObject ?: error("Nim configuration item must be an object")
            require(item.keys.all { it == "section" || it == "scopeUri" }) { "Unexpected Nim configuration item fields" }
            fun optionalString(key: String, bound: Int): String? {
                val value = item[key] ?: return null
                if (value == JsonNull) return null
                val primitive = value as? JsonPrimitive ?: error("Invalid Nim configuration $key")
                require(primitive.isString && primitive.content.length <= bound && primitive.content.none { it.code < 0x20 }) {
                    "Invalid Nim configuration $key"
                }
                return primitive.content
            }
            val section = optionalString("section", 256)
            val scope = optionalString("scopeUri", 4096)
            require(scope.isNullOrEmpty() || scopeAllowed(scope)) { "Nim configuration scope is not owned" }
            if (section == "nim") configuration() else JsonNull
        })
    }

    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + mapOf(
        "general" to buildJsonObject { put("positionEncodings", buildJsonArray { add("utf-16") }) },
        "window" to buildJsonObject { put("workDoneProgress", false) },
        "workspace" to buildJsonObject {
            put("configuration", true); put("applyEdit", false); put("workspaceFolders", false)
            put("didChangeConfiguration", buildJsonObject { put("dynamicRegistration", false) })
            put("symbol", buildJsonObject { put("dynamicRegistration", false) })
        },
        "textDocument" to buildJsonObject {
            put("synchronization", buildJsonObject {
                put("dynamicRegistration", false); put("willSave", false)
                put("willSaveWaitUntil", false); put("didSave", false)
            })
            put("hover", buildJsonObject {
                put("dynamicRegistration", false); put("contentFormat", buildJsonArray { add("markdown"); add("plaintext") })
            })
            put("completion", buildJsonObject {
                put("dynamicRegistration", false)
                put("completionItem", buildJsonObject { put("snippetSupport", false); put("commitCharactersSupport", false) })
            })
            put("signatureHelp", buildJsonObject {
                put("dynamicRegistration", false); put("contextSupport", false)
                put("signatureInformation", buildJsonObject {
                    put("documentationFormat", buildJsonArray { add("markdown"); add("plaintext") })
                    put("activeParameterSupport", false)
                    put("parameterInformation", buildJsonObject { put("labelOffsetSupport", false) })
                })
            })
            listOf("definition", "references").forEach { put(it, buildJsonObject { put("dynamicRegistration", false) }) }
            put("documentSymbol", buildJsonObject {
                put("dynamicRegistration", false); put("hierarchicalDocumentSymbolSupport", false)
            })
            put("rename", buildJsonObject { put("dynamicRegistration", false); put("prepareSupport", false) })
        },
    ))

    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities = native.copy(
        sync = native.sync.copy(save = false, saveIncludeText = false),
        semanticTokens = false, inlayHint = false, implementation = false,
        codeAction = false, codeActionResolve = false, callHierarchy = false,
        formatting = false, pullDiagnostics = false, diagnosticIdentifier = null,
    )

    fun requireInitializeContract(result: JsonElement) {
        val caps = (result as? JsonObject)?.get("capabilities") as? JsonObject
            ?: error("Nim initialize capabilities missing")
        val native = LspNegotiatedCapabilities.fromInitialize(result)
        val sync = caps["textDocumentSync"] as? JsonObject ?: error("Nim native synchronization object missing")
        require(sync["willSave"] == JsonPrimitive(false) && sync["willSaveWaitUntil"] == JsonPrimitive(true)) {
            "Nim native save capability contract changed"
        }
        require(native.sync.openClose && native.sync.change == LspTextSyncPolicy.ChangeKind.FULL &&
            native.sync.save && native.sync.saveIncludeText) { "Nim native full synchronization contract changed" }
        require(native.hover && native.completion && native.signatureHelp && native.definition && native.references &&
            native.documentSymbol && native.workspaceSymbol && native.rename) { "Nim native feature contract changed" }
        require(native.completionTriggerCharacters == setOf(".") &&
            native.signatureHelpTriggerCharacters == setOf("(", ",")) { "Nim native trigger contract changed" }
        val completion = caps["completionProvider"] as? JsonObject ?: error("Nim completion contract missing")
        require((completion["resolveProvider"] as? JsonPrimitive)?.booleanOrNull == false) { "Nim completion resolve contract changed" }
        require(caps["renameProvider"] == JsonPrimitive(true)) { "Nim prepare-rename must remain unadvertised" }
        // Native inlays/commands/code actions remain masked, even though the original server advertises them.
    }

    const val DISPLAY_NAME = "Nim 2.2.12 fixed-toolchain analysis (nimlangserver 1.16.0)"
    const val MODE_DETAIL = "Restricted metadata tools; existing dependencies required."
    const val APPROVAL_EXPLANATION = "Analyze this package with the original Nim 2.2.12 compiler and matching nimsuggest, using nimlangserver 1.16.0. Nimble metadata is evaluated offline with this compiler forced, overriding automatic Nimble compiler selection. Detected supported compiler-version conflicts stop analysis; computed, conditional and transitive graph compatibility remains unknown. Trusted project/configuration code may write caches, access files outside this folder and use the network. Analysis creates temporary source copies and a local TCP backend. This is not a sandbox; ordinary process cleanup cannot undo file/network effects or contain deliberately escaped descendants. Only one entry project and a small metadata dump are supported. Restricted metadata tools such as git/hg are unavailable through PATH, so some projects fail visibly. Navigation and rename require current captured open targets."
    const val INSTALLATION_DISCLOSURE = "Enable analysis is separate from installation. Exact reviewed nimlangserver 1.16.0, private Nim 2.2.12/Nimble 0.24.1 and private Node helper 22.23.3 require three explicit managed artifact installations; these are not one atomic transaction. Terminal Nim/Node selections stay unchanged. No compiler/dependencies are implicitly downloaded and this integration does not rewrite project/lock files. Zero-document transport health does not establish semantic readiness."
    const val PARTIAL_RESULT_NOTE = "Some Nim results were omitted because their targets were not captured, open and current, or their coordinates could not be verified."
    const val FEATURE_NOTE = "Fixed Nim 2.2.12 / nimlangserver 1.16.0: native plain-label completion, hover, signature text, provisional unversioned pushed diagnostics, captured-open-target navigation and flat symbols, limited last-backend workspace symbols, and previewed all-target-captured rename. Signature parameter highlighting and full-project rename completeness are unavailable. Save retires the owner; later supported demand replays current buffers. Restart/reopen for unobserved external changes. Formatting/nph, inlays, commands/tasks/tests, macro/ARC expansion, semantic tokens, prepareRename and multi-root/watch semantics are unavailable. Graph compatibility remains unknown. Restricted metadata tools; existing dependencies required. Original runtime/device semantics remain unverified; protocol connection and empty diagnostics do not establish readiness. Generic custom commands retain separate unverified provenance and behavior."
}
