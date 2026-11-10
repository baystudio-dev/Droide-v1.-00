package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.*

/** Exact managed public Svelte contract. Project roots and observation are not a sandbox. */
internal object LspSvelteProfile {
    const val ID = "svelte-language-server"
    const val COMMAND = "svelteserver"
    const val VERSION = "0.18.5"
    const val IDENTITY = "svelte-0.18.5-managed-companion-observer-v1"
    const val ROOT_POLICY = "nearest-typed-config-package-vcs-workspace-with-unavailable-parent-v1"
    const val COMPANION_POLICY = "existing-ordinary-ts-js-public-primer-full-text-before-svelte-v1"
    const val WATCH_POLICY = "registered-real-glob-coalesced-observation-drain-retire-v1"
    val extensions = setOf("svelte")
    private val configExtensions = listOf("js", "ts", "mjs", "cjs", "mts", "cts")
    val rootFiles = listOf("package.json", "tsconfig.json", "jsconfig.json") +
        listOf("svelte.config", "vite.config").flatMap { stem -> configExtensions.map { "$stem.$it" } }
    private val vcs = listOf(".git", ".hg", ".svn", ".jj")
    private val scripts = setOf("ts", "js", "tsx", "jsx", "mts", "cts", "mjs", "cjs")
    private val generated = setOf("node_modules", ".svelte-kit", ".next", ".nuxt", "dist", "build", "coverage")

    fun supportsPath(path: String): Boolean = path.replace('\\', '/').substringAfterLast('/').endsWith(".svelte")
    fun isScriptCandidate(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in scripts
    fun supportedCompanion(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        val leaf = normalized.substringAfterLast('/')
        return leaf.substringAfterLast('.', "") in setOf("ts", "js") && !leaf.endsWith(".d.ts") &&
            !normalized.contains("node_modules") && normalized.split('/').none { it in generated }
    }

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

    fun projectRoot(workspace: File, document: File): File {
        val boundary = workspace.canonicalFile
        val file = document.canonicalFile
        require(PathSecurity.contains(boundary, file)) { "Svelte document escaped the workspace" }
        val directory = file.parentFile ?: boundary
        if (kind(directory) != Kind.DIRECTORY) return directory
        var candidate = directory
        while (PathSecurity.contains(boundary, candidate)) {
            // Invalid present markers are a barrier, never permission to borrow a broader grant.
            if (rootFiles.any { kind(File(candidate, it)) != Kind.ABSENT } ||
                vcs.any { kind(File(candidate, it)) == Kind.DIRECTORY }) return candidate
            if (candidate == boundary) break
            candidate = candidate.parentFile ?: break
        }
        return boundary
    }

    fun projectAvailable(project: File): Boolean = kind(project) == Kind.DIRECTORY &&
        rootFiles.all { kind(File(project, it)) in setOf(Kind.ABSENT, Kind.FILE) }

    fun selectionIdentity(project: File): JsonObject = buildJsonObject {
        put("rootPolicy", ROOT_POLICY)
        put("directory", kind(project).name)
        put("markers", buildJsonObject { rootFiles.forEach { put(it, kind(File(project, it)).name) } })
    }

    fun initializationOptions(): JsonObject = buildJsonObject {
        put("isTrusted", true)
        put("configuration", buildJsonObject {
            put("svelte", buildJsonObject {
                put("language-server", buildJsonObject { put("debug", false) })
                put("plugin", buildJsonObject {
                    put("css", buildJsonObject { put("globals", "") })
                    put("typescript", buildJsonObject { put("workspaceSymbols", buildJsonObject { put("enable", false) }) })
                })
            })
        })
    }

    fun clientCapabilities(base: JsonObject): JsonObject {
        val workspace = base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(false), "applyEdit" to JsonPrimitive(false),
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", true); put("relativePatternSupport", false) },
        )
        return JsonObject(base + ("workspace" to JsonObject(workspace)))
    }

    fun supportedCapabilities(native: LspNegotiatedCapabilities): LspNegotiatedCapabilities =
        native.copy(workspaceSymbol = false, callHierarchy = false)

    fun requireInitializeContract(result: JsonElement) {
        val caps = (result as? JsonObject)?.get("capabilities") as? JsonObject ?: error("Svelte initialize capabilities missing")
        val sync = caps["textDocumentSync"] as? JsonObject ?: error("Svelte synchronization contract unavailable")
        require((sync["openClose"] as? JsonPrimitive)?.booleanOrNull == true &&
            (sync["change"] as? JsonPrimitive)?.intOrNull == 2) { "Svelte synchronization contract changed" }
        val diagnostic = caps["diagnosticProvider"] as? JsonObject ?: error("Svelte captured pull diagnostics unavailable")
        require((diagnostic["interFileDependencies"] as? JsonPrimitive)?.booleanOrNull == true &&
            (diagnostic["workspaceDiagnostics"] as? JsonPrimitive)?.booleanOrNull == false) { "Svelte diagnostic contract changed" }
        require(caps["executeCommandProvider"] == null || caps["executeCommandProvider"] == JsonNull) { "Unexpected Svelte command contract" }
    }

    const val INSTALLATION_DISCLOSURE = "Analysis enablement does not install packages. The specialized adapter requires the exact reviewed managed server/runtime identity; zero-document health does not establish project semantics."
    const val APPROVAL_EXPLANATION = "Svelte analysis can load and execute project Svelte/Vite configuration, compilers, preprocessors, Prettier configuration and plugins during startup and editing. Trusted code can read/write outside this folder and use guest credentials or the network. The selected root and bounded read-only observer are not a sandbox. Known configuration changes require renewed approval; observed source changes restart the server and may repeat project code. Local logs/private state and native configuration watchers may remain outside the editor's observation guarantee."
    const val FEATURE_NOTE = "Managed Svelte 0.18.5 only: real Svelte requests use captured existing ordinary TS/JS companions and bounded observed disk generations. Native snapshot priming is not a dirty-text acknowledgement or configured-membership proof. Unsupported dirty script/dependency/generated/symlink/untitled or cross-root inputs stop analysis. External ABA, outside-root configuration and native independent watchers remain unverified; observation caps fail closed. Push diagnostics are unversioned; use captured pulls. Commands, file-resource edits, uncaptured edit targets, workspace symbols/call hierarchy and reverse dirty-Svelte-to-TypeScript integration are unavailable. Native/device semantics remain unverified."
}
