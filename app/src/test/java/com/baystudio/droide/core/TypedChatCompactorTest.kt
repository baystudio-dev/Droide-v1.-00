package com.baystudio.droide.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TypedChatCompactorTest {
    private fun entry(role:String,text:String)=TypedChatEntry.Legacy(buildJsonObject { put("role",role);put("content",text) })
    private class Fixture : AutoCloseable {
        val root=Files.createTempDirectory("typed-compact-").toFile()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        val requests=AtomicInteger()
        var received=""
        var reply="""{"choices":[{"delta":{"content":"Historical task investigated; no edit authorization was granted."},"finish_reason":"stop"}]}"""
        val provider=AiProvider("compact-test-${server.address.port}","Compact","http://127.0.0.1:${server.address.port}/v1","model",false,false,"","",authScheme=ProviderAuthScheme.NONE,networkScope=ProviderNetworkScope.LOOPBACK_ONLY)
        val target=TypedChatTarget(provider.id,"model",OwnedChatEndpointIdentity.fromValidatedUrl(provider.baseUrl+"/chat/completions"))
        val store=OwnedResponseContentStore(File(root,"owned").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),1024)
        val journal=ToolCallHistoryJournal(File(root,"journal"),store)
        val registry=CanonicalChatTurnRegistry(File(root,"turns"),store,journal)
        val resources=TypedChatResources(store,journal,registry)
        val config=AgentConfig(provider.baseUrl,"","model",provider.id)
        val client:LlmClient
        init {
            server.createContext("/v1/chat/completions") { exchange ->
                requests.incrementAndGet();received=exchange.requestBody.bufferedReader().readText()
                exchange.responseHeaders.add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0)
                exchange.responseBody.use { it.write(("data: $reply\n\ndata: [DONE]\n\n").toByteArray()) }
            };server.start()
            ProviderModelCertificationRegistry.certify(provider,listOf("model"));ModelContextRegistry.remember(provider.id,"model",64_000)
            client=LlmClient(ProviderRegistry.prepareConfigured(listOf(provider)))
        }
        suspend fun compact(checkpoint:TypedChatCheckpoint,check:()->Unit={},usage:(TokenUsage)->Unit={}) = TypedChatCompactor.compact(
            checkpoint,"s",resources,client,config,provider,buildJsonObject{put("model","model")},emptyList(),checkCurrent=check,onUsage=usage)
        override fun close(){server.stop(0);root.deleteRecursively();ProviderModelCertificationRegistry.revoke(provider.id)}
    }

    @Test fun actualClientCompactsWhilePreservingAuthorityLatestUserAndExactHistory()=runBlocking {
        Fixture().use { f ->
            val entries=listOf(entry("system","Never publish without approval."),entry("user","Earlier investigation"),
                entry("assistant","investigated detail ".repeat(2000)),entry("developer","Keep source backups."),entry("user","Newest instruction: review only."))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val result=f.compact(original)
            assertTrue(result.changed);assertEquals(1,result.requests);assertEquals(1,f.requests.get())
            assertEquals(original,result.original);assertEquals(original.exact,result.checkpoint.exact)
            assertEquals(entries[0],result.checkpoint.live[0]);assertEquals(entries[3],result.checkpoint.live[1]);assertEquals(entries.last(),result.checkpoint.live.last())
            assertTrue(f.received.contains("investigated detail"));assertFalse(f.received.contains("registryId"))
            val request=Json.parseToJsonElement(f.received).jsonObject
            assertEquals(JsonPrimitive("none"),request["tool_choice"]);assertNull(request["tools"])
        }
    }
    @Test fun sourceBackedCanonicalToolGroupIsSummarizedWithoutNativeFieldLoss()=runBlocking {
        Fixture().use { f ->
            val owner=OwnedResponseContentStore.Owner("s","historical-run","response")
            val arguments=buildJsonObject { put("path","result.txt");put("content","x".repeat(50_000)) }.toString()
            val result=OwnedChatStreamAccumulator(f.store.authorize(owner),{ f.store.authorize(owner.copy(callId=it)) },f.target.runtime()).use { a ->
                a.accept(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject {
                    put("delta",buildJsonObject { put("reasoning_content","native private continuity");put("tool_calls",buildJsonArray { add(buildJsonObject {
                        put("index",0);put("id","call");put("function",buildJsonObject { put("name","write_file");put("arguments",arguments) })
                    }) }) });put("finish_reason","tool_calls")
                }) }) }.toString())
                a.accept("[DONE]");a.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
            }
            val pending=f.journal.recordCompleted(owner.copy(callId="call"),"write_file",result.calls.single().arguments)
            f.journal.settleWithoutExecution(pending,buildJsonObject { put("role","tool");put("tool_call_id","call");put("content","not authorized; no write") })
            val id=f.registry.recordAccepted(result,owner,listOf(pending.locator))
            val entries=listOf(entry("user","historical request"),TypedChatEntry.OwnedTurn(id,owner.runId,owner.callId,1),entry("user","Current request is review only"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val compacted=f.compact(original)
            assertTrue(compacted.changed);assertEquals(entries,compacted.checkpoint.exact)
            val messages=Json.parseToJsonElement(f.received).jsonObject.getValue("messages").jsonArray.map{it.jsonObject}
            val native=messages.single{it.containsKey("tool_calls")}
            assertEquals(JsonPrimitive("native private continuity"),native["reasoning_content"])
            assertEquals(arguments,native.getValue("tool_calls").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
            assertEquals(1,messages.count{it["tool_call_id"]==JsonPrimitive("call")})
            assertEquals(entries.last(),compacted.checkpoint.live.last())
        }
    }

    private fun nativeSmallHistory(f:Fixture):List<TypedChatEntry> {
        val owner=OwnedResponseContentStore.Owner("s","native-small","response")
        val received=OwnedChatStreamAccumulator(f.store.authorize(owner),{f.store.authorize(owner.copy(callId=it))},f.target.runtime()).use { a ->
            a.accept(buildJsonObject { put("choices",buildJsonArray { add(buildJsonObject {
                put("delta",buildJsonObject {
                    put("reasoning_content","private continuation ".repeat(2000))
                    put("tool_calls",buildJsonArray { for(id in listOf("a","b"))add(buildJsonObject {
                        put("index",if(id=="a")0 else 1);put("id",id)
                        put("function",buildJsonObject { put("name","read_file");put("arguments","{}") })
                    }) })
                });put("finish_reason","tool_calls")
            }) }) }.toString())
            a.accept("[DONE]");a.finishAtTransportEnd(OwnedChatStreamAccumulator.TransportEnd.DONE_MARKER)
        }
        val id=f.resources.nativeAssistants.recordAccepted(received,owner)
        fun tool(id:String)=TypedChatEntry.Legacy(buildJsonObject { put("role","tool");put("tool_call_id",id);put("content","result $id") })
        return listOf(entry("system","No edits"),entry("user","old request"),TypedChatEntry.OwnedAssistant(id,owner.runId,owner.callId,listOf("a","b")),tool("b"),tool("a"),entry("user","latest review only"))
    }
    @Test fun nativeSmallMultiToolCompactionKeepsPrivateReplayAndAtomicResults()=runBlocking {
        Fixture().use { f ->
            val entries=nativeSmallHistory(f)
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val compacted=f.compact(original)
            assertTrue(compacted.changed);assertEquals(entries,compacted.checkpoint.exact)
            assertEquals(entries.first(),compacted.checkpoint.live.first());assertEquals(entries.last(),compacted.checkpoint.live.last())
            val messages=Json.parseToJsonElement(f.received).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
            val native=messages.single { it.containsKey("tool_calls") }
            assertTrue(native["reasoning_content"]!!.jsonPrimitive.content.startsWith("private continuation"))
            assertEquals(2,native["tool_calls"]!!.jsonArray.size)
            assertEquals(setOf("a","b"),messages.filter { it["role"]==JsonPrimitive("tool") }.map { it["tool_call_id"]!!.jsonPrimitive.content }.toSet())
        }
    }
    @Test fun oversizedNativeSmallGroupCannotBeSplitForCompaction()=runBlocking {
        Fixture().use { f ->
            ModelContextRegistry.remember(f.provider.id,"model",4096)
            val entries=nativeSmallHistory(f).filterIndexed { index,_ -> index!=1 }
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            assertNotNull(runCatching { f.compact(original) }.exceptionOrNull())
            assertEquals(0,f.requests.get());assertEquals(entries,original.exact)
        }
    }

    @Test fun oversizedIndivisibleHistoricalGroupBlocksBeforeNetwork()=runBlocking {
        Fixture().use { f ->
            ModelContextRegistry.remember(f.provider.id,"model",4096)
            val entries=listOf(entry("user","x".repeat(100_000)),entry("assistant","older response"),entry("user","new request"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val error=runCatching { f.compact(original) }.exceptionOrNull()
            assertNotNull(error);assertTrue(error!!.message.orEmpty().contains("complete historical"));assertEquals(0,f.requests.get());assertEquals(entries,original.live)
        }
    }
    @Test fun latestUserTooLargeNeverGetsSummarizedAway()=runBlocking {
        Fixture().use { f ->
            ModelContextRegistry.remember(f.provider.id,"model",4096)
            val entries=listOf(entry("user","old"),entry("assistant","history".repeat(3000)),entry("user","KEEP EXACT ".repeat(10_000)))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            assertTrue(runCatching { f.compact(original) }.exceptionOrNull() is ProviderContextOverflowException)
            assertEquals(0,f.requests.get());assertEquals(entries,original.exact)
        }
    }
    @Test fun providerToolCallInsteadOfSummaryIsRejectedWithoutExecution()=runBlocking {
        Fixture().use { f ->
            f.reply="""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"bad","function":{"name":"write_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            val entries=listOf(entry("user","old"),entry("assistant","detail".repeat(3000)),entry("user","review only"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            assertNotNull(runCatching { f.compact(original) }.exceptionOrNull());assertEquals(1,f.requests.get());assertEquals(entries,original.live)
        }
    }
    @Test fun cancelledOwnerAfterResponseCannotProduceCommitCandidate()=runBlocking {
        Fixture().use { f ->
            var cancelled=false
            val entries=listOf(entry("user","old"),entry("assistant","detail".repeat(3000)),entry("user","new"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val failure=runCatching { f.compact(original,{if(cancelled)throw CancellationException("stale")},{cancelled=true}) }.exceptionOrNull()
            assertTrue(failure is CancellationException);assertEquals(entries,original.live)
        }
    }
    @Test fun rollingBatchesRespectSmallContextWithoutSplittingGroups()=runBlocking {
        Fixture().use { f ->
            ModelContextRegistry.remember(f.provider.id,"model",4096)
            val entries=listOf(entry("user","older one"),entry("assistant","a".repeat(7000)),entry("user","older two"),entry("assistant","b".repeat(7000)),entry("user","latest exact"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val result=f.compact(original)
            assertTrue(result.requests>=2);assertEquals(result.requests,f.requests.get())
            assertEquals(entries,result.checkpoint.exact);assertEquals(entries.last(),result.checkpoint.live.last())
        }
    }
    @Test fun endpointChangeIsRejectedBeforeAnyHistoryTransmission()=runBlocking {
        Fixture().use { f ->
            val entries=listOf(entry("user","old"),entry("assistant","details".repeat(1000)),entry("user","latest"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val failure=runCatching { TypedChatCompactor.compact(original,"s",f.resources,f.client,
                f.config.copy(baseUrl=f.provider.baseUrl+"/different"),f.provider,buildJsonObject { put("model","model") },emptyList(),checkCurrent={}) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException);assertEquals(0,f.requests.get())
        }
    }

    @Test fun failedProviderAttemptStillAccountsReportedUsageOnce()=runBlocking {
        Fixture().use { f ->
            f.reply="""{"choices":[{"delta":{"content":"partial checkpoint"}}],"usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}""" + "\n\ndata: " + """{"error":{"message":"failed"}}"""
            val entries=listOf(entry("user","old"),entry("assistant","details".repeat(1000)),entry("user","latest"))
            val original=TypedChatCheckpoint(target=f.target,live=entries,exact=entries)
            val samples=mutableListOf<TokenUsage>()
            assertNotNull(runCatching { f.compact(original,usage={samples+=it}) }.exceptionOrNull())
            assertEquals(1,samples.size);assertEquals(10,samples.single().total);assertEquals(entries,original.live)
            val manifests=f.root.resolve("recovery").walkTopDown().filter { it.name=="manifest.json" }.toList()
            assertEquals(1,manifests.size)
            val recovery=Json.parseToJsonElement(manifests.single().readText()).jsonObject
            assertEquals(JsonPrimitive("s"),recovery["session"])
            assertTrue(recovery.getValue("run").jsonPrimitive.content.startsWith("compact-"))
            assertEquals(JsonPrimitive("summary-0"),recovery["response"])
            assertEquals(JsonPrimitive(f.target.endpointIdentity),recovery["endpoint"])
        }
    }

    @Test fun oneLongUserTurnCanCompactCompleteToolGroupsWithoutLosingTheRequest() = runBlocking {
        Fixture().use { f ->
            ModelContextRegistry.remember(f.provider.id, "model", 8_192)
            val entries = buildList {
                add(entry("system", "Keep every source file"))
                add(entry("user", "Investigate only; never write or publish"))
                repeat(6) { n ->
                    add(TypedChatEntry.Legacy(buildJsonObject {
                        put("role", "assistant"); put("content", "")
                        put("tool_calls", buildJsonArray { add(buildJsonObject {
                            put("id", "c$n"); put("type", "function")
                            put("function", buildJsonObject { put("name", "read_file"); put("arguments", "{}") })
                        }) })
                    }))
                    add(TypedChatEntry.Legacy(buildJsonObject {
                        put("role", "tool"); put("tool_call_id", "c$n"); put("content", "evidence$n ".repeat(500))
                    }))
                }
            }
            val original = TypedChatCheckpoint(target = f.target, live = entries, exact = entries)
            val result = f.compact(original)
            assertTrue(result.changed)
            assertEquals(entries, result.checkpoint.exact)
            assertEquals(entries.first(), result.checkpoint.live.first())
            assertTrue(result.checkpoint.live.contains(entries[1]))
            assertEquals(entries.takeLast(2), result.checkpoint.live.takeLast(2))
            assertTrue(result.checkpoint.live.size < entries.size)
            val legacy = result.checkpoint.live.map { (it as TypedChatEntry.Legacy).message }
            assertEquals(legacy, AgentHistory.requireCompleteGroups(legacy).flatten())
            assertTrue(f.received.contains("Investigate only; never write or publish"))
        }
    }

    @Test fun forgedCandidateCannotChangeAuthorityLatestUserOrExactHistory() {
        val entries=listOf(entry("system","keep"),entry("user","old"),entry("assistant","detail"),entry("user","latest"))
        val original=TypedChatCheckpoint(target=TypedChatTarget("p","m",OwnedChatEndpointIdentity.fromValidatedUrl("https://example.test/chat")),live=entries,exact=entries)
        val valid=listOf(entries[0],TypedChatEntry.Legacy(AgentCompactionCheckpoint.message("summary",emptyList())),entries.last())
        TypedChatCompactor.Candidate.validated(original,original.copy(live=valid),true,1)
        for (changed in listOf(original.copy(live=valid.dropLast(1)),original.copy(live=listOf(entry("system","evil"))+valid.drop(1)),original.copy(live=valid,exact=emptyList()))) {
            assertThrows(IllegalArgumentException::class.java) { TypedChatCompactor.Candidate.validated(original,changed,true,1) }
        }
        assertThrows(IllegalArgumentException::class.java) { TypedChatCompactor.Candidate.validated(original,original.copy(live=listOf(entries[0],entry("system","summary"),entries.last())),true,1) }
    }
}
