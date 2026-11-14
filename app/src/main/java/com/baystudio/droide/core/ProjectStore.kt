package com.baystudio.droide.core

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.CopyOption
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class DroideProject(
    val id: String,
    val name: String,
    val rootPath: String,
    val treeUri: String? = null,
    val workspaceRemoved: Boolean = false,
)

interface ProjectStore {
    suspend fun load(): List<DroideProject>
    suspend fun save(all: List<DroideProject>)
    suspend fun loadActiveId(): String?
    suspend fun saveActiveId(id: String)
}

/** A caller-owned observation, not an immutable filesystem generation or CAS token. */
data class ProjectCatalogSnapshot(val projects: List<DroideProject>, val revision: DiskFileVersion)

/** Explicit capability; legacy/custom stores keep their existing unversioned contract. */
interface VersionedProjectStore : ProjectStore {
    suspend fun loadCatalog(): ProjectCatalogSnapshot
    suspend fun catalogRevision(all: List<DroideProject>): DiskFileVersion
    suspend fun saveCatalog(all: List<DroideProject>, expected: DiskFileVersion): DiskFileVersion
}

class ProjectCatalogConflictException internal constructor() : IOException(
    "Project catalog changed since it was loaded. The current catalog and proposed registration were preserved; " +
        "restart and review the saved projects before trying again.",
)

/** Cooperating catalog/selection publications and explicit update choices share this authority.
 * External/CLI writes remain outside it; lock ownership is not mode authorization. */
internal object ProjectCatalogMutationLocks {
    private val locks = mutableMapOf<String, WeakReference<Mutex>>()

    /** Explicit update choice never waits behind an admitted old-mode publisher. */
    suspend fun <T> withUpdateChoice(dir: File, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val mutex = forDirectory(dir)
        val owner = Any()
        check(mutex.tryLock(owner)) {
            "Catalog metadata publication is in progress; retry the explicit update choice after it finishes"
        }
        return try {
            currentCoroutineContext().ensureActive()
            action()
        } finally { mutex.unlock(owner) }
    }

    fun forDirectory(dir: File): Mutex = synchronized(locks) {
        locks.entries.removeAll { it.value.get() == null }
        // Resolve the platform app-files alias, never a mutable catalog or .droide alias.
        val key = File(dir.canonicalFile, ".droide/projects.json").path
        locks[key]?.get() ?: Mutex().also { locks[key] = WeakReference(it) }
    }
}

class FileProjectStore internal constructor(
    private val dir: File,
    private val durability: StreamPublicationDurability,
    private val replace: (Path, Path, Array<CopyOption>) -> Unit,
    private val requireWriteAdmission: () -> Unit = {},
) : VersionedProjectStore {
    internal constructor(dir: File, replace: (Path, Path, Array<CopyOption>) -> Unit) :
        this(dir, StreamPublicationDurability.platform, replace)
    constructor(dir: File) : this(dir, ProjectMetadataPublication::replace)
    private val f = File(dir, ".droide/projects.json")
    private val active = File(dir, ".droide/active_project.txt")
    private val json = Json

    override suspend fun load(): List<DroideProject> = loadCatalog().projects

    override suspend fun loadCatalog(): ProjectCatalogSnapshot = withContext(Dispatchers.IO) {
        requireMetadataLocation()
        if (!Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            val projectsRoot = ProjectWorkspacePolicy.projectsRoot(dir)
            if (projectsRoot.exists()) {
                Files.newDirectoryStream(projectsRoot.toPath()).use { entries ->
                    if (entries.iterator().hasNext()) throw catalogFailure("is missing while project files exist")
                }
            }
            return@withContext ProjectCatalogSnapshot(emptyList(), DiskFileVersion.fromText(null))
        }
        readExistingCatalog()
    }

    private fun requireMetadataLocation() {
        try {
            WorkspaceDirectoryIdentity(dir).requireCurrent()
            val metadata = requireNotNull(f.parentFile)
            if (Files.exists(metadata.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                WorkspaceDirectoryIdentity(metadata).requireCurrent()
            }
        } catch (failure: Exception) {
            throw catalogFailure("metadata location cannot be verified", failure)
        }
    }

    internal suspend fun readCatalogText(afterRead: () -> Unit = {}): String {
        val context = currentCoroutineContext()
        context.ensureActive()
        return AnchoredWorkspaceFiles(dir).use { container ->
            container.openDirectory(".droide").use { metadata ->
                val parentKey = metadata.directoryKey()
                metadata.openRead(f.name).use { input ->
                    val before = input.observe()
                    if (before.attributes.size() > CATALOG_MAX_BYTES) throw catalogFailure("exceeds the supported size")
                    val borrowed = object : FilterInputStream(input) { override fun close() = Unit }
                    val text = BoundedUtf8.read(borrowed, CATALOG_MAX_BYTES) {
                        throw catalogFailure("exceeds the supported size")
                    }
                    afterRead()
                    context.ensureActive()
                    fun requireSame(observed: AnchoredWorkspaceFiles.Observation) {
                        check(observed.attributes.fileKey() == before.attributes.fileKey() &&
                            observed.attributes.size() == before.attributes.size() &&
                            observed.attributes.lastModifiedTime() == before.attributes.lastModifiedTime() &&
                            observed.changed == before.changed) {
                            "Project catalog changed while reading; no loaded state was published"
                        }
                    }
                    requireSame(input.observe())
                    metadata.openRead(f.name).use { requireSame(it.observe()) }
                    check(container.directoryKey(".droide") == parentKey) { "Project metadata directory changed while reading" }
                    text
                }
            }
        }
    }

    private suspend fun readExistingCatalog(): ProjectCatalogSnapshot {
        if (!f.isFile || Files.isSymbolicLink(f.toPath())) throw catalogFailure("is not a regular file")
        if (f.length() > CATALOG_MAX_BYTES) throw catalogFailure("exceeds the supported size")

        return try {
            val text = readCatalogText()
            val arr = json.parseToJsonElement(text) as? JsonArray
                ?: throw UnsupportedCatalogFormat()
            val projectsRoot = ProjectWorkspacePolicy.projectsRoot(dir)
            val projects = arr.map { el ->
                val o = el as? JsonObject ?: throw IllegalArgumentException("Invalid project entry")
                if (o.keys.any { it !in CATALOG_FIELDS }) throw UnsupportedCatalogFormat()
                // Legacy trust is compatibility data and never grants workspace permissions.
                o["trusted"]?.takeUnless { it == JsonNull }?.let { value ->
                    if (value !is JsonPrimitive || value.isString || value.booleanOrNull == null) {
                        throw UnsupportedCatalogFormat()
                    }
                }
                val id = optionalString(o, "id") ?: throw IllegalArgumentException("Project ID is missing")
                val safeId = PathSecurity.safeLeafName(id)
                val name = optionalString(o, "name") ?: "Project"
                val tree = optionalString(o, "treeUri")
                val storedRoot = optionalString(o, "rootPath")
                val removed = o["workspaceRemoved"]?.let { value ->
                    require(value is JsonPrimitive && !value.isString) { "Invalid workspace membership" }
                    value.booleanOrNull ?: throw IllegalArgumentException("Invalid workspace membership")
                } ?: false
                val root = if (removed) {
                    ProjectWorkspacePolicy.requireDirectChild(projectsRoot, File(requireNotNull(storedRoot) {
                        "Stored project location is missing"
                    })).canonicalPath
                } else ProjectWorkspacePolicy.restoreRoot(projectsRoot, safeId, storedRoot).canonicalPath
                DroideProject(safeId, name.take(120), root, tree, removed)
            }.also { projects ->
                ProjectWorkspacePolicy.requireDistinctRoots(projectsRoot, projects)
            }
            ProjectCatalogSnapshot(projects.toList(), DiskFileVersion.fromText(text))
        } catch (unsupported: UnsupportedCatalogFormat) {
            throw catalogFailure("uses an unsupported format; open it with a compatible Droide version", unsupported)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw catalogFailure("cannot be read", failure)
        }
    }

    private class UnsupportedCatalogFormat : IllegalArgumentException()

    private fun optionalString(entry: JsonObject, key: String): String? {
        val value = entry[key] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString) { "Invalid project field: $key" }
        return value.content
    }

    private fun catalogFailure(reason: String, cause: Exception? = null) =
        IOException("Project catalog $reason. Existing project files and the catalog are preserved.", cause)

    override suspend fun save(all: List<DroideProject>) {
        saveCatalogInternal(all, expected = null)
    }

    override suspend fun catalogRevision(all: List<DroideProject>): DiskFileVersion =
        DiskFileVersion.fromText(serializeCatalog(all))

    internal suspend fun encodeCatalog(all: List<DroideProject>): String = serializeCatalog(all)

    override suspend fun saveCatalog(all: List<DroideProject>, expected: DiskFileVersion): DiskFileVersion =
        saveCatalogInternal(all, expected)

    private suspend fun saveCatalogInternal(all: List<DroideProject>, expected: DiskFileVersion?): DiskFileVersion =
        withContext(Dispatchers.IO) {
            ProjectCatalogMutationLocks.forDirectory(dir).withLock {
                requireWriteAdmission()
                val text = serializeCatalog(all)
                val proposed = DiskFileVersion.fromText(text)
                // Legacy callers also participate in the lock and retain format-preservation checks.
                val observed = if (Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    val containerIdentity = WorkspaceDirectoryIdentity(dir)
                    val metadataIdentity = WorkspaceDirectoryIdentity(requireNotNull(f.parentFile))
                    readExistingCatalog().revision.also {
                        containerIdentity.requireCurrent()
                        metadataIdentity.requireCurrent()
                    }
                } else null
                requireWriteAdmission()
                if (observed == proposed && (expected == null || expected == observed)) return@withLock proposed
                val replaceExisting = observed != null
                val publish: (Path, Path, Array<CopyOption>) -> Unit = { source, target, options ->
                    try { replaceWithAdmission(source, target, options, f) }
                    catch (conflict: FileAlreadyExistsException) {
                        if (replaceExisting) throw conflict
                        throw ProjectCatalogConflictException().also { it.initCause(conflict) }
                    }
                }
                ProjectMetadataPublication.writeChecked(f, text, publish, durability, replaceExisting, beforeReplace = {
                    // Creation is no-replace; existing-file replacement still needs journal integration for external races.
                    if (expected != null && observeCatalogRevision() != expected) {
                        throw ProjectCatalogConflictException()
                    }
                    requireWriteAdmission()
                })
                requirePostPublicationAdmission(f)
                proposed
            }
        }

    /** Raw observation, deliberately independent of startup's missing-with-existing-projects policy. */
    private fun observeCatalogRevision(): DiskFileVersion {
        val path = f.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return DiskFileVersion.fromText(null)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw catalogFailure("is not a regular file")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > CATALOG_MAX_BYTES) throw catalogFailure("exceeds the supported size")
                digest.update(buffer, 0, count)
            }
        }
        return DiskFileVersion(true, size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private suspend fun serializeCatalog(all: List<DroideProject>): String {
        ProjectWorkspacePolicy.requireDistinctRoots(ProjectWorkspacePolicy.projectsRoot(dir), all)
        val arr = JsonArray(all.map { p ->
            buildJsonObject {
                put("id", p.id)
                put("name", p.name)
                put("rootPath", p.rootPath)
                p.treeUri?.let { put("treeUri", it) }
                if (p.workspaceRemoved) put("workspaceRemoved", true)
            }
        })
        val text = arr.toString()
        BoundedUtf8.encodedSize(text, CATALOG_MAX_BYTES) {
            throw catalogFailure("would exceed the supported size; registration was not saved")
        }
        return text
    }

    override suspend fun loadActiveId(): String? = withContext(Dispatchers.IO) {
        // This read also resolves an ambiguous atomic-write return. The byte bound is
        // enforced while decoding, not just by a racy pre-read file-length check.
        if (!Files.isDirectory(active.parentFile!!.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(active.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withContext null
        try {
            BoundedUtf8.read(Files.newInputStream(active.toPath(), LinkOption.NOFOLLOW_LINKS), ACTIVE_ID_MAX_BYTES) {
                throw ActiveProjectIdTooLarge()
            }.trim().takeIf { it.isNotEmpty() }
        } catch (_: ActiveProjectIdTooLarge) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private class ActiveProjectIdTooLarge : IOException("Active project ID exceeds 512 bytes")

    override suspend fun saveActiveId(id: String) = withContext(Dispatchers.IO) {
        ProjectCatalogMutationLocks.forDirectory(dir).withLock {
            requireWriteAdmission()
            val text = id + "\n"
            BoundedUtf8.encodedSize(text, ACTIVE_ID_MAX_BYTES) {
                throw IOException("Active project ID would exceed the supported size. The previous selection was preserved.")
            }
            ProjectMetadataPublication.writeChecked(active, text, { source, target, options ->
                replaceWithAdmission(source, target, options, active)
            }, durability, beforeReplace = requireWriteAdmission)
            requirePostPublicationAdmission(active)
        }
    }

    private fun requirePostPublicationAdmission(target: File) {
        try { requireWriteAdmission() }
        catch (failure: Throwable) { throw ProjectMetadataPublication.unconfirmed(target, failure) }
    }

    private fun replaceWithAdmission(source: Path, target: Path, options: Array<CopyOption>, file: File) {
        try { replace(source, target, options) }
        catch (failure: Throwable) {
            // A failed native return can follow publication. Mode evidence must not be lost
            // when manager cancellation recovery reads back bytes equal to the proposal.
            try { requireWriteAdmission() }
            catch (modeFailure: Throwable) {
                val primary = if (modeFailure is Error && failure !is Error) modeFailure else failure
                throw ProjectMetadataPublication.unconfirmed(file, primary).also {
                    if (modeFailure !== primary) it.addSuppressed(modeFailure)
                    if (failure !== primary) it.addSuppressed(failure)
                }
            }
            throw failure
        }
        requirePostPublicationAdmission(file)
    }

    private companion object {
        private val CATALOG_FIELDS = setOf("id", "name", "rootPath", "treeUri", "workspaceRemoved", "trusted")
        private const val CATALOG_MAX_BYTES = 2_000_000L
        private const val ACTIVE_ID_MAX_BYTES = 512L
    }
}
