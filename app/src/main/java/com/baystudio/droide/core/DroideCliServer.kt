package com.baystudio.droide.core

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job


object DroideCliServer {
    private var active: Session? = null
    private val ownedSessions = ConcurrentHashMap.newKeySet<Session>()
    private val endpointPublication = CliEndpointPublication<Session>(
        isCurrent = { active === it && !it.isClosed && ApplicationWorkAdmission.isOpen },
        replace = { it.endpoint.publishPrepared() },
        remove = { it.withdrawEndpointFile() },
    )

    fun start(context: Context, authority: UnifiedPackageAuthority, workspaceId: String) {
        ApplicationWorkAdmission.requireOpen()
        require(workspaceId.matches(Regex("[A-Za-z0-9._-]{1,160}"))) { "Invalid CLI workspace identity" }
        var previous: Session? = null
        var next: Session? = null
        var accepted = false
        try {
            synchronized(endpointPublication.monitor) {
                if (active?.authority === authority && active?.workspaceId == workspaceId &&
                    active?.isClosed == false) return
                ApplicationWorkAdmission.requireOpen()
                // Construction is inert and may fail without revoking the old session.
                next = Session(context.applicationContext, authority, workspaceId).also { ownedSessions.add(it) }
                previous = active
                previous?.revoke()
                // Also retries a single retained owner after an earlier failed stop. Failure
                // retains that owner and prevents publication in a second workspace.
                endpointPublication.withdrawPrevious()
                active = next
                accepted = true
            }
        } finally {
            // Socket shutdown/cancellation must not run under the publication monitor.
            previous?.closeResources()
            if (!accepted) next?.closeResources()
        }
        // Assign active before launching: even a fast worker must see its own generation.
        next?.start()
    }

    fun stop(authority: UnifiedPackageAuthority? = null) {
        var retiring: Session? = null
        try {
            synchronized(endpointPublication.monitor) {
                val session = active ?: return
                if (authority != null && session.authority !== authority) return
                retiring = session
                session.revoke()
                active = null
                endpointPublication.withdraw(session)
            }
        } catch (failure: Exception) {
            Log.w("DroideCli", "Internal CLI endpoint could not be withdrawn", failure)
        } finally {
            retiring?.closeResources()
        }
    }


    internal suspend fun stopAndAwaitForExit(authority: UnifiedPackageAuthority? = null) {
        check(!ApplicationWorkAdmission.isOpen)
        stop(authority)
        val owned = ownedSessions.filter { authority == null || it.authority === authority }
        for (session in owned) {
            session.revoke()
            synchronized(endpointPublication.monitor) { endpointPublication.withdraw(session) }
            session.closeAndJoinForExit()
            ownedSessions.remove(session)
        }
        check(ownedSessions.none { authority == null || it.authority === authority }) { "CLI server retirement is still pending" }
    }

    private class Session(
        private val appContext: Context,
        val authority: UnifiedPackageAuthority,
        val workspaceId: String,
    ) {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val closed = AtomicBoolean(false)
        private val resourcesClosed = AtomicBoolean(false)
        @Volatile private var closeFailure: Throwable? = null
        @Volatile private var endpointWithdrawalFailure: Throwable? = null
        private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
        val endpoint = CliEndpointFile(appContext.filesDir, workspaceId)
        val isClosed: Boolean get() = closed.get()
        @Volatile private var server: LocalServerSocket? = null

        fun start() {
            scope.launch {
                try {
                    val startContext = currentCoroutineContext()
                    DroideCliClientInstaller.ensureInstalled(appContext) { startContext.ensureActive() }
                    startContext.ensureActive()
                    val socketName = "com.baystudio.droide.cli.${Process.myPid()}.${UUID.randomUUID()}"
                    val listener = LocalServerSocket(socketName)
                    server = listener
                    endpoint.prepare(socketName)
                    if (!endpointPublication.publish(this@Session)) return@launch
                    while (isActive && !closed.get()) {
                        val client = listener.accept()
                        val sameUid = runCatching { client.peerCredentials.uid == Process.myUid() }.getOrDefault(false)
                        if (!sameUid || clients.size >= MAX_CLIENTS || closed.get()) {
                            closeClient(client)
                            continue
                        }
                        clients.add(client)
                        // stop() can win after the earlier admission check but before
                        // publication. A canceled scope never enters serve's finally.
                        if (closed.get()) {
                            clients.remove(client)
                            closeClient(client)
                            continue
                        }
                        launch { serve(client) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!closed.get()) Log.w("DroideCli", "Internal CLI endpoint could not start", failure)
                } finally {
                    // A's delayed finally must never remove B's endpoint, even on the
                    // same workspace. Withdrawal and final replacement share one monitor.
                    runCatching { endpointPublication.withdraw(this@Session) }.onFailure {
                        Log.w("DroideCli", "Internal CLI endpoint could not be withdrawn", it)
                    }
                    runCatching { endpoint.discardStaging() }
                    // The listener may have been created after closeResources saw null.
                    // Preserve a failed final close until the Exit retry confirms it.
                    runCatching { server?.close() }.onFailure { closeFailure = it }
                }
            }
        }

        private suspend fun serve(client: LocalSocket) {
            try {
                client.soTimeout = REQUEST_TIMEOUT_MS
                val request = DroideCliWireProtocol.readRequestEnvelope(client.inputStream)
                client.soTimeout = 0 // Package transactions may legitimately take longer than request framing.
                CliConnectionLifetime.serve(
                    operation = {
                        val action: suspend () -> DroideCliExecutionResult = {
                            DroideCliTool(authority, workspaceId).executeResult(request.arguments)
                        }
                        if (request.streamProgress) CliProgressRelay.execute(action) { text ->
                            DroideCliWireProtocol.writeProgress(client.outputStream, text)
                        } else action()
                    },
                    readPeer = { client.inputStream.read() },
                    closePeer = { closeClient(client) },
                    writeResponse = { completed ->
                        DroideCliWireProtocol.writeResponse(client.outputStream, completed)
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Malformed/disconnected clients are rejected without exposing stack traces over IPC.
            } finally {
                clients.remove(client)
                closeClient(client)
            }
        }

        /** Called under the same monitor as selection/publication, before resource cleanup. */
        fun revoke() {
            closed.set(true)
        }

        /** Called by the publication slot under its monitor; failed ownership stays retained. */
        fun withdrawEndpointFile() {
            try {
                endpoint.withdraw()
                endpointWithdrawalFailure = null
            } catch (failure: Throwable) {
                endpointWithdrawalFailure = failure
                throw failure
            }
        }

        fun closeResources() {
            closed.set(true)
            if (!resourcesClosed.compareAndSet(false, true)) return
            runCatching { server?.close() }.onFailure { closeFailure = it }
            clients.forEach(::closeClient)
            scope.cancel()
            scope.coroutineContext[Job]?.invokeOnCompletion {
                // Socket completion is not proof that discovery-file withdrawal succeeded.
                if (endpointWithdrawalFailure == null && closeFailure == null && clients.isEmpty()) {
                    ownedSessions.remove(this)
                }
            }
        }

        suspend fun closeAndJoinForExit() {
            closeResources()
            scope.coroutineContext[Job]?.join()
            // Retry only these exact retired sockets, never the current server generation.
            var failure: Throwable? = null
            runCatching { server?.close() }.onFailure { failure = it }
            clients.toList().forEach { client ->
                runCatching { client.close() }.onSuccess { clients.remove(client) }.onFailure { failure = it }
            }
            closeFailure = failure
            check(failure == null && clients.isEmpty()) { "Internal CLI socket cleanup could not be confirmed" }
        }

        private fun closeClient(client: LocalSocket) {
            // Closing only a Java socket wrapper may leave another thread in read().
            // Shutdown both directions before close for active framing/transaction I/O.
            runCatching { client.shutdownInput() }
            runCatching { client.shutdownOutput() }
            runCatching { client.close() }.onSuccess { clients.remove(client) }.onFailure { closeFailure = it; clients.add(client) }
        }
    }

    private const val MAX_CLIENTS = 8
    private const val REQUEST_TIMEOUT_MS = 10_000
}
