package com.baystudio.droide.core
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class CustomToolApprovalInputTest {
 @get:org.junit.Rule val applicationWork:org.junit.rules.TestRule=ApplicationWorkAdmissionFixture()
 private class Terminal:ITerminalSession by AgentWorkflowTerminal() {
  val inputs=mutableListOf<String>()
  override suspend fun execArgv(argv:List<String>,timeoutMs:Long):ExecResult {inputs.add(argv.last());return ExecResult(0,"ok",false)}
 }
 private fun full(r:ApprovalInputReview)=buildString {var i=0;while(true){val p=r.readPage(i++);append(p.text);if(!p.hasNext)break}}
 private fun fixture(block:suspend CoroutineScope.(File,Terminal,AgentTools.Ctx)->Unit)=runBlocking {
  val root=Files.createTempDirectory("custom-review").toFile()
  try {
   root.resolve(".droide/tools").mkdirs();root.resolve(".droide/tools/fixture.sh").writeText("# never run")
   val terminal=Terminal()
   val c=agentWorkflowContext(root,terminal=terminal,provenance=AgentRequestProvenance.primary("custom-review"),
    permissions=PermissionEngine(listOf(PermRule("*","*",PermEffect.ALLOW),PermRule("shell","*",PermEffect.ASK),PermRule("shell_escape","*",PermEffect.ASK))))
   withTimeout(15000){block(root,terminal,c)}
  } finally {root.deleteRecursively()}
 }
 private suspend fun pending(c:AgentTools.Ctx,prev:Long=-1)=withTimeout(5000){c.approvals.pending.filterNotNull().first{it.id!=prev}}
 @Test fun bothGatesExposeExactOriginalInputAndReaderExpires()=fixture {root,terminal,c ->
  val input="😀".repeat(6000)+"\nTAIL"
  val task=async{AgentTools.execute("custom_fixture",buildJsonObject{put("input",input)},c)}
  var prev=-1L
  for(action in listOf("shell_escape","shell")){
   val r=pending(c,prev);prev=r.id
   try {
    val review=requireNotNull(r.inputReview);assertEquals(action,r.action);assertEquals(input,full(review))
    assertEquals("Tool input",review.label.title)
    assertTrue(review.optionsSummary.contains(root.absolutePath))
    assertTrue(review.optionsSummary.contains(CustomToolManager(root).permissionFingerprint("fixture")!!))
    if(action=="shell")assertFalse(r.canAllowForSession)
    c.approvals.resolve(r.id,ApprovalResolution.ALLOW_ONCE)
    assertTrue(runCatching{full(review)}.isFailure)
   } catch(t:Throwable){task.cancel();throw t}
  }
  assertEquals("exit=0\nok".replace("\\n","\n"),task.await())
  assertEquals(listOf(input),terminal.inputs)
 }
 @Test fun denialNeverExecutes()=fixture {_,terminal,c ->
  val task=async{AgentTools.execute("custom_fixture",buildJsonObject{put("input","x".repeat(20000))},c)}
  val r=pending(c)
  try{assertNotNull(r.inputReview)}finally{c.approvals.resolve(r.id,ApprovalResolution.DENY)}
  assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.inputs.isEmpty())
 }
 @Test fun cancellationInvalidatesFullReader()=fixture {_,terminal,c ->
  val task=async{AgentTools.execute("custom_fixture",buildJsonObject{put("input","x".repeat(20000))},c)}
  val r=pending(c)
  try{assertNotNull(r.inputReview)}finally{task.cancelAndJoin()}
  assertTrue(runCatching{full(requireNotNull(r.inputReview))}.isFailure);assertTrue(terminal.inputs.isEmpty())
 }
 @Test fun fallbackJsonInputAlsoReviewable()=fixture {_,_,c ->
  val args=buildJsonObject{put("payload","z".repeat(16000))}
  val task=async{AgentTools.execute("custom_fixture",args,c)}
  val r=pending(c)
  try{assertEquals(args.toString(),full(requireNotNull(r.inputReview)))}finally{task.cancelAndJoin()}
 }

 @Test fun previewCollisionCannotReuseSessionApproval()=fixture {_,terminal,c ->
  var last=-1L
  for(suffix in listOf("FIRST","SECOND")){
   val input="x".repeat(20000)+suffix
   val task=async{AgentTools.execute("custom_fixture",buildJsonObject{put("input",input)},c)}
   val escape=pending(c,last);last=escape.id
   c.approvals.resolve(escape.id,ApprovalResolution.ALLOW_ONCE)
   val shell=pending(c,last);last=shell.id
   try{
    assertFalse(shell.canAllowForSession)
    assertFalse(c.approvals.resolve(shell.id,ApprovalResolution.ALLOW_SESSION))
    assertEquals(input,full(requireNotNull(shell.inputReview)))
    c.approvals.resolve(shell.id,ApprovalResolution.ALLOW_ONCE)
    task.await()
   }finally{task.cancelAndJoin()}
  }
  assertEquals(2,terminal.inputs.size)
  assertTrue(terminal.inputs[0].endsWith("FIRST"));assertTrue(terminal.inputs[1].endsWith("SECOND"))
 }
 @Test fun scriptChangeDuringReviewStillRejected()=fixture {root,terminal,c ->
  val task=async{runCatching{AgentTools.execute("custom_fixture",buildJsonObject{put("input","x".repeat(20000))},c)}}
  val a=pending(c);c.approvals.resolve(a.id,ApprovalResolution.ALLOW_ONCE)
  val b=pending(c,a.id);root.resolve(".droide/tools/fixture.sh").appendText(" changed")
  c.approvals.resolve(b.id,ApprovalResolution.ALLOW_ONCE)
  val result=task.await();assertTrue(result.isFailure || result.getOrNull()?.startsWith("ERROR") == true);assertTrue(terminal.inputs.isEmpty())
 }
 @Test fun ownerRevocationExpiresReviewAndDeniesExecution()=fixture {_,terminal,c ->
  var current=true
  c.localRecoveryOwner=HookInvocationAuthority(c.mode,c.provenance,c.permissionScope,{current})
  val task=async{AgentTools.execute("custom_fixture",buildJsonObject{put("input","long".repeat(5000))},c)}
  val r=pending(c)
  try{
   val review=requireNotNull(r.inputReview);current=false
   assertTrue(runCatching{full(review)}.isFailure)
   c.approvals.resolve(r.id,ApprovalResolution.ALLOW_ONCE)
   assertTrue(task.await().startsWith("DENIED"));assertTrue(terminal.inputs.isEmpty())
  }finally{task.cancelAndJoin()}
 }
 @Test fun savedAllowPolicyRemainsLazyAndExact()=fixture {root,terminal,_ ->
  val c=agentWorkflowContext(root,terminal=terminal)
  val input="x".repeat(20000)+"\uD800"
  AgentTools.execute("custom_fixture",buildJsonObject{put("input",input)},c)
  assertEquals(listOf(input),terminal.inputs);assertNull(c.approvals.pending.value)
 }
}
