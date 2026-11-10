package com.baystudio.droide.core

import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants

/** Sequential bounded-buffer transfer from sealed staging only. No source access after seal. */
class ExportEngine {
    fun transfer(capture:ExportCapturedSource, format:ExportFormat, destination:ExportDestination,
        control:ExportControl=ExportControl()):ExportVerification {
        control.checkCancelled()
        if(format==ExportFormat.RAW_FILE && (capture.entries!=1L || capture.rootType!=ExportEntryType.FILE)) throw ExportFailure("RawFileRequiresRegularFile")
        // Metadata-only manifest pass, not a source traversal or content/hash pass.
        if(format==ExportFormat.TREE) ExportManifest.each(capture.manifest,capture.manifestDigest) { if(it.type==ExportEntryType.SYMLINK) throw ExportFailure("TreeCannotRepresentSymlinkUseTar",it.path) }
        return if(format==ExportFormat.TAR) tar(capture,destination,control) else files(capture,format,destination,control)
    }
    private fun files(c:ExportCapturedSource,format:ExportFormat,d:ExportDestination,control:ExportControl):ExportVerification {
        var count=0L; var bytes=0L
        ExportManifest.each(c.manifest,c.manifestDigest) { entry ->
            control.checkCancelled(); control.onProgress(ExportProgress(ExportPhase.WRITING,count,bytes))
            if(entry.type==ExportEntryType.DIRECTORY) d.createDirectory(entry.path)
            else {
                if(entry.type!=ExportEntryType.FILE) throw ExportFailure("UnsupportedEntry",entry.path)
                val output=d.createFile(if(format==ExportFormat.RAW_FILE) "" else entry.path)
                val sent=output.openWrite().use { sink -> FileInputStream(c.data(entry.dataId)).use { input ->
                    ExportStreams.copy(input,sink,control) { n -> bytes=Math.addExact(bytes,n); control.onProgress(ExportProgress(ExportPhase.WRITING,count,bytes)) }
                } }
                requireMatch(sent,entry.size to entry.digest,"StagingHashMismatch",entry.path)
                control.checkCancelled(); control.onProgress(ExportProgress(ExportPhase.VERIFYING,count,bytes))
                val received=readback(output,control)
                requireMatch(received,sent,"HashMismatch",entry.path)
            }
            count=Math.addExact(count,1)
        }
        return completed(c,count,bytes,control)
    }
    private fun tar(c:ExportCapturedSource,d:ExportDestination,control:ExportControl):ExportVerification {
        val output=d.createFile(""); var count=0L; var sourceBytes=0L
        val digest=MessageDigest.getInstance("SHA-256")
        var archiveBytes=0L
        // Disk index preserves only in-scope hardlinks without O(entries) heap maps.
        val index=File(c.directory,"tar-links-${java.util.UUID.randomUUID()}")
        if(!index.mkdir()) throw ExportFailure("StagingIndexUnavailable")
        try {
            val sink=output.openWrite()
            val counted=object:FilterOutputStream(sink) {
                override fun write(b:Int) { out.write(b); archiveBytes=Math.addExact(archiveBytes,1) }
                override fun write(b:ByteArray,off:Int,len:Int) { out.write(b,off,len); archiveBytes=Math.addExact(archiveBytes,len.toLong()) }
            }
            TarArchiveOutputStream(DigestOutputStream(counted,digest),"UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                tar.setAddPaxHeadersForNonAsciiNames(true)
                ExportManifest.each(c.manifest,c.manifestDigest) { e ->
                    control.checkCancelled(); control.onProgress(ExportProgress(ExportPhase.WRITING,count,sourceBytes))
                    val name=if(e.path.isEmpty()) "." else e.path
                    val key=File(index,MessageDigest.getInstance("SHA-256").digest(e.inode.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) })
                    val existing=e.type==ExportEntryType.FILE && key.exists()
                    val entry=when {
                        e.type==ExportEntryType.DIRECTORY -> TarArchiveEntry("$name/")
                        e.type==ExportEntryType.SYMLINK -> TarArchiveEntry(name,TarConstants.LF_SYMLINK).also { it.linkName=e.link }
                        existing -> TarArchiveEntry(name,TarConstants.LF_LINK).also { it.linkName=key.readText(Charsets.UTF_8) }
                        else -> TarArchiveEntry(name).also { it.size=e.size }
                    }
                    entry.mode=e.mode and 0xFFF; entry.setUserId(e.uid); entry.setGroupId(e.gid); entry.setModTime(e.modifiedMillis)
                    entry.userName=""; entry.groupName=""
                    tar.putArchiveEntry(entry)
                    if(e.type==ExportEntryType.FILE) {
                        // First payload is verified inline; later links share that sealed data, no hash-only pass.
                        if(existing) sourceBytes=Math.addExact(sourceBytes,e.size)
                        else {
                        val sent=FileInputStream(c.data(e.dataId)).use { input -> ExportStreams.copy(input,tar,control) { n -> sourceBytes=Math.addExact(sourceBytes,n); control.onProgress(ExportProgress(ExportPhase.WRITING,count,sourceBytes)) } }
                        requireMatch(sent,e.size to e.digest,"StagingHashMismatch",e.path)
                        key.writeText(name,Charsets.UTF_8)
                        }
                    }
                    tar.closeArchiveEntry(); count=Math.addExact(count,1)
                }
                tar.finish()
            }
            control.checkCancelled(); control.onProgress(ExportProgress(ExportPhase.VERIFYING,count,archiveBytes))
            val sent=archiveBytes to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            requireMatch(readback(output,control),sent,"HashMismatch","")
            return completed(c,count,sourceBytes,control)
        } finally { index.deleteRecursively() }
    }
    private fun completed(c:ExportCapturedSource,count:Long,bytes:Long,control:ExportControl):ExportVerification {
        if(count!=c.entries || bytes!=c.bytes) throw ExportFailure("InventoryAccountingMismatch")
        control.checkCancelled(); control.onProgress(ExportProgress(ExportPhase.COMPLETED_VERIFIED,count,bytes))
        return ExportVerification(count,bytes,c.stabilityAssumptions)
    }
    private fun readback(output:ExportOutput,control:ExportControl):Pair<Long,String> {
        val input=try { output.openRead() } catch(e:IOException) { throw ExportFailure("ReadbackUnavailable",cause=e) }
        return input.use { ExportStreams.copy(it,null,control) }
    }
    private fun requireMatch(actual:Pair<Long,String>,expected:Pair<Long,String>,category:String,path:String) {
        if(actual!=expected) throw ExportFailure(category,path)
    }
}
