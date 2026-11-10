package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/** Completion is registered before dispatch, including cancellation before the body ever runs. */
internal object ExportWorkerLifecycle {
    fun start(scope: CoroutineScope, context: CoroutineContext, completed: (Throwable?) -> Unit,
        body: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(context, start = CoroutineStart.LAZY, block = body)
        job.invokeOnCompletion(completed)
        job.start()
        return job
    }
}
