package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

internal object LspElixirProfile {
    const val ID = "elixir-ls"
    const val IDENTITY = "elixirls-user-managed-configured-before-build-v1"
    const val COMMAND = "language_server.sh"
    val extensions = setOf("ex", "exs")
    val command = listOf(COMMAND)
    val spec = LanguageServerSpec(ID, setOf("elixir"), extensions, listOf(command))

    fun matches(candidate: LanguageServerSpec): Boolean = candidate == spec
    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions
    fun isRoute(candidate: LanguageServerSpec, path: String? = null): Boolean =
        candidate.id == ID || "elixir" in candidate.languageIds || candidate.extensions.any { it in extensions } ||
            path?.let(::supportsPath) == true

    fun projectRoot(workspace: File, document: File): File {
        val root = workspace.canonicalFile
        require(PathSecurity.contains(root, document.canonicalFile)) { "Elixir document escaped the workspace" }
        return root
    }
    fun projectAvailable(project: File): Boolean = Files.isDirectory(project.toPath(), NOFOLLOW_LINKS)

    // ElixirLS waits for configuration before starting builds. Keep native compiler diagnostics on,
    // without automatic project dependency downloads or Dialyzer PLTs.
    fun settings(): JsonObject = buildJsonObject {
        put("autoBuild", true)
        put("fetchDeps", false)
        put("dialyzerEnabled", false)
        put("suggestSpecs", false)
        put("enableTestLenses", false)
        put("mcpEnabled", false)
    }
    fun configuration(): JsonObject = buildJsonObject {
        put("settings", buildJsonObject { put("elixirLS", settings()) })
    }
    fun clientCapabilities(base: JsonObject): JsonObject = JsonObject(base + (
        "workspace" to JsonObject(base.getValue("workspace").jsonObject + mapOf(
            "configuration" to JsonPrimitive(true),
            "didChangeConfiguration" to buildJsonObject { put("dynamicRegistration", false) },
            "didChangeWatchedFiles" to buildJsonObject { put("dynamicRegistration", false) },
        ))
    ))
    /** Fixed non-secret per-server settings. A supplied URI never opens a file or runs a tool. */
    fun configurationReply(params: JsonElement?): JsonArray {
        val items = (params as? JsonObject)?.get("items") as? JsonArray
            ?: error("ElixirLS configuration requires an items array")
        require(items.size <= 64) { "Too many ElixirLS configuration items" }
        val values = settings()
        return buildJsonArray {
            items.forEach { item ->
                val objectItem = item as? JsonObject
                val section = objectItem?.get("section")
                add(when {
                    objectItem == null -> JsonNull
                    section == null || section == JsonNull -> buildJsonObject { put("elixirLS", values) }
                    section == JsonPrimitive("elixirLS") -> values
                    section is JsonPrimitive && section.isString && section.content.startsWith("elixirLS.") ->
                        values[section.content.removePrefix("elixirLS.")] ?: JsonNull
                    else -> JsonNull
                })
            }
        }
    }

    const val INSTALL_NOTE = "Install ElixirLS with compatible Elixir/Erlang in the shared Ubuntu workstation. " +
        "Keep its release scripts together and put their directory on PATH so language_server.sh can run. " +
        "Update through your usual terminal/tool manager; a different launcher can be configured as a custom server. " +
        "Launcher discovery alone does not establish runtime compatibility. Newer upstream launchers may populate or repair their own Mix cache. " +
        "Droide does not install, pin or replace these tools."
    const val APPROVAL_EXPLANATION = "Start your installed ElixirLS launcher in this workspace. " +
        "It can source user setup/version-manager scripts and initialize its cached server dependencies. " +
        "ElixirLS loads Mix project configuration and compiles project/dependency code for diagnostics after activation and saves, " +
        "with guest file/network access. Automatic project dependency fetching, Dialyzer and the server's MCP listener are requested off. " +
        "These settings do not block arbitrary project code or the upstream launcher's own cache repair/downloads. " +
        "The workspace is not a sandbox and compilation has no hard memory bound."
    const val FEATURE_NOTE = "Elixir source/scripts using your installed ElixirLS and selected Elixir/Erlang. " +
        "Completion, hover, navigation, compiler diagnostics, formatting and rename depend on server capabilities. " +
        "Enable project analysis to start; normal saves reuse the session. Install project dependencies yourself when needed. " +
        "Revoke/re-enable analysis after toolchain/configuration or external-file changes. " +
        "Dialyzer, test-lens actions, MCP, DAP and Phoenix template support are not included in this profile."
}
