package com.baystudio.droide.core

import java.io.File

class AgentWorkspaceReader(
    private val files: FileRepository,
    private val documents: WorkspaceDocumentAuthority? = null,
) {
    suspend fun read(path: String, start: Int? = null, end: Int? = null, offset: Int? = null): String {
        require(offset == null || offset >= 0) { "offset must be non-negative" }
        require(offset == null || (start == null && end == null)) { "offset cannot be combined with start/end" }
        val normalizedPath = canonicalRelativePath(path)
        val snapshot = documents?.snapshot(normalizedPath)
        if (snapshot?.loaded == true && snapshot.kind == WorkspaceDocumentKind.LARGE && snapshot.dirty) {
            return "[DROIDE_DOCUMENT_STATE source=large_file_performance_buffer dirty=true]\n" +
                "This large file has unsaved editor changes. Save it before Agent file reads so Droide never exposes stale disk content."
        }
        val live = snapshot?.takeIf { it.loaded && it.kind == WorkspaceDocumentKind.TEXT }

        if (live != null) {
            val body = if (offset != null) {
                require(offset <= live.content.length) { "offset is beyond end of file" }
                require(offset == 0 || offset == live.content.length || !live.content[offset].isLowSurrogate() ||
                    !live.content[offset - 1].isHighSurrogate()) { "offset is inside a UTF-16 surrogate pair; use the returned next_offset" }
                val until = pageEnd(live.content, offset)
                renderOffset(TextReadPage(live.content.substring(offset, until), until, until < live.content.length), offset)
            } else if (start != null || end != null) {
                renderRange(live.content, start ?: 1, end ?: ((start ?: 1) + 199))
            } else {
                renderFull(live.content)
            }
            return buildString {
                append("[DROIDE_DOCUMENT_STATE source=editor_buffer dirty=")
                append(live.dirty)
                append(" document_id=")
                append(live.documentId ?: "unavailable")
                append(" revision=")
                append(live.revision)
                append(" change_version=")
                append(live.changeVersion)
                append(" saved_baseline_diverged=")
                append(live.divergedFromSavedBaseline)
                appendLine("]")
                append(body)
            }
        }

        return if (offset != null) {
            renderOffset(files.readTextPage(normalizedPath, offset, MAX_FULL_READ_CHARS), offset)
        } else if (start != null || end != null) {
            files.readRange(normalizedPath, start ?: 1, end ?: ((start ?: 1) + 199), MAX_FULL_READ_CHARS)
        } else {
            try { renderFull(files.readText(normalizedPath)) }
            catch (_: LargeFileException) {
                "[DROIDE_READ_PAGE large_file=true; ranged read]\n" + files.readRange(normalizedPath, 1, 200, MAX_FULL_READ_CHARS)
            }
        }
    }

    private fun canonicalRelativePath(path: String): String {
        val root = files.root.canonicalFile
        val target = PathSecurity.resolveWithin(root, path).canonicalFile
        return root.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
    }

    private fun renderFull(content: String): String {
        if (content.length <= MAX_FULL_READ_CHARS) return content
        val part = content.substring(0, pageEnd(content, 0))
        val nextLine = part.lineSequence().count()
        return "[DROIDE_READ_PAGE truncated=true next_start=$nextLine next_offset=${part.length} offset_unit=utf16; use read_file offset=next_offset for exact continuation]\n" + part
    }

    private fun pageEnd(content: String, offset: Int): Int {
        var end = minOf(content.length.toLong(), offset.toLong() + MAX_FULL_READ_CHARS).toInt()
        if (end > offset && end < content.length && content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) end--
        return end
    }

    private fun renderOffset(page: TextReadPage, offset: Int): String =
        "[DROIDE_READ_PAGE offset=$offset next_offset=${page.nextOffset} has_more=${page.hasMore} offset_unit=utf16]\n" + page.text

    private fun renderRange(content: String, start: Int, end: Int): String {
        val s = start.coerceAtLeast(1)
        val e = end.coerceIn(s, s + MAX_RANGE_SPAN)
        if (content.isEmpty()) return "(range $s–$e is beyond end of file)"

        val lines = content.lineSequence().iterator()
        var budget = MAX_FULL_READ_CHARS
        val renderedLines = mutableListOf<String>()
        var nextLine = s
        var clippedLine = false
        var lineNumber = 0
        var hasMore = false
        while (lines.hasNext()) {
            val line = lines.next()
            // Match disk ranges: a final separator terminates a line, not a new empty line.
            if (line.isEmpty() && !lines.hasNext() && (content.endsWith('\n') || content.endsWith('\r'))) break
            lineNumber++
            if (lineNumber < s) continue
            if (lineNumber > e) { hasMore = true; break }
            val rendered = if (line.length <= MAX_RENDERED_LINE_CHARS) line
            else {
                clippedLine = true
                var limit = MAX_RENDERED_LINE_CHARS
                if (line[limit - 1].isHighSurrogate() && line[limit].isLowSurrogate()) limit--
                line.take(limit) + "… [line truncated]"
            }
            val numbered = String.format("%4d│ %s", lineNumber, rendered)
            if (numbered.length + 1 > budget) { hasMore = true; break }
            budget -= numbered.length + 1
            renderedLines += numbered
            nextLine = lineNumber + 1
        }
        if (renderedLines.isEmpty()) return "(range $s–$e is beyond end of file)"

        return buildString {
            appendLine("… showing lines $s–${nextLine - 1}${if (hasMore) "; more lines follow; next_start=$nextLine" else ""} …")
            if (clippedLine) appendLine("[DROIDE_READ_PAGE long_line_truncated=true; use read_file offset=0 then next_offset for exact character pages]")
            renderedLines.forEach(::appendLine)
        }
    }

    private companion object {
        const val MAX_FULL_READ_CHARS = 10_000
        const val MAX_RANGE_SPAN = 500
        const val MAX_RENDERED_LINE_CHARS = 2_000
    }
}
