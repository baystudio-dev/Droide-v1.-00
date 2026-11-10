package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class PluginToolApprovalInputTest {
 @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
 private class Terminal:ITerminalSession by AgentWorkflowTerminal(){
  val inputs=mutableListOf<String>()
  override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult{inputs.add(argv.last());return ExecResult(0,"ok",false)}
 }
 private fun fixture(block:suspend CoroutineScope.(File,Terminal,AgentTools.Ctx,AgentPluginToolDef)->Unit)=runBlocking{
  val root=Files.createTempDirectory("plugin-review").toFile()
  try{
   val plugin=root.resolve("plugin");plugin.mkdirs();plugin.resolve("tools.json").writeText("""{"tools":[{"name":"inspect","command":["fixture"]}]}""")
   val source=AgentPluginSource{listOf(AgentPluginContributionRoot("fixture","Fixture","1","",AgentPluginFormat.DROIDE,plugin,"fixture-sha"))}
   val terminal=Terminal();val base=agentWorkflowContext(root,terminal=terminal,provenance=AgentRequestProvenance.primary("plugin-review"),permissions=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK),PermRule("shell_escape","*",PermEffect.ASK))))
   val c=AgentTools.Ctx(base.files,terminal,base.git,base.approvals,base.questions,base.history,base.perms,base.mode,base.llm,base.llmConfig,root,base.todos,base.sessionId,base.lsp,provenance=base.provenance,pluginSource=source)
   withTimeout(15000){block(root,terminal,c,AgentPluginToolManager(root,source).discover().single())}
  }finally{root.deleteRecursively()}
 }
 private suspend fun pending(c:AgentTools.Ctx,last:Long=-1)=withTimeout(5000){c.approvals.pending.filterNotNull().first{it.id!=last}}
 private fun full(r:ApprovalInputReview)=buildString{var n=0;while(true){val p=r.readPage(n++);append(p.text);if(!p.hasNext)break}}
 @Test fun bothPluginGatesExposeCompleteJsonAndExecuteSamePayload()=fixture{root,terminal,c,def->
  val args=buildJsonObject{put("text","😀".repeat(5000)+"\nEND")}
  val task=async{AgentTools.execute(def.functionName,args,c)}
  var prev=-1L
  try{
   for(action in listOf("shell_escape","shell")){
    val request=pending(c,prev);prev=request.id;val review=requireNotNull(request.inputReview)
    assertEquals(action,request.action);assertEquals(args.toString(),full(review))
    assertTrue(review.optionsSummary.contains(def.fingerprint));assertTrue(review.optionsSummary.contains(root.absolutePath))
    assertEquals(AgentPluginToolManager(root,c.pluginSource).permissionResource(def,args),request.resource)
    c.approvals.resolve(request.id,ApprovalResolution.ALLOW_ONCE);assertTrue(runCatching{full(review)}.isFailure)
   }
   assertEquals("exit=0\nok",task.await());assertEquals(listOf(args.toString()),terminal.inputs)
  }finally{task.cancelAndJoin()}
 }
 @Test fun deniedPluginInputDoesNotExecute()=fixture{_,terminal,c,def->
  val task=async{AgentTools.execute(def.functionName,buildJsonObject{put("text","x".repeat(15000))},c)}
  val request=pending(c)
  try{assertNotNull(request.inputReview)}finally{c.approvals.resolve(request.id,ApprovalResolution.DENY)}
  assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.inputs.isEmpty())
 }

 @Test fun callerMutationCannotChangeReviewedPayload()=fixture{root,terminal,c,def->
  val nested=linkedMapOf<String,JsonElement>("value" to JsonPrimitive("original"))
  val args=JsonObject(mapOf("nested" to JsonObject(nested)));val original=args.toString()
  val expectedResource=AgentPluginToolManager(root,c.pluginSource).permissionResource(def,args)
  val task=async{AgentTools.execute(def.functionName,args,c)}
  try{
   val first=pending(c);nested["value"]=JsonPrimitive("mutated")
   c.approvals.resolve(first.id,ApprovalResolution.ALLOW_ONCE)
   val second=pending(c,first.id)
   assertEquals(expectedResource,second.resource);assertEquals(original,full(requireNotNull(second.inputReview)))
   c.approvals.resolve(second.id,ApprovalResolution.ALLOW_ONCE);task.await()
   assertEquals(listOf(original),terminal.inputs)
  }finally{task.cancelAndJoin()}
 }
 @Test fun cancellationExpiresPluginReader()=fixture{_,terminal,c,def->
  val task=async{AgentTools.execute(def.functionName,buildJsonObject{put("text","original")},c)}
  val request=pending(c)
  try{assertNotNull(request.inputReview)}finally{task.cancelAndJoin()}
  assertTrue(runCatching{full(requireNotNull(request.inputReview))}.isFailure);assertTrue(terminal.inputs.isEmpty())
 }
 @Test fun ownerRevocationDeniesPluginExecution()=fixture{_,terminal,c,def->
  var current=true;c.localRecoveryOwner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
  val task=async{AgentTools.execute(def.functionName,buildJsonObject{put("text","original")},c)}
  try{
   val request=pending(c);val review=requireNotNull(request.inputReview);current=false
   assertTrue(runCatching{full(review)}.isFailure)
   c.approvals.resolve(request.id,ApprovalResolution.ALLOW_ONCE)
   assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.inputs.isEmpty())
  }finally{task.cancelAndJoin()}
 }
 @Test fun stalePluginDefinitionStillRejected()=fixture{root,terminal,c,def->
  val task=async{AgentTools.execute(def.functionName,buildJsonObject{},c)}
  val first=pending(c);c.approvals.resolve(first.id,ApprovalResolution.ALLOW_ONCE)
  val second=pending(c,first.id)
  root.resolve("plugin/tools.json").writeText("""{"tools":[{"name":"inspect","command":["changed"]}]}""")
  c.approvals.resolve(second.id,ApprovalResolution.ALLOW_ONCE)
  assertTrue(task.await().startsWith("ERROR"));assertTrue(terminal.inputs.isEmpty())
 }
 @Test fun exactInputSessionGrantsRemainAvailableButChangedInputAsksAgain()=fixture{_,terminal,c,def->
  val args=buildJsonObject{put("text","first")}
  val task=async{AgentTools.execute(def.functionName,args,c)}
  val first=pending(c);assertTrue(first.canAllowForSession);c.approvals.resolve(first.id,ApprovalResolution.ALLOW_SESSION)
  val second=pending(c,first.id);assertTrue(second.canAllowForSession);c.approvals.resolve(second.id,ApprovalResolution.ALLOW_SESSION)
  task.await();AgentTools.execute(def.functionName,args,c);assertEquals(2,terminal.inputs.size)
  val changed=async{AgentTools.execute(def.functionName,buildJsonObject{put("text","second")},c)}
  try{val third=pending(c,second.id);assertNotEquals(first.resource,third.resource);assertNotNull(third.inputReview)}finally{changed.cancelAndJoin()}
 }
 @Test fun savedAllowRemainsLazy()=fixture{root,terminal,original,def->
  val base=agentWorkflowContext(root,terminal=terminal)
  val c=AgentTools.Ctx(base.files,terminal,base.git,base.approvals,base.questions,base.history,base.perms,base.mode,base.llm,base.llmConfig,root,base.todos,base.sessionId,base.lsp,pluginSource=original.pluginSource)
  val args=buildJsonObject{put("text","small\uD800")}
  assertEquals("exit=0\nok",AgentTools.execute(def.functionName,args,c))
  assertEquals(listOf(args.toString()),terminal.inputs);assertNull(c.approvals.pending.value)
 }
}
