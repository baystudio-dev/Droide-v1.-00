package com.baystudio.droide.core

import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class SkillManagement(
    private val files: FileRepository,
    private val plugins: AgentPluginSource = AgentPluginSource.EMPTY,
    private val personalRoot: File? = null,
) {
    fun manager(scope: String? = null): SkillManager = (when (scope) {
        null -> SkillManager(files.root, plugins, personalRoot)
        "workspace" -> SkillManager(files.root, plugins)
        "user" -> requireNotNull(personalRoot) { "Personal skill storage is unavailable" }.let { SkillManager.personal(it) }
        else -> error("Skill scope must be workspace or user")
    }).withBundled()
    suspend fun catalog(scope: String? = null): List<SkillSummary> = withContext(Dispatchers.IO) { manager(scope).catalog() }
    suspend fun document(name: String, scope: String? = null): SkillDocument = withContext(Dispatchers.IO) {
        requireNotNull(manager(scope).document(name)) { "Skill is missing, unreadable or changed. Refresh the skill list." }
    }

    suspend fun save(markdown: String, scope: String, expectedSha256: String? = null): SkillDocument = withContext(Dispatchers.IO) {
        val name = SkillDocumentFormat.validate(markdown)
        val (repository, prefix) = storage(scope)
        val path = "$prefix$name/SKILL.md"
        val previous = if (repository.exists(path)) repository.readTextForEdit(path, SkillDocumentFormat.MAX_BYTES.toLong()) else null
        val version = DiskFileVersion.fromText(previous)
        require(if (expectedSha256 == null) !version.existed else version.existed && version.sha256 == expectedSha256) {
            "Skill already exists or changed. Read it again and provide its current SHA-256 to update it."
        }
        repository.writeTextIfUnchanged(path, markdown, version)
        stored(scope, name)
    }

    /** Remove only the managed entry point. Supporting resources remain available for recovery/reuse. */
    suspend fun remove(name: String, scope: String, expectedSha256: String) = withContext(Dispatchers.IO) {
        requireManagedName(name)
        val (repository, prefix) = storage(scope)
        val path = "$prefix$name/SKILL.md"
        val previous = repository.readTextForEdit(path, SkillDocumentFormat.MAX_BYTES.toLong())
        val version = DiskFileVersion.fromText(previous)
        require(version.sha256 == expectedSha256) { "Skill changed. Refresh before removing it." }
        check(repository.deleteFileIfUnchanged(path, version)) { "Skill was not removed" }
    }

    suspend fun importFile(input: InputStream, fileName: String, scope: String): SkillDocument = withContext(Dispatchers.IO) {
        if (!fileName.endsWith(".zip", ignoreCase = true)) {
            return@withContext save(decode(input.use { boundedBytes(it, SkillDocumentFormat.MAX_BYTES) }), scope)
        }
        val bundle = linkedMapOf<String, ByteArray>()
        var total = 0
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_FILES * 2) { "Skill archive contains too many entries" }
                val path = resourcePath(entry.name.trimEnd('/'))
                if (!entry.isDirectory) {
                    require(path !in bundle) { "Skill archive contains duplicate paths" }
                    val bytes = boundedBytes(zip, minOf(MAX_RESOURCE_BYTES, MAX_BUNDLE_BYTES - total))
                    total += bytes.size
                    bundle[path] = bytes
                    require(bundle.size <= MAX_FILES) { "Skill bundle contains too many files" }
                } else boundedBytes(zip, 0) // A directory entry must not hide an unbounded compressed payload.
                zip.closeEntry()
            }
        }
        val marker = bundle.keys.singleOrNull { it.substringAfterLast('/') == "SKILL.md" }
            ?: error("Import one skill folder containing exactly one SKILL.md")
        val prefix = marker.removeSuffix("SKILL.md")
        require(bundle.keys.all { it.startsWith(prefix) }) { "Archive contains files outside the skill folder" }
        publishBundle(bundle.mapKeys { it.key.removePrefix(prefix) }, scope)
    }

    suspend fun importDirectory(path: String, scope: String, beforePublish: suspend (String) -> Unit = {}): SkillDocument = withContext(Dispatchers.IO) {
        val reader = SkillBundleReader(files.root, File(files.root, safeRelative(path)))
        val bundle = linkedMapOf<String, ByteArray>()
        var total = 0
        for (relative in reader.paths()) {
            currentCoroutineContext().ensureActive()
            val bytes = reader.read(relative, minOf(MAX_RESOURCE_BYTES, MAX_BUNDLE_BYTES - total))
            total += bytes.size
            bundle[relative] = bytes
        }
        publishBundle(bundle, scope, beforePublish)
    }

    suspend fun resources(name: String, scope: String? = null): List<String> = withContext(Dispatchers.IO) {
        val document = document(name, scope)
        if (document.bundled) emptyList() else bundleReader(document).paths().filter { it != "SKILL.md" }
    }

    suspend fun readResource(name: String, path: String, scope: String? = null): String = withContext(Dispatchers.IO) {
        decode(bundleReader(document(name, scope)).read(path, SkillDocumentFormat.MAX_BYTES))
    }

    suspend fun copyResource(name: String, path: String, destination: String, scope: String? = null): String = withContext(Dispatchers.IO) {
        val bytes = bundleReader(document(name, scope)).read(path, MAX_RESOURCE_BYTES)
        val target = safeRelative(destination)
        require(!target.startsWith(".droide/") && !target.startsWith(".git/")) { "Choose a regular project destination" }
        files.writeStream(target, bytes.inputStream(), expectedBytes = bytes.size.toLong(), requireAbsent = true, executable = false)
        target
    }

    private suspend fun publishBundle(bundle: Map<String, ByteArray>, scope: String, beforePublish: suspend (String) -> Unit = {}): SkillDocument {
        require(bundle.size in 1..MAX_FILES && bundle.values.sumOf { it.size.toLong() } <= MAX_BUNDLE_BYTES)
        val markdown = decode(requireNotNull(bundle["SKILL.md"]) { "Skill folder has no SKILL.md" })
        val name = SkillDocumentFormat.validate(markdown)
        bundle.keys.forEach(::resourcePath)
        beforePublish(name)
        val (repository, prefix) = storage(scope)
        val target = "$prefix$name"
        require(!repository.exists(target)) { "A skill folder named $name already exists. Choose a different name or edit the existing skill." }
        // The catalog never sees a half-imported directory. Normal workspace move admission publishes
        // the complete bundle; cancelled staging remains outside discovery for recovery.
        val stage = if (scope == "workspace") ".droide/skill-imports/${UUID.randomUUID()}" else ".imports/${UUID.randomUUID()}"
        for ((relative, bytes) in bundle) {
            currentCoroutineContext().ensureActive()
            repository.writeStream("$stage/$relative", bytes.inputStream(), expectedBytes = bytes.size.toLong(), requireAbsent = true, executable = false)
        }
        check(repository.move(stage, target)) { "Skill publication failed; import staging was retained at $stage" }
        return stored(scope, name)
    }

    private fun bundleReader(document: SkillDocument): SkillBundleReader {
        require(!document.bundled) { "This bundled skill has no resource files; use its documented tools" }
        return SkillBundleReader(document.sourceRoot, document.bundleDirectory, document.validateSource)
    }

    private fun storage(scope: String): Pair<FileRepository, String> = when (scope) {
        "workspace" -> files to ".droide/skills/"
        "user" -> FileRepository(requireNotNull(personalRoot) { "Personal skill storage is unavailable" }) to ""
        else -> error("Skill scope must be workspace or user")
    }

    private fun stored(scope: String, name: String): SkillDocument {
        // A workspace override must not make saving a personal skill return that override's receipt.
        return requireNotNull(manager(scope).document(name)) { "Skill was saved but is not discoverable; inspect its frontmatter" }
    }

    companion object {
        const val MAX_FILES = 64
        const val MAX_RESOURCE_BYTES = 1_000_000
        const val MAX_BUNDLE_BYTES = 4_000_000
        fun personalRoot(context: android.content.Context?): File? = context?.filesDir?.let { File(it, "agent-skills") }

        fun requireManagedName(name: String) {
            require(name.length in 1..64 && Regex("[a-z0-9]+(?:[-_][a-z0-9]+)*").matches(name)) { "Invalid managed skill name" }
        }

        internal fun resourcePath(path: String): String = safeRelative(path).also {
            require(!AgentWorkspacePathPolicy.internalArtifact(it)) { "Private repository state and temporary files are not skill resources" }
        }

        internal fun safeRelative(path: String): String {
            require(path.isNotEmpty() && path.length <= 500 && !path.startsWith('/') && '\\' !in path) { "Use a relative skill path" }
            path.split('/').forEach { PathSecurity.safeLeafName(it) }
            return path
        }

        internal fun boundedBytes(input: InputStream, limit: Int): ByteArray {
            require(limit >= 0)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (read == -1) break
                require(read > 0 && out.size() + read <= limit) { "Skill file exceeds its size limit or cannot be read" }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
