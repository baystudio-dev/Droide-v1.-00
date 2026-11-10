package com.baystudio.droide.core

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import okio.*
import java.util.concurrent.TimeUnit

class ProviderEventIdleTest {
 @Test fun workspaceProviderTimeoutIsExplicitValidatedAndCopied() {
   fun config(value:String)="""{"providers":[{"id":"custom.idle","name":"Idle","baseUrl":"https://example.com/v1","streamIdleTimeoutMs":$value}]}"""
   val provider=ProviderEndpointCatalog.parseWorkspaceConfig(config("1200")).single()
   assertEquals(1200L,ProviderStreamPolicy.idleTimeoutMillis(provider))
   assertEquals(1200L,ProviderRegistry.prepareConfigured(listOf(provider)).providers.single().streamIdleTimeoutMs)
   for(value in listOf("0","-1","1.2","\"1200\"","null","9223372036854775807"))
     assertThrows(IllegalArgumentException::class.java){ProviderEndpointCatalog.parseWorkspaceConfig(config(value))}
 }
 @Test fun bodyReadRestoresTimeoutAndHonorsEarlierDeadlineEvenOnFailure() {
   val method=LlmClient::class.java.getDeclaredMethod("readStreamLine",BufferedSource::class.java,ProviderStreamIdleGuard::class.java).apply{isAccessible=true}
   for(fail in listOf(false,true)) {
     val timeout=Timeout().timeout(12,TimeUnit.MILLISECONDS).deadlineNanoTime(System.nanoTime()+5_000_000_000L)
     val oldDeadline=timeout.deadlineNanoTime()
     val guard=ProviderStreamIdleGuard(10_000)
     val upstream=object:Source {
       override fun timeout()=timeout
       override fun close()=Unit
       override fun read(sink:Buffer,byteCount:Long):Long {
         assertEquals(oldDeadline,timeout.deadlineNanoTime())
         assertEquals(TimeUnit.MILLISECONDS.toNanos(10_000),timeout.timeoutNanos())
         if(fail)throw java.io.IOException("simulated read error")
         sink.writeUtf8("x\n");return 2
       }
     }
     upstream.buffer().use {source->
       if(fail)assertThrows(java.lang.reflect.InvocationTargetException::class.java){method.invoke(LlmClient(),source,guard)}
       else assertEquals("x",method.invoke(LlmClient(),source,guard))
       assertEquals(oldDeadline,timeout.deadlineNanoTime())
       assertEquals(TimeUnit.MILLISECONDS.toNanos(12),timeout.timeoutNanos())
     }
   }
 }
 @Test(timeout=15000) fun metadataEventsKeepAllProtocolsAliveWithoutVisibleOutput() = runBlocking {
   for(protocol in ProviderWireProtocol.entries) fixture(protocol,Behavior.METADATA) { client,p,request ->
     val result=client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){}
     assertTrue(result.responseBody.contains("ok"))
   }
 }
 @Test(timeout=15000) fun typedHeartbeatsKeepAllProtocolsAliveUntilTheRealCompletion() = runBlocking {
   for(protocol in ProviderWireProtocol.entries) fixture(protocol,Behavior.HEARTBEATS) { client,p,request ->
     val result=client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){}
     assertTrue(result.responseBody.contains("ok"))
   }
 }
 @Test(timeout=15000) fun emptyProxyFlushesDoNotReplaceRealEventProgress() = runBlocking {
   for(protocol in ProviderWireProtocol.entries) fixture(protocol,Behavior.EMPTY_FLUSHES) { client,p,request ->
     assertNotNull(runCatching{client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){}}.exceptionOrNull())
   }
 }
 @Test(timeout=15000) fun commentsDoNotExtendNextEventDeadlineAcrossAllProtocols() = runBlocking {
   for(protocol in ProviderWireProtocol.entries) fixture(protocol,Behavior.COMMENTS) { client,p,request ->
     val failure=runCatching{client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){}}.exceptionOrNull()
     assertNotNull(protocol.name,failure)
     assertFalse(failure is CancellationException)
   }
 }
 @Test(timeout=15000) fun firstEventWaitUsesConfiguredTimeoutAcrossAllProtocols() = runBlocking {
   for(protocol in ProviderWireProtocol.entries) fixture(protocol,Behavior.SILENT) { client,p,request ->
     val failure=runCatching{client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){}}.exceptionOrNull()
     assertNotNull(protocol.name,failure)
   }
 }
 @Test(timeout=15000) fun slowEventConsumerDoesNotSpendNetworkIdleBudget() = runBlocking {
   fixture(ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS,Behavior.METADATA) { client,p,request ->
     val result=client.chatCompletionsStream(p.id,p.baseUrl,"",p.model,request){ if(it is LlmStreamEvent.TextDelta)delay(500) }
     assertTrue(result.responseBody.contains("ok"))
   }
 }
 private enum class Behavior { METADATA,COMMENTS,SILENT,HEARTBEATS,EMPTY_FLUSHES }
 private suspend fun fixture(protocol:ProviderWireProtocol,behavior:Behavior,block:suspend(LlmClient,AiProvider,String)->Unit) {
   val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0)
   val model=when(protocol){ProviderWireProtocol.OPENAI_RESPONSES->"gpt-5";ProviderWireProtocol.ANTHROPIC_MESSAGES->"claude-sonnet-4-5";ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"gemini-2.5-flash";else->"fixture"}
   val provider=AiProvider(id="idle.${UUID.randomUUID()}",name="fixture",baseUrl="http://127.0.0.1:${server.address.port}/v1",model=model,free=true,needsKey=false,helpUrl="",note="test",wireProtocol=protocol,authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY,streamIdleTimeoutMs=200)
   fun event(s:String)="data: $s\n\n"
   val metadata=when(protocol){ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->"""{"choices":[],"usage":{"total_tokens":1}}""";ProviderWireProtocol.OPENAI_RESPONSES->"""{"type":"response.in_progress"}""";ProviderWireProtocol.ANTHROPIC_MESSAGES->"""{"type":"ping"}""";ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"usageMetadata":{"totalTokenCount":1}}"""}
   val text=when(protocol){ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->"""{"choices":[{"delta":{"content":"ok"}}]}""";ProviderWireProtocol.OPENAI_RESPONSES->"""{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"ok"}""";ProviderWireProtocol.ANTHROPIC_MESSAGES->"""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""";ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->"""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}"""}
   val finish=when(protocol){
    ProviderWireProtocol.OPENAI_CHAT_COMPLETIONS->event("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")+"data: [DONE]\n\n"
    ProviderWireProtocol.OPENAI_RESPONSES->event("""{"type":"response.completed","response":{"id":"r1","model":"$model","status":"completed","output":[{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"ok"}]}]}}""")
    ProviderWireProtocol.ANTHROPIC_MESSAGES->event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")+event("""{"type":"message_stop"}""")
    ProviderWireProtocol.GOOGLE_GENERATE_CONTENT->event("""{"candidates":[{"finishReason":"STOP"}]}""")
   }
   server.createContext("/"){ex->try{
     ex.requestBody.close();ex.responseHeaders.set("Content-Type","text/event-stream");ex.sendResponseHeaders(200,0)
     ex.responseBody.use{out->
       if(behavior==Behavior.SILENT) {out.flush();Thread.sleep(450)} else repeat(6){
         val chunk=when(behavior){
           Behavior.METADATA -> event(metadata)
           Behavior.HEARTBEATS -> event("""{"type":"keepalive"}""")
           Behavior.EMPTY_FLUSHES -> event("null")+event(": keepalive")
           else -> ": heartbeat\n\n"
         }
         out.write(chunk.toByteArray());out.flush();Thread.sleep(80)
       }
       out.write((event(text)+finish).toByteArray())
     }
   }catch(_:java.io.IOException){}finally{ex.close()}}
   server.start();ProviderRegistry.installConfigured(listOf(provider));ProviderModelCertificationRegistry.certify(provider,listOf(model))
   try {block(LlmClient(),provider,"""{"model":"$model","messages":[{"role":"user","content":"hello"}]}""")}
   finally{ProviderModelCertificationRegistry.revoke(provider.id);ModelCapabilityRegistry.revokeRuntime(provider.id);ProviderRegistry.installConfigured(emptyList());server.stop(0)}
 }
}
