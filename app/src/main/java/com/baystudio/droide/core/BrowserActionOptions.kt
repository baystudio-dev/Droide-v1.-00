package com.baystudio.droide.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Invocation-local options; never shared with the next queued operation. */
internal data class BrowserActionOptions(
    val checked: Boolean? = null,
    val optionIndex: Int? = null,
    val keyStroke: String? = null,
    val endTarget: String? = null,
    val timeoutMs: Long = 4_000,
    val durationMs: Long = 350,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<BrowserActionOptions>
    init {
        require(timeoutMs in 100..15_000 && durationMs in 100..2_000) { "Browser action timing is out of range" }
        require(optionIndex == null || optionIndex in 0..10_000) { "Invalid select option index" }
        require(keyStroke == null || keyStroke.length in 1..80) { "Invalid browser key" }
        require(endTarget == null || endTarget.length in 1..20_000) { "Invalid drag destination" }
    }
}
