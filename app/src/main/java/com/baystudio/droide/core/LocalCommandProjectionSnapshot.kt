package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Compare only the small, flat generated shim directory, never installed dependency trees. */
internal object LocalCommandProjectionSnapshot {
    fun matches(directory: File, expected: Map<String, String>): Boolean = try {
        val noFollow = LinkOption.NOFOLLOW_LINKS
        val root = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, noFollow)
        if (!root.isDirectory || root.isSymbolicLink) false
        else {
            val entries = directory.listFiles()
            entries != null && entries.size == expected.size && entries.all { file ->
                expected[file.name]?.let { matchesFile(file, it) } == true
            }
        }
    } catch (_: java.io.IOException) { false }
      catch (_: SecurityException) { false }

    fun matchesFile(file: File, text: String): Boolean = try {
        val noFollow = LinkOption.NOFOLLOW_LINKS
        val bytes = text.toByteArray(Charsets.UTF_8)
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
        if (bytes.size > 65_536 || !before.isRegularFile || before.isSymbolicLink ||
            before.size() != bytes.size.toLong() || !file.canExecute()) false
        else {

            val actual = file.inputStream().use { input ->
                val buffer = ByteArray(bytes.size + 1)
                var count = 0
                while (count < buffer.size) {
                    val n = input.read(buffer, count, buffer.size - count)
                    if (n < 0) break
                    count += n
                }
                buffer.copyOf(count)
            }
            val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, noFollow)
            after.isRegularFile && !after.isSymbolicLink && before.fileKey() == after.fileKey() &&
                before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                bytes.contentEquals(actual)
        }
    } catch (_: java.io.IOException) { false }
      catch (_: SecurityException) { false }
}
