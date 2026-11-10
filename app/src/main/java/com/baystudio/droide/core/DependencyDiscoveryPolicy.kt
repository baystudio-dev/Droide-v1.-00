package com.baystudio.droide.core

/** Discovery only: never apply this policy to explicit reads, Explorer visibility, or synchronization. */
internal object DependencyDiscoveryPolicy {
    fun isExcluded(path: String, isDirectory: Boolean, includeDependencies: Boolean): Boolean {
        if (includeDependencies) return false
        // A file named node_modules is ordinary content; only directory segments are excluded.
        val directories = if (isDirectory) path else path.substringBeforeLast('/', "")
        return directories.split('/').any { it == "node_modules" }
    }
}
