package com.baystudio.droide.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.IdentityHashMap

/** Workspace-lifetime explicit choices. No launch, configuration, discovery of builds, or disk writes. */
class CMakeAnalysisSelections(
    workspace: File,
    val workspaceIdentity: String,
    val environmentIdentity: String,
    val mappingRevision: String,
    private val mapper: WorkspacePathMapper,
    private val inspector: Inspector,
) {
    interface Inspector {
        /** Reject symlink components and require exact canonical, no-follow directory identity. */
        fun directoryIdentity(directory: File, environmentIdentity: String): CMakeDirectoryIdentity
        /** Regular no-follow open/fstat/bounded read/fstat/final locator check; no project execution. */
        fun readCache(cache: File, maximumBytes: Int): ByteArray
    }
    enum class BuildMode { BUILTIN_ONLY_PRIVATE, EXISTING_CONFIGURED }
    data class CacheSourceAssociation(
        val workspaceIdentity: String,
        val sourceLocalCanonical: String,
        val sourceGuestAbsolute: String,
        val cacheHomeGuestNormalized: String,
        val policyId: String = CACHE_ASSOCIATION_POLICY,
    )
    data class Selection(
        val workspaceIdentity: String,
        val environmentIdentity: String,
        val mappingRevision: String,
        val sourceLocalCanonical: String,
        val sourceGuestAbsolute: String,
        val sourceDirectoryIdentity: CMakeDirectoryIdentity,
        val selectionRevision: Long,
        val buildMode: BuildMode,
        val configuredBuildLocalCanonical: String? = null,
        val configuredBuildGuestAbsolute: String? = null,
        val configuredBuildDirectoryIdentity: CMakeDirectoryIdentity? = null,
        val cacheSourceAssociation: CacheSourceAssociation? = null,
        val privateBuildPolicyId: String = PRIVATE_BUILD_POLICY,
    )
    data class Status(
        val selection: Selection?,
        val suggestedSourcePath: String,
        val message: String,
        val available: Boolean,
        val nestedSourceAmbiguity: Boolean = false,
    )
    private val root = workspace.canonicalFile
    private val selections = LinkedHashMap<String, Selection>()
    private val staged = IdentityHashMap<Selection, Unit>()
    private val unavailable = HashMap<String, String>()
    private var revision = 0L
    private var closed = false
    init {
        require(workspaceIdentity.isNotBlank() && environmentIdentity.isNotBlank() && mappingRevision.isNotBlank())
        require(mapper.localRoot == root && normalizeGuestPath(mapper.remoteRoot) == mapper.remoteRoot)
    }

    /** Read-only, possibly blocking validation; callers run this outside manager locks on IO. */
    fun validate(sourcePath: String, configuredBuildPath: String?): Selection {
        synchronized(this) { check(!closed) { "CMake workspace is closed" } }
        val source = checkedLocal(sourcePath)
        val sourceIdentity = inspector.directoryIdentity(source, environmentIdentity)
        require(sourceIdentity.environmentIdentity == environmentIdentity)
        val sourceGuest = mappedGuest(source)
        val build = configuredBuildPath?.let { checkedLocal(it) }
        val buildIdentity = build?.let { inspector.directoryIdentity(it, environmentIdentity) }
        require(buildIdentity == null || buildIdentity.environmentIdentity == environmentIdentity)
        val buildGuest = build?.let(::mappedGuest)
        val provisional = Selection(workspaceIdentity, environmentIdentity, mappingRevision, source.path, sourceGuest,
            sourceIdentity, 0, if (build == null) BuildMode.BUILTIN_ONLY_PRIVATE else BuildMode.EXISTING_CONFIGURED,
            build?.path, buildGuest, buildIdentity)
        val association = build?.let { validateCacheAssociation(inspector.readCache(File(it, "CMakeCache.txt"), MAX_CACHE_BYTES), provisional, mapper) }
        check(inspector.directoryIdentity(source, environmentIdentity) == sourceIdentity) { "Selected source directory changed; review the selection" }
        if (build != null) check(inspector.directoryIdentity(build, environmentIdentity) == buildIdentity) { "Selected build directory changed; review the selection" }
        val candidate = provisional.copy(cacheSourceAssociation = association)
        synchronized(this) {
            check(!closed) { "CMake workspace is closed" }
            // Staged choices are transient validation results, never authority; newest validation
            // replaces discarded UI staging without evicting any committed source selection.
            if (staged.size >= MAX_SELECTIONS) staged.clear()
            staged[candidate] = Unit
        }
        return candidate
    }

    /** Atomic commit only of this store's exact validated record. No I/O under the caller's lock. */
    @Synchronized fun commit(candidate: Selection, replacing: Selection? = null): Selection {
        check(!closed && staged.remove(candidate) != null) { "CMake selection validation expired" }
        check(replacing == null || selections[replacing.sourceLocalCanonical] === replacing) { "The prior CMake source selection changed; review the staged choice" }
        val removesPriorSource = replacing != null && replacing.sourceLocalCanonical != candidate.sourceLocalCanonical
        val retainedCount = selections.size - if (removesPriorSource) 1 else 0
        check(candidate.sourceLocalCanonical in selections || retainedCount < MAX_SELECTIONS) { "At most 32 CMake source selections may be retained in one workspace" }
        check(revision < Long.MAX_VALUE) { "CMake selection revision exhausted; reopen the workspace" }
        val committed = candidate.copy(selectionRevision = ++revision)
        if (removesPriorSource) {
            selections.remove(requireNotNull(replacing).sourceLocalCanonical)
            unavailable.remove(replacing.sourceLocalCanonical)
        }
        selections[committed.sourceLocalCanonical] = committed
        unavailable.remove(committed.sourceLocalCanonical)
        return committed
    }
    @Synchronized fun discard(candidate: Selection) { staged.remove(candidate) }

    /** Pure lexical lookup on admitted records; never canonicalize or touch disk here. */
    @Synchronized fun selectionFor(document: File): Selection? {
        if (closed) return null
        val path = document.toPath().toAbsolutePath().normalize().toString()
        return selections.values.filter { contains(it.sourceLocalCanonical, path) }.maxByOrNull { it.sourceLocalCanonical.length }
    }
    @Synchronized fun status(document: File): Status {
        val selected = selectionFor(document)
        val suggested = selected?.sourceLocalCanonical ?: document.toPath().toAbsolutePath().normalize().parent?.toString().orEmpty()
        val reason = selected?.let { unavailable[it.sourceLocalCanonical] }
        return Status(selected, suggested, reason ?: if (selected == null)
            "Select and save a source directory before enabling CMake analysis. The document directory is only a suggestion; review nested sources."
        else if (selected.buildMode == BuildMode.BUILTIN_ONLY_PRIVATE)
            "Saved builtin documentation mode. Enable analysis separately; no project build is selected."
        else "Saved configured build: ${selected.configuredBuildLocalCanonical}. Enable analysis separately.",
            !closed && selected != null && reason == null, selected == null)
    }
    @Synchronized fun markUnavailable(selection: Selection, reason: String) {
        if (selections[selection.sourceLocalCanonical] === selection) unavailable[selection.sourceLocalCanonical] = reason.take(1_000)
    }
    @Synchronized fun isCurrent(selection: Selection): Boolean = !closed &&
        selections[selection.sourceLocalCanonical] === selection && selection.sourceLocalCanonical !in unavailable

    /** Observations fail closed and preserve the inert record. No automatic source/build fallback. */
    fun revalidate(selection: Selection): Boolean {
        if (!isCurrent(selection)) return false
        val valid = runCatching {
            check(selection.workspaceIdentity == workspaceIdentity && selection.environmentIdentity == environmentIdentity && selection.mappingRevision == mappingRevision)
            val source = checkedLocal(selection.sourceLocalCanonical)
            check(mappedGuest(source) == selection.sourceGuestAbsolute && inspector.directoryIdentity(source, environmentIdentity) == selection.sourceDirectoryIdentity)
            if (selection.buildMode == BuildMode.EXISTING_CONFIGURED) {
                val build = checkedLocal(requireNotNull(selection.configuredBuildLocalCanonical))
                check(mappedGuest(build) == selection.configuredBuildGuestAbsolute && inspector.directoryIdentity(build, environmentIdentity) == selection.configuredBuildDirectoryIdentity)
                check(validateCacheAssociation(inspector.readCache(File(build, "CMakeCache.txt"), MAX_CACHE_BYTES), selection, mapper) == selection.cacheSourceAssociation)
                check(inspector.directoryIdentity(build, environmentIdentity) == selection.configuredBuildDirectoryIdentity)
            } else check(selection.configuredBuildLocalCanonical == null && selection.configuredBuildGuestAbsolute == null &&
                selection.configuredBuildDirectoryIdentity == null && selection.cacheSourceAssociation == null)
            check(inspector.directoryIdentity(source, environmentIdentity) == selection.sourceDirectoryIdentity)
        }.isSuccess
        if (!valid) markUnavailable(selection, "Selected CMake source/build is unavailable or its directory or cache association changed. Review and save the explicit pairing again.")
        return valid && isCurrent(selection)
    }
    @Synchronized fun clear() { closed = true; selections.clear(); staged.clear(); unavailable.clear() }

    private fun checkedLocal(raw: String): File {
        require(raw.isNotBlank() && raw.length <= MAX_PATH_CHARS && raw.none { it.isISOControl() || it == '\\' }) { "CMake paths must be bounded ordinary local paths" }
        require(raw.split('/').none { it == ".." }) { "CMake paths cannot contain parent traversal" }
        val requested = if (File(raw).isAbsolute) File(raw) else File(root, raw)
        val lexical = requested.toPath().toAbsolutePath().normalize().toFile()
        require(contains(root.path, lexical.path) && lexical.path.length <= MAX_PATH_CHARS) { "Select source and build inside the opened workspace; open their common parent for sibling builds" }
        // Inspector rejects all symlink components; canonical equality is an additional bound.
        inspector.directoryIdentity(lexical, environmentIdentity)
        require(lexical.canonicalFile == lexical) { "CMake selection uses a symlink or noncanonical path; review its exact pairing" }
        return lexical
    }
    private fun mappedGuest(local: File): String {
        val guest = normalizeGuestPath(mapper.localToRemote(local)) ?: error("CMake guest mapping is unavailable")
        check(mapper.remoteToLocal(guest)?.path == local.path) { "CMake source/build mapping does not round trip" }
        return guest
    }
    companion object {
        const val MAX_SELECTIONS = 32
        const val MAX_PATH_CHARS = 4_096
        const val MAX_CACHE_BYTES = 1_048_576
        const val PRIVATE_BUILD_POLICY = "cmake-owner-private-checked-v1"
        const val CACHE_ASSOCIATION_POLICY = "cmake-home-directory-mapped-guest-v1"
        private fun contains(root: String, child: String): Boolean = child == root || child.startsWith(root.trimEnd('/') + "/")
        /** Guest paths are lexical POSIX values, never input to host File.canonicalFile. */
        fun normalizeGuestPath(raw: String): String? {
            if (!raw.startsWith('/') || raw.length > MAX_PATH_CHARS || raw.any { it.isISOControl() || it == '\\' }) return null
            val segments = raw.split('/')
            if (segments.any { it == ".." }) return null
            return "/" + segments.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        }
        fun validateCacheAssociation(bytes: ByteArray, selection: Selection, mapper: WorkspacePathMapper): CacheSourceAssociation {
            require(bytes.size <= MAX_CACHE_BYTES) { "CMake cache exceeds the bounded read limit" }
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            require('\u0000' !in text && text.indices.none { text[it] == '\r' && text.getOrNull(it + 1) != '\n' }) { "CMake cache is not ordinary UTF-8 LF/CRLF text" }
            var value: String? = null
            for (raw in text.split('\n')) {
                val line = raw.removeSuffix("\r")
                if (line.startsWith('#') || line.startsWith("//") || line.isBlank()) continue
                if (line.trimStart().startsWith("CMAKE_HOME_DIRECTORY")) {
                    require(line.startsWith("CMAKE_HOME_DIRECTORY:INTERNAL=") && value == null) { "CMake cache has ambiguous, malformed or duplicate source association" }
                    value = line.substringAfter("CMAKE_HOME_DIRECTORY:INTERNAL=")
                }
            }
            val home = normalizeGuestPath(requireNotNull(value) { "CMake cache has no source association" }) ?: error("CMake cache source is not an admitted absolute guest path")
            val sourceGuest = normalizeGuestPath(selection.sourceGuestAbsolute) ?: error("Selected CMake source mapping is invalid")
            require(home == sourceGuest) { "CMake cache belongs to a different guest source; review the source/build pairing" }
            // Only the admitted mapper translates the guest path, after its strict POSIX validation.
            val inverse = mapper.remoteToLocal(home) ?: error("CMake cache source escaped the opened mapping")
            require(inverse.path == selection.sourceLocalCanonical) { "CMake cache/source mapping does not round trip" }
            return CacheSourceAssociation(selection.workspaceIdentity, selection.sourceLocalCanonical, sourceGuest, home)
        }
    }
}
