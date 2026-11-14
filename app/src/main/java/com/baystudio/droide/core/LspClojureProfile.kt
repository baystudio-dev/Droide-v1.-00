package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.serialization.json.*

/** Source-bound clojure-lsp client policy. Defaults and project roots are not a sandbox. */
internal object LspClojureProfile {
    val extensions = setOf("clj", "cljs", "cljc", "edn", "bb", "cljd", "clj_kondo")
    const val IDENTITY = "clojure-lsp-2026.10.15-11.42.38-full-project-readiness-v1"
    const val LAUNCH_POLICY_REVISION = "clojure-native-managed-local-log-v1"
    const val ROOT_POLICY = "nearest-typed-edn-project-marker-then-opened-workspace-with-absent-parent-barrier-v1"
    const val READINESS_POLICY = "manager-owned-330s-attempt-300s-initialize-15s-replay-close-retire-current-buffers-v1"
    const val INITIALIZE_TIMEOUT_MS = 300_000L
    const val ATTEMPT_TIMEOUT_MS = 330_000L
    const val REPLAY_TIMEOUT_MS = 15_000L
    const val MAX_FORMATTING_TEXT_CHARS = 200_000

    private val projectTools = linkedMapOf(
        "deps.edn" to "deps.edn: Clojure CLI and compatible Java",
        "project.clj" to "project.clj: Leiningen and compatible Java",
        "bb.edn" to "bb.edn: Babashka",
        "build.boot" to "build.boot: Boot and compatible Java",
        "shadow-cljs.edn" to "shadow-cljs.edn: npx/Node and Shadow/project Java requirements",
        "squint.edn" to "squint.edn: Clojure CLI and compatible Java",
    )

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "") in extensions

    private fun hasMarker(root: File, marker: String): Boolean =
        Files.isRegularFile(File(root, marker).toPath(), NOFOLLOW_LINKS)

    fun projectMarker(root: File): Boolean = projectTools.keys.any { hasMarker(root, it) } ||
        (Files.isDirectory(File(root, ".lsp").toPath(), NOFOLLOW_LINKS) && hasMarker(root, ".lsp/config.edn"))

    /** Inspect only marker types; never probe versions or execute project-selected tools. */
    fun toolHints(root: File): List<String> = projectTools.filterKeys { hasMarker(root, it) }.values.toList()

    fun initializationOptions(): JsonObject = buildJsonObject {
        put("text-document-sync-kind", "full")
    }

    /** Exact-key absence matters: this server tests member truthiness, not nested support. */
    fun clientCapabilities(base: JsonObject): JsonObject {
        val workspace = JsonObject(base.getValue("workspace").jsonObject - "didChangeWatchedFiles" +
            ("configuration" to JsonPrimitive(false)))
        val textDocument = base.getValue("textDocument").jsonObject
        val codeAction = textDocument["codeAction"] as? JsonObject
        val documents = if (codeAction == null) textDocument else
            JsonObject(textDocument + ("codeAction" to JsonObject(codeAction - "resolveSupport")))
        val result = base + ("workspace" to workspace) + ("textDocument" to documents)
        val window = base["window"] as? JsonObject ?: return JsonObject(result)
        return JsonObject(result + ("window" to JsonObject(window - "showDocument")))
    }

    /**
     * Only the exact full-document formatting response is normalized. The manager
     * owns profile/request admission; the ordinary edit validator still owns application.
     * Every unrecognized shape remains unchanged, including blank-file applyEdit's
     * distinct 999998 sentinel, near-sentinels and multi-edit results.
     */
    fun normalizeFormatting(result: JsonArray, requestText: String): JsonArray {
        val edit = result.singleOrNull() as? JsonObject ?: return result
        if (edit.keys != setOf("range", "newText")) return result
        val range = edit["range"] as? JsonObject ?: return result
        if (range.keys != setOf("start", "end")) return result
        fun exactPosition(value: JsonElement?, line: Int, column: Int): Boolean {
            val position = value as? JsonObject ?: return false
            if (position.keys != setOf("line", "character")) return false
            fun exactNumber(key: String, expected: Int): Boolean =
                (position[key] as? JsonPrimitive)?.let { !it.isString && it.content == expected.toString() } == true
            return exactNumber("line", line) && exactNumber("character", column)
        }
        if (!exactPosition(range["start"], 0, 0) || !exactPosition(range["end"], 999999, 999999)) return result
        val replacement = (edit["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return result
        if (replacement.length > MAX_FORMATTING_TEXT_CHARS || !validUtf16(replacement) || !validUtf16(requestText)) return result
        // Match the editor's LSP line model without normalizing the captured text:
        // standalone CR and LF each end a line; CRLF is one line break.
        var endLine = 0
        var lineStart = 0
        var index = 0
        while (index < requestText.length) {
            when (requestText[index++]) {
                '\r' -> {
                    if (index < requestText.length && requestText[index] == '\n') index++
                    endLine++
                    lineStart = index
                }
                '\n' -> {
                    endLine++
                    lineStart = index
                }
            }
        }
        val end = buildJsonObject {
            put("line", endLine)
            put("character", requestText.length - lineStart)
        }
        return JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("end" to end))))))
    }

    private fun validUtf16(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (character.isHighSurrogate()) {
                if (index == text.length || !text[index++].isLowSurrogate()) return false
            } else if (character.isLowSurrogate()) return false
        }
        return true
    }

    const val INSTALLATION_DISCLOSURE = "Enabling analysis does not invoke the IDE package installer. Trusted project tools may download or install dependencies as described above. It does not authorize database migrations or deployment."
    const val APPROVAL_EXPLANATION = "Clojure LSP performs full project and dependency analysis. It can run Clojure CLI, Leiningen, Babashka, Boot, npx/Shadow or custom classpath commands selected by your project, and use executable hooks/custom linters and configuration. Tools inherit the selected Ubuntu user's environment, HOME, configuration and credentials and can read outside this folder, access the network, download dependencies and write files/caches. Optional configured stub generation runs Java and deletes/replaces its configured output directory. The server can fetch ClojureDocs/dependency indexes or configured Java sources, and project settings can enable telemetry or change log/cache paths. Known IDE changes may restart analysis and repeat this work. Initialization settings are defaults, not a sandbox. Missing tools are reported; the IDE does not install or substitute a tool or compiler. The server creates a local guest temporary log that may remain after shutdown; project settings can also choose other log destinations."
    const val FEATURE_NOTE = "Ready means initialization and current-buffer replay completed; it does not certify project-tool compatibility, dependency resolution, asynchronous diagnostics/downloads or stub generation. Push diagnostics are unversioned and may lag; empty reports or silence do not certify current validity. A command named clojure is not proof of Clojure CLI/tools.deps support. All matching standard project tools may run, and configured project-specs can replace them. Discovered/custom executable provenance and managed private-state guarantees remain unverified. IDE writes and discarded buffers retire indexes; later supported demand replays current buffers. External disk/config changes may require restart/reopen. Command-backed refactorings/quick fixes and blank-file namespace applyEdit are not applied. Dependency archive sources (jar:file:/zipfile:) are not navigable yet. Completion resolve, declaration, range-formatting, folding/selection/linked-editing, highlights, code lenses and custom tree UI are not integrated."
}
