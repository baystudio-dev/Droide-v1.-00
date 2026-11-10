package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Bounded cooperative observations, performed off admission locks and only after consent. */
internal object LspGraphqlSnapshotAuthority {
    const val MAX_SOURCE_BYTES = 1_048_576
    const val MAX_TOTAL_BYTES = 8_388_608
    const val MAX_DESCRIPTOR_BYTES = 12_582_912
    const val OBSERVATION_MAX_AGE_NANOS = 2_000_000_000L
    class Overlay(val text: String, val openGeneration: Long, val revision: Long)
    data class Observation(val path: String, val type: String, val key: String, val size: Long, val modified: String, val created: String, val permissions: String)
    data class Root(val path: String, val identity: String, val parents: Map<String, Observation>)
    class Entry(val path: String, val relativePath: String, val role: String, val text: String, val digest: String,
        val overlay: Overlay?, val observation: Observation?) {
        fun source() = GraphqlCapturedSource(role, LspGraphqlProfile.uri(path), relativePath, text, digest,
            if (overlay != null) "editor" else "disk", overlay?.openGeneration, overlay?.revision)
    }
    class Snapshot(val root: Root, val generation: String, val configDigest: String, val schemaDigest: String,
        val fragmentsDigest: String, val entries: Map<String, Entry>, val observations: Map<String, Observation?>,
        val parents: Map<String, Observation>, val capturedOverlays: Map<String, Overlay?>, val observedAtNanos: Long) {
        val sources: List<GraphqlCapturedSource> get() = entries.values.map { it.source() }
        fun overlaysCurrent(overlays: Map<String, Overlay>): Boolean = capturedOverlays.all { (path, overlay) -> overlays[path] === overlay }
        fun sameGraph(other: Snapshot): Boolean = generation == other.generation && root.identity == other.root.identity
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha(text: String): String = sha(text.toByteArray(Charsets.UTF_8))
    fun path(root: String, input: String): String {
        require(input.isNotEmpty() && !input.contains('\\') && input.none { it.isISOControl() })
        val full = if (input.startsWith('/')) input else root.trimEnd('/') + "/" + input
        require(full.startsWith(root.trimEnd('/') + "/") && full.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." })
        require(!SensitivePathPolicy.isSensitive(full)) { "Sensitive GraphQL path unavailable" }
        return full
    }
    private fun observe(path: Path): Observation? {
        val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS) } catch (_: java.nio.file.NoSuchFileException) { return null }
        require(!attrs.isSymbolicLink && (attrs.isRegularFile || attrs.isDirectory)) { "GraphQL paths cannot be symlinks or special files" }
        require(Files.isReadable(path))
        if (attrs.isRegularFile) {
            val count = runCatching { (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong() }.getOrNull()
            require(count == null || count == 1L) { "GraphQL hard-linked sources are unavailable" }
        }
        val perms = try { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).map { it.name }.sorted().joinToString(",") }
            catch (_: UnsupportedOperationException) { "readable=${Files.isReadable(path)};executable=${Files.isExecutable(path)}" }
        return Observation(path.toString(), if (attrs.isDirectory) "directory" else "file", attrs.fileKey()?.toString() ?: error("No stable GraphQL file identity"),
            if (attrs.isDirectory) 0 else attrs.size(), if (attrs.isDirectory) "" else attrs.lastModifiedTime().toString(), attrs.creationTime().toString(), perms)
    }
    private fun parents(target: Path, into: MutableMap<String, Observation>) {
        val chain = mutableListOf<Path>(); var current = target.parent
        while (current != null) { chain.add(current); current = current.parent }
        require(chain.size <= 64)
        for (p in chain.asReversed()) {
            val found = observe(p) ?: error("GraphQL parent disappeared")
            require(found.type == "directory" && Files.isExecutable(p))
            val old = into.putIfAbsent(p.toString(), found); require(old == null || old == found)
            require(into.size <= 256)
        }
    }
    /** Metadata only. Allowed before the prompt; never reads config/package/schema bytes. */
    fun root(workspace: File): Root {
        val root = workspace.absoluteFile.toPath()
        require(root.toString() != "/" && root == root.normalize() && root.nameCount <= 64)
        val map = linkedMapOf<String, Observation>(); parents(root.resolve(LspGraphqlConfiguration.CONFIG), map)
        require(workspace.canonicalPath == root.toString())
        return Root(root.toString(), sha(map.entries.joinToString("\n") { "${it.key}:${it.value}" }), map.toMap())
    }
    data class Primary(val path: String, val parents: Map<String, Observation>, val observation: Observation?)
    fun primary(root: Root, path: String): Primary {
        val map = linkedMapOf<String, Observation>(); val target = File(path(root.path, path)).toPath(); parents(target, map)
        require(map.filterKeys { it in root.parents } == root.parents)
        val observation = observe(target); require(observation?.type.let { it == null || it == "file" })
        return Primary(target.toString(), map.toMap(), observation)
    }
    fun primaryCurrent(primary: Primary): Boolean = primary.parents.all { (p, o) -> observe(File(p).toPath()) == o } && observe(File(primary.path).toPath()) == primary.observation
    fun capture(workspace: File, overlays: Map<String, Overlay>, nanoTime: () -> Long = System::nanoTime): Snapshot {
        val root = root(workspace); val parentMap = root.parents.toMutableMap(); val observations = linkedMapOf<String, Observation?>()
        val entries = linkedMapOf<String, Entry>(); var total = 0L
        fun read(relative: String, role: String, maximum: Int, optional: Boolean = false): Entry? {
            val full = path(root.path, relative); val target = File(full).toPath(); parents(target, parentMap)
            val before = observe(target); require(before == null || before.type == "file")
            val overlay = overlays[full]
            val bytes = if (overlay != null) {
                require(LspGraphqlPositionCodec.validText(overlay.text)); overlay.text.toByteArray(Charsets.UTF_8).also { require(it.size <= maximum) }
            } else if (before == null) null else {
                require(before.size <= maximum)
                Files.newByteChannel(target, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteBuffer.allocate(16_384)
                    while (true) { val n = channel.read(buffer); if (n < 0) break; require(out.size() + n <= maximum); out.write(buffer.array(), 0, n); buffer.clear() }
                    out.toByteArray()
                }
            }
            require(before == observe(target)) { "GraphQL source changed during capture" }; observations[full] = before
            if (bytes == null) { require(optional) { "Required GraphQL input is missing: $relative" }; return null }
            total += bytes.size; require(total <= MAX_TOTAL_BYTES)
            val text = LspGraphqlConfiguration.decode(bytes, maximum)
            return Entry(full, relative, role, text, sha(bytes), overlay, before).also { if (role != "metadata") entries[full] = it }
        }
        // Competitor presence blocks without opening its content, including symlinks and special files.
        val competing = LspGraphqlConfiguration.competitors.filter { name ->
            val full = path(root.path, name); overlays.containsKey(full) || Files.exists(File(full).toPath(), NOFOLLOW_LINKS)
        }
        require(competing.isEmpty()) { "Competing root GraphQL configuration: ${competing.joinToString(", ")}" }
        val config = requireNotNull(read(LspGraphqlConfiguration.CONFIG, "config", LspGraphqlConfiguration.CONFIG_BYTES))
        val selection = LspGraphqlConfiguration.selection(config.text.toByteArray(Charsets.UTF_8))
        read("package.json", "metadata", LspGraphqlConfiguration.PACKAGE_BYTES, true)?.let { LspGraphqlConfiguration.admitPackage(it.text.toByteArray(Charsets.UTF_8)) }
        selection.schema.forEach { requireNotNull(read(it, "schema", MAX_SOURCE_BYTES)).also { e -> LspGraphqlConfiguration.admitSource(e.text) } }
        selection.fragments.forEach { requireNotNull(read(it, "fragment", MAX_SOURCE_BYTES)).also { e -> LspGraphqlConfiguration.admitSource(e.text) } }
        parentMap.forEach { (path, expected) -> require(observe(File(path).toPath()) == expected) }
        val schemaDigest = sha(entries.values.filter { it.role == "schema" }.joinToString("\n") { "${it.relativePath}:${it.digest}" })
        val fragmentsDigest = sha(entries.values.filter { it.role == "fragment" }.joinToString("\n") { "${it.relativePath}:${it.digest}" })
        val generation = sha(root.identity + "\n" + entries.values.joinToString("\n") { "${it.path}:${it.role}:${it.digest}:${it.overlay?.openGeneration}:${it.overlay?.revision}:${it.observation}" } +
            "\n" + observations + "\n" + parentMap)
        require(entries.size <= 41)
        return Snapshot(root, generation, config.digest, schemaDigest, fragmentsDigest, entries.toMap(), observations.toMap(), parentMap.toMap(), observations.keys.associateWith { overlays[it] }, nanoTime())
    }
    /** Metadata and overlay identity observation only; unchanged graphs are not rehashed per keystroke. */
    fun observeCurrent(snapshot: Snapshot, overlays: Map<String, Overlay>): Boolean {
        if (!snapshot.overlaysCurrent(overlays)) return false
        if (LspGraphqlConfiguration.competitors.any { overlays.containsKey(path(snapshot.root.path, it)) || Files.exists(File(path(snapshot.root.path, it)).toPath(), NOFOLLOW_LINKS) }) return false
        return snapshot.parents.all { (p, o) -> observe(File(p).toPath()) == o } && snapshot.observations.all { (p, o) -> observe(File(p).toPath()) == o }
    }
}
