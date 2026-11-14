package com.baystudio.droide.core

internal object AgentWorkspacePathPolicy {
    private val stagingPrefixes = listOf(".droide-tmp-", ".droide-copy-", ".droide-sync-")
    private val excluded = setOf(".git", ".gradle", ".droide", "node_modules")
    // Published build products are project files. Only transient Gradle working trees
    // are excluded; there is no extension/type whitelist for ordinary output files.
    private val transientBuildDirectories = setOf("intermediates", "tmp", "kotlin", ".transforms")
    /** Private repository state and retained publication stages are never skill resources. */
    fun internalArtifact(path: String): Boolean = path.split('/').any { part ->
        part == ".git" || part == ".droide" || stagingPrefixes.any(part::startsWith)
    }

    fun visible(path: String): Boolean {
        if (path.isBlank() || path.length > 500 || path.startsWith('/') || '\\' in path || path.any { it.code < 32 || it.code == 127 }) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it in excluded || stagingPrefixes.any(it::startsWith) }) return false
        if (parts.zipWithNext().any { (parent, child) -> parent == "build" && child in transientBuildDirectories }) return false
        return path != ".droide-sync-manifest.tsv" && !SensitivePathPolicy.isSensitive(path)
    }

    fun coverageNotice(paths: Set<String>, view: String): String? {
        val sample = java.util.TreeSet<String>()
        var count = 0
        for (path in paths) {
            if (path == ".droide-sync-manifest.tsv" || path.split('/').any { part ->
                    part == ".droide" || stagingPrefixes.any(part::startsWith)
                }) continue
            count++
            sample += path
            if (sample.size > 4) sample.pollLast()
        }
        if (count == 0) return null
        val names = sample.joinToString { path ->
            buildString {
                for (character in path.take(120)) append(when (character) {
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> if (character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt())
                        "\\u${character.code.toString(16).padStart(4, '0')}" else character.toString()
                })
                if (path.length > 120) append('…')
            }.take(180)
        }
        val more = if (count > sample.size) "; ${count - sample.size} more" else ""
        return "$view: $count excluded entr${if (count == 1) "y" else "ies"}: $names$more. " +
            "These entries and their contents are outside automatic synchronization."
    }

    fun hashScanCommand(remoteRoot: String, checksumCommand: String): String =
        "set -eu; cd ${DeviceBridgeManager.shellQuote(remoteRoot)}; " +
            "find . \\( -type d \\( -name .git -o -name .gradle -o -name .droide -o -name node_modules " +
            stagingPrefixes.joinToString("") { "-o -name '$it*' " } +
            transientBuildDirectories.sorted().joinToString("") { "-o -path '*/build/$it' " } +
            "\\) \\) -prune -o -type f ! -name '.droide-sync-manifest.tsv' ! -name 'local.properties' " +
            stagingPrefixes.joinToString("") { "! -name '$it*' " } +
            "-print | sort | while IFS= read -r f; do $checksumCommand \"${'$'}f\"; done"

    fun manifest(text: String): Map<String, String> = WorkspaceSyncManifest.parseTrustedOrEmpty(text, 20_000)
        .filterKeys(::visible).mapValues { it.value.sha256 }

    fun hashListing(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        text.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            require(line.length >= 67 && line.substring(0, 64).matches(Regex("[0-9a-f]{64}")) &&
                line[64] == ' ' && line[65] in setOf(' ', '*')) { "Malformed agent checksum listing; source is preserved" }
            val path = line.substring(66).removePrefix("./")
            if (visible(path)) {
                require(result.put(path, line.substring(0, 64)) == null) { "Duplicate agent checksum path" }
                require(result.size <= 20_000) { "Agent workspace contains too many files" }
            }
        }
        return result
    }
}
