package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files

/** Downloaded artifacts remain disposable after their consumer releases its lease. */
internal object TrustedArtifactCache {
    private val leases = mutableMapOf<String, Int>()
    private val pendingDiscards = mutableMapOf<String, File>()

    private fun key(file: File): String = File(checkNotNull(file.parentFile).canonicalFile, file.name).path

    suspend fun <T> withLease(file: File, block: suspend () -> T): T {
        val key = key(file)
        synchronized(leases) { leases[key] = Math.addExact(leases[key] ?: 0, 1) }
        return try {
            block()
        } finally {
            synchronized(leases) {
                val count = checkNotNull(leases[key])
                if (count == 1) {
                    leases.remove(key)
                    pendingDiscards.remove(key)?.delete()
                } else leases[key] = count - 1
            }
        }
    }

    fun discard(file: File) = synchronized(leases) {
        val key = key(file)
        if (key in leases) {
            pendingDiscards[key] = file
            false
        } else file.delete()
    }

    fun prune(root: File, budgetBytes: Long, protectedNames: Set<String>) = synchronized(leases) {
        require(budgetBytes >= 0L) { "Invalid trusted artifact cache budget" }
        val canonicalRoot = root.canonicalFile
        val candidates = root.listFiles().orEmpty().filter { file ->
            file.isFile && file.parentFile?.canonicalFile == canonicalRoot &&
                !Files.isSymbolicLink(file.toPath()) &&
                !file.name.endsWith(".part") && !file.name.endsWith(".part.properties") &&
                !file.name.endsWith(".part.properties.tmp")
        }
        var total = candidates.fold(0L) { bytes, file -> Math.addExact(bytes, file.length().coerceAtLeast(0L)) }
        candidates.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).forEach { file ->
            if (total <= budgetBytes || file.name in protectedNames || key(file) in leases) return@forEach
            val size = file.length().coerceAtLeast(0L)
            if (file.delete()) total = (total - size).coerceAtLeast(0L)
        }
    }
}
