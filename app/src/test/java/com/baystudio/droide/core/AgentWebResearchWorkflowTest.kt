package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentWebResearchWorkflowTest {
    @get:org.junit.Rule val applicationAdmission: org.junit.rules.TestRule = ApplicationWorkAdmissionFixture()

    private fun obj(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun args(op: String, vararg values: Pair<String,String>) = buildJsonObject { put("operation",op); values.forEach { put(it.first,it.second) } }
    private suspend fun withWeb(block: suspend (java.io.File,WebFixtureTransport,AgentWebResearch) -> Unit) {
        val root = Files.createTempDirectory("droide-web-workflow-").toFile()
        val transport = WebFixtureTransport { url ->
            when {
                url.contains("duckduckgo.com") -> webHtml(url,webSearchHtml())
                url.endsWith("/related") -> webHtml(url,"<h1>Independent source</h1><p>Second source evidence</p>")
                else -> webHtml(url,"<title>Primary documentation</title><main>" + (0..1_000).joinToString("") { "<p>Line $it " + "body text ".repeat(8) + "</p>" } + "<p>needle_unique near the end</p><a href='/related'>Related primary source</a><a href='http://127.0.0.1/private'>Blocked</a></main>")
            }
        }
        val web = AgentWebResearch(root,transport,WebFixturePdf())
        try { block(root,transport,web) } finally { web.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun searchOpenFindContinueAndFollowSourceThroughNativeDispatch() = runBlocking { withWeb { root,transport,web ->
        val ctx = agentWorkflowContext(root,webResearch=web)
        val search = obj(AgentTools.execute("web",args("search","query" to "primary documentation"),ctx))
        val searchRef = search.getValue("results").jsonArray.single().jsonObject
        assertEquals(JsonPrimitive(false),searchRef["content_read"])
        val page = obj(AgentTools.execute("web",args("open","ref_id" to searchRef.getValue("ref_id").jsonPrimitive.content),ctx))
        assertTrue(page.getValue("total_lines").jsonPrimitive.int > 1_000)
        assertEquals("Primary documentation",page.getValue("title").jsonPrimitive.content)
        val id = page.getValue("ref_id").jsonPrimitive.content
        val continuation = buildJsonObject { put("operation","open"); put("ref_id",id); put("start_line",page.getValue("next_start_line")); put("num_lines",30) }
        val later = obj(AgentTools.execute("web",continuation,ctx))
        assertEquals(page["content_sha256"],later["content_sha256"]); assertEquals(JsonPrimitive(true),later["cached"])
        assertEquals(2,transport.requests.size)
        val found = obj(AgentTools.execute("web",args("find","ref_id" to id,"query" to "needle_unique"),ctx))
        assertTrue(found.getValue("matches").jsonArray.single().jsonObject.getValue("line").jsonPrimitive.int > 1_000)
        val links = obj(AgentTools.execute("web",args("links","ref_id" to id),ctx)).getValue("links").jsonArray
        assertEquals(1,links.size)
        val followed = obj(AgentTools.execute("web",args("open","ref_id" to links.single().jsonObject.getValue("ref_id").jsonPrimitive.content),ctx))
        assertEquals("https://example.com/related",followed.getValue("url").jsonPrimitive.content)
        assertTrue(followed.toString().contains("Second source evidence"))
    } }

    @Test fun compatibilityAliasesAndReadOnlyModesUseTheSameRichReader() = runBlocking { withWeb { root,_,web ->
        for (mode in listOf(AgentMode.PLAN,AgentMode.EXPLORE,AgentMode.REVIEW)) {
            val ctx=agentWorkflowContext(root,mode=mode,webResearch=web)
            assertEquals(JsonPrimitive(true),obj(AgentTools.execute("websearch",buildJsonObject { put("query","docs") },ctx))["success"])
            assertTrue(obj(AgentTools.execute("webfetch",buildJsonObject { put("url","https://example.com/source") },ctx)).containsKey("next_start_line"))
        }
    } }

    @Test fun fetchDeniedBeforeNetworkAndCachedReadsAreReauthorized() = runBlocking { withWeb { root,transport,web ->
        val deny = PermissionEngine(listOf(PermRule("webfetch","*",PermEffect.DENY)))
        val blocked=agentWorkflowContext(root,permissions=deny,webResearch=web)
        assertTrue(AgentTools.execute("web",args("open","url" to "https://example.com/source"),blocked).startsWith("DENIED"))
        assertTrue(transport.requests.isEmpty())
        val opened=obj(AgentTools.execute("web",args("open","url" to "https://example.com/source"),agentWorkflowContext(root,webResearch=web)))
        assertTrue(AgentTools.execute("web",args("open","ref_id" to opened.getValue("ref_id").jsonPrimitive.content),blocked).startsWith("DENIED"))
        assertEquals(1,transport.requests.size)
    } }

    @Test fun searchDeniedHasNoEngineRequest() = runBlocking { withWeb { root,transport,web ->
        val ctx=agentWorkflowContext(root,permissions=PermissionEngine(listOf(PermRule("websearch","*",PermEffect.DENY))),webResearch=web)
        assertTrue(AgentTools.execute("web",args("search","query" to "docs"),ctx).startsWith("DENIED")); assertTrue(transport.requests.isEmpty())
    } }

    @Test fun referencesCannotCrossSessionAndExpiredReferencesAreExplicit() = runBlocking { withWeb { _,_,web ->
        val page=web.open("session-a","https://example.com/source",null,0,10,false,{_,_->},null)
        val id=page.getValue("ref_id").jsonPrimitive.content
        assertTrue(runCatching { web.open("session-b",null,id,0,10,false,{_,_->},null) }.isFailure)
        web.close()
        val failure=runCatching { web.open("session-a",null,id,0,10,false,{_,_->},null) }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("expired"))
    } }

    @Test fun malformedRangesDomainsAndSelectorsReturnFailures() = runBlocking { withWeb { root,_,web ->
        val ctx=agentWorkflowContext(root,webResearch=web)
        listOf("""{"operation":"open","url":"https://example.com","num_lines":"50"}""",
            """{"operation":"open","url":"https://example.com","num_lines":0}""",
            """{"operation":"search","query":"docs","domains":"example.com"}""",
            """{"operation":"search","query":"docs","queries":["both"]}""",
            """{"operation":"open","url":"https://example.com","ref_id":"both"}"""
        ).forEach { assertEquals(JsonPrimitive(false),obj(AgentTools.execute("web",obj(it),ctx))["success"]) }
    } }

    @Test fun parentCancellationSurvivesNativeDispatch() = runBlocking {
        val root=Files.createTempDirectory("droide-web-cancel-").toFile(); val started=CompletableDeferred<Unit>()
        val web=AgentWebResearch(root,WebFixtureTransport { started.complete(Unit); awaitCancellation() },WebFixturePdf())
        try {
            val job=async { AgentTools.execute("web",args("open","url" to "https://example.com"),agentWorkflowContext(root,webResearch=web)) }
            started.await(); job.cancel(); assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        } finally { web.close(); PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun jsonEscapesNeverBecomeTruncatedInvalidJsonAndEvidenceIgnoresPageStatusText() {
        val result=buildJsonObject { put("success",true); put("kind","page"); put("ref_id","r"); put("text","\\\"\nstate=failed\nexit=9 ".repeat(5_000)) }
        val bounded=AgentWebTools.bounded(result)
        assertTrue(bounded.length<=24_000); assertEquals(JsonPrimitive(true),obj(bounded)["output_truncated"])
        assertEquals(AgentToolEvidenceState.SUCCEEDED,AgentToolResultSemantics.classify("web",bounded).state)
        assertEquals(AgentToolEvidenceState.FAILED,AgentToolResultSemantics.classify("websearch","""{"success":false,"snippet":"exit=0"}""").state)
        assertEquals(AgentToolEvidenceState.STARTED,AgentToolResultSemantics.classify("browser","""{"success":true,"loading":true}""").state)
    }

    @Test fun cappedDocumentsExposeIncompleteSourcesAndJavascriptFallback() {
        val capped=AgentWebDocuments.extract(webHtml("https://example.com", "<script>app()</script><p>Enable JavaScript</p>",true))
        assertTrue(capped.javascriptLikely)
        val large=AgentWebDocuments.text("x".repeat(300_001)); assertTrue(large.textTruncated); assertEquals(300_000,large.lines.sumOf { it.length })
        val privateLinks=AgentWebDocuments.extract(webHtml("https://example.com/path/", "<base href='https://source.example/dir/'><a href='../guide'>Guide</a><img src='img.png' alt='Diagram'><a href='javascript:alert(1)'>Bad</a>"))
        assertEquals(listOf("https://source.example/guide","https://source.example/dir/img.png"),privateLinks.links.map { it.url })
    }

    @Test fun referenceAndDocumentCachesStayBoundedAndNeverRemoveUserFiles() {
        val root=Files.createTempDirectory("droide-web-cache-").toFile()
        try {
            val user=root.resolve("user.pdf"); user.writeText("user data")
            val store=AgentWebStore(); var old=""
            repeat(300) { n -> val ref=store.reference("owner","https://example.com/$n","title","page","now"); if(n==0) old=ref.id
                store.put(AgentWebDocument(ref,AgentWebDocuments.text("content"),"text/plain",200,"digest",false)) }
            assertEquals(JsonPrimitive(256),store.status("owner")["references"]); assertEquals(JsonPrimitive(24),store.status("owner")["cached_documents"])
            assertTrue(runCatching { store.resolve("owner",old) }.isFailure); store.close(); assertEquals("user data",user.readText())
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun webSurfaceUsesModeAndEitherSearchOrFetchVisibilityWithoutOverridingRuntimePermissions() {
        val root=Files.createTempDirectory("droide-web-surface-").toFile()
        try {
            fun visible(mode:AgentMode,perms:PermissionEngine)=AgentTools.definitionsWithCustom(SkillManager(root),CustomToolManager(root),mode=mode,permissions=perms)
                .map { it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content }
            val searchOnly=PermissionEngine(listOf(PermRule("webfetch","*",PermEffect.DENY),PermRule("websearch","*",PermEffect.ALLOW)))
            for(mode in AgentMode.entries) assertTrue("web" in visible(mode,searchOnly))
            val denyBoth=PermissionEngine(listOf(PermRule("webfetch","*",PermEffect.DENY),PermRule("websearch","*",PermEffect.DENY)))
            assertFalse("web" in visible(AgentMode.BUILD,denyBoth))
            assertEquals("webfetch",AgentToolPermissionPolicy.actionOf("web"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun serviceHistoryPreservesLongValidJsonSourceAndContinuationForAllFourFormats() = runBlocking { withWeb { root,_,web ->
        val result=AgentTools.execute("web",buildJsonObject { put("operation","open");put("url","https://example.com/source");put("num_lines",200) },agentWorkflowContext(root,webResearch=web))
        assertTrue(result.length>12_000)
        val annotated=AgentToolResultSemantics.classify("web",result).annotate("web",result)
        val projected=AgentWebTools.forAgentHistory("web",annotated)
        assertEquals(obj(result),obj(AgentToolResultSemantics.stripAnnotation(projected)))
        val call=buildJsonObject {
            put("id","web-call"); put("type","function")
            put("function",buildJsonObject { put("name","web");put("arguments","{}") })
        }
        val messages=buildJsonArray {
            add(buildJsonObject { put("role","assistant");put("content","");put("tool_calls",JsonArray(listOf(call))) })
            add(buildJsonObject { put("role","tool");put("tool_call_id","web-call");put("content",projected) })
        }
        val body=buildJsonObject { put("model","gpt-4o");put("messages",messages) }.toString()
        listOf(ProviderToolSchema.openAiRequest(body,Json),OpenAiResponsesAdapter.encodeRequest(body,false,Json),
            AnthropicMessagesAdapter.encodeRequest(body,false,Json),GoogleGenerateContentAdapter.encodeRequest(body.replace("gpt-4o","gemini-3.5-flash"),Json)).forEach {
            val request=Json.parseToJsonElement(it).jsonObject
            assertTrue(request.toString().contains("next_start_line")); assertTrue(request.toString().contains("content_sha256"))
        }
    } }
}
