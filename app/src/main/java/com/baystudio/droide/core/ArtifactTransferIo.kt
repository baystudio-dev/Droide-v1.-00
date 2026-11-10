package com.baystudio.droide.core

import okhttp3.Call
import okhttp3.Response

/** Share the response-body cancellation policy with generation: Stop is never a socket error/retry. */
internal suspend fun <T> Call.consumeCancellableResponse(block: suspend (Response) -> T): T =
    withCancellableResponse(block)
