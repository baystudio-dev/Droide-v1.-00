package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Bounded observations of current bytes are never evidence of an earlier transaction's commit. */
internal object ProjectCopyEvidence {
    const val MAX_INSPECTION_BYTES = 64L * 1024 * 1024
    const val MAX_COPY_BYTES = 4L * 1024 * 1024 * 1024
    val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    val SHA256_PATTERN = Regex("[0-9a-f]{64}")

    data class Identity(val key: String, val size: Long, val modified: String, val type: String)
    data class Current(val parents: List<Pair<String, String>>, val target: Identity?, val sha256: String?)
    enum class StagePresence { LEGACY_UNAVAILABLE, ABSENT, PRESENT, UNVERIFIABLE }
    enum class StageStatus { LEGACY_UNAVAILABLE, ABSENT, UNVERIFIED, VERIFIED, MISMATCH, TOO_LARGE, UNSAFE }
    @Serializable data class StageParent(val segment: String, val identity: String)
    data class StageInspection(val relativePath: String?, val status: StageStatus,
        val bytes: Long? = null, val identity: String? = null, val sha256: String? = null, val detail: String? = null,
        val modified: String? = null, val changed: String? = null, val parents: List<StageParent> = emptyList())

    fun validateStagePath(destination: String, path: String, operationId: String? = null) {
        validateDestination(destination)
        val parent = destination.substringBeforeLast('/', "")
        val expectedParent = path.substringBeforeLast('/', "")
        val leaf = path.substringAfterLast('/')
        require(path.length <= 547 && !File(path).isAbsolute && '\\' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." } && parent == expectedParent &&
            leaf.startsWith(".droide-tmp-") && UUID_PATTERN.matches(leaf.removePrefix(".droide-tmp-")) &&
            (operationId == null || leaf == ".droide-tmp-$operationId")) { "Copy stage is not the planned destination sibling" }
    }

    fun stageParents(workspaceRoot: File, expectedRootKey: String, path: String): List<StageParent> =
        AnchoredWorkspaceFiles(workspaceRoot).use { files ->
            val before = stageParents(files, expectedRootKey, path)
            check(stageParents(files, expectedRootKey, path) == before) { "Stage parents changed during observation" }
            before
        }

    private fun stageParents(files: AnchoredWorkspaceFiles, expectedRootKey: String, path: String): List<StageParent> {
        require(files.directoryKey() == expectedRootKey) { "Project identity changed" }
        val parents = mutableListOf(StageParent("", expectedRootKey))
        var relative = ""
        for (part in path.split('/').dropLast(1)) {
            relative = if (relative.isEmpty()) part else "$relative/$part"
            parents += StageParent(part, files.directoryKey(relative))
        }
        return parents
    }

    fun stagePresence(workspaceRoot: File, expectedRootKey: String, path: String?,
        expectedParents: List<StageParent>? = null): StagePresence {
        if (path == null) return StagePresence.LEGACY_UNAVAILABLE
        return try {
            require(root(workspaceRoot) == (workspaceRoot to expectedRootKey))
            AnchoredWorkspaceFiles(workspaceRoot).use { files ->
                val parents = stageParents(files, expectedRootKey, path)
                require(expectedParents == null || expectedParents == parents) { "Stage parent identity changed" }
                when (files.kind(path)) {
                    AnchoredWorkspaceFiles.Kind.ABSENT -> if (expectedParents == null && parents.size > 1)
                        StagePresence.UNVERIFIABLE else StagePresence.ABSENT
                    AnchoredWorkspaceFiles.Kind.FILE -> StagePresence.PRESENT
                    else -> StagePresence.UNVERIFIABLE
                }.also {
                    require(stageParents(files, expectedRootKey, path) == parents) { "Stage parents changed" }
                    require(root(workspaceRoot) == (workspaceRoot to expectedRootKey))
                }
            }
        } catch (_: Exception) { StagePresence.UNVERIFIABLE }
    }

    fun inspectStage(workspaceRoot: File, expectedRootKey: String, path: String?,
        expected: StreamPublicationPreparation?, expectedParents: List<StageParent>? = null): StageInspection {
        if (path == null) return StageInspection(null, StageStatus.LEGACY_UNAVAILABLE)
        return try {
            require(root(workspaceRoot) == (workspaceRoot to expectedRootKey)) { "Project identity changed" }
            AnchoredWorkspaceFiles(workspaceRoot).use { files ->
                val parents = stageParents(files, expectedRootKey, path)
                require(expectedParents == null || expectedParents == parents) { "Stage parent identity changed" }
                if (files.kind(path) == AnchoredWorkspaceFiles.Kind.ABSENT) {
                    require(stageParents(files, expectedRootKey, path) == parents) { "Stage parents changed" }
                    require(root(workspaceRoot) == (workspaceRoot to expectedRootKey))
                    return if (expectedParents == null && parents.size > 1)
                        StageInspection(path, StageStatus.UNSAFE,
                            detail = "Parent history was never bound; the candidate may be absent or moved", parents = parents)
                    else StageInspection(path, StageStatus.ABSENT, parents = parents)
                }
                files.openRead(path).use { input ->
                    val before = input.observe()
                    val key = requireNotNull(before.attributes.fileKey()).toString()
                    val size = before.attributes.size()
                    val result = when {
                        expected == null -> StageInspection(path, StageStatus.UNVERIFIED, size, key,
                            detail = "No recorded attempt binds this candidate to the original payload")
                        key != expected.stageIdentity || size != expected.bytes ->
                            StageInspection(path, StageStatus.MISMATCH, size, key, detail = "Recorded identity or length differs")
                        size > MAX_INSPECTION_BYTES -> StageInspection(path, StageStatus.TOO_LARGE, size, key,
                            detail = "Content exceeds the 64 MiB inspection budget")
                        else -> {
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(32_768)
                            var count = 0L
                            var emptyReads = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) { check(++emptyReads <= 16) { "Stage stopped providing bytes" }; continue }
                                check(read.toLong() <= size - count) { "Stage grew during inspection" }
                                digest.update(buffer, 0, read); count += read
                            }
                            check(count == size) { "Stage changed length during inspection" }
                            val hash = hex(digest.digest())
                            StageInspection(path, if (hash == expected.sha256) StageStatus.VERIFIED else StageStatus.MISMATCH,
                                size, key, hash)
                        }
                    }
                    fun unchanged(observed: AnchoredWorkspaceFiles.Observation) =
                        observed.attributes.fileKey() == before.attributes.fileKey() && observed.attributes.size() == size &&
                            observed.attributes.lastModifiedTime() == before.attributes.lastModifiedTime() &&
                            observed.changed == before.changed && observed.executable == before.executable
                    check(unchanged(input.observe()) && files.openRead(path).use { unchanged(it.observe()) }) {
                        "Stage identity or bytes changed during inspection"
                    }
                    require(root(workspaceRoot) == (workspaceRoot to expectedRootKey)) { "Project identity changed" }
                    require(stageParents(files, expectedRootKey, path) == parents) { "Stage parents changed" }
                    result.copy(modified = before.attributes.lastModifiedTime().toString(), changed = before.changed.toString(),
                        parents = parents)
                }
            }
        } catch (failure: Exception) {
            StageInspection(path, StageStatus.UNSAFE, detail = (failure.message ?: "Stage could not be safely inspected").take(300))
        }
    }

    fun root(root: File): Pair<File, String> {
        val canonical = ChatMediaStoragePaths.canonicalWorkspace(root)
        val identity = identity(canonical)
        require(identity.type == "directory") { "Project root identity is unavailable" }
        return canonical to identity.key
    }

    fun identity(file: File): Identity {
        val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val key = requireNotNull(attrs.fileKey()) { "File identity cannot be verified" }.toString()
        require(key.length in 1..512 && key.none { it.code < 0x20 }) { "Invalid filesystem identity" }
        return Identity(key, attrs.size(), attrs.lastModifiedTime().toString(), when {
            attrs.isSymbolicLink -> "link"
            attrs.isRegularFile -> "file"
            attrs.isDirectory -> "directory"
            else -> "special"
        })
    }

    /** Only an explicit no-such-file result is absence; access/I/O errors remain errors. */
    fun identityOrAbsent(file: File, read: (File) -> Identity = ::identity): Identity? =
        try { read(file) } catch (_: NoSuchFileException) { null }

    fun validateDestination(path: String) {
        require(path.isNotBlank() && path.length <= 500 && !File(path).isAbsolute && '\\' !in path)
        val parts = path.split('/')
        require(parts.size <= 32 && parts.none { it == ".git" || it == ".droide" })
        parts.forEach(PathSecurity::safeLeafName)
    }

    fun inspect(workspaceRoot: File, expectedRootKey: String, path: String): Current =
        inspectCurrent(workspaceRoot, expectedRootKey, path, content = true)

    fun inspectMetadata(workspaceRoot: File, expectedRootKey: String, path: String): Current =
        inspectCurrent(workspaceRoot, expectedRootKey, path, content = false)

    private fun inspectCurrent(workspaceRoot: File, expectedRootKey: String, path: String, content: Boolean): Current {
        validateDestination(path)
        require(root(workspaceRoot) == (workspaceRoot to expectedRootKey)) { "Project identity changed; inspect the active project again" }
        val parents = mutableListOf("" to expectedRootKey)
        var current = workspaceRoot
        val parts = path.split('/')
        var relative = ""
        for (part in parts.dropLast(1)) {
            relative = if (relative.isEmpty()) part else "$relative/$part"
            current = File(current, part)
            val identity = identityOrAbsent(current)
            if (identity == null) {
                // Missing parents are evidence too. A new folder invalidates the confirmation.
                parents += relative to "absent"
                continue
            }
            require(identity.type == "directory") { "Destination parent is not a verified project directory" }
            parents += relative to identity.key
        }
        val target = File(workspaceRoot, path)
        val before = identityOrAbsent(target)
        if (before == null) return Current(parents, null, null).also {
            require(root(workspaceRoot).second == expectedRootKey)
            requireParents(workspaceRoot, parents)
        }
        if (!content) return Current(parents, before, null).also {
            require(identity(target) == before) { "Destination changed during metadata inspection" }
            require(root(workspaceRoot).second == expectedRootKey)
            requireParents(workspaceRoot, parents)
        }
        require(before.type == "file" && before.size in 0..MAX_INSPECTION_BYTES) {
            "Recovery can inspect only regular files up to 64 MiB. Preserve or move this destination manually first."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(target.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == before.size && identity(target) == before) { "Destination changed while opening" }
            val buffer = ByteBuffer.allocate(32_768)
            var total = 0L
            var emptyReads = 0
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) { check(++emptyReads <= 16) { "Destination stopped providing data" }; continue }
                emptyReads = 0
                total += read
                require(total <= before.size) { "Destination grew during inspection" }
                digest.update(buffer.array(), 0, read)
            }
            require(total == before.size && channel.size() == before.size && identity(target) == before) {
                "Destination changed during inspection"
            }
        }
        require(root(workspaceRoot).second == expectedRootKey)
        requireParents(workspaceRoot, parents)
        return Current(parents, before, hex(digest.digest()))
    }

    private fun requireParents(root: File, parents: List<Pair<String, String>>) {
        parents.forEach { (relative, key) ->
            val file = if (relative.isEmpty()) root else File(root, relative)
            if (key == "absent") require(identityOrAbsent(file) == null) { "Destination parent appeared" }
            else require(identity(file).let { it.key == key && it.type == "directory" }) { "Destination parent changed" }
        }
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
