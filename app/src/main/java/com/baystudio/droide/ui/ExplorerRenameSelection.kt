package com.baystudio.droide.ui

/** Keep the existing selection attached to its renamed tree position, without changing focus. */
internal fun ExplorerWorkspaceState.renamed(from: String, to: String) {
    fun remap(path: String): String =
        if (path == from || path.startsWith("$from/")) to + path.removePrefix(from) else path
    expandedPaths = expandedPaths.map(::remap).distinct()
    activeDirectory = remap(activeDirectory)
    selectedPath = selectedPath?.let(::remap)
    pendingReveal = pendingReveal?.let(::remap)
}
