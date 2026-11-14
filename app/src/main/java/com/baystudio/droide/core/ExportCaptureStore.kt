package com.baystudio.droide.core

import java.io.*
import java.nio.channels.Channels
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.security.DigestInputStream
import java.util.UUID

/** Optional per-entry policy admission, before entry metadata or payload is accessed. */
internal interface ExportEntryAdmissionPermit : ExportCapturePermit {
    fun requireEntry(relativePath: String)
}

internal enum class ExportEntryType { FILE, DIRECTORY, SYMLINK }
internal data class ExportEntry(val id: Long, val dataId: Long, val path: String, val type: ExportEntryType,
    val size: Long, val digest: String, val link: String, val mode: Int, val uid: Long,
    val gid: Long, val modifiedMillis: Long, val inode: String)

/** Length-prefixed records: no newline delimiters, no all-entry index in RAM. */
internal object ExportManifest {
    fun write(out: DataOutputStream, e: ExportEntry) {
        out.writeLong(e.id); out.writeLong(e.dataId); string(out,e.path); out.writeInt(e.type.ordinal); out.writeLong(e.size)
        string(out,e.digest); string(out,e.link); out.writeInt(e.mode); out.writeLong(e.uid)
        out.writeLong(e.gid); out.writeLong(e.modifiedMillis); string(out,e.inode)
    }
    fun string(out:DataOutputStream,s:String) { val b=s.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
    fun string(input:DataInputStream):String { val n=input.readInt(); if(n !in 0..(1024*1024)) throw ExportFailure("ManifestCorrupt"); return ByteArray(n).also(input::readFully).toString(Charsets.UTF_8) }
    fun each(file:File, expectedDigest:String? = null, block:(ExportEntry)->Unit) { val digest=MessageDigest.getInstance("SHA-256"); DataInputStream(BufferedInputStream(DigestInputStream(FileInputStream(file),digest))).use { i ->
        while(true) {
            val id=try { i.readLong() } catch(e:EOFException) { break }
            val entry=ExportEntry(id,i.readLong(),string(i),ExportEntryType.entries[i.readInt()],i.readLong(),string(i),string(i),i.readInt(),i.readLong(),i.readLong(),i.readLong(),string(i))
            try { ExportScope(entry.path); require(entry.id>=0 && entry.dataId>=0 && entry.dataId<=entry.id && entry.size>=0) } catch(e:IllegalArgumentException) { throw ExportFailure("ManifestCorrupt",cause=e) }
            block(entry)
        }
    }; if(expectedDigest!=null && digest.digest().hex()!=expectedDigest) throw ExportFailure("ManifestCorrupt") }
}
internal fun ByteArray.hex()=joinToString("") { "%02x".format(it.toInt() and 255) }
class ExportCapturedSource internal constructor(internal val directory:File, internal val manifest:File,
    val entries:Long, val bytes:Long, val stabilityAssumptions:List<String>, internal val rootType:ExportEntryType, internal val manifestDigest:String, val sourceBytesRead:Long, val sourceFilesRead:Long) : AutoCloseable {
    internal fun data(id:Long)=File(directory,"$id.data")
    /** Explicit disposal, never invoked automatically on process-death recovery. */
    override fun close() { directory.deleteRecursively() }
}

/** One traversal, no filtered listings. Concurrent single-file capture reads source bytes again
 * before sealing; ordinary quiescent directory capture still reads each unique inode once. IO only. */
class ExportCaptureStore(private val baseDir:File) {
    fun capture(permit:ExportCapturePermit, control:ExportControl=ExportControl()):ExportCapturedSource {
        permit.validate(); control.checkCancelled()
        val root=permit.root.toPath().toAbsolutePath().normalize()
        if(Files.isSymbolicLink(root) || root.toRealPath()!=root) throw ExportFailure("ScopeIdentityChanged")
        val base=baseDir.canonicalFile.toPath()
        if(base.startsWith(root)) throw ExportFailure("StagingInsideSource")
        ExportStorageBudget.requireAvailable(baseDir.parentFile ?: baseDir)
        Files.createDirectories(base)
        var entries=0L; var bytes=0L; var sourceBytesRead=0L; var sourceFilesRead=0L
        val scope=root.resolve(permit.scope.relativePath).normalize()
        var ancestor=scope.parent
        while(ancestor!=null && ancestor.startsWith(root)) { if(Files.isSymbolicLink(ancestor)) throw ExportFailure("ScopeIdentityChanged"); if(ancestor==root) break; ancestor=ancestor.parent }
        (permit as? ExportEntryAdmissionPermit)?.requireEntry("")
        val rootType=kind(attrs(scope),permit.scope.relativePath)
        val verifySingleFile = (permit as? VerifiedFileExportCapturePermit)?.verifySingleFile == true
        if (verifySingleFile && rootType != ExportEntryType.FILE) throw ExportFailure("ScopeIdentityChanged")
        val initialStamp = if (verifySingleFile) ExportFileStamp.read(scope).also {
            if (it.identity != (permit as VerifiedFileExportCapturePermit).selectedFileIdentity) throw ExportFailure("ScopeIdentityChanged")
        } else null
        var singleFileDigest: String? = null
        val dir=Files.createDirectory(base.resolve(UUID.randomUUID().toString())).toFile()
        val manifest=File(dir,"manifest.bin")
        val inodeIndex=File(dir,"capture-links").apply { if(!mkdir()) throw ExportFailure("StagingIndexUnavailable") }
        try {
            val manifestHash=MessageDigest.getInstance("SHA-256")
            DataOutputStream(BufferedOutputStream(DigestOutputStream(ExportStorageBudget.output(dir, FileOutputStream(manifest)),manifestHash))).use { out ->
                fun visit(path:Path, relative:String) {
                    control.checkCancelled(); permit.validate()
                    ExportStorageBudget.requireAvailable(dir, 4096)
                    (permit as? ExportEntryAdmissionPermit)?.requireEntry(relative)
                    // UnixPath retains raw bytes; round-trip rejects replacement-decoded filenames.
                    if(path.fileName != Paths.get(path.fileName.toString())) throw ExportFailure("NameNotRepresentable",relative)
                    val before=attrs(path); val type=kind(before,relative)
                    val metadata=try { Files.readAttributes(path,"unix:mode,uid,gid,dev,ino",LinkOption.NOFOLLOW_LINKS) } catch(e:UnsupportedOperationException) { throw ExportFailure("SourceMetadataUnavailable",relative,e) }
                    if (initialStamp != null && (
                        (metadata["dev"] as Number).toLong() != initialStamp.device ||
                        (metadata["ino"] as Number).toLong() != initialStamp.inode ||
                        (metadata["mode"] as Number).toInt() != initialStamp.mode ||
                        (metadata["uid"] as Number).toLong() != initialStamp.uid ||
                        (metadata["gid"] as Number).toLong() != initialStamp.gid ||
                        before.size() != initialStamp.size ||
                        // NIO timestamps may omit fractional seconds on Android even when
                        // Os.lstat reports nanoseconds. Compare change stamps in one domain;
                        // never round away ctime/mtime precision to match NIO's view.
                        ExportFileStamp.read(path) != initialStamp
                    )) throw ExportFailure("SourceChanged", relative)
                    val inode="${metadata["dev"]}:${metadata["ino"]}"
                    val id=entries; var dataId=id; var size=0L; var digest=""; var link=""
                    when(type) {
                        ExportEntryType.FILE -> {
                            val first=File(inodeIndex,MessageDigest.getInstance("SHA-256").digest(inode.toByteArray(Charsets.UTF_8)).hex())
                            if(first.exists()) {
                                DataInputStream(FileInputStream(first)).use { index ->
                                    dataId=index.readLong(); size=index.readLong(); digest=index.readUTF()
                                    val modified=index.readUTF(); val firstMode=index.readInt(); val firstUid=index.readLong(); val firstGid=index.readLong()
                                    if(size!=before.size() || modified!=before.lastModifiedTime().toString() || firstMode!=(metadata["mode"] as Number).toInt() || firstUid!=(metadata["uid"] as Number).toLong() || firstGid!=(metadata["gid"] as Number).toLong()) throw ExportFailure("SourceChanged",relative)
                                }
                                bytes=Math.addExact(bytes,size)
                            } else {
                            if (before.size() == 0L) {
                                // An observed empty regular file needs no source descriptor.
                                // In particular, opening+closing a dedicated zero-byte metadata
                                // lock in this process could release another owner's POSIX lock.
                                // Keep the same entry policy and before/after identity checks.
                                FileOutputStream(File(dir,"$id.data")).use { }
                                digest=MessageDigest.getInstance("SHA-256").digest(byteArrayOf()).hex()
                            } else {
                                // O_NOFOLLOW descriptor stays bound after rename; hardlink peers share this staging copy.
                                sourceFilesRead=Math.addExact(sourceFilesRead,1)
                                ExportSourceReader.read(path,initialStamp?.device ?: (metadata["dev"] as Number).toLong(),
                                    initialStamp?.inode ?: (metadata["ino"] as Number).toLong(),initialStamp?.size ?: before.size()) { input ->
                                    ExportStorageBudget.requireAvailable(dir, before.size())
                                    ExportStorageBudget.output(dir, FileOutputStream(File(dir,"$id.data")), before.size()).use { output ->
                                        val count: (Long) -> Unit = { n -> sourceBytesRead=Math.addExact(sourceBytesRead,n); bytes=Math.addExact(bytes,n); control.onProgress(ExportProgress(ExportPhase.CAPTURING,entries,bytes)) }
                                        val result = if (initialStamp != null) ExportStreams.copyBounded(input,output,control,initialStamp.size,count)
                                            else ExportStreams.copy(input,output,control,count)
                                        size=result.first; digest=result.second
                                    }
                                }
                            }
                            if(size!=before.size()) throw ExportFailure("SourceChanged",relative)
                            DataOutputStream(ExportStorageBudget.output(dir, FileOutputStream(first))).use { index ->
                                index.writeLong(dataId); index.writeLong(size); index.writeUTF(digest); index.writeUTF(before.lastModifiedTime().toString())
                                index.writeInt((metadata["mode"] as Number).toInt()); index.writeLong((metadata["uid"] as Number).toLong()); index.writeLong((metadata["gid"] as Number).toLong())
                            }
                            }
                        }
                        ExportEntryType.SYMLINK -> { val target=Files.readSymbolicLink(path); if(target!=Paths.get(target.toString())) throw ExportFailure("NameNotRepresentable",relative); link=target.toString() }
                        ExportEntryType.DIRECTORY -> Unit
                    }
                    if (verifySingleFile) {
                        if (entries != 0L || type != ExportEntryType.FILE) throw ExportFailure("ScopeIdentityChanged")
                        singleFileDigest = digest
                    }
                    val after=attrs(path)
                    if(before.fileKey()!=after.fileKey() || before.size()!=after.size() || before.lastModifiedTime()!=after.lastModifiedTime()) throw ExportFailure("SourceChanged",relative)
                    ExportManifest.write(out,ExportEntry(id,dataId,relative,type,size,digest,link,(metadata["mode"] as Number).toInt(),(metadata["uid"] as Number).toLong(),(metadata["gid"] as Number).toLong(),before.lastModifiedTime().toMillis(),inode))
                    entries=Math.addExact(entries,1)
                    control.onProgress(ExportProgress(ExportPhase.CAPTURING,entries,bytes))
                    if(type==ExportEntryType.DIRECTORY) {
                        Files.newDirectoryStream(path).use { children -> for(child in children) visit(child,if(relative.isEmpty()) child.fileName.toString() else "$relative/${child.fileName}") }
                        val end=attrs(path)
                        if(before.fileKey()!=end.fileKey() || before.lastModifiedTime()!=end.lastModifiedTime()) throw ExportFailure("SourceChanged",relative)
                    }
                }
                visit(scope,if(rootType==ExportEntryType.DIRECTORY) "" else scope.fileName.toString())
            }
            control.checkCancelled(); permit.validate()
            if (initialStamp != null) {
                // The entire copy must have one unchanged source identity/change stamp.
                // Read bytes again to catch torn reads, even on coarse/nonstandard filesystems.
                if (initialStamp != ExportFileStamp.read(scope)) throw ExportFailure("SourceChanged")
                if (initialStamp.size > 0L) {
                    sourceFilesRead = Math.addExact(sourceFilesRead, 1)
                    val verified = ExportSourceReader.read(scope, initialStamp.device, initialStamp.inode, initialStamp.size) { input ->
                        ExportStreams.copyBounded(input, null, control, initialStamp.size) { n -> sourceBytesRead = Math.addExact(sourceBytesRead, n) }
                    }
                    if (verified.first != initialStamp.size || verified.second != singleFileDigest) throw ExportFailure("SourceChanged")
                }
                if (initialStamp != ExportFileStamp.read(scope)) throw ExportFailure("SourceChanged")
                control.checkCancelled(); permit.validate()
            }
            if(!inodeIndex.deleteRecursively()) throw ExportFailure("StagingIndexCleanupFailed")
            return ExportCapturedSource(dir,manifest,entries,bytes,permit.stabilityAssumptions.toList(),rootType,manifestHash.digest().hex(),sourceBytesRead,sourceFilesRead)
        } catch(t:Throwable) { dir.deleteRecursively(); throw t }
    }
    private fun attrs(path:Path)=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
    private fun kind(a:BasicFileAttributes,path:String)=when { a.isRegularFile -> ExportEntryType.FILE; a.isDirectory -> ExportEntryType.DIRECTORY; a.isSymbolicLink -> ExportEntryType.SYMLINK; else -> throw ExportFailure("UnsupportedEntry",path) }
}
internal object ExportStreams {
    /** A concurrent appender must not turn snapshot capture into an unbounded stream. */
    fun copyBounded(input: InputStream, output: OutputStream?, control: ExportControl, expectedBytes: Long,
        chunk: (Long) -> Unit = {}): Pair<Long, String> {
        require(expectedBytes >= 0L)
        var remaining = expectedBytes
        val bounded = object : InputStream() {
            override fun read(): Int {
                val value = input.read()
                if (value >= 0 && remaining-- == 0L) throw ExportFailure("SourceChanged")
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val limit = if (remaining >= length.toLong()) length else (remaining + 1L).toInt()
                val count = input.read(buffer, offset, limit)
                if (count > remaining) throw ExportFailure("SourceChanged")
                if (count > 0) remaining -= count
                return count
            }
        }
        val copied = copy(bounded, output, control, chunk)
        if (copied.first != expectedBytes) throw ExportFailure("SourceChanged")
        return copied
    }

    fun copy(input:InputStream, output:OutputStream?, control:ExportControl, chunk:(Long)->Unit={}):Pair<Long,String> {
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(256*1024); var size=0L
        while(true) { control.checkCancelled(); val n=input.read(buffer); if(n<0) break; if(n==0) continue; output?.write(buffer,0,n); digest.update(buffer,0,n); size=Math.addExact(size,n.toLong()); chunk(n.toLong()) }
        control.checkCancelled(); return size to digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
