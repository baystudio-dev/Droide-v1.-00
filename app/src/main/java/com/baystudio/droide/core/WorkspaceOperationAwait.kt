package com.baystudio.droide.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Agent Stop owns its operation; UI navigation only stops observing retained workspace work. */
internal class AgentOwnedWorkspaceOperation : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentOwnedWorkspaceOperation>
}

internal suspend fun <T> awaitWorkspaceOperation(operation: Deferred<T>): T {
    val cancelWithCaller = currentCoroutineContext()[AgentOwnedWorkspaceOperation] != null
    try {
        return operation.await()
    } catch (cancelled: CancellationException) {
        if (cancelWithCaller) withContext(NonCancellable) { operation.cancelAndJoin() }
        throw cancelled
    }
}
