package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamedExactEditTest {
    private fun source(session:OwnedResponseContentStore.Session,text:String,kind:OwnedResponseContentStore.Kind):StreamedFileArgumentExtractor.Source = session.begin(kind).use { w ->
        text.chunked(4093).forEach(w::append)
        val c=w.sealCompleted{it.copyTo(java.io.OutputStream.nullOutputStream())}.content
        StreamedFileArgumentExtractor.Source(c.handle,c.byteLength,c.utf16Length,c.sha256,c.kind)
    }
    private fun test(text:String,search:String,replacement:String,expected:String?,denyScratch:Boolean=false) {
        val root=Files.createTempDirectory("exact-edit-")
        try {
            val admission=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
            val store=OwnedResponseContentStore(root.resolve("store"),admission,1024)
            val session=store.authorize(OwnedResponseContentStore.Owner("s","r","c"))
            val raw=source(session,buildJsonObject{put("path","a.txt");put("search",search);put("replace",replacement)}.toString(),OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            val args=StreamedFileArgumentExtractor.extract(session,raw.handle,StreamedFileArgumentExtractor.Tool.EDIT_FILE,"c")
            val before=source(session,text,OwnedResponseContentStore.Kind.TOOL_CONTENT)
            val scratch=Files.createDirectory(root.resolve("scratch"))
            val disk=if(denyScratch) object:OwnedResponseContentStore.DiskAdmission{override fun reserve(bytes:Long):AutoCloseable{throw java.io.IOException("disk full")}} else admission
            if(expected==null) {
                assertThrows(StreamedExactEdit.MatchCount::class.java){StreamedExactEdit.prepare(args,session,before,session,scratch,disk)}
            } else if(denyScratch) {
                assertThrows(java.io.IOException::class.java){StreamedExactEdit.prepare(args,session,before,session,scratch,disk)}
            } else {
                val metrics=StreamedExactEdit.Diagnostics()
                val result=StreamedExactEdit.prepare(args,session,before,session,scratch,disk,diagnostics=metrics)
                assertTrue(metrics.patternCachePeak<=8);assertTrue(metrics.prefixCachePeak<=8)
                assertTrue(metrics.patternAccesses<=8*(before.bytes+args.search!!.bytes))
                val actual=session.openBytes(result.content.handle).use{it.readBytes().toString(Charsets.UTF_8)}
                assertEquals(expected,actual);assertEquals(before.sha256,result.originalSnapshot.sha256)
                assertEquals(text,session.openBytes(before.handle).use{it.readBytes().toString(Charsets.UTF_8)})
            }
            Files.list(scratch).use{assertEquals(0L,it.count())}
        }finally{root.toFile().deleteRecursively()}
    }
    @Test fun exactNonoverlapSemantics(){test("aaaaa","aaa","X","Xaa");test("aaaaaa","aaa","X",null);test("abc","z","X",null)}
    @Test fun utf8BomCrLfAndEmptyReplacementPreserved(){test("\uFEFFa\r\n🌍 café\r\nz","🌍 café","","\uFEFFa\r\n\r\nz")}
    @Test fun largePatternAndAdversarialRepeatedPrefix(){val search="a".repeat(200_000)+"b";val text="a".repeat(600_000)+"b tail";test(text,search,"REPLACED","a".repeat(400_000)+"REPLACED tail")}
    @Test fun scratchDiskFailureBeforeMutation(){test("old","old","new","new",true)}
    @Test fun randomizedMatchesAgreeWithLegacyNonoverlap(){val random=kotlin.random.Random(314);repeat(60){
        val text=(0 until 80).joinToString(""){('a'.code+random.nextInt(3)).toChar().toString()};val needle=(0 until random.nextInt(1,8)).joinToString(""){('a'.code+random.nextInt(3)).toChar().toString()}
        var from=0;var count=0;while(true){val at=text.indexOf(needle,from);if(at<0)break;count++;from=at+needle.length}
        test(text,needle,"R",if(count==1)text.replaceFirst(needle,"R") else null)
    }}
    @Test fun cancellationAndPartialPrefixFailureCleanOwnedScratch() {
        for(cancel in listOf(true,false)) {
            val root=Files.createTempDirectory("exact-abort-")
            try {
                val base=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
                val store=OwnedResponseContentStore(root.resolve("store"),base,1024)
                val session=store.authorize(OwnedResponseContentStore.Owner("s","r","c"))
                val needle="a".repeat(9000)+"b"
                val raw=source(session,buildJsonObject{put("path","a.txt");put("search",needle);put("replace","new")}.toString(),OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
                val args=StreamedFileArgumentExtractor.extract(session,raw.handle,StreamedFileArgumentExtractor.Tool.EDIT_FILE,"c")
                val before=source(session,needle,OwnedResponseContentStore.Kind.TOOL_CONTENT)
                val scratch=Files.createDirectory(root.resolve("scratch"))
                fun hasPrefix()=Files.walk(scratch).use{files->files.anyMatch{it.fileName.toString()=="prefix"}}
                var checks=0
                val check:()->Unit={if(cancel && ++checks>100 && hasPrefix())throw java.util.concurrent.CancellationException("stop")}
                val disk=object:OwnedResponseContentStore.DiskAdmission {
                    var writes=0
                    override fun reserve(bytes:Long):AutoCloseable {
                        if(!cancel && bytes==8192L && hasPrefix() && ++writes==2)throw java.io.IOException("partial prefix ENOSPC")
                        return base.reserve(bytes)
                    }
                }
                if(cancel)assertThrows(StreamedExactEdit.PreparationCancelled::class.java){StreamedExactEdit.prepare(args,session,before,session,scratch,disk,check)}
                else assertThrows(StreamedExactEdit.PreparationFailure::class.java){StreamedExactEdit.prepare(args,session,before,session,scratch,disk,check)}
                Files.list(scratch).use{assertEquals(0L,it.count())}
                assertEquals(needle,session.openBytes(before.handle).use{it.readBytes().toString(Charsets.UTF_8)})
            }finally{root.toFile().deleteRecursively()}
        }
    }
    @Test fun forgedHugeDescriptorAndWrongOwnerRejectedBeforePreparation() {
        val root=Files.createTempDirectory("exact-descriptor-")
        try{
            val disk=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
            val store=OwnedResponseContentStore(root.resolve("store"),disk,1024)
            val owner=OwnedResponseContentStore.Owner("s","r","c");val session=store.authorize(owner)
            val raw=source(session,"""{"path":"a.txt","search":"old","replace":"new"}""",OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
            val args=StreamedFileArgumentExtractor.extract(session,raw.handle,StreamedFileArgumentExtractor.Tool.EDIT_FILE,"c")
            val original=source(session,"old",OwnedResponseContentStore.Kind.TOOL_CONTENT)
            val huge=StreamedFileArgumentExtractor.Source(original.handle,Long.MAX_VALUE,Long.MAX_VALUE,original.sha256,original.kind)
            val scratch=Files.createDirectory(root.resolve("scratch"))
            assertThrows(IllegalArgumentException::class.java){StreamedExactEdit.prepare(args,session,huge,session,scratch,disk)}
            assertThrows(Exception::class.java){StreamedExactEdit.prepare(args,store.authorize(owner.copy(sessionId="other")),original,session,scratch,disk)}
            Files.list(scratch).use{assertEquals(0L,it.count())}
        }finally{root.toFile().deleteRecursively()}
    }

    @Test fun generatedLargeSourcesNeverNeedWholeInputOrOutputString() {
        val root=Files.createTempDirectory("exact-generated-")
        try{
            val disk=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)
            val store=OwnedResponseContentStore(root.resolve("store"),disk,1024)
            val session=store.authorize(OwnedResponseContentStore.Owner("s","r","c"));val chunk="a".repeat(8192)
            val raw=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{w ->
                w.append("{\"path\":\"a.txt\",\"search\":\"");repeat(64){w.append(chunk)};w.append("b\",\"replace\":\"X\"}")
                w.sealCompleted{StreamedJsonValidator.requireObject(it)}.content
            }
            val args=StreamedFileArgumentExtractor.extract(session,raw.handle,StreamedFileArgumentExtractor.Tool.EDIT_FILE,"c")
            val snapshot=session.begin(OwnedResponseContentStore.Kind.TOOL_CONTENT).use{w -> repeat(256){w.append(chunk)};w.append("b tail");w.sealCompleted{it.copyTo(java.io.OutputStream.nullOutputStream())}.content}
            val before=StreamedFileArgumentExtractor.Source(snapshot.handle,snapshot.byteLength,snapshot.utf16Length,snapshot.sha256,snapshot.kind)
            val metrics=StreamedExactEdit.Diagnostics()
            val result=StreamedExactEdit.prepare(args,session,before,session,Files.createDirectory(root.resolve("scratch")),disk,diagnostics=metrics)
            session.openBytes(result.content.handle).use{input ->
                val buffer=ByteArray(8192)
                repeat(192){var read=0;while(read<buffer.size){val n=input.read(buffer,read,buffer.size-read);assertTrue(n>0);read+=n};assertTrue(buffer.all{it=='a'.code.toByte()})}
                val tail=ByteArray(6);assertEquals(6,input.read(tail));assertEquals("X tail",tail.toString(Charsets.UTF_8));assertEquals(-1,input.read())
            }
            assertTrue(metrics.patternCachePeak<=8);assertTrue(metrics.prefixCachePeak<=8)
            assertTrue(metrics.patternAccesses<=8*(before.bytes+args.search!!.bytes))
        }finally{root.toFile().deleteRecursively()}
    }

    @Test fun candidateAppendCancellationDiskFailureAndLateSealRecoveryStayExplicit() {
        for(mode in listOf("append-cancel","output-enospc","after-seal-cancel","cleanup-failure")) {
            val root=Files.createTempDirectory("exact-output-failure-")
            try {
                val storeRoot=root.resolve("store");val scratch=Files.createDirectory(root.resolve("scratch"))
                val base=OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0);var armed=false
                fun parts()=if(Files.exists(storeRoot))Files.list(storeRoot).use{files->files.filter{it.fileName.toString().endsWith(".part")}.toList()}else emptyList()
                val storeDisk=object:OwnedResponseContentStore.DiskAdmission {
                    override fun reserve(bytes:Long):AutoCloseable {
                        if(armed && mode=="output-enospc" && parts().isNotEmpty())throw java.io.IOException("output ENOSPC")
                        return base.reserve(bytes)
                    }
                }
                val store=OwnedResponseContentStore(storeRoot,storeDisk,1024)
                val session=store.authorize(OwnedResponseContentStore.Owner("s","r","c"))
                val raw=source(session,"""{"path":"a.txt","search":"old","replace":"new"}""",OwnedResponseContentStore.Kind.TOOL_ARGUMENTS)
                val args=StreamedFileArgumentExtractor.extract(session,raw.handle,StreamedFileArgumentExtractor.Tool.EDIT_FILE,"c")
                val before=source(session,"prefix-".repeat(3000)+"old tail",OwnedResponseContentStore.Kind.TOOL_CONTENT)
                fun metaCount()=Files.list(storeRoot).use{files->files.filter{it.fileName.toString().endsWith(".meta")}.count()}
                val baseline=metaCount();armed=true
                val check:()->Unit={
                    if(mode=="append-cancel" && parts().any{Files.size(it)>0})throw java.util.concurrent.CancellationException("during append")
                    if(metaCount()>baseline) {
                        if(mode=="after-seal-cancel")throw java.util.concurrent.CancellationException("after seal")
                        if(mode=="cleanup-failure")Files.list(scratch).use{dirs->dirs.findFirst().ifPresent{dir->Files.write(dir.resolve("unexpected-owned-test-entry"),"keep".toByteArray())}}
                    }
                }
                val failure=assertThrows(Exception::class.java){StreamedExactEdit.prepare(args,session,before,session,scratch,base,check)}
                val handle=when(failure){is StreamedExactEdit.PreparationCancelled->failure.recoveryHandle;is StreamedExactEdit.PreparationFailure->failure.recoveryHandle;else->null}
                assertNotNull("mode=$mode error=$failure",handle)
                armed=false
                if(mode=="after-seal-cancel" || mode=="cleanup-failure")assertEquals(OwnedResponseContentStore.State.COMPLETE,session.openCompleted(handle!!).content.state)
                else {assertEquals(baseline,metaCount());assertTrue(session.hasRecoverableStage(handle!!))}
                if(mode!="cleanup-failure")Files.list(scratch).use{assertEquals(0L,it.count())}
                else Files.walk(scratch).use{assertTrue(it.anyMatch{p->p.fileName.toString()=="unexpected-owned-test-entry"})}
                before.verify(session)
            }finally{root.toFile().deleteRecursively()}
        }
    }

}
