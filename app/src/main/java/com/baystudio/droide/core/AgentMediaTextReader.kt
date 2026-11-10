package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An attachment alias is not a project file; registered, owned text uses existing bounded paging. */
internal object AgentMediaTextReader {
    private val textExtensions = setOf("txt", "md", "csv", "tsv", "log", "json", "jsonl", "xml", "yaml", "yml", "toml",
        "ini", "cfg", "conf", "html", "htm", "css", "scss", "less", "js", "mjs", "cjs", "jsx", "ts", "tsx",
        "kt", "kts", "java", "py", "rs", "go", "c", "h", "cpp", "hpp", "cc", "cxx", "sh", "bash", "zsh",
        "sql", "rb", "php", "swift", "dart", "lua", "r", "pl", "ex", "exs", "erl", "hs", "scala", "clj")

    suspend fun read(workDir: File, path: String, start: Int?, end: Int?, offset: Int?, sessionId: String?): String =
        withContext(Dispatchers.IO) {
            val owner = requireNotNull(sessionId) { "A chat attachment requires its active conversation" }
            val store = ChatMediaStore(workDir)
            val ref = requireNotNull(store.findByPath(path)) { "Attachment is expired or unavailable; choose it again" }
            require(store.ownerRefs(owner).any { it.id == ref.id }) { "This attachment does not belong to the active conversation" }
            val file = requireNotNull(store.resolvePath(path)) { "Attachment is expired or unavailable; choose it again" }
            val text = ref.mediaType.startsWith("text/") || ref.mediaType in setOf(
                "application/json", "application/xml", "application/yaml", "application/x-yaml", "application/javascript") ||
                !ref.mediaType.startsWith("image/") && !ref.mediaType.startsWith("video/") &&
                ref.name.substringAfterLast('.', "").lowercase() in textExtensions
            require(text) { "This is a binary attachment, not UTF-8 text. Use document for PDF/DOCX, audio for audio metadata/editing, or transcribe_audio for supported speech recognition when available. To copy the exact original into the project, use save_media_to_project(path=<this managed path>, destination=<new project-relative filename>) when advertised, or the current droide attachment save command for a CLI turn. Copying works for ZIP, PDF, documents and every file type without decoding. Use view_image/view_video only to inspect supported visuals." }
            // A concurrent deletion must fail, never recreate an attachment directory or source project.
            val body = AgentWorkspaceReader(FileRepository(file.parentFile, createRoot = false))
                .read(file.name, start, end, offset)
            require(store.resolvePath(path) != null) { "Attachment expired or was removed while it was read" }
            "[DROIDE_ATTACHMENT source=chat_owned expires_at=${ref.expiresAt}]\n$body"
        }
}
