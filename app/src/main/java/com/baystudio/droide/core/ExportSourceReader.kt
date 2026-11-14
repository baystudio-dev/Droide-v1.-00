package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Android source opens are O_NOFOLLOW|O_NONBLOCK, then fstat-bound before any byte read. */
internal object ExportSourceReader {
    fun <T> read(path:Path, device:Long, inode:Long, size:Long, action:(InputStream)->T):T {
        if(System.getProperty("java.vm.name") == "Dalvik") {
            val fd=Os.open(path.toString(),OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC,0)
            // Android FileInputStream(FileDescriptor) borrows rather than owns this
            // descriptor. Closing the stream alone leaks the fd returned by Os.open.
            return withExportOwnedDescriptor(
                openInput = { FileInputStream(fd) },
                closeDescriptor = { if (fd.valid()) Os.close(fd) },
            ) { input ->
                val before=Os.fstat(fd)
                if(!OsConstants.S_ISREG(before.st_mode) || before.st_dev!=device || before.st_ino!=inode || before.st_size!=size) throw ExportFailure("SourceChanged")
                val result=action(input)
                val after=Os.fstat(fd)
                if(before.st_dev!=after.st_dev || before.st_ino!=after.st_ino || before.st_size!=after.st_size || before.st_mtim!=after.st_mtim || before.st_ctim!=after.st_ctim || before.st_mode!=after.st_mode || before.st_uid!=after.st_uid || before.st_gid!=after.st_gid) throw ExportFailure("SourceChanged")
                result
            }
        }
        // Host fixture implementation; Android never takes this branch.
        return Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use { action(Channels.newInputStream(it)) }
    }
}

/** The descriptor owner survives stream-construction, read and stream-close failures. */
internal fun <T> withExportOwnedDescriptor(
    openInput: () -> InputStream,
    closeDescriptor: () -> Unit,
    action: (InputStream) -> T,
): T {
    var primary: Throwable? = null
    try {
        return openInput().use(action)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        try {
            closeDescriptor()
        } catch (closeFailure: Throwable) {
            val failure = primary
            if (failure == null) throw closeFailure
            if (failure !== closeFailure) failure.addSuppressed(closeFailure)
        }
    }
}
