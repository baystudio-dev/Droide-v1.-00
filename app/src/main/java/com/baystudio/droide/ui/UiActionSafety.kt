package com.baystudio.droide.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun <T> runUiCatching(onError: (Throwable) -> Unit, block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onError(failure)
    null
} catch (failure: LinkageError) {
    onError(failure)
    null
}

 
fun CoroutineScope.launchUiCatching(
    onError: suspend (Throwable) -> Unit = {},
    block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onError(failure)
    } catch (failure: LinkageError) {
        onError(failure)
    }
}
