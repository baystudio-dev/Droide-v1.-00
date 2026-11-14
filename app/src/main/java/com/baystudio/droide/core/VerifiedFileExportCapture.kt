package com.baystudio.droide.core

import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** Only the identity-bound regular-file path opts into concurrent capture. */
internal interface VerifiedFileExportCapturePermit : ExportCapturePermit {
    val selectedFileIdentity: String
    val verifySingleFile: Boolean
}

/** No atime: reading our source legitimately changes it. ctime must NOT be omitted or reduced
 * to seconds; an in-place writer can restore mtime and keep the same length and inode. */
internal data class ExportFileStamp(
    val device: Long,
    val inode: Long,
    val size: Long,
    val mode: Int,
    val uid: Long,
    val gid: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
) {
    val identity: String get() = "$device:$inode:false:false:true"

    companion object {
        fun read(path: Path): ExportFileStamp {
            if (System.getProperty("java.vm.name") == "Dalvik") {
                val s = Os.lstat(path.toString())
                if (!OsConstants.S_ISREG(s.st_mode)) throw ExportFailure("SourceChanged")
                return ExportFileStamp(s.st_dev, s.st_ino, s.st_size, s.st_mode, s.st_uid.toLong(), s.st_gid.toLong(),
                    s.st_mtim.tv_sec, s.st_mtim.tv_nsec, s.st_ctim.tv_sec, s.st_ctim.tv_nsec)
            }
            // Host fixture path. Unsupported precision fails closed; no coarse-time fallback.
            val s = Files.readAttributes(path, "unix:dev,ino,size,mode,uid,gid,lastModifiedTime,ctime", NOFOLLOW_LINKS)
            fun number(name: String) = (s.getValue(name) as Number).toLong()
            val mode = number("mode").toInt()
            if (mode and 0xf000 != 0x8000) throw ExportFailure("SourceChanged")
            fun seconds(name: String) = (s.getValue(name) as FileTime).toInstant().epochSecond
            fun nanos(name: String) = (s.getValue(name) as FileTime).toInstant().nano.toLong()
            return ExportFileStamp(number("dev"), number("ino"), number("size"), mode, number("uid"), number("gid"),
                seconds("lastModifiedTime"), nanos("lastModifiedTime"), seconds("ctime"), nanos("ctime"))
        }
    }
}
