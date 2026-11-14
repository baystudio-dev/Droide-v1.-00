package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FormatterDef(val name: String, val command: List<String>, val extensions: List<String>)
data class FormattedDocument(val text: String, val formatter: String)

object GuestDocumentFormatter {
    private const val MAX_BYTES = 2_000_000
    private val builtins = listOf(
        FormatterDef("ruff", listOf("ruff", "format", "\$FILE"), listOf("py", "pyi")),
        FormatterDef("prettier", listOf("prettier", "--stdin-filepath", "\$FILE"), listOf("js", "ts", "jsx", "tsx", "mjs", "cjs", "mts", "cts", "md", "json", "html", "css", "scss", "yaml", "yml")),
        FormatterDef("ktlint", listOf("ktlint", "-F", "\$FILE"), listOf("kt", "kts")),
        FormatterDef("gofmt", listOf("gofmt", "-w", "\$FILE"), listOf("go")),
        FormatterDef("shfmt", listOf("shfmt", "-w", "\$FILE"), listOf("sh", "bash")),
    )

    fun definition(path: String): FormatterDef? =
        builtins.firstOrNull { path.substringAfterLast('.', "").lowercase() in it.extensions }

    suspend fun format(
        root: File,
        relativePath: String,
        content: String,
        codeStyle: CodeStyleProfile? = null,
        execute: suspend (List<String>) -> ExecResult,
    ): FormattedDocument? = ExportWriterAdmission.withSuspendingWriter(root, "Document formatter") { withContext(Dispatchers.IO) {
        val definition = definition(relativePath) ?: return@withContext null
        val target = PathSecurity.resolveWithin(root, relativePath)
        require(target.isFile) { "Formatter source is missing: $relativePath" }
        val bytes = content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Formatter skipped: file exceeds 2 MB safety limit" }
        // Snapshot unsaved content without overwriting the saved file. Prettier receives
        // the original filename separately so filename-specific config remains applicable.
        val snapshot = File.createTempFile(".droide-format-", ".${target.extension.lowercase()}", target.parentFile)
        var formatted = snapshot
        try {
            snapshot.writeBytes(bytes)
            if (definition.name == "prettier") {
                formatted = File.createTempFile(".droide-formatted-", ".${target.extension.lowercase()}", target.parentFile)
            }
            val formatterPath = if (definition.name == "prettier") target.absolutePath else snapshot.absolutePath
            val argv = definition.command.map { if (it == "\$FILE") formatterPath else it }.toMutableList()
            when (definition.name) {
                "prettier" -> {
                    argv.addAll(1, listOf("--tab-width", (codeStyle?.tabWidth ?: 4).coerceIn(1, 8).toString()))
                    if (codeStyle?.useTabs == true) argv.add(3, "--use-tabs")
                }
                "shfmt" -> argv.addAll(1, listOf("-i", if (codeStyle?.useTabs == true) "0" else (codeStyle?.indentSize ?: 4).coerceIn(1, 8).toString()))
            }
            // Fixed adapter only; file paths and formatter arguments are positional data.
            // Separate output prevents truncating the input snapshot before it is read.
            val invocation = if (formatted === snapshot) argv else listOf(
                "sh", "-c",
                """input=${'$'}1; output=${'$'}2; shift 2; exec "${'$'}@" < "${'$'}input" > "${'$'}output";""",
                "droide-formatter", snapshot.absolutePath, formatted.absolutePath,
            ) + argv
            val result = execute(invocation)
            check(!result.timedOut && result.exitCode == 0) {
                if (!result.timedOut && result.exitCode in listOf(126, 127))
                    "Install ${definition.name} in the active Ubuntu terminal to format this file"
                else "Formatter ${definition.name} ${if (result.timedOut) "timed out" else "failed"}: ${result.output.takeLast(500)}"
            }
            check(formatted.isFile && !PathSecurity.isSymbolicLink(formatted) && formatted.length() <= MAX_BYTES) {
                "Formatter produced an invalid file or output larger than 2 MB"
            }
            val decoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(formatted.readBytes())).toString()
            FormattedDocument(decoded, definition.name)
        } finally {
            if (formatted !== snapshot) formatted.delete()
            snapshot.delete()
        }
    } }
}
