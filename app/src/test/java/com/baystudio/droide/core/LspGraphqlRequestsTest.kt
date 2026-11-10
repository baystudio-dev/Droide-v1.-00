package com.baystudio.droide.core
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class LspGraphqlRequestsTest {
 @Test fun queuedLatestReplacementAndCapturedAuthority()=runBlocking {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var now=0L;var expired=0;val requests=LspGraphqlRequests(scope,{_,_->expired++},{now});val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
    val first=async{requests.owned("textDocument/completion",{true}){op->started.complete(Unit);release.await();op.markDispatched();"first"}}
    started.await();val second=async{requests.owned("textDocument/completion",{true}){"second"}};delay(20);val third=async{requests.owned("textDocument/completion",{true}){"third"}};delay(20);release.complete(Unit)
    assertNull(first.await());assertNull(second.await());assertEquals("third",third.await());assertEquals(0,expired);requests.close();scope.cancel()
 }
 @Test fun preDispatchExpiryDoesNotRetireIndependentStartup()=runBlocking {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var now=0L;var expired=0;val requests=LspGraphqlRequests(scope,{_,_->expired++},{now})
    val result=requests.owned("textDocument/hover",{true}){now=15_000_000_000L;null};assertNull(result);assertEquals(0,expired);requests.close();scope.cancel()
 }
 @Test fun actualDispatchedDeadlineFencesOwner()=runBlocking {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);var now=0L;var expired=0;val requests=LspGraphqlRequests(scope,{_,_->expired++},{now})
    val result=requests.owned("textDocument/hover",{true}){op->op.markDispatched();now=15_000_000_000L;"late"};assertNull(result);assertEquals(1,expired);requests.close();scope.cancel()
 }
}
