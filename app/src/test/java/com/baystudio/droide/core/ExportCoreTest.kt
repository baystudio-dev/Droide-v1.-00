package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants

class ExportCoreTest {
    private fun fixture(block:(File,File)->Unit) { val home=Files.createTempDirectory("export-test").toFile(); try { val root=File(home,"root").apply { mkdir() }; block(root,File(home,"staging")) } finally { home.deleteRecursively() } }
    private fun permit(root:File,path:String="",validate:()->Unit={}):ExportCapturePermit=object:ExportCapturePermit {
        override val root=root; override val scope=ExportScope(path)
        override val stabilityAssumptions=listOf("External writers confirmed quiescent by user")
        override fun validate()=validate()
    }
    private class Destination(val corrupt:Boolean=false,val unreadable:Boolean=false):ExportDestination {
        val dirs=mutableSetOf<String>(); val files=linkedMapOf<String,ByteArray>(); var reads=0; var writes=0
        override fun createDirectory(relativePath:String) { check(dirs.add(relativePath)) }
        override fun createFile(relativePath:String):ExportOutput {
            check(!files.containsKey(relativePath)); files[relativePath]=byteArrayOf()
            return object:ExportOutput {
                override fun openWrite():OutputStream { writes++; return object:ByteArrayOutputStream() { override fun close() { files[relativePath]=toByteArray(); super.close() } } }
                override fun openRead():InputStream { reads++; if(unreadable) throw IOException("reopen unavailable"); val bytes=files.getValue(relativePath).clone(); if(corrupt && bytes.isNotEmpty()) bytes[0]=(bytes[0].toInt() xor 1).toByte(); return ByteArrayInputStream(bytes) }
            }
        }
    }
    @Test fun allIncludesHiddenDependenciesBinaryAndEmptyDirectories()=fixture { root,stage ->
        val paths=listOf(".git/objects/raw",".gradle/cache",".droide/recovery",".env","node_modules/a/index.js","build/intermediates/file","a\nname","😀.bin")
        val bytes=byteArrayOf(0,-1,13,10,127,1)
        paths.forEach { File(root,it).apply { parentFile!!.mkdirs(); writeBytes(bytes) } }; File(root,"empty/nested").mkdirs()
        ExportCaptureStore(stage).capture(permit(root)).use { captured ->
            val target=Destination(); val result=ExportEngine().transfer(captured,ExportFormat.TREE,target)
            assertEquals(paths.toSet(),target.files.keys); paths.forEach { assertArrayEquals(bytes,target.files[it]) }
            assertTrue(target.dirs.contains("empty/nested")); assertEquals(paths.size,target.reads); assertEquals(paths.size*bytes.size.toLong(),result.bytes)
            assertEquals(target.files.size+target.dirs.size.toLong(),result.entries)
        }
    }
    @Test fun scopedFileUsesCapturedBytesEvenAfterSourceChanges()=fixture { root,stage ->
        File(root,"a").writeBytes(byteArrayOf(0,13,10,-1)); File(root,"other").writeText("excluded")
        ExportCaptureStore(stage).capture(permit(root,"a")).use { c ->
            File(root,"a").writeText("changed after seal")
            val d=Destination(); ExportEngine().transfer(c,ExportFormat.RAW_FILE,d)
            assertArrayEquals(byteArrayOf(0,13,10,-1),d.files[""]); assertEquals(1,d.reads); assertEquals(1,d.writes)
        }
    }
    @Test fun scopeFolderDoesNotIncludeSiblingsAndPreservesZeroBytes()=fixture { root,stage ->
        File(root,"folder/empty").mkdirs(); File(root,"folder/zero").writeBytes(byteArrayOf()); File(root,"sibling").writeText("no")
        ExportCaptureStore(stage).capture(permit(root,"folder")).use { c -> val d=Destination(); ExportEngine().transfer(c,ExportFormat.TREE,d); assertEquals(setOf("zero"),d.files.keys); assertEquals(setOf("","empty"),d.dirs) }
    }
    @Test fun symlinkNeverDereferencedAndTreeRefusesBeforeCreatingOutput()=fixture { root,stage ->
        Files.createSymbolicLink(File(root,"outside").toPath(),File(root.parentFile,"missing-secret").toPath())
        ExportCaptureStore(stage).capture(permit(root)).use { c ->
            assertEquals(0L,c.bytes); val d=Destination()
            expectFailure("TreeCannotRepresentSymlinkUseTar") { ExportEngine().transfer(c,ExportFormat.TREE,d) }
            assertTrue(d.files.isEmpty()); assertTrue(d.dirs.isEmpty())
            ExportEngine().transfer(c,ExportFormat.TAR,d)
            TarArchiveInputStream(ByteArrayInputStream(d.files.getValue(""))).use { tar ->
                var link=false; while(true) { val e=tar.nextTarEntry ?: break; if(e.name=="outside") { link=true; assertTrue(e.isSymbolicLink); assertEquals(File(root.parentFile,"missing-secret").path,e.linkName) } }; assertTrue(link)
            }
        }
    }
    @Test fun tarPreservesHardlinkRelationshipAndEmptyDirectory()=fixture { root,stage ->
        File(root,"first").writeText("same inode bytes"); Files.createLink(File(root,"second").toPath(),File(root,"first").toPath()); File(root,"empty").mkdir()
        ExportCaptureStore(stage).capture(permit(root)).use { c ->
            assertEquals(1L,c.sourceFilesRead); assertEquals("same inode bytes".toByteArray().size.toLong(),c.sourceBytesRead)
            assertEquals(1,c.directory.listFiles()!!.count { it.name.endsWith(".data") })
            val tree=Destination(); ExportEngine().transfer(c,ExportFormat.TREE,tree)
            assertArrayEquals(tree.files.getValue("first"),tree.files.getValue("second"))
            val d=Destination(); ExportEngine().transfer(c,ExportFormat.TAR,d); assertEquals(1,d.reads)
            var links=0; var regular=0; var empty=false
            val restored=linkedMapOf<String,ByteArray>()
            TarArchiveInputStream(ByteArrayInputStream(d.files.getValue(""))).use { tar ->
                while(true) {
                    val e=tar.nextTarEntry ?: break

                    when {
                        e.isLink -> {
                            links++
                            assertTrue(e.name=="first" || e.name=="second")
                            assertNotEquals(e.name,e.linkName)
                            assertEquals(0L,e.size)
                            assertEquals(0,tar.readBytes().size)
                            val target=restored[e.linkName]
                            assertNotNull("Hardlink must reference an earlier payload",target)
                            assertFalse(restored.containsKey(e.name))
                            restored[e.name]=checkNotNull(target)
                        }
                        e.linkFlag==TarConstants.LF_NORMAL || e.linkFlag==TarConstants.LF_OLDNORM -> {
                            regular++
                            assertFalse(restored.containsKey(e.name))
                            val bytes=tar.readBytes()
                            assertEquals("same inode bytes",bytes.toString(Charsets.UTF_8))
                            assertEquals(bytes.size.toLong(),e.size)
                            restored[e.name]=bytes
                        }
                        e.isDirectory -> if(e.name=="empty/") empty=true
                        else -> fail("Unexpected TAR entry type for ${e.name}")
                    }
                }
            }
            assertEquals(1,links); assertEquals(1,regular); assertTrue(empty)
            assertEquals(setOf("first","second"),restored.keys)
            assertArrayEquals("same inode bytes".toByteArray(Charsets.UTF_8),restored.getValue("first"))
            assertArrayEquals(restored.getValue("first"),restored.getValue("second"))
        }
    }
    @Test fun changedHardlinkPeerMetadataFailsInsteadOfReusingOldBytes()=fixture { root,stage ->
        File(root,"first").writeText("original"); Files.createLink(File(root,"second").toPath(),File(root,"first").toPath())
        var changed=false
        expectFailure("SourceChanged") {
            ExportCaptureStore(stage).capture(permit(root),ExportControl(onProgress={ progress ->
                if(progress.entries==2L && !changed) { changed=true; File(root,"first").writeText("different length on same inode") }
            }))
        }
        assertTrue(changed); assertEquals(0,stage.listFiles()!!.size)
    }
    @Test fun corruptStagingOrDestinationNeverCompletes()=fixture { root,stage ->
        File(root,"a").writeText("original")
        ExportCaptureStore(stage).capture(permit(root,"a")).use { c ->
            expectFailure("HashMismatch") { ExportEngine().transfer(c,ExportFormat.RAW_FILE,Destination(corrupt=true)) }
            expectFailure("ReadbackUnavailable") { ExportEngine().transfer(c,ExportFormat.RAW_FILE,Destination(unreadable=true)) }
            c.data(0).writeText("damaged!"); val d=Destination()
            expectFailure("StagingHashMismatch") { ExportEngine().transfer(c,ExportFormat.RAW_FILE,d) }; assertEquals(0,d.reads)
        }
    }
    @Test fun cancelCaptureCleansStagingAndCancelTransferHasNoSuccess()=fixture { root,stage ->
        File(root,"a").writeBytes(ByteArray(700000)); var chunks=0
        try { ExportCaptureStore(stage).capture(permit(root),ExportControl(checkCancelled={if(chunks>0) throw CancellationException()},onProgress={chunks++})); fail() } catch(_:CancellationException) {}
        assertEquals(0,stage.listFiles()!!.size)
        ExportCaptureStore(stage).capture(permit(root)).use { c ->
            var completed=false
            try { ExportEngine().transfer(c,ExportFormat.TREE,Destination(),ExportControl(checkCancelled={throw CancellationException()},onProgress={if(it.phase==ExportPhase.COMPLETED_VERIFIED) completed=true})); fail() } catch(_:CancellationException) {}
            assertFalse(completed)
        }
    }
    @Test fun changedOwnerStopsBeforeSeal()=fixture { root,stage ->
        File(root,"a").writeText("bytes"); var valid=true
        try { ExportCaptureStore(stage).capture(permit(root,validate={check(valid)}),ExportControl(onProgress={valid=false})); fail() } catch(_:IllegalStateException) {}
        assertEquals(0,stage.listFiles()!!.size)
    }
    @Test fun stagingMustStayOutsideSourceAndScopeCannotEscape()=fixture { root,_ ->
        expectFailure("StagingInsideSource") { ExportCaptureStore(File(root,"stage")).capture(permit(root)) }
        try { ExportScope("../escape"); fail() } catch(_:IllegalArgumentException) {}
    }
    @Test fun manifestTruncationAndTamperingCannotProduceSuccess()=fixture { root,stage ->
        File(root,"a").writeText("bytes")
        ExportCaptureStore(stage).capture(permit(root)).use { c -> c.manifest.writeBytes(byteArrayOf()); expectFailure("ManifestCorrupt") { ExportEngine().transfer(c,ExportFormat.TREE,Destination()) } }
    }
    @Test fun logicalStreamBeyondFourGiBUsesLongCountersAndBoundedReads() {
        val length=4L*1024*1024*1024+257
        var remaining=length; var largestRequest=0; var counted=0L
        val input=object:InputStream() {
            override fun read():Int=throw AssertionError("Bulk read required")
            override fun read(b:ByteArray,off:Int,len:Int):Int { largestRequest=maxOf(largestRequest,len); if(remaining==0L) return -1; val n=minOf(remaining,len.toLong()).toInt(); java.util.Arrays.fill(b,off,off+n,0.toByte()); remaining-=n; return n }
        }
        val result=ExportStreams.copy(input,null,ExportControl()) { counted+=it }
        assertEquals(length,result.first); assertEquals(length,counted); assertTrue(largestRequest<=256*1024)
    }
    @Test fun outputCloseFailureAndVerificationCancellationNeverComplete()=fixture { root,stage ->
        File(root,"a").writeText("abc")
        ExportCaptureStore(stage).capture(permit(root,"a")).use { c ->
            var read=false
            val bad=object:ExportDestination {
                override fun createDirectory(relativePath:String)=Unit
                override fun createFile(relativePath:String)=object:ExportOutput {
                    override fun openWrite()=object:ByteArrayOutputStream() { override fun close() { throw IOException("close failure") } }
                    override fun openRead():InputStream { read=true; return ByteArrayInputStream(byteArrayOf()) }
                }
            }
            try { ExportEngine().transfer(c,ExportFormat.RAW_FILE,bad); fail() } catch(_:IOException) {}
            assertFalse(read)
            var verifying=false; var completed=false
            try { ExportEngine().transfer(c,ExportFormat.RAW_FILE,Destination(),ExportControl(checkCancelled={if(verifying) throw CancellationException()},onProgress={verifying=it.phase==ExportPhase.VERIFYING; if(it.phase==ExportPhase.COMPLETED_VERIFIED) completed=true})); fail() } catch(_:CancellationException) {}
            assertFalse(completed)
        }
    }
    @Test fun unsupportedFifoIsRejectedWithoutOpeningIt()=fixture { root,stage ->
        val process=try { ProcessBuilder("mkfifo",File(root,"fifo").path).start() } catch(_:IOException) { return@fixture }
        org.junit.Assume.assumeTrue(process.waitFor()==0)
        expectFailure("UnsupportedEntry") { ExportCaptureStore(stage).capture(permit(root)) }
    }
    private fun expectFailure(category:String,block:()->Unit) { try { block(); fail("Expected $category") } catch(e:ExportFailure) { assertEquals(category,e.category) } }
}
