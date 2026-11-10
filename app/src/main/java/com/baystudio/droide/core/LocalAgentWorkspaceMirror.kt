package com.baystudio.droide.core

import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A working copy, not a disposable cache: unpublished CLI bytes must survive every refresh. */
internal class LocalAgentWorkspaceMirror private constructor(
    private val source: File,
    val directory: File,
    private val fingerprints: WorkspaceFileFingerprintCache,
    private val authority: MirrorMetadataAuthority?,
) {
    constructor(
        source: File,
        directory: File,
        fingerprints: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache(),
    ) : this(source, directory, fingerprints, null)

    private val mutex = Mutex()
    private val sourceIdentity = WorkspaceDirectoryIdentity(source)
    @Volatile private var directoryIdentity: WorkspaceDirectoryIdentity? = null
    @Volatile var metadataNotice: String? = null
        private set
    @Volatile private var sourceCoverageNotice: String? = null
    @Volatile private var workingCoverageNotice: String? = null

    fun synchronizationNotice(): String? = listOfNotNull(metadataNotice, sourceCoverageNotice, workingCoverageNotice)
        .distinct().joinToString("\n").ifEmpty { null }
    // Both indexes live outside the guest mount. Guest-written manifests are never merge authority.
    private val conflictIndex = File(directory.parentFile, ".${directory.name}-attachment-conflicts.txt")
    private val baselineIndex = File(directory.parentFile, ".${directory.name}-sync-baseline.tsv")
    private val retainedAuthorityDirectory = File(directory.parentFile, ".${directory.name}-sync-authority")
    private val retainedConflicts = linkedSetOf<String>()
    val isPrepared: Boolean get() = directoryIdentity != null
    internal val processCustody: WorkspaceProcessCustody? get() = authority?.processCustody()

    internal suspend fun <T> withProcessCustody(action: suspend () -> T): T {
        val custody = processCustody ?: return action()
        requireCurrent()
        return withContext(custody) { action() }
    }

    fun requireCurrent() {
        sourceIdentity.requireCurrent()
        checkNotNull(directoryIdentity) { "CLI workspace is not prepared" }.requireCurrent()
    }

    suspend fun <T> withExecutionRead(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireCurrent()
            if (authority == null) legacyExecutionRead(action) else retainedRead { action() }
        }
    }

    suspend fun <T> withExecutionBaseline(
        action: suspend (Map<String, WorkspaceSyncManifest.Entry>) -> T,
    ): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireCurrent()
            if (authority == null) legacyExecutionRead { action(readBaseline().files) }
            else retainedRead { action(it.baseline) }
        }
    }

    private suspend fun <T> legacyExecutionRead(action: suspend () -> T): T =
        WorkspaceFileMutationLocks.withCaptureRoot(directory) { guard ->
            requireCurrent()
            action().also {
                requireCurrent()
                guard.requireRoot(directory)
            }
        }

    suspend fun requireExecutionWriteAdmission(guard: WorkspaceMutationGuard) {
        guard.requireRoot(directory)
        requireCurrent()
        authority?.let { LocalMirrorAuthorityBootstrap(source, directory).readLinkedExecutionState(guard, it) }
        requireCurrent()
        guard.requireRoot(directory)
    }

    suspend fun baseline(): Map<String, WorkspaceSyncManifest.Entry> = mutex.withLock {
        if (authority != null) retainedRead { it.baseline }
        else withContext(Dispatchers.IO) { sourceIdentity.requireCurrent(); directoryIdentity?.requireCurrent(); readBaseline().files }
    }

    suspend fun retainedConflictPaths(): Set<String> = mutex.withLock {
        if (authority == null) emptySet() else retainedRead { it.conflicts.toSet() }
    }

    suspend fun inventory(): Map<String, String> = mutex.withLock {
        workingCoverageNotice = null
        if (authority != null) return@withLock retainedRead { workingInventory().mapValues { it.value.sha256 } }
        sourceIdentity.requireCurrent()
        directoryIdentity?.requireCurrent()
        workingInventory().mapValues { it.value.sha256 }
    }

    suspend fun inventoryEntries(): Map<String, WorkspaceSyncManifest.Entry> = mutex.withLock {
        workingCoverageNotice = null
        if (authority != null) return@withLock retainedRead { workingInventory() }
        requireCurrent()
        workingInventory()
    }

    private suspend fun workingInventory(): Map<String, WorkspaceSyncManifest.Entry> = withContext(Dispatchers.IO) {
        val inventory = AnchoredWorkspaceTree.snapshot(directory, fingerprints, currentCoroutineContext())
        workingCoverageNotice = AgentWorkspacePathPolicy.coverageNotice(inventory.opaque, "CLI workspace")
        inventory.files.mapValues { (_, value) ->
            WorkspaceSyncManifest.Entry(value.sha256, if (value.executable) 493 else 420)
        }
    }

    suspend fun preserveAttachmentConflict(relative: String): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(AgentWorkspacePathPolicy.visible(relative)) { "Invalid attachment conflict path" }
            authority?.let { store ->
                WorkspaceFileMutationLocks.withRoot(directory) { guard ->
                    val state = LocalMirrorAuthorityBootstrap(source, directory).readLinkedExecutionState(guard, store)
                    store.commit(state.revision, state.baseline, state.conflicts + relative)
                }
                return@withContext
            }
            WorkspaceFileMutationLocks.withRoot(directory) { guard ->
                sourceIdentity.requireCurrent()
                directoryIdentity?.requireCurrent()
                guard.requireRoot(directory)
                requireNoRetainedAuthority()
                retainedConflicts += readConflicts()
                require(retainedConflicts.size < 128 || relative in retainedConflicts) { "Resolve existing CLI attachment conflicts first" }
                retainedConflicts += relative
                saveConflicts(retainedConflicts)
            }
        }
    }

    private suspend fun <T> retainedRead(action: suspend (MirrorMetadataAuthority.State) -> T): T =
        withContext(Dispatchers.IO) {
            WorkspaceFileMutationLocks.withCaptureRoot(directory) { guard ->
                val store = checkNotNull(authority)
                sourceIdentity.requireCurrent()
                directoryIdentity?.requireCurrent()
                val state = LocalMirrorAuthorityBootstrap(source, directory).readLinkedExecutionState(guard, store)
                action(state).also {
                    sourceIdentity.requireCurrent()
                    directoryIdentity?.requireCurrent()
                    guard.requireRoot(directory)
                }
            }
        }

    private fun readConflicts(): Set<String> {
        require(!Files.isSymbolicLink(conflictIndex.toPath())) { "Attachment conflict index cannot be a symlink" }
        if (!conflictIndex.exists()) return retainedConflicts.toSet()
        require(conflictIndex.isFile && conflictIndex.length() <= 65_536) { "CLI attachment conflict index is unavailable; mirror preserved" }
        val paths = conflictIndex.readLines().filter { it.isNotEmpty() }
        require(paths.size <= 128 && paths.all(AgentWorkspacePathPolicy::visible)) { "CLI attachment conflict index is invalid; mirror preserved" }
        return paths.toSet() + retainedConflicts
    }

    private fun saveConflicts(paths: Set<String>) = writeIndex(conflictIndex,
        paths.sorted().joinToString("\n", postfix = if (paths.isEmpty()) "" else "\n"))

    private data class Baseline(
        val files: Map<String, WorkspaceSyncManifest.Entry> = emptyMap(),
        val directories: Set<String> = emptySet(),
        val typed: Boolean = false,
    )

    private suspend fun readBaseline(allowNewWorkspace: Boolean = false): Baseline {
        requireNoRetainedAuthority()
        require(!Files.isSymbolicLink(baselineIndex.toPath())) { "CLI baseline cannot be a symlink; mirror preserved" }
        if (!Files.exists(baselineIndex.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            check(allowNewWorkspace || !Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "CLI baseline is missing for an existing working copy; restore its host metadata before retrying. All CLI files were preserved."
            }
            return Baseline()
        }
        require(baselineIndex.isFile && baselineIndex.length() <= MAX_MANIFEST_BYTES) { "CLI baseline is unavailable; mirror preserved" }
        val stored = Files.newInputStream(baselineIndex.toPath(), LinkOption.NOFOLLOW_LINKS).use {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = it.read(buffer)
                if (count < 0) break
                require(count <= MAX_MANIFEST_BYTES - bytes.size()) { "CLI baseline exceeds its size limit; mirror preserved" }
                bytes.write(buffer, 0, count)
            }
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
        val format = stored.substringBefore('\t')
        val typed = format == TYPED_BASELINE_HEADER
        val text = if (format == BASELINE_HEADER || typed) {
            val header = stored.substringBefore('\n').split('\t')
            val body = stored.substringAfter('\n', "")
            require('\n' in stored && header.size == 3 && header[0] == format && header[1] == baselineBinding() &&
                header[2] == MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).hex()) {
                "CLI baseline identity or checksum is invalid; all working-copy data was preserved"
            }
            body
        } else {
            require(stored.isNotBlank()) { "CLI baseline is empty or truncated; working-copy data was preserved" }
            stored
        }
        val directories = linkedSetOf<String>()
        val fileRows = StringBuilder()
        var fileCount = 0
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (typed && line.startsWith("D\t")) {
                require(directories.size < MAX_DIRECTORIES) { "CLI directory history exceeds its limit; data was preserved" }
                val path = line.substring(2)
                WorkspaceSyncManifest.validateRelativePath(path)
                require(AgentWorkspacePathPolicy.visible(path) && directories.add(path)) {
                    "CLI directory history contains an invalid or duplicate path; data was preserved"
                }
            } else {
                require(fileCount < MAX_FILES) { "CLI baseline exceeds its file limit; data was preserved" }
                fileRows.append(line).append('\n')
                fileCount++
            }
        }
        val entries = WorkspaceSyncManifest.parseTrustedOrEmpty(fileRows.toString(), MAX_FILES)
        require(entries.size == fileCount && entries.values.all { it.mode != null } &&
            directories.none { it in entries }) { "CLI baseline is invalid; mirror preserved" }
        val baseline = Baseline(entries.filterKeys(AgentWorkspacePathPolicy::visible), directories.toSet(), typed)
        if (typed) validateTypes(baseline)
        return baseline
    }

    private fun requireNoRetainedAuthority() {
        val present = try {
            Files.readAttributes(retainedAuthorityDirectory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: NoSuchFileException) {
            false
        }
        check(!present) {
            "CLI workspace has retained synchronization metadata; use its matching workspace or recover its metadata. All files were preserved."
        }
    }

    private fun baselineBinding(): String {
        val sourceKey = attributes(source.toPath()).fileKey().toString()
        val directoryKey = attributes(directory.toPath()).fileKey().toString()
        return MessageDigest.getInstance("SHA-256").digest(
            "${source.canonicalPath}\n$sourceKey\n$directoryKey".toByteArray(Charsets.UTF_8)).hex()
    }

    private fun validateTypes(baseline: Baseline) {
        for (path in baseline.files.keys + baseline.directories) {
            var slash = path.indexOf('/')
            while (slash >= 0) {
                require(path.substring(0, slash) !in baseline.files) {
                    "CLI baseline contains a file/directory collision; working-copy data was preserved"
                }
                slash = path.indexOf('/', slash + 1)
            }
        }
        require(baseline.directories.none { it in baseline.files }) { "CLI baseline contains conflicting path types" }
    }

    private fun saveBaseline(baseline: Baseline) {
        require(baseline.files.size <= MAX_FILES && baseline.directories.size <= MAX_DIRECTORIES) {
            "CLI baseline exceeds its entry limit; pending work preserved"
        }
        if (baseline.typed || baseline.directories.isNotEmpty()) validateTypes(baseline)
        val body = WorkspaceSyncManifest.serialize(baseline.files) +
            baseline.directories.sorted().joinToString("") { "D\t$it\n" }
        val header = if (baseline.typed || baseline.directories.isNotEmpty()) TYPED_BASELINE_HEADER else BASELINE_HEADER
        val encoded = "$header\t${baselineBinding()}\t" +
            MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).hex() + "\n" + body
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
            "CLI baseline exceeds size limit; working copy preserved"
        }
        sourceIdentity.requireCurrent()
        directoryIdentity?.requireCurrent()
        writeIndex(baselineIndex, encoded)
    }

    private fun writeIndex(file: File, text: String) {
        require(!Files.isSymbolicLink(file.toPath())) { "CLI metadata cannot be a symlink" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Compare actual bounded metadata bytes, not a cached mtime or guest-provided hash. Unchanged
        // turns need no temp file, fsync, rename or watcher event.
        if (sameIndexBytes(file, bytes)) return
        val temporary = Files.createTempFile(file.parentFile.toPath(), ".droide-sync-", ".tmp").toFile()
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncMetadataDirectory(file.parentFile)
        } finally { temporary.delete() }
    }

    private fun syncMetadataDirectory(directory: File) {
        if (System.getProperty("java.vm.name") == "Dalvik" ||
            System.getProperty("java.runtime.name") == "Android Runtime") {
            val fd = android.system.Os.open(directory.absolutePath,
                android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW, 0)
            try {
                check(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode)) { "CLI metadata parent is not a directory" }
                android.system.Os.fsync(fd)
            } finally { android.system.Os.close(fd) }
        } else java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }

    private fun sameIndexBytes(file: File, expected: ByteArray): Boolean {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            file.length() != expected.size.toLong()) return false
        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var offset = 0
            while (offset < expected.size) {
                val count = input.read(buffer, 0, minOf(buffer.size, expected.size - offset))
                if (count < 0) return false
                for (i in 0 until count) if (buffer[i] != expected[offset + i]) return false
                offset += count
            }
            return input.read() < 0
        }
    }

    suspend fun refresh(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
          sourceCoverageNotice = null
          workingCoverageNotice = null
          authority?.let { store ->
            WorkspaceFileMutationLocks.withRoot(directory) { guard ->
                sourceIdentity.requireCurrent()
                directoryIdentity?.requireCurrent()
                if (directoryIdentity == null) directoryIdentity = WorkspaceDirectoryIdentity(directory)
                val refresh = LocalMirrorRetainedRefresh(source, directory, store, fingerprints)
                metadataNotice = refresh.refresh(guard)
                sourceCoverageNotice = refresh.sourceCoverageNotice
                workingCoverageNotice = refresh.workingCoverageNotice
            }
            return@withContext
          }
          WorkspaceFileMutationLocks.forRoot(directory).withLock {
            sourceIdentity.requireCurrent()
            requireNoRetainedAuthority()
            check(source.isDirectory && !PathSecurity.isSymbolicLink(source)) { "Agent source workspace is unavailable" }
            directoryIdentity?.requireCurrent()
            val directoryExisted = Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
            check(directory.isDirectory || !baselineIndex.exists()) {
                "CLI workspace disappeared: ${directory.absolutePath}. Restore it before retrying; its baseline was retained."
            }
            check(directory.isDirectory || directory.mkdirs()) { "Agent mirror storage is unavailable" }
            if (directoryIdentity == null) directoryIdentity = WorkspaceDirectoryIdentity(directory)
            check(!PathSecurity.isSymbolicLink(directory)) { "Agent mirror cannot be a symlink" }
            require(!PathSecurity.contains(source, directory) && !PathSecurity.contains(directory, source)) { "Agent mirror must be separate from source" }
            val context = currentCoroutineContext()
            context.ensureActive()
            metadataNotice = null
            val baseline = readBaseline(allowNewWorkspace = !directoryExisted)
            val recorded = readConflicts()
            val protected = recorded.filterTo(linkedSetOf()) {
                Files.exists(File(directory, it).toPath(), LinkOption.NOFOLLOW_LINKS)
            }
            fun protectedPath(relative: String): Boolean = protected.any {
                relative == it || relative.startsWith("$it/") || it.startsWith("$relative/")
            }
            retainedConflicts.clear(); retainedConflicts += protected
            if (protected != recorded) saveConflicts(protected)
            val canonical = snapshot(source, protected, context)
            val working = snapshot(directory, protected, context)
            val previous = baseline.files.toMutableMap()
            val directoryHistory = baseline.directories.toMutableSet()
            val transitions = previous.keys.filter { it in canonical.directories && it in working.directories }
            if (transitions.isNotEmpty()) {
                transitions.forEach { previous.remove(it); directoryHistory += it }
                saveBaseline(Baseline(previous, directoryHistory, typed = true))
            }
            val next = previous.toMutableMap()
            val nextDirectories = directoryHistory.toMutableSet()
            val historicalPaths by lazy { java.util.TreeSet(previous.keys + directoryHistory) }
            val retainedStages = mutableMapOf<String, Int>()
            val blocked = linkedSetOf<String>()
            fun blockedPath(path: String): Boolean = blocked.any { path == it || path.startsWith("$it/") }

            // A source-side file -> directory change may replace only an unchanged working file.
            for (relative in canonical.directories.sortedBy { it.count { c -> c == '/' } }) {
                context.ensureActive()
                if (blockedPath(relative) || protectedPath(relative)) continue
                val target = PathSecurity.resolveWithin(directory, relative)
                val old = working.files[relative]

                if (old == null && relative !in working.directories &&
                    (relative in previous || relative in directoryHistory ||
                        historicalPaths.ceiling("$relative/")?.startsWith("$relative/") == true)) {
                    blocked += relative
                    continue
                }
                if (old != null) {
                    if (old.entry != previous[relative] || !deleteUnchanged(target, old, context)) {
                        blocked += relative
                        continue
                    }
                    next.remove(relative)
                }
                if (relative in working.directories) {
                    if (!Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) blocked += relative
                } else if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    blocked += relative
                } else {
                    check(target.isDirectory || target.mkdirs()) { "Cannot create agent mirror directory; pending CLI work preserved" }
                    if (old != null) nextDirectories += relative
                }
            }

            val originals = lazy(LazyThreadSafetyMode.NONE) { AnchoredWorkspaceFiles(source) }
            val workspace = lazy(LazyThreadSafetyMode.NONE) { AnchoredWorkspaceFiles(directory) }
            try {
                for ((relative, fresh) in canonical.files) {
                    context.ensureActive()
                    if (blockedPath(relative) || protectedPath(relative)) continue
                    val old = working.files[relative]
                    val base = previous[relative]
                    if (fresh.entry == old?.entry) {
                        next[relative] = fresh.entry
                        nextDirectories.remove(relative)
                        continue
                    }
                    val target = PathSecurity.resolveWithin(directory, relative)
                    if (relative in working.directories) {
                        if (!replaceableDirectory(relative, working, previous) ||
                            !removeUnchangedDirectory(relative, working, context)) continue
                        next.keys.removeAll { it.startsWith("$relative/") }
                    } else if (old == null) {
                        // With a known base, absence is an unpublished CLI deletion, not a cache miss.
                        if (base != null || relative in directoryHistory ||
                            working.opaque.any { relative == it || relative.startsWith("$it/") }) continue
                    } else if (old.entry != base) continue
                    if (copyIfUnchanged(relative, fresh, old, context, retainedStages, originals.value, workspace.value)) {
                        next[relative] = fresh.entry
                        nextDirectories.removeAll { it == relative || it.startsWith("$relative/") }
                    }
                }
            } finally {
                try { if (workspace.isInitialized()) workspace.value.close() }
                finally { if (originals.isInitialized()) originals.value.close() }
            }

            // Source-only removals are safe only when the mirror still has the previously agreed bytes.
            // Mirror-only additions and divergent files deliberately never appear in this deletion loop.
            for ((relative, base) in previous) {
                context.ensureActive()
                if (relative in canonical.files || relative in canonical.directories || protectedPath(relative) ||
                    canonical.opaque.any { relative == it || relative.startsWith("$it/") }) continue
                val old = working.files[relative]
                if (old == null) {
                    if (relative !in working.directories && working.opaque.none { relative == it || relative.startsWith("$it/") }) next.remove(relative)
                } else if (old.entry == base && deleteUnchanged(PathSecurity.resolveWithin(directory, relative), old, context)) {
                    next.remove(relative)
                }
            }
            nextDirectories.removeAll { relative ->
                !protectedPath(relative) && absentFrom(canonical, relative) && absentFrom(working, relative)
            }
            context.ensureActive()
            require(next.size <= MAX_FILES) { "CLI baseline exceeds 20,000 files; pending work preserved" }
            val manifest = WorkspaceSyncManifest.serialize(next)
            require(manifest.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "CLI baseline exceeds size limit; pending work preserved" }
            sourceIdentity.requireCurrent()
            directoryIdentity?.requireCurrent()
            val compatibility = File(directory, ".droide-sync-manifest.tsv")
            saveBaseline(Baseline(next, nextDirectories, baseline.typed || transitions.isNotEmpty()))
            val compatibilityMatches = try {
                sameIndexBytes(compatibility, manifest.toByteArray(Charsets.UTF_8))
            } catch (_: java.io.IOException) { false }
            val compatibilityNotice = if (!Files.exists(compatibility.toPath(), LinkOption.NOFOLLOW_LINKS) || compatibilityMatches) null else
                "Compatibility snapshot was preserved at ${compatibility.absolutePath}; " +
                    "local synchronization uses host-owned metadata."
            metadataNotice = listOfNotNull(metadataNotice, compatibilityNotice).joinToString("\n").ifEmpty { null }
          }
        }
    }

    private data class Version(val entry: WorkspaceSyncManifest.Entry, val attributes: BasicFileAttributes)
    private data class Tree(
        val files: MutableMap<String, Version> = linkedMapOf(),
        val directories: MutableSet<String> = linkedSetOf(),
        val opaque: MutableSet<String> = linkedSetOf(),
    )

    private fun snapshot(root: File, protected: Set<String>, context: CoroutineContext): Tree {
        fun excluded(relative: String) = !AgentWorkspacePathPolicy.visible(relative) ||
            protected.any { relative == it || relative.startsWith("$it/") }
        val anchored = AnchoredWorkspaceTree.snapshot(root, fingerprints, context, ::excluded)
        if (root == source) sourceCoverageNotice = AgentWorkspacePathPolicy.coverageNotice(anchored.opaque, "Project")
        else workingCoverageNotice = AgentWorkspacePathPolicy.coverageNotice(anchored.opaque, "CLI workspace")
        return Tree(anchored.files.mapValuesTo(linkedMapOf()) { (_, value) ->
            Version(WorkspaceSyncManifest.Entry(value.sha256, if (value.executable) 493 else 420), value.attributes)
        }, anchored.directories, anchored.opaque)
    }

    private fun absentFrom(tree: Tree, relative: String): Boolean {
        if (relative in tree.files || relative in tree.directories) return false
        var path = relative
        while (true) {
            if (path in tree.opaque) return false
            val slash = path.lastIndexOf('/')
            if (slash < 0) return true
            path = path.substring(0, slash)
        }
    }

    private fun replaceableDirectory(relative: String, tree: Tree, base: Map<String, WorkspaceSyncManifest.Entry>): Boolean {
        val prefix = "$relative/"
        if (tree.opaque.any { it.startsWith(prefix) }) return false
        val descendants = tree.files.filterKeys { it.startsWith(prefix) }
        if (descendants.any { (path, version) -> version.entry != base[path] }) return false
        if (base.keys.any { it.startsWith(prefix) && it !in descendants }) return false // Pending deletion. Unknown empty directories are CLI output too. Only remove ancestry of known
 // clean files.
        return tree.directories.filter { it == relative || it.startsWith(prefix) }
            .all { dir -> descendants.keys.any { it.startsWith("$dir/") } }
    }

    private fun removeUnchangedDirectory(relative: String, tree: Tree, context: CoroutineContext): Boolean {
        WorkspaceProcessPathGuard.requireUnused(PathSecurity.resolveWithin(directory, relative))
        for ((path, old) in tree.files.filterKeys { it.startsWith("$relative/") }) {
            if (!deleteUnchanged(PathSecurity.resolveWithin(directory, path), old, context)) return false
        }
        for (path in tree.directories.filter { it == relative || it.startsWith("$relative/") }.sortedByDescending { it.length }) {
            context.ensureActive()
            try { Files.delete(PathSecurity.resolveWithin(directory, path).toPath()) }
            catch (_: DirectoryNotEmptyException) { return false } // Concurrent CLI output must stay in place.
        }
        return true
    }

    private fun deleteUnchanged(file: File, expected: Version, context: CoroutineContext): Boolean {
        if (!matches(file, expected, context)) return false
        Files.delete(file.toPath())
        return true
    }

    private suspend fun copyIfUnchanged(relative: String, fresh: Version, old: Version?, context: CoroutineContext,
                                        retainedStages: MutableMap<String, Int>, originals: AnchoredWorkspaceFiles,
                                        workspace: AnchoredWorkspaceFiles): Boolean {
        val target = PathSecurity.resolveWithin(directory, relative)
        val parent = requireNotNull(target.parentFile) { "Agent mirror parent is unavailable" }
        check(Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "CLI parent directory disappeared during refresh; pending deletion was preserved"
        }
        WorkspaceTransferSpace.requireAvailable(parent, fresh.attributes.size(), 192L * 1024 * 1024)
        val temporary = File(parent, ".droide-tmp-${java.util.UUID.randomUUID()}")
        try {
            val parentRelative = relative.substringBeforeLast('/', "")
            val staged = if (parentRelative.isEmpty()) workspace else workspace.openDirectory(parentRelative)
            try {
                val parentKey = staged.directoryKey()
                val countBefore = retainedStages.getOrPut(parentRelative) {
                    val inventory = RetainedWorkPolicy.inspect(directory, parentRelative)
                    check(inventory.complete) { inventory.problem ?: "Retained CLI staging could not be inspected" }
                    inventory.candidates.size
                }
                check(countBefore < RetainedWorkPolicy.MAX_ENTRIES) {
                    "At least ${RetainedWorkPolicy.MAX_ENTRIES} retained copies need inspection at ${parent.absolutePath}"
                }
                val created = staged.createNew(temporary.name)
                retainedStages[parentRelative] = countBefore + 1
                created.use { output ->
                    val createdKey = output.observe().attributes.fileKey()
                    originals.openRead(relative).use { input ->
                        val before = input.observe()
                        check(sameStat(fresh.attributes, before.attributes) &&
                            before.executable == (fresh.entry.mode == 493)) {
                            "Source identity changed before copying; pending CLI work preserved"
                        }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        var bytesSinceSpaceCheck = 0L
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            check(n.toLong() <= fresh.attributes.size() - count) {
                                "Source changed while refreshing CLI workspace; pending work preserved"
                            }
                            bytesSinceSpaceCheck += n
                            if (bytesSinceSpaceCheck >= 16L * 1024 * 1024) {
                                WorkspaceTransferSpace.requireAvailable(parent,
                                    fresh.attributes.size() - count, 192L * 1024 * 1024)
                                bytesSinceSpaceCheck = 0L
                            }
                            count += n
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                        check(count == fresh.attributes.size() && digest.digest().hex() == fresh.entry.sha256 &&
                            sameObservation(before, input.observe()) &&
                            originals.openRead(relative).use { sameObservation(before, it.observe()) }) {
                            "Source changed while refreshing CLI workspace; pending work preserved"
                        }
                    }
                    context.ensureActive()
                    output.force(requireNotNull(fresh.entry.mode))
                    val forced = output.observe()
                    val prepared = fingerprints.read(staged, temporary.name, temporary.toPath(), context, allowReuse = false)
                    check(prepared.attributes.fileKey() == createdKey && prepared.sha256 == fresh.entry.sha256 &&
                        prepared.executable == (fresh.entry.mode == 493) && sameObservation(forced, output.observe())) {
                        "CLI copy stage changed; all named versions were preserved"
                    }
                    requireCurrent()
                    check(staged.directoryKey() == parentKey) { "CLI copy parent changed; all staged bytes were preserved" }
                    if (old == null) {
                        val receipt = staged.moveNoReplaceDurable(temporary.name, staged, target.name)
                        if (receipt.published) retainedStages[parentRelative] = countBefore
                        if (!receipt.published && receipt.error == 17) {
                            metadataNotice = "CLI destination changed concurrently. Staged copy retained at ${temporary.absolutePath}."
                            return false
                        }
                        check(receipt.durable) {
                            "CLI copy publication could not be confirmed (published=${receipt.published}, errno=${receipt.error})"
                        }
                    } else {
                        if (!matches(target, old, context)) {
                            metadataNotice = "CLI file changed concurrently. Staged copy retained at ${temporary.absolutePath}."
                            return false
                        }
                        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        retainedStages[parentRelative] = countBefore
                        staged.forceDirectory()
                    }
                    return true
                }
            } finally {
                if (staged !== workspace) staged.close()
            }
        } catch (cancelled: CancellationException) {
            metadataNotice = "CLI copy interrupted; inspect retained staging at ${temporary.absolutePath}."
            throw cancelled
        } catch (failure: Exception) {
            throw FileSystemException(target.path, temporary.path,
                "CLI copy stopped; staging was not deleted automatically. Inspect the staging path before retrying: ${failure.message}")
                .also { it.initCause(failure) }
        }
    }

    private fun sameObservation(a: AnchoredWorkspaceFiles.Observation, b: AnchoredWorkspaceFiles.Observation): Boolean =
        sameStat(a.attributes, b.attributes) && a.changed == b.changed && a.executable == b.executable

    private fun matches(file: File, expected: Version, context: CoroutineContext): Boolean {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        // Inventory caching never replaces the final destructive-write/delete check.
        val current = version(file.toPath(), context, allowReuse = false)
        return current.entry == expected.entry && sameStat(current.attributes, expected.attributes)
    }

    private fun version(path: Path, context: CoroutineContext, allowReuse: Boolean = true): Version {
        val fingerprint = fingerprints.read(path, context, allowReuse)
        return Version(WorkspaceSyncManifest.Entry(fingerprint.sha256, if (fingerprint.executable) 493 else 420),
            fingerprint.attributes)
    }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    private fun sameStat(a: BasicFileAttributes, b: BasicFileAttributes) = b.isRegularFile &&
        a.size() == b.size() && a.lastModifiedTime() == b.lastModifiedTime() && a.fileKey() == b.fileKey()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    companion object {
        suspend fun openRetained(
            source: File,
            directory: File,
            fingerprints: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache(),
        ): LocalAgentWorkspaceMirror = withContext(Dispatchers.IO) {
            val identity = WorkspaceDirectoryIdentity(directory)
            val bootstrap = LocalMirrorAuthorityBootstrap(source, directory)
            val store = MirrorMetadataAuthority(bootstrap.directory, source, directory)
            WorkspaceFileMutationLocks.withCaptureRoot(directory) { guard ->
                identity.requireCurrent()
                bootstrap.readLinkedExecutionState(guard, store)
                LocalAgentWorkspaceMirror(source, directory, fingerprints, store).also {
                    identity.requireCurrent()
                    it.directoryIdentity = identity
                }
            }
        }

        suspend fun prepareRetained(
            source: File,
            directory: File,
            fingerprints: WorkspaceFileFingerprintCache = WorkspaceFileFingerprintCache(),
        ): LocalAgentWorkspaceMirror {
            val store = LocalMirrorAuthorityBootstrap(source, directory).prepare()
            return LocalAgentWorkspaceMirror(source, directory, fingerprints, store).also { it.refresh() }
        }

        private const val BASELINE_HEADER = "DROIDE-LOCAL-BASELINE-1"
        private const val TYPED_BASELINE_HEADER = "DROIDE-LOCAL-BASELINE-2"
        private const val MAX_DIRECTORIES = 40_000
        private const val MAX_FILES = 20_000
        private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
        private const val BUFFER_SIZE = 64 * 1024
    }
}
