package com.baystudio.droide.core

import kotlinx.serialization.json.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.IdentityHashMap

/** Cooperative, exact-path filesystem observation. Call only outside callbacks and admission locks. */
internal object LspTomlSnapshotAuthority {
    const val MAX_PATHS = 64
    const val MAX_FILE_BYTES = 1024 * 1024
    const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
    const val MAX_PARENT_COMPONENTS = 64
    const val MAX_PARENTS = 256
    const val MAX_OPEN_TOML = 32
    const val MAX_REPLAY_BYTES = 8 * 1024 * 1024
    const val MAX_GROWTH_ROUNDS = 8
    const val OBSERVATION_MAX_AGE_NANOS = 2_000_000_000L
    class Overlay(val text: String, val revision: Long)
    data class Observation(val path: String, val type: String, val key: String, val size: Long, val modified: String,
        val created: String, val permissions: String)
    class Entry(val path: String, bytes: ByteArray?, val hash: String?, val origin: String, val optionalConfig: Boolean,
        val observation: Observation?, val overlay: Overlay?) {
        private val content = bytes?.copyOf()
        fun bytes(): ByteArray? = content?.copyOf()
        val size: Int get() = content?.size ?: 0
        val present: Boolean get() = content != null
    }
    class Snapshot(val rootPath: String, val rootUri: String, val generation: String, val selectedConfigPath: String?,
        entries: Map<String, Entry>, parents: Map<String, Observation>, val observedAtNanos: Long, documentPaths: Set<String>) {
        val entries: Map<String, Entry> = java.util.Collections.unmodifiableMap(LinkedHashMap(entries))
        val documentPaths: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(documentPaths))
        val parents: Map<String, Observation> = java.util.Collections.unmodifiableMap(LinkedHashMap(parents))
        val rootIdentity: String = sha(parents.filterKeys { it == "/" || it == rootPath || rootPath.startsWith("$it/") }
            .entries.joinToString("\n") { "${it.key}:${it.value.type}:${it.value.key}:${it.value.created}:${it.value.permissions}" })
        val dependencyPaths: Set<String> get() = entries.keys.filterNot { entries.getValue(it).optionalConfig }.toSet()
        fun descriptor(): TomlSnapshotDescriptor = TomlSnapshotDescriptor(generation, rootUri, selectedConfigPath,
            entries.values.map { TomlSnapshotEntry(it.path, it.bytes(), it.hash, it.origin, it.optionalConfig) })
        fun overlaysCurrent(overlays: Map<String, Overlay>): Boolean = entries.all { (path, entry) -> overlays[path] === entry.overlay }
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha(text: String): String = sha(text.toByteArray(Charsets.UTF_8))
    fun path(root: String, input: String): String {
        require(input.isNotBlank() && !input.contains('\\') && input.none { it.isISOControl() }) { "Invalid local TOML dependency path" }
        val file = if (input.startsWith('/')) File(input) else File(root, input)
        require(file.path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }) { "TOML dependency aliases are unavailable" }
        val full = file.absolutePath
        require(full.startsWith(root.trimEnd('/') + "/")) { "TOML dependency escaped the actual workspace" }
        require(!SensitivePathPolicy.isSensitive(full)) { "Sensitive TOML dependency path is unavailable" }
        return full
    }
    fun validateReplay(overlays: Map<String, Overlay>, root: String): Map<String, Overlay> {
        val docs = overlays.filterKeys { it.startsWith(root.trimEnd('/') + "/") && LspTomlProfile.supportsPath(it) }
        require(docs.size <= MAX_OPEN_TOML) { "TOML open-document replay exceeds 32 documents" }
        var bytes = 0L
        docs.values.forEach { require(LspTomlPositionAdmission.validText(it.text)) { "TOML editor text is malformed or oversized" }; bytes += it.text.toByteArray(Charsets.UTF_8).size }
        require(bytes <= MAX_REPLAY_BYTES) { "TOML replay exceeds 8 MiB; no dirty document was omitted" }
        return docs
    }
    private fun observe(file: Path): Observation? {
        val attr = try { Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
            catch (_: java.nio.file.NoSuchFileException) { return null }
        require(!attr.isSymbolicLink && (attr.isDirectory || attr.isRegularFile)) { "TOML dependency is a symlink or special file" }
        require(Files.isReadable(file)) { "TOML dependency is unreadable" }
        val permissions = try { Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).map { it.name }.sorted().joinToString(",") }
            catch (_: UnsupportedOperationException) { "readable=${Files.isReadable(file)};executable=${Files.isExecutable(file)}" }
        return Observation(file.toString(), if (attr.isDirectory) "directory" else "file", attr.fileKey()?.toString() ?: error("TOML filesystem has no stable file identity"),
            if (attr.isDirectory) 0 else attr.size(), if (attr.isDirectory) "" else attr.lastModifiedTime().toString(), attr.creationTime().toString(), permissions)
    }
    private fun parents(root: Path, target: Path, into: MutableMap<String, Observation>) {
        val chain = mutableListOf<Path>()
        var current: Path? = target.parent
        while (current != null) { chain.add(current); current = current.parent }
        require(chain.size <= MAX_PARENT_COMPONENTS) { "TOML dependency exceeds parent component limit" }
        for (parent in chain.asReversed()) {
            val observation = observe(parent) ?: error("TOML dependency parent disappeared")
            require(observation.type == "directory" && Files.isExecutable(parent)) { "TOML dependency parent is unavailable" }
            val previous = into.putIfAbsent(parent.toString(), observation)
            require(previous == null || previous == observation) { "TOML parent changed during capture" }
            require(into.size <= MAX_PARENTS) { "TOML dependency parent set exceeds 256 identities" }
        }
        require(target.startsWith(root) && target != root)
    }
    fun capture(workspace: File, dependencies: Set<String>, overlays: Map<String, Overlay>, documentPaths: Set<String> = emptySet(), nanoTime: () -> Long = System::nanoTime): Snapshot {
        val root = workspace.absoluteFile.toPath()
        require(root.toString() != "/" && root.toString() == root.normalize().toString() && root.nameCount <= MAX_PARENT_COMPONENTS) { "TOML requires the actual bounded workspace root" }
        val parentMap = linkedMapOf<String, Observation>()
        parents(root, root.resolve(".taplo.toml"), parentMap)
        require(workspace.canonicalPath == root.toString()) { "TOML workspace symlink/alias is unavailable" }
        require(documentPaths.size <= MAX_OPEN_TOML) { "TOML replay parent observations exceed 32 documents" }
        documentPaths.forEach { document ->
            val owned = path(root.toString(), document)
            require(LspTomlProfile.supportsPath(owned) && owned in overlays) { "TOML parent observation has no authoritative open document" }
            parents(root, File(owned).toPath(), parentMap)
            val existing = observe(File(owned).toPath())
            require(existing == null || existing.type == "file") { "Open TOML document is a symlink or special file" }
        }
        val rootObservation = observe(root) ?: error("TOML workspace disappeared")
        require(rootObservation.type == "directory")
        val configs = listOf(root.resolve(".taplo.toml").toString(), root.resolve("taplo.toml").toString())
        val paths = (configs + dependencies.map { path(root.toString(), it) }.sorted()).distinct()
        require(paths.size <= MAX_PATHS) { "TOML dependency set exceeds 64 exact paths" }
        val entries = linkedMapOf<String, Entry>()
        var total = 0L
        for (name in paths) {
            val file = File(name).toPath()
            parents(root, file, parentMap)
            val before = observe(file)
            require(before == null || before.type == "file") { "TOML dependency is not a regular file" }
            val overlay = overlays[name]
            val bytes: ByteArray? = if (overlay != null) {
                require(LspTomlPositionAdmission.validText(overlay.text)) { "Invalid authoritative dirty TOML dependency" }
                overlay.text.toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FILE_BYTES) { "Dirty TOML dependency exceeds 1 MiB" } }
            } else if (before == null) null else {
                require(before.size <= MAX_FILE_BYTES) { "TOML dependency exceeds 1 MiB" }
                Files.newByteChannel(file, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteBuffer.allocate(16_384)
                    while (true) {
                        val count = channel.read(buffer)
                        if (count < 0) break
                        require(out.size() + count <= MAX_FILE_BYTES) { "TOML dependency grew past 1 MiB" }
                        out.write(buffer.array(), 0, count); buffer.clear()
                    }
                    out.toByteArray()
                }
            }
            val after = observe(file)
            require(before == after) { "TOML dependency changed during capture" }
            if (bytes != null) {
                total += bytes.size; require(total <= MAX_TOTAL_BYTES) { "TOML dependency map exceeds 8 MiB" }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                require(LspTomlPositionAdmission.validText(text)) { "TOML dependency contains invalid text" }
                if (name !in configs) admitSchema(text)
            } else require(name in configs) { "Requested local TOML schema is absent; schema-disabled fallback is unavailable" }
            val hash = bytes?.let(::sha)
            val origin = if (overlay != null) "dirty:${overlay.revision}" else "disk:${sha(before.toString())}"
            entries[name] = Entry(name, bytes, hash, origin, name in configs, before, overlay)
        }
        parentMap.forEach { (name, observation) -> require(observe(File(name).toPath()) == observation) { "TOML parent identity changed during capture" } }
        require(observe(root) == rootObservation)
        val generation = sha(buildString {
            append("toml-snapshot-v1\u0000").append(root).append('\n')
            parentMap.forEach { (name, observation) -> append(name).append('\u0000').append(observation).append('\n') }
            entries.forEach { (name, entry) -> append(name).append('\u0000').append(entry.hash ?: "ABSENT").append('\u0000').append(entry.origin).append('\u0000').append(entry.observation).append('\n') }
        })
        return Snapshot(root.toString(), LspTomlProfile.rootUri(root.toString()), generation, configs.firstOrNull { entries.getValue(it).present }, entries, parentMap, nanoTime(), documentPaths)
    }
    /** Structural compatibility only. Native Taplo remains the actual schema/instance validator. */
    fun admitSchema(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else if (c == '"') quoted = true else if (c == '{' || c == '[') { depth++; require(depth <= 64) { "Schema JSON depth exceeds 64" } }
            else if (c == '}' || c == ']') { depth--; require(depth >= 0) }
        }
        require(depth == 0 && !quoted)
        val root = Json.parseToJsonElement(text)
        fun schema(node: JsonElement): Boolean = node is JsonObject || node is JsonPrimitive && !node.isString && node.booleanOrNull != null
        require(schema(root)) { "Local schema must be an object or boolean" }
        val rootObject = root as? JsonObject
        val drafts = mapOf("http://json-schema.org/draft-04/schema#" to 4, "http://json-schema.org/draft-06/schema#" to 6, "http://json-schema.org/draft-07/schema#" to 7)
        val declared = rootObject?.get("\$schema")
        val draft = if (declared == null) 7 else drafts[(declared as? JsonPrimitive)?.takeIf { it.isString }?.content]
            ?: error("Unsupported local schema draft; expected exact Draft 4, 6 or 7 declaration")
        var nodes = 0; var refs = 0
        val active = IdentityHashMap<JsonElement, Boolean>()
        fun target(ref: String): JsonElement {
            require(ref.startsWith("#/") && ref.length <= 4096) { "Only bounded root-internal schema pointers are supported" }
            require(ref.drop(2).all { it.code in 0x21..0x7e && it !in "%#?\\[]" }) { "Encoding-dependent schema fragments are unavailable" }
            var node: JsonElement = root
            for (part in ref.substring(2).split('/')) {
                require(!Regex("~(?![01])").containsMatchIn(part)) { "Malformed schema pointer escaping" }
                val key = part.replace("~1", "/").replace("~0", "~")
                node = when (val at = node) {
                    is JsonObject -> at[key] ?: error("Schema pointer target is missing")
                    is JsonArray -> { require(key == "0" || key.matches(Regex("[1-9][0-9]*"))); at.getOrNull(key.toIntOrNull() ?: -1) ?: error("Schema pointer array target missing") }
                    else -> error("Schema pointer traverses a non-container")
                }
            }
            require(schema(node)) { "Schema pointer target is not a schema" }
            return node
        }
        fun walk(node: JsonElement, graphDepth: Int, isRoot: Boolean) {
            require(schema(node)); require(graphDepth <= 32) { "Schema graph exceeds 32 levels" }
            require(++nodes <= 20_000) { "Schema graph exceeds 20,000 nodes" }
            if (node !is JsonObject) return
            require(active.put(node, true) == null) { "Recursive local schema references are unavailable" }
            try {
                require(isRoot || "\$schema" !in node) { "Nested schema draft declarations are unavailable" }
                require((if (draft == 4) "id" else "\$id") !in node) { "Schema identifier rebasing is unavailable" }
                require(node.keys.none { it in setOf("\$dynamicRef", "\$dynamicAnchor", "\$recursiveRef", "\$recursiveAnchor", "\$anchor") }) { "Newer schema reference semantics are unavailable" }
                node["\$ref"]?.let { value ->
                    require(++refs <= 512) { "Schema exceeds 512 reference sites" }
                    val ref = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Schema reference is not text")
                    walk(target(ref), graphDepth + 1, false)
                }
                for (key in listOf("properties", "patternProperties", "definitions")) node[key]?.let { value ->
                    (value as? JsonObject ?: error("Malformed schema map")).values.forEach { walk(it, graphDepth + 1, false) }
                }
                node["items"]?.let { value -> if (value is JsonArray) value.forEach { walk(it, graphDepth + 1, false) } else walk(value, graphDepth + 1, false) }
                for (key in listOf("additionalItems", "additionalProperties", "contains", "propertyNames", "not", "if", "then", "else"))
                    node[key]?.let { walk(it, graphDepth + 1, false) }
                for (key in listOf("allOf", "anyOf", "oneOf")) node[key]?.let { value ->
                    (value as? JsonArray ?: error("Malformed schema composition")).forEach { walk(it, graphDepth + 1, false) }
                }
                node["dependencies"]?.let { value -> (value as? JsonObject ?: error("Malformed schema dependencies")).values.forEach {
                    if (it is JsonArray) require(it.all { item -> item is JsonPrimitive && item.isString }) else walk(it, graphDepth + 1, false)
                } }
            } finally { active.remove(node) }
        }
        walk(root, 0, true)
    }
}
