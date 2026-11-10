package com.baystudio.droide.core

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamedStdinArgumentExtractorTest {
    private val owner=OwnedResponseContentStore.Owner("session","run","call")
    private fun drain(i:InputStream){val b=ByteArray(8192);while(i.read(b)>=0){}}
    private fun fixture(block:(Path,OwnedResponseContentStore.Session)->Unit){
        val p=Files.createTempDirectory("stdin-extract-")
        try{block(p,OwnedResponseContentStore(p,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),32).authorize(owner))}
        finally{p.toFile().deleteRecursively()}
    }
    private fun raw(s:OwnedResponseContentStore.Session,text:String)=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{w->
        var offset=0
        while(offset<text.length){val end=minOf(offset+(if(text.length>100000)8192 else 17),text.length);w.append(text.substring(offset,end));offset=end}
        w.sealCompleted(::drain).content.handle
    }
    private fun args(s:OwnedResponseContentStore.Session,text:String)=raw(s,buildJsonObject{put("session_id","bg-12345678-abc-1");put("chars",text)}.toString())
    private fun decode(s:OwnedResponseContentStore.Session,h:OwnedResponseContentStore.Handle)=
        StreamedStdinArgumentExtractor.materialize(s,StreamedStdinArgumentExtractor.extract(s,h,"call"))
    @Test fun exactEscapesDefaultsAndFlagsPreserved()=fixture{_,s->
        val json=buildJsonObject{put("session_id","bg-test-1");put("chars","🌏\n\t\\");put("close_stdin",true);put("output_cursor",20);put("yield_time_ms",30000);put("wait_for_completion",true);put("interrupt",false)}
        assertEquals(json,decode(s,raw(s," \n$json\t")))
        val only=buildJsonObject{put("session_id","bg-test-1")}
        assertEquals(only,decode(s,raw(s,only.toString())))
        val explicit=buildJsonObject{put("session_id","bg-test-1");put("chars","");put("close_stdin",false);put("interrupt",false);put("wait_for_completion",false);put("yield_time_ms",0);put("output_cursor",0)}
        assertEquals(explicit,decode(s,raw(s,explicit.toString())))
    }
    @Test fun escapedWireAbove128kRetainsBoundedOriginalChars()=fixture{_,s->
        val h=raw(s,"{\"session_id\":\"bg-test-1\",\"chars\":\""+"\\u0001".repeat(65536)+"\"}")
        val p=StreamedStdinArgumentExtractor.extract(s,h,"call")
        assertTrue(p.originalArguments.bytes>128000)
        assertEquals("\u0001".repeat(65536),StreamedStdinArgumentExtractor.materialize(s,p)["chars"]!!.jsonPrimitive.content)
        assertEquals(setOf("callId","originalArguments"),p.javaClass.declaredFields.filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
    }
    @Test fun exactUtf8NotUtf16Limit()=fixture{_,s->
        for(text in listOf("x".repeat(65536),"🌏".repeat(16384),"漢".repeat(21845)+"x")){
            assertEquals(text,decode(s,args(s,text))["chars"]!!.jsonPrimitive.content)
            assertThrows(StreamedStdinArgumentExtractor.ExtractionFailure::class.java){decode(s,args(s,text+"x"))}
        }
    }
    @Test fun malformedSchemaAndConflictingFlagsReject()=fixture{_,s->
        val tails=listOf("\"chars\":null","\"chars\":123","\"chars\":{}","\"chars\":\"\\uD800\"","\"chars\":\"\\uDC00\"",
            "\"chars\":\"a\",\"chars\":\"b\"","\"chars\":\"a\",\"ch\\u0061rs\":\"b\"","\"interrupt\":true,\"chars\":\"a\"",
            "\"interrupt\":true,\"close_stdin\":true","\"close_stdin\":\"true\"","\"yield_time_ms\":30001","\"yield_time_ms\":-1","\"output_cursor\":-1",
            "\"output_cursor\":01","\"output_cursor\":1.0","\"output_cursor\":9223372036854775808","\"unknown\":true")
        val invalid=tails.map{"{\"session_id\":\"bg-test-1\",$it}"}+listOf("{}","[]","{\"session_id\":\"\"}","{\"session_id\":\"a\",}","{\"session_id\":\"a\"}{}")
        invalid.forEach{assertThrows(StreamedStdinArgumentExtractor.ExtractionFailure::class.java){decode(s,raw(s,it))}}
    }
    @Test fun fragmentBoundaryUnicodeAndLongCursor()=fixture{_,s->
        val text="x".repeat(2047)+"🌏"+"漢"
        assertEquals(text,decode(s,args(s,text))["chars"]!!.jsonPrimitive.content)
        assertEquals(Long.MAX_VALUE,decode(s,raw(s,"{\"session_id\":\"a\",\"output_cursor\":9223372036854775807}"))["output_cursor"]!!.jsonPrimitive.long)
    }
    @Test fun incompleteAndWrongKindRejectWithoutDerivedContent()=fixture{_,s->
        val partial=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{it.append("{}");it.sealIncomplete()}
        assertTrue(runCatching{StreamedStdinArgumentExtractor.extract(s,partial.handle,"call")}.isFailure)
        val answer=s.begin(OwnedResponseContentStore.Kind.ANSWER).use{it.append("{}");it.sealCompleted(::drain).content.handle}
        assertTrue(runCatching{StreamedStdinArgumentExtractor.extract(s,answer,"call")}.isFailure)
    }
    @Test fun cancellationAndForeignOwnerKeepOriginalOnly()=fixture{p,s->
        val h=args(s,"x".repeat(60000));var n=0
        assertThrows(CancellationException::class.java){StreamedStdinArgumentExtractor.extract(s,h,"call"){if(++n>10)throw CancellationException()}}
        assertEquals(OwnedResponseContentStore.State.COMPLETE,s.open(h).state)
        val other=OwnedResponseContentStore(p,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)).authorize(owner.copy(callId="other"))
        assertTrue(runCatching{StreamedStdinArgumentExtractor.extract(other,h,"call")}.isFailure)
        val prepared=StreamedStdinArgumentExtractor.extract(s,h,"call");val source=prepared.originalArguments
        val forged=StreamedStdinArgumentExtractor.Prepared("call",StreamedFileArgumentExtractor.Source(source.handle,source.bytes,source.utf16Units,"0".repeat(64),source.kind))
        assertThrows(StreamedStdinArgumentExtractor.ExtractionFailure::class.java){StreamedStdinArgumentExtractor.materialize(s,forged)}
    }
}
