package com.baystudio.droide.core

import java.io.File
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Per-request enumeration limits. Text decoding/matching has its own, separate contract. */
internal data class DiscoveryLimits(
    val directoryProbes: Int = 50_000,
    val directoryOpens: Int = 4_096,
    val depth: Int = 20,
    val pathChars: Int = 4_096,
    val durationNanos: Long = 5_000_000_000L,
) {
    init {
        require(directoryProbes >= 0 && directoryOpens >= 0)
        require(depth >= 1 && pathChars >= 1 && durationNanos >= 0)
    }
}

/** Charges before operations that can read ahead; exhausted requests never probe for EOF. */
internal class DiscoveryBudget(
    val limits: DiscoveryLimits = DiscoveryLimits(),
    nanoTime: () -> Long = System::nanoTime,
    private val deadline: WorkspaceRequestDeadline = WorkspaceRequestDeadline(limits.durationNanos, nanoTime),
) {
    private val reasons = linkedSetOf<DiscoveryStopReason>()
    var directoryProbes: Int = 0
        private set
    var directoryOpenAttempts: Int = 0
        private set
    var scannedEntries: Int = 0
        private set
    val stopReasons: Set<DiscoveryStopReason> get() = reasons.toSet()
    val incomplete: Boolean get() = reasons.isNotEmpty()

    suspend fun checkpoint(): Boolean {
        currentCoroutineContext().ensureActive()
        if (deadline.expired()) {
            reasons += DiscoveryStopReason.DEADLINE
            return false
        }
        return true
    }

    suspend fun beforeOpen(): Boolean {
        if (!checkpoint()) return false
        if (directoryOpenAttempts >= limits.directoryOpens) {
            reasons += DiscoveryStopReason.DIRECTORY_LIMIT
            return false
        }
        directoryOpenAttempts++
        return true
    }

    suspend fun beforeProbe(): Boolean {
        if (!checkpoint()) return false
        if (directoryProbes >= limits.directoryProbes) {
            reasons += DiscoveryStopReason.PROBE_LIMIT
            return false
        }
        directoryProbes++
        return true
    }

    fun deliveredEntry() { scannedEntries++ }

    fun admitPath(relativePath: String): Boolean {
        if (relativePath.length <= limits.pathChars) return true
        reasons += DiscoveryStopReason.PATH_LIMIT
        return false
    }

    fun depthLimited() { reasons += DiscoveryStopReason.DEPTH_LIMIT }

    suspend fun requireCompleteDirectory(relativePath: String) {
        checkpoint()
        if (incomplete) throw DirectoryScanLimitException(
            relativePath, stopReasons, scannedEntries, directoryProbes, directoryOpenAttempts,
        )
    }
}

/** Only the stream opener is injectable; tests count actual helper operations without a fake filesystem. */
internal class WorkspaceDiscoveryTraversal(
    private val openDirectory: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
) {
    private data class Frame(val stream: DirectoryStream<Path>, val iterator: Iterator<Path>, val depth: Int)

    private fun openFrame(directory: File, depth: Int): Frame {
        val stream = openDirectory(directory)
        try {
            return Frame(stream, stream.iterator(), depth)
        } catch (failure: Throwable) {
            try { stream.close() } catch (close: Throwable) { failure.addSuppressed(close) }
            throw failure
        }
    }

    suspend fun scanDirectory(
        directory: File,
        budget: DiscoveryBudget,
        onEntry: suspend (File) -> Unit,
    ) {
        if (!budget.beforeOpen()) return
        val frame = openFrame(directory, 0)
        frame.stream.use {
            while (budget.beforeProbe()) {
                if (!frame.iterator.hasNext()) return
                val child = frame.iterator.next().toFile()
                budget.deliveredEntry()
                if (!budget.checkpoint()) return
                onEntry(child)
            }
        }
    }

    /** Root is depth zero, matching FileTreeWalk.maxDepth: depth one admits immediate files only. */
    suspend fun walk(
        root: File,
        budget: DiscoveryBudget,
        canDescend: (File) -> Boolean,
        shouldContinue: () -> Boolean = { true },
        onFile: suspend (File) -> Unit,
    ) {
        if (!shouldContinue() || !budget.checkpoint() || !canDescend(root)) return
        val rootPath = root.canonicalFile.toPath()
        val stack = ArrayDeque<Frame>()
        var failure: Throwable? = null
        try {
            if (!budget.beforeOpen()) return
            stack.addLast(openFrame(root, 0))
            while (stack.isNotEmpty() && shouldContinue()) {
                if (!budget.beforeProbe()) break
                val frame = stack.peekLast()
                if (!frame.iterator.hasNext()) {
                    stack.removeLast().stream.close()
                    continue
                }
                val child = frame.iterator.next().toFile()
                budget.deliveredEntry()
                if (!budget.checkpoint()) break
                if (PathSecurity.isSymbolicLink(child) || !PathSecurity.contains(root, child)) continue
                val relative = rootPath.relativize(child.canonicalFile.toPath()).joinToString("/")
                if (!budget.admitPath(relative)) continue
                if (child.isDirectory) {
                    if (!canDescend(child)) continue
                    val childDepth = frame.depth + 1
                    if (childDepth >= budget.limits.depth) {
                        budget.depthLimited()
                        continue
                    }
                    if (!budget.beforeOpen()) break
                    stack.addLast(openFrame(child, childDepth))
                } else if (child.isFile) {
                    if (!budget.checkpoint()) break
                    onFile(child)
                }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            var closeFailure: Throwable? = null
            while (stack.isNotEmpty()) {
                try { stack.removeLast().stream.close() }
                catch (close: Throwable) {
                    val prior = closeFailure
                    if (prior == null) closeFailure = close else prior.addSuppressed(close)
                }
            }
            closeFailure?.let { close ->
                val prior = failure
                if (prior != null) prior.addSuppressed(close) else throw close
            }
        }
    }
}
