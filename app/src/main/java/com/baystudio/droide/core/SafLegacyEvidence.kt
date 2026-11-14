package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class SafLegacyManifestState { ABSENT, JSON_V2, OWNED, PATH_LIST, INVALID }
/** Supplied only after the root/tree-bound core admits global lineage and this exact visible inode. */
internal data class SafLegacyOwnedManifest(val visible: SafStoredFile, val trustedEntries: Map<String, String>)
internal enum class SafLegacyBlockReason { PENDING_WRITE, POSSIBLE_CREATION, ACTIVATION, MIGRATION, UNREADABLE_OR_UNKNOWN }
internal data class SafLegacyBlock(val scopes: List<String>, val reason: SafLegacyBlockReason)
internal data class SafLegacySource(val path: String, val key: String, val bytes: Long?, val sha256: String?)
internal data class SafLegacyAdmission(
    val blockers: List<SafLegacyBlock>, val manifestState: SafLegacyManifestState,
    val baselineEntries: Map<String, String>, val sources: List<SafLegacySource>,
    val workspacePath: String, val workspaceKey: String?, val treeUri: String,
    private val revalidateEvidence: (() -> Unit) -> Unit,
) {
    private var cancelled = false
    val requiresFullRefreshMigration: Boolean get() = manifestState == SafLegacyManifestState.PATH_LIST
    /** Deep, cancellable evidence rehash; consume this unchanged baseline immediately under the same root guard.
     * This observation is never a reusable lease or an authority to clear/replay legacy evidence. */
    fun revalidateAndRequireDisjoint(workspace: File, paths: List<String>, checkActive: () -> Unit) {
        check(!cancelled) { "Legacy admission was cancelled" }
        try {
            requireDisjointAfterRevalidation(workspace, paths, checkActive)
        } catch (failure: CancellationException) {
            cancelled = true
            throw failure
        }
    }

    private fun requireDisjointAfterRevalidation(workspace: File, paths: List<String>, checkActive: () -> Unit) {
        require(workspace.absoluteFile.toPath().normalize().toString() == workspacePath)
        revalidateEvidence(checkActive)
        val root = Files.readAttributes(workspace.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        check(root.isDirectory && !root.isSymbolicLink && root.fileKey()?.toString() == workspaceKey) { "Legacy admission belongs to a different workspace identity" }
        require(paths.isNotEmpty())
        paths.forEach { path ->
            checkActive()
            SafOperationJournal.validPath(path, true)
            // Even an in-root symlink alias cannot turn a lexical disjoint path into overlap.
            var current = workspace
            check(!Files.isSymbolicLink(current.toPath()))
            path.split('/').filter { it.isNotEmpty() }.forEach {
                checkActive()
                current = File(current, it)
                check(!Files.isSymbolicLink(current.toPath())) { "SAF retained evidence rejects aliased paths" }
            }
            check(blockers.none { block -> block.scopes.any { SafOperationJournal.overlaps(it, path) } }) {
                "SAF retained legacy evidence overlaps '$path'; no recovery or cleanup was attempted"
            }
        }
    }
}

internal data class SafLegacyLimits(
    val entries: Int = 4096, val recordBytes: Long = 16_384,
    val metadataBytes: Long = 16L * 1024 * 1024, val payloadBytes: Long = Long.MAX_VALUE,
) {
    init { require(entries > 0 && recordBytes in 1..Int.MAX_VALUE.toLong() && metadataBytes in 1..Int.MAX_VALUE.toLong() && payloadBytes >= 0) }
}

/** Strictly read-only admission of the deployed old journal/marker, never a migration or cleanup authority. */
internal class SafLegacyEvidence(
    private val filesDir: File,
    private val workspace: File,
    private val treeUri: String,
    private val storage: SafOperationStorage = SafOperationStorage(),
    private val limits: SafLegacyLimits = SafLegacyLimits(),
) {
    @Serializable private data class Pending(val path: String, val previous: String?, val intended: String)
    @Serializable private data class Activation(val version: Int = 1, val roots: List<String>, val stage: String, val backup: String)
    @Serializable private data class Manifest(val version: Int = 2, val entries: Map<String, String> = emptyMap())

    private val omittedDefaults = Json { ignoreUnknownKeys = false }
    private val includedDefaults = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun inspect(checkActive: () -> Unit = {}): SafLegacyAdmission = inspectInternal(checkActive, null)

    fun inspectOwned(ownedManifest: SafLegacyOwnedManifest, checkActive: () -> Unit = {}): SafLegacyAdmission {
        SafDurableManifestCommit.validateEntries(ownedManifest.trustedEntries, checkActive)
        return inspectInternal(checkActive, ownedManifest.copy(trustedEntries = Collections.unmodifiableMap(LinkedHashMap(ownedManifest.trustedEntries))))
    }

    private fun inspectInternal(checkActive: () -> Unit, ownedManifest: SafLegacyOwnedManifest?): SafLegacyAdmission {
        val blockers = arrayListOf<SafLegacyBlock>()
        val sources = arrayListOf<SafLegacySource>()
        var state = SafLegacyManifestState.ABSENT
        var baseline: Map<String, String> = emptyMap()
        var metadataBytesRead = 0L
        var payloadBytesRead = 0L
        var admittedWorkspaceKey: String? = null
        val slots = linkedMapOf<File, String?>()
        val namespaces = linkedMapOf<File, Map<String, String>>()
        val retainedPaths = linkedSetOf<String>()
        var admitted = false
        fun rootBlock() { blockers += SafLegacyBlock(listOf(""), SafLegacyBlockReason.UNREADABLE_OR_UNKNOWN) }
        fun slot(file: File): Boolean {
            storage.requireRealAncestors(file)
            val attrs = storage.attributes(file)
            slots[file] = attrs?.let { requireNotNull(it.fileKey()).toString() }
            return attrs != null
        }
        fun unaliased(path: String, poll: () -> Unit = checkActive) {
            poll()
            // Retained scopes must be checked as well as incoming paths; never resolve an alias into authority.
            storage.requireRealAncestors(File(workspace, path))
        }
        fun directory(file: File): String {
            storage.requireRealAncestors(file)
            return storage.key(file, true).also { sources += SafLegacySource(file.absolutePath, it, null, null) }
        }
        fun metadata(file: File, maximum: Long): ByteArray {
            val bytes = storage.read(file, minOf(maximum, limits.metadataBytes - metadataBytesRead), checkActive)
            metadataBytesRead = SafOperationJournal.boundedAdd(metadataBytesRead, bytes.size.toLong(), limits.metadataBytes)
            val identity = storage.describe(file, bytes.size.toLong(), checkActive)
            check(identity.bytes == bytes.size.toLong() && identity.sha256 == bytes.safDigest(checkActive))
            sources += SafLegacySource(file.absolutePath, identity.key, identity.bytes, identity.sha256)
            return bytes
        }
        fun payload(file: File, expected: String) {
            fingerprint(expected)
            storage.requireRealAncestors(file)
            val identity = storage.describe(file, limits.payloadBytes - payloadBytesRead, checkActive)
            payloadBytesRead = SafOperationJournal.boundedAdd(payloadBytesRead, identity.bytes, limits.payloadBytes)
            check("f:${identity.sha256}" == expected) { "Incomplete legacy SAF payload" }
            sources += SafLegacySource(file.absolutePath, identity.key, identity.bytes, identity.sha256)
        }
        fun children(file: File, poll: () -> Unit = checkActive): Map<String, String> {
            val out = sortedMapOf<String, String>()
            Files.newDirectoryStream(file.toPath()).use { stream ->
                for (path in stream) {
                    poll()
                    check(out.size < limits.entries) { "Legacy SAF namespace capacity exceeded" }
                    val attrs = requireNotNull(storage.attributes(path.toFile()))
                    check(!attrs.isSymbolicLink && (attrs.isDirectory || attrs.isRegularFile))
                    out[path.fileName.toString()] = requireNotNull(attrs.fileKey()).toString()
                }
            }
            return out
        }
        fun revalidate(poll: () -> Unit) {
            // Deep reads can take time. Namespace/absence checks must be LAST, after all content revalidation.
            sources.forEach {
                poll()
                val file = File(it.path)
                storage.requireRealAncestors(file)
                if (it.bytes == null) check(storage.key(file, true) == it.key)
                else check(storage.describe(file, it.bytes, poll) == SafStoredFile(it.key, it.bytes, requireNotNull(it.sha256)))
            }
            retainedPaths.forEach { unaliased(it, poll) }
            namespaces.forEach { (file, expected) ->
                poll()
                storage.requireRealAncestors(file)
                check(children(file, poll) == expected) { "Legacy evidence namespace changed after inspection" }
            }
            slots.forEach { (file, key) ->
                poll()
                storage.requireRealAncestors(file)
                check(storage.attributes(file)?.let { requireNotNull(it.fileKey()).toString() } == key) { "Legacy evidence slot changed after inspection" }
            }
            sources.forEach { poll(); if (it.bytes == null) check(storage.key(File(it.path), true) == it.key) }
        }
        try {
            checkActive()
            require(treeUri.startsWith("content://") && treeUri.length <= 256 * 1024 && '\u0000' !in treeUri)
            val parsedTree = java.net.URI(treeUri)
            require(parsedTree.scheme == "content" && !parsedTree.rawAuthority.isNullOrEmpty() && parsedTree.userInfo == null &&
                parsedTree.port == -1 && parsedTree.rawQuery == null && parsedTree.rawFragment == null &&
                parsedTree.rawPath.matches(Regex("/tree/[^/]+")))
            val workspaceKey = directory(workspace)
            admittedWorkspaceKey = workspaceKey
            storage.requireRealAncestors(filesDir)
            val meta = File(workspace, ".droide")
            storage.requireRealAncestors(meta)
            val metaKey = if (slot(meta)) directory(meta) else null
            val manifest = File(meta, "saf_manifest.json")
            val pathList = File(meta, "saf_manifest.txt")
            val hasManifest = slot(manifest)
            val hasPathList = slot(pathList)
            val marker = File(meta, "saf_activation_pending.json")
            val hasMarker = slot(marker)
            if (ownedManifest != null) check(hasManifest) { "Core-admitted SAF manifest disappeared" }
            if (hasManifest) {
                state = SafLegacyManifestState.INVALID // Any decode/validation failure stays invalid, never an empty bootstrap.
                val bytes = metadata(manifest, limits.metadataBytes)
                if (ownedManifest != null) {
                    check(storage.describe(manifest, limits.metadataBytes, checkActive) == ownedManifest.visible &&
                        bytes.size.toLong() == ownedManifest.visible.bytes && bytes.safDigest(checkActive) == ownedManifest.visible.sha256)
                    SafDurableManifestCommit.validateEntries(ownedManifest.trustedEntries, checkActive)
                    baseline = Collections.unmodifiableMap(LinkedHashMap(ownedManifest.trustedEntries))
                    state = SafLegacyManifestState.OWNED
                    if (hasPathList) {
                        metadata(pathList, minOf(2_000_000L, limits.metadataBytes))
                        blockers += SafLegacyBlock(listOf(""), SafLegacyBlockReason.MIGRATION)
                    }
                } else {
                    val parsed = decode(Manifest.serializer(), bytes)
                    require(parsed.version == 2)
                    SafDurableManifestCommit.validateEntries(parsed.entries, checkActive)
                    baseline = Collections.unmodifiableMap(LinkedHashMap(parsed.entries))
                    state = SafLegacyManifestState.JSON_V2
                }
            } else if (hasPathList) {
                state = SafLegacyManifestState.INVALID
                val text = utf8(metadata(pathList, minOf(2_000_000L, limits.metadataBytes)))
                val names = linkedSetOf<String>()
                text.lineSequence().forEach { raw ->
                    checkActive()
                    val path = raw.trim().trimEnd('/')
                    if (path.isNotEmpty()) {
                        SafOperationJournal.validPath(path, false)
                        check(names.size < limits.entries && names.add(path))
                    }
                }
                state = SafLegacyManifestState.PATH_LIST
                blockers += SafLegacyBlock(listOf(""), SafLegacyBlockReason.MIGRATION)
            }
            if (hasMarker) {
                val pending = decode(Activation.serializer(), metadata(marker, limits.metadataBytes))
                require(pending.version == 1 && pending.roots.isNotEmpty() && pending.roots.size <= limits.entries)
                require(pending.roots.distinct().size == pending.roots.size)
                pending.roots.forEach { checkActive(); PathSecurity.safeLeafName(it); SafOperationJournal.validPath(it, false); unaliased(it); retainedPaths += it }
                PathSecurity.safeLeafName(pending.stage)
                PathSecurity.safeLeafName(pending.backup)
                require(pending.stage != pending.backup)
                blockers += SafLegacyBlock(Collections.unmodifiableList(pending.roots.sorted()), SafLegacyBlockReason.ACTIVATION)
            }
            val recovery = recoveryRoot()
            storage.requireRealAncestors(recovery)
            if (slot(recovery)) {
                val recoveryKey = directory(recovery)
                val originalEntries = children(recovery)
                namespaces[recovery] = originalEntries
                for ((name, key) in originalEntries) {
                    checkActive()
                    SafOperationJournal.requireUuid(name)
                    val entry = File(recovery, name)
                    check(directory(entry) == key)
                    val files = children(entry)
                    namespaces[entry] = files
                    require(files.keys.all { it in setOf("pending.json", "previous", "intended") } && "pending.json" in files && "intended" in files)
                    val record = decode(Pending.serializer(), metadata(File(entry, "pending.json"), limits.recordBytes))
                    SafOperationJournal.validPath(record.path, false)
                    unaliased(record.path)
                    retainedPaths += record.path
                    fingerprint(record.intended)
                    if (record.previous != null) {
                        require("previous" in files)
                        payload(File(entry, "previous"), record.previous)
                    } else require("previous" !in files)
                    payload(File(entry, "intended"), record.intended)
                    check(children(entry) == files && storage.key(entry, true) == key) { "Legacy evidence changed during inspection" }
                    val scope = if (record.previous == null) record.path.substringBeforeLast('/', "") else record.path
                    blockers += SafLegacyBlock(listOf(scope), if (record.previous == null) SafLegacyBlockReason.POSSIBLE_CREATION else SafLegacyBlockReason.PENDING_WRITE)
                }
                check(children(recovery) == originalEntries && storage.key(recovery, true) == recoveryKey)
            }
            check(storage.key(workspace, true) == workspaceKey)
            check((if (storage.attributes(meta) == null) null else storage.key(meta, true)) == metaKey)
            revalidate(checkActive)
            admitted = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            rootBlock()
        }
        return SafLegacyAdmission(Collections.unmodifiableList(blockers), state, baseline, Collections.unmodifiableList(sources),
            workspace.absoluteFile.toPath().normalize().toString(), admittedWorkspaceKey, treeUri) { poll ->
            check(admitted) { "Legacy evidence was not completely admitted" }
            try { revalidate(poll) }
            catch (failure: Throwable) { admitted = false; throw failure }
        }
    }

    internal fun recoveryRoot(): File = File(filesDir, ".droide/saf-write-recovery/" +
        (treeUri + "\u0000" + workspace.absolutePath).toByteArray(Charsets.UTF_8).safDigest())

    private fun fingerprint(value: String) { require(value.matches(Regex("f:[0-9a-f]{64}"))) }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T {
        val value = omittedDefaults.decodeFromString(serializer, utf8(bytes))
        // Admit both encodings actually emitted by the old writer / explicit version fixtures.
        // Duplicate keys, unknown keys and noncanonical ambiguous metadata remain blockers.
        require(omittedDefaults.encodeToString(serializer, value).toByteArray(Charsets.UTF_8).contentEquals(bytes) ||
            includedDefaults.encodeToString(serializer, value).toByteArray(Charsets.UTF_8).contentEquals(bytes))
        return value
    }
}
