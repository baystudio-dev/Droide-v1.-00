package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files

internal object WorkspaceProcessPathGuard {
    // A positive cwd observation is a useful refusal. An empty scan is NOT a writer-exit
    // receipt: descriptors, changed cwd, and processes outside this view are not inventoried.
    data class Occupant(val pid: Int, val deleted: Boolean)

    fun requireUnused(directory: File, proc: File = File("/proc")) {
        if (!directory.isDirectory) return
        val occupants = occupants(directory, proc)
        check(occupants.isEmpty()) {
            "Stop the command or terminal using ${directory.absolutePath} before moving or deleting it " +
                "(PID ${occupants.joinToString { it.pid.toString() }}). The folder was preserved."
        }
    }

    fun requireSafe(root: File, mutation: FileRepository.Mutation) {
        val relative = when (mutation) {
            is FileRepository.Mutation.Move -> mutation.from
            is FileRepository.Mutation.Delete -> mutation.path
            else -> return
        }
        requireUnused(PathSecurity.resolveWithin(root, relative))
    }

    internal fun occupants(directory: File, proc: File = File("/proc")): List<Occupant> {
        val target = directory.canonicalFile.toPath()
        val result = mutableListOf<Occupant>()
        Files.newDirectoryStream(proc.toPath()).use { entries ->
            var inspected = 0
            for (entry in entries) {
                val pid = entry.fileName.toString().toIntOrNull()?.takeIf { it > 0 } ?: continue
                check(++inspected <= 32_768) { "Too many processes to verify folder ownership; folder was preserved" }
                val cwd = try { Files.readSymbolicLink(entry.resolve("cwd")).toString() }
                catch (_: java.io.IOException) { continue }
                catch (_: SecurityException) { continue }
                if (!cwd.startsWith('/')) continue
                val deleted = cwd.endsWith(" (deleted)")
                val actual = File(cwd.removeSuffix(" (deleted)")).canonicalFile.toPath()
                if (actual.startsWith(target)) {
                    result += Occupant(pid, deleted)
                    if (result.size == 8) break
                }
            }
        }
        return result
    }
}
