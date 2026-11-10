package com.baystudio.droide.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** Observation only. Failed reads do not authorize use of stale settings or change stored data. */
internal data class StoredPreferenceRead<T>(val value: T, val ready: Boolean, val failed: Boolean)
internal fun <T> observeStoredPreferences(lastKnown: T, source: () -> Flow<T>): Flow<StoredPreferenceRead<T>> = flow {
    var latest = lastKnown
    emitAll(flow { emitAll(source()) }.map { value ->
        latest = value
        StoredPreferenceRead(value, ready = true, failed = false)
    }.catch { failure ->
        when (failure) {
            is CancellationException -> throw failure
            is IOException, is ClassCastException -> emit(StoredPreferenceRead(latest, ready = false, failed = true))
            else -> throw failure
        }
    })
}
