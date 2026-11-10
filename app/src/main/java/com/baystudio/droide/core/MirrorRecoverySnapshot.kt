package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.ensureActive

/** Observed recovery versions, not a lease against uncooperative external writers. */
internal object MirrorRecoverySnapshot {
    data class Observation(val node: MirrorMetadataAuthority.Node, val identity: String,
                                    val parentIdentity: String, val bytes: Long = 0,
                                    val files: Map<String, WorkspaceSyncManifest.Entry> = emptyMap(),
                                    val directories: Set<String> = emptySet())
    fun observe(root: File, path: String, context: kotlin.coroutines.CoroutineContext): Observation = AnchoredWorkspaceFiles(root).use { files ->
        val parentIdentity = files.directoryKey()
        val result = when (files.kind(path)) {
            AnchoredWorkspaceFiles.Kind.ABSENT -> Observation(MirrorMetadataAuthority.Node.absent, "", parentIdentity)
            AnchoredWorkspaceFiles.Kind.FILE -> {
                val parent = path.substringBeforeLast('/', "")
                files.openDirectory(parent).use { directory ->
                    val version = CanonicalFileSnapshot(Long.MAX_VALUE).inspect(directory, path.substringAfterLast('/'), context)
                    Observation(MirrorMetadataAuthority.Node.file(WorkspaceSyncManifest.Entry(version.sha256,
                        if (version.mode and 64 != 0) 493 else 420)), version.identity, parentIdentity + ":" + directory.directoryKey(), version.size)
                }
            }
            AnchoredWorkspaceFiles.Kind.DIRECTORY -> {
                files.openDirectory(path).use { child ->
                    val key = child.directoryKey()
                    val tree = AnchoredWorkspaceTree.snapshot(File(root, path), WorkspaceFileFingerprintCache(0), context, excluded = { false })
                    check(tree.opaque.isEmpty()) { "Folder contains links or unsupported entries; retained data was kept" }
                    // Stable directory identity plus content and leaf identities rejects replacement after review.
                    val rows = tree.directories.map { "D\t$it" } + tree.files.map { (name, value) ->
                        "F\t$name\t${value.sha256}\t${if (value.executable) 493 else 420}"
                    }
                    val hash = java.security.MessageDigest.getInstance("SHA-256")
                    rows.sorted().forEach { hash.update(it.toByteArray()); hash.update('\n'.code.toByte()) }
                    val identities = tree.files.toSortedMap().map { (name, value) -> "$name:${value.attributes.fileKey()}" } +
                        tree.directories.sorted().map { "$it:${child.directoryKey(it)}" }
                    check(child.directoryKey() == key && files.directoryKey(path) == key) { "Folder changed during review" }
                    Observation(MirrorMetadataAuthority.Node.directory(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }),
                        key + identities.joinToString("\n"), parentIdentity,
                        files = tree.files.mapValues { (_, value) -> WorkspaceSyncManifest.Entry(value.sha256, if (value.executable) 493 else 420) },
                        directories = tree.directories.toSet())
                }
            }
            else -> error("Recovery entry is a link or unsupported type; original data was kept")
        }
        check(files.directoryKey() == parentIdentity) { "Recovery parent changed during review" }
        context.ensureActive()
        result
    }
}
