package com.baystudio.droide.core

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class OfficialBugReportTransportTest {
    private val token="a".repeat(64)
    private val cookie="droide_support_v2=fixture-session; path=/; secure; HttpOnly; SameSite=Lax"
    private fun response(request: Request, text: String, code: Int=200, headers: List<Pair<String,String>> = emptyList(), body:ResponseBody?=null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .apply { headers.forEach { (k,v) -> addHeader(k,v) } }
            .body(body ?: text.toResponseBody("application/json; charset=utf-8".toMediaType())).build()
    private fun csrf(request: Request, text: String="{\"ok\":true,\"token\":\"$token\"}", cookies:List<String> = listOf(cookie)) =
        response(request,text,headers=cookies.map { "Set-Cookie" to it })

    @Test fun privateClientHasNoRedirectsRetriesCredentialsCookiesOrCache() {
        val t=OfficialBugReportTransport()
        try {
            assertFalse(t.client.followRedirects); assertFalse(t.client.followSslRedirects)
            assertFalse(t.client.retryOnConnectionFailure); assertSame(CookieJar.NO_COOKIES,t.client.cookieJar)
            assertSame(Authenticator.NONE,t.client.authenticator); assertSame(Authenticator.NONE,t.client.proxyAuthenticator)
            assertNull(t.client.cache); assertNull(t.client.proxy); assertNotNull(t.client.proxySelector)
            assertTrue(t.client.callTimeoutMillis in 1..60_000)
        } finally { t.close() }
    }

    @Test fun prepareAndPostUseExactWebsiteContractWithSameOriginHeadersAndNoExtraFields() = runBlocking {
        val requests=Collections.synchronizedList(mutableListOf<Request>())
        val t=OfficialBugReportTransport(testInterceptor=Interceptor { chain ->
            val request=chain.request(); requests.add(request)
            if(request.method=="GET") csrf(request) else response(request,"{\"ok\":true,\"report_id\":\"BUG-123\"}")
        })
        try {
            assertEquals(BugReportPreparation.READY,t.prepare()); assertEquals(1,requests.size)
            var dispatches=0
            assertEquals(BugReportOutcome.Accepted("BUG-123"),t.submit(validBugReport()){dispatches++})
            assertEquals(1,dispatches); assertEquals(listOf("GET","POST"),requests.map { it.method })
            assertEquals(BugReportPolicy.CSRF_URL,requests[0].url.toString())
            assertNull(requests[0].header("Cookie")); assertNull(requests[0].header("X-Droide-CSRF"))
            val post=requests[1]; assertEquals(BugReportPolicy.SUBMIT_URL,post.url.toString())
            assertEquals(token,post.header("X-Droide-CSRF")); assertEquals("droide_support_v2=fixture-session",post.header("Cookie"))
            assertEquals("application/json",post.header("Accept"))
            assertEquals(BugReportPolicy.ORIGIN, post.header("Origin"))
            assertEquals("${BugReportPolicy.ORIGIN}/report-bug.html", post.header("Referer"))
            for(header in listOf("Authorization","Proxy-Authorization")) assertNull(post.header(header))
            assertTrue(requireNotNull(post.body).isOneShot())
            val encoded=Buffer().also { post.body!!.writeTo(it) }.readUtf8()
            for((name,value) in BugReportPolicy.formFields(validBugReport())) {
                assertTrue(name,encoded.contains("name=\"$name\"")); assertTrue(value,encoded.contains("\r\n\r\n$value\r\n"))
            }
            assertFalse(encoded.contains("screenshot")); assertFalse(encoded.contains("filename="))
            assertTrue(post.body!!.contentType().toString().startsWith("multipart/form-data; boundary="))

            assertEquals(BugReportPreparation.READY,t.prepare()); assertEquals(3,requests.size)
        } finally { t.close() }
    }

    @Test fun officialOriginCheckAdmitsExactlyOnePreparedNativeSubmission() = runBlocking {
        val posts = AtomicInteger()
        val transport = OfficialBugReportTransport(testInterceptor = Interceptor { chain ->
            val request = chain.request()
            if (request.method == "GET") csrf(request) else {
                posts.incrementAndGet()
                // Live rejection probes: absent origin -> 403 forbidden; the official Origin/Referer pair
                // proceeds to form validation. No live report is sent.
                if (request.header("Origin") != BugReportPolicy.ORIGIN ||
                    request.header("Referer") != "${BugReportPolicy.ORIGIN}/report-bug.html") {
                    response(request, "{\"ok\":false,\"error\":\"forbidden\"}", 403)
                } else {
                    assertEquals(token, request.header("X-Droide-CSRF"))
                    assertEquals("droide_support_v2=fixture-session", request.header("Cookie"))
                    response(request, "{\"ok\":true,\"report_id\":\"fixture-native-report\"}")
                }
            }
        })
        try {
            assertEquals(BugReportPreparation.READY, transport.prepare())
            var dispatched = 0
            assertEquals(BugReportOutcome.Accepted("fixture-native-report"),
                transport.submit(validBugReport()) { dispatched++ })
            assertEquals(1, posts.get())
            assertEquals(1, dispatched)
        } finally { transport.close() }
    }

    @Test fun tooFastKeepsPreparedSessionButOnlyExplicitNextSubmitSendsAgain() = runBlocking {
        val gets=AtomicInteger(); val posts=AtomicInteger()
        val t=OfficialBugReportTransport(testInterceptor=Interceptor { chain ->
            val r=chain.request()
            if(r.method=="GET") { gets.incrementAndGet();csrf(r) }
            else { posts.incrementAndGet();response(r,"{\"ok\":false,\"error\":\"too_fast\"}",429) }
        })
        try {
            repeat(2) { assertEquals(BugReportOutcome.Rejected(BugReportError.TOO_FAST),t.submit(validBugReport()){}) }
            assertEquals(1,gets.get());assertEquals(2,posts.get())
        } finally {t.close()}
    }

    @Test fun tokenMustBeStrictBooleanAndExactHexString() = runBlocking {
        val bad=listOf("{}","[]","not json","{\"ok\":\"true\",\"token\":\"$token\"}",
            "{\"ok\":1,\"token\":\"$token\"}","{\"ok\":true,\"token\":64}",
            "{\"ok\":true,\"token\":\"${"a".repeat(63)}\"}","{\"ok\":true,\"token\":\"${"z".repeat(64)}\"}")
        for(text in bad) {
            val count=AtomicInteger(); val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->count.incrementAndGet();csrf(chain.request(),text)})
            try { assertEquals(text,BugReportOutcome.PreparationFailed,t.submit(validBugReport()){});assertEquals(1,count.get()) }
            finally {t.close()}
        }
    }

    @Test fun sessionCookieMustBeUniquePrivateSecureHostOnlyAndUnexpired() = runBlocking {
        val bad=listOf(emptyList(),listOf(cookie,cookie),listOf("other=session; path=/; secure; HttpOnly"),
            listOf("droide_support_v2=session; path=/; HttpOnly"),listOf("droide_support_v2=session; path=/; secure"),
            listOf("droide_support_v2=session; path=/; secure; HttpOnly; Domain=rekacipta.biz.id"),
            listOf("droide_support_v2=session; path=/api; secure; HttpOnly"),
            listOf("droide_support_v2=session; path=/; secure; HttpOnly; Max-Age=0"))
        for(cookies in bad) {
            val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->csrf(chain.request(),cookies=cookies)})
            try {assertEquals(cookies.toString(),BugReportPreparation.FAILED,t.prepare())} finally {t.close()}
        }
    }

    @Test fun successRequiresTwoHundredsAndBooleanTrueAndNeverPromisesMailDelivery() {
        for(status in listOf(200,201,202,204,299)) assertTrue(decode(status,"{\"ok\":true}") is BugReportOutcome.Accepted)
        for(status in listOf(300,301,302,307,308,400,403,500)) assertEquals(BugReportOutcome.UnknownSubmission,decode(status,"{\"ok\":true}"))
        for(text in listOf("{}","{\"ok\":\"true\"}","{\"ok\":1}","{\"ok\":null}","{\"ok\":false}"))
            assertEquals(text,BugReportOutcome.UnknownSubmission,decode(200,text))
    }

    @Test fun onlySafeBoundedStringTicketIdentifiersAreReturned() {
        assertEquals(BugReportOutcome.Accepted("BUG-1_ok.2"),decode(200,"{\"ok\":true,\"report_id\":\"BUG-1_ok.2\"}"))
        for(value in listOf("123","null","\"<script>\"","\"one\\ntwo\"","\"${"x".repeat(81)}\"","\"\""))
            assertEquals(value,BugReportOutcome.Accepted(),decode(200,"{\"ok\":true,\"report_id\":$value}"))
    }

    @Test fun everyDocumentedRejectionCodeHasLocalMessageAndUnknownTextIsNeverDisplayed() {
        for(error in BugReportError.entries) assertEquals(error.code,BugReportOutcome.Rejected(error),
            decode(400,"{\"ok\":false,\"error\":\"${error.code}\"}"))
        assertEquals(BugReportOutcome.UnknownSubmission,decode(500,"{\"ok\":false,\"error\":\"private traceback or arbitrary HTML\"}"))
        assertEquals(BugReportOutcome.UnknownSubmission,decode(302,"{\"ok\":false,\"error\":\"forbidden\"}"))
    }

    @Test fun malformedHtmlAndWrongContentTypeAfterPostAreUnknownWithoutRetry() = runBlocking {
        for(text in listOf("<!DOCTYPE html><html>error</html>","not json","[]","{\"ok\":true} trailing", "{\"ok\":false,\"ok\":true}", "{\"ok\":false,\"\\u006fk\":true}", "{\"ok\":true,\"extra\":" + "[".repeat(4000) + "0" + "]".repeat(4000) + "}")) {
            val count=AtomicInteger();val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
                if(chain.request().method=="GET") csrf(chain.request()) else {count.incrementAndGet();response(chain.request(),text)} })
            try {assertEquals(BugReportOutcome.UnknownSubmission,t.submit(validBugReport()){});assertEquals(1,count.get())}finally{t.close()}
        }
        val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            if(chain.request().method=="GET") csrf(chain.request()) else response(chain.request(),"",body="{\"ok\":true}".toResponseBody("text/html".toMediaType()))})
        try {assertEquals(BugReportOutcome.UnknownSubmission,t.submit(validBugReport()){})}finally{t.close()}
    }

    @Test fun networkFailureAfterPostIsUnknownAndOneAttemptOnly() = runBlocking {
        val posts=AtomicInteger();val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            if(chain.request().method=="GET") csrf(chain.request()) else {posts.incrementAndGet();throw IOException("mock disconnect")}})
        try {assertEquals(BugReportOutcome.UnknownSubmission,t.submit(validBugReport()){});assertEquals(1,posts.get())}finally{t.close()}
    }

    @Test fun redirectsInPreparationNeverBecomePosts() = runBlocking {
        val count=AtomicInteger();val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            count.incrementAndGet();response(chain.request(),"{}",302,listOf("Location" to "https://example.com/collect"))})
        try {assertEquals(BugReportOutcome.PreparationFailed,t.submit(validBugReport()){});assertEquals(1,count.get())}finally{t.close()}
    }

    @Test fun mismatchedResponseOriginIsRejectedBeforeSuccess() = runBlocking {
        val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            if(chain.request().method=="GET") csrf(chain.request()) else
                response(chain.request().newBuilder().url("https://example.com/api/report-bug.php").build(),"{\"ok\":true}")})
        try {assertEquals(BugReportOutcome.UnknownSubmission,t.submit(validBugReport()){})}finally{t.close()}
    }

    @Test fun oversizedDeclaredAndStreamingBodiesAreBoundedAndClosed() = runBlocking {
        for(declared in listOf(true,false)) {
            val bodyClosed=AtomicBoolean();val bytes=AtomicInteger()
            val source=object: Source {
                override fun read(sink:Buffer,byteCount:Long):Long {
                    val n=minOf(byteCount,1024).toInt();bytes.addAndGet(n);sink.writeUtf8("x".repeat(n));return n.toLong()
                }
                override fun timeout()=Timeout.NONE
                override fun close(){bodyClosed.set(true)}
            }.buffer()
            val body=object: ResponseBody(){
                override fun contentType()="application/json".toMediaType()
                override fun contentLength()=if(declared) BugReportPolicy.MAX_RESPONSE_BYTES+1L else -1L
                override fun source()=source
            }
            val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
                if(chain.request().method=="GET") csrf(chain.request()) else response(chain.request(),"",body=body)})
            try {
                assertEquals(BugReportOutcome.UnknownSubmission,t.submit(validBugReport()){})
                assertTrue(bodyClosed.get());assertTrue(bytes.get()<=BugReportPolicy.MAX_RESPONSE_BYTES+8192)
                if(declared) assertEquals(0,bytes.get())
            } finally {t.close()}
        }
    }

    @Test fun clearSessionPreventsCookieReuseAndClosePreventsFurtherRequests() = runBlocking {
        val requests=AtomicInteger();val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->requests.incrementAndGet();csrf(chain.request())})
        assertEquals(BugReportPreparation.READY,t.prepare());assertEquals(BugReportPreparation.READY,t.prepare());assertEquals(1,requests.get())
        t.clearSession();assertEquals(BugReportPreparation.READY,t.prepare());assertEquals(2,requests.get())
        t.close();assertEquals(BugReportPreparation.CLOSED,t.prepare());assertEquals(2,requests.get())
    }

    @Test fun sessionsAreIsolatedBetweenDifferentForms() = runBlocking {
        val seen=Collections.synchronizedList(mutableListOf<String?>())
        fun transport(id:String)=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            if(chain.request().method=="GET") csrf(chain.request(),cookies=listOf("droide_support_v2=$id; path=/; secure; HttpOnly"))
            else {seen.add(chain.request().header("Cookie"));response(chain.request(),"{\"ok\":true}")}})
        val a=transport("first");val b=transport("second")
        try {a.prepare();b.prepare();a.submit(validBugReport()){};b.submit(validBugReport()){}
            assertEquals(listOf("droide_support_v2=first","droide_support_v2=second"),seen)
        } finally {a.close();b.close()}
    }

    @Test fun closedDispatchAdmissionPreventsGetAndPostAndDoesNotMarkAttempt() = runBlocking {
        var open=false;var sent=0;var marks=0
        val t=OfficialBugReportTransport(dispatchGuard={block->check(open);block()},testInterceptor=Interceptor{chain->sent++;csrf(chain.request())})
        try {
            assertEquals(BugReportPreparation.FAILED,t.prepare());assertEquals(0,sent)
            open=true;assertEquals(BugReportPreparation.READY,t.prepare());assertEquals(1,sent)
            open=false;assertEquals(BugReportOutcome.PreparationFailed,t.submit(validBugReport()){marks++})
            assertEquals(0,marks);assertEquals(1,sent)
        }finally{t.close()}
    }

    @Test(timeout=10_000) fun cancellationActuallyCancelsPendingCallAndClearsSession() = runBlocking {
        val entered=CountDownLatch(1);val cancelled=CountDownLatch(1)
        val t=OfficialBugReportTransport(testInterceptor=Interceptor{chain->
            entered.countDown()
            while(!chain.call().isCanceled()) Thread.sleep(5)
            cancelled.countDown();throw IOException("fixture cancelled")})
        try {
            val job=launch(Dispatchers.Default) {t.prepare()}
            assertTrue(entered.await(3,TimeUnit.SECONDS));job.cancelAndJoin()
            assertTrue(job.isCancelled);assertTrue(cancelled.await(3,TimeUnit.SECONDS))
        }finally{t.close()}
    }

    @Test fun invalidDraftCannotTriggerEvenReadOnlyPreparation() = runBlocking {
        var called=false;val t=OfficialBugReportTransport(testInterceptor=Interceptor {called=true;throw AssertionError("No network allowed")})
        try {assertTrue(t.submit(BugReportDraft()){} is BugReportOutcome.Invalid);assertFalse(called)}finally{t.close()}
    }

    @Test(timeout=10_000) fun cancellationAfterResponseHeadersStopsBodyReadAndPreservesUnknown() = runBlocking {
        val reading=CountDownLatch(1);val bodyClosed=AtomicBoolean();val cancelled=AtomicBoolean()
        val posts=AtomicInteger()
        val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->
            if(chain.request().method=="GET") csrf(chain.request()) else {
                posts.incrementAndGet()
                val source=object: Source {
                    override fun read(sink:Buffer,byteCount:Long):Long {
                        reading.countDown()
                        while(!chain.call().isCanceled()) Thread.sleep(5)
                        cancelled.set(true)
                        throw IOException("fixture body read cancelled")
                    }
                    override fun timeout()=Timeout.NONE
                    override fun close(){bodyClosed.set(true)}
                }.buffer()
                val body=object: ResponseBody() {
                    override fun contentType()="application/json".toMediaType()
                    override fun contentLength()=-1L
                    override fun source()=source
                }
                response(chain.request(),"",body=body)
            }
        })
        val coordinator=BugReportCoordinator(t)
        try {
            val job=launch(Dispatchers.Default) {coordinator.submit(validBugReport())}
            assertTrue(reading.await(3,TimeUnit.SECONDS))
            coordinator.cancel();job.join()
            assertTrue(job.isCancelled);assertTrue(cancelled.get());assertTrue(bodyClosed.get())
            assertTrue(coordinator.requiresUnknownRetry);assertEquals(1,posts.get())
            assertEquals(BugReportOutcome.RetryConfirmationRequired,coordinator.submit(validBugReport()))
            assertEquals(1,posts.get())
        } finally {coordinator.close()}
    }

    @Test fun otherwiseValidReportWithoutPrivacyAcknowledgementMakesNoRequest() = runBlocking {
        val calls=AtomicInteger()
        val t=OfficialBugReportTransport(testInterceptor=Interceptor {chain->calls.incrementAndGet();csrf(chain.request())})
        try {
            val outcome=t.submit(validBugReport().copy(privacyAcknowledged=false)){}
            assertEquals(setOf(BugReportField.PRIVACY),(outcome as BugReportOutcome.Invalid).fields.keys)
            assertEquals(0,calls.get())
        } finally {t.close()}
    }

    private fun decode(status:Int,text:String)=OfficialBugReportTransport.decodeSubmission(status,Json.parseToJsonElement(text) as JsonObject)
}
