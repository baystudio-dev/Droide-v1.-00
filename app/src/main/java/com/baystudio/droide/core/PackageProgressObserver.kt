package com.baystudio.droide.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Request-scoped, nonblocking observations; never installation or completion authority. */
internal data class PackageProgressSnapshot(
    val stage: String,
    val message: String,
    val log: String = "",
)

internal class PackageProgressObserver(
    private val offer: (PackageProgressSnapshot) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PackageProgressObserver>
    fun publish(snapshot: PackageProgressSnapshot) = offer(snapshot)
}
