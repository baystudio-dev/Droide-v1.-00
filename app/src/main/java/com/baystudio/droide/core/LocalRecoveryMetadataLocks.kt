package com.baystudio.droide.core

import java.io.Closeable

/** Reserve before opening a metadata lock inode: closing a losing contender's descriptor can
 * silently release the winning POSIX process lock. Shared by deletion and recovery. */
internal object LocalRecoveryMetadataLocks {
    private val active = mutableSetOf<String>()
    fun claim(@Suppress("UNUSED_PARAMETER") rootIdentity: String, inode: String): Closeable = synchronized(active) {
        val key = inode
        check(active.add(key)) { "Folder recovery metadata is busy; retry after the current operation finishes" }
        object : Closeable {
            private var closed = false
            override fun close() = synchronized(active) {
                if (!closed) { closed = true; active.remove(key) }
                Unit
            }
        }
    }
}
