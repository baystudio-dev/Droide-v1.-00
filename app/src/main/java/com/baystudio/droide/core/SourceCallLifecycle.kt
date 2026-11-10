package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject

/** Settlement lifecycle only. This interface conveys no execution/permission authority. */
internal interface SourceCallLifecycle {
    data class Outcome(val output: String, val rejected: Boolean)
    val settledResult: JsonObject?
    fun settleSkipped(output: String): JsonObject
    suspend fun checkpointInterrupted(): JsonObject?
}
