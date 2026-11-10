package com.baystudio.droide.core

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A process is a writer for its whole owned lifetime, not just while it is being spawned. */

internal object WorkspaceProcessWriteAdmission {
    private data class Ticket(val root: String, val receipt: AutoCloseable)
    private val tickets = IdentityHashMap<HostedStdioProcess, Ticket>()
    private var ticketVersion: Any = Any()
    private val unconfirmedFactories = mutableSetOf<String>()
    private var factoryVersion: Any = Any()
    class Observation internal constructor(val registeredHandles: Int, unknownFactoryRoots: List<String>,
                                          private val validateState: () -> Unit) {
        val unknownFactoryRoots: List<String> = java.util.Collections.unmodifiableList(unknownFactoryRoots.toList())
        fun validate() = validateState()
    }

    /** Root-scoped participating tickets. Does not inspect/call the process handles. */
    fun observe(root: File): Observation {
        val rootKey = root.canonicalPath
        val held = synchronized(tickets) { ticketVersion to tickets.values.count { factoryRootsOverlap(it.root, rootKey) } }
        val unknown = synchronized(unconfirmedFactories) {
            factoryVersion to unconfirmedFactories.filter { factoryRootsOverlap(it, rootKey) }.sorted()
        }
        return Observation(held.second, unknown.second) {
            synchronized(tickets) { check(ticketVersion === held.first) { "Process tickets changed during review" } }
            synchronized(unconfirmedFactories) { check(factoryVersion === unknown.first) { "Process factory custody changed during review" } }
        }.also { it.validate() }
    }

    internal fun factoryRootsOverlap(first: String, second: String): Boolean {
        val a = File(first).toPath(); val b = File(second).toPath()
        return a.startsWith(b) || b.startsWith(a)
    }

    private fun requireFactoryCustody(root: String) = synchronized(unconfirmedFactories) {
        check(unconfirmedFactories.none { factoryRootsOverlap(it, root) }) {
            "Earlier process creation has unconfirmed child custody; another workspace process cannot be launched"
        }
    }

    /** beforeFactory is validation-only: it MUST NOT start a process or call a process-capable
     * callback. Only factory may create native child resources. */
    fun <T : HostedStdioProcess> create(
        root: File,
        custody: WorkspaceProcessCustody? = null,
        beforeFactory: () -> Unit = {},
        factory: () -> T,
    ): T {
        val rootKey = root.canonicalPath
        requireFactoryCustody(rootKey)
        val memory = ExportWriterAdmission.beginWriter(root, "Running or unconfirmed workspace process")
        val durable = try { custody?.acquire(root) } catch (failure: Throwable) {
            memory.close()
            throw failure
        }
        val ticket = object : AutoCloseable {
            private var confirmed = false
            @Synchronized override fun close() {
                if (confirmed) return
                // Never reopen the memory fence before persistence acknowledges cleanup.
                durable?.close()
                memory.close()
                confirmed = true
            }
        }
        var published = false
        var factoryInvoked = false
        try {
            val invokeFactory = {
                // Linearize permission to enter the factory against unknown-owner registration, after the
                // durable entry barrier and revalidation.
                synchronized(unconfirmedFactories) {
                    requireFactoryCustody(rootKey)
                    factoryInvoked = true
                }
                factory()
            }
            val process = if (durable != null) durable.withFactory(beforeFactory, invokeFactory)
                else { beforeFactory(); invokeFactory() }
            synchronized(tickets) {
                check(!tickets.containsKey(process)) { "Process already has workspace writer ownership" }
                ticketVersion = Any()
                tickets[process] = Ticket(rootKey, ticket)
                published = true
            }

            ExitHostedProcessRegistry.retain(process)
            return process
        } catch (failure: Throwable) {
            if (!published) {
                if (factoryInvoked) {
                    // The callback may have created a process before failing. Keep both fences and report unknown
                    // custody at Exit; do not generate a durable exact-cleanup proof from this exception.
                    synchronized(unconfirmedFactories) { factoryVersion = Any(); unconfirmedFactories.add(rootKey) }
                    ExitHostedProcessRegistry.retain(UnconfirmedProcessCreation(ticket, failure))
                } else try { ticket.close() } catch (cleanup: Throwable) {
                    // The process-capable callback was never entered. Its exact no-start receipt may retry
                    // durability without inventing cleanup.
                    ExitHostedProcessRegistry.retain(UnstartedProcessCleanup(ticket))
                    if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    /** Call only after awaitExit succeeded and the exact handle no longer reports live. */
    fun confirmCompletion(process: HostedStdioProcess) {
        val ticket = synchronized(tickets) { tickets[process] } ?: return
        check(!process.isAlive) { "Workspace process still reports live" }
        ticket.receipt.close()
        synchronized(tickets) { if (tickets[process] === ticket) { ticketVersion = Any(); tickets.remove(process) } }
    }

    /** Retains the ticket even when there is no trustworthy process handle to reap. No retry can turn
     * this uncertainty into successful cleanup by itself. */
    private class UnconfirmedProcessCreation(
        @Suppress("unused") private val receipt: AutoCloseable,
        private val failure: Throwable,
    ) : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive = true // conservative unknown, never a negative liveness proof
        override val exitCode: Int? = null
        override fun close() = Unit
        override suspend fun awaitExit(): Int = throw IllegalStateException(
            "Process factory failed after creation began; exact child cleanup is unconfirmed and workspace custody was preserved", failure)
    }

    private class UnstartedProcessCleanup(private val receipt: AutoCloseable) : HostedStdioProcess {
        override val stdin = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(byteArrayOf())
        override val stderr = ByteArrayInputStream(byteArrayOf())
        override val isAlive = false
        override val exitCode = 0
        override fun close() = Unit
        override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { receipt.close(); 0 }
    }
}

/** A bounded capture's receipt includes its pipe workers and lease cleanup, not just waitFor.
 * Stop cancels the capture child; its existing NonCancellable finally owns reaping and IO. */
internal class CapturedWorkspaceProcess(
    val process: Process,
    private val capture: Job,
) : HostedStdioProcess {
    private val completion = CompletableDeferred<Unit>()
    override val stdin get() = process.outputStream
    override val stdout get() = process.inputStream
    override val stderr get() = process.errorStream
    override val isAlive get() = process.isAlive
    override val exitCode get() = runCatching { process.exitValue() }.getOrNull()
    override fun close() { if (!completion.isCompleted) capture.cancel(CancellationException("Workspace process cleanup requested")) }
    override suspend fun awaitExit(): Int {
        completion.await()
        check(!process.isAlive) { "Captured process cleanup is unconfirmed" }
        return process.exitValue()
    }

    fun completed(failure: Throwable?) {
        val error = failure ?: runCatching {
            check(!process.isAlive) { "Captured process cleanup is unconfirmed" }
        }.exceptionOrNull()
        if (error != null) completion.completeExceptionally(error)
        else {
            completion.complete(Unit)
            WorkspaceProcessWriteAdmission.confirmCompletion(this)
        }
    }
}
