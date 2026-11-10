package com.baystudio.droide.core

import java.io.InputStream
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StreamedCommandArgumentExtractorTest {
    private val owner = OwnedResponseContentStore.Owner("session", "run", "call")
    private fun drain(input: InputStream) { val b=ByteArray(8192); while(input.read(b)>=0) {} }
    private fun fixture(block: (OwnedResponseContentStore.Session)->Unit) {
        val root=Files.createTempDirectory("source-command-")
        try { block(OwnedResponseContentStore(root,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),32).authorize(owner)) }
        finally { root.toFile().deleteRecursively() }
    }
    private fun raw(s:OwnedResponseContentStore.Session, text:String)=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { w ->
        var offset=0
        while(offset<text.length) { val end=minOf(offset+8192,text.length); w.append(text.substring(offset,end)); offset=end }
        w.sealCompleted(::drain).content.handle
    }
    private fun prepared(s:OwnedResponseContentStore.Session,text:String,name:String="run_command")=
        StreamedCommandArgumentExtractor.extract(s,raw(s,text),"call",name)
    private fun args(command:String,key:String="command",extra:Map<String,JsonElement> = emptyMap())=
        JsonObject(linkedMapOf<String,JsonElement>(key to JsonPrimitive(command)).apply{putAll(extra)}).toString()
    @Test fun hugeEscapedWireMaterializesOnlyExactBoundedCommandForAllNames()=fixture { s ->
        for(name in listOf("run_command","bash","exec_command")) {
            val key=if(name=="exec_command")"cmd" else "command"
            val wire="{\"$key\":\": #"+"\\u0078".repeat(23000)+"\\nprintf marker\"}"
            val p=prepared(s,wire,name)
            assertTrue(p.originalArguments.utf16Units>138000)
            assertEquals(name,p.toolName)
            val decoded=StreamedCommandArgumentExtractor.materialize(s,p)
            assertEquals(": #"+"x".repeat(23000)+"\nprintf marker",decoded[key]!!.jsonPrimitive.content)
            assertEquals(wire,s.openBytes(p.originalArguments.handle).bufferedReader().use{it.readText()})
            assertEquals(setOf("callId","toolName","originalArguments","semanticDigest"),p.javaClass.declaredFields
                .filterNot{java.lang.reflect.Modifier.isStatic(it.modifiers)}.map{it.name}.toSet())
        }
    }
    @Test fun semanticIdentityIgnoresEscapingFieldOrderDefaultsAndBashAlias()=fixture { s ->
        val first=prepared(s,"{\"command\":\"x\"}")
        val second=prepared(s,"{\"yield_time_ms\":5000,\"timeout_ms\":null,\"background\":false,\"requires_tty\":false,\"command\":\"\\u0078\"}","bash")
        assertEquals(first.argumentIdentity,second.argumentIdentity)
        assertNotEquals(first.originalArguments.sha256,second.originalArguments.sha256)
        assertNotEquals(first.argumentIdentity,prepared(s,args("x ")).argumentIdentity)
        assertNotEquals(first.argumentIdentity,prepared(s,args("x",extra=mapOf("background" to JsonPrimitive(true)))).argumentIdentity)
    }
    @Test fun unusedYieldPreservedButIgnoredBySemanticGuard()=fixture { s ->
        val base=mapOf("background" to JsonPrimitive(true))
        val a=prepared(s,args("x",extra=base))
        for(value in listOf(JsonPrimitive(-1),JsonPrimitive("ignored"),JsonPrimitive(true),JsonNull,JsonPrimitive(30001))) {
            val p=prepared(s,args("x",extra=base+mapOf("yield_time_ms" to value)))
            assertEquals(a.argumentIdentity,p.argumentIdentity)
            assertEquals(value,StreamedCommandArgumentExtractor.materialize(s,p)["yield_time_ms"])
        }
    }
    @Test fun decodedManagerAndTtyBoundariesAreActualConsumerLimits()=fixture { s ->
        for((size,tty) in listOf(128000 to false,32000 to true)) {
            val extras=mapOf("requires_tty" to JsonPrimitive(tty))
            assertEquals(size,StreamedCommandArgumentExtractor.materialize(s,prepared(s,args("x".repeat(size),extra=extras)))["command"]!!.jsonPrimitive.content.length)
            assertThrows(StreamedCommandArgumentExtractor.ExtractionFailure::class.java) { prepared(s,args("x".repeat(size+1),extra=extras)) }
        }
    }
    @Test fun completeJsonUnicodeDuplicateTypesAndNulAreStrict()=fixture { s ->
        val invalid=listOf("{}","[]","{\"command\":null}","{\"command\":123}","{\"command\":\" \"}",
            "{\"command\":\"x\",\"comm\\u0061nd\":\"x\"}","{\"command\":\"x\"}{}","{\"command\":\"x\",}",
            "{\"command\":\"\\u0000\"}","{\"command\":\"\\uD800\"}","{\"command\":\"\\uDC00\"}",
            "{\"command\":\"x\",\"requires_tty\":\"true\"}","{\"command\":\"x\",\"background\":null}",
            "{\"command\":\"x\",\"unknown\":true}","{\"command\":\"x\",\"yield_time_ms\":{}}",
            "{\"command\":\"x\",\"timeout_ms\":01}","{\"command\":\"x\",\"timeout_ms\":1.0}",
            "{\"command\":\"x\",\"timeout_ms\":9223372036854775808}","{\"command\":\"x\",\"yield_time_ms\":01}",
            "{\"command\":\"x\",\"yield_time_ms\":30001}","{\"command\":\"x\",\"timeout_ms\":999}")
        invalid.forEach { wire -> assertThrows(wire,StreamedCommandArgumentExtractor.ExtractionFailure::class.java) { prepared(s,wire) } }
    }
    @Test fun exactUnicodeAcrossChunksAndExecOptionsDefaults()=fixture { s ->
        val command="x".repeat(2047)+"🌏\r\n\t\\漢"
        val wire=args(command,"cmd",mapOf("keep_stdin_open" to JsonPrimitive(true),"timeout_ms" to JsonPrimitive(1000),"yield_time_ms" to JsonPrimitive(0)))
        assertEquals(Json.parseToJsonElement(wire),StreamedCommandArgumentExtractor.materialize(s,prepared(s,wire,"exec_command")))
        val implicit=prepared(s,args("x","cmd"),"exec_command")
        val explicit=prepared(s,args("x","cmd",mapOf("keep_stdin_open" to JsonPrimitive(false),"requires_tty" to JsonPrimitive(false),"yield_time_ms" to JsonPrimitive(1000))),"exec_command")
        assertEquals(implicit.semanticDigest,explicit.semanticDigest)
    }
    @Test fun incompleteWrongKindOwnerAndCancellationCannotMaterialize()=fixture { s ->
        val incomplete=s.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use{it.append("{}");it.sealIncomplete()}
        assertTrue(runCatching{StreamedCommandArgumentExtractor.extract(s,incomplete.handle,"call","run_command")}.isFailure)
        val wrong=s.begin(OwnedResponseContentStore.Kind.ANSWER).use{it.append(args("x"));it.sealCompleted(::drain).content.handle}
        assertTrue(runCatching{StreamedCommandArgumentExtractor.extract(s,wrong,"call","run_command")}.isFailure)
        val handle=raw(s,args("x".repeat(40000)));var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            StreamedCommandArgumentExtractor.extract(s,handle,"call","run_command") { if(++checks>10) throw java.util.concurrent.CancellationException() }
        }
        val p=StreamedCommandArgumentExtractor.extract(s,handle,"call","run_command")
        assertThrows(java.util.concurrent.CancellationException::class.java) { StreamedCommandArgumentExtractor.materialize(s,p){throw java.util.concurrent.CancellationException()} }
    }
    @Test fun independentSessionOwnerCannotReadSource() {
        val root=Files.createTempDirectory("command-owner-")
        try {
            val store=OwnedResponseContentStore(root,OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),32)
            val own=store.authorize(owner)
            val foreign=store.authorize(owner.copy(sessionId="other"))
            val handle=raw(own,args("x"))
            assertTrue(runCatching{StreamedCommandArgumentExtractor.extract(foreign,handle,"call","run_command")}.isFailure)
            val p=StreamedCommandArgumentExtractor.extract(own,handle,"call","run_command")
            assertTrue(runCatching{StreamedCommandArgumentExtractor.materialize(foreign,p)}.isFailure)
            val forged=StreamedCommandArgumentExtractor.Prepared(p.callId,p.toolName,p.originalArguments,"0".repeat(64))
            assertThrows(StreamedCommandArgumentExtractor.ExtractionFailure::class.java) { StreamedCommandArgumentExtractor.materialize(own,forged) }
        } finally { root.toFile().deleteRecursively() }
    }

}
