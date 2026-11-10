package com.baystudio.droide.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class StreamingRetentionTest {
 @Test fun boundedFramingDoesNotCountDisposableTraffic() {
   val framing=ProviderSseFraming(128)
   repeat(40_000) { assertNull(framing.line(":"+"x".repeat(100))); assertNull(framing.line("")) }
   assertNull(framing.line("data: {")); assertNull(framing.line("data: \"ok\":true}"))
   assertEquals("{\n\"ok\":true}",framing.line(""))
 }
 @Test fun multilineEventCannotGrowWithoutBound() {
   val framing=ProviderSseFraming(64)
   repeat(5){framing.line("data: 1234567890")}
   assertThrows(IllegalStateException::class.java){ repeat(5){framing.line("data: 1234567890")} }
 }
 @Test fun retainedBudgetReplacesSnapshotsAndRejectsGrowthBeforeAppend() {
   val b=ProviderPayloadLimits.RetainedBudget(256)
   repeat(50_000){b.replace("usage",10)}
   val target=StringBuilder("prefix")
   assertThrows(IllegalStateException::class.java){b.append(target,"x".repeat(256))}
   assertEquals("prefix",target.toString())
 }
 @Test fun heartbeatDoesNotResetIdleButRealProgressDoes() {
   var now=0L
   val guard=ProviderStreamIdleGuard(100){now}
   repeat(10){ now+=90_000_000;guard.check();guard.progress() }
   now+=100_000_000
   assertThrows(IllegalStateException::class.java){guard.check()}
 }
 @Test(timeout=90_000) fun allFourTransportsAcceptLargeWireWithSmallFinalOutput() = runBlocking {
   val count=15_000
   val expected="x".repeat(count)
   for(protocol in ProviderWireProtocol.entries) {
     val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0)
     val model=when(protocol){ProviderWireProtocol.OPENAI_RESPONSES->"gpt-5";ProviderWireProtocol.ANTHROPIC_MESSAGES->"claude-sonnet-4-5";ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"gemini-2.5-flash";else->"fixture"}
     val provider=AiProvider(id="retention.${UUID.randomUUID()}",name="fixture",baseUrl="http://127.0.0.1:${server.address.port}/v1",model=model,free=true,needsKey=false,helpUrl="",note="test",wireProtocol=protocol,authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
     fun event(s:String)="data: $s\n\n"
     val delta=when(protocol){
       ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->"""{"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":null}]}"""
       ProviderWireProtocol.OPENAI_RESPONSES->"""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"x"}"""
       ProviderWireProtocol.ANTHROPIC_MESSAGES->"""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}"""
       ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"candidates":[{"content":{"role":"model","parts":[{"text":"x"}]}}]}"""
     }
     val padded=delta.dropLast(1)+",\"ignored_metadata\":\""+"m".repeat(200)+"\"}"
     val finish=when(protocol){
       ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->event("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
       ProviderWireProtocol.OPENAI_RESPONSES->event("""{"type":"response.completed","response":{"id":"r1","model":"$model","status":"completed","output":[{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"$expected"}]}]}}""")
       ProviderWireProtocol.ANTHROPIC_MESSAGES->event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")+event("""{"type":"message_stop"}""")
       ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->event("""{"candidates":[{"finishReason":"STOP"}]}""")
     }
     assertTrue(event(padded).length.toLong()*count>2_000_000)
     server.createContext("/"){ex->
       try {ex.requestBody.close();ex.responseHeaders.set("Content-Type","text/event-stream");ex.sendResponseHeaders(200,0)
         ex.responseBody.use{out->
           if(protocol==ProviderWireProtocol.ANTHROPIC_MESSAGES)out.write(event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""").toByteArray())
           repeat(count){out.write(": keepalive\n\n".toByteArray());out.write(event(padded).toByteArray())}
           out.write(finish.toByteArray())
         }
       } catch(_:java.io.IOException){} finally{ex.close()}
     }
     server.start();ProviderRegistry.installConfigured(listOf(provider));ProviderModelCertificationRegistry.certify(provider,listOf(model))
     try {
       val request="""{"model":"$model","messages":[{"role":"user","content":"hello"}]}"""
       var emitted=0
       if (java.lang.Boolean.getBoolean("droide.expectLegacyWireCap")) {
         val failure=runCatching { LlmClient().chatCompletionsStream(provider.id,provider.baseUrl,"",model,request){} }.exceptionOrNull()
         assertNotNull("Legacy must fail $protocol",failure)
         assertTrue(failure!!.message.orEmpty(),failure.message.orEmpty().contains("streamed response is too large"))
         println("REPRODUCED legacy $protocol: ${failure.message}")
         continue
       }
       val result=LlmClient().chatCompletionsStream(provider.id,provider.baseUrl,"",model,request){if(it is LlmStreamEvent.TextDelta)emitted+=it.text.length}
       val content=Json.parseToJsonElement(result.responseBody).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
       assertEquals(protocol.name,expected,content);assertEquals(protocol.name,count,emitted)
       println("PASS $protocol wire>${event(padded).length.toLong()*count} final=$emitted")
     } finally{ProviderModelCertificationRegistry.revoke(provider.id);ModelCapabilityRegistry.revokeRuntime(provider.id);ProviderRegistry.installConfigured(emptyList());server.stop(0)}
   }
 }
}
