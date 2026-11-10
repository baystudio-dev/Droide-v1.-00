package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer

@Serializable internal enum class SafLocalRole { WORKSPACE, ACTIVATION_STAGE, ACTIVATION_BACKUP }
@Serializable internal enum class SafLocalKind { FILE, DIRECTORY, SYMLINK }
@Serializable internal enum class SafLocalEffect { WRITE, CREATE_PARENTS, CREATE_DIRECTORY, DELETE_STAGE, MOVE, ACTIVATE_SAVE_ORIGINAL, ACTIVATE_PUBLISH }
@Serializable internal data class SafLocalPath(val role: SafLocalRole, val path: String)
@Serializable internal data class SafLocalVersion(val kind: SafLocalKind, val key: String, val bytes: Long, val modified: String)
@Serializable internal data class SafLocalParent(val path: String, val key: String)
@Serializable internal data class SafDeleteCustody(val directory: String, val directoryKey: String)
@Serializable internal data class SafLocalPlan(
    val effect: SafLocalEffect, val source: SafLocalPath?, val destination: SafLocalPath,
    val sourceVersion: SafLocalVersion?, val expectedDestination: SafLocalVersion?,
    val parents: List<SafLocalParent>, val missingDirectories: List<String> = emptyList(),
    val beforeInventory: SafEvidenceFile? = null, val desiredInventory: SafEvidenceFile? = null,
    val intended: SafEvidenceFile? = null, val previous: SafEvidenceFile? = null,
    val activationOrdinal: Int? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val deleteCustody: SafDeleteCustody? = null,
)
@Serializable internal data class SafLocalReturn(
    val invocationReturned: Boolean, val successful: Boolean, val destinationKey: String?, val reportedDurable: Boolean,
    val diagnosticClass: String? = null, val diagnosticMessage: String? = null, val diagnosticTruncated: Boolean = false,
)
@Serializable internal data class SafLocalVerified(
    val destination: SafLocalVersion, val contentDigest: String?, val inventoryDigest: String?,
    val createdDirectories: List<SafLocalParent> = emptyList(), val forcesCompleted: Boolean,
)
@Serializable internal data class SafLocalEvidenceEntry(
    val path: String, val version: SafLocalVersion, val sha256: String? = null, val linkTarget: String? = null,
)
@Serializable internal data class SafLocalEvidence(
    val version: Int = 1, val operationId: String, val envelopeDigest: String, val workspaceKey: String,
    val endpoint: SafLocalPath, val rootVersion: SafLocalVersion, val entries: List<SafLocalEvidenceEntry>,
)

@Serializable internal data class SafActivationInventory(
    val version: Int = 1, val operationId: String, val envelopeDigest: String,
    val artifact: SafLocalArtifact, val entries: List<SafLocalEvidenceEntry>,
)

/** The two fixed activation forests. Protected roots and symlink targets are never traversed. */
internal object SafActivationFiles {
    fun encode(image: SafActivationInventory, maximum: Long, checkActive: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        fun append(bytes: ByteArray) { checkActive(); check(bytes.size.toLong() <= maximum - out.size()); out.write(bytes) }
        fun literal(value: String) = append(value.toByteArray(Charsets.UTF_8))
        fun string(value: String) = append(SafJournalCodec.encode(String.serializer(), value))
        literal("{\"version\":${image.version},\"operationId\":"); string(image.operationId)
        literal(",\"envelopeDigest\":"); string(image.envelopeDigest)
        literal(",\"artifact\":"); append(SafJournalCodec.encode(SafLocalArtifact.serializer(), image.artifact))
        literal(",\"entries\":[")
        image.entries.forEachIndexed { index, entry -> if (index != 0) literal(","); append(SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), entry)) }
        literal("]}")
        return out.toByteArray()
    }

    fun capture(journal: SafOperationJournal, operation: SafOperationJournal.Operation, artifact: SafLocalArtifact,
        checkActive: () -> Unit): SafActivationInventory {
        val root = File(artifact.path)
        journal.storage.requireRealAncestors(root)
        check(journal.storage.key(root, true) == artifact.directoryKey)
        val entries = arrayListOf<SafLocalEvidenceEntry>()
        var bytes = 0L
        var files = 0
        var encoded = 1024L + artifact.path.toByteArray().size + artifact.directoryKey.toByteArray().size
        fun children(directory: File): List<String> {
            val names = arrayListOf<String>()
            Files.newDirectoryStream(directory.toPath()).use { stream -> stream.forEach {
                checkActive()
                check(names.size + entries.size < journal.limits.treeEntries)
                names += PathSecurity.safeLeafName(it.fileName.toString())
            } }
            return names.sortedWith(Comparator { a, b -> checkActive(); a.compareTo(b) })
        }
        fun visit(file: File, path: String) {
            checkActive()
            SafOperationJournal.validPath(path, false)
            require(path.count { it == '/' } + 1 <= journal.limits.treeDepth)
            check(entries.size < journal.limits.treeEntries)
            val before = requireNotNull(SafLocalFiles.version(journal.storage, file))
            val digest = if (before.kind == SafLocalKind.FILE) {
                check(++files <= journal.limits.treeFiles)
                journal.storage.describe(file, journal.limits.treeBytes - bytes, checkActive).also {
                    check(it.key == before.key && it.bytes == before.bytes)
                    bytes = SafOperationJournal.boundedAdd(bytes, it.bytes, journal.limits.treeBytes)
                }.sha256
            } else null
            val link = if (before.kind == SafLocalKind.SYMLINK) Files.readSymbolicLink(file.toPath()).toString() else null
            val entry = SafLocalEvidenceEntry(path, before, digest, link)
            encoded = SafOperationJournal.boundedAdd(encoded, SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), entry).size.toLong() + 1,
                journal.limits.treeIndexBytes)
            entries += entry
            if (before.kind == SafLocalKind.DIRECTORY) children(file).forEach { visit(File(file, it), "$path/$it") }
            check(SafLocalFiles.version(journal.storage, file) == before) { "Activation inventory changed during capture" }
        }
        fun roots(): List<String> = children(root).let { names ->
            if (artifact.kind == SafLocalArtifactKind.LOCAL_ROOT) names.filterNot { it == ".git" || it == ".droide" }
            else names.also { check(it.none { name -> name == ".git" || name == ".droide" }) {
                "Unexpected protected-name bytes in SAF activation artifact; retained"
            } }
        }
        val names = roots()
        names.forEach { visit(File(root, it), it) }
        check(roots() == names)
        check(journal.storage.key(root, true) == artifact.directoryKey)
        return SafActivationInventory(operationId = operation.id, envelopeDigest = operation.envelopeIdentity.sha256,
            artifact = artifact, entries = Collections.unmodifiableList(entries.sortedBy { checkActive(); it.path })).also {
            validate(journal, operation, it, artifact, checkActive)
        }
    }

    fun validate(journal: SafOperationJournal, operation: SafOperationJournal.Operation, image: SafActivationInventory,
        artifact: SafLocalArtifact, checkActive: () -> Unit): Long {
        require(image.version == 1 && image.operationId == operation.id && image.envelopeDigest == operation.envelopeIdentity.sha256 && image.artifact == artifact)
        require(image.entries.size <= journal.limits.treeEntries)
        val seen = hashMapOf<String, SafLocalKind>()
        val keys = hashSetOf<String>()
        var previous: String? = null
        var bytes = 0L
        var files = 0
        image.entries.forEach { entry ->
            checkActive()
            SafOperationJournal.validPath(entry.path, false)
            require(entry.path.count { it == '/' } + 1 <= journal.limits.treeDepth)
            require(previous == null || requireNotNull(previous) < entry.path)
            previous = entry.path
            SafLocalEvidenceFormat.validateVersion(entry.version)
            require(keys.add(entry.version.key)) { "Aliased activation inventory" }
            val parent = entry.path.substringBeforeLast('/', "")
            require(parent.isEmpty() || seen[parent] == SafLocalKind.DIRECTORY)
            seen[entry.path] = entry.version.kind
            when (entry.version.kind) {
                SafLocalKind.FILE -> {
                    require(entry.sha256?.matches(Regex("[0-9a-f]{64}")) == true && entry.linkTarget == null)
                    check(++files <= journal.limits.treeFiles)
                    bytes = SafOperationJournal.boundedAdd(bytes, entry.version.bytes, journal.limits.treeBytes)
                }
                SafLocalKind.DIRECTORY -> require(entry.sha256 == null && entry.linkTarget == null)
                SafLocalKind.SYMLINK -> require(entry.sha256 == null && entry.linkTarget != null &&
                    entry.linkTarget.toByteArray().size <= 32 * 1024 && '\u0000' !in entry.linkTarget)
            }
        }
        return bytes
    }

    fun forceAndVerify(journal: SafOperationJournal, operation: SafOperationJournal.Operation,
        image: SafActivationInventory, at: SafLocalArtifact = image.artifact, checkActive: () -> Unit) {
        val root = File(at.path)
        check(capture(journal, operation, at, checkActive).entries == image.entries)
        image.entries.filter { it.version.kind == SafLocalKind.FILE }.forEach { entry ->
            checkActive()
            val file = File(root, entry.path)
            journal.storage.requireRealAncestors(file)
            check(SafLocalFiles.version(journal.storage, file) == entry.version)
            journal.storage.syncFile(file)
        }
        image.entries.asReversed().filter { it.version.kind == SafLocalKind.DIRECTORY }.forEach { entry ->
            checkActive(); journal.storage.syncDirectory(File(root, entry.path))
        }
        checkActive(); journal.storage.syncDirectory(root)
        check(capture(journal, operation, at, checkActive).entries == image.entries) { "Activation forest changed during durability verification" }
    }
}

/** Local-only, no-follow observations. No method deletes, renames, follows links or changes permissions. */
internal object SafLocalFiles {
    fun version(storage: SafOperationStorage, file: File): SafLocalVersion? {
        val attrs = storage.attributes(file) ?: return null
        val kind = when { attrs.isRegularFile -> SafLocalKind.FILE; attrs.isDirectory -> SafLocalKind.DIRECTORY
            attrs.isSymbolicLink -> SafLocalKind.SYMLINK; else -> error("Unsupported local SAF node") }
        return SafLocalVersion(kind, requireNotNull(attrs.fileKey()).toString(), attrs.size(), attrs.lastModifiedTime().toString())
    }

    fun resolve(journal: SafOperationJournal, endpoint: SafLocalPath, custody: SafDeleteCustody? = null): File {
        require(endpoint.role == SafLocalRole.WORKSPACE) { "Activation artifacts require a later owned binding" }
        if (custody != null && endpoint.path == "${custody.directory}/original") {
            require(custody.directory == ".droide/saf-delete-custody/${custody.directory.substringAfterLast('/')}" &&
                java.util.UUID.fromString(custody.directory.substringAfterLast('/')).toString() == custody.directory.substringAfterLast('/'))
        } else SafOperationJournal.validPath(endpoint.path, false)
        require(endpoint.path.count { it == '/' } + 1 <= journal.limits.treeDepth)
        return File(journal.workspace, endpoint.path)
    }

    fun parents(journal: SafOperationJournal, paths: List<SafLocalPath>, checkActive: () -> Unit): List<SafLocalParent> {
        return parents(journal, paths, null, checkActive)
    }

    fun parents(journal: SafOperationJournal, paths: List<SafLocalPath>, custody: SafDeleteCustody?, checkActive: () -> Unit): List<SafLocalParent> {
        val found = linkedMapOf<String, SafLocalParent>()
        for (endpoint in paths) {
            resolve(journal, endpoint, custody)
            var current = journal.workspace
            var relative = ""
            val names = endpoint.path.split('/').dropLast(1)
            for (name in listOf("") + names) {
                checkActive()
                if (name.isNotEmpty()) { relative = if (relative.isEmpty()) name else "$relative/$name"; current = File(current, name) }
                val observed = version(journal.storage, current) ?: break
                require(observed.kind == SafLocalKind.DIRECTORY) { "Local SAF ancestor is not a real directory" }
                found[relative] = SafLocalParent(relative, observed.key)
            }
        }
        require(found.size <= journal.limits.treeDepth * 2)
        return Collections.unmodifiableList(found.values.toList())
    }

    fun requireParents(journal: SafOperationJournal, parents: List<SafLocalParent>, checkActive: () -> Unit = {}) {
        parents.forEach {
            checkActive()
            val observed = version(journal.storage, File(journal.workspace, it.path))
            check(observed?.kind == SafLocalKind.DIRECTORY && observed.key == it.key) { "Local SAF parent changed" }
        }
    }

    fun requireVersions(journal: SafOperationJournal, plan: SafLocalPlan, checkActive: () -> Unit = {}) {
        requireParents(journal, plan.parents, checkActive)
        plan.source?.let { check(version(journal.storage, resolve(journal, it)) == plan.sourceVersion) { "Local SAF source changed" } }
        check(version(journal.storage, resolve(journal, plan.destination, plan.deleteCustody)) == plan.expectedDestination) { "Local SAF destination changed" }
        plan.missingDirectories.forEach { checkActive(); check(version(journal.storage, File(journal.workspace, it)) == null) { "Local directory appeared" } }
    }

    fun capture(journal: SafOperationJournal, operation: SafOperationJournal.Operation, endpoint: SafLocalPath,
        checkActive: () -> Unit): SafLocalEvidence = capture(journal, operation, endpoint, null, checkActive)

    private fun capture(journal: SafOperationJournal, operation: SafOperationJournal.Operation, endpoint: SafLocalPath,
        custody: SafDeleteCustody?, checkActive: () -> Unit): SafLocalEvidence {
        val file = resolve(journal, endpoint, custody)
        val ancestors = parents(journal, listOf(endpoint), custody, checkActive)
        val root = requireNotNull(version(journal.storage, file))
        val entries = arrayListOf<SafLocalEvidenceEntry>()
        var bytes = 0L
        var files = 0
        var queuedBytes = 0L
        var listed = 0
        var encoded = 1024L + endpoint.path.toByteArray(Charsets.UTF_8).size + root.key.toByteArray(Charsets.UTF_8).size
        fun visit(node: File, relative: String) {
            checkActive()
            val full = endpoint.path + if (relative.isEmpty()) "" else "/$relative"
            if (custody != null) {
                require(custody.directory == ".droide/saf-delete-custody/${operation.id}" && endpoint.path == "${custody.directory}/original")
                if (relative.isNotEmpty()) relative.split('/').forEach { PathSecurity.safeLeafName(it) }
            } else SafOperationJournal.validPath(full, false)
            val logical = if (custody == null) full else operation.envelope.request.paths.single() + if (relative.isEmpty()) "" else "/$relative"
            require(logical.count { it == '/' } + 1 <= journal.limits.treeDepth)
            check(entries.size < journal.limits.treeEntries)
            val before = requireNotNull(version(journal.storage, node))
            val digest = if (before.kind == SafLocalKind.FILE) {
                check(++files <= journal.limits.treeFiles)
                val value = journal.storage.describe(node, journal.limits.treeBytes - bytes, checkActive)
                check(value.key == before.key && value.bytes == before.bytes)
                bytes = SafOperationJournal.boundedAdd(bytes, value.bytes, journal.limits.treeBytes)
                value.sha256
            } else null
            val link = if (before.kind == SafLocalKind.SYMLINK) Files.readSymbolicLink(node.toPath()).toString().also {
                require(it.length <= 32 * 1024 && it.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
            } else null
            check(version(journal.storage, node) == before) { "Local SAF node changed during capture" }
            val entry = SafLocalEvidenceEntry(relative, before, digest, link)
            encoded = SafOperationJournal.boundedAdd(encoded, SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), entry).size.toLong() + 1,
                journal.limits.treeIndexBytes)
            entries += entry
            if (before.kind == SafLocalKind.DIRECTORY) {
                val names = arrayListOf<String>()
                Files.newDirectoryStream(node.toPath()).use { children -> children.forEach { child ->
                    checkActive()
                    check(++listed < journal.limits.treeEntries)
                    val name = child.fileName.toString()
                    PathSecurity.safeLeafName(name)
                    queuedBytes = SafOperationJournal.boundedAdd(queuedBytes, name.toByteArray(Charsets.UTF_8).size.toLong(), journal.limits.treeIndexBytes)
                    names += name
                } }
                names.sortedWith(Comparator { a, b -> checkActive(); a.compareTo(b) }).forEach { name ->
                    visit(File(node, name), if (relative.isEmpty()) name else "$relative/$name")
                }
                check(version(journal.storage, node) == before) { "Local SAF directory changed during capture" }
            }
        }
        visit(file, "")
        requireParents(journal, ancestors, checkActive)
        return SafLocalEvidence(operationId = operation.id, envelopeDigest = operation.envelopeIdentity.sha256,
            workspaceKey = journal.anchor.workspaceKey, endpoint = endpoint, rootVersion = root,
            entries = Collections.unmodifiableList(entries.sortedWith(Comparator { a, b -> checkActive(); a.path.compareTo(b.path) })))
    }

    fun verify(journal: SafOperationJournal, operation: SafOperationJournal.Operation, image: SafLocalEvidence,
        at: SafLocalPath, checkActive: () -> Unit) {
        verify(journal, operation, image, at, null, checkActive)
    }

    fun verify(journal: SafOperationJournal, operation: SafOperationJournal.Operation, image: SafLocalEvidence,
        at: SafLocalPath, custody: SafDeleteCustody?, checkActive: () -> Unit) {
        val actual = capture(journal, operation, at, custody, checkActive)
        check(actual.rootVersion == image.rootVersion && actual.entries == image.entries) { "Local SAF inventory changed" }
    }

    /** Forces each admitted file and directory, never link targets; then callers recheck complete contents. */
    fun forceInventory(journal: SafOperationJournal, image: SafLocalEvidence, at: SafLocalPath, checkActive: () -> Unit) {
        forceInventory(journal, image, at, null, checkActive)
    }

    fun forceInventory(journal: SafOperationJournal, image: SafLocalEvidence, at: SafLocalPath, custody: SafDeleteCustody?, checkActive: () -> Unit) {
        val root = resolve(journal, at, custody)
        image.entries.filter { it.version.kind == SafLocalKind.FILE }.forEach { entry ->
            checkActive()
            val file = if (entry.path.isEmpty()) root else File(root, entry.path)
            check(version(journal.storage, file) == entry.version)
            journal.storage.requireRealAncestors(file)
            journal.storage.syncFile(file)
            check(version(journal.storage, file) == entry.version)
        }
        image.entries.asReversed().filter { it.version.kind == SafLocalKind.DIRECTORY }.forEach { entry ->
            checkActive()
            val file = if (entry.path.isEmpty()) root else File(root, entry.path)
            check(version(journal.storage, file) == entry.version)
            journal.storage.requireRealAncestors(file)
            journal.storage.syncDirectory(file)
        }
    }
}

internal object SafLocalEvidenceFormat {
    fun validate(image: SafLocalEvidence, journal: SafOperationJournal, operation: SafOperationJournal.Operation,
        endpoint: SafLocalPath, rootVersion: SafLocalVersion, checkActive: () -> Unit): Long {
        require(image.version == 1 && image.operationId == operation.id && image.envelopeDigest == operation.envelopeIdentity.sha256 &&
            image.workspaceKey == journal.anchor.workspaceKey && image.endpoint == endpoint && image.rootVersion == rootVersion)
        SafLocalFiles.resolve(journal, endpoint)
        require(image.entries.size in 1..journal.limits.treeEntries)
        val paths = hashMapOf<String, SafLocalKind>()
        val keys = hashSetOf<String>()
        var previous: String? = null
        var files = 0
        var bytes = 0L
        image.entries.forEachIndexed { index, entry ->
            checkActive()
            val full = endpoint.path + if (entry.path.isEmpty()) "" else "/${entry.path}"
            SafOperationJournal.validPath(full, false)
            require(full.count { it == '/' } + 1 <= journal.limits.treeDepth)
            require(previous == null || requireNotNull(previous) < entry.path)
            previous = entry.path
            validateVersion(entry.version)
            require(keys.add(entry.version.key)) { "Aliased local SAF inventory" }
            if (index == 0) require(entry.path.isEmpty() && entry.version == rootVersion)
            else require(entry.path.isNotEmpty() && paths[entry.path.substringBeforeLast('/', "")] == SafLocalKind.DIRECTORY)
            paths[entry.path] = entry.version.kind
            when (entry.version.kind) {
                SafLocalKind.FILE -> { require(entry.sha256?.matches(Regex("[0-9a-f]{64}")) == true && entry.linkTarget == null)
                    check(++files <= journal.limits.treeFiles)
                    bytes = SafOperationJournal.boundedAdd(bytes, entry.version.bytes, journal.limits.treeBytes) }
                SafLocalKind.DIRECTORY -> require(entry.sha256 == null && entry.linkTarget == null)
                SafLocalKind.SYMLINK -> { require(entry.sha256 == null && entry.linkTarget != null)
                    require(entry.linkTarget.length <= 32 * 1024 && entry.linkTarget.toByteArray(Charsets.UTF_8).size <= 32 * 1024 && '\u0000' !in entry.linkTarget) }
            }
        }
        return bytes
    }

    fun validateVersion(version: SafLocalVersion) {
        require(version.key.isNotEmpty() && version.key.length <= 4096 && version.key.toByteArray(Charsets.UTF_8).size <= 4096)
        require(version.bytes >= 0 && version.modified.isNotEmpty() && version.modified.length <= 128 && '\u0000' !in version.modified)
    }

    fun encode(image: SafLocalEvidence, maximum: Long, checkActive: () -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        fun append(bytes: ByteArray) { checkActive(); check(bytes.size.toLong() <= maximum - out.size()); out.write(bytes) }
        fun literal(value: String) = append(value.toByteArray(Charsets.UTF_8))
        fun string(value: String) = append(SafJournalCodec.encode(String.serializer(), value))
        literal("{\"version\":${image.version},\"operationId\":"); string(image.operationId)
        literal(",\"envelopeDigest\":"); string(image.envelopeDigest)
        literal(",\"workspaceKey\":"); string(image.workspaceKey)
        literal(",\"endpoint\":"); append(SafJournalCodec.encode(SafLocalPath.serializer(), image.endpoint))
        literal(",\"rootVersion\":"); append(SafJournalCodec.encode(SafLocalVersion.serializer(), image.rootVersion))
        literal(",\"entries\":[")
        image.entries.forEachIndexed { index, entry -> if (index != 0) literal(","); append(SafJournalCodec.encode(SafLocalEvidenceEntry.serializer(), entry)) }
        literal("]}")
        return out.toByteArray()
    }
}
