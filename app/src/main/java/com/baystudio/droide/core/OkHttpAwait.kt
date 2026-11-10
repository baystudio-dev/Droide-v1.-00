package com.baystudio.droide.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) { response.close() }
            else response.close()
        }
    })
}

/** Keep cancellation attached through body consumption, after awaitResponse has already resumed. */
internal suspend fun <T> Call.withCancellableResponse(block: suspend (Response) -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val call = this@withCancellableResponse
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                if (!finished.get()) call.cancel()
            }
        }
    }
    try {
        call.awaitResponse().use { response -> block(response) }
    } catch (failure: IOException) {
        // Closing a socket on Stop can throw IOException; preserve user cancellation, not an Agent
        // error.
        currentCoroutineContext().ensureActive()
        throw failure
    } finally {
        finished.set(true)
        cancellation.cancel()
    }
}
