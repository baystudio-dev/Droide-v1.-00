package com.baystudio.droide.core

import java.io.File
import java.nio.file.FileSystemException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class RetainedWorkPurpose { TEMPORARY_WRITE, DIRECTORY_DELETE }

internal enum class RetainedWorkKind { PRESENT, FOREIGN, UNVERIFIABLE }

internal data class RetainedWorkCandidate(
    val path: String,
    val kind: RetainedWorkKind,
    val bytes: Long?,
    val detail: String? = null,
    val purpose: RetainedWorkPurpose = RetainedWorkPurpose.TEMPORARY_WRITE,
    val directoryDeletion: LocalDirectoryDeleteCustody.Inspection? = null,
)

internal data class RetainedWorkNotice(
    val target: String,
    val candidate: RetainedWorkCandidate,
    val historicalWriteReturned: Boolean,
    val purpose: RetainedWorkPurpose = RetainedWorkPurpose.TEMPORARY_WRITE,
)

internal data class RetainedWorkSnapshot(
    val token: String,
    val notices: List<RetainedWorkNotice>,
    val omitted: Long,
)

internal data class RetainedWorkInventory(
    val directory: String,
    val candidates: List<RetainedWorkCandidate>,
    val complete: Boolean,
    val problem: String?,
)

internal object RetainedWorkPolicy {
    const val MAX_ENTRIES = 32
    const val MAX_SCAN = 20_000
    const val MAX_INSPECT = 64
    private const val HEAD_NAMES = 8
    private val names = Regex("\\.droide-tmp-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val locks = Array(32) { Any() }

    fun isCandidateName(name: String): Boolean = names.matches(name)

    fun <T> creating(parent: File, action: (AnchoredWorkspaceFiles) -> T): T {
        val files = try { AnchoredWorkspaceFiles(parent) }
        catch (failure: Exception) { throw admissionFailure(parent, failure) }
        return files.use {
            val key = try { it.directoryKey() }
            catch (failure: Exception) { throw admissionFailure(parent, failure) }
            synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
                var scanned = 0
                var retained = 0
                val head = ArrayList<String>(HEAD_NAMES)
                try {
                    check(it.directoryKey() == key) { "Destination directory changed" }
                    it.forEachChild { name ->
                        if (++scanned > MAX_SCAN) throw FileSystemException(parent.path, null,
                            "Retained-work admission exceeds the $MAX_SCAN-entry directory budget; existing files were preserved. " +
                                "First candidate names: ${head.joinToString()}")
                        if (isCandidateName(name)) {
                            retained++
                            if (head.size < HEAD_NAMES) head += name
                            if (retained >= MAX_ENTRIES) throw FileSystemException(parent.path, null,
                                "At least $MAX_ENTRIES retained-work candidates need inspection before another stage is created. " +
                                    "Existing files were preserved. First names: ${head.joinToString()}")
                        }
                    }
                    check(it.directoryKey() == key) { "Destination directory changed" }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: FileSystemException) { throw failure }
                catch (failure: Exception) { throw admissionFailure(parent, failure) }
                action(it)
            }
        }
    }

    suspend fun inspect(root: File, directory: String): RetainedWorkInventory = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val candidates = ArrayList<RetainedWorkCandidate>()
        var problem: String? = null
        fun report(detail: String) { if (problem == null) problem = detail.take(300) }
        context.ensureActive()
        try {
            val owner = WorkspaceDirectoryIdentity(root)
            require(ExplorerFilePolicy.requireUnaliasedProjectPath(root, directory) == directory) {
                "Inspect an unchanged project directory without aliases"
            }
            AnchoredWorkspaceFiles(root).use { workspace ->
                val rootKey = workspace.directoryKey()
                val directoryKey = workspace.directoryKey(directory)
                workspace.openDirectory(directory).use { files ->
                    check(files.directoryKey() == directoryKey) { "Retained-work directory changed" }
                    var scanned = 0
                    try {
                        files.forEachChild { name ->
                            context.ensureActive()
                            check(++scanned <= MAX_SCAN) { "Inspection exceeds the $MAX_SCAN-entry directory budget; results are partial" }
                            if (isCandidateName(name)) {
                                check(candidates.size < MAX_INSPECT) { "More than $MAX_INSPECT retained-work candidates; results are partial" }
                                val path = if (directory.isEmpty()) name else "$directory/$name"
                                val candidate = observe(files, name, path)
                                candidates += candidate
                                if (candidate.kind == RetainedWorkKind.UNVERIFIABLE)
                                    report(candidate.detail ?: "A retained-work candidate could not be verified")
                            }
                        }
                    } catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { report(failure.message ?: "Retained-work listing could not be completed") }
                    context.ensureActive()
                    owner.requireCurrent()
                    check(workspace.directoryKey() == rootKey && workspace.directoryKey(directory) == directoryKey &&
                        files.directoryKey() == directoryKey &&
                        ExplorerFilePolicy.requireUnaliasedProjectPath(root, directory) == directory) {
                        "Project or retained-work directory changed during inspection"
                    }
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { report(failure.message ?: "Retained-work inspection is unavailable") }
        catch (_: LinkageError) { report("Native retained-work inspection is unavailable; existing files were preserved") }
        context.ensureActive()
        RetainedWorkInventory(directory, candidates.toList(), problem == null, problem)
    }

    private fun observe(files: AnchoredWorkspaceFiles, name: String, path: String): RetainedWorkCandidate = try {
        val kind = files.kind(name)
        when (kind) {
            AnchoredWorkspaceFiles.Kind.FILE -> files.openRead(name).use { opened ->
                val before = opened.observe()
                val after = files.openRead(name).use { it.observe() }
                check(before.attributes.fileKey() == after.attributes.fileKey() &&
                    before.attributes.size() == after.attributes.size() &&
                    before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() &&
                    before.changed == after.changed && before.executable == after.executable) {
                    "Retained-work candidate changed during inspection"
                }
                RetainedWorkCandidate(path, RetainedWorkKind.PRESENT, before.attributes.size(),
                    "Current regular file; ownership and content are unverified")
            }
            AnchoredWorkspaceFiles.Kind.ABSENT -> RetainedWorkCandidate(path, RetainedWorkKind.UNVERIFIABLE, null,
                "Retained-work candidate disappeared during inspection")
            else -> {
                check(files.kind(name) == kind) { "Retained-work candidate type changed during inspection" }
                RetainedWorkCandidate(path, RetainedWorkKind.FOREIGN, null,
                    "Reserved stage name contains a ${kind.name.lowercase()}; it was not followed or changed")
            }
        }
    } catch (failure: CancellationException) { throw failure }
    catch (failure: Exception) { RetainedWorkCandidate(path, RetainedWorkKind.UNVERIFIABLE, null,
        (failure.message ?: "Retained-work candidate is unreadable").take(300)) }
    catch (_: LinkageError) { RetainedWorkCandidate(path, RetainedWorkKind.UNVERIFIABLE, null,
        "Native retained-work observation is unavailable") }

    private fun admissionFailure(parent: File, cause: Exception) = FileSystemException(parent.path, null,
        "Cannot verify retained-work admission; existing files were preserved. ${cause.message?.take(300).orEmpty()}")
        .also { it.initCause(cause) }
}
