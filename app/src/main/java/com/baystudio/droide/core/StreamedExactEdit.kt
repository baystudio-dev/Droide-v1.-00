package com.baystudio.droide.core

import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

/** Preparation only. */

internal object StreamedExactEdit {
    class Diagnostics {
        var patternCachePeak: Int = 0; internal set
        var prefixCachePeak: Int = 0; internal set
        var patternAccesses: Long = 0; internal set
    }
    class MatchCount(val found: Long) : IllegalArgumentException("edit_file search must match exactly once (found $found)")
    class PreparationFailure(cause: Exception, val recoveryHandle: OwnedResponseContentStore.Handle?) :
        IllegalStateException("Streamed edit preparation failed; no project mutation occurred", cause) {
        @Deprecated("Recovery may already be COMPLETE; reconcile store state") val incompleteHandle get() = recoveryHandle
    }
    class PreparationCancelled(cause: Exception, val recoveryHandle: OwnedResponseContentStore.Handle?) : java.util.concurrent.CancellationException("Streamed edit preparation cancelled") {
        init { initCause(cause) }
        @Deprecated("Recovery may already be COMPLETE; reconcile store state") val incompleteHandle get() = recoveryHandle
    }
    data class Candidate(val content: StreamedFileArgumentExtractor.Source,
        val originalSnapshot: StreamedFileArgumentExtractor.Source,
        val originalArguments: StreamedFileArgumentExtractor.Source,
        val matchByteOffset: Long)

    fun prepare(prepared: StreamedFileArgumentExtractor.Prepared,
        argumentsSession: OwnedResponseContentStore.Session,
        reviewedSnapshot: StreamedFileArgumentExtractor.Source,
        snapshotSession: OwnedResponseContentStore.Session,
        trustedScratchRoot: Path, admission: OwnedResponseContentStore.DiskAdmission,
        checkCancelled: () -> Unit = {}, diagnostics: Diagnostics = Diagnostics()): Candidate {
        require(prepared.tool == StreamedFileArgumentExtractor.Tool.EDIT_FILE)
        val pattern = requireNotNull(prepared.search)
        val replacement = requireNotNull(prepared.replacement)
        fun verify(source: StreamedFileArgumentExtractor.Source, session: OwnedResponseContentStore.Session, kind: OwnedResponseContentStore.Kind) {
            checkCancelled();require(source.kind == kind)
            val actual = session.openCompleted(source.handle, checkCancelled).content
            require(actual.kind == kind && actual.byteLength == source.bytes && actual.utf16Length == source.utf16Units && actual.sha256 == source.sha256)
        }
        verify(prepared.originalArguments,argumentsSession,OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
        verify(pattern,argumentsSession,OwnedResponseContentStore.Kind.TOOL_CONTENT)
        verify(replacement,argumentsSession,OwnedResponseContentStore.Kind.TOOL_CONTENT)
        verify(reviewedSnapshot,snapshotSession,OwnedResponseContentStore.Kind.TOOL_CONTENT)
        require(pattern.bytes <= Long.MAX_VALUE / 8) { "Pattern prefix index exceeds file offset representation" }
        require(pattern.bytes > 0) { "edit_file search must not be empty" }
        requireRoot(trustedScratchRoot)
        val scratch = admission.reserve(4096).use { Files.createTempDirectory(trustedScratchRoot,"edit-index-") }
        var output: OwnedResponseContentStore.Session.Writer? = null
        var primary: Exception? = null
        try {
            val patternPath=scratch.resolve("pattern")
            admission.reserve(4096).use { FileChannel.open(patternPath,CREATE_NEW,WRITE,READ) }.use { channel ->
                argumentsSession.openBytes(pattern.handle,checkCancelled).use { input -> copyToFile(input,channel,admission,checkCancelled) }
                require(channel.size()==pattern.bytes)
                PagedBytes(channel,pattern.bytes,checkCancelled,diagnostics).use { bytes ->
                    PrefixIndex(scratch.resolve("prefix"),admission,checkCancelled,diagnostics).use { index ->
                        index.append(0)
                        var i=1L
                        while(i<pattern.bytes) {
                            checkCancelled();val b=bytes[i];var j=index[i-1]
                            while(j>0 && b!=bytes[j]) { checkCancelled();j=index[j-1] }
                            if(b==bytes[j])j++
                            index.append(j);i++
                        }
                        var count=0L;var first=-1L;var position=0L;var matched=0L
                        snapshotSession.openBytes(reviewedSnapshot.handle,checkCancelled).use { input ->
                            val buffer=ByteArray(PAGE)
                            while(true) { checkCancelled();val n=input.read(buffer);if(n<0)break;check(n>0)
                                for(k in 0 until n) {
                                    if(k%1024==0)checkCancelled();val b=buffer[k]
                                    while(matched>0 && b!=bytes[matched]) { checkCancelled();matched=index[matched-1] }
                                    if(b==bytes[matched])matched++
                                    position++
                                    if(matched==pattern.bytes) {
                                        count++;if(first<0)first=position-pattern.bytes
                                        matched=0
                                    }
                                }
                            }
                        }
                        require(position==reviewedSnapshot.bytes)
                        if(count!=1L)throw MatchCount(count)
                        checkCancelled()
                        val writer=argumentsSession.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT);output=writer
                        snapshotSession.openBytes(reviewedSnapshot.handle,checkCancelled).use { input ->
                            transfer(input,first,writer,checkCancelled)
                            discard(input,pattern.bytes,checkCancelled)
                            argumentsSession.openBytes(replacement.handle,checkCancelled).use { transfer(it,replacement.bytes,writer,checkCancelled);require(it.read()==-1) }
                            transfer(input,reviewedSnapshot.bytes-first-pattern.bytes,writer,checkCancelled)
                            require(input.read()==-1)
                        }
                        val complete=writer.sealCompleted { input -> val buffer=ByteArray(PAGE);while(true){checkCancelled();val n=input.read(buffer);if(n<0)break;check(n>0)} }.content
                        checkCancelled()
                        return Candidate(StreamedFileArgumentExtractor.Source(complete.handle,complete.byteLength,complete.utf16Length,complete.sha256,complete.kind),reviewedSnapshot,prepared.originalArguments,first)
                    }
                }
            }
        } catch(error: Exception) {
            primary=error
            if(error is MatchCount)throw error
            // Writer handle is returned for reconciliation, including a failure after seal. Never silently
            // claim all bytes were preserved; ENOSPC can leave only an incomplete prefix.
            if(error is java.util.concurrent.CancellationException)throw PreparationCancelled(error,output?.recoveryHandle)
            throw PreparationFailure(error,output?.recoveryHandle)
        } finally {
            var cleanup: Exception? = null
            fun attempt(block:()->Unit){try{block()}catch(e:Exception){if(cleanup==null)cleanup=e else cleanup!!.addSuppressed(e)}}
            attempt { output?.close() }
            // Only this invocation's private scratch files, never published/project/source content.
            attempt { Files.deleteIfExists(scratch.resolve("prefix")) }
            attempt { Files.deleteIfExists(scratch.resolve("pattern")) }
            attempt { Files.deleteIfExists(scratch) }
            cleanup?.let { if(primary!=null)primary!!.addSuppressed(it) else throw PreparationFailure(it,output?.recoveryHandle) }
        }
    }
    private const val PAGE=8192
    private fun requireRoot(root: Path) {
        require(root.isAbsolute)
        var path:Path?=root
        while(path!=null){require(!Files.isSymbolicLink(path)){"Scratch ancestry contains a link"};path=path.parent}
        require(Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Expected existing app-private scratch root" }
    }
    private fun copyToFile(input:InputStream,channel:FileChannel,admission:OwnedResponseContentStore.DiskAdmission,check:()->Unit) {
        val bytes=ByteArray(PAGE)
        while(true){check();val n=input.read(bytes);if(n<0)break;require(n>0);admission.reserve(n.toLong()).use{val b=ByteBuffer.wrap(bytes,0,n);while(b.hasRemaining()){check();require(channel.write(b)>0)}}}
    }
    private fun transfer(input:InputStream,count:Long,out:OwnedResponseContentStore.Session.Writer,check:()->Unit) {
        var left=count;val buffer=ByteArray(PAGE)
        while(left>0){check();val n=input.read(buffer,0,minOf(buffer.size.toLong(),left).toInt());require(n>0){"Immutable source ended early"};out.appendUtf8(buffer,0,n);left-=n}
    }
    private fun discard(input:InputStream,count:Long,check:()->Unit) {
        var left=count;val buffer=ByteArray(PAGE)
        while(left>0){check();val n=input.read(buffer,0,minOf(buffer.size.toLong(),left).toInt());require(n>0);left-=n}
    }
    private class PagedBytes(private val file:FileChannel,private val length:Long,private val check:()->Unit,private val diagnostics:Diagnostics):Closeable {
        private val pages=object:LinkedHashMap<Long,ByteArray>(8,0.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<Long,ByteArray>?)=size>8}
        operator fun get(index:Long):Byte {
            require(index in 0 until length);diagnostics.patternAccesses++;val page=index/PAGE
            val data=pages[page] ?: run {check();val start=page*PAGE;val bytes=ByteArray(minOf(PAGE.toLong(),length-start).toInt());val b=ByteBuffer.wrap(bytes);var at=start
                while(b.hasRemaining()){check();val n=file.read(b,at);require(n>0);at+=n};pages[page]=bytes;diagnostics.patternCachePeak=maxOf(diagnostics.patternCachePeak,pages.size);bytes}
            return data[(index%PAGE).toInt()]
        }
        override fun close(){pages.clear()}
    }
    private class PrefixIndex(path:Path,private val admission:OwnedResponseContentStore.DiskAdmission,private val check:()->Unit,private val diagnostics:Diagnostics):Closeable {
        private val file=admission.reserve(4096).use { FileChannel.open(path,CREATE_NEW,READ,WRITE) }
        private val tail=ByteBuffer.allocate(PAGE)
        private var flushed=0L;private var count=0L
        private val pages=object:LinkedHashMap<Long,ByteBuffer>(8,0.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<Long,ByteBuffer>?)=size>8}
        fun append(value:Long){check();tail.putLong(value);count++;if(!tail.hasRemaining())flush()}
        private fun flush(){tail.flip();val length=tail.remaining();admission.reserve(length.toLong()).use{while(tail.hasRemaining()){check();require(file.write(tail)>0)}};flushed+=length;tail.clear()}
        operator fun get(index:Long):Long {
            require(index in 0 until count);val byteOffset=Math.multiplyExact(index,8L)
            if(byteOffset>=flushed)return tail.getLong((byteOffset-flushed).toInt())
            val page=byteOffset/PAGE;val data=pages[page] ?: run{check();val b=ByteBuffer.allocate(PAGE);var at=page*PAGE
                while(b.hasRemaining()){check();val n=file.read(b,at);require(n>0);at+=n};b.flip();pages[page]=b;diagnostics.prefixCachePeak=maxOf(diagnostics.prefixCachePeak,pages.size);b}
            return data.getLong((byteOffset%PAGE).toInt())
        }
        override fun close(){pages.clear();file.close()}
    }
}
