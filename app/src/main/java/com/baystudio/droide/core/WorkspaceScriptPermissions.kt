package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Read-only diagnosis: extracted filenames never implicitly authorize chmod or execution. */
internal object WorkspaceScriptPermissions {
    data class Diagnostic(val relativePath: String, val executable: Boolean, val portableHeader: Boolean) {
        fun render(): String = buildString {
            val quoted = DeviceBridgeManager.shellQuote("./$relativePath")
            appendLine("path=$relativePath executable=$executable portable_executable_header=$portableHeader")
            if (executable) {
                append("The execute bit is present. Permission errors may instead involve the interpreter, mount restrictions or Android policy.")
            } else {
                appendLine("This regular workspace file is missing its execute bit. ZIP archives and shared storage may lose Unix permission metadata.")
                appendLine("After reviewing and trusting this file, explicitly run: chmod u+x -- $quoted")
                append("No permission was changed and no script was executed. Run in the workstation that has the script's interpreter and dependencies.")
            }
        }.trimEnd()
    }

    fun inspect(projectRoot: File, relative: String): Diagnostic {
        require(relative.isNotBlank() && relative.length <= 2_048 && relative.none { it.code < 32 || it.code == 127 }) {
            "Invalid workspace script path"
        }
        require(!File(relative).isAbsolute && relative.split('/', '\\').none { it == ".." }) { "Script path must remain inside the workspace" }
        val root = projectRoot.canonicalFile.toPath()
        val normalized = root.resolve(relative).normalize()
        require(normalized != root && normalized.startsWith(root)) { "Script path must remain inside the workspace" }
        var current = root
        for (component in root.relativize(normalized)) {
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Script permission diagnostics do not follow symbolic links" }
        }
        val attributes = Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "Script permission diagnostics require a regular file" }
        val header = ByteArray(4)
        val read = Files.newInputStream(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.read(header) }
        val portable = read >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte() ||
            read >= 4 && header.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        return Diagnostic(root.relativize(normalized).toString().replace(File.separatorChar, '/'), Files.isExecutable(normalized), portable)
    }
}
