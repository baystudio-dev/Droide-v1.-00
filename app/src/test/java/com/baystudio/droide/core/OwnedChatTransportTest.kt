package com.baystudio.droide.core

import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OwnedChatTransportTest {
    @Test fun multiMillionPrivateReasoningTinyToolProjectsPublicAndReplaysExactNative() = runBlocking {
        val root=Files.createTempDirectory("owned-private-large-").toFile()
        val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0);val requests=AtomicInteger()
        val chunk="private-".repeat(1024);val args="""{"path":"a.txt","note":"quote\" slash\\ newline\n 🌍"}"""
        var exactReplay=false
        server.createContext("/chat/completions") { e ->
            val n=requests.incrementAndGet()
            val incoming=e.requestBody.use{it.readBytes().toString(Charsets.UTF_8)}
            if(n==2){val messages=Json.parseToJsonElement(incoming).jsonObject["messages"]!!.jsonArray
                val native=messages[0].jsonObject
                exactReplay=native["reasoning_content"]!!.jsonPrimitive.content==chunk.repeat(260) && native["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content==args
            }
            e.responseHeaders.add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0)
            e.responseBody.use{out ->
                fun send(value:JsonObject){out.write(("data: "+value.toString()+"\n\n").toByteArray())}
                fun event(delta:JsonObject,reason:String?=null)=buildJsonObject{put("choices",buildJsonArray{add(buildJsonObject{put("delta",delta);reason?.let{put("finish_reason",it)}})})}
                if(n==1){repeat(260){send(event(buildJsonObject{put("reasoning_content",chunk)}))}
                    send(event(buildJsonObject{put("content","Ready");put("tool_calls",buildJsonArray{add(buildJsonObject{put("index",0);put("id","c");put("function",buildJsonObject{put("name","read_file");put("arguments",args)})})})},"tool_calls"))
                }else send(event(buildJsonObject{put("content","Done")},"stop"))
                out.write("data: [DONE]\n\n".toByteArray())
            }
        };server.start()
        val p=AiProvider("owned-private-large","Private","http://127.0.0.1:${server.address.port}","model",false,false,"","",authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
        try {
            ProviderModelCertificationRegistry.certify(p,listOf("model"));ModelContextRegistry.remember(p.id,"model",2_000_000)
            val client=LlmClient(ProviderRegistry.prepareConfigured(listOf(p)));val store=OwnedResponseContentStore(File(root,"store").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),1024)
            val owner=OwnedResponseContentStore.Owner("s","r","response");val options=buildJsonObject{put("model","model")}
            val result=client.streamOwnedChat(p.id,p.baseUrl,"","model",options,listOf(SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","user");put("content","go")})),store.authorize(owner),{store.authorize(owner.copy(callId=it))},{fail("recovery")}){}
            assertTrue(result.nativeAssistantMessage.utf16Units>2_000_000);assertTrue(result.hasPrivateContinuation)
            assertNull(OwnedChatLegacyProjection.response(result,store.authorize(owner),p))
            val registry=NativeAssistantRegistry(File(root,"native"),store);val id=registry.recordAccepted(result,owner);val retained=registry.reopen(id,owner,result.target)
            val public=OwnedChatLegacyProjection.publicResponse(result,retained,p,{store.authorize(owner.copy(callId=it))})!!
            assertTrue(public.length<1000);assertFalse(public.contains("private-"));assertFalse(public.contains("reasoning"))
            val message=Json.parseToJsonElement(public).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            assertEquals(args,message["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
            assertThrows(Exception::class.java){OwnedChatLegacyProjection.publicResponse(result,retained,p,{store.authorize(owner.copy(sessionId="wrong",callId=it))})}
            val history=listOf(SourceAwareChatHistoryWriter.Item.OwnedAssistant(retained),SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","tool");put("tool_call_id","c");put("content","read")}))
            val next=owner.copy(runId="r2")
            client.streamOwnedChat(p.id,p.baseUrl,"","model",options,history,store.authorize(next),{store.authorize(next.copy(callId=it))},{fail("recovery")}){}
            assertEquals(2,requests.get());assertTrue(exactReplay)
        }finally{server.stop(0);root.deleteRecursively();ProviderModelCertificationRegistry.revoke(p.id);ModelContextRegistry.forget(p.id)}
    }
    @Test fun sameResponseBufferedSmallToolAndMislabeledSseRemainCompatible() = runBlocking {
        val root=Files.createTempDirectory("owned-compat-").toFile()
        val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0);val calls=AtomicInteger()
        val raw="""{"id":"buffered","choices":[{"message":{"role":"assistant","reasoning_content":"private","tool_calls":[{"id":"c","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a.txt\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"completion_tokens":5}}"""
        server.createContext("/chat/completions") { e ->
            val n=calls.incrementAndGet();e.requestBody.use{it.readBytes()}
            if(n<3)e.responseHeaders.add("Content-Type","application/json")
            val body=if(n==1)raw else "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            e.sendResponseHeaders(200,0);e.responseBody.use{it.write(body.toByteArray())}
        };server.start()
        val p=AiProvider("owned-compat","Compat","http://127.0.0.1:${server.address.port}","model",false,false,"","",authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
        try {
            ProviderModelCertificationRegistry.certify(p,listOf("model"));val client=LlmClient(ProviderRegistry.prepareConfigured(listOf(p)))
            val store=OwnedResponseContentStore(File(root,"store").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0))
            repeat(3){n ->
                val owner=OwnedResponseContentStore.Owner("s","r$n","response")
                val result=client.streamOwnedChat(p.id,p.baseUrl,"","model",buildJsonObject{put("model","model")},listOf(SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","user");put("content","go")})),store.authorize(owner),{store.authorize(owner.copy(callId=it))},{fail("unexpected recovery")}){}
                if(n==0){assertEquals("read_file",result.calls.single().name);assertEquals(5,result.usage!!["completion_tokens"]!!.jsonPrimitive.int)
                    assertTrue(result.hasPrivateContinuation)
                    val registry=NativeAssistantRegistry(File(root,"native"),store)
                    val id=registry.recordAccepted(result,owner)
                    val retained=registry.reopen(id,owner,result.target)
                    val public=OwnedChatLegacyProjection.publicResponse(result,retained,p,{store.authorize(owner.copy(callId=it))})!!
                    assertFalse(public.contains("private"));assertFalse(public.contains("_droide_"));assertFalse(public.contains("reasoning_content"))
                    val toolResult=buildJsonObject{put("role","tool");put("tool_call_id","c");put("content","read")}
                    val replay=java.io.ByteArrayOutputStream()
                    SourceAwareChatHistoryWriter.writeMessages(listOf(SourceAwareChatHistoryWriter.Item.OwnedAssistant(retained),SourceAwareChatHistoryWriter.Item.Legacy(toolResult)),result.target,replay)
                    assertEquals("private",Json.parseToJsonElement(replay.toString("UTF-8")).jsonArray[0].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
                    val projected=OwnedChatLegacyProjection.response(result,store.authorize(owner),p)!!
                    val expected=ChatReasoningContinuation.capture(raw,p,"model",Json)
                    assertEquals(JsonObject(Json.parseToJsonElement(expected).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject + ("content" to JsonPrimitive(""))),Json.parseToJsonElement(projected).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"])
                } else {assertEquals("stop",result.finishReason);assertTrue(result.hasMeaningfulPublicText)}
                assertEquals(n+1,calls.get())
            }
        }finally{server.stop(0);root.deleteRecursively();ProviderModelCertificationRegistry.revoke(p.id)}
    }
    @Test fun endpointIdentityNeverContainsPathOrQuerySecrets() {
        val url="https://EXAMPLE.com:443/private-secret/chat/completions?api_key=query-secret"
        val identity=OwnedChatEndpointIdentity.fromValidatedUrl(url)
        assertTrue(identity.matches(Regex("endpoint-sha256:[a-f0-9]{64}")))
        assertFalse(identity.contains("private-secret"));assertFalse(identity.contains("query-secret"))
        assertEquals(identity,OwnedChatEndpointIdentity.fromValidatedUrl("https://example.com/private-secret/chat/completions?api_key=query-secret"))
        assertNotEquals(identity,OwnedChatEndpointIdentity.fromValidatedUrl("https://example.com/other/chat/completions?api_key=query-secret"))
        assertNotEquals(identity,OwnedChatEndpointIdentity.fromValidatedUrl("https://example.com/private-secret/chat/completions?api_key=changed"))
    }
    @Test fun realLlmClientLargeArgumentsAndReplayThroughSecuredConfiguredLoopback() = runBlocking {
        val root=Files.createTempDirectory("owned-transport-").toFile()
        val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests=AtomicInteger();var replayBytes=0L;var firstRequest=""
        val chunk="x".repeat(8192)
        server.createContext("/v1/chat/completions") { exchange ->
            val number=requests.incrementAndGet(); var count=0L
            exchange.requestBody.use { input -> if(number==1){firstRequest=input.readBytes().toString(Charsets.UTF_8);count=firstRequest.toByteArray().size.toLong()}else{val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;count+=n}} }
            if(number==2) replayBytes=count
            exchange.responseHeaders.add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0)
            exchange.responseBody.use { out ->
                fun send(s:String){out.write("data: $s\n\n".toByteArray());out.flush()}
                fun args(s:String,first:Boolean=false){send(buildJsonObject{put("choices",buildJsonArray{add(buildJsonObject{put("delta",buildJsonObject{put("tool_calls",buildJsonArray{add(buildJsonObject{put("index",0);if(first)put("id","c");put("function",buildJsonObject{if(first)put("name","write_file");put("arguments",s)})})})})})})}.toString())}
                if(number==1){args("{\"path\":\"large.txt\",\"content\":\"",true);repeat(260){args(chunk)};args("\"}");send("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""")}
                else {send("""{"choices":[{"delta":{"content":"done"}}]}""");send("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")}
                send("[DONE]")
            }
        };server.start()
        val provider=AiProvider("owned-test","Owned","http://127.0.0.1:${server.address.port}/v1","model",false,false,"","",authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
        try {
            ProviderModelCertificationRegistry.certify(provider,listOf("model"));ModelContextRegistry.remember(provider.id,"model",2_000_000)
            val client=LlmClient(ProviderRegistry.prepareConfigured(listOf(provider)))
            val store=OwnedResponseContentStore(File(root,"store").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),1024)
            val owner=OwnedResponseContentStore.Owner("s","r","response")
            val user=SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","user");put("content","write")})
            val options=buildJsonObject{put("model","model")}
            val baselineMessage=buildJsonObject{put("role","assistant");put("content","prior");put("reasoning_content","private continuation")}
            val captured=ChatReasoningContinuation.capture(buildJsonObject{put("choices",buildJsonArray{add(buildJsonObject{put("message",baselineMessage);put("finish_reason","stop")})})}.toString(),provider,"model",Json)
            val legacyMessage=Json.parseToJsonElement(captured).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            val baselineHistory=listOf(user,SourceAwareChatHistoryWriter.Item.Legacy(legacyMessage),SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","assistant");put("content","")}))
            val progressName=StringBuilder()
            val result=client.streamOwnedChat(provider.id,provider.baseUrl,"","model",options,baselineHistory,store.authorize(owner),{store.authorize(owner.copy(callId=it))},{fail("No recovery expected")}){ event -> if(event is LlmStreamEvent.ToolCallDelta) { progressName.append(event.nameFragment.orEmpty());assertNull(event.argumentsFragment) } }
            val sent=Json.parseToJsonElement(firstRequest).jsonObject["messages"]!!.jsonArray
            assertEquals(2,sent.size);assertEquals(baselineMessage,sent[1]);assertFalse(firstRequest.contains("_droide_"))
            assertEquals("write_file",progressName.toString())
            assertTrue(result.calls.single().arguments.utf16Units>2_000_000)
            assertEquals(client.ownedChatTarget(provider.id,provider.baseUrl,"model"),result.target)
            assertNull(OwnedChatLegacyProjection.response(result,store.authorize(owner),provider))
            val journal=ToolCallHistoryJournal(File(root,"journal"),store)
            val pending=journal.recordCompleted(owner.copy(callId="c"),"write_file",result.calls.single().arguments)
            journal.settleWithoutExecution(pending,buildJsonObject{put("role","tool");put("tool_call_id","c");put("content","declined")})
            val registry=CanonicalChatTurnRegistry(File(root,"turn"),store,journal)
            val turnId=registry.recordAccepted(result,owner,listOf(pending.locator))
            val history=listOf(user,SourceAwareChatHistoryWriter.Item.OwnedAssistantGroup(registry.reopen(turnId,owner,result.target)))
            val second=client.streamOwnedChat(provider.id,provider.baseUrl,"","model",options,history,store.authorize(owner.copy(runId="r2")),{store.authorize(owner.copy(runId="r2",callId=it))},{fail("No recovery expected")}){}
            assertEquals("stop",second.finishReason)
            val projection=OwnedChatLegacyProjection.response(second,store.authorize(owner.copy(runId="r2")),provider)
            assertNotNull(projection);assertEquals("done",Json.parseToJsonElement(projection!!).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
            assertEquals(2,requests.get());assertTrue(replayBytes>2_000_000)
            ModelContextRegistry.remember(provider.id,"model",1000)
            var rejected=false
            try{client.streamOwnedChat(provider.id,provider.baseUrl,"","model",options,history,store.authorize(owner.copy(runId="r3")),{store.authorize(owner.copy(runId="r3",callId=it))},{}){}}catch(_:ProviderContextOverflowException){rejected=true}
            assertTrue(rejected);assertEquals(2,requests.get())
        } finally {server.stop(0);root.deleteRecursively();ProviderModelCertificationRegistry.revoke(provider.id);ModelContextRegistry.forget(provider.id)}
    }
    @Test fun cancellationDuringBlockedRealResponseCancelsCallAndNeverRetries() = runBlocking {
        val root=Files.createTempDirectory("owned-stop-").toFile()
        val server=ProviderLoopbackServer.create(InetSocketAddress("127.0.0.1",0),0)
        val started=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val count=AtomicInteger();val recoveries=AtomicInteger();val received=java.util.concurrent.CountDownLatch(1)
        server.createContext("/chat/completions") { e ->
            count.incrementAndGet();e.requestBody.use { it.readBytes() };e.responseHeaders.add("Content-Type","text/event-stream");e.sendResponseHeaders(200,0)
            try {e.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".toByteArray());e.responseBody.flush();started.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS)}finally{e.close()}
        };server.start()
        val p=AiProvider("owned-stop","Stop","http://127.0.0.1:${server.address.port}","model",false,false,"","",authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
        try {
            ProviderModelCertificationRegistry.certify(p,listOf("model"))
            val store=OwnedResponseContentStore(File(root,"store").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),1024)
            val owner=OwnedResponseContentStore.Owner("s","r","response")
            val client=LlmClient(ProviderRegistry.prepareConfigured(listOf(p)))
            val job=launch(Dispatchers.IO) {client.streamOwnedChat(p.id,p.baseUrl,"","model",buildJsonObject{put("model","model")},listOf(SourceAwareChatHistoryWriter.Item.Legacy(buildJsonObject{put("role","user");put("content","go")})),store.authorize(owner),{store.authorize(owner.copy(callId=it))},{recoveries.incrementAndGet()}){if(it is LlmStreamEvent.TextDelta)received.countDown()};fail("Cancelled response returned completion")}
            assertTrue(withContext(Dispatchers.IO){received.await(5,java.util.concurrent.TimeUnit.SECONDS)})
            withTimeout(5000){job.cancelAndJoin()};assertTrue(job.isCancelled);assertEquals(1,count.get());assertEquals(1,recoveries.get())
        }finally{release.countDown();server.stop(0);root.deleteRecursively();ProviderModelCertificationRegistry.revoke(p.id)}
    }

}
