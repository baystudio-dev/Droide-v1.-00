package com.baystudio.droide.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

/** Bounded review evidence for the UI/history, separate from model tool results. */
@Serializable
data class AgentFileChange(
    val path: String,
    val diff: String,
    val addedLines: Int? = null,
    val removedLines: Int? = null,
    val truncated: Boolean = false,
    val previousPath: String? = null,
)

/** Immutable presentation totals for one reviewed proposal; never mutation authority. */
data class AgentChangeTotals(val addedLines: Long?, val removedLines: Long?) {
    val known: Boolean get() = addedLines != null && addedLines >= 0 && removedLines != null && removedLines >= 0
}

object AgentFileChanges {
    /** Reuses already-computed full edit counts; never parses a clipped preview or reads files. */
    fun totals(changes: List<AgentFileChange>): AgentChangeTotals? {
        if (changes.isEmpty()) return null
        if (changes.any { it.addedLines == null || it.removedLines == null || it.addedLines < 0 || it.removedLines < 0 }) {
            return AgentChangeTotals(null, null)
        }
        // Int-sized counts and list size fit their aggregate in Long without Int overflow.
        return AgentChangeTotals(changes.sumOf { it.addedLines!!.toLong() }, changes.sumOf { it.removedLines!!.toLong() })
    }

    const val MAX_PREVIEW_BYTES = 16_000
    private const val MAX_DIFF_CHARS = 750_000
    private const val MAX_DIFF_LINES = 12_000

    fun preview(path: String, before: String?, after: String?, previousPath: String? = null,
                limit: Int = MAX_PREVIEW_BYTES): AgentFileChange {
        val old = before.orEmpty()
        val new = after.orEmpty()
        val safePath = path.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val safeOld = (previousPath ?: path).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val headers = "diff --git a/$safeOld b/$safePath\n" +
            "--- ${if (before == null) "/dev/null" else "a/$safeOld"}\n" +
            "+++ ${if (after == null) "/dev/null" else "b/$safePath"}\n"
        fun metadata(message: String, added: Int? = null, removed: Int? = null, unavailable: Boolean = false): AgentFileChange {
            val stream = PreviewStream(limit.coerceIn(256, MAX_PREVIEW_BYTES))
            stream.write((headers + message + "\n").toByteArray(Charsets.UTF_8))
            val text = stream.bytes.toString(Charsets.UTF_8.name()).let {
                if (stream.truncated) it.substringBeforeLast('\n', "") + "\n" else it
            }
            return AgentFileChange(path, text, added, removed, unavailable || stream.truncated, previousPath)
        }
        if (old == new) {
            val operation = when {
                before == null && after != null -> "Created empty file"
                before != null && after == null -> "Deleted empty file"
                previousPath != null && previousPath != path -> "Moved file (no content changes)"
                else -> "(no content changes)"
            }
            return metadata(operation, 0, 0)
        }
        if (old.length + new.length > MAX_DIFF_CHARS ||
            old.count { it == '\n' } + new.count { it == '\n' } > MAX_DIFF_LINES) {
            return metadata("Large file: open the editor to review the changes.", unavailable = true)
        }
        return try {
            val a = RawText(old.toByteArray(Charsets.UTF_8))
            val b = RawText(new.toByteArray(Charsets.UTF_8))
            val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                .diff(RawTextComparator.DEFAULT, a, b)
            val stream = PreviewStream(limit.coerceIn(256, MAX_PREVIEW_BYTES))
            stream.write(headers.toByteArray(Charsets.UTF_8))
            DiffFormatter(stream).use { formatter ->
                formatter.setContext(3)
                formatter.format(edits, a, b)
                formatter.flush()
            }
            var text = stream.bytes.toString(Charsets.UTF_8.name())
            if (stream.truncated) text = text.substringBeforeLast('\n', "") + "\n"
            AgentFileChange(path, text, edits.sumOf { it.lengthB }, edits.sumOf { it.lengthA }, stream.truncated, previousPath)
        } catch (_: Exception) {
            // Review formatting must never make a successful mutation fail.
            metadata("Preview unavailable: open the editor to review the changes.", unavailable = true)
        }
    }

    fun patch(ops: List<PatchTool.Op>, before: Map<String, String?>): List<AgentFileChange> {
        val perFile = (32_000 / ops.size.coerceAtLeast(1)).coerceIn(256, MAX_PREVIEW_BYTES)
        return ops.map { op -> when (op) {
            is PatchTool.Op.Add -> preview(op.path, before[op.path], op.content, limit = perFile)
            is PatchTool.Op.Delete -> preview(op.path, before[op.path], null, limit = perFile)
            is PatchTool.Op.Update -> preview(op.moveTo ?: op.path, before[op.path],
                PatchTool.updatedText(before[op.path].orEmpty(), op), op.path.takeIf { op.moveTo != null }, perFile)
        } }
    }

    private class PreviewStream(private val limit: Int) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var truncated = false
            private set
        override fun write(value: Int) {
            if (bytes.size() < limit) bytes.write(value) else truncated = true
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val available = (limit - bytes.size()).coerceAtLeast(0)
            bytes.write(buffer, offset, minOf(length, available))
            if (length > available) truncated = true
        }
    }
}
