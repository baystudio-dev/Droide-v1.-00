package com.baystudio.droide.core

internal object FrozenCodexPatchParserOracle {
    fun parse(text: String): List<PatchTool.Op> {
        val lines = text.trim('\n', '\r').lines().map { it.removeSuffix("\r") }
        require(lines.firstOrNull()?.trim() == "*** Begin Patch" && lines.lastOrNull()?.trim() == "*** End Patch") {
            "A canonical patch must be enclosed by *** Begin Patch and *** End Patch"
        }
        val ops = mutableListOf<PatchTool.Op>()
        var i = 1
        fun path(prefix: String): String = lines[i].removePrefix(prefix).trim().also {
            require(it.isNotEmpty()) { "Patch path is missing" }
        }
        while (i < lines.lastIndex) {
            val marker = lines[i]
            if (marker.isBlank()) { i++; continue }
            when {
                marker.startsWith("*** Add File: ") -> {
                    val target = path("*** Add File: ")
                    i++
                    val body = mutableListOf<String>()
                    while (i < lines.lastIndex && !lines[i].startsWith("*** ")) {
                        require(lines[i].startsWith('+')) { "Add File lines must start with +: $target" }
                        body += lines[i++].substring(1)
                    }
                    ops += PatchTool.Op.Add(target, if (body.isEmpty()) "" else body.joinToString("\n", postfix = "\n"))
                }
                marker.startsWith("*** Delete File: ") -> {
                    ops += PatchTool.Op.Delete(path("*** Delete File: "))
                    i++
                }
                marker.startsWith("*** Update File: ") -> {
                    val target = path("*** Update File: ")
                    i++
                    val move = if (i < lines.lastIndex && lines[i].startsWith("*** Move to: ")) {
                        lines[i++].removePrefix("*** Move to: ").trim().also { require(it.isNotEmpty()) }
                    } else null
                    val hunks = mutableListOf<CodexPatchHunk>()
                    while (i < lines.lastIndex && !lines[i].startsWith("*** ")) {
                        val hasHeader = lines[i] == "@@" || lines[i].startsWith("@@ ")
                        require(hasHeader || hunks.isEmpty()) {
                            "Update $target requires an @@ hunk"
                        }
                        val heading = if (hasHeader) lines[i++].removePrefix("@@").trimStart().takeIf { it.isNotEmpty() } else null
                        val before = mutableListOf<String>()
                        val after = mutableListOf<String>()
                        var changed = false
                        while (i < lines.lastIndex && !lines[i].startsWith("*** ") &&
                            lines[i] != "@@" && !lines[i].startsWith("@@ ")) {
                            val line = lines[i++]
                            if (line.isEmpty()) { before += ""; after += ""; continue }
                            require(line[0] in " +-") { "Invalid hunk line in $target" }
                            when (line[0]) {
                                ' ' -> { before += line.substring(1); after += line.substring(1) }
                                '-' -> { before += line.substring(1); changed = true }
                                '+' -> { after += line.substring(1); changed = true }
                            }
                        }
                        require(changed) { "Hunk in $target does not change text" }
                        val atEnd = i < lines.lastIndex && lines[i] == "*** End of File"
                        if (atEnd) i++
                        if (atEnd) while (i < lines.lastIndex && lines[i].isBlank()) i++
                        hunks += CodexPatchHunk(heading, before, after, atEnd)
                        require(hunks.size <= 1_000) { "Too many patch hunks" }
                    }
                    require(hunks.isNotEmpty() || move != null) { "Empty Update File: $target" }
                    ops += PatchTool.Op.Update(target, "", "", move, hunks, canonical = true)
                }
                else -> error("Unknown canonical patch marker: $marker")
            }
            require(ops.size <= 100) { "Too many patch operations" }
        }
        require(ops.isNotEmpty()) { "Patch has no operations" }
        return ops
    }

    fun apply(text: String, hunks: List<CodexPatchHunk>, path: String): String {
        if (hunks.isEmpty()) return text // A move without text changes.
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val normalized = text.replace("\r\n", "\n")
        val trailingNewline = normalized.endsWith('\n')
        val original = if (normalized.isEmpty()) emptyList() else
            normalized.removeSuffix("\n").split('\n')
        val result = mutableListOf<String>()
        var cursor = 0
        var comparisons = 0
        fun spend() {
            comparisons++
            require(comparisons <= 2_000_000) { "Patch context search exceeded the mobile work budget; split the patch or add @@ headings" }
        }
        for (hunk in hunks) {
            var from = cursor
            hunk.heading?.let { heading ->
                val anchor = (cursor until original.size).firstOrNull { spend(); original[it] == heading }
                    ?: error("Hunk heading not found in $path: $heading")
                from = anchor + 1
            }
            var match = if (hunk.before.isEmpty()) original.size else -1
            if (hunk.before.isNotEmpty()) {
                val first = if (hunk.atEnd) original.size - hunk.before.size else from
                val last = original.size - hunk.before.size
                for (candidate in first..last) {
                    if (candidate < from) continue
                    val matches = hunk.before.indices.all {
                        spend(); original[candidate + it] == hunk.before[it]
                    }
                    if (matches) {
                        require(match == -1) { "Hunk in $path is ambiguous; read current text and add context" }
                        match = candidate
                    }
                }
            }
            require(match >= 0) { "Hunk in $path has no exact match; read current text and add context" }
            val start = match
            require(start >= cursor) { "Overlapping or out-of-order hunks in $path" }
            result += original.subList(cursor, start)
            result += hunk.after
            cursor = start + hunk.before.size
        }
        result += original.subList(cursor, original.size)
        return result.joinToString(newline) + if (result.isNotEmpty() && (trailingNewline || text.isEmpty())) newline else ""
    }
}
