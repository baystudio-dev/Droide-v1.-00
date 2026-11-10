package com.baystudio.droide.core

import java.io.File
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Persist ownership before a participating process can be created. */

internal abstract class WorkspaceProcessCustody : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkspaceProcessCustody> {
        val runtimeId: String = UUID.randomUUID().toString()
    }
    abstract fun acquire(root: File): Receipt

    /** One local capability, not a reusable launch permit. */

    abstract class Receipt : AutoCloseable {
        private var consumed = false
        private var closing = false
        private var closed = false
        private var factoryRunning = false

        @Synchronized fun <T> withFactory(validate: () -> Unit, factory: () -> T): T {
            check(!consumed && !closing) { "Process launch permission was already consumed or cancelled" }
            consumed = true
            validate()
            check(!closing) { "Process launch was cancelled during validation" }
            enterFactory()
            check(!closing) { "Process launch was cancelled during durable entry" }
            // The durable entry transaction can block. Recheck cancellation, epoch and directory identity
            // before entering any process-capable callback.
            validate()
            check(!closing) { "Process launch was cancelled before factory invocation" }
            factoryRunning = true
            try { return factory() } finally { factoryRunning = false }
        }

        @Synchronized final override fun close() {
            if (closed) return
            check(!factoryRunning) { "Cannot confirm cleanup while the process factory is running" }
            closing = true
            consumed = true
            confirmCleanupOrAbort()
            closed = true
        }

        protected abstract fun enterFactory()
        protected abstract fun confirmCleanupOrAbort()
    }
}
