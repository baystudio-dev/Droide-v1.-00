package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** One implementation for native chat and the external CLI bridge; only FileRepository publishes results. */
internal class MediaAuthoringTools(
    private val files: FileRepository,
    private val terminal: ITerminalSession,
    private val worker: () -> String,
    private val documents: WorkspaceDocumentAuthority? = null,
    private val requireCurrent: () -> Unit = {},
    private val onProducedFile: (String) -> Unit = {},
) {
    suspend fun execute(kind: String, args: JsonObject, mode: AgentMode, owner: String? = null,
                        current: () -> Unit = {}, permission: suspend (String, String) -> String?): String = withContext(Dispatchers.IO) {
        fun checkOwner() { requireCurrent(); current() }
        require(kind in setOf("document", "audio"))
        val operation = string(args, "operation")
        require(operation in (if (kind == "document") DOCUMENT_OPERATIONS else AUDIO_OPERATIONS)) { "Unsupported $kind operation" }
        val mutates = operation in WRITES
        if (mutates && mode != AgentMode.BUILD) return@withContext "DENIED: $kind $operation requires BUILD mode."
        checkOwner()
        currentCoroutineContext().ensureActive()
        if (operation == "status") {
            permission("shell", "media:$kind:status")?.let { return@withContext it }
            val result = run(listOf("python3", "-I", "-c", worker(), "--status", kind), 15_000)
            return@withContext decode(result).toString()
        }
        val path = if (operation == "create_docx") null else string(args, "path")
        // Ownership/path validation must precede approvals: a CLI cannot request access to another chat's blob.
        val input = path?.let { source(it, owner) }
        path?.let { permission("read", it)?.let { denied -> return@withContext denied } }
        val extension = input?.extension?.lowercase() ?: "bin"
        require(input == null || extension in if (kind == "document") setOf("pdf", "docx") else AUDIO_EXTENSIONS) {
            "Unsupported format. Documents: PDF/DOCX (export legacy DOC or Google Docs first). Audio: MP3/WAV/M4A/AAC/FLAC/OGG/OPUS."
        }
        val destination = if (mutates) destination(string(args, "destination")) else null
        destination?.let {
            require(documents?.snapshot(it)?.dirty != true) { "Destination has unsaved editor changes" }
            require(!files.exists(it)) { "Destination exists; choose a new filename. The original is never overwritten." }
            permission("edit", it)?.let { denied -> return@withContext denied }
        }
        val expected = args["expected_sha256"]?.let { string(args, "expected_sha256").also(::requireSha) }
        require(!mutates || input == null || expected != null) { "Read/inspect the source first, then supply expected_sha256 for this edit" }
        val offset = args["offset"]?.let { integer(args, "offset", 0, Int.MAX_VALUE) } ?: 0
        require(offset == 0 || expected != null) { "Text continuation requires expected_sha256 from the previous page" }
        val outputExtension = when (operation) {
            "create_docx", "replace_text" -> "docx"
            "pdf_pages" -> "pdf"
            "render" -> "png"
            "edit" -> (args["format"]?.let { string(args, "format") } ?: "wav").also {
                require(it in setOf("wav", "mp3", "flac", "ogg")) { "Unsupported output audio format" }
            }
            else -> "bin"
        }
        require(destination == null || destination.substringAfterLast('.', "").lowercase() == outputExtension) {
            "Destination extension must be .$outputExtension"
        }
        // DOCX text is available without installing or launching a runtime.
        val nativeRead = kind == "document" && extension == "docx" && operation in setOf("inspect", "read")
        if (!nativeRead) permission("shell", "media:$kind:$operation")?.let { return@withContext it }
        val job = ".droide/media-jobs/${UUID.randomUUID()}"
        val jobDirectory = File(files.root, job)
        var processCompleted = true
        var jobKey: Any? = null
        // Keep raw parser/encoder writes inside the same export fence used by source runners.
        val writer = ExportWriterAdmission.beginWriter(files.root, "Media authoring")
        try {
            val inputPath = "$job/input.$extension"
            val sourceHash = input?.let {
                val before = observe(it)
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(boundedInput(it), digest).use { stream ->
                    files.writeStream(inputPath, stream, expectedBytes = before.last().size(), requireAbsent = true,
                        executable = false, beforePublication = {
                            jobKey = Files.readAttributes(jobDirectory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                            currentCoroutineContext().ensureActive(); checkOwner()
                            check(observe(it) == before) { "Source changed while being captured; inspect it again" }
                            val actual = digest.digest().hex()
                            require(expected == null || actual == expected) { "Source SHA-256 changed; inspect it again before editing or paging" }
                        })
                }
                sha(files.resolveChecked(inputPath))
            }
            jobKey = if (jobDirectory.exists()) Files.readAttributes(jobDirectory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() else null
            if (nativeRead) {
                val task = currentCoroutineContext()
                val read = DocxTextReader.read(files.resolveChecked(inputPath)) { task.ensureActive(); checkOwner() }
                val text = read.text
                require(offset <= text.length) { "Offset exceeds document text" }
                var end = minOf(text.length, offset + PAGE_CHARS)
                if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                require(offset == 0 || offset == text.length || !text[offset].isLowSurrogate()) { "Use the returned next_offset" }
                return@withContext buildJsonObject {
                    put("ok", true); put("source_sha256", sourceHash); put("format", "docx")
                    put("notice", read.notice); put("extraction_truncated", read.truncated)
                    if (operation == "read") {
                        put("text", text.substring(offset, end)); put("offset", offset)
                        put("next_offset", if (end < text.length) JsonPrimitive(end) else JsonNull)
                    }
                }.toString()
            }
            val request = JsonObject(args + mapOf("kind" to JsonPrimitive(kind), "extension" to JsonPrimitive(extension),
                "output_extension" to JsonPrimitive(outputExtension), "input_sha256" to JsonPrimitive(sourceHash)))
            val bytes = request.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 65_536) { "Media request exceeds 64 KiB; use a smaller operation" }
            files.writeStream("$job/request.json", bytes.inputStream(), bytes.size.toLong(), requireAbsent = true, executable = false)
            jobKey = Files.readAttributes(jobDirectory.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            checkOwner()
            processCompleted = false
            val execution = run(listOf("python3", "-I", "-c", worker(), jobDirectory.absolutePath), 120_000)
            processCompleted = !execution.timedOut
            val result = decode(execution)
            if (result["ok"] != JsonPrimitive(true)) return@withContext result.toString()
            currentCoroutineContext().ensureActive(); checkOwner()
            if (destination == null) return@withContext JsonObject(result + ("source_sha256" to JsonPrimitive(sourceHash))).toString()
            val output = files.resolveChecked("$job/output.$outputExtension")
            require(result["output"] == JsonPrimitive(output.name)) { "Worker returned an unexpected output" }
            val outputHash = result["sha256"]?.jsonPrimitive?.content ?: error("Worker omitted output SHA-256")
            requireSha(outputHash)
            val before = observe(output)
            val size = before.last().size()
            require(size in 1..MAX_BYTES && result["bytes"]?.jsonPrimitive?.longOrNull == size) { "Output size changed or exceeds 64 MiB" }
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                DigestInputStream(boundedInput(output), digest).use { stream ->
                    files.writeStream(destination, stream, size, requireAbsent = true, executable = false, beforePublication = {
                        currentCoroutineContext().ensureActive(); checkOwner()
                        check(observe(output) == before && digest.digest().hex() == outputHash) { "Output changed before publication" }
                    })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                // FileRepository may already have published before an observer failed. Never imply it is safe to replay.
                throw IllegalStateException("Media publication did not settle. Inspect $destination before any retry; it may already contain the result. Original preserved.", failure)
            }
            try { onProducedFile(destination) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A display observer cannot revoke a published file. */ }
            JsonObject(result.filterKeys { it != "output" } + mapOf("path" to JsonPrimitive(destination),
                "source_sha256" to JsonPrimitive(sourceHash), "original_preserved" to JsonPrimitive(true),
                "persisted_to_project" to JsonPrimitive(true))).toString()
        } finally {
            try {
            // An interrupted backend may still own its staging. Retain it rather than racing that writer.
            if (processCompleted && jobDirectory.exists()) withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    val directory = jobDirectory.toPath()
                    val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    if (jobKey != null && attributes.isDirectory && attributes.fileKey() == jobKey &&
                        files.resolveChecked(job).absoluteFile == jobDirectory.absoluteFile) {
                        // Disposable job files are not Explorer deletions: do not retain 64 MiB inputs in folder recovery.
                        Files.newDirectoryStream(directory).use { entries ->
                            val children = entries.asSequence().take(16).toList()
                            if (children.size < 16 && children.all { child ->
                                Files.isRegularFile(child, NOFOLLOW_LINKS) && (child.fileName.toString() in setOf("request.json", "input.$extension", "output.$outputExtension") || child.fileName.toString().startsWith(".droide-tmp-"))
                            }) {
                                children.forEach { Files.deleteIfExists(it) }
                                Files.deleteIfExists(directory)
                            }
                        }
                    }
                }
            }
            } finally { writer.close() }
        }
    }

    private suspend fun run(argv: List<String>, timeout: Long): ExecResult {
        currentCoroutineContext().ensureActive(); requireCurrent()
        val pinned = (terminal as? ExecutionTerminalRouter)?.bindCommandSession() ?: terminal
        val action: suspend () -> ExecResult = {
            WorkspaceCommandInvocation.execute(pinned, files.root, files.root, argv, timeout)
        }
        return (pinned as? DevelopmentOperationSession)?.protectCommand(action) ?: action()
    }

    private fun decode(result: ExecResult): JsonObject {
        require(!result.timedOut && !result.outputIncomplete) { "Media process did not finish with a complete result; no project output was published. Check the active Ubuntu terminal." }
        require(result.output.toByteArray().size <= 65_536) { "Media result exceeds the limit" }
        val value = runCatching { Json.parseToJsonElement(result.output.trim()) as? JsonObject }.getOrNull()
        require(value != null && value["ok"] is JsonPrimitive) {
            "Media runtime unavailable. Open an Ubuntu terminal, verify python3, then load /skill/documents or /skill/audio for setup. No automatic install was performed."
        }
        require(result.exitCode == 0 || value["ok"] == JsonPrimitive(false)) { "Media worker failed; no project output was published" }
        return value
    }

    private fun source(path: String, owner: String?): File {
        require(path.length in 1..500 && !File(path).isAbsolute)
        val file = if (path.startsWith(".droide/chat-media/")) {
            require(!owner.isNullOrBlank()) { "Conversation ownership is required for this attachment; external CLIs use project files" }
            ChatMediaStore(files.root).let { store -> requireNotNull(store.resolvePath(store.requireOwnedOriginal(owner, path).path)) { "Attachment is expired or unavailable" } }
        } else {
            require(AgentWorkspacePathPolicy.visible(path)) { "Use a visible project file" }
            files.resolveChecked(path).also {
                require(AgentWorkspacePathPolicy.visible(it.canonicalFile.relativeTo(files.root.canonicalFile).invariantSeparatorsPath)) { "Source resolves into internal project state" }
            }
        }
        observe(file)
        return file
    }

    private fun destination(path: String): String {
        ProjectCopyEvidence.validateDestination(path)
        require(AgentWorkspacePathPolicy.visible(path)) { "Choose a visible project destination" }
        return ExplorerFilePolicy.requireUserTarget(files.root, path)
    }

    private fun observe(file: File): List<Observation> {
        val root = files.root.absoluteFile.toPath().normalize()
        val path = file.absoluteFile.toPath().normalize()
        require(path != root && path.startsWith(root)) { "Media path escaped workspace" }
        var cursor = root
        return (listOf("") + root.relativize(path).map { it.toString() }).map { part ->
            if (part.isNotEmpty()) cursor = cursor.resolve(part)
            val a = Files.readAttributes(cursor, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(if (cursor == path) a.isRegularFile && a.size() in 1..MAX_BYTES else a.isDirectory) { "Media paths must use ordinary files and directories within the size limit" }
            Observation(checkNotNull(a.fileKey()), a.size(), a.lastModifiedTime().toString(), a.creationTime().toString(), cursor == path)
        }.map { if (it.file) it else it.copy(bytes = 0, modified = "") }
    }
    private data class Observation(val key: Any, val bytes: Long, val modified: String, val created: String, val file: Boolean) {
        fun size() = bytes
    }

    private fun boundedInput(file: File): InputStream = object : FilterInputStream(Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) checkCount(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { if (it > 0) checkCount(it) }
        private fun checkCount(n: Int) { count += n; require(count <= MAX_BYTES) { "Media exceeds 64 MiB" } }
    }
    private fun sha(file: File): String = boundedInput(file).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().hex()
    }

    companion object {
        const val ASSET = "agent-media/worker.py"
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val PAGE_CHARS = 8000
        private val DOCUMENT_OPERATIONS = setOf("status", "inspect", "read", "create_docx", "replace_text", "pdf_pages", "render")
        private val AUDIO_OPERATIONS = setOf("status", "inspect", "edit")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus")
        private val WRITES = setOf("create_docx", "replace_text", "pdf_pages", "render", "edit")
        private fun requireSha(value: String) = require(Regex("[a-f0-9]{64}").matches(value)) { "Invalid SHA-256" }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun string(args: JsonObject, key: String): String = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")
        private fun integer(args: JsonObject, key: String, min: Int, max: Int): Int = (args[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in min..max } ?: error("Invalid $key")
    }
}
