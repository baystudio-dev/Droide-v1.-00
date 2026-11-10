package com.baystudio.droide.core
import java.nio.file.Files
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class HookResultPreservationTest {
 private fun through(tool:String,result:String, source:Boolean=false, cancel:Boolean=false):String=runBlocking {
  val root=Files.createTempDirectory("hook-result").toFile()
  try {
   val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)) { _,_,_ -> throw AssertionError("No terminal invocation expected") } as ITerminalSession
   val manager=HookManager(root,terminal,PermissionEngine(emptyList()),ApprovalManager())
   val bridge=AgentHookLifecycleBridge(manager,{"fixture"},{AgentMode.BUILD})
   if(!source) bridge.tool(tool,"{}"){if(cancel)throw kotlinx.coroutines.CancellationException("fixture cancellation");result}
   else {
    val store=OwnedResponseContentStore(root.resolve("private-store").toPath(),OwnedResponseContentStore.HeadroomAdmission({Long.MAX_VALUE},0),inlineThreshold=32)
    val session=store.authorize(OwnedResponseContentStore.Owner("fixture","run","call"))
    val content=session.begin(OwnedResponseContentStore.Kind.TOOL_ARGUMENTS).use { writer ->
     writer.append("{}");writer.sealCompleted { StreamedJsonValidator.requireObject(it) }.content
    }
    bridge.toolSource(tool,HookToolInputSource.admit(session,content.handle,{ })){result}
   }
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
 @Test fun noHookReadResultIsNotSilentlyCut() { val raw="x".repeat(18000)+"END"; assertTrue("producer output changed",raw==through("custom_fixture",raw)) }
 @Test fun noHookStructuredToolResultRemainsParseable() {
  val raw=buildJsonObject { put("data","x".repeat(18000));put("success",true) }.toString()
  assertTrue("JSON producer output changed",raw==through("web",raw))
  assertEquals(JsonPrimitive(true),Json.parseToJsonElement(through("web",raw)).jsonObject["success"])
 }
 @Test fun sourceBackedToolAlsoPreservesProducerOutput() { val raw="y".repeat(18000)+"END";assertTrue(raw==through("write_file",raw,true)) }
 @Test fun unicodeOutputDoesNotSplitAtOldBoundary() { val raw="x".repeat(11999)+"😀END"; assertTrue(raw==through("custom_fixture",raw)) }
 private fun decorate(result:String, context:String, tool:String="custom_fixture"):String {
  val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)) { _,_,_ -> throw AssertionError("No terminal invocation") } as ITerminalSession
  val bridge=AgentHookLifecycleBridge(HookManager(java.io.File("."),terminal,PermissionEngine(emptyList()),ApprovalManager()),{"fixture"},{AgentMode.BUILD})
  val method=AgentHookLifecycleBridge::class.java.getDeclaredMethod("decorate",String::class.java,String::class.java,Array<HookDispatchResult>::class.java)
  method.isAccessible=true
  return method.invoke(bridge,tool,result,arrayOf(HookDispatchResult(additionalContext=context))) as String
 }
 @Test fun additionalContextBudgetDoesNotConsumeProducerBytes() {
  val raw="x".repeat(20000)+"END";val result=decorate(raw,"c".repeat(8000))
  assertTrue(result.startsWith(raw));assertEquals(raw.length+"\n[hook context]\n".length+HookLifecycleProtocol.MAX_CONTEXT_CHARS,result.length)
 }
 @Test fun commandEnvelopeBehaviorIsUnchanged() {
  val raw="exit=0\n"+"x".repeat(18000);val context="c".repeat(8000)
  assertEquals(AgentCommandSessions.appendHookContext(raw,context),decorate(raw,context,"run_command"))
 }
 @Test fun whitespaceContextReturnsOriginalReference() {
  val raw="x".repeat(18000)
  assertTrue("blank-context fastpath must return same reference",raw === decorate(raw,"   "))
 }
 @Test fun cancellationPropagatesWithoutPublication() { assertThrows(kotlinx.coroutines.CancellationException::class.java){through("custom_fixture","must not publish",cancel=true)} }
 @Test fun actualCustomManagerProducerSurvivesLifecycleBridge() = runBlocking {
  val root=Files.createTempDirectory("custom-hook-output").toFile()
  try {
   root.resolve(".droide/tools").mkdirs()
   root.resolve(".droide/tools/fixture.sh").writeText("# in-memory test only\n")
   var executions=0
   val payload="x".repeat(18000)+"END"
   val terminal=Proxy.newProxyInstance(ITerminalSession::class.java.classLoader,arrayOf(ITerminalSession::class.java)) { _,method,_ ->
    if(method.name=="execArgv"){executions++;ExecResult(0,payload,false)}else throw AssertionError("Unexpected terminal method")
   } as ITerminalSession
   val custom=CustomToolManager(root)
   val bridge=AgentHookLifecycleBridge(HookManager(root,terminal,PermissionEngine(emptyList()),ApprovalManager()),{"fixture"},{AgentMode.BUILD})
   val result=bridge.tool("custom_fixture","{}"){custom.execute("fixture","{}",terminal)}
   assertEquals(1,executions)
   assertTrue("custom output silently changed",result=="exit=0\n"+payload)
  } finally { PathSecurity.deleteTreeNoFollow(root) }
 }
 @Test fun shortResultUnchanged() { assertEquals("ok",through("read_file","ok")) }
}
