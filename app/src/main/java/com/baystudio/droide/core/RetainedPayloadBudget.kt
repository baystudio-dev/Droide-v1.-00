package com.baystudio.droide.core

/** Payload reservation independent of map membership; hold until its owning job completes. */
internal class RetainedPayloadBudget(private val maxChars: Long, private val maxEntries: Int) {
    private val lock = Any()
    private var retained = 0L
    private var count = 0
    init { require(maxChars > 0 && maxEntries > 0) }
    internal val retainedChars: Long get() = synchronized(lock) { retained }
    internal val retainedCount: Int get() = synchronized(lock) { count }

    inner class Lease internal constructor(private val chars: Long) {
        private var released = false
        fun release() = synchronized(lock) {
            if (!released) { released = true; retained -= chars; count-- }
        }
    }

    fun tryAcquire(chars: Long): Lease? {
        require(chars >= 0)
        return synchronized(lock) {
            if (count >= maxEntries || chars > maxChars - retained) null
            else { retained += chars; count++; Lease(chars) }
        }
    }
}
