package com.baystudio.droide.core

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DevelopmentForegroundRegistryTest {
 @Test fun finishedTokenCannotBeReactivatedByDelayedStartup() {
  val key=UUID.randomUUID().toString().replace("-","")
  var canceled=false
  DevelopmentForegroundSessionRegistry.register(key) { canceled=true }
  assertTrue(DevelopmentForegroundSessionRegistry.isRegistered(key))
  DevelopmentForegroundSessionRegistry.remove(key)
  assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(key))
  assertFalse(DevelopmentForegroundSessionRegistry.cancel(key))
  assertFalse(canceled)
 }
 @Test fun cancelledTokenIsRemovedExactlyOnce() {
  val key=UUID.randomUUID().toString().replace("-","")
  var count=0
  DevelopmentForegroundSessionRegistry.register(key) { count++ }
  assertTrue(DevelopmentForegroundSessionRegistry.cancel(key))
  assertFalse(DevelopmentForegroundSessionRegistry.cancel(key))
  assertEquals(1,count)
  assertFalse(DevelopmentForegroundSessionRegistry.isRegistered(key))
 }
}
