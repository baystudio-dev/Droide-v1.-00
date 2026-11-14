package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** The server's one publication slot. All session selection/revocation uses [monitor] too. */
internal class CliEndpointPublication<Owner : Any>(
    private val isCurrent: (Owner) -> Boolean,
    private val replace: (Owner) -> Unit,
    private val remove: (Owner) -> Unit,
) {
    val monitor = Any()
    private var publishedEndpointOwner: Owner? = null

    fun publish(owner: Owner): Boolean = synchronized(monitor) {
        if (!isCurrent(owner)) return@synchronized false
        check(publishedEndpointOwner == null) { "Previous CLI endpoint has not been withdrawn" }
        // A failed atomic replacement grants no ownership and leaves the prior file intact.
        replace(owner)
        publishedEndpointOwner = owner
        true
    }

    fun withdraw(owner: Owner) = synchronized(monitor) {
        if (publishedEndpointOwner === owner) {
            remove(owner)
            // Retain ownership if removal throws, so no second workspace can publish.
            publishedEndpointOwner = null
        }
    }

    /** Called only by accepted activation after revoking the previous active session. */
    fun withdrawPrevious() = synchronized(monitor) {
        publishedEndpointOwner?.let(::withdraw)
    }
}

/** File-only seam: no installer, socket, process or Android dependency. */
internal class CliEndpointFile(filesDir: File, private val workspaceId: String) {
    private val root = filesDir.canonicalFile
    private val cliDir = File(root, "cli")
    private val discoveryDir = File(cliDir, workspaceId)
    val endpointFile = File(discoveryDir, "endpoint-v1")
    val stagingFile = File(discoveryDir, ".endpoint-v1-${UUID.randomUUID()}")

    init {
        require(workspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}")) &&
            workspaceId != "." && workspaceId != "..") { "Invalid CLI workspace identity" }
    }

    fun prepare(socketName: String) {
        for (directory in listOf(cliDir, discoveryDir)) {
            requireDirectoryPath(directory)
            check(directory.mkdir() || directory.isDirectory) { "Could not create CLI discovery directory" }
            requireDirectoryPath(directory)
        }
        val payload = "DROIDE_CLI_ENDPOINT_V1\n$socketName\n$workspaceId\n".toByteArray(Charsets.UTF_8)
        // CREATE_NEW + NOFOLLOW refuses an existing staging leaf. 0600 applies at creation,
        // rather than after a world-readable payload could already have been written.
        Files.newByteChannel(
            stagingFile.toPath(),
            setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        ).use { channel ->
            val bytes = ByteBuffer.wrap(payload)
            while (bytes.hasRemaining()) channel.write(bytes)
        }
    }

    fun publishPrepared() {
        requireDirectories()
        check(Files.isRegularFile(stagingFile.toPath(), NOFOLLOW_LINKS)) { "CLI staging file is not a regular file" }
        // Do not unlink endpoint-v1 first. Unsupported/failed atomic replacement is a failure.
        Files.move(stagingFile.toPath(), endpointFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun withdraw() {
        requireDirectories()
        // Delete the named leaf only, never follow a substituted link or recursively delete it.
        Files.deleteIfExists(endpointFile.toPath())
    }

    fun discardStaging() {
        requireDirectories()
        Files.deleteIfExists(stagingFile.toPath())
    }

    private fun requireDirectories() {
        requireDirectoryPath(cliDir)
        requireDirectoryPath(discoveryDir)
    }

    private fun requireDirectoryPath(directory: File) {
        check(!Files.isSymbolicLink(directory.toPath())) { "CLI discovery directory may not be a symlink" }
        require(directory.canonicalFile.toPath().startsWith(root.toPath())) { "CLI discovery path escaped app storage" }
    }
}
