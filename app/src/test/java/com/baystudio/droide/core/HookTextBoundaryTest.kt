package com.baystudio.droide.core
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
class HookTextBoundaryTest {
 private fun decision(reason:String="",context:String="")=HookLifecycleProtocol.decision(HookLifecycleEvent.PRE_TOOL_USE,"block",reason,context)
 @Test fun reasonBoundaryDoesNotSplitValidPair() { assertEquals("x".repeat(999),decision(reason="x".repeat(999)+"😀END").reason) }
 @Test fun contextBoundaryDoesNotSplitValidPair() { assertEquals("x".repeat(3999),decision(context="x".repeat(3999)+"😀END").additionalContext) }
 @Test fun completePairAtBoundaryIsKept() {
  assertEquals("x".repeat(998)+"😀",decision(reason="x".repeat(998)+"😀END").reason)
  assertEquals("x".repeat(3998)+"😀",decision(context="x".repeat(3998)+"😀END").additionalContext)
 }
 @Test fun ordinaryLimitsAndDecisionStayUnchanged() {
  val value=decision(reason="x".repeat(1001),context="y".repeat(4001))
  assertTrue(value.blocked);assertEquals(1000,value.reason.length);assertEquals(4000,value.additionalContext.length)
  assertEquals("ok",decision(reason="  ok  ").reason)
 }
 @Test fun existingUnpairedDataIsNotSilentlyRewritten() { assertEquals("a"+'\uD800',decision(context="a"+'\uD800').additionalContext) }
 @Test fun combinedHookContextsDoNotIntroduceBrokenPair() {
  val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)){_,_,_->throw AssertionError("No terminal call")} as ITerminalSession
  val bridge=AgentHookLifecycleBridge(HookManager(java.io.File("."),terminal,PermissionEngine(emptyList()),ApprovalManager()),{"fixture"},{AgentMode.BUILD})
  val method=AgentHookLifecycleBridge::class.java.getDeclaredMethod("decorate",String::class.java,String::class.java,Array<HookDispatchResult>::class.java);method.isAccessible=true
  val result=method.invoke(bridge,"custom_fixture","producer",arrayOf(HookDispatchResult(additionalContext="x".repeat(3998)),HookDispatchResult(additionalContext="😀END"))) as String
  assertEquals("producer\n[hook context]\n"+"x".repeat(3998)+"\n",result)
 }
}
