package com.baystudio.droide

import com.baystudio.droide.core.NativeFilePublication

/** Host subprocess probe: exit0 means descriptor lease busy, exit7 means acquired. */
object ExportLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        val file = java.io.File(args.single())
        val root = NativeFilePublication.openDirectoryRoot(file.parentFile.absolutePath.toByteArray())
        check(root >= 0)
        var fd = -1
        try {
            fd = NativeFilePublication.openOrdinaryRelative(root, file.name.toByteArray())
            check(fd >= 0)
            val error = NativeFilePublication.exportDescriptorLock(fd, true)
            if (error == 11) return // Linux EWOULDBLOCK/EAGAIN.
            check(error == 0) { "Lock failed: $error" }
            check(NativeFilePublication.exportDescriptorLock(fd, false) == 0)
        } finally {
            if (fd >= 0) NativeFilePublication.closeDescriptor(fd)
            NativeFilePublication.closeDescriptor(root)
        }
        kotlin.system.exitProcess(7)
    }
}
