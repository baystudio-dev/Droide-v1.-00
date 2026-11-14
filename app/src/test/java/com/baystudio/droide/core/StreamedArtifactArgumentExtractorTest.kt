package com.baystudio.droide.core

import java.nio.file.Files
import java.nio.file.Path
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamedArtifactArgumentExtractorTest {
    private val owner=OwnedResponseContentStore.Owner("session","run","call")
    private fun drain(input:InputStream){val b=ByteArray(8192);while(input.read(b)>=0){}}
    private fun count(root:Path)=Files.list(root).use { it.filter { p->p.toString().endsWith(".meta") }.count() }
    private fun fixture(block:(Path,OwnedResponseContentStore.Session)->Unit){
        val root=Files.createTempDirectory("artifact-extract-")
        try{block(root,OwnedResponseContentStore(root,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},8192),32).authorize(owner))}
        finally{root.toFile().deleteRecursively()}
    }
    private fun args(s:OwnedResponseContentStore.Session,text:String)=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { w->
        // Include one-character fragments, splitting UTF16 surrogate pairs as a real provider may.
        text.forEach { w.append(it.toString()) };w.sealCompleted(::drain).content.handle
    }
    private fun read(s:OwnedResponseContentStore.Session,c:StreamedFileArgumentExtractor.Source)=s.openPages(c.handle).use { p->buildString {while(true)append(p.readPage(31)?:break)} }
    @Test fun exactTypeResourceAndUnicodeContentRemainSeparateFromDisplay()=fixture {_,s->
        val original="  report\n🌏  "
        val content="x".repeat(2047)+"🌏\n\u0000\"\\漢"
        val raw=buildJsonObject{put("type",original);put("content",content)}.toString()
        val prepared=StreamedArtifactArgumentExtractor.extract(s,args(s,raw),"call")
        assertEquals(original,prepared.originalType);assertEquals("report 🌏",prepared.displayType)
        assertEquals(content,read(s,prepared.content));assertEquals("artifact",prepared.toolName)
        prepared.originalArguments.verify(s);prepared.content.verify(s)
        assertFalse(prepared.toString().contains("report"))
    }
    @Test fun overLegacy500kStreamsExactlyWithoutWholeContentMaterialization()=fixture {_,s->
        val chunk="0123456789abcdef".repeat(512);val hash=MessageDigest.getInstance("SHA-256")
        val raw=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {w->
            w.append("{\"type\":\"report\",\"content\":\"")
            repeat(100){w.append(chunk);hash.update(chunk.toByteArray())};w.append("\"}");w.sealCompleted(::drain).content.handle
        }
        val p=StreamedArtifactArgumentExtractor.extract(s,raw,"call")
        assertEquals(819200L,p.content.bytes);assertEquals(hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)},p.content.sha256)
        p.content.verify(s)
    }
    @Test fun invalidSchemasDuplicatesDepthAndTrailingDataNeverSealDerivedContent()=fixture {root,s->
        val invalid=listOf("{}","[]","null","{\"type\":\"x\"}","{\"content\":\"x\"}",
            """{"type":1,"content":"x"}""","""{"type":"x","content":null}""","""{"type":"x","content":[]}""",
            """{"type":"x","content":"x","title":"unknown"}""","""{"type":"x","content":"x","ty\u0070e":"y"}""",
            """{"content":"x","type":"x","content":"duplicate"}""","""{"type":"x","content":"x"}{}""",
            """{"type":"x","content":"x",}""","""{"type":"x","content":"\uD800"}""",
            """{"type":"x","content":"\uDC00"}""","""{"type":"x","content":"unterminated}""",
            "{\"type\":\"x\",\"content\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+"}")
        invalid.forEach { raw->val h=args(s,raw);val before=count(root)
            assertThrows(StreamedArtifactArgumentExtractor.ExtractionFailure::class.java){StreamedArtifactArgumentExtractor.extract(s,h,"call")}
            assertEquals(before,count(root))
        }
    }
    @Test fun metadataBoundDoesNotTruncateOriginalPermissionTypeAndEmptyStringsAreValid()=fixture {_,s->
        val empty=StreamedArtifactArgumentExtractor.extract(s,args(s,"""{"type":"","content":""}"""),"call")
        assertEquals("",empty.originalType);assertEquals("artifact",empty.displayType);assertEquals(0L,empty.content.bytes)
        val boundary=StreamedArtifactArgumentExtractor.extract(s,args(s,buildJsonObject{put("type","a".repeat(79)+"🌏");put("content","")}.toString()),"call")
        assertEquals("a".repeat(79),boundary.displayType)
        val tooLong=buildJsonObject{put("type","t".repeat(4097));put("content","safe")}.toString()
        assertThrows(StreamedArtifactArgumentExtractor.ExtractionFailure::class.java){StreamedArtifactArgumentExtractor.extract(s,args(s,tooLong),"call")}
    }
    @Test fun incompleteWrongKindAndForeignOwnerAreRejected()=fixture {root,s->
        val partial=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {it.append("{}");it.sealIncomplete()}
        assertTrue(runCatching{StreamedArtifactArgumentExtractor.extract(s,partial.handle,"call")}.isFailure)
        val wrong=s.begin(OwnedResponseContentStore.Kind.ANSWER).use {it.append("{}");it.sealCompleted(::drain).content}
        assertTrue(runCatching{StreamedArtifactArgumentExtractor.extract(s,wrong.handle,"call")}.isFailure)
        val h=args(s,"""{"type":"x","content":"x"}""")
        val other=OwnedResponseContentStore(root,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)).authorize(owner.copy(callId="other"))
        assertTrue(runCatching{StreamedArtifactArgumentExtractor.extract(other,h,"call")}.isFailure)
    }
    @Test fun cancelBeforeSealAndAfterSealNeverReturnsPreparedAndReportsRetainedSource()=fixture {root,s->
        val raw=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use {it.append("{\"type\":\"x\",\"content\":\"${"x".repeat(150000)}\"}");it.sealCompleted(::drain).content.handle}
        val before=count(root);var calls=0
        assertThrows(CancellationException::class.java){StreamedArtifactArgumentExtractor.extract(s,raw,"call"){if(++calls>20)throw CancellationException()}}
        assertEquals(before,count(root))
        val failure=assertThrows(StreamedArtifactArgumentExtractor.ExtractionCancelled::class.java){
            StreamedArtifactArgumentExtractor.extract(s,raw,"call"){if(count(root)>before)throw CancellationException()}
        }
        assertEquals(1,failure.recoveryHandles.size);assertEquals(OwnedResponseContentStore.Kind.TOOL_CONTENT,s.open(failure.recoveryHandles.single()).kind)
    }
    @Test fun diskExhaustionDuringExtractionLeavesOriginalAndNoCompleteDerivedCandidate(){
        val root=Files.createTempDirectory("artifact-full-");var free=Long.MAX_VALUE
        try{
            val s=OwnedResponseContentStore(root,OwnedResponseContentStore.HeadroomAdmission({free},0),32).authorize(owner)
            val h=args(s,"""{"type":"x","content":"safe"}""");val before=count(root);free=0
            assertThrows(StreamedArtifactArgumentExtractor.ExtractionFailure::class.java){StreamedArtifactArgumentExtractor.extract(s,h,"call")}
            free=Long.MAX_VALUE;assertEquals(before,count(root));assertEquals(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS,s.openCompleted(h).content.kind)
        }finally{root.toFile().deleteRecursively()}
    }
}
