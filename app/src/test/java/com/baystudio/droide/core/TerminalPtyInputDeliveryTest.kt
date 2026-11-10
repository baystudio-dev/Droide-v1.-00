package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test

class TerminalPtyInputDeliveryTest {
 @Test fun callbackFailureClosesQueueAndNeverReplays() {
  val queue=TerminalStartupCommandQueue()
  queue.sendRaw("first",0) {}
  queue.sendRaw("second",0) {}
  var failures=0
  TerminalPtyInputDelivery.flush(queue,123,{ throw java.io.IOException("PTY delivery ambiguous") }) { failures++ }
  assertEquals(1,failures)
  TerminalPtyInputDelivery.flush(queue,123,{ fail("Must not replay") }) { fail("Unexpected error") }
  assertTrue(runCatching { queue.sendRaw("later",123) {} }.isFailure)
 }
 @Test fun ctrlCIsOnlyAllowedForLiveHumanPty() {
  assertFalse(TerminalPtyInputDelivery.mayInterrupt(true,55,true))
  assertFalse(TerminalPtyInputDelivery.mayInterrupt(false,0,true))
  assertFalse(TerminalPtyInputDelivery.mayInterrupt(false,55,false))
  assertTrue(TerminalPtyInputDelivery.mayInterrupt(false,55,true))
 }
}
