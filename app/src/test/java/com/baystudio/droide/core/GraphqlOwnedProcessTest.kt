package com.baystudio.droide.core
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class GraphqlOwnedProcessTest {
    @Test fun absoluteBudgetDoesNotReset(){var now=1_000_000L;val d=GraphqlDeadline.after(60_000){now};now+=20_000_000_000L;assertEquals(40_000,d.remainingMillis());assertEquals(d.endNanos,d.limited(90_000).endNanos);assertEquals(d.endNanos-10_000_000_000L,d.reserve(10_000).endNanos);assertEquals(d.endNanos,GraphqlDeadline.at(d.endNanos){now}.endNanos)}
    @Test fun exhaustedBudgetRejects(){var now=0L;val d=GraphqlDeadline.after(1){now};now=1_000_000;assertTrue(d.expired);assertThrows(IllegalStateException::class.java){d.check()}}
    @Test fun duplicateJSONRejected(){assertThrows(IllegalArgumentException::class.java){GraphqlWire.strict("{\"a\":1,\"a\":2}".toByteArray())}}
    @Test fun escapedDuplicateJSONRejected(){assertThrows(IllegalArgumentException::class.java){GraphqlWire.strict("{\"a\":1,\"\\u0061\":2}".toByteArray())}}
    @Test fun invalidUTF8Rejected(){assertThrows(Exception::class.java){GraphqlWire.strict(byteArrayOf(123,34,0xc3.toByte(),34,58,49,125))}}
    @Test fun duplicateHeaderRejected(){assertThrows(IllegalArgumentException::class.java){GraphqlWire.readFrame(ByteArrayInputStream("Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}".toByteArray()),32)}}
    @Test fun unknownHeaderRejected(){assertThrows(IllegalStateException::class.java){GraphqlWire.readFrame(ByteArrayInputStream("Content-Length: 2\r\nX-Extra: no\r\n\r\n{}".toByteArray()),32)}}
    @Test fun noiseRejected(){assertThrows(Exception::class.java){GraphqlWire.readFrame(ByteArrayInputStream("noise\r\nContent-Length: 2\r\n\r\n{}".toByteArray()),32)}}
    @Test fun scalarAndBatchRejected(){for(s in listOf("null","1","[]","[{\"x\":1}]"))assertThrows(Exception::class.java){GraphqlWire.strict(s.toByteArray())}}
    @Test fun idsAreTypedAndSafe(){assertNotEquals(GraphqlWire.id(JsonPrimitive("1")),GraphqlWire.id(JsonPrimitive(1)));for(id in listOf(JsonNull,JsonPrimitive(true),JsonPrimitive(1.5),JsonPrimitive(9_007_199_254_740_992L)))assertThrows(Exception::class.java){GraphqlWire.id(id)}}
    @Test fun finiteJSONOnly(){assertThrows(Exception::class.java){GraphqlWire.strict("{\"a\":1e999}".toByteArray())}}
    @Test fun frameBoundEnforced(){assertThrows(Exception::class.java){GraphqlWire.readFrame(ByteArrayInputStream("Content-Length: 99999999\r\n\r\n".toByteArray()),8_388_608)}}
    @Test fun partialFramesAreReassembled(){val seen=mutableListOf<JsonObject>();val parser=GraphqlOutgoingFrames{seen+=it};val f=GraphqlWire.frame(buildJsonObject{put("jsonrpc","2.0");put("id",1);put("method","initialize")});f.forEach{parser.feed(byteArrayOf(it),0,1)};assertEquals(1,seen.size)}
    @Test fun blockedNativeWriteCannotBlockFence()=runBlocking {val entered=CountDownLatch(1);val release=CountDownLatch(1);val out=object:OutputStream(){override fun write(b:Int){entered.countDown();release.await()};override fun write(b:ByteArray){entered.countDown();release.await()}};val w=GraphqlAsyncWriter(out){};w.offer(byteArrayOf(1));assertTrue(entered.await(1,TimeUnit.SECONDS));val start=System.nanoTime();w.fence();assertTrue(System.nanoTime()-start<200_000_000L);assertFalse(w.settled);release.countDown();val d=GraphqlDeadline.after(1000);while(!w.settled&&!d.expired)kotlinx.coroutines.delay(1);assertTrue(w.settled)}
    @Test fun heldFactoryIsNotSettlement()=runBlocking {val latch=CountDownLatch(1);val a=GraphqlIoAttempt("fixture-held-factory"){latch.await();42};assertFalse(a.await(GraphqlDeadline.after(10)));assertFalse(a.complete);latch.countDown();assertTrue(a.await(GraphqlDeadline.after(1000)));assertEquals(42,a.result())}
    @Test fun cleanupNeedsEveryFact(){val r=GraphqlTeardownReceipt(true,true,true,true,true,true,true,true,true);assertTrue(r.settled);assertFalse(r.copy(factorySettled=false).settled);assertFalse(r.copy(writersSettled=false).settled);assertFalse(r.copy(readersSettled=false).settled);assertFalse(r.copy(closeAttemptsSettled=false).settled);assertFalse(r.copy(groupEmpty=false).settled)}
}
