package com.baystudio.droide.core
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
class LspCSharpRequestsTest {
 @Test fun finiteBudgetAndFenceBeforeWaiter()=runBlocking {
  var clock=0L;var fenced=false;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val requests=LspCSharpRequests(scope,{fenced=true},{clock})
  try {
   assertEquals(15000L,LspCSharpRequests.FEATURE_MS);assertEquals(16,LspCSharpRequests.MAX_RECORDS)
   val result=requests.owned("textDocument/hover",{true}){op->op.markDispatched();clock=15_000_000_001L;"too late"}
   assertNull(result);assertTrue(fenced)
  }finally{requests.close();scope.cancel()}
 }
 @Test fun undispatchedExpiryIsInert()=runBlocking {
  var clock=0L;var fenced=false;val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val requests=LspCSharpRequests(scope,{fenced=true},{clock})
  try { assertNull(requests.owned("textDocument/hover",{true}){clock=15_000_000_001L;"late"});assertFalse(fenced) }finally{requests.close();scope.cancel()}
 }
}
