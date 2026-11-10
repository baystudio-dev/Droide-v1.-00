package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.*

/** Exact 0.41.0 response policy. The manager must separately prove owner, source and managed identity. */
internal object LspSqruffProfile {
    const val ID = "sqruff"
    const val VERSION = "0.41.0"
    const val IDENTITY = "sqruff-0.41.0-immutable-lf-scalar-format-v1"
    const val LAUNCH_POLICY_REVISION = "sqruff-managed-native-project-lsp-v1"
    const val ROOT_POLICY = "nearest-typed-config-then-real-vcs-then-document-parent-barriers-v1"
    const val MAX_TEXT_CHARS = 200_000
    const val MAX_TEXT_UTF8_BYTES = 800_000
    const val MAX_DIAGNOSTICS = 8_000
    val extensions = setOf("sql")
    val tokenTypes = listOf("keyword", "string", "number", "comment", "operator", "function", "type", "variable", "parameter", "property", "macro")
    val legend = LspSemanticTokenLegend(tokenTypes, emptyList())

    fun supportsPath(path: String): Boolean =
        path.replace('\\', '/').substringAfterLast('/').substringAfterLast('.', "").equals("sql", ignoreCase = true)

    private enum class Kind { ABSENT, FILE, DIRECTORY, INVALID }
    private val configMarkers = listOf(".sqlfluff", ".sqruff", ".sqruff.ini", "pyproject.toml", "sqruff.toml")
    private val vcsMarkers = listOf(".git", ".hg", ".svn", ".jj")
    private fun kind(file: File): Kind = try {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        when {
            attrs.isSymbolicLink || !Files.isReadable(file.toPath()) -> Kind.INVALID
            attrs.isRegularFile -> Kind.FILE
            attrs.isDirectory -> Kind.DIRECTORY
            else -> Kind.INVALID
        }
    } catch (_: NoSuchFileException) { Kind.ABSENT } catch (_: Exception) { Kind.INVALID }

    private fun configs(project: File): Map<String, Kind> = configMarkers.associateWith { kind(File(project, it)) }
    private fun invalidConfig(markers: Map<String, Kind>): Boolean = markers.values.any { it != Kind.ABSENT && it != Kind.FILE }

    /** Metadata-only two-pass search; invalid recognized entries stop ancestor grant borrowing. */
    fun projectRoot(workspace: File, document: File): File {
        val boundary = workspace.canonicalFile
        val file = document.canonicalFile
        require(PathSecurity.contains(boundary, file)) { "Language-server document escaped the workspace" }
        val directory = file.parentFile ?: boundary
        if (kind(directory) != Kind.DIRECTORY) return directory
        val ancestors = mutableListOf<File>()
        var candidate = directory
        while (PathSecurity.contains(boundary, candidate)) {
            ancestors += candidate
            if (kind(candidate) != Kind.DIRECTORY || configs(candidate).values.any { it != Kind.ABSENT }) return candidate
            if (candidate == boundary) break
            candidate = candidate.parentFile ?: break
        }
        return ancestors.firstOrNull { root -> vcsMarkers.any { kind(File(root, it)) == Kind.DIRECTORY } } ?: directory
    }

    fun projectAvailable(project: File): Boolean = kind(project) == Kind.DIRECTORY && !invalidConfig(configs(project))

    /** Kind/name/root identity deliberately excludes contents, allowing consent-preserving saved-config refresh. */
    fun selectionIdentity(project: File): JsonObject {
        val markers = configs(project)
        val vcs = vcsMarkers.associateWith { kind(File(project, it)) }
        val directory = kind(project)
        return buildJsonObject {
            put("root", project.absoluteFile.normalize().path)
            put("directory", directory.name)
            put("reason", when {
                directory != Kind.DIRECTORY || invalidConfig(markers) -> "unavailable"
                markers.values.any { it == Kind.FILE } -> "config"
                vcs.values.any { it == Kind.DIRECTORY } -> "vcs"
                else -> "document-parent"
            })
            put("markers", buildJsonObject { markers.forEach { (name, value) -> put(name, value.name) } })
            put("vcs", buildJsonObject { vcs.forEach { (name, value) -> put(name, value.name) } })
        }
    }

    /** Original editor bytes remain authoritative. Only LF-normalized text enters the reviewed server. */
    class WireSnapshot private constructor(val originalText: String, val wireText: String) {
        private val lineStarts: IntArray = buildList {
            add(0)
            wireText.forEachIndexed { index, c -> if (c == '\n') add(index + 1) }
        }.toIntArray()
        internal val lineCount: Int get() = lineStarts.size
        internal fun lineStart(line: Int): Int = lineStarts[line]
        internal fun lineEnd(line: Int): Int = if (line + 1 == lineStarts.size) wireText.length else lineStarts[line + 1] - 1
        companion object {
            internal fun checked(original: String): WireSnapshot? {
                if (!boundedText(original)) return null
                for (index in original.indices) if (original[index] == '\r' && original.getOrNull(index + 1) != '\n') return null
                return WireSnapshot(original, original.replace("\r\n", "\n"))
            }
        }
    }

    fun wireSnapshot(originalText: String): WireSnapshot? = WireSnapshot.checked(originalText)

    private fun boundedText(text: String): Boolean {
        if (text.length > MAX_TEXT_CHARS) return false
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            bytes += when {
                character.isHighSurrogate() -> {
                    if (index == text.length || !text[index++].isLowSurrogate()) return false
                    4
                }
                character.isLowSurrogate() -> return false
                character.code < 0x80 -> 1
                character.code < 0x800 -> 2
                else -> 3
            }
            if (bytes > MAX_TEXT_UTF8_BYTES) return false
        }
        return true
    }

    private fun uint(value: JsonElement?): Int? = (value as? JsonPrimitive)?.takeUnless { it.isString }
        ?.takeIf { it.content.matches(Regex("0|[1-9][0-9]*")) }?.intOrNull
    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun position(value: JsonElement?): Pair<Int, Int>? {
        val obj = value as? JsonObject ?: return null
        if (obj.keys != setOf("line", "character")) return null
        return (uint(obj["line"]) ?: return null) to (uint(obj["character"]) ?: return null)
    }
    private fun positionJson(line: Int, character: Int): JsonObject = buildJsonObject { put("line", line); put("character", character) }
    private fun scalarColumn(snapshot: WireSnapshot, line: Int, scalar: Int): Int? {
        if (line !in 0 until snapshot.lineCount) return null
        val begin = snapshot.lineStart(line)
        val end = snapshot.lineEnd(line)
        var index = begin
        var count = 0
        while (count < scalar && index < end) {
            index += if (snapshot.wireText[index].isHighSurrogate()) 2 else 1
            count++
        }
        return if (count == scalar) index - begin else null
    }

    /** Exact native zero-width warnings only. Null is unavailable; empty is an explicit unverified report, never a validity certificate. */
    fun convertDiagnostics(items: JsonArray, snapshot: WireSnapshot): JsonArray? {
        if (items.size > MAX_DIAGNOSTICS) return null
        return JsonArray(items.map { element ->
            val diagnostic = element as? JsonObject ?: return null
            if (!diagnostic.keys.containsAll(setOf("range", "severity", "source", "message")) ||
                diagnostic.keys.any { it !in setOf("range", "severity", "code", "source", "message") }) return null
            val message = string(diagnostic["message"]) ?: return null
            if (uint(diagnostic["severity"]) != 2 || string(diagnostic["source"]) != "sqruff" || !boundedText(message)) return null
            if ("code" in diagnostic && string(diagnostic["code"])?.let(::boundedText) != true) return null
            val range = diagnostic["range"] as? JsonObject ?: return null
            if (range.keys != setOf("start", "end")) return null
            val start = position(range["start"]) ?: return null
            if (start != position(range["end"])) return null
            val column = scalarColumn(snapshot, start.first, start.second) ?: return null
            val converted = positionJson(start.first, column)
            JsonObject(diagnostic + ("range" to buildJsonObject { put("start", converted); put("end", converted) }))
        })
    }

    private fun boundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
        !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

    fun validateSemanticTokens(tokens: List<LspSemanticToken>, nativeLegend: LspSemanticTokenLegend, snapshot: WireSnapshot): Boolean {
        if (nativeLegend != legend || tokens.size > LspSemanticTokenDecoder.MAX_TOKENS) return false
        var previousLine = -1
        var previousEnd = 0
        for (token in tokens) {
            if (token.type !in tokenTypes || token.modifiers.isNotEmpty() || token.line !in 0 until snapshot.lineCount ||
                token.startCharacter < 0 || token.length !in 1..LspSemanticTokenDecoder.MAX_TOKEN_LENGTH) return false
            val start = snapshot.lineStart(token.line).toLong() + token.startCharacter
            val end = start + token.length
            if (end > snapshot.lineEnd(token.line) || !boundary(snapshot.wireText, start.toInt()) || !boundary(snapshot.wireText, end.toInt()) ||
                token.line < previousLine || (token.line == previousLine && token.startCharacter < previousEnd)) return false
            previousLine = token.line
            previousEnd = token.startCharacter + token.length
        }
        return true
    }

    /** Strict raw admission precedes the generic decoder. Null is unavailable; empty is a native empty result, not proof of health. */
    fun parseSemanticTokens(result: JsonElement?, nativeLegend: LspSemanticTokenLegend, snapshot: WireSnapshot): List<LspSemanticToken>? {
        val obj = result as? JsonObject ?: return null
        if (obj.keys != setOf("data") || nativeLegend != legend) return null
        val data = obj["data"] as? JsonArray ?: return null
        if (data.size % 5 != 0 || data.size / 5 > LspSemanticTokenDecoder.MAX_TOKENS) return null
        val integers = data.map { (uint(it) ?: return null).toLong() }
        for (index in integers.indices step 5) if (integers[index + 4] != 0L) return null
        val tokens = LspSemanticTokenDecoder.decode(integers, nativeLegend)
        if (tokens.size != data.size / 5 || !validateSemanticTokens(tokens, nativeLegend, snapshot)) return null
        return tokens
    }

    /** Only the exact native bug formula is replaced with the ORIGINAL editor UTF-16 EOF. */
    fun normalizeFormatting(result: JsonElement?, requestText: String): JsonArray? {
        if (wireSnapshot(requestText) == null) return null
        val edit = (result as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
        if (edit.keys != setOf("range", "newText")) return null
        val range = edit["range"] as? JsonObject ?: return null
        if (range.keys != setOf("start", "end") || position(range["start"]) != (0 to 0)) return null
        val replacement = string(edit["newText"]) ?: return null
        if (!boundedText(replacement)) return null

        val lines = replacement.count { it == '\n' } + if (replacement.isNotEmpty() && !replacement.endsWith('\n')) 1 else 0
        val scalars = replacement.codePointCount(0, replacement.length)
        if (position(range["end"]) != (lines to scalars)) return null
        val end = positionJson(requestText.count { it == '\n' }, requestText.length - requestText.lastIndexOf('\n') - 1)
        return JsonArray(listOf(JsonObject(edit + ("range" to JsonObject(range + ("end" to end))))))
    }

    fun requireInitializeContract(result: JsonElement) {
        val caps = (result as? JsonObject)?.get("capabilities") as? JsonObject
            ?: error("sqruff initialize result is missing capabilities")
        require(uint(caps["textDocumentSync"]) == 1 && caps["documentFormattingProvider"] == JsonPrimitive(true)) {
            "sqruff initialize contract differs from the reviewed full-sync/formatting pin"
        }
        require("positionEncoding" !in caps || string(caps["positionEncoding"]) == "utf-16") { "sqruff requires UTF-16 wire positions" }
        val tokens = caps["semanticTokensProvider"] as? JsonObject
            ?: error("sqruff initialize result is missing full semantic tokens")
        require(tokens["full"] == JsonPrimitive(true) && tokens["range"] == JsonPrimitive(false)) {
            "sqruff requires the reviewed full semantic-token shape"
        }
        val rawLegend = tokens["legend"] as? JsonObject ?: error("sqruff semantic legend is missing")
        require(rawLegend.keys == setOf("tokenTypes", "tokenModifiers") &&
            rawLegend["tokenTypes"] == JsonArray(tokenTypes.map(::JsonPrimitive)) &&
            rawLegend["tokenModifiers"] == JsonArray(emptyList())) { "sqruff semantic legend differs from the reviewed pin" }
    }

    fun supportedCapabilities(native: LspNegotiatedCapabilities, managedIdentity: Boolean): LspNegotiatedCapabilities {
        require(native.sync.openClose && native.sync.change == LspTextSyncPolicy.ChangeKind.FULL) { "sqruff requires full document synchronization" }
        require(native.semanticTokens && native.semanticTokenLegend == legend) { "sqruff semantic capability/legend differs from the reviewed pin" }
        return native.copy(
            sync = native.sync.copy(save = false, saveIncludeText = false),
            hover = false, completion = false, completionTriggerCharacters = emptySet(), signatureHelp = false,
            signatureHelpTriggerCharacters = emptySet(), signatureHelpRetriggerCharacters = emptySet(), inlayHint = false,
            definition = false, references = false, implementation = false, documentSymbol = false, workspaceSymbol = false,
            codeAction = false, codeActionResolve = false, callHierarchy = false, rename = false,
            formatting = native.formatting && managedIdentity, pullDiagnostics = false, diagnosticIdentifier = null,
        )
    }

    const val INSTALLATION_DISCLOSURE = "Enabling SQL analysis does not install tools or authorize database connections or SQL execution. Managed installation and private zero-document health are separate."
    const val APPROVAL_EXPLANATION = "SQL analysis loads native project and HOME configuration, ignore rules, file globs and inline directives. Reads can reach outside this selected root; cwd, root and PRoot are not a sandbox. The reviewed 0.41.0 release's default source feature set has no Python templaters; a custom or discovered build is unverified and can execute code or access files/network using the guest user's environment and credentials. Project settings select dialect, rules and templating; without config, the native ANSI fallback applies, and invalid config can fail or fall back. Inline directives can disable limits. Known IDE writes restart native analysis under this grant."
    const val FEATURE_NOTE = "sqruff 0.41.0 provides native lint warnings, full semantic highlighting and managed exact-pin full formatting only. No completion, hover, navigation, rename, database UI or SQL execution is added. Formatting uses native text and may apply lint fixes beyond whitespace. Diagnostics are unversioned/unverified; empty reports, tokens or silence do not certify validity, and notification-only lint can remain pending without a wall-time guarantee. Each relevant edit retires the shared owner; next demand restarts and replays current buffers after a quiet interval, so continuous typing may have no native result. CRLF uses an LF wire view; lone CR and malformed/oversized UTF-16 are unavailable. Managed identity is required for coordinate/range repairs; custom builds do not inherit them. Native config/ignore/glob/outside-root reads and inline directives require trust. The reviewed default release has no Python templaters; custom build features are unverified. Known IDE saves refresh config without another prompt; external changes require restart/reopen. No fake settings or dynamic watcher adapter is supplied. Original native payload/device acceptance remains separate."
}
