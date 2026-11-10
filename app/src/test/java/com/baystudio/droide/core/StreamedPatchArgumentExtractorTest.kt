package com.baystudio.droide.core

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamedPatchArgumentExtractorTest {
    private val owner=OwnedResponseContentStore.Owner("session","run","call")
    private fun drain(i:InputStream){val b=ByteArray(8192);while(i.read(b)>=0){}}
    private fun fixture(block:(Path,OwnedResponseContentStore.Session)->Unit){
        val p=Files.createTempDirectory("patch-extract-")
        try{block(p,OwnedResponseContentStore(p,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),32).authorize(owner))}
        finally{p.toFile().deleteRecursively()}
    }
    private fun raw(s:OwnedResponseContentStore.Session,text:String)=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{w->
        var offset=0
        while(offset<text.length){val end=minOf(offset+(if(text.length>100000)8192 else 17),text.length);w.append(text.substring(offset,end));offset=end}
        w.sealCompleted(::drain).content.handle
    }
    private fun args(s:OwnedResponseContentStore.Session,text:String)=raw(s,buildJsonObject{put("patchText",text)}.toString())
    private val prefix="*** Begin Patch\n*** Add File: a\n+"
    private val suffix="\n*** End Patch"
    private fun patch(payload:String)=prefix+payload+suffix
    private fun decode(s:OwnedResponseContentStore.Session,h:OwnedResponseContentStore.Handle)=
        StreamedPatchArgumentExtractor.materialize(s,StreamedPatchArgumentExtractor.extract(s,h,"call"))
    private fun count(p:Path)=Files.list(p).use{it.count()}
    @Test fun exactTwoDialectTextEscapesWhitespaceAndSourceMetadata()=fixture {p,s->
        for(text in listOf("*** Begin Patch\n*** Add File: a\n+🌏\\\"\t\n*** End Patch", "*** Update File: a\n*** Search: old\n*** Replace: new")){
            val h=raw(s," \t\r\n{\"patch\\u0054ext\" : ${JsonPrimitive(text)} }\r\n")
            val before=count(p);val prepared=StreamedPatchArgumentExtractor.extract(s,h,"call")
            assertEquals(text,StreamedPatchArgumentExtractor.materialize(s,prepared));assertEquals("apply_patch",prepared.toolName)
            assertEquals(h,prepared.originalArguments.handle);prepared.originalArguments.verify(s)
            assertEquals(before,count(p));assertFalse(prepared.toString().contains("Begin Patch"))
        }
    }
    @Test fun hugeEscapedWireIsDecodedWithoutFullJsonProjection()=fixture {_,s->
        val h=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{w->
            w.append("{\"patchText\":\"" + JsonPrimitive(prefix).toString().drop(1).dropLast(1));repeat(400){w.append("\\u0001".repeat(1000))};w.append(JsonPrimitive(suffix).toString().drop(1).dropLast(1)+"\"}")
            w.sealCompleted(::drain).content.handle
        }
        val p=StreamedPatchArgumentExtractor.extract(s,h,"call")
        assertTrue(p.originalArguments.bytes>2_000_000)
        assertEquals(patch("\u0001".repeat(400000)),StreamedPatchArgumentExtractor.materialize(s,p))
    }
    @Test fun existingTwoMillionDecodedUtf16BoundaryUnchanged()=fixture {_,s->
        val exact=patch("x".repeat(2_000_000-prefix.length-suffix.length))
        assertEquals(exact,decode(s,args(s,exact)))
        val failure=assertThrows(StreamedPatchArgumentExtractor.ExtractionFailure::class.java){StreamedPatchArgumentExtractor.extract(s,args(s,exact+"x"),"call")}
        assertEquals("Patch too large",failure.cause!!.message)
        val units=2_000_000-prefix.length-suffix.length
        val unicode=patch("🌏".repeat(units/2)+"x".repeat(units%2))
        assertEquals(unicode,decode(s,args(s,unicode)))
    }
    @Test fun malformedSchemaDuplicateTrailingAndUnicodeRejectWithoutDerivedSpools()=fixture {p,s->
        val invalid=listOf("{}","[]","null","{\"patchText\":null}","{\"patchText\":1}","{\"patchText\":[]}",
            "{\"patchText\":\"x\",\"patchText\":\"y\"}","{\"patchText\":\"x\",\"patch\\u0054ext\":\"y\"}",
            "{\"patchText\":\"x\",\"extra\":{}}","{\"patchText\":\"x\",}","{\"patchText\":\"x\"}{}",
            "{\"patchText\":\"\\uD800\"}","{\"patchText\":\"\\uDC00\"}","{\"patchText\":\"\\q\"}",
            "{\"patchText\":\"x\nraw\"}","{\"patchText\":\"unterminated}")
        invalid.forEach{json->val h=raw(s,json);val before=count(p)
            assertThrows(StreamedPatchArgumentExtractor.ExtractionFailure::class.java){StreamedPatchArgumentExtractor.extract(s,h,"call")}
            assertEquals(before,count(p))
        }
    }
    @Test fun surrogatePairAcrossDecodedFragmentAndIngressBoundariesIsExact()=fixture {_,s->
        val text=patch("x".repeat(2047-prefix.length)+"🌏"+"漢".repeat(3000))
        val h=raw(s,buildJsonObject{put("patchText",text)}.toString().replace("🌏","\\uD83C\\uDF0F"))
        assertEquals(text,decode(s,h))
    }
    @Test fun cancellationKeepsOriginalAndNeverWritesDerivedState()=fixture {p,s->
        val h=args(s,"x".repeat(1_000_000));val before=count(p);var calls=0
        assertThrows(CancellationException::class.java){StreamedPatchArgumentExtractor.extract(s,h,"call"){if(++calls>10)throw CancellationException()}}
        assertEquals(before,count(p));assertEquals(OwnedResponseContentStore.State.COMPLETE,s.open(h).state)
    }
    @Test fun malformedPatchSyntaxRejectedDuringMetadataAdmission()=fixture {_,s->
        for(text in listOf("", "not a patch", "*** Begin Patch\n*** Add File: a\nmissing-plus\n*** End Patch")) {
            assertThrows(StreamedPatchArgumentExtractor.ExtractionFailure::class.java){StreamedPatchArgumentExtractor.extract(s,args(s,text),"call")}
        }
    }
    @Test fun metadataOnlyPreparedDoesNotRetainDecodedTextAndMaterializeRechecksIdentity()=fixture {_,s->
        val text=patch("x".repeat(200000));val h=args(s,text)
        val prepared=List(20){StreamedPatchArgumentExtractor.extract(s,h,"call")}
        assertEquals(setOf("callId","originalArguments"),StreamedPatchArgumentExtractor.Prepared::class.java.declaredFields.filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
        assertEquals(text,StreamedPatchArgumentExtractor.materialize(s,prepared.last()))
        val source=prepared.first().originalArguments
        val forged=StreamedPatchArgumentExtractor.Prepared("call",StreamedFileArgumentExtractor.Source(source.handle,source.bytes,source.utf16Units,"0".repeat(64),source.kind))
        assertThrows(StreamedPatchArgumentExtractor.ExtractionFailure::class.java){StreamedPatchArgumentExtractor.materialize(s,forged)}
    }
    @Test fun incompleteForeignOwnerWrongKindAndMissingSourceReject()=fixture {p,s->
        val partial=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{it.append("{}");it.sealIncomplete()}
        assertTrue(runCatching{StreamedPatchArgumentExtractor.extract(s,partial.handle,"call")}.isFailure)
        val wrong=s.begin(OwnedResponseContentStore.Kind.ANSWER).use{it.append("{}");it.sealCompleted(::drain).content}
        assertTrue(runCatching{StreamedPatchArgumentExtractor.extract(s,wrong.handle,"call")}.isFailure)
        val h=args(s,"x".repeat(1000))
        val other=OwnedResponseContentStore(p,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0)).authorize(owner.copy(callId="other"))
        assertTrue(runCatching{StreamedPatchArgumentExtractor.extract(other,h,"call")}.isFailure)
        Files.list(p).use{paths->paths.filter{it.toString().endsWith(".blob")}.forEach(Files::delete)}
        assertTrue(runCatching{StreamedPatchArgumentExtractor.extract(s,h,"call")}.isFailure)
    }
}
