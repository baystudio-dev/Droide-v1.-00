package com.baystudio.droide.core

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

/** Optional client defaults; existing files and the user's SSH configuration remain authoritative. */
internal object UbuntuSshClientDefaults {
    const val CONFIG = "etc/ssh/ssh_config.d/90-droide-client.conf"
    const val CONTENT = "# Droide client defaults; override per host in ~/.ssh/config or with -o.\n" +
        "# Only connection setup is timed; an established session has no time limit.\n" +
        "Match final\n    ConnectTimeout 15\n    IPQoS none\nMatch all\n"

    fun install(rootfs: File): Boolean = try {
        AnchoredWorkspaceFiles(rootfs).use { root ->
            for (directory in listOf("etc", "etc/ssh", "etc/ssh/ssh_config.d")) {
                if (root.kind(directory) == AnchoredWorkspaceFiles.Kind.ABSENT) root.createDirectory(directory)
                if (root.kind(directory) != AnchoredWorkspaceFiles.Kind.DIRECTORY) return false
            }
            when (root.kind(CONFIG)) {
                AnchoredWorkspaceFiles.Kind.FILE -> return true
                AnchoredWorkspaceFiles.Kind.ABSENT -> Unit
                else -> return false
            }
            val pending = ".droide-ssh-${UUID.randomUUID()}.tmp"
            var identity: Any? = null
            try {
                root.createNew(pending).use { file ->
                    identity = file.observe().attributes.fileKey()
                    file.write(CONTENT.toByteArray(Charsets.UTF_8))
                    file.openWriteChannel().use { it.force(true) }
                }
                val result = root.moveNoReplaceDurable(pending, root, CONFIG)
                result.durable || result.error == 17 && root.kind(CONFIG) == AnchoredWorkspaceFiles.Kind.FILE
            } catch (_: FileAlreadyExistsException) {
                root.kind(CONFIG) == AnchoredWorkspaceFiles.Kind.FILE
            } finally {
                identity?.let { key ->
                    if (root.kind(pending) == AnchoredWorkspaceFiles.Kind.FILE) {
                        runCatching { root.deleteRedundantMetadata(pending, key) }
                    }
                }
            }
        }
    } catch (_: IOException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
