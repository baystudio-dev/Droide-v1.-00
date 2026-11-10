package com.baystudio.droide.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

internal object UbuntuAptCacheDefaults {
    const val CONTENT = "// Droide: keep signed indexes; discard package archives after successful installation.\n" +
        "// Explicit command-line APT options can override this default.\n" +
        "APT::Keep-Downloaded-Packages \"false\";\n" +
        "Binary::apt-get::APT::Keep-Downloaded-Packages \"false\";\n"
    const val INDEX_FILE = "00droide-index-defaults"
    const val INDEX_CONTENT = "// Droide: package installs do not need description translations.\n" +
        "// Signed repository and package indexes remain enabled. Override with -o Acquire::Languages=en.\n" +
        "Acquire::Languages \"none\";\n"

    fun install(rootfs: File): Boolean {
        val directory = File(rootfs, "etc/apt/apt.conf.d")
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) ||
            !directory.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) return false
        val indexDefaults = installIndexDefaults(rootfs, directory)
        val target = File(directory, "99droide-cache-defaults")
        return writeAbsent(target, CONTENT) && indexDefaults
    }

    private fun installIndexDefaults(rootfs: File, directory: File): Boolean {
        val target = File(directory, INDEX_FILE)
        if (Files.isSymbolicLink(target.toPath()) || (target.exists() && !target.isFile)) return false
        try {
            // Never replace a customized Droide file. Check other files too, including nested APT syntax;
            // uncertainty leaves the user's existing configuration in control.
            val owned = target.isFile && target.length() == INDEX_CONTENT.toByteArray().size.toLong() &&
                target.readText() == INDEX_CONTENT
            if (target.exists() && !owned) return true
            val configs = directory.listFiles()?.filter { it.name != INDEX_FILE && !it.name.startsWith('.') }
                ?: return false
            if (configs.size > 256) return false
            val customized = (configs + File(rootfs, "etc/apt/apt.conf")).any { config ->
                when {
                    Files.isSymbolicLink(config.toPath()) -> true
                    !config.exists() -> false
                    !config.isFile || config.length() > 1_048_576 -> true
                    else -> Regex("(?i)\\bLanguages\\b|(?m)^\\s*#(?:include|clear)\\b").containsMatchIn(config.readText())
                }
            }
            if (customized) {
                // Remove only our exact optional default if a user later supplies languages.
                return !owned || target.delete()
            }
            return writeAbsent(target, INDEX_CONTENT)
        } catch (_: IOException) { return false }
    }

    private fun writeAbsent(target: File, content: String): Boolean {
        if (Files.isSymbolicLink(target.toPath())) return false
        // Keep a user-customized copy. The default is written only when absent.
        if (target.exists()) return target.isFile
        val pending = File(target.parentFile, ".${target.name}.next")
        if (Files.isSymbolicLink(pending.toPath()) || (pending.exists() && !pending.isFile)) return false
        try {
            FileOutputStream(pending).use { out -> out.write(content.toByteArray()); out.fd.sync() }
            return pending.renameTo(target)
        } catch (_: IOException) {
            // A full filesystem must not prevent opening a working terminal to inspect storage. Private
            // installer cleanup remains active.
            return false
        } finally { if (pending.exists()) pending.delete() }
    }
}
